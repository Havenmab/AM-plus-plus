# 宿主版本适配清单

每次适配都按本清单逐项填写证据和结果。架构与检查入口用于减少遗漏，不能证明未知版本必然兼容。源码/DEX匹配、构建成功、用户真机验收分别记录；当次执行记录放入任务计划，通用文档只维护流程和行为规则。

## 身份、基线和验证范围

- [ ] 从实际base Manifest记录包名、versionName、versionCode，明确所有split DEX/资源来源。
- [ ] 记录代码基线和上一支持版本，对照其原生实现及模块变换；旧混淆类仍存在不能证明其职责相同。
- [ ] 明确用户授权范围，区分源码分析、静态检查、APK构建和功能测试；功能由用户手动测试，设备操作和哈希检查须有明确授权。
- [ ] 逐项确认schema、键、字体/歌词文件ID、DB/cache namespace、ZIP格式和插件接口是否变化。

## 功能及附属依赖

每行填写完整owner/name/参数/返回类型/static或字段类型，以及实际调用来源。没有变化也注明依据，未确认的项不得写成通过。

| 功能 | 必须追踪的接入及附属依赖 | 本版本证据/决定 | 静态状态 | 用户验收 |
|---|---|---|---|---|
| 播放器入口/恢复 | Main/Pane生命周期、当前pane、BottomSheet、封面selector/动画/baseline、背景 | 待填写 | 待确认 | 待确认 |
| 平板双栏 | 主状态、Fragment/Manager/事务、独立歌词、封面回调、可用性、视频接力和浏览器getter | 待填写 | 待确认 | 待确认 |
| 歌词面板/逐字宽度 | binding、辅助容器、RecyclerView listener注册/清理、同步度量、adapter/holder/flexbox | 待填写 | 待确认 | 待确认 |
| 自定义/自动歌词 | 当前歌曲、元数据转换/发布、TTML注入、异步重应用、重复抑制和优先级 | 待填写 | 待确认 | 待确认 |
| 模糊/CJK/字体 | 行/词事件、前景文本、合并高亮、动画结束、字体绑定/恢复 | 待填写 | 待确认 | 待确认 |
| 手机/平板玻璃 | 导航模型/getter/选择、mini登录/显示、展开回调、采样/准备/恢复和容器限制 | 待填写 | 待确认 | 待确认 |
| 名称/元数据表面 | 播放器/队列/历史/操作表/搜索/资料库/专辑/歌单/艺术家/主页、Epoxy/Compose状态 | 待填写 | 待确认 | 待确认 |
| 设置/蜂窝/DPI/数据 | 设置构造/绑定、蜂窝登录规则、DPI重建、文件和数据兼容 | 待填写 | 待确认 | 待确认 |

## Fragment必需契约

`FragmentDualPaneInventory` 是代码维护的必需角色清单。`FragmentDualPanePreflight` 先解析所有组，再开始Hook注册；JSON少写必需角色也会失败。错误按 `section.methods/fields/constructors.role` 给出，并保留完整异常日志。

| 组 | 覆盖内容 |
|---|---|
| fragmentDualPane | 主播放器、Fragment事务、封面回调、四项歌词可用性、原生binding完成方法/root字段和事务构造器 |
| fragmentLyricsRuntime | 歌词生命周期、binding、同步度量、行监听接口和注册/清理 |
| fragmentKaraokeWidth | adapter绑定、缓存宽度、holder binding/item和flexbox |
| fragmentTabletVideoHandoff | 元数据listener、视频尺寸/视图、接口浏览器getter和返回对象字段 |
| fragmentDualPane.songLayout | 显式选择布局适配时所需的ConstraintLayout参数、Guideline构造器、Barrier和binding setter |
| fragmentChrome.members | 导航/菜单/LiveData、BottomSheet、进度、生命周期、blur与mini启动；由FragmentChromeInventory在玻璃安装前完整解析，并校验调用语义 |

- [ ] 新运行依赖同时加入角色清单、profile和适配记录；数据只描述成员，策略实现留在Kotlin。
- [ ] `songLayoutPolicy` 明确选择 `native` 或 `1606-column`，不得通过缺少section默默选择布局。
- [ ] 继承/接口方法记录实际声明owner；抽象接口仅作为 `invocationOnly` 调用，Hook目标不能是abstract/bridge/synthetic。
- [ ] 安装时缓存反射，绑定时缓存资源/视图；slide、逐字动画和度量回调使用缓存成员。

## 完整布局和数据绑定

`fragmentDualPane.layoutReview` 六项证据不能为空。文字记录表示完成了对照，不能代替设备结果。

| 项目 | 对照内容 |
|---|---|
| artwork | 所有限定符布局、guideline/inset、尺寸/比例约束、实际测量和native缩放/video模式 |
| artwork sizing | 基准边长与实际 progress 内容宽度共用解析；可用高度不足时限制正方形；窗口/旋转/padding 更新后重新计算；保留暂停缩放、视频和尺寸动画；退出时仅恢复模块所有的几何 |
| metadata | 标题/副标题/合作信息/更多按钮的独立边界、锚点、margin/padding、Barrier引用/margin |
| controls | 整栏或封面宽度、控制区高度、进度/播放/footer约束、图标和触摸区域 |
| progress | 进度/音量内容边界、dimens/integers、seekbar/live-radio包含布局 |
| binding | 递归include的生成binding、重绑定setter、换歌/播放状态再次写入的margin/padding/显隐 |
| restoration | 关闭、旋转、SONG/QUEUE、换歌、销毁/重建、旧owner延迟回调和属性所有权 |

- [ ] 资源名相同但值/限定符是否变化；窗口缩成双栏后是否重复计算间距。
- [ ] 主布局和递归include是否变化；一次调整是否被binding再次覆盖，必要时接入当前视图的原生setter。
- [ ] 不对称padding和播放按钮居中在binding完成后、测量前是否一起生效；勿用尚未重测的子控件宽度计算下一次布局。普通重新布局时已显示的模块控件是否因isLayoutRequested隐藏一帧，切歌是否同时出现封面与footer跳动/闪动。
- [ ] 模块视图只加入允许的普通容器；恢复时只恢复仍由模块拥有的属性。

## 架构和交付

- [ ] Contract只持有成员/资源；Runtime注册Hook并管理活动绑定；Session管理单个视图生命周期。
- [ ] 属性恢复复用OwnedHostProperty；视图/配置替换复用HostViewSessionController，创建失败不发布部分会话。
- [ ] 宿主反射留在host-applemusic，app通过能力接口装配；core/SDK不泄漏宿主实现。
- [ ] 必需检查失败关闭对应注册作用域，日志写明失败组/角色；记录恢复原生播放的路径。
- [ ] HLE专用Hooker通过stageInstallation分组，内层安装异常同时上报作用域；独立子组、晚注册成员与安装状态汇总不遗漏。
- [ ] 玻璃读取当前双栏会话的封面就绪状态，不依赖停用实现；旧反射缓存包括完整参数/argument-type签名、空值和失败查询，历史候选顺序不变。
- [ ] 静态检查覆盖声明的辅助成员/构造器。verify-host-profile.py已扩展上述Fragment组；执行须符合本轮授权范围。
- [ ] 构建交付APK，旧映射/候选顺序保留，新tuple精确匹配。
- [ ] runtimeVerified保持false到用户验收；只勾选明确确认的场景，其余写待验收。

行为标准和通用人工验收场景参照 [功能保全矩阵](feature-preservation.md)。
