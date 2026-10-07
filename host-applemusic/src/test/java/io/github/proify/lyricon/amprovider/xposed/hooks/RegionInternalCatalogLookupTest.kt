package io.github.proify.lyricon.amprovider.xposed.hooks

import dev.amenhancer.module.config.RegionSelection
import dev.amenhancer.module.config.RegionTitleRequestPolicy
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.shouldCaptureAccountStorefront
import io.github.proify.lyricon.amprovider.xposed.shouldRestoreModuleLookupStorefront
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The region control rewrites ordinary Apple Music catalog traffic, but the module's own
 * identity/original-name lookups must take exactly the same catalog path as HLE's.
 *
 * HLE's `queryById(mediaId, null)` for the untargeted identity/ISRC/genre probe builds **no**
 * localization: no module token, no storefront field write.  The probe is then localized by the
 * region seams exactly like ordinary traffic, and the direct query's catalog target -- which
 * Apple's client derives from the shared MediaApi storefront field -- is left at the region's
 * value instead of being switched to the account storefront.
 *
 * The fork used to invent a target-less module localization for that probe and to switch the
 * shared MediaApi storefront field to the captured account storefront for the duration of the
 * call.  Device evidence showed every switched lookup coming back empty
 * (`fieldStorefront=us->tr` / `cn->tr` -> `dataSize=0`) while the unswitched ones resolved, so
 * the original-region name was never probed.  This is the regression cover: the probe must not
 * be tokenized and the shared field must not be switched for it.
 */
class RegionInternalCatalogLookupTest {

    /** NONE plus two real regions: the internal lookups must be immune to all of them. */
    private val regions = listOf(
        RegionSelection.NONE,
        RegionSelection.ZH_HANS_US,
        RegionSelection.JAPAN,
    )

    private val probeStorefront = "jp"
    private val probeLanguage = "ja-JP"

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
    fun `host traffic follows the region while a module localization keeps its own target`() {
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

            // A targeted module query (the original-region probe) keeps its own storefront and
            // language; only its token is stripped before the request goes out.
            val moduleQuery = query(token = "original-token")
            val moduleResult = AppleCatalogExecutorArgs.rewrite(
                args = args(5, moduleQuery),
                queryArgIndex = 5,
                configuredStorefront = configuredStorefront,
                localizationForToken = { token ->
                    assertEquals("original-token", token)
                    AppleInternalCatalogResolver.CatalogRequestLocalization(
                        storefront = probeStorefront,
                        language = probeLanguage,
                    )
                },
            )
            requireNotNull(moduleResult)
            assertEquals(probeStorefront, moduleResult.storefront)
            assertEquals(
                probeStorefront,
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
    fun `an untargeted module lookup carries no localization for any region`() {
        regions.forEach { region ->
            val configuredStorefront = region.catalogStorefront
            // The identity/ISRC/genre probe: HLE builds no localization for it, so neither may
            // the fork.  A token here is what used to pin the probe to the account storefront
            // and, with it, empty the identity.
            assertNull(
                "${region.name} must not tokenize the untargeted identity probe",
                AppleInternalCatalogResolver.moduleCatalogRequestLocalization(
                    storefront = null,
                    language = null,
                    regionRewriteEnabled = configuredStorefront != null,
                ),
            )
            // Token-less means every seam sees host traffic, including the executor rewrite.
            val identityQuery = query()
            val identityResult = AppleCatalogExecutorArgs.rewrite(
                args = args(5, identityQuery),
                queryArgIndex = 5,
                configuredStorefront = configuredStorefront,
                localizationForToken = { error("the identity probe must not resolve a token") },
            )
            if (configuredStorefront == null) {
                assertNull("${region.name} must leave the probe untouched", identityResult)
            } else {
                requireNotNull(identityResult)
                assertEquals(configuredStorefront, identityResult.storefront)
                assertEquals(
                    configuredStorefront,
                    identityResult.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX],
                )
            }
        }
    }

    @Test
    fun `a targeted module query keeps storefront and language for every region`() {
        regions.forEach { region ->
            assertEquals(
                AppleInternalCatalogResolver.CatalogRequestLocalization(
                    storefront = probeStorefront,
                    language = probeLanguage,
                ),
                AppleInternalCatalogResolver.moduleCatalogRequestLocalization(
                    storefront = probeStorefront,
                    language = probeLanguage,
                    regionRewriteEnabled = region.catalogStorefront != null,
                ),
            )
        }
    }

    @Test
    fun `the original probe language follows the catalog identity, not the region`() {
        regions.forEach { region ->
            val plan = RegionTitleRequestPolicy.plan(
                region = region,
                overrideAccountLanguage = false,
                restoreCjkOriginalMetadata = true,
            )
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

    // ---- The shared MediaApi storefront field: only a targeted module query may switch it ----

    /** A Turkish account browsing with a region selected, as in the device evidence. */
    private val accountStorefront = "tr"

    @Test
    fun `the region's own write is never captured as the account storefront`() {
        // Any value that is not this resolver's own region write is the account's.
        assertTrue(shouldCaptureAccountStorefront(current = "tr", lastAppliedConfiguredStorefront = null))
        assertTrue(shouldCaptureAccountStorefront(current = "tr", lastAppliedConfiguredStorefront = "us"))
        // The field still holds the region storefront we applied: capturing it would pin the
        // account fallbacks to the selected region.
        assertFalse(shouldCaptureAccountStorefront(current = "us", lastAppliedConfiguredStorefront = "us"))
        // Nothing to capture.
        assertFalse(shouldCaptureAccountStorefront(current = null, lastAppliedConfiguredStorefront = null))
        assertFalse(shouldCaptureAccountStorefront(current = null, lastAppliedConfiguredStorefront = "us"))
    }

    @Test
    fun `a field another writer changed is not restored from a stale observation`() {
        // Our write is still in place: restore the value observed before the call.
        assertTrue(
            shouldRestoreModuleLookupStorefront(applied = accountStorefront, current = accountStorefront),
        )
        // The region apply/retry wrote the field while the probe was in flight: it owns the
        // value now, so restoring the stale observation would undo the chosen region.
        assertFalse(shouldRestoreModuleLookupStorefront(applied = accountStorefront, current = "us"))
        assertFalse(shouldRestoreModuleLookupStorefront(applied = accountStorefront, current = null))
        // Nothing was written: nothing to restore.
        assertFalse(shouldRestoreModuleLookupStorefront(applied = null, current = "us"))
        assertFalse(shouldRestoreModuleLookupStorefront(applied = null, current = null))
    }

    @Test
    fun `the identity diagnostic states the field before and that the probe left it unchanged`() {
        // The untargeted probe no longer switches the field, so the decision line must say so
        // while still reporting the captured account storefront.
        val captured = AppleInternalCatalogResolver.moduleIdentityLookupDetail(
            fieldBefore = "us",
            fieldUsed = null,
            idsCount = 1,
            storefront = null,
            language = null,
            accountStorefront = accountStorefront,
            accountStorefrontCaptured = true,
        )
        assertTrue(captured.contains("fieldStorefront=us->unchanged"))
        assertTrue(captured.contains("ids=1"))
        assertTrue(captured.contains("localization=none/none"))
        assertTrue(captured.contains("accountStorefront=tr"))

        // Unknown account: the line must say so, not imply the region value is the account's.
        val unknown = AppleInternalCatalogResolver.moduleIdentityLookupDetail(
            fieldBefore = "us",
            fieldUsed = null,
            idsCount = 1,
            storefront = null,
            language = null,
            accountStorefront = null,
            accountStorefrontCaptured = false,
        )
        assertTrue(unknown.contains("fieldStorefront=us->unchanged"))
        assertTrue(unknown.contains("accountStorefront=not-captured"))

        // Captured but unread is distinct from never captured.
        val unset = AppleInternalCatalogResolver.moduleIdentityLookupDetail(
            fieldBefore = null,
            fieldUsed = null,
            idsCount = 0,
            storefront = null,
            language = null,
            accountStorefront = null,
            accountStorefrontCaptured = true,
        )
        assertTrue(unset.contains("fieldStorefront=unset->unchanged"))
        assertTrue(unset.contains("accountStorefront=unset"))
    }

    @Test
    fun `the id count follows the ids parameter and the isrc probe`() {
        assertEquals(0, AppleInternalCatalogResolver.catalogLookupIdCount(emptyMap()))
        assertEquals(1, AppleInternalCatalogResolver.catalogLookupIdCount(mapOf("ids" to "1440833098")))
        assertEquals(3, AppleInternalCatalogResolver.catalogLookupIdCount(mapOf("ids" to "1,2,3")))
        assertEquals(1, AppleInternalCatalogResolver.catalogLookupIdCount(mapOf("filter[isrc]" to "JPDN1")))
        assertEquals(0, AppleInternalCatalogResolver.catalogLookupIdCount(mapOf("ids" to "")))
    }
}
