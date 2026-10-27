package com.luma.remote.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CanvasKind(val title:String,val minW:Int=1,val minH:Int=1) {
    KEY("Button"), DPAD("D-pad",4,4), TOUCH("Touchpad",3,3), APP("App shortcut",2,1), KEYBOARD("Keyboard",2,1), NUMBERS("Number pad",3,4), TV("TV action",2,1), DEVICE("TV name",2,1),
    VOLUME("Volume rocker",1,2), CHANNELS("Channel rocker",1,2), PLAYBACK("Playback controls",3,1), NAVIGATION("Back & Home",2,1);
    val keys:List<Command> get()=when(this) {
        VOLUME -> listOf(Command.VOLUME_UP,Command.VOLUME_DOWN)
        CHANNELS -> listOf(Command.CHANNEL_UP,Command.CHANNEL_DOWN)
        PLAYBACK -> listOf(Command.REWIND,Command.PLAY_PAUSE,Command.FORWARD)
        NAVIGATION -> listOf(Command.BACK,Command.HOME)
        else -> emptyList()
    }
    val vertical:Boolean get()=this==VOLUME || this==CHANNELS
}
data class CanvasItem(val id:String=UUID.randomUUID().toString(),val kind:CanvasKind,val x:Int,val y:Int,val w:Int=kind.minW,val h:Int=kind.minH,val command:Command?=null,val app:String?=null) {
    fun overlaps(other:CanvasItem)=x<other.x+other.w && x+w>other.x && y<other.y+other.h && y+h>other.y
    fun valid(rows:Int)=id.isNotBlank() && id.length<=80 && x in 0..5 && y in 0 until rows && w in kind.minW..6 && h in kind.minH..rows && x+w<=6 && y+h<=rows && (kind!=CanvasKind.KEY || command!=null) && (kind!=CanvasKind.APP || app!=null && ShortcutKind.entries.any { AppShortcut("App",app,kind=it).isValid() })
    fun supported(caps:Capabilities,apps:List<AppShortcut>)=when(kind) {
        CanvasKind.KEY -> command in caps.commands
        CanvasKind.DPAD -> GestureNavigation.supported(caps)
        CanvasKind.TOUCH -> caps.pointer || GestureNavigation.supported(caps)
        CanvasKind.APP -> caps.apps && apps.any { it.url==app }
        CanvasKind.KEYBOARD -> caps.text
        CanvasKind.NUMBERS -> AndroidTvProtocol.digits.all { it in caps.commands }
        CanvasKind.TV -> Command.TV in caps.commands || Command.INPUT in caps.commands || caps.apps
        CanvasKind.DEVICE -> true
        else -> kind.keys.isNotEmpty() && kind.keys.all { it in caps.commands }
    }
}
data class RemoteCanvas(val items:List<CanvasItem>,val rows:Int=10,val enabled:Boolean=true,val fit:Boolean=true) {
    val contentRows:Int get()=maxOf(1,items.maxOfOrNull { it.y+it.h } ?: 0)
    fun compacted()=copy(rows=contentRows,fit=true)
    val emptyRowCount:Int get()=(0 until contentRows).count { y -> items.none { y in it.y until it.y+it.h } }
    fun removeEmptyRows():RemoteCanvas {
        val used=(0 until contentRows).filter { y -> items.any { y in it.y until it.y+it.h } }
        return copy(items=items.map { it.copy(y=used.indexOf(it.y)) },rows=maxOf(1,used.size),fit=true)
    }
    fun valid()=rows in 1..24 && items.size<=48 && items.map { it.id }.distinct().size==items.size && items.all { it.valid(rows) } && items.indices.none { i -> items.drop(i+1).any { items[i].overlaps(it) } }
    fun place(id:String,x:Int,y:Int,w:Int?=null,h:Int?=null):RemoteCanvas? {
        val original=items.firstOrNull { it.id==id } ?: return null
        val moved=original.copy(x=x,y=y,w=w ?: original.w,h=h ?: original.h)
        if(!moved.valid(24))return null
        val hits=items.filter { it.id!=id && moved.overlaps(it) }
        val next=when {
            hits.isEmpty() -> items.map { if(it.id==id)moved else it }
            hits.size==1 && hits[0].x==x && hits[0].y==y && hits[0].w==original.w && hits[0].h==original.h && moved.w==original.w && moved.h==original.h -> items.map { when(it.id) { id -> moved;hits[0].id -> hits[0].copy(x=original.x,y=original.y);else -> it } }
            else -> return null
        }
        return copy(items=next,rows=24).takeIf { it.valid() }?.compacted()
    }
    /** Resize in place, preserving group shapes and cascading collisions downward atomically. */
    fun resizePushingDown(id:String,w:Int?=null,h:Int?=null):RemoteCanvas? {
        if(!valid())return null
        val original=items.firstOrNull { it.id==id } ?: return null
        val resized=original.copy(w=w ?: original.w,h=h ?: original.h)
        if(!resized.valid(24))return null
        val placed=mutableListOf(resized)
        for(item in items.filter { it.id!=id }.sortedBy { it.y }) {
            if(item.y<original.y) {
                // Growing sideways must not reorder controls that start above the selected item.
                if(item.overlaps(resized))return null
                placed+=item;continue
            }
            // Keep vertical order in intersecting columns, even if an earlier control
            // moves far enough to pass over this item's original position.
            val below=placed.filter { item.x<it.x+it.w && item.x+item.w>it.x }.maxOfOrNull { it.y+it.h } ?: item.y
            val next=item.copy(y=maxOf(item.y,below))
            if(!next.valid(24))return null
            placed+=next
        }
        val byId=placed.associateBy { it.id }
        return copy(items=items.map { byId.getValue(it.id) },rows=24).takeIf { it.valid() }?.compacted()
    }
    fun add(kind:CanvasKind,command:Command?=null,app:String?=null):RemoteCanvas? {
        if(items.size>=48)return null
        for(y in 0 until 24)for(x in 0 until 6) {
            val item=CanvasItem(kind=kind,x=x,y=y,command=command,app=app)
            if(item.valid(24) && items.none { it.overlaps(item) })return copy(items=items+item,rows=24).takeIf { it.valid() }?.compacted()
        }
        return null
    }
    /** Join touching keys in their existing group shape. A selection limits the edit to its group. */
    fun joinMatchingButtons(selectedId:String?=null):RemoteCanvas {
        var result=this
        CanvasKind.entries.filter { it.keys.isNotEmpty() }.forEach { kind ->
            val starts=result.items.filter { it.kind==CanvasKind.KEY && it.command==kind.keys.first() }
            starts.forEach candidate@ { first ->
                val group=mutableListOf(first)
                kind.keys.drop(1).forEach { command ->
                    val last=group.last()
                    result.items.firstOrNull { next -> next.kind==CanvasKind.KEY && next.command==command &&
                        if(kind.vertical)next.x==last.x && next.w==last.w && next.y==last.y+last.h
                        else next.y==last.y && next.h==last.h && next.x==last.x+last.w
                    }?.let(group::add)
                }
                if(group.map { it.command }==kind.keys) {
                    val ids=group.map { it.id }.toSet()
                    if(selectedId!=null && selectedId !in ids)return@candidate
                    val joined=first.copy(kind=kind,w=if(kind.vertical)first.w else group.sumOf { it.w },h=if(kind.vertical)group.sumOf { it.h } else first.h,command=null)
                    val next=result.copy(items=result.items.mapNotNull { if(it.id==first.id)joined else if(it.id in ids)null else it })
                    if(next.valid())result=next
                }
            }
        }
        return result
    }
    fun split(id:String):RemoteCanvas? {
        val item=items.firstOrNull { it.id==id && it.kind.keys.isNotEmpty() } ?: return null
        val keys=item.kind.keys
        val parts=keys.mapIndexed { n,command ->
            val length=if(item.kind.vertical)item.h else item.w
            val start=length*n/keys.size;val end=length*(n+1)/keys.size
            CanvasItem(id=if(n==0)item.id else UUID.randomUUID().toString(),kind=CanvasKind.KEY,
                x=item.x+if(item.kind.vertical)0 else start,y=item.y+if(item.kind.vertical)start else 0,
                w=if(item.kind.vertical)item.w else end-start,h=if(item.kind.vertical)end-start else item.h,command=command)
        }
        return copy(items=items.flatMap { if(it.id==id)parts else listOf(it) }).takeIf { it.valid() }
    }
    fun encode():String { require(valid());return JSONObject().put("rows",rows).put("enabled",enabled).put("fit",fit).put("items",JSONArray(items.map { i -> JSONObject().put("id",i.id).put("kind",i.kind.name).put("x",i.x).put("y",i.y).put("w",i.w).put("h",i.h).put("command",i.command?.name).put("app",i.app) })).toString() }
    companion object {
        fun decode(raw:String?):RemoteCanvas?=runCatching {
            if(raw==null)return null
            val o=JSONObject(raw);val a=o.getJSONArray("items");require(a.length()<=48)
            RemoteCanvas((0 until a.length()).map { n -> val i=a.getJSONObject(n);CanvasItem(i.getString("id"),CanvasKind.valueOf(i.getString("kind")),i.getInt("x"),i.getInt("y"),i.getInt("w"),i.getInt("h"),Command.entries.firstOrNull { it.name==i.optString("command") },i.optString("app").takeIf { it.isNotBlank() && it!="null" }) },o.getInt("rows"),o.optBoolean("enabled",true),o.optBoolean("fit",true)).takeIf { it.valid() }
        }.getOrNull()
        fun preset(preset:LayoutPreset,caps:Capabilities,apps:List<AppShortcut>):RemoteCanvas {
            val list=mutableListOf<CanvasItem>()
            fun key(c:Command,x:Int,y:Int,w:Int=1,h:Int=1) { if(c in caps.commands)list+=CanvasItem(kind=CanvasKind.KEY,x=x,y=y,w=w,h=h,command=c) }
            fun widget(kind:CanvasKind,x:Int,y:Int,w:Int=kind.minW,h:Int=kind.minH,app:String?=null) { val i=CanvasItem(kind=kind,x=x,y=y,w=w,h=h,app=app);if(i.supported(caps,apps))list+=i }
            val channels=preset==LayoutPreset.CHANNELS
            if(channels)widget(CanvasKind.NUMBERS,0,0,6,4)
            else apps.take(2).forEachIndexed { n,a -> widget(CanvasKind.APP,n*3,0,3,1,a.url) }
            val start=if(channels)4 else 1
            widget(if(preset==LayoutPreset.TOUCH)CanvasKind.TOUCH else CanvasKind.DPAD,1,start,4,4)
            if(CanvasItem(kind=CanvasKind.VOLUME,x=0,y=0).supported(caps,apps))widget(CanvasKind.VOLUME,0,start+1)
            else { key(Command.VOLUME_UP,0,start+1);key(Command.VOLUME_DOWN,0,start+2) }
            key(Command.MUTE,0,start+3)
            if(CanvasItem(kind=CanvasKind.CHANNELS,x=0,y=0).supported(caps,apps))widget(CanvasKind.CHANNELS,5,start+1)
            else { key(Command.CHANNEL_UP,5,start+1);key(Command.CHANNEL_DOWN,5,start+2) }
            if(CanvasItem(kind=CanvasKind.NAVIGATION,x=0,y=0).supported(caps,apps))widget(CanvasKind.NAVIGATION,0,start+4,4,1)
            else { key(Command.BACK,0,start+4,2);key(Command.HOME,2,start+4,2) }
            widget(CanvasKind.TV,4,start+4)
            if(preset!=LayoutPreset.MINIMAL) {
                if(CanvasItem(kind=CanvasKind.PLAYBACK,x=0,y=0).supported(caps,apps))widget(CanvasKind.PLAYBACK,0,start+5,6,1)
                else { key(Command.REWIND,0,start+5,2);key(Command.PLAY_PAUSE,2,start+5,2);key(Command.FORWARD,4,start+5,2) }
            }
            widget(CanvasKind.KEYBOARD,0,start+6);key(Command.MENU,2,start+6,2);key(Command.INPUT,4,start+6,2)
            return RemoteCanvas(list,rows=if(channels)11 else 8).also { check(it.valid()) }
        }
    }
}

/** Runtime fitting never enlarges a grid row beyond its comfortable design size. */
object CanvasFit {
    fun rowHeight(cell:Double,available:Double,rows:Int,gap:Double):Double {
        val natural=cell.coerceIn(48.0,72.0)
        val fitted=(available-gap*(rows.coerceAtLeast(1)-1))/rows.coerceAtLeast(1)
        return fitted.coerceIn(48.0,natural)
    }
}
