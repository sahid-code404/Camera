package com.camera.feature.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewTreeObserver
import com.camera.core.model.ValuableLens
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns one Camera2 preview session at a time.
 *
 * Square composition crops a sharp native-aspect stream instead of requesting tiny OEM 1:1
 * streams. Camera loss is recoverable: the controller releases the stale device and reopens it when
 * Android reports the camera available again. Front preview is intentionally NOT mirrored so the
 * viewfinder matches the scene/captured orientation.
 */
internal class Camera2PreviewController(
    context: Context,
    private val onState: (PreviewState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("Camera2Preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }

    private var textureView: TextureView? = null
    private var focusListenerView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var activeLensKey: String? = null
    private var pendingLens: ValuableLens? = null
    private var pendingAspect: Float? = 4f / 3f
    private var opening = false
    private var released = false

    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        handler.post {
            if (released) return@post
            if (hasFocus) {
                reopenIfPossible()
            } else {
                closeCamera()
                emit(PreviewState.Idle)
            }
        }
    }

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            val lens = pendingLens ?: return
            if (released || lens.cameraId != cameraId) return
            reopenIfPossible()
        }

        override fun onCameraUnavailable(cameraId: String) {
            val lens = pendingLens ?: return
            if (
                lens.cameraId == cameraId &&
                cameraDevice == null &&
                !opening &&
                textureView?.hasWindowFocus() == true
            ) {
                emit(PreviewState.Opening(lens.cameraId, lens.physicalCameraId))
            }
        }
    }

    init {
        cameraManager.registerAvailabilityCallback(executor, availabilityCallback)
    }

    fun bind(
        view: TextureView,
        lens: ValuableLens?,
        targetAspect: Float? = 4f / 3f,
    ) {
        if (released) return

        textureView = view
        pendingLens = lens
        pendingAspect = targetAspect
        installWindowFocusListener(view)

        if (view.surfaceTextureListener !== surfaceListener) {
            view.surfaceTextureListener = surfaceListener
        }

        val key = lens?.let { lensKey(it, targetAspect) }
        if (
            lens != null &&
            key == activeLensKey &&
            (opening || cameraDevice != null || captureSession != null)
        ) {
            configureTransform(lens, view.width, view.height, targetAspect = targetAspect)
            return
        }

        if (view.isAvailable && lens != null && view.hasWindowFocus()) {
            open(lens, targetAspect)
        } else if (lens == null) {
            closeCamera()
            emit(PreviewState.Idle)
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { cameraManager.unregisterAvailabilityCallback(availabilityCallback) }
        removeWindowFocusListener()
        closeCamera()
        textureView?.surfaceTextureListener = null
        textureView = null
        thread.quitSafely()
    }

    private fun installWindowFocusListener(view: TextureView) {
        if (focusListenerView === view) return
        removeWindowFocusListener()
        if (view.viewTreeObserver.isAlive) {
            view.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
            focusListenerView = view
        }
    }

    private fun removeWindowFocusListener() {
        val view = focusListenerView ?: return
        if (view.viewTreeObserver.isAlive) {
            runCatching {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
            }
        }
        focusListenerView = null
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            reopenIfPossible()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            pendingLens?.let { lens ->
                configureTransform(lens, width, height, targetAspect = pendingAspect)
            }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            emit(PreviewState.Idle)
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun reopenIfPossible() {
        if (released || opening || cameraDevice != null) return
        val view = textureView ?: return
        val lens = pendingLens ?: return
        if (!view.isAvailable || !view.isAttachedToWindow || !view.hasWindowFocus()) return
        open(lens, pendingAspect)
    }

    @SuppressLint("MissingPermission")
    private fun open(lens: ValuableLens, targetAspect: Float?) {
        if (released) return
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return
        if (!view.isAttachedToWindow || !view.hasWindowFocus()) return

        val key = lensKey(lens, targetAspect)
        if (key == activeLensKey && (opening || cameraDevice != null)) return

        closeCamera()
        activeLensKey = key
        opening = true
        emit(PreviewState.Opening(lens.cameraId, lens.physicalCameraId))

        runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(lens.cameraId)
            val size = choosePreviewSize(characteristics, targetAspect)
            texture.setDefaultBufferSize(size.width, size.height)
            configureTransform(
                lens = lens,
                width = view.width,
                height = view.height,
                explicitSize = size,
                targetAspect = targetAspect,
            )

            cameraManager.openCamera(
                lens.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false
                        if (
                            released ||
                            activeLensKey != key ||
                            textureView?.hasWindowFocus() != true
                        ) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createSession(camera, lens, texture, size)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        handleRecoverableCameraLoss(camera, lens)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (isRecoverableDeviceError(error)) {
                            handleRecoverableCameraLoss(camera, lens)
                        } else {
                            opening = false
                            if (cameraDevice === camera) cameraDevice = null
                            runCatching { camera.close() }
                            closeCamera()
                            emit(PreviewState.Error(cameraErrorMessage(error)))
                        }
                    }
                },
                handler,
            )
        }.onFailure { error ->
            opening = false
            closeCamera()
            if (isRecoverableOpenFailure(error)) {
                markWaitingForCamera(lens)
            } else {
                emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun handleRecoverableCameraLoss(camera: CameraDevice, lens: ValuableLens) {
        opening = false
        if (cameraDevice === camera) cameraDevice = null
        runCatching { camera.close() }
        closeCamera()
        markWaitingForCamera(lens)
    }

    private fun markWaitingForCamera(lens: ValuableLens) {
        if (
            !released &&
            pendingLens?.id == lens.id &&
            textureView?.hasWindowFocus() == true
        ) {
            emit(PreviewState.Opening(lens.cameraId, lens.physicalCameraId))
        } else {
            emit(PreviewState.Idle)
        }
    }

    private fun isRecoverableDeviceError(error: Int): Boolean = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE,
        -> true
        else -> false
    }

    private fun cameraErrorMessage(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled by device policy"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Android camera service error"
        else -> "Camera open error $error"
    }

    private fun isRecoverableOpenFailure(error: Throwable): Boolean {
        if (error !is CameraAccessException) return false
        return when (error.reason) {
            CameraAccessException.CAMERA_DISCONNECTED,
            CameraAccessException.CAMERA_IN_USE,
            CameraAccessException.MAX_CAMERAS_IN_USE,
            CameraAccessException.CAMERA_ERROR,
            -> true
            else -> false
        }
    }

    private fun createSession(
        camera: CameraDevice,
        lens: ValuableLens,
        texture: SurfaceTexture,
        previewSize: Size,
    ) {
        runCatching {
            val surface = Surface(texture)
            previewSurface = surface
            val output = OutputConfiguration(surface)
            lens.physicalCameraId?.let(output::setPhysicalCameraId)

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (
                            cameraDevice !== camera ||
                            released ||
                            textureView?.hasWindowFocus() != true
                        ) {
                            session.close()
                            return
                        }
                        captureSession = session
                        startRepeating(camera, session, surface, lens, previewSize)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (captureSession === session) captureSession = null
                        emit(
                            PreviewState.Error(
                                if (lens.physicalCameraId != null) {
                                    "Physical lens ${lens.physicalCameraId} rejected the preview session"
                                } else {
                                    "Camera preview session configuration failed"
                                },
                            ),
                        )
                    }
                },
            )
            camera.createCaptureSession(sessionConfig)
        }.onFailure { error ->
            if (isRecoverableOpenFailure(error)) {
                closeCamera()
                markWaitingForCamera(lens)
            } else {
                emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun startRepeating(
        camera: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface,
        lens: ValuableLens,
        previewSize: Size,
    ) {
        runCatching {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                val characteristics = cameraManager.getCameraCharacteristics(lens.cameraId)
                val afModes = characteristics
                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?: intArrayOf()
                when {
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }
            }
            session.setRepeatingRequest(request.build(), null, handler)
            emit(
                PreviewState.Streaming(
                    cameraId = lens.cameraId,
                    physicalCameraId = lens.physicalCameraId,
                    width = previewSize.width,
                    height = previewSize.height,
                ),
            )
        }.onFailure { error ->
            if (isRecoverableOpenFailure(error)) {
                closeCamera()
                markWaitingForCamera(lens)
            } else {
                emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun closeCamera() {
        opening = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { previewSurface?.release() }
        captureSession = null
        cameraDevice = null
        previewSurface = null
        activeLensKey = null
    }

    private fun choosePreviewSize(
        characteristics: CameraCharacteristics,
        targetAspect: Float?,
    ): Size {
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width > 0 && it.height > 0 }
            ?.distinctBy { "${it.width}x${it.height}" }
            .orEmpty()

        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorAspect = active
            ?.let { landscapeAspect(it.width().toFloat() / it.height().toFloat()) }
            ?: 4f / 3f
        val compositionAspect = targetAspect
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let(::landscapeAspect)
            ?: sensorAspect
        val squareComposition = abs(compositionAspect - 1f) < 0.03f
        val streamAspect = if (squareComposition) sensorAspect else compositionAspect
        val targetLongEdge = if (squareComposition) 1440 else 1280

        if (sizes.isEmpty()) {
            return when {
                streamAspect > 1.55f -> Size(1280, 720)
                squareComposition -> Size(1440, 1080)
                else -> Size(1280, 960)
            }
        }

        val bounded = sizes.filter {
            max(it.width, it.height) <= 1920 && pixels(it) <= 2_100_000L
        }.ifEmpty { sizes }
        val qualityCandidates = if (squareComposition) {
            bounded.filter { pixels(it) >= 900_000L }.ifEmpty { bounded }
        } else {
            bounded
        }

        return qualityCandidates.minWithOrNull(
            compareBy<Size> {
                abs(sizeAspect(it) - streamAspect)
            }.thenBy {
                abs(max(it.width, it.height) - targetLongEdge)
            }.thenByDescending(::pixels),
        ) ?: sizes.first()
    }

    private fun configureTransform(
        lens: ValuableLens,
        width: Int,
        height: Int,
        explicitSize: Size? = null,
        targetAspect: Float? = pendingAspect,
    ) {
        if (width <= 0 || height <= 0) return
        val view = textureView ?: return
        val characteristics = runCatching {
            cameraManager.getCameraCharacteristics(lens.cameraId)
        }.getOrNull() ?: return
        val size = explicitSize ?: choosePreviewSize(characteristics, targetAspect)
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = max(
                    height.toFloat() / size.height.toFloat(),
                    width.toFloat() / size.width.toFloat(),
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(
                    if (rotation == Surface.ROTATION_90) -90f else 90f,
                    centerX,
                    centerY,
                )
            }
            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
        }

        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            val sourceAspect = sizeAspect(size)
            val requested = targetAspect
                ?.takeIf { it.isFinite() && it > 0f }
                ?.let(::landscapeAspect)
                ?: sourceAspect
            when {
                sourceAspect > requested + 0.01f ->
                    matrix.postScale(1f, sourceAspect / requested, centerX, centerY)
                requested > sourceAspect + 0.01f ->
                    matrix.postScale(requested / sourceAspect, 1f, centerX, centerY)
            }
        }

        // Do not horizontally flip the front camera. Camera2 already provides sensor-oriented
        // frames; applying a second flip here creates a mirrored viewfinder.
        view.setTransform(matrix)
    }

    private fun emit(state: PreviewState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onState(state)
        } else {
            mainHandler.post { if (!released) onState(state) }
        }
    }

    private fun lensKey(lens: ValuableLens, targetAspect: Float?): String =
        "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}:a${aspectKey(targetAspect)}"

    private fun aspectKey(value: Float?): Int =
        value?.takeIf { it.isFinite() && it > 0f }?.let { (it * 1000f).toInt() } ?: 0

    private fun landscapeAspect(value: Float): Float = if (value >= 1f) value else 1f / value

    private fun sizeAspect(size: Size): Float =
        max(size.width, size.height).toFloat() / min(size.width, size.height).coerceAtLeast(1).toFloat()

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()
}

internal sealed interface PreviewState {
    data object Idle : PreviewState
    data class Opening(val cameraId: String, val physicalCameraId: String?) : PreviewState
    data class Streaming(
        val cameraId: String,
        val physicalCameraId: String?,
        val width: Int,
        val height: Int,
    ) : PreviewState
    data class Error(val message: String) : PreviewState
}
