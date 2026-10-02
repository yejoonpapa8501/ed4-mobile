package com.ed4mobile.app

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.widget.FrameLayout
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RetroEngineBridge(private val context: Context) {
    private var retroView: GLRetroView? = null
    var speedMultiplier: Int = 1
        private set

    fun hasImportedGame(): Boolean = importedGameFile().exists()
    fun importedGameFile(): File = File(File(context.filesDir, "ed4").apply { mkdirs() }, "ed4.zip")

    fun importGame(uri: Uri): File {
        val dst = importedGameFile()
        val tmp = File(dst.parentFile, "ed4.importing.zip")
        if (tmp.exists()) tmp.delete()

        var foundEd4Exe = false
        val input = context.contentResolver.openInputStream(uri)
            ?: error("선택한 파일을 읽을 수 없습니다.")

        ZipInputStream(BufferedInputStream(input)).use { zin ->
            ZipOutputStream(BufferedOutputStream(tmp.outputStream())).use { zout ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')

                    if (name.equals("ed4/ED4.EXE", ignoreCase = true)) {
                        foundEd4Exe = true
                    }

                    // Always replace a root DOSBOX.BAT with our known-good launcher.
                    if (!name.equals("DOSBOX.BAT", ignoreCase = true)) {
                        val outEntry = ZipEntry(name)
                        if (entry.time > 0) outEntry.time = entry.time
                        zout.putNextEntry(outEntry)
                        if (!entry.isDirectory) {
                            while (true) {
                                val count = zin.read(buffer)
                                if (count <= 0) break
                                zout.write(buffer, 0, count)
                            }
                        }
                        zout.closeEntry()
                    }
                    zin.closeEntry()
                }

                require(foundEd4Exe) {
                    "ZIP 안에서 ed4/ED4.EXE를 찾지 못했습니다. 올바른 영웅전설4 ed4.zip을 선택해 주세요."
                }

                zout.putNextEntry(ZipEntry("DOSBOX.BAT"))
                zout.write(
                    (
                        "@echo off\r\n" +
                        "cd ed4\r\n" +
                        "if exist NOSOUND.BAT goto runbat\r\n" +
                        "if exist ED4.EXE goto runexe\r\n" +
                        "echo ED4 launcher: no executable found\r\n" +
                        "pause\r\n" +
                        "goto end\r\n" +
                        ":runbat\r\n" +
                        "call NOSOUND.BAT\r\n" +
                        "goto failed\r\n" +
                        ":runexe\r\n" +
                        "ED4.EXE\r\n" +
                        ":failed\r\n" +
                        "echo.\r\n" +
                        "echo ED4 returned to DOS. ErrorLevel=%ERRORLEVEL%\r\n" +
                        "echo Capture this screen for diagnosis.\r\n" +
                        "pause\r\n" +
                        ":end\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                )
                zout.closeEntry()
            }
        }

        require(tmp.length() > 1024 * 1024) { "변환된 ed4.zip 파일 크기가 너무 작습니다." }
        if (dst.exists() && !dst.delete()) error("기존 게임 데이터를 교체할 수 없습니다.")
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        return dst
    }

    fun createView(container: FrameLayout, gameFile: File): GLRetroView {
        val core = resolveCoreFile()
        val saveDir = File(context.filesDir, "saves").apply { mkdirs() }
        val systemDir = File(context.filesDir, "system").apply { mkdirs() }

        val data = GLRetroViewData(context).apply {
            coreFilePath = core.absolutePath
            gameFilePath = gameFile.absolutePath
            savesDirectory = saveDir.absolutePath
            systemDirectory = systemDir.absolutePath
            shader = ShaderConfig.CUT3(
                staticSharpness = 0.82f,
                softEdgesSharpening = true,
                softEdgesSharpeningAmount = 0.90f,
                hardEdgesSearchMaxDistance = 4
            )
            preferLowLatencyAudio = true
            skipDuplicateFrames = false
        }

        return GLRetroView(context, data).also { view ->
            retroView = view
            container.removeAllViews()
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    fun keyDown(key: DosKey) = sendKey(key, KeyEvent.ACTION_DOWN)
    fun keyUp(key: DosKey) = sendKey(key, KeyEvent.ACTION_UP)

    private fun sendKey(key: DosKey, action: Int) {
        retroView?.sendKeyEvent(action, key.androidKeyCode)
    }

    fun cycleSpeed(): Int {
        speedMultiplier = when (speedMultiplier) {
            1 -> 2
            2 -> 3
            else -> 1
        }
        retroView?.frameSpeed = speedMultiplier
        return speedMultiplier
    }

    fun quickSave(): Boolean {
        val view = retroView ?: return false
        return runCatching {
            val bytes = view.serializeState()
            File(context.filesDir, "quickstate.sav").writeBytes(bytes)
        }.isSuccess
    }

    fun quickLoad(): Boolean {
        val view = retroView ?: return false
        val state = File(context.filesDir, "quickstate.sav")
        if (!state.exists()) return false
        return runCatching { view.unserializeState(state.readBytes()) }.getOrDefault(false)
    }

    private fun resolveCoreFile(): File {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val candidates = listOf(
            File(dir, "libdosbox_pure_libretro_android.so"),
            File(dir, "dosbox_pure_libretro_android.so")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("DOSBox-Pure ARM64 코어가 APK에 포함되지 않았습니다.")
    }
}
