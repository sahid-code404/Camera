package com.camera.camera.camera2

import android.content.Context
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.api.CameraRouteProbeResult
import com.camera.camera.api.CameraRouteProbeStatus
import com.camera.camera.capability.ValuableCameraResolver
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.ValuableLens

/**
 * Fast metadata discovery plus runtime validation of routes that can become user-facing lenses.
 *
 * Hot path: when the ROM/HAL camera fingerprint is unchanged, previously verified routes are loaded
 * from SharedPreferences and the lens graph is resolved immediately. No camera device is opened.
 *
 * Cold path: de-duplicate first, then probe only the currently preferred route for each physical
 * lens. Java physical children that share a logical parent are batch-probed through one open device.
 * If a preferred route fails, the resolver promotes the next Java/physical/NDK alias and only that
 * replacement is tested. NDK startup validation is session-only; a real successful photo later
 * promotes the route to RAW_VERIFIED in the cache.
 */
suspend fun AndroidCameraCatalog.scanValidated(context: Context): CameraCatalogSnapshot {
    val metadata = scan()
    val profiles = metadata.deviceProfiles
    if (profiles.isEmpty()) return metadata

    val profileByKey = profiles.associateBy(::routeKey)

    // Lightning-fast normal launch. The fingerprint covers the Android build and all relevant HAL
    // route/optics/RAW/preview metadata, so a ROM or camera-provider change naturally invalidates it.
    CameraRouteValidationCache.load(context, profiles)?.let { cached ->
        val cachedResolution = ValuableCameraResolver.resolve(profiles, cached.allUsableRouteKeys)
        if (cachedResolution.lenses.isNotEmpty()) {
            val cachedResults = cached.allUsableRouteKeys.mapNotNull { key ->
                val profile = profileByKey[key] ?: return@mapNotNull null
                CameraRouteProbeResult(
                    routeCameraId = profile.routeCameraId,
                    physicalCameraId = profile.physicalCameraId,
                    status = CameraRouteProbeStatus.SESSION_CONFIGURED,
                    message = when (cached.level(key)) {
                        CameraRouteValidationCache.TrustLevel.RAW -> "Cached RAW verified"
                        CameraRouteValidationCache.TrustLevel.SESSION -> "Cached session verified"
                        null -> "Cached verified route"
                    },
                )
            }
            return metadata.copy(
                valuableLenses = cachedResolution.lenses,
                routeProbeResults = cachedResults,
            )
        }
    }

    val allKeys = profileByKey.keys
    val rejectedKeys = mutableSetOf<String>()
    val probeResults = linkedMapOf<String, CameraRouteProbeResult>()
    var resolution = ValuableCameraResolver.resolve(profiles)

    AndroidCameraRouteProbe(context).use { javaProbe ->
        val nativeProbe = NativeCameraRouteProbe(context)
        var iterations = 0
        while (iterations++ <= profiles.size) {
            val selectedKeys = resolution.lenses.map(::routeKey)
            val pendingKeys = selectedKeys.filterNot(probeResults::containsKey)
            if (pendingKeys.isEmpty()) break

            val pendingProfiles = pendingKeys.mapNotNull(profileByKey::get)
            val javaPending = pendingProfiles.filter { "NDK_ENUMERATED" !in it.capabilities }
            val nativePending = pendingProfiles.filter { "NDK_ENUMERATED" in it.capabilities }

            // One open per routeCameraId, even when several physical children share the same parent.
            javaProbe.probeBatch(javaPending).forEach { result ->
                probeResults[result.routeKey] = result
                if (!result.usable) rejectedKeys += result.routeKey
            }

            // The native bridge intentionally owns one session at a time. Sequential probing avoids
            // ERROR_MAX_CAMERAS_IN_USE and false negatives on conservative vendor camera providers.
            nativePending.forEach { profile ->
                val result = nativeProbe.probe(profile)
                probeResults[result.routeKey] = result
                if (!result.usable) rejectedKeys += result.routeKey
            }

            val candidateKeys = allKeys - rejectedKeys
            val nextResolution = ValuableCameraResolver.resolve(profiles, candidateKeys)
            val nextKeys = nextResolution.lenses.map(::routeKey)
            resolution = nextResolution

            // If nothing changed and every selected route has already been probed, we are done.
            if (nextKeys.all(probeResults::containsKey)) break
        }
    }

    // Final UI is constructed exclusively from routes that actually passed runtime validation.
    val verifiedKeys = probeResults.values
        .filter { it.usable }
        .mapTo(mutableSetOf()) { it.routeKey }
    val verifiedResolution = ValuableCameraResolver.resolve(profiles, verifiedKeys)

    if (verifiedResolution.lenses.isNotEmpty()) {
        CameraRouteValidationCache.saveSessionValidated(
            context = context,
            profiles = profiles,
            routeKeys = verifiedResolution.lenses.mapTo(mutableSetOf(), ::routeKey),
        )
    }

    return metadata.copy(
        valuableLenses = verifiedResolution.lenses,
        routeProbeResults = probeResults.values.toList(),
    )
}

private fun routeKey(profile: CameraDeviceProfile): String =
    "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

private fun routeKey(lens: ValuableLens): String =
    "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}"
