package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.host.OwnedHostProperty
import java.lang.reflect.Field

/** Own only the square slot's geometry; native card scale, video and other constraints stay native. */
internal class TabletArtworkLayoutLease(
    private val artwork: View,
    field: (Class<*>, String) -> Field?,
) : AutoCloseable {
    private val owned = ArrayList<AutoCloseable>()
    private val params get() = artwork.layoutParams as ViewGroup.MarginLayoutParams
    private fun <T> property(read: () -> T, write: (T) -> Unit) = OwnedHostProperty(read, write).also { owned += it }
    private val width = property({ params.width }, { params.width = it })
    private val height = property({ params.height }, { params.height = it })
    private val top = property({ params.topMargin }, { params.topMargin = it })
    private val bottom = property({ params.bottomMargin }, { params.bottomMargin = it })
    private val start = property({ params.marginStart }, { params.marginStart = it })
    private val end = property({ params.marginEnd }, { params.marginEnd = it })
    private val anchors = listOf("topToTop", "topToBottom", "bottomToTop", "bottomToBottom").map { name ->
        checkNotNull(field(params.javaClass, name)) { "artwork.$name missing" }
    }
    private val anchorProperties = anchors.map { member -> property({ member.getInt(params) }, { member.setInt(params, it) }) }
    private val horizontal = listOf("startToStart", "endToEnd").map { name ->
        checkNotNull(field(params.javaClass, name)) { "artwork.$name missing" }
    }
    private val horizontalProperties = horizontal.map { member -> property({ member.getInt(params) }, { member.setInt(params, it) }) }
    private val ratioField = checkNotNull(field(params.javaClass, "dimensionRatio")) { "artwork.dimensionRatio missing" }
    private val ratio = property({ ratioField.get(params) as String? }, { ratioField.set(params, it) })
    private var closed = false

    fun apply(sizePx: Int, topMarginPx: Int, startInsetPx: Int? = null, endInsetPx: Int? = null): Boolean {
        check(!closed)
        require(sizePx > 0)
        val values = listOf(0, -1, -1, -1)
        val alignContent = startInsetPx != null && endInsetPx != null && startInsetPx >= 0 && endInsetPx >= 0
        val horizontalMatches = !alignContent || (params.marginStart == startInsetPx && params.marginEnd == endInsetPx &&
            horizontal.all { it.getInt(params) == 0 })
        if (params.width == sizePx && params.height == sizePx && params.topMargin == topMarginPx &&
            params.bottomMargin == 0 && anchors.indices.all { anchors[it].getInt(params) == values[it] } &&
            ratioField.get(params) == null && horizontalMatches) return false
        width.set(sizePx); height.set(sizePx); top.set(topMarginPx); bottom.set(0)
        anchorProperties.forEachIndexed { index, property -> property.set(values[index]) }
        ratio.set(null)
        if (alignContent) {
            start.set(checkNotNull(startInsetPx)); end.set(checkNotNull(endInsetPx))
            horizontalProperties.forEach { it.set(0) }
        }
        artwork.layoutParams = params
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        owned.asReversed().forEach { it.close() }
        owned.clear()
        artwork.layoutParams = params
    }
}
