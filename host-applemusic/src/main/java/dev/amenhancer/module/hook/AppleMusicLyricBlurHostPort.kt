package dev.amenhancer.module.hook

import android.animation.Animator
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Private host objects stay in this binding. Only copied IDs enter the reducer. */
internal class AppleMusicLyricBlurHostPort(
    owner: Any,
    private val contract: AppleMusicLyricBlurContract,
    private val observeModel: (Any) -> Unit,
) : LyricBlurHostPort {
    private val ownerRef = WeakReference(owner)
    private var ordinals: Map<Int, Int> = emptyMap()
    private var frameAdapter: Any? = null
    private val models = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val ignoredAnimations = Collections.newSetFromMap(WeakHashMap<Animator, Boolean>())

    override fun recycler(): ViewGroup? = ownerRef.get()?.let { owner ->
        contract.field("binding").get(owner)?.let { binding ->
            (contract.field("recycler").get(binding) as? ViewGroup)?.takeIf(contract.recyclerClass::isInstance)
        }
    }

    fun adapter(): Any? = recycler()?.let { contract.method("getAdapter").invoke(it) }
        ?.takeIf(contract::isAdapter)

    fun beginSnapshot(adapter: Any?) { frameAdapter = adapter }

    fun ownsAdapter(adapter: Any): Boolean = this.adapter() === adapter
    fun ownsViewModel(viewModel: Any): Boolean = ownerRef.get()?.let {
        contract.field("viewModel").get(it) === viewModel
    } == true
    fun callbacks(): List<Pair<String, Any>> = ownerRef.get()?.let { owner ->
        AppleMusicLyricBlurContract.nativeRoles.mapNotNull { role ->
            contract.field(role).get(owner)?.let { role to it }
        }
    }.orEmpty()

    override fun document(): LyricDecodeResult<Map<Int, Int>> {
        models.clear()
        ignoredAnimations.clear()
        ordinals = emptyMap()
        val values = linkedMapOf<Int, Int>()
        var failure: String? = null
        return try {
            val adapter = adapter() ?: return LyricDecodeResult.Pending("document:awaiting-adapter")
            if (contract.adapterMethod(adapter, "Token").invoke(adapter) == null) return LyricDecodeResult.Complete(emptyMap())
            val reader = contract.adapterMethod(adapter, "Reader").invoke(adapter)
                ?: return LyricDecodeResult.Complete(emptyMap())
            val count = (contract.method("readerCount").invoke(reader) as Number).toInt()
            check(count >= 0)
            for (ordinal in 0 until count) {
                runCatching {
                    val pointer = checkNotNull(contract.method("readerLine").invoke(reader, ordinal))
                    val line = checkNotNull(contract.method("lineGet").invoke(pointer))
                    val id = (contract.method("lineId").invoke(line) as Number).toInt()
                    check(values.put(id, ordinal) == null) { "Duplicate native line ID" }
                }.onFailure { failure = it.javaClass.simpleName }
            }
            ordinals = values.toMap()
            failure?.let { LyricDecodeResult.Partial(ordinals, "document:$it") }
                ?: LyricDecodeResult.Complete(ordinals)
        } catch (error: Exception) {
            LyricDecodeResult.Failed("document:${error.javaClass.simpleName}")
        }
    }

    override fun row(view: View, bindingGeneration: Long): LyricBlurBoundRow {
        fun unknown() = LyricBlurBoundRow(view, LyricRowSnapshot(null, null, null, LyricRowKind.UNKNOWN, bindingGeneration))
        return try {
            val adapter = frameAdapter ?: return unknown()
            val position = (contract.method("rowPosition").invoke(null, view) as Number).toInt()
            if (position < 0) return unknown()
            val holder = contract.method("rowHolder").invoke(null, view) ?: return unknown()
            val type = (contract.adapterMethod(adapter, "Type").invoke(adapter, position) as Number).toInt()
            if ((contract.field("holderType").get(holder) as Number).toInt() != type) return unknown()
            val kind = when (type) {
                0 -> if (contract.lineAdapter.isInstance(adapter)) LyricRowKind.LYRIC else LyricRowKind.UNKNOWN
                3 -> if (contract.wordAdapter.isInstance(adapter)) LyricRowKind.LYRIC else LyricRowKind.UNKNOWN
                1 -> LyricRowKind.CREDITS
                2 -> LyricRowKind.INSTRUMENTAL
                else -> LyricRowKind.UNKNOWN
            }
            if (kind == LyricRowKind.LYRIC) {
                val ordinal = (contract.adapterMethod(adapter, "Map").invoke(adapter, position, true) as Number).toInt()
                val id = (contract.adapterMethod(adapter, "Id").invoke(adapter, position) as Number).toLong()
                if ((contract.field("holderId").get(holder) as Number).toLong() != id) return unknown()
                if (id !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || ordinals[id.toInt()] != ordinal) return unknown()
                LyricBlurBoundRow(view, LyricRowSnapshot(id.toInt(), ordinal, position, kind, bindingGeneration))
            } else LyricBlurBoundRow(view, LyricRowSnapshot(null,
                if (kind == LyricRowKind.CREDITS) ordinals.values.maxOrNull() else null,
                position, kind, bindingGeneration))
        } catch (_: Exception) { unknown() }
    }

    override fun displayed(rows: List<LyricBlurBoundRow>): LyricDecodeResult<LyricDisplaySnapshot> {
        return try {
        val adapter = frameAdapter ?: return LyricDecodeResult.Pending("display:awaiting-adapter")
        val raw = contract.adapterMethod(adapter, "Snapshot").invoke(adapter) as? Set<*>
            ?: return LyricDecodeResult.Failed("display:invalid-snapshot")
        check(raw.all { it is Int })
        val displayed = raw.map { it as Int }.toSet()
        val words = LyricWordSource.entries.associateWith { linkedSetOf<Int>() }
        if (contract.wordAdapter.isInstance(adapter)) {
            rows.filter { it.identity.kind == LyricRowKind.LYRIC }.forEach { row ->
                val holder = contract.method("rowHolder").invoke(null, row.view)
                check(holder != null && contract.wordHolder.isInstance(holder))
                contract.wordMaps.forEach maps@{ (source, field) ->
                    val map = field.get(holder) as? Map<*, *> ?: return@maps
                    map.values.forEach { model ->
                        check(model != null && contract.wordModel.isInstance(model))
                        if (models.add(model)) observeModel(model)
                        val id = (contract.field("wordLineId").get(model) as Number).toInt()
                        if (hasLiveAnimation(model)) {
                            words.getValue(source).add(id)
                        }
                    }
                }
            }
        }
        val gap = (contract.field(if (contract.wordAdapter.isInstance(adapter)) "wordInstrumentalPosition"
            else "instrumentalPosition").get(adapter) as Number).toInt().takeIf { it in 0..ordinals.size }
        LyricDecodeResult.Complete(LyricDisplaySnapshot(displayed, words.mapValues { it.value.toSet() }, gap))
    } catch (error: Exception) { LyricDecodeResult.Failed("display:${error.javaClass.simpleName}") }
    }

    private fun animators(model: Any): List<Animator> = buildList {
        (contract.field("wordAnimator").get(model) as? Animator)?.let(::add)
        val tails = contract.field("wordTailAnimators").get(model) as Iterable<*>
        tails.forEach { if (it is Animator) add(it) }
    }

    private fun hasLiveAnimation(model: Any): Boolean {
        fun live(animation: Animator) = animation !in ignoredAnimations &&
            (animation.isStarted || animation.isRunning || animation.isPaused)
        val progress = contract.field("wordAnimator").get(model) as? Animator
        if (progress != null && live(progress)) return true
        val tails = contract.field("wordTailAnimators").get(model) as Iterable<*>
        return tails.any { it is Animator && live(it) }
    }

    fun seek() {
        models.toList().forEach { model -> runCatching { ignoredAnimations.addAll(animators(model)) } }
    }

    fun wordStarted(model: Any, animation: Animator) {
        if (model in models) ignoredAnimations.remove(animation)
    }
}
