package dev.amenhancer.module.hook

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import java.lang.reflect.Method

internal class TabletChromeTopHeader(private val resourceId: (String) -> Int) : AutoCloseable {
    private var appBar: View? = null
    private var content: View? = null
    private var active = false
    private var backgrounds = emptyList<Background>()
    private var scrims = emptyList<Scrim>()
    private var divider: Divider? = null
    private var smallTitle: TabletChromeSmallTitle? = null
    private var libraryHeader: TabletChromeLibraryHeader? = null
    private var libraryPinned = false

    fun ignoresDependency(view: View, dependency: View): Boolean = TabletChromeHeaderPolicy.ignoresDependency(
        active, view === content, dependency === appBar, view.parent != null && view.parent === dependency.parent,
        libraryPinned,
    )

    fun update(bar: View?, page: View?, enabled: Boolean, pinLibrary: Boolean, selectedRootTitle: String?): Boolean {
        if (!enabled || bar == null || page == null || bar.parent == null || bar.parent !== page.parent) {
            return release()
        }
        val rebound = appBar !== bar || content !== page || !active
        if (rebound) {
            release()
            appBar = bar
            content = page
            val collapsing = child(bar, "collapsing_toolbar_layout")
            backgrounds = listOfNotNull(
                bar, collapsing, child(bar, "toolbar_actionbar"),
                child(bar, "app_bar_view_container"), child(bar, "header_page_layout"),
            ).distinct().map(::Background)
            scrims = listOfNotNull(
                collapsing?.let { Scrim.create(it, "getContentScrim", "setContentScrim") },
                collapsing?.let { Scrim.create(it, "getStatusBarScrim", "setStatusBarScrim") },
                Scrim.create(bar, "getStatusBarForeground", "setStatusBarForeground"),
            )
            divider = child(bar, "toolbar_divider")?.let(::Divider)
            smallTitle = (child(bar, "main_title") as? TextView)?.let(::TabletChromeSmallTitle)
            libraryHeader = collapsing?.let { TabletChromeLibraryHeader.create(bar, it) }
            active = true
            (page.parent as? View)?.requestLayout()
        }
        var needsLayout = rebound
        if (libraryPinned != pinLibrary) {
            libraryPinned = pinLibrary
            (page.parent as? View)?.requestLayout()
            needsLayout = true
        }
        val headerChanged = if (pinLibrary) libraryHeader?.pin() == true else libraryHeader?.restore() == true
        needsLayout = needsLayout || headerChanged
        backgrounds.forEach(Background::suppress)
        scrims.forEach(Scrim::suppress)
        divider?.suppress()
        smallTitle?.update(selectedRootTitle)
        return needsLayout
    }

    private fun child(parent: View, name: String): View? = resourceId(name).takeIf { it != 0 }?.let {
        parent.findViewById<View>(it)
    }

    private fun release(): Boolean {
        if (!active) return false
        active = false
        backgrounds.forEach(Background::restore)
        scrims.forEach(Scrim::restore)
        divider?.restore()
        smallTitle?.restore()
        libraryHeader?.restore()
        (content?.parent as? View)?.requestLayout()
        appBar = null
        content = null
        backgrounds = emptyList()
        scrims = emptyList()
        divider = null
        smallTitle = null
        libraryHeader = null
        libraryPinned = false
        return true
    }

    override fun close() {
        release()
    }

    private class Background(private val view: View) {
        private var drawable: Drawable? = null
        private var nativeAlpha = 255

        fun suppress() {
            val current = view.background?.mutate()
            if (current !== drawable) {
                restore()
                drawable = current
                nativeAlpha = current?.alpha ?: 255
            }
            if (current != null && current.alpha != 0) {
                nativeAlpha = current.alpha
                current.alpha = 0
            }
        }

        fun restore() {
            drawable?.takeIf { it.alpha == 0 }?.let { it.alpha = nativeAlpha }
        }
    }

    private class Divider(private val view: View) {
        private var nativeAlpha = view.alpha

        fun suppress() {
            if (view.alpha != 0f) {
                nativeAlpha = view.alpha
                view.alpha = 0f
            }
        }

        fun restore() {
            if (view.alpha == 0f) view.alpha = nativeAlpha
        }
    }

    private class Scrim(private val view: View, private val getter: Method, private val setter: Method) {
        private var native: Drawable? = null

        fun suppress() {
            val current = getter.invoke(view) as? Drawable ?: return
            native = current
            setter.invoke(view, null as Any?)
        }

        fun restore() {
            if (getter.invoke(view) == null) setter.invoke(view, native)
        }

        companion object {
            fun create(view: View, getter: String, setter: String): Scrim? = runCatching {
                Scrim(view, view.javaClass.getMethod(getter), view.javaClass.getMethod(setter, Drawable::class.java))
            }.getOrNull()
        }
    }
}
