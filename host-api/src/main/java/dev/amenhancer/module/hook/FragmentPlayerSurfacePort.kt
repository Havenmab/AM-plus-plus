package dev.amenhancer.module.hook

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

data class FragmentPlayerSurfaceSnapshot(
    val contentRoot: ViewGroup,
    /** This region excludes every module glass consumer. */
    val backdropSource: View,
    val navigation: View,
    val navigationBlur: View?,
    val miniContent: View?,
    val miniTouchPanel: View?,
    val miniBlur: View?,
    val playerSheet: View,
    /** Safe native player-root group; never the restricted FragmentContainerView. */
    val materialParent: ViewGroup?,
    /** Measured native full-player/pane region is the morph destination. */
    val playerContent: View?,
    val expansion: Float,
    val nativeNavigationAlpha: Float,
    val accentColor: Int,
    val foregroundColor: Int,
)

interface FragmentPlayerSurfacePort : PlayerSurfacePort, AutoCloseable {
    val activity: Activity
    val navigation: NavigationPort
    val tabletChrome: FragmentTabletChromePort? get() = null
    val phoneChrome: FragmentPhoneChromePort? get() = null
    fun snapshot(): FragmentPlayerSurfaceSnapshot
    /** Suppression belongs to this view identity and requires a fully rendered replacement. */
    fun setNavigationGlassReady(ready: Boolean)
    fun setMiniGlassReady(ready: Boolean)
    /** Native playback/artwork still own their animation; these multiply only the material layers. */
    fun setPlayerGlassProgress(materialExpansion: Float, motionAlpha: Float) = Unit
    fun observe(observer: (FragmentPlayerSurfaceSnapshot) -> Unit): HostSubscription
}

interface FragmentPlayerSurfaceObserver {
    /** Announced before the new view's first draw, including a surviving view's reattachment. */
    fun onPreparing(root: ViewGroup) = Unit
    fun onCreated(surface: FragmentPlayerSurfacePort)
    fun onDestroyed(identity: Any)
    /**
     * The mini panel's touches.
     *
     * Normally observation only: native dispatch, every mini control, and player drag continue
     * unchanged. Returning non-null **consumes** the event, so the mini's own controls never see
     * it — which is what lets a collapsed row turn the whole mini into one tap target. Returning
     * null is the default and leaves dispatch completely untouched.
     */
    fun onMiniTouch(identity: Any, event: MotionEvent): Boolean? = null
    /**
     * The page's gestures, from the content root rather than the mini panel.
     *
     * Compose pages scroll entirely inside Compose and expose no native scroll container, so this
     * gesture is the only signal the scroll collapse can observe. Returning non-null **consumes**
     * the event at the root, which is how a tap on the collapsed row is claimed: the root is an
     * ancestor of the mini panel, so it is reached whatever the host's internal view chain looks
     * like, unlike a match on one specific panel view. Null is the default and leaves the event
     * entirely alone, so the expanded bar and every page gesture are untouched.
     */
    fun onContentTouch(identity: Any, event: MotionEvent): Boolean? = null
    fun onFailure(identity: Any?, error: Throwable)
}
