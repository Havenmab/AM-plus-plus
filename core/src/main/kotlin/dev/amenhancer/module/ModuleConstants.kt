package dev.amenhancer.module

object ModuleConstants {
    const val MODULE_PACKAGE = "dev.amenhancer.module"
    const val TARGET_PACKAGE = "com.apple.android.music"
    const val REMOTE_PREFERENCES_GROUP = "settings"
    /**
     * Single merged schema version carrying every feature area at once.
     *
     * This fork's line numbered its own keys from 16 up: 16 added the
     * region-replacement extras (original-name restore + region cache); 17 the
     * online lyric supplement toggle; 18 the online lyric source chain (one
     * enable flag per source, the automatic-order flag, the stored order and the
     * global-best flag); 19 the missing-translation enrichment toggle.  The
     * merged tree kept the region keys and the whole lyric chain under 19.
     *
     * 20 splits the retired single `title_correction_mode` picker into the
     * `region_selection` control plus the existing `restore_cjk_original_metadata`
     * title-correction switch.  The migration rewrites the old value so an
     * existing installation keeps the exact behaviour it had.
     *
     * 21 retires the fork-only `title_correction_enabled` master switch and adds
     * HLE's `override_account_language` switch, so the region/metadata page holds
     * exactly HLE's four controls with HLE's own visibility and combination rules.
     * The migration carries the retired master over onto the new switch for a
     * store that already selected a region, and leaves the original-name switch
     * off by default as HLE does.
     *
     * 22 adds `metadata_cache_clear_generation`, the monotonic one-shot signal the
     * 「清空检索库」 action uses to ask the live runtime to drop the persistent
     * region/original metadata caches.  It defaults to 0, so an upgrading store
     * starts with no pending clear.
     *
     * 23 adds HLE's 「不显示国语歌拼音」 switch
     * (`online_lyrics_hide_mandarin_pinyin`).  It defaults off, so an upgrading
     * store keeps publishing the pronunciation lane exactly as before; the
     * existing `online_lyrics_translation_enabled` key is NOT renamed and now
     * covers the pronunciation lane too, so no migration is needed for it.
     *
     * The upstream 1.6.6 sync numbered its own additions 16 (the LLM lyrics
     * fallback: enabled + OpenAI-compatible base URL / API key / model / thinking
     * toggle) and 17 (`dual_pane_hide_status_bar`, so hiding the status bar in the
     * dual-pane player is optional).  Both sides are additive and defaulted, so
     * the merged tree publishes the single combined version 24: every key is a
     * new defaulted key, and the only migration is the existing region/lyrics one
     * which re-encodes decoded values and therefore never rewrites a stored
     * setting.  A store written by either line (17 or 23) upgrades to 24 with its
     * values intact.
     *
     * 25 adds the two lyrics typography sliders (`lyrics_font_scale_percent` and
     * `lyrics_line_spacing_scale_percent`).  Both are new defaulted keys: 100 keeps
     * Apple Music's own font size and line spacing, so an upgrading store renders
     * exactly what it rendered before and no migration step is needed.
     */
    const val CONFIG_SCHEMA_VERSION = 25

    const val FEATURE_DUAL_PANE = "dual_pane"
    const val FEATURE_EDITORIAL_VIDEO = "editorial_video"
    const val FEATURE_PHONE_LIQUID_GLASS = "phone_liquid_glass"
    const val FEATURE_FUTURE_BLUR = "future_blur"
    const val FEATURE_CJK_KARAOKE_ANIMATION = "cjk_karaoke_animation"
    const val FEATURE_LYRIC_WRAP_SWEEP = "lyric_wrap_sweep"
    const val FEATURE_LYRICS_TYPEFACE = "lyrics_typeface"
    const val FEATURE_CUSTOM_LYRICS = "custom_lyrics"
    const val FEATURE_NATIVE_LYRICS = "native_lyrics"

    /**
     * Identifies the package a device log came from. Bump it whenever a
     * release ships, and print it once per session so a log can never be
     * mistaken for a newer (or older) build than it is.
     */
    const val BUILD_TAG = "2026-10-22-1.6.6-lyrics-typography-1609"
    const val FEATURE_CURRENT_SONG_IDENTITY = "current_song_identity"
    const val FEATURE_CATALOG_LANGUAGE = "catalog_language"
    const val FEATURE_TITLE_CORRECTION = "title_correction"
    const val FEATURE_APPLE_MUSIC_DPI = "apple_music_dpi"
    const val FEATURE_CELLULAR_DATA_ENTRY = "cellular_data_entry"
    const val FEATURE_PLAYER_RECOVERY = "player_recovery"
    const val FEATURE_STARTUP_PAGE_PREWARM = "startup_page_prewarm"
}
