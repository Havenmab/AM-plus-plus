package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the persisted vocabulary: lane keys, provider ids, option keys and their labels. */
class LyricSourceKeysTest {

    @Test
    fun `lanes parse from their stable keys`() {
        assertEquals(LyricLane.ORIGINAL, LyricLane.fromKey("original"))
        assertEquals(LyricLane.TRANSLATION, LyricLane.fromKey(" TRANSLATION "))
        assertEquals(LyricLane.ROMANIZATION, LyricLane.fromKey("romanization"))
        assertNull(LyricLane.fromKey(null))
        assertNull(LyricLane.fromKey("bogus"))
        assertEquals(3, LyricLane.ALL.size)
        assertTrue(LyricLane.TRANSLATION.isSupplement)
        assertTrue(LyricLane.ROMANIZATION.isSupplement)
        assertFalse(LyricLane.ORIGINAL.isSupplement)
    }

    @Test
    fun `kinds parse from their tokens`() {
        LyricSourceKind.values().forEach { kind ->
            assertEquals(kind, LyricSourceKind.fromToken(kind.token))
        }
        assertEquals(LyricSourceKind.OVERLAY_STORE, LyricSourceKind.fromToken("overlay-store"))
        assertNull(LyricSourceKind.fromToken("nope"))
        assertNull(LyricSourceKind.fromToken(null))
    }

    @Test
    fun `option keys are stable and readable`() {
        assertEquals("apple", LyricSourceKeys.APPLE)
        assertEquals("none", LyricSourceKeys.HIDDEN)
        assertEquals("overlay:translation", LyricSourceKeys.overlay(LyricLane.TRANSLATION))
        assertEquals("file:f1", LyricSourceKeys.customFile("f1"))
        assertEquals("cache:c1", LyricSourceKeys.cache("c1"))
        assertEquals("online:qm:1001", LyricSourceKeys.online(LyricSourceProviders.QQ, "1001"))
    }

    @Test
    fun `key segments cannot smuggle separators or control characters`() {
        assertEquals(
            "online:qm:1001",
            LyricSourceKeys.online("q:m", " 1001 "),
        )
        assertEquals("online:qm:12", LyricSourceKeys.online("qm", "1\u00002"))
        assertEquals(
            "file:" + "f".repeat(LyricSourceKeys.MAX_SEGMENT_LENGTH),
            LyricSourceKeys.customFile("f".repeat(LyricSourceKeys.MAX_SEGMENT_LENGTH + 20)),
        )
        assertEquals("file:unknown", LyricSourceKeys.customFile("   "))
    }

    @Test
    fun `sanitize accepts real keys and rejects everything else`() {
        assertEquals("apple", LyricSourceKeys.sanitize(" apple "))
        assertEquals("online:qm:1001", LyricSourceKeys.sanitize("online:qm:1001"))
        assertEquals("none", LyricSourceKeys.sanitize("none"))

        assertNull(LyricSourceKeys.sanitize(null))
        assertNull(LyricSourceKeys.sanitize(""))
        assertNull(LyricSourceKeys.sanitize("   "))
        assertNull(LyricSourceKeys.sanitize("two words"))
        assertNull(LyricSourceKeys.sanitize(":leading"))
        assertNull(LyricSourceKeys.sanitize("tab\there"))
        assertNull(LyricSourceKeys.sanitize("k".repeat(LyricSourceKeys.MAX_KEY_LENGTH + 1)))
    }

    @Test
    fun `provider ids map from both source vocabularies`() {
        assertEquals(LyricSourceProviders.QQ, LyricSourceProviders.fromSource(Source.QM))
        assertEquals(LyricSourceProviders.NETEASE, LyricSourceProviders.fromSource(Source.NE))
        assertEquals(LyricSourceProviders.KUWO, LyricSourceProviders.fromSource(Source.KUWO))
        assertEquals(LyricSourceProviders.KUGOU, LyricSourceProviders.fromSource(Source.KUGOU))
        assertEquals(LyricSourceProviders.LUNABEAT, LyricSourceProviders.fromSource(Source.LB))

        assertEquals(LyricSourceProviders.QQ, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.QQ))
        assertEquals(LyricSourceProviders.NETEASE, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.NETEASE))
        assertEquals(LyricSourceProviders.KUWO, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.KUWO))
        assertEquals(LyricSourceProviders.KUGOU, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.KUGOU))
        assertEquals(LyricSourceProviders.AMLL, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.AMLL))
        assertEquals(LyricSourceProviders.AM_LYRICS, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.AM_LYRICS))
        assertEquals(LyricSourceProviders.LUNABEAT, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.LUNABEAT))
        assertEquals(LyricSourceProviders.CACHE, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.AUTO_CACHE))
        assertEquals(LyricSourceProviders.FILE, LyricSourceProviders.fromCustomLyricsSource(CustomLyricsSources.MANUAL))
        assertEquals(LyricSourceProviders.FILE, LyricSourceProviders.fromCustomLyricsSource("removed-provider"))
        assertEquals(LyricSourceProviders.FILE, LyricSourceProviders.fromCustomLyricsSource(null))
    }

    @Test
    fun `the online chain order is the module's own`() {
        assertEquals(0, LyricSourceProviders.onlineRank(LyricSourceProviders.QQ))
        assertEquals(
            LyricSourceProviders.ONLINE_ORDER.size,
            LyricSourceProviders.onlineRank("unknown"),
        )
        assertTrue(LyricSourceProviders.isOnline(LyricSourceProviders.NETEASE))
        assertFalse(LyricSourceProviders.isOnline(LyricSourceProviders.APPLE))
    }

    @Test
    fun `labels are localized and never blank`() {
        assertEquals("QQ 音乐", LyricSourceText.providerLabel(LyricSourceProviders.QQ, ZH))
        assertEquals("QQ Music", LyricSourceText.providerLabel(LyricSourceProviders.QQ, EN))
        assertEquals("Apple Music", LyricSourceText.providerLabel(LyricSourceProviders.APPLE, EN))
        assertEquals("自定义文件", LyricSourceText.providerLabel(LyricSourceProviders.FILE, ZH))
        assertEquals("fresh-provider", LyricSourceText.providerLabel("fresh-provider", ZH))
        assertEquals("未知来源", LyricSourceText.providerLabel("  ", ZH))
        assertEquals("Unknown source", LyricSourceText.providerLabel("  ", EN))

        assertEquals("自定义文件（已编辑）", LyricSourceText.customFileLabel(LyricSourceProviders.FILE, true, ZH))
        assertEquals("AMLL", LyricSourceText.customFileLabel(LyricSourceProviders.AMLL, false, ZH))
        assertEquals("AMLL (edited)", LyricSourceText.customFileLabel(LyricSourceProviders.AMLL, true, EN))

        assertEquals("歌词", LyricSourceText.laneLabel(LyricLane.ORIGINAL, ZH))
        assertEquals("翻译", LyricSourceText.laneLabel(LyricLane.TRANSLATION, ZH))
        assertEquals("音译", LyricSourceText.laneLabel(LyricLane.ROMANIZATION, ZH))
        assertEquals("不显示", LyricSourceText.hiddenLabel(ZH))
        assertEquals("自动缓存", LyricSourceText.kindLabel(LyricSourceKind.CACHE, ZH))
        assertEquals("逐词 · 带翻译", LyricSourceText.appleDetail(true, true, false, ZH))
        assertEquals("Line by line", LyricSourceText.appleDetail(false, false, false, EN))
        assertEquals("YOASOBI · THE BOOK", LyricSourceText.onlineDetail("YOASOBI", "THE BOOK", ZH))
        assertEquals("在线歌词", LyricSourceText.onlineDetail("", "", ZH))
        assertTrue(LyricSourceText.previewUnavailable(ZH).isNotBlank())
        assertTrue(LyricSourceText.noOptionsLabel(ZH).isNotBlank())
        assertTrue(LyricSourceText.hideUnavailable(ZH).isNotBlank())
    }

    @Test
    fun `apply results carry the shared failure codes`() {
        assertTrue(ApplyResult.APPLIED.isApplied)
        assertFalse(ApplyResult.APPLIED.isPending)
        assertFalse(ApplyResult.APPLIED.isRejected)
        assertNull(ApplyResult.APPLIED.errorCode)

        val accepted = ApplyResult.accepted()
        assertTrue(accepted.isPending)
        assertFalse(accepted.isApplied)

        val rejected = ApplyResult.failed("该来源已不可用")
        assertTrue(rejected.isRejected)
        assertEquals(LyricSourceErrorCodes.FAILED, rejected.errorCode)
        assertEquals("该来源已不可用", rejected.message)

        val coded = ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, "拉取失败")
        assertEquals(LyricSourceErrorCodes.FETCH_FAILED, coded.errorCode)
    }

    @Test
    fun `lane and snapshot helpers expose the derived state`() {
        val apple = LyricSourceOption(
            key = LyricSourceKeys.APPLE,
            kind = LyricSourceKind.APPLE,
            providerId = LyricSourceProviders.APPLE,
            label = "Apple Music",
        )
        val online = LyricSourceOption(
            key = LyricSourceKeys.online(LyricSourceProviders.QQ, "1001"),
            kind = LyricSourceKind.ONLINE,
            providerId = LyricSourceProviders.QQ,
            label = "夜に駆ける",
            matchScore = 96,
        )

        val lane = LyricLaneState(
            lane = LyricLane.ORIGINAL,
            selected = apple,
            options = listOf(apple, online),
        )
        assertTrue(lane.appleAvailable)
        assertFalse(lane.hidden)
        assertEquals(online, lane.option(online.key))
        assertNull(lane.option(null))
        assertTrue(apple.isApple)
        assertFalse(online.isApple)
        assertFalse(apple.hasWords)

        val song = LyricSongContext(songId = 42L, title = "夜に駆ける", artist = "YOASOBI", album = "THE BOOK")
        val snapshot = LyricsSourceSnapshot(
            song = song,
            lanes = listOf(lane),
            overriddenLanes = setOf(LyricLane.TRANSLATION),
        )
        assertEquals(42L, snapshot.songId)
        assertEquals("夜に駆ける", snapshot.title)
        assertEquals("YOASOBI", snapshot.artist)
        assertEquals("THE BOOK", snapshot.album)
        assertNull(snapshot.artworkUrl)
        assertEquals(lane, snapshot.lane(LyricLane.ORIGINAL))
        assertTrue(snapshot.hasOverrides)
        assertFalse(snapshot.isEmpty)
        assertEquals(LyricLane.ALL, listOf(LyricLane.ORIGINAL, LyricLane.TRANSLATION, LyricLane.ROMANIZATION))
    }

    @Test
    fun `every custom-lyrics source maps to a provider id`() {
        val expected = mapOf(
            CustomLyricsSources.MANUAL to LyricSourceProviders.FILE,
            CustomLyricsSources.AUTO_CACHE to LyricSourceProviders.CACHE,
            CustomLyricsSources.AMLL to LyricSourceProviders.AMLL,
            CustomLyricsSources.AM_LYRICS to LyricSourceProviders.AM_LYRICS,
            CustomLyricsSources.LUNABEAT to LyricSourceProviders.LUNABEAT,
            CustomLyricsSources.QQ to LyricSourceProviders.QQ,
            CustomLyricsSources.NETEASE to LyricSourceProviders.NETEASE,
            CustomLyricsSources.KUWO to LyricSourceProviders.KUWO,
            CustomLyricsSources.KUGOU to LyricSourceProviders.KUGOU,
        )

        expected.forEach { (source, providerId) ->
            assertEquals(providerId, LyricSourceProviders.fromCustomLyricsSource(source))
            assertTrue(LyricSourceText.providerLabel(providerId, ZH).isNotBlank())
        }
    }

    @Test
    fun `a saved provider name identifies its origin`() {
        // The manifest downgrades an unsupported source tag to "manual", so the name
        // the sheet wrote is the only remaining evidence of the provider (bug B).
        assertEquals(LyricSourceProviders.QQ, LyricSourceProviders.providerIdForLabel("QQ 音乐"))
        assertEquals(LyricSourceProviders.QQ, LyricSourceProviders.providerIdForLabel(" qq music "))
        assertEquals(LyricSourceProviders.NETEASE, LyricSourceProviders.providerIdForLabel("网易云音乐"))
        assertEquals(LyricSourceProviders.NETEASE, LyricSourceProviders.providerIdForLabel("NetEase Cloud Music"))
        assertEquals(LyricSourceProviders.KUWO, LyricSourceProviders.providerIdForLabel("酷我音乐"))
        assertEquals(LyricSourceProviders.KUGOU, LyricSourceProviders.providerIdForLabel("酷狗音乐"))
        assertEquals(LyricSourceProviders.LUNABEAT, LyricSourceProviders.providerIdForLabel("Lunabeat"))
        assertEquals(LyricSourceProviders.AMLL, LyricSourceProviders.providerIdForLabel("AMLL"))
        assertEquals(LyricSourceProviders.AM_LYRICS, LyricSourceProviders.providerIdForLabel("AM-Lyrics"))
        assertNull(LyricSourceProviders.providerIdForLabel("我自己的歌词"))
        assertNull(LyricSourceProviders.providerIdForLabel("   "))
    }

    @Test
    fun `a downgraded record recovers its provider and reads as saved online`() {
        val downgraded = CustomLyricsSourceRecord.of(entry(displayName = "QQ 音乐", source = CustomLyricsSources.MANUAL))
        assertEquals(LyricSourceProviders.QQ, downgraded.providerId)
        assertTrue(downgraded.isSavedOnline)

        val keptTag = CustomLyricsSourceRecord.of(entry(displayName = "网易云音乐", source = CustomLyricsSources.NETEASE))
        assertEquals(LyricSourceProviders.NETEASE, keptTag.providerId)
        assertTrue(keptTag.isSavedOnline)

        val manual = CustomLyricsSourceRecord.of(entry(displayName = "我手写的歌词", source = CustomLyricsSources.MANUAL))
        assertEquals(LyricSourceProviders.FILE, manual.providerId)
        assertFalse(manual.isSavedOnline)

        val cached = CustomLyricsSourceRecord.of(
            entry(displayName = "自动缓存歌词 · QQ 音乐", source = CustomLyricsSources.AUTO_CACHE),
        )
        assertEquals(LyricSourceProviders.CACHE, cached.providerId)
        assertFalse(cached.isSavedOnline)
        assertTrue(cached.isAutoCache)
    }

    @Test
    fun `online and file keys parse back`() {
        assertEquals(LyricSourceProviders.QQ, LyricSourceKeys.onlineProvider("online:qm:552173242"))
        assertEquals("552173242", LyricSourceKeys.onlineRemoteId("online:qm:552173242"))
        assertNull(LyricSourceKeys.onlineProvider("apple"))
        assertNull(LyricSourceKeys.onlineProvider("online:qm"))
        assertNull(LyricSourceKeys.onlineProvider("online:"))
        assertNull(LyricSourceKeys.onlineProvider(null))

        assertEquals("f1", LyricSourceKeys.customFileId("file:f1"))
        assertEquals("c1", LyricSourceKeys.customFileId("cache:c1"))
        assertNull(LyricSourceKeys.customFileId("file:"))
        assertNull(LyricSourceKeys.customFileId("overlay:original"))
        assertNull(LyricSourceKeys.customFileId(null))
    }

    @Test
    fun `saved documents say they are saved and applied ones say they are applied`() {
        assertEquals("已保存到本地", LyricSourceText.customFileDetail(LyricSourceProviders.QQ, "QQ 音乐", ZH))
        assertEquals("Saved locally", LyricSourceText.customFileDetail(LyricSourceProviders.QQ, "QQ 音乐", EN))
        assertEquals("我写的.ttml", LyricSourceText.customFileDetail(LyricSourceProviders.FILE, "我写的.ttml", ZH))
        assertEquals("cache-1.ttml", LyricSourceText.customFileDetail(LyricSourceProviders.CACHE, "cache-1.ttml", ZH))
        assertEquals("已应用的在线歌词", LyricSourceText.appliedOnlineDetail(LyricLane.ORIGINAL, ZH))
        assertEquals("已应用的在线翻译", LyricSourceText.appliedOnlineDetail(LyricLane.TRANSLATION, ZH))
        assertEquals("Applied online source", LyricSourceText.appliedOnlineDetail(LyricLane.ORIGINAL, EN))
    }

    private fun entry(
        displayName: String,
        source: String,
    ): CustomLyricsEntry = CustomLyricsEntry(
        appleMusicId = 42L,
        displayName = displayName,
        fileId = "f1",
        sizeBytes = 1024L,
        sha256 = "0".repeat(64),
        source = source,
        userEdited = true,
    )

    private companion object {
        val ZH: Locale = Locale.SIMPLIFIED_CHINESE
        val EN: Locale = Locale.US
    }
}
