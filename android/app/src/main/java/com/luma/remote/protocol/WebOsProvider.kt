package com.luma.remote.protocol

import com.luma.remote.R
import com.luma.remote.data.Vault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Legacy webOS local SSAP transport. Never falls back from TLS or disables TLS validation. */
class WebOsProvider(private val vault: Vault): RemoteProvider {
    override val state=MutableStateFlow(RemoteState())
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var ws:WebSocket?=null
    private var pointer:WebSocket?=null
    private var tv:TvDevice?=null
    private var timer:Job?=null
    private val ids=AtomicInteger()
    private val pending=ConcurrentHashMap<String,CompletableDeferred<JSONObject>>()
    override suspend fun connect(device:TvDevice) {
        disconnect(); tv=device; state.value=RemoteState(Phase.CONNECTING,"Connecting over local Wi-Fi…",messageRes=R.string.status_connecting_over_local_wi_fi)
        ws=client.newWebSocket(Request.Builder().url("ws://${device.host}:3000/").build(),object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) {
                val manifest=JSONObject().put("manifestVersion",1).put("permissions",JSONArray(listOf("CONTROL_AUDIO","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_POWER","CONTROL_MOUSE_AND_KEYBOARD","CONTROL_INPUT_TEXT","CONTROL_INPUT_JOYSTICK")))
                val payload=JSONObject().put("pairingType","PROMPT").put("manifest",manifest)
                vault.secret("lg:${device.id}")?.let { payload.put("client-key",it) }
                webSocket.send(JSONObject().put("id","register").put("type","register").put("payload",payload).toString())
                state.value=RemoteState(Phase.PAIRING,"Accept OpenTV-Remote on your TV",messageRes=R.string.status_accept_opentv_remote_on_your_tv)
            }
            override fun onMessage(webSocket:WebSocket,text:String) {
                runCatching {
                    val o=JSONObject(text); val id=o.optString("id"); val payload=o.optJSONObject("payload") ?: JSONObject()
                    when(o.optString("type")) {
                        "registered" -> { timer?.cancel(); scope.launch {
                            try { val key=payload.getString("client-key"); vault.putSecret("lg:${device.id}",key); probe() }
                            catch(_:Exception) { fail("Could not finish pairing. Try again.",messageRes=R.string.status_could_not_finish_pairing_try_again) }
                        } }
                        "response" -> pending.remove(id)?.complete(payload)
                        "error" -> { pending.remove(id)?.completeExceptionally(IllegalStateException("TV declined this feature")); if(id=="register") fail("TV denied pairing. Try again and accept the TV prompt.",messageRes=R.string.status_tv_denied_pairing_try_again_and_accept_the_tv_prompt) }
                    }
                }.onFailure { fail("The TV sent an invalid response. Reconnect to try again.",messageRes=R.string.status_the_tv_sent_an_invalid_response_reconnect_to_try_again) }
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { fail("LG connection unavailable. This build supports local port 3000; TVs requiring secure port 3001 need a secure provider update.",messageRes=R.string.status_lg_connection_unavailable_this_build_supports_local_port_3000_tvs) }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { fail("TV disconnected. Reconnect to continue.",messageRes=R.string.status_tv_disconnected_reconnect_to_continue) }
        })
        timer=scope.launch { delay(60000); fail("Pairing timed out. Reconnect, then accept the prompt on your TV.",messageRes=R.string.status_pairing_timed_out_reconnect_then_accept_the_prompt_on_your_tv); ws?.cancel() }
    }
    private fun fail(message:String,messageRes:Int) { state.value=RemoteState(Phase.ERROR,message,messageRes=messageRes); pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) }; pending.clear() }
    private suspend fun request(uri:String,payload:JSONObject=JSONObject()):JSONObject {
        val id=ids.incrementAndGet().toString(); val result=CompletableDeferred<JSONObject>(); pending[id]=result
        try { check(ws?.send(JSONObject().put("id",id).put("type","request").put("uri","ssap://$uri").put("payload",payload).toString())==true); return withTimeout(5000) { result.await() }.also { check(!it.has("returnValue") || it.optBoolean("returnValue")) } }
        finally { pending.remove(id) }
    }
    private suspend fun probe() {
        val commands=mutableSetOf<Command>()
        val audio=runCatching { request("audio/getVolume") }.isSuccess
        if(audio) commands.addAll(listOf(Command.VOLUME_UP,Command.VOLUME_DOWN,Command.MUTE))
        val path=runCatching { request("com.webos.service.networkinput/getPointerInputSocket").getString("socketPath") }.getOrNull()
        var hasPointer=false
        if(path!=null) {
            val url=runCatching { java.net.URI(path) }.getOrNull()
            if(url!=null && url.host==tv?.host && url.scheme=="ws") hasPointer=runCatching {
                withTimeout(5000) { suspendCancellableCoroutine<Boolean> { cont ->
                    val p=client.newWebSocket(Request.Builder().url(path).build(),object:WebSocketListener() {
                        override fun onOpen(webSocket:WebSocket,response:Response) { if(cont.isActive)cont.resume(true) }
                        override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { if(cont.isActive)cont.resume(false) else state.value=state.value.copy(capabilities=state.value.capabilities.copy(pointer=false,commands=state.value.capabilities.commands-filterKeys),message="Pointer disconnected. Reconnect to restore navigation.",messageRes=R.string.status_pointer_disconnected_reconnect_to_restore_navigation) }
                    }); pointer=p; cont.invokeOnCancellation { p.cancel() }
                } }
            }.getOrDefault(false)
        }
        if(hasPointer)commands.addAll(filterKeys)
        // Non-mutating service inventory gates media and text; no destructive capability probes.
        val services=runCatching { request("api/getServiceList").getJSONArray("services").toString() }.getOrDefault("")
        if(services.contains("media.controls"))commands.addAll(listOf(Command.PLAY_PAUSE,Command.REWIND,Command.FORWARD))
        state.value=RemoteState(Phase.READY,"Connected • local Wi-Fi",Capabilities(commands,text=services.contains("ime"),pointer=hasPointer),messageRes=R.string.status_connected_local_wi_fi)
    }
    private val filterKeys=setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK,Command.BACK,Command.HOME)
    private var muted=false
    private var paused=false
    override suspend fun command(command:Command) {
        check(command in state.value.capabilities.commands)
        val key=mapOf(Command.UP to "UP",Command.DOWN to "DOWN",Command.LEFT to "LEFT",Command.RIGHT to "RIGHT",Command.OK to "ENTER",Command.BACK to "BACK",Command.HOME to "HOME")[command]
        if(key!=null) { check(pointer?.send("type:button\nname:$key\n\n")==true); return }
        when(command) {
            Command.VOLUME_UP -> request("audio/volumeUp")
            Command.VOLUME_DOWN -> request("audio/volumeDown")
            Command.MUTE -> { val current=request("audio/getMute"); muted=!current.optBoolean("mute"); request("audio/setMute",JSONObject().put("mute",muted)) }
            Command.PLAY_PAUSE -> { request("media.controls/${if(paused) "play" else "pause"}"); paused=!paused }
            Command.REWIND -> request("media.controls/rewind")
            Command.FORWARD -> request("media.controls/fastForward")
            else -> error("Command unavailable")
        }
    }
    override suspend fun text(value:String) { check(state.value.capabilities.text); require(value.length in 1..1000); request("com.webos.service.ime/insertText",JSONObject().put("text",value).put("replace",false)) }
    override suspend fun move(dx:Float,dy:Float) { check(state.value.capabilities.pointer); check(pointer?.send("type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\ndown:0\n\n")==true) }
    override fun disconnect() { timer?.cancel(); ws?.cancel(); ws=null; pointer?.cancel(); pointer=null; pending.values.forEach { it.cancel() }; pending.clear(); state.value=RemoteState() }
}
