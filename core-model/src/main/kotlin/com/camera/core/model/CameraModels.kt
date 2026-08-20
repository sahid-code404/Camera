package com.camera.core.model

enum class LensFacing { BACK, FRONT, EXTERNAL, UNKNOWN }

enum class LensRole {
    ULTRAWIDE,
    WIDE,
    TELE,
    LONG_TELE,
    MACRO,
    MONOCHROME,
    FRONT_WIDE,
    FRONT_ULTRAWIDE,
    DEPTH_AUX,
    UNKNOWN,
}

enum class CaptureMode {
    PHOTO,
    VIDEO,
    PORTRAIT,
    NIGHT,
    PRO,
    SLO_MO,
    PANORAMA,
    TIME_LAPSE,
    LONG_EXPOSURE,
    ASTRO,
}

data class LensStableId(
    val value: String,
)

data class ValuableLens(
    val id: LensStableId,
    val cameraId: String,
    val physicalCameraId: String? = null,
    val facing: LensFacing,
    val inferredRole: LensRole,
    val roleConfidence: Float,
    val focalLengthMm: Float?,
    val sensorWidthMm: Float?,
    val displayZoomAnchor: Float?,
    val rawSupported: Boolean,
    val userVisible: Boolean = true,
    val userOrder: Int = 0,
    val userName: String? = null,
)
