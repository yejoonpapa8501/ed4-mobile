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
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var gameView: NativeEd4View

    private val chooseGame = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        try {
            val info = importOriginalData(uri)
            gameView.loadGame(File(filesDir, "ed4native/BACK.DAT"), info)
            Toast.makeText(this, "ED4 원본 데이터 로드 완료", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "불러오기 실패: " + (t.message ?: "알 수 없는 오류"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        gameView = NativeEd4View()
        setContentView(gameView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { gameView.toggleMenu() }
        })
        val back = File(filesDir, "ed4native/BACK.DAT")
        val meta = File(filesDir, "ed4native/meta.txt")
        if (back.exists()) gameView.loadGame(back, meta.takeIf { it.exists() }?.readText() ?: "ED4 DATA")
        else chooseGame.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun importOriginalData(uri: Uri): String {
        val dir = File(filesDir, "ed4native").apply { mkdirs() }
        var entries=0; var dataFiles=0; var effects=0; var aflbEntries=0; var extracted=0
        var driver=false; var exe=false; var back=false
        val input=contentResolver.openInputStream(uri) ?: error("ZIP을 읽을 수 없습니다.")
        ZipInputStream(BufferedInputStream(input)).use { zin ->
            while(true) {
                val e=zin.nextEntry ?: break
                val n=e.name.replace('\\','/')
                entries++
                val base=n.substringAfterLast('/')
                val isData=base.matches(Regex("DATA(_[A-J]|1[0-3])\\.DAT",RegexOption.IGNORE_CASE))
                if(isData) dataFiles++
                if(n.endsWith(".EFC",true)) effects++
                if(base.equals("DRIVER.EXE",true)) driver=true
                if(base.equals("ED4.EXE",true)) exe=true
                if(base.equals("BACK.DAT",true)) {
                    File(dir,"BACK.DAT").outputStream().use { zin.copyTo(it) }; back=true
                } else if(isData) {
                    val out=File(dir,base.uppercase())
                    out.outputStream().use { zin.copyTo(it) }
                    if(out.length()>=16) {
                        val h=ByteArray(16); out.inputStream().use { it.read(h) }
                        if(String(h,0,8,Charsets.US_ASCII)=="AFLB DAT") aflbEntries += u16(h,10)
                    }
                }
                zin.closeEntry()
            }
        }
        require(driver && exe && back) { "영웅전설4 원본 구조를 확인하지 못했습니다." }
        val info="원본 ZIP $entries · DATA $dataFiles · AFLB $aflbEntries / 추출 $extracted · EFC $effects"
        File(dir,"meta.txt").writeText(info)
        return info
    }

    private fun u16(b:ByteArray,o:Int)=(b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)

    inner class NativeEd4View : View(this) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private var world:Bitmap?=null
        private var info="ED4 DATA"
        private var px=.50f; private var py=.55f
        private var tx=px; private var ty=py
        private var lastFrame=0L
        private var menu=false
        private var dialogue=true

        fun loadGame(file:File,text:String) { world=decodePcx16(file.readBytes()); info=text; invalidate() }
        fun toggleMenu(){ menu=!menu; invalidate() }

        override fun onDraw(c:Canvas) {
            super.onDraw(c); c.drawColor(Color.BLACK)
            val bmp=world
            if(bmp==null){ drawImport(c); return }
            advanceMovement()
            drawWorld(c,bmp)
            drawHero(c,width*.5f,height*.52f)
            drawHud(c)
            if(dialogue) drawDialogue(c)
            if(menu) drawMenu(c)
        }

        private fun drawWorld(c:Canvas,b:Bitmap){
            val viewW=(b.width*.62f).toInt().coerceAtLeast(1)
            val viewH=(b.height*.62f).toInt().coerceAtLeast(1)
            val cx=(px*b.width).toInt(); val cy=(py*b.height).toInt()
            val l=(cx-viewW/2).coerceIn(0,(b.width-viewW).coerceAtLeast(0))
            val t=(cy-viewH/2).coerceIn(0,(b.height-viewH).coerceAtLeast(0))
            val src=Rect(l,t,l+viewW,t+viewH)
            val dst=Rect(0,0,width,height)
            p.isFilterBitmap=false; c.drawBitmap(b,src,dst,p)
            p.color=Color.argb(70,0,0,20); c.drawRect(0f,0f,width.toFloat(),height.toFloat(),p)
        }

        private fun drawHero(c:Canvas,x:Float,y:Float){
            val s=(width/900f).coerceIn(.8f,1.7f)
            p.color=Color.rgb(35,25,22); c.drawOval(RectF(x-15*s,y-34*s,x+15*s,y-6*s),p)
            p.color=Color.rgb(236,199,157); c.drawCircle(x,y-20*s,10*s,p)
            p.color=Color.rgb(58,84,126); c.drawRect(x-12*s,y-9*s,x+12*s,y+20*s,p)
            p.color=Color.rgb(230,224,205); c.drawRect(x-8*s,y+20*s,x-1*s,y+39*s,p); c.drawRect(x+2*s,y+20*s,x+9*s,y+39*s,p)
            p.color=Color.rgb(118,75,45); c.drawRect(x-10*s,y+36*s,x-1*s,y+42*s,p); c.drawRect(x+2*s,y+36*s,x+11*s,y+42*s,p)
            p.style=Paint.Style.STROKE; p.strokeWidth=2*s; p.color=Color.WHITE; c.drawCircle(x,y-20*s,11*s,p); p.style=Paint.Style.FILL
        }

        private fun drawHud(c:Canvas){
            p.color=Color.argb(210,7,14,25); c.drawRect(0f,0f,width.toFloat(),64f,p)
            p.color=Color.rgb(232,210,145); p.textSize=25f; c.drawText("영웅전설 IV  주홍물방울",22f,39f,p)
            p.color=Color.LTGRAY; p.textSize=14f; c.drawText(info,22f,58f,p)
            val r=RectF(width-220f,78f,width-18f,174f)
            p.color=Color.argb(220,5,12,24); c.drawRoundRect(r,8f,8f,p)
            p.style=Paint.Style.STROKE; p.strokeWidth=2f; p.color=Color.rgb(208,181,104); c.drawRoundRect(r,8f,8f,p); p.style=Paint.Style.FILL
            p.color=Color.WHITE; p.textSize=19f; c.drawText("어빈",width-198f,106f,p)
            p.textSize=16f; c.drawText("HP 120 / 120",width-198f,134f,p); c.drawText("MP  42 / 42",width-198f,158f,p)
        }

        private fun drawDialogue(c:Canvas){
            val r=RectF(70f,height-150f,width-70f,height-28f)
            p.color=Color.argb(232,6,12,24); c.drawRoundRect(r,10f,10f,p)
            p.style=Paint.Style.STROKE; p.strokeWidth=3f; p.color=Color.rgb(214,187,110); c.drawRoundRect(r,10f,10f,p); p.style=Paint.Style.FILL
            p.color=Color.rgb(235,211,140); p.textSize=20f; c.drawText("어빈",94f,height-112f,p)
            p.color=Color.WHITE; p.textSize=19f; c.drawText("이제부터 실제 ED4 데이터를 하나씩 해석해 나간다.",94f,height-75f,p)
            p.color=Color.LTGRAY; p.textSize=14f; c.drawText("화면을 터치하면 이동 · 대화창 터치로 닫기",94f,height-46f,p)
        }

        private fun drawMenu(c:Canvas){
            val r=RectF(width*.34f,height*.18f,width*.66f,height*.78f)
            p.color=Color.argb(242,4,10,22); c.drawRoundRect(r,12f,12f,p)
            p.style=Paint.Style.STROKE; p.strokeWidth=3f; p.color=Color.rgb(216,188,105); c.drawRoundRect(r,12f,12f,p); p.style=Paint.Style.FILL
            p.textAlign=Paint.Align.CENTER; p.color=Color.WHITE; p.textSize=27f
            c.drawText("메 뉴",width*.5f,height*.28f,p)
            p.textSize=22f
            arrayOf("아이템","마법","장비","상태","세이브").forEachIndexed{i,s->c.drawText(s,width*.5f,height*(.39f+i*.075f),p)}
            p.textAlign=Paint.Align.LEFT
        }

        private fun drawImport(c:Canvas){
            p.color=Color.WHITE; p.textAlign=Paint.Align.CENTER; p.textSize=34f
            c.drawText("영웅전설4 네이티브 엔진",width/2f,height/2f-30,p)
            p.textSize=20f; c.drawText("화면을 눌러 ed4.zip 선택",width/2f,height/2f+18,p); p.textAlign=Paint.Align.LEFT
        }

        private fun advanceMovement(){
            val now=System.nanoTime(); if(lastFrame==0L) lastFrame=now
            val dt=((now-lastFrame)/1_000_000_000f).coerceAtMost(.05f); lastFrame=now
            val dx=tx-px; val dy=ty-py; val d=sqrt(dx*dx+dy*dy)
            if(d>.001f){ val step=(.20f*dt).coerceAtMost(d); px+=dx/d*step; py+=dy/d*step; postInvalidateOnAnimation() }
        }

        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(e.action!=MotionEvent.ACTION_DOWN) return true
            if(world==null){ chooseGame.launch(arrayOf("application/zip","*/*")); return true }
            if(menu){ menu=false; invalidate(); return true }
            if(dialogue && e.y>height-170){ dialogue=false; invalidate(); return true }
            tx=(px+(e.x/width-.5f)*.34f).coerceIn(.05f,.95f)
            ty=(py+(e.y/height-.52f)*.34f).coerceIn(.08f,.92f)
            invalidate(); return true
        }
    }

    private fun decodePcx16(b:ByteArray):Bitmap{
        require(b.size>128 && (b[0].toInt() and 255)==10)
        fun u(o:Int)=(b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)
        val w=u(8)-u(4)+1; val h=u(10)-u(6)+1; val planes=b[65].toInt() and 255; val bpl=u(66)
        val pal=IntArray(16){i->val o=16+i*3; Color.rgb(b[o].toInt() and 255,b[o+1].toInt() and 255,b[o+2].toInt() and 255)}
        val scan=ByteArray(bpl*planes); val pixels=IntArray(w*h); var pos=128
        for(y in 0 until h){
            var q=0
            while(q<scan.size && pos<b.size){
                var v=b[pos++].toInt() and 255; var count=1
                if((v and 0xC0)==0xC0){ count=v and 0x3F; v=b[pos++].toInt() and 255 }
                repeat(count.coerceAtMost(scan.size-q)){scan[q++]=v.toByte()}
            }
            for(x in 0 until w){
                var idx=0
                for(pl in 0 until planes){ val v=scan[pl*bpl+x/8].toInt() and 255; if((v and (0x80 shr (x and 7)))!=0) idx=idx or (1 shl pl) }
                pixels[y*w+x]=pal[idx]
            }
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
    }
}
