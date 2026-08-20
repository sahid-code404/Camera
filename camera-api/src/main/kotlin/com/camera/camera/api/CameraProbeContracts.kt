package com.camera.camera.api

import com.camera.core.model.CameraDeviceProfile

enum class CameraRouteProbeStatus {
    SESSION_CONFIGURED,
    OPEN_FAILED,
    SESSION_FAILED,
    NO_PROBE_STREAM,
    PERMISSION_DENIED,
    TIMED_OUT,
}

data class CameraRouteProbeResult(
    val routeCameraId: String,
    val physicalCameraId: String?,
    val status: CameraRouteProbeStatus,
    val message: String? = null,
) {
    val routeKey: String get() = "$routeCameraId:${physicalCameraId ?: "direct"}"
    val usable: Boolean get() = status == CameraRouteProbeStatus.SESSION_CONFIGURED
}

/**
 * Stronger-than-metadata validation. A route is considered usable only after the app can open its
 * CameraDevice and configure a small processed output for the direct or requested physical sensor.
 */
interface CameraRouteProbe {
    suspend fun probe(profile: CameraDeviceProfile): CameraRouteProbeResult

    suspend fun probeAll(profiles: List<CameraDeviceProfile>): List<CameraRouteProbeResult> =
        profiles.map { probe(it) }
}
