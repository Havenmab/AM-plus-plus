package dev.amenhancer.module.hook

import android.view.View
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import java.lang.reflect.Modifier

/**
 * HLE's `refreshAppleLyricsRecyclerView` → `appleRecyclerNotifyDataSetChanged`
 * tail, ported onto the fork's presentation-refresh path.
 *
 * The lyrics adapter reads the pronunciation-enabled flags once at bind, so
 * re-invoking Apple's own result presentation is not enough on its own: the
 * rows have to be rebound before a late online/official lane becomes visible.
 * HLE resolves the RecyclerView from the fragment, takes its adapter and only
 * then notifies it, deferring to the next frame while the layout manager is
 * computing a layout (a `notifyDataSetChanged` in that window is dropped), and
 * requesting the *full* bind rather than the partial translation payload (the
 * karaoke adapter appends duplicate child rows for the payload).
 *
 * The fork had no rebind at all. This mirrors HLE's sequence with the fork's
 * reflection helpers and keeps every step fail-open:
 *
 *  - the RecyclerView accessor is the one the fork already uses
 *    (`LyricsTypefaceSession`, `TabletLyricTypography` and HLE's 1606 inherited
 *    `LYRICS_UI_RECYCLER_VIEW_METHOD` all resolve
 *    `PlayerLyricsViewFragment#getRecyclerView`); a profile-pinned accessor is
 *    tried first, then that verified fallback;
 *  - the resolved instance is validated as an
 *    `androidx.recyclerview.widget.RecyclerView` by superclass *name* before any
 *    reflective call, so a same-named unrelated getter or field can never be
 *    touched and no androidx class needs to be resolved from the module
 *    classloader;
 *  - the adapter is `RecyclerView#getAdapter()`, the standard
 *    `getItemCount()`/`notifyDataSetChanged()` are preferred, and the pinned
 *    obfuscated `LYRICS_ADAPTER_ITEM_COUNT_METHOD` /
 *    `LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD` members are the fallback.
 *
 * No Android type is referenced except the framework `View` the RecyclerView is
 * posted through, so the helper is safe to build for every profile.
 */
internal class AppleLyricsPresentationRebind(
    /** Candidate fragment accessors, the profile-pinned one first. */
    private val recyclerMethodNames: List<String>,
    /** The pinned `LYRICS_ADAPTER_ITEM_COUNT_METHOD` fallbacks, in profile order. */
    private val adapterItemCountMemberNames: List<String>,
    /** The pinned `LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD` fallbacks, in profile order. */
    private val adapterNotifyMemberNames: List<String>,
) {
    /** What the rebind managed to touch, for the `adapter=` diagnostic. */
    data class Result(val didNotify: Boolean, val adapterName: String?)

    /**
     * Rebind the lyrics rows of [fragment] on the current (main) thread.
     * [didNotify] is true when the full-bind notification was issued (possibly
     * deferred to the next frame); [adapterName] is the resolved adapter's class
     * name, or null when no lyrics RecyclerView/adapter could be resolved.
     */
    fun rebind(fragment: Any?): Result {
        if (fragment == null) return Result(false, null)
        val recycler = runCatching { resolveRecyclerView(fragment) }.getOrNull()
            ?: return Result(false, null)
        val adapter = runCatching { callOrNull(recycler, GET_ADAPTER) }.getOrNull()
            ?: return Result(false, null)
        val adapterName = adapter.javaClass.name
        if (runCatching { adapterItemCount(adapter) }.getOrDefault(0) <= 0) {
            return Result(false, adapterName)
        }
        val recyclerView = recycler as? View ?: return Result(false, adapterName)
        if (runCatching { isComputingLayout(recycler) }.getOrDefault(false)) {
            // HLE re-queues the rebind on the next frame while the layout is
            // being computed; notifying now would be dropped.
            recyclerView.postOnAnimation { runCatching { notifyDataSetChanged(adapter) } }
            return Result(true, adapterName)
        }
        return runCatching { notifyDataSetChanged(adapter) }.fold(
            onSuccess = { Result(true, adapterName) },
            onFailure = { Result(false, adapterName) },
        )
    }

    private fun resolveRecyclerView(fragment: Any): Any? {
        recyclerMethodNames.forEach { name ->
            val candidate = runCatching { callOrNull(fragment, name) }.getOrNull()
            if (isRecyclerView(candidate)) return candidate
        }
        return directRecyclerViewField(fragment)
    }

    /**
     * HLE's `directRecyclerViewField`: the last resort when the fragment's
     * accessor is renamed — an instance field whose value is a RecyclerView.
     */
    private fun directRecyclerViewField(instance: Any): Any? =
        generateSequence(instance.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    if (!View::class.java.isAssignableFrom(field.type)) return@runCatching null
                    field.isAccessible = true
                    field.get(instance)?.takeIf(::isRecyclerView)
                }.getOrNull()
            }

    /** HLE's `isAppleRecyclerViewClass`, by name so the classloaders never matter. */
    private fun isRecyclerView(value: Any?): Boolean =
        value is View && generateSequence(value.javaClass as Class<*>?) { it.superclass }
            .any { it.name == RECYCLER_VIEW_CLASS_NAME }

    private fun isComputingLayout(recycler: Any): Boolean =
        callOrNull(recycler, IS_COMPUTING_LAYOUT) as? Boolean ?: false

    private fun adapterItemCount(adapter: Any): Int {
        val method = sequenceOf(GET_ITEM_COUNT)
            .plus(adapterItemCountMemberNames)
            .mapNotNull { name ->
                AppleReflection.findMethodOrNull(adapter.javaClass, name, parameterCount = 0)
            }
            .firstOrNull()
            ?: return 0
        return ((method.invoke(adapter) as? Number)?.toInt() ?: 0).coerceAtLeast(0)
    }

    /**
     * HLE: the standard `notifyDataSetChanged` first, else the profile's
     * obfuscated notify member. The full bind is deliberate: the partial
     * translation payload makes Apple's karaoke adapter append duplicate rows.
     */
    private fun notifyDataSetChanged(adapter: Any) {
        val method = sequenceOf(NOTIFY_DATA_SET_CHANGED)
            .plus(adapterNotifyMemberNames)
            .mapNotNull { name ->
                AppleReflection.findMethodOrNull(adapter.javaClass, name, parameterCount = 0)
            }
            .firstOrNull()
            ?: error("no notifyDataSetChanged on ${adapter.javaClass.name}")
        method.invoke(adapter)
    }

    /** A zero-argument reflective read; absent members and throws both return null. */
    private fun callOrNull(receiver: Any, name: String): Any? {
        val method = AppleReflection.findMethodOrNull(
            receiver.javaClass,
            name,
            parameterCount = 0,
        ) ?: return null
        return runCatching { method.invoke(receiver) }.getOrNull()
    }

    private companion object {
        const val RECYCLER_VIEW_CLASS_NAME = "androidx.recyclerview.widget.RecyclerView"
        const val GET_ADAPTER = "getAdapter"
        const val GET_ITEM_COUNT = "getItemCount"
        const val NOTIFY_DATA_SET_CHANGED = "notifyDataSetChanged"
        const val IS_COMPUTING_LAYOUT = "isComputingLayout"
    }
}
