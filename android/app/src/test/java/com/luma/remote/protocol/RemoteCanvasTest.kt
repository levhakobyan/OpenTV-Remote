package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class RemoteCanvasTest {
    @Test fun fittingCapsSparseLayoutsAndKeepsDenseLayoutsScrollable() {
        assertEquals(56.0,CanvasFit.rowHeight(56.0,800.0,1,4.0),0.001)
        assertEquals(72.0,CanvasFit.rowHeight(140.0,1000.0,2,4.0),0.001)
        assertEquals(49.6,CanvasFit.rowHeight(60.0,800.0,15,4.0),0.001)
        assertEquals(48.0,CanvasFit.rowHeight(60.0,300.0,15,4.0),0.001)
    }
    @Test fun runtimeFittingDoesNotChangeCustomSpansOrPositions() {
        val custom=RemoteCanvas(listOf(key(1,2,3),key(4,5,2)),rows=6)
        val saved=custom.encode()
        CanvasFit.rowHeight(56.0,800.0,custom.contentRows,4.0)
        CanvasFit.rowHeight(110.0,350.0,custom.contentRows,4.0)
        assertEquals(saved,custom.encode())
        assertEquals(custom,RemoteCanvas.decode(saved))
    }

    private val caps=AndroidTvProtocol.capabilities(622)
    private fun key(x:Int,y:Int,w:Int=1)=CanvasItem(kind=CanvasKind.KEY,x=x,y=y,w=w,command=Command.MENU)
    @Test fun allPresetsProduceBoundedNonOverlappingLayouts() {
        LayoutPreset.entries.forEach { p ->
            val value=RemoteCanvas.preset(p,caps,AppShortcut.defaults)
            assertTrue(p.name,value.valid());assertEquals(value,RemoteCanvas.decode(value.encode()))
        }
        assertTrue(RemoteCanvas.preset(LayoutPreset.MINIMAL,Capabilities(setOf(Command.BACK)),emptyList()).valid())
    }
    @Test fun movesToAnyFreeCellAndSwapsEqualSizedButtons() {
        val a=key(0,0);val b=key(2,0);val value=RemoteCanvas(listOf(a,b))
        val moved=value.place(a.id,4,7)!!;assertEquals(7,moved.items.first().y)
        val swap=value.place(a.id,2,0)!!;assertEquals(2,swap.items[0].x);assertEquals(0,swap.items[1].x)
        assertNull(value.place(a.id,-1,0));assertNull(value.place(a.id,6,0))
    }
    @Test fun resizingRejectsOverlapOutOfBoundsAndTooSmallWidgets() {
        val a=key(0,0);val b=key(2,0);val value=RemoteCanvas(listOf(a,b))
        assertNotNull(value.place(a.id,0,0,w=2));assertNull(value.place(a.id,0,0,w=3))
        val pad=CanvasItem(kind=CanvasKind.DPAD,x=0,y=1)
        assertNull(RemoteCanvas(listOf(pad)).place(pad.id,0,1,w=3))
        assertEquals(11,value.place(a.id,0,0,h=11)!!.rows)
        assertNull(value.place(a.id,0,0,h=25))
    }
    @Test fun pickerAddsEverySupportedCommandIncludingMenuWithoutOverwriting() {
        var value=RemoteCanvas(emptyList(),rows=12)
        caps.commands.forEach { value=value.add(CanvasKind.KEY,it)!! }
        assertTrue(value.valid());assertTrue(value.items.any { it.command==Command.MENU })
        val filled=RemoteCanvas((0 until 6).flatMap { x -> (0 until 6).map { y -> key(x,y) } },rows=6)
        assertEquals(7,filled.add(CanvasKind.KEY,Command.INFO)!!.rows)
    }
    @Test fun corruptAndOverlappingDataCannotCreateAmbiguousControls() {
        val a=key(0,0);val invalid=RemoteCanvas(listOf(a,a.copy(id="other")))
        assertFalse(invalid.valid());assertNull(RemoteCanvas.decode("broken"));assertNull(RemoteCanvas.decode(null))
        val valid=RemoteCanvas(listOf(a)).encode();assertNull(RemoteCanvas.decode(valid.replace("\"w\":1","\"w\":7")))
    }
    @Test fun removedAppsAndUnsupportedCommandsAreNotWorkingControls() {
        val app=CanvasItem(kind=CanvasKind.APP,x=0,y=0,app=AppShortcut.defaults.first().url)
        assertTrue(app.supported(caps,AppShortcut.defaults));assertFalse(app.supported(caps,emptyList()))
        assertFalse(key(0,0).supported(Capabilities(setOf(Command.HOME)),emptyList()))
    }
    @Test fun emptyRowRemovalPreservesGroupsAndOnlyShiftsRowsBelowGaps() {
        val top=CanvasItem(kind=CanvasKind.KEY,x=0,y=1,command=Command.MENU)
        val rail=CanvasItem(kind=CanvasKind.CHANNELS,x=5,y=4)
        val lower=CanvasItem(kind=CanvasKind.KEY,x=2,y=8,command=Command.HOME)
        val value=RemoteCanvas(listOf(top,rail,lower),rows=12)
        val cleaned=value.removeEmptyRows()
        assertTrue(cleaned.valid());assertEquals(4,cleaned.rows)
        assertEquals(listOf(0,1,3),cleaned.items.map { it.y })
        assertEquals(value.items.map { it.copy(y=0) },cleaned.items.map { it.copy(y=0) })
        assertEquals(cleaned,cleaned.removeEmptyRows())
        assertEquals(0,cleaned.emptyRowCount)
        assertTrue(RemoteCanvas(emptyList()).removeEmptyRows().valid())
        val removed=cleaned.copy(items=cleaned.items.filter { it.id!=rail.id }).removeEmptyRows()
        assertEquals(listOf(0,1),removed.items.map { it.y });assertEquals(2,removed.rows)
    }
    @Test fun contextualJoinOnlyChangesTheSelectedAdjacentGroup() {
        val a=CanvasItem(kind=CanvasKind.KEY,x=0,y=0,command=Command.CHANNEL_UP)
        val b=CanvasItem(kind=CanvasKind.KEY,x=0,y=1,command=Command.CHANNEL_DOWN)
        val c=CanvasItem(kind=CanvasKind.KEY,x=5,y=0,command=Command.VOLUME_UP)
        val d=CanvasItem(kind=CanvasKind.KEY,x=5,y=1,command=Command.VOLUME_DOWN)
        val value=RemoteCanvas(listOf(a,b,c,d)).compacted()
        val joined=value.joinMatchingButtons(b.id)
        assertEquals(CanvasKind.CHANNELS,joined.items.first().kind)
        assertEquals(listOf(c,d),joined.items.takeLast(2))
        assertEquals(value,value.joinMatchingButtons("missing"))
        val gap=value.copy(items=listOf(a,b.copy(y=3),c,d),rows=4)
        assertEquals(gap,gap.joinMatchingButtons(b.id))
        val split=joined.split(a.id)!!
        assertEquals(CanvasKind.CHANNELS,split.joinMatchingButtons(split.items[1].id).items.first().kind)
    }
    @Test fun matchingKeysJoinWithoutMovingNeighboursAndCanSplit() {
        val legacy=RemoteCanvas(listOf(
            CanvasItem(kind=CanvasKind.KEY,x=0,y=1,command=Command.VOLUME_UP),
            CanvasItem(kind=CanvasKind.KEY,x=0,y=2,command=Command.VOLUME_DOWN),
            CanvasItem(kind=CanvasKind.KEY,x=0,y=3,command=Command.MUTE),
            CanvasItem(kind=CanvasKind.KEY,x=2,y=5,w=2,command=Command.BACK),
            CanvasItem(kind=CanvasKind.KEY,x=4,y=5,w=2,command=Command.HOME)
        ))
        val joined=legacy.joinMatchingButtons()
        assertEquals(3,joined.items.size)
        assertEquals(CanvasKind.VOLUME,joined.items[0].kind)
        assertEquals(legacy.items[0].id,joined.items[0].id)
        assertEquals(legacy.items[2],joined.items[1])
        assertEquals(CanvasKind.NAVIGATION,joined.items[2].kind)
        assertEquals(joined,RemoteCanvas.decode(joined.encode()))
        val split=joined.split(joined.items[0].id)!!
        assertEquals(listOf(Command.VOLUME_UP,Command.VOLUME_DOWN,Command.MUTE),split.items.take(3).map { it.command })
        assertEquals(joined,joined.joinMatchingButtons())
    }
    @Test fun groupOperationsRespectGapsPartialCapabilitiesAndResizeLimits() {
        val gap=RemoteCanvas(listOf(CanvasItem(kind=CanvasKind.KEY,x=0,y=0,command=Command.CHANNEL_UP),CanvasItem(kind=CanvasKind.KEY,x=0,y=2,command=Command.CHANNEL_DOWN)))
        assertEquals(gap,gap.joinMatchingButtons())
        val rocker=CanvasItem(kind=CanvasKind.CHANNELS,x=0,y=0)
        assertFalse(rocker.supported(Capabilities(setOf(Command.CHANNEL_UP)),emptyList()))
        assertTrue(rocker.supported(caps,emptyList()))
        assertNull(RemoteCanvas(listOf(rocker)).place(rocker.id,0,0,h=1))
        assertFalse(rocker.copy(w=Int.MAX_VALUE).valid(8))
        assertNull(RemoteCanvas(listOf(rocker)).place(rocker.id,6,0))
    }
    @Test fun presetsUseJoinedControlsAndLegacyCanvasStillDecodesUnchanged() {
        val preset=RemoteCanvas.preset(LayoutPreset.CLASSIC,caps,AppShortcut.defaults)
        assertTrue(setOf(CanvasKind.VOLUME,CanvasKind.CHANNELS,CanvasKind.PLAYBACK,CanvasKind.NAVIGATION).all { kind -> preset.items.any { it.kind==kind } })
        val old=RemoteCanvas(listOf(key(0,0),key(1,0)))
        assertEquals(old,RemoteCanvas.decode(old.encode()))
        val playback=CanvasItem(kind=CanvasKind.PLAYBACK,x=0,y=0,w=6)
        val split=RemoteCanvas(listOf(playback)).split(playback.id)!!
        assertEquals(listOf(Command.REWIND,Command.PLAY_PAUSE,Command.FORWARD),split.items.map { it.command })
        assertEquals(listOf(0,2,4),split.items.map { it.x })
        assertEquals(CanvasKind.PLAYBACK,split.joinMatchingButtons().items.single().kind)
    }
    @Test fun addingControlsGrowsOnlyAsNeededAndKeepsExistingPositions() {
        val row=RemoteCanvas((0 until 6).map { key(it,0) },rows=1)
        val added=row.add(CanvasKind.CHANNELS)!!
        assertEquals(3,added.rows)
        assertEquals(row.items,added.items.dropLast(1))
        assertEquals(0,added.items.last().x);assertEquals(1,added.items.last().y)
        assertEquals(added,RemoteCanvas.decode(added.encode()))
    }
    @Test fun movingAndResizingGrowPastOldExtentThenShrinkBack() {
        val rail=CanvasItem(kind=CanvasKind.CHANNELS,x=0,y=0)
        val short=RemoteCanvas(listOf(rail),rows=2)
        val moved=short.place(rail.id,3,12)!!
        assertEquals(14,moved.rows)
        val enlarged=moved.place(rail.id,3,12,h=5)!!
        assertEquals(17,enlarged.rows)
        val returned=enlarged.place(rail.id,0,0,h=2)!!
        assertEquals(2,returned.rows)
        assertNull(short.place(rail.id,0,23))
        assertNotNull(short.place(rail.id,0,22))
    }
    @Test fun trimmingLegacyAndDeletedTrailingRowsPreservesIntentionalSpacing() {
        val top=key(1,2);val bottom=key(4,9)
        val legacy=RemoteCanvas(listOf(top,bottom),rows=24)
        val trimmed=RemoteCanvas.decode(legacy.encode())!!.compacted()
        assertEquals(10,trimmed.rows);assertEquals(legacy.items,trimmed.items)
        val removed=trimmed.copy(items=listOf(top)).compacted()
        assertEquals(3,removed.rows);assertEquals(top,removed.items.single())
        assertEquals(1,removed.copy(items=emptyList()).compacted().rows)
        assertFalse(RemoteCanvas(emptyList(),rows=0).valid())
    }
    @Test fun automaticGrowthStillHonoursItemAndGridLimits() {
        val limit=RemoteCanvas((0 until 8).flatMap { y -> (0 until 6).map { x -> key(x,y) } },rows=8)
        assertNull(limit.add(CanvasKind.KEY,Command.MENU))
        val full=RemoteCanvas((0 until 24).map { y -> key(0,y,6) },rows=24)
        assertNull(full.add(CanvasKind.KEY,Command.MENU))
        assertNull(full.place(full.items.last().id,0,24))
        assertNull(RemoteCanvas(emptyList(),rows=25).takeIf { it.valid() })
    }
    @Test fun alwaysFitKeepsCustomDeviceWidgetsButNoPresetAddsThem() {
        val manual=CanvasItem(kind=CanvasKind.DEVICE,x=2,y=3,w=3)
        val old=RemoteCanvas(listOf(manual),rows=20,fit=false)
        val restored=RemoteCanvas.decode(old.encode())!!.compacted()
        assertTrue(restored.fit);assertEquals(4,restored.rows)
        assertEquals(listOf(manual),restored.items)
        LayoutPreset.entries.forEach { preset ->
            assertFalse(RemoteCanvas.preset(preset,caps,AppShortcut.defaults).items.any { it.kind==CanvasKind.DEVICE })
        }
    }
}
