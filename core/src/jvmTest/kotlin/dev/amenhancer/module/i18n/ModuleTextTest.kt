package dev.amenhancer.module.i18n

import dev.amenhancer.module.config.CatalogLanguagePolicy
import dev.amenhancer.module.config.TitleCorrectionMode
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ModuleTextTest {
    @Test fun `Chinese locales retain the Chinese interface`() {
        for (tag in listOf("zh-CN", "zh-SG", "zh-TW", "zh-HK", "zh-Hans", "zh-Hant")) {
            assertEquals("AM++ 模块设置", ModuleText.MODULE_SETTINGS.text(locale = Locale.forLanguageTag(tag)))
        }
    }

    @Test fun `unsupported languages fall back to English`() {
        for (locale in listOf(Locale.ENGLISH, Locale.US, Locale.UK, Locale.FRENCH, Locale.JAPANESE,
            Locale.forLanguageTag("ar"), Locale.ROOT)) {
            assertEquals("AM++ module settings", ModuleText.MODULE_SETTINGS.text(locale = locale))
            assertEquals("Plugins", ModuleText.PLUGINS.text(locale = locale))
        }
    }

    @Test fun `explicit host locale is resolved again instead of cached`() {
        assertEquals("保存", ModuleText.SAVE.text(locale = Locale.CHINESE))
        assertEquals("Save", ModuleText.SAVE.text(locale = Locale.ENGLISH))
        assertEquals("保存", ModuleText.SAVE.text(locale = Locale.CHINESE))
        assertEquals("固定日本", TitleCorrectionMode.JAPAN.displayName(Locale.CHINESE))
        assertEquals("Use Japan", TitleCorrectionMode.JAPAN.displayName(Locale.ENGLISH))
    }

    @Test fun `formatting preserves song names and plugin data verbatim`() {
        val song = "我的歌 · 100% · \$x"
        assertEquals("Delete “$song” and its Apple Music ID-to-TTML mappings (2)?",
            ModuleText.DELETE_LYRICS_CONFIRM.text(song, 2, locale = Locale.ENGLISH))
        assertEquals("删除“$song”及其 2 个 Apple Music ID 的 TTML 映射？",
            ModuleText.DELETE_LYRICS_CONFIRM.text(song, 2, locale = Locale.CHINESE))
        assertEquals("Invalid relative path: assets/我的歌.txt",
            ModuleText.PLUGIN_PATH_INVALID.text("assets/我的歌.txt", locale = Locale.ENGLISH))
    }

    @Test fun `all translations have complete matching format arguments`() {
        val placeholder = Regex("%(\\d+)\\\$s")
        for (key in ModuleText.values()) {
            val englishArgs = placeholder.findAll(key.english).map { it.groupValues[1].toInt() }.toList()
            val chineseArgs = placeholder.findAll(key.chinese).map { it.groupValues[1].toInt() }.toList()
            assertEquals(key.name, englishArgs.sorted(), chineseArgs.sorted())
            assertFalse(key.name, key.english.any { it in '\u3400'..'\u9fff' })
            assertTrue(key.name, key.english.isNotBlank())
            assertTrue(key.name, key.chinese.isNotBlank())
            val count = englishArgs.maxOrNull() ?: 0
            assertEquals(key.name, (1..count).toList(), englishArgs.distinct().sorted())
            val args = (1..count).map { "ARG_$it" }.toTypedArray()
            for (locale in listOf(Locale.ENGLISH, Locale.CHINESE)) {
                val rendered = key.text(*args, locale = locale)
                assertFalse(key.name, placeholder.containsMatchIn(rendered))
                args.forEach { assertTrue(key.name, rendered.contains(it)) }
            }
        }
    }

    @Test fun `locale selection leaves catalog language and storage values intact`() {
        assertEquals("mainland_china", TitleCorrectionMode.MAINLAND_CHINA.storageValue)
        assertEquals("zh-CN", TitleCorrectionMode.MAINLAND_CHINA.catalogLanguage)
        assertEquals("Unchanged (follow Apple Music)", CatalogLanguagePolicy.displayName(null, Locale.ENGLISH))
        assertEquals("不改写（跟随 Apple Music）", CatalogLanguagePolicy.displayName(null, Locale.CHINESE))
        assertEquals("Japanese (ja-JP)", CatalogLanguagePolicy.displayName("ja-JP", Locale.ENGLISH))
    }
}
