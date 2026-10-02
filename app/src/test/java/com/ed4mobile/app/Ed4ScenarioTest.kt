package com.ed4mobile.app
import org.junit.Test
import org.junit.Assert.*
import java.nio.charset.Charset
class Ed4ScenarioTest {
    private fun fixture(bytes: ByteArray): Ed4Scenario {
        val script = ByteArray(80) + bytes
        script[34] = 40
        "테스트 화자".toByteArray(Charset.forName("MS949")).copyInto(script,40)
        return Ed4Scenario(script, ByteArray(8192), listOf("어빈","마일"))
    }
    @Test fun flagAndDialogueYieldRollback() {
        val code = byteArrayOf(0x0b,0xc4.toByte(),0,0x13,1,0x0f) + "안녕하세요".toByteArray(Charset.forName("MS949")) + byteArrayOf(0,0x5c)
        val vm=fixture(code)
        val event=vm.start(80) as Ed4Scenario.Event.Speech
        assertEquals("마일",event.speaker)
        assertEquals("안녕하세요",event.pages.single())
        assertEquals(1,vm.byte(0x4b0))
        assertEquals(0,vm.checkpoint()[0x4b0].toInt())
        assertTrue(vm.resume() is Ed4Scenario.Event.Halt)
        assertEquals(0,vm.byte(0x4b0))
    }
    @Test fun variableAssignmentPreservesOperandBoundary() {
        val vm=fixture(byteArrayOf(4,0xc4.toByte(),0x35,5,0x72,0))
        assertEquals(Ed4Scenario.Event.Done,vm.start(80))
        assertEquals(5,vm.byte(0x435))
    }
    @Test fun callsReturnAndPagesStaySeparate() {
        val code=byteArrayOf(7,84,0,0,0x13,0,0x0f)+"첫 문장".toByteArray(Charset.forName("MS949"))+byteArrayOf(2)+"둘째 문장".toByteArray(Charset.forName("MS949"))+byteArrayOf(0,0)
        val vm=fixture(code)
        val speech=vm.start(80) as Ed4Scenario.Event.Speech
        assertEquals("어빈",speech.speaker)
        assertEquals(listOf("첫 문장","둘째 문장"),speech.pages)
        assertEquals(Ed4Scenario.Event.Done,vm.resume())
    }
    @Test fun conditionalBranchUsesOriginalFlagSemantics() {
        val vm=fixture(byteArrayOf(1,0xc4.toByte(),0,0x61,0x66,0x60,95,0,0x0b,0xc4.toByte(),0,0,0,0,0,0))
        assertEquals(Ed4Scenario.Event.Done,vm.start(80))
        assertEquals(1,vm.byte(0x4b0))
        assertEquals(Ed4Scenario.Event.Done,vm.start(80))
        assertEquals(1,vm.byte(0x4b0))
    }
    @Test fun mapChangesRollbackAlongWithFlags() {
        val grid=ByteArray(20480); grid[0]=42; grid[2]=43
        val code=byteArrayOf(0x49,0,0,2,1,0,1,0x0f,65,0,0x5c)
        val vm=Ed4Scenario(ByteArray(80)+code,ByteArray(8192),listOf("어빈"),grid)
        assertTrue(vm.start(80) is Ed4Scenario.Event.Speech)
        assertEquals(42,vm.map[256].toInt()); assertEquals(43,vm.map[258].toInt())
        assertEquals(0,vm.mapCheckpoint()[256].toInt())
        assertTrue(vm.resume() is Ed4Scenario.Event.Halt)
        assertEquals(0,vm.map[256].toInt())
    }
    @Test fun rejectedMapTransitionRetainsRollbackSnapshot() {
        val vm=fixture(byteArrayOf(0x0b,0xc4.toByte(),0,0x14,1,0x2c,0x90.toByte(),0,0))
        assertTrue(vm.start(80) is Ed4Scenario.Event.MapChange)
        assertEquals(1,vm.byte(0x4b0)); assertEquals(0,vm.checkpoint()[0x4b0].toInt())
        vm.cancel(); assertEquals(0,vm.byte(0x4b0))
    }
    @Test fun regionTriggerChecksBoundsHeightAndInputMask() {
        val b=ByteArray(100); b[28]=40; b[30]=1
        byteArrayOf(3,4,21,0x21,2,1,80,0).copyInto(b,40)
        val vm=Ed4Scenario(b,ByteArray(8192),emptyList())
        vm.putByte(0x20,3);vm.putByte(0x21,4);vm.putByte(0x22,21);vm.putByte(0x23,2)
        assertEquals(80,vm.trigger(1)); assertEquals(80,vm.trigger(0x20)); assertEquals(0,vm.trigger(4))
        vm.putByte(0x22,20);assertEquals(0,vm.trigger(1))
    }
    @Test fun queryOpcodeHasNoOperandAndWorksInsideExpressions() {
        val vm=fixture(byteArrayOf(4,0x7b,0x2b,0x60,0x0b,0xc4.toByte(),0,0))
        vm.putByte(0x1b,1)
        assertEquals(Ed4Scenario.Event.Done,vm.start(80)); assertEquals(1,vm.byte(0x4b0))
    }
}
