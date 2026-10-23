package dev.amenhancer.module.hook.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.amenhancer.lyricsplayer.LyricsPlayerConfig
import dev.amenhancer.lyricsplayer.LyricsPlayerPort
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.lyrics.player.PlayerDocument
import dev.amenhancer.module.lyrics.player.PlayerGeometry
import dev.amenhancer.module.lyrics.player.PlayerLine
import dev.amenhancer.module.lyrics.player.PlayerTrackInfo
import dev.amenhancer.module.lyrics.player.PlayerWord
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The host half of `LyricsPlayerPort`: every read that can fail returns `null`/`false` and
 * never throws, so a host-side surprise dims one control instead of killing the page
 * (spec §7.3/§7.4). All members are called on the main thread, once per frame for the read
 * side and about twice a second for `track()`/`artwork()`/`preferences()`.
 *
 * Sources (see `.prep/lb-player-host-data.md` for the evidence of every symbol):
 *  - document: the host's own TTML reader behind the active lyric adapter,
 *  - position/playing/duration/transport: the media3 controller the fragment already owns,
 *  - track: the controller's current `MediaMetadata`,
 *  - artwork: the bitmap the host hands to its own lyrics background layer,
 *  - geometry: the bound lyric RecyclerView and its first row's `TextView`.
 */
internal class LyricsPlayerPortImpl(
    private val contract: LyricsPlayerContract,
    /** Spec §7.3 second source: the module's own document for this song, or null. */
    private val fallback: (() -> PlayerDocument?)? = null,
) : LyricsPlayerPort {
    private var fragmentRef: WeakReference<Any>? = null
    private var viewRef: WeakReference<View>? = null
    private var controllerRef: WeakReference<Any>? = null
    private var documentCache: PlayerDocument? = null
    private var lastDocumentAttemptMs = 0L
    private var metadataAccessor: Method? = null
    private var metadataProbeLogged = false
    private var artworkBitmap: Bitmap? = null
    private var lastReportedReason: String? = null

    /** The lyrics fragment currently on screen; a different fragment drops the cached document. */
    fun attach(fragment: Any?, view: View?) {
        if (fragment != null && fragmentRef?.get() !== fragment) {
            fragmentRef = WeakReference(fragment)
            controllerRef = null
            invalidateDocument()
        }
        if (view != null) viewRef = WeakReference(view)
    }

    /** A new document was installed by the host (`w2`/`F2`): rebuild on the next poll. */
    fun invalidateDocument() {
        documentCache = null
        lastDocumentAttemptMs = 0L
    }

    /**
     * The host redrew its own lyrics background: this bitmap is the current cover.
     *
     * The page keeps the bitmap across frames while the host may recycle its own copy, so the
     * cover is copied once per track. A copy failure (huge/OOM) falls back to the host bitmap,
     * which [artwork] still guards with [Bitmap.isRecycled].
     */
    fun onArtwork(bitmap: Bitmap?) {
        artworkBitmap = bitmap?.takeIf { !it.isRecycled }?.let { source ->
            runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: source
        }
    }

    /** True while the host fragment exposes a readable lyric document source (adapter + reader). */
    fun hostDocumentAvailable(): Boolean = try {
        val fragment = fragmentRef?.get() ?: return false
        val adapter = contract.adapter(fragment) ?: return false
        contract.reader(adapter) != null
    } catch (_: Throwable) {
        false
    }

    fun release() {
        fragmentRef = null
        viewRef = null
        controllerRef = null
        documentCache = null
        artworkBitmap = null
        metadataAccessor = null
        lastDocumentAttemptMs = 0L
    }

    // ---- PlayerHostPort ------------------------------------------------------------------

    /**
     * Host native model first, then the module's own stored document, then null (spec §7.3).
     * A null result is what keeps the overlay unmounted: the page never shows an empty lyrics
     * area over Apple's own page.
     */
    override fun document(): PlayerDocument? {
        documentCache?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (now - lastDocumentAttemptMs < DOCUMENT_RETRY_MS) return null
        lastDocumentAttemptMs = now
        primaryDocument()?.let { built ->
            documentCache = built
            return built
        }
        val stored = runCatching { fallback?.invoke() }.getOrNull()?.takeIf { it.lines.isNotEmpty() }
        if (stored != null) {
            documentCache = stored
            return stored
        }
        return null
    }

    private fun primaryDocument(): PlayerDocument? = try {
        readDocument()?.takeIf { it.lines.isNotEmpty() }
    } catch (error: Throwable) {
        report("document read failed: ${error.javaClass.simpleName}")
        null
    }

    override fun positionMs(): Long? = contract.position(controller())

    override fun isPlaying(): Boolean = contract.isPlaying(controller())

    override fun geometry(): PlayerGeometry? = try {
        val fragment = fragmentRef?.get() ?: return null
        val recycler = contract.recyclerOf(fragment) ?: return null
        if (recycler.width <= 0 || recycler.height <= 0) return null
        val location = IntArray(2)
        recycler.getLocationOnScreen(location)
        val row = firstRow(recycler)
        PlayerGeometry(
            leftPx = location[0].toFloat(),
            topPx = location[1].toFloat(),
            widthPx = recycler.width.toFloat(),
            heightPx = recycler.height.toFloat(),
            textSizePx = row?.let(::firstTextSize) ?: 0f,
            lineSpacingPx = row?.height?.toFloat()?.takeIf { it > 0f } ?: 0f,
            horizontalInsetPx = row?.left?.toFloat()?.coerceAtLeast(0f) ?: 0f,
        )
    } catch (_: Throwable) {
        null
    }

    override fun seekTo(positionMs: Long): Boolean = try {
        val controller = controller() ?: return false
        contract.mediaSeekTo.invoke(controller, positionMs.coerceAtLeast(0L))
        true
    } catch (_: Throwable) {
        false
    }

    // ---- LyricsPlayerPort ----------------------------------------------------------------

    override fun track(): PlayerTrackInfo? = try {
        val controller = controller() ?: return null
        val metadata = metadata(controller) ?: return null
        val title = metadataText(contract.metadataTitle, metadata) ?: return null
        val artist = metadataText(contract.metadataArtist, metadata).orEmpty()
        PlayerTrackInfo(
            identity = metadataIdentity(metadata, title, artist),
            title = title,
            artist = artist,
            album = "",
            durationMs = contract.durationMs(controller) ?: 0L,
        )
    } catch (_: Throwable) {
        null
    }

    override fun artwork(): Bitmap? = artworkBitmap?.takeIf { !it.isRecycled }

    override fun playPause(): Boolean = try {
        val controller = controller() ?: return false
        if (contract.isPlaying(controller)) contract.mediaPause.invoke(controller)
        else contract.mediaPlay.invoke(controller)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Next/previous go through the same two controller calls the host's own chrome uses:
     * `player.a1#C0` (forward skip) falls back to `J4.B.y()` and `player.a1#E0` (backward
     * skip) falls back to `J4.B.w()` whenever the item is not 30s-skippable; the cast dialog's
     * previous button calls `J4.B.w()` too. Evidence is recorded in
     * `.prep/lb-player-host-data.md` §7.1.
     */
    override fun skipNext(): Boolean = contract.skipNext(controller())

    override fun skipPrevious(): Boolean = contract.skipPrevious(controller())

    @Suppress("DEPRECATION")
    override fun closePage(): Boolean = try {
        val activity = activity() ?: return false
        activity.onBackPressed()
        true
    } catch (_: Throwable) {
        false
    }

    override fun more(): Boolean = false

    override fun preferences(): LyricsPlayerConfig = try {
        val settings = TargetConfigClient.currentSettings()
        LyricsPlayerConfig(
            motionPercent = settings.lyricsPlayerMotionPercent,
            staggerMs = settings.lyricsPlayerStaggerMs,
            emphasisPercent = settings.lyricsPlayerEmphasisPercent,
            fontScalePercent = settings.lyricsPlayerFontScalePercent,
        )
    } catch (_: Throwable) {
        LyricsPlayerConfig()
    }

    // ---- document ------------------------------------------------------------------------

    private fun readDocument(): PlayerDocument? {
        val fragment = fragmentRef?.get() ?: return null
        val adapter = contract.adapter(fragment) ?: return null
        val reader = contract.reader(adapter) ?: return null
        val count = (contract.readerCount.invoke(reader) as? Number)?.toInt() ?: return null
        if (count <= 0 || count > MAX_LINES) return null
        val lines = ArrayList<PlayerLine>(count)
        var firstLineId = Int.MIN_VALUE
        var lastLineId = Int.MIN_VALUE
        for (index in 0 until count) {
            val line = runCatching {
                val pointer = contract.readerLine.invoke(reader, index) ?: return@runCatching null
                contract.lineResolve.invoke(pointer)
            }.getOrNull() ?: continue
            val begin = number(contract.lineBegin, line) ?: continue
            val end = number(contract.lineEnd, line) ?: continue
            if (begin < 0L || end < begin) continue
            val text = LyricsPlaybackText.plainText(string(contract.lineText, line))
            val words = readWords(runCatching { contract.lineWords.invoke(line) }.getOrNull(), text, begin, end)
            if (words.isEmpty()) continue
            val backgroundWords = readWords(
                runCatching { contract.lineBackgroundWords.invoke(line) }.getOrNull(),
                LyricsPlaybackText.plainText(string(contract.lineBackgroundText, line)),
                begin, end,
            )
            val lineId = (runCatching { contract.lineId.invoke(line) }.getOrNull() as? Number)?.toInt()
            if (lineId != null) {
                if (firstLineId == Int.MIN_VALUE) firstLineId = lineId
                lastLineId = lineId
            }
            lines += PlayerLine(
                startMs = begin,
                endMs = end,
                text = text,
                words = words,
                backgroundWords = backgroundWords,
                translation = LyricsPlaybackText.plainText(string(contract.lineTranslation, line)),
            )
        }
        if (lines.isEmpty()) return null
        return PlayerDocument(lines = lines, identity = documentIdentity(lines, firstLineId, lastLineId))
    }

    private fun readWords(vector: Any?, text: String, lineBegin: Long, lineEnd: Long): List<PlayerWord> {
        val words = ArrayList<PlayerWord>(WORD_CAPACITY)
        if (vector != null) {
            val size = (runCatching { contract.wordSize.invoke(vector) }.getOrNull() as? Number)?.toInt() ?: 0
            if (size in 1..MAX_WORDS) {
                for (index in 0 until size) {
                    val word = runCatching {
                        val pointer = contract.wordGet.invoke(vector, index.toLong()) ?: return@runCatching null
                        contract.wordResolve.invoke(pointer)
                    }.getOrNull() ?: continue
                    val begin = number(contract.wordBegin, word) ?: continue
                    val end = number(contract.wordEnd, word) ?: continue
                    if (begin < 0L || end < begin) continue
                    val raw = string(contract.wordText, word)
                    words += PlayerWord(begin, end, LyricsPlaybackText.plainText(raw, trim = false).ifEmpty { " " })
                }
            }
        }
        // A line-only document is allowed by the contract: one word spans the whole line.
        if (words.isEmpty() && text.isNotEmpty()) words += PlayerWord(lineBegin, lineEnd, text)
        return words
    }

    private fun documentIdentity(lines: List<PlayerLine>, firstLineId: Int, lastLineId: Int): String =
        "${firstLineId}:${lastLineId}:${lines.size}:${lines.first().startMs}:${lines.last().endMs}"

    // ---- playback / metadata -------------------------------------------------------------

    private fun controller(): Any? {
        controllerRef?.get()?.let { return it }
        val fragment = fragmentRef?.get() ?: return null
        val browser = contract.browser(fragment) ?: return null
        controllerRef = WeakReference(browser)
        return browser
    }

    /**
     * The controller exposes two `MediaMetadata` getters (`J4.B.X()`/`J4.B.i0()`); the DEX
     * does not say which is `getMediaMetadata`. Rather than guessing, both are probed once and
     * the one that actually carries a title wins — a wrong pick degrades to "no track" and
     * never throws.
     */
    private fun metadata(controller: Any): Any? {
        metadataAccessor?.let { accessor ->
            return runCatching { accessor.invoke(controller) }.getOrNull()
        }
        for (candidate in listOf(contract.mediaMetadataPrimary, contract.mediaMetadataAlternate)) {
            val value = runCatching { candidate.invoke(controller) }.getOrNull() ?: continue
            if (metadataText(contract.metadataTitle, value) != null) {
                metadataAccessor = candidate
                if (!metadataProbeLogged) {
                    metadataProbeLogged = true
                    ModernXposedRuntime.log("lyrics player metadata accessor: ${candidate.name}")
                }
                return value
            }
        }
        return null
    }

    private fun metadataText(field: Field, metadata: Any): String? =
        runCatching { (field.get(metadata) as? CharSequence)?.toString()?.trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    private fun metadataIdentity(metadata: Any, title: String, artist: String): String {
        val mediaId = runCatching { contract.metadataBundle.get(metadata) as? Bundle }.getOrNull()
            ?.let { bundle ->
                bundle.keySet().asSequence()
                    .mapNotNull { key -> runCatching { bundle.getString(key) }.getOrNull() }
                    .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            }
        return mediaId ?: "$title\u0000$artist"
    }

    // ---- view helpers --------------------------------------------------------------------

    private fun firstRow(recycler: View): View? {
        val group = recycler as? ViewGroup ?: return null
        for (index in 0 until minOf(group.childCount, ROW_PROBE_LIMIT)) {
            val child = group.getChildAt(index) ?: continue
            if (child.width > 0 && child.height > 0) return child
        }
        return null
    }

    /** First measurable text size inside a bound row; the page only needs a hint. */
    private fun firstTextSize(root: View): Float {
        var level = listOf(root)
        repeat(TEXT_PROBE_DEPTH) {
            level.forEach { view -> if (view is TextView && view.textSize > 0f) return view.textSize }
            level = level.flatMap { view ->
                (view as? ViewGroup)?.let { group -> (0 until group.childCount).mapNotNull(group::getChildAt) }
                    ?: emptyList()
            }
            if (level.isEmpty()) return 0f
        }
        return 0f
    }

    private fun activity(): Activity? {
        val context = viewRef?.get()?.context ?: return null
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun number(method: Method, receiver: Any): Long? =
        runCatching { (method.invoke(receiver) as? Number)?.toLong() }.getOrNull()

    private fun string(method: Method, receiver: Any): String? =
        runCatching { method.invoke(receiver) as? String }.getOrNull()

    /** One log line per distinct reason; the mount decision itself stays in the session. */
    private fun report(reason: String) {
        if (lastReportedReason == reason) return
        lastReportedReason = reason
        ModernXposedRuntime.log("lyrics player degraded: $reason")
    }

    private companion object {
        const val MAX_LINES = 400
        const val MAX_WORDS = 512
        const val WORD_CAPACITY = 16
        const val DOCUMENT_RETRY_MS = 250L
        const val ROW_PROBE_LIMIT = 4
        const val TEXT_PROBE_DEPTH = 4
    }
}
