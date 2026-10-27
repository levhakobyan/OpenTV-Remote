package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class RemoteControlsTest {
    @Test fun okNativeHoldUsesOneDownAndReleaseInsteadOfShortPresses() {
        assertEquals(setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK),AndroidTvProtocol.heldCommands)
        val down=Wire.parse(AndroidTvProtocol.key(Command.OK,KeyDirection.DOWN)).nested(10)
        val up=Wire.parse(AndroidTvProtocol.key(Command.OK,KeyDirection.UP)).nested(10)
        assertEquals(23,down.num(1));assertEquals(1,down.num(2))
        assertEquals(23,up.num(1));assertEquals(2,up.num(2))
    }
    @Test fun existingNavigationEncodingRemainsCompatible() {
        assertArrayEquals(byteArrayOf(0x52,4,8,23,16,3),AndroidTvProtocol.key(Command.OK))
        assertEquals(4,Wire.parse(AndroidTvProtocol.key(Command.BACK)).nested(10).num(1))
        assertEquals(3,Wire.parse(AndroidTvProtocol.key(Command.HOME)).nested(10).num(1))
    }
    @Test fun keypadSendsAndroidDigitKeysInNumericOrder() {
        assertEquals((7..16).toList(),AndroidTvProtocol.digits.map { Wire.parse(AndroidTvProtocol.key(it)).nested(10).num(1) })
    }
    @Test fun tvAndAppDrawerUseMultibyteAndroidKeyCodes() {
        assertArrayEquals(byteArrayOf(0x52,5,8,0xaa.toByte(),1,16,3),AndroidTvProtocol.key(Command.TV))
        assertEquals(178,Wire.parse(AndroidTvProtocol.key(Command.INPUT)).nested(10).num(1))
        assertEquals(284,Wire.parse(AndroidTvProtocol.key(Command.APPS)).nested(10).num(1))
        assertEquals(166,Wire.parse(AndroidTvProtocol.key(Command.CHANNEL_UP)).nested(10).num(1))
    }
    @Test fun featureNegotiationDoesNotExposePowerVolumeOrLinksWithoutFlags() {
        val keysOnly=AndroidTvProtocol.capabilities(2)
        assertTrue(keysOnly.commands.containsAll(AndroidTvProtocol.digits))
        assertFalse(Command.POWER in keysOnly.commands);assertFalse(Command.VOLUME_UP in keysOnly.commands)
        assertFalse(keysOnly.apps);assertFalse(keysOnly.text)
        assertTrue(AndroidTvProtocol.capabilities(0).commands.isEmpty())
        val all=AndroidTvProtocol.capabilities(2 or 4 or 32 or 64 or 512)
        assertTrue(all.text && all.apps);assertTrue(Command.POWER in all.commands && Command.MUTE in all.commands)
    }
    @Test fun shortcutUsesRemoteV2Field90AndPreservesUrl() {
        val message=Wire.parse(AndroidTvProtocol.app(AppShortcut.defaults.first()))
        assertEquals(90,message.single().number)
        assertEquals("https://www.youtube.com/",String(message.nested(90).single().bytes!!))
    }
    @Test fun shortcutsRejectExecutableUrlsCredentialsAndMalformedData() {
        listOf("javascript:alert(1)","intent://youtube/#Intent;end","file:///sdcard/a","http://youtube.com","https://user:password@youtube.com","https://youtube.com:8443/","https:///missing","https://you tube.com").forEach {
            val shortcut=AppShortcut("App",it);assertFalse(it,shortcut.isValid());assertThrows(IllegalArgumentException::class.java) { AndroidTvProtocol.app(shortcut) }
        }
        assertFalse(AppShortcut("", "https://youtube.com").isValid())
        assertFalse(AppShortcut("Bad\nname", "https://youtube.com").isValid())
        assertFalse(AppShortcut("x".repeat(25),"https://youtube.com").isValid())
    }
    @Test fun validCustomLinksAndBuiltinsCanBeSent() {
        assertTrue(AppShortcut.defaults.all { it.isValid() })
        assertTrue(AppShortcut("My channel","https://www.youtube.com/watch?v=test").isValid())
    }
}

class TvKeyboardRequestTest {
    @Test fun explicitTvImeRequestCarriesFieldCounterWithoutRetainingTvText() {
        val status=Wire.message(Wire.int(1,42),Wire.string(2,"private TV text"),Wire.int(3,3),Wire.int(4,3))
        val message=Wire.parse(Wire.bytes(22,Wire.bytes(2,status)))
        assertEquals(TvKeyboardRequest(9,42),AndroidTvProtocol.keyboardRequest(message,9))
    }
    @Test fun ordinaryAppOrCounterMessagesDoNotPopUpPhoneKeyboard() {
        assertNull(AndroidTvProtocol.keyboardRequest(Wire.parse(Wire.bytes(20,Wire.int(1,1))),1))
        assertNull(AndroidTvProtocol.keyboardRequest(Wire.parse(Wire.bytes(21,Wire.int(1,4))),2))
        assertNull(AndroidTvProtocol.keyboardRequest(Wire.parse(Wire.bytes(22,byteArrayOf())),3))
    }
    @Test fun noSelectionImeStatusIsIgnoredWithoutDroppingConnection() {
        val negative=ByteArray(10) { if(it==9)1 else 0xff.toByte() }
        val status=Wire.message(Wire.int(1,4),Wire.varint(24)+negative,Wire.varint(32)+negative)
        val message=Wire.parse(Wire.bytes(22,Wire.bytes(2,status)))
        assertNull(AndroidTvProtocol.keyboardRequest(message,1))
    }
    @Test fun selectableShortcutIconDoesNotChangeAppLinkTransport() {
        val shortcut=AppShortcut("Music","https://music.youtube.com/",ShortcutIcon.MUSIC)
        assertTrue(shortcut.isValid())
        assertEquals(shortcut.url,String(Wire.parse(AndroidTvProtocol.app(shortcut)).nested(90).single().bytes!!))
    }
}
