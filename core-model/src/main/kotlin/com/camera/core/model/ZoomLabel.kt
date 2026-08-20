package com.camera.core.model

import java.util.Locale

/** Presentation policy for compact lens/zoom pills. */
object ZoomLabel {
    const val MAX_CUSTOM_LABEL_LENGTH = 12

    fun normalizeCustom(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_CUSTOM_LABEL_LENGTH)

    fun resolve(customLabel: String?, numericAnchor: Float?): String {
        normalizeCustom(customLabel)?.let { return it }
        return numericAnchor?.let(::formatAnchor) ?: "LENS"
    }

    fun formatAnchor(anchor: Float): String {
        val safe = anchor.coerceAtLeast(0f)
        val text = when {
            kotlin.math.abs(safe - safe.toInt()) < 0.001f -> safe.toInt().toString()
            safe < 10f -> String.format(Locale.US, "%.1f", safe)
            else -> String.format(Locale.US, "%.0f", safe)
        }
        return "$text×"
    }
}
