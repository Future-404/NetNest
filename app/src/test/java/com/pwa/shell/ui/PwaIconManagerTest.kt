package com.pwa.shell.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PwaIconManagerTest {

    @Test
    fun `managed icon deletion accepts only direct children`() {
        val root = Files.createTempDirectory("pwa_icons_test").toFile()
        val direct = File(root, "custom.png")
        val nested = File(root, "nested/custom.png")
        val outside = File(root.parentFile, "outside.png")

        assertTrue(isDirectChildPath(root, direct))
        assertFalse(isDirectChildPath(root, nested))
        assertFalse(isDirectChildPath(root, outside))
        assertFalse(isDirectChildPath(root, File(root, "../outside.png")))
    }

    @Test
    fun `center crop fills a square without stretching a wide image`() {
        assertEquals(
            IconDrawBounds(left = -256, top = 0, right = 768, bottom = 512),
            calculateCenterCropBounds(sourceWidth = 1024, sourceHeight = 512, targetSize = 512)
        )
    }

    @Test
    fun `center crop fills a square without stretching a tall image`() {
        assertEquals(
            IconDrawBounds(left = 0, top = -256, right = 512, bottom = 768),
            calculateCenterCropBounds(sourceWidth = 512, sourceHeight = 1024, targetSize = 512)
        )
    }
}
