package io.github.proify.lyricon.amprovider.xposed.hooks

import dev.amenhancer.module.config.RegionSelection
import dev.amenhancer.module.config.RegionTitleRequestPolicy
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.shouldCaptureAccountStorefront
import io.github.proify.lyricon.amprovider.xposed.shouldRestoreModuleLookupStorefront
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

    // ---- The shared MediaApi storefront field: the lever the executor argument is built from ----

    /** A Turkish account browsing with a region selected, as in the device evidence. */
    private val accountStorefront = "tr"

    private fun fieldStorefront(storefront: String?, language: String?, rewrite: Boolean) =
        AppleInternalCatalogResolver.moduleCatalogLookupFieldStorefront(
            storefront = storefront,
            language = language,
            regionRewriteEnabled = rewrite,
            accountStorefront = accountStorefront,
        )

    @Test
    fun `an untargeted module lookup runs against the account storefront while a region is active`() {
        regions.forEach { region ->
            val rewrite = region.catalogStorefront != null
            if (rewrite) {
                assertEquals(
                    "${region.name} must hand the shared field back to the account",
                    accountStorefront,
                    fieldStorefront(storefront = null, language = null, rewrite = true),
                )
            } else {
                assertNull(
                    "${region.name} must leave the field untouched",
                    fieldStorefront(storefront = null, language = null, rewrite = false),
                )
            }
        }
    }

    @Test
    fun `the field switch fails open for an unknown account and never overrides a targeted lookup`() {
        // Account storefront never captured: the field must be left exactly as it is.
        assertNull(
            AppleInternalCatalogResolver.moduleCatalogLookupFieldStorefront(
                storefront = null,
                language = null,
                regionRewriteEnabled = true,
                accountStorefront = null,
            ),
        )
        assertNull(
            AppleInternalCatalogResolver.moduleCatalogLookupFieldStorefront(
                storefront = null,
                language = null,
                regionRewriteEnabled = true,
                accountStorefront = "",
            ),
        )
        // A targeted lookup owns its storefront argument; the field is not that lever.
        assertNull(fieldStorefront(storefront = "jp", language = "ja-JP", rewrite = true))
        assertNull(fieldStorefront(storefront = "jp", language = null, rewrite = true))
        assertNull(fieldStorefront(storefront = null, language = "ja-JP", rewrite = true))
    }

    @Test
    fun `the identity probe keeps a target-less executor localization while the field carries the account`() {
        regions.forEach { region ->
            val rewrite = region.catalogStorefront != null
            val localization = AppleInternalCatalogResolver.moduleCatalogRequestLocalization(
                storefront = null,
                language = null,
                regionRewriteEnabled = rewrite,
            )
            if (!rewrite) {
                assertNull(localization)
                return@forEach
            }
            requireNotNull(localization)
            // The executor storefront argument stays exactly as Apple's client built it...
            assertNull(localization.storefront)
            assertNull(localization.language)
            assertTrue(AppleInternalCatalogResolver.keepsAccountCatalogTarget(localization))
            // ...because the shared field is what hands Apple the account storefront to build it
            // from.  The two levers are complementary: neither one alone is the fix.
            assertEquals(
                accountStorefront,
                fieldStorefront(storefront = null, language = null, rewrite = true),
            )
        }
    }

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
    fun `the identity diagnostic states the field before, the value used and the account storefront`() {
        val captured = AppleInternalCatalogResolver.moduleIdentityLookupDetail(
            fieldBefore = "us",
            fieldUsed = accountStorefront,
            idsCount = 1,
            storefront = null,
            language = null,
            accountStorefront = accountStorefront,
            accountStorefrontCaptured = true,
        )
        assertTrue(captured.contains("fieldStorefront=us->tr"))
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
