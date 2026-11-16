package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.PronunciationBoundary

/**
 * The narrow door the app needs into the romanization-row hooks.
 *
 * [LyricsPronunciationRowCentring] and [LyricsPronunciationWordAlignment] stay internal on purpose:
 * they are one feature with one write path, and every other caller lives inside this module. The app
 * module owns the runtime wiring, though, so it has to do exactly two things from outside — hand the
 * splice a plan provider, and ask the rows to repaint once a plan landed. Those two calls are the
 * whole API surface, and they live here rather than widening either object.
 */
object LyricsPronunciationCentringBridge {

    /**
     * Sets where the splice looks a line's plan up, or clears it with null.
     *
     * The provider is consulted for every row the correction touches, so it must answer from memory:
     * [LyricsPronunciationWordAlignment.alignRow] runs on the layout path and may never wait for a
     * request. A null provider — and a provider that answers null — leave the rules' grouping in place.
     */
    fun setBoundaryPlans(provider: ((String) -> PronunciationBoundary.BoundaryLine?)?) {
        LyricsPronunciationWordAlignment.boundaryPlans = provider
    }

    /** Re-runs the correction for the containers that are still inflated; idempotent, main thread. */
    fun reapplyObservedRows() {
        LyricsPronunciationRowCentring.reapplyObserved()
    }

    /**
     * Where the host reports a lyric document it is about to hand to Apple, or clears it with null.
     *
     * The injection seam is the only place every path passes through — the custom-lyrics quick path
     * that injects an already-configured document never reaches the app's publish callbacks — so the
     * observer is what makes the boundary planner see those songs at all. It runs on the thread that
     * publishes the document, must not block and must not do I/O: the app's handler only queues.
     */
    @Volatile
    private var documentObserver: ((Long, String) -> Unit)? = null

    /** Installs the observer; the host calls it with the song id and our own document. */
    fun setDocumentObserver(observer: ((Long, String) -> Unit)?) {
        documentObserver = observer
    }

    /** Reports one document to the observer, if any; a no-op otherwise. */
    fun reportDocument(appleMusicId: Long, ttml: String) {
        if (appleMusicId <= 0L || ttml.isBlank()) return
        runCatching {
            // The observer is app code running on the host's publishing thread; a throw here would
            // reach Apple's lyric binding and take the lyrics page down, so it is contained twice:
            // once for the lookup, once for the call.
            val observer = documentObserver
            if (observer != null) runCatching { observer(appleMusicId, ttml) }
        }.onFailure { error ->
            android.util.Log.i("AMPP-Bndry", "bndry report failed " + (error.message ?: error.javaClass.simpleName))
        }
    }
}
