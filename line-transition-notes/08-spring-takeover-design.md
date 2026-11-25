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

## 6. 待确认

- `M0` 恒为 1 的语义（可能是“已锚定”），乙是否需要读它；
- `s2()` 的定时来源（每次换行后 ~0.8–1.2 s 一次，queue 恒 0）——若它只是空转，乙无需理会；
- 行动画 `A#N` 的触发条件（本曲 0 次），需要在 instrumental 曲目上复测。