package dev.amenhancer.lyricsplayer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.amenhancer.module.lyrics.player.LyricsPlayerClock
import dev.amenhancer.module.lyrics.player.LyricsPlayerColumns
import dev.amenhancer.module.lyrics.player.LyricsPlayerMotion
import dev.amenhancer.module.lyrics.player.LyricsPlayerTimeline
import dev.amenhancer.module.lyrics.player.PlayerDocument
import dev.amenhancer.module.lyrics.player.PlayerGeometry
import dev.amenhancer.module.lyrics.player.PlayerTrackInfo
import dev.amenhancer.module.lyrics.player.SpringChannel

/**
 * Per-frame state machine between the host port and the composition.
 *
 * The frame loop samples the host once per display frame (the contract asks for exactly
 * that), advances the position clock and the line-change spring, and only writes Compose
 * state when something a composable actually reads has changed. Everything the canvas reads
 * directly is a plain field: the canvas invalidates itself through [tick], so a running
 * spring never triggers recomposition.
 */
internal class LyricsPlayerRuntime(private val port: LyricsPlayerPort) {
    val clock = LyricsPlayerClock()
    private val scroll = SpringChannel(LyricsPlayerMotion.lineChange)

    /** Bumped once per frame; readers in the draw phase only invalidate drawing. */
    val tick = mutableStateOf(0L)

    /** Per-frame scroll position (line units), read from graphicsLayer blocks only. */
    val displayedIndexState = mutableStateOf(0f)

    /** Observable current/previous line for the per-line composables (rare updates). */
    val currentIndexState = mutableStateOf(0)
    val previousIndexState = mutableStateOf(0)
    val lineChangeNanosState = mutableStateOf(0L)

    val documentState = mutableStateOf<PlayerDocument?>(null)
    val trackState = mutableStateOf<PlayerTrackInfo?>(null)
    val artworkState = mutableStateOf<ImageBitmap?>(null)
    val playingState = mutableStateOf(false)
    val elapsedSecondsState = mutableStateOf(0L)
    val durationMsState = mutableStateOf(0L)
    val configState = mutableStateOf(LyricsPlayerConfig())
    val geometryState = mutableStateOf<PlayerGeometry?>(null)

    var config: LyricsPlayerConfig = LyricsPlayerConfig()
        private set
    var document: PlayerDocument? = null
        private set

    /** Spring-driven scroll position in line units. */
    var displayedIndex: Float = 0f
        private set
    var currentIndex: Int = 0
        private set
    var previousIndex: Int = 0
        private set

    /** Moment the current line changed, the origin of the stagger/emphasis clock. */
    var lineChangeNanos: Long = 0L
        private set

    var lastFrameNanos: Long = 0L
        private set

    private var tapIndex: Int = -1
    private var tapStartNanos: Long = 0L
    private var dragging = false
    private var dragAnchorIndex = 0f
    private var dragOffsetPx = 0f
    private var followBlockedUntilNanos = 0L
    private var lastDocumentIdentity: String? = null
    private var lastTrackIdentity: String? = null
    private var frameCount = 0
    private var missingDocumentFrames = 0

    fun onFrame(nowNanos: Long) {
        lastFrameNanos = nowNanos
        // Every host read is defensive: a host-side surprise must dim one frame, never kill
        // the page (spec §1 D7). runCatching is inline and allocates nothing.
        val playing = runCatching { port.isPlaying() }.getOrDefault(false)
        clock.update(runCatching { port.positionMs() }.getOrNull(), playing, nowNanos)
        if (playingState.value != clock.isPlaying) playingState.value = clock.isPlaying

        val position = clock.visualPositionMs()
        val sampledDocument = runCatching { port.document() }.getOrNull()
        if (sampledDocument == null) {
            // The host needs a moment to build a document; keep the old one on screen until it
            // has been missing long enough to mean "this track has no lyrics".
            val hadDocument = this.document != null
            if (hadDocument && ++missingDocumentFrames > DOCUMENT_GRACE_FRAMES) {
                lastDocumentIdentity = null
                this.document = null
                documentState.value = null
                currentIndex = 0
                previousIndex = 0
                displayedIndex = 0f
                displayedIndexState.value = 0f
                currentIndexState.value = 0
                previousIndexState.value = 0
                scroll.snap(0f)
                lineChangeNanos = nowNanos
                lineChangeNanosState.value = nowNanos
            }
        } else {
            missingDocumentFrames = 0
            if (sampledDocument.identity != lastDocumentIdentity) {
                lastDocumentIdentity = sampledDocument.identity
                this.document = sampledDocument
                documentState.value = sampledDocument
                val index = LyricsPlayerTimeline.lineIndexAt(sampledDocument.lines, position)
                    .coerceAtLeast(0)
                currentIndex = index
                previousIndex = index
                displayedIndex = index.toFloat()
                displayedIndexState.value = displayedIndex
                currentIndexState.value = index
                previousIndexState.value = index
                scroll.snap(displayedIndex)
                lineChangeNanos = nowNanos
                lineChangeNanosState.value = nowNanos
            }
        }

        val lines = this.document?.lines.orEmpty()
        if (lines.isNotEmpty() && !dragging && nowNanos >= followBlockedUntilNanos) {
            val target = LyricsPlayerTimeline.lineIndexAt(lines, position)
            if (target != currentIndex) {
                previousIndex = currentIndex
                currentIndex = target
                lineChangeNanos = nowNanos
                scroll.timing = LyricsPlayerMotion.lineChange
                scroll.animateTo(target.toFloat(), nowNanos, config.motionPercent)
            }
        }
        if (!dragging) displayedIndex = scroll.valueAt(nowNanos)
        displayedIndexState.value = displayedIndex
        if (currentIndexState.value != currentIndex) {
            currentIndexState.value = currentIndex
            previousIndexState.value = previousIndex
            lineChangeNanosState.value = lineChangeNanos
        }

        val seconds = position / 1000L
        if (elapsedSecondsState.value != seconds) elapsedSecondsState.value = seconds

        if (frameCount++ % TRACK_POLL_FRAMES == 0) pollHostMetadata()
        tick.value = nowNanos
    }

    /** Drag starts: freeze the spring on the current position and follow the finger. */
    fun beginDrag() {
        dragging = true
        dragAnchorIndex = displayedIndex
        dragOffsetPx = 0f
        scroll.snap(displayedIndex)
    }

    fun dragBy(distancePx: Float, linePitchPx: Float) {
        if (!dragging || linePitchPx <= 0f) return
        dragOffsetPx += distancePx
        displayedIndex = dragAnchorIndex - dragOffsetPx / linePitchPx
    }

    /**
     * Drag ends: project the fling onto a line and spring there. Auto-follow stays paused
     * for [LyricsPlayerMotion.DRAG_RESUME_DELAY_MS] so the view does not fight the finger.
     */
    fun endDrag(velocityY: Float, linePitchPx: Float, nowNanos: Long) {
        if (!dragging) return
        dragging = false
        scroll.snap(displayedIndex)
        val lineCount = document?.lines?.size ?: 0
        val target = LyricsPlayerMotion.releaseTargetIndex(
            displayedIndex = displayedIndex,
            velocityPxPerSecond = velocityY,
            linePitchPx = linePitchPx,
            lineCount = lineCount,
        )
        scroll.timing = LyricsPlayerMotion.springForJump(target - displayedIndex)
        scroll.animateTo(target.toFloat(), nowNanos, config.motionPercent)
        followBlockedUntilNanos = nowNanos + LyricsPlayerMotion.DRAG_RESUME_DELAY_MS * 1_000_000L
    }

    fun cancelDrag(nowNanos: Long, linePitchPx: Float) {
        if (!dragging) return
        dragging = false
        scroll.snap(displayedIndex)
        val lineCount = document?.lines?.size ?: 0
        val target = LyricsPlayerMotion.releaseTargetIndex(displayedIndex, 0f, linePitchPx, lineCount)
        scroll.timing = LyricsPlayerMotion.lineChange
        scroll.animateTo(target.toFloat(), nowNanos, config.motionPercent)
        followBlockedUntilNanos = nowNanos + LyricsPlayerMotion.DRAG_RESUME_DELAY_MS * 1_000_000L
    }

    /** Tap: seek to the tapped line and start the emphasise feedback. [yPx] is local to the column. */
    fun tapAt(yPx: Float, columns: LyricsPlayerColumns, nowNanos: Long) {
        val lines = document?.lines.orEmpty()
        val index = columns.lineIndexAt(yPx + columns.lyricsTopPx, displayedIndex, lines.size) ?: return
        tapIndex = index
        tapStartNanos = nowNanos
        val line = lines.getOrNull(index) ?: return
        val seeked = runCatching { port.seekTo(line.startMs) }.getOrDefault(false)
        if (!seeked) android.util.Log.w(LOG_TAG, "seek to line " + index + " refused by host")
    }

    /**
     * Extra scale of a tapped line: the press spring pulls it down, the release spring lets
     * it settle back, so the tap is visible even when the seek lands instantly.
     */
    fun tapBoost(lineIndex: Int, nowNanos: Long): Float {
        if (lineIndex != tapIndex) return 1f
        val elapsedMs = ((nowNanos - tapStartNanos) / 1_000_000L).coerceAtLeast(0L)
        val progress = if (elapsedMs <= TAP_PRESS_MS) {
            LyricsPlayerMotion.press.value(elapsedMs / 1000.0).toFloat()
        } else {
            1f - LyricsPlayerMotion.release.value((elapsedMs - TAP_PRESS_MS) / 1000.0).toFloat()
        }
        return 1f - TAP_EXTRA_SCALE * progress.coerceIn(0f, 1f)
    }

    private fun pollHostMetadata() {
        val preferences = runCatching { port.preferences() }.getOrDefault(LyricsPlayerConfig())
        if (preferences != configState.value) {
            configState.value = preferences
            config = preferences
        }
        // Geometry is a hint from the host lyric window; it only changes on rotation/relayout,
        // and structural equality keeps an unchanged hint from invalidating the layout.
        geometryState.value = runCatching { port.geometry() }.getOrNull()
        val track = runCatching { port.track() }.getOrNull()
        if (track?.identity != lastTrackIdentity) {
            lastTrackIdentity = track?.identity
            trackState.value = track
            durationMsState.value = track?.durationMs ?: 0L
            artworkState.value = null
        }
        // The host hands the cover to its own background layer after the metadata is ready, so
        // keep retrying while we have none: one decode per poll, never per frame (spec §5.1).
        if (artworkState.value == null && track != null) {
            artworkState.value = runCatching { port.artwork() }.getOrNull()?.asImageBitmap()
        }
    }

    companion object {
        /** Metadata and settings do not change per frame; poll them twice per second. */
        private const val TRACK_POLL_FRAMES = 30

        /** Frames a missing document is tolerated before the page falls back to the empty state. */
        private const val DOCUMENT_GRACE_FRAMES = 30
        private const val TAP_PRESS_MS = 180L

        /** Extra scale of a tapped line: it dips to the A-level pressed scale (Specs +0x5c = 0.95). */
        private val TAP_EXTRA_SCALE = 1f - dev.amenhancer.module.lyrics.player.LyricsPlayerMotion.PRESSED_SCALE
        private const val LOG_TAG = "lyrics player"
    }
}
