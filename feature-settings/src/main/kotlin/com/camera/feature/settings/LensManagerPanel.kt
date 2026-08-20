package com.camera.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camera.core.model.LensFacing
import com.camera.core.model.LensRole
import com.camera.core.model.LensUserConfig
import com.camera.core.model.PhotoLensSettings
import com.camera.core.model.ValuableLens
import com.camera.core.model.ZoomLabel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Lens layout + per-lens computational DNG tuning. */
@Composable
fun LensManagerPanel(
    lenses: List<ValuableLens>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { LensConfigStore(context) }
    val scope = rememberCoroutineScope()
    var configs by remember { mutableStateOf<List<LensUserConfig>>(emptyList()) }
    val lensById = remember(lenses) { lenses.associateBy { it.id.value } }

    LaunchedEffect(lenses) {
        configs = store.reconcile(lenses)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF080808),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(
                    "Lenses",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scope.launch {
                            store.saveLayout(configs)
                            onDismiss()
                        }
                    },
                    enabled = configs.isNotEmpty(),
                ) {
                    Text("Save")
                }
            }

            Text(
                "Choose visible cameras, order, zoom labels, and independent DNG processing for every lens.",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                configs.forEachIndexed { index, config ->
                    val lens = lensById[config.lensId.value] ?: return@forEachIndexed
                    LensConfigCard(
                        lens = lens,
                        config = config,
                        canHide = canHide(config, configs, lensById),
                        canMoveUp = index > 0,
                        canMoveDown = index < configs.lastIndex,
                        onChange = { changed ->
                            configs = configs.toMutableList().also { it[index] = changed }
                        },
                        onMove = { direction ->
                            val destination = index + direction
                            if (destination !in configs.indices) return@LensConfigCard
                            val mutable = configs.toMutableList()
                            val moved = mutable.removeAt(index)
                            mutable.add(destination, moved)
                            configs = mutable.mapIndexed { position, item -> item.copy(position = position) }
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LensConfigCard(
    lens: ValuableLens,
    config: LensUserConfig,
    canHide: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (LensUserConfig) -> Unit,
    onMove: (Int) -> Unit,
) {
    val confirmed = config.confirmedRole
    val visibleLabel = ZoomLabel.resolve(config.customZoomLabel, config.displayZoomAnchor ?: lens.displayZoomAnchor)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    config.customName ?: defaultLensName(lens),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    metadataSummary(lens),
                    color = Color.White.copy(alpha = 0.48f),
                    fontSize = 11.sp,
                )
            }
            Text(
                visibleLabel,
                color = Color(0xFFFFD54F),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(end = 10.dp),
            )
            Switch(
                checked = config.visible,
                onCheckedChange = { visible ->
                    if (visible || canHide) onChange(config.copy(visible = visible))
                },
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        OutlinedTextField(
            value = config.customName.orEmpty(),
            onValueChange = { onChange(config.copy(customName = it.take(32))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Custom lens name") },
            singleLine = true,
        )

        OutlinedTextField(
            value = config.customZoomLabel.orEmpty(),
            onValueChange = {
                onChange(config.copy(customZoomLabel = it.take(ZoomLabel.MAX_CUSTOM_LABEL_LENGTH)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Custom zoom label") },
            supportingText = {
                Text("0.6×, 1×, 35mm, MAIN, TELE — presentation only, never optical math.")
            },
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    onChange(
                        config.copy(
                            confirmedRole = nextRole(
                                current = confirmed,
                                inferred = lens.inferredRole,
                                facing = lens.facing,
                            ),
                        ),
                    )
                },
            ) {
                Text(if (confirmed == null) "Confirm ${lens.inferredRole.pretty()}" else "Role ${confirmed.pretty()}")
            }
            if (confirmed != null) {
                TextButton(onClick = { onChange(config.copy(confirmedRole = null)) }) {
                    Text("Auto")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Text("↑") }
            TextButton(onClick = { onMove(1) }, enabled = canMoveDown) { Text("↓") }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        if (lens.rawSupported) {
            DngProcessingControls(
                photo = config.photo,
                onChange = { photo -> onChange(config.copy(photo = photo)) },
            )
        } else {
            Text(
                "DNG photo unavailable: this route exposes no RAW_SENSOR stream. It can still remain available for other modes such as video when supported.",
                color = Color(0xFFFFB4AB),
                fontSize = 11.sp,
            )
        }

        if (!canHide && config.visible) {
            Text(
                "At least one ${lens.facing.name.lowercase()} camera must stay visible.",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun DngProcessingControls(
    photo: PhotoLensSettings,
    onChange: (PhotoLensSettings) -> Unit,
) {
    Text(
        "Computational DNG",
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("HDR", color = Color.White, fontSize = 13.sp)
            Text(
                "Bracket + native C++ fusion",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
            )
        }
        Switch(
            checked = photo.hdrEnabled ?: true,
            onCheckedChange = { onChange(photo.copy(hdrEnabled = it)) },
        )
    }

    FloatControl(
        label = "HDR strength",
        value = photo.hdrStrength ?: 0.72f,
        range = 0f..2f,
        onValueChange = { onChange(photo.copy(hdrStrength = it)) },
    )
    FloatControl(
        label = "Highlights",
        value = photo.highlightProtection ?: 0.55f,
        range = 0f..2f,
        onValueChange = { onChange(photo.copy(highlightProtection = it)) },
    )
    FloatControl(
        label = "Shadows",
        value = photo.shadowRecovery ?: 0.18f,
        range = 0f..2f,
        onValueChange = { onChange(photo.copy(shadowRecovery = it)) },
    )
    FloatControl(
        label = "Denoise",
        value = photo.denoise ?: 0.40f,
        range = 0f..2f,
        onValueChange = { onChange(photo.copy(denoise = it)) },
    )
    FloatControl(
        label = "Saturation",
        value = photo.saturation ?: 1f,
        range = 0f..2.5f,
        onValueChange = { onChange(photo.copy(saturation = it)) },
    )
    FloatControl(
        label = "Sharpness",
        value = photo.sharpness ?: 0.28f,
        range = 0f..2f,
        onValueChange = { onChange(photo.copy(sharpness = it)) },
    )

    val upscale = (photo.upscaleFactor ?: 1f).roundToInt().coerceIn(1, 4)
    Text(
        "DNG upscale ${upscale}×",
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
    Slider(
        value = upscale.toFloat(),
        onValueChange = {
            onChange(photo.copy(upscaleFactor = it.roundToInt().coerceIn(1, 4).toFloat()))
        },
        valueRange = 1f..4f,
        steps = 2,
    )
    Text(
        "Bayer-preserving native upscale. 1× keeps native dimensions; higher factors are experimental until validated on this device.",
        color = Color.White.copy(alpha = 0.42f),
        fontSize = 10.sp,
    )
}

@Composable
private fun FloatControl(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("%.2f".format(value), color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
    )
}

private fun canHide(
    config: LensUserConfig,
    configs: List<LensUserConfig>,
    lensById: Map<String, ValuableLens>,
): Boolean {
    if (!config.visible) return true
    val facing = lensById[config.lensId.value]?.facing ?: return true
    return configs.count { other ->
        other.visible && lensById[other.lensId.value]?.facing == facing
    } > 1
}

private fun nextRole(current: LensRole?, inferred: LensRole, facing: LensFacing): LensRole {
    if (current == null) return inferred
    val choices = when (facing) {
        LensFacing.BACK -> listOf(
            LensRole.ULTRAWIDE,
            LensRole.WIDE,
            LensRole.TELE,
            LensRole.LONG_TELE,
            LensRole.MACRO,
            LensRole.MONOCHROME,
            LensRole.UNKNOWN,
        )
        LensFacing.FRONT -> listOf(
            LensRole.FRONT_ULTRAWIDE,
            LensRole.FRONT_WIDE,
            LensRole.UNKNOWN,
        )
        else -> listOf(LensRole.WIDE, LensRole.TELE, LensRole.UNKNOWN)
    }
    val index = choices.indexOf(current).takeIf { it >= 0 } ?: 0
    return choices[(index + 1) % choices.size]
}

private fun defaultLensName(lens: ValuableLens): String = lens.inferredRole.pretty()

private fun LensRole.pretty(): String = name
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

private fun metadataSummary(lens: ValuableLens): String {
    val focal = lens.focalLengthMm?.let { "%.2f mm".format(it) } ?: "focal ?"
    val sensor = lens.sensorWidthMm?.let { "%.2f mm sensor".format(it) } ?: "sensor ?"
    val raw = if (lens.rawSupported) "DNG RAW" else "no RAW"
    val physical = lens.physicalCameraId?.let { "physical $it" } ?: "direct ${lens.cameraId}"
    return "$focal · $sensor · $raw · $physical"
}
