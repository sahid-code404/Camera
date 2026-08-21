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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight runtime validation for NDK-only auxiliary cameras.
 *
 * Startup must prove that the native camera can actually be opened and negotiate the same session
 * policy used by the UI, but it should not transfer a multi-megabyte Bayer frame just to populate
 * the lens row. A successful real photo capture later promotes this route to RAW_VERIFIED in the
 * persistent validation cache.
 *
 * NativeCameraNdkBridge.startSession() already performs adaptive session negotiation: it first
 * attempts preview+RAW together and falls back to preview-only for HALs that require a RAW-only
 * shutter session. RAW metadata itself came from a genuine RAW10/RAW12/RAW16 stream declaration.
 */
class NativeCameraRouteProbe(context: Context) : CameraRouteProbe {
    private val appContext = context.applicationContext

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

            result(
                profile,
                CameraRouteProbeStatus.SESSION_CONFIGURED,
                buildString {
                    append("Fast NDK session verified · RAW ")
                    append(descriptor.rawWidth)
                    append('×')
                    append(descriptor.rawHeight)
                    append(" · format=")
                    append(descriptor.rawFormat)
                },
            )
        } finally {
            NativeCameraNdkBridge.stopSession()
            runCatching { surface.release() }
            runCatching { texture.release() }
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
