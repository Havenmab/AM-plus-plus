package dev.amenhancer.module.hook

/**
 * Distinguishes Apple's own TTML parses from the module's parser calls.
 *
 * The capture hook is installed on the same native entry point the module uses
 * to build every replacement pointer, so a capture that did not exclude the
 * module's own parses would record our generated document as if it were Apple's
 * — and the translation pass would then merge a provider lane into its own
 * output instead of into the displayed Apple document.
 *
 * The gate is a thread-local depth counter because the hook runs synchronously
 * on the calling thread inside the reflective invoke, so nesting (a hook that
 * itself parses) stays correct without any cross-thread state.
 */
internal object NativeTtmlParseOrigin {
    private val depth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    fun <T> moduleInitiated(block: () -> T): T {
        depth.set(depth.get() + 1)
        return try {
            block()
        } finally {
            depth.set(depth.get() - 1)
        }
    }

    /** True while the current thread is inside a module-initiated native parse. */
    fun isModuleInitiated(): Boolean = depth.get() > 0
}
