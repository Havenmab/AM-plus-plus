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
}
