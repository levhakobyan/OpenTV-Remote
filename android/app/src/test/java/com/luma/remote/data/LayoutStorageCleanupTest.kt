package com.luma.remote.data

import com.luma.remote.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayoutStorageCleanupTest {
    private val canvas=RemoteCanvas(emptyList(),rows=1)
    @Test fun activeCanvasDropsRetiredFieldsButKeepsTvActionAndUnknownData() {
        val raw=RemoteLayout(size=ControlSize.LARGE,tvAction=TvAction.APP,tvApp=AppShortcut.defaults.first().url,tvConfigured=true).encode()
        val data=JSONObject(raw).put("future", "preserve me")
        val cleaned=LayoutStorageCleanup.clean(data.toString(),canvas)!!
        val json=JSONObject(cleaned)
        assertFalse(json.has("order"));assertFalse(json.has("blockSizes"));assertFalse(json.has("fitScreen"));assertFalse(json.has("size"))
        val restored=RemoteLayout.decode(cleaned)
        assertEquals(TvAction.APP,restored.tvAction);assertTrue(restored.tvConfigured)
        assertEquals(AppShortcut.defaults.first().url,restored.tvApp)
        assertEquals("preserve me",json.getString("future"))
        assertEquals(cleaned,LayoutStorageCleanup.clean(cleaned,canvas))
    }
    @Test fun legacyRendererKeepsItsLayoutWhileOldFitFalseUpgrades() {
        val raw=RemoteLayout(size=ControlSize.LARGE,volumeRight=true).encode().replace("\"fitScreen\":true","\"fitScreen\":false")
        for(value in listOf(null,canvas.copy(enabled=false))) {
            val json=JSONObject(LayoutStorageCleanup.clean(raw,value)!!)
            assertTrue(json.has("order"));assertTrue(json.has("blockSizes"))
            assertEquals("LARGE",json.getString("size"));assertTrue(json.getBoolean("volumeRight"));assertTrue(json.getBoolean("fitScreen"))
        }
    }
    @Test fun emptyRetiredRecordsAreRemovedAndMalformedRecordsArePreserved() {
        assertNull(LayoutStorageCleanup.clean("""{"order":[],"size":"LARGE","fitScreen":false}""",canvas))
        assertNull(LayoutStorageCleanup.clean(null,canvas))
        assertEquals("broken",LayoutStorageCleanup.clean("broken",canvas))
        val raw="""{"order":[],"size":"LARGE"}"""
        assertEquals(raw,LayoutStorageCleanup.clean(raw,null))
    }
}
