package com.luma.remote.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Small bounded protobuf wire codec; only varint and length-delimited fields used by Remote v2. */
object Wire {
    data class Field(val number: Int, val numberValue: Long = 0, val bytes: ByteArray? = null)
    fun varint(value: Long): ByteArray {
        require(value >= 0)
        var n = value
        val out = ByteArrayOutputStream()
        while (n > 127) { out.write(((n and 127) or 128).toInt()); n = n ushr 7 }
        out.write(n.toInt()); return out.toByteArray()
    }
    fun readVarint(input: InputStream): Long {
        var n = 0L
        for (i in 0..9) {
            val b=input.read(); require(b>=0) { "Connection closed" }
            require(i<9 || b<=1) { "Invalid varint" }
            n=n or ((b and 127).toLong() shl (7*i))
            if(b and 128 == 0)return n
        }
        throw IllegalArgumentException("Invalid varint")
    }
    fun int(n: Int, value: Int) = varint((n*8).toLong()) + varint(value.toLong())
    fun bytes(n: Int, value: ByteArray) = varint((n*8+2).toLong()) + varint(value.size.toLong()) + value
    fun string(n: Int, value: String) = bytes(n, value.toByteArray(Charsets.UTF_8))
    fun message(vararg fields: ByteArray) = fields.fold(byteArrayOf()) { a,b -> a+b }
    fun parse(value: ByteArray): List<Field> {
        val input = ByteArrayInputStream(value); val fields = mutableListOf<Field>()
        while (input.available() > 0) {
            val tag = readVarint(input).toInt(); require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> fields += Field(tag ushr 3, readVarint(input))
                2 -> { val size = readVarint(input); require(size in 0..1_048_576 && size <= input.available()); val b = ByteArray(size.toInt()); require(b.isEmpty() || input.read(b) == b.size); fields += Field(tag ushr 3, bytes=b) }
                1 -> require(input.skip(8) == 8L)
                5 -> require(input.skip(4) == 4L)
                else -> error("Unsupported wire type")
            }
        }
        return fields
    }
    fun frame(input: InputStream): ByteArray {
        val size = readVarint(input); require(size in 1..1_048_576) { "Invalid message size" }
        val b = ByteArray(size.toInt()); var offset = 0
        while (offset < b.size) { val read = input.read(b, offset, b.size-offset); require(read > 0) { "Connection closed" }; offset += read }; return b
    }
    fun write(out: OutputStream, data: ByteArray) { out.write(varint(data.size.toLong())); out.write(data); out.flush() }
}
fun List<Wire.Field>.num(n: Int) = firstOrNull { it.number == n }?.numberValue?.toInt() ?: 0
fun List<Wire.Field>.nested(n: Int) = firstOrNull { it.number == n }?.bytes?.let(Wire::parse) ?: emptyList()
