package com.luma.remote.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Detect native remote services without asking for a brand or model. */
internal object ManualTvDetection {
    suspend fun detect(
        host:String,
        open:suspend (Int)->Boolean,
        samsung:suspend ()->SamsungInfo?
    ):TvDevice?=coroutineScope {
        require(LocalAddress.valid(host))
        val metadata=async { samsung() }
        val android=async { open(6467) }
        val lg=async { open(3000) }
        val secure=async { open(8002) }
        val info=metadata.await()
        when {
            info!=null -> info.device(host)
            android.await() -> TvDevice("manual:ANDROID_TV:$host","Android TV",host,Platform.ANDROID_TV)
            lg.await() -> TvDevice("manual:LG:$host","LG webOS",host,Platform.LG)
            secure.await() -> TvDevice("samsung:$host","Samsung TV service",host,Platform.SAMSUNG)
            else -> null
        }
    }
}
