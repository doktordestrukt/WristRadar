package com.wristradar.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DistanceUnit { FEET, METRES }
enum class AnimationLevel { FULL, REDUCED, OFF }

/** Radio strategy. MAX = strongest power + Bluetooth 5 long range; STEALTH = lowest power. */
enum class RangeMode { MAX, BALANCED, STEALTH }

/** Interface font (system families - nothing extra bundled in the APK). */
enum class FontChoice(val label: String) { DEFAULT("Clean"), ROUNDED("Rounded"), SERIF("Classic serif"), MONO("Terminal mono"), CURSIVE("Handwritten"), CONDENSED("Condensed") }

/**
 * Icon pack: how icons are drawn. Glass packs put each icon on a glossy rounded tile
 * (original artwork, inspired by liquid-glass home screens). MATCH follows the theme.
 */
enum class IconPack(val label: String, val tile: List<Long>?) {
    MATCH("Match theme (glass)", null),
    ROSE("Rose glass", listOf(0xFFF3C6CF, 0xFFD48A9B, 0xFFA85A6E)),
    MIDNIGHT("Midnight glass", listOf(0xFF2B3A78, 0xFF16205A, 0xFF070B2A)),
    AQUA("Aqua glass", listOf(0xFFCFF5EF, 0xFF7FD3D0, 0xFF4A9FD8)),
    LILAC("Lilac glass", listOf(0xFFEDE2FF, 0xFFC0A8F5, 0xFF8E6FDA)),
    CANDY("Candy", listOf(0xFFFF9AD5, 0xFFB57BFF, 0xFFFFB36B)),
    PEACH("Peach glass", listOf(0xFFFFE3CF, 0xFFF9AE90, 0xFFE27D6C)),
    MINT("Mint glass", listOf(0xFFDDFBEA, 0xFF9EE6C8, 0xFF4FC79C)),
    MONO("Graphite glass", listOf(0xFF5A5A66, 0xFF2E2E36, 0xFF121216)),
    CLASSIC("Classic (no tiles)", null);
}

/** Home-screen app icon (launcher alias). */
enum class AppIcon(val label: String, val alias: String) {
    CLASSIC("Classic", ".ui.LauncherClassic"),
    ROSE("Rose glass", ".ui.LauncherRose"),
    MIDNIGHT("Midnight glass", ".ui.LauncherMidnight"),
    AQUA("Aqua glass", ".ui.LauncherAqua"),
    LILAC("Lilac glass", ".ui.LauncherLilac"),
}

enum class TextSize(val label: String, val sp: Float) { SMALL("Small", 12f), NORMAL("Normal", 14f), LARGE("Large", 16f), HUGE("Extra large", 18f) }

enum class ButtonShape(val label: String) { ROUND("Round"), SOFT("Soft square"), SQUARE("Square") }

/** All colours are ARGB ints. */
data class ThemeColors(
    val primary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val text: Int,
    val incoming: Int,
    val outgoing: Int,
    val connection: Int,
)

enum class ColorSlot(val label: String) {
    PRIMARY("Primary accent"), SECONDARY("Secondary accent"), BACKGROUND("Background"),
    SURFACE("Surfaces"), TEXT("Text"), INCOMING("Incoming bubble"), OUTGOING("Outgoing bubble"),
    CONNECTION("Connection light");
}

fun ThemeColors.get(slot: ColorSlot): Int = when (slot) {
    ColorSlot.PRIMARY -> primary; ColorSlot.SECONDARY -> secondary; ColorSlot.BACKGROUND -> background
    ColorSlot.SURFACE -> surface; ColorSlot.TEXT -> text; ColorSlot.INCOMING -> incoming
    ColorSlot.OUTGOING -> outgoing; ColorSlot.CONNECTION -> connection
}

fun ThemeColors.with(slot: ColorSlot, v: Int): ThemeColors = when (slot) {
    ColorSlot.PRIMARY -> copy(primary = v); ColorSlot.SECONDARY -> copy(secondary = v)
    ColorSlot.BACKGROUND -> copy(background = v); ColorSlot.SURFACE -> copy(surface = v)
    ColorSlot.TEXT -> copy(text = v); ColorSlot.INCOMING -> copy(incoming = v)
    ColorSlot.OUTGOING -> copy(outgoing = v); ColorSlot.CONNECTION -> copy(connection = v)
}

/** gradient = background colours top-left to bottom-right (null = solid background). */
data class ThemePreset(val id: String, val name: String, val colors: ThemeColors, val gradient: List<Int>? = null, val glass: Boolean = false)

object ThemePresets {
    private fun c(v: Long) = v.toInt()
    private fun glass(id: String, name: String, text: Long, primary: Long, secondary: Long, out: Long, conn: Long, vararg bg: Long) =
        ThemePreset(id, name, ThemeColors(c(primary), c(secondary), c(bg.last()), c(0x33FFFFFF), c(text), c(0x38FFFFFF), c(out), c(conn)), bg.map { c(it) }, glass = true)

    val all = listOf(
        // ---- liquid glass (default family)
        glass("glass_rose", "Rose Glass", 0xFFFFFFFF, 0xFFFFE3EA, 0xFFF7A8B8, 0x8CB0606F, 0xFF7CFFC4, 0xFFF0C4CC, 0xFFCB8C9A, 0xFF9C5869),
        glass("glass_midnight", "Midnight Glass", 0xFFFFFFFF, 0xFF8FB8FF, 0xFF5C7CFF, 0x8C2C4BB8, 0xFF5CFFB0, 0xFF02061F, 0xFF0D1F66, 0xFF1C3FA6),
        glass("glass_aqua", "Aqua Glass", 0xFF06303A, 0xFF0B6E8A, 0xFF2E9BC9, 0x8C2F8FB0, 0xFF0E8C5A, 0xFFC9F2EA, 0xFF79CCCB, 0xFF3F8ED2),
        glass("glass_lilac", "Lilac Glass", 0xFFFFFFFF, 0xFFF3EAFF, 0xFFC9B5FF, 0x8C7B5CC9, 0xFF7CFFC4, 0xFFE4D8FF, 0xFFB29CEE, 0xFF7E61CF),
        glass("glass_peach", "Peach Glass", 0xFFFFFFFF, 0xFFFFF0E6, 0xFFFFC2A6, 0x8CC8634F, 0xFF6CFFB8, 0xFFFFDCC7, 0xFFF4A48A, 0xFFD9705F),
        glass("glass_ice", "Ice Glass", 0xFF0F2740, 0xFF2563A8, 0xFF5A9AE0, 0x8C4F86C4, 0xFF138A57, 0xFFF4F9FF, 0xFFCFE2F6, 0xFF9DC0E8),
        glass("glass_emerald", "Emerald Glass", 0xFFFFFFFF, 0xFFB9FFE3, 0xFF4FE3A8, 0x8C138A63, 0xFFB9FFE3, 0xFF02150F, 0xFF0A5A42, 0xFF2CB386),
        glass("glass_sunset", "Sunset Glass", 0xFFFFFFFF, 0xFFFFD6B0, 0xFFFF8FB1, 0x8CB43A6E, 0xFF7CFFC4, 0xFF27093F, 0xFFA8306A, 0xFFFF8E57),
        glass("glass_graphite", "Graphite Glass", 0xFFFFFFFF, 0xFFE6E6F0, 0xFF9C9CB0, 0x8C4A4A5A, 0xFF7CFFC4, 0xFF09090B, 0xFF26262E, 0xFF4A4A57),
        glass("glass_cherry", "Cherry Glass", 0xFFFFFFFF, 0xFFFFC2CB, 0xFFFF6B81, 0x8C9E1430, 0xFF7CFFC4, 0xFF1A0006, 0xFF6E0A1E, 0xFFC7213F),
        glass("glass_ocean", "Ocean Glass", 0xFFFFFFFF, 0xFFBFF4FF, 0xFF4FD1FF, 0x8C0E6A9E, 0xFF7CFFC4, 0xFF001A2E, 0xFF045A8D, 0xFF1AA6C9),
        glass("glass_gold", "Champagne Glass", 0xFF3A2A10, 0xFF8A6214, 0xFFC99A3E, 0x8CB78A3A, 0xFF16865A, 0xFFFFF4DF, 0xFFEFD3A0, 0xFFC9A061),
        ThemePreset("midnight", "Midnight Purple", ThemeColors(c(0xFFB266FF), c(0xFF7A3DCC), c(0xFF000000), c(0xFF16121C), c(0xFFEDE7F6), c(0xFF1E1A26), c(0xFF4A2380), c(0xFF39FF88))),
        ThemePreset("neon", "Neon Green", ThemeColors(c(0xFF39FF14), c(0xFF1FAA59), c(0xFF000000), c(0xFF0F1A10), c(0xFFE8FFE8), c(0xFF132015), c(0xFF1E5C1A), c(0xFF39FF14))),
        ThemePreset("cyber", "Cyber Blue", ThemeColors(c(0xFF00B3FF), c(0xFF3D5AFE), c(0xFF000000), c(0xFF0D1520), c(0xFFE3F2FD), c(0xFF111C2A), c(0xFF0D3C73), c(0xFF00E5FF))),
        ThemePreset("crimson", "Crimson Red", ThemeColors(c(0xFFFF3B4E), c(0xFFB0102A), c(0xFF000000), c(0xFF1C0D10), c(0xFFFFE9EC), c(0xFF23111A), c(0xFF6E0F20), c(0xFF4CFF8F))),
        ThemePreset("mono", "Monochrome", ThemeColors(c(0xFFFFFFFF), c(0xFF9E9E9E), c(0xFF000000), c(0xFF161616), c(0xFFF5F5F5), c(0xFF1E1E1E), c(0xFF3A3A3A), c(0xFFFFFFFF))),
        ThemePreset("amber", "Amber Terminal", ThemeColors(c(0xFFFFB000), c(0xFFCC7A00), c(0xFF000000), c(0xFF1A1405), c(0xFFFFE7B0), c(0xFF1E180A), c(0xFF4D3500), c(0xFFFFB000))),
        ThemePreset("arctic", "Arctic Cyan", ThemeColors(c(0xFF00E5FF), c(0xFF80DEEA), c(0xFF000000), c(0xFF0A1A1C), c(0xFFE0FFFF), c(0xFF0F2224), c(0xFF0E4A52), c(0xFF7CFFCB))),
        ThemePreset("ocean", "Deep Ocean", ThemeColors(c(0xFF2E86FF), c(0xFF00BFA5), c(0xFF000814), c(0xFF0A1A2E), c(0xFFD6E9FF), c(0xFF0E2238), c(0xFF0B3D6E), c(0xFF00E5B0))),
        // ---- gradient themes
        ThemePreset("g_mint", "Mint Breeze", ThemeColors(c(0xFF0E8C6A), c(0xFF4DB6AC), c(0xFFB9F3DC), c(0x55FFFFFF), c(0xFF0B2E25), c(0x66FFFFFF), c(0xFF0E8C6A), c(0xFF0E8C6A)), listOf(c(0xFFD9FBEA), c(0xFF9EE6C8), c(0xFF6FD3B0))),
        ThemePreset("g_peach", "Peach Sunset", ThemeColors(c(0xFFE0603A), c(0xFFF2A65A), c(0xFFFFD3B6), c(0x55FFFFFF), c(0xFF3A1A0C), c(0x66FFFFFF), c(0xFFE0603A), c(0xFF2E9E5B)), listOf(c(0xFFFFE8D6), c(0xFFFFC3A0), c(0xFFF79A7A))),
        ThemePreset("g_lavender", "Lavender Dream", ThemeColors(c(0xFF7C4DFF), c(0xFFB39DDB), c(0xFFE3D7FF), c(0x55FFFFFF), c(0xFF22163F), c(0x66FFFFFF), c(0xFF7C4DFF), c(0xFF2E9E5B)), listOf(c(0xFFF1EAFF), c(0xFFD7C6FF), c(0xFFB9A2F5))),
        ThemePreset("g_aurora", "Purple Blaze", ThemeColors(c(0xFFFFB347), c(0xFFFF6A88), c(0xFF3B1060), c(0x33FFFFFF), c(0xFFFFFFFF), c(0x40FFFFFF), c(0xCCFF6A88), c(0xFF5CFFB0)), listOf(c(0xFF3B1060), c(0xFF8E2DE2), c(0xFFFF6A3D))),
        ThemePreset("g_galaxy", "Blue Galaxy", ThemeColors(c(0xFF8FD3FF), c(0xFFB388FF), c(0xFF1A1F5C), c(0x33FFFFFF), c(0xFFFFFFFF), c(0x40FFFFFF), c(0xCC6A5CFF), c(0xFF5CFFB0)), listOf(c(0xFF0F1B4D), c(0xFF3A47C9), c(0xFF8E4FD8))),
        ThemePreset("g_tropic", "Teal Candy", ThemeColors(c(0xFFFFFFFF), c(0xFFFF8AD8), c(0xFF0F7C86), c(0x33FFFFFF), c(0xFFFFFFFF), c(0x40FFFFFF), c(0xCCFF5FA8), c(0xFFFFF176)), listOf(c(0xFF0E8C94), c(0xFF5B7FD6), c(0xFFF06BB5))),
        ThemePreset("g_blood", "Blood Moon", ThemeColors(c(0xFFFF4D5E), c(0xFFB0102A), c(0xFF000000), c(0x33FF4D5E), c(0xFFFFEDEE), c(0x40FFFFFF), c(0xCC9E1428), c(0xFF4CFF8F)), listOf(c(0xFF000000), c(0xFF3A0610), c(0xFFA3122A))),
        ThemePreset("g_navy", "Midnight Navy", ThemeColors(c(0xFF64B5F6), c(0xFF26C6DA), c(0xFF020B1F), c(0x3364B5F6), c(0xFFE3F2FD), c(0x40FFFFFF), c(0xCC1E4E8C), c(0xFF5CFFB0)), listOf(c(0xFF01040F), c(0xFF0A1E44), c(0xFF17407A))),
    )
    const val CUSTOM_ID = "custom"
    val default = all.first()   // Rose Glass
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

data class Settings(
    val notifications: Boolean = true,
    val vibration: Boolean = true,
    val previews: Boolean = true,
    val sound: Boolean = false,
    val unit: DistanceUnit = DistanceUnit.FEET,
    val animations: AnimationLevel = AnimationLevel.FULL,
    val themeId: String = ThemePresets.default.id,
    val custom: ThemeColors = ThemePresets.default.colors,
    val brightness: Float = 1f,
    val blackboxSound: Boolean = true,
    val blackboxMusic: Boolean = true,
    val blackboxVibration: Boolean = true,
    val range: RangeMode = RangeMode.MAX,
    /** Calibration: measured signal (dBm) at about 1 m from the partner; null = default model. */
    val rssiAt1m: Int? = null,
    val font: FontChoice = FontChoice.DEFAULT,
    val buttonShape: ButtonShape = ButtonShape.ROUND,
    val buttonGradient: Boolean = false,
    /** In-call loudness multiplier (1 = normal, up to 3). */
    val callBoost: Float = 1.6f,
    /** Liquid-glass panels, buttons and bubbles on every theme. */
    val liquidGlass: Boolean = true,
    val iconPack: IconPack = IconPack.MATCH,
    val appIcon: AppIcon = AppIcon.CLASSIC,
    val chatText: TextSize = TextSize.NORMAL,
    /** Notifications muted until this time (ms), 0 = not muted, Long.MAX_VALUE = until unmuted. */
    val mutedUntil: Long = 0,
    val quickReplies: List<String> = listOf("👍", "OK", "On my way", "Call me", "😂"),
    /** Last app version whose "What's new" screen was shown. */
    val seenVersion: Int = 0,
    /** Send my newer app to my friend's watch over Bluetooth automatically. */
    val autoSendUpdates: Boolean = true,
    /** Install received/downloaded updates automatically (Android may ask once). */
    val autoInstallUpdates: Boolean = true,
    /** Check GitHub for updates over Wi-Fi. */
    val githubUpdates: Boolean = true,
    val lastGithubCheck: Long = 0,
    /** Height in cm, for step length. */
    val heightCm: Int = 170,
    /** Learned walking speed in m/s (0 = not learned yet). */
    val walkSpeed: Float = 0f,
    /** Buzz when the partner is about to go out of range / is lost. */
    val rangeAlert: Boolean = true,
    /** Glass gleam + background drift that follow wrist tilt. */
    val glassMotion: Boolean = true,
    /** Tap ripples through the glass. */
    val glassRipple: Boolean = true,
    /** Glass dims itself from 21:00 to 07:00. */
    val nightTint: Boolean = true,
    /** Fingerprint of the partner whose emoji safety code was checked in person ("" = not verified). */
    val safetyVerified: String = "",
) {
    val muted: Boolean get() = mutedUntil > System.currentTimeMillis()

    val gradient: List<Int>?
        get() = if (themeId == ThemePresets.CUSTOM_ID) null else ThemePresets.byId(themeId)?.gradient
    /** Theme draws liquid "waves" behind the glass. */
    val glassBackground: Boolean
        get() = themeId != ThemePresets.CUSTOM_ID && ThemePresets.byId(themeId)?.glass == true
    val colors: ThemeColors
        get() = if (themeId == ThemePresets.CUSTOM_ID) custom else (ThemePresets.byId(themeId) ?: ThemePresets.default).colors
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("wr_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private fun load(): Settings {
        val d = Settings()
        val dc = d.custom
        return Settings(
            notifications = prefs.getBoolean("notif", d.notifications),
            vibration = prefs.getBoolean("vibe", d.vibration),
            previews = prefs.getBoolean("preview", d.previews),
            sound = prefs.getBoolean("sound", d.sound),
            unit = runCatching { DistanceUnit.valueOf(prefs.getString("unit", d.unit.name)!!) }.getOrDefault(d.unit),
            animations = runCatching { AnimationLevel.valueOf(prefs.getString("anim", d.animations.name)!!) }.getOrDefault(d.animations),
            themeId = prefs.getString("theme", d.themeId)!!,
            custom = ThemeColors(
                prefs.getInt("c_primary", dc.primary), prefs.getInt("c_secondary", dc.secondary),
                prefs.getInt("c_bg", dc.background), prefs.getInt("c_surface", dc.surface),
                prefs.getInt("c_text", dc.text), prefs.getInt("c_in", dc.incoming),
                prefs.getInt("c_out", dc.outgoing), prefs.getInt("c_conn", dc.connection),
            ),
            brightness = prefs.getFloat("bright", d.brightness),
            blackboxSound = prefs.getBoolean("bb_sound", d.blackboxSound),
            blackboxMusic = prefs.getBoolean("bb_music", d.blackboxMusic),
            blackboxVibration = prefs.getBoolean("bb_vibe", d.blackboxVibration),
            range = runCatching { RangeMode.valueOf(prefs.getString("range", d.range.name)!!) }.getOrDefault(d.range),
            rssiAt1m = prefs.getInt("rssi1m", 0).takeIf { it != 0 },
            font = runCatching { FontChoice.valueOf(prefs.getString("font", d.font.name)!!) }.getOrDefault(d.font),
            buttonShape = runCatching { ButtonShape.valueOf(prefs.getString("btn_shape", d.buttonShape.name)!!) }.getOrDefault(d.buttonShape),
            buttonGradient = prefs.getBoolean("btn_grad", d.buttonGradient),
            callBoost = prefs.getFloat("call_boost", d.callBoost),
            seenVersion = prefs.getInt("seen_ver", d.seenVersion),
            autoSendUpdates = prefs.getBoolean("upd_send", d.autoSendUpdates),
            autoInstallUpdates = prefs.getBoolean("upd_install", d.autoInstallUpdates),
            githubUpdates = prefs.getBoolean("upd_github", d.githubUpdates),
            lastGithubCheck = prefs.getLong("upd_gh_last", d.lastGithubCheck),
            heightCm = prefs.getInt("height", d.heightCm),
            walkSpeed = prefs.getFloat("walk_speed", d.walkSpeed),
            rangeAlert = prefs.getBoolean("range_alert", d.rangeAlert),
            liquidGlass = prefs.getBoolean("glass", d.liquidGlass),
            glassMotion = prefs.getBoolean("glass_motion", d.glassMotion),
            glassRipple = prefs.getBoolean("glass_ripple", d.glassRipple),
            nightTint = prefs.getBoolean("night_tint", d.nightTint),
            safetyVerified = prefs.getString("safety_verified", d.safetyVerified) ?: "",
            chatText = runCatching { TextSize.valueOf(prefs.getString("chat_text", d.chatText.name)!!) }.getOrDefault(d.chatText),
            mutedUntil = prefs.getLong("muted_until", d.mutedUntil),
            quickReplies = prefs.getString("quick_replies", null)?.split('\u001F')?.filter { it.isNotBlank() }?.take(8) ?: d.quickReplies,
            iconPack = runCatching { IconPack.valueOf(prefs.getString("icon_pack", d.iconPack.name)!!) }.getOrDefault(d.iconPack),
            appIcon = runCatching { AppIcon.valueOf(prefs.getString("app_icon", d.appIcon.name)!!) }.getOrDefault(d.appIcon),
        ).let { s ->
            // V7: liquid glass is the default look. Anyone still on the old default theme moves to Rose Glass once.
            if (!prefs.getBoolean("glass_migrated", false)) {
                prefs.edit().putBoolean("glass_migrated", true).apply()
                if (s.themeId == "midnight") s.copy(themeId = ThemePresets.default.id) else s
            } else s
        }
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value)
        val c = s.custom
        prefs.edit()
            .putBoolean("notif", s.notifications).putBoolean("vibe", s.vibration)
            .putBoolean("preview", s.previews).putBoolean("sound", s.sound)
            .putString("unit", s.unit.name).putString("anim", s.animations.name)
            .putString("theme", s.themeId)
            .putInt("c_primary", c.primary).putInt("c_secondary", c.secondary)
            .putInt("c_bg", c.background).putInt("c_surface", c.surface)
            .putInt("c_text", c.text).putInt("c_in", c.incoming)
            .putInt("c_out", c.outgoing).putInt("c_conn", c.connection)
            .putFloat("bright", s.brightness)
            .putBoolean("bb_sound", s.blackboxSound).putBoolean("bb_music", s.blackboxMusic)
            .putBoolean("bb_vibe", s.blackboxVibration)
            .putString("range", s.range.name).putInt("rssi1m", s.rssiAt1m ?: 0)
            .putString("font", s.font.name).putString("btn_shape", s.buttonShape.name)
            .putBoolean("btn_grad", s.buttonGradient).putFloat("call_boost", s.callBoost)
            .putInt("seen_ver", s.seenVersion)
            .putBoolean("upd_send", s.autoSendUpdates).putBoolean("upd_install", s.autoInstallUpdates)
            .putBoolean("upd_github", s.githubUpdates).putLong("upd_gh_last", s.lastGithubCheck)
            .putInt("height", s.heightCm).putFloat("walk_speed", s.walkSpeed)
            .putBoolean("range_alert", s.rangeAlert)
            .putString("chat_text", s.chatText.name).putLong("muted_until", s.mutedUntil)
            .putString("quick_replies", s.quickReplies.joinToString("\u001F"))
            .putBoolean("glass", s.liquidGlass).putString("icon_pack", s.iconPack.name).putString("app_icon", s.appIcon.name)
            .putBoolean("glass_motion", s.glassMotion).putBoolean("glass_ripple", s.glassRipple).putBoolean("night_tint", s.nightTint)
            .putString("safety_verified", s.safetyVerified)
            .apply()
        _state.value = s
    }
}
