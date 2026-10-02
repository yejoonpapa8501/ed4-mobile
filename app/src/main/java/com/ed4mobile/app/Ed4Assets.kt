package com.ed4mobile.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import kotlin.math.roundToInt

/** Verified DOS planar tiles and 14-layer map composites, without bundled game bytes. */
class Ed4Assets(private val directory: File) {
    private val areas = mutableMapOf<String, Ed4Archive>()
    private fun archive(name: String) = areas.getOrPut(name) { Ed4Archive(File(directory, name)) }
    private val actors = Ed4Archive(File(directory, "DATA12.DAT"))
    private val engine = Ed4Archive(File(directory, "DATA11.DAT")).resource(0)
    private fun resource(id: Int): ByteArray {
        val bank = id ushr 12
        val name = if (bank in 0..9) "DATA_${'A' + bank}.DAT" else "DATA${bank}.DAT"
        require(bank in 0..13) { "리소스 뱅크 범위 초과" }
        return archive(name).resource(id and 4095)
    }
    val sceneIds = listOf(0, 4, 10, 14, 15, 21, 26, 30, 34, 39, 43, 47, 51, 55)
    data class Scene(val image: Bitmap, val frames: List<Bitmap>, val id: Int, val unsupportedCells: Int,
        val script: ByteArray, val seed: ByteArray, val npcFrames: Map<Int, Map<Int, Bitmap>>, val stage: Int,
        val spawnX: Float, val spawnY: Float, val characterNames: List<String>,
        val grid: ByteArray, val definitions: ByteArray, val tilePixels: List<IntArray>, val colors: IntArray,
        val footprints: Map<Int,Int>) {
        val terrain = Ed4Terrain(grid, definitions)
    }

    fun initialScene(): Scene {
        val base = Ed4Archive.word(engine,0xffa4)*16
        return sceneResource(Ed4Archive.word(engine,base+0x12),engine[base+0x19].toInt() and 255)
    }
    fun scene(index: Int): Scene {
        return sceneResource(sceneIds[index.coerceIn(sceneIds.indices)])
    }
    fun sceneResource(id: Int, stage: Int = 0): Scene {
        val bank = id ushr 12
        require(bank in 0..9) { "이 맵 리소스는 아직 지원하지 않습니다: %04X".format(id) }
        val metadata = resource(id)
        require(Ed4Archive.word(metadata, 2) == 0) { "광역 맵의 구역 전환은 아직 구현 중입니다 (%04X)".format(id) }
        fun ref(offset: Int) = resource(Ed4Archive.word(metadata, offset))
        val grid = ref(0)
        val graphics = ref(4)
        val definitions = ref(6)
        require(grid.size == 20480 && graphics.size >= 32768 && definitions.size == 8192)
        val palette = palette(graphics)
        val source = tiles(graphics.copyOfRange(0, 32768))
        val combined = List(512) { i ->
            val pixels = IntArray(256)
            for (layer in 0 until 14) {
                val tile = definitions[i * 16 + layer].toInt() and 255
                if (tile != 0) for (p in 0 until 256) if (source[tile][p] != 15) pixels[p] = source[tile][p]
            }
            pixels
        }
        fun bitmap(tile: IntArray) = Bitmap.createBitmap(IntArray(256) { palette[tile[it]] }, 16, 16, Bitmap.Config.ARGB_8888)
        val tileImages = (source + combined).map { bitmap(it) }
        val output = Bitmap.createBitmap(2048, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isFilterBitmap = false }
        var missing = 0
        var maxX = 32; var maxY = 32
        for (i in 0 until grid.size / 2) {
            val word = Ed4Archive.word(grid, i * 2)
            val tile = word ushr 6
            val pixels = when {
                tile >= 512 -> combined[tile - 512]
                tile < 256 -> source[tile]
                else -> { missing++; continue }
            }
            if (pixels.any { it != 0 && it != 15 }) { maxX = maxOf(maxX, i % 128 * 16 + 16); maxY = maxOf(maxY, i / 128 * 16 + 16) }
            val image = tileImages[if (tile >= 512) source.size + tile - 512 else tile]
            canvas.drawBitmap(image, (i % 128 * 16).toFloat(), (i / 128 * 16).toFloat(), paint)
        }
        val subset = Bitmap.createBitmap(output, 0, 0, maxX, maxY)
        val cropped = subset.copy(Bitmap.Config.ARGB_8888, true)
        if (subset !== output) subset.recycle()
        if (cropped !== output) output.recycle()
        tileImages.forEach { it.recycle() }
        val stateBase = Ed4Archive.word(engine, 0xffa4) * 16
        val stageOffset = 46 + stage * 14
        require(stageOffset + 14 <= metadata.size) { "맵 배치 데이터 범위 초과" }
        val spriteOffset = Ed4Archive.word(metadata, stageOffset)
        val motions = mutableMapOf<Int,ByteArray>()
        var motionPointer = Ed4Archive.word(metadata,stageOffset+2)
        var motionRecords = 0
        while (motionPointer < metadata.size && (metadata[motionPointer].toInt() and 255) != 255 && motionRecords++ < 64) {
            require(motionPointer+4 <= metadata.size)
            val slot = metadata[motionPointer].toInt() and 255
            val span = metadata[motionPointer+1].toInt() and 255
            val data = resource(Ed4Archive.word(metadata,motionPointer+2))
            require(data.size == span*64 && slot+span <= 32)
            for (frame in 0 until span*8) motions[slot*8+frame] = data.copyOfRange(frame*8,frame*8+8)
            motionPointer += 4
        }
        val footprints = motions.mapValues { (_,pose) -> pose.indices.fold(0) { mask,i -> if (pose[i].toInt()!=0) mask or (1 shl i) else mask } }
        val sprites = mutableMapOf<Int, Map<Int,Bitmap>>()
        var pointer = spriteOffset
        var records = 0
        while (pointer < metadata.size && (metadata[pointer].toInt() and 255) != 255 && records++ < 64) {
            require(pointer + 4 <= metadata.size)
            val slot = metadata[pointer].toInt() and 255
            val resource = Ed4Archive.word(metadata, pointer + 2)
            val rows = metadata[pointer+1].toInt() and 255
            val actorResource = if (resource <= 3) 0xc010 + resource else resource
            runCatching { actorFrames(palette,actorResource,rows,motions) }.getOrNull()?.let { sprites[slot] = it }
            pointer += 4
        }
        val position = Ed4Archive.word(metadata, 36 + stage * 14)
        val hero = sprites[0] ?: actorFrames(palette,0xc010,4,motions)
        return Scene(cropped, List(8) { hero[it] ?: error("주인공 동작 데이터 누락") }, id, missing, metadata,
            engine.copyOfRange(stateBase, stateBase + 0x2000), sprites, stage,
            ((position and 255) + 1) * 16f, ((position ushr 8) + 1) * 16f,
            List(13) { i ->
                val start = stateBase + Ed4Archive.word(engine, stateBase + 0x2614 + i * 2)
                val end = (start until engine.size).first { engine[it].toInt() == 0 }
                String(engine, start, end - start, java.nio.charset.Charset.forName("MS949"))
            }, grid, definitions, source + combined, palette, footprints)
    }
    fun refreshMap(scene: Scene, nextGrid: ByteArray) {
        require(nextGrid.size == scene.grid.size)
        val canvas = Canvas(scene.image)
        val paint = Paint().apply { isFilterBitmap = false }
        for (offset in nextGrid.indices step 2) if (nextGrid[offset] != scene.grid[offset] || nextGrid[offset + 1] != scene.grid[offset + 1]) {
            val tile = Ed4Archive.word(nextGrid, offset) ushr 6
            require(tile < 256 || tile >= 512) { "지원하지 않는 맵 타일" }
            val index = if (tile >= 512) 256 + tile - 512 else tile
            val pixels = scene.tilePixels.getOrNull(index) ?: error("지원하지 않는 맵 타일")
            val bitmap = Bitmap.createBitmap(IntArray(256) { scene.colors[pixels[it]] },16,16,Bitmap.Config.ARGB_8888)
            canvas.drawBitmap(bitmap, (offset / 2 % 128 * 16).toFloat(), (offset / 2 / 128 * 16).toFloat(), paint)
            bitmap.recycle()
        }
        nextGrid.copyInto(scene.grid)
    }

    private fun palette(graphics: ByteArray): IntArray {
        val base = Ed4Archive.word(engine, 0xffa4) * 16
        require(base + 0x1ef6 + 48 <= engine.size)
        val p = engine.copyOfRange(base + 0x1ef6, base + 0x1ef6 + 48)
        require(p.all { (it.toInt() and 255) <= 15 })
        if (graphics.size >= 32774) graphics.copyInto(p, 36, 32768, 32774)
        // Original VGA writer accepts B,R,G triples and expands 4-bit values to a 6-bit DAC.
        fun value(i: Int): Int { val n = p[i].toInt() and 15; return if (n == 0) 0 else ((n * 4 + 3) * 255f / 63).roundToInt() }
        return IntArray(16) { i -> Color.rgb(value(i * 3 + 1), value(i * 3 + 2), value(i * 3)) }
    }
    private fun tiles(b: ByteArray): List<IntArray> = List(b.size / 128) { n ->
        IntArray(256) { i ->
            val x = i % 16; val y = i / 16; var color = 0
            for (p in 0 until 4) color = color or (((b[n * 128 + p * 32 + y * 2 + x / 8].toInt() ushr (7 - x % 8)) and 1) shl p)
            color
        }
    }
    fun openingImages(): List<Bitmap> = listOf("SAMSUNG.DAT", "MANTRA.DAT").mapNotNull { name ->
        val file = File(directory, name)
        if (!file.exists()) null else runCatching {
            val b = file.readBytes()
            val bpl = Ed4Archive.word(b, 0); val height = Ed4Archive.word(b, 2)
            require(bpl in 1..160 && height in 1..600 && 52 + bpl * height * 4 <= b.size)
            val width = bpl * 8; val planeSize = bpl * height
            val palette = IntArray(16) { i -> Color.rgb(b[4+i*3].toInt() and 255, b[5+i*3].toInt() and 255, b[6+i*3].toInt() and 255) }
            val pixels = IntArray(width * height) { i ->
                val x = i % width; val y = i / width; var index = 0
                for (plane in 0 until 4) index = index or (((b[52 + plane * planeSize + y * bpl + x / 8].toInt() ushr (7-x%8)) and 1) shl plane)
                palette[index]
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private fun actorFrames(palette: IntArray, resourceId: Int, rows: Int, motions: Map<Int,ByteArray>): Map<Int,Bitmap> {
        val data = resource(resourceId)
        require(rows in 1..8 && data.size == rows * 2048) { "미지원 캐릭터 그래픽 형식" }
        val source = tiles(data)
        // DATA11 800b/89xx: each motion contains four bottom-to-top references per column.
        return motions.filterValues { pose -> pose.all { (it.toInt() and 255) <= source.size } }.mapValues { (_,pose) ->
            val colors = IntArray(32*64)
            for (col in 0..1) for (row in 0..3) {
                val reference = pose[col*4+row].toInt() and 255
                if (reference == 0) continue
                val tile = source[reference-1]
                for (y in 0 until 16) for (x in 0 until 16) {
                    val v = tile[y*16+x]
                    colors[((3-row)*16+y)*32+col*16+x] = if (v==15) Color.TRANSPARENT else palette[v]
                }
            }
            Bitmap.createBitmap(colors,32,64,Bitmap.Config.ARGB_8888)
        }
    }
}
