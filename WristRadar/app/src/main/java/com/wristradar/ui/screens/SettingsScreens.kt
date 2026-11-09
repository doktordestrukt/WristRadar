package com.wristradar.ui.screens

import androidx.compose.material.icons.filled.Favorite

import androidx.compose.runtime.CompositionLocalProvider

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.wristradar.BuildConfig
import com.wristradar.R
import com.wristradar.WristRadarApp
import com.wristradar.ble.DistanceEstimator
import com.wristradar.crypto.IdentityManager
import com.wristradar.data.AnimationLevel
import com.wristradar.data.ColorSlot
import com.wristradar.data.DistanceUnit
import com.wristradar.data.ThemePresets
import com.wristradar.data.get
import com.wristradar.data.with
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.KeyValue
import com.wristradar.ui.components.RadarSweep
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.StatusLine
import com.wristradar.ui.components.Swatch
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun WrToggle(label: String, checked: Boolean, secondary: String? = null, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    ToggleChip(
        checked = checked,
        onCheckedChange = onChange,
        label = { Text(label) },
        secondaryLabel = secondary?.let { { Text(it, fontSize = 11.sp) } },
        toggleControl = { Switch(checked = checked, enabled = true) },
        colors = if (com.wristradar.ui.theme.LocalGlass.current.on) ToggleChipDefaults.toggleChipColors(
            checkedStartBackgroundColor = p.primary.copy(alpha = 0.38f), checkedEndBackgroundColor = Color.White.copy(alpha = 0.12f),
            uncheckedStartBackgroundColor = Color.White.copy(alpha = 0.24f), uncheckedEndBackgroundColor = Color.White.copy(alpha = 0.10f),
            checkedToggleControlColor = p.text, checkedContentColor = p.text, uncheckedContentColor = p.text,
        ) else ToggleChipDefaults.toggleChipColors(
            checkedStartBackgroundColor = p.surface, checkedEndBackgroundColor = p.secondary.copy(alpha = 0.35f),
            uncheckedStartBackgroundColor = p.surface, uncheckedEndBackgroundColor = p.surface,
            checkedToggleControlColor = p.primary, checkedContentColor = p.text, uncheckedContentColor = p.text,
        ),
        shape = com.wristradar.ui.theme.LocalButtonStyle.current.shape,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ConfirmDialog(show: Boolean, title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Dialog(showDialog = show, onDismissRequest = onDismiss) {
        Alert(
            title = { Text(title, textAlign = TextAlign.Center, color = p.text) },
            negativeButton = {
                Button(onClick = onDismiss, colors = ButtonDefaults.secondaryButtonColors()) { Icon(Icons.Filled.Close, "Cancel") }
            },
            positiveButton = {
                Button(onClick = onConfirm, colors = ButtonDefaults.primaryButtonColors(backgroundColor = p.danger)) {
                    Icon(Icons.Filled.Check, "Confirm")
                }
            },
        ) { Text(message, textAlign = TextAlign.Center, fontSize = 12.sp, color = p.textDim) }
    }
}

@Composable
fun SettingsScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val s by g.settings.state.collectAsState()
    val partner by g.trust.partner.collectAsState()
    val partnerName by g.profile.partnerName.collectAsState()
    var confirmUnpair by remember { mutableStateOf(false) }
    val themeName = if (s.themeId == ThemePresets.CUSTOM_ID) "Custom" else ThemePresets.byId(s.themeId)?.name ?: "Midnight Purple"

    WrScreen {
        item { SectionLabel("Settings") }
        item {
            val up by g.engine.updater.state.collectAsState()
            WrChip("Updates", { nav.navigate("updates") },
                secondary = up.readyLabel?.let { "$it ready to install" } ?: "${BuildConfig.RELEASE_LABEL} · Bluetooth & GitHub",
                icon = { Icon(Icons.Filled.Refresh, null) }, primary = up.readyLabel != null)
        }
        item { WrChip("Appearance", { nav.navigate("appearance") }, secondary = themeName, icon = { Icon(Icons.Filled.Star, null) }) }
        item { WrChip("Notifications", { nav.navigate("notifications") }, icon = { Icon(Icons.Filled.Notifications, null) }) }
        item { WrChip("Privacy & security", { nav.navigate("privacy") }, icon = { Icon(Icons.Filled.Lock, null) }) }
        item { WrChip("Blackbox security", { nav.navigate("blackbox") }, secondary = "Intrusion alerts & log", icon = { Icon(Icons.Filled.Warning, null) }) }
        item {
            WrChip(
                "Distance units", { g.settings.update { it.copy(unit = if (it.unit == DistanceUnit.FEET) DistanceUnit.METRES else DistanceUnit.FEET) } },
                secondary = if (s.unit == DistanceUnit.FEET) "Feet (tap to switch)" else "Metres (tap to switch)",
                icon = { Icon(Icons.Filled.Place, null) },
            )
        }
        item {
            WrChip("Connection & background", { nav.navigate("connection") }, icon = { Icon(painterResource(R.drawable.ic_bluetooth), null) })
        }
        if (partner != null) item {
            WrChip("Refresh connection", { g.engine.refresh() }, icon = { Icon(Icons.Filled.Refresh, null) })
        }
        item { WrChip("My profile", { nav.navigate("profile") }, icon = { Icon(Icons.Filled.Person, null) }) }
        if (partner != null) item {
            WrChip("Unpair device", { confirmUnpair = true }, secondary = partnerName, danger = true, icon = { Icon(Icons.Filled.Delete, null) })
        }
        item { WrChip("About WristRadar", { nav.navigate("about") }, icon = { Icon(Icons.Filled.Info, null) }) }
        item {
            Text(
                "WristRadar ${BuildConfig.RELEASE_LABEL} · ${BuildConfig.VERSION_NAME}",
                style = WrType.label.copy(fontSize = 8.sp), color = p.textDim,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
            )
        }
    }
    ConfirmDialog(
        confirmUnpair, "Unpair ${partnerName ?: ""}?".trim(),
        "Deletes the trusted keys and this conversation on this watch. You will need to pair again.",
        onConfirm = { confirmUnpair = false; g.engine.unpair(); nav.popBackStack("home", false) },
        onDismiss = { confirmUnpair = false },
    )
}

/** Set by the nav host so settings can open the quick replies editor. */
object QuickRepliesNav { var go: (() -> Unit)? = null }

/** Edit the quick reply buttons shown under the chat. */
@Composable
fun QuickRepliesScreen() {
    val g = WristRadarApp.graph
    val s by g.settings.state.collectAsState()
    val add = com.wristradar.ui.components.rememberTextInput { t: String -> val q = t.trim().take(30); if (q.isNotEmpty()) g.settings.update { it.copy(quickReplies = (it.quickReplies + q).distinct().take(8)) } }
    WrScreen {
        item { SectionLabel("Quick replies") }
        if (s.quickReplies.size < 8) item { WrChip("+ Add a quick reply", { add("Quick reply") }, primary = true) }
        s.quickReplies.forEach { q ->
            item(key = q) { WrChip(q, { g.settings.update { it.copy(quickReplies = it.quickReplies - q) } }, secondary = "Tap to remove") }
        }
        item { Hint("Up to 8. They show as one-tap buttons under the chat.") }
    }
}

@Composable
fun NotificationSettingsScreen() {
    val g = WristRadarApp.graph
    val ctx = LocalContext.current
    val s by g.settings.state.collectAsState()
    WrScreen {
        item { SectionLabel("Notifications") }
        item { WrToggle("Notifications", s.notifications) { v -> g.settings.update { it.copy(notifications = v) } } }
        item { WrToggle("Vibration", s.vibration, "Buzz on new messages") { v -> g.settings.update { it.copy(vibration = v) } } }
        item { WrToggle("Message previews", s.previews, "Show text in notifications") { v -> g.settings.update { it.copy(previews = v) } } }
        item { WrToggle("Sound", s.sound, "Uses the watch's notification sound") { v -> g.settings.update { it.copy(sound = v) } } }
        item { SectionLabel("Mute") }
        if (s.muted) item {
            val until = if (s.mutedUntil == Long.MAX_VALUE) "until you unmute" else "until " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.mutedUntil))
            WrChip("🔕 Unmute", { g.settings.update { it.copy(mutedUntil = 0) } }, secondary = "Muted $until", primary = true)
        } else {
            item { WrChip("Mute for 1 hour", { g.settings.update { it.copy(mutedUntil = System.currentTimeMillis() + 3_600_000) } }) }
            item { WrChip("Mute for 8 hours", { g.settings.update { it.copy(mutedUntil = System.currentTimeMillis() + 8 * 3_600_000L) } }) }
            item { WrChip("Mute until I unmute", { g.settings.update { it.copy(mutedUntil = Long.MAX_VALUE) } }) }
        }
        item { SectionLabel("Chat") }
        item {
            val sizes = com.wristradar.data.TextSize.entries
            WrChip("Message text size", { g.settings.update { it.copy(chatText = sizes[(sizes.indexOf(it.chatText) + 1) % sizes.size]) } },
                secondary = s.chatText.label + " (tap to change)")
        }
        item { WrChip("Quick replies", { QuickRepliesNav.go?.invoke() }, secondary = s.quickReplies.joinToString(" · ")) }
        item {
            WrChip("System notification settings", {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
        }
        item { Hint("Do Not Disturb and system settings can still silence WristRadar.") }
    }
}

@Composable
fun PrivacyScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val s by g.settings.state.collectAsState()
    val partner by g.trust.partner.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    WrScreen {
        item { SectionLabel("Privacy & security") }
        item {
            WrChip("App lock PIN", { nav.navigate("pinsettings") },
                secondary = if (g.lock.isSet) "On" else "Off - tap to set one", primary = !g.lock.isSet, icon = { Icon(Icons.Filled.Lock, null) })
        }
        item { WrToggle("Message previews", s.previews, "In notifications") { v -> g.settings.update { it.copy(previews = v) } } }
        partner?.let { pt ->
            item {
                val ok = s.safetyVerified == com.wristradar.crypto.SafetyCode.fingerprint(pt.identityKey)
                WrChip("Emoji safety code", { nav.navigate("safety") }, secondary = if (ok) "Verified ✓" else "Check it with your friend", primary = !ok, icon = { Icon(Icons.Filled.Lock, null) })
            }
        }
        item { KeyValue("Your fingerprint", g.identity.fingerprint()) }
        partner?.let { pt -> item { KeyValue("Partner fingerprint", IdentityManager.fingerprintOf(pt.identityKey)) } }
        item {
            val su = g.engine.sessionSuite
            KeyValue("Encryption", "AES-256-GCM per message. Keys: X25519 + ML-KEM-1024 (post-quantum). Identity: Ed25519 + ML-DSA-87 (post-quantum). Hashing: SHA-384 / HKDF-SHA-384. Fresh keys every session." +
                (if (su == null) "" else "\nThis connection: ${su.title}"))
        }
        item {
            KeyValue("Stored on watch", "Keys and messages encrypted with an Android Keystore key. Nothing leaves the watch except to your partner.")
        }
        item {
            KeyValue("Limits", "Nearby people can see your username and avatar while you are on the Nearby screen. Bluetooth activity itself is visible to radio scanners. No system is unhackable.")
        }
        item { WrChip("Clear conversation history", { confirmClear = true }, danger = true, icon = { Icon(Icons.Filled.Delete, null) }) }
    }
    ConfirmDialog(
        confirmClear, "Clear history?", "Deletes all messages and pictures on this watch only.",
        onConfirm = { confirmClear = false; scope.launch { g.messages.clearAll() } },
        onDismiss = { confirmClear = false },
    )
}

@Composable
fun ConnectionInfoScreen() {
    val g = WristRadarApp.graph
    val conn by g.engine.connection.collectAsState()
    val s by g.settings.state.collectAsState()
    val last by g.trust.lastConnected.collectAsState()
    val partner by g.trust.partner.collectAsState()
    var calNote by remember { mutableStateOf<String?>(null) }
    WrScreen {
        item { SectionLabel("Connection") }
        item { StatusLine(conn.phase) }
        item {
            val next = when (s.range) {
                com.wristradar.data.RangeMode.MAX -> com.wristradar.data.RangeMode.BALANCED
                com.wristradar.data.RangeMode.BALANCED -> com.wristradar.data.RangeMode.STEALTH
                com.wristradar.data.RangeMode.STEALTH -> com.wristradar.data.RangeMode.MAX
            }
            WrChip(
                "Range: " + when (s.range) {
                    com.wristradar.data.RangeMode.MAX -> "Maximum"
                    com.wristradar.data.RangeMode.BALANCED -> "Balanced"
                    com.wristradar.data.RangeMode.STEALTH -> "Stealth"
                },
                { g.settings.update { it.copy(range = next) } },
                secondary = when (s.range) {
                    com.wristradar.data.RangeMode.MAX -> if (g.engine.longRangeSupported) "Max power + Bluetooth 5 long range" else "Max power (long range not supported here)"
                    com.wristradar.data.RangeMode.BALANCED -> "Medium power, normal battery"
                    com.wristradar.data.RangeMode.STEALTH -> "Lowest power, hardest to detect"
                },
                primary = s.range == com.wristradar.data.RangeMode.MAX,
            )
        }
        if (partner != null) item {
            WrChip(
                "Calibrate distance",
                { calNote = g.engine.calibrateDistance()?.let { "Saved: $it dBm = 1 m" } ?: "Connect to your partner first" },
                secondary = calNote ?: if (s.rssiAt1m != null) "Calibrated (${s.rssiAt1m} dBm at 1 m)" else "Stand ~1 m apart, then tap",
            )
        }
        if (s.rssiAt1m != null) item {
            WrChip("Reset calibration", { g.settings.update { it.copy(rssiAt1m = null) }; calNote = null })
        }
        if (conn.detail.isNotBlank()) item { Hint(conn.detail) }
        item { KeyValue("Signal strength", conn.rssi?.let { "$it dBm · ${DistanceEstimator.signalLabel(it)}" } ?: "--") }
        item {
            KeyValue(
                "Estimated distance",
                "${DistanceEstimator.format(conn.distanceM, DistanceUnit.FEET)}  /  ${DistanceEstimator.format(conn.distanceM, DistanceUnit.METRES)}",
            )
        }
        item {
            KeyValue("Last successful connection", if (last > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last)) else "Never")
        }
        item { KeyValue("This watch battery", g.battery.percent()?.let { "$it%" } ?: "--") }
        if (partner != null) item { KeyValue("Partner battery", conn.remoteBattery?.let { "$it% (sent encrypted)" } ?: "Not reported yet") }
        if (partner != null) item { WrChip("Refresh connection", { g.engine.refresh() }, primary = true, icon = { Icon(Icons.Filled.Refresh, null) }) }
        item {
            KeyValue(
                "Background service",
                "While paired, WristRadar runs a foreground service so messages arrive with the screen off. Wear OS battery saver or low memory can still pause it; it restarts automatically.",
            )
        }
        item {
            Hint("Distance comes from Bluetooth signal strength. Walls, bodies, wrist position and interference change it a lot - treat it as a rough guide, not GPS.")
        }
        item { Hint("Units: ${if (s.unit == DistanceUnit.FEET) "feet" else "metres"} (change in Settings)") }
    }
}

@Composable
fun AboutScreen() {
    val p = LocalPalette.current
    WrScreen {
        item { RadarSweep(56.dp) }
        item { Text("WRISTRADAR", style = WrType.title, color = p.primary) }
        item { Text("Version ${BuildConfig.RELEASE_LABEL} (${BuildConfig.VERSION_NAME})", fontSize = 11.sp, color = p.textDim) }
        item {
            Hint("A private, offline messenger for two watches. Talks directly over Bluetooth Low Energy - no internet, phone, SIM, account or server.")
        }
        item { SectionLabel("Privacy") }
        item {
            Hint("Messages, pictures and profile updates are end-to-end encrypted between you and one verified partner. Nothing is collected or uploaded. Unpairing deletes the keys.")
        }
        item { Hint("Security details: see SECURITY.md in the project.") }
    }
}

// --------------------------------------------------------------------- appearance

@Composable
fun AppearanceScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val s by g.settings.state.collectAsState()
    WrScreen {
        item { SectionLabel("Appearance") }
        item { SectionLabel("Themes") }
        ThemeFolder.entries.forEach { f ->
            item(key = "folder-" + f.name) {
                val list = f.themes()
                val active = list.any { it.id == s.themeId }
                WrChip(
                    "📁 " + f.title, { nav.navigate("themes/" + f.name) },
                    secondary = if (active) "${list.size} themes · current theme is here" else "${list.size} themes",
                    primary = active,
                    icon = { list.firstOrNull()?.gradient?.let { gr -> Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(gr.map { Color(it) }))) } },
                    tile = false,
                )
            }
        }
        item {
            WrChip(
                "Custom theme", { g.settings.update { it.copy(themeId = ThemePresets.CUSTOM_ID) }; nav.navigate("custom") },
                secondary = if (s.themeId == ThemePresets.CUSTOM_ID) "Active · edit colours" else "Pick your own colours",
                icon = { Swatch(Color(s.custom.primary), 18.dp, selected = s.themeId == ThemePresets.CUSTOM_ID) },
            )
        }
        item { SectionLabel("Effects") }
        item { WrToggle("Liquid glass effect", s.liquidGlass, "Frosted glass buttons and bubbles on every theme") { v -> g.settings.update { it.copy(liquidGlass = v) } } }
        if (s.liquidGlass) {
            item { WrToggle("Tilt shimmer", s.glassMotion, "Light slides across the glass as you tilt your wrist") { v -> g.settings.update { it.copy(glassMotion = v) } } }
            item { WrToggle("Tap ripples", s.glassRipple, "Taps send a ripple through the glass") { v -> g.settings.update { it.copy(glassRipple = v) } } }
            item { WrToggle("Night glass", s.nightTint, "Glass dims itself from 9 pm to 7 am") { v -> g.settings.update { it.copy(nightTint = v) } } }
        }
        item { SectionLabel("Icon pack") }
        item { IconPackRow(s.iconPack) { pack -> g.settings.update { it.copy(iconPack = pack) } } }
        item { SectionLabel("App icon") }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val icons = com.wristradar.data.AppIcon.entries
            WrChip("App icon", {
                val next = icons[(icons.indexOf(s.appIcon) + 1) % icons.size]
                if (AppIconSwitcher.set(ctx, next)) g.settings.update { it.copy(appIcon = next) }
            }, secondary = s.appIcon.label + " (tap to change)")
        }
        item { Hint("\"Matches your theme\" changes the icon whenever you pick a theme. It can take a moment to update in the app list.") }
        item { SectionLabel("Style") }
        item {
            val fonts = com.wristradar.data.FontChoice.entries
            WrChip("Font", { g.settings.update { it.copy(font = fonts[(fonts.indexOf(it.font) + 1) % fonts.size]) } },
                secondary = s.font.label + " (tap to change)")
        }
        item {
            val shapes = com.wristradar.data.ButtonShape.entries
            WrChip("Button shape", { g.settings.update { it.copy(buttonShape = shapes[(shapes.indexOf(it.buttonShape) + 1) % shapes.size]) } },
                secondary = s.buttonShape.label + " (tap to change)")
        }
        item { WrToggle("Gradient buttons", s.buttonGradient, "Colour fade on every button") { v -> g.settings.update { it.copy(buttonGradient = v) } } }
        item { WrToggle("Dynamic Island", s.dynamicIsland, "Pill at the top for calls, typing and connections") { v -> g.settings.update { it.copy(dynamicIsland = v) } } }
        item { WrToggle("Haptic taps", s.haptics, "A tiny tick when you tap a button") { v -> g.settings.update { it.copy(haptics = v) } } }
        item { WrToggle("Away mode", s.awayMode, "When your friend is out of reach, home shows only the chat") { v -> g.settings.update { it.copy(awayMode = v) } } }
        item {
            val next = when (s.animations) { AnimationLevel.FULL -> AnimationLevel.REDUCED; AnimationLevel.REDUCED -> AnimationLevel.OFF; AnimationLevel.OFF -> AnimationLevel.FULL }
            WrChip("Animations", { g.settings.update { it.copy(animations = next) } }, secondary = s.animations.name.lowercase().replaceFirstChar { it.uppercase() } + " (tap to change)")
        }
        item { SectionLabel("Interface brightness") }
        item {
            InlineSlider(
                value = s.brightness,
                onValueChange = { v -> g.settings.update { it.copy(brightness = v) } },
                steps = 5,
                valueRange = 0.4f..1f,
                decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "Dimmer") },
                increaseIcon = { Icon(InlineSliderDefaults.Increase, "Brighter") },
                segmented = true,
            )
        }
        item { Hint("Dims WristRadar only. Black backgrounds save the most battery on AMOLED.") }
    }
}

/** Swaps the launcher icon by enabling exactly one activity-alias. */
object AppIconSwitcher {
    private fun allAliases() = com.wristradar.data.AppIcon.entries.filter { it.alias.isNotEmpty() }.map { it.alias } +
        com.wristradar.ui.theme.ThemeIcons.ids.map { com.wristradar.ui.theme.ThemeIcons.alias(it) }

    /** Alias that should be on for this icon choice + theme. */
    fun aliasFor(icon: com.wristradar.data.AppIcon, themeId: String): String =
        if (icon != com.wristradar.data.AppIcon.MATCH) icon.alias
        else if (themeId in com.wristradar.ui.theme.ThemeIcons.ids) com.wristradar.ui.theme.ThemeIcons.alias(themeId)
        else com.wristradar.data.AppIcon.CLASSIC.alias

    /** Turns on exactly one launcher alias; only touches components whose state actually changes. */
    fun apply(ctx: android.content.Context, alias: String): Boolean = runCatching {
        val pm = ctx.packageManager
        val on = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val off = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        fun cn(a: String) = android.content.ComponentName(ctx.packageName, ctx.packageName + a)
        // Enable the new one first so there is never a moment with no launcher icon.
        if (pm.getComponentEnabledSetting(cn(alias)) != on) pm.setComponentEnabledSetting(cn(alias), on, android.content.pm.PackageManager.DONT_KILL_APP)
        for (a in allAliases()) if (a != alias) {
            val st = pm.getComponentEnabledSetting(cn(a))
            // DEFAULT counts as on for the Classic alias (enabled in the manifest).
            val isOn = st == on || (st == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && a == com.wristradar.data.AppIcon.CLASSIC.alias)
            if (isOn) pm.setComponentEnabledSetting(cn(a), off, android.content.pm.PackageManager.DONT_KILL_APP)
        }
        true
    }.getOrDefault(false)

    fun set(ctx: android.content.Context, icon: com.wristradar.data.AppIcon): Boolean =
        apply(ctx, aliasFor(icon, com.wristradar.WristRadarApp.graph.settings.current.themeId))
}

/** Preview of every icon pack: tap one to use it. */
@Composable
private fun IconPackRow(current: com.wristradar.data.IconPack, onPick: (com.wristradar.data.IconPack) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        com.wristradar.data.IconPack.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { pack ->
                    CompositionLocalProvider(com.wristradar.ui.theme.LocalGlass provides com.wristradar.ui.theme.GlassStyle(true, pack)) {
                        Box(
                            Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .border(if (pack == current) 2.dp else 0.dp, if (pack == current) p.text else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable { onPick(pack) }.padding(3.dp)
                        ) {
                            com.wristradar.ui.theme.IconTile(30.dp) { Icon(Icons.Filled.Favorite, pack.label, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
        Text(current.label, fontSize = 11.sp, color = p.textDim)
    }
}

/** Theme folders on the Appearance screen. */
enum class ThemeFolder(val title: String) {
    GLASS("Liquid glass"), GRADIENT_GLASS("Gradient glass"), SOFT("Soft glass"), GRADIENT("Gradients"), DARK("Dark");
    fun themes() = ThemePresets.all.filter {
        when (this) {
            GLASS -> it.glass && !it.id.startsWith("soft_") && !it.id.startsWith("gg_")
            GRADIENT_GLASS -> it.glass && it.id.startsWith("gg_")
            SOFT -> it.glass && it.id.startsWith("soft_")
            GRADIENT -> it.gradient != null && !it.glass
            DARK -> it.gradient == null
        }
    }
}

@Composable
fun ThemeFolderScreen(folder: String) {
    val g = WristRadarApp.graph
    val s by g.settings.state.collectAsState()
    val f = ThemeFolder.entries.firstOrNull { it.name == folder } ?: ThemeFolder.GLASS
    WrScreen {
        item { SectionLabel(f.title) }
        f.themes().forEach { preset -> item(key = preset.id) { ThemeChip(preset, s.themeId == preset.id) { g.settings.update { it.copy(themeId = preset.id) } } } }
    }
}

@Composable
private fun ThemeChip(preset: com.wristradar.data.ThemePreset, active: Boolean, onClick: () -> Unit) {
    WrChip(
        preset.name, onClick,
        secondary = if (active) "Active" else null,
        icon = {
            val grad = preset.gradient
            if (grad != null) {
                Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(grad.map { Color(it) }))
                    .border(if (active) 2.dp else 1.dp, if (active) Color.White else Color.White.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape))
            } else Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                Swatch(Color(preset.colors.background), 14.dp)
                Swatch(Color(preset.colors.primary), 14.dp, selected = active)
                Swatch(Color(preset.colors.secondary), 14.dp)
            }
        },
    )
}

@Composable
fun CustomThemeScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val s by g.settings.state.collectAsState()
    WrScreen {
        item { SectionLabel("Custom theme") }
        ColorSlot.entries.forEach { slot ->
            item(key = slot.name) {
                WrChip(slot.label, { nav.navigate("color/${slot.name}") }, icon = { Swatch(Color(s.custom.get(slot)), 22.dp) })
            }
        }
        item {
            WrChip("Copy from a preset", {
                g.settings.update {
                    val base = (ThemePresets.byId(it.themeId) ?: ThemePresets.default).colors
                    it.copy(custom = base, themeId = ThemePresets.CUSTOM_ID)
                }
            }, secondary = "Starts from the last preset you used")
        }
    }
}

private val PALETTE = listOf(
    0xFFB266FF, 0xFF7A3DCC, 0xFF39FF14, 0xFF00B3FF, 0xFFFF3B4E, 0xFFFFB000,
    0xFF00E5FF, 0xFF2E86FF, 0xFFFF4FD8, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF16121C,
    0xFF000000, 0xFF0A1A2E, 0xFF1E1A26, 0xFF4A2380,
).map { it.toInt() }

@Composable
fun ColorPickerScreen(nav: NavHostController, slot: ColorSlot) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(g.settings.current.custom.get(slot), it) } }
    var hue by remember { mutableStateOf(initial[0]) }
    var sat by remember { mutableStateOf(initial[1]) }
    var value by remember { mutableStateOf(initial[2]) }
    val color = Color.hsv(hue.coerceIn(0f, 359.9f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    fun setFrom(argb: Int) {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(argb, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }
    WrScreen {
        item { SectionLabel(slot.label) }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Swatch(color, 44.dp, selected = true)
                Text("#%06X".format(color.toArgb() and 0xFFFFFF), style = WrType.label, color = p.textDim)
            }
        }
        item { Text("Hue", style = WrType.label, color = p.textDim) }
        item {
            InlineSlider(value = hue, onValueChange = { hue = it }, steps = 22, valueRange = 0f..345f,
                decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "Less") }, increaseIcon = { Icon(InlineSliderDefaults.Increase, "More") })
        }
        item { Text("Saturation", style = WrType.label, color = p.textDim) }
        item {
            InlineSlider(value = sat, onValueChange = { sat = it }, steps = 9, valueRange = 0f..1f,
                decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "Less") }, increaseIcon = { Icon(InlineSliderDefaults.Increase, "More") })
        }
        item { Text("Brightness", style = WrType.label, color = p.textDim) }
        item {
            InlineSlider(value = value, onValueChange = { value = it }, steps = 9, valueRange = 0f..1f,
                decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "Less") }, increaseIcon = { Icon(InlineSliderDefaults.Increase, "More") })
        }
        PALETTE.chunked(4).forEachIndexed { i, row ->
            item(key = "pal$i") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c -> Box(Modifier.clickable { setFrom(c) }) { Swatch(Color(c), 26.dp) } }
                }
            }
        }
        if (slot == ColorSlot.BACKGROUND && color.luminance() > 0.25f) item {
            Hint("Bright backgrounds are harder to read and use more battery on AMOLED screens.")
        }
        item {
            WrChip("Save colour", {
                g.settings.update { it.copy(themeId = ThemePresets.CUSTOM_ID, custom = it.custom.with(slot, color.toArgb())) }
                nav.popBackStack()
            }, primary = true)
        }
    }
}

// --------------------------------------------------------------------- blackbox

@Composable
fun BlackboxSettingsScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val s by g.settings.state.collectAsState()
    val log by g.blackbox.log.collectAsState(initial = emptyList())
    WrScreen {
        item { SectionLabel("Blackbox security") }
        item {
            Hint("On real evidence of a break-in (failed authentication, tampered or replayed messages, altered pairing data) both of you get a warning with instructions. The device that tried to break in gets the skull screen and hacker music.")
        }
        item { WrToggle("Intruder screen sound", s.blackboxSound, "Glitch effects if this watch is ever refused") { v -> g.settings.update { it.copy(blackboxSound = v) } } }
        item { WrToggle("Intruder screen music", s.blackboxMusic, "Loops on the skull screen") { v -> g.settings.update { it.copy(blackboxMusic = v) } } }
        item { WrChip("Preview intruder screen", { g.blackbox.preview() }, secondary = "See and hear what someone gets when your watch refuses them", icon = { Text("💀", fontSize = 16.sp) }) }
        item { WrToggle("Warning vibration", s.blackboxVibration) { v -> g.settings.update { it.copy(blackboxVibration = v) } } }
        item { WrChip("Security log", { nav.navigate("securitylog") }, secondary = "${log.size} event(s) on this watch", icon = { Icon(Icons.Filled.Lock, null) }) }
    }
}

@Composable
fun SecurityLogScreen() {
    val g = WristRadarApp.graph
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val log by g.blackbox.log.collectAsState(initial = emptyList())
    var confirm by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (g.settings.current.blackboxSound) com.wristradar.ui.blackbox.BlackboxAudio.glitch1(ctx)
    }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    WrScreen {
        item { Text("SECURITY LOG", style = WrType.title.copy(fontSize = 12.sp), color = p.danger) }
        if (log.isEmpty()) item { Hint("No unauthorised access detected.") }
        log.forEach { e ->
            item(key = e.id) {
                val title = runCatching { com.wristradar.security.ThreatType.valueOf(e.type).title }.getOrDefault(e.type)
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(fmt.format(Date(e.time)), style = WrType.label, color = p.textDim)
                    Text(title, fontSize = 12.sp, color = p.text)
                    Text("${e.device}${e.deviceId?.let { " · $it" } ?: ""}", fontSize = 10.sp, color = p.primary)
                }
            }
        }
        if (log.isNotEmpty()) item { WrChip("Clear security log", { confirm = true }, danger = true, icon = { Icon(Icons.Filled.Delete, null) }) }
        item { Hint("Stored only on this watch. Never contains messages or keys.") }
    }
    ConfirmDialog(confirm, "Clear log?", "Deletes all recorded security events on this watch.",
        onConfirm = { confirm = false; g.blackbox.clearLog() }, onDismiss = { confirm = false })
}
