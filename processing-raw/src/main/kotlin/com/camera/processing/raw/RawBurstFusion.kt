package com.camera.processing.raw

import android.hardware.camera2.CameraCharacteristics
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * First production computational-RAW fusion stage.
 *
 * This is deliberately conservative: Bayer-safe even-pixel global alignment, exposure-normalized
 * robust fusion and motion rejection. It improves SNR/detail without inventing texture. Local,
 * sub-pixel alignment and true multi-frame super-resolution are separate later stages.
 */
object RawBurstFusion {
    fun fuse(
        frames: List<RawFrame>,
        characteristics: CameraCharacteristics,
        scratchDir: File,
    ): FusedRaw {
        require(frames.size >= 3) { "Computational RAW needs at least 3 frames" }
        val first = frames.first()
        require(frames.all { it.width == first.width && it.height == first.height }) {
            "RAW burst dimensions changed inside one capture"
        }
        val referenceIndex = chooseReference(frames)
        val mapped = frames.map(::mapFrame)
        val reference = mapped[referenceIndex]
        val alignments = mapped.mapIndexed { index, candidate ->
            if (index == referenceIndex) RawAlignment(0, 0, 1f)
            else estimateAlignment(reference, candidate)
        }
        val output = File.createTempFile("camera_fused_", ".raw16", scratchDir)
        var accepted = 0L
        var rejected = 0L

        try {
            BufferedOutputStream(FileOutputStream(output), 1 shl 20).use { stream ->
                val rowBytes = ByteArray(first.width * BYTES_PER_PIXEL)
                val row = ByteBuffer.wrap(rowBytes).order(ByteOrder.nativeOrder())
                for (y in 0 until first.height) {
                    row.clear()
                    for (x in 0 until first.width) {
                        val ref = reference.normalizedQ16(x, y)
                        var sum = ref.toLong() * REFERENCE_WEIGHT
                        var weight = REFERENCE_WEIGHT
                        for (i in mapped.indices) {
                            if (i == referenceIndex) continue
                            val alignment = alignments[i]
                            if (alignment.confidence < MIN_ALIGNMENT_CONFIDENCE) {
                                rejected++
                                continue
                            }
                            val sx = x + alignment.dx
                            val sy = y + alignment.dy
                            val candidate = mapped[i]
                            if (sx !in 0 until candidate.frame.width || sy !in 0 until candidate.frame.height) {
                                rejected++
                                continue
                            }
                            val rawCandidate = candidate.normalizedQ16(sx, sy)
                            val scaled = ((rawCandidate.toLong() * candidate.scaleToReferenceQ16 + HALF_Q16) shr 16)
                                .toInt()
                                .coerceIn(0, FULL_SCALE * 2)
                            if (rawCandidate >= SOURCE_CLIP_Q16 && ref < HIGHLIGHT_Q16) {
                                rejected++
                                continue
                            }
                            val delta = abs(scaled - ref)
                            val threshold = MOTION_BASE_Q16 + (ref ushr MOTION_SIGNAL_SHIFT)
                            if (delta > threshold) {
                                rejected++
                                continue
                            }
                            val confidenceWeight = when {
                                alignment.confidence >= 0.75f -> 3
                                alignment.confidence >= 0.45f -> 2
                                else -> 1
                            }
                            sum += scaled.toLong() * confidenceWeight
                            weight += confidenceWeight
                            accepted++
                        }
                        val merged = (sum / weight.coerceAtLeast(1)).toInt().coerceIn(0, FULL_SCALE)
                        row.putShort(reference.encodeFromNormalizedQ16(merged, x, y).toShort())
                    }
                    stream.write(rowBytes)
                    if ((y and 31) == 0) Thread.yield()
                }
            }
            return FusedRaw(
                width = first.width,
                height = first.height,
                file = output,
                referenceResult = frames[referenceIndex].result,
                referenceCharacteristics = characteristics,
                frameCount = frames.size,
                alignments = alignments,
                acceptedSamples = accepted,
                rejectedSamples = rejected,
            )
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private data class MappedFrame(
        val frame: RawFrame,
        val buffer: ByteBuffer,
        val scaleToReferenceQ16: Int,
    ) {
        fun raw(x: Int, y: Int): Int =
            buffer.getShort((y * frame.width + x) * BYTES_PER_PIXEL).toInt() and 0xffff

        fun normalizedQ16(x: Int, y: Int): Int {
            val channel = cfaIndex(x, y)
            val black = frame.blackLevels[channel]
            val range = (frame.whiteLevel - black).coerceAtLeast(1)
            val signal = (raw(x, y) - black).coerceAtLeast(0)
            return ((signal.toLong() * FULL_SCALE.toLong()) / range.toLong())
                .toInt().coerceIn(0, FULL_SCALE)
        }

        fun encodeFromNormalizedQ16(value: Int, x: Int, y: Int): Int {
            val channel = cfaIndex(x, y)
            val black = frame.blackLevels[channel]
            val range = (frame.whiteLevel - black).coerceAtLeast(1)
            val signal = ((value.toLong() * range.toLong() + HALF_Q16) shr 16).toInt()
            return (black + signal).coerceIn(0, 65535)
        }
    }

    private fun mapFrame(frame: RawFrame): MappedFrame {
        val channel = FileChannel.open(frame.file.toPath())
        val buffer = try {
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.nativeOrder())
        } finally {
            channel.close()
        }
        return MappedFrame(frame, buffer, Q16)
    }

    private fun chooseReference(frames: List<RawFrame>): Int {
        val mapped = frames.map(::mapFrame)
        val exposureProducts = frames.map(::exposureProduct)
        val medianExposure = exposureProducts.sorted()[exposureProducts.size / 2]
        return mapped.indices.maxByOrNull { index ->
            val frame = mapped[index]
            val sharpness = sharpnessScore(frame)
            val clipped = clippedFraction(frame)
            val exposurePenalty = abs(exposureProducts[index] / medianExposure.coerceAtLeast(1.0) - 1.0)
                .coerceAtMost(2.0)
            sharpness * (1.0 - clipped * 2.5).coerceAtLeast(0.15) / (1.0 + exposurePenalty * 0.35)
        } ?: 0
    }

    private fun sharpnessScore(frame: MappedFrame): Double {
        var sum = 0L
        var samples = 0
        val step = max(8, min(frame.frame.width, frame.frame.height) / 180)
        var y = step
        while (y < frame.frame.height - step) {
            var x = step
            while (x < frame.frame.width - step) {
                val center = blockLuma(frame, x, y)
                val right = blockLuma(frame, x + step, y)
                val down = blockLuma(frame, x, y + step)
                if (center in DETAIL_DARK_Q16..DETAIL_LIGHT_Q16) {
                    sum += abs(center - right).toLong() + abs(center - down).toLong()
                    samples += 2
                }
                x += step
            }
            y += step
        }
        return if (samples == 0) 0.0 else sum.toDouble() / samples.toDouble()
    }

    private fun clippedFraction(frame: MappedFrame): Double {
        var clipped = 0
        var samples = 0
        val step = max(12, min(frame.frame.width, frame.frame.height) / 140)
        var y = 0
        while (y < frame.frame.height) {
            var x = 0
            while (x < frame.frame.width) {
                if (frame.normalizedQ16(x, y) >= SOURCE_CLIP_Q16) clipped++
                samples++
                x += step
            }
            y += step
        }
        return if (samples == 0) 0.0 else clipped.toDouble() / samples.toDouble()
    }

    private fun estimateAlignment(reference: MappedFrame, candidate: MappedFrame): RawAlignment {
        val coarse = search(reference, candidate, 0, 0, COARSE_RADIUS, COARSE_STEP)
        val fine = search(reference, candidate, coarse.dx, coarse.dy, REFINE_RADIUS, REFINE_STEP)
        if (fine.samples < MIN_ALIGNMENT_SAMPLES || fine.score == Long.MAX_VALUE) {
            return RawAlignment(fine.dx, fine.dy, 0f)
        }
        val mean = fine.score.toDouble() / fine.samples.toDouble() / FULL_SCALE.toDouble()
        val residualConfidence = (1.0 - mean / BAD_ALIGNMENT_RESIDUAL).coerceIn(0.0, 1.0)
        val separation = if (fine.secondScore in 1 until Long.MAX_VALUE) {
            ((fine.secondScore - fine.score).toDouble() / fine.secondScore.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return RawAlignment(
            dx = fine.dx,
            dy = fine.dy,
            confidence = max(residualConfidence, separation * 1.25).coerceIn(0.0, 1.0).toFloat(),
        )
    }

    private data class SearchResult(
        val dx: Int,
        val dy: Int,
        val score: Long,
        val secondScore: Long,
        val samples: Int,
    )

    private fun search(
        reference: MappedFrame,
        candidate: MappedFrame,
        centerDx: Int,
        centerDy: Int,
        radius: Int,
        sampleStep: Int,
    ): SearchResult {
        var bestScore = Long.MAX_VALUE
        var second = Long.MAX_VALUE
        var bestDx = even(centerDx)
        var bestDy = even(centerDy)
        var bestSamples = 0
        var dy = even(centerDy - radius)
        val maxDy = even(centerDy + radius)
        while (dy <= maxDy) {
            var dx = even(centerDx - radius)
            val maxDx = even(centerDx + radius)
            while (dx <= maxDx) {
                var error = 0L
                var samples = 0
                var y = ALIGNMENT_BORDER
                while (y < reference.frame.height - ALIGNMENT_BORDER) {
                    var x = ALIGNMENT_BORDER
                    while (x < reference.frame.width - ALIGNMENT_BORDER) {
                        val cx = x + dx
                        val cy = y + dy
                        if (
                            cx >= 0 && cy >= 0 &&
                            cx + 1 < candidate.frame.width && cy + 1 < candidate.frame.height
                        ) {
                            val a = blockLuma(reference, x, y)
                            val b = blockLuma(candidate, cx, cy)
                            if (a in DETAIL_DARK_Q16..DETAIL_LIGHT_Q16 && b in DETAIL_DARK_Q16..DETAIL_LIGHT_Q16) {
                                error += abs(a - b).toLong()
                                samples++
                            }
                        }
                        x += sampleStep
                    }
                    y += sampleStep
                }
                val score = if (samples >= MIN_ALIGNMENT_SAMPLES) error / samples else Long.MAX_VALUE
                if (score < bestScore) {
                    second = bestScore
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                    bestSamples = samples
                } else if (score < second) {
                    second = score
                }
                dx += CFA_PERIOD
            }
            dy += CFA_PERIOD
        }
        return SearchResult(bestDx, bestDy, bestScore, second, bestSamples)
    }

    private fun blockLuma(frame: MappedFrame, x: Int, y: Int): Int {
        val xx = x.coerceIn(0, frame.frame.width - 2)
        val yy = y.coerceIn(0, frame.frame.height - 2)
        return (
            frame.normalizedQ16(xx, yy) +
                frame.normalizedQ16(xx + 1, yy) +
                frame.normalizedQ16(xx, yy + 1) +
                frame.normalizedQ16(xx + 1, yy + 1)
            ) ushr 2
    }

    private fun exposureProduct(frame: RawFrame): Double =
        frame.exposureTimeNs.toDouble().coerceAtLeast(1.0) * frame.iso.toDouble().coerceAtLeast(1.0)

    private fun even(value: Int): Int = if ((value and 1) == 0) value else value - 1

    private fun cfaIndex(x: Int, y: Int): Int = ((y and 1) shl 1) or (x and 1)

    private const val BYTES_PER_PIXEL = 2
    private const val Q16 = 1 shl 16
    private const val HALF_Q16 = 1 shl 15
    private const val FULL_SCALE = 65535
    private const val REFERENCE_WEIGHT = 3
    private const val SOURCE_CLIP_Q16 = 64200
    private const val HIGHLIGHT_Q16 = 56000
    private const val MOTION_BASE_Q16 = 1600
    private const val MOTION_SIGNAL_SHIFT = 5
    private const val MIN_ALIGNMENT_CONFIDENCE = 0.12f
    private const val DETAIL_DARK_Q16 = 1800
    private const val DETAIL_LIGHT_Q16 = 61000
    private const val ALIGNMENT_BORDER = 40
    private const val COARSE_RADIUS = 16
    private const val REFINE_RADIUS = 4
    private const val COARSE_STEP = 18
    private const val REFINE_STEP = 10
    private const val CFA_PERIOD = 2
    private const val MIN_ALIGNMENT_SAMPLES = 90
    private const val BAD_ALIGNMENT_RESIDUAL = 0.09
}
