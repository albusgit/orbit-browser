package com.albustech.mikraot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * Renders the quiz on the JVM at the Watch6 Classic sizes, clipped to the circle, for review:
 * build/screenshots/<screen>_<size>.png. Not pixel-compared.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotTest(private val tag: String) {

    private fun shot(name: String, state: QuizState) =
        captureRoboImage("build/screenshots/${name}_$tag.png") {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1C1E))) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { QuizScreen(state) }
            }
        }

    /** A state on question [text] (or the first one asked). */
    private fun state(text: String? = null): QuizState {
        val s = QuizState(GATE_1, Progress(GATE_1.size), random = Random(11))
        if (text != null) {
            var guard = 0
            while (s.question.text != text && guard++ < 500) s.next()
            // next() only cycles the queue head; walk it by answering right instead.
            while (s.question.text != text && guard++ < 1000) { s.choose((0 until 4).first { s.isCorrect(it) }); s.next() }
        }
        return s
    }

    private val longest = GATE_1.maxBy { q -> q.text.length + q.answers.sumOf { it.length } }.text

    @Test fun fresh() = shot("1_fresh", state())

    @Test fun lit() = shot("2_lit", state().apply { rotate(2) })

    @Test fun wrong() = shot("3_wrong", state().apply { choose((0 until 4).first { !isCorrect(it) }) })

    @Test fun right() = shot("4_right", state().apply { choose((0 until 4).first { isCorrect(it) }) })

    @Test fun longestLit() = shot("5_longest_lit", state(longest).apply {
        // Light the longest answer.
        highlighted = (0 until 4).maxBy { answer(it).length }
    })

    @Test fun longestWrong() = shot("6_longest_wrong", state(longest).apply {
        choose((0 until 4).filter { !isCorrect(it) }.maxBy { answer(it).length })
    })

    @Test fun stats() = shot("7_stats", state().apply {
        repeat(9) { choose((0 until 4).first { s -> it % 4 != 0 == isCorrect(s) }); next() }
        showStats = true
    })
}

@Config(sdk = [36], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class Screenshot480 : ScreenshotTest("480")

@Config(sdk = [36], qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class Screenshot432 : ScreenshotTest("432")
