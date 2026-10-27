package com.luma.remote.data

import org.junit.Assert.*
import org.junit.Test

class RetiredDeviceCleanupTest {
    private val native="""{"id":"atv:living","name":"Living TV","host":"192.168.1.2","platform":"ANDROID_TV"}"""
    @Test fun removingRetiredDevicePreservesNativeDataAndUnknownEntries() {
        val raw="""[$native,{"id":"companion:old","platform":"COMPANION"},{"future":true}]"""
        val keys=setOf("s:pin:atv:living","canvas:atv:living","apps:atv:living","s:tls-identity","s:companion-token:companion:old","s:companion-pin:companion:old","canvas:companion:old","layout:companion:old","apps:companion:old","canvas-presets")
        val (updated,removed)=RetiredDeviceCleanup.plan(raw,keys)
        assertNotNull(updated)
        assertTrue(updated!!.contains("future"))
        assertFalse(updated.contains("COMPANION"))
        assertEquals(keys.filter { it.contains("companion:") }.toSet(),removed)
        assertEquals("atv:living",RetiredDeviceCleanup.devices(updated).single().id)
        assertNull(RetiredDeviceCleanup.plan(updated,keys-removed).first)
    }
    @Test fun malformedOrUnknownNeighborDoesNotHideWorkingTv() {
        assertEquals("atv:living",RetiredDeviceCleanup.devices("""[{"platform":"FUTURE"},$native,null]""").single().id)
        assertEquals(null to emptySet<String>(),RetiredDeviceCleanup.plan("broken",setOf("s:pin:atv:living")))
    }
    @Test fun orphanedRetiredKeysAreRemovedWithoutTouchingIdentity() {
        assertEquals(setOf("canvas:companion:orphan","s:companion-token:orphan"),RetiredDeviceCleanup.plan("[$native]",setOf("canvas:companion:orphan","s:companion-token:orphan","s:tls-identity")).second)
    }
}
