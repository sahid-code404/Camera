package com.camera.camera.camera2

import org.json.JSONArray

/**
 * MotionCam-style camera discovery using the public Android NDK camera APIs.
 *
 * Some Qualcomm/Xiaomi framework builds filter auxiliary IDs inside the Java CameraManager layer.
 * ACameraManager talks through the NDK camera client path and can expose the same RAW-capable
 * devices MotionCam sees without hard-coding vendor camera numbers or pretending processed output
 * is RAW.
 */
object NativeCameraNdkBridge {
    data class Descriptor(
        val id: String,
        val facing: Int,
        val hardware: String,
        val rawFormat: Int,
        val rawWidth: Int,
        val rawHeight: Int,
        val previewWidth: Int,
        val previewHeight: Int,
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

    private val loaded = runCatching {
        System.loadLibrary("camera_ndk_bridge")
        true
    }.getOrDefault(false)

    val available: Boolean get() = loaded

    fun enumerateRawCameras(): List<Descriptor> {
        if (!loaded) return emptyList()
        val raw = runCatching { nativeEnumerateJson() }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val black = item.optJSONArray("black")
                    add(
                        Descriptor(
                            id = id,
                            facing = item.optInt("facing", -1),
                            hardware = item.optString("hardware", "UNKNOWN"),
                            rawFormat = item.optInt("rawFormat", 0),
                            rawWidth = item.optInt("rawWidth", 0),
                            rawHeight = item.optInt("rawHeight", 0),
                            previewWidth = item.optInt("previewWidth", 0),
                            previewHeight = item.optInt("previewHeight", 0),
                            focalLengthMm = item.optDouble("focal", 0.0)
                                .toFloat().takeIf { it > 0f && it.isFinite() },
                            sensorWidthMm = item.optDouble("sensorWidth", 0.0)
                                .toFloat().takeIf { it > 0f && it.isFinite() },
                            sensorHeightMm = item.optDouble("sensorHeight", 0.0)
                                .toFloat().takeIf { it > 0f && it.isFinite() },
                            activeWidth = item.optInt("activeWidth", 0).takeIf { it > 0 },
                            activeHeight = item.optInt("activeHeight", 0).takeIf { it > 0 },
                            cfaArrangement = item.optInt("cfa", -1).takeIf { it >= 0 },
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
    }

    private external fun nativeEnumerateJson(): String
}
