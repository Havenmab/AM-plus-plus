package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import org.json.JSONObject

/** Installation-time aggregate: complete dependencies resolve before registration starts. */
internal data class FragmentDualPaneBindings(
    val main: FragmentDualPaneContract,
    val lyrics: FragmentLyricsRuntimeContract,
    val karaoke: FragmentKaraokeWidthContract,
    val video: FragmentTabletVideoContract,
)

internal object FragmentDualPanePreflight {
    fun resolve(loader: ClassLoader, profile: AppleMusicHostProfile): FragmentDualPaneBindings {
        val errors = ArrayList<String>()
        val sections = LinkedHashMap<String, Pair<JSONObject, FragmentProfileMembers>>()
        fun section(name: String, required: FragmentMemberRequirements, json: JSONObject? = profile.document.optJSONObject(name)) {
            if (json == null) { errors += "$name: missing required section"; return }
            runCatching { FragmentProfileMembers(loader, json, name, required) }
                .onSuccess { sections[name] = json to it }
                .onFailure { errors += it.message ?: "$name: ${it.javaClass.simpleName}" }
        }
        section("fragmentDualPane", FragmentDualPaneInventory.main)
        section("fragmentLyricsRuntime", FragmentDualPaneInventory.lyrics)
        section("fragmentKaraokeWidth", FragmentDualPaneInventory.karaoke)
        section("fragmentTabletVideoHandoff", FragmentDualPaneInventory.video)
        val mainNames = profile.document.optJSONObject("fragmentDualPane")
        val policy = mainNames?.optString("songLayoutPolicy")
        when (policy) {
            "native" -> if (mainNames.has("songLayout")) errors += "fragmentDualPane.songLayoutPolicy: native policy contains a layout override"
            "1606-column" -> section("fragmentDualPane.songLayout", FragmentDualPaneInventory.song, mainNames.optJSONObject("songLayout"))
            else -> errors += "fragmentDualPane.songLayoutPolicy: explicitly select native or 1606-column after layout comparison"
        }
        val review = mainNames?.optJSONObject("layoutReview")
        listOf("artwork", "metadata", "controls", "progress", "binding", "restoration").forEach { role ->
            if (review?.optString(role).isNullOrBlank()) errors += "fragmentDualPane.layoutReview.$role: missing comparison evidence"
        }
        check(errors.isEmpty()) { "${profile.id} dual-pane preflight failed:\n${errors.joinToString("\n")}" }
        fun <T> contract(name: String, create: (JSONObject, FragmentProfileMembers) -> T): T? =
            runCatching { sections.getValue(name).let { create(it.first, it.second) } }
                .onFailure { errors += "$name: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
        val song = if (policy == "1606-column") contract("fragmentDualPane.songLayout", ::FragmentTabletSongLayoutContract) else null
        val main = contract("fragmentDualPane") { _, members -> FragmentDualPaneContract(profile, members, song) }
        val lyrics = contract("fragmentLyricsRuntime") { _, members -> FragmentLyricsRuntimeContract(profile, members) }
        val karaoke = main?.let { contract("fragmentKaraokeWidth") { names, members -> FragmentKaraokeWidthContract(it.lyrics, names, members) } }
        val video = contract("fragmentTabletVideoHandoff", ::FragmentTabletVideoContract)
        check(errors.isEmpty()) { "${profile.id} dual-pane relationships failed:\n${errors.joinToString("\n")}" }
        return FragmentDualPaneBindings(checkNotNull(main), checkNotNull(lyrics), checkNotNull(karaoke), checkNotNull(video))
    }
}
