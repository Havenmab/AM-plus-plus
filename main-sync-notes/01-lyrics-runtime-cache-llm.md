# 01 · 歌词运行时 / 缓存 / LLM 会话 — 1.7.1 合并评估

范围：AutoLyricsReplacementSession.kt（冲突）、EmbeddedLyricsPlaybackRuntime.kt、EmbeddedLyricsSourceStore.kt、
LyricsPlaybackRuntimeRegistry.kt、LlmLyricsCache.kt / LlmLyricsReplacementSession.kt（LLM 缓存清理）、
新增 EmbeddedLyricsCachePage.kt 与 EmbeddedLlmSettingsPage.kt 的缓存卡片。

基线与两侧：merge-base `1159d90c`，ours = `HEAD`（integ/all-features `ee6a99f6`），theirs = `MERGE_HEAD`（origin/main `8bdf6636`）。
上游相关提交：`fcbfb5f9`（第三方歌词缓存管理）、`f68fcc7e`（LLM 歌词缓存清理）、`dada1da0`（缓存歌曲名回填）。
我方相关提交：9a58a9e4（编辑器保存落 manual index）、92087483（清空时丢掉 manual 镜像）、c34d7e32、986fd9d1/4811bb29 系列（读音边界）、
35fdebcc/c7acc554（自建镜像 + 索引缓存 + presence）。

**结论先行**：本文件范围内只有 **1 处冲突**，而且是"纯签名"冲突（body 已被 git 自动合并成上游版本），
解法是**两边都要**：保留我们 13 个带默认值的参数，插入上游的 `currentSong`。
真正危险的不是冲突本身，而是 **AppleMusicAssembly 的调用点必须同步补 `currentSong = currentSong,`**，
否则编译直接挂在「没有为参数 currentSong 传值」。

---

## 1. 冲突逐条

### 冲突 #1（唯一一处）：`createAutoLyricsRuntime` 形参表 · 文件 L171–187

**上游意图**（`dada1da0` + `fcbfb5f9`）
- 加一个必填参数 `currentSong: CurrentSongIdentityCache`（位置：`lunabeatCatalogCache` 之后、`configuration` 之前）。
- 原因：新代码需要"当前播放曲"的 Apple Music 元数据才能回填缓存歌曲名：
  - `withSongTitle()`（L296–313）用 `currentSong.current()?.details` 兜名字；
  - `lookupCacheSongNames()`（L315–370）先问当前曲，再问 AM-Lyrics 索引和 LunaBeat 目录；
  - `songNameSubscription`（L728–733）订阅换歌事件，把「歌名 - 歌手」推进 playback 的回填队列。
- 上游同时把两处 LLM 落盘改成带 `expectedGeneration`、给 `LlmLyricsRuntime` 传 `cacheGeneration`，
  并把 `playback` 的构造参数加了 `lookupCacheSongNames` / `llmCache`（这些都在自动合并区，已就位）。

**我们的意图**（integ 侧，base→HEAD 431 行）
- 在同一形参表后追加默认值参数：`suppressedIds`、`onlineLyricsSupplementEnabled`、`onlineLyricsTranslationEnabled`、
  `hideMandarinPronunciation`、`genreFor`、`onlineLyricsSelection`（默认走 `OnlineLyricSourcePolicy.resolve(ModuleSettings())`）、
  `currentTrack`、`logger`、`displayedTtml`、`nativeLyricOverlay`，并把 `configuration` 变成有默认值、仍在最后。
- 目的：宿主装配层（AppleMusicAssembly）能一次性注入读音/翻译/自建镜像/边界所需的全部 seam，同时保持
  `core` 与 `app` 解耦、单元测试可以不传。

**建议解法：保留两边，按下面的顺序**

```kotlin
internal fun createAutoLyricsRuntime(
    application: Application,
    content: EmbeddedContentManager,
    lunabeatCatalogCache: LunabeatCatalogCache,
    currentSong: CurrentSongIdentityCache,          // ← 上游（必填，无默认值）
    suppressedIds: Set<Long> = emptySet(),          // ↓ 我们，全部保留
    onlineLyricsSupplementEnabled: Boolean = false,
    onlineLyricsTranslationEnabled: Boolean = false,
    hideMandarinPronunciation: Boolean = false,
    genreFor: (Long) -> String? = { null },
    onlineLyricsSelection: OnlineLyricSelection =
        OnlineLyricSourcePolicy.resolve(ModuleSettings()),
    currentTrack: () -> CurrentSongDetails? = { null },
    logger: (String) -> Unit = {},
    displayedTtml: (Long) -> String? = { null },
    nativeLyricOverlay: NativeLyricOverlayStore = NativeLyricOverlayStore(),
    configuration: () -> ModuleSettings = { ModuleSettings() },
): AutoLyricsRuntime
```

为什么这样合：
1. **不要给 `currentSong` 默认值**。`CurrentSongIdentityCache()` 是一个永远收不到 `publish` 的空缓存：
   编译能过，但名字回填、换歌订阅全部静默失效——这是"能跑但功能没了"的最坏失败方式。
2. **位置照上游**（`lunabeatCatalogCache` 之后）。上游参数保持相对顺序 = 下次 1.7.2 再合时参数区还是同一块，
   冲突面最小；插到末尾也合法（调用点全用具名实参），但会让未来的上游签名永远错位。
3. `configuration` 继续放最后并保留默认值：上游也是最后一个参数，我们只是给它加了默认值，两边不打架。
4. **body 一行都不要回退**：自动合并出来的 body 已经是上游 + 我们的混合体，且互相依赖——
   `withSongTitle`/`lookupCacheSongNames` 只能在有 `currentSong` 的前提下编译；`bndry` 日志、`triggerBoundary`、
   `boundaryOnDocument`、`boundarySession`、`sourceStore` 接线、`refreshSong` 的 `latest` 重读全都在非冲突区，保持现状。

**必须一起改的连带点（跨文件，属 assembly 的归属人）**
`app/.../hook/AppleMusicAssembly.kt` 里 `createAutoLyricsRuntime(...)` 的实参块（自动合并区，L30–53）**没有** `currentSong`：

```kotlin
        createAutoLyricsRuntime(
            application = application,
            content = settingsController.content,
            lunabeatCatalogCache = settingsController.lunabeatCatalogCache,
            currentSong = currentSong,        // ← 新增，否则 "No value passed for parameter 'currentSong'"
            suppressedIds = suppressed,
            ...
            configuration = config::settings,
        )
```

好消息：`assembleAppleMusicTarget` 本来就收了 `currentSong: CurrentSongIdentityCache`（它已经在用
`currentTrack = { currentSong.current()?.details }`），所以只是把同一个对象多传一次，没有新的传播链。

**这条冲突不会碰到的我们这些东西**（逐个确认过，可以放心）
| 我们的东西 | 合并后位置 | 状态 |
|---|---|---|
| `boundarySession` / `PronunciationBoundaryRuntime`（读音边界） | L476–546 | 非冲突区，原样 |
| `boundaryOnDocument` + `triggerBoundary`（含防自递归注释） | L388–415、L572–583 | 非冲突区，原样 |
| `LyricsPronunciationCentringBridge.setBoundaryPlans / setDocumentObserver` | L548–571 | 非冲突区，原样 |
| `bndry` 系列日志（enricher/llm/transport/ready/trigger） | L144–145、396–414、509–521、536–539、551–563、603–618 | 非冲突区，原样 |
| AMLL/AM-Lyrics 诊断与自建镜像（`customSourceBaseUrl`、`amLyricsIndexCache`、`lyricPresenceCache`） | L209–259、L261–289 | 非冲突区，原样 |
| `sourceStore` 接线（`cache.writeCandidateIfCurrent`、`refreshSong`、`preserve` 语义） | L371–377、L757–775 | 上游在我们这层上又包了一层 `withSongTitle`，见 §2.2 |
| `publishDisplayed` 相关 seam（`nativeLyricOverlay`、`overlay?.update`） | L155–159、L449、L741 | 非冲突区，原样 |

---

## 2. 非冲突但语义有变的上游改动（我的范围）

### 2.1 `EmbeddedLyricsSourceStore.kt`：186+/3-，全是上游的（我方自 base 起对该文件零提交）

`git diff 1159d90c..HEAD -- app/.../config/EmbeddedLyricsSourceStore.kt` 为空 ⇒ **我们的手动索引/来源分流/salvage/修剪一行都没被改**，
上游改动是纯叠加，无文本冲突。逐条看语义：

| 上游新增 | 位置 | 与我们现有机制的交叉 |
|---|---|---|
| `LyricsCacheEntry` / `LyricsCacheSnapshot` / `cacheSnapshot()` | L16–25、L116–140 | **只读**，明确不迁移、不写 LRU；复用 `withCustomLyricsMutation`（进程级 `INDEX_MUTATION_LOCK`）。但它会读索引 + 扫目录算 `usageBytesLocked()`，**只能在工作线程调**（缓存页已经走 IO + 单线程 worker）。 |
| `updateCacheSongNames(names, expectedGeneration)` | L143–185 | 只写 **sourceManifest**（`commitSourceManifest`），从不碰 manual index；先 `generationMatches` 再 `prepareSource()`；名字过 `cacheSongDisplayName` + `sanitizeDisplayName` 双过滤；无变化直接 true（不写盘）。 |
| `deleteCachedLyrics(id, source?)` | L225–278 | 只删第三方 source 槽（`isThirdPartySource` 门禁）；要求 manual index `isResolved`；删完 `generation++`；回收正文走 `retireUnreferenced`，而它把 `shared.manualManifest` 的所有 fileId 都放进保护集 → **编辑器保存的正文不可能被这条路径删掉**。 |
| `cachedSongDisplayName()` / `isCompleteCacheSongName()` / `CACHE_SONG_NAME_PLACEHOLDERS` | L27–59 | 纯扩展函数，不改模型；细节见 §2.5 / §4。 |
| `usageBytesLocked()` 抽取 | L488–511 | `sizeBytes()` 的纯重构，语义不变。 |
| `touch()` 比较改成 `latest?.copy(displayName = entry.displayName) != entry` | L731–748 | 只忽略 displayName 差异 → **名字刚被回填时 LRU 仍能更新**（否则回填会把 LRU 冻住、进而影响 `trimSourceEntries` 的淘汰顺序）。对我们有利。 |

**问题 2 的正面回答：我们刚修的「编辑器保存落 manual index」仍然成立**，而且是三层保证叠加：
1. `EmbeddedRuntimeSettingsController.saveLyrics(MultiIdDraft)` → `content.saveManualLyrics(...)` →
   `draft.copy(source = MANUAL, userEdited = true)`（9a58a9e4；这两个文件上游完全没碰，合并后原样）；
2. `EmbeddedContentManager.saveLyrics` 的分流（`isThirdPartySource(canonicalSource(draft.source))` → sourceCache，
   否则走 manual 事务）未被上游修改；
3. `commitManualLyrics(next)` 仍先过 `sourceCache.preserveUnmigratedSources(next)`，provider 残留行不会因为一次
   编辑器保存被丢掉。

唯一需要盯的语义交叉（**风险 R2**）：`updateCacheSongNames` / `deleteCachedLyrics` 内部都会调 `prepareSource()`，
而 `prepare()` 的正事是「发布 source index → 再把 provider 行从 manual index 移除」。这条路径现在可能由**缓存页/回填**
在后台线程触发（以前只有装配期 `refreshExecutor.execute { sourceStore.prepare() }` 和用户操作会触发）。
它按 `preserveUnmigratedSources` 的反向顺序执行（先发 source index，后删 manual 行，任一步失败就整体回退），
逻辑上是安全的；但要真机确认「已配置 N」不会因为打开缓存页而抖一下。

"salvage"：仓库里没有叫 `salvage` 的符号（`grep -rni salvage` 空）。我按两条路径核对过，都不受上游影响：
① `CustomLyricsIndexRepository` 的 `allowRecovery` 恢复提交（`EmbeddedContentManager.commitManualLyrics(allowRecovery=true)`）；
② `retireUnreferenced` + `pendingDeletes` 的"删不掉就记账、下次再删"的正文回收。见 §6 拍板 P0。

### 2.2 session 的 `refreshSong` / `AutoLyricsCache` 写回：上游顺手修了我们的一个交叉 bug（要保留）

- 原来（我们）：`sourceStore.entryFor(id, source) != previous` → 放弃刷新。
  一旦名字被回填（displayName 变了），这条判断会把"仅名字变了"误判成"基线变了"，**「刷新缓存歌词」从此失效**。
- 上游：先重读 `latest`，再比 `latest.copy(displayName = previous.displayName) != previous`，写入时用
  `candidate.withSongTitle(appleMusicId, latest.displayName)` 保留已回填的名字。
  ⇒ 名字回填与手动刷新从此互不干扰。**这版必须留**（注释也写明了「A display-name backfill does not invalidate a refresh」）。
- `cache.writeCandidateIfCurrent` 现在写 `candidate.withSongTitle(appleMusicId)`：候选落盘时就把「歌名 - 歌手」固化，
  失败时退回原名（`withSongTitle` 的 `resolvedName == null` 分支返回 `this`）。

### 2.3 `lookupCacheSongNames` 绕开了我们的自建镜像与磁盘索引（**回归 · 已拍板 P3：本次合并一起修** · 风险 R4）

合并后 L337：

```kotlin
val index = runCatching { AmLyricsClient(indexTransport).fetchIndex() }.getOrNull()
```

问题三条：
1. `AmLyricsClient(indexTransport)` 没有 `customRoot` → **跳过 `customSourceBaseUrl` 自建镜像**（我们 1.6.9 做的核心优化）；
2. 没传 `indexProvider` → `fetchIndex()` 是**真网络请求**，绕开 `FileAmLyricsIndexCache` 的 12h 磁盘副本；
   每台设备冷启第一次打开缓存页都要重新下 ~55 KB；
3. GitHub raw 在部分地区不可达 ⇒ 回填永远失败，缓存页长期停在「未知歌曲 · ID」。

**落地方案（已拍板：不 new 裸 client，与 `sources.loadAmLyricsIndex` 同一策略）**

替换合并结果 L337 这一行：

```diff
-                val index = runCatching { AmLyricsClient(indexTransport).fetchIndex() }.getOrNull()
+                // 先吃 12h 磁盘副本（冷缓存返回 null 并顺带排一次后台刷新），再走 amLyrics —— 它带
+                // customRoot = customSourceBaseUrl，所以自建镜像也在这条路上；裸 AmLyricsClient(indexTransport)
+                // 会同时绕开磁盘副本和自建镜像。
+                val index = runCatching { amLyricsIndexCache.index() ?: amLyrics.fetchIndex() }.getOrNull()
```

落地时必须确认的四个点（都核对过）：
1. `amLyricsIndexCache`（L223）与 `amLyrics`（`AmLyricsClient(lyricTransport, customSourceBaseUrl, amLyricsIndexCache)`，L242）
   都在 `lookupCacheSongNames` 之前声明，作用域没问题。
2. `FileAmLyricsIndexCache.index()`（L76–83）**冷缓存返回 null 并 `scheduleRefreshLocked()`** ⇒ 首次仍会退到网络，
   与现状等价（只是这次带了镜像），不会因为改这行而"永远读不到索引"。
3. `amLyrics.fetchIndex()` 走的是 `lyricTransport`（`maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES = 512 KiB`），
   AM-Lyrics 索引 ~55 KB 远小于上限；`LunabeatClient.INDEX_MAX_BYTES` / `FileAmLyricsIndexCache.MAX_INDEX_BYTES` 都是 2 MiB，
   也不会截断。
4. 结构性测试不会因此变红：`AutoLyricsIndexCacheWiringTest` 断言的是
   `AmLyricsClient(lyricTransport, customSourceBaseUrl, amLyricsIndexCache)` 这一行**存在**，改的是`lookupCacheSongNames` 里的另一处调用。

（这条属"语义修复"，不改变冲突判定：冲突仍只有 §1 那 1 处，修完只是让回填路径重新吃到自建镜像与磁盘索引。）

### 2.4 `EmbeddedLyricsPlaybackRuntime.kt`（+78，全是上游；我方零改动）

新增 `cacheSnapshot()` / `llmCacheUsageBytes()` / `clearLlmCache()` / `backfillCacheSongName()` /
`refreshCacheSongNames()` / `deleteCachedLyrics()`，以及构造参数 `lookupCacheSongNames` + `llmCache`（都是必填）。

对我们功能的影响：
- **构造点唯一**（session L713–726，已带 `lookupCacheSongNames = ::lookupCacheSongNames`、`llmCache = llmCache`）⇒ 没有别的实例化点要改。
- `deleteCachedLyrics` 复用我们已有的 `clearingCache` 门（L181/209/249/527/562），删除期间 `prepare`/`selectSource`/`setOffset` 会被拒一次；
  删完把 `prepared[choice.source] = PreparedChoice(choice, null)` 保住当前显示的文档，再 `notifyChanged(wake = true)` 重算。
- `backfillCacheSongName` 走 `execute { }`（`ampp-lyrics-controls` 单线程）→ **不占主线程**；`writeCacheSongNames` 还额外套了 `commitLock`。
- `cacheSnapshot`/`llmCacheUsageBytes` 是同步磁盘操作，**只允许在工作线程调**（UI 已如此：`LyricsCachePageState` 的 worker、
  LLM 卡片的 `Dispatchers.IO`）。

### 2.5 `LyricsPlaybackRuntimeRegistry.kt`（+10，全是上游）

`active: LyricsPlaybackRuntime?` → `active: EmbeddedLyricsPlaybackRuntime?`，`controls()` 随之收窄；`attach`/`detach` 收窄。

- 编译面：全仓只有 `EmbeddedLyricsPlaybackRuntime` 一个实现（`grep ': LyricsPlaybackRuntime\b'` 只有它），
  `controls()` 的消费者（`EmbeddedLyricsMenu` 的 `LyricsPlaybackControls?` 形参、`EmbeddedLlmSettingsPage`、
  新缓存页）都吃子类型，安全；`AutoLyricsRuntime.playback`（core）仍声明为接口 `LyricsPlaybackRuntime?`，不受影响。
- 语义面：这是"接口从注册表里消失"。将来若想在 host 侧包一层装饰器（比如给读音 seam 加中间层）就必须改注册表类型。
  目前不需要，记一笔即可（风险 R6，低）。

### 2.6 LLM 缓存清理（`f68fcc7e`）：`LlmLyricsCache` / `LlmLyricsReplacementSession` 都是纯叠加（我方零提交）

- `FileLlmLyricsCache`：`@Volatile generation`、`generation()`、`usageBytes()`、`clear()`；
  `write(..., expectedGeneration: Long = generation)` 进锁后先比 generation。
- `LlmLyricsRequest` 多一个字段 `cacheGeneration`（放在 `config` 之后、`title` 之前），
  `LlmLyricsRuntime` 多一个 `cacheGeneration: () -> Long`（默认 `{ 0L }`），session 构造请求时取一次；
  我们 fork 的 `onPrepared`/`onTransliterationPrepared` 已自动合并成 `expectedGeneration = request.cacheGeneration`。
- `LlmLyricsReplacementSession` 只改了 4 行（构造 `LlmLyricsRequest` 那一行），我们的翻译/音译流程其余部分不在 diff 里。
- `EmbeddedLlmSettingsPage` 新增 `LlmLyricsCacheCard`（`BasicComponent` + `TextButton` + 确认弹窗），
  用法与我们的设置页风格一致；`ModuleText` 新增 38 条 `LYRICS_CACHE_*`（`grep` 查过：无重名枚举，不会编译冲突）。

---

## 3. 问题 3 专项：LLM 清缓存与我们的三路会话（翻译 / 音译 / 边界）

**接线**
```
EmbeddedLlmSettingsPage.LlmLyricsCacheCard
  → LyricsPlaybackRuntimeRegistry.controls()            (具体类，上游收窄后才能调这两个)
  → playback.llmCacheUsageBytes() / playback.clearLlmCache()   [Dispatchers.IO]
  → FileLlmLyricsCache.usageBytes() / clear() = generation++ + directory.deleteRecursively()
  → 目录只有 filesDir/ampp-llm-lyrics
```

**会不会顺带清掉 `ampp-llm-boundary`？——不会，也不需要"保护"。**
- `FilePronunciationBoundaryCache`（core/.../lyrics/PronunciationBoundaryCache.kt）是另一个类、另一个目录
  （session L477–481 明文写 `File(application.filesDir, "ampp-llm-boundary")`），`LlmLyricsCache.clear()` 触不到它。
- 语义上也不该保护成"一起清"：边界 plan 的 identity 是 `(appleMusicId, kanaHash, tokenCounts, config)`，
  只跟"Apple 给的那份日文假名"和 LLM 配置有关，跟翻译结果无关；清翻译不会让已存的 plan 变错，重算反而要再花一次 LLM 请求。
- 唯一没有覆盖的是**在途请求**：清缓存那一刻正在跑的边界请求会把 plan 写进 `ampp-llm-boundary`（它没有 generation 概念）。
  这不是 bug（写入的是仍然有效的 plan），但如果你要"清一次就干净"，得给 `PronunciationBoundaryCache` 加 generation（见拍板 P2）。

**翻译 / 音译怎么不被"清缓存竞态"污染**
- 请求创建时冻结 `cacheGeneration`；落盘时 generation 不等就丢弃 ⇒ 清缓存之前发出的请求，结果不会在清完之后写回。
  这条链在合并结果里已经完整（session L662–666、L698–703、L706），**别再退回成不带 expectedGeneration 的旧写法**。
- `read()` 不看 generation：清完目录空了自然读不到，行为正确。
- `clear()` 与 `write()` 共用同一把 `lock` ⇒ generation 递增与目录删除、写入之间是串行的，没有"删完又被写回来"的窗口。

**口径问题（低风险，非 bug）**：LLM 卡片只统计 `ampp-llm-lyrics`。用户的 LLM 磁盘占用其实还包括
`ampp-llm-boundary`、`ampp-amlyrics`、`ampp-lyrics-presence`（后两个是索引，不是 LLM 产物，合理）。
「歌词缓存」页与「LLM」页两个数字口径不同，建议文案上明确「LLM 生成的翻译/音译」而不是笼统的"缓存"。
**已拍板 P2：本次合并不改文案**（只记录为遗留项 T2），`clear()` 的范围保持只删 `ampp-llm-lyrics`。

---

## 4. 问题 4 专项：缓存歌曲名回填 / ID 显示 与模型兼容性

**模型没有变**：`CustomLyricsEntry(appleMusicId, displayName, fileId, sizeBytes, sha256, source, enabled, autoManaged, userEdited)`
与 `AutoLyricsCandidate(source, ttml, displayName)` 字段一个都没增删（`ModuleModels.kt` 的 +2 是玻璃自适应前景，与歌词无关）。
上游只在 `displayName` 的**语义**上加了两条规则：
- `cachedSongDisplayName()`：等于 source 名、等于纯 ID、命中 `CACHE_SONG_NAME_PLACEHOLDERS`（"AMLL"/"LunaBeat"/"AM-Lyrics 自定义歌词"…）、
  或 "auto-cached lyrics · <id>" 的中英两种写法，一律当"没有名字"；
- `isCompleteCacheSongName()`：必须含 `" - "` 且后半段非空（"歌名 - 歌手"）。

**兼容性结论：兼容，但有三处行为变化要知道**

1. **老数据会短暂"退回 ID"**：我们 fork 的自动缓存历史行很多是 `displayName == source`（`writeCandidates` 用
   `sanitizeDisplayName(candidate.displayName ?: source)`）。这些行现在会被判成"没有名字"，缓存页显示
   `未知歌曲 · ID <appleMusicId>`，直到回填把它们写成「歌名 - 歌手」。→ 预期行为，但用户观感是"名字丢了又回来"。
2. **只影响第三方索引，不影响用户的亲手条目**：回填写 `sourceManifest`，`updateCacheSongNames` 从不碰 manual index；
   「歌词管理」列表（`EmbeddedLyricsSettingsPages` 用 `entry.displayName.ifBlank { id }`）里用户自己保存的条目名字不会被自动改写。
3. **幂等，不会造成索引 churn**：`withSongTitle` 的优先级是 own(完整) > previous(完整) > 当前曲 > own > previous，
   第二次抓同一首会稳定复用已回填名字；`writeCandidates` 里 `it.displayName == name && sha256 相同` 的快路径再兜一层。
   附带：`lookupCacheSongNames` 会遍历 AM-Lyrics 索引的 `alternateIds`，同一份正文的多个 Apple ID 都能拿到名字。

**ID 显示**：`ModuleText.LYRICS_CACHE_UNKNOWN_SONG("未知歌曲 · ID %1$s")`，数据来自 `CustomLyricsEntry.appleMusicId`，
和我们 `CustomLyricsIdParser` 那套 ID 语义一致（都是正 Long），没有互换风险。

---

## 5. 风险等级

| # | 风险 | 等级 | 说明 |
|---|---|---|---|
| R1 | 签名冲突解错 / assembly 调用点漏传 `currentSong` | **高** | 编不过（`No value passed for parameter 'currentSong'`）。必须与本文件的冲突**同一次**解掉。 |
| R2 | `updateCacheSongNames` / `deleteCachedLyrics` 在后台首次触发完整 `prepareSource()`（迁移 + 写两份索引） | 中 | 装配期 `refreshExecutor.execute { sourceStore.prepare() }` 通常已跑完，所以绝大多数情况是空操作；但"启动瞬间打开缓存页"可能叠加一次索引写。需要真机看「已配置」计数与列表是否闪。 |
| R3 | `currentSong.addListener` 会**同步**用当前值回调一次 → 启动即入队一次 `backfillCacheSongName` | 中 | 走 `ampp-lyrics-controls` 单线程，不卡主线程；但若 store 尚未 prepare，`updateCacheSongNames` 会先试 `prepareSource()` 再返回 false（fail-closed）。 |
| R4 | `lookupCacheSongNames` 用裸 `AmLyricsClient(indexTransport)`：绕开自建镜像 + 绕开磁盘索引 | 中 | 每台设备冷启多下一次 ~55 KB；GitHub raw 不可达地区回填永远失败 → 缓存页长期显示 ID。**已拍板 P3：本次合并一起修**（§2.3 给了逐行 diff），修完后此风险降为低，剩余验证见 §6 第 6 条。 |
| R5 | `deleteCachedLyrics` 刻意不动 `preferred` / `offset` 元数据 | 低 | **已拍板 P4：保持上游行为**，本次合并不加清理；死条目遗留到后续「缓存页清理」单独处理（见 §7 待办 T1）。 |
| R6 | `LyricsPlaybackRuntimeRegistry` 类型收窄成具体类（接口从注册表消失） | 低 | 目前全仓唯一实现，编译安全；将来想在 host 侧包装饰器就得改注册表。 |
| R7 | 删除期间 `clearingCache=true` 会短暂拒绝一次源切换/偏移设置 | 低 | 已有机制，属可接受的一次点击丢失。 |
| R8 | 老缓存行名字"退回 ID → 再回填"的观感 | 低 | **已拍板 P5：接受，不加文案**。仅作为更新说明里的可选提及，不写代码。 |

---

## 6. 合并后必须跑的验证

**能自动跑的（本机 1.8 GiB 跑不了 Gradle，全走 CI）**
1. 编译 + 单测：`bin/ampp-ci.sh build --branch merge/main-1.7.1 --base integ/all-features`。
2. 结构性回归（**直接把我这份源码当字符串断言**，合并后必须绿）：
   - `app/src/test/.../LyricPresenceWiringTest.kt`：`LYRIC_PRESENCE_CACHE_DIRECTORY`、`customRoot = customSourceBaseUrl`、
     `presence = lyricPresenceCache`、`presence=${lyricPresenceCache.enabled}`、`amLyricsIndexScheduler.shutdownNow()`；
   - `app/src/test/.../AutoLyricsIndexCacheWiringTest.kt`：`AmllTtmlClient(lyricTransport, customSourceBaseUrl)`、
     `AmLyricsClient(lyricTransport, customSourceBaseUrl, amLyricsIndexCache)`、`AM_LYRICS_CACHE_DIRECTORY = "ampp-amlyrics"`；
   - `app/src/test/.../OnlineLyricsSupplementWiringTest.kt`：`trailing = onlineSources`、`val enricher = chain.composite`、
     `hideMandarinPinyin = hideMandarinPronunciation`、`translationEnricher = enricher`、
     `online-translation runtime supplement=`（还断言 assembly 里的 `currentTrack = { currentSong.current()?.details }`）；
   - `AutoLyricsRuntimeStructuralRegressionTest`（`ArrayBlockingQueue(1)` + `DiscardOldestPolicy()`）、
     `AppleMusic700NativeLyricsProfileTest`（`nativeLyricOverlay = nativeLyricOverlay`、`overlay?.update(`…）。
   > 这五条是"合并把我们的 seam 改坏"的最灵敏探针——它们读的不是行为而是源码文本。
3. 单元：`LlmLyricsCacheTest`（新增 generation/clear）、`LlmLyricsReplacementSessionTest`（`cacheGeneration` 透传）、
   `CustomLyricsIndexRepositoryTest` / `CustomLyricsManifestPolicyTest` / `CustomLyricsClearTransactionTest`（清空时 manual 修剪）、
   `AutoLyricsRefreshSessionTest`、`CustomLyricsUpdateCoordinatorTest`（`userEdited` 不被自动更新覆盖）。
4. `python3 scripts/verify-architecture.py`（AGENTS.md 提交前要求）。

**真机（7.0.3/1626 与 7.0.2/1624 至少各一台）**
1. 「歌词缓存」页：列出第三方缓存；老行的 `未知歌曲 · ID x` 在 1–2 秒内回填成「歌名 - 歌手」（开自建镜像时更明显）。
2. 单来源删除 / 单曲删除 / 全部清除 → 再播该曲会重新抓取；期间 `logcat` 无 `AMPP-Bndry ... trigger failed`。
3. LLM 页缓存卡片：显示大小 → 清除 → `filesDir/ampp-llm-lyrics` 空、`filesDir/ampp-llm-boundary` **仍在**；
   随后播放一首日文歌，边界分句应命中缓存（日志没有新的 `bndry transport endpoint=`）。
4. 编辑器保存回归：编辑→保存 → 「已配置」+1、列表出现 `source=manual` 条目；再点「刷新缓存歌词」不得覆盖（`userEdited`）。
5. 读音边界/居中：`logcat -s AMPP-Bndry` 应看到 `bndry path=enricher entered` / `bndry path=llm entered` /
   `bndry trigger path=... ttml=N`，分句仍居中（`reapplyObservedRows` 有被调用）。
6. R4 验证（**P3 修完后变成必过项**）：禁用 GitHub raw、只留自建镜像，缓存页回填仍应成功；
   同时抓一次 `logcat` 确认冷启第一次打开缓存页只下 ~55 KB 索引**一次**（磁盘副本生效），不是每次进页都下。

---

## 7. 决策记录（父 agent 2026-11-24 已拍板，无需再评估）

| 编号 | 问题 | 决定 | 对本文件/合并的落地动作 |
|---|---|---|---|
| P0 | 「salvage」指什么 | 就是 §2.1 核对的两条恢复路径（`allowRecovery` 索引恢复 + `pendingDeletes` 正文回收），仓库里没有同名符号，不必再查 | 无动作。§2.1 那两段结论即为最终评估。 |
| P1 | `currentSong` 放在形参表哪个位置 | 未单独裁决；按 §1 的建议落（插在上游位置 `lunabeatCatalogCache` 之后，无默认值） | 与 assembly 的 `currentSong = currentSong,` **同一次**落（§1 连带点）。 |
| P2 | 清 LLM 缓存是否连 `ampp-llm-boundary` | **不清**。plan 的 identity 与翻译结果无关，重算要再花一次 LLM 请求 | 保持 `LlmLyricsCache.clear()` 现状（只删 `ampp-llm-lyrics`）。接受的副作用：清缓存瞬间在途的边界请求仍会把 plan 写进 `ampp-llm-boundary`（plan 仍有效，非 bug）。用量口径不统一一事记为文案问题，本次不改。 |
| P3 | `lookupCacheSongNames` 的两行修复何时做 | **现在做**，合并时一起落，不单开 PR；走 `amLyricsIndexCache.index() ?: amLyrics.fetchIndex()`，不要 new 裸 client | 按 §2.3 的 diff 替换 L337；合并实现时照抄，别回退成 `AmLyricsClient(indexTransport)`。 |
| P4 | `deleteCachedLyrics` 是否清 `preferredSource`/`offsetMs` | **保持上游行为**，不清；死条目后续用「缓存页清理」单独处理 | 本次合并**不加**任何清理代码（不要顺手写进 `deleteCachedLyrics` 或 playback 的删除路径）。 |
| P5 | 老缓存名字"退回 ID → 回填"的过渡 | **接受**，不额外加文案 | 缓存页/文案保持上游原样，不加一次性说明或迁移。 |

**留给后续（不阻塞本次合并）**
- **T1**：`deleteCachedLyrics` 之后 `preferredSource` 可能指向已删除的 source（R5），后续在「缓存页清理」里一并处理。
- **T2**：「歌词缓存」页与「LLM」页的磁盘用量口径不同（`ampp-llm-lyrics` vs 第三方歌词缓存），若要做"统一占用"视图再单独评估。
- **T3**：`FilePronunciationBoundaryCache` 没有 generation 概念；P2 决定了不需要，但若将来要"一键全清 LLM 产物"再补 `clear()/generation()`。

**合并实现时的动作清单（照抄即可）**
1. `AutoLyricsReplacementSession.kt` L171–187：按 §1 的形参表解冲突（上游 `currentSong` + 我们全部默认参数，`configuration` 仍在最后）。
2. `AppleMusicAssembly.kt`：`createAutoLyricsRuntime(...)` 实参块补 `currentSong = currentSong,`（与 assembly 自身冲突同一次解）。
3. `AutoLyricsReplacementSession.kt` L337：按 §2.3 的 diff 换成 `amLyricsIndexCache.index() ?: amLyrics.fetchIndex()`。
4. 其余（store / playback / registry / LLM cache / 新缓存页）**保持合并结果不动**；特别是 `refreshSong` 的 `latest.copy(displayName = previous.displayName)` 与 LLM 写盘的 `expectedGeneration` 不要退回旧写法。
5. 跑 §6 的 CI + 结构性回归 + 真机清单。
