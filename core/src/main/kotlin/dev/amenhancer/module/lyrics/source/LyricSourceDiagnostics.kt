package dev.amenhancer.module.lyrics.source

/**
 * The lyrics-source device-log lines.
 *
 * Same rule as the rest of the module: the lines are composed here, as a pure
 * function of the state the host is about to publish, so the exact shape a
 * device log depends on is pinned by JVM tests instead of by reading an exported
 * LSPosed log. Every line starts with the stable "lyrics-source" prefix, stays
 * on one line, and quotes free text, so the log can be grepped for one token.
 *
 * The host owns the sink (it is the only layer with a log channel); a typical
 * call is [snapshotLine] once when the sheet is opened, [laneLine] for each lane,
 * [applyLine] for each switch and [degradedLine] whenever a stored key no longer
 * resolves.
 */
object LyricSourceDiagnostics {

    /** The greppable prefix of every line this object builds. */
    const val PREFIX = "lyrics-source"

    /** The whole snapshot: the song identity plus each lane's effective key. */
    fun snapshotLine(snapshot: LyricsSourceSnapshot): String = buildString {
        append(PREFIX).append(" snapshot id=").append(snapshot.songId)
        append(" title=").append(quote(snapshot.title))
        append(" artist=").append(quote(snapshot.artist))
        append(" lanes=")
        snapshot.lanes.forEachIndexed { index, state ->
            if (index > 0) append(',')
            append(state.lane.key).append(':').append(keyOf(state.selected))
            if (state.overridden) append("(overridden)")
        }
        append(" overrides=").append(
            snapshot.overriddenLanes.joinToString(",") { it.key }.ifEmpty { "none" },
        )
    }

    /** One lane: what is selected, whether it is an override, and what else is offered. */
    fun laneLine(songId: Long, state: LyricLaneState): String = buildString {
        append(PREFIX).append(" lane id=").append(songId)
        append(" lane=").append(state.lane.key)
        append(" selected=").append(keyOf(state.selected))
        append(" kind=").append(state.selected?.kind?.token ?: "none")
        append(" overridden=").append(state.overridden)
        append(" apple=").append(state.appleAvailable)
        append(" options=").append(
            state.options.joinToString(",") { it.key }.ifEmpty { "none" },
        )
        append(" labels=").append(
            state.options.joinToString("|") { quote(it.label) }.ifEmpty { "none" },
        )
    }

    /** One apply / clear / hide call and its outcome. */
    fun applyLine(
        songId: Long,
        lane: LyricLane,
        option: LyricSourceOption?,
        result: ApplyResult,
    ): String = buildString {
        append(PREFIX).append(" apply id=").append(songId)
        append(" lane=").append(lane.key)
        append(" option=").append(keyOf(option))
        append(" ok=").append(result.ok)
        append(" pending=").append(result.pending)
        append(" code=").append(result.errorCode ?: "-")
        if (!result.message.isNullOrBlank()) append(" message=").append(quote(result.message))
    }

    /** One search call: the query, how many candidates came back and how it ended. */
    fun searchLine(
        songId: Long,
        lane: LyricLane,
        query: String,
        candidates: Int,
        failure: Throwable? = null,
    ): String = buildString {
        append(PREFIX).append(" search id=").append(songId)
        append(" lane=").append(lane.key)
        append(" query=").append(quote(query))
        append(" candidates=").append(candidates)
        append(" result=").append(if (failure == null) "ok" else "failed")
        if (failure != null) append(" error=").append(quote(failure.message ?: failure::class.java.simpleName))
    }

    /**
     * A stored key that no longer names an option, i.e. the lane fell back to its
     * default. This is the line that explains "the sheet forgot my choice" after
     * a custom file was deleted or a provider dropped the song.
     */
    fun degradedLine(songId: Long, lane: LyricLane, storedKey: String?): String =
        PREFIX + " degraded id=" + songId +
            " lane=" + lane.key +
            " stored=" + quote(storedKey ?: "") +
            " reason=unresolved"

    /** The persisted state of one song, for a "why was it restored" question. */
    fun selectionLine(songId: Long, stored: Map<LyricLane, String>): String = buildString {
        append(PREFIX).append(" selection id=").append(songId)
        LyricLane.ALL.forEach { lane ->
            append(' ').append(lane.key).append('=').append(stored[lane] ?: "-")
        }
    }

    private fun keyOf(option: LyricSourceOption?): String = option?.key ?: "none"

    /** Quotes free text and strips line breaks so one record stays one line. */
    private fun quote(value: String?): String {
        val flat = value.orEmpty()
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\"", "'")
        return "\"" + flat + "\""
    }
}
