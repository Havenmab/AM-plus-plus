package dev.amenhancer.module.hook

/** Geometry comes from the native slot, never the playback-scaled image or the window. */
internal object FragmentArtworkRecoveryPolicy {
    data class SlotSize(val width: Int, val height: Int)

    fun nativeSquare(width: Int, height: Int, laidOut: Boolean): SlotSize? =
        if (laidOut && width > 1 && height > 1 &&
            kotlin.math.abs(width.toLong() - height.toLong()) <= 1L) SlotSize(width, height) else null

    fun canRecover(
        shown: Boolean,
        laidOut: Boolean,
        sheetState: Int?,
        entering: Boolean,
        sharedElement: Boolean,
        videoMode: Boolean,
        sizeAnimationRunning: Boolean,
    ): Boolean = shown && laidOut && sheetState == 3 && !entering && !sharedElement &&
        !videoMode && !sizeAnimationRunning

    fun needsResize(imageWidth: Int, imageHeight: Int, slot: SlotSize): Boolean =
        (imageWidth > 0 && imageWidth != slot.width) ||
            (imageHeight > 0 && imageHeight != slot.height)
}
