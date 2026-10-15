package dev.amenhancer.module.hook

/**
 * Remembers whether the gesture in progress began on the **collapsed** mini player.
 *
 * Nothing is consumed. The collapsed mini is a complete mini player, so its own controls
 * (play/pause, skip) and its tap-to-open-player must reach the host exactly as they do when the bar
 * is expanded — the module draws the glass *under* that native content and never replaces it.
 *
 * What the module still needs is the *shape* of that gesture, because the content root observes
 * every page gesture for the scroll collapse, including the one the mini is already using. A swipe
 * that began on the mini would otherwise withdraw or expand the bar underneath the user's finger,
 * so the collapse tracker stands down for the rest of that gesture.
 *
 * Only "did this gesture start there" is recorded. Which control the finger landed on, and whether
 * it turned into a drag, is the host's business rather than the module's.
 *
 * Android-free so the rule stays unit-testable.
 */
internal class GlassCollapsedMiniGesture {
    private var active = false

    /** True from an accepted [down] until [reset]; the collapse tracker must stand down. */
    val owning: Boolean get() = active

    /** Records that a gesture started on the collapsed mini player. */
    fun down(x: Float, y: Float) {
        active = x.isFinite() && y.isFinite()
    }

    /** Ends the gesture. */
    fun reset() {
        active = false
    }
}
