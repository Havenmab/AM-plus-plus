package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app alias pipeline must reach the collection2 Compose album page: the album entity is
 * enrolled and requested there, and a late alias republishes the header independently of the
 * track rows.
 */
class AppleAlbumComposeWiringStructuralTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("Missing $relative")

    @Test
    fun album_alias_refresh_reaches_the_compose_album_page() {
        val applier = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataApplier.kt",
        )
        assertTrue(applier.contains("artistSurfaceHooks.refreshComposeMetadata(mediaId, alias)"))

        val artist = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt",
        )
        assertTrue(artist.contains("AppleAlbumComposeMetadataHooks(runtime, host)"))
        assertTrue(artist.contains("albumComposeMetadataHooks.install()"))
        assertTrue(artist.contains("fun refreshComposeMetadata("))
        assertTrue(artist.contains("albumComposeMetadataHooks.refresh(mediaId, alias)"))
    }

    @Test
    fun compose_album_page_enrolls_the_album_entity_and_requests_it() {
        val hooks = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleAlbumComposeMetadataHooks.kt",
        )
        // Fail-open: no exact content target means the build never resolves the Compose chain.
        assertTrue(
            hooks.contains(
                "AppleMusicHookProfiles.exactTargets(resolver.version, AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT).isEmpty()",
            ),
        )
        // The album root is registered under its kind, so rememberEntityType records ALBUM.
        assertTrue(hooks.contains("host.registerLibraryEntity(id, entity, kind)"))
        // …and the page catalog id is probed as a real entity request.
        assertTrue(hooks.contains("page.catalogId?.let { request(page, it) }"))
        assertTrue(hooks.contains("host.markMetadataVisible(listOf(id))"))
        assertTrue(hooks.contains("host.enrichLibraryEntitiesForResolution(listOf(id))"))
        assertTrue(hooks.contains("InAppOriginalResolutionMode.ORIGINAL_FIRST"))
    }

    @Test
    fun header_revision_is_independent_of_the_row_revision() {
        val hooks = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleAlbumComposeMetadataHooks.kt",
        )
        assertTrue(hooks.contains("refreshHeader?.invoke(page.vm.get())"))
        assertTrue(hooks.contains("if (page.headerRevision.dirty())"))
        // Rows are published only after the header attempt, under their own dirty flag.
        assertTrue(hooks.contains("if (!page.revision.dirty()) return@refresh"))

        val registry = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleAlbumComposePageRegistry.kt",
        )
        assertTrue(registry.contains("val headerRevision = AppleAlbumRowRevision()"))
        assertTrue(registry.contains("fun updateAlias(id: String, alias: AppleInternalCatalogResolver.Alias): Boolean"))
        assertTrue(registry.contains("fun needsRefresh(): Boolean"))
        assertTrue(registry.contains("page.headerRevision.invalidateIfKnown()"))
        assertTrue(
            registry.contains(
                "val headerChanged = id == catalogId && headerRevision.update(id, alias)",
            ),
        )
    }
}
