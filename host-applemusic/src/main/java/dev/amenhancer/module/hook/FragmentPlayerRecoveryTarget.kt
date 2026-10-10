package dev.amenhancer.module.hook

/** One dormant registration scope owns both repairs; partial registration cannot affect AM. */
internal class FragmentPlayerRecoveryTarget(
    private val symbols: TargetSymbolResolver,
    private val build: TargetBuild,
    private val register: (FragmentPlayerRecoveryContract, HookRegistrationScope) -> Unit = { contract, scope ->
        FragmentPlayerArtworkRecovery(contract, scope).install()
        FragmentPlayerBackgroundRecovery(contract, scope).install()
    },
) : PlayerRecoveryTarget {
    private val scope = HookRegistrationScope()
    private var installed: TargetCapabilityInstall? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        installed?.let { return it }
        val result = if (!FragmentPlayerRecoveryContract.supports(build)) {
            TargetCapabilityInstall.Unsupported("Native player recovery requires a verified Fragment player profile")
        } else {
            runCatching {
                val contract = FragmentPlayerRecoveryContract(symbols, build)
                register(contract, scope)
                scope.activate()
                TargetCapabilityInstall.Active("Native static artwork measurement and background lifecycle recovery installed")
            }.getOrElse { error ->
                scope.close()
                TargetCapabilityInstall.Degraded("Native player recovery registration failed: ${error.javaClass.simpleName}")
            }
        }
        if (result !is TargetCapabilityInstall.Active) scope.close()
        installed = result
        return result
    }
}
