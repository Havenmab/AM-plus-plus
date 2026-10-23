package dev.amenhancer.lyricsplayer

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Host-side container of the self-drawn lyrics page.
 *
 * Mirrors [dev.amenhancer.glass.GlassHostView]: the host application's AndroidX owners are
 * obfuscated and do not satisfy this module's types, so the view owns a [Recomposer] and a
 * [LifecycleRegistry] of its own and never reads the host's composition context.
 *
 * The host adds this view to the activity decor (MATCH_PARENT), pushes the port and the
 * safe-area insets, and calls [show]/[hide] from the lyrics fragment lifecycle. The view is
 * opaque and clickable so it swallows every touch while visible (spec §1 D2).
 */
class LyricsPlayerHostView(context: Context) : FrameLayout(context) {
    private var owner = Owner()
    private var compositionScope: CoroutineScope? = null
    private var recomposer: Recomposer? = null

    private val portState = mutableStateOf<LyricsPlayerPort?>(null)
    private val insetsState = mutableStateOf(LyricsPlayerInsets.ZERO)
    private val visibleState = mutableStateOf(false)

    /** Latest overlay size; kept for the host and for [viewportListener]. */
    var viewportWidthPx: Int = 0
        private set

    var viewportHeightPx: Int = 0
        private set

    val density: Float get() = resources.displayMetrics.density

    /** Called whenever the overlay is measured; pixels are in the overlay's own space. */
    var viewportListener: LyricsPlayerViewportListener? = null

    private val compose = ComposeView(context).apply {
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            LyricsPlayerPage(
                port = portState.value,
                insets = insetsState.value,
                visible = visibleState.value,
            )
        }
    }

    init {
        // Opaque and touchable: nothing of the host lyrics page may show through or get touches.
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        // Stay gone until the host has a document and calls show(): an opaque overlay must
        // never flash over the host page while the port is still empty (spec §1 D7).
        visibility = GONE
        bindOwners()
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Attach the host implementation; pass null while the host is tearing down. */
    fun setPort(port: LyricsPlayerPort?) {
        portState.value = port
    }

    /** Host-owned safe-area insets; the page pads the top bar and the transport with them. */
    fun setSafeInsets(insets: LyricsPlayerInsets) {
        insetsState.value = insets
    }

    fun setSafeInsets(leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int) {
        setSafeInsets(LyricsPlayerInsets(leftPx, topPx, rightPx, bottomPx))
    }

    /** Show the page and let it take touches. */
    fun show() {
        if (visibleState.value) return
        visibleState.value = true
        visibility = VISIBLE
        foreground(true)
    }

    /** Hide the page; the host page below becomes visible and touchable again. */
    fun hide() {
        visibleState.value = false
        visibility = GONE
        foreground(false)
    }

    val isPageVisible: Boolean get() = visibleState.value

    /** Host lifecycle hook: drops the composition to CREATED while the activity is paused. */
    fun foreground(active: Boolean) {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        owner.registry.currentState = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        viewportWidthPx = width
        viewportHeightPx = height
        viewportListener?.onViewportChanged(width, height, density)
        super.onSizeChanged(width, height, oldWidth, oldHeight)
    }

    /**
     * The host usually pushes insets through [setSafeInsets] because it already knows which
     * window areas the activity reserves. This is only a fallback for a plain decor attach.
     */
    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        setSafeInsets(
            LyricsPlayerInsets(
                leftPx = insets.systemWindowInsetLeft,
                topPx = insets.systemWindowInsetTop,
                rightPx = insets.systemWindowInsetRight,
                bottomPx = insets.systemWindowInsetBottom,
            ),
        )
        return insets
    }

    private fun bindOwners() {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    override fun onAttachedToWindow() {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            owner = Owner()
            bindOwners()
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        // Keep recomposition inside this island: the host's content root uses obfuscated
        // AndroidX types that our module cannot satisfy, so we never use its recomposer.
        val uiDispatcher = AndroidUiDispatcher.CurrentThread
        val ownRecomposer = Recomposer(uiDispatcher).also { recomposer = it }
        compositionScope = CoroutineScope(uiDispatcher + SupervisorJob()).also { scope ->
            scope.launch { ownRecomposer.runRecomposeAndApplyChanges() }
        }
        compose.setParentCompositionContext(ownRecomposer)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        compose.disposeComposition()
        recomposer?.cancel()
        compositionScope?.cancel()
        recomposer = null
        compositionScope = null
        owner.registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }
}
