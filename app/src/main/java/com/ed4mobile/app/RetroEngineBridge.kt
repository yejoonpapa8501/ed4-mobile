package com.ed4mobile.app

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.widget.FrameLayout
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import java.io.File

class RetroEngineBridge(private val context: Context) {
    private var retroView: GLRetroView? = null
    var speedMultiplier: Int = 1
        private set

    fun hasImportedGame(): Boolean = importedGameFile().exists()
    fun importedGameFile(): File = File(File(context.filesDir, "ed4").apply { mkdirs() }, "ed4.zip")

    fun importGame(uri: Uri): File {
        val dst = importedGameFile()
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: error("선택한 파일을 읽을 수 없습니다.")
        require(dst.length() > 1024 * 1024) { "ed4.zip 파일 크기가 너무 작습니다." }
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
