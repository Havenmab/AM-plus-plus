package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Alias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.OriginalLanguageResolution
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-language ordering of the original-name resolution.
 *
 * Device evidence (region = 简体中文（美国）, correction on, Turkish account storefront `tr`): the
 * identity response carried an account-catalog alias tagged with the song's own derived language
 * (`ja-JP`) whose title was the account's romanized form (`Harumeku`).  The resolver applied that
 * alias *instead of* querying the Japanese storefront, so the native form the region reports
 * (`春めく`) never won.  The identity's alias is now only a fallback: the origin-region lookup
 * always runs, its confident result wins, and the fallback stands in only when the lookup yields
 * nothing usable.
 */
class OriginalLanguageLookupOrderingTest {

    private fun alias(title: String, artist: String, language: String = "ja-JP") =
        Alias(title = title, artist = artist, language = language)

    private fun decide(
        storefrontAlias: Alias?,
        identityAlias: Alias?,
        localizedTitle: String,
        localizedArtist: String,
    ) = AppleInternalCatalogResolver.decideOriginalLanguageResolution(
        storefrontAlias = storefrontAlias,
        identityAlias = identityAlias,
        localizedTitle = localizedTitle,
        localizedArtist = localizedArtist,
    )

    /** The exact device pair: the account's romanized alias against the region's native form. */
    private val accountRomanized = alias(
        title = "Harumeku",
        artist = "ナナツカゼ, PIKASONIC, nakotanmaru",
    )
    private val japaneseRegional = alias(
        title = "春めく",
        artist = "ナナツカゼ, PIKASONIC, なこたんまる",
    )
    private val localizedTitle = "Harumeku"
    private val localizedArtist = "ナナツカゼ, PIKASONIC, nakotanmaru"

    @Test
    fun `a matching fallback alias no longer replaces the origin-region result`() {
        // The identity alias is tagged with the derived language, so the old shortcut would have
        // fired here and kept the romanized account form.  The storefront result must win.
        val resolution = decide(
            storefrontAlias = japaneseRegional,
            identityAlias = accountRomanized,
            localizedTitle = localizedTitle,
            localizedArtist = localizedArtist,
        )
        assertEquals(OriginalLanguageResolution.Regional(japaneseRegional), resolution)
    }

    @Test
    fun `a storefront result wins over the fallback alias`() {
        // MIREI -> 當山 みれい: the identity carried a Latin-only alias, the region its own form.
        val regional = alias("Let Me Know", "當山 みれい")
        val resolution = decide(
            storefrontAlias = regional,
            identityAlias = alias("Let Me Know", "Mirei"),
            localizedTitle = "Let Me Know",
            localizedArtist = "MIREI",
        )
        assertEquals(OriginalLanguageResolution.Regional(regional), resolution)
    }

    @Test
    fun `a storefront miss still yields the fallback alias`() {
        // The entity probe returned nothing: today's artist-only correction must survive.
        val resolution = decide(
            storefrontAlias = null,
            identityAlias = accountRomanized,
            localizedTitle = localizedTitle,
            localizedArtist = localizedArtist,
        )
        assertEquals(OriginalLanguageResolution.IdentityFallback(accountRomanized), resolution)
    }

    @Test
    fun `a non-matching fallback alias behaves as before`() {
        // No identity alias for the derived language: a hit resolves regionally and a miss
        // continues down the existing ISRC/next-language path.
        assertEquals(
            OriginalLanguageResolution.Regional(japaneseRegional),
            decide(
                storefrontAlias = japaneseRegional,
                identityAlias = null,
                localizedTitle = localizedTitle,
                localizedArtist = localizedArtist,
            ),
        )
        assertEquals(
            OriginalLanguageResolution.None,
            decide(
                storefrontAlias = null,
                identityAlias = null,
                localizedTitle = localizedTitle,
                localizedArtist = localizedArtist,
            ),
        )
    }

    @Test
    fun `an unusable storefront result is a miss and keeps the fallback`() {
        // A differing title with no non-Latin script is not a correction; it is invalidated and
        // the identity alias stands in, exactly as the old miss branch did.
        val unusable = alias("Catchy Phrase", "Yabasu & Hatsune Miku")
        val fallback = alias("Catchphrase", "ヤバス, Hatsune Miku")
        assertEquals(
            OriginalLanguageResolution.IdentityFallback(fallback),
            decide(
                storefrontAlias = unusable,
                identityAlias = fallback,
                localizedTitle = "Catchphrase",
                localizedArtist = "Yabasu & Hatsune Miku",
            ),
        )
        // Without a fallback the unusable result leaves the language walk to continue.
        assertEquals(
            OriginalLanguageResolution.None,
            decide(
                storefrontAlias = unusable,
                identityAlias = null,
                localizedTitle = "Catchphrase",
                localizedArtist = "Yabasu & Hatsune Miku",
            ),
        )
    }

    @Test
    fun `a blank storefront alias is a miss`() {
        assertEquals(
            OriginalLanguageResolution.IdentityFallback(accountRomanized),
            decide(
                storefrontAlias = alias("", ""),
                identityAlias = accountRomanized,
                localizedTitle = localizedTitle,
                localizedArtist = localizedArtist,
            ),
        )
    }

    @Test
    fun `the identity alias the fallback reads is the one tagged with the derived language`() {
        // The fallback is only reachable when the identity alias matches the derived language;
        // this pins that `selectExactIdentityAlias` accepts the romanized ja-JP device alias.
        assertEquals(
            accountRomanized,
            AppleInternalCatalogResolver.selectExactIdentityAlias(
                aliases = listOf(accountRomanized),
                sourceLanguage = "ja-JP",
            ),
        )
    }

    @Test
    fun `configured storefront supplements identity facts without becoming an original alias`() {
        val source = sequenceOf(
            File("host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogOriginalResolution.kt"),
            File("../host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogOriginalResolution.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing CatalogOriginalResolution.kt")
        assertTrue(source.contains("queryIdentityWithConfiguredFallback"))
        assertTrue(source.contains("storefrontOverride = storefront"))
        assertTrue(source.contains("fallbackAliases = listOfNotNull(currentSong?.alias)"))
        assertTrue(
            "the configured storefront must contribute facts, not its localized title",
            source.contains("configuredSong?.genres.orEmpty()"),
        )
    }

    // ---- Source contract: the lookup is not suppressed before it is dispatched. ----

    private fun queryNextBody(): String {
        val source = sequenceOf(
            File("host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogOriginalResolution.kt"),
            File("../host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogOriginalResolution.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing CatalogOriginalResolution.kt")
        return source.substringAfter("fun queryNext(index: Int)").substringBefore("queryNext(0)")
            .lines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }

    @Test
    fun `the origin-region lookup runs before the identity alias can resolve anything`() {
        val body = queryNextBody()
        assertTrue(body.contains("resolveOriginalEntityForLanguage("))
        assertTrue(body.contains("decideOriginalLanguageResolution("))
        // The removed shortcut applied the identity alias and returned without querying.
        assertFalse(body.contains("selectExactIdentityAlias(identity.fallbackAliases, language)?.let"))
        val beforeLookup = body
            .substringAfter("val identityAlias = selectExactIdentityAlias")
            .substringBefore("resolveOriginalEntityForLanguage(")
        assertFalse("the identity alias must not finish the resolution early", beforeLookup.contains("finishResolve"))
        assertFalse("the identity alias must not return early", beforeLookup.contains("return"))
        // The fallback is decided inside the lookup callback, after the query was dispatched.
        assertTrue(
            body.indexOf("decideOriginalLanguageResolution(") >
                body.indexOf("resolveOriginalEntityForLanguage("),
        )
    }
}
