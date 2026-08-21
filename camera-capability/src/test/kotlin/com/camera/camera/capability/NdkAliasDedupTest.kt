package com.camera.camera.capability

import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.CameraRouteKind
import com.camera.core.model.CameraStreamCapabilities
import com.camera.core.model.LensFacing
import com.camera.core.model.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdkAliasDedupTest {
    @Test
    fun ndkAliasesDoNotCreateExtraRearOrFrontLenses() {
        val rearJava = profile(
            id = "0",
            facing = LensFacing.BACK,
            focal = 4.74f,
            sensorWidth = 6.40f,
            sensorHeight = 4.80f,
            activeWidth = 4000,
            activeHeight = 3000,
            rawSize = PixelSize(4000, 3000),
            apertures = listOf(1.8f),
        )
        val rearNdkAlias = profile(
            id = "20",
            facing = LensFacing.BACK,
            focal = 4.74f,
            sensorWidth = 6.40f,
            sensorHeight = 4.80f,
            activeWidth = 4000,
            activeHeight = 3000,
            rawSize = PixelSize(3992, 2992),
            capabilities = setOf("RAW", "BACKWARD_COMPATIBLE", "NDK_ENUMERATED"),
        )
        val frontJava = profile(
            id = "1",
            facing = LensFacing.FRONT,
            focal = 3.20f,
            sensorWidth = 4.80f,
            sensorHeight = 3.60f,
            activeWidth = 3264,
            activeHeight = 2448,
            rawSize = PixelSize(3264, 2448),
            apertures = listOf(2.4f),
        )
        val frontNdkAlias = profile(
            id = "21",
            facing = LensFacing.FRONT,
            focal = 3.20f,
            sensorWidth = 4.80f,
            sensorHeight = 3.60f,
            activeWidth = 3264,
            activeHeight = 2448,
            rawSize = PixelSize(3264, 2448),
            capabilities = setOf("RAW", "BACKWARD_COMPATIBLE", "NDK_ENUMERATED"),
        )

        val result = ValuableCameraResolver.resolve(
            listOf(rearNdkAlias, frontNdkAlias, rearJava, frontJava),
        )

        assertEquals(2, result.lenses.size)
        assertEquals(1, result.lenses.count { it.facing == LensFacing.BACK })
        assertEquals(1, result.lenses.count { it.facing == LensFacing.FRONT })
        assertEquals("0", result.lenses.first { it.facing == LensFacing.BACK }.cameraId)
        assertEquals("1", result.lenses.first { it.facing == LensFacing.FRONT }.cameraId)
        assertTrue("20:direct" in result.hiddenRouteKeys)
        assertTrue("21:direct" in result.hiddenRouteKeys)
    }

    @Test
    fun genuinelyNdkOnlyAuxLensIsStillKept() {
        val main = profile(
            id = "0",
            facing = LensFacing.BACK,
            focal = 4.74f,
            sensorWidth = 6.40f,
            sensorHeight = 4.80f,
            activeWidth = 4000,
            activeHeight = 3000,
            rawSize = PixelSize(4000, 3000),
        )
        val ndkOnlyUltraWide = profile(
            id = "22",
            facing = LensFacing.BACK,
            focal = 2.20f,
            sensorWidth = 5.80f,
            sensorHeight = 4.35f,
            activeWidth = 4000,
            activeHeight = 3000,
            rawSize = PixelSize(4000, 3000),
            capabilities = setOf("RAW", "BACKWARD_COMPATIBLE", "NDK_ENUMERATED"),
        )

        val result = ValuableCameraResolver.resolve(listOf(main, ndkOnlyUltraWide))

        assertEquals(2, result.lenses.size)
        assertTrue(result.lenses.any { it.cameraId == "22" })
    }

    private fun profile(
        id: String,
        facing: LensFacing,
        focal: Float,
        sensorWidth: Float,
        sensorHeight: Float,
        activeWidth: Int,
        activeHeight: Int,
        rawSize: PixelSize,
        apertures: List<Float> = emptyList(),
        capabilities: Set<String> = setOf("RAW", "BACKWARD_COMPATIBLE"),
    ): CameraDeviceProfile = CameraDeviceProfile(
        routeCameraId = id,
        routeKind = CameraRouteKind.ENUMERATED,
        facing = facing,
        hardwareLevel = "FULL",
        capabilities = capabilities,
        focalLengthsMm = listOf(focal),
        apertures = apertures,
        sensorWidthMm = sensorWidth,
        sensorHeightMm = sensorHeight,
        activeArrayWidth = activeWidth,
        activeArrayHeight = activeHeight,
        streams = CameraStreamCapabilities(rawSizes = listOf(rawSize)),
        cfaArrangement = "RGGB",
        supportsRaw = true,
        supportsManualSensor = true,
        supportsBurstCapture = true,
    )
}
