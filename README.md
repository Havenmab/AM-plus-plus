<a id="top"></a>

<p align="center">
  <img src="docs/images/b851cbb7f571c6666f1a41377baa778b.jpg" alt="AM++ icon" width="180">
</p>

<h1 align="center">AM++</h1>

<p align="center">
  Apple Music 的 Android 增强模块：平板双栏、歌词模糊与字体、自定义歌词、歌曲名修正、液态玻璃底栏。
</p>

<p align="center">
  <a href="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml"><img src="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/Zennmn/AM-plus-plus/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Zennmn/AM-plus-plus" alt="GNU GPL v3.0"></a>
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android API 26+">
  <img src="https://img.shields.io/badge/libxposed-API%20102-7F52FF" alt="libxposed API 102">
</p>

<p align="center">
  没有 Lsposed？可以直接安装<a href="https://github.com/Zennmn/AM-plus-plus/releases/tag/embedded-2026.08.10-r1">npatch 嵌入版</a>。
</p>

<details>
<summary>目录</summary>

1. [项目简介](#项目简介)
2. [功能](#功能)
3. [效果展示](#效果展示)
4. [兼容性与限制](#兼容性与限制)
5. [安装](#安装)
6. [使用](#使用)
7. [从源码构建](#从源码构建)
8. [项目结构](#项目结构)
9. [路线图](#路线图)
10. [贡献](#贡献)
11. [隐私与权限](#隐私与权限)
12. [许可证与致谢](#许可证与致谢)

</details>

## 版本适配重构

架构和后续适配入口见 [适配手册](docs/host-adaptation-guide.md)，旧版重构验证见 [验证记录](docs/refactor-validation.md)。开发分支已为精确的 Apple Music **7.0.0-beta/1606** 实现 Fragment 接入和新平板玻璃，正在用户真机验收，详见 [1606 适配记录](docs/applemusic-700-adaptation.md)。其他 7.x 版本需要独立适配。


## 项目简介

AM++ 通过 libxposed API 102 注入 Apple Music（`com.apple.android.music`）。它不替换播放器，只在保留原有播放流程的前提下补充增强能力。

设置页嵌在 Apple Music 自己的设置列表中，入口是“AM++ 模块设置”，没有独立的桌面图标。首次启动时会把 Xposed remote preferences／remote file 中的旧配置迁移到 Apple Music 宿主私有目录，之后设置和文件都保存在那里。

## 功能

| 功能 | 默认 | 说明 |
| --- | --- | --- |
| 平板双栏播放器 | 开启 | 平板横屏时左侧播放器、右侧实时歌词，并同时抑制 Editorial Video。 |
| 强制显示蜂窝数据入口 | 关闭 | Apple Music 6.5.2/1586、6.5.3/1599 和开发分支的 7.0.0-beta/1606 上保留原生数据分组，并恢复蜂窝使用偏好的可用性判断。独立于双栏和歌曲名修正；重开 Apple Music 后显示。 |
| 双向歌词模糊 | 开启 | 当前高亮行清晰，前后歌词按距离逐渐模糊；手动滚动时暂停，停止约 1 秒后恢复。需要 Android 12 及以上。 |
| CJK 长尾歌词动画 | 开启 | 让 CJK 歌词复用 Apple Music 原生的 rush-gradient 动画。需重开 Apple Music。 |
| 歌词模糊半径偏移 | `0px` | 在基础半径上增减 `-10..10px`。 |
| 歌曲名显示修正 | 关闭 | 地区替换：不开启地区替换（跟随账号）/ 简体中文（中国）/ 简体中文（美国）/ 繁体中文（香港）/ 繁体中文（台湾）/ 韩语（韩国）/ 日语（日本）。选中地区后普通浏览、搜索与推荐请求会改到该地区；电台、歌词与播放请求仍走账号地区。需重开 Apple Music。 |
| 替换中日韩歌曲信息为原地区原名 | 开启 | 把中日韩歌曲还原为原地区语言的歌名与歌手名，可与地区替换叠加。需重开 Apple Music。 |
| 创建检索库以提升替换体验 | 开启 | 缓存已解析的地区/原名歌曲信息，冷启动后不必重新抓取。需重开 Apple Music。 |
| 自定义歌词 | 关闭 | 按 Apple Music ID 注入 TTML，支持手动 TTML、AMLL、AM-Lyrics、Lunabeat 导入与 ZIP 备份恢复。 |
| 自动实时补全 | 开启 | 自定义歌词开启后，为缺词、非逐字或缺翻译的歌曲自动查找歌词服务。 |
| 歌词字体 | 关闭 | 导入 TTF/OTF 应用到播放器歌词，可一键恢复原字体。 |
| 液态玻璃底栏 | 关闭 | Android 13 及以上：6.5.2/6.5.3 保留手机与双栏平板横屏的原有玻璃布局；开发分支的 7.0.0-beta/1606 使用新版手机底导航/平板顶部导航与独立 mini，支持单栏和双栏。1606 真机验收中。 |
| 底栏高度 | `16dp` | 6.5.x 液态玻璃附加项：底栏距屏幕底部的距离 `0..48dp`，需重开 Apple Music。7.0 使用原生导航/mini 边界，这个旧几何配置不改变新版布局。 |
| 底栏背景模糊强度 | `4dp` | 液态玻璃附加项：底栏与迷你播放器的背景模糊半径 `0..24dp`。仅在“液态玻璃底栏”开启时显示和生效，需重开 Apple Music。 |
| 平板底栏补偿 | 关闭 | 平板底栏显示异常时使用的兼容选项；液态玻璃底栏开启期间由玻璃接管底栏几何，此开关不生效，且设置行在「液态玻璃底栏」开启时隐藏。 |
| Apple Music 内部 DPI | 跟随系统 | 只改 Apple Music 进程的资源密度，`160..640`，`0` 表示跟随系统。需完全重开 Apple Music。 |

## 效果展示

### 自定义歌词注入

<p align="center">
  <img src="docs/images/bf32d15a3519cef0051d8a208b58ab42.jpg" alt="自定义歌词注入示例一" width="48%">
  <img src="docs/images/918d640313475d68cf66fff0e63f4e19.jpg" alt="自定义歌词注入示例二" width="48%">
</p>

### MiSans 字体替换

<p align="center">
  <img src="docs/images/537369a74adc232f855165263c9ff1cc.jpg" alt="MiSans 字体替换前后对比" width="900">
</p>

### 平板双栏播放器与歌词模糊

<p align="center">
  <img src="docs/images/tablet-dual-pane-player-open-source-blur.png" alt="平板横屏双栏播放器与歌词模糊" width="900">
</p>

### 液态玻璃底栏

<p align="center">
  <img src="docs/images/liquid-glass-demo.jpg" alt="液态玻璃底栏与迷你播放器（主页与资料库）" width="720">
</p>

## 兼容性与限制

| 项目 | 支持范围 |
| --- | --- |
| Android | 8.0（API 26）及以上；双向歌词模糊需 12（API 31）及以上；液态玻璃底栏需 13（API 33）及以上 |
| Xposed 框架 | 实现 libxposed API 102、remote preferences 和 remote file 的框架 |
| Apple Music | `6.5.1 (1583)`、`6.5.2 (1586)`、`6.5.3 (1599)` |

## 安装

### Xposed 模块（推荐）

前置条件：已安装 Apple Music，以及一个支持 libxposed API 102 的 Xposed 框架。判断框架兼容性以框架核心报告的 API 版本为准，不要只看 Manager 应用版本。

1. 从 [Releases](https://github.com/Zennmn/AM-plus-plus/releases/latest) 下载并安装 AM++ APK。
2. 在 LSPosed 或兼容的 Xposed 管理器中启用 **AM++**。
3. 作用域只勾选 Apple Music（`com.apple.android.music`）。
4. 强制停止并重新打开 Apple Music。
5. 打开 Apple Music → 设置 → “AM++ 模块设置”，确认页面显示已连接 libxposed API 102 后再修改设置。

### npatch 嵌入版

没有Lsposed的话可以选择<a href="https://github.com/Zennmn/AM-plus-plus/releases/tag/embedded-2026.08.10-r1">npatch 嵌入版</a>，无法和官方版本共存。

## 使用

每个开关的含义见上方[功能](#功能)表，下面只写需要多步操作的流程。

### 自定义歌词

1. 进入设置页的“自定义歌词”，打开“自定义歌词替换”。
2. 点“获取 ID”，从当前播放的歌曲读取 Apple Music ID、标题和艺术家；也可以手动填写 ID。
3. 选择歌词来源：粘贴或导入本地 TTML，或按 Apple Music ID 从 AMLL、AM-Lyrics、Lunabeat 导入。
4. 保存映射并启用该歌曲。
5. 强制停止并重新打开 Apple Music。

自定义歌词支持编辑、删除和按名称或 Apple Music ID 搜索，也支持 ZIP 备份与恢复，恢复时可以选择覆盖冲突项或保留当前版本。校验不通过的 TTML 会被拒绝，此时保留 Apple Music 原歌词。从 AMLL 取回的 TTML 会先转换成 Apple Music 格式再填入编辑框。

手动编写 TTML 时可参考 [Apple Music TTML 格式说明](docs/apple-music-ttml-format.md)。

“自动实时补全”开启后，播放中检测到原生歌词缺失、不是逐字时间轴、或外语逐字歌词缺翻译，且没有可用手动歌词时，才会请求候选歌词；关闭后播放过程中不再请求。手动导入始终由用户主动触发。

Lunabeat 会缓存 manifest 和歌曲索引，只在远端 revision 变化时重新下载。

### 歌词字体

1. 在设置页的“歌词字体”中选择 TTF 或 OTF 文件。
2. 等导入完成，然后强制停止并重新打开 Apple Music。
3. 需要还原时点“恢复原字体”，再重开 Apple Music。

字体只覆盖播放器歌词，不修改系统字体或设置页字体。

### 液态玻璃底栏

打开“液态玻璃底栏”，强制停止并重新打开 Apple Music。生效范围是手机布局与开启“平板双栏播放器”的平板横屏（平板竖屏或双栏关闭时保持原生界面）；平板形态下底栏与迷你播放器并排在同一条底部胶囊行内（底栏在左、迷你在右、同高居中），整行约 2/3 屏宽居中、左右留白等长，展开播放器时右下小胶囊四边插值摊开为全屏。底栏使用 AndroidLiquidGlass 的 LiquidBottomTabs，迷你播放器使用 LiquidButton 材质和按压形变，播放控件仍是原生实现；页面背景通过共享硬件 RenderNode 采样。宿主接入与维护流程见 [宿主适配手册](docs/host-adaptation-guide.md)。

开启后可微调两个附加项（关闭液态玻璃时不显示、也不生效）：“底栏高度”（`0..48dp`，即底栏距屏幕底部的距离，同时调整内容底部留白与播放器 peek 高度）与“底栏背景模糊强度”（`0..24dp`，同时作用于底栏面板和迷你播放器）；两者均为重开 Apple Music 后生效。每项右上角有小恢复按钮，可单独一键回到默认值（`16dp` / `4dp`）。

### 歌曲名显示修正（地区替换）

打开“歌曲名显示修正”总开关后，用“地区替换”选择地区，并用独立的“歌曲名称修正”开关决定是否按歌曲原地区显示原名；两项可自由组合，改完重开 Apple Music。总开关关闭时整个功能不生效，请求跟随 Apple Music 账号地区。

选中一个地区（而不是“不开启地区替换”）后，模块会把 Apple Music 的普通浏览、搜索与推荐请求改写到你选的地区，共五个改写层：宿主 MediaApi 的 storefront 字段、MediaApi 请求参数表、内容请求的 URL 与 `Accept-Language` / `X-Apple-Store-Front` 请求头、目录直连请求执行器的参数，以及 amp-api 内容请求拦截器。**电台、歌词和播放相关请求始终回退到账号真实地区**，避免把账号本来能播的歌曲变成不可用。

> [!WARNING]
> 地区替换只改变**内容展示与浏览目录**，不会解锁账号所在地区不可用的歌曲或服务；这类歌曲/服务只能浏览。它会把 Apple Music 的普通请求改道到其他地区，可能影响加载速度，也可能因为服务端策略变化而失效。

两个附加开关与地区选择相互独立，且都在总开关开启时才生效：

- **替换中日韩歌曲信息为原地区原名**：把中日韩歌曲还原为原地区语言的歌名与歌手名；旧配置里“按歌曲原地区修正”的行为在 schema 16 升级后由这个开关承接（默认按旧档案推导）。
- **创建检索库以提升替换体验**：把解析结果写入 SQLite，冷启动后不必重新抓取；关闭后每次冷启动都会重新查询。

改写层按 Apple Music 版本分级。MediaApi storefront 字段、MediaApi 请求参数和内容 HTTP 三层在 `6.5.1/1583`、`6.5.2/1586`、`6.5.3/1599` 和 `7.0.0-beta/1606` 都有声明（`6.5.2` 的内容 HTTP 目标通过兼容候选链复用 `6.5.1` 的目标）；目录执行器参数层与 amp-api 拦截层**只在 `6.5.3/1599` 和 `7.0.0-beta/1606` 有已验证目标**。`6.5.1/6.5.2` 上这两层不安装（日志记录“本版本无已验证目标”，不猜目标），改写弱于 `6.5.3`。`6.5.3` 的目标沿用该包已验证的结果；`7.0.0-beta` 的目标由该版本 DEX 推导，并用 `scripts/verify-host-profile.py` 校验（7.0 原包 262 项、6.5.3 原包 55 项，均 0 失败）。

本次移植**没有做真机验证**：7.0 只做过描述符与结构校验，五个改写层的实际请求结果、地区切换后的加载速度与账号回退都未在设备上确认。按项目惯例，这里只能记为静态通过。

## 从源码构建

环境：JDK 17、Android SDK 37、Android Build Tools 37.0.0，以及项目自带的 Gradle Wrapper。项目用 Kotlin 编写，构建基于 Android Gradle Plugin，模块加载与跨进程配置使用 libxposed API/service。

Windows：

```powershell
.\gradlew.bat test :app:lintDebug :app:lintVitalRelease :app:assembleRelease
```

Linux 或 macOS：

```bash
chmod +x gradlew
./gradlew test :app:lintDebug :app:lintVitalRelease :app:assembleRelease
```

CI 还会检查玻璃渲染器源码，并对 `glass` 执行 Lint（PR 构建用 `assembleDebug` 代替 `assembleRelease`）。

生成的 Release APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

生成正式签名的 Release APK 需要签名配置：把 `keystore.properties.example` 复制为被 Git 忽略的 `keystore.properties`，填写 keystore 路径、密码与别名（也可以用 `AMPP_RELEASE_STORE_FILE` 等环境变量覆盖）。没有签名配置时仍可构建 Debug APK。

## 项目结构

```text
app/src/main/java/        模块入口、依赖装配、设置页、功能编排与 Android 存储
app/src/main/resources/   libxposed 模块元数据
core/                     纯配置/歌词业务、网络来源、缓存与布局策略
host-api/                 语义宿主能力、事件、安装结果与订阅
hook-runtime/             libxposed 包装、注册作用域与日志
host-applemusic/           版本 profile、反射/DexKit 解析与原生宿主接入
glass/                    AndroidLiquidGlass 渲染器（固定提交纳入）
backdrop/                 上游 Backdrop 库
docs/images/              演示图
docs/                     当前维护文档、功能矩阵与验证记录
scripts/                  可选的真机回归、录屏分析与 host profile 校验脚本
```

`scripts/` 中的设备脚本需要 ADB；部分液态玻璃检查还需要 root、Python 和 OpenCV，并按参考设备的分辨率写死了坐标，运行前用 `-Serial`、`-Device` 或 `ANDROID_SERIAL` 指定设备。`verify-host-profile.py` 不需要设备，只读校验 APK 里的 profile 符号。详见 [scripts/README.md](scripts/README.md)。

## 路线图

- [x] 平板横屏双栏播放器
- [x] 双向歌词模糊
- [x] 自定义歌词注入与备份恢复
- [x] 歌词字体导入与恢复
- [x] 液态玻璃底栏与迷你播放器（手机；开启双栏的平板横屏生效范围待真机验收）
- [x] 歌曲名显示修正
- [x] 地区替换（移植自 HyperLyrics Enhanced：六个地区档位 + 原名还原 + 检索库；仅静态验证，待真机验收）
- [ ] 补齐 Apple Music 6.5.3 的两处降级
- [ ] 为 6.5.1/1583 与 6.5.2/1586 取证目录执行器与 amp-api 目标（当前这两层仅 6.5.3/1599 与 7.0.0-beta/1606 可用）
- [ ] 持续适配后续 Apple Music 版本

## 贡献

欢迎提交 Issue 和 Pull Request。改动代码的 PR 请至少运行 `test`、`lintVitalRelease` 和 `assembleRelease`；涉及界面行为时，请在 Issue 或 PR 中附上设备型号、Android 版本、Apple Music 版本以及截图或录屏。适配 Apple Music 新版本前，请先读 [宿主适配手册](docs/host-adaptation-guide.md)。完整文档入口见 [文档目录](docs/README.md)。

## 隐私与权限

- 模块只声明 `INTERNET` 权限，用于设置页中用户主动触发的 AMLL、AM-Lyrics、Lunabeat 歌词导入，以及开启“自动实时补全”后符合条件的播放期歌词请求。
- 不申请存储或通知运行时权限，本地文件通过 Android 文件选择器读取。
- 模块不含分析服务，也不含独立入口 Activity。
- 首次迁移前的配置来自 Xposed remote preferences／remote file；迁移后普通设置、歌词索引和字体文件保存在 Apple Music 宿主私有目录。

## 许可证与致谢

本项目以 [GNU General Public License v3.0](LICENSE) 开源。第三方代码与依赖仍分别遵循其原始许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

- [AMLyricBlur](https://github.com/a23bc/amlyricblur)：双向歌词模糊核心的移植来源。
- [AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)：玻璃折射、高光、色散与参考交互。

<p align="right">(<a href="#top">返回顶部</a>)</p>
