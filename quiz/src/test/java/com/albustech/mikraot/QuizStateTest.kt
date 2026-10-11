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
        val right = (0 until 4).first { s.isCorrect(it) }
        s.tapSlot(right)
        assertFalse(s.answered)
        s.tapSlot(right)
        assertTrue(s.answered)
        assertEquals(right, s.chosen)
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
    fun aWrongPickKeepsTheQuestionUntilTheRightOne() {
        val s = state()
        val first = s.question
        val wrongs = (0 until 4).filter { !s.isCorrect(it) }
        s.tapSlot(wrongs[0]); s.tapSlot(wrongs[0])
        assertFalse(s.answered)
        assertEquals(setOf(wrongs[0]), s.wrong)
        s.rotate(1) // the bezel does not skip the question
        assertTrue(first === s.question)
        s.choose(wrongs[1])
        assertEquals(0, s.progress.answered)
        val right = (0 until 4).first { s.isCorrect(it) }
        s.choose(right)
        assertTrue(s.answered)
        // Passed, but not on the first pick: it counts as a miss.
        assertEquals(1, s.progress.answered)
        assertEquals(0, s.progress.correct)
        s.rotate(1)
        assertTrue(first !== s.question)
        assertTrue(s.wrong.isEmpty())
    }

    @Test
    fun bezelSkipsWrongPicks() {
        val s = state()
        val wrongs = (0 until 4).filter { !s.isCorrect(it) }
        wrongs.forEach(s::choose)
        s.highlighted = null
        s.rotate(1)
        assertTrue(s.isCorrect(s.highlighted!!))
        assertFalse(s.rotate(1)) // only one answer left to light
    }

    @Test
    fun tappingAWrongPickAgainDoesNothing() {
        val s = state()
        val w = (0 until 4).first { !s.isCorrect(it) }
        s.choose(w)
        s.tapSlot(w); s.tapBackground()
        assertEquals(setOf(w), s.wrong)
        assertFalse(s.answered)
    }
}
