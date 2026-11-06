package com.wristradar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.core.GamePhase
import com.wristradar.core.GameRules
import com.wristradar.core.GameState
import com.wristradar.ui.theme.LocalPalette

@Composable
fun C4Body(s: GameState, name: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    if (s.phase != GamePhase.DONE) Text(if (s.myTurn) "Your turn - tap a column" else "$name's turn", fontSize = 10.sp, color = p.textDim)
    Spacer(Modifier.height(3.dp))
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E3A8A).copy(alpha = 0.85f)).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (r in 0 until GameRules.C4_ROWS) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (c in 0 until GameRules.C4_COLS) {
                val v = s.board[r * GameRules.C4_COLS + c]
                Box(
                    Modifier.size(19.dp).clip(CircleShape)
                        .background(when (v) { 0 -> Color.Black.copy(alpha = 0.55f); s.myMark -> Color(0xFFFF4F8B); else -> Color(0xFFFFD23F) })
                        .clickable(enabled = s.phase == GamePhase.PLAYING && s.myTurn) { g.engine.extras.move(c) },
                )
            }
        }
    }
}

@Composable
fun MemoryBody(s: GameState, name: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val deck = GameRules.memDeck(s.id)
    val mine = s.board.count { it == s.myMark } / 2
    val theirs = s.board.count { it != 0 && it != s.myMark } / 2
    Text("You $mine · $name $theirs", fontSize = 11.sp, color = p.text)
    if (s.phase != GamePhase.DONE) Text(if (s.myTurn) "Your turn - flip 2" else "$name is flipping…", fontSize = 10.sp, color = p.textDim)
    Spacer(Modifier.height(3.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (r in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (c in 0 until 4) {
                val i = r * 4 + c
                val open = s.board[i] != 0 || i in s.flips
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(7.dp))
                        .background(
                            when {
                                s.board[i] == s.myMark -> p.primary.copy(alpha = 0.45f)
                                s.board[i] != 0 -> p.secondary.copy(alpha = 0.35f)
                                open -> Color.White.copy(alpha = 0.30f)
                                else -> p.surface
                            }
                        )
                        .clickable(enabled = s.phase == GamePhase.PLAYING && s.myTurn && !open) { g.engine.extras.move(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(if (open) GameRules.MEM_FACES[deck[i]] else "?", fontSize = if (open) 17.sp else 13.sp, color = p.textDim) }
            }
        }
    }
}

private val DiceFaces = listOf("", "⚀", "⚁", "⚂", "⚃", "⚄", "⚅")

@Composable
fun DiceBody(s: GameState, name: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    fun show(v: Int?) = if (v == null) "🎲 🎲" else "${DiceFaces[v / 10]} ${DiceFaces[v % 10]}"
    Text("You: ${show(s.mine)}" + (s.mine?.let { "  = ${GameRules.diceSum(it)}" } ?: ""), fontSize = 14.sp, color = p.text)
    Text("$name: " + if (s.phase == GamePhase.DONE) show(s.theirs) + "  = ${GameRules.diceSum(s.theirs ?: 0)}" else if (s.theirs != null) "rolled 🤫" else "…", fontSize = 14.sp, color = p.text)
    if (s.mine == null && s.phase == GamePhase.PLAYING) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Roll!", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.onColor(p.primary),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(p.primary).clickable { g.engine.extras.move(0) }.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
}
