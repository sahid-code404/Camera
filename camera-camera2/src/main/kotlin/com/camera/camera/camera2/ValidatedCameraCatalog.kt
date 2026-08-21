package com.camera.camera.camera2

import android.content.Context
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.api.CameraRouteProbeResult
import com.camera.camera.api.CameraRouteProbeStatus
import com.camera.camera.capability.ValuableCameraResolver
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.ValuableLens

/**
 * Lightning-fast catalog validation.
 *
 * Progressive startup validates the tiny primary front/back metadata snapshot first, then reuses
 * the same cache filtering for advertised and deep AUX discovery after the viewfinder is usable.
 * It never opens every candidate camera. Previously rejected aliases are removed immediately,
 * while verified aliases retain their trust annotation. The selected lens is validated naturally
 * by the real preview session; successful preview/DNG operations promote metadata -> SESSION -> RAW.
 */
suspend fun AndroidCameraCatalog.scanPrimaryRearValidated(
    context: Context,
): CameraCatalogSnapshot = validateMetadata(context, scanPrimaryRearCamera())

suspend fun AndroidCameraCatalog.scanValidated(
    context: Context,
    deepScan: Boolean = false,
): CameraCatalogSnapshot = validateMetadata(context, scan(deepScan = deepScan))

private suspend fun validateMetadata(
    context: Context,
    metadata: CameraCatalogSnapshot,
): CameraCatalogSnapshot {
    val profiles = metadata.deviceProfiles
    if (profiles.isEmpty()) return metadata

    val cache = CameraRouteValidationCache.prepare(context, profiles)
    val profileByKey = profiles.associateBy(::routeKey)
    val candidateKeys = profileByKey.keys - cache.rejectedRouteKeys
    val resolution = ValuableCameraResolver.resolve(profiles, candidateKeys)
    val selectedKeys = resolution.lenses.mapTo(mutableSetOf(), ::routeKey)

    val cachedResults = cache.allUsableRouteKeys
        .filter { it in selectedKeys }
        .mapNotNull { key ->
            val profile = profileByKey[key] ?: return@mapNotNull null
            CameraRouteProbeResult(
                routeCameraId = profile.routeCameraId,
                physicalCameraId = profile.physicalCameraId,
                status = CameraRouteProbeStatus.SESSION_CONFIGURED,
                message = when (cache.level(key)) {
                    CameraRouteValidationCache.TrustLevel.RAW -> "Cached RAW verified"
                    CameraRouteValidationCache.TrustLevel.SESSION -> "Cached preview/session verified"
                    null -> "Cached route"
                },
            )
        }

    return metadata.copy(
        valuableLenses = resolution.lenses,
        routeProbeResults = cachedResults,
    )
}

private fun routeKey(profile: CameraDeviceProfile): String =
    "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

private fun routeKey(lens: ValuableLens): String =
    "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}"
