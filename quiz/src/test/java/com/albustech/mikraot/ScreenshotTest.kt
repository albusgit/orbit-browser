package com.albustech.mikraot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/screenshots/${name}_$tag.png") {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1C1E))) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { content() }
            }
        }

    private fun quiz(name: String, state: QuizState) = shot(name) { QuizScreen(state, title = "ישראל: תעודת זהות") }

    private fun state() = QuizState(TestBank.gate1, Progress(TestBank.gate1.size), random = Random(11))

    /** A state showing just [q]. */
    private fun only(q: Question) = QuizState(listOf(q), Progress(1), random = Random(3))

    private val all = TestBank.parts.flatMap { it.questions }
    private val longest = all.maxBy { q -> q.text.length + q.answers.sumOf { it.length } }
    private val longestAnswer = all.maxBy { q -> q.answers.maxOf { it.length } }
    private val five = all.firstOrNull { it.answers.size == 5 }
    private val two = all.firstOrNull { it.answers.size == 2 }

    private fun QuizState.wrongs() = (0 until slots).filter { !isCorrect(it) }
    private fun QuizState.right() = (0 until slots).first { isCorrect(it) }
    private fun QuizState.longestSlot(of: List<Int>) = of.maxBy { answer(it).length }

    @Test fun fresh() = quiz("1_fresh", state())

    @Test fun lit() = quiz("2_lit", state().apply { rotate(2) })

    @Test fun wrong() = quiz("3_wrong", state().apply { choose(wrongs().first()) })

    @Test fun wrongThenLit() = quiz("3b_wrong_then_lit", state().apply {
        val w = wrongs()
        choose(w[0]); choose(w[1])
        rotate(1)
    })

    @Test fun right() = quiz("4_right", state().apply { choose(wrongs().first()); choose(right()) })

    @Test fun longestLit() = quiz("5_longest_lit", only(longest).apply { highlighted = longestSlot((0 until slots).toList()) })

    @Test fun longestRight() = quiz("6_longest_right", only(longest).apply { choose(longestSlot(wrongs())); choose(right()) })

    @Test fun longestAnswerLit() = quiz("6b_longest_answer", only(longestAnswer).apply { highlighted = longestSlot((0 until slots).toList()) })

    @Test fun fiveAnswers() = quiz("6c_five", only(five ?: longest).apply { rotate(1) })

    @Test fun twoAnswers() = quiz("6d_two", only(two ?: longest).apply { choose(wrongs().first()) })

    @Test fun stats() = quiz("7_stats", state().apply {
        repeat(9) {
            if (it % 4 == 0) choose(wrongs().first())
            choose(right()); next()
        }
        showStats = true
    })

    private val entries = listOf(PickerEntry(0, "כל השערים", all.size, 41)) +
        TestBank.parts.mapIndexed { i, p -> PickerEntry(p.number, p.title, p.questions.size, (p.questions.size * i) / 14) }

    @Test fun pickerFirst() = shot("8_picker_all") { PartPicker(entries, 0, {}, {}) }

    @Test fun pickerMiddle() = shot("8b_picker_part") { PartPicker(entries, 6, {}, {}) }

    @Test fun pickerLast() = shot("8c_picker_last") { PartPicker(entries, entries.lastIndex, {}, {}) }
}

@Config(sdk = [36], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class Screenshot480 : ScreenshotTest("480")

@Config(sdk = [36], qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class Screenshot432 : ScreenshotTest("432")
