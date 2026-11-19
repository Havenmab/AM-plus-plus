package dev.amenhancer.module.config

import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 28 -> 29 source-order migration.
 *
 * A fork store at 28 keeps its search chain in the per-source enable flags plus a separate order,
 * and its fixed providers have no stored order at all; schema 29 publishes one unified order.
 * These samples pin the three rules the migration promises: a source the user switched off never
 * comes back, the user's stored order wins over the built-in one, and the retired prefer-native
 * preference decides where AM native sits.
 */
class AutomaticLyricsOrderMigrationTest {

    private fun store(vararg values: Pair<String, Any>): Map<String, Any> =
        linkedMapOf<String, Any>("schema_version" to 28).apply {
            values.forEach { (key, value) -> put(key, value) }
        }

    private val fixedNativeFirst = listOf(
        CustomLyricsSources.AM_NATIVE,
        CustomLyricsSources.AMLL,
        CustomLyricsSources.AM_LYRICS,
        CustomLyricsSources.LUNABEAT,
    )

    @Test
    fun `an automatic supplement store keeps the built-in order and appends the search chain`() {
        val settings = ModuleSettingsSchema.decode(
            store(
                "online_lyrics_supplement_enabled" to true,
            ),
        )
        assertEquals(
            fixedNativeFirst + listOf(
                CustomLyricsSources.NETEASE,
                CustomLyricsSources.QQ,
                CustomLyricsSources.KUWO,
                CustomLyricsSources.KUGOU,
            ),
            settings.automaticLyricsSourceOrder,
        )
    }

    @Test
    fun `a custom order keeps the user's sequence and drops the disabled source`() {
        val settings = ModuleSettingsSchema.decode(
            store(
                "online_lyrics_supplement_enabled" to true,
                "online_lyrics_source_order" to "qq,kuwo,netease,kugou",
                "online_lyrics_automatic_order_enabled" to false,
                "online_lyrics_source_netease_enabled" to false,
            ),
        )
        // The user's stored search order wins and the disabled source is dropped; AM native is
        // first because a fork store without the retired switch still preferred native.
        assertEquals(
            listOf(
                CustomLyricsSources.AM_NATIVE,
                CustomLyricsSources.AMLL,
                CustomLyricsSources.AM_LYRICS,
                CustomLyricsSources.LUNABEAT,
                CustomLyricsSources.QQ,
                CustomLyricsSources.KUWO,
                CustomLyricsSources.KUGOU,
            ),
            settings.automaticLyricsSourceOrder,
        )
    }

    @Test
    fun `a store without the supplement keeps native first and adds no search source`() {
        val settings = ModuleSettingsSchema.decode(
            store(
                "online_lyrics_supplement_enabled" to false,
                "online_lyrics_source_order" to "qq,kuwo,netease,kugou",
            ),
        )
        assertEquals(fixedNativeFirst, settings.automaticLyricsSourceOrder)
    }

    @Test
    fun `an explicit prefer-native choice is folded into the fixed providers`() {
        val settings = ModuleSettingsSchema.decode(
            store(
                "online_lyrics_supplement_enabled" to true,
                "automatic_lyrics_prefer_native" to false,
            ),
        )
        assertEquals(
            listOf(
                CustomLyricsSources.AMLL,
                CustomLyricsSources.AM_LYRICS,
                CustomLyricsSources.AM_NATIVE,
                CustomLyricsSources.LUNABEAT,
                CustomLyricsSources.NETEASE,
                CustomLyricsSources.QQ,
                CustomLyricsSources.KUWO,
                CustomLyricsSources.KUGOU,
            ),
            settings.automaticLyricsSourceOrder,
        )
    }

    @Test
    fun `every disabled search source stays out of the migrated order`() {
        val settings = ModuleSettingsSchema.decode(
            store(
                "online_lyrics_supplement_enabled" to true,
                "online_lyrics_source_netease_enabled" to false,
                "online_lyrics_source_qq_enabled" to false,
                "online_lyrics_source_kuwo_enabled" to false,
                "online_lyrics_source_kugou_enabled" to false,
            ),
        )
        assertEquals(fixedNativeFirst, settings.automaticLyricsSourceOrder)
    }

    @Test
    fun `an upstream only store keeps upstream's own legacy handling`() {
        val settings = ModuleSettingsSchema.decode(
            mapOf(
                "schema_version" to 21,
                "automatic_lyrics_source_order" to "amll-ttml-db,am-lyrics,am-native,lunabeat-ttml-hub",
            ),
        )
        // No fork key, so the migration stays out: an upstream store keeps upstream's own
        // order, where AM native sits between AM-Lyrics and Lunabeat.
        assertEquals(
            listOf(
                CustomLyricsSources.AMLL,
                CustomLyricsSources.AM_LYRICS,
                CustomLyricsSources.AM_NATIVE,
                CustomLyricsSources.LUNABEAT,
            ),
            settings.automaticLyricsSourceOrder,
        )
    }

    @Test
    fun `a current store is not migrated again`() {
        val settings = ModuleSettingsSchema.decode(
            mapOf(
                "schema_version" to 29,
                "online_lyrics_supplement_enabled" to true,
                "online_lyrics_source_netease_enabled" to false,
            ),
        )
        assertEquals(fixedNativeFirst, settings.automaticLyricsSourceOrder)
    }
}
