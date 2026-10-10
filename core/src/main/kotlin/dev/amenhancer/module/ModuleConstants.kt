package dev.amenhancer.module

object ModuleConstants {
    const val MODULE_PACKAGE = "dev.amenhancer.module"
    const val TARGET_PACKAGE = "com.apple.android.music"
    const val REMOTE_PREFERENCES_GROUP = "settings"
    /**
     * Single merged schema version carrying every feature area at once.
     *
     * 16 added the region-replacement extras (original-name restore + region
     * cache); 17 added the online lyric supplement toggle.  The integration
     * branch resolved the two independent 16/17 bumps to that combined 17.
     * The lyrics work then continued alone: 18 added the online lyric source
     * chain (one enable flag per source, the automatic-order flag, the stored
     * order and the global-best flag) and 19 adds the missing-translation
     * enrichment toggle.  The merged tree keeps the region keys and the whole
     * lyric chain under the single final version, 19.
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
     */
    const val CONFIG_SCHEMA_VERSION = 23

    const val FEATURE_DUAL_PANE = "dual_pane"
    const val FEATURE_EDITORIAL_VIDEO = "editorial_video"
    const val FEATURE_PHONE_LIQUID_GLASS = "phone_liquid_glass"
    const val FEATURE_FUTURE_BLUR = "future_blur"
    const val FEATURE_CJK_KARAOKE_ANIMATION = "cjk_karaoke_animation"
    const val FEATURE_LYRICS_TYPEFACE = "lyrics_typeface"
    const val FEATURE_CUSTOM_LYRICS = "custom_lyrics"
    const val FEATURE_NATIVE_LYRICS = "native_lyrics"

    /**
     * Identifies the package a device log came from. Bump it whenever a
     * release ships, and print it once per session so a log can never be
     * mistaken for a newer (or older) build than it is.
     */
    const val BUILD_TAG = "2026-10-10-apple-only-pronunciation"
    const val FEATURE_CURRENT_SONG_IDENTITY = "current_song_identity"
    const val FEATURE_CATALOG_LANGUAGE = "catalog_language"
    const val FEATURE_TITLE_CORRECTION = "title_correction"
    const val FEATURE_APPLE_MUSIC_DPI = "apple_music_dpi"
    const val FEATURE_CELLULAR_DATA_ENTRY = "cellular_data_entry"
    const val FEATURE_PLAYER_RECOVERY = "player_recovery"
}
