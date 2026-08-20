package com.camera.camera.capability

import com.camera.core.model.CameraDeviceProfile
import com.camera.core.model.CameraRouteKind
import com.camera.core.model.CameraStreamCapabilities
import com.camera.core.model.LensFacing
import com.camera.core.model.LensRole
import com.camera.core.model.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuableCameraResolverTest {
    @Test
    fun hidesLogicalAggregatorWhenPhysicalMembersAreAvailable() {
        val logical = profile(
            routeCameraId = "logical",
            facing = LensFacing.BACK,
            focal = 4.5f,
            sensorWidth = 6.4f,
            physicalIds = setOf("uw", "main"),
            logical = true,
        )
        val ultra = profile(
            routeCameraId = "logical",
            physicalCameraId = "uw",
            parent = "logical",
            facing = LensFacing.BACK,
            focal = 2.2f,
            sensorWidth = 5.8f,
        )
        val main = profile(
            routeCameraId = "logical",
            physicalCameraId = "main",
            parent = "logical",
            facing = LensFacing.BACK,
            focal = 4.5f,
            sensorWidth = 6.4f,
        )

        val result = ValuableCameraResolver.resolve(listOf(logical, ultra, main))

        assertEquals(2, result.lenses.size)
        assertTrue("logical:direct" in result.hiddenRouteKeys)
        assertEquals(LensRole.ULTRAWIDE, result.lenses[0].inferredRole)
        assertEquals(LensRole.WIDE, result.lenses[1].inferredRole)
    }

    @Test
    fun directEnumeratedRouteWinsOverDuplicatePhysicalMember() {
        val direct = profile(
            routeCameraId = "2",
            facing = LensFacing.BACK,
            focal = 2.2f,
            sensorWidth = 5.8f,
        )
        val duplicatePhysical = profile(
            routeCameraId = "0",
            physicalCameraId = "2",
            parent = "0",
            facing = LensFacing.BACK,
            focal = 2.2f,
            sensorWidth = 5.8f,
        )

        val result = ValuableCameraResolver.resolve(listOf(direct, duplicatePhysical))

        assertEquals(1, result.lenses.size)
        assertEquals("2", result.lenses.single().cameraId)
        assertEquals(null, result.lenses.single().physicalCameraId)
    }

    @Test
    fun zoomAnchorsAreComputedFromOpticsNotCameraIds() {
        val ultra = profile("99", LensFacing.BACK, 2.2f, 5.8f)
        val main = profile("abc", LensFacing.BACK, 4.5f, 6.4f)
        val tele = profile("1", LensFacing.BACK, 9.0f, 6.4f)

        val result = ValuableCameraResolver.resolve(listOf(tele, ultra, main))
        val lenses = result.lenses

        assertEquals(3, lenses.size)
        assertTrue((lenses[0].displayZoomAnchor ?: 1f) < 1f)
        assertEquals(1f, lenses[1].displayZoomAnchor ?: 0f, 0.08f)
        assertTrue((lenses[2].displayZoomAnchor ?: 1f) > 1.5f)
    }

    @Test
    fun nonPhotographicRouteIsHidden() {
        val depth = profile(
            routeCameraId = "depth",
            facing = LensFacing.BACK,
            focal = null,
            sensorWidth = null,
            capabilities = setOf("DEPTH_OUTPUT"),
            photoSize = null,
        )

        val result = ValuableCameraResolver.resolve(listOf(depth))

        assertTrue(result.lenses.isEmpty())
        assertFalse(result.hiddenRouteKeys.isEmpty())
    }

    @Test
    fun validationRejectsMetadataOnlyRouteThatCouldNotCreateSession() {
        val main = profile("main", LensFacing.BACK, 4.5f, 6.4f)
        val vendorAux = profile("aux", LensFacing.BACK, 8.0f, 5.0f)

        val result = ValuableCameraResolver.resolve(
            profiles = listOf(main, vendorAux),
            validatedRouteKeys = setOf("main:direct"),
        )

        assertEquals(1, result.lenses.size)
        assertEquals("main", result.lenses.single().cameraId)
        assertTrue("aux:direct" in result.hiddenRouteKeys)
    }

    private fun profile(
        routeCameraId: String,
        facing: LensFacing,
        focal: Float?,
        sensorWidth: Float?,
        physicalCameraId: String? = null,
        parent: String? = null,
        physicalIds: Set<String> = emptySet(),
        logical: Boolean = false,
        capabilities: Set<String> = setOf("BACKWARD_COMPATIBLE"),
        photoSize: PixelSize? = PixelSize(4000, 3000),
    ): CameraDeviceProfile = CameraDeviceProfile(
        routeCameraId = routeCameraId,
        physicalCameraId = physicalCameraId,
        parentLogicalCameraId = parent,
        routeKind = if (physicalCameraId == null) CameraRouteKind.ENUMERATED else CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
        facing = facing,
        hardwareLevel = "FULL",
        capabilities = capabilities,
        logicalPhysicalIds = physicalIds,
        focalLengthsMm = focal?.let(::listOf).orEmpty(),
        sensorWidthMm = sensorWidth,
        streams = CameraStreamCapabilities(jpegSizes = photoSize?.let(::listOf).orEmpty()),
        supportsLogicalMultiCamera = logical,
    )
}
