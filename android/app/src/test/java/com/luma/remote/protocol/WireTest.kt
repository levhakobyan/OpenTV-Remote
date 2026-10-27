package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WireTest {
    @Test fun textDefaultsAndNegotiatedCountersMatchRemoteV2() {
        val value="բարև🙂"
        val batch=Wire.parse(AndroidTvProtocol.text(value)).nested(21)
        assertEquals(0,batch.num(1));assertEquals(0,batch.num(2))
        val edit=batch.nested(3);assertEquals(1,edit.num(1))
        val obj=edit.nested(2);assertEquals(value.length-1,obj.num(1));assertEquals(value,String(obj.first { it.number==3 }.bytes!!,Charsets.UTF_8))
        val synced=Wire.parse(AndroidTvProtocol.text("hello",7,42)).nested(21)
        assertEquals(7,synced.num(1));assertEquals(42,synced.num(2))
        assertThrows(IllegalArgumentException::class.java) { AndroidTvProtocol.text("a".repeat(1001)) }
    }
    @Test fun varintRoundTripsAtBoundaries() { listOf(0L,1,127,128,16383,16384,Int.MAX_VALUE.toLong()).forEach { assertEquals(it,Wire.readVarint(ByteArrayInputStream(Wire.varint(it)))) } }
    @Test fun emptyAcknowledgementAtEndOfFrameIsValid() { val msg=Wire.message(Wire.int(1,2),Wire.int(2,200),Wire.bytes(11,byteArrayOf())); val fields=Wire.parse(msg); assertEquals(200,fields.num(2)); assertArrayEquals(byteArrayOf(),fields.last().bytes) }
    @Test fun signedProtoInt32AndOverflowAreHandledAtTenByteBoundary() {
        val signed=ByteArray(10) { if(it==9)1 else 0xff.toByte() }
        assertEquals(-1L,Wire.readVarint(ByteArrayInputStream(signed)))
        assertEquals(-1,Wire.parse(Wire.varint(24)+signed).num(3))
        assertThrows(IllegalArgumentException::class.java) { Wire.readVarint(ByteArrayInputStream(ByteArray(10) { if(it==9)2 else 0xff.toByte() })) }
    }
    @Test fun heldNavigationHasExplicitDownAndUp() {
        assertEquals(1,Wire.parse(AndroidTvProtocol.key(Command.RIGHT,KeyDirection.DOWN)).nested(10).num(2))
        assertEquals(2,Wire.parse(AndroidTvProtocol.key(Command.RIGHT,KeyDirection.UP)).nested(10).num(2))
        assertEquals(3,Wire.parse(AndroidTvProtocol.key(Command.HOME)).nested(10).num(2))
    }
    @Test fun navigationMatchesRemoteV2Schema() {
        val message=Wire.bytes(10,Wire.message(Wire.int(1,23),Wire.int(2,3)))
        assertArrayEquals(byteArrayOf(0x52,4,8,23,16,3),message)
        val parsed=Wire.parse(message).nested(10); assertEquals(23,parsed.num(1));assertEquals(3,parsed.num(2))
    }
    @Test fun multipleFramesAreReadIndependently() {
        val out=ByteArrayOutputStream(); val a=Wire.int(1,622); val b=Wire.bytes(40,Wire.int(1,1))
        Wire.write(out,a);Wire.write(out,b);val input=ByteArrayInputStream(out.toByteArray());assertArrayEquals(a,Wire.frame(input));assertArrayEquals(b,Wire.frame(input));assertEquals(0,input.available())
    }
    @Test fun fragmentedStreamStillReadsCompleteFrame() {
        val msg=Wire.string(1,"OpenTV TV");val out=ByteArrayOutputStream();Wire.write(out,msg)
        val input=object:ByteArrayInputStream(out.toByteArray()) { override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(len,1)) }
        assertArrayEquals(msg,Wire.frame(input))
    }
    @Test fun utf8AndUnknownFieldsArePreserved() {
        val msg=Wire.message(Wire.string(3,"Հայերեն 🎬"),Wire.int(999,7))
        val fields=Wire.parse(msg);assertEquals("Հայերեն 🎬",String(fields.first().bytes!!));assertEquals(7,fields.num(999))
    }
    @Test fun malformedFramesAreRejected() {
        listOf(byteArrayOf(),byteArrayOf(0),Wire.varint(1_048_577),byteArrayOf(2,8),ByteArray(12){0xff.toByte()}).forEach { assertThrows(IllegalArgumentException::class.java) { Wire.frame(ByteArrayInputStream(it)) } }
    }
    @Test fun malformedNestedLengthsAreRejected() { assertThrows(IllegalArgumentException::class.java) { Wire.parse(byteArrayOf(10,100,1)) };assertThrows(IllegalArgumentException::class.java) { Wire.parse(byteArrayOf(0,1)) } }
    @Test fun pairingConfigurationMatchesSchema() {
        val encoding=Wire.message(Wire.int(1,3),Wire.int(2,6)); val config=Wire.message(Wire.int(1,2),Wire.int(2,200),Wire.bytes(30,Wire.message(Wire.bytes(1,encoding),Wire.int(2,1))))
        val parsed=Wire.parse(config);assertEquals(200,parsed.num(2)); assertEquals(3,parsed.nested(30).nested(1).num(1)); assertEquals(6,parsed.nested(30).nested(1).num(2));assertEquals(1,parsed.nested(30).num(2))
    }
}
