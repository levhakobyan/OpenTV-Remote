package com.luma.remote.protocol

import org.json.JSONArray
import org.json.JSONObject

enum class LayoutPreset(val title:String) { CLASSIC("Classic"), CHANNELS("TV / Channels"), TOUCH("Touch"), MINIMAL("Minimal") }
enum class ControlSize(val title:String,val target:Int) { COMPACT("Compact",48), COMFORT("Comfort",56), LARGE("Large",64) }
enum class RemoteBlock(val title:String,val optional:Boolean=false) {
    DEVICE("TV details",true), APPS("App shortcuts",true), CONTROL("Navigation pad"), NAVIGATION("Back / Home / TV"), PLAYBACK("Playback",true), NUMBERS("Number pad",true)
}
enum class TvAction(val title:String,val command:Command?) {
    TV("Live TV",Command.TV), INPUT("Inputs",Command.INPUT), CHANNEL("Channel +",Command.CHANNEL_UP), APP("App shortcut",null), HIDDEN("Hidden",null)
}
data class RemoteLayout(
    val preset:LayoutPreset=LayoutPreset.CLASSIC,
    val size:ControlSize=ControlSize.COMPACT,
    val order:List<RemoteBlock> = RemoteBlock.entries.toList(),
    val hidden:Set<RemoteBlock> = setOf(RemoteBlock.NUMBERS,RemoteBlock.DEVICE),
    val gestures:Boolean=false,
    val volumeRight:Boolean=false,
    val tvAction:TvAction=TvAction.TV,
    val tvApp:String?=null,
    val blockSizes:Map<RemoteBlock,ControlSize> = emptyMap(),
    val tvConfigured:Boolean=false,
    val fitScreen:Boolean=true
) {
    fun normalized()=copy(fitScreen=true,order=(order+RemoteBlock.entries).distinct(),hidden=hidden.filter { it.optional }.toSet(),blockSizes=blockSizes.filterKeys { it!=RemoteBlock.DEVICE },tvApp=tvApp?.takeIf { target -> ShortcutKind.entries.any { AppShortcut("TV",target,kind=it).isValid() } })
    fun move(block:RemoteBlock,delta:Int):RemoteLayout {
        val items=normalized().order.toMutableList();val from=items.indexOf(block)
        val to=(from+delta).coerceIn(0,items.lastIndex)
        items.removeAt(from);items.add(to,block);return copy(order=items)
    }
    fun applyPreset(value:LayoutPreset):RemoteLayout {
        val base=copy(preset=value,order=RemoteBlock.entries.toList(),hidden=setOf(RemoteBlock.NUMBERS,RemoteBlock.DEVICE),gestures=false,blockSizes=emptyMap())
        return when(value) {
            LayoutPreset.CLASSIC -> base
            LayoutPreset.CHANNELS -> base.copy(order=listOf(RemoteBlock.NUMBERS,RemoteBlock.CONTROL,RemoteBlock.NAVIGATION,RemoteBlock.APPS,RemoteBlock.PLAYBACK,RemoteBlock.DEVICE),hidden=setOf(RemoteBlock.DEVICE))
            LayoutPreset.TOUCH -> base.copy(gestures=true)
            LayoutPreset.MINIMAL -> base.copy(hidden=setOf(RemoteBlock.NUMBERS,RemoteBlock.PLAYBACK,RemoteBlock.DEVICE))
        }
    }
    fun encode():String=normalized().let { p -> JSONObject().put("preset",p.preset.name).put("size",p.size.name).put("order",JSONArray(p.order.map { it.name })).put("hidden",JSONArray(p.hidden.map { it.name })).put("gestures",p.gestures).put("volumeRight",p.volumeRight).put("tvAction",p.tvAction.name).put("tvApp",p.tvApp).put("tvConfigured",p.tvConfigured).put("fitScreen",p.fitScreen).put("blockSizes",JSONObject(p.blockSizes.mapKeys { it.key.name }.mapValues { it.value.name })).toString() }
    companion object {
        fun decode(raw:String?):RemoteLayout=runCatching {
            if(raw==null)return RemoteLayout()
            val o=JSONObject(raw)
            fun <T:Enum<T>> enum(key:String,values:Array<T>,fallback:T)=values.firstOrNull { it.name==o.optString(key) } ?: fallback
            fun blocks(key:String,fallback:List<RemoteBlock>):List<RemoteBlock> {
                val a=o.optJSONArray(key) ?: return fallback
                return (0 until a.length()).mapNotNull { i -> RemoteBlock.entries.firstOrNull { it.name==a.optString(i) } }
            }
            val sizes=mutableMapOf<RemoteBlock,ControlSize>()
            val sizeObject=o.optJSONObject("blockSizes")
            RemoteBlock.entries.forEach { block -> ControlSize.entries.firstOrNull { it.name==sizeObject?.optString(block.name) }?.let { sizes[block]=it } }
            RemoteLayout(enum("preset",LayoutPreset.entries.toTypedArray(),LayoutPreset.CLASSIC),enum("size",ControlSize.entries.toTypedArray(),ControlSize.COMPACT),blocks("order",RemoteBlock.entries.toList()),blocks("hidden",listOf(RemoteBlock.NUMBERS,RemoteBlock.DEVICE)).toSet(),o.optBoolean("gestures"),o.optBoolean("volumeRight"),enum("tvAction",TvAction.entries.toTypedArray(),TvAction.TV),o.optString("tvApp").takeIf { it.isNotBlank() },sizes,o.optBoolean("tvConfigured"),o.optBoolean("fitScreen",true)).normalized()
        }.getOrDefault(RemoteLayout())
    }
}

object ShortcutCatalog {
    // Official HTTPS entry points, not installed-app detection or guaranteed deep links.
    val apps=listOf(
        AppShortcut("YouTube","https://www.youtube.com/"),
        AppShortcut("Netflix","https://www.netflix.com/"),
        AppShortcut("Prime Video","https://www.primevideo.com/",ShortcutIcon.VIDEO),
        AppShortcut("Spotify","https://open.spotify.com/",ShortcutIcon.MUSIC),
        AppShortcut("Disney+","https://www.disneyplus.com/",ShortcutIcon.VIDEO),
        AppShortcut("Twitch","https://www.twitch.tv/",ShortcutIcon.GAME),
        AppShortcut("Plex","https://app.plex.tv/",ShortcutIcon.TV)
    )
}

object GestureNavigation {
    fun direction(dx:Float,dy:Float):Command=if(kotlin.math.abs(dx)>kotlin.math.abs(dy)) { if(dx>0)Command.RIGHT else Command.LEFT } else { if(dy>0)Command.DOWN else Command.UP }
    fun supported(caps:Capabilities)=setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK).all { it in caps.commands }
}
