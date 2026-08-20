package com.camera.camera.capability

import com.camera.core.model.LensRole

/**
 * Domain placeholder for Phase 1.
 *
 * Real implementation must score optics, route topology, openability and stream usefulness.
 * It must never classify a lens from a fixed numeric Camera2 ID table.
 */
object ValuableCameraResolver {
    data class Candidate(
        val cameraId: String,
        val physicalCameraId: String?,
        val focalLengthMm: Float?,
        val sensorWidthMm: Float?,
        val isLogicalAggregator: Boolean,
        val photoCapable: Boolean,
        val openable: Boolean,
    )

    data class Decision(
        val visibleByDefault: Boolean,
        val role: LensRole,
        val confidence: Float,
        val reason: String,
    )

    fun placeholderDecision(candidate: Candidate): Decision =
        Decision(
            visibleByDefault = candidate.photoCapable && candidate.openable && !candidate.isLogicalAggregator,
            role = LensRole.UNKNOWN,
            confidence = 0f,
            reason = "Phase 1 resolver not implemented yet",
        )
}
