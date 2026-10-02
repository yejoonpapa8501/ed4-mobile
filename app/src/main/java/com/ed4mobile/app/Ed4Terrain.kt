package com.ed4mobile.app

/** DATA11 bcf1/bd7a and bdc0/be15: two foot columns, body clearance and floor descent.
 * Composite bits describe solid elevation layers; graphic transparency is not collision. */
class Ed4Terrain(private val grid: ByteArray, private val definitions: ByteArray) {
    init { require(grid.size == 20480 && definitions.size == 8192) }
    data class Position(val x: Int, val y: Int, val z: Int)
    private fun solid(x: Int, y: Int, level: Int): Boolean {
        if (x !in 0..127 || y !in 0..79) return true
        val cell = Ed4Archive.word(grid, (y * 128 + x) * 2)
        val floor = cell and 63
        if (floor >= level) return true
        if (cell and 0x8000 == 0) return false
        val layer = level - floor
        if (layer !in 0..13) return false
        val bits = Ed4Archive.word(definitions, ((cell and 0x7fc0) ushr 2) + 14)
        return bits and (1 shl layer) != 0
    }
    fun move(position: Position, dx: Int, dy: Int, footprint: Int = 0x77): Position? {
        require(dx in -1..1 && dy in -1..1)
        val x = position.x + dx; val y = position.y + dy; val z = position.z
        if (x !in 0..126 || y !in 0..78) return null
        // bcf1 tests four cells above each foot, at progressively higher elevation.
        for (column in 0..1) for (row in 0..3) {
            if (footprint and (1 shl (column * 4 + row)) != 0 && solid(x + column, y - row, z + row)) return null
        }
        val grounds = (0..1).filter { footprint and (2 shl (it * 4)) != 0 }.mapNotNull { column ->
            var groundY = y
            var groundZ = z
            while (groundZ > 1 && groundY < 79) {
                if (solid(x + column, groundY + 1, groundZ - 1)) return@mapNotNull Position(x, groundY, groundZ)
                groundY++; groundZ--
            }
            null
        }
        val ground = grounds.maxByOrNull { it.z } ?: return null
        // b9ee rejects a descent of five or more levels.
        return ground.takeIf { z - it.z < 5 }
    }
}
