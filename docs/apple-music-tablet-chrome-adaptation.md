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
2. 顶栏宽度按参考图比例推导（整条胶囊约占窗口 22.5%、每格约 4.5% → 1280dp 平板上约 58dp），当前取 `TOP_TAB_CELL_DP = 60`，仍是唯一调参点，需目视复核。
2.1 `GlassNavigation` 的「选中项用强调色」是**可选参数** `tintSelectedWithAccent`（默认 false）。原版手机/双栏栏只靠库的半透明拇指表达选中，因此**不得**默认开启；只有 iPad 顶栏传 true。`TabletChromeStructuralRegressionTest` 有回归断言守护这一点。
3. `peekHeight()` 仍沿用手机几何（56+16=72dp+inset），与原生 `miniplayer_height`=59dp 存在约 3dp 残差；未改动以免引入不可验证的偏差。
4. 命中胶囊后不再由宿主 sheet 处理拖拽，即**从胶囊上拉不再展开**（改为点击展开）——若希望两者都支持需再调整拦截条件。
5. ~~若会话挂载时播放器已处于展开态，`topSlide` 在首个 slide 回调前仍为 0。~~ 已修：渲染改用 `effectiveSlide()` —— 当尚未收到任何 slide 回调且 `isCollapsed` 为假时按「已展开」处理，顶栏直接停在隐藏态、迷你胶囊同样隐藏，不会盖在完整播放器上。

## 6.1 未在设备上验证的已知风险

1. **隐藏原生 tabs 帧**使 `navFrame.isShown == false`，基类据此计算底部占用为 0，迷你播放器的位置 / peek / 滚动避让可能改变。
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
