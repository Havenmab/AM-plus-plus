package dev.amenhancer.module.hook

internal object TabletChromeHeaderPolicy {
    fun enabled(activated: Boolean, menuReady: Boolean, capsuleMounted: Boolean, onTabPage: Boolean): Boolean =
        activated && menuReady && capsuleMounted && onTabPage

    fun ignoresDependency(active: Boolean, isContent: Boolean, isAppBar: Boolean, sameParent: Boolean): Boolean =
        active && isContent && isAppBar && sameParent
}
