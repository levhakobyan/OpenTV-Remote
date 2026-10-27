package com.luma.remote.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

internal fun samsungMetadata(text:Boolean=true,remote:Boolean=true)="""{"type":"Samsung SmartTV","id":"fixture-tv","name":"Samsung fixture","device":{"id":"fixture-tv","type":"Samsung SmartTV","ImeSyncedSupport":"$text"},"isSupport":"{\"remote_available\":\"$remote\",\"remote_fourDirections\":\"true\"}"}"""

class SamsungProtocolTest {
    @Test fun keyVectorsDoNotInventUnsupportedTogglesOrAppLaunch() {
        val expected=mapOf(Command.OK to "KEY_ENTER",Command.BACK to "KEY_RETURN",Command.INPUT to "KEY_SOURCE",Command.VOLUME_UP to "KEY_VOLUP",Command.CHANNEL_DOWN to "KEY_CHDOWN",Command.POWER to "KEY_POWER")
        expected.forEach { (command,key) -> val o=JSONObject(SamsungProtocol.key(command));assertEquals("ms.remote.control",o.getString("method"));val p=o.getJSONObject("params");assertEquals("Click",p.getString("Cmd"));assertEquals(key,p.getString("DataOfCmd")) }
        AndroidTvProtocol.digits.forEachIndexed { n,c -> assertEquals("KEY_$n",JSONObject(SamsungProtocol.key(c)).getJSONObject("params").getString("DataOfCmd")) }
        assertThrows(IllegalStateException::class.java) { SamsungProtocol.key(Command.PLAY_PAUSE) }
        assertThrows(IllegalStateException::class.java) { SamsungProtocol.key(Command.APPS) }
    }
    @Test fun metadataGatesTextAndRejectsOtherDevicesDisabledRemotesAndOversizedInputs() {
        val info=SamsungProtocol.info(samsungMetadata());assertTrue(info.capabilities.text);assertFalse(info.capabilities.pointer);assertFalse(info.capabilities.apps)
        assertEquals("samsung:fixture-tv",info.device("192.168.1.2").id)
        assertFalse(SamsungProtocol.info(samsungMetadata(text=false)).capabilities.text)
        assertThrows(IllegalArgumentException::class.java) { SamsungProtocol.info(samsungMetadata(remote=false)) }
        assertThrows(IllegalArgumentException::class.java) { SamsungProtocol.info(samsungMetadata().replace("Samsung SmartTV","Samsung Fridge")) }
        assertThrows(IllegalArgumentException::class.java) { SamsungProtocol.info("x".repeat(65537)) }
        val o=JSONObject(samsungMetadata()).put("isSupport",JSONObject().put("remote_fourDirections",false))
        assertFalse(Command.UP in SamsungProtocol.info(o.toString()).capabilities.commands)
    }
    @Test fun unicodeTextIsEncodedAsDataAndBounded() {
        val text="Բարեւ \"TV\" & test"
        val packets=SamsungProtocol.text(text)
        assertEquals("custom.remote.textReceived",JSONObject(packets[0]).getJSONObject("params").getString("event"))
        val p=JSONObject(packets[1]).getJSONObject("params");assertEquals("SendInputString",p.getString("TypeOfRemote"))
        assertEquals(text,String(Base64.getDecoder().decode(p.getString("Cmd")),Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { SamsungProtocol.text("x".repeat(1001)) }
        assertThrows(IllegalArgumentException::class.java) { SamsungProtocol.text("a\u0000b") }
        assertNull(SamsungProtocol.approvalToken(JSONObject().put("token",JSONObject.NULL)))
        assertEquals("12345",SamsungProtocol.approvalToken(JSONObject().put("token",12345)))
        assertThrows(IllegalArgumentException::class.java) { SamsungProtocol.approvalToken(JSONObject().put("token",JSONObject())) }
        assertNull(SamsungProtocol.token("bad\ntoken"));assertNull(SamsungProtocol.token("x".repeat(1025)))
    }
}
