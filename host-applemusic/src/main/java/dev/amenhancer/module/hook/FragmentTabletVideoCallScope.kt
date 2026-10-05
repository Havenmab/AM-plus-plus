package dev.amenhancer.module.hook

/** A native size update can query its surface more than once; the fallback lives only in that call. */
internal class FragmentTabletVideoCallScope {
    private data class Frame(val owner: Any, val staticUpdate: Boolean)
    private val frames = ThreadLocal<ArrayDeque<Frame>>()
    fun enter(owner: Any, staticUpdate: Boolean) {
        val stack = frames.get() ?: ArrayDeque<Frame>().also(frames::set)
        stack.addLast(Frame(owner, staticUpdate))
    }
    fun allows(owner: Any): Boolean = frames.get()?.lastOrNull()?.let {
        it.owner === owner && it.staticUpdate
    } == true
    fun leave(owner: Any) {
        val stack = frames.get() ?: return
        if (stack.lastOrNull()?.owner === owner) stack.removeLast()
        if (stack.isEmpty()) frames.remove()
    }
}
