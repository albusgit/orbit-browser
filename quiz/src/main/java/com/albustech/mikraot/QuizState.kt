package com.albustech.mikraot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * What the quiz screen shows: the current question, its four answers in a shuffled order
 * ("slots", top to bottom), the slot the bezel is on, and the slot that was chosen.
 */
class QuizState(
    private val questions: List<Question>,
    progress: Progress,
    private val save: (Progress) -> Unit = {},
    private val random: Random = Random,
) {
    var progress by mutableStateOf(progress); private set
    var index by mutableIntStateOf(progress.current(random)); private set

    /** order[slot] = index into [Question.answers]; 0 is the correct answer. */
    var order by mutableStateOf(shuffledOrder()); private set
    var highlighted by mutableStateOf<Int?>(null)
    var chosen by mutableStateOf<Int?>(null); private set
    var showStats by mutableStateOf(false)

    /** Bumped on every answer so the screen can react (haptics, rim flash). */
    var answerCount by mutableIntStateOf(0); private set

    val question: Question get() = questions[index]
    val answered: Boolean get() = chosen != null
    val lastCorrect: Boolean get() = chosen?.let(::isCorrect) == true

    fun answer(slot: Int): String = question.answers[order[slot]]
    fun isCorrect(slot: Int): Boolean = order[slot] == 0

    /** [detents] bezel clicks: move the highlight, wrapping. Returns false if nothing moved. */
    fun rotate(detents: Int): Boolean {
        if (detents == 0) return false
        if (answered) {
            next()
            return true
        }
        val from = highlighted ?: if (detents > 0) -1 else SLOTS
        highlighted = Math.floorMod(from + detents, SLOTS)
        return true
    }

    /** A tap on a slot: the first tap highlights it, a tap on the highlighted slot answers. */
    fun tapSlot(slot: Int) {
        when {
            answered -> next()
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
        if (answered) return
        chosen = slot
        highlighted = slot
        progress.record(index, isCorrect(slot), random)
        answerCount++
        save(progress)
    }

    fun next() {
        index = progress.current(random)
        order = shuffledOrder()
        highlighted = null
        chosen = null
    }

    fun reset() {
        progress = Progress(questions.size)
        save(progress)
        showStats = false
        next()
    }

    private fun shuffledOrder() = (0 until SLOTS).shuffled(random)

    companion object {
        const val SLOTS = 4
    }
}
