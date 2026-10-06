/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLE's catalog-language cache contract, ported with the resolver: a language-specific
 * compatibility entry is eligible only when the caller already knows the original language, and
 * every accepted alias must match the source language it was looked up for.
 */
class AppleOriginalLanguageCacheTest {
    private val japaneseTranslation = AppleInternalCatalogResolver.Alias(
        title = "ザ・ウィークエンド",
        artist = "ザ・ウィークエンド",
        language = "ja-JP",
    )

    @Test
    fun `unknown artist origin does not turn a regional translation into an original`() {
        val artistId = "479756766"
        val regionalCache = mapOf(
            AppleInternalCatalogResolver.originalEntityCacheKey(
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                "ja-JP",
                artistId,
            ) to japaneseTranslation,
        )
        val keys = AppleInternalCatalogResolver.originalEntityCacheLookupKeys(
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            artistId,
        )

        assertTrue(keys.none(regionalCache::containsKey))
        assertFalse(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(japaneseTranslation, "current"),
        )
        assertFalse(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(japaneseTranslation, ""),
        )
    }

    @Test
    fun `known original language rejects a different language even for an exact ID`() {
        assertFalse(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(japaneseTranslation, "ko-KR"),
        )
        assertFalse(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(
                japaneseTranslation,
                "zh-Hans-CN",
            ),
        )
        assertEquals(
            null,
            AppleInternalCatalogResolver.selectExactOriginalEntityAlias(
                mediaId = "479756766",
                lookupIds = listOf("479756766"),
                resolved = mapOf("479756766" to japaneseTranslation),
                sourceLanguage = "ko-KR",
            ),
        )
    }

    @Test
    fun `wrong language direct cache cannot hide a matching alternate ID`() {
        val correct = AppleInternalCatalogResolver.Alias("아이유", "아이유", "ko-KR")
        val keys = AppleInternalCatalogResolver.originalEntityCacheLookupKeys(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            mediaId = "1",
            lookupIds = listOf("2"),
            languages = listOf("ko-KR"),
        )
        val cache = mapOf(
            AppleInternalCatalogResolver.originalDirectEntityCacheKey(
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                "1",
            ) to japaneseTranslation,
            AppleInternalCatalogResolver.originalEntityCacheKey(
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                "ko-KR",
                "2",
            ) to correct,
        )
        val selected = keys.firstNotNullOfOrNull { key ->
            cache[key]?.takeIf {
                AppleInternalCatalogResolver.isAcceptableOriginalAlias(it, "ko-KR")
            }
        }
        assertEquals(correct, selected)
    }

    @Test
    fun `validated direct original stays available before song language is known`() {
        val artistId = "18756224"
        val original = AppleInternalCatalogResolver.Alias("宇多田ヒカル", "宇多田ヒカル", "ja-JP")
        val cache = mapOf(
            AppleInternalCatalogResolver.originalDirectEntityCacheKey(
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                artistId,
            ) to original,
        )
        val keys = AppleInternalCatalogResolver.originalEntityCacheLookupKeys(
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            artistId,
        )
        assertEquals(original, keys.firstNotNullOfOrNull(cache::get))
        assertTrue(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(original, "ja-JP"),
        )
    }

    @Test
    fun `canonical Chinese tags match and legitimate Latin Japanese names remain accepted`() {
        assertTrue(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(
                AppleInternalCatalogResolver.Alias("晴天", "周杰伦", "zh-CN"),
                "zh-Hans-CN",
            ),
        )
        val original = AppleInternalCatalogResolver.Alias("HANA", "HANA", "ja-JP")
        assertTrue(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(original, "ja-JP"),
        )
        assertEquals(
            original,
            AppleInternalCatalogResolver.selectExactOriginalEntityAlias(
                "1",
                listOf("1"),
                mapOf("1" to original),
                "ja-JP",
            ),
        )
        assertFalse(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(
                AppleInternalCatalogResolver.Alias("", "", "ja-JP"),
                "ja-JP",
            ),
        )
    }

    @Test
    fun `old originals and derived artist regions cannot repopulate the new cache`() {
        AppleInternalCatalogResolver.LocalizedEntityType.entries.forEach { type ->
            val keys = AppleInternalCatalogResolver.originalEntityCacheLookupKeys(
                type,
                "479756766",
                languages = listOf("ja-JP"),
            )
            assertTrue(keys.all { it.startsWith("V3:") })
            assertTrue(keys.none { it.startsWith("V2:") })
        }
        assertTrue(AppleInternalCatalogResolver.originalSongCacheKey("1363310482").startsWith("V3:"))
    }

    @Test
    fun `ordinary rhythm and blues identity does not provide Japanese origin evidence`() {
        assertEquals(
            emptyList<String>(),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                null,
                listOf("R&B/灵魂乐", "音乐"),
                "USUG11800560",
            ),
        )
        assertEquals(
            null,
            inferredOriginalArtistLanguage(
                kind = InAppLibraryEntityKind.ARTIST,
                artist = "Abel Tesfaye",
                associatedArtistIds = listOf("479756766"),
                genres = listOf("R&B/灵魂乐", "音乐"),
            ),
        )
    }
}
