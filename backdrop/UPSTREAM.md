# Source provenance

AndroidLiquidGlass / Backdrop 2.0.1, by Kyant.

- Upstream: https://github.com/Kyant0/AndroidLiquidGlass
- Pinned commit: `65ab177e90e5c1d8c62e70cf7755841982da65f6`
- License: Apache-2.0; see LICENSE in this directory.
- `src/commonMain` and the original `src/androidMain` files come from upstream. `DrawBackdropModifier.kt` has AM++ options for clip compositing and a minimum backdrop-layer size, plus scoped CPU trace markers. Rendering options retain the original defaults. `upstream-sha256.json` retains the original SHA256 values; `local-patches-sha256.json` pins the modified file. Both normalize CRLF to LF for cross-platform checkouts.
- `src/androidMain/.../backdrops/ViewBackdrop.kt` is an AM++ addition for a hardware-recorded Android View source.
- Build configuration targets Android only and uses the application's Java 17 baseline. Desktop, browser, Apple targets and publication tasks are not included.

Run `python scripts/verify-glass-reference.py` from the repository root to verify the original rendering sources and the declared local patch.

Every file AM++ modified, and every file AM++ added inside the upstream
packages, carries a header naming the upstream commit, the Apache-2.0 license
and the change itself, as Apache-2.0 section 4(b) requires. Files without an
entry in `local-patches-sha256.json` remain byte-identical to the upstream
hashes apart from line endings.

The seven catalog component/helper files in `glass/src/main/kotlin/com/kyant/backdrop/catalog` have the same source commit and license. AM++ changes: optional host accent and tap-only native reselection in LiquidBottomTabs/DampedDragAnimation, Android-only awaitFrame helper, native touch entry points on InteractiveHighlight, and an opt-in collapse rendering path in LiquidBottomTabs (`optimizeCollapseRendering`, default `false`; enabled only by `GlassNavigation` when it has a collapse progress). This path retains material modifiers, reuses a size-independent effect prefix, reserves bounded expanded backing sizes, selects Auto clipping instead of forcing another offscreen layer, and suspends the unused refraction source layout. Original shaders, springs, logical geometry and effect ordering are retained. NativeLiquidButton adapts LiquidButton's material and transform to content whose View/actions remain owned by Apple Music; its minimum backing is supplied only by a collapse-capable phone host while the full player is closed.

`backdrop/src/commonMain/kotlin/dev/amenhancer/backdrop/VibrantBlur.kt` is an AM++ addition under AM++'s own `dev.amenhancer.backdrop` namespace. It caches the size-independent `vibrancy() + blur(radiusPx)` prefix per pixel radius (converted from Dp with the current density), and exposes `paddedSize` to reserve its pre-lens padding. It adds no entry to `backdrop/upstream-sha256.json`. The catalog directory is not covered by that manifest; its AM++ changes are recorded here.

The optional `minLayerSize` in `DrawBackdropModifier` is an allocation floor in pixels, including effect padding, defaulting to zero. Recording callbacks keep their current logical padded size, effects keep the actual surface size, and output is restricted to the original padded bounds. Callers derive the floor from current expanded geometry and material padding; it is not a historical maximum and does not freeze the backdrop or scale content. Actual larger bounds still take precedence. The existing GraphicsLayer lifecycle owns release. The effect scope, lens/shader sources and the default Offscreen clip strategy are unchanged.

Collapse consumers reserve the larger backing only during transitions, returning to actual dimensions at stable endpoints. Mini reservation uses the current parent width as the rebound bound because its native margins remain non-negative; its gate observes animation running state as a derived boolean. `VibrantBlur.paddedSize` preserves zero as the disabled allocation floor.

`dev.amenhancer.backdrop.BackdropTracing` is an AM++ common/Android tracing bridge. Static `AM++/` CPU markers cover effect updates, effect-layer recording/creation and ViewBackdrop source recording only while system tracing is enabled. Sections close in `finally`; no rendering inputs or scheduling are changed. These markers measure CPU-side scopes, not GPU execution, and live outside the upstream namespace.
