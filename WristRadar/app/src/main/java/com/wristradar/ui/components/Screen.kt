package com.wristradar.ui.components

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender

/** Standard round-screen scaffold: time at top, vignette, scroll indicator, rotary-friendly list. */
@Composable
fun WrScreen(
    state: ScalingLazyListState = rememberScalingLazyListState(),
    showTime: Boolean = true,
    content: ScalingLazyListScope.() -> Unit,
) {
    Scaffold(
        timeText = { if (showTime) TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 36.dp),
            content = content,
        )
    }
}

private const val INPUT_KEY = "wr_text"

/**
 * Launches the Wear OS system text input (Samsung keyboard, voice, handwriting, emoji - whatever
 * the watch provides). Using RemoteInput avoids assuming a specific keyboard is installed.
 */
@Composable
fun rememberTextInput(onClosed: () -> Unit = {}, onText: (String) -> Unit): (String) -> Unit {
    val cb by rememberUpdatedState(onText)
    val closed by rememberUpdatedState(onClosed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        closed()
        val data: Intent = res.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(INPUT_KEY)?.toString()
        if (!text.isNullOrBlank()) cb(text)
    }
    return { label ->
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val input = RemoteInput.Builder(INPUT_KEY)
            .setLabel(label)
            .wearableExtender {
                setEmojisAllowed(true)
                setInputActionType(EditorInfo.IME_ACTION_SEND)
            }
            .build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        runCatching { launcher.launch(intent) }
    }
}
