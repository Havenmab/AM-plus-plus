# 旧版功能保全与验收矩阵

基线：97c4b69；schema=15；Apple Music 1583/1586/1599。自动测试不等同真机验收。

本矩阵用于每个受支持宿主版本的功能保全与人工验收。历史兼容范围以精确 profile 为准，下表保留旧版验收基线；Fragment 页面族的平板玻璃使用顶部导航和独立底部 mini。真机验收按版本分别记录，静态契约匹配和构建成功不等于功能通过。

Fragment 双栏保留原生封面缩放、暂停比例和视频内容；关闭歌词后的居中目标通过封面 X 补偿接入原生展开/收起过程，下半区保持居中显现。具体契约见 [宿主适配手册](host-adaptation-guide.md#平板双栏与媒体组件)。旧 Activity 页面族的 Editorial 保全语义保持不变。

| 功能 | 保全语义 | 自动验证入口 | 真机状态 |
|---|---|---|---|
| 双栏 | 官方平板横屏、原生 SONG/QUEUE、独立右歌词、事务/旋转/锚点/渐变/封面 | DualPane、Tablet、StateAccessors 用例 | 待三版本对照 |
| Editorial | 双栏控制、仅平板横屏 URL、保留预览及 Music Video | TargetAdaptationBehavior | 待对照 |
| 模糊 | 双向、焦点、滚动暂停恢复、换歌、API31、-10..10px | Blur/Highlight 用例 | 待对照 |
| CJK | 单词作用域、汉字/日文/韩文、动画清理、不抢位移 | CjkKaraoke 用例 | 待对照 |
| 字体 | TTF/OTF、后台与重试、行词一致、恢复原字体 | Font、LyricsTypeface | 待导入/恢复 |
| 自定义歌词 | 获取ID、多ID、增删改查/禁用、TTML/指针校验、异步重应用 | CustomLyrics、Ttml 用例 | 待播放对照 |
| 在线/自动 | 三手动来源；自动 AMLL→Lunabeat→AM-Lyrics；manual/disabled优先；generation/退避/哈希/ETag | Online、AutoLyrics、Lunabeat 用例 | 待网络/离线/切歌 |
| 更新/ZIP | 批量、取消、进度、覆盖/保留冲突、事务恢复 | Update、Backup、Restore | 待恢复对照 |
| 元数据 | 原地区/cn/jp、所有展示表面、ISRC/原名证据、离线/持久缓存/优先级 | Metadata、Catalog、Cache 用例 | 待三模式/页面 |
| 玻璃 | API33，1586/1599；手机/旧平板横屏、导航/mini/手势/过渡/采样/恢复 | Glass、TabletGlass、LayerAlpha | 待帧/视觉/手势 |
| 玻璃参数/补偿 | gap16/0..48，blur4/0..24；独立恢复与显隐；玻璃接管补偿 | ModuleSettingsSchema、SettingsDraft | 待显隐/占位 |
| 蜂窝 | 默认false，1586/1599；构建作用域仅一次；availability独立 | CellularDataEntry | 待原生入口 |
| DPI | 0或160..640、仅宿主、冷启动/配置变化/失败失活 | AppleMusicDpiOverride | 待重启/旋转 |
| 设置 | 无launcher、原生入口、草稿/SAF归属/页面重建/清理 | EmbeddedSettings、EmbeddedOnlyArtifact | 待全流程 |
| 存储/迁移 | 原键/目录/ID/marker/schema/ZIP/SQLite；ordinary不覆盖资产；原子发布；只读重试 | ConfigurationMigration/Session/Storage/Index/Transaction | 待升级/回滚 |

1583 的玻璃/蜂窝保持不支持。6.5.0/1580 仅参考，不在生产注册范围。停用的全局目录语言功能不重新启用。

## 平板双栏补充验收

以下补充对应当前平板双栏实现；上面的旧版验收基线不变。每次适配都分别记录静态检查、构建和完整真机验收结果。

| 场景 | 应保留的行为 | 自动验证入口 | 真机状态 |
|---|---|---|---|
| 首次打开、旋转、重建 | 左栏 45%/右栏 55%；SONG/QUEUE 控制区 25%；标题行/进度条左留白 55dp，右到分隔线 7dp，加歌词文字起点的 48dp 得到 55dp 栏间距；原生绑定后仍保持末端留白；保留原生字体、图标和封面缩放；首帧没有旧 footer 闪现 | TabletSongLayout、TabletSongContentInsets、TabletNativeControlRows、TabletComponentStartup | 待完整验收 |
| 封面基准尺寸 | 空间足够且未缩放时正方形边长与实际进度条同宽；高度不足时缩小并居中；旋转/窗口/原生 padding 更新后重新计算；保留暂停缩放、视频比例及原生过渡，退出双栏恢复仍由模块拥有的几何 | TabletArtworkLayoutPolicy、TabletProgressContent、TabletArtworkLayoutLease 源码契约；功能由用户手动验收 | 待宽高比、暂停/播放、旋转和视频对照 |
| 切歌封面布局 | 原生绑定完成后在测量前统一应用留白和播放按钮居中；组件/封面几何变化后先完成新布局；普通重新布局时已有音量/代理按钮保持有效位置；原生渐变、暂停缩放、视频接管和队列过渡保持 | FragmentTabletDualPaneSession、FragmentTabletDualPaneCoordinator、TabletSongContentInsets、TabletPlayerComponents 源码时序；功能由用户手动验收 | 1609：2026-10-11 用户确认最新绑定修正后，本次切歌封面位置/大小跳动及底部闪动消失；其他切歌边界、原生动画组合及旧版本待验收 |
| 音量与底部动作 | 整个音量控件（含两端图标）与进度条等宽，内部轨道保留每侧 40dp 图标空间；短窗口压缩音量触摸空白；不添加输出按钮，原生底部动作不可见且不接收触摸；音量下方无残留水波纹，退出双栏恢复原生可见性；音量拖动可用 | TabletComponentGeometry、TabletComponentStartup | 待横竖比、触摸及切页对照 |
| 原生动作与反馈 | 播放三按钮与进度条、音质标识及封面使用同一水平中心，布局与点击区域同步，原生间距/垂直位置及退出后的水平边距恢复；右下 selector/状态稳定，队列调用原生，翻译原生；播放图标默认大小，水波纹外加 8dp、白色 20% | TabletSongContentInsets、TabletNativeActionStyle、TabletTransportRipple、TabletComponentStartup | 1609：用户已确认当前居中效果；交互/切页及其他版本待验收 |
| 换到无歌词歌曲 | 上一首文本或启用状态不能打开右栏；普通/自定义、网络许可和离线歌词按当前歌曲判定 | FragmentTabletLyricsAvailability、TabletComponentStartup | 待切歌、网络及离线对照 |
| 关闭歌词后切队列 | SONG/QUEUE 的进度条内容范围都居中，封面保持同一水平中心，右栏继续关闭；可用歌词恢复只解除自动关闭，保留手动关闭状态 | TabletLyricsPaneMotion、TabletComponentStartup | 待切页和换歌对照 |
| 展开、收起、反向拖动 | 封面 mini 到居中目标连续，下半区不从左侧滑入；保留原生 scale/Y，重复帧和换 pane 不累计 X 补偿 | TabletLyricsPaneMotion、TabletComponentStartup | 待录屏、暂停/播放及动态封面对照 |

## 既有降级（独立修复）

| 子面 | 基线状态 | 重构验收规则 |
|---|---|---|
| 6.5.3 操作表元数据 | 已知目标歧义/降级 | 单独取证和修复，不能扩大到任意 binding |
| 6.5.3 Listen Now 封面连续性 | 已知降级 | 保持其余主页/元数据；单独取证、测试和真机验证 |

真机使用相同设备、宿主、设置、歌曲与操作脚本对照；每场景留日志/截图或录屏。性能至少五次，持续>10%退化须修复。缺少设备/原始旧包时保持待验收，不能写 PASS。
