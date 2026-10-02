package com.ed4mobile.app

enum class DosKey(val androidKeyCode: Int) {
    UP(android.view.KeyEvent.KEYCODE_DPAD_UP),
    DOWN(android.view.KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(android.view.KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
    CONFIRM(android.view.KeyEvent.KEYCODE_ENTER),
    CANCEL(android.view.KeyEvent.KEYCODE_ESCAPE),
    MENU(android.view.KeyEvent.KEYCODE_F1)
}
