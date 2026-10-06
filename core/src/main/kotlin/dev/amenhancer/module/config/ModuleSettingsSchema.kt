package dev.amenhancer.module.config

import dev.amenhancer.module.model.EnhancementDefaults
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.OnlineLyricSources

object ModuleSettingsSchema {
    /** Keys removed by the profile migrations. */
    val obsoleteKeys: Set<String> = setOf(
        KEY_TITLE_CORRECTION_TARGET_LANGUAGE,
        // v20: the retired single picker; its region half moves to KEY_REGION_SELECTION
        // and its name half to KEY_RESTORE_CJK_ORIGINAL_METADATA.
        KEY_TITLE_CORRECTION_MODE,
        // v21: the fork-only master switch.  HLE's page has no master; the region
        // picker plus KEY_OVERRIDE_ACCOUNT_LANGUAGE and
        // KEY_RESTORE_CJK_ORIGINAL_METADATA fully express when the runtime acts.
        // The retired value is carried over onto the new override switch below.
        KEY_TITLE_CORRECTION_ENABLED,
    )

    fun decode(values: Map<String, *>): ModuleSettings {
        val regionSelection = values.regionSelection()
        return ModuleSettings(
            dualPaneEnabled = values.boolean(KEY_DUAL_PANE, default = true),
            disableEditorialVideoOnTablet = values.boolean(
                KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
                default = true,
            ),
            phoneLiquidGlassEnabled = values.boolean(
                KEY_PHONE_LIQUID_GLASS,
                default = false,
            ),
            phoneLiquidGlassBottomGapDp = values.number(KEY_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP)
                ?.coerceIn(
                    ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                    ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                ) ?: EnhancementDefaults.GLASS_BOTTOM_DP,
            phoneLiquidGlassPanelBlurDp = values.number(KEY_PHONE_LIQUID_GLASS_PANEL_BLUR_DP)
                ?.coerceIn(
                    ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                    ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                ) ?: EnhancementDefaults.GLASS_PANEL_BLUR_DP.toInt(),
            futureBlurEnabled = values.boolean(KEY_FUTURE_BLUR, default = true),
            cjkKaraokeAnimationEnabled = values.boolean(
                KEY_CJK_KARAOKE_ANIMATION_ENABLED,
                default = true,
            ),
            navigationCompensationEnabled = values.boolean(
                KEY_NAVIGATION_COMPENSATION,
                default = false,
            ),
            forceCellularDataEntryEnabled = values.boolean(KEY_FORCE_CELLULAR_DATA_ENTRY, default = false),
            lyricBlurRadiusOffsetPx = values.number(KEY_LYRIC_BLUR_RADIUS_OFFSET)
                ?.coerceIn(
                    ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                ) ?: 0,
            appleMusicDpiOverrideDpi = ModuleSettings.normalizeAppleMusicDpi(
                values.number(KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI) ?: ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI,
            ),
            regionSelection = regionSelection,
            // HLE's 「歌曲信息替换至设定地区语言」.  A store written by the retired
            // master-switch model replaced account language whenever a region was
            // selected and the master was on; carry that over so the upgrade keeps
            // behaving the same.  A fresh store lands on HLE's default, false.
            overrideAccountLanguage = values.boolean(
                KEY_OVERRIDE_ACCOUNT_LANGUAGE,
                default = values.legacyOverrideAccountLanguageDefault(),
            ),
            // Stores that predate the separate switch keep the behaviour of the retired
            // picker: its no-region value restored names, every region value did not.
            // Once the key exists it always wins, so the controls are independent.
            restoreCjkOriginalMetadata = values.boolean(
                KEY_RESTORE_CJK_ORIGINAL_METADATA,
                default = values.legacyRestoreCjkOriginalMetadataDefault(),
            ),
            localizedMetadataCache = values.boolean(
                KEY_LOCALIZED_METADATA_CACHE,
                default = true,
            ),
            metadataCacheClearGeneration = metadataCacheClearGeneration(values),
            customLyricsEnabled = values.boolean(
                KEY_CUSTOM_LYRICS_ENABLED,
                default = values.boolean(KEY_LEGACY_ONLINE_LYRIC_REPLACEMENT, default = false),
            ),
            automaticLyricsEnabled = values.boolean(KEY_AUTOMATIC_LYRICS_ENABLED, default = true),
            onlineLyricsSupplementEnabled = values.boolean(
                KEY_ONLINE_LYRICS_SUPPLEMENT_ENABLED,
                default = false,
            ),
            onlineLyricsSourceNeteaseEnabled = values.boolean(
                KEY_ONLINE_LYRICS_SOURCE_NETEASE_ENABLED,
                default = true,
            ),
            onlineLyricsSourceQqEnabled = values.boolean(
                KEY_ONLINE_LYRICS_SOURCE_QQ_ENABLED,
                default = true,
            ),
            onlineLyricsSourceKuwoEnabled = values.boolean(
                KEY_ONLINE_LYRICS_SOURCE_KUWO_ENABLED,
                default = true,
            ),
            onlineLyricsSourceKugouEnabled = values.boolean(
                KEY_ONLINE_LYRICS_SOURCE_KUGOU_ENABLED,
                default = true,
            ),
            onlineLyricsAutomaticOrderEnabled = values.boolean(
                KEY_ONLINE_LYRICS_AUTOMATIC_ORDER_ENABLED,
                default = true,
            ),
            onlineLyricsSourceOrder = OnlineLyricSources.normalizeOrder(
                values.string(KEY_ONLINE_LYRICS_SOURCE_ORDER),
            ).joinToString(","),
            onlineLyricsGlobalBestEnabled = values.boolean(
                KEY_ONLINE_LYRICS_GLOBAL_BEST_ENABLED,
                default = false,
            ),
            onlineLyricsTranslationEnabled = values.boolean(
                KEY_ONLINE_LYRICS_TRANSLATION_ENABLED,
                default = false,
            ),
            fontManifest = values.fontManifest(),
            customLyricsManifest = values.customLyricsManifest(),
            schemaVersion = values.number(KEY_SCHEMA_VERSION)
                ?: ModuleConstants.CONFIG_SCHEMA_VERSION,
        )
    }

    fun encode(settings: ModuleSettings): Map<String, Any> =
        encodeOrdinarySettings(settings) +
            encodeFontManifest(settings.fontManifest) +
            encodeCustomLyricsManifest(settings.customLyricsManifest)

    /**
     * Runtime write map for ordinary settings only. Never carries the
     * lyrics_font_* or custom_lyrics_manifest keys, so a stale ModuleSettings
     * captured before a remote-file transaction cannot overwrite a manifest
     * committed afterwards.
     */
    fun encodeOrdinarySettings(settings: ModuleSettings): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            KEY_DUAL_PANE to settings.dualPaneEnabled,
            KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET to settings.disableEditorialVideoOnTablet,
            KEY_PHONE_LIQUID_GLASS to settings.phoneLiquidGlassEnabled,
            KEY_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP to
                ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(
                    settings.phoneLiquidGlassBottomGapDp,
                ),
            KEY_PHONE_LIQUID_GLASS_PANEL_BLUR_DP to
                ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(
                    settings.phoneLiquidGlassPanelBlurDp,
                ),
            KEY_FUTURE_BLUR to settings.futureBlurEnabled,
            KEY_CJK_KARAOKE_ANIMATION_ENABLED to settings.cjkKaraokeAnimationEnabled,
            KEY_NAVIGATION_COMPENSATION to settings.navigationCompensationEnabled,
            KEY_FORCE_CELLULAR_DATA_ENTRY to settings.forceCellularDataEntryEnabled,
            KEY_LYRIC_BLUR_RADIUS_OFFSET to settings.lyricBlurRadiusOffsetPx.coerceIn(
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ),
            KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI to ModuleSettings.normalizeAppleMusicDpi(
                settings.appleMusicDpiOverrideDpi,
            ),
            KEY_REGION_SELECTION to settings.regionSelection.storageValue,
            KEY_OVERRIDE_ACCOUNT_LANGUAGE to settings.overrideAccountLanguage,
            KEY_RESTORE_CJK_ORIGINAL_METADATA to settings.restoreCjkOriginalMetadata,
            KEY_LOCALIZED_METADATA_CACHE to settings.localizedMetadataCache,
            KEY_METADATA_CACHE_CLEAR_GENERATION to
                settings.metadataCacheClearGeneration.coerceAtLeast(0L),
            KEY_CUSTOM_LYRICS_ENABLED to settings.customLyricsEnabled,
            KEY_AUTOMATIC_LYRICS_ENABLED to settings.automaticLyricsEnabled,
            KEY_ONLINE_LYRICS_SUPPLEMENT_ENABLED to settings.onlineLyricsSupplementEnabled,
            KEY_ONLINE_LYRICS_SOURCE_NETEASE_ENABLED to settings.onlineLyricsSourceNeteaseEnabled,
            KEY_ONLINE_LYRICS_SOURCE_QQ_ENABLED to settings.onlineLyricsSourceQqEnabled,
            KEY_ONLINE_LYRICS_SOURCE_KUWO_ENABLED to settings.onlineLyricsSourceKuwoEnabled,
            KEY_ONLINE_LYRICS_SOURCE_KUGOU_ENABLED to settings.onlineLyricsSourceKugouEnabled,
            KEY_ONLINE_LYRICS_AUTOMATIC_ORDER_ENABLED to
                settings.onlineLyricsAutomaticOrderEnabled,
            KEY_ONLINE_LYRICS_SOURCE_ORDER to
                OnlineLyricSources.normalizeOrder(settings.onlineLyricsSourceOrder)
                    .joinToString(","),
            KEY_ONLINE_LYRICS_GLOBAL_BEST_ENABLED to settings.onlineLyricsGlobalBestEnabled,
            KEY_ONLINE_LYRICS_TRANSLATION_ENABLED to settings.onlineLyricsTranslationEnabled,
        )
        values[KEY_SCHEMA_VERSION] = ModuleConstants.CONFIG_SCHEMA_VERSION
        return values
    }

    fun encodeFontManifest(manifest: LyricsFontManifest): Map<String, Any> {
        val safe = FontManifestPolicy.sanitize(manifest)
        return linkedMapOf(
            KEY_FONT_ENABLED to safe.enabled,
            KEY_FONT_FILE_ID to safe.fileId,
            KEY_FONT_DISPLAY_NAME to safe.displayName,
            KEY_FONT_SIZE_BYTES to safe.sizeBytes,
            KEY_FONT_SHA256 to safe.sha256,
        )
    }

    fun encodeCustomLyricsManifest(manifest: CustomLyricsManifest): Map<String, Any> =
        linkedMapOf(KEY_CUSTOM_LYRICS_MANIFEST to CustomLyricsManifestCodec.encode(manifest))

    /**
     * Pointer keys point at the remote index file. They are index state, not
     * settings: never emitted by [encode] or [encodeOrdinarySettings], so an
     * ordinary settings write can never overwrite a published index pointer.
     */
    fun encodeIndexPointer(pointer: CustomLyricsIndexPointer): Map<String, Any> =
        linkedMapOf(
            KEY_CUSTOM_LYRICS_INDEX_FILE_ID to pointer.fileId,
            KEY_CUSTOM_LYRICS_INDEX_GENERATION to pointer.generation,
            KEY_CUSTOM_LYRICS_INDEX_SHA256 to pointer.sha256,
            KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES to pointer.sizeBytes,
        )

    /** Fails closed: a pointer must be complete, well-formed, and published at least once. */
    fun decodeIndexPointer(values: Map<String, *>): CustomLyricsIndexPointer? {
        val fileId = values.string(KEY_CUSTOM_LYRICS_INDEX_FILE_ID)
        val generation = values.long(KEY_CUSTOM_LYRICS_INDEX_GENERATION)
        val sha256 = values.string(KEY_CUSTOM_LYRICS_INDEX_SHA256)
        val sizeBytes = values.long(KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES)
        if (generation == null || generation < 1L) return null
        if (sizeBytes == null || sizeBytes !in 1L..CustomLyricsManifestPolicy.MAX_INDEX_BYTES.toLong()) {
            return null
        }
        if (!CustomLyricsManifestPolicy.isValidFileId(fileId)) return null
        if (!CustomLyricsManifestPolicy.isValidSha256(sha256)) return null
        return CustomLyricsIndexPointer(
            fileId = fileId,
            generation = generation,
            sha256 = sha256.lowercase(),
            sizeBytes = sizeBytes,
        )
    }

    fun hasIndexPointerValues(values: Map<String, *>): Boolean =
        indexPointerKeys.any(values::containsKey)

    /** Avoid turning an unrelated/empty remote group into a completed migration. */
    fun hasMigratableValues(values: Map<String, *>): Boolean =
        values.keys.any { it in settingKeys || it in obsoleteKeys || it in indexPointerKeys }

    /**
     * Cheap single-key read of the one-shot 「清空检索库」 signal.  The metadata
     * runtime polls this on its request seams, so it must not decode every
     * setting (and the map values are already loaded by the caller).
     */
    fun metadataCacheClearGeneration(values: Map<String, *>): Long =
        values.long(KEY_METADATA_CACHE_CLEAR_GENERATION)?.coerceAtLeast(0L) ?: 0L

    /** Legacy v1 preference-string manifest, kept for pre-migration reads. */
    fun decodeLegacyCustomLyricsManifest(values: Map<String, *>): CustomLyricsManifest =
        CustomLyricsManifestCodec.decode(values.string(KEY_CUSTOM_LYRICS_MANIFEST))

    fun legacyCustomLyricsManifestRaw(values: Map<String, *>): String =
        values.string(KEY_CUSTOM_LYRICS_MANIFEST)

    private fun Map<String, *>.fontManifest(): LyricsFontManifest {
        val raw = LyricsFontManifest(
            enabled = boolean(KEY_FONT_ENABLED, default = false),
            fileId = string(KEY_FONT_FILE_ID),
            displayName = string(KEY_FONT_DISPLAY_NAME),
            sizeBytes = long(KEY_FONT_SIZE_BYTES) ?: 0L,
            sha256 = string(KEY_FONT_SHA256),
        )
        return FontManifestPolicy.sanitize(raw)
    }

    private fun Map<String, *>.customLyricsManifest(): CustomLyricsManifest =
        decodeLegacyCustomLyricsManifest(this)

    /**
     * Reads the region control.  The new key wins; a store written before the split
     * still carries the retired picker value, and the v11 target language is the last
     * fallback for a store that predates the picker entirely.
     */
    private fun Map<String, *>.regionSelection(): RegionSelection {
        val storedRegion = string(KEY_REGION_SELECTION)
        if (storedRegion.isNotBlank()) return RegionSelection.decode(storedRegion)
        val storedLegacyMode = string(KEY_TITLE_CORRECTION_MODE)
        if (storedLegacyMode.isNotBlank()) {
            return RegionSelection.fromLegacyTitleCorrectionMode(storedLegacyMode)
        }
        if (!boolean(KEY_TITLE_CORRECTION_ENABLED, default = false)) {
            return RegionSelection.NONE
        }
        return RegionSelection.fromLegacyTargetLanguage(
            string(KEY_TITLE_CORRECTION_TARGET_LANGUAGE),
        )
    }

    /**
     * The original-name restore default for a store that predates the separate switch.
     * It deliberately reads only the retired picker and its v11 predecessor, never
     * [KEY_REGION_SELECTION], so the region control can never imply the switch.
     *
     * A store with no retired region profile at all is a fresh (or lyrics-only)
     * installation: HLE's 「替换中日韩歌曲信息为原地区原名」 defaults off there.
     */
    private fun Map<String, *>.legacyRestoreCjkOriginalMetadataDefault(): Boolean {
        val storedLegacyMode = string(KEY_TITLE_CORRECTION_MODE)
        if (storedLegacyMode.isNotBlank()) {
            return RegionSelection.fromLegacyTitleCorrectionMode(storedLegacyMode) ==
                RegionSelection.NONE
        }
        if (!containsKey(KEY_TITLE_CORRECTION_TARGET_LANGUAGE)) return false
        if (!boolean(KEY_TITLE_CORRECTION_ENABLED, default = false)) return false
        return RegionSelection.fromLegacyTargetLanguage(
            string(KEY_TITLE_CORRECTION_TARGET_LANGUAGE),
        ) == RegionSelection.NONE
    }

    /**
     * HLE's 「歌曲信息替换至设定地区语言」 default for a store written by the retired
     * master-switch model: the old runtime replaced account language whenever a region
     * was selected and the master was on, so the upgrade keeps that behaviour.
     *
     * It reads the retired master rather than [KEY_REGION_SELECTION] alone, and a
     * store with no retired master (a fresh store) keeps HLE's default, false.
     */
    private fun Map<String, *>.legacyOverrideAccountLanguageDefault(): Boolean =
        boolean(KEY_TITLE_CORRECTION_ENABLED, default = false) &&
            regionSelection().replacesRegion

    /**
     * Returns the host-local values required before removing the retired keys.  This
     * is intentionally independent of the schema version: an already-initialized
     * embedded store skips remote migration, so it must still be able to upgrade its
     * own legacy values in place.
     *
     * It publishes the region selection and, when the store predates the separate
     * switches, their derived values, because the legacy keys are deleted right after
     * this call.  A v20 store already has [KEY_REGION_SELECTION], so only the
     * `override_account_language` carry-over runs for it.
     */
    fun legacyTitleCorrectionMigrationValues(values: Map<String, *>): Map<String, Any> {
        val migration = linkedMapOf<String, Any>()
        if (values.string(KEY_REGION_SELECTION).isBlank()) {
            val hasLegacyMode = values.string(KEY_TITLE_CORRECTION_MODE).isNotBlank()
            val hasLegacyTarget = values.containsKey(KEY_TITLE_CORRECTION_TARGET_LANGUAGE)
            if (hasLegacyMode || hasLegacyTarget) {
                migration[KEY_REGION_SELECTION] = values.regionSelection().storageValue
                if (!values.containsKey(KEY_RESTORE_CJK_ORIGINAL_METADATA)) {
                    migration[KEY_RESTORE_CJK_ORIGINAL_METADATA] =
                        values.legacyRestoreCjkOriginalMetadataDefault()
                }
            }
        }
        // The retired master switch is deleted right after this call; record the
        // account-language override it implied while it is still readable.
        if (!values.containsKey(KEY_OVERRIDE_ACCOUNT_LANGUAGE) &&
            values.containsKey(KEY_TITLE_CORRECTION_ENABLED)
        ) {
            migration[KEY_OVERRIDE_ACCOUNT_LANGUAGE] =
                values.legacyOverrideAccountLanguageDefault()
        }
        if (migration.isEmpty()) return emptyMap()
        migration[KEY_SCHEMA_VERSION] = ModuleConstants.CONFIG_SCHEMA_VERSION
        return migration
    }

    private fun Map<String, *>.string(key: String): String = this[key] as? String ?: ""

    private fun Map<String, *>.long(key: String): Long? = when (val value = this[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    fun upgrade(
        storedValues: Map<String, *>,
        legacyValues: Map<String, *>,
    ): Map<String, Any>? {
        // Deliberately no AMTool key migration here: AMTool 1.2's
        // modify_locale / modify_locale_target_tag live in the private
        // "module_settings" file of the separate com.mukapp.applemusictool
        // app and are unreadable from AM++ (see AMTOOL_MODIFY_LOCALE_KEY).
        val storedVersion = storedValues.number(KEY_SCHEMA_VERSION)
        if (storedVersion != null && storedVersion >= ModuleConstants.CONFIG_SCHEMA_VERSION) return null
        val source = if (storedValues.hasSettingValue()) storedValues else legacyValues
        return encode(decode(source))
    }

    private fun Map<String, *>.boolean(key: String, default: Boolean): Boolean =
        this[key] as? Boolean ?: default

    private fun Map<String, *>.number(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, *>.hasSettingValue(): Boolean = settingKeys.any(::containsKey)

    private val settingKeys = setOf(
        KEY_DUAL_PANE,
        KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
        KEY_PHONE_LIQUID_GLASS,
        KEY_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
        KEY_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
        KEY_FUTURE_BLUR,
        KEY_CJK_KARAOKE_ANIMATION_ENABLED,
        KEY_NAVIGATION_COMPENSATION,
        KEY_FORCE_CELLULAR_DATA_ENTRY,
        KEY_LYRIC_BLUR_RADIUS_OFFSET,
        KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI,
        KEY_REGION_SELECTION,
        KEY_OVERRIDE_ACCOUNT_LANGUAGE,
        KEY_RESTORE_CJK_ORIGINAL_METADATA,
        KEY_LOCALIZED_METADATA_CACHE,
        KEY_METADATA_CACHE_CLEAR_GENERATION,
        // Retired keys still count as settings so an old store migrates its own
        // values instead of falling back to the legacy source.
        KEY_TITLE_CORRECTION_ENABLED,
        KEY_TITLE_CORRECTION_MODE,
        KEY_TITLE_CORRECTION_TARGET_LANGUAGE,
        KEY_CUSTOM_LYRICS_ENABLED,
        KEY_AUTOMATIC_LYRICS_ENABLED,
        KEY_ONLINE_LYRICS_SUPPLEMENT_ENABLED,
        KEY_ONLINE_LYRICS_SOURCE_NETEASE_ENABLED,
        KEY_ONLINE_LYRICS_SOURCE_QQ_ENABLED,
        KEY_ONLINE_LYRICS_SOURCE_KUWO_ENABLED,
        KEY_ONLINE_LYRICS_SOURCE_KUGOU_ENABLED,
        KEY_ONLINE_LYRICS_AUTOMATIC_ORDER_ENABLED,
        KEY_ONLINE_LYRICS_SOURCE_ORDER,
        KEY_ONLINE_LYRICS_GLOBAL_BEST_ENABLED,
        KEY_ONLINE_LYRICS_TRANSLATION_ENABLED,
        KEY_LEGACY_ONLINE_LYRIC_REPLACEMENT,
        KEY_FONT_ENABLED,
        KEY_FONT_FILE_ID,
        KEY_FONT_DISPLAY_NAME,
        KEY_FONT_SIZE_BYTES,
        KEY_FONT_SHA256,
        KEY_CUSTOM_LYRICS_MANIFEST,
    )

    private val indexPointerKeys = setOf(
        KEY_CUSTOM_LYRICS_INDEX_FILE_ID,
        KEY_CUSTOM_LYRICS_INDEX_GENERATION,
        KEY_CUSTOM_LYRICS_INDEX_SHA256,
        KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES,
    )

    private const val KEY_DUAL_PANE = "dual_pane_enabled"
    private const val KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET =
        "disable_editorial_video_on_tablet"
    private const val KEY_PHONE_LIQUID_GLASS = "phone_liquid_glass_enabled"
    private const val KEY_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP = "phone_liquid_glass_bottom_gap_dp"
    private const val KEY_PHONE_LIQUID_GLASS_PANEL_BLUR_DP = "phone_liquid_glass_panel_blur_dp"
    private const val KEY_FUTURE_BLUR = "future_blur_enabled"
    private const val KEY_CJK_KARAOKE_ANIMATION_ENABLED = "cjk_karaoke_animation_enabled"
    private const val KEY_NAVIGATION_COMPENSATION = "navigation_compensation_enabled"
    private const val KEY_FORCE_CELLULAR_DATA_ENTRY = "force_cellular_data_entry_enabled"
    private const val KEY_LYRIC_BLUR_RADIUS_OFFSET = "lyric_blur_radius_offset_px"
    private const val KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI = "apple_music_dpi_override_dpi"
    /** Retired v21: the fork-only master switch for the whole HLE runtime. */
    private const val KEY_TITLE_CORRECTION_ENABLED = "title_correction_enabled"
    private const val KEY_REGION_SELECTION = "region_selection"
    /** HLE's 「歌曲信息替换至设定地区语言」 switch. */
    private const val KEY_OVERRIDE_ACCOUNT_LANGUAGE = "override_account_language"
    /** Retired v20: the single picker that carried both region and title correction. */
    private const val KEY_TITLE_CORRECTION_MODE = "title_correction_mode"
    private const val KEY_RESTORE_CJK_ORIGINAL_METADATA = "restore_cjk_original_metadata"
    private const val KEY_LOCALIZED_METADATA_CACHE = "localized_metadata_cache"
    /** Monotonic one-shot signal written by the 「清空检索库」 action. */
    private const val KEY_METADATA_CACHE_CLEAR_GENERATION = "metadata_cache_clear_generation"
    private const val KEY_TITLE_CORRECTION_TARGET_LANGUAGE = "title_correction_target_language"
    private const val KEY_CUSTOM_LYRICS_ENABLED = "custom_lyrics_enabled"
    private const val KEY_AUTOMATIC_LYRICS_ENABLED = "automatic_lyrics_enabled"
    private const val KEY_ONLINE_LYRICS_SUPPLEMENT_ENABLED =
        "online_lyrics_supplement_enabled"
    private const val KEY_ONLINE_LYRICS_SOURCE_NETEASE_ENABLED =
        "online_lyrics_source_netease_enabled"
    private const val KEY_ONLINE_LYRICS_SOURCE_QQ_ENABLED =
        "online_lyrics_source_qq_enabled"
    private const val KEY_ONLINE_LYRICS_SOURCE_KUWO_ENABLED =
        "online_lyrics_source_kuwo_enabled"
    private const val KEY_ONLINE_LYRICS_SOURCE_KUGOU_ENABLED =
        "online_lyrics_source_kugou_enabled"
    private const val KEY_ONLINE_LYRICS_AUTOMATIC_ORDER_ENABLED =
        "online_lyrics_automatic_order_enabled"
    private const val KEY_ONLINE_LYRICS_SOURCE_ORDER = "online_lyrics_source_order"
    private const val KEY_ONLINE_LYRICS_GLOBAL_BEST_ENABLED =
        "online_lyrics_global_best_enabled"
    private const val KEY_ONLINE_LYRICS_TRANSLATION_ENABLED =
        "online_lyrics_translation_enabled"
    private const val KEY_LEGACY_ONLINE_LYRIC_REPLACEMENT = "online_lyric_replacement_enabled"
    private const val KEY_FONT_ENABLED = "lyrics_font_enabled"
    private const val KEY_FONT_FILE_ID = "lyrics_font_file_id"
    private const val KEY_FONT_DISPLAY_NAME = "lyrics_font_display_name"
    private const val KEY_FONT_SIZE_BYTES = "lyrics_font_size_bytes"
    private const val KEY_FONT_SHA256 = "lyrics_font_sha256"
    private const val KEY_CUSTOM_LYRICS_MANIFEST = "custom_lyrics_manifest"
    private const val KEY_CUSTOM_LYRICS_INDEX_FILE_ID = "custom_lyrics_index_file_id"
    private const val KEY_CUSTOM_LYRICS_INDEX_GENERATION = "custom_lyrics_index_generation"
    private const val KEY_CUSTOM_LYRICS_INDEX_SHA256 = "custom_lyrics_index_sha256"
    private const val KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES = "custom_lyrics_index_size_bytes"
    private const val KEY_SCHEMA_VERSION = "schema_version"

    /**
     * AMTool 1.2's own config keys, verified from AMTool_1.2.apk: the
     * `com.mukapp.applemusictool` module stores exactly `modify_locale` and
     * `modify_locale_target_tag` in ITS private `module_settings`
     * SharedPreferences and never writes them into the host's preferences.
     *
     * These constants are documentation only.  Android app-data isolation
     * means neither the AM++ settings process nor the Apple Music hook
     * process can open another package's private storage, so a real read
     * migration is not implementable: [decode] ignores the keys, [encode]
     * never emits them, and they must NOT be added to [settingKeys] — doing
     * so would make an AMTool-owned key look like a migrated AM++ setting.
     * A user moving from AMTool to AM++ re-enters the target language once.
     */
    internal const val AMTOOL_MODIFY_LOCALE_KEY = "modify_locale"
    internal const val AMTOOL_MODIFY_LOCALE_TARGET_TAG_KEY = "modify_locale_target_tag"
}
