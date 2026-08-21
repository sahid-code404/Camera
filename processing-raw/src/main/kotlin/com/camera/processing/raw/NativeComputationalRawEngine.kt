package com.camera.processing.raw

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import com.camera.core.model.PhotoLensSettings
import java.io.File
import kotlin.math.roundToInt

/**
 * Thin JNI bridge. Kotlin owns orchestration only; full-frame image math and DNG serialization
 * live in C++.
 *
 * Java Camera2 RAW and MotionCam-style NDK RAW both enter the exact same native processing call.
 * The transport backend therefore cannot silently alter the computational algorithm or output
 * format; it only supplies tightly packed Bayer samples and matching numeric sensor metadata.
 */
object NativeComputationalRawEngine {
    init {
        System.loadLibrary("camera_raw_native")
    }

    fun process(
        frames: List<RawFrame>,
        characteristics: CameraCharacteristics,
        settings: PhotoLensSettings,
        targetAspect: Float,
        outputDirectory: File,
    ): FusedRaw {
        require(frames.isNotEmpty()) { "At least one RAW frame is required" }
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: error("Selected RAW lens does not expose a Bayer CFA arrangement")
        val core = processInputs(
            frames = frames.map { frame ->
                InputFrame(
                    file = frame.file,
                    width = frame.width,
                    height = frame.height,
                    exposureTimeNs = frame.exposureTimeNs,
                    iso = frame.iso,
                    blackLevels = frame.blackLevels,
                    whiteLevel = frame.whiteLevel,
                    awbGains = frame.awbGains,
                    colorTransform = frame.sensorToLinearSrgb,
                )
            },
            cfaArrangement = cfa,
            settings = settings,
            targetAspect = targetAspect,
            outputDirectory = outputDirectory,
        )
        val referenceIndex = core.referenceIndex.coerceIn(frames.indices)
        return FusedRaw(
            width = core.width,
            height = core.height,
            file = core.file,
            referenceResult = frames[referenceIndex].result,
            referenceCharacteristics = characteristics,
            frameCount = frames.size,
            alignments = core.alignments,
            acceptedSamples = core.acceptedSamples,
            rejectedSamples = core.rejectedSamples,
        )
    }

    /** Process RAW frames captured directly through ACameraManager/AImageReader. */
    fun processNative(
        frames: List<NativeRawFrame>,
        cfaArrangement: Int,
        settings: PhotoLensSettings,
        targetAspect: Float,
        outputDirectory: File,
    ): NativeFusedRaw {
        require(frames.isNotEmpty()) { "At least one NDK RAW frame is required" }
        val core = processInputs(
            frames = frames.map { frame ->
                InputFrame(
                    file = frame.file,
                    width = frame.width,
                    height = frame.height,
                    exposureTimeNs = frame.exposureTimeNs,
                    iso = frame.iso,
                    blackLevels = frame.blackLevels,
                    whiteLevel = frame.whiteLevel,
                    awbGains = frame.awbGains,
                    colorTransform = frame.sensorToLinearSrgb,
                )
            },
            cfaArrangement = cfaArrangement,
            settings = settings,
            targetAspect = targetAspect,
            outputDirectory = outputDirectory,
        )
        return NativeFusedRaw(
            width = core.width,
            height = core.height,
            file = core.file,
            frameCount = frames.size,
            alignments = core.alignments,
            acceptedSamples = core.acceptedSamples,
            rejectedSamples = core.rejectedSamples,
        )
    }

    private fun processInputs(
        frames: List<InputFrame>,
        cfaArrangement: Int,
        settings: PhotoLensSettings,
        targetAspect: Float,
        outputDirectory: File,
    ): CoreResult {
        require(frames.isNotEmpty()) { "At least one RAW frame is required" }
        val width = frames.first().width
        val height = frames.first().height
        require(width > 0 && height > 0 && frames.all { it.width == width && it.height == height }) {
            "RAW burst contains mismatched dimensions"
        }
        require(cfaArrangement in 0..3) { "Unsupported RAW CFA arrangement $cfaArrangement" }
        require(frames.all { it.file.isFile && it.file.length() >= width.toLong() * height.toLong() * 2L }) {
            "RAW burst contains an incomplete Bayer frame"
        }

        outputDirectory.mkdirs()
        val output = File(outputDirectory, "camera_native_${System.nanoTime()}.dng")
        val blackLevels = IntArray(frames.size * 4)
        val whiteLevels = IntArray(frames.size)
        val awbGains = FloatArray(frames.size * 4)
        val colorTransforms = FloatArray(frames.size * 9)
        frames.forEachIndexed { frameIndex, frame ->
            for (channel in 0 until 4) {
                blackLevels[frameIndex * 4 + channel] = frame.blackLevels.getOrElse(channel) { 0 }
                awbGains[frameIndex * 4 + channel] = frame.awbGains.getOrElse(channel) { 1f }
            }
            for (element in 0 until 9) {
                colorTransforms[frameIndex * 9 + element] = frame.colorTransform.getOrElse(element) {
                    if (element % 4 == 0) 1f else 0f
                }
            }
            whiteLevels[frameIndex] = frame.whiteLevel.coerceAtLeast(1)
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
        val aspect = targetAspect.takeIf { it.isFinite() && it > 0f } ?: 4f / 3f
        val cameraModel = buildString {
            append(Build.MANUFACTURER.ifBlank { "Android" })
            append(' ')
            append(Build.MODEL.ifBlank { "Camera" })
        }

        val metrics = nativeProcess(
            inputPaths = frames.map { it.file.absolutePath }.toTypedArray(),
            exposureTimesNs = LongArray(frames.size) { frames[it].exposureTimeNs.coerceAtLeast(1L) },
            sensitivities = IntArray(frames.size) { frames[it].iso.coerceAtLeast(1) },
            blackLevels = blackLevels,
            whiteLevels = whiteLevels,
            awbGains = awbGains,
            colorTransforms = colorTransforms,
            width = width,
            height = height,
            cfaArrangement = cfaArrangement,
            hdrEnabled = hdrEnabled,
            hdrStrength = hdrStrength,
            highlightProtection = highlight,
            shadowRecovery = shadow,
            denoise = denoise,
            sharpness = sharpness,
            saturation = saturation,
            upscaleFactor = upscale,
            targetAspect = aspect,
            cameraModel = cameraModel,
            outputPath = output.absolutePath,
        )

        if (metrics.size < HEADER_SIZE || metrics[0] <= 0L || metrics[1] <= 0L) {
            output.delete()
            val code = metrics.getOrNull(4) ?: -999L
            error("Native Linear DNG processing failed ($code)")
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
            while (size < frames.size) {
                add(RawAlignment(0, 0, if (size == referenceIndex) 1f else 0f))
            }
        }

        return CoreResult(
            width = metrics[0].toInt(),
            height = metrics[1].toInt(),
            file = output,
            referenceIndex = referenceIndex,
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
        whiteLevels: IntArray,
        awbGains: FloatArray,
        colorTransforms: FloatArray,
        width: Int,
        height: Int,
        cfaArrangement: Int,
        hdrEnabled: Boolean,
        hdrStrength: Float,
        highlightProtection: Float,
        shadowRecovery: Float,
        denoise: Float,
        sharpness: Float,
        saturation: Float,
        upscaleFactor: Int,
        targetAspect: Float,
        cameraModel: String,
        outputPath: String,
    ): LongArray

    private data class InputFrame(
        val file: File,
        val width: Int,
        val height: Int,
        val exposureTimeNs: Long,
        val iso: Int,
        val blackLevels: IntArray,
        val whiteLevel: Int,
        val awbGains: FloatArray,
        val colorTransform: FloatArray,
    )

    private data class CoreResult(
        val width: Int,
        val height: Int,
        val file: File,
        val referenceIndex: Int,
        val alignments: List<RawAlignment>,
        val acceptedSamples: Long,
        val rejectedSamples: Long,
    )

    private const val HEADER_SIZE = 5
}
