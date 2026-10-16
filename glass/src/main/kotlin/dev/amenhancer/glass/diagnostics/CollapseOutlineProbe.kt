package dev.amenhancer.glass.diagnostics

import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle

/** Diagnostic A/B only: compare Rounded outlines against the original Generic curve. */
object CollapseOutlineProbe {
    private val original = Capsule()
    private val rounded = Capsule(RoundedCornerStyle.Circular)

    fun capsule(enabled: Boolean): Capsule = if (enabled) rounded else original
}
