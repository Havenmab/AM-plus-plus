package dev.amenhancer.module.config

import com.juren233.hyperlyricsenhanced.common.RootConstants

/**
 * Region replacement selection for Apple Music's online content.
 *
 * This is HLE's 「将Apple Music改成其他地区」 picker: [displayName] is HLE's exact
 * option label and [contentUiLanguageSelection] is HLE's
 * `APPLE_MUSIC_CONTENT_UI_LANGUAGE_*` value, so the two pages stay in sync.
 * [NONE] leaves the account's own region and language in force; every other value
 * rewrites the catalog request storefront/language and the content-HTTP seams.
 *
 * It is deliberately independent of the metadata switches
 * (`ModuleSettings.overrideAccountLanguage` and
 * `ModuleSettings.restoreCjkOriginalMetadata`): the controls compose in any
 * combination and neither projection reads the other.
 *
 * The numeric `X-Apple-Store-Front` mapping for each storefront lives with the
 * resolver (`AppleInternalCatalogResolver.localizedStorefrontHeaderValue`) so the
 * HTTP rewrite keeps a single source of truth.
 */
enum class RegionSelection(
    val storageValue: String,
    val displayName: String,
    val contentUiLanguageSelection: Int,
    val catalogStorefront: String?,
    val catalogLanguage: String?,
    val cacheNamespace: String,
) {
    NONE(
        storageValue = "none",
        // HLE option_apple_music_content_ui_language_none
        displayName = "不开启",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
        catalogStorefront = null,
        catalogLanguage = null,
        // Kept from the retired no-region profile so a migrated installation keeps
        // reading the persistent original-metadata cache database it already has.
        cacheNamespace = "original_hyper_v1",
    ),
    MAINLAND_CHINA(
        storageValue = "mainland_china",
        displayName = "简体中文（中国）",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN,
        catalogStorefront = "cn",
        catalogLanguage = "zh-CN",
        cacheNamespace = "cn_v1",
    ),
    ZH_HANS_US(
        storageValue = "zh_hans_us",
        displayName = "简体中文（美国）",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US,
        catalogStorefront = "us",
        catalogLanguage = "zh-Hans",
        cacheNamespace = "us_v1",
    ),
    HONG_KONG(
        storageValue = "hong_kong",
        displayName = "繁体中文（香港）",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK,
        catalogStorefront = "hk",
        catalogLanguage = "zh-HK",
        cacheNamespace = "hk_v1",
    ),
    TAIWAN(
        storageValue = "taiwan",
        displayName = "繁体中文（台湾）",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW,
        catalogStorefront = "tw",
        catalogLanguage = "zh-TW",
        cacheNamespace = "tw_v1",
    ),
    KOREA(
        storageValue = "korea",
        displayName = "韩语（韩国）",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR,
        catalogStorefront = "kr",
        catalogLanguage = "ko-KR",
        cacheNamespace = "kr_v1",
    ),
    JAPAN(
        storageValue = "japan",
        displayName = "日语（日本）",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP,
        catalogStorefront = "jp",
        catalogLanguage = "ja-JP",
        cacheNamespace = "jp_v1",
    );

    /** Whether this selection rewrites ordinary Apple Music traffic to [catalogStorefront]. */
    val replacesRegion: Boolean get() = catalogStorefront != null

    companion object {
        /** The retired single picker's storage value that also meant "no region". */
        const val LEGACY_NO_REGION_STORAGE_VALUE = "original_hyper"

        fun decode(raw: String?): RegionSelection = values().firstOrNull {
            it.storageValue.equals(raw?.trim(), ignoreCase = true)
        } ?: NONE

        /**
         * Maps the retired `title_correction_mode` picker value onto the region
         * control.  It carried both concepts, so only the region half is read here;
         * the title-correction half migrates from `restore_cjk_original_metadata`.
         */
        fun fromLegacyTitleCorrectionMode(raw: String?): RegionSelection {
            val normalized = raw?.trim().orEmpty()
            if (normalized.isEmpty() ||
                normalized.equals(LEGACY_NO_REGION_STORAGE_VALUE, ignoreCase = true)
            ) {
                return NONE
            }
            return decode(normalized)
        }

        /** Maps the v11 target-language setting into the region model. */
        fun fromLegacyTargetLanguage(raw: String?): RegionSelection = when (
            CatalogLanguagePolicy.normalize(raw)
        ) {
            "zh-CN" -> MAINLAND_CHINA
            "ja-JP" -> JAPAN
            else -> NONE
        }
    }
}
