package com.albustech.mikraot

import java.io.File

/** The bundled question bank, read straight from the source tree. */
object TestBank {
    val parts: List<Part> by lazy { QuestionBank.parse(File("src/main/assets/questions.json").readText()) }
    val gate1: List<Question> get() = parts.first { it.number == 1 }.questions
}
