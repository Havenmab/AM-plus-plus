package io.github.proify.lyricon.amprovider.xposed.hooks

import dev.amenhancer.module.config.RegionSelection
import dev.amenhancer.module.config.RegionTitleRequestPolicy
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The region control rewrites ordinary Apple Music catalog traffic, but never the module's own
 * identity/original-name lookups: their catalog IDs only exist in the storefront they were read
 * from.  Regression cover for the case where the untargeted identity probe was redirected to the
 * configured region, came back empty (`isrc=null, genres=[]`) and left the per-song
 * original-name correction with nothing to probe.
 */
class RegionInternalCatalogLookupTest {

    /** NONE plus two real regions: the internal lookups must be immune to all of them. */
    private val regions = listOf(
        RegionSelection.NONE,
        RegionSelection.ZH_HANS_US,
        RegionSelection.JAPAN,
    )

    /** The storefront argument Apple's own client supplied; it must survive untouched. */
    private val hostStorefrontArgument = "arg3"

    private fun query(token: String? = null) = linkedMapOf<Any?, Any?>(
        "ids" to "1440833098",
        "platform" to "android",
        "include[songs]" to "artists",
    ).also { source ->
        token?.let { source[AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM] = it }
    }

    private fun args(queryIndex: Int, query: MutableMap<Any?, Any?>): MutableList<Any?> =
        MutableList(queryIndex + 1) { index -> if (index == queryIndex) query else "arg$index" }

    @Test
    fun `host traffic follows the region while module-internal lookups keep the host target`() {
        regions.forEach { region ->
            val configuredStorefront = region.catalogStorefront

            // Host request: token-less, so the user's region is the only thing that may redirect it.
            val hostQuery = query()
            val hostResult = AppleCatalogExecutorArgs.rewrite(
                args = args(5, hostQuery),
                queryArgIndex = 5,
                configuredStorefront = configuredStorefront,
                localizationForToken = { error("a host request must not resolve a module token") },
            )
            if (configuredStorefront == null) {
                assertNull("${region.name} must leave host traffic untouched", hostResult)
            } else {
                requireNotNull(hostResult)
                assertEquals(configuredStorefront, hostResult.storefront)
                assertEquals(
                    configuredStorefront,
                    hostResult.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX],
                )
                assertEquals(
                    AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_VALUE,
                    hostQuery[AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM],
                )
            }

            // Module-internal identity lookup: module-owned, but with no storefront of its own.
            // The storefront argument must stay exactly as Apple's own client built it: writing
            // one here is what emptied the identity and stopped the original-name correction.
            val moduleQuery = query(token = "identity-token")
            val moduleResult = AppleCatalogExecutorArgs.rewrite(
                args = args(5, moduleQuery),
                queryArgIndex = 5,
                configuredStorefront = configuredStorefront,
                localizationForToken = { token ->
                    assertEquals("identity-token", token)
                    AppleInternalCatalogResolver.CatalogRequestLocalization(
                        storefront = null,
                        language = null,
                    )
                },
            )
            requireNotNull(moduleResult)
            assertNull(moduleResult.storefront)
            assertEquals(
                "${region.name} must not write a storefront into a module-internal lookup",
                hostStorefrontArgument,
                moduleResult.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX],
            )
            assertNull(moduleQuery[AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM])
            assertEquals(
                AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_VALUE,
                moduleQuery[AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM],
            )
        }
    }

    @Test
    fun `untargeted module queries carry no storefront only while a region is active`() {
        // No region: today's token-less probe is preserved.
        assertNull(
            AppleInternalCatalogResolver.moduleCatalogRequestLocalization(
                storefront = null,
                language = null,
                regionRewriteEnabled = false,
            ),
        )
        // Region active: the probe becomes module-owned but still targets nothing.
        assertEquals(
            AppleInternalCatalogResolver.CatalogRequestLocalization(null, null),
            AppleInternalCatalogResolver.moduleCatalogRequestLocalization(
                storefront = null,
                language = null,
                regionRewriteEnabled = true,
            ),
        )
        // A targeted probe keeps its own storefront/language regardless of the region.
        assertEquals(
            AppleInternalCatalogResolver.CatalogRequestLocalization("jp", "ja-JP"),
            AppleInternalCatalogResolver.moduleCatalogRequestLocalization(
                storefront = "jp",
                language = "ja-JP",
                regionRewriteEnabled = true,
            ),
        )
    }

    @Test
    fun `account-scoped module localizations are the ones the region seams must skip`() {
        assertTrue(
            AppleInternalCatalogResolver.keepsAccountCatalogTarget(
                AppleInternalCatalogResolver.CatalogRequestLocalization(null, null),
            ),
        )
        assertTrue(
            AppleInternalCatalogResolver.keepsAccountCatalogTarget(
                AppleInternalCatalogResolver.CatalogRequestLocalization("jp", null),
            ),
        )
        assertFalse(
            AppleInternalCatalogResolver.keepsAccountCatalogTarget(
                AppleInternalCatalogResolver.CatalogRequestLocalization("jp", "ja-JP"),
            ),
        )
        assertFalse(
            "ordinary traffic has no module localization",
            AppleInternalCatalogResolver.keepsAccountCatalogTarget(null),
        )
    }

    @Test
    fun `the original probe language follows the catalog identity, not the region`() {
        regions.forEach { region ->
            val plan = RegionTitleRequestPolicy.plan(region, restoreCjkOriginalMetadata = true)
            assertTrue("correction must stay on for ${region.name}", plan.probesOriginalMetadata)

            // An identity that yields a Japanese ISRC probes the song's own original region.
            val languages = AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                catalogGenres = listOf("动画", "音乐"),
                isrc = "JPDN12345678",
            )
            assertEquals(listOf("ja-JP"), languages)
            val probeStorefront =
                AppleInternalCatalogResolver.storefrontForOriginalLanguage(languages.first())
            // The probe storefront is derived from the identity's language alone, so it is "jp" for
            // every region selection.  A region whose own storefront is also "jp" (日本) is a
            // legitimate coincidence and must not be asserted against: the property under test is
            // that the *derivation* ignores the region, not that the two values differ.
            assertEquals("jp", probeStorefront)
        }
    }
}
