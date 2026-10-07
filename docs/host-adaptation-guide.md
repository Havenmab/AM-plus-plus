# 后续版本适配入口

版本资料集中在 `host-applemusic/src/main/resources/host-profiles/`。每个精确包名/版本名/版本号只有一份 JSON；`index.json` 控制旧引擎的候选顺序。生产入口、玻璃资格、蜂窝资格、静态脚本都读取这里。`host-applemusic/src/test/resources/baseline/` 是 97c4b69 导出的冻结快照，不随普通重构更新。

## 模块边界

| 模块 | 内容 | 禁止依赖 |
|---|---|---|
| core | schema、配置/歌词/字体事务、网络来源、缓存、布局和会话策略 | Android、宿主反射、libxposed、玻璃 |
| host-api | 能力安装结果、配置读取、歌曲身份、可关闭订阅、导航/mini/player 区域 | 混淆成员、反射类型、宿主 AndroidX、渲染器 |
| hook-runtime | libxposed 包装和注册作用域 | app、宿主实现、玻璃 |
| host-applemusic | 两套旧解析引擎、DexKit、native TTML、双栏、设置入口和元数据接入 | app、glass |
| app | APK 入口、装配、存储、业务开关、设置 View、玻璃会话呈现 | 功能/页面自行解析宿主成员 |
| glass / backdrop | 材质呈现和固定上游源码 | Apple Music 版本白名单 |

`AppleMusicHostFactory` 是 app 装配宿主实现的入口。设置页接收 `SettingsViewBridge`/`SettingsActivityMatcher`，不直接依赖 Apple Music 工厂。自动歌词源、发布器与存储仍由 app 注入，宿主模块不会反向读取 app 的内容管理器。

## 普通混淆变化

1. 先保存实际安装包 SHA-256、二进制 Manifest、全部 DEX/split 和资源证据，区分“类仍存在”和“实际活跃路径”。
2. 新建精确 tuple JSON，填写 `indexed`、`hookTargets`、运行时成员、资源、能力、页面族和 `verification`。完整描述符优先于只记录名字。
3. 新 profile 默认 `ambiguityPolicy=reject-ambiguous`，不得复制旧 `legacyFirstMatchExceptions`。契约和回退实现留在 Kotlin，数据不能执行代码。
4. `productionEnabled` 保持 false，先给新 tuple 增加解析、错误签名、歧义、继承、缓存重校验和类加载器测试。
5. 运行 `python scripts/verify-profile-data.py`、`python scripts/verify-architecture.py` 和 `python scripts/verify-host-profile.py PACKAGE --version-name NAME --version-code CODE --glass`。静态脚本直接核对二进制 Manifest，参数不能伪造宿主版本。APKS/XAPK 收集所有 split DEX 和布局。
6. 执行全模块测试、Lint、构建，再按功能保全矩阵进行旧版本回归和新版本真机验收。用户明确要求适配精确 beta 时，可以在开发分支为用户测试启用该 tuple；运行时验收状态必须独立记录，不能将静态通过写为真机通过。其他版本仍需独立取证。

旧 HLE 引擎的“版本名或版本号匹配”和已审核候选回退暂时保留，外层生产门禁始终精确匹配 tuple。不要直接收紧旧引擎后把未验证的旧功能失效当作正常重构。

## 地区替换

地区替换在 HLE 本地化钩子里，共五层，均在“将Apple Music改成其他地区”选中了带 storefront 的地区档位时才改写普通目录流量（与 HLE 一致，没有额外总开关）：

1. MediaApi storefront 字段（`applyRegionConfiguration`）；
2. MediaApi 请求参数表（`MEDIA_API_LOCALIZATION`）；
3. 内容 HTTP 拦截（`CONTENT_HTTP_LOCALIZATION`，URL 与 `Accept-Language` / `X-Apple-Store-Front` 头）；
4. 目录直连请求执行器参数（`MEDIA_API_CATALOG_REQUEST_EXECUTOR`）；
5. amp-api 内容请求拦截（`MEDIA_API_AMP_HTTP_INTERCEPTOR`）。

第 4、5 层先取 `AppleMusicHookProfiles.exactTargets`，为空即“本版本无已验证目标”，不安装也不回退整包 DexKit 扫描；第 3 层不走这个门禁（6.5.2 靠兼容候选链命中 6.5.1 的 `u8.a#a`）。因此前三层在四个 profile 都有声明或兼容候选，第 4、5 层仅 `6.5.3/1599` 与 `7.0.0-beta/1606` 声明。6.5.1/6.5.2 缺第 4、5 层的覆盖缺口见 `docs/refactor-degradations.md` 的 D3，未取证前不得补猜测目标。

电台/歌词/播放这类账号权益相关请求在改写前先命中 `isAccountScopedPlaybackPath` / `isAppleLyricsRequestPath`，强制回退到 `accountStorefrontForPlaybackRequest()` 捕获的账号 storefront。

代码入口：地区档位与映射 `core/src/main/kotlin/dev/amenhancer/module/config/RegionSelection.kt`（storefront/语言/缓存命名空间；两款开关的请求侧投影见同目录 `RegionTitleRequestPolicy.kt`）；请求改写 `host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleContentLocalizationHooks.kt`（五层安装与参数/URL 改写）；storefront 应用与账号回退 `host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogRegionAccess.kt` 与 `AppleInternalCatalogResolver.kt`（`applyRegionConfiguration` / `accountStorefrontForPlaybackRequest`）。

`7.0.0-beta/1606` 上第 3 层是内容 HTTP 的 OkHttp 拦截器（`rb.a#a`，HLE 的 cookie/主客户端拦截器），第 5 层是 amp-api 的拦截器（`y9.d#a`）。两层必须挂在**不同**客户端上：`y9.a#a` 是 amp-api 客户端上更靠后的 OkHttp 拦截器，若把它当作第 3 层，第 5 层剥掉 executor 标记后它会把已经按原地区改写的请求再次改回配置地区，使原地区原名查询永远拿到配置地区的行。目标描述符与 262 项/55 项原包校验见 `scripts/verify-host-profile.py`。

## 页面架构变化 / 7.x

`PlayerSurfacePort` 分别提供导航、mini 和播放器区域及导航位置。`LegacyChromeHostBinding` 接现有 Activity；`FragmentPlayerSurfaceAdapter` 按 view 身份和配置 revision 创建/销毁会话。FragmentContainerView 的限制通过 `restrictedPlayerContainer` 明确表达；模块视图挂入允许的 sibling/普通容器，不能直接往受限容器塞 View。

`HostViewSessionController` 关闭旧会话后才发布新会话；旧 owner 的延迟 destroy 不能关闭新 owner。`OwnedHostProperty` 只恢复模块仍拥有的最后写入；原生暂停封面 scale、后续 alpha/translation 写入保留。旧 SONG/QUEUE 语义维持，不以新导航替换播放器状态。

7.0.0-beta/1606 已新增精确生产 profile，为用户测试启用；历史 research 夹具继续留在测试目录。工厂按 `fragment-content` 分派 settings2、双栏与 Fragment 玻璃。新平板使用顶部导航和独立底部 mini 的原生边界，抽屉保留原生交互；玻璃不依赖双栏开关。新布局和渐变字段只从当前 tuple 读取。具体证据、测试范围和待验收项见 [1606 适配记录](applemusic-700-adaptation.md)。正式版和其他 beta 必须重新取证。此前实验 APK 没有整体合并。

## 热路径与安装

冷启动顺序维持：精确版本 → 配置迁移/绑定 → DPI → 资源回调 → Application 后功能 → 设置入口。资源基础设施失败停止后续安装；目标缺失只降级对应能力。

Hook 注册先处于 preparing，必要目标全部成功后 activate；失败 close 后已注册回调变为原生透传。不依赖框架提供可靠 unhook。原生歌词缓存持有不透明句柄，JavaCPP 地址/liveness 与 Adam ID 校验留在宿主适配器，进入 I2（旧版）或 w2（1606）前才解包。

Chrome ID、字段、方法在绑定时缓存；布局变化显式失效视图缓存。slide/alpha 回调不扫描 DEX 或发现反射成员。文件、网络、TTML parse 继续在后台。字体 Hook 已安装和字体实际加载分别报告。

## 验证与回滚

`./gradlew test :app:lintDebug :app:lintVitalRelease :glass:lintDebug :host-applemusic:lintDebug :app:assembleRelease`，CI 另运行架构/profile/玻璃参考源码校验。配置 schema 16、键、文件 ID、ZIP、目录、DB/cache namespace 不变，代码阶段回滚无需反向数据迁移。

源码契约覆盖拆分后整个职责组件；实际行为测试仍执行原夹具。不可通过删断言、更新冻结快照或加入猜测候选消除失败。交付记录见 `docs/refactor-validation.md`，既有降级与地区覆盖缺口见 `docs/refactor-degradations.md`。
