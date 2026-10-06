package com.albustech.orbit.browser

import android.app.AlertDialog
import android.content.Context
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse

/**
 * The page prompts a watch can sensibly show: alert(), confirm() and <select> menus, as system
 * dialogs (round-aware on Wear OS). Everything else (text prompts, logins, file pickers, colour
 * and date pickers) is dismissed, as WebView did without a chrome client for them.
 */
class Prompts(private val context: Context) : PromptDelegate {

    /** A result that takes the first answer only: a dialog's dismiss follows its button. */
    private class Answer {
        val result = GeckoResult<PromptResponse>()
        private var done = false

        fun give(response: () -> PromptResponse) {
            if (done) return
            done = true
            result.complete(response())
        }
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse> {
        val answer = Answer()
        AlertDialog.Builder(context)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> }
            .setOnDismissListener { answer.give(prompt::dismiss) }
            .show()
        return answer.result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse> {
        val answer = Answer()
        AlertDialog.Builder(context)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> answer.give { prompt.confirm(ButtonPrompt.Type.POSITIVE) } }
            .setNegativeButton(android.R.string.cancel) { _, _ -> answer.give { prompt.confirm(ButtonPrompt.Type.NEGATIVE) } }
            .setOnDismissListener { answer.give(prompt::dismiss) }
            .show()
        return answer.result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> {
        val answer = Answer()
        // Option groups are flattened; separators and disabled options are left out.
        val choices = prompt.choices.flatMap { c -> if (c.items != null) c.items!!.toList() else listOf(c) }
            .filter { !it.separator && !it.disabled }
        if (choices.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())
        val labels = choices.map { it.label }.toTypedArray()
        val builder = AlertDialog.Builder(context).setTitle(prompt.title ?: prompt.message)
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(choices.size) { choices[it].selected }
            builder.setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer.give { prompt.confirm(choices.filterIndexed { i, _ -> checked[i] }.toTypedArray()) }
                }
        } else {
            builder.setSingleChoiceItems(labels, choices.indexOfFirst { it.selected }) { dialog, i ->
                answer.give { prompt.confirm(choices[i]) }
                dialog.dismiss()
            }
        }
        builder.setOnDismissListener { answer.give(prompt::dismiss) }.show()
        return answer.result
    }
}
