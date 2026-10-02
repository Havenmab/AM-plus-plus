/*
 * Derived from AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass), commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, Apache License 2.0.
 * Changed by AM++: optional host accent, tap-only native reselection, free thumb dragging with
 * multi-finger hand-over, a panel highlight pinned to the thumb's centre (the reference draws
 * the same light a row inset to the left), an opt-in `cleanSelectionMask` that keeps the tab
 * cells out of the layer the thumb refracts — the masked row records the panel's page material
 * instead of the cells — so the cells are never smeared into a ghost and the glass still refracts,
 * and an opt-in `effectReferenceHeight` that scales the reference's absolute-dp lens/squeeze
 * constants from the panel height so a thinner capsule keeps the same proportions,
 * and an opt-in `pressScalesCells = false` that holds the cells still while pressed and replaces
 * that all-cell squeeze with a brief pulse on the one cell the thumb settles on,
 * and an opt-in `refractionScalesWithThumb` that lets the thumb's press bloom magnify the backdrop
 * it refracts in place instead of counter-transforming it against the thumb.
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md.
 */

package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
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
import dev.amenhancer.glass.TabThumbGeometry
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * AM++: how far the settled cell grows during its brief pulse while `pressScalesCells` is off. The
 * reference's press squeeze is 1.2x applied to every cell; the settled pulse is deliberately a
 * smaller, one-cell "landed" accent.
 */
private const val SETTLE_PULSE_SCALE = 1.12f

/** AM++: milliseconds the settled cell takes to ease back to 1x after its pulse peak. */
private const val SETTLE_PULSE_MS = 180

/**
 * AM++: the thumb's own press bloom — the reference's spring scale plus its velocity shear.
 *
 * It lives in one place because it can be applied two different ways (see
 * [LiquidBottomTabs]'s `refractionScalesWithThumb`): as the backdrop sampler's `layerBlock`
 * (the reference path, where the library counter-transforms the refraction against it) or as a
 * plain `graphicsLayer` on the thumb (the in-place path, where the refracting backdrop is
 * magnified with the thumb). Both must use the identical numbers or the two paths drift.
 */
private fun GraphicsLayerScope.thumbBloom(animation: DampedDragAnimation) {
    scaleX = TabThumbGeometry.scaleX(animation.scaleX, animation.velocity)
    scaleY = TabThumbGeometry.scaleY(animation.scaleY, animation.velocity)
}

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
    /**
     * AM++: the panel height the reference's absolute-dp effect constants were authored for.
     *
     * Upstream hardcodes its lens radii, press squeeze, press nudge and inner-shadow radius in
     * absolute dp, tuned against the reference bar's height (the shipped phone bar is 56dp). On a
     * shorter capsule those absolutes stay the same physical size and smear the refraction, so every
     * one of them is multiplied by `panelHeight / effectReferenceHeight`. The default is
     * [panelHeight] itself, i.e. a scale of exactly 1, so the shipped phone/dual-pane bar is
     * unchanged; a thin bar passes the reference height it wants to keep the proportions of.
     */
    effectReferenceHeight: androidx.compose.ui.unit.Dp = panelHeight,
    /**
     * AM++: keep the tab cells out of the layer the selection thumb refracts.
     *
     * The reference draws the cells twice: once visibly, and once as an invisible row that is
     * recorded into the layer backdrop the thumb samples with its lens. That second copy is what
     * makes the selected cell look duplicated and smeared inside the sliding mask once the thumb
     * is pressed, because the lens displaces, colour-fringes and press-scales it into a ghost of
     * the label. With this on the cells are never recorded: the invisible row records only the
     * panel's own material — the page backdrop seen through the bar, plus the container tint, left
     * unblurred so the lens has high-frequency edges to act on — so the mask keeps the refraction
     * the reference shows (displacement and chromatic fringing are visible because the recorded page
     * has edges, not because a copy of a cell is smeared), and the single visible copy of the cells
     * is drawn last, on top of the thumb, so it stays crisp.
     *
     * Off by default, so the shipped phone/dual-pane bar keeps the reference layer and the
     * reference press animation exactly as they are.
     */
    cleanSelectionMask: Boolean = false,
    /**
     * AM++: scale the cells while the thumb is pressed, exactly as the reference does.
     *
     * The reference provides `lerp(1f, 1.2f, pressProgress)` to [LocalLiquidBottomTabScale], so a
     * press on the bar grows every cell — including an icon-only cell such as a search glyph. The
     * iPad-style top bar must stay still while pressed, so that session turns this off with `false`.
     * With it off the *visible* cells are held at `1f` during the press, and only the one cell the
     * thumb settles on grows, briefly, once the settle animation has carried the thumb onto it
     * (after a tap or a completed drag).
     * Defaulted to `true`, so the shipped phone/dual-pane bar keeps the reference's press animation
     * byte-identically.
     */
    pressScalesCells: Boolean = true,
    /**
     * AM++: magnify the backdrop the thumb refracts **with** the thumb instead of against it.
     *
     * The reference hands the thumb's press bloom to the backdrop sampler as its `layerBlock`.
     * The library counters that transform inside the sampler (its `InverseLayerScope`) and scales
     * the thumb with the same block, so the two are meant to cancel and the refracted copy is
     * supposed to stay put while the thumb grows. They do not cancel exactly: the counter-scale is
     * about the sampled layer's own origin while the thumb's bloom is about the **thumb's centre**,
     * so the sampled copy — the recorded row, i.e. the cells the thumb refracts — ends up translated
     * by `(1 - scale) * thumbCentre` while the page behind it is magnified in place. For a wide
     * reference panel that residue is small; for a compact bar whose cell is barely wider than its
     * label it is a large fraction of a glyph, so the refracted copy lands beside the crisp label as
     * a second, ghosted label.
     *
     * With this on the bloom is applied only as a plain `graphicsLayer` on the thumb: the sampler is
     * never counter-transformed, so the page and the recorded cells are magnified together about the
     * thumb's centre — the refracted copy is the label, magnified in place.
     *
     * Off by default, so the shipped phone/dual-pane bar keeps the reference path byte-identically.
     */
    refractionScalesWithThumb: Boolean = false,
    replaceContentUnderThumb: Boolean = false,
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
    // AM++: every absolute-dp effect constant below is multiplied by this, so a thinner capsule
    // keeps the reference's proportions instead of smearing the lens/squeeze (see
    // [effectReferenceHeight]). Exactly 1 under the default (reference == panel height), which is
    // what keeps the shipped phone/dual-pane bar byte-identical.
    val effectScale = run {
        val scale = panelHeight.value / effectReferenceHeight.value
        if (scale.isFinite() && scale > 0f) scale.coerceIn(0.25f, 4f) else 1f
    }
    val squeeze = with(density) { (4f.dp * effectScale).toPx() }
    val panelInset = with(density) { 4f.dp.toPx() }

    // AM++: the squeeze nudge is read by both the panel layer and the highlight, so it lives
    // in one place and is evaluated against whichever width is being drawn.
    fun squeezeOffset(width: Float): Float {
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
            position = { size, _ -> Offset(highlightAnchor.x(), size.height / 2f) }
        )
    }

    BoxWithConstraints(
        modifier.then(freeDragBridge.modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val panelOffset by remember(density) {
            derivedStateOf { squeezeOffset(constraints.maxWidth.toFloat()) }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        // AM++: the settle pulse used when `pressScalesCells` is off. `settlePulse` runs 1 -> 0 and
        // `settleIndex` names the one cell it applies to; both are plain Compose state, so the cells'
        // scale lambdas observe them inside their `graphicsLayer` blocks without any per-frame work.
        val settlePulse = remember { Animatable(0f) }
        var settleIndex by remember { mutableIntStateOf(-1) }
        val dampedDragAnimation = remember(animationScope, pressScalesCells) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                onTap = { selectedTabClick.value?.invoke(currentIndex) },
                pressedScale = 78f / 56f,
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
                    // AM++: the settled one-cell pulse. Waiting for the thumb's spring to reach the
                    // target is what makes it fire when the droplet *lands* on the tab rather than
                    // the instant the finger lifts; a tap on the thumb's own cell is already at rest
                    // there, so it pulses on the tap itself. Off in the shipped `pressScalesCells`
                    // path, whose cells are scaled by `pressProgress` instead.
                    if (!pressScalesCells) {
                        animationScope.launch {
                            val restThreshold = ((tabsCount - 1) * 0.025f).coerceAtLeast(0.001f)
                            snapshotFlow { value }
                                .filter { abs(it - targetIndex) <= restThreshold }
                                .first()
                            settleIndex = targetIndex
                            settlePulse.snapTo(1f)
                            settlePulse.animateTo(0f, tween(SETTLE_PULSE_MS))
                        }
                    }
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
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

        // AM++: the author's own press squeeze on every cell — `lerp(1f, 1.2f, pressProgress)`,
        // exactly as upstream provides it. The recorded (sampled) row always uses this, whatever
        // `pressScalesCells` says: the droplet's lens must refract a copy magnified about its own
        // cell centre, so the sampled label lands in place under the crisp one. With a constant 1f
        // here the sampled copy was instead magnified only by the thumb's bloom, whose pivot is the
        // thumb/layer origin above the droplet, so it drifted down-and-outward.
        val pressScale: (Int) -> Float = { _ ->
            lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
        }

        // AM++: what the *visible* cells resolve to. The shipped path is `pressScale`, so the phone
        // and dual-pane bars are unchanged; `pressScalesCells = false` holds the visible cells at 1x
        // while the thumb is pressed and scales only the one cell the thumb has settled on, by the
        // brief `settlePulse` — pressing must not grow the labels or a search glyph. This row's
        // lambda, and only this one, honours the opt-out. Both are read inside the cells'
        // `graphicsLayer` blocks, so the animation invalidates just those layers and never
        // recomposes the row.
        val tabScale: (Int) -> Float = if (pressScalesCells) pressScale else {
            { index ->
                if (index == settleIndex) lerp(1f, SETTLE_PULSE_SCALE, settlePulse.value) else 1f
            }
        }

        // AM++: hand the thumb's live geometry to the highlight built above the box scope.
        highlightAnchor.pillValue = { dampedDragAnimation.value }
        highlightAnchor.panelWidth = constraints.maxWidth.toFloat()
        highlightAnchor.panelInset = panelInset
        highlightAnchor.tabWidth = tabWidth
        highlightAnchor.isLtr = isLtr
        freeDragBridge.animation = dampedDragAnimation
        freeDragBridge.tabWidth = tabWidth
        freeDragBridge.panelWidth = constraints.maxWidth.toFloat()
        freeDragBridge.panelInset = panelInset
        freeDragBridge.panelOffset = panelOffset
        freeDragBridge.tabsCount = tabsCount
        freeDragBridge.isLtr = isLtr
        freeDragBridge.isTabEnabled = isTabEnabled
        freeDragBridge.onCanceled = {
            interactiveHighlight.releasePress()
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(panelBlur.toPx())
                        lens(24f.dp.toPx() * effectScale, 24f.dp.toPx() * effectScale)
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() * effectScale / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    // AM++: the panel itself keeps the reference's own material. The clean mask no
                    // longer needs an exported surface: the invisible row below records the panel's
                    // page material, without the cells, for the thumb to refract.
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(panelHeight)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            // AM++: this row is the reference's only visible copy of the cells. With the clean mask
            // the cells are drawn once, by the top row below, so this copy is dropped rather than
            // double-painted under it. `cleanSelectionMask = false` passes `content` unchanged.
            content = if (cleanSelectionMask || replaceContentUnderThumb) EmptyTabContent else content
        )

        if (replaceContentUnderThumb && !cleanSelectionMask) {
            CompositionLocalProvider(LocalLiquidBottomTabScale provides tabScale) {
                Row(
                    Modifier
                        .graphicsLayer {
                            translationX = panelOffset
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithCache {
                            val thumbOutline = Capsule().createOutline(
                                Size(tabWidth, size.height), layoutDirection, this,
                            )
                            onDrawWithContent {
                                drawContent()
                                val maskScaleX = TabThumbGeometry.scaleX(
                                    dampedDragAnimation.scaleX, dampedDragAnimation.velocity,
                                )
                                val maskScaleY = TabThumbGeometry.scaleY(
                                    dampedDragAnimation.scaleY, dampedDragAnimation.velocity,
                                )
                                val bounds = TabThumbGeometry.bounds(
                                    panelWidth = size.width,
                                    tabWidth = tabWidth,
                                    thumbHeight = size.height,
                                    inset = panelInset,
                                    index = dampedDragAnimation.value,
                                    isLtr = isLtr,
                                    scaleX = maskScaleX,
                                    scaleY = maskScaleY,
                                )
                                if (bounds != null) {
                                    withTransform({
                                        translate(bounds.left, bounds.top)
                                        scale(maskScaleX, maskScaleY, Offset.Zero)
                                    }) {
                                        drawOutline(thumbOutline, Color.Black, blendMode = BlendMode.DstOut)
                                    }
                                }
                            }
                        }
                        .height(panelHeight - 8f.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 4f.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }

        // AM++: the reference records this row — the panel material *and* the cells — into the layer
        // the thumb refracts. The row itself is alpha(0f), but layerBackdrop records the content
        // before alpha is applied, so the cells end up in that layer and the thumb's lens turns
        // them into a displaced, colour-fringed, press-scaled ghost of the label/icon.
        //
        // With `cleanSelectionMask` the row is kept but its *cells* are dropped: what it records is
        // then only the panel's own material — the page backdrop seen through the bar plus the
        // container tint — so the lens still refracts the page content behind the bar, never a copy
        // of a cell. The blur is dropped from that recording too: the mask must refract something
        // with high-frequency edges, and a pre-blurred surface leaves the lens nothing to displace
        // or colour-fringe, which is how the earlier attempt ended up a flat grey shape. The visible
        // panel keeps its blur; only the recorded refraction source is left sharp.
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides if (replaceContentUnderThumb) tabScale else pressScale
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            if (!cleanSelectionMask) blur(panelBlur.toPx())
                            lens(
                                24f.dp.toPx() * progress * effectScale,
                                24f.dp.toPx() * progress * effectScale
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(panelHeight - 8f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                // With the clean mask the cells are never recorded: the one visible copy is drawn
                // by the row above the selection mask, on top of it.
                content = if (cleanSelectionMask) EmptyTabContent else content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                // AM++: the in-place path keeps the bloom on the thumb itself and leaves the sampler
                // alone, so everything the thumb draws — the page and the recorded cells alike — is
                // magnified together about the thumb's centre. The default still hands the same
                // bloom to `drawBackdrop` as its `layerBlock`, i.e. the reference path.
                .then(
                    if (refractionScalesWithThumb) Modifier.graphicsLayer { thumbBloom(dampedDragAnimation) }
                    else Modifier
                )
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress * effectScale,
                            14f.dp.toPx() * progress * effectScale,
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
                            radius = 8f.dp * (progress * effectScale),
                            alpha = progress
                        )
                    },
                    layerBlock = if (refractionScalesWithThumb) {
                        null
                    } else {
                        { thumbBloom(dampedDragAnimation) }
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
                .height(panelHeight - 8f.dp)
                .fillMaxWidth(1f / tabsCount)
        )

        // AM++: the one and only copy of the cells, drawn last so it sits on top of the thumb and
        // is never sampled by it. Placed with the same inset, height, squeeze and cell scale as the
        // reference's front row, so the cells stay exactly where they were; only the refracted
        // ghost of them is gone. The layer the thumb refracts is the invisible row's recording of
        // the panel material — the page behind the bar, never the cells — so the lens still has real
        // content to displace and colour-fringe.
        if (cleanSelectionMask) {
            CompositionLocalProvider(
                LocalLiquidBottomTabScale provides tabScale
            ) {
                Row(
                    Modifier
                        .graphicsLayer {
                            translationX = panelOffset
                        }
                        .height(panelHeight - 8f.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 4f.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    }
}

/**
 * AM++: the cells are painted exactly once while `cleanSelectionMask` is on — by the row drawn above
 * the selection mask — so both rows underneath are laid out without them: the panel row, so the
 * cells are not double-painted, and the recorded refraction row, so the mask cannot refract a ghost
 * of a cell.
 */
private val EmptyTabContent: @Composable RowScope.() -> Unit = {}

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

    /** Mirrors the thumb's own translation: half a cell in from its inset at the start edge. */
    fun x(): Float {
        val fromStart = panelInset + (pillValue() + 0.5f) * tabWidth
        return if (isLtr) fromStart else panelWidth - fromStart
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
    var isTabEnabled: (Int) -> Boolean = { true }
    var onCanceled: () -> Unit = {}

    val modifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
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
