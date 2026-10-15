package dev.amenhancer.module.config

/**
 * The request-side decisions the Apple Music host derives from HLE's three
 * metadata/region controls: the region picker, the account-language override switch
 * and the original-name restore switch.
 *
 * The region control owns everything about which region ordinary traffic resolves
 * against; the two switches are independent of it and of each other, which is what
 * makes 地区替换 and 替换中日韩歌曲信息为原地区原名 freely composable.
 */
data class RegionTitleRequestPlan(
    /** Content-UI language applied to MediaApi and the content-HTTP seams. */
    val contentUiLanguageSelection: Int,
    /** True when ordinary catalog traffic is redirected to the region storefront. */
    val rewritesCatalogRequests: Boolean,
    /** Catalog `Accept-Language` tag, or null to keep the account's own request language. */
    val catalogLanguage: String?,
    /** Cache namespace for the persistent region/original metadata stores. */
    val cacheNamespace: String,
    /**
     * HLE's `regionReplacementEnabled`:
     * `regionSelection != NONE && overrideAccountLanguage`.  When true the resolved
     * song/album/artist info is replaced with the selected region's language.
     */
    val overrideAccountLanguage: Boolean,
    /** True when per-song titles/albums may be resolved to the song's original region. */
    val probesOriginalMetadata: Boolean,
) {
    /**
     * HLE's `metadataLookupEnabled`: the metadata lookup runs whenever the selected
     * region replaces account-language info or the original-name restore is on.
     * This is the gate HLE's page puts on the 「创建检索库以提升替换体验」 switch.
     */
    val metadataLookupEnabled: Boolean
        get() = overrideAccountLanguage || probesOriginalMetadata

    /** True when any region/metadata feature would do work, so the runtime is worth installing. */
    val featureActive: Boolean
        get() = rewritesCatalogRequests || probesOriginalMetadata
}

/**
 * Pure projection of [RegionSelection] plus the two metadata switches onto
 * [RegionTitleRequestPlan].  Kept pure so every combination is verifiable on the
 * JVM; the host only consumes the result.
 */
object RegionTitleRequestPolicy {
    fun plan(
        region: RegionSelection,
        overrideAccountLanguage: Boolean,
        restoreCjkOriginalMetadata: Boolean,
    ): RegionTitleRequestPlan = RegionTitleRequestPlan(
        contentUiLanguageSelection = region.contentUiLanguageSelection,
        rewritesCatalogRequests = region.replacesRegion,
        catalogLanguage = region.catalogLanguage,
        cacheNamespace = region.cacheNamespace,
        // HLE: regionReplacementEnabled = contentUiLanguage != NONE && overrideAccountLanguage
        overrideAccountLanguage = region.replacesRegion && overrideAccountLanguage,
        probesOriginalMetadata = restoreCjkOriginalMetadata,
    )
}
