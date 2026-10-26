package dev.amenhancer.module.lyrics.source

import java.util.Locale

/**
 * Every user-facing string the lyrics-source layer itself produces: option
 * labels, the secondary detail line, the lane titles and the "不显示" marker.
 *
 * It mirrors [dev.amenhancer.module.i18n.ModuleText]'s rule (Chinese for zh
 * devices, English otherwise) but stays local to this feature so the source
 * layer owns its own copy and the catalog can be tested with an explicit locale.
 * The sheet may render [LyricSourceOption.label] and
 * [LyricSourceOption.detail] as they are, and use [providerLabel] for a badge
 * when it wants the brand of the selected source instead of the option label
 * (which for an online candidate is the candidate's own title).
 */
object LyricSourceText {

    /** Picks the Chinese string on a zh device and the English one otherwise. */
    fun pick(english: String, chinese: String, locale: Locale = Locale.getDefault()): String =
        if (locale.language.equals("zh", ignoreCase = true)) chinese else english

    /** "歌词" / "翻译" / "音译". */
    fun laneLabel(lane: LyricLane, locale: Locale = Locale.getDefault()): String = when (lane) {
        LyricLane.ORIGINAL -> pick("Lyrics", "歌词", locale)
        LyricLane.TRANSLATION -> pick("Translation", "翻译", locale)
        LyricLane.ROMANIZATION -> pick("Romanization", "音译", locale)
    }

    /** The generic label of a source kind, used when a provider has no brand. */
    fun kindLabel(kind: LyricSourceKind, locale: Locale = Locale.getDefault()): String = when (kind) {
        LyricSourceKind.APPLE -> providerLabel(LyricSourceProviders.APPLE, locale)
        LyricSourceKind.ONLINE -> pick("Online", "在线", locale)
        LyricSourceKind.CUSTOM_FILE -> pick("Custom file", "自定义文件", locale)
        LyricSourceKind.OVERLAY_STORE -> pick("Stored lyrics", "已写入歌词", locale)
        LyricSourceKind.CACHE -> pick("Auto cache", "自动缓存", locale)
    }

    /**
     * The brand of a provider id; never blank. An unknown or empty id is
     * returned as-is (trimmed) so a future provider still renders something
     * instead of an empty badge.
     */
    fun providerLabel(providerId: String, locale: Locale = Locale.getDefault()): String =
        when (providerId.trim().lowercase(Locale.ROOT)) {
            LyricSourceProviders.APPLE -> "Apple Music"
            LyricSourceProviders.QQ -> pick("QQ Music", "QQ 音乐", locale)
            LyricSourceProviders.NETEASE -> pick("NetEase Cloud Music", "网易云音乐", locale)
            LyricSourceProviders.KUWO -> pick("Kuwo Music", "酷我音乐", locale)
            LyricSourceProviders.KUGOU -> pick("Kugou Music", "酷狗音乐", locale)
            LyricSourceProviders.LUNABEAT -> "Lunabeat"
            LyricSourceProviders.AMLL -> "AMLL"
            LyricSourceProviders.AM_LYRICS -> "AM-Lyrics"
            LyricSourceProviders.FILE -> pick("Custom file", "自定义文件", locale)
            LyricSourceProviders.CACHE -> pick("Auto cache", "自动缓存", locale)
            LyricSourceProviders.OVERLAY -> pick("Stored lyrics", "已写入歌词", locale)
            else -> providerId.trim().ifEmpty { pick("Unknown source", "未知来源", locale) }
        }

    /** The label of a custom-lyrics index record: its origin brand, or the generic file label. */
    fun customFileLabel(
        providerId: String,
        userEdited: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val brand = if (providerId == LyricSourceProviders.FILE) {
            pick("Custom file", "自定义文件", locale)
        } else {
            providerLabel(providerId, locale)
        }
        return if (userEdited) pick(brand + " (edited)", brand + "（已编辑）", locale) else brand
    }

    /** The "不显示" choice offered by the supplement lanes' toggle. */
    fun hiddenLabel(locale: Locale = Locale.getDefault()): String = pick("Hidden", "不显示", locale)

    /** The secondary line of Apple's option: precision plus the lanes Apple also carries. */
    fun appleDetail(
        hasWords: Boolean,
        hasTranslation: Boolean,
        hasRomanization: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val parts = mutableListOf(
            if (hasWords) pick("Word by word", "逐词", locale) else pick("Line by line", "逐行", locale),
        )
        if (hasTranslation) parts += pick("with translation", "带翻译", locale)
        if (hasRomanization) parts += pick("with romanization", "带音译", locale)
        return parts.joinToString(" · ")
    }

    /** The secondary line of the overlay-store option. */
    fun overlayDetail(
        lane: LyricLane,
        providerId: String,
        locale: Locale = Locale.getDefault(),
    ): String {
        val source = providerLabel(providerId, locale)
        return pick(
            "Already published · " + source,
            "本曲已写入的" + laneLabel(lane, locale) + " · " + source,
            locale,
        )
    }

    /** The secondary line of a cache option. */
    fun cacheDetail(displayName: String, locale: Locale = Locale.getDefault()): String =
        displayName.ifBlank { pick("Auto cache", "自动缓存", locale) }

    /**
     * The secondary line of a custom-lyrics index option.
     *
     * A document downloaded from a provider says so ("已保存到本地"): the file is
     * local, but the user picked an online source and the sheet must not present it
     * as something they wrote. A manual document keeps its own name, and the auto
     * cache keeps its cache name.
     */
    fun customFileDetail(
        providerId: String,
        displayName: String,
        locale: Locale = Locale.getDefault(),
    ): String = when (providerId) {
        LyricSourceProviders.CACHE ->
            displayName.ifBlank { pick("Auto cache", "自动缓存", locale) }
        LyricSourceProviders.FILE ->
            displayName.ifBlank { pick("Custom lyrics", "自定义歌词", locale) }
        else -> pick("Saved locally", "已保存到本地", locale)
    }

    /**
     * The secondary line of the placeholder option the catalog synthesizes for a
     * stored online selection whose search result is no longer in the snapshot.
     */
    fun appliedOnlineDetail(lane: LyricLane, locale: Locale = Locale.getDefault()): String =
        pick(
            "Applied online source",
            "已应用的在线" + laneLabel(lane, locale),
            locale,
        )

    /** The secondary line of an online candidate: artist and album, whichever exist. */
    fun onlineDetail(
        artist: String,
        album: String,
        locale: Locale = Locale.getDefault(),
    ): String = listOf(artist.trim(), album.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .ifEmpty { pick("Online lyrics", "在线歌词", locale) }

    /** Why the preview is unavailable, when the host does not implement it. */
    fun previewUnavailable(locale: Locale = Locale.getDefault()): String =
        pick("Preview is unavailable for this source", "该来源暂不支持预览", locale)

    /** Why a lane cannot be hidden (a host that does not implement the switch). */
    fun hideUnavailable(locale: Locale = Locale.getDefault()): String =
        pick("This lane cannot be hidden yet", "暂时无法隐藏该车道", locale)

    /** Shown when a lane offers nothing at all. */
    fun noOptionsLabel(locale: Locale = Locale.getDefault()): String =
        pick("No source available", "暂无可选来源", locale)
}
