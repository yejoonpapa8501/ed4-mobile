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
                } else if(base.equals("MANTRA.DAT",true) || base.equals("SAMSUNG.DAT",true)) {
                    File(dir,base.uppercase()).outputStream().use { zin.copyTo(it) }
                } else if(isData) {
                    val out=File(dir,base.uppercase()); out.outputStream().use { zin.copyTo(it) }
                    val bytes=out.readBytes()
                    if(bytes.size>=20 && String(bytes,0,8,Charsets.US_ASCII)=="AFLB DAT") {
                        val count=u16(bytes,10); aflbEntries += count
                        val resDir=File(dir,"resources/"+base.substringBeforeLast(".")); resDir.mkdirs()
                        for(i in 0 until count) {
                            val st=16+i*2; val et=16+(i+1)*2
                            if(et+1>=bytes.size) break
                            val start=u16(bytes,st)*32
                            val blockEnd=(u16(bytes,et)*32).coerceAtMost(bytes.size)
                            if(start>=blockEnd || start>=bytes.size) continue
                            var end=blockEnd
                            while(end>start && bytes[end-1].toInt()==-1) end--
                            if(end>start) { File(resDir,"%03d.bin".format(i)).writeBytes(bytes.copyOfRange(start,end)); extracted++ }
                        }
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
        private var heroFrames=emptyArray<Bitmap>()
        private var facing=6
        private var walkTime=0f
        private var moving=false
        private var info="ED4 DATA"
        private var px=.50f; private var py=.55f
        private var tx=px; private var ty=py
        private var lastFrame=0L
        private var speed=1
        private val camera=Rect()
        private val saves=getSharedPreferences("native-save", MODE_PRIVATE)
        private var menu=false
        private var dialogue=true; private var introStage=0; private var samsung:Bitmap?=null; private var mantra:Bitmap?=null

        fun loadGame(file:File,text:String) { val scene=Ed4Resources.scene(file.parentFile!!); world=scene.bitmap; heroFrames=scene.hero; px=520f/world!!.width; py=740f/world!!.height; tx=px; ty=py; info=text; samsung=File(filesDir,"ed4native/SAMSUNG.DAT").takeIf{it.exists()}?.let{decodeRawPlanar16(it.readBytes())}; mantra=File(filesDir,"ed4native/MANTRA.DAT").takeIf{it.exists()}?.let{decodeRawPlanar16(it.readBytes())}; introStage=if(samsung!=null) 0 else 2; invalidate() }
        fun toggleMenu(){ if(introStage<2) introStage=2 else if(dialogue) dialogue=false else menu=!menu; lastFrame=0L; invalidate() }

        override fun onDraw(c:Canvas) {
            super.onDraw(c); c.drawColor(Color.BLACK)
            val bmp=world
            if(bmp==null){ drawImport(c); return }
            if(introStage<2){ drawIntro(c,if(introStage==0) samsung else mantra); return }
            if(!menu && !dialogue) advanceMovement() else lastFrame=0L
            drawWorld(c,bmp)
            drawHero(c,(px*bmp.width-camera.left)*width/camera.width(),(py*bmp.height-camera.top)*height/camera.height())
            drawHud(c)
            if(dialogue) drawDialogue(c)
            if(menu) drawMenu(c)
        }

        private fun drawIntro(c:Canvas,b:Bitmap?){
            c.drawColor(Color.BLACK)
            if(b!=null){ val scale=minOf(width.toFloat()/b.width,height.toFloat()/b.height); val dw=b.width*scale; val dh=b.height*scale; c.drawBitmap(b,null,RectF((width-dw)/2,(height-dh)/2,(width+dw)/2,(height+dh)/2),p) }
            p.textAlign=Paint.Align.CENTER; p.color=Color.WHITE; p.textSize=16f
            c.drawText("터치하여 계속",width/2f,height-28f,p); p.textAlign=Paint.Align.LEFT
        }

        private fun drawWorld(c:Canvas,b:Bitmap){
            val viewW=400.coerceAtMost(b.width)
            val viewH=(viewW*height.toFloat()/width).toInt().coerceIn(1,b.height)
            val cx=(px*b.width).toInt(); val cy=(py*b.height).toInt()
            val l=(cx-viewW/2).coerceIn(0,(b.width-viewW).coerceAtLeast(0))
            val t=(cy-viewH/2).coerceIn(0,(b.height-viewH).coerceAtLeast(0))
            camera.set(l,t,l+viewW,t+viewH)
            val src=camera
            val dst=Rect(0,0,width,height)
            p.isFilterBitmap=false; c.drawBitmap(b,src,dst,p)

        }

        private fun drawHero(c:Canvas,x:Float,y:Float){
            if(heroFrames.isEmpty()) return
            val frame=facing+if(moving && (walkTime*8).toInt()%2==1) 1 else 0
            val scale=width.toFloat()/camera.width()
            p.isFilterBitmap=false
            c.drawBitmap(heroFrames[frame],null,RectF(x-16*scale,y-44*scale,x+16*scale,y+4*scale),p)
        }

        private fun drawHud(c:Canvas){
            p.color=Color.argb(210,7,14,25); c.drawRect(0f,0f,width.toFloat(),64f,p)
            p.color=Color.rgb(232,210,145); p.textSize=25f; c.drawText("영웅전설 IV  주홍물방울",22f,39f,p)
            p.color=Color.LTGRAY; p.textSize=14f; c.drawText("이동 속도 ${speed}×  ·  상단 터치: 속도 변경  ·  뒤로가기: 메뉴",22f,58f,p)
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
            p.color=Color.WHITE; p.textSize=19f; c.drawText("원본 마을을 터치하여 둘러보실 수 있습니다.",94f,height-75f,p)
            p.color=Color.LTGRAY; p.textSize=14f; c.drawText("화면을 터치하면 이동 · 대화창 터치로 닫기",94f,height-46f,p)
        }

        private fun drawMenu(c:Canvas){
            val r=RectF(width*.34f,height*.18f,width*.66f,height*.78f)
            p.color=Color.argb(242,4,10,22); c.drawRoundRect(r,12f,12f,p)
            p.style=Paint.Style.STROKE; p.strokeWidth=3f; p.color=Color.rgb(216,188,105); c.drawRoundRect(r,12f,12f,p); p.style=Paint.Style.FILL
            p.textAlign=Paint.Align.CENTER; p.color=Color.WHITE; p.textSize=27f
            c.drawText("메 뉴",width*.5f,height*.28f,p)
            p.textSize=22f
            arrayOf("계속하기","이동 속도 ${speed}×","위치 저장","위치 불러오기","오프닝 다시 보기").forEachIndexed{i,s->c.drawText(s,width*.5f,height*(.39f+i*.075f),p)}
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
            val b=world ?: return
            val dx=(tx-px)*b.width; val dy=(ty-py)*b.height; val d=sqrt(dx*dx+dy*dy)
            moving=d>.1f
            if(moving){
                facing=if(kotlin.math.abs(dx)>kotlin.math.abs(dy)) { if(dx<0) 0 else 4 } else { if(dy<0) 2 else 6 }
                walkTime+=dt*speed
                val step=(90f*speed*dt).coerceAtMost(d); px+=dx/d*step/b.width; py+=dy/d*step/b.height; postInvalidateOnAnimation() }
        }

        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(e.action!=MotionEvent.ACTION_DOWN) return true
            if(world==null){ chooseGame.launch(arrayOf("application/zip","*/*")); return true }
            if(introStage<2){ introStage++; if(introStage==1 && mantra==null) introStage=2; lastFrame=0L; invalidate(); return true }
            if(menu){
                if(e.x in width*.34f..width*.66f){
                    val row=kotlin.math.floor((e.y/height-.345f)/.075f).toInt()
                    when(row){
                        0 -> menu=false
                        1 -> speed=speed%3+1
                        2 -> { saves.edit().putFloat("x",px).putFloat("y",py).putInt("speed",speed).apply(); Toast.makeText(context,"위치를 저장했습니다",Toast.LENGTH_SHORT).show(); menu=false }
                        3 -> { if(saves.contains("x")){ px=saves.getFloat("x",.5f); py=saves.getFloat("y",.55f); tx=px; ty=py; speed=saves.getInt("speed",1).coerceIn(1,3); menu=false } else Toast.makeText(context,"저장된 위치가 없습니다",Toast.LENGTH_SHORT).show() }
                        4 -> { introStage=if(samsung!=null) 0 else if(mantra!=null) 1 else 2; menu=false }
                    }
                } else menu=false
                lastFrame=0L; invalidate(); return true
            }
            if(dialogue){ dialogue=false; lastFrame=0L; invalidate(); return true }
            if(e.y<64){ speed=speed%3+1; invalidate(); return true }
            val b=world ?: return true
            if(camera.width()==0 || camera.height()==0) return true
            tx=((camera.left+e.x/width*camera.width())/b.width).coerceIn(0f,1f)
            ty=((camera.top+e.y/height*camera.height())/b.height).coerceIn(0f,1f)
            lastFrame=0L
            invalidate(); return true
        }
    }

    private fun decodeRawPlanar16(b:ByteArray):Bitmap? {
        if(b.size<52) return null
        val bpl=u16(b,0); val h=u16(b,2)
        if(bpl !in 1..160 || h !in 1..600 || 52+bpl*h*4>b.size) return null
        val w=bpl*8; val pal=IntArray(16)
        for(i in 0 until 16){ val o=4+i*3; pal[i]=Color.rgb(((b[o].toInt() and 255)*4).coerceAtMost(255),((b[o+1].toInt() and 255)*4).coerceAtMost(255),((b[o+2].toInt() and 255)*4).coerceAtMost(255)) }
        val dataOff=52; val planeSize=bpl*h; val pixels=IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w){ var idx=0; for(pl in 0 until 4){ val v=b[dataOff+pl*planeSize+y*bpl+x/8].toInt() and 255; if((v and (0x80 shr (x and 7)))!=0) idx=idx or (1 shl pl) }; pixels[y*w+x]=pal[idx] }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
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
