package com.luma.remote.protocol

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Per-connection certificate pinning. An unknown peer aborts TLS before any HTTP/token is sent. */
internal class SamsungTrust(private val expected:String?) : X509TrustManager {
    @Volatile var observed:String?=null
        private set
    @Volatile var changed=false
        private set
    override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
    override fun checkClientTrusted(chain:Array<out X509Certificate>,authType:String) { throw CertificateException("Client trust unsupported") }
    override fun checkServerTrusted(chain:Array<out X509Certificate>,authType:String) {
        if(chain.isEmpty())throw CertificateException("Missing TV identity")
        val fingerprint=fingerprint(chain.first());observed=fingerprint
        if(expected==null)throw CertificateException("TV identity confirmation required")
        if(expected!=fingerprint) { changed=true;throw CertificateException("TV identity changed") }
    }
    fun socketFactory():SSLSocketFactory=SSLContext.getInstance("TLS").apply { init(null,arrayOf(this@SamsungTrust),null) }.socketFactory
    fun verifier(host:String)=HostnameVerifier { actual,session -> actual==host && expected!=null && runCatching { fingerprint(session.peerCertificates.first() as X509Certificate)==expected }.getOrDefault(false) }
    companion object {
        fun fingerprint(cert:X509Certificate)=MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }
}
