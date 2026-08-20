package com.camera.camera.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuableCameraResolverTest {
    @Test
    fun hidesLogicalAggregatorByDefault() {
        val decision = ValuableCameraResolver.placeholderDecision(
            ValuableCameraResolver.Candidate(
                cameraId = "logical",
                physicalCameraId = null,
                focalLengthMm = 5f,
                sensorWidthMm = 6f,
                isLogicalAggregator = true,
                photoCapable = true,
                openable = true,
            ),
        )
        assertFalse(decision.visibleByDefault)
    }

    @Test
    fun keepsOpenablePhotoRouteVisibleByDefault() {
        val decision = ValuableCameraResolver.placeholderDecision(
            ValuableCameraResolver.Candidate(
                cameraId = "0",
                physicalCameraId = null,
                focalLengthMm = 5f,
                sensorWidthMm = 6f,
                isLogicalAggregator = false,
                photoCapable = true,
                openable = true,
            ),
        )
        assertTrue(decision.visibleByDefault)
    }
}
