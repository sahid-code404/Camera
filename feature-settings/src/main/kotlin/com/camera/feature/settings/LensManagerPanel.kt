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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import com.camera.core.model.ValuableLens
import com.camera.core.model.ZoomLabel
import kotlinx.coroutines.launch

/** First working Lens Manager. Advanced per-lens photo/video tuning is layered onto this later. */
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
                "Choose which cameras appear, their order, role, name, and the exact zoom label shown on the camera screen.",
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
                Text("Examples: 0.6×, 1×, 35mm, MAIN, TELE. Label never changes optical zoom math.")
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

        if (!canHide && config.visible) {
            Text(
                "At least one ${lens.facing.name.lowercase()} camera must stay visible.",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 10.sp,
            )
        }
    }
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
    val raw = if (lens.rawSupported) "RAW" else "processed"
    val physical = lens.physicalCameraId?.let { "physical $it" } ?: "direct ${lens.cameraId}"
    return "$focal · $sensor · $raw · $physical"
}
