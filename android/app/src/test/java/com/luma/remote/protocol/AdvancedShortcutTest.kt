package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class AdvancedShortcutTest {
    @Test fun packageBuildsOnlyFixedLaunchUriAndFrame() {
        val app=AppShortcut("Kodi","org.xbmc.kodi",kind=ShortcutKind.PACKAGE)
        assertTrue(app.isValid());assertEquals("market://launch?id=org.xbmc.kodi",app.launchUri())
        assertEquals(app.launchUri(),String(Wire.parse(AndroidTvProtocol.app(app)).nested(90).first { it.number==1 }.bytes!!))
        listOf("org.xbmc.kodi&other=1","org.xbmc.kodi#Intent;end","org..kodi","kodi","../org.kodi","org.1invalid","org.kodi\n").forEach { assertFalse(it,app.copy(url=it).isValid()) }
    }
    @Test fun acceptsPublisherDeepLinksButNeverRawIntentsFilesOrScripts() {
        val app=AppShortcut("Album","spotify:album:12345",kind=ShortcutKind.DEEP_LINK)
        assertTrue(app.isValid());assertEquals(app.url,app.launchUri())
        listOf("intent://app#Intent;end","javascript:alert(1)","file:///sdcard/a","content://settings","data:text/plain,a","market://launch?id=org.app","https://example.com","http://example.com","spotify:","spotify:user:bad%0Atext").forEach { assertFalse(it,app.copy(url=it).isValid()) }
        assertTrue(app.copy(url="plex://server/library/1").isValid())
    }
    @Test fun savedAdvancedTargetsAndIconsRoundTripAndLegacyHttpsSurvives() {
        val apps=listOf(AppShortcut("Kodi","org.xbmc.kodi",ShortcutIcon.TV,ShortcutKind.PACKAGE),AppShortcut("Music","spotify:album:123",ShortcutIcon.MUSIC,ShortcutKind.DEEP_LINK))+AppShortcut.defaults
        assertEquals(apps,ShortcutCodec.decode(ShortcutCodec.encode(apps)))
        assertEquals(listOf(AppShortcut.defaults.first()),ShortcutCodec.decode("""[{"name":"YouTube","url":"https://www.youtube.com/"}]"""))
        assertTrue(ShortcutCodec.decode("[]").isEmpty())
    }
    @Test fun tvMappingRetainsAdvancedShortcutTargetWithoutWideningIntents() {
        val layout=RemoteLayout(tvAction=TvAction.APP,tvApp="org.xbmc.kodi",tvConfigured=true)
        assertEquals(layout,RemoteLayout.decode(layout.encode()))
        assertNull(layout.copy(tvApp="intent://bad").normalized().tvApp)
    }
}
