package dev.amenhancer.module.lyrics

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest

sealed interface CustomLyricsRestoreResult {
    data class Restored(val manifest: CustomLyricsManifest) : CustomLyricsRestoreResult
    data class Failed(val message: String) : CustomLyricsRestoreResult
}

/**
 * Conflict strategy applied per Apple Music ID when a restore meets both a
 * current entry and a backup entry with the same ID.
 */
enum class CustomLyricsRestorePolicy {
    /** Same-ID conflicts take the backup entry; the overwritten current file is retired. */
    OVERWRITE,

    /** Same-ID conflicts keep the current entry; the written backup file is dropped after publish. */
    KEEP_EXISTING,
}

/**
 * Merge-restore semantics: under [CustomLyricsRestorePolicy.OVERWRITE] backup
 * entries overwrite current entries with the same Apple Music ID; under
 * [CustomLyricsRestorePolicy.KEEP_EXISTING] same-ID conflicts keep the
 * current entry. Current-only IDs are kept and backup-only IDs are appended
 * under either policy. The backup's files arrive one at a time through the
 * caller-supplied stream; every one is written to a fresh remote fileId as it
 * arrives, the merged manifest is published once after the whole backup
 * scans, then files that no longer reference any entry are deleted: old files
 * of overwritten IDs under OVERWRITE, written-but-dropped backup files under
 * KEEP_EXISTING. Any scan, write, or publish failure rolls back only the new
 * files and leaves the old manifest and old files untouched. An empty backup
 * is a successful no-op.
 */
class CustomLyricsRestoreTransaction(
    private val fileIdFactory: () -> String,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishManifest: (CustomLyricsManifest) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
) {
    fun merge(
        oldManifest: CustomLyricsManifest,
        policy: CustomLyricsRestorePolicy = CustomLyricsRestorePolicy.OVERWRITE,
        streamBackup: (onFile: (fileId: String, bytes: ByteArray) -> Unit) -> CustomLyricsBackupDecodeResult,
    ): CustomLyricsRestoreResult {
        val allocatedFileIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        val newFileIds = linkedMapOf<String, String>()
        val written = mutableListOf<String>()
        var writeError: String? = null
        val scan = streamBackup { backupFileId, bytes ->
            if (writeError != null) return@streamBackup
            val newFileId = newFileIds[backupFileId]
            if (newFileId == null) {
                val generated = runCatching(fileIdFactory).getOrNull()
                    ?.takeIf(CustomLyricsManifestPolicy::isValidFileId)
                if (generated == null) {
                    writeError = ModuleText.LYRICS_FILE_ID_CREATE_FAILED.text()
                    return@streamBackup
                }
                if (generated in allocatedFileIds) {
                    writeError = ModuleText.LYRICS_UNIQUE_ID_FAILED.text()
                    return@streamBackup
                }
                allocatedFileIds += generated
                newFileIds[backupFileId] = generated
            }
            val target = newFileIds.getValue(backupFileId)
            if (!runCatching { writeRemoteFile(target, bytes) }.getOrDefault(false)) {
                runCatching { deleteRemoteFile(target) }
                writeError = ModuleText.LYRICS_FILE_WRITE_FAILED.text()
                return@streamBackup
            }
            written += target
        }
        return when (scan) {
            is CustomLyricsBackupDecodeResult.Rejected -> {
                written.forEach { runCatching { deleteRemoteFile(it) } }
                CustomLyricsRestoreResult.Failed(scan.message)
            }
            is CustomLyricsBackupDecodeResult.Decoded -> {
                val backup = scan.backup
                val error = writeError
                if (error != null) {
                    written.forEach { runCatching { deleteRemoteFile(it) } }
                    return CustomLyricsRestoreResult.Failed(error)
                }
                if (backup.manifest.entries.isEmpty()) {
                    written.forEach { runCatching { deleteRemoteFile(it) } }
                    return CustomLyricsRestoreResult.Restored(oldManifest)
                }
                val rebuilt = mutableListOf<CustomLyricsEntry>()
                val incomingById = linkedMapOf<Long, CustomLyricsEntry>()
                for (incoming in backup.manifest.entries) {
                    val fileId = newFileIds[incoming.fileId]
                    if (fileId == null) {
                        written.forEach { runCatching { deleteRemoteFile(it) } }
                        return CustomLyricsRestoreResult.Failed(ModuleText.BACKUP_CONTENT_MISSING.text())
                    }
                    val entry = incoming.copy(fileId = fileId)
                    if (incomingById.putIfAbsent(entry.appleMusicId, entry) != null) {
                        written.forEach { runCatching { deleteRemoteFile(it) } }
                        return CustomLyricsRestoreResult.Failed(ModuleText.BACKUP_ENTRIES_DUPLICATE.text())
                    }
                    rebuilt += entry
                }
                val currentIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
                val mergedEntries = mutableListOf<CustomLyricsEntry>()
                val retiredFileIds = mutableListOf<String>()
                val droppedFileIds = mutableListOf<String>()
                oldManifest.entries.forEach { current ->
                    val replacement = incomingById[current.appleMusicId]
                    when {
                        replacement == null -> mergedEntries += current
                        policy == CustomLyricsRestorePolicy.OVERWRITE -> {
                            mergedEntries += replacement
                            retiredFileIds += current.fileId
                        }
                        else -> {
                            mergedEntries += current
                            droppedFileIds += replacement.fileId
                        }
                    }
                }
                rebuilt.forEach { entry ->
                    if (entry.appleMusicId !in currentIds) mergedEntries += entry
                }
                val merged = CustomLyricsManifestPolicy.sanitize(CustomLyricsManifest(mergedEntries))
                if (!runCatching { publishManifest(merged) }.getOrDefault(false)) {
                    written.forEach { runCatching { deleteRemoteFile(it) } }
                    return CustomLyricsRestoreResult.Failed(ModuleText.LYRICS_MAPPING_PUBLISH_FAILED.text())
                }
                (retiredFileIds + droppedFileIds).forEach { runCatching { deleteRemoteFile(it) } }
                CustomLyricsRestoreResult.Restored(merged)
            }
        }
    }
}
