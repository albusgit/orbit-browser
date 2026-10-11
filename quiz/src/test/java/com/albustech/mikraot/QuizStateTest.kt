package com.albustech.mikraot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizStateTest {

    private fun state() = QuizState(GATE_1, Progress(GATE_1.size), random = Random(7))

    @Test
    fun bezelWrapsAroundTheFourAnswers() {
        val s = state()
        s.rotate(1); assertEquals(0, s.highlighted)
        s.rotate(3); assertEquals(3, s.highlighted)
        s.rotate(1); assertEquals(0, s.highlighted)
        s.rotate(-1); assertEquals(3, s.highlighted)
    }

    @Test
    fun firstCounterClockwiseClickLightsTheLastAnswer() {
        val s = state()
        s.rotate(-1)
        assertEquals(3, s.highlighted)
    }

    @Test
    fun tapWithNothingLitDoesNothing() {
        val s = state()
        s.tapBackground()
        assertFalse(s.answered)
    }

    @Test
    fun tapOnASlotLightsItThenAnswers() {
        val s = state()
        s.tapSlot(2)
        assertFalse(s.answered)
        s.tapSlot(2)
        assertTrue(s.answered)
        assertEquals(2, s.chosen)
    }

    @Test
    fun theCorrectSlotIsTheFirstAnswer() {
        val s = state()
        val slot = (0 until 4).first { s.isCorrect(it) }
        assertEquals(s.question.answers[0], s.answer(slot))
        s.rotate(slot + 1)
        s.tapBackground()
        assertTrue(s.lastCorrect)
        assertEquals(1, s.progress.correct)
    }

    @Test
    fun bezelAfterAnAnswerMovesOn() {
        val s = state()
        val wrong = (0 until 4).first { !s.isCorrect(it) }
        val first = s.question
        s.tapSlot(wrong); s.tapSlot(wrong)
        assertFalse(s.lastCorrect)
        s.rotate(1)
        assertFalse(s.answered)
        assertNull(s.highlighted)
        assertTrue(first !== s.question)
    }
}
