package com.camera.processing.raw

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.DngCreator
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.FileInputStream

/** Writes the fused native-resolution Bayer master as a standards-compliant DNG. */
object FusedDngWriter {
    fun save(
        context: Context,
        fused: FusedRaw,
        description: String = "Camera computational RAW",
    ): String {
        val resolver = context.contentResolver
        val name = "Camera_CRAW_${System.currentTimeMillis()}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create DNG MediaStore entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                DngCreator(fused.referenceCharacteristics, fused.referenceResult).use { dng ->
                    dng.setDescription(
                        "$description · ${fused.frameCount} frames · " +
                            "accepted=${fused.acceptedSamples} rejected=${fused.rejectedSamples}",
                    )
                    FileInputStream(fused.file).use { input ->
                        dng.writeInputStream(
                            output,
                            Size(fused.width, fused.height),
                            input,
                            0L,
                        )
                    }
                }
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
