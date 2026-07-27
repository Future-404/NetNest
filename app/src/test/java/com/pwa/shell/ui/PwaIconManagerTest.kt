package com.pwa.shell.ui

import java.io.File
import java.nio.file.Files
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
}
