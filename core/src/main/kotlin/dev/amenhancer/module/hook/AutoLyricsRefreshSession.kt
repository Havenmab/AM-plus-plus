package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsEntry
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** One independent remote check per playback visit; never performs IO on the caller. */
class AutoLyricsRefreshSession(
    private val executor: Executor,
    private val refreshSong: (Long, () -> Boolean) -> CustomLyricsEntry?,
    private val isEnabled: () -> Boolean,
    private val onUpdated: (CustomLyricsEntry, () -> Boolean) -> Unit,
    private val logger: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var currentId: Long? = null
    private var scheduled = false
    private var fresh = false
    private var closed = false

    fun onSongChanged(appleMusicId: Long?, cacheReady: Boolean = true) {
        val id = appleMusicId?.takeIf { it > 0 }
        synchronized(lock) {
            if (closed) return
            if (currentId != id) {
                currentId = id
                generation++
                scheduled = false
                fresh = false
            }
            if (id == null || scheduled || !cacheReady || !isEnabled()) return
            val visit = generation
            scheduled = true
            val cancelled = { synchronized(lock) {
                closed || fresh || visit != generation || currentId != id || !isEnabled()
            } }
            try {
                executor.execute {
                    try {
                        if (cancelled()) return@execute
                        val entry = refreshSong(id, cancelled)
                        if (entry != null && !cancelled()) onUpdated(entry, cancelled)
                    } catch (error: Exception) {
                        logger("automatic lyrics update failed id=$id: $error")
                    }
                }
            } catch (_: RejectedExecutionException) {
                scheduled = false
                logger("automatic lyrics update was rejected id=$id")
            }
        }
    }

    /** A normal cache miss just downloaded this visit's lyrics; don't fetch them again. */
    fun markDownloaded(appleMusicId: Long) {
        synchronized(lock) {
            if (!closed && currentId == appleMusicId) { fresh = true; scheduled = true }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; generation++; currentId = null; scheduled = false }
    }
}
