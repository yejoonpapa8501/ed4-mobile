package com.ed4mobile.app

/** c39a places spawn records at +36 and sprite/motion pointers at +46 (14-byte stride).
 * 80ec resolves signed relative spawn fields using the preceding map's actor state. */
object Ed4Layout {
    fun spawn(script: ByteArray, stage: Int, previous: Ed4Terrain.Position): Ed4Terrain.Position {
        val offset = 36 + stage * 14
        require(stage in 0..255 && offset + 14 <= script.size)
        fun b(index: Int) = script[offset + index].toInt() and 255
        val relativeX = b(6) and 0x80 != 0
        val relativeY = b(8) and 0x80 != 0
        return Ed4Terrain.Position(
            (b(0) + if (relativeX) previous.x else 0) and 255,
            (b(1) + if (relativeY) previous.y else 0) and 255,
            (b(2) + if (relativeX || relativeY) previous.z else 0) and 255)
    }
}
