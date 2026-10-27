package com.luma.remote.protocol

import com.luma.remote.R
import com.luma.remote.data.Vault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.*

class AndroidTvProvider(private val vault: Vault) : RemoteProvider {
    override val state = MutableStateFlow(RemoteState())
    private var socket: SSLSocket? = null
    private var pairingSocket: SSLSocket? = null
    @Volatile private var heldKey:Command?=null
    private var device: TvDevice? = null
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var reader: Job? = null
    companion object { private val keyboardIds=java.util.concurrent.atomic.AtomicLong() }
    private var features = 0
    private val ime=AndroidImeSession()
    private val imeLock=Any()
    private val imeFence=ImeSessionFence()
    @Volatile private var imeConnection=0L
    private var imeDeadline:Job?=null
    private val sendLock = Any()
    private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    private fun tls(tv: TvDevice, port: Int, pairing: Boolean): SSLSocket {
        val identity = vault.identity()
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(k: String?,i: Array<out Principal>?) = arrayOf("luma")
            override fun chooseClientAlias(k: Array<out String>?,i: Array<out Principal>?,s: java.net.Socket?) = "luma"
            override fun getServerAliases(k: String?,i: Array<out Principal>?) : Array<String>? = null
            override fun chooseServerAlias(k: String?,i: Array<out Principal>?,s: java.net.Socket?): String? = null
            override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate as X509Certificate)
            override fun getPrivateKey(alias: String?) = identity.privateKey
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(c: Array<out X509Certificate>?,a: String?) { throw java.security.cert.CertificateException("Client-side trust manager only") }
            override fun checkServerTrusted(c: Array<out X509Certificate>,a: String?) {
                // A code proves possession during pairing. All subsequent sessions pin the exact TV certificate.
                if (!pairing) check(vault.secret("pin:${tv.id}") == fingerprint(c.first())) { "TV identity changed. Forget and pair again." }
            }
        }
        val context = SSLContext.getInstance("TLS"); context.init(arrayOf(manager),arrayOf(trust),null)
        return (context.socketFactory.createSocket() as SSLSocket).apply {
            try { connect(InetSocketAddress(tv.host,port),6000); RemoteSocketPolicy.handshake(this); startHandshake() } catch(e: Exception) { close(); throw e }
        }
    }
    override suspend fun connect(device: TvDevice) = withContext(Dispatchers.IO) {
        disconnect(); this@AndroidTvProvider.device=device
        state.value=RemoteState(Phase.CONNECTING,"Connecting securely…",messageRes=R.string.status_connecting_securely)
        try { if (vault.secret("pin:${device.id}") == null) startPairing(device) else startRemote(device) }
        catch(e: Exception) { disconnect(); state.value=RemoteState(Phase.ERROR,"Could not connect. Check the same Wi-Fi network and Android TV Remote Service. If the TV was reset, forget it and pair again.",messageRes=R.string.status_could_not_connect_check_the_same_wi_fi_network_and_android_tv_rem) }
    }
    private fun envelope(n: Int, b: ByteArray) = Wire.message(Wire.int(1,2),Wire.int(2,200),Wire.bytes(n,b))
    private fun exchange(s: SSLSocket, field: Int, body: ByteArray, expected: Int) {
        Wire.write(s.outputStream,envelope(field,body)); val response=Wire.parse(Wire.frame(s.inputStream))
        check(response.num(2)==200 && response.any { it.number==expected }) { "Pairing rejected" }
    }
    private fun startPairing(tv: TvDevice) {
        val s=tls(tv,6467,true); pairingSocket=s
        exchange(s,10,Wire.message(Wire.string(1,"atvremote"),Wire.string(2,"OpenTV-Remote")),11)
        val encoding=Wire.message(Wire.int(1,3),Wire.int(2,6))
        exchange(s,20,Wire.message(Wire.bytes(1,encoding),Wire.int(3,1)),20)
        exchange(s,30,Wire.message(Wire.bytes(1,encoding),Wire.int(2,1)),31)
        state.value=RemoteState(Phase.PAIRING,"Enter the six-character code on your TV",messageRes=R.string.status_enter_the_six_character_code_on_your_tv)
    }
    override suspend fun pair(code: String) = withContext(Dispatchers.IO) {
        require(code.matches(Regex("[0-9a-fA-F]{6}"))) { "Enter all six characters (0–9, A–F)." }
        val s=pairingSocket ?: error("Pairing expired. Reconnect to get a new code.")
        val client=vault.identity().certificate.publicKey as RSAPublicKey
        val server=s.session.peerCertificates.first() as X509Certificate
        val key=server.publicKey as RSAPublicKey
        fun unsigned(b: java.math.BigInteger) = b.toByteArray().let { if(it.size>1 && it[0]==0.toByte()) it.copyOfRange(1,it.size) else it }
        val digest=MessageDigest.getInstance("SHA-256").digest(unsigned(client.modulus)+unsigned(client.publicExponent)+unsigned(key.modulus)+unsigned(key.publicExponent)+byteArrayOf(code.substring(2,4).toInt(16).toByte(),code.substring(4,6).toInt(16).toByte()))
        require((digest[0].toInt() and 255)==code.substring(0,2).toInt(16)) { "Code does not match this TV. Check it and try again." }
        try {
            exchange(s,40,Wire.bytes(1,digest),41)
            vault.putSecret("pin:${device!!.id}",fingerprint(server)); s.close(); pairingSocket=null
            startRemote(device!!)
        } catch(e: Exception) { s.close(); pairingSocket=null; state.value=RemoteState(Phase.ERROR,"Pairing was rejected or expired. Reconnect and try again.",messageRes=R.string.status_pairing_was_rejected_or_expired_reconnect_and_try_again); throw e }
    }
    private fun startRemote(tv: TvDevice) {
        val g=imeFence.nextConnection();imeConnection=g
        synchronized(imeLock) {ime.reset();imeFence.noteSession(ime.snapshot.session)}
        val s=tls(tv,6466,false)
        if(imeFence.currentConnection()!=g) {s.close();return}
        socket=s
        reader=scope.launch {
            try {
                while (isActive) {
                    val msg=Wire.parse(Wire.frame(s.inputStream))
                    if(imeFence.currentConnection()!=g)return@launch
                    when {
                        msg.any { it.number==1 } -> {
                            features=msg.nested(1).num(1) and (1 or 2 or 4 or 32 or 64 or 512)
                            check(features and 2 != 0) { "Keys unavailable" }
                            val info=Wire.message(Wire.int(3,1),Wire.string(4,"1"),Wire.string(5,com.luma.remote.BuildConfig.APPLICATION_ID),Wire.string(6,com.luma.remote.BuildConfig.VERSION_NAME))
                            send(Wire.bytes(1,Wire.message(Wire.int(1,features),Wire.bytes(2,info))))
                        }
                        msg.any { it.number==2 } -> send(Wire.bytes(2,Wire.int(1,features)))
                        msg.any { it.number==8 } -> send(Wire.bytes(9,Wire.int(1,msg.nested(8).num(1))))
                        msg.any { it.number in 20..22 } -> {
                            if(features and 4 != 0) {
                                val outbound=synchronized(imeLock) {
                                    if(!imeFence.allows(g,ime.snapshot.session))ime.dismiss()
                                    val before=ime.snapshot
                                    val showRequest=ime.receive(msg)
                                    val after=ime.snapshot
                                    val show=showRequest || after.active && (!before.active || after.session!=before.session)
                                    val next=ime.nextEdit()
                                    publishIme(if(show)TvKeyboardRequest(keyboardIds.incrementAndGet(),0) else null,show,g)
                                    next
                                }
                                if(outbound!=null)writeIme(outbound,g)
                            }
                        }
                        msg.any { it.number==40 } -> {
                            RemoteSocketPolicy.ready(s)
                            state.value=RemoteState(Phase.READY,"Connected • encrypted Wi-Fi",AndroidTvProtocol.capabilities(features),keyboardRequest=state.value.keyboardRequest,ime=synchronized(imeLock) {ime.snapshot},messageRes=R.string.status_connected_encrypted_wi_fi)
                        }
                        msg.any { it.number==50 } -> state.value=state.value.copy(volume=msg.nested(50).num(7))
                        msg.any { it.number==3 } -> state.value=state.value.copy(message="TV rejected the last request",messageRes=R.string.status_tv_rejected_the_last_request)
                    }
                }
            } catch(e: Exception) { if(isActive && imeFence.currentConnection()==g) { synchronized(imeLock) { imeDeadline?.cancel();ime.reset() };s.close(); state.value=RemoteState(Phase.ERROR,"Connection lost. Tap reconnect; commands are never replayed.",messageRes=R.string.status_connection_lost_tap_reconnect_commands_are_never_replayed) } }
        }
    }
    private fun send(bytes: ByteArray) = synchronized(sendLock) { val s=socket ?: error("Reconnect to your TV"); Wire.write(s.outputStream,bytes) }
    override suspend fun command(command: Command) = withContext(Dispatchers.IO) {
        check(state.value.phase==Phase.READY && command in state.value.capabilities.commands)
        send(AndroidTvProtocol.key(command))
    }
    override fun nativeHold(command:Command)=command in AndroidTvProtocol.heldCommands
    override suspend fun beginHold(command:Command) {
        check(nativeHold(command) && state.value.phase==Phase.READY)
        heldKey=command
        withContext(Dispatchers.IO) { send(AndroidTvProtocol.key(command,KeyDirection.DOWN)) }
    }
    override suspend fun endHold(command:Command) {
        if(heldKey!=command)return
        heldKey=null
        val s=socket ?: return
        withContext(Dispatchers.IO) { synchronized(sendLock) { Wire.write(s.outputStream,AndroidTvProtocol.key(command,KeyDirection.UP)) } }
    }
    override suspend fun launchApp(shortcut:AppShortcut) = withContext(Dispatchers.IO) {
        check(state.value.phase==Phase.READY && state.value.capabilities.apps)
        send(AndroidTvProtocol.app(shortcut))
    }
    override suspend fun text(value: String) {keyboardManual(state.value.ime.session,value)}

    private fun publishIme(request:TvKeyboardRequest?=null,show:Boolean=false,connection:Long=imeConnection) {
        if(imeFence.currentConnection()!=connection)return
        val value=ime.snapshot
        imeFence.noteSession(value.session)
        if(!imeFence.allows(connection,value.session))return
        state.update { current -> if(imeFence.currentConnection()!=connection || current.phase !in setOf(Phase.READY,Phase.CONNECTING))current else current.copy(ime=value,keyboardRequest=if(!value.active)null else if(show)request else current.keyboardRequest) }
    }
    /** No mutable IME lock is held while waiting for the socket writer. */
    private fun writeIme(edit:ImeEdit,connection:Long):Boolean {
        val sent=try { synchronized(sendLock) {
            val allowed=synchronized(imeLock) {imeFence.allows(connection,edit.session) && ime.isPending(edit.session,edit.id)}
            if(!allowed)return@synchronized false
            val s=socket ?: return@synchronized false
            Wire.write(s.outputStream,edit.bytes());true
        } } catch(e:Exception) {
            synchronized(imeLock) {if(ime.isPending(edit.session,edit.id)) {ime.fallback();publishIme(connection=connection)}}
            throw e
        }
        if(!sent)return false
        synchronized(imeLock) {
            if(!imeFence.allows(connection,edit.session) || !ime.isPending(edit.session,edit.id))return false
            publishIme(connection=connection);imeDeadline?.cancel()
            imeDeadline=scope.launch {
                delay(1800)
                synchronized(imeLock) {if(imeFence.allows(connection,edit.session)) {ime.expire(edit.session,edit.id);publishIme(connection=connection)}}
            }
        }
        return true
    }
    override suspend fun keyboardEdit(session:Long,value:TvImeValue,composing:Boolean,revision:Long,baseVersion:Long):Boolean=withContext(Dispatchers.IO) {
        val connection=imeConnection
        var accepted=false
        val outbound=synchronized(imeLock) {
            if(!imeFence.allows(connection,session) || state.value.phase!=Phase.READY || !state.value.capabilities.text)return@synchronized null
            accepted=ime.local(session,value,composing,revision,baseVersion)
            val next=if(accepted)ime.nextEdit() else null
            publishIme(connection=connection);next
        }
        if(outbound!=null)writeIme(outbound,connection)
        accepted
    }
    override suspend fun keyboardManual(session:Long,value:String):Boolean=withContext(Dispatchers.IO) {
        val connection=imeConnection
        val bytes=synchronized(imeLock) {
            if(!imeFence.allows(connection,session) || ime.snapshot.session!=session || state.value.phase!=Phase.READY || !state.value.capabilities.text)return@synchronized null
            ime.fallback();imeDeadline?.cancel();publishIme(connection=connection);ime.manualInsertion(value)
        } ?: return@withContext false
        synchronized(sendLock) {
            if(!imeFence.allows(connection,session))return@synchronized false
            val s=socket ?: return@synchronized false
            Wire.write(s.outputStream,bytes);true
        }
    }
    override suspend fun keyboardEnter(session:Long,revision:Long):Boolean=withContext(Dispatchers.IO) {
        val connection=imeConnection
        // An explicit submit may arrive before its final phone edit/ACK. Wait without
        // holding either lock, and abandon it on any newer input or field/lifecycle change.
        val confirmed=withTimeoutOrNull(1800) {
            state.first { current ->
                current.phase!=Phase.READY || current.ime.session!=session || current.ime.mode!=TvImeMode.LIVE ||
                    current.ime.clientRevision>revision || current.ime.clientRevision==revision && !current.ime.pending
            }
        } ?: return@withContext false
        if(confirmed.phase!=Phase.READY || confirmed.ime.session!=session || confirmed.ime.mode!=TvImeMode.LIVE || confirmed.ime.clientRevision!=revision)return@withContext false
        synchronized(sendLock) {
            val allowed=synchronized(imeLock) {imeFence.allows(connection,session) && state.value.phase==Phase.READY && features and 2!=0 && ime.canEnter(session,revision)}
            if(!allowed)return@synchronized false
            val s=socket ?: return@synchronized false
            Wire.write(s.outputStream,Wire.bytes(10,Wire.message(Wire.int(1,66),Wire.int(2,KeyDirection.SHORT.wire))));true
        }
    }
    override fun cancelKeyboard(session:Long) {
        if(state.value.ime.session!=session)return
        val connection=imeConnection
        imeFence.invalidate(session)
        state.update { if(it.ime.session==session)it.copy(ime=TvImeState(session=session),keyboardRequest=null) else it }
        scope.launch {synchronized(imeLock) {if(ime.snapshot.session<=session) {ime.dismiss();publishIme(connection=connection)}}}
    }

    override fun disconnect() {
        val cancelledConnection=imeFence.nextConnection()
        imeDeadline?.cancel()
        reader?.cancel(); reader=null
        val remoteSocket=socket; val pairSocket=pairingSocket;val held=heldKey
        heldKey=null
        socket=null; pairingSocket=null
        // SSLSocket.close writes close_notify; lifecycle callbacks must never perform that I/O.
        scope.launch {
            if(held!=null && remoteSocket!=null)runCatching { synchronized(sendLock) { Wire.write(remoteSocket.outputStream,AndroidTvProtocol.key(held,KeyDirection.UP)) } }
            runCatching { remoteSocket?.close() }; runCatching { pairSocket?.close() }
            synchronized(imeLock) {if(imeFence.currentConnection()==cancelledConnection)ime.reset()} }
        state.value=RemoteState()
    }
}
