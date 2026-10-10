package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Shader
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.robolectric.Robolectric
import java.lang.reflect.Method

/** Real Android objects with the same member shapes as the verified host contract. */
internal class PlayerRecoveryFixture {
    val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    val resources = activity.resources as LookupResources
    val context: Context = activity
    val root = FrameLayout(context)
    val paneRoot = FrameLayout(context)
    val slot = FrameLayout(context).apply { id = CONTAINER }
    val cover = FrameLayout(context).apply { id = CARD }
    val image = ImageView(context).apply { id = IMAGE }
    val background = Background(context).apply { id = BACKGROUND }
    val main = Main().apply { rootView = root }
    val pane = Pane().apply { rootView = paneRoot; parent = main }
    val scope = HookRegistrationScope()
    val hooks = Hooks()
    val contract = FragmentPlayerRecoveryContract(Symbols(), TargetBuild("com.apple.android.music", "7.0.0-beta", 1606))

    init {
        root.addView(background, FrameLayout.LayoutParams(400, 400))
        root.addView(paneRoot, FrameLayout.LayoutParams(400, 400))
        paneRoot.addView(slot, FrameLayout.LayoutParams(400, 400))
        slot.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cover.addView(image, FrameLayout.LayoutParams(328, 328))
        activity.setContentView(root)
        layout()
    }

    fun layout(size: Int = 400) {
        root.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, size, size)
        slot.layout(0, 0, size, size)
        background.layout(0, 0, size, size)
    }

    fun installArtwork() {
        FragmentPlayerArtworkRecovery(contract, scope, hooks::register).install()
        scope.activate()
        hooks.after(contract.createView, main, result = root)
        hooks.after(contract.paneViewCreated, pane, arrayOf(paneRoot))
    }

    fun installBackground() {
        FragmentPlayerBackgroundRecovery(contract, scope, hooks::register).install()
        scope.activate()
    }

    fun draw() { root.viewTreeObserver.dispatchOnPreDraw() }
    fun resume() { hooks.after(contract.resume, main) }
    fun destroyPane() { hooks.before(contract.fragmentDestroyView, pane) }
    fun destroyMain() { hooks.before(contract.destroyView, main) }
    fun detachBackground() { hooks.after(contract.backgroundDetach, background) }
    fun attachBackground() {
        hooks.after(View::class.java.getDeclaredMethod("onAttachedToWindow"), background)
    }

    class HostActivity : Activity() {
        private var hostResources: LookupResources? = null
        override fun getResources(): Resources = hostResources
            ?: LookupResources(super.getResources()).also { hostResources = it }
    }

    class LookupResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        var identifiers = 0
        override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
            identifiers++
            return when (name) {
                "artwork_container" -> CONTAINER
                "fullplayerSongImage" -> CARD
                "artwork_image" -> IMAGE
                "background_layers" -> BACKGROUND
                else -> super.getIdentifier(name, defType, defPackage)
            }
        }
    }

    open class Fragment {
        var rootView: View? = null
        var parent: Fragment? = null
        var viewReads = 0
        var parentReads = 0
        fun view(): View? { viewReads++; return rootView }
        fun parentFragment(): Fragment? { parentReads++; return parent }
        fun viewCreated(view: View) = Unit
        fun destroyFragmentView() = Unit
    }

    class Behavior { @JvmField var state = 3 }
    class Main : Fragment() {
        @JvmField var entering = false
        @JvmField var sharedElement = false
        @JvmField var behavior = Behavior()
        fun create(): View? = rootView
        fun resume() = Unit
        fun destroyView() = Unit
    }

    class Pane : Fragment() {
        @JvmField var videoMode = false
        @JvmField var sizeAnimation: Animator? = null
        @JvmField var baseline: Size? = null
        @JvmField var target: Size? = null
        var resizes = 0
        var lastSize: Size? = null
        var targetAtResize: Size? = null
        fun resize(video: Boolean, size: Size) {
            assertFalse(video)
            resizes++
            lastSize = size
            targetAtResize = target
            target = size
            rootView!!.findViewById<View>(IMAGE).let {
                it.layoutParams = it.layoutParams.apply { width = size.width; height = size.height }
            }
        }
    }

    class Background(context: Context) : View(context) {
        @JvmField var source: Bitmap? = null
        @JvmField var queued: Bitmap? = null
        @JvmField var derived: Bitmap? = null
        @JvmField var shader: Shader? = null
        @JvmField var previousShader: Shader? = null
        @JvmField var paint = Paint()
        @JvmField var previousPaint = Paint()
        @JvmField var fade = ValueAnimator.ofFloat(0f, 1f)
        val events = mutableListOf<String>()
        var restores = 0
        var restored: Bitmap? = null
        var failArtwork = false
        fun detach() = Unit
        fun artwork(bitmap: Bitmap) {
            events += "artwork"
            assertNull(shader)
            assertNull(previousShader)
            assertNull(derived)
            assertNull(paint.shader)
            assertNull(previousPaint.shader)
            restores++
            if (failArtwork) error("fixture artwork failure")
            restored = bitmap
            source = bitmap
            queued = null
            derived = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        }
    }

    /** Inject only registration; production before/after callbacks run unchanged. */
    class Hooks {
        private val callbacks = linkedMapOf<Method, MutableList<ModernMethodHook>>()
        fun register(method: Method, hook: ModernMethodHook, scope: HookRegistrationScope): Boolean {
            callbacks.getOrPut(method) { mutableListOf() } += hook
            return true
        }
        fun before(method: Method, receiver: Any, args: Array<Any?> = emptyArray()) {
            val param = param(method, receiver, args, null)
            callbacks.getValue(method).forEach { it.beforeHookedMethod(param) }
        }
        fun after(method: Method, receiver: Any, args: Array<Any?> = emptyArray(), result: Any? = null) {
            val param = param(method, receiver, args, result)
            callbacks.getValue(method).forEach { it.afterHookedMethod(param) }
        }
        private fun param(method: Method, receiver: Any, args: Array<Any?>, result: Any?) =
            ModernMethodHook.MethodHookParam::class.java.declaredConstructors.single()
                .apply { isAccessible = true }.newInstance(method, receiver, args)
                .let { it as ModernMethodHook.MethodHookParam }.apply { this.result = result }
    }

    private class Symbols : TargetSymbolResolver {
        override fun <T : Any> resolve(symbol: TargetSymbolKey<T>): TargetResolution<T> {
            fun method(type: Class<*>, name: String, vararg args: Class<*>) = type.getDeclaredMethod(name, *args)
            fun field(type: Class<*>, name: String) = type.getDeclaredField(name)
            val member: Any = when (symbol.id) {
                "player-controller-create-view" -> method(Main::class.java, "create")
                "player-fragment-view" -> method(Fragment::class.java, "view")
                "player-controller-resume" -> method(Main::class.java, "resume")
                "player-controller-destroy-view" -> method(Main::class.java, "destroyView")
                "player-fragment-destroy-view" -> method(Fragment::class.java, "destroyFragmentView")
                "player-pane-view-created" -> method(Fragment::class.java, "viewCreated", View::class.java)
                "player-artwork-resize" -> method(Pane::class.java, "resize", Boolean::class.javaPrimitiveType!!, Size::class.java)
                "player-fragment-parent" -> method(Fragment::class.java, "parentFragment")
                "player-background-detach" -> method(Background::class.java, "detach")
                "player-background-artwork" -> method(Background::class.java, "artwork", Bitmap::class.java)
                "player-artwork-video-mode" -> field(Pane::class.java, "videoMode")
                "player-artwork-size-animation" -> field(Pane::class.java, "sizeAnimation")
                "player-artwork-static-baseline" -> field(Pane::class.java, "baseline")
                "player-artwork-target-size" -> field(Pane::class.java, "target")
                "player-pane-enter-running" -> field(Main::class.java, "entering")
                "player-pane-shared-running" -> field(Main::class.java, "sharedElement")
                "player-page-behavior" -> field(Main::class.java, "behavior")
                "player-page-behavior-state" -> field(Behavior::class.java, "state")
                "player-background-source" -> field(Background::class.java, "source")
                "player-background-queued" -> field(Background::class.java, "queued")
                "player-background-derived" -> field(Background::class.java, "derived")
                "player-background-shader" -> field(Background::class.java, "shader")
                "player-background-previous-shader" -> field(Background::class.java, "previousShader")
                "player-background-paint" -> field(Background::class.java, "paint")
                "player-background-previous-paint" -> field(Background::class.java, "previousPaint")
                "player-background-fade" -> field(Background::class.java, "fade")
                else -> error("Unexpected symbol: ${symbol.id}")
            }
            @Suppress("UNCHECKED_CAST")
            return TargetResolution.Found(symbol.id, member as T, SymbolMatch.VERSION_PROFILE, "fixture")
        }
    }

    companion object {
        const val CONTAINER = 0x123001
        const val CARD = 0x123002
        const val IMAGE = 0x123003
        const val BACKGROUND = 0x123004
    }
}
