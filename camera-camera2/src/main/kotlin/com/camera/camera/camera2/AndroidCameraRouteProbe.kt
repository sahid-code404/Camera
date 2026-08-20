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
 * Runtime validation for metadata-discovered camera routes.
 *
 * A physical route is validated by opening its logical parent CameraDevice and assigning a tiny
 * processed output to the requested physical camera through OutputConfiguration. This is stronger
 * evidence than CameraCharacteristics alone and catches many OEM auxiliary-camera aliases that are
 * listed but not actually usable by the current application identity.
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

        val target = createProbeTarget(profile)
            ?: return result(profile, CameraRouteProbeStatus.NO_PROBE_STREAM, "No processed probe stream")

        return target.use {
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
                val configured = try {
                    withTimeout(SESSION_TIMEOUT_MS) {
                        configureProbeSession(device, profile, target.surface)
                    }
                } catch (error: TimeoutCancellationException) {
                    return@use result(profile, CameraRouteProbeStatus.TIMED_OUT, "Session configuration timed out")
                } catch (error: Throwable) {
                    return@use result(
                        profile,
                        CameraRouteProbeStatus.SESSION_FAILED,
                        error.message ?: error.javaClass.simpleName,
                    )
                }

                if (configured) {
                    result(profile, CameraRouteProbeStatus.SESSION_CONFIGURED, "${target.size.width}×${target.size.height}")
                } else {
                    result(profile, CameraRouteProbeStatus.SESSION_FAILED, "CameraCaptureSession rejected probe output")
                }
            } finally {
                device.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        // probe() performs the runtime permission gate immediately before this method. The lint
        // suppression is deliberately narrow rather than suppressing permission checks globally.
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
        profile.physicalCameraId?.let { physicalId -> output.setPhysicalCameraId(physicalId) }

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

    private fun createProbeTarget(profile: CameraDeviceProfile): ProbeTarget? {
        val privateSize = chooseProbeSize(profile.streams.privateSizes)
        if (privateSize != null) return PrivateProbeTarget(privateSize)

        val yuvSize = chooseProbeSize(profile.streams.yuvSizes)
        if (yuvSize != null) return YuvProbeTarget(yuvSize)
        return null
    }

    private fun chooseProbeSize(sizes: List<PixelSize>): PixelSize? {
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

    private companion object {
        const val OPEN_TIMEOUT_MS = 2_500L
        const val SESSION_TIMEOUT_MS = 2_500L
    }
}
