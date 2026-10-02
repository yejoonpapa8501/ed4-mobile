package com.ed4mobile.app

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.*
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var gameView: NativeEd4View

    private val chooseGame = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {}
        try {
            val info = importOriginalData(uri)
            gameView.loadWorldMap(File(filesDir, "ed4native/BACK.DAT"), info)
            Toast.makeText(this, "원본 데이터 분석 완료 · 네이티브 엔진으로 표시 중", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "불러오기 실패: " + (t.message ?: "알 수 없는 오류"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        gameView = NativeEd4View()
        setContentView(gameView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { gameView.showHelp = !gameView.showHelp; gameView.invalidate() }
        })

        val back = File(filesDir, "ed4native/BACK.DAT")
        val meta = File(filesDir, "ed4native/meta.txt")
        if (back.exists()) gameView.loadWorldMap(back, meta.takeIf { it.exists() }?.readText() ?: "이전 가져오기 데이터")
        else chooseGame.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun importOriginalData(uri: Uri): String {
        val dir = File(filesDir, "ed4native").apply { mkdirs() }
        var entries = 0
        var dataFiles = 0
        var effects = 0
        var foundDriver = false
        var foundEd4 = false
        var foundBack = false
        val input = contentResolver.openInputStream(uri) ?: error("ZIP을 읽을 수 없습니다.")
        ZipInputStream(BufferedInputStream(input)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val n = e.name.replace('\\', '/')
                entries++
                if (n.substringAfterLast('/').matches(Regex("DATA(_[A-J]|1[0-3])\\.DAT", RegexOption.IGNORE_CASE))) dataFiles++
                if (n.endsWith(".EFC", true)) effects++
                if (n.endsWith("/DRIVER.EXE", true) || n.equals("DRIVER.EXE", true)) foundDriver = true
                if (n.endsWith("/ED4.EXE", true) || n.equals("ED4.EXE", true)) foundEd4 = true
                if (n.endsWith("/BACK.DAT", true) || n.equals("BACK.DAT", true)) {
                    File(dir, "BACK.DAT").outputStream().use { out -> zin.copyTo(out) }
                    foundBack = true
                }
                zin.closeEntry()
            }
        }
        require(foundDriver && foundEd4 && foundBack) { "영웅전설4 원본 구조를 확인하지 못했습니다." }
        val info = "ZIP 항목 $entries · DATA $dataFiles · EFC $effects · BACK.DAT 네이티브 PCX 디코딩"
        File(dir, "meta.txt").writeText(info)
        return info
    }

    inner class NativeEd4View : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var map: Bitmap? = null
        private var info = "원본 ed4.zip을 선택해 주세요"\n        private var gameTitle = "영웅전설 IV · 주홍물방울"
        var showHelp = true
        private var px = 0.5f
        private var py = 0.55f; private var targetX = px; private var targetY = py; private var lastFrame = 0L
        private val buttons = mutableMapOf<String, RectF>()

        fun loadWorldMap(file: File, text: String) {
            map = decodePcx16(file.readBytes())
            info = text
            showHelp = false
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.BLACK);     animateMarker()
            val bmp = map
            if (bmp != null) {
                val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val dst = RectF((width-dw)/2, (height-dh)/2, (width+dw)/2, (height+dh)/2)
                paint.isFilterBitmap = true
                c.drawBitmap(bmp, null, dst, paint)
                paint.color = Color.argb(210, 20, 20, 20)
                c.drawRect(0f, 0f, width.toFloat(), 70f, paint)
                paint.color = Color.WHITE; paint.textSize = 28f
                c.drawText(gameTitle, 28f, 43f, paint)
                paint.textSize = 18f
                c.drawText(info, 28f, 66f, paint)

                paint.color = Color.YELLOW
                c.drawCircle(px * width, py * height, 10f, paint)
                drawControls(c)\n                drawGameHud(c)
            } else {
                paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 34f
                c.drawText("영웅전설4 네이티브 엔진", width/2f, height/2f-40, paint)
                paint.textSize = 22f
                c.drawText("화면을 눌러 ed4.zip 선택", width/2f, height/2f+10, paint)
            }
            if (showHelp && bmp != null) {
                paint.color = Color.argb(225,0,0,0); c.drawRect(width*.18f,height*.2f,width*.82f,height*.8f,paint)
                paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 28f
                c.drawText("DOSBox를 제거한 첫 네이티브 빌드", width/2f,height*.36f,paint)
                paint.textSize = 20f
                c.drawText("BACK.DAT를 Android에서 직접 디코딩했습니다.",width/2f,height*.47f,paint)
                c.drawText("다음 단계: 맵/캐릭터/대사 데이터 해석",width/2f,height*.55f,paint)
                c.drawText("뒤로가기: 이 안내 닫기",width/2f,height*.67f,paint)
            }
            paint.textAlign = Paint.Align.LEFT
        }

        private fun drawGameHud(c: Canvas) {
            val panel = RectF(width-270f, 88f, width-18f, 205f)
            paint.color = Color.argb(205, 8, 18, 28); c.drawRoundRect(panel, 12f, 12f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.rgb(205,180,105); c.drawRoundRect(panel,12f,12f,paint); paint.style=Paint.Style.FILL
            paint.color = Color.WHITE; paint.textSize=19f
            c.drawText("어빈", width-248f, 118f, paint)
            c.drawText("HP  120 / 120", width-248f, 148f, paint)
            c.drawText("MP   42 / 42", width-248f, 176f, paint)
            paint.color=Color.rgb(220,195,120); paint.textSize=15f
            c.drawText("Native Engine · ED4 DATA", width-248f, 198f, paint)
        }

        private fun animateMarker() {
            val now = System.nanoTime()
            if (lastFrame == 0L) lastFrame = now
            val dt = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(.05f)
            lastFrame = now
            val dx = targetX - px
            val dy = targetY - py
            val dist = kotlin.math.sqrt(dx*dx + dy*dy)
            if (dist > .002f) {
                val step = (.45f * dt).coerceAtMost(dist)
                px += dx / dist * step
                py += dy / dist * step
                postInvalidateOnAnimation()
            }
        }

        private fun drawControls(c: Canvas) {
            buttons.clear()
            val s=64f; val x=85f; val y=height-100f
            fun b(name:String,cx:Float,cy:Float,label:String){
                val r=RectF(cx-s/2,cy-s/2,cx+s/2,cy+s/2); buttons[name]=r
                paint.color=Color.argb(145,0,0,0); c.drawRoundRect(r,18f,18f,paint)
                paint.color=Color.WHITE; paint.textAlign=Paint.Align.CENTER; paint.textSize=30f
                c.drawText(label,cx,cy+10f,paint)
            }
            b("L",x-s,y,"◀"); b("R",x+s,y,"▶"); b("U",x,y-s,"▲"); b("D",x,y+s,"▼")
            paint.textAlign=Paint.Align.LEFT
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            if (map == null) { chooseGame.launch(arrayOf("application/zip","*/*")); return true }
            for ((k,r) in buttons) if (r.contains(e.x,e.y)) {
                when(k){ "L"->px-=.015f; "R"->px+=.015f; "U"->py-=.02f; "D"->py+=.02f }
                px=px.coerceIn(.05f,.95f); py=py.coerceIn(.12f,.92f); invalidate(); return true
            }
            // Native mobile control: tap anywhere on the map to move the marker there.
            // D-pad remains only as a temporary fine-control fallback during prototyping.
            px=(e.x/width.toFloat()).coerceIn(.05f,.95f)
            py=(e.y/height.toFloat()).coerceIn(.12f,.92f)
            showHelp=false
            invalidate()
            return true
        }
    }

    private fun decodePcx16(b: ByteArray): Bitmap {
        require(b.size > 128 && (b[0].toInt() and 255) == 10) { "BACK.DAT가 PCX 형식이 아닙니다." }
        fun u16(o:Int)=(b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)
        val xmin=u16(4); val ymin=u16(6); val xmax=u16(8); val ymax=u16(10)
        val w=xmax-xmin+1; val h=ymax-ymin+1
        val planes=b[65].toInt() and 255; val bytesPerLine=u16(66)
        require(planes in 1..4) { "지원하지 않는 PCX plane 수: $planes" }
        val pal=IntArray(16)
        for(i in 0 until 16){ val o=16+i*3; pal[i]=Color.rgb(b[o].toInt() and 255,b[o+1].toInt() and 255,b[o+2].toInt() and 255) }
        val scan=ByteArray(bytesPerLine*planes); val pixels=IntArray(w*h); var p=128
        for(y in 0 until h){
            var q=0
            while(q<scan.size && p<b.size){
                var v=b[p++].toInt() and 255; var count=1
                if((v and 0xC0)==0xC0){ count=v and 0x3F; v=b[p++].toInt() and 255 }
                repeat(count.coerceAtMost(scan.size-q)){ scan[q++]=v.toByte() }
            }
            for(x in 0 until w){
                var idx=0
                for(pl in 0 until planes){
                    val byt=scan[pl*bytesPerLine+x/8].toInt() and 255
                    if((byt and (0x80 shr (x and 7)))!=0) idx=idx or (1 shl pl)
                }
                pixels[y*w+x]=pal[idx]
            }
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
    }
}
