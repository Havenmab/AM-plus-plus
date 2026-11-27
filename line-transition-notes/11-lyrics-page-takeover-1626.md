# 11 · 歌词页接管评估（AM++ / Apple Music 1626）

> 只读研究。目标：如果把我们自己的渲染层（WebView 或原生 View）接到歌词页上，需要接哪四个东西——
> 展开入口、拖动跟手、封面+歌词、播放进度。
>
> 证据口径：
> - **DEX**：`类#方法 @N` 中的 `N` 是 `amd2.py` 打印的**指令下标**（code_item 内 16-bit unit 序号），
>   不是文件偏移；类名保留混淆名。splits 来源 `/tmp/am1626/base.apk`，解码器 `/tmp/am1626/amd2.py`。
> - **布局**：`res/layout/*.xml`（AXML 已解码，id 用 `id/xxx`）。
> - **profile**：`host-applemusic/src/main/resources/host-profiles/7.0.3-1626.json`，行号为该文件行号。
> - **既有笔记**：`line-transition-notes/*.md:行`。
>
> 结论先给：**能接，但不能只 addView 一个 WebView。** 歌词区的手势是「嵌套滚动 + view id 白名单」驱动的，
> 裸 WebView 会把「拖动跟手展开」整条链掐死；歌词区的父容器每帧 `saveLayer`，是 WebView 合成成本最差的位置。

---

## 0. 一句话结论

1626 的歌词页不是一个独立的 BottomSheet，而是 **BottomSheet 里换 Fragment**：

```
PlayerActivity / MusicContentFragment
  └─ CoordinatorLayout 的 sheet 子 View          ← BottomSheetBehavior 挂在这里（activity 持有）
       └─ id/player_sheet_container (2131364037)  ← PlayerMainFragment 被 replace 进来
            └─ fragment_player_main.xml
                 ├─ id/background_layers   (LyricsBackgroundLayerView)   ← 玻璃/封面背景
                 ├─ id/motion_switcher     (ViewSwitcher, 共享元素过渡用)
                 ├─ id/player_fragments_host (2131364031)               ← ★ 页面在这里换
                 │    └─ PlayerSongViewFragment | PlayerLyricsViewFragment | NewPlayerQueueViewFragment
                 └─ id/mini_player         (include)                    ← ★ 底栏
```

歌词页 = `PlayerLyricsViewFragment`，它 inflate `fragment_player_lyrics_sheet.xml`，
**歌词正文只是一个 RecyclerView（`id/lyrics_main_content`）**，装在一个 `AlphaGradientFrameLayout`
（`id/recycler_view_gradients`）里。标题/封面/收藏/更多/播放控制/点按区全在这张 layout 的**兄弟节点**上。

→ 所以「只换歌词正文、保留宿主标题封面按钮」**在视图树上是一等公民**，不需要伪造任何东西。

---

## 1. 歌词页是怎么打开的

### 1.1 页面切换的唯一入口：`PlayerMainFragment#s1(PlayerMainFragment$l, Bundle)`

**证据**（`classes2.dex`）：

| 位置 | 内容 |
| --- | --- |
| `PlayerMainFragment#s1(PlayerMainFragment$l;Landroid/os/Bundle;)V` **@off=5353852** | 唯一的页面切换方法 |
| `PlayerMainFragment$l` | `enum {SONG, LYRICS, QUEUE}`，每个值带 `mClz:Class` + `mTag:String` + `mWidgetToResMap:EnumMap` |
| `PlayerMainFragment#n1()Z` **@off=5350304** | 读 `BottomSheetBehavior.n0`（state），**返回 true 表示「当前不是 EXPANDED(3)」** |
| `s1` @942 | `const v4, #2131364031` → **`id/player_fragments_host`**（容器） |
| `s1` @945–954 | `n1()!=0` 分支：`androidx.fragment.app.a#d(I,Fragment,String,I)` = **add**（带 tag） |
| `s1` @961–969 | `n1()==0` 分支：`androidx.fragment.app.S#e(I,Fragment,String)` = **replace** |
| `s1` @984–990 | `fm.Q()` 为真才 `a#h(Z)I` 提交（state saved 时不提交） |
| `s1` @996–1006 | 离开/进入 LYRICS 时 `LyricsBackgroundLayerView#setReducedEffects` |
| `s1` @633–641 | `new androidx.fragment.app.a(fm)`，`S.p = true`（事务标记） |
| `s1` @796–810 | 旧 fragment 设 `setExitTransition`（`v5.B`，500 ms，`utils.G.h` PathInterpolator），然后 `a#l(old)` 隐藏 |
| `s1` @867 | `q1(fragment, sharedElements)` → 共享元素进入过渡（同 500 ms） |

**关键推论**：`add` 还是 `replace` **取决于打开那一刻 sheet 是否已 EXPANDED**。
即「从底栏点开」（sheet 还 COLLAPSED）走 **add**，「已经在全屏播放页再点歌词」走 **replace**。
这对我们是好消息也是坏消息：add 分支下旧 fragment 的 view 还在，replace 分支下会被销毁——
**我们的 View 必须自己扛住 `onDestroyView`/重建**，不能假设「只创建一次」。

共享元素的名字来自 `PlayerMainFragment$l#g(PlayerMainFragment$m)`，`$m` 是
`enum {COVER_ART_IMAGE, VIDEO_SURFACE, ...}`（`s1` @54–67 取这两个）。这就是封面在
「歌曲页 ↔ 歌词页」之间做 shared-element 飞行的机制。

### 1.2 底栏点击 → 展开歌词：`e1#K0` → `s1(LYRICS, bundle)`

**证据**：

| 位置 | 内容 |
| --- | --- |
| `player/e1#K0(Landroid/content/Context;Landroid/os/Bundle;)V` **@off=5282508** @12–14 | `s1(PlayerMainFragment$l.LYRICS, bundle)` |
| `e1#K0` @0–10 | 先 `instanceof PlayerActivity` → `PlayerActivity#Z0()` 拿 `PlayerMainFragment`；否则 `e1#x0(ctx)` → `MusicContentFragment#q1()` 拿 |
| `PlayerLyricsViewFragment#m2()V` **@off=5330992** @64 | 自己也调 `e1#K0`（「重新进歌词」），并带 `utils.G.k/.l` 两个 int 参数 |
| `PlayerSongViewFragment#O1()V` @34 | 歌曲页 → 歌词页 |
| `e1#M0(Landroid/content/Context;)V` **@off=5282644** @43 | **注意：这个开的是 QUEUE**，不是歌词 |

**`e1` 是底栏（mini player）的 adapter**：`PlayerMainFragment#onCreateView` @15–26 建
`e1(ctx, Y0)`，@60–62 把自己的 `c:BottomSheetBehavior` 塞进 `e1.T`；`q8.Z4#q0(e1)` 绑定。
调用 `e1#M0` 的两个点是 `q8/u2#a(View,I)` @26（databinding 回调）和 `bb/d#onClick(View)` @27。

> ⚠️ 别把 `M0` 当「点底栏」：`M0` = 队列。底栏本体的点按在 `PlayerActivity$k$a/$b#onClick`
> （见 1.4），它先 `c1([Z)` 展开再切页。

### 1.3 展开/收起本体：`PlayerActivity` 持有 behavior，`setState` 只有 3 个出口

| 位置 | 内容 |
| --- | --- |
| `PlayerActivity.Z0 : Lcom/google/android/material/bottomsheet/BottomSheetBehavior` | **activity 持有**，不是 fragment |
| `PlayerActivity#a1([Z)Z` **@off=3107192** @5–27 | 收起：`K(false)`（setHideable）+ 非 EXPANDED 则 `M(4)`（STATE_COLLAPSED） |
| `PlayerActivity#c1([Z)V` **@off=3108536** @22–29 | 展开：`K(false)` + `M(3)`（STATE_EXPANDED） |
| `PlayerActivity#i1(PlayerActivity$m)` **@off=3113460** | 按 `PlayerActivity$m` 枚举分派到 `a1`/`c1`/直接 `M` |
| `PlayerActivity#Z0()Lcom/apple/android/music/player/fragment/PlayerMainFragment;` **@off=3107856** @25–28 | `getSupportFragmentManager().D(2131364037)` = **`id/player_sheet_container`** |

`BottomSheetBehavior` 的方法名映射（Material，混淆后）：`K(Z)`=setHideable，`M(I)`=setState，
`n0`=state 字段，`k0`=fitToContents，`e`=expandedOffset，`f`=peekHeight。

### 1.4 自定义 Behavior：`PlayerBottomSheetBehavior`

`Lcom/apple/android/music/player/PlayerBottomSheetBehavior` **extends `BottomSheetBehavior`**（`classes2.dex`）：

| 方法 | off | 干了什么 |
| --- | --- | --- |
| `<init>(Context,AttributeSet)` | 5252712 | @17 `K(true)`（hideable）+ @21 `M(5)` → **初始 STATE_HIDDEN** |
| `f(View)F` | 5252688 | 只 `return this.H0`（float，全程没人写）→ **死代码/预留**，别当钩子 |
| `l(CoordinatorLayout,View,MotionEvent)Z` = onInterceptTouchEvent | 5252472 | 手势落在 sheet 子 View 的 hitRect 内且 state!=DRAGGING(3) 时**强行返回 true 拦截** |
| `m(CoordinatorLayout,View,int)Z` = onLayoutChild | 5252576 | state==HIDDEN 时把子 View `offsetTopAndBottom` 挪到屏幕外 |
| `p(...,II[II)V` = onNestedPreScroll | 5252780 | **按 view id 白名单**决定是「歌词/队列自己滚」还是「交给 sheet」（详见 §4.1） |
| `q(...,III[I)V` = onNestedScroll | 5252924 | `J0`/`K0` 一个 3 帧的 slop 状态机 |
| `t` / `s` | 5252428 / 5252996 | 保存/恢复 state（`PlayerBottomSheetBehavior$a.c`） |

**没有 `ViewPager` 参与歌词页切换**（`ViewSwitcher` 只负责共享元素过渡的画面）。

### 1.5 拖动进度怎么暴露 —— 三条，只有第 2 条是给外人的

这是本题最容易踩空的地方。链是：

```
BottomSheetBehavior 内部逐帧算 slideOffset
  → BottomSheetBehavior$c.onSlide(View, float)            [回调接口]
    = PlayerMainFragment$i#b(View,F)    @off=5345332       [实现]
       ├─ (A) M9.e#b(F)                                    ← 封面视差，内部
       ├─ (B) PlayerMainFragment$i#d(F)   @off=5343540      ← 内部
       └─ (C) new PlayerFragmentSlideEvent{ a = slideOffset }
              + lh.b#f(Object)  → EventBus.post             ← ★ 唯一公开出口
                 └─ fragment/m$g#onEventMainThread(PlayerFragmentSlideEvent) @4
                      → fragment/m#onBottomSheetSlide(F)
                         → PlayerLyricsViewFragment#onBottomSheetSlide(F)  @off=5331656
```

证据细节：

- `PlayerMainFragment$i` **extends `BottomSheetBehavior$c`**，方法：
  `b(View,F)`=onSlide(5345332)、`c(View,I)`=onStateChanged(5345760)、`d(F)`(5343540)、
  `e()`(5345232)、`f(View)`(5346328)。
- `i#b` @183–194：`new PlayerFragmentSlideEvent` → `iput v6 → .a:F` → `lh.b#f(Object)`（EventBus.post）。
- `PlayerFragmentSlideEvent` 只有 **一个字段 `a : F`**（`classes2.dex`）。
- 注册点在 `PlayerMainFragment#onCreateView` @1–6：`iput a0 = new PlayerMainFragment$i(this)`。

> ⚠️ **两个坑**
> 1. `i#b` @9–14 有早退：`if (getContext()==null || slideOffset >= 0) return;`
>    → **事件只在 slideOffset < 0 时发**。也就是说 1626 这个 sheet 的展开方向对应**负偏移**，
>    你在 `onBottomSheetSlide` 里收到的 `f` 不是 Material 教科书的 0→1。量级要真机验（见 §6-E1）。
> 2. 这个事件**不是 sticky**，且只在 `PlayerMainFragment` 活着时发；收起后不会再发最后一次值。
>    Fragment 重建后必须自己重新订阅并补一次当前值（`BottomSheetBehavior.n0` + `getExpandedOffset`）。

- `PlayerLyricsViewFragment#onBottomSheetSlide(F)` @off=5331656 极短：
  `if (f <= 0.3f) return; else PlayerLyricsViewFragment#x2(Activity)`。
  `x2` @off=5335672 只是清 Window 的 immersive 位（`& ~0x1002`，去 STICKY + 显示导航栏）。
  **即：宿主的 slide 回调跟歌词渲染无关，纯粹是系统栏。**

**结论**：拿拖动进度，最稳的是**订阅 EventBus 的 `PlayerFragmentSlideEvent`**（零 hook、零签名依赖，
照抄 `player/fragment/m$g` 的订阅方式即可）；次稳是 hook `PlayerMainFragment$i#b(View,F)`；
再次是 hook `PlayerBottomSheetBehavior#l/m/p/q`（要自己重算 slideOffset，不推荐）。

---

## 2. 可插入的位置

### 2.1 歌词页视图树（`res/layout/fragment_player_lyrics_sheet.xml`，无变体）

```
ConstraintLayout id/container  (marginTop=dimen/current_player_margin_top)
├─ LinearLayout id/current_player_item        ← 头部：可点，含封面+标题+收藏+更多
│   ├─ MaterialCardView id/lyrics_thumbnail_container  (transitionName=lyrics_cover_art_container...)
│   │    ├─ TextureView          id/video_surface      ← 视频封面（TextureView！不是 SurfaceView）
│   │    └─ PlayerTransitionImageView id/thumbnail     ← ★ 封面图片
│   ├─ LinearLayout id/text_metadata_container
│   │    ├─ CustomTextView id/mini_player_title
│   │    └─ CustomTextView id/mini_player_subtitle
│   ├─ ImageView id/list_favorite_icon
│   └─ ImageView id/list_left_icon
├─ AlphaGradientFrameLayout id/recycler_view_gradients   ← ★ 歌词区的父容器（FrameLayout 子类）
│    └─ RecyclerView id/lyrics_main_content             ← ★ 歌词正文
├─ Guideline id/tap_target_guideline
├─ View id/controls_tap_target                ← 下半屏点按=显示/隐藏控制条
├─ FrameLayout id/controls  → include id/player_controls (constraint_player_media_controls)
├─ ImageView id/translations_button
├─ VocalAttenuationControl id/vocal_ctrl
├─ CustomTextView id/no_lyrics_available_message
├─ include id/va_bubble_tip / id/translations_bubble_tip
└─ FrameLayout id/loading_progress  → ProgressBar
```

**歌词正文的绑定与装配点集中在 `PlayerLyricsViewFragment#onCreateView`（@off=5325472）**：

| 行 | 动作 |
| --- | --- |
| @186–199 | `androidx.databinding.g#d(inflater, 2131558992, container, false, adapter)` → `q8.X4`（`layout/fragment_player_lyrics_sheet`），存入字段 `l0` |
| @201–220 | `binding.m0`（`id/thumbnail`，`PlayerTransitionImageView`）设 `setOnResourceAction`/`setOnLoadFailedAction` |
| @211–234 | `binding#r0(Y0)`、`binding#q0(e1)` 绑 databinding 变量 |
| @237–243 | `binding.d0 (RecyclerView)`.`setAdapter(n0 = V0)` |
| @246–252 | `.setLayoutManager(q0 = PlayerLyricsViewFragment$w)` |
| @255–261 | `.setItemAnimator(r0 = $u)` |
| @264–270 | `.g(s0 = $v)`（addOnScrollListener）+ `setHasFixedSize(true)` |
| @282–291 | `binding.b0 (ImageView)` 收藏按钮 setEnabled |
| @294–300 | `binding.i0 (q8.t2)` = include 的媒体控制条 |

配套 getter（都是**一行 iget，稳定到可以当契约**）：

| 方法 | off | 返回 |
| --- | --- | --- |
| `getRecyclerView()` | 5327312 | `l0.d0` → `id/lyrics_main_content` |
| `m1()` | 5328680 | `l0.m0` → `PlayerTransitionImageView`（封面） |
| `u1()` | 5325300 | `l0.q0` → `TextureView`（视频封面） |
| `S1()` / `T1()` / `R1()` | 5325412/5325444/5325344 | 控制条相关 View |

### 2.2 三个插入档位（都能保留宿主手势，差别在「插入点多深」）

**(A) 只覆盖歌词正文 —— 推荐第一档**

插入点：`id/recycler_view_gradients`（`AlphaGradientFrameLayout`，FrameLayout 子类）。
在该容器里 `addView(ourView, MATCH_PARENT, MATCH_PARENT)`，把 `id/lyrics_main_content` 设为 `INVISIBLE`
（**不要 GONE**，见 §4.1：INVISIBLE 仍参与布局，id 也还在树里）。

- 保留：头部（封面/标题/收藏/更多）、`controls`、`controls_tap_target`、`vocal_ctrl`、所有边距/约束、
  宿主的 slide 回调、沉浸式切换。
- 需要 hook：`PlayerLyricsViewFragment#onCreateView`（**after**，拿 `l0`）、`#onDestroyView`（移除）、
  `#onResume`（补一次进度）；以及自己的 `NestedScrollingChild` 包装（§4.1）。
- 不需要 hook：`s1`、`n1`、`PlayerBottomSheetBehavior` 任何方法、`PlayerActivity` 任何方法。

**(B) 覆盖整页但保留手势**

插入点：`PlayerMainFragment` 的根 View（`fragment_player_main.xml` 的 `RelativeLayout`），或
`PlayerLyricsViewFragment.getView()` 的父容器（= `id/player_fragments_host`）。
此时 `lyrics_main_content` 与头部一起被盖住，手势只能靠我们自己的 wrapper 转发给
`CoordinatorLayout`/`PlayerBottomSheetBehavior`。

- 需要 hook：`s1`（知道何时进/出 LYRICS，以便挂/卸我们的层）、`n1`（判断 add/replace 语义）、
  `PlayerLyricsViewFragment#onCreateView/onDestroyView/onResume/onPause`、
  `PlayerMainFragment#onCreateView`（拿 `c:BottomSheetBehavior` 与 `player_fragments_host`）、
  `PlayerBottomSheetBehavior#p/q`（把我们的 wrapper 的嵌套滚动接上去）。
- 需要在我们的 wrapper 里**完整复刻** §4.1 的语义，否则手感会变。

**(C) 完全接管**

不再用宿主的 Fragment 事务：hook `s1` 的 `LYRICS` 分支，`returnResult` 掉，自己在
`id/player_sheet_container`（或 `player_fragments_host`）里 `add` 我们的 Fragment/View；
`BottomSheetBehavior` 只留 `setState`/`STATE_EXPANDED` 与 `PlayerFragmentSlideEvent` 作为输入。

- 需要 hook：`s1`、`PlayerActivity#a1/c1/i1`、`PlayerMainFragment#n1`、`BottomSheetBehavior#M(I)/K(Z)`、
  `PlayerMainFragment$i#b`、`e1#K0/M0`、`PlayerMainFragment$l#e()`（tag）、`PlayerActivity#Z0()`。
- 代价：宿主自己的 shared-element 过渡（`q1` + `v5.B` 500 ms）、`setReducedEffects`、
  迷你播放器的 databinding 会有一半失效；等于接管产品的整个播放页。

### 2.3 关于「能不能替换 fragment 的 content」

技术上完全可以，但**不建议 replace 那个 Fragment**：`PlayerMainFragment#s1` 已经用
`id/player_fragments_host` 做 replace，且用 tag（`$l#e()`）做 `findFragmentByTag`。
我们再插一个同 tag 的 fragment 会和 `m1()`（@off=5350240，`getChildFragmentManager().E(tag)`）打架。

**正确做法是「在 View 层插入」，不动 Fragment 事务**。View 层唯一的硬约束是父容器类型
（`FrameLayout`/子类 → 可任意叠），而 `recycler_view_gradients` 正好是。

---

## 3. 数据来源

### 3.1 播放进度 —— 4 个候选，按稳定性排序

| # | 点 | 证据 | 评价 |
| --- | --- | --- | --- |
| **1** | **`MediaControllerCompat.getCurrentPosition()`** | `PlayerLyricsViewFragment#y2(I)J` **@off=5329096** @31 `invoke-virtual {v0}, J4.B#getCurrentPosition()J`；`J4.B` = MediaControllerCompat，经 `ia.a$c#getMediaBrowser()` 拿到 | ★ 最稳：宿主自己就用它，`J4.B` 是 androidx.media 类（不随版本改），`getMediaBrowser` 已是 profile 里的契约级入口。缺点：要自己起 ticker |
| **2** | **hook `y2(I)J` 的入参** | 同上；`y2` 由 `$h#run()`@12、`d2`@24、`X1(I)`@169、`w2(SongInfoPtr)`@247 驱动 | 次稳：跟着宿主的节拍走，零额外 ticker。但 `y2` 是混淆名（每版变），要进 profile |
| **3** | **`PlayerLyricsViewModel#setCurrentHighlightedLine(I)`** | **@off=5434408**；`getCurrentHighlightedLine()` **@off=5432132** | 只有**行号**，没有毫秒；适合当「对齐修正」而不是主时钟 |
| **4** | 自己 poll `PlayerActivity#Z0()` → `PlayerMainFragment.l1()`（`N8.q`） | `PlayerMainFragment#l1()LN8/q;` **@off=5348980** | 最差：`N8.q` 是宿主接口，且要穿过两层 |

> **进度是「自调度」的**，这点很重要：`y2` @96 调 `ttml/f#c(SongInfoPtr, long, 5×oi.q)J`，
> 返回值 = **下一次 tick 的延迟毫秒**，@119–133 `if (ret > 0) handler.sendMessageDelayed(obtainMessage(msgId), ret)`。
> 即宿主的歌词引擎是**按需节拍**（native 算出来的），不是固定 16 ms。
> 如果我们自己起 ticker，用 16 ms 就够（显示层），但要跟 `getCurrentPosition()` 做漂移校正。

补充：`d2(int)` **@off=5329392** 是 seek 入口 —— 先
`SongInfoTimeProcessorNative#suggestLineOffset(int)`，再 `n2()`，再 `y2(getPlaybackState())`。
拖进度条之后宿主的行偏移会被重算，我们的层也要跟。

### 3.2 歌词文档 —— 两条都通，一条是「我们已有的」

**(a) 我们自己的 `lyricResultSubmission` 管线（已在 profile 里）**

`host-profiles/7.0.3-1626.json` 的 `lyricResultSubmission`：

| 角色 | 指向 |
| --- | --- |
| `fields.fragmentViewModel` | `PlayerLyricsViewFragment.m1 : PlayerLyricsViewModel` |
| `fields.lyricsResult` | `PlayerLyricsViewModel.mLyricsResult : MutableLiveData` |
| `methods.buildIndex` | `PlayerLyricsViewModel#buildTimeRangeToLyricsMap(SongInfoPtr)` @off=5433652 |
| `methods.pointerGet` | `SongInfo$SongInfoPtr#get()` |
| `methods.pointerAdamId` | `SongInfo$SongInfoNative#getAdamId()` |
| `methods.pointerScript` | `SongInfo$SongInfoNative#setScript(String)` |
| `methods.loadLyrics` | `PlayerLyricsViewModel#loadLyrics(PlaybackItem)` @off=5433876 |

→ **最稳的文档读取点：`PlayerLyricsViewFragment.m1.getLyricsResult().getValue()`**，
或直接读 fragment 字段 `f1 : SongInfo$SongInfoPtr`（由 `w2`/`F2`/`t2`/`u2` 等安装路径写入；
`onCreate` @74–79 只建了 `Z0 : ttml/f` 引擎，`f1` 当时还是 null）。

**(b) 原生 TTML 模型（要逐行/逐字时间就得走这条）**

| 层级 | 方法（javacpp wrapper） |
| --- | --- |
| `SongInfo$SongInfoNative` | `getSections()LyricsSectionVector`、`getAvailableTiming()Dk.a`、`getLanguage()`、`getScript()`、`getTiming()J`、`getAdamId()J` |
| `LyricsSection$LyricsSectionNative` | `getLines()LyricsLineVector` |
| `LyricsLine$LyricsLineNative` | `getLineId()I`、`getWords()/getBackgroundWords()/getPronunciationWords()/getTranslatedBackgroundWords()`、`getHtmlLineText()/getHtmlTranslationLineText()/getHtmlPronunciationLineText()` |
| `LyricsWord$LyricsWordNative` | `getWordId()I`、`getHtmlLineText()`、`isWhitespace()Z`、`getLyricsLine()` |
| `LyricsTiming` | `getBegin()I`、`getEnd()I`、`getDuration()I`、`getAgent()` |

> `LyricsTiming#getBegin` 在宿主里由 `player/A#b0(...)` / `#c0(...)`（行/字渲染器）读取，
> 说明**逐字时间可以从模型里拿到**。但 `LyricsWordNative` 本身没有 timing getter，
> 具体从哪个句柄取 `LyricsTiming` 要真机串一次（§6-E2）。

**(c) 还有一个「白送」的时间线出口**：`ttml/f#c` 的 5 个回调
（`PlayerLyricsViewFragment$f/$g/$n/$p/$r`，都是 `oi/q` 三参 lambda）——
`SongInfoTimeProcessor#processEvents` 每拍把 `(long time, LyricsLineVector|LyricsWordVector, long)`
推给它们。hook 这 5 个（或直接 hook `ttml/f#c` / `#d`）等于拿到**宿主算好的「当前行+当前字+时间」**，
比我们自己解析 TTML 再对齐省事，且永远跟宿主一致。

### 3.3 封面

| 路径 | 证据 | 适用 |
| --- | --- | --- |
| **`PlayerLyricsViewFragment#m1()`** | @off=5328680 → `l0.m0 : PlayerTransitionImageView`（`id/thumbnail`） | ★ 最省事：直接读宿主已设好的 drawable/bitmap，**零网络、零缓存、天然同步**（切歌时宿主自己换） |
| `PlayerLyricsViewFragment#u1()` | @off=5325300 → `l0.q0 : TextureView`（`id/video_surface`） | 视频封面走这条（`SurfaceTexture`） |
| `LyricsBackgroundLayerView` | `setArtwork(Bitmap)` @off=5197776；静态 `b(Bitmap,int,int,long,Context)` @off=5193908 会做模糊 | 背景玻璃用的同一张图，可能是大尺寸+预模糊版本 |
| MediaItem metadata | `PlayerMainFragment#onEventMainThread(PlayerImageUpdate)` @off=5352592 | 事件驱动的更新通知 |

> 建议：**封面只读 `m1()` 的 `Drawable`**，不要自己按 artworkUri 去下载。
> 理由：`PlayerTransitionImageView` 参与 shared-element 过渡（`transitionName` 在 layout 里写死），
> 自己再下一张会导致「飞行图和落地图不是同一张」。

---
## 4. 风险

### 4.1 ★ 最大风险：裸 WebView 会让「拖动跟手展开」失效

`PlayerBottomSheetBehavior#p`（onNestedPreScroll，**@off=5252780**）逐条：

```
@0      if (dy < 0) goto super                       // 向下滚一律交给 sheet
@2-18   v5.getId() == 2131364121 (id/queue_main_content)
        || v5.getId() == 2131363638 (id/lyrics_main_content)   // ★ view id 白名单
@20     canScrollVertically(SCROLL_AXIS_VERTICAL)
@25     if (!canScroll) goto super
@27-43  J0 ? +inf : ViewConfiguration.getScaledTouchSlop()
@47-56  if (|dy| >= slop && state == 1 /*DRAGGING*/) return   // ★ 让歌词列表吃掉这次滚动
@59     super.p(...)                                          // 否则 sheet 自己消费
```

两个直接后果：

1. **白名单是 view id**。我们用任意 View 替换歌词正文，只要它不是 `id/lyrics_main_content`
   这个实例，这条特判就**永不命中** → sheet 会抢滚动。若把 RecyclerView 设 `GONE`，
   `canScrollVertically` 变 false → 同样永不命中。留 `INVISIBLE` 时它仍参与布局、仍能垂直滚动，
   **大概率仍返回 true** —— 所以「RecyclerView 保持 INVISIBLE + 我们的 View 叠在上面」是唯一
   能保住白名单特判的做法，代价是触点被最上层吃掉，我们自己的 View 拿不到滚动。
2. **`android.webkit.WebView` 不实现 `NestedScrollingChild`**（Android 平台事实，非本 DEX 结论）。
   它不会 `dispatchNestedPreScroll`，所以 `BottomSheetBehavior.onNestedPreScroll` 根本不会被调用，
   拖动会被 WebView 自己的滚动吃掉。

**可行解**（按推荐度）：

- **A1**：我们的 View 外层套一个自写的 `FrameLayout implements NestedScrollingChild2`，
  在 `onTouchEvent`/`onInterceptTouchEvent` 里把 dy 先 `dispatchNestedPreScroll`，
  `ViewCompat.setNestedScrollingEnabled(true)`；滚动到顶/底时把剩余 dy 交回。
  为命中白名单，可把 wrapper 的 id 设为 `R.id.lyrics_main_content` 并让 RecyclerView 退场——
  但那会与 `AlphaGradientFrameLayout` 的 `findViewById` 语义打架，需要实测。
- **A2**：保留 `id/lyrics_main_content` 作为**唯一滚动宿主**（可见、内容透明），把 WebView 设为
  `clickable=false`/`focusable=false`/`isNestedScrollingEnabled=false`，**完全由 JS 侧跟随**
  宿主 RecyclerView 的滚动位置（`K#b` 的重锚 / `scrollToPositionWithOffset`）。
  即「WebView 只负责画，滚动手感 100% 由宿主 RecyclerView 提供」。
  代价：AMLL 的惯性/弹簧滚动用不上，只能拿到宿主「换行即重锚」的离散位置。
- **A3**：完全接管（C 档），自己实现 drag。

> 相邻的坑已有记录：`05-plugin-sdk-v1-capability.md:139`（`K#b`/`u1` 是每版变的混淆成员，
> SDK 无定位辅助）、`01-lyrics-line-transition-1626.md:19`（位移来自 `scrollToPositionWithOffset`）。

### 4.2 ★ 第二大风险：`AlphaGradientFrameLayout` 每帧 `saveLayer`

`Lcom/apple/android/music/common/views/AlphaGradientFrameLayout#dispatchDraw` **@off=3204712**
里调 `android.graphics.Canvas#saveLayer(FFFFLandroid/graphics/Paint;I)`；
构造路径 `a(AttributeSet,I)` **@off=3205776** 建 `PorterDuffXfermode(DST_IN)`。

含义：**歌词区每帧都在做离屏图层 + DST_IN 混合**。把 WebView（自己还有一层合成
functor/纹理）塞进这个 `saveLayer` 区域，等于每帧多一次全区域离屏合成。这是本题**最差的合成位置**。

叠加因素：
- `LyricsBackgroundLayerView`（`extends View`）自己 `onDraw` 画背景玻璃，静态
  `b(Bitmap,IIJContext)` @off=5193908 做模糊，`setReducedEffects(Z)` @off=5198012 由 `s1` @1006 切换。
- `id/motion_switcher` 有 `setClipToOutline(true)` + 自定义 `ViewOutlineProvider`
  （`PlayerMainFragment#onCreateView` @64–78），圆角裁剪又是一层。
- 视频封面是 **`TextureView`**（不是 `SurfaceView`）—— 这点反而是**好消息**：
  同窗口内没有独立 Surface 的 z-order 问题，WebView 与它共存不需要 `setZOrderMediaOverlay` 之类。

**缓解**：把我们的层挂在 `recycler_view_gradients` 的**外面**（父级 `id/container` 里，
手工复制 `recycler_view_gradients` 的 4 条约束），绕开 `saveLayer`。代价是要自己写
`ConstraintLayout.LayoutParams`，不能用「addView 到 FrameLayout」这种一行代码的省事做法。

### 4.3 Fragment 生命周期

- `s1` 在 **sheet 未展开时 add、已展开时 replace**（§1.1）→ 同一份「歌词页」可能被 add 多次、
  也可能被 replace 销毁重建。**我们的 View 必须幂等**：`onCreateView` after 里先按自建 tag/弱引用表去重。
- `PlayerLyricsViewFragment#onDestroyView`（@off=5332264）要负责摘掉 WebView 并 `destroy()`，
  否则 Chromium 侧泄漏。
- `onResume`（@off=5335376）是补一次进度的合理时机（profile 已有 `fragmentResume` 角色）。
- shared-element 过渡有 `postponeEnterTransition(1, SECONDS)`（`onCreateView` @11）：
  **过渡没结束时插 View 会闪一下**，建议等 `onResume` 或过渡回调再挂。

### 4.4 WebView 在 Xposed 进程里的加载

- AM++ 是 LSPosed 模块，注入代码跑在**宿主自己的进程与 classloader** 里，
  所以 `new WebView(activity)` 走的是宿主的 WebView provider —— 正常路径，**不是**「从模块
  classloader 建 WebView」那种会 ClassNotFound 的场景。
- 必须用 **Activity/Fragment 的 themed context**（`PlayerLyricsViewFragment.requireContext()`），
  不要用 application context（拿不到主题，弹窗/媒体权限会出问题）。
- 第一次 `new WebView` 会拉起 Chromium 子进程，**冷启动 100–300 ms**，且必须主线程。
  → 不要在 sheet 滑动过程中惰性创建；在 `onCreateView`/`onResume` 预建并复用。
- 宿主视频封面用的是 `TextureView`，因此**没有 `addView` 与独立 Surface 的合成冲突**。
  真正的合成风险是 §4.2 的 `saveLayer`。
- 需要 `android:hardwareAccelerated`（宿主默认开）。**不要**给 WebView 设 `LAYER_TYPE_SOFTWARE`
  ——在 `saveLayer` 区域里会立刻掉到 CPU 合成。

### 4.5 性能（WebView 合成层 + 玻璃）

| 项 | 量级 | 说明 |
| --- | --- | --- |
| WebView 一块满屏纹理 | ~1080×2000×4 ≈ 8.6 MB | 还要叠加宿主已有的背景 bitmap |
| `saveLayer` 区域 | 同歌词区大小 | §4.2，每帧一次 |
| 行级模糊 | 宿主本身没有（`lyricBlur` 是 AM++ 自己的功能） | 但 AMLL 侧 `setEnableBlur`（`packages/core/src/lyric-player/base/index.ts:450`）是 CSS `filter: blur()`，由 Skia 做，**与 AM++ 的原生模糊不是同一种**，观感不会一致 |
| 逐帧 JS→native 桥 | 60 Hz × 1 次 `evaluateJavascript`，可接受 | 但**不要**每帧传整份歌词，只传 `time` |

### 4.6「只替换歌词内容、保留宿主标题/封面/按钮」的可行性

**可行性：高。** 三个理由，都有证据：

1. 头部（`current_player_item`）、控制条（`controls`）、点按区（`controls_tap_target`）、
   `vocal_ctrl`、bubble tip **全部是 `id/container` 的直接子节点**，与歌词区**并列**，
   各自有独立约束（`fragment_player_lyrics_sheet.xml`）。盖住 `recycler_view_gradients` 不影响它们。
2. 封面在 `id/thumbnail`，是 `PlayerTransitionImageView`，参与 shared-element；我们不碰它就行。
3. 播放按钮不读歌词数据（走 `binding.i0` / `player_controls`，是另一个 databinding 子树）。

**唯一做不到的**：`id/recycler_view_gradients` 的上下渐变淡出是父容器的 `saveLayer` 效果。
我们的 View 加到外面就**没有渐变淡出**，需要自己画（或接受硬边）。

### 4.7 其他小坑

- `PlayerBottomSheetBehavior#<init>` @21 把初态设为 **STATE_HIDDEN(5)**，不是 COLLAPSED。
  任何「读 state 判断是否可见」的逻辑要接受 5。
- `n1()` 在 `c == null` 时返回 `15 != 3` = **true**（当成「未展开」），不是 false。
- `PlayerFragmentSlideEvent.a` 只承载 `float`，**没有 state**。要 state 得 hook `PlayerMainFragment$i#c(View,I)`。
- `s1` @984 只在 `fm.Q()` 为真时提交 → 后台（state saved）切页会被丢掉；我们的层要能接受「这次没切」。

### 4.8 不在本节范围、但足以否决的两条（来自 AMLL 侧评估）

这两条不是 Apple Music 宿主的问题，但只要走 WebView 就绕不开，写在风险里以免被当成「实现细节」：

1. **许可**：AMLL 是 AGPL-3.0-only，AM++ 是 GPL-3.0。把 AMLL 的代码/bundle 塞进 AM++ 的发布物，
   按保守解释会把整包拖进 AGPL（见 `11-amll-webview-drivability.md:14` 的判定表「许可」行与 §5）。
   这条在动工前必须先定，不是技术选型。
2. **架构互斥**：AM++ 目前**零 WebView**（全仓 grep 无命中；本轮也复核过
   `host-applemusic/src/main` 下无 `WebView`），歌词完全是原生 View 注入。
   A 档等于在同一个页面里并存两套渲染哲学——这不是代码量问题，是后续每条歌词特性都要写两遍。

---

## 5. 工作量估计

前提：**渲染层本身不算在这三个数字里**（AMLL 的渲染器 `packages/core/src/lyric-player/dom/` 已经是
DOM 实现，打包成 WebView bundle 是另一条工作线）。下面只算「接到宿主上」的成本。
单位：**人日**（1 人 × 1 天），含真机验证。

### 5.1 三档

| 档 | 范围 | 关键工作 | 工作量 |
| --- | --- | --- | --- |
| **A：只覆盖歌词区域** | 换掉 `lyrics_main_content` 的**渲染**，保留头部/封面/控制/手势 | ① after-hook `onCreateView` 取 `l0`；② 在 `recycler_view_gradients` 内挂 View + RecyclerView INVISIBLE；③ 自写 `NestedScrollingChild2` wrapper（§4.1 A1）或走 A2 的「JS 跟随」；④ 进度 ticker（`getCurrentPosition` 16 ms + 漂移校正 + 每帧 `update(delta)`）；⑤ 文档抽取（`SongInfoNative` → 线段/字段时间）；⑥ 封面取 `m1().getDrawable()`；⑦ 生命周期幂等 + `destroy()` | **10–14** |
| **B：覆盖整页，保留手势** | 头部/控制条也由我们画（或半透明覆盖），手势仍走宿主 sheet | A 的全部 + ① hook `s1`/`n1` 判进出 LYRICS；② 把 wrapper 的嵌套滚动接到 `BottomSheetBehavior`（复刻 `p` 的 slop 语义）；③ 控制条交互自己接（播放/上一首/下一首/`VocalAttenuationControl`）；④ 沉浸式与状态栏（原本是 `x2` 的职责）；⑤ 平板双栏（`fragmentDualPane`，profile 里 30 methods） | **18–26** |
| **C：完全接管** | 不用宿主 Fragment 事务，自己当页面 | B 的全部 + ① `s1` 的 LYRICS 分支 `returnResult` 接管；② 自己实现展开/收起（复用 `BottomSheetBehavior.setState` 或自写 offset 驱动）；③ 复刻 shared-element 过渡（500 ms PathInterpolator）或放弃；④ 迷你播放器 databinding 的一半改由我们驱动；⑤ `setReducedEffects` 背景玻璃替换 | **30–45** |

### 5.2 每档需要新增的 profile 角色数量级

现有 `7.0.3-1626.json` 的规模可作标尺：`fragmentLyricsRuntime` 12 methods/13 fields，
`fragmentChrome.members` 37/11，`lyricBlur` 63/22，`lyricResultSubmission` 12/2。

| 档 | 新增 section | 新增角色量级 | 主要角色（示例，均已有 DEX 证据） |
| --- | --- | --- | --- |
| **A** | `lyricsViewReplacement` | **约 15–20**（12–15 methods + 4–6 fields） | `fragmentClass`、`createView`(5325472)、`destroyView`(5332264)、`resume`(5335376)、`fields.binding`(`q8.X4 l0`)、`fields.recycler`(`X4.d0`)、`fields.artwork`(`X4.m0`)、`getRecyclerView`(5327312)、`coverArt`(5328680)、`artwork`(`LyricsBackgroundLayerView#setArtwork`)、`pointerGet`、`sections`(`SongInfoNative#getSections`)、`lines`(`LyricsSectionNative#getLines`)、`words`(`LyricsLineNative#getWords`)、`wordText`(`LyricsWordNative#getHtmlLineText`)、`timingBegin/End`(`LyricsTiming`)、`mediaController`(`ia.a$c#getMediaBrowser`)、`position`(`J4.B#getCurrentPosition`) |
| **B** | A + `lyricsPageTakeover` | **约 28–35** | A 的全部 + `swapPage`(`PlayerMainFragment#s1` 5353852)、`isCollapsed`(`#n1` 5350304)、`fields.behavior`(`PlayerMainFragment.c`)、`fields.host`(`id/player_fragments_host` 2131364031)、`slide`(`PlayerMainFragment$i#b` 5345332)、`state`(`#c` 5345760)、`slideEvent`(`PlayerFragmentSlideEvent.a`)、`nestedPreScroll`(`PlayerBottomSheetBehavior#p` 5252780)、`nestedScroll`(`#q` 5252924)、`setState`(`BottomSheetBehavior#M`)、`setHideable`(`#K`)、`stateField`(`n0`)、`pageEnum`×3、`enterLyrics`(`e1#K0` 5282508) |
| **C** | B + `playerPageOwnership` | **约 45–55** | B 的全部 + `expand`(`PlayerActivity#c1` 3108536)、`collapse`(`#a1` 3107192)、`command`(`#i1` 3113460)、`getHost`(`#Z0` 3107856)、`fields.sheetContainer`(2131364037)、`behaviorClass`(`PlayerBottomSheetBehavior`)、`onLayoutChild`(`#m` 5252576)、`onInterceptTouch`(`#l` 5252472)、`saveState`/`restore`(`#t`/`#s`)、`pageList`(`$l#values`)、`pageTag`(`$l#e`)、`sharedElement`(`$l#g`)、`exitTransition`(`s1` @796–810) |

> 口径说明：profile 的每个角色必须是**精确签名**（`scripts/verify-host-profile.py:699` 要求
> 「invalid exact descriptor」报错），所以「角色」≈「一个 6 字段的 JSON 对象」。
> 上表是**新增**量级；若复用 `fragmentLyricsRuntime`/`fragmentChrome` 里已有的
> `createView`/`destroyView`/`resume`/`slide`/`progress`，A 档可再省 4–6 个。

### 5.3 建议路径

1. **先做 A2**（WebView 只画、宿主 RecyclerView 当滚动宿主）验证观感，1–2 天出原型。
   它绕开了 §4.1 的全部难点，但只能验证「画得对不对」，不能验证手感。
2. 若观感 OK，再投入 A1（自写 `NestedScrollingChild2` wrapper）把跟手拽回来。
3. B/C 只在「产品决定连头部和控制条一起换」时才值得；否则永远停在 A。

---

## 6. 待真机验证清单

| # | 要验什么 | 怎么验 | 为什么必须验 |
| --- | --- | --- | --- |
| E1 | `PlayerFragmentSlideEvent.a` 的实际符号与量级 | 订阅 EventBus，打印 `slideOffset` 从 COLLAPSED→EXPANDED 全过程 | §1.5 的 `>= 0 return` 说明它是负向；不知道映射就画不出「跟手」 |
| E2 | 行/字 timing 的取法 | 从 `LyricsWordNative` 拿 `LyricsTiming` 的实际调用链 | `LyricsWordNative` 没有 timing getter，宿主是经 `player/A#b0/c0` 取的 |
| E3 | RecyclerView 设 INVISIBLE 后 `canScrollVertically(15)` 是否为 true | 真机 hook `PlayerBottomSheetBehavior#p` 打印 | 决定 §4.1 的白名单特判能不能保住 |
| E4 | `saveLayer` 区域塞 WebView 的帧率 | `dumpsys gfxinfo` + 滑动掉帧统计 | §4.2 是第一大性能未知 |
| E5 | 首次 `new WebView` 在 1626 进程里的耗时 | `System.nanoTime` 包住构造 | 决定是否要预建 |
| E6 | `s1` 的 add/replace 是否真按 `n1()` 分流 | hook `s1` 打印 `n1()` 与分派到的 `a#d`/`S#e` | add 分支下旧 view 还在，会撞我们的去重逻辑 |

---

## 附 A：本轮新增的 DEX 证据索引

| 类#方法 | off | 一句话 |
| --- | --- | --- |
| `PlayerMainFragment#s1(PlayerMainFragment$l;Bundle)V` | 5353852 | 唯一的页面切换入口，容器 `player_fragments_host`(2131364031) |
| `PlayerMainFragment#n1()Z` | 5350304 | `BottomSheetBehavior.n0 != 3`（未展开） |
| `PlayerMainFragment#m1()Landroidx/fragment/app/o;` | 5350240 | `getChildFragmentManager().E(tag)` |
| `PlayerMainFragment#onCreateView(...)` | 5349356 | @38 `BottomSheetBehavior.from(container)`；@1 建 `$i` 回调 |
| `PlayerMainFragment$i#b(View,F)V` | 5345332 | = onSlide；@183–194 post `PlayerFragmentSlideEvent` |
| `PlayerMainFragment$i#c(View,I)V` | 5345760 | = onStateChanged |
| `PlayerMainFragment$i#d(F)V` | 5343540 | 内部 slide 消费者 |
| `PlayerBottomSheetBehavior#<init>` | 5252712 | hideable + 初态 HIDDEN(5) |
| `PlayerBottomSheetBehavior#l/m/p/q/t/s/f` | 5252472/5252576/5252780/5252924/5252428/5252996/5252688 | 手势与 id 白名单 |
| `PlayerActivity#Z0()PlayerMainFragment` | 3107856 | `findFragmentById(2131364037)` |
| `PlayerActivity#a1([Z)Z` / `#c1([Z)V` / `#i1(PlayerActivity$m)V` | 3107192 / 3108536 / 3113460 | 收起 / 展开 / 命令分派 |
| `e1#K0(Context,Bundle)V` / `#M0(Context)V` | 5282508 / 5282644 | 进歌词 / 进队列 |
| `PlayerLyricsViewFragment#onCreateView(...)` | 5325472 | inflate 2131558992 + setAdapter/LM/Animator |
| `PlayerLyricsViewFragment#onBottomSheetSlide(F)V` | 5331656 | 只切沉浸式 |
| `PlayerLyricsViewFragment#x2(Activity)V` | 5335672 | 清 `SYSTEM_UI_FLAG` 0x1002 |
| `PlayerLyricsViewFragment#y2(I)J` | 5329096 | 进度 tick，@31 `getCurrentPosition()` |
| `PlayerLyricsViewFragment#d2(int)V` | 5329392 | seek → `suggestLineOffset` |
| `PlayerLyricsViewFragment#X1(I)V` | 5334072 | 播放态变化 → @169 `y2` |
| `PlayerLyricsViewFragment#getRecyclerView/m1/u1` | 5327312 / 5328680 / 5325300 | 三个 getter |
| `ttml/f#c(SongInfoPtr,J,5×oi.q)J` / `#d(...)J` | 3842536 / 3842376 | 每拍喂时间给 native |
| `SongInfoTimeProcessor#processEvents` | — | 5 个回调的时间线出口 |
| `AlphaGradientFrameLayout#dispatchDraw` | 3204712 | `saveLayer` + `DST_IN` |
| `LyricsBackgroundLayerView#setArtwork/setReducedEffects` | 5197776 / 5198012 | 背景玻璃 |
| `PlayerLyricsViewModel#setCurrentHighlightedLine(I)V` | 5434408 | 行号（不是时间） |
| `PlayerLyricsViewModel#buildTimeRangeToLyricsMap(SongInfoPtr)V` | 5433652 | profile 里的 `buildIndex` |

## 附 B：布局/资源与 profile 索引

**布局**：`res/layout/fragment_player_lyrics_sheet.xml`（无变体）、`res/layout/fragment_player_main.xml`、
`res/layout/music_player.xml`（歌曲页，含 `id/artwork_image`）。

**id 解析**：`2131363638→id/lyrics_main_content`、`2131364121→id/queue_main_content`、
`2131364031→id/player_fragments_host`、`2131364037→id/player_sheet_container`、
`2131558993→layout/fragment_player_main`、`2131558992→layout/fragment_player_lyrics_sheet`。

**profile 已有可复用项**：`7.0.3-1626.json:3773`（`fragmentChrome.members.slide` =
`PlayerMainFragment$i#b(View,float)`）、`:3783`（`progress` = `$i#d(float)`）、
`:4609`（`fragmentLyricsRuntime`，含 `createView/destroyView/resume/controls`）、
`:2383`（`LYRICS_UI_RECYCLER_VIEW_METHOD=getRecyclerView`）。

**AMLL 侧接口**（WebView 桥要调的）：`packages/core/src/lyric-player/base/index.ts:611` `setLyricLines(lines, initialTime)`、
`:643` `setCurrentTime(time, isSeek)`、`:759` `rebuildLyricView(initialTime)`、
`:450` `setEnableBlur(boolean)`、`:1101` `setLinePosYSpringParams(params)`、
`:1162` `update(delta)`、`:1138` `pause()`、`:1148` `resume()`。
**组件自己没有 rAF 主循环**（`packages/core/src/lyric-player/dom/index.ts:224` 只提供 `update`），
所以「16 ms ticker」必须**同时**推 `setCurrentTime()` 与 `update(delta)` 两个调用，
不是只推时间。AMLL 侧的可驱动性、打包体积、许可结论见同目录 `11-amll-webview-drivability.md:7`（判定表）。
渲染器是 **DOM**（`packages/core/src/lyric-player/dom/`，canvas 只用于
`utils/line-balancer.ts` 的文本测量）→ **要写原生 View 等于重写布局层，WebView 是唯一现实路径**。
