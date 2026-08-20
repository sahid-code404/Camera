package com.camera.camera.api

import com.camera.core.model.ValuableLens
import kotlinx.coroutines.flow.Flow

interface CameraCatalog {
    suspend fun scan(): CameraCatalogSnapshot
}

data class CameraCatalogSnapshot(
    val valuableLenses: List<ValuableLens>,
    val diagnosticsJson: String,
)

interface CameraSession {
    val activeLens: Flow<ValuableLens?>
    suspend fun open(lens: ValuableLens)
    suspend fun close()
}
