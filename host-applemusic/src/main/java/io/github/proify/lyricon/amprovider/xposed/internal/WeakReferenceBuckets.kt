/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.internal

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Identity-registration buckets keyed by a media id.
 *
 * Ported from HLE v8.2.1 (`1a665f69`): the row-bind path registered a binding by
 * scanning the whole `ConcurrentLinkedQueue` for its bucket, then scanning it
 * again for the instance identity. The bucket does the per-key de-duplication in
 * one `compute`, and a bounded periodic sweep drops dead references and empty
 * buckets so a key that is never visited again cannot leak its bucket forever.
 *
 * The first [sweepInterval] registrations sweep the whole table once, so a long
 * scrolling session pays one short pass per few hundred row binds instead of a
 * growing per-registration cost.
 */
internal class WeakReferenceBuckets<K : Any, T : Any>(
    private val sweepInterval: Int = DEFAULT_SWEEP_INTERVAL,
) {
    private val buckets = ConcurrentHashMap<K, ConcurrentLinkedQueue<WeakReference<T>>>()
    private val registrations = AtomicInteger()

    operator fun get(key: K): ConcurrentLinkedQueue<WeakReference<T>>? = buckets[key]

    val keyCount: Int get() = buckets.size

    /** Registers [value] under [key]; returns whether this identity was newly added. */
    fun add(key: K, value: T): Boolean {
        var added = false
        buckets.compute(key) { _, existing ->
            val refs = existing ?: ConcurrentLinkedQueue()
            var registered = false
            refs.forEach { ref ->
                val target = ref.get()
                if (target == null) refs.remove(ref) else if (target === value) registered = true
            }
            if (!registered) {
                refs.add(WeakReference(value))
                added = true
            }
            refs
        }
        if (registrations.incrementAndGet() % sweepInterval == 0) sweep()
        return added
    }

    /** Removes collected references and any bucket left empty. */
    fun sweep() {
        buckets.keys.forEach { key ->
            buckets.computeIfPresent(key) { _, refs ->
                refs.removeIf { it.get() == null }
                refs.takeIf { it.isNotEmpty() }
            }
        }
    }

    fun clear() {
        buckets.clear()
        registrations.set(0)
    }

    private companion object {
        /** One cheap full sweep per this many registrations. */
        const val DEFAULT_SWEEP_INTERVAL = 256
    }
}
