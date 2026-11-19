package dev.amenhancer.module.ui

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsDraftTest {
    @Test
    fun `cellular toggle preserves pending changes after another row fails to save`() {
        var stored = ModuleSettings()
        var writable = false
        val draft = EmbeddedSettingsDraft(stored) { next ->
            if (writable) stored = next
            writable
        }
        val pending = stored.copy(dualPaneEnabled = false, lyricBlurRadiusOffsetPx = 4)

        assertFalse(draft.update(pending))
        assertEquals(ModuleSettings(), stored)

        writable = true
        assertTrue(draft.updateCellularDataEntry(true))
        assertEquals(pending.copy(forceCellularDataEntryEnabled = true), stored)
        assertEquals(stored, draft.settings)
    }

    @Test
    fun `save button retries all changes when the cellular toggle also fails to save`() {
        var stored = ModuleSettings()
        var writable = false
        val draft = EmbeddedSettingsDraft(stored) { next ->
            if (writable) stored = next
            writable
        }
        val pending = stored.copy(futureBlurEnabled = false, overrideAccountLanguage = true)

        assertFalse(draft.update(pending))
        assertFalse(draft.updateCellularDataEntry(true))
        assertEquals(ModuleSettings(), stored)
        assertEquals(pending.copy(forceCellularDataEntryEnabled = true), draft.settings)

        writable = true
        assertTrue(draft.save())
        assertEquals(pending.copy(forceCellularDataEntryEnabled = true), stored)
    }

    @Test
    fun `cellular toggle uses the latest draft after successful changes on the same page`() {
        var stored = ModuleSettings()
        val draft = EmbeddedSettingsDraft(stored) { next ->
            stored = next
            true
        }
        val updated = stored.copy(dualPaneEnabled = false, lyricBlurRadiusOffsetPx = 3)

        assertTrue(draft.update(updated))
        assertTrue(draft.updateCellularDataEntry(true))
        assertEquals(updated.copy(forceCellularDataEntryEnabled = true), stored)

        val latest = draft.settings.copy(lyricBlurRadiusOffsetPx = 5)
        assertTrue(draft.update(latest))
        assertTrue(draft.updateCellularDataEntry(false))
        assertEquals(updated.copy(lyricBlurRadiusOffsetPx = 5), stored)
    }

    @Test
    fun `clearing the custom source address is written, not only shown`() {
        var stored = ModuleSettings(customLyricsSourceBaseUrl = "https://mirror.example/lyrics")
        val draft = EmbeddedSettingsDraft(stored) { next ->
            stored = next
            true
        }

        assertTrue(draft.saveChange { it.copy(customLyricsSourceBaseUrl = "") })
        assertEquals("", stored.customLyricsSourceBaseUrl)
        assertEquals("", draft.settings.customLyricsSourceBaseUrl)
    }

    @Test
    fun `a failed write keeps the cleared address in the draft for a retry`() {
        var stored = ModuleSettings(customLyricsSourceBaseUrl = "https://mirror.example/lyrics")
        var writes = 0
        // A rejected commit leaves the store untouched; only the draft keeps the new value.
        val draft = EmbeddedSettingsDraft(stored) { _ ->
            writes += 1
            false
        }

        assertFalse(draft.saveChange { it.copy(customLyricsSourceBaseUrl = "") })
        assertEquals("https://mirror.example/lyrics", stored.customLyricsSourceBaseUrl)
        assertEquals("", draft.settings.customLyricsSourceBaseUrl)
        assertEquals(1, writes)
    }

    @Test
    fun `mirroring a key-scoped write leaves the stored settings alone`() {
        var stored = ModuleSettings(automaticLyricsEnabled = true)
        var writes = 0
        val draft = EmbeddedSettingsDraft(stored) { next ->
            stored = next
            writes += 1
            true
        }

        draft.mirrorSavedChange { it.copy(automaticLyricsEnabled = false) }

        assertEquals(0, writes)
        assertTrue(stored.automaticLyricsEnabled)
        assertFalse(draft.settings.automaticLyricsEnabled)
    }
}
