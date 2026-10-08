package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Looper
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import dev.amenhancer.module.ModuleConstants
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Owns only static artwork measurement; Apple's playback scale and video sizing stay native. */
internal class FragmentPlayerArtworkRecovery(
    private val contract: FragmentPlayerRecoveryContract,
    private val scope: HookRegistrationScope,
) {
    private val mainType = contract.createView.declaringClass
    private val views = IdentityHashMap<Any, View>()
    private val watches = IdentityHashMap<Any, ArtworkFrameWatch>()
    private val originals = WeakHashMap<View, Pair<Int, Int>>()
    private val owners = WeakHashMap<View, WeakReference<Any>>()
    private val listeners = IdentityHashMap<Animator, ArtworkAnimationListener>()

    fun install() {
        // Register cleanup before hooks. The composition owner activates/closes this scope.
        scope.onClose {
            watches.values.toList().forEach { runCatching { it.close() } }
            watches.clear()
            listeners.values.toList().forEach { runCatching { it.close() } }
            listeners.clear()
            originals.keys.toList().forEach { runCatching { restoreNativeSize(it) } }
            originals.clear()
            owners.clear()
            views.clear()
        }
        hook(contract.paneViewCreated, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                if (param.throwable != null) return@guarded
                val pane = param.thisObject ?: return@guarded
                if (!contract.resizeArtwork.declaringClass.isInstance(pane)) return@guarded
                val root = param.args.firstOrNull() as? View ?: return@guarded
                remember(pane, root)
                card(root)?.let { owners[it] = WeakReference(pane) }
            }
        })
        hook(contract.createView, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                if (param.throwable != null) return@guarded
                val main = param.thisObject?.takeIf(mainType::isInstance) ?: return@guarded
                val root = param.result as? View ?: return@guarded
                track(main, root)
            }
        })
        hook(contract.resume, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                if (param.throwable != null) return@guarded
                val main = param.thisObject?.takeIf(mainType::isInstance) ?: return@guarded
                val root = contract.getView.invoke(main) as? View ?: return@guarded
                track(main, root)
                reconcile(main, root)
            }
        })
        hook(contract.destroyView, object : ModernMethodHook() {
            // Revoke identities before native destruction can finish/cancel a size animation.
            override fun beforeHookedMethod(param: MethodHookParam) = guarded {
                param.thisObject?.let(::destroy)
            }
        })
        hook(contract.fragmentDestroyView, object : ModernMethodHook() {
            // A removed SONG pane may no longer be a descendant of the main root.
            override fun beforeHookedMethod(param: MethodHookParam) = guarded {
                param.thisObject?.let(::destroy)
            }
        })
        hook(contract.resizeArtwork, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = guarded {
                val pane = param.thisObject ?: return@guarded
                val root = contract.getView.invoke(pane) as? View ?: return@guarded
                val cover = card(root) ?: return@guarded
                if (param.args.firstOrNull() == true || contract.videoMode.getBoolean(pane) ||
                    animation(pane)?.isRunning == true) {
                    restoreNativeSize(cover)
                    return@guarded
                }
                if (param.args.firstOrNull() != false || param.args.size < 2) return@guarded
                val main = currentMain(pane, root) ?: return@guarded
                val mainRoot = views[main] ?: return@guarded
                if (!eligible(main, mainRoot, pane) || !visible(cover)) return@guarded
                val slot = desired(cover) ?: return@guarded
                prepare(cover)
                val size = Size(slot.width, slot.height)
                contract.baselineSize.set(pane, size)
                param.args[1] = size
            }

            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                if (param.throwable != null || param.args.firstOrNull() != false) return@guarded
                val pane = param.thisObject ?: return@guarded
                val root = contract.getView.invoke(pane) as? View ?: return@guarded
                val main = currentMain(pane, root) ?: return@guarded
                val running = animation(pane)
                if (running?.isRunning == true) {
                    card(root)?.let(::restoreNativeSize)
                    if (!listeners.containsKey(running)) {
                        val listener = ArtworkAnimationListener(running, pane, root, main)
                        listeners[running] = listener
                        running.addListener(listener)
                    }
                } else {
                    val mainRoot = views[main] ?: return@guarded
                    val cover = card(root) ?: return@guarded
                    if (eligible(main, mainRoot, pane) && visible(cover) && desired(cover) != null) {
                        prepare(cover)
                    }
                }
            }
        })
        // getView is intentionally invoked only; hooking it re-enters fragment view lookup.
    }

    private fun hook(method: Method, callback: ModernMethodHook) {
        check(ModernXposedRuntime.hookMethod(method, callback, scope)) { "Artwork recovery hook failed: $method" }
    }

    private fun guarded(action: () -> Unit) {
        if (!scope.isActive || Looper.myLooper() != Looper.getMainLooper()) return
        runCatching(action).onFailure { error ->
            runCatching { ModernXposedRuntime.log("Fragment artwork recovery callback failed", error) }
        }
    }

    private fun resource(root: View, key: String): Int = root.resources.getIdentifier(
        contract.names.getString(key), "id", ModuleConstants.TARGET_PACKAGE,
    )

    private fun card(root: View): View? {
        val cardId = resource(root, "cardId").takeIf { it != 0 } ?: return null
        val containerId = resource(root, "containerId").takeIf { it != 0 } ?: return null
        val cover = root.findViewById<View>(cardId) ?: return null
        return cover.takeIf { (it.parent as? ViewGroup)?.id == containerId }
    }

    private fun desired(cover: View): FragmentArtworkRecoveryPolicy.SlotSize? {
        val slot = cover.parent as? ViewGroup ?: return null
        return FragmentArtworkRecoveryPolicy.nativeSquare(slot.width, slot.height, slot.isLaidOut)
    }

    private fun animation(pane: Any): Animator? = contract.sizeAnimation.get(pane) as? Animator

    private fun visible(cover: View): Boolean = cover.isShown && cover.alpha > .01f

    private fun eligible(main: Any, root: View, pane: Any): Boolean =
        root.isAttachedToWindow && FragmentArtworkRecoveryPolicy.canRecover(
            shown = root.isShown,
            laidOut = root.isLaidOut,
            sheetState = contract.behavior.get(main)?.let(contract.behaviorState::getInt),
            entering = contract.entering.getBoolean(main),
            sharedElement = contract.sharedElement.getBoolean(main),
            videoMode = contract.videoMode.getBoolean(pane),
            sizeAnimationRunning = animation(pane)?.isRunning == true,
        )

    private fun currentMain(pane: Any, root: View): Any? {
        if (!contract.baselineSize.declaringClass.isInstance(pane) || views[pane] !== root ||
            contract.getView.invoke(pane) !== root) return null
        var parent = contract.parentFragment.invoke(pane)
        repeat(5) {
            val candidate = parent ?: return null
            if (mainType.isInstance(candidate)) {
                val mainRoot = views[candidate] ?: return null
                return candidate.takeIf { watches[candidate]?.root === mainRoot &&
                    contract.getView.invoke(candidate) === mainRoot && within(root, mainRoot) }
            }
            parent = contract.parentFragment.invoke(candidate)
        }
        return null
    }

    private fun prepare(cover: View) {
        val params = cover.layoutParams ?: return
        if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
            params.height != ViewGroup.LayoutParams.WRAP_CONTENT) return
        originals.putIfAbsent(cover, params.width to params.height)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        cover.layoutParams = params
    }

    private fun restoreNativeSize(cover: View) {
        val original = originals.remove(cover) ?: return
        val params = cover.layoutParams ?: return
        // Another native writer may already have taken ownership of these parameters.
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
            params.height != ViewGroup.LayoutParams.MATCH_PARENT) return
        params.width = original.first
        params.height = original.second
        cover.layoutParams = params
    }

    private fun reconcile(main: Any, root: View) {
        if (!scope.isActive || views[main] !== root || watches[main]?.root !== root) return
        val cover = card(root) ?: return
        val pane = owners[cover]?.get() ?: return
        val paneRoot = views[pane] ?: return
        if (currentMain(pane, paneRoot) !== main) return
        if (contract.videoMode.getBoolean(pane) || animation(pane)?.isRunning == true) {
            restoreNativeSize(cover)
            return
        }
        if (!eligible(main, root, pane) || !visible(cover)) return
        val slot = desired(cover) ?: return
        prepare(cover)
        val size = Size(slot.width, slot.height)
        if (contract.baselineSize.get(pane) != size) contract.baselineSize.set(pane, size)
        val imageId = resource(root, "imageId").takeIf { it != 0 } ?: return
        val params = cover.findViewById<View>(imageId)?.layoutParams ?: return
        if (!FragmentArtworkRecoveryPolicy.needsResize(params.width, params.height, slot)) return
        // Native resize may otherwise early-return while a transition left stale child params.
        if (contract.targetSize.get(pane) == size) contract.targetSize.set(pane, null)
        contract.resizeArtwork.invoke(pane, false, size)
    }

    private fun remember(owner: Any, root: View) {
        if (views[owner] === root) return
        if (views.containsKey(owner)) destroy(owner)
        views[owner] = root
    }

    private fun track(main: Any, root: View) {
        remember(main, root)
        if (watches[main]?.root === root) return
        watches.remove(main)?.close()
        watches[main] = ArtworkFrameWatch(root, { scope.isActive && views[main] === root }) {
            guarded { reconcile(main, root) }
        }
    }

    private fun within(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun destroy(owner: Any) {
        // Ignore unrelated fragments and repeated main/base lifecycle callbacks.
        val root = views.remove(owner) ?: return
        val retired = views.keys.filter { within(checkNotNull(views[it]), root) }
        retired.forEach { views.remove(it); watches.remove(it)?.let { watch -> runCatching { watch.close() } } }
        watches.remove(owner)?.let { runCatching { it.close() } }
        listeners.values.toList().filter { it.pane === owner || it.main === owner ||
            retired.any { pane -> pane === it.pane } }.forEach { runCatching { it.close() } }
        owners.keys.toList().filter { cover -> owners[cover]?.get() === owner ||
            within(cover, root) }.forEach { cover ->
            owners.remove(cover)
            runCatching { restoreNativeSize(cover) }
        }
    }

    private inner class ArtworkAnimationListener(
        private val animator: Animator,
        val pane: Any,
        private val root: View,
        val main: Any,
    ) : AnimatorListenerAdapter(), AutoCloseable {
        private var closed = false

        override fun onAnimationEnd(animation: Animator) {
            val current = !closed && listeners[animator] === this
            runCatching { close() }
            if (current) guarded {
                if (views[pane] === root && currentMain(pane, root) === main) {
                    views[main]?.let { reconcile(main, it) }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            if (listeners[animator] === this) listeners.remove(animator)
            animator.removeListener(this)
        }
    }
}

private class ArtworkFrameWatch(
    val root: View,
    private val active: () -> Boolean,
    private val update: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private var tree: ViewTreeObserver? = null
    private val draw = ViewTreeObserver.OnPreDrawListener {
        runCatching { if (!closed && active() && root.isAttachedToWindow) update() }
        true
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            runCatching { if (!closed && active()) listen() }
        }
        override fun onViewDetachedFromWindow(view: View) { runCatching { unlisten() } }
    }

    init {
        root.addOnAttachStateChangeListener(attach)
        try {
            if (root.isAttachedToWindow) listen()
        } catch (error: Throwable) {
            runCatching { close() }
            throw error
        }
    }

    private fun listen() {
        unlisten()
        if (closed || !active()) return
        tree = root.viewTreeObserver.takeIf { it.isAlive }?.also { it.addOnPreDrawListener(draw) }
    }

    private fun unlisten() {
        val previous = tree
        tree = null
        previous?.takeIf { it.isAlive }?.removeOnPreDrawListener(draw)
    }

    override fun close() {
        if (closed) return
        closed = true
        try { unlisten() } finally { root.removeOnAttachStateChangeListener(attach) }
    }
}
