package com.luma.remote.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.*
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class SamsungProviderTest {
    private class Store:SamsungSecrets {
        val data=ConcurrentHashMap<String,String>()
        override fun read(key:String)=data[key]
        override fun write(key:String,value:String) { data[key]=value }
        override fun remove(key:String) { data.remove(key) }
    }
    private data class Tls(val cert:X509Certificate,val factory:SSLSocketFactory)
    private fun tls():Tls {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name=X500Name("CN=Samsung fixture")
        val signed=JcaX509v3CertificateBuilder(name,BigInteger.valueOf(System.nanoTime()),Date(System.currentTimeMillis()-1000),Date(System.currentTimeMillis()+86400000),name,pair.public).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert=JcaX509CertificateConverter().getCertificate(signed)
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setKeyEntry("fixture",pair.private,"test".toCharArray(),arrayOf(cert)) }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"test".toCharArray()) }
        return Tls(cert,SSLContext.getInstance("TLS").apply { init(km.keyManagers,null,null) }.socketFactory)
    }
    private val tv=TvDevice("fixture","Samsung fixture","127.0.0.1",Platform.SAMSUNG)
    private fun provider(store:Store,secure:MockWebServer,legacy:MockWebServer,timeout:Long=2000)=SamsungProvider(store,securePort=secure.port,legacyPort=legacy.port,hostAllowed={it=="127.0.0.1"},approvalTimeout=timeout)
    private suspend fun await(p:SamsungProvider,phase:Phase)=withTimeout(4000) { p.state.first { it.phase==phase } }
    private fun metadata(server:MockWebServer) { server.enqueue(MockResponse().setBody(samsungMetadata())) }
    private fun upgrade(server:MockWebServer,token:String?="fixture-token",event:String="ms.channel.connect",messages:LinkedBlockingQueue<String>?=null,peer:AtomicReference<WebSocket>?=null) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onOpen(ws:WebSocket,response:Response) { peer?.set(ws);if(event.isNotEmpty())ws.send("""{"event":"$event","data":${if(token==null)"{}" else "{\"token\":\"$token\"}"}}""") }
            override fun onMessage(ws:WebSocket,text:String) { messages?.offer(text) }
        }))
    }
    private fun pinned(store:Store,tls:Tls) { store.write("samsung-pin:fixture",SamsungTrust.fingerprint(tls.cert)) }
    @Test fun firstTrustAbortsBeforeHttpThenApprovalAndEncryptedStoreContractSupportReconnect()=runBlocking {
        val tls=tls();MockWebServer().use { secure -> MockWebServer().use { legacy ->
            secure.useHttps(tls.factory,false);secure.start();legacy.start();val store=Store();val p=provider(store,secure,legacy);val messages=LinkedBlockingQueue<String>()
            metadata(secure);upgrade(secure,messages=messages)
            try {
                p.connect(tv);val fp=p.state.value.identity!!.fingerprint;assertEquals(SamsungTrust.fingerprint(tls.cert),fp);assertEquals(0,secure.requestCount);assertTrue(store.data.isEmpty())
                p.trustIdentity("wrong");assertTrue(store.data.isEmpty());p.trustIdentity(fp);p.connect(tv);await(p,Phase.READY)
                assertEquals("fixture-token",store.read("samsung-token:fixture"));secure.takeRequest();val ws=secure.takeRequest();assertNull(ws.requestUrl!!.queryParameter("token"))
                p.command(Command.VOLUME_UP);assertTrue(messages.poll(2,TimeUnit.SECONDS).contains("KEY_VOLUP"))
                p.text("Բարեւ");assertTrue(messages.poll(2,TimeUnit.SECONDS).contains("textReceived"));assertTrue(messages.poll(2,TimeUnit.SECONDS).contains("SendInputString"))
                p.disconnect();metadata(secure);upgrade(secure);p.connect(tv);await(p,Phase.READY);secure.takeRequest();assertEquals("fixture-token",secure.takeRequest().requestUrl!!.queryParameter("token"))
            } finally { p.disconnect() }
        } }
    }
    @Test fun rejectionTimeoutAndLateApprovalDoNotEnableControlsOrSaveTokens()=runBlocking {
        val tls=tls();MockWebServer().use { secure -> MockWebServer().use { legacy ->
            secure.useHttps(tls.factory,false);secure.start();legacy.start();val store=Store();pinned(store,tls);val p=provider(store,secure,legacy,150)
            try {
                metadata(secure);upgrade(secure,event="ms.channel.unauthorized");p.connect(tv);await(p,Phase.ERROR);assertNull(store.read("samsung-token:fixture"));assertTrue(p.state.value.capabilities.commands.isEmpty());assertFalse(p.state.value.retryable)
                metadata(secure);upgrade(secure,event="");p.connect(tv);await(p,Phase.ERROR);assertTrue(p.state.value.message.contains("timed out"))
                val peer=AtomicReference<WebSocket>();metadata(secure);upgrade(secure,event="",peer=peer);p.connect(tv);await(p,Phase.PAIRING);p.disconnect();peer.get()?.send("""{"event":"ms.channel.connect","data":{"token":"late"}}""");delay(100)
                assertEquals(Phase.DISCONNECTED,p.state.value.phase);assertNull(store.read("samsung-token:fixture"))
            } finally { p.disconnect() }
        } }
    }
    @Test fun changedCertificateFailsClosedWithoutSendingTokenOrOfferingDowngrade()=runBlocking {
        val original=tls();val changed=tls();MockWebServer().use { secure -> MockWebServer().use { legacy ->
            secure.useHttps(changed.factory,false);secure.start();legacy.start();val store=Store();pinned(store,original);store.write("samsung-token:fixture","private-fixture-token");val p=provider(store,secure,legacy)
            try {
                p.connect(tv);assertEquals(Phase.ERROR,p.state.value.phase);assertTrue(p.state.value.message.contains("identity changed"));assertNull(p.state.value.identity);assertFalse(p.state.value.legacyAvailable)
                assertEquals(0,secure.requestCount);assertEquals(0,legacy.requestCount);assertFalse(p.state.value.message.contains("private-fixture-token"));assertFalse(p.state.value.retryable);assertTrue(p.state.value.canResetPairing)
                store.write("unrelated","keep");p.resetPairing();assertNull(store.read("samsung-pin:fixture"));assertNull(store.read("samsung-token:fixture"));assertEquals("keep",store.read("unrelated"))
            } finally { p.disconnect() }
        } }
    }
    @Test fun staleTokenIsRemovedAndOnlyOneNewTvApprovalIsRequested()=runBlocking {
        val tls=tls();MockWebServer().use { secure -> MockWebServer().use { legacy ->
            secure.useHttps(tls.factory,false);secure.start();legacy.start();val store=Store();pinned(store,tls);store.write("samsung-token:fixture","expired");val p=provider(store,secure,legacy)
            try {
                metadata(secure);upgrade(secure,event="ms.channel.unauthorized");upgrade(secure,token="replacement");p.connect(tv);await(p,Phase.READY)
                secure.takeRequest();assertEquals("expired",secure.takeRequest().requestUrl!!.queryParameter("token"));assertNull(secure.takeRequest().requestUrl!!.queryParameter("token"));assertEquals("replacement",store.read("samsung-token:fixture"))
            } finally { p.disconnect() }
        } }
    }
    @Test fun legacyRequiresOptInAndNeverTransmitsStoredSecureToken()=runBlocking {
        val tls=tls();MockWebServer().use { secure -> MockWebServer().use { legacy ->
            secure.useHttps(tls.factory,false);secure.start();legacy.start();val port=secure.port;secure.shutdown();val store=Store();pinned(store,tls);store.write("samsung-token:fixture","never-plaintext")
            val p=SamsungProvider(store,securePort=port,legacyPort=legacy.port,hostAllowed={it=="127.0.0.1"})
            try {
                metadata(legacy);p.connect(tv);assertEquals(Phase.ERROR,p.state.value.phase);assertTrue(p.state.value.legacyAvailable);assertNull(store.read("samsung-mode:fixture"));assertEquals("/api/v2/",legacy.takeRequest().path)
                p.useLegacyConnection();metadata(legacy);upgrade(legacy);p.connect(tv);await(p,Phase.READY);legacy.takeRequest();assertNull(legacy.takeRequest().requestUrl!!.queryParameter("token"));assertEquals("never-plaintext",store.read("samsung-token:fixture"));assertTrue(p.state.value.message.contains("unencrypted"));assertTrue(p.state.value.legacyConnection);p.useSecureConnection();assertNull(store.read("samsung-mode:fixture"));assertEquals("never-plaintext",store.read("samsung-token:fixture"));assertNotNull(store.read("samsung-pin:fixture"))
            } finally { p.disconnect() }
        } }
    }
    @Test fun publicHostsAreRejectedBeforeNetworkAccess()=runBlocking {
        val p=SamsungProvider(Store())
        try { try { p.connect(tv.copy(host="8.8.8.8"));fail("Public address accepted") } catch(_:IllegalArgumentException) { } }
        finally { p.disconnect() }
    }
}
