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
 * Fast runtime validation for Java Camera2/logical-physical routes.
 *
 * Validation is deliberately session-only: moving a full Bayer frame is capture work, not startup
 * work. The common path tries one combined preview+RAW session. If a HAL rejects that combination,
 * we fall back to the compatibility path used by the app at shutter time: preview-only followed by
 * RAW-only. Physical children sharing one logical parent are batch-probed through a single opened
 * CameraDevice, avoiding repeated expensive camera opens.
 *
 * Probe surfaces use small declared stream sizes. Full-resolution RAW is exercised by the real
 * capture path and a successful DNG promotes the route to RAW_VERIFIED in the persistent cache.
 */
class AndroidCameraRouteProbe(context: Context) : CameraRouteProbe, AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CameraRouteProbe").apply { priority = Thread.NORM_PRIORITY }
    }

    override suspend fun probe(profile: CameraDeviceProfile): CameraRouteProbeResult =
        probeBatch(listOf(profile)).first()

    /**
     * Opens each logical/direct CameraDevice only once, then validates all selected physical routes
     * that share it. This is a major cold-start win on logical multi-camera phones.
     */
    suspend fun probeBatch(profiles: List<CameraDeviceProfile>): List<CameraRouteProbeResult> {
        if (profiles.isEmpty()) return emptyList()
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return profiles.map {
                result(it, CameraRouteProbeStatus.PERMISSION_DENIED, "Camera permission is required")
            }
        }

        val results = mutableMapOf<String, CameraRouteProbeResult>()
        profiles.groupBy { it.routeCameraId }.forEach { (cameraId, group) ->
            val device = try {
                withTimeout(OPEN_TIMEOUT_MS) { openDevice(cameraId) }
            } catch (error: TimeoutCancellationException) {
                group.forEach { profile ->
                    results[routeKey(profile)] = result(
                        profile,
                        CameraRouteProbeStatus.TIMED_OUT,
                        "Camera open timed out",
                    )
                }
                return@forEach
            } catch (error: SecurityException) {
                group.forEach { profile ->
                    results[routeKey(profile)] = result(
                        profile,
                        CameraRouteProbeStatus.PERMISSION_DENIED,
                        error.message,
                    )
                }
                return@forEach
            } catch (error: Throwable) {
                group.forEach { profile ->
                    results[routeKey(profile)] = result(
                        profile,
                        CameraRouteProbeStatus.OPEN_FAILED,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
                return@forEach
            }

            try {
                group.forEach { profile ->
                    results[routeKey(profile)] = probeOnOpenDevice(device, profile)
                }
            } finally {
                device.close()
            }
        }
        return profiles.map { profile ->
            results[routeKey(profile)]
                ?: result(profile, CameraRouteProbeStatus.SESSION_FAILED, "Route probe did not complete")
        }
    }

    private suspend fun probeOnOpenDevice(
        device: CameraDevice,
        profile: CameraDeviceProfile,
    ): CameraRouteProbeResult {
        val previewTarget = createPreviewProbeTarget(profile)
            ?: return result(profile, CameraRouteProbeStatus.NO_PROBE_STREAM, "No preview probe stream")
        val rawTarget = createRawProbeTarget(profile)
            ?: run {
                previewTarget.close()
                return result(profile, CameraRouteProbeStatus.NO_PROBE_STREAM, "No RAW_SENSOR probe stream")
            }

        return previewTarget.use { preview ->
            rawTarget.use { raw ->
                try {
                    // Fast/common case: one configuration proves both surfaces and avoids two
                    // session round-trips.
                    val combined = configureSafely(
                        device = device,
                        profile = profile,
                        surfaces = listOf(preview.surface, raw.surface),
                        label = "Preview + RAW",
                    )
                    if (combined == true) {
                        return@use result(
                            profile,
                            CameraRouteProbeStatus.SESSION_CONFIGURED,
                            "Fast concurrent probe · preview ${preview.size.width}×${preview.size.height} · RAW ${raw.size.width}×${raw.size.height}",
                        )
                    }

                    // Compatibility path for aux sensors that reject concurrent outputs.
                    val previewOkay = configureSafely(
                        device = device,
                        profile = profile,
                        surfaces = listOf(preview.surface),
                        label = "Preview",
                    ) ?: return@use result(
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

                    val rawOkay = configureSafely(
                        device = device,
                        profile = profile,
                        surfaces = listOf(raw.surface),
                        label = "RAW",
                    ) ?: return@use result(
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
                        "Fast switch probe · preview ${preview.size.width}×${preview.size.height} · RAW ${raw.size.width}×${raw.size.height}",
                    )
                } catch (error: Throwable) {
                    result(
                        profile,
                        CameraRouteProbeStatus.SESSION_FAILED,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private suspend fun configureSafely(
        device: CameraDevice,
        profile: CameraDeviceProfile,
        surfaces: List<Surface>,
        label: String,
    ): Boolean? = try {
        withTimeout(SESSION_TIMEOUT_MS) {
            configureProbeSession(device, profile, surfaces)
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
        surfaces: List<Surface>,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val sessionRef = AtomicReference<CameraCaptureSession?>(null)
        var outcome: Boolean? = null
        val outputs = surfaces.map { surface ->
            OutputConfiguration(surface).apply {
                profile.physicalCameraId?.let(::setPhysicalCameraId)
            }
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sessionRef.set(session)
                outcome = true
                session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                sessionRef.set(session)
                outcome = false
                session.close()
            }

            override fun onClosed(session: CameraCaptureSession) {
                if (sessionRef.compareAndSet(session, null) && continuation.isActive) {
                    continuation.resume(outcome == true)
                }
            }
        }

        try {
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
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
        // Smallest declared RAW stream is sufficient for fast startup route validation. Real photo
        // capture still requests the highest RAW resolution and promotes the cache after success.
        val size = profile.streams.rawSizes.minByOrNull { it.area } ?: return null
        return runCatching { RawProbeTarget(size) }.getOrNull()
    }

    private fun choosePreviewSize(sizes: List<PixelSize>): PixelSize? {
        if (sizes.isEmpty()) return null
        val normal = sizes.filter { it.width >= 320 && it.height >= 240 }
        val bounded = normal.filter { it.width <= 1280 && it.height <= 720 }
        return bounded.minByOrNull { it.area }
            ?: normal.minByOrNull { it.area }
            ?: sizes.minByOrNull { it.area }
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

    private fun routeKey(profile: CameraDeviceProfile): String =
        "${profile.routeCameraId}:${profile.physicalCameraId ?: "direct"}"

    private companion object {
        const val OPEN_TIMEOUT_MS = 1_800L
        const val SESSION_TIMEOUT_MS = 1_500L
    }
}
