from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch target: {label}")
    return text.replace(old, new, 1)


# 1) Startup catalog: return a tiny rear-preview seed; do not enumerate stream tables before first frame.
path = Path("camera-camera2/src/main/kotlin/com/camera/camera/camera2/AndroidCameraCatalog.kt")
text = path.read_text()
text = replace_once(
    text,
    "import com.camera.core.model.LensFacing\n",
    "import com.camera.core.model.LensFacing\nimport com.camera.core.model.LensRole\nimport com.camera.core.model.LensStableId\nimport com.camera.core.model.ValuableLens\n",
    "catalog imports",
)
start = text.index("    suspend fun scanPrimaryRearCamera(): CameraCatalogSnapshot")
end = text.index("\n    suspend fun scan(deepScan: Boolean)", start)
primary = '''    suspend fun scanPrimaryRearCamera(): CameraCatalogSnapshot = withContext(Dispatchers.Default) {
        val javaIds = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        var firstCompatible: Pair<String, CameraCharacteristics>? = null
        var firstBack: Pair<String, CameraCharacteristics>? = null

        // Absolute minimum Camera2 work for first frame: one characteristics block at a time and
        // stop at the first normal rear route. Do NOT enumerate stream-size tables here.
        for (id in javaIds) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val capabilities = chars
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toSet()
                .orEmpty()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities) {
                continue
            }
            if (firstCompatible == null) firstCompatible = id to chars
            if (facingOf(chars.get(CameraCharacteristics.LENS_FACING)) == LensFacing.BACK) {
                firstBack = id to chars
                break
            }
        }

        val selected = firstBack ?: firstCompatible
        val lenses = selected?.let { (id, chars) ->
            val facing = facingOf(chars.get(CameraCharacteristics.LENS_FACING))
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull { it > 0f }
            val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
            listOf(
                ValuableLens(
                    id = LensStableId("startup:$id"),
                    cameraId = id,
                    facing = facing,
                    inferredRole = if (facing == LensFacing.FRONT) LensRole.FRONT_WIDE else LensRole.WIDE,
                    roleConfidence = 0.5f,
                    focalLengthMm = focal,
                    sensorWidthMm = sensorWidth,
                    displayZoomAnchor = 1f,
                    rawSupported = false,
                    nativeRoutePreferred = false,
                ),
            )
        }.orEmpty()
        CameraCatalogSnapshot(
            deviceProfiles = emptyList(),
            valuableLenses = lenses,
            diagnosticsJson = JSONObject()
                .put("schemaVersion", 2)
                .put("startupSeed", true)
                .put("javaCameraIds", JSONArray(javaIds))
                .toString(),
        )
    }
'''
text = text[:start] + primary + text[end:]
path.write_text(text)


# 2) UI startup: first preview gets exclusive camera-service priority. One deep pass after streaming.
path = Path("feature-camera/src/main/kotlin/com/camera/feature/camera/CameraBootstrapScreen.kt")
text = path.read_text()
text = replace_once(
    text,
    "    // 0=rear preview seed, 1=advertised routes complete, 2=deep hidden-AUX complete.\n",
    "    // 0=rear preview seed only, 2=complete advertised + physical + hidden AUX discovery.\n",
    "discovery stage comment",
)
effects_start = text.index("    LaunchedEffect(cameraPermissionGranted) {", text.index("    LaunchedEffect(Unit)"))
effects_end = text.index("    LaunchedEffect(snapshot?.valuableLenses)", effects_start)
effects = '''    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        discoveryError = null
        discoveryStage = 0

        // Give openCamera() the camera service exclusively until the first viewfinder is alive.
        val primaryResult = runCatching { catalog.scanPrimaryRearValidated(context) }
        val primary = primaryResult.getOrNull()
        if (primary != null && primary.valuableLenses.isNotEmpty()) {
            snapshot = primary
            return@LaunchedEffect
        }

        // Unusual device with no normal rear seed: pay for the complete scan immediately.
        val deepResult = runCatching { catalog.scanValidated(context, deepScan = true) }
        deepResult.onSuccess { deep -> snapshot = deep }
        discoveryStage = 2
        if (snapshot == null) {
            val failure = deepResult.exceptionOrNull() ?: primaryResult.exceptionOrNull()
            discoveryError = failure?.message ?: failure?.javaClass?.simpleName ?: "Camera discovery failed"
        }
    }

    LaunchedEffect(cameraPermissionGranted, previewState, discoveryStage, snapshot) {
        if (!cameraPermissionGranted || snapshot == null || discoveryStage != 0) return@LaunchedEffect
        if (previewState !is PreviewState.Streaming && previewState !is PreviewState.Error) return@LaunchedEffect

        // Single complete pass. This replaces the old advertised-then-deep duplicate scan and only
        // starts after first-frame delivery, so AUX discovery cannot steal camera-service time from
        // opening the main viewfinder.
        val deepResult = runCatching { catalog.scanValidated(context, deepScan = true) }
        deepResult.onSuccess { deep -> snapshot = deep }
        discoveryStage = 2
        if (deepResult.isFailure && snapshot == null) {
            val failure = deepResult.exceptionOrNull()
            discoveryError = failure?.message ?: failure?.javaClass?.simpleName ?: "Camera discovery failed"
        }
    }

'''
text = text[:effects_start] + effects + text[effects_end:]
text = replace_once(
    text,
    '''    LaunchedEffect(snapshot?.valuableLenses) {
        val lenses = snapshot?.valuableLenses.orEmpty()
        if (lenses.isNotEmpty()) lensStore.reconcile(lenses)
    }
''',
    '''    LaunchedEffect(snapshot?.valuableLenses, discoveryStage) {
        val lenses = snapshot?.valuableLenses.orEmpty()
        // Never persist the temporary first-frame seed lens.
        if (discoveryStage >= 2 && lenses.isNotEmpty()) lensStore.reconcile(lenses)
    }
''',
    "lens-store seed guard",
)
text = replace_once(
    text,
    '''                            Color.Black.copy(
                                alpha = if (previewState is PreviewState.Streaming) 0.03f else 0.24f,
                            ),
''',
    '''                            Color.Transparent,
''',
    "preview dim overlay",
)
text = replace_once(
    text,
    '''        previewState is PreviewState.Opening -> {
            Text("Opening camera…", color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
        }
''',
    "",
    "opening camera status",
)
path.write_text(text)


# 3) Preview controller: never close for temporary window-focus loss, reuse logical CameraDevice,
# preserve the Surface/last frame during switches, use efficient display-sized preview streams,
# and pass real DNG orientation.
path = Path("feature-camera/src/main/kotlin/com/camera/feature/camera/Camera2PreviewController.kt")
text = path.read_text()
text = replace_once(text, "import android.graphics.Matrix\n", "import android.graphics.ImageFormat\nimport android.graphics.Matrix\n", "ImageFormat import")
text = replace_once(text, "import android.hardware.camera2.CameraManager\n", "import android.hardware.camera2.CameraManager\nimport android.hardware.camera2.CameraMetadata\n", "CameraMetadata import")
text = replace_once(
    text,
    "import android.hardware.camera2.params.OutputConfiguration\n",
    "import android.hardware.camera2.params.OutputConfiguration\nimport android.hardware.camera2.params.RecommendedStreamConfigurationMap\n",
    "recommended map import",
)
text = replace_once(
    text,
    "import com.camera.core.model.PhotoLensSettings\n",
    "import com.camera.core.model.LensFacing\nimport com.camera.core.model.PhotoLensSettings\n",
    "LensFacing import",
)
text = text.replace("    private val javaCameraIds = runCatching { cameraManager.cameraIdList.toSet() }.getOrDefault(emptySet())\n", "")
text = replace_once(
    text,
    "    private var activeLensKey: String? = null\n",
    "    private var activeLensKey: String? = null\n    private var activeCameraId: String? = null\n",
    "active camera id",
)
old_focus = '''    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
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
'''
new_focus = '''    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        // Dialogs, menus, permission sheets and Compose overlays may steal window focus. They must
        // never tear down a healthy camera stream; only a real Surface/lifecycle teardown may do it.
        if (hasFocus) handler.post { if (!released) reopenIfPossible() }
    }
'''
text = replace_once(text, old_focus, new_focus, "window focus behavior")
text = text.replace("                textureView?.hasWindowFocus() == true\n", "                textureView?.isAttachedToWindow == true\n")
text = text.replace("        if (view.isAvailable && lens != null && view.hasWindowFocus()) {\n", "        if (view.isAvailable && lens != null && view.isAttachedToWindow) {\n")
text = text.replace("            if (!view.isAvailable || !view.hasWindowFocus()) return@post\n", "            if (!view.isAvailable || !view.isAttachedToWindow) return@post\n")
text = text.replace("        if (!view.isAvailable || !view.isAttachedToWindow || !view.hasWindowFocus()) return\n", "        if (!view.isAvailable || !view.isAttachedToWindow) return\n")
text = text.replace("        if (!view.isAttachedToWindow || !view.hasWindowFocus()) return\n", "        if (!view.isAttachedToWindow) return\n")
text = text.replace("                        if (released || activeLensKey != key || textureView?.hasWindowFocus() != true) {\n", "                        if (released || activeLensKey != key || textureView?.isAttachedToWindow != true) {\n")
text = text.replace("        if (released || activeLensKey != key || textureView?.hasWindowFocus() != true) {\n", "        if (released || activeLensKey != key || textureView?.isAttachedToWindow != true) {\n")
text = text.replace("                            if (cameraDevice !== camera || released || textureView?.hasWindowFocus() != true) {\n", "                            if (cameraDevice !== camera || released || textureView?.isAttachedToWindow != true) {\n")
text = text.replace("        if (!released && pendingLens?.id == lens.id && textureView?.hasWindowFocus() == true) {\n", "        if (!released && pendingLens?.id == lens.id && textureView?.isAttachedToWindow == true) {\n")
text = text.replace("        if (!view.isAvailable || !view.hasWindowFocus() || captureSession != null) return\n", "        if (!view.isAvailable || !view.isAttachedToWindow || captureSession != null) return\n")

old_java_open = '''        closeCamera()
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
'''
new_java_open = '''        // Logical/physical lens switches under the same CameraDevice do not need a device reopen.
        // Creating a new session directly lets Camera2 retire the old session and reuse the Surface.
        val reusableCamera = cameraDevice?.takeIf { activeCameraId == lens.cameraId }
        if (reusableCamera != null) {
            activeLensKey = key
            opening = true
            runCatching {
                val characteristics = cameraManager.getCameraCharacteristics(lens.cameraId)
                activeCharacteristics = characteristics
                configureZoomRange(characteristics)
                val size = choosePreviewSize(characteristics, targetAspect)
                activePreviewSize = size
                texture.setDefaultBufferSize(size.width, size.height)
                configureTransform(lens, view.width, view.height, size, targetAspect)
                createPreviewSession(reusableCamera, lens, texture, size)
            }.onFailure { error ->
                opening = false
                closeCamera(preserveSurface = true)
                emit(PreviewState.Error(error.message ?: error.javaClass.simpleName))
            }
            return
        }

        closeCamera(preserveSurface = true)
        activeCameraId = lens.cameraId
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
'''
text = replace_once(text, old_java_open, new_java_open, "same-device lens switch")
text = replace_once(
    text,
    '''        closeCamera()
        activeLensKey = key
        activeNativeDescriptor = descriptor
''',
    '''        closeCamera(preserveSurface = true)
        activeLensKey = key
        activeNativeDescriptor = descriptor
''',
    "native surface preservation",
)
text = replace_once(
    text,
    '''    private fun nativeDescriptorFor(lens: ValuableLens): NativeCameraNdkBridge.Descriptor? {
        if (lens.physicalCameraId != null) return null
        if (!lens.nativeRoutePreferred && lens.cameraId in javaCameraIds) return null
        return NativeCameraNdkBridge.descriptor(lens.cameraId)
    }
''',
    '''    private fun nativeDescriptorFor(lens: ValuableLens): NativeCameraNdkBridge.Descriptor? {
        if (lens.physicalCameraId != null || !lens.nativeRoutePreferred) return null
        return NativeCameraNdkBridge.descriptor(lens.cameraId)
    }
''',
    "normal route NDK bypass",
)
text = text.replace("                closeCamera()\n                markWaitingForCamera(lens)\n", "                closeCamera(preserveSurface = true)\n                markWaitingForCamera(lens)\n")

# Preview stream use case and switching callback state.
text = replace_once(
    text,
    '''            val output = OutputConfiguration(surface)
            lens.physicalCameraId?.let(output::setPhysicalCameraId)
            camera.createCaptureSession(
''',
    '''            val output = OutputConfiguration(surface)
            lens.physicalCameraId?.let(output::setPhysicalCameraId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val useCases = activeCharacteristics
                    ?.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                    .orEmpty()
                if (useCases.contains(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong())) {
                    output.streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
                }
            }
            camera.createCaptureSession(
''',
    "preview stream use case",
)
text = replace_once(
    text,
    '''                            captureSession = session
                            startRepeating(camera, session, surface, lens, previewSize)
''',
    '''                            opening = false
                            captureSession = session
                            startRepeating(camera, session, surface, lens, previewSize)
''',
    "session configured opening state",
)
text = replace_once(
    text,
    '''                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
''',
    '''                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            opening = false
                            session.close()
''',
    "session failed opening state",
)

# Orientation for Java RAW.
text = replace_once(
    text,
    '''            pendingAspect = targetAspect
            captureInFlight = true
            emitCapture(PhotoCaptureState.Capturing)
''',
    '''            val dngOrientation = computeDngOrientation(
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                facing = lens.facing,
            )
            pendingAspect = targetAspect
            captureInFlight = true
            emitCapture(PhotoCaptureState.Capturing)
''',
    "java dng orientation compute",
)
text = replace_once(
    text,
    '''                settings = settings,
                targetAspect = targetAspect ?: 4f / 3f,
            )
''',
    '''                settings = settings,
                targetAspect = targetAspect ?: 4f / 3f,
                dngOrientation = dngOrientation,
            )
''',
    "java dng orientation pass",
)
text = replace_once(
    text,
    '''        settings: PhotoLensSettings,
        targetAspect: Float,
    ) {
        computationalRaw.capture(
''',
    '''        settings: PhotoLensSettings,
        targetAspect: Float,
        dngOrientation: Int,
    ) {
        computationalRaw.capture(
''',
    "computational capture signature",
)
text = replace_once(
    text,
    '''            settings = settings,
            targetAspect = targetAspect,
            onAcquisitionFinished = {
''',
    '''            settings = settings,
            targetAspect = targetAspect,
            dngOrientation = dngOrientation,
            onAcquisitionFinished = {
''',
    "coordinator orientation argument",
)

# Orientation for NDK RAW.
text = replace_once(
    text,
    '''    ) {
        captureInFlight = true
        emitCapture(PhotoCaptureState.Capturing)
        val captureKey = activeLensKey
''',
    '''    ) {
        val dngOrientation = computeDngOrientation(
            sensorOrientation = descriptor.sensorOrientation ?: 0,
            facing = lens.facing,
        )
        captureInFlight = true
        emitCapture(PhotoCaptureState.Capturing)
        val captureKey = activeLensKey
''',
    "native dng orientation compute",
)
text = replace_once(
    text,
    '''                        fused = fused,
                        description = description,
                    )
''',
    '''                        fused = fused,
                        description = description,
                        orientation = dngOrientation,
                    )
''',
    "native dng orientation save",
)

# Fast close: CameraDevice.close directly, preserving TextureView Surface between lens switches.
close_start = text.index("    private fun closeCamera() {")
close_end = text.index("\n    private fun choosePreviewSize", close_start)
close_impl = '''    private fun closeCamera(preserveSurface: Boolean = false) {
        opening = false
        captureInFlight = false
        handler.removeCallbacks(clearFocusRunnable)
        computationalRaw.cancel()
        if (nativeSessionActive || activeNativeDescriptor != null) {
            NativeCameraNdkBridge.stopSession()
        }
        nativeSessionActive = false
        activeNativeDescriptor = null

        // CameraDevice.close() is the fastest device-switch path; it discards outstanding work and
        // tears down its active session. Do not serially stop/abort/close the session first.
        val device = cameraDevice
        if (device != null) {
            runCatching { device.close() }
        } else {
            runCatching { captureSession?.close() }
        }
        if (!preserveSurface) runCatching { previewSurface?.release() }
        captureSession = null
        cameraDevice = null
        if (!preserveSurface) previewSurface = null
        previewBuilder = null
        activeCharacteristics = null
        activePreviewSize = null
        activeLensKey = null
        activeCameraId = null
        lastMeteringRegion = null
        latestPreviewResult = null
        emitFocus(null)
    }
'''
text = text[:close_start] + close_impl + text[close_end:]

# Quality + latency aware preview stream selection. Prefer HAL recommended non-stalling sizes.
size_start = text.index("    private fun choosePreviewSize(characteristics: CameraCharacteristics, targetAspect: Float?): Size {")
size_end = text.index("\n    private fun configureTransform(", size_start)
size_impl = '''    private fun choosePreviewSize(characteristics: CameraCharacteristics, targetAspect: Float?): Size {
        val exhaustive = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
        val recommended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                characteristics
                    .getRecommendedStreamConfigurationMap(RecommendedStreamConfigurationMap.USECASE_PREVIEW)
                    ?.getOutputSizes(ImageFormat.PRIVATE)
                    ?.toList()
            }.getOrNull().orEmpty()
        } else emptyList()
        val sizes = (recommended.ifEmpty { exhaustive })
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { "${it.width}x${it.height}" }
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
        val viewLongEdge = textureView?.let { max(it.width, it.height) }?.coerceAtLeast(0) ?: 0
        val targetLongEdge = max(1600, (viewLongEdge * 1.20f).toInt()).coerceAtMost(1920)
        if (sizes.isEmpty()) return when {
            streamAspect > 1.55f -> Size(1920, 1080)
            else -> Size(1920, 1440)
        }
        val bounded = sizes
            .filter { max(it.width, it.height) <= 2560 && pixels(it) <= 3_700_000L }
            .ifEmpty { sizes }
        return bounded.minWithOrNull(
            compareBy<Size> { abs(sizeAspect(it) - streamAspect) }
                .thenBy { abs(max(it.width, it.height) - targetLongEdge) }
                .thenByDescending(::pixels),
        ) ?: sizes.first()
    }
'''
text = text[:size_start] + size_impl + text[size_end:]

# DNG orientation helper using Android's sensor/device rotation model.
text = replace_once(
    text,
    '''    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
''',
    '''    private fun computeDngOrientation(sensorOrientation: Int, facing: LensFacing): Int {
        val deviceDegrees = rotationDegrees(textureView?.display?.rotation ?: Surface.ROTATION_0)
        val sign = if (facing == LensFacing.FRONT) 1 else -1
        val degrees = ((sensorOrientation - deviceDegrees * sign) % 360 + 360) % 360
        return when (degrees) {
            90 -> 6   // TIFF/Exif rotate 90 CW
            180 -> 3  // rotate 180
            270 -> 8  // rotate 270 CW
            else -> 1 // normal
        }
    }

    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
''',
    "DNG orientation helper",
)
path.write_text(text)


# 4) Java RAW coordinator carries orientation to final DNG publication.
path = Path("feature-camera/src/main/kotlin/com/camera/feature/camera/ComputationalRawCaptureCoordinator.kt")
text = path.read_text()
text = replace_once(
    text,
    '''        settings: PhotoLensSettings,
        targetAspect: Float,
        onAcquisitionFinished: () -> Unit,
''',
    '''        settings: PhotoLensSettings,
        targetAspect: Float,
        dngOrientation: Int,
        onAcquisitionFinished: () -> Unit,
''',
    "coordinator capture orientation signature",
)
text = replace_once(
    text,
    '''            targetAspect = targetAspect.takeIf { it.isFinite() && it > 0f } ?: 4f / 3f,
            expectedFrames = expectedFrames,
''',
    '''            targetAspect = targetAspect.takeIf { it.isFinite() && it > 0f } ?: 4f / 3f,
            dngOrientation = dngOrientation,
            expectedFrames = expectedFrames,
''',
    "burst orientation state init",
)
text = replace_once(
    text,
    '''                        fused = fused,
                        description = description,
                    )
''',
    '''                        fused = fused,
                        description = description,
                        orientation = state.dngOrientation,
                    )
''',
    "java dng writer orientation",
)
text = replace_once(
    text,
    '''        val targetAspect: Float,
        val expectedFrames: Int,
''',
    '''        val targetAspect: Float,
        val dngOrientation: Int,
        val expectedFrames: Int,
''',
    "burst state orientation property",
)
path.write_text(text)


# 5) NDK descriptor: sensor orientation + higher-quality AUX preview size.
path = Path("camera-camera2/src/main/kotlin/com/camera/camera/camera2/NativeCameraNdkBridge.kt")
text = path.read_text()
text = replace_once(
    text,
    '''        val previewWidth: Int,
        val previewHeight: Int,
        val focalLengthMm: Float?,
''',
    '''        val previewWidth: Int,
        val previewHeight: Int,
        val sensorOrientation: Int?,
        val focalLengthMm: Float?,
''',
    "NDK descriptor sensor orientation",
)
text = replace_once(
    text,
    '''                            previewWidth = previewWidth,
                            previewHeight = previewHeight,
                            focalLengthMm = item.optPositiveFloat("focal"),
''',
    '''                            previewWidth = previewWidth,
                            previewHeight = previewHeight,
                            sensorOrientation = item.optInt("sensorOrientation", -1).takeIf { it in 0..359 },
                            focalLengthMm = item.optPositiveFloat("focal"),
''',
    "NDK descriptor parse orientation",
)
path.write_text(text)

path = Path("camera-camera2/src/main/cpp/motioncam_camera_ndk.cpp")
text = path.read_text()
text = text.replace("1920LL * 1080LL", "2560LL * 1440LL")
text = text.replace("return bestArea > 0 ? best : previews.back();", "return bestArea > 0 ? best : previews.front();")
text = replace_once(
    text,
    '''    int facing = -1;
    int hardware = -1;
''',
    '''    int facing = -1;
    int sensorOrientation = 0;
    int hardware = -1;
''',
    "native sensor orientation variable",
)
text = replace_once(
    text,
    '''    if (getEntry(metadata.get(), ACAMERA_LENS_FACING, &entry) && entry.count > 0) facing = entry.data.u8[0];
    if (getEntry(metadata.get(), ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL, &entry) && entry.count > 0) hardware = entry.data.u8[0];
''',
    '''    if (getEntry(metadata.get(), ACAMERA_LENS_FACING, &entry) && entry.count > 0) facing = entry.data.u8[0];
    if (getEntry(metadata.get(), ACAMERA_SENSOR_ORIENTATION, &entry) && entry.count > 0) sensorOrientation = entry.data.i32[0];
    if (getEntry(metadata.get(), ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL, &entry) && entry.count > 0) hardware = entry.data.u8[0];
''',
    "native sensor orientation metadata",
)
text = replace_once(
    text,
    '''        << "\\\"facing\\\":" << facing << ','
        << "\\\"hardware\\\":\\\"" << hardwareLevelName(static_cast<uint8_t>(hardware)) << "\\\","
''',
    '''        << "\\\"facing\\\":" << facing << ','
        << "\\\"sensorOrientation\\\":" << sensorOrientation << ','
        << "\\\"hardware\\\":\\\"" << hardwareLevelName(static_cast<uint8_t>(hardware)) << "\\\","
''',
    "native sensor orientation json",
)
path.write_text(text)


# 6) Final DNG: patch TIFF Orientation tag in the native-generated file before publication.
path = Path("processing-raw/src/main/kotlin/com/camera/processing/raw/FusedDngWriter.kt")
path.write_text('''package com.camera.processing.raw

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/** Publishes the complete computational DNG produced by the native C++ engine. */
object FusedDngWriter {
    fun save(
        context: Context,
        fused: FusedRaw,
        description: String = "Camera computational Linear DNG V2",
        orientation: Int = 1,
    ): String = saveFile(context, fused.file, description, orientation)

    fun saveNative(
        context: Context,
        fused: NativeFusedRaw,
        description: String = "Camera computational Linear DNG V2 · NDK RAW",
        orientation: Int = 1,
    ): String = saveFile(context, fused.file, description, orientation)

    private fun saveFile(
        context: Context,
        file: File,
        description: String,
        orientation: Int,
    ): String {
        require(file.isFile && file.length() > 0L) { "Native DNG file is missing" }
        val safeOrientation = orientation.takeIf { it in 1..8 } ?: 1
        patchTiffOrientation(file, safeOrientation)

        val resolver = context.contentResolver
        val name = "Camera_LINEAR_V2_${System.currentTimeMillis()}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.DESCRIPTION, description)
            put(MediaStore.Images.Media.ORIENTATION, orientationDegrees(safeOrientation))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create DNG MediaStore entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output, 1024 * 1024) }
            } ?: error("Unable to open DNG output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri.toString()
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Native writer already emits TIFF tag 274; update its SHORT value without re-encoding pixels. */
    private fun patchTiffOrientation(file: File, orientation: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < 16L) return
            val first = raf.readUnsignedByte()
            val second = raf.readUnsignedByte()
            val little = first == 0x49 && second == 0x49
            val big = first == 0x4d && second == 0x4d
            if (!little && !big) return

            fun readU16(): Int {
                val a = raf.readUnsignedByte()
                val b = raf.readUnsignedByte()
                return if (little) a or (b shl 8) else (a shl 8) or b
            }
            fun readU32(): Long {
                val a = raf.readUnsignedByte().toLong()
                val b = raf.readUnsignedByte().toLong()
                val c = raf.readUnsignedByte().toLong()
                val d = raf.readUnsignedByte().toLong()
                return if (little) {
                    a or (b shl 8) or (c shl 16) or (d shl 24)
                } else {
                    (a shl 24) or (b shl 16) or (c shl 8) or d
                }
            }
            fun writeU16(value: Int) {
                if (little) {
                    raf.write(value and 0xff)
                    raf.write((value ushr 8) and 0xff)
                } else {
                    raf.write((value ushr 8) and 0xff)
                    raf.write(value and 0xff)
                }
            }

            if (readU16() != 42) return
            val ifdOffset = readU32()
            if (ifdOffset < 8L || ifdOffset > raf.length() - 2L) return
            raf.seek(ifdOffset)
            val count = readU16()
            for (index in 0 until count) {
                val entryOffset = ifdOffset + 2L + index * 12L
                if (entryOffset + 12L > raf.length()) break
                raf.seek(entryOffset)
                val tag = readU16()
                val type = readU16()
                val valueCount = readU32()
                if (tag == 274 && type == 3 && valueCount >= 1L) {
                    raf.seek(entryOffset + 8L)
                    writeU16(orientation)
                    writeU16(0)
                    return
                }
            }
        }
    }

    private fun orientationDegrees(orientation: Int): Int = when (orientation) {
        6 -> 90
        3 -> 180
        8 -> 270
        else -> 0
    }
}
''')

print("latency/preview/orientation patch applied")
