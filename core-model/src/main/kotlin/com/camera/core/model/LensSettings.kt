package com.camera.core.model

data class PhotoLensSettings(
    val hdrEnabled: Boolean? = null,
    val hdrStrength: Float? = null,
    val highlightProtection: Float? = null,
    val shadowRecovery: Float? = null,
    val denoise: Float? = null,
    val temporalDenoise: Float? = null,
    val sharpness: Float? = null,
    val texture: Float? = null,
    val saturation: Float? = null,
    val vibrance: Float? = null,
    val warmth: Float? = null,
    val tint: Float? = null,
    val upscaleFactor: Float? = null,
    val trueMultiFrameSuperResolution: Boolean? = null,
)

data class VideoLensSettings(
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val codec: String? = null,
    val targetBitrate: Int? = null,
    val tenBit: Boolean? = null,
    val hdr: Boolean? = null,
    val stabilization: Boolean? = null,
    val allowLensSwitching: Boolean? = null,
    val allowExperimentalCombinations: Boolean? = null,
    val allowUpscaledOutput: Boolean? = null,
)

data class LensUserConfig(
    val lensId: LensStableId,
    val visible: Boolean = true,
    val position: Int = 0,
    val confirmedRole: LensRole? = null,
    val customName: String? = null,
    val displayZoomAnchor: Float? = null,
    val photo: PhotoLensSettings = PhotoLensSettings(),
    val video: VideoLensSettings = VideoLensSettings(),
)
