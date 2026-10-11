package dev.amenhancer.module.hook.tabletmedia

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/** Cover and whole-volume width use the same native track and horizontal padding. */
internal class TabletProgressContent(private val find: (View, String) -> View?) {
    data class Bounds(val view: View, val start: Int, val end: Int) {
        val width: Int get() = end - start
    }

    fun resolve(parent: View, requireShown: Boolean = false): Bounds? {
        val container = container(parent, requireShown) ?: return null
        return bounds(container)
    }

    private fun container(parent: View, requireShown: Boolean = false): View? =
        listOf("seek_bar_controls", "live_radio_container").asSequence()
            .mapNotNull { visibleContainer(parent, it, requireShown) }.firstOrNull()

    /** A match-parent track's next bounds after native/container padding changes. */
    fun layoutBoundsIn(parent: ViewGroup): Rect? {
        val container = container(parent) ?: return null
        val track = find(container, "progress")?.takeIf { it.visibility == View.VISIBLE }
        return boundsIn(parent, Bounds(container,
            container.paddingLeft + (track?.paddingLeft ?: 0),
            container.width - container.paddingRight - (track?.paddingRight ?: 0)))
    }

    private fun visibleContainer(parent: View, name: String, requireShown: Boolean): View? {
        val first = find(parent, name) ?: return null
        fun ready(view: View) = view.width > 0 && view.height > 0 &&
            (if (requireShown) view.isShown else visibleWithin(view, parent))
        if (ready(first)) return first
        // A retained hidden SONG can precede the active QUEUE with the same resource ID.
        fun visit(view: View): View? {
            if (view !== parent && view.visibility != View.VISIBLE) return null
            if (view.id == first.id && ready(view)) return view
            if (view is ViewGroup) for (index in 0 until view.childCount)
                visit(view.getChildAt(index))?.let { return it }
            return null
        }
        return visit(parent)
    }

    private fun visibleWithin(view: View, parent: View): Boolean {
        var current: View? = view
        while (current != null && current !== parent) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return current === parent
    }

    fun boundsIn(parent: ViewGroup, content: Bounds): Rect = Rect().also {
        content.view.getDrawingRect(it)
        parent.offsetDescendantRectToMyCoords(content.view, it)
        it.left += content.start; it.right -= content.view.width - content.end
    }

    /** Local span excludes the parent host's own translation while lyrics close/open. */
    fun restingSpan(parent: ViewGroup): Pair<Int, Int>? {
        val content = resolve(parent) ?: return null
        val bounds = boundsIn(parent, content)
        return bounds.left to bounds.width()
    }

    fun bounds(container: View): Bounds? {
        val track = find(container, "progress")?.takeIf { it.visibility == View.VISIBLE && it.width > 0 } ?: container
        val start = track.paddingLeft
        val end = track.width - track.paddingRight
        return if (end > start) Bounds(track, start, end) else null
    }
}
