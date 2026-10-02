import com.ed4mobile.app.*
import java.io.File
import java.nio.charset.Charset

/** Read-only integration checks against the owner's original DOS data. No fixture assets are bundled. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Pass the extracted original ed4 directory" }
    val root=File(args[0]); val areas=mutableMapOf<String,Ed4Archive>()
    fun resource(id:Int):ByteArray {
        val bank=id ushr 12; val name=if(bank<10) "DATA_${'A'+bank}.DAT" else "DATA${bank}.DAT"
        return areas.getOrPut(name){Ed4Archive(File(root,name))}.resource(id and 4095)
    }
    val engine=resource(0xb000);val base=Ed4Archive.word(engine,0xffa4)*16
    val seed=engine.copyOfRange(base,base+0x2000)
    val names=List(13){i ->
        val p=base+Ed4Archive.word(engine,base+0x2614+i*2);val end=(p until engine.size).first { engine[it].toInt()==0 }
        String(engine,p,end-p,Charset.forName("MS949"))
    }
    fun vmFor(id:Int):Ed4Scenario {
        val s=resource(id);val vm=Ed4Scenario(s,seed,names,resource(Ed4Archive.word(s,0)))
        vm.memory.fill(0,0x430,0x436);s.copyInto(vm.memory,8,0,10);s.copyInto(vm.memory,0,40,46)
        vm.putByte(2,vm.byte(2) and 127);vm.putByte(4,vm.byte(4) and 127)
        vm.putWord(0x12,id)
        val position=Ed4Layout.spawn(s,0,Ed4Terrain.Position(vm.byte(0x20),vm.byte(0x21),vm.byte(0x22)))
        vm.putByte(0x20,position.x);vm.putByte(0x21,position.y);vm.putByte(0x22,position.z)
        val count=Ed4Archive.word(s,22);vm.putWord(0x420,count)
        for(index in 1 until count) {
            val p=Ed4Archive.word(s,20)+(index-1)*6;val a=0x20+index*16
            vm.putWord(a,Ed4Archive.word(s,p));vm.putByte(a+2,s[p+2].toInt());vm.putWord(a+4,Ed4Archive.word(s,p+3))
            vm.putByte(a+3,vm.byte(a+4) and 6);vm.putWord(a+6,vm.byte(a+2)*257);vm.putByte(a+12,s[p+5].toInt())
        }
        return vm
    }
    val initial=Ed4Archive.word(engine,base+0x12)
    check(initial==55) { "Unexpected original start resource" }
    val intro=vmFor(initial)
    for(offset in listOf(14,16)) check(intro.start(Ed4Archive.word(intro.script,offset))==Ed4Scenario.Event.Done)
    var speech=intro.start(Ed4Archive.word(intro.script,18));var pages=0
    while(speech is Ed4Scenario.Event.Speech) {pages+=speech.pages.size;speech=intro.resume()}
    check(speech==Ed4Scenario.Event.Done && pages>0)
    println("Original intro initialization, entry and first dialogue: passed ($pages pages)")
    val start=Ed4Terrain.Position(intro.byte(0x20),intro.byte(0x21),intro.byte(0x22))
    val terrain=Ed4Terrain(intro.map,resource(Ed4Archive.word(intro.script,6)))
    val seen=mutableSetOf(start);val queue=ArrayDeque<Ed4Terrain.Position>();queue.add(start)
    while(queue.isNotEmpty() && seen.size<30000) {
        val position=queue.removeFirst()
        for(dx in -1..1)for(dy in -1..1)if(dx!=0||dy!=0) terrain.move(position,dx,dy)?.let { if(seen.add(it))queue.add(it) }
    }
    check(seen.size>1000) { "Original initial position is blocked" }
    println("Intro terrain traversal: ${seen.size} reachable positions")
    val town=vmFor(0)
    for(offset in listOf(14,16)) check(town.start(Ed4Archive.word(town.script,offset))==Ed4Scenario.Event.Done)
    for(actor in 2..6) {
        val pointer=Ed4Archive.word(town.script,26)+(actor-1)*2
        var event=town.start(Ed4Archive.word(town.script,pointer),actor);var count=0
        while(event is Ed4Scenario.Event.Speech && count<100) {count+=event.pages.size;event=town.resume()}
        check(event==Ed4Scenario.Event.Done && count>0) { "Town actor $actor failed: $event" }
        println("Town actor $actor original dialogue: passed ($count pages)")
    }
    for(id in listOf(0,4,10,14,15,21,26,30,34,39,43,47,51,55)) {
        val s=resource(id)
        for(pointerOffset in listOf(46,48)) {
            var p=Ed4Archive.word(s,pointerOffset);var records=0
            while(s[p].toInt() and 255 != 255) {
                check(records++<64)
                val span=s[p+1].toInt() and 255;var ref=Ed4Archive.word(s,p+2)
                if(pointerOffset==46 && ref<4)ref=0xc010+ref
                check(resource(ref).size==span*(if(pointerOffset==46)2048 else 64))
                p+=4
            }
        }
    }
    println("All 14 map sprite and motion tables: passed")
}
