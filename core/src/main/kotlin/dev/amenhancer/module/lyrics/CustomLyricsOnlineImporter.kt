package dev.amenhancer.module.lyrics

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.model.CustomLyricsSources

sealed interface CustomLyricsOnlineImportResult {
    data class Imported(
        val ttml: String,
        val source: String,
        /** True when the AMLL TTML format was rewritten into the Apple Music format. */
        val reformatted: Boolean = false,
    ) : CustomLyricsOnlineImportResult

    data class Failed(val message: String) : CustomLyricsOnlineImportResult
}

/** User-triggered online imports. Playback hooks never call this class. */
class CustomLyricsOnlineImporter(
    private val fetchAmll: (Long) -> String?,
    private val fetchAmLyrics: (Long) -> String?,
    private val fetchLunabeat: (Long) -> String?,
) {
    fun importAmll(appleMusicId: Long): CustomLyricsOnlineImportResult {
        if (appleMusicId <= 0L) return CustomLyricsOnlineImportResult.Failed(ModuleText.MUSIC_ID_POSITIVE_REQUIRED.text())
        val fetched = runCatching { fetchAmll(appleMusicId) }.getOrNull()
            ?: return CustomLyricsOnlineImportResult.Failed(ModuleText.AMLL_TTML_MISSING.text())
        // AMLL serves its own TTML format; reformat it before Apple's parser sees it.
        val conversion = AmllTtmlFormatConverter.toAppleFormat(fetched)
        val ttml = conversion.ttml.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed(ModuleText.AMLL_TTML_MISSING.text())
        return CustomLyricsOnlineImportResult.Imported(
            ttml = ttml,
            source = CustomLyricsSources.AMLL,
            reformatted = conversion.converted,
        )
    }

    fun importAmLyrics(appleMusicId: Long): CustomLyricsOnlineImportResult {
        if (appleMusicId <= 0L) return CustomLyricsOnlineImportResult.Failed(
            ModuleText.MUSIC_ID_POSITIVE_REQUIRED.text(),
        )
        val ttml = runCatching { fetchAmLyrics(appleMusicId) }.getOrNull()
            ?.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed(ModuleText.GITHUB_TTML_MISSING.text())
        return CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.AM_LYRICS)
    }

    fun importLunabeat(appleMusicId: Long): CustomLyricsOnlineImportResult {
        if (appleMusicId <= 0L) return CustomLyricsOnlineImportResult.Failed(
            ModuleText.MUSIC_ID_POSITIVE_REQUIRED.text(),
        )
        val ttml = runCatching { fetchLunabeat(appleMusicId) }.getOrNull()
            ?.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed(ModuleText.LUNABEAT_TTML_MISSING.text())
        return CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.LUNABEAT)
    }
}
