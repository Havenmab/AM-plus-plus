package dev.amenhancer.module.hook

internal class TabletChromeArtworkState {
    var native: TabletChromeArtworkPolicy.Transform? = null
        private set
    var progress = 0f
        private set
    private var applied: TabletChromeArtworkPolicy.Transform? = null

    fun capture(transform: TabletChromeArtworkPolicy.Transform, slide: Float) {
        native = transform
        progress = slide.coerceIn(0f, 1f)
        applied = null
    }

    fun owns(current: TabletChromeArtworkPolicy.Transform): Boolean =
        native != null && current == (applied ?: native)

    fun recordApplied(transform: TabletChromeArtworkPolicy.Transform) {
        applied = transform.takeUnless { it == native }
    }

    fun takeRestoration(current: TabletChromeArtworkPolicy.Transform): TabletChromeArtworkPolicy.Transform? {
        val restore = native.takeIf { applied != null && current == applied }
        applied = null
        return restore
    }

    fun clear() {
        native = null
        progress = 0f
        applied = null
    }
}
