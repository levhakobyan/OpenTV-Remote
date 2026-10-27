package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class BluetoothHidReportsTest {
    @Test fun mouseUsesSignedRelativeAxesAndNeutralRelease() {
        assertArrayEquals(byteArrayOf(0,127,-127,3),BluetoothHidReports.mouse(200f,-200f,3f))
        assertArrayEquals(byteArrayOf(1,0,0,0),BluetoothHidReports.mouse(pressed=true))
        assertArrayEquals(ByteArray(4),BluetoothHidReports.mouse())
        assertThrows(IllegalArgumentException::class.java) { BluetoothHidReports.mouse(Float.NaN) }
    }
    @Test fun descriptorDefinesSeparateReportsWithCorrectPayloadSizes() {
        fun inputBits(bytes:ByteArray):Map<Int,Int> {
            var id=0;var size=0;var count=0;var offset=0
            val bits=mutableMapOf<Int,Int>()
            while(offset<bytes.size) {
                val prefix=bytes[offset++].toInt() and 255
                val n=when(prefix and 3) { 3 -> 4;else -> prefix and 3 }
                var value=0;repeat(n) { value=value or ((bytes[offset++].toInt() and 255) shl (it*8)) }
                when(prefix and 252) { 0x84 -> id=value;0x74 -> size=value;0x94 -> count=value;0x80 -> bits[id]=(bits[id] ?: 0)+size*count }
            }
            return bits
        }
        assertEquals(mapOf(1 to 64),inputBits(BluetoothHidReports.descriptor(false)))
        assertEquals(mapOf(1 to 64,2 to 32),inputBits(BluetoothHidReports.descriptor(true)))
    }
}
