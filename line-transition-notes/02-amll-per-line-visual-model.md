# AMLL「每行视觉模型」拆解：scale / opacity / blur / 亮度 / 强调 / 浮动

> 对象：`third_party/applemusic-like-lyrics`（只读浅克隆，HEAD `f329796`），核心在 `packages/core/src/lyric-player/`。
> 目的：给出一份**能直接翻译成 Kotlin** 的规格——默认值、公式、边界条件、以及每一条结论的源码出处（`文件:行`）。
> 配套笔记：[01-lyrics-line-transition-1626.md](01-lyrics-line-transition-1626.md)（Apple 1626 宿主的真实实现）、[01-line-change-motion-feasibility.md](01-line-change-motion-feasibility.md)（宿主接入点）。
> 证据分级：**默认全部是「有代码证据」**；凡标【推断】的是由代码行为推出、未在真机/浏览器里量过。
> 引用约定：`base/…`、`dom/…`、`styles:…` 都是相对 `packages/core/src/lyric-player/` 的路径；`utils/…` 相对 `packages/core/src/`；`styles/…` 是 `packages/core/src/styles/lyric-player.module.css` 的简写。

---

## 0. 结论速查

| 问题 | AMLL 的答案 | 出处 |
| --- | --- | --- |
| 非当前行 scale | **0.97**（`SCALE_ASPECT`），且只在 `isPlaying` 时生效；暂停全部回 1.0 | `base/group.ts:110-114` |
| 当前行 scale | **1.0**（弹簧从 0.97 弹到 1.0，ζ=0.884，无可见回弹） | `base/group.ts:111-123` |
| 缩放是否随距离衰减 | **否**。只有两档 1.0 / 0.97（背景人声行另有一档 0.75） | `base/group.ts:110-136` |
| opacity | 视口外 0；`hidePassedLines` 时 1e-4；**高亮 0.85**；其余逐词歌词 1.0 / 非逐词歌词 0.2 | `base/index.ts:1036-1056` |
| blur | **不是半径，是「档位」**：`level = 1 + 行距`，宽视口直接当 px，窄视口 ×0.8，最后 `min(5, level)` px 写进 `filter` | `base/index.ts:1065-1087` + `dom/lyric-group.ts:189-193` |
| 亮度 | **二值**（不是随距离连续变化）：高亮＝逐词遮罩 alpha 1.0/0.4 叠在 0.85 的母层上；非高亮＝均匀 0.2（亮度副本或遮罩 0.2/0.2） | `dom/line-brightness.ts` + `styles/lyric-player.module.css:47-48,153-184` |
| 强调（当前行放大序列） | 行级只有 97→100 的弹簧（t90≈0.47s，过冲 0.26%）；真正的「强调」是**逐字符脉冲**：scale 峰值 `1 + 0.1·amount`（≤1.12），包络 0→1→0 | `dom/animation/emphasize/index.ts:105-172` |
| 浮动 | **没有周期运动**。每个词一次性上浮 0.05em（BG 0.1em），时长＝该词时长，暂停即停、失焦反放 | `dom/animation/float/index.ts:42-60` |
| 时钟 | 两个：① rAF + 弹簧（posY、scale，**同一个 delta**）② Web Animations / CSS transition（mask、float、emphasize、opacity、filter） | `dom/index.ts:224-241` + `packages/vue/src/LyricPlayer.tsx:260-278` |
| 逐词遮罩与行变换共用时钟吗 | **不共用**。遮罩是 WAAPI 自己跑，只在 `enable()` 时对齐一次；换行时旧行遮罩**冻住**再整体交叉淡出 450ms | `dom/lyric-line.ts:113-164` |

---

## 1. 属性所有权：谁写哪个属性

一层包装、两层元素，**分工非常清楚**（这点直接影响 Android 端该把属性写在哪一层）：

```
.lyricLineWrapper            ← 组（LyricLineGroup）
  transform: translateY(y)   ← posY 弹簧（逐帧）
  opacity: α                 ← resolveOpacity（0.4s CSS ease）
  filter: blur(Npx)          ← resolveBlurLevel（0.4s CSS ease）
  └ .lyricLine               ← 行（LyricLineEl）
      transform: scale(s)    ← scale 弹簧（逐帧，transform-origin: left）
      classes: gradientMask / active / lyricBgLine / lyricDuetLine
      └ .lyricMainLine (+ .lyricMainLineBright 亮层)
          mask-image / -webkit-mask 逐词遮罩 ← WebMaskAnimator
          └ span(word) ... span(word)         ← 每个词的 float / emphasize 动画
      └ .lyricMainLineDim（亮度副本，绝对定位，克隆自主行，无 mask）
      └ .lyricSubLine（翻译）
      └ .lyricSubLine（音译）
```

| 属性 | 写入点 | 频率 | 备注 |
| --- | --- | --- | --- |
| `translateY` | `dom/lyric-group.ts:178-182` | 每帧（变化 ≥0.001px 才写） | `toFixed(1)` |
| `opacity` | `dom/lyric-group.ts:184-187` | 变化 ≥0.05 才写 | CSS 过渡 0.4s ease |
| `filter: blur` | `dom/lyric-group.ts:189-193` | 变化 ≥0.05 才写 | `min(5, blur)`，`toFixed(2)` |
| `scale` | `dom/lyric-line.ts:222-230` | 每帧（变化 ≥0.0001 才写） | `toFixed(3)`；`transform-origin: left`（对唱行 right） |
| 逐词 mask | `dom/animation/mask/animator-web.ts` | 建一次，之后 WAAPI 自跑 | 只有 `setCurrentTime()` 时才写 |
| 子行 opacity | CSS 固定 | 不写 | 翻译/音译 `0.3`，背景人声行 `0.4`（`styles:137-151,186-195`） |

**关键点**：`scale` 在**内层**（`.lyricLine`）、`opacity`/`blur` 在**外层**（wrapper）。所以 blur 会把翻译行一起糊掉，而 scale 不会影响子行字号比例（子行 `font-size: 0.5em` 跟随父级缩放）。

---

## 2. 两个时钟，以及一帧里发生什么

### 2.1 时钟 ①：rAF + 弹簧（唯一被 `update(delta)` 驱动的部分）

```
requestAnimationFrame(t => player.update(t - last))   // packages/vue/src/LyricPlayer.tsx:264-272
  └ DomLyricPlayer.update(delta)                      // dom/index.ts:224-241
       d = min(delta, MAX_FRAME_DELTA=100ms)          // base/consts.ts:136
       ├ 每个组: group.update(d)                       // base/group.ts:149-163
       │    ├ posY.update(d)        → translateY       // dom/lyric-group.ts:178
       │    ├ bgSlideY.update(d)    → 背景行滑入        // dom/lyric-group.ts:203-230
       │    └ mainLine.update(d) → scale.update(d)      // dom/lyric-line.ts:638-647
       └ 每个组: group.commitChanges()                 // dom/index.ts:238-240
            └ 只在 isInRenderRange() 时写 DOM，否则 hide()
```

**结论**：posY 与 scale 由**同一帧、同一个 delta** 驱动（`group.update` 里连续调用），但**目标值不是同一时刻设置的**——见 §6.3。

### 2.2 时钟 ②：Web Animations / CSS transition（库不参与逐帧驱动）

| 动画 | 载体 | 谁推进 | 对齐时机 |
| --- | --- | --- | --- |
| 逐词遮罩 | `element.animate(maskPosition)` | 浏览器动画时钟 | `enable()` 时 `setCurrentTime(t - lineStart)` |
| 词上浮 float | `element.animate(translateY)` | 浏览器动画时钟 | 同上 |
| 字符强调 emphasize | `element.animate(transform/textShadow)` | 浏览器动画时钟 | 同上 |
| opacity / filter | CSS `transition` | 样式引擎 | 目标值变更那一帧 |
| 亮层交叉淡入淡出 | CSS `transition: opacity` | 样式引擎 | 切 class 那一帧 |

**这是一处重要的架构事实**：AMLL 的「换行视觉」里只有**位移和缩放**是逐帧数值计算的，**明暗与模糊是「设目标值 + 交给 CSS 过渡」**。移植到 Android 时不要照抄成「每帧算 alpha」，要么用 `ViewPropertyAnimator`（等价 CSS transition），要么自己线性插值（我们仓库里 `LyricBlurTransition` 就是后者）。

### 2.3 换行那一帧的顺序（`setCurrentTime` → 一帧内）

```
setCurrentTime(t)                                     // base/index.ts:643-654
 └ syncTime(mediaTime, isSeek)                        // base/index.ts:661-724
    ├ timeline.sync() → diff(removedHighlighted / addedHighlighted)   // base/timeline.ts:304-385
    ├ for removed: group.disable()                    // base/index.ts:679-681 → dom/lyric-line.ts:143-164
    │    ├ setRenderMode(SOLID) → 去掉 .gradientMask + markHighlightExited()   // :589-602
    │    ├ maskAnimator.pause()                       // 遮罩停在原位
    │    ├ float-word: playbackRate=-1; play()        // 上浮回退
    │    └ main.classList.remove(active)
    ├ for added: group.enable()                       // base/index.ts:687-691 → dom/lyric-line.ts:113-141
    │    ├ 每个 word 动画 currentTime = t - lineStart
    │    ├ maskAnimator.setCurrentTime(t - lineStart, isPlaying)
    │    └ main.classList.add(active)
    ├ updateSpringParams(policy)   // 仅在跳行/间奏/曲末变化时     // base/index.ts:708-719
    └ calcLayout(PlaybackTick)                        // base/index.ts:857-1016
         └ 逐行 group.setTransform(y, snap, delay, isActive, opacity, blur, inWindow)   // :980-988
              ├ setLineTransformations → line.setTransform(scale…) → scale.setTargetPosition(s)  // group.ts:102-137 / lyric-line.ts:631-635
              └ posY.setTargetPosition(y, delay)      // group.ts:95
```

---

## 3. 每帧输入状态（决定视觉的 12 个量）

| 量 | 来源 | 含义 |
| --- | --- | --- |
| `scrollToIndex` | `base/timeline.ts:472` | 自动对齐的焦点行（= 当前高亮行里**最小**的索引） |
| `latestHighlightedIndex` | `base/timeline.ts:366-374` | 高亮行里**最大**的索引；无高亮时 undefined |
| `highlightedGroups` | 同上 | 所有仍亮着的行（唱完不熄，直到下一行开始） |
| `isActive(i)` | `base/index.ts:1023-1028` | `highlighted(i) OR (scrollToIndex ≤ i < latestIndex)` |
| `inViewport(i)` | `base/layout.ts:381-401` | 视口 ± (overscan 300px + 0.4H 运动缓冲) |
| `isPlaying` | `base/index.ts:93,1138-1154` | 暂停时所有行 scale 回 1.0、背景行滑出 |
| `enableScale` | `base/index.ts:149,363-366` | 关掉后 `SCALE_ASPECT = 100` |
| `enableBlur` | `base/index.ts:148,450-454` | 关掉后 blur 恒 0 |
| `isNonDynamic` | `base/index.ts:110-112` | 非逐词歌词：无亮度副本，用整体 opacity 0.2 表达「暗」 |
| `isTouchScrolled` | `base/index.ts:334,1077` | 用户手指滚动过：全场 blur＝0（直到 `resetScroll()`） |
| `hidePassedLines` | `base/index.ts:442-445,1044-1051` | 已唱过的行 opacity=1e-4（**故意不用 0**，避免浏览器优化） |
| `narrow` | `base/index.ts:950` | `容器宽 ≤ 1024` → blur 档位 ×0.8 |

---

## 4. 每行的静态目标值（核心章节）

### 4.1 scale

`base/group.ts:102-137`：

```kotlin
// 伪代码（AMLL 内部用 0..100 的百分数域，CSS 里再 /100）
val SCALE_ASPECT = if (enableScale) 97f else 100f
val mainScale = if (!isActive && isPlaying) SCALE_ASPECT else 100f
val bgScale   = if (!isActive && isPlaying) 75f else 100f      // 背景人声行
```

应用到 `.lyricLine` 的 `transform: scale(mainScale/100)`，`transform-origin: left`（对唱行 `right`，`styles:45,59-62`）。

要点：

1. **只有两档**：0.97 / 1.0。**没有** `scale = f(dy)` 的连续衰减，也没有「越远越小」。
2. **暂停时全部 1.0**（`isPlaying=false`），所以暂停会让所有行「涨回来」——第一次读 AMLL 容易意外的行为。
3. **背景人声行**默认 `font-size: 0.7em`（`styles:139`），再乘 0.75，视觉上约等于主行的 0.525 倍；它与高亮无关的「滑入」由 `bgSlideY` 弹簧（±80 单位）负责（`group.ts:86-97`、`lyric-group.ts:203-230`）。
4. **scale 不参与排版**：换行平衡器测量前会把祖先的 `transform` 临时改成 `none`（`utils/line-balancer.ts:99-128`，测试 `packages/core/test/line-balancer-ancestor-transform.test.ts:16-28`）。Android 端同理：`setScaleX/Y` 不能触发重新测量/换行。

### 4.2 opacity

`base/index.ts:1036-1056`（写在外层 wrapper）：

```kotlin
fun resolveOpacity(i: Int, inViewport: Boolean, s: Snapshot): Float = when {
    !inViewport -> 0f
    hidePassedLines && isPlaying && i < passedBoundary -> 1e-4f
    i in s.highlightedGroups -> 0.85f
    isNonDynamic -> 0.2f
    else -> 1f
}
// passedBoundary = if (间奏中) anchorLineIndex+1 else scrollToIndex   (base/index.ts:947-949)
```

注意这**不是**最终观感亮度——逐词歌词的非高亮行虽然 group opacity = 1，但被遮罩压到 0.2（见 4.4）。

### 4.3 blur（档位，不是半径）

`base/index.ts:1065-1087` + `dom/lyric-group.ts:189-193`：

```kotlin
fun resolveBlurLevel(i: Int, isFocused: Boolean, inViewport: Boolean, s: Snapshot): Float {
    if (!enableBlur) return 0f
    if (!inViewport) return 0f
    if (isTouchScrolled || isFocused) return 0f          // 手指滚动中 / 焦点行不糊
    val d = if (i < s.scrollToIndex) abs(s.scrollToIndex - i) + 1
            else abs(i - s.latestIndex)                   // latestIndex = latestHighlightedIndex ?: scrollToIndex
    val level = 1f + d
    return if (narrow) level * 0.8f else level
}

// 写入时：
val px = min(5f, level)                                   // dom/lyric-group.ts:189
wrapper.filter = if (px > 0.01f) "blur(" + px + "px)" else "none"
```

要点：

1. **距离是「行索引差」，不是像素 dy**（整个文件里没有任何按像素距离衰减的逻辑）。
2. 上下不对称：**上方邻行距离 +1**（那个 `+1` 常量），下方邻行不加。所以上一行 3px、下一行 2px（宽视口）。
3. 上限 5px 硬钳制；窄视口（宽 ≤1024）整体 ×0.8。
4. 焦点行（`isActive`）永不模糊——它靠遮罩表达明暗，不靠模糊。
5. 手指滚动后 `isTouchScrolled` 会**一直保持 true**，直到 `resetScroll()`（`base/index.ts:1189-1193`；触发点：跳转、或滚动结束 500ms 后自动对齐恢复）。所以「用户自己滑歌词时全场不糊」是持久的，不是手势期间。

### 4.4 brightness（亮度层 + 遮罩 alpha）—— 二值模型

AMLL 的「当前行更亮」不是靠调 opacity，而是**两层叠加 + 遮罩 alpha 双档**，全部由 CSS 类切换，零逐帧成本：

| 状态 | 主层（原件）opacity | 副本（`.lyricMainLineDim`）opacity | 逐词遮罩 bright/dark | 合成后 α |
| --- | --- | --- | --- | --- |
| 高亮 + 亮层已建 | 1（`.lyricMainLineBright`） | 0 | **1.0 / 0.4** | **0.85**（已唱）/ **0.34**（未唱） |
| 高亮 + 冷启动（无副本） | 1 | — | 1.0 / 0.4 | 同上 |
| 非高亮 + 有副本 | 0（`.lyricMainLineBright` 的 `opacity:0`） | **0.2** | 不参与（副本无 mask） | **0.20** |
| 非高亮 + 无副本 | 1 | — | **0.2 / 0.2** | **0.20** |
| 非逐词歌词 | 1 | 不启用 | 无 mask | group opacity **0.20** |

出处：`dom/line-brightness.ts:214-249`（克隆副本、移除副本的 mask）、`:359-386`（切亮层形态 + 两帧过渡抑制）、`:122-148`（冷启动 300ms 补间）、`styles/lyric-player.module.css:47-48`（`--bright-mask-alpha`/`--dark-mask-alpha` 默认 0.2）、`:153-184`（bright/dim/gradientMask 三组规则）、`dom/animation/mask/utils.ts:1-16`（渐变里用的就是这两个变量）。

**为什么搞两层**：逐词遮罩的渐变色标被固定成 1 / 0.4（注释 `line-brightness.ts:22-37` 明说），如果还要用遮罩表达「整行变暗」就得每帧重算渐变、重光栅化；改成「一个带遮罩的亮层 + 一个不带遮罩的均匀暗副本，两者只动 opacity」，就全交给合成器了。

**过渡时长**（这是「换行时旧行怎么暗下去」的真正参数）：

| 方向 | 时长 | 缓动 | 出处 |
| --- | --- | --- | --- |
| 进入高亮（暗→亮） | **300ms** | ease-out | `styles:174-184`（`.gradientMask > *` 覆盖 `transition-duration: 0.3s`） |
| 退出高亮（亮→暗） | **450ms** | ease-out | `styles:153-157`（`.lyricMainLineBright, .lyricMainLineDim { transition: opacity 0.45s ease-out }`） |
| 副本回收守卫 | 500ms | — | `dom/line-brightness.ts:21`（`BRIGHTNESS_SETTLE_MS`，注释要求 ≥450ms） |
| 冷启动补间 | 300ms | ease-out | `dom/line-brightness.ts:127-141`（dim 0.2→0，main 0→1） |

### 4.5 一张距离表（单行高亮、宽视口、动态歌词、正在播放）

设当前行索引 k（`scrollToIndex = latestIndex = k`）：

| 相对行 | isActive | scale | group α | 遮罩 α | 观感 α | blur |
| --- | --- | --- | --- | --- | --- | --- |
| k-4 及更远 | 否 | 0.97 | 1.0 | 0.2 | 0.20 | 5.0 |
| k-3 | 否 | 0.97 | 1.0 | 0.2 | 0.20 | 5.0 |
| k-2 | 否 | 0.97 | 1.0 | 0.2 | 0.20 | 4.0 |
| **k-1** | 否 | 0.97 | 1.0 | 0.2 | 0.20 | **3.0** |
| **k（当前行）** | 是 | **1.00** | **0.85** | **1.0 / 0.4** | **0.85 / 0.34** | **0** |
| **k+1** | 否 | 0.97 | 1.0 | 0.2 | 0.20 | **2.0** |
| k+2 | 否 | 0.97 | 1.0 | 0.2 | 0.20 | 3.0 |
| k+3 | 否 | 0.97 | 1.0 | 0.2 | 0.20 | 4.0 |
| k+4 及更远 | 否 | 0.97 | 1.0 | 0.2 | 0.20 | 5.0 |

窄视口（≤1024）把 blur 列 ×0.8：`k-1 → 2.4`、`k+1 → 1.6`、`k±2 → 3.2 / 2.4`……（当前行恒 0）。

多行同时高亮（句间重叠）时：`latestIndex > scrollToIndex`，`[scrollToIndex, latestIndex)` 之间的行 `isActive=true`（scale 1.0、无 blur），但只有真正在 `highlightedGroups` 里的行拿 0.85。

---

## 5. 纵向位置与 stagger（和「浮动」抢视觉的两件事）

### 5.1 目标 y

`base/layout.ts:381-401` + `:546-566`：

```kotlin
viewportStartY = containerH * alignPosition - anchorOffset - scrollOffset - focalTopY
y_i = viewportStartY + prefixSum[i]        // prefixSum = 行高前缀和（不含间奏点）
// 默认 alignAnchor = Center、alignPosition = 0.35（base/index.ts:120-124）
// → 焦点行的**中心**落在容器高度的 35% 处：y_focus = 0.35*H - lineH/2
```

注意 `setAlignPosition` 的文档写「默认 0.5」（`base/index.ts:562`），但字段默认是 **0.35**（`:122`）——以代码为准。

### 5.2 stagger（阶梯延迟，只有 posY 有）

`base/index.ts:967-998`：

```kotlin
var baseDelay = if (strategy.disableStagger) 0f else 0.05f        // 50ms
var delay = 0f
for (i in 0 until lineCount) {
    group.setTransform(..., delay, ...)
    if (y_i + lineH_i >= 0) {
        delay += baseDelay
        if (i >= scrollToIndex) baseDelay /= 1.05f
    }
}
```

效果：焦点行之上每行各 +50ms；焦点行之下，第 1 行 +50ms、第 2 行 +47.6ms、第 3 行 +45.3ms……（几何收敛）。`disableStagger` 在除 `PlaybackTick` 外的所有 `LayoutReason` 里都是 true（`base/consts.ts:87-129`）。

**重要**：这个 delay 只进 `posY.setTargetPosition(top, delay)` 和 `bgSlideY`（`group.ts:91-97`），**scale 不吃延迟**（`lyric-line.ts:631-635` 没有 delay 参数）。

---

## 6. 强调（emphasize）：行级弹簧 + 字符级脉冲

### 6.1 行级：97 → 100 的弹簧（这才是「当前行被选中时的放大」）

参数：`mass=2, stiffness=100, damping=25`（`base/index.ts:192-196`；背景人声行 `mass=1, stiffness=50, damping=20`，`:197-201`）。

`utils/spring.ts:141-176` 是解析解（欠阻尼用 `cos/sin × e^(-c/2m·t)`，ζ≥1 走临界阻尼式）。代入后用同一公式数值求解（本次实算）：

| 指标 | 值 |
| --- | --- |
| 阻尼比 ζ = c/(2√(km)) | **0.8839**（欠阻尼） |
| 10% / 50% / 90% / 99% 行程 | **0.074s / 0.223s / 0.472s / 0.693s** |
| 峰值过冲 | **0.26% 行程**（3 单位行程 → 0.008 个单位 → scale 96.99） |
| 是否可见回弹 | **否**（3% 的缩放变化里回弹 0.008%，肉眼不可见） |
| 是否会「先冲过头再回来」 | 数学上会，视觉上不会。要「弹」的感觉必须靠字符级脉冲 |

缓动**不是**贝塞尔，就是弹簧闭式解；`arrived()` 的收敛阈值是位置/速度/加速度都 <0.01（`utils/spring.ts:53-61`），到达后 `setPosition(target)` 硬置位（`:87-89`），避免长尾。

### 6.2 字符级脉冲（真正的「强调」）

触发条件（`base/line.ts:80-88`）：

```kotlin
fun shouldEmphasize(w: Word): Boolean =
    if (isCJK(w.word)) (w.endTime - w.startTime) >= 1000
    else (w.endTime - w.startTime) >= 1000 && w.word.trim().length in 2..7
```

参数（`dom/animation/emphasize/index.ts:105-145`）：

```kotlin
val de = max(0f, word.startTime - line.startTime)
var du = max(1000f, word.duration)
var amount = du / 2000f; amount = if (amount > 1) sqrt(amount) else amount.pow(3)
var blurV  = du / 3000f; blurV  = if (blurV   > 1) sqrt(blurV)  else blurV.pow(3)
amount *= 0.6f; blurV *= 0.5f
if (isLastWord) { amount *= 1.6f; blurV *= 1.5f; du *= 1.2f }
amount = min(1.2f, amount); blurV = min(0.8f, blurV)
// 每个字符的延迟：de + du/2.5/anchorCharCount * i   （anchorCharCount = ruby 字数 ?: 字符数）
```

包络（`empEasing`，`:9-21`）是**脉冲**，不是单调上升：

```
x < 0.5 : bezIn(2x)            bezIn  = cubic-bezier(0.2, 0.4, 0.58, 1.0)
x >= 0.5: 1 - bezOut(2x - 1)   bezOut = cubic-bezier(0.3, 0.0, 0.58, 1.0)
```

实算采样（x = 动画进度 0→1，每 1/8）：`0.000, 0.430, 0.739, 0.932, 1.000, 0.810, 0.431, 0.116, 0.000`——**前半程冲到 1，后半程退回 0**。

每个字符的 32 帧关键帧（`:149-172`）：

```kotlin
val transX = empEasing(x)
scale      = 1 + transX * 0.1f * amount
translateX = -transX * 0.03f * amount * (totalChars / 2f - charIndex)   // 字符间轻微推挤
translateY = -transX * 0.025f * amount                                   // em 为单位
textShadow = "0 0 " + min(0.3f, blurV * 0.3f) + "em rgba(255,255,255," + transX * blurV + ")"
```

时长/合成：glow `duration = du`、`composite: replace`；float `duration = du*1.4`、`composite: add`（`:193-242`）。

数值表（本次实算）：

| 词时长 | amount | blur | 峰值 scale | 辉光半径 / α | 上浮 |
| --- | --- | --- | --- | --- | --- |
| 1000ms | 0.075 | 0.019 | 1.008 | 0.006em / 0.019 | 0.002em |
| 2000ms | 0.600 | 0.148 | **1.060** | 0.044em / 0.148 | 0.015em |
| 3000ms | 0.735 | 0.500 | 1.074 | 0.150em / 0.500 | 0.018em |
| 5000ms | 0.949 | 0.645 | 1.095 | 0.194em / 0.645 | 0.024em |
| 2000ms（整行最后一个词） | 0.960 | 0.222 | **1.096** | 0.067em / 0.222 | 0.024em |
| 5000ms（整行最后一个词） | 1.200（上限） | 0.800（上限） | **1.120** | 0.240em / 0.800 | 0.030em |

### 6.3 它和 posY 弹簧什么关系

| 维度 | posY | scale | 字符 emphasize / float |
| --- | --- | --- | --- |
| 驱动 | 库自己的 rAF delta | **同一个** rAF delta | 浏览器动画时钟 |
| 目标设置时机 | `calcLayout` 里，带 stagger delay | `calcLayout` 里，**无 delay** | `enable()` 时对齐一次 |
| seek / 掉帧 | 受 `MAX_FRAME_DELTA=100ms` 钳制 | 同左 | **完全不受钳制**（浏览器自己走，掉帧后跳到正确相位） |
| 暂停 | 停在当前值 | 停在当前值 | `pause()` 显式暂停全部 |

所以严格说：**位移与缩放同一帧驱动；强调脉冲与它们独立**。如果音频时钟和 `document.timeline` 漂移，遮罩/脉冲会漂，位移不会。

## 7. 浮动（float）

### 7.1 实体

`dom/animation/float/index.ts:42-60`（**每个非强调词**一个，`lyric-line.ts:363-371` 创建）：

```kotlin
val delay = word.startTime - line.startTime                 // 相位 = 词在行内的起点
val duration = max(1000f, word.endTime - word.startTime)    // 时长 = 该词时长（下限 1s）
val up = if (isBG) 0.1f else 0.05f                          // em
el.animate(keyframes = [translateY(0), translateY(-up em)],
           options   = { duration, delay, composite: add, fill: both, easing: ease-out })
// 创建后立刻 pause()
```

### 7.2 幅度 / 周期 / 相位

| 维度 | 结论 |
| --- | --- |
| 幅度 | **0.05em**（背景人声行 0.1em）。以 20sp 字为例 ≈ 1sp / 2sp |
| 周期 | **没有周期**。一次性 0 → -up，ease-out，跑到 duration 就停在终点（fill: both） |
| 相位 | 由**词**决定（delay = word.start - line.start），**与行无关**——同一行里每个词各自起浮 |
| 随行变化 | 不随行变化；只有 BG 行幅度翻倍 |
| 是否叠加 | composite 为 add，与强调动画的 translateY **相加** |

### 7.3 生命周期（暂停 / 恢复 / 失焦）

| 事件 | 行为 | 出处 |
| --- | --- | --- |
| 创建 | pause()，currentTime = 0 → 停在 0（fill: both 回填首帧） | `float/index.ts:62` |
| 行成为高亮 | enable() 里 a.currentTime = clampPositive(now - lineStart)；未结束则 play() | `lyric-line.ts:121-136` |
| 播放器暂停 | line.pause() 逐个 a.pause() | `lyric-line.ts:191-199` + `dom/index.ts:206-213` |
| 播放器恢复 | line.resume() 里对未 finished 且 currentTime < end 的补 play() | `lyric-line.ts:168-189` |
| 行失焦 | disable()：playbackRate = -1; play() → **倒放回 0** | `lyric-line.ts:143-164` |
| seek | enable() 重新对齐 currentTime，但**只对齐一次**（之后靠浏览器时钟） | 同上 |

**边界 / 疑似 bug**：disable() 里判断 `a.id === "float-word" || a.id.includes("emphasize-word-float-only")`（`lyric-line.ts:151-158`），而强调浮动动画的实际 id 是 `"emphasize-word-float"`（`emphasize/index.ts:236`）——那个 -only 后缀从来没被生成过，所以**强调词的上浮在失焦时不会倒放**，只能靠 450ms 的亮层淡出把它一起盖过去。移植时自行决定是否修正。

**注意**：强调词（shouldEmphasize 为真）**不创建** float-word（`lyric-line.ts:363-371` 的三元），改由 emphasize 的 float 关键帧承担（§6.2 的 sin 包络）。

---

## 8. 模糊：实现与成本

### 8.1 实现

- 不是遮罩、不是阴影，是 **CSS `filter: blur(Npx)`**，写在**组外层**（.lyricLineWrapper），因此翻译/音译子行一起糊。
- 半径来自「档位」而不是几何距离（§4.3）。写入门槛 0.05px，最终 `min(5, level)`，`toFixed(2)`（`dom/lyric-group.ts:189-193`）。
- 过渡由 CSS 负责：.lyricLineWrapper 的 `transition: opacity 0.4s ease, filter 0.4s ease`（`styles:16-19`）。**JS 不逐帧改 filter**——只在目标值变化 ≥0.05 时写一次，之后交给样式引擎动画。
- 三个「永久关模糊」的开关：`enableBlur=false`、`isTouchScrolled`、`isFocused`（焦点行）；CSS 层再加一条「鼠标悬停整个播放器时 filter: unset !important」（`styles:344-348`，用来盖过内联样式）。

### 8.2 成本

| 事实 | 含义 |
| --- | --- |
| 模糊作用于**每个** wrapper（不是整屏后处理） | 成本 ∝ Σ(被模糊元素面积)。可见区通常 5–8 行 → 5–8 个离屏层 |
| 半径变化走 CSS transition | 【推断】Chromium 对 filter 的过渡在合成器侧完成，主线程不参与；即便退回主线程也不经过 JS |
| 与 will-change: transform 同层 | wrapper 本来就被提升为合成层，模糊只是给它加一个滤镜，不额外提升层级 |
| 上限 5px | 半径小 → 中间纹理小 → 采样开销低。这是「弱模糊」审美与性能的折中（5px 在大字号下明显偏弱） |
| 悬停/触摸即关 | 说明作者也知道逐层模糊是这套视觉里最贵的一项 |

### 8.3 对 Android 端（RenderEffect）的意味

| AMLL | Android | 注意 |
| --- | --- | --- |
| filter: blur(Npx) | view.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))（API 31+） | RenderEffect 作用在 RenderNode 的**本地坐标**，随后才应用 scale/alpha → 缩放会把半径一起缩（0.97 时差 3%，可忽略） |
| 半径每帧可微调 | **每次改半径都可能重建 effect** | 必须量化（我们已有 BidirectionalBlurPolicy.quantize → roundToInt） |
| 模糊向外扩散 | RenderEffect 会被 view 边界裁剪 | 需要 clipChildren=false + padding，或我们现有的 LyricBlurClippingLease |
| CSS 过渡 0.4s ease | ObjectAnimator / ViewPropertyAnimator 或自己插值 | 我们现有 LyricBlurTransition 是 300ms 线性；要 1:1 复刻得改成 400ms PathInterpolator(0.25,0.1,0.25,1) |
| API < 31 | 无 RenderEffect | 三条退路：①直接关模糊（最省）②setLayerType(LAYER_TYPE_SOFTWARE) + BlurMaskFilter（只在软件画布可用，极贵）③降采样位图再放大（贵且糊掉子像素）。**建议 <31 一律降级为「只调 alpha」** |
| 半径 0–5（CSS px） | dp | AMLL 的 5px 与字号无关；我们现有 host 方案用的是 0/13/17/22px（`core/.../BidirectionalBlurPolicy.kt:13-15`），两套参数**不可混用**，见 §10.3 |

补充三条（Android 侧，来自我们对 RenderEffect 的既有实践）：

1. `setLayerType(LAYER_TYPE_HARDWARE)` **不是必需的**：RenderEffect 直接挂在 RenderNode 上生效，手动加硬件层只会多一次离屏。
2. 反过来，`LAYER_TYPE_SOFTWARE` 会让 RenderEffect **完全失效**（软件画布没有 RenderNode effect 管线）——「RenderEffect + software layer」这个组合不存在。
3. 半径与 alpha 同时变化的行，Skia 会先离屏模糊再按 alpha 合成，成本 ≈ 面积 × 半径；≤5dp 基本安全，>8dp 且可见行多时掉帧明显【推断，未在目标机量】。

---

## 9. 逐词遮罩与「换行时上一行怎么收尾」

### 9.1 两套实现（按能力选择）

| 实现 | 触发条件 | 时钟 | 文件 |
| --- | --- | --- | --- |
| WebMaskAnimator | CSS.supports("mask-image") | WAAPI，duration = 行总时长，只在 enable() 设 currentTime | `mask/animator-web.ts` |
| CalcMaskAnimator | 不支持 mask-image | CSS calc() + 每帧写 --amll-player-time | `mask/animator-calc.ts` |

选路：`mask/index.ts:7-16`；supportMaskImage 由 `dom/index.ts:78` 探测。

### 9.2 关键帧算法（animator-web.ts:123-329，可直接照抄成 Kotlin 的「预计算」）

```
输入：所有词的 (startTime, endTime, width, padding, ruby[])
输出：一个 maskPosition 关键帧数组（整行共用一条时间轴，时长 = lineEnd - lineStart）

cursor = { curPos: initialPos, timeOffset: 0, lastPos: initialPos, lastTime: 0 }
push(初始帧)                                            // maskPosition = clamp(curPos)

for word in words:
    advancePause(word.startTime)                        // 停顿段：movePx = 0
    if word 有 ruby: 每个 ruby 片段按字数均分 width，逐字推进
    else:            movePx = word.width
                     if 第一个词:   movePx += fadeWidth * 1.5
                     if 最后一个词: movePx += fadeWidth * 0.5
                     advance(duration = word.duration, movePx)

advance(duration, movePx):
    timeOffset += duration / totalDuration
    curPos += movePx
    越界处插折点（curPos 跨过 minOffset / 0 时按 px 速度反推时间）
    push({ offset: clamp01(timeOffset), maskPosition: clamp(curPos, minOffset, 0) })
```

其中 `fadeWidth = word.height * wordFadeWidth`（默认 `wordFadeWidth = 0.5`，`base/index.ts:284`；setWordFadeWidth 文档建议 Android 用 1.0、iPad 用 0.5，`base/index.ts:351-353`），渐变由 `mask/utils.ts:4-16` 生成：mask-size = (2 + w) * 100%，亮/暗色标在 50 ∓ (w/(2+w))*50 %，颜色是 `rgb(0 0 0 / var(--bright-mask-alpha))` 和 `var(--dark-mask-alpha)`。

- 渐变宽度以**词高**为基准（不是字宽）：fadeWidth / (word.width + 2*padding) 才是渐变的相对宽度。
- 第一个词多走 1.5 个 fadeWidth、最后一个词多 0.5 个，保证两端「擦除」完整。

### 9.3 时钟关系（回答「是否共用时钟」）

**不共用。** 三个时间源：

1. maskAnimator.setCurrentTime(t - lineStart, isPlaying) 只在 enable() 调一次（`lyric-line.ts:138`），之后 WAAPI 自己按 document.timeline 推进。
2. 位移/缩放弹簧由 update(delta) 推进（掉帧会被 MAX_FRAME_DELTA 钳到 100ms）。
3. 明暗/模糊是 CSS transition。

后果：如果宿主推送播放进度的节奏与浏览器动画时钟有偏差，**遮罩会漂、位移不会**。AMLL 对此的补救只有「每次行切换重新对齐」；CalcMaskAnimator 那条路（每帧写 CSS 变量）才是严格同步的。

### 9.4 换行时序（旧行收尾 + 新行起手）

```
t=0     旧行 disable():
          遮罩冻结（pause）→ --bright-mask-alpha 从 1 掉到 0.2（瞬时，custom property 不可动画）
          → .gradientMask 移除 → 主层 1→0、副本 0→0.2 的 450ms ease-out 交叉淡出
          → scale 弹簧目标 100→97（与 posY 同帧启动，t90≈0.47s）
          → float-word 倒放（强调词不倒，见 §7.3）
t=0     新行 enable():
          遮罩 currentTime = now - lineStart（可能已经跑过一段）
          → 主层 0→1、副本 0.2→0 的 300ms ease-out 交叉淡入
          → scale 弹簧目标 97→100
t≈+300ms  新行到达 0.85
t≈+450ms  旧行落到 0.20
t≈+500ms  旧行若已离开亮度窗口 → 回收副本（BRIGHTNESS_SETTLE_MS）
```

补充边界：

- 如果旧行仍在 highlightedGroups 里（多行重叠 / 句间空隙），它**不会**被 disable，继续 0.85 亮着，直到有新行开始（`base/timeline.ts:455-473`）。
- 如果换行恰好触发 snapPosY（ContinuousScroll），posY 直接 setPosition 瞬移，但 **scale 弹簧仍会跑**（`group.ts:91-97` 只 snap 了 posY/bgSlideY）。
- enable() 的 shouldPlay 来自 getIsPlaying()；暂停时 setCurrentTime 后动画保持 pause，遮罩停在正确位置。

---

## 10. 可复刻性评估（Android / Kotlin）

### 10.1 属性映射

| AMLL | Android 对应 | 说明 |
| --- | --- | --- |
| wrapper translateY | itemView.translationY | **每帧写绝对值**；不要与 ViewPropertyAnimator 混用 |
| line scale | lineView.scaleX/scaleY + pivotX = 0f（对唱行 width）、pivotY = height/2f | 纯渲染变换，不触发测量；pivot 在 row 尺寸变化时要重设 |
| wrapper opacity | itemView.alpha | 400ms PathInterpolator(0.25,0.1,0.25,1)（= CSS ease） |
| filter: blur | setRenderEffect（31+）/ 降级 alpha | 见 §8.3 |
| 遮罩 alpha 1.0 / 0.4 | 文字颜色 alpha，或 LinearGradient shader + clipRect | 二值模型让「两层 + alpha」成为最便宜的等价物 |
| 亮层 / 暗副本 | 两个 TextView 叠放；或一个 TextView + 两段 alpha | mix-blend-mode: plus-lighter ≈ BlendMode.PLUS（需单独图层） |
| --bright-mask-alpha / --dark-mask-alpha | paint.shader 的两个 stop alpha | 高亮 1.0/0.4，静息 0.2/0.2 |
| BG 行 font-size 0.7em + scale 0.75 | setTextSize(0.7f * base) + scaleX/Y = 0.75f | 两者相乘 ≈ 0.525 |
| 子行 opacity 0.3 | 子行 alpha | 翻译/音译 |
| 字符强调 text-shadow | setShadowLayer(radiusPx, 0, 0, color) | radius 必须是 px；<= 0 表示关闭 |
| 字符强调 transform | 逐字符 View，或 StaticLayout 自绘 | **这是最大的移植成本**（见 10.4 第 6 条） |

### 10.2 参考骨架

```kotlin
// ---------- 1) 目标值（AMLL 口径） ----------
data class LineVisual(
    val scale: Float,      // 0.97f / 1.0f
    val alpha: Float,      // 0f / 1e-4f / 0.2f / 0.85f / 1f
    val blurPx: Float,     // 0f..maxBlur
    val bright: Boolean,   // true = 逐词遮罩 1.0/0.4；false = 0.2/0.2
)

fun lineVisual(i: Int, s: FrameState): LineVisual {
    val active = i in s.highlighted || (i >= s.scrollToIndex && i < s.latestIndex)
    val inViewport = i in s.viewport
    val alpha = when {
        !inViewport -> 0f
        s.hidePassedLines && s.playing && i < s.passedBoundary -> 1e-4f
        i in s.highlighted -> 0.85f
        s.nonDynamic -> 0.2f
        else -> 1f
    }
    val scale = if (s.enableScale && !active && s.playing) 0.97f else 1f
    val blur = when {
        !s.enableBlur || !inViewport || s.touchScrolled || active -> 0f
        else -> {
            val d = if (i < s.scrollToIndex) abs(s.scrollToIndex - i) + 1
                    else abs(i - s.latestIndex)
            val level = 1f + d
            val px = min(5f, if (s.narrow) level * 0.8f else level)
            px * s.blurDpPerLevel * s.density          // blurDpPerLevel 建议 1f
        }
    }
    return LineVisual(scale, alpha, blur, bright = i in s.highlighted)
}

// ---------- 2) 弹簧（utils/spring.ts:141-176 的直译） ----------
class Spring(var position: Float, var target: Float = position,
             var params: Params = Params()) {

    data class Params(val mass: Float = 1f, val damping: Float = 10f, val stiffness: Float = 100f)

    private var t = 0.0
    private var f: (Double) -> Float = { target }

    fun setTarget(v: Float) {
        if (abs(target - v) < 1e-3f) return
        val v0 = velocity(t)
        target = v; t = 0.0; f = solve(position, v0, target, params)
    }
    fun snap(v: Float) { target = v; position = v; t = 0.0; f = { target } }
    fun update(dtSec: Double) { t += dtSec; position = f(t) }

    private fun velocity(x: Double): Double {           // derivative.ts 的中心差分
        val h = 1e-3
        return ((f(x + h) - f(x - h)) / (2 * h)).toDouble()
    }
    private fun solve(from: Float, v0: Double, to: Float, p: Params): (Double) -> Float {
        val delta = (to - from).toDouble()
        val zeta = p.damping / (2.0 * sqrt(p.stiffness * p.mass))
        return if (zeta >= 1.0) {
            val a = sqrt((p.stiffness / p.mass).toDouble())
            val leftover = a * delta - v0
            { x -> (to - (delta + x * leftover) * exp(-a * x)).toFloat() }
        } else {
            val df = sqrt((4.0 * p.mass * p.stiffness - p.damping * p.damping).toDouble())
            val leftover = (p.damping * delta - 2.0 * p.mass * v0) / df
            val dfm = 0.5 * df / p.mass
            val dm = -0.5 * p.damping / p.mass
            { x -> (to - (cos(x * dfm) * delta + sin(x * dfm) * leftover) * exp(x * dm)).toFloat() }
        }
    }
}

// ---------- 3) 一帧 ----------
fun onFrame(dtMs: Long) {
    val dt = min(dtMs, 100L) / 1000.0                  // MAX_FRAME_DELTA = 100ms
    for (row in rows) {
        row.scaleSpring.update(dt)                     // mass 2 / damping 25 / stiffness 100
        row.posSpring.update(dt)                       // 按 policy 换参数
        row.alphaAnim.step(dtMs)                       // 400ms ease
        row.blurAnim.step(dtMs)                        // 400ms ease
    }
    for (row in rows) row.writeIfChanged()             // 门槛见 10.3
}
```

### 10.3 归一化（dp / sp）

| 量 | AMLL 的单位 | Android 建议 | 理由 |
| --- | --- | --- | --- |
| blur 半径 | CSS px（level 1..5，上限 5） | level * 1dp，上限 5dp（blurDpPerLevel 可调） | AMLL 的 px 与字号无关；我们现有 host 参数 13/17/22px 是另一套审美，别混 |
| 浮起 0.05em / BG 0.1em | em（相对字号） | 0.05f * textSizePx（含 fontScale） | 文字相对位移必须跟 sp 走 |
| 强调上浮 0.025em、推挤 0.03em | em | 0.025f * textSizePx | 同上 |
| 辉光半径 0.3em | em | 0.3f * textSizePx，用 setShadowLayer | shadow radius 是 px |
| 遮罩渐变宽度 | wordFadeWidth * 词高 | 0.5f * lineHeightPx | AMLL 以**词高**为基准，不是字宽 |
| stagger 50ms | ms | 不变 | 与密度无关 |
| 位移 / 行高 | px / 布局 px | dp |  |
| 写入门槛 | 0.001px / 0.05 / 0.0001 | 0.5dp / 0.01 / 0.001 | 手机 dpi 更高，AMLL 的门槛会变成「每帧都写」 |

### 10.4 性能坑

1. **模糊半径必须量化**。每帧给 setRenderEffect 一个新半径会不断重建 effect 描述符，并可能让 RenderNode 重新录制；LyricBlurEffectOwner 已经 roundToInt，保持这一点。
2. **模糊与缩放同时动画**时成本最高（换行瞬间正好如此：旧行 blur 2→3 且 scale 0.97→1 反向）。AMLL 也是这个时刻最贵；如果掉帧，优先冻结模糊变化（AMLL 的 isTouchScrolled 就是这个思路）。
3. **不要用两个写入者**：ViewPropertyAnimator.translationY + 弹簧写 translationY 会互相覆盖（历史分支 ac0da828 已经踩过「每帧写绝对值」）。
4. **RecyclerView 复用**：scaleX/alpha/renderEffect 必须在 bind 时按当前 state 重置，并在 recycle 时清 renderEffect；弹簧状态按 logical row（我们已有 Key(document, kind, nativeId)）而不是 View 存活。
5. **掉帧钳制**：MAX_FRAME_DELTA = 100ms 必须复刻，否则后台切回来的首帧会让弹簧「过冲一大截」。
6. **逐字符强调是最大的移植成本**：AMLL 每个字符是一个 span（`lyric-line.ts:321-341`），Android 的 TextView 做不到「逐字符 scale + translate」。三条路：①每个字符一个 TextView（只在强调词上做，词长 <= 7 且时长 >= 1s，数量可控）②StaticLayout + 自绘 + 手写逐字符变换（贵但可控）③**只做整体缩放 + 辉光**（视觉损失约 30%，工程量降低 90%）。建议先做 ③，A/B 后再决定。
7. **遮罩不是 CSS mask**：Android 用 LinearGradient + xfermode/clipPath 做逐词擦除时，**每帧改 shader** 很贵；AMLL 的解法（固定渐变 1/0.4 + 两层 alpha）正是为了回避这一点，照抄这个结构比照抄「渐变位置动画」便宜一个数量级。
8. **同帧提交**：translationY/scale/alpha 必须在同一个 doFrame 回调里写（Android 会在下一帧统一合成，不会撕裂）；但 setRenderEffect 尽量少写。
9. **暂停语义**：AMLL 暂停时把所有行 scale 回 1.0（会播一次 0.97→1 的弹簧）。如果不想要这个「暂停会涨一下」，在 isPlaying=false 时直接 snap。
10. **亮度层回收**：离开窗口 500ms 后才回收副本，回收时用「两帧 rAF 抑制 transition」避免闪（`line-brightness.ts:353-386`）。Android 上用字段 + post 两帧即可，但**我们仓库禁用 view.tag**，用 IdentityHashMap。

---

## 11. 参数总表（可直接抄进 Kotlin 常量）

| 参数 | 值 | 单位 | 出处 | 建议 Kotlin 默认 |
| --- | --- | --- | --- | --- |
| 非高亮 scale | 0.97 | 倍 | `base/group.ts:110` | 0.97f |
| BG 非高亮 scale | 0.75 | 倍 | `base/group.ts:127` | 0.75f |
| BG 字号 | 0.7 | em | `styles:139` | 0.7f |
| scale 弹簧 | mass 2 / stiffness 100 / damping 25 | — | `base/index.ts:192-196` | 同 |
| BG scale 弹簧 | mass 1 / stiffness 50 / damping 20 | — | `base/index.ts:197-201` | 同 |
| posY 弹簧（seek/间奏/首末行） | mass 0.9 / stiffness 90 / damping 15 | — | `base/spring.ts:4-9,50-55` | 同 |
| posY 弹簧（曲末） | 0.9 / 140 / 22 | — | `base/spring.ts:7-9,42-47` | 同 |
| posY 弹簧（播放，随行距） | stiffness = 170 + ratio*50，damping = 2.2*sqrt(k)，ratio = ((800-gap)/700)^0.2，gap 钳 [100,800]ms | — | `base/spring.ts:12-17,58-77` | 同 |
| 行 opacity：高亮 / 静息 / 非逐词 / 已唱 | 0.85 / 1.0 / 0.2 / 1e-4 | — | `base/index.ts:1044-1055` | 同 |
| blur 档位 | 1 + 距离（上方 +1），窄屏 *0.8 | 档 | `base/index.ts:1079-1086` | 同 |
| blur 上限 | 5 | px | `dom/lyric-group.ts:189` | 5f * density |
| 窄视口阈值 | 1024 | px | `base/index.ts:950` | 1024 * density |
| 遮罩 bright / dark alpha（高亮 / 静息） | 1.0/0.4 ; 0.2/0.2 | — | `styles:47-48,53-56,159-163` | 同 |
| 亮层进入 / 退出 | 300 / 450 | ms | `styles:174-184` / `:153-157` | 同 |
| 副本回收守卫 | 500 | ms | `dom/line-brightness.ts:21` | 同 |
| opacity / filter 过渡 | 400 | ms ease | `styles:16-19` | PathInterpolator(0.25,0.1,0.25,1) |
| stagger | 50（每行递减 1/1.05） | ms | `base/index.ts:967-970` | 同 |
| 帧 delta 上限 | 100 | ms | `base/consts.ts:136` | 100L |
| wordFadeWidth | 0.5（Android 建议 1.0） | x 词高 | `base/index.ts:284,351-353` | 0.5f |
| 强调条件 | 时长 >=1000ms；非 CJK 还要求 2..7 字符 | — | `base/line.ts:80-88` | 同 |
| 强调 amount 公式 | clamp(sqrt-or-cube(du/2000))*0.6，末词 *1.6，上限 1.2 | — | `emphasize/index.ts:116-133` | 同 |
| 强调 blur 公式 | 同上（du/3000），末词 *1.5，上限 0.8 | — | 同上 | 同 |
| 强调包络 | 0.5 处为峰；bezIn(0.2,0.4,0.58,1) / 1-bezOut(0.3,0,0.58,1) | — | `emphasize/index.ts:9-21` | 同 |
| 强调关键帧数 | 32 | — | `emphasize/index.ts:7` | 同 |
| 逐字符延迟 | du / 2.5 / 字符数 * i | ms | `emphasize/index.ts:94` | 同 |
| float 幅度 | 0.05em（BG 0.1） | em | `float/index.ts:45-48` | 同 |
| float 时长 | max(1000, 词时长)，ease-out | ms | `float/index.ts:43,58` | 同 |
| 焦点行位置 | 容器 35% 高度（anchor=center） | — | `base/index.ts:120-124` | 0.35f |
| overscan | 300 | px | `base/index.ts:123` | 300f * density |
| 回退行高 | 容器高 / 5 | px | `base/index.ts:166-169` | H / 5f |
| 亮度活跃窗口 | 焦点/高亮行 ±2 行 | 行 | `base/index.ts:59,961-962` | 同 |

---

## 12. 还没搞清 / 存疑

1. **emphasize-word-float-only 从未被生成**（§7.3）：disable() 里那个分支永远不命中，所以强调词的上浮不会回退。是历史遗留还是刻意，代码里没有注释；**没在真机/GUI 里对比过**。
2. **CSS filter 过渡是否走合成器**：这是【推断】。没有在 Chromium 里量过层数/帧时间，所以 §8.2 的成本结论只能当量级估计。
3. **mix-blend-mode: plus-lighter 的 Android 等价物**：BlendMode.PLUS 是预乘加法，plus-lighter 也是「plus」但色彩空间与裁剪规则不同；交叉淡入的那 450ms 里两者的差异会落在「白字过曝」上。没有做像素级对比。
4. **5px 上限的来历**：没找到注释或提交说明；无法判断是审美还是性能折中。移植时我按「1dp/档、上限 5dp」给，属于保守复刻。
5. **setAlignPosition 文档写默认 0.5、代码是 0.35**（§5.1）：文档漂移，行为以 0.35 为准，但不确定是不是「本该 0.5」的 bug。
6. **换行平衡器在 scale 动画期间测量**：`utils/line-balancer.ts:102-110` 会把祖先 transform 设成 none 再还原，理论上触发一次同步布局；对换行帧的耗时影响没量过。
7. **没有找到任何「按像素 dy 衰减」的亮度/模糊实现**：这点可以确证（整个 `lyric-player/` 里只有索引距离）。但 AMLL 的 demo 页面/主题是否会再叠一层 CSS 覆盖，我没有看（只读了 core 包）。
8. **字号完全交给宿主**（`styles/index.css:14-18`：max(max(5vh,2.5vw),12px)，窄屏 max(8vw,12px)），所以 blur/浮起/辉光的「观感」在 AMLL 里是随屏幕尺寸漂移的；Android 端必须重新标定，不能直接照抄 px。

---

## 13. 与 Apple 1626（宿主真机）的对照速记

| 维度 | Apple 1626 | AMLL（本笔记） |
| --- | --- | --- |
| 位移实现 | RecyclerView ItemAnimator 的 move（480–792ms，PathInterpolator(0.25,0.1,0.25,1)） | 弹簧（默认 90/15，播放时 170–220 / 2.2*sqrt(k)） |
| 位移时长来源 | 行间 gap 线性映射到 480..792ms | 行间 gap 映射到 stiffness（200ms 内基本到位） |
| 行缩放 | 只有 instrumental 行：1→1.2（750ms）再 →0.5+alpha0（250ms） | 所有非高亮行 0.97，弹簧过渡，没有「放大再消失」 |
| 逐词高亮 | 渐变蒙版 4 段 float 数组（A.m0），CJK 走 ValueAnimator | mask-position 关键帧 + 固定 1.0/0.4 双档 |
| 明暗 | 顶部/底部整屏渐变蒙版（AlphaGradientFrameLayout） | 逐行 filter: blur + 两层 alpha |
| 当前行停靠位置 | 顶部占位（x.a = round(0.06H) - dimen，见 01 笔记 §6） | alignPosition 0.35 + anchor center |

对照结论：**AMLL 是「弹簧 + 逐行 CSS 滤镜」的 web 风格实现，1626 是「ItemAnimator + 整屏渐变遮罩」的原生实现**。两者可以混用，但不要互相当作对方的「标准答案」：1626 的 480–792ms 是 move 时长，AMLL 的 200–400ms 是弹簧收敛，视觉上 AMLL 更「跟手」，1626 更「滑」。

---

## 14. 一句话总结（给实现者）

> 把「位移 + 缩放」做成每帧弹簧（同帧、同 delta、100ms 钳制），把「明暗 + 模糊」做成 300/450ms 的目标值过渡（**不要**逐帧算），把「逐词擦除」做成固定 1.0/0.4 渐变的 mask 而不是每帧改渐变，把「强调」做成 <=0.12 的字符级脉冲而不是行级放大——这四条就复刻了 AMLL 的 90% 观感。
