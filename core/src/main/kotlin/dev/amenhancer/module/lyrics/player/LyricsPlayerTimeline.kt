package dev.amenhancer.module.lyrics.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Paint state of one line's karaoke fill at one position.
 *
 * [wordIndex] is the word being sung (-1 when the line has no timed words), [wordProgress]
 * is 0–1 inside that word and [completedWords] counts the words that are already sung. The
 * renderer turns this into pixels: the fill edge is the width of the completed words plus
 * the width of the current word times its progress (spec §5.2).
 */
data class WordHighlight(
    val wordIndex: Int,
    val wordProgress: Float,
    val completedWords: Int,
) {
    val hasWords: Boolean get() = wordIndex >= 0

    companion object {
        val NONE = WordHighlight(wordIndex = -1, wordProgress = 0f, completedWords = 0)
    }
}

/**
 * Position → line/word mapping. Pure and stateless, so the renderer can call it every frame.
 *
 * Lines are assumed to be sorted by [PlayerLine.startMs] (the host document contract); gaps
 * between two lines keep the previous line current, which is what Apple Music does while an
 * instrumental tail of a line is still on screen.
 */
object LyricsPlayerTimeline {
    /** Index of the line that is current at [positionMs]; -1 for an empty document. */
    fun lineIndexAt(lines: List<PlayerLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines[0].startMs) return 0
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lines[mid].startMs <= positionMs) low = mid else high = mid - 1
        }
        return low
    }

    /** How much of [line] has been sung at [positionMs] (0–1). */
    fun lineProgress(line: PlayerLine, positionMs: Long): Float {
        val duration = line.durationMs
        if (duration <= 0L) return if (positionMs >= line.startMs) 1f else 0f
        return ((positionMs - line.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /** Karaoke fill of [line] at [positionMs]. */
    fun wordHighlight(line: PlayerLine, positionMs: Long): WordHighlight {
        val words = line.words
        if (words.isEmpty()) return WordHighlight.NONE
        var completed = 0
        var index = -1
        for (i in words.indices) {
            if (positionMs < words[i].endMs) {
                index = i
                break
            }
            completed = i + 1
        }
        if (index < 0) return WordHighlight(words.lastIndex, 1f, words.size)
        return WordHighlight(index, words[index].progressAt(positionMs), completed)
    }

    /**
     * Index of the last line that started at or before [positionMs], or 0 before the first
     * line. Convenience for hosts that only need the index; [lineIndexAt] is the real one.
     */
    fun startedLineIndex(lines: List<PlayerLine>, positionMs: Long): Int =
        lineIndexAt(lines, positionMs).coerceAtLeast(0)
}

/**
 * Position smoother between host samples.
 *
 * The host reports its MediaController position a few dozen milliseconds late and with a
 * little jitter; the page must still move its 100 ms animation head start smoothly. The
 * clock extrapolates from the last host sample, snaps on a real jump (seek, track change)
 * and otherwise corrects only a quarter of the observed error per sample, so a noisy
 * controller cannot make the line jump back and forth.
 */
class LyricsPlayerClock(private val leadMs: Long = LyricsPlayerMotion.HEADSTART_MS) {
    var isPlaying: Boolean = false
        private set

    /** Best estimate of the host position at the last [update]. */
    var positionMs: Long = 0L
        private set

    private var anchorNanos: Long = 0L
    private var initialized = false

    fun reset() {
        initialized = false
        positionMs = 0L
        isPlaying = false
        anchorNanos = 0L
    }

    /**
     * Feed one host sample. [hostPositionMs] null means "the host cannot tell" and freezes
     * extrapolation until the next real sample.
     */
    fun update(hostPositionMs: Long?, hostPlaying: Boolean, nowNanos: Long) {
        val sampled = hostPositionMs
        if (sampled == null) {
            initialized = false
            return
        }
        if (!initialized) {
            positionMs = sampled
            isPlaying = hostPlaying
            anchorNanos = nowNanos
            initialized = true
            return
        }
        val elapsedMs = (nowNanos - anchorNanos).coerceAtLeast(0L) / 1_000_000L
        val predicted = if (isPlaying) positionMs + elapsedMs else positionMs
        positionMs = when {
            // A real jump: seek, track change or a stalled controller catching up.
            abs(sampled - predicted) > RESYNC_THRESHOLD_MS -> sampled
            // Both paused: keep the frozen position instead of chasing rounding noise.
            !isPlaying && !hostPlaying -> predicted
            else -> predicted + ((sampled - predicted) * CORRECTION_FACTOR).roundToLong()
        }
        isPlaying = hostPlaying
        anchorNanos = nowNanos
    }

    /** Position the animation should show; while playing it leads the host by [leadMs]. */
    fun visualPositionMs(): Long = if (isPlaying) positionMs + leadMs else positionMs

    companion object {
        /** A sample this far from the extrapolation is a jump, not jitter. */
        const val RESYNC_THRESHOLD_MS = 80L

        /** Fraction of the remaining error folded in per sample. */
        const val CORRECTION_FACTOR = 0.25f
    }
}
