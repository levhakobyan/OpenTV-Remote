package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class MotionFilterTest {
    @Test fun stationaryJitterDoesNotMoveOrSendKeys() {
        val f=MotionFilter(false)
        repeat(100) { assertNull(f.sample(1_000_000_000L+it*20_000_000L,.01f,-.01f,0)) }
    }
    @Test fun movementProducesArrowsWithBoundedRateAndNoCursorPretence() {
        val f=MotionFilter(false);val steps=(0..100).mapNotNull { f.sample(1_000_000_000L+it*20_000_000L,0f,-2f,0) }
        assertTrue(steps.isNotEmpty());assertTrue(steps.size<=12)
        assertTrue(steps.all { it.key==Command.RIGHT && it.dx==0f && it.dy==0f })
    }
    @Test fun pointerUsesSmallRelativeDeltasAndRespectsSampleRate() {
        val f=MotionFilter(true);val steps=(0..50).mapNotNull { f.sample(1_000_000_000L+it*20_000_000L,2f,-2f,0) }
        assertTrue(steps.size in 1..26);assertTrue(steps.all { it.key==null && it.dx in -30f..30f && it.dy in -30f..30f })
    }
    @Test fun rotationIsRemappedAndNewHoldStartsFresh() {
        val f=MotionFilter(false);val steps=(0..30).mapNotNull { f.sample(1_000_000_000L+it*20_000_000L,2f,0f,1) }
        assertTrue(steps.all { it.key==Command.RIGHT });assertTrue(steps.isNotEmpty())
        assertNull(MotionFilter(false).sample(99_000_000_000L,2f,2f,0))
        assertNull(f.sample(99_000_000_000L,2f,2f,0))
    }
    @Test fun invalidSamplesAreDropped() { assertNull(MotionFilter(true).sample(1,Float.NaN,0f,0)) }
    @Test fun gravityProjectionSupportsFlatAndUprightPhonePostures() {
        for(flat in listOf(false,true)) {
            val f=MotionFilter(false)
            f.gravity(0f,if(flat)0f else 9.81f,if(flat)9.81f else 0f)
            val steps=(0..30).mapNotNull { f.sample(1_000_000_000L+it*20_000_000L,0f,if(flat)0f else -2f,0,if(flat)-2f else 0f) }
            assertTrue(steps.isNotEmpty());assertTrue(steps.all { it.key==Command.RIGHT })
        }
    }

}
