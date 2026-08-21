package com.camera.camera.camera2

import android.view.Surface
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * MotionCam-style camera backend using the public Android NDK camera APIs.
 *
 * Discovery deliberately uses ACameraManager rather than Java CameraManager so auxiliary IDs that
 * are native-service-visible can participate without hard-coded ID lists or package-name spoofing.
 * NDK-only lenses use the same native API for preview and genuine RAW10/RAW12/RAW16 acquisition;
 * processed JPEG/YUV data is never relabeled as RAW.
 */
object NativeCameraNdkBridge {
    data class Descriptor(
        val id: String,
        val facing: Int,
        val hardware: String,
        val rawCapabilityAdvertised: Boolean,
        val rawFormat: Int,
        val rawWidth: Int,
        val rawHeight: Int,
        val previewWidth: Int,
        val previewHeight: Int,
        val sensorOrientation: Int?,
        val focalLengthMm: Float?,
        val sensorWidthMm: Float?,
        val sensorHeightMm: Float?,
        val activeWidth: Int?,
        val activeHeight: Int?,
        val cfaArrangement: Int?,
        val whiteLevel: Int?,
        val blackLevels: List<Int>,
        val isoMin: Int?,
        val isoMax: Int?,
        val exposureMinNs: Long?,
        val exposureMaxNs: Long?,
        val manualSensor: Boolean,
        val burstCapture: Boolean,
    )

    data class CapturedRawFrame(
        val file: File,
        val timestampNs: Long,
        val width: Int,
        val height: Int,
        val exposureTimeNs: Long,
        val iso: Int,
        val whiteLevel: Int,
        val blackLevels: IntArray,
        val awbGains: FloatArray,
        val colorTransform: FloatArray,
    )

    data class RawBurst(
        val cfaArrangement: Int,
        val rawFormat: Int,
        val frames: List<CapturedRawFrame>,
        /** Native session policy actually used for this burst: concurrent or switch. */
        val captureStrategy: String,
        /** False means the RAW succeeded but the native preview session needs to be reopened. */
        val previewRestored: Boolean,
    )

    private val loaded = runCatching {
        System.loadLibrary("camera_ndk_bridge")
        true
    }.getOrDefault(false)

    @Volatile
    private var descriptorCache: List<Descriptor>? = null

    @Volatile
    private var deepScanComplete = false

    val available: Boolean get() = loaded

    fun enumerateRawCameras(
        refresh: Boolean = false,
        deepScan: Boolean = false,
    ): List<Descriptor> {
        if (!loaded) return emptyList()
        if (!refresh && (!deepScan || deepScanComplete)) descriptorCache?.let { return it }
        val raw = runCatching { nativeEnumerateJson(deepScan) }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val black = item.optJSONArray("black")
                    val rawWidth = item.optInt("rawWidth", 0)
                    val rawHeight = item.optInt("rawHeight", 0)
                    val previewWidth = item.optInt("previewWidth", 0)
                    val previewHeight = item.optInt("previewHeight", 0)
                    if (rawWidth <= 0 || rawHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) continue
                    add(
                        Descriptor(
                            id = id,
                            facing = item.optInt("facing", -1),
                            hardware = item.optString("hardware", "UNKNOWN"),
                            rawCapabilityAdvertised = item.optBoolean("rawCapabilityAdvertised", false),
                            rawFormat = item.optInt("rawFormat", 0),
                            rawWidth = rawWidth,
                            rawHeight = rawHeight,
                            previewWidth = previewWidth,
                            previewHeight = previewHeight,
                            sensorOrientation = item.optInt("sensorOrientation", -1).takeIf { it in 0..359 },
                            focalLengthMm = item.optPositiveFloat("focal"),
                            sensorWidthMm = item.optPositiveFloat("sensorWidth"),
                            sensorHeightMm = item.optPositiveFloat("sensorHeight"),
                            activeWidth = item.optInt("activeWidth", 0).takeIf { it > 0 },
                            activeHeight = item.optInt("activeHeight", 0).takeIf { it > 0 },
                            cfaArrangement = item.optInt("cfa", -1).takeIf { it in 0..3 },
                            whiteLevel = item.optInt("white", 0).takeIf { it > 0 },
                            blackLevels = buildList {
                                if (black != null) {
                                    for (i in 0 until black.length()) add(black.optInt(i, 0))
                                }
                            },
                            isoMin = item.optInt("isoMin", 0).takeIf { it > 0 },
                            isoMax = item.optInt("isoMax", 0).takeIf { it > 0 },
                            exposureMinNs = item.optLong("exposureMin", 0L).takeIf { it > 0L },
                            exposureMaxNs = item.optLong("exposureMax", 0L).takeIf { it > 0L },
                            manualSensor = item.optBoolean("manual", false),
                            burstCapture = item.optBoolean("burst", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        val merged = (descriptorCache.orEmpty() + parsed)
            .associateBy { it.id }
            .values
            .toList()
        descriptorCache = merged
        if (deepScan) deepScanComplete = true
        return merged
    }

    fun descriptor(cameraId: String): Descriptor? =
        enumerateRawCameras().firstOrNull { it.id == cameraId }

    /** Returns null on success, otherwise a human-readable native camera error. */
    fun startSession(cameraId: String, previewSurface: Surface): String? {
        if (!loaded) return "Android NDK camera backend is unavailable"
        val error = runCatching { nativeStartSession(cameraId, previewSurface) }
            .getOrElse { it.message ?: it.javaClass.simpleName }
            .trim()
        return error.takeIf { it.isNotEmpty() }
    }

    fun stopSession() {
        if (loaded) runCatching { nativeStopSession() }
    }

    /**
     * Synchronously acquires a genuine RAW burst. Call from a worker thread.
     *
     * Pass frameCount=0 for native light-adaptive sampling (4..8 frames). Packed RAW10/RAW12 is
     * unpacked natively into tightly packed 16-bit Bayer samples before the computational engine.
     */
    fun captureBurst(
        scratchDirectory: File,
        frameCount: Int = 0,
        hdrStrength: Float = 0.72f,
    ): RawBurst {
        check(loaded) { "Android NDK camera backend is unavailable" }
        scratchDirectory.mkdirs()
        // The current camera controller historically requested exactly four NDK frames. Treat that
        // legacy value as the new adaptive policy so existing call sites immediately gain 4..8
        // light-aware sampling; the runtime route probe deliberately requests one frame and remains
        // fixed/cheap. Explicit non-legacy counts (2,3,5..8) are still honored exactly.
        val requestedFrames = when {
            frameCount <= 0 || frameCount == LEGACY_FIXED_BURST_COUNT -> 0
            else -> frameCount.coerceIn(1, 8)
        }
        val raw = nativeCaptureBurst(
            scratchDirectory.absolutePath,
            requestedFrames,
            hdrStrength.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 0.72f,
        )
        val root = JSONObject(raw)
        check(root.optBoolean("ok", false)) {
            root.optString("error", "NDK RAW capture failed")
        }
        val cfa = root.optInt("cfa", -1)
        check(cfa in 0..3) { "NDK RAW capture returned unsupported CFA $cfa" }
        val framesJson = root.optJSONArray("frames") ?: JSONArray()
        val frames = buildList {
            for (index in 0 until framesJson.length()) {
                val item = framesJson.optJSONObject(index) ?: continue
                val path = item.optString("path").takeIf { it.isNotBlank() } ?: continue
                val black = item.optJSONArray("blackLevels")
                val gains = item.optJSONArray("awbGains")
                val transform = item.optJSONArray("colorTransform")
                add(
                    CapturedRawFrame(
                        file = File(path),
                        timestampNs = item.optLong("timestampNs", 0L),
                        width = item.optInt("width", 0),
                        height = item.optInt("height", 0),
                        exposureTimeNs = item.optLong("exposureTimeNs", 1L).coerceAtLeast(1L),
                        iso = item.optInt("iso", 100).coerceAtLeast(1),
                        whiteLevel = item.optInt("whiteLevel", 65535).coerceAtLeast(1),
                        blackLevels = IntArray(4) { channel -> black?.optInt(channel, 0) ?: 0 },
                        awbGains = FloatArray(4) { channel ->
                            gains?.optDouble(channel, 1.0)?.toFloat()
                                ?.takeIf { it.isFinite() && it > 0f }
                                ?: 1f
                        },
                        colorTransform = FloatArray(9) { element ->
                            transform?.optDouble(element, if (element % 4 == 0) 1.0 else 0.0)
                                ?.toFloat()
                                ?.takeIf(Float::isFinite)
                                ?: if (element % 4 == 0) 1f else 0f
                        },
                    ),
                )
            }
        }
        check(frames.isNotEmpty()) { "NDK RAW capture returned no frames" }
        check(frames.all { it.file.isFile && it.width > 0 && it.height > 0 }) {
            "NDK RAW capture returned an invalid staged frame"
        }
        return RawBurst(
            cfaArrangement = cfa,
            rawFormat = root.optInt("rawFormat", 0),
            frames = frames,
            captureStrategy = root.optString("strategy", "unknown"),
            previewRestored = root.optBoolean("previewRestored", true),
        )
    }

    private fun JSONObject.optPositiveFloat(key: String): Float? =
        optDouble(key, 0.0).toFloat().takeIf { it.isFinite() && it > 0f }

    private external fun nativeEnumerateJson(deepScan: Boolean): String
    private external fun nativeStartSession(cameraId: String, previewSurface: Surface): String
    private external fun nativeStopSession()
    private external fun nativeCaptureBurst(cacheDir: String, frameCount: Int, hdrStrength: Float): String

    private const val LEGACY_FIXED_BURST_COUNT = 4
}
