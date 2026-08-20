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
    /**
     * Optical zoom anchor used by the camera engine for ordering/zoom math.
     * This remains numeric even when the user chooses a completely custom visible label.
     */
    val displayZoomAnchor: Float? = null,
    /**
     * Optional user-facing zoom/lens label shown on the camera selector.
     * Examples: "0.6×", "1×", "35mm", "MAIN", "TELE".
     * It is presentation-only and must never change the physical zoom calculation.
     */
    val customZoomLabel: String? = null,
    val photo: PhotoLensSettings = PhotoLensSettings(),
    val video: VideoLensSettings = VideoLensSettings(),
)
