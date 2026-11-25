# AMLL 换行动画规格 v1（对照 Apple Music 1626）

范围：只考虑 1626。输入：

- `01-lyrics-line-transition-1626.md`（Apple 宿主的真实实现）
- `01-line-change-motion-feasibility.md`（模块接入点与风险）
- `02-amll-posy-spring-spec.md`（AMLL 运动核心）
- `02-amll-per-line-visual-model.md`（AMLL 每行视觉）

## 1. 两种模型的本质差异

| 维度 | Apple 1626 | AMLL |
| --- | --- | --- |
| 位移 | `scrollToPositionWithOffset` 瞬时重锚，`requestLayout()` | 标量弹簧驱动的整块 `translateY`，解析闭式解，帧率无关 |
| 换行触发 | 适配器回调 `K#b(start,end)`（时间线副作用） | 同：`scrollToIndex = min(高亮集)`，随 `setCurrentTime` 推送更新，空隙期焦点停在上一行 |
| 位移时长 | `$k.e` = 480–792 ms（按行距 `lerp`） | 距离无关，t99≈0.43 s（k 170–220，ζ=1.16） |
| 行缩放 | `A#N`：当前行 scale→1.2（750 ms）→ 0.5 + alpha→0（250 ms），仅 instrumental 行有证据 | 只有 97→100 的两档弹簧（ζ=0.884，无可见过冲）；BG 行 0.75 |
| 明暗 | 高亮/非高亮（宿主自有） | 二值：高亮 0.85 / 静息 0.2；无连续渐变 |
| 模糊 | 无（宿主不用 blur） | `level = 1 + 行索引距离`，`min(5, level)` px，上方邻行 +1、下方不加 |
| 强调 | 行级 scale 序列 | 逐字符脉冲（≤1.12、辉光 0.3em、需词时长 ≥1 s）；行级无强调 |
| 帧循环 | 无（依赖 View 动画 + RecyclerView 动画） | 单一 rAF，`dt` 钳 100 ms |

结论：**Apple 是「瞬时重锚 + 延后的一次性行动画」，AMLL 是「连续弹簧位移 + 二值视觉状态」。** 两者不能混用参数，只能选一种作为产品形态。

## 2. 参数表（可直接落 Kotlin 常量）

### 2.1 AMLL 运动（来自 02-amll-posy-spring-spec）

```
mass = 0.9f
seek/间奏/首行：k=90f  c=15f        (ζ=0.833)
歌末：          k=140f c=22f        (ζ=0.98)
正常：          k=170f..220f, c=2.2f*sqrt(k)  (ζ=1.1595)
dt 上限 100 ms；停帧闸门建议 0.01dp + 0.5dp/s（AMLL 的 |a|<0.01 会多跑 ~0.8 s）
对齐：alignPosition = 0.35（焦点行中心在容器 35% 高度）
overscan = 300 CSS px（≈ 1.667 dp/px 换算后按屏幕密度取值）
```

### 2.2 AMLL 每行视觉（来自 02-amll-per-line-visual-model）

```
opacity: 视口外 0 / 已唱 1e-4 / 高亮 0.85 / 逐词 1.0 / 非逐词 0.2
blur:    level = 1 + 行索引距离，min(5, level) px（窄屏 ×0.8），过渡 400 ms ease
scale:   非高亮 0.97，当前行 1.0；暂停时全体 1.0；BG 0.75
scale 弹簧：m=2 k=100 c=25；BG：m=1 k=50 c=20
写入阈值（Android 建议）：Y 0.5dp / opacity 0.01 / blur 0.05dp / scale 0.001
```

### 2.3 Apple 1626 的实测值（来自 01-lyrics-line-transition-1626）

```
moveDuration  $k.e = round(lerp(480, 800, (clamp(gap,200,750)-200)/550))  // 480–792 ms
行过渡       scale 1.2, 750 ms, cubic-bezier(0.25, 0.1, 0.25, 1)
             scale 0.5 + alpha 0, 250 ms, cubic-bezier(0.25, 0, 1, 0.2)
位移         瞬时（scrollToPositionWithOffset）
```

## 3. 1626 上我们能改什么（可行性结论）

| 能力 | 手段 | 风险 |
| --- | --- | --- |
| 观察换行 | hook `K#b(II)` 或 `LinearLayoutManager#u1(II)` | 无（只读） |
| 改停靠高度 | 改 `$x.a` 后 `RecyclerView#S()` | 低（ItemDecoration 重算） |
| 改节奏 | 覆盖 `$k.e` 或替换 `A.i0` 的 bezier | 低（只改数值） |
| 整块弹簧位移 | 拒绝 `animateMove` 的位移动画并在 `translationY` 上自跑标量弹簧 | **中高**：`mc.y#m` 里夹着宿主记账（`V0` 队列 + `U0` + `J4.t2`），必须复刻“零位移→延后”语义 |
| 完全自定义行动画 | `setItemAnimator(ours)` | **高**：需自行实现 `canReuseUpdatedViewHolder=true`、延后队列、`s2()` 冲刷时机 |
| 行级 blur/scale | 在行 View 上写 `RenderEffect` / `setScaleX/Y` | **中**：与宿主 `V0$N`/`A$N` 的所有权冲突，必须先让步或接管 |

单一写入者规则：行缩放的所有权目前在宿主（`V0#N` 写 pivot、`V0$b`/`A$s#onAnimationEnd` 写 scale、`V0#l`(bind) 读 scale）。**要么不碰，要么完整接管**，不能各写一半。

## 4. 推荐形态（待拍板）

**方案甲（Apple 形态增强）**：保持瞬时重锚 + 宿主的 `A#N` 序列，只调 `$k.e` 与 bezier，并可选加行级 blur。改动小、风险低、观感仍“像 Apple”。

**方案乙（AMLL 形态）**：接管位移为标量弹簧（`translationY = blockPosY`），行视觉改为 0.97/1.0 两档 + 索引距离 blur，强调留给逐词。观感更“AMLL”，但要完整复刻宿主记账，且必须与 `A#N` 的行动画择一。

建议：**先做方案甲的可调参数版（P1），把 PoC 打点做出来后再决定是否升级到乙**。理由是甲不触碰 `mc.y` 的记账语义，失败可回退到“只改数值”。

## 5. 分期实现

| 期 | 内容 | 验收 |
| --- | --- | --- |
| P0 | 只读打点：`K#b` / `u1` / `mc.y#m` / `A#N`，输出 start/end、offset、fromY/toY、scale 序列、时长 | 真机 1626 上连续换行/seek/拖动/切歌各一轮，确认“零位移 + 延后”假设 |
| P1 | 可配置节奏：暴露 `$k.e` 比例与两条 bezier；行 blur 开关（若 P0 证明行 View 可安全加 `RenderEffect`） | 手感可调、无宿主记账异常（`V0` 队列长度与原生一致） |
| P2 | 标量弹簧位移（方案乙）：自建 `Choreographer` 弹簧驱动 `translationY`，停帧闸门按 §2.1 | 跳 1 行与跳 20 行时长一致（≈0.43 s），seek 瞬移 |
| P3 | 视觉层：行 0.97/1.0、blur 档位、写入阈值；逐字符强调作为可选高阶项 | 帧时间不劣化（`gfxinfo` 对比 P0） |

## 6. 未决问题

1. 产品形态选甲还是乙（决定 P2 是否存在）。
2. 行级 blur 在 1626 上是否与宿主的 `A#N` 叠加冲突（需 P0 打点看 `A#N` 是否每行都跑）。
3. 逐字符强调是否值得移植（B 估计占 90% 工程量、30% 观感）。
4. 平板双栏是否同时存在两个歌词 RecyclerView（影响 session 绑定）。
5. AMLL 的 stagger（50 ms/行）是否复刻（作者标为“疑似历史遗留”）。

## 7. 参考

- Apple 1626：`01-lyrics-line-transition-1626.md`
- 接入点：`01-line-change-motion-feasibility.md`
- AMLL 运动：`02-amll-posy-spring-spec.md`
- AMLL 视觉：`02-amll-per-line-visual-model.md`