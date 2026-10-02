package com.ed4mobile.app

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import kotlin.math.roundToInt

/** Decodes user-imported Falcom AFLB/BZ resources. No original game assets shipped. */
object Ed4Resources {
    fun word(b: ByteArray, o: Int): Int {
        require(o >= 0 && o + 2 <= b.size) { "Truncated resource word" }
        return (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    }
    fun archive(file: File): List<ByteArray> {
        val b = file.readBytes()
        require(b.size >= 18 && String(b, 0, 9, Charsets.US_ASCII) == "AFLB DAT\u001a")
        val n = word(b, 10)
        require(18 + n * 2 <= b.size)
        val offsets = (0..n).map { (word(b, 16 + it * 2) * 32).coerceAtMost(b.size) }
        require(offsets.first() >= 18 + n * 2 && offsets.zipWithNext().all { it.first <= it.second })
        return offsets.zipWithNext().map { b.copyOfRange(it.first, it.second) }
    }
    fun unpack(b: ByteArray): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        var pos = 0
        while (true) {
            val size = word(b, pos)
            require(size >= 4 && pos + size < b.size)
            result.write(block(b.copyOfRange(pos + 2, pos + size)))
            require(result.size() <= 8 * 1024 * 1024)
            pos += size
            when (b[pos++].toInt() and 255) { 0 -> return result.toByteArray(); 1 -> Unit; else -> error("Invalid block marker") }
        }
    }
    private fun block(b: ByteArray): ByteArray {
        require(b.size >= 2 && b[0].toInt() == 0)
        var pos = 2; var flags = b[1].toInt() and 255; var remaining = 8
        val out = ByteArray(1024 * 1024); var count = 0
        fun byte(): Int { require(pos < b.size); return b[pos++].toInt() and 255 }
        fun bit(): Int {
            if (remaining == 0) { flags = byte() or (byte() shl 8); remaining = 16 }
            val v = flags and 1; flags = flags ushr 1; remaining--; return v
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        while (count < out.size) {
            if (bit() == 0) { out[count++] = byte().toByte(); continue }
            val distance: Int
            if (bit() == 0) distance = byte() else {
                distance = (bits(5) shl 8) or byte()
                if (distance == 0) return out.copyOf(count)
                if (distance <= 2) {
                    val long = bit(); var length = bits(4)
                    if (long != 0) length = (length shl 8) or byte()
                    length += 14; val value = byte().toByte()
                    require(count + length <= out.size)
                    repeat(length) { out[count++] = value }; continue
                }
            }
            var length = 2
            while (length < 6 && bit() == 0) length++
            if (length == 6) length = if (bit() != 0) bits(3) + 6 else byte() + 14
            require(distance in 1..count && count + length <= out.size)
            repeat(length) { out[count] = out[count - distance]; count++ }
        }
        error("Resource too large")
    }
    data class Scene(val bitmap: Bitmap, val grid: IntArray, val columns: Int = 128, val hero: Array<Bitmap> = emptyArray())
    fun scene(root: File, scene: Int = 0): Scene {
        val a = archive(File(root, "DATA_A.DAT")); val meta = unpack(a[scene])
        val gridBytes = unpack(a[word(meta, 0) and 4095])
        val gfx = unpack(a[word(meta, 4) and 4095]); val defs = unpack(a[word(meta, 6) and 4095])
        val engine = unpack(archive(File(root, "DATA11.DAT"))[0])
        val base = word(engine, 0xffa4) * 16 + 0x1ef6
        require(base + 48 <= engine.size)
        val raw = engine.copyOfRange(base, base + 48)
        if (gfx.size >= 32774) gfx.copyInto(raw, 36, 32768, 32774)
        fun dac(v: Int): Int { require(v in 0..15); return if (v == 0) 0 else ((v * 4 + 3) * 255f / 63).roundToInt() }
        val pal = IntArray(16) { i -> Color.rgb(dac(raw[i*3+1].toInt() and 255), dac(raw[i*3+2].toInt() and 255), dac(raw[i*3].toInt() and 255)) }
        val tiles = Array(minOf(256, gfx.size / 128)) { n ->
            IntArray(256) { j ->
                val x = j % 16; val y = j / 16; var v = 0
                for (p in 0..3) v = v or (((gfx[n*128+p*32+y*2+x/8].toInt() ushr (7-x%8)) and 1) shl p)
                v
            }
        }
        val combined = Array(defs.size / 16) { n ->
            val dest = IntArray(256)
            for (k in 0..13) {
                val index = defs[n*16+k].toInt() and 255
                if (index != 0) { require(index < tiles.size); for (j in 0..255) if (tiles[index][j] != 15) dest[j] = tiles[index][j] }
            }; dest
        }
        val grid = IntArray(gridBytes.size/2) { word(gridBytes,it*2) }
        require(grid.size % 128 == 0 && grid.size <= 128 * 128)
        val width = 2048; val height = grid.size / 128 * 16; val pixels = IntArray(width*height)
        for (i in grid.indices) {
            val index = grid[i] ushr 6
            val tile = if (index >= 512) combined.getOrNull(index-512) else tiles.getOrNull(index)
            require(tile != null) { "Unsupported map cell $index" }
            for (j in 0..255) pixels[(i/128*16+j/16)*width+i%128*16+j%16] = pal[tile[j]]
        }
        val heroBytes = unpack(archive(File(root, "DATA12.DAT"))[16])
        val hero = Array(8) { frame ->
            val rgba = IntArray(32*48)
            for (y in 0 until 48) for (x in 0 until 32) {
                val tile = y/16*16+frame*2+x/16; val lx=x%16; val ly=y%16; var v=0
                for (plane in 0..3) v = v or (((heroBytes[tile*128+plane*32+ly*2+lx/8].toInt() ushr (7-lx%8)) and 1) shl plane)
                rgba[y*32+x] = if (v == 15) Color.TRANSPARENT else pal[v]
            }
            Bitmap.createBitmap(rgba,32,48,Bitmap.Config.ARGB_8888)
        }
        return Scene(Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888),grid,hero=hero)
    }
}
