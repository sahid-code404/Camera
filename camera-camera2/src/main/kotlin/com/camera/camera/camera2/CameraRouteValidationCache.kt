package com.camera.camera.camera2

import android.content.Context
import android.os.Build
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.ValuableLens
import java.security.MessageDigest

/**
 * Tiny persistent trust cache for lazy camera validation.
 *
 * Discovery must never open every camera before the UI appears. Metadata produces candidates
 * immediately; the real preview session validates SESSION and the first successful DNG validates
 * RAW. Definitive route failures are remembered so a broken vendor alias is not retried on every
 * launch. Android build fingerprint + schema invalidate the cache after ROM/HAL or policy changes.
 */
object CameraRouteValidationCache {
    enum class TrustLevel { SESSION, RAW }

    data class Snapshot(
        val fingerprint: String,
        val sessionValidatedRouteKeys: Set<String>,
        val rawVerifiedRouteKeys: Set<String>,
        val rejectedRouteKeys: Set<String>,
    ) {
        val allUsableRouteKeys: Set<String>
            get() = sessionValidatedRouteKeys + rawVerifiedRouteKeys

        fun level(routeKey: String): TrustLevel? = when {
            routeKey in rawVerifiedRouteKeys -> TrustLevel.RAW
            routeKey in sessionValidatedRouteKeys -> TrustLevel.SESSION
            else -> null
        }
    }

    fun prepare(context: Context, profiles: List<CameraDeviceProfile>): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentFingerprint = fingerprint(profiles)
        val sameGeneration = prefs.getInt(KEY_SCHEMA, -1) == CACHE_SCHEMA &&
            prefs.getString(KEY_FINGERPRINT, null) == currentFingerprint
        if (!sameGeneration) {
            prefs.edit()
                .clear()
                .putInt(KEY_SCHEMA, CACHE_SCHEMA)
                .putString(KEY_FINGERPRINT, currentFingerprint)
                .apply()
        }

        val currentKeys = profiles.mapTo(mutableSetOf(), ::routeKey)
        val session = prefs.getStringSet(KEY_SESSION_ROUTES, emptySet())
            .orEmpty().filterTo(mutableSetOf()) { it in currentKeys }
        val raw = prefs.getStringSet(KEY_RAW_ROUTES, emptySet())
            .orEmpty().filterTo(mutableSetOf()) { it in currentKeys }
        val rejected = prefs.getStringSet(KEY_REJECTED_ROUTES, emptySet())
            .orEmpty().filterTo(mutableSetOf()) { it in currentKeys }
        return Snapshot(currentFingerprint, session, raw, rejected)
    }

    fun markSessionValidated(context: Context, lens: ValuableLens) {
        mutate(context, lens) { session, raw, rejected, key ->
            session += key
            rejected -= key
            Triple(session, raw, rejected)
        }
    }

    fun markRawVerified(context: Context, lens: ValuableLens) {
        mutate(context, lens) { session, raw, rejected, key ->
            session += key
            raw += key
            rejected -= key
            Triple(session, raw, rejected)
        }
    }

    fun markRejected(context: Context, lens: ValuableLens) {
        mutate(context, lens) { session, raw, rejected, key ->
            session -= key
            raw -= key
            rejected += key
            Triple(session, raw, rejected)
        }
    }

    fun invalidate(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun fingerprint(@Suppress("UNUSED_PARAMETER") profiles: List<CameraDeviceProfile>): String {
        val canonical = buildString {
            append("schema=").append(CACHE_SCHEMA)
            append("|build=").append(Build.FINGERPRINT)
            append("|sdk=").append(Build.VERSION.SDK_INT)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private inline fun mutate(
        context: Context,
        lens: ValuableLens,
        block: (
            MutableSet<String>,
            MutableSet<String>,
            MutableSet<String>,
            String,
        ) -> Triple<MutableSet<String>, MutableSet<String>, MutableSet<String>>,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA, -1) != CACHE_SCHEMA) return
        if (prefs.getString(KEY_FINGERPRINT, null).isNullOrBlank()) return
        val key = routeKey(lens)
        val session = prefs.getStringSet(KEY_SESSION_ROUTES, emptySet()).orEmpty().toMutableSet()
        val raw = prefs.getStringSet(KEY_RAW_ROUTES, emptySet()).orEmpty().toMutableSet()
        val rejected = prefs.getStringSet(KEY_REJECTED_ROUTES, emptySet()).orEmpty().toMutableSet()
        val (nextSession, nextRaw, nextRejected) = block(session, raw, rejected, key)
        prefs.edit()
            .putStringSet(KEY_SESSION_ROUTES, nextSession)
            .putStringSet(KEY_RAW_ROUTES, nextRaw)
            .putStringSet(KEY_REJECTED_ROUTES, nextRejected)
            .apply()
    }

    private fun routeKey(profile: CameraDeviceProfile): String =
        "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

    private fun routeKey(lens: ValuableLens): String =
        "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}"

    private const val PREFS = "camera_route_validation_v4"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_SESSION_ROUTES = "session_routes"
    private const val KEY_RAW_ROUTES = "raw_routes"
    private const val KEY_REJECTED_ROUTES = "rejected_routes"
    private const val CACHE_SCHEMA = 4
}
