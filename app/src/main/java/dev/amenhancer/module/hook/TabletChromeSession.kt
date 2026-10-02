package dev.amenhancer.module.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.ViewBackdrop
import dev.amenhancer.glass.GlassCapsuleBounds
import dev.amenhancer.glass.GlassGeometry
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.GlassMiniPlayer
import dev.amenhancer.glass.GlassMiniPlayerCommand
import dev.amenhancer.glass.GlassMiniPlayerIcons
import dev.amenhancer.glass.GlassMiniPlayerState
import dev.amenhancer.glass.GlassNavigation
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.glass.GlassRepeatMode
import dev.amenhancer.glass.GlassTab
import dev.amenhancer.glass.GlassTabDisplay
import dev.amenhancer.glass.TabletGlassGestureGate
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TabletChromeStyle
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings
import java.lang.reflect.Method

/**
 * iPad-style tablet chrome: [GlassNavigation] renders the navigation as one floating capsule at
 * the window's top centre and [GlassMiniPlayer] renders the transport as one capsule at the bottom
 * centre. The host's native bottom tab strip and the author's full-width bottom mini glass are
 * parked, while the inherited [PhoneGlassSession] pipeline keeps owning the player transition and
 * the peek handling.
 *
 * The top capsule is a pure overlay: it floats over `navigation_host_group` behind a started
 * [ViewBackdrop] — the same material contract `PhoneGlassSession` uses for its bottom bar — and
 * reserves **no** top padding, so the page keeps scrolling under the glass. The session owns only
 * the two capsules, their seat, and touch ownership of their bands. Every native write goes through
 * the inherited compare-then-write seams: the tabs frame, the native mini root/content and the
 * author's mini glass are snapshotted through `hideSeam` (a [PhoneGlassSession] `NativeViewState`
 * record) and [PhoneGlassSession.close] restores every one of them.
 */
@RequiresApi(33)
internal class TabletChromeSession(
    activity: Activity,
    config: TargetConfigClient,
    private val onFailure: (Throwable) -> Unit,
) : PhoneGlassSession(activity, config, onFailure) {

    /** Mirrors the ~500 ms settings cadence PhoneGlassSession already uses for its own re-checks. */
    private companion object {
        const val TOP_REFRESH_INTERVAL_MS = 500L

        /** Slide progress that means "the full player owns the screen". */
        const val EXPANDED_SLIDE = 1f

        /** Upper end of the inherited material band: `blend(0f, 0.35f)` (same as `glassExpansion`). */
        const val MATERIAL_PROGRESS_END = 0.35f
    }

    /**
     * The iPad-style chrome is its own feature, so a successful mount reports there rather than
     * under the phone glass toggle that merely gates it.
     */
    override val glassFeatureKey: String get() = ModuleConstants.FEATURE_TABLET_CHROME

    override val glassActiveMessage: String
        get() = "iPad 风格界面已挂载：顶部导航胶囊已接管平板导航，原生底栏已隐藏；真机视觉验收另行记录"

    /** Screen-space rectangle of a rendered capsule; written on Compose layout, read on touch. */
    private class CapsuleFrame {
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        val ready: Boolean get() = right > left && bottom > top
    }

    // ---- Top capsule -------------------------------------------------------------------------

    private val topTouchGate = TabletGlassGestureGate()
    private val topCapsuleFrame = CapsuleFrame()
    private val topLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { topLayoutDirty = true }

    private var topClosed = false
    private var topFailureScheduled = false
    private var topLayoutDirty = true
    private var topRefreshAt = 0L
    private var topGlass: GlassHostView? = null
    private var topBackdrop: ViewBackdrop? = null
    private var topObserver: ViewTreeObserver? = null
    private var navView: View? = null
    private var topBarHeightPx = 0
    private var topBarMarginPx = 0
    private var topPanelBlurDp = GlassPolicy.PANEL_BLUR_DP.toInt()

    /**
     * Latest sheet progress, mirrored from [onSlide]. A plain float so the pre-draw path can read
     * it without boxing or allocation.
     */
    private var topSlide = 0f

    /** Cached host typeface for the mini-player text lines, and the view it was read from. */
    private var miniLabelTypefaceCache: android.graphics.Typeface? = null
    private var miniLabelTypefaceSource: View? = null

    private var topTabs by mutableStateOf(emptyList<GlassTab>())
    private var topSelectedId by mutableIntStateOf(View.NO_ID)
    private var topAccent by mutableStateOf(Color(0xFFFA233B))
    private var topForeground by mutableStateOf(Color.Black)
    private var topHostConfiguration by mutableStateOf(Configuration(activity.resources.configuration))
    private var topMenuKey: List<Any?> = emptyList()

    // ---- Bottom mini-player capsule ----------------------------------------------------------

    // The iPad capsule is the inherited `miniGlass` itself, re-skinned with GlassMiniPlayer. That
    // one View is the only surface the author's `updateTransition` grows (height/top/margins from
    // `slide`), so reusing it is what makes mini -> full continuous. The native mini root, the
    // content that carries the host artwork, and the image view actually read from are cached
    // together, so a refresh never scans the hierarchy per frame.
    private val miniTouchGate = TabletGlassGestureGate()

    /**
     * Owns the "should the host player behavior intercept this gesture" latch. A DOWN inside a
     * capsule takes the whole gesture away from the behavior so the module's Compose view receives
     * it; a DOWN outside leaves the behavior alone so the host page keeps its native handling.
     */
    private val behaviorBypassGate = TabletGlassGestureGate()

    private val miniCapsuleFrame = CapsuleFrame()
    private var miniListener: AutoCloseable? = null
    private var miniListenerBound = false
    private var miniSuppressedRoot: View? = null
    private var miniPlayerContent: View? = null
    private var miniArtworkContainer: View? = null
    private var miniArtworkImage: View? = null
    private var miniArtworkMethod: Method? = null

    /**
     * The author glass whose Compose content this session re-skins. Held so the content is
     * installed exactly once per glass instance: `GlassHostView.content` feeds a Compose
     * `MutableState`, so re-setting it every frame would be pure churn.
     */
    private var miniStyledGlass: GlassHostView? = null

    /** The **collapsed** reference height the capsule content measures its cover/corner against. */
    private var miniPanelHeightPx = 0

    /** Cached [geometry]; resolved once the host's `miniplayer_height` dimension is readable. */
    private var resolvedGeometry: GlassGeometry? = null

    /**
     * `blend(0f, 0.35f)` of the sheet progress — the same value the inherited `updateTransition`
     * writes to `glassExpansion`. It drives only the Compose material's shape; the glass's height,
     * seat and margins stay owned by the inherited geometry maths, exactly like the original.
     */
    private var miniExpansion by mutableFloatStateOf(0f)

    /** Written by the host controller listener (any thread), consumed on the next pre-draw. */
    @Volatile
    private var miniStateDirty = false

    private var miniState by mutableStateOf(
        GlassMiniPlayerState(
            isPlaying = false,
            shuffleOn = false,
            repeatMode = GlassRepeatMode.OFF,
            enabled = false,
        ),
    )
    private var miniTitle by mutableStateOf("")
    private var miniArtist by mutableStateOf("")
    private var miniCover by mutableStateOf<Drawable?>(null)
    private var miniIcons by mutableStateOf(
        GlassMiniPlayerIcons(
            shuffle = null,
            shuffleOn = null,
            previous = null,
            play = null,
            pause = null,
            next = null,
            repeat = null,
            repeatOn = null,
            repeatOne = null,
            repeatOneOn = null,
            lyrics = null,
            queue = null,
        ),
    )
    private var miniIconsConfiguration: Configuration? = null

    // Touch redirection: the DOWN chosen by a gesture is latched for its whole duration.
    private var redirectedMiniTarget: GlassHostView? = null
    private var redirectedMiniDownTime: Long? = null

    /**
     * Both tablet orientations use the same chrome: the host bool that selects the
     * `w640dp-port`/`w640dp-land` `bottom_navigation` layouts, falling back to `is_tablet`.
     * Never [TabletModeQualifier.isEligible], which additionally requires dual-pane landscape.
     */
    override fun sessionEligible(): Boolean {
        val settings = config.settings()
        return settings.phoneLiquidGlassEnabled &&
            settings.tabletChromeStyle == TabletChromeStyle.IPAD &&
            officialTablet()
    }

    /** The iPad style replaces the flat tablet tab strip; the stacked root stays the fallback. */
    override fun resolveBottomNavigationRoot(): View? =
        find("bottom_navigation_root_flat") ?: find("bottom_navigation_root_stacked")

    /**
     * The table form that matches the iPad mini band: its own `miniplayer_height` as the collapsed
     * height, side-by-side off so the collapsed band reads as a single centred capsule (the pill
     * row is a dual-pane concept). The inherited `nativePeekBaseline()` then agrees with the
     * host's actual tablet bottom band, exactly as the dual-pane form's does. Resolved once and
     * cached: the base reads `geometry` on every transition frame, so no per-frame allocation.
     */
    override val geometry: GlassGeometry
        get() = resolvedGeometry ?: run {
            // `dimen` returns device pixels; GlassGeometry.miniHeightDp is in dp, exactly what
            // `dp(geometry.miniHeightDp)` and `GlassPolicy.occupiedHeight` expect. Cache only once
            // the host dimension actually resolves, so an early read cannot pin the fallback.
            // The tab row moved to the TOP, so the bottom band holds the mini capsule alone:
            // `navHeightDp` and the inter-capsule `gapDp` must be 0 or
            // `GlassPolicy.occupiedHeight` reserves their height again and the page keeps a blank
            // strip below the capsule (the reported "迷你播放器下方多出一片空白").
            val miniDp = (dimen("miniplayer_height") / density).toInt()
            if (miniDp > 0) {
                GlassGeometry(
                    navHeightDp = 0,
                    miniHeightDp = miniDp,
                    gapDp = 0,
                    sideBySide = false,
                ).also { resolvedGeometry = it }
            } else {
                GlassGeometry(
                    navHeightDp = 0,
                    miniHeightDp = GlassGeometry.Tablet.miniHeightDp,
                    gapDp = 0,
                    sideBySide = false,
                )
            }
        }

    /**
     * Collapsed height of the bottom band: the mini capsule plus the lift under it, and nothing
     * else. The inherited formula also adds the host's `shadow_height`, but that shadow belongs to
     * the author's bottom scrim, which this session never renders (the tab row it fades is parked),
     * so counting it would reserve yet another blank strip under the capsule.
     */
    override fun peekHeight(): Int =
        dimen("miniplayer_height") + (bottomGapDp * density).toInt() + bottomInset

    /**
     * The centred iPad mini seat: the capsule sits where the host's tablet mini band collapses to
     * (half-width, centred), so `updateTransition` grows exactly that seat into the full player.
     * The nav slot is untouched — the top chrome replaces the native tab row entirely.
     */
    override fun capsuleMarginsPx(frameWidth: Int, mini: Boolean): IntArray =
        if (mini) {
            val width = TabletChromeLayoutPolicy.miniPlayerWidthPx(frameWidth)
            val left = TabletChromeLayoutPolicy.miniPlayerLeftPx(frameWidth)
            intArrayOf(left, frameWidth - left - width)
        } else {
            super.capsuleMarginsPx(frameWidth, mini)
        }

    override fun attachAvailableViews() {
        super.attachAvailableViews()
        if (topClosed) return
        try {
            attachTopChrome()
            attachMiniCapsule()
            // A host that re-inflated the mini player replaces the root underneath us; re-seat the
            // suppression as soon as the base republishes it, not a frame later.
            suppressNativeMiniChrome()
        } catch (error: Throwable) {
            scheduleTopFailure(error)
        }
    }

    /**
     * Once the inherited menu is mappable the top capsule is the only navigation UI, so park the
     * whole native tabs frame. That frame also carries the inherited bottom navigation capsule, so
     * hiding it leaves exactly one navigation surface. `hideSeam` records visibility/alpha/padding
     * through NativeViewState and PhoneGlassSession.close() restores them.
     *
     * While the inherited menu is not mappable the base keeps the native strip visible (and its own
     * glass transparent); the frame is left alone for that transient rather than blanking both.
     */
    override fun suppressNativeChromeSeams() {
        super.suppressNativeChromeSeams()
        if (!glassMenuReady) return
        hideSeam(navFrame)
    }

    // The flat boundary sync mutes its own geometry writes while the glass owns the chrome;
    // this session replaces the dual-pane glass session whenever the iPad style is selected.
    override fun onGlassOwnership(root: View?) {
        root?.let(TabletGlassChrome::markGlassActive)
    }

    override fun releaseGlassOwnership(root: View?) {
        root?.let(TabletGlassChrome::clearGlassActive)
    }

    override fun foreground(active: Boolean) {
        super.foreground(active)
        topGlass?.foreground(active)
    }

    /**
     * Mirrors the runtime's slide callback into [topSlide]. The base keeps owning the exit/geometry
     * maths; this session only needs the scalar to fade the top capsule out of the expanded player.
     */
    override fun onSlide(progress: Float) {
        super.onSlide(progress)
        val next = progress.coerceIn(0f, 1f)
        if (next != topSlide) topSlide = next
    }

    /**
     * Slide progress the chrome renders from. The mirrored callback value is authoritative once it
     * has fired, but a session created while the sheet was **already open** has never seen one: then
     * [isCollapsed] is the only truth, so the top capsule has to start parked instead of being drawn
     * over the full player (and the mini capsule has to start hidden instead of over it).
     */
    private fun effectiveSlide(): Float = if (topSlide <= 0f && !isCollapsed) EXPANDED_SLIDE else topSlide

    /**
     * Apple Music's own typeface, read off its native mini-player title. Using the host's own font
     * is what makes Latin letters and punctuation render in SF Pro while CJK falls back to the
     * system face, without the module shipping a font of its own. Cached and re-resolved only when
     * the host swaps the title view.
     */
    private fun miniLabelTypeface(): android.graphics.Typeface? {
        val view = find("mini_player_title")
        if (view !== miniLabelTypefaceSource) {
            miniLabelTypefaceSource = view
            miniLabelTypefaceCache = (view as? TextView)?.typeface
        }
        return miniLabelTypefaceCache
    }

    override fun onPreDraw(): Boolean {
        val result = super.onPreDraw()
        if (topClosed) return result
        try {
            if (!activated) return result
            // Re-read the host menu only on an actual layout change (OnGlobalLayout), on a
            // controller callback, or on the project's ~500 ms throttle, never per frame: the
            // menu/state compares then decide whether anything is rebuilt.
            val now = SystemClock.uptimeMillis()
            if (topLayoutDirty || miniStateDirty || now >= topRefreshAt) {
                topLayoutDirty = false
                miniStateDirty = false
                topRefreshAt = now + TOP_REFRESH_INTERVAL_MS
                refreshTopMetrics()
                refreshTopMenu()
                refreshMiniIcons()
                refreshMiniState()
                refreshMiniCover()
            }
            updateTopChrome()
            attachMiniCapsule()
            updateMiniChrome()
        } catch (error: Throwable) {
            scheduleTopFailure(error)
        }
        return result
    }

    override fun close() {
        if (topClosed) {
            super.close()
            return
        }
        topClosed = true
        releaseMiniCapsule()
        releaseTopChrome()
        // The base restores every NativeViewState snapshot, including the mini views this session
        // concealed: no reserved top space ever existed, so nothing else needs undoing.
        super.close()
    }

    // ---- Top capsule construction ------------------------------------------------------------

    private fun attachTopChrome() {
        if (topGlass != null || topClosed) return
        if (navFrame == null || hostRoot == null) return
        // The inherited nav surface already carries the isolated module Context (module
        // Resources/theme/class loader). Reuse it instead of duplicating moduleContext().
        val glassContext = navGlass?.context ?: return
        val content = find("navigation_host_group") as? ViewGroup ?: return
        val navigation = find("bottom_navigation") ?: return
        // A slim top toolbar, not the host's 56dp bottom tab row: see TOP_BAR_HEIGHT_DP. The
        // occupied-top-top calculation reads the same value so the two stay consistent.
        val navHeight = (TabletChromeLayoutPolicy.TOP_BAR_HEIGHT_DP * density).toInt()
        if (navHeight <= 0) return
        val parent = topMountParent(content) ?: return

        navView = navigation
        topPanelBlurDp = ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(
            config.settings().phoneLiquidGlassPanelBlurDp,
        )
        refreshTopMetrics()
        topHostConfiguration = Configuration(activity.resources.configuration)
        refreshTopMenu()
        refreshMiniIcons()

        // Exact original pattern (PhoneGlassSession.attachAvailableViews): the backdrop samples the
        // page content and is started so it captures before the capsule is revealed. A ViewBackdrop
        // that is never started, or whose capture is disabled, never becomes ready and the capsule
        // renders no glass at all.
        val backdrop = ViewBackdrop(content, ::scheduleTopFailure).also {
            topBackdrop = it
            it.start()
        }
        val glass = GlassHostView(glassContext).also { topGlass = it }
        glass.alpha = 0f
        glass.visibility = View.GONE
        val panelHeight = (navHeight / density).dp
        glass.content {
            TopHostConfiguration {
                // GlassHostView fills its Box, so the component's own bar is centred here; the
                // host's own gravity only decides where the floating band sits. The wrapper Box
                // carries the layout callback: the shared GlassNavigation capsule owns its own
                // drawing, and the Box's bounds are exactly the capsule's floating band.
                Box(
                    Modifier.fillMaxWidth().onGloballyPositioned(::recordTopCapsuleFrame),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    GlassNavigation(
                        tabs = topTabs,
                        selectedId = topSelectedId,
                        accent = topAccent,
                        foreground = topForeground,
                        backdrop = backdrop,
                        onSelect = ::selectTopTab,
                        panelHeight = panelHeight,
                        panelBlur = topPanelBlurDp.dp,
                        // The iPad reference turns the selected label and the search glyph the host
                        // accent; the phone/dual-pane bar keeps the library thumb alone.
                        tintSelectedWithAccent = true,
                        // The library records a second, faded copy of the cells into the layer its
                        // thumb refracts, which smears a ghost of the label inside the mask. The
                        // top bar opts out of that recording; the phone bar keeps it.
                        cleanSelectionMask = true,
                    )
                }
            }
        }
        // The top capsule is a pure overlay: it floats over the page, so no content padding is
        // written anywhere and the page keeps scrolling under the glass. Its width follows the live
        // tab count so it reads as the iPad top bar (a compact centred capsule), not a full band.
        parent.addView(
            glass,
            FrameLayout.LayoutParams(
                TabletChromeLayoutPolicy.topBarWidthPx(capsuleScreenWidthPx(parent), topTabs.size, density),
                navHeight,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = topBarMarginPx
            },
        )
        topObserver = activity.window.decorView.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(topLayoutListener)
        }
    }

    /**
     * The capsule must sit at the top of the window. `bottom_navigation_root_flat` is the
     * bottom-navigation ConstraintLayout (its child FrameLayout/gravity contract is not
     * guaranteed), so it is used only when it is a FrameLayout that already spans the window and
     * is not part of the backdrop source; otherwise the host's own content FrameLayout, which
     * always does, hosts the band.
     */
    private fun topMountParent(content: View): ViewGroup? {
        val contentFrame = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = hostRoot as? ViewGroup
        if (root is FrameLayout && contentFrame != null && !contains(content, root) && spansWindow(root, contentFrame)) {
            return root
        }
        return contentFrame as? FrameLayout
    }

    /** True when [parent] is [child] itself or one of its ancestors. */
    private fun contains(parent: View, child: View): Boolean =
        generateSequence(child) { it.parent as? View }.any { it === parent }

    private fun spansWindow(root: View, content: View): Boolean {
        if (root.height <= 0 || content.height <= 0) return false
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        val contentLocation = IntArray(2).also(content::getLocationOnScreen)
        return rootLocation[0] == contentLocation[0] &&
            rootLocation[1] == contentLocation[1] &&
            root.height >= content.height
    }

    // ---- Top capsule touch -------------------------------------------------------------------

    override fun shouldPassThroughTouch(view: View, event: MotionEvent): Boolean {
        if (view === topGlass) return passesThroughTopBand(event)
        if (view === miniGlass) return passesThroughMiniBand(event)
        return false
    }

    /**
     * The host player behavior must not swallow a gesture that starts on either capsule, otherwise
     * the module's Compose view never sees the DOWN and every button plus tap-to-expand is dead.
     * A DOWN outside both capsules leaves the behavior alone (the host page keeps its native
     * drag/handling). The choice is latched by the DOWN for the whole gesture.
     */
    override fun shouldBypassPlayerIntercept(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            return behaviorBypassGate.start(event.downTime, hitCapsule = !capsuleHitEither(event))
        }
        return behaviorBypassGate.isPassedThrough(event.downTime)
    }

    private fun capsuleHitEither(event: MotionEvent): Boolean {
        val top = topGlass?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
        if (top != null && capsuleHit(event)) return true
        val mini = miniGlass?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
        return mini != null && miniCapsuleHit(event)
    }

    // The host field declares BottomSheetBehavior<FrameLayout> but runs PlayerBottomSheetBehavior
    // (and is the activity's only Behavior field). Prefer the value whose runtime class names it so
    // the shared intercept hook's `playerBehavior === thisObject` test resolves for the bypass.
    override fun findPlayerBehavior(): Any? {
        generateSequence(activity.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .mapNotNull { field -> runCatching { field.isAccessible = true; field.get(activity) }.getOrNull() }
            .firstOrNull { it.javaClass.name.contains("PlayerBottomSheetBehavior") }
            ?.let { return it }
        return super.findPlayerBehavior()
    }

    private fun passesThroughTopBand(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val visible = topGlass?.isShown == true
            return topTouchGate.start(event.downTime, hitCapsule = !activated || (visible && capsuleHit(event)))
        }
        // Keep the owner chosen by the DOWN for the whole gesture.
        return topTouchGate.isPassedThrough(event.downTime)
    }

    private fun passesThroughMiniBand(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val visible = miniGlass?.isShown == true
            return miniTouchGate.start(event.downTime, hitCapsule = !activated || (visible && miniCapsuleHit(event)))
        }
        // Keep the owner chosen by the DOWN for the whole gesture.
        return miniTouchGate.isPassedThrough(event.downTime)
    }

    private fun capsuleHit(event: MotionEvent): Boolean {
        val glass = topGlass ?: return false
        val bounds = if (topCapsuleFrame.ready) {
            GlassCapsuleBounds(
                topCapsuleFrame.left,
                topCapsuleFrame.top,
                topCapsuleFrame.right,
                topCapsuleFrame.bottom,
            )
        } else {
            // Before the first Compose placement, fall back to the whole band.
            val location = IntArray(2).also(glass::getLocationOnScreen)
            GlassCapsuleBounds(
                location[0].toFloat(),
                location[1].toFloat(),
                (location[0] + glass.width).toFloat(),
                (location[1] + glass.height).toFloat(),
            )
        }
        return TabletChromeLayoutPolicy.capsuleContains(event.rawX, event.rawY, bounds)
    }

    private fun recordTopCapsuleFrame(coordinates: LayoutCoordinates) {
        val glass = topGlass ?: return
        val screen = IntArray(2).also(glass::getLocationOnScreen)
        val windowLocation = IntArray(2).also(glass::getLocationInWindow)
        val origin = coordinates.positionInWindow()
        val left = origin.x + (screen[0] - windowLocation[0])
        val top = origin.y + (screen[1] - windowLocation[1])
        topCapsuleFrame.left = left
        topCapsuleFrame.top = top
        topCapsuleFrame.right = left + coordinates.size.width
        topCapsuleFrame.bottom = top + coordinates.size.height
    }

    private fun miniCapsuleHit(event: MotionEvent): Boolean {
        val glass = miniGlass ?: return false
        val bounds = if (miniCapsuleFrame.ready) {
            GlassCapsuleBounds(
                miniCapsuleFrame.left,
                miniCapsuleFrame.top,
                miniCapsuleFrame.right,
                miniCapsuleFrame.bottom,
            )
        } else {
            // Before the first Compose placement, fall back to the raw host view bounds.
            val location = IntArray(2).also(glass::getLocationOnScreen)
            GlassCapsuleBounds(
                location[0].toFloat(),
                location[1].toFloat(),
                (location[0] + glass.width).toFloat(),
                (location[1] + glass.height).toFloat(),
            )
        }
        return TabletChromeLayoutPolicy.miniPlayerContains(event.rawX, event.rawY, bounds)
    }

    private fun recordMiniCapsuleFrame(coordinates: LayoutCoordinates) {
        val glass = miniGlass ?: return
        val screen = IntArray(2).also(glass::getLocationOnScreen)
        val windowLocation = IntArray(2).also(glass::getLocationInWindow)
        val origin = coordinates.positionInWindow()
        val left = origin.x + (screen[0] - windowLocation[0])
        val top = origin.y + (screen[1] - windowLocation[1])
        miniCapsuleFrame.left = left
        miniCapsuleFrame.top = top
        miniCapsuleFrame.right = left + coordinates.size.width
        miniCapsuleFrame.bottom = top + coordinates.size.height
    }

    /**
     * Delivers a collapsed mini gesture to the module's own Compose capsule when the host routes
     * the DOWN to the native player subtree instead of the capsule (the sheet container and
     * `player_root` are full-size native touch owners). Mirroring the dual-pane session's proven
     * redirect: the DOWN picks the capsule, every following event is forwarded to it translated
     * into its own coordinates, and the latch clears on UP/CANCEL. A DOWN outside the capsule
     * returns null so the host page keeps the event.
     */
    override fun dispatchCollapsedMiniTouch(view: View, event: MotionEvent): Boolean? {
        val capsule = miniGlass ?: return null
        if (view !== playerSheet && view.id != resourceId("player_root", "id")) return null
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            redirectedMiniTarget = capsule.takeIf {
                activated && isCollapsed && it.isShown && miniCapsuleHit(event)
            }
            redirectedMiniDownTime = event.downTime.takeIf { redirectedMiniTarget != null }
        }
        val target = redirectedMiniTarget?.takeIf { redirectedMiniDownTime == event.downTime } ?: return null
        val location = IntArray(2).also(target::getLocationOnScreen)
        val forwarded = MotionEvent.obtain(event)
        forwarded.setLocation(event.rawX - location[0], event.rawY - location[1])
        return try {
            target.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                redirectedMiniTarget = null
                redirectedMiniDownTime = null
            }
        }
    }

    // ---- Bottom mini-player capsule ----------------------------------------------------------

    /**
     * Re-skins the inherited `miniGlass` with [GlassMiniPlayer].
     *
     * This is the whole point of the fix: the author's `updateTransition` grows exactly this one
     * View from the collapsed mini band into the full player — its height, top seat and side
     * margins are interpolated from the sheet progress. Putting the iPad 7-control capsule *inside*
     * that surface, instead of mounting a second fixed-size capsule that only faded, is what makes
     * mini <-> full continuous. `GlassHostView.content` forwards to `ComposeView.setContent`, whose
     * content slot is a `MutableState`, so installing it once per glass instance replaces the
     * default `NativeLiquidButton` material and nothing is re-set per frame.
     */
    private fun attachMiniCapsule() {
        if (topClosed) return
        val glass = miniGlass ?: return
        if (miniStyledGlass === glass) return
        val backdrop = topBackdrop ?: return
        // Populate the Compose states before the composition is first committed.
        refreshMiniIcons()
        refreshMiniState()
        refreshMiniCover()
        // The collapsed reference height the content measures its own cover/corner against. It is
        // the host's own mini height once `geometry` resolves it, so it needs no separate lookup.
        if (miniPanelHeightPx <= 0) {
            miniPanelHeightPx = dimen("miniplayer_height").takeIf { it > 0 }
                ?: (geometry.miniHeightDp * density).toInt()
        }
        // Install once per author glass; a host that re-inflates the mini player yields a new
        // GlassHostView, which this identity check re-skins on the next pre-draw. Mark it only
        // after content() succeeds, so a failed install retries next frame.
        glass.content {
            TopHostConfiguration {
                val cover: (@Composable () -> Unit)? = if (miniCover != null) {
                    { MiniCoverArtwork() }
                } else {
                    null
                }
                GlassMiniPlayer(
                    state = miniState,
                    backdrop = backdrop,
                    icons = miniIcons,
                    accent = topAccent,
                    foreground = topForeground,
                    onCommand = ::handleMiniCommand,
                    onExpand = ::expandPlayer,
                    cover = cover,
                    title = miniTitle,
                    artist = miniArtist,
                    panelHeight = (miniPanelHeightPx / density).dp,
                    // Apple's own typeface for the two text lines: Latin and punctuation render in
                    // the host's SF Pro, CJK falls back to the system font.
                    labelTypeface = miniLabelTypeface(),
                    // The inherited transition's own 0..0.35 band, so the Compose shape morphs in
                    // lockstep with the geometry the base is writing to this same View.
                    expansion = miniExpansion,
                    panelBlur = topPanelBlurDp.dp,
                    modifier = Modifier.onGloballyPositioned(::recordMiniCapsuleFrame),
                )
            }
        }
        miniStyledGlass = glass
    }

    private fun releaseMiniCapsule() {
        miniListener?.let { handle -> runCatching { handle.close() } }
        miniListener = null
        miniListenerBound = false
        miniStyledGlass = null
        miniPanelHeightPx = 0
        resolvedGeometry = null
        miniSuppressedRoot = null
        miniPlayerContent = null
        miniArtworkContainer = null
        miniArtworkImage = null
        miniArtworkMethod = null
        redirectedMiniTarget = null
        redirectedMiniDownTime = null
        if (miniExpansion != 0f) miniExpansion = 0f
    }

    /**
     * Per-frame bottom-capsule work, compare-then-write throughout: mirror the inherited material
     * expansion so the Compose shape interpolates exactly like `NativeLiquidButton`'s, and keep the
     * native mini content laid out but un-drawn. Height, seat, side margins, alpha and visibility
     * are all deliberately **owned by the inherited `updateTransition`** — that is what keeps the
     * morph continuous, so nothing here writes geometry.
     */
    private fun updateMiniChrome() {
        val next = miniExpansionFor(effectiveSlide())
        if (miniExpansion != next) miniExpansion = next
        suppressNativeMiniChrome()
    }

    /** The inherited `blend(0f, 0.35f)`: the material's own expansion band, smooth-stepped. */
    private fun miniExpansionFor(progress: Float): Float {
        val t = (progress / MATERIAL_PROGRESS_END).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun capsuleScreenWidthPx(parent: View): Int =
        parent.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels

    /**
     * Suppresses the author's native bottom chrome for this session only.
     *
     * The native mini root itself is left **visible and laid out** — never `GONE`: it is what the
     * base's `miniVisible`, its own seat measurement, the collapsed peek height and the host's
     * artwork refresh all read. Only the content *inside* it is hidden, with `INVISIBLE` +
     * `alpha = 0`, which keeps measurement and artwork alive while drawing nothing.
     *
     * The first write goes through [hideSeam], which snapshots visibility/alpha into the inherited
     * `NativeViewState` *before* changing anything, so [PhoneGlassSession.close] restores the
     * host's original content exactly; [concealForArtwork] then downgrades `GONE` to `INVISIBLE`
     * so the subtree keeps measuring. The author's morphing glass is deliberately **not** hidden:
     * it is the surface this session re-skins and the one the inherited transition grows.
     */
    private fun suppressNativeMiniChrome() {
        val root = miniRoot ?: return
        if (root === playerSheet) return
        // Only conceal the host's own mini content once this session has actually re-skinned the
        // surface that replaces it. If the top chrome (and its shared backdrop) never mounted, the
        // capsule is never installed, and blanking the native content would leave an empty band.
        if (miniStyledGlass !== miniGlass) return
        if (miniSuppressedRoot !== root) {
            miniSuppressedRoot = root
            miniPlayerContent = find("mini_player_content")
            hideSeam(miniPlayerContent)
        }
        concealForArtwork(miniPlayerContent)
    }

    /** Compare-then-write conceal that keeps the view in layout; never touches a missing view. */
    private fun concealForArtwork(view: View?) {
        view ?: return
        if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
        if (view.alpha != 0f) view.alpha = 0f
    }

    /** Draws the host's already-loaded artwork, never its own. Nothing is drawn while it is null. */
    @Composable
    private fun MiniCoverArtwork() {
        val source = miniCover ?: return
        // Clone before setting bounds: the host keeps ownership of its own Drawable. A drawable
        // with no constant state falls back to drawing the host instance (it is invisible anyway).
        val artwork = remember(source) {
            runCatching { source.constantState?.newDrawable() }.getOrNull() ?: source
        }
        Canvas(Modifier.fillMaxSize()) {
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            try {
                artwork.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                artwork.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    /**
     * Host playback state -> capsule state. A missing or still-unavailable command surface renders
     * the capsule disabled instead of throwing.
     */
    private fun refreshMiniState() {
        val commands = TabletChromeRuntime.commands
        registerMiniListener(commands)
        val next = if (commands == null || !commands.available) {
            GlassMiniPlayerState(
                isPlaying = false,
                shuffleOn = false,
                repeatMode = GlassRepeatMode.OFF,
                enabled = false,
            )
        } else {
            GlassMiniPlayerState(
                isPlaying = commands.isPlaying(),
                shuffleOn = commands.shuffleEnabled(),
                repeatMode = TabletChromeLayoutPolicy.repeatModeOf(commands.repeatMode()),
                enabled = true,
            )
        }
        if (next != miniState) miniState = next
        val title = commands?.currentTitle().orEmpty()
        if (title != miniTitle) miniTitle = title
        val artist = commands?.currentArtist().orEmpty()
        if (artist != miniArtist) miniArtist = artist
    }

    /**
     * Loads the host's own transport drawables once per resource/theme change. Resolved strictly by
     * resource name at runtime (never a hard-coded id) and cached in Compose state so no frame or
     * layout pass re-resolves them. A name the host build does not carry resolves to null.
     */
    private fun refreshMiniIcons() {
        if (miniIconsConfiguration == topHostConfiguration) return
        miniIconsConfiguration = topHostConfiguration
        val next = GlassMiniPlayerIcons(
            shuffle = hostDrawable("ic_nowplaying_shuffle"),
            shuffleOn = hostDrawable("ic_nowplaying_shuffleon"),
            previous = hostDrawable("ic_nowplaying_mp_rewind"),
            play = hostDrawable("ic_nowplaying_mp_play"),
            pause = hostDrawable("ic_nowplaying_mp_pause"),
            next = hostDrawable("ic_nowplaying_mp_fforward"),
            repeat = hostDrawable("ic_nowplaying_repeat"),
            repeatOn = hostDrawable("ic_nowplaying_repeaton"),
            // The host ships one "repeat one, on" asset; seed both one-state slots with it so
            // whichever variant the component reads always paints the selected one glyph.
            repeatOne = hostDrawable("ic_nowplaying_repeatoneon"),
            repeatOneOn = hostDrawable("ic_nowplaying_repeatoneon"),
            lyrics = hostDrawable("selector_nowplaying_lyrics"),
            queue = hostDrawable("selector_nowplaying_queue"),
        )
        if (next != miniIcons) miniIcons = next
    }

    private fun hostDrawable(name: String): Drawable? {
        val id = resourceId(name, "drawable")
        if (id == 0) return null
        return runCatching { activity.getDrawable(id) }.getOrNull()
    }

    /**
     * Binds the host controller listener once, and only after the adapter actually has a live
     * controller; before that it would hand back a no-op handle that would never fire.
     */
    private fun registerMiniListener(commands: TabletChromeCommands?) {
        if (miniListenerBound || miniGlass == null) return
        val target = commands ?: return
        if (!target.available) return
        miniListener = runCatching { target.addListener(::onCommandsChanged) }.getOrNull()
        miniListenerBound = miniListener != null
    }

    /**
     * Controller callbacks can arrive on a binder thread, so only a flag and a frame request cross
     * the boundary; the Compose state is written later, on the main thread, in `onPreDraw`.
     */
    private fun onCommandsChanged() {
        miniStateDirty = true
        runCatching { activity.window.decorView.postInvalidateOnAnimation() }
    }

    private fun refreshMiniCover() {
        val drawable = nativeMiniArtwork()
        if (miniCover !== drawable) miniCover = drawable
    }

    /**
     * The artwork the host already loaded into its native mini player. The container
     * (`mini_player_content` -> `video_surface`) and the image view that actually carries the
     * drawable are cached and only re-resolved when the cached view detaches, so a refresh never
     * scans the hierarchy per frame.
     */
    private fun nativeMiniArtwork(): Drawable? {
        // Prefer the base's cached root; only fall back to a lookup when the host replaced it.
        val root = miniRoot?.takeIf { it.isAttachedToWindow }
            ?: find("mini_player")?.takeIf { it.isAttachedToWindow }
            ?: return null
        val container = miniArtworkContainer?.takeIf { it.isAttachedToWindow }
            ?: resolveArtworkContainer(root)?.also {
                miniArtworkContainer = it
                miniArtworkImage = null
            }
            ?: return null
        // A cached view that detached or temporarily lost its drawable is re-resolved (throttled
        // by the caller), so a host that swaps its image view is picked up without a per-frame scan.
        val image = miniArtworkImage?.takeIf { it.isAttachedToWindow && artworkDrawableOf(it) != null }
            ?: resolveArtworkImage(container)?.also { miniArtworkImage = it }
            ?: return null
        return artworkDrawableOf(image)
    }

    /**
     * `mini_player_content` -> `video_surface` (`com.apple.android.music.player.NowPlayingContentView`).
     * Falls back to the content container and then to the cached root so a build without
     * `video_surface` still yields the container the host fills.
     */
    private fun resolveArtworkContainer(root: View): View? {
        val contentId = resourceId("mini_player_content", "id")
        val content = (if (contentId != 0) root.findViewById<View>(contentId) else null)
            ?: find("mini_player_content")
            ?: root
        val surfaceId = resourceId("video_surface", "id")
        if (surfaceId != 0) content.findViewById<View>(surfaceId)?.let { return it }
        return content
    }

    /**
     * The view that carries the artwork itself: `getArtworkView()` first (resolved reflectively and
     * cached per class), then the first descendant `ImageView`/`ImageButton` with a live drawable.
     */
    private fun resolveArtworkImage(container: View): View? {
        reflectedArtworkView(container)?.let { if (artworkDrawableOf(it) != null) return it }
        return firstArtworkChild(container)
    }

    /** `NowPlayingContentView.getArtworkView()`, when the host build exposes one. */
    private fun reflectedArtworkView(container: View): View? {
        val method = miniArtworkMethod?.takeIf { it.declaringClass.isInstance(container) }
            ?: findArtworkMethod(container.javaClass)?.also { miniArtworkMethod = it }
            ?: return null
        return runCatching { method.invoke(container) as? View }.getOrNull()
    }

    /**
     * Resolves `getArtworkView` publicly or privately, walking superclasses: the host's View
     * subclass obfuscates most members but keeps this accessor on the verified build. Cached per
     * session (see [miniArtworkMethod]), so this never runs on a layout pass.
     */
    private fun findArtworkMethod(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { it.parameterCount == 0 && it.name == "getArtworkView" }
                ?.let { method ->
                    runCatching { method.isAccessible = true }
                    return method
                }
            current = current.superclass
        }
        return null
    }

    private fun artworkDrawableOf(view: View): Drawable? =
        if (view is ImageView) view.drawable else view.background

    private fun firstArtworkChild(root: View): View? {
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ImageView && child.drawable != null) return child
            firstArtworkChild(child)?.let { return it }
        }
        return null
    }

    /**
     * Forwards one capsule command to the host command surface. The adapter guards every call
     * internally, so a failure is swallowed here rather than reported as a session failure (which
     * would tear the whole glass down and restore the native UI).
     */
    private fun handleMiniCommand(command: GlassMiniPlayerCommand) {
        val commands = TabletChromeRuntime.commands
        if (commands != null) {
            runCatching {
                when (command) {
                    GlassMiniPlayerCommand.SHUFFLE ->
                        commands.setShuffleEnabled(!commands.shuffleEnabled())

                    GlassMiniPlayerCommand.PREVIOUS -> commands.skipToPrevious()
                    GlassMiniPlayerCommand.PLAY_PAUSE ->
                        if (commands.isPlaying()) commands.pause() else commands.play()

                    GlassMiniPlayerCommand.NEXT -> commands.skipToNext()
                    GlassMiniPlayerCommand.REPEAT -> commands.cycleRepeatMode()
                    // The lyrics button expands the full player, per the product decision.
                    GlassMiniPlayerCommand.LYRICS -> commands.expandPlayer(activity)
                    GlassMiniPlayerCommand.QUEUE -> commands.openQueue(activity)
                }
            }
        }
        miniStateDirty = true
    }

    /** Tapping anywhere on the mini capsule (outside a control) expands the full player. */
    private fun expandPlayer() {
        runCatching { TabletChromeRuntime.commands?.expandPlayer(activity) }
    }

    // ---- Top capsule content -----------------------------------------------------------------

    /** Host `Menu` -> [GlassTab]; only a changed menu key rebuilds the list. */
    private fun refreshTopMenu() {
        val navigation = navView ?: return
        val configuration = activity.resources.configuration
        if (topHostConfiguration != configuration) topHostConfiguration = Configuration(configuration)
        val menu = ModernXposedRuntime.callMethod(navigation, "getMenu") as Menu
        val selected = (ModernXposedRuntime.callMethod(navigation, "getSelectedItemId") as Number).toInt()
        val night = configuration.uiMode and 0x30 == 0x20
        val foreground = if (night) AndroidColor.WHITE else AndroidColor.BLACK
        val accentId = resourceId("color_primary", "color")
        val hostAccent = if (accentId != 0) activity.getColor(accentId) else 0xfffa233b.toInt()
        val items = (0 until menu.size()).map(menu::getItem).filter { it.isVisible }
        val key = items.flatMap { listOf(it.itemId, it.title?.toString(), it.isEnabled, it.icon) } +
            listOf(night, hostAccent)
        if (key != topMenuKey) {
            topMenuKey = key
            val searchId = resourceId("search_fragment", "id")
            topTabs = items.map { item ->
                GlassTab(
                    id = item.itemId,
                    title = item.title?.toString().orEmpty(),
                    // The component clones and tints the icon itself; never pre-tint the host drawable.
                    icon = item.icon,
                    enabled = item.isEnabled,
                    display = if (searchId != 0 && item.itemId == searchId) {
                        GlassTabDisplay.ICON
                    } else {
                        GlassTabDisplay.TEXT
                    },
                )
            }
            topForeground = Color(foreground)
            topAccent = Color(hostAccent)
        }
        if (topSelectedId != selected) topSelectedId = selected
    }

    /**
     * Drives the host and reports what it actually accepted, so a rejected tap leaves the
     * highlight where it was ([GlassNavigation] only moves it when the return value matches).
     */
    private fun selectTopTab(id: Int): Int {
        if (topTabs.none { it.id == id && it.enabled }) return topSelectedId
        runCatching {
            navView?.let { ModernXposedRuntime.callMethod(it, "setSelectedItemId", id) }
            refreshTopMenu()
        }.onFailure(::scheduleTopFailure)
        return topSelectedId
    }

    private fun refreshTopMetrics() {
        topBarHeightPx = dimen("navigation_tabs_height")
        if (topBarHeightPx <= 0) return
        val topInset = statusBarInset()
        topBarMarginPx = TabletChromeLayoutPolicy.capsuleTopMarginPx(topBarHeightPx, topInset, density)
    }

    private fun statusBarInset(): Int =
        activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0

    /** Compare-then-write for every per-frame value: width, height, seat, alpha and visibility. */
    private fun updateTopChrome() {
        val glass = topGlass ?: return
        if (topBarHeightPx <= 0) return
        val params = glass.layoutParams as? FrameLayout.LayoutParams
        if (params != null) {
            val parentWidth = (glass.parent as? View)?.width?.takeIf { it > 0 }
                ?: activity.resources.displayMetrics.widthPixels
            val width = TabletChromeLayoutPolicy.topBarWidthPx(parentWidth, topTabs.size, density)
            if (params.width != width ||
                params.height != topBarHeightPx ||
                params.topMargin != topBarMarginPx
            ) {
                params.width = width
                params.height = topBarHeightPx
                params.topMargin = topBarMarginPx
                params.leftMargin = 0
                params.rightMargin = 0
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                glass.layoutParams = params
            }
        }
        // The expanded full player owns the screen: the floating top bar fades out of the way and
        // is restored the moment the sheet collapses again.
        val hide = TabletChromeLayoutPolicy.expandHideFactor(effectiveSlide())
        val ready = activated && glassMenuReady && onTabPage() && hide < 1f
        val alpha = if (ready) 1f - hide else 0f
        if (glass.alpha != alpha) glass.alpha = alpha
        val visibility = if (ready) View.VISIBLE else View.GONE
        if (glass.visibility != visibility) glass.visibility = visibility
    }

    /**
     * Whether the host is currently showing one of its five tab pages.
     *
     * Settings and Account are not Activities: the host pushes them as preference fragments into the
     * selected tab's NavHost, so `navigation_host_group` (and our overlay on it) survives and the
     * capsule would otherwise float over them. The host itself decides one thing only there — its
     * own nav root goes GONE — so this mirrors that exact signal rather than inventing a rule:
     * `settings.fragment.p.onStart()` calls `MainContentActivity.Q1(0, true)`, which hides the view
     * `resolveBottomNavigationRoot()` already resolved, and the tab fragments restore it on the way
     * back. Reading it is free (one view, no hierarchy walk) and needs no new obfuscated symbol.
     *
     * Known and accepted: the Library tab also hides that root while a playlist-edit session is
     * active (`LibraryComposeContentFragment.shouldHideBottomNav()`), so the capsule hides there too
     * — which matches what the host is already doing with its own navigation.
     */
    private fun onTabPage(): Boolean = when (val root = hostRoot) {
        null -> true
        else -> root.isShown && root.visibility == View.VISIBLE
    }

    private fun releaseTopChrome() {
        topObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(topLayoutListener)
        topObserver = null
        topBackdrop?.close()
        topBackdrop = null
        topGlass?.let { glass -> (glass.parent as? ViewGroup)?.removeView(glass) }
        topGlass = null
        navView = null
    }

    private fun officialTablet(): Boolean =
        boolResource("multiply_tablet_layout_enabled") ?: boolResource("is_tablet") ?: false

    private fun boolResource(name: String): Boolean? {
        val id = resourceId(name, "bool")
        if (id == 0) return null
        return runCatching { activity.resources.getBoolean(id) }.getOrNull()
    }

    private fun scheduleTopFailure(error: Throwable) {
        if (topFailureScheduled || topClosed) return
        topFailureScheduled = true
        activity.window.decorView.post { onFailure(error) }
    }

    @Composable
    private fun TopHostConfiguration(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalConfiguration provides topHostConfiguration,
            LocalDensity provides Density(topHostConfiguration.densityDpi / 160f, topHostConfiguration.fontScale),
            LocalLayoutDirection provides if (topHostConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            },
            content = content,
        )
    }
}
