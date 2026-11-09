package com.wristradar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Text
import com.wristradar.BuildConfig
import com.wristradar.WristRadarApp
import com.wristradar.data.ChatMessage
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.rememberTextInput
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import kotlinx.coroutines.launch

val QuickReactions = listOf("❤️", "😂", "👍", "😮", "😢", "🔥", "💀", "🙏")

/** Opened by holding a message: react, reply, edit, pin, delete. */
@Composable
fun MessageActionsScreen(nav: NavHostController, id: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val all by g.messages.messages.collectAsState(initial = null)
    val m = all?.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    val editInput = rememberTextInput { text -> scope.launch { g.engine.messenger.edit(id, text); nav.popBackStack() } }

    WrScreen {
        if (all != null && m == null) { item { Hint("This message was deleted.") }; return@WrScreen }
        if (m == null) return@WrScreen
        item { Text(snippet(m), fontSize = 12.sp, color = p.textDim, maxLines = 3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        item {
            // Message info: when it was sent and what happened to it.
            val time = android.text.format.DateFormat.format("EEE d MMM, HH:mm", m.sentAt).toString()
            val state = if (!m.outgoing) "Received" else when (m.status) {
                com.wristradar.data.db.MsgStatus.SEEN -> "Seen ✓✓"; com.wristradar.data.db.MsgStatus.DELIVERED -> "Delivered ✓✓"
                com.wristradar.data.db.MsgStatus.FAILED -> "Not sent"; else -> "Sending…"
            }
            Text("$time · $state" + if (m.edited) " · edited" else "", fontSize = 10.sp, color = p.textDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item { SectionLabel("React") }
        QuickReactions.chunked(4).forEachIndexed { i, row ->
            item(key = "r$i") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { e ->
                        Text(
                            e, fontSize = 20.sp,
                            modifier = Modifier.clip(CircleShape)
                                .background(if (m.reactMine == e) p.primary.copy(alpha = 0.5f) else p.surface)
                                .clickable { scope.launch { g.engine.messenger.react(id, e); nav.popBackStack() } }
                                .padding(6.dp),
                        )
                    }
                }
            }
        }
        item { WrChip("Reply", { ChatReply.target = m; nav.popBackStack() }) }
        if (m.outgoing && !m.isImage) item { WrChip("Edit", { editInput("Edit message") }) }
        item { WrChip(if (m.pinned) "Unpin" else "Pin", { scope.launch { g.engine.messenger.togglePin(id); nav.popBackStack() } }) }
        item {
            WrChip(
                if (confirmDelete) "Tap again to delete" else if (m.outgoing) "Delete for both" else "Delete for me",
                {
                    if (!confirmDelete) confirmDelete = true
                    else scope.launch { g.engine.messenger.deleteForBoth(id); nav.popBackStack() }
                },
                danger = true,
            )
        }
    }
}

@Composable
fun PinnedScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val all by g.messages.messages.collectAsState(initial = null)
    val pinned = all?.filter { it.pinned }.orEmpty()
    WrScreen {
        item { SectionLabel("Pinned") }
        if (all != null && pinned.isEmpty()) item { Hint("Nothing pinned. Hold a message and tap Pin.") }
        pinned.forEach { m -> item(key = m.id) { ResultChip(m) { nav.navigate("msg/${m.id}") } } }
    }
}

@Composable
fun SearchScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val all by g.messages.messages.collectAsState(initial = null)
    var query by remember { mutableStateOf("") }
    val input = rememberTextInput { query = it.trim() }
    val results = if (query.isBlank()) emptyList()
        else all.orEmpty().filter { !it.isImage && !it.isAlert && it.text.contains(query, ignoreCase = true) }.takeLast(40).reversed()
    WrScreen {
        item { SectionLabel("Search") }
        item { WrChip(if (query.isBlank()) "Type to search" else "\"$query\"", { input("Search messages") }, primary = true) }
        if (query.isNotBlank() && results.isEmpty()) item { Hint("No messages found.") }
        results.forEach { m -> item(key = m.id) { ResultChip(m) { nav.navigate("msg/${m.id}") } } }
    }
}

@Composable
private fun ResultChip(m: ChatMessage, onClick: () -> Unit) {
    val g = WristRadarApp.graph
    val who = if (m.outgoing) "You" else (g.profile.partnerName.value ?: "Partner")
    val time = android.text.format.DateFormat.format("d MMM HH:mm", m.localTime).toString()
    WrChip(snippet(m), onClick, secondary = "$who · $time")
}

/** Shown once after each update: announces the new version and what changed. */
@Composable
fun WhatsNewScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    WrScreen {
        item { Text("WRISTRADAR ${BuildConfig.RELEASE_LABEL}", style = WrType.title, color = p.primary) }
        item { Text("New version installed", fontSize = 12.sp, color = p.textDim) }
        item { Hint("Your chats, pairing and settings were kept.") }
        WhatsNew.items.forEach { (title, body) ->
            item(key = title) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(title, fontSize = 13.sp, color = p.text)
                    Text(body, fontSize = 11.sp, color = p.textDim)
                }
            }
        }
        item {
            WrChip("Got it", {
                g.settings.update { it.copy(seenVersion = BuildConfig.VERSION_CODE) }
                nav.popBackStack()
                if (!g.settings.current.pinIntroSeen && !g.lock.isSet) nav.navigate("pinintro")
            }, primary = true)
        }
    }
}

/** Release notes for the current version. Update this list with every new APK. */
object WhatsNew {
    val items = listOf(
        "✨ KK themes" to "8 new KK liquid glass themes: Nude, Champagne, Mocha, Cream, Black & Gold, Blush Nude, Sand and Diamond.",
        "🩹 Fixed: app closing after the PIN" to "Changing the app icon made Samsung close WristRadar. The icon now only changes a minute after you leave the app.",
        "🔑 A new key for every message" to "Each message is locked with its own key, destroyed right after use. Even a stolen key can't open a single older message.",
        "♻️ Faster healing" to "A completely fresh key exchange every hour when idle (was every 3 hours).",
        "🤝 Top-level pairing" to "New pairings use ML-KEM-1024 and ML-DSA-87 too, when both watches have MK17.",
        "🛡 Top-secret grade encryption" to "When both watches have MK16: ML-KEM-1024 keys, ML-DSA-87 + Ed25519 identity signatures, and SHA-384 / HKDF-SHA-384 - the CNSA 2.0 style suite.",
        "💀 Intruder screen fixed" to "Someone refused by your watch now really gets the skull and music. Preview it in Settings › Blackbox security.",
        "🔐 App lock PIN" to "Lock WristRadar with a 4-6 digit PIN, asked every 30 minutes (you can change how often). Settings › Privacy.",
        "🛡 Stronger encryption" to "Connections now use ML-KEM-1024, the strongest standard post-quantum key exchange, once both watches have this version.",
        "📡 Stronger Bluetooth" to "Max Range now steps down to Bluetooth's most robust long-range mode when your friend is far away, finds them faster while searching and reconnects quicker.",
        "🎉 Message effects" to "Send a message with confetti, hearts, fireworks, balloons, lasers, snow, sparkles or fire. Tap \"Send with effect\" in the chat.",
        "🌈 Gradient glass" to "10 new gradient liquid glass themes in their own folder.",
        "🖼 Icon matches your theme" to "Your app icon now changes to match the theme you pick, and fits the round watch screen perfectly.",
        "🌙 Away mode" to "While your friend is out of reach, the home screen shows only the chat. Everything comes back when they're near.",
        "🧈 Silkier scrolling" to "The chat no longer redraws itself every time the signal or distance changes, so scrolling stays smooth.",
        "🛟 Stuck messages fixed" to "Messages no longer get stuck on \"sending\" - a dead connection is noticed within seconds and reconnects by itself.",
        "⌨️ Keyboard fix" to "Fixed the black screen that sometimes appeared instead of the keyboard.",
        "🎮 3 new games" to "Connect 4, Memory Match and Dice Duel. Your friend needs this version too.",
        "✍️ Text formatting" to "Like Discord: **bold**, *italic*, __underline__, ~~strike~~ and `code`.",
        "🙈 Spoilers" to "Wrap words in ||double bars|| - they're hidden until tapped. Notifications hide them too.",
        "😍 Big emojis" to "Send just 1-3 emojis and they show up big.",
        "🔄 Update reminders" to "A fresh reminder each time your friend is behind, plus when you each last updated.",
        "📁 Theme folders" to "Themes are now sorted into folders. Settings › Appearance › Themes.",
        "🫧 Soft glass" to "10 new soft liquid glass themes, in their own folder.",
        "🏝 Dynamic Island" to "A pill at the top springs open for calls, typing and when your friend connects or leaves.",
        "✓ Delivered / Read" to "Your last message says Delivered or Read, like iMessage.",
        "📳 Haptic taps" to "A tiny tick when you tap a button.",
        "🧈 Smoother" to "The app is compiled ahead of time when it installs, so scrolling is smooth from the first swipe.",
        "🛡 Emoji safety code" to "Settings › Privacy: compare 8 emojis with your friend in person. If they match, nobody is in the middle.",
        "⚡ Lighter & faster" to "The app is less than half the size, so updates to your friend's watch send twice as fast.",
        "🔢 Versions fixed" to "Your friend's version now always shows correctly - no more \"on V1\" by mistake.",
        "🔋 Battery saver" to "Bluetooth slows down when you're not using the app and searches gently when your friend is away. Messages still arrive instantly.",
        "✨ Tilt shimmer" to "Tilt your wrist and light slides across the glass, while the background drifts behind it.",
        "💧 Tap ripples" to "Every tap sends a ripple through the glass.",
        "🌙 Night glass" to "The glass dims itself from 9 pm to 7 am.",
        "⚙️ Your choice" to "Switch each effect on or off in Settings › Appearance.",
        "✂️ Crop & resize" to "Zoom, move and rotate your profile picture before using it.",
        "🖼 Profile banners" to "Add a wide banner to your profile. Your friend sees it too.",
        "✨ Profile effects" to "Sparkles, glow, rainbow, orbiting stars, snow, flames, petals and more.",
        "📅 Day dividers" to "Today / Yesterday / dates between messages.",
        "↓ Jump to latest" to "One tap back to the newest message.",
        "❤️ Double-tap" to "Double-tap a message to heart it.",
        "⚡ Quick replies" to "Edit your own one-tap replies. Settings › Notifications.",
        "🔠 Text size" to "Make chat text smaller or bigger.",
        "🔕 Mute" to "Mute notifications for 1 h, 8 h or until you unmute.",
        "🔋 Friend's battery" to "Shown in the chat header, with message info when you hold a message.",
        "💎 Liquid glass" to "12 new liquid glass themes, and frosted glass buttons and bubbles on every theme.",
        "🧸 Icon packs" to "10 icon packs with glossy glass tiles. Settings › Appearance.",
        "📱 App icons" to "Pick a glass app icon for your app list.",
        "🧹 Tidier" to "SOS removed.",
        "🔄 GitHub updates" to "Fixed the update check so new versions download on Wi-Fi.",
        "‹ Back button" to "The back key and the ‹ button now go to the previous screen instead of leaving the app.",
        "🔒 Security" to "Tighter checks on updates and shared lists; secret messages removed.",
        "✏️ Draw & send" to "Doodle with your finger and send it as a picture.",
        "💥 Emoji blast" to "Fill your friend's screen with an emoji.",
        "🎮 Games" to "Rock paper scissors, tic-tac-toe and a reaction duel, with scores.",
        "🔥 Streaks" to "Days in a row you've both messaged.",
        "📳 Tap code" to "Send silent vibration patterns.",
        "📡 Range alerts" to "A buzz when your friend is about to leave range.",
        "🌡 Hide & seek" to "Warmer / colder screen that buzzes faster as you get close.",
        "📝 Shared to-do" to "One list on both watches.",
        "😎 Status" to "Show your mood on your friend's watch.",
        "⌚ Tile & complication" to "See your friend from the watch face.",
        "🔄 Updates over Bluetooth" to "Your watch sends new versions to your friend's watch - offline, automatic when they're back in range.",
        "📶 Updates from GitHub" to "Checks for new versions over Wi-Fi. Settings › Updates.",
        "🚶 Walk to your friend" to "Steps, metres, feet, km, miles and time - learned from how you walk.",
        "👋 Nudge" to "Buzz your friend's watch from the chat.",
        "✨ Update reminders" to "If your friend's app is older, the chat tells you what they can't see yet.",
    ) + V2_ITEMS
}

private val V2_ITEMS = listOf(
        "📡 Maximum range" to "Strongest signal + Bluetooth long range. Change in Settings › Connection.",
        "📏 Better distance" to "Fixed readings. Calibrate at 1 m in Settings › Connection.",
        "🔵 Blue ticks" to "Ticks turn blue when your friend has seen your message.",
        "🔴 Intercept alerts" to "Blocked tampered messages show in red.",
        "🎨 Gradient themes" to "8 new gradient backgrounds in Settings › Appearance.",
        "🔤 Fonts & buttons" to "Pick a font, round or square buttons, and gradient buttons.",
        "🔊 Louder calls" to "Tap 🔊 during a call to boost your friend's voice up to 3×. Clearer mic.",
        "〰 Voice waveforms" to "Voice messages show a sound wave.",
        "💬 Reactions & replies" to "Hold a message to react, reply, edit, pin or delete it.",
        "🔍 Search & pins" to "Find old messages and see pinned ones from the chat.",
        "↩ Scroll memory" to "The chat reopens where you left it.",
        "🖼 Photos fix" to "The photo button no longer opens Google Photos.",
    )
