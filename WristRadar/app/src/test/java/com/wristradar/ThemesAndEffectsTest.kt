package com.wristradar

import com.wristradar.core.MessageEffect
import com.wristradar.data.ThemePresets
import com.wristradar.ui.theme.ThemeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesAndEffectsTest {
    @Test fun every_theme_has_its_own_icon() {
        assertEquals(ThemePresets.all.map { it.id }.toSet(), ThemeIcons.ids)
    }

    @Test fun theme_ids_are_unique_and_alias_safe() {
        val ids = ThemePresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Regex("[a-z0-9_]+").matches(it) })
    }

    @Test fun effect_ids_are_unique_bytes() {
        val ids = MessageEffect.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it in 1..127 })
        assertTrue(MessageEffect.entries.all { MessageEffect.of(it.id) == it })
    }
}
