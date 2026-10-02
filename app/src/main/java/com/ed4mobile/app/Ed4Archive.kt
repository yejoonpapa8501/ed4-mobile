package com.ed4mobile.app

import java.io.ByteArrayOutputStream
import java.io.File

/** AFLB container + bounded Falcom BZ blocks. Game data stays on the user's device. */
class Ed4Archive(file: File) {
    private val bytes = file.readBytes()
    private val offsets: IntArray
    val count: Int get() = offsets.size - 1
    init {
        require(bytes.size >= 20 && String(bytes, 0, 9, Charsets.US_ASCII) == "AFLB DAT\u001a") { "지원하지 않는 DAT 형식" }
        val count = word(bytes, 10)
        require(count in 1..4096 && 18 + count * 2 <= bytes.size)
        offsets = IntArray(count + 1) { (word(bytes, 16 + it * 2) * 32).coerceAtMost(bytes.size) }
        require(offsets[0] >= 18 + count * 2 && (0 until count).all { offsets[it] <= offsets[it + 1] })
    }
    fun resource(index: Int): ByteArray {
        require(index in 0 until count)
        return unpack(bytes.copyOfRange(offsets[index], offsets[index + 1]))
    }
    companion object {
        fun word(b: ByteArray, offset: Int): Int {
            require(offset >= 0 && offset + 1 < b.size) { "데이터가 잘렸습니다" }
            return (b[offset].toInt() and 255) or ((b[offset + 1].toInt() and 255) shl 8)
        }
        fun unpack(input: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (true) {
                val size = word(input, pos)
                require(size >= 4 && pos + size < input.size) { "압축 블록 길이 오류" }
                val block = unpackBlock(input.copyOfRange(pos + 2, pos + size))
                require(out.size() + block.size <= 2 * 1024 * 1024)
                out.write(block)
                pos += size
                when (input[pos++].toInt() and 255) {
                    0 -> return out.toByteArray()
                    1 -> Unit
                    else -> error("압축 연결 표시 오류")
                }
            }
        }
        private fun unpackBlock(b: ByteArray): ByteArray {
            require(b.size >= 2 && b[0].toInt() == 0)
            var pos = 2
            var flags = b[1].toInt() and 255
            var remaining = 8
            val out = ByteArray(1024 * 1024)
            var size = 0
            fun byte(): Int {
                require(pos < b.size) { "압축 스트림이 잘렸습니다" }
                return b[pos++].toInt() and 255
            }
            fun bit(): Int {
                if (remaining == 0) { flags = byte() or (byte() shl 8); remaining = 16 }
                val v = flags and 1; flags = flags ushr 1; remaining--; return v
            }
            fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
            fun append(v: Int) { require(size < out.size); out[size++] = v.toByte() }
            while (true) {
                if (bit() == 0) { append(byte()); continue }
                val distance: Int
                if (bit() == 0) distance = byte()
                else {
                    distance = (bits(5) shl 8) or byte()
                    if (distance == 0) return out.copyOf(size)
                    if (distance <= 2) {
                        val long = bit(); var run = bits(4)
                        if (long != 0) run = (run shl 8) or byte()
                        val value = byte(); repeat(run + 14) { append(value) }; continue
                    }
                }
                var run = 2
                while (run < 6 && bit() == 0) run++
                if (run == 6) run = if (bit() != 0) bits(3) + 6 else byte() + 14
                require(distance in 1..size) { "잘못된 압축 참조" }
                repeat(run) { append(out[size - distance].toInt() and 255) }
            }
        }
    }
}
