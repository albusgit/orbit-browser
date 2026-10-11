package com.albustech.mikraot

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.wear.compose.foundation.BasicSwipeToDismissBox

/**
 * Pick a part, then answer endlessly. Each part keeps its own progress, saved after every
 * answer, so leaving the app loses nothing. Swipe right (or Back) returns to the picker.
 */
class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("quiz", MODE_PRIVATE)
        val parts = QuestionBank.parse(assets.open("questions.json").bufferedReader().use { it.readText() })
        // Index 0 is the mixed mode, then the parts in order.
        val modes = listOf(QuestionBank.all(parts)) + parts

        var picked by mutableIntStateOf(prefs.getInt(KEY_PICKED, 1).coerceIn(0, modes.lastIndex))
        var open by mutableStateOf<Part?>(null)

        setContent {
            BasicSwipeToDismissBox(
                onDismissed = { open = null },
                backgroundKey = "picker",
                contentKey = open?.key ?: "picker",
                userSwipeEnabled = open != null,
            ) { isBackground ->
                val part = open
                if (isBackground || part == null) {
                    // Recomputed whenever the picker shows, so it reflects the last session.
                    val entries = remember(open) {
                        modes.map { PickerEntry(it.number, it.title, it.questions.size, load(prefs, it).mastered) }
                    }
                    PartPicker(
                        entries,
                        picked,
                        onSelect = { picked = it; prefs.edit { putInt(KEY_PICKED, it) } },
                        onOpen = { open = modes[it] },
                        onInteract = ::keepAwake,
                    )
                } else {
                    val state = remember(part) {
                        QuizState(part.questions, load(prefs, part), save = { prefs.edit { putString(part.key, it.encode()) } })
                    }
                    androidx.activity.compose.BackHandler { open = null }
                    QuizScreen(state, title = part.title, onInteract = ::keepAwake)
                }
            }
        }
    }

    private fun load(prefs: SharedPreferences, part: Part) =
        Progress.decode(prefs.getString(part.key, null), part.questions.size)

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
        const val KEY_PICKED = "picked"
        const val AWAKE_MS = 45_000L
    }
}
