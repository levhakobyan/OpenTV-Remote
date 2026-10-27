package com.luma.remote.protocol

import com.luma.remote.R
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow

enum class Platform(val title: String) { ANDROID_TV("Android TV / Google TV"), LG("LG webOS"), SAMSUNG("Samsung TV"), ROKU("Roku"), FIRE_TV("Fire TV"), BLUETOOTH("Bluetooth keyboard host"), UNKNOWN("Network device") }
data class TvDevice(val id: String, val name: String, val host: String, val platform: Platform)
enum class Command(val label: String, val androidKey: Int) {
    UP("Up",19), DOWN("Down",20), LEFT("Left",21), RIGHT("Right",22), OK("OK",23), BACK("Back",4), HOME("Home",3),
    VOLUME_UP("Volume up",24), VOLUME_DOWN("Volume down",25), MUTE("Mute",164), PLAY_PAUSE("Play / pause",85), REWIND("Rewind",89), FORWARD("Fast forward",90), POWER("Power",26), SETTINGS("Settings",176), INPUT("Inputs",178), TV("Live TV",170), APPS("All apps",284),
    CHANNEL_UP("Channel up",166), CHANNEL_DOWN("Channel down",167), GUIDE("Guide",172), INFO("Info",165), MENU("Menu",82), DELETE("Delete",67),
    ZERO("0",7), ONE("1",8), TWO("2",9), THREE("3",10), FOUR("4",11), FIVE("5",12), SIX("6",13), SEVEN("7",14), EIGHT("8",15), NINE("9",16)
}
data class Capabilities(val commands: Set<Command> = emptySet(), val text: Boolean = false, val pointer: Boolean = false, val apps: Boolean = false, val scroll: Boolean = false)
enum class Phase { DISCONNECTED, CONNECTING, PAIRING, READY, ERROR }
data class PeerIdentity(val fingerprint:String)
data class RemoteState(val phase: Phase = Phase.DISCONNECTED, val message: String = "Choose a TV to get started", val capabilities: Capabilities = Capabilities(), val volume: Int? = null, val keyboardRequest: TvKeyboardRequest? = null, val ime:TvImeState=TvImeState(), val identity:PeerIdentity?=null, val legacyAvailable:Boolean=false, val retryable:Boolean=true, val canResetPairing:Boolean=false, val legacyConnection:Boolean=false, @StringRes val messageRes:Int=R.string.status_choose_a_tv_to_get_started)
enum class KeyDirection(val wire:Int) { DOWN(1), UP(2), SHORT(3) }
interface RemoteProvider {
    val state: StateFlow<RemoteState>
    suspend fun connect(device: TvDevice)
    suspend fun pair(code: String) { error("This device pairs on the TV") }
    suspend fun command(command: Command)
    fun nativeHold(command:Command):Boolean = false
    suspend fun beginHold(command:Command) { error("Held keys unavailable") }
    suspend fun endHold(command:Command) {}
    suspend fun launchApp(shortcut: AppShortcut) { error("App shortcuts are unavailable") }
    suspend fun text(value: String) { error("Text entry is unavailable") }
    suspend fun keyboardEdit(session:Long,value:TvImeValue,composing:Boolean,revision:Long,baseVersion:Long):Boolean = false
    suspend fun keyboardManual(session:Long,value:String):Boolean = false
    suspend fun keyboardEnter(session:Long,revision:Long):Boolean = false
    fun cancelKeyboard(session:Long) {}
    suspend fun move(dx: Float, dy: Float) { error("Pointer is unavailable") }
    suspend fun click() { command(Command.OK) }
    suspend fun scroll(amount:Float) { error("Scrolling is unavailable") }
    fun trustIdentity(fingerprint:String) { error("Identity confirmation unavailable") }
    fun useSecureConnection() { error("Secure connection selection unavailable") }
    fun resetPairing() { error("Pairing reset unavailable") }
    fun useLegacyConnection() { error("Legacy connection unavailable") }
    fun disconnect()
}

/** Validated, user-selected launch targets. Package IDs become a fixed market launch URI; raw intents are never accepted. */
enum class ShortcutKind(val title:String) { HTTPS("HTTPS link"), DEEP_LINK("Deep link"), PACKAGE("App ID") }
enum class ShortcutIcon { AUTO, VIDEO, MUSIC, TV, GAME, WEB }
data class TvKeyboardRequest(val id:Long,val fieldCounter:Int)
data class AppShortcut(val name:String,val url:String,val icon:ShortcutIcon=ShortcutIcon.AUTO,val kind:ShortcutKind=ShortcutKind.HTTPS) {
    fun isValid():Boolean=runCatching {
        if(name.isBlank() || name.length>24 || name.any { it.isISOControl() } || url.isBlank() || url.length>2048 || url.any { it.isWhitespace() || it.isISOControl() })return false
        if(kind==ShortcutKind.PACKAGE)return url.length<=255 && url.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
        val uri=java.net.URI(url)
        if(uri.userInfo!=null || uri.schemeSpecificPart.isNullOrBlank() || uri.schemeSpecificPart.any { it.isISOControl() })return false
        if(kind==ShortcutKind.HTTPS)uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.port in setOf(-1,443)
        else {
            val scheme=uri.scheme ?: return false
            scheme.matches(Regex("[a-z][a-z0-9+.-]{1,31}")) && scheme !in setOf("http","https","intent","file","content","data","javascript","about","blob","tel","sms","smsto","mailto","market","android.resource")
        }
    }.getOrDefault(false)
    fun launchUri():String { require(isValid());return if(kind==ShortcutKind.PACKAGE)"market://launch?id=$url" else url }
    companion object {
        val defaults=listOf(AppShortcut("YouTube","https://www.youtube.com/"),AppShortcut("Netflix","https://www.netflix.com/"))
    }
}

object AndroidTvProtocol {
    val heldCommands=setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK)
    val digits=listOf(Command.ZERO,Command.ONE,Command.TWO,Command.THREE,Command.FOUR,Command.FIVE,Command.SIX,Command.SEVEN,Command.EIGHT,Command.NINE)
    fun capabilities(features:Int):Capabilities {
        val commands=if(features and 2 != 0) Command.entries.toMutableSet() else mutableSetOf()
        if(features and 64 == 0)commands.removeAll(setOf(Command.VOLUME_UP,Command.VOLUME_DOWN,Command.MUTE))
        if(features and 32 == 0)commands.remove(Command.POWER)
        return Capabilities(commands,text=features and 4 != 0,apps=features and 512 != 0)
    }
    fun keyboardRequest(message:List<Wire.Field>,id:Long):TvKeyboardRequest? {
        val show=message.firstOrNull { it.number==22 }?.bytes ?: return null
        val field=Wire.parse(show).firstOrNull { it.number==2 }?.bytes ?: return null
        val status=Wire.parse(field)
        if(status.num(3)<0 || status.num(4)<0 || status.num(1)<0)return null
        return TvKeyboardRequest(id,status.num(1))
    }
    fun text(value:String,imeCounter:Int=0,fieldCounter:Int=0):ByteArray {
        require(value.isNotEmpty() && value.length<=1000)
        val obj=Wire.message(Wire.int(1,value.length-1),Wire.int(2,value.length-1),Wire.string(3,value))
        val edit=Wire.message(Wire.int(1,1),Wire.bytes(2,obj))
        return Wire.bytes(21,Wire.message(Wire.int(1,imeCounter),Wire.int(2,fieldCounter),Wire.bytes(3,edit)))
    }
    fun key(command:Command,direction:KeyDirection=KeyDirection.SHORT)=Wire.bytes(10,Wire.message(Wire.int(1,command.androidKey),Wire.int(2,direction.wire)))
    fun app(shortcut:AppShortcut):ByteArray { require(shortcut.isValid()); return Wire.bytes(90,Wire.string(1,shortcut.launchUri())) }
}
