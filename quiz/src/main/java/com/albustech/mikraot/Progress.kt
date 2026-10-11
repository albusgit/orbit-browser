package com.albustech.mikraot

import kotlin.random.Random

/**
 * The endless question queue and the score.
 *
 * Every round asks each question once, the ones not yet mastered first. A miss puts the
 * question back [RETRY_GAP] places ahead, so it returns while the answer is still fresh, and
 * resets its mastery. [MASTERED] correct answers in a row master a question.
 */
class Progress(
    val size: Int,
    private val known: IntArray = IntArray(size),
    private val queue: ArrayDeque<Int> = ArrayDeque(),
    answered: Int = 0,
    correct: Int = 0,
    streak: Int = 0,
    best: Int = 0,
) {
    var answered = answered; private set
    var correct = correct; private set
    var streak = streak; private set
    var best = best; private set

    val mastered: Int get() = known.count { it >= MASTERED }

    /** The question to ask now. */
    fun current(random: Random = Random): Int {
        if (queue.isEmpty()) refill(random)
        return queue.first()
    }

    fun record(question: Int, ok: Boolean, random: Random = Random) {
        if (queue.firstOrNull() == question) queue.removeFirst() else queue.remove(question)
        answered++
        if (ok) {
            correct++
            streak++
            best = maxOf(best, streak)
            known[question] = minOf(known[question] + 1, MASTERED)
        } else {
            streak = 0
            known[question] = 0
            queue.add(minOf(RETRY_GAP, queue.size), question)
        }
        if (queue.isEmpty()) refill(random, avoid = question)
    }

    private fun refill(random: Random, avoid: Int = -1) {
        val (learning, done) = (0 until size).partition { known[it] < MASTERED }
        queue.addAll(learning.shuffled(random))
        queue.addAll(done.shuffled(random))
        // Never ask the question just answered twice in a row across rounds.
        if (queue.size > 1 && queue.first() == avoid) queue.add(queue.removeFirst())
    }

    fun encode(): String = listOf(
        VERSION, size, answered, correct, streak, best, known.joinToString(","), queue.joinToString(","),
    ).joinToString("|")

    companion object {
        const val MASTERED = 2
        const val RETRY_GAP = 3
        private const val VERSION = 1

        /** Restores [encode]d progress, or starts fresh if it is missing, corrupt or for another question list. */
        fun decode(text: String?, size: Int): Progress = runCatching {
            val f = text!!.split("|")
            require(f.size == 8 && f[0].toInt() == VERSION && f[1].toInt() == size)
            val known = f[6].split(",").map { it.toInt() }.toIntArray()
            require(known.size == size)
            val queue = ArrayDeque(if (f[7].isEmpty()) emptyList() else f[7].split(",").map { it.toInt() })
            require(queue.all { it in 0 until size })
            Progress(size, known, queue, f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt())
        }.getOrElse { Progress(size) }
    }
}
