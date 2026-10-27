package com.luma.remote.data

import com.luma.remote.protocol.RemoteCanvas
import org.json.JSONObject

/** Remove only explicitly retired block-editor fields once a valid canvas owns the layout. */
object LayoutStorageCleanup {
    private val retired=setOf("preset","size","order","hidden","gestures","volumeRight","blockSizes","fitScreen")
    fun clean(raw:String?,canvas:RemoteCanvas?):String? {
        if(raw==null)return null
        val json=runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        var changed=false
        if(canvas?.enabled==true)retired.forEach { key -> if(json.has(key)) { json.remove(key);changed=true } }
        else if(json.has("fitScreen") && !json.optBoolean("fitScreen",true)) { json.put("fitScreen",true);changed=true }
        if(!changed)return raw
        return if(json.length()==0)null else json.toString()
    }
}
