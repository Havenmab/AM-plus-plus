package dev.amenhancer.module.ui.source

import dev.amenhancer.module.lyrics.source.ApplyResult
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricLaneState
import dev.amenhancer.module.lyrics.source.LyricSourceKind
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricSourceText
import dev.amenhancer.module.lyrics.source.LyricsSourcePreviewLine
import dev.amenhancer.module.lyrics.source.LyricsSourceSnapshot
import java.util.Locale

/** What the sheet is showing for the current song. */
internal enum class LyricsSourceSheetPhase {
    /** A snapshot with lanes is available. */
    READY,

    /** No song is playing; the host returned a null snapshot. */
    NO_SONG,

    /** No host published a controller; the sheet can only explain why. */
    UNAVAILABLE,

    /** Reading the snapshot threw; the sheet offers a retry. */
    ERROR,
}

/** One asynchronous provider search. */
internal sealed interface LyricsSourceSearchState {
    data object Idle : LyricsSourceSearchState

    data object Loading : LyricsSourceSearchState

    data class Failed(val message: String) : LyricsSourceSearchState

    /** A completed search; an empty list is the sheet's "no results" state. */
    data class Loaded(val options: List<LyricSourceOption>) : LyricsSourceSearchState
}

/** The three-line preview of one candidate, keyed so a stale result cannot land on another row. */
internal sealed interface LyricsSourcePreviewState {
    data object None : LyricsSourcePreviewState

    data class Loading(val optionKey: String) : LyricsSourcePreviewState

    data class Failed(val optionKey: String, val message: String) : LyricsSourcePreviewState

    data class Loaded(
        val optionKey: String,
        val lines: List<LyricsSourcePreviewLine>,
        /** True when the fetched document's word timing was actually observed. */
        val precisionKnown: Boolean = false,
        /** Per-word timing of the fetched document; only meaningful with [precisionKnown]. */
        val hasWords: Boolean = false,
        /** False when the fetched document has no timeline at all (static lyrics). */
        val hasTiming: Boolean = true,
    ) : LyricsSourcePreviewState

    /** The key this state belongs to, or null for [None]. */
    val optionKeyOrNull: String?
        get() = when (this) {
            None -> null
            is Loading -> optionKey
            is Failed -> optionKey
            is Loaded -> optionKey
        }
}

/**
 * The timing badge text for one option, or null when the sheet must render none.
 *
 * Delegates to [LyricSourceText.timingLabel] so the badge and the option's own
 * detail line (which core builds with the same function) can never disagree about
 * the same source: "无时间轴" wins over everything else, unknown precision renders
 * nothing, and only an observed document may claim 逐词/逐行.
 */
internal fun precisionLabel(option: LyricSourceOption, locale: Locale): String? =
    LyricSourceText.timingLabel(option, locale)

/** The same four-state label for a fetched preview document. */
internal fun previewTimingLabel(
    hasTiming: Boolean,
    precisionKnown: Boolean,
    hasWords: Boolean,
    locale: Locale,
): String? = LyricSourceText.timingLabel(hasTiming, precisionKnown, hasWords, locale)

/**
 * Pure search decisions, kept out of the view so the debounce and query rules
 * are testable without an Android runtime.
 */
internal object LyricsSourceSearchPolicy {

    /** How long the sheet waits after the last keystroke before searching. */
    const val DEBOUNCE_MS = 350L

    /** How many preview lines the sheet shows (spec 3.3). */
    const val PREVIEW_LINES = 3

    /**
     * The one query string the controller contract accepts. Providers receive
     * title first because their own rankers weight the title channel highest;
     * the artist stays behind it so "same title, different artist" still ranks.
     */
    fun composeQuery(title: String, artist: String): String =
        listOf(title.trim(), artist.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** True when there is something to search for. */
    fun isSearchable(title: String, artist: String): Boolean =
        title.isNotBlank() || artist.isNotBlank()

    /**
     * True when an edited query deserves an automatic search. An unchanged,
     * already-searched query is not repeated on every keystroke.
     */
    fun shouldSchedule(composed: String, lastScheduled: String?): Boolean =
        composed.isNotBlank() && composed != lastScheduled
}

/**
 * Monotonic tokens for the sheet's asynchronous callbacks.
 *
 * The controller contract guarantees exactly one callback per call, so a token
 * only has to answer "is this still the newest request"; results from an older
 * search, preview or song are dropped instead of flickering into the panel.
 */
internal class LyricsSourceRequestToken {

    private var current = 0

    fun next(): Int {
        current += 1
        return current
    }

    fun isCurrent(token: Int): Boolean = token == current

    fun invalidate() {
        current += 1
    }
}

/** The lane a selectable option belongs to, for the busiest state in the sheet. */
internal data class LyricsSourceBusy(
    val lane: LyricLane,
    val optionKey: String? = null,
    val hiding: Boolean = false,
    val restoring: Boolean = false,
)

/** Convenience accessors over the current snapshot used by the view layer. */
internal fun LyricsSourceSnapshot?.laneState(lane: LyricLane) = this?.lane(lane)

/**
 * The source the sheet must describe for one lane.
 *
 * [LyricLaneState.displayed] is the document the page is showing right now;
 * [LyricLaneState.selected] is only the behavioural choice, which differs while a
 * stored choice has not taken effect and when the overlay store merely layers a
 * translation on top of Apple's own document. The sheet prefers the displayed
 * source and falls back to the choice when the host cannot tell, so a lane never
 * ends up with no source line at all.
 */
internal fun LyricLaneState.displayedSource(): LyricSourceOption? = displayed ?: selected

/**
 * Apple's option names itself ("Apple Music（原生）"), so rendering its provider
 * brand as a second badge would print the same name twice.
 */
internal fun LyricSourceOption.showsProviderBadge(): Boolean = kind != LyricSourceKind.APPLE

/**
 * The user-facing text of an [ApplyResult]: the host owns the precise wording
 * (a rejected lane switch explains why, a successful one names what changed),
 * so a non-blank message always wins over the sheet's generic fallback. A blank
 * or missing message is the only case that falls back.
 */
internal fun ApplyResult.userMessageOr(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
