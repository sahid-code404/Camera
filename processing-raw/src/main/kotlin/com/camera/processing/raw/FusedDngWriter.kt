package com.camera.processing.raw

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/** Publishes the complete computational DNG produced by the native C++ engine. */
object FusedDngWriter {
    fun save(
        context: Context,
        fused: FusedRaw,
        description: String = "Camera computational Linear DNG V2",
        orientation: Int = 1,
    ): String = saveFile(context, fused.file, description, orientation)

    fun saveNative(
        context: Context,
        fused: NativeFusedRaw,
        description: String = "Camera computational Linear DNG V2 · NDK RAW",
        orientation: Int = 1,
    ): String = saveFile(context, fused.file, description, orientation)

    private fun saveFile(
        context: Context,
        file: File,
        description: String,
        orientation: Int,
    ): String {
        require(file.isFile && file.length() > 0L) { "Native DNG file is missing" }
        val safeOrientation = orientation.takeIf { it in 1..8 } ?: 1
        patchTiffOrientation(file, safeOrientation)

        val resolver = context.contentResolver
        val name = "Camera_LINEAR_V2_${System.currentTimeMillis()}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.DESCRIPTION, description)
            put(MediaStore.Images.Media.ORIENTATION, orientationDegrees(safeOrientation))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create DNG MediaStore entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output, 1024 * 1024) }
            } ?: error("Unable to open DNG output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri.toString()
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Native writer already emits TIFF tag 274; update its SHORT value without re-encoding pixels. */
    private fun patchTiffOrientation(file: File, orientation: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < 16L) return
            val first = raf.readUnsignedByte()
            val second = raf.readUnsignedByte()
            val little = first == 0x49 && second == 0x49
            val big = first == 0x4d && second == 0x4d
            if (!little && !big) return

            fun readU16(): Int {
                val a = raf.readUnsignedByte()
                val b = raf.readUnsignedByte()
                return if (little) a or (b shl 8) else (a shl 8) or b
            }
            fun readU32(): Long {
                val a = raf.readUnsignedByte().toLong()
                val b = raf.readUnsignedByte().toLong()
                val c = raf.readUnsignedByte().toLong()
                val d = raf.readUnsignedByte().toLong()
                return if (little) {
                    a or (b shl 8) or (c shl 16) or (d shl 24)
                } else {
                    (a shl 24) or (b shl 16) or (c shl 8) or d
                }
            }
            fun writeU16(value: Int) {
                if (little) {
                    raf.write(value and 0xff)
                    raf.write((value ushr 8) and 0xff)
                } else {
                    raf.write((value ushr 8) and 0xff)
                    raf.write(value and 0xff)
                }
            }

            if (readU16() != 42) return
            val ifdOffset = readU32()
            if (ifdOffset < 8L || ifdOffset > raf.length() - 2L) return
            raf.seek(ifdOffset)
            val count = readU16()
            for (index in 0 until count) {
                val entryOffset = ifdOffset + 2L + index * 12L
                if (entryOffset + 12L > raf.length()) break
                raf.seek(entryOffset)
                val tag = readU16()
                val type = readU16()
                val valueCount = readU32()
                if (tag == 274 && type == 3 && valueCount >= 1L) {
                    raf.seek(entryOffset + 8L)
                    writeU16(orientation)
                    writeU16(0)
                    return
                }
            }
        }
    }

    private fun orientationDegrees(orientation: Int): Int = when (orientation) {
        6 -> 90
        3 -> 180
        8 -> 270
        else -> 0
    }
}
