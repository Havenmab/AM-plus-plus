# 歌词源 / 歌词链路 交接文档（2026-10-28）

> 范围：AM++ 的「歌词源」面板、歌词行过滤、车道（歌词/翻译/音译）来源与持久化、自动补全对接。
> 不含：已被放弃的 LyricsBlossom 播放器移植线（**不在本文档范围**）。

## 1. 分支与合并状态

| 分支 | 内容 | 状态 |
|---|---|---|
| `feat/lyrics-search-ux` | 搜索并发 + 跨源去重 + 最近使用置顶/快捷行 | **已合并进 `integ/all-features`** |
| `feat/lyric-line-filter` | 歌词无效行过滤（署名/版权/占位/头行/重复） | 已合并进下方修复分支 |
| `fix/lyrics-overlay-source-display` | 车道分类器、overlay 持久化、来源显示、面板入口、补全对接（本轮主线） | **未合并，含大量未验证改动** |
| `perf/glass-texture-blur` | 玻璃性能实验 | 已删除，未采用 |

## 2. 已交付功能（在 mainline 或待合并分支）

### 2.1 歌词源面板（`feat/lyrics-search-ux`，已合并）
- 入口：原生「歌词更多/译文」按钮始终可见；有原生车道→走原生弹层并追加「歌词源…」，无车道→直接打开面板。
- 搜索：Lunabeat/AMLL/AM-Lyrics/QQ/网易云/酷我/酷狗 **四路并发**，2.6s 上限；跨源同曲去重；最近使用置顶 + 快捷胶囊。
- 面板：当前曲卡；三条车道（歌词/翻译/音译）显示来源/精度/更换/不显示；3 行预览；恢复 Apple 原生。
- 持久化：`ampp-lyrics-source/recents.json`（最近使用）+ `selection.json`（按曲车道选择）。

### 2.2 过滤（`LyricLineFilter`，core）
- 署名行：36 键词表（含无冒号写法、英文 `Lyrics by/Composed by/…`、**日文/繁体：作詞/編曲/製作/訳詞**），行长 ≤60，任何位置都删。
- 平台/版权：`©/℗/Copyright/All rights reserved/Provided to YouTube by/QQ音乐/TME/网易云/酷狗/酷我/歌词来自/未经许可/纯音乐/暂无歌词/此歌曲由…制作/扫码`，任意位置。
- 头行：`歌名 - 歌手 (英文名)`（有身份按身份，无身份按首行 ≤3s + A - B 形态）。
- 开头垃圾区：**首行即垃圾时**，清扫到开头窗口（前 20 行/60s）内最后一条垃圾行为止。
- 占位/重复：无字母行、`//`、相邻 3s 相同行。

### 2.3 车道语义（`fix/…` 分支，未合并）
- `LyricLaneClassifier`：假名/罗马音/中文/混合判定 + `rebalance`；QQ 日语歌 `trans`（`[kana:…]`+罗马音）移入音译车道；译文车道只收中文。
- 应用点：`OnlineTranslationExtraction`、`OnlineTranslationEnrichment`、`AutoLyricsReplacementSession.publishLanes`、overlay 持久化读写。
- `documentFor` 的语种分类**已降级为 advisory**：分类失败不再拒绝整篇（只丢辅助车道），只有 0 行/全署名才失败（`stage=filter` + 中文原因）。

### 2.4 来源与持久化（`fix/…` 分支，未合并）
- `LyricsSourceOverlayPersistence` 为唯一事实来源：`translations[]`/`romanizations[]` 两组车道 + `translationSource/pronunciationSource/originalSource/pronunciationLanguages`；`overlay.json` 原子写，单曲 512KB/64 曲/4MB。
- 自动补全与手动 apply 都写穿（`LyricsSourceOverlayCacheBridge.save/rememberDisplayed/replay`）。
- `NativeLyricOverlayStore.update` 改为**车道级保留**（只带译文的 refresh 不再清掉发音车道；空 provider 不覆盖；`pronunciationLanguages` 保留）。
- probes 读「内存 store ∪ 盘上记录」；发音车道缺少时按 `(song, revision)` 从盘重放 → 「显示发音」长期可点（`hasPronunciationAvailability = original || official`）。
- 面板车道来源用 `LyricLaneState.displayed`（页面实际显示来源），Apple 文案统一「Apple Music（原生）」；Apple 不重复品牌徽标。

### 2.5 自动补全对接
- 有轴 Apple 文档：只补 overlay 车道（译文/音译），**不整篇替换**（避免出现「自定义文件」）。
- 无轴/无文档：resolver 优先，再 enrichment。
- 面板显示：歌词/音译 = Apple 原生（overlay 无对应车道时），翻译 = 实际 provider。

## 3. 关键文件

core：
- `lyrics/online/LyricLaneClassifier.kt`、`LyricLineFilter.kt`、`LyricMatchPolicy.kt`（`artistIdentities` 括注别名）、`NativeLyricOverlayStore.kt`（Lane/updateLane/dropLane/车道级 update）、`OnlineTranslationExtraction.kt`、`OnlineTranslationEnrichment.kt`、`AppleLyricTtmlWriter.kt`、`QrcParser.kt`（QRC→LRC 兜底）、`NeSource.kt`（YRC→LRC 兜底）。
- 契约：`lyrics/source/LyricsSourceContract.kt`（`LyricLaneState.displayed`、`LyricSourceOption.hasTiming/precisionKnown`）、`LyricSourceCatalog.kt`、`LyricSourceSelectionStore.kt`、`LyricSourceText.kt`、`LyricSourceDiagnostics.kt`、`AmllCatalogIndex.kt`、`LyricsSourceRecents.kt`。

host-applemusic：
- `hook/lyricsource/`：`LyricsSourceMenuTarget`（双路径插项 + 按钮面）、`LyricsSourceButtonSurface`、`LyricsSourceControllerImpl`、`LyricsSourceSearch`（并发/预览 stage）、`LyricsSourceAmllProvider`、`LyricsSourceAmLyricsProvider`、`LyricsSourceCorpusMirror/Fetch/Downloader`、`LyricsSourceOverlayPersistence`、`LyricsSourceProbes`、`LyricsSourceOverlayLanes`、`LyricsSourceRecentsStore`、`LyricsSourceWiring`。
- 其它：`AppleMusicCustomLyricsTarget`（有轴不替换）、`AppleNativeLyricModelHooks`（发音车道重放）、`AppleMusicNativeLyricsTarget`（换曲只清上一首）。

app：
- `hook/AutoLyricsReplacementSession.kt`（`lanePureLines`/`publishLanes`/写穿）、`ui/source/*`（面板 View 实现，8 文件）、`ui/EmbeddedSettingsHost.kt`（入口注册）、`settings/LyricsSourceIndexWriter.kt`（自定义歌词索引写入，displayName=歌名）。

## 4. 测试与 CI

- core jvmTest：累计 1000+ 条（含 filter 24、classifier 14+、match 8+、overlay store 12+、catalog/policy）。
- host/app：结构测试（菜单契约/安全线/持久化/快照/预览），`LyricsSourceHostStructureTest` 41+ 条、`LyricsSourceSnapshotTest`、`LyricsSourceSearchDocumentTest`、`LyricsSourceOverlayPersistenceTest`。
- 闸门：`verify-architecture.py`、`verify-profile-data.py`、`verify-glass-reference.py`、`amll_to_apple_ttml.py --selftest`。
- CI：公开仓库走「临时分支→PR→出包→关 PR」；构建命令见 `bin/ampp-ci.sh`。

## 5. 已知问题 / 下一步

1. `AppleLyricTtmlWriter.from` 与 `extract` 重复过滤，极端情况可能按下标错位 —— 建议改为按 `startTimeMs` 对齐。
2. provider 罗马音仍不发布（Apple-only 音译策略），「只有 QQ 有罗马音」的日语歌发音车道仍不可用；放开需改 injector + 策略 + 设置项。
3. `fix/lyrics-overlay-source-display` 分支尚未合并，且其上部分改动未经真机验证（最近一次 CI 通过的是 b1fc7d8 之后加了一个 lane 降级的提交，正在验证）。
4. 缺少「真机自动化回归」：目前依赖用户导出 LSPosed 日志；建议后续把 `lyrics-source` 日志做成本地导出按钮（模块内）。
5. 歌词源面板的「歌词/翻译/音译」卡在降级快照下会显示「不显示」，建议 UI 区分「未知」与「不显示」。

## 6. 使用方式速查

- 面板入口：歌词页「⋯」/「译文」按钮 →「歌词源…」；无车道时点该按钮直接打开。
- 预览/应用链路：`LyricsSourceSearch.documentFor` → `LyricLineFilter.filter` → `OnlineTranslationExtraction.extract` → `AppleLyricTtmlWriter.from`；失败带 `stage=fetch|filter|classify|write`。
- 持久化：`<filesDir>/ampp-lyrics-source/{selection.json,recents.json,overlay.json,amll/,am-lyrics/}`。
- 日志关键字：`lyrics-source snapshot|apply|document|overlay|recents`、`online-translation native-write`、`trace=type=…`。

> 维护约定：所有「内部异常」只在日志出现（`trace=`），UI 只显示中文可读原因；新增过滤规则必须带真实样本单测；涉及 overlay 车道的改动必须同时更新持久化读写两侧。

## 7. 2026-10-28 追加：一条关键真机结论

- 真机日志出现 `lyrics-source document stage=classify type=java.lang.NoClassDefFoundError message=…LyricLaneClassifier`：
  **安装包 dex 里没有 `LyricLaneClassifier` 这个类**（同进程的 `OnlineTranslationExtraction` 能加载，只缺该类），
  于是所有来源的预览都走到 `failed(Stage.CLASSIFY, "取回的歌词语种无法识别")`，与语种无关。
- 处理：`documentFor` / `translationOutcome` 的 classify 闸全部降级（分类失败=无辅助车道，不拒绝整篇），
  唯一拒绝条件改为 `hasLyricLine(clean)==false`（0 行/全占位/全署名）；并在日志里保留 `stage=classify` 以便区分“缺类”与“真没歌词”。
- **发布前必须 clean rebuild 并确认 APK 内含 `LyricLaneClassifier`**（`unzip -l` 或 `dexdump` grep），
  否则 `OnlineTranslationEnrichment.enrich` 与 `AutoLyricsReplacementSession.lanePureLines` 里对它的直接调用仍会抛。
- 该修复目前在 `fix/lyrics-overlay-source-display` 工作树（`worktrees/integ-merge`），尚未合并进 mainline。

## 8. 用户反馈问题与日志证据（按时间顺序）

日志均为用户从 LSPosed 导出的 zip（路径为本机附件缓存，换机后请让用户重新导出）��hook 行在 `log/modules_*.log`；`full.log` 是系统日志，无 hook 行。

| # | 用户反馈 | 诊断 / 根因 | 处理与状态 | 日志 |
|---|---|---|---|---|
| 1 | 「三个点菜单里没有歌词源选项，按钮也没有始终显示」 | 播放器「⋯」是**轨道操作菜单**（adapter 列表），我们跳过 adapter 菜单；该歌 Apple 只有静态歌词时，「更多」按钮本身不存在 | 加「原生按钮强制可见」+ 无车道时接管点击直达面板；删除浮动胶囊与长按入口（用户否决） | `…/58/58aadb62…/LSPosed_20261026_090338.zip` |
| 2 | 「一直搜不到 AMLL 源」 | `raw.githubusercontent.com` 设备不可达 + 共享 transport 512KB 上限/gzip 不解压 | AMLL 专用 fetcher（gzip/15s/4MB）+ 三镜像回退（github→jsDelivr→ghfast）+ 24h 磁盘缓存 | `…/67/67ff0b5e…/LSPosed_20261026_115806.zip` |
| 3 | 「搜索歌词后选择任意一项根本不生效」 | 自定义歌词索引对已有条目拒绝二次写入；bridge 只回 Boolean，UI 只能显示「写入失败」 | writer 先查 `lyricsEntries()` 再用 `replacingAppleMusicId` 覆盖；bridge 返回失败原因并透传到 UI | `…/01/01fcdd8a…/LSPosed_20261026_142740.zip` |
| 4 | 「恢复到 Apple 原始歌词没用；选择完必须重启生效」 | restore 只清选择/overlay，没删索引条目；apply 只写索引、未发布到原生模型 | apply 走 `reapplyCustomLyrics`（`refreshEntry`→`publishReplacement`）；restore 删条目 + `session.start()` + 三次唤醒 | `…/c4/c46f28d7…/LSPosed_20261026_150421.zip` |
| 5 | 「删除后歌词管理里显示平台名而不是歌名」 | writer 把 `providerLabel` 当 `displayName` | 写入用歌名（`歌名 - 歌手` 兜底），旧条目打开面板时自动 `rename` | 截图（10-26 20:18） |
| 6 | 「自动补全的来源显示成『已写入歌词』；恢复 Apple 后又被覆盖」 | overlay 未记录 `originalSource`；restore 是清空而非「用户偏好」 | overlay 记录来源链；restore 写 `apple` 偏好键并计入 `isUserLocked`，自动链不再覆盖 | 截图（10-26 21:28） |
| 7 | 「Fly to the moon 明明是静态歌词，被当成逐行」 | 模型只有 `hasWords`，无时间轴维度 | 新增 `hasTiming`（option/lane/preview），无时间轴显示「无时间轴」 | 截图（10-26 21:28） |
| 8 | 「入口必须始终显示；不要浮动胶囊/长按」 | 原生按钮被 Apple 隐藏 → 无入口 | 仅保留「原生按钮强制可见 + 弹层 append」两个入口，删掉浮动/长按 | 截图（10-26 22:xx） |
| 9 | 「选了翻译重启后翻译变成音译；自动补全后没有翻译来源；音译被识别成自定义文件」 | overlay 持久化用单 blob 反推两条车道；`DisplayedLyricPublication` 语义被误用；音译车道被自定义文档顶替 | 持久化 v2 按 `translations[]/romanizations[]` 分车道；`hasRomanization=false` 使音译回落 Apple；补全写穿 provider | `…/3a/3a88d19c…/LSPosed_20261027_152748.zip`、`…/9e/9e3ebd3d…/LSPosed_20261027_160319.zip` |
| 10 | 「自动补全近乎失效；按钮多点几下发音就灰；重启后翻译显示不出来」 | ①艺术家身份通道把括注原名整段删掉 → `artist=0`、总分 <85、`candidates=0`；②hide/restore 调 `overlay.clear` 把两条车道一起清；③`restoreInto` 有任一车道就整体跳过 | `artistIdentities()` 支持异脚本括注别名；store 增加 `Lane/updateLane/dropLane` 单车道操作；restore 按车道补齐；换曲只清上一首；发布无条件写穿 | `…/6d/6dda9d1e…/LSPosed_20261028_102507.zip` |
| 11 | 「面板打不开，显示『当前没有播放的歌曲』；搜索出现类名 `LyricMatchPolicy`」 | `snapshotOf()` 把探测失败与无歌塌缩成 null；`artistIdentities` 在设备上触发 `NoClassDefFoundError`（类初始化/链接）而 UI 直接渲染 `message` | 失败分阶段日志 + 降级快照（有歌不再报无歌）；匹配器改手写括号扫描（去 Regex/UnicodeScript）+ 逐通道兜底；错误文案中文化、异常只进日志 | `…/f4/f474b80f…/LSPosed_20261028_110358.zip`、`…/ad/ad806200…/LSPosed_20261028_123812.zip` |
| 12 | 「自动补全完成但面板翻译仍『不显示』；放大字号后底栏重影；搜到的歌词无法预览」 | 面板只读内存 overlay；搜索用 option.key 反推请求丢掉 extras/元数据；QRC/YRC 解析 0 行无 LRC 兜底 | probes 读 store ∪ 盘；命中缓存按 key 回放完整元数据；解析器三级兜底；`documentFor` 返回 `stage+中文原因` | `…/8d/8d070694…/LSPosed_20261028_143437.zip` |
| 13 | 「所有来源预览都报『取回的歌词语种无法识别』」 | 实为 `NoClassDefFoundError: LyricLaneClassifier`（安装包 dex 缺类）被 classify 闸升级成整篇拒绝 | classify 失败只降级（丢辅助车道），唯一拒绝条件＝没有可用歌词行；发布前需 clean rebuild 校验 dex 含该类 | `…/d4/d4afd554…/LSPosed_20261028_155518.zip` |

### 复现与验证要点（供后续会话）

- 复现面：歌词页「⋯/译文」→「歌词源…」；切换来源后重启 App；自动补全开关打开后播放无译文歌；播放器菜单连点「显示/隐藏发音」。
- 关键日志：`lyrics-source snapshot|apply|document|overlay|recents|diag`、`online-translation native-write|candidate|inject|overlay`、`trace=type=…`。
- 失败排查顺序：`stage=fetch`（网络/鉴权/缺 extras）→ `stage=filter`（全署名/占位）→ `stage=classify`（只影响车道，不再拒绝）→ `stage=write`（TTML 写入）。
