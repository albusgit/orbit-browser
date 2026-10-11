package com.albustech.mikraot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionsTest {

    @Test
    fun everyQuestionHasFourDistinctAnswers() {
        for (q in GATE_1) {
            assertTrue(q.text, q.text.isNotBlank())
            assertEquals(q.text, 4, q.answers.map { it.trim() }.filter { it.isNotEmpty() }.toSet().size)
        }
    }

    @Test
    fun questionsAreUnique() {
        assertEquals(GATE_1.size, GATE_1.map { it.text }.toSet().size)
    }

    @Test
    fun answersAreShortEnoughForTheWatch() {
        for (q in GATE_1) for (a in q.answers) assertTrue("$a (${q.text})", a.length <= 45)
    }
}
