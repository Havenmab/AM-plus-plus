package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log

/**
 * Debug-only sink for the AMLL to Apple conversion, printed under `AMPP-Meta`.
 *
 * The conversion is the one place where an AMLL document's pronunciation lane can
 * be lost before Apple ever parses it, so a device log has to be able to separate
 * "the source carried no reading" from "the reading was dropped on the way in".
 * The converter writes two lines per conversion: the source document's shape on
 * entry (how many inline `x-roman` spans, whether the head already declares
 * a lane) and, on exit, the head lane Apple will read (its language tag, how many
 * entries carry timed spans, and the timing verdict) beside how many line-level
 * readings this conversion had to split per word.
 *
 * Silent unless the process was started with `log.tag.AMPP-Meta=DEBUG`, so nothing
 * is written on a normal build; the line budget keeps a burst of re-fetches (a
 * track whose lookup is retried on every presentation) from filling logcat. Every
 * failure is swallowed: a diagnostic must never fail a lyrics fetch.
 */
internal object AmllConversionDiagnostics {

    /** The tag to watch: `adb logcat -s AMPP-Meta` with `log.tag.AMPP-Meta=DEBUG` set. */
    const val TAG = "AMPP-Meta"

    /** How many lines one window may write, so a retry loop cannot flood the log. */
    const val MAX_LINES = 4

    /** The window those lines are counted over. */
    const val WINDOW_MS = 4_000L

    private val enabled: Boolean
        get() = runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)

    private var windowStart = 0L
    private var windowLines = 0

    /** Writes one line when the tag is enabled and the window still has budget. */
    fun log(line: String) {
        if (!enabled) return
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now
                windowLines = 0
            }
            if (windowLines >= MAX_LINES) return
            windowLines += 1
        }
        runCatching { Log.d(TAG, line) }
    }
}
