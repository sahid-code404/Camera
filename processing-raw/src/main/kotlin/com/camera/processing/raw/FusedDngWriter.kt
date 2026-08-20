package com.camera.processing.raw

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.FileInputStream

/**
 * Publishes the DNG file produced by the native C++ engine.
 *
 * Android no longer constructs or interprets the computational DNG. MediaStore is used only as the
 * public-file publication boundary.
 */
object FusedDngWriter {
    fun save(
        context: Context,
        fused: FusedRaw,
        description: String = "Camera computational Linear DNG V2",
    ): String {
        require(fused.file.isFile && fused.file.length() > 0L) { "Native DNG file is missing" }
        val resolver = context.contentResolver
        // Deliberately different from the legacy Camera_CRAW_* name. A real V2 capture can be
        // identified without opening metadata, which prevents stale APK/output confusion.
        val name = "Camera_LINEAR_V2_${System.currentTimeMillis()}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.DESCRIPTION, description)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create DNG MediaStore entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(fused.file).use { input -> input.copyTo(output, 1024 * 1024) }
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
}
