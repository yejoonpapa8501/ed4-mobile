package com.ed4mobile.app

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import android.util.Base64
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var game: FieldView
    private var assets: Ed4Assets? = null
    private var loading = false
    private var dialogDepth = 0
    private val saves by lazy { getSharedPreferences("native-scenario-save-v1", MODE_PRIVATE) }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            background("원본 게임을 읽는 중…") {
                importGame(uri)
                assets = Ed4Assets(dataDirectory())
                assets!!.initialScene()
            }
        }
    }
    private fun dataDirectory() = File(filesDir, "ed4native")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        game = FieldView()
        setContentView(game)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!loading) { if (game.opening) game.skipOpening() else if (game.hasDialogue()) game.cancelDialogue() else showMenu() } }
        })
        if (File(dataDirectory(), "DATA_A.DAT").exists()) {
            background("원본 필드를 준비하는 중…") {
                assets = Ed4Assets(dataDirectory())
                val index = saves.getInt("resume.scene", 0).coerceIn(assets!!.sceneIds.indices)
                if (saves.contains("resume.resource")) assets!!.sceneResource(saves.getInt("resume.resource", 0), saves.getInt("resume.stage", 0))
                else if (saves.contains("resume.scene")) assets!!.scene(index) else assets!!.initialScene()
            }
        } else game.message = "영웅전설 IV · 주홍물방울\n화면을 눌러 보유하신 ed4.zip을 선택해 주세요"
    }
    override fun onPause() { super.onPause(); if (::game.isInitialized) { game.paused = true; saveResume() } }
    override fun onResume() { super.onResume(); if (::game.isInitialized) { game.paused = dialogDepth > 0; game.resetClock() } }
    private fun background(message: String, work: () -> Ed4Assets.Scene) {
        if (loading) return
        loading = true; game.message = message; game.invalidate()
        Thread {
            val result = runCatching { work() }
            runOnUiThread {
                loading = false
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.let { it.image.recycle(); it.frames.forEach { frame -> frame.recycle() }; it.npcFrames.values.flatMap { it.values }.forEach { frame -> frame.recycle() } }
                    return@runOnUiThread
                }
                result.onSuccess {
                    if (game.scene == null && saves.contains("resume.x")) {
                        game.restoreX = saves.getFloat("resume.x", 536f)
                        game.restoreY = saves.getFloat("resume.y", 660f)
                        game.speed = saves.getInt("resume.speed", 2).coerceIn(1, 3)
                        game.pendingMap = saves.getString("resume.map", null)?.let { value -> runCatching { Base64.decode(value,Base64.DEFAULT) }.getOrNull() }
                        game.restoreActors = true
                        game.pendingState = saves.getString("resume.state", null)?.let { value -> runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull() }
                    }
                    game.applyScene(it)
                }.onFailure {
                    game.cancelDialogue()
                    game.restoreX = null; game.restoreY = null; game.pendingState = null; game.pendingMap = null; game.restoreActors = false
                    game.message = if (game.scene == null) "불러오기 실패\n화면을 눌러 ZIP을 다시 선택해 주세요" else ""
                    Toast.makeText(this, it.message ?: "게임 데이터를 읽지 못했습니다", Toast.LENGTH_LONG).show()
                }
                game.invalidate()
            }
        }.start()
    }
    private fun importGame(uri: Uri) {
        val temp = File(filesDir, "ed4-import").apply { deleteRecursively(); mkdirs() }
        val required = setOf("DATA_A.DAT", "DATA11.DAT", "DATA12.DAT")
        fun destination(entry: String): String? {
            val name = entry.replace('\\', '/').substringAfterLast('/').uppercase(Locale.ROOT)
            return when {
                name.matches(Regex("DATA(_[A-J]|1[0-3])\\.DAT")) || name in setOf("BACK.DAT", "SAMSUNG.DAT", "MANTRA.DAT") -> name
                name.matches(Regex("[A-Z0-9_]+\\.EFC")) -> "EFFECT/$name"
                else -> null
            }
        }
        try {
            val input = contentResolver.openInputStream(uri) ?: error("ZIP을 읽을 수 없습니다")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = destination(e.name)
                    if (!e.isDirectory && name != null) {
                        require(!File(temp, name).exists()) { "ZIP에 중복 데이터가 있습니다" }
                        File(temp, name).apply { parentFile!!.mkdirs() }.outputStream().use { output ->
                            val buffer = ByteArray(65536); var fileSize = 0L
                            while (true) {
                                val n = zip.read(buffer); if (n < 0) break
                                fileSize += n; total += n
                                require(fileSize <= 8 * 1024 * 1024 && total <= 32 * 1024 * 1024) { "게임 데이터 크기 제한 초과" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(required.all { File(temp, it).exists() }) { "영웅전설4 필드 데이터를 찾지 못했습니다" }
            val check = Ed4Assets(temp).scene(0)
            check.image.recycle(); check.frames.forEach { it.recycle() }; check.npcFrames.values.flatMap { it.values }.forEach { it.recycle() }
            val old = dataDirectory(); val backup = File(filesDir, "ed4-backup").apply { deleteRecursively() }
            if (old.exists()) require(old.renameTo(backup))
            if (!temp.renameTo(old)) { backup.renameTo(old); error("게임 데이터를 저장하지 못했습니다") }
            backup.deleteRecursively()
            saves.edit().clear().apply()
        } finally { temp.deleteRecursively() }
    }
    private fun choose() { if (!loading) picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    private fun saveResume() {
        if (loading || game.scene == null) return
        saves.edit().putInt("resume.scene", game.sceneIndex).putFloat("resume.x", game.heroX)
            .putFloat("resume.y", game.heroY).putInt("resume.speed", game.speed)
            .putInt("resume.resource", game.scene!!.id).putInt("resume.stage", game.scene!!.stage)
            .putString("resume.map", game.scenario?.mapCheckpoint()?.let { Base64.encodeToString(it,Base64.NO_WRAP) })
            .putString("resume.state", game.scenario?.checkpoint()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()
    }
    private fun showMenu() {
        if (game.scene == null) { choose(); return }
        game.paused = true; dialogDepth++
        val items = arrayOf("계속하기", "이동 속도 ${game.speed}×", "진행 상태 저장", "진행 상태 불러오기", "다른 원본 맵 보기", "원본 ZIP 다시 선택")
        AlertDialog.Builder(this).setTitle("영웅전설 IV").setItems(items) { _, item ->
            when (item) {
                1 -> game.speed = game.speed % 3 + 1
                2 -> {
                    saves.edit().putInt("resource", game.scene!!.id).putInt("stage", game.scene!!.stage)
                        .putFloat("x", game.heroX).putFloat("y", game.heroY).putInt("speed", game.speed)
                        .putString("map", game.scenario?.mapCheckpoint()?.let { Base64.encodeToString(it,Base64.NO_WRAP) })
                        .putString("state", game.scenario?.checkpoint()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()
                    toast("진행 상태를 저장했습니다")
                }
                3 -> if (saves.contains("x")) {
                    game.restoreX = saves.getFloat("x", 512f); game.restoreY = saves.getFloat("y", 640f)
                    game.speed = saves.getInt("speed", 2).coerceIn(1, 3)
                    game.pendingMap = saves.getString("map", null)?.let { value -> runCatching { Base64.decode(value,Base64.DEFAULT) }.getOrNull() }
                    game.restoreActors = true
                    game.pendingState = saves.getString("state", null)?.let { value -> runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull() }
                    if (saves.contains("resource")) loadResource(saves.getInt("resource", 0), saves.getInt("stage", 0)) else loadScene(saves.getInt("scene", 0))
                } else toast("저장된 진행 상태가 없습니다")
                4 -> showScenes()
                5 -> choose()
            }
        }.setOnDismissListener { dialogDepth--; game.paused = dialogDepth > 0; game.resetClock() }.show()
    }
    private fun showScenes() {
        val a = assets ?: return
        game.paused = true; dialogDepth++
        AlertDialog.Builder(this).setTitle("원본 맵 탐색").setItems(a.sceneIds.map { "필드 ${it + 1}" }.toTypedArray()) { _, i -> loadScene(i) }
            .setOnDismissListener { dialogDepth--; game.paused = dialogDepth > 0; game.resetClock() }.show()
    }
    private fun loadScene(index: Int) {
        val a = assets ?: return
        val target = index.coerceIn(a.sceneIds.indices)
        game.pendingState = game.scenario?.checkpoint()
        background("원본 필드를 준비하는 중…") { a.scene(target) }
    }
    private fun loadResource(resource: Int, stage: Int) {
        val a = assets ?: return
        background("원본 필드를 준비하는 중…") { a.sceneResource(resource, stage) }
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    inner class FieldView : View(this@MainActivity) {
        var scene: Ed4Assets.Scene? = null
        var scenario: Ed4Scenario? = null
        var pendingState: ByteArray? = null
        var pendingMap: ByteArray? = null
        var restoreActors = false
        private var speech: Ed4Scenario.Event.Speech? = null
        private var speechPage = 0
        private var approaching = 0
        private var lastTrigger = 0
        private var tickStopped = false
        private var nextTick = 0L
        private val entryEvents = ArrayDeque<Int>()
        private var dialogueBox = RectF()
        var sceneIndex = 0
        var message = ""
        var paused = false
        var opening = false
        private var logos: List<Bitmap> = emptyList()
        private var logoIndex = 0
        var speed = 2
        var heroX = 536f; var heroY = 660f
        var restoreX: Float? = null; var restoreY: Float? = null
        private var targetX = heroX; private var targetY = heroY
        private var clock = 0L
        private var walked = 0f
        private var direction = 3
        private val camera = RectF()
        private val field = RectF()
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        private var menuButton = RectF()
        fun resetClock() { clock = 0L; invalidate() }
        fun skipOpening() { opening = false; resetClock() }
        fun cancelDialogue() { entryEvents.clear(); scenario?.cancel(); readHero(); scene?.let { s -> scenario?.let { vm -> assets?.refreshMap(s,vm.map) } }; speech = null; approaching = 0; resetClock() }
        fun applyScene(next: Ed4Assets.Scene) {
            val first = scene == null
            scene?.let { it.image.recycle(); it.frames.forEach { b -> b.recycle() }; it.npcFrames.values.flatMap { it.values }.forEach { b -> b.recycle() } }
            scene = next; sceneIndex = assets!!.sceneIds.indexOf(next.id).coerceAtLeast(0); message = ""
            scenario = Ed4Scenario(next.script, next.seed, next.characterNames, next.grid)
            val restoredActors = restoreActors && pendingState?.size == 0x2000
            pendingState?.takeIf { it.size == 0x2000 }?.copyInto(scenario!!.memory); pendingState = null; restoreActors = false
            val vm = scenario!!
            pendingMap?.takeIf { it.size == vm.map.size }?.copyInto(vm.map); pendingMap = null
            assets?.refreshMap(next, vm.map)
            val count = Ed4Archive.word(next.script, 22)
            require(count in 1..64)
            vm.putWord(0x420,count); vm.putWord(0x12,next.id); vm.putByte(0x19,next.stage)
            next.script.copyInto(vm.memory,8,0,10)
            if (!restoredActors) {
                vm.memory.fill(0,0x430,0x436)
                val offset = 36+next.stage*14
                next.script.copyInto(vm.memory,0,offset+4,offset+10)
                vm.putByte(2,vm.byte(2) and 127); vm.putByte(4,vm.byte(4) and 127)
            }
            if (!restoredActors) for (index in 1 until count) {
                val offset = Ed4Archive.word(next.script, 20) + (index - 1) * 6
                val actor = 0x20 + index * 16
                vm.putWord(actor, Ed4Archive.word(next.script, offset)); vm.putByte(actor + 2, next.script[offset + 2].toInt())
                vm.putWord(actor + 4, Ed4Archive.word(next.script, offset + 3)); vm.putByte(actor + 3, vm.byte(actor + 4) and 6)
                vm.putWord(actor + 6,vm.byte(actor + 2)*257); vm.putWord(actor+10,0); vm.putByte(actor+13,0); vm.putWord(actor+14,0)
                vm.putByte(actor + 12, next.script[offset + 5].toInt())
            }
            speech = null; approaching = 0; lastTrigger = 0; tickStopped = false; nextTick = 0L; entryEvents.clear()
            if (first) { logos = assets?.openingImages() ?: emptyList(); logoIndex = 0; opening = logos.isNotEmpty() }
            val spawn = Ed4Layout.spawn(next.script,next.stage,Ed4Terrain.Position(vm.byte(0x20),vm.byte(0x21),vm.byte(0x22)))
            heroX = (restoreX ?: (spawn.x+1)*16f).coerceIn(16f, next.image.width - 16f)
            heroY = (restoreY ?: (spawn.y+1)*16f).coerceIn(48f, next.image.height - 16f)
            val spawnOffset = 36 + next.stage * 14
            if (!restoredActors) vm.putByte(0x22,spawn.z)
            direction = if (restoredActors) (vm.byte(0x23) and 6)/2 else ((next.script[spawnOffset + 3].toInt() and 6) / 2)
            restoreX = null; restoreY = null; targetX = heroX; targetY = heroY; walked = 0f; syncHero(); resetClock()
            if (!restoredActors) {
                listOf(14,16).map { Ed4Archive.word(next.script,it) }.filter { it != 0 }.forEach { entryEvents.addLast(it) }
                if (entryEvents.isNotEmpty()) showEvent(vm.start(entryEvents.removeFirst()))
            }
        }
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(10, 14, 21))
            val s = scene
            if (s == null || message.isNotEmpty()) { centerMessage(c, message); return }
            if (opening) {
                val b = logos[logoIndex]
                val scale = minOf(width.toFloat()/b.width, height.toFloat()/b.height)
                val dw = b.width*scale; val dh = b.height*scale
                brush.isFilterBitmap = false
                c.drawBitmap(b, null, RectF((width-dw)/2, (height-dh)/2, (width+dw)/2, (height+dh)/2), brush)
                brush.color = Color.WHITE; brush.textAlign = Paint.Align.CENTER; brush.textSize = 18f*resources.displayMetrics.density
                c.drawText("터치하여 계속", width/2f, height-20f, brush); brush.textAlign = Paint.Align.LEFT
                return
            }
            val now = System.nanoTime()
            val dt = if (clock == 0L) 0f else ((now - clock) / 1_000_000_000f).coerceAtMost(.05f)
            clock = now
            val dx = targetX - heroX; val dy = targetY - heroY; val distance = hypot(dx, dy)
            var moving = distance > .1f && !paused && speech == null
            if (moving) {
                direction = if (abs(dx) > abs(dy)) if (dx < 0) 0 else 2 else if (dy < 0) 1 else 3
                val step = minOf(80f * speed * dt, distance)
                moveHero(heroX + dx / distance * step, heroY + dy / distance * step)
                walked += step
                syncHero()
                val trigger = scenario!!.trigger(intArrayOf(4,1,8,2)[direction])
                if (trigger == 0) lastTrigger = 0
                else if (trigger != lastTrigger) { lastTrigger = trigger; targetX = heroX; targetY = heroY; approaching = 0; showEvent(scenario!!.start(trigger)) }
                if (approaching > 0 && distance <= 34f) { targetX = heroX; targetY = heroY; val actor = approaching; approaching = 0; interact(actor) }
            }
            moving = hypot(targetX-heroX,targetY-heroY) > .1f && !paused && speech == null && !loading
            val scale = width / 640f
            val top = 38f * scale; val bottom = 25f * scale
            field.set(0f, top, width.toFloat(), height - bottom)
            val pixelScale = maxOf(field.width() / minOf(640f, s.image.width.toFloat()), field.height() / s.image.height)
            val vw = field.width() / pixelScale
            val vh = field.height() / pixelScale
            val left = (heroX - vw / 2).coerceIn(0f, s.image.width - vw)
            val upper = (heroY - vh * .60f).coerceIn(0f, s.image.height - vh)
            camera.set(left, upper, left + vw, upper + vh)
            brush.isFilterBitmap = false
            c.drawBitmap(s.image, Rect(camera.left.toInt(), camera.top.toInt(), camera.right.toInt(), camera.bottom.toInt()), field, brush)
            fun sx(wx: Float) = field.left + (wx - camera.left) / camera.width() * field.width()
            fun sy(wy: Float) = field.top + (wy - camera.top) / camera.height() * field.height()
            c.save(); c.clipRect(field)
            if (moving) {
                brush.color = Color.argb(190, 236, 211, 123); brush.style = Paint.Style.STROKE; brush.strokeWidth = 1.5f * scale
                c.drawOval(RectF(sx(targetX) - 7 * scale, sy(targetY) - 3 * scale, sx(targetX) + 7 * scale, sy(targetY) + 3 * scale), brush); brush.style = Paint.Style.FILL
            }
            brush.color = Color.argb(95, 0, 0, 0)
            c.drawOval(RectF(sx(heroX) - 11 * scale, sy(heroY) - 3 * scale, sx(heroX) + 11 * scale, sy(heroY) + 3 * scale), brush)
            val vm = scenario!!
            val actors = visibleActors().map { index -> index to ((vm.byte(0x20 + index * 16 + 1) + 1) * 16f) }.toMutableList()
            actors.add(0 to heroY)
            actors.sortedBy { it.second }.forEach { (index, _) ->
                if (index == 0) {
                    val frame = direction * 2 + if (moving) (walked / 9).toInt() % 2 else 0
                    val bitmap = s.frames[frame]
                    c.drawBitmap(bitmap, null, RectF(sx(heroX - 16), sy(heroY + 2 - bitmap.height), sx(heroX + 16), sy(heroY + 2)), brush)
                } else {
                    val a = 0x20 + index * 16
                    val frames = s.npcFrames[vm.byte(a + 5)] ?: return@forEach
                    val frame = vm.byte(a + 4)
                    val x = (vm.byte(a) + 1) * 16f; val y = (vm.byte(a + 1) + 1) * 16f
                    val bitmap = frames[frame] ?: return@forEach
                    c.drawBitmap(bitmap, null, RectF(sx(x - 16), sy(y + 2 - bitmap.height), sx(x + 16), sy(y + 2)), brush)
                }
            }
            c.restore()
            brush.color = Color.rgb(12, 21, 34); c.drawRect(0f, 0f, width.toFloat(), top, brush)
            brush.color = Color.rgb(219, 197, 142); brush.textSize = 16 * scale
            c.drawText("영웅전설 IV", 12 * scale, 24 * scale, brush)
            brush.color = Color.LTGRAY; brush.textSize = 10 * scale
            c.drawText("어빈  ·  필드 ${s.id + 1}  ·  ${speed}×", 145 * scale, 23 * scale, brush)
            menuButton.set(width - 67 * scale, 3 * scale, width - 5 * scale, top - 3 * scale)
            brush.color = Color.rgb(35, 50, 67); c.drawRoundRect(menuButton, 4 * scale, 4 * scale, brush)
            brush.color = Color.WHITE; brush.textSize = 12 * scale; c.drawText("메뉴", width - 52 * scale, 24 * scale, brush)
            brush.color = Color.rgb(12, 21, 34); c.drawRect(0f, height - bottom, width.toFloat(), height.toFloat(), brush)
            brush.color = Color.LTGRAY; brush.textSize = 10 * scale
            c.drawText("터치하여 이동  ·  마을 사람을 터치하여 대화", 12 * scale, height - 8 * scale, brush)
            if (speech != null) drawSpeech(c)
            if (!paused && !loading && speech == null && entryEvents.isEmpty() && !tickStopped && now >= nextTick) {
                nextTick = now + 100_000_000L
                val offset = Ed4Archive.word(s.script,18)
                if (offset != 0) showEvent(scenario!!.start(offset))
            }
            if (moving || (!tickStopped && speech == null && !paused && !loading)) postInvalidateOnAnimation()
        }
        private fun centerMessage(c: Canvas, text: String) {
            brush.color = Color.rgb(226, 212, 172); brush.textAlign = Paint.Align.CENTER; brush.textSize = (width / 40f).coerceIn(18f, 38f)
            text.split('\n').forEachIndexed { i, line -> c.drawText(line, width / 2f, height / 2f + i * brush.textSize * 1.8f, brush) }
            brush.textAlign = Paint.Align.LEFT
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            if (loading) return true
            if (scene == null) { choose(); return true }
            if (opening) { logoIndex++; if (logoIndex >= logos.size) opening = false; resetClock(); return true }
            if (speech != null) {
                if (dialogueBox.contains(e.x, e.y)) {
                    val current = speech!!
                    if (++speechPage >= current.pages.size) { speech = null; showEvent(scenario!!.resume()) }
                    resetClock()
                }
                return true
            }
            if (menuButton.contains(e.x, e.y)) { showMenu(); return true }
            if (paused || !field.contains(e.x, e.y)) return true
            val s = scene ?: return true
            targetX = (camera.left + (e.x - field.left) / field.width() * camera.width()).coerceIn(16f, s.image.width - 16f)
            targetY = (camera.top + (e.y - field.top) / field.height() * camera.height()).coerceIn(48f, s.image.height - 16f)
            approaching = 0
            val vm = scenario!!
            visibleActors().firstOrNull { index ->
                val a = 0x20 + index * 16
                val x = (vm.byte(a) + 1) * 16f; val y = (vm.byte(a + 1) + 1) * 16f
                targetX in x-20..x+20 && targetY in y-(s.npcFrames[vm.byte(a+5)]?.values?.firstOrNull()?.height ?: 48)..y+10
            }?.let { index ->
                approaching = index
                val a = 0x20 + index * 16
                targetX = (vm.byte(a) + 1) * 16f; targetY = (vm.byte(a + 1) + 1) * 16f
                if (hypot(targetX - heroX, targetY - heroY) <= 48f) { targetX = heroX; targetY = heroY; approaching = 0; interact(index) }
            }
            resetClock(); return true
        }
        fun hasDialogue() = speech != null
        private fun moveHero(nextX: Float, nextY: Float) {
            val s = scene ?: return; val vm = scenario ?: return
            val from = Ed4Terrain.Position(vm.byte(0x20),vm.byte(0x21),vm.byte(0x22))
            val x = (nextX/16-1).toInt(); val y = (nextY/16-1).toInt()
            if (x == from.x && y == from.y) { heroX = nextX; heroY = nextY; return }
            val candidate = s.terrain.move(from,(x-from.x).coerceIn(-1,1),(y-from.y).coerceIn(-1,1),s.footprints[direction*2] ?: 0x77)
            val occupied = candidate != null && visibleActors().any { index ->
                val a = 0x20+index*16
                vm.byte(a+12) and 0xa0 == 0 && abs(vm.byte(a)-candidate.x)<2 &&
                    vm.byte(a+1)+vm.byte(a+2) == candidate.y+candidate.z && abs(vm.byte(a+1)-candidate.y)<3
            }
            if (candidate == null || occupied) { targetX = heroX; targetY = heroY; approaching = 0; return }
            heroX = nextX; heroY = nextY + (candidate.y-y)*16
            vm.putByte(0x22,candidate.z)
        }
        private fun readHero() {
            val vm = scenario ?: return; val s = scene ?: return
            if (vm.byte(0x20) != (heroX/16-1).toInt() || vm.byte(0x21) != (heroY/16-1).toInt()) {
                heroX = ((vm.byte(0x20)+1)*16f).coerceIn(16f,s.image.width-16f)
                heroY = ((vm.byte(0x21)+1)*16f).coerceIn(48f,s.image.height-16f)
                targetX = heroX; targetY = heroY; approaching = 0
            }
            direction = (vm.byte(0x23) and 6)/2
        }
        private fun syncHero() {
            scenario?.let { vm -> vm.putByte(0x20, (heroX / 16 - 1).toInt()); vm.putByte(0x21, (heroY / 16 - 1).toInt()); vm.putByte(0x23, direction * 2) }
        }
        private fun visibleActors(): List<Int> {
            val s = scene ?: return emptyList(); val vm = scenario ?: return emptyList()
            return (1 until Ed4Archive.word(s.script, 22)).filter { index ->
                val a = 0x20 + index * 16
                (vm.byte(a + 12) and 0x82) == 0 && s.npcFrames.containsKey(vm.byte(a + 5))
            }
        }
        private fun interact(index: Int) {
            val s = scene ?: return
            val offset = Ed4Archive.word(s.script, Ed4Archive.word(s.script, 26) + (index - 1) * 2)
            if (offset != 0) showEvent(scenario!!.start(offset, index))
        }
        private var committedHash = 0
        private fun showEvent(event: Ed4Scenario.Event) {
            readHero()
            val nextHash = scenario!!.checkpoint().contentHashCode() * 31 + scenario!!.mapCheckpoint().contentHashCode()
            val eventHasChanges = nextHash != committedHash
            if (event is Ed4Scenario.Event.Done) committedHash = nextHash
            scene?.let { s -> scenario?.let { vm -> assets?.refreshMap(s, vm.map) } }
            when (event) {
                is Ed4Scenario.Event.Speech -> { speech = event.copy(pages = paginate(event.pages)); speechPage = 0 }
                is Ed4Scenario.Event.MapChange -> { pendingState = scenario!!.transitionState(); loadResource(event.resource, event.stage) }
                is Ed4Scenario.Event.Halt -> { tickStopped = true; entryEvents.clear(); toast("이 이벤트는 아직 지원하지 않습니다 (%04X): %s".format(event.offset, event.reason)) }
                Ed4Scenario.Event.Done -> {
                    if (entryEvents.isNotEmpty()) showEvent(scenario!!.start(entryEvents.removeFirst())) else if (eventHasChanges) saveResume()
                }
            }
            invalidate()
        }
        private fun paginate(pages: List<String>): List<String> {
            brush.textSize = 12*(width/640f)
            val maxWidth = width-56*(width/640f)
            return pages.flatMap { page ->
                val lines = mutableListOf<String>()
                page.split('\n').forEach { paragraph ->
                    var rest = paragraph
                    if (rest.isEmpty()) lines.add("")
                    while (rest.isNotEmpty()) {
                        val count = brush.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                        lines.add(rest.substring(0,count)); rest = rest.substring(count)
                    }
                }
                lines.chunked(5).map { it.joinToString("\n") }
            }
        }
        private fun drawSpeech(c: Canvas) {
            val current = speech ?: return
            val scale = width / 640f
            dialogueBox.set(16*scale, height-154*scale, width-16*scale, height-32*scale)
            brush.color = Color.argb(245, 11, 20, 35); c.drawRoundRect(dialogueBox, 6*scale, 6*scale, brush)
            brush.style = Paint.Style.STROKE; brush.strokeWidth = 1.5f*scale; brush.color = Color.rgb(218, 196, 140)
            c.drawRoundRect(dialogueBox, 6*scale, 6*scale, brush); brush.style = Paint.Style.FILL
            brush.textSize = 13*scale; c.drawText(current.speaker, dialogueBox.left+12*scale, dialogueBox.top+20*scale, brush)
            brush.color = Color.WHITE; brush.textSize = 12*scale
            var y = dialogueBox.top+41*scale
            current.pages[speechPage].split('\n').forEach { paragraph ->
                var rest = paragraph
                while (rest.isNotEmpty()) {
                    val count = brush.breakText(rest, true, dialogueBox.width()-24*scale, null).coerceAtLeast(1)
                    c.drawText(rest.substring(0,count), dialogueBox.left+12*scale, y, brush); y+=16*scale; rest=rest.substring(count)
                }
            }
            brush.color = Color.rgb(218, 196, 140); brush.textSize = 9*scale
            c.drawText("터치하여 계속  ▼", dialogueBox.right-90*scale, dialogueBox.bottom-8*scale, brush)
        }
    }
}
