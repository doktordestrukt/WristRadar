package com.wristradar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.audio.GroupCall
import com.wristradar.ui.components.Avatar
import com.wristradar.ui.components.Badge
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.rememberTextInput
import com.wristradar.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Every conversation: the primary friend, other contacts and groups. */
@Composable
fun ChatsScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val contacts by g.trust.contacts.collectAsState()
    val groups by g.engine.groups.groups.collectAsState()
    val peers by g.engine.peers.collectAsState()
    val names by g.trust.names.collectAsState()
    val primaryName by g.profile.partnerName.collectAsState()
    WrScreen {
        item { SectionLabel("Chats") }
        contacts.forEachIndexed { i, c ->
            item(key = "c" + c.id) {
                val conv = if (i == 0) "" else "c:${c.id}"
                val unread by remember(conv) { g.messages.unreadIn(conv) }.collectAsState(initial = 0)
                val name = if (i == 0) primaryName ?: "Friend" else names[c.id] ?: g.trust.contactName(c.id) ?: "Friend"
                val online = peers[c.id]?.connected == true
                WrChip(name, { nav.navigate(if (i == 0) "chat" else "chat/$conv") },
                    secondary = (if (online) "● Online" else "Away") + (if (unread > 0) " · $unread new" else ""),
                    icon = { Box { Avatar(null, name, 26.dp, ring = false); if (unread > 0) Box(Modifier.align(Alignment.TopEnd)) { Badge(unread) } } },
                    primary = unread > 0, tile = false)
            }
        }
        groups.forEach { grp ->
            item(key = "g" + grp.id) {
                val unread by remember(grp.conv) { g.messages.unreadIn(grp.conv) }.collectAsState(initial = 0)
                val online = grp.members.count { peers[it.id]?.connected == true }
                WrChip("👥 " + grp.name, { nav.navigate("chat/${grp.conv}") },
                    secondary = "${grp.members.size} people · $online nearby" + (if (unread > 0) " · $unread new" else ""),
                    primary = unread > 0, tile = false)
            }
        }
        if (contacts.isNotEmpty()) item {
            WrChip("➕ New group", { nav.navigate("newgroup") }, secondary = "Chat and call with several friends", tile = false)
        }
        item { WrChip("➕ Add a friend", { nav.navigate("discovery") }, secondary = "Pair with another watch nearby", tile = false) }
        if (contacts.isEmpty()) item { Hint("Pair with a watch first, then you can make groups.") }
    }
}

/** Pick contacts, name the group, create it. */
@Composable
fun NewGroupScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val contacts by g.trust.contacts.collectAsState()
    val names by g.trust.names.collectAsState()
    val primaryName by g.profile.partnerName.collectAsState()
    var picked by remember { mutableStateOf(setOf<String>()) }
    var name by remember { mutableStateOf("") }
    val askName = rememberTextInput { name = it.take(40) }
    WrScreen {
        item { SectionLabel("New group") }
        item { WrChip(if (name.isBlank()) "Name the group" else name, { askName("Group name") }, secondary = if (name.isBlank()) "Tap to type" else "Tap to change", primary = name.isBlank(), tile = false) }
        item { SectionLabel("Who's in it?") }
        contacts.forEachIndexed { i, c ->
            item(key = c.id) {
                val n = if (i == 0) primaryName ?: "Friend" else names[c.id] ?: "Friend"
                val on = c.id in picked
                WrChip((if (on) "✅ " else "⬜ ") + n, { picked = if (on) picked - c.id else picked + c.id }, primary = on, tile = false)
            }
        }
        item {
            WrChip("Create group", {
                val grp = g.engine.groups.create(name.ifBlank { "Group" }, picked.toList())
                if (grp != null) { nav.popBackStack(); nav.navigate("chat/${grp.conv}") }
            }, secondary = if (picked.isEmpty()) "Pick at least one friend" else "${picked.size + 1} people including you", primary = picked.isNotEmpty(), enabled = picked.isNotEmpty(), tile = false)
        }
        item { Hint("Everyone needs WristRadar MK19 or newer. Messages reach members through any member they're connected to, and every message is signed by whoever wrote it.") }
    }
}

@Composable
fun GroupInfoScreen(nav: NavHostController, groupId: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val groups by g.engine.groups.groups.collectAsState()
    val peers by g.engine.peers.collectAsState()
    val grp = groups.firstOrNull { it.id == groupId }
    val scope = rememberCoroutineScope()
    var confirmLeave by remember { mutableStateOf(false) }
    WrScreen {
        item { SectionLabel(grp?.name ?: "Group") }
        if (grp == null) { item { Hint("This group no longer exists.") }; return@WrScreen }
        item {
            WrChip("📞 Group call", { if (g.engine.calls.group.start(groupId)) nav.navigate("groupcall") },
                secondary = "Everyone nearby joins with one tap", primary = true, tile = false)
        }
        item { SectionLabel("Members") }
        grp.members.forEach { m ->
            item(key = m.id) {
                val me = m.key.contentEquals(g.identity.publicKey)
                val online = me || peers[m.id]?.connected == true
                WrChip(m.name + if (me) " (you)" else "", {}, secondary = if (online) "● Online" else "Away",
                    icon = { Avatar(null, m.name, 24.dp, ring = false) }, tile = false)
            }
        }
        item {
            WrChip(if (confirmLeave) "Tap again to leave" else "Leave group", {
                if (!confirmLeave) confirmLeave = true
                else scope.launch { g.engine.groups.leave(groupId); nav.popBackStack(); nav.popBackStack() }
            }, danger = true, tile = false)
        }
    }
}

/** Live group call: who's in it, who's muted, mute / hang up. */
@Composable
fun GroupCallScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val gc = g.engine.calls.group
    val st by gc.state.collectAsState()
    val s = st
    var left by remember { mutableStateOf(false) }
    LaunchedEffect(s?.phase) {
        if (left) return@LaunchedEffect
        if (s == null) { left = true; nav.popBackStack(); return@LaunchedEffect }
        if (s.phase == GroupCall.Phase.ENDED) { delay(1500); left = true; nav.popBackStack(); gc.dismiss() }
    }
    if (s == null) return
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            Text("👥 " + s.groupName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text)
            Text(
                when (s.phase) {
                    GroupCall.Phase.INCOMING -> "${s.invitedBy ?: "Someone"} is calling the group"
                    GroupCall.Phase.ACTIVE -> if (s.people.isEmpty()) "Waiting for people to join…" else "%d:%02d".format((now - s.since) / 60000, (now - s.since) / 1000 % 60)
                    GroupCall.Phase.ENDED -> s.endReason ?: "Call ended"
                }, fontSize = 11.sp, color = p.textDim,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.people.forEach { (id, name) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(null, name, 30.dp)
                        Text((if (id in s.mutedPeople) "🔇 " else "") + name, fontSize = 9.sp, color = p.text, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s.phase) {
                    GroupCall.Phase.INCOMING -> {
                        RoundBtn("✓", Color(0xFF34C759)) { gc.accept() }
                        RoundBtn("✕", Color(0xFFFF3B30)) { gc.decline() }
                    }
                    GroupCall.Phase.ACTIVE -> {
                        RoundBtn(if (s.muted) "🔇" else "🎙", if (s.muted) p.text else p.surface) { gc.toggleMute() }
                        RoundBtn("✕", Color(0xFFFF3B30)) { gc.hangUp() }
                    }
                    GroupCall.Phase.ENDED -> Unit
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(label: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 18.sp, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** Settings › Contacts: everyone you're paired with; remove one. */
@Composable
fun ContactsScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val contacts by g.trust.contacts.collectAsState()
    val names by g.trust.names.collectAsState()
    val primaryName by g.profile.partnerName.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    WrScreen {
        item { SectionLabel("Contacts") }
        contacts.forEachIndexed { i, c ->
            item(key = c.id) {
                val n = if (i == 0) primaryName ?: "Friend" else names[c.id] ?: "Friend"
                WrChip(if (confirm == c.id) "Tap again to remove $n" else n, {
                    if (confirm == c.id) { g.engine.removeContact(c.id); confirm = null } else confirm = c.id
                }, secondary = if (confirm == c.id) "Deletes your chat with them" else if (i == 0) "First contact" else "Tap to remove",
                    danger = confirm == c.id, icon = { Avatar(null, n, 24.dp, ring = false) }, tile = false)
            }
        }
        item { WrChip("➕ Add a friend", { nav.navigate("discovery") }, tile = false) }
    }
}
