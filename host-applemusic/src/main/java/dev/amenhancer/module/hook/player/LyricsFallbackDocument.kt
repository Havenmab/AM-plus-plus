package dev.amenhancer.module.hook.player

import android.os.SystemClock
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.AppleTtmlLine
import dev.amenhancer.module.lyrics.online.AppleTtmlWord
import dev.amenhancer.module.lyrics.player.PlayerDocument
import dev.amenhancer.module.lyrics.player.PlayerLine
import dev.amenhancer.module.lyrics.player.PlayerWord

/**
 * Second document source of the bridge (spec §7.3 fallback), used when the host's own
 * native lyric model cannot be read.
 *
 * It resolves the current song through the shared [CurrentSongIdentityCache] and reads the
 * TTML this module itself stored for that song (the custom/imported lyrics document listed in
 * [TargetConfigClient.customLyricsManifest]) through the pure [AppleLyricTtmlReader]. Both
 * halves are module-owned: no obfuscated host symbol is involved, so the fallback keeps
 * working on a build whose lyric model drifted.
 *
 * Fail-open by construction: no current song, no manifest entry, an unreadable file or a
 * malformed document all return null, and the caller then leaves Apple's own page in place.
 */
internal class LyricsFallbackDocument(
    private val config: TargetConfigClient,
    private val currentSong: CurrentSongIdentityCache,
) {
    private var cachedSongId = 0L
    private var cachedDocument: PlayerDocument? = null
    private var lastAttemptMs = 0L
    private var lastReported: String? = null

    fun document(): PlayerDocument? {
        val songId = runCatching { currentSong.current()?.details?.appleMusicId }.getOrNull()
            ?.takeIf { it > 0L } ?: return null
        if (songId == cachedSongId) cachedDocument?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (now - lastAttemptMs < RETRY_MS) return null
        lastAttemptMs = now
        val entry = runCatching {
            config.customLyricsManifest().entries.firstOrNull { it.enabled && it.appleMusicId == songId }
        }.getOrNull() ?: return null
        val ttml = runCatching {
            config.openFile(entry.fileId)?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val lines = runCatching { AppleLyricTtmlReader.read(ttml) }.getOrNull().orEmpty()
        if (lines.isEmpty()) {
            report("custom lyrics document is empty")
            return null
        }
        val document = PlayerDocument(
            lines = lines.map(::line),
            identity = "custom:$songId:${entry.sha256}",
        )
        cachedSongId = songId
        cachedDocument = document
        lastReported = null
        return document
    }

    fun invalidate() {
        cachedDocument = null
        lastAttemptMs = 0L
    }

    private fun line(source: AppleTtmlLine): PlayerLine {
        val words = words(source.words).ifEmpty { listOf(PlayerWord(source.begin, source.end, source.text)) }
        val background = words(source.backgroundWords).ifEmpty {
            source.backgroundText?.takeIf { it.isNotEmpty() }
                ?.let { text -> listOf(PlayerWord(source.begin, source.end, text)) }
                .orEmpty()
        }
        return PlayerLine(
            startMs = source.begin,
            endMs = source.end,
            text = source.text,
            words = words,
            backgroundWords = background,
            translation = source.translation.orEmpty(),
        )
    }

    private fun words(source: List<AppleTtmlWord>): List<PlayerWord> = source.mapNotNull { word ->
        word.takeIf { it.end >= it.begin }?.let { PlayerWord(it.begin, it.end, it.text) }
    }

    private fun report(reason: String) {
        if (lastReported == reason) return
        lastReported = reason
        ModernXposedRuntime.log("lyrics player degraded: $reason")
    }

    private companion object {
        const val RETRY_MS = 1000L
    }
}
