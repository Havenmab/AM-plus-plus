package dev.amenhancer.module.hook

import dev.amenhancer.module.config.CatalogLanguagePolicy
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogLanguageCompositionTest {
    @Test
    fun ordinaryCatalogRequestReceivesConfiguredTargetLanguage() {
        val source = linkedMapOf<Any?, Any?>(
            "l" to "en-US",
            "Accept-Language" to "en-US",
        )

        val raw = CatalogLanguageRewritePolicy.withRawTagLanguageValue(source, "ja-JP")
        val headers = CatalogLanguageRewritePolicy.withHeaderLanguageValue(source, "ja-JP")

        assertEquals("ja-JP", raw["l"])
        // The tag goes out unchanged, as in HLE: Apple picks the storefront's own
        // localization from this header, so downgrading it (ja-JP -> ja) changed the answer.
        assertEquals("ja-JP", headers["Accept-Language"])
    }

    @Test
    fun hleTokenizedOriginalLanguageRequestBypassesTargetLanguageRewrite() {
        val source = linkedMapOf<Any?, Any?>(
            "l" to "ko-KR",
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM to "hle-42",
        )

        val rewritten = CatalogLanguageRewritePolicy.withRawTagLanguageValue(source, "ja-JP")

        assertSame(source, rewritten)
        assertEquals("ko-KR", rewritten["l"])
    }

    @Test
    fun acceptLanguageWritesTheConfiguredTagUnchanged() {
        // Same contract as HLE's `Accept-Language = language`: the region's own tag is written
        // through, never mapped to a language-only or script spelling.  The removed mapper sent
        // "ja-JP" as "ja", "zh-CN" as "zh-Hans" and "zh-TW" as "zh-Hant".
        assertEquals("ja-JP", CatalogLanguagePolicy.normalize("ja-JP"))
        assertEquals("ko-KR", CatalogLanguagePolicy.normalize("ko-KR"))
        assertEquals("zh-Hans", CatalogLanguagePolicy.normalize("zh-Hans"))
        assertEquals("zh-CN", CatalogLanguagePolicy.normalize("zh-CN"))
        assertEquals("zh-Hant", CatalogLanguagePolicy.normalize("zh-Hant"))
        assertEquals("zh-TW", CatalogLanguagePolicy.normalize("zh-TW"))

        val source = linkedMapOf<Any?, Any?>("Accept-Language" to "en-US")
        assertEquals(
            "ja-JP",
            CatalogLanguageRewritePolicy.withHeaderLanguageValue(source, "ja-JP")["Accept-Language"],
        )
        assertEquals(
            "zh-TW",
            CatalogLanguageRewritePolicy.withHeaderLanguageValue(source, "zh-TW")["Accept-Language"],
        )
    }
}
