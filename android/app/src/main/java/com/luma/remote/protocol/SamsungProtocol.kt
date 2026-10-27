package com.luma.remote.protocol

import org.json.JSONObject
import java.util.Base64

/** Local Tizen WebSocket protocol. Transport approval does not prove every key works in every TV app. */
internal object SamsungProtocol {
    val keys=mapOf(
        Command.UP to "KEY_UP",Command.DOWN to "KEY_DOWN",Command.LEFT to "KEY_LEFT",Command.RIGHT to "KEY_RIGHT",
        Command.OK to "KEY_ENTER",Command.BACK to "KEY_RETURN",Command.HOME to "KEY_HOME",
        Command.VOLUME_UP to "KEY_VOLUP",Command.VOLUME_DOWN to "KEY_VOLDOWN",Command.MUTE to "KEY_MUTE",
        Command.CHANNEL_UP to "KEY_CHUP",Command.CHANNEL_DOWN to "KEY_CHDOWN",Command.GUIDE to "KEY_GUIDE",
        Command.INFO to "KEY_INFO",Command.MENU to "KEY_MENU",Command.INPUT to "KEY_SOURCE",Command.TV to "KEY_TV",
        Command.POWER to "KEY_POWER",Command.REWIND to "KEY_REWIND",Command.FORWARD to "KEY_FF"
    )+AndroidTvProtocol.digits.mapIndexed { n,c -> c to "KEY_$n" }
    fun key(command:Command):String {
        val code=keys[command] ?: error("Unsupported Samsung command")
        return JSONObject().put("method","ms.remote.control").put("params",JSONObject().put("Cmd","Click").put("DataOfCmd",code).put("Option","false").put("TypeOfRemote","SendRemoteKey")).toString()
    }
    fun text(value:String):List<String> {
        require(value.length in 1..1000 && value.none { it=='\u0000' })
        val prepare=JSONObject().put("method","ms.channel.emit").put("params",JSONObject().put("event","custom.remote.textReceived").put("to","broadcast"))
        val input=JSONObject().put("method","ms.remote.control").put("params",JSONObject().put("Cmd",Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))).put("DataOfCmd","base64").put("TypeOfRemote","SendInputString"))
        return listOf(prepare.toString(),input.toString())
    }
    fun token(value:String?)=value?.takeIf { it.length in 1..1024 && it.all { c -> c.code in 33..126 } }
    fun approvalToken(data:JSONObject):String? {
        if(!data.has("token") || data.isNull("token"))return null
        val raw=data.get("token")
        require(raw is String || raw is Number)
        return token(raw.toString()) ?: error("Invalid approval token")
    }
    fun clientName()=Base64.getEncoder().encodeToString("OpenTV-Remote".toByteArray(Charsets.UTF_8))
    fun info(raw:String):SamsungInfo {
        require(raw.length<=65536)
        val o=JSONObject(raw);val device=o.getJSONObject("device")
        require(listOf(o.optString("type"),device.optString("type")).any { it.equals("Samsung SmartTV",true) })
        val support=when(val value=o.opt("isSupport")) { is JSONObject -> value;is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject());else -> JSONObject() }
        fun flag(name:String):Boolean? {
            val value=if(support.has(name))support.opt(name) else device.opt(name)
            return when(value?.toString()?.lowercase()) { "true","1" -> true;"false","0" -> false;else -> null }
        }
        require(flag("remote_available")!=false)
        val name=(o.optString("name").ifBlank { device.optString("name") }).filterNot { it.isISOControl() }.trim().take(64).ifBlank { "Samsung TV" }
        val id=(device.optString("id").ifBlank { o.optString("id") }).takeIf { it.length in 1..128 && it.none { c -> c.isWhitespace() || c.isISOControl() } }
        val commands=keys.keys.toMutableSet()
        if(flag("remote_fourDirections")==false)commands.removeAll(setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK))
        return SamsungInfo(id,name,Capabilities(commands,text=flag("ImeSyncedSupport")==true))
    }
}
internal data class SamsungInfo(val id:String?,val name:String,val capabilities:Capabilities) {
    fun device(host:String)=TvDevice("samsung:${id ?: host}",name,host,Platform.SAMSUNG)
}
