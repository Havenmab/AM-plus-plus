package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.ViewBackdrop
import dev.amenhancer.glass.BottomScrim
import dev.amenhancer.glass.GlassGeometry
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.GlassNavigation
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.glass.GlassTab
import dev.amenhancer.glass.NativeButtonInput
import dev.amenhancer.glass.NativeLiquidButton
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.EnhancementDefaults
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import dev.amenhancer.module.model.ModuleSettings
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@RequiresApi(33)
internal open class PhoneGlassSession(
    val activity: Activity,
    protected val config: TargetConfigClient,
    protected val hostBinding: ChromeHostBinding = AppleMusicHostFactory.bindChrome(activity),
    private val failure: (Throwable) -> Unit,
) : GlassSession, ViewTreeObserver.OnPreDrawListener {
    private val transformOwners = IdentityHashMap<View, MutableMap<String, dev.amenhancer.module.host.OwnedHostProperty<Float>>>()
    private val states = IdentityHashMap<View, NativeViewState>()
    private val layerAlphas = IdentityHashMap<View, NativeLayerAlpha>()
    private val visibleGlassRect = android.graphics.Rect()
    private val visibleGlassLocation = IntArray(2)
    private val windowLocation = IntArray(2)
    private var glassConsumersVisible = false
    private var writingLayerAlpha = false
    protected var navFrame: FrameLayout? = null
    private var navigation: View? = null
    private var source: ViewGroup? = null
    private var backdrop: ViewBackdrop? = null
    protected var navGlass: GlassHostView? = null
    protected var navScrim: GlassHostView? = null
    protected var miniGlass: GlassHostView? = null
    final override var miniRoot: FrameLayout? = null
        private set
    private var miniContent: View? = null
    final override var playerBehavior: Any? = null
        private set
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private var failureScheduled = false
    protected var hostRoot: View? = null
    protected var playerSheet: View? = null
    final override var activated = false
        private set
    private var tabs by mutableStateOf(emptyList<GlassTab>())
    private var selectedId by mutableIntStateOf(View.NO_ID)
    private var accent by mutableStateOf(Color.Red)
    private var foreground by mutableStateOf(Color.Black)
    private var hostConfiguration by mutableStateOf(Configuration(activity.resources.configuration))
    private var menuKey: List<Any?> = emptyList()
    private val input = NativeButtonInput()
    private var slide = 0f
    private var returningToMini = false
    protected val isCollapsed: Boolean get() = slide <= 0.001f
    protected val glassMenuReady: Boolean get() = tabs.size > 1 && tabs.any { it.id == selectedId }
    private var glassExpansion by androidx.compose.runtime.mutableFloatStateOf(0f)
    private var miniOffsetInSheet = 0
    private var navMarginPx = intArrayOf(0, 0)
    private var miniMarginPx = intArrayOf(0, 0)
    private var slottedMiniContent: View? = null
    private var lastPeek = -1
    private val nativePeek = NativePeekHeight()
    private val overflowClips = dev.amenhancer.module.host.HostClippingLeases<ViewGroup> { view ->
        dev.amenhancer.module.host.OwnedHostClipping(
            { view.clipChildren }, { view.clipChildren = it },
            { view.clipToPadding }, { view.clipToPadding = it },
        )
    }
    private val captureWait = dev.amenhancer.module.host.GlassCaptureWait()
    private val attachHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var behaviorRetries = 0
    private var retryPending = false
    private val retryAttach = Runnable {
        retryPending = false
        if (!closed && !failureScheduled && !activity.isDestroyed && !activity.isFinishing) {
            try { attachAvailableViews() } catch (error: Throwable) { scheduleFailure(error) }
        }
    }
    private var contentDownX = 0f
    private var contentDownY = 0f
    private var observingPress = false
    private var underlap = false
    private var scanNeeded = true
    private var scrollTargets: List<View> = emptyList()
    private var hierarchyDirty = false
    // AM++ scroll collapse. `collapse.value` is snapshot state, so the navigation and mini
    // glass islands both read this one animation and can never drift apart.
    private val collapse = androidx.compose.animation.core.Animatable(0f)
    private val collapseScope = kotlinx.coroutines.CoroutineScope(
        androidx.compose.ui.platform.AndroidUiDispatcher.Main + kotlinx.coroutines.SupervisorJob(),
    )
    private val collapseAccumulator by lazy {
        GlassScrollCollapseAccumulator(COLLAPSE_SCROLL_THRESHOLD_DP * density)
    }
    private val collapseScrollOffsets = IdentityHashMap<View, Int>()
    /**
     * Remembers whether the gesture in progress began on the **collapsed** mini player, so the
     * collapse tracker can stand down for it. Nothing is consumed; see [collapsedMiniTouch].
     */
    private val collapsedMiniGesture = GlassCollapsedMiniGesture()
    /**
     * Second signal source: the gesture itself. Compose pages expose no native scroll container,
     * so their scrolling is invisible to [collapseScrollListener] and only the touch that drives
     * it can be observed. Built on first use because it needs the activity's touch slop.
     */
    private val collapseTouch by lazy {
        GlassTouchCollapseTracker(
            slopPx = ViewConfiguration.get(activity).scaledTouchSlop.toFloat(),
            flingPxPerSecond = ViewConfiguration.get(activity).scaledMinimumFlingVelocity.toFloat(),
            thresholdPx = COLLAPSE_SCROLL_THRESHOLD_DP * density,
        )
    }
    /**
     * The single gate for every collapse code path.
     *
     * It folds in three things: the user's phone switch, the **tablet** switch when the row is
     * side-by-side, and the geometry. Starting it from `false` rather than a flat `true` matters
     * because the settings tick only runs every 500ms — a flat `true` would leave a tablet briefly
     * collapsing before the first tick could correct it, and the tablet's row has no collapsed
     * geometry to move into. `attachAvailableViews` resolves it from the live settings before
     * anything reads it, and `onPreDraw` keeps it current.
     *
     * It is **Compose state**, not a plain field, for the same reason the rebound amplitudes are:
     * `GlassNavigation` reads it while composing to decide whether to receive a `collapseProgress`
     * lambda at all, and a plain field would be captured once and never re-read — so toggling the
     * tablet switch would leave the row wired the way it was at attach until the host restarted.
     */
    private var collapseEnabled by mutableStateOf(false)
    private var collapseTarget = false
    private var collapseFrames: kotlinx.coroutines.Job? = null
    private val collapseScrollListener = ViewTreeObserver.OnScrollChangedListener { onScrollChanged() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scanNeeded = true; hierarchyDirty = true; hostBinding.invalidateViews()
    }
    private var nextSettingsCheck = 0L
    protected val density get() = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    protected val bottomInset get() = activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    protected open val miniVisible get() = miniRoot?.isShown == true
    // AM++: user-adjustable glass lift/material, captured with the session so every
    // height consumer (frame, content padding, peek) agrees within a frame.
    protected var bottomGapDp = GlassPolicy.BOTTOM_DP
    private var navBlurDp = GlassPolicy.PANEL_BLUR_DP.toInt()
    /**
     * Collapse-animation amplitudes, read live so the settings rows take effect on the next
     * collapse instead of needing a host restart.
     *
     * These are **Compose state**, not plain fields, and that is the fix for "setting the rebound to
     * 0% still rebounds, sometimes". A plain field is invisible to the composition: the slider
     * updates it, but the already-composed `GlassNavigation` never re-reads it, so the bar keeps
     * animating with whatever value was captured the last time something *else* happened to
     * recompose the row. That is exactly the intermittent behaviour — it "works sometimes" when an
     * unrelated recomposition (a tab change, a rotation) happens to pick the new value up.
     *
     * The two amplitudes are deliberately **shared** by the capsule and the detached search control:
     * the user asked for control over "the rebound" of the buttons, and two independent sliders would
     * let the two side buttons drift into animating differently — which is the exact complaint that
     * started this line of work.
     */
    protected var collapseReboundPercent by mutableIntStateOf(EnhancementDefaults.GLASS_COLLAPSE_REBOUND_PERCENT)
    protected var expandReboundPercent by mutableIntStateOf(EnhancementDefaults.GLASS_EXPAND_REBOUND_PERCENT)
    /**
     * The **mini player's** rebound amplitude, on its own scale.
     *
     * Separate from the two above because the travels differ by ~6x: the mini's margins span
     * `ROW_DP + GAP_DP = 51dp` while the side buttons span the whole row (~317dp). At a shared 100%
     * the buttons move 8dp — already clamped by the gap — while the mini moves 1.4dp, so a single
     * slider cannot make both read the same. Both directions of the mini's motion use this value.
     */
    protected var miniReboundPercent by mutableIntStateOf(EnhancementDefaults.GLASS_MINI_REBOUND_PERCENT)
    protected var miniSinkBounceDp by mutableIntStateOf(EnhancementDefaults.GLASS_MINI_SINK_BOUNCE_DP)

    /** Capsule geometry shared by every occupied-height consumer; a diverging form overrides this. */
    protected open val geometry: GlassGeometry get() = GlassGeometry.Phone

    protected open fun playerFragmentsAlphaFactor(progress: Float, materialProgress: Float): Float = materialProgress

    /**
     * Horizontal slot of a floating capsule as [left, right] margins. The phone
     * keeps the tuned symmetric margins; the tablet row carves asymmetric slots
     * (nav pill left, mini pill right) inside one centered row.
     */
    protected open fun capsuleMarginsPx(frameWidth: Int, mini: Boolean): IntArray {
        val side = dp(geometry.horizontalDp)
        return intArrayOf(side, side)
    }

    // Resource IDs are stable for this Activity's host APK. Keep values and Views live so
    // configuration changes and replaced page/player hierarchies still take effect.
    protected fun resourceId(role: ChromeResource): Int = hostBinding.resourceId(role)
    protected open fun find(role: ChromeResource): View? = hostBinding.find(role)
    protected fun dimen(role: ChromeResource): Int = hostBinding.dimension(role)

    protected fun writeOwnedTransform(view: View, property: String, value: Float) {
        val owner = transformOwners.getOrPut(view) { HashMap() }.getOrPut(property) {
            when (property) {
                "scaleX" -> dev.amenhancer.module.host.OwnedHostProperty({ view.scaleX }, { view.scaleX = it })
                "scaleY" -> dev.amenhancer.module.host.OwnedHostProperty({ view.scaleY }, { view.scaleY = it })
                "translationX" -> dev.amenhancer.module.host.OwnedHostProperty({ view.translationX }, { view.translationX = it })
                "translationY" -> dev.amenhancer.module.host.OwnedHostProperty({ view.translationY }, { view.translationY = it })
                else -> error("Unknown transform property")
            }
        }
        owner.set(value)
    }

    private fun save(view: View): NativeViewState = states.getOrPut(view) { NativeViewState(view) }

    /**
     * Native chrome seams must stay gone under the floating capsule. The phone
     * host carries only the tabs divider; the flat host adds more (see the
     * tablet session). Idempotent compare-then-write, run at activation and on
     * every transition frame, so a late (re)creation by host or installer code
     * cannot resurrect a seam; close() restores the saved states.
     */
    protected open fun suppressNativeChromeSeams() {
        hideSeam(find(ChromeResource.NAVIGATION_TABS_DIVIDER))
        navigation?.let { nav ->
            if (glassMenuReady) {
                // An alpha-hidden native strip still accepts taps across its full width.
                hideSeam(nav)
                nav.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                // A transient unmappable menu needs the native strip and its controls.
                states[nav]?.restoreInteraction(nav)
            }
        }
    }

    protected fun hideSeam(view: View?) {
        view ?: return
        save(view)
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    protected fun clearNativeBackground(view: View?) {
        view ?: return
        save(view)
        if (view.background != null) view.background = null
    }

    private fun overflowAncestors(view: View?): Sequence<ViewGroup> =
        generateSequence(view) { it.parent as? View }
            .takeWhile { it.layoutParams != null }.filterIsInstance<ViewGroup>()

    private fun allowGlassOverflow(view: View) {
        // Ancestors include DecorView: own clipping, never its visibility/alpha/layout.
        overflowAncestors(view).forEach(overflowClips::allowOverflow)
    }

    // Form seams overridden by the dual-pane session; the phone behavior below stays
    // exactly what shipped on the stacked host.
    protected open fun sessionEligible(): Boolean =
        config.settings().phoneLiquidGlassEnabled && !TabletModeQualifier.isOfficialTablet(activity)

    protected open fun resolveBottomNavigationRoot(): View? = find(ChromeResource.BOTTOM_NAVIGATION_ROOT_STACKED)

    // The stacked native holder (also installed by the tablet dual-pane adaptation)
    // reserves miniplayer_height even when mini is hidden, plus tabs and bottom inset.
    protected open fun nativePeekBaseline(): Int =
        bottomInset + dimen(ChromeResource.NAVIGATION_TABS_HEIGHT) + dimen(ChromeResource.MINIPLAYER_HEIGHT)

    /** Capsule exit driver; the phone host translates the frame from its own holder. */
    protected open fun driveNavFrameExit(progress: Float) = Unit

    protected open val navigationScrimEnabled: Boolean = true

    protected open fun miniMaterialParent(root: FrameLayout): FrameLayout =
        find(ChromeResource.PLAYER_SHEET_CONTAINER) as? FrameLayout ?: root

    protected open fun initialSlide(sheet: View, behavior: Any): Float = hostBinding.sheetSnapshot(behavior).let {
        InitialGlassSlide.resolve(it.state, sheet.top, it.collapsedTop, it.expandedTop)
    }

    /** Phone keeps its bottom fade inside the native tabs frame. */
    protected open fun attachNavigationScrim(frame: FrameLayout, scrim: GlassHostView) {
        frame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Chrome ownership hand-off; only the dual-pane session arbitrates ownership. */
    protected open fun onGlassOwnership(root: View?) = Unit

    protected open fun releaseGlassOwnership(root: View?) = Unit

    override fun attachAvailableViews() {
        if (closed || failureScheduled) return
        if (!sessionEligible()) {
            close()
            return
        }
        if (navGlass == null) {
            val glassSettings = config.settings()
            bottomGapDp = ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(glassSettings.phoneLiquidGlassBottomGapDp)
            navBlurDp = ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(glassSettings.phoneLiquidGlassPanelBlurDp)
            hostRoot = resolveBottomNavigationRoot() ?: return
            val frame = find(ChromeResource.BOTTOM_NAVIGATION_TABS_FRAME) as? FrameLayout ?: return
            val nav = find(ChromeResource.BOTTOM_NAVIGATION) ?: return
            val content = find(ChromeResource.NAVIGATION_HOST_GROUP) as? ViewGroup ?: return
            check(!isDescendant(frame, content)) { "Backdrop source contains the glass consumer" }
            navFrame = frame
            navigation = nav
            source = content
            playerBehavior = findPlayerBehavior()
            if (playerBehavior == null) {
                if (!retryPending) {
                    check(behaviorRetries < 20) { "Native player behavior not ready after retries" }
                    behaviorRetries++
                    retryPending = true
                    attachHandler.postDelayed(retryAttach, 50L)
                }
                return
            }
            attachHandler.removeCallbacks(retryAttach)
            retryPending = false
            // Resolve the collapse gate **before** anything reads it. The settings tick below only
            // runs every 500ms, and in between the scroll listener, the gesture path and
            // `GlassNavigation`'s own wiring all consult this flag — so leaving it at its safe
            // default would mean a phone whose user has the feature on would not collapse for the
            // first half-second, and a tablet could briefly collapse before the tick corrected it.
            val initialSettings = config.settings()
            collapseEnabled = initialSettings.phoneLiquidGlassCollapseEnabled &&
                (!geometry.sideBySide || initialSettings.phoneLiquidGlassTabletCollapseEnabled)
            val bg = ViewBackdrop(content, ::scheduleFailure).also { backdrop = it; it.start() }
            refreshMenu()
            // Bottom fade: blurred, washed-out strip under the tabs, matching the
            // reference apps' gradient bar. Added first so the tabs stay on top.
            if (navigationScrimEnabled) {
                val scrim = GlassHostView(moduleContext(), bleedDp = 0).also { navScrim = it }
                scrim.alpha = 0f
                scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                scrim.content { HostConfiguration { BottomScrim(bg) } }
                attachNavigationScrim(frame, scrim)
            }
            val glass = GlassHostView(moduleContext()).also { navGlass = it }
            glass.alpha = 0f
            glass.content { HostConfiguration { GlassNavigation(tabs, selectedId, accent, foreground, bg, ::selectTab, panelBlur = navBlurDp.dp,
                // `null` is `GlassNavigation`'s explicit "this host does not support the collapse"
                // opt-in, and it is the only value that keeps the tablet's row intact: with a
                // non-null lambda the capsule would shrink to a 43dp circle inside the tablet's
                // 23%-wide slot and the detached search control would try to appear, even though
                // that row has no collapsed geometry to move into. Gating on `collapseEnabled`
                // means the tablet switch is what decides, not the geometry alone.
                collapseProgress = if (collapseEnabled) ::collapseProgress else null,
                onExpandRequest = { requestCollapse(false) },
                collapseReboundScale = collapseReboundPercent / 100f,
                expandReboundScale = expandReboundPercent / 100f) } }
            val navSlot = capsuleMarginsPx(frame.width, mini = false)
            frame.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GlassPolicy.NAV_HEIGHT_DP), Gravity.TOP).apply {
                leftMargin = navSlot[0]; rightMargin = navSlot[1]
            })
            observer = activity.window.decorView.viewTreeObserver.also {
                it.addOnPreDrawListener(this)
                it.addOnGlobalLayoutListener(layoutListener)
                if (collapseEnabled) it.addOnScrollChangedListener(collapseScrollListener)
            }
            driveCollapseFrames()
        }
        val root = (find(ChromeResource.MINI_PLAYER) ?: find(ChromeResource.MINI_PLAYER_TOUCH_PANEL)) as? FrameLayout
        val content = root?.findViewById<View>(resourceId(ChromeResource.MINI_PLAYER_CONTENT))
        if (root != null && (root !== miniRoot || content !== miniContent)) {
            miniGlass?.let { (it.parent as? ViewGroup)?.removeView(it) }
            miniRoot?.let { states.remove(it)?.restore(it) }
            miniContent?.let {
                transformOwners.remove(it)?.values?.forEach(AutoCloseable::close)
                states.remove(it)?.restore(it)
            }
            // Restore old NativeViewState first; its clipping baseline may already be false.
            // Drop detached roots/private ancestors, keeping those used by either live surface.
            overflowClips.retainOnly((overflowAncestors(navFrame) + overflowAncestors(root)).asIterable())
            miniRoot = root
            miniContent = content
            val bg = backdrop ?: return
            val glass = GlassHostView(moduleContext()).also { miniGlass = it }
            glass.alpha = 0f
            glass.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            glass.content {
                HostConfiguration {
                    NativeLiquidButton(bg, input, glassExpansion, panelBlur = navBlurDp.dp, autoClip = geometry.sideBySide,
                        miniHeightDp = geometry.miniHeightDp) { sx, sy, x, y ->
                        miniContent?.let { v -> writeOwnedTransform(v, "scaleX", sx); writeOwnedTransform(v, "scaleY", sy); writeOwnedTransform(v, "translationX", x); writeOwnedTransform(v, "translationY", y) }
                    }
                }
            }
            // The native mini container disappears early in the opening animation.
            // Keep the material behind the whole sheet, independent of that container.
            val surfaceParent = miniMaterialParent(root)
            playerSheet = surfaceParent
            surfaceParent.addView(glass, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(geometry.miniHeightDp), Gravity.TOP).apply {
                val slot = capsuleMarginsPx(navFrame?.width ?: 0, mini = true)
                leftMargin = slot[0]; rightMargin = slot[1]
            })
            if (activated) prepareMini()
        }
        states.forEach { (view,state) -> state.captureOwned(view) }
    }

    override fun ownsCurrentHierarchy(): Boolean {
        hostBinding.invalidateViews()
        return !closed && (navFrame == null || find(ChromeResource.BOTTOM_NAVIGATION_TABS_FRAME) === navFrame)
    }

    @androidx.compose.runtime.Composable
    private fun HostConfiguration(content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(
            LocalConfiguration provides hostConfiguration,
            LocalDensity provides Density(hostConfiguration.densityDpi / 160f, hostConfiguration.fontScale),
            LocalLayoutDirection provides if (hostConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            content = content,
        )
    }

    private fun moduleContext(): Context {
        // Package-name lookup is filtered inside the host process. The framework supplies
        // the installed module's ApplicationInfo, including its readable APK paths.
        val info = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
        val apkResources = activity.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION")
        val isolatedResources = Resources(apkResources.assets, activity.resources.displayMetrics, Configuration(activity.resources.configuration))
        val moduleTheme = isolatedResources.newTheme().apply {
            applyStyle(info.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true)
        }
        return object : ContextWrapper(activity) {
            override fun getResources(): Resources = isolatedResources
            override fun getAssets() = isolatedResources.assets
            override fun getClassLoader(): ClassLoader = GlassHostView::class.java.classLoader!!
            override fun getTheme(): Resources.Theme = moduleTheme
        }
    }

    private fun refreshMenu() {
        if (hostConfiguration != activity.resources.configuration) {
            hostConfiguration = Configuration(activity.resources.configuration)
            // A new configuration re-measures every slot, so the collapsed geometry would be
            // computed against a stale width. Return to the expanded bar and start over.
            requestCollapse(false)
            collapseAccumulator.reset()
            collapseScrollOffsets.clear()
        }
        val nav = navigation ?: return
        val snapshot = hostBinding.navigation(nav)
        val menu = snapshot.menu
        val selected = snapshot.selectedId
        val night = activity.resources.configuration.uiMode and 0x30 == 0x20
        val fg = if (night) AndroidColor.WHITE else AndroidColor.BLACK
        val accentId = resourceId(ChromeResource.COLOR_PRIMARY)
        val hostAccent = if (accentId != 0) activity.getColor(accentId) else 0xfffa233b.toInt()
        val items = (0 until menu.size()).map(menu::getItem).filter { it.isVisible }
        val key = items.flatMap { listOf(it.itemId, it.title?.toString(), it.isEnabled, it.icon) } + listOf(night, hostAccent)
        if (key != menuKey) {
            menuKey = key
            tabs = items.map {
                val icon = it.icon?.constantState?.newDrawable(activity.resources)?.mutate()?.apply { setTint(fg) }
                    ?: it.icon
                GlassTab(it.itemId, it.title?.toString().orEmpty(), icon, it.isEnabled, isSearch = isSearchItem(it.itemId))
            }
            foreground = Color(fg)
            accent = Color(hostAccent)
        }
        if (selectedId != selected) selectedId = selected
    }

    /**
     * The legacy host exposes only a [Menu] and its item IDs, so the search destination is
     * identified from the item's own resource entry name. Unreadable or unmatched names
     * report false on purpose: the scroll collapse then keeps every tab inside one capsule
     * instead of detaching a control that cannot be proven to be search.
     */
    private fun isSearchItem(itemId: Int): Boolean =
        runCatching { activity.resources.getResourceEntryName(itemId).contains("search", ignoreCase = true) }
            .getOrDefault(false)

    /**
     * Whether the scroll collapse is allowed to move the bar right now.
     *
     * While the full player is open the bar is fading out under a full-screen sheet, and the
     * collapsed geometry would fight the native transition; the same applies before the glass
     * has taken over. Both conditions simply hold the bar at its expanded geometry.
     */
    private val collapseActive: Boolean
        get() = activated && collapseEnabled && !closed && slide <= 0.001f

    /**
     * Vertical scroll displacement of [view].
     *
     * `View.getScrollY()` is the wrong reading for a `RecyclerView`: it recycles its children and
     * so never scrolls its own canvas, keeping the real displacement in
     * `computeVerticalScrollOffset()` instead. That method is `protected` on `View`, so it cannot
     * be called through a `View` reference — only through the subclass that promotes it to
     * public. Every other scroll container in this host (ScrollView, NestedScrollView, ListView)
     * moves its own canvas, so `scrollY` is already the correct reading for them.
     */
    private fun scrollOffsetOf(view: View): Int =
        if (view is androidx.recyclerview.widget.RecyclerView) view.computeVerticalScrollOffset()
        else view.scrollY

    /**
     * Scroll-driven collapse. The measured displacement is accumulated rather than the raw
     * offset, so a container that recycles or re-lays-out cannot move the bar on its own.
     *
     * The accumulator is fed the *negation* of the offset delta: a scrolling page increases its
     * own offset while the content moves up, and the accumulator reads a negative delta as
     * "content moved up, collapse". Feeding the delta unchanged would invert the bar's direction
     * on every native page.
     */
    private fun onScrollChanged() {
        if (!collapseActive) { collapseScrollOffsets.clear(); collapseAccumulator.reset(); return }
        runCatching {
            for (target in scrollTargets) {
                if (!target.isShown) continue
                val offset = scrollOffsetOf(target)
                val previous = collapseScrollOffsets.put(target, offset) ?: offset
                val decision = collapseAccumulator.accumulate(previous - offset) ?: continue
                GlassCollapseDiagnostics.event("scroll source -> ${if (decision) "collapse" else "expand"}")
                requestCollapse(decision)
            }
        }.onFailure { error -> scheduleFailure(error) }
    }

    /**
     * Drives the bar between its expanded and collapsed geometry.
     *
     * The target and the animation spec are chosen together, in the same frame, because
     * `animateTo` only adopts a spec at the moment the animation starts; deriving the direction
     * from an animation frame instead would pick the wrong spring for a reversal. The animation
     * itself keeps its current value and velocity, so a mid-flight reversal stays continuous.
     */
    fun requestCollapse(collapsed: Boolean) {
        if (!collapsed && !collapseTarget && collapse.value == 0f) return
        collapseTarget = collapsed
        val target = if (collapsed) 1f else 0f
        if (collapse.value == target) return
        val spec = if (collapsed) dev.amenhancer.glass.GlassBarCollapseSpec
        else dev.amenhancer.glass.GlassBarExpandSpec
        collapseScope.launch { collapse.animateTo(target, spec) }
        driveCollapseFrames()
    }

    /**
     * Keeps the host traversing while the bar moves.
     *
     * The peek height and the mini player's margins are native properties, and this session only
     * writes them from `onPreDraw`. An animation that only changed Compose state would therefore
     * move the glass while the native mini player and the sheet's peek stayed where they were —     * and because nothing else invalidates the tree, no further `onPreDraw` would even arrive.
     * Requesting a traversal per value change keeps the two in step; when the bar is at rest the
     * flow is silent and the host is left alone.
     */
    private fun driveCollapseFrames() {
        if (collapseFrames != null) return
        collapseFrames = collapseScope.launch {
            snapshotFlow { collapse.value }.collect {
                activity.window.decorView.postOnAnimation { activity.window.decorView.invalidate() }
            }
        }
    }

    private fun observeScrollCollapse(observe: Boolean) {
        val tree = activity.window.decorView.viewTreeObserver
        if (!tree.isAlive) return
        if (observe) tree.addOnScrollChangedListener(collapseScrollListener)
        else tree.removeOnScrollChangedListener(collapseScrollListener)
    }

    /** Current collapse progress; read by both glass islands and by every geometry consumer. */
    protected fun collapseProgress(): Float = collapse.value

    private fun selectTab(id: Int): Int {
        if (tabs.none { it.id == id && it.enabled }) return selectedId
        // Choosing a destination returns the bar to its expanded form: the collapsed capsule
        // shows one glyph, so a selection made from it would otherwise be unverifiable.
        requestCollapse(false)
        runCatching {
            navigation?.let { hostBinding.selectNavigation(it, id) }
            refreshMenu()
        }.onFailure(::scheduleFailure)
        return selectedId
    }

    override fun onPreDraw(): Boolean {
        if (closed || failureScheduled) return true
        states.forEach { (view,state) -> state.observeNative(view) }
        try {
            if (hierarchyDirty) { hierarchyDirty = false; attachAvailableViews() }
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= nextSettingsCheck) {
                nextSettingsCheck = now + 500
                if (!sessionEligible()) {
                    activity.window.decorView.post { close() }
                    return true
                }
                // The collapse toggle is read live: turning it off must expand a collapsed bar
                // rather than leaving it stuck in a state the user just disabled.
                //
                // The tablet row needs BOTH switches. Its shape is different enough that the phone
                // default must not silently opt a tablet in — and, symmetrically, the tablet switch
                // alone must not enable the collapse on a phone. Requiring both keeps each switch
                // honest about what it turns on.
                val liveSettings = config.settings()
                val collapseWanted = liveSettings.phoneLiquidGlassCollapseEnabled &&
                    (!geometry.sideBySide || liveSettings.phoneLiquidGlassTabletCollapseEnabled)
                if (collapseWanted != collapseEnabled) {
                    collapseEnabled = collapseWanted
                    observeScrollCollapse(collapseWanted)
                    if (!collapseWanted) requestCollapse(false)
                }
                if (!collapseEnabled && collapse.value != 0f) requestCollapse(false)
                // The amplitudes are read on the same tick as the toggle that gates them. Writing
                // Compose state here is what makes the slider take effect on the next collapse:
                // a plain field would be captured by the composition once and never re-read.
                collapseReboundPercent = ModuleSettings.normalizePhoneLiquidGlassCollapseReboundPercent(
                    liveSettings.phoneLiquidGlassCollapseReboundPercent,
                )
                expandReboundPercent = ModuleSettings.normalizePhoneLiquidGlassExpandReboundPercent(
                    liveSettings.phoneLiquidGlassExpandReboundPercent,
                )
                miniReboundPercent = ModuleSettings.normalizePhoneLiquidGlassMiniReboundPercent(
                    liveSettings.phoneLiquidGlassMiniReboundPercent,
                )
                miniSinkBounceDp = ModuleSettings.normalizePhoneLiquidGlassMiniSinkBounceDp(
                    liveSettings.phoneLiquidGlassMiniSinkBounceDp,
                )
            }
            refreshMenu()
            if (!activated && backdrop?.ready == true && navGlass?.isLaidOut == true && glassMenuReady) {
                activate()
                return false // Layout the new occupied area before exposing either surface.
            }
            if (activated) {
                val menuReady = glassMenuReady
                val navAlpha = if (menuReady) 1f else 0f
                navGlass?.alpha = navAlpha
                navScrim?.alpha = navAlpha
                val navGlassRevealed = menuReady && navGlass?.visibility != View.VISIBLE
                navGlass?.let { glass ->
                    val visibility = if (menuReady) View.VISIBLE else View.GONE
                    if (glass.visibility != visibility) glass.visibility = visibility
                }
                navigation?.alpha = if (menuReady) 0f else 1f
                updateGeometry()
                val sourceNeedsLayout = updateUnderlap()
                val transitionNeedsLayout = updateTransition()
                val waitingForCapture = glassConsumersVisible && backdrop?.ready == false && canRefreshBackdrop()
                if (captureWait.timedOut(now, waitingForCapture)) {
                    scheduleFailure(IllegalStateException("Glass backdrop resume timed out; restoring native drawing"))
                    return true
                }
                // Insets can be reapplied when the native player finishes collapsing.
                // setLayoutParams only schedules layout: do not expose the old, shorter
                // content bounds (and window background beneath them) in this frame.
                if (navGlassRevealed || sourceNeedsLayout || transitionNeedsLayout || waitingForCapture) {
                    if (waitingForCapture) activity.window.decorView.postInvalidateOnAnimation()
                    return false
                }
            } else if (backdrop?.ready == true) {
                // Keep the initial capture for activation, but stop recording while
                // the menu has not produced a visible glass surface yet.
                backdrop?.setCaptureEnabled(
                    backdropConsumerVisible(navGlass) || backdropConsumerVisible(navScrim) ||
                        backdropConsumerVisible(miniGlass),
                )
            }
        } catch (error: Throwable) { scheduleFailure(error) }
        finally { states.forEach { (view,state) -> state.captureOwned(view) } }
        return true
    }

    private fun activate() {
        val nav = navigation ?: return
        val frame = navFrame ?: return
        // First layout may have dispatched its slide callback before this session existed.
        // Read the laid-out native state before changing peek height or hiding any layer.
        val sheet = find(ChromeResource.PLAYER_SHEET_CONTAINER) ?: return
        if (!sheet.isLaidOut) return
        playerSheet = sheet
        val behavior = checkNotNull(playerBehavior)
        slide = initialSlide(sheet, behavior)
        save(nav)
        save(frame)
        nav.alpha = 0f
        nav.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        frame.background = null
        frame.clipChildren = false
        frame.clipToPadding = false
        allowGlassOverflow(frame)
        suppressNativeChromeSeams()
        source?.let { save(it) }
        nativePeek.initialize(nativePeekBaseline())
        activated = true
        prepareMini()
        navGlass?.alpha = 1f
        navScrim?.alpha = 1f
        updateGeometry()
        updateUnderlap()
        updateTransition()
        // The dual-pane boundary sync yields geometry ownership once activation completes.
        onGlassOwnership(hostRoot)
        config.reportHealth(FeatureHealth(ModuleConstants.FEATURE_PHONE_LIQUID_GLASS, FeatureState.ACTIVE,
            "AndroidLiquidGlass 已挂载：实时背景、底栏透镜及迷你播放器；真机视觉验收另行记录", targetBuild(activity).displayName))
    }

    private fun prepareMini() {
        miniRoot?.let(::allowGlassOverflow)
        miniRoot?.let { save(it); it.background = null; it.clipChildren = false; it.clipToPadding = false }
        miniRoot?.let { root ->
            root.layoutParams = root.layoutParams.apply { height = dp(geometry.miniHeightDp) }
        }
        miniContent?.let { content ->
            save(content)
            val params = content.layoutParams
            val contentHeight = dp(minOf(GlassPolicy.MINI_HEIGHT_DP, geometry.miniHeightDp))
            params.height = contentHeight
            if (params is ViewGroup.MarginLayoutParams) {
                val slot = capsuleMarginsPx(navFrame?.width ?: 0, mini = true)
                params.leftMargin = slot[0]; params.rightMargin = slot[1]
                val topOffset = (dp(geometry.miniHeightDp) - contentHeight) / 2
                if (topOffset != 0) params.topMargin = topOffset
            }
            content.layoutParams = params
            listOf(ChromeResource.VIDEO_SURFACE_CONTAINER, ChromeResource.MINI_PLAYER_PLAY_BTN, ChromeResource.MINI_PLAYER_NEXT_BTN).forEach { name ->
                val id = resourceId(name)
                content.findViewById<View>(id)?.let { child ->
                    save(child)
                    child.layoutParams = child.layoutParams.apply {
                        width = dp(32)
                        height = dp(32)
                    }
                }
            }
        }
        listOf(ChromeResource.PLAYER_ROOT, ChromeResource.PLAYER_TOP_SHADOW, ChromeResource.BACKGROUND_LAYERS, ChromeResource.MOTION_SWITCHER, ChromeResource.PLAYER_FRAGMENTS_HOST).mapNotNull(::find).forEach(::save)
    }

    /**
     * How far the mini player sinks so its **bottom edge lands on the collapsed row's bottom edge**,
     * which is the line the two side buttons are bottom-anchored to.
     *
     * Zero while expanded and on the side-by-side tablet row, which has no collapse.
     *
     * This is the whole leftover space, not half of it. Centring the mini left its bottom edge a few
     * dp above the buttons', which made the collapsed row read as having drifted upwards relative to
     * the expanded bar — the row's bottom edge is the one the expanded bar already occupied.
     *
     * The user's sink bounce is added here rather than animated separately, so the mini's vertical
     * position keeps exactly one writer: this value is threaded through `syncMiniPresentation`,
     * which owns `miniRoot.translationY`. A second animation on the same property would fight it.
     */
    protected fun miniDropPx(): Float {
        if (!collapseEnabled) return 0f
        val p = collapse.value
        if (!p.isFinite()) return 0f
        // No `if (p <= 0f) return 0f` early return. The expand side (`p < 0`) is exactly where the
        // return rebound lives, so that guard was silently dropping the mini player's half of it.
        // Both terms below are already exactly zero for every `p >= 0`, so removing the guard only
        // adds motion on the return.
        //
        // The mini's rebound is **horizontal, not vertical**: it is the same height as the row, so
        // it has no vertical travel left to overshoot. Its width springs instead, through the
        // margins in `collapseAwareMiniSlot`, which is the surface the user actually sees move.
        val bottomAnchor = dev.amenhancer.glass.GlassBarCollapsePolicy.miniDrop(
            dp(geometry.miniHeightDp).toFloat(),
            dp(dev.amenhancer.glass.GlassBarCollapsePolicy.ROW_DP).toFloat(),
            p,
        )
        // The dip rides the collapse spring's own overshoot, so it needs no animation of its own
        // and cannot drift out of phase with the capsule's rebound. It is zero for the whole
        // travel and at rest, and non-zero only while the spring is past its end point.
        val dip = dev.amenhancer.glass.GlassBarCollapsePolicy.miniSinkOvershoot(
            amplitudePx = miniSinkBounceDp * density,
            springOvershoot = dev.amenhancer.glass.GlassBarCollapseSprings.CollapseOvershoot,
            p = p,
        )
        return bottomAnchor + dip
    }

    private fun updateGeometry() {
        val height = dp(GlassPolicy.NAV_HEIGHT_DP + bottomGapDp) + bottomInset
        navFrame?.let { frame ->
            // A generic copy constructor drops the host ConstraintLayout's bottom anchor.
            if (frame.layoutParams.height != height) frame.layoutParams = frame.layoutParams.apply { this.height = height }
            // The capsule slots may follow the host width (see the tablet row);
            // resync every surface whenever a resolved slot edge changes.
            val navSlot = capsuleMarginsPx(frame.width, mini = false)
            val miniSlot = collapseAwareMiniSlot(capsuleMarginsPx(frame.width, mini = true))
            fun applySlot(view: View?, slot: IntArray) {
                val surface = view ?: return
                val params = surface.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (params.leftMargin != slot[0] || params.rightMargin != slot[1]) {
                    params.leftMargin = slot[0]; params.rightMargin = slot[1]
                    surface.layoutParams = params
                }
            }
            val slotsChanged = !navSlot.contentEquals(navMarginPx) || !miniSlot.contentEquals(miniMarginPx)
            if (slotsChanged) {
                navMarginPx = navSlot
                miniMarginPx = miniSlot
                applySlot(navGlass, navSlot)
                applySlot(miniGlass, miniSlot)
            }
            // A new mini_player can arrive without a frame-width change. Its native
            // content must still occupy the cached slot, without resetting the
            // animated miniGlass margins on every transition frame.
            if (slotsChanged || slottedMiniContent !== miniContent) {
                applySlot(miniContent, miniSlot)
                slottedMiniContent = miniContent
            }
        }
        val peek = peekHeight()
        if (lastPeek != peek) {
            lastPeek = peek
            writePeek(peek)
        }
    }

    /**
     * Narrows the mini slot into the collapsed row: one gap after the withdrawn capsule, and one
     * gap before the detached search control. Only the phone's stacked form collapses; the
     * tablet row already shares one line and passes `sideBySide`, so it keeps its own slots.
     *
     * Both margins are expressed the same way the expanded slot is (insets from the row edges),
     * so the result feeds the existing `applySlot` compare-then-write unchanged.
     *
     * The progress handed to the margin functions is the **sprung** fraction, not the clamped one,
     * so the mini player's own glass backplate rebounds on the return exactly as the two side
     * buttons do. There is deliberately no `if (p <= 0f)` early return any more: that guard
     * returned the resting slot for the whole expand side, which is precisely where the rebound
     * lives, so the mini was the one surface on the row that could not spring back.
     */
    private fun collapseAwareMiniSlot(expanded: IntArray): IntArray {
        if (!collapseEnabled) return expanded
        val p = collapse.value
        val capsule = dp(dev.amenhancer.glass.GlassBarCollapsePolicy.CAPSULE_DP).toFloat()
        val gap = dp(dev.amenhancer.glass.GlassBarCollapsePolicy.GAP_DP).toFloat()
        // The detached search control only exists when the menu actually yields a search tab;
        // without it the mini runs to the row's end edge instead of leaving a hole.
        val endReserve = if (hasDetachedSearch) {
            dp(dev.amenhancer.glass.GlassBarCollapsePolicy.SEARCH_DP).toFloat() + gap
        } else 0f
        // The mini's own amplitude in BOTH directions. It has one slider rather than the buttons'
        // two because its travel is ~6x shorter, so its collapse and expand overshoots are both
        // sub-2dp and a user cannot meaningfully want them different.
        val scale = miniReboundPercent / 100f
        val fraction = dev.amenhancer.glass.GlassBarCollapsePolicy.sprungFraction(p, scale, scale)
        val left = dev.amenhancer.glass.GlassBarCollapsePolicy.miniStartMargin(
            expanded[0].toFloat(), capsule, gap, fraction,
        )
        val right = dev.amenhancer.glass.GlassBarCollapsePolicy.miniEndMargin(
            expanded[1].toFloat(), endReserve, fraction,
        )
        return intArrayOf(
            left.roundToInt().coerceAtLeast(0),
            right.roundToInt().coerceAtLeast(0),
        )
    }

    /**
     * True only when the current menu offers a detachable search tab, i.e. search is the last
     * tab and detaching it still leaves a real tab strip behind. Mirrors
     * [NavigationSnapshot.searchTab] for the legacy menu path, which builds its own tab list.
     */
    private val hasDetachedSearch: Boolean
        get() = tabs.size > 2 && tabs.last().isSearch

    override fun peekHeight(): Int =
        GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry,
            collapse = collapse.value) + if (miniVisible) dimen(ChromeResource.SHADOW_HEIGHT) else 0

    override fun observeNativePeek(height: Int) = nativePeek.observe(height)

    private fun writePeek(height: Int) = nativePeek.writeByModule {
        playerBehavior?.let { hostBinding.writePeek(it, height) }
    }

    private fun updateUnderlap(): Boolean {
        val root = source ?: return false
        var sourceNeedsLayout = false
        // 1586 applies the navigation-bar inset as a margin on this source, even
        // in an edge-to-edge window. Extend the scene, not the controls, beneath
        // the gesture area. Leave larger margins (e.g. IME avoidance) untouched.
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (bottomInset > 0 && params.bottomMargin == bottomInset) {
                save(root)
                params.bottomMargin = 0
                root.layoutParams = params
                sourceNeedsLayout = true
            }
        }
        if (scanNeeded) {
            scanNeeded = false
            val entries = states.entries.iterator()
            while (entries.hasNext()) {
                val (view, state) = entries.next()
                if (state.scrollPadding && !isDescendant(view, root)) {
                    state.restoreScroll(view)
                    entries.remove()
                }
            }
            val candidates = descendants(root).filter { view ->
            view.isShown && view.height >= root.height / 2 && view.height > 0 && !isViewPagerPageHost(view) && hostBinding.isScrollContainer(view)
            }.toList()
            scrollTargets = candidates.filter { child ->
                generateSequence(child.parent) { it.parent }.takeWhile { it !== root }.none { parent -> candidates.any { it === parent } }
            }
            // Compose pages expose no scroll container at all, which is why the gesture path
            // exists; naming the classes that *were* found makes that distinction visible on a
            // device instead of inferred from the source.
            GlassCollapseDiagnostics.event(
                "scroll targets=${scrollTargets.size}" +
                    scrollTargets.joinToString(prefix = " [", postfix = "]", separator = ", ") { it.javaClass.simpleName },
            )
        }
        val terminal = scrollTargets
        // A view that stopped being a target (for example the pager RecyclerView we no
        // longer pad) keeps its old padding until we release it here.
        states.entries.forEach { (view, state) ->
            if (state.scrollPaddingActive && terminal.none { it === view }) {
                state.restoreScroll(view)
                state.scrollPaddingActive = false
            }
        }
        // Library / New / parts of Search are Compose scenes in 1586. They do
        // not expose RecyclerView children. Padding their View viewport removes
        // the very pixels the backdrop needs; their own content owns scrolling.
        val composeScene = descendants(root).any { view ->
            view.isShown && view.height > 0 && hostBinding.isComposeScene(view)
        }
        val occupied = if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry, collapse = collapse.value) else 0
        if (terminal.isEmpty() && !composeScene) {
            underlap = false
            if (root.paddingBottom != occupied) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, occupied)
            return sourceNeedsLayout
        }
        underlap = true
        if (root.paddingBottom != 0) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
        root.clipToPadding = false
        terminal.forEach { view ->
            val initial = save(view).also { it.scrollPadding = true; it.scrollPaddingActive = true }
            val desired = initial.bottomPadding + occupied
            if (view.paddingBottom != desired) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, desired)
            (view as? ViewGroup)?.clipToPadding = false
        }
        return sourceNeedsLayout
    }

    private fun updateTransition(): Boolean {
        suppressNativeChromeSeams()
        navFrame?.background = null
        // Keep Z ordering (also used for touch dispatch); remove only the old
        // rectangular shadow outline, not the navigation view's elevation.
        navFrame?.let { if (it.outlineProvider != null) it.outlineProvider = null }
        miniRoot?.background = null
        val progress = slide.coerceIn(0f, 1f)
        driveNavFrameExit(progress)
        fun blend(start: Float, end: Float): Float {
            val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        val materialProgress = blend(0f, 0.35f)
        glassExpansion = materialProgress
        val miniAlpha = if (!miniVisible && progress == 0f) 0f else 1f - blend(0.35f, 0.6f)
        val hideTransparentMiniGlass = miniAlpha <= 0f
        var revivedMiniGlass = false
        miniGlass?.let { glass ->
            if (!hideTransparentMiniGlass) {
                val sheet = glass.parent as? FrameLayout
                if (sheet != null && sheet !== miniRoot) {
                    if (miniVisible && progress == 0f) {
                        val miniPosition = IntArray(2).also { miniRoot?.getLocationInWindow(it) }
                        val sheetPosition = IntArray(2).also(sheet::getLocationInWindow)
                        miniOffsetInSheet = miniPosition[1] - sheetPosition[1]
                    }
                    // Keep the tablet's original capsule anchor, but stop the
                    // horizontal morph at 80% of the sheet width. This reduces
                    // the fading surface area without changing its timing.
                    val sideInset = if (geometry.sideBySide) sheet.width * 0.10f * materialProgress else 0f
                    // The cached mini slot already carries the scroll collapse, so the
                    // material follows the native content into the collapsed row. Both are
                    // multiplied by the player's own morph so opening the player returns the
                    // row to its expanded geometry smoothly.
                    val left = (miniMarginPx[0] * (1f - materialProgress) + sideInset).roundToInt()
                    val right = (miniMarginPx[1] * (1f - materialProgress) + sideInset).roundToInt()
                    // The centring drop is NOT added here. `miniOffsetInSheet` is sampled from the
                    // native mini's live window position, which already includes the drop this
                    // session writes through `syncMiniPresentation`; adding it again would sink
                    // the glass by twice the centring distance, which is exactly the difference
                    // between centring on the row and sitting on its bottom edge. The native
                    // content and the material over it therefore move as one because they are
                    // driven by the one translation the native root owns.
                    val top = (miniOffsetInSheet * (1f - materialProgress)).roundToInt()
                    val collapsedHeight = dp(geometry.miniHeightDp)
                    val height = (collapsedHeight + (sheet.height - collapsedHeight) * progress).roundToInt().coerceAtLeast(collapsedHeight)
                    val params = glass.layoutParams as FrameLayout.LayoutParams
                    if (params.height != height || params.topMargin != top || params.leftMargin != left || params.rightMargin != right) {
                        params.height = height; params.topMargin = top
                        params.leftMargin = left; params.rightMargin = right
                        glass.layoutParams = params
                    }
                }
            }
            if (glass.alpha != miniAlpha) glass.alpha = miniAlpha
            val visibility = if (hideTransparentMiniGlass) View.GONE else View.VISIBLE
            if (glass.visibility != visibility) {
                revivedMiniGlass = visibility == View.VISIBLE
                glass.visibility = visibility
            }
        }
        find(ChromeResource.PLAYER_SHEET_CONTAINER)?.let { v ->
            val original = save(v)
            val desired = if (progress == 0f) null else original.outlineProvider
            if (v.outlineProvider !== desired) v.outlineProvider = desired
        }
        listOf(ChromeResource.PLAYER_TOP_SHADOW, ChromeResource.BACKGROUND_LAYERS).mapNotNull(::find).forEach { v ->
            applyLayerAlpha(v, materialProgress)
        }
        find(ChromeResource.PLAYER_FRAGMENTS_HOST)?.let { v ->
            applyLayerAlpha(v, playerFragmentsAlphaFactor(progress, materialProgress))
        }
        // The motion subtree includes rectangular legibility/blur overlays and can
        // still have thumbnail-sized bounds early in the native transition. Reveal
        // it only after the glass has faded and the opaque player background is back.
        find(ChromeResource.MOTION_SWITCHER)?.let { v ->
            applyLayerAlpha(v, blend(0.6f, 0.85f))
        }
        find(ChromeResource.PLAYER_ROOT)?.background = if (materialProgress < 1f) null else states[find(ChromeResource.PLAYER_ROOT)]?.background
        // The flat tablet row is parked offscreen at 60%; the stacked phone row
        // moves under native control, so test its actual window bounds instead.
        val consumerVisible = !(geometry.sideBySide && progress >= 0.6f) &&
            ((revivedMiniGlass && miniAlpha > 0f) ||
                backdropConsumerVisible(navGlass) || backdropConsumerVisible(navScrim) ||
                backdropConsumerVisible(miniGlass))
        glassConsumersVisible = consumerVisible
        // On the way back, capture the source while native player content still
        // covers the screen. The mini glass stays GONE until the original 60% boundary.
        val prewarmCapture = !consumerVisible && returningToMini && progress in 0.6f..0.85f
        val captureResumed = backdrop?.setCaptureEnabled(consumerVisible || prewarmCapture) == true
        return revivedMiniGlass || (consumerVisible && captureResumed)
    }

    private fun backdropConsumerVisible(view: View?): Boolean {
        if (view == null || !view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return false
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.alpha <= 0f) return false
            ancestor = ancestor.parent as? View
        }
        if (!view.getGlobalVisibleRect(visibleGlassRect)) return false
        // clipChildren=false can report an offscreen row as globally visible.
        // Its translated screen bounds must still intersect this window.
        view.getLocationOnScreen(visibleGlassLocation)
        val window = activity.window.decorView
        window.getLocationOnScreen(windowLocation)
        return visibleGlassLocation[0] < windowLocation[0] + window.width &&
            visibleGlassLocation[0] + view.width > windowLocation[0] &&
            visibleGlassLocation[1] < windowLocation[1] + window.height &&
            visibleGlassLocation[1] + view.height > windowLocation[1]
    }

    private fun canRefreshBackdrop(): Boolean = source?.let {
        it.isAttachedToWindow && it.width > 0 && it.height > 0
    } == true

    override fun onSlide(progress: Float) {
        val next = progress.coerceIn(0f, 1f)
        if (next < slide - 0.001f) returningToMini = true
        else if (next > slide + 0.001f) returningToMini = false
        slide = next
    }

    override fun redirectedLayerAlpha(view: Any?, alpha: Float): Float? {
        if (closed || writingLayerAlpha) return null
        return layerAlphas[view]?.hostWrite(alpha)
    }

    private fun applyLayerAlpha(view: View, factor: Float) {
        save(view)
        val state = layerAlphas.getOrPut(view) { NativeLayerAlpha(view.alpha) }
        state.factor = factor
        if (view.alpha != state.effective) {
            writingLayerAlpha = true
            try { view.alpha = state.effective } finally { writingLayerAlpha = false }
        }
    }

    override fun redirectedPadding(view: Any?): Int? = if (activated && view === source) {
        if (underlap) 0 else if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry, collapse = collapse.value) else 0
    } else null

    // Only the tablet form narrows the full-width host touch surfaces.
    override fun shouldPassThroughTouch(view: View, event: MotionEvent): Boolean = false

    override fun shouldBypassPlayerIntercept(event: MotionEvent): Boolean = false

    /**
     * The collapsed row's own touch owner.
     *
     * The phone form claims **nothing** here. The collapsed mini player is a complete mini player:
     * its play/pause and skip buttons and its tap-to-open-player all belong to the host, and the
     * module draws the glass *under* that native content rather than replacing it. Consuming the
     * gesture at this seam is what made those working controls dead — the row looked interactive
     * and every tap was swallowed into one action.
     *
     * The tablet keeps its own redirect: its two native touch owners are full-width and the
     * rendered capsules are what the user is actually aiming at, so that form has to re-target.
     */
    override fun dispatchCollapsedMiniTouch(view: View, event: MotionEvent): Boolean? = null

    /**
     * Whether [event] lands on the **mini player**, in whichever state the bar is in.
     *
     * The collapsed row is three separate controls: the withdrawn capsule, the mini player, and the
     * detached search control. The capsule and the search control are Compose-drawn in the module's
     * own row and handle their own taps, so this region has to stop at the mini player's edges or it
     * swallows them and all three controls collapse into one action.
     *
     * Expanded, the mini player is the only thing in its band, so the test is simply its own bounds
     * and no narrowing applies. Collapsed, only the two **narrowed** surfaces are tested — the glass
     * island and the native content, both laid out to the collapsed mini slot. The native mini
     * *root* is deliberately excluded: it keeps its expanded, full-row width (only its height and its
     * content are narrowed), so testing it made every tap anywhere on the row — the capsule and the
     * search control included — count as "on the mini player".
     */
    private fun hitsMiniPlayer(event: MotionEvent): Boolean {
        val targets = if (collapse.value < COLLAPSED_TOUCH_MIN_PROGRESS) {
            listOfNotNull(miniContent, miniRoot)
        } else {
            listOfNotNull(miniGlass, miniContent)
        }
        val location = IntArray(2)
        return targets.any { view ->
            if (!view.isShown || view.width <= 0 || view.height <= 0) return@any false
            view.getLocationOnScreen(location)
            event.rawX >= location[0] && event.rawX < location[0] + view.width &&
                event.rawY >= location[1] && event.rawY < location[1] + view.height
        }
    }

    /**
     * Traces a tap on the collapsed row so the two possible failures can be told apart on device.
     *
     * "Tapping the mini does nothing" has two very different causes and the log must separate them:
     *
     * - `row tap -> mini` means the module's own row recognised the tap as landing on the mini
     *   player, so if the mini still does nothing the event was taken before it could reach the
     *   native view — the overlay is the culprit.
     * - `row tap -> outside` means the module does **not** even think the tap was on the mini, which
     *   points at the geometry instead: the mini's slot, the overlay's band, or the hit test.
     *
     * It also prints the band the overlay occupies and the mini's own rect, so the two can be
     * compared directly rather than inferred. Called on the down event only, so a gesture costs one
     * line, and rate-limited by [GlassCollapseDiagnostics] like every other trace here.
     */
    private fun traceRowTap(event: MotionEvent) {
        val location = IntArray(2)
        val rects = listOfNotNull(miniGlass, miniContent).joinToString(" ") { view ->
            view.getLocationOnScreen(location)
            "${location[0]},${location[1]} ${view.width}x${view.height}"
        }
        navFrame?.let { frame ->
            frame.getLocationOnScreen(location)
            GlassCollapseDiagnostics.log(
                "row tap -> ${if (hitsMiniPlayer(event)) "mini" else "outside"}" +
                    " at ${event.rawX.toInt()},${event.rawY.toInt()}" +
                    " p=${collapse.value}" +
                    " overlay=${location[0]},${location[1]} ${frame.width}x${frame.height}" +
                    " mini=[$rects]",
            )
        }
    }

    /**
     * Reports whether [event] belongs to a gesture that began on the mini player.
     *
     * **Nothing is consumed.** The mini player is a complete mini player in both states: its
     * play/pause and skip buttons and its tap-to-open-player are the host's, and the module
     * deliberately draws the glass *under* that native content instead of replacing it. Earlier
     * rounds consumed the event here, which is exactly what left those working controls dead.
     *
     * The module needs the gesture's *origin* for two reasons, and both apply in **every** state:
     *
     * 1. The content root observes every page gesture for the scroll collapse, and a finger busy
     *    with the mini must not withdraw the bar underneath itself.
     * 2. Dragging the mini player **upward** opens the full player, and that drag is a long upward
     *    travel on the same seam. Fed to the collapse tracker it reads as "the page scrolled down"
     *    and collapses the bar — which is why dragging the mini open and coming back left the bar
     *    collapsed, while *tapping* it open (too short to clear the slop) did not. The origin is
     *    therefore recorded regardless of the current collapse value, not only once collapsed.
     *
     * So this records the origin and reports it, and [observeContentTouch] stands its collapse
     * tracker down for the whole gesture — including the lift, which is why the ending event
     * reports `true` before clearing the flag.
     */
    protected fun collapsedMiniTouch(event: MotionEvent): Boolean {
        if (!activated || closed || !collapseEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                traceRowTap(event)
                if (!hitsMiniPlayer(event)) {
                    collapsedMiniGesture.reset()
                } else {
                    collapsedMiniGesture.down(event.rawX, event.rawY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Reported before clearing: the lift still belongs to the mini's gesture, and
                // letting it through would let the drag's velocity decide the bar's direction.
                val owned = collapsedMiniGesture.owning
                collapsedMiniGesture.reset()
                return owned
            }
            else -> Unit
        }
        return collapsedMiniGesture.owning
    }

    private fun miniGlassPosition(event: MotionEvent): Pair<Float, Float>? {
        val glass = miniGlass ?: return null
        if (glass.width <= 0 || glass.height <= 0) return null
        val location = IntArray(2)
        glass.getLocationOnScreen(location)
        return (
            (event.rawX - location[0]).coerceIn(0f, glass.width.toFloat()) to
                (event.rawY - location[1]).coerceIn(0f, glass.height.toFloat())
            )
    }

    /**
     * Content-root gesture observation, the collapse signal source for Compose pages.
     *
     * Observation only: the event is never consumed, never delayed and never rewritten, so native
     * dispatch, every host control and the player drag behave exactly as they did. The decision
     * comes from finger travel alone, which is why this path works on a Compose scene where no
     * native scroll container exists to watch.
     *
     * The content root is an ancestor of the mini player, so it sees the mini's gestures first.
     * They are *not* consumed — the collapsed mini is a complete mini player and its controls must
     * work — but they must not drive the collapse either: a finger busy with the mini would
     * otherwise withdraw the bar underneath itself. The gesture is therefore excluded from the
     * collapse signal while still being delivered to the host untouched.
     *
     * A pointer count above one is ignored entirely: a pinch or a two-finger gesture is not a
     * scroll, and letting it drive the bar would move the bottom chrome while the user zooms.
     */
    open fun observeContentTouch(event: MotionEvent): Boolean? {
        if (!activated || closed) return null
        // `collapseEnabled` is the single gate, and it already folds in the tablet decision: it is
        // only true on a side-by-side row when the user has turned the **tablet** switch on. Gating
        // on the raw geometry instead would make that switch dead code — the collapse would be
        // forbidden on a tablet no matter what the user chose.
        if (!collapseEnabled) return null
        // Excluded, not claimed: the return value stays null so the host still receives the event.
        if (collapsedMiniTouch(event)) { collapseTouch.reset(); return null }
        if (event.pointerCount > 1) { collapseTouch.reset(); return null }
        if (!collapseActive) { collapseTouch.reset(); return null }
        val decision = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { collapseTouch.down(event.rawX, event.rawY, event.eventTime); null }
            MotionEvent.ACTION_MOVE -> collapseTouch.move(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                collapseTouch.up(event.rawX, event.rawY, event.eventTime)
            else -> null
        }
        if (decision != null) {
            GlassCollapseDiagnostics.event("touch source -> ${if (decision) "collapse" else "expand"}")
            requestCollapse(decision)
        }
        return null
    }

    override fun observeTouch(event: MotionEvent) {
        if (!activated) return
        val position = miniGlassPosition(event) ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { contentDownX = event.x; contentDownY = event.y; observingPress = true }
            MotionEvent.ACTION_MOVE -> if (abs(event.y - contentDownY) > ViewConfiguration.get(activity).scaledTouchSlop && abs(event.y - contentDownY) > abs(event.x - contentDownX)) {
                if (observingPress) input.event(MotionEvent.ACTION_CANCEL, position.first, position.second)
                observingPress = false
            }
        }
        if (observingPress) input.event(event.actionMasked, position.first, position.second)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) observingPress = false
    }

    override fun foreground(active: Boolean) {
        if (!active) captureWait.timedOut(0L, false)
        navGlass?.foreground(active); navScrim?.foreground(active); miniGlass?.foreground(active)
    }

    protected open fun findPlayerBehavior(): Any? = hostBinding.playerBehavior(false)

    private fun scheduleFailure(error: Throwable) {
        if (failureScheduled || closed) return
        failureScheduled = true
        releaseGlassOwnership(hostRoot)
        activity.window.decorView.post { failure(error) }
    }

    override fun close() {
        if (closed) return
        closed = true
        activated = false
        playerSheet = null
        attachHandler.removeCallbacks(retryAttach)
        retryPending = false
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observer?.takeIf { it.isAlive }?.removeOnScrollChangedListener(collapseScrollListener)
        collapseScope.cancel()
        collapseScrollOffsets.clear()
        backdrop?.close()
        listOfNotNull(navGlass, navScrim, miniGlass).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        states.forEach { (view, state) -> state.restore(view) }
        states.clear()
        overflowClips.close()
        transformOwners.values.forEach { it.values.forEach(AutoCloseable::close) }
        transformOwners.clear()
        layerAlphas.forEach { (view, state) -> view.alpha = state.native }
        layerAlphas.clear()
        nativePeek.latest?.let { runCatching { writePeek(it) } }
        releaseGlassOwnership(hostRoot)
        hostBinding.close()
        activity.window.decorView.requestLayout()
    }

    private fun descendants(root: ViewGroup): Sequence<View> = sequence {
        for (i in 0 until root.childCount) { val view = root.getChildAt(i); yield(view); if (view is ViewGroup) yieldAll(descendants(view)) }
    }

    private fun isDescendant(child: View, parent: View) = generateSequence(child.parent) { it.parent }.any { it === parent }

    /** ViewPager2 hosts its pages in an internal RecyclerView. Padding that RecyclerView
     * shrinks every page instead of adding scroll space, so the page content stops above
     * the glass and the bar samples empty background. Pad the lists inside the pages. */
    private fun isViewPagerPageHost(view: View): Boolean = hostBinding.isPagerPageHost(view)

    private companion object {
        /**
         * Scroll distance, in dp, the user must commit to before the bar flips. Small enough
         * that a deliberate flick registers immediately, large enough that a jitter or a
         * one-pixel bounce at the end of a list does not move the bar.
         */
        const val COLLAPSE_SCROLL_THRESHOLD_DP = 6f

        /**
         * Collapse progress at which the mini player starts owning its own touches.
         *
         * Set near the end of the travel so the region does not become a tap target while the row
         * is still visibly a moving target: a tap landing mid-collapse would expand a bar the user
         * never finished collapsing. Above this the row is settled and the mini's bounds are its
         * final ones.
         */
        const val COLLAPSED_TOUCH_MIN_PROGRESS = 0.9f
    }


    private class NativeViewState(view: View) {
        private val currentValues = HashMap<String,Any?>(24)
        private val ownership = dev.amenhancer.module.host.OwnedHostState(snapshot(view))
        val background get() = ownership.nativeValue("background") as? android.graphics.drawable.Drawable
        val bottomPadding get() = ownership.nativeValue("paddingBottom") as Int
        val outlineProvider get() = ownership.nativeValue("outline") as? android.view.ViewOutlineProvider
        var scrollPadding = false
        var scrollPaddingActive = false
        fun observeNative(view: View) = ownership.observeNative(snapshot(view))
        fun captureOwned(view: View) = ownership.captureOwned(snapshot(view))
        fun restoreInteraction(view: View) {
            view.visibility = ownership.nativeValue("visibility") as Int
            view.importantForAccessibility = ownership.nativeValue("accessibility") as Int
        }
        fun restoreScroll(view: View) {
            val restored=ownership.restoreValues(snapshot(view))
            view.setPadding(view.paddingLeft,view.paddingTop,view.paddingRight,restored.getValue("paddingBottom") as Int)
            if (view is ViewGroup) view.clipToPadding=restored.getValue("clipPadding") as Boolean
        }
        fun restore(view: View) {
            val values=ownership.restoreValues(snapshot(view))
            view.background=values["background"] as? android.graphics.drawable.Drawable
            view.alpha=values.getValue("alpha") as Float
            view.visibility=values.getValue("visibility") as Int
            view.importantForAccessibility=values.getValue("accessibility") as Int
            view.outlineProvider=values["outline"] as? android.view.ViewOutlineProvider
            val params=view.layoutParams
            params.width=values.getValue("width") as Int; params.height=values.getValue("height") as Int
            if (params is ViewGroup.MarginLayoutParams) params.setMargins(values.getValue("leftMargin") as Int,
                values.getValue("topMargin") as Int,values.getValue("rightMargin") as Int,values.getValue("bottomMargin") as Int)
            view.layoutParams=params
            view.setPadding(values.getValue("paddingLeft") as Int,values.getValue("paddingTop") as Int,
                values.getValue("paddingRight") as Int,values.getValue("paddingBottom") as Int)
            if (view is ViewGroup) { view.clipChildren=values.getValue("clipChildren") as Boolean;view.clipToPadding=values.getValue("clipPadding") as Boolean }
        }
        private fun snapshot(view: View): Map<String,Any?> = currentValues.apply {
            put("background",view.background);put("alpha",view.alpha);put("visibility",view.visibility)
            put("accessibility",view.importantForAccessibility);put("outline",view.outlineProvider)
            put("width",view.layoutParams.width);put("height",view.layoutParams.height)
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                put("leftMargin",it.leftMargin);put("topMargin",it.topMargin);put("rightMargin",it.rightMargin);put("bottomMargin",it.bottomMargin)
            }
            put("paddingLeft",view.paddingLeft);put("paddingTop",view.paddingTop);put("paddingRight",view.paddingRight);put("paddingBottom",view.paddingBottom)
            (view as? ViewGroup)?.let { put("clipChildren",it.clipChildren);put("clipPadding",it.clipToPadding) }
        }
    }
}
