package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class PushResizeTest {
    private fun key(id:String,x:Int,y:Int,w:Int=2,h:Int=1)=CanvasItem(id=id,kind=CanvasKind.KEY,x=x,y=y,w=w,h=h,command=Command.MENU)
    @Test fun growthCascadesDownAndPreservesUnrelatedControlsAndGroupShape() {
        val selected=key("selected",0,0)
        val below=key("below",0,1)
        val group=CanvasItem(id="group",kind=CanvasKind.CHANNELS,x=0,y=2)
        val side=key("side",4,1)
        val original=RemoteCanvas(listOf(group,side,selected,below),rows=4)
        val changed=original.resizePushingDown(selected.id,h=3)!!
        assertTrue(changed.valid());assertEquals(6,changed.rows)
        assertEquals(3,changed.items.single { it.id==below.id }.y)
        assertEquals(group.copy(y=4),changed.items.single { it.id==group.id })
        assertEquals(side,changed.items.single { it.id==side.id })
        assertEquals(original.items.map { it.id },changed.items.map { it.id })
        assertEquals(1,original.items.single { it.id==selected.id }.h)
    }
    @Test fun aPushedWideControlCannotJumpPastLowerControlsInOtherColumns() {
        val a=key("a",0,0,w=1)
        val wide=key("wide",0,1,w=6)
        val lowerSide=key("lower-side",4,3)
        val upperSide=key("upper-side",4,0)
        val value=RemoteCanvas(listOf(a,wide,lowerSide,upperSide),rows=4)
        val grown=value.resizePushingDown(a.id,h=5)!!
        assertEquals(5,grown.items.single { it.id==wide.id }.y)
        assertEquals(6,grown.items.single { it.id==lowerSide.id }.y)
        assertEquals(upperSide,grown.items.single { it.id==upperSide.id })
        assertTrue(grown.valid())
    }
    @Test fun widthGrowthCanMoveSameRowPeersButDoesNotReorderControlsAbove() {
        val a=key("a",0,1)
        val peer=key("peer",2,1)
        val value=RemoteCanvas(listOf(a,peer),rows=2)
        val grown=value.resizePushingDown(a.id,w=4)!!
        assertEquals(2,grown.items.single { it.id==peer.id }.y)
        val above=key("above",2,0,h=2)
        assertNull(RemoteCanvas(listOf(a,above),rows=2).resizePushingDown(a.id,w=4))
        assertEquals(peer,value.items[1])
    }
    @Test fun rejectedOverflowIsAtomicAndNormalMovementStillRejectsCollisions() {
        val a=key("a",0,0)
        val bottom=key("bottom",0,1,h=23)
        val value=RemoteCanvas(listOf(a,bottom),rows=24)
        assertNull(value.resizePushingDown(a.id,h=2))
        assertNull(value.resizePushingDown(a.id,w=7));assertNull(value.resizePushingDown(a.id,h=25))
        assertNull(value.resizePushingDown("missing",h=2))
        assertNull(value.place(a.id,0,1))
        assertEquals(1,value.items[0].h);assertEquals(1,value.items[1].y)
    }
    @Test fun shrinkingDoesNotPullOtherControlsUpAndUnobstructedResizeMatchesPlacement() {
        val a=key("a",0,0,h=3);val b=key("b",0,3)
        val value=RemoteCanvas(listOf(a,b),rows=4)
        val shrunk=value.resizePushingDown(a.id,h=1)!!
        assertEquals(b,shrunk.items[1]);assertEquals(2,shrunk.emptyRowCount)
        assertEquals(value.place(a.id,0,0,h=1),shrunk)
        val group=CanvasItem(kind=CanvasKind.VOLUME,x=4,y=0)
        assertNull(RemoteCanvas(listOf(group),rows=2).resizePushingDown(group.id,h=1))
    }
}
