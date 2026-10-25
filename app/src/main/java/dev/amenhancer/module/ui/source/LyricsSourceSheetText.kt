package dev.amenhancer.module.ui.source

import android.app.Activity
import java.util.Locale

/**
 * Chrome copy for the lyrics-source sheet.
 *
 * The source domain copy (lane names, option labels, provider brands, empty
 * states) is owned by [dev.amenhancer.module.lyrics.source.LyricSourceText] in
 * core. This object only adds the sheet's own controls and follows the same
 * locale rule as [dev.amenhancer.module.i18n.ModuleText]: Chinese on a zh
 * device, English otherwise.
 */
internal object LyricsSourceSheetText {

    /** The host locale, including Android per-app language overrides. */
    fun locale(activity: Activity): Locale {
        val locales = activity.resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    private fun pick(english: String, chinese: String, locale: Locale): String =
        if (locale.language.equals("zh", ignoreCase = true)) chinese else english

    private fun format(template: String, locale: Locale, vararg arguments: Any?): String =
        if (arguments.isEmpty()) template else String.format(locale, template, *arguments)

    fun title(locale: Locale): String = pick("Lyrics source", "歌词源", locale)

    fun close(locale: Locale): String = pick("Close", "关闭", locale)

    fun cancel(locale: Locale): String = pick("Cancel", "取消", locale)

    fun refresh(locale: Locale): String = pick("Refresh", "刷新", locale)

    fun retry(locale: Locale): String = pick("Retry", "重试", locale)

    fun change(locale: Locale): String = pick("Change", "更换", locale)

    fun collapse(locale: Locale): String = pick("Collapse", "收起", locale)

    fun use(locale: Locale): String = pick("Use", "使用", locale)

    fun search(locale: Locale): String = pick("Search", "搜索", locale)

    fun searching(locale: Locale): String = pick("Searching…", "正在搜索…", locale)

    fun searchTitle(locale: Locale): String = pick("Search other sources", "搜索其他来源", locale)

    fun searchSummary(locale: Locale): String =
        pick(
            "Artist and title default to the current song; edit them before searching",
            "歌手和歌名默认取当前曲，可修改后再搜索",
            locale,
        )

    fun artistHint(locale: Locale): String = pick("Artist", "歌手", locale)

    fun songTitleHint(locale: Locale): String = pick("Title", "歌名", locale)

    fun resetQuery(locale: Locale): String = pick("Use current song", "用当前曲", locale)

    fun queryRequired(locale: Locale): String =
        pick("Enter an artist or a title first", "请先填写歌手或歌名", locale)

    fun searchIdle(locale: Locale): String =
        pick("Enter an artist or a title, then tap Search", "填写歌手或歌名后点「搜索」", locale)

    fun noResults(locale: Locale): String = pick("No matching lyrics", "没有找到匹配的歌词", locale)

    fun noResultsHint(locale: Locale): String =
        pick("Try another artist or title", "换个歌手或歌名再试", locale)

    fun resultCount(locale: Locale, count: Int): String =
        format(pick("%1\$s result(s)", "共 %1\$s 条结果", locale), locale, count)

    fun matchScore(locale: Locale, score: Int): String =
        format(pick("%1\$s%% match", "匹配度 %1\$s%%", locale), locale, score)

    fun hideLaneTitle(locale: Locale): String =
        dev.amenhancer.module.lyrics.source.LyricSourceText.hiddenLabel(locale)

    fun hideLaneSummary(locale: Locale): String =
        pick("Hide this lane in the player", "在播放器里隐藏该车道", locale)

    fun useAppleLane(locale: Locale): String =
        pick("Use Apple Music", "使用 Apple 官方", locale)

    fun restoreApple(locale: Locale): String =
        pick("Restore Apple Music original", "恢复 Apple 原生", locale)

    fun restoreHint(locale: Locale): String =
        pick("Clears this song's source choices", "清除本曲的全部来源选择", locale)

    fun restoreAllNative(locale: Locale): String =
        pick("Every lane already uses Apple Music", "所有车道均使用 Apple 原生", locale)

    fun restoreConfirmTitle(locale: Locale): String = restoreApple(locale)

    fun restoreConfirmMessage(locale: Locale): String =
        pick(
            "Restore the Apple Music original lyrics, translation and romanization for this song?",
            "将把本曲的歌词、翻译、音译全部恢复为 Apple 原生。",
            locale,
        )

    fun restore(locale: Locale): String = pick("Restore", "恢复", locale)

    fun restored(locale: Locale): String =
        pick("Restored the Apple Music original", "已恢复 Apple 原生", locale)

    fun applied(locale: Locale): String = pick("Lyrics source updated", "已切换歌词源", locale)

    fun accepted(locale: Locale): String =
        pick("Accepted. The new source is loading…", "已受理，正在应用新的歌词源…", locale)

    fun hiddenApplied(locale: Locale): String = pick("Lane hidden", "已隐藏该车道", locale)

    fun shownApplied(locale: Locale): String = pick("Lane restored", "已恢复该车道", locale)

    fun failedFallback(locale: Locale): String =
        pick("Cannot change the lyrics source", "无法切换歌词源", locale)

    fun restoreFailed(locale: Locale, message: String): String =
        format(pick("Restore failed: %1\$s", "恢复失败：%1\$s", locale), locale, message)

    fun loadFailed(locale: Locale, message: String): String =
        format(pick("Cannot read lyrics sources: %1\$s", "读取歌词源失败：%1\$s", locale), locale, message)

    fun unavailable(locale: Locale): String =
        pick("Lyrics source service is unavailable", "歌词源服务不可用", locale)

    fun unavailableHint(locale: Locale): String =
        pick(
            "Reopen this sheet after the module is active in Apple Music",
            "请等模块在 Apple Music 生效后重新打开这里",
            locale,
        )

    fun noSong(locale: Locale): String = pick("No song is playing", "当前没有播放的歌曲", locale)

    fun noSongHint(locale: Locale): String =
        pick(
            "Play a song in Apple Music, then open this sheet from its lyrics page",
            "先在 Apple Music 播放歌曲，再从歌词页打开这里",
            locale,
        )

    fun currentSong(locale: Locale): String = pick("Current song", "当前歌曲", locale)

    fun sourceBadge(locale: Locale, provider: String): String =
        format(pick("Source · %1\$s", "来源 · %1\$s", locale), locale, provider)

    fun customisedBadge(locale: Locale): String = pick("Custom", "已自定义", locale)

    fun currentBadge(locale: Locale): String = pick("Current", "当前", locale)

    fun noCandidates(locale: Locale): String =
        pick("No cached source for this song", "本曲暂无缓存来源", locale)

    fun noCandidatesHint(locale: Locale): String =
        pick("Search the providers below", "可以在下面搜索在线来源", locale)

    fun searchFromLane(locale: Locale): String =
        pick("Search online sources…", "搜索在线来源…", locale)

    fun previewLoading(locale: Locale): String =
        pick("Loading preview…", "正在加载预览…", locale)

    fun previewEmpty(locale: Locale): String =
        pick("No preview for this source", "该来源没有可预览的歌词", locale)

    fun previewLines(locale: Locale, lines: Int): String =
        format(pick("Preview · first %1\$s lines", "预览 · 前 %1\$s 行", locale), locale, lines)

    fun wordPrecision(locale: Locale): String = pick("Word by word", "逐词", locale)

    fun linePrecision(locale: Locale): String = pick("Line by line", "逐行", locale)

    fun hasTranslation(locale: Locale): String = pick("Translation", "含翻译", locale)

    fun hasRomanization(locale: Locale): String = pick("Romanization", "含音译", locale)
}
