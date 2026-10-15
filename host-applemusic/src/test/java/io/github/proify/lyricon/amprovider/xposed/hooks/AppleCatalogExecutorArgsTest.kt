package io.github.proify.lyricon.amprovider.xposed.hooks

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM coverage for the catalog-executor argument rewrite that redirects ordinary Apple Music
 * entity loads and catalog searches to the configured storefront.
 */
class AppleCatalogExecutorArgsTest {
    @Suppress("unused")
    private class ExecutorFixture {
        fun d(
            requestId: Long,
            path: String,
            owner: String,
            storefront: String,
            language: String,
            query: LinkedHashMap<String, String>,
            continuation: Any?,
        ): Any? = null

        fun b(
            requestId: Long,
            path: String,
            owner: String,
            storefront: String,
            query: LinkedHashMap<String, String>,
            headers: LinkedHashMap<String, String>,
            continuation: Any?,
        ): Any? = null
    }

    private fun method(name: String, parameterCount: Int): Method =
        ExecutorFixture::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == parameterCount
        }

    private fun args(queryIndex: Int, query: MutableMap<Any?, Any?>): MutableList<Any?> =
        MutableList(queryIndex + 1) { index ->
            if (index == queryIndex) query else "arg$index"
        }

    @Test
    fun `query argument is the first Map parameter of each executor shape`() {
        assertEquals(5, AppleCatalogExecutorArgs.queryArgIndex(method("d", 7)))
        assertEquals(4, AppleCatalogExecutorArgs.queryArgIndex(method("b", 7)))
    }

    @Test
    fun `a native request is redirected only when a region is configured`() {
        val queryIndex = 5
        val query = linkedMapOf<Any?, Any?>("ids" to "123")
        val args = args(queryIndex, query)

        assertNull(
            "no configured region must leave native traffic untouched",
            AppleCatalogExecutorArgs.rewrite(
                args = args,
                queryArgIndex = queryIndex,
                configuredStorefront = null,
                localizationForToken = { null },
            ),
        )

        val result = AppleCatalogExecutorArgs.rewrite(
            args = args,
            queryArgIndex = queryIndex,
            configuredStorefront = "jp",
            localizationForToken = { null },
        )
        requireNotNull(result)
        assertNull(result.token)
        assertEquals("jp", result.storefront)
        assertEquals("jp", result.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX])
        // The HTTP seam must be able to tell that this request is already localized.
        assertEquals(
            AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_VALUE,
            query[AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM],
        )
        // Only the shared query map is mutated in place; the argument array is replaced through
        // the returned copy, which is what the hook installer hands back to the framework.
        assertEquals("arg3", args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX])
    }

    @Test
    fun `a module request follows its token and never leaks the token`() {
        val queryIndex = 4
        val query = linkedMapOf<Any?, Any?>(
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM to "tok1",
            "ids" to "123",
        )
        val args = args(queryIndex, query)
        val localization = AppleInternalCatalogResolver.CatalogRequestLocalization("kr", "ko-KR")

        val result = AppleCatalogExecutorArgs.rewrite(
            args = args,
            queryArgIndex = queryIndex,
            configuredStorefront = "cn",
            localizationForToken = { token -> localization.takeIf { token == "tok1" } },
        )
        requireNotNull(result)
        assertEquals("tok1", result.token)
        assertEquals("kr", result.storefront)
        // The token's own region wins over the configured one.
        assertEquals("kr", result.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX])
        assertNull(query[AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM])
        assertEquals(
            AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_VALUE,
            query[AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM],
        )
    }

    @Test
    fun `an unknown token still gets stripped and keeps the storefront argument`() {
        val queryIndex = 5
        val query = linkedMapOf<Any?, Any?>(
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM to "tok2",
        )
        val args = args(queryIndex, query)
        val originalStorefront = args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX]

        val result = AppleCatalogExecutorArgs.rewrite(
            args = args,
            queryArgIndex = queryIndex,
            configuredStorefront = "cn",
            localizationForToken = { null },
        )
        requireNotNull(result)
        assertEquals("tok2", result.token)
        assertNull(result.storefront)
        assertEquals(originalStorefront, result.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX])
        assertNull(query[AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM])
    }

    @Test
    fun `shapes that cannot carry a storefront argument are rejected`() {
        // The query map must come after the storefront argument, otherwise index 3 cannot be
        // rewritten without touching the query itself.
        val tooEarly = linkedMapOf<Any?, Any?>("ids" to "123")
        assertNull(
            AppleCatalogExecutorArgs.rewrite(
                args = args(1, tooEarly),
                queryArgIndex = 1,
                configuredStorefront = "jp",
                localizationForToken = { null },
            ),
        )
        // A non-Map query argument is not an executor shape we can rewrite.
        val notAMap = args(5, linkedMapOf<Any?, Any?>("ids" to "123"))
        notAMap[5] = "not-a-map"
        assertNull(
            AppleCatalogExecutorArgs.rewrite(
                args = notAMap,
                queryArgIndex = 5,
                configuredStorefront = "jp",
                localizationForToken = { null },
            ),
        )
    }
}
