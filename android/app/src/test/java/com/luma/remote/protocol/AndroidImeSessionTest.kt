package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class AndroidImeSessionTest {
    @Test fun canonicalSharedVectorsMatchActualRangeAndWireEncoder() {
        val source=File("../shared/ime-edit-fixtures.json").takeIf {it.exists()} ?: File("../../shared/ime-edit-fixtures.json")
        val cases=JSONObject(source.readText()).getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val s=ready(c.getString("before"))
            s.local(s.snapshot.session,TvImeValue(c.getString("after"),c.getInt("selectionStart"),c.getInt("selectionEnd")),false)
            val edit=s.nextEdit()!!
            assertEquals(c.getString("name"),ImeReplacement(c.getInt("start"),c.getInt("end"),c.getString("replacement")),edit.replacement)
            assertEquals(c.getString("name"),c.getString("wireHex"),edit.bytes().joinToString("") {"%02x".format(it)})
        }
    }
    private fun status(text:String,start:Int=text.length,end:Int=start,counter:Int=42,label:String="Search"):ByteArray=Wire.message(Wire.int(1,counter),Wire.string(2,text),Wire.int(3,start),Wire.int(4,end),Wire.string(6,label))
    private fun show(text:String,start:Int=text.length,end:Int=start,counter:Int=42,label:String="Search")=Wire.parse(Wire.bytes(22,Wire.bytes(2,status(text,start,end,counter,label))))
    private fun update(text:String,start:Int=text.length,end:Int=start,counter:Int=43)=Wire.parse(Wire.bytes(20,Wire.bytes(2,status(text,start,end,counter))))
    private fun batch(ime:Int=7,counter:Int=42)=Wire.parse(Wire.bytes(21,Wire.message(Wire.int(1,ime),Wire.int(2,counter))))
    private fun ready(text:String="",start:Int=text.length,end:Int=start):AndroidImeSession=AndroidImeSession().apply {receive(batch());receive(show(text,start,end))}
    @Test fun rangeReplacementWireUsesUtf16InsteadOfChunkLengthMinusOne() {
        val s=ready("A😀BC",3,4);val session=s.snapshot.session
        assertTrue(s.local(session,TvImeValue("A😀ЖC",4),false))
        val e=s.nextEdit()!!
        assertEquals(ImeReplacement(3,4,"Ж"),e.replacement)
        val body=Wire.parse(e.bytes()).nested(21);val op=body.nested(3).nested(2)
        assertEquals(7,body.num(1));assertEquals(42,body.num(2));assertEquals(3,op.num(1));assertEquals(4,op.num(2))
        assertEquals("Ж",op.first {it.number==3}.bytes!!.toString(Charsets.UTF_8))
    }
    @Test fun deletionInRepeatedRunKeepsUserCaret() {
        val s=ready("aaaa",1,2);assertTrue(s.local(s.snapshot.session,TvImeValue("aaa",1),false))
        assertEquals(ImeReplacement(1,2,""),s.nextEdit()!!.replacement)
    }
    @Test fun clearSpacesPasteAutocorrectAndUnicodeAreRangeEdits() {
        for((before,after) in listOf("hello" to "", "" to "   ","a" to "Հայերեն русский 😀","teh fox" to "the fox")) {
            val s=ready(before);assertTrue(s.local(s.snapshot.session,TvImeValue(after),false))
            val edit=s.nextEdit()!!;val r=edit.replacement
            assertEquals(after,before.substring(0,r.start)+r.text+before.substring(r.end))
            assertTrue(ImeRanges.boundary(before,r.start));assertTrue(ImeRanges.boundary(before,r.end))
        }
    }
    @Test fun emojiDifferenceNeverSplitsSurrogatePairs() {
        assertEquals(ImeReplacement(0,2,"😁"),ImeRanges.difference("😀","😁"))
        assertFalse(TvImeValue("😀",1).valid());assertFalse(TvImeValue("\ud800").valid())
    }
    @Test fun oneInflightCoalescesNewInputAndOldEchoDoesNotOverwriteIt() {
        val s=ready();val session=s.snapshot.session
        s.local(session,TvImeValue("a"),false,1);val first=s.nextEdit()!!
        s.local(session,TvImeValue("abc"),false,2);assertNull(s.nextEdit())
        s.receive(update("a",counter=43));assertEquals("abc",s.snapshot.value!!.text)
        val second=s.nextEdit()!!;assertEquals(43,second.fieldCounter);assertEquals(ImeReplacement(1,1,"bc"),second.replacement)
        s.receive(update("a",counter=43));assertTrue(s.snapshot.pending);assertEquals("abc",s.snapshot.value!!.text)
        s.receive(update("abc",counter=44));assertFalse(s.snapshot.pending);assertNull(s.nextEdit())
        assertTrue(first.id<second.id)
    }
    @Test fun remoteOnlyChangesAndSelectionsDoNotEchoBack() {
        val s=ready("old");s.receive(update("TV changed",2,5));assertEquals(TvImeValue("TV changed",2,5),s.snapshot.value)
        assertNull(s.nextEdit())
    }
    @Test fun selectionOnlyIsLocalAndUsedByNextReplacementWithoutInventedOpcode() {
        val s=ready("abcd");s.local(s.snapshot.session,TvImeValue("abcd",1,3),false);assertNull(s.nextEdit())
        s.local(s.snapshot.session,TvImeValue("aXd",2),false)
        assertEquals(ImeReplacement(1,3,"X"),s.nextEdit()!!.replacement)
    }
    @Test fun validProvisionalTextIsLiveAndAutocorrectUsesConfirmedBase() {
        val s=ready();val session=s.snapshot.session
        s.local(session,TvImeValue("teh"),true);assertEquals(ImeReplacement(0,0,"teh"),s.nextEdit()!!.replacement)
        s.receive(update("teh",0,counter=43))
        assertEquals(3,s.snapshot.value!!.start);assertFalse(s.canEnter(session,1))
        s.local(session,TvImeValue("the "),false);assertEquals(ImeReplacement(1,3,"he "),s.nextEdit()!!.replacement)
    }
    @Test fun provisionalTypingCoalescesWithoutWaitingForSpaceAndCommitDoesNotDuplicate() {
        val s=ready("slow");val session=s.snapshot.session
        s.local(session,TvImeValue("slowA"),true,1);s.nextEdit()
        s.local(session,TvImeValue("slowABC"),true,2);assertNull(s.nextEdit())
        s.receive(update("slowA",counter=43));assertEquals("slowABC",s.snapshot.value!!.text)
        assertEquals(ImeReplacement(5,5,"BC"),s.nextEdit()!!.replacement)
        s.receive(update("slowABC",counter=44));assertFalse(s.snapshot.pending);assertFalse(s.canEnter(session,2))
        s.local(session,TvImeValue("slowABC"),false,3);assertNull(s.nextEdit());assertTrue(s.canEnter(session,3))
    }
    @Test fun unsolicitedTvChangeDuringCompositionPreservesDraftAndStopsLive() {
        val s=ready("same");val session=s.snapshot.session
        s.local(session,TvImeValue("sameX"),true)
        s.receive(update("TV changed",counter=43))
        assertEquals("sameX",s.snapshot.value!!.text);assertEquals(TvImeMode.CONFLICT,s.snapshot.mode);assertNull(s.nextEdit())
    }
    @Test fun confirmedProvisionalWordAllowsAuthoritativeTvChangeWithoutFeedback() {
        val s=ready();val session=s.snapshot.session
        s.local(session,TvImeValue("confirmed"),true,1);s.nextEdit();s.receive(update("confirmed",counter=43))
        assertFalse(s.canEnter(session,1))
        s.receive(update("Բարև мир 🙂",2,5,counter=44))
        assertEquals(TvImeMode.LIVE,s.snapshot.mode);assertEquals(TvImeValue("Բարև мир 🙂",2,5),s.snapshot.value)
        assertNull(s.nextEdit());assertTrue(s.canEnter(session,1))
    }
    @Test fun counterOnlyRefreshDoesNotResetCompositionLocalSelectionOrUiBasis() {
        val s=ready("abc",3);val session=s.snapshot.session;val basis=s.snapshot.externalVersion
        s.local(session,TvImeValue("abc",1),true,1,basis)
        s.receive(update("abc",3,counter=43))
        assertEquals(basis,s.snapshot.externalVersion);assertEquals(TvImeValue("abc",1),s.snapshot.value)
        assertFalse(s.canEnter(session,1));assertNull(s.nextEdit())
        s.local(session,TvImeValue("aXbc",2),true,2,basis)
        val edit=s.nextEdit()!!;assertEquals(43,edit.fieldCounter);assertEquals(ImeReplacement(1,1,"X"),edit.replacement)
    }
    @Test fun incompleteSurrogateDraftWaitsForValidUnicodeWithoutInventedCompositionPacket() {
        val s=ready();val session=s.snapshot.session
        assertFalse(s.local(session,TvImeValue("\ud83d"),true,1));assertNull(s.nextEdit())
        assertTrue(s.local(session,TvImeValue("😀"),true,2));val edit=s.nextEdit()!!
        assertEquals(ImeReplacement(0,0,"😀"),edit.replacement)
        assertEquals(21,Wire.parse(edit.bytes()).single().number)
    }
    @Test fun reorderedUiTasksCannotReplaceTheNewerDraft() {
        val s=ready();val id=s.snapshot.session
        assertTrue(s.local(id,TvImeValue("new"),false,2));assertFalse(s.local(id,TvImeValue("old"),false,1))
        assertEquals("new",s.snapshot.value!!.text)
    }
    @Test fun conflictingRemoteEditHaltsQueueAndPreservesNewerDraft() {
        val s=ready("a");val id=s.snapshot.session
        s.local(id,TvImeValue("ab"),false);s.nextEdit();s.local(id,TvImeValue("abc"),false)
        s.receive(update("external",counter=43));assertEquals(TvImeMode.CONFLICT,s.snapshot.mode)
        assertEquals("abc",s.snapshot.value!!.text);assertNull(s.nextEdit())
    }
    @Test fun timeoutNeverReplaysAndKeepsDraftForManualReview() {
        val s=ready();s.local(s.snapshot.session,TvImeValue("a"),false);val e=s.nextEdit()!!
        s.expire(e.session,e.id);assertEquals(TvImeMode.MANUAL,s.snapshot.mode);assertEquals("a",s.snapshot.value!!.text);assertNull(s.nextEdit())
    }
    @Test fun matchingNewShowIsNotAnAckOrPermissionToReplayIntoANewField() {
        val s=ready();val old=s.snapshot.session
        s.local(old,TvImeValue("a"),false);s.nextEdit();s.local(old,TvImeValue("abc"),false)
        s.receive(show("a",counter=43));assertNotEquals(old,s.snapshot.session);assertEquals(TvImeMode.CONFLICT,s.snapshot.mode)
        assertEquals("abc",s.snapshot.value!!.text);assertNull(s.nextEdit());assertFalse(s.local(old,TvImeValue("old field"),false))
    }
    @Test fun resetCloseAndImeContextChangeInvalidatePendingInput() {
        for(close in listOf<(AndroidImeSession)->Unit>({it.reset()},{it.detach()},{it.receive(batch(8,0))},{it.receive(Wire.parse(Wire.bytes(22,byteArrayOf())))})) {
            val s=ready();val old=s.snapshot.session;s.local(old,TvImeValue("a"),false);s.nextEdit();close(s)
            assertNull(s.nextEdit());assertFalse(s.local(old,TvImeValue("stale"),false));assertNull(s.snapshot.value)
        }
    }
    @Test fun appOnlyUpdateClosesAndErasesText() {
        val s=ready("secret");s.receive(Wire.parse(Wire.bytes(20,Wire.bytes(1,Wire.string(12,"other.app")))))
        assertFalse(s.snapshot.active);assertNull(s.snapshot.value)
    }
    @Test fun stalePrivateStatusCannotEraseNewerDraft() {
        val s=ready("new");s.local(s.snapshot.session,TvImeValue("newer"),false)
        s.receive(Wire.parse(Wire.bytes(20,Wire.bytes(2,status("••",counter=1,label="Password")))))
        assertEquals("newer",s.snapshot.value!!.text);assertEquals(TvImeMode.LIVE,s.snapshot.mode)
    }
    @Test fun dismissDoesNotReopenFromLateStatusEcho() {
        val s=ready();s.local(s.snapshot.session,TvImeValue("typing"),false);s.nextEdit();s.dismiss()
        s.receive(update("typing",counter=43));assertFalse(s.snapshot.active);assertNull(s.snapshot.value)
        s.receive(show("new",counter=44));assertTrue(s.snapshot.active);assertEquals("new",s.snapshot.value!!.text)
    }
    @Test fun differentConnectionsNeverAcceptEachOthersFieldTokens() {
        val old=ready();val current=ready()
        assertNotEquals(old.snapshot.session,current.snapshot.session)
        assertFalse(current.local(old.snapshot.session,TvImeValue("wrong TV"),false))
    }
    @Test fun queuedOldUiIntentCannotOverwriteNewerExternalTvText() {
        val s=ready("abc");val session=s.snapshot.session;val basis=s.snapshot.externalVersion
        s.receive(update("TV",counter=43))
        assertFalse(s.local(session,TvImeValue("abcd"),false,1,basis))
        assertEquals(TvImeMode.CONFLICT,s.snapshot.mode);assertEquals("abcd",s.snapshot.value!!.text);assertNull(s.nextEdit())
    }
    @Test fun ownAcknowledgmentDoesNotInvalidateQueuedTypingLineage() {
        val s=ready("abc");val session=s.snapshot.session;val basis=s.snapshot.externalVersion
        s.local(session,TvImeValue("abcd"),false,1,basis);s.nextEdit();s.receive(update("abcd",counter=43))
        assertEquals(basis,s.snapshot.externalVersion)
        assertTrue(s.local(session,TvImeValue("abcde"),false,2,basis));assertEquals("e",s.nextEdit()!!.replacement.text)
    }
    @Test fun enterRequiresCommittedConfirmedDraftAndStagedUiRevision() {
        val s=ready("abc");val session=s.snapshot.session
        assertTrue(s.canEnter(session,0));assertFalse(s.canEnter(session,1))
        s.local(session,TvImeValue("abcd"),true,1);assertFalse(s.canEnter(session,1))
        s.local(session,TvImeValue("abcd"),false,2);assertFalse(s.canEnter(session,2));s.nextEdit();assertFalse(s.canEnter(session,2))
        s.receive(update("abcd",counter=43));assertTrue(s.canEnter(session,2));assertFalse(s.canEnter(session,3))
    }
    @Test fun newerTvSelectionWinsAfterLocalOnlyCursorMovement() {
        val s=ready("abc",3);val session=s.snapshot.session
        s.local(session,TvImeValue("abc",1),false);assertNull(s.nextEdit())
        s.receive(update("abc",2,counter=43));assertEquals(2,s.snapshot.value!!.start);assertNull(s.nextEdit())
    }
    @Test fun lateShowAfterConfirmedEditCannotRegressTextOrCounter() {
        val s=ready("abc");val session=s.snapshot.session
        s.local(session,TvImeValue("abcd"),false);s.nextEdit();s.receive(update("abcd",counter=43))
        s.receive(show("abc",counter=42))
        assertEquals("abcd",s.snapshot.value!!.text);assertEquals(TvImeMode.CONFLICT,s.snapshot.mode);assertNull(s.nextEdit())
    }
    @Test fun lateAcknowledgmentCannotRestoreRetiredAppContext() {
        fun context(id:Int,text:String,count:Int)=Wire.parse(Wire.bytes(20,Wire.message(Wire.bytes(1,Wire.message(Wire.int(1,id),Wire.string(12,"same.app"))),Wire.bytes(2,status(text,counter=count)))))
        val s=AndroidImeSession();s.receive(batch());s.receive(context(7,"old",42));val old=s.snapshot.session
        s.local(old,TvImeValue("old edit"),false);s.nextEdit()
        s.receive(context(8,"new",100));val current=s.snapshot.session
        s.receive(context(7,"old edit",43))
        assertEquals(current,s.snapshot.session);assertEquals("new",s.snapshot.value!!.text);assertNull(s.nextEdit())
        s.receive(show("old edit",counter=43));assertEquals("new",s.snapshot.value!!.text);assertEquals(TvImeMode.CONFLICT,s.snapshot.mode)
    }
    @Test fun oldImeCounterCannotRestoreRetiredInputConnection() {
        val s=ready("old");s.receive(batch(8,100));s.receive(show("new",counter=100));val current=s.snapshot.session
        s.receive(batch(7,43));assertEquals(current,s.snapshot.session);assertEquals("new",s.snapshot.value!!.text)
        s.local(current,TvImeValue("newer"),false);assertEquals(8,s.nextEdit()!!.imeCounter)
    }
    @Test fun ignoredRetiredContextDoesNotPoisonNextVerifiedFreshField() {
        fun context(id:Int,text:String,count:Int)=Wire.parse(Wire.bytes(20,Wire.message(Wire.bytes(1,Wire.message(Wire.int(1,id),Wire.string(12,"same.app"))),Wire.bytes(2,status(text,counter=count)))))
        val s=AndroidImeSession();s.receive(batch(100,1000));s.receive(context(100,"old",1000))
        s.receive(context(101,"new",2000));s.receive(show("new",counter=2000))
        s.receive(context(100,"old late echo",1001));assertEquals("new",s.snapshot.value!!.text)
        s.receive(context(102,"wait",3000));s.receive(show("wait",counter=3000))
        assertEquals(TvImeMode.LIVE,s.snapshot.mode);assertEquals("wait",s.snapshot.value!!.text)
        assertTrue(s.local(s.snapshot.session,TvImeValue("waitZ"),false));assertNotNull(s.nextEdit())
    }
    @Test fun fieldLabelChangeInvalidatesOldSessionAndNoOldEditsRemain() {
        val s=ready("query");val old=s.snapshot.session
        s.local(old,TvImeValue("query changed"),false);s.nextEdit()
        s.receive(Wire.parse(Wire.bytes(20,Wire.bytes(2,status("new field",counter=44,label="Address")))))
        assertNotEquals(old,s.snapshot.session);assertFalse(s.local(old,TvImeValue("stale"),false));assertNull(s.nextEdit())
        assertEquals("new field",s.snapshot.value!!.text)
    }
    @Test fun invalidUtf8AtZeroSelectionIsNotMistakenForAnEmptyField() {
        val s=ready("old")
        s.receive(Wire.parse(Wire.bytes(22,Wire.bytes(2,Wire.message(Wire.int(1,45),Wire.bytes(2,byteArrayOf(0xc3.toByte(),0x28)),Wire.int(3,0),Wire.int(4,0))))))
        assertNull(s.snapshot.value);assertEquals(TvImeMode.PRIVATE,s.snapshot.mode)
        val empty=ready();assertEquals("",empty.snapshot.value!!.text);assertEquals(TvImeMode.LIVE,empty.snapshot.mode)
    }
    @Test fun zeroBatchDoesNotRegressOrCloseKnownField() {
        val s=ready();s.receive(batch(0,0));assertTrue(s.snapshot.active)
        s.local(s.snapshot.session,TvImeValue("a"),false);val e=s.nextEdit()!!
        assertEquals(7,e.imeCounter);assertEquals(42,e.fieldCounter)
    }
    @Test fun explicitInitialZeroCountersAreValid() {
        val s=AndroidImeSession();s.receive(batch(0,0));s.receive(show("",counter=0));s.local(s.snapshot.session,TvImeValue("x"),false)
        assertEquals(0,s.nextEdit()!!.imeCounter)
    }
    @Test fun maskedPasswordLabelsInvalidSelectionAndMalformedUtf8AreNotMirrored() {
        for((text,label) in listOf("••••" to "Search","••••x" to "","****" to "","***x" to "","clear password" to "Password","1234" to "PIN")) {
            val s=AndroidImeSession();s.receive(batch());s.receive(show(text,label=label))
            assertNull(s.snapshot.value);assertEquals(TvImeMode.PRIVATE,s.snapshot.mode);assertNull(s.nextEdit())
        }
        val s=ready();s.receive(show("abc",start=9));assertNull(s.snapshot.value)
        s.receive(Wire.parse(Wire.bytes(22,Wire.bytes(2,Wire.message(Wire.int(1,45),Wire.bytes(2,byteArrayOf(0xc3.toByte(),0x28)),Wire.int(3,1),Wire.int(4,1))))))
        assertNull(s.snapshot.value)
    }
    @Test fun diagnosticsDoNotContainText() {
        val s=ready("TOP SECRET");assertFalse(s.snapshot.toString().contains("SECRET"));assertFalse(s.snapshot.value.toString().contains("SECRET"))
        s.local(s.snapshot.session,TvImeValue("NEW SECRET"),false);assertFalse(s.nextEdit().toString().contains("SECRET"))
    }
    @Test fun privateManualInsertionUsesReportedSelectionWithoutReconstructingMasks() {
        val s=AndroidImeSession();s.receive(batch());s.receive(show("••••x",5,label=""))
        val session=s.snapshot.session
        val objectBytes=Wire.parse(s.manualInsertion("Z")).nested(21).nested(3).nested(2)
        assertEquals(5,objectBytes.num(1));assertEquals(5,objectBytes.num(2))
        assertEquals("Z",objectBytes.first {it.number==3}.bytes!!.toString(Charsets.UTF_8))
        assertNull(s.snapshot.value)
        s.fallback();assertEquals(TvImeMode.PRIVATE,s.snapshot.mode)
        s.receive(update("unmasked secret",15,counter=43))
        assertEquals(session,s.snapshot.session);assertEquals(TvImeMode.PRIVATE,s.snapshot.mode);assertNull(s.snapshot.value)
        assertNull(s.nextEdit())
    }
    @Test fun privateClassificationStaysStickyAcrossPlainStatusAndShowInSameContext() {
        val s=AndroidImeSession();s.receive(batch());s.receive(show("secret",6,label="Password"));val session=s.snapshot.session
        s.receive(update("looks plain",11,counter=43));assertNull(s.snapshot.value);assertEquals(session,s.snapshot.session)
        s.receive(show("looks plain",11,counter=44,label=""));assertEquals(TvImeMode.PRIVATE,s.snapshot.mode);assertNull(s.snapshot.value);assertNotEquals(session,s.snapshot.session)
        assertFalse(s.local(session,TvImeValue("leak"),false));assertNull(s.nextEdit())
        s.detach();assertFalse(s.snapshot.active);assertEquals(TvImeMode.PRIVATE,s.snapshot.mode);assertNull(s.snapshot.value)
    }
    @Test fun repeatedPrivateStatusOnlyUpdatesSafeCounterAndSelectionWithoutNewEpoch() {
        val s=AndroidImeSession();s.receive(batch());s.receive(show("••••x",5,label=""));val session=s.snapshot.session
        s.receive(update("•••••x",6,counter=43));assertEquals(session,s.snapshot.session)
        val body=Wire.parse(s.manualInsertion("Z")).nested(21)
        assertEquals(43,body.num(2));assertEquals(6,body.nested(3).nested(2).num(1));assertNull(s.snapshot.value)
    }
    @Test fun knownPrivateNegativeSelectionAndInvalidUtf8NeverExposeContents() {
        val negative=ByteArray(10) {if(it==9)1 else 0xff.toByte()}
        val raw=Wire.message(Wire.int(1,42),Wire.bytes(2,byteArrayOf(0xc3.toByte(),0x28)),Wire.varint(24)+negative,Wire.varint(32)+negative,Wire.string(6,"Password"))
        val s=AndroidImeSession();s.receive(batch());s.receive(Wire.parse(Wire.bytes(22,Wire.bytes(2,raw))))
        assertEquals(TvImeMode.PRIVATE,s.snapshot.mode);assertNull(s.snapshot.value)
        val objectBytes=Wire.parse(s.manualInsertion("Z")).nested(21).nested(3).nested(2)
        assertEquals(0,objectBytes.num(1));assertEquals(0,objectBytes.num(2))
        s.receive(update("plain",5,counter=43));assertNull(s.snapshot.value);assertEquals(TvImeMode.PRIVATE,s.snapshot.mode)
    }
    @Test fun onlyObservedNewEditorContextClearsStickyPrivacy() {
        fun context(id:Int,text:String,count:Int,label:String)=Wire.parse(Wire.bytes(20,Wire.message(Wire.bytes(1,Wire.message(Wire.int(1,id),Wire.string(12,"same.app"))),Wire.bytes(2,status(text,counter=count,label=label)))))
        val s=AndroidImeSession();s.receive(batch());s.receive(context(7,"•••",42,"Password"));assertEquals(TvImeMode.PRIVATE,s.snapshot.mode)
        s.receive(context(7,"unmasked",43,"Search"));assertNull(s.snapshot.value)
        s.receive(context(8,"new query",44,"Search"));assertEquals(TvImeMode.LIVE,s.snapshot.mode);assertEquals("new query",s.snapshot.value!!.text)
    }
    @Test fun unknownManualCounterOnlyConnectionKeepsLegacyCompatibility() {
        val s=AndroidImeSession();s.receive(batch())
        val body=Wire.parse(s.manualInsertion("abc")).nested(21);val op=body.nested(3).nested(2)
        assertEquals(2,op.num(1));assertEquals(2,op.num(2));assertEquals(42,body.num(2))
    }
}
