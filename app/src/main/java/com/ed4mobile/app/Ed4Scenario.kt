package com.ed4mobile.app

import java.nio.charset.Charset

/** Native translation of DATA11's c57d/c595 dispatcher and dc72 expression machine.
 * Unknown instructions stop execution; never guess their operand lengths. */
class Ed4Scenario(val script: ByteArray, seed: ByteArray, private val characterNames: List<String>) {
    val memory = seed.copyOf(0x2000)
    var pc = 0; private set
    var actor = 0x20
    var speaker = ""
    private var ax = 0
    private val returns = ArrayDeque<Int>()
    private var active = false
    private var budget = 0
    private var before: ByteArray? = null
    sealed class Event {
        data class Speech(val speaker: String, val pages: List<String>) : Event()
        data class MapChange(val resource: Int, val stage: Int, val fade: Int) : Event()
        data class Halt(val offset: Int, val reason: String) : Event()
        data object Done : Event()
    }
    fun byte(address: Int): Int { require(address in memory.indices); return memory[address].toInt() and 255 }
    fun word(address: Int): Int = byte(address) or (byte(address + 1) shl 8)
    fun putByte(address: Int, value: Int) { require(address in memory.indices); memory[address] = value.toByte() }
    fun putWord(address: Int, value: Int) { putByte(address, value); putByte(address + 1, value ushr 8) }
    private fun next(): Int { require(pc in script.indices) { "이벤트 데이터 범위 초과" }; return script[pc++].toInt() and 255 }
    private fun nextWord(): Int = next() or (next() shl 8)
    private fun flag(index: Int) = (byte(0x430 + (index ushr 3)) and (1 shl (index and 7))) != 0
    private fun setFlag(index: Int, enabled: Boolean) {
        val address = 0x430 + (index ushr 3); val mask = 1 shl (index and 7)
        putByte(address, if (enabled) byte(address) or mask else byte(address) and mask.inv())
    }
    private fun value(): Int {
        val tag = next()
        if (tag < 0x80) return tag
        if ((tag and 0x40) != 0) return if ((tag and 0x20) != 0) nextWord() else ((tag and 31) shl 8) or next()
        val address = ((tag and 31) shl 8) or next()
        return if ((tag and 0x20) != 0) word(address) else byte(address)
    }
    private fun expression(initial: List<Int> = emptyList()): Int {
        val stack = initial.toMutableList()
        fun pop(): Int { require(stack.isNotEmpty()) { "이벤트 계산 스택 오류" }; return stack.removeAt(stack.lastIndex) }
        repeat(2048) {
            val tag = script.getOrNull(pc)?.toInt()?.and(255) ?: error("계산 데이터가 잘렸습니다")
            if (tag >= 0x80 || tag < 0x60) { stack.add(value()); return@repeat }
            pc++
            if (tag == 0x60) return pop()
            if (tag == 0x7b) {
                val instruction = next()
                val result = when (instruction) {
                    0x69 -> { val item = next(); (byte(0x8e0 + item / 2) ushr ((item and 1) * 4)) and 3 }
                    else -> error("미지원 중첩 계산 명령 %02X".format(instruction))
                }
                stack.add(result); return@repeat
            }
            val right = pop()
            val result = when (tag) {
                0x61 -> if (flag(right)) 0xffff else 0
                0x62 -> if (pop() == right) 0xffff else 0
                0x63 -> if (pop() != right) 0xffff else 0
                0x64 -> if (pop() < right) 0xffff else 0
                0x65 -> if (pop() > right) 0xffff else 0
                0x66 -> if (right == 0) 0xffff else 0
                0x67 -> if (pop() != 0 && right != 0) 0xffff else 0
                0x68 -> pop() and right
                0x69 -> pop() or right
                0x6a -> pop() + right
                0x6b -> pop() - right
                0x6c -> -right
                0x6d -> pop() xor right
                0x6e -> pop() * right
                0x6f -> { val left = pop(); if (right == 0) 0xffff else left / right }
                0x70 -> { val left = pop(); if (right == 0) 0 else left % right }
                0x71 -> { val address = (right and 0x1fff) + actor; if ((right and 0x2000) != 0) word(address) else byte(address) }
                in 0x72..0x7a -> {
                    val destination = pop(); val address = destination and 0x1fff
                    val wide = (destination and 0x2000) != 0
                    val old = if (wide) word(address) else byte(address)
                    val updated = when (tag) {
                        0x72 -> right; 0x73 -> old * right; 0x74 -> if (right == 0) 0xffff else old / right
                        0x75 -> if (right == 0) 0 else old % right; 0x76 -> old + right; 0x77 -> old - right
                        0x78 -> old and right; 0x79 -> old xor right; else -> old or right
                    }
                    if (wide) putWord(address, updated) else putByte(address, updated)
                    // These handlers return from dc72 immediately (they are assignments).
                    return right
                }
                0x7c -> { val index = pop(); setFlag(index, right != 0); return index }
                else -> error("미지원 계산 명령 %02X".format(tag))
            }
            stack.add(result and 0xffff)
        }
        error("이벤트 계산 실행 한도")
    }
    fun start(offset: Int, actorIndex: Int = 0): Event {
        require(offset in script.indices)
        before = memory.copyOf(); pc = offset; actor = 0x20 + actorIndex * 16
        returns.clear(); active = true; budget = 0; speaker = ""
        return resume()
    }
    fun transitionState(): ByteArray = memory.copyOf()
    fun checkpoint(): ByteArray = (before ?: memory).copyOf()
    fun cancel() { before?.copyInto(memory); before = null; active = false; returns.clear() }
    fun resume(): Event {
        if (!active) return Event.Done
        val offset = pc
        return try {
            while (budget++ < 10000) {
                val instruction = next()
                when (instruction) {
                    0 -> if (returns.isEmpty()) { active = false; before = null; return Event.Done } else pc = returns.removeLast()
                    1 -> { ax = expression(); val destination = nextWord(); if (ax == 0) pc = destination }
                    2 -> pc = nextWord()
                    3 -> { ax = expression(); val count = nextWord(); require(count <= 256); val cases = List(count) { nextWord() to nextWord() }; val default = nextWord(); pc = cases.firstOrNull { it.first == ax }?.second ?: default }
                    4 -> ax = expression()
                    5 -> ax = expression(listOf(actor + next()))
                    6 -> ax = expression(listOf(value()))
                    7, 0x0e -> { val target = nextWord(); if (target != 0) { returns.addLast(pc); pc = target } }
                    8 -> { value() /* sound effect requested by original script */ }
                    9, 0x11, 0x18, 0x19, 0x2a, 0x2d, 0x31 -> Unit // render/wait/window refresh: no state operands
                    0x0a -> { val index = value(); ax = if (flag(index)) { setFlag(index, false); 0xffff } else 0 }
                    0x0b -> { val index = value(); ax = if (!flag(index)) { setFlag(index, true); 0xffff } else 0 }
                    0x0f -> return speech()
                    0x10 -> { active = false; before = null; return Event.Done } // original returns control to the field loop
                    0x12 -> {
                        var p = Ed4Archive.word(script, 34)
                        repeat(next() - 1) { while (p < script.size && script[p].toInt() != 0) p++; p++ }
                        val end = (p until script.size).firstOrNull { script[it].toInt() == 0 } ?: error("이름 데이터 오류")
                        speaker = String(script, p, end - p, Charset.forName("MS949"))
                    }
                    0x13 -> { val id = next(); speaker = characterName(id) }
                    0x14 -> { val fade = next(); val resource = nextWord(); val stage = nextWord(); active = false; return Event.MapChange(resource, stage and 255, fade) }
                    0x16 -> { val target = 0x20 + next() * 16; val direction = next(); putByte(target + 3, direction); if (direction < 8) putByte(target + 4, (byte(target + 4) and 0xf9) or (direction and 6)) }
                    0x1b, 0x46 -> putWord(0x10, nextWord())
                    0x1d -> { val target = 0x20 + next() * 16; putByte(target + 12, byte(target + 12) or next()) }
                    0x1e -> { val direction = next(); putByte(actor + 3, direction); if (direction < 8) putByte(actor + 4, (byte(actor + 4) and 0xf9) or (direction and 6)) }
                    0x1f -> { val target = 0x20 + next() * 16; putByte(target + 12, byte(target + 12) and next()) }
                    0x20 -> putByte(actor + 12, byte(actor + 12) and next())
                    0x22 -> { val target = 0x20 + next() * 16; putByte(target + 12, next()) }
                    0x23 -> putWord(0, nextWord()) // original scroll camera
                    0x24 -> { nextWord(); nextWord() /* animated map cell descriptor */ }
                    0x27, 0x2b -> { next() /* delay ticks / fade steps */ }
                    0x28 -> ax = if ((byte(0x1b) and 1) != 0) 0xffff else 0
                    0x2c -> putByte(0x1be6, next())
                    0x2e -> putByte(0x20 + next() * 16 + 4, next())
                    0x2f -> { val target = 0x20 + next() * 16; putWord(target, nextWord()); putByte(target + 2, next()) }
                    0x66 -> next() // original location-title window, separate from scenario text
                    0x67 -> { nextWord(); nextWord() }
                    0x68 -> value()
                    else -> error("미지원 이벤트 명령 %02X".format(instruction))
                }
            }
            error("이벤트 실행 한도")
        } catch (error: Exception) {
            val failed = pc - 1; cancel(); Event.Halt(failed, error.message ?: "이벤트 해석 오류 ($offset)")
        }
    }
    private fun characterName(id: Int): String = characterNames.getOrNull(id) ?: error("등장인물 이름 범위 초과: $id")
    private fun speech(): Event.Speech {
        val pages = mutableListOf<String>(); val text = StringBuilder(); val literal = java.io.ByteArrayOutputStream()
        fun flush() { if (literal.size() > 0) { text.append(String(literal.toByteArray(), Charset.forName("MS949"))); literal.reset() } }
        while (true) {
            val token = next()
            when {
                token == 0 -> { flush(); pages.add(text.toString()); break }
                token >= 32 -> literal.write(token)
                else -> { flush(); when (token) {
                    1 -> text.append('\n')
                    2 -> { pages.add(text.toString()); text.clear() }
                    3, 4, 0x14 -> Unit
                    else -> error("미지원 대사 치환 %02X".format(token))
                } }
            }
        }
        return Event.Speech(speaker, pages.filter { it.isNotBlank() }.ifEmpty { listOf("") })
    }
}
