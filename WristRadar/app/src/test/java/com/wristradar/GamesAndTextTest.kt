package com.wristradar

import androidx.compose.ui.graphics.Color
import com.wristradar.core.GameKind
import com.wristradar.core.GamePhase
import com.wristradar.core.GameRules
import com.wristradar.core.GameState
import com.wristradar.ui.components.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamesAndTextTest {
    @Test fun c4_discs_stack_and_column_fills() {
        var b = GameRules.emptyBoard(GameKind.C4)
        repeat(GameRules.C4_ROWS) { b = b.toMutableList().also { l -> l[GameRules.c4Drop(it.let { b }, 3)] = 1 } }
        assertEquals(-1, GameRules.c4Drop(b, 3))
        assertEquals(-1, GameRules.c4Drop(b, 7))
    }

    @Test fun c4_detects_wins_in_all_directions() {
        fun board(vararg cells: Pair<Int, Int>) = GameRules.emptyBoard(GameKind.C4).toMutableList().also { b -> cells.forEach { (r, c) -> b[r * 7 + c] = 2 } }
        assertEquals(2, GameRules.c4Winner(board(5 to 0, 5 to 1, 5 to 2, 5 to 3)))
        assertEquals(2, GameRules.c4Winner(board(5 to 6, 4 to 6, 3 to 6, 2 to 6)))
        assertEquals(2, GameRules.c4Winner(board(5 to 0, 4 to 1, 3 to 2, 2 to 3)))
        assertEquals(2, GameRules.c4Winner(board(2 to 3, 3 to 4, 4 to 5, 5 to 6)))
        assertEquals(0, GameRules.c4Winner(board(5 to 0, 5 to 1, 5 to 2)))
    }

    @Test fun memory_deck_same_on_both_watches_and_has_pairs() {
        assertEquals(GameRules.memDeck(1234), GameRules.memDeck(1234))
        assertTrue(GameRules.memDeck(99).groupingBy { it }.eachCount().values.all { it == 2 })
    }

    @Test fun memory_match_keeps_turn_and_mismatch_passes_it() {
        val id = 777
        val deck = GameRules.memDeck(id)
        val a = 0; val b = deck.indices.first { it != a && deck[it] == deck[a] }; val c = deck.indices.first { deck[it] != deck[a] }
        var s = GameState(GameKind.MEMORY, id, iStarted = true, phase = GamePhase.PLAYING, board = GameRules.emptyBoard(GameKind.MEMORY))
        s = GameRules.memFlip(s, a, true)!!; s = GameRules.memFlip(s, b, true)!!
        assertEquals(1, s.board[a]); assertEquals(1, s.board[b]); assertTrue(s.starterTurn)
        assertNull(GameRules.memFlip(s, a, true))   // already matched
        val d = deck.indices.first { it != c && s.board[it] == 0 && deck[it] != deck[c] }
        s = GameRules.memFlip(s, c, true)!!; s = GameRules.memFlip(s, d, true)!!
        assertFalse(s.starterTurn)
        assertNotNull(GameRules.memFlip(s, c, false))   // mismatch hidden again on next flip
    }

    @Test fun dice_values_validate() {
        assertTrue(GameRules.validDice(36)); assertEquals(9, GameRules.diceSum(36))
        assertFalse(GameRules.validDice(70)); assertFalse(GameRules.validDice(10))
    }

    @Test fun new_games_need_new_version_old_ones_dont() {
        assertEquals(0, GameKind.TTT.since); assertEquals(13, GameKind.C4.since)
        assertTrue(GameKind.entries.all { it.since <= BuildConfig.VERSION_CODE })
    }

    @Test fun formatting_and_spoilers() {
        val f = RichText.format("hi **bold** ||secret|| `x`", Color.White, revealSpoilers = false)
        assertEquals("hi bold secret x", f.text)
        assertTrue(RichText.hasSpoiler("a ||b||")); assertFalse(RichText.hasSpoiler("a || b"))
        assertEquals("see ▒▒▒ and bold", RichText.plain("see ||it|| and **bold**"))
        assertEquals("5 * 3 = 15", RichText.plain("5 * 3 = 15"))
    }

    @Test fun jumbo_emoji_detection() {
        assertTrue(RichText.isJumboEmoji("😂")); assertTrue(RichText.isJumboEmoji("❤️🔥"))
        assertFalse(RichText.isJumboEmoji("lol 😂")); assertFalse(RichText.isJumboEmoji("😂😂😂😂"))
    }
}
