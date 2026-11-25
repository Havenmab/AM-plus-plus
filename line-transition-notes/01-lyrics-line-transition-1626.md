# Apple Music 歌词「换行动画」实现拆解（7.0.3 / versionCode 1626）

> 研究对象：Apple Music Android 7.0.3（1626）歌词页在**当前行唱完、高亮跳到下一行**时，歌词列表如何位移、新旧行如何过渡。
> 主证据：/tmp/am1626/base.apk（classes.dex / classes2.dex / classes3.dex）逐条指令解码。
> 工具：/tmp/am1626/amd2.py（本轮自写：正确的 Dalvik 指令长度表 + 引用/常量解析。旧的 dexcode.py 把 0xb0–0xcf（binop/2addr）误判成 2 code unit，会整段错位，别再用它做证据）。
>
> 结论分级：**【确证】**= 有指令级证据；**【推断】**= 由调用图/字段用法推断，未逐条验证；**【不确定】**= 明确没查到。

> **2026-11-25 更正（以 `07-probe-readme.md` 的 DEX 复核为准）**：文中把 `K#b` 的哨兵读作“15”是错的——`const/4 v2, #0xf` 是 4 bit 符号扩展，实际值 **-1**（`NO_POSITION`，与 `mc.y.u` 初值、`findFirstVisibleItemPosition` 空返回同一个值）。
> 分支方向也与 §2.3 伪码相反：**`start != -1` 时才调用 `scrollToPositionWithOffset`**，`start == -1` 时跳过。
> 文中的 `== 15` / `!= 15` 判断请一律按 `== -1` / `!= -1` 阅读；探针日志打的是真值（`sentinel=1` 表示 `start == -1`）。

---

## 0. TL;DR

| 问题 | 结论 | 关键证据 |
| --- | --- | --- |
| 谁发起换行位移 | **不是** scrollToPosition / smoothScrollBy / SmoothScroller，而是 LinearLayoutManager.scrollToPositionWithOffset(pos, offset)；调用点是 **PlayerLyricsViewFragment$K#b(II)**（适配器 → Fragment 的回调），offset = 顶部装饰器算出的 $x.a | K#b 偏移 111/113：iget q0:PlayerLyricsViewFragment$w → invoke-virtual {v0, v8, v9}, LinearLayoutManager#u1(II)V；u1(II) 即 scrollToPositionWithOffset（全 APK 唯一 (II)V 且会 requestLayout 的 LM 方法） |
| 这个位移是动画吗 | **不是**。scrollToPositionWithOffset 是瞬时重锚（pendingScrollPosition + requestLayout）。视觉上的「滑」来自 **ItemAnimator 的逐行动画**，与滚动解耦 | 见 §3、§4 |
| 位移动画 / 行过渡由谁做 | 自定义 ItemAnimator **PlayerLyricsViewFragment$u**（extends mc.y，mc.y = DefaultItemAnimator 的混淆改名版）：canReuseUpdatedViewHolder 恒 true → change 被降级成 **零位移 move**，而 mc.y#m(animateMove) 被改写：零位移时不做位移动画，改为立即 dispatchAnimationFinished(holder) 并把「该行的后续动画」塞进 fragment.V0 队列，稍后由 s2() 冲刷 | mc.y#m 偏移 23–55；$u#b（恒 return true）；PlayerLyricsViewFragment#s2 偏移 8–28 |
| 时长 | 换行回调里**动态改写 ItemAnimator 的时长字段**：$k.e = round(lerp(480, 800, (clamp(gap,200,750) − 200) / 550)) → **480 ms ~ 792 ms**（gap = 下一行 begin − 当前行 end） | PlayerLyricsViewFragment$f#invoke 偏移 115–179（常量 200.0f / 750.0f / 550.0f / 480.0f / 800.0f） |
| 缓动曲线 | PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)（= CSS ease），静态字段 A.i0；另有 PathInterpolator(0.25f, 0f, 1f, 0.2f) 用于退场段 | A.<clinit> 0–12；A#N 346–355 / 392–400 / 419–423 |
| 缩放 / 透明度 | 行内 q8.m9.a0（lyricsInstrumentalRoot）：SCALE_X/Y 当前值 → **1.2**，750 ms；随后 SCALE→**0.5** + ALPHA→**0**，250 ms；AnimatorSet: play(缩小淡出).with(alpha).after(放大).after(A.M) | A#N 303–462；监听器 A$s#onAnimationEnd 复位 scale/alpha=1 |
| 位移距离是否和行高相关 | **不是**。位移量 = 「让第 pos 行停在 $x.a 这个 y 偏移上」，由 LayoutManager 按行高自然计算，没有 const 行高倍数 | K#b 79–113；$v#f(getItemOffsets) 只用 $x.a/.b/.c |
| 每行是否都有独立动画 | 有，但**只在行内容变化（notifyItemChanged）时**：$u#p/q/r/s 把「动画结束」回调转发给适配器 i1#G/I/M/N，其中 A#N（change 结束）做放大→缩小淡出 | $u#p/q/r/s；i1#G/I/M/N（抽象）；A#G/I/M/N |
| 逐词高亮如何衔接 | 同一入口 i1#P(List<Integer> inLineIds, int playbackPos, Pair[] payloads)；payload key = PLAYBACK_POSITION / WORD_HIGHLIGHT_IDS / BG_WORD_HIGHLIGHT_IDS；CJK 走 A#W（ValueAnimator，用 A.i0），拉丁走渐变蒙版（FullWidthAlphaGradientFlexboxLayout + A#m0 的 4 段渐变） | $g(wordCallback)→f2；$l$a#run；A#W；A#m0 |
| 有没有 anim/*.xml / interpolator/*.xml | **没有参与**。全 APK 里 AnimatorInflater / AnimationUtils.loadAnimation 的调用点没有一处在歌词链路；所有动画都是代码构造 | 全量反查（21 处 inflater 调用全部属于别的模块） |

---

## 1. 组件替换图（1626）

PlayerLyricsViewFragment#onCreateView 里一次性装配（指令偏移 234–276）：

| 角色 | 字段 | 类型 | 装配指令 |
| --- | --- | --- | --- |
| RecyclerView | l0.d0（q8.X4.d0） | androidx.recyclerview.widget.RecyclerView | — |
| Adapter | n0 | com.apple.android.music.player.i1（抽象基类） | 243 setAdapter |
| LayoutManager | q0 | **PlayerLyricsViewFragment$w extends LinearLayoutManager** | 252 setLayoutManager |
| ItemAnimator | r0 | **PlayerLyricsViewFragment$u extends mc.y** | 261 setItemAnimator |
| ItemDecoration | s0 | PlayerLyricsViewFragment$v extends RecyclerView$m | 270 addItemDecoration（RecyclerView#g） |
| OnScrollListener | （无字段） | com.apple.android.music.player.fragment.L extends RecyclerView$s | 627 addOnScrollListener（RecyclerView#j） |
| OnItemTouchListener | （无字段） | com.apple.android.music.player.fragment.M extends RecyclerView$r | 641 addOnItemTouchListener（RecyclerView#i） |

具体适配器是 i1 的两个子类，二选一写进 n0（B2 偏移 72/90）：
* **A**（com.apple.android.music.player.A）—— 逐词（getAvailableTiming() == Word）路径，内含卡拉 OK 逐词动画；
* **V0**（com.apple.android.music.player.V0）—— 行级（line timing）路径。

profile 里的 LYRICS_RECYCLER_ADAPTER 指向的 A/V0 是**适配器**，不是 binding；行布局 binding 是 q8.o9（karaoke 行）/ q8.m9（instrumental 行）。

### 1.1 $w 只重写了一个方法：supportsPredictiveItemAnimations

```
PlayerLyricsViewFragment$w#s()Z          // 覆盖 RecyclerView.LayoutManager#s()
    v0 = this.h0
    if (h0 == false) return true         // 默认：开预测动画
    return super.s()                     // super = LayoutManager#s() → 恒 false
```

（LayoutManager#s() 实体只有 const/4 v0,#0 ; return v0 → 就是 supportsPredictiveItemAnimations）

h0 的开合：
* A#M（move 动画结束）偏移 28–29：iput-boolean true → q0.h0（关预测动画）
* A#N（change 动画结束）偏移 34：iput-boolean false → q0.h0（开回来）
* B2 偏移 276–278：装歌时 scrollToPositionWithOffset(0,0)

> 意义：歌词列表平时**开着** RecyclerView 的预测动画（瞬时重锚时进出视口的行会有 add/remove 动画），
> 但在「行位移动画」进行中把它关掉，避免两套动画打架。

---

## 2. 换行链路（谁在什么时候动）

### 2.1 事件源：native 时间处理器

```
com.apple.android.music.ttml.SongInfoTimeProcessor#processEvents(SongInfo$SongInfoPtr, long, oi.q x5) : long
    ↑ 由 com.apple.android.music.ttml.f#c(...) 包一层，5 个回调是
      PlayerLyricsViewFragment$f  = lineCallback
      PlayerLyricsViewFragment$g  = wordCallback
      PlayerLyricsViewFragment$n  = bgWordCallback
      PlayerLyricsViewFragment$p  = prWordCallback
      PlayerLyricsViewFragment$r  = prBgWordCallback
    （ttml.f#c 偏移 66：invoke-virtual/range {v9..v10}, SongInfoTimeProcessor#processEvents(...)）
```

SongInfoTimeProcessor$processEvents$lineEventCallback$1#call(J, LyricsLineVector, J) 只是把
(beginMs, lineVector, endMs) 原样转给 oi.q#invoke，不含逻辑。

### 2.2 lineCallback = PlayerLyricsViewFragment$f#invoke（核心）

1. h1 = !lineVector.isEmpty()（偏移 10–14；h1 后面用于「这一行有没有词」）。
2. 遍历 lineVector，收集 LyricsLineNative#getLineId() 到 ArrayList<Integer>，同时拼 log 字符串（16–71）。
3. **算行间空隙 gap**（72–126）：
   * next = ttml.j#a(curLineId + 1)
   * gap = (next != null) ? next.getBegin() − cur.getEnd() : 15
   * gap = min(750, max(200, gap))
4. **把 gap 映射成动画时长**（127–151）：
   * t = (gap − 200.0f) / 550.0f（常量 0x43480000 = 200.0f、0x44098000 = 550.0f）
   * d = Math.round(lerp(480.0f, 800.0f, t))（la.f0#D(FFF)F；端点常量 0x43f00000 = 480.0f、0x443b8000 = 750.0f… 见下方注意）
   * 范围断言：不在 [200.0, 750.0] 或 > 800.0 就 throw IllegalArgumentException("number: … is not within range [200.0, 750.0]")（182–210）
5. **写回 native 行偏移 + 写 ItemAnimator 时长**（156–181）：

```
iget-object v3, frag, Z0:ttml.f
invoke-virtual {v3}, ttml.f#a()                                // → SongInfoTimeProcessor
invoke-virtual {v3}, SongInfoTimeProcessorNative#getSuggestedLineOffset()I
invoke-static {v3}, Math#abs(I)I
if-eq v11, v3, +41 → 211                                       // 和当前一致就跳过
neg-int v3, v11
invoke-static {frag, v3}, PlayerLyricsViewFragment#d2(Fragment,int)   // suggestLineOffset(-d)
iget-object v3, frag, r0:PlayerLyricsViewFragment$u                    // ← ItemAnimator
int-to-long v4, v11
iput-wide v4, v3, androidx.recyclerview.widget.RecyclerView$k.e : J    // ← 时长字段
goto → 211
```

d2(frag,int) 实体：SongInfoTimeProcessorNative#suggestLineOffset(v) → n2() → y2(playbackState)。

> **注意**：$k.e 是 RecyclerView.ItemAnimator 的四个 long 时长字段之一（c/d/e/f），
> mc.y#j（runPendingAnimations）在 **moves 分支**读它（if (v18=movesPending) v7 = $k.e），
> 所以它是 **moveDuration**，默认 250 ms；这里被按行距改成 480~792 ms。

6. **等动画空闲再继续**（211–239），注意 RecyclerView$k#h 在空闲时是**同步**回调：

```
iget-object v11, frag, r0:$u
new-instance v1, Lcom/apple/android/music/player/fragment/I;   // ItemAnimatorFinishedListener
invoke-direct {v1, this$f, lineIds(ArrayList), begin(Long), end(Long)}
invoke-virtual {v11, v1}, RecyclerView$k#h(RecyclerView$k$a)Z   // isRunning(listener)
```

即：**换行不立刻改 UI**，而是登记「等 ItemAnimator 跑完再执行」的监听器 fragment.I。
I#a() 在 m.b.d != 1（非播放态？）时 post I$a 到 m.V:Handler。

### 2.3 适配器 → Fragment 的回调 K#b(II)（真正的「滚动」）

```
A#P 偏移 689 / 734 / 819:  invoke-virtual {v2, v5, v4}, K#b(II)V     // (firstLine, lastLine) 或 (cur,cur)
A#P 偏移 819:              invoke-virtual {v0, v10, v10}, K#b(II)V    // v10 = 15（「无当前行」哨兵）
```

K#b(int start, int end)（精简伪码，均为原始指令）：

```
G0_old = frag.G0 ; frag.G0 = start ; frag.H0 = end
idle = (recyclerView.getScrollState() == 0)
if (!idle) recyclerView.setVerticalScrollBarEnabled(false)
inRange = e2(frag, start)                        // 该行是否已在 [findFirstVisible, findLastVisible]
if (frag.T0 && idle && frag.U0 && !adapter.D() && !inRange) return   // 用户正在滚/动画未完 → 不抢
if (start == 15 || end == 15) frag.T0 = false
adapter.Q(true)                                  // 标记「程序滚动中」
frag.A2(false)                                   // adapter.R(false) + 容器 view 可点击
frag.r0.u = end                                  // mc.y.u（DefaultItemAnimator 的一个 int 字段）
if (start == 15) {
    off = frag.E0.a.a                            // ← $x.a：顶部占位（见 §6）
    if (frag.M0) frag.M0 = true
    else if (start == G0_old && frag.L0.size() != 0) off += frag.L0.get(start-1)
    invoke-virtual {q0, start, off}, LinearLayoutManager#u1(II)V   // ★ scrollToPositionWithOffset
}
if (frag.T0 && !wasT0 && !inRange) frag.T0 = true
viewModel.setCurrentHighlightedLine(start)
```

**这就是「换行位移」的全部**：把新当前行重新锚定到 off（= 顶部渐变区高度），瞬时完成。
LinearLayoutManager#u1(II) 的三个调用者里只有它逐行触发，其余是 B2（装歌 (0,0)）、
S#invoke（deeplink 跳行）、c0$a#run（回第 0 行）。

u1(II) 的**实体**（确证它就是 scrollToPositionWithOffset，而不是别的 (II)V 方法）：

```
LinearLayoutManager#u1(II)V
    0  iput v1, this, LinearLayoutManager.Z : I          // mPendingScrollPosition = position
    2  iput v2, this, LinearLayoutManager.a0 : I         // mPendingScrollPositionOffset = offset
    4  v1 = this.c0 : LinearLayoutManager$d
    8  const/4 v2, #15
    9  iput v2, v1, LinearLayoutManager$d.a : I          // NO_POSITION，作废锚点缓存
   11  invoke-virtual {this}, RecyclerView$n#F0()V        // requestLayout()
```

> 附带确证：RecyclerView$k#h(listener)（isRunning）在**空闲**时会**同步**回调 listener.a() 并返回 false，
> 只有真在跑动画时才入队 —— 所以 $f#invoke 里的 $I#a 大多数情况下是当场执行的。

e2(frag,pos)：lm#e1() = findFirstVisibleItemPosition、lm#f1() = findLastVisibleItemPosition，
pos 落在 [first, last] 内返回 true。

L#a(RecyclerView,int)（onScrollStateChanged）：
* state != 1（非 DRAGGING）→ setVerticalScrollBarEnabled(true)、adapter.Q(false)、T0 = false
* state == 0（IDLE）→ 若 !f1.l(...) 且 e2(G0) 且 e2(H0) 且 i2() → A2(true)（「回到当前行」提示/吸附）

### 2.4 时序总结

```
native line event (begin, end, lines)
  └→ $f#invoke
       ├─ 计算 gap → 写 $k.e(moveDuration) 480..792ms，suggestLineOffset(-d)
       └─ $u.isRunning($I) ──(动画跑完)──→ $I#a → post I$a
                                    └→ 适配器 P(...) → notifyItemChanged(old/new line)
                                         ├─ $u 收到 change → 因 canReuse=true 降级为 0 位移 move
                                         │    └→ mc.y#m: 0 位移 → 立即 dispatchAnimationFinished
                                         │         + 把 "run $u.s(holder)" 排队到 frag.V0（或直接跑）
                                         └─ K#b(cur,cur) → scrollToPositionWithOffset(cur, $x.a)

frag.s2()（由 M#c 触摸拦截 / S.M0 触发）→ U0=false，排空 V0：逐行跑 $u.s(holder) → i1#N(holder) → A#N(放大/缩小动画)
```

---

## 3. ItemAnimator：$u + mc.y

```
com.apple.android.music.player.fragment.PlayerLyricsViewFragment$u
  f z : PlayerLyricsViewFragment
  <init>(fragment) { this.z = fragment; mc.y.<init>() }
  b(ViewHolder, List)Z   → const/4 v0,#1 ; return   // canReuseUpdatedViewHolder 恒 true
  i(ViewHolder)          → if (holder is i1$b) holder.y.clear()      // LinkedHashSet 清空
  p/q/r/s(ViewHolder)    → i1#G / #I / #M / #N                        // 动画结束分发
```

mc.y 是 androidx.recyclerview.widget.F（= SimpleItemAnimator）的子类，字段形态与 DefaultItemAnimator 一致
（h..r 八个 ArrayList、s: SparseIntArray、t: SparseArray、u:int=15、v: TimeInterpolator、w/x: PathInterpolator），
方法 e/f/g/j/k/l/m/n/t/u/v/w/x 都是标准 DefaultItemAnimator 逻辑，**只有 m(animateMove) 被改写**：

```
mc.y#m(holder, fromX, fromY, toX, toY)Z
    dx = (toX - fromX) + (int)view.getTranslationX()
    dy = (toY - fromY) + (int)view.getTranslationY()
    endAnimation(holder)                       // mc.y#x
    if (dx == 0 && dy == 0) {                  // ★ 只处理「零位移 move」
        runnable = new J4.t2(this, 2, holder)  // 泛型 lambda：稍后调 $u#s(holder) → i1#N
        if (frag.U0) runnable.run()            // 立即
        else frag.V0.offer(runnable)           // 入队
        dispatchAnimationFinished(holder)      // RecyclerView$k#c(holder)
        return false
    }
    if (dx != 0) view.setTranslationX(-dx)
    if (dy != 0) view.setTranslationY(-dy)
    y.j.add(new mc.y$e(...))                   // 正常位移动画（这里几乎不会走到）
    return true

PlayerLyricsViewFragment#s2()                  // 队列冲刷
    handler.removeMessages(2131363703)
    U0 = false
    while (!V0.isEmpty()) ((Runnable)V0.poll()).run()
```

**要点**：换行时位移由 LayoutManager 瞬时完成，**行级动画被有意推迟到列表稳定之后**，
避免「边滚边放」的错位感。

---

## 4. 行过渡动画的实体：A#N / A#G（适配器侧）

### 4.1 A#N(holder) —— change 结束时的放大 → 缩小淡出

条件：holder is A$a 且 binding is q8.m9（instrumental 行）且 ttml.j 非空。

```
 29  RecyclerView#y0()                 // = stopScroll()（setScrollState(0) + OverScroller.abortAnimation + SmoothScroller.stop）
 34  iput-boolean false → q0.h0        // 恢复预测动画
 // 以行根 q8.m9.a0 的几何中心为 pivot：
268 view.setPivotX(...)
299 view.setPivotY(top + height/2)
 // ① 放大
303-342 ObjectAnimator.ofPropertyValuesHolder(view,
          PropertyValuesHolder.ofFloat(SCALE_X, cur, 1.2f),     // 0x3f99999a = 1.2f
          PropertyValuesHolder.ofFloat(SCALE_Y, cur, 1.2f))
346-355 setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f))
361-363 setDuration(750)
 // ② 缩小
366-388 PropertyValuesHolder.ofFloat(SCALE_X/Y, …)  目标 0.5f        // 0x3f000000 = 0.5f
392-400 setInterpolator(new PathInterpolator(0.25f, 0f, 1f, 0.2f))
404-406 setDuration(250)
 // ③ 淡出
409-427 ObjectAnimator.ofFloat(view, View.ALPHA, 0f)                // 同插值器、同时长 250
 // 串起来
430 AnimatorSet
435 play(②).with(③).after(①)
447 if (A.M != null) builder.after(A.M)          // 接在卡拉 OK 的 ValueAnimator 之后
459 addListener(new A$s(root = q8.m9.a0, binding, A.this))
462 start()
```

A$s#onAnimationEnd：scaleX=scaleY=alpha=1f，并把 q8.m9.X/Y/Z 三个 ImageView 的 setAlpha(1f) + setImageTintList(ColorStateList.valueOf(A.I)) 复位。

> 也就是说：**instrumental（纯音乐）行**在「被换下」时，先放大到 1.2（750 ms，CSS ease），
> 再缩到 0.5 + 淡化到 0（250 ms，ease-out-ish），最后复位。
> q8.m9.a0 的 id 名是 lyricsInstrumentalRoot；三个 ImageView 是「…」三点指示（A#G 里是 staggered fade-in：
> AccelerateInterpolator、750 ms、每点 +50 ms 起始延迟）。

### 4.2 A#G / A#I / A#M（remove / add / move 结束）

* **A#G**（remove 结束）：把 q8.m9.X/Y/Z 三点 setAlpha(0) → ObjectAnimator.ofFloat(view, ALPHA, [0,1])，AccelerateInterpolator，setDuration(750)，setStartDelay(i*50)，逐点 start()。
* **A#I**（add 结束）：仅 A#Z(holder, F(pos,true))（日志/埋点路径）。
* **A#M**（move 结束）：

```
A#w()                                   // 取消 L/M/N 动画
if (A.y != null) { frag.q0.h0 = true ; if (!i1.B()) d2(frag, -500) }
```

  d2(frag,-500) → suggestLineOffset(-500) + n2() + y2(playbackState)。→ 【不确定】业务含义。

---

## 5. 适配器的状态入口：i1#P(List, int, Pair[])

```
i1.P(inLineIds: List<Integer>, playbackPos: int, payloads: Pair[])
```

A#P 内部维护：
* r: TreeSet = 当前「高亮/在视口内」的行 id 集合
* q: LinkedHashSet、s: TreeSet、t: LinkedHashSet = 各种去重缓存
* u: Integer = 上一次的当前行 id；v: boolean
* E: int = 当前行 begin（−800ms 起算）；F: int = 当前行索引；G: long = 上次切换时间戳
* O/P/Q/R/X/Y/Z/a0: int[]、b0: float、C/D/J: float = 颜色/位置常量

关键分支（偏移）：
* 390–455：if (F != 15) 分支 —— 若距上次切换 < 4000ms 就 notifyItemChanged(F) 直接返回（**4 秒节流**，常量 const-wide/16 #4000）
* 394–427：$f#j(pos)（notifyItemInserted）、A.N = null、A.G = now、RecyclerView$g#e(F, 1)（= onItemRangeRemoved，直接调 observable，绕开 Adapter 封装）、K#b(F,F)
* 456–466：另一分支 notifyItemChanged(pos) + notifyItemInserted(pos)
* 468–493：PAUSE_REQUESTED payload 分支
* 744–822：对 r 里每个行 id notifyItemChanged(F(pos,false))，最后 K#b(first,last) / K#b(15,15)

> **【不确定】** RecyclerView$g#e(pos,1)（onItemRangeRemoved）与 $f#j(pos)（notifyItemInserted）
> 在同一位置成对出现，看起来是「借 remove/insert 通知触发 ItemAnimator 的 remove/add 动画」的 hack，
> 没有真正改动 getItemCount()。没能 100% 证明。

---

## 6. 视觉细节：当前行停在哪、上下渐隐怎么来

PlayerLyricsViewFragment#W1()Z（update metrics，lyrics-fragment-update-metrics 契约）会算：
* 顶部/底部渐变蒙版的颜色与 stops，写进 AlphaGradientFrameLayout：
  0x3d8f5c29 = 0.07、0x3e8e147b = 0.2775、0x3da3d70a = 0.08、0x3e75c28f = 0.24（都是「占高度比例」）
* **两个 $x 实例**（C0 / D0）的 a/b/c 三个 padding：

```
x.a = round(height*0.06f) − dimen(2131165738)
x.b = dimen(2131165722) − dimen(2131165738) − dimen(2131165736)
x.c = S1().getHeight() + round(0.28f*height)
// 另一份： x.a = dimen(2131165735) ; x.b = dimen(2131165734) ; x.c 同上
RecyclerView#S()      // = invalidateItemDecorations()
```

* $v#f(Rect, View, RecyclerView, ViewHolder)（getItemOffsets）：
  * position == 0 → rect.top = x.a
  * position == count-1 → rect.bottom = x.c，否则 x.b

> 所以「当前行停在屏幕什么高度」由 x.a 决定，K#b 把该值直接当作
> scrollToPositionWithOffset 的 offset —— 这是改「当前行停靠位置」的唯一旋钮。

---

## 7. 逐词高亮的衔接

* wordCallback $g#invoke(beginMs, LyricsWordVector, endMs)：
      if (adapter != null && adapter.C()) f2(frag, wordVector, false)
* PlayerLyricsViewFragment#f2(frag, LyricsWordVector, boolean)：
      for each word in vector:
          viewModel.notifyWordHighlight(lineId, wordId, wordDuration, isBg)
          收集 wordId → wordIds, lineId → lineIds
      末尾只把两个集合拼成 log 字符串
* PlayerLyricsViewModel#notifyWordHighlight(int,int,int,boolean) → mWordHighlightProps: MutableLiveData
  → 行 binding 重绑 → FullWidthAlphaGradientFlexboxLayout 用 A#m0(f1,f2,Z) 生成的 **4 段 float[]**
  （[0, p0, p1, 1]，p0/p1 clamp 到 [0,1]）设置渐变位置，实现「逐词渐亮」。
* CJK 分支：A#W(A$a, int, int, int, boolean)（cjk-karaoke-animation-method 契约）用
  ValueAnimator.ofFloat + setStartDelay / setDuration / setInterpolator（A.i0 或 new PathInterpolator(0.25,0,1,0.2)）
  + addUpdateListener 更新逐字色块。
* 行级与词级**共用**同一个 i1#P(...) 入口和同一个 $u.isRunning() / s2() 节流+排队机制：
  $l$a#run（animator 空闲后）把 PLAYBACK_POSITION + WORD_HIGHLIGHT_IDS + BG_WORD_HIGHLIGHT_IDS
  三个 payload 一起递给 P。

---

## 8. 与 1614 的对照 / 命名漂移

| 1614 记录 | 1626 实际 | 说明 |
| --- | --- | --- |
| PlayerLyricsViewFragment#F2(SongInfoPtr) | **仍是 F2**，签名一致 | profile LYRICS_NATIVE_PRESENTATION 继续有效 |
| 换行入口记作 t2 / u2 | t2(SongInfoPtr)Z =「有读音 && 读音开关开」；u2(SongInfoPtr)Z =「有翻译 && 翻译开关开」 | **和滚动无关**。1614 那份 seam 若把 t2/u2 当滚动入口，需要复核/纠正 |
| — | 新的换行入口 = K#b(II) + $u / mc.y#m + A#N | 本轮新增 seam |

建议新增 profile seam（供实现阶段用）：

```
LYRICS_LINE_SCROLL_CALLBACK  = com.apple.android.music.player.fragment.K#b(II)V
LYRICS_LAYOUT_MANAGER        = com.apple.android.music.player.fragment.PlayerLyricsViewFragment$w
LYRICS_LINE_ITEM_ANIMATOR    = com.apple.android.music.player.fragment.PlayerLyricsViewFragment$u
LYRICS_LINE_ADAPTER          = com.apple.android.music.player.i1  (子类 A / V0)
LYRICS_ITEM_ANIMATOR_BASE    = mc.y   (DefaultItemAnimator 变体；animateMove 被改写)
LYRICS_ROW_TRANSITION_METHOD = com.apple.android.music.player.A#N(RecyclerView$D)V
LYRICS_EASE_INTERPOLATOR     = com.apple.android.music.player.A.i0 : PathInterpolator(0.25, 0.1, 0.25, 1)
```

---

## 9. 不确定 / 待补

1. **文本行（非 instrumental）的换行视觉**：A#N 只处理 binding is q8.m9（instrumental 行）。
   普通歌词行是 q8.o9（LyricsLineKaraokeBinding），在 A#l / A#m / A#X 里**没有**找到
   setScaleX / setAlpha / setTextSize / animate() 之类调用，只有：
   * A#l 偏移 2379 / 2388：对**背景词**视图 setTranslationY(A.J) / setTranslationY(0)；
   * 文字亮度/大小很可能由 databinding 表达式 + PlayerLyricsViewModel$e（属性 a,b,d,e,f,g,h,l,m,n,o,p,q）驱动，
     但布局 XML 是二进制 AXML，没解出表达式 → **文本行是否渐变/缩放，不确定**。
2. RecyclerView$g#e(F,1)（onItemRangeRemoved）+ $f#j(pos)（notifyItemInserted）这对通知的**真实意图**
   （动画 hack？还是真的滑动窗口）→ 不确定。
3. $k.e 判定为 **moveDuration**（依据 mc.y#j 的 moves 分支读取 + $k.d 在 removals 循环读取），
   但没有 100% 排除 changeDuration。
4. A#M 里 d2(frag, -500) 的业务含义 → 不确定。
5. U0 / T0 / M0 三个布尔只有调用点证据，语义是推断：
   U0 ≈「列表正在动 / 动画排队中」、T0 ≈「程序刚抢过滚动」、M0 ≈「已经吸附过一次当前行」。
6. S#invoke（deeplink 跳行）里把 15 当 playbackPos 传给 P 的用法可疑，未深究。
7. 1614 的 DEX 已被清理，上面 1614 对照只基于 profile 记录，**没有在 1614 二进制上复核**。

---

## 10. 给实现阶段的建议

1. **不要**试图 hook RecyclerView.scrollBy / smoothScrollBy —— 歌词链路根本不走它们。
   要接管「换行位移」就 hook PlayerLyricsViewFragment$w#u1(II)（= scrollToPositionWithOffset），
   或更上游的 K#b(II)（能拿到 (start,end) 语义，且只在换行时触发）。
2. 想改「当前行停靠高度」：改 $x.a（W1() 写、$v#f 读、K#b 当 offset 用）。
   注意 W1() 会同时设置两个 $x（C0 / D0），改完要 RecyclerView#S()（invalidateItemDecorations）。
3. 想改「过渡节奏」：最省事的是覆盖 $k.e（moveDuration，480~792ms）或替换
   A.<clinit> 里的 PathInterpolator(0.25, 0.1, 0.25, 1)；要完全替换动画则必须在 onCreateView 之后
   setItemAnimator(自己的 animator)，并自己实现 canReuseUpdatedViewHolder → true 与
   animateMove 的「零位移 → 延后执行」语义，否则会和 $u + s2() 的队列打架。
4. 逐词/逐行共用 i1#P(...)：若要在插件里注入「高亮状态」，最稳的注入点是
   PlayerLyricsViewModel#notifyWordHighlight(int,int,int,boolean) 和
   PlayerLyricsViewModel#setCurrentHighlightedLine(int)（K#b 末尾必调）。
5. 复刻参数（可直接用）：
   * 位移：scrollToPositionWithOffset(line, topInset)，瞬时；
   * 放大：SCALE 1 → 1.2，750 ms，cubic-bezier(0.25, 0.1, 0.25, 1)；
   * 退场：SCALE → 0.5 + ALPHA → 0，250 ms，cubic-bezier(0.25, 0, 1, 0.2)，两者 with 并行；
   * 时长随行距：moveDuration = round(480 + 320 * clamp((gap − 200)/550, 0, 1))，gap = next.begin − cur.end，clamp 到 [200, 750]。
