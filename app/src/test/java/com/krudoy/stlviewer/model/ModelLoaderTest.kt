package com.krudoy.stlviewer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLoaderTest {
    private fun sniff(s: String): ModelFormat = s.toByteArray().let { ModelLoader.sniff(it, it.size) }

    @Test fun detectsStep() {
        assertEquals(ModelFormat.STEP, sniff("ISO-10303-21;\nHEADER;"))
        assertEquals(ModelFormat.STEP, sniff("﻿  ISO-10303-21;"))
    }

    @Test fun detectsZipAsThreeMf() {
        assertEquals(ModelFormat.THREE_MF, ModelLoader.sniff(byteArrayOf(0x50, 0x4B, 3, 4, 0), 5))
    }

    @Test fun everythingElseIsStl() {
        assertEquals(ModelFormat.STL, sniff("solid cube"))
        assertEquals(ModelFormat.STL, sniff("binary header"))
    }
}
