package dev.amenhancer.module.hook

import android.app.Activity
import android.view.MotionEvent

internal interface FragmentGlassSessionLifecycle : AutoCloseable {
    val activity: Activity
    /**
     * The mini panel's touches.
     *
     * Returning non-null **consumes** the event, so the native mini's own controls and its
     * player-open drag never see it. Null is the default and leaves dispatch untouched.
     */
    fun observeMiniTouch(event: MotionEvent): Boolean? = null
    /**
     * The page's own gestures, observed from the content root.
     *
     * This is the collapse's only signal on a Compose page, and it is also the seam that claims a
     * tap on the **collapsed** row: the content root is an ancestor of the mini panel, so it sees
     * that touch before the panel does, and returning non-null stops the event there. Returning
     * null — every not-collapsed case — leaves dispatch completely untouched.
     */
    fun observeContentTouch(event: MotionEvent): Boolean? = null
    fun foreground(active: Boolean)
}
