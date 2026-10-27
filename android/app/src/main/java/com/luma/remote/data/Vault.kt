package com.luma.remote.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.luma.remote.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("luma-private", Context.MODE_PRIVATE)
    init { cleanRetiredDevices();cleanRetiredLayoutData() }
    private fun cleanRetiredDevices() {
        val (devices,keys)=RetiredDeviceCleanup.plan(prefs.getString("devices",null),prefs.all.keys)
        if(devices==null && keys.isEmpty())return
        val edit=prefs.edit()
        if(devices!=null)edit.putString("devices",devices)
        keys.forEach(edit::remove)
        check(edit.commit()) { "Could not finish retired device cleanup" }
    }
    private fun cleanRetiredLayoutData() {
        val edit=prefs.edit()
        prefs.all.forEach { (key,value) ->
            if(key.startsWith("canvas:") && value is String) {
                val canvas=RemoteCanvas.decode(value)
                if(canvas!=null && canvas!=canvas.compacted())edit.putString(key,canvas.compacted().encode())
            }
            if(key.startsWith("layout:") && value is String) {
                val id=key.removePrefix("layout:")
                val canvas=RemoteCanvas.decode(prefs.getString("canvas:$id",null))
                val cleaned=LayoutStorageCleanup.clean(value,canvas)
                if(cleaned!=value) { if(cleaned==null)edit.remove(key) else edit.putString(key,cleaned) }
            }
        }
        edit.apply()
    }
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized private fun aes(): SecretKey {
        (store.getKey("luma-vault",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder("luma-vault", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build()) }.generateKey()
    }
    fun secret(id: String): String? = prefs.getString("s:$id",null)?.let {
        val b = Base64.decode(it,Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE,aes(),GCMParameterSpec(128,b.copyOfRange(0,12))); String(doFinal(b.copyOfRange(12,b.size)),Charsets.UTF_8) }
    }
    fun removeSecret(id:String) { check(prefs.edit().remove("s:$id").commit()) }
    fun putSecret(id: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,aes())
        check(prefs.edit().putString("s:$id",Base64.encodeToString(c.iv+c.doFinal(value.toByteArray()),Base64.NO_WRAP)).commit())
    }
    @Synchronized fun identity(): KeyStore.PrivateKeyEntry {
        // Software RSA avoids opaque Android Keystore signing incompatibilities in TLS providers.
        // PKCS12 bytes are AES-GCM encrypted with the non-exportable Keystore vault key.
        val p12=KeyStore.getInstance("PKCS12")
        val saved=secret("tls-identity")
        if(saved!=null) p12.load(Base64.decode(saved,Base64.NO_WRAP).inputStream(),CharArray(0))
        else {
            p12.load(null,CharArray(0))
            val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject=org.bouncycastle.asn1.x500.X500Name("CN=OpenTV-Remote")
            val bc=org.bouncycastle.jce.provider.BouncyCastleProvider()
            val cert=org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(subject,BigInteger(128,java.security.SecureRandom()),Date(System.currentTimeMillis()-86400000),Date(System.currentTimeMillis()+315360000000L),subject,pair.public)
                .build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.private))
            val x509=org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().setProvider(bc).getCertificate(cert)
            p12.setKeyEntry("luma",pair.private,CharArray(0),arrayOf(x509))
            val out=java.io.ByteArrayOutputStream(); p12.store(out,CharArray(0))
            putSecret("tls-identity",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP))
        }
        return p12.getEntry("luma",KeyStore.PasswordProtection(CharArray(0))) as KeyStore.PrivateKeyEntry
    }
    fun saved(): List<TvDevice> = RetiredDeviceCleanup.devices(prefs.getString("devices",null))
    fun save(device: TvDevice) = write(saved().filter { it.id != device.id } + device)
    fun shortcuts(deviceId:String)=ShortcutCodec.decode(prefs.getString("apps:$deviceId",null))
    fun saveShortcuts(deviceId:String,shortcuts:List<AppShortcut>) {
        prefs.edit().putString("apps:$deviceId",ShortcutCodec.encode(shortcuts)).apply()
    }
    fun layout(deviceId:String)=RemoteLayout.decode(prefs.getString("layout:$deviceId",null))
    fun saveLayout(deviceId:String,layout:RemoteLayout) {
        val cleaned=LayoutStorageCleanup.clean(layout.encode(),canvas(deviceId))
        prefs.edit().putString("layout:$deviceId",cleaned).apply()
    }
    fun presets()=CanvasPresetCodec.decode(prefs.getString("canvas-presets",null))
    fun savePresets(items:List<CanvasPreset>) { prefs.edit().putString("canvas-presets",CanvasPresetCodec.encode(items)).apply() }
    fun canvas(deviceId:String)=RemoteCanvas.decode(prefs.getString("canvas:$deviceId",null))?.compacted()
    fun saveCanvas(deviceId:String,canvas:RemoteCanvas) {
        val compact=canvas.compacted()
        val edit=prefs.edit().putString("canvas:$deviceId",compact.encode())
        val old=prefs.getString("layout:$deviceId",null)
        val cleaned=LayoutStorageCleanup.clean(old,compact)
        if(cleaned!=old) { if(cleaned==null)edit.remove("layout:$deviceId") else edit.putString("layout:$deviceId",cleaned) }
        edit.apply()
    }
    fun forget(device: TvDevice) { write(saved().filter { it.id != device.id }); prefs.edit().remove("s:pin:${device.id}").remove("s:lg:${device.id}").remove("s:samsung-pin:${device.id}").remove("s:samsung-token:${device.id}").remove("s:samsung-mode:${device.id}").remove("apps:${device.id}").remove("layout:${device.id}").remove("canvas:${device.id}").apply() }
    private fun write(devices: List<TvDevice>) { prefs.edit().putString("devices",JSONArray(devices.map { JSONObject().put("id",it.id).put("name",it.name).put("host",it.host).put("platform",it.platform.name) }).toString()).apply() }
}
