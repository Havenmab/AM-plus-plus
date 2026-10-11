package dev.amenhancer.module.hook.tabletmedia

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.hook.ConstraintLayoutPane
import dev.amenhancer.module.hook.TabletDualPaneLayoutPolicy
import dev.amenhancer.module.host.OwnedHostProperty
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** View-owned end insets are reapplied after native media binding and restored on release. */
internal class TabletSongContentInsets(private val find: (View, String) -> View?) : AutoCloseable {
    private val guides = IdentityHashMap<View, OwnedHostProperty<Int>>()
    private val paddings = IdentityHashMap<View, OwnedHostProperty<Int>>()
    private val margins = IdentityHashMap<View, OwnedHostProperty<Int>>()
    private val transportMargins = IdentityHashMap<View, OwnedHostProperty<Pair<Int, Int>>>()
    private val progressContent = TabletProgressContent(find)

    private fun padding(view: View, value: Int): Boolean {
        if (view.paddingEnd == value) return false
        paddings.getOrPut(view) { OwnedHostProperty({ view.paddingEnd }, {
            view.setPaddingRelative(view.paddingStart, view.paddingTop, it, view.paddingBottom)
        }) }.set(value)
        return true
    }

    fun prepare(root: View): Boolean {
        var changed = false
        val end = (TabletDualPaneLayoutPolicy.CONTENT_END_INSET_DP * root.resources.displayMetrics.density).roundToInt()
        find(root, "metadata_guideline_end")?.let { guide ->
            val field = ConstraintLayoutPane.metadataEndGuideField(guide)
            val property = guides.getOrPut(guide) {
                OwnedHostProperty({ field.getInt(guide.layoutParams) }, { value ->
                    guide.layoutParams.also { field.setInt(it, value); guide.layoutParams = it }
                })
            }
            if (field.getInt(guide.layoutParams) != 0) { property.set(0); changed = true }
            find(root, "list_left_icon")?.let { if (padding(it, end)) changed = true }
            find(root, "collaborator_pill")?.let { view ->
                if (view.layoutParams is ViewGroup.MarginLayoutParams) {
                    val property = margins.getOrPut(view) { OwnedHostProperty(
                        { (view.layoutParams as ViewGroup.MarginLayoutParams).marginEnd }, { value ->
                            (view.layoutParams as ViewGroup.MarginLayoutParams).also { it.marginEnd = value; view.layoutParams = it }
                        }) }
                    if ((view.layoutParams as ViewGroup.MarginLayoutParams).marginEnd != end) { property.set(end); changed = true }
                }
            }
        }
        listOf("seek_bar_controls", "live_radio_container").mapNotNull { find(root, it) }.forEach { container ->
            // Native media binding pads the container, including its time labels.
            // Keep the seekbar's own drawable/gesture padding intact.
            if (padding(container, end)) changed = true
        }
        return alignTransport(root) || changed
    }

    private fun alignTransport(root: View): Boolean {
        val play = find(root, "play_pause") ?: return false
        val parent = play.parent as? ViewGroup ?: return false
        val params = play.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        // The child's measured width still belongs to the previous padding. Use
        // the container's next content bounds so both updates settle together.
        val bounds = progressContent.layoutBoundsIn(parent) ?: return false
        val left = bounds.left
        val right = parent.width - bounds.right
        if (left < 0 || right < 0) return false
        val insets = if (parent.layoutDirection == View.LAYOUT_DIRECTION_RTL) right to left else left to right
        if (params.marginStart == insets.first && params.marginEnd == insets.second) return false
        transportMargins.getOrPut(play) { OwnedHostProperty(
            { (play.layoutParams as ViewGroup.MarginLayoutParams).let { it.marginStart to it.marginEnd } },
            { value ->
                (play.layoutParams as ViewGroup.MarginLayoutParams).also {
                    it.marginStart = value.first; it.marginEnd = value.second; play.layoutParams = it
                }
            }) }.set(insets)
        // Native previous/next constraints already follow play_pause. Move their
        // anchor's layout, so drawing, touch targets and ripples stay together.
        return true
    }

    override fun close() {
        transportMargins.values.toList().asReversed().forEach { it.close() }; transportMargins.clear()
        margins.values.toList().asReversed().forEach { it.close() }; margins.clear()
        paddings.values.toList().asReversed().forEach { it.close() }; paddings.clear()
        guides.values.toList().asReversed().forEach { it.close() }; guides.clear()
    }
}
