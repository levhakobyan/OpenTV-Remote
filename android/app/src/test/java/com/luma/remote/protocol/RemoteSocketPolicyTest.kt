package com.luma.remote.protocol

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RemoteSocketPolicyTest {
    @Test fun handshakeStaysBoundedAndReadyReadSurvivesIdleThenReadsFrame() {
        ServerSocket(0).use { server -> Socket("127.0.0.1",server.localPort).use { client -> server.accept().use { peer ->
            RemoteSocketPolicy.handshake(client);assertEquals(10_000,client.soTimeout)
            // Reproduce the inherited timeout on a quiet connection before applying READY policy.
            client.soTimeout=40
            try { Wire.frame(client.getInputStream());fail("Expected idle timeout") } catch(_:SocketTimeoutException) { }
            RemoteSocketPolicy.ready(client);assertEquals(0,client.soTimeout);assertTrue(client.keepAlive)
            val executor=Executors.newSingleThreadExecutor()
            try {
                val read=executor.submit<ByteArray> { Wire.frame(client.getInputStream()) }
                Thread.sleep(100);assertFalse(read.isDone)
                Wire.write(peer.getOutputStream(),byteArrayOf(8,1))
                assertArrayEquals(byteArrayOf(8,1),read.get(2,TimeUnit.SECONDS))
                val blocked=executor.submit<Boolean> { try { Wire.frame(client.getInputStream());false } catch(_:Exception) { true } }
                client.close();assertTrue(blocked.get(2,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        } } }
    }
}
