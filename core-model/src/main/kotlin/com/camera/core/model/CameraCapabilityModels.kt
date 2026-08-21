package com.camera.core.model

/** Android-free representations of camera hardware so discovery logic can be unit tested. */
data class PixelSize(
    val width: Int,
    val height: Int,
) {
    val area: Long get() = width.toLong() * height.toLong()
    val megapixels: Double get() = area / 1_000_000.0
}

data class IntValueRange(val lower: Int, val upper: Int)
data class LongValueRange(val lower: Long, val upper: Long)
data class FloatValueRange(val lower: Float, val upper: Float)

enum class CameraRouteKind {
    ENUMERATED,
    LOGICAL_PHYSICAL_MEMBER,
}

data class HighSpeedVideoProfile(
    val size: PixelSize,
    val minFps: Int,
    val maxFps: Int,
)

data class CameraStreamCapabilities(
    val rawSizes: List<PixelSize> = emptyList(),
    val jpegSizes: List<PixelSize> = emptyList(),
    val heicSizes: List<PixelSize> = emptyList(),
    val ultraHdrJpegSizes: List<PixelSize> = emptyList(),
    val yuvSizes: List<PixelSize> = emptyList(),
    val privateSizes: List<PixelSize> = emptyList(),
    val highSpeedVideo: List<HighSpeedVideoProfile> = emptyList(),
)

data class CameraDeviceProfile(
    val routeCameraId: String,
    val physicalCameraId: String? = null,
    val parentLogicalCameraId: String? = null,
    val routeKind: CameraRouteKind,
    val facing: LensFacing,
    val hardwareLevel: String,
    val capabilities: Set<String>,
    val logicalPhysicalIds: Set<String> = emptySet(),
    val focalLengthsMm: List<Float> = emptyList(),
    val apertures: List<Float> = emptyList(),
    val sensorWidthMm: Float? = null,
    val sensorHeightMm: Float? = null,
    val activeArrayWidth: Int? = null,
    val activeArrayHeight: Int? = null,
    val minimumFocusDistanceDiopters: Float? = null,
    val flashAvailable: Boolean = false,
    val oisAvailable: Boolean = false,
    val eisAvailable: Boolean = false,
    val zoomRatioRange: FloatValueRange? = null,
    val isoRange: IntValueRange? = null,
    val exposureTimeRangeNs: LongValueRange? = null,
    val maxAnalogIso: Int? = null,
    val aeFpsRanges: List<IntValueRange> = emptyList(),
    val streams: CameraStreamCapabilities = CameraStreamCapabilities(),
    val cfaArrangement: String? = null,
    val whiteLevel: Int? = null,
    val blackLevels: List<Int> = emptyList(),
    val supportsRaw: Boolean = false,
    val supportsManualSensor: Boolean = false,
    val supportsManualPostProcessing: Boolean = false,
    val supportsBurstCapture: Boolean = false,
    val supportsLogicalMultiCamera: Boolean = false,
    val supportsPrivateReprocessing: Boolean = false,
    val supportsYuvReprocessing: Boolean = false,
    val supportsUltraHighResolution: Boolean = false,
    val dynamicRangeProfiles: List<String> = emptyList(),
    val colorSpaceProfiles: List<String> = emptyList(),
    val discoveryWarnings: List<String> = emptyList(),
) {
    val primaryFocalLengthMm: Float? get() = focalLengthsMm.filter { it > 0f }.minOrNull()
    val equivalentFocalLength35Mm: Float?
        get() {
            val focal = primaryFocalLengthMm ?: return null
            val sensorWidth = sensorWidthMm?.takeIf { it > 0f } ?: return null
            return focal * 36f / sensorWidth
        }

    val maxPhotoPixels: Long
        get() = sequenceOf(
            streams.rawSizes,
            streams.heicSizes,
            streams.jpegSizes,
            streams.yuvSizes,
        ).flatten().maxOfOrNull { it.area } ?: 0L
}
