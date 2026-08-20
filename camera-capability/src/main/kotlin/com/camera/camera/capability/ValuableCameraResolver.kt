package com.camera.camera.capability

import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.CameraRouteKind
import com.camera.core.model.LensFacing
import com.camera.core.model.LensRole
import com.camera.core.model.LensStableId
import com.camera.core.model.ValuableLens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Converts raw Camera2 routes into useful photographic lenses without relying on numeric camera IDs.
 *
 * When [validatedRouteKeys] is supplied, only routes that successfully configured a Camera2 session
 * are eligible. A null set means metadata-only discovery and is intentionally weaker evidence.
 */
object ValuableCameraResolver {
    data class Resolution(
        val lenses: List<ValuableLens>,
        val hiddenRouteKeys: Set<String>,
    )

    fun resolve(
        profiles: List<CameraDeviceProfile>,
        validatedRouteKeys: Set<String>? = null,
    ): Resolution {
        if (profiles.isEmpty()) return Resolution(emptyList(), emptySet())

        val enumeratedIds = profiles
            .filter { it.routeKind == CameraRouteKind.ENUMERATED }
            .mapTo(mutableSetOf()) { it.routeCameraId }

        val deduplicated = profiles.filterNot { profile ->
            profile.routeKind == CameraRouteKind.LOGICAL_PHYSICAL_MEMBER &&
                profile.physicalCameraId in enumeratedIds
        }

        val result = mutableListOf<ValuableLens>()
        val hidden = mutableSetOf<String>()

        deduplicated.groupBy { it.facing }.forEach { (facing, facingProfiles) ->
            val photoProfiles = facingProfiles.filter { profile ->
                val metadataEligible = isPhotographicRoute(profile)
                val validationEligible = validatedRouteKeys == null || routeKey(profile) in validatedRouteKeys
                if (!metadataEligible || !validationEligible) hidden += routeKey(profile)
                metadataEligible && validationEligible
            }
            val baseEquivalent = chooseBaseEquivalent(facing, photoProfiles)

            photoProfiles.forEach { profile ->
                val logicalAggregator = profile.routeKind == CameraRouteKind.ENUMERATED &&
                    profile.supportsLogicalMultiCamera &&
                    profile.logicalPhysicalIds.isNotEmpty() &&
                    photoProfiles.any { it.parentLogicalCameraId == profile.routeCameraId }

                if (logicalAggregator) {
                    hidden += routeKey(profile)
                    return@forEach
                }

                val equivalent = profile.equivalentFocalLength35Mm
                val anchor = if (equivalent != null && baseEquivalent != null && baseEquivalent > 0f) {
                    (equivalent / baseEquivalent).coerceIn(0.1f, 30f)
                } else null

                result += ValuableLens(
                    id = LensStableId(stableId(profile)),
                    cameraId = profile.routeCameraId,
                    physicalCameraId = profile.physicalCameraId,
                    facing = facing,
                    inferredRole = inferRole(facing, equivalent),
                    roleConfidence = roleConfidence(facing, equivalent),
                    focalLengthMm = profile.primaryFocalLengthMm,
                    sensorWidthMm = profile.sensorWidthMm,
                    displayZoomAnchor = anchor,
                    rawSupported = profile.supportsRaw,
                )
            }
        }

        val ordered = result.sortedWith(
            compareBy<ValuableLens> { facingRank(it.facing) }
                .thenBy { it.displayZoomAnchor ?: Float.MAX_VALUE }
                .thenBy { it.focalLengthMm ?: Float.MAX_VALUE }
                .thenBy { it.id.value },
        ).mapIndexed { index, lens -> lens.copy(userOrder = index) }

        return Resolution(ordered, hidden)
    }

    private fun isPhotographicRoute(profile: CameraDeviceProfile): Boolean {
        if (profile.facing == LensFacing.UNKNOWN) return false
        if (profile.maxPhotoPixels <= 0L) return false
        val onlyDepth = profile.capabilities.isNotEmpty() && profile.capabilities.all {
            it == "DEPTH_OUTPUT" || it == "MOTION_TRACKING"
        }
        return !onlyDepth
    }

    private fun chooseBaseEquivalent(
        facing: LensFacing,
        profiles: List<CameraDeviceProfile>,
    ): Float? {
        val values = profiles.mapNotNull { it.equivalentFocalLength35Mm }.filter { it > 0f }
        if (values.isEmpty()) return null
        val target = if (facing == LensFacing.FRONT) 26f else 25f
        return values.minByOrNull { abs(it - target) }
    }

    private fun inferRole(facing: LensFacing, equivalentMm: Float?): LensRole {
        if (facing == LensFacing.FRONT) {
            return when {
                equivalentMm == null -> LensRole.FRONT_WIDE
                equivalentMm < 23f -> LensRole.FRONT_ULTRAWIDE
                else -> LensRole.FRONT_WIDE
            }
        }
        if (facing != LensFacing.BACK) return LensRole.UNKNOWN
        return when {
            equivalentMm == null -> LensRole.UNKNOWN
            equivalentMm < 20f -> LensRole.ULTRAWIDE
            equivalentMm < 43f -> LensRole.WIDE
            equivalentMm < 90f -> LensRole.TELE
            else -> LensRole.LONG_TELE
        }
    }

    private fun roleConfidence(facing: LensFacing, equivalentMm: Float?): Float {
        if (equivalentMm == null) return if (facing == LensFacing.FRONT) 0.45f else 0.15f
        return when (facing) {
            LensFacing.BACK -> when {
                equivalentMm < 17f -> 0.96f
                equivalentMm < 20f -> 0.82f
                equivalentMm in 22f..32f -> 0.96f
                equivalentMm < 43f -> 0.78f
                equivalentMm in 48f..82f -> 0.92f
                equivalentMm >= 100f -> 0.94f
                else -> 0.72f
            }
            LensFacing.FRONT -> 0.78f
            else -> 0.40f
        }
    }

    private fun stableId(profile: CameraDeviceProfile): String {
        val effectiveId = profile.physicalCameraId ?: profile.routeCameraId
        val eqBucket = profile.equivalentFocalLength35Mm?.roundToInt()?.toString() ?: "na"
        val sensorBucket = profile.sensorWidthMm?.let { (it * 100f).roundToInt() }?.toString() ?: "na"
        return listOf(
            profile.facing.name.lowercase(),
            profile.routeCameraId,
            effectiveId,
            eqBucket,
            sensorBucket,
        ).joinToString(":")
    }

    private fun routeKey(profile: CameraDeviceProfile): String =
        "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

    private fun facingRank(facing: LensFacing): Int = when (facing) {
        LensFacing.BACK -> 0
        LensFacing.FRONT -> 1
        LensFacing.EXTERNAL -> 2
        LensFacing.UNKNOWN -> 3
    }
}
