package dev.amenhancer.module.model

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.RegionSelection

data class ModuleSettings(
    val dualPaneEnabled: Boolean = true,
    /** Legacy storage key; Editorial Video suppression now follows dualPaneEnabled. */
    val disableEditorialVideoOnTablet: Boolean = true,
    val phoneLiquidGlassEnabled: Boolean = false,
    /** Distance in dp between the bottom-bar capsule and the screen bottom; glass-gated. */
    val phoneLiquidGlassBottomGapDp: Int = EnhancementDefaults.GLASS_BOTTOM_DP,
    /** Backdrop blur radius in dp shared by the nav panel and the mini-player. */
    val phoneLiquidGlassPanelBlurDp: Int = EnhancementDefaults.GLASS_PANEL_BLUR_DP.toInt(),
    val futureBlurEnabled: Boolean = true,
    /** Enables the native rush-gradient adaptation for CJK karaoke lyrics. */
    val cjkKaraokeAnimationEnabled: Boolean = true,
    val navigationCompensationEnabled: Boolean = false,
    /** Restores native Data settings and the app's cellular availability predicate. */
    val forceCellularDataEntryEnabled: Boolean = false,
    val lyricBlurRadiusOffsetPx: Int = 0,
    /** Fixed logical density for Apple Music; 0 follows the system density. */
    val appleMusicDpiOverrideDpi: Int = FOLLOW_SYSTEM_APPLE_MUSIC_DPI,
    /**
     * Which region's storefront/language content requests are resolved against;
     * [RegionSelection.NONE] keeps the account's own region.  This is HLE's
     * 「将Apple Music改成其他地区」 picker: choosing a region applies that content-UI
     * language to ordinary browsing, independently of the two metadata switches.
     */
    val regionSelection: RegionSelection = RegionSelection.NONE,
    /**
     * HLE's 「歌曲信息替换至设定地区语言」 switch.  Only meaningful together with a
     * chosen region: it replaces song/album/artist info with the selected region's
     * language.  Off by default, exactly like HLE's
     * `DEFAULT_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE`.
     *
     * Together with [regionSelection] it forms HLE's `regionReplacementEnabled`:
     * `regionSelection != NONE && overrideAccountLanguage`.
     */
    val overrideAccountLanguage: Boolean = false,
    /**
     * HLE's 「替换中日韩歌曲信息为原地区原名」 switch: restores CJK songs to their
     * original-region names.  Independent of [regionSelection] and
     * [overrideAccountLanguage]: it composes with either.
     *
     * Off by default, like HLE's
     * `DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA`.  A store that
     * predates the split derives it from the retired picker instead
     * ([dev.amenhancer.module.config.ModuleSettingsSchema]), so migrating users
     * keep the behaviour they had.
     */
    val restoreCjkOriginalMetadata: Boolean = false,
    /**
     * Keeps the region/original metadata lookup results in SQLite so a cold start
     * does not have to re-query Apple Music's catalog.
     */
    val localizedMetadataCache: Boolean = true,
    val customLyricsEnabled: Boolean = false,
    /** Enables background AMLL/Lunabeat/user-repository lyric completion. */
    val automaticLyricsEnabled: Boolean = true,
    /**
     * Enables the online search supplement, which is prepended to the automatic
     * chain for tracks Apple Music reports as having no lyrics. Defaults off so
     * the ported online sources stay invisible until a user opts in. When it is
     * on, every source enabled below participates.
     */
    val onlineLyricsSupplementEnabled: Boolean = false,
    /** Netease Cloud Music participates in the online supplement chain. */
    val onlineLyricsSourceNeteaseEnabled: Boolean = true,
    /** QQ Music participates in the online supplement chain. */
    val onlineLyricsSourceQqEnabled: Boolean = true,
    /** Kuwo Music participates in the online supplement chain. */
    val onlineLyricsSourceKuwoEnabled: Boolean = true,
    /** Kugou Music participates in the online supplement chain. */
    val onlineLyricsSourceKugouEnabled: Boolean = true,
    /**
     * Uses [OnlineLyricSources.DEFAULT_ORDER] rather than
     * [onlineLyricsSourceOrder]; defaults on so the built-in order keeps the
     * ported sources' original priority.
     */
    val onlineLyricsAutomaticOrderEnabled: Boolean = true,
    /** User order of source ids when [onlineLyricsAutomaticOrderEnabled] is off. */
    val onlineLyricsSourceOrder: String = OnlineLyricSources.DEFAULT_ORDER_STORAGE,
    /**
     * Scores every candidate instead of keeping the first one that passes the
     * score floor; defaults off so the ported first-passing behaviour stays.
     */
    val onlineLyricsGlobalBestEnabled: Boolean = false,
    /**
     * Fills in a missing lyric translation from the online chain while Apple's
     * document is showing. Defaults off, and only the translation lane is
     * touched: Apple's own translation always wins, and a document that needs
     * no translation is left exactly as it is.
     */
    val onlineLyricsTranslationEnabled: Boolean = false,
    val fontManifest: LyricsFontManifest = LyricsFontManifest.disabled(),
    val customLyricsManifest: CustomLyricsManifest = CustomLyricsManifest.empty(),
    val schemaVersion: Int = ModuleConstants.CONFIG_SCHEMA_VERSION,
) {
    /** Reads the per-source flag that gates [source] in the online chain. */
    fun onlineLyricsSourceEnabled(source: String): Boolean = when (source) {
        CustomLyricsSources.NETEASE -> onlineLyricsSourceNeteaseEnabled
        CustomLyricsSources.QQ -> onlineLyricsSourceQqEnabled
        CustomLyricsSources.KUWO -> onlineLyricsSourceKuwoEnabled
        CustomLyricsSources.KUGOU -> onlineLyricsSourceKugouEnabled
        else -> false
    }

    /** Returns a copy with the per-source flag for [source] replaced. */
    fun withOnlineLyricsSourceEnabled(source: String, enabled: Boolean): ModuleSettings =
        when (source) {
            CustomLyricsSources.NETEASE -> copy(onlineLyricsSourceNeteaseEnabled = enabled)
            CustomLyricsSources.QQ -> copy(onlineLyricsSourceQqEnabled = enabled)
            CustomLyricsSources.KUWO -> copy(onlineLyricsSourceKuwoEnabled = enabled)
            CustomLyricsSources.KUGOU -> copy(onlineLyricsSourceKugouEnabled = enabled)
            else -> this
        }

    companion object {
        const val MIN_LYRIC_BLUR_RADIUS_OFFSET_PX = -10
        const val MAX_LYRIC_BLUR_RADIUS_OFFSET_PX = 10
        const val FOLLOW_SYSTEM_APPLE_MUSIC_DPI = 0
        const val MIN_APPLE_MUSIC_DPI = 160
        const val MAX_APPLE_MUSIC_DPI = 640
        const val MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP = 0
        const val MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP = 48
        const val MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP = 0
        const val MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP = 24

        fun isValidAppleMusicDpi(value: Int): Boolean =
            value == FOLLOW_SYSTEM_APPLE_MUSIC_DPI || value in MIN_APPLE_MUSIC_DPI..MAX_APPLE_MUSIC_DPI

        fun normalizeAppleMusicDpi(value: Int): Int =
            value.takeIf(::isValidAppleMusicDpi) ?: FOLLOW_SYSTEM_APPLE_MUSIC_DPI

        fun normalizePhoneLiquidGlassBottomGapDp(value: Int): Int =
            value.coerceIn(MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP, MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP)

        fun normalizePhoneLiquidGlassPanelBlurDp(value: Int): Int =
            value.coerceIn(MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP, MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP)
    }
}

/** Shared, Android-free description of the font file selected by the user. */
data class LyricsFontManifest(
    val enabled: Boolean = false,
    val fileId: String = "",
    val displayName: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
) {
    companion object {
        fun disabled(): LyricsFontManifest = LyricsFontManifest()
    }
}

/** One user-managed Apple Music ID -> TTML file mapping. */
data class CustomLyricsEntry(
    val appleMusicId: Long,
    val displayName: String,
    val fileId: String,
    val sizeBytes: Long,
    val sha256: String,
    val source: String,
    val enabled: Boolean = true,
)
/** Small index shared through remote preferences; TTML bodies stay in remote files. */
data class CustomLyricsManifest(
    val entries: List<CustomLyricsEntry> = emptyList(),
) {
    companion object {
        fun empty(): CustomLyricsManifest = CustomLyricsManifest()
    }
}

object CustomLyricsSources {
    const val MANUAL = "manual"
    const val AUTO_CACHE = "auto-cache"
    const val AMLL = "amll-ttml-db"
    const val AM_LYRICS = "am-lyrics"
    const val LUNABEAT = "lunabeat-ttml-hub"
    /** Search-based Kuwo supplement; the source name published for its lyrics. */
    const val KUWO = "kuwo"
    /** Search-based Netease Cloud Music supplement. */
    const val NETEASE = "netease"
    /** Search-based QQ Music supplement. */
    const val QQ = "qq"
    /** Search-based Kugou Music supplement. */
    const val KUGOU = "kugou"
}

/**
 * Plain-data description of the selectable online lyric chain.
 *
 * Deliberately lives in the model package with no provider, transport, or host
 * dependency: the settings codec and the embedded settings UI both need the ids
 * and the default order, and the UI must not import the network package.
 */
object OnlineLyricSources {
    /** HyperLyricsEnhanced's `OnlineTranslationSourcePreferences.defaultOrder`. */
    val DEFAULT_ORDER: List<String> = listOf(
        CustomLyricsSources.NETEASE,
        CustomLyricsSources.QQ,
        CustomLyricsSources.KUWO,
        CustomLyricsSources.KUGOU,
    )

    /** Storage form of [DEFAULT_ORDER]; the default of the order setting. */
    val DEFAULT_ORDER_STORAGE: String = DEFAULT_ORDER.joinToString(",")

    /** Every selectable source in the built-in default order. */
    val ALL: List<String> = DEFAULT_ORDER

    /**
     * Canonicalizes a stored order: keeps the known ids in their written order
     * and appends any missing default, so the result is always a permutation of
     * [DEFAULT_ORDER] and is never empty.
     */
    fun normalizeOrder(raw: String): List<String> {
        val parsed = raw.split(',')
            .map(String::trim)
            .filter { it in DEFAULT_ORDER }
            .distinct()
        return parsed + DEFAULT_ORDER.filterNot(parsed::contains)
    }
}

enum class FeatureState {
    ACTIVE,
    DISABLED,
    UNSUPPORTED,
    DEGRADED,
    FAILED,
}

data class FeatureHealth(
    val feature: String,
    val state: FeatureState,
    val message: String,
    val targetVersion: String = "",
)
