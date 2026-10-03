package dev.amenhancer.module.config

import com.juren233.hyperlyricsenhanced.common.RootConstants

/**
 * Region profile used by the title-correction feature.
 *
 * The master switch (`ModuleSettings.titleCorrectionEnabled`) remains the opt-in
 * gate.  When it is off no profile is installed and Apple Music follows the
 * account.  When it is on, one of the explicit profiles below owns the Apple Music
 * content storefront, the catalog request language and its cache namespace for the
 * lifetime of the Apple Music process.
 *
 * [ORIGINAL_HYPER] deliberately selects no region: it keeps the account storefront
 * and relies on `ModuleSettings.restoreCjkOriginalMetadata` to restore
 * original-region names.  Every other profile rewrites ordinary Apple Music catalog
 * traffic to that region.
 *
 * The numeric `X-Apple-Store-Front` mapping for each storefront lives with the
 * resolver (`AppleInternalCatalogResolver.localizedStorefrontHeaderValue`) so the
 * HTTP rewrite keeps a single source of truth.
 */
enum class TitleCorrectionMode(
    val storageValue: String,
    val displayName: String,
    val contentUiLanguageSelection: Int,
    val catalogStorefront: String?,
    val catalogLanguage: String?,
    val cacheNamespace: String,
) {
    ORIGINAL_HYPER(
        storageValue = "original_hyper",
        displayName = "不开启地区替换",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
        catalogStorefront = null,
        catalogLanguage = null,
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

    /**
     * Whether this profile rewrites ordinary Apple Music catalog traffic to
     * [catalogStorefront].  False for [ORIGINAL_HYPER], which only restores names.
     */
    val replacesRegion: Boolean get() = catalogStorefront != null

    companion object {
        fun decode(raw: String?): TitleCorrectionMode = values().firstOrNull {
            it.storageValue.equals(raw?.trim(), ignoreCase = true)
        } ?: ORIGINAL_HYPER

        /** Maps the v11 target-language setting into the new profile model. */
        fun fromLegacyTargetLanguage(raw: String?): TitleCorrectionMode = when (
            CatalogLanguagePolicy.normalize(raw)
        ) {
            "zh-CN" -> MAINLAND_CHINA
            "ja-JP" -> JAPAN
            else -> ORIGINAL_HYPER
        }
    }
}
