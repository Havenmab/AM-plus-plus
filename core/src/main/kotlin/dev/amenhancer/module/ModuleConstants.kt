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
     */
    const val CONFIG_SCHEMA_VERSION = 19

    const val FEATURE_DUAL_PANE = "dual_pane"
    const val FEATURE_EDITORIAL_VIDEO = "editorial_video"
    const val FEATURE_PHONE_LIQUID_GLASS = "phone_liquid_glass"
    const val FEATURE_FUTURE_BLUR = "future_blur"
    const val FEATURE_CJK_KARAOKE_ANIMATION = "cjk_karaoke_animation"
    const val FEATURE_LYRICS_TYPEFACE = "lyrics_typeface"
    const val FEATURE_CUSTOM_LYRICS = "custom_lyrics"
    const val FEATURE_CURRENT_SONG_IDENTITY = "current_song_identity"
    const val FEATURE_CATALOG_LANGUAGE = "catalog_language"
    const val FEATURE_TITLE_CORRECTION = "title_correction"
    const val FEATURE_APPLE_MUSIC_DPI = "apple_music_dpi"
    const val FEATURE_CELLULAR_DATA_ENTRY = "cellular_data_entry"
}
