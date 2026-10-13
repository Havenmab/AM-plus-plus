# 歌词模糊维护指南

本文说明歌词模糊的现行行为、分层、宿主契约和人工验收方法，供已有构建维护和新版本适配共用。精确版本、成员描述符、能力资格与证据保存在 [host-profiles](../host-applemusic/src/main/resources/host-profiles/)；单次分析、提交和构建记录保存在任务记录中。

完整适配流程见 [宿主适配手册](host-adaptation-guide.md)，跨功能回归见 [功能保全与验收矩阵](feature-preservation.md)。

## 1. 显示行为

| 项目 | 规则 |
|---|---|
| 高亮歌词 | 正常跟随时焦点半径和边缘权重目标均为 0；实际效果可以在 300ms 内逐渐清晰 |
| 过渡 | 清晰与模糊两个方向均为 300ms 线性插值；同目标事件不重启动画，目标改变时从当前值接续 |
| 已唱歌词 | 从距离 0 起，基础半径为 `0、13、17、22px`，更远保持 22px |
| 待唱歌词 | 从距离 0 起，基础半径为 `0、8、13、17、22px`，更远保持 22px |
| 用户偏移 | 使用现有配置键、单位和范围，高亮目标 0 不受偏移影响 |
| 无三点前奏 | 有首句时间与播放位置或宿主前奏状态的证据时，文档首句清晰，后续按原曲线模糊 |
| 三点延迟显示 | 三点实际绑定、显示且有尺寸后，平滑切到间奏距离；首句未高亮时基础目标为 8px，已高亮仍为 0 |
| 间奏 | 锚点为间奏后的第一条歌词序号；最近的已唱、待唱歌词分别按距离 1；三点自身不施加模块模糊 |
| Credits | 单独分类，继承文档末尾歌词的焦点强度 |
| 歌词结束 | 保留末句期间最后有效的焦点和模糊分布，不因高亮集合变空让整页清晰；文档变化与 Seek 清除旧焦点 |
| 用户浏览 | 触摸或用户拖动后平滑解除焦点模糊；长按与用户惯性滚动继续保持浏览模式 |
| 恢复跟随 | 手指离开且列表空闲 1 秒后，按最新显示状态平滑恢复；再次触摸或滚动撤销计时 |
| 程序滚动 | 宿主自动跟随不会主动进入浏览模式 |
| 平板边缘 | 上下 10% 区域、最大 5px；浏览模式保留边缘效果 |

前奏、间奏和高亮保护分开保存。前奏确认在文档变化和 Seek 时清除，在宿主实际显示行/词或进入其他间奏时更新；不能仅因空高亮集合就推断为前奏或间奏，也不能把第一条可见行伪装成高亮。

## 2. 分层与入口

| 层 | 职责与维护入口 |
|---|---|
| `core` | [LyricBlurState](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricBlurState.kt)：事件、代际、归并、保护集合、焦点与交互；[BidirectionalBlurPolicy](../core/src/main/kotlin/dev/amenhancer/module/hook/BidirectionalBlurPolicy.kt)：原半径曲线、偏移、量化与插值 |
| `host-api` | [LyricBlurHostPort](../host-api/src/main/java/dev/amenhancer/module/hook/LyricBlurHostPort.kt)：文档、显示快照与行身份接口；不暴露宿主私有对象 |
| `host-applemusic` 契约 | [AppleMusicLyricBlurContract](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurContract.kt)：解析 profile、必需角色及类型关系；[HostPort](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurHostPort.kt)：读取宿主已经应用的显示状态 |
| `host-applemusic` 会话 | [Runtime](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurRuntime.kt)：Hook 与活动视图；[LyricBlurViewSession](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewSession.kt)：每个 root 的绑定、恢复任务和帧驱动 |
| `host-applemusic` 渲染 | [Renderer](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewRenderer.kt)：逻辑行动画与边缘几何；[EffectOwner](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurEffectOwner.kt)：效果写入与恢复 |
| `hook-runtime` | [HookRegistrationScope](../hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrationScope.kt)：PREPARING、激活与关闭 |
| 装配 | [AppleMusicHostFactory](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) 创建唯一 [能力目标](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicBidirectionalLyricBlurTarget.kt)；`app` 保留设置、门禁与能力状态入口 |

Credits 与三点语义标记由 [LyricCreditsRowResourceHook](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricCreditsRowResourceHook.kt) 接入。字体、翻译、CJK 和双栏通过现有架构协作，模糊不依赖双栏开关或双栏安装成功。

仅混淆名变化、语义一致时，主要更新精确 profile。宿主提交时序、模型或生命周期变化时，修改相应 Contract、HostPort 或 Runtime；共享状态与渲染规则继续复用。新页面族必须重新分析，不能仅凭主版本号选旧接入。

## 3. 宿主契约与显示读取

必需角色由代码维护。新增依赖时同步更新 `AppleMusicLyricBlurContract`、当前 profile 和 `scripts/verify-host-profile.py` 的独立清单；JSON 少写一个角色不能绕过安装预检。

每个构建独立核对以下链路：

- Fragment 生命周期、binding、精确 RecyclerView、子项监听、触摸和滚动状态。
- 逐行与逐字 adapter 的提交、快照、位置映射、类型、稳定 ID、文档安装、绑定及回收。
- 文档 token/reader、native 行和词 ID、首句时间与当前媒体浏览器播放位置。
- 主词、背景词、发音与背景发音四路模型及实际文字动画、开始/结束/取消释放；纯装饰动画不延长保护。
- Native、VM 刷新及媒体控制器各个实际 Seek 入口。

方法记录实际声明 owner、参数、返回类型、static 和调用方式，字段记录 owner、完整类型及对象归属。沿实际 `extends` / `implements` 关系确认继承或接口成员；使用 DEX 的擦除签名，不把反编译工具推断的泛型、类型或修复名称当作契约。

| 身份或角色 | 用途 |
|---|---|
| `nativeLineId` | 文档内原生歌词身份，用于显示保护与文档映射 |
| `lyricOrdinal` | 文档内歌词序号，用于距离计算 |
| `adapterPosition` | 当前列表位置，经 adapter 映射后才能对应歌词 |
| `nativeLineId/nativeWordId` getter | 原生对象 ID 角色，与 adapter getter 分开登记 |
| `lineId/wordId` getter | adapter 稳定 ID 角色，用于核对当前 holder 与绑定身份 |
| `nativeLineBegin/nativeLineEnd/mediaBrowser/mediaPosition` | 首句时间、文档最大结束时间与当前播放位置；前奏和歌词结束所需证据 |
| `wordAnimationTag` | 当前构建的标记接口 getter；识别主动画中的纯位移子项 |

在 adapter 实际提交方法完成后重新读取其显示集合，包括宿主因输入是旧集合子集而跳过更新的情况。宿主等待 ItemAnimator、再向 Handler 提交期间，继续使用已应用的显示状态。Native 和 VM 事件可以触发刷新，不能直接把新 raw 集合替换为显示集合，也不能因 Native Hook 安装成功永久屏蔽其他来源。

词保护读取四路模型的主文字动画，递归排除 `KARAOKE_WORD_LIFT_TAG` 位移子项；`p` 中的辉光、缩放和回落不作为高亮证据。只观察相关文字子动画的开始、结束与取消，及时触发快照刷新；不等待整个装饰动画组结束，不使用固定 grace 或修改宿主动画。模型归属复用弱身份映射，避免绑定字段改变 `hashCode` 后丢失结束通知。保护集合与焦点独立：即使焦点进入间奏，仍在显示的高亮/词行保持目标 0。

Native vector、pointer 及包装对象在 Hook 当次同步解码，只向主线程提交复制出的数值。`Complete` 空集合、`Pending`、`Partial` 和 `Failed` 分别处理；不完整读取不能解释为空高亮。没有可信显示状态时平滑解除焦点模糊，等待下一次有效事件恢复。`NO_POSITION`、未知类型和身份不一致的行不参与距离计算。

## 4. 文档、视图与交互生命周期

每个歌词 root 有独立会话，全屏和右栏可同时存在。事件按适用的视图、文档、播放与交互代际校验；所有状态在主线程串行归并，一帧使用同一份状态与行快照。

文档安装、清空、adapter 替换及自定义 TTML 重装更新文档代际，即使宿主包装对象引用未变化。Seek 更新播放代际，清理旧词流和过渡；集合缩小不能作为 Seek 证据。

末句进入有效高亮后记录当前焦点；后续真实高亮继续更新它，显示集合正常清空后转为独立 `Ending` 焦点，保留原曲线与清晰锚点的边缘保护。暂停打开或重建时，可用完整文档的最大 `getEnd()` 与当前播放位置确认结束，再使用末句焦点。读取失败不当作正常结束，文档变化和 Seek 清除旧记录；手动浏览与 1 秒恢复规则继续生效。

动画按逻辑行保存。同一歌词再次出现可衔接原进度；View 换绑时撤销旧租约，按新行状态初始化，不继承旧半径。首次取得 adapter、文档、binding 或行布局就安排首帧，包括暂停时打开歌词；初始化等待仅在后续宿主事件重读，不增加固定延迟轮询。

观察原生触摸与滚动方法，不替换 `OnTouchListener`，不消费事件。浏览期间高亮更新只更新状态；恢复任务携带会话和交互代际，再次触摸、继续滚动、换歌或销毁均使旧任务失效。

关闭视图取消帧回调、恢复任务和监听，释放仍拥有的效果。旧会话回调不得操作新视图。

## 5. 渲染、所有权与安装

焦点半径和边缘保护权重分别按 300ms 动画计算：

```text
最终半径 = 量化并限制到 0–32(
    max(动画中的焦点半径, 当前边缘半径 × 动画中的边缘权重)
)
```

边缘使用经过行 View 变换、位移与列表滚动的矩形，视口扣除 padding。几何更新不重启焦点动画。每个会话保留一个帧驱动，合并脏状态与动画推进；动画结束且状态、几何不变时停止请求连续帧。`DECAL` 效果最多缓存 32 个，量化半径及宿主效果版本不变时不重复写入。

Android 12 起在歌词视图创建前观察 SDK `View` 构造和 `setRenderEffect` 写入，记录最新已知宿主效果；模块写入带重入标记并与宿主效果合成。释放只撤销仍由模块拥有的最后写入，恢复已知的最新宿主效果。初始效果未知的既有 View 不强行接管，不使用效果 getter、隐藏 API 或 `alpha = 1f` 清理。

安装顺序固定为：精确版本资格与 Android 门禁 → 解析全部必需契约 → PREPARING scope 注册 Hook 和清理 → 全部成功后 activate → 返回能力状态。必需解析或注册失败关闭整组，报告降级并保留宿主原生行为。

## 6. 诊断与人工验收

沿用 `AMPP-LYRIC-PROBE` 入口，DEBUG 启用后才输出详细事件。每会话最多 128 条，ID/行观察最多 32 项，重复摘要与失败原因合并限量。记录构建、来源、各代际、行映射、保护集合、焦点、模式、目标/实际焦点半径及动画时间，不记录歌词文本。

| 场景 | 验收标准 |
|---|---|
| 逐行首句、三点消失 | 首句连续渐清，不上下错行；区分目标 0 与 300ms 内的实际非零半径 |
| 无三点前奏、暂停打开 | 文档首句清晰，后续原曲线模糊，首帧不依赖下一次播放 |
| 三点延迟加载 | 显示后平滑切回间奏距离；已经高亮的行仍目标 0 |
| 两行重叠、集合缩小、动画等待 | 与宿主实际显示对应，重复事件不延长动画 |
| 主词、背景词、发音和重叠词 | 保护随实际显示释放，没有旧词长期残留 |
| 带辉光的旧行 | 文字高亮退出后立即开始原 300ms 渐模糊，辉光/上浮/回落继续由宿主运行 |
| 末句结束、暂停重开 | 原模糊分布保留；换歌、清空和 Seek 不携带旧结尾焦点 |
| 拖动、长按、惯性、程序滚动 | 浏览持续解除焦点模糊，结束 1 秒后恢复；自动跟随不误入浏览 |
| 高频拖动进度 | 旧高亮、词流和恢复任务不污染新位置 |
| 快滚、字体与翻译变化 | View 复用不继承其他歌词半径 |
| 换歌、清空、同 wrapper 替换 TTML | 文档状态完整更新，不残留旧行身份 |
| 旋转、后台、全屏与右栏 | 首帧正确，会话独立，旧回调不能修改新视图 |
| 平板边缘、缩放与位移 | 边缘随当前几何和 padding 更新，高亮渐清连续 |
| 关闭、退出、契约或 Hook 缺失 | 释放模块效果，保留宿主透明度和自身效果，能力状态如实降级 |

静态契约、APK 构建、安装结果与人工场景分别记录。`runtimeVerified` 只按实际验收范围维护；一个设备或版本的局部反馈不能代替其他场景和版本。功能由使用者手测，文档清理无需重新构建 APK 或运行功能测试。
