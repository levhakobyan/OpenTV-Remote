package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CanvasPresetsTest {
    private val full=AndroidTvProtocol.capabilities(622)
    /** Runtime snapshots shared with Swift: IDs are random; key codes, geometry and app targets are contracts. */
    @Test fun builtInTemplatesMatchSharedCrossPlatformFixtures() {
        val fixtureFile=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it,"shared/canvas-template-fixtures.json") }.first { it.isFile }
        val fixture=JSONObject(fixtureFile.readText())
        assertEquals(1,fixture.getInt("version"))
        val definitions=fixture.getJSONArray("templates")
        assertEquals(CanvasTemplate.entries.map { it.name },(0 until definitions.length()).map { definitions.getJSONObject(it).getString("android") })
        definitions.forEachObject { definition ->
            val template=CanvasTemplate.valueOf(definition.getString("android"))
            assertEquals(definition.getString("title"),template.title)
            assertEquals(definition.getString("detail"),template.description)
        }
        val apps=fixture.getJSONArray("apps").let { values -> (0 until values.length()).map { n ->
            values.getJSONObject(n).let { AppShortcut(it.getString("name"),it.getString("target")) }
        } }
        val actual=JSONObject()
        fixture.getJSONArray("profiles").forEachObject { profile ->
            val codes=profile.getJSONArray("commands").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            val caps=Capabilities(Command.entries.filter { it.androidKey in codes }.toSet(),profile.getBoolean("text"),profile.getBoolean("pointer"),profile.getBoolean("apps"),profile.getBoolean("scroll"))
            assertEquals("Every fixture command must map exactly",codes,caps.commands.map { it.androidKey }.toSet())
            assertEquals(codes.size,caps.commands.size)
            if(profile.getString("id")=="android622")assertEquals(AndroidTvProtocol.capabilities(622),caps)
            for(count in 0..2)for(hand in HandPreference.entries)for(index in 0 until definitions.length()) {
                val definition=definitions.getJSONObject(index)
                val template=CanvasTemplate.valueOf(definition.getString("android"))
                val selectedApps=apps.take(count)
                val canvas=template.create(caps,selectedApps,hand)
                assertTrue(canvas.valid())
                assertTrue(canvas.items.all { it.supported(caps,selectedApps) })
                val values=listOf("rows=${canvas.rows}")+canvas.items.sortedWith(compareBy({it.y},{it.x},{it.kind.name.lowercase()})).map {
                    listOf(it.kind.name.lowercase(),it.x,it.y,it.w,it.h,it.command?.androidKey ?: "-",it.app ?: "-").joinToString("|")
                }
                actual.put("${profile.getString("id")}/apps$count/${hand.name.lowercase()}/${definition.getString("ios")}",JSONArray(values))
            }
        }
        System.getenv("LUMA_CANVAS_SNAPSHOT_OUTPUT")?.let { File(it).writeText(JSONObject().put("cases",actual).toString(2)+"\n") }
        val expected=fixture.getJSONObject("cases")
        assertEquals("Fixture coverage must include every generated case",actual.keySet(),expected.keySet())
        for(key in actual.keySet())assertEquals(key,expected.getJSONArray(key).toString(),actual.getJSONArray(key).toString())
    }
    private fun JSONArray.forEachObject(body:(JSONObject)->Unit) { for(n in 0 until length())body(getJSONObject(n)) }
    @Test fun templatesAreValidAndOnlyUseSupportedFeatures() {
        val cases=listOf(full,Capabilities(setOf(Command.BACK,Command.VOLUME_UP)),Capabilities(emptySet(),pointer=true),Capabilities())
        CanvasTemplate.entries.forEach { template -> cases.forEach { caps -> HandPreference.entries.forEach { hand ->
            val canvas=template.create(caps,AppShortcut.defaults,hand)
            assertTrue(template.name,canvas.valid())
            assertTrue(template.name,canvas.items.all { it.supported(caps,AppShortcut.defaults) })
            assertEquals(canvas.contentRows,canvas.rows)
            assertFalse(canvas.items.any { it.kind==CanvasKind.DEVICE })
        } } }
    }
    @Test fun handednessChangesRailsWithoutReversingNumbersOrNavigation() {
        val right=CanvasTemplate.FULL.create(full,AppShortcut.defaults,HandPreference.RIGHT)
        val left=CanvasTemplate.FULL.create(full,AppShortcut.defaults,HandPreference.LEFT)
        assertEquals(0,right.items.single { it.kind==CanvasKind.VOLUME }.x)
        assertEquals(5,left.items.single { it.kind==CanvasKind.VOLUME }.x)
        AndroidTvProtocol.digits.forEach { digit -> assertEquals(right.items.single { it.command==digit }.x,left.items.single { it.command==digit }.x) }
        val saved=CanvasPreset(name="My full remote",canvas=right,apps=emptyList())
        assertEquals(right,saved.forDevice(full))
    }
    @Test fun presetsAreDistinctAndMinimalContainsOnlyDailyEssentials() {
        val minimal=CanvasTemplate.MINIMAL.create(full,AppShortcut.defaults)
        assertEquals(setOf(CanvasKind.DPAD,CanvasKind.VOLUME,CanvasKind.NAVIGATION,CanvasKind.KEY),minimal.items.map { it.kind }.toSet())
        assertEquals(Command.MUTE,minimal.items.single { it.kind==CanvasKind.KEY }.command)
        val streaming=CanvasTemplate.STREAMING.create(full,AppShortcut.defaults)
        assertTrue(streaming.items.any { it.kind==CanvasKind.APP })
        assertTrue(streaming.items.any { it.kind==CanvasKind.PLAYBACK })
        assertFalse(streaming.items.any { it.kind in setOf(CanvasKind.NUMBERS,CanvasKind.CHANNELS) })
        val tv=CanvasTemplate.CHANNELS.create(full,AppShortcut.defaults)
        assertTrue(tv.items.any { it.kind==CanvasKind.NUMBERS });assertTrue(tv.items.any { it.command==Command.GUIDE })
        val fullRemote=CanvasTemplate.FULL.create(full,AppShortcut.defaults)
        assertTrue(fullRemote.items.any { it.command==Command.MENU });assertTrue(fullRemote.items.any { it.command==Command.SETTINGS })
        assertEquals(1,fullRemote.items.count { it.command==Command.MUTE })
    }
    @Test fun customLayoutRoundTripsWithItsActualAppTargetAndGeometry() {
        val app=AppShortcut("Kodi","org.xbmc.kodi",ShortcutIcon.TV,ShortcutKind.PACKAGE)
        val canvas=RemoteCanvas(emptyList()).add(CanvasKind.APP,app=app.url)!!.add(CanvasKind.CHANNELS)!!
        val preset=CanvasPreset(name="My TV",canvas=canvas,apps=listOf(app))
        val restored=CanvasPresetCodec.decode(CanvasPresetCodec.encode(listOf(preset))).single()
        assertEquals(preset,restored)
        assertEquals(canvas,restored.forDevice(full))
        val filtered=restored.forDevice(Capabilities(setOf(Command.BACK)))
        assertTrue(filtered.items.isEmpty());assertTrue(filtered.valid());assertEquals(1,filtered.rows)
    }
    @Test fun malformedPresetsAndMissingTargetsDoNotBecomeWorkingControls() {
        assertTrue(CanvasPresetCodec.decode("bad").isEmpty())
        val canvas=RemoteCanvas(emptyList()).add(CanvasKind.APP,app=AppShortcut.defaults.first().url)!!
        assertFalse(CanvasPreset(name="Broken",canvas=canvas,apps=emptyList()).valid())
        assertFalse(CanvasPreset(name=" ",canvas=canvas,apps=AppShortcut.defaults).valid())
        assertFalse(CanvasPreset(name="x".repeat(33),canvas=canvas,apps=AppShortcut.defaults).valid())
        assertFalse(CanvasPreset(name="Empty",canvas=RemoteCanvas(emptyList()),apps=emptyList()).valid())
        val preset=CanvasPreset(name="Good",canvas=canvas,apps=AppShortcut.defaults)
        assertTrue(CanvasPresetCodec.decode(CanvasPresetCodec.encode(listOf(preset)).replace("https://www.youtube.com/","javascript:bad")).isEmpty())
    }
    @Test fun oversizedOrDuplicateCollectionsCannotBeWritten() {
        val value=CanvasPreset(name="TV",canvas=CanvasTemplate.CLASSIC.create(full,emptyList()),apps=emptyList())
        assertThrows(IllegalArgumentException::class.java) { CanvasPresetCodec.encode(List(13) { value.copy(id="id$it") }) }
        assertThrows(IllegalArgumentException::class.java) { CanvasPresetCodec.encode(listOf(value,value)) }
    }
}
