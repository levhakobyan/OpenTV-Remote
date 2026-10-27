package com.luma.remote.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal object SamsungProbe {
    val http=OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun read(response:Response):SamsungInfo {
        check(response.isSuccessful)
        val body=response.body ?: error("Missing device information")
        val raw=body.byteStream().use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(out.size()<=65536) { val n=input.read(buffer,0,minOf(buffer.size,65537-out.size()));if(n<0)break;out.write(buffer,0,n) }
            out.toByteArray()
        }
        require(raw.size<=65536)
        return SamsungProtocol.info(String(raw,Charsets.UTF_8))
    }
    fun legacyInfo(host:String,client:OkHttpClient=http,port:Int=8001,hostAllowed:(String)->Boolean=LocalAddress::valid):SamsungInfo?=runCatching {
        require(hostAllowed(host))
        client.newCall(Request.Builder().url("http://$host:$port/api/v2/").build()).execute().use(::read)
    }.getOrNull()
}
