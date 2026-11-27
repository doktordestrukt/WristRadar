package com.wristradar.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristradar.ui.theme.LocalPalette
import kotlinx.coroutines.delay

/** One text box at a time, shown above every screen. */
object TextEntry {
    class Request(val label: String, val onText: (String) -> Unit, val onClosed: () -> Unit)
    var request by mutableStateOf<Request?>(null)
        private set
    fun open(r: Request) { request = r }
    internal fun close() { request = null }
}

@Composable
fun TextEntryOverlay() {
    val r = TextEntry.request ?: return
    val p = LocalPalette.current
    val kb = LocalSoftwareKeyboardController.current
    val focus = remember(r) { FocusRequester() }
    var value by remember(r) { mutableStateOf(TextFieldValue("")) }
    // Voice / handwriting via the system input screen, written into the box.
    val other = rememberSystemTextInput { t -> value = TextFieldValue(value.text + t, TextRange(value.text.length + t.length)) }
    fun close(send: Boolean) {
        val text = value.text.trim()
        kb?.hide()
        TextEntry.close()
        r.onClosed()
        if (send && text.isNotEmpty()) r.onText(text)
    }
    BackHandler { close(false) }
    LaunchedEffect(r) {
        delay(30)                       // let the box attach first
        runCatching { focus.requestFocus() }
        kb?.show()
    }
    Box(
        Modifier.fillMaxSize().background(p.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { runCatching { focus.requestFocus() }; kb?.show() },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(r.label, fontSize = 11.sp, color = p.textDim, textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = value, onValueChange = { value = it },
                modifier = Modifier.focusRequester(focus).fillMaxWidth().heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(14.dp)).background(p.surface).padding(10.dp),
                textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                cursorBrush = SolidColor(p.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { close(true) }, onDone = { close(true) }),
                maxLines = 4,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("✕", p.surface, p.text) { close(false) }
                Pill("🎙", p.surface, p.text) { other(r.label) }
                Pill("Send", p.primary, p.onColor(p.primary)) { close(true) }
            }
        }
    }
}

@Composable
private fun Pill(label: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, color = fg) }
}
