package com.luma.remote.protocol

import com.luma.remote.localizedResources

import com.luma.remote.R
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.*
import java.util.concurrent.TimeUnit

object LocalAddress {
    fun valid(host: String): Boolean = runCatching {
        // Only literal local IPv4 addresses; no DNS rebinding, URLs, credentials or loopback.
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))
        val parts=host.split('.').map(String::toInt); require(parts.all { it in 0..255 })
        parts[0]==10 || (parts[0]==192 && parts[1]==168) || (parts[0]==172 && parts[1] in 16..31) || (parts[0]==169 && parts[1]==254)
    }.getOrDefault(false)
}
class Discovery(private val context: Context) {
    val devices=MutableStateFlow<List<TvDevice>>(emptyList())
    val scanning=MutableStateFlow(false)
    val issue=MutableStateFlow<String?>(null)
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val http=OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS).followRedirects(false).build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var scan: Job?=null
    private var generation=0
    private val listeners=mutableListOf<NsdManager.DiscoveryListener>()
    private var udp: DatagramSocket?=null
    private var lock: WifiManager.MulticastLock?=null
    private data class Found(val service:NsdServiceInfo,val generation:Int)
    private val resolve=Channel<Found>(64)
    init { scope.launch { for(found in resolve) {
        if(found.generation!=generation)continue
        val info=found.service
        val done=CompletableDeferred<Unit>()
        try { nsd.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo:NsdServiceInfo,errorCode:Int) { done.complete(Unit) }
            override fun onServiceResolved(serviceInfo:NsdServiceInfo) {
                val host=serviceInfo.host?.hostAddress
                if(found.generation==generation && host!=null && LocalAddress.valid(host)) {
                    add(TvDevice("atv:${serviceInfo.serviceName}",serviceInfo.serviceName,host,Platform.ANDROID_TV))
                }
                done.complete(Unit)
            }
        }); withTimeoutOrNull(4000) { done.await() } } catch(_:Exception) { }
    } } }
    private fun add(tv:TvDevice) { devices.update { old -> old.filter { it.id!=tv.id && !(it.host==tv.host && it.platform==tv.platform) }+tv } }
    fun start() {
        stop(); val attempt=++generation; devices.value=emptyList(); issue.value=null; scanning.value=true
        try {
            lock=(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("luma-discovery").apply { setReferenceCounted(false); acquire() }
            for(type in listOf("_androidtvremote2._tcp.")) {
            val listener=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t:String) { }
                override fun onDiscoveryStopped(t:String) { }
                override fun onStartDiscoveryFailed(t:String,e:Int) { issue.value=context.localizedResources().getString(R.string.status_network_discovery_is_unavailable_try_again_or_use_an_ip_address) }
                override fun onStopDiscoveryFailed(t:String,e:Int) { }
                override fun onServiceLost(s:NsdServiceInfo) { }
                override fun onServiceFound(s:NsdServiceInfo) { if(attempt==generation)resolve.trySend(Found(s,attempt)) }
            }
            listeners+=listener
            nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener)
            }
        } catch(_:Exception) { issue.value=context.localizedResources().getString(R.string.status_allow_local_network_access_to_find_tvs_or_use_manual_connection) }
        scan=scope.launch {
            try {
                DatagramSocket().use { socket ->
                    udp=socket; socket.soTimeout=1000
                    val packet="M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: ssdp:all\r\n\r\n".toByteArray()
                    repeat(2) { socket.send(DatagramPacket(packet,packet.size,InetAddress.getByName("239.255.255.250"),1900)) }
                    val end=System.currentTimeMillis()+9000; val seen=mutableSetOf<String>()
                    while(isActive && System.currentTimeMillis()<end) {
                        try {
                            val response=DatagramPacket(ByteArray(8192),8192); socket.receive(response)
                            val host=response.address.hostAddress ?: continue
                            if(!LocalAddress.valid(host)) continue
                            val headers=String(response.data,0,response.length).lineSequence().mapNotNull { line -> val i=line.indexOf(':'); if(i<0)null else line.substring(0,i).trim().lowercase() to line.substring(i+1).trim() }.toMap()
                            val location=headers["location"] ?: continue
                            if(seen.add(location)) launch { describe(host,location) }
                        } catch(_:SocketTimeoutException) { }
                    }
                }
            } catch(_:Exception) { if(isActive) issue.value=context.localizedResources().getString(R.string.status_multicast_discovery_could_not_run_check_wi_fi_and_try_again) }
            finally { if(attempt==generation) { scanning.value=false; stopNsd() } }
        }
    }
    private fun describe(host:String, location:String) {
        runCatching {
            val uri=URI(location); require(uri.scheme=="http" && uri.host==host && uri.userInfo==null && (uri.port==-1 || uri.port in 1..65535))
            http.newCall(Request.Builder().url(location).build()).execute().use { response ->
                check(response.isSuccessful)
                val body=response.body ?: return
                val raw=body.byteStream().use { input -> val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(4096); while(out.size()<=65536) { val count=input.read(buffer,0,minOf(buffer.size,65537-out.size())); if(count<0)break; out.write(buffer,0,count) }; out.toByteArray() }; require(raw.size<=65536)
                val parser=XmlPullParserFactory.newInstance().newPullParser(); parser.setInput(raw.inputStream(),null)
                val values=mutableMapOf<String,String>(); var event=parser.eventType
                while(event!=XmlPullParser.END_DOCUMENT) { if(event==XmlPullParser.START_TAG && parser.name in setOf("friendlyName","manufacturer","modelName","UDN","deviceType")) values[parser.name]=parser.nextText(); event=parser.next() }
                val all=values.values.joinToString(" ").lowercase()
                val platform=when { all.contains("webos") || all.contains("lg electronics") -> Platform.LG; all.contains("samsung") -> Platform.SAMSUNG; all.contains("roku") -> Platform.ROKU; all.contains("amazon") || all.contains("fire tv") -> Platform.FIRE_TV; else -> return }
                if(platform==Platform.SAMSUNG) {
                    val type=values["deviceType"].orEmpty()
                    if(!type.contains("MediaRenderer",true) && !type.contains("RemoteControlReceiver",true) && !all.contains("dtv") && !all.contains("smarttv"))return
                    val info=SamsungProbe.legacyInfo(host)
                    val id=values["UDN"]?.takeIf { it.length in 1..128 && it.none { c -> c.isWhitespace() || c.isISOControl() } } ?: host
                    val name=values["friendlyName"].orEmpty().filterNot { it.isISOControl() }.trim().take(64).ifBlank { "Samsung TV" }
                    add(info?.device(host) ?: TvDevice("samsung:$id",name,host,Platform.SAMSUNG))
                    return
                }
                add(TvDevice(values["UDN"] ?: "$platform:$host",values["friendlyName"] ?: platform.title,host,platform))
            }
        }
    }
    private fun stopNsd() { listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }; listeners.clear(); lock?.let { if(it.isHeld) it.release() }; lock=null }
    fun stop() { generation++; scan?.cancel(); scan=null; udp?.close(); udp=null; scanning.value=false; stopNsd() }
    fun close() { stop(); resolve.close(); scope.cancel() }
}
