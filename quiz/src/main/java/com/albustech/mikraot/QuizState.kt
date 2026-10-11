package com.albustech.mikraot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * What the quiz screen shows: the current question, its answers in a shuffled order
 * ("slots", top to bottom), the slot the bezel is on, and the wrong picks so far.
 *
 * A question is only passed with its right answer: a wrong pick is marked and you pick
 * again. It counts as known only if the first pick was right.
 */
class QuizState(
    private val questions: List<Question>,
    progress: Progress,
    private val save: (Progress) -> Unit = {},
    private val random: Random = Random,
) {
    var progress by mutableStateOf(progress); private set
    var index by mutableIntStateOf(progress.current(random)); private set

    /** order[slot] = index into [Question.answers]. */
    var order by mutableStateOf(shuffledOrder()); private set
    var highlighted by mutableStateOf<Int?>(null)
    /** The right slot, once picked. */
    var chosen by mutableStateOf<Int?>(null); private set

    /** Wrong picks on this question. */
    var wrong by mutableStateOf(emptySet<Int>()); private set
    var showStats by mutableStateOf(false)

    /** Bumped on every pick so the screen can react (haptics, rim flash). */
    var answerCount by mutableIntStateOf(0); private set

    val question: Question get() = questions[index]

    /** How many answers the current question has (2–5). */
    val slots: Int get() = order.size
    /** The right answer has been picked. */
    val answered: Boolean get() = chosen != null

    /** The latest pick was right (after a pick; a wrong pick leaves the question open). */
    val lastCorrect: Boolean get() = answered

    fun answer(slot: Int): String = question.answers[order[slot]]
    fun isCorrect(slot: Int): Boolean = order[slot] == question.correct

    /** [detents] bezel clicks: move the highlight, wrapping and skipping wrong picks. Returns false if nothing moved. */
    fun rotate(detents: Int): Boolean {
        if (detents == 0) return false
        if (answered) {
            next()
            return true
        }
        val open = (0 until slots).filter { it !in wrong }
        val step = if (detents > 0) 1 else -1
        var slot = highlighted ?: if (detents > 0) -1 else slots
        repeat(Math.abs(detents)) {
            do slot = Math.floorMod(slot + step, slots) while (slot !in open)
        }
        if (slot == highlighted) return false
        highlighted = slot
        return true
    }

    /** A tap on a slot: the first tap highlights it, a tap on the highlighted slot answers. */
    fun tapSlot(slot: Int) {
        when {
            answered -> next()
            slot in wrong -> Unit
            highlighted == slot -> choose(slot)
            else -> highlighted = slot
        }
    }

    /** A tap outside the answers: answers with the highlighted slot, or moves on. */
    fun tapBackground() {
        when {
            answered -> next()
            else -> highlighted?.let(::choose)
        }
    }

    fun choose(slot: Int) {
        if (answered || slot in wrong) return
        highlighted = slot
        if (isCorrect(slot)) {
            chosen = slot
            progress.record(index, wrong.isEmpty(), random)
            save(progress)
        } else {
            wrong = wrong + slot
        }
        answerCount++
    }

    fun next() {
        index = progress.current(random)
        order = shuffledOrder()
        highlighted = null
        chosen = null
        wrong = emptySet()
    }

    fun reset() {
        progress = Progress(questions.size)
        save(progress)
        showStats = false
        next()
    }

    private fun shuffledOrder(): List<Int> {
        val q = questions[index]
        val order = q.answers.indices.toList()
        return if (q.fixed) order else order.shuffled(random)
    }
}
