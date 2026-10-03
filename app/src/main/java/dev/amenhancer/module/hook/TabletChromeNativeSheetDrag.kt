package dev.amenhancer.module.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method

internal class TabletChromeNativeSheetDrag private constructor(
    private val behavior: Any,
    private val sheet: View,
    private val parent: ViewGroup,
    private val intercept: Method,
    private val touch: Method,
) {
    private val parentLocation = IntArray(2)
    private var lastEvent: MotionEvent? = null

    fun start(down: MotionEvent): Boolean {
        if (down.actionMasked != MotionEvent.ACTION_DOWN || !attached()) return false
        val local = inParent(down)
        return try {
            intercept.invoke(behavior, parent, sheet, local)
            deliver(local)
        } finally {
            local.recycle()
        }
    }

    fun dispatch(event: MotionEvent): Boolean {
        if (!attached()) return false
        val local = inParent(event)
        return try {
            deliver(local)
        } finally {
            local.recycle()
        }
    }

    fun finish() {
        lastEvent?.recycle()
        lastEvent = null
    }

    fun cancel() {
        val last = lastEvent ?: return
        try {
            if (attached()) {
                last.action = MotionEvent.ACTION_CANCEL
                touch.invoke(behavior, parent, sheet, last)
            }
        } finally {
            finish()
        }
    }

    private fun attached(): Boolean = sheet.isAttachedToWindow && sheet.parent === parent

    private fun inParent(event: MotionEvent): MotionEvent {
        parent.getLocationOnScreen(parentLocation)
        return MotionEvent.obtain(event).apply {
            setLocation(event.rawX - parentLocation[0], event.rawY - parentLocation[1])
        }
    }

    private fun deliver(event: MotionEvent): Boolean {
        lastEvent?.recycle()
        lastEvent = MotionEvent.obtain(event)
        return touch.invoke(behavior, parent, sheet, event) as? Boolean == true
    }

    companion object {
        fun resolve(behavior: Any, sheet: View): TabletChromeNativeSheetDrag? = runCatching {
            val parent = sheet.parent as? ViewGroup ?: return@runCatching null
            val coordinator = generateSequence(parent.javaClass as Class<*>?) { it.superclass }
                .firstOrNull { it.name == "androidx.coordinatorlayout.widget.CoordinatorLayout" }
                ?: return@runCatching null
            val base = generateSequence(behavior.javaClass as Class<*>?) { it.superclass }
                .firstOrNull { it.name == "com.google.android.material.bottomsheet.BottomSheetBehavior" }
                ?: return@runCatching null
            val intercept = PhoneGlassRuntime.method(behavior.javaClass, "h", coordinator, View::class.java, MotionEvent::class.java)
            val touch = base.getDeclaredMethod("s", coordinator, View::class.java, MotionEvent::class.java)
            if (intercept.returnType != Boolean::class.javaPrimitiveType ||
                touch.returnType != Boolean::class.javaPrimitiveType
            ) return@runCatching null
            intercept.isAccessible = true
            touch.isAccessible = true
            TabletChromeNativeSheetDrag(behavior, sheet, parent, intercept, touch)
        }.getOrNull()
    }
}
