package com.camera.feature.camera

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.weight
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
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.camera2.AndroidCameraCatalog
import com.camera.core.model.ZoomLabel

@Composable
fun CameraBootstrapScreen() {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<CameraCatalogSnapshot?>(null) }
    var discoveryError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { AndroidCameraCatalog(context).scan() }
            .onSuccess { snapshot = it }
            .onFailure { discoveryError = it.message ?: it.javaClass.simpleName }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
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
                        GlassChip("CAMERA2")
                        GlassChip("•••")
                    }

                    Spacer(Modifier.weight(1f))

                    DiscoveryStatus(snapshot, discoveryError)

                    Spacer(Modifier.weight(1f))

                    val lenses = snapshot?.valuableLenses.orEmpty()
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
                                LensPill(
                                    text = ZoomLabel.resolve(null, lens.displayZoomAnchor),
                                    selected = index == lenses.indexOfFirst { it.displayZoomAnchor?.let { z -> kotlin.math.abs(z - 1f) < 0.18f } == true }
                                        .takeIf { it } ?: (index == 0),
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
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        )

                        Box(
                            Modifier
                                .size(82.dp)
                                .border(5.dp, Color.White, CircleShape)
                                .padding(6.dp)
                                .background(Color.White, CircleShape)
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
            }
        }
    }
}

@Composable
private fun DiscoveryStatus(snapshot: CameraCatalogSnapshot?, error: String?) {
    when {
        error != null -> {
            Text("Camera discovery failed", color = Color(0xFFFF8A80), fontSize = 13.sp)
            Text(error, color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
        }
        snapshot == null -> {
            Text("Scanning camera hardware…", color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
            Text("Phase 1 · capability discovery", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        }
        else -> {
            Text(
                "${snapshot.valuableLenses.size} valuable lenses · ${snapshot.deviceProfiles.size} routes",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
            )
            val rawCount = snapshot.deviceProfiles.count { it.supportsRaw }
            val logicalCount = snapshot.deviceProfiles.count { it.supportsLogicalMultiCamera }
            Text(
                "$rawCount RAW routes · $logicalCount logical routes",
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun GlassChip(text: String) {
    Box(
        Modifier
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
