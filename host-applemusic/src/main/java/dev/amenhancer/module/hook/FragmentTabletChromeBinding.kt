package dev.amenhancer.module.hook

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.host.OwnedHostProperty
import dev.amenhancer.module.host.OwnedHostIntOffset
import java.util.IdentityHashMap

/** Native ownership from reference595581b, isolated from module Compose/session objects. */
internal class FragmentTabletChromeBinding(
    private val root: ViewGroup,
    private val region: (TabletChromeRegion) -> View?,
    private val layer: (String) -> View?,
    private val coverReady: () -> Boolean,
    private val contract: FragmentChromeContract,
    private val behavior: () -> Any?,
) : FragmentTabletChromePort {
    private class Alpha(val view: View) {
        var native = view.alpha
        var factor = 1f
        var last: Float? = null
        fun write(value: Float): Float { native = value; return (native * factor).also { last = it } }
    }
    private val alphas = IdentityHashMap<View, Alpha>()
    private val clips = IdentityHashMap<ViewGroup, Pair<OwnedHostProperty<Boolean>, OwnedHostProperty<Boolean>>>()
    private val backgrounds = IdentityHashMap<View, OwnedHostProperty<Drawable?>>()
    private val outlines = IdentityHashMap<View, OwnedHostProperty<Boolean>>()
    private var navigationVisibility: OwnedHostProperty<Int>? = null
    private var navigationAccessibility: OwnedHostProperty<Int>? = null
    private var nativeNavigationVisibility: Int? = null
    private var miniTransform: FragmentContentTransformOwner? = null
    private var transformView: View? = null
    private var writing = false
    private var navigationReady = false
    private var miniReady = false
    private val behaviorNames = contract.names.getJSONObject("phone").getJSONObject("behavior")
    private val peekField = FragmentChromeContract.field(contract.playerBehavior.type,
        behaviorNames.getString("peekField"), java.lang.Integer.TYPE)
    private val autoPeekField = FragmentChromeContract.field(contract.playerBehavior.type,
        behaviorNames.getString("autoPeekField"), java.lang.Boolean.TYPE)
    private val writePeek = FragmentChromeContract.method(contract.playerBehavior.type,
        behaviorNames.getString("peekMethod"), java.lang.Integer.TYPE, java.lang.Boolean.TYPE)
    private val nativeGapId = root.resources.getIdentifier(
        contract.names.getJSONObject("tablet").getString("miniBottomGapDimension"), "dimen", root.context.packageName,
    ).also { check(it != 0) { "Native tablet mini bottom spacing missing" } }
    private var gapBehavior: Any? = null
    private var gapPeek: OwnedHostIntOffset? = null
    private var gapMaterial: View? = null
    private var gapMargin: OwnedHostIntOffset? = null

    fun peek(owner: Any, value: Int): Int? =
        if (owner === gapBehavior) gapPeek?.hostWrite(value) else null

    fun miniBottomMargin(view: View, value: Int): Int? =
        if (view === gapMaterial) gapMargin?.hostWrite(value) else null

    override fun setMiniBottomGap(gapPx: Int): Boolean {
        val material = region(TabletChromeRegion.MINI_MATERIAL)
        val owner = behavior()
        if (material == null || material.visibility != View.VISIBLE || owner == null) {
            restoreMiniGap()
            return false
        }
        if (owner !== gapBehavior) {
            gapPeek?.close()
            gapBehavior = owner
            gapPeek = OwnedHostIntOffset(
                { if (autoPeekField.getBoolean(owner)) -1 else peekField.getInt(owner) },
                { writePeek.invoke(owner, it, false) },
                // No current item and automatic peek must retain their native semantics.
                { native, offset -> if (native > 0) (native + offset).coerceAtLeast(0) else native },
            )
        }
        if (material !== gapMaterial) {
            gapMargin?.close()
            gapMaterial = material
            gapMargin = OwnedHostIntOffset(
                { (material.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin },
                { value -> material.layoutParams = (material.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    bottomMargin = value
                } },
            )
        }
        // Native tablet binding/peek already include the 10dp spacing and system-bar inset.
        // Replace just that spacing; moving the sheet keeps mini touch/artwork coordinates native.
        val offset = gapPx - root.resources.getDimensionPixelSize(nativeGapId)
        return gapMargin!!.setOffset(offset) or gapPeek!!.setOffset(offset)
    }

    private fun restoreMiniGap() {
        gapPeek?.close(); gapPeek = null; gapBehavior = null
        gapMargin?.close(); gapMargin = null; gapMaterial = null
    }

    override fun view(region: TabletChromeRegion) = this.region(region)
    fun alphaWrite(view: View, value: Float): Float? =
        if (writing) null else alphas[view]?.write(value)
    fun replacesBlur(view: View): Boolean =
        (navigationReady && view === region(TabletChromeRegion.NAVIGATION_MATERIAL)) ||
            (miniReady && view === region(TabletChromeRegion.MINI_MATERIAL))

    private fun alpha(view: View, factor: Float) {
        val state = alphas.getOrPut(view) { Alpha(view) }
        if (state.last != null && view.alpha != state.last) state.native = view.alpha
        state.factor = factor
        val desired = state.native * factor
        if (view.alpha != desired) {
            writing = true
            try { view.alpha = desired; state.last = desired } finally { writing = false }
        }
    }

    override fun replaceNavigation(ready: Boolean) {
        navigationReady = ready
        val content = region(TabletChromeRegion.NAVIGATION) ?: return
        if (navigationVisibility == null) {
            nativeNavigationVisibility = content.visibility
            navigationVisibility = OwnedHostProperty({ content.visibility }, { content.visibility = it })
            navigationAccessibility = OwnedHostProperty({ content.importantForAccessibility }, { content.importantForAccessibility = it })
        }
        if (ready) {
            navigationVisibility?.set(View.INVISIBLE)
            navigationAccessibility?.set(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        } else { navigationVisibility?.close(); navigationAccessibility?.close() }
        region(TabletChromeRegion.NAVIGATION_MATERIAL)?.let { alpha(it, if (ready) 0f else 1f) }
    }

    override fun replaceMini(ready: Boolean) {
        miniReady = ready
        region(TabletChromeRegion.MINI_MATERIAL)?.let { alpha(it, if (ready) 0f else 1f) }
        region(TabletChromeRegion.MINI)?.let { content ->
            if (ready) backgrounds.getOrPut(content) { OwnedHostProperty({ content.background }, { content.background = it }) }.set(null)
            else backgrounds.remove(content)?.close()
        }
    }

    override fun playerLayers(frame: FragmentTabletGlassFrame, slide: Float) {
        layer("playerBackground")?.let { alpha(it, frame.expansion) }
        layer("playerContent")?.let { alpha(it, if (slide > 0f && coverReady()) 1f else frame.expansion) }
        layer("playerMotion")?.let { alpha(it, frame.motion) }
        region(TabletChromeRegion.PLAYER)?.let { player ->
            if (frame.expansion < 1f) {
                backgrounds.getOrPut(player) { OwnedHostProperty({ player.background }, { player.background = it }) }.set(null)
                outlines.getOrPut(player) { OwnedHostProperty({ player.clipToOutline }, { player.clipToOutline = it }) }.set(false)
            } else { backgrounds.remove(player)?.close(); outlines.remove(player)?.close() }
        }
    }

    override fun allowOverflow() {
        generateSequence(region(TabletChromeRegion.PLAYER)) { it.parent as? View }.takeWhile { it !== root }.forEach { view ->
            if (view is ViewGroup) {
                val owned = clips.getOrPut(view) {
                    OwnedHostProperty({ view.clipChildren }, { view.clipChildren = it }) to
                        OwnedHostProperty({ view.clipToPadding }, { view.clipToPadding = it })
                }
                owned.first.set(false); owned.second.set(false)
            }
        }
    }

    override fun transformMini(sx: Float, sy: Float, x: Float, y: Float) {
        val content = region(TabletChromeRegion.MINI) ?: return
        if (transformView !== content) {
            miniTransform?.close(); transformView = content
            miniTransform = FragmentContentTransformOwner(
                { FragmentContentTransform(content.scaleX, content.scaleY, content.translationX, content.translationY) },
                { content.scaleX = it.scaleX; content.scaleY = it.scaleY; content.translationX = it.translationX; content.translationY = it.translationY })
        }
        if (sx == 1f && sy == 1f && x == 0f && y == 0f) miniTransform?.close()
        else miniTransform?.apply(sx, sy, x, y)
    }

    private fun product(views: Sequence<View>): Float = FragmentTabletGlassPolicy.opacity(true,
        views.map { alphas[it]?.native ?: it.alpha }.asIterable())
    override fun opacity(view: View): Float = if (!view.isShown || view.width <= 0 || view.height <= 0) 0f
        else product(generateSequence(view) { it.parent as? View }.takeWhile { it !== root })
    override fun navigationOpacity(): Float {
        val content = region(TabletChromeRegion.NAVIGATION) ?: return 0f
        val parent = content.parent as? View ?: return 0f
        if ((nativeNavigationVisibility ?: content.visibility) != View.VISIBLE || !parent.isShown) return 0f
        return product(sequenceOf(content).plus(generateSequence(parent) { it.parent as? View }.takeWhile { it !== root }))
    }
    override fun restore() {
        restoreMiniGap()
        navigationReady = false; miniReady = false
        miniTransform?.close(); miniTransform = null; transformView = null
        writing = true
        try { alphas.forEach { (view, state) -> if (view.alpha == state.last) view.alpha = state.native } }
        finally { writing = false }
        alphas.clear()
        backgrounds.values.forEach { it.close() }; backgrounds.clear()
        outlines.values.forEach { it.close() }; outlines.clear()
        clips.values.forEach { it.first.close(); it.second.close() }; clips.clear()
        navigationVisibility?.close(); navigationAccessibility?.close()
        navigationVisibility = null; navigationAccessibility = null; nativeNavigationVisibility = null
    }
}
