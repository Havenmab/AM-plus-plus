/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.internal

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Identity-keyed map with weak keys.
 *
 * Ported from HLE v8.2.1 (`1a665f69`): every lookup used to allocate a
 * `IdentityWeakReference`, which the row-bind path paid on each get. The keys are
 * still [IdentityWeakReference]s, but a map lookup now uses a reusable
 * [IdentityProbe] that compares by identity and is cleared immediately, so a hot
 * `get`/`remove` allocates nothing. [putIfAbsent] also lets callers register an
 * object without building a `List<K>` first.
 */
internal class WeakIdentityMap<K : Any, V> {
    private val queue = ReferenceQueue<K>()

    /** Keys are always [IdentityWeakReference]; [probe] matches them without allocating one. */
    private val values = LinkedHashMap<Any, V>()

    /** Only used while holding the `values` lock, and cleared immediately, so it never pins a key. */
    private val probe = IdentityProbe()

    operator fun get(key: K): V? = synchronized(values) {
        removeCollectedKeys()
        probe.lookup(key) { values[it] }
    }

    operator fun set(key: K, value: V) {
        synchronized(values) {
            removeCollectedKeys()
            values[IdentityWeakReference(key, queue)] = value
        }
    }

    /** Registers [value] only when [key] has no entry yet; returns whether a new entry was written. */
    fun putIfAbsent(key: K, value: V): Boolean = synchronized(values) {
        removeCollectedKeys()
        if (probe.lookup(key) { values.containsKey(it) }) return@synchronized false
        values[IdentityWeakReference(key, queue)] = value
        true
    }

    /** Live keys in registration order. */
    fun keys(): List<K> = synchronized(values) {
        removeCollectedKeys()
        @Suppress("UNCHECKED_CAST")
        values.keys.mapNotNull { (it as IdentityWeakReference<K>).get() }
    }

    fun remove(key: K) {
        synchronized(values) {
            removeCollectedKeys()
            probe.lookup(key) { stored ->
                values.remove(stored)
                Unit
            }
        }
    }

    fun clear() {
        synchronized(values) {
            values.clear()
            while (queue.poll() != null) Unit
        }
    }

    private fun removeCollectedKeys() {
        while (true) {
            val reference = queue.poll() ?: return
            values.remove(reference)
        }
    }
}

private class IdentityWeakReference<T : Any>(
    referent: T,
    queue: ReferenceQueue<T>? = null,
) : WeakReference<T>(referent, queue) {
    private val identityHashCode = System.identityHashCode(referent)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val referent = get() ?: return false
        return when (other) {
            is IdentityWeakReference<*> -> referent === other.get()
            is IdentityProbe -> referent === other.referent
            else -> false
        }
    }
}

/** A reusable identity-equal lookup key; only valid for the duration of one [lookup]. */
private class IdentityProbe {
    var referent: Any? = null
        private set
    private var identityHashCode = 0

    inline fun <R> lookup(key: Any, block: (Any) -> R): R {
        referent = key
        identityHashCode = System.identityHashCode(key)
        try {
            return block(this)
        } finally {
            referent = null
        }
    }

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val key = referent ?: return false
        return other is IdentityWeakReference<*> && other.get() === key
    }
}
