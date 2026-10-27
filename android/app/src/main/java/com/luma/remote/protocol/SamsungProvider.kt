package com.luma.remote.protocol

import com.luma.remote.R
import com.luma.remote.data.Vault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal interface SamsungSecrets {
    fun read(key:String):String?
    fun write(key:String,value:String)
    fun remove(key:String)
}

/** Token-authenticated local Tizen remote. TLS never silently downgrades or accepts a changed pin. */
internal class SamsungProvider(
    private val secrets:SamsungSecrets,
    private val base:OkHttpClient=SamsungProbe.http,
    private val securePort:Int=8002,
    private val legacyPort:Int=8001,
    private val hostAllowed:(String)->Boolean=LocalAddress::valid,
    private val approvalTimeout:Long=60000L
):RemoteProvider {
    constructor(vault:Vault):this(object:SamsungSecrets {
        override fun read(key:String)=vault.secret(key)
        override fun write(key:String,value:String)=vault.putSecret(key,value)
        override fun remove(key:String)=vault.removeSecret(key)
    })
    override val state=MutableStateFlow(RemoteState())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val sendLock=Mutex()
    private val owner=Any()
    @Volatile private var generation=0
    @Volatile private var socketEpoch=0
    @Volatile private var socket:WebSocket?=null
    @Volatile private var call:Call?=null
    private var timer:Job?=null
    private var device:TvDevice?=null
    private var info:SamsungInfo?=null
    private var trust:SamsungTrust?=null
    private var client:OkHttpClient?=null
    private var legacy=false
    private var authRetry=false
    private fun key(prefix:String)="$prefix:${device!!.id}"
    override suspend fun connect(device:TvDevice)=withContext(Dispatchers.IO) {
        disconnect();require(device.platform==Platform.SAMSUNG && hostAllowed(device.host))
        this@SamsungProvider.device=device;val attempt=generation
        state.value=RemoteState(Phase.CONNECTING,"Checking Samsung TV…",messageRes=R.string.status_checking_samsung_tv)
        try {
            legacy=secrets.read(key("samsung-mode"))=="legacy"
            val policy=SamsungTrust(secrets.read(key("samsung-pin")));trust=policy
            val builder=base.newBuilder().followRedirects(false).followSslRedirects(false)
            if(!legacy)builder.sslSocketFactory(policy.socketFactory(),policy).hostnameVerifier(policy.verifier(device.host))
            val http=builder.build();client=http
            val url="${if(legacy)"http" else "https"}://${device.host}:${if(legacy)legacyPort else securePort}/api/v2/"
            val request=http.newCall(Request.Builder().url(url).build())
            synchronized(owner) { if(attempt!=generation)return@withContext;call=request }
            val found=request.execute().use(SamsungProbe::read)
            if(attempt!=generation)return@withContext
            info=found;call=null;authRetry=false
            openSocket(attempt)
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) {
            if(attempt!=generation)return@withContext
            call=null
            when {
                trust?.changed==true -> fail(attempt,"Samsung TV identity changed. Reset pairing only if this is the TV you expect.",retryable=false,reset=true,messageRes=R.string.status_samsung_tv_identity_changed_reset_pairing_only_if_this_is_the_tv)
                !legacy && secrets.read(key("samsung-pin"))==null && trust?.observed!=null -> {
                    state.value=RemoteState(Phase.CONNECTING,"Confirm this TV connection on your trusted home network.",identity=PeerIdentity(trust!!.observed!!),messageRes=R.string.status_confirm_this_tv_connection_on_your_trusted_home_network)
                }
                else -> {
                    val older=if(!legacy)SamsungProbe.legacyInfo(device.host,base,legacyPort,hostAllowed) else null
                    if(attempt==generation)fail(attempt,if(legacy)"Samsung legacy connection unavailable. Turn on the TV and check its Device Connection Manager permissions." else "Secure Samsung connection unavailable. Keep the TV on and check the same Wi-Fi network.",offerLegacy=older!=null,messageRes=if(legacy)R.string.status_samsung_legacy_connection_unavailable_turn_on_the_tv_and_check_it else R.string.status_secure_samsung_connection_unavailable_keep_the_tv_on_and_check_th)
                }
            }
        }
    }
    private fun openSocket(attempt:Int):Unit=synchronized(owner) {
        if(attempt!=generation)return@synchronized
        val tv=device ?: return@synchronized
        val epoch=++socketEpoch
        socket?.cancel();timer?.cancel()
        // Never send a stored token on plaintext port 8001.
        val token=if(legacy)null else SamsungProtocol.token(secrets.read(key("samsung-token")))
        val url=HttpUrl.Builder().scheme(if(legacy)"http" else "https").host(tv.host).port(if(legacy)legacyPort else securePort)
            .addPathSegments("api/v2/channels/samsung.remote.control").addQueryParameter("name",SamsungProtocol.clientName()).apply { token?.let { addQueryParameter("token",it) } }.build()
        state.value=RemoteState(Phase.CONNECTING,"Connecting to Samsung TV…",messageRes=R.string.status_connecting_to_samsung_tv)
        val http=client!!.newBuilder().readTimeout(0,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).build()
        fun current()=attempt==generation && epoch==socketEpoch
        socket=http.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) {
                synchronized(owner) {
                if(!current()) { webSocket.cancel();return }
                state.value=RemoteState(Phase.PAIRING,if(token==null)"On your TV, allow OpenTV-Remote. If no prompt appears, check Device Connection Manager → Device List." else "Restoring Samsung TV permission…",legacyConnection=legacy,messageRes=if(token==null)R.string.status_on_your_tv_allow_opentv_remote_if_no_prompt_appears_check_device_co else R.string.status_restoring_samsung_tv_permission)
                }
            }
            override fun onMessage(webSocket:WebSocket,text:String) {
                synchronized(owner) {
                if(!current())return
                if(text.length>65536) { fail(attempt,"Samsung TV sent an oversized response.",retryable=false,messageRes=R.string.status_samsung_tv_sent_an_oversized_response);return }
                runCatching {
                    val message=JSONObject(text)
                    when(message.optString("event")) {
                        "ms.channel.connect" -> {
                            val data=message.optJSONObject("data") ?: error("Missing approval")
                            val fresh=SamsungProtocol.approvalToken(data)
                            synchronized(owner) {
                                if(!current())return
                                if(!legacy && fresh!=null)secrets.write("samsung-token:${tv.id}",fresh)
                                timer?.cancel()
                                state.value=RemoteState(Phase.READY,if(legacy)"Connected • legacy Wi-Fi (unencrypted)" else "Connected • secure Samsung Wi-Fi",info!!.capabilities,legacyConnection=legacy,messageRes=if(legacy)R.string.status_connected_legacy_wi_fi_unencrypted else R.string.status_connected_secure_samsung_wi_fi)
                            }
                        }
                        "ms.channel.unauthorized" -> {
                            if(!legacy && token!=null && !authRetry) {
                                authRetry=true;secrets.remove("samsung-token:${tv.id}");openSocket(attempt)
                            } else fail(attempt,"TV declined permission. Allow OpenTV-Remote in the TV's Device Connection Manager, then reconnect.",retryable=false,messageRes=R.string.status_tv_declined_permission_allow_opentv_remote_in_the_tv_s_device_conne)
                        }
                        "ms.channel.timeOut" -> fail(attempt,"TV approval timed out. Reconnect and accept OpenTV-Remote on the TV.",retryable=false,messageRes=R.string.status_tv_approval_timed_out_reconnect_and_accept_opentv_remote_on_the_tv)
                        "ms.error" -> fail(attempt,"Samsung TV declined this request. Its firmware may not support this local remote feature.",retryable=false,messageRes=R.string.status_samsung_tv_declined_this_request_its_firmware_may_not_support_thi)
                    }
                }.onFailure { if(current())fail(attempt,"Samsung TV sent an invalid response. Reconnect to try again.",retryable=false,messageRes=R.string.status_samsung_tv_sent_an_invalid_response_reconnect_to_try_again) }
                }
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {
                synchronized(owner) { if(current())fail(attempt,if(trust?.changed==true)"Samsung TV identity changed. Forget this TV and pair again only if you trust it." else "Samsung connection lost. Check TV power, Wi-Fi and TV remote permissions, then reconnect.",retryable=trust?.changed!=true,reset=trust?.changed==true,messageRes=if(trust?.changed==true)R.string.status_samsung_tv_identity_changed_forget_this_tv_and_pair_again_only_if else R.string.status_samsung_connection_lost_check_tv_power_wi_fi_and_tv_remote_permis) }
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {
                webSocket.close(code,null)
                synchronized(owner) { if(current())fail(attempt,"Samsung TV disconnected. Reconnect to continue.",messageRes=R.string.status_samsung_tv_disconnected_reconnect_to_continue) }
            }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { synchronized(owner) { if(current())fail(attempt,"Samsung TV disconnected. Reconnect to continue.",messageRes=R.string.status_samsung_tv_disconnected_reconnect_to_continue) } }
        })
        timer=scope.launch { delay(approvalTimeout);synchronized(owner) { if(current() && state.value.phase!=Phase.READY)fail(attempt,"TV approval timed out. Reconnect, then allow OpenTV-Remote on your TV.",retryable=false,messageRes=R.string.status_tv_approval_timed_out_reconnect_then_allow_opentv_remote_on_your_tv) } }
    }
    private fun fail(attempt:Int,message:String,messageRes:Int,offerLegacy:Boolean=false,retryable:Boolean=true,reset:Boolean=false)=synchronized(owner) {
        if(attempt!=generation)return@synchronized
        timer?.cancel();socketEpoch++;socket?.cancel();socket=null
        state.value=RemoteState(Phase.ERROR,message,legacyAvailable=offerLegacy,retryable=retryable,canResetPairing=reset,legacyConnection=legacy,messageRes=messageRes)
    }
    override fun trustIdentity(fingerprint:String)=synchronized(owner) {
        val pending=state.value.identity ?: return@synchronized
        if(pending.fingerprint!=fingerprint)return@synchronized
        secrets.write(key("samsung-pin"),fingerprint)
    }
    override fun useSecureConnection()=synchronized(owner) {
        if(device==null)return@synchronized
        secrets.remove(key("samsung-mode"))
    }
    override fun resetPairing()=synchronized(owner) {
        if(!state.value.canResetPairing)return@synchronized
        listOf("samsung-pin","samsung-token","samsung-mode").forEach { secrets.remove(key(it)) }
    }
    override fun useLegacyConnection()=synchronized(owner) {
        if(!state.value.legacyAvailable)return@synchronized
        secrets.write(key("samsung-mode"),"legacy")
    }
    override suspend fun command(command:Command)=sendLock.withLock {
        check(state.value.phase==Phase.READY && command in state.value.capabilities.commands)
        send(SamsungProtocol.key(command));delay(100)
    }
    override suspend fun text(value:String)=sendLock.withLock {
        check(state.value.phase==Phase.READY && state.value.capabilities.text)
        SamsungProtocol.text(value).forEach(::send);delay(100)
    }
    private fun send(message:String) { check((socket?.queueSize() ?: Long.MAX_VALUE)<65536 && socket?.send(message)==true) { "Samsung send failed" } }
    override fun disconnect()=synchronized(owner) {
        generation++;socketEpoch++;timer?.cancel();call?.cancel();call=null;socket?.cancel();socket=null
        state.value=RemoteState();info=null;client=null;trust=null
    }
}
