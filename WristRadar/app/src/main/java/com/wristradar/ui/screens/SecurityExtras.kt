package com.wristradar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.crypto.SafetyCode
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.theme.LocalPalette

/** Emoji safety code: compare in person to prove nobody is in the middle. */
@Composable
fun SafetyCodeScreen() {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val partner by g.trust.partner.collectAsState()
    val s by g.settings.state.collectAsState()
    val name by g.profile.partnerName.collectAsState()
    WrScreen {
        item { SectionLabel("Safety code") }
        val pt = partner
        if (pt == null) { item { Hint("Pair with your friend first.") }; return@WrScreen }
        val code = SafetyCode.compute(g.identity.publicKey, pt.identityKey)
        val fp = SafetyCode.fingerprint(pt.identityKey)
        val verified = s.safetyVerified == fp
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(code.take(4).joinToString(" "), fontSize = 22.sp, textAlign = TextAlign.Center)
                Text(code.drop(4).joinToString(" "), fontSize = 22.sp, textAlign = TextAlign.Center)
            }
        }
        item {
            Hint("Hold your watches side by side. If ${name ?: "your friend"}'s watch shows exactly the same emojis in the same order, nobody can be secretly in the middle of your chats.")
        }
        item {
            if (verified) WrChip("Verified ✓", { g.settings.update { it.copy(safetyVerified = "") } },
                secondary = "Tap to undo", primary = true, icon = { Icon(Icons.Filled.Check, null) })
            else WrChip("They match - mark verified", { g.settings.update { it.copy(safetyVerified = fp) } },
                icon = { Icon(Icons.Filled.Check, null) })
        }
        if (!verified) item {
            Hint("If they DON'T match, don't trust this connection: unpair and pair again in person.")
        }
        item { Text("If you ever re-pair, the code changes and you'll need to check again.", fontSize = 10.sp, color = p.textDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
}
