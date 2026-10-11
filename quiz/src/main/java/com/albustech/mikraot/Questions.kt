package com.albustech.mikraot

import org.json.JSONObject

/**
 * One multiple-choice question with 2–5 [answers]; [correct] is the right one's index (0 unless
 * the order is [fixed]). The screen shuffles the answers unless [fixed] is set, for options like
 * "כל התשובות נכונות" that only make sense in the author's order.
 */
class Question(val text: String, val answers: List<String>, val fixed: Boolean = false, val correct: Int = 0) {
    init {
        require(answers.size in 2..MAX_ANSWERS) { "2–$MAX_ANSWERS answers: $text" }
        require(correct in answers.indices) { "correct answer out of range: $text" }
    }

    companion object {
        const val MAX_ANSWERS = 5
    }
}

/** One part (שער) of מקראות ישראל. [key] names its saved progress. */
class Part(val number: Int, val title: String, val questions: List<Question>) {
    val key: String get() = "part$number"
}

/**
 * The question bank: `assets/questions.json`, built from public study material (see the README
 * for sources). Shape: `{"parts": [{"number", "title", "questions": [{"q", "a": [right, …]}]}]}`; a
 * question in the author's order has `"fixed": true` and `"answer": <index of the right one>`.
 */
object QuestionBank {
    fun parse(json: String): List<Part> {
        val parts = JSONObject(json).getJSONArray("parts")
        return (0 until parts.length()).map { i ->
            val p = parts.getJSONObject(i)
            val qs = p.getJSONArray("questions")
            Part(
                number = p.getInt("number"),
                title = p.getString("title"),
                questions = (0 until qs.length()).map { j ->
                    val q = qs.getJSONObject(j)
                    val a = q.getJSONArray("a")
                    Question(q.getString("q"), (0 until a.length()).map(a::getString), q.optBoolean("fixed"), q.optInt("answer"))
                },
            )
        }
    }

    /** Every question, for the mixed "all parts" mode. */
    fun all(parts: List<Part>): Part = Part(ALL, "כל השערים", parts.flatMap { it.questions })

    const val ALL = 0
}
