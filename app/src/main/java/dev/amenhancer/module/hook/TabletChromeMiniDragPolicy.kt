package dev.amenhancer.module.hook

import kotlin.math.abs

internal class TabletChromeMiniDragPolicy(private val touchSlop: Float) {
    private var downTime: Long? = null
    private var downX = 0f
    private var downY = 0f
    var dragging = false
        private set

    fun start(time: Long, x: Float, y: Float, hitCapsule: Boolean) {
        clear()
        if (!hitCapsule || !x.isFinite() || !y.isFinite() || !touchSlop.isFinite() || touchSlop < 0f) return
        downTime = time
        downX = x
        downY = y
    }

    fun owns(time: Long): Boolean = downTime == time

    fun move(time: Long, x: Float, y: Float): Boolean {
        if (!owns(time) || !x.isFinite() || !y.isFinite()) return false
        if (dragging) return true
        val upward = downY - y
        val horizontal = abs(x - downX)
        dragging = upward > touchSlop && upward > horizontal
        return dragging
    }

    fun clear() {
        downTime = null
        dragging = false
    }
}
