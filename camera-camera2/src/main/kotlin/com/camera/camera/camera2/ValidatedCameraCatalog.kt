package com.camera.camera.camera2

import android.content.Context
import com.camera.camera.api.CameraCatalogSnapshot
import com.camera.camera.capability.ValuableCameraResolver

/**
 * Metadata discovery followed by runtime CameraDevice/session validation.
 *
 * This must only be called after CAMERA permission has been granted. Failed or vendor-filtered
 * routes stay in diagnostics, but are removed from the default user-facing valuable-lens list.
 */
suspend fun AndroidCameraCatalog.scanValidated(context: Context): CameraCatalogSnapshot {
    val metadata = scan()
    val probeResults = AndroidCameraRouteProbe(context).use { probe ->
        probe.probeAll(metadata.deviceProfiles)
    }
    val usableKeys = probeResults.filter { it.usable }.mapTo(mutableSetOf()) { it.routeKey }
    val resolution = ValuableCameraResolver.resolve(metadata.deviceProfiles, usableKeys)
    return metadata.copy(
        valuableLenses = resolution.lenses,
        routeProbeResults = probeResults,
    )
}
