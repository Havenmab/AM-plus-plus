package dev.amenhancer.glass

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.abs

/**
 * Scroll collapse geometry for the phone bottom bar.
 *
 * The bar starts as one capsule holding every tab, search included. Collapsing shrinks that
 * capsule to a square pinned to the row's start edge and **detaches the search tab into its own
 * square at the row's end edge**, leaving the mini player in the middle. Every value here is a
 * plain interpolation between the two measured end points, so the split is continuous with the
 * spring rather than a threshold cut, and `progress = 0` reproduces the expanded bar exactly.
 *
 * The capsule deliberately keeps all of its cells throughout: the detach is drawn by the search
 * square fading in at the row's end while the capsule's own row fades out, not by removing a
 * cell. Dropping search from the row would silently narrow the *expanded* bar, where it is
 * still the user's way to reach search.
 *
 * All values are pixels. The dp constants are the collapsed end points, converted by the caller
 * with its own density so this policy stays density-agnostic and unit-testable.
 */
object GlassBarCollapsePolicy {
    /**
     * The **drawn** diameter of the collapsed side controls, and the height of the collapsed row.
     *
     * iOS 27 draws both side buttons the same height as the mini player, so the three controls share
     * one band. Not a free parameter: [GlassPolicy.MINI_HEIGHT_DP] is the source of truth, and a test
     * asserts the two agree so a future mini-height change cannot silently break the match.
     */
    const val CONTROL_DP = GlassPolicy.MINI_HEIGHT_DP

    /**
     * Collapsed row height.
     *
     * Equal to [CONTROL_DP], and that is the point of "the collapsed row's bottom edge must sit at
     * the same height as the expanded one". All three controls are [CONTROL_DP] tall and
     * **bottom-anchored** to the expanded bar's bottom edge, so the collapsed row occupies exactly
     * `[X, X + CONTROL_DP]`. A taller row would leave the controls floating inside it and the peek —
     * which is derived from this — would hold the whole row higher than the controls it contains.
     */
    const val ROW_DP = CONTROL_DP

    /** Gap between the collapsed capsule, the mini player and the detached search control. */
    const val GAP_DP = 8

    /**
     * Collapsed nav capsule. Each side control is as wide as it is tall, so all three read as one
     * row of equal circles and the mini's margins are measured from the real button width.
     */
    const val CAPSULE_DP = CONTROL_DP

    /** Collapsed search control. See [CAPSULE_DP]. */
    const val SEARCH_DP = CONTROL_DP

    /**
     * Signed clamp on the spring output, as a fraction of the travel.
     *
     * It is a *limit*, not a window: the spring's own shape is preserved on both sides and only the
     * extremes are cut, so neither direction is truncated at the spring's highest velocity. 0.09 is
     * comfortably above both real peaks (0.046 collapsing, 0.027 expanding), so in normal motion it
     * never actually clamps — it only guards against an interrupted animation reporting a larger
     * excess than a first overshoot.
     */
    const val SPRING_OVERSHOOT_LIMIT = 0.09f

    // Every control on the collapsed row is the same size, and **each is anchored to its own outer
    // edge**: the capsule to the row's start, the search control to the row's end, the mini player
    // between them. That anchoring is what puts the entire visible motion on the side facing the
    // mini player — the buttons appear to pull away from it and spring back towards it, rather than
    // swelling symmetrically about their own centres.
    //
    // It also decides where the expand overshoot is allowed to go. The capsule growing back means
    // its inner edge moves towards the mini, and the [GAP_DP] beside it is exactly the room that
    // motion spends: at the tuned expand bounce the overshoot is ~7.7dp against an 8dp gap, so the
    // button reaches for the mini player without ever touching it. A symmetric (centre-anchored)
    // growth would instead have spent half of it outwards, where the row's own margin is, and the
    // other half would have had to be clipped.

    /**
     * Clamps the spring output into the geometry domain. Semantic consumers (alpha, layer
     * gating) must stay inside `[0, 1]` or a negative alpha would throw at draw time; the
     * bouncy geometry consumers below deliberately do **not** clamp, so the overshoot of
     * [GlassBarExpandSpec] stays visible as the Q-bounce.
     */
    fun progress(value: Float): Float = if (!value.isFinite()) 0f else value.coerceIn(0f, 1f)

    /**
     * Unclamped interpolation for geometry that is allowed to overshoot.
     *
     * Degenerate inputs collapse to the expanded end point rather than producing NaN: these
     * values feed layout params and a `GraphicsLayer` matrix, where a non-finite number is not
     * recoverable.
     */
    fun lerp(from: Float, to: Float, p: Float): Float =
        if (!from.isFinite() || !to.isFinite() || !p.isFinite()) {
            if (from.isFinite()) from else 0f
        } else {
            from + (to - from) * p
        }

    /**
     * The spring output, **signed-clamped** rather than windowed.
     *
     * The shape stays the spring itself, symmetric on both sides of the travel; only the extremes
     * are cut off so a layout value can never be driven to something absurd.
     *
     * The alternative — remapping the spring output through a `[start, end]` window — is wrong for a
     * measurable reason: any one-sided window truncates the geometry **at the spring's highest
     * velocity**, so one direction starts late and the other slams into a wall part-way through its
     * motion. The two directions then no longer share a shape, which is exactly what "the rebound
     * feels wrong" turns into.
     */
    fun sprungFraction(value: Float, limit: Float = SPRING_OVERSHOOT_LIMIT): Float =
        if (!value.isFinite()) 0f else value.coerceIn(-limit, 1f + limit)

    /**
     * [sprungFraction] with each direction's own user amplitude applied **to its overshoot only**.
     *
     * The travel itself is never scaled, so a 0% setting removes the rebound and leaves the plain
     * narrowing rather than changing the animation's shape. Scaling the overshoot (rather than the
     * whole fraction) is what keeps `fraction == value` for the whole of `[0, 1]`, so the visible
     * travel is bit-identical at every amplitude.
     */
    fun sprungFraction(
        value: Float,
        collapseScale: Float,
        expandScale: Float,
        limit: Float = SPRING_OVERSHOOT_LIMIT,
    ): Float {
        val clamped = sprungFraction(value, limit)
        val base = progress(clamped)
        val excess = clamped - base
        if (excess == 0f) return base
        val raw = if (excess > 0f) collapseScale else expandScale
        val scale = if (raw.isFinite() && raw >= 0f) raw else 1f
        return base + excess * scale
    }

    /**
     * The extra displacement a spring's overshoot adds to a control's width, measured against
     * [travelPx].
     *
     * **The sign is left alone**, and the sign is what makes the two directions read correctly:
     *
     * - **Collapsing** (`p > 1`) the control is narrower than its collapsed width: it is simply
     *   further along the motion. This is the visible Q-bounce.
     * - **Expanding** (`p < 0`) the control is **wider** than its expanded width, so it grows into
     *   the gap beside it and settles back. An earlier revision flipped this inward with `abs()`,
     *   on the theory that an outward excess would be clipped away. That was wrong twice over: the
     *   gap beside each control is exactly the room the overshoot is meant to spend, and flipping it
     *   inward made the expand direction move *backwards* — narrower than the width it had already
     *   reached — which reads as a stumble rather than a rebound.
     *
     * [maxExpandReboundPx] is the room the growing edge actually has — the gap between the control
     * and the mini player. The expand rebound is **clamped to it**, because that growth is a
     * fraction of the *row's* travel while the gap is a fixed dp: on a wide screen the fraction
     * alone would push the button into the mini player. Clamping the **amplitude** (rather than
     * rescaling the whole curve) keeps the policy density- and device-independent: the motion still
     * follows the spring, it simply cannot overshoot further than the room it has.
     *
     * [travelPx] is measured against the **row's** travel for both controls, not each control's own,
     * so the two side buttons rebound by the same visible distance even though the capsule travels
     * the whole row and the search control only travels one cell.
     */
    fun reboundOffset(
        travelPx: Float,
        p: Float,
        collapseScale: Float,
        expandScale: Float,
        maxExpandReboundPx: Float = Float.POSITIVE_INFINITY,
    ): Float {
        if (!travelPx.isFinite() || !p.isFinite()) return 0f
        val fraction = sprungFraction(p, collapseScale, expandScale)
        val excess = fraction - progress(fraction)
        if (excess == 0f) return 0f
        val offset = travelPx * excess
        // Positive offset = the control grew past its resting width (the expand side).
        if (offset <= 0f || !maxExpandReboundPx.isFinite()) return offset
        return offset.coerceAtMost(maxExpandReboundPx.coerceAtLeast(0f))
    }

    /**
     * The capsule's travelling edges. The start edge is anchored to the row, so only the end edge
     * moves; anchoring instead of centring keeps the capsule still while its width springs, which is
     * what makes the collapsed state read as "the bar withdrew" rather than "the bar slid sideways".
     *
     * The width is the **plain unclamped lerp of the spring**, so the overshoot is the spring's own
     * shape and nothing post-processes it.
     */
    fun capsuleWidth(
        expandedWidth: Float,
        collapsedWidth: Float,
        p: Float,
        collapseReboundScale: Float = 1f,
        expandReboundScale: Float = 1f,
        maxExpandReboundPx: Float = Float.POSITIVE_INFINITY,
    ): Float {
        val resting = lerp(expandedWidth, collapsedWidth, progress(p)).coerceAtLeast(0f)
        if (!expandedWidth.isFinite() || !collapsedWidth.isFinite()) return resting
        val rebound = reboundOffset(
            travelPx = collapsedWidth - expandedWidth,
            p = p,
            collapseScale = collapseReboundScale,
            expandScale = expandReboundScale,
            maxExpandReboundPx = maxExpandReboundPx,
        )
        return (resting + rebound).coerceAtLeast(0f)
    }

    fun capsuleHeight(expandedHeight: Float, collapsedHeight: Float, p: Float): Float =
        lerp(expandedHeight, collapsedHeight, sprungFraction(p)).coerceAtLeast(0f)

    /**
     * How far a collapsed control must be dropped so its **bottom edge lands on the expanded bar's
     * bottom edge**.
     *
     * The controls are laid out centred in a box as tall as the *expanded* bar, so a control that
     * shrank kept its centre and pulled its bottom edge up by half the shrink. That is the "the
     * collapsed bar sits a few dp higher than the expanded one" report: the bar visibly floated up
     * as it collapsed.
     *
     * Bottom-anchoring instead means dropping the control by half the difference between the box and
     * the control, which puts its bottom edge exactly where the expanded bar's bottom edge was. The
     * caller passes the **collapsed** control height, not the animating one — feeding it the live
     * height would make the drop cancel itself out to zero.
     */
    fun collapseDrop(expandedBoxHeightPx: Float, collapsedControlHeightPx: Float, p: Float): Float {
        if (!expandedBoxHeightPx.isFinite() || !collapsedControlHeightPx.isFinite()) return 0f
        val span = ((expandedBoxHeightPx - collapsedControlHeightPx) / 2f).coerceAtLeast(0f)
        return span * progress(p)
    }

    /**
     * The mini capsule's margins inside the row, both measured **inwards from the row's own
     * expanded margins** so the caller can hand back values of the same shape it already uses.
     *
     * Collapsed, the mini sits one gap after the withdrawn capsule on the start side and one gap
     * before the detached search control on the end side. [endReserve] is that control's width
     * plus its gap, or zero when the menu yields no search tab — in which case the mini simply
     * runs to the row's end edge and no spurious gap appears.
     */
    fun miniStartMargin(expandedMargin: Float, capsuleWidth: Float, gap: Float, p: Float): Float =
        lerp(expandedMargin, expandedMargin + capsuleWidth + gap, p)

    fun miniEndMargin(expandedMargin: Float, endReserve: Float, p: Float): Float =
        lerp(expandedMargin, expandedMargin + endReserve, p)

    /**
     * How far the mini player drops so its **bottom edge lands on the collapsed row's bottom edge**,
     * the same line the two side controls are bottom-anchored to.
     *
     * This is the *whole* leftover space, not half of it. Half would centre the mini in the row band,
     * which left its bottom edge floating a few dp above the buttons' — and the row's bottom edge is
     * the one the expanded bar already occupied, so centring made the whole collapsed row read as
     * having drifted upwards.
     *
     * Zero at progress 0, so the expanded bar is untouched, and it interpolates on the same spring as
     * everything else instead of snapping when the collapse completes.
     *
     * Degenerate geometry (no mini, an already-taller mini, a non-finite height) yields 0: a negative
     * drop would lift the mini out of the row it is supposed to sit in.
     */
    fun miniDrop(expandedMiniHeightPx: Float, collapsedRowHeightPx: Float, p: Float): Float {
        if (!expandedMiniHeightPx.isFinite() || !collapsedRowHeightPx.isFinite()) return 0f
        val bottomAnchor = (collapsedRowHeightPx - expandedMiniHeightPx).coerceAtLeast(0f)
        return lerp(0f, bottomAnchor, progress(p))
    }

    /**
     * The mini player's **extra** sink past its centred height, riding the collapse spring's own
     * overshoot: it settles onto the row's centre line, dips a few dp lower, then springs back up.
     *
     * The dip is not a second animation. The collapse spring already overshoots its end point and
     * returns, so gating on exactly that overshoot makes the dip and the recovery inherit the
     * spring's timing for free — including when the user reverses mid-flight, where the excess
     * collapses to zero and the dip simply never happens.
     *
     * [springOvershoot] is the spring's first overshoot as a fraction of the travel (see
     * [GlassBarCollapseSprings.CollapseOvershoot]); it normalizes the raw excess so [amplitudePx]
     * is the dip at the spring's own peak rather than an arbitrary fraction of it.
     *
     * The whole visible travel and the settled state both give exactly zero — `p - progress(p)` is
     * zero for every `p <= 1` — so this only ever adds to the collapsed end of the motion and the
     * resting sink is unchanged.
     */
    fun miniSinkOvershoot(
        amplitudePx: Float,
        springOvershoot: Float,
        p: Float,
    ): Float {
        if (!amplitudePx.isFinite() || amplitudePx <= 0f) return 0f
        if (!springOvershoot.isFinite() || springOvershoot <= 0f) return 0f
        if (!p.isFinite()) return 0f
        // Clamped at the spring's own peak: an interrupted animation can report a larger excess
        // than a first overshoot, and letting that through would dip the mini far past the row.
        val excess = (p - progress(p)).coerceIn(0f, springOvershoot)
        return amplitudePx * (excess / springOvershoot)
    }

    /**
     * Content cross-fade. The two windows overlap so the bar never draws an empty frame, and
     * the panel material is **not** part of either: the glass stays for the whole travel, only
     * the glyph row and the selection droplet fade out of it.
     */
    fun expandedContentAlpha(p: Float): Float = 1f - smoothStep(progress(p), 0.43f, 0.72f)

    fun compactContentAlpha(p: Float): Float = smoothStep(progress(p), 0.52f, 0.82f)

    /**
     * The detached search control fades in later than the capsule's own glyph, so the bar never
     * shows two search affordances at once: the in-capsule cell is gone before this one arrives.
     */
    fun searchAlpha(p: Float): Float = smoothStep(progress(p), 0.60f, 0.85f)

    /**
     * The detached search control's width across the collapse, carrying the **capsule's** rebound.
     *
     * The control narrows from the tab cell it occupies down to the collapsed square, but that is
     * only about 22dp of travel where the capsule's is the whole row. Interpolating the control's
     * own width therefore gives it a rebound of roughly 1dp against the capsule's 14dp, so the two
     * read as different animations even though they ride one spring — and the rebound is precisely
     * what the eye reads as "the same motion".
     *
     * The rebound is an *absolute* displacement, so it is inherited rather than recomputed: past
     * the collapsed end point the control swings by exactly the distance the capsule swings, which
     * is [expandedRowWidth] worth of travel measured against the same collapsed width. Inside
     * `[0, 1]` the extra term is exactly zero, so for the whole visible travel the control is its
     * own geometry and only the overshoot differs.
     *
     * Derivation: the capsule's own `capsuleWidth(row, collapsed, p)` equals
     * `collapsed + (collapsed - row) * (p - 1)` beyond its end point, and `progress(p)` pins that
     * `(p - 1)` to zero inside the range — which is why the same expression serves both.
     */
    fun searchWidth(
        expandedCellWidth: Float,
        collapsedWidth: Float,
        expandedRowWidth: Float,
        p: Float,
        collapseReboundScale: Float = 1f,
        expandReboundScale: Float = 1f,
        maxExpandReboundPx: Float = Float.POSITIVE_INFINITY,
    ): Float {
        val resting = lerp(expandedCellWidth, collapsedWidth, progress(p)).coerceAtLeast(0f)
        if (!expandedCellWidth.isFinite() || !collapsedWidth.isFinite()) return resting
        // The rebound is measured against the ROW's travel, not the control's own. That is what
        // makes the two side controls rebound by the same visible distance even though this one
        // only travels a single cell where the capsule travels the whole row; see the class note.
        val rebound = reboundOffset(
            travelPx = collapsedWidth - expandedRowWidth,
            p = p,
            collapseScale = collapseReboundScale,
            expandScale = expandReboundScale,
            maxExpandReboundPx = maxExpandReboundPx,
        )
        return (resting + rebound).coerceAtLeast(0f)
    }

    /**
     * The detached search control only accepts input once it is the visible search affordance.
     * Before that the bar is still the expanded tab strip, which is where search is reachable.
     */
    fun searchInteractive(p: Float): Boolean = searchAlpha(p) > 0.5f

    /**
     * The collapsed capsule owns the row exactly when the tab row has given it up.
     *
     * Defined as the *negation* of [expandedInteractive] rather than as its own alpha window, so
     * the handover has no gap: two independent windows would leave a stretch of the travel where
     * the tab row is already inert but the capsule is not yet live, and a tap landing there would
     * do nothing at all. Because the capsule's action is "expand the bar", owning the row a
     * little before it is fully opaque is harmless.
     */
    fun compactInteractive(p: Float): Boolean = !expandedInteractive(p)

    /**
     * The tab row stops accepting taps once it is mostly faded.
     *
     * `Modifier.alpha(0f)` hides a subtree without disabling it, so without this an invisible —
     * and, mid-collapse, squashed — tab cell could still swallow a tap and navigate somewhere the
     * user cannot see. The threshold is the same half-visible rule the other two gates use, so
     * exactly one of the three faces owns the row at any moment.
     */
    fun expandedInteractive(p: Float): Boolean = expandedContentAlpha(p) > 0.5f

    /** Below this the bar is still a normal tab strip, so tab dragging stays enabled. */
    fun dragEnabled(p: Float): Boolean = progress(p) < 0.5f

    private fun smoothStep(value: Float, start: Float, end: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * The two directions get one complete spring each, never a shared spec.
 *
 * Collapsing happens once the row around the capsule has emptied out (the capsule is narrowing
 * and search has left), so it can afford a loose bounce. Expanding grows the capsule back
 * towards the mini player and the returning search control, which is the space-constrained
 * direction, so it stays tighter. Sharing one spec would force an after-the-fact compression of
 * whichever direction is tighter, and that curve is no longer a spring.
 *
 * The caller must pick by target in the same frame the target is computed, because
 * `Animatable.animateTo` only adopts a spec at the moment the animation starts.
 */
object GlassBarCollapseSprings {
    /** Collapse: bounce 0.30 -> damping ratio 0.70, a clearly visible rebound. */
    const val CollapseBounce = 0.30f

    /** Expand: bounce 0.245 -> damping ratio 0.755, held back for the returning neighbours. */
    const val ExpandBounce = 0.245f

    /**
     * Stiffness sets the length of the overshoot window, not just the speed. At k=250
     * (response ~0.40s) the window is long enough to read as a rebound; a stiffer spring
     * finishes the travel so fast that the overshoot reads as a jitter instead.
     */
    const val Stiffness = 250f

    fun dampingRatio(bounce: Float): Float = (1f - bounce).coerceIn(0.05f, 1f)

    /**
     * The collapse spring's first overshoot as a fraction of its travel, derived from its own
     * damping ratio rather than tabulated: `exp(-pi * z / sqrt(1 - z^2))`.
     *
     * Derived, not hard-coded, so retuning [CollapseBounce] moves the mini player's sink dip with
     * it. A dip calibrated against a stale constant would silently drift out of phase with the
     * capsule's rebound — which is the one thing the sink is supposed to match.
     */
    val CollapseOvershoot: Float = run {
        val z = dampingRatio(CollapseBounce).toDouble()
        kotlin.math.exp(-kotlin.math.PI * z / kotlin.math.sqrt(1.0 - z * z)).toFloat()
    }

    /**
     * The expand spring's first overshoot, derived the same way as [CollapseOvershoot].
     *
     * The expand spring is the tighter of the two (bounce 0.245), so its overshoot is smaller:
     * 0.0375 against the collapse spring's 0.0460. Deriving it keeps the expand rebound's
     * calibration honest if [ExpandBounce] is ever retuned.
     */
    val ExpandOvershoot: Float = run {
        val z = dampingRatio(ExpandBounce).toDouble()
        kotlin.math.exp(-kotlin.math.PI * z / kotlin.math.sqrt(1.0 - z * z)).toFloat()
    }
}

/** Collapse direction (expanded -> collapsed). See [GlassBarCollapseSprings]. */
val GlassBarCollapseSpec: SpringSpec<Float> = spring(
    dampingRatio = GlassBarCollapseSprings.dampingRatio(GlassBarCollapseSprings.CollapseBounce),
    stiffness = GlassBarCollapseSprings.Stiffness,
    visibilityThreshold = 0.001f,
)

/** Expand direction (collapsed -> expanded). See [GlassBarCollapseSprings]. */
val GlassBarExpandSpec: SpringSpec<Float> = spring(
    dampingRatio = GlassBarCollapseSprings.dampingRatio(GlassBarCollapseSprings.ExpandBounce),
    stiffness = GlassBarCollapseSprings.Stiffness,
    visibilityThreshold = 0.001f,
)
