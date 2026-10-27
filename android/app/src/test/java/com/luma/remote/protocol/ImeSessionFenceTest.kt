package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImeSessionFenceTest {
    @Test fun cancellationIsIndependentOfBlockedWriterAndRejectsQueuedOldWrites() {
        val fence=ImeSessionFence();val connection=fence.nextConnection();fence.noteSession(100)
        val writerLock=Any();val writerStarted=CountDownLatch(1);val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val writer=executor.submit { synchronized(writerLock) {writerStarted.countDown();release.await()} }
            assertTrue(writerStarted.await(1,TimeUnit.SECONDS))
            fence.invalidate(100)
            assertFalse(fence.allows(connection,100))
            release.countDown();writer.get(1,TimeUnit.SECONDS)
            synchronized(writerLock) {assertFalse("An edit queued before dismissal must not be sent",fence.allows(connection,100))}
            fence.noteSession(101);assertTrue(fence.allows(connection,101))
            fence.nextConnection();assertFalse(fence.allows(connection,101))
        } finally {release.countDown();executor.shutdownNow()}
    }
    @Test fun fieldChangeRejectsPreparedEditEvenWithoutUiDismiss() {
        val fence=ImeSessionFence();val connection=fence.nextConnection();fence.noteSession(100)
        assertTrue(fence.allows(connection,100));fence.noteSession(101)
        assertFalse(fence.allows(connection,100));assertTrue(fence.allows(connection,101))
    }
}
