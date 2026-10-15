package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsIndexPointer
import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsManifest

/**
 * What a 「清空自定义歌词」 action must retire: the empty manifest that replaces
 * the current one, how many configured entries disappear, and every file the
 * reset leaves unreferenced.
 */
data class CustomLyricsClearPlan(
    /** Always [CustomLyricsManifest.empty]: every mapping is removed. */
    val remainingManifest: CustomLyricsManifest,
    /** How many configured entries are removed; the confirmation/toast count. */
    val removedEntries: Int,
    /**
     * Every file the reset leaves unreferenced: each entry's TTML body plus the
     * published index file. Distinct and validated, so no referenced or malformed
     * id reaches the file store.
     */
    val fileIds: List<String>,
)

/**
 * The pure half of the clear-all action: which entries disappear and which files
 * become unreferenced. Kept free of I/O so the orphan-file guarantee (every
 * entry file and the index file are enumerated, exactly once) is covered by JVM
 * tests.
 *
 * The file list is taken from the **raw** manifest, not a sanitized copy: an
 * entry whose size/hash metadata is malformed but whose file id is still
 * addressable must have its file deleted too, otherwise the clear would leave an
 * orphan behind. Only file ids the store itself would reject are skipped.
 */
object CustomLyricsClearPolicy {

    fun plan(
        manifest: CustomLyricsManifest,
        pointer: CustomLyricsIndexPointer?,
    ): CustomLyricsClearPlan {
        val fileIds = buildList {
            manifest.entries.forEach { entry -> add(entry.fileId) }
            pointer?.fileId?.let(::add)
        }.filter(CustomLyricsManifestPolicy::isValidFileId).distinct()
        return CustomLyricsClearPlan(
            remainingManifest = CustomLyricsManifest.empty(),
            removedEntries = manifest.entries.size,
            fileIds = fileIds,
        )
    }
}

/** The outcome of one clear-all attempt. */
sealed interface CustomLyricsClearResult {
    data class Cleared(
        val removedEntries: Int,
        /** Files the store confirmed deleted; equal to the plan for a clean store. */
        val removedFiles: Int,
    ) : CustomLyricsClearResult

    data class Failed(val message: String) : CustomLyricsClearResult
}

/**
 * Deletes every custom-lyrics entry and its stored files.
 *
 * The remote index pointer (and its legacy manifest key) is reset **first**: only
 * once that reset is published are the now-unreferenced entry and index files
 * retired. A failed reset therefore leaves the previous mappings and all their
 * files intact, and a successful reset can never leave a file an entry still
 * references. Every file the plan names is passed to the store, so no orphan
 * file remains.
 */
class CustomLyricsClearTransaction(
    private val resetIndex: () -> Boolean,
    private val deleteRemoteFile: (String) -> Boolean,
) {

    fun clear(
        oldManifest: CustomLyricsManifest,
        pointer: CustomLyricsIndexPointer?,
    ): CustomLyricsClearResult {
        val plan = CustomLyricsClearPolicy.plan(oldManifest, pointer)
        if (!runCatching { resetIndex() }.getOrDefault(false)) {
            return CustomLyricsClearResult.Failed("无法重置歌词索引")
        }
        var removedFiles = 0
        plan.fileIds.forEach { fileId ->
            val deleted = runCatching { deleteRemoteFile(fileId) }.getOrDefault(false)
            if (deleted) removedFiles++
        }
        return CustomLyricsClearResult.Cleared(
            removedEntries = plan.removedEntries,
            removedFiles = removedFiles,
        )
    }
}
