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
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.GlassMiniPlayer
import dev.amenhancer.glass.GlassMiniPlayerCommand
import dev.amenhancer.glass.GlassMiniPlayerState
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.glass.GlassRepeatMode
import dev.amenhancer.glass.GlassTopNavigation
import dev.amenhancer.glass.GlassTopTab
import dev.amenhancer.glass.GlassTopTabDisplay
import dev.amenhancer.glass.TabletGlassGestureGate
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TabletChromeStyle
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings

/**
 * iPad-style tablet chrome: [GlassTopNavigation] renders the navigation as one capsule at the
 * window's top centre and [GlassMiniPlayer] renders the transport as one capsule at the bottom
 * centre. The host's native bottom tab strip and the author's full-width bottom mini glass are
 * parked, while the inherited [PhoneGlassSession] pipeline keeps owning the player transition and
 * the peek handling.
 *
 * The session owns only the two capsules, the reservation of the top space they occupy and touch
 * ownership of their bands. Every native write goes through the inherited compare-then-write
 * seams: the tabs frame, the native mini root/content and the author's mini glass are hidden with
 * `hideSeam` (a [PhoneGlassSession] `NativeViewState` snapshot) and the content root's original top
 * padding is snapshotted here, so [close] restores all of them exactly.
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
    }

    /**
     * The iPad-style chrome is its own feature, so a successful mount reports there rather than
     * under the phone glass toggle that merely gates it.
     */
    override val glassFeatureKey: String get() = ModuleConstants.FEATURE_TABLET_CHROME

    override val glassActiveMessage: String
        get() = "iPad 风格界面已挂载：顶部导航胶囊已接管平板导航，原生底栏已隐藏；真机视觉验收另行记录"

    /** Screen-space rectangle of the rendered capsule; written on Compose layout, read on touch. */
    private class CapsuleFrame {
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        val ready: Boolean get() = right > left && bottom > top
    }

    private val topTouchGate = TabletGlassGestureGate()
    private val topCapsuleFrame = CapsuleFrame()
    private val topLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { topLayoutDirty = true }

    private var topClosed = false
    private var topFailureScheduled = false
    private var topLayoutDirty = true
    private var topRefreshAt = 0L
    private var topGlass: GlassHostView? = null
    private var topBackdrop: ViewBackdrop? = null
    private var topBackdropCapture = false
    private var topObserver: ViewTreeObserver? = null
    private var contentRoot: ViewGroup? = null
    private var navView: View? = null
    private var topPaddingOriginal: Int? = null
    private var topBarHeightPx = 0
    private var topBarMarginPx = 0
    private var topContentPaddingPx = 0
    private var topPanelBlurDp = GlassPolicy.PANEL_BLUR_DP.toInt()

    private var topTabs by mutableStateOf(emptyList<GlassTopTab>())
    private var topSelectedId by mutableIntStateOf(View.NO_ID)
    private var topAccent by mutableStateOf(Color.Red)
    private var topForeground by mutableStateOf(Color.Black)
    private var topHostConfiguration by mutableStateOf(Configuration(activity.resources.configuration))
    private var topMenuKey: List<Any?> = emptyList()

    // Bottom iPad-style mini-player capsule. It shares the top capsule's backdrop capture (the
    // base already renders three consumers from one [ViewBackdrop]) and owns no reflection cache
    // beyond the resolved artwork source, so a layout pass never scans the hierarchy.
    private val miniTouchGate = TabletGlassGestureGate()
    private val miniCapsuleFrame = CapsuleFrame()
    private var miniCapsule: GlassHostView? = null
    private var miniCapsuleHeightPx = 0
    private var miniListener: AutoCloseable? = null
    private var miniListenerBound = false
    private var miniSuppressedRoot: View? = null
    private var miniArtworkSource: View? = null

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

    /**
     * Only the two rendered capsules own their DOWNs. Each gate is scoped to this session's own
     * band: a DOWN outside the capsule makes that view report "not handled", so the event falls
     * through to whatever is underneath (the host page for the top band, the host sheet for the
     * mini band). Every other view keeps the inherited phone behaviour.
     */
    override fun shouldPassThroughTouch(view: View, event: MotionEvent): Boolean {
        if (view === topGlass) return passesThroughTopBand(event)
        if (view === miniCapsule) return passesThroughMiniBand(event)
        return false
    }

    override fun foreground(active: Boolean) {
        super.foreground(active)
        topGlass?.foreground(active)
        miniCapsule?.foreground(active)
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
        super.close()
        // PhoneGlassSession restored the content root from the NativeViewState snapshot it took
        // after this session had already reserved the top space. Undo that snapshot's top padding
        // once the base restore has run, so no reserved space survives the session.
        restoreTopContentPadding()
    }

    private fun attachTopChrome() {
        if (topGlass != null || topClosed) return
        if (navFrame == null || hostRoot == null) return
        // The inherited nav surface already carries the isolated module Context (module
        // Resources/theme/class loader). Reuse it instead of duplicating moduleContext().
        val glassContext = navGlass?.context ?: return
        val content = find("navigation_host_group") as? ViewGroup ?: return
        val navigation = find("bottom_navigation") ?: return
        val navHeight = dimen("navigation_tabs_height")
        if (navHeight <= 0) return
        val parent = topMountParent(content) ?: return

        contentRoot = content
        navView = navigation
        topPanelBlurDp = ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(
            config.settings().phoneLiquidGlassPanelBlurDp,
        )
        refreshTopMetrics()
        topHostConfiguration = Configuration(activity.resources.configuration)
        refreshTopMenu()

        val backdrop = ViewBackdrop(content, ::scheduleTopFailure).also {
            topBackdrop = it
            it.start()
            // Nothing consumes the top capsule before activation, so do not record alongside the
            // inherited backdrop; updateTopChrome() enables capture when the capsule is revealed.
            it.setCaptureEnabled(false)
            topBackdropCapture = false
        }
        val glass = GlassHostView(glassContext).also { topGlass = it }
        glass.alpha = 0f
        glass.visibility = View.GONE
        val panelHeight = (navHeight / density).dp
        glass.content {
            TopHostConfiguration {
                // GlassHostView fills its Box, so the wrap-content capsule is centred here; the
                // host's own gravity only decides where the full-width band sits.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    GlassTopNavigation(
                        tabs = topTabs,
                        selectedId = topSelectedId,
                        accent = topAccent,
                        foreground = topForeground,
                        backdrop = backdrop,
                        onSelect = ::selectTopTab,
                        panelHeight = panelHeight,
                        panelBlur = topPanelBlurDp.dp,
                        modifier = Modifier.onGloballyPositioned(::recordTopCapsuleFrame),
                    )
                }
            }
        }
        parent.addView(
            glass,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                navHeight,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = topBarMarginPx },
        )
        topObserver = activity.window.decorView.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(topLayoutListener)
        }
        applyTopContentPadding()
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

    private fun passesThroughTopBand(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            return topTouchGate.start(event.downTime, hitCapsule = !activated || capsuleHit(event))
        }
        // Keep the owner chosen by the DOWN for the whole gesture.
        return topTouchGate.isPassedThrough(event.downTime)
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

    private fun passesThroughMiniBand(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            return miniTouchGate.start(event.downTime, hitCapsule = !activated || miniCapsuleHit(event))
        }
        // Keep the owner chosen by the DOWN for the whole gesture.
        return miniTouchGate.isPassedThrough(event.downTime)
    }

    private fun miniCapsuleHit(event: MotionEvent): Boolean {
        val glass = miniCapsule ?: return false
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
        val glass = miniCapsule ?: return
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
     * Mounts the iPad-style bottom capsule exactly where the base mounts its own mini glass: into
     * `player_sheet_container` at index 0 with `Gravity.TOP`, so the host's peek/slide/expand
     * behaviour keeps moving it. The host view spans exactly the capsule, so a touch outside it is
     * never dispatched to it in the first place and the gesture gate only guards the bleed margin.
     *
     * The native mini root is the fallback mount parent on hosts without a sheet container; there
     * the replacement capsule would hide itself, so this session leaves the host chrome alone
     * instead of blanking the band.
     */
    private fun attachMiniCapsule() {
        if (topClosed) return
        val sheet = playerSheet as? ViewGroup ?: return
        if (sheet === miniRoot) return
        if (miniCapsule?.parent === sheet) return
        val height = dimen("miniplayer_height")
        if (height <= 0) return
        val backdrop = topBackdrop ?: return
        // Reuse the inherited nav surface's isolated module Context, never the host's.
        val glassContext = navGlass?.context ?: return
        releaseMiniCapsule()
        miniCapsuleHeightPx = height
        val glass = GlassHostView(glassContext).also { miniCapsule = it }
        glass.alpha = 0f
        glass.visibility = View.GONE
        glass.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Populate the Compose states before the composition is first committed.
        refreshMiniState()
        refreshMiniCover()
        glass.content {
            TopHostConfiguration {
                GlassMiniPlayer(
                    state = miniState,
                    backdrop = backdrop,
                    accent = topAccent,
                    foreground = topForeground,
                    onCommand = ::handleMiniCommand,
                    cover = { MiniCoverArtwork() },
                    title = miniTitle,
                    artist = miniArtist,
                    panelHeight = (height / density).dp,
                    panelBlur = topPanelBlurDp.dp,
                    modifier = Modifier.onGloballyPositioned(::recordMiniCapsuleFrame),
                )
            }
        }
        val screenWidth = capsuleScreenWidthPx(sheet)
        sheet.addView(
            glass,
            0,
            FrameLayout.LayoutParams(
                TabletChromeLayoutPolicy.miniPlayerWidthPx(screenWidth),
                height,
                Gravity.TOP,
            ).apply {
                leftMargin = TabletChromeLayoutPolicy.miniPlayerLeftPx(screenWidth)
            },
        )
        suppressNativeMiniChrome()
    }

    private fun releaseMiniCapsule() {
        miniListener?.let { handle -> runCatching { handle.close() } }
        miniListener = null
        miniListenerBound = false
        miniCapsule?.let { glass -> (glass.parent as? ViewGroup)?.removeView(glass) }
        miniCapsule = null
        miniCapsuleHeightPx = 0
        miniSuppressedRoot = null
        miniArtworkSource = null
    }

    /**
     * Compare-then-write for the capsule only: size, seat, alpha and visibility. The capsule is
     * visible exactly while the shell is active and the shared backdrop is ready, mirroring the top
     * capsule; no layout parameter is written unless it actually changed.
     */
    private fun updateMiniChrome() {
        val glass = miniCapsule ?: return
        if (miniCapsuleHeightPx <= 0) return
        val parent = glass.parent as? ViewGroup ?: return
        val screenWidth = capsuleScreenWidthPx(parent)
        val width = TabletChromeLayoutPolicy.miniPlayerWidthPx(screenWidth)
        val left = TabletChromeLayoutPolicy.miniPlayerLeftPx(screenWidth)
        val top = TabletChromeLayoutPolicy.miniPlayerTopPx()
        val params = glass.layoutParams as? FrameLayout.LayoutParams
        if (params != null &&
            (params.width != width ||
                params.height != miniCapsuleHeightPx ||
                params.leftMargin != left ||
                params.topMargin != top)
        ) {
            params.width = width
            params.height = miniCapsuleHeightPx
            params.leftMargin = left
            params.topMargin = top
            glass.layoutParams = params
        }
        val ready = activated && topBackdrop?.ready == true
        val alpha = if (ready) 1f else 0f
        if (glass.alpha != alpha) glass.alpha = alpha
        val visibility = if (ready) View.VISIBLE else View.GONE
        if (glass.visibility != visibility) glass.visibility = visibility
        suppressNativeMiniChrome()
    }

    private fun capsuleScreenWidthPx(parent: View): Int =
        parent.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels

    /**
     * Suppresses the author's bottom chrome for this session only.
     *
     * [hideSeam] snapshots visibility/alpha through the inherited `NativeViewState`, so
     * [PhoneGlassSession.close] restores every one of these views exactly.
     *
     * Parking the native mini root is what makes the base park its own mini glass without a
     * per-frame fight: `PhoneGlassSession.miniVisible` is `miniRoot.isShown`, so a hidden root
     * drives the base's own `miniAlpha` to zero while the sheet is collapsed. The explicit
     * `miniGlass` snapshots record the author's surface as hidden rather than leaving it to chance.
     */
    private fun suppressNativeMiniChrome() {
        val root = miniRoot ?: return
        if (root === playerSheet) return
        if (miniSuppressedRoot !== root) {
            miniSuppressedRoot = root
            hideSeam(find("mini_player_content"))
            hideSeam(miniGlass)
        }
        hideSeam(root)
    }

    /** Draws the host's already-loaded artwork, never its own. Nothing is drawn while it is null. */
    @Composable
    private fun MiniCoverArtwork() {
        val source = miniCover ?: return
        // Clone before setting bounds: the host keeps ownership of its own Drawable.
        val artwork = remember(source) {
            runCatching { source.constantState?.newDrawable() }.getOrNull()
        } ?: return
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
     * Binds the host controller listener once, and only after the adapter actually has a live
     * controller; before that it would hand back a no-op handle that would never fire.
     */
    private fun registerMiniListener(commands: TabletChromeCommands?) {
        if (miniListenerBound || miniCapsule == null) return
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
     * The artwork the host already loaded into its native mini player. The source view is cached
     * and only re-resolved when it detaches, so a refresh never scans the hierarchy per frame.
     */
    private fun nativeMiniArtwork(): Drawable? {
        // Prefer the base's cached root; only fall back to a lookup when the host replaced it.
        val root = miniRoot?.takeIf { it.isAttachedToWindow } ?: find("mini_player") ?: return null
        val source = miniArtworkSource?.takeIf { it.isAttachedToWindow }
            ?: resolveMiniArtworkSource(root)?.also { miniArtworkSource = it }
            ?: return null
        return artworkDrawable(source)
    }

    private fun resolveMiniArtworkSource(root: View): View? {
        val contentId = resourceId("mini_player_content", "id")
        val content = if (contentId != 0) root.findViewById<View>(contentId) ?: root else root
        val surfaceId = resourceId("video_surface", "id")
        if (surfaceId != 0) return content.findViewById(surfaceId)
        return content
    }

    private fun artworkDrawable(source: View): Drawable? =
        reflectedArtwork(source) ?: firstImageDrawable(source)

    /** `NowPlayingContentView.getArtworkView()`, when the host build exposes one. */
    private fun reflectedArtwork(source: View): Drawable? = runCatching {
        val method = source.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name == "getArtworkView"
        }
        when (val view = method?.invoke(source) as? View) {
            null -> null
            is ImageView -> view.drawable
            else -> firstImageDrawable(view)
        }
    }.getOrNull()

    private fun firstImageDrawable(root: View): Drawable? {
        if (root is ImageView) root.drawable?.let { return it }
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            firstImageDrawable(root.getChildAt(index))?.let { return it }
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

    /** Host `Menu` -> [GlassTopTab]; only a changed menu key rebuilds the list. */
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
                GlassTopTab(
                    id = item.itemId,
                    title = item.title?.toString().orEmpty(),
                    // GlassTopNavigation clones and tints the icon itself; never pre-tint the host drawable.
                    icon = item.icon,
                    enabled = item.isEnabled,
                    display = if (searchId != 0 && item.itemId == searchId) {
                        GlassTopTabDisplay.ICON
                    } else {
                        GlassTopTabDisplay.TEXT
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
     * highlight where it was ([GlassTopNavigation] only moves it when the return value matches).
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
        topContentPaddingPx = TabletChromeLayoutPolicy.contentTopPaddingPx(topBarHeightPx, topInset, density)
    }

    private fun statusBarInset(): Int =
        activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0

    /** Compare-then-write for every per-frame value: params, alpha, visibility, capture, padding. */
    private fun updateTopChrome() {
        val glass = topGlass ?: return
        if (topBarHeightPx <= 0) return
        val params = glass.layoutParams as? FrameLayout.LayoutParams
        if (params != null && (params.height != topBarHeightPx || params.topMargin != topBarMarginPx)) {
            params.height = topBarHeightPx
            params.topMargin = topBarMarginPx
            glass.layoutParams = params
        }
        // Capture is armed by activation/menu readiness, not by the backdrop being ready: the
        // backdrop can only become ready once capture is enabled.
        val armed = activated && glassMenuReady
        if (topBackdropCapture != armed) {
            topBackdropCapture = armed
            topBackdrop?.setCaptureEnabled(armed)
        }
        val ready = armed && topBackdrop?.ready == true
        val alpha = if (ready) 1f else 0f
        if (glass.alpha != alpha) glass.alpha = alpha
        val visibility = if (ready) View.VISIBLE else View.GONE
        if (glass.visibility != visibility) glass.visibility = visibility
        applyTopContentPadding()
    }

    private fun applyTopContentPadding() {
        val root = contentRoot ?: return
        if (topPaddingOriginal == null) topPaddingOriginal = root.paddingTop
        if (topContentPaddingPx <= 0) return
        // The policy owns the "where" decision; it is the page viewport, not a scrolling child.
        if (TabletChromeLayoutPolicy.topPaddingTarget() != TabletChromeLayoutPolicy.TopPaddingTarget.CONTENT_ROOT) return
        if (root.paddingTop == topContentPaddingPx) return
        root.setPadding(root.paddingLeft, topContentPaddingPx, root.paddingRight, root.paddingBottom)
    }

    private fun restoreTopContentPadding() {
        val root = contentRoot ?: return
        val original = topPaddingOriginal ?: return
        if (root.paddingTop != original) {
            root.setPadding(root.paddingLeft, original, root.paddingRight, root.paddingBottom)
        }
        topPaddingOriginal = null
        contentRoot = null
    }

    private fun releaseTopChrome() {
        topObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(topLayoutListener)
        topObserver = null
        topBackdrop?.close()
        topBackdrop = null
        topBackdropCapture = false
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
