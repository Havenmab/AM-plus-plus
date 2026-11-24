package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HLE applies a metadata configuration change in three ordered phases: clear every state
 * owner, restore the models the runtime captured, then refresh the consumers.  The order is
 * the contract — restoring before clearing would put the old values back into maps that are
 * about to be emptied, and refreshing before restoring would rebind the old alias.
 *
 * Behaviour test: it drives the dispatcher itself, not the source text of its call site.
 */
class AppleMetadataConfigurationDispatcherTest {
    @Test
    fun `a configuration change clears, restores, then refreshes`() {
        val events = mutableListOf<String>()
        val dispatcher = AppleMetadataConfigurationDispatcher(
            clearStateOwners = listOf(
                { events += "clear.override-store" },
                { events += "clear.library" },
                { events += "clear.data-binding" },
                { events += "clear.listen-now" },
                { events += "clear.applier+deferred" },
            ),
            restoreCapturedModels = { events += "restore.captured-models" },
            scheduleConsumerRefresh = { events += "refresh.consumers" },
        )

        dispatcher.dispatch()

        assertEquals(
            listOf(
                "clear.override-store",
                "clear.library",
                "clear.data-binding",
                "clear.listen-now",
                "clear.applier+deferred",
                "restore.captured-models",
                "refresh.consumers",
            ),
            events,
        )
    }

    @Test
    fun `a dispatch with no state owner still restores and refreshes`() {
        val events = mutableListOf<String>()
        AppleMetadataConfigurationDispatcher(
            clearStateOwners = emptyList(),
            restoreCapturedModels = { events += "restore" },
            scheduleConsumerRefresh = { events += "refresh" },
        ).dispatch()

        assertEquals(listOf("restore", "refresh"), events)
    }
}
