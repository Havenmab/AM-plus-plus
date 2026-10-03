package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeSongMenuStructuralTest {
    private fun source(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()?.replace(Regex("\\s+"), " ")
        ?: error("$relativePath was not found from the unit-test working directory")

    private val miniPath = "glass/src/main/kotlin/dev/amenhancer/glass/GlassMiniPlayer.kt"
    private val sessionPath = "app/src/main/java/dev/amenhancer/module/hook/TabletChromeSession.kt"
    private val targetPath = "app/src/main/java/dev/amenhancer/module/hook/AppleMusicTabletChromeTarget.kt"

    @Test
    fun shuffleUsesTheSamePlainGlyphAndOnlyChangesTint() {
        val mini = source(miniPath)
        val session = source(sessionPath)
        assertTrue(mini.contains("icon = icons.shuffle, tint = if (state.shuffleOn) accent else foreground"))
        assertFalse(mini.contains("val shuffleOn: Drawable?"))
        assertTrue(session.contains("shuffle = hostDrawable(\"ic_nowplaying_shuffle\")"))
        assertFalse(session.contains("ic_nowplaying_shuffleon"))
    }

    @Test
    fun repeatKeepsItsThreeStatesWithoutUsingFilledSelectionAssets() {
        val mini = source(miniPath)
        val session = source(sessionPath)
        assertTrue(mini.contains("GlassRepeatMode.OFF -> icons.repeat GlassRepeatMode.ALL -> icons.repeat GlassRepeatMode.ONE -> icons.repeatOne"))
        assertTrue(mini.contains("tint = if (state.repeatMode == GlassRepeatMode.OFF) foreground else accent"))
        assertTrue(session.contains("repeatOne = hostDrawable(\"ic_nowplaying_repeatone\")"))
        assertFalse(session.contains("\"ic_nowplaying_repeaton\""))
        assertFalse(session.contains("\"ic_nowplaying_repeatoneon\""))
    }

    @Test
    fun moreUsesTheNativeHorizontalDotsBeforeLyricsAndQueue() {
        val mini = source(miniPath)
        val session = source(sessionPath)
        val more = mini.indexOf("command = GlassMiniPlayerCommand.MORE")
        val lyrics = mini.indexOf("command = GlassMiniPlayerCommand.LYRICS")
        val queue = mini.indexOf("command = GlassMiniPlayerCommand.QUEUE")
        assertTrue(more >= 0 && more < lyrics && lyrics < queue)
        assertTrue(session.contains("more = hostDrawable(\"ic_actionsheet_more\")"))
        assertTrue(mini.contains("enabled = state.enabled && state.moreEnabled"))
        assertTrue(session.contains("moreEnabled = commands.moreAvailable"))
    }

    @Test
    fun moreDispatchesOnlyTheSongMenuAndPreservesLyricsAndQueueActions() {
        val session = source(sessionPath)
        assertTrue(session.contains("GlassMiniPlayerCommand.MORE -> commands.openSongMenu(activity)"))
        assertTrue(session.contains("GlassMiniPlayerCommand.LYRICS -> commands.expandPlayer(activity)"))
        assertTrue(session.contains("GlassMiniPlayerCommand.QUEUE -> requestQueuePane()"))
        val menu = source(targetPath).substringAfter("override fun openSongMenu(activity: Activity)")
            .substringBefore("override fun addListener")
        assertFalse(menu.contains("expandPlayer"))
        assertFalse(menu.contains("openPane"))
        assertFalse(menu.contains("PopupMenu"))
        assertFalse(menu.contains("performClick"))
    }

    @Test
    fun songMenuReadsTheLivePaneAndItsNativeCallbackInsteadOfAnActivityWideMatch() {
        val menu = source(targetPath).substringAfter("override fun openSongMenu(activity: Activity)")
            .substringBefore("override fun addListener")
        assertTrue(menu.contains("accessor.invoke(activity)"))
        assertTrue(menu.contains("resolved.currentFragment?.invoke(player)"))
        assertTrue(menu.contains("resolved.fragmentView?.invoke(pane) as? View"))
        assertTrue(menu.contains("getIdentifier(\"list_left_icon\", \"id\", ModuleConstants.TARGET_PACKAGE)"))
        assertTrue(menu.contains("root.findViewById<View>(id)"))
        assertTrue(menu.contains("root.isAttachedToWindow"))
        assertTrue(menu.contains("!button.isEnabled || !button.hasOnClickListeners()"))
        assertTrue(menu.contains("button.callOnClick()"))
        assertFalse(menu.contains("activity.findViewById"))
        assertFalse(menu.contains("currentTitle"))
    }
}
