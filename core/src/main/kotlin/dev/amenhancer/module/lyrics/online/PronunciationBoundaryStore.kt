package dev.amenhancer.module.lyrics.online

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The boundary plans the playing song carries.
 *
 * This is the only thing the splice reads and the only place a plan lands: a plain map keyed by a
 * line's kana join, plus a revision the host uses to re-run the rows that are already inflated.
 * Every method is lock-free and answers from memory, so the playback path never waits for a request
 * — a line that has no plan simply keeps the rules' grouping.
 *
 * Lines with the same kana join share one entry on purpose: identical glyphs cannot have two
 * different groupings that matter, and the map then stays as small as the song's distinct lines.
 */
class PronunciationBoundaryStore {

    private val lines = ConcurrentHashMap<String, PronunciationBoundary.BoundaryLine>()
    private val revision = AtomicLong(0L)

    @Volatile
    private var songId: String? = null

    /** Installs one song's lines and reports whether anything changed. */
    fun install(songId: String, planLines: List<PronunciationBoundary.BoundaryLine>): Boolean {
        if (songId.isBlank() || planLines.isEmpty()) return false
        this.songId = songId
        lines.clear()
        for (line in planLines) lines[line.lineKey] = line
        revision.incrementAndGet()
        return true
    }

    /** The plan of one line, or null when this song has none for it. */
    fun planFor(lineKey: String): PronunciationBoundary.BoundaryLine? = lines[lineKey]

    /** Drops everything; the next song starts from the rules. */
    fun clear(): Boolean {
        if (lines.isEmpty() && songId == null) return false
        lines.clear()
        songId = null
        revision.incrementAndGet()
        return true
    }

    fun revision(): Long = revision.get()

    fun lineCount(): Int = lines.size

    fun currentSongId(): String? = songId
}
