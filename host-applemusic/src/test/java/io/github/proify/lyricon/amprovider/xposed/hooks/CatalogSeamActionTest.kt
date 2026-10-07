package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM cover for the content-HTTP seam's region decision.
 *
 * The seam is the last writer of a catalog request's storefront (URL path segment and the
 * `X-Apple-Store-Front`/`X-Apple-Request-Store-Front` headers).  A targeted original-region
 * lookup is localized by the catalog executor and must never be re-localized to the configured
 * region by a later seam.  These cases pin the three module-owned outcomes that make that true.
 */
class CatalogSeamActionTest {

    @Test
    fun `an executor-marked request is only stripped`() {
        // The catalog executor already wrote the request's own storefront and stamped its marker;
        // the seam strips the marker and never rewrites the storefront again.
        assertEquals(
            CatalogSeamAction.SKIP_MARKED,
            catalogSeamAction(
                carriesModuleMarker = true,
                requestToken = "tok",
                localizationResolved = true,
                globalRegionRewriteEnabled = true,
            ),
        )
        // Even with the localization gone the marker still means "already localized".
        assertEquals(
            CatalogSeamAction.SKIP_MARKED,
            catalogSeamAction(
                carriesModuleMarker = true,
                requestToken = null,
                localizationResolved = false,
                globalRegionRewriteEnabled = true,
            ),
        )
    }

    @Test
    fun `a token request whose localization is gone fails open instead of taking the region`() {
        // A later seam sees a module request after an earlier seam stripped the marker.  Applying
        // the configured region here is what turned a jp original-region lookup into the us row,
        // so the request must be left exactly as Apple built it.
        assertEquals(
            CatalogSeamAction.FAIL_OPEN_UNRESOLVED_TOKEN,
            catalogSeamAction(
                carriesModuleMarker = false,
                requestToken = "tok",
                localizationResolved = false,
                globalRegionRewriteEnabled = true,
            ),
        )
        assertEquals(
            CatalogSeamAction.FAIL_OPEN_UNRESOLVED_TOKEN,
            catalogSeamAction(
                carriesModuleMarker = false,
                requestToken = "tok",
                localizationResolved = false,
                globalRegionRewriteEnabled = false,
            ),
        )
    }

    @Test
    fun `a token request with its localization uses its own target`() {
        assertEquals(
            CatalogSeamAction.REWRITE_MODULE,
            catalogSeamAction(
                carriesModuleMarker = false,
                requestToken = "tok",
                localizationResolved = true,
                globalRegionRewriteEnabled = true,
            ),
        )
    }

    @Test
    fun `ordinary traffic follows the region only while the feature is on`() {
        assertEquals(
            CatalogSeamAction.PASS,
            catalogSeamAction(
                carriesModuleMarker = false,
                requestToken = null,
                localizationResolved = false,
                globalRegionRewriteEnabled = false,
            ),
        )
        assertEquals(
            CatalogSeamAction.REWRITE_REGION,
            catalogSeamAction(
                carriesModuleMarker = false,
                requestToken = null,
                localizationResolved = false,
                globalRegionRewriteEnabled = true,
            ),
        )
    }
}
