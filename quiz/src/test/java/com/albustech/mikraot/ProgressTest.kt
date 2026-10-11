package com.albustech.mikraot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class ProgressTest {

    @Test
    fun aRoundAsksEveryQuestionOnce() {
        val p = Progress(10)
        val r = Random(1)
        val asked = (0 until 10).map { p.current(r).also { q -> p.record(q, true, r) } }
        assertEquals((0 until 10).toSet(), asked.toSet())
    }

    @Test
    fun aMissComesBackAfterThreeOthers() {
        val p = Progress(10)
        val r = Random(2)
        val missed = p.current(r)
        p.record(missed, false, r)
        repeat(Progress.RETRY_GAP) {
            val q = p.current(r)
            assertNotEquals(missed, q)
            p.record(q, true, r)
        }
        assertEquals(missed, p.current(r))
    }

    @Test
    fun unmasteredQuestionsComeFirstInTheNextRound() {
        val p = Progress(6)
        val r = Random(3)
        // Round 1: miss question asked first, get the rest right twice over two rounds.
        repeat(2) {
            repeat(6) {
                val q = p.current(r)
                p.record(q, q != 0, r)
                if (q == 0) { // answer it right on the retry so the round ends
                    repeat(minOf(Progress.RETRY_GAP, 5)) { val o = p.current(r); p.record(o, o != 0, r) }
                }
            }
        }
        assertEquals(0, p.current(r))
    }

    @Test
    fun scoreAndStreak() {
        val p = Progress(5)
        val r = Random(4)
        listOf(true, true, true, false, true).forEach { ok -> p.record(p.current(r), ok, r) }
        assertEquals(5, p.answered)
        assertEquals(4, p.correct)
        assertEquals(1, p.streak)
        assertEquals(3, p.best)
    }

    @Test
    fun encodeRoundTrips() {
        val p = Progress(8)
        val r = Random(5)
        repeat(11) { p.record(p.current(r), it % 3 != 0, r) }
        val back = Progress.decode(p.encode(), 8)
        assertEquals(p.encode(), back.encode())
        assertEquals(p.current(r), back.current(r))
    }

    @Test
    fun decodeFallsBackToFresh() {
        assertEquals(Progress(4).encode(), Progress.decode("garbage", 4).encode())
        assertEquals(Progress(4).encode(), Progress.decode(null, 4).encode())
        assertEquals(Progress(4).encode(), Progress.decode(Progress(5).encode(), 4).encode())
    }
}
