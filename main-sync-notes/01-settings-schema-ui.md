# 01 · 设置与配置层评估（upstream 1.7.1 → integ）

分支 `merge/main-1.7.1`（from integ/all-features `ee6a99f6`）合 `origin/main` `8bdf6636`。
本笔记只覆盖：`ModuleSettingsSchema.kt`、`EmbeddedSettingsPages.kt`、`EmbeddedLyricsSettingsPages.kt`（冲突）
+ `app/build.gradle.kts`、`EmbeddedLlmSettingsPage.kt`、`ModuleText.kt`、`ModuleModels.kt`、
`ModuleSettingsSchemaTest.kt`、`OrdinarySettingsWritePolicyTest.kt`（关注项）。
**只读评估，未 add/commit/abort，未改任何源码。**

## 0. 事实基线（先钉死版本号问题）

| 事实 | 证据 |
| --- | --- |
| 上游在 base..origin/main 窗口内**从未改过** `ModuleConstants.kt` | `git diff --stat 1159d90c origin/main -- …/ModuleConstants.kt` → 空 |
| base = 21、origin/main = 21、HEAD(integ) = 29 | `git show <ref>:…/ModuleConstants.kt \| grep CONFIG_SCHEMA_VERSION` |
| 合并后保留 29（文件两侧无差异，自动取 HEAD） | worktree `ModuleConstants.kt:66` |
| 上游本窗口对 schema 的唯一改动 = `glass_adaptive_foreground_enabled` 共 4 处（decode / encodeOrdinary / settingKeys / private const） | `git diff 1159d90c origin/main -- …/ModuleSettingsSchema.kt`（只有 +7 行） |
| 上游 1.7.0/1.7.1 的 1624/1626 适配**没有代码改动**，只加 host-profile JSON + index.json | `git show --stat bd6bd5ba / 41842ba4` |
| 冲突规模（我的范围） | **3 文件 / 4 hunk**（ModuleSettingsSchema 1、EmbeddedSettingsPages 1、EmbeddedLyricsSettingsPages 2） |

结论一句话：**上游没有推进 schema 版本，29 仍然只属于我们；因此不存在「上游迁移 vs fork 迁移」的版本号对撞，只有「谁的键先被读到」的语义边界问题**（见 §3 Q1）。

## 1. 冲突逐条

### 1.1 `core/…/config/ModuleSettingsSchema.kt` 35–114（1 hunk）

**上游意图**
只在 `decode(...)` 里插一个键，其余保持原样（表达式体 `= ModuleSettings(`、单行参数、无尾逗号）：

```kotlin
glassAdaptiveForegroundEnabled = values.boolean(
    KEY_GLASS_ADAPTIVE_FOREGROUND,
    default = true,
),
```

**我们的意图**
`decode` 从表达式体改成**块体**，并把整个函数按 ktlint 多行重排：

```kotlin
private fun decode(values: Map<String, *>, customLyricsManifest: CustomLyricsManifest): ModuleSettings {
    // One read for the four metadata controls …
    val metadata = metadataConfiguration(values)
    return ModuleSettings( … )
}
```

块体不是洁癖：区域/元数据四控件必须在**同一次解码里读同一个 reader**（`metadata.region`、`metadata.overrideAccountLanguage`、`metadata.restoreCjkOriginalMetadata`、`metadata.localizedMetadataCache` 全部取自 `metadata`），运行时 request seam 也走同一个 `metadataConfiguration`，两条路径不能对默认值有分歧。这个 hunk 里 **99% 的逐行差异是格式**，上游那 4 行被我们的重排整段吃掉了。

**建议解法：保留 HEAD 整块，手工补 4 行 glass 字段。**
插入锚点：HEAD 侧 `phoneLiquidGlassEnabled = values.boolean(KEY_PHONE_LIQUID_GLASS, default = false),` 之后，
`phoneLiquidGlassBottomGapDp = …` 之前（worktree 现在冲突块的 HEAD 侧第 69 行 `),` 之后）：

```kotlin
            glassAdaptiveForegroundEnabled = values.boolean(
                KEY_GLASS_ADAPTIVE_FOREGROUND,
                default = true,
            ),
```

为什么不能取上游侧：取上游就等于把 `decode` 退回表达式体，同时**丢掉整个 v20/v21 区域控件与 fork 的 30+ 个键**（`lyricsFontScalePercent`、`regionSelection`、`onlineLyrics*`、`llmPronunciationBoundaryEnabled`、`automaticLyricsPreferNative`、`customLyricsSourceBaseUrl`…）。这不是“格式回退”，是功能删除。

**不要重复补的两处**（已自动合入，重复 = 编译错）：
- `KEY_GLASS_ADAPTIVE_FOREGROUND to settings.glassAdaptiveForegroundEnabled`（worktree:290，在 `encodeOrdinarySettings`）
- `settingKeys` 里的 `KEY_GLASS_ADAPTIVE_FOREGROUND`（worktree:743）
- `private const val KEY_GLASS_ADAPTIVE_FOREGROUND = "glass_adaptive_foreground_enabled"`（worktree:827）

### 1.2 `app/…/ui/EmbeddedSettingsPages.kt` 263–274（1 hunk）

**上游意图**（base:255 之后再插 2 行）
```kotlin
EmbeddedSettingsPage.AUTOMATIC_LYRICS ->
    EmbeddedAutomaticLyricsPage(activity, current, ::applySavedChange)
EmbeddedSettingsPage.CACHED_LYRICS ->
    EmbeddedLyricsCachePage(activity, active = active)
```
依旧是旧的 3 参调用；新增第三方缓存管理页的路由。

**我们的意图**
同一个 `AUTOMATIC_LYRICS` 分支改成两个具名回调：
```kotlin
EmbeddedAutomaticLyricsPage(
    activity,
    current,
    onSettingsSaved = ::saveChange,
    onKeyScopedSettingsSaved = ::mirrorSavedChange,
)
```
（`b9ca7c9b fix(settings): persist a cleared custom lyrics source`：搜索区必须走普通写，
键作用域的 controller 写只镜像草稿——路由错就会“清空镜像地址后重启又回来”。）

**建议解法：取 HEAD 的具名调用 + 追加 CACHED_LYRICS 分支**，最终：
```kotlin
EmbeddedSettingsPage.AUTOMATIC_LYRICS ->
    EmbeddedAutomaticLyricsPage(
        activity,
        current,
        onSettingsSaved = ::saveChange,
        onKeyScopedSettingsSaved = ::mirrorSavedChange,
    )
EmbeddedSettingsPage.CACHED_LYRICS ->
    EmbeddedLyricsCachePage(activity, active = active)
```

理由 / 取错侧的后果：
- `::applySavedChange` 在 HEAD **已被改名删除**（全仓 grep 只剩冲突块里这一处），取上游侧 = 未定义引用；
- `EmbeddedAutomaticLyricsPage` 现在 4 个参数（`EmbeddedLyricsSettingsPages.kt:327`），3 参调用 = 编译错；
- `active` 在同 entry 作用域 253 行定义（`navigationStack.lastOrNull() == page`），`EmbeddedLyricsCachePage`
  签名为 `(activity: Activity, active: Boolean)`（`EmbeddedLyricsCachePage.kt:218`），可直接用。

### 1.3 `app/…/ui/EmbeddedLyricsSettingsPages.kt` 367–371（hunk 1）

**上游意图**
从自动歌词页**删掉** `EmbeddedLyricsCacheRow()`（base:339）并删掉它的定义（base 681–737，57 行），
缓存 UI 迁到新页；入口改到自定义歌词页的「歌词缓存管理」链接（已自动合入，现位于
AUTO_LYRICS 链接与 LLM_LYRICS 链接之间，worktree:141–147）。

**我们的意图**
在同一位置**新增** `EmbeddedOnlineLyricsSourceSection(settings, onSettingsSaved)`（fork 的在线搜索源
区块：镜像地址 / 补充开关 / 翻译 / 拼音 / 全局最佳 / 自动顺序 / 四源开关），行尾保留 base 的
`EmbeddedLyricsCacheRow()`。

**建议解法：保留在线源区块，删掉 `EmbeddedLyricsCacheRow()`。** 最终：
```kotlin
        onReset = { pageRefresh?.invoke() },
    )
    EmbeddedOnlineLyricsSourceSection(settings, onSettingsSaved)
}
```

理由：
- 上游已删该 composable 的**定义**（worktree 只剩这处调用，grep 确认）→ 保留即编译错；
- 能力没有回退：歌词弹窗菜单里还有一条同类缓存行（`EmbeddedLyricsMenu.kt:276 / 525`）
  用的是同一个 `LyricsCacheStatusState`（该类仍在 `EmbeddedLyricsSettingsPages.kt:663`）；
- 新页覆盖得更全（条目列表、单条删除、清空、来源筛选、大小/时间排序）；
- 不去恢复那 57 行：会得到两套缓存 UI + 两套清理入口，行为还容易不一致。

### 1.4 `app/…/ui/EmbeddedLyricsSettingsPages.kt` 1155–1404（hunk 2）

这是「**双方在同一锚点各插入一段**」的伪冲突：

**上游意图**（该 hunk 的上游侧为空）
这里它只有一处改动：base:1167 `private fun automaticLyricsSourceName(...)` → `internal`
（新页 `EmbeddedLyricsCachePage.kt:680` 要调用它）。我们这边也是 `internal`，git 已把这一行
自动合到 worktree:1405。

**我们的意图**（247 行）
在线搜索源区块之后的一整批新代码：`withUnifiedSearchTier()`、`normalizeLyricsSourceAddressInput`、
`clearCustomLyricsSummaryKey`、`showLyricsSourceBaseUrlDialog`、`showClearCustomLyricsDialog`、
`onlineSearchSourceName`、以及 `automaticLyricsSourceName` 的 KDoc。

**建议解法：整段取 HEAD（保留我们的 247 行）。**
检查点（合并后必须肉眼过一遍）：
- `automaticLyricsSourceName` 必须仍是 `internal`（worktree:1405 已就位，别被回退成 `private`）；
- 它的 4 个搜索源分支 `NETEASE/QQ/KUWO/KUGOU -> localizedText(onlineSearchSourceName(source))`
  在冲突块之外（worktree:1411–1414）已就位，不要丢；
- KDoc（1396–1402）描述的是 29 号统一顺序把搜索源喂进同一列表，保留有用。

## 2. 非冲突但语义有变的点（我的范围）

### 2.1 玻璃自适应前景开关（上游 `b3ba3b39`）
- **落点**：INTERFACE 页 `if (settings.phoneLiquidGlassEnabled)` 组的**第一行**（worktree:692–696），
  在「底栏高度」之前。我们 fork 的玻璃组（高度/模糊/折叠/回弹/凹陷）原样排在它后面，无重复。
- **默认值语义**：`ModuleSettings.glassAdaptiveForegroundEnabled = true`（自动合入，ModuleModels:14）。
  旧 store 没有这个 key → decode 出 `true` → **老用户升级后底栏/迷你播放器文字图标会开始自动反色**，
  这是可见的外观突变，但符合上游意图（它就没打算 bump 版本做迁移）。
- `settingKeys` 已含该键 → `hasMigratableValues` 会把它算作可迁移设置（自动合入，正确）。
- **ordinary-write 契约**：`encodeOrdinarySettings` 会写它（worktree:290）。这与
  `OrdinarySettingsWritePolicyTest` 的整表 `assertEquals` 强耦合——**测试要求 decode 也必须读它**，
  也就是 1.1 那个补丁是必须的，不是可选的。

### 2.2 第三方歌词缓存管理页（上游 `fcbfb5f9`）
- **不进 schema**：整页只用 `LyricsPlaybackRuntimeRegistry.controls()` + 文件 store；
  `EmbeddedLyricsCachePage.kt` 里没有任何 `KEY_`/`ModuleSettingsSchema`/`ModuleSettings` 引用（grep 为空）。
  所以**没有默认值、没有迁移、不需要版本号**。
- **落点**：`EmbeddedSettingsPage.CACHED_LYRICS`（`EmbeddedSettingsDesign.kt` 枚举，位于
  `AUTOMATIC_LYRICS` 与 `LYRICS_EFFECTS` 之间）；`navigationCategory = CUSTOM_LYRICS`（不新增侧栏项）；
  `title = ModuleText.LYRICS_CACHE_MANAGEMENT`；详情页三行说明（worktree:912 附近，自动合入）；
  入口 = 自定义歌词页链接。
- 入口迁移：自动歌词页不再有缓存块；歌词弹窗菜单的缓存行**仍在**（既有能力不回退）。

### 2.3 LLM 歌词缓存清理（上游 `f68fcc7e`）
- **落点**：LLM 页新卡片 `LlmLyricsCacheCard`（调用 worktree:172，定义 334），位于第一个 group 之后、
  API 设置小节之前。自动合入，与我们 fork 在该页加的边界开关/音译开关**完全不重叠**。
- **不进 schema**：`FileLlmLyricsCache` 新增 `generation()/usageBytes()/clear()`；清理是文件操作。
  `LlmLyricsRequest.cacheGeneration` 让在途请求写不回已清空的缓存（并发正确性，不是设置项）。
- 依赖已就位：`EmbeddedLyricsPlaybackRuntime:610/611` 暴露 `llmCacheUsageBytes()/clearLlmCache()`。

### 2.4 `ModuleText.kt`（自动合入，但我做了完整性核对）
572 条 = HEAD 534 + 上游独有 38 + 我们独有 43，**无重复常量、无丢失**。
上游新增含 `GLASS_ADAPTIVE_FOREGROUND(_SUMMARY)`、`LYRICS_CACHE_MANAGEMENT(_SUMMARY)`、
`LYRICS_LLM_CACHE*`、`LYRICS_CACHE_SEARCH*` 等。

### 2.5 两个 schema 测试（自动合入，是本次的硬门槛）
- `ModuleSettingsSchemaTest`：期望 map 里加了 `glass_adaptive_foreground_enabled to true`（104、182 行），
  同时保留我们 fork 的全部键（`lyrics_font_scale_percent`、`region_selection`、
  `online_lyrics_*`、`llm_pronunciation_boundary_enabled`…）。
- `OrdinarySettingsWritePolicyTest`：字段侧 `glassAdaptiveForegroundEnabled = false`（96）、
  整表侧 `"glass_adaptive_foreground_enabled" to false`（109）。
这两个 `assertEquals` 是「1.1 合得对不对」的**唯一硬判据**（多一个键/少一个键、默认值写错都会红）。

### 2.6 `app/build.gradle.kts`
上游 `versionCode 119/1.6.9` → `121/1.7.1`，**自动合入无冲突**（我们本来没改版本号）。
要不要带 fork 后缀由你拍板（见 §5）。

## 3. 直接回答四个问题

### Q1 `ModuleSettingsSchema` 的冲突是什么？上游是否把 29 又推进了？我们的 28→29 怎么接？
- 冲突 = **纯格式冲突**：我们重排 `decode` 成块体（为了 `metadata` 单次读），上游只插了 1 个键。
  解法 = 我们的块体 + 手补 4 行 glass（§1.1）。
- **上游没有推进版本号**：整个窗口 `ModuleConstants.kt` 零改动，仍是 **21**；29 是我们 fork 自己的号，
  合并后仍是 29（无需 bump：新键都有默认值）。
- **28→29 的接法**：`forkAutomaticLyricsSourceOrder()` 保持现有判据——
  `number(KEY_SCHEMA_VERSION) < CONFIG_SCHEMA_VERSION(29)` **且** store 里带任一 fork 键
  （`online_lyrics_source_order` / `online_lyrics_supplement_enabled` / 四个源开关）。
  上游 store（21、无 fork 键）→ 返回 `null` → 落到上游 `automaticLyricsSourceOrder()`
  （`hasLegacyAutomaticLyricsSourceOrder` 只在 schema < 21 时才折叠 `prefer_native`）。
- **会不会和上游迁移冲突/重复？不会**：
  - fork 路径返回非 null 时，`?: values.automaticLyricsSourceOrder()` 右边的上游路径根本不被求值 → 单次折叠；
  - 上游路径对 28/29 store 也不做任何折叠（`28 < 21` 为假），两边不是同一条流水线；
  - 边界是「**有没有 fork 键**」而非版本号，这条已被 `AutomaticLyricsOrderMigrationTest`
    的第 4 个用例（`an upstream only store keeps upstream's own legacy handling`，schema 21）钉住 ——
    合并后这个用例正好替我们回归上游 store 场景。
- 两个附带提醒：
  1) 将来上游若把 21 推到 22…30 会和我们**撞号**（现在不会）。同时也说明我们不该把「加默认值键」
     当成必须 bump 的理由（上游 1.7.1 就没 bump）。
  2) fork 的 29 号语义**没有变**（只是多一个带默认值的键），所以已发出的 29 store 不需要再迁一次。

### Q2 上游新增字段要不要进 schema/默认值/迁移？我们多出来的字段要不要全留？
- 玻璃自适应前景：**要**进 schema 的 4 处（decode/encodeOrdinary/settingKeys/const，已自动合并）+
  `ModuleSettings` 默认 `true`（已自动）。**不需要**迁移、**不需要** bump（缺 key 走默认值即上游意图）。
- 歌词缓存管理页 / LLM 缓存清理：**都不进 schema**（纯文件 + 运行时能力），无默认值、无迁移。
- 我们 fork 多出来的字段（读音、边界、LLM 音译、自定义源、在线源、字体缩放、行距、区域四控件、
  元数据缓存 generation…）：**全部保留，无一可删**。证据：`git diff HEAD -- ModuleSettingsSchema.kt`
  只有 3 处 glass 新增、0 处删除；`ModuleModels.kt` 同样只有 1 个新字段。

### Q3 EmbeddedSettingsPages / EmbeddedLyricsSettingsPages 冲突
见 §1.2 / §1.3 / §1.4（上游改哪行、我们改哪行、最终形态、取错侧的编译后果都写了）。

### Q4 热生效差异 & 对我们调用期判定的影响
上游 1.7 把「读取时机」改动集中在这三处：
| 项 | 读取时机 | 是否要重启 |
| --- | --- | --- |
| `glass_adaptive_foreground_enabled` | `PhoneGlassSession`/`FragmentTabletGlassSession` 的 500ms `nextSettingsCheck` 轮询重读 + `mutableStateOf`（137/478/961、81/127） | **否**（约 0.5s 生效） |
| 第三方缓存管理页 | 进入页面时 `refreshCacheSongNames` + `registry.controls()` 订阅，实时 | 否 |
| LLM 缓存清理 | 即时文件删除 + `cacheGeneration` 让在途请求写不回 | 否 |

注意同一个玻璃组里的主开关 `phone_liquid_glass_enabled` 文案仍是「需重开应用」（Restart to apply）——
**同一组里两个开关一个热一个不热**，这是上游自己的不一致，不是合并引入的。

对我们已有调用期判定的影响：**没有实质影响**，逐条核对过：
- 读音 lane 的安装门（`NativeLyricsFeature.kt:15` 读一次 `settings`）与上游无关；
  上游这 37 个文件里没有读音/居中/拼接相关文件。
- 读音 matching / 居中：`LyricsPronunciationCentringBridge` + `LyricsPronunciationWordAlignment`
  走 id 名解析（`resources.getIdentifier`）与内存 plan provider，不读设置、无版本表 → 新增键不影响；
  但 1624/1626 只是换了一份 profile（3690 个路径同构，仅混淆 id/字段名不同，
  `/verification/*` 有 20 条增删、141 处值变），**行钩子要不要为新版本再验一次属于 host 侧的事**（见 §4 验证清单）。
- 元数据：`RegionMetadataConfigurationWatcher`（4 键短 TTL）+ `metadataConfiguration(values)`，
  上游一行没动；新增玻璃键只是让 `config.settings()` 多解一个 boolean。
- 拼接（自定义歌词 → 原生注入）：走 `AutoLyricsReplacementSession`（别人的冲突文件），
  本轮上游对它的改动是缓存回填/缓存管理，不涉及我们的拼接判定。

## 4. 风险等级与合并后必跑

**总体：中**（编译期强耦合、无数据迁移风险；唯一的“高”是漏补字段导致核心测试红，但很容易发现）。

| 项 | 等级 | 说明 |
| --- | --- | --- |
| 1.1 漏补 `glassAdaptiveForegroundEnabled` | 低（但必踩） | `ModuleSettings` 构造缺参 → 编译错；两个 assertEquals 也会红 |
| 1.1 重复补 encode/settingKeys/const | 低 | 重复声明 = 编译错 |
| 1.2 取上游侧 | 低 | `applySavedChange` 未定义 + 参数不匹配 |
| 1.3 保留 `EmbeddedLyricsCacheRow()` | 低 | 定义已被上游删除 → 未解析引用 |
| 1.4 回退成上游（空块） | 低 | 丢 247 行 + 丢在线源区块，编译错 |
| 玻璃自适应默认 true 的观感突变 | **中** | 老用户升级后底栏文字开始换色；产品判断 |
| 29 vs 上游 21 并存 | 低 | 现在无冲突；未来上游 bump 有撞号可能，需记录 |
| 1624/1626 上我们的行钩子（居中/读音） | **中** | 上游只加 profile，没改代码；需要真机验 |

必跑（顺序即优先级）：
1. `python3 scripts/verify-architecture.py`（提交前强制）
2. core jvmTest：`ModuleSettingsSchemaTest`、`OrdinarySettingsWritePolicyTest`、
   `AutomaticLyricsOrderMigrationTest`、`RegionSelectionMigrationTest`、`RegionSelectionTest`、
   `RegionMetadataConfigurationTest`、`RegionTitleCompositionTest`
3. CI debug APK + 真机（7.0.3/1626 优先）：
   - INTERFACE → 玻璃组第一行出现「自动反色 / Auto-invert glass text and icons」，**开关即时生效**（不重启）；
   - 歌词管理 → 「歌词缓存管理」入口存在；能列出条目、按来源筛选、删单条、清空全部；
     自动歌词页的在线源区块（镜像地址/补充/翻译/拼音/四源）仍在，改完重启不回退（1.3 的回归点）；
   - LLM 页出现缓存卡片，清理后 usage 归零；清理过程中切歌不崩；
   - 读音（罗马音行）与居中在 1626 上仍然生效；
   - 日志里不出现 `settings-open failed`（除非真的失败）。
4. 老 store 升级验证：用一份 schema 28 的 fork 偏好文件启动 → 顺序迁移成统一顺序
   （`AutomaticLyricsOrderMigrationTest` 已在单测层覆盖，真机只看“搜索源顺序没乱”）。

## 5. 需要你拍板的点

1. **玻璃自适应默认 `true` 是否照抄上游**？照抄 = 老用户视觉突变（符合上游、零迁移成本）；
   改 `false` = 我们和上游默认不一致（要在 decode 里区分「新装」与「老 store」，成本更高）。我的建议：照抄上游。
2. **版本号**：保留 `1.7.1 / 121`，还是要 fork 标记（如 `1.7.1-fork`）？影响 CI 产物命名与用户识别。
3. **要不要顺手把 `phone_liquid_glass_enabled` 也做成热生效**（上游没做，同组开关文案仍写 “Restart to apply”）。
   这属于行为改进，超出合并范围，默认**不做**。
4. **`EmbeddedLyricsMenu` 里那条缓存行留不留**（新页已覆盖）。建议留（不回退既有入口），但要你确认
   “两处缓存入口”是否可接受。
5. **29 号要不要改成 30 与上游 21 拉开**？建议**不要**（会让所有 29 store 再迁一次，收益为零）；
   只把这个撞号可能性写进文档即可。

## 6. 我无法判断 / 需要别人核对的

- 1624/1626 上我们的**行级钩子**（读音行几何、居中、自定义歌词注入）是否仍然命中：
  上游只加 profile 没改代码，但新 profile 的混淆 id 有变化（`PlayerLyricsViewFragment` 的
  `q0→C0`、`l0` 等），属 host-profile/行钩子负责人；
- `AutoLyricsReplacementSession` / `AppleMusicAssembly` 的冲突（别人负责）会决定
  「缓存管理 + 我们的拼接/自定义歌词快路径」是否共存，我这里只看设置层入口有没有落地；
- `EmbeddedLyricsPlaybackRuntime` 的 `cacheSnapshot`/`refreshCacheSongNames` 与我们的
  `LyricsPlaybackRuntimeRegistry` 订阅语义（我只核对了签名存在，未核运行时行为）。
