# main (1.7.1) → integ 合并执行计划

分支 `merge/main-1.7.1`（从 `integ/all-features` `ee6a99f6` 开出），目标 `origin/main` `8bdf6636`。
试合并：7 文件冲突 / 37 文件变动。三份评估：`01-settings-schema-ui.md`、`01-lyrics-runtime-cache-llm.md`、`05-host-applemusic-glass.md`。

## 0. 拍板结论（本计划采用的）

| 项 | 决定 |
| --- | --- |
| `CONFIG_SCHEMA_VERSION` | 保持 **29**（上游仍是 21，不存在撞号） |
| 模块版本 | 保留上游 **1.7.1 (121)** |
| `createAutoLyricsRuntime` 的 `currentSong` | **保留上游形参、无默认值**；assembly 同一次补实参 |
| 1624/1626 读音 seam | **本次不补**；登记为已知降级 + 单测钉住“为空”，后续用 1624/1626 的 base.apk/splits 派生 |
| 玻璃自适应默认值 | 照抄上游 **true** |
| 平板顶栏自动反色 | 接受（与上游一致） |
| `appleMusic(...)` 位置参数 | 改为**具名实参**（9 参 6 个类型相近，这次冲突正因此产生） |
| `lookupCacheSongNames` | 合并时一起修：`amLyricsIndexCache.index() ?: amLyrics.fetchIndex()` |
| LLM 清缓存 | 不动 `ampp-llm-boundary` |
| `deleteCachedLyrics` 清理 preferredSource/offset | 保持上游，不加（T1 遗留） |

## 1. 解冲突顺序

1. **core/.../config/ModuleSettingsSchema.kt**（35–114，纯格式）
   - 保留 HEAD 的块体 decode；在 `phoneLiquidGlassEnabled` 之后手补 4 行 `glassAdaptiveForegroundEnabled`（default=true）。
   - `encode` / `settingKeys` / `KEY_*` 三处**已自动合入，禁止重复补**。
2. **app/.../ui/EmbeddedSettingsPages.kt**（263–274）
   - 取 HEAD 的具名回调 `onSettingsSaved = ::saveChange` / `onKeyScopedSettingsSaved = ::mirrorSavedChange`；
   - 追加 `EmbeddedSettingsPage.CACHED_LYRICS -> EmbeddedLyricsCachePage(activity, active = active)`。
3. **app/.../ui/EmbeddedLyricsSettingsPages.kt**（两处）
   - 367–371：保留 `EmbeddedOnlineLyricsSourceSection(...)`，删掉 `EmbeddedLyricsCacheRow()` 调用（定义已被上游删除）；
   - 1155–1404：整段取 HEAD（247 行）；确认 `automaticLyricsSourceName` 仍是 `internal`，1411–1414 的四个搜索源分支在。
4. **app/.../hook/AutoLyricsReplacementSession.kt**（形参表）
   - 在 `lunabeatCatalogCache` 之后插入 `currentSong: CurrentSongIdentityCache,`（**无默认值**）；其余 13 个带默认值参数原样、`configuration` 仍在最后；
   - 函数体保持合并结果（`refreshSong` 的 `latest.copy(displayName = previous.displayName)`、LLM 的 `expectedGeneration` 不动）。
5. **app/.../hook/AppleMusicAssembly.kt**（1 hunk）
   - 取 HEAD（保留 `timingObservations`/`nativeLyricOverlay`、runtime 总门、非空 `automatic`）；
   - 补 `currentSong = currentSong,` 实参；同时把 `appleMusic(...)` 调用改成**具名实参**。
6. **glass/.../GlassNavigation.kt**（2 hunk）
   - hunk1：保留我们的 `labelStyle`（平板 15sp SemiBold / 手机 11sp），**颜色换上游 `labelColor`**；
   - hunk2：保留 3 参 `tabContent(index, weight, refraction)` 与 `refractionContent`，**加回** `onSampleMaterial = contrast?.sampleMaterial` 与 `sampleCompactForeground = neutralCompactGlyph`。
7. **glass/.../LiquidBottomTabs.kt**（KDoc）
   - 取并集，末尾补上游那句 "The panel can expose its recorded material for adaptive tab foregrounds."

## 2. 合并同时落的功能修复

- `AutoLyricsReplacementSession` 的歌词索引回填改为 `amLyricsIndexCache.index() ?: amLyrics.fetchIndex()`（不再 new 裸 `AmLyricsClient`，保住自建镜像与磁盘索引）。
- 为 1624/1626 的“读音 seam 为空”加一条单测（断言 `hookTargets.LYRICS_VIEW_MODEL_LOAD` 等为空），把降级写成已知事实而不是遗漏。

## 3. 验证阶梯

1. `python3 scripts/verify-architecture.py`、`verify-profile-data.py`（10→12 个精确档案）、`verify-host-profile.py`、`verify-glass-reference.py`；
2. core jvmTest：`ModuleSettingsSchemaTest`、`OrdinarySettingsWritePolicyTest`（两张整表 assertEquals 是 schema 合并的硬判据）、`AutomaticLyricsOrderMigrationTest`、`RegionSelectionMigrationTest`、`RegionMetadataConfigurationTest`；
3. host 结构性探针：`LyricPresenceWiringTest`、`AutoLyricsIndexCacheWiringTest`、`OnlineLyricsSupplementWiringTest`、`AutoLyricsRuntimeStructuralRegressionTest`、`AppleMusic700NativeLyricsProfileTest`、`GlassTabletTopBarWiringTest`、`PhoneLiquidGlassStructuralRegressionTest`；
4. CI 全量（`bin/ampp-ci.sh watch`）；
5. 真机（1614）：读音、居中、按词拼接、自定义歌词添加/保存、边界提示词、元数据开关即时生效；
6. 真机（1626，若升级）：玻璃自适应首行开关、缓存管理页、读音降级是否可接受。

## 4. 回滚与备份

- 合并前给 `integ/all-features` 尖端打 tag（`backup/integ-before-main-171`）并推送；
- 冲突全部解决并 CI 绿后再 fast-forward `integ/all-features`。
