package com.camera.camera.camera2

import android.content.Context
import android.os.Build
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.ValuableLens
import java.security.MessageDigest

/**
 * Persistent validation cache keyed by a deterministic camera-HAL fingerprint.
 *
 * Opening camera devices and negotiating sessions is orders of magnitude slower than reading
 * CameraCharacteristics. Once a route has passed runtime validation, repeating that work on every
 * launch adds latency without adding safety while the ROM/HAL metadata is unchanged.
 *
 * The cache is invalidated automatically when the Android build fingerprint or any route identity,
 * physical relationship, optical geometry, CFA, RAW/preview stream declaration or relevant
 * capability changes. CACHE_SCHEMA is deliberately part of the fingerprint contract so validation
 * policy changes can force a one-time re-probe after an app update.
 */
object CameraRouteValidationCache {
    enum class TrustLevel { SESSION, RAW }

    data class Snapshot(
        val fingerprint: String,
        val sessionValidatedRouteKeys: Set<String>,
        val rawVerifiedRouteKeys: Set<String>,
    ) {
        val allUsableRouteKeys: Set<String>
            get() = sessionValidatedRouteKeys + rawVerifiedRouteKeys

        fun level(routeKey: String): TrustLevel? = when {
            routeKey in rawVerifiedRouteKeys -> TrustLevel.RAW
            routeKey in sessionValidatedRouteKeys -> TrustLevel.SESSION
            else -> null
        }
    }

    fun load(context: Context, profiles: List<CameraDeviceProfile>): Snapshot? {
        if (profiles.isEmpty()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA, -1) != CACHE_SCHEMA) return null
        val currentFingerprint = fingerprint(profiles)
        if (prefs.getString(KEY_FINGERPRINT, null) != currentFingerprint) return null

        val currentKeys = profiles.mapTo(mutableSetOf(), ::routeKey)
        val session = prefs.getStringSet(KEY_SESSION_ROUTES, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { it in currentKeys }
        val raw = prefs.getStringSet(KEY_RAW_ROUTES, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { it in currentKeys }
        if (session.isEmpty() && raw.isEmpty()) return null

        return Snapshot(
            fingerprint = currentFingerprint,
            sessionValidatedRouteKeys = session,
            rawVerifiedRouteKeys = raw,
        )
    }

    fun saveSessionValidated(
        context: Context,
        profiles: List<CameraDeviceProfile>,
        routeKeys: Set<String>,
    ) {
        if (profiles.isEmpty() || routeKeys.isEmpty()) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentFingerprint = fingerprint(profiles)
        val previousRaw = if (
            prefs.getInt(KEY_SCHEMA, -1) == CACHE_SCHEMA &&
            prefs.getString(KEY_FINGERPRINT, null) == currentFingerprint
        ) {
            prefs.getStringSet(KEY_RAW_ROUTES, emptySet()).orEmpty().intersect(routeKeys)
        } else {
            emptySet()
        }
        prefs.edit()
            .putInt(KEY_SCHEMA, CACHE_SCHEMA)
            .putString(KEY_FINGERPRINT, currentFingerprint)
            .putStringSet(KEY_SESSION_ROUTES, routeKeys.toSet())
            .putStringSet(KEY_RAW_ROUTES, previousRaw.toSet())
            .apply()
    }

    /** Promote an already session-validated route after a real successful DNG capture. */
    fun markRawVerified(context: Context, lens: ValuableLens) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA, -1) != CACHE_SCHEMA) return
        if (prefs.getString(KEY_FINGERPRINT, null).isNullOrBlank()) return
        val key = routeKey(lens)
        val sessions = prefs.getStringSet(KEY_SESSION_ROUTES, emptySet()).orEmpty()
        if (key !in sessions) return
        val raw = prefs.getStringSet(KEY_RAW_ROUTES, emptySet()).orEmpty().toMutableSet()
        if (raw.add(key)) prefs.edit().putStringSet(KEY_RAW_ROUTES, raw).apply()
    }

    /** Force a fresh route decision after a future validation-policy or runtime failure requires it. */
    fun invalidate(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun fingerprint(profiles: List<CameraDeviceProfile>): String {
        val canonical = buildString {
            append("schema=").append(CACHE_SCHEMA)
            append("|build=").append(Build.FINGERPRINT)
            append("|sdk=").append(Build.VERSION.SDK_INT)
            profiles.sortedBy(::routeKey).forEach { profile ->
                append("\nroute=").append(routeKey(profile))
                append("|parent=").append(profile.parentLogicalCameraId ?: "-")
                append("|kind=").append(profile.routeKind.name)
                append("|facing=").append(profile.facing.name)
                append("|hw=").append(profile.hardwareLevel)
                append("|physical=").append(profile.logicalPhysicalIds.sorted().joinToString(","))
                append("|caps=").append(profile.capabilities.sorted().joinToString(","))
                append("|focal=").append(profile.focalLengthsMm.sorted().joinToString(",") { q(it) })
                append("|sensor=").append(profile.sensorWidthMm?.let(::q) ?: "-")
                    .append('x').append(profile.sensorHeightMm?.let(::q) ?: "-")
                append("|active=").append(profile.activeArrayWidth ?: -1)
                    .append('x').append(profile.activeArrayHeight ?: -1)
                append("|cfa=").append(profile.cfaArrangement ?: "-")
                append("|raw=").append(sizeList(profile.streams.rawSizes))
                append("|private=").append(sizeList(profile.streams.privateSizes))
                append("|yuv=").append(sizeList(profile.streams.yuvSizes))
                append("|supportsRaw=").append(profile.supportsRaw)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun sizeList(sizes: List<com.camera.core.model.PixelSize>): String =
        sizes.sortedWith(compareBy({ it.width }, { it.height }))
            .joinToString(",") { "${it.width}x${it.height}" }

    private fun q(value: Float): String = ((value * 10_000f).toInt()).toString()

    private fun routeKey(profile: CameraDeviceProfile): String =
        "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

    private fun routeKey(lens: ValuableLens): String =
        "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}"

    private const val PREFS = "camera_route_validation_v3"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_SESSION_ROUTES = "session_routes"
    private const val KEY_RAW_ROUTES = "raw_routes"
    private const val CACHE_SCHEMA = 3
}
