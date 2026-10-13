# Apple Music 宿主适配手册

本手册供维护者为一个具体 Apple Music 构建建立、维护和验收 AM++ 接入。适配对象是包名、versionName 和 versionCode 组成的精确版本组合；正式版、beta 和同名的不同构建分别处理。手册覆盖当前 Activity、Fragment 两类宿主，并作为后续版本的统一流程。

配套文档：[适配清单](host-adaptation-checklist.md)用于核对工作是否完整，[功能保全与验收矩阵](feature-preservation.md)定义功能行为和人工场景。本手册描述当前架构与适配方法；单次任务的排查过程、代码改动和构建日志保存在任务记录中。

## 1. 适用范围与版本登记

版本资料位于 [host-profiles](../host-applemusic/src/main/resources/host-profiles/)。支持范围由 `index.json`、精确 profile 的 `productionEnabled` 和能力标志共同确定。版本表、成员映射、已知降级与该构建的证据在档案中维护，本手册不重复登记。

“已启用”表示允许模块接入该构建，不表示全部功能已经通过当前代码的真机验收。功能还受设置、Android API 和设备形态限制；人工验收模板见 [功能矩阵](feature-preservation.md)，实际结果在对应任务中记录。

页面族按实际入口、生命周期和布局识别：`legacy-activity` 与 `fragment-content` 分别使用自己的接入。正式版、beta、相邻构建和新页面族均需独立分析，版本号大小和主版本号不能代替页面族识别。

`AppleMusicHostProfiles.find` 按版本名、版本号定位文件，并校验包名；生产资格另检查 `productionEnabled`。资料文件名使用 `<versionName>-<versionCode>.json`。同一组合只能登记一次。索引顺序还影响旧 HLE 引擎的已审核候选，适配时应保留现有条目的相对顺序。

## 2. 架构与修改位置

| 模块 | 职责 | 适配时的边界 |
|---|---|---|
| `core` | 配置、歌词与字体业务、来源、缓存、布局和会话策略 | 不引入 Android、宿主反射或 Xposed 依赖 |
| `host-api` | 宿主能力、歌曲身份、区域接口、配置读取、安装结果和可关闭订阅 | 不暴露混淆成员、反射类型或宿主 AndroidX 实现 |
| `hook-runtime` | Xposed 接入、注册作用域、通用调用缓存 | 不承载 Apple Music 版本策略或业务页面 |
| `host-applemusic` | profile、符号解析、原生歌词、设置、播放器、导航和元数据接入 | 所有 Apple Music 私有成员和页面族差异集中于此 |
| `app` | 功能装配、配置存储、设置界面、歌词服务与玻璃会话 | 通过宿主接口使用能力，不自行寻找混淆成员 |
| `glass` / `backdrop` | 材质、采样和呈现 | 不管理 Apple Music 版本资格 |
| `plugin-api` / `plugin-runtime` | 插件 SDK、导入、加载、生命周期和冲突分析 | 不随单个宿主构建改变公共协议 |

宿主装配入口为 `AppleMusicHostFactory`，按当前精确 profile 的 `family` 选择设置、双栏、蜂窝和视频处理路径。只发生混淆变化时，优先修改版本数据；调用形式、生命周期或布局语义变化时，修改对应宿主适配层。

内部接入遵循三类职责：

- **Contract**：解析并校验成员、资源及相互类型关系，持有缓存句柄。
- **Runtime / Coordinator**：注册 Hook、管理活动宿主和能力状态。
- **Session / Binding**：管理具体视图、监听器、延迟任务和属性恢复。

业务开关、缓存规则和歌词来源逻辑不应因混淆名字变化而重写。新页面族需要单独的能力工厂，不能默认归入已有路径。

## 3. 版本资料与成员契约

### 3.1 profile 的主要区域

| 区域 | 应记录的内容 |
|---|---|
| 身份与资格 | `id`、`packageName`、`versionName`、`versionCode`、`family`、`productionEnabled`、`profileRevision` |
| `capabilities` | 玻璃、蜂窝、收起拦截、播放器恢复等独立资格 |
| `indexed` | 类、方法、字段映射；支持精确契约的路径还填写 `methodContracts` / `fieldContracts` |
| `hookTargets` | HLE Hook 入口、签名约束、调用方约束及 `runtimeMemberNames` |
| `settings` / `chrome` | 设置入口、原生设置模型、导航和播放器资源或旧宿主契约 |
| `layoutVariants` / `cjk` | 歌词 binding、渐变和前景文本等版本数据 |
| `lyricBlur` | 两类页面族独立使用的完整歌词模糊契约：显示提交、文档/行身份、绑定、词动画、视图、触摸/滚动与 Seek |
| `catalogQueryRenames` | 实际目录查询 owner 与方法映射，配合完整签名和调用路径核对 |
| Fragment 专用区域 | 双栏、歌词运行时、逐字宽度、视频接力、chrome 和恢复所需契约 |
| `verification` | 离线检查需要的 DEX 描述符、字段类型、资源名和辅助接口证据 |
| `evidence` / `knownDegradations` | 来源、证据状态、运行时确认范围及已知能力缺口 |
| 匹配策略 | `ambiguityPolicy` 和历史 `legacyFirstMatchExceptions` |

JSON 描述宿主事实，不承载可执行逻辑。参数变换、回退条件、线程和生命周期策略由 Kotlin 实现。profile schema 当前为 1，应用配置 schema 当前为 15。

### 3.2 完整描述符与语义核对

| 成员 | 必须核对 |
|---|---|
| 方法 | 实际声明 owner、名字、参数顺序和完整类型、返回类型、static、抽象/bridge/synthetic 属性 |
| 字段 | 实际声明 owner、名字、完整类型、实例归属和被读写的含义 |
| 构造器 | owner、完整参数类型与顺序、创建对象的实际用途 |
| 接口调用 | 返回对象实现关系、实际声明接口、调用形式；必要时使用 `invocationOnly` |
| Hook 目标 | 可 Hook 的具体实现，及它是否位于实际活跃调用路径 |
| 资源 | 名称、类型、限定符、实际取值、包含布局、运行时父容器和参数类型 |

“类存在”“参数个数相同”和“成员能反射调用”均不足以证明正确适配。混淆类名可在新包中承担其他职责；旧方法名也可能仍存在，却接收另一种模型。应沿调用方、返回对象和后续读写核对完整链路。

继承成员记录实际声明 owner。抽象接口可作为带 `invocationOnly` 的调用契约，不能作为新增 Hook 目标。数组、primitive、可空对象和实例/静态调用不能相互替代。

### 3.3 两类解析路径

`IndexedTargetSymbolResolver` 为每个符号缓存解析结果，包括缺失和歧义。已声明的精确方法或字段契约不匹配时返回缺失；稳定名和结构回退是否允许，由该符号的策略决定。必需成员不能通过另一个相似符号补齐。

HLE 的 `AppleMusicHookProfiles` 将 JSON 转为兼容引擎的候选。Fragment 档案使用严格候选；旧 Activity 档案保留既有版本匹配和已审核回退顺序。外层生产门禁始终精确匹配版本组合。

新档案采用 `reject-ambiguous`。历史首个匹配例外只适用于原有已审核目标，不能作为新版本的默认策略。若发现两个候选，应补充类型、调用关系或契约，无法唯一确定时降级对应能力。

## 4. 每个版本的适配流程

### 4.1 确认安装包与代码基线

1. 从实际 base APK 的二进制 Manifest 确认包名、versionName、versionCode；包文件名及外层清单只作辅助。
2. 清点 base、全部配置或功能 split、各 DEX 和资源来源。不能假定 DEX 仅位于 base。
3. 记录代码基线、拟复用的页面族及对照宿主。保存可回溯的源码位置、描述符和资源证据。
4. 明确工作范围：源码分析、静态检查、构建及人工功能验收分别安排。包哈希只在明确要求时计算。
5. 保存当前代码与必要测试产物，以便对照和回退。

Java 反编译用于理解调用链，成员声明与完整签名以 DEX/smali 为准。工具生成的类型推断、命名修复以及大小写冲突后的文件名不是宿主原始类名；嵌套类、接口和泛型擦除后的实际类型按字节码记录。资源反编译还需核对限定符和实际包含关系。

### 4.2 建立完整接入清单

从工厂、能力入口和实际调用链展开，覆盖设置、歌曲身份、自定义/自动歌词、模糊、CJK、字体、名称修正、手机/平板玻璃、双栏、视频与背景、蜂窝及 DPI。

每项都记录“直接 Hook 入口”和“回调继续使用的附属成员”。附属成员包括 binding、holder、getter、listener、列表、Fragment 事务、浏览器接口和模型字段。没有变化的契约也应有当前安装包依据。

### 4.3 建立档案并实现接入

1. 建立精确 tuple JSON，开发期保持 `productionEnabled=false`。
2. 按实际页面族填写通用区域和该族必需区域；从相邻构建取模板时逐项重新核对。
3. 将新依赖同时加入 profile、代码维护的必需角色清单和适配清单。
4. 调整 Contract、Runtime、Session 中发生变化的调用或生命周期；同一能力不同时维护一份固定旧版本映射和一份新映射。
5. 缓存成员与资源，处理失败和恢复。缺失目标应明确报告能力状态。
6. 对照全部布局限定符和生成 binding，确认原生重绑定、尺寸动画和异步回调不会覆盖模块布局规则。

### 4.4 检查、登记与交付

静态核对和源码检查用于发现成员与资料缺口；APK 构建用于确认可以生成交付物；人工验收用于确认具体功能和设备场景。三者分别记录。

完成接入后登记到 `index.json`。为明确请求的用户测试启用精确 tuple 时，保留尚未完成的运行时验收状态。相邻构建和未登记版本仍不获得资格。

交付至少包括精确 profile、必要宿主接入、当前通用文档、可安装 APK、能力缺口和按版本填写的验收结果。发布、推送和 PR 依任务授权单独处理。

## 5. Activity 页面族

`legacy-activity` 的主要入口为 `AppleMusicDualPaneTarget`、`DualPaneShell` / `ConstraintLayoutPane`、`LegacyChromeHostBinding` 和旧设置桥。

| 接入域 | 每个构建应核对 |
|---|---|
| Activity 与播放器 | PlayerActivity 根视图、BottomSheet、控制器初始化/创建/选择 pane、原生枚举和状态访问器 |
| 双栏资源 | 主播放器布局、左右 host、ConstraintLayout 字段、歌词 sheet、导航高度与收起边界 |
| 歌词同步 | Fragment binding、RecyclerView、渐变、度量、行词回调及监听清理 |
| 旧 chrome | 导航 holder、导航切页、mini、展开进度、拖动与采样区域 |
| 设置 | PreferenceFragment、PreferenceScreen、设置行 key/标题/摘要、生命周期及 Activity 结果桥 |
| 视频规则 | 既有 Editorial URL 处理范围、预览及 Music Video 的原生行为 |
| 名称修正 | HLE 模型与 binding、Epoxy、资料库、主页等全部显示表面 |

旧宿主的 AndroidX 参数类可能经过重新打包和字段混淆。应依据实际字段类型和原生使用位置确认映射，不能直接采用另一构建的字段名，也不能用模块自带 AndroidX 对象替换宿主对象。

旧路径的布局安装、原生状态选择及已审核候选回退属于兼容语义。调整通用接入时，需对已登记构建分别列出回归项。已有冻结夹具位于 `host-applemusic/src/test/resources/baseline/`；普通适配不能以更新夹具消除行为差异。

玻璃、蜂窝和 `staticCollapsedIntercept` 是独立资格，按当前 profile 判断，不能扩展到其他构建。操作表、主页封面等子面的已知降级单独记录，不能因其他表面成功而省略。

## 6. Fragment 页面族

`fragment-content` 的播放器、页面导航与 mini 生命周期由 Fragment 接入；不能套用旧 Activity 的 holder、设置或事务模型。

### 6.1 必需契约

| 区域 | 代码入口 | 完整性要求 |
|---|---|---|
| `fragmentDualPane` | `FragmentDualPaneContract` | 主播放器状态、Fragment/Manager/事务、pane LiveData、封面 callback、BottomSheet、歌词可用性及 binding 完成接入 |
| `fragmentLyricsRuntime` | `FragmentLyricsRuntimeContract` / `BetaLyricsPaneRuntime` | 生命周期、binding/附属容器、RecyclerView、度量、行监听注册与清理 |
| `fragmentKaraokeWidth` | `FragmentKaraokeWidthContract` | adapter、缓存宽度、holder、item、binding 与 flexbox |
| `fragmentTabletVideoHandoff` | `FragmentTabletVideoContract` / `FragmentTabletVideoHandoff` | 元数据 listener、视频尺寸/原生 resize 入口、静态基准/目标尺寸、TextureView、浏览器接口、模式和动画字段 |
| `fragmentDualPane.songLayout` | 歌曲栏 Contract / Runtime / Session | 明确需要覆盖布局时的参数、Guideline、Barrier、binding 与 padding setter |
| `fragmentChrome.members` | `FragmentChromeContract` / `FragmentChromeInventory` | 导航、菜单、LiveData、进度、生命周期、BottomSheet 和 mini 启动 |
| `indexed` 与 `playerRecovery` | `FragmentPlayerRecoveryContract` | 封面、尺寸基准、背景、进入/共享动画及销毁恢复的精确契约 |
| `settings` | Fragment 原生设置工厂及模型契约 | ViewModel、原生行模型、构造器、callback、Compose 入口及结果归属 |

`FragmentDualPanePreflight` 按 `FragmentDualPaneInventory` 解析双栏及附属组，再校验成员间的类型关系。JSON 少写必需角色会导致预检失败。chrome 和播放器恢复分别完成自己的必需契约解析，不能把双栏预检成功当作其他能力完整。

`songLayoutPolicy` 显式选择 `native` 或 `1606-column`。后者是现有列布局策略的标识，所有成员仍从当前版本 profile 读取。`native` 不携带布局覆盖；`1606-column` 必须提供 `songLayout`。新增布局策略需要代码和契约共同实现。

`layoutReview` 的六个字段必须有当前构建证据：`artwork`、`metadata`、`controls`、`progress`、`binding`、`restoration`。填写证据说明已经做过对照，不表示真机通过。

### 6.2 Fragment 生命周期与容器

`FragmentTabletDualPaneCoordinator` 管理控制器，`FragmentTabletDualPaneSession` 管理单个播放器视图。Fragment view 重建、配置变化和 controller 销毁分别处理。保存状态期间的事务应延后；恢复中的歌词 host 保持可测量，不能在事务提交前折成无效宽度。

右栏运行时 ID 和 Fragment 恢复 tag 属于持久恢复约定。其历史命名不影响作用，不能随版本号改名。SONG / QUEUE 使用原生状态和选择方法，独立歌词放在右栏。

`PlayerSurfacePort` 及其 Fragment 适配提供导航、mini 和播放器区域。模块视图只挂入允许的普通容器或 sibling；受限的 FragmentContainerView 不接受任意模块 View。

### 6.3 导航、mini 与设置

手机使用原生底导航与 mini 边界；平板使用顶部导航和独立底部 mini。平板 Fragment 玻璃与双栏开关独立。导航模型、三个 getter、选择 callback、重复点击、搜索及抽屉逐项核对，不能只找一个签名相似的方法。

mini 启动恢复需同时满足原生登录判断及当前播放对象存在。显示方法可能为实例调用，也可能是静态的 `(owner, boolean)`；按当前描述符及调用标识处理。重试和延迟回调确认 owner / view 仍有效，销毁后停止。

Fragment 设置使用原生 ViewModel 和设置行模型，保留分类、行去重、点击与文件选择结果归属。构造器中的 artwork 类型、callback 和 Unit 不应借用其他构建的类型。

## 7. 各功能的适配要点

### 7.1 歌词模糊的独立契约

`AppleMusicLyricBlurContract` 对两类页面族都解析独立 `lyricBlur` 区域，不依赖双栏开关或双栏契约解析成功。代码维护的必需角色、当前 profile 与 `scripts/verify-host-profile.py` 清单同步维护，JSON 漏写角色不能绕过预检。

适配时独立核对实际显示提交、文档/行身份、四路词动画、首句时间与播放位置、绑定/回收、触摸/滚动和 Seek。混淆名、声明 owner 和类型关系以当前安装包为准；Native 事件不替代宿主已应用的显示集合。

显示规则、前奏/延迟三点处理、状态分层、效果所有权和人工场景统一维护在 [歌词模糊维护指南](lyrics-blur-guide.md)。

### 7.2 其他功能与共同规则

| 功能 | 必须保持的业务与接入规则 |
|---|---|
| 当前歌曲身份 | 元数据转换器、当前 item 和歌曲 ID 对应实际播放对象；异步结果再次核对当前歌曲 |
| 自定义歌词 | 原生 TTML 解析、有效指针检查、普通/无官方歌词歌曲、多 ID、禁用优先级、异步重应用和重复注入抑制 |
| 自动补全 | 既有触发条件、来源顺序、手动/禁用优先级、代次控制、重试和缓存策略；切歌后旧结果不能注入 |
| 模糊 | 已提交显示快照、ID/序号/位置分离、双向300ms原曲线、用户浏览和空闲1秒恢复；每视图会话、效果所有权独立于双栏 |
| CJK | 单词作用域、前景文本、合并高亮及动画结束清理；保留宿主位移 |
| 字体 | TTF/OTF、后台加载、重试、行词一致、视图重建和原字体恢复；区分 Hook 安装与字体就绪 |
| 名称修正 | 原地区/cn/jp 模式、原名与标识证据、目录查询、离线及持久缓存、显示优先级；各表面单独安装与报告 |
| 玻璃 | Android API、精确资格、导航/mini 区域、采样、触摸、展开过渡和属性恢复；材质准备完成后接管原生呈现 |
| 蜂窝入口 | 精确能力资格、原生登录/类别逻辑、availability 接入和默认开关；不使无资格构建自动支持 |
| DPI 与数据 | 仅宿主范围、冷启动和配置重建、失败失活；配置键、文件 ID、目录、SQLite、缓存 namespace 和 ZIP 格式保全 |
| 视频与背景 | 页面族自己的原生视频策略、尺寸/比例、暂停缩放、表面切换、shader/背景清理和后台恢复 |

名称修正至少覆盖播放器、队列、历史、操作表、最近搜索、资料库、专辑、歌单、艺术家和主页。除目标方法外，还需核对模型绑定、Epoxy holder、LiveData、辅助 getter 和实际封面加载路径。一个表面成功不能代表整项能力成功。

目录查询需核对实际接口、请求结构和后续返回路径；Compose/LiveData 需核对当前类型别名及真实参数类型。名称相同或反射调用成功不足以确认它们仍服务于同一表面。

旧 Activity Editorial 处理和 Fragment 原生视频策略分别保留。停用的全局目录语言功能及未启用的播放模式 UI 不因新增档案自动获得启用。

## 8. 平板布局与视频尺寸

### 8.1 资格与基础布局

双栏使用既有门禁：精确版本、开关、横屏、宽度至少 600dp，以及原生 `useNavigationDrawer`。同时核对 DPI 覆盖和多窗口对有效宽度的影响。

当前共享呈现规则如下：

| 项目 | 布局要求 |
|---|---|
| 分区 | 名义分隔位置为播放器宽度的 45%，歌词视口位于右侧 55% 分区 |
| 左侧留白 | 标题/进度内容到屏幕左边缘 50dp |
| 右侧留白 | 标题/进度内容右端到歌词正文起点 50dp |
| 歌词留白 | 歌词自己的 margin、padding 和右侧边界沿用原生 |
| 左栏对齐 | 标题、进度、音质标识、播放按钮和封面使用一致的水平中心 |
| Fragment 控制区 | SONG / QUEUE 控制区为原生容器高度的 25% |
| 音量 | Fragment 的整个控件含两端图标与进度内容等宽，轨道内部每侧保留40dp图标空间 |
| Fragment footer | 被替代的原生动作保留布局但不可见且不接收输入，模块音量及右下代理使用当前有效位置 |
| Fragment 代理 | 歌词/队列代理沿用原生 drawable、selector、tint及动作，44dp点击区域、8dp间隔、底部12dp |
| 播放反馈 | 原生图标尺寸保持，Fragment水波纹为图标最大半径外加8dp、白色20%不透明度 |
| 原生动作 | SONG / QUEUE、播放、搜索、抽屉和队列操作保留原生语义 |

50dp 的右侧测量终点是歌词正文，不是名义分隔线。对当前原生布局，歌词起点为分隔线加 48dp（host 16dp、行 margin 20dp、文字 padding 12dp）；原生播放器内部左右各 32dp，外层各 18dp。左 host 宽度为 `0.45 × 播放器宽度 + 48dp - 2 × 18dp`。这是可见内容间距，不能据此声称两个实际 View 宽度严格为 45% / 55%。

计算时先将 dp 按当前宿主密度换算为像素，再计算容器和整像素边界；窗口宽度来自当前播放器区域。

新宿主需重新核对这些内部尺寸及资源限定符，再由共享布局策略计算容器。普通/逐字、静态、翻译、背景、间奏和 credits 行应分别检查；字体、高亮比例和字形边界会影响可见像素。

Fragment 媒体组件位于 `hook/tabletmedia/`。旧 Activity 路径共用列宽和封面策略，并保留自己的原生控制组件；不能把 Fragment 的附加音量、代理按钮或动画接入直接视为 Activity 页面族功能。

Fragment 音量行中心比原生 footer 中心高6dp，与播放/进度区域保留4dp净间距；短窗口优先调整行位置或压缩触摸空白，24dp图标保持。原生被替代动作使用 INVISIBLE 保留约束，并关闭点击、取消待处理输入及按压反馈；原本 GONE 的装饰保持 GONE。关闭会话恢复仍由模块拥有的属性。歌词渐变视口不额外预留底部代理高度。

### 8.2 完整布局和数据绑定

布局核对必须覆盖所有有效的 `layout`、`layout-land`、宽度限定符、dimen/integer/bool，以及递归 include。资源名称相同也可能取到不同值。

特别检查：

- 封面 Guideline、ratio、上下锚点、实际 inset 和元数据 Barrier。
- 标题/副标题/合作信息/更多按钮的边界及是否依赖封面宽度。
- 进度和直播进度的容器 padding、音质标识、播放按钮及 footer 约束。
- generated binding 是否在换歌、播放状态变化或异步加载后重写 padding、尺寸和可见性。
- adapter 首次绑定是否早于右栏测量，以及逐字有效宽度是否从正确视口初始化。

反射与资源在安装或视图绑定阶段解析。Fragment 的原生 binding 完成接入只处理当前播放器子树，在测量前准备控制区和 footer。尺寸修改后应完成新布局再绘制；普通重新布局期间，已经放置的模块控件保留有效状态。

### 8.3 封面、视频与恢复

静态封面基准为实际进度内容宽度与上方可用高度的较小值，并在该区域居中。尺寸由当前测量重新计算，封面可因高度限制小于进度宽度。原生暂停 scale、sheet scale/Y、队列和共享过渡分别保留。

视频需要分别核对外层 `artwork_container`、内层 card、静态图、TextureView、尺寸基准和目标尺寸。静态转视频、视频转静态、视频转视频、静态转静态必须独立验收。

当前 Fragment 双栏由视图会话维护普通/视频共用的外框和静态尺寸基准。进入视频模式不释放双栏外框；原生 image/TextureView 继续负责视频比例和渐变。`FragmentTabletVideoHandoff` 在原生尺寸计算和 resize 前通过 Coordinator 准备当前会话的基准，视频目标按其比例适配该外框，静态目标使用同一基准。`staticBaseline`、`targetSize` 和 `resize` 均为当前精确 profile 的必需角色。

`FragmentPlayerArtworkRecovery` 优先读取当前双栏会话的目标尺寸；视图尚未完成重测时，也不使用旧的测量值覆盖新基准。外框修改请求布局，并在布局完成后绘制。会话退出、视图更换和销毁时按属性所有权恢复；进入/共享过渡、sheet 进度及非双栏状态仍使用各自的原生边界。

原生视频尺寸回调可能经过临时零尺寸，再进入有效尺寸；生成视频宽高比的函数还可能读取缓存的静态基准。每个新构建应追踪这条完整路径，检查外框、基准和目标是否一致。原生内部比例/渐变动画和模块外框变化分别判断；动画期间的写入、让权和恢复也要明确到具体 View 及生效时机。

播放器恢复、双栏尺寸和视频接力可能处理不同层级的 View。应列出每个写入者，确认不会交替恢复和覆盖同一参数。视频表面接力必须确认当前 owner、视图和播放状态；仅凭旧纹理仍可见不能认定它属于当前歌曲。

关闭歌词后的居中、右栏可用性与封面 sheet 补偿只适用于对应 Fragment 会话。原生 callback 执行前释放上一帧补偿，执行后合成当前帧；不累计 X，不覆盖原生 scale/Y，SONG / QUEUE 切换保留手动关闭状态。

## 9. 安装、降级与资源所有权

安装顺序为精确版本识别、配置迁移/绑定、DPI、资源回调、Application 后功能、设置入口。资源基础设施失败应停止依赖它的安装；单个能力目标缺失由该能力报告。

新增 Hook 使用 `HookRegistrationScope`：注册阶段为 preparing，必需项成功后 activate，失败或退出 close。关闭后的回调保持原生透传；不依赖框架一定提供可用的 unhook。

HLE 使用 `HleMetadataInstallation` 和 `AppleHookRegistrar.stageInstallation` 按表面分组。可选子组单独降级，父组失效关闭其子组；后续加入的 getter 继承所属组。已捕获的内层安装错误仍须上报，不能仅记日志后把整项能力报告为成功。

`HostViewSessionController` 以视图身份和配置 revision 管理会话，先关闭旧会话再发布新会话。`OwnedHostProperty` 仅恢复模块仍拥有的最后写入，原生后续写入保留。每个 listener、observer、animation listener 和延迟任务明确所属会话及清理时机。

成员调用缓存按 owner、完整签名及类加载器身份组织；旧路径明确采用历史兼容调用时保留原候选语义。slide、pre-draw、歌词高亮和逐字动画不扫描 DEX、不发现新反射成员。网络、文件读取和 TTML 处理沿用后台业务路径。

## 10. 检查、验收与回退

### 10.1 证据阶段

| 阶段 | 能证明什么 | 不能替代什么 |
|---|---|---|
| 安装包/源码分析 | 版本身份、成员描述符、调用关系、资源与布局决定 | 当前设备的实际触发和显示 |
| 静态工具 | 工具覆盖范围内的数据/契约一致性 | 所有辅助路径、资源取值和运行表现 |
| APK 构建 | 可生成本次代码的安装包 | Hook 实际安装、视觉和功能正确性 |
| 能力状态 | 当前进程中安装、降级、未支持或失败的结果 | 全部场景和旧版本回归 |
| 人工验收 | 指定版本、设备、设置和操作下的结果 | 未执行场景及其他版本 |

`productionEnabled` 与 `runtimeVerified` 分别维护。局部场景确认不能把整个档案改为运行时验证完成；没有对应字段或证据也不能推断为通过。

### 10.2 工具与执行范围

现有辅助入口包括 `scripts/verify-profile-data.py`、`scripts/verify-architecture.py` 和 `scripts/verify-host-profile.py`。按证据缺口和当次任务范围选择，工具说明见 [scripts 文档](../scripts/README.md)。

宿主静态脚本读取二进制 base Manifest，并收集包内 split 的 DEX 与布局名；显式版本参数必须与真实 Manifest 一致。开发期未启用的 profile 可通过 `--profile` 提供。静态匹配结果应同时记录脚本覆盖不到的语义依赖。

项目默认由使用者执行功能验收，不主动操作设备或运行功能脚本、全量测试、哈希验证。既有测试夹具供获授权的检查和 CI 使用；仅文档修改不需要构建 APK。构建交付物时选择任务需要的 Gradle 构建目标，记录产物与实际结果。

### 10.3 完成条件与回退

一个构建的适配记录应能回答：

1. 精确身份、页面族和能力资格是否明确。
2. 所有功能和附属契约是否有证据，缺口是否可报告。
3. 资源、binding、视频与恢复是否已按实际路径核对。
4. 配置、数据和插件接口是否保持兼容。
5. 构建、人工场景与旧版本回归分别完成到哪一步。
6. 出现问题时能否停用该 tuple 或能力，并恢复原生行为。

回退通过停用新版本登记及撤销对应接入实现完成。保持配置 schema、键、文件 ID、数据库/缓存 namespace 和 ZIP 格式时，无需反向数据迁移；涉及数据格式变化的工作必须另行定义迁移和回退，不能作为普通混淆适配附带处理。
