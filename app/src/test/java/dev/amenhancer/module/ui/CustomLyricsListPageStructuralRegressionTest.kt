package dev.amenhancer.module.ui
import dev.amenhancer.module.hook.readRefactorComponent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsListPageStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `renders only the revealed window from the shared list state`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val state = projectFile("app/src/main/java/dev/amenhancer/module/ui/CustomLyricsListState.kt")

        assertTrue(host.contains("CustomLyricsListState"))
        assertTrue(host.contains("customLyricsListState.update(entries, customLyricsSearchQuery)"))
        assertTrue(host.contains("val visibleGroups = state.visibleGroups"))
        assertTrue(host.contains("embeddedCustomLyricsEntryRow(activity, group, song)"))
        assertTrue(state.contains("DEFAULT_PAGE_SIZE = 50"))
        assertTrue(state.contains("visibleEntries"))
        assertTrue(state.contains("hasMore"))
        assertFalse(state.contains("import android"))
    }

    @Test
    fun `paginates with search, load more and a shown total counter`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("ModuleText.SEARCH_LYRICS"))
        assertTrue(host.contains("addTextChangedListener"))
        assertTrue(host.contains("afterTextChanged"))
        assertTrue(host.contains("customLyricsSearchQuery"))
        assertTrue(host.contains("ModuleText.LOAD_MORE"))
        assertTrue(host.contains("customLyricsListState.loadMore()"))
        assertTrue(
            host.contains(
                "localizedText(ModuleText.LYRICS_VISIBLE_COUNT, state.visibleCount, state.totalCount)",
            ),
        )
    }

    @Test
    fun `places add and backup actions above the search input`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val page = host.substringAfter("private fun renderEmbeddedCustomLyricsPage(")
            .substringBefore("private fun embeddedCustomLyricsEntryRow(")

        val actionBar = page.indexOf("embeddedCompactLyricsActionBar(")
        val search = page.indexOf("val search = EditText(activity)")
        assertTrue("action bar must exist", actionBar >= 0)
        assertTrue("search input must exist", search >= 0)
        assertTrue("actions must precede search", actionBar < search)
        listOf("onAdd", "onTtml", "onBackup").forEach { action ->
            assertTrue("$action must remain in the embedded action bar", page.contains(action))
        }
    }

    @Test
    fun `keeps embedded empty and no match states`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("ModuleText.NO_CUSTOM_LYRICS"))
        assertTrue(host.contains("ModuleText.NO_SEARCH_RESULTS"))
        assertTrue(host.contains("ModuleText.ADD_LYRICS_EMPTY_HINT"))
        assertTrue(host.contains("ModuleText.SEARCH_LYRICS_EMPTY_HINT"))
    }

    @Test
    fun `configures the embedded custom lyrics search field for text IME input`() {
        val embeddedHost = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt",
        )
        val embeddedSearch = embeddedHost
            .substringAfter("private fun renderEmbeddedCustomLyricsPage(")
            .substringBefore("private fun embeddedCustomLyricsEntryRow(")

        assertTrue(embeddedSearch.contains("inputType = InputType.TYPE_CLASS_TEXT"))
        assertTrue(embeddedSearch.contains("imeOptions = EditorInfo.IME_ACTION_SEARCH"))
        assertTrue(embeddedSearch.contains("showSoftInputOnFocus = true"))
        assertTrue(embeddedHost.contains("clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)"))
        assertTrue(embeddedHost.contains("setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)"))
    }

    @Test
    fun `embedded custom lyrics page exposes update instead of github bulk sync`() {
        val embeddedHost = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt",
        )

        assertTrue(embeddedHost.contains("onUpdate = { updateEmbeddedLyrics(activity) }"))
        assertTrue(embeddedHost.contains("label = localizedText(ModuleText.UPDATE)"))
        assertTrue(embeddedHost.contains("description = localizedText(ModuleText.LYRICS_UPDATE)"))
        assertTrue(embeddedHost.contains("internal fun EmbeddedSettingsHost.updateEmbeddedLyrics(activity: Activity)"))
        assertFalse(embeddedHost.contains("syncEmbeddedGitHub"))
        assertFalse(embeddedHost.contains("syncFromGitHub"))
    }
}
