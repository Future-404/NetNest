package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PwaIconDraftTest {

    @Test
    fun `saved custom icon survives immediate dialog disposal`() {
        val draft = PwaIconDraft("/icons/website.png")
        draft.replace("/icons/custom.png")

        assertEquals("/icons/custom.png", draft.pathToDeleteOnDispose())

        draft.commit()

        assertNull(draft.pathToDeleteOnDispose())
    }
}
