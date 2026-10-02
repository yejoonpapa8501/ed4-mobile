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
}
