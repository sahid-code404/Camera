package com.camera.video.engine

data class VideoRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
    val tenBit: Boolean,
    val hdr: Boolean,
    val stabilization: Boolean,
    val allowExperimental: Boolean,
    val allowFallback: Boolean,
)

data class VideoResolvedStrategy(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
    val tenBit: Boolean,
    val hdr: Boolean,
    val stabilization: Boolean,
    val fallbackReason: String? = null,
    val upscaledOutput: Boolean = false,
)

/**
 * Phase-6 placeholder. The real resolver must use camera stream configs + MediaCodec capabilities
 * and validate combinations, not guess.
 */
interface VideoStrategyResolver {
    suspend fun resolve(request: VideoRequest): VideoResolvedStrategy
}
