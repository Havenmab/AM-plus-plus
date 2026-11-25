# 05 · host-applemusic + glass（AppleMusicAssembly / GlassNavigation / LiquidBottomTabs）

评估时间：2026-11-24。分支 `merge/main-1.7.1`（从 `integ/all-features` ee6a99f6 开出），合 `origin/main` 8bdf6636（上游 AM++ 1.7.1）。
**只读评估**：没有 add/commit/abort，没有改任何源码，只写本文件。

## 0. 结论速览

| 文件 | 冲突 hunk | 建议 |
| --- | --- | --- |
| `app/.../hook/AppleMusicAssembly.kt` | 1 | 取 HEAD（我们），但若 `createAutoLyricsRuntime` 保留上游 `currentSong` 形参，本侧调用要补 `currentSong = currentSong` |
| `glass/.../GlassNavigation.kt` | 2 | 两个 hunk 都取 HEAD 结构，但把上游新语义（`labelColor`、`onSampleMaterial`、`sampleCompactForeground`）**并进来**；纯取任一侧都会丢东西 |
| `glass/.../LiquidBottomTabs.kt` | 1 | 只冲突 KDoc 头部注释，取两边并集；函数体已自动合并干净（我们的 content-hugging + 上游的 material 取样并存） |

冲突不复杂；真正会伤人的是我负责范围里那两件非冲突的事：**1624/1626 的读音 seam 一个都没 pin**（第 2 节）和 `createAutoLyricsRuntime` 的 `currentSong` 形参必须同时保留两侧（第 3 节）。

---

## 1. 三个冲突逐条

### 1.1 `app/src/main/java/dev/amenhancer/module/hook/AppleMusicAssembly.kt:58-72`

**上游意图**（`git show :3:` = 32 行版本，来自 fcbfb5f9 "Add third-party lyrics cache management"）

```kotlin
val automatic = createAutoLyricsRuntime(
    application,
    content = settingsController.content,
    lunabeatCatalogCache = settingsController.lunabeatCatalogCache,
    currentSong = currentSong,              // ← 1.7.x 新增的必需形参
    configuration = config::settings,
)
return AppleMusicHostFactory.appleMusic(config, application, classLoader, lyricsTypefaceSession, currentSong, automatic,
    onOpenLyricsMenu = { activity -> ... })
```

上游这一版把 `currentSong: CurrentSongIdentityCache` 传进 runtime，为的是第三方歌词缓存的「歌名/歌手回填 + 缺名时显示 ID」（`AutoLyricsReplacementSession.kt:291-313` `withSongTitle`、`:315-350` `lookupCacheSongNames`、`:728-733` `currentSong.addListener { playback.backfillCacheSongName(...) }`）。上游 base（`:1:`，31 行）还没有这个形参，是本条合并带进来的新代码。

**我们的意图**（`git log --oneline origin/main..HEAD -- AppleMusicAssembly.kt`：5ac2766b / f11b5a63 / be6070fb / d1a9ceb3 / fa51dc82 / d1f071ab / fd5aea8f / ca13d67d / 1000a35e）

我们这侧多三块：

1. `automatic` 变成 **可空**，并且被 `settings.customLyricsEnabled && settings.automaticLyricsEnabled` 门住（34-54 行整个参数块：`suppressedIds`、`onlineLyricsSupplementEnabled`、`onlineLyricsTranslationEnabled`、`hideMandarinPinyin`、`genreFor`（HLE 从 `MediaMetadataCache` 取 genre）、`onlineLyricsSelection`、`currentTrack`、`logger`、`displayedTtml`、`nativeLyricOverlay`）。上游那版是**无条件**创建 runtime，等于把「自定义歌词/自动实时补全」两个总开关的门丢掉。
2. 位置参数多传 `timingObservations`（`TtmlTimingObservationRegistry`）与 `nativeLyricOverlay`（`NativeLyricOverlayStore`）。21-25 行的注释说明了为什么必须在 assembly 里创建再传下去：composite 要从同一个 registry 回读「当前显示的文档」来执行 timed-Apple-lyrics 规则，custom-lyrics target 也记录进同一个实例。**这两个对象是共享的，不能由 factory 内部各自 new 一份。**
3. `onOpenLyricsMenu` 用 `automatic?.playback` 空安全（可空之后必需）。

**建议解法**：整块取 HEAD，把 65-72 行（`=======` 到 `>>>>>>>`）整段删掉，只留：

```kotlin
    return AppleMusicHostFactory.appleMusic(
        config,
        application,
        classLoader,
        lyricsTypefaceSession,
        currentSong,
        automatic,
        timingObservations,
        nativeLyricOverlay,
        onOpenLyricsMenu = { activity ->
            val playback = automatic?.playback
            if (playback != null) { showEmbeddedLyricsMenu(...) }
        },
    )
```

为什么：合并后的 `AppleMusicHostFactory.appleMusic`（`host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt:121-131`）签名**已经是我们这侧的顺序**（`config, application, classLoader, lyricsTypefaceSession, currentSong, autoLyricsRuntime, timingObservations, nativeLyricOverlay, onOpenLyricsMenu`），上游 1.7.1 没改这个函数。取上游那 4 行等于：丢掉 timing registry 与 overlay store（native 覆盖层与 timed-Apple 规则静默失效）、丢掉两个总开关的门、并把 `automatic` 从可空改回非空 → 第 74 行 `automatic?.playback` 也要跟着改。**收益为零、损失三处。**

**必须的跨文件补充**：如果 `AutoLyricsReplacementSession.createAutoLyricsRuntime` 的解决方式是「保留上游 `currentSong: CurrentSongIdentityCache` 形参 + 保留我们的可选参数块」（这是唯一能让 734-776 行的函数体编译的解法，因为函数体在 `291-313/315-350/728-733` 直接用 `currentSong`），那么本文件第 30-53 行的调用必须加一行：

```kotlin
        createAutoLyricsRuntime(
            application = application,
            content = settingsController.content,
            lunabeatCatalogCache = settingsController.lunabeatCatalogCache,
            currentSong = currentSong,          // ← 上游形参，补这一行
            suppressedIds = suppressed,
            ...
        )
```

反之，如果那边决定不放 `currentSong`（改成从 `currentTrack` 现推），那么 1.7.1 的「缓存歌名回填 / 缺名显示 ID」在这条线上就是残的 —— 需要明确接受（见第 5 节待拍板项 A）。

### 1.2 `glass/src/main/kotlin/dev/amenhancer/glass/GlassNavigation.kt:239-247`（可见行的标签样式）

**上游意图**（d15b9956 "Adapt liquid glass foreground contrast" + b3ba3b39 + 5ce18718）

上游给玻璃导航加了「自适应前景」：`rememberGlassTextContrast()` 每 250 ms 采样一次面板材质亮度，得到 `sampledForeground: Color?`（黑或白），并用 `labelColor = sampledForeground ?: foreground` 替换原来写死的 `foreground`。因此这条 hunk 里上游把内联样式改成：

```kotlin
val textStyle = TextStyle(color = labelColor,
    fontSize = if (style == GlassNavigationStyle.Stacked) 11.sp else 14.sp)
if (labelLayouts == null) BasicText(tab.title, style = textStyle, ...)
```

**我们的意图**（b130bd9c / 127c5241 / 354ac3ce / 37b4c883 / 501ce5de，见 `git log -1 --format=%B`）

平板顶栏（`GlassNavigationStyle.TabletLabels`）的排版是我们做的：**15sp + SemiBold**，手机 Stacked 保持 11sp（`b130bd9c`、`37b4c883`）。这个 `labelStyle` 不只是画字，它还：

- 被 `textMeasurer.measure(..., labelStyle)` 用来算每个 cell 的内容宽度（`tabContentWidths`），进而算出 `huggedCapsuleWidth` → `insetToDrawnWidth` 的收窄宽度；
- 被传给 `NavigationLabel(tab.id, tab.title, labelStyle, labelLayouts, refraction)`，而折射行复用可见行的 text layout（`hasSameLayoutAffectingAttributes` 比对），两边必须**同一个 TextStyle**。

501ce5de 的提交信息已经写过一次教训：*1.6.6 同步时把上游的 collapse 重写整个吃下来，平板的 content-hugging 被静默丢掉了*。这次如果再吃上游这条 hunk，就是同一类事故的第二遍：字号回到 14sp/11sp、字重回到 Normal，而 `tabContentWidths` 仍用我们的 `labelStyle` 量 → cell 宽度与画出来的字不匹配，胶囊会偏宽/偏窄，而且因为 `GlassTabletTopBarWiringTest` 只 pin 了 `15.sp`/`FontWeight.SemiBold` 字符串，改回上游会**红**」。

**建议解法**：保留我们的 `labelStyle`，但**把颜色换成 `labelColor`**（这是上游这条 hunk 唯一不能丢的语义）：

```kotlin
        // AM++: the tablet bar's labels are slightly larger and heavier than the phone bar's; the
        // two styles therefore get different typography, and the tablet bar sizes each cell to the
        // label it is about to draw. The reference's labels are heavier still, hence SemiBold on
        // the tablet bar only.
        val labelStyle = if (tabletBar) {
            TextStyle(color = labelColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            TextStyle(color = labelColor, fontSize = 11.sp)
        }
```

下游两个分支（`BasicText(..., labelStyle)` / `NavigationLabel(..., labelStyle, ...)`）保持我们的写法，把上游那三行 `textStyle` 整段丢掉。

为什么必须换成 `labelColor`：`glassAdaptiveForegroundEnabled` 在 `ModuleModels.kt:14` 的默认值是 **true**，1.7.1 合进来后默认开启。若标签仍用 `foreground`（宿主静态色）而同一次重绘里图标走 `sampledForeground`（`GlassNavigation.kt:236`）和抽屉图标 `sampledForeground`（`:275`），会出现「图标反色、标签不反色」的割裂；平板顶栏在我方是纯标签栏，症状最明显。

顺带说明为什么不会引入新的抖动：`labelStyle` 是 `remember(tabs, textMeasurer, density, labelStyle)` 与 `labelLayouts` 的 key 之一，颜色翻转会重建这两处缓存；宽度与颜色无关，所以只是丢掉一帧的折射行 layout 复用（`NavigationLabel` 会走 fallback 分支），不会改变几何。想彻底避免这点可以再进一步：测量用 `labelStyle.copy(color = Color.Unspecified)`、绘制用 `labelStyle`（`hasSameLayoutAffectingAttributes` 不看颜色，安全）。这是可选优化，**不建议**在这次合并里顺手做。

### 1.3 `glass/src/main/kotlin/dev/amenhancer/glass/GlassNavigation.kt:288-300`（refractionContent / content 与取样参数）

**上游意图**：同一个自适应前景改造的第二处 —— 把采样入口接进面板：

```kotlin
onSampleMaterial = contrast?.sampleMaterial,
sampleCompactForeground = neutralCompactGlyph,     // 收起时选中的若是搜索页，compact 面用前景色，继续取样
refractionContent = if (labelLayouts != null) { { tabContent(true) } } else null,
) { tabContent(false) }
```

（`neutralCompactGlyph = searchTab?.id == selectedId`，`GlassNavigation.kt:149`）

**我们的意图**：`37b4c883`/`501ce5de` 把 `tabContent` 从「`(Boolean)`，闭包里自己 `inCapsule.forEach`」改成 **`(contentIndex: Int, weight: Float, Boolean)`**，因为 content-hugging 要求每个 cell 拿到自己的 `weight`（`LiquidBottomTabs` 用 `geometry.cellWeight(index)` 算出来）。因此 `refractionContent`/`content` 的签名是 `(Int, Float) -> Unit`（`LiquidBottomTabs.kt:149-150`，**已经自动合并成我们的 2 参形态**）。

**建议解法**：并集 —— 保留我们的 3 参结构，同时把上游两行原样加回去：

```kotlin
                    onSampleMaterial = contrast?.sampleMaterial,
                    sampleCompactForeground = neutralCompactGlyph,
                    refractionContent = if (labelLayouts != null) {
                        { contentIndex, weight -> tabContent(contentIndex, weight, true) }
                    } else null,
                ) { contentIndex, weight -> tabContent(contentIndex, weight, false) }
```

- 取 HEAD 而丢掉 `onSampleMaterial`/`sampleCompactForeground`：`contrast.foreground` 永远是 null → `labelColor` 永远等于 `foreground` → 1.7.1 的「自动反色」在导航条上整条失效（设置页开关变成摆设），而 mini 播放器那条路径（`GlassMiniForeground`）却是好的，症状是「只有 mini 会反色」。
- 取上游而丢掉 index/weight：平板 cell 全部拿不到 `cellWeight` → 胶囊宽度不均匀、拖动 pill 落点错位；`GlassTabletTopBarWiringTest:42`（pin `content: @Composable RowScope.(index: Int, weight: Float) -> Unit`）与 `:43`（pin `geometry.cellWeight(index)`）会红。

### 1.4 `glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt:7-24`（KDoc）

纯注释冲突。上游把「content-hugging cells / opt-in 参数 / 每个 content 参数默认等宽」那几句删了（它的版本没有这些参数），并加了最后一句 `The panel can expose its recorded material for adaptive tab foregrounds.`。

**建议**：并集，保留我们的两段（提到 content-hugging cells、tabContentWidths/tabIconOnly、以及「Every collapse parameter defaults to the expanded bar and every content parameter defaults to upstream's equal cells」），末尾追加：

```
 * The panel can expose its recorded material for adaptive tab foregrounds.
```

**函数体不需要动**：`git diff` 证据（merged vs `:2:`）显示自动合并后只剩上游的纯增量 —— `onSampleMaterial`/`sampleCompactForeground` 形参（146-147）、`sampleMaterial` 计算（451-452）、`glassPanelMaterial(..., sampleMaterial)`（453-459）、`glassPanelMaterial` 的形参与 `onSampleMaterial = onSampleMaterial`（663-687），以及 `containerColor` 透明度 0.4 → 0.2。我们的 `measuredContent`/`contentWeighted`/`contentGeometry`/`selectionLayerSize` 最宽 cell、`highlightAnchor.geometry`/`freeDragBridge.geometry`/`snapIndex`/`cellWeight` 全部在（`:241-250、`:435-441、`:370-373、`:478-495`）。也就是说**这个文件的自动合并这次没有重演 501ce5de 的事故**。

---

## 2. 1624 / 1626 profile vs 我们给 1614 补的 12 个读音 seam

### 2.1 事实

`d280708c "fix(profile): pin the 1614 pronunciation seams"` 给 `7.0.0-1614.json` 补了 12 个 seam（提交信息逐条列了 DEX 依据）。合并后我逐点统计了 4 个 profile 的 `hookTargets`（79 个 contract）：

| seam | 1614 | 1622 | 1624 | 1626 |
| --- | --- | --- | --- | --- |
| LYRICS_VIEW_MODEL_LOAD | 1 | 0 | **0** | **0** |
| LYRICS_VIEW_MODEL_BUILD | 1 | 0 | **0** | **0** |
| LYRICS_NATIVE_PRESENTATION | 1 | 0 | **0** | **0** |
| LYRICS_OFFICIAL_PRONUNCIATION_MATCH | 1 | 0 | **0** | **0** |
| LYRICS_PRONUNCIATION_PREFERENCE | 1 | 0 | **0** | **0** |
| LYRICS_PREFERRED_LANGUAGES_REQUEST | 1 | 0 | **0** | **0** |
| LYRICS_RECYCLER_ADAPTER | 2 | 0 | **0** | **0** |
| LYRICS_WORD_RENDER_ADAPTER | 1 | 0 | **0** | **0** |
| LYRICS_WORD_VECTOR_CLASS | 1 | 0 | **0** | **0** |
| LYRICS_UI_ON_CREATE_VIEW | 1 | 0 | **0** | **0** |
| LYRICS_UI_ON_DESTROY_VIEW | 1 | 0 | **0** | **0** |
| APPLE_SHARED_PREFERENCES_CLASS | 1 | 0 | **0** | **0** |

**结论：1624/1626 一个读音 seam 都没有 pin，和我们已经发布的 1622 完全同一状态。** 合并本身不会让 1614/1622 变差（1614 与 1622 这两个文件在本次合并里 0 改动，`git diff HEAD -- ... 7.0.0-1614.json 7.0.1-1622.json` 为空），只是**新版本没有任何读音能力**，而且失败方式是「静默降级」而不是崩溃。

### 2.2 `lyricPronunciation` 节点形态是否一致 —— 一致，只有一个 descriptor 变了

`LyricsPronunciationMatching.install()`（`LyricsPronunciationMatching.kt:27`）读的是 `profile.document.lyricPronunciation`，**不是**上面那 12 个 seam。四个 profile 的这个节点都是 `methods` 一组 10 个 role，逐 role 比对：

- **9 个完全相同**（含 `evaluate = PlayerLyricsViewFragment#F2(SongInfo$SongInfoPtr)V`、`match = LocaleUtil#matchToSystemLyricsScript(StringVector$StringVectorNative)String`、`address = Pointer#address()long`、`songLanguage/getPronunciationLanguages/setTranslation/vectorSize/vectorGet`）；
- **1 个不同**：`requestLyrics`
  - 1614：`com.apple.android.music.ttml.e#e(long,[Ljava.lang.String;,long,[Ljava.lang.String;,hi.c)Serializable`
  - 1622/1624/1626：`com.apple.android.music.ttml.f#e(long,[Ljava.lang.String;,long,[Ljava.lang.String;,gi.c)Serializable`
  （上游自己的 R8 重打包：`ttml.e`→`ttml.f`、`hi.c`→`gi.c`，与 profile 里 `q8.X4.o0→n0`、`PlayerLyricsViewFragment.m1→l0` 同一批位移一致。）

### 2.3 退化点（重点，含一个不显然的坑）

1. **原生歌词模型/载荷链整条降级**。`AppleMusicNativeLyricsTarget.install()`（`AppleMusicNativeLyricsTarget.kt:32-39`）第一件事就是 `exactTargets(version, LYRICS_VIEW_MODEL_LOAD).firstOrNull()`，1624/1626 拿到空表 → 直接 `Degraded("Native lyrics profile is unavailable for 7.0.2 / 7.0.3")`。即使绕过它，`AppleNativeLyricModelHooks.install()`（`:762-768`）在 `nativeNames.isEmpty()` 时也只打一行 log 返回。**后果**：我们自己投递的在线/自定义歌词的读音与翻译无法写进原生模型；`LYRICS_PRONUNCIATION_PREFERENCE` 缺失使 `pronunciationPreferenceAvailable = false`（`:367-371`）→ 我们这侧读不到宿主的「读音已选」偏好，只能保持旧的 fail-open；`LYRICS_RECYCLER_ADAPTER`/`WORD_RENDER_ADAPTER`/`WORD_VECTOR_CLASS` 缺失使逐词注入与逐词居中失去落点。
2. **坑：`requestLyrics` 描述符错一个，整组读音钩子一起死**。`FragmentProfileMembers` 的 `init` 不是只校验 `required.methods`：它 `declared?.keys()?.forEach { ... onFailure { errors += ... } }`（`FragmentProfileMembers.kt:23-31`），即**凡是在节点里声明的 role 都必须能解析**，最后 `check(errors.isEmpty())`（`:53`）。而 `LyricsPronunciationMatching.install` 外面只有一个 `runCatching`（`:30-147`）：`requestLyrics` 解析失败 → 整个 install 抛错 → `match` 兜底、CJK 语言短路、`en-Latn` 追加**全部不装**，只剩一行 `"Native lyrics pronunciation hooks unavailable (…)"`。而 1622→1626 唯一改动的 role 恰好就是这个 `requestLyrics`。所以：**1624/1626 上读音 matching 是「要么全好、要么全死」的赌注，取决于上游那个 `ttml.f#e(...,gi.c)` 是否真解得出。**
3. **不受影响的部分**（不要过度悲观）：`layoutVariants.lyricPronunciationPairLayout = "lyrics_pronunciation_vertical_group"` 在 1624/1626 **没变**，`productionEnabled=true`、`capabilities.lineLyricAgentAlignment=true` 也都在 —— 所以 `installLyricPronunciationPairAlignment()` 会正常注册（居中的 LayoutInflation 侧），`lyricResultSubmission` 节点（`loadLyrics`/`buildTimeRangeToLyricsMap`/`fragmentViewModel=m1`）在 1624/1626 齐备，**共用歌词结果提交这条主干仍然能用**，被牺牲的是「我们自己往原生模型里塞读音/翻译」那条支线。
4. **我们只有 1614 的 DEX 证据**：`runtimeMemberNames` 是逐版本混淆名字典（例：1614 的 `LYRICS_VIEW_MODEL_LOAD` 带 `LYRICS_NATIVE_SET_PRONUNCIATION_METHOD=setPronunciation`、`LYRICS_ADAPTER_ITEM_COUNT_METHOD=d`、`LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD=e` 等 20+ 项），不能从 1622 的值照抄（1622→1624 就移了 `PlayerLyricsViewFragment.m0→l0`、`E0→C0`、`X4.Y ConstraintLayout→FrameLayout`）。**没有 1624/1626 的 base.apk 就不能真 pin**；能从 1624 profile 自身推出的只有 `LYRICS_VIEW_MODEL_LOAD`（`lyricResultSubmission.loadLyrics`）、`LYRICS_VIEW_MODEL_BUILD`（`buildIndex`）、`LYRICS_NATIVE_PRESENTATION`（`lyricPronunciation.evaluate` = `F2`）、`LYRICS_OFFICIAL_PRONUNCIATION_MATCH`（`lyricPronunciation.match`）、`LYRICS_UI_ON_CREATE_VIEW/_DESTROY`（`fragmentLyricsRuntime.createView/destroyView`）这 6 个的 owner/name/descriptor —— 但 `runtimeMemberNames` 与另外 6 个（adapter 三件套、preference、shared-prefs、preferred-languages）仍要 DEX。

---

## 3. `AppleMusicHostFactory` 的自动合并结果（关注但未冲突）

`git diff HEAD -- host-applemusic/.../AppleMusicHostFactory.kt` 只有一个补丁块：上游 d15b9956 新增的两个自适应前景辅助函数 + 两个 import。**我们的东西一条没少、也没有被绕开**：

| 我们的门/接线 | 合并后位置 | 状态 |
| --- | --- | --- |
| 读音 matching 安装（按调用读偏好） | `:165-170` `LyricsPronunciationMatching.install(profile, classLoader) { config.settings().lyricsPronunciationEnabled }` | 在。仍是 install 一次、开关按 call 读（注释也在） |
| pair alignment 的 `lyricEffectsOptimizationEnabled` 门 | `:187-195` `installLyricPronunciationPairAlignment(profile, application.resources) { config.settings().lyricEffectsOptimizationEnabled }`，内部 `:106-107` `register { if (!enabled()) return@register }` | 在，且是「注册固定、开关按 inflate 读」 |
| native lyrics feature 门 | `NativeLyricsFeature`（`app/.../hook/NativeLyricsFeature.kt:7-18`，key `FEATURE_NATIVE_LYRICS`）→ `context.target.nativeLyrics.install()`；factory 只把 target 挂到 `TargetAdaptation.nativeLyrics`（`:248`） | 在。上游没碰 feature registry |
| source cache 接线 | `createAutoLyricsRuntime` 内 `val sourceStore = content.sourceCache`（`AutoLyricsReplacementSession.kt:189`），`EmbeddedLyricsPlaybackRuntime` 也从 `content.sourceCache` 取（`:32`） | 在。上游把它用在 1.7 的缓存管理/回填上，是**加**在我们同一个 store 上 |
| timing registry / overlay 共享 | factory 形参 `:128-129` 保留默认值，assembly 传入同一实例 | 在 |

唯一需要注意的不是「被绕开」，而是**上游新代码依赖我们这侧必须把对象传进来**：`cacheSongName`/`withSongTitle`/`lookupCacheSongNames`/`backfillCacheSongName` 都读 `currentSong`（第 1.1 节），一旦 `createAutoLyricsRuntime` 的形参只保留我们那侧，这段函数体就编译不过 —— 编译期会挡住，不会静默。

---

## 4. 风险等级 + 合并后必须跑的东西

设备侧脚本可以直接复用：`scripts/verify-device-phone-liquid-glass.ps1`（玻璃/mini）、`verify-device-stacked-bottom-nav.ps1`（手机底栏等宽不动）、`verify-device-dual-pane.ps1` 与 `verify-device-right-lyrics-pane.ps1`（平板双栏/右栏）、`verify-device-auto-lyric-refresh.ps1`（自动补全）。

**风险等级：中高。** 冲突本身低风险（4 个 hunk，3 个有明确 precedent），风险集中在「新版本没有读音 seam」与「玻璃一套新的取样路径和我们的平板 content-hugging 几何第一次同屏」。

### 4.1 静态闸门（提交前，本机可跑）

1. `python3 scripts/verify-architecture.py`（AGENTS.md 要求的最低门槛）；
2. `python3 scripts/verify-profile-data.py` + `python3 scripts/verify-host-profile.py`（合并后精确档案 10 → 12，`index.json` 也改了，断言若写死数量/清单会红）；
3. `python3 scripts/verify-glass-reference.py`（backdrop patch sha 变了：`backdrop/local-patches-sha256.json` + `UPSTREAM.md`，`DrawBackdropModifier.kt` 新增 `onSampleMaterial`）；
4. `glass` 模块单测：`GlassTabletTopBarWiringTest`（pin 了 `15.sp`/`SemiBold`/`cellWeight`/`content: ...(index, weight)` —— 它会直接抓住「顺手取上游」）、`GlassTabGeometryTest`、`GlassPolicyTest`；
5. `PhoneLiquidGlassStructuralRegressionTest`（已被上游 f1692b2d 改写成 pin `NavigationIcon(...)` / `PorterDuffColorFilter(...SRC_IN)` / `canvas.saveLayer(...)` 的新形态；它 pin 的 `NavigationIcon(glyph?.icon, if (searchLeft) foreground else accent)` 与合并后的 `CompactSelectionGlyph` 一致，但**如果谁在解决 1.2 时把 `labelColor` 顺手改成 `foreground` 并动了这一行，会红**）。

### 4.2 真机必测（按优先级）

| # | 场景 | 版本 | 看什么 |
| --- | --- | --- | --- |
| 1 | 读音全链路 | 1614（唯一有 seam 的 7.0.x） | 逐词读音出现、逐词居中（pair layout 补偿）、「读音」菜单项可选、「歌词效果优化」关掉后**不**居中、翻译与读音同时开的双轨；日志 `Native lyrics pronunciation hooks installed (…)` / `Apple native lyric pronunciation/translation hooks installed …` |
| 2 | 读音在 1624/1626 上的实际状态 | 1624 + 1626 | 只验「失败得干净」：没有崩溃、读音菜单不出现或不可选、日志恰好一条 `unavailable`/`Degraded`；特别确认**没有**「读音出现但错位/英文读音消失一半」的中间态（那就是 2.3.2 那个 `requestLyrics` 赌注输了的症状） |
| 3 | 平板双栏 + 顶栏 | 1614 与 1626 各一次，横屏 + w600dp | 顶栏标签 15sp/SemiBold、cell 内容自适应宽度、胶囊居中收窄（`insetToDrawnWidth`）、拖动 pill 落点与高亮一致、抽屉图标、搜索页脱离/回归；开着「自动反色」在**深浅两种封面**下看标签与图标是否**同时**反色（1.2 的解法就是为这条） |
| 4 | 玻璃 / mini 播放器 | 1614、1626 | 自动反色开：mini 歌名/艺人/三个按钮随材质反色，Nav 标签与图标同步；关：停止取样（`updateMiniForeground(false)`）、恢复静态配色、材质仍在；手势切 mini↔展开无残留；深色/浅色主题各一遍 |
| 5 | 元数据 / 缓存歌名回填 | 1614（新代码在 1624 也有，但先验 1614） | 第三方歌词缓存管理页：新歌播放后条目显示「歌名 - 歌手」而不是「未知歌曲」+ID；旧条目播放一次后被回填；`backfillCacheSongName` 只改 `displayName` 不让条目失效（refresh 不该因此重下） |
| 6 | 居中/边界 | 1614 | 逐词读音居中在「英文 + 中文」混排、行尾、`x-bg` 和声行上不被裁切；关闭效果优化后宿主原生几何不被我们动过 |
| 7 | 回归 | 1614 | 自动实时补全、翻译、LLM 翻译/音译（这次一起进来的缓存清理页）、自定义歌词导入 —— 确认 1.1 取 HEAD 后 `automatic` 的两个总开关仍然生效（关掉自定义歌词 → 不装 runtime） |

---

## 5. 需要拍板的点

**A. `createAutoLyricsRuntime` 的 `currentSong` 走哪条路（跨文件，需与 AutoLyricsReplacementSession 的负责人对齐）**
建议：保留上游必需形参 + 我们可选参数块，调用点补 `currentSong = currentSong`。替代方案是去掉它、从 `currentTrack` 现推，代价是丢掉 1.7 的缓存歌名回填与缺名 ID 显示（`AutoLyricsReplacementSession.kt:728-733`、`315-350`）。**我不建议替代方案**，但需要你确认「1.7 的缓存管理页必须有回填」这个产品要求。

**B. 1624/1626 的读音 seam 要不要现在补**
- 补：需要 1624 与 1626 的 base.apk + splits 做 DEX 派生（1614 的 12 条全是我们自己从 DEX 读出来的，`runtimeMemberNames` 不能抄）。工作量与 1614 那次相当，且必须真机验证。
- 不补：就要接受「7.0.2/7.0.3 没有任何读音/原生载荷能力，且 `requestLyrics` 描述符若与上游不符会连 matching 一起死」。至少建议加一条**回归断言**（单测）把「1624/1626 的 12 个 seam 为空 = 已知降级」固定下来，别让下一次同步误以为是漏 pin。
- 我倾向：**这次不补**（不阻塞 1.7.1 合并），但要在 `docs/feature-preservation.md` / `knownDegradations` 里显式登记 1624/1626 的读音能力为 0，并在下一个版本线补 DEX 派生。

**C. 平板上 `glassAdaptiveForegroundEnabled` 默认 true 是否可接受**
1.7.1 之后所有用户默认开启自动反色，平板顶栏的标签/图标配色会随封面明暗变。如果你不希望「平板顶栏一上线就变色」，需要有意识地改默认值（`ModuleModels.kt:14`）—— 但那是跨功能的取舍，我不动。

**D. 1.1 里我们要不要顺便把位置参数改成具名参数**
`AppleMusicHostFactory.appleMusic(...)` 有 9 个参数且 6 个连续类型相近（`ClassLoader`/`LyricsTypefaceResourceBinding`/`CurrentSongIdentityCache`/`AutoLyricsRuntime?`/`TtmlTimingObservationRegistry`/`NativeLyricOverlayStore`）。这次冲突正是「位置参数在两侧不一样」造成的。建议顺手改成具名调用（纯可读性，无行为变化）——需要你同意在合并提交里掺这一手。

**E. `LYRICS_PREFERRED_LANGUAGES_REQUEST` 在 1622/1624/1626 也是空的**
这条不只影响读音，还影响「首选的歌词语言扩展」（`installPreferredLanguageExpansion`）。也就是说 1622 上线以来，7.0.1+ 用户可能一直没有那条能力 —— 如果你印象里 1622 上有人反馈「读音请求不到 en-Latn」，那就是这里的已知降级，不是新回归。
