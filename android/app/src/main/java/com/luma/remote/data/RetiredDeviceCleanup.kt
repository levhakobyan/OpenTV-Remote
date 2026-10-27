package com.luma.remote.data

import com.luma.remote.protocol.Platform
import com.luma.remote.protocol.TvDevice
import org.json.JSONArray

/** Narrow upgrade cleanup for the removed TV app; native pairing/layouts are untouched. */
internal object RetiredDeviceCleanup {
    fun devices(raw:String?):List<TvDevice> = runCatching {
        val entries=JSONArray(raw ?: "[]")
        (0 until entries.length()).mapNotNull { index -> runCatching {
            val o=entries.getJSONObject(index)
            TvDevice(o.getString("id"),o.getString("name"),o.getString("host"),Platform.valueOf(o.getString("platform")))
        }.getOrNull() }
    }.getOrDefault(emptyList())
    fun plan(raw:String?,keys:Set<String>):Pair<String?,Set<String>> {
        val entries=runCatching { JSONArray(raw ?: "[]") }.getOrNull()
        val ids=mutableSetOf<String>()
        val kept=JSONArray()
        if(entries!=null)for(i in 0 until entries.length()) {
            val entry=entries.optJSONObject(i)
            val id=entry?.optString("id").orEmpty()
            if(entry?.optString("platform")=="COMPANION" || id.startsWith("companion:"))ids+=id
            else kept.put(entries.get(i))
        }
        val removed=keys.filter { key ->
            key.startsWith("s:companion-pin:") || key.startsWith("s:companion-token:") ||
                listOf("apps:","layout:","canvas:").any { prefix -> key.startsWith(prefix+"companion:") || ids.any { key==prefix+it } }
        }.toSet()
        return (if(ids.isNotEmpty())kept.toString() else null) to removed
    }
}
