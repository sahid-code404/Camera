package com.camera.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.camera2.AndroidCameraCatalog
import com.camera.camera.camera2.scanPrimaryRearValidated
import com.camera.camera.camera2.scanValidated
import com.camera.core.model.LensFacing
import com.camera.core.model.PhotoLensSettings
import com.camera.core.model.ValuableLens
import com.camera.core.model.ZoomLabel
import com.camera.feature.settings.LensConfigStore
import com.camera.feature.settings.LensManagerPanel
import kotlin.math.abs

private val CameraYellow = Color(0xFFFFD60A)
private val CameraGlass = Color(0xB82C2C2E)
private const val GLOBAL_PHOTO_ASPECT_KEY = "photo_aspect_global"

/** Landscape composition ratio. The portrait viewfinder displays its reciprocal. */
private enum class PhotoAspect(val label: String, val ratio: Float) {
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
}

@Composable
fun CameraBootstrapScreen() {
    val context = LocalContext.current
    val catalog = remember(context) { AndroidCameraCatalog(context) }
    val lensStore = remember(context) { LensConfigStore(context) }
    val uiPrefs = remember(context) { context.getSharedPreferences("camera_ui", Context.MODE_PRIVATE) }
    val storedConfigs by lensStore.configs.collectAsStateWithLifecycle(initialValue = emptyMap())

    var snapshot by remember { mutableStateOf<CameraCatalogSnapshot?>(null) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    var previewState by remember { mutableStateOf<PreviewState>(PreviewState.Idle) }
    var zoomState by remember { mutableStateOf(ZoomState()) }
    var focusPoint by remember { mutableStateOf<FocusPoint?>(null) }
    var captureState by remember { mutableStateOf<PhotoCaptureState>(PhotoCaptureState.Idle) }
    var lensManagerOpen by remember { mutableStateOf(false) }
    var aspectMenuOpen by remember { mutableStateOf(false) }
    var selectedLensId by remember { mutableStateOf<String?>(null) }
    var selectedAspect by remember {
        mutableStateOf(
            runCatching {
                PhotoAspect.valueOf(
                    uiPrefs.getString(
                        GLOBAL_PHOTO_ASPECT_KEY,
                        uiPrefs.getString("default_photo_aspect", PhotoAspect.FOUR_THREE.name),
                    ) ?: PhotoAspect.FOUR_THREE.name,
                )
            }.getOrDefault(PhotoAspect.FOUR_THREE),
        )
    }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // 0=rear preview seed only, 2=complete advertised + physical + hidden AUX discovery.
    var discoveryStage by remember { mutableStateOf(0) }

    val controller = remember(context) {
        Camera2PreviewController(
            context = context,
            onState = { previewState = it },
            onZoomState = { zoomState = it },
            onFocusPoint = { focusPoint = it },
            onCaptureState = { captureState = it },
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraPermissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(cameraPermissionGranted) {
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

    LaunchedEffect(snapshot?.valuableLenses, discoveryStage) {
        val lenses = snapshot?.valuableLenses.orEmpty()
        // Never persist the temporary first-frame seed lens.
        if (discoveryStage >= 2 && lenses.isNotEmpty()) lensStore.reconcile(lenses)
    }

    val allVisibleLenses = snapshot?.valuableLenses
        .orEmpty()
        .filter { lens -> storedConfigs[lens.id.value]?.visible != false }
        .sortedBy { lens -> storedConfigs[lens.id.value]?.position ?: lens.userOrder }

    // Capture remains genuine-RAW-only. Preview can use normal rear while NDK RAW is discovered.
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

    LaunchedEffect(selectedLens?.id?.value) {
        if (selectedLens != null && selectedLensId != selectedLens.id.value) {
            selectedLensId = selectedLens.id.value
        }
        aspectMenuOpen = false
        focusPoint = null
        captureState = PhotoCaptureState.Idle
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cameraPermissionGranted && selectedLens != null) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f / selectedAspect.ratio)
                                .clipToBounds(),
                        ) {
                            CameraPreview(
                                controller = controller,
                                lens = selectedLens,
                                targetAspect = selectedAspect.ratio,
                                modifier = Modifier.fillMaxSize(),
                            )

                            focusPoint?.let { point ->
                                Box(
                                    modifier = Modifier
                                        .offset(
                                            x = maxWidth * point.x - 22.dp,
                                            y = maxHeight * point.y - 22.dp,
                                        )
                                        .size(44.dp)
                                        .border(1.5.dp, CameraYellow, RoundedCornerShape(7.dp)),
                                )
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Color.Transparent,
                        ),
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassChip("RAW")
                        GlassChip(selectedAspect.label) { aspectMenuOpen = !aspectMenuOpen }
                        GlassChip("•••") {
                            if (snapshot?.valuableLenses?.isNotEmpty() == true) lensManagerOpen = true
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    CameraStatus(
                        snapshot = snapshot,
                        discoveryError = discoveryError,
                        previewState = previewState,
                        captureState = captureState,
                        permissionGranted = cameraPermissionGranted,
                        rawLensCount = photoLenses.size,
                        discoveryComplete = discoveryStage >= 2,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )

                    Spacer(Modifier.weight(1f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (facingLenses.isEmpty()) {
                            LensPill("RAW", selected = true)
                        } else {
                            facingLenses.forEach { lens ->
                                val config = storedConfigs[lens.id.value]
                                val baseAnchor = config?.displayZoomAnchor ?: lens.displayZoomAnchor
                                val selected = lens.id.value == selectedLens?.id?.value
                                val label = if (selected && zoomState.ratio > 1.015f && baseAnchor != null) {
                                    ZoomLabel.formatAnchor(baseAnchor * zoomState.ratio)
                                } else {
                                    ZoomLabel.resolve(
                                        customLabel = config?.customZoomLabel,
                                        numericAnchor = baseAnchor,
                                    )
                                }
                                LensPill(
                                    text = label,
                                    selected = selected,
                                    onClick = { selectedLensId = lens.id.value },
                                )
                            }
                        }
                    }

                    if (zoomState.maxRatio > 1.05f) {
                        Spacer(Modifier.height(7.dp))
                        Text(
                            text = "Pinch to zoom",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .background(
                                    if (captureState is PhotoCaptureState.Saved) {
                                        Color.White.copy(alpha = 0.22f)
                                    } else {
                                        Color.White.copy(alpha = 0.14f)
                                    },
                                    RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (captureState is PhotoCaptureState.Saved) {
                                Text("✓", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        val shutterReady = selectedLens?.rawSupported == true &&
                            previewState is PreviewState.Streaming &&
                            captureState !is PhotoCaptureState.Capturing &&
                            captureState !is PhotoCaptureState.Saving
                        Box(
                            Modifier
                                .size(82.dp)
                                .border(5.dp, Color.White, CircleShape)
                                .padding(6.dp)
                                .background(
                                    if (captureState is PhotoCaptureState.Capturing) {
                                        Color.White.copy(alpha = 0.55f)
                                    } else {
                                        Color.White
                                    },
                                    CircleShape,
                                )
                                .clickable(enabled = shutterReady) {
                                    val lens = selectedLens ?: return@clickable
                                    captureState = PhotoCaptureState.Capturing
                                    controller.capturePhoto(
                                        targetAspect = selectedAspect.ratio,
                                        settings = storedConfigs[lens.id.value]?.photo ?: PhotoLensSettings(),
                                    )
                                },
                        )

                        Box(
                            Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.14f), CircleShape)
                                .clickable {
                                    selectedLensId = oppositeFacingLens(
                                        current = selectedLens,
                                        lenses = photoLenses,
                                        configs = storedConfigs,
                                    )?.id?.value ?: selectedLensId
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("↻", color = Color.White, fontSize = 22.sp)
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Mode("VIDEO")
                        Mode("PHOTO", true)
                        Mode("PORTRAIT")
                    }
                }

                if (aspectMenuOpen) {
                    AspectPicker(
                        selected = selectedAspect,
                        onSelect = { aspect ->
                            selectedAspect = aspect
                            aspectMenuOpen = false
                            focusPoint = null
                            uiPrefs.edit()
                                .putString(GLOBAL_PHOTO_ASPECT_KEY, aspect.name)
                                .putString("default_photo_aspect", aspect.name)
                                .apply()
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 88.dp),
                    )
                }

                if (lensManagerOpen) {
                    LensManagerPanel(
                        lenses = snapshot?.valuableLenses.orEmpty(),
                        onDismiss = { lensManagerOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    controller: Camera2PreviewController,
    lens: ValuableLens,
    targetAspect: Float,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isOpaque = true
                controller.bind(this, lens, targetAspect)
            }
        },
        modifier = modifier,
        update = { view -> controller.bind(view, lens, targetAspect) },
    )
}

@Composable
private fun AspectPicker(
    selected: PhotoAspect,
    onSelect: (PhotoAspect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(CameraGlass, RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoAspect.entries.forEach { aspect ->
            Box(
                modifier = Modifier
                    .background(
                        if (aspect == selected) Color.White.copy(alpha = 0.17f) else Color.Transparent,
                        RoundedCornerShape(18.dp),
                    )
                    .clickable { onSelect(aspect) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = aspect.label,
                    color = if (aspect == selected) CameraYellow else Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (aspect == selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CameraStatus(
    snapshot: CameraCatalogSnapshot?,
    discoveryError: String?,
    previewState: PreviewState,
    captureState: PhotoCaptureState,
    permissionGranted: Boolean,
    rawLensCount: Int,
    discoveryComplete: Boolean,
    onRequestPermission: () -> Unit,
) {
    when {
        !permissionGranted -> {
            Text(
                "Camera permission required",
                color = CameraYellow,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onRequestPermission),
            )
        }
        discoveryError != null -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera discovery failed", color = Color(0xFFFF8A80), fontSize = 13.sp)
                Text(discoveryError, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
            }
        }
        snapshot != null && rawLensCount == 0 && discoveryComplete -> {
            Text("No enabled lens exposes RAW_SENSOR", color = Color(0xFFFF8A80), fontSize = 13.sp)
        }
        previewState is PreviewState.Error -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Preview unavailable", color = Color(0xFFFF8A80), fontSize = 13.sp)
                Text(previewState.message, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
            }
        }
        captureState is PhotoCaptureState.Error -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DNG failed", color = Color(0xFFFF8A80), fontSize = 13.sp)
                Text(captureState.message, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
            }
        }
        captureState is PhotoCaptureState.Saving -> {
            Text("Native DNG processing…", color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp)
        }
        else -> Unit
    }
}

private fun oppositeFacingLens(
    current: ValuableLens?,
    lenses: List<ValuableLens>,
    configs: Map<String, com.camera.core.model.LensUserConfig>,
): ValuableLens? {
    val desired = if (current?.facing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
    return lenses
        .filter { it.facing == desired }
        .minByOrNull { lens ->
            val anchor = configs[lens.id.value]?.displayZoomAnchor ?: lens.displayZoomAnchor ?: 1f
            abs(anchor - 1f)
        }
}

@Composable
private fun GlassChip(text: String, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        modifier
            .background(CameraGlass, CircleShape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LensPill(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        clickModifier
            .background(
                if (selected) Color.Black.copy(alpha = 0.68f) else Color.Black.copy(alpha = 0.40f),
                CircleShape,
            )
            .padding(horizontal = if (selected) 18.dp else 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) CameraYellow else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun Mode(text: String, selected: Boolean = false) {
    Text(
        text = text,
        color = if (selected) CameraYellow else Color.White.copy(alpha = 0.68f),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
    )
}
