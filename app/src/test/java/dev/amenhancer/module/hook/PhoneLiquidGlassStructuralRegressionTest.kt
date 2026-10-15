package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the liquid-glass resource and configuration contract across host forms. */
class PhoneLiquidGlassStructuralRegressionTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("core/src/main/kotlin/$relativePath"),
        File("../core/src/main/kotlin/$relativePath"),
        File("../core/src/main/kotlin/$relativePath"),
        File("host-api/src/main/java/$relativePath"),
        File("../host-api/src/main/java/$relativePath"),
        File("../host-api/src/main/java/$relativePath"),
        File("hook-runtime/src/main/java/$relativePath"),
        File("../hook-runtime/src/main/java/$relativePath"),
        File("../hook-runtime/src/main/java/$relativePath"),
        File("host-applemusic/src/main/java/$relativePath"),
        File("../host-applemusic/src/main/java/$relativePath"),
        File("../host-applemusic/src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
        File("../app/src/main/java/$relativePath"),
        File("../host-applemusic/src/main/java/$relativePath"),
        File("../host-api/src/main/java/$relativePath"),
        File("../core/src/main/kotlin/$relativePath"),
        File("../hook-runtime/src/main/java/$relativePath"),

    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `persists the setting with its embedded phone entry`() {
        val models = source("dev/amenhancer/module/model/ModuleModels.kt")
        val session = source("dev/amenhancer/module/config/EmbeddedConfigurationSession.kt")
        val schema = source("dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val client = source("dev/amenhancer/module/config/TargetConfigClient.kt")
        val storage = source("dev/amenhancer/module/config/HostPrivateEmbeddedStorage.kt")
        val settings = source("dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(models.contains("val phoneLiquidGlassEnabled: Boolean = false"))
        assertTrue(session.contains("ModuleSettingsSchema.encodeOrdinarySettings(settings)"))
        assertTrue(session.contains("ModuleSettingsSchema.encodeFontManifest(manifest)"))
        listOf(
            "lyrics_font_enabled",
            "lyrics_font_file_id",
            "lyrics_font_display_name",
            "lyrics_font_size_bytes",
            "lyrics_font_sha256",
        ).forEach { key -> assertTrue(schema.contains("\"$key\"")) }
        assertTrue(schema.contains("\"phone_liquid_glass_enabled\""))
        assertTrue(schema.contains("\"phone_liquid_glass_bottom_gap_dp\""))
        assertTrue(schema.contains("\"phone_liquid_glass_panel_blur_dp\""))
        assertTrue(storage.contains("ampp-embedded-settings"))
        assertTrue(client.contains("valuesProvider"))
        assertFalse(settings.contains("手机 Liquid Glass"))
        assertTrue(settings.contains("phoneLiquidGlassEnabled = it"))
        // The height/blur extras are add-ons of the liquid-glass toggle: their rows must
        // stay gated behind it and their values must live in the settings model.
        assertTrue(models.contains("val phoneLiquidGlassBottomGapDp: Int"))
        assertTrue(models.contains("val phoneLiquidGlassPanelBlurDp: Int"))
        assertTrue(settings.contains("if (settings.phoneLiquidGlassEnabled)"))
        assertTrue(settings.contains("ModuleText.BOTTOM_BAR_HEIGHT"))
        assertTrue(settings.contains("ModuleText.BOTTOM_BAR_BLUR"))
        // The scroll collapse is the third add-on of the same toggle, so it lives in the same
        // gated block and its value lives in the settings model.
        assertTrue(models.contains("val phoneLiquidGlassCollapseEnabled: Boolean = true"))
        assertTrue(settings.contains("ModuleText.BOTTOM_BAR_COLLAPSE"))
        assertTrue(settings.contains("phoneLiquidGlassCollapseEnabled = it"))
        // The collapse-animation amplitudes are tuning knobs for an animation that already has a
        // sensible default, so they live in **Advanced settings** rather than beside the feature
        // toggle — the main glass card stays about what the bar does, the advanced card about how it
        // moves. They are still gated on the feature so a disabled collapse shows no dead sliders.
        assertTrue(models.contains("val phoneLiquidGlassCollapseReboundPercent: Int"))
        assertTrue(models.contains("val phoneLiquidGlassExpandReboundPercent: Int"))
        assertTrue(models.contains("val phoneLiquidGlassMiniReboundPercent: Int"))
        assertTrue(models.contains("val phoneLiquidGlassMiniSinkBounceDp: Int"))
        assertTrue(settings.contains("if (settings.phoneLiquidGlassEnabled && settings.phoneLiquidGlassCollapseEnabled)"))
        assertTrue(settings.contains("ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND"))
        assertTrue(settings.contains("ModuleText.BOTTOM_BAR_EXPAND_REBOUND"))
        assertTrue(settings.contains("ModuleText.BOTTOM_BAR_MINI_REBOUND"))
        assertTrue(settings.contains("ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE"))
        assertTrue(settings.contains("phoneLiquidGlassCollapseReboundPercent = it"))
        assertTrue(settings.contains("phoneLiquidGlassExpandReboundPercent = it"))
        assertTrue(settings.contains("phoneLiquidGlassMiniReboundPercent = it"))
        assertTrue(settings.contains("phoneLiquidGlassMiniSinkBounceDp = it"))
        // The amplitudes must sit INSIDE the advanced card, not the main glass card.
        val advancedAt = settings.indexOf("localizedText(ModuleText.ADVANCED_SETTINGS)")
        val reboundAt = settings.indexOf("ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND")
        assertTrue("the collapse rebound row must be inside the Advanced card", advancedAt in 1 until reboundAt)
        // ...and in this order: the three collapse-direction knobs first, the single expand-direction
        // knob last. Grouping them keeps the one expand slider from splitting the collapse set.
        val order = listOf(
            "ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND",
            "ModuleText.BOTTOM_BAR_MINI_REBOUND",
            "ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE",
            "ModuleText.BOTTOM_BAR_EXPAND_REBOUND",
        ).map { settings.indexOf(it) }
        assertTrue("every amplitude row must be present", order.all { it > 0 })
        assertEquals("collapse knobs first, expand knob last", order.sorted(), order)
        // The percentages must not be labelled in dp like the distances.
        assertTrue(settings.contains("unit = \"%\""))
        assertTrue(schema.contains("\"phone_liquid_glass_collapse_rebound_percent\""))
        assertTrue(schema.contains("\"phone_liquid_glass_expand_rebound_percent\""))
        assertTrue(schema.contains("\"phone_liquid_glass_mini_rebound_percent\""))
        assertTrue(schema.contains("\"phone_liquid_glass_mini_sink_bounce_dp\""))
        // 35% is the recommended collapse rebound: the full spring overshoot (14.6dp on a phone row)
        // reads as exaggerated for a bar this size, and 35% lands at about 5dp.
        assertTrue(models.contains("EnhancementDefaults.GLASS_COLLAPSE_REBOUND_PERCENT"))
        val defaults = projectFile("core/src/main/kotlin/dev/amenhancer/module/model/EnhancementDefaults.kt")
        assertTrue(defaults.contains("const val GLASS_COLLAPSE_REBOUND_PERCENT = 35"))
        assertTrue(defaults.contains("const val GLASS_MINI_SINK_BOUNCE_DP = 4"))
        assertTrue(defaults.contains("const val GLASS_MINI_REBOUND_PERCENT = 100"))
        // The invented vertical lift is gone: nothing needs it, and it was the reason the expand
        // rebound read as a vertical jolt instead of the control growing back.
        assertTrue(!models.contains("phoneLiquidGlassExpandLiftDp"))
        assertTrue(!schema.contains("phone_liquid_glass_expand_lift_dp"))
        // Each gated row carries a small one-tap restore button for its own default.
        assertTrue(settings.contains("ModuleText.RESET_DEFAULT"))
        assertTrue(settings.contains("defaultValue = GlassPolicy.BOTTOM_DP"))
        assertTrue(settings.contains("defaultValue = GlassPolicy.PANEL_BLUR_DP.toInt()"))
        // The restore button uses the AM++-authored SVG glyph, not the legacy drawable.
        assertTrue(settings.contains("EmbeddedSvgIcon.RestoreDefault"))
    }

    @Test
    fun `keeps the phone form semantics and routes the tablet dual-pane form`() {
        val policy = projectFile("glass/src/main/kotlin/dev/amenhancer/glass/GlassPolicy.kt")
        val runtime = source("dev/amenhancer/module/hook/PhoneGlassRuntime.kt")

        // The pre-form supports() overload survives with its phone-only meaning.
        assertTrue(policy.contains("fun supports(sdk: Int, verifiedHost: Boolean, tablet: Boolean)"))
        assertTrue(policy.contains("!tablet && supports(sdk, verifiedHost, GlassHostForm.PhoneStacked)"))
        // The phone path keeps excluding official tablets and keeps its own session.
        assertTrue(runtime.contains("isOfficialTablet"))
        assertTrue(runtime.contains("PhoneGlassSession("))
        // Eligible tablets are routed to the dedicated tablet session.
        assertTrue(runtime.contains("TabletDualPaneGlassSession"))
        // The form and geometry seams live in the Android-free policy.
        assertTrue(policy.contains("GlassHostForm"))
        assertTrue(policy.contains("GlassGeometry"))
    }

    @Test
    fun `uses api 102 remote preferences and keeps liquid glass fail closed`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val client = source("dev/amenhancer/module/config/TargetConfigClient.kt")

        assertFalse(manifest.contains("ConfigProvider"))
        assertTrue(client.contains("phoneLiquidGlassEnabled = false"))
        assertFalse(client.contains("contentResolver.call"))
    }

    @Test
    fun `reference sources retain their pinned provenance`() {
        val provenance = projectFile("backdrop/UPSTREAM.md")
        assertTrue(provenance.contains("65ab177e90e5c1d8c62e70cf7755841982da65f6"))
        assertTrue(projectFile("backdrop/LICENSE").contains("Apache License"))
        assertTrue(projectFile("settings.gradle.kts").contains(":backdrop"))
    }

    @Test
    fun `keeps pager pages under the glass`() {
        val session = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        // ViewPager2 lays its pages out inside an internal RecyclerView. Padding that
        // RecyclerView shrinks every page, so the page stops above the glass and the bar
        // samples empty background (Search results looked opaque). The pager host must stay
        // out of the padding targets and previously padded targets must be released.
        assertTrue(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.all.filter { it.family == "legacy-activity" }.all {
            it.document.getJSONObject("chrome").getJSONObject("views").getString("pagerType") ==
                "androidx.viewpager2.widget.ViewPager2"
        })
        assertTrue(session.contains("hostBinding.isPagerPageHost(view)"))
        assertTrue(session.contains("!isViewPagerPageHost(view)"))
        assertTrue(session.contains("state.scrollPaddingActive && terminal.none"))
        assertTrue(session.contains("restoreScroll(view)"))
    }

    @Test
    fun `drives the scroll collapse from the gesture on compose pages`() {
        val session = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        val tracker = source("dev/amenhancer/module/hook/GlassTouchCollapseTracker.kt")
        val factory = source("dev/amenhancer/module/hook/FragmentChromeFactory.kt")
        val runtime = source("dev/amenhancer/module/hook/FragmentGlassRuntime.kt")
        val binding = source("dev/amenhancer/module/hook/FragmentSurfaceBinding.kt")
        val phone = source("dev/amenhancer/module/hook/FragmentPhoneGlassSession.kt")

        // Library / New / parts of Search are Compose scenes: their scrolling never moves a
        // native container, so the scroll listener alone observes nothing and the gesture is
        // the signal that actually reaches those pages. Both sources must stay wired.
        assertTrue(session.contains("addOnScrollChangedListener(collapseScrollListener)"))
        assertTrue(session.contains("open fun observeContentTouch(event: MotionEvent): Boolean?"))
        assertTrue(session.contains("collapseTouch.move(event.rawX, event.rawY, event.eventTime)"))
        // The content root is the page's ancestor: one dispatch per gesture, before any child
        // consumes it. It is forwarded under its own callback so it cannot collide with the
        // mini player's press handling.
        assertTrue(binding.contains("fun isContentRoot(view: View): Boolean = root === view"))
        assertTrue(factory.contains("binding.isContentRoot(view)"))
        assertTrue(factory.contains("observer.onContentTouch(binding.viewSessionIdentity"))
        assertTrue(runtime.contains("sessions[identity]?.observeContentTouch(event)"))
        assertTrue(phone.contains("super<PhoneGlassSession>.observeContentTouch(event)"))
        // A sideways swipe belongs to a carousel or the pager and must not withdraw the bar.
        assertTrue(tracker.contains("if (abs(totalX) > abs(totalY))"))
        // Observation only: the collapse must never consume or rewrite the host's event.
        assertTrue(session.contains("override fun shouldPassThroughTouch(view: View, event: MotionEvent): Boolean = false"))
    }

    @Test
    fun `routes the page's own scrolling into the bar through one direction convention`() {
        val session = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        val accumulator = source("dev/amenhancer/module/hook/GlassScrollCollapse.kt")
        val tracker = source("dev/amenhancer/module/hook/GlassTouchCollapseTracker.kt")

        // A scrolling page increases its offset while the content moves up, and the accumulator
        // reads a negative delta as "content moved up, collapse". The scroll path must therefore
        // feed the negation of its delta; the unchanged delta would invert the bar on every
        // native page. The touch path reaches the same convention through finger travel.
        assertTrue(accumulator.contains("fun accumulate(deltaY: Int): Boolean?"))
        assertTrue(session.contains("collapseAccumulator.accumulate(previous - offset)"))
        // Both sources share the accumulator, so they cannot disagree on when the bar may move.
        assertTrue(tracker.contains("GlassScrollCollapseAccumulator(thresholdPx)"))
        // The exit distance is the bar's own occupied height, so it has to follow the collapse.
        assertTrue(source("dev/amenhancer/module/hook/FragmentPhoneGlassSession.kt")
            .contains("collapse = collapseProgress()"))
    }

    @Test
    fun `leaves the collapsed mini player fully native and interactive`() {
        val session = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        val phone = source("dev/amenhancer/module/hook/FragmentPhoneGlassSession.kt")
        val factory = source("dev/amenhancer/module/hook/FragmentChromeFactory.kt")
        val runtime = source("dev/amenhancer/module/hook/FragmentGlassRuntime.kt")
        val gesture = source("dev/amenhancer/module/hook/GlassCollapsedMiniGesture.kt")

        // The 7.0 Fragment path is the one that runs on the phone host.
        assertTrue(factory.contains("observer.onMiniTouch(binding.viewSessionIdentity"))
        assertTrue(runtime.contains("override fun onMiniTouch(identity: Any, event: MotionEvent): Boolean?"))
        // The phone form claims NOTHING on either seam. The collapsed mini is a complete mini
        // player: its play/pause and skip buttons and its tap-to-open-player are the host's, and
        // consuming the gesture here is exactly what made those working controls dead.
        assertTrue(session.contains("override fun dispatchCollapsedMiniTouch(view: View, event: MotionEvent): Boolean? = null"))
        assertTrue(phone.contains("collapsedMiniTouch(event)"))
        assertTrue(phone.contains("return null"))
        // The module must never synthesise the player-open action: the host already wires a click
        // (and long-click) listener to the mini touch panel, so any route of our own would be a
        // second way in that could drift from the host's.
        assertTrue(!session.contains("openPlayerFromCollapsedMini"))
        assertTrue(!session.contains("performClick()"))
        assertTrue(!session.contains("target.dispatchTouchEvent(forwarded)"))
        // `collapsedMiniTouch` therefore reports ownership rather than a consumed result, and the
        // content-root seam uses it to EXCLUDE the gesture from the collapse signal instead of
        // claiming it. Returning null there is what keeps native dispatch untouched.
        assertTrue(session.contains("protected fun collapsedMiniTouch(event: MotionEvent): Boolean"))
        assertTrue(session.contains("val miniGesture = collapsedMiniTouch(event)"))
        assertTrue(session.contains("if (navigationGesture || miniGesture) { resetCollapseInput(); return null }"))
        assertTrue(session.contains("open fun observeContentTouch(event: MotionEvent): Boolean?"))
        assertTrue(factory.contains("observer.onContentTouch(binding.viewSessionIdentity, event)"))
        assertTrue(runtime.contains("override fun onContentTouch(identity: Any, event: MotionEvent): Boolean?"))
        assertTrue(phone.contains("override fun observeContentTouch(event: MotionEvent): Boolean?"))
        // The lift still belongs to the mini's gesture: letting it through would let the drag's
        // velocity decide the bar's direction. Ownership is therefore read before it is cleared.
        assertTrue(session.contains("val owned = collapsedMiniGesture.owning"))
        assertTrue(session.contains("MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {"))
        // Only the gesture's ORIGIN is recorded now — no tap-versus-drag classification, because
        // which control the finger landed on is the host's business.
        assertTrue(gesture.contains("val owning: Boolean get() = active"))
        assertTrue(!gesture.contains("dragged"))
        // The hit test must cover the mini player and nothing else. Collapsed, the native root
        // keeps its full-row width, so including it there made every tap on the row — the capsule
        // and the search control included — count as "on the mini".
        assertTrue(session.contains("private fun hitsMiniPlayer(event: MotionEvent): Boolean"))
        // Collapsed, only the two narrowed surfaces are tested; expanded, the mini is alone in its
        // band so its own bounds suffice.
        assertTrue(session.contains("listOfNotNull(miniGlass, miniContent)"))
        assertTrue(session.contains("listOfNotNull(miniContent, miniRoot)"))
        assertTrue(!session.contains("listOfNotNull(miniGlass, miniRoot, miniContent)"))
        assertTrue(session.contains("COLLAPSED_TOUCH_MIN_PROGRESS"))
    }

    @Test
    fun `centres the mini player on the collapsed row and animates the search control like the capsule`() {
        val session = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        val phone = source("dev/amenhancer/module/hook/FragmentPhoneGlassSession.kt")
        val binding = projectFile("host-applemusic/src/main/java/dev/amenhancer/module/hook/FragmentPhoneChromeBinding.kt")
        val port = source("dev/amenhancer/module/hook/FragmentPhoneChromePort.kt")
        val tabs = projectFile("glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt")
        val navigation = projectFile("glass/src/main/kotlin/dev/amenhancer/glass/GlassNavigation.kt")
        val policy = projectFile("glass/src/main/kotlin/dev/amenhancer/glass/GlassBarCollapse.kt")

        // The mini is shorter than the collapsed row, so it shares a TOP edge with the taller
        // capsule and its centre sits 2.5dp high. It must sink by half the difference to centre.
        assertTrue(policy.contains("fun miniDrop(expandedMiniHeightPx: Float, collapsedRowHeightPx: Float, p: Float): Float"))
        assertTrue(session.contains("GlassBarCollapsePolicy.miniDrop("))
        // `miniRoot.translationY` already has an owner, so the drop is threaded through that one
        // owner rather than written by a second writer on the same property.
        assertTrue(port.contains("fun syncMiniPresentation(miniRoot: View?, miniDropPx: Float = 0f)"))
        assertTrue(phone.contains("native.syncMiniPresentation(miniRoot, miniDropPx())"))
        assertTrue(binding.contains("if (miniDropPx.isFinite()) miniDropPx else 0f"))
        // The root's translation ownership must follow the ROOT, not the content identity. Tying
        // it to the `content !== mini` branch meant a first call with a null root left the owner
        // null forever: the resting offset was never written and the centring reached only the
        // glass drawn over the controls, never the controls themselves.
        assertTrue(binding.contains("private var translationRoot: View? = null"))
        assertTrue(binding.contains("if (translationRoot !== miniRoot) {"))
        // The baseline is read plainly, because the module has never written to a freshly owned
        // root; subtracting a live drop would bake a negative offset in and cancel the drop.
        assertTrue(binding.contains("translationBaseline = root.translationY"))
        assertTrue(!binding.contains("root.translationY - miniDropPx"))
        // The glass must NOT add the drop a second time: `miniOffsetInSheet` is sampled from the
        // native mini's live window position, which already includes the translation above. Adding
        // it again sank the glass by twice the centring distance — exactly the difference between
        // centring on the row and sitting on its bottom edge, which is what the device showed.
        assertTrue(session.contains("val top = (miniOffsetInSheet * (1f - materialProgress)).roundToInt()"))
        assertTrue(!session.contains("+ miniDropPx()"))
        // The right button must use the LEFT button's animation, not a scale-in of its own: the
        // same capsule functions, on the same raw (unclamped) spring, with the same drop.
        assertTrue(!policy.contains("fun searchScale(p: Float): Float"))
        assertTrue(!navigation.contains("searchScale"))
        assertTrue(navigation.contains("GlassBarCollapsePolicy.searchWidth("))
        assertTrue(navigation.contains("GlassBarCollapsePolicy.capsuleHeight(rowHeightPx, controlPx, raw)"))
        assertTrue(navigation.contains("GlassBarCollapsePolicy.collapseDrop("))
        // ...and it must inherit the capsule's REBOUND DISTANCE, not just its curve. The control
        // travels one cell where the capsule travels the whole row, so interpolating its own width
        // gave it ~1dp of overshoot against the capsule's ~14dp — the "no big rebound" report.
        assertTrue(policy.contains("fun searchWidth("))
        assertTrue(policy.contains("fun reboundOffset("))
        // The sign is LEFT ALONE. Flipping the expand overshoot inward with `abs()` made the
        // return direction move *backwards*, narrower than the width it had already reached, which
        // is what read as a stumble.
        assertTrue(policy.contains("val offset = travelPx * excess"))
        assertTrue(!policy.contains("abs(overshoot)"))
        // ...but the expand side IS clamped to the room beside the control. The growth is a
        // fraction of the ROW's travel while the gap is a fixed dp, so without this a wide screen
        // pushes the button into the mini player. Clamping the amplitude (rather than retuning the
        // spring) keeps this density- and device-independent.
        assertTrue(policy.contains("maxExpandReboundPx: Float = Float.POSITIVE_INFINITY"))
        assertTrue(policy.contains("return offset.coerceAtMost(maxExpandReboundPx.coerceAtLeast(0f))"))
        // The clamp must not touch the collapse side: it narrows, so it cannot run out of room.
        assertTrue(policy.contains("if (offset <= 0f || !maxExpandReboundPx.isFinite()) return offset"))
        // Both callers pass the gap, so the two side buttons are clamped identically.
        assertTrue(navigation.contains("maxExpandReboundPx = with(density) { GlassBarCollapsePolicy.GAP_DP.dp.toPx() }"))
        assertTrue(tabs.contains("maxExpandReboundPx = expandRoomPx"))
        assertTrue(tabs.contains("val expandRoomPx = with(density) { dev.amenhancer.glass.GlassBarCollapsePolicy.GAP_DP.dp.toPx() }"))
        // ONE amplitude pair, shared by the capsule and the search control.
        assertTrue(navigation.contains("collapseReboundScale: Float = 1f"))
        assertTrue(navigation.contains("expandReboundScale: Float = 1f"))
        assertTrue(session.contains("collapseReboundScale = collapseReboundPercent / 100f"))
        assertTrue(session.contains("expandReboundScale = expandReboundPercent / 100f"))
        // The amplitudes must be COMPOSE STATE, not plain fields. A plain field is
        // invisible to the composition — the slider updates it but the already-composed row never
        // re-reads it, so the bar keeps animating with a stale value until some unrelated
        // recomposition happens to pick the new one up. That is exactly "0% still rebounds,
        // sometimes it works".
        assertTrue(session.contains("protected var collapseReboundPercent by mutableIntStateOf("))
        assertTrue(session.contains("protected var expandReboundPercent by mutableIntStateOf("))
        assertTrue(session.contains("protected var miniSinkBounceDp by mutableIntStateOf("))
        assertTrue(session.contains("collapseReboundPercent = ModuleSettings.normalizePhoneLiquidGlassCollapseReboundPercent("))
        assertTrue(session.contains("expandReboundPercent = ModuleSettings.normalizePhoneLiquidGlassExpandReboundPercent("))
        // The signed clamp, with the spring's own shape preserved on both sides.
        assertTrue(policy.contains("const val SPRING_OVERSHOOT_LIMIT = 0.09f"))
        assertTrue(policy.contains("fun sprungFraction(value: Float, limit: Float = SPRING_OVERSHOOT_LIMIT): Float"))
        assertTrue(policy.contains("value.coerceIn(-limit, 1f + limit)"))
        assertTrue(policy.contains("val ExpandOvershoot: Float = run {"))
        // The buttons and the ROW are both the mini player's height, and every control is
        // bottom-anchored onto the expanded bar's bottom edge, so the collapsed row's bottom edge
        // sits at the same height as the expanded one.
        assertTrue(policy.contains("const val CONTROL_DP = GlassPolicy.MINI_HEIGHT_DP"))
        assertTrue(policy.contains("const val ROW_DP = CONTROL_DP"))
        assertTrue(policy.contains("fun collapseDrop(expandedBoxHeightPx: Float, collapsedControlHeightPx: Float, p: Float): Float"))
        assertTrue(navigation.contains("GlassBarCollapsePolicy.collapseDrop(rowHeightPx, controlPx, raw)"))
        assertTrue(!navigation.contains("(rowHeightPx - heightPx) / 2f"))
        // The mini drops by the WHOLE leftover space, not half: half left its bottom edge floating
        // above the buttons' line, which is the "collapsed row drifted upwards" report.
        assertTrue(policy.contains("val bottomAnchor = (collapsedRowHeightPx - expandedMiniHeightPx).coerceAtLeast(0f)"))
        assertTrue(!policy.contains("centring = ((collapsedRowHeightPx - expandedMiniHeightPx) / 2f)"))
        // The sink dip rides the collapse spring's own overshoot, so it needs no animation of its
        // own and cannot drift out of phase with the capsule's rebound. It is added INSIDE
        // `miniDropPx` so the mini's vertical position still has exactly one writer.
        assertTrue(policy.contains("fun miniSinkOvershoot("))
        assertTrue(policy.contains("val excess = (p - progress(p)).coerceIn(0f, springOvershoot)"))
        assertTrue(policy.contains("val CollapseOvershoot: Float = run {"))
        assertTrue(session.contains("amplitudePx = miniSinkBounceDp * density"))
        assertTrue(session.contains("springOvershoot = dev.amenhancer.glass.GlassBarCollapseSprings.CollapseOvershoot"))
        // The collapsed mini player's taps being dead. The tab-drag gesture is a `pointerInput`
        // on the row box, which is `fillMaxWidth()`; while it is PRESENT the row is a full-width
        // Compose hit target, and Compose tells the View system the event was handled as soon as a
        // pointer lands on any pointer-input node — consumed or not. The collapsed mini sinks into
        // exactly that band, so the overlay swallowed every tap meant for it, while the module's own
        // Compose controls in the same row kept working because they are in the same view.
        //
        // So the node must be ABSENT, not merely idle, whenever the row does not own a drag. The
        // previous `enabled` flag only made the handler return early, which is why removing the two
        // consumption seams could not fix it: the event never reached the mini to begin with.
        assertTrue(tabs.contains("modifier.then(if (dragEnabled) freeDragBridge.modifier else Modifier)"))
        assertTrue(!tabs.contains("modifier.then(freeDragBridge.modifier)"))
        // Created once and keyed on the lambda through `rememberUpdatedState`, so the gate reduces
        // the per-frame spring to one boolean instead of recomposing the row every frame.
        assertTrue(tabs.contains("val collapseProgressState = rememberUpdatedState(collapseProgress)"))
        assertTrue(tabs.contains("val dragEnabled by remember {"))
        assertTrue(tabs.contains("GlassBarCollapsePolicy.dragEnabled(collapseProgressState.value())"))
        // The gate is the same one the policy already defines, so "the row owns a drag" and "the row
        // accepts taps" cannot drift apart.
        assertTrue(policy.contains("fun dragEnabled(p: Float): Boolean = progress(p) < 0.5f"))
        // The module must not consume the mini's touches anywhere: the collapsed mini is a complete
        // mini player whose play/pause, skip and tap-to-open are the host's.
        assertTrue(session.contains("override fun dispatchCollapsedMiniTouch(view: View, event: MotionEvent): Boolean? = null"))
        assertTrue(!session.contains("openPlayerFromCollapsedMini"))
        assertTrue(!session.contains("performClick()"))
        // ...but it must still be able to tell the two failure modes apart on device.
        assertTrue(session.contains("private fun traceRowTap(event: MotionEvent)"))
        assertTrue(session.contains("\"row tap -> \${if (hitsMiniPlayer(event)) \"mini\" else \"outside\"}\""))
        // Dragging the mini player upward opens the full player, and that long
        // upward travel arrives on the same content-root seam the collapse tracker watches — where
        // it reads as "the page scrolled down" and collapses the bar. Coming back therefore left the
        // bar collapsed, while a *tap* (too short to clear the slop) did not. The exclusion must
        // therefore apply in EVERY state, not only once collapsed.
        assertTrue(session.contains("private fun hitsMiniPlayer(event: MotionEvent): Boolean"))
        assertTrue(session.contains("if (!hitsMiniPlayer(event)) {"))
        assertTrue(!session.contains("collapse.value < COLLAPSED_TOUCH_MIN_PROGRESS || !hitsMiniPlayer(event)"))
        // Expanded, the mini is the only thing in its band, so the test uses its own bounds; the
        // collapsed narrowing is still needed because the native root keeps its full-row width.
        assertTrue(session.contains("if (collapse.value < COLLAPSED_TOUCH_MIN_PROGRESS) {"))
        assertTrue(session.contains("listOfNotNull(miniContent, miniRoot)"))
        assertTrue(session.contains("listOfNotNull(miniGlass, miniContent)"))
        assertTrue(session.contains("return bottomAnchor + dip"))
        // The dip must not become a second writer on the mini's vertical position.
        assertTrue(!session.contains("miniRoot?.translationY ="))
        // The selection droplet is omitted rather than faded: `Modifier.alpha` sits inside
        // `drawBackdrop`, so the droplet's own surface and shadow escaped it and left a sliver.
        assertTrue(tabs.contains("if (expandedContentAlpha > 0.001f) {"))
    }

    @Test
    fun `tints only a copy of the compact glyph`() {
        val navigation = projectFile("glass/src/main/kotlin/dev/amenhancer/glass/GlassNavigation.kt")
        // `GlassTab.icon` is the very drawable the expanded tab row paints. Tinting it in place
        // recoloured that tab for the rest of the session — it stayed accent-red after expanding
        // and followed the tab through every later selection, because a Drawable's tint belongs
        // to the drawable rather than to this composition.
        assertTrue(navigation.contains("constantState?.newDrawable(resources)?.mutate()"))
        assertTrue(navigation.contains("copy?.setTint(tint.toArgb())"))
        // Without a constant state there is nothing safe to tint, so the glyph stays untinted
        // rather than recolouring the shared drawable.
        assertTrue(navigation.contains("copy ?: source"))
    }

    @Test
    fun `the search button carries the accent when it is the selection`() {
        val navigation = projectFile("glass/src/main/kotlin/dev/amenhancer/glass/GlassNavigation.kt")
        // The collapsed search button IS the selection when search is the active tab, so it has
        // to be tinted exactly like the capsule's compact face. Leaving it uncoloured made the
        // collapsed row look like nothing was selected while the expanded row had clearly shown red.
        assertTrue(navigation.contains("if (!selected) source"))
        assertTrue(navigation.contains("copy?.setTint(accent.toArgb())"))
        // Same copy-not-mutate rule as the compact glyph, and for the same reason.
        assertTrue(navigation.contains("remember(tab.icon, selected, accent)"))
    }

    @Test
    fun `the collapsed capsule press feedback is a capsule, not the stock ripple`() {
        val tabs = projectFile("glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt")
        // The compact face is the panel's full bounding SQUARE, so the stock rectangular ripple
        // drew a square highlight over a round button — and because the box is dropped by
        // `collapseDropPx` while the ripple is inset from its own bounds, the square also read as
        // offset from the circle. `clip` must sit before `clickable` so it bounds the indication.
        assertTrue(tabs.contains(".clip(Capsule())"))
        // `clip` must come after the drop and before `clickable`, so it bounds the indication.
        val clipAt = tabs.indexOf(".clip(Capsule())")
        val clickableAt = tabs.indexOf("if (interactive) Modifier.clickable(onClick = onExpandRequest!!)")
        assertTrue("the clip must precede the clickable it clips", clipAt in 1 until clickableAt)
        // The highlight primitive must be clipped to the same shape on the wide bar too, so the two
        // buttons cannot diverge.
        assertTrue(tabs.contains("shape = Capsule(),"))
        val highlight = projectFile("glass/src/main/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt")
        assertTrue(highlight.contains("val shape: Shape? = null"))
        assertTrue(highlight.contains("clipPath(Path().apply { addOutline(outline) }) { drawHighlight(progress) }"))
    }

    @Test
    fun `the highlight follows the panel into the collapsed circle`() {
        val tabs = projectFile("glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt")
        // The light was pinned to the selected tab's CELL centre. Collapsed the panel is
        // a 43dp circle and that cell centre lands near the circle's left edge, so the glow appeared
        // offset from the button. It now blends to the panel's own centre on the collapse progress.
        assertTrue(tabs.contains("highlightAnchor.collapse = collapseRaw"))
        assertTrue(tabs.contains("val centred = panelWidth / 2f"))
        assertTrue(tabs.contains("val blended = fromStart + (centred - fromStart) * t"))
        // Clamped, so a stale tabWidth during a menu swap cannot push the light off the glass.
        assertTrue(tabs.contains("blended.coerceIn(0f, panelWidth)"))
    }

    @Test
    fun `the expand rebound is rendered with required sizing`() {
        val tabs = projectFile("glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt")
        // The panel's expanded width IS the row's width, so the outward expand
        // overshoot asked for a size the box could not offer and the plain size modifiers coerced it
        // into the incoming constraints — ~8.5dp of travel computed correctly and then silently
        // dropped at measurement. That is why the backplate appeared to have no rebound while the
        // smaller selector visibly did.
        assertTrue(tabs.contains(".requiredWidth(panelWidthDp)"))
        assertTrue(tabs.contains(".requiredHeight(panelHeightDp)"))
        assertTrue(!tabs.contains(".width(panelWidthDp)\n                    .padding(4f.dp)"))
        // The tab row is pinned to the RESTING width instead, so the glyphs do not stretch with the
        // backplate's overshoot: only the backplate should visibly spring.
        assertTrue(tabs.contains("val restingWidthPx ="))
        assertTrue(tabs.contains("Modifier.width(restingWidthDp - 8f.dp).fillMaxHeight()"))
        // ...and the geometry the selector uses is the resting one.
        assertTrue(tabs.contains("GlassTabGeometry(restingWidthPx, panelInset, leadingPx, tabsCount)"))
        assertTrue(!tabs.contains("GlassTabGeometry(panelWidthPx, panelInset, leadingPx, tabsCount)"))
    }

    @Test
    fun `the mini player rebounds with the row`() {
        val session = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        // The mini was the one surface on the row that could not spring back, because its slot
        // and its drop both short-circuited on `p <= 0f` — which is exactly where the return
        // rebound lives. Both early returns are gone.
        //
        // Comments are stripped first: the replacements deliberately *quote* the removed guards to
        // explain why they must not come back, so a raw substring search would match the
        // explanation rather than the code.
        val code = session.lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue(!code.contains("if (p <= 0f) return expanded"))
        assertTrue(!code.contains("if (p <= 0f) return 0f"))
        // The slot now rides the sprung fraction, so the mini's own glass backplate rebounds on the
        // same spring as the two side buttons.
        assertTrue(code.contains("val fraction = dev.amenhancer.glass.GlassBarCollapsePolicy.sprungFraction("))
        assertTrue(code.contains("miniStartMargin(\n            expanded[0].toFloat(), capsule, gap, fraction,"))
        assertTrue(code.contains("miniEndMargin(\n            expanded[1].toFloat(), endReserve, fraction,"))
    }
}
