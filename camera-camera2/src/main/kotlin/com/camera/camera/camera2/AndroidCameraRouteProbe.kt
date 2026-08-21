package com.camera.camera.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.view.Surface
import com.camera.camera.api.CameraRouteProbe
import com.camera.camera.api.CameraRouteProbeResult
import com.camera.camera.api.CameraRouteProbeStatus
import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.PixelSize
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Runtime validation for Java Camera2/logical-physical routes.
 *
 * PHOTO capture intentionally uses the compatibility-first strategy: preview is configured on its
 * own, then shutter briefly switches to a RAW-only session. Therefore a route is user-facing only
 * if BOTH the real preview output and a genuine RAW_SENSOR output can be configured for the same
 * direct/physical lens. This avoids metadata-only false positives without requiring the HAL to
 * support preview + RAW concurrently.
 */
class AndroidCameraRouteProbe(context: Context) : CameraRouteProbe, AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CameraRouteProbe").apply { priority = Thread.NORM_PRIORITY }
    }

    override suspend fun probe(profile: CameraDeviceProfile): CameraRouteProbeResult {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return result(profile, CameraRouteProbeStatus.PERMISSION_DENIED, "Camera permission is required")
        }

        val previewTarget = createPreviewProbeTarget(profile)
            ?: return result(profile, CameraRouteProbeStatus.NO_PROBE_STREAM, "No preview probe stream")
        val rawTarget = createRawProbeTarget(profile)
            ?: run {
                previewTarget.close()
                return result(profile, CameraRouteProbeStatus.NO_PROBE_STREAM, "No RAW_SENSOR probe stream")
            }

        return previewTarget.use { preview ->
            rawTarget.use { raw ->
                val device = try {
                    withTimeout(OPEN_TIMEOUT_MS) { openDevice(profile.routeCameraId) }
                } catch (error: TimeoutCancellationException) {
                    return@use result(profile, CameraRouteProbeStatus.TIMED_OUT, "Camera open timed out")
                } catch (error: SecurityException) {
                    return@use result(profile, CameraRouteProbeStatus.PERMISSION_DENIED, error.message)
                } catch (error: Throwable) {
                    return@use result(
                        profile,
                        CameraRouteProbeStatus.OPEN_FAILED,
                        error.message ?: error.javaClass.simpleName,
                    )
                }

                try {
                    val previewOkay = configureSafely(device, profile, preview.surface, "Preview")
                        ?: return@use result(
                            profile,
                            CameraRouteProbeStatus.TIMED_OUT,
                            "Preview session configuration timed out",
                        )
                    if (!previewOkay) {
                        return@use result(
                            profile,
                            CameraRouteProbeStatus.SESSION_FAILED,
                            "Camera rejected preview route",
                        )
                    }

                    val rawOkay = configureSafely(device, profile, raw.surface, "RAW")
                        ?: return@use result(
                            profile,
                            CameraRouteProbeStatus.TIMED_OUT,
                            "RAW session configuration timed out",
                        )
                    if (!rawOkay) {
                        return@use result(
                            profile,
                            CameraRouteProbeStatus.SESSION_FAILED,
                            "Camera rejected RAW_SENSOR route",
                        )
                    }

                    result(
                        profile,
                        CameraRouteProbeStatus.SESSION_CONFIGURED,
                        "Preview ${preview.size.width}×${preview.size.height} · RAW ${raw.size.width}×${raw.size.height} · switch",
                    )
                } catch (error: Throwable) {
                    result(
                        profile,
                        CameraRouteProbeStatus.SESSION_FAILED,
                        error.message ?: error.javaClass.simpleName,
                    )
                } finally {
                    device.close()
                }
            }
        }
    }

    private suspend fun configureSafely(
        device: CameraDevice,
        profile: CameraDeviceProfile,
        surface: Surface,
        label: String,
    ): Boolean? = try {
        withTimeout(SESSION_TIMEOUT_MS) {
            configureProbeSession(device, profile, surface)
        }
    } catch (_: TimeoutCancellationException) {
        null
    } catch (error: Throwable) {
        throw IllegalStateException("$label session failed: ${error.message ?: error.javaClass.simpleName}", error)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        val opened = AtomicReference<CameraDevice?>(null)
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened.set(camera)
                if (continuation.isActive) continuation.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Camera disconnected while probing"))
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Camera open error $error"))
                }
            }
        }

        try {
            manager.openCamera(cameraId, executor, callback)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        continuation.invokeOnCancellation { opened.getAndSet(null)?.close() }
    }

    private suspend fun configureProbeSession(
        device: CameraDevice,
        profile: CameraDeviceProfile,
        surface: Surface,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val sessionRef = AtomicReference<CameraCaptureSession?>(null)
        val output = OutputConfiguration(surface)
        profile.physicalCameraId?.let(output::setPhysicalCameraId)

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sessionRef.set(session)
                session.close()
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                sessionRef.set(session)
                session.close()
                if (continuation.isActive) continuation.resume(false)
            }
        }

        try {
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    callback,
                ),
            )
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        continuation.invokeOnCancellation { sessionRef.getAndSet(null)?.close() }
    }

    private fun createPreviewProbeTarget(profile: CameraDeviceProfile): ProbeTarget? {
        val privateSize = choosePreviewSize(profile.streams.privateSizes)
        if (privateSize != null) return PrivateProbeTarget(privateSize)

        val yuvSize = choosePreviewSize(profile.streams.yuvSizes)
        if (yuvSize != null) return YuvProbeTarget(yuvSize)
        return null
    }

    private fun createRawProbeTarget(profile: CameraDeviceProfile): ProbeTarget? {
        val size = profile.streams.rawSizes.maxByOrNull { it.area } ?: return null
        return runCatching { RawProbeTarget(size) }.getOrNull()
    }

    private fun choosePreviewSize(sizes: List<PixelSize>): PixelSize? {
        if (sizes.isEmpty()) return null
        val moderate = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        return moderate.maxByOrNull { it.area } ?: sizes.minByOrNull { it.area }
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

    override fun close() {
        executor.shutdownNow()
    }

    private sealed interface ProbeTarget : AutoCloseable {
        val size: PixelSize
        val surface: Surface
    }

    private class PrivateProbeTarget(
        override val size: PixelSize,
    ) : ProbeTarget {
        private val texture = SurfaceTexture(false).apply {
            setDefaultBufferSize(size.width, size.height)
        }
        override val surface = Surface(texture)

        override fun close() {
            surface.release()
            texture.release()
        }
    }

    private class YuvProbeTarget(
        override val size: PixelSize,
    ) : ProbeTarget {
        private val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            2,
        )
        override val surface: Surface get() = reader.surface

        override fun close() {
            reader.close()
        }
    }

    private class RawProbeTarget(
        override val size: PixelSize,
    ) : ProbeTarget {
        private val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.RAW_SENSOR,
            2,
        )
        override val surface: Surface get() = reader.surface

        override fun close() {
            reader.close()
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 3_000L
        const val SESSION_TIMEOUT_MS = 3_000L
    }
}
