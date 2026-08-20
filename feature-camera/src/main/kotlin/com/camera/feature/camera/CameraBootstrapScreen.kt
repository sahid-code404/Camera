package com.camera.feature.camera

import android.Manifest
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.camera.core.model.LensFacing
import com.camera.core.model.ValuableLens
import com.camera.core.model.ZoomLabel
import com.camera.feature.settings.LensConfigStore
import com.camera.feature.settings.LensManagerPanel
import kotlin.math.abs

@Composable
fun CameraBootstrapScreen() {
    val context = LocalContext.current
    val catalog = remember(context) { AndroidCameraCatalog(context) }
    val lensStore = remember(context) { LensConfigStore(context) }
    val storedConfigs by lensStore.configs.collectAsStateWithLifecycle(initialValue = emptyMap())

    var snapshot by remember { mutableStateOf<CameraCatalogSnapshot?>(null) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    var previewState by remember { mutableStateOf<PreviewState>(PreviewState.Idle) }
    var lensManagerOpen by remember { mutableStateOf(false) }
    var selectedLensId by remember { mutableStateOf<String?>(null) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraPermissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Do metadata discovery only on startup. Session-probing every route while a live preview is
    // being opened causes avoidable camera-service contention on vendor HALs (notably Xiaomi/QTI).
    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        discoveryError = null
        runCatching { catalog.scan() }
            .onSuccess { snapshot = it }
            .onFailure { discoveryError = it.message ?: it.javaClass.simpleName }
    }

    LaunchedEffect(snapshot?.valuableLenses) {
        val lenses = snapshot?.valuableLenses.orEmpty()
        if (lenses.isNotEmpty()) lensStore.reconcile(lenses)
    }

    val visibleLenses = snapshot?.valuableLenses
        .orEmpty()
        .filter { lens -> storedConfigs[lens.id.value]?.visible != false }
        .sortedBy { lens -> storedConfigs[lens.id.value]?.position ?: lens.userOrder }

    val mainIndex = visibleLenses.indexOfFirst { lens ->
        val config = storedConfigs[lens.id.value]
        val anchor = config?.displayZoomAnchor ?: lens.displayZoomAnchor
        lens.facing == LensFacing.BACK && anchor?.let { abs(it - 1f) < 0.18f } == true
    }.let { index ->
        when {
            index >= 0 -> index
            visibleLenses.indexOfFirst { it.facing == LensFacing.BACK } >= 0 ->
                visibleLenses.indexOfFirst { it.facing == LensFacing.BACK }
            else -> 0
        }
    }

    val selectedLens = visibleLenses.firstOrNull { it.id.value == selectedLensId }
        ?: visibleLenses.getOrNull(mainIndex)

    LaunchedEffect(selectedLens?.id?.value) {
        if (selectedLens != null && selectedLensId != selectedLens.id.value) {
            selectedLensId = selectedLens.id.value
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                if (cameraPermissionGranted && selectedLens != null) {
                    CameraPreview(
                        lens = selectedLens,
                        onState = { previewState = it },
                    )
                }

                // A very light scrim keeps labels readable without hiding the actual preview.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (previewState is PreviewState.Streaming) 0.08f else 0.30f)),
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
                    ) {
                        GlassChip("⚡")
                        GlassChip(
                            when (previewState) {
                                is PreviewState.Streaming -> "LIVE"
                                is PreviewState.Opening -> "OPENING"
                                is PreviewState.Error -> "CAMERA ERROR"
                                PreviewState.Idle -> "CAMERA2"
                            },
                        )
                        GlassChip("•••") {
                            if (snapshot?.valuableLenses?.isNotEmpty() == true) lensManagerOpen = true
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    CameraStatus(
                        snapshot = snapshot,
                        discoveryError = discoveryError,
                        previewState = previewState,
                        permissionGranted = cameraPermissionGranted,
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
                        if (visibleLenses.isEmpty()) {
                            LensPill("…", selected = true)
                        } else {
                            visibleLenses.forEach { lens ->
                                val config = storedConfigs[lens.id.value]
                                LensPill(
                                    text = ZoomLabel.resolve(
                                        customLabel = config?.customZoomLabel,
                                        numericAnchor = config?.displayZoomAnchor ?: lens.displayZoomAnchor,
                                    ),
                                    selected = lens.id.value == selectedLens?.id?.value,
                                    onClick = { selectedLensId = lens.id.value },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                        )

                        Box(
                            Modifier
                                .size(82.dp)
                                .border(5.dp, Color.White, CircleShape)
                                .padding(6.dp)
                                .background(Color.White, CircleShape),
                        )

                        Box(
                            Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                                .clickable {
                                    selectedLensId = oppositeFacingLens(
                                        current = selectedLens,
                                        lenses = visibleLenses,
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
    lens: ValuableLens,
    onState: (PreviewState) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(context) { Camera2PreviewController(context, onState) }

    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isOpaque = true
                controller.bind(this, lens)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view -> controller.bind(view, lens) },
    )

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
}

@Composable
private fun CameraStatus(
    snapshot: CameraCatalogSnapshot?,
    discoveryError: String?,
    previewState: PreviewState,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
) {
    when {
        !permissionGranted -> {
            Text(
                "Camera permission required",
                color = Color(0xFFFFD54F),
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onRequestPermission),
            )
        }
        discoveryError != null -> {
            Text("Camera discovery failed", color = Color(0xFFFF8A80), fontSize = 13.sp)
            Text(discoveryError, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
        }
        previewState is PreviewState.Error -> {
            Text("Preview unavailable", color = Color(0xFFFF8A80), fontSize = 13.sp)
            Text(previewState.message, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
        }
        snapshot == null -> {
            Text("Scanning camera hardware…", color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
        }
        previewState is PreviewState.Opening -> {
            Text("Opening camera…", color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
        }
        previewState is PreviewState.Streaming -> {
            val physical = previewState.physicalCameraId?.let { " · physical $it" }.orEmpty()
            Text(
                "${snapshot.valuableLenses.size} lenses · ${previewState.width}×${previewState.height}$physical",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 11.sp,
            )
        }
    }
}

private fun oppositeFacingLens(current: ValuableLens?, lenses: List<ValuableLens>): ValuableLens? {
    val desired = if (current?.facing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
    return lenses.firstOrNull { it.facing == desired }
}

@Composable
private fun GlassChip(text: String, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.48f), CircleShape)
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
                if (selected) Color.Black.copy(alpha = 0.62f) else Color.Black.copy(alpha = 0.38f),
                CircleShape,
            )
            .padding(horizontal = if (selected) 18.dp else 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color(0xFFFFD54F) else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun Mode(text: String, selected: Boolean = false) {
    Text(
        text = text,
        color = if (selected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.68f),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
    )
}
