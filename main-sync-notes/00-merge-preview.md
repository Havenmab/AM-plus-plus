# main (upstream 1.7.1) → integ 合并预览

分支 merge/main-1.7.1 从 integ/all-features ee6a99f6 开出，试合 origin/main 8bdf6636。

## 冲突文件（$(git diff --name-only --diff-filter=U | wc -l) 个）

- `app/src/main/java/dev/amenhancer/module/hook/AppleMusicAssembly.kt`：1 处冲突
- `app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt`：1 处冲突
- `app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt`：2 处冲突
- `app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsPages.kt`：1 处冲突
- `core/src/main/kotlin/dev/amenhancer/module/config/ModuleSettingsSchema.kt`：1 处冲突
- `glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt`：1 处冲突
- `glass/src/main/kotlin/dev/amenhancer/glass/GlassNavigation.kt`：2 处冲突

## 自动合并但内容有变（前 40 行 diffstat）
```
 app/build.gradle.kts                               |    4 +-
 .../module/config/EmbeddedLyricsSourceStore.kt     |  189 +-
 .../amenhancer/module/hook/AppleMusicAssembly.kt   |    1 +
 .../module/hook/AutoLyricsReplacementSession.kt    |  117 +-
 .../module/hook/EmbeddedLyricsPlaybackRuntime.kt   |   78 +
 .../module/hook/FragmentTabletGlassSession.kt      |   26 +-
 .../amenhancer/module/hook/GlassMiniForeground.kt  |   87 +
 .../module/hook/LyricsPlaybackRuntimeRegistry.kt   |   10 +-
 .../amenhancer/module/hook/PhoneGlassSession.kt    |   30 +-
 .../module/ui/EmbeddedLlmSettingsPage.kt           |   61 +
 .../module/ui/EmbeddedLyricsCachePage.kt           |  707 +++
 .../module/ui/EmbeddedLyricsSettingsPages.kt       |   61 +-
 .../amenhancer/module/ui/EmbeddedSettingsDesign.kt |    1 +
 .../amenhancer/module/ui/EmbeddedSettingsPages.kt  |   17 +-
 .../PhoneLiquidGlassStructuralRegressionTest.kt    |   21 +-
 backdrop/UPSTREAM.md                               |    2 +
 backdrop/local-patches-sha256.json                 |    2 +-
 .../com/kyant/backdrop/DrawBackdropModifier.kt     |   35 +-
 .../module/config/ModuleSettingsSchemaTest.kt      |    2 +
 .../config/OrdinarySettingsWritePolicyTest.kt      |    2 +
 .../module/config/ModuleSettingsSchema.kt          |    7 +
 .../module/hook/LlmLyricsReplacementSession.kt     |    4 +-
 .../dev/amenhancer/module/i18n/ModuleText.kt       |   74 +
 .../dev/amenhancer/module/lyrics/LlmLyricsCache.kt |   14 +
 .../dev/amenhancer/module/model/ModuleModels.kt    |    2 +
 docs/feature-preservation.md                       |    3 +
 docs/host-adaptation-checklist.md                  |   15 +-
 docs/host-adaptation-guide.md                      |   22 +-
 .../catalog/components/LiquidBottomTabs.kt         |   23 +-
 .../kotlin/dev/amenhancer/glass/GlassNavigation.kt |  113 +-
 .../dev/amenhancer/glass/GlassTextContrast.kt      |  123 +
 .../dev/amenhancer/glass/NativeLiquidButton.kt     |   15 +-
 host-applemusic/build.gradle.kts                   |    2 +
 .../module/hook/AppleMusicHostFactory.kt           |   14 +
 .../main/resources/host-profiles/7.0.2-1624.json   | 6345 ++++++++++++++++++++
 .../main/resources/host-profiles/7.0.3-1626.json   | 6345 ++++++++++++++++++++
 .../src/main/resources/host-profiles/index.json    |    4 +-
 37 files changed, 14407 insertions(+), 171 deletions(-)
```
