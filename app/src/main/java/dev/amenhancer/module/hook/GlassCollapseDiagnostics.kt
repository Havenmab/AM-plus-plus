package dev.amenhancer.module.hook

import android.os.SystemClock

/**
 * Rate-limited trace of the collapse signal path.
 *
 * The bar has two signal sources and neither is visible from the outside: a Compose page exposes
 * no native scroll container at all, and "found no targets" and "found targets that never moved"
 * call for different fixes. Recording both — plus which source actually moved the bar — turns a
 * device session into a readable trace instead of a guess.
 *
 * [log] repeats only when the text changes or the previous line is older than [WINDOW_MS], so a
 * gesture cannot flood logcat and a steady state stays silent. [event] is for state transitions,
 * which are rare by construction and each worth a line.
 */
internal object GlassCollapseDiagnostics {
    private const val WINDOW_MS = 1000L
    private var lastAt = 0L
    private var lastMessage: String? = null

    fun log(message: String) {
        val now = SystemClock.uptimeMillis()
        if (message == lastMessage && now - lastAt < WINDOW_MS) return
        lastMessage = message
        lastAt = now
        ModernXposedRuntime.log("collapse: $message")
    }

    fun event(message: String) {
        lastMessage = message
        lastAt = SystemClock.uptimeMillis()
        ModernXposedRuntime.log("collapse: $message")
    }
}
