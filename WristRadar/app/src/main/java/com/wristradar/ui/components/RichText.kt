package com.wristradar.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Discord-style formatting, drawn on this watch only (the message itself stays plain text,
 * so older apps simply show the symbols):
 *   **bold**  *italic*  __underline__  ~~strike~~  `code`  ||spoiler||
 */
object RichText {
    private val TOKEN = Regex("""\|\|(.+?)\|\||\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\*([^*\s][^*]*?)\*""")

    fun hasSpoiler(text: String) = text.contains("||") && TOKEN.findAll(text).any { it.groupValues[1].isNotEmpty() }

    fun hasFormatting(text: String) = TOKEN.containsMatchIn(text)

    fun format(text: String, color: Color, revealSpoilers: Boolean): AnnotatedString = buildAnnotatedString {
        var i = 0
        for (m in TOKEN.findAll(text)) {
            append(text, i, m.range.first)
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> if (revealSpoilers) withStyle(SpanStyle(background = color.copy(alpha = 0.15f))) { append(g[1]) }
                    else withStyle(SpanStyle(color = Color.Transparent, background = color.copy(alpha = 0.55f))) { append(g[1]) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[4]) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.30f))) { append(g[5]) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            }
            i = m.range.last + 1
        }
        if (i < text.length) append(text, i, text.length)
    }

    /** Text for notifications: formatting symbols removed, spoilers hidden. */
    fun plain(text: String): String = TOKEN.replace(text) { m ->
        val g = m.groupValues
        if (g[1].isNotEmpty()) "▒▒▒" else (2..6).map { g[it] }.firstOrNull { it.isNotEmpty() } ?: ""
    }

    /** True for messages that are only 1-3 emojis: shown big, like Discord. */
    fun isJumboEmoji(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 24) return false
        var emojis = 0
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val type = Character.getType(cp)
            when {
                Character.isWhitespace(cp) -> Unit
                cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF -> Unit   // joiners, variation selectors, skin tones
                type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() || cp >= 0x1F000 -> emojis++
                else -> return false
            }
            i += Character.charCount(cp)
        }
        return emojis in 1..3
    }
}
