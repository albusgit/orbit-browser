package com.albustech.orbit.claude.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.albustech.orbit.claude.R

/** Speak or type a message: the watch's own voice and keyboard screens, as in Orbit (UrlEntry). */
class MessageEntry internal constructor(
    private val speakAction: () -> Unit,
    private val typeAction: () -> Unit,
) {
    fun speak() = speakAction()
    fun type() = typeAction()
}

private const val REMOTE_INPUT_KEY = "claude_message"

@Composable
fun rememberMessageEntry(onText: (String) -> Unit): MessageEntry {
    val context = LocalContext.current
    val currentOnText by rememberUpdatedState(onText)

    val keyboard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
        if (!text.isNullOrBlank()) currentOnText(text)
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) currentOnText(text)
    }

    return remember(context) {
        val typeAction = { keyboard.launch(keyboardIntent(context)) }
        MessageEntry(
            speakAction = {
                try {
                    voice.launch(voiceIntent(context))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.no_speech, Toast.LENGTH_SHORT).show()
                    typeAction()
                }
            },
            typeAction = typeAction,
        )
    }
}

private fun voiceIntent(context: Context) =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_prompt))
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

private fun keyboardIntent(context: Context): Intent {
    val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
        .setLabel(context.getString(R.string.keyboard_label))
        .setAllowFreeFormInput(true)
        .wearableExtender {
            setEmojisAllowed(true)
            setInputActionType(EditorInfo.IME_ACTION_SEND)
        }
        .build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
    }
}
