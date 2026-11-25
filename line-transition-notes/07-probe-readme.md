# 07 · 换行诊断（probe）使用与判读手册

> 本文只讲**方案乙 P0 的只读打点**：怎么打开、每行日志什么意思、真机上该做什么、数字到什么范围算“假设成立”。
> 宿主事实见 `01-lyrics-line-transition-1626.md`；目标形态见 `03-amll-spec-v1.md`。
> 版本线：Apple Music **7.0.3 / versionCode 1626**（其它版本没有这些角色，probe 会直接报 Unsupported）。

打点的目的只有一个：**在接管换行之前，先用数字确认宿主的真实行为**。
它不改变任何可见行为——不改 `translationY`、不改 scale/alpha、不改时长、不碰 `V0` 队列，也不写 `view.tag`。

---

## 1. 怎么打开

1. 模块设置 → **歌词效果** → 打开 **「换行诊断（临时）」**（英文 Line-change probe (diagnostic)）；
2. **重开 Apple Music**（和其它歌词效果一样，开关决定的是“装不装 hook”）；
3. 抓日志：

```bash
adb logcat -s AMPP-LineProbe
```

设置键是 `lyric_line_motion_probe_enabled`（Boolean，默认 `false`）。CLI 侧只能通过模块自己的设置存储改这个键，
没有单独的命令行入口；嫌设置页麻烦时脚本改键即可，但**改完必须重开 Apple Music**。

关掉开关 = **一个 hook 都不装**：不是“装好再过滤”，而是这条能力根本不会被创建（`LyricLineMotionProbeFeature`
在开关关闭时直接返回 `disabled`）。所以关闭状态下的开销是零，也不存在漏装过滤器导致的行为差异。

> 「换行诊断」与「歌词换行节奏」「歌词效果优化」**互相独立**：probe 要测的是宿主自己的行为，
> 校准期间请把「歌词换行节奏」保持在 100%。

安装结果会出现在模块的功能健康里（Active / Degraded），Degraded 会写明**哪几个角色没解析到**。

---

## 2. 日志总览

* 统一 tag：`AMPP-LineProbe`（同时走 `ModernXposedRuntime.log` 与 `android.util.Log`）；
* 每行以 `event=` 开头；
* 读不到的值（角色没解析到、或宿主此刻没有该状态）一律打印 `n/a`，**不会静默变成 0**；
* 位置 `pos=-1` 是宿主的 `NO_POSITION`（见 §5）。

| event | 何时打印 | 回答什么问题 |
| --- | --- | --- |
| `lineChange` | `K#b(start,end)` 返回后 | 谁触发了换行、是否真的重锚、当前可见区间、队列状态 |
| `anchor` | `LinearLayoutManager#u1(pos,off)` 调用前 | 瞬时重锚落到哪一行、offset 是多少 |
| `animateMove` | `mc.y#m(...)` 返回后 | 每次换行是否都过一次 move、位移是否恒为零 |
| `flush` | `s2()` 进入/退出 | 延后队列里有多少东西、等了多久、跑了几个 |
| `rowAnim` | `A#N` / `V0#N` 进入时 | 行过渡入口是否也会被**文本行**走到 |
| `summary` | 每 20 次**真实**换行 | 上面四件事的一次性汇总，便于横向对比 |

### 2.1 限流

* **每个 event 每秒最多 10 行**；超出的行被丢弃并计数，计数会在该 event 的**下一行**以 `suppressed=N` 打出来，
  所以“被吞了多少”永远可查；
* 一次进程最多 4000 行，到顶就彻底停（不会把 logcat 灌满）；
* `summary` 每 20 次真实换行一条（见 §3 的窗口定义）。

---

## 3. 每种日志的字段

### `event=lineChange`

```
event=lineChange start=12 end=12 sentinel=0 anchor=1 first=9 last=18 queue=0 T0=0 U0=1 M0=0
```

| 字段 | 含义 |
| --- | --- |
| `start` / `end` | `K#b` 的两个入参（高亮行区间）。`-1` = 宿主自己的 `NO_POSITION` |
| `sentinel` | `start==-1 || end==-1`。**为 1 时宿主会跳过重锚分支**，这次不算“换行” |
| `anchor` | `start != -1` → 1，表示这次回调会调 `scrollToPositionWithOffset`（1626 的实际分支方向，见 §5） |
| `first` / `last` | `findFirstVisibleItemPosition` / `findLastVisibleItemPosition`（在 after 里取，反映的是**这次重锚之后**已经开始的新布局请求） |
| `queue` | `V0`（延后队列）当前长度 |
| `T0` | “宿主已经把滚动从用户手里抢过来” |
| `U0` | “队列正在被冲刷”，即行过渡会**当场**跑而不是排队 |
| `M0` | “当前行已经重锚过一次” |

### `event=anchor`

```
event=anchor pos=12 off=64
```

| 字段 | 含义 |
| --- | --- |
| `pos` | `scrollToPositionWithOffset` 的目标 position |
| `off` | 目标行的顶部偏移（= ItemDecoration 的 `$x.a`，即“当前行停在屏幕哪个高度”） |

只对**歌词那个 LayoutManager** 打印（`PlayerLyricsViewFragment$w` 的实例，或从 `K#b` 学到的那个实例），
App 里其它 RecyclerView 的 `scrollToPositionWithOffset` 不会出现在日志里。

> 一条 `lineChange anchor=1` 后面紧跟一条 `anchor pos=<start>`，就是“换行 = 瞬时重锚”的直接证据。

### `event=animateMove`

```
event=animateMove pos=12 fromY=0 toY=0 ty=0 dx=0 dy=0 ret=0 zero=1 queue=3 U0=0
```

| 字段 | 含义 |
| --- | --- |
| `pos` | holder 的 `getLayoutPosition()` |
| `fromY` / `toY` | `animateMove` 的入参 |
| `ty` | 该行 itemView 的 `translationY`（宿主计算 dy 时会把它加进 fromY） |
| `dx` / `dy` | probe 按宿主的算法重算的位移：`dx = toX-(fromX+tx)`、`dy = toY-(fromY+ty)` |
| `ret` | 宿主返回的 boolean：`0` = 走了**零位移分支**，`1` = 真的排了一个位移动画 |
| `zero` | `ret==0`，即这次 move 被判定为零位移 |
| `queue` / `U0` | 返回时 `V0` 的长度与 `U0` |

`ret=0` 同时意味着宿主已经调过 `RecyclerView$k#c(holder)`（`dispatchAnimationFinished`）并把
“跑这一行的 `$u#s` → `i1#N`” 塞进了 `V0`（`U0=1` 时是当场跑）。这条链路在 DEX 里是顺序指令，所以返回值本身就足够判定。

### `event=flush`

```
event=flush phase=before queue=3 U0=1 T0=0 M0=0 sinceChange=412ms sinceQueue=398ms
event=flush phase=after  queue=0 U0=0 T0=0 M0=0 ran=3 dur=1ms
```

| 字段 | 含义 |
| --- | --- |
| `phase` | `before` = `s2()` 进入，`after` = 返回 |
| `queue` | `V0` 长度（`after` 正常应为 0） |
| `T0`/`U0`/`M0` | 同 `lineChange` |
| `sinceChange` | 距离**最近一次 `K#b` 进入**的毫秒数 —— 这就是“延后播放”的真实延迟 |
| `sinceQueue` | 距离**最近一次零位移 move 入队**的毫秒数（更精确的“排队等到什么时候”） |
| `ran` | `before.queue - after.queue`，即这一次冲刷跑了几个 runnable |
| `dur` | `s2()` 自身耗时（微秒级，用于确认冲刷本身不卡） |

> 注意：`U0=1` 时宿主**不会**入队，而是原地 `runnable.run()`。这种情况下不会有 `flush` 行，
> 也就没有“延后”——这本身就是结论（见 §4 判读 3）。

### `event=rowAnim`

```
event=rowAnim adapter=A pos=12 binding=q8.m9 instrumental=1 scale=1.00/1.00 alpha=1.00 dur=750+250
```

| 字段 | 含义 |
| --- | --- |
| `adapter` | `A` = 逐词适配器，`V0` = 行级适配器（谁在跑取决于这首歌有没有逐词时间轴） |
| `pos` | holder 的 layout position |
| `binding` | holder 的 ViewDataBinding 类名：`q8.m9` = instrumental 行，`q8.o9` = 文本行 |
| `instrumental` | binding 是否为 `q8.m9`。**这就是宿主自己的门槛**：不是 instrumental 时方法会立刻返回 |
| `scale` / `alpha` | 进入时行根 view 的 `scaleX/scaleY` 与 `alpha`（正常情况下是 1.00/1.00，复位后的基线） |
| `dur` | **常量**：放大 750 ms、缩小+淡出 250 ms。两个时长在方法体里是内联字面量，读不到，故按 DEX 常量打印 |

### `event=summary`

```
event=summary changes=20 sentinel=1 moves=40 moves/change=2.00 zeroDx=true animEntry=20 animText=0 s2Delay=402ms
```

| 字段 | 含义 |
| --- | --- |
| `changes` | 本窗口内**真实**换行次数（`sentinel=1` 的不算） |
| `sentinel` | 本窗口内 `-1` 回调次数 |
| `moves` | `animateMove` 次数 |
| `moves/change` | `moves / changes` |
| `zeroDx` | 本窗口**每一次** move 都是零位移才为 `true`；窗口内没有 move 时为 `n/a`（不是 `false`） |
| `animEntry` | `A#N`/`V0#N` 进入次数 |
| `animText` | 其中 **binding 不是 `q8.m9`** 的次数 —— 也就是“文本行也走行动画”的直接计数 |
| `s2Delay` | 本窗口 `flush` 的 `sinceQueue` 均值；没有 flush 时为 `n/a` |

窗口定义：**每次真实换行计一次**；第 20 次之后、**下一次换行进入时**结算并清零。
放在“下一次进入时结算”是为了让这一窗口包含第 20 次换行自己引发的 move 与 flush
（若在计数到 20 的瞬间结算，会系统性地丢掉最后一次换行的 move）。代价是**最后一个窗口要多一次换行才会打出来**。

---

## 4. 真机上要跑的四种操作

每种操作单独跑一轮，每轮之间用 `adb logcat -c` 清一次，方便对齐 `summary`。

### (1) 连续换行（基线，最重要）

播放一首**歌词密集**的歌（间奏少、行距短），正常播放 40–60 秒，等至少 2 条 `summary`。

看：`changes` 涨得动、`moves/change` 在 1–2、`zeroDx=true`、`anchor` 与 `lineChange anchor=1` 一一对应。

### (2) seek 5 行（验证跨度大时的行为）

暂停 → 把进度条往前拖 5 行左右 → 继续播放。观察这一跳是**一次**重锚还是**多次**、`animateMove` 是否照样零位移、
`anchor off=` 是否还是同一个值（即“停靠高度与跳多远无关”）。

### (3) 手动拖动（验证用户抢滚动时的抑制）

在歌词列表上用手指上下拖，松手后等 1–2 秒。看：
* 拖动过程中 `lineChange` 是否被 `T0/U0` 的门槛挡掉（`anchor=0` 或干脆没有 `anchor`）；
* 松手后是否有一次“吸回当前行”的 `anchor`；
* `U0` 在拖动期间是否为 1（用户在动 → 行过渡会不会当场跑）。

### (4) 切歌（验证重置）

播放中切到下一首，等歌词重新加载。看：
* 切歌后第一条 `lineChange` 是否是 `start=0`（装歌的 `scrollToPositionWithOffset(0,0)`）；
* `M0` 是否重新变 0；
* `queue` 是否被清空（`flush ran=` 一次跑掉全部）。

---

## 5. 判读标准

> 这一节是**结论表**：数字落在什么范围，对应什么判断。全部来自 1626 的 DEX 事实 + 打点应当呈现的形状。

| # | 判据 | 期望 | 含义 / 不成立时怎么办 |
| --- | --- | --- | --- |
| 1 | `moves/change` | **1–2** | 每次换行至少一次、最多两次 `animateMove`（换下的行 + 换上的行）。`=0` 说明换行根本不走 ItemAnimator（我们的接入点错了）；`>2` 说明一次换行触发了多批通知（`i1#P` 的 `notifyItemChanged` 批量） |
| 2 | `zeroDx` | **true** | 位移全部是零位移：行不靠 ItemAnimator 滑，滑的是 ListView 的瞬时重锚。“方案乙接管位移”的前提成立 |
| 3 | `s2Delay` | **≈ 行距对应的宿主时长**（480–792 ms，行距越大越接近 792） | `ret=0` 的 move 把行过渡塞进 `V0`，等列表稳定才冲。若 `s2Delay` 远小于 480 ms，说明 `U0=1` 当场跑了（没有真正的延后）；若 `s2Delay=n/a`，说明整个窗口没有 flush，同样是“当场跑” |
| 4 | `animEntry` / `animText` | `animEntry>0` 且 `animText=0` | 行过渡入口只服务 instrumental 行 —— 文本行的“换行视觉”确实不在这里。若 `animText>0`，则 `01` 的“只有 instrumental 行有证据”需要改写，且 §P1 的 blur/scale 要重新评估所有权 |
| 5 | `anchor` 行数 | 与 `lineChange anchor=1` **一一对应** | 每次真实换行恰好一次 `scrollToPositionWithOffset`，没有额外滚动 |
| 6 | `anchor off=` | 恒定（同一屏、同一字号） | 停靠高度由 `$x.a` 决定，与“跳几行”无关；改这个值就能改“当前行停在哪”（甲方 P1 的旋钮） |
| 7 | `lineChange first/last` | 目标行落在 `[first,last]` | 重锚生效。若目标行长期不在区间内，说明装饰器高度与重锚没对上 |
| 8 | `ret` 分布 | 绝大多数 `ret=0` | 真位移（`ret=1`）应当只在装歌/切歌的首帧出现；播放中的换行出现 `ret=1` 就是“宿主有时会真的滑动”，方案乙必须处理这条分支 |
| 9 | `flush ran=` | 与 `queue`（before）相等，`queue`（after）=0 | 冲刷是清空式的，没有残留；若 `after.queue>0`，说明冲刷期间又入队了（需要复刻这个语义） |

### 关于“15 哨兵”的一处更正

`01-lyrics-line-transition-1626.md` 里把 `K#b` 里的 `const/4 #15`（显示成 15）当作“无当前行哨兵”。
按 `const/4` 的符号扩展，它的真值是 **`-1`**（`0xf`），也就是 `RecyclerView.NO_POSITION`；`mc.y.u` 的初值、
`findFirstVisibleItemPosition()` 的空返回值都是同一个 `-1`。日志里一律打印真值，所以：

* `start=-1` / `sentinel=1` 才是那个哨兵；
* 分支方向也和 `01` 的伪码相反：**`start != -1` 时才重锚**（`anchor=1`），`start == -1` 时跳过，
  实测点就是 `anchor` 行只跟在 `anchor=1` 后面。

---

## 6. 这些数据回答/不回答什么

**被回答的**

1. 每次换行是否都经过 `animateMove`、是否恒为零位移（判据 1/2/8）；
2. “瞬时重锚”的落点与频率（判据 5/6/7）；
3. “延后播放”的真实延迟，以及 `U0` 会不会让它变成“当场播放”（判据 3/9）；
4. 文本行是否也走 `A#N`（判据 4）——`03-amll-spec-v1.md` §6 的问题 2。

**不被回答的**

1. 文本行**到底**靠什么做视觉变化（databinding 表达式，需另解 AXML；probe 只能证明它不经 `A#N`）；
2. `RecyclerView$g#e(pos,1)` + `notifyItemInserted` 那对通知的真实意图（本 probe 不挂适配器通知）；
3. `A#M` 里 `d2(frag,-500)` 的业务含义；
4. 帧率/掉帧：probe 只记事件时间，不记帧时间。需要时用 `gfxinfo` 对照。

---

## 7. 关闭与清理

* 设置页关掉开关 → 重开 Apple Music：不再装任何 hook；
* probe 本身不写入任何持久状态（不写 `view.tag`、不改字段、不改队列），关掉后宿主行为立即回到原生；
* 这一整套是**临时诊断**：P0 出结论后，`LyricLineMotionProbeFeature`、`AppleMusicLyricLineProbeTarget`、
  `LyricLineProbe`、`LyricLineProbeRoles`、profile 的 `lyricLineMotion.probe` 节与设置页那个开关应一起删除。
