package com.albustech.mikraot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionsTest {

    private val all = TestBank.parts.flatMap { it.questions }

    @Test
    fun partsAreNumberedInOrder() {
        val numbers = TestBank.parts.map { it.number }
        assertEquals(numbers.sorted(), numbers)
        assertTrue(numbers.all { it in 1..12 })
        assertTrue(TestBank.parts.all { it.title.isNotBlank() && it.questions.isNotEmpty() })
    }

    @Test
    fun everyQuestionHasDistinctAnswers() {
        for (q in all) {
            assertTrue(q.text, q.text.isNotBlank())
            assertEquals(q.text, q.answers.size, q.answers.map { it.trim() }.filter { it.isNotEmpty() }.toSet().size)
        }
    }

    @Test
    fun noQuestionRepeatsWithinAPart() {
        for (p in TestBank.parts) {
            val keys = p.questions.map { it.text to it.answers[it.correct] }
            assertEquals(p.title, keys.size, keys.toSet().size)
        }
    }

    @Test
    fun answersFitTheWatch() {
        for (q in all) for (a in q.answers) assertTrue("$a (${q.text})", a.length <= 95)
    }

    @Test
    fun mixedModeHasEveryQuestion() {
        assertEquals(all.size, QuestionBank.all(TestBank.parts).questions.size)
    }
}
