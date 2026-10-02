package dev.amenhancer.module.hook

import android.graphics.drawable.Drawable
import android.view.View
import java.lang.reflect.Method

internal class TabletChromeTopHeader(private val resourceId: (String) -> Int) : AutoCloseable {
    private var appBar: View? = null
    private var content: View? = null
    private var active = false
    private var backgrounds = emptyList<Background>()
    private var scrims = emptyList<Scrim>()
    private var divider: Divider? = null

    fun ignoresDependency(view: View, dependency: View): Boolean = TabletChromeHeaderPolicy.ignoresDependency(
        active, view === content, dependency === appBar, view.parent != null && view.parent === dependency.parent,
    )

    fun update(bar: View?, page: View?, enabled: Boolean): Boolean {
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
            active = true
            (page.parent as? View)?.requestLayout()
        }
        backgrounds.forEach(Background::suppress)
        scrims.forEach(Scrim::suppress)
        divider?.suppress()
        return rebound
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
        (content?.parent as? View)?.requestLayout()
        appBar = null
        content = null
        backgrounds = emptyList()
        scrims = emptyList()
        divider = null
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
