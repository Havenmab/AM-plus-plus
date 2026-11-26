# 10 · 间奏三圆点：宿主到底怎么显示/结束，以及换行时我们先把它收掉

> 只讲 **Apple Music 7.0.3 / versionCode 1626**。DEX 来自 `/tmp/am1626/`（`amd2.py` 指令级解码），
> 布局/资源来自 `base.apk` 的 AXML（`/tmp/am1626/axml.py`）与 `resources.arsc`。
> 结论先行：**1626 的三圆点没有任何"退场动画"，也从来没人把它们的 alpha 归零** —— 唯一的活路径是
> 间奏期间那段"tint 扫过 + 4 s 缩放呼吸"的演出（`A#Y`/`V0#U`），它一直跑到行被回收才被 `w()` 取消。
> 换行时演出还在跑，而我们的波浪已经把下一行送上锚点 ⇒ 两者同屏。修法是**换行瞬间结束演出并把三颗点淡出**。

---

## 1. 三圆点是哪个视图

`res/layout/lyrics_line_instrumental.xml`（AXML 解出的完整结构；全部 1773 个 layout 里只有这一个含这些 id）：

| 元素 | id | 备注 |
| --- | --- | --- |
| `ConstraintLayout`（行根 = itemView） | `id/lyrics_instrumental_root` | `layout_width=match_parent`、`layout_height=wrap_content`、`paddingTop/Bottom = lyrics_text_padding_top/bottom`、左右 `lyrics_line_margin_horiz` |
| `ImageView` | `id/dot1` | `src=drawable/lyrics_instrumental_dot`、`layout_width/height = lyrics_instrumental_dot_size`、`scaleType=5`(center)、`tint=color/white_alpha_85`、top/bottom 贴父、右贴 `dot2` |
| `ImageView` | `id/dot2` | 同上，左右都贴邻居 |
| `ImageView` | `id/dot3` | 同上，左贴 `dot2`、右贴父 |

三个点 `top_toTopOf=parent` + `bottom_toBottomOf=parent`，所以**行高 = dot_size + paddingTop + paddingBottom**，
内容是"一行只有三个点"——这一点决定了后面「淡出后整行是空的」这个事实。

DataBinding：`DataBinderMapperImpl#f` 把 `layout/lyrics_line_instrumental_0` 映射到 `q8.m9`，
构造函数把 4 个入参依次 `iput-object` 到（偏移 4/6/8/10）：

    q8.m9.X : ImageView        // dot1
    q8.m9.Y : ImageView        // dot2
    q8.m9.Z : ImageView        // dot3
    q8.m9.a0: ConstraintLayout // lyrics_instrumental_root

字段名在 `A#l` 里被 `Intrinsics` 当参数字符串钉死（`"dot1"`/`"dot3"`，见 §2.2），所以 X/Y/Z ↔ dot1/2/3 不是猜的。
（`id/item_instrumental` 是另一个东西，任何 layout 里都没有它，与三圆点无关。）

---

## 2. 唯一的活路径：间奏"演出"

### 2.1 `V0#U(Lq8/m9;Z)V`（行适配器）/ `A#Y(Lq8/m9;ZZ)V`（逐词适配器）

两者结构相同（`A` 用字段 `A.N/E/I`、`A.L/M`，`V0` 用 `V0.C/w`、`V0.A/B`）。用 `V0#U` 的偏移说：

| 偏移 | 指令 | 含义 |
| --- | --- | --- |
| 8-28 | `iget V0.C` + `getAnimatedFraction()*getDuration()` + `Math.round` | `elapsed` = 演出时钟已经走了多少 ms；`C == null` 时 0 |
| 30-35 | `v8 = V0.w - elapsed`；`if-gez` 跳走，否则 `goto 544`（return） | **`remaining < 0` 就直接 return**：间奏已经结束，这一次调用什么都不做（**注意：也不清理上一次的演出**） |
| 37 | `invoke V0#w()` | 取消上一套 `AnimatorSet` 并把 `A`/`B` 置 null |
| 45-100 | `[X,Y,Z]` → 逐个 `setImageTintList(ColorStateList.valueOf(V0#W()))` | 先把三个点涂成"底色"（`V0#W()` = 两种白，按 `V0.t` 选） |
| 101-103 | `if (z) Collections.reverse(dots)` | 空白行文本（`utils.L0#a(lineText) == true`）时反序 |
| 111-119 | `step = round((V0.w + 750) / 3f)` | 每个点的时间窗 |
| 132-341 | 每个点一个 `ValueAnimator.ofFloat(fraction)` → `ArgbEvaluator`，`setStartDelay`/`setDuration`/`PathInterpolator(0, .25, 1, .6)`，update listener `V0$e` | **tint 依次扫过**：第 i 个点在 `[i*step, i*step+step]` 内从底色渐变到亮色（最后一个点用 `2*step` 的窗，终点正好是 `V0.w`） |
| 258-303 | 只在**最后一个**点上额外建 `V0.B = ValueAnimator.ofFloat(...)`，`duration=750`、`LinearInterpolator`、listener `V0$g` | 这一颗点自己的"点亮"收尾 |
| 345-379 | `if (V0.C == null) { C = ofFloat(0,1); duration = V0.w; Linear; start() }` | **演出时钟**：只在第一次创建，之后一直复用（所以"第二次间奏"的 `elapsed` 是上一段的累计值，见 §6） |
| 392-467 | `ObjectAnimator.ofPropertyValuesHolder(root, SCALE_X/SCALE_Y = [当前, 1.2, 1.0])`，`duration=4000`、`startDelay=0`、`repeatMode=1`(RESTART)、listener `V0$i` | **行根缩放呼吸**：1.0 → 1.2 → 1.0，4 s 一轮 |
| 475-502 | `n = remaining / (4000+0)`；`n > 0` 时 `setDuration(remaining/n)`、`setRepeatCount(n-1)` | 把呼吸裁到"正好在间奏结束时停下" |
| 505-527 | `AnimatorSet A = playTogether(...); A.addListener(V0$a); A.start()` | 一起跑 |
| 530-541 | `Y0.d == 2` 时 `A.pause()` | 播放器暂停时演出也暂停 |

`V0$a`（listener）：`onAnimationStart` → `PlayerLyricsViewFragment#d2(fragment, -1300)`；`onAnimationCancel` → `i1#B()` 为真时 `d2(fragment, -500)`。
（`d2` 就是 profile 里的 `lineOffset`，即"建议行偏移"的桥；那两个数字 07 号笔记 §6 列为未解问题，本次仍未追到业务含义，但不影响本议题。）

### 2.2 什么时候被调用（arming points）

| 调用点 | 证据 |
| --- | --- |
| `V0#l`（`onBindViewHolder`）偏移 438-477 | 先按 `blank/RTL` 把 `dot1`/`dot3` 的 `ConstraintLayout.LayoutParams.E`（horizontalBias）设成 0 或 1，再 `U(binding, blank)` |
| `A#l` 偏移 90-156 | 同上，字符串常量 `"dot1"`/`"dot3"` 就钉在这里；末尾 `A#Y(binding, blank, flag)` |
| `V0#H(ViewHolder)` 偏移 59 / `A#H` 偏移 99 | **零位移 move 结算**：`mc.y#m` 的 `dy==0` 分支把 `i1#H` 塞进 `V0` 队列（或 `U0=1` 当场跑），`H` 只对 `q8.m9` 行做 `U/Y` |

也就是说：**每绑定一次、每结算一次零位移 move，就（重）启一次演出**。而 `U/Y` 的第一件事是 `w()`（取消上一套），
所以适配器上永远只有"最近一次"的 `AnimatorSet`。

### 2.3 没有任何东西把三圆点藏起来

| 方法 | 实际行为 |
| --- | --- |
| `V0#s` / `A#s`（`onViewRecycled`） | 只 `w()`：`AnimatorSet#cancel()` + 置 null。**`cancel()` 不会把 scale/alpha 复位** |
| `V0#w` | `if (A != null) { A.cancel(); A = null } ; B = null` |

所以间奏结束后：点还是满 alpha、行根还停在 `cancel` 那一刻的 scale（可能是 1.1x），**直到这一行被回收**。
回收发生在行离开可见区之后，而"下一行已经换上来"发生在换行的那一帧。

---

## 3. 两个"看起来像退场/入场"的方法，在 1626 是死代码

| 方法 | 它本来要做什么 | 为什么不会跑 |
| --- | --- | --- |
| `V0#N`（=`i1#N` 的实现）/ `A#N` | 行根 `pivot` 改到行中心（`setPivotX/Y`，偏移 108/124），`SCALE_X/Y [当前, 1.2]` **750 ms**（`PathInterpolator(.25,.1,.25,1)`，偏移 170-187），随后 `SCALE→0.5` + `ALPHA→0` **250 ms**（`PathInterpolator(.25,0,1,.1)`，偏移 216-251），`AnimatorSet.play(shrink).with(fade).after(grow)`，若 `V0.B` 在跑则再 `after(V0.B)`，结束时 listener `V0$b` 把 scale/alpha 复位、把三颗点 alpha=1 + 底色 | **唯一调用者 `PlayerLyricsViewFragment$u#s` 没有任何代码引用它。** `mc.y#m` 的零位移分支用的是 `J4.t2(this, 2, holder)`；`J4.t2#run` 的 packed-switch 只有 key 0/1（→ media3 通知分支），ordinal 2 落到默认体 = `i1#H`，不是 `i1#N`。全 APK 199,965 个方法的引用索引里没有一处调用 `$u#s` |
| `V0#G` / `A#G` | 每颗点 `setAlpha(0)` 然后 `ALPHA [0, 1]` **750 ms**、`AccelerateInterpolator`、`startDelay = i*50`（偏移 87-122） | 没有任何引用者（连 `$u` 都没调它） |

> 这也解释了 08 号笔记 §1 的观察 `animEntry=0 / animText=0` 和 §6 的疑问"行动画 `A#N` 的触发条件"：
> **它不"条件性"，是这个版本里根本不可达**。08 号 §6 那条待确认项可以划掉。

`u#s`（→`i1#N`）和 `u#r`（→`i1#M`，`M` 会 `w()` 取消演出 + `q0.h0=true` + `d2(fragment,-500)`）都只是 2-3 条指令的转发方法，**两者都没有调用者**。
R8 没删掉它们（`mc.y` 子类的公有成员），但运行期不会被执行。

---

## 4. 与 `K#b` / lineChange 的调用顺序

    PlayerLyricsViewFragment$f#invoke(...)      // 行事件：算 lerp(480,800,gap) 写 $k.e（甲的注入点）
      └─ K#b(start, end)                        // 换行：start != -1 时 LinearLayoutManager.u1(start, off) → requestLayout
           └─ 下一帧 layout → RecyclerView.ItemAnimator#animateMove(holder, fromX, fromY, toX, toY)
                ├─ dx==0 && dy==0 → J4.t2(this,2,holder) → i1#H(holder) → V0#H → 若 q8.m9 → U(binding, blank)
                └─ 否则          → translationY = -dy，返回 true（宿主自己的 VPA 跑 $k.e 毫秒）
                                    └─ j#onAnimationEnd → RecyclerView$k#c(holder)（不碰 i1#H / i1#N）

- **换行本身没有任何"圆点退场"的调用**；`$k.e` 与三圆点的时长**无关**（点的时间窗是 `(V0.w+750)/3` 与固定的
  `4000`/`750` ms，`V0.w` 是间奏自身时长）。09 号笔记里 bg 行高度动画用的才是 `$k.e`。
- 活路径里唯一和"换行"相遇的地方是 `i1#H`：它**只会让演出再启动一次**（而且 `remaining<0` 时直接 return，
  连清理都不做）。
- 我们的弹簧接管的是"非零 dy"分支，所以 `i1#H` 那条（零位移）原样保留 —— 接管前后圆点的演出强度完全一样，
  差别只在"行什么时候到位"。

---

## 5. 修法（方案 1）：换行瞬间结束演出 + 淡出三颗点

### 5.1 为什么不是方案 2（推迟波浪）

- 1626 里**没有**可对齐的退场 animator：`V0#N` 是死代码（§3），演出是"循环 + 扫色"，其结束时刻等于
  `V0.w`（间奏长度），不是能等的"退场回调"。等它意味着等整个间奏结束，不能作为换行前置。
- 推迟 ~100–200 ms 的代价是**所有**换行都变慢（包括没有间奏的曲子），而问题只出现在间奏→首句这类切换上。

### 5.2 方案 1 的实际内容

新增 profile 小节 `lyricLineMotion.interlude`（5 方法 + 5 字段 + 1 类），全部只读 `q8.m9` 与 holder：

| 角色 | 1626 成员 | 用途 |
| --- | --- | --- |
| `lineChange` | `K#b(int,int)` | 记录"当前行 = start"，`start == -1` 记成未知 |
| `wordBind`/`lineBind` | `A#l(VH,int)` / `V0#l(VH,int)` | 行被绑定 ⇒ 三颗点归位到满 alpha（保证下一次间奏的出场与宿主一致） |
| `wordSettled`/`lineSettled` | `A#H(VH)` / `V0#H(VH)` | 零位移结算 ⇒ 按当前行判一次 |
| `holderPosition` | `RecyclerView$D#d()I` | 行位置（与 probe 的 `holderPosition` 同一成员） |
| `holderBinding` | `i1$b.u` | holder → `q8.m9` |
| `dot1/2/3` | `q8.m9.X/Y/Z` | 三颗点（**只写 alpha**） |

真正的扫描点还多一个、且是最全的一个：弹簧自己的 `mc.y#m` 钩子（每一行每次换行都会过），
在它之前调 `onMove(holder)`，于是"间奏行"和"下一行"在同一次 layout pass 里被同时分类：

- `position < start` ⇒ **RETIRE**：取消我们在飞的淡出，按剩余 alpha 重新起一个**更短**的淡化
  （`120 ms × alpha`），所以第二次换行只会让它更快消失，永远不会更长；
- `position == start` ⇒ **ARM**：取消淡化、alpha=1（下一次间奏照旧）；
- `position > start` ⇒ 一律不碰。

**不取消宿主的 `AnimatorSet`**（`A.L`/`V0.A`、`A.M`/`V0.B`）：那是适配器级单例，"最近一次 arming"的行才拥有它，
替别人取消会把**另一行**冻在半程 scale 上（那才是真正的残影）。而三圆点所在的整行**除了三个点没有别的内容**，
所以点淡出之后，那一行即使还在呼吸也看不见。`onViewRecycled` 的 `w()` 与下一次 `A#Y`/`V0#U` 完全不受影响。

时序账：

| 量 | 值 | 来源 |
| --- | --- | --- |
| 淡化时长 | **120 ms** | `LyricInterludeDotsPolicy.RETIRE_MS` |
| 波浪 t99（AMLL 档） | **450 ms** | `LyricLineSpringPolicy.AMLL_SETTLE_MS` |
| 结论 | 点在行到位前约 330 ms 就没有像素了 | 单测 `theDotsLeaveBeforeTheWaveArrives` 钉住这条关系 |

### 5.3 失败/缺失时的行为

- `lyricLineMotion.interlude` 缺失、任一角色解析失败、`q8.m9` 加载不到 ⇒ `open()` 返回 null，
  **弹簧照常 Active**（安装消息里只是没有 `, interlude dots on`），日志一条 `AMPP-Interlude interlude dots unavailable: …`；
- 每个 hook / 每行处理都 `runCatching`，第一次失败打一行 `failed:…`，之后不再重复；
- 开关（「换行弹簧」）关掉时 `enabled()` 立刻返回 false，**一个 `alpha` 都不写**（设置是热读的）。

---

## 6. 已知的宿主坑（本次没改，只记录）

1. **演出时钟 `V0.C` 永不复位**：`U` 只在 `C == null` 时创建它。第二段间奏时 `elapsed` 已经是上一段的累计值，
   `remaining < 0` ⇒ 直接 return ⇒ 第二段间奏**没有任何 tint 扫过**，但 `w()` 也没被调，于是上一套演出继续跑。
   这是宿主自己的状态机缺陷，我们的修复对它无害（我们只写 alpha）。
2. **`AnimatorSet#cancel` 不复位 `scale`**：回收后行根可能停在 1.1x，被复用到下一段间奏时 `[当前, 1.2, 1.0]` 的起点就是 1.1x，
   于是"呼吸"从半程开始。同理我们的淡出不依赖 cancel，而是显式 `alpha = 0` / `animate().alpha(0)`。
3. **`V0#U` 的 `pivot` 依赖 layout 完成**（`A#Y` 用 `isLaidOut && !isLayoutRequested` 判断，否则挂 `OnLayoutChangeListener`，偏移 372-603）：
   换行那一帧如果正在 requestLayout，pivot 会在下一次 layout 才写 —— 与我们的淡出无关，但会让呼吸的形变位置跳一下。

---

## 7. 单测与校验

| 项 | 结果 |
| --- | --- |
| `LyricInterludeDotsPolicyTest`（纯 JVM，22 条） | 全过（本机 kotlin-compiler-embeddable + JUnit 4.13.2 跑通） |
| `scripts/verify-architecture.py` | PASS |
| `scripts/verify-profile-data.py` | PASS（12 profiles / 880 targets；新增的 `interlude` 小节被要求含全部 10 个角色） |
| `scripts/verify-host-profile.py /tmp/am1626/base.apk --version-name 7.0.3 --version-code 1626` | PASS（762 checks，0 failures；其中 11 条来自新增的 `verify_lyric_interlude_contract`，故意改错 `dot2.owner` / `holderPosition.owner` 会 FAIL） |

单测覆盖的正是"取消/加速退场"的状态机：

1. `actionFor`：过去/当前/未来 + `NO_POSITION` 两侧；
2. 淡化曲线 1→0、**在终点恰好是 0**（"不留下残影"）；
3. 第二次 `retire` 不延长 deadline（"不会拖到行到位之后"）；
4. 淡化中途 `arm` 立刻回到 alpha=1（"不影响下一次间奏"）；
5. 从未见过的行 `retire` 仍然真淡化（它的点确实在屏幕上）；已经 HIDDEN 的行则完全无动作；
6. `settle` 正好在 deadline 关闭一次；`retireImmediately` 是"加速退场"的兜底；
7. 连续 5 轮 arm→retire→hidden 后状态不漂移；
8. `LyricInterludeDotsTrack`：一次换行同时 retire 过去的间奏 + arm 当前行、未来行不建条目、
   同一判定不重复上报、淡到期后关闭、seek 回到间奏会重新 arm、容量上限（32）不随播放增长、`forget/clear`。

---

## 8. 真机验收

    adb logcat -c && adb logcat -s AMPP-Interlude AMPP-LineSpring

1. **判据 A（本议题）**：播一首有间奏的歌，等间奏→首句的切换。行的到位过程中**看不到三圆点**；
   `AMPP-Interlude` 上应出现成对的 `armed pos=N`（间奏行成为当前行）与 `retired pos=N fade=120ms`（它被换下去）。
2. **判据 B（不生硬）**：三圆点的消失是 120 ms 淡出，不是"啪一下没了"；出现侧完全没有变化
   （我们只把 alpha 拉回 1，演出还是宿主自己的 tint 扫过 + 呼吸）。
3. **判据 C（连续稳定）**：连续 3 段以上间奏反复切换，每次只有一对 `armed/retired`，不出现
   `retired` 之后又 `armed` 同一行（若出现，说明 `animateMove` 那一遍把它误判成"当前行"，贴日志再看）。
4. **判据 D（无间奏曲目）**：`AMPP-Interlude` 一行都没有，`AMPP-LineSpring` 与改动前完全一致。
5. **判据 E（回退）**：把「换行弹簧」关掉重开 APP，`AMPP-Interlude` 不再出现，圆点行为回到宿主原生（即"会重叠"）。

### 还剩多少残留重叠？

- **零像素重叠**（三颗点）：淡出在 120 ms 内结束，行在 450 ms 才到位，工程上留了约 330 ms 余量。
- **可能残留的**：行根那 4 s 呼吸的 `scale` 仍在跑（我们不取消），间奏行淡出后是空的所以看不见；
  但如果将来宿主的 instrumental 行里加了别的内容，这一段就会重新可见 —— 那时才需要去碰 `V0.A`。
- **另一个与圆点无关的重叠源**：我们的阶梯（`LyricLineStaggerPolicy`）按 `toY` 从最上面一行开始每行 +50 ms，
  而 1626 的间奏行比文本行**矮**（行高 = dot_size + 上下 padding），它与相邻行的间距和文本行不一样 ——
  阶梯相位差会让相邻两行在途中靠得更近。这次没有动它；若真机上仍能看到"行与行擦边"，那是阶梯问题，不是圆点问题。
