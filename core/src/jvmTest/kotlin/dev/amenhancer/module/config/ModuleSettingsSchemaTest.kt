package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsSchemaTest {
    @Test
    fun `cellular entry defaults off rejects malformed values and round trips`() {
        assertFalse(ModuleSettingsSchema.decode(emptyMap<String, Any>()).forceCellularDataEntryEnabled)
        assertFalse(ModuleSettingsSchema.decode(
            mapOf("force_cellular_data_entry_enabled" to "true"),
        ).forceCellularDataEntryEnabled)
        val values = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(forceCellularDataEntryEnabled = true),
        )
        assertEquals(true, values["force_cellular_data_entry_enabled"])
        assertEquals(true, ModuleSettingsSchema.decode(values).forceCellularDataEntryEnabled)
    }

    @Test
    fun `cellular entry alone identifies a legacy configuration during upgrade`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf("force_cellular_data_entry_enabled" to true),
            legacyValues = mapOf("dual_pane_enabled" to false),
        )!!
        assertEquals(true, upgraded["force_cellular_data_entry_enabled"])
        assertEquals(true, upgraded["dual_pane_enabled"])
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])
    }

    @Test
    fun `empty values decode to the documented defaults`() {
        assertEquals(
            ModuleSettings(
                dualPaneEnabled = true,
                disableEditorialVideoOnTablet = true,
                phoneLiquidGlassEnabled = false,
                futureBlurEnabled = true,
                cjkKaraokeAnimationEnabled = true,
                navigationCompensationEnabled = false,
                lyricBlurRadiusOffsetPx = 0,
                schemaVersion = ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()),
        )
    }

    @Test
    fun `encoding writes every setting with the current schema version`() {
        val encoded = ModuleSettingsSchema.encode(
            ModuleSettings(
                dualPaneEnabled = false,
                disableEditorialVideoOnTablet = false,
                phoneLiquidGlassEnabled = true,
                futureBlurEnabled = false,
                lyricBlurRadiusOffsetPx = 6,
                schemaVersion = 1,
            ),
        )

        assertEquals(
            mapOf(
                "dual_pane_enabled" to false,
                "disable_editorial_video_on_tablet" to false,
                "phone_liquid_glass_enabled" to true,
                "phone_liquid_glass_bottom_gap_dp" to 16,
                "phone_liquid_glass_panel_blur_dp" to 4,
                "future_blur_enabled" to false,
                "cjk_karaoke_animation_enabled" to true,
                "navigation_compensation_enabled" to false,
                "force_cellular_data_entry_enabled" to false,
                "lyric_blur_radius_offset_px" to 6,
                "apple_music_dpi_override_dpi" to 0,
                "region_selection" to "none",
                "override_account_language" to false,
                "restore_cjk_original_metadata" to false,
                "localized_metadata_cache" to true,
                "custom_lyrics_enabled" to false,
                "automatic_lyrics_enabled" to true,
                "online_lyrics_supplement_enabled" to false,
                "online_lyrics_source_netease_enabled" to true,
                "online_lyrics_source_qq_enabled" to true,
                "online_lyrics_source_kuwo_enabled" to true,
                "online_lyrics_source_kugou_enabled" to true,
                "online_lyrics_automatic_order_enabled" to true,
                "online_lyrics_source_order" to "netease,qq,kuwo,kugou",
                "online_lyrics_global_best_enabled" to false,
                "online_lyrics_translation_enabled" to false,
                "lyrics_font_enabled" to false,
                "lyrics_font_file_id" to "",
                "lyrics_font_display_name" to "",
                "lyrics_font_size_bytes" to 0L,
                "lyrics_font_sha256" to "",
                "custom_lyrics_manifest" to CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()),
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            encoded,
        )
    }

    @Test
    fun `an empty remote store upgrades from legacy values`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = emptyMap<String, Any?>(),
            legacyValues = mapOf(
                "dual_pane_enabled" to false,
                "phone_liquid_glass_enabled" to true,
            ),
        )

        assertEquals(
            mapOf(
                "dual_pane_enabled" to false,
                "disable_editorial_video_on_tablet" to true,
                "phone_liquid_glass_enabled" to true,
                "phone_liquid_glass_bottom_gap_dp" to 16,
                "phone_liquid_glass_panel_blur_dp" to 4,
                "future_blur_enabled" to true,
                "cjk_karaoke_animation_enabled" to true,
                "navigation_compensation_enabled" to false,
                "force_cellular_data_entry_enabled" to false,
                "lyric_blur_radius_offset_px" to 0,
                "apple_music_dpi_override_dpi" to 0,
                "region_selection" to "none",
                "override_account_language" to false,
                "restore_cjk_original_metadata" to false,
                "localized_metadata_cache" to true,
                "custom_lyrics_enabled" to false,
                "automatic_lyrics_enabled" to true,
                "online_lyrics_supplement_enabled" to false,
                "online_lyrics_source_netease_enabled" to true,
                "online_lyrics_source_qq_enabled" to true,
                "online_lyrics_source_kuwo_enabled" to true,
                "online_lyrics_source_kugou_enabled" to true,
                "online_lyrics_automatic_order_enabled" to true,
                "online_lyrics_source_order" to "netease,qq,kuwo,kugou",
                "online_lyrics_global_best_enabled" to false,
                "online_lyrics_translation_enabled" to false,
                "lyrics_font_enabled" to false,
                "lyrics_font_file_id" to "",
                "lyrics_font_display_name" to "",
                "lyrics_font_size_bytes" to 0L,
                "lyrics_font_sha256" to "",
                "custom_lyrics_manifest" to CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()),
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            upgraded,
        )
    }

    @Test
    fun `a current remote schema does not trigger a rewrite`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION,
                "dual_pane_enabled" to false,
            ),
            legacyValues = mapOf("dual_pane_enabled" to true),
        )

        assertEquals(null, upgraded)
    }

    @Test
    fun `an old remote schema upgrades its own values instead of legacy values`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 2,
                "dual_pane_enabled" to false,
            ),
            legacyValues = mapOf("dual_pane_enabled" to true),
        )

        assertEquals(false, upgraded?.get("dual_pane_enabled"))
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded?.get("schema_version"))
    }

    @Test
    fun `malformed values safely fall back without changing valid values`() {
        val decoded = ModuleSettingsSchema.decode(
            mapOf(
                "dual_pane_enabled" to "not-a-boolean",
                "disable_editorial_video_on_tablet" to false,
                "phone_liquid_glass_enabled" to 1,
                "future_blur_enabled" to false,
                "lyric_blur_radius_offset_px" to "too-strong",
                "schema_version" to "three",
            ),
        )

        assertEquals(
            ModuleSettings(
                dualPaneEnabled = true,
                disableEditorialVideoOnTablet = false,
                phoneLiquidGlassEnabled = false,
                futureBlurEnabled = false,
                cjkKaraokeAnimationEnabled = true,
                navigationCompensationEnabled = false,
                lyricBlurRadiusOffsetPx = 0,
                schemaVersion = ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            decoded,
        )
    }

    @Test
    fun `a future schema is never downgraded`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf("schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION + 1),
            legacyValues = mapOf("dual_pane_enabled" to false),
        )

        assertEquals(null, upgraded)
    }

    @Test
    fun `blur radius offset is clamped to the supported range`() {
        assertEquals(
            ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ModuleSettingsSchema.decode(
                mapOf("lyric_blur_radius_offset_px" to 99),
            ).lyricBlurRadiusOffsetPx,
        )
        assertEquals(
            ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ModuleSettingsSchema.decode(
                mapOf("lyric_blur_radius_offset_px" to -99),
            ).lyricBlurRadiusOffsetPx,
        )
    }

    @Test
    fun `liquid glass extras default to the shipped geometry and round trip`() {
        val defaults = ModuleSettingsSchema.decode(emptyMap<String, Any?>())
        assertEquals(16, defaults.phoneLiquidGlassBottomGapDp)
        assertEquals(4, defaults.phoneLiquidGlassPanelBlurDp)

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(phoneLiquidGlassBottomGapDp = 32, phoneLiquidGlassPanelBlurDp = 12),
        )
        assertEquals(32, encoded["phone_liquid_glass_bottom_gap_dp"])
        assertEquals(12, encoded["phone_liquid_glass_panel_blur_dp"])
        val decoded = ModuleSettingsSchema.decode(encoded)
        assertEquals(32, decoded.phoneLiquidGlassBottomGapDp)
        assertEquals(12, decoded.phoneLiquidGlassPanelBlurDp)
    }

    @Test
    fun `liquid glass extras clamp out-of-range values and reject malformed values`() {
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_bottom_gap_dp" to 99),
            ).phoneLiquidGlassBottomGapDp,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_bottom_gap_dp" to -1),
            ).phoneLiquidGlassBottomGapDp,
        )
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_panel_blur_dp" to 99),
            ).phoneLiquidGlassPanelBlurDp,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_panel_blur_dp" to -5),
            ).phoneLiquidGlassPanelBlurDp,
        )
        assertEquals(
            16,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_bottom_gap_dp" to "high"),
            ).phoneLiquidGlassBottomGapDp,
        )
    }

    @Test
    fun `Apple Music DPI accepts fixed values, reset, and fails open for malformed values`() {
        assertEquals(
            480,
            ModuleSettingsSchema.decode(
                mapOf("apple_music_dpi_override_dpi" to 480),
            ).appleMusicDpiOverrideDpi,
        )
        assertEquals(
            ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI,
            ModuleSettingsSchema.decode(
                mapOf("apple_music_dpi_override_dpi" to 641),
            ).appleMusicDpiOverrideDpi,
        )
        assertEquals(
            240,
            ModuleSettingsSchema.decode(
                mapOf("apple_music_dpi_override_dpi" to 240),
            ).appleMusicDpiOverrideDpi,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(appleMusicDpiOverrideDpi = 240),
        )
        assertEquals(240, encoded["apple_music_dpi_override_dpi"])
    }

    @Test
    fun `custom lyrics defaults to disabled and round trips`() {
        assertEquals(
            false,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).customLyricsEnabled,
        )
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf("custom_lyrics_enabled" to "not-a-boolean"),
            ).customLyricsEnabled,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(customLyricsEnabled = true),
        )
        assertEquals(true, encoded["custom_lyrics_enabled"])
        assertEquals(
            true,
            ModuleSettingsSchema.decode(encoded).customLyricsEnabled,
        )
    }

    @Test
    fun `automatic lyrics defaults to enabled and round trips`() {
        assertEquals(
            true,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).automaticLyricsEnabled,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(automaticLyricsEnabled = false),
        )
        assertEquals(false, encoded["automatic_lyrics_enabled"])
        assertEquals(
            false,
            ModuleSettingsSchema.decode(encoded).automaticLyricsEnabled,
        )
    }

    @Test
    fun `online lyrics supplement defaults off and round trips`() {
        assertEquals(
            false,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).onlineLyricsSupplementEnabled,
        )
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf("online_lyrics_supplement_enabled" to "not-a-boolean"),
            ).onlineLyricsSupplementEnabled,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(onlineLyricsSupplementEnabled = true),
        )
        assertEquals(true, encoded["online_lyrics_supplement_enabled"])
        assertEquals(
            true,
            ModuleSettingsSchema.decode(encoded).onlineLyricsSupplementEnabled,
        )
    }

    @Test
    fun `a region-era schema upgrades with the online toggle absent and off`() {
        // The region-only state: v16 carried the region extras but none of the
        // online lyric keys.  Its stored region values must survive the jump to 21.
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 16,
                "title_correction_enabled" to true,
                "title_correction_mode" to "japan",
                "restore_cjk_original_metadata" to false,
                "localized_metadata_cache" to false,
                "custom_lyrics_enabled" to true,
                "automatic_lyrics_enabled" to true,
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!

        assertEquals(false, upgraded["online_lyrics_supplement_enabled"])
        assertEquals(true, upgraded["custom_lyrics_enabled"])
        // v16 -> v21 transition: the region extras are preserved, not re-derived, and
        // the retired picker is rewritten onto the region key.  The retired master
        // switch carried over onto HLE's account-language override.
        assertEquals("japan", upgraded["region_selection"])
        assertEquals(true, upgraded["override_account_language"])
        assertEquals(false, upgraded["restore_cjk_original_metadata"])
        assertEquals(false, upgraded["localized_metadata_cache"])
        assertFalse(upgraded.containsKey("title_correction_enabled"))
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])
    }

    @Test
    fun `the online lyric chain defaults on, uses the built-in order and round trips`() {
        val decoded = ModuleSettingsSchema.decode(emptyMap<String, Any?>())
        assertTrue(decoded.onlineLyricsSourceNeteaseEnabled)
        assertTrue(decoded.onlineLyricsSourceQqEnabled)
        assertTrue(decoded.onlineLyricsSourceKuwoEnabled)
        assertTrue(decoded.onlineLyricsSourceKugouEnabled)
        assertTrue(decoded.onlineLyricsAutomaticOrderEnabled)
        assertFalse(decoded.onlineLyricsGlobalBestEnabled)
        assertEquals("netease,qq,kuwo,kugou", decoded.onlineLyricsSourceOrder)

        // Malformed booleans fall back to the documented defaults; an unknown or
        // partial order is normalized back to a permutation of the default order.
        val malformed = ModuleSettingsSchema.decode(
            mapOf(
                "online_lyrics_source_netease_enabled" to "yes",
                "online_lyrics_automatic_order_enabled" to 1,
                "online_lyrics_global_best_enabled" to "on",
                "online_lyrics_source_order" to "kugou,unknown",
            ),
        )
        assertTrue(malformed.onlineLyricsSourceNeteaseEnabled)
        assertTrue(malformed.onlineLyricsAutomaticOrderEnabled)
        assertFalse(malformed.onlineLyricsGlobalBestEnabled)
        assertEquals("kugou,netease,qq,kuwo", malformed.onlineLyricsSourceOrder)

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(
                onlineLyricsSourceNeteaseEnabled = false,
                onlineLyricsSourceKugouEnabled = false,
                onlineLyricsAutomaticOrderEnabled = false,
                onlineLyricsSourceOrder = "kuwo,qq",
                onlineLyricsGlobalBestEnabled = true,
            ),
        )
        assertEquals(false, encoded["online_lyrics_source_netease_enabled"])
        assertEquals(false, encoded["online_lyrics_source_kugou_enabled"])
        assertEquals(false, encoded["online_lyrics_automatic_order_enabled"])
        assertEquals("kuwo,qq,netease,kugou", encoded["online_lyrics_source_order"])
        assertEquals(true, encoded["online_lyrics_global_best_enabled"])

        val roundTripped = ModuleSettingsSchema.decode(encoded)
        assertEquals(false, roundTripped.onlineLyricsSourceNeteaseEnabled)
        assertEquals(false, roundTripped.onlineLyricsSourceKugouEnabled)
        assertEquals(false, roundTripped.onlineLyricsAutomaticOrderEnabled)
        assertEquals("kuwo,qq,netease,kugou", roundTripped.onlineLyricsSourceOrder)
        assertEquals(true, roundTripped.onlineLyricsGlobalBestEnabled)
    }

    @Test
    fun `a schema 17 store upgrades with the online lyric chain defaults`() {
        // The previous integration state: v17 = region extras (16) + supplement.
        // Its region values must survive while the newly added lyric chain takes
        // its documented defaults.
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 17,
                "title_correction_enabled" to true,
                "title_correction_mode" to "japan",
                "restore_cjk_original_metadata" to false,
                "localized_metadata_cache" to false,
                "custom_lyrics_enabled" to true,
                "online_lyrics_supplement_enabled" to true,
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!

        // v17 -> v21 transition: region values preserved, the retired master carried
        // over onto the override switch.
        assertEquals("japan", upgraded["region_selection"])
        assertEquals(true, upgraded["override_account_language"])
        assertEquals(false, upgraded["restore_cjk_original_metadata"])
        assertEquals(false, upgraded["localized_metadata_cache"])
        assertEquals(true, upgraded["online_lyrics_supplement_enabled"])
        assertEquals(true, upgraded["online_lyrics_source_netease_enabled"])
        assertEquals(true, upgraded["online_lyrics_source_qq_enabled"])
        assertEquals(true, upgraded["online_lyrics_source_kuwo_enabled"])
        assertEquals(true, upgraded["online_lyrics_source_kugou_enabled"])
        assertEquals(true, upgraded["online_lyrics_automatic_order_enabled"])
        assertEquals("netease,qq,kuwo,kugou", upgraded["online_lyrics_source_order"])
        assertEquals(false, upgraded["online_lyrics_global_best_enabled"])
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])
    }

    @Test
    fun `a schema 18 store upgrades with the translation enrichment toggle off`() {
        // A store written by the lyrics-only branch: it has the lyric chain but
        // no region keys.  The v18 -> v19 jump adds the translation toggle and
        // derives the region extras from the stored profile.
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 18,
                "custom_lyrics_enabled" to true,
                "online_lyrics_supplement_enabled" to true,
                "online_lyrics_global_best_enabled" to true,
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!

        assertEquals(true, upgraded["online_lyrics_supplement_enabled"])
        assertEquals(true, upgraded["online_lyrics_global_best_enabled"])
        assertEquals(false, upgraded["online_lyrics_translation_enabled"])
        // No stored region profile at all: HLE's region/override defaults are off and
        // its 替换中日韩歌曲信息为原地区原名 switch lands off too.
        assertEquals("none", upgraded["region_selection"])
        assertEquals(false, upgraded["override_account_language"])
        assertEquals(false, upgraded["restore_cjk_original_metadata"])
        assertEquals(true, upgraded["localized_metadata_cache"])
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])
    }

    @Test
    fun `a lyrics-only 19 store upgrades by splitting the retired picker`() {
        // A lyrics-only store reached the same version number but still carries the
        // retired picker and master switch.  v20/v21 rewrite them: the region half is
        // preserved, the absent switch keeps the value the picker used to imply and
        // the master carries over onto HLE's account-language override.
        val stored = mapOf<String, Any?>(
            "schema_version" to 19,
            "custom_lyrics_enabled" to true,
            "title_correction_enabled" to true,
            "title_correction_mode" to "japan",
        )
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = stored,
            legacyValues = emptyMap<String, Any?>(),
        )!!

        assertEquals("japan", upgraded["region_selection"])
        assertEquals(true, upgraded["override_account_language"])
        assertEquals(false, upgraded["restore_cjk_original_metadata"])
        assertFalse(upgraded.containsKey("title_correction_mode"))
        assertFalse(upgraded.containsKey("title_correction_enabled"))
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])

        val decoded = ModuleSettingsSchema.decode(upgraded)
        assertEquals(RegionSelection.JAPAN, decoded.regionSelection)
        assertEquals(true, decoded.overrideAccountLanguage)
        assertEquals(false, decoded.restoreCjkOriginalMetadata)
        assertEquals(true, decoded.localizedMetadataCache)
    }

    @Test
    fun `translation enrichment defaults off rejects malformed values and round trips`() {
        assertFalse(
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).onlineLyricsTranslationEnabled,
        )
        assertFalse(
            ModuleSettingsSchema.decode(
                mapOf("online_lyrics_translation_enabled" to "not-a-boolean"),
            ).onlineLyricsTranslationEnabled,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(onlineLyricsTranslationEnabled = true),
        )
        assertEquals(true, encoded["online_lyrics_translation_enabled"])
        assertEquals(
            true,
            ModuleSettingsSchema.decode(encoded).onlineLyricsTranslationEnabled,
        )
    }

    @Test
    fun `the account-language override defaults off and round trips`() {
        assertFalse(
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).overrideAccountLanguage,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(
                regionSelection = RegionSelection.JAPAN,
                overrideAccountLanguage = true,
            ),
        )
        assertEquals(true, encoded["override_account_language"])
        assertEquals(true, ModuleSettingsSchema.decode(encoded).overrideAccountLanguage)
    }

    @Test
    fun `the retired master switch carries over onto the override only with a region`() {
        // v20's master gated the runtime while a region selected meant "replace account
        // language"; the v21 upgrade keeps that pair without turning the switch on for a
        // store that had no region.
        assertTrue(
            ModuleSettingsSchema.decode(
                mapOf("title_correction_enabled" to true, "region_selection" to "japan"),
            ).overrideAccountLanguage,
        )
        assertFalse(
            ModuleSettingsSchema.decode(
                mapOf("title_correction_enabled" to true, "region_selection" to "none"),
            ).overrideAccountLanguage,
        )
        assertFalse(
            ModuleSettingsSchema.decode(
                mapOf("title_correction_enabled" to false, "region_selection" to "japan"),
            ).overrideAccountLanguage,
        )
    }

    @Test
    fun `the region control defaults to no region and round trips`() {
        assertEquals(
            RegionSelection.NONE,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).regionSelection,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(regionSelection = RegionSelection.JAPAN),
        )
        assertEquals("japan", encoded["region_selection"])
        assertEquals(RegionSelection.JAPAN, ModuleSettingsSchema.decode(encoded).regionSelection)
    }

    @Test
    fun `schema v11 target language migrates to the selected region`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 11,
                "title_correction_enabled" to true,
                "title_correction_target_language" to "ja_jp",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )

        assertEquals("japan", upgraded?.get("region_selection"))
        assertEquals(true, upgraded?.get("override_account_language"))
        assertFalse(upgraded?.containsKey("title_correction_target_language") == true)
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded?.get("schema_version"))
    }

    @Test
    fun `legacy mainland target maps to mainland region and unsupported target maps to none`() {
        val mainland = ModuleSettingsSchema.decode(
            mapOf(
                "title_correction_enabled" to true,
                "title_correction_target_language" to "zh-CN",
            ),
        )
        val unsupported = ModuleSettingsSchema.decode(
            mapOf(
                "title_correction_enabled" to true,
                "title_correction_target_language" to "ko-KR",
            ),
        )
        assertEquals(RegionSelection.MAINLAND_CHINA, mainland.regionSelection)
        assertEquals(RegionSelection.NONE, unsupported.regionSelection)
    }

    @Test
    fun `the region and title controls default to HLE's off state`() {
        val decoded = ModuleSettingsSchema.decode(emptyMap<String, Any?>())
        assertEquals(RegionSelection.NONE, decoded.regionSelection)
        assertEquals(false, decoded.overrideAccountLanguage)
        assertEquals(false, decoded.restoreCjkOriginalMetadata)
        assertEquals(true, decoded.localizedMetadataCache)
    }

    @Test
    fun `a legacy store without the switch keeps the retired picker's implied value`() {
        // The pre-v16 picker did not separate "replace the region" from "restore
        // original names": restoring was implied by the no-region value only.
        assertEquals(
            true,
            ModuleSettingsSchema.decode(
                mapOf("title_correction_mode" to "original_hyper"),
            ).restoreCjkOriginalMetadata,
        )
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf("title_correction_mode" to "mainland_china"),
            ).restoreCjkOriginalMetadata,
        )
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf("title_correction_mode" to "japan"),
            ).restoreCjkOriginalMetadata,
        )
    }

    @Test
    fun `an explicit title-correction value always wins and the region key never implies it`() {
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf(
                    "title_correction_mode" to "original_hyper",
                    "restore_cjk_original_metadata" to false,
                ),
            ).restoreCjkOriginalMetadata,
        )
        // Combining a region replacement with original-name restore is allowed.
        assertEquals(
            true,
            ModuleSettingsSchema.decode(
                mapOf(
                    "title_correction_mode" to "japan",
                    "restore_cjk_original_metadata" to true,
                ),
            ).restoreCjkOriginalMetadata,
        )
        // The region key alone never implies the restore switch: with no retired
        // picker profile and no stored value it lands on HLE's off default.
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf("region_selection" to "none"),
            ).restoreCjkOriginalMetadata,
        )
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf("region_selection" to "japan"),
            ).restoreCjkOriginalMetadata,
        )
    }

    @Test
    fun `region extras round trip and can be turned off`() {
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(
                restoreCjkOriginalMetadata = false,
                localizedMetadataCache = false,
            ),
        )
        assertEquals(false, encoded["restore_cjk_original_metadata"])
        assertEquals(false, encoded["localized_metadata_cache"])
        val decoded = ModuleSettingsSchema.decode(encoded)
        assertEquals(false, decoded.restoreCjkOriginalMetadata)
        assertEquals(false, decoded.localizedMetadataCache)
    }

    @Test
    fun `every region selection survives a settings round trip`() {
        RegionSelection.values().forEach { region ->
            val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
                ModuleSettings(
                    regionSelection = region,
                    restoreCjkOriginalMetadata = !region.replacesRegion,
                ),
            )
            val decoded = ModuleSettingsSchema.decode(encoded)
            assertEquals(region, decoded.regionSelection)
            assertEquals(region.contentUiLanguageSelection, decoded.regionSelection.contentUiLanguageSelection)
        }
    }

    @Test
    fun `a v15 configuration upgrades to v21 without losing its region behaviour`() {
        val upgradedRegion = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 15,
                "title_correction_enabled" to true,
                "title_correction_mode" to "japan",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgradedRegion?.get("schema_version"))
        assertEquals(false, upgradedRegion?.get("restore_cjk_original_metadata"))
        assertEquals(true, upgradedRegion?.get("override_account_language"))
        assertEquals("japan", upgradedRegion?.get("region_selection"))

        val upgradedOriginal = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 15,
                "title_correction_enabled" to true,
                "title_correction_mode" to "original_hyper",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )
        assertEquals(true, upgradedOriginal?.get("restore_cjk_original_metadata"))
        assertEquals(false, upgradedOriginal?.get("override_account_language"))
        assertEquals("none", upgradedOriginal?.get("region_selection"))
    }

    @Test
    fun `navigation compensation defaults off and round trips`() {
        assertEquals(
            false,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).navigationCompensationEnabled,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(navigationCompensationEnabled = true),
        )
        assertEquals(true, encoded["navigation_compensation_enabled"])
        assertEquals(
            true,
            ModuleSettingsSchema.decode(encoded).navigationCompensationEnabled,
        )
    }

    @Test
    fun `cjk karaoke animation defaults on and round trips`() {
        assertEquals(
            true,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).cjkKaraokeAnimationEnabled,
        )
        assertEquals(
            true,
            ModuleSettingsSchema.decode(
                mapOf("cjk_karaoke_animation_enabled" to "not-a-boolean"),
            ).cjkKaraokeAnimationEnabled,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(cjkKaraokeAnimationEnabled = false),
        )
        assertEquals(false, encoded["cjk_karaoke_animation_enabled"])
        assertEquals(
            false,
            ModuleSettingsSchema.decode(encoded).cjkKaraokeAnimationEnabled,
        )
    }

    @Test
    fun `an old online lyric setting migrates to the custom lyrics gate`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 5,
                "online_lyric_replacement_enabled" to true,
            ),
            legacyValues = emptyMap<String, Any?>(),
        )

        assertEquals(true, upgraded?.get("custom_lyrics_enabled"))
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded?.get("schema_version"))
    }

    @Test
    fun `index pointer round trips through its preference keys`() {
        val pointer = CustomLyricsIndexPointer(
            fileId = "index_abc123",
            generation = 7L,
            sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            sizeBytes = 4096L,
        )

        assertEquals(
            pointer,
            ModuleSettingsSchema.decodeIndexPointer(ModuleSettingsSchema.encodeIndexPointer(pointer)),
        )
    }

    @Test
    fun `malformed index pointers fail closed`() {
        val base = mapOf(
            "custom_lyrics_index_file_id" to "index_abc123",
            "custom_lyrics_index_generation" to 1L,
            "custom_lyrics_index_sha256" to "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            "custom_lyrics_index_size_bytes" to 4096L,
        )

        assertNull(ModuleSettingsSchema.decodeIndexPointer(emptyMap<String, Any>()))
        assertNull(ModuleSettingsSchema.decodeIndexPointer(base - "custom_lyrics_index_file_id"))
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_file_id" to "../bad"),
            ),
        )
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_generation" to 0L),
            ),
        )
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_sha256" to "not-a-hash"),
            ),
        )
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_size_bytes" to 0L),
            ),
        )
    }

    @Test
    fun `legacy manifest decode reads the v1 preference string`() {
        val values = mapOf(
            "custom_lyrics_manifest" to
                """{"version":1,"entries":[{"appleMusicId":42,"displayName":"Old","fileId":"lyrics_old","sizeBytes":42,"sha256":"0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53","source":"manual","enabled":true}]}""",
        )

        assertEquals(
            listOf(42L),
            ModuleSettingsSchema.decodeLegacyCustomLyricsManifest(values).entries.map { it.appleMusicId },
        )
    }

    @Test
    fun `AMTool module settings keys are documented but never migrated or decoded`() {
        assertEquals("modify_locale", ModuleSettingsSchema.AMTOOL_MODIFY_LOCALE_KEY)
        assertEquals(
            "modify_locale_target_tag",
            ModuleSettingsSchema.AMTOOL_MODIFY_LOCALE_TARGET_TAG_KEY,
        )

        val decoded = ModuleSettingsSchema.decode(
            mapOf(
                "modify_locale" to true,
                "modify_locale_target_tag" to "zh-CN",
            ),
        )
        assertFalse(decoded.overrideAccountLanguage)

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(decoded)
        assertFalse(encoded.containsKey("modify_locale"))
        assertFalse(encoded.containsKey("modify_locale_target_tag"))
        assertEquals("none", encoded["region_selection"])

        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "modify_locale" to true,
                "modify_locale_target_tag" to "zh-CN",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )
        assertFalse(upgraded!!.containsKey("modify_locale"))
        assertFalse(upgraded.containsKey("modify_locale_target_tag"))
        assertEquals("none", upgraded["region_selection"])
    }
}
