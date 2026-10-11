package dev.amenhancer.module.hook

/** Installs only the exact Fragment profile selected by the composition factory. */
internal class FragmentDualPaneTarget(private val symbols: TargetSymbolResolver, private val targetBuild: TargetBuild) : DualPaneTarget {
    private val registration = HookRegistrationScope()
    private var installed: TargetCapabilityInstall? = null
    @Synchronized override fun install(): TargetCapabilityInstall {
        installed?.let { return it }
        val result = runCatching {
            val profile = checkNotNull(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(
                targetBuild.packageName, targetBuild.versionName, targetBuild.versionCode))
            check(profile.productionEnabled && profile.family == "fragment-content" && profile.document.has("fragmentDualPane"))
            val create = checkNotNull(symbols.resolve(AppleMusicSymbols.PlayerControllerCreateView).valueOrNull())
            FragmentTabletDualPaneCoordinator.install(checkNotNull(create.declaringClass.classLoader), targetBuild, registration)
            TargetCapabilityInstall.Active("${targetBuild.displayName} player: native SONG/QUEUE left, native lyrics right, native artwork/video animation")
        }.getOrElse { error ->
            registration.close()
            ModernXposedRuntime.log("${targetBuild.displayName} dual-pane registration failed; restoring native player", error)
            TargetCapabilityInstall.Degraded("${targetBuild.displayName} player contract failed: ${error.message}")
        }
        if (result is TargetCapabilityInstall.Active) registration.activate() else registration.close()
        installed = result
        return result
    }
}
