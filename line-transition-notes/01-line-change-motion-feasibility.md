# 歌词「换行动画」接管可行性研究

- 工作树：`worktrees/line-transition`，分支 `feat/lyric-line-transition`（基线 `integ/all-features` `add1935b`）
- 只读研究，未改源码、未提交、未 push。
- 对象：Apple Music 1614（7.0.0）/ 1626（7.0.3）的歌词列表换行过程。
- 证据分级：**[DEX]** = 从 `/tmp/am1626/` 的 base.apk + arm64 split 解出、可复现；**[profile]** = 仓库 `host-profiles/*.json` 已 pin 的事实；**[分支]** = `feat/lyric-line-motion` 的既有结论（含真机观测）；**[推断]** = 由上述证据推出、未在真机验证。

---

## 0. 结论（先看这里）

1. **换行确实是 RecyclerView 的 item 移动，而且宿主用的是它自己的 ItemAnimator**。1626 里歌词列表的 animator 是 **`mc.y`**（`extends androidx.recyclerview.widget.F`，`F extends RecyclerView$k` = `ItemAnimator`），全 app **只有一个构造点**：`PlayerLyricsViewFragment$u.<init>`（`PlayerLyricsViewFragment$u` 本身 `extends mc.y`）[DEX]。也就是说这个 animator 是**歌词列表专用**的，接管它不会波及别的列表。
2. **它不是一个通用 animator，里面塞了歌词业务**：`mc.y.animateMove` 直接读 `PlayerLyricsViewFragment` 的 `V0:LinkedList`/`U0:boolean`、通过 `$u.z` 拿回 fragment、还 `post` 一个 `J4.t2` runnable [DEX]。**这意味着「拒绝 animateMove」不只是关掉动画，而是跳过宿主的换行后处理记账**——这是本次研究最重要的风险发现。
3. **最便宜的接入口是 `lyricBlur` profile 区，不是 `LYRICS_*`**：`lyricBlur` 的 `contractVersion:1` 在 1606/1607/1609/1613/**1614**/1622/1624/**1626** 全部存在，含 `createView` + `vmLine` + `binding`/`recycler` 字段；而 1622 的 `hookTargets.LYRICS_*` 是**空表** [profile]。历史分支正是踩在这个区上，所以它能一台机器上跑通且几乎不用新增 pin。
4. **历史分支的路线（接管 ItemAnimator）技术可行，但它的第二半（自己驱动行 `scaleX`）和宿主打架**：宿主的 `V0$N` 写 pivot、`V0$b`/`A$s` 的 `onAnimationEnd` 写 `setScaleX/setScaleY`、`V0.l`(bind) 还会读 `getScaleX()` [DEX]。**行缩放的所有权在宿主，别再抢**；只抢 `translationY` 是干净的。
5. 建议：**方案 A（接管 ItemAnimator 的 animateMove/animateChange + 整块 translationY 弹簧）** 作为主路线；**方案 D（行级属性动画）** 作为 A 的数据源不完备时的降级；**方案 B（hook 滚动）** 只用来做落点校正；**方案 C（overlay）** 不适合做换行，只适合做装饰。
6. 下一步 PoC 只需要验**一个**假设：**「1614 与 1626 上，每次行变化都必然经过 `ItemAnimator.animateMove`，且 `fromY-toY` 恰等于一行高」**（见 §6）。这条不成立，A 的触发点就不完备，应直接转 D。

---

## 1. 历史分支 `feat/lyric-line-motion` 做了什么（可直接复用的部分）

`git log origin/integ/all-features..feat/lyric-line-motion` → 6 个提交，HEAD `ac0da828`：

| 提交 | 内容 |
|---|---|
| `4502e942` | 换行动画改用 iOS 弹簧（A1+A3，可调时长/错峰/高亮缩放） |
| `2076841a` | （附带）玻璃结构回归测试跟随 schema 26 |
| `4087b04c` | 换行改为弹簧滑行（抑制宿主瞬移）+ 诊断日志 |
| `ec6f2ab2` | 换行诊断日志（开关/animator/滚动调用） |
| `e199bcbf` | **修正 ItemAnimator 形状匹配：ViewHolder 被混淆为 `RecyclerView$D`** |
| `ac0da828` | 换行改为**整块**弹簧位移（b5）；`animateChange` 一并拒绝（否则新旧两份文本叠影）；强调行缩放改左锚点 |

规模：19 文件 / +1301 行。核心 `LyricLineMotionSession.kt` 587 行、`LyricLineMotionPolicy.kt` 98、`SpringTiming.kt` 126、`AppleMusicLyricLineMotionTarget.kt` 53、`LyricLineMotionContract.kt` 50（≈924 行主代码，其余是设置项/i18n/装配）。BUILD_TAG `2026-10-23-1.6.6-lyric-line-motion-1609-b5` → **只在 1609 上真机跑过**。

**可直接复用的结论：**

- **R1｜接入面已经验证过一遍**：它只用 `lyricBlur` 区的 4 个角色（`createView`、`vmLine`、`Fields.binding`、`Fields.recycler`），并用 `contractVersion==1` + 类型断言做 preflight。1614/1622/1626 的 `lyricBlur` 都满足这些断言 [profile]，所以**接口层不用重做**。
- **R2｜形状匹配是可行的兜底**：`animateMove` 按「非 static、返回 boolean、5 参、第 0 参类型名前缀 `androidx.recyclerview.widget.RecyclerView$`、其余 int」匹配；`animateChange` 用 6 参。**这份经验在 1626 上依然成立**——1626 的 `mc.y#m(VH,int,int,int,int)Z` / `#l(VH,VH,int,int,int,int)Z` 形状完全一致 [DEX]。
- **R3｜弹簧数学已经是纯函数、有单测**：`SpringTiming`（欠阻尼/临界/过阻尼闭式解 + 时长反解 + 二分细化）+ `LyricLineMotionPolicy`（iOS `lineChangeSpring` mass1.0/stiffness100/damping18、`liftSpring` 14/7、`lineDelay` 50 ms、`emphasizingScaleRange` 1.14、`deselectedTransform` 0.98）。这些参数来自 LyricsBlossom 的 iPadOS 逆向笔记，**不是**我们从 DEX 里读出来的，属于外部先例。
- **R4｜三个已经踩过的坑**：(a) `ViewHolder` 在宿主里被混淆成 `RecyclerView$D`，不能按 `$ViewHolder` 匹配；(b) `animateChange` 必须一起拒绝，否则交叉淡入会和新旧两份文本叠影；(c) `translationY` 要**每帧写绝对值**，否则中途 rebind 的行会漂移。

**不要照搬的部分（本次研究推翻/修正的）：**

- **它自己驱动了 `row.scaleX/scaleY`**（`emphasise()`/`scaleTo()`，并写 `view.pivotX`）。1626 的 DEX 显示宿主自己在做这件事（`V0$N` 写 pivotX/pivotY、`V0$b#onAnimationEnd`/`A$s#onAnimationEnd` 写 `setScaleX/setScaleY`、`V0#l` bind 里读 `getScaleX()`），**两个写入者**。见 §5 风险 6。
- **它假设「宿主动画 = 750 ms 线性」**（`LyricLineMotionSession` 类注释）。这条我**没能从 DEX 证实**：`mc.y#j()` 确实读基类 `RecyclerView$k.{d,e,f}:long` 作 duration 并 `ViewPropertyAnimator#setDuration`，但具体数值是运行时值，注释里的 750 ms 是 1609 的观测。
- **它的 `activeRecycler()` 取「第一个已 attach 且有 child 的 recycler」**——平板双栏/多 fragment 场景有选错风险（§5 风险 8）。

---

## 2. 可注入的位置（4 种方案）

先给一张总表，后面逐条展开。

| # | 方案 | 实现难度 | 对原生行为的破坏面 | 跨版本稳定性（依赖哪个 seam） | 与逐词高亮共存 |
|---|---|---|---|---|---|
| A | 接管 `ItemAnimator`（拒绝 `animateMove`/`animateChange`，自绘整块位移） | 中（历史分支 ≈900 行已写完） | **中–高**：跳过宿主自己的换行记账/后处理 | **高**：`lyricBlur.createView` + `RecyclerView.getItemAnimator()`（稳定名）+ 形状匹配；**不依赖任何混淆名，也不依赖 `LYRICS_*`** | **好**（只写 itemView 的 `translationY`） |
| B | hook `LayoutManager.scrollToPositionWithOffset` / `RecyclerView.smoothScrollToPosition` | 中低（但每版要 pin） | 低（只改落点/时机） | **低**：`LinearLayoutManager.u1(II)` / `RecyclerView.u0(I)` 是混淆名，且 2-int-void 在 LM 里还有 `y1`/`z1` 同形状 | 好（不动视图） |
| C | `onCreateView` 后拿 RecyclerView 自绘一层（overlay / 自定义 LayoutManager） | **高** | 高（参与 measure/layout/触摸；自定义 LM = 重写整个排版） | 中（只用 `binding`/`recycler` 字段 + ViewGroup） | 好 |
| D | 行视图属性动画（hook `lineBind`/`linePayload`/`lineRecycle` + child attach，逐行 `translationY`/`alpha`） | 中 | 中（宿主 `animateMove` 仍在跑，会叠加位移） | **中高**：`lyricBlur.lineBind/linePayload/lineRecycle/registerChildListener/childAttach/childDetach` 全版本已 pin | 需要与宿主 `scaleX` 动画协调 |

### 方案 A｜接管 ItemAnimator

**做法**：`createView` 之后取 `recycler.getItemAnimator()`，形状匹配 `animateMove`/`animateChange`，hook 里 `param.result = false` 拒绝宿主动画，然后对整个列表的可见子 view 写同一个 `translationY` 偏移，用弹簧逐帧归零。

**证据**：
- `androidx.recyclerview.widget.RecyclerView#getItemAnimator()Landroidx/recyclerview/widget/RecyclerView$k;` 在 1626 **保留原名**（同批保留的有 `getAdapter`、`getScrollState`、`onTouchEvent`）[DEX]，所以取 animator 这一步不需要 pin。
- 宿主 animator `mc.y` 的 `animateMove` 签名是 `(RecyclerView$D,int,int,int,int)boolean`，`animateChange` 是 `(RecyclerView$D,RecyclerView$D,int,int,int,int)boolean` [DEX]，与历史分支的形状规则一致。
- 唯一构造点：`PlayerLyricsViewFragment$u#<init>(PlayerLyricsViewFragment)` → `mc.y#<init>()V`；`PlayerLyricsViewFragment$u extends mc.y` 并覆写 `b(VH,List)Z`/`i`/`p`/`q`/`r`/`s`（`p→i1#G`、`q→i1#I`、`r→i1#M`、`s→i1#N`，都回打 adapter）[DEX]。**没有第二个地方 new 这个 animator**，所以它是歌词列表独占。

**难度**：中。历史分支把坑都趟过了（`$ViewHolder` 混淆、`animateChange` 叠影、每帧绝对值）。真正的难点不在代码量，在**判断哪些宿主副作用被一起跳过**（见下）。

**破坏面**：
- `animateChange` 被拒绝 → 丢掉宿主的交叉淡入。结构证据：`$u` 覆写了 `b(VH,List)Z`（与 `RecyclerView$k#b(VH,List)Z` 同签名，即 `canReuseUpdatedViewHolder`），并在里面 `clear()` 了 `i1$b.y:LinkedHashSet` [DEX]。**若它返回 false**，则被 payload 更新的行会拿到**新 holder**，`animateChange` 里就是两个视图交叉淡入——这与历史分支观测到的「叠影」吻合；但返回值本身需指令级确认（见 U4）。
- `animateMove` 被拒绝 → 连带跳过 `mc.y#m` 里的 `PlayerLyricsViewFragment.V0:LinkedList` / `U0:boolean` 记账和 `J4.t2` 投递 [DEX]。**这是本次研究新发现、历史分支没有记录的破坏面**（它在 1609 上可能没有这段，也可能有但没观测到）。
- `$u` 覆写的 `p/q/r/s` 是 `mc.y` 的 per-holder 入口，回打 `i1#G/I/M/N`；如果我们的拒绝发生在 `mc.y#m` 且 `$u` 的覆盖没被调用，adapter 侧的每行通知就缺一次。**建议 PoC 时专门数一次 `i1#G/I/M/N` 的调用次数**（比宿主原生少几次就是丢了通知）。

**跨版本稳定性**：**最高**。整条链路只用到「稳定 androidx 名 + 方法形状 + `lyricBlur` 已 pin 的字段」，1614/1622/1626 都不需要新的混淆名。1622 的 `LYRICS_*` 空表**不影响**它。

**与逐词高亮共存**：好。逐词高亮写的是行内 word view（`A$a.G/H/I/J` 那 4 个 `ArrayMap` 管的 model，以及 `A$e` 的 word 进度动画），我们写的是 RecyclerView 子 view 的 `translationY`，两者是父子关系、互不覆盖。**但不要碰 `row.scaleX`**（宿主所有，见 §5-6）。

### 方案 B｜hook 滚动调用

**做法**：hook `LinearLayoutManager.scrollToPositionWithOffset(pos, offset)` 或 `RecyclerView.smoothScrollToPosition(pos)`，改写目标 offset / 抑制调用，自己用 `View.scrollBy` 或 `translationY` 做位移。

**证据**：
- 宿主的滚动点：`PlayerLyricsViewFragment#B2(SongInfoPtr,Z)` → `LinearLayoutManager#u1(II)V`（文档安装时的重锚点）；`fragment.c0$a#run()` → `RecyclerView#u0(I)V` + `LinearLayoutManager#u1(II)V`（延迟滚动，见 §3-E8）；同形状 `u1(II)` 也出现在 `NewPlayerQueueViewFragment#onBottomSheetSlide`（队列滚动），说明它是 `scrollToPositionWithOffset` 而不是 `smoothScrollToPosition`（3 参）[DEX]。
- **混淆名未 pin**：`LinearLayoutManager` 在 1626 **没有** `scrollToPositionWithOffset` 这个名字，成员级混淆掉了；`RecyclerView` 也没有 `smoothScrollBy`（`scrollBy`/`scrollTo` 因为是 View 覆写才活着）[DEX]。`u0(I)` 的语义靠旁证确定：多处 `scrollToTop()` 调它 → `smoothScrollToPosition`。

**难度**：中低，但**每版都要新增 pin**。而且 `(II)V` 在 `LinearLayoutManager` 里有 3 个同形状候选（`u1`/`y1`/`z1`），按仓库「禁止静默取首匹配、歧义要上报」的规则，必须补调用方/语义证据才能唯一化。

**破坏面**：低——只改落点与时机，不动绘制。**但它接管不了动画形状**：只要 `animateMove` 还在，行依旧按宿主的位移走；你只能改「停在哪个 offset」。所以 B **不能单独作为换行接管**，只能做落点校正（例如「换行后让当前行落在 1/3 高度」）。

**跨版本稳定性**：低。1622 连 `LYRICS_*` 都是空表，说明这条版本线上「按名字 pin」这件事本身就不稳。

**与逐词高亮共存**：好（完全不碰视图属性）。

### 方案 C｜RecyclerView 之上自绘一层

**做法**：`createView` 后往 `binding.recycler` 的父容器塞一个自绘 View；或者替换 `LayoutManager` 自己排版。

**证据**：`lyricBlur.fields.binding`(`q8.X4`) + `recycler`(`q8.X4.d0`) 在所有 7.0.x 版本都 pin 了 [profile]；fragment 还有 `getRecyclerView()` 方法（1626 确认存在 `getRecyclerView()Landroidx/recyclerview/widget/RecyclerView;`）[DEX]，对应 `LYRICS_UI_RECYCLER_VIEW_METHOD`。

**难度**：高。overlay 要「接管换行」就必须自己画歌词文本——那等于重写渲染；自定义 LayoutManager 要接 `RecyclerView$n` 的一整套混淆方法（1626 里 60+ 个），且宿主在 `createView` 里 `setLayoutManager(new LinearLayoutManager(1))`，我们得在之后替换并接管所有回调。

**破坏面**：最高（新增 measure/layout/触摸参与者，或整个排版换人）。

**跨版本稳定性**：中（字段稳定，但 LayoutManager 抽象方法名全混淆）。

**结论**：**不适合做换行**。适合做「装饰层」（行间光带、渐变遮罩），因为那类需求不需要动真实行视图。

### 方案 D｜行视图属性动画

**做法**：hook `lineBind`(`V0.l(VH,int)`)、`linePayload`(`V0.m(VH,int,List)`)、`lineRecycle`(`V0.s(VH)`)，配合 `registerChildListener`(`RecyclerView.h(RecyclerView$p)`) + `childAttach`/`childDetach`，对每个 itemView 做 `translationY`/`alpha` 弹簧。

**证据**：这些角色在 `lyricBlur.methods` 里**全版本已 pin**（1606→1626 都有 `lineBind/linePayload/lineRecycle`，只有名字随版本变：1609 `R0.k/l/r`、1614 `S0.l/m/s`、1622+ `V0.l/m/s`）[profile]。`registerChildListener`/`childAttach`/`childDetach` 也在 `commonMethods` 里，且 `AppleMusicLyricBlurRuntime` 已经在用 `setScrollState`/`onTouchEvent`/`onInterceptTouchEvent` [源码]。

**难度**：中。**核心难点是它拿不到 `fromY/toY`**——`lineBind` 只告诉你「这一行被绑定到 position N」，不告诉你它是从哪移动过来的。必须自己维护「上一帧每行的位置表」（`WeakHashMap<View, Int>`，注意仓库禁止 `view.tag`），并在 layout 后比对。这比 A 的方案脆：layout 一次可能连着绑多行，顺序不保证。

**破坏面**：中。**宿主 `animateMove` 还在跑**，所以 D 必须**同时**压制宿主（否则同一行被位移两次），压制的复杂度就退化成 A 了。D 的真正价值是：**当 A 的触发点不完备时（某些换行不走 animateMove），D 能兜住**。

**跨版本稳定性**：中高（角色全 pin），但语义弱（bind ≠ 位置变化）。

**与逐词高亮共存**：需要协调——payload bind（`V0.m`）内部会再调 `V0.l` 做整行 bind，我们的位移如果写在 bind 之后，会被宿主 bind 里的 `setTranslationY`（`A#l`/`A#u0` 也写 translation）覆盖。

---

## 3. 信息需求：要读到什么、现在能不能拿到

| # | 需要的数据 | 现有 seam | 能否拿到 | 缺口 / 补法 |
|---|---|---|---|---|
| I1 | 滚动容器（RecyclerView 实例） | `lyricBlur.fields.binding`+`recycler`；`LYRICS_UI_ON_CREATE_VIEW`.runtimeMemberNames.`LYRICS_UI_RECYCLER_VIEW_METHOD=getRecyclerView` | ✅ 全 7.0.x | 无 |
| I2 | 当前高亮行 index | `lyricBlur.methods.vmLine` = `PlayerLyricsViewModel.setCurrentHighlightedLine(int)` | ✅ 全 7.0.x | 无。注意它是**行序数**（adapter ordinal）语义，不是 native lineId |
| I3 | 每行的 view / holder / 行类型 / 稳定性标识 | `rowPosition`(`RecyclerView.M(View)→int`)、`rowHolder`(`RecyclerView.O(View)→VH`)、`holderView`/`holderId`/`holderType` 字段 | ✅ 全 7.0.x | 无。`holderId`（长整型 itemId）+ `lineId(int)` 可以做「不依赖 getAdapterPosition」的行身份 |
| I4 | 行高 / 行位置 | 运行时 `itemView.height`/`top`；不需要 seam | ✅ | **缺口**：宿主的 `fromY/toY` 只在 `animateMove` 里给。若走 D，需要自建「上一帧位置表」（`WeakHashMap`，禁 `view.tag`） |
| I5 | 每行的 ttml 行模型（`.i`） | `lineReader`(`ttml.j`)、`readerCount`、`readerLine`、`lineGet`、`nativeLineId`、`nativeLineBegin/End`、`lineId(int)`、`lineType(int)`、`lineMap(int,boolean)`、`lineSnapshot()` | ✅ 全 7.0.x（`AppleMusicLyricBlurHostPort.document()` 已经在用） | 无 |
| I6 | 是否正在滚动 / 用户是否在摸 | `getScrollState`/`setScrollState`/`onTouchEvent`/`onInterceptTouchEvent` + `registerChildListener`/`childAttach`/`childDetach` | ✅ | 无。`AppleMusicLyricBlurRuntime` 已经全挂上了 |
| I7 | 宿主动画的时长/插值/错峰参数 | `mc.y#j()` 读 `RecyclerView$k.{d,e,f}:long`，`mc.y$a#run` 读 `mc.y.w/x:PathInterpolator` | ⚠️ **拿不到语义** | 三个 long 分不清是 add/remove/move/change 的哪个；`mc.y.u:int` 被 `B2`/`K#b`/`mc.y$a` 读写但含义不明。**建议不依赖**：用自己的弹簧（`SpringTiming` 已有），只把宿主值当日志 |
| I8 | ItemAnimator 的 `animateMove`/`animateChange` 方法对象 | 目前**只能形状匹配** | ⚠️ 可用但不符合仓库规则 | 建议新增独立 profile 区（例 `lyricLineMotion`，`contractVersion:1`），用 `invocationOnly` 显式声明 owner+完整擦除签名，把形状匹配降为 fallback。需要：`classes.animator`(`mc.y`)、`methods.animateMove`/`animateChange`/`isRunning`，以及 inventory 更新 + `verification` + `scripts/verify-host-profile.py` 覆盖 |
| I9 | 「宿主是否正在做动画」（用来和宿主握手） | `RecyclerView$k#h(RecyclerView$k$a)Z` = `ItemAnimator.isRunning(listener)`，被 `PlayerLyricsViewFragment#X1(I)` 与 `fragment$f#invoke`（nativeLine 回调）调用 | ⚠️ 可读但**不建议读**：我们接管后它恒 false，读它反而会改变宿主分支 | 记日志即可 |

**补齐 8/9 需要的新 profile 内容**（若要按仓库规则做）：

```jsonc
"lyricLineMotion": {
  "contractVersion": 1,
  "classes": { "animator": "mc.y" },              // 版本相关，需每版 pin
  "methods": {
    "animateMove":   { "owner": "mc.y", "name": "m", "parameters": ["androidx.recyclerview.widget.RecyclerView$D","int","int","int","int"], "returns": "boolean" },
    "animateChange": { "owner": "mc.y", "name": "l", "parameters": ["androidx.recyclerview.widget.RecyclerView$D","androidx.recyclerview.widget.RecyclerView$D","int","int","int","int"], "returns": "boolean" }
  }
}
```

> 注意：`mc.y` 是 1626 的名字；1614/1622 需要各自解 DEX 确认（见 §7）。**如果只做方案 A 且接受形状匹配兜底，这一个区可以先不加**——历史分支就是这么跑的。

---

## 4. 换行链路（1626 实测调用链）

把 DEX 里能看到的顺序串起来（箭头 = 方法引用证据）：

```
时间/歌词事件
  └─ player.V0#P(List, int, Pair[])            ← profile: lineCommit（行提交）
       ├─ V0#F(IZ)I                             ← lineMap：ordinal ↔ position
       ├─ i1#A(TreeSet)Z / i1#E(TreeSet,Collection)Z   ← 可见行窗口是否变化
       ├─ RecyclerView$f#h(I)V                  ← 1 参 adapter 通知（changed/inserted/removed 之一）
       ├─ RecyclerView$f#j(I)V                  ← 另一个 1 参通知
       ├─ RecyclerView$g#e(II)V                 ← AdapterDataObservable 的 (int,int) 通知（R8 内联；inserted/removed 之一，引用级无法区分）
       └─ fragment.K#b(II)V                     ← **行变化回调**
            ├─ 读 mc.y.u:I                       （含义未知，int）
            ├─ RecyclerView#getScrollState()I
            ├─ PlayerLyricsViewFragment#e2(frag,int)Z
            │     └─ LinearLayoutManager#e1()I / #f1()I   ← findFirst/LastVisibleItemPosition
            ├─ PlayerLyricsViewModel#setCurrentHighlightedLine(I)V   ← profile: vmLine
            └─ PlayerLyricsViewFragment#A2(Z)V

RecyclerView 内部（结构变化之后）
  └─ mc.y#m(VH,int fromX,int fromY,int toX,int toY)Z          ← animateMove
       ├─ view.get/setTranslationX/Y
       ├─ RecyclerView$k#c(VH)                                ← dispatch
       ├─ PlayerLyricsViewFragment$u.z (回指 fragment)
       ├─ PlayerLyricsViewFragment.V0:LinkedList / .U0:boolean
       └─ new J4.t2(Object,int,Object)#run()                  ← post 一个带 int 的 runnable
  └─ mc.y#l(VH,VH,int,int,int,int)Z                            ← animateChange（内部再调 mc.y#m）
  └─ mc.y#j()V                                                 ← runPendingAnimations
       ├─ RecyclerView$k.{d,e,f}:long (duration)
       ├─ ViewPropertyAnimator#setDuration/setListener/start
       └─ mc.y$a/$b/$c/$d, mc.z

文档安装 / 首次落点
  └─ PlayerLyricsViewFragment#B2(SongInfoPtr,Z)V
       ├─ RecyclerView#setAdapter / Adapter#g()  (notifyDataSetChanged)
       ├─ RecyclerView#getRecycledViewPool()→#a()  (clear)
       ├─ PlayerLyricsViewFragment.g1:I = <目标行>
       ├─ LinearLayoutManager#u1(II)V            ← scrollToPositionWithOffset
       └─ 读 mc.y.u:I
  └─ fragment.c0#a()V  → post c0$a
       └─ c0$a#run()V
            ├─ 读 g1:I
            ├─ Adapter#f(I)                      ← getItemViewType
            ├─ RecyclerView#u0(I)V               ← smoothScrollToPosition
            ├─ LinearLayoutManager#u1(II)V       ← scrollToPositionWithOffset
            └─ i1#P(List,int,Pair[])             ← 提交
```

**读法**：换行时**视图位移来自 `mc.y.animateMove`**（因为 `V0#P` 发的是结构性 notify，行整体上移一格）；`c0$a` 只在文档安装/显式跳转时出现；`K#b` 是「谁现在是当前行」的唯一判定点，它**不滚动**。

---

## 5. 风险清单

按「会不会让用户看到错」排序。

### 5.1 RecyclerView 复用/回收导致视图错位（高）
- 机理：我们写 `child.translationY`，而 `V0#s(VH)`（onViewRecycled）的引用集里是 `Animator#cancel`、`ViewDataBinding#n()/v()`、`V0#w()`、`q8.k9#p0`，**没有任何 `setTranslation*`** [DEX-引用集推断]；回收后的视图被新 position 复用时，会带着旧位移回来。
- 缓解：在 `lineRecycle` hook 里对该 view 清零 + 从位移表移除；每帧写**绝对值**而不是增量（历史分支已做）。

### 5.2 与 ItemAnimator 的 notify 冲突（高）
- 机理：`V0#P` 一次提交里同时发 `notifyItemChanged` + `notifyItemRemoved` + `notifyItemRangeRemoved`，于是 `animateChange` 与 `animateMove` **同批入队**，而 `mc.y#l` 内部还会再调 `mc.y#m` [DEX]。只拒绝其中一个 → 得到「半动画」（有的行瞬移、有的行滑行）。
- 缓解：两个一起拒绝（历史分支 b5 的结论），并且**同批里只启动一个 glide**（用一个短时间窗去重，历史分支用 250 ms）。

### 5.3 逐词 payload 重放时机（中高）
- 机理：`F2`(`LYRICS_NATIVE_PRESENTATION`) → `Adapter#d()` + `RecyclerView$g#d(II,Object)` = `notifyItemRangeChanged(count, payload)`；onBindViewHolder 的 payload 版本 `V0#m` **内部又调 `V0#l` 做整行 bind** [DEX]。也就是说 payload 路径会**再 bind 一次**，可能在位移中途重置视图属性（`V0#l` 里有 `setLayoutParams`/`setVisibility`/`setTextAlignment`）。
- 缓解：位移**不落在 bind 里**，而是独立的每帧写入；bind/payload 之后用「当前 glide 的进度」重新施加偏移（不要相信视图上的值）。

### 5.4 `F2` 在换行时的调用顺序（中）
- 事实：`F2` 只做 payload 刷新 + 语音/翻译可见性 + `C2`/`t2`/`u2`，**不滚动**；`fragment$f#invoke`（nativeLine 回调）会调 `ItemAnimator.isRunning(listener)`，`X1(I)` 也调 [DEX]。
- 风险：接管后 `isRunning` 恒 false。如果宿主有「等动画结束再落位」的分支，它会**提前**执行，表现为「先落位、后被我们的弹簧拉回去」的一次抖动。
- 缓解：PoC 阶段先**不改 `isRunning` 的返回值**（只观察），确认抖动来源后再决定是否伪造 `isRunning = true`（伪造要自带超时，否则宿主永远等）。

### 5.5 性能 / 低端机 60 fps（中低，但要选对写法）
- **单次 glide 的每帧成本可以忽略**：历史分支的 `translateRows` 只是 `for (child in 0 until childCount) child.translationY = offset`（10–20 个 child，不算 position、不反射）。每帧一次三角/指数求值（`SpringTiming.value`）也可忽略。
- **代价出在「谁驱动」**：如果改成方案 D 那种「每行一个 `ViewPropertyAnimator`/`ObjectAnimator`」，20 个并行 animator 各自走 Choreographer 回调，低端机上比「一次循环写 20 个属性」贵一个量级，且**相位不齐**（看起来像抖）。→ 保持「一个 animator 驱动所有 child」的形态。
- **第二个代价是反射**：历史分支的 `childPosition()` 用 `Method.invoke` 找 adapter position，只在 `emphasise()`（每次换行一次，不是每帧）里用。若未来把「行索引」算进帧循环，就会变成 20 次 invoke/帧。→ 用 profile 已 pin 的**静态** `RecyclerView.M(View)→int`（`rowPosition`），并把它挪到「换行时算一次」。
- **第三个代价是 layout**：写 `translationY` 只触发重绘不触发 layout，安全；但如果为了实现「每行错峰」而写 `layoutParams.topMargin` 或 `offsetTopAndBottom`，就会触发 `requestLayout`，每帧一次就是灾难。→ 只写 `translationY`。

### 5.6 行缩放的所有权冲突（中高，新发现）
- 事实：`V0#N(VH)` 写 `pivotX/pivotY`；`V0$b#onAnimationEnd` 与 `A$s#onAnimationEnd` 写 `setScaleX/setScaleY`；`V0#l` bind 里 `getScaleX()`；每 holder 一个 `V0$j.z:Animator`，`V0#s` 里 cancel [DEX]。
- 结论：**行缩放（选中放大/未选中 0.98）由宿主负责，模块不要写**。历史分支的 `emphasise()/scaleTo()` 是在抢这个所有权，会与宿主 animator 互相覆盖（历史分支只能靠「拒绝 animateMove」间接减少冲突，但宿主那个 animator 不是 ItemAnimator，拒绝 animateMove 停不掉它）。
- 缓解：只做 `translationY`；如果要更强的「强调感」，用不改 transform 的手段（文字颜色/辉光/背景层），或与宿主缩放**同向叠加**而不是覆写。

### 5.7 属性所有权需要显式登记（中）
- 仓库已有原语 `OwnedHostProperty`（`core/.../HostViewSessionController.kt:25`）：宿主改过就放弃所有权、只在仍是我们的值时才回滚。行 `translationY` 的现有写入者有 `A#l`、`A#u0`、`fragment.C#c` [DEX]，**必须**走这套原语，否则「像在改、其实被宿主覆盖」。
- 另外仓库硬规则：**禁止写 `view.tag`**（DataBinding 拥有它，会抛 `IllegalArgumentException`）；按 view 记状态一律 `WeakHashMap`。

### 5.8 多实例 / 多窗口选错 recycler（中）
- 历史分支 `activeRecycler()` = 「第一个 attached 且有 child 的 recycler」。平板双栏（`fragmentDualPane`）、歌词+队列同屏、或 fragment 重建过程中，可能拿到旧的那一个。
- 缓解：把 session 绑在 `createView` 的 owner 上（仓库已有 `HostViewSessionController` 做 view 身份 + `bindingGeneration`），不要全局找。

### 5.9 `getItemAnimator()` 为 null / 被替换（低）
- 事实：`onCreateView` 就 `setItemAnimator(...)`；但 adapter 会在 `B2` 里被 `setAdapter` 重设，recycler 也可能在复用 session 时为 null。历史分支用「`createView` 后 + `handler.post` 再来一次」处理。
- 缓解：保留这个二次尝试；并把「animator 为 none」写进一行限流日志（历史分支已有 `trace("remember animator=none")`）。

### 5.10 与读音/模糊/扫光的写入点冲突（低–中）
- 读音行的位置有唯一写入点 `LyricsPronunciationRowGeometry.applyReadingOffset`（`translationX`/`offsetLeftAndRight`），有测试在数写入点数量。我们写的是**行**的 `translationY`，不冲突；但**不要**顺手去动 reading 子 view 的位移。
- 模糊/扫光走的是 RenderEffect/渐变层，与 transform 正交。

---

## 6. 建议的下一步：最小验证（PoC）

### 只验一个假设

> **H1：在 1614 与 1626 上，每次「行变化」都必然经过歌词列表 `ItemAnimator` 的 `animateMove`，且 `fromY - toY` 恰好等于该行的高度。**

**为什么是它**：方案 A 的全部输入就是这一对 `fromY/toY`（+ 一个统一位移量）。如果某些换行不走 `animateMove`（例如一次性跳过 3 行以上的 seek、instrumental 行、开启翻译/读音导致行高变化、`notifyDataSetChanged` 全量刷新），A 就会**漏触发**——表现为「大多数换行有弹簧，偶尔瞬移」。这种情况必须转 D（自建上一帧位置表）。

**怎么做（不动行为，只观测）**：
1. 只用 `lyricBlur` 的 4 个角色做 preflight（`createView`/`vmLine`/`binding`/`recycler`），拿到 recycler。
2. `recycler.getItemAnimator()` → 形状匹配 `animateMove`/`animateChange` → **hook 里什么都不改**，只打一行限流日志：`dy = fromY - toY`、`holderType`、`rowPosition(view)`、`recycler.scrollState`、以及此刻的 `vmLine` 值。
3. 同时在 `lineCommit`（`V0#P`）、`lineBind`、`linePayload`、`F2`、`K#b`（若能 pin）上各打一个 tick，看**同一次换行里谁先谁后**、`animateMove` 被调了几次（应该 ≈ 可见行数，且 dy 全部相等）。
4. 真机跑 30–60 秒，覆盖：正常连续换行、手动 seek 前后跳 5 行、拖动歌词列表、切歌、开/关读音与翻译。
5. 验收：`animateMove` 次数 / 行变化次数 ≈ 1（每次换行 dy 方差 ≈ 0），且 dy ≈ `child.height`。

**日志约定**（照仓库规则）：tag `AMPP-LineMotion`，`ModernXposedRuntime.log`（会镜像到 logcat），高频行用常量开关包住、限流（每类 ≤12 行）。

**H1 成立之后的第二轮**（再动手写功能）：只做「拒绝 animateMove/animateChange + 整块 `translationY` + 弹簧归零」，**不做** emphasis 缩放；用 `OwnedHostProperty` 管 `translationY`；在 `lineRecycle` 里清零。

**H1 不成立时**：直接转 D，并把 §3-I4 的「上一帧位置表」当成主数据结构。

---

## 7. 不确定点 / 还需要什么材料

| # | 不确定点 | 影响 | 需要什么 |
|---|---|---|---|
| U1 | **1614 完全没有 DEX 证据**（APK 已清理）。`mc.y`/`c0`/`K#b`/`LinearLayoutManager.u1` 这些名字在 1614 是否同构，未验证 | 方案 A 的形状匹配**不依赖**这些名字，所以大概率无碍；但要新增 §3-I8 那种显式 pin 就必须有 1614 的 DEX | **1614 base.apk**（+ 对应 ABI split）。我可以按 `/tmp/am1626/*.py` 的同一套流程出 `classes.pkl`/`code_refs.pkl` 再对比 |
| U2 | `mc.y.u:int` 的语义（`B2`/`K#b`/`mc.y$a` 都读写）未知 | 可能正是宿主「换行中」的状态位；若我们在接管时不维护它，宿主可能走错分支 | 需要 `mc.y$a#run`/`K#b` 的**字节码级**分析（当前只有引用级索引，没有指令序列）。`/tmp/am1626/dexcode.py` 只解引用不解指令流，要另写指令解码 |
| U3 | 「宿主 move 动画 = 750 ms 线性」是历史分支注释里的说法，未见 DEX 佐证 | 只影响「参数对齐」，不影响接管 | 真机日志（U1 那轮 PoC 就能顺带量出来） |
| U4 | `$u#b(VH,List)Z`（`canReuseUpdatedViewHolder`）**返回值**未知——只能确定它清空了 `i1$b.y` | 如果它其实返回 true，那「两视图叠影」的解释就要换（可能是别的原因） | 需要指令级解码 `$u#b` |
| U5 | `RecyclerView$k.{d,e,f}:long` 三个 duration 的对应关系 | 同上，仅参数对齐 | 指令级解码 `mc.y#j` + 基类 `$k` 的构造/clinit |
| U6 | 1626 的 `fragment.K#b(II)` 两个 int 的语义（行 index？前后行？） | 若要用 `K#b` 作为「行变化」触发点，必须知道 | 指令级解码 + 真机打点对比 `vmLine` |
| U7 | 平板双栏下是否**两个**歌词 RecyclerView 同时存在 | 影响 §5.8 | 真机（用户手上 1614/1626 都可以）跑一次 `recycler` 实例计数 |

**当前可复现的 DEX 分析入口**（我已验证可用）：
```
/tmp/am1626/base.apk + split_config.arm64_v8a.apk
/tmp/am1626/build_index.py   → classes.pkl（类/父类/方法/字段/access）
/tmp/am1626/dexcode.py       → code_refs.pkl（每方法的 字符串/字段/方法/类型 引用集）
/tmp/am1626/cq.py            → R / jn / mref / fref 查询helper
/tmp/lt/q.py                 → 我这次加的 dump()/callers() 便捷封装
```
（`dexcode.py` 只解**引用**，不解指令流；U2/U4/U5/U6 需要扩到指令级。）

---

## 8. 给实现阶段的建议（如果决定做）

1. **先落 `lyricLineMotion` profile 区，再写代码**。理由：仓库规则要求每个符号能独立报告「找到/缺失/歧义」，而形状匹配是「静默首匹配」的近亲。把 `mc.y` + `animateMove`/`animateChange` 显式 pin 之后，形状匹配只作 fallback，缺 pin 的版本降级而不是瞎猜。
2. **只接管 `translationY`，不接管 `scaleX/scaleY`**（§5.6）。强调感用颜色/辉光表达，或者与宿主缩放同向叠加。
3. **把「block offset」抽象成一个纯函数**（`progress → offsetPx`），host 侧只负责「取 recycler、算一次 delta、逐帧写」。`SpringTiming`/`LyricLineMotionPolicy` 可以直接搬（它们已经在 `feat/lyric-line-motion` 上写完并有单测）。
4. **位移写入走 `OwnedHostProperty`**，并在 `lineRecycle` 里清零；不要写 `view.tag`。
5. **默认关闭**，开关在设置里；关闭时所有回调首行返回（历史分支就是这么做的，便于「出问题先关掉」验收）。
6. **验收要看真机可观测结果**：一条「`animateMove dy=… row=…`」日志 + 一段录屏；不要用「应该顺滑」结案。
