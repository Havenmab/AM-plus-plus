package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method

internal class TabletChromeLibraryHeader private constructor(
    private val bar: View,
    private val collapsing: View,
    private val getFlags: Method,
    private val setFlags: Method,
    private val setExpanded: Method,
) {
    private var parameters: ViewGroup.LayoutParams? = null
    private var nativeFlags = 0
    private var expanded = false

    fun pin(): Boolean {
        val current = collapsing.layoutParams ?: return false
        var changed = false
        if (parameters !== current) {
            restore()
            parameters = current
            nativeFlags = (getFlags.invoke(current) as Number).toInt()
        }
        val flags = (getFlags.invoke(current) as Number).toInt()
        if (flags != 0) {
            nativeFlags = flags
            setFlags.invoke(current, 0)
            collapsing.layoutParams = current
            changed = true
        }
        if (!expanded) {
            setExpanded.invoke(bar, true, false)
            expanded = true
            changed = true
        }
        return changed
    }

    fun restore(): Boolean {
        val saved = parameters ?: return false
        var changed = false
        if (collapsing.layoutParams === saved && (getFlags.invoke(saved) as Number).toInt() == 0 && nativeFlags != 0) {
            setFlags.invoke(saved, nativeFlags)
            collapsing.layoutParams = saved
            changed = true
        }
        parameters = null
        expanded = false
        return changed
    }

    companion object {
        fun create(bar: View, collapsing: View): TabletChromeLibraryHeader? = runCatching {
            val type = collapsing.layoutParams.javaClass
            TabletChromeLibraryHeader(
                bar, collapsing, type.getMethod("getScrollFlags"),
                type.getMethod("setScrollFlags", Int::class.javaPrimitiveType!!),
                bar.javaClass.getMethod("setExpanded", Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            )
        }.getOrNull()
    }
}
