package dev.amenhancer.module.ui

import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.OnlineLyricSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings page publishes the search tier into the unified order.
 *
 * Schema 29 replaced the fork's separate chain with one list: the fixed repositories followed by
 * the enabled search sources. The runtime reads that list whenever the automatic flag is on, so a
 * page that only wrote the legacy order key would leave the unified list one-way - a source the
 * user switched on afterwards could never run.
 */
class OnlineLyricsOrderWriteTest {

    private val fixed = listOf(
        CustomLyricsSources.AM_NATIVE,
        CustomLyricsSources.AMLL,
        CustomLyricsSources.AM_LYRICS,
        CustomLyricsSources.LUNABEAT,
    )

    private fun settings(
        order: String = OnlineLyricSources.DEFAULT_ORDER_STORAGE,
        unified: List<String> = fixed,
    ) = ModuleSettings(onlineLyricsSourceOrder = order, automaticLyricsSourceOrder = unified)

    @Test
    fun `switching a source off keeps it in the visible order but drops it from the unified tail`() {
        val next = settings()
            .withOnlineLyricsSourceEnabled(CustomLyricsSources.KUWO, false)
            .withUnifiedSearchTier()

        assertEquals(
            OnlineLyricSources.normalizeOrder(next.onlineLyricsSourceOrder),
            next.onlineLyricsSourceOrder.split(","),
        )
        assertTrue(CustomLyricsSources.KUWO in next.onlineLyricsSourceOrder.split(","))
        assertFalse(CustomLyricsSources.KUWO in next.automaticLyricsSourceOrder)
        assertEquals(
            fixed + listOf(
                CustomLyricsSources.NETEASE,
                CustomLyricsSources.QQ,
                CustomLyricsSources.KUGOU,
            ),
            next.automaticLyricsSourceOrder,
        )
    }

    @Test
    fun `switching the only source on appends it after the fixed repositories`() {
        val onlyQqOff = settings().copy(
            onlineLyricsSourceNeteaseEnabled = false,
            onlineLyricsSourceQqEnabled = false,
            onlineLyricsSourceKuwoEnabled = false,
            onlineLyricsSourceKugouEnabled = false,
        )
        val next = onlyQqOff
            .withOnlineLyricsSourceEnabled(CustomLyricsSources.QQ, true)
            .withUnifiedSearchTier()

        assertEquals(fixed + CustomLyricsSources.QQ, next.automaticLyricsSourceOrder)
    }

    @Test
    fun `the unified tail follows the visible order`() {
        val next = settings(order = "qq,netease,kuwo,kugou")
            .withUnifiedSearchTier()

        assertEquals(
            fixed + listOf(
                CustomLyricsSources.QQ,
                CustomLyricsSources.NETEASE,
                CustomLyricsSources.KUWO,
                CustomLyricsSources.KUGOU,
            ),
            next.automaticLyricsSourceOrder,
        )
        assertEquals("qq,netease,kuwo,kugou", next.onlineLyricsSourceOrder)
    }

    @Test
    fun `a store that lost a fixed provider gets it back before the search tail`() {
        val next = settings(unified = listOf(CustomLyricsSources.AM_NATIVE))
            .withUnifiedSearchTier()

        assertEquals(fixed + OnlineLyricSources.DEFAULT_ORDER, next.automaticLyricsSourceOrder)
    }

    @Test
    fun `writing the tier twice is a no-op`() {
        val once = settings().withUnifiedSearchTier()
        assertEquals(once.automaticLyricsSourceOrder, once.withUnifiedSearchTier().automaticLyricsSourceOrder)
        assertEquals(once.onlineLyricsSourceOrder, once.withUnifiedSearchTier().onlineLyricsSourceOrder)
    }
}
