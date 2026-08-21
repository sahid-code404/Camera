package com.camera.processing.raw

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
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
    val result: CaptureResult,
    val exposureTimeNs: Long,
    val iso: Int,
    val blackLevels: IntArray,
    val whiteLevel: Int,
    /** Camera2 AWB gains in R, G-even, G-odd, B order. */
    val awbGains: FloatArray,
    /** Camera2 sensor -> linear-sRGB color correction transform, row-major 3x3. */
    val sensorToLinearSrgb: FloatArray,
)

/**
 * Same tightly packed Bayer payload as [RawFrame], but sourced from Android NDK AImageReader.
 * Keeping this Android-framework-free lets hidden auxiliary cameras use the same native
 * computational processor without fabricating a Java CaptureResult.
 */
data class NativeRawFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val file: File,
    val exposureTimeNs: Long,
    val iso: Int,
    val blackLevels: IntArray,
    val whiteLevel: Int,
    val awbGains: FloatArray,
    val sensorToLinearSrgb: FloatArray,
)

data class RawAlignment(
    val dx: Int,
    val dy: Int,
    val confidence: Float,
)

data class FusedRaw(
    val width: Int,
    val height: Int,
    /** Native engine output. This is now a complete computational Linear DNG. */
    val file: File,
    val referenceResult: CaptureResult,
    val referenceCharacteristics: CameraCharacteristics,
    val frameCount: Int,
    val alignments: List<RawAlignment>,
    val acceptedSamples: Long,
    val rejectedSamples: Long,
)

/** Native-camera equivalent of [FusedRaw]. The generated file is the same complete Linear DNG. */
data class NativeFusedRaw(
    val width: Int,
    val height: Int,
    val file: File,
    val frameCount: Int,
    val alignments: List<RawAlignment>,
    val acceptedSamples: Long,
    val rejectedSamples: Long,
)

fun StagedRawPayload.pairWith(
    result: CaptureResult,
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

    val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        ?: error("RAW capture did not report color-correction gains")
    val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        ?: error("RAW capture did not report a sensor color transform")

    val matrix = FloatArray(9)
    for (row in 0 until 3) {
        for (column in 0 until 3) {
            val rational = transform.getElement(column, row)
            require(rational.denominator != 0) { "Invalid RAW color transform denominator" }
            matrix[row * 3 + column] = rational.numerator.toFloat() / rational.denominator.toFloat()
        }
    }

    val dynamicWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
    val staticWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
    val resolvedWhite = (dynamicWhite ?: staticWhite ?: 65535).coerceAtLeast(1)

    return RawFrame(
        timestampNs = timestampNs,
        width = width,
        height = height,
        file = file,
        result = result,
        exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 1L,
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
        blackLevels = black,
        whiteLevel = resolvedWhite,
        awbGains = floatArrayOf(
            gains.red,
            gains.greenEven,
            gains.greenOdd,
            gains.blue,
        ),
        sensorToLinearSrgb = matrix,
    )
}
