from pathlib import Path

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, found {count}: {old[:120]!r}')
    write(path, text.replace(old, new, 1))


# 1) Route model: remember whether the same numeric ID should use the NDK transport.
replace_once(
    'core-model/src/main/kotlin/com/camera/core/model/CameraModels.kt',
    '    val rawSupported: Boolean,\n    val userVisible: Boolean = true,',
    '    val rawSupported: Boolean,\n    val nativeRoutePreferred: Boolean = false,\n    val userVisible: Boolean = true,',
)

replace_once(
    'camera-capability/src/main/kotlin/com/camera/camera/capability/ValuableCameraResolver.kt',
    '                            rawSupported = routeKey(profile) in rawRouteKeys,\n',
    '                            rawSupported = routeKey(profile) in rawRouteKeys,\n'
    '                            nativeRoutePreferred =\n'
    '                                "NDK_ENUMERATED" in profile.capabilities ||\n'
    '                                    "NDK_RAW_PREFERRED" in profile.capabilities,\n',
)

# 2) Hybrid catalog: fast advertised pass + optional deep metadata-only hidden-ID pass.
path = 'camera-camera2/src/main/kotlin/com/camera/camera/camera2/AndroidCameraCatalog.kt'
replace_once(
    path,
    '    override suspend fun scan(): CameraCatalogSnapshot = withContext(Dispatchers.Default) {\n',
    '    override suspend fun scan(): CameraCatalogSnapshot = scan(deepScan = false)\n\n'
    '    suspend fun scan(deepScan: Boolean): CameraCatalogSnapshot = withContext(Dispatchers.Default) {\n',
)
replace_once(
    path,
    '\n\n        val physicalMembers = buildList {\n            enumerated.forEach { parent ->',
    '\n\n        val ndkDescriptors = NativeCameraNdkBridge.enumerateRawCameras(deepScan = deepScan)\n'
    '        val ndkById = ndkDescriptors.associateBy { it.id }\n'
    '        val enrichedEnumerated = enumerated.map { profile ->\n'
    '            val native = ndkById[profile.routeCameraId]\n'
    '            when {\n'
    '                native == null -> profile\n'
    '                profile.streams.rawSizes.isEmpty() -> profile.copy(\n'
    '                    streams = profile.streams.copy(\n'
    '                        rawSizes = listOf(PixelSize(native.rawWidth, native.rawHeight)),\n'
    '                    ),\n'
    '                    capabilities = profile.capabilities + setOf("NDK_RAW_ALIAS", "NDK_RAW_PREFERRED"),\n'
    '                    supportsRaw = true,\n'
    '                    discoveryWarnings = profile.discoveryWarnings +\n'
    '                        "Java route has no RAW_SENSOR map; NDK RAW transport preferred",\n'
    '                )\n'
    '                else -> profile.copy(\n'
    '                    capabilities = profile.capabilities + "NDK_RAW_ALIAS",\n'
    '                )\n'
    '            }\n'
    '        }\n\n'
    '        val physicalMembers = buildList {\n'
    '            enrichedEnumerated.forEach { parent ->',
)
replace_once(
    path,
    '                        inheritedFacing = parent.facing,\n                    )\n\n                    // API 29+ physical cameras may omit a standalone stream configuration map while',
    '                        inheritedFacing = parent.facing,\n                    )\n'
    '                    val childHadNoPhotoStreams = child.maxPhotoPixels == 0L\n\n'
    '                    // API 29+ physical cameras may omit a standalone stream configuration map while',
)
replace_once(
    path,
    '                    if (child.maxPhotoPixels == 0L && parent.maxPhotoPixels > 0L) {\n'
    '                        child = child.copy(\n'
    '                            streams = parent.streams,\n'
    '                            supportsRaw = child.supportsRaw || parent.streams.rawSizes.isNotEmpty(),\n'
    '                            discoveryWarnings = child.discoveryWarnings +\n'
    '                                "Inherited logical-parent stream candidates",\n'
    '                        )\n'
    '                    }\n',
    '                    val parentHasFrameworkRaw =\n'
    '                        parent.streams.rawSizes.isNotEmpty() &&\n'
    '                            "NDK_RAW_PREFERRED" !in parent.capabilities\n'
    '                    if (child.streams.rawSizes.isEmpty() && parentHasFrameworkRaw) {\n'
    '                        child = child.copy(\n'
    '                            streams = child.streams.copy(rawSizes = parent.streams.rawSizes),\n'
    '                            supportsRaw = true,\n'
    '                            discoveryWarnings = child.discoveryWarnings +\n'
    '                                "Inherited logical-parent RAW_SENSOR candidates",\n'
    '                        )\n'
    '                    }\n'
    '                    if (childHadNoPhotoStreams && parent.maxPhotoPixels > 0L) {\n'
    '                        child = child.copy(\n'
    '                            streams = parent.streams,\n'
    '                            supportsRaw = child.supportsRaw || parentHasFrameworkRaw,\n'
    '                            discoveryWarnings = child.discoveryWarnings +\n'
    '                                "Inherited logical-parent stream candidates",\n'
    '                        )\n'
    '                    }\n',
)
replace_once(path, '        val javaRouteKeys = (enumerated + physicalMembers)\n', '        val javaRouteKeys = (enrichedEnumerated + physicalMembers)\n')
replace_once(
    path,
    '        val ndkProfiles = NativeCameraNdkBridge.enumerateRawCameras()\n            .map(::buildNdkProfile)\n',
    '        val ndkProfiles = ndkDescriptors\n            .map(::buildNdkProfile)\n',
)
replace_once(path, '        val profiles = (enumerated + physicalMembers + ndkProfiles)\n', '        val profiles = (enrichedEnumerated + physicalMembers + ndkProfiles)\n')
replace_once(
    path,
    '                add("NDK_ENUMERATED")\n',
    '                add("NDK_ENUMERATED")\n                add("NDK_RAW_PREFERRED")\n',
)
replace_once(
    path,
    '        // auxiliary IDs on a number of Qualcomm vendor frameworks. Keep only IDs that report RAW\n        // plus an actual RAW10/RAW16 output configuration.\n',
    '        // auxiliary IDs on a number of Qualcomm vendor frameworks. Keep only IDs with a genuine\n        // RAW10/RAW12/RAW16 stream. Deep scan is metadata-only and never opens camera devices.\n',
)

# 3) Native descriptor cache supports a non-blocking deep scan performed after first render.
path = 'camera-camera2/src/main/kotlin/com/camera/camera/camera2/NativeCameraNdkBridge.kt'
replace_once(
    path,
    '    @Volatile\n    private var descriptorCache: List<Descriptor>? = null\n\n    val available: Boolean get() = loaded\n\n    fun enumerateRawCameras(refresh: Boolean = false): List<Descriptor> {\n        if (!loaded) return emptyList()\n        if (!refresh) descriptorCache?.let { return it }\n        val raw = runCatching { nativeEnumerateJson() }.getOrNull().orEmpty()\n',
    '    @Volatile\n    private var descriptorCache: List<Descriptor>? = null\n\n'
    '    @Volatile\n    private var deepScanComplete = false\n\n'
    '    val available: Boolean get() = loaded\n\n'
    '    fun enumerateRawCameras(\n'
    '        refresh: Boolean = false,\n'
    '        deepScan: Boolean = false,\n'
    '    ): List<Descriptor> {\n'
    '        if (!loaded) return emptyList()\n'
    '        if (!refresh && (!deepScan || deepScanComplete)) descriptorCache?.let { return it }\n'
    '        val raw = runCatching { nativeEnumerateJson(deepScan) }.getOrNull().orEmpty()\n',
)
replace_once(
    path,
    '        descriptorCache = parsed\n        return parsed\n',
    '        val merged = (descriptorCache.orEmpty() + parsed)\n'
    '            .associateBy { it.id }\n'
    '            .values\n'
    '            .toList()\n'
    '        descriptorCache = merged\n'
    '        if (deepScan) deepScanComplete = true\n'
    '        return merged\n',
)
replace_once(path, '    private external fun nativeEnumerateJson(): String\n', '    private external fun nativeEnumerateJson(deepScan: Boolean): String\n')

# 4) Startup validation cache: no eager camera opens. Actual preview/capture promotes trust lazily.
write(
    'camera-camera2/src/main/kotlin/com/camera/camera/camera2/CameraRouteValidationCache.kt',
    '''package com.camera.camera.camera2

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
''',
)

write(
    'camera-camera2/src/main/kotlin/com/camera/camera/camera2/ValidatedCameraCatalog.kt',
    '''package com.camera.camera.camera2

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
 * Startup performs metadata discovery only. It never opens every candidate camera. Previously
 * rejected aliases are removed immediately, while verified aliases retain their trust annotation.
 * The selected lens is validated naturally by the real preview session; successful preview/DNG
 * operations promote the cache from metadata -> SESSION -> RAW.
 */
suspend fun AndroidCameraCatalog.scanValidated(
    context: Context,
    deepScan: Boolean = false,
): CameraCatalogSnapshot {
    val metadata = scan(deepScan = deepScan)
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
''',
)

# 5) Deep NDK discovery: advertised IDs first, then bounded metadata-only numeric probing.
path = 'camera-camera2/src/main/cpp/motioncam_camera_ndk.cpp'
replace_once(path, '#include <cstdint>\n', '#include <cstdint>\n#include <cstdlib>\n#include <set>\n')
replace_once(
    path,
    '    for (const int format : {AIMAGE_FORMAT_RAW16, AIMAGE_FORMAT_RAW12, AIMAGE_FORMAT_RAW10}) {\n',
    '    for (const int format : {AIMAGE_FORMAT_RAW10, AIMAGE_FORMAT_RAW16, AIMAGE_FORMAT_RAW12}) {\n',
)
text = read(path)
start = text.index('std::string enumerateJson() {')
end = text.index('\n} // namespace', start)
new_enum = r'''std::string enumerateJson(bool deepScan) {
    ACameraManager* managerRaw = ACameraManager_create();
    if (managerRaw == nullptr) return "[]";
    std::unique_ptr<ACameraManager, decltype(&ACameraManager_delete)> manager(managerRaw, ACameraManager_delete);

    ACameraIdList* idListRaw = nullptr;
    if (ACameraManager_getCameraIdList(manager.get(), &idListRaw) != ACAMERA_OK || idListRaw == nullptr) {
        return "[]";
    }
    std::unique_ptr<ACameraIdList, void(*)(ACameraIdList*)> idList(idListRaw, ACameraManager_deleteCameraIdList);

    std::vector<std::string> candidates;
    std::set<std::string> seen;
    int maxNumeric = -1;
    for (int i = 0; i < idList->numCameras; ++i) {
        const char* id = idList->cameraIds[i];
        if (id == nullptr || *id == '\0') continue;
        const std::string value(id);
        if (seen.insert(value).second) candidates.push_back(value);
        char* endPtr = nullptr;
        const long numeric = std::strtol(value.c_str(), &endPtr, 10);
        if (endPtr != value.c_str() && endPtr != nullptr && *endPtr == '\0' && numeric >= 0 && numeric <= 128) {
            maxNumeric = std::max(maxNumeric, static_cast<int>(numeric));
        }
    }

    if (deepScan) {
        // This pass is deliberately metadata-only: no camera is opened. Scan a bounded numeric
        // namespace so Qualcomm/vendor IDs filtered from both Java and the advertised NDK list can
        // still be found without device-specific hard-coded IDs. It runs after the first UI render.
        const int upper = std::min(31, std::max(11, maxNumeric + 8));
        for (int id = 0; id <= upper; ++id) {
            const std::string value = std::to_string(id);
            if (seen.insert(value).second) candidates.push_back(value);
        }
    }

    std::ostringstream out;
    out << '[';
    bool first = true;
    int rawCount = 0;
    for (const auto& id : candidates) {
        const std::string description = describeCamera(manager.get(), id.c_str());
        if (description.empty()) continue;
        if (!first) out << ',';
        first = false;
        ++rawCount;
        out << description;
    }
    out << ']';
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "NDK discovery advertised=%d candidates=%zu deep=%d retainedRAW=%d",
        idList->numCameras,
        candidates.size(),
        deepScan ? 1 : 0,
        rawCount);
    return out.str();
}'''
write(path, text[:start] + new_enum + text[end:])
replace_once(
    path,
    'Java_com_camera_camera_camera2_NativeCameraNdkBridge_nativeEnumerateJson(JNIEnv* env, jobject) {\n    const std::string json = enumerateJson();\n',
    'Java_com_camera_camera_camera2_NativeCameraNdkBridge_nativeEnumerateJson(JNIEnv* env, jobject, jboolean deepScan) {\n'
    '    const std::string json = enumerateJson(deepScan == JNI_TRUE);\n',
)

# Keep discovery/session format preference consistent and compatibility-first for vendor AUX RAW.
replace_once(
    'camera-camera2/src/main/cpp/native_camera_session.cpp',
    '    for (const int wanted : {AIMAGE_FORMAT_RAW16, AIMAGE_FORMAT_RAW12, AIMAGE_FORMAT_RAW10}) {\n',
    '    for (const int wanted : {AIMAGE_FORMAT_RAW10, AIMAGE_FORMAT_RAW16, AIMAGE_FORMAT_RAW12}) {\n',
)

# 6) Real preview/capture becomes the validation authority and updates trust cache.
path = 'feature-camera/src/main/kotlin/com/camera/feature/camera/Camera2PreviewController.kt'
replace_once(
    path,
    'import com.camera.camera.camera2.NativeCameraNdkBridge\n',
    'import com.camera.camera.camera2.CameraRouteValidationCache\nimport com.camera.camera.camera2.NativeCameraNdkBridge\n',
)
replace_once(
    path,
    '            onSaved = { uri ->\n                handler.post {\n',
    '            onSaved = { uri ->\n'
    '                CameraRouteValidationCache.markRawVerified(appContext, lens)\n'
    '                handler.post {\n',
)
replace_once(
    path,
    '            onError = { message ->\n                handler.post {\n',
    '            onError = { message ->\n'
    '                markDefinitiveRawFailure(lens, message)\n'
    '                handler.post {\n',
)
replace_once(
    path,
    '                    val uri = FusedDngWriter.saveNative(\n                        context = appContext,\n                        fused = fused,\n                        description = description,\n                    )\n                    handler.post {\n',
    '                    val uri = FusedDngWriter.saveNative(\n'
    '                        context = appContext,\n'
    '                        fused = fused,\n'
    '                        description = description,\n'
    '                    )\n'
    '                    CameraRouteValidationCache.markRawVerified(appContext, lens)\n'
    '                    handler.post {\n',
)
replace_once(
    path,
    '            } catch (error: Throwable) {\n                handler.post {\n                    if (released) return@post\n                    captureInFlight = false\n                    emitCapture(\n                        PhotoCaptureState.Error(\n                            error.message ?: "Native auxiliary RAW capture failed",\n                        ),\n                    )\n                }\n',
    '            } catch (error: Throwable) {\n'
    '                val message = error.message ?: "Native auxiliary RAW capture failed"\n'
    '                markDefinitiveRawFailure(lens, message)\n'
    '                handler.post {\n'
    '                    if (released) return@post\n'
    '                    captureInFlight = false\n'
    '                    emitCapture(PhotoCaptureState.Error(message))\n'
    '                }\n',
)
replace_once(
    path,
    '        nativeSessionActive = true\n        currentZoomRatio = 1f\n',
    '        nativeSessionActive = true\n'
    '        CameraRouteValidationCache.markSessionValidated(appContext, lens)\n'
    '        currentZoomRatio = 1f\n',
)
replace_once(
    path,
    '    private fun nativeDescriptorFor(lens: ValuableLens): NativeCameraNdkBridge.Descriptor? {\n'
    '        if (lens.physicalCameraId != null || lens.cameraId in javaCameraIds) return null\n'
    '        return NativeCameraNdkBridge.descriptor(lens.cameraId)\n'
    '    }\n',
    '    private fun nativeDescriptorFor(lens: ValuableLens): NativeCameraNdkBridge.Descriptor? {\n'
    '        if (lens.physicalCameraId != null) return null\n'
    '        val descriptor = NativeCameraNdkBridge.descriptor(lens.cameraId) ?: return null\n'
    '        return descriptor.takeIf { lens.nativeRoutePreferred || lens.cameraId !in javaCameraIds }\n'
    '    }\n',
)
replace_once(
    path,
    '                        override fun onConfigureFailed(session: CameraCaptureSession) {\n                            session.close()\n                            if (captureSession === session) captureSession = null\n                            emit(\n',
    '                        override fun onConfigureFailed(session: CameraCaptureSession) {\n'
    '                            session.close()\n'
    '                            if (captureSession === session) captureSession = null\n'
    '                            CameraRouteValidationCache.markRejected(appContext, lens)\n'
    '                            emit(\n',
)
replace_once(
    path,
    '            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)\n            emit(\n',
    '            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)\n'
    '            CameraRouteValidationCache.markSessionValidated(appContext, lens)\n'
    '            emit(\n',
)
replace_once(
    path,
    '    private fun closeCamera() {\n',
    '    private fun markDefinitiveRawFailure(lens: ValuableLens, message: String) {\n'
    '        val value = message.lowercase()\n'
    '        val definitive = listOf(\n'
    '            "no genuine raw",\n'
    '            "no raw_sensor",\n'
    '            "rejected raw",\n'
    '            "raw_sensor routing",\n'
    '            "unsupported bayer",\n'
    '            "raw image reader",\n'
    '            "raw output",\n'
    '        ).any(value::contains)\n'
    '        if (definitive) CameraRouteValidationCache.markRejected(appContext, lens)\n'
    '    }\n\n'
    '    private fun closeCamera() {\n',
)
replace_once(
    path,
    '        if (error != null) {\n            nativeSessionActive = false\n',
    '        if (error != null) {\n'
    '            markDefinitiveRawFailure(lens, error)\n'
    '            nativeSessionActive = false\n',
)

# 7) UI: render fast scan immediately, then enrich hidden AUX metadata in background.
path = 'feature-camera/src/main/kotlin/com/camera/feature/camera/CameraBootstrapScreen.kt'
replace_once(
    path,
    '        // Do not expose metadata-only camera IDs. The validated scan probes the preferred route for\n'
    '        // each physical lens and falls back across Java/physical/NDK aliases until a real preview +\n'
    '        // genuine RAW path is verified.\n'
    '        runCatching { catalog.scanValidated(context) }\n'
    '            .onSuccess { snapshot = it }\n'
    '            .onFailure { discoveryError = it.message ?: it.javaClass.simpleName }\n',
    '        // Fast path is metadata-only: never serially open/configure every camera before the UI.\n'
    '        // Real preview/capture validates a route lazily and persists that result. After first render,\n'
    '        // perform a bounded metadata-only NDK deep scan to discover vendor-hidden numeric AUX IDs.\n'
    '        val fast = runCatching { catalog.scanValidated(context, deepScan = false) }\n'
    '            .onFailure { discoveryError = it.message ?: it.javaClass.simpleName }\n'
    '            .getOrNull()\n'
    '        if (fast != null) {\n'
    '            snapshot = fast\n'
    '            kotlinx.coroutines.yield()\n'
    '            runCatching { catalog.scanValidated(context, deepScan = true) }\n'
    '                .onSuccess { deep -> snapshot = deep }\n'
    '        }\n',
)

print('Fast AUX discovery patch applied successfully')
