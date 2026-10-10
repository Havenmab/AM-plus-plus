package dev.amenhancer.plugin.runtime

/** Concurrent callers wait for a fresh check; changes triggered by cleanup request another pass. */
internal class PluginConflictChecks(private val evaluate: () -> Unit) {
    private var checking = false
    private var pending = false

    @Synchronized fun check() {
        pending = true
        if (checking) return
        checking = true
        try {
            do { pending = false; evaluate() } while (pending)
        } finally { checking = false }
    }
}
