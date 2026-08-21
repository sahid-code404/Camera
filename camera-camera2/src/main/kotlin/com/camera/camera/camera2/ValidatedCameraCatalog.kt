package com.camera.camera.camera2

import android.content.Context
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.api.CameraRouteProbeResult
import com.camera.camera.capability.ValuableCameraResolver
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.ValuableLens

/**
 * Metadata discovery followed by runtime validation of only the routes that can actually become
 * user-facing lenses.
 *
 * The resolver may have several aliases for one piece of glass (Java direct, logical/physical,
 * NDK vendor route). We probe the preferred route first. If it fails, that route is rejected and
 * the resolver is run again so the next alias can be tried. This keeps startup work bounded to
 * roughly one successful probe per physical lens while still recovering when the nominally
 * preferred Java route cannot really deliver RAW but an NDK route can.
 *
 * Java routes must configure both preview and RAW-only sessions. NDK routes go further and acquire
 * one real RAW frame through the same adaptive native session used by the camera UI.
 */
suspend fun AndroidCameraCatalog.scanValidated(context: Context): CameraCatalogSnapshot {
    val metadata = scan()
    val profiles = metadata.deviceProfiles
    if (profiles.isEmpty()) return metadata

    val profileByKey = profiles.associateBy(::routeKey)
    val allKeys = profileByKey.keys
    val rejectedKeys = mutableSetOf<String>()
    val probeResults = linkedMapOf<String, CameraRouteProbeResult>()
    var resolution = ValuableCameraResolver.resolve(profiles)

    AndroidCameraRouteProbe(context).use { javaProbe ->
        val nativeProbe = NativeCameraRouteProbe(context)

        repeat(profiles.size + 1) {
            val selectedKeys = resolution.lenses.map(::routeKey)
            val pending = selectedKeys.filterNot(probeResults::containsKey)
            if (pending.isEmpty()) return@repeat

            pending.forEach { key ->
                val profile = profileByKey[key] ?: return@forEach
                val result = if ("NDK_ENUMERATED" in profile.capabilities) {
                    nativeProbe.probe(profile)
                } else {
                    javaProbe.probe(profile)
                }
                probeResults[key] = result
                if (!result.usable) rejectedKeys += key
            }

            // Unprobed aliases remain candidates. Failed routes do not. If a selected route failed,
            // this immediately promotes the best remaining alias for the same physical optics.
            val candidateKeys = allKeys - rejectedKeys
            resolution = ValuableCameraResolver.resolve(profiles, candidateKeys)
        }
    }

    // Every final user-facing route must have passed a runtime probe. Do not leave an unvalidated
    // metadata-only camera visible merely because the retry loop exhausted.
    val verifiedKeys = probeResults.values
        .filter { it.usable }
        .mapTo(mutableSetOf()) { it.routeKey }
    val verifiedResolution = ValuableCameraResolver.resolve(profiles, verifiedKeys)

    return metadata.copy(
        valuableLenses = verifiedResolution.lenses,
        routeProbeResults = probeResults.values.toList(),
    )
}

private fun routeKey(profile: CameraDeviceProfile): String =
    "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

private fun routeKey(lens: ValuableLens): String =
    "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}"
