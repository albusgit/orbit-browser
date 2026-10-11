package com.albustech.mikraot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit

/** Endless quiz. Progress is saved after every answer, so leaving the app loses nothing. */
class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("quiz", MODE_PRIVATE)
        val progress = Progress.decode(prefs.getString(KEY, null), GATE_1.size)
        val state = QuizState(GATE_1, progress, save = { prefs.edit { putString(KEY, it.encode()) } })
        setContent { QuizScreen(state, onInteract = ::keepAwake) }
    }

    override fun onResume() {
        super.onResume()
        keepAwake()
    }

    override fun onPause() {
        handler.removeCallbacks(releaseScreen)
        releaseScreen.run()
        super.onPause()
    }

    /** Reading a question takes longer than the watch's screen timeout: stay on while in use. */
    private fun keepAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(releaseScreen)
        handler.postDelayed(releaseScreen, AWAKE_MS)
    }

    private companion object {
        const val KEY = "gate1"
        const val AWAKE_MS = 45_000L
    }
}
