package com.ed4mobile.app
import org.junit.Test
import org.junit.Assert.*
class Ed4TerrainTest {
    private fun terrain(floor: Int, mutate: (ByteArray, ByteArray) -> Unit = {_,_->}): Ed4Terrain {
        val grid = ByteArray(20480); val defs = ByteArray(8192)
        for (i in 0 until 10240) grid[i*2] = floor.toByte()
        mutate(grid,defs); return Ed4Terrain(grid,defs)
    }
    private fun cell(grid: ByteArray, x: Int, y: Int, value: Int) { val offset=(y*128+x)*2; grid[offset]=value.toByte(); grid[offset+1]=(value ushr 8).toByte() }
    @Test fun floorSupportsWalkingAndHigherTerrainBlocksBody() {
        val p=Ed4Terrain.Position(10,10,21)
        assertEquals(p.copy(x=11),terrain(20).move(p,1,0))
        assertNull(terrain(21).move(p,1,0))
    }
    @Test fun compositeCollisionUsesSolidLayersAndBothFeet() {
        val p=Ed4Terrain.Position(10,10,21)
        val t=terrain(17) { g,d ->
            // Layer 3 is a floor at level 20; layer 4 blocks the character at 21.
            d[14]=8
            for (x in 11..12) for (y in 0..79) cell(g,x,y,0x8000 or 17)
        }
        assertEquals(p.copy(x=11),t.move(p,1,0))
        val blocked=terrain(17) {g,d -> d[14]=24; cell(g,12,10,0x8000 or 17) }
        assertNull(blocked.move(p,1,0))
    }
    @Test fun descentChangesGroundCoordinateAndRejectsCliffs() {
        val p=Ed4Terrain.Position(10,10,21)
        assertEquals(Ed4Terrain.Position(11,12,19),terrain(18).move(p,1,0))
        assertNull(terrain(15).move(p,1,0))
        assertNull(terrain(20).move(p.copy(x=0),-1,0))
    }
}
