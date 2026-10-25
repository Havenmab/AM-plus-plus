package dev.amenhancer.module.ui.source

import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricsSourcePreviewLine
import dev.amenhancer.module.lyrics.source.LyricsSourceSnapshot

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
