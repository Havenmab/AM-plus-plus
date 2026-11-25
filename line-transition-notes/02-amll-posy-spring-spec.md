# AMLL「运动核心」规格：posY 弹簧 / 换行时序 / 锚点

- 对象：`applemusic-like-lyrics` 只读克隆，HEAD `f329796`
- 代码位置：`packages/core/src/`，下文的 `file:line` 都相对该目录（省略前缀时）
- 目的：给 Kotlin/Android 端一份**可直接翻译**的规格（公式、默认值、边界条件、移植坑）
- 只读研究，未改 AMLL 源码；本文档只写在本工作树 `line-transition-notes/`

---

## 0. 三个关键结论（先看这里）

1. **posY 是「整块刚性平移」，不是「每行各自飞到自己位置」。** 每一行都有一个 `Spring`，但它们的参数完全一样（`setLinePosYSpringParams` 对所有 group 广播同一组 k/c），而一次换行时**所有行的目标位移量都等于同一个 Δ**（Δ = 前一焦点的行高 + 焦点行高变化量的一半；行高都一样时就是一行高）。数学上这等价于**一个标量弹簧驱动整个歌词块**。唯一打破等价性的是**阶梯延迟（stagger）**：正常播放时每一行比上一行晚 50 ms 才开始动（`base/index.ts:967-998`）。→ Kotlin 侧完全可以只维护 1 个 `blockPosY`，只有在需要复刻那种「手风琴式错峰」时才要每行一个。

2. **弹簧是闭式解（analytic），不是欧拉积分；帧率无关。** `utils/spring.ts:141-176` 用 MIT `pushkine/spring` 的解析解：位置 = 关于 `t`（距本次 re-anchor 的秒数）的函数，`update(dt)` 只是把 `t += dt` 再求值（`utils/spring.ts:69-90`）。所以 60 Hz / 120 Hz / 掉帧到 100 ms 采样得到的是**同一条曲线**。且**位移-时间曲线与位移幅度无关**（归一化后一致）：同样是 0.43 s 走完，跳 1 行和跳 30 行的时长一样、峰值速度差 30 倍。

3. **「换行」不是一个动画事件，而是时间线推导的副作用。** 当前行 = `scrollToIndex` = **高亮行集合里的最小索引**（`base/timeline.ts:466-473`）。它只在「有新行进入播放状态」时更新，且**不跟随逐帧时间**——它跟随每次 `setCurrentTime` 推送（`base/index.ts:643-654`）。歌词在空隙里**不会**提前滚到下一行（`packages/core/test/timeline-controller.test.ts:290-306` 明确锁定了这个行为）。→ 在 Android 上不要从 `ItemAnimator.animateMove` 反推当前行，直接按时间算，两者会不一致（尤其 seek 后）。

---

## 1. 每帧的数据流（谁在什么时候算什么）

```
宿主 progress tick（不等帧）
  setCurrentTime(t, isSeek)                 base/index.ts:643
    └ SeekDetector.detect(t, isPlaying)     base/seek-detector.ts:57   → 自动判定 seek
    └ syncTime(t, isSeek)                   base/index.ts:661
        ├ TimelineController.sync()         base/timeline.ts:304        → 产出 diff
        ├ if (!diff.hasChanged) return;     base/index.ts:671           ← 没有状态变化则整段跳过
        ├ enable/disable 高亮行
        ├ 可能 resetScroll()（seek 时）
        ├ updateSpringParams(...)           base/index.ts:816           ← 只有事件帧才换弹簧参数
        └ calcLayout(reason)                base/index.ts:857           ← 这里才写 posY 目标

宿主帧回调（每帧，约 16.6ms）
  update(delta)                             base/index.ts:1162
    └ DomLyricPlayer.update(delta)           dom/index.ts:224
        ├ d = min(delta, 100ms)              consts.ts:136 (MAX_FRAME_DELTA)
        ├ for group: group.update(d)          base/group.ts:149 → posY.update(d)
        └ for group: group.commitChanges()    dom/lyric-group.ts:175 → style.transform
```

要点：

- **布局（目标）是事件驱动的，动画（位置）是帧驱动的**。两者解耦，所以进度推送频率低也不会卡动画，只会让换行判定晚一点。
- `calcLayout` 虽然叫「计算布局」，它只算**目标 y**，不写像素；写像素发生在 `commitChanges → renderStyles`。
- 没有 `diff.hasChanged` 的帧不会跑 `calcLayout`，但**照样跑 `update(dt)`**（弹簧继续收敛）。

三个 `update` 的重载容易被误读，这里锁一下：

| 调用点 | 谁被更新 |
|---|---|
| `base/index.ts:1162` `LyricPlayerBase.update` | 只更新 bottomLine + interludeDots |
| `dom/index.ts:224` `DomLyricPlayer.update` | 先 `super.update`，再遍历所有 group（弹簧 + commitChanges） |
| `base/group.ts:149` `LyricLineGroupBase.update` | 该组 `posY`、`bgSlideY` 弹簧推进 → 标记 dirty |

---

## 2. posY 的目标值怎么算

### 2.1 一行的高度与前缀和

- 行高来自 `ResizeObserver` 实测，写入 `LayoutCalculator.setLineHeight(index, h)`（`base/layout.ts:265-274`）；
- 未测量的行用**回退高度** `defaultLineHeight = containerHeight / 5`（`base/index.ts:166-169`、`base/layout.ts:217-247`）；
- 之后维护前缀和 `prefix[i] = Σ heights[0..i-1]`，`totalLyricHeight = prefix[n]`（`base/layout.ts:452-463`）。

> 注意「行」= **歌词组 group**，不是 TTML 的原始行：主歌词行 + 它的背景人声行合成一个 group（`dom/index.ts:171-194`、`base/group.ts:45-53`）。`scrollToIndex`、`prefixSums`、`heights` 全部以 group 为索引。行高即该 group 的 DOM 高度（多行文本自动更高）。

### 2.2 焦点（focal target）→ focalTopY / anchorOffset

`base/layout.ts:507-544`：

| `target.type` | `focalTopY` | `targetHeight` |
|---|---|---|
| `line(index)` | `prefix[index]`（若间奏锚点在该行之前，再 `+ interludeTotalHeight`） | 该行高 |
| `interlude(anchorIndex)` | `prefix[anchorIndex + 1]` | `interludeTotalHeight` |
| `bottom` | `totalLyricHeight`（+ 间奏高度） | `bottomLineHeight` |

`anchorOffset` 由对齐锚点决定（`base/layout.ts:549-566`）：

```
Top    → 0
Center → targetHeight / 2      ← 默认
Bottom → targetHeight
```

### 2.3 视口原点与每行 y（核心公式）

`base/layout.ts:381-382`：

```
viewportStartY = H * alignPosition - anchorOffset - scrollOffset - focalTopY
lineY[i]       = viewportStartY + prefix[i] + (i > interludeAnchorIndex ? interludeTotalHeight : 0)
bottomLineY    = viewportStartY + totalLyricHeight + (间奏激活 ? interludeTotalHeight : 0)
interludeY     = viewportStartY + prefix[interludeAnchorIndex + 1]
```

即：**`lineY[i]` 就是 `posY` 的目标值**（`base/index.ts:980-988` → `group.setTransform(curPos, ...)` → `base/group.ts:95` `posY.setTargetPosition(top, delay)`）。

默认参数（`base/index.ts:120-124`）：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `alignAnchor` | `Center` | 焦点目标的**中心**对齐到基准线 |
| `alignPosition` | `0.35` | 基准线 = 容器高度的 35%（注意 `setAlignPosition` 的注释写 0.5，已过时） |
| `overscanPx` | `300` | 只影响渲染剔除，不影响几何 |

### 2.4 数值例（可直接当单测）

H = 800，alignPosition = 0.35 → 基准线 280；每行 40 高；alignAnchor = Center。

- 焦点 i：`focalTopY = 40i`，`anchorOffset = 20` → `viewportStartY = 280 - 20 - 40i = 260 - 40i`
- `lineY[j] = 260 + 40(j - i)` → 焦点行占据 [260, 300]，中心 280 ✓
- 焦点 i → i+1：**所有行 y 都减去 40**（行高）。这就是整块刚性平移的来源。

间奏例（anchor = 0，gap = 50）：`focalTopY = prefix[1] = 40`、`anchorOffset = 25` → `viewportStartY = 215`、`interludeY = 255`（间奏点占 [255, 305]，中心 280 ✓）、`lineY[1] = 215 + 40 + 50 = 305`。

### 2.5 首行 / 末行 / 内容不足的边界

- **不做任何「内容不满一屏就居中/吸附」的处理**：首行时焦点行照样在 0.35H（上面留白），末行时也一样（下面留白）。底栏紧随最后一行（`bottomLineY = viewportStartY + totalLyricHeight`），所以**歌词很短时底栏会停在屏幕中间**，不会掉到容器底部。
- **容器高度不足**不影响自动对齐，只影响用户可拖动范围：`beginFrame` 算出的 bounds 只用来钳制 `scrollOffset`（`base/layout.ts:333-347`）：

  ```
  minOffset = min(0, -focalTopY)
  maxOffset = max(0, (-focalTopY + H*alignPosition - anchorOffset) + totalContentHeight - H/2)
  ```

  两端都保证包含 0，所以用户永远能滑回自动对齐位置。
- **无歌词 / 索引越界**：`resolveLayoutMetrics` 判定 `isValid = false`（`base/layout.ts:478-490`），`commit` 返回 `lineCount = 0` 的空结果 → 不给任何行写目标（保持原位），底栏目标被写成 0。间奏目标额外要求 `anchorIndex` 落在 -1 到 行数-2 之间，即间奏不能挂在最后一行之后。
- **行高抖动**：首次布局时全部行用 H/5（=160），实测后 `setLineHeight` 改前缀和 → 下一帧目标整体变化 → 弹簧重新瞄准，会出现肉眼可见的呼吸。Android 侧务必**先测量再进场**。

---

## 3. 弹簧模型

### 3.1 `SpringParams`（`utils/spring.ts:5-10`）

```ts
interface SpringParams {
  mass: number;       // 默认 1.0
  damping: number;    // 默认 10.0
  stiffness: number;  // 默认 100.0
  soft: boolean;      // 默认 false —— 强制走临界阻尼分支
}
```

posY 的实参（`base/index.ts:187-191`）：

```ts
posYSpringParams = { mass: 0.9, damping: 15, stiffness: 90 }
```

**`mass` 从此固定为 0.9**：`getPosYSpringPolicy` 只返回 `{stiffness, damping}`，`setLinePosYSpringParams` 是 merge 而不是替换（`base/index.ts:1101-1112`）。这一点非常关键：**策略里的 stiffness 必须配 mass = 0.9 才有下面表里的阻尼比**。

### 3.2 `getPosYSpringPolicy` 完整公式（`base/spring.ts:27-78`）

常量（`base/spring.ts:4-17`）：

```
SLOW_STIFFNESS   = 90     SLOW_DAMPING   = 15
MEDIUM_STIFFNESS = 140    MEDIUM_DAMPING = 22
MIN_INTERVAL = 100   MAX_INTERVAL = 800
MIN_STIFFNESS = 170  MAX_STIFFNESS = 220
DAMPING_MULTIPLIER = 2.2
INTERVAL_EXPONENT  = 0.2
```

判定顺序（**短路，顺序不能改**）：

```
if (isSeeking || isInterludeActive) return { k: 90,  c: 15 }    // 模式 A：慢
if (isEndOfSong)                    return { k: 140, c: 22 }    // 模式 B：中
if (intervalMs == null)             return { k: 90,  c: 15 }    // 首行（无前一行）→ 同 A
// 模式 C：按行间隔
clamped = clamp(intervalMs, 100, 800)
ratio   = 1 - (clamped - 100) / 700          // 落在 [0,1]，间隔越长 ratio 越小
ratio   = ratio ^ 0.2                        // 开五次方根，把 ratio 抬向 1
k       = 170 + ratio * (220 - 170)
c       = 2.2 * sqrt(k)
```

`intervalMs` 的来源（`base/index.ts:825-835`）：

```
interval = currentGroup.startTime - currentLyricGroups[scrollToIndex - 1].startTime
```

是 **start 到 start**（不是行间空隙 end 到 start），并且 `startTime` 已经被 `optimize-lyric` 归一化成第一个词的开始时间（`utils/optimize-lyric.ts:41`）。

模式扫描（mass = 0.9，Δ = 100px 阶跃，数值由解析解算出）：

| interval | k | c | ζ = c/(2√(km)) | ω0 = √(k/m) | 分支 | t(99%) | 过冲 |
|---|---|---|---|---|---|---|---|
| ≤100 ms | 220.00 | 32.63 | 1.160 | 15.63 | 过阻尼式 | 0.425 s | 0 |
| 150 ms | 219.26 | 32.58 | 1.160 | 15.61 | 同 | 0.426 s | 0 |
| 300 ms | 216.75 | 32.39 | 1.160 | 15.52 | 同 | 0.428 s | 0 |
| 500 ms | 212.21 | 32.05 | 1.160 | 15.36 | 同 | 0.433 s | 0 |
| ≥800 ms | 170.00 | 28.68 | 1.160 | 13.74 | 同 | 0.484 s | 0 |
| **SLOW（seek/间奏/首行）** | 90 | 15 | **0.833** | 10.00 | 欠阻尼 | 0.428 s | **0.88%** |
| **MEDIUM（歌末）** | 140 | 22 | **0.980** | 12.47 | 欠阻尼（临界附近） | **0.509 s** | 0 |

三个反直觉但重要的结论：

1. **模式 C 的动态范围极小**：k 只在 170 到 220 之间，t(99%) 只从 0.484 s 变到 0.425 s（**14%**）。那个开五次方根把比例压得很平，实际听感差别主要靠 stagger（50 ms/行）而不是弹簧本身。移植时如果嫌公式麻烦，固定 k=200、c=2.2√200 的误差小于 3%。
2. **SLOW 并不比 MEDIUM 慢收敛**（0.428 s vs 0.509 s）。它的慢来自 ω0 = 10（最低）加 0.88% 的轻微过冲，是**软**不是**久**。而 MEDIUM 恰好卡在 ζ≈0.98，是三种里收敛最慢的。
3. 模式 C 的 ζ 恒等于 `2.2 / (2√0.9) = 1.1595`，跟 k 无关——这是**故意让 damping 跟着 √k 走以保持恒定阻尼比**。换成别的常量移植时请保留这个比例关系。

### 3.3 求解器（`utils/spring.ts:141-176`）

**不是半隐式欧拉，是解析解。** 记 Δ = to − from，v0 = 初始速度，ω = √(k/m)。

分支条件（`utils/spring.ts:153`）：`soft || 1.0 <= damping / (2*sqrt(stiffness*mass))`，即 **ζ ≥ 1 或 soft**。

- **ζ ≥ 1 分支**（`utils/spring.ts:154-160`），用临界阻尼形式（**注意：ζ > 1 时也套这个式子，不是双指数叠加**）：

  ```
  L      = -ω*Δ - v0
  x(t)   = to - (Δ + t*L) * e^(-ω t)
  ```

- **ζ < 1 分支**（`utils/spring.ts:162-175`）：

  ```
  ωd     = sqrt(4*m*k - c²)          // = 2mω√(1-ζ²)
  L      = (c*Δ - 2*m*v0) / ωd
  dfm    = 0.5*ωd/m
  dm     = -0.5*c/m
  x(t)   = to - (cos(t*dfm)*Δ + sin(t*dfm)*L) * e^(t*dm)
  ```

**速度与加速度不是解析求导，而是对闭式位置做中心差分**（`utils/derivative.ts:1-8`，h = 0.001 s）：

```
v(t) = (x(t+0.001) - x(t-0.001)) / 0.002
a(t) = (v(t+0.001) - v(t-0.001)) / 0.002
```

**收敛判定 `arrived()`（`utils/spring.ts:53-61`）**：

```
|target - current| < 0.01  &&  |v(t)| < 0.01  &&  |a(t)| < 0.01  &&  无排队项
```

三项都用同一个 0.01，但单位不同（px、px/s、px/s²）。因为 |a| 约等于 ω|v|，**加速度项实际决定了收敛时刻**：100 px 阶跃在 k=170 下 `arrived()` 要到 **约 1.25 s**（视觉上 0.48 s 就到位了），SLOW 更是 **1.68 s**。`arrived()` 之后 `update` 会 `setPosition(target)` 精确吸附（`utils/spring.ts:87-89`）。

> **移植建议**：不要照抄加速度阈值。Android 上直接用 `|x-T| < 0.01dp && |v| < 0.5dp/s` 就停帧，否则你会为了 1.2 s 的亚像素残差持续 `postFrameCallback` 并每帧写 `translationY`（还会让 View 一直 invalidate）。

**dt 来源与上限**：

- 宿主给的是 RAF 时间差（`packages/react/src/lyric-player.tsx:265-272`：`update(time - lastTime)`）；
- `MAX_FRAME_DELTA = 100ms`（`base/consts.ts:136`）双重钳制（`base/index.ts:1163`、`dom/index.ts:225`），防止页面挂起后首帧带着几秒的 delta 把弹簧炸出去；
- 因为解是解析的，**dt 只影响采样密度**，不影响曲线形状。掉帧只会看到更粗的轨迹。

### 3.4 re-anchor：`setTargetPosition` / `updateParams` 的语义

这是整个 Spring 实现里最容易被移植错的部分，逐条列清（`utils/spring.ts:40-52, 91-135`）：

1. **`setTargetPosition(T)`（无延迟）**：
   - 若 `|现目标 - T| < 0.001` → **清掉延迟队列并直接返回**（不改状态、不重置解）。这是「重复 layout 不打断动画」的关键。
   - 否则：清队列 → 换目标 → `resetSolver()`。
2. **`resetSolver()`**：取**当前速度** `curV = v(当前 t)`，把 `t` 归零，用 `(currentPosition, curV, 新目标)` 重新起一条解析解。→ **速度被保留**，所以换行时不会顿一下。这条决定了连续快速换行的顺滑度，Kotlin 必须照做（保存 `x0, v0, t, target` 四元组）。
3. **`setTargetPosition(T, delay>0)`**：只写 `queuePosition = {T, time}`，**不动当前目标**。`update(dt)` 里 `time -= dt`，到点才真正 `setTargetPosition(T)`。→ 这就是 stagger：行在延迟期间**完全不动**，然后开始弹。
4. **`updateParams(p, delay=0)`**：merge 参数并 `resetSolver()`（同样保留速度）。代码里 `delay > 0` 的分支（`queueParams`）**在 AMLL 内没有任何调用者**（`updateParams` 只在 `base/index.ts:1106-1110` 被无延迟调用），可视为死代码——顺带一提那个分支有个未清 `queueParams` 的缺陷，重启解会每帧触发一次；因为没人走这条路，没有实际影响。
5. **`setPosition(T)`（瞬移）**：位置/目标/速度全部归零，**但不清延迟队列**（`utils/spring.ts:62-68` 没写 `queuePosition = undefined`）。→ 上一帧排队的延迟目标仍会在到点后生效，可能把行拉回旧位置。**移植时务必在 snap 路径同时清队列**（这是我认为的 AMLL 潜在缺陷，在触摸拖动即 ContinuousScroll 与 stagger 叠加时理论上可复现）。

### 3.5 每行弹簧的初值：从屏幕下方飞入

- 每个 group 构造时 `posY.setPosition(window.innerHeight * 2)`（`dom/lyric-group.ts:34`），底栏同理（`dom/bottom-line.ts:61-64`）；
- `LayoutReason.RebuildView` 的 `snapPosY = false`（`base/consts.ts:114-118`，注释明确说了这是为了保留这个入场动画）。所以加载歌词时所有行从 2 屏高之外一起飞上来。
- 因为解析解对幅度归一化，**所有行同时到达**（目标不同但归一化进度相同），观感是整块从下面升上来。

---

## 4. 换行时序：谁把「当前行」推进

### 4.1 推进条件（`base/timeline.ts:392-474`）

每次 `setCurrentTime` 推送时（**不是每帧**）：

```
playing       = { i | startTime[i] <= t < endTime[i] }            区间命中
highlighted  ∪= newly playing
highlighted  -= { 已唱完且「有新行开始」的行 }                     ← 只有新行开始时才摘旧的
scrollToIndex = min(highlighted)                                  base/timeline.ts:466-473
```

所以：

- 一行唱完后**不会**立刻失去高亮，也不会立刻滚走；它会一直亮到下一行开口（`base/timeline.ts:455-464` 的注释写得很清楚）；
- 空隙期间焦点停在**上一行**（`packages/core/test/timeline-controller.test.ts:290-306`：`tick(seeked, 2000, true)` → highlighted = [0]、scrollToIndex = 0）。**不要**实现成总是对齐到下一句待唱。
- 多行重叠（合唱 / 对唱）时 `scrollToIndex = min`，即最上面那行，直到它不再高亮。
- `isEndOfSong = t >= max(endTime)`（`base/timeline.ts:326-327`）。

### 4.2 目标更新 vs 运动开始

| 阶段 | 时机 |
|---|---|
| `scrollToIndex` 变化 | 该次 `setCurrentTime` 内（同步，非帧） |
| `posY` 目标写入 | 同一调用栈里的 `calcLayout`（`base/index.ts:721-723`），reason 为 `Seek` 或 `PlaybackTick` |
| 弹簧参数切换 | 同栈、在 `calcLayout` **之前**（`base/index.ts:708-719`），且**只在**间奏变化 / scrollTo 变化 / 歌末变化 / 时间跳变时执行 |
| 实际开始移动 | 下一帧 `update(dt)`；带 stagger 时再晚 `delay_i` |

**stagger 细节（`base/index.ts:967-998`）**：

```
baseDelay = 50ms（PlaybackTick 时；其它 reason 下 disableStagger → 0）
delay = 0
for i in 0..n-1:
   if (lineY[i] + height[i] >= 0):      // 该行底边还没完全跑到容器顶部之上
        delay  += baseDelay              // 先把 delay 加给当前行，再给下一行
        apply lineY[i] with delay
        if (i >= scrollToIndex) baseDelay *= 1/1.05      // 焦点以下增量几何衰减
   else:
        apply lineY[i] with delay（不加）
```

即：**相对延迟约 50 ms/行，向下按 1/1.05 衰减（离焦点第 10 行约 31 ms/行），焦点以上恒 50 ms**。累积效果是一列手风琴：换行瞬间块被轻微拉伸再收回。`LayoutReasonStrategyMap`（`base/consts.ts:87-129`）里 `disableStagger` 只有 `PlaybackTick` 是 false。

> 也就是说：**只有正常播放换行有错峰；seek / resize / 交互 / 重建视图都没有**。

### 4.3 与 Apple 1626 的对照（供本项目取舍）

- Apple 是**时长驱动**：`moveDuration = round(lerp(480, 800, clamp((gap-200)/550, 0, 1)))`，按 **gap（下一行 begin − 当前行 end）** 映射（见 `01-lyrics-line-transition-1626.md:434`）。
- AMLL 是**弹簧驱动**，且按 **start 到 start 的间隔**映射，t(99%) 只有 0.425 到 0.484 s —— **几乎正比于 Apple 的下限 480 ms**，缺少 Apple 那 320 ms 的长间隔延展。
- 两者都有约 50 ms 的错峰（Apple 侧来自 LyricsBlossom 先例的 `lineDelay`，AMLL 侧是 50 ms × 行）。
- 想要更像 Apple：把模式 C 的慢端从 k=170（t99 约 0.48 s）拉到 k 约 55（t99 约 0.85 s），快端保持 220；或者干脆改用 Apple 的时长曲线 + 同一套解析解反解 k（临界阻尼附近 k 约等于 (6.64/t)²·m，t 为期望的 99% 收敛时间）。

---

## 5. 特殊状态

### 5.1 Seek（拖动进度条 / 点击某行）

- 判定有三条路径（`base/index.ts:383-397`）：显式 `isSeek`、**进度倒退**（无条件）、`SeekDetector` 自动推导（超量前进）。探测器参数：`JITTER_TOLERANCE = 150ms`、`DRIFT_SLACK = 0.5`、`MAX_TRUSTED_GAP = 800ms`（`base/seek-detector.ts:13-23`）。
- **seek 不瞬移 posY**：`LayoutReason.Seek` 的 `snapPosY = false`（`base/consts.ts:108-112`）；只有 `ContinuousScroll` 才 `snapPosY = true`。→ seek 用慢速弹簧把块滑到新位置，**跳 30 行和跳 1 行都是 0.43 s**，峰值速度与跳行数成正比。观感上远距离 seek 会明显甩一下。
- 但 `scrollOffset`（用户手动滚动量）**是硬清零的**：`if (isTimeJumped && !isTouchScrolled) resetScroll()`（`base/index.ts:693-696`）。所以「用户滚过 + seek」时会出现目标瞬间跳到新位置（因为坐标系被换掉了）。
- `updateSpringParams` 此时传 `isSeeking = true` → k=90 / c=15。
- 时间线侧 `performSeek`（`base/timeline.ts:485-557`）用二分重建 playing / highlighted，结果与正常播到该时刻一致（测试 `timeline-controller.test.ts:308-351` 专门锁定这一点）。

### 5.2 暂停 / 恢复

- `pause()` 只做三件事（`base/index.ts:1138-1144`）：停间奏点演出、置 `isPlaying = false`、`calcLayout(ConfigChange)`。
- **弹簧不会冻结**：宿主 RAF 还在跑，`update(dt)` 照常推进 → 未完成的换行会继续滑到位（这是对的）。
- `isPlaying = false` 的视觉副作用（`base/group.ts:102-137`）：非激活行**不再缩小**（97 → 100），背景人声行滑出位移归 0（显示出来）。
- 恢复不重置任何弹簧状态；时间线靠 `seekDetector` 的基线判断「暂停期间进度前进」是否算 seek（算，因为期望推进量为 0，任何超过 150 ms 的前进都是 seek）。
- 页面不可见时 `DomLyricPlayer.update` 在 `super.update` 之后 `return`（`dom/index.ts:233`）→ **group 弹簧停更**，底栏 / 间奏点仍更新。恢复时 delta 已被 100 ms 钳制，不会暴走。

### 5.3 歌曲结束

- 焦点切换：`isEndOfSong` 且底栏有内容 → `bottom` 类型；否则最后一行（`base/focus.ts:124-133`）。
- 参数：`{k:140, c:22}`（模式 B，`base/spring.ts:42-47`）。
- 底栏目标 `bottomLineY = viewportStartY + totalLyricHeight`，焦点在底栏时 `anchorOffset = bottomLineHeight/2` → 底栏中心对齐 0.35H。
- 结束时清空全部高亮（`base/timeline.ts:341-343`），并给底栏加 `isFocused`（`base/index.ts:1001-1002`，影响渐变遮罩）。
- `isEndOfSong` 是粘性的，直到 seek 回去。

### 5.4 间奏（instrumental）

- **判定**：任意两行之间空隙 ≥ `MIN_INTERLUDE_GAP = 7000 ms`（`base/timeline.ts:6`、`base/timeline.ts:611-641`），用「前缀最大结束时间」做区间并集，保证区间不与任何行重叠。`anchorLineIndex = i` 是间奏前最后一行；`-1` 表示第一句之前（开场长前奏）。
- **画面**：在 anchor 行之后插入一段 `interludeTotalHeight` 的空隙（`base/index.ts:888-891`）：

  ```
  dotMargin = fontSize * 0.4
  interludeTotalHeight = 间奏点元素实测高度 + 2 * dotMargin
  ```

  间奏点元素高度是 CSS `clamp(0.5em, 1vh, 3em)` 加上下各 `0.4em` padding（`styles/lyric-player.module.css:258-278`），实务上约 1.6 到 2.0 个字高。
- **锚点怎么走**：进入间奏后高亮被清空 → `isFocusOnInterlude = true`（`base/timeline.ts:706-709`）→ 焦点变成 `interlude(anchorIndex)` → `focalTopY = prefix[anchor+1]`、`targetHeight = interludeTotalHeight`。代入 §2.3 可得两段位移：

  ```
  Δy = -(gap + h_anchor)/2      // anchor 行及其以上的行：整体上移
  Δy =  (gap - h_anchor)/2      // anchor 之后的行：被插入的空隙顶下去
  ```

  不变量很干净：**间奏点中心正好落在 0.35H，且它的上边缘正好贴住 anchor 行的下边缘**（间奏点占 [0.35H − gap/2, 0.35H + gap/2]，anchor 行占 [0.35H − gap/2 − h, 0.35H − gap/2]）。gap 约为 2 个字高、行高约 1.6 个字高时 (gap − h)/2 是个小正数，所以视觉上主要看到整块往上让位。
- **参数**：`isInterludeActive` → 模式 A（k=90 / c=15），并在 `isInterludeChanged` 那一帧切换。间奏持续期间块不动（目标恒定），只有间奏点自己呼吸。
- **离开间奏**：`ctx.interlude = undefined` → 空隙消失，上面两段位移按相反方向还原（焦点变回 anchor+1 那行）；参数回到模式 C。
- **间奏点自身**：`InterludeDotsBase` 有独立的 `posY` 弹簧（`base/interlude-dots.ts:106-112`），且第一次 `setTransform` 会 **snap**（`shouldSnapPosY`，避免从旧位置滑入），之后才走弹簧。它的演出时钟由 `syncClock(mediaTime)` 每次推送对齐 + `update(dt)` 累加（`base/interlude-dots.ts:304-336`、`base/index.ts:666-669` 明确解释了为什么两者都要）。
- 间奏点不可显示时 `setInterlude` 返回 false（门槛是 `bodyMs = 剩余时长 − delayEnd − 1000ms < 910ms`，即空隙至少要 2410 ms；前奏 anchor = -1 时 delayEnd 为 0，门槛降到 1910 ms，见 `base/interlude-dots.ts:174-196`） → `canDisplayInterlude = false` → 焦点顺延到下一行，**不发生间奏排版**（`base/focus.ts:107-117`）。

### 5.5 用户手势

- 触摸滑动超过 10 px 或滚轮 → `onInteractionStart` → `isAutoAlignSuspended = true`，焦点被 `FocusController` **冻结**在上一帧目标（`base/focus.ts:63-96`）。
- 连续拖动（touch / 惯性 RAF）用 `snapPosY = true` 直接写位置（跟手）；滚轮的离散步进保留弹簧。
- 惯性：速度按 `0.95^(dt/16.67ms)` 衰减，单帧 dt 超过 100 ms 丢弃该步（`base/scroll.ts:247-283`）。
- 松手 500 ms 后（`AUTO_ALIGN_RESUME_DELAY_MS`）且新行在渲染范围内，才恢复自动对齐（`base/index.ts:697-706`）。

---

## 6. Kotlin 移植规格

### 6.1 直接可用的 Spring

```kotlin
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

data class SpringParams(
    val mass: Float = 1f,
    val stiffness: Float = 100f,
    val damping: Float = 10f,
    val soft: Boolean = false,
)

/** 1:1 复刻 packages/core/src/utils/spring.ts */
class Spring(initial: Float = 0f) {
    var params: SpringParams = SpringParams()

    private var x0 = initial          // 本次 re-anchor 的起点
    private var v0 = 0f               // 本次 re-anchor 的初速度
    private var target = initial
    private var current = initial
    private var t = 0f                // 秒，距 re-anchor

    private var queued = Float.NaN    // 延迟目标
    private var queuedIn = 0f         // 剩余秒数

    private var pos: (Float) -> Float = { target }
    private var vel: (Float) -> Float = { 0f }
    private var acc: (Float) -> Float = { 0f }

    /** snap：注意同时清延迟队列（AMLL 漏清，别抄这个 bug） */
    fun setPosition(v: Float) {
        x0 = v; current = v; target = v; v0 = 0f; t = 0f
        pos = { target }; vel = { 0f }; acc = { 0f }
        queued = Float.NaN; queuedIn = 0f
    }

    fun setTargetPosition(next: Float, delaySec: Float = 0f) {
        if (delaySec <= 0f) {
            if (abs(target - next) < 0.001f) { queued = Float.NaN; return }   // 目标没变就别打断动画
            queued = Float.NaN
            target = next
            reanchor()
        } else {
            queued = next
            queuedIn = delaySec
        }
    }

    fun updateParams(p: SpringParams) {
        params = p
        reanchor()
    }

    private fun reanchor() {
        val curV = vel(t)      // ← 保留速度，这是换行顺滑的关键
        t = 0f
        x0 = current
        v0 = curV
        buildSolver()
    }

    private fun buildSolver() {
        val m = params.mass
        val k = params.stiffness
        val c = params.damping
        val d = target - x0
        if (params.soft || c >= 2f * sqrt(k * m)) {              // ζ >= 1 或 soft
            val w = -sqrt(k / m)
            val l = -w * d - v0
            pos = { tt -> target - (d + tt * l) * exp(tt * w) }
            vel = { tt -> -exp(tt * w) * (l + w * (d + tt * l)) }
        } else {                                                 // ζ < 1
            val wd = sqrt(4f * m * k - c * c)
            val l = (c * d - 2f * m * v0) / wd
            val dfm = 0.5f * wd / m
            val dm = -0.5f * c / m
            pos = { tt -> target - (cos(tt * dfm) * d + sin(tt * dfm) * l) * exp(tt * dm) }
            vel = { tt ->
                -exp(tt * dm) * (dfm * (-d * sin(tt * dfm) + l * cos(tt * dfm)) +
                                 dm * (d * cos(tt * dfm) + l * sin(tt * dfm)))
            }
        }
        acc = derived(vel)
    }

    /** 与 AMLL derivative.ts 一致：中心差分，h = 1ms */
    private fun derived(f: (Float) -> Float): (Float) -> Float =
        { tt -> (f(tt + 0.001f) - f(tt - 0.001f)) / 0.002f }

    fun update(dtSec: Float) {
        t += dtSec
        current = pos(t)
        if (!queued.isNaN()) {
            queuedIn -= dtSec
            if (queuedIn <= 0f) {
                val q = queued
                queued = Float.NaN
                setTargetPosition(q)
            }
        }
        if (arrived()) setPosition(target)
    }

    fun arrived(): Boolean =
        abs(target - current) < 0.01f &&
            abs(vel(t)) < 0.01f &&
            abs(acc(t)) < 0.01f &&
            queued.isNaN()

    fun currentY(): Float = current
}
```

要点复述：**四元组 `x0 / v0 / t / target` 加解析求值，换目标时保留 v0**。绝不要用 `DynamicAnimation` 或逐帧欧拉——两者都无法在换行瞬间保留速度，且帧率相关。

### 6.2 帧循环

```kotlin
private var lastFrameNs = -1L
private val choreographer = Choreographer.getInstance()

private val frameCallback = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
        val dtMs = if (lastFrameNs < 0) 0f else (frameTimeNanos - lastFrameNs) / 1_000_000f
        lastFrameNs = frameTimeNanos
        blockPosY.update(min(dtMs, 100f) / 1000f)      // MAX_FRAME_DELTA
        applyTranslation(blockPosY.currentY())
        if (!blockPosY.arrived()) choreographer.postFrameCallback(this)   // 收敛即停帧
    }
}
```

- 数据源用 `frameTimeNanos`（vsync 时间戳，单调，等价于 web 的 `requestAnimationFrame` 时间），不要用 `System.nanoTime()` 自己算——那会跟渲染时刻错位。
- **只在有运动时 post**：AMLL 是常驻 RAF（每帧都跑），Android 上常驻会一直唤醒 GPU/CPU。用 `arrived()` 作闸门，但阈值按 §3.3 的建议改。
- 写入端：`view.translationY = y`（或整块容器的 `translationY`）。AMLL 会做 `|Δ| >= 0.001` 的写入去重（`dom/lyric-group.ts:179-181`），Android 建议取整到 0.01dp 再比较，避免无意义 invalidate。

### 6.3 单弹簧（块级）还是每行弹簧

| 方案 | 何时够用 | 代价 |
|---|---|---|
| **A. 一个 `blockPosY`** | 关掉 stagger（等价于 AMLL 除 `PlaybackTick` 以外的所有场景） | 1 个标量、1 次 `translationY` 写在容器上；**与 AMLL 逐行弹簧在数学上完全等价**（所有行 Δ 相同、参数相同） |
| **B. 每行一个 `Spring`** | 要 1:1 复刻 `PlaybackTick` 的错峰 | N 个标量；只有可见行需要（上下各 2 行），不超过 10 个 |

B 的等价实现更省：**延迟不必真排 N 个队列**——按 §4.2 的公式直接算出 `delay_i`，再让每行的弹簧从 `t = max(0, now - start_i - delay_i)` 求值即可（解析解允许任意时刻求值，不需要逐帧累加）。

> 落到本项目的 RecyclerView 路线：`translationY_i = blockPosY + (targetLayoutY_i - row.layoutTop)`。`targetLayoutY_i` 用 §2.3 的公式从焦点索引算出来，`row.layoutTop` 是宿主布局给的位置——**只接管偏移量，不接管布局**，这与 1626 的 `animateMove` 拒绝策略是相容的（见 `01-line-change-motion-feasibility.md` 方案 A）。

### 6.4 哪些参数要按 dp 归一化

**不需要归一化**（无量纲或时间）：

- `k` / `c` / `m`：`x'' = (-k(x-T) - c x') / m` 对位置是**线性**的；位置单位整体乘 s（dp 转 px），轨迹在时间轴上完全不变。**所以 §3.2 的表可以直接用，dp 或 px 都行**（前提是位置和速度用同一套单位）。
- 所有时间常量：`MAX_FRAME_DELTA = 100ms`、stagger 50 ms、`AUTO_ALIGN_RESUME_DELAY_MS = 500`、`WHEEL_IDLE_TIMEOUT_MS = 150`、`MIN_INTERLUDE_GAP = 7000ms`、间奏点演出各阶段（500 / 750 / 250 / 180 / 150 ms）。
- 所有比例：`alignPosition = 0.35`、`dotMargin = 0.4em`、`defaultLineHeight = H/5`、`motionBuffer = 0.4H`、`0.95^frames` 惯性、缩放 97 / 100 / 75。

**需要换算**（AMLL 里写死的绝对像素）：

| 常量 | AMLL 值 | Android 建议 |
|---|---|---|
| `overscanPx` | 300（CSS px） | 仅影响剔除，用 1 屏高或 300dp 都行；注意 **1 CSS px 约等于 1.667 dp**（1/96 in 对 1/160 in） |
| 模糊强度 | `min(5, level)` px | Android 无同价 blur；要么 `RenderEffect`（API 31+），要么改用 alpha / 缩放替代 |
| 手势意图阈值 | 10 px | `ViewConfiguration.get(context).scaledTouchSlop` |
| 飞入初始位置 | `window.innerHeight * 2` | `2 * screenHeight` |
| 底栏 reset 位置 | 同上 | 同上 |

### 6.5 一个必须自己决定的取舍

AMLL 的 seek 是**慢弹簧滑过去**（0.43 s，距离无关）。在 Android 上点歌词行 seek 时，如果跨度 30 行（30 × 40 px = 1200 px），峰值速度约 6000 px/s，观感更像闪一下。建议：**seek 时按距离分档**——`|Δy| < 2 行` 走弹簧，否则 `setPosition` 直接落位（Apple 1626 也是瞬间跳，再由宿主 `smoothScrollToPosition` 处理落点）。

---

## 7. 参数总表（Kotlin 常量）

```kotlin
object LyricMotionDefaults {
    // 几何
    const val ALIGN_POSITION = 0.35f          // 焦点基准线 = 0.35 * 容器高
    const val DEFAULT_LINE_HEIGHT_RATIO = 1f / 5f
    const val OVERSCAN_DP = 300f
    const val INTERLUDE_DOT_MARGIN_EM = 0.4f
    const val MIN_INTERLUDE_GAP_MS = 7000L

    // 弹簧（mass 恒定 0.9）
    const val MASS = 0.9f
    // A. seek / 间奏 / 首行
    const val SLOW_K = 90f
    const val SLOW_C = 15f                    // ζ = 0.833，ω0 = 10.0
    // B. 歌末
    const val MEDIUM_K = 140f
    const val MEDIUM_C = 22f                  // ζ = 0.98，ω0 = 12.5
    // C. 正常播放
    const val MIN_INTERVAL_MS = 100f
    const val MAX_INTERVAL_MS = 800f
    const val MIN_K = 170f
    const val MAX_K = 220f
    const val INTERVAL_EXPONENT = 0.2f
    const val DAMPING_MULTIPLIER = 2.2f       // c = 2.2*sqrt(k) → ζ 恒 1.1595

    // 时序
    const val MAX_FRAME_DELTA_MS = 100f
    const val STAGGER_STEP_MS = 50f
    const val STAGGER_DECAY = 1f / 1.05f
    const val AUTO_ALIGN_RESUME_DELAY_MS = 500L

    // 收敛
    const val EPS_POSITION = 0.01f            // px / dp
    const val EPS_VELOCITY = 0.01f            // px / s
}
```

---

## 8. 还没搞清 / 需要真机验证

1. **`mass` 是否被外部覆盖**：React / Vue 绑定暴露了 `linePosYSpringParams`（`packages/react/src/lyric-player.tsx:345-348`），业务层传了别的 mass 就会改变所有 ζ。我按没人传处理，默认 0.9。
2. **stagger 的观感贡献**：50 ms/行 的错峰在 0.45 s 的收敛时间里占 11%，我无法从代码判断它是刻意的手风琴设计还是历史遗留。需要在真机上用「关 stagger / 开 stagger」对比才能定性。若要复刻 Apple 的「整块平移 + 每行固定 duration」，这个 delay 应该去掉。
3. **`arrived()` 的加速度阈值对渲染的影响**：AMLL 里它只驱动 `isUiDirty`，`commitChanges` 仍会被每帧调用，实际样式写入被 `|Δ|>=0.001` 挡住；换算成 Android 会不会造成额外 invalidate，需要量 `dumpsys gfxinfo`。
4. **`posY` 在 120 Hz 上的表现**：解析解理论上帧率无关，但 `Duration` 是毫秒浮点，`setLinePosYSpringParams` 每次事件都 `resetSolver`，高频推送（如 30 ms 一次进度）会不会引起速度抖动，需要看真机。
5. **间奏点元素实测高度**：`clamp(0.5em, 1vh, 3em)` 依赖字体大小与视口高度，我没在真机量过最终 gap 的 dp 值。按 H=800 / font=24 估约 50 px，但这是推算不是实测。
6. **首行的 `intervalMs == null` 兜底**：它让「切换到第一行」永远用慢速弹簧。新歌从头播放时没问题，但 seek 到 0 附近时会让「回到第一行」比其它换行更软——是否符合预期需要产品判断。

---

## 9. 文件索引（按阅读顺序）

所有路径相对 `packages/core/src/`（最后两行除外）。

| 文件 | 关键行 | 作用 |
|---|---|---|
| `utils/spring.ts` | 5-10, 40-90, 91-135, 141-176 | SpringParams / 队列与 re-anchor / 闭式解 |
| `utils/derivative.ts` | 1-8 | 中心差分求速度 / 加速度 |
| `lyric-player/base/spring.ts` | 4-17, 27-78 | posY 弹簧策略（三种模式 + 间隔映射） |
| `lyric-player/base/layout.ts` | 306-348, 358-423, 507-544, 549-566 | 前缀和 / 视口原点 / 焦点度量 / 锚点偏移 |
| `lyric-player/base/focus.ts` | 47-58, 101-148 | 焦点状态机（冻结 / 间奏 / 歌末 / 钳制） |
| `lyric-player/base/timeline.ts` | 304-385, 392-474, 485-557, 611-641, 706-709 | 时间线推进 / 高亮 / seek 重建 / 间奏区间 |
| `lyric-player/base/consts.ts` | 33-41, 87-129, 136 | 锚点枚举 / LayoutReason 策略表 / dt 上限 |
| `lyric-player/base/index.ts` | 120-124, 166-169, 187-191, 643-724, 816-845, 857-1016, 1101-1112, 1138-1166 | 播放器基类：配置 / 进度同步 / 策略 / calcLayout / 弹簧广播 / 暂停 |
| `lyric-player/base/group.ts` | 20-21, 64-100, 149-163 | 每行组：setTransform / update / 缩放与背景滑移 |
| `lyric-player/base/scroll.ts` | 26-35, 247-283, 338-365 | 手势 / 惯性 / 边界钳制 |
| `lyric-player/base/seek-detector.ts` | 13-23, 57-97 | seek 自动推导 |
| `lyric-player/dom/index.ts` | 171-204, 224-241 | group 构建 / 每帧 update 与 commit |
| `lyric-player/dom/lyric-group.ts` | 34, 43-58, 175-182 | 初始 posY = 2H / 渲染剔除 / 写 transform |
| `lyric-player/dom/bottom-line.ts` | 61-64, 83-136 | 底栏弹簧与写入 |
| `lyric-player/base/interlude-dots.ts` | 106-112, 153-219, 268-288, 304-336 | 间奏点演出编排 / posY / 时钟 |
| `packages/react/src/lyric-player.tsx` | 261-281, 326-334 | 宿主帧循环 / 进度推送 |
| `packages/core/test/timeline-controller.test.ts` | 290-306, 308-351, 389-402, 531-546 | scrollToIndex 行为契约 |
