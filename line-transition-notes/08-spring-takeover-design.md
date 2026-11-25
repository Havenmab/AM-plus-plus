# 方案乙（弹簧接管位移）设计 v2 —— 基于 1626 实测

数据来源：真机 probe（`AMPP-LineProbe`，1626）事件 `lineChange` / `anchor` / `animateMove` / `flush` / `summary`。

## 1. 实测事实（推翻了早先的两个假设）

| 观察 | 数值 | 含义 |
| --- | --- | --- |
| 每次换行的 move 数 | `moves/change = 6.45`（单批 4–8 个 holder） | 位移是**整块多 holder** 同时动画，不是单个 |
| 位移量 `dy` | 单批内所有 holder **完全一致**（-357 / -261 / -453；seek 时 2046） | 纯粹的整块平移，`dy = toY - fromY` |
| `ret` | 非零 `dy` 时恒 `1` | 宿主**真的在跑** ViewPropertyAnimator 位移动画 |
| `zero=1 ret=0` | 仅 `dy == 0`（首帧绑定） | 只有零位移才走 `V0` 队列延后路径 |
| `event=flush` | 每次换行后 ~0.76–1.22 s 触发一次，`queue=0`、`ran=0`、`U0 1→0` | `s2()` 定期被调，但本曲**没有延后动画**可冲 |
| `event=rowAnim` | `animEntry=0`、`animText=0`（summary） | 本曲 `A#N` / `V0#N` **一次都没跑** → 行过渡动画是条件性的（instrumental 行），不是每行都有 |
| `anchor` | 每次换行前一次，`off=132`（顶部占位） | 重锚落点固定为 132px 顶部偏移（本机型） |
| `sentinel` | 一次 `start=-1`（seek 到未布局位置） | 与 -1 更正一致 |

结论：**Apple 的“换行”= 整块多 holder 的 `translationY` 动画（时长 `$k.e`）+ 条件性的行过渡动画**。乙要接管的是前者。

## 2. 乙 的接管点与时序

```
K#b(start, end)                       ← 行变化（锚点已由 scrollToPositionWithOffset 落好）
  └─ layout 之后，RecyclerView 对每个可见 holder 调 ItemAnimator.animateMove(holder, fromX, fromY, toX, toY)
       ├─ dy == 0  → 保持宿主行为（ret=0，走 V0/s2 记账）
       └─ dy != 0  → 我们接管：
            1. param.returnResult(false)              // 不让宿主跑 ViewPropertyAnimator
            2. view.translationY = (fromY - toY)      // 起点 = -dy
            3. 在共享时钟上跑标量弹簧 → translationY → 0
            4. 结束时 dispatchAnimationFinished(holder)
```

### 2.1 必须满足的 RecyclerView 契约

| 契约 | 要求 |
| --- | --- |
| `dispatchAnimationStarted(holder)` | 首次接管时调用一次（宿主 `mc.y#m` 内部会做；我们跳过后要自己补） |
| `dispatchAnimationFinished(holder)` | 弹簧到位或被 `endAnimation` 打断时调用**恰好一次**；重复调用会让 RecyclerView 抛 `IllegalStateException`（mPendingMoves 记账） |
| `isRunning()` | 有任一弹簧在跑时返回 true，否则 RecyclerView 会提前认为动画结束 |
| `endAnimation(holder)` / `endAnimations()` | 被复用/回收/列表替换时，必须立刻把该 holder 的 `translationY` 归零并结束弹簧（`onViewRecycled` 也会走这里） |
| `canReuseUpdatedViewHolder` | 保持宿主的 `true`（`$u#b` 语义），否则 change 会退回 change 动画 |

### 2.2 弹簧参数（AMLL 实测映射）

```
mass = 0.9f
ζ（damping ratio）= 1.1595      // AMLL 正常模式恒定略过阻尼
c = 2·ζ·sqrt(k·m) = 2.2·sqrt(k)
k = 170 · (0.484 / T)^2         // T = 目标 t99（秒），AMLL 的 k=170 对应 t99≈0.484 s
```

| 目标 | k | c |
| --- | --- | --- |
| T=0.30 s | ≈ 442 | ≈ 46 |
| T=0.43 s（AMLL 正常） | 170 | 29 |
| T=0.60 s（Apple 默认中段） | ≈ 111 | 23 |
| T=0.79 s（Apple 最慢） | ≈ 64 | 18 |

> 口径（2026-11 补记）：表里的 480–792 ms 是**宿主自己算出来的**值（`lerp(480,800,gap)`），
> 但 `$k.e` 被甲就地改写，所以**运行时字段值 = 宿主值 × 甲倍率**；现有 probe 数据是甲 = 200% 时抓的
> （见 `07-probe-readme.md` 的补记）。乙的 `T` 一律取“`animateMove` 被调用那一刻 `$k.e` 的当前值”，
> 即已含甲倍率，**不要**再乘一次；原生基准需要在甲关闭（滑杆 100%）时重抓。

甲 的滑杆（0.5–2.0×）可继续复用：`T = hostDuration × scale`，再由上式换算 `k`；两者共存时乙优先，甲在乙关闭时生效。

### 2.3 何时不该接管

- `|dy| > 1200 px`（seek / 换歌的大跳）：直接让宿主跑（或瞬移），弹簧会拖出“飞过整屏”的观感；
- `dy == 0`：保持宿主分支（`V0`/`s2` 记账）；
- holder 正在被回收 / `view.parent == null`：放弃接管，交回宿主；
- 用户在设置里关闭乙：完全不 hook。

## 3. 与宿主记账的关系

- 我们**只**接管 `dy != 0` 的 move；`dy == 0` 的延后路径原样保留，`V0`/`s2` 语义不动。
- 宿主 `mc.y#m` 在 `dy != 0` 时原本会 `dispatchAnimationFinished` 并返回 true；我们返回 false 后必须自己补这个回调（见 2.1），否则 RecyclerView 的 pending move 会永久挂起。
- `A#N`/`V0#N` 行动画本曲未触发；乙不碰它。若将来在 instrumental 曲上触发，它与我们的 `translationY` 不冲突（不同属性）。

## 4. 风险清单

1. **重复 dispatch**：弹簧被 `endAnimation` 打断时必须不再补一次 finished；用一个 per-holder 的 `finished` 标志。
2. **holder 复用**：`onViewRecycled` 会调 `endAnimation`；若我们没把 `translationY` 归零，复用的行会带着偏移出现。
3. **多 holder 同批**：一次换行 4–8 个 holder 共用同一 `dy`，必须共享同一个时钟与参数，否则行与行之间会“撕裂”。
4. **平板双栏**：两个 RecyclerView 各自一个 animator，session 需按 animator 实例绑定（不要按类）。
5. **帧率**：8 个 holder 同时弹簧 ≈ 每帧 8 次标量积分，成本可忽略；但不要每帧 `requestLayout`。

## 5. 分期

| 期 | 内容 | 验收 |
| --- | --- | --- |
| 乙-1 | hook `mc.y#m`、`dy != 0` 接管、写 `translationY`、弹簧、`dispatchAnimationFinished` | 连续换行 20 次不卡死（RecyclerView 不报 pending）、行不错位 |
| 乙-2 | `endAnimation`/`endAnimations`/复用清理、seek 大跳回退 | 快速滑动/切歌/拖动进度条无残影 |
| 乙-3 | 甲滑杆映射到 `T`、曲线参数暴露、平板双栏验证 | 与甲手感一致可调 |

## 5.5 视觉层范围（产品决定，2026-11-25）

**不做模糊**。AMLL 的 `blur = min(5, 1 + 行索引距离)` 不移植：模块已有独立的「双向歌词模糊」功能，换行动画这条线只做位移（乙-1/乙-2），最多再做行 `scale`（0.97/1.0）的轻量层次，不改 opacity、不加 RenderEffect。

## 6. 待确认

- `M0` 恒为 1 的语义（可能是“已锚定”），乙是否需要读它；
- `s2()` 的定时来源（每次换行后 ~0.8–1.2 s 一次，queue 恒 0）——若它只是空转，乙无需理会；
- 行动画 `A#N` 的触发条件（本曲 0 次），需要在 instrumental 曲目上复测。

## 7. 乙-1 实际使用的 1626 契约（DEX 复核）

实现时按 1626 DEX 逐条核对了 §2.1 那张表，结论写进 profile 的 `lyricLineMotion.spring`：

| 角色 | 1626 成员 | 说明 |
| --- | --- | --- |
| `animateMove` | `mc.y#m(ViewHolder,int,int,int,int)Z` | 非零分支写 `translationY = -dy`、入队 `mc.y$e`、返回 `true`，**不**派发任何通知 |
| `isRunning` | `mc.y#g()Z` | 逐个检查 11 个动画列表；`RecyclerView$k#g()` 是抽象声明 |
| `endAnimation` | `mc.y#e(ViewHolder)V` | 取消 VPA、把该 holder 从各 pending 列表摘掉并清 `translation` |
| `endAnimations` | `mc.y#f()V` | 同上遍历全部；其间 `if (!isRunning()) return` |
| `dispatchAnimationFinished` | `RecyclerView$k#c(ViewHolder)V` | `setIsRecyclable(true)` + 清 shadowed + `removeAnimatingView`；`mc.y#m` 的零位移分支与所有 VPA `onAnimationEnd` 都调它 |
| `duration` | `RecyclerView$k.e : long` | 就是甲改写的那个字段，所以乙读到的 `T` 已经含甲的倍率（**不要再乘一次**） |

两处与 §2.1 不同，都以 DEX 为准：

1. **1626 没有 `dispatchAnimationStarted`。** `ItemAnimator` 里它是个空 final 方法，R8 直接删掉了。
   真实 move 动画发出的“开始”通知是 `SimpleItemAnimator.dispatchMoveStarting` = `RecyclerView$F#q(ViewHolder)V`，
   调用点就是 move 的 VPA 监听器 `androidx.recyclerview.widget.j#onAnimationStart`。乙接管时补的就是它
   （反射调基类方法 → 虚分派到 `PlayerLyricsViewFragment$u#q` → `i1#I`）。
2. **被 hook 的四个方法必须声明在 `mc.y` 上，不能声明在 `RecyclerView$k`。** 后者是抽象声明，
   `getDeclaredMethod` 能解析到，但虚分派永远走 `mc.y` 的实现 → hook 不触发。profile 与
   `verify-host-profile.py` 都按“被 hook 的 owner 必须等于 `classes.animator`、被调用的 owner 必须在该
   animator 的继承链上”来校验。

因此乙的 `T` 就是宿主当前 `$k.e` 的毫秒值：甲开启时它已被甲乘过倍率，乙读到即所得；账面 t99 ≈ 1.31·T，
这是常量配比的必然结果，不是映射错误。**那个 1.31 的成因在 §8.1 里查清了**：乙-1 的双指数精确解与
AMLL 在 ζ≥1 时用的临界阻尼形状之间的比值；乙-2 两档手感就是围绕这一点分的。

## 8. 乙-2：每行一个弹簧 + 阶梯延迟 + 两档手感

乙-1 上线后功能可用（无卡死、无残影），但与 AMLL 差得远。复核 AMLL 源码后，差距不是参数而是**结构**：

| 维度 | AMLL | 乙-1 | 乙-2 |
| --- | --- | --- | --- |
| 位移单元 | **每行一个 `posY` 弹簧**（`base/group.ts:20`） | 整块一个标量弹簧 | 每行一个弹簧（`LyricLineSpringWave`） |
| 起步 | **阶梯延迟** 50 ms/行，焦点行以下每行 `/1.05` 收紧（`base/index.ts:960-1006`） | 同帧一起起步 | 同上 |
| 换行连续性 | `resetSolver()` 保留速度，只换目标 | 每批从静止重开 | 每行保留速度，`-dy` 直接成为新 lag |
| t99 | 0.425–0.484 s | 1.31·T（0.63–1.04 s） | AMLL 档 0.450 s / HOST 档 1.31·T |

### 8.1 「1.31」是从哪来的（本次的关键发现）

AMLL 的 `utils/spring.ts` 在 `zeta >= 1`（`soft || 1 <= damping / (2 sqrt(k m))`）时**不**用双指数叠加，而是用临界阻尼形状：

```
x(t) = (x0 + t (v0 + omega0 x0)) e^(-omega0 t)      // omega0 = sqrt(k/m)
```

它的 1% 门限是 `(1+u) e^-u = 0.01` → `u = 6.64` → `t99 = 6.64 / omega0`，与 k 的关系是 `t99 ∝ 1/sqrt(k)`（这也正是 02 号笔记 §3.3 表里 0.425/0.484 s 的来历）。乙-1 用的是**精确过阻尼解**，衰减率是最慢根 `omega0 (zeta - sqrt(zeta^2-1)) = 0.53 omega0`，再加上多项式因子，1% 门限落在 `1.31 / omega0` 量级 —— 所以**同一个 k 下乙-1 比 AMLL 慢 1.31 倍**，这就是 `t99 = 1.31 T` 的来源，不是映射错误。

乙-2 因此把「曲线形状」也做成手感的一部分（`LyricLineSpringDecay`）：AMLL 档用 AMLL 的形状，HOST 档保留乙-1 的精确解（RK4 交叉验证那条单测继续守着它）。

### 8.2 两档手感（`lyricLineSpringFeel`）

`k = 170 (0.484 / t)^2`，`c = 2.2 sqrt(k)`，`m = 0.9`，`zeta = 1.1595`；只有 `t` 的来源不同：

| 档 | `t` | k | c | 曲线 | 实测 t99 | 与甲的关系 |
| --- | --- | --- | --- | --- | --- | --- |
| `AMLL`（默认） | 450 ms 固定 | 196.7 | 30.9 | 临界阻尼形状 | **0.450 s** | 忽略甲 |
| `HOST`（乙-1） | `$k.e`（含甲倍率） | 63–443 | 18–46 | 精确过阻尼解 | **1.314–1.317·T** | 甲生效 |

`AMLL` 档的 `$k.e` 只用来做「这确实是歌词 animator 的字段」的合理性检查（100–1600 ms），不参与参数。

### 8.3 状态机（`LyricLineSpringWave`）

每个 holder 一份状态：`spring`（闭式解）、`elapsedMs`（自己的时钟）、`delayMs`（剩余起步延迟）、`finished`（欠一次 finished）、`layoutTop`（本批排序键）。

1. **`push`（hook 准入通过后）**：`lag = -dy`。宿主 1626 的 `mc.y#m` 算的是 `dy = toY - (fromY + translationY)`，已经把当前 translation 折进去，因此 `-dy` **就是新的绝对 lag**，写入即刻保证视觉连续。已在飞行中的行：先补上一次的 `finished`（不归零），再带着当前速度重新瞄准。
2. **阶梯**：一次换行的 push 都在同一个 layout pass 里，所以「本帧 push 的行」= 一次换行。下一帧 `advance` 按 `toY` 排序（AMLL 的行序），焦点线取 `0.35 × 列表高度`，逐行 `delay = 50 ms`、焦点行以下 `/1.05`。
3. **延迟未到**：只写不动（`push` 已写过起点），不推进弹簧，也不消耗 deadline。
4. **在飞行的行不给新延迟，只修正目标**：AMLL 是把「新目标」排队、行继续跑旧目标；我们的 layout 已经跳过，等价做法是立即重瞄。宿主算的是 `dy = toY - (fromY + translationY)`，所以新的 `-dy` 已经是**绝对** lag：视觉位置不动、只挪终点，这就是 iOS「一次单调上移、终点已含 bg 行高度」的语义。因此**不再把后续 move 当第二次冲量**：
   * 只有当携带的速度仍然指向新终点（`v · lag < 0`）时才保速；否则清零（否则会冲过终点再回落，观感就是"被推一下再弹回来"）；
   * 还在阶梯延迟里的行**保留剩余延迟**（它的 layout 动了、视觉没动，本来也没有速度）。
   若给在飞行的行重新排 50–250 ms，它会中途定格、lag 逐行累积 —— 这是唯一一处与「延迟按新批次重算」字面不同的地方。
5. **逐帧读回 layout**：bg 行的高度动画是 `ValueAnimator` 逐帧改 `layoutParams.height`，**没有 adapter 通知 ⇒ 没有 `animateMove`**。所以 `advance` 每帧读一次被跟踪行的 `itemView.top`，与"上一次 move 的 `toY` / 上一帧读到的值"比较；不同就把差值折进 residual（`lag -= delta`，视觉不动）并重瞄弹簧（方向翻转则丢速度）。两条拒绝：**所有被跟踪行同幅度位移**（那是滚动/重锚）、**单帧超过 240 px**（回收行/跳转）都只重设基线。
6. **到位**：`|lag| <= 0.5 px` **且** `|v| <= 30 px/s`（0.5 px / 一个 60 Hz 帧）；归零 + 逐个 `dispatchAnimationFinished`。deadline（`1.6 × t99`）是兜底，防止极端 re-target 让 `isRunning()` 无限为真。
7. **契约**：`endAnimation(holder)` 归零 + 恰好一次 finished；`endAnimations()` 遍历该 animator 的 wave；收到 rest 时同样恰好一次。乙-1 的「每行一个 finished 标志」和回退路径原样保留。

### 8.4 已知的取舍

- **不再刚性**：行与行之间有 0–250 ms 的相位差，这是有意的（AMLL 就是这个观感）；单行位移仍是一行行距，不会撞行。
- **平板双栏**：wave 仍按 animator 实例隔离（`IdentityHashMap`），两栏互不共享时钟或行。
- **甲 / probe 未动**：甲只写 `$k.e`，probe 只读；AMLL 档读 `$k.e` 但不使用它的数值。

### 8.5 单测（`/tmp/ktool`，JVM）

`LyricLineSpringPolicyTest`（17 条）：两档 ζ、`k=170(0.484/t)^2`、AMLL 档 t99=450±5%、HOST 档 1.31T±5% 且比值恒定、AMLL 形状 = 临界阻尼（与同参数精确解差 1.31 倍）、HOST 闭式解与 RK4 一致、单调无过冲、到位门限、`appliedFeel` 双开关门控。

`LyricLineSpringWaveTest`（21 条）：同一次换行同起点 + 按视觉序起跑、50 ms 阶梯与 1/1.05 斜率、焦点线推导、延迟未到不动、re-target 保速（同向时）并累加 lag、连续 3 批×6 行 = 恰好 18 次 finished、`endAnimations` 中途打断恰好一次、`endAnimation` 单行、在飞行中加入的新行拿到自己的 lag、帧步钳制；目标修正五条：换行 + bg 展开 = 一次单调上移（317 px）且视觉不走回头路、延迟内修正保留槽位且起点不动、终点翻到另一侧时丢速度且无过冲、bg 反复展开/收起 residual 收敛（不超出行位移本身）、首次 move 报告为"新行"而不是"修正"。

逐帧布局跟踪四条：**没有任何 move 的** bg 展开（12 帧 × 4 px = 48 px）被逐帧吸收、视觉 y 单调不回退、终点 = 位移 − 48；每行同幅度的位移按"滚动/重锚"处理、不吸收；move 与逐帧 delta 不重复计数；超界位移只重设基线。

> 修订（2026-11-25）：第 1 条的"保速"改成了**有条件保速**（见 §8.3 第 4 点）。
> 宿主对同一个 holder 可能在一屏内发多次 `animateMove`（重锚、批量 notify、bg 行高度动画落在一次带动画的 layout 里），
> 每次都当成第二次冲量就会多出一段"往回走"的目标。现在一律按**目标修正**处理：只挪终点，速度只在还指向终点时保留。

