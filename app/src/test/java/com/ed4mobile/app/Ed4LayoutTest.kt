package com.ed4mobile.app
import org.junit.Test
import org.junit.Assert.*
class Ed4LayoutTest {
    @Test fun firstSpawnAndSpritePointerHaveSeparateOffsets() {
        val b=ByteArray(80); byteArrayOf(0,2,17,6,18,0,0x80.toByte(),32,0,64).copyInto(b,36)
        b[46]=64; b[50]=99
        assertEquals(Ed4Terrain.Position(12,2,38),Ed4Layout.spawn(b,0,Ed4Terrain.Position(12,30,21)))
    }
    @Test fun relativeOffsetsWrapLikeOriginalBytes() {
        val b=ByteArray(80); b[36]=(-1).toByte(); b[37]=5; b[38]=(-4).toByte(); b[42]=0x80.toByte(); b[44]=0x80.toByte()
        assertEquals(Ed4Terrain.Position(9,25,17),Ed4Layout.spawn(b,0,Ed4Terrain.Position(10,20,21)))
    }
}
