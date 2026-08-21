package com.camera.feature.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Size
import com.camera.core.model.PhotoLensSettings
import com.camera.core.model.ValuableLens
import com.camera.processing.raw.FusedDngWriter
import com.camera.processing.raw.NativeComputationalRawEngine
import com.camera.processing.raw.RawFrame
import com.camera.processing.raw.RawFrameStager
import com.camera.processing.raw.StagedRawPayload
import com.camera.processing.raw.pairWith
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.pow

/**
 * Owns only the short RAW acquisition transaction.
 *
 * Full-frame pixel processing is native C++ in :processing-raw. Android Camera2 is used only to
 * obtain genuine RAW_SENSOR buffers and matching capture metadata.
 *
 * Physical auxiliary cameras are allowed to use a RAW stream configuration exposed by their
 * logical parent. The ImageReader is still RAW_SENSOR and OutputConfiguration.setPhysicalCameraId()
 * routes that RAW surface to the selected physical sensor. If the HAL rejects that combination,
 * capture fails explicitly rather than falling back to JPEG/YUV.
 *
 * Burst length is light-adaptive. Bright scenes use four samples; progressively darker/high-ISO
 * scenes use up to eight. This keeps daytime latency/memory low while giving low-light fusion more
 * independent sensor samples without an always-on full-resolution RAW ring buffer.
 */
internal class ComputationalRawCaptureCoordinator(
    context: android.content.Context,
    private val cameraHandler: Handler,
    private val imageHandler: Handler,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val executor = Executor { command -> cameraHandler.post(command) }
    private val scratchDir = File(appContext.cacheDir, "computational-raw")
    private var generation = 0L
    private var active: BurstState? = null

    fun supports(characteristics: CameraCharacteristics): Boolean {
        // PHOTO lens eligibility is already decided by the route-aware, runtime-validated catalog.
        // A physical member can legitimately have no standalone RAW stream map while its logical
        // parent owns the RAW stream that is assigned to this physical camera.
        return true
    }

    fun capture(
        camera: CameraDevice,
        lens: ValuableLens,
        captureCharacteristics: CameraCharacteristics,
        latestPreviewResult: TotalCaptureResult?,
        settings: PhotoLensSettings,
        targetAspect: Float,
        onAcquisitionFinished: () -> Unit,
        onProcessing: (Int) -> Unit,
        onSaved: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel(notify = false)
        scratchDir.mkdirs()
        scratchDir.listFiles()
            ?.filter {
                it.name.startsWith("camera_raw_") ||
                    it.name.startsWith("camera_fused_") ||
                    it.name.startsWith("camera_native_")
            }
            ?.forEach { runCatching { it.delete() } }

        val rawStreamCharacteristics = resolveRawStreamCharacteristics(lens, captureCharacteristics)
        val rawSize = chooseRawSize(rawStreamCharacteristics)
            ?: run {
                onError("Selected lens and logical parent expose no RAW_SENSOR size")
                return
            }
        val sensorCharacteristics = chooseSensorMetadataCharacteristics(
            preferred = captureCharacteristics,
            fallback = rawStreamCharacteristics,
        )
        val expectedFrames = adaptiveFrameCount(
            previewResult = latestPreviewResult,
            characteristics = captureCharacteristics,
        )

        val myGeneration = ++generation
        val reader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            expectedFrames + RAW_READER_HEADROOM,
        )
        val state = BurstState(
            generation = myGeneration,
            lens = lens,
            characteristics = sensorCharacteristics,
            settings = settings,
            targetAspect = targetAspect.takeIf { it.isFinite() && it > 0f } ?: 4f / 3f,
            expectedFrames = expectedFrames,
            reader = reader,
            onAcquisitionFinished = onAcquisitionFinished,
            onProcessing = onProcessing,
            onSaved = onSaved,
            onError = onError,
        )
        active = state

        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            val payload = try {
                RawFrameStager.stage(image, scratchDir)
            } catch (error: Throwable) {
                fail(state, error.message ?: "Unable to stage RAW frame")
                null
            } finally {
                image.close()
            } ?: return@setOnImageAvailableListener

            synchronized(state.lock) {
                if (!isActive(state)) {
                    payload.file.delete()
                    return@synchronized
                }
                state.payloads[payload.timestampNs] = payload
                pairLocked(state, payload.timestampNs)
            }
            maybeStartProcessing(state)
        }, imageHandler)

        val output = OutputConfiguration(reader.surface)
        lens.physicalCameraId?.let(output::setPhysicalCameraId)
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isActive(state)) {
                        session.close()
                        return
                    }
                    state.session = session
                    runCatching {
                        val requests = buildRequests(
                            camera = camera,
                            surface = reader.surface,
                            characteristics = captureCharacteristics,
                            previewResult = latestPreviewResult,
                            settings = settings,
                            frameCount = expectedFrames,
                        )
                        session.captureBurst(
                            requests,
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult,
                                ) {
                                    if (!isActive(state)) return
                                    val physical = physicalResult(result, lens.physicalCameraId)
                                    val timestamp = physical.get(CaptureResult.SENSOR_TIMESTAMP)
                                        ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                                    if (timestamp == null) {
                                        fail(state, "RAW capture result has no sensor timestamp")
                                        return
                                    }
                                    // Some physical-result payloads omit AWB/color transforms even
                                    // though the logical result contains valid calibration for the
                                    // same exposure. Prefer complete physical metadata, otherwise use
                                    // the logical result rather than inventing calibration values.
                                    val metadata = if (
                                        physical.get(CaptureResult.COLOR_CORRECTION_GAINS) != null &&
                                        physical.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) != null
                                    ) {
                                        physical
                                    } else {
                                        result
                                    }
                                    synchronized(state.lock) {
                                        if (!isActive(state)) return@synchronized
                                        state.results[timestamp] = metadata
                                        pairLocked(state, timestamp)
                                    }
                                    maybeStartProcessing(state)
                                }

                                override fun onCaptureFailed(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    failure: CaptureFailure,
                                ) {
                                    fail(state, "RAW burst failed (${failure.reason})")
                                }

                                override fun onCaptureSequenceCompleted(
                                    session: CameraCaptureSession,
                                    sequenceId: Int,
                                    frameNumber: Long,
                                ) {
                                    finishAcquisition(state)
                                }

                                override fun onCaptureSequenceAborted(
                                    session: CameraCaptureSession,
                                    sequenceId: Int,
                                ) {
                                    fail(state, "RAW burst was aborted")
                                }
                            },
                            cameraHandler,
                        )
                    }.onFailure { error ->
                        fail(state, error.message ?: "Unable to submit RAW burst")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail(
                        state,
                        if (lens.physicalCameraId != null) {
                            "HAL rejected RAW_SENSOR routing to physical lens ${lens.physicalCameraId}"
                        } else {
                            "Camera rejected RAW capture session"
                        },
                    )
                }
            },
        )

        cameraHandler.postDelayed({
            if (isActive(state) && !state.processingStarted) {
                fail(state, "RAW burst timed out")
            }
        }, RAW_TIMEOUT_MS)

        runCatching { camera.createCaptureSession(config) }
            .onFailure { error ->
                fail(
                    state,
                    error.message ?: if (lens.physicalCameraId != null) {
                        "Unable to create physical RAW_SENSOR session"
                    } else {
                        "Unable to create RAW session"
                    },
                )
            }
    }

    fun cancel() = cancel(notify = false)

    private fun cancel(notify: Boolean) {
        val state = active ?: return
        generation++
        active = null
        cleanup(state)
        if (notify) state.onError("RAW capture cancelled")
    }

    private fun buildRequests(
        camera: CameraDevice,
        surface: android.view.Surface,
        characteristics: CameraCharacteristics,
        previewResult: TotalCaptureResult?,
        settings: PhotoLensSettings,
        frameCount: Int,
    ): List<CaptureRequest> {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val manual = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val maxFrameDuration = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        val previewIso = previewResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: isoRange?.lower ?: 100
        val previewExposure = previewResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?: exposureRange?.lower?.coerceAtLeast(DEFAULT_EXPOSURE_NS)
            ?: DEFAULT_EXPOSURE_NS
        val iso = if (isoRange != null) previewIso.coerceIn(isoRange.lower, isoRange.upper) else previewIso
        val baseExposure = if (exposureRange != null) {
            previewExposure.coerceIn(exposureRange.lower, exposureRange.upper)
        } else {
            previewExposure
        }
        val focusDistance = previewResult?.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val aeLock = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbLock = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        val lensShadingModes = characteristics
            .get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
            ?: intArrayOf()

        val hdr = settings.hdrEnabled ?: true
        val strength = (settings.hdrStrength ?: 0.72f).coerceIn(0f, 2f)
        val offsets = List(frameCount) { index ->
            if (!hdr || !manual) {
                0f
            } else {
                when (index) {
                    0 -> -2.0f * strength
                    1 -> -1.0f * strength
                    else -> 0f
                }
            }
        }

        return offsets.map { ev ->
            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if (manual) {
                    val requestedExposure = (baseExposure.toDouble() * 2.0.pow(ev.toDouble())).toLong()
                    val exposure = if (exposureRange != null) {
                        requestedExposure.coerceIn(exposureRange.lower, exposureRange.upper)
                    } else {
                        requestedExposure.coerceAtLeast(1L)
                    }
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    maxFrameDuration?.let { maxDuration ->
                        set(
                            CaptureRequest.SENSOR_FRAME_DURATION,
                            max(exposure + FRAME_MARGIN_NS, exposure).coerceAtMost(maxDuration),
                        )
                    }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    focusDistance?.let { set(CaptureRequest.LENS_FOCUS_DISTANCE, it.coerceAtLeast(0f)) }
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    if (aeLock) set(CaptureRequest.CONTROL_AE_LOCK, true)
                    val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        ?: intArrayOf()
                    when {
                        afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    }
                }
                if (awbLock) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (lensShadingModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
                    set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
                    )
                }
            }.build()
        }
    }

    /**
     * Conservative exposure/ISO heuristic. It intentionally bottoms out at four frames to preserve
     * the current HDR fusion behavior and grows only when photon noise is likely to dominate.
     */
    private fun adaptiveFrameCount(
        previewResult: TotalCaptureResult?,
        characteristics: CameraCharacteristics,
    ): Int {
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = previewResult?.get(CaptureResult.SENSOR_SENSITIVITY)
            ?: isoRange?.lower
            ?: 100
        val exposureNs = previewResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?: exposureRange?.lower?.coerceAtLeast(DEFAULT_EXPOSURE_NS)
            ?: DEFAULT_EXPOSURE_NS
        val exposureMs = exposureNs.coerceAtLeast(1L).toDouble() / 1_000_000.0
        val isoScale = (iso.coerceAtLeast(1).toDouble() / 100.0).coerceAtLeast(1.0)
        val lightCost = exposureMs * isoScale
        return when {
            lightCost >= 140.0 -> 8
            lightCost >= 70.0 -> 7
            lightCost >= 32.0 -> 6
            lightCost >= 12.0 -> 5
            else -> 4
        }
    }

    private fun pairLocked(state: BurstState, timestamp: Long) {
        val payload = state.payloads[timestamp] ?: return
        val result = state.results[timestamp] ?: return
        state.payloads.remove(timestamp)
        state.results.remove(timestamp)
        state.frames += payload.pairWith(result, state.characteristics)
    }

    private fun maybeStartProcessing(state: BurstState) {
        val frames = synchronized(state.lock) {
            if (!isActive(state) || state.processingStarted || state.frames.size < state.expectedFrames) {
                null
            } else {
                state.processingStarted = true
                state.frames.take(state.expectedFrames).toList()
            }
        } ?: return

        runCatching { state.reader.setOnImageAvailableListener(null, null) }
        runCatching { state.reader.close() }
        state.onProcessing(frames.size)
        imageHandler.post {
            try {
                val fused = NativeComputationalRawEngine.process(
                    frames = frames,
                    characteristics = state.characteristics,
                    settings = state.settings,
                    targetAspect = state.targetAspect,
                    outputDirectory = scratchDir,
                )
                try {
                    val description = buildString {
                        append("Camera native computational Linear DNG")
                        append(" · frames=${frames.size}")
                        append(" · adaptive=true")
                        append(" · hdr=${state.settings.hdrEnabled ?: true}")
                        append(" · saturation=${state.settings.saturation ?: 1f}")
                        append(" · sharpness=${state.settings.sharpness ?: 0.28f}")
                        append(" · upscale=${state.settings.upscaleFactor ?: 1f}x")
                        append(" · aspect=${state.targetAspect}")
                    }
                    val uri = FusedDngWriter.save(
                        context = appContext,
                        fused = fused,
                        description = description,
                    )
                    if (isActive(state)) {
                        active = null
                        state.onSaved(uri)
                    }
                } finally {
                    fused.file.delete()
                }
            } catch (error: Throwable) {
                if (isActive(state)) {
                    active = null
                    state.onError(error.message ?: "Native computational DNG processing failed")
                }
            } finally {
                frames.forEach { it.file.delete() }
                cleanupOrphanPayloads(state)
            }
        }
    }

    private fun finishAcquisition(state: BurstState) {
        if (!isActive(state)) return
        if (!state.acquisitionFinished) {
            state.acquisitionFinished = true
            runCatching { state.session?.close() }
            state.session = null
            state.onAcquisitionFinished()
        }
    }

    private fun fail(state: BurstState, message: String) {
        if (!isActive(state)) return
        active = null
        generation++
        val shouldRestore = !state.acquisitionFinished
        cleanup(state)
        if (shouldRestore) state.onAcquisitionFinished()
        state.onError(message)
    }

    private fun cleanup(state: BurstState) {
        runCatching { state.session?.stopRepeating() }
        runCatching { state.session?.abortCaptures() }
        runCatching { state.session?.close() }
        runCatching { state.reader.setOnImageAvailableListener(null, null) }
        runCatching { state.reader.close() }
        state.session = null
        cleanupOrphanPayloads(state)
        synchronized(state.lock) {
            state.frames.forEach { it.file.delete() }
            state.frames.clear()
            state.payloads.clear()
            state.results.clear()
        }
    }

    private fun cleanupOrphanPayloads(state: BurstState) {
        synchronized(state.lock) {
            state.payloads.values.forEach { it.file.delete() }
            state.payloads.clear()
        }
    }

    private fun isActive(state: BurstState): Boolean =
        active === state && state.generation == generation

    private fun resolveRawStreamCharacteristics(
        lens: ValuableLens,
        preferred: CameraCharacteristics,
    ): CameraCharacteristics {
        if (chooseRawSize(preferred) != null) return preferred
        return runCatching { cameraManager.getCameraCharacteristics(lens.cameraId) }
            .getOrNull()
            ?.takeIf { chooseRawSize(it) != null }
            ?: preferred
    }

    private fun chooseSensorMetadataCharacteristics(
        preferred: CameraCharacteristics,
        fallback: CameraCharacteristics,
    ): CameraCharacteristics {
        val cfa = preferred.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val white = preferred.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        return if (cfa != null && white != null) preferred else fallback
    }

    private fun chooseRawSize(characteristics: CameraCharacteristics): Size? =
        characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.filter { it.width > 0 && it.height > 0 }
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

    private fun physicalResult(result: TotalCaptureResult, physicalId: String?): CaptureResult {
        if (physicalId == null) return result
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.physicalCameraTotalResults[physicalId] ?: result
        } else {
            @Suppress("DEPRECATION")
            result.physicalCameraResults[physicalId] ?: result
        }
    }

    private class BurstState(
        val generation: Long,
        val lens: ValuableLens,
        val characteristics: CameraCharacteristics,
        val settings: PhotoLensSettings,
        val targetAspect: Float,
        val expectedFrames: Int,
        val reader: ImageReader,
        val onAcquisitionFinished: () -> Unit,
        val onProcessing: (Int) -> Unit,
        val onSaved: (String) -> Unit,
        val onError: (String) -> Unit,
    ) {
        val lock = Any()
        val payloads = mutableMapOf<Long, StagedRawPayload>()
        val results = mutableMapOf<Long, CaptureResult>()
        val frames = mutableListOf<RawFrame>()
        var session: CameraCaptureSession? = null
        var acquisitionFinished = false
        var processingStarted = false
    }

    private companion object {
        const val RAW_READER_HEADROOM = 2
        const val RAW_TIMEOUT_MS = 18_000L
        const val DEFAULT_EXPOSURE_NS = 10_000_000L
        const val FRAME_MARGIN_NS = 1_000_000L
    }
}
