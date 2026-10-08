package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.json.JSONObject

/** Resolve the complete verified descriptor set before installing any native repair hook. */
internal class FragmentPlayerRecoveryContract(private val symbols: TargetSymbolResolver, build: TargetBuild) {
    val names: JSONObject = checkNotNull(AppleMusicHostProfiles.find(
        build.packageName, build.versionName, build.versionCode,
    )).document.getJSONObject("playerRecovery")

    private fun method(id: String): Method = requireNotNull(symbols.resolve(TargetSymbolKey<Method>(
        id = id, profilePolicy = ProfilePolicy.EXACT_REQUIRED,
        structuralCandidates = { emptyList() }, identity = { it.toGenericString() },
    )).valueOrNull()) { "Missing verified player recovery method: $id" }.apply { isAccessible = true }

    private fun field(id: String): Field = requireNotNull(symbols.resolve(TargetSymbolKey<Field>(
        id = id, profilePolicy = ProfilePolicy.EXACT_REQUIRED,
        structuralCandidates = { emptyList() }, identity = { it.toGenericString() },
    )).valueOrNull()) { "Missing verified player recovery field: $id" }.apply { isAccessible = true }

    val createView = method("player-controller-create-view")
    val getView = method("player-fragment-view")
    val resume = method("player-controller-resume")
    val destroyView = method("player-controller-destroy-view")
    val fragmentDestroyView = method("player-fragment-destroy-view")
    val paneViewCreated = method("player-pane-view-created")
    val resizeArtwork = method("player-artwork-resize")
    val parentFragment = method("player-fragment-parent")
    val backgroundDetach = method("player-background-detach")
    val backgroundArtwork = method("player-background-artwork")

    val videoMode = field("player-artwork-video-mode")
    val sizeAnimation = field("player-artwork-size-animation")
    val baselineSize = field("player-artwork-static-baseline")
    val targetSize = field("player-artwork-target-size")
    val entering = field("player-pane-enter-running")
    val sharedElement = field("player-pane-shared-running")
    val behavior = field("player-page-behavior")
    val behaviorState = field("player-page-behavior-state")
    val backgroundSource = field("player-background-source")
    val backgroundQueued = field("player-background-queued")
    val backgroundDerived = field("player-background-derived")
    val backgroundShader = field("player-background-shader")
    val backgroundPreviousShader = field("player-background-previous-shader")
    val backgroundPaint = field("player-background-paint")
    val backgroundPreviousPaint = field("player-background-previous-paint")
    val backgroundFade = field("player-background-fade")

    companion object {
        fun supports(build: TargetBuild): Boolean = AppleMusicHostProfiles.find(
            build.packageName, build.versionName, build.versionCode,
        )?.let { it.productionEnabled && it.family == "fragment-content" && it.capability("playerRecovery") } == true
    }
}
