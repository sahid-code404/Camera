package com.camera.feature.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
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
import com.camera.core.model.ValuableLens
import com.camera.processing.raw.FusedDngWriter
import com.camera.processing.raw.RawBurstFusion
import com.camera.processing.raw.RawFrame
import com.camera.processing.raw.RawFrameStager
import com.camera.processing.raw.StagedRawPayload
import com.camera.processing.raw.pairWith
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * Owns only the short RAW acquisition transaction. Heavy fusion stays in :processing-raw.
 *
 * Normal computational capture uses a constant-exposure burst. Bracketed assist frames belong to a
 * later scene-aware HDR planner; they are not the default burst direction.
 */
internal class ComputationalRawCaptureCoordinator(
    context: android.content.Context,
    private val cameraHandler: Handler,
    private val imageHandler: Handler,
) {
    private val appContext = context.applicationContext
    private val executor = Executor { command -> cameraHandler.post(command) }
    private val scratchDir = File(appContext.cacheDir, "computational-raw")
    private var generation = 0L
    private var active: BurstState? = null

    fun supports(characteristics: CameraCharacteristics): Boolean {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val rawSizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            .orEmpty()
        return capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSizes.isNotEmpty()
    }

    fun capture(
        camera: CameraDevice,
        lens: ValuableLens,
        captureCharacteristics: CameraCharacteristics,
        latestPreviewResult: TotalCaptureResult?,
        onAcquisitionFinished: () -> Unit,
        onProcessing: (Int) -> Unit,
        onSaved: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel(notify = false)
        scratchDir.mkdirs()
        scratchDir.listFiles()
            ?.filter { it.name.startsWith("camera_raw_") || it.name.startsWith("camera_fused_") }
            ?.forEach { runCatching { it.delete() } }

        val rawSize = chooseRawSize(captureCharacteristics)
            ?: run {
                onError("Selected lens exposes no RAW_SENSOR size")
                return
            }
        val myGeneration = ++generation
        val reader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            FRAME_COUNT + 2,
        )
        val state = BurstState(
            generation = myGeneration,
            lens = lens,
            characteristics = captureCharacteristics,
            reader = reader,
            onAcquisitionFinished = onAcquisitionFinished,
            onProcessing = onProcessing,
            onSaved = onSaved,
            onError = onError,
        )
        active = state

        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
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
                                    val selected = physicalResult(result, lens.physicalCameraId)
                                    val timestamp = selected.get(CaptureResult.SENSOR_TIMESTAMP)
                                        ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                                    if (timestamp == null) {
                                        fail(state, "RAW capture result has no sensor timestamp")
                                        return
                                    }
                                    synchronized(state.lock) {
                                        if (!isActive(state)) return@synchronized
                                        state.results[timestamp] = selected
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
                    fail(state, "Camera rejected RAW capture session")
                }
            },
        )

        cameraHandler.postDelayed({
            if (isActive(state) && !state.processingStarted) {
                fail(state, "RAW burst timed out")
            }
        }, RAW_TIMEOUT_MS)

        runCatching { camera.createCaptureSession(config) }
            .onFailure { error -> fail(state, error.message ?: "Unable to create RAW session") }
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
        val exposure = if (exposureRange != null) {
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

        return List(FRAME_COUNT) {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if (manual) {
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

    private fun pairLocked(state: BurstState, timestamp: Long) {
        val payload = state.payloads[timestamp] ?: return
        val result = state.results[timestamp] ?: return
        state.payloads.remove(timestamp)
        state.results.remove(timestamp)
        state.frames += payload.pairWith(result, state.characteristics)
    }

    private fun maybeStartProcessing(state: BurstState) {
        val frames = synchronized(state.lock) {
            if (!isActive(state) || state.processingStarted || state.frames.size < FRAME_COUNT) {
                null
            } else {
                state.processingStarted = true
                state.frames.take(FRAME_COUNT).toList()
            }
        } ?: return

        runCatching { state.reader.setOnImageAvailableListener(null, null) }
        runCatching { state.reader.close() }
        state.onProcessing(frames.size)
        imageHandler.post {
            try {
                val fused = RawBurstFusion.fuse(frames, state.characteristics, scratchDir)
                try {
                    val uri = FusedDngWriter.save(
                        context = appContext,
                        fused = fused,
                        description = "Camera computational RAW",
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
                    state.onError(error.message ?: "Computational RAW processing failed")
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
        const val FRAME_COUNT = 4
        const val RAW_TIMEOUT_MS = 12_000L
        const val DEFAULT_EXPOSURE_NS = 10_000_000L
        const val FRAME_MARGIN_NS = 1_000_000L
    }
}
