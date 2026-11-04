package com.wristradar.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DistanceUnit { FEET, METRES }
enum class AnimationLevel { FULL, REDUCED, OFF }

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

data class ThemePreset(val id: String, val name: String, val colors: ThemeColors)

object ThemePresets {
    private fun c(v: Long) = v.toInt()
    val all = listOf(
        ThemePreset("midnight", "Midnight Purple", ThemeColors(c(0xFFB266FF), c(0xFF7A3DCC), c(0xFF000000), c(0xFF16121C), c(0xFFEDE7F6), c(0xFF1E1A26), c(0xFF4A2380), c(0xFF39FF88))),
        ThemePreset("neon", "Neon Green", ThemeColors(c(0xFF39FF14), c(0xFF1FAA59), c(0xFF000000), c(0xFF0F1A10), c(0xFFE8FFE8), c(0xFF132015), c(0xFF1E5C1A), c(0xFF39FF14))),
        ThemePreset("cyber", "Cyber Blue", ThemeColors(c(0xFF00B3FF), c(0xFF3D5AFE), c(0xFF000000), c(0xFF0D1520), c(0xFFE3F2FD), c(0xFF111C2A), c(0xFF0D3C73), c(0xFF00E5FF))),
        ThemePreset("crimson", "Crimson Red", ThemeColors(c(0xFFFF3B4E), c(0xFFB0102A), c(0xFF000000), c(0xFF1C0D10), c(0xFFFFE9EC), c(0xFF23111A), c(0xFF6E0F20), c(0xFF4CFF8F))),
        ThemePreset("mono", "Monochrome", ThemeColors(c(0xFFFFFFFF), c(0xFF9E9E9E), c(0xFF000000), c(0xFF161616), c(0xFFF5F5F5), c(0xFF1E1E1E), c(0xFF3A3A3A), c(0xFFFFFFFF))),
        ThemePreset("amber", "Amber Terminal", ThemeColors(c(0xFFFFB000), c(0xFFCC7A00), c(0xFF000000), c(0xFF1A1405), c(0xFFFFE7B0), c(0xFF1E180A), c(0xFF4D3500), c(0xFFFFB000))),
        ThemePreset("arctic", "Arctic Cyan", ThemeColors(c(0xFF00E5FF), c(0xFF80DEEA), c(0xFF000000), c(0xFF0A1A1C), c(0xFFE0FFFF), c(0xFF0F2224), c(0xFF0E4A52), c(0xFF7CFFCB))),
        ThemePreset("ocean", "Deep Ocean", ThemeColors(c(0xFF2E86FF), c(0xFF00BFA5), c(0xFF000814), c(0xFF0A1A2E), c(0xFFD6E9FF), c(0xFF0E2238), c(0xFF0B3D6E), c(0xFF00E5B0))),
    )
    const val CUSTOM_ID = "custom"
    val default = all.first()
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
) {
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
        )
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
            .apply()
        _state.value = s
    }
}
