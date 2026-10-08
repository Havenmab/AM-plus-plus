package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.ApplePronunciationVisibilityPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricModelPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.OnlineTranslationContentPolicy
import dev.amenhancer.module.lyrics.online.RomanizationPolicy
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsPronunciationLanguages
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsTranslationLanguages
import java.util.concurrent.ConcurrentHashMap

/**
 * HLE's model-level delivery for translation/pronunciation, ported onto the
 * fork's hook runtime.
 *
 * The document lane edits Apple's own TTML head, but the app does not render an
 * injected `<transliterations>` track on Apple Music 7.0.0-beta (1606):
 * device-confirmed, the log shows `publish … pronunciationSource=QM
 * pronunciationLines=34` and `inject … published=true`, yet the screen shows no
 * romanization, while HLE shows it on the same device. HLE never relies on the
 * document for this: it asks the app's own lyric model.
 *
 * This installer reproduces HLE's behaviour with the fork's mechanism:
 *
 *  - `PlayerLyricsViewModel.buildTimeRangeToLyricsMap(SongInfoPtr)` is the
 *    native-model seam. Before it runs, the pointer is dereferenced to the
 *    `SongInfoNative` model and the per-class lyric hooks are installed, exactly
 *    as HLE does from `LYRICS_VIEW_MODEL_BUILD`.
 *  - Each line's `getHtmlPronunciationLineText` / `getHtmlTranslationLineText`
 *    is overridden to return the timing-keyed online lane only when Apple's own
 *    value is absent, so Apple's data always wins (HLE's `selectAppleLyricsText`
 *    with the official value first).
 *  - The song's `hasPronunciation` / `hasTranslation` (and their `set*`
 *    siblings) advertise the online lanes, so the UI considers the content
 *    available; the Mandarin rule still returns false for a hidden song.
 *  - The preferred-language request arrays are expanded with
 *    `ja-Latn`/`ko-Latn`/`zh-Latn` (HLE's
 *    `hookAppleLyricsPreferredLanguages`), and the official pronunciation
 *    language match returns the third-party fallback language (system language
 *    when it is a Latin tag, else `und-Latn`).
 *  - `setPronunciation` is handed Apple's own advertised Latin language whenever
 *    the song offers one, so the third-party lane can never displace it; the
 *    third-party fallback is used only when Apple offers no lane of its own. This
 *    is deliberately not gated on the per-line `getHtmlPronunciationLineText`
 *    probe as HLE gates it: on 1606 that getter stays empty until a
 *    pronunciation language has already been selected, so the strict mirror
 *    always took the fallback and overwrote Apple's lane (device log:
 *    `languages=ja-Latn officialPronunciation=false` becoming `und-Latn`).
 *    The HLE probe is still evaluated, per call as HLE does, for the availability
 *    override and the diagnostic.
 *
 * Every step fails open: an unresolved profile target, a missing member name, a
 * malformed vector or a throwing getter leaves Apple's own value in place. The
 * member names come from the exact 1606 profile's `LYRICS_VIEW_MODEL_LOAD`
 * `runtimeMemberNames` dictionary ([AppleMusicHookProfiles]); a profile without
 * it (for example 6.5.2/6.5.3) makes this installer a no-op.
 */
internal class AppleNativeLyricModelHooks(
    private val resolver: AppleMusicHookResolver,
    private val overlay: NativeLyricOverlayStore,
    private val enabled: Boolean,
    private val hideMandarinPinyin: Boolean,
    private val genreFor: (Long) -> String?,
    private val scope: HookRegistrationScope? = null,
    private val log: (String) -> Unit = { ModernXposedRuntime.log(it) },
) {
    private val diagnostic = TrackScopedDiagnostics(log, MAX_DIAGNOSTIC_LINES)
    private val installed = ConcurrentHashMap.newKeySet<String>()
    private val rawRead = ThreadLocal<Boolean>()

    /** The exact profile's member-name dictionary; empty means "not pinned here". */
    private val nativeNames: Map<AppleMusicRuntimeMember, String> =
        AppleMusicHookProfiles
            .exactTargets(resolver.version, AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)
            .firstOrNull()
            ?.runtimeMemberNames
            .orEmpty()

    @Volatile
    private var modelSongId: Long = 0L

    @Volatile
    private var systemLyricsLanguage: String? = null

    @Volatile
    private var applePronunciationLanguages: List<String> = emptyList()

    @Volatile
    private var mandarinHidden: Boolean = false

    fun install() {
        if (!enabled) {
            log("online-translation native-write skipped: translation/pronunciation is off")
            return
        }
        if (nativeNames.isEmpty()) {
            log(
                "online-translation native-write unavailable: " +
                    "${resolver.version.displayName} pins no LYRICS_VIEW_MODEL_LOAD members",
            )
            return
        }
        installNativeModelSeam()
        installPreferredLanguageExpansion()
        installPronunciationLanguageMatch()
    }

    /**
     * HLE hooks `LYRICS_VIEW_MODEL_BUILD` because its single argument is the
     * `SongInfoPtr` that is about to become the model the UI reads. Nothing is
     * changed when the profile cannot resolve the seam.
     */
    private fun installNativeModelSeam() {
        val method = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD).method
        }.getOrNull() ?: run {
            log("online-translation native-write seam unavailable on ${resolver.version.displayName}")
            return
        }
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val pointer = param.args.getOrNull(0) ?: return@runCatching
                    val songNative = pointerGet(pointer) ?: return@runCatching
                    ensureNativeModel(songNative, param.thisObject)
                }.onFailure { error ->
                    log("online-translation native-write ensure failed: ${error.message}")
                }
            }
        }, scope)
    }

    /**
     * The second trigger: Apple's lyric-result presentation (`I2`/`w2`) receives
     * the `SongInfoPtr` that is becoming visible. HLE installs its model hooks
     * there too, so the delivery still happens when the view-model build seam is
     * not the path that carries the pointer.
     */
    fun onLyricsPointer(pointer: Any?) {
        if (!enabled || nativeNames.isEmpty()) return
        runCatching {
            val songNative = pointerGet(pointer) ?: return@runCatching
            ensureNativeModel(songNative, viewModel = null)
        }.onFailure { error ->
            log("online-translation native-write pointer ensure failed: ${error.message}")
        }
    }

    private fun ensureNativeModel(songNative: Any, viewModel: Any?) {
        val songId = nativeSongId(songNative)
        modelSongId = songId
        if (viewModel != null) {
            systemLyricsLanguage = call(
                viewModel,
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
            ) as? String
        }
        val languages = songPronunciationLanguages(songNative)
        applePronunciationLanguages = languages
        mandarinHidden = isMandarinHidden(languages)

        installSongAvailabilityHooks(songNative.javaClass)
        val lines = nativeLines(songNative)
        // HLE-compatible per-call probe as the build-time reading, for the
        // diagnostic and for the "did Apple populate the lines yet" signal. It is
        // not the decision input: on 1606 it is false until a language is
        // selected, which is exactly why the selection may not gate Apple's own
        // lane behind it.
        val officialAtBuild = hasValidOfficialPronunciation(songNative)
        lines.map { it.javaClass }.distinct().forEach(::installLineTextHooks)
        val selectedLanguage = applyAppleNativePronunciationSelection(songNative, languages)
        reportNativeWrite(
            lines = lines,
            officialAtBuild = officialAtBuild,
            officialNow = hasValidOfficialPronunciation(songNative),
            selectedLanguage = selectedLanguage,
        )
    }

    /**
     * HLE's `hasValidOfficialRomanization`, evaluated on every call the way HLE
     * evaluates it (from the availability override and from the selection) and
     * never once at BUILD time. On 1606 Apple fills
     * `getHtmlPronunciationLineText` only after a pronunciation language has been
     * selected, so the build-time reading is always false: the device log's
     * `officialPronunciation=false ×100` sits beside Apple's own
     * `languages=ja-Latn`. Reading it per call lets Apple's value become visible
     * as soon as the app has selected a track. Raw reads bypass our own getter, so
     * the online lane can never masquerade as Apple's.
     */
    private fun hasValidOfficialPronunciation(songNative: Any?): Boolean {
        if (songNative == null) return false
        if (isMandarinHidden(songPronunciationLanguages(songNative))) return false
        return nativeLines(songNative).any { line ->
            val text = rawLineText(line)
            val pronunciation = withRawRead {
                call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD) as? String
            }
            !text.isNullOrBlank() &&
                RomanizationPolicy.sanitize(text, pronunciation) != null
        }
    }

    /** Apple's advertised pronunciation languages; updates the cache when present. */
    private fun songPronunciationLanguages(songNative: Any?): List<String> {
        val languages = vectorStrings(
            call(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD),
        )
        if (languages.isNotEmpty()) applePronunciationLanguages = languages
        return languages
    }

    private fun isMandarinHidden(languages: List<String>): Boolean =
        ApplePronunciationVisibilityPolicy.shouldHide(
            genre = genreFor(modelSongId).orEmpty().takeIf(String::isNotBlank),
            pronunciationLanguages = languages,
            hideMandarinPinyin = hideMandarinPinyin,
        )

    private fun installSongAvailabilityHooks(clazz: Class<*>) {
        listOf(
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD,
            AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD,
        ).forEach { member ->
            installBooleanAvailability(clazz, member) { _, original ->
                NativeLyricModelPolicy.hasTranslationAvailability(
                    original = original,
                    enabled = enabled,
                    hasOnlineTranslation = enabled && overlay.hasTranslation(currentSongId()),
                )
            }
        }
        listOf(
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD,
            AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD,
        ).forEach { member ->
            installBooleanAvailability(clazz, member) { song, original ->
                // Per call, exactly as HLE re-runs `hasValidOfficialRomanization`
                // from this override. Apple's advertised Latin lane is itself
                // proof of an official pronunciation, so availability never
                // withdraws Apple's own value while the per-line probe is still
                // empty.
                val languages = songPronunciationLanguages(song)
                NativeLyricModelPolicy.hasPronunciationAvailability(
                    original = original,
                    enabled = enabled,
                    hasOnlinePronunciation = enabled && overlay.hasPronunciation(currentSongId()),
                    hasValidOfficialPronunciation = hasValidOfficialPronunciation(song) ||
                        NativeLyricModelPolicy.officialPronunciationLanguage(languages) != null,
                    mandarinHidden = isMandarinHidden(languages),
                )
            }
        }
    }

    /**
     * HLE's `applyAppleNativePronunciationSelection`: hand the app's own setter
     * Apple's official Latin language when the song advertises one, otherwise the
     * third-party fallback. Apple's own lane always wins, so the third-party
     * language can never displace it. Skipped entirely while the Mandarin rule
     * hides the song. Returns the language that was selected, for the diagnostic.
     */
    private fun applyAppleNativePronunciationSelection(
        songNative: Any,
        languages: List<String>,
    ): String? {
        if (mandarinHidden) return null
        val language = NativeLyricModelPolicy.selectPronunciationLanguage(
            appleLanguages = languages,
            thirdPartyFallbackLanguage = fallbackLanguage(languages),
        ) ?: return null
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD] ?: return null
        runCatching { AppleReflection.call(songNative, name, language) }
            .onFailure { error ->
                log("online-translation native-write selectPronunciation failed: ${error.message}")
            }
        return language
    }

    private fun installLineTextHooks(clazz: Class<*>) {
        installLineGetter(
            clazz = clazz,
            member = AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD,
        ) { line, original ->
            OnlineTranslationContentPolicy.sanitize(original) ?: onlineTranslation(line)
        }
        installLineGetter(
            clazz = clazz,
            member = AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
        ) { line, original ->
            if (mandarinHidden) {
                ""
            } else {
                val text = rawLineText(line)
                RomanizationPolicy.sanitize(text, original) ?: onlinePronunciation(line, text)
            }
        }
    }

    /**
     * Installs a zero-argument String getter override. [resolve] returns the
     * replacement, or null to keep the method's own result; the callback never
     * sees a value it produced itself (raw reads bypass it).
     */
    private fun installLineGetter(
        clazz: Class<*>,
        member: AppleMusicRuntimeMember,
        resolve: (Any, String?) -> Any?,
    ) {
        val name = nativeNames[member] ?: return
        val method = AppleReflection.findMethodOrNull(clazz, name, parameterCount = 0) ?: return
        if (method.returnType != String::class.java) return
        if (!installed.add("${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (rawRead.get() == true) return
                val line = param.thisObject ?: return
                runCatching {
                    val replacement = resolve(line, param.result as? String)
                    if (replacement != null) param.result = replacement
                }
            }
        }, scope)
    }

    private fun installBooleanAvailability(
        clazz: Class<*>,
        member: AppleMusicRuntimeMember,
        resolve: (Any?, Boolean) -> Boolean,
    ) {
        val name = nativeNames[member] ?: return
        val method = AppleReflection.findMethodOrNull(clazz, name, parameterCount = 1) ?: return
        if (method.returnType != java.lang.Boolean.TYPE) return
        if (!installed.add("${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = param.result as? Boolean ?: return
                runCatching { param.result = resolve(param.thisObject, original) }
            }
        }, scope)
    }

    /**
     * HLE's `hookAppleLyricsPreferredLanguages`: expand both `String[]` request
     * arrays so the UI asks for the Latn tags the online lane can satisfy.
     */
    private fun installPreferredLanguageExpansion() {
        val clazz = runCatching {
            resolver.resolveClass(AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST).clazz
        }.getOrNull() ?: return
        clazz.declaredConstructors.forEach { constructor ->
            val stringArrayIndexes = constructor.parameterTypes.indices.filter { index ->
                constructor.parameterTypes[index] == Array<String>::class.java
            }
            if (stringArrayIndexes.size != 2) return@forEach
            if (!installed.add("${constructor.declaringClass.name}<init>${stringArrayIndexes.joinToString()}")) {
                return@forEach
            }
            val translationIndex = stringArrayIndexes.first()
            val pronunciationIndex = stringArrayIndexes.last()
            runCatching { constructor.isAccessible = true }
            ModernXposedRuntime.hookMethod(constructor, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        (param.args.getOrNull(translationIndex) as? Array<*>)?.let { values ->
                            param.args[translationIndex] = expandAppleLyricsTranslationLanguages(
                                values.filterIsInstance<String>(),
                            ).toTypedArray()
                        }
                        (param.args.getOrNull(pronunciationIndex) as? Array<*>)?.let { values ->
                            param.args[pronunciationIndex] = expandAppleLyricsPronunciationLanguages(
                                values.filterIsInstance<String>(),
                            ).toTypedArray()
                        }
                    }
                }
            }, scope)
        }
    }

    /**
     * HLE's `hookAppleOfficialPronunciationLanguageMatching`: never match a
     * Mandarin pronunciation while hidden, and fall back to the third-party
     * language when Apple itself matches nothing.
     */
    private fun installPronunciationLanguageMatch() {
        val method = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH).method
        }.getOrNull() ?: return
        if (!installed.add("${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val appleLanguages = (param.args.firstOrNull() as? Array<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                    if (
                        ApplePronunciationVisibilityPolicy.shouldHide(
                            genre = genreFor(modelSongId).orEmpty().takeIf(String::isNotBlank),
                            pronunciationLanguages = appleLanguages,
                            hideMandarinPinyin = hideMandarinPinyin,
                        )
                    ) {
                        param.result = null
                        return@runCatching
                    }
                    param.result = NativeLyricModelPolicy.selectLanguage(
                        systemMatch = param.result as? String,
                        appleLanguages = appleLanguages,
                        onlineFallbackLanguage = fallbackLanguage(),
                    )
                }
            }
        }, scope)
    }

    private fun fallbackLanguage(languages: List<String> = applePronunciationLanguages): String? =
        NativeLyricModelPolicy.thirdPartyPronunciationFallbackLanguage(
            systemLanguage = systemLyricsLanguage,
            enabled = enabled,
            hasOnlinePronunciation = enabled && overlay.hasPronunciation(currentSongId()),
            hideMandarinPinyin = hideMandarinPinyin,
            pronunciationLanguages = languages,
            genre = genreFor(modelSongId).orEmpty().takeIf(String::isNotBlank),
        )

    private fun onlineTranslation(line: Any): String? {
        if (!enabled) return null
        val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)) ?: return null
        val end = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD)) ?: return null
        return overlay.translation(currentSongId(), begin, end, rawLineText(line))
    }

    private fun onlinePronunciation(line: Any, text: String?): String? {
        if (!enabled || mandarinHidden) return null
        val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)) ?: return null
        val end = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD)) ?: return null
        return RomanizationPolicy.sanitize(
            originalText = text,
            pronunciation = overlay.pronunciation(currentSongId(), begin, end, text),
        )
    }

    /**
     * The device-facing per-track proof that the native model was written.
     * `officialPronunciation` is the per-call reading that now drives the
     * decision; `officialAtBuild` is the old build-time reading, kept so a log
     * can show the two diverging; `officialLanguage` is Apple's advertised own
     * lane and `selectedLanguage` what `setPronunciation` was handed.
     */
    private fun reportNativeWrite(
        lines: List<Any>,
        officialAtBuild: Boolean,
        officialNow: Boolean,
        selectedLanguage: String?,
    ) {
        val songId = modelSongId
        val translationLines = lines.count { onlineTranslation(it) != null }
        val pronunciationLines = lines.count { onlinePronunciation(it, rawLineText(it)) != null }
        val officialLanguage = NativeLyricModelPolicy.officialPronunciationLanguage(applePronunciationLanguages)
        diagnostic.log(
            songId,
            "online-translation native-write id=$songId " +
                "translationLines=$translationLines " +
                "pronunciationLines=$pronunciationLines " +
                "languages=${applePronunciationLanguages.joinToString(",")} " +
                "officialPronunciation=$officialNow " +
                "officialAtBuild=$officialAtBuild " +
                "officialLanguage=${officialLanguage ?: NONE} " +
                "selectedLanguage=${selectedLanguage ?: NONE} " +
                "mandarinHidden=$mandarinHidden",
        )
    }

    private fun nativeLines(songNative: Any): List<Any> {
        val sections = call(
            songNative,
            AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD,
        ) ?: return emptyList()
        val sectionCount = vectorSize(sections)
        return buildList {
            repeat(sectionCount.coerceIn(0, MAX_SECTIONS)) { sectionIndex ->
                val section = vectorItem(sections, sectionIndex) ?: return@repeat
                val lineVector = call(
                    section,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD,
                ) ?: return@repeat
                val lineCount = vectorSize(lineVector)
                repeat(lineCount.coerceIn(0, MAX_LINES_PER_SECTION)) { lineIndex ->
                    vectorItem(lineVector, lineIndex)?.let(::add)
                }
            }
        }
    }

    private fun vectorItem(vector: Any, index: Int): Any? {
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD] ?: return null
        val pointer = runCatching { AppleReflection.call(vector, name, index.toLong()) }
            .recoverCatching { AppleReflection.call(vector, name, index) }
            .getOrNull() ?: return null
        return pointerGet(pointer)
    }

    private fun vectorSize(vector: Any?): Int =
        (call(vector, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD) as? Number)
            ?.toInt()
            ?: 0

    private fun vectorStrings(vector: Any?): List<String> {
        if (vector == null) return emptyList()
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD] ?: return emptyList()
        return buildList {
            repeat(vectorSize(vector).coerceIn(0, MAX_LANGUAGES)) { index ->
                val value = runCatching { AppleReflection.call(vector, name, index.toLong()) }
                    .recoverCatching { AppleReflection.call(vector, name, index) }
                    .getOrNull()
                if (value is String && value.isNotBlank()) add(value)
            }
        }
    }

    /** Dereferences a JavaCPP pointer through the profile's `get` member. */
    private fun pointerGet(pointer: Any?): Any? {
        if (pointer == null) return null
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD] ?: return pointer
        return runCatching { AppleReflection.call(pointer, name) }.getOrNull() ?: pointer
    }

    private fun call(receiver: Any?, member: AppleMusicRuntimeMember, vararg args: Any?): Any? {
        if (receiver == null) return null
        val name = nativeNames[member] ?: return null
        return runCatching { AppleReflection.call(receiver, name, *args) }.getOrNull()
    }

    private fun rawLineText(line: Any?): String? = withRawRead {
        call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD) as? String
    }

    private fun nativeSongId(songNative: Any?): Long {
        val value = call(songNative, AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD)
        return (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
    }

    private fun currentSongId(): String? = modelSongId.takeIf { it > 0L }?.toString()

    private fun number(value: Any?): Long? = (value as? Number)?.toLong()

    private fun <T> withRawRead(block: () -> T): T {
        val previous = rawRead.get()
        rawRead.set(true)
        return try {
            block()
        } finally {
            if (previous == null) rawRead.remove() else rawRead.set(previous)
        }
    }

    private companion object {
        const val MAX_SECTIONS = 8
        const val MAX_LINES_PER_SECTION = 64
        const val MAX_LANGUAGES = 32

        /** Placeholder for a diagnostic field with no language to report. */
        const val NONE = "none"

        /**
         * HLE emits one full native-model line per build, but the shared
         * per-track budget on this channel is deliberately small; keep the
         * native proof to a couple of lines per track.
         */
        const val MAX_DIAGNOSTIC_LINES = 4
    }
}
