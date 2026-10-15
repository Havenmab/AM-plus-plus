/*
 * Derived from AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass), commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, Apache License 2.0.
 * Changed by AM++: optional host accent, tap-only native reselection, free thumb dragging with
 * multi-finger hand-over, a panel highlight pinned to the thumb's centre (the reference draws
 * the same light a row inset to the left), and a scroll-collapse progress that animates the
 * panel's width/height and swaps the tab row for a compact face. Every collapse parameter
 * defaults to the expanded bar, so the reference behaviour is unchanged when it is unused.
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md.
 */

package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.utils.DampedDragAnimation
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import dev.amenhancer.glass.GlassPolicy
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    accentOverride: Color = Color.Unspecified,
    onSelectedTabClick: ((Int) -> Unit)? = null,
    isTabEnabled: (Int) -> Boolean = { true },
    panelHeight: androidx.compose.ui.unit.Dp = 64f.dp,
    panelBlur: androidx.compose.ui.unit.Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    leadingWidth: androidx.compose.ui.unit.Dp = 0f.dp,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    /** AM++ scroll collapse: 0 = expanded bar, 1 = collapsed capsule. */
    collapseProgress: () -> Float = { 0f },
    /** AM++: drawn in place of the tab row once the capsule has withdrawn. */
    compactContent: (@Composable () -> Unit)? = null,
    /** AM++: the collapsed capsule is the only way back, so it must accept the tap. */
    onExpandRequest: (() -> Unit)? = null,
    /**
     * AM++: user-set rebound amplitudes, as fractions of each spring's own overshoot. Both collapsed
     * side buttons share this pair so they cannot animate differently. [collapseReboundScale] is the
     * collapse direction (the visible Q-bounce); [expandReboundScale] is the return direction, where
     * the overshoot makes each button grow back **towards the mini player**.
     */
    collapseReboundScale: Float = 1f,
    expandReboundScale: Float = 1f,
    content: @Composable RowScope.() -> Unit
) {
    // AM++: preserve Apple's reselect action without changing drag/animation behavior.
    val selectedTabClick = androidx.compose.runtime.rememberUpdatedState(onSelectedTabClick)
    val isLightTheme = !isSystemInDarkTheme()
    // AM++: allow the host accent without changing material or animation.
    val accentColor = if (accentOverride != Color.Unspecified) accentOverride else
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(0f) }
    val squeeze = with(density) { 4f.dp.toPx() }
    val panelInset = with(density) { 4f.dp.toPx() }

    // AM++: the squeeze nudge is read by both the panel layer and the highlight, so it lives
    // in one place and is evaluated against whichever width is being drawn.
    fun squeezeOffset(width: Float): Float {
        if (!width.isFinite() || width <= 0f) return 0f
        val fraction = (offsetAnimation.value / width).fastCoerceIn(-1f, 1f)
        return squeeze * fraction.sign * EaseOut.transform(abs(fraction))
    }

    // AM++: the light is part of the control rather than a spot beside it, so it is placed on
    // the glass thumb's centre and rides it wherever it goes. The thumb's geometry only exists
    // inside the box scope below, so the two are wired together through this coupling.
    val highlightAnchor = remember { PillCentre() }
    val freeDragBridge = remember { FreeDragBridge() }

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ -> Offset(highlightAnchor.x(), size.height / 2f) },
            // Clipped to the same capsule the glass draws. On the wide bar the difference is
            // invisible; on the withdrawn 43dp circle it is the difference between a round press
            // glow and a square shadow behind a round button.
            shape = Capsule(),
        )
    }

    /**
     * AM++: whether the tab-drag gesture may exist **at all**, as opposed to merely being
     * inactive.
     *
     * This is the collapsed mini player's taps being dead. `freeDragBridge.modifier` is a
     * `pointerInput` on the row box, which is `fillMaxWidth()` — so while it is present the row is a
     * full-width Compose hit target. When the bar collapses, the mini player sinks into exactly that
     * band, so the overlay sits over the mini and claims every tap meant for it. Compose reports the
     * event as handled to the Android View system as soon as a pointer lands on *any* pointer-input
     * node, whether or not that node consumes it — which is why the module's own Compose controls in
     * the same row kept working (they are inside the same Compose tree, so its internal hit testing
     * still reaches them) while the native mini, which lives in a different subtree, got nothing.
     * Removing the earlier consumption seams could not help: the event was never reaching the mini
     * to begin with.
     *
     * So the node must be **absent**, not idle, whenever the row does not own a drag. The progress
     * lambda is held in a `rememberUpdatedState` and read inside `derivedStateOf`, so the gate is
     * created once and reduces the per-frame spring value to a single boolean: this outer scope then
     * recomposes only when the gate actually flips — about twice per collapse — instead of on every
     * animation frame. Keying the `remember` on the lambda itself would not do that, because a
     * bound callable reference is not guaranteed to be the same instance across recompositions.
     */
    val collapseProgressState = rememberUpdatedState(collapseProgress)
    val dragEnabled by remember {
        derivedStateOf {
            dev.amenhancer.glass.GlassBarCollapsePolicy.dragEnabled(collapseProgressState.value())
        }
    }

    BoxWithConstraints(
        // AM++ scroll collapse: the box is pinned to the *expanded* bar height so it does not
        // shrink with the panel. That fixed box is what lets the panel withdraw upwards while
        // keeping the capsule the same distance above the screen edge, and it keeps
        // `constraints.maxWidth` at the expanded row width the panel interpolates away from.
        // The alignment stays `CenterStart` as in the reference. Each child is dropped onto the
        // collapsed row's centre line by `collapseDropPx`, which is 0 at progress 0, so every
        // value below is bit-identical to the reference while expanded.
        //
        // AM++: the drag gesture is applied only while the row actually owns it. Left on, its
        // full-width hit region swallowed every tap on the collapsed mini player underneath.
        modifier.then(if (dragEnabled) freeDragBridge.modifier else Modifier)
            .fillMaxWidth().height(panelHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        val leadingPx = with(density) { if (leadingContent == null) 0f else leadingWidth.toPx() }
        // AM++ scroll collapse. The unclamped value drives geometry so the expand spring's
        // overshoot stays visible; the clamped value drives alpha and input gating, where a
        // value outside [0, 1] would be invalid.
        val collapseRaw = collapseProgress()
        val expandedWidthPx = constraints.maxWidth.toFloat()
        val controlPx = with(density) { dev.amenhancer.glass.GlassBarCollapsePolicy.CONTROL_DP.dp.toPx() }
        // The room the capsule's growing edge actually has: the gap between it and the mini player.
        // The expand rebound is clamped to it, because that growth is a fraction of the ROW's travel
        // while the gap is a fixed dp — on a wide screen the fraction alone would push the capsule
        // into the mini player. See `reboundOffset`.
        val expandRoomPx = with(density) { dev.amenhancer.glass.GlassBarCollapsePolicy.GAP_DP.dp.toPx() }
        val panelWidthPx = dev.amenhancer.glass.GlassBarCollapsePolicy.capsuleWidth(
            expandedWidthPx,
            controlPx,
            collapseRaw,
            collapseReboundScale = collapseReboundScale,
            expandReboundScale = expandReboundScale,
            maxExpandReboundPx = expandRoomPx,
        )
        // The **clamped** width, i.e. the same travel without the rebound. The tab row's own
        // geometry (cell width, the droplet's position, drag hit-testing) is derived from this
        // rather than from the sprung width, so the SELECTOR does not rebound even though the
        // BACKPLATE does. The two are deliberately different: the backplate is the thing that
        // should visibly spring back, while a selection indicator that wobbles with it just reads
        // as the tabs being misaligned. Inside the travel the two are equal.
        val restingWidthPx = dev.amenhancer.glass.GlassBarCollapsePolicy.lerp(
            expandedWidthPx,
            controlPx,
            dev.amenhancer.glass.GlassBarCollapsePolicy.progress(collapseRaw),
        )
        val panelHeightPx = dev.amenhancer.glass.GlassBarCollapsePolicy.capsuleHeight(
            with(density) { panelHeight.toPx() },
            controlPx,
            collapseRaw,
        )
        val panelWidthDp = with(density) { panelWidthPx.toDp() }
        val panelHeightDp = with(density) { panelHeightPx.toDp() }
        val restingWidthDp = with(density) { restingWidthPx.toDp() }
        // The same travel with the height rebound removed. The SELECTOR uses this; the backplate
        // uses the sprung value. See the note on `restingWidthPx`.
        val restingHeightDp = with(density) {
            dev.amenhancer.glass.GlassBarCollapsePolicy.capsuleHeight(
                panelHeight.toPx(),
                controlPx,
                dev.amenhancer.glass.GlassBarCollapsePolicy.progress(collapseRaw),
            ).toDp()
        }
        val expandedPanelHeightPx = with(density) { panelHeight.toPx() }
        /**
         * Downward shift that **bottom-anchors** the shrinking panel: its bottom edge stays on the
         * expanded bar's bottom edge for the whole travel.
         *
         * The box keeps the expanded height and the panel is centred in it, so a panel that shrank
         * pulled its own bottom edge up by half the shrink — the collapsed bar visibly floated above
         * where the expanded one had been. Dropping it by `(box - collapsedControl) / 2` scaled by the
         * spring puts the bottom edge back on that line at every progress, not just at the ends.
         *
         * The **collapsed** height is what goes in, not the animating one: feeding the live height
         * would make the two terms cancel and the drop would vanish.
         *
         * At progress 0 the drop is 0 and the panel fills the box, so the expanded bar is
         * bit-identical to the reference.
         */
        val collapseDropPx = dev.amenhancer.glass.GlassBarCollapsePolicy.collapseDrop(
            expandedPanelHeightPx,
            controlPx,
            collapseRaw,
        )
        val expandedContentAlpha = dev.amenhancer.glass.GlassBarCollapsePolicy.expandedContentAlpha(collapseRaw)
        val compactContentAlpha = dev.amenhancer.glass.GlassBarCollapsePolicy.compactContentAlpha(collapseRaw)
        // Every horizontal base below is the **resting** panel, not the sprung one, so the tab row,
        // the droplet, the highlight and the drag hit regions do not ride the backplate's overshoot.
        // That split is deliberate: the backplate is what should visibly spring back, while a
        // selection indicator that wobbles with it reads as the tabs being misaligned. Inside the
        // travel the two widths are identical, so nothing changes except during the overshoot.
        val geometry = dev.amenhancer.glass.GlassTabGeometry(restingWidthPx, panelInset, leadingPx, tabsCount)
        val tabWidth = geometry.tabWidth

        val panelOffset by remember(density, restingWidthPx) {
            derivedStateOf { squeezeOffset(restingWidthPx) }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        // The first composition can be the 1px warm-up host. Gestures must use the current
        // measured slot, never the width captured when the animation object was constructed.
        val liveGeometry = rememberUpdatedState(geometry)
        val liveIsLtr = rememberUpdatedState(isLtr)
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                onTap = { selectedTabClick.value?.invoke(currentIndex) },
                pressedScale = 1.5f,
                pressedScaleY = 1.45f,
                // Only the thumb's own gesture may light the highlight. A tap on another tab
                // selects that tab without lighting the thumb while it settles there.
                onDragStarted = {
                    interactiveHighlight.pressAt(Offset.Zero)
                },
                onDragStopped = {
                    interactiveHighlight.releasePress()
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(liveGeometry.value.draggedIndex(targetValue, dragAmount.x, liveIsLtr.value))
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }
        LaunchedEffect(selectedTabIndex()) {
            // Drawer/back navigation changes the native selected ID without a glass gesture.
            currentIndex = selectedTabIndex()
            if (dampedDragAnimation.targetValue != currentIndex.toFloat()) {
                dampedDragAnimation.animateToValue(currentIndex.toFloat())
            }
        }

        // AM++: hand the thumb's live geometry to the highlight built above the box scope.
        highlightAnchor.pillValue = { dampedDragAnimation.value }
        highlightAnchor.panelWidth = restingWidthPx
        highlightAnchor.panelInset = panelInset + leadingPx
        highlightAnchor.tabWidth = tabWidth
        highlightAnchor.isLtr = isLtr
        // The light must migrate to the panel's own centre as the bar withdraws. Expanded it
        // belongs on the selected tab's cell, which is what `x()` computes; collapsed the panel is a
        // 43dp circle and that cell centre lands near the circle's *left edge*, so the press glow
        // appeared offset from the button. Interpolating on the same progress the geometry uses
        // keeps the light on the glass at every point of the travel.
        highlightAnchor.collapse = collapseRaw
        freeDragBridge.animation = dampedDragAnimation
        freeDragBridge.tabWidth = tabWidth
        freeDragBridge.panelWidth = restingWidthPx
        freeDragBridge.panelInset = panelInset + leadingPx
        freeDragBridge.panelOffset = panelOffset
        freeDragBridge.tabsCount = tabsCount
        freeDragBridge.isLtr = isLtr
        freeDragBridge.isTabEnabled = isTabEnabled
        // A withdrawn capsule must not keep owning the row's touches.
        freeDragBridge.enabled = dev.amenhancer.glass.GlassBarCollapsePolicy.dragEnabled(collapseRaw)
        freeDragBridge.onCanceled = {
            interactiveHighlight.releasePress()
        }

        Row(
            Modifier
                .graphicsLayer {
                    // The capsule is anchored to the row's start edge and only its end edge travels,
                    // so the whole expand rebound shows up on the side facing the mini player.
                    translationX = panelOffset
                    translationY = collapseDropPx
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(panelBlur.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = dev.amenhancer.glass.GlassPressGeometry.panelScale(size.width, 8f.dp.toPx(), progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                // `requiredWidth`/`requiredHeight`, NOT `width`/`height`. This is what makes the
                // expand rebound visible at all, and it was the whole reason it appeared to be
                // missing: the panel's expanded width *is* the row's width, so any outward
                // overshoot asks for a size the box cannot offer, and the plain size modifiers
                // coerce the request into the incoming constraints. The overshoot was computed
                // correctly and then silently dropped at measurement — ~8.5dp of travel that never
                // reached the screen. The required variants bypass that coercion, and the box is
                // `CenterStart`, so the extra width extends away from the anchored start edge, i.e.
                // towards the mini player, which is where the rebound belongs.
                //
                // Nothing clips it: the row does not clip its children and `GlassHostView` measures
                // its composition a 32dp bleed wider than its own bounds precisely so the lens and
                // shadow have room. The 8dp gap beside the control is the budget, and it is clamped
                // to exactly that in `capsuleWidth`.
                .requiredHeight(panelHeightDp)
                .requiredWidth(panelWidthDp)
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // AM++ scroll collapse: only the tab row fades. The glass panel above must stay
            // for the whole travel, otherwise the capsule would blink out mid-collapse.
            //
            // The width is pinned to the **resting** panel rather than `fillMaxSize()`, because the
            // node itself is now `requiredWidth(panelWidthDp)` and can be wider than the resting
            // bar during the expand overshoot. Letting the tab row fill it would stretch every cell
            // with the rebound; pinning it keeps the glyphs on their resting cells so the backplate
            // is the only thing that visibly springs. `- 8.dp` is the node's own `padding(4.dp)` on
            // both sides, so at rest this is exactly the width `fillMaxSize()` used to produce.
            Row(
                Modifier.width(restingWidthDp - 8f.dp).fillMaxHeight().alpha(expandedContentAlpha),
                verticalAlignment = Alignment.CenterVertically,
                content = { leadingContent?.invoke(this); content() }
            )
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                        translationY = collapseDropPx
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(panelBlur.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    // `required*` to match the backplate above: this layer is invisible itself but
                    // is recorded into `tabsBackdrop` for the droplet to refract, so if it kept the
                    // coerced size while the backplate grew, the droplet would refract a strip that
                    // no longer lines up with the glass it sits on.
                    .requiredHeight(panelHeightDp - 8f.dp)
                    .requiredWidth(panelWidthDp)
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The droplet refracts this layer, so its tabs must narrow and fade with the
                // visible row or the capsule would show a stale, wider tab strip. Pinned to the
                // resting width for the same reason as the visible row above.
                Row(
                    Modifier.width(restingWidthDp - 8f.dp).fillMaxHeight().alpha(expandedContentAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                    content = { leadingContent?.invoke(this); content() }
                )
            }
        }

        // AM++ scroll collapse: the droplet is the tab row's selection indicator, so it
        // withdraws with that row. Left visible it would float over the collapsed
        // capsule's own glyph.
        //
        // It is **omitted** rather than faded, and that is not an optimisation. `Modifier.alpha`
        // is an inner layer here: placed after `drawBackdrop` it wraps only the node's children,
        // while the droplet's own surface, highlight and shadow are drawn by `drawBackdrop`
        // itself and so escape the alpha entirely — at full collapse that left a translucent
        // 1dp-wide sliver sitting on the capsule. `tabWidth` also collapses to `coerceAtLeast(1dp)`
        // once the panel is narrower than its own insets, so there is nothing left to draw anyway.
        if (expandedContentAlpha > 0.001f) {
            Box(
                Modifier
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer {
                        translationX =
                            if (leadingPx == 0f) {
                                if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                                else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                            } else geometry.thumbOffset(dampedDragAnimation.value, isLtr) + panelOffset
                        translationY = collapseDropPx
                    }
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        shadow = {
                            val progress = dampedDragAnimation.pressProgress
                            Shadow(alpha = progress)
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 8f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f * dampedDragAnimation.pressProgress
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                if (isLightTheme) Color.Black.copy(0.1f)
                                else Color.White.copy(0.1f),
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .height(panelHeightDp - 8f.dp)
                    // The thumb's width must follow the animated panel: `fillMaxWidth(1f / tabsCount)`
                    // measures against the full box, so it would keep the droplet at its expanded
                    // width while the capsule narrowed. `tabWidth` is the same value whenever no
                    // leading action exists, because `tabWidth = (panelWidth - 2 * inset) / count`
                    // and the 4dp inset is applied on both sides here — exact at progress 0.
                    .width(with(density) { tabWidth.toDp().coerceAtLeast(1.dp) })
            )
        }

        // AM++ scroll collapse: the withdrawn capsule's replacement face. It carries the
        // selected tab's glyph and is the only way back to the expanded bar, so it owns the
        // tap once it is the visible affordance. Drawn above the fading tab row.
        if (compactContent != null && compactContentAlpha > 0.001f) {
            // The press feedback must be the same **capsule** the glass is, not the default
            // rectangular ripple. This box is the panel's full bounding square, so the stock
            // indication drew a square highlight over a round button — and because the box is
            // dropped by `collapseDropPx` while the ripple is inset from its own bounds, the square
            // also read as offset from the circle. Clipping to `Capsule()` makes the two coincide by
            // construction, at every size, and the highlight is the same `Highlight.Default` the
            // right-hand search button already gets so the two buttons match.
            //
            // `compactInteractive` gates it because a ripple on a control that is not yet live
            // would promise a tap that does nothing.
            val interactive = onExpandRequest != null &&
                dev.amenhancer.glass.GlassBarCollapsePolicy.compactInteractive(collapseRaw)
            Box(
                Modifier
                    // Deliberately NOT `.align(BottomStart)`. The glass panel above takes the box's
                    // own `CenterStart` alignment and is then dropped by `collapseDropPx`, which is
                    // what bottom-anchors it. Bottom-aligning this box *and* dropping it applied the
                    // anchoring twice, so the glyph sat 6.5dp below the circle's centre — invisible
                    // when expanded (the drop is 0 and both alignments coincide) and only wrong once
                    // collapsed, which is exactly how it was reported.
                    //
                    // Sharing the panel's alignment and its drop makes the two concentric at every
                    // progress by construction, rather than by two offsets that have to agree.
                    .width(panelWidthDp)
                    .height(panelHeightDp)
                    .alpha(compactContentAlpha)
                    // The same drop the glass circle gets, so the glyph stays locked to it through
                    // the shrink and the rebound.
                    .graphicsLayer { translationY = collapseDropPx }
                    // `clip` sits BEFORE `clickable`, so it bounds the press indication too —
                    // that is the whole fix. This box is the panel's full bounding square, so the
                    // stock rectangular ripple drew a **square** highlight over a round button, and
                    // because the box is dropped by `collapseDropPx` while the ripple is inset from
                    // its own bounds the square also read as **offset** from the circle. Clipping to
                    // the same `Capsule()` the glass draws makes the two coincide by construction, at
                    // every size, and is a no-op on the expanded bar where the box is the capsule's
                    // own bounds anyway.
                    .clip(Capsule())
                    .then(
                        if (interactive) Modifier.clickable(onClick = onExpandRequest!!) else Modifier
                    ),
                contentAlignment = Alignment.Center,
                content = { compactContent() }
            )
        }
    }
}

/**
 * AM++: where the light sits, in the panel's own drawing space — the glass thumb's centre.
 *
 * The thumb's geometry only exists inside the box scope while the highlight is built above it,
 * so the two are wired together through this coupling. Only the thumb decides the position, so
 * the light cannot drift away from the control it belongs to. The squeeze cancels out on its
 * own: the panel and the thumb are nudged by the same amount.
 */
private class PillCentre {
    var pillValue: () -> Float = { 0f }
    var panelWidth: Float = 0f
    var panelInset: Float = 0f
    var tabWidth: Float = 0f
    var isLtr: Boolean = true

    /** AM++: 0 = the light sits on the selected tab's cell, 1 = on the panel's own centre. */
    var collapse: Float = 0f

    /**
     * The light's x within the panel.
     *
     * Expanded this is the selected tab's cell centre, which is where the glass thumb is and where
     * the reference draws the light. Collapsed the panel is the 43dp circle and the tab row has
     * faded away entirely, so there is no cell to sit on — and the cell centre would land near the
     * circle's left edge, which is what made the pressed glow look offset from the button. Both
     * ends are blended on the collapse progress so the light tracks the glass through the travel.
     *
     * The result is clamped into the panel, so a stale `tabWidth` during a menu swap cannot push the
     * light outside the glass it belongs to.
     */
    fun x(): Float {
        val t = collapse.coerceIn(0f, 1f)
        val onTab = panelInset + (pillValue() + 0.5f) * tabWidth
        val fromStart = if (isLtr) onTab else panelWidth - onTab
        val centred = panelWidth / 2f
        val blended = fromStart + (centred - fromStart) * t
        return if (panelWidth > 0f) blended.coerceIn(0f, panelWidth) else blended
    }
}

/**
 * AM++: owns every touch on the bar. A pointer that starts on another tab takes the thumb over
 * immediately and drags it freely across the whole bar, while one that starts on the thumb's own
 * tab holds it in place. A second finger landing mid-drag is ignored outright and only inherits
 * the drag when it is still held as the finger in charge lifts.
 */
private class FreeDragBridge {
    var animation: DampedDragAnimation? = null
    var tabWidth: Float = 0f
    var panelWidth: Float = 0f
    var panelInset: Float = 0f
    var panelOffset: Float = 0f
    var tabsCount: Int = 0
    var isLtr: Boolean = true
    /** AM++: false once the capsule has withdrawn, so it cannot keep owning the row. */
    var enabled: Boolean = true
    var isTabEnabled: (Int) -> Boolean = { true }
    var onCanceled: () -> Unit = {}

    val modifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            if (!enabled) return@awaitEachGesture
            val targetIndex = tabIndexAt(down.position.x)
            val damped = animation
            val originalIndex = damped?.targetValue
                ?.fastRoundToInt()
                ?.fastCoerceIn(0, tabsCount - 1)

            if (
                damped != null &&
                targetIndex != null &&
                originalIndex != null &&
                isTabEnabled(targetIndex)
            ) {
                // A press on the tab the thumb already occupies is a plain hold, so the thumb stays
                // where it is and only follows the finger's motion. A press on any other tab is
                // taken over, and the thumb rides across to that finger instead.
                val grabsInPlace = targetIndex == originalIndex
                // Take ownership at the initial pass so the tab's ordinary clickable cannot
                // finish this sequence before the thumb's own gesture does.
                down.consume()
                damped.onDragStarted.invoke(damped, down.position)
                damped.press()
                if (!grabsInPlace) {
                    damped.updateValue(valueAt(down.position.x))
                }

                var ownerId = down.id
                var ownerPosition = down.position
                var traveled = 0f
                // Handing the drag over moves the thumb, so only a hold that never changed hands
                // may still count as a plain tap on it.
                var tapEligible = grabsInPlace
                // Other fingers that land during this drag, oldest first. They are swallowed while
                // the owner holds, and the newest one still held inherits the drag when it lifts.
                val held = mutableListOf<Pair<PointerId, Offset>>()
                var canceled = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)

                        for (change in event.changes) {
                            if (change.id == ownerId) continue
                            if (!change.pressed && !change.previousPressed) continue
                            change.consume()
                            when {
                                !change.previousPressed -> {
                                    // A press that lands mid-drag is only a hand-over candidate, and
                                    // only when it targets a tab that may be selected.
                                    if (tabIndexAt(change.position.x)?.let { isTabEnabled(it) } == true) {
                                        held.removeAll { it.first == change.id }
                                        held.add(change.id to change.position)
                                    }
                                }

                                change.pressed -> {
                                    val index = held.indexOfFirst { it.first == change.id }
                                    if (index >= 0) {
                                        held[index] = change.id to change.position
                                    }
                                }

                                else -> held.removeAll { it.first == change.id }
                            }
                        }

                        val change = event.changes.fastFirstOrNull { it.id == ownerId }
                        if (change == null) {
                            canceled = true
                            break
                        }
                        change.consume()
                        if (change.changedToUpIgnoreConsumed()) {
                            // Hand the drag over to the newest finger still down. One that already
                            // lifted leaves nothing behind, so the drag simply ends where it is.
                            val next = held.lastOrNull() ?: break
                            held.removeAt(held.lastIndex)
                            ownerId = next.first
                            ownerPosition = next.second
                            tapEligible = false
                            damped.updateValue(valueAt(ownerPosition.x))
                            continue
                        }
                        val dragAmount = change.position - ownerPosition
                        if (dragAmount != Offset.Zero) {
                            if (tapEligible) {
                                traveled += dragAmount.getDistance()
                            }
                            damped.onDrag.invoke(damped, IntSize.Zero, dragAmount)
                        }
                        ownerPosition = change.position
                    }

                    if (canceled) {
                        onCanceled()
                        damped.animateToValue(originalIndex.toFloat())
                    } else {
                        damped.onDragStopped.invoke(damped)
                        // The thumb's own gesture reports a tap the same way: a hold that never moved
                        // beyond the touch slop is the finger pressing and lifting in place.
                        if (tapEligible && traveled <= viewConfiguration.touchSlop) {
                            damped.onTap?.invoke()
                        }
                    }
                } finally {
                    // Pointer cancellation and menu disposal must not leave a held lens behind.
                    onCanceled()
                    damped.release()
                }
            }
        }
    }

    private fun tabIndexAt(x: Float): Int? {
        if (tabWidth <= 0f) return null
        val contentX = logicalX(x)
        if (contentX < 0f || contentX >= tabWidth * tabsCount) return null
        val visualIndex = (contentX / tabWidth).toInt().fastCoerceIn(0, tabsCount - 1)
        return visualIndex
    }

    private fun valueAt(x: Float): Float =
        ((logicalX(x) / tabWidth) - 0.5f)
            .fastCoerceIn(0f, (tabsCount - 1).toFloat())

    private fun logicalX(x: Float): Float =
        if (isLtr) {
            x - panelOffset - panelInset
        } else {
            panelWidth - x + panelOffset - panelInset
        }
}
