package com.luma.remote.ui.theme

import org.junit.Assert.*
import org.junit.Test

class AccentPaletteTest {
    @Test fun onlyOpaqueRgbSeedsAreAccepted() {
        assertEquals("12ABEF",AccentPalette.normalize("#12abef"))
        listOf(null,"","123","12345678","#FF00ZZ","112233 ","javascript:bad").forEach { assertNull(AccentPalette.normalize(it)) }
        assertEquals(AccentPalette.from(AccentChoice.LIME.hex),AccentPalette.from("bad"))
    }
    @Test fun allChoicesAndCustomExtremesKeepReadableForegrounds() {
        val seeds=AccentChoice.entries.map { it.hex }+listOf("000000","FFFFFF","FF0000","0000FF","00FF00","123456")
        seeds.forEach { seed -> assertReadable(seed) }
        assertEquals(6,AccentChoice.entries.size)
        assertEquals(6,AccentChoice.entries.map { AccentPalette.from(it.hex) }.distinct().size)
    }
    @Test fun generatedTonesMeetContrastAcrossTheRgbCube() {
        for(r in 0..15)for(g in 0..15)for(b in 0..15)assertReadable("%02X%02X%02X".format(r*17,g*17,b*17))
    }
    private fun assertReadable(seed:String) {
        val p=AccentPalette.from(seed)
        assertTrue(seed,AccentPalette.contrast(p.accent,p.raised)>=4.5)
        assertTrue(seed,AccentPalette.contrast(p.accent,p.background)>=4.5)
        assertTrue(seed,AccentPalette.contrast(p.text,p.surface)>=4.5)
        assertTrue(seed,AccentPalette.contrast(p.muted,p.raised)>=4.5)
        assertEquals(255,p.accent ushr 24)
    }
}
