package com.camera.feature.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.ViewTreeObserver
import com.camera.camera.camera2.NativeCameraNdkBridge
import com.camera.core.model.PhotoLensSettings
import com.camera.core.model.ValuableLens
import com.camera.processing.raw.FusedDngWriter
import com.camera.processing.raw.NativeComputationalRawEngine
import com.camera.processing.raw.NativeRawFrame
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Camera owner for preview, focus/zoom and computational RAW capture.
 *
 * Normal framework-visible cameras keep the mature Java Camera2 path. Auxiliary IDs discovered only
 * by ACameraManager use a MotionCam-style NDK session for both preview and genuine RAW10/RAW16
 * acquisition. Both routes feed the same native C++ processing pipeline and persist only DNG for
 * PHOTO; there is deliberately no JPEG/HEIC/YUV still-capture fallback.
 */
internal class Camera2PreviewController(
    context: Context,
    private val onState: (PreviewState) -> Unit,
    private val onZoomState: (ZoomState) -> Unit = {},
    private val onFocusPoint: (FocusPoint?) -> Unit = {},
    private val onCaptureState: (PhotoCaptureState) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val javaCameraIds = runCatching { cameraManager.cameraIdList.toSet() }.getOrDefault(emptySet())
    private val thread = HandlerThread("Camera2Preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val processingThread = HandlerThread("CameraRawProcessing").apply { start() }
    private val processingHandler = Handler(processingThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }
    private val computationalRaw = ComputationalRawCaptureCoordinator(
        context = appContext,
        cameraHandler = handler,
        imageHandler = processingHandler,
    )
    private val rawScratchDir = File(appContext.cacheDir, "computational-raw")

    private var textureView: TextureView? = null
    private var focusListenerView: TextureView? = null
    private var gestureView: TextureView? = null
    private var tapDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activePreviewSize: Size? = null
    private var activeLensKey: String? = null
    private var activeNativeDescriptor: NativeCameraNdkBridge.Descriptor? = null
    private var nativeSessionActive = false
    private var pendingLens: ValuableLens? = null
    private var pendingAspect: Float? = 4f / 3f
    private var latestPreviewResult: TotalCaptureResult? = null

    private var currentZoomRatio = 1f
    private var maxZoomRatio = 1f
    private var lastMeteringRegion: MeteringRectangle? = null
    private var opening = false
    private var captureInFlight = false
    private var released = false

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (captureSession === session && !released) latestPreviewResult = result
        }
    }

    private val clearFocusRunnable = Runnable {
        if (nativeSessionActive) {
            emitFocus(null)
            return@Runnable
        }
        val builder = previewBuilder ?: return@Runnable
        val session = captureSession ?: return@Runnable
        val characteristics = activeCharacteristics ?: return@Runnable
        lastMeteringRegion = null
        runCatching {
            if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            }
            if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
            setBestContinuousAf(builder, characteristics)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)
        }
        emitFocus(null)
    }

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
            if (released || lens.cameraId != cameraId || nativeDescriptorFor(lens) != null) return
            reopenIfPossible()
        }

        override fun onCameraUnavailable(cameraId: String) {
            val lens = pendingLens ?: return
            if (
                nativeDescriptorFor(lens) == null &&
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
        installGestures(view)
        if (view.surfaceTextureListener !== surfaceListener) view.surfaceTextureListener = surfaceListener

        val key = lens?.let { lensKey(it, targetAspect) }
        if (
            lens != null && key == activeLensKey &&
            (opening || cameraDevice != null || captureSession != null || nativeSessionActive)
        ) {
            val nativeSize = activeNativeDescriptor?.let { Size(it.previewWidth, it.previewHeight) }
            if (nativeSize != null) {
                configureTransformForSize(view.width, view.height, nativeSize, targetAspect)
            } else {
                configureTransform(lens, view.width, view.height, targetAspect = targetAspect)
            }
            return
        }
        if (view.isAvailable && lens != null && view.hasWindowFocus()) {
            open(lens, targetAspect)
        } else if (lens == null) {
            closeCamera()
            emit(PreviewState.Idle)
        }
    }

    fun capturePhoto(
        targetAspect: Float? = pendingAspect,
        settings: PhotoLensSettings = PhotoLensSettings(),
    ) {
        handler.post {
            if (released || captureInFlight) return@post
            val lens = pendingLens ?: return@post
            val view = textureView ?: return@post
            if (!view.isAvailable || !view.hasWindowFocus()) return@post

            val nativeDescriptor = activeNativeDescriptor
            if (
                nativeSessionActive &&
                nativeDescriptor != null &&
                nativeDescriptor.id == lens.cameraId
            ) {
                pendingAspect = targetAspect
                captureNativeRaw(
                    lens = lens,
                    descriptor = nativeDescriptor,
                    settings = settings,
                    targetAspect = targetAspect ?: 4f / 3f,
                )
                return@post
            }

            val camera = cameraDevice ?: return@post
            val characteristics = captureCharacteristics(lens)
            if (!computationalRaw.supports(characteristics)) {
                emitCapture(PhotoCaptureState.Error("Selected lens exposes no RAW_SENSOR DNG path"))
                return@post
            }

            pendingAspect = targetAspect
            captureInFlight = true
            emitCapture(PhotoCaptureState.Capturing)
            closePreviewSessionForCapture()
            captureComputationalRaw(
                camera = camera,
                lens = lens,
                characteristics = characteristics,
                settings = settings,
                targetAspect = targetAspect ?: 4f / 3f,
            )
        }
    }

    fun release() {
        if (released) return
        released = true
        computationalRaw.cancel()
        runCatching { cameraManager.unregisterAvailabilityCallback(availabilityCallback) }
        handler.removeCallbacks(clearFocusRunnable)
        removeWindowFocusListener()
        removeGestures()
        closeCamera()
        textureView?.surfaceTextureListener = null
        textureView = null
        thread.quitSafely()
        processingThread.quitSafely()
    }

    private fun captureComputationalRaw(
        camera: CameraDevice,
        lens: ValuableLens,
        characteristics: CameraCharacteristics,
        settings: PhotoLensSettings,
        targetAspect: Float,
    ) {
        computationalRaw.capture(
            camera = camera,
            lens = lens,
            captureCharacteristics = characteristics,
            latestPreviewResult = latestPreviewResult,
            settings = settings,
            targetAspect = targetAspect,
            onAcquisitionFinished = {
                handler.post { if (!released) restorePreviewIfPossible() }
            },
            onProcessing = { emitCapture(PhotoCaptureState.Saving) },
            onSaved = { uri ->
                handler.post {
                    if (released) return@post
                    captureInFlight = false
                    emitCapture(PhotoCaptureState.Saved(uri))
                }
            },
            onError = { message ->
                handler.post {
                    if (released) return@post
                    captureInFlight = false
                    emitCapture(PhotoCaptureState.Error(message))
                    restorePreviewIfPossible()
                }
            },
        )
    }

    /** NDK-only auxiliary camera capture. Preview stays alive while the RAW burst is acquired. */
    private fun captureNativeRaw(
        lens: ValuableLens,
        descriptor: NativeCameraNdkBridge.Descriptor,
        settings: PhotoLensSettings,
        targetAspect: Float,
    ) {
        captureInFlight = true
        emitCapture(PhotoCaptureState.Capturing)
        val captureKey = activeLensKey
        processingHandler.post {
            val stagedFiles = mutableListOf<File>()
            try {
                rawScratchDir.mkdirs()
                rawScratchDir.listFiles()
                    ?.filter { it.name.startsWith("camera_ndk_raw_") }
                    ?.forEach { runCatching { it.delete() } }
                val burst = NativeCameraNdkBridge.captureBurst(
                    scratchDirectory = rawScratchDir,
                    frameCount = NATIVE_FRAME_COUNT,
                    hdrStrength = settings.hdrStrength ?: 0.72f,
                )
                stagedFiles += burst.frames.map { it.file }
                emitCapture(PhotoCaptureState.Saving)
                val frames = burst.frames.map { frame ->
                    NativeRawFrame(
                        timestampNs = frame.timestampNs,
                        width = frame.width,
                        height = frame.height,
                        file = frame.file,
                        exposureTimeNs = frame.exposureTimeNs,
                        iso = frame.iso,
                        blackLevels = frame.blackLevels,
                        whiteLevel = frame.whiteLevel,
                        awbGains = frame.awbGains,
                        sensorToLinearSrgb = frame.colorTransform,
                    )
                }
                val fused = NativeComputationalRawEngine.processNative(
                    frames = frames,
                    cfaArrangement = burst.cfaArrangement,
                    settings = settings,
                    targetAspect = targetAspect,
                    outputDirectory = rawScratchDir,
                )
                try {
                    val description = buildString {
                        append("Camera native computational Linear DNG")
                        append(" · NDK/MotionCam route")
                        append(" · camera=${lens.cameraId}")
                        append(" · frames=${frames.size}")
                        append(" · RAW=${descriptor.rawWidth}x${descriptor.rawHeight}")
                        append(" · hdr=${settings.hdrEnabled ?: true}")
                        append(" · saturation=${settings.saturation ?: 1f}")
                        append(" · sharpness=${settings.sharpness ?: 0.28f}")
                        append(" · upscale=${settings.upscaleFactor ?: 1f}x")
                    }
                    val uri = FusedDngWriter.saveNative(
                        context = appContext,
                        fused = fused,
                        description = description,
                    )
                    handler.post {
                        if (released) return@post
                        captureInFlight = false
                        if (activeLensKey == captureKey || pendingLens?.id == lens.id) {
                            emitCapture(PhotoCaptureState.Saved(uri))
                        }
                    }
                } finally {
                    fused.file.delete()
                }
            } catch (error: Throwable) {
                handler.post {
                    if (released) return@post
                    captureInFlight = false
                    emitCapture(
                        PhotoCaptureState.Error(
                            error.message ?: "Native auxiliary RAW capture failed",
                        ),
                    )
                }
            } finally {
                stagedFiles.forEach { runCatching { it.delete() } }
            }
        }
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
            runCatching { view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener) }
        }
        focusListenerView = null
    }

    private fun installGestures(view: TextureView) {
        if (gestureView === view) return
        removeGestures()
        gestureView = view
        tapDetector = GestureDetector(
            view.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val nx = (e.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (e.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    emitFocus(FocusPoint(nx, ny))
                    handler.post { focusAt(e.x, e.y) }
                    return true
                }
            },
        )
        scaleDetector = ScaleGestureDetector(
            view.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    if (factor.isFinite() && factor > 0f) handler.post { zoomBy(factor) }
                    return true
                }
            },
        )
        view.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            tapDetector?.onTouchEvent(event)
            true
        }
    }

    private fun removeGestures() {
        gestureView?.setOnTouchListener(null)
        gestureView = null
        tapDetector = null
        scaleDetector = null
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = reopenIfPossible()
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            val nativeSize = activeNativeDescriptor?.let { Size(it.previewWidth, it.previewHeight) }
            if (nativeSize != null) {
                configureTransformForSize(width, height, nativeSize, pendingAspect)
            } else {
                pendingLens?.let { configureTransform(it, width, height, targetAspect = pendingAspect) }
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
        if (released || opening || cameraDevice != null || nativeSessionActive) return
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
        if (key == activeLensKey && (opening || cameraDevice != null || nativeSessionActive)) return

        val nativeDescriptor = nativeDescriptorFor(lens)
        if (nativeDescriptor != null) {
            openNative(lens, nativeDescriptor, targetAspect, view, texture, key)
            return
        }

        closeCamera()
        activeLensKey = key
        opening = true
        emit(PreviewState.Opening(lens.cameraId, lens.physicalCameraId))
        runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(lens.cameraId)
            activeCharacteristics = characteristics
            configureZoomRange(characteristics)
            val size = choosePreviewSize(characteristics, targetAspect)
            activePreviewSize = size
            texture.setDefaultBufferSize(size.width, size.height)
            configureTransform(lens, view.width, view.height, size, targetAspect)
            cameraManager.openCamera(
                lens.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false
                        if (released || activeLensKey != key || textureView?.hasWindowFocus() != true) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createPreviewSession(camera, lens, texture, size)
                    }
                    override fun onDisconnected(camera: CameraDevice) = handleRecoverableCameraLoss(camera, lens)
                    override fun onError(camera: CameraDevice, error: Int) {
                        if (isRecoverableDeviceError(error)) handleRecoverableCameraLoss(camera, lens)
                        else {
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
            if (isRecoverableOpenFailure(error)) markWaitingForCamera(lens)
            else emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
        }
    }

    private fun openNative(
        lens: ValuableLens,
        descriptor: NativeCameraNdkBridge.Descriptor,
        targetAspect: Float?,
        view: TextureView,
        texture: SurfaceTexture,
        key: String,
    ) {
        closeCamera()
        activeLensKey = key
        activeNativeDescriptor = descriptor
        opening = true
        emit(PreviewState.Opening(lens.cameraId, null))
        val size = Size(
            descriptor.previewWidth.coerceAtLeast(1),
            descriptor.previewHeight.coerceAtLeast(1),
        )
        activePreviewSize = size
        texture.setDefaultBufferSize(size.width, size.height)
        configureTransformForSize(view.width, view.height, size, targetAspect)
        val surface = Surface(texture)
        previewSurface = surface
        val error = NativeCameraNdkBridge.startSession(lens.cameraId, surface)
        opening = false
        if (error != null) {
            nativeSessionActive = false
            runCatching { surface.release() }
            if (previewSurface === surface) previewSurface = null
            activeNativeDescriptor = null
            activePreviewSize = null
            activeLensKey = null
            emit(PreviewState.Error(error))
            return
        }
        if (released || activeLensKey != key || textureView?.hasWindowFocus() != true) {
            NativeCameraNdkBridge.stopSession()
            nativeSessionActive = false
            return
        }
        nativeSessionActive = true
        currentZoomRatio = 1f
        maxZoomRatio = 1f
        emitZoom(ZoomState(1f, 1f))
        emit(
            PreviewState.Streaming(
                cameraId = lens.cameraId,
                physicalCameraId = null,
                width = size.width,
                height = size.height,
            ),
        )
    }

    private fun nativeDescriptorFor(lens: ValuableLens): NativeCameraNdkBridge.Descriptor? {
        if (lens.physicalCameraId != null || lens.cameraId in javaCameraIds) return null
        return NativeCameraNdkBridge.descriptor(lens.cameraId)
    }

    private fun handleRecoverableCameraLoss(camera: CameraDevice, lens: ValuableLens) {
        opening = false
        if (cameraDevice === camera) cameraDevice = null
        runCatching { camera.close() }
        closeCamera()
        markWaitingForCamera(lens)
    }

    private fun markWaitingForCamera(lens: ValuableLens) {
        if (!released && pendingLens?.id == lens.id && textureView?.hasWindowFocus() == true) {
            emit(PreviewState.Opening(lens.cameraId, lens.physicalCameraId))
        } else emit(PreviewState.Idle)
    }

    private fun isRecoverableDeviceError(error: Int): Boolean = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> true
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
            CameraAccessException.CAMERA_ERROR -> true
            else -> false
        }
    }

    private fun createPreviewSession(
        camera: CameraDevice,
        lens: ValuableLens,
        texture: SurfaceTexture,
        previewSize: Size,
    ) {
        runCatching {
            val surface = previewSurface?.takeIf { it.isValid }
                ?: Surface(texture).also { previewSurface = it }
            val output = OutputConfiguration(surface)
            lens.physicalCameraId?.let(output::setPhysicalCameraId)
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice !== camera || released || textureView?.hasWindowFocus() != true) {
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
                ),
            )
        }.onFailure { error ->
            if (isRecoverableOpenFailure(error)) {
                closeCamera()
                markWaitingForCamera(lens)
            } else emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
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
            val characteristics = activeCharacteristics ?: cameraManager.getCameraCharacteristics(lens.cameraId)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                setBestContinuousAf(this, characteristics)
                applyZoom(this, characteristics)
                applyMetering(this, characteristics)
            }
            previewBuilder = builder
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)
            emit(
                PreviewState.Streaming(
                    lens.cameraId,
                    lens.physicalCameraId,
                    previewSize.width,
                    previewSize.height,
                ),
            )
        }.onFailure { error ->
            if (isRecoverableOpenFailure(error)) {
                closeCamera()
                markWaitingForCamera(lens)
            } else emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
        }
    }

    private fun zoomBy(scaleFactor: Float) {
        if (captureInFlight || nativeSessionActive) return
        val builder = previewBuilder ?: return
        val session = captureSession ?: return
        val characteristics = activeCharacteristics ?: return
        val requested = (currentZoomRatio * scaleFactor).coerceIn(1f, maxZoomRatio)
        if (abs(requested - currentZoomRatio) < 0.002f) return
        currentZoomRatio = requested
        runCatching {
            applyZoom(builder, characteristics)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)
        }.onSuccess { emitZoom(ZoomState(currentZoomRatio, maxZoomRatio)) }
    }

    private fun configureZoomRange(characteristics: CameraCharacteristics) {
        val digitalMax = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val ratioMax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
        } else null
        maxZoomRatio = max(1f, ratioMax ?: digitalMax).coerceAtMost(30f)
        currentZoomRatio = 1f.coerceAtMost(maxZoomRatio)
        emitZoom(ZoomState(currentZoomRatio, maxZoomRatio))
    }

    private fun applyZoom(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                builder.set(
                    CaptureRequest.CONTROL_ZOOM_RATIO,
                    currentZoomRatio.coerceIn(max(1f, range.lower), range.upper),
                )
                return
            }
        }
        builder.set(CaptureRequest.SCALER_CROP_REGION, zoomCrop(characteristics, currentZoomRatio))
    }

    private fun zoomCrop(characteristics: CameraCharacteristics, zoomRatio: Float): Rect? {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val ratio = zoomRatio.coerceAtLeast(1f)
        val width = (active.width() / ratio).toInt().coerceAtLeast(2)
        val height = (active.height() / ratio).toInt().coerceAtLeast(2)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun focusAt(viewX: Float, viewY: Float) {
        if (captureInFlight) return
        if (nativeSessionActive) {
            handler.removeCallbacks(clearFocusRunnable)
            handler.postDelayed(clearFocusRunnable, 650L)
            return
        }
        val view = textureView ?: return
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        val characteristics = activeCharacteristics ?: return
        val crop = zoomCrop(characteristics, currentZoomRatio)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return
        val (sx, sy) = mapViewPointToSensor(viewX, viewY, view, characteristics)
        val centerX = crop.left + (sx * crop.width()).toInt()
        val centerY = crop.top + (sy * crop.height()).toInt()
        val boxW = max(80, (crop.width() * 0.12f).toInt())
        val boxH = max(80, (crop.height() * 0.12f).toInt())
        val regionRect = Rect(
            (centerX - boxW / 2).coerceIn(crop.left, crop.right - 2),
            (centerY - boxH / 2).coerceIn(crop.top, crop.bottom - 2),
            (centerX + boxW / 2).coerceIn(crop.left + 1, crop.right),
            (centerY + boxH / 2).coerceIn(crop.top + 1, crop.bottom),
        )
        if (regionRect.width() <= 0 || regionRect.height() <= 0) return
        val region = MeteringRectangle(regionRect, MeteringRectangle.METERING_WEIGHT_MAX)
        lastMeteringRegion = region
        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, handler)
            if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)
            handler.removeCallbacks(clearFocusRunnable)
            handler.postDelayed(clearFocusRunnable, 2800L)
        }
    }

    private fun mapViewPointToSensor(
        x: Float,
        y: Float,
        view: TextureView,
        characteristics: CameraCharacteristics,
    ): Pair<Float, Float> {
        val nx = (x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val ny = (y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = rotationDegrees(view.display?.rotation ?: Surface.ROTATION_0)
        return when ((sensorOrientation - displayDegrees + 360) % 360) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }
    }

    private fun applyMetering(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics) {
        val region = lastMeteringRegion ?: return
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        }
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private fun setBestContinuousAf(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics) {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
    }

    private fun closePreviewSessionForCapture() {
        handler.removeCallbacks(clearFocusRunnable)
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        previewBuilder = null
    }

    private fun restorePreviewIfPossible() {
        if (released || nativeSessionActive) return
        val camera = cameraDevice ?: return
        val lens = pendingLens ?: return
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return
        val size = activePreviewSize ?: return
        if (!view.isAvailable || !view.hasWindowFocus() || captureSession != null) return
        createPreviewSession(camera, lens, texture, size)
    }

    private fun captureCharacteristics(lens: ValuableLens): CameraCharacteristics {
        lens.physicalCameraId?.let { id ->
            runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()?.let { return it }
        }
        return cameraManager.getCameraCharacteristics(lens.cameraId)
    }

    private fun closeCamera() {
        opening = false
        captureInFlight = false
        handler.removeCallbacks(clearFocusRunnable)
        computationalRaw.cancel()
        if (nativeSessionActive || activeNativeDescriptor != null) {
            NativeCameraNdkBridge.stopSession()
        }
        nativeSessionActive = false
        activeNativeDescriptor = null
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { previewSurface?.release() }
        captureSession = null
        cameraDevice = null
        previewSurface = null
        previewBuilder = null
        activeCharacteristics = null
        activePreviewSize = null
        activeLensKey = null
        lastMeteringRegion = null
        latestPreviewResult = null
        emitFocus(null)
    }

    private fun choosePreviewSize(characteristics: CameraCharacteristics, targetAspect: Float?): Size {
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width > 0 && it.height > 0 }
            ?.distinctBy { "${it.width}x${it.height}" }
            .orEmpty()
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorAspect = active?.let {
            landscapeAspect(it.width().toFloat() / it.height().toFloat())
        } ?: 4f / 3f
        val compositionAspect = targetAspect
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let(::landscapeAspect)
            ?: sensorAspect
        val squareComposition = abs(compositionAspect - 1f) < 0.03f
        val streamAspect = if (squareComposition) sensorAspect else compositionAspect
        val targetLongEdge = if (squareComposition) 1440 else 1280
        if (sizes.isEmpty()) return when {
            streamAspect > 1.55f -> Size(1280, 720)
            squareComposition -> Size(1440, 1080)
            else -> Size(1280, 960)
        }
        val bounded = sizes
            .filter { max(it.width, it.height) <= 1920 && pixels(it) <= 2_100_000L }
            .ifEmpty { sizes }
        val candidates = if (squareComposition) {
            bounded.filter { pixels(it) >= 900_000L }.ifEmpty { bounded }
        } else bounded
        return candidates.minWithOrNull(
            compareBy<Size> { abs(sizeAspect(it) - streamAspect) }
                .thenBy { abs(max(it.width, it.height) - targetLongEdge) }
                .thenByDescending(::pixels),
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
        val characteristics = runCatching {
            cameraManager.getCameraCharacteristics(lens.cameraId)
        }.getOrNull() ?: return
        val size = explicitSize ?: choosePreviewSize(characteristics, targetAspect)
        configureTransformForSize(width, height, size, targetAspect)
    }

    private fun configureTransformForSize(
        width: Int,
        height: Int,
        size: Size,
        targetAspect: Float?,
    ) {
        if (width <= 0 || height <= 0) return
        val view = textureView ?: return
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
                    height.toFloat() / size.height,
                    width.toFloat() / size.width,
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
        view.setTransform(matrix)
    }

    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun emit(state: PreviewState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onState(state)
        } else {
            mainHandler.post { if (!released) onState(state) }
        }
    }

    private fun emitZoom(state: ZoomState) =
        mainHandler.post { if (!released) onZoomState(state) }

    private fun emitFocus(point: FocusPoint?) =
        mainHandler.post { if (!released) onFocusPoint(point) }

    private fun emitCapture(state: PhotoCaptureState) =
        mainHandler.post { if (!released) onCaptureState(state) }

    private fun lensKey(lens: ValuableLens, targetAspect: Float?): String =
        "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}:a${aspectKey(targetAspect)}"

    private fun aspectKey(value: Float?): Int =
        value?.takeIf { it.isFinite() && it > 0f }?.let { (it * 1000f).toInt() } ?: 0

    private fun landscapeAspect(value: Float): Float = if (value >= 1f) value else 1f / value
    private fun sizeAspect(size: Size): Float =
        max(size.width, size.height).toFloat() / min(size.width, size.height).coerceAtLeast(1).toFloat()

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()

    private companion object {
        const val NATIVE_FRAME_COUNT = 4
    }
}

internal data class ZoomState(val ratio: Float = 1f, val maxRatio: Float = 1f)
internal data class FocusPoint(val x: Float, val y: Float)
internal sealed interface PhotoCaptureState {
    data object Idle : PhotoCaptureState
    data object Capturing : PhotoCaptureState
    data object Saving : PhotoCaptureState
    data class Saved(val uri: String) : PhotoCaptureState
    data class Error(val message: String) : PhotoCaptureState
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
