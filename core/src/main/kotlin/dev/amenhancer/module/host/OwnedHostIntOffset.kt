package dev.amenhancer.module.host

/** Adds a layout offset without accumulating it or losing later native writes. */
class OwnedHostIntOffset(
    private val read: () -> Int,
    private val write: (Int) -> Unit,
    private val adjust: (Int, Int) -> Int = { native, offset -> native + offset },
) : AutoCloseable {
    private var native = read()
    private var offset = 0
    private var last: Int? = null
    private var writing = false

    /** Called before the native setter, including writes equal to our current value. */
    fun hostWrite(value: Int): Int? {
        if (writing) return null
        native = value
        return adjust(value, offset).also { last = it }
    }

    fun setOffset(value: Int): Boolean {
        val current = read()
        if (last == null || current != last) native = current
        offset = value
        val desired = adjust(native, value)
        last = desired
        if (current == desired) return false
        writeOwned(desired)
        return true
    }

    private fun writeOwned(value: Int) {
        writing = true
        try { write(value) } finally { writing = false }
    }

    override fun close() {
        if (last == null) return
        if (read() == last && last != native) writeOwned(native)
        offset = 0
        last = null
    }
}
