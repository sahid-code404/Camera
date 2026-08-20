package com.camera.processing.raw

import android.graphics.ImageFormat
import android.media.Image
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copies a RAW_SENSOR Image into tightly packed file-backed RAW16 and immediately releases Image. */
object RawFrameStager {
    fun stage(image: Image, scratchDir: File): StagedRawPayload {
        require(image.format == ImageFormat.RAW_SENSOR) { "Expected RAW_SENSOR, got ${image.format}" }
        scratchDir.mkdirs()
        val width = image.width
        val height = image.height
        val timestamp = image.timestamp
        val plane = image.planes.single()
        val file = File.createTempFile("camera_raw_", ".raw16", scratchDir)

        try {
            FileOutputStream(file).channel.use { channel ->
                val source = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
                val base = source.position()
                val limit = source.limit()
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                require(pixelStride >= BYTES_PER_PIXEL) {
                    "Unsupported RAW pixelStride=$pixelStride"
                }
                val packedRowBytes = width * BYTES_PER_PIXEL
                val packedFrameBytes = packedRowBytes.toLong() * height.toLong()

                if (
                    pixelStride == BYTES_PER_PIXEL &&
                    rowStride == packedRowBytes &&
                    packedFrameBytes <= Int.MAX_VALUE &&
                    base.toLong() + packedFrameBytes <= limit.toLong()
                ) {
                    val contiguous = source.duplicate()
                    contiguous.position(base)
                    contiguous.limit(base + packedFrameBytes.toInt())
                    val slice = contiguous.slice()
                    while (slice.hasRemaining()) channel.write(slice)
                } else {
                    val packedRow = ByteBuffer.allocateDirect(packedRowBytes).order(ByteOrder.nativeOrder())
                    for (y in 0 until height) {
                        val rowStart = base + y * rowStride
                        if (pixelStride == BYTES_PER_PIXEL && rowStart + packedRowBytes <= limit) {
                            val row = source.duplicate()
                            row.position(rowStart)
                            row.limit(rowStart + packedRowBytes)
                            val slice = row.slice()
                            while (slice.hasRemaining()) channel.write(slice)
                        } else {
                            packedRow.clear()
                            for (x in 0 until width) {
                                val offset = rowStart + x * pixelStride
                                packedRow.putShort(if (offset + 1 < limit) source.getShort(offset) else 0)
                            }
                            packedRow.flip()
                            while (packedRow.hasRemaining()) channel.write(packedRow)
                        }
                    }
                }
            }
            return StagedRawPayload(timestamp, width, height, file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private const val BYTES_PER_PIXEL = 2
}
