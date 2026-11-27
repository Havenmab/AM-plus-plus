package dev.amenhancer.module.hook

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「换行弹簧」的诊断文件：`/sdcard/Download/AMPP/line-spring.log`, where a person can just open it.
 *
 * **Why a file at all.** `adb logcat` needs a computer, and the one thing a device report has to
 * carry is the whole sequence — a `line-change` with no `push`, a gap between two `tick` lines, a
 * `stuck` while the middle line stands still. A file the user can open in a file manager is the only
 * form of that which survives a reboot and needs no tooling.
 *
 * **Where it goes.** `MediaStore.Downloads` (API 29+, no storage permission): the same
 * `DISPLAY_NAME` in `Download/AMPP/` is reused when it already exists, so repeated sessions append
 * to one file instead of littering `line-spring (1).log` copies; only a missing document is
 * `insert`ed. Every line is then appended with `openOutputStream(uri, "wa")`. If the document
 * cannot be created or written — an old system, the user cleaned `Download/`, a provider restart —
 * the sink is rebuilt once, and if that fails too the trace falls back to
 * `context.getExternalFilesDir(null)/line-spring.log` (`/sdcard/Android/data/<pkg>/files/`), whose
 * real path is written into the header so a screenshot of the first line still says where it is.
 *
 * **What is in it.** One header line per session (time, host package, host version, switch state,
 * path), then the same lines the logcat tag prints, each prefixed with a millisecond timestamp. The
 * file is capped at 256 KiB: past that it is rewritten as the header plus the last 128 KiB on a line
 * boundary, so the newest evidence is what survives.
 *
 * **Cost.** One bounded append per line (~100-200 bytes), and the diagnostics are already rate
 * limited (`LyricLineSpringDiagnostics`: ≤2 lines/s per class, a 250 ms heartbeat, 200 lines per
 * session), so a whole song writes at most ~40 KiB. Every call is wrapped: a slow, full or revoked
 * provider can never take the lyric list down, and nothing is created while the feature is off.
 */
internal class LineSpringLogFile(
    context: Context,
    private val packageName: String,
    private val version: String,
    /** Read live: while the feature is off nothing is created and nothing is appended. */
    private val enabled: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val application: Context = context.applicationContext ?: context
    private val resolver: ContentResolver get() = application.contentResolver
    private val lock = Any()
    private val stamps = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val times = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Where the next line goes; null until the first line of this session is written. */
    private var target: Sink? = null

    /** Bytes we believe the current document holds, so the cap needs no read per line. */
    private var written = 0L

    /** Append one already-bounded diagnostic line; a no-op while the feature is off. */
    fun write(line: String) {
        if (!isEnabled()) return
        synchronized(lock) {
            val bytes = (times.format(Date(now())) + " " + line + "\n").toByteArray(Charsets.UTF_8)
            var sink = target ?: open()
            if (sink == null) return
            if (!append(sink, bytes)) {
                // The document was removed behind us — the user cleaned Download/, the provider
                // restarted, the app-specific file went away. Rebuild it once; open() already falls
                // back to the app's own external directory, so this is the second and last attempt.
                target = null
                sink = open() ?: return
                if (!append(sink, bytes)) {
                    target = null
                    return
                }
            }
            target = sink
            written += bytes.size
            trim(sink)
        }
    }

    // ---- where ---------------------------------------------------------------------------------

    /** The live sink, creating it (and writing this session's header) on first use. */
    private fun open(): Sink? {
        val download = runCatching { downloadSink() }.getOrNull()
        if (download != null && head(download)) return download
        val fallback = runCatching { fallbackSink() }.getOrNull()
        if (fallback != null && head(fallback)) return fallback
        return null
    }

    /**
     * The `Download/AMPP/line-spring.log` document, reused when present.
     *
     * `RELATIVE_PATH` is the MediaStore spelling of `Download/AMPP` (the provider keeps the trailing
     * slash), and `IS_PENDING = 0` publishes the document immediately so a file manager shows it
     * while the song is still playing. Below API 29 neither column exists, which is one of the two
     * reasons this returns null and the fallback takes over.
     */
    private fun downloadSink(): Sink? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                arrayOf(FILE_NAME, RELATIVE_DIRECTORY),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()
        if (existing != null) return DownloadSink(ContentUris.withAppendedId(collection, existing))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIRECTORY)
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return DownloadSink(uri)
    }

    /** `getExternalFilesDir(null)/line-spring.log`: private, but reachable from a file manager. */
    private fun fallbackSink(): Sink? {
        val directory = runCatching { application.getExternalFilesDir(null) }.getOrNull()
            ?: runCatching { application.filesDir }.getOrNull()
            ?: return null
        runCatching { directory.mkdirs() }
        return FileSink(File(directory, FILE_NAME))
    }

    // ---- writing -------------------------------------------------------------------------------

    /** Write this session's one header line; false when the sink cannot be written at all. */
    private fun head(sink: Sink): Boolean {
        val bytes = (headerLine(sink) + "\n").toByteArray(Charsets.UTF_8)
        val before = runCatching { sink.size() }.getOrDefault(0L).coerceAtLeast(0L)
        if (!append(sink, bytes)) return false
        written = before + bytes.size
        return true
    }

    private fun append(sink: Sink, bytes: ByteArray): Boolean = runCatching {
        sink.open(truncate = false)?.use { stream ->
            stream.write(bytes)
            stream.flush()
            true
        } ?: false
    }.getOrDefault(false)

    /**
     * Keep the document under [MAX_BYTES] by rewriting it as the header plus the last [KEEP_BYTES].
     *
     * The cut is moved forward to the next newline so the file never starts mid-line, and the header
     * is written again: a truncated file still says which build, which switch state and which path
     * produced it.
     */
    private fun trim(sink: Sink) {
        if (written <= MAX_BYTES) return
        runCatching {
            val bytes = sink.read()
            if (bytes == null || bytes.size <= KEEP_BYTES) {
                written = bytes?.size?.toLong() ?: 0L
                return@runCatching
            }
            var cut = bytes.size - KEEP_BYTES
            while (cut < bytes.size && bytes[cut] != NEWLINE) cut += 1
            if (cut < bytes.size) cut += 1
            val tail = bytes.copyOfRange(cut, bytes.size)
            val head = (headerLine(sink) + "\n").toByteArray(Charsets.UTF_8)
            sink.open(truncate = true)?.use { stream ->
                stream.write(head)
                stream.write(tail)
                stream.flush()
            }
            written = head.size.toLong() + tail.size
        }
    }

    // ---- the two places a line can live ---------------------------------------------------------

    private interface Sink {
        /** The path a person opens, for the header and for a bug report. */
        val path: String

        /** Append (`truncate = false`) or start the file over (`truncate = true`); null when gone. */
        fun open(truncate: Boolean): OutputStream?

        /** The whole document, for the truncation pass; null when it cannot be read. */
        fun read(): ByteArray?

        /** Current size in bytes, 0 when unknown. */
        fun size(): Long
    }

    private inner class DownloadSink(private val uri: Uri) : Sink {
        override val path: String get() = DOWNLOAD_PATH

        override fun open(truncate: Boolean): OutputStream? =
            runCatching { resolver.openOutputStream(uri, if (truncate) "wt" else "wa") }.getOrNull()

        override fun read(): ByteArray? = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()

        override fun size(): Long = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
    }

    private inner class FileSink(private val file: File) : Sink {
        override val path: String get() = file.absolutePath

        override fun open(truncate: Boolean): OutputStream? =
            runCatching { FileOutputStream(file, !truncate) }.getOrNull()

        override fun read(): ByteArray? = runCatching {
            if (file.exists()) file.readBytes() else null
        }.getOrNull()

        override fun size(): Long = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)
    }

    // ---- header --------------------------------------------------------------------------------

    /**
     * `# AMPP line-spring trace  2026-11-27 22:51:43.123  pkg=…  version=…  enabled=…  path=…`
     *
     * The path is on the header itself so that a log which only ever shows its first line still
     * says whether it is the Download copy or the app-specific fallback.
     */
    private fun headerLine(sink: Sink): String =
        "# AMPP line-spring trace  " + stamps.format(Date(now())) +
            "  pkg=" + packageName +
            "  version=" + version +
            "  enabled=" + (if (isEnabled()) "on" else "off") +
            "  path=" + sink.path

    private fun isEnabled(): Boolean = runCatching(enabled).getOrDefault(false)

    private fun now(): Long = runCatching(clock).getOrDefault(0L)

    private companion object {
        const val FILE_NAME = "line-spring.log"

        const val MIME_TYPE = "text/plain"

        /** `Download/AMPP` in MediaStore spelling; the provider stores the trailing slash. */
        val RELATIVE_DIRECTORY: String = Environment.DIRECTORY_DOWNLOADS + "/AMPP/"

        /** The path a user opens in MT 管理器; `/sdcard` is the same volume as `/storage/emulated/0`. */
        const val DOWNLOAD_PATH: String = "/sdcard/Download/AMPP/line-spring.log"

        /** 256 KiB, then the file keeps only its newest [KEEP_BYTES]. */
        const val MAX_BYTES: Long = 256L * 1024L
        const val KEEP_BYTES: Int = 128 * 1024

        const val NEWLINE: Byte = 0x0A
    }
}
