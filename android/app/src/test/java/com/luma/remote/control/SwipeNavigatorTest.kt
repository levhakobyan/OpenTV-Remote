package com.luma.remote.control

import com.luma.remote.protocol.Command
import org.junit.Assert.*
import org.junit.Test

class SwipeNavigatorTest {
    private fun navigator()=SwipeNavigator(8f,18f,32f)
    @Test fun smallIntentionalSwipesRespondEarlierThanRepeatSteps() {
        val n=navigator()
        assertNull(n.move(7f,0f,0));assertFalse(n.dragged)
        assertEquals(Command.RIGHT,n.move(12f,0f,30));assertTrue(n.dragged)
        assertNull(n.move(20f,0f,150))
        assertEquals(Command.RIGHT,n.move(12f,0f,180))
    }
    @Test fun rapidLargeMovesAreBoundedAndDoNotQueueCommands() {
        val n=navigator()
        assertEquals(Command.LEFT,n.move(-1000f,0f,0))
        assertNull(n.move(-1000f,0f,20))
        assertNull(n.move(-1000f,0f,70))
        assertEquals(Command.LEFT,n.move(-1f,0f,100))
        assertNull(n.move(0f,0f,300))
    }
    @Test fun diagonalDirectionAndReversalFollowFingerInsteadOfLockingWholeSwipe() {
        val n=navigator()
        assertEquals(Command.UP,n.move(3f,-20f,0))
        assertEquals(Command.DOWN,n.move(0f,40f,100))
        assertEquals(Command.LEFT,n.move(-40f,2f,200))
    }
    @Test fun invalidMotionAndFreshContactCannotReplayPreviousSwipe() {
        val n=navigator()
        assertNull(n.move(Float.NaN,0f,0));assertFalse(n.dragged)
        assertEquals(Command.RIGHT,n.move(40f,0f,10))
        val next=navigator();assertFalse(next.dragged)
        assertNull(next.move(2f,1f,1000));assertFalse(next.dragged)
        assertNull(next.move(0f,Float.POSITIVE_INFINITY,1100))
    }
}
