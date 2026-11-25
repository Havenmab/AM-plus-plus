# 09 · 背景人声行：宿主到底怎么“展开”，以及行槽里有没有地方放

> 只讲 1626（Apple Music 7.0.3 / versionCode 1626）。DEX 来源 `/tmp/am1626/`（`amd2.py`/`amd3.py` 解码），
> 布局与尺寸来自 `base.apk` 的 `res/layout/lyrics_*.xml`（AXML）与 `resources.arsc`（dimen）。
> 结论先行：**bg 行的出现是 `layoutParams.height` 的 ValueAnimator，不是 `GONE` 切换**；item 是
> `wrap_content`，自己**没有**可用的空位（12 dp 空位 vs 约 48 dp 需求），所以“后行完全不动”只能靠占位。

---

## 1. 行结构与“bg 行”是哪一个 view

`q8.o9` = `res/layout/lyrics_line_karaoke.xml`（`DataBinderMapperImpl#f` 里 `layout/lyrics_line_karaoke_0` →
`new q8.o9(...)`）。根是 ConstraintLayout，两个 `FullWidthAlphaGradientFlexboxLayout`：

| binding 字段 | id | XML tag | 角色 | 实测约束 |
| --- | --- | --- | --- | --- |
| `q8.o9.Y` | `0x7f0a06b1` | `binding_1` | **karaokeLineFlexbox**（主行） | `top_toTopOf=parent`、`bottom_toTopOf=0x7f0a0153`、`paddingTop=@dimen/lyrics_text_padding_top` |
| `q8.o9.X` | `0x7f0a0153` | — | **bgLineFlexbox**（背景人声行） | `top_toBottomOf=0x7f0a06b1`、`bottom_toBottomOf=parent`、`paddingTop=@dimen/lyrics_bg_lyrics_margin_top` |
| `q8.o9.Z` | `0x7f0a06b2` | — | 行根 ConstraintLayout | `layout_height=wrap_content`、`paddingBottom=@dimen/lyrics_text_padding_bottom`、`clipChildren=false` |

X/Y 的对应关系不是猜的：`A#l` 里对 `getBackgroundWords(...)` 的结果调用
`A#b0(words, str, view, 2131559456)` 时传的是 `q8.o9.X`，而参数名在 DEX 里就是字符串常量
`"bgLineFlexbox"`（`Intrinsics.checkNotNullParameter` 的第二个参数），主行那一路传 `Y` 且参数名是
`"karaokeLineFlexbox"`。`player.r` / `player.t` 的 update 监听器读的也是 `q8.o9.X` + `"bgLineFlexbox"`。

## 2. “bg 行出现”的真实实现（不是 GONE 切换）

`A#l`（`onBindViewHolder`）里只有一处管可见性：

```
A#l: 1442  if-eqz  v19 (bg 文本是空串) --> 1464
     1444  v9.setVisibility(8)      // GONE：仅当这一行**没有** bg 词
     1449  v9.removeAllViews()
     1452  v9.v()                    // 复位渐变掩码
     1509  v9.setVisibility(0)       // 有 bg 词：一律 VISIBLE（不区分是否当前行）
     1540  A#c0(bgWords, ..., v9, 2131559454)   // 填 bg 词视图
```

所以“轮到这一行才出现”**不是**可见性开关，而是**行高**：`A#j0(A$a,int)` 在 `A#l` 末尾（offset 3084，
条件 `A.A && A.q0()`）被调，创建：

```
1945  ValueAnimator.ofFloat(0f, 1f)
1957  setDuration( fragment.r0.$k.e )        // = RecyclerView$k.e，甲改写的那个字段
1965  setInterpolator( utils.G.i )           // PathInterpolator
1970  addUpdateListener( player.r(holder, binding) )   // height = round(f * A$a.M)
1978  addListener( player.E(binding) )                 // 结束时 height = 14
1992  bgFlexbox.setAlpha(0f) + SCALE_X/SCALE_Y 0.9->1 + ALPHA 0->1（500 ms，延迟 0.3*duration）
```

`player.r#onAnimationUpdate`：`q8.o9.X.layoutParams.height = round(f * A$a.M)`；
`player.t#onAnimationUpdate`：`round((1-f) * A$a.M)`（收起，`startDelay = 0.3*250`）；
`A$a.M` 是 `A#l` 里对 bg flexbox 手工 `measure()` 出来的**自然高度**（`getMeasuredHeight()`）。

**三条对实现有决定作用的事实**：

1. 高度是**逐帧** `setLayoutParams` 写出来的（60 fps），不是一次性的 `GONE` 切换；
2. 这些 layout 是**纯 layout 变化**：没有 adapter 通知，RecyclerView 不会把它们交给 ItemAnimator，
   因此**没有 `animateMove`、没有 `dy`**，乙的弹簧根本收不到这次位移；
3. `A#l` 还有一处 `height = 0`（offset 2856，条件：bg 文本非空 + 无缓存 + 某个稀疏数组没有该行）与
   `E#onAnimationEnd` 的 `height = 14`，即宿主的“收起”状态是 0 或 14 px，而不是 wrap-content。

## 3. 行槽里到底有多少空位（方案 E 的量化前提）

`res/layout/lyrics_line_karaoke.xml` + `resources.arsc`（默认配置）实测：

| 量 | 资源名 | 值 |
| --- | --- | --- |
| 主行字号 | `lyrics_line_text_size` | **34 sp** |
| bg 行字号 | `lyrics_line_secondary_text_size` | **22 sp**（0.65 × 主行） |
| 行根下内边距 | `lyrics_text_padding_bottom` | **10 dp** |
| 主行上内边距 | `lyrics_text_padding_top` | **2 dp** |
| bg 行上内边距 | `lyrics_bg_lyrics_margin_top` | **22 dp** |
| 行根横向 margin | `lyrics_line_margin_horiz` | 20 dp |
| 横向内边距 | `lyrics_text_padding_horiz` | 12 dp |

bg 行收起时 item 里**已经空着**的竖向空间 = 行根 `paddingBottom` 10 dp + 主行 `paddingTop` 2 dp ≈ **12 dp**
（APK 里没有任何 `getItemOffsets`/ItemDecoration 参与行间距，`$x.a` 只是重锚用的顶部偏移）。

bg 行展开时多出来的高度：

```
ΔH ≈ 22 dp（bg 自己的 paddingTop）+ 22 sp 行高（≈ 1.16 em ≈ 26 dp） ≈ 48 dp
```

所以 **ΔH 48 dp vs 空位 12 dp**：即使把 bg 行压进这 12 dp，仍要多出 **36 dp**；
按 AMLL 的 0.75 缩放（22sp→16.5sp，行高 ≈ 19 dp）也只降到 **41 dp**，仍然是空位的 3 倍以上。

## 4. 方案 E 的结论

| 问 | 答 |
| --- | --- |
| item 高度能否保持不变 | **不能**（`wrap_content` 且内容真的变高；没有 decoration 间距可以借） |
| 最多能吃掉多少 | **12 dp / 48 dp ≈ 25 %**（把 bg 的 22 dp 前导压到 0，并占用行根下内边距） |
| 压缩后会撞到下一行文本吗 | 会。剩下的 36 dp 必须从 item 外伸出去，而下一行文本在它自己 item 顶部 +2 dp 处，`clipChildren=false` 只是不裁剪，不解决重叠 |
| 需要动哪些布局参数 | bg flexbox 的 `paddingTop`（22 dp→0）、行根 `paddingBottom`（借 10 dp）、以及 item 高度必须钉死 |
| 与宿主的 bind/measure 冲突吗 | 冲突：`A#l` 用 `measure(EXACTLY(width), UNSPECIFIED)` 算 `A$a.M`，`fragment.L0` 还按行缓存这个高度给重锚用；钉死 item 高度会让 `A$a.M` 与真实布局脱钩 |

**净结论：在“不占位、不常驻、不改字号”的前提下，后行下移无法消除；E 只能把下移量削减到约 3/4。**

## 5. 本次真正落地的修正：后续 move = 目标修正，不是第二次冲量

乙-2 原来对“同一个 holder 又来一次 `animateMove`”的处理是**累加 lag + 保速**。宿主的 `dy` 已经把当前
`translationY` 折进去了，所以 `-dy` 本来就是**绝对** lag：视觉位置不动、只挪终点。保速会让行冲过新终点
再回落 —— 这正是“被推一下再弹回来”的一段。现在（`LyricLineSpringWave#push`）：

* 新的 lag = `-dy`（不变，天然是目标修正）；
* **速度只在仍指向新终点时保留**（`v · lag < 0`），否则清零；
* 还在阶梯延迟里的行**保留剩余延迟**（layout 动了、视觉没动、本来也没有速度）；
* `push` 返回 `LyricLineSpringPush`（`lagPx` / `previousLagPx` / `offeredVelocity` / `keptVelocity` /
  `moving` / `waiting` / `layoutDeltaPx` / `velocityDropped`），只有 `moving` 时才打一行
  `retarget`（限流：同 key 300 ms、整场 160 行）。

真机上这条日志同时是**判据**：bg 展开时如果出现 `retarget ... layoutDelta=+40`，说明那次 layout 确实
派发了 move（我们修正终点）；如果**没有** `retarget` 却仍看到下推，说明 bg 的高度变化是纯 layout、
没有 `animateMove` —— 那就是第 2 节第 2 条，任何“只动 translationY”的方案都看不见它。

## 6. 真机验收

```bash
adb logcat -c && adb logcat -s AMPP-LineSpring
```

1. 播一首**有背景人声**的歌，等 4–6 次换行：
   * 期望每行只上移一次；逐帧看屏幕 y **单调不减**（不回弹）；
   * `retarget ... kept=<非 0>` 是正常修正；`dropped=1` 表示那次修正把终点翻到了行的另一侧（不会有回弹）；
   * 若出现 `layoutDelta` 接近 48 dp 的一次修正，终点直接少了这一段，观感就是 iOS 的缩短式上移。
2. 无 bg 的曲目：`takeover` 行照旧、`retarget` 行不该出现（没有第二次 layout 就没有修正）。
3. 连续换行 20 次不卡死（`finished` 计数由单测守住 18/18 那条）。
4. seek 5 行 / 切歌：允许 `retarget`，但不允许残留 `translationY`（`endAnimation`/`endAnimations` 路径未改）。

## 7. 仍未解决的部分（需要产品决定）

如果第 5 节的真机日志里**没有** `retarget` 而下推仍在，说明 bg 行的展开不经过 `animateMove`。
此时可选的只有：

| 选项 | 后行是否下移 | 空白 | 代价 |
| --- | --- | --- | --- |
| B/D 占位（常驻/按需） | **不下移** | 有（非激活行下方一片） | 用户已否 |
| E 压缩 bg 前导（22 dp→0）+ 借下内边距 | 下移 ≈ 36 dp（原 48） | 无 | 改两个人的布局参数、`A$a.M` 与真实高度脱钩 |
| C 同帧补偿（`r`/`t`/`E`/`A#l` 四个 hook，把 ΔH 折进后行 residual） | 下移但平滑（终点仍 +48 dp） | 无 | 需要 11 个新 profile 角色 + 逐帧补偿 |

三条都需要用户拍板，本提交只落地第 5 节（不改变任何布局参数，纯弹簧语义修正）。
