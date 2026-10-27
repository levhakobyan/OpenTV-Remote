package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class RemoteLayoutTest {
    @Test fun presetsIncludeNumbersTouchAndMinimalWithPreservedTvMapping() {
        val base=RemoteLayout(tvAction=TvAction.INPUT,volumeRight=true,size=ControlSize.LARGE)
        val channels=base.applyPreset(LayoutPreset.CHANNELS)
        assertEquals(RemoteBlock.NUMBERS,channels.order.first());assertFalse(RemoteBlock.NUMBERS in channels.hidden)
        assertTrue(base.applyPreset(LayoutPreset.TOUCH).gestures)
        assertTrue(RemoteBlock.DEVICE in base.applyPreset(LayoutPreset.MINIMAL).hidden)
        assertEquals(TvAction.INPUT,channels.tvAction);assertTrue(channels.volumeRight);assertEquals(ControlSize.LARGE,channels.size)
    }
    @Test fun customizeRoundTripAndOldDataDefaults() {
        val value=RemoteLayout().applyPreset(LayoutPreset.CHANNELS).move(RemoteBlock.DEVICE,-5).copy(size=ControlSize.COMFORT,volumeRight=true,tvConfigured=true,fitScreen=false,tvAction=TvAction.APP,tvApp=ShortcutCatalog.apps.first().url,hidden=setOf(RemoteBlock.PLAYBACK),blockSizes=mapOf(RemoteBlock.NUMBERS to ControlSize.LARGE))
        assertEquals(value.copy(fitScreen=true),RemoteLayout.decode(value.encode()))
        assertEquals(RemoteLayout(),RemoteLayout.decode(null));assertEquals(RemoteLayout(),RemoteLayout.decode("broken"))
    }
    @Test fun legacyFitFalseIsUpgradedWithoutLosingManualChoices() {
        val layout=RemoteLayout(size=ControlSize.LARGE,volumeRight=true,tvAction=TvAction.INPUT,tvConfigured=true)
        val raw=layout.encode().replace("\"fitScreen\":true","\"fitScreen\":false")
        val restored=RemoteLayout.decode(raw)
        assertTrue(restored.fitScreen)
        assertEquals(layout,restored)
        assertTrue(RemoteBlock.DEVICE in RemoteLayout().hidden)
    }
    @Test fun malformedOrderCannotRemoveCoreControlsOrDuplicateBlocks() {
        val value=RemoteLayout.decode("""{"order":["NUMBERS","NUMBERS","INVALID"],"hidden":["CONTROL","NAVIGATION","DEVICE"],"tvApp":"javascript:alert(1)","size":"invalid"}""")
        assertEquals(RemoteBlock.NUMBERS,value.order.first())
        assertEquals(RemoteBlock.entries.size,value.order.size);assertEquals(RemoteBlock.entries.toSet(),value.order.toSet())
        assertEquals(setOf(RemoteBlock.DEVICE),value.hidden);assertNull(value.tvApp)
        assertEquals(ControlSize.COMPACT,value.size)
    }
    @Test fun reorderClampsAndKeepsEveryBlock() {
        val base=RemoteLayout();assertEquals(base,base.move(base.order.first(),-1))
        val moved=base.move(RemoteBlock.DEVICE,100)
        assertEquals(RemoteBlock.DEVICE,moved.order.last());assertEquals(base.order.toSet(),moved.order.toSet())
    }
    @Test fun catalogContainsOnlyValidUniqueOfficialLinks() {
        assertTrue(ShortcutCatalog.apps.size>=4)
        assertTrue(ShortcutCatalog.apps.all { it.isValid() })
        assertEquals(ShortcutCatalog.apps.size,ShortcutCatalog.apps.map { it.url }.toSet().size)
        assertTrue(ShortcutCatalog.apps.containsAll(AppShortcut.defaults))
    }
    @Test fun gesturesMapToExistingDirectionalCommandsAndRequireNavigation() {
        assertEquals(Command.RIGHT,GestureNavigation.direction(60f,5f));assertEquals(Command.LEFT,GestureNavigation.direction(-60f,5f))
        assertEquals(Command.UP,GestureNavigation.direction(2f,-60f));assertEquals(Command.DOWN,GestureNavigation.direction(2f,60f))
        assertTrue(GestureNavigation.supported(AndroidTvProtocol.capabilities(2)))
        assertFalse(GestureNavigation.supported(Capabilities(setOf(Command.OK,Command.HOME))))
    }
}
