package com.camera.processing.raw

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import java.io.File

/** File-backed RAW16 payload copied out of ImageReader as quickly as possible. */
data class StagedRawPayload(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val file: File,
)

data class RawFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val file: File,
    val result: TotalCaptureResult,
    val exposureTimeNs: Long,
    val iso: Int,
    val blackLevels: IntArray,
    val whiteLevel: Int,
)

data class RawAlignment(
    val dx: Int,
    val dy: Int,
    val confidence: Float,
)

data class FusedRaw(
    val width: Int,
    val height: Int,
    val file: File,
    val referenceResult: TotalCaptureResult,
    val referenceCharacteristics: CameraCharacteristics,
    val frameCount: Int,
    val alignments: List<RawAlignment>,
    val acceptedSamples: Long,
    val rejectedSamples: Long,
)

fun StagedRawPayload.pairWith(
    result: TotalCaptureResult,
    characteristics: CameraCharacteristics,
): RawFrame {
    val dynamic = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
    val staticPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
    val black = when {
        dynamic != null && dynamic.size >= 4 -> IntArray(4) { index ->
            dynamic[index].toInt().coerceAtLeast(0)
        }
        staticPattern != null -> intArrayOf(
            staticPattern.getOffsetForIndex(0, 0),
            staticPattern.getOffsetForIndex(1, 0),
            staticPattern.getOffsetForIndex(0, 1),
            staticPattern.getOffsetForIndex(1, 1),
        )
        else -> intArrayOf(0, 0, 0, 0)
    }
    return RawFrame(
        timestampNs = timestampNs,
        width = width,
        height = height,
        file = file,
        result = result,
        exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 1L,
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
        blackLevels = black,
        whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 65535,
    )
}
