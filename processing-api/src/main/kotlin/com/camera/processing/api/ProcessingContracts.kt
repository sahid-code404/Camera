package com.camera.processing.api

interface PhotoProcessingEngine {
    suspend fun process(request: PhotoProcessingRequest): PhotoProcessingResult
}

data class PhotoProcessingRequest(
    val lensStableId: String,
    val sourceUris: List<String>,
    val outputKind: String,
)

sealed interface PhotoProcessingResult {
    data class Success(val outputUri: String) : PhotoProcessingResult
    data class Failure(val message: String) : PhotoProcessingResult
}
