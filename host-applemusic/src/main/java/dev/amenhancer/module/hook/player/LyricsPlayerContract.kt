package dev.amenhancer.module.hook.player

import android.graphics.Bitmap
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.hook.FragmentMemberRequirements
import dev.amenhancer.module.hook.FragmentProfileMembers
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.json.JSONObject

/**
 * Every private host member the full-page player needs, resolved once from the exact
 * production build before a single hook is registered (same discipline as
 * `LyricWrapSweepContract` and `AppleMusicLyricBlurContract`).
 *
 * The profile section is `lyricsPlayer` (contractVersion 1). Its descriptors come from the
 * 1609 DEX dumps recorded in `.prep/lb-player-host-data.md`; a mismatch fails the
 * constructor, and the target then degrades to the native lyrics page instead of mounting a
 * half-wired overlay.
 *
 * Read side is deliberately narrow: the document comes from the host's own TTML reader
 * (`R0#x()` → `ttml.j`), playback from the media3 `MediaController` the lyrics fragment
 * already holds, artwork from the host's own background layer. Nothing here creates host
 * objects, so a missing member can never leave a partially constructed document behind.
 */
internal class LyricsPlayerContract(
    private val profile: AppleMusicHostProfile,
    loader: ClassLoader,
) {
    private val names: JSONObject = checkNotNull(profile.document.optJSONObject("lyricsPlayer")) {
        "lyricsPlayer profile section is missing"
    }
    private val classes = names.getJSONObject("classes")
    private val members = FragmentProfileMembers(
        loader, names, "lyricsPlayer",
        FragmentMemberRequirements(
            methods = setOf(
                "fragmentCreateView", "fragmentResume", "fragmentPause", "fragmentDestroyView",
                "fragmentInstallDocument", "fragmentPresentDocument",
                "activityStop",
                "mediaBrowser", "mediaPosition", "mediaPlaying", "mediaPlaybackState",
                "mediaDuration", "mediaSeekTo", "mediaPlay", "mediaPause",
                "mediaSkipNext", "mediaSkipPrevious",
                "mediaMetadataPrimary", "mediaMetadataAlternate",
                "lineAdapterReader", "wordAdapterReader", "readerCount", "readerLine",
                "lineResolve", "lineText", "lineTranslation", "lineBackgroundText",
                "lineWords", "lineBackgroundWords", "lineId", "lineBegin", "lineEnd",
                "wordSize", "wordGet", "wordResolve", "wordText", "wordBegin", "wordEnd",
                "wordWhitespace",
                "artworkSet", "artworkDetach",
            ),
            fields = setOf(
                "fragmentAdapter", "fragmentBinding", "bindingRecycler",
                "metadataTitle", "metadataArtist", "metadataBundle",
            ),
        ),
    )

    val fragment: Class<*> = type("fragment")
    val lineAdapter: Class<*> = type("lineAdapter")
    val wordAdapter: Class<*> = type("wordAdapter")
    val binding: Class<*> = type("binding")
    val recycler: Class<*> = type("recycler")
    val mediaController: Class<*> = type("mediaController")
    val mediaMetadata: Class<*> = type("mediaMetadata")
    val reader: Class<*> = type("reader")
    val timing: Class<*> = type("timing")
    val artworkLayer: Class<*> = type("artworkLayer")
    val activity: Class<*> = type("activity")

    val fragmentCreateView: Method = method("fragmentCreateView")
    val fragmentResume: Method = method("fragmentResume")
    val fragmentPause: Method = method("fragmentPause")
    val fragmentDestroyView: Method = method("fragmentDestroyView")
    val fragmentInstallDocument: Method = method("fragmentInstallDocument")
    val fragmentPresentDocument: Method = method("fragmentPresentDocument")
    val activityStop: Method = method("activityStop")

    val mediaBrowser: Method = method("mediaBrowser")
    val mediaPosition: Method = method("mediaPosition")
    val mediaPlaying: Method = method("mediaPlaying")
    val mediaPlaybackState: Method = method("mediaPlaybackState")
    val mediaDuration: Method = method("mediaDuration")
    val mediaSeekTo: Method = method("mediaSeekTo")
    val mediaPlay: Method = method("mediaPlay")
    val mediaPause: Method = method("mediaPause")
    val mediaSkipNext: Method = method("mediaSkipNext")
    val mediaSkipPrevious: Method = method("mediaSkipPrevious")
    val mediaMetadataPrimary: Method = method("mediaMetadataPrimary")
    val mediaMetadataAlternate: Method = method("mediaMetadataAlternate")

    val lineAdapterReader: Method = method("lineAdapterReader")
    val wordAdapterReader: Method = method("wordAdapterReader")
    val readerCount: Method = method("readerCount")
    val readerLine: Method = method("readerLine")
    val lineResolve: Method = method("lineResolve")
    val lineText: Method = method("lineText")
    val lineTranslation: Method = method("lineTranslation")
    val lineBackgroundText: Method = method("lineBackgroundText")
    val lineWords: Method = method("lineWords")
    val lineBackgroundWords: Method = method("lineBackgroundWords")
    val lineId: Method = method("lineId")
    val lineBegin: Method = method("lineBegin")
    val lineEnd: Method = method("lineEnd")

    val wordSize: Method = method("wordSize")
    val wordGet: Method = method("wordGet")
    val wordResolve: Method = method("wordResolve")
    val wordText: Method = method("wordText")
    val wordBegin: Method = method("wordBegin")
    val wordEnd: Method = method("wordEnd")
    val wordWhitespace: Method = method("wordWhitespace")

    val artworkSet: Method = method("artworkSet")
    val artworkDetach: Method = method("artworkDetach")

    val fragmentAdapter: Field = field("fragmentAdapter")
    val fragmentBinding: Field = field("fragmentBinding")
    val bindingRecycler: Field = field("bindingRecycler")
    val metadataTitle: Field = field("metadataTitle")
    val metadataArtist: Field = field("metadataArtist")
    val metadataBundle: Field = field("metadataBundle")

    init {
        check(profile.productionEnabled && names.getInt("contractVersion") == 1)
        // Lifecycle and document seams are declared on the lyrics fragment itself.
        check(fragmentCreateView.declaringClass == fragment)
        check(fragmentResume.declaringClass == fragment && fragmentPause.declaringClass == fragment &&
            fragmentDestroyView.declaringClass == fragment)
        check(fragmentInstallDocument.declaringClass == fragment &&
            fragmentPresentDocument.declaringClass == fragment)
        check(activityStop.declaringClass == activity)
        // The reader is reached through the adapter that currently owns the recycled rows.
        check(lineAdapterReader.declaringClass == lineAdapter && wordAdapterReader.declaringClass == wordAdapter &&
            lineAdapterReader.returnType == reader && wordAdapterReader.returnType == reader)
        check(readerCount.declaringClass == reader && readerLine.declaringClass == reader)
        check(lineResolve.declaringClass == readerLine.returnType)
        check(lineText.declaringClass == lineResolve.returnType &&
            lineTranslation.declaringClass == lineText.declaringClass &&
            lineBackgroundText.declaringClass == lineText.declaringClass &&
            lineId.declaringClass == lineText.declaringClass)
        check(timing.isAssignableFrom(lineResolve.returnType) && timing.isAssignableFrom(wordResolve.returnType))
        check(lineBegin.declaringClass == timing && lineEnd.declaringClass == timing)
        check(lineWords.returnType == wordSize.declaringClass && lineBackgroundWords.returnType == wordSize.declaringClass)
        check(wordSize.declaringClass == lineWords.returnType && wordGet.declaringClass == wordSize.declaringClass)
        check(wordResolve.declaringClass == wordGet.returnType)
        check(wordText.declaringClass == wordResolve.returnType && wordWhitespace.declaringClass == wordText.declaringClass)
        check(wordBegin.declaringClass == timing && wordEnd.declaringClass == timing)
        // Playback lives on the media3 controller the lyrics fragment already owns; the browser
        // getter must be callable on the fragment itself (same check lyricWrapSweep uses).
        check(mediaController.isAssignableFrom(mediaBrowser.returnType))
        check(mediaBrowser.declaringClass.isAssignableFrom(fragment))
        listOf(mediaPosition, mediaPlaying, mediaPlaybackState, mediaDuration, mediaSeekTo, mediaPlay, mediaPause,
            mediaSkipNext, mediaSkipPrevious, mediaMetadataPrimary, mediaMetadataAlternate).forEach { method ->
            check(method.declaringClass == mediaController) { "playback member off the controller: ${method.name}" }
        }
        check(mediaMetadataPrimary.returnType == mediaMetadata && mediaMetadataAlternate.returnType == mediaMetadata)
        check(metadataTitle.declaringClass == mediaMetadata && metadataArtist.declaringClass == mediaMetadata &&
            metadataBundle.declaringClass == mediaMetadata)
        // Artwork is the bitmap the host itself hands to its lyrics background layer.
        check(artworkSet.declaringClass == artworkLayer && artworkDetach.declaringClass == artworkLayer)
        check(artworkSet.parameterTypes.contentEquals(arrayOf(Bitmap::class.java)))
        // Geometry is read off the bound lyric rows.
        check(fragmentBinding.type == binding && bindingRecycler.declaringClass == binding)
        check(recycler.isAssignableFrom(bindingRecycler.type))
    }

    private fun type(role: String): Class<*> = members.type(classes.getString(role))
    fun method(role: String): Method = members.method(role)
    fun field(role: String): Field = members.field(role)

    fun isLineAdapter(adapter: Any?): Boolean = adapter != null && lineAdapter.isInstance(adapter)

    /** The reader of whichever adapter currently renders the rows (`R0` or `A`). */
    fun reader(adapter: Any): Any? = runCatching {
        val method = if (isLineAdapter(adapter)) lineAdapterReader else wordAdapterReader
        method.invoke(adapter)
    }.getOrNull()

    fun adapter(fragment: Any): Any? = runCatching { fragmentAdapter.get(fragment) }.getOrNull()

    fun recyclerOf(fragment: Any): View? = runCatching {
        val bound = fragmentBinding.get(fragment)
        val view = bound?.let { bindingRecycler.get(it) } as? View
        view?.takeIf { recycler.isInstance(it) }
    }.getOrNull()

    fun browser(fragment: Any): Any? = runCatching { mediaBrowser.invoke(fragment) }.getOrNull()

    fun position(controller: Any?): Long? =
        runCatching { (mediaPosition.invoke(controller) as? Number)?.toLong()?.takeIf { it >= 0L } }.getOrNull()

    fun playbackState(controller: Any?): Int? =
        runCatching { (mediaPlaybackState.invoke(controller) as? Number)?.toInt() }.getOrNull()

    fun isPlaying(controller: Any?): Boolean {
        runCatching { mediaPlaying.invoke(controller) as? Boolean }.getOrNull()?.let { return it }
        // media3 PlaybackState.STATE_PLAYING == 3; only a positive read may fall back.
        return playbackState(controller) == PLAYBACK_STATE_PLAYING
    }

    fun durationMs(controller: Any?): Long? =
        runCatching { (mediaDuration.invoke(controller) as? Number)?.toLong()?.takeIf { it > 0L } }.getOrNull()

    /** The host's own previous/next calls (see the section evidence); false when unavailable. */
    fun skipNext(controller: Any?): Boolean =
        runCatching { mediaSkipNext.invoke(controller); true }.getOrDefault(false)

    fun skipPrevious(controller: Any?): Boolean =
        runCatching { mediaSkipPrevious.invoke(controller); true }.getOrDefault(false)

    private companion object {
        const val PLAYBACK_STATE_PLAYING = 3
    }
}
