package com.luma.remote.protocol

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HoldRepeaterTest {
    @Test fun cancelledIntermediatePressCannotSkipPendingRelease()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val allowRelease=CompletableDeferred<Unit>()
        try {
            val down=CompletableDeferred<Unit>();val releasing=CompletableDeferred<Unit>();val nextDown=CompletableDeferred<Unit>()
            val repeater=HoldRepeater(scope);val first=Any();val middle=Any()
            repeater.startNative(first,{true},{down.complete(Unit)},{releasing.complete(Unit);allowRelease.await()})
            withTimeout(2000) {down.await()};repeater.stop(first)
            withTimeout(2000) {releasing.await()}
            repeater.startNative(middle,{true},{fail("Cancelled middle press must never begin")},{})
            repeater.stop(middle)
            repeater.startNative(Any(),{true},{nextDown.complete(Unit)},{})
            delay(80)
            assertFalse("Cancelled middle press must preserve the UP barrier",nextDown.isCompleted)
            allowRelease.complete(Unit)
            withTimeout(2000) {nextDown.await()};repeater.stop()
        } finally {allowRelease.complete(Unit);scope.cancel()}
    }
    @Test fun tapOnlyOkNeverRepeatsEvenWhenHeld()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val count=java.util.concurrent.atomic.AtomicInteger()
            val repeater=HoldRepeater(scope,5,5)
            repeater.start(Any(),{true},repeat=false) {count.incrementAndGet()}
            delay(100);assertEquals(1,count.get());repeater.stop()
            assertTrue(Command.OK in pressCommands)
            assertFalse(Command.OK in repeatingCommands)
            assertTrue(Command.OK in AndroidTvProtocol.heldCommands)
            assertFalse(Command.POWER in pressCommands)
        } finally {scope.cancel()}
    }
    @Test fun releaseThenNewPressWaitsForPendingNativeUp()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val allowRelease=CompletableDeferred<Unit>()
        try {
            val down=CompletableDeferred<Unit>();val releasing=CompletableDeferred<Unit>();val nextDown=CompletableDeferred<Unit>()
            val repeater=HoldRepeater(scope);val old=Any()
            repeater.startNative(old,{true},{down.complete(Unit)},{releasing.complete(Unit);allowRelease.await()})
            withTimeout(2000) {down.await()};repeater.stop(old)
            withTimeout(2000) {releasing.await()}
            repeater.startNative(Any(),{true},{nextDown.complete(Unit)},{})
            delay(80)
            assertFalse("New DOWN must wait for previous UP even after explicit release",nextDown.isCompleted)
            allowRelease.complete(Unit)
            withTimeout(2000) {nextDown.await()};repeater.stop()
        } finally {allowRelease.complete(Unit);scope.cancel()}
    }
    @Test fun nativeHoldHasOneDownAndOneUpWithoutShortRepeat()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val events=java.util.concurrent.CopyOnWriteArrayList<String>()
            val down=CompletableDeferred<Unit>();val up=CompletableDeferred<Unit>()
            val repeater=HoldRepeater(scope);val token=Any()
            repeater.startNative(token,{true},{events.add("down");down.complete(Unit)},{events.add("up");up.complete(Unit)})
            withTimeout(2000) { down.await() };delay(150);assertEquals(listOf("down"),events.toList())
            repeater.stop(token);withTimeout(2000) { up.await() };assertEquals(listOf("down","up"),events.toList())
        } finally {scope.cancel()}
    }
    @Test fun nativeReplacementWaitsForOldReleaseAndIgnoresStaleOwner()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val events=java.util.concurrent.CopyOnWriteArrayList<String>()
            val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>()
            val repeater=HoldRepeater(scope);val old=Any();val next=Any()
            repeater.startNative(old,{true},{events.add("left down");first.complete(Unit)},{delay(40);events.add("left up")})
            withTimeout(2000) {first.await()}
            repeater.startNative(next,{true},{events.add("right down");second.complete(Unit)},{events.add("right up");released.complete(Unit)})
            withTimeout(2000) {second.await()};repeater.stop(old);delay(30)
            assertEquals(listOf("left down","left up","right down"),events.toList())
            repeater.stop(next);withTimeout(2000) {released.await()}
            assertEquals(listOf("left down","left up","right down","right up"),events.toList())
        } finally {scope.cancel()}
    }

    @Test fun nativeStartFailureStillAttemptsRelease()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val released=CompletableDeferred<Unit>();val failed=CompletableDeferred<Unit>()
            HoldRepeater(scope).startNative(Any(),{true},{throw IllegalStateException("test transport failure")},{released.complete(Unit)},{failed.complete(Unit)})
            withTimeout(2000) {failed.await();released.await()}
        } finally {scope.cancel()}
    }
    @Test fun repeatsThenStopsOnRelease()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repeat=HoldRepeater(scope,30,15); val token=Any()
            val count=java.util.concurrent.atomic.AtomicInteger()
            val repeated=CompletableDeferred<Unit>()
            repeat.start(token,{true}) { if(count.incrementAndGet()==3)repeated.complete(Unit) }
            withTimeout(2000) { repeated.await() };repeat.stop(token)
            delay(40);val stopped=count.get();delay(70);assertEquals(stopped,count.get())
        } finally { scope.cancel() }
    }
    @Test fun slowSendDoesNotQueueAndOldReleaseCannotStopNewHold()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repeat=HoldRepeater(scope,20,10);val old=Any();val current=Any()
            val started=CompletableDeferred<Unit>();val count=java.util.concurrent.atomic.AtomicInteger()
            repeat.start(old,{true}) { awaitCancellation() }
            repeat.start(current,{true}) { count.incrementAndGet();started.complete(Unit);awaitCancellation() }
            withTimeout(2000) { started.await() };repeat.stop(old)
            delay(80);assertEquals(1,count.get());repeat.stop(current)
        } finally { scope.cancel() }
    }
    @Test fun lossOfReadinessStopsFurtherCommands()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repeat=HoldRepeater(scope,30,15);val ready=java.util.concurrent.atomic.AtomicBoolean(true)
            val first=CompletableDeferred<Unit>();val count=java.util.concurrent.atomic.AtomicInteger()
            repeat.start(Any(),{ready.get()}) { count.incrementAndGet();ready.set(false);first.complete(Unit) }
            withTimeout(2000) { first.await() };delay(100);assertEquals(1,count.get())
        } finally { scope.cancel() }
    }
    @Test fun navigationVolumeChannelsAndSeekRepeat() {
        assertEquals(11,repeatingCommands.size)
        assertTrue(Command.DELETE in repeatingCommands)
        assertTrue(Command.REWIND in repeatingCommands);assertTrue(Command.FORWARD in repeatingCommands)
        assertFalse(Command.PLAY_PAUSE in repeatingCommands)
        assertFalse(Command.OK in repeatingCommands);assertFalse(Command.HOME in repeatingCommands)
        assertFalse(Command.BACK in repeatingCommands);assertFalse(Command.POWER in repeatingCommands)
    }
    @Test fun seekCanUseSlowerRepeatInterval()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repeat=HoldRepeater(scope,20,5);val third=CompletableDeferred<Unit>();var count=0
            val start=System.nanoTime()
            repeat.start(Any(),{true},repeatInterval=60) { count++;if(count==3)third.complete(Unit) }
            withTimeout(2000) { third.await() };repeat.stop()
            assertTrue("Seek interval override was ignored",(System.nanoTime()-start)/1_000_000>=70)
        } finally { scope.cancel() }
    }

}
