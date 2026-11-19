package dev.amenhancer.module.ui

import dev.amenhancer.module.model.ModuleSettings

/** Dialog-owned settings remain available for retry when persistence fails. */
internal class EmbeddedSettingsDraft(
    initial: ModuleSettings,
    private val persist: (ModuleSettings) -> Boolean,
) {
    var settings: ModuleSettings = initial
        private set

    fun update(next: ModuleSettings): Boolean {
        settings = next
        return save()
    }

    fun updateCellularDataEntry(enabled: Boolean): Boolean =
        update(settings.copy(forceCellularDataEntryEnabled = enabled))

    /**
     * Applies [change] to the latest draft and writes it.
     *
     * This is what a row must use when nothing else persists it. A cleared
     * "custom lyrics source" address is exactly that case: the store already
     * keeps the empty string, so only the write was missing.
     */
    fun saveChange(change: (ModuleSettings) -> ModuleSettings): Boolean = update(change(settings))

    /**
     * Applies [change] to the draft **without** writing it.
     *
     * Only for a row whose key-scoped controller write already landed (the
     * automatic-order key and the automatic on/off flag). Everything else that
     * took this path stayed in memory and came back from disk on the next launch.
     */
    fun mirrorSavedChange(change: (ModuleSettings) -> ModuleSettings) {
        settings = change(settings)
    }

    fun save(): Boolean = persist(settings)
}
