package com.camera.camera.api

import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.ValuableLens
import kotlinx.coroutines.flow.Flow

interface CameraCatalog {
    suspend fun scan(): CameraCatalogSnapshot
}

data class CameraCatalogSnapshot(
    val deviceProfiles: List<CameraDeviceProfile>,
    val valuableLenses: List<ValuableLens>,
    val diagnosticsJson: String,
    val routeProbeResults: List<CameraRouteProbeResult> = emptyList(),
) {
    val sessionValidated: Boolean get() = routeProbeResults.isNotEmpty()
    val usableRouteCount: Int get() = routeProbeResults.count { it.usable }
}

interface CameraSession {
    val activeLens: Flow<ValuableLens?>
    suspend fun open(lens: ValuableLens)
    suspend fun close()
}
