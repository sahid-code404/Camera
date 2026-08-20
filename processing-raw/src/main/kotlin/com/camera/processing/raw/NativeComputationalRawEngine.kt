package com.camera.processing.raw

import android.hardware.camera2.CameraCharacteristics
import com.camera.core.model.PhotoLensSettings
import java.io.File
import kotlin.math.roundToInt

/**
 * Thin JNI bridge. Kotlin owns orchestration only; full-frame pixel math lives in C++.
 *
 * Output is a computational Bayer master intended for one DNG file. This is deliberately not
 * described as untouched sensor RAW: HDR/tone/chroma/detail/upscale settings may alter samples.
 */
object NativeComputationalRawEngine {
    init {
        System.loadLibrary("camera_raw_native")
    }

    fun process(
        frames: List<RawFrame>,
        characteristics: CameraCharacteristics,
        settings: PhotoLensSettings,
        outputDirectory: File,
    ): FusedRaw {
        require(frames.isNotEmpty()) { "At least one RAW frame is required" }
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height }) {
            "RAW burst contains mismatched dimensions"
        }

        outputDirectory.mkdirs()
        val output = File(outputDirectory, "camera_native_${System.nanoTime()}.raw16")
        val blackLevels = IntArray(frames.size * 4)
        frames.forEachIndexed { frameIndex, frame ->
            for (channel in 0 until 4) {
                blackLevels[frameIndex * 4 + channel] = frame.blackLevels.getOrElse(channel) { 0 }
            }
        }

        val hdrEnabled = settings.hdrEnabled ?: true
        val hdrStrength = (settings.hdrStrength ?: 0.72f).coerceIn(0f, 2f)
        val highlight = (settings.highlightProtection ?: 0.55f).coerceIn(0f, 2f)
        val shadow = (settings.shadowRecovery ?: 0.18f).coerceIn(0f, 2f)
        val denoise = (settings.denoise ?: 0.40f).coerceIn(0f, 2f)
        val sharpness = (settings.sharpness ?: 0.28f).coerceIn(0f, 2f)
        val saturation = (settings.saturation ?: 1.0f).coerceIn(0f, 2.5f)
        val upscale = (settings.upscaleFactor ?: 1f)
            .takeIf { it.isFinite() }
            ?.roundToInt()
            ?.coerceIn(1, 4)
            ?: 1
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        val metrics = nativeProcess(
            inputPaths = frames.map { it.file.absolutePath }.toTypedArray(),
            exposureTimesNs = LongArray(frames.size) { frames[it].exposureTimeNs },
            sensitivities = IntArray(frames.size) { frames[it].iso },
            blackLevels = blackLevels,
            width = width,
            height = height,
            whiteLevel = frames.first().whiteLevel,
            cfaArrangement = cfa,
            hdrEnabled = hdrEnabled,
            hdrStrength = hdrStrength,
            highlightProtection = highlight,
            shadowRecovery = shadow,
            denoise = denoise,
            sharpness = sharpness,
            saturation = saturation,
            upscaleFactor = upscale,
            outputPath = output.absolutePath,
        )

        if (metrics.size < HEADER_SIZE || metrics[0] <= 0L || metrics[1] <= 0L) {
            output.delete()
            val code = metrics.getOrNull(4) ?: -999L
            error("Native RAW processing failed ($code)")
        }

        val referenceIndex = metrics[4].toInt().coerceIn(frames.indices)
        val alignments = buildList {
            var offset = HEADER_SIZE
            while (offset + 2 < metrics.size && size < frames.size) {
                add(
                    RawAlignment(
                        dx = metrics[offset].toInt(),
                        dy = metrics[offset + 1].toInt(),
                        confidence = (metrics[offset + 2].toFloat() / 10_000f).coerceIn(0f, 1f),
                    ),
                )
                offset += 3
            }
            while (size < frames.size) add(RawAlignment(0, 0, if (size == referenceIndex) 1f else 0f))
        }

        return FusedRaw(
            width = metrics[0].toInt(),
            height = metrics[1].toInt(),
            file = output,
            referenceResult = frames[referenceIndex].result,
            referenceCharacteristics = characteristics,
            frameCount = frames.size,
            alignments = alignments,
            acceptedSamples = metrics[2],
            rejectedSamples = metrics[3],
        )
    }

    private external fun nativeProcess(
        inputPaths: Array<String>,
        exposureTimesNs: LongArray,
        sensitivities: IntArray,
        blackLevels: IntArray,
        width: Int,
        height: Int,
        whiteLevel: Int,
        cfaArrangement: Int,
        hdrEnabled: Boolean,
        hdrStrength: Float,
        highlightProtection: Float,
        shadowRecovery: Float,
        denoise: Float,
        sharpness: Float,
        saturation: Float,
        upscaleFactor: Int,
        outputPath: String,
    ): LongArray

    private const val HEADER_SIZE = 5
}
