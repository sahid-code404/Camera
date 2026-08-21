package com.camera.camera.camera2

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import com.camera.camera.api.CameraCatalog
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.capability.ValuableCameraResolver
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.CameraRouteKind
import com.camera.core.model.CameraStreamCapabilities
import com.camera.core.model.FloatValueRange
import com.camera.core.model.IntValueRange
import com.camera.core.model.LensFacing
import com.camera.core.model.LongValueRange
import com.camera.core.model.PixelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hybrid camera catalog.
 *
 * Java CameraManager is still used for normal logical/physical Camera2 routing, but discovery is
 * augmented with Android NDK ACameraManager enumeration. This intentionally mirrors MotionCam's
 * architecture because several Qualcomm/Xiaomi framework builds filter auxiliary IDs in the Java
 * CameraManager layer even though the native camera service exposes them to regular applications.
 *
 * Only real RAW-capable NDK cameras are imported. No numeric camera IDs and no JPEG/YUV-as-RAW
 * fallback are used.
 */
class AndroidCameraCatalog(context: Context) : CameraCatalog {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)

    override suspend fun scan(): CameraCatalogSnapshot = scan(deepScan = false)

    suspend fun scan(deepScan: Boolean): CameraCatalogSnapshot = withContext(Dispatchers.Default) {
        val javaIds = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val enumerated = javaIds.mapNotNull { id ->
            runCatching {
                buildJavaProfile(
                    routeCameraId = id,
                    physicalCameraId = null,
                    parentLogicalCameraId = null,
                    routeKind = CameraRouteKind.ENUMERATED,
                    characteristics = manager.getCameraCharacteristics(id),
                    inheritedFacing = null,
                )
            }.getOrNull()
        }

        val ndkDescriptors = NativeCameraNdkBridge.enumerateRawCameras(deepScan = deepScan)
        val ndkById = ndkDescriptors.associateBy { it.id }
        val enrichedEnumerated = enumerated.map { profile ->
            val native = ndkById[profile.routeCameraId]
            when {
                native == null -> profile
                profile.streams.rawSizes.isEmpty() -> profile.copy(
                    streams = profile.streams.copy(
                        rawSizes = listOf(PixelSize(native.rawWidth, native.rawHeight)),
                    ),
                    capabilities = profile.capabilities + setOf("NDK_RAW_ALIAS", "NDK_RAW_PREFERRED"),
                    supportsRaw = true,
                    discoveryWarnings = profile.discoveryWarnings +
                        "Java route has no RAW_SENSOR map; NDK RAW transport preferred",
                )
                else -> profile.copy(
                    capabilities = profile.capabilities + "NDK_RAW_ALIAS",
                )
            }
        }

        val physicalMembers = buildList {
            enrichedEnumerated.forEach { parent ->
                parent.logicalPhysicalIds.forEach physical@ { physicalId ->
                    val chars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
                        ?: return@physical
                    var child = buildJavaProfile(
                        routeCameraId = parent.routeCameraId,
                        physicalCameraId = physicalId,
                        parentLogicalCameraId = parent.routeCameraId,
                        routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                        characteristics = chars,
                        inheritedFacing = parent.facing,
                    )
                    val childHadNoPhotoStreams = child.maxPhotoPixels == 0L

                    // API 29+ physical cameras may omit a standalone stream configuration map while
                    // still being routable through the logical parent. Preserve the parent's stream
                    // declarations as candidates and let the real session decide whether that
                    // physical route works.
                    val parentHasFrameworkRaw =
                        parent.streams.rawSizes.isNotEmpty() &&
                            "NDK_RAW_PREFERRED" !in parent.capabilities
                    if (child.streams.rawSizes.isEmpty() && parentHasFrameworkRaw) {
                        child = child.copy(
                            streams = child.streams.copy(rawSizes = parent.streams.rawSizes),
                            supportsRaw = true,
                            discoveryWarnings = child.discoveryWarnings +
                                "Inherited logical-parent RAW_SENSOR candidates",
                        )
                    }
                    if (childHadNoPhotoStreams && parent.maxPhotoPixels > 0L) {
                        child = child.copy(
                            streams = parent.streams,
                            supportsRaw = child.supportsRaw || parentHasFrameworkRaw,
                            discoveryWarnings = child.discoveryWarnings +
                                "Inherited logical-parent stream candidates",
                        )
                    }
                    add(child)
                }
            }
        }

        val javaRouteKeys = (enrichedEnumerated + physicalMembers)
            .mapTo(mutableSetOf()) { routeKey(it) }

        // MotionCam-style path. Unlike Java CameraManager.getCameraIdList(), the NDK client can see
        // auxiliary IDs on a number of Qualcomm vendor frameworks. Keep only IDs with a genuine
        // RAW10/RAW12/RAW16 stream. Deep scan is metadata-only and never opens camera devices.
        val ndkProfiles = ndkDescriptors
            .map(::buildNdkProfile)
            .filterNot { routeKey(it) in javaRouteKeys }

        val profiles = (enrichedEnumerated + physicalMembers + ndkProfiles)
            .distinctBy(::routeKey)

        val resolution = ValuableCameraResolver.resolve(profiles)
        CameraCatalogSnapshot(
            deviceProfiles = profiles,
            valuableLenses = resolution.lenses,
            diagnosticsJson = diagnosticsJson(
                profiles = profiles,
                hidden = resolution.hiddenRouteKeys,
                javaIds = javaIds,
                ndkIds = ndkProfiles.map { it.routeCameraId },
            ),
        )
    }

    private fun buildJavaProfile(
        routeCameraId: String,
        physicalCameraId: String?,
        parentLogicalCameraId: String?,
        routeKind: CameraRouteKind,
        characteristics: CameraCharacteristics,
        inheritedFacing: LensFacing?,
    ): CameraDeviceProfile {
        val warnings = mutableListOf<String>()
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) warnings += "No standalone stream configuration map"
        val streams = streamCapabilities(map)
        val facing = facingOf(characteristics.get(CameraCharacteristics.LENS_FACING))
            .takeUnless { it == LensFacing.UNKNOWN }
            ?: inheritedFacing
            ?: LensFacing.UNKNOWN
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val black = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)

        return CameraDeviceProfile(
            routeCameraId = routeCameraId,
            physicalCameraId = physicalCameraId,
            parentLogicalCameraId = parentLogicalCameraId,
            routeKind = routeKind,
            facing = facing,
            hardwareLevel = hardwareLevelName(
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            capabilities = capabilities.mapTo(sortedSetOf()) { capabilityName(it) },
            logicalPhysicalIds = characteristics.physicalCameraIds,
            focalLengthsMm = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.filter { it > 0f }
                .orEmpty(),
            apertures = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.filter { it > 0f }
                .orEmpty(),
            sensorWidthMm = sensor?.width,
            sensorHeightMm = sensor?.height,
            activeArrayWidth = active?.width(),
            activeArrayHeight = active?.height(),
            minimumFocusDistanceDiopters =
                characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            zoomRatioRange = if (android.os.Build.VERSION.SDK_INT >= 30) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toFloatRange()
            } else null,
            isoRange = characteristics
                .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.toIntRange(),
            exposureTimeRangeNs = characteristics
                .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.toLongRange(),
            streams = streams,
            cfaArrangement = cfaName(
                characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            ),
            whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            blackLevels = black?.let {
                listOf(
                    it.getOffsetForIndex(0, 0),
                    it.getOffsetForIndex(1, 0),
                    it.getOffsetForIndex(0, 1),
                    it.getOffsetForIndex(1, 1),
                )
            }.orEmpty(),
            // A concrete RAW_SENSOR stream declaration is accepted even when a Qualcomm physical
            // metadata block forgets to repeat the RAW capability bit.
            supportsRaw = streams.rawSizes.isNotEmpty(),
            supportsManualSensor =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            supportsManualPostProcessing =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
            supportsBurstCapture =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            supportsLogicalMultiCamera =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities,
            supportsPrivateReprocessing =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in capabilities,
            supportsYuvReprocessing =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING in capabilities,
            supportsUltraHighResolution = android.os.Build.VERSION.SDK_INT >= 31 &&
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
            discoveryWarnings = warnings,
        )
    }

    private fun buildNdkProfile(item: NativeCameraNdkBridge.Descriptor): CameraDeviceProfile {
        val rawSize = PixelSize(item.rawWidth, item.rawHeight)
        val previewSize = PixelSize(item.previewWidth, item.previewHeight)
        return CameraDeviceProfile(
            routeCameraId = item.id,
            routeKind = CameraRouteKind.ENUMERATED,
            facing = facingOf(item.facing),
            hardwareLevel = item.hardware,
            capabilities = buildSet {
                add("RAW")
                add("BACKWARD_COMPATIBLE")
                if (item.manualSensor) add("MANUAL_SENSOR")
                if (item.burstCapture) add("BURST_CAPTURE")
                add("NDK_ENUMERATED")
                add("NDK_RAW_PREFERRED")
            },
            focalLengthsMm = item.focalLengthMm?.let(::listOf).orEmpty(),
            sensorWidthMm = item.sensorWidthMm,
            sensorHeightMm = item.sensorHeightMm,
            activeArrayWidth = item.activeWidth,
            activeArrayHeight = item.activeHeight,
            isoRange = if (item.isoMin != null && item.isoMax != null) {
                IntValueRange(item.isoMin, item.isoMax)
            } else null,
            exposureTimeRangeNs = if (item.exposureMinNs != null && item.exposureMaxNs != null) {
                LongValueRange(item.exposureMinNs, item.exposureMaxNs)
            } else null,
            streams = CameraStreamCapabilities(
                rawSizes = listOf(rawSize),
                privateSizes = listOf(previewSize),
            ),
            cfaArrangement = cfaName(item.cfaArrangement),
            whiteLevel = item.whiteLevel,
            blackLevels = item.blackLevels,
            supportsRaw = true,
            supportsManualSensor = item.manualSensor,
            supportsBurstCapture = item.burstCapture,
            discoveryWarnings = listOf(
                "Discovered through Android NDK camera service (MotionCam-style auxiliary path)",
            ),
        )
    }

    private fun streamCapabilities(map: StreamConfigurationMap?): CameraStreamCapabilities {
        if (map == null) return CameraStreamCapabilities()
        return CameraStreamCapabilities(
            rawSizes = outputSizes(map, ImageFormat.RAW_SENSOR),
            jpegSizes = outputSizes(map, ImageFormat.JPEG),
            heicSizes = outputSizes(map, ImageFormat.HEIC),
            yuvSizes = outputSizes(map, ImageFormat.YUV_420_888),
            privateSizes = outputSizes(map, ImageFormat.PRIVATE),
        )
    }

    private fun outputSizes(map: StreamConfigurationMap, format: Int): List<PixelSize> = runCatching {
        map.getOutputSizes(format)
            ?.map { PixelSize(it.width, it.height) }
            ?.filter { it.width > 0 && it.height > 0 }
            ?.distinct()
            ?.sortedByDescending { it.area }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun diagnosticsJson(
        profiles: List<CameraDeviceProfile>,
        hidden: Set<String>,
        javaIds: List<String>,
        ndkIds: List<String>,
    ): String {
        val cameras = JSONArray()
        profiles.forEach { profile ->
            cameras.put(
                JSONObject()
                    .put("routeCameraId", profile.routeCameraId)
                    .put("physicalCameraId", profile.physicalCameraId ?: JSONObject.NULL)
                    .put("facing", profile.facing.name)
                    .put("hardwareLevel", profile.hardwareLevel)
                    .put("raw", profile.supportsRaw)
                    .put("rawSizes", JSONArray(profile.streams.rawSizes.map { "${it.width}x${it.height}" }))
                    .put("focalLengthsMm", JSONArray(profile.focalLengthsMm))
                    .put("hiddenByResolver", routeKey(profile) in hidden)
                    .put("capabilities", JSONArray(profile.capabilities.toList()))
                    .put("warnings", JSONArray(profile.discoveryWarnings)),
            )
        }
        return JSONObject()
            .put("schemaVersion", 2)
            .put("javaCameraIds", JSONArray(javaIds))
            .put("nativeAuxCameraIds", JSONArray(ndkIds))
            .put("nativeBridgeLoaded", NativeCameraNdkBridge.available)
            .put("cameraCount", profiles.size)
            .put("cameras", cameras)
            .toString(2)
    }

    private fun routeKey(profile: CameraDeviceProfile): String =
        "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

    private fun facingOf(value: Int?): LensFacing = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
        else -> LensFacing.UNKNOWN
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun capabilityName(value: Int): String = when (value) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        else -> "CAPABILITY_$value"
    }

    private fun cfaName(value: Int?): String? = when (value) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
        else -> null
    }

    private fun Range<Int>.toIntRange() = IntValueRange(lower, upper)
    private fun Range<Long>.toLongRange() = LongValueRange(lower, upper)
    private fun Range<Float>.toFloatRange() = FloatValueRange(lower, upper)
}
