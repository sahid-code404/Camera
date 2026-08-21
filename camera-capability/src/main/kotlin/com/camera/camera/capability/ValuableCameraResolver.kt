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
 * Converts the raw Camera2 graph into user-facing photographic lenses.
 *
 * Vendor HALs can publish several Camera2/NDK IDs for the same piece of glass, logical aggregators,
 * physical-member aliases, depth routes and alternate ISP pipelines. The main camera UI must show
 * useful optical cameras, not raw IDs. This resolver therefore classifies by public capability and
 * optical fingerprint rather than hard-coded Qualcomm/Xiaomi camera numbers or expected counts.
 *
 * RAW routing is intentionally route-aware. A physical member may omit its own RAW capability and
 * RAW stream map even though its logical parent exposes a RAW output that can be assigned to that
 * physical sensor with OutputConfiguration.setPhysicalCameraId(). Such a child is kept as a RAW
 * candidate and the actual RAW capture session is the final authority.
 *
 * When [validatedRouteKeys] is supplied it is applied BEFORE alias selection. This is important:
 * if the normally preferred Java alias fails its runtime RAW probe, an equivalent verified NDK
 * route must be allowed to become the selected route rather than being hidden by the failed alias.
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

        val hidden = mutableSetOf<String>()

        // RAW reachability is calculated against the complete metadata graph. A validated physical
        // child can still depend on RAW stream metadata owned by its logical parent even if that
        // parent is not itself a user-facing validated route.
        val rawRouteKeys = profiles
            .filter { profile -> hasDirectRawSensorOutput(profile) || hasParentRawRoute(profile, profiles) }
            .mapTo(mutableSetOf(), ::routeKey)

        val candidateProfiles = if (validatedRouteKeys == null) {
            profiles
        } else {
            profiles.filter { routeKey(it) in validatedRouteKeys }.also { candidates ->
                val candidateKeys = candidates.mapTo(mutableSetOf(), ::routeKey)
                profiles.filterNot { routeKey(it) in candidateKeys }
                    .forEach { hidden += routeKey(it) }
            }
        }
        if (candidateProfiles.isEmpty()) return Resolution(emptyList(), hidden)

        val routeComparator = Comparator<CameraDeviceProfile> { left, right ->
            val score = routePreferenceScore(right, rawRouteKeys).compareTo(
                routePreferenceScore(left, rawRouteKeys),
            )
            if (score != 0) return@Comparator score

            val leftNumeric = left.routeCameraId.toLongOrNull()
            val rightNumeric = right.routeCameraId.toLongOrNull()
            when {
                leftNumeric != null && rightNumeric != null -> leftNumeric.compareTo(rightNumeric)
                leftNumeric != null -> -1
                rightNumeric != null -> 1
                else -> routeKey(left).compareTo(routeKey(right))
            }
        }

        // Camera HALs often expose the same sensor twice: once as a directly enumerated camera and
        // once as a physical member of a logical parent. Match those routes using the effective
        // sensor ID plus optics, then keep the route with the strongest real RAW path.
        val routeCandidates = candidateProfiles
            .groupBy(::effectiveRouteFingerprint)
            .values
            .map { group ->
                val preferred = group.sortedWith(routeComparator).first()
                group.filterNot { routeKey(it) == routeKey(preferred) }
                    .forEach { duplicate -> hidden += routeKey(duplicate) }
                preferred
            }

        val eligible = routeCandidates.filter { profile ->
            val photographic = isPhotographicRoute(profile)
            if (!photographic) hidden += routeKey(profile)
            photographic
        }.filterNot { profile ->
            // Hide a logical aggregator only when a preferred child can replace it. A parent RAW
            // output may be the transport used to reach a physical child, so children that inherit
            // a parent RAW route count as valid replacements.
            val parentHasRaw = routeKey(profile) in rawRouteKeys
            val hasPreferredChild = routeCandidates.any { child ->
                child.parentLogicalCameraId == profile.routeCameraId &&
                    (!parentHasRaw || routeKey(child) in rawRouteKeys)
            }
            val logicalAggregator = profile.routeKind == CameraRouteKind.ENUMERATED &&
                profile.supportsLogicalMultiCamera &&
                profile.logicalPhysicalIds.isNotEmpty() &&
                hasPreferredChild
            if (logicalAggregator) hidden += routeKey(profile)
            logicalAggregator
        }

        // Collapse alternate routes to the same physical lens. NDK and Java metadata are not always
        // byte-for-byte identical: the NDK path can omit aperture tables and can advertise RAW at a
        // different maximum size from the Java JPEG/remosaic route. Those are pipeline differences,
        // not extra pieces of glass, so identity is based on facing + focal length + sensor geometry
        // + active-array geometry (+ CFA when present). This prevents a 4-rear/1-front phone from
        // being presented as 5-rear/2-front simply because the HAL publishes aliases.
        val preferredProfiles = eligible
            .groupBy { profile -> opticalFingerprint(profile) ?: "route:${routeKey(profile)}" }
            .values
            .map { group ->
                val preferred = group.sortedWith(routeComparator).first()
                group.filterNot { routeKey(it) == routeKey(preferred) }
                    .forEach { duplicate -> hidden += routeKey(duplicate) }
                preferred
            }

        val groupedByFacing = preferredProfiles.groupBy { it.facing }
        val result = buildList {
            groupedByFacing.forEach { (facing, facingProfiles) ->
                val baseEquivalent = chooseBaseEquivalent(facing, facingProfiles)
                facingProfiles.forEach { profile ->
                    val equivalent = profile.equivalentFocalLength35Mm
                    val anchor = if (equivalent != null && baseEquivalent != null && baseEquivalent > 0f) {
                        (equivalent / baseEquivalent).coerceIn(0.1f, 30f)
                    } else null

                    add(
                        ValuableLens(
                            id = LensStableId(stableId(profile)),
                            cameraId = profile.routeCameraId,
                            physicalCameraId = profile.physicalCameraId,
                            facing = facing,
                            inferredRole = inferRole(facing, equivalent),
                            roleConfidence = roleConfidence(facing, equivalent),
                            focalLengthMm = profile.primaryFocalLengthMm,
                            sensorWidthMm = profile.sensorWidthMm,
                            displayZoomAnchor = anchor,
                            rawSupported = routeKey(profile) in rawRouteKeys,
                        ),
                    )
                }
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

        val onlyDepthOrTracking = profile.capabilities.isNotEmpty() && profile.capabilities.all {
            it == "DEPTH_OUTPUT" || it == "MOTION_TRACKING"
        }
        if (onlyDepthOrTracking) return false

        val hasOptics = profile.focalLengthsMm.any { it > 0f }
        val standardCamera = "BACKWARD_COMPATIBLE" in profile.capabilities ||
            profile.routeKind == CameraRouteKind.LOGICAL_PHYSICAL_MEMBER
        return hasOptics || standardCamera
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

    private fun routePreferenceScore(
        profile: CameraDeviceProfile,
        rawRouteKeys: Set<String>,
    ): Int {
        var score = 0
        // A route that can plausibly deliver RAW_SENSOR must dominate alias selection. This also
        // includes physical children that can use the logical parent's RAW output configuration.
        if (routeKey(profile) in rawRouteKeys) score += 256
        if (hasDirectRawSensorOutput(profile)) score += 32
        if (profile.routeKind == CameraRouteKind.ENUMERATED) score += 100
        if ("BACKWARD_COMPATIBLE" in profile.capabilities) score += 50
        if (profile.parentLogicalCameraId != null) score += 20
        if (profile.supportsManualSensor) score += 8
        if (profile.supportsBurstCapture) score += 4

        // If Java/physical Camera2 and an NDK vendor alias describe the same optics, prefer the
        // framework-visible route. It has richer session negotiation. If runtime validation later
        // rejects it, candidate filtering happens before this comparator and the verified NDK alias
        // can take over.
        if ("NDK_ENUMERATED" !in profile.capabilities) score += 64

        score += (profile.maxPhotoPixels / 1_000_000L).coerceAtMost(20L).toInt()
        return score
    }

    private fun hasDirectRawSensorOutput(profile: CameraDeviceProfile): Boolean =
        profile.streams.rawSizes.isNotEmpty() || profile.supportsRaw

    private fun hasParentRawRoute(
        profile: CameraDeviceProfile,
        allProfiles: List<CameraDeviceProfile>,
    ): Boolean {
        val parentId = profile.parentLogicalCameraId ?: return false
        if (profile.routeKind != CameraRouteKind.LOGICAL_PHYSICAL_MEMBER) return false
        val parent = allProfiles.firstOrNull { candidate ->
            candidate.routeKind == CameraRouteKind.ENUMERATED &&
                candidate.routeCameraId == parentId &&
                candidate.physicalCameraId == null
        } ?: return false
        return hasDirectRawSensorOutput(parent)
    }

    /**
     * First-pass route identity. The logical parent ID is intentionally not used when a physical ID
     * exists; this lets a direct vendor alias and its logical/physical route meet in the same bucket
     * without hard-coded camera numbers.
     */
    private fun effectiveRouteFingerprint(profile: CameraDeviceProfile): String {
        val effectiveId = profile.physicalCameraId ?: profile.routeCameraId
        val focalBucket = profile.primaryFocalLengthMm
            ?.let { quantize(it, 100f) }
            ?.toString()
            ?: "na"
        return "${profile.facing.name}:$effectiveId:$focalBucket"
    }

    /**
     * Physical optical identity shared by Java Camera2 and NDK ACameraManager aliases.
     *
     * Deliberately excluded: route ID, aperture list, stream format list and max output resolution.
     * Vendors frequently expose those differently for the same sensor through Java, NDK, logical,
     * remosaic and ISP routes. Including them creates false extra lenses. Sensor/focal/active-array
     * geometry is substantially more stable across those routes.
     */
    private fun opticalFingerprint(profile: CameraDeviceProfile): String? {
        if (profile.focalLengthsMm.isEmpty()) return null

        val sensorWidth = profile.sensorWidthMm
        val sensorHeight = profile.sensorHeightMm
        val activeWidth = profile.activeArrayWidth
        val activeHeight = profile.activeArrayHeight

        if ((sensorWidth == null || sensorHeight == null) &&
            (activeWidth == null || activeHeight == null)
        ) return null

        val nativeFocals = profile.focalLengthsMm.sorted()
            .joinToString(",") { quantize(it, 100f).toString() }
        val equivalentFocals = profile.focalLengthsMm.sorted()
            .mapNotNull { focal ->
                sensorWidth?.takeIf { it > 0f }?.let { width -> focal * 36f / width }
            }
            .joinToString(",") { quantize(it, 10f).toString() }
        val cfa = profile.cfaArrangement ?: "cfa-na"

        return buildString {
            append(profile.facing.name)
            append('|')
            if (sensorWidth != null && sensorHeight != null) {
                append(quantize(sensorWidth, 100f))
                append('x')
                append(quantize(sensorHeight, 100f))
            } else {
                append("sensor-na")
            }
            append('|')
            if (activeWidth != null && activeHeight != null) {
                append(activeWidth)
                append('x')
                append(activeHeight)
            } else {
                append("active-na")
            }
            append('|')
            append(nativeFocals)
            append('|')
            append(equivalentFocals)
            append('|')
            append(cfa)
        }
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

    private fun quantize(value: Float, scale: Float): Int = (value * scale).roundToInt()
}
