package dev.amenhancer.module.hook

/**
 * Process-wide hand-off of the tablet chrome command surface.
 *
 * [AppleMusicTabletChromeTarget] is built by the target adaptation (Application.onCreate), while the
 * chrome session is created later by the glass resource hook, so there is no direct object path
 * between them. The install phase publishes the adapter here and the session reads it. Null means
 * "no command surface": the chrome renders its controls disabled and the feature reports DEGRADED,
 * rather than the session guessing a host method.
 */
internal object TabletChromeRuntime {
    @Volatile
    var commands: TabletChromeCommands? = null
}
