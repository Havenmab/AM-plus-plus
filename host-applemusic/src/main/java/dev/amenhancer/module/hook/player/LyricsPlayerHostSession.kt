package dev.amenhancer.module.hook.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import dev.amenhancer.lyricsplayer.LyricsPlayerHostView
import dev.amenhancer.module.hook.ModernXposedRuntime
import java.lang.ref.WeakReference

/**
 * Lifecycle and mount ownership of the self-drawn page (spec §7.1/§7.2).
 *
 * The page is a sibling of the activity content inside `decorView`, `MATCH_PARENT`, opaque and
 * clickable (the page itself swallows touches). It is only ever added when the host actually
 * has lyrics — a failed contract, a missing document or an unlaid-out fragment logs
 * `lyrics player degraded: <reason>` and leaves Apple Music's own page visible.
 *
 * Triggers mirror the host's own lyrics-fragment lifecycle:
 *  - `onCreateView`/`onResume` → show,
 *  - `onPause`/`onDestroyView`/Activity `onStop` → hide (remove on view destroy),
 *  - a new document install (`w2`/`F2`) retries a show that had nothing to paint yet,
 *  - a configuration change re-creates the host activity, so the next `onCreateView` mounts a
 *    fresh view on the new decor; the old one is removed with the old decor.
 */
internal class LyricsPlayerHostSession(
    private val port: LyricsPlayerPortImpl,
    private val enabled: () -> Boolean,
) {
    private var hostView: LyricsPlayerHostView? = null
    private var hostActivity: WeakReference<Activity>? = null
    private var fragmentView: WeakReference<View>? = null
    private var visible = false
    private var pageActive = false
    private var lastReason: String? = null

    fun onLyricsViewCreated(fragment: Any, view: View?) {
        fragmentView = view?.let { created -> WeakReference(created) }
        port.attach(fragment, view)
        pageActive = true
        showIfReady()
    }

    fun onLyricsResumed(fragment: Any) {
        port.attach(fragment, fragmentView?.get())
        pageActive = true
        showIfReady()
    }

    fun onLyricsPaused() {
        pageActive = false
        hide()
    }

    fun onLyricsViewDestroyed() {
        detach()
    }

    fun onActivityStopped(activity: Activity) {
        if (hostActivity?.get() === activity) {
            pageActive = false
            hide()
        }
    }

    /** The host installed a new document: rebuild it and retry a show that had nothing yet. */
    fun onDocumentInstalled(fragment: Any) {
        port.attach(fragment, fragmentView?.get())
        port.invalidateDocument()
        if (pageActive) showIfReady()
    }

    fun close() {
        detach()
    }

    private fun showIfReady() {
        if (!enabled()) {
            hide()
            return
        }
        // A resumed fragment without a view yet is transient, not a degradation.
        val activity = activity() ?: return
        val document = port.document()
        if (document == null || document.lines.isEmpty()) {
            // Never mount a page that has nothing to paint: the native lyrics stay visible.
            // A source that exists but produced nothing is a real degradation (no lyrics /
            // broken contract); a source that is merely not bound yet is just "waiting".
            if (port.hostDocumentAvailable()) degrade("lyrics document unavailable")
            else waiting("lyrics document not ready")
            return
        }
        val view = mount(activity) ?: run {
            degrade("activity decor unavailable")
            return
        }
        view.setPort(port)
        view.show()
        view.foreground(true)
        visible = true
        lastReason = null
    }

    private fun mount(activity: Activity): LyricsPlayerHostView? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        var view = hostView
        if (view == null || view.context !== activity) {
            // A re-created activity (rotation/theme change) gets a new decor: drop the stale view.
            view?.let { previous -> (previous.parent as? ViewGroup)?.removeView(previous) }
            val created = LyricsPlayerHostView(activity)
            created.setOnApplyWindowInsetsListener { _, insets -> applyInsets(created, insets) }
            hostView = created
            view = created
        }
        if (view.parent !== decor) {
            (view.parent as? ViewGroup)?.removeView(view)
            decor.addView(view, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        hostActivity = WeakReference(activity)
        view.requestApplyInsets()
        return view
    }

    private fun applyInsets(view: LyricsPlayerHostView, insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setSafeInsets(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            view.setSafeInsets(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }
        return insets
    }

    private fun hide() {
        hostView?.hide()
        hostView?.foreground(false)
        visible = false
    }

    private fun detach() {
        val view = hostView
        view?.setPort(null)
        view?.hide()
        (view?.parent as? ViewGroup)?.removeView(view)
        hostView = null
        hostActivity = null
        fragmentView = null
        visible = false
        pageActive = false
        port.release()
    }

    private fun activity(): Activity? {
        hostActivity?.get()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) return activity
        }
        val context = fragmentView?.get()?.context ?: return null
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun degrade(reason: String) {
        if (lastReason == reason) return
        lastReason = reason
        ModernXposedRuntime.log("lyrics player degraded: $reason")
    }

    /** Not a failure: the host has not bound its lyrics model yet. Logged once per reason. */
    private fun waiting(reason: String) {
        if (lastReason == "waiting:$reason") return
        lastReason = "waiting:$reason"
        ModernXposedRuntime.log("lyrics player waiting: $reason")
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
