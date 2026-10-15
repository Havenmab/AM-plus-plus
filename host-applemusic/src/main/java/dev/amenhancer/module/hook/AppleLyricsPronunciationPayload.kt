package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.NativeLyricModelPolicy
import dev.amenhancer.module.lyrics.online.PronunciationPayloadOutcome
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Replays the app's own pronunciation payload onto the live lyrics adapter.
 *
 * `PlayerLyricsViewFragment.F2` is the only writer of the live adapter's
 * pronunciation flag `n1.e`, and the app's own preference observer is the only
 * other code that re-applies it — both through `n1`'s payload
 * `notifyItemRangeChanged(0, count, n1.i)`, which `Y0.l()`/`A.l()` handle
 * outside their per-row text memo. After our presentation refresh rebinds the
 * rows, the flag can still be the stale `false` `F2` latched when Apple had not
 * advertised its lane yet, and a plain `notifyDataSetChanged` cannot repair it
 * (the row skips its whole text/visibility block when its cached strings are
 * unchanged). This helper therefore performs exactly the app's own dispatch:
 *
 * ```
 * n1 adapter = fragment.p0;             // live Y0 (lines) or A (words)
 * if (!adapter.e) adapter.e = true;     // pronunciation-selected flag
 * adapter.a.d(0, adapter.d(), n1.i);    // RecyclerView.g#d(int,int,Object)
 * ```
 *
 * The adapter field (`p0`) and the flag (`e`) are the profile-pinned
 * `LYRICS_UI_ADAPTER_FIELD` / `LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD`
 * names. The three surfaces the 1606 profile does not pin are resolved from
 * this build's verified shape (all from the 1606 investigation, `dexdump`d):
 * `RecyclerView.f` exposes its `AdapterDataObservable` in field `a`; the 3-arg
 * `RecyclerView.Adapter#notifyItemRangeChanged` was inlined away, so the
 * dispatch goes through `RecyclerView.g#d(int,int,Object)`; and `n1` carries
 * the static payload name in field `i` (its value ends with
 * `PAYLOAD_TOGGLE_PRONUNCIATION`). Each has a structural fallback and every step
 * fails open: an unresolved member or a throwing call leaves Apple's own state
 * untouched.
 *
 * The helper never touches `n1.f` (it chooses which text lands in the *main*
 * line) and never touches `n1.d` (translation). On a failed dispatch the flag is
 * restored so a later retry is not swallowed by `already-true`.
 */
internal class AppleLyricsPronunciationPayload(
    /** Candidate fragment adapter fields, profile-pinned first. */
    private val adapterFieldNames: List<String>,
    /** Candidate `n1.e` fields, profile-pinned first. */
    private val adapterFlagFieldNames: List<String>,
    /** Candidate `getItemCount()` names, profile-pinned first. */
    private val adapterItemCountMemberNames: List<String>,
) {
    /** What one re-application resolved and did, for `payload=`/`adapterE=`. */
    data class Result(
        val outcome: PronunciationPayloadOutcome,
        val adapterName: String?,
        val before: Boolean,
        val after: Boolean,
    )

    /** The resolved reflection surface of one live adapter. */
    private class Resolved(
        val adapter: Any,
        val adapterName: String,
        val flag: Field,
        val itemCount: Int,
        val observable: Any,
        val payloadMethod: Method,
        val payload: Any,
    )

    /**
     * Resolve the live adapter and, when a confirmed Apple lane exists and the
     * user's [pronunciationSelected] preference allows it, set `n1.e` and replay
     * the app's own pronunciation payload. This must run *after* the caller's
     * presentation invoke and adapter rebind, otherwise `F2`'s own `e=false` +
     * payload would undo it.
     */
    fun apply(
        fragment: Any?,
        hasConfirmedOfficialLane: Boolean,
        pronunciationSelected: Boolean,
    ): Result {
        if (!hasConfirmedOfficialLane) {
            return Result(PronunciationPayloadOutcome.NOT_AVAILABLE, null, false, false)
        }
        if (!pronunciationSelected) {
            return Result(PronunciationPayloadOutcome.PREFERENCE_OFF, null, false, false)
        }
        val resolved = runCatching { resolve(fragment) }.getOrNull()
            ?: return Result(PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE, null, false, false)
        val before = runCatching { resolved.flag.getBoolean(resolved.adapter) }.getOrNull()
        val outcome = NativeLyricModelPolicy.pronunciationPayloadOutcome(
            hasConfirmedOfficialLane = true,
            pronunciationSelected = true,
            adapterPronunciationSelected = before,
        )
        if (outcome != PronunciationPayloadOutcome.DISPATCHED) {
            val flag = before ?: false
            return Result(outcome, resolved.adapterName, flag, flag)
        }
        if (resolved.itemCount <= 0) {
            // No rows have been bound yet: the payload would be dropped, so keep
            // the attempt retryable (the same rule the rebind uses) and leave
            // the flag alone.
            val flag = before ?: false
            return Result(
                PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE,
                resolved.adapterName,
                flag,
                flag,
            )
        }
        val dispatched = runCatching {
            resolved.flag.setBoolean(resolved.adapter, true)
            resolved.payloadMethod.invoke(
                resolved.observable,
                PAYLOAD_POSITION_START,
                resolved.itemCount,
                resolved.payload,
            )
        }.isSuccess
        if (!dispatched) {
            // A half-applied attempt must not look `already-true` to the retry:
            // restore the flag so the next refresh re-dispatches the payload.
            runCatching { resolved.flag.setBoolean(resolved.adapter, before ?: false) }
        }
        val after = runCatching { resolved.flag.getBoolean(resolved.adapter) }
            .getOrDefault(before ?: false)
        return Result(
            outcome = if (dispatched) {
                PronunciationPayloadOutcome.DISPATCHED
            } else {
                PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE
            },
            adapterName = resolved.adapterName,
            before = before ?: false,
            after = after,
        )
    }

    private fun resolve(fragment: Any?): Resolved? {
        if (fragment == null) return null
        val adapter = resolveAdapter(fragment) ?: return null
        val flag = resolveFlagField(adapter) ?: return null
        val observable = resolveObservable(adapter) ?: return null
        val payloadMethod = resolvePayloadMethod(observable) ?: return null
        val payload = resolvePayload(adapter) ?: return null
        return Resolved(
            adapter = adapter,
            adapterName = adapter.javaClass.name,
            flag = flag,
            itemCount = resolveItemCount(adapter),
            observable = observable,
            payloadMethod = payloadMethod,
            payload = payload,
        )
    }

    /**
     * The fragment's live lyrics adapter, validated by the presence of the
     * pinned `n1.e` flag so an unrelated same-named field can never be touched.
     */
    private fun resolveAdapter(fragment: Any): Any? {
        adapterFieldNames.forEach { name ->
            val candidate = runCatching { readFieldOrNull(fragment, name) }.getOrNull()
            if (candidate != null && resolveFlagField(candidate) != null) return candidate
        }
        return null
    }

    private fun resolveFlagField(adapter: Any): Field? {
        adapterFlagFieldNames.forEach { name ->
            val field = fieldOrNull(adapter.javaClass, name) ?: return@forEach
            if (!Modifier.isStatic(field.modifiers) && field.type == java.lang.Boolean.TYPE) {
                runCatching { field.isAccessible = true }
                return field
            }
        }
        return null
    }

    private fun resolveItemCount(adapter: Any): Int {
        val method = sequenceOf(GET_ITEM_COUNT)
            .plus(adapterItemCountMemberNames)
            .mapNotNull { name ->
                AppleReflection.findMethodOrNull(adapter.javaClass, name, parameterCount = 0)
            }
            .firstOrNull()
            ?: return 0
        return runCatching { (method.invoke(adapter) as? Number)?.toInt() ?: 0 }
            .getOrDefault(0)
            .coerceAtLeast(0)
    }

    /**
     * The adapter's `RecyclerView.g` (`AdapterDataObservable`). The verified
     * field `a` is tried first, then any instance field of the hierarchy whose
     * value exposes the 3-arg payload dispatch.
     */
    private fun resolveObservable(adapter: Any): Any? {
        val named = fieldOrNull(adapter.javaClass, VERIFIED_OBSERVABLE_FIELD)
        if (named != null && !Modifier.isStatic(named.modifiers)) {
            val value = runCatching {
                named.isAccessible = true
                named.get(adapter)
            }.getOrNull()
            if (value != null && resolvePayloadMethod(value) != null) return value
        }
        return hierarchy(adapter.javaClass)
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull()
            }
            .firstOrNull { candidate -> resolvePayloadMethod(candidate) != null }
    }

    /** `RecyclerView.g#d(int,int,Object)`: the app's inlined-away 3-arg notify. */
    private fun resolvePayloadMethod(observable: Any?): Method? {
        if (observable == null) return null
        val direct = runCatching {
            observable.javaClass.getMethod(
                VERIFIED_PAYLOAD_METHOD,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                Any::class.java,
            )
        }.getOrNull()
        if (direct != null) return direct
        return observable.javaClass.methods.firstOrNull { method ->
            method.name == VERIFIED_PAYLOAD_METHOD &&
                method.parameterTypes.contentEquals(PAYLOAD_METHOD_PARAMETERS)
        }
    }

    /**
     * `n1`'s static `PAYLOAD_TOGGLE_PRONUNCIATION` name. Found by suffix so the
     * obfuscated field name does not matter; the verified `i` is the fallback.
     */
    private fun resolvePayload(adapter: Any): Any? {
        hierarchy(adapter.javaClass)
            .flatMap { it.declaredFields.asSequence() }
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .forEach { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(null) as? String
                }.getOrNull()
                if (value != null && value.endsWith(PAYLOAD_SUFFIX)) return value
            }
        val field = fieldOrNull(adapter.javaClass, VERIFIED_PAYLOAD_FIELD) ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }

    private fun readFieldOrNull(instance: Any, name: String): Any? {
        val field = fieldOrNull(instance.javaClass, name) ?: return null
        field.isAccessible = true
        return field.get(instance)
    }

    private fun fieldOrNull(clazz: Class<*>, name: String): Field? =
        runCatching { clazz.getField(name) }.getOrNull()
            ?: hierarchy(clazz).firstNotNullOfOrNull { type ->
                runCatching { type.getDeclaredField(name) }.getOrNull()
            }

    private fun hierarchy(clazz: Class<*>): Sequence<Class<*>> =
        generateSequence(clazz as Class<*>?) { it.superclass }

    private companion object {
        const val GET_ITEM_COUNT = "getItemCount"
        const val VERIFIED_OBSERVABLE_FIELD = "a"
        const val VERIFIED_PAYLOAD_METHOD = "d"
        const val VERIFIED_PAYLOAD_FIELD = "i"
        const val PAYLOAD_POSITION_START = 0
        const val PAYLOAD_SUFFIX = "PAYLOAD_TOGGLE_PRONUNCIATION"

        val PAYLOAD_METHOD_PARAMETERS: Array<Class<*>> = arrayOf(
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
            Any::class.java,
        )
    }
}
