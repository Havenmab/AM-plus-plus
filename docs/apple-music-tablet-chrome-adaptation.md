# 平板 iPad 风格界面（行为变更记录）

> 本文记录一次**行为变更**（新增能力），不是版本适配：`docs/apple-music-target-adaptation.md` §1.1 要求两者分开记录。
> 分支 `feat/tablet-ipad-chrome`；目标宿主 Apple Music 6.5.3 (1599)；证据来自本次逆向（`re-tablet-topnav.md`、`re-tablet-miniplayer.md`）与主代理的 aapt2/DEX 取证。

## 1. 需求与产品决策

在**官方平板**上增加一种可选界面风格：顶部导航胶囊（主页 / 新发现 / 广播 / 资料库 用文字，搜索用图标；**不还原**最左侧的侧边栏切换按钮）+ 底部迷你播放器胶囊（随机 / 上一曲 / 播放暂停 / 下一曲 / 循环三态、中部封面+歌曲名+艺人名、右侧歌词按钮点击展开完整播放器、右侧队列按钮）。

用户确认的四项决策：

| # | 决策 |
| --- | --- |
| 1 | iPad 风格下**隐藏**宿主原生底部导航标签栏，底部只留迷你播放器 |
| 2 | **横屏 + 竖屏都生效**（官方平板） |
| 3 | 该风格是「液态玻璃底栏」的**子选项**：必须先开启液态玻璃底栏 |
| 4 | 队列/历史列表做不出来时**降级**为调用宿主原生队列界面 |

**硬约束**：原作者已完成的手机与平板液态玻璃适配必须原样保留；默认风格为原作者风格，未选择新风格时全部既有行为不变。

## 2. 宿主证据（6.5.3 / 1599）

### 2.1 顶部导航：宿主本来没有，但菜单可直接复用

- 宿主没有全局顶栏；顶层导航是 Material `BottomNavigationView`（`id/bottom_navigation`，`app:menu = menu/bottom_navigation_menu_multiply`）。平板只是把根 id 从 `bottom_navigation_root_stacked` 换成 `bottom_navigation_root_flat`，**位置仍在底部**；无 `NavigationRail`，无侧边栏切换按钮（未找到）。
- 菜单**恰好就是需求中的 5 项**，顺序一致：

| 宿主 item id | 标题 | 需求对应 |
| --- | --- | --- |
| `id/action_listen_now` | `string/listen_now` | 主页 |
| `id/action_browse` | `string/new_tab_name` | 新发现 |
| `id/action_multiply_radio` | `string/radio` | 广播 |
| `id/action_library` | `string/setting_library` | 资料库 |
| `id/search_fragment` | `string/search` | 搜索（图标） |

- 模块**已经在用同一机制**：`PhoneGlassSession.refreshMenu/selectTab` 读 `getMenu()` / `getSelectedItemId()`，点击回写 `setSelectedItemId(I)`。因此顶栏不需要新的导航缝。
- `STACKED_NAVIGATION_MENU`（`Kd.b`，6.5.3）的真实身份是被混淆的 Material `BottomNavigationMenuView`（`BottomNavigationView` 的子 View），**不是顶栏、也不是平板专属**；`Hd.b` 在 1599 不存在。
- 平板布局有 `layout-w640dp-land-v13/bottom_navigation.xml` 与 `layout-w640dp-port-v13/bottom_navigation.xml` 两份，横竖屏各一。

### 2.2 迷你播放器：原生只有 2 个按钮

`res/layout/mini_player.xml`：封面（`NowPlayingContentView`）、歌曲名、艺人名都在，但按钮行只有 `mini_player_play_btn`、`mini_player_next_btn` 两个图标 —— **随机、上一曲、循环在原生命中不存在**，必须由模块自绘并调用宿主命令。

### 2.3 播放命令面：接口未被混淆

`Lcom/apple/android/music/playback/controller/MediaPlayerController;`（PUBLIC INTERFACE ABSTRACT）：

| 需求 | 方法 |
| --- | --- |
| 播放 / 暂停 | `play()V` / `pause()V` |
| 上一曲 / 下一曲 | `skipToPreviousItem()V` / `skipToNextItem()V` |
| 随机 | `setShuffleMode(I)V` / `getShuffleMode()I` |
| 循环 | `setRepeatMode(I)V` / `getRepeatMode()I` |
| 能力查询 | `canSetShuffleMode()Z` / `canSetRepeatMode()Z` / `canSkipToNextItem()Z` / `canSkipToPreviousItem()Z` |
| 播放状态 | `getPlaybackState()I` |
| 当前项 | `getCurrentItem()Lcom/apple/android/music/playback/model/PlayerQueueItem;` |
| 队列 | `getQueueItems()Ljava/util/List;` / `getAllQueueItems()Ljava/util/List;` |
| 监听 | `addListener(...)V` / `removeListener(...)V` |

常量：`PlaybackRepeatMode` OFF=0 / ONE=1 / ALL=2；`PlaybackShuffleMode` OFF=0 / SONGS=1 / INVALID=-1；`PlaybackState` STOPPED=0 / PLAYING=1 / PAUSED=2。
循环三态切换映射：`0 → 2 → 1 → 0`。

### 2.4 活动实例捕获链与面板缝

- 捕获：`MediaPlaybackService#onCreate()V` after-hook → 字段 `MediaPlaybackService#N : player.m0` → 字段 `m0#h : MediaPlayerController`。（`MediaPlaybackService` 无独立 `android:process`。）
- 展开完整播放器：`PlayerActivity#v1(Lcom/apple/android/music/common/activity/PlayerActivity$p;)V`，传枚举常量 `EXPAND_PLAYER`。
- 歌词 / 队列：`player.fragment.v0#F1(Lcom/apple/android/music/player/fragment/v0$n;Landroid/os/Bundle;)V`，枚举常量 `LYRICS` / `QUEUE`（队列带 Bundle 布尔键 `utils.E#o`）。

## 3. 新增符号档案

全部为 6.5.3 (1599) 精确钉住：`ProfilePolicy.EXACT_REQUIRED` + `fallbackOwner = { false }`，6.5.0/6.5.1/6.5.2 与未知版本报告 `Missing`（不猜）。未混淆的库/模型方法走 `NO_PROFILE` + 精确描述符。

| 符号 | owner（6.5.3） | 证据 |
| --- | --- | --- |
| `TabletMediaPlaybackService` | `com.apple.android.music.player.MediaPlaybackService` | 类存在，父类 `android.app.Service` |
| `TabletMediaPlaybackServiceOnCreate` | 同上，`onCreate` | `onCreate()V` |
| `TabletMediaPlaybackServiceControllerField` | 同上，字段 `N` | 类型 `player.m0`；`onCreate` 内 `new m0(service,handler)`→`iput N` |
| `TabletMediaPlaybackServiceControllerHolder` | `com.apple.android.music.player.m0` | 类存在 |
| `TabletMediaPlaybackServiceControllerHolderField` | `m0`，字段 `h` | 类型 `MediaPlayerController`；`m0.<init>` 调 `createLocalController` 后 `iput h` |
| `TabletPlayerActivityExpandPlayer` | `PlayerActivity`，`v1` | `(PlayerActivity$p)V` |
| `TabletPlayerActivityPlayerFragment` | `PlayerActivity`，`f1` | `()Lcom/apple/android/music/player/fragment/v0;` |
| `TabletQueueBundleArgKeyField` | `com.apple.android.music.utils.E`，静态字段 `o` | 队列 Bundle 布尔键 |
| `TabletMediaPlayerController` + 16 个方法 | `...playback.controller.MediaPlayerController` | 精确描述符（接口未混淆） |
| `TabletPlayerQueueItemGetItem` / `TabletPlayerMediaItemGetTitle` / `GetArtistName` | `playback.model.*` | 精确描述符 |
| 面板方法 | 复用既有 `AppleMusicSymbols.PlayerControllerSelectPane`（`v0#F1`） | — |

## 4. 实现结构

| 文件 | 职责 |
| --- | --- |
| `config/TabletChromeStyle.kt` | 风格枚举 `AUTHOR`（默认）/ `IPAD`，未知值回落 `AUTHOR` |
| `hook/TabletChromeCommands.kt` | 命令面接口（播放/随机/循环/歌曲曲目文本/展开/歌词/队列/监听） |
| `hook/AppleMusicTabletChromeTarget.kt` | 目标适配：解析上述符号、安装捕获 hook、实现命令面；缺失即 `DEGRADED` |
| `hook/TabletChromeFeature.kt` | 门控：液态玻璃开关 + iPad 风格 + Android 13 + 目标是否可用 |
| `hook/TabletChromeSession.kt` | 会话：顶栏胶囊挂载、隐藏原生底栏、顶部占位、触摸命中、横竖屏 |
| `hook/TabletChromeLayoutPolicy.kt` | 纯策略（可单测）：顶栏高度/占位/命中 |
| `glass/…/TopBarGeometry.kt`、`GlassTopNavigation.kt`、`GlassMiniPlayer.kt` | 材质组件（胶囊随内容收窄；搜索为图标；迷你播放器 7 个控件自绘） |
| `PhoneGlassRuntime.createSession` | 路由：iPad 分支先于双栏分支；双栏分支显式排除 iPad 风格（两套玻璃永不同时写几何） |
| `FeatureInstallation` | 登记新能力，资源期发现仍由既有玻璃 hook 完成 |

## 5. 与原作者风格的关系

- 默认 `tablet_chrome_style = author`：既有手机/平板液态玻璃路径**字节不变**，`GlassPolicy`、`PhoneGlassSession`、`TabletDualPaneGlassSession` 不改资格、不改几何。
- 选择 `ipad` 时：`PhoneGlassRuntime` 创建新会话，双栏玻璃会话不再创建；`TabletGlassChrome` 单写者仲裁由新会话接管，退出时恢复原生。
- 手机的 `PhoneGlassSession` 分支完全不受影响（iPad 分支的平板判定在手机上不成立）。

## 6. 验证状态

| 项 | 结果 |
| --- | --- |
| 静态符号校验（`scripts/verify-host-profile.py --glass --tablet-chrome`，针对用户提供的 XAPK） | **93 checks / 1 failure**：新增符号断言 5 条 + **新增资源断言 25 条**（11 drawable、9 id、`color_primary`、2 dimen、2 bool，全部按 name→type 解析通过）。唯一失败是既有的 `LGi/A$a; h`（`feat/region-port` 地区替换遗留，与本次无关）。资源检查只证明「名字仍以该类型存在」，不证明取值、配置限定符或运行时 `getIdentifier()` 结果 |
| 结构回归（`TabletChromeStructuralRegressionTest`） | 已加：路由顺序与互斥、门控、单键无迁移、能力接口 |
| JVM 全量测试 | **896 tests / 0 failures / 0 errors**（基线 871 + 本能力 25：`TopBarGeometryTest` 7、`TabletChromeStyleTest` 6、`AppleMusicTabletChromeTargetTest` 3、`TabletChromeMiniPlayerPolicyTest` 5、`TabletChromeStructuralRegressionTest` 4） |
| `verify-glass-reference.py` | PASS：33 个 `backdrop/src` 文件哈希一致，1 个已声明补丁命中（本次未改 `backdrop`） |
| `git diff --check` | 无空白错误 |
| lint（`:app:lintDebug :app:lintVitalRelease :glass:lintDebug`） | **PASS（GitHub Actions PR 运行）**：`./gradlew test :app:lintDebug :app:lintVitalRelease :glass:lintDebug :app:assembleDebug --no-daemon` 全部成功，并产出 `AM-plus-plus-debug` APK。本机离线首次运行曾因缺少缓存的 `io.github.libxposed:interface:102.0.0`（仅 `debugLintChecksClasspath` 需要）解析失败——环境问题，非代码问题 |
| `push` 触发的 release 路径 | 失败于 `Verify release signature`：fork 上未配置 `AMPP_RELEASE_*` 签名 secrets，release APK 落回 debug 证书时工作流按设计拒绝。与本次代码无关；验证以 PR 运行为准 |
| 真机验收 | **未做**（无设备） |

## 6.2 真机首轮验收与修复（2026-10-01）

首轮真机（平板）暴露的缺陷与修复：

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| 顶栏**完全没有玻璃** | 会话自建 `ViewBackdrop` 后立刻 `setCaptureEnabled(false)`，`ready` 永远为假，激活门控不过 → 胶囊始终 `GONE` | 改为原版做法：`ViewBackdrop(navigation_host_group).also { topBackdrop = it; it.start() }`，不做 capture 门控 |
| 顶栏下方**莫名空白且不随滚动** | 会话给内容根加了顶部 padding 预留空间，胶囊悬在空白上（既无内容可折射，也挡不住内容） | 删除全部顶部 padding 及其快照/恢复，顶栏改为**纯悬浮覆盖**，页面内容从其下方滚过 |
| 按压无灵动触动、选中项是**红色实心块**、文字不变色 | 自绘 `GlassTopNavigation` 绕开了库的 `LiquidBottomTabs`（拖动拇指/按压动画都在那里），且强调色被写死成 `Color.Red` | 顶栏改用 `GlassNavigation`（库提供半透明选中遮罩 + 按压/灵动 + 拖拽）；强调色取宿主 `color_primary`（回退 `0xFFFA233B`）；`GlassNavigation` 新增按选中态着色：选中项的**文字与图标**用强调色 |
| 进入完整播放器后**顶栏仍在** | 顶栏未订阅 slide 进度 | 覆写 `onSlide`，`1 - smoothstep(0, 0.35, progress)` 淡出并在阈值后 `GONE`（迷你胶囊 0.35→0.6 淡出） |
| 迷你播放器**图标不对、间距差** | 控件图标是模块自绘矢量 | 改用宿主真实资源（按资源名解析）：`ic_nowplaying_shuffle/shuffleon`、`ic_nowplaying_mp_rewind`、`ic_nowplaying_mp_play/mp_pause`、`ic_nowplaying_mp_fforward`、`ic_nowplaying_repeat/repeaton/repeatoneon`、`selector_nowplaying_lyrics`、`selector_nowplaying_queue`；间距统一为 8dp 外插值 + 4dp 均匀间隔 |
| 迷你播放器**封面缺失** | 原生 mini 子树被 `GONE`，且封面查找路径不对 | 容器取 `mini_player_content → video_surface`；优先 `getArtworkView()`，否则取第一个带 drawable 的 `ImageView`；隐藏方式改为 `INVISIBLE + alpha 0`（不用 `GONE`），保证原生仍更新封面 |
| 歌词/播放列表**点了没反应**、点本体**不能展开**（只能上拉） | 胶囊的 DOWN 被宿主 `StaticCollapsedBottomSheetBehavior` 吃掉，Compose 收不到事件 | 命中胶囊的 DOWN 时 `shouldBypassPlayerIntercept` 返回 true；在 `player_sheet_container`/`player_root` 上把 DOWN/MOVE/UP 平移转发给 Compose 胶囊；胶囊本体 `onExpand` → `PlayerActivity#v1(EXPAND_PLAYER)` |
| 收起时与底栏**对齐偏差** | 迷你胶囊没有对齐原生折叠座位 | 折叠态下读取原生 mini root 在 `player_sheet_container` 内的偏移，作为 `topMargin`（`miniPlayerSeatPx`）；`miniPlayerTopPx` 保留为 0 调参点 |

修复后：`test` **896 / 0 失败**。仍未在真机验证的项见 §6.1 与 §6.3。

## 6.3 修复后仍待真机确认

1. 顶栏玻璃与按压实效、选中项强调色（本轮修复的目标，需复测）。
2. 顶栏宽度按参考图比例推导：**每格占窗口 4.5%**（整条约 22.5% ÷ 5 格），夹在 `TOP_TAB_CELL_MIN_DP = 44` .. `TOP_TAB_CELL_MAX_DP = 58`。窄平板等比收窄，不再是固定 dp。
2.1 `GlassNavigation` 的「选中项用强调色」是**可选参数** `tintSelectedWithAccent`（默认 false）。原版手机/双栏栏只靠库的半透明拇指表达选中，因此**不得**默认开启；只有 iPad 顶栏传 true。`TabletChromeStructuralRegressionTest` 有回归断言守护这一点。
2.2 `cleanSelectionMask`（默认 false，仅 iPad 顶栏传 true）：库会把标签内容**录进拇指折射用的图层**（`alpha(0f)` 在 `layerBackdrop` 之前，只淡化合成输出，录进图层的内容仍是实心），于是拇指的 `lens` 把这份拷贝位移+色散成重影。开启后不再录那一遍、标签在拇指之后只画一次；lens/高亮/阴影/按压动画不变。
3. `peekHeight()`：删除自绘胶囊、不再 `GONE` miniRoot 之后，`miniVisible == true`，基类恢复按 mini 高度测量座位与 peek —— 本节此前的 3dp 残差结论已不再适用，需真机复核。
4. 命中胶囊后不再由宿主 sheet 处理拖拽，即**从胶囊上拉不再展开**（改为点击展开）——若希望两者都支持需再调整拦截条件。
5. ~~若会话挂载时播放器已处于展开态，`topSlide` 在首个 slide 回调前仍为 0。~~ 已修：渲染改用 `effectiveSlide()`。
6. **设置/账户页顶栏作用域**：判据取宿主自身信号 —— `settings.fragment.p.onStart()` → `MainContentActivity.Q1(0, true)` 会把 `bottom_navigation_root_flat` 置 GONE，回到标签页的 `common.fragment.a.onStart()` 再恢复；顶栏因此跟随 `hostRoot.isShown`。已知边角：资料库标签页在「加入播放列表」会话中宿主同样会隐藏该根（`LibraryComposeContentFragment.shouldHideBottomNav()`），此时顶栏一并隐藏，与宿主自身行为一致。
7. **迷你播放器连贯形变**：已按原版机制改造 —— 不再自绘固定尺寸胶囊，而是**重皮宿主那唯一会形变的玻璃表面**（`miniGlass`），几何/座位/alpha/可见性全部回到原版 `updateTransition` 驱动；`GlassMiniPlayer` 新增默认 `expansion`，圆角按 `NativeLiquidButton` 的 `Capsule()`→24dp 插值。**未完成**：平板封面飞入的原点校正（原 `TabletDualPaneGlassSession.alignNativeArtworkStart`）需要把该校正在 `PhoneGlassSession` 提为 `protected open` 并改 `PhoneGlassRuntime` 的广播，超出本次改动范围，封面原点可能有轻微偏差。
8. 待真机判定的两个前提（静态读代码无法确定）：平板下 `player_sheet_container` 是否等于 `mini_player`（若是，基类的几何形变整段被跳过，只剩淡出）；以及 `mini_player_content` 被置 INVISIBLE 后宿主是否仍刷新封面 drawable。

## 6.4 第三轮真机反馈与修复

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| 迷你播放器**下方多出一片空白** | 重皮原版形变面后 `miniVisible` 恢复为 true，而 `geometry` 仍带底部标签栏的 `navHeightDp=56` 与 `gapDp=8`，`GlassPolicy.occupiedHeight` 于是又为「已不在底部的标签栏」预留高度 | 我们的 `geometry` 覆盖改为 `navHeightDp=0 / gapDp=0`；并覆写 `peekHeight()` 去掉宿主 `shadow_height`（本会话不渲染那条底栏 scrim） |
| 顶栏**太厚**（用户澄清：横向长度合适，是纵向） | 直接复用了宿主 `dimen/navigation_tabs_height`(56dp) —— 那是**底部**标签栏的高度 | 新增 `TabletChromeLayoutPolicy.TOP_BAR_HEIGHT_DP = 44`；按参考图实测约 42–43dp、且等于 Apple 标称 44pt |
| 顶栏按压时**玻璃扭曲效果消失** | 开启 `cleanSelectionMask` 后跳过了文件里唯一的 `layerBackdrop` 录制，`LayerBackdrop` 走到 `layerCoordinates == null` 提前返回，拇指 `lens` 只折射到裸的 ViewBackdrop（顶栏后方是白底）→ 看起来是平的灰块 | 可见面板改为 `exportedBackdrop = tabsBackdrop`：录制的是**面板自身的材质**（背景/模糊/容器色），**从不录制标签**；默认路径该参数为 null，手机/双栏材质逐字节不变 |
| 播放键是**红色** | 我把 play/pause 也映射成了 accent | 改为与相邻控件相同的 `foreground`；accent 只保留给真正「开启」的状态（随机开、循环非关） |
| **播放键要更大**、歌名/艺人要更大更粗 | 原来 play 与相邻同尺寸；13sp/11sp 偏小 | play 独立 `PlayControlSize = 48dp`（参考图 1.73×相邻）；标题 15sp、艺人 13sp，均 `FontWeight.Medium` |
| 字体要用 **AM 内置字体**（西文/标点 SF Pro，其余回落系统） | 之前用 Compose 默认字体 | `GlassMiniPlayer` 新增 `labelTypeface: android.graphics.Typeface? = null`；会话从宿主原生 `mini_player_title` 读取其 `Typeface` 传入，模块不自带字体 |

仍未完成：平板封面飞入原点校正（见 §6.3.7）。本轮验证：`test` 897/0，CI 全绿。

## 6.5 第四轮：顶栏回到库自身的路径（关键教训）

上一轮我用 `cleanSelectionMask` 去重影，结果把玻璃效果一起去掉了。回读上游库与原作者用法后确认：

| 事实 | 证据 |
| --- | --- |
| 上游 `LiquidBottomTabs` 是**示例组件**，材料/透镜/色散/按压全部**写死为绝对 dp**，**没有任何参数 API** | 上游 README 明示不提供高层组件；`effects { vibrancy(); blur(8dp); lens(24dp,24dp) }`、拇指 `lens(10dp·p, 14dp·p, chromaticAberration=true)`、按压 `lerp(1,1.2,p)` 皆为常量 |
| ⇒ 唯一能间接影响效果的外部量是**胶囊尺寸** | 透镜/挤压常量是绝对 dp，胶囊变矮它们就相对过大 |
| 原作者底栏参数：`panelHeight = 56dp`、全宽−2×16dp、**无**自定义 tint、**保留**单元格录制层 | `PhoneGlassSession.kt:286` + `GlassPolicy.NAV_HEIGHT_DP` |
| **那层录制正是折射源** | 拇指 `lens(..., chromaticAberration=true)` 采样 `tabsBackdrop`，而该层由 `.alpha(0f).layerBackdrop(...)` 那一行录制；`alpha(0f)` 在 `layerBackdrop` 之前，只淡化合成输出，录制内容仍是实心 |
| `cleanSelectionMask` 让透镜只能采样**平滑模糊面** ⇒ 必然平灰 | 平滑面没有高频边缘，位移与色散都无从显现 |

**结论与改动**：顶栏改回作者路径 —— 高度取 `GlassPolicy.NAV_HEIGHT_DP`(56dp)，不再传 `tintSelectedWithAccent`（组件自身的 `accentOverride` 已经给水珠下的单元格上色）与 `cleanSelectionMask`，只保留有意收窄的宽度。`TabletChromeStructuralRegressionTest` 现在**断言禁止**这两个开关再次被传、并断言复用作者的胶囊高度。

## 6.6 平板封面飞入原点校正（已完成）

原来只有 `TabletDualPaneGlassSession` 有该校正，且 `PhoneGlassRuntime` 通过 `as? TabletDualPaneGlassSession` **硬转**调用 —— iPad 会话是它的**兄弟**（都直接继承 `PhoneGlassSession`），因此永远拿不到，展开/收起时封面原点有偏差。现改为：把校正提升为 `GlassSession` 上的 seam（手机侧空实现、双栏实现体不变），运行时对每个活跃会话虚调用；iPad 会话按自己的**居中半宽座位**推导 Y 向校正量。

## 6.7 第五轮：静置纯白的真正根因，以及细胶囊下的正确做法

§6.4/§6.5 的结论需要修正：**「静置纯白、划一下才取色」不是参数问题**。

| 现象 | 真正根因 | 修复 |
| --- | --- | --- |
| 静置时玻璃一片纯白，手指划一下才开始取色 | `ViewBackdrop` 只在源视图**变脏/移动/尺寸变化**时重录；本会话把 `navFrame` 停掉后基类 `redirectedPadding` 为 0（`PhoneGlassSession.kt:745`），静态页面永不重排，于是**只录到最初一帧**并一直保留到滚动为止。手机底栏靠 `onPreDraw` 里的 `setCaptureEnabled` 反复重新武装，平板侧从未做 | 挂载时先关闭 capture，在 `updateTopChrome` 中当胶囊可见时重新武装，并每次武装强制补录 2 帧（`CAPTURE_SETTLE_RECORDS`），保证首帧绘制之后必有一次录制。**可见性不再依赖 `ready`**（那正是 §6.2「胶囊永久 GONE」的原因） |
| 重影（标签拷贝被水滴折射） | 录制层里含单元格 | `cleanSelectionMask` 语义修正为：**保留**录制行（折射源），但只录**面板材质本身**（`EmptyTabContent`，且不再 `blur()`），单元格只在最上层画一次。透镜作用于**顶栏后面那层有细节的页面**，而不是标签拷贝 |
| 细胶囊下折射糊成一片 | 上游的透镜/挤压常量是**绝对 dp**，胶囊变矮后相对过大 | 新增 `effectReferenceHeight`（默认 = `panelHeight`）与 `effectScale = panelHeight / effectReferenceHeight`（夹 0.25..4），按比例缩放挤压 4dp、面板透镜 24dp、按压位移 16dp、录制层透镜 24dp、拇指透镜 10/14dp、内阴影 8dp。平板传 44/56 → 0.786；**默认值为 1，手机/双栏逐字节不变** |
| 顶栏文字太小太细 | 顶栏复用了手机底栏的 11sp | `GlassNavigation` 新增 `tabLabelSize: TextUnit = 11sp`、`tabLabelWeight: FontWeight? = null`；平板传 **13sp + SemiBold**；每格宽度 `TOP_TAB_CELL_FRACTION .045→.05`、`MIN 44→48`、`MAX 58→62`（1280dp 上 5 格约 288→310dp） |
| 高度 | 用户明确要求**细** | `TabletChromeLayoutPolicy.TOP_CAPSULE_HEIGHT_DP = 44` 驱动视图高度、`panelHeight` 与度量刷新；座位不受影响（`capsuleTopMarginPx` 会抵消高度变化） |

调参点：`TOP_CAPSULE_HEIGHT_DP`、`TOP_TAB_LABEL_SIZE_SP`、`TOP_TAB_CELL_MIN/MAX_DP`、`TOP_TAB_CELL_FRACTION`、`effectReferenceHeight`。

## 6.1 未在设备上验证的已知风险

1. ~~**隐藏原生 tabs 帧**使 `navFrame.isShown == false`，基类据此计算底部占用为 0，迷你播放器的位置 / peek / 滚动避让可能改变。~~ 自绘胶囊与 `hideSeam(miniRoot)` 已删除，此条已不适用。
2. **隐藏 `mini_player_touch_panel`** 让基类 `miniVisible == false`，模块 peek 从约 123dp 缩到约 72dp+inset；胶囊按 `Gravity.TOP` 落到折叠带顶部，可能需要改为底部对齐（`TabletChromeLayoutPolicy.miniPlayerTopPx` 是唯一调节点）。
3. 原生 mini root 被 GONE 后，宿主是否仍刷新 `mini_player_content` 的封面（胶囊封面直接读该原生 View 的 drawable）。
4. 平板 flat holder 的 `BottomSheetBehavior` 拦截可能吃掉胶囊点击（本会话未覆写 `shouldBypassPlayerIntercept`）。
5. 状态栏 inset 在非 edge-to-edge 下是否重复计算。
6. 给 `navigation_host_group` 加顶部 padding 后，顶栏胶囊的背景采样源不再绘制到胶囊后方，可能退化为平面底色。
7. 展开完整播放器时顶栏不随 slide 隐藏（按 iPad 观感应属预期）。

## 7. 未验证项与限制

- **无真机**：顶栏位置/状态栏 inset、隐藏原生底栏后的播放器 peek 与拖拽几何、竖屏布局、玻璃观感均未在设备上验证。
- 队列/历史：队列**可达**（`getQueueItems`/`getAllQueueItems`/`getCurrentItem`），但**历史播放列表未找到任何存储**；按用户决策，队列按钮调用宿主原生队列界面，不自绘列表。
- 6.5.3 profile 中 `PLAYER_ACTIVITY_BEHAVIOR_FIELD` 钉的 `c1` 是死条目（该字段实为 `Llg/d$a;`），真正的 Behavior 字段是 `a1`；目前靠 `EXACT_PREFERRED` 结构回退兜住，未致故障。

## 8. 跨版本维护提示

1. 顶栏标签一律运行时读宿主 `Menu`，**不得硬编码**标题/图标/顺序；搜索项按 `resourceId("search_fragment","id")` 判定。
2. 平板判定优先读 `bool/multiply_tablet_layout_enabled`（w640dp，正是平板底栏布局的开关），回退 `bool/is_tablet`；**不要**用只覆盖 w600dp 的 `bool/isTablet`。
3. 捕获链（服务字段名 `N`、holderr 字段名 `h`）与面板枚举常量名（`EXPAND_PLAYER`/`LYRICS`/`QUEUE`）都是按名解析，跨版本必须重新取证。
4. 新增符号必须同步补 `scripts/verify-host-profile.py` 断言（本次已补 5 条）。

## 9. 2026-10-02：折射文字替换与切页主动采样

本轮目标以用户新提供的现状与 iPad 参考截图为准：保留水滴内的红色文字折射、边缘色散与页面采样；不实现侧边栏。前面各轮将问题归因于缩放参数或直接去掉标签采样的结论，不作为本轮的实现约束。

### 9.1 单一可见文字，而不是两份文字叠加

原路径把普通黑色标签画在面板上，又把录制行的红色标签通过透镜画上去。录制行并不等于最终画面应有两份文字：面板材质和录制行都可能有透明像素，导致黑色原字从红色折射字下方透出。

新增默认关闭的 `replaceContentUnderThumb`，仅 iPad 顶栏启用。普通标签单独绘制到一个小的离屏层，再用 `DstOut` 清除水滴覆盖的那一块；玻璃面板、背景和录制行不被清除。随后水滴继续采样包含标签的 `tabsBackdrop`：水滴外显示普通字，水滴内显示折射字，不把两份可见字叠在一起。遮罩复用水滴的实时浮点位置、RTL、按压膨胀和速度剪切，不能只清除“当前选中格”的固定矩形。两行标签在该模式下共享同一个缩放曲线，避免上一轮“原字固定、录制字另行放大”的配准差异。

不启用 `cleanSelectionMask`，不修改透镜常量、色散、玻璃模糊参数或上游渲染器。手机和原作者双栏会话不启用新开关；默认渲染路径保持原样。

轮廓直接复用 `Capsule().createOutline`，而不是用普通圆角矩形近似：Shapes 1.2.1 的默认胶囊是连续曲率路径。轮廓在 `drawWithCache` 中缓存，动画只更新绘制变换，不逐帧重建曲线路径。

### 9.2 切页后即使静止，也安排后续录制

原补录计数只在 `onPreDraw` 中检查时钟，没有投递定时回调。页面静止后若没有新的 traversal，200ms 到期并不会自动再绘制。另外，顶栏与 mini 持续可见时，切换 tab 不会触发重新武装。

改为纯 JVM 可测试的 `TabletChromeBackdropRefreshPolicy`：切换宿主选中项、布局变化、消费者重新可见或 mini 新出现时，安排约 0/80/320/960ms 的有限补录。每个时间点通过源 View 的 `postDelayed` 主动唤醒采样，不依赖用户滚动，也不是永久逐帧抓取。密集布局事件合并，强制录制至少间隔 64ms；延迟执行时不会在同一帧连续补齐多次录制。顶栏和 mini 仍共享同一个页面 backdrop；切后台、消费者隐藏和会话关闭时取消回调。

新增几何、调度与接线回归测试。JVM/CI 能验证坐标、时序、编译和默认路径隔离，不能替代真机 GPU 验收。安装后应重点检查：长按五项并慢拖、快速切页后不滚动、返回已缓存页面、数据稍后加载、顶栏隐藏时 mini 的玻璃、播放器展开/收起以及后台返回。

## 10. 底栏封面收起衔接（2026-10-02）

用户已确认顶栏修复，本轮不改顶栏或玻璃渲染器。截图中的大封面落在左侧播放按钮上，小封面却在按钮组右侧：宿主动画仍以隐藏的原生缩略图为目标，与居中胶囊的实际封面槽位不同，尺寸也不同。

本轮保留原作者的原生 artwork slide 回调，并借用其“识别到原生封面后不再让 fragments 额外淡入”的交接方式；原作者右下角双栏会话本身不改。iPad 会话在 Canvas 的布局回调读取真实槽位，通过 ComposeView 和 artwork 父容器的全局矩阵映射到同一坐标系，包含 GlassHostView 的出血边距、宿主父容器平移和滚动。位置和宽高同时校正，缩放时按原生 pivot 补偿，不猜运输按钮的固定宽度。

校正只在 slide 0–0.35 的 mini 材质段生效：收起端精确匹配当前槽位，smoothstep 衰减后在 0.35 完全接回宿主原始轨迹，完整播放器的位置、尺寸、玻璃几何与时序均保留。没有固定屏幕 Y 下限，也不依赖第一次必须收到 slide=0 的回调；这替代了 §6.6 曾声称完成、后来因展开态偏移而撤销的旧校正。

每次宿主回调前先还原上一次的原生变换，回调后缓存新的原生值。布局和 pre-draw 再校正时都从该缓存计算，不从已经校正的值继续累加。关闭会话还原变换；重建胶囊清除旧布局坐标。有效且可见的原生封面接管滑动期间的绘制，Compose 缩略图只停止画图、保留布局；完全收起后交回 Compose。缺少回调、槽位、有效尺寸或可见原生封面时不隐藏缩略图，也不强制 fragments alpha。

新增纯 JVM 几何/所有权测试与接线回归测试。真机需验证：首次展开后慢速下滑至底、反向拖回和取消、连续多次展开/收起、封面最终的位置和大小、展开态封面不贴底，以及横竖屏、切歌和缺失封面的回退。Android 编译、lint 和完整测试由分支 PR 的 Actions 验证；动画观感仍待设备反馈。
