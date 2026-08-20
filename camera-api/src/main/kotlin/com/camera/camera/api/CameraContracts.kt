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
)

interface CameraSession {
    val activeLens: Flow<ValuableLens?>
    suspend fun open(lens: ValuableLens)
    suspend fun close()
}
