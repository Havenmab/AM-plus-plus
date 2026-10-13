package dev.amenhancer.module.hook

/** Native IDs, document ordinals and RecyclerView positions are separate domains. */
enum class LyricRowKind { LYRIC, INSTRUMENTAL, CREDITS, UNKNOWN }
enum class LyricWordSource { WORD, BACKGROUND, PRONUNCIATION, PRONUNCIATION_BACKGROUND }
enum class LyricBlurInteraction { FOLLOW, BROWSING }

data class LyricBlurEpoch(val view: Long, val document: Long = 0, val playback: Long = 0)

data class LyricRowSnapshot(
    val nativeLineId: Int?,
    val lyricOrdinal: Int?,
    val adapterPosition: Int?,
    val kind: LyricRowKind,
    val bindingGeneration: Long,
)

sealed interface LyricDecodeResult<out T> {
    data class Pending(val role: String) : LyricDecodeResult<Nothing>
    data class Complete<T>(val value: T) : LyricDecodeResult<T>
    data class Partial<T>(val value: T, val reason: String) : LyricDecodeResult<T>
    data class Failed(val reason: String) : LyricDecodeResult<Nothing>
}

/** Copied host values only. No native wrapper or Android object survives here. */
data class LyricDisplaySnapshot(
    val displayedLineIds: Set<Int>,
    val words: Map<LyricWordSource, Set<Int>> = emptyMap(),
    val instrumentalBeforeOrdinal: Int? = null,
)

sealed interface LyricFocusAnchor {
    data object None : LyricFocusAnchor
    data class Lines(val ordinals: Set<Int>) : LyricFocusAnchor
    data class Instrumental(val beforeOrdinal: Int) : LyricFocusAnchor
}

data class LyricBlurState(
    val epoch: LyricBlurEpoch,
    val ordinals: Map<Int, Int> = emptyMap(),
    val display: LyricDisplaySnapshot? = null,
    val interaction: LyricBlurInteraction = LyricBlurInteraction.FOLLOW,
    val interactionGeneration: Long = 0,
    val touchActive: Boolean = false,
    val scrollState: Int = 0,
) {
    val protectedLineIds: Set<Int>
        get() = display?.let { it.displayedLineIds + it.words.values.flatten() }.orEmpty()

    val focusAnchor: LyricFocusAnchor
        get() {
            val current = display ?: return LyricFocusAnchor.None
            val displayed = current.displayedLineIds.mapNotNull(ordinals::get).toSet()
            if (displayed.isNotEmpty()) return LyricFocusAnchor.Lines(displayed)
            current.instrumentalBeforeOrdinal?.let { return LyricFocusAnchor.Instrumental(it) }
            val words = current.words.values.flatten().mapNotNull(ordinals::get).toSet()
            if (words.isNotEmpty()) return LyricFocusAnchor.Lines(words)
            return LyricFocusAnchor.None
        }
}

sealed interface LyricBlurEvent {
    val epoch: LyricBlurEpoch
    data class Document(override val epoch: LyricBlurEpoch, val ordinals: Map<Int, Int>) : LyricBlurEvent
    data class Display(override val epoch: LyricBlurEpoch, val result: LyricDecodeResult<LyricDisplaySnapshot>) : LyricBlurEvent
    data class Seek(override val epoch: LyricBlurEpoch) : LyricBlurEvent
    data class Touch(override val epoch: LyricBlurEpoch, val active: Boolean) : LyricBlurEvent
    data class Scroll(override val epoch: LyricBlurEpoch, val state: Int) : LyricBlurEvent
    data class Restore(override val epoch: LyricBlurEpoch, val interactionGeneration: Long) : LyricBlurEvent
}

object LyricBlurReducer {
    fun reduce(state: LyricBlurState, event: LyricBlurEvent): LyricBlurState {
        if (event is LyricBlurEvent.Document) {
            if (event.epoch.view != state.epoch.view || event.epoch.document <= state.epoch.document) return state
            return LyricBlurState(event.epoch, event.ordinals.toMap(), interaction = state.interaction,
                touchActive = state.touchActive, scrollState = state.scrollState,
                interactionGeneration = state.interactionGeneration + 1)
        }
        if (event is LyricBlurEvent.Seek) {
            if (event.epoch.view != state.epoch.view || event.epoch.document != state.epoch.document ||
                event.epoch.playback <= state.epoch.playback) return state
            return state.copy(epoch = event.epoch, display = null,
                interactionGeneration = state.interactionGeneration + 1)
        }
        if (event.epoch != state.epoch) return state
        return when (event) {
            is LyricBlurEvent.Display -> state.copy(display =
                (event.result as? LyricDecodeResult.Complete<LyricDisplaySnapshot>)?.value)
            is LyricBlurEvent.Touch -> if (state.touchActive == event.active) state else state.copy(
                touchActive = event.active,
                interaction = if (event.active) LyricBlurInteraction.BROWSING else state.interaction,
                interactionGeneration = state.interactionGeneration + 1)
            is LyricBlurEvent.Scroll -> state.copy(scrollState = event.state,
                interaction = if (event.state == 1) LyricBlurInteraction.BROWSING else state.interaction,
                interactionGeneration = state.interactionGeneration + 1)
            is LyricBlurEvent.Restore -> if (event.interactionGeneration == state.interactionGeneration &&
                !state.touchActive && state.scrollState == 0) state.copy(interaction = LyricBlurInteraction.FOLLOW) else state
            is LyricBlurEvent.Document, is LyricBlurEvent.Seek -> state
        }
    }
}

data class LyricBlurTarget(val focusRadius: Float, val edgeWeight: Float)

object LyricBlurPolicy {
    fun targets(state: LyricBlurState, rows: List<LyricRowSnapshot>, offsetPx: Int): List<LyricBlurTarget> {
        val protected = state.protectedLineIds
        val anchor = state.focusAnchor
        return rows.map { target(state, it, offsetPx, protected, anchor) }
    }

    private fun target(state: LyricBlurState, row: LyricRowSnapshot, offsetPx: Int,
        protectedIds: Set<Int>, anchor: LyricFocusAnchor): LyricBlurTarget {
        if (row.kind == LyricRowKind.UNKNOWN || row.kind == LyricRowKind.INSTRUMENTAL ||
            (row.kind == LyricRowKind.LYRIC && (row.nativeLineId == null || row.lyricOrdinal == null))) {
            return LyricBlurTarget(0f, 0f)
        }
        if (state.interaction == LyricBlurInteraction.BROWSING) return LyricBlurTarget(0f, 1f)
        val protected = row.kind == LyricRowKind.LYRIC && row.nativeLineId in protectedIds
        if (protected) return LyricBlurTarget(0f, 0f)
        val ordinal = row.lyricOrdinal ?: return LyricBlurTarget(0f, 0f)
        val focus = when (anchor) {
            LyricFocusAnchor.None -> return LyricBlurTarget(0f, 0f)
            is LyricFocusAnchor.Lines -> BidirectionalBlurPolicy.targetRadius(ordinal, anchor.ordinals)
            is LyricFocusAnchor.Instrumental -> {
                // The inserted gap lies between ordinals k-1 and k. Both nearest lyrics have distance one.
                val relative = ordinal - anchor.beforeOrdinal
                if (relative < 0) BidirectionalBlurPolicy.targetRadius(relative, setOf(0))
                else BidirectionalBlurPolicy.targetRadius(relative + 1, setOf(0))
            }
        }
        return LyricBlurTarget(BidirectionalBlurPolicy.applyRadiusOffset(focus, offsetPx), 1f)
    }
}

/** Logical-row animation survives detach; equal targets never restart the clock. */
data class LyricBlurTransition(val start: Float, val target: Float, val startedAtMs: Long) {
    fun value(nowMs: Long): Float = BidirectionalBlurPolicy.interpolate(start, target, nowMs - startedAtMs)
    fun running(nowMs: Long): Boolean = start != target && nowMs - startedAtMs < BidirectionalBlurPolicy.TRANSITION_DURATION_MS
    fun to(next: Float, nowMs: Long): LyricBlurTransition = if (next == target) this
        else LyricBlurTransition(value(nowMs), next, nowMs)
}

data class LyricBlurRenderState(val focus: LyricBlurTransition, val edgeWeight: LyricBlurTransition) {
    fun to(target: LyricBlurTarget, nowMs: Long) = LyricBlurRenderState(
        focus.to(target.focusRadius, nowMs), edgeWeight.to(target.edgeWeight, nowMs))
    fun running(nowMs: Long) = focus.running(nowMs) || edgeWeight.running(nowMs)
    companion object {
        fun initial(target: LyricBlurTarget, nowMs: Long) = LyricBlurRenderState(
            LyricBlurTransition(target.focusRadius, target.focusRadius, nowMs),
            LyricBlurTransition(target.edgeWeight, target.edgeWeight, nowMs))
    }
}
