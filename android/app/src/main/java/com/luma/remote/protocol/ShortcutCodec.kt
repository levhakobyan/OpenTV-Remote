package com.luma.remote.protocol

import org.json.JSONArray
import org.json.JSONObject

internal object ShortcutCodec {
    fun decode(raw:String?):List<AppShortcut> = runCatching {
        if(raw==null)return AppShortcut.defaults
        val array=JSONArray(raw)
        (0 until minOf(array.length(),8)).map { i ->
            val o=array.getJSONObject(i)
            AppShortcut(o.getString("name"),o.getString("url"),
                ShortcutIcon.entries.firstOrNull { it.name==o.optString("icon") } ?: ShortcutIcon.AUTO,
                ShortcutKind.entries.firstOrNull { it.name==o.optString("kind") } ?: ShortcutKind.HTTPS)
        }.filter { it.isValid() }.distinctBy { it.url }
    }.getOrDefault(AppShortcut.defaults)
    fun encode(items:List<AppShortcut>):String {
        require(items.size<=8 && items.all { it.isValid() })
        return JSONArray(items.map { JSONObject().put("name",it.name).put("url",it.url).put("icon",it.icon.name).put("kind",it.kind.name) }).toString()
    }
}
