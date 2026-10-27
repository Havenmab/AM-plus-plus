package dev.amenhancer.module.hook.lyricsource

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-shape pins for the lane contract between 「补全歌词翻译与发音」 and the
 * lyrics-source panel.
 *
 * The device report this covers: QQ Music's `trans` column is the *reading*
 * (kana and romaji) for a Japanese song, the old completion wrote it into the
 * translation lane, and after a restart the panel showed the romanization as the
 * translation while 「显示发音」 had nothing to show. Three properties keep that
 * from coming back, and none of them can be executed in a JVM test (the network
 * provider and the native lyric model live in the Apple Music process):
 *
 *  1. a completed lane records the provider the panel reads back, in the module's
 *     own vocabulary;
 *  2. a provider's reading never reaches the translation lane on any write path;
 *  3. a romanization lane the overlay does not hold falls back to Apple's.
 */
class LyricsLaneSourceStructureTest {

    private val session = projectFile(
        "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
    )

    private val composite = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/CompositeOnlineSearchAutoLyricsSource.kt",
    )

    private val target = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
    )

    private val probes = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/lyricsource/LyricsSourceProbes.kt",
    )

    private val classifier = projectFile(
        "core/src/main/kotlin/dev/amenhancer/module/lyrics/online/LyricLaneClassifier.kt",
    )

    private val extraction = projectFile(
        "core/src/main/kotlin/dev/amenhancer/module/lyrics/online/OnlineTranslationExtraction.kt",
    )

    private val enrichment = projectFile(
        "core/src/main/kotlin/dev/amenhancer/module/lyrics/online/OnlineTranslationEnrichment.kt",
    )

    @Test
    fun `a completed translation is recorded with the provider the panel reads back`() {
        // The write: the lane source is the module's vocabulary ("qq"), which
        // LyricSourceProviders.fromCustomLyricsSource maps back to a brand. The
        // enrichment's own token ("QM", "apple+QM") is diagnostic only — stored
        // verbatim it would degrade to 自定义文件 in the sheet.
        assertTrue(
            session.contains(
                "translationSource = laneProvider(outcome, outcome.translationSource)",
            ),
        )
        assertTrue(
            session.contains(
                "pronunciationSource = laneProvider(outcome, outcome.pronunciationSource)",
            ),
        )
        assertTrue(session.contains("private fun laneProvider("))
        // A lane the provider did not write is never attributed to it: Apple's own
        // preserved romanization keeps no provider (only a lane the winner really
        // supplied is named).
        assertTrue(session.contains("laneSource.split('+')"))
        // The same write names the document's own provider, so the original lane
        // is not left as "已写入歌词" either.
        assertTrue(session.contains("originalSource = customSourceTag(outcome.source)"))
        assertTrue(session.contains("private fun customSourceTag("))
        assertTrue(session.contains("CustomLyricsSources.QQ"))
        // The read-back half: without it the record would be unreadable.
        assertTrue(probes.contains("translationSource = overlay.translationSource(key)"))
        assertTrue(probes.contains("val romanizationSource = overlay.pronunciationSource(key)"))
        assertTrue(probes.contains("romanizationSource = romanizationSource,"))
    }

    @Test
    fun `a provider reading never reaches the translation lane`() {
        // One shared verdict, used by every write path instead of each site
        // deciding for itself what "this looks like a translation" means.
        assertTrue(classifier.contains("fun isTranslationLike(text: String?): Boolean"))
        assertTrue(classifier.contains("fun isRomanizationLike(text: String?): Boolean"))
        assertTrue(classifier.contains("fun rebalance("))
        // Extraction re-homes the provider's two columns before anything reads
        // them; the merge and the restart replay call the same function.
        assertTrue(extraction.contains("LyricLaneClassifier.rebalance("))
        assertTrue(enrichment.contains("LyricLaneClassifier.rebalance("))
        // The completion's overlay write applies it once more, because the overlay
        // is the value Apple's 显示翻译 / 显示发音 switches are computed from.
        assertTrue(session.contains("private fun lanePureLines("))
        assertTrue(session.contains("LyricLaneClassifier.rebalance("))
        assertTrue(session.contains("translation = lanes.translation,"))
        assertTrue(session.contains("roma = lanes.romanization,"))
        // The candidate walk decides lane presence from the text too, so a reading
        // can neither be published as a translation nor win the walk over a
        // provider that really has Chinese.
        assertTrue(composite.contains("LyricLaneClassifier.isTranslationLike(it.translation)"))
        assertTrue(composite.contains("LyricLaneClassifier.isRomanizationLike(it.romanization)"))
        assertTrue(composite.contains("private fun OnlineTranslationCandidate.hasTranslationLane()"))
        assertFalse(
            "a non-slash translation column is still not a translation",
            composite.contains(
                "lines.any { OnlineTranslationContentPolicy.isMeaningful(it.translation) }",
            ),
        )
        // The whole-document path takes the same filtered lane, so a document this
        // module installs never carries a reading in its translations track: the
        // provider body is fetched with its null guard, filtered with the song's own
        // identity (the `歌名 - 歌手` header and credit blocks), and only then handed
        // to the writer — whose `from(result)` re-homes the lanes through
        // [OnlineTranslationExtraction] again.
        assertTrue(composite.contains("val result = provider.source.getLyrics(song) ?: return null"))
        assertTrue(
            composite.contains(
                "val clean = LyricLineFilter.filter(result, song.title, song.artist)",
            ),
        )
        assertTrue(composite.contains("AppleLyricTtmlWriter.from(clean)"))
        assertTrue(composite.contains("translationLines="))
    }

    @Test
    fun `a romanization lane the overlay does not hold falls back to Apple`() {
        // The overlay claims the lane only from content the store really holds ...
        assertTrue(probes.contains("hasRomanization = overlay.hasPronunciation(key)"))
        // ... an empty store never turns into "the song has no romanization":
        // Apple's own lane is measured separately, from the document it served, so
        // the sheet still offers it.
        assertTrue(probes.contains("appleCapability(appleTtml)"))
        assertTrue(probes.contains("hasRomanization = lines.any { !it.romanization.isNullOrBlank() } ||"))
        assertTrue(
            probes.contains("AppleLyricTtmlReader.transliterationLanguages(ttml).isNotEmpty()"),
        )
        // The lane's source is read from the store, never inferred from the text.
        assertTrue(probes.contains("val romanizationSource = overlay.pronunciationSource(key)"))
        // A store that belongs to another track reports nothing at all — the lane
        // falls back instead of inheriting the previous song's romanization.
        assertTrue(probes.contains("return@runCatching OverlayLyricsCapability()"))
        // And the completion never replaces the document that owns the lane: a
        // timed Apple document is completed through the overlay only.
        assertTrue(
            target.contains(
                "val lanesOnly = displayedTtml != null && TtmlTimingPolicy.hasTiming(displayedTtml)",
            ),
        )
        assertTrue(target.contains("lanesOnly=\$lanesOnly"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
