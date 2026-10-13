# 歌词模糊重构实现与手测清单

日期：2026-10-13。分支：`codex/lyrics-blur-analysis`。问题依据见[原实现分析](lyrics-blur-refactor-analysis.md)，本文记录重构后的行为、静态接入与实际交付范围。

## 1. 当前结果

五个生产登记构建已填写独立 `lyricBlur` 契约，并通过同一个宿主工厂入口使用新的状态、视图会话与渲染实现。旧 `OpenSourceLyricBlurPort`、`LyricHighlightSession`、`LyricWordHighlightState`、`LyricBlurRenderer` 已移除，没有两套模糊 Hook 同时操作歌词行。

debug 和签名 release APK 均构建成功。**五版运行时安装及功能验收均待使用者手测**；构建成功不代表私有 Hook 在设备上安装成功，也不代替视觉、交互和跨版本验收。

重构初版分阶段提交为 `8e0cbf1`（契约/状态）、`a1edb31`（会话/渲染/迁移/清理）、`c5dab85`（ID 契约与初始化收敛）。随后以 `342e397` 修复使用者反馈的无三点前奏回归及延迟三点切换，当前两个 APK 已包含该修正，联合离线构建成功（40s）；本轮修正仍待手测。

| 交付物 | 结果 | 用途 |
|---|---|---|
| `app/build/outputs/apk/release/app-release.apk` | `:app:assembleRelease` 成功，沿用本地 release 签名配置 | 正式包名 `dev.amenhancer.module`，版本 1.6.5 / 115 |
| `app/build/outputs/apk/debug/app-debug.apk` | `:app:assembleDebug` 成功 | 调试包名 `dev.amenhancer.module.debug`，版本 1.6.5-debug / 115 |

构建使用本地 Android Studio JBR、离线依赖和 `--no-daemon`。未调用测试任务，未运行自动功能测试、性能基准、全量宿主验证脚本或哈希验证；没有操作设备。安装包、反编译源码、工具、签名凭据与构建产物不提交 Git。

## 2. 固定的视觉和交互行为

| 项目 | 新实现 |
|---|---|
| 动画 | 清晰、模糊双向 300ms，线性插值 |
| 已唱方向半径 | 距离从 0 起：`0、13、17、22px`，更远保持 22px |
| 待唱方向半径 | 距离从 0 起：`0、8、13、17、22px`，更远保持 22px |
| 配置偏移 | 保留原配置键、像素单位和 ±10 范围；高亮目标 0 不受偏移影响 |
| 普通高亮 | 焦点半径目标与边缘权重目标均为 0，允许在 300ms 内逐渐清晰 |
| 无三点前奏 | 可信前奏内，第一条文档歌词目标为 0、边缘权重为 0，后续按原未来曲线 8/13/17/22px |
| 三点延迟出现 | 原生前奏间奏位置为 0，且三点已绑定、显示并有尺寸后，切回三点距离；首句平滑转向 8px，实际高亮保持目标 0 |
| 重复目标 | 不重启动画；目标中途改变时从当前值继续过渡 |
| 新行首次出现 | 按对应逻辑歌词的状态初始化；不继承被复用 View 的旧半径 |
| 浏览歌词 | 触摸、用户拖动进入浏览模式，焦点平滑转向 0；用户惯性滚动继续保持浏览模式 |
| 恢复跟随 | 手指离开且列表空闲 1 秒后，按最新显示快照平滑恢复；再次触摸或滚动撤销计时 |
| 程序滚动 | 宿主自动跟随产生的滚动不主动开启浏览模式 |
| 平板边缘 | 上下 10% 区域，最大 5px；浏览时保留边缘效果 |

最终半径为整数并限制在 0–32：

```text
max(动画中的焦点半径, 当前几何位置的边缘半径 × 动画中的边缘权重)
```

焦点与边缘权重分别以 300ms 过渡，几何更新不重启焦点动画。边缘坐标使用经过行 View 矩阵、位移和列表滚动变换的矩形，并扣除 RecyclerView 的上下 padding。缓存最多 32 个 `DECAL` 模糊效果；量化半径和宿主效果版本都不变时不重复写入。

## 3. 状态、显示与生命周期

### 显示依据

- 在逐行、逐字 adapter 的实际提交完成后触发读取，包括宿主因输入是旧集合子集而直接返回的情况。读取的是实际提交集合，不将新 raw 集合提前当成屏幕高亮。
- 宿主等待 ItemAnimator 并向 Handler 提交期间，继续使用已有显示状态。VM 事件与 native 事件均可触发刷新；没有“native 安装成功后永久屏蔽 VM”的来源锁。
- 四路词保护分别读取当前 word holder 中的宿主模型，以主动画及尾部动画的 started/running/paused 状态判断是否仍在显示；开始、结束和取消后的结束通知触发刷新。已结束模型不因集合历史而继续保护，删除固定 grace 和累积并集补丁。
- 保护集合与焦点分离。正常焦点来自已提交行；行集合为空时，接受宿主明确的间奏位置、实际词动画或有时间证据的前奏焦点。即使间奏成立，仍在显示的词行继续保持目标 0。
- 前奏焦点依赖完整文档首句 `LyricsTiming.getBegin()` 和当前 Fragment 的 `mediaBrowser.getCurrentPosition()`。宿主已确认前奏间奏位置 0，也可确认前奏；三点是否实际显示独立记录。无三点时首句清晰，三点实际绑定、可显示且有尺寸后切到三点距离，两种状态双向按 300ms 过渡。
- 前奏确认保持到宿主真正显示行/词或进入其他间奏，避免首句 native 事件先到、实际提交延迟时提前改变焦点。文档更新和 Seek 清除旧确认；读取失败保持可读，正常歌曲中途空集合不会凭可见行产生前奏焦点。
- 三点行本身不加模块模糊。间奏锚点为其后的第一条歌词序号，最近两侧分别按距离 1；credits 单独分类，继承文档末尾歌词的焦点强度。
- native wrapper/vector 仅在 Hook 当次同步复制数值；诊断关闭时不额外遍历原始 vector。主线程队列不保存 native pointer 或 wrapper。完整空集合、等待初始化、部分解码与失败有不同结果。

### 身份与会话

`nativeLineId`、`lyricOrdinal`、`adapterPosition` 分别保存。距离使用歌词序号，adapter 映射和文档映射相互校验，并核对 holder 的类型和稳定 ID。`NO_POSITION`、未知行种类、身份不一致或不可读的显示状态保持可读，不猜测第一条可见行为高亮。

每个 Fragment root 有独立 `LyricBlurViewSession`。全屏歌词和独立右栏各自管理绑定、文档、播放、交互代际；关闭旧视图会取消帧、恢复任务和监听。文档以实际 adapter 安装/清空及 adapter 更换为边界；同一 native wrapper 再次安装也更新代际，Fragment 拒绝的安装请求不伪造文档变化。

Seek 清理旧词动画保护和待处理状态；事件、词模型所有者和 1 秒恢复任务核对适用代际。单纯的高亮集合变小不会触发 Seek。浏览期间新高亮只更新状态，不重新开启焦点模糊。

一个会话只有一个帧驱动。绑定、提交、配置、布局及几何变化合并到一帧；动画结束后停止请求连续帧。首次取得 adapter、文档与布局便安排首帧，包括暂停打开歌词；没有固定延迟寻找列表的轮询。同一逻辑行再次出现按时间衔接动画，View 换绑撤销旧效果租约，root detach 释放 View 效果并保留逻辑动画。

文档初始化等待、部分解码和失败会保留重读标记，只在下次宿主事件到达时重试；不增加常驻轮询，也不会因第一次暂时不可读而永久失去映射。

### 效果所有权与失败

Android 12 起，在歌词视图创建之前注册 SDK `View` 构造和 `setRenderEffect` 写入观察，记录已知的最新宿主效果。模块写入有重入标记，并与已知宿主效果合成。释放时只恢复模块仍拥有的最后写入；后续宿主写入不被撤销。初始效果未知的既有 View 不强行接管，待出现可信构造/写入记录再使用。没有效果 getter、隐藏 API 或 `alpha = 1f` 清理。

安装顺序为解析全部必需契约、在 PREPARING scope 注册 Hook 与清理、全部成功后 activate、返回能力状态。必需解析或 Hook 注册失败会关闭整组并报告降级，保留宿主原生歌词。模糊安装不依赖双栏开关或双栏安装成功。

## 4. 五版静态接入状态

每版 `lyricBlur` 均有 **67 个方法角色、26 个字段角色**，分别记录 owner、实际成员名、完整参数/返回类型、字段类型、方法属性及当前版本证据。名称简表只用于导航，运行时使用完整契约。

| 精确构建 | 逐行 / 逐字 | 提交 / 映射 / 快照 | binding → RecyclerView 字段 | 静态登记 | 新实现运行时 |
|---|---|---|---|---|---|
| 6.5.1 / 1583 | `U0 / A` | `T / J / B` | `i0 → l7.i5.a0` | 67 方法 / 26 字段，独立 DEX 与源码证据 | 待手测 |
| 6.5.2 / 1586 | `U0 / z` | `T / J / B` | `i0 → l7.i5.a0` | 67 方法 / 26 字段，独立 DEX 与源码证据 | 待手测 |
| 6.5.3 / 1599 | `U0 / A` | `T / J / B` | `g0 → n7.j5.Y` | 67 方法 / 26 字段，独立 DEX 与源码证据 | 待手测 |
| 7.0.0-beta / 1606 | `Y0 / A` | `O / E / w` | `n0 → q8.Y4.f0` | 67 方法 / 26 字段，独立 DEX 与源码证据 | 待手测 |
| 7.0.0-beta / 1609 | `R0 / A` | `O / E / w` | `l0 → q8.X4.d0` | 67 方法 / 26 字段，独立 DEX 与源码证据 | 待手测 |

`lyricBlur` 的 `runtimeVerified` 保持 false。1580 继续未生产启用；未知 tuple 继续不授予资格。6.x 的既有能力与数据、7.x 的双栏/玻璃/视频资格、Android 门禁、配置 schema/键和插件格式保持兼容。

静态证据除行提交外，还补齐 document token/reader、行绑定/回收、word holder 四路模型、动画监听器、RecyclerView 子项监听、触摸/滚动、Fragment 生命周期、VM 通知及媒体控制器两种 Seek 入口。1586 的逐字 `z`、1599 的 binding `g0` 与每版 seek owner 独立登记。native callback 按真实擦除签名 `(Object,Object,Object) -> Object` Hook，不用 JADX 显示的泛型签名替代字节码描述符。

原生 `nativeLineId/nativeWordId` 为无参数 int getter；adapter `lineId/wordId` 为接收列表位置并返回 long 的稳定 ID getter。两组角色独立登记，预检还校验其 owner 与返回对象/adapter 的关系、参数和返回类型，避免同名角色覆盖导致行身份读取失败。

本轮新增 `nativeLineBegin`、`mediaBrowser`、`mediaPosition`：首句时间实际声明于 `LyricsTiming.getBegin() -> int`；browser getter 的接口 owner 分别为 1583 `c9.a$c`、1586 `c9.b$c`、1599 `e9.a$c`、1606/1609 `ia.a$c`，返回各版 `E3.w/E3.x/J4.w`；位置 getter 为 `E3.D/E3.F/J4.B.getCurrentPosition() -> long`。接口调用与具体 browser 的类型关系在安装前校验，模糊能力依然独立于双栏。

## 5. 维护入口

| 层 | 入口 |
|---|---|
| 纯数据与动画 | [LyricBlurState](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricBlurState.kt)、[原半径曲线](../core/src/main/kotlin/dev/amenhancer/module/hook/BidirectionalBlurPolicy.kt) |
| 宿主协议 | [LyricBlurHostPort](../host-api/src/main/java/dev/amenhancer/module/hook/LyricBlurHostPort.kt) |
| 完整版本契约 | [AppleMusicLyricBlurContract](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurContract.kt)、[五版 profile](../host-applemusic/src/main/resources/host-profiles/) |
| 实际显示读取 | [AppleMusicLyricBlurHostPort](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurHostPort.kt) |
| 安装与事件 | [唯一能力目标](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicBidirectionalLyricBlurTarget.kt)、[Runtime](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurRuntime.kt) |
| View 生命周期 | [LyricBlurViewSession](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewSession.kt) |
| 渲染与恢复 | [LyricBlurViewRenderer](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewRenderer.kt)、[LyricBlurEffectOwner](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurEffectOwner.kt) |
| 语义资源 | [LyricCreditsRowResourceHook](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricCreditsRowResourceHook.kt)，三点标记归此入口，不再由字体负责 |

新增宿主版本时重新读取完整描述符及语义，保持 `AppleMusicLyricBlurContract` 的代码必需角色清单、profile 和 `scripts/verify-host-profile.py` 中的独立清单同步。维护脚本新增 `lyricBlur` 检查；本次只修改其源码，没有执行它。不能以缺少 JSON 角色规避预检，不能以新版本安装失败为由恢复旧集合补丁。

随旧实现删除的测试仅对应被移除的 Router、grace、集合补偿和强制 alpha 恢复；原半径、偏移、量化、插值与独立字体/资源检查继续保留。测试文件维护不代表本次运行过测试。

## 6. 诊断与手动验收

沿用 `AMPP-LYRIC-PROBE` 日志入口，只有其 DEBUG 级别启用时记录详细事件。每会话最多 128 条，ID/行观察最多 32 项，重复显示摘要合并。记录构建、来源、view/document/playback/interaction 代际、ID/ordinal/position/binding、保护集合、焦点、模式、目标/实际焦点半径和动画已用时；不记录歌词文本。初始化等待与真实读取失败分开，失败摘要按原因去重并限制数量。

所有场景需分别记录宿主构建、模块产物、Android/设备、相关开关及实际结果。以下为当前新实现的待手测清单：

| 场景 | 验收标准 | 当前结果 |
|---|---|---|
| 逐行首句、前奏三点消失 | 首句连续渐清，清晰行不向上下偏移；目标 0 与动画中非零区分 | 待手测 |
| 无三点前奏、暂停时打开 | 首条文档歌词清晰，后续恢复原模糊曲线，不依赖下一播放事件 | 已收到初版回归反馈；修正待手测 |
| 三点延迟绑定/加载 | 三点实际显示后按 300ms 切回三点曲线，首句未高亮时转向 8px；不锁在无三点状态 | 修正待手测 |
| 三点晚到但首句已经高亮 | 实际高亮行仍目标 0，不被三点或前奏替换 | 修正待手测 |
| 两行重叠、集合变小 | 宿主仍显示高亮的行不提前转向模糊 | 待手测 |
| 快节奏、列表动画等待、延迟提交 | 焦点与实际显示一致；同目标事件不延长 300ms | 待手测 |
| 逐字、背景词、发音与重叠词 | 四路实际词动画保护及时释放，暂停中的显示保持正确 | 待手测 |
| 拖动、长按停留、惯性滚动 | 浏览持续清晰；手指离开且空闲 1 秒后平滑恢复 | 待手测 |
| 程序自动跟随与再次触摸 | 自动跟随不误入浏览；再次触摸撤销旧恢复任务 | 待手测 |
| 高频前后拖动进度 | 旧词动画、旧高亮及恢复计时不污染新位置 | 待手测 |
| 暂停打开/关闭、旋转、后台返回 | 首帧不依赖下一播放事件，旧会话不改新视图 | 待手测 |
| 快滚、字体和翻译变化 | View 复用不继承其他歌词半径；宿主透明度保留 | 待手测 |
| 换歌、清空、TTML 同 wrapper 替换 | 文档边界完整更新，无旧行号/词动画残留 | 待手测 |
| 间奏与 credits | 三点自身无模块模糊，最近两侧保持原强度，credits 跟末尾歌词 | 待手测 |
| 平板独立右栏与全屏歌词 | 两个会话独立；边缘跟随缩放、位移、padding 和滚动 | 待手测 |
| 关闭模糊、退出歌词、View 重建 | 撤销仍拥有的模块效果，保留宿主自身效果与透明度 | 待手测 |
| 契约/必需 Hook 缺失 | 能力报告降级，整组关闭，宿主歌词照常工作 | 待手测 |

在各版反馈完成前不将 `runtimeVerified` 改为 true；其他能力的既有人工反馈不能作为此次歌词模糊验收结果。

## 7. 无三点前奏回归说明

重构前 `49dd9d9` 的 `resolveDisplayHighlights` 在无高亮、无三点时，使用最早可见歌词位置作焦点；因此首条可见歌词清晰，后续模糊。该兜底随旧集合补丁移除后，无三点前奏落入 `None`，所有焦点目标变成 0。使用者反馈确认了这项回归。

五版宿主 Fragment 均过滤空行集合且间隔不足 7000ms 的提交，因此短前奏正常情况下可以没有三点。修正恢复使用者选定的旧观感，以文档第一条歌词的身份和时间证据建立 `Prelude`，不使用任意可见位置作高亮。三点显隐/布局变化由现有绑定、布局与 pre-draw 路径触发快照更新；不添加固定延迟，也不锁住前奏兜底。没有增加视觉设置或修改数值曲线。
