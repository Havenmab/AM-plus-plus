package dev.amenhancer.module.config

/**
 * Tablet chrome style: how the module renders the tablet top navigation and the
 * bottom mini player. [AUTHOR] keeps the existing liquid-glass rendering that the
 * original project shipped; [IPAD] renders the iPad-like top bar and mini player.
 */
enum class TabletChromeStyle(
    val storageValue: String,
    val displayName: String,
) {
    AUTHOR(storageValue = "author", displayName = "原有玻璃风格"),
    IPAD(storageValue = "ipad", displayName = "iPad 风格");

    companion object {
        /** Unknown or missing values fall back to [AUTHOR] so an old store stays on the shipped behaviour. */
        fun decode(raw: String?): TabletChromeStyle = values().firstOrNull {
            it.storageValue.equals(raw?.trim(), ignoreCase = true)
        } ?: AUTHOR
    }
}
