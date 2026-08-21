from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected one match, found {count}')
    p.write_text(text.replace(old, new, 1))


# Do not leak an NDK-only RAW declaration into a Java physical-child route.
replace_once(
    'camera-camera2/src/main/kotlin/com/camera/camera/camera2/AndroidCameraCatalog.kt',
    '                    if (childHadNoPhotoStreams && parent.maxPhotoPixels > 0L) {\n'
    '                        child = child.copy(\n'
    '                            streams = parent.streams,\n'
    '                            supportsRaw = child.supportsRaw || parentHasFrameworkRaw,\n',
    '                    if (childHadNoPhotoStreams && parent.maxPhotoPixels > 0L) {\n'
    '                        child = child.copy(\n'
    '                            streams = if (parentHasFrameworkRaw) {\n'
    '                                parent.streams\n'
    '                            } else {\n'
    '                                parent.streams.copy(rawSizes = emptyList())\n'
    '                            },\n'
    '                            supportsRaw = child.supportsRaw || parentHasFrameworkRaw,\n',
)

# Keep deep hidden-ID discovery away from first-preview latency. If the fast pass has no RAW lens,
# deep scan immediately because there is no preview to protect; otherwise start it after streaming.
path = 'feature-camera/src/main/kotlin/com/camera/feature/camera/CameraBootstrapScreen.kt'
replace_once(
    path,
    '    var cameraPermissionGranted by remember {\n'
    '        mutableStateOf(\n'
    '            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==\n'
    '                PackageManager.PERMISSION_GRANTED,\n'
    '        )\n'
    '    }\n\n'
    '    val controller = remember(context) {\n',
    '    var cameraPermissionGranted by remember {\n'
    '        mutableStateOf(\n'
    '            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==\n'
    '                PackageManager.PERMISSION_GRANTED,\n'
    '        )\n'
    '    }\n'
    '    var deepDiscoveryDone by remember { mutableStateOf(false) }\n\n'
    '    val controller = remember(context) {\n',
)
replace_once(
    path,
    '        if (fast != null) {\n'
    '            snapshot = fast\n'
    '            kotlinx.coroutines.yield()\n'
    '            runCatching { catalog.scanValidated(context, deepScan = true) }\n'
    '                .onSuccess { deep -> snapshot = deep }\n'
    '        }\n'
    '    }\n\n'
    '    LaunchedEffect(snapshot?.valuableLenses) {\n',
    '        if (fast != null) {\n'
    '            snapshot = fast\n'
    '            deepDiscoveryDone = false\n'
    '            if (fast.valuableLenses.none { it.rawSupported }) {\n'
    '                runCatching { catalog.scanValidated(context, deepScan = true) }\n'
    '                    .onSuccess { deep -> snapshot = deep }\n'
    '                deepDiscoveryDone = true\n'
    '            }\n'
    '        }\n'
    '    }\n\n'
    '    LaunchedEffect(cameraPermissionGranted, previewState, deepDiscoveryDone) {\n'
    '        if (!cameraPermissionGranted || deepDiscoveryDone || previewState !is PreviewState.Streaming) {\n'
    '            return@LaunchedEffect\n'
    '        }\n'
    '        deepDiscoveryDone = true\n'
    '        kotlinx.coroutines.delay(250L)\n'
    '        runCatching { catalog.scanValidated(context, deepScan = true) }\n'
    '            .onSuccess { deep -> snapshot = deep }\n'
    '    }\n\n'
    '    LaunchedEffect(snapshot?.valuableLenses) {\n',
)

print('AUX follow-up applied')
