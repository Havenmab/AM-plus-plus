# 10 · 间奏三圆点：退场动画的真实调用链，以及换行怎么等它

> 只讲 **Apple Music 7.0.3 / versionCode 1626**。DEX 来自 `/tmp/am1626/`（`amd2.py` 指令级解码 +
> 20 万方法的引用索引），布局/资源来自 `base.apk` 的 AXML（`/tmp/am1626/axml.py`）。
>
> **先纠正上一版这一篇的错误结论。** 上一版用"全 APK 没有一处引用 `PlayerLyricsViewFragment$u#s`"来判定
> `V0#N`/`A#N`（放大→缩小→消失）是死代码，并据此写了 120 ms 强制淡出。真机观感推翻了它：
> 间奏时间到后三颗点**一起放大 → 迅速缩小直到消失 → 才衔接换行**，正是 `V0#N` 的形状。
> 错在哪：`$u#s` **是对 `androidx.recyclerview.widget.F#s` 的覆写**，调用方写的是基类 `F#s`
> （虚分派才落到 `$u#s`），所以"搜 `$u#s` 无引用"是必然结果，不是证据。
> **本次的规矩：先按真机观感建假设，再用 DEX 找"能产生这个观感"的调用链；引用索引只能用来证实，不能用来否证。**

---

## 1. 三圆点是哪个视图

`res/layout/lyrics_line_instrumental.xml`（1773 个 layout 里只有它含这些 id）：行根
`ConstraintLayout id/lyrics_instrumental_root`（`wrap_content` + 上下 padding）里三个
`ImageView id/dot1..dot3`（`src=drawable/lyrics_instrumental_dot`、`scaleType=5`、top/bottom 贴父）。
DataBinding `q8.m9`，构造偏移 4/6/8/10 存 `X/Y/Z`(三个 ImageView) 与 `a0`(ConstraintLayout)。
**行里除了三个点没有别的内容。**

## 2. 退场动画：`V0#N(ViewHolder)` / `A#N(ViewHolder)`

| 偏移 | 指令 | 含义 |
| --- | --- | --- |
| 3-13 | `instance-of V0$j` + `instance-of q8/m9` + `V0.m != null` | 只对**间奏行**生效，文本行直接 return |
| 56-124 | `getHeight - margins - paddings`，`setPivotX/Y` 落到行中心（空白行取左边界） | 放大/缩小的支点 |
| 127-187 | `PropertyValuesHolder(SCALE_X/Y: [当前, 1.2])` + `PathInterpolator(.25,.1,.25,1)` + `setDuration(750)` | **放大 750 ms** |
| 190-230 | 两个 holder：`SCALE_X/Y → 0.5`，`PathInterpolator(.25,0,1,.1)`，`setDuration(250)` | **缩小 250 ms** |
| 233-251 | `ObjectAnimator.ofFloat(root, ALPHA, 0f)`，同一个 250 ms | **淡出 250 ms** |
| 254-271 | `AnimatorSet.play(shrink).with(fade).after(grow)` | 750 之后再 250 |
| 271-278 | `if (V0.B != null) builder.after(V0.B)` | B 是"最后一颗点自己的 750 ms"、与 grow 同时起跑，**不拉长总时长** |
| 278-286 | `set.addListener(V0$b)` + `set.start()` | 结束时 `V0$b#onAnimationEnd` 复位 scale=1 / alpha=1 / 点底色 |

`A#N` 是逐词适配器的同款（listener `A$s`）。**总时长 = 750 + 250 = 1000 ms**
（`LyricInterludeExitPolicy.EXIT_MS`）；`V0$b#onAnimationEnd` / `A$s#onAnimationEnd` 就是"圆点没了"的**精确回调**。

## 3. 谁在间奏结束时启动它（真实调用链）

`K#b(start,end)` 只是重锚（`scrollToPositionWithOffset` + `requestLayout`）。退场动画走的是
**change 动画**那条线，不是 move：

    PlayerLyricsViewFragment$f#invoke(...)        // 行事件（甲 在这里改写 $k.e）
      └─ K#b(start,end) → LinearLayoutManager.u1(start, off) → requestLayout
           └─ 下一帧 layout：RecyclerView 对每个可见 holder 调度
                ├─ animateMove   mc.y#m(...)  → 我们的弹簧接管（dy != 0），或宿主零位移分支 → i1#H
                └─ animateChange mc.y#l(old,new,fromX,fromY,toX,toY)
                     → 同 holder 时退化成 m；old != new 时建 mc.y$d，由 mc.y#v/w 起 ViewPropertyAnimator
                     → 监听器 mc.z（androidx 的孪生是 h）
                          ├─ onAnimationStart → androidx.recyclerview.widget.F#s(holder)
                          │     └─ 虚分派 → PlayerLyricsViewFragment$u#s(holder) → i1#N(holder)
                          │           └─ V0#N / A#N  ← 退场动画在这里被重建并 start()
                          └─ onAnimationEnd   → F#o(holder) + RecyclerView 记账

要点：

1. `F#p/q/r/s` 在基类是**空方法**（各一条 `return-void`），是给子类覆写的钩子；`$u` 覆写了
   `p/q/r/s` → `i1#G/I/M/N`。所以"`$u#s` 零引用"完全正常：调用方写的是 `F#s`。
   上一版据此判"死代码"是错的。
2. `F#s` 的调用者是 `mc.z#onAnimationStart`（androidx 孪生 `h#onAnimationStart`），即 **change 动画启动的那一刻**，
   也就是 `runPendingAnimations`（通常排在 layout 之后的一帧）——**比我们的 `animateMove` 钩子晚**。
   这就是"圆点还在放大、行已经开始走"的机制。
3. `F#s` 对 change 的两侧各发一次；只有间奏侧能过 `V0#N` 的 `q8.m9` 门槛。
4. 圆点的"演出"（`V0#U` / `A#Y`：tint 扫过 + 4 s 缩放呼吸）与退场互不影响：退场自己是一套新的 AnimatorSet。

## 4. 我们的修法：挂起待命，而不是抢跑

**不写宿主的任何属性**（上一版的 alpha 淡出已彻底删除）。我们只做一件事：等。

    exit 开始（A#N / V0#N 进入，且 holder 的 binding 是 q8.m9）
      → LyricInterludeHold.start(now)                     // deadline = now + 1000 ms（书值上限）
      → tracks.hold(remaining)                             // 已在飞的整批 holder：原地冻结
      → 之后每一次 mc.y#m 的 push 都带 pendingHoldMs()      // 期间才出现的行同样等
    exit 结束（A$s / V0$b#onAnimationEnd）
      → LyricInterludeHold.finish(now) + tracks.release()  // 立刻放行；比书值早就是早
    没有 end 回调（角色没解析到，或 animator 被 cancel 而不回调）
      → 书值 1000 ms 自然到期                               // 退化路径

冻结的语义（`LyricLineSpringWave` 的 `Member.holdMs`）：

* `push` 照常把 `translationY = -dy` **写下去**（layout 跳变不可见），然后 `holdMs > 0` ⇒
  `advance` 只递减 `holdMs`，**既不动弹簧、也不递减 stagger**，所以"到点后再按 50 ms 阶梯起跑"是字面成立的；
* 已经在飞的行（exit 信号晚到一两帧）**冻结在当前位置**，不归零、不重瞄；
* 冻结期间 `advance` 仍返回 true ⇒ `mc.y#g()` 仍报 running ⇒ RecyclerView 不会认为列表 idle 而丢掉 pending move；
* exit 期间又来一次换行：新的 `push` 是"目标修正"（视觉不动、只挪终点），`holdMs` 取 max，**不会提前起跑**；
  若宿主重新 `start()` 了一套退场，`start` 的 max 语义把 deadline 推后一整段，不会缩短。

降级：`lyricLineMotion.interlude` 缺失 / 任一角色解析失败 / `q8.m9` 加载不到 ⇒ 钩子不装，
**弹簧照旧**，圆点由宿主自己演、行照旧立刻起跑（= 修改前的行为）。只有 end 回调缺失时退化到书值，并打一条
`AMPP-Interlude interlude exit degraded: no exit end callback; parking for the book duration 1000 ms`。

## 5. profile 小节 `lyricLineMotion.interlude`（只读，独立降级）

| 角色 | 1626 成员 | 用途 |
| --- | --- | --- |
| `instrumentalBinding` | `q8.m9` | 判断 holder 是不是间奏行 |
| `holderBinding` | `i1$b.u` | holder → binding |
| `wordExit` / `lineExit` | `A#N(VH)` / `V0#N(VH)` | 退场开始（必须钉在**子类实现**上；基类 `i1#N` 是空方法） |
| `wordExitEnd` / `lineExitEnd` | `A$s#onAnimationEnd` / `V0$b#onAnimationEnd` | 退场结束（拿不到就用书值） |

`verify-host-profile.py` 的 `verify_lyric_interlude_contract` 做 exact 校验（6 条）+ 语义钉子：
binding 必须继承 `ViewDataBinding`、`holderBinding` 类型必须是 `ViewDataBinding`、end 回调参数必须是
`android.animation.Animator`、两个 exit 构造器必须收同一个 ViewHolder 类型、**不许钉在空基类 `i1#N` 上**；
`verify-profile-data.py` 要求这 6 个角色全在。

## 6. 单测（纯 JVM）

`LyricInterludeExitPolicyTest`（8 条）：750+250=1000、exit 比波浪（450 ms t99）长这条关系、
`remainingMs` 不为负、阈值、hold 从开始到书值到期、**第二次 start 只会把 deadline 推后不会缩短**、
end 回调立即放行、`clear`、自定义时长。

`LyricLineSpringHoldTest`（8 条）：被 hold 的批**只写起点、60 帧一像素不动、仍报 running**、
**stagger 在 hold 期间不递减**（到点后从视觉顺序起跑）、已经在飞的行冻结不跳变、
end 回调提前放行、**期间新 push 的行同样等**、**exit 期间再次换行 = 目标修正且不提前起跑**、`hold(0)` 等效无操作。

配套的 `LyricLineSpringWaveTest` / `LyricLineSpringPolicyTest` 共 34 条一并回归：**50/50 通过**。

## 7. 真机验收

`adb logcat -c && adb logcat -s AMPP-Interlude AMPP-LineSpring`

1. **判据 A（本议题）**：间奏时间到后，先看到三颗点**放大 → 缩小 → 消失**的完整过程，**行才开始移动**；两者不再同屏。
2. **判据 B**：`AMPP-Interlude` 出现成对 `exit start n=…` / `exit end n=…`；若只有 start 没有 end，说明 end 回调没解析到
   （退化书值，观感应基本相同，但请把日志发我）。
3. **判据 C**：`AMPP-LineSpring` 的 `takeover … held=NNNms` —— 带 `held=` 的那一批就是被挂起的批；`held` 应小于 1000。
4. **判据 D**：无间奏曲目，`AMPP-Interlude` 一行都没有，`AMPP-LineSpring` 与改前完全一致。
5. **判据 E**：连续 3 段以上间奏，每次都先圆点、后行车；关掉「换行弹簧」重开 APP ⇒ 回到宿主原生（圆点与行会重叠）。

## 8. 这次仍然没做的

* 不接管宿主的 `AnimatorSet`：不 cancel、不 setDuration、不改 scale/alpha/tint；
* 不改 `$k.e` 与退场的关系（退场时长是固定常量，与 `$k.e` 无关）；
* 不解决宿主自身的两个小坑（记在这里）：`V0.C` 演出时钟永不复位（第二段间奏没有 tint 扫过）、
  `AnimatorSet#cancel` 不复位 scale（复用行可能从 1.1x 开始呼吸）。
