# Apple Music 7.0.0-beta/1606 适配与用户验收

2026-10-03 转屏后续修补：v14 修复横屏转竖屏时临时右侧歌词首次测量为 1px 导致原生 karaoke 宽度初始化崩溃。竖屏为原生单栏、横屏恢复双栏，竖屏玻璃保留；986 项自动测试通过，最终交付包在连接的平板上通过 mini/展开 × 播放/暂停共 8 次方向切换。见 [平板转竖屏崩溃修复](applemusic-700-tablet-portrait-crash.md)。以下保留此前交付阶段记录。

## 当前交付状态（2026-10-03）

当前交付为 v12：完整 1606 Fragment 接入、手机/平板参考玻璃、原生双栏 SONG/QUEUE 与独立歌词、原生封面动画和动态封面均通过当前重构模块实现。7.0 手机和平板不再叠加模块的底部渐变模糊，保留原生渐变；旧版本的兼容代码仍保留。模块划分、依赖、配置 schema 15 和数据格式未改变。

用户已确认参考平板迁移、v10 mini 对齐和 v11 收起状态切换播放后展开终点修补正常。v12 顶栏阴影淡入淡出修补交付后获得用户正向反馈；本轮没有代理安装或新的 ADB 录屏，慢速/反向拖动及主题组合不逐项记为通过。

最终本地自动验证为 984 项测试（失败/错误/跳过均为 0）、Debug/Release vital Lint、Release 构建、架构/冻结 profile/渲染参考检查和 262 项原包契约检查。部分未变任务使用 Gradle 缓存。签名 v12 APK 的 SHA-256 为 `e21c3c89c11e3990aed2bff3e6c2f251659c56317641ac9375b6a19e07c7b4ba`，详见 [顶栏阴影修补](applemusic-700-tablet-navigation-shadow.md)。原生动画及用户验收见 [封面对齐](applemusic-700-native-artwork-alignment.md)和[展开终点](applemusic-700-native-artwork-endpoint.md)。

完整手机/平板功能矩阵、五轮性能量测和 1583/1586 原始包静态重验仍待补充；此前暂停的手机长后台小封面分析没有生产修补或归因结论。以下按阶段保留此前交付与验证记录，不代表所有条目均已实测通过。

2026-10-03 v6 问题修复：手机前台恢复时补接原生材质 alpha／背景所有权和 View 重绑定；歌名修正补齐精确1606换歌／状态及当前歌曲读取契约。实现 `fc5c086`、951项测试／253项原包契约通过，已交付签名 v6。见 [前台与歌名修正修复](applemusic-700-foreground-metadata-fix.md)。此前记录的 metadata bootstrap 缺口已在代码与原包静态验证层修复，真实查询与界面结果仍待设备验收。

2026-10-03 手机后续迁移：按参考分支的完整共享手机会话接入，保持重构模块，禁用 AM++ 平板底部渐变层、保留原生渐变。详见 [手机参考迁移](applemusic-700-phone-reference-port.md)。实现 `25db0b6`、942项测试与242项原包契约通过，已交付签名 v5；当前没有 ADB 设备，手机真机效果待验收。此前已验收的平板 v4 包保留。

日期：2026-10-02。实现分支：`codex/applemusic-700-adaptation`；代码基线：`main/d3637ed`。本次只为 `com.apple.android.music / 7.0.0-beta / 1606` 启用接入，供用户测试；未知 beta、正式版和错误 tuple 不放行。**首包曾启动崩溃，已修复并通过平板冷启动检查；完整参考平板迁移已由用户确认通过，广泛设备矩阵仍待验收。** 此记录不代表公开发布。

实现提交：`25b5cae`（profile/歌词契约）、`461a570`（settings2/蜂窝）、`fb5e4ad`（双栏/元数据）、`5f86368`（初版 Fragment 玻璃）、`833904f`（启动崩溃修复）、`2b5c6de`（参考平板玻璃与拖动/切页/渐隐修正）。原始包、反编译资料、日志、签名秘密和生成 APK 均未提交 Git。

## 安装包和交付物

| 项目 | 核验结果 |
|---|---|
| 宿主身份 | base 二进制 Manifest：`com.apple.android.music / 7.0.0-beta / 1606` |
| 用户提供 APKS | `Apple Music_7.0.0-beta_com.apple.android.music.apks`，含 base、arm64、xxhdpi |
| APKS SHA-256 | `e527fb763525ac24414aed48f956f9b6ffaaff03ade27e6fe5e767f1e9adf4f8` |
| base SHA-256 | `3d09687ed752e48e73f2c72524e18cffff69c66b523096c2e97c8f9135980603` |
| 模块 APK | `.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-reference-full-v4.apk` |
| 模块版本与大小 | 沿用 `dev.amenhancer.module / 1.6.2 / 112`；9,716,716 bytes |
| 模块 SHA-256 | `62a4fb6bf00693bfb17cb381fea2bceaefaeabbb20d2555a8e59eb9330d60672` |
| 签名 | APK v2 验证通过，当前 AM Plus Plus Release / RSA4096 |
| 证书 SHA-256 | `6ed7800187c3513562334319723498fd01772396b9848d4aeba50622910be0d4` |
| 打包范围 | 五个精确 profile 和 index；HookEntry、scope、许可资产存在；历史 research 夹具未打包 |

签名、依赖、工具链、版本号、配置 schema 15、配置键、资产 ID/目录、ZIP 与数据库格式均沿用项目现状。1583/1586/1599 支持范围及冻结 profile 保留；1580 仍仅作参考。

## 接入证据和实现

| 功能链路 | 旧版 → 1606 的关键接入与活跃路径 |
|---|---|
| 首页和会话 | Activity → `MainActivity / MusicContentFragment`；以 Fragment root 身份管理玻璃。生命周期和失败回调只关闭所属 root |
| 导航 | 底栏 → 手机原生底导航或平板顶部 Compose；`r1()` 返回 `common.NavTabsViewModel`，`g1(y,long,pi.l,z0.m,int)` 提供原生 tab 回调，状态来自原生模型 |
| mini | 用不受模块按压形变影响的 `mini_player_blur` 作为折叠锚点，窗口坐标支持 sibling；600/675/735/830dp 的资源逐档取证。`ViewGroup.dispatchTouchEvent` 观察按压，所有按钮仍由原生派发 |
| 播放器 | `PlayerMainFragment$i.b(View,float)` 按原生 sheet 身份路由；`d(float)` 同步直接过渡。挂载前进度缓存、事件驱动透明度，缓存字段只用于初始恢复，避免覆盖渐隐进度 |
| 设置 | settings → `settings2.fragment.SettingsFragment / SettingsViewModel.getPreferenceItems()`；宿主类加载器构造唯一原生分类和 action，原生 `pi.a` 回调返回宿主 Unit，继续打开完整 AM++ 设置页面 |
| 歌词安装 | `I2` → `w2(SongInfoPtr)`；原地址、存活、sections、Adam ID 和后台解析规则保留 |
| 换歌和重应用 | `o2(v3.v,…)` → `b2(z3.w,BaseContentItem,e$c)`；实际当前歌曲字段为继承的 `fragment.l.c`，不是次级封面状态 `e.b0`。原生尾部 w2 与模块重应用去重 |
| 当前元数据 | `player.f.D(z3.w)` → `player.P.b(z3.w)`；完整 owner/参数/返回/静态性验证，拒绝旧版同形方法 |
| CJK | `player.A.V(A$a,int,int,int,boolean)`、`utils.L0$a.a(CharSequence,Set)`；前景歌词 binding 字段改为 Z，原单词作用域和清理保留 |
| 双栏 | 初始化/切页 → `j1(BagConfig)` / `s1(State,Bundle)`；左侧仍是原生 SONG/QUEUE，固定右 host 放独立歌词 Fragment；manager `E.Q/E`、transaction `a.m/e/f/h` 按实际描述符接入 |
| 右歌词布局 | 当前 profile 的 `n0 / Z / f0 / l0 / E0,F0`；字号在行/词 XML 初次绑定前应用，`W1()` 后校正锚点，N1 抑制重复控制条。7.0 渐变只读取本 tuple 的 W/a0/b0/c0 字段组合 |
| 元数据展示 | 37 个 hook 点、65 个精确目标；队列 `ea.a.w/k/v`，资料库 `LibraryMainContentEpoxyController.buildModels(I,…,Q7.e)`，主页 artwork `common.L.s(CollectionItemView)` |
| 菜单、媒体库和目录请求 | 操作菜单 `player.h1.G0`（`q8.na` 调用）；媒体转换 `C9.F.b / C6.a.n,b`；原生 uncached `getMediaApi` → `w9.Q.F`，storefront 实例字段 t。请求本地化 `w9.Q#q0`；内容 HTTP `rb.a#a`（HLE 的 cookie/主客户端拦截器）与 amp-api `y9.d#a`（两条链路必须分属不同客户端，否则 amp-api 上更靠后的 `y9.a#a` 会把 executor 已按原地区改写的地名再次改回配置地区）；目录执行器 `x9.E/x9.u1/x9.t` 六个 7 参方法 |
| 蜂窝 | settings2 的数据分类本身受账户条件控制，7.0 已无旧 SIM 显示门禁；保留默认关闭，仅按配置修改 `FuseConnectivityChecker.isCellularAvailable()`，不绕过登录条件 |
| Editorial / DPI | Editorial `player.j1.e(Song,float,Flavor[])` 保留平板横屏限定；DPI 使用现有仅宿主冷启动与配置变化实现 |

1606 的 HLE 解析只接受本 tuple 的候选，不跨旧 profile 寻找类、方法或 DexKit 修复成员。Indexed 方法和字段使用完整描述符。旧 HLE 的兼容回退及候选顺序保持原状。

1606 的地区替换目标由该版本 DEX 推导后加入 profile，并经 `scripts/verify-host-profile.py` 对真实 7.0 原包做 262 项描述符校验（0 失败）。这一步只证明类/方法/字段与拦截器归属仍成立，没有证明五个改写层在设备上真的改写了请求：**本次移植没有真机验证，7.0 仅为描述符与结构通过**，地区切换效果、账号回退与加载速度仍待验收。

## 新平板玻璃

- 保留顶部导航、原生抽屉和独立底部 mini；没有使用旧 `TabletDualPaneGlassSession` 的并排胶囊或紧凑 mini 重排。
- 按用户最新要求，以 `codex/applemusic-7-tablet-glass / 595581b` 的渲染和交互为基准；保持现有 `host-applemusic / host-api / app / glass` 模块结构，没有合并旧 bootstrap、配置或设置实现。
- 顶栏使用参考分支的 `GlassNavigationStyle.TabletLabels` 和 `LiquidBottomTabs`，按原生 `top_nav_blur` 胶囊边界挂载；原生标签、搜索图标、黑/白前景和 `color_primary` 强调色同步。固定抽屉按钮独立于透镜区域，执行原生抽屉动作。
- 拖动读取当前测量的 cell 宽度与方向，避免 1px 初始挂载捕获的宽度导致小幅滑动跳两端；取消和菜单替换释放按压。选中项变化保持已绘制玻璃，旧 Compose 帧不能撤销新就绪状态；真正的菜单替换仍等待绘制。
- mini 保留各宽度档位原生封面、文字和全部按钮。沿用参考分支：0～35% 放大至播放器 80% 宽度、35～60% 平滑渐隐并在透明后 GONE、60～85% 显示运动层；原生背景和内容层按相同进度交接。纵向展开及按钮动作由原生派发，双栏关闭时仍可使用玻璃。
- 手机使用同一 Fragment 会话和材质能力，位置仍来自手机的原生底导航及 mini。7.0 不套用旧底栏高度/补偿几何；这些旧配置保留用于 6.5.x。背景模糊参数在新版生效。
- 普通 sibling 容器承载模块视图，避免 FragmentContainerView 的任意子视图限制。背景取 `blur_target`，排除玻璃自身；完成测量、采样和绘制后才隐藏对应原生材质，失败恢复原生属性。
- 图标与反射成员缓存；动画布局止于模块视图子树，隐藏或后台时停止采样和几何更新；关闭只恢复模块仍拥有的属性。
- AM++ 入口只通过原生 settings2 列表安装，禁用本页面族的标题扫描/View fallback，避免匹配侧栏“设置”并在左下角插入模块行。

这些是源码实现与自动策略验证范围；视觉效果、抽屉实际命中区域、过渡连续性、帧耗时仍需下面的设备验收。

## 已执行自动验证

| 检查 | 结果 |
|---|---|
| 全模块 `test` | **939 项，0 失败、0 错误、0 跳过**；core278、app240、glass22、host-api30、host-applemusic367、hook-runtime2。Gradle 未变化任务使用已有结果 |
| 新行为覆盖 | 精确 tuple、完整/错误描述符、同形旧方法拒绝、当前 item 与次级状态分离、w2/b2 去重与过期重应用、设置列表去重/失败/销毁、cellular 条件、双栏事务恢复/歌词 fade、封面属性所有权、导航测量/命中/取消、mini motion/属性恢复、目录查询/生命周期与优先级 |
| Debug Lint | app、glass、host-applemusic 通过；保留项目既有 warnings，不扩大 baseline。模块玻璃 island 的 requestLayout 有局部注释说明的 MissingSuperCall suppression，避免动画传播至宿主布局 |
| Release | app `lintVitalRelease` 和 `assembleRelease` 通过；v2 签名与打包内容核验通过 |
| 1606 宿主静态 | **237 项，0 失败**，二进制 Manifest、全部 split DEX、方法、原生字段类型及布局；包含双栏生命周期/事务、lyrics listener/metrics、MarginLayoutParams、抽屉、库标题、d(F) 和 BlurView.draw 契约 |
| 1599 宿主静态 | 51 项，0 失败，包含玻璃入口 |
| Profile / 架构 / 玻璃参考 | 五个 profile、314 个 HLE 目标；旧冻结映射与候选顺序不变；模块边界通过；32 个上游文件和 2 个已声明补丁 hash 通过 |

复现命令（Windows 使用 pwsh7；此工作区按 RTK 规则执行）：

```powershell
rtk proxy .\gradlew.bat --offline test :app:lintDebug :app:lintVitalRelease :glass:lintDebug :host-applemusic:lintDebug :app:assembleRelease
rtk proxy python scripts/verify-profile-data.py
rtk proxy python scripts/verify-architecture.py
rtk proxy python scripts/verify-glass-reference.py
rtk proxy python scripts/verify-host-profile.py 'Apple Music_7.0.0-beta_com.apple.android.music.apks' --glass
# 尚未启用的 profile 也可显式验证，身份仍由 base 二进制 Manifest 决定：
rtk proxy python scripts/verify-host-profile.py PACKAGE --profile PATH_TO_PROFILE --glass
```

最新本地构建日志位于 `.scratch/applemusic-700-beta-analysis/reference-glass-v3-final-build.log`、`reference-glass-v3-package-build.log` 和 `reference-glass-v3-artifact-build.log`。

## 用户测试顺序与逐项状态

用户启动崩溃后授权 ADB，已在 OPD2413 / Android16/API36 上验证启动修复：首次及两次横屏冷启动、一次竖屏冷启动和后台返回均存活；没有清除宿主数据。v2 实际播放器截图中右侧歌词可见，但拖动、顶栏瞬间恢复原版和渐隐仍被用户否定。随后设备断开，用户要求直接复刻参考渐隐，**v3 尚未真机验收**。安装 v3 后确认作用域包含 Apple Music，强制停止并重新打开，优先测试长按小幅左右拖动、切页瞬间顶栏和 mini 展开/反向收起。

| 顺序 | 场景与通过条件 | 状态 |
|---|---|---|
| 1 设置与基础播放 | 原生设置出现唯一“AM++ 模块设置”；打开/保存/返回，重开和旋转不重复；播放流程正常 | 待用户验证 |
| 2 玻璃单开、双栏关闭 | 无 mini/有 mini，顶部每个 tab 点击/重选/横向拖动，按压/取消；抽屉正常。静止导航不得消失；mini 每个按钮和菜单可操作 | 待用户验证 |
| 3 播放器过渡 | 单栏展开/收起、中途反向拖动、播放/暂停和封面暂停缩放，材质随原生边界变化；关闭玻璃后原生恢复 | 待用户验证 |
| 4 双栏与组合 | 默认双栏、SONG/QUEUE 切换、独立右歌词；玻璃+双栏组合；封面缩放保留、队列不裁切、上拉无明显卡顿 | 待用户验证 |
| 5 歌词 | 普通/逐词/无歌词、快速切歌、滚动暂停模糊/稳定恢复、CJK；导入 TTML、多 ID、禁用/删除、自动补词与三个手动来源；不得串歌或重影 | 待用户验证 |
| 6 字体与资产 | TTF/OTF 导入、后台加载、行/词一致、原字体恢复；ZIP 导入导出/冲突选择/取消，SAF 返回原设置草稿 | 待用户验证 |
| 7 元数据全部表面 | 原地区/cn/jp；mini、播放器、队列、资料库、艺人/专辑、主页和操作菜单分别对照；缓存与离线正常 | 待用户验证 |
| 8 蜂窝/Editorial/DPI | 蜂窝默认关闭、开启后原生 availability；Editorial 平板横屏规则、预览/Music Video；DPI 冷启动/旋转仍仅宿主生效 | 待用户验证 |
| 9 自适应布局 | 宽度在600/675/735/830dp两侧，横竖屏/分屏、深浅色、字体缩放、系统栏及RTL；新档位按钮保留 | 待用户验证 |
| 10 生命周期与性能 | 多次旋转/设置返回/后台返回、连续重建无 View/会话/监听累积；同设备/歌曲/配置测5轮冷启动和拖动帧时间，持续>10%退化须定位 | 待用户验证 |
| 手机 | 同版本 arm64 手机上的导航、mini触摸、系统栏和全部歌词流程；缩窄平板可先看布局但不替代手机验收 | 待用户验证 |
| 旧版本回归 | 1599 当前静态通过；1583/1586 原始包尚缺，完整旧版设备矩阵待验收 | 待用户验证/补充安装包 |

6.5.3 的两处既有降级仍单独记录在 [旧降级记录](refactor-degradations.md)。1606 已重新取证菜单与主页目标，未直接继承“已降级”的结论；它们也不能在未测试前写为已修复。

另有运行时未完成项：已采集的 1606 能力日志中，元数据修正的 `LOCAL_MEDIA_PLAYER_INDEX_CHANGED` 必需 bootstrap 目标仍未解析，显示 DEGRADED。65 个展示目标的静态验证不能代替这条启动链路的运行时成功；后续需独立修复和验收。本次 v3 交付聚焦用户反馈的平板玻璃，不能据此声称完整功能矩阵已通过。

反馈时请附设备/API、屏幕方向与宽度、开启的功能、歌曲ID、操作步骤，以及设置中的能力报告和对应录屏或日志。首轮优先完成1–4，再测试歌词、字体和元数据。真机完成后再更新 `evidence.runtimeVerified` 与本表；本轮保持 false/待验证。

最新平板实现及逐项迁移、实机和用户验收记录见 [完整参考迁移记录](applemusic-700-tablet-reference-port.md)。迁移提交 c619a7a；最终 v4 已安装并由用户确认通过，此记录覆盖此前 v3 与临时 v4 的平板交互状态。
