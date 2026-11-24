package dev.amenhancer.module.hook

import android.app.Application
import android.app.Activity
import android.content.res.Resources
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion

/** Apple Music composition; the application supplies configuration and automatic-lyric services. */
object AppleMusicHostFactory {
    fun installDensity(application: Application, configuredDpi: Int): AppleMusicDpiOverrideStatus = AppleMusicDpiOverrideRuntime.install(application,configuredDpi)
    fun densityStatus(): AppleMusicDpiOverrideStatus = AppleMusicDpiOverrideRuntime.status

    private fun settingsNames(context: android.content.Context): org.json.JSONObject {
        val build=targetBuild(context)
        return checkNotNull(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(build.packageName,build.versionName,build.versionCode)).document.getJSONObject("settings")
    }
    private fun fragmentFamily(context: android.content.Context): Boolean {
        val build = targetBuild(context)
        return dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(
            build.packageName, build.versionName, build.versionCode,
        )?.family == "fragment-content"
    }
    fun preservesNativeEditorialVideo(context: android.content.Context): Boolean = fragmentFamily(context)
    fun settingsActivityMatcher(context: android.content.Context, playerClass: Class<*>?): SettingsActivityMatcher =
        if (fragmentFamily(context)) FragmentSettingsNativeFactory.activityMatcher(context)
        else settingsNames(context).let { LegacySettingsActivityMatcher(playerClass,it.getString("playerActivity"),it.getString("mainActivity"),context.packageName) }
    fun settingsViewBridge(context: android.content.Context, onOpen: (android.app.Activity)->Unit): SettingsViewBridge =
        if (fragmentFamily(context)) FragmentSettingsNativeFactory.viewBridge(context,onOpen)
        else LegacySettingsViewBridge(context,onOpen)
    fun installSettingsEntry(context: android.content.Context, loader: ClassLoader, observer: SettingsEntryObserver) =
        if (fragmentFamily(context)) { FragmentSettingsNativeFactory.install(context,loader,observer); Unit }
        else LegacySettingsEntryInstaller(context).install(loader,observer)

    fun bindChrome(activity: android.app.Activity): ChromeHostBinding = LegacyChromeHostBinding(activity)
    fun installChromeHooks(loader: ClassLoader, build: TargetBuild, observer: ChromeHookObserver): HostSubscription =
        LegacyChromeHookInstaller.install(loader, build, observer)
    fun newTypefaceResources(): LyricsTypefaceResourceBinding = LyricsTypefaceSession()
    /** Lyric font-size / line-spacing scales; independent of the typeface and online-lyrics features. */
    fun newTypographyResources(): LyricsTypographyResourceBinding = LyricsTypographySession()
    fun registerDualPaneResources() {
        DualPaneResourceHook.install()
        FragmentDualPaneResources.install()
    }
    /**
     * Auxiliary lyric resource hooks: credits row identity, plus the word-splice reading row.
     *
     * The reading's own centring is not registered here any more — the host's pronunciation pair
     * alignment does it (see [installLyricPronunciationPairAlignment]) — so what is left is the one
     * placement that is ours: several mora readings spliced into one word, and only where a plan
     * says so.
     */
    fun registerLyricAuxiliaryResources() {
        LyricCreditsRowResourceHook.install()
        LyricsPronunciationWordAlignment.install()
    }
    fun installLayoutCallbacks() = LayoutInflationRegistry.install()
    fun registerChromeResources(observer: (View) -> Unit) {
        listOf("bottom_navigation", "mini_player").forEach { name ->
            LayoutInflationRegistry.register(name, observer)
        }
    }

    private fun installLyricPronunciationPairAlignment(
        profile: AppleMusicHostProfile,
        resources: Resources,
        /**
         * 「歌词效果优化」, read at inflation time.
         *
         * Pair alignment centres the reading under its glyph, which is a lyric *effect*: with the
         * switch off Apple's own container geometry has to stay untouched, exactly as the native
         * line alignment and the karaoke effects do.  The registration itself is fixed so the
         * switch can change without reinstalling the hooks.
         */
        enabled: () -> Boolean,
    ): TargetCapabilityInstall {
        val layoutName = profile.document.optJSONObject("layoutVariants")
            ?.optString("lyricPronunciationPairLayout").orEmpty()
        if (!profile.productionEnabled || layoutName.isBlank()) {
            return TargetCapabilityInstall.Unsupported("No verified word pronunciation pair layout")
        }
        if (resources.getIdentifier(layoutName, "layout", profile.packageName) == 0) {
            return TargetCapabilityInstall.Degraded("Word pronunciation pair layout is missing: $layoutName")
        }
        LayoutInflationRegistry.register(layoutName) { root ->
            if (!runCatching(enabled).getOrDefault(false)) return@register
            if (root is LinearLayout && root.orientation == LinearLayout.VERTICAL &&
                root.layoutParams?.width == ViewGroup.LayoutParams.WRAP_CONTENT
            ) {
                // The wider child determines the group width. The host keeps its mixed-direction rows separate.
                root.setHorizontalGravity(Gravity.CENTER_HORIZONTAL)
                root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    compensateLyricPronunciationEdges(root)
                }
            }
        }
        return TargetCapabilityInstall.Active("Word pronunciation pair alignment registered")
    }

    fun appleMusic(
        config: TargetConfigClient,
        application: Application,
        classLoader: ClassLoader,
        lyricsTypefaceSession: LyricsTypefaceResourceBinding,
        currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
        autoLyricsRuntime: AutoLyricsRuntime? = null,
        timingObservations: TtmlTimingObservationRegistry = TtmlTimingObservationRegistry(),
        nativeLyricOverlay: NativeLyricOverlayStore = NativeLyricOverlayStore(),
        onOpenLyricsMenu: (Activity) -> Unit = {},
    ): TargetAdaptation {
        val build = targetBuild(application)
        val profile = checkNotNull(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        check(profile.family in setOf("legacy-activity", "fragment-content")) {
            "Host family ${profile.family} needs its own verified native capability factory"
        }
        val resolver = IndexedTargetSymbolResolver(
            build = build,
            source = ApkTargetClassSource(application, classLoader),
        )
        val settings = config.settings()
        // Exact-profile resolver for HLE's native lyric-model delivery. A plain
        // class-loader lookup is enough here: 1606 is a strict profile, so the
        // resolver never guesses by DexKit, and an unresolved target fails open.
        val nativeLyricResolver = AppleMusicHookResolver(
            version = AppleMusicVersion(build.versionName, build.versionCode),
            classLoader = classLoader,
        )
        val nativePresentationMethod = runCatching {
            resolver.resolve(AppleMusicSymbols.LyricsInstallMethod).valueOrNull()
        }.getOrNull()
        val nativeLyricsTarget = AppleMusicNativeLyricsTarget(
            resolver = nativeLyricResolver,
            overlay = nativeLyricOverlay,
            currentSong = currentSong,
            hideMandarinPinyin = settings.onlineLyricsHideMandarinPinyinEnabled,
            genreFor = { appleMusicId ->
                runCatching {
                    io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
                        .getMetadataById(appleMusicId.toString())?.genre
                }.getOrNull()
            },
            presentationMethod = nativePresentationMethod,
        )
        // Installed unconditionally: the preference is read per call, so toggling
        // "lyrics pronunciation" in the module's settings takes effect on the next
        // lyrics presentation instead of needing an Apple Music restart.
        LyricsPronunciationMatching.install(profile, classLoader) {
            config.settings().lyricsPronunciationEnabled
        }
        val customLyrics = AppleMusicCustomLyricsTarget(
            config = config,
            symbols = resolver,
            currentSong = currentSong,
            autoLyricsRuntime = autoLyricsRuntime,
            timingObservations = timingObservations,
            nativeLyricDelivery = nativeLyricsTarget,
            installMenu = { registration -> AppleMusicLyricsMenuTarget(profile, classLoader, onOpenLyricsMenu).install(registration) },
        )
        val karaokeAnimation = AppleMusicCjkKaraokeAnimationTarget(
            symbols = resolver,
            foregroundTextField = profile.document.optJSONObject("cjk")
                ?.optString("foregroundTextField", "U") ?: "U",
            maxSpanGlowCharacters = profile.document.optJSONObject("cjk")
                ?.optInt("maxSpanGlowCharacters", 1) ?: 1,
        )
        val lyricRepairs by lazy {
            val parser = customLyrics.installParserRepair()
            val alignment = installLineLyricAgentAlignment(profile, classLoader, application.resources) {
                config.settings().lyricEffectsOptimizationEnabled
            }
            val pronunciationAlignment = installLyricPronunciationPairAlignment(
                profile,
                application.resources,
            ) { config.settings().lyricEffectsOptimizationEnabled }
            val terminal = AppleMusicCjkTerminalGradientTarget(profile, classLoader).install()
            val splitGlow = karaokeAnimation.installSplitWordGlow(
                profile.document.optJSONObject("cjk")?.optJSONObject("splitWordGlow"),
            )
            val optionalRepairs = listOf(alignment, pronunciationAlignment, terminal, splitGlow)
            val repairs = listOf(parser) + optionalRepairs.filterNot { it is TargetCapabilityInstall.Unsupported }
            val message = (listOf(parser) + optionalRepairs).joinToString("; ") { it.message }
            when {
                repairs.all { it is TargetCapabilityInstall.Active } -> TargetCapabilityInstall.Active(message)
                repairs.all { it is TargetCapabilityInstall.Unsupported } -> TargetCapabilityInstall.Unsupported(message)
                else -> TargetCapabilityInstall.Degraded(message)
            }
        }
        return TargetAdaptation(
            identity = build.displayName,
            build = build,
            currentSong = currentSong,
            playerRecovery = FragmentPlayerRecoveryTarget(resolver, build),
            startupPagePrewarm = FragmentStartupPagePrewarmTarget(
                application, classLoader, { config.settings().startupPagePrewarmEnabled },
                onFailure = { error -> config.reportHealth(dev.amenhancer.module.model.FeatureHealth(
                    dev.amenhancer.module.ModuleConstants.FEATURE_STARTUP_PAGE_PREWARM,
                    dev.amenhancer.module.model.FeatureState.DEGRADED,
                    "Startup page prewarming stopped: ${error.cause?.message ?: error.message ?: error.javaClass.simpleName}",
                    build.displayName,
                )) },
            ),
            dualPane = if (profile.family == "fragment-content") FragmentDualPaneTarget(resolver, build)
                else AppleMusicDualPaneTarget(resolver, build),
            editorialVideo = editorialVideoTargetForFamily(profile.family) {
                AppleMusicEditorialVideoTarget(application, resolver)
            },
            cellularDataEntry = if (profile.family == "fragment-content") FragmentCellularDataEntryTarget(
                resolver, build, classLoader, { config.settings().forceCellularDataEntryEnabled },
            ) else AppleMusicCellularDataEntryTarget(
                symbols = resolver,
                build = build,
                enabled = { config.settings().forceCellularDataEntryEnabled },
            ),
            bidirectionalLyricBlur = AppleMusicBidirectionalLyricBlurTarget(profile, classLoader),
            cjkKaraokeAnimation = karaokeAnimation,
            lyricWrapSweep = AppleMusicLyricWrapSweepTarget(profile, classLoader),
            lyricBackgroundHighlight = AppleMusicLyricBackgroundHighlightTarget(profile, classLoader,
                onFailure = { message -> config.reportHealth(dev.amenhancer.module.model.FeatureHealth(
                    dev.amenhancer.module.ModuleConstants.FEATURE_LYRIC_BACKGROUND_HIGHLIGHT,
                    dev.amenhancer.module.model.FeatureState.DEGRADED, message, build.displayName,
                )) },
            ),
            lyricsTypeface = AppleMusicLyricsTypefaceTarget(
                symbols = resolver,
                session = lyricsTypefaceSession as LyricsTypefaceSession,
            ),
            nativeLyrics = nativeLyricsTarget,
            customLyrics = customLyrics,
            lyricsParser = LyricsParserTarget { lyricRepairs },
            currentSongIdentity = AppleMusicCurrentSongIdentityTarget(
                resolver,
                currentSong,
            ),
            catalogLanguage = AppleMusicCatalogLanguageTarget(
                symbols = resolver,
                rawTargetLanguage = settings.regionSelection.catalogLanguage.orEmpty(),
            ),
            hleMetadata = HleMetadataTarget {
                val activeModule = ModernXposedRuntime.activeModule()
                    ?: return@HleMetadataTarget TargetCapabilityInstall.Degraded(
                        "Modern Xposed module was not attached",
                    )
                runCatching {
                    HleMetadataRuntime(
                        module = activeModule,
                        application = application,
                        classLoader = classLoader,
                        region = settings.regionSelection,
                        overrideAccountLanguage = settings.overrideAccountLanguage,
                        restoreCjkOriginalMetadata = settings.restoreCjkOriginalMetadata,
                        localizedMetadataCache = settings.localizedMetadataCache,
                        // Live read: the settings page can clear the index at any
                        // time, so the runtime must not snapshot the generation.
                        metadataCacheClearSignal = { config.metadataCacheClearGeneration() },
                        // Live read too: HLE re-reads its three metadata controls on every
                        // call, so the runtime polls this reader instead of keeping the
                        // values that happened to be on disk when the hook was installed.
                        metadataConfiguration = { config.metadataConfiguration() },
                    ).install()
                }.getOrElse { error ->
                    ModernXposedRuntime.log("HLE metadata runtime install failed", error)
                    TargetCapabilityInstall.Degraded(
                        "HLE metadata runtime failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            },
        )
    }
}
