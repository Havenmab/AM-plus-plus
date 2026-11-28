package dev.amenhancer.module.lyrics

import java.util.IdentityHashMap

/**
 * 「isRunning 借了要还」: one answer handed back per borrow epoch, per animator.
 *
 * The spring answers `isRunning()` **true** for as long as a wave of that animator is still
 * travelling, because RecyclerView would otherwise conclude the list is idle and end every pending
 * move mid-flight. The host hangs the other half of that exchange on the same answer: 1626's
 * `ItemAnimator.isRunning(listener)` queues the listener while the answer is true, and only
 * `dispatchAnimationsFinished()` ever fires it — reached in this build through `mc.y#u()`, which the
 * host calls from its own animation listeners' `onAnimationEnd`.
 *
 * A takeover never runs those listeners, so nothing ever dispatches: every listener the host queued
 * while we animated stays queued, which is why a short line ("this row has not finished yet") can
 * hold back the host's own line update until the line after it. This ledger is the debt: the first
 * answer we give while an animator has waves opens an epoch, every further answer extends it, and
 * the moment that animator's **last** wave is gone the epoch is handed back exactly once.
 *
 * Deliberately per epoch, not per wave: a line change that finishes while the next one is already
 * travelling must not tell the host "nothing is animating" — that is the same early-finish the
 * `isRunning()` override exists to prevent. Two panes own two animators and two epochs.
 *
 * Pure and identity-keyed: it holds one integer pair per animator instance, never touches a View, a
 * clock or the host, and is what makes the exactly-once rule testable with a fake animator.
 */
class LyricLineSpringBorrows {
    /** One animator's debt: how many of its waves are live, and how many answers we still owe. */
    private class Epoch {
        var waves: Int = 0
        var answers: Int = 0
    }

    private val epochs = IdentityHashMap<Any, Epoch>()

    /** A wave of [animator] now exists: it will keep `isRunning()` true until it ends. */
    fun waveStarted(animator: Any) {
        epoch(animator).waves += 1
    }

    /** We just answered `isRunning()` true for [animator]: one more answer to hand back. */
    fun borrow(animator: Any) {
        epoch(animator).answers += 1
    }

    /** How many answers this animator is still owed; 0 means `isRunning()` may answer false again. */
    fun owed(animator: Any): Int = epochs[animator]?.answers ?: 0

    /**
     * One of [animator]'s waves is gone: the answers to hand back when it was the **last** one, or
     * null while the animator still owns a wave (or when we never borrowed).
     *
     * Closing the epoch forgets it, so a later wave starts a new debt and is handed back on its own
     * — that is what "borrow again, then return again" means for a song that keeps changing lines.
     */
    fun waveEnded(animator: Any): Int? {
        val epoch = epochs[animator] ?: return null
        if (epoch.waves > 0) epoch.waves -= 1
        if (epoch.waves > 0) return null
        val answers = epoch.answers
        epochs.remove(animator)
        return answers.takeIf { it > 0 }
    }

    /** Drop whatever [animator] owes — the animator itself is going away (a destroyed pane). */
    fun forget(animator: Any) {
        epochs.remove(animator)
    }

    private fun epoch(animator: Any): Epoch =
        epochs[animator] ?: Epoch().also { epochs[animator] = it }
}
