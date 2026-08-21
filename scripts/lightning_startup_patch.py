from pathlib import Path

# Hybrid catalog: rear-only critical path + bounded parallel Java/NDK metadata discovery.
path = Path('camera-camera2/src/main/kotlin/com/camera/camera/camera2/AndroidCameraCatalog.kt')
text = path.read_text()
text = text.replace(
    'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n',
    'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.async\nimport kotlinx.coroutines.awaitAll\nimport kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.sync.Semaphore\nimport kotlinx.coroutines.sync.withPermit\nimport kotlinx.coroutines.withContext\n',
    1,
)
text = text.replace(
    '    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)\n',
    '    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)\n'
    '    // Bounded metadata concurrency avoids serial discovery without flooding weak HALs.\n'
    '    private val metadataSlots = Semaphore(4)\n',
    1,
)

primary_start = text.index('    /**\n     * Critical-path startup scan.')
scan_start = text.index('    suspend fun scan(deepScan: Boolean)', primary_start)
primary = '''    /**
     * Absolute critical-path startup scan. Resolve the first normal framework rear camera and
     * return immediately. Front/AUX/NDK discovery is intentionally excluded so Compose can bind
     * the rear viewfinder while all remaining metadata work runs concurrently in the background.
     */
    suspend fun scanPrimaryRearCamera(): CameraCatalogSnapshot = withContext(Dispatchers.Default) {
        val javaIds = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        var firstCompatible: Pair<String, CameraCharacteristics>? = null
        var firstBack: Pair<String, CameraCharacteristics>? = null

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
        val profiles = selected?.let { (id, chars) ->
            listOf(
                buildJavaProfile(
                    routeCameraId = id,
                    physicalCameraId = null,
                    parentLogicalCameraId = null,
                    routeKind = CameraRouteKind.ENUMERATED,
                    characteristics = chars,
                    inheritedFacing = null,
                ),
            )
        }.orEmpty()
        val resolution = ValuableCameraResolver.resolve(profiles)
        CameraCatalogSnapshot(
            deviceProfiles = profiles,
            valuableLenses = resolution.lenses,
            diagnosticsJson = diagnosticsJson(
                profiles = profiles,
                hidden = resolution.hiddenRouteKeys,
                javaIds = javaIds,
                ndkIds = emptyList(),
            ),
        )
    }

'''
text = text[:primary_start] + primary + text[scan_start:]

scan_start = text.index('    suspend fun scan(deepScan: Boolean)')
ndk_by_id = text.index('        val ndkById = ndkDescriptors.associateBy { it.id }', scan_start)
scan_prefix = '''    suspend fun scan(deepScan: Boolean): CameraCatalogSnapshot = withContext(Dispatchers.Default) {
        val javaIds = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())

        // Java Camera2 and NDK metadata are independent read-only work: execute both at once.
        val (enumerated, ndkDescriptors) = coroutineScope {
            val ndkDeferred = async(Dispatchers.Default) {
                NativeCameraNdkBridge.enumerateRawCameras(deepScan = deepScan)
            }
            val javaDeferred = javaIds.map { id ->
                async(Dispatchers.IO) {
                    metadataSlots.withPermit {
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
                }
            }
            javaDeferred.awaitAll().filterNotNull() to ndkDeferred.await()
        }

'''
text = text[:scan_start] + scan_prefix + text[ndk_by_id:]

physical_start = text.index('        val physicalMembers = buildList {', scan_start)
routes_start = text.index('        val javaRouteKeys =', physical_start)
physical = '''        // Logical physical-member metadata is also read concurrently with a conservative cap.
        val physicalMembers = coroutineScope {
            enrichedEnumerated.flatMap { parent ->
                parent.logicalPhysicalIds.map { physicalId ->
                    async(Dispatchers.IO) {
                        metadataSlots.withPermit {
                            val chars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
                                ?: return@withPermit null
                            var child = buildJavaProfile(
                                routeCameraId = parent.routeCameraId,
                                physicalCameraId = physicalId,
                                parentLogicalCameraId = parent.routeCameraId,
                                routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                                characteristics = chars,
                                inheritedFacing = parent.facing,
                            )
                            val childHadNoPhotoStreams = child.maxPhotoPixels == 0L
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
                                    streams = if (parentHasFrameworkRaw) {
                                        parent.streams
                                    } else {
                                        parent.streams.copy(rawSizes = emptyList())
                                    },
                                    supportsRaw = child.supportsRaw || parentHasFrameworkRaw,
                                    discoveryWarnings = child.discoveryWarnings +
                                        "Inherited logical-parent stream candidates",
                                )
                            }
                            child
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

'''
text = text[:physical_start] + physical + text[routes_start:]
path.write_text(text)

# Validation wrapper follows the rear-only micro-scan.
path = Path('camera-camera2/src/main/kotlin/com/camera/camera/camera2/ValidatedCameraCatalog.kt')
text = path.read_text()
old = '''suspend fun AndroidCameraCatalog.scanPrimaryValidated(
    context: Context,
): CameraCatalogSnapshot = validateMetadata(context, scanPrimaryCameras())
'''
new = '''suspend fun AndroidCameraCatalog.scanPrimaryRearValidated(
    context: Context,
): CameraCatalogSnapshot = validateMetadata(context, scanPrimaryRearCamera())
'''
if old not in text:
    raise SystemExit('primary validation wrapper not found')
path.write_text(text.replace(old, new, 1))

# UI: publish rear snapshot immediately; advertised metadata overlaps preview opening.
path = Path('feature-camera/src/main/kotlin/com/camera/feature/camera/CameraBootstrapScreen.kt')
text = path.read_text()
text = text.replace(
    'import com.camera.camera.camera2.scanPrimaryValidated\n',
    'import com.camera.camera.camera2.scanPrimaryRearValidated\n',
    1,
)
text = text.replace(
    '    // 0=primary-only, 1=advertised routes complete, 2=deep hidden-AUX complete.\n',
    '    // 0=rear preview seed, 1=advertised routes complete, 2=deep hidden-AUX complete.\n',
    1,
)
effects_start = text.index('    LaunchedEffect(cameraPermissionGranted) {', text.index('    LaunchedEffect(Unit)'))
effects_end = text.index('    LaunchedEffect(snapshot?.valuableLenses)', effects_start)
effects = '''    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        discoveryError = null
        discoveryStage = 0

        // Critical path: return as soon as the first normal rear camera is known.
        val primaryResult = runCatching { catalog.scanPrimaryRearValidated(context) }
        val primary = primaryResult.getOrNull()
        if (primary != null && primary.valuableLenses.isNotEmpty()) {
            snapshot = primary
            return@LaunchedEffect
        }

        val advertisedResult = runCatching { catalog.scanValidated(context, deepScan = false) }
        advertisedResult.onSuccess { advertised -> snapshot = advertised }
        discoveryStage = 1
        if (snapshot == null) {
            val failure = advertisedResult.exceptionOrNull() ?: primaryResult.exceptionOrNull()
            discoveryError = failure?.message ?: failure?.javaClass?.simpleName ?: "Camera discovery failed"
        }
    }

    LaunchedEffect(cameraPermissionGranted, snapshot, discoveryStage) {
        if (!cameraPermissionGranted || snapshot == null || discoveryStage != 0) return@LaunchedEffect

        // One UI turn gives TextureView/openCamera first dispatch; metadata then runs in parallel.
        kotlinx.coroutines.yield()
        val advertisedResult = runCatching { catalog.scanValidated(context, deepScan = false) }
        advertisedResult.onSuccess { advertised -> snapshot = advertised }
        discoveryStage = 1
    }

    LaunchedEffect(cameraPermissionGranted, previewState, discoveryStage, snapshot) {
        if (!cameraPermissionGranted || discoveryStage != 1) return@LaunchedEffect
        val hasRawRoute = snapshot?.valuableLenses?.any { it.rawSupported } == true
        when {
            previewState is PreviewState.Streaming -> kotlinx.coroutines.delay(80L)
            previewState is PreviewState.Error -> Unit
            !hasRawRoute -> Unit
            else -> return@LaunchedEffect
        }

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

old = '''    // PHOTO is DNG-only. Do not fake RAW with a processed route. Other modes may use the full
    // valuable-lens list later when their native pipelines are implemented.
    val photoLenses = allVisibleLenses.filter { it.rawSupported }

    val mainIndex = photoLenses.indexOfFirst { lens ->
        val config = storedConfigs[lens.id.value]
        val anchor = config?.displayZoomAnchor ?: lens.displayZoomAnchor
        lens.facing == LensFacing.BACK && anchor?.let { abs(it - 1f) < 0.18f } == true
    }.let { index ->
        when {
            index >= 0 -> index
            photoLenses.indexOfFirst { it.facing == LensFacing.BACK } >= 0 ->
                photoLenses.indexOfFirst { it.facing == LensFacing.BACK }
            else -> 0
        }
    }

    val selectedLens = photoLenses.firstOrNull { it.id.value == selectedLensId }
        ?: photoLenses.getOrNull(mainIndex)
    val facingLenses = photoLenses.filter { lens -> lens.facing == selectedLens?.facing }
'''
new = '''    // Capture remains genuine-RAW-only. Preview can use normal rear while NDK RAW is discovered.
    val photoLenses = allVisibleLenses.filter { it.rawSupported }
    val previewLenses = if (photoLenses.isNotEmpty()) photoLenses else allVisibleLenses

    val mainIndex = previewLenses.indexOfFirst { lens ->
        val config = storedConfigs[lens.id.value]
        val anchor = config?.displayZoomAnchor ?: lens.displayZoomAnchor
        lens.facing == LensFacing.BACK && anchor?.let { abs(it - 1f) < 0.18f } == true
    }.let { index ->
        when {
            index >= 0 -> index
            previewLenses.indexOfFirst { it.facing == LensFacing.BACK } >= 0 ->
                previewLenses.indexOfFirst { it.facing == LensFacing.BACK }
            else -> 0
        }
    }

    val selectedLens = previewLenses.firstOrNull { it.id.value == selectedLensId }
        ?: previewLenses.getOrNull(mainIndex)
    val facingLenses = previewLenses.filter { lens -> lens.facing == selectedLens?.facing }
'''
if old not in text:
    raise SystemExit('lens selection block not found')
text = text.replace(old, new, 1)
text = text.replace(
    '                        rawLensCount = photoLenses.size,\n                        onRequestPermission =',
    '                        rawLensCount = photoLenses.size,\n                        discoveryComplete = discoveryStage >= 2,\n                        onRequestPermission =',
    1,
)
text = text.replace(
    '                        val shutterReady = selectedLens != null &&\n                            previewState is PreviewState.Streaming &&',
    '                        val shutterReady = selectedLens?.rawSupported == true &&\n                            previewState is PreviewState.Streaming &&',
    1,
)
text = text.replace(
    '    rawLensCount: Int,\n    onRequestPermission: () -> Unit,\n',
    '    rawLensCount: Int,\n    discoveryComplete: Boolean,\n    onRequestPermission: () -> Unit,\n',
    1,
)
text = text.replace(
    '        snapshot != null && rawLensCount == 0 -> {\n',
    '        snapshot != null && rawLensCount == 0 && discoveryComplete -> {\n',
    1,
)
path.write_text(text)

# Preview: a normal Java main/front camera must never synchronously trigger NDK enumeration.
path = Path('feature-camera/src/main/kotlin/com/camera/feature/camera/Camera2PreviewController.kt')
text = path.read_text()
old = '''    private fun nativeDescriptorFor(lens: ValuableLens): NativeCameraNdkBridge.Descriptor? {
        if (lens.physicalCameraId != null) return null
        val descriptor = NativeCameraNdkBridge.descriptor(lens.cameraId) ?: return null
        return descriptor.takeIf { lens.nativeRoutePreferred || lens.cameraId !in javaCameraIds }
    }
'''
new = '''    private fun nativeDescriptorFor(lens: ValuableLens): NativeCameraNdkBridge.Descriptor? {
        if (lens.physicalCameraId != null) return null
        if (!lens.nativeRoutePreferred && lens.cameraId in javaCameraIds) return null
        return NativeCameraNdkBridge.descriptor(lens.cameraId)
    }
'''
if old not in text:
    raise SystemExit('nativeDescriptorFor block not found')
path.write_text(text.replace(old, new, 1))
