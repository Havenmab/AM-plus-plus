package dev.amenhancer.module.hook

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Dispatch surface of one glass session, consumed by [PhoneGlassRuntime]'s shared hooks.
 * [PhoneGlassSession] implements it for the stacked phone host and
 * [TabletDualPaneGlassSession] extends that implementation for the dual-pane tablet host.
 */
internal interface GlassSession : AutoCloseable {
    val miniRoot: FrameLayout?
    val playerBehavior: Any?
    val activated: Boolean

    /** Feature key this session reports its mounted/failed health under. */
    val glassFeatureKey: String

    fun attachAvailableViews()
    fun ownsCurrentHierarchy(): Boolean
    fun onSlide(progress: Float)
    fun observeNativePeek(height: Int)
    fun peekHeight(): Int
    fun redirectedPadding(view: Any?): Int?
    fun redirectedLayerAlpha(view: Any?, alpha: Float): Float?
    fun shouldIgnoreTopHeaderDependency(view: View, dependency: View): Boolean = false

    fun beforeNativeArtwork(artwork: View) = Unit

    /**
     * Corrects the native full-player artwork's screen origin while the sheet slides
     * ([slide] in `0..1`), called right after Apple's own per-frame artwork write.
     *
     * Only the tablet forms need it: that callback computes its rect with
     * `offsetDescendantRectToMyCoords`, which omits the tablet `artwork_container`'s visual
     * translation. The stacked phone host has no such translation, so
     * [PhoneGlassSession]'s implementation is the empty default.
     */
    fun alignNativeArtwork(artwork: View, slide: Float)

    fun shouldPassThroughTouch(view: View, event: MotionEvent): Boolean
    fun shouldBypassPlayerIntercept(event: MotionEvent): Boolean
    /** Returns null for normal dispatch, or the native mini player's handled result. */
    fun dispatchCollapsedMiniTouch(view: View, event: MotionEvent): Boolean?
    fun observeTouch(event: MotionEvent)
    fun foreground(active: Boolean)
    override fun close()
}
