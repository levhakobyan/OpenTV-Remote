package com.luma.remote.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class HandPreference(val title:String) { RIGHT("Right hand"), LEFT("Left hand") }

enum class CanvasTemplate(val title:String,val description:String) {
    CLASSIC("Classic","Balanced everyday remote"), CHANNELS("Live TV","Numbers, channels and guide"),
    STREAMING("Streaming","Apps, navigation and playback"), TOUCH("Touchpad","A generous swipe surface"),
    MINIMAL("Minimal","Navigation, volume and home"), BIG_BUTTONS("Big buttons","Large targets, fewer controls"),
    FULL("Full remote","The All controls arrangement"), NUMBERS_SIDE("Numbers & touch","Keypad beside the touchpad");

    // Keep geometry in sync with iOS CanvasTemplate and shared/canvas-template-fixtures.json.
    fun create(caps:Capabilities,apps:List<AppShortcut>,hand:HandPreference=HandPreference.RIGHT):RemoteCanvas {
        val list=mutableListOf<CanvasItem>()
        fun add(kind:CanvasKind,x:Int,y:Int,w:Int=kind.minW,h:Int=kind.minH,command:Command?=null,app:String?=null) {
            val item=CanvasItem(kind=kind,x=x,y=y,w=w,h=h,command=command,app=app)
            if(item.supported(caps,apps))list+=item
        }
        fun key(command:Command,x:Int,y:Int,w:Int=1)=add(CanvasKind.KEY,x,y,w,command=command)
        fun row(keys:List<Command>,y:Int) { val w=6/keys.size;keys.forEachIndexed { n,c -> key(c,n*w,y,w) } }
        fun group(kind:CanvasKind,x:Int,y:Int,w:Int=kind.minW) {
            if(kind.keys.all { it in caps.commands })add(kind,x,y,w)
            else kind.keys.forEachIndexed { n,c -> key(c,x+if(kind.vertical)0 else w*n/kind.keys.size,y+if(kind.vertical)n else 0,if(kind.vertical)w else w/kind.keys.size) }
        }
        fun favorites(y:Int):Boolean {
            if(!caps.apps || apps.isEmpty())return false
            apps.take(2).forEachIndexed { n,a -> add(CanvasKind.APP,n*3,y,3,app=a.url) };return true
        }
        fun sides(y:Int,channels:Boolean=true) {
            val volume=if(hand==HandPreference.RIGHT)0 else 5
            val channel=5-volume
            group(CanvasKind.VOLUME,volume,y+1)
            key(Command.MUTE,volume,y+3)
            if(channels)group(CanvasKind.CHANNELS,channel,y+1)
        }
        fun pad(y:Int,touch:Boolean=false,channels:Boolean=true) {
            add(if(touch)CanvasKind.TOUCH else CanvasKind.DPAD,1,y,4,4);sides(y,channels)
        }
        when(this) {
            CLASSIC,TOUCH,STREAMING -> {
                val start=if(favorites(0))1 else 0
                pad(start,touch=this==TOUCH,channels=this!=STREAMING)
                group(CanvasKind.NAVIGATION,0,start+4,4)
                if(this==STREAMING)key(Command.APPS,4,start+4,2) else add(CanvasKind.TV,4,start+4)
                group(CanvasKind.PLAYBACK,0,start+5,6)
                add(CanvasKind.KEYBOARD,0,start+6,3)
                key(if(this==STREAMING)Command.MENU else Command.INPUT,3,start+6,3)
            }
            MINIMAL -> { pad(0,channels=false);group(CanvasKind.NAVIGATION,0,4,6) }
            BIG_BUTTONS -> {
                add(CanvasKind.DPAD,0,0,6,6);group(CanvasKind.NAVIGATION,0,6,6)
                row(listOf(Command.VOLUME_DOWN,Command.MUTE,Command.VOLUME_UP),7)
                group(CanvasKind.PLAYBACK,0,8,6)
            }
            CHANNELS -> {
                add(CanvasKind.NUMBERS,0,0,6,4)
                row(listOf(Command.GUIDE,Command.INFO,Command.INPUT),4)
                pad(5);group(CanvasKind.NAVIGATION,0,9,4);add(CanvasKind.TV,4,9)
                group(CanvasKind.PLAYBACK,0,10,6)
            }
            NUMBERS_SIDE -> {
                add(CanvasKind.NUMBERS,0,0);add(CanvasKind.TOUCH,3,0,3,4)
                group(CanvasKind.NAVIGATION,0,4,4);add(CanvasKind.TV,4,4)
                row(listOf(Command.VOLUME_DOWN,Command.MUTE,Command.VOLUME_UP),5)
                group(CanvasKind.PLAYBACK,0,6,6)
            }
            FULL -> {
                row(listOf(Command.INPUT,Command.SETTINGS,Command.APPS),0)
                AndroidTvProtocol.digits.drop(1).chunked(3).forEachIndexed { y,keys -> row(keys,y+1) }
                key(Command.DELETE,0,4,2);key(Command.ZERO,2,4,2);add(CanvasKind.TV,4,4)
                pad(5)
                row(listOf(Command.BACK,Command.HOME,Command.MENU),9)
                group(CanvasKind.PLAYBACK,0,10,6)
                row(listOf(Command.MUTE,Command.GUIDE,Command.INFO),11)
                // Mute already has its physical key in the bottom row of this layout.
                list.removeAll { it.command==Command.MUTE && it.y<11 }
                add(CanvasKind.KEYBOARD,0,12,6)
            }
        }
        return RemoteCanvas(list,rows=24).removeEmptyRows().also { check(it.valid()) }
    }
}

/** Saved independently from a TV: app metadata travels with the layout, pairing data never does. */
data class CanvasPreset(val id:String=UUID.randomUUID().toString(),val name:String,val canvas:RemoteCanvas,val apps:List<AppShortcut>) {
    fun valid()=id.length in 1..80 && name.isNotBlank() && name.length<=32 && name.none { it.isISOControl() } && canvas.valid() && canvas.items.isNotEmpty() && apps.size<=8 && apps.all { it.isValid() } && apps.map { it.url }.distinct().size==apps.size && canvas.items.filter { it.kind==CanvasKind.APP }.all { item -> apps.any { it.url==item.app } }
    fun forDevice(caps:Capabilities)=canvas.copy(items=canvas.items.filter { it.supported(caps,apps) },enabled=true).compacted()
}
internal object CanvasPresetCodec {
    const val LIMIT=12
    fun encode(items:List<CanvasPreset>):String {
        require(items.size<=LIMIT && items.all { it.valid() } && items.map { it.id }.distinct().size==items.size)
        return JSONArray(items.map { JSONObject().put("id",it.id).put("name",it.name).put("canvas",JSONObject(it.canvas.encode())).put("apps",JSONArray(ShortcutCodec.encode(it.apps))) }).toString()
    }
    fun decode(raw:String?):List<CanvasPreset> = runCatching {
        if(raw==null || raw.length>500000)return emptyList()
        val array=JSONArray(raw);require(array.length()<=LIMIT)
        (0 until array.length()).mapNotNull { n -> runCatching {
            val o=array.getJSONObject(n)
            val appArray=o.getJSONArray("apps");require(appArray.length()<=8)
            val apps=ShortcutCodec.decode(appArray.toString());require(apps.size==appArray.length())
            CanvasPreset(o.getString("id"),o.getString("name"),RemoteCanvas.decode(o.getJSONObject("canvas").toString()) ?: return@mapNotNull null,apps).takeIf { it.valid() }
        }.getOrNull() }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
