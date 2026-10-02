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
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var game: FieldView
    private var assets: Ed4Assets? = null
    private var loading = false
    private var dialogDepth = 0
    private val saves by lazy { getSharedPreferences("native-field-save", MODE_PRIVATE) }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            background("원본 게임을 읽는 중…") {
                importGame(uri)
                assets = Ed4Assets(dataDirectory())
                assets!!.scene(0)
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
            override fun handleOnBackPressed() { if (!loading) { if (game.opening) game.skipOpening() else showMenu() } }
        })
        if (File(dataDirectory(), "DATA_A.DAT").exists()) {
            background("원본 필드를 준비하는 중…") {
                assets = Ed4Assets(dataDirectory())
                val index = saves.getInt("resume.scene", 0).coerceIn(assets!!.sceneIds.indices)
                assets!!.scene(index)
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
                    result.getOrNull()?.let { it.image.recycle(); it.frames.forEach { frame -> frame.recycle() } }
                    return@runOnUiThread
                }
                result.onSuccess {
                    if (game.scene == null && saves.contains("resume.x")) {
                        game.restoreX = saves.getFloat("resume.x", 536f)
                        game.restoreY = saves.getFloat("resume.y", 660f)
                        game.speed = saves.getInt("resume.speed", 2).coerceIn(1, 3)
                    }
                    game.applyScene(it)
                }.onFailure {
                    game.restoreX = null; game.restoreY = null
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
            check.image.recycle(); check.frames.forEach { it.recycle() }
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
            .putFloat("resume.y", game.heroY).putInt("resume.speed", game.speed).apply()
    }
    private fun showMenu() {
        if (game.scene == null) { choose(); return }
        game.paused = true; dialogDepth++
        val items = arrayOf("계속하기", "이동 속도 ${game.speed}×", "탐색 위치 저장", "탐색 위치 불러오기", "다른 원본 맵 보기", "원본 ZIP 다시 선택")
        AlertDialog.Builder(this).setTitle("영웅전설 IV").setItems(items) { _, item ->
            when (item) {
                1 -> game.speed = game.speed % 3 + 1
                2 -> { saves.edit().putInt("scene", game.sceneIndex).putFloat("x", game.heroX).putFloat("y", game.heroY).putInt("speed", game.speed).apply(); toast("탐색 위치를 저장했습니다") }
                3 -> if (saves.contains("x")) {
                    game.restoreX = saves.getFloat("x", 512f); game.restoreY = saves.getFloat("y", 640f)
                    game.speed = saves.getInt("speed", 2).coerceIn(1, 3)
                    loadScene(saves.getInt("scene", 0))
                } else toast("저장된 탐색 위치가 없습니다")
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
        background("원본 필드를 준비하는 중…") { a.scene(target) }
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    inner class FieldView : View(this@MainActivity) {
        var scene: Ed4Assets.Scene? = null
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
        fun applyScene(next: Ed4Assets.Scene) {
            val first = scene == null
            scene?.let { it.image.recycle(); it.frames.forEach { b -> b.recycle() } }
            scene = next; sceneIndex = assets!!.sceneIds.indexOf(next.id); message = ""
            if (first) { logos = assets?.openingImages() ?: emptyList(); logoIndex = 0; opening = logos.isNotEmpty() }
            heroX = (restoreX ?: minOf(536f, next.image.width * .45f)).coerceIn(16f, next.image.width - 16f)
            heroY = (restoreY ?: minOf(660f, next.image.height * .55f)).coerceIn(48f, next.image.height - 16f)
            restoreX = null; restoreY = null; targetX = heroX; targetY = heroY; walked = 0f; resetClock()
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
            val moving = distance > .1f && !paused
            if (moving) {
                direction = if (abs(dx) > abs(dy)) if (dx < 0) 0 else 2 else if (dy < 0) 1 else 3
                val step = minOf(80f * speed * dt, distance)
                heroX += dx / distance * step; heroY += dy / distance * step; walked += step
            }
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
            val frame = direction * 2 + if (moving) (walked / 9).toInt() % 2 else 0
            c.drawBitmap(s.frames[frame], null, RectF(sx(heroX - 16), sy(heroY - 46), sx(heroX + 16), sy(heroY + 2)), brush)
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
            c.drawText("터치하여 이동  ·  원본 맵 탐색 단계 (전투·이벤트 준비 중)", 12 * scale, height - 8 * scale, brush)
            if (moving) postInvalidateOnAnimation()
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
            if (menuButton.contains(e.x, e.y)) { showMenu(); return true }
            if (paused || !field.contains(e.x, e.y)) return true
            val s = scene ?: return true
            targetX = (camera.left + (e.x - field.left) / field.width() * camera.width()).coerceIn(16f, s.image.width - 16f)
            targetY = (camera.top + (e.y - field.top) / field.height() * camera.height()).coerceIn(48f, s.image.height - 16f)
            resetClock(); return true
        }
    }
}
