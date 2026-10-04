package dev.amenhancer.module.hook

internal class TabletChromeBackdropRefreshPolicy {
    private companion object {
        val SETTLE_DELAYS_MS = longArrayOf(0L, 80L, 320L, 960L)
        const val MIN_CAPTURE_INTERVAL_MS = 64L
    }

    private var visible = false
    private var refreshStartedAt = 0L
    private var settleIndex = SETTLE_DELAYS_MS.size
    private var lastCaptureAt: Long? = null
    var nextCaptureAt: Long? = null
        private set

    fun setVisible(wanted: Boolean, now: Long) {
        if (visible == wanted) return
        visible = wanted
        if (wanted) requestRefresh(now) else nextCaptureAt = null
    }

    fun requestRefresh(now: Long) {
        refreshStartedAt = now
        settleIndex = 0
        val earliest = maxOf(now, lastCaptureAt?.plus(MIN_CAPTURE_INTERVAL_MS) ?: now)
        nextCaptureAt = if (visible) minOf(nextCaptureAt ?: earliest, earliest) else null
    }

    fun takeCapture(now: Long): Boolean {
        val scheduled = nextCaptureAt ?: return false
        if (!visible || now < scheduled) return false
        lastCaptureAt = now
        settleIndex++
        nextCaptureAt = if (settleIndex < SETTLE_DELAYS_MS.size) {
            maxOf(now + MIN_CAPTURE_INTERVAL_MS, refreshStartedAt + SETTLE_DELAYS_MS[settleIndex])
        } else null
        return true
    }
}
