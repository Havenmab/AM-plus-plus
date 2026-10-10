package dev.amenhancer.module.ui

import android.net.Uri
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateProgress
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.ModuleSettings

/** Small facade so the host UI never depends on a particular storage backend. */
internal interface EmbeddedSettingsController {
    fun currentSettings(): ModuleSettings

    fun saveOrdinarySettings(settings: ModuleSettings): Boolean

    fun currentSongDetails(): CurrentSongDetails? = null
    fun lyricsEntries(): List<CustomLyricsEntry> = emptyList()
    fun readLyrics(appleMusicId: Long): String? = null
    /** Reads and validates a SAF TTML document without persisting it. */
    fun readTtml(uri: Uri): String? = null
    fun saveLyrics(draft: CustomLyricsDraft, replacingAppleMusicId: Long? = null): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun saveLyrics(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long> = emptyList(),
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("歌词管理不可用")
    fun setLyricsEnabled(appleMusicId: Long, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun setLyricsEnabled(appleMusicIds: List<Long>, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun deleteLyrics(appleMusicId: Long): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun deleteLyrics(appleMusicIds: List<Long>): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    /** Deletes every custom-lyrics entry and its stored file. */
    fun clearLyrics(): EmbeddedActionResult = EmbeddedActionResult.Failed("歌词管理不可用")
    fun importFont(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed("字体导入不可用")
    fun clearFont(): EmbeddedActionResult = EmbeddedActionResult.Failed("字体管理不可用")
    fun importTtml(
        uri: Uri,
        appleMusicId: Long,
        displayName: String,
        replacingAppleMusicId: Long? = null,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("歌词导入不可用")
    fun backupLyrics(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed("备份不可用")
    fun restoreLyrics(uri: Uri, policy: CustomLyricsRestorePolicy): EmbeddedActionResult =
        EmbeddedActionResult.Failed("恢复不可用")
    fun importOnlineLyrics(
        source: EmbeddedOnlineSource,
        appleMusicId: Long,
        displayName: String,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("在线导入不可用")

    fun updateLyrics(
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsUpdateProgress) -> Unit = {},
    ): CustomLyricsUpdateResult = CustomLyricsUpdateResult.Failed("歌词更新不可用")
}

internal class EmbeddedSessionSettingsController(
    private val session: EmbeddedConfigurationSession,
) : EmbeddedSettingsController {
    override fun currentSettings(): ModuleSettings = session.settings()

    override fun saveOrdinarySettings(settings: ModuleSettings): Boolean = session.saveSettings(settings)
}

internal fun interface EmbeddedSafSelectionHandler {
    fun onSelected(operation: EmbeddedSafOperation, uri: Uri)
}

