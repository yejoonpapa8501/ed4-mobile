package com.ed4mobile.app

import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var bridge: RetroEngineBridge
    private lateinit var statusText: TextView
    private lateinit var gameContainer: FrameLayout
    private var gameStarted = false

    private val chooseGame = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val game = bridge.importGame(uri)
            startGame(game)
        } catch (t: Throwable) {
            statusText.text = "게임 파일 불러오기 실패"
            Toast.makeText(this, t.message ?: "ed4.zip을 확인해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemUi()

        statusText = findViewById(R.id.statusText)
        gameContainer = findViewById(R.id.gameContainer)
        bridge = RetroEngineBridge(this)

        bindHoldButton(R.id.btnUp, DosKey.UP)
        bindHoldButton(R.id.btnDown, DosKey.DOWN)
        bindHoldButton(R.id.btnLeft, DosKey.LEFT)
        bindHoldButton(R.id.btnRight, DosKey.RIGHT)
        bindHoldButton(R.id.btnConfirm, DosKey.CONFIRM)
        bindHoldButton(R.id.btnCancel, DosKey.CANCEL)
        bindHoldButton(R.id.btnMenu, DosKey.MENU)

        findViewById<Button>(R.id.btnSpeed).setOnClickListener { button ->
            if (!gameStarted) return@setOnClickListener
            val speed = bridge.cycleSpeed()
            button.text = "${speed}×"
            statusText.text = "ED4 Mobile · CUT3 · ${speed}×"
        }
        findViewById<Button>(R.id.btnQuickSave).setOnClickListener {
            val ok = bridge.quickSave()
            toast(if (ok) "빠른 저장 완료" else "아직 게임이 실행되지 않았습니다.")
        }
        findViewById<Button>(R.id.btnQuickLoad).setOnClickListener {
            val ok = bridge.quickLoad()
            toast(if (ok) "빠른 불러오기 완료" else "저장 상태가 없습니다.")
        }
        findViewById<Button>(R.id.btnSelectGame).setOnClickListener {
            chooseGame.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gameStarted) {
                    bridge.keyDown(DosKey.CANCEL)
                    bridge.keyUp(DosKey.CANCEL)
                }
            }
        })

        if (bridge.hasImportedGame()) {
            startGame(bridge.importedGameFile())
        } else {
            statusText.text = "처음 실행: 아래 ‘ed4.zip 선택’ 버튼을 눌러 주세요"
            findViewById<Button>(R.id.btnSelectGame).visibility = View.VISIBLE
        }
    }

    private fun startGame(gameFile: java.io.File) {
        if (gameStarted) return
        try {
            val retroView = bridge.createView(gameContainer, gameFile)
            lifecycle.addObserver(retroView)
            gameStarted = true
            findViewById<Button>(R.id.btnSelectGame).visibility = View.GONE
            statusText.text = "ED4 Mobile · DOSBox-Pure 시작 중"
            lifecycleScope.launch {
                retroView.getGLRetroEvents().collect {
                    statusText.text = "ED4 Mobile · CUT3 · ${bridge.speedMultiplier}×"
                }
            }
        } catch (t: Throwable) {
            statusText.text = "실행 오류: ${t.message}"
            findViewById<Button>(R.id.btnSelectGame).visibility = View.VISIBLE
        }
    }

    private fun bindHoldButton(id: Int, key: DosKey) {
        findViewById<View>(id).setOnTouchListener { _, event ->
            if (!gameStarted) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { bridge.keyDown(key); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { bridge.keyUp(key); true }
                else -> true
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun hideSystemUi() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
