package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** Corrects visual word fragments while the host retains its AnimatorSet and lyric identities. */
internal class AppleMusicLyricWrapSweepTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
) : LyricWrapSweepTarget {
    private class Recipe(adapter: Any, word: Any, browser: Any, flex: ViewGroup, bindings: List<*>, val content: String,
        val bottom: Boolean, val rtl: Boolean) {
        val adapter = WeakReference(adapter)
        val word = WeakReference(word)
        val browser = WeakReference(browser)
        val flex = WeakReference(flex)
        val bindings = bindings.filterNotNull().map { WeakReference(it) }
        var geometry: LyricWrapSweepGeometry? = null

        fun matchesContent(contract: LyricWrapSweepContract): Boolean {
            val values = bindings.map { it.get() ?: return false }
            val currentText = values.joinToString("") {
                (contract.textView.invoke(null, it) as? android.widget.TextView)?.text?.toString().orEmpty()
            }
            return currentText.filterNot(Char::isWhitespace) == content.filterNot(Char::isWhitespace)
        }

        fun prepare(contract: LyricWrapSweepContract): LyricWrapSweepGeometry? {
            geometry?.takeIf { it.valid(contract) }?.let { return it }
            val view = flex.get() ?: return null
            val values = bindings.map { it.get() ?: return null }
            if (!matchesContent(contract)) return null
            return LyricWrapSweepGeometry.create(contract, values, view, bottom, rtl).also { geometry = it }
        }

        fun belongsTo(root: View): Boolean {
            var current: View? = flex.get()
            while (current != null) {
                if (current === root) return true
                current = current.parent as? View
            }
            return false
        }
    }

    private class Sweep(val recipe: Recipe, val initialProgress: Float, val sourceMask: WeakReference<Any>) {
        @Volatile var retired = false
        var lastProgress = initialProgress
        var colors: IntArray? = null
        var feather = 0f
        var hasRendered = false
    }

    private class StaticFrame(val recipe: Recipe, val colors: IntArray)

    private val sweeps = WeakIdentityMap<ValueAnimator, Sweep>()
    private val rushes = WeakIdentityMap<ValueAnimator, Sweep>()
    private val staticFrames = WeakIdentityMap<ViewGroup, MutableMap<Boolean, StaticFrame>>()
    private val layoutObservers = WeakIdentityMap<ViewGroup, View.OnLayoutChangeListener>()
    private var registration: HookRegistrationScope? = null
    private var lastFailure: String? = null

    @Synchronized override fun install(): TargetCapabilityInstall {
        registration?.takeIf { it.isActive }?.let {
            return TargetCapabilityInstall.Active("Wrapped lyric sweep already active; ${profile.id}")
        }
        val names = profile.document.optJSONObject("lyricWrapSweep")
            ?: return TargetCapabilityInstall.Unsupported("Wrapped lyric sweep has no exact contract; ${profile.id}")
        val scope = HookRegistrationScope()
        return try {
            val contract = LyricWrapSweepContract(profile, loader, names)
            fun hook(method: Method, callback: ModernMethodHook) {
                check(ModernXposedRuntime.hookMethod(method, callback, scope))
            }
            hook(contract.build, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    guarded { installSweep(contract, param) }
                }
            })
            hook(contract.update, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val animator = param.args.firstOrNull() as? ValueAnimator ?: return
                    val sweep = sweeps[animator] ?: return
                    // A seek/recycle invalidates this callback even if the host ends its old set later.
                    if (sweep.retired) { param.result = null; return }
                    val listener = param.thisObject ?: return
                    guarded {
                        if (contract.listenerMask.get(listener) !== sweep.sourceMask.get() ||
                            contract.listenerRtl.getBoolean(listener) != sweep.recipe.rtl) return@guarded
                        val colors = contract.listenerColors.get(listener) as? IntArray ?: return@guarded
                        val feather = contract.listenerFeather.getFloat(listener)
                        val word = sweep.recipe.word.get() ?: return@guarded
                        val browser = sweep.recipe.browser.get() ?: return@guarded
                        val position = contract.position(browser) ?: return@guarded
                        val timed = lyricWrapWordProgress(contract.wordBegin.getInt(word), contract.wordDuration.getInt(word), position)
                            ?: return@guarded
                        // Only the new sweep created after a backward seek may reduce the visible progress.
                        val progress = if (animator.animatedFraction >= 1f) 1f else maxOf(sweep.lastProgress, timed)
                        val geometry = sweep.recipe.prepare(contract) ?: return@guarded
                        if (geometry.render(contract, progress, colors, feather)) {
                            sweep.lastProgress = progress
                            sweep.colors = colors
                            sweep.feather = feather
                            sweep.hasRendered = true
                            param.result = null
                        }
                    }
                }
            })
            hook(contract.rushUpdate, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val animator = param.args.firstOrNull() as? ValueAnimator ?: return
                    val sweep = rushes[animator] ?: return
                    // Until valid geometry has actually rendered, retain the original fallback.
                    if (sweep.retired || sweep.hasRendered) param.result = null
                }
            })
            hook(contract.rebind, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded {
                        val holder = param.args.firstOrNull() ?: return@guarded
                        val binding = contract.holderBinding.get(holder) ?: return@guarded
                        val root = contract.bindingRoot.get(binding) as? View ?: return@guarded
                        val models = contract.wordMaps.flatMap { (it.get(holder) as? Map<*, *>)?.values.orEmpty() }
                        retire { it.recipe.belongsTo(root) && models.none { model -> model === it.recipe.word.get() } }
                        forgetFrames { it.recipe.belongsTo(root) && models.none { model -> model === it.recipe.word.get() } }
                        sweeps.keys().forEach { animator ->
                            sweeps[animator]?.takeIf { it.recipe.belongsTo(root) }?.recipe?.geometry = null
                        }
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    guarded {
                        val holder = param.args.firstOrNull() ?: return@guarded
                        val binding = contract.holderBinding.get(holder) ?: return@guarded
                        val root = contract.bindingRoot.get(binding) as? View ?: return@guarded
                        staticFrames.keys().filter { view ->
                            staticFrames[view]?.values?.any { it.recipe.belongsTo(root) } == true
                        }.forEach { restoreFrames(contract, it) }
                    }
                }
            })
            hook(contract.staticMask, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    guarded { restoreStatic(contract, param) }
                }
            })
            // Old callbacks must not write newly bound row masks or a replacement TTML document.
            listOf(contract.bind, contract.recycle).forEach { method ->
                hook(method, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        guarded {
                            val holder = param.args.firstOrNull() ?: return@guarded
                            if (!contract.holderBinding.declaringClass.isInstance(holder)) return@guarded
                            val binding = contract.holderBinding.get(holder) ?: return@guarded
                            val root = contract.bindingRoot.get(binding) as? View ?: return@guarded
                            retire { it.recipe.belongsTo(root) }
                            forgetFrames { it.recipe.belongsTo(root) }
                        }
                    }
                })
            }
            hook(contract.document, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    retire { it.recipe.adapter.get() === param.thisObject }
                    forgetFrames { it.recipe.adapter.get() === param.thisObject }
                }
            })
            hook(contract.clearMasks, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    retire { it.recipe.flex.get() === param.thisObject }
                    (param.thisObject as? ViewGroup)?.let(staticFrames::remove)
                }
            })
            hook(contract.seek, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    retire { it.recipe.browser.get() === param.thisObject }
                    forgetFrames { it.recipe.browser.get() === param.thisObject }
                    guarded {
                        // Release Apple's word.o gate before LiveData can replay a word at the new position.
                        sweeps.keys().forEach { animator ->
                            val sweep = sweeps[animator] ?: return@forEach
                            if (sweep.recipe.browser.get() !== param.thisObject) return@forEach
                            val word = sweep.recipe.word.get() ?: return@forEach
                            (contract.wordAnimator.get(word) as? Animator)?.cancel()
                        }
                    }
                }
            })
            listOf(contract.pause, contract.pauseRequested, contract.resume).forEach { method ->
                hook(method, object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        guarded {
                            sweeps.keys().forEach { animator ->
                                val sweep = sweeps[animator] ?: return@forEach
                                if (!sweep.retired && sweep.recipe.adapter.get() === param.thisObject && animator.isStarted) {
                                    val word = sweep.recipe.word.get()
                                    val owner = word?.let { contract.wordAnimator.get(it) as? Animator } ?: animator
                                    if (method == contract.resume) owner.resume() else owner.pause()
                                }
                            }
                        }
                    }
                })
            }
            scope.onClose {
                sweeps.clear()
                rushes.clear()
                staticFrames.clear()
                layoutObservers.keys().forEach { view -> layoutObservers[view]?.let(view::removeOnLayoutChangeListener) }
                layoutObservers.clear()
            }
            scope.activate()
            registration = scope
            TargetCapabilityInstall.Active("Native wrapped-word sweep installed; ${profile.id}; manual acceptance pending")
        } catch (error: Throwable) {
            scope.close()
            ModernXposedRuntime.log("Wrapped lyric sweep kept native behavior", error)
            TargetCapabilityInstall.Degraded("Wrapped lyric sweep contract/registration failed; ${profile.id}: ${error.message}")
        }
    }

    private fun installSweep(contract: LyricWrapSweepContract, param: ModernMethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return
        val word = param.args[2] ?: return
        val bindings = param.args[3] as? List<*> ?: return
        val flex = param.args[4] as? ViewGroup ?: return
        val sourceMasks = param.args[1] as? Array<*> ?: return
        val sourceMask = sourceMasks.lastOrNull() ?: return
        val bottom = bankFor(contract, flex, sourceMask) ?: return
        staticFrames[flex]?.remove(bottom)
        val rtl = param.args[6] as? Boolean ?: return
        if (!LyricWrapSweepGeometry.spansRows(contract, bindings, flex, bottom)) return
        val browser = contract.browser(adapter) ?: return
        val position = contract.position(browser) ?: return
        val begin = contract.wordBegin.getInt(word)
        val duration = contract.wordDuration.getInt(word)
        val initial = lyricWrapWordProgress(begin, duration, position) ?: return
        val animations = param.result as? List<*> ?: return
        // d0 has exactly one untagged native ValueAnimator (player.t); rush has a distinct tagged class.
        val animator = animations.filterIsInstance<ValueAnimator>().singleOrNull { it.javaClass == ValueAnimator::class.java }
            ?: return
        if (animator.isStarted || animator.duration <= 0L) return
        val content = contract.wordText.get(word) as? String ?: return
        val recipe = Recipe(adapter, word, browser, flex, bindings, content, bottom, rtl)
        if (!recipe.matchesContent(contract)) return
        val prepared = recipe.prepare(contract)
        if (flex.isAttachedToWindow && !flex.isLayoutRequested && prepared == null) return
        val remaining = begin.toLong() + duration - position
        if (prepared != null && position >= begin && initial < 1f)
            animator.duration = minOf(animator.duration, remaining.coerceAtLeast(1L))
        val sweep = Sweep(recipe, initial, WeakReference(sourceMask))
        retire { it.recipe.flex.get() === flex && it.recipe.bottom == bottom }
        sweeps[animator] = sweep
        observeLayout(contract, flex)
        val animatorRef = WeakReference(animator)
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { animatorRef.get()?.let(sweeps::remove) }
            override fun onAnimationCancel(animation: Animator) { sweep.retired = true }
        })
        // Keep native scheduling; only suppress a rush after multi-row rendering actually succeeded.
        animations.filterIsInstance<ValueAnimator>().filter(contract::isRush).forEach { rush ->
            rushes[rush] = sweep
            val rushRef = WeakReference(rush)
            rush.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { rushRef.get()?.let(rushes::remove) }
                override fun onAnimationCancel(animation: Animator) { rushRef.get()?.let(rushes::remove) }
            })
        }
    }

    private fun restoreStatic(contract: LyricWrapSweepContract, param: ModernMethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return
        val word = param.args[0] ?: return
        val bindings = param.args[1] as? List<*> ?: return
        val flex = param.args[2] as? ViewGroup ?: return
        val colors = param.args[4] as? IntArray ?: return
        val rtl = param.args[5] as? Boolean ?: return
        val positionName = (param.args[6] as? Enum<*>)?.name ?: return
        val bottom = when (positionName) { "TOP" -> false; "BOTTOM" -> true; else -> return }
        if (!LyricWrapSweepGeometry.spansRows(contract, bindings, flex, bottom)) {
            staticFrames[flex]?.remove(bottom)
            return
        }
        val browser = contract.browser(adapter) ?: return
        val position = contract.position(browser) ?: return
        if (lyricWrapWordProgress(contract.wordBegin.getInt(word), contract.wordDuration.getInt(word), position) == null) return
        val content = contract.wordText.get(word) as? String ?: return
        val recipe = Recipe(adapter, word, browser, flex, bindings, content, bottom, rtl)
        val frame = StaticFrame(recipe, colors.copyOf())
        val frames = staticFrames[flex] ?: mutableMapOf<Boolean, StaticFrame>().also { staticFrames[flex] = it }
        frames[bottom] = frame
        observeLayout(contract, flex)
        renderFrame(contract, frame)
    }

    private fun renderFrame(contract: LyricWrapSweepContract, frame: StaticFrame) {
        val word = frame.recipe.word.get() ?: return
        val browser = frame.recipe.browser.get() ?: return
        val position = contract.position(browser) ?: return
        val progress = lyricWrapWordProgress(contract.wordBegin.getInt(word), contract.wordDuration.getInt(word), position) ?: return
        val active = sweeps.keys().mapNotNull { sweeps[it] }.firstOrNull {
            !it.retired && it.recipe.flex.get() === frame.recipe.flex.get() &&
                it.recipe.bottom == frame.recipe.bottom
        }
        if (active != null) {
            val colors = active.colors ?: return
            active.recipe.prepare(contract)?.render(contract, active.lastProgress, colors, active.feather)
        } else {
            val adapter = frame.recipe.adapter.get() ?: return
            val flex = frame.recipe.flex.get() ?: return
            val feather = contract.featherPixels.getFloat(adapter) / maxOf(flex.width, 1)
            frame.recipe.prepare(contract)?.render(contract, progress, frame.colors, feather)
        }
    }

    private fun restoreFrames(contract: LyricWrapSweepContract, view: ViewGroup) {
        staticFrames[view]?.values?.forEach { renderFrame(contract, it) }
    }

    private fun observeLayout(contract: LyricWrapSweepContract, view: ViewGroup) {
        if (layoutObservers[view] != null) return
        val observer = View.OnLayoutChangeListener { source, _, _, _, _, _, _, _, _ ->
            val flex = source as? ViewGroup ?: return@OnLayoutChangeListener
            guarded {
                staticFrames[flex]?.values?.forEach { it.recipe.geometry = null }
                sweeps.keys().forEach { animator ->
                    sweeps[animator]?.takeIf { it.recipe.flex.get() === flex }?.recipe?.geometry = null
                }
                restoreFrames(contract, flex)
            }
        }
        layoutObservers[view] = observer
        view.addOnLayoutChangeListener(observer)
    }

    private fun forgetFrames(predicate: (StaticFrame) -> Boolean) {
        staticFrames.keys().forEach { view ->
            val frames = staticFrames[view] ?: return@forEach
            frames.entries.removeAll { predicate(it.value) }
            if (frames.isEmpty()) staticFrames.remove(view)
        }
    }

    private fun bankFor(contract: LyricWrapSweepContract, flex: ViewGroup, mask: Any): Boolean? {
        val key = contract.maskFirst.getInt(mask)
        val top = contract.topMasks.get(flex) as? SparseArray<*>
        val bottom = contract.bottomMasks.get(flex) as? SparseArray<*>
        return when {
            top?.get(key) === mask -> false
            bottom?.get(key) === mask -> true
            else -> null
        }
    }

    private fun retire(predicate: (Sweep) -> Boolean) {
        sweeps.keys().forEach { animator -> sweeps[animator]?.let { if (predicate(it)) it.retired = true } }
        rushes.keys().forEach { animator -> rushes[animator]?.let { if (predicate(it)) it.retired = true } }
    }

    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { error ->
            val reason = error.javaClass.simpleName
            if (lastFailure != reason) {
                lastFailure = reason
                ModernXposedRuntime.log("Wrapped lyric callback kept native behavior: $reason", error)
            }
        }
    }
}
