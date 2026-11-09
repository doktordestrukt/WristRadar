package com.wristradar.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.R
import com.wristradar.WristRadarApp
import com.wristradar.ble.DistanceEstimator
import com.wristradar.core.ConnectionState
import com.wristradar.core.Phase
import com.wristradar.data.DistanceUnit
import com.wristradar.ui.components.Avatar
import com.wristradar.ui.components.Badge
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SignalBars
import com.wristradar.ui.components.StatusLine
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.animationsOn
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(nav: NavHostController, requestPermissions: () -> Unit) {
    val g = WristRadarApp.graph
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val conn by g.engine.connection.collectAsState()
    val partner by g.trust.partner.collectAsState()
    val partnerName by g.profile.partnerName.collectAsState()
    val partnerAvatar by g.profile.partnerAvatar.collectAsState()
    val myName by g.profile.myName.collectAsState()
    val myAvatar by g.profile.myAvatar.collectAsState()
    val unread by g.messages.unread.collectAsState(initial = 0)
    val settings by g.settings.state.collectAsState()
    val lastConnected by g.trust.lastConnected.collectAsState()

    WrScreen {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "WRISTRADAR", style = WrType.title.copy(shadow = Shadow(p.primary, Offset.Zero, 14f)),
                    color = p.primary,
                )
                Spacer(Modifier.width(3.dp))
                Text(com.wristradar.BuildConfig.RELEASE_LABEL, style = WrType.label.copy(fontSize = 8.sp), color = p.textDim)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clickable { nav.navigate("profile") },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(myAvatar, myName, 46.dp)
                Text(myName, fontSize = 14.sp, color = p.text, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item { StatusCard(conn, partnerName, partner != null, settings.unit, lastConnected) }
        if (partner != null) item {
            val presence by g.engine.presence.collectAsState()
            val lastSeen by g.trust.partnerLastSeen.collectAsState()
            Hint(
                (if (presence.partnerTyping) "typing…" else com.wristradar.ui.components.presenceText(conn.phase == Phase.CONNECTED, lastSeen)) +
                    (if (presence.partnerPhone == true) " · 📱" else ""),
            )
        }

        if (conn.phase == Phase.NO_PERMISSION) item {
            WrChip("Grant permissions", requestPermissions, secondary = "Nearby devices + notifications", primary = true)
        }
        if (conn.phase == Phase.BLUETOOTH_OFF) item {
            WrChip("Turn on Bluetooth", {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, primary = true)
        }
        // Away mode: while the friend is out of reach, only the chat stays (nothing that makes you
        // visible to other watches, like "Connect to nearby"). Everything returns when they're near.
        val away = partner != null && settings.awayMode && conn.phase != Phase.CONNECTED
        if (partner != null) {
            item {
                WrChip(
                    label = "Chat with ${partnerName ?: "partner"}",
                    onClick = { nav.navigate("chat") },
                    secondary = if (unread > 0) "$unread unread" else "End-to-end encrypted",
                    icon = {
                        Box {
                            Avatar(partnerAvatar, partnerName, 26.dp, ring = false)
                            if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(start = 12.dp)) { Badge(unread) }
                        }
                    },
                    primary = true,
                    tile = false,
                )
            }
            if (away) item {
                Hint("🌙 ${partnerName ?: "Your friend"} is away - extras come back when they're near.")
            }
            if (!away) item {
                val mood by g.engine.extras.partnerMood.collectAsState()
                WrChip("✨ Fun & extras", { nav.navigate("fun") },
                    secondary = mood?.let { "${partnerName ?: "Friend"}: $it" } ?: "Games, draw and more",
                    icon = { Text("🎮", fontSize = 16.sp) })
            }
            if (!away) item {
                WrChip("Walk to ${partnerName ?: "partner"}", { nav.navigate("walk") },
                    secondary = "Steps, distance & time",
                    icon = { Text("🚶", fontSize = 16.sp) })
            }
            item {
                WrChip(
                    "Refresh connection", { g.engine.refresh() },
                    secondary = conn.detail.ifBlank { null },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                )
            }
        }
        if (!away) item {
            WrChip(
                "Connect to nearby watches", { nav.navigate("discovery") },
                primary = partner == null,
                icon = { Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null) },
            )
        }
        item {
            WrChip("Settings", { nav.navigate("settings") }, icon = { Icon(Icons.Filled.Settings, contentDescription = null) })
        }
        item {
            val bat = g.battery.percent()
            Hint("Watch battery ${bat?.let { "$it%" } ?: "--"}")
        }
        item {
            Text(
                "Disclaimer", fontSize = 10.sp, color = p.textDim,
                modifier = Modifier.clickable { nav.navigate("disclaimer") }.padding(6.dp),
            )
        }
    }
}

/** The owner's disclaimer, linked from the bottom of the home screen. */
@Composable
fun DisclaimerScreen() {
    val p = LocalPalette.current
    com.wristradar.ui.components.WrScreen {
        item { com.wristradar.ui.components.SectionLabel("Disclaimer") }
        item {
            Text(
                "I do not claim responsibility for anything you say using this app, including inappropriate words.\n\n" +
                    "Only the people I have personally given this APK to - just me and one other person - are allowed to use it.\n\n" +
                    "If you share this APK, I will report you and you will not be allowed to use it.",
                fontSize = 12.sp, color = p.text, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun StatusCard(conn: ConnectionState, partnerName: String?, paired: Boolean, unit: DistanceUnit, lastConnected: Long) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (animationsOn()) {
            AnimatedContent(conn.phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { StatusLine(it) }
        } else StatusLine(conn.phase)
        if (paired) {
            Text(partnerName ?: "Partner", fontSize = 13.sp, color = p.text)
            if (conn.distanceM != null || conn.rssi != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(DistanceEstimator.format(conn.distanceM, unit), fontSize = 12.sp, color = p.primary)
                    Spacer(Modifier.width(6.dp))
                    SignalBars(conn.rssi)
                }
            }
            if (conn.phase != Phase.CONNECTED && lastConnected > 0) {
                Text(
                    "Last link " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastConnected)),
                    fontSize = 10.sp, color = p.textDim,
                )
            }
        }
        if (conn.detail.isNotBlank() && conn.phase != Phase.CONNECTED) {
            Text(conn.detail, fontSize = 10.sp, color = p.textDim, textAlign = TextAlign.Center)
        }
    }
}
