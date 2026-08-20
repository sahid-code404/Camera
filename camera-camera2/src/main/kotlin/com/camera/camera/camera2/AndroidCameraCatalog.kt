package com.camera.camera.camera2

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import android.util.Size
import com.camera.camera.api.CameraCatalog
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.capability.ValuableCameraResolver
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.CameraRouteKind
import com.camera.core.model.CameraStreamCapabilities
import com.camera.core.model.FloatValueRange
import com.camera.core.model.HighSpeedVideoProfile
import com.camera.core.model.IntValueRange
import com.camera.core.model.LensFacing
import com.camera.core.model.LongValueRange
import com.camera.core.model.PixelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Public-API Camera2 discovery implementation.
 *
 * It intentionally separates raw hardware routes from user-visible ValuableLens resolution. Camera
 * IDs are opaque strings: no numeric ID or OEM table is used to infer optical role.
 */
class AndroidCameraCatalog(
    context: Context,
) : CameraCatalog {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)

    override suspend fun scan(): CameraCatalogSnapshot = withContext(Dispatchers.Default) {
        val enumerated = manager.cameraIdList.mapNotNull { cameraId ->
            runCatching {
                buildProfile(
                    routeCameraId = cameraId,
                    physicalCameraId = null,
                    parentLogicalCameraId = null,
                    routeKind = CameraRouteKind.ENUMERATED,
                    characteristics = manager.getCameraCharacteristics(cameraId),
                    inheritedFacing = null,
                )
            }.getOrNull()
        }

        val physicalMembers = buildList {
            enumerated.forEach { parent ->
                parent.logicalPhysicalIds.forEach { physicalId ->
                    val child = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
                        ?: return@forEach
                    add(
                        buildProfile(
                            routeCameraId = parent.routeCameraId,
                            physicalCameraId = physicalId,
                            parentLogicalCameraId = parent.routeCameraId,
                            routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                            characteristics = child,
                            inheritedFacing = parent.facing,
                        ),
                    )
                }
            }
        }

        val profiles = (enumerated + physicalMembers)
            .distinctBy { "${it.routeCameraId}:${it.physicalCameraId ?: "direct"}" }

        val resolution = ValuableCameraResolver.resolve(profiles)
        CameraCatalogSnapshot(
            deviceProfiles = profiles,
            valuableLenses = resolution.lenses,
            diagnosticsJson = diagnosticsJson(profiles, resolution.hiddenRouteKeys),
        )
    }

    private fun buildProfile(
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
        val capabilityNames = capabilities.mapTo(sortedSetOf(), ::capabilityName)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maxResolutionMap = if (Build.VERSION.SDK_INT >= 31) {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        } else null

        if (streamMap == null) warnings += "No SCALER_STREAM_CONFIGURATION_MAP"

        val facing = facingOf(characteristics.get(CameraCharacteristics.LENS_FACING))
            .takeUnless { it == LensFacing.UNKNOWN }
            ?: inheritedFacing
            ?: LensFacing.UNKNOWN

        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val zoom = if (Build.VERSION.SDK_INT >= 30) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toFloatValueRange()
        } else null
        val iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.toIntValueRange()
        val exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.toLongValueRange()
        val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION).orEmpty()
        val eisModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES).orEmpty()
        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val physicalIds = if (Build.VERSION.SDK_INT >= 28) characteristics.physicalCameraIds else emptySet()

        val normalStreams = streamCapabilities(streamMap)
        val maximumStreams = streamCapabilities(maxResolutionMap)
        val mergedStreams = mergeStreams(normalStreams, maximumStreams)

        val highSpeed = streamMap?.let { map ->
            runCatching {
                map.highSpeedVideoSizes.flatMap { size ->
                    map.getHighSpeedVideoFpsRangesFor(size).map { fps ->
                        HighSpeedVideoProfile(size.toPixelSize(), fps.lower, fps.upper)
                    }
                }
            }.getOrElse {
                warnings += "High-speed configuration query failed: ${it.javaClass.simpleName}"
                emptyList()
            }
        }.orEmpty()

        val fpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { IntValueRange(it.lower, it.upper) }
            .orEmpty()

        val supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        val supportsLogical = Build.VERSION.SDK_INT >= 28 &&
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities

        return CameraDeviceProfile(
            routeCameraId = routeCameraId,
            physicalCameraId = physicalCameraId,
            parentLogicalCameraId = parentLogicalCameraId,
            routeKind = routeKind,
            facing = facing,
            hardwareLevel = hardwareLevelName(characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = capabilityNames,
            logicalPhysicalIds = physicalIds,
            focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.filter { it > 0f }
                .orEmpty(),
            apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.filter { it > 0f }
                .orEmpty(),
            sensorWidthMm = physicalSize?.width,
            sensorHeightMm = physicalSize?.height,
            activeArrayWidth = active?.width(),
            activeArrayHeight = active?.height(),
            minimumFocusDistanceDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            oisAvailable = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON),
            eisAvailable = eisModes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            zoomRatioRange = zoom,
            isoRange = iso,
            exposureTimeRangeNs = exposure,
            maxAnalogIso = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
            aeFpsRanges = fpsRanges,
            streams = mergedStreams.copy(highSpeedVideo = highSpeed),
            cfaArrangement = cfaName(characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)),
            whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            blackLevels = blackPattern?.let { pattern ->
                listOf(
                    pattern.getOffsetForIndex(0, 0),
                    pattern.getOffsetForIndex(1, 0),
                    pattern.getOffsetForIndex(0, 1),
                    pattern.getOffsetForIndex(1, 1),
                )
            }.orEmpty(),
            supportsRaw = supportsRaw,
            supportsManualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            supportsManualPostProcessing =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
            supportsBurstCapture = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            supportsLogicalMultiCamera = supportsLogical,
            supportsPrivateReprocessing =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in capabilities,
            supportsYuvReprocessing =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING in capabilities,
            supportsUltraHighResolution = Build.VERSION.SDK_INT >= 31 &&
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities,
            dynamicRangeProfiles = if (Build.VERSION.SDK_INT >= 33 &&
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES) != null
            ) listOf("AVAILABLE") else emptyList(),
            colorSpaceProfiles = if (Build.VERSION.SDK_INT >= 34 &&
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES) != null
            ) listOf("AVAILABLE") else emptyList(),
            discoveryWarnings = warnings,
        )
    }

    private fun streamCapabilities(map: android.hardware.camera2.params.StreamConfigurationMap?): CameraStreamCapabilities {
        if (map == null) return CameraStreamCapabilities()
        return CameraStreamCapabilities(
            rawSizes = outputSizes(map, ImageFormat.RAW_SENSOR),
            jpegSizes = outputSizes(map, ImageFormat.JPEG),
            heicSizes = if (Build.VERSION.SDK_INT >= 28) outputSizes(map, ImageFormat.HEIC) else emptyList(),
            ultraHdrJpegSizes = if (Build.VERSION.SDK_INT >= 34) outputSizes(map, ImageFormat.JPEG_R) else emptyList(),
            yuvSizes = outputSizes(map, ImageFormat.YUV_420_888),
            privateSizes = outputSizes(map, ImageFormat.PRIVATE),
        )
    }

    private fun outputSizes(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        format: Int,
    ): List<PixelSize> = runCatching {
        map.getOutputSizes(format)
            ?.map(Size::toPixelSize)
            ?.distinct()
            ?.sortedByDescending(PixelSize::area)
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun mergeStreams(a: CameraStreamCapabilities, b: CameraStreamCapabilities): CameraStreamCapabilities =
        CameraStreamCapabilities(
            rawSizes = mergeSizes(a.rawSizes, b.rawSizes),
            jpegSizes = mergeSizes(a.jpegSizes, b.jpegSizes),
            heicSizes = mergeSizes(a.heicSizes, b.heicSizes),
            ultraHdrJpegSizes = mergeSizes(a.ultraHdrJpegSizes, b.ultraHdrJpegSizes),
            yuvSizes = mergeSizes(a.yuvSizes, b.yuvSizes),
            privateSizes = mergeSizes(a.privateSizes, b.privateSizes),
        )

    private fun mergeSizes(a: List<PixelSize>, b: List<PixelSize>): List<PixelSize> =
        (a + b).distinct().sortedByDescending(PixelSize::area)

    private fun diagnosticsJson(
        profiles: List<CameraDeviceProfile>,
        hidden: Set<String>,
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("cameraCount", profiles.size)
        val array = JSONArray()
        profiles.forEach { profile ->
            val key = "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"
            array.put(
                JSONObject()
                    .put("routeCameraId", profile.routeCameraId)
                    .put("physicalCameraId", profile.physicalCameraId ?: JSONObject.NULL)
                    .put("routeKind", profile.routeKind.name)
                    .put("facing", profile.facing.name)
                    .put("hardwareLevel", profile.hardwareLevel)
                    .put("focalLengthsMm", JSONArray(profile.focalLengthsMm))
                    .put("sensorWidthMm", profile.sensorWidthMm ?: JSONObject.NULL)
                    .put("equivalent35Mm", profile.equivalentFocalLength35Mm ?: JSONObject.NULL)
                    .put("raw", profile.supportsRaw)
                    .put("manualSensor", profile.supportsManualSensor)
                    .put("logical", profile.supportsLogicalMultiCamera)
                    .put("physicalIds", JSONArray(profile.logicalPhysicalIds.toList()))
                    .put("maxPhotoPixels", profile.maxPhotoPixels)
                    .put("hiddenByDefault", key in hidden)
                    .put("capabilities", JSONArray(profile.capabilities.toList()))
                    .put("warnings", JSONArray(profile.discoveryWarnings)),
            )
        }
        root.put("cameras", array)
        return root.toString(2)
    }

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
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
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

    private fun Size.toPixelSize(): PixelSize = PixelSize(width, height)
    private fun Range<Int>.toIntValueRange(): IntValueRange = IntValueRange(lower, upper)
    private fun Range<Long>.toLongValueRange(): LongValueRange = LongValueRange(lower, upper)
    private fun Range<Float>.toFloatValueRange(): FloatValueRange = FloatValueRange(lower, upper)
}
