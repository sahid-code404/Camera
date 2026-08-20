package com.camera.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.camera2.AndroidCameraCatalog
import com.camera.camera.camera2.scanValidated
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
    var lensManagerOpen by remember { mutableStateOf(false) }
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
        runCatching { catalog.scan() }
            .onSuccess { snapshot = it }
            .onFailure { discoveryError = it.message ?: it.javaClass.simpleName }
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        discoveryError = null
        runCatching { catalog.scanValidated(context) }
            .onSuccess { snapshot = it }
            .onFailure { discoveryError = it.message ?: it.javaClass.simpleName }
    }

    LaunchedEffect(snapshot?.valuableLenses) {
        val lenses = snapshot?.valuableLenses.orEmpty()
        if (lenses.isNotEmpty()) lensStore.reconcile(lenses)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
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
                        GlassChip(if (snapshot?.sessionValidated == true) "VALIDATED" else "CAMERA2")
                        GlassChip("•••") {
                            if (snapshot?.valuableLenses?.isNotEmpty() == true) lensManagerOpen = true
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    DiscoveryStatus(
                        snapshot = snapshot,
                        error = discoveryError,
                        permissionGranted = cameraPermissionGranted,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )

                    Spacer(Modifier.weight(1f))

                    val lenses = snapshot?.valuableLenses
                        .orEmpty()
                        .filter { lens -> storedConfigs[lens.id.value]?.visible != false }
                        .sortedBy { lens -> storedConfigs[lens.id.value]?.position ?: lens.userOrder }
                    val mainIndex = lenses.indexOfFirst { lens ->
                        val config = storedConfigs[lens.id.value]
                        val anchor = config?.displayZoomAnchor ?: lens.displayZoomAnchor
                        anchor?.let { abs(it - 1f) < 0.18f } == true
                    }.let { if (it >= 0) it else 0 }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (lenses.isEmpty()) {
                            LensPill("…", selected = true)
                        } else {
                            lenses.forEachIndexed { index, lens ->
                                val config = storedConfigs[lens.id.value]
                                LensPill(
                                    text = ZoomLabel.resolve(
                                        customLabel = config?.customZoomLabel,
                                        numericAnchor = config?.displayZoomAnchor ?: lens.displayZoomAnchor,
                                    ),
                                    selected = index == mainIndex,
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
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
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
                                .background(Color.White.copy(alpha = 0.12f), CircleShape),
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
private fun DiscoveryStatus(
    snapshot: CameraCatalogSnapshot?,
    error: String?,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
) {
    when {
        error != null -> {
            Text("Camera discovery failed", color = Color(0xFFFF8A80), fontSize = 13.sp)
            Text(error, color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
        }
        snapshot == null -> {
            Text("Scanning camera hardware…", color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
            Text("Phase 1 · capability discovery", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        }
        !permissionGranted -> {
            Text(
                "${snapshot.deviceProfiles.size} metadata routes found",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
            )
            Text(
                "Grant camera permission to validate lenses",
                color = Color(0xFFFFD54F),
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onRequestPermission),
            )
        }
        snapshot.sessionValidated -> {
            Text(
                "${snapshot.valuableLenses.size} valuable lenses · ${snapshot.usableRouteCount} usable routes",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
            )
            val rawCount = snapshot.deviceProfiles.count { it.supportsRaw }
            val failed = snapshot.routeProbeResults.count { !it.usable }
            Text(
                "$rawCount RAW metadata routes · $failed routes rejected by probe",
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 11.sp,
            )
        }
        else -> {
            Text(
                "${snapshot.valuableLenses.size} candidate lenses · ${snapshot.deviceProfiles.size} routes",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
            )
            Text(
                "Validating Camera2 sessions…",
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun GlassChip(text: String, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        modifier
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LensPill(text: String, selected: Boolean = false) {
    Box(
        Modifier
            .background(
                if (selected) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.42f),
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
