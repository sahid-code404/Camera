package com.camera.camera.camera2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import com.camera.camera.api.CameraRouteProbe
import com.camera.camera.api.CameraRouteProbeResult
import com.camera.camera.api.CameraRouteProbeStatus
import com.camera.core.model.CameraDeviceProfile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Strong runtime validation for NDK-only auxiliary cameras.
 *
 * Metadata enumeration is not enough: vendor aliases can advertise RAW yet reject the actual
 * preview/RAW combination. This probe starts the same adaptive native session used by the UI and
 * acquires one genuine RAW frame. Only routes that pass both operations are allowed into the
 * validated user-facing lens set.
 */
class NativeCameraRouteProbe(context: Context) : CameraRouteProbe {
    private val appContext = context.applicationContext
    private val scratchDir = File(appContext.cacheDir, "native-route-probe")

    override suspend fun probe(profile: CameraDeviceProfile): CameraRouteProbeResult = withContext(Dispatchers.IO) {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return@withContext result(
                profile,
                CameraRouteProbeStatus.PERMISSION_DENIED,
                "Camera permission is required",
            )
        }
        val descriptor = NativeCameraNdkBridge.descriptor(profile.routeCameraId)
            ?: return@withContext result(
                profile,
                CameraRouteProbeStatus.NO_PROBE_STREAM,
                "No NDK RAW descriptor",
            )

        scratchDir.mkdirs()
        scratchDir.listFiles()?.forEach { runCatching { it.delete() } }
        val texture = SurfaceTexture(false).apply {
            setDefaultBufferSize(descriptor.previewWidth, descriptor.previewHeight)
        }
        val surface = Surface(texture)
        try {
            val startError = NativeCameraNdkBridge.startSession(profile.routeCameraId, surface)
            if (startError != null) {
                val status = if (startError.contains("open", ignoreCase = true)) {
                    CameraRouteProbeStatus.OPEN_FAILED
                } else {
                    CameraRouteProbeStatus.SESSION_FAILED
                }
                return@withContext result(profile, status, startError)
            }

            val burst = try {
                NativeCameraNdkBridge.captureBurst(
                    scratchDirectory = scratchDir,
                    frameCount = 1,
                    hdrStrength = 0f,
                )
            } catch (error: Throwable) {
                return@withContext result(
                    profile,
                    CameraRouteProbeStatus.SESSION_FAILED,
                    error.message ?: "Native RAW probe capture failed",
                )
            }
            burst.frames.forEach { runCatching { it.file.delete() } }
            result(
                profile,
                CameraRouteProbeStatus.SESSION_CONFIGURED,
                buildString {
                    append("NDK RAW verified ")
                    append(descriptor.rawWidth)
                    append('×')
                    append(descriptor.rawHeight)
                    append(" · ")
                    append(burst.captureStrategy)
                },
            )
        } finally {
            NativeCameraNdkBridge.stopSession()
            runCatching { surface.release() }
            runCatching { texture.release() }
            scratchDir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private fun result(
        profile: CameraDeviceProfile,
        status: CameraRouteProbeStatus,
        message: String?,
    ) = CameraRouteProbeResult(
        routeCameraId = profile.routeCameraId,
        physicalCameraId = profile.physicalCameraId,
        status = status,
        message = message,
    )
}
