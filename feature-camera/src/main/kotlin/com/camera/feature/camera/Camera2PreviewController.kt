package com.camera.feature.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
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
import com.camera.core.model.ValuableLens
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal production-style Camera2 preview owner for the live camera screen.
 *
 * It deliberately owns exactly one CameraDevice/CameraCaptureSession at a time, routes a preview
 * surface to a physical member when the public logical-multi-camera API allows it, and converts
 * camera failures into UI state instead of letting them crash the process.
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
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var activeLensKey: String? = null
    private var pendingLens: ValuableLens? = null
    private var opening = false
    private var released = false

    fun bind(view: TextureView, lens: ValuableLens?) {
        if (released) return
        textureView = view
        pendingLens = lens

        if (view.surfaceTextureListener !== surfaceListener) {
            view.surfaceTextureListener = surfaceListener
        }

        val key = lens?.let(::lensKey)
        if (key == activeLensKey && (opening || cameraDevice != null || captureSession != null)) return

        if (view.isAvailable && lens != null) {
            open(lens)
        } else if (lens == null) {
            closeCamera()
            emit(PreviewState.Idle)
        }
    }

    fun release() {
        if (released) return
        released = true
        closeCamera()
        textureView?.surfaceTextureListener = null
        textureView = null
        thread.quitSafely()
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            pendingLens?.let(::open)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            pendingLens?.let { lens -> configureTransform(lens, width, height) }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    @SuppressLint("MissingPermission")
    private fun open(lens: ValuableLens) {
        if (released) return
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return
        val key = lensKey(lens)

        if (key == activeLensKey && (opening || cameraDevice != null)) return

        closeCamera()
        activeLensKey = key
        opening = true
        emit(PreviewState.Opening(lens.cameraId, lens.physicalCameraId))

        runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(lens.cameraId)
            val size = choosePreviewSize(characteristics, view.width, view.height)
            texture.setDefaultBufferSize(size.width, size.height)
            configureTransform(lens, view.width, view.height, size)

            cameraManager.openCamera(
                lens.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false
                        if (released || activeLensKey != key) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createSession(camera, lens, texture, size)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        opening = false
                        if (cameraDevice === camera) cameraDevice = null
                        camera.close()
                        emit(PreviewState.Error("Camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        opening = false
                        if (cameraDevice === camera) cameraDevice = null
                        camera.close()
                        emit(PreviewState.Error("Camera open error $error"))
                    }
                },
                handler,
            )
        }.onFailure { error ->
            opening = false
            closeCamera()
            emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
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
            lens.physicalCameraId?.let { physicalId ->
                output.setPhysicalCameraId(physicalId)
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== camera || released) {
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
            emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
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
                    .orEmpty()
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
            emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
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
        viewWidth: Int,
        viewHeight: Int,
    ): Size {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = streamMap
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width > 0 && it.height > 0 }
            .orEmpty()

        if (sizes.isEmpty()) return Size(1280, 720)

        val longView = max(viewWidth, viewHeight).coerceAtLeast(1)
        val shortView = min(viewWidth, viewHeight).coerceAtLeast(1)
        val targetRatio = longView.toFloat() / shortView.toFloat()
        val maxPreviewArea = 2560L * 1440L

        val bounded = sizes.filter { it.width.toLong() * it.height <= maxPreviewArea }
            .ifEmpty { sizes }

        return bounded.minWithOrNull(
            compareBy<Size> {
                val longSide = max(it.width, it.height).toFloat()
                val shortSide = min(it.width, it.height).coerceAtLeast(1).toFloat()
                abs(longSide / shortSide - targetRatio)
            }.thenByDescending { it.width.toLong() * it.height },
        ) ?: sizes.first()
    }

    private fun configureTransform(
        lens: ValuableLens,
        width: Int,
        height: Int,
        explicitSize: Size? = null,
    ) {
        if (width <= 0 || height <= 0) return
        val view = textureView ?: return
        val characteristics = runCatching {
            cameraManager.getCameraCharacteristics(lens.cameraId)
        }.getOrNull() ?: return
        val size = explicitSize ?: choosePreviewSize(characteristics, width, height)
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
        view.setTransform(matrix)
    }

    private fun emit(state: PreviewState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onState(state)
        } else {
            mainHandler.post { if (!released) onState(state) }
        }
    }

    private fun lensKey(lens: ValuableLens): String =
        "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}"
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
