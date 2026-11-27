# 11 — AMLL 歌词页作为「可嵌入渲染组件」的可行性评估

> 评估对象：`third_party/applemusic-like-lyrics`（浅克隆，HEAD `f329796`，core 版本 0.7.2）
> 评估目标：给定「时间 + 歌词文档 + 封面 + 播放状态」，AMLL 的歌词页能否被独立驱动成一枚可嵌入的渲染组件；代价是什么。
> 只读研究：未修改 AMLL 源码、未改 AM++ 工作树、未提交。

---

## 0. 一句话结论

**能独立驱动，而且成本比想象低（4 个方法 + 1 个外部 rAF 循环）；但代价不在代码里，在「渲染表面」和「许可」两件事上：它需要一整块 Chromium 合成表面（WebView），而这块表面与 AM++ 今天「原生 View 内嵌 Apple Music 歌词树」的架构互斥；同时按 AGPL 的保守解释，把它塞进 GPL-3.0 的 AM++ 会把整个发布物拖进 AGPL。**

| 维度 | 判定 | 一句话依据 |
| --- | --- | --- |
| 对外 API | ✅ 干净、可脚本化 | `LyricPlayer`=`DomLyricPlayer`，只有 `setLyricLines`/`setCurrentTime`/`update`/`pause`/`resume` 五个真入口（`packages/core/src/lyric-player/base/index.ts:611,643,1162,1138,1148`） |
| 时间驱动 | ✅ 外部推 | 组件内部**没有** rAF 主循环，必须宿主每帧调 `update(delta)`（`dom/index.ts:224`；`docs/src/content/docs/guides/component/quickstart.mdx:88-99`） |
| 封面 | ⚠️ 不在歌词组件里 | 歌词组件没有 `setCover`；封面属于另一个组件 `BackgroundRender.setAlbum()`（`bg-render/index.ts:76-81`） |
| 播放状态 | ⚠️ 只有 pause/resume | 没有 `setPlaying`，只有 `pause()`/`resume()`/`getIsPlaying()`（`base/index.ts:1138-1154,624`） |
| 渲染依赖 | ⚠️ 重 | DOM+CSS 遮罩/混合/逐行 blur、Web Animations（每词一条）、可选 WebGL1 背景（`bg-render/*`） |
| WebView 可行性 | ✅ 主流特性齐备，两条内建降级 | `CSS.supports("mask-image")` 与 `mix-blend-mode: plus-lighter` 都做了兜底（`dom/index.ts:74-79`） |
| 性能（2K 平板 60Hz） | 🟡 可行但需限档 | 逐词遮罩层 + 逐行 blur + 亮度副本是三层叠加；见 §3.4 |
| 性能（120Hz） | 🟠 不建议原生 120 | 每帧成本不高，但每帧都要重建 blur/混合层；建议背景 30–60fps、歌词 60Hz |
| 打包 | 🟡 需自己 bundler | 官方不发 CDN/IIFE（`quickstart.mdx:8`）；实测全量 min+gz = 48.6 KB，**只做歌词的 IIFE = 86 KB min / 25 KB gz + 4.7 KB CSS** |
| 许可 | 🔴 最大风险 | AMLL=AGPL-3.0-only，AM++=GPL-3.0；见 §5，保守结论：整包按 AGPL 对外，或别把代码搬进来 |
| 与 AM++ 现状 | 🔴 架构冲突 | AM++ 目前**零 WebView**（全仓 `grep -i webview` 无命中），歌词是原生 View 注入 |

---

## 1. 对外 API

### 1.1 导出面

`packages/core/src/index.ts:1-6` 只有 5 行，全部导出汇聚于此：

```ts
export * from "./bg-render/index.ts";      // 背景渲染器全家桶
export type * from "./interfaces.ts";      // LyricLine / LyricWord / OptimizeLyricOptions
export * from "./lyric-player/index.ts";   // 歌词组件 + 布局枚举
export * as spring from "./utils/spring.ts";
export { Duration, MediaTime } from "./utils/time.ts";
```

`LyricPlayer` 只是 `DomLyricPlayer` 的别名（`packages/core/src/lyric-player/index.ts:8-13`）：

```ts
export { /** 默认导出的歌词播放组件 */ DomLyricPlayer as LyricPlayer };
```

发布产物的 `.d.mts` 里对外符号约 70 个，除组件外还漏出了 `LayoutCalculator`、`TimelineController`、`SeekDetector`、整套 palette 色彩函数（`amll-core.d.mts:2574` 的 `export {...}` 一行）——**API 面偏大，但没有「框架耦合」**：不用 React/Vue，`getElement()` 拿到一个 `HTMLElement` 挂进任意容器即可（`base/index.ts:1238-1240`）。

### 1.2 驱动方法真名（纠正题面假设）

| 题面猜测 | 实际 | 证据 |
| --- | --- | --- |
| `new LyricPlayer()` | ✅ | `lyric-player/index.ts:8-13` |
| `setLyrics(…)` | ❌ 叫 `setLyricLines(lines, initialTime?)` | `base/index.ts:611-618` |
| `setCurrentTime(t)` | ✅ 且第二个参数是 `isSeek` | `base/index.ts:643-654` |
| `setPlaying(bool)` | ❌ 没有；用 `resume()`/`pause()`/`getIsPlaying()` | `base/index.ts:1138-1154`、`:624-626` |
| `setCover(url)` | ❌ **完全没有**；封面是 `BackgroundRender.setAlbum(url\|img\|video, isVideo?)` | `bg-render/index.ts:76-81` |
| 每帧更新 | `update(deltaMs)`，**外部**驱动 | `base/index.ts:1162-1166` |
| 销毁 | `dispose()` | `base/index.ts:1241-1250` |

其余都是"配置类"方法（可在运行时改，不需要重建）：`setEnableSpring`/`setEnableBlur`/`setEnableScale`/`setWordFadeWidth`/`setAlignAnchor`/`setAlignPosition`/`setOverscanPx`/`setHidePassedLines`/`setAlwaysPostpositionBackground`/`setMaskObsceneWords`/`setOptimizeOptions`（`base/index.ts:351-595, 442-529, 1222-1231`）。

### 1.3 最小驱动代码

README 给的是四行（`packages/core/README.md:43-52`）：

```ts
import { LyricPlayer } from "@applemusic-like-lyrics/core";
import "@applemusic-like-lyrics/core/style.css";     // 必需样式，必须一起打包
const player = new LyricPlayer();
document.body.appendChild(player.getElement());
player.setLyricLines([]);   // 歌词
player.setCurrentTime(0);   // 每帧推
player.update(0);           // 每帧驱动动画
```

真正"可运行"的最小闭环在 `packages/playground/core-legacy/src/test.ts:297-331`（作者自己的调试页，最接近真机形态），抽出来是：

```ts
const player = new DomLyricPlayer();
host.appendChild(player.getElement());
let last = -1;
const frame = (t: number) => {
  if (last === -1) last = t;
  if (!audio.paused) player.setCurrentTime((audio.currentTime * 1000) | 0); // 整数毫秒
  player.update(t - last);          // 唯一动画驱动
  last = t;
  requestAnimationFrame(frame);
};
requestAnimationFrame(frame);
audio.addEventListener("play",  () => player.resume());
audio.addEventListener("pause", () => player.pause());
```

同样的形状出现在三处，可以互相印证这是官方认定的驱动模型：
- `packages/react/src/lyric-player.tsx:261-281`（React 绑定的内部 rAF 循环，只调 `update`；`currentTime` 走另一个 effect `:326-334`）
- `packages/docs/src/content/docs/guides/component/quickstart.mdx:88-110`（原生示例，含 `seeked` 时补一次 `setCurrentTime(t, true)`）
- `packages/docs/src/content/docs/guides/component/background.md:84-96`（强调背景**自带**循环，歌词必须手动逐帧）

### 1.4 输入数据模型

`LyricLine`（`packages/core/src/interfaces.ts:47-65`）：

```ts
interface LyricLine {
  words: LyricWord[];            // 逐词时间（毫秒）
  translatedLyric: string; romanLyric: string;
  startTime: number; endTime: number;
  isBG: boolean;                 // 背景人声行
  isDuet: boolean;               // 对唱（右对齐）
}
interface LyricWord { startTime; endTime; word; romanWord?; obscene?; ruby?: LyricWordBase[] }
```

时间全部是**毫秒**（`interfaces.ts:29-32`），且 React 绑定文档特别注明 `currentTime` **必须是整数**（`react/src/lyric-player.tsx:96-99`）——AM++ 侧从 `PlaybackController` 拿到的 `long position` 正好是整数毫秒，天然适配。

AM++ 已经有自己的 TTML 解析与 `AmllTtmlFormatConverter`（`core/jvmTest/kotlin/dev/amenhancer/module/lyrics/AmllTtmlFormatConverterTest.kt`），所以**不需要**引入 `@applemusic-like-lyrics/lyric`/`ttml`，只要在 Kotlin 侧生成等价的 `LyricLine[]` JSON 即可，这也顺带砍掉一个 AGPL 包。

### 1.5 必须遵守的时序契约（不遵守会出现"歌词不走"）

1. **每帧调用 `update(delta)`**，且 `delta` 是"距上次调用"的毫秒差（`base/index.ts:1156-1166`）。内部会把 `delta` 钳到 `MAX_FRAME_DELTA = 100ms`（`base/consts.ts:131-136`），因此页面挂起恢复不会过冲。
2. **页面不可见时组件自己罢工**：`DomLyricPlayer.update` 里 `if (!this.isPageVisible) return;`（`dom/index.ts:233`），`isPageVisible` 由 `pageshow`/`pagehide` 维护（`base/index.ts:203-209,320-321`）。WebView 被遮挡/后台时 rAF 本身也会停，两者行为一致，不冲突。
3. **进度推送越勤越好**：`setCurrentTime` 的注释明确"调用频率越快越准确越好"，并说明内部用"实际推进量 vs 应有推进量"自动识别 seek（`base/index.ts:628-654`、`setEnableAutoSeekDetection` `:424-436`）。若你的进度源精度差（例如 1 秒一跳），会被误判成 seek 而走"瞬移"路径——AM++ 有 `PlaybackController` 的毫秒级位置，没问题。
4. **`setLyricLines` 传入的数组之后不得修改**（`base/index.ts:604-611` 的 `@param` 说明）；内部会 `structuredClone` 两份（`base/lyric-data-manager.ts:47,109`）。
5. `dispose()` 必须调（`base/index.ts:1241`），否则 `ResizeObserver`/`MutationObserver`/`AbortController` 与 `ResizeObserver.observe` 的每行元素都会泄漏（`base/index.ts:218-283,294-318`）。

---

## 2. 渲染依赖

### 2.1 DOM + CSS：到底用了哪些"高级"特性

**（重要纠正）core 里没有 `backdrop-filter`。** 全仓 `backdrop-filter` 只出现在文档站和 playground 的 UI 外框（`packages/docs/src/components/ApiTester/styles.css:11-12` 等），与歌词渲染无关。歌词的"模糊"是**逐行 `filter: blur()`**，上限 5px。

| 特性 | 用在哪 | 证据 |
| --- | --- | --- |
| `contain: strict` | 播放器根元素（隔离布局/绘制） | `src/styles/index.css:12` |
| `mix-blend-mode: plus-lighter` | 播放器根 + 亮层/暗层副本 | `src/styles/index.css:13`；`lyric-player.module.css:156`；`@supports` 兜底 `:192-194` |
| `contain: layout style paint` + `content-visibility: auto` | 每个歌词行 | `lyric-player.module.css:41-43` |
| `mask-image` / `mask-size` / 动画化的 `mask-position` | 逐词擦除（核心视觉） | `animation/mask/animator-web.ts:104-118`（写样式）、`:83-89`（WAAPI） |
| `filter: blur(Npx)` | 每行按距焦点距离分级模糊 | `dom/lyric-group.ts:189-193`；档位计算 `base/index.ts:1065-1087` |
| `will-change: transform` + `transition` | 行容器 | `lyric-player.module.css:15-19` |
| CSS 变量 `--amll-player-time` | 遮罩回退路径的时间源 | `dom/index.ts:227-232` |

### 2.2 Web Animations（WAAPI）：不是"顺带用"，是主力

- 逐词遮罩关键帧：**每个词一条** `Animation`，`fill: "both"`、手动 `pause()`，由 `setCurrentTime(timeRelative)` 直接写 `a.currentTime = t`（`animation/mask/animator-web.ts:23-52,71-99`）。
- 每个词的轻微上浮：`createFloatAnimation`（`animation/float/index.ts:36-64`）。
- 强调词（CJK 逐字发光）：**每个字符两条**动画，其中一条动 `textShadow`（`animation/emphasize/index.ts:155-171,193-242`）。
- 亮度层冷启动交叉淡入：`line-brightness.ts:127-148`。

一句台词：**一个 20 词的动态歌词行 = 20 条 float 动画 + 20 条 mask 动画；若该行带强调，字符数 × 2 条再加一层。** 这是它"看起来像 Apple Music"的全部秘密，也是它 DOM 层数与动画数远超普通歌词组件的根因。

### 2.3 WebGL（`bg-render`）：完全独立、可整个不装

- 只是"把封面画成流动背景"，与歌词组件**没有数据依赖**；宿主自己决定是否创建（`docs/.../background.md:5`：`BackgroundRender` 负责背景，歌词组件只管歌词）。
- 上下文是 **WebGL1**（`mesh-renderer/index.ts:1088` `getContext("webgl")`；titanium/isolation 同样 `getContext("webgl")`），不是 WebGL2；mesh 渲染器还会探测 `EXT_color_buffer_float`/`EXT_float_blend`/`OES_texture_float*`，缺了只 `console.warn` 不崩（`:726-739`）。
- 画布尺寸 = CSS 尺寸 × `devicePixelRatio` × `renderScale`（默认 0.75）（`bg-render/base.ts:74-99`）。**2K 平板（DPR 2，1280×800 CSS px）在 `renderScale=0.75` 下就是 1920×1200 的后备缓冲**，每帧一条全屏 quad + 一张 mesh 网格（`mesh-renderer/index.ts:955-1073`）。
- 自带走 rAF + 自限帧率（默认 `maxFPS = 60`，`setFPS` 可调；`staticMode` 可在封面切换动画结束后彻底停帧）（`mesh-renderer/index.ts:696,870-901,1104-1111`）。

### 2.4 内建的两条降级路径（这是它最值得抄的设计）

1. **遮罩**：`supportMaskImage = CSS.supports("mask-image","none")`（`dom/index.ts:78`）。不支持则换 `CalcMaskAnimator`，把时间轴折进一条 `mask-position: clamp(…, start + (var(--amll-player-time) - startTime) * speed, 0)`，由播放器每帧写一个 CSS 变量驱动（`animation/mask/animator-calc.ts:40-51` + `dom/index.ts:227-232`）。**没有 WAAPI 也能跑**。
2. **混合**：`supportPlusLighter`（`dom/index.ts:74-77`）+ `@supports (mix-blend-mode: plus-lighter)`（`lyric-player.module.css:192-194`）。不支持则对比度降一档（0.3 而非 0.2 透明度），视觉损失有限。

### 2.5 放进 Android WebView（Chromium）会怎样

| 能力 | 最低门槛（Chromium） | 2K 平板（WebView 常年自动更新） | 退化表现 |
| --- | --- | --- | --- |
| `contain: strict` | 52 | ✅ | — |
| `content-visibility: auto` | 85 | ✅ | 老版本忽略该声明，只是少了"离屏跳过绘制"的优化 |
| `Intl.Segmenter` | 87 | ✅ | 缺失时 `wordSegmenter`/`graphemeSegmenter` 为 `null`（`base/line.ts:31-42`），换行平衡与逐字强调降级 |
| `ResizeObserver` | 64 | ✅ | 缺失直接崩（无兜底，构造函数即 new） |
| `mix-blend-mode: plus-lighter` | ~100（BCD 未单列该值；2022-03 的 BCD PR 记录 Chrome/Firefox/Safari 均已发布；chromestatus 有独立条目） | ✅ | 走 `@supports` 分支，对比度降低 |
| `mask-image`（无前缀） | **120**（Chrome 120 发布日志把 CSS masking 列为新特性） | 视设备而定 | <120 时 `CSS.supports` 为 false → 走 calc 回退：**能用，但每帧改 CSS 变量会让所有带遮罩的词重算样式**（见 §3.4） |
| WebGL1 + float 扩展 | 很老即有 | ✅（Mali/Adreno 均支持） | 缺扩展只 warn；完全无 WebGL 时 `MeshGradientRenderer` 构造即抛（`mesh-renderer/index.ts:1088-1089`），需在创建前问 `MeshGradientRenderer.isSupported()`（`:680-682`） |

补充两点 WebView 特有约束：
- **跨 WebView 的混合不成立。** `mix-blend-mode` 只在同一合成上下文内生效。若把 WebView 做成透明浮窗盖在 Apple Music 原生封面之上，歌词与"原生像素"之间**不会**发生 plus-lighter 叠加——只有 WebView 内部的层（亮层/暗层/文本）之间会。想要 AMLL 那种"歌词压在流动封面上"的效果，封面必须也进 WebView（即必须用 `bg-render`，把封面 `Bitmap` 以 `dataUrl`/`asset` 方式给进去）。
- **`fetch` 本地资源**：`setAlbum` 走 `fetch(url)` → `blob` → `createImageBitmap`（`mesh-renderer/index.ts:1147-1157`）。`file://` 下 `fetch` 被拒；必须用 `WebViewAssetLoader`（`https://appassets.androidplatform.net/...`）或 `loadDataWithBaseURL`，否则封面加载失败并重试 5 次后放弃（`:1144-1187`）。

### 2.6 "无背景 / 低配"模式：有，而且是分层可关的

| 开关 | 效果 | 证据 |
| --- | --- | --- |
| 不创建 `BackgroundRender` | 省掉全部 WebGL | `docs/.../background.md:5,31-58` |
| `setEnableBlur(false)` | 关掉逐行 `filter: blur()`（**最值钱的一项**） | `base/index.ts:450-454`、`:1071` |
| `setEnableSpring(false)` | 弹簧 → CSS `transition`，CPU 归零，效果变"直给" | `base/index.ts:587-595`；样式 `lyric-player.module.css:281-288` |
| `setEnableScale(false)` | 关掉非焦点行缩放 | `base/index.ts:363-366` |
| `setWordFadeWidth(0.0001)` | 渐变近似"硬切"，减弱遮罩重绘压力 | `base/index.ts:351-353` |
| `setOverscanPx(0)` | 只渲染视口内行，减少 DOM/动画 | `base/index.ts:573-575` |
| `setStaticMode(true)` / `setFPS(n)` / `setRenderScale(0.5)` | 背景停帧 / 限帧 / 降分辨率 | `bg-render/index.ts:51-63` |

---

## 3. 性能预期

### 3.1 每帧到底做什么

```
宿主 rAF ──► player.setCurrentTime(t)          // 只在"时间线变化"时才重排（line 切换/间奏/seek）
              └─ timeline.sync → calcLayout(reason)
                   ├─ 逐行 setTransform(y, …, opacity, blurLevel, inBrightnessWindow)   // 只写字段，不碰 DOM
                   └─ 更新 spring 目标 / 参数
         └─► player.update(delta)              // 每帧必调
              ├─ bottomLine.update / interludeDots.update
              └─ if (页可见):
                   for 每个 group:  group.update(delta)     // 解析解弹簧积分（posY / bgSlideY / scale）
                   for 每个 group:  group.commitChanges()   // isInRenderRange → 脏才写 transform/opacity/filter
```

关键点：
- **弹簧是解析解，不是迭代积分**（`utils/spring.ts:141-176`：给定 t 直接算位置/速度）。因此每帧成本与帧率无关、与 dt 无关，120Hz 只是调用次数翻倍。
- **写 DOM 有阈值节流**：Y 变 <0.001px 不写、opacity <0.05 不写、blur <0.05 不写（`dom/lyric-group.ts:175-193`）。这是它能稳的关键。
- **行进出视口是"挂载/卸挂"而不是"隐藏"**：`commitChanges` 依据 `isInRenderRange()` 调 `show()`/`hide()`（`dom/lyric-group.ts:104-111`），DOM 插入位置要保证顺序（`:60-86`），并伴随 `ResizeObserver.observe/unobserve`。
- **首帧进视口才构建内容**（延迟构建 + 遮罩签名）：`lyric-line.ts:107-109,206-215,525-583`。

### 3.2 单帧成本构成（结构分析，非实测）

| 项 | 量级 | 说明 |
| --- | --- | --- |
| 弹簧积分 | 每行 3 个解析解（`posY`、`bgSlideY`、`scale`）≈ 微秒级 | 便宜 |
| `isInRenderRange` | **O(N) per 行 → O(N²) per 帧**（`lyric-group.ts:43-58` 里 `currentLyricGroups.indexOf(this)`） | N=300 行时每帧 4.5 万次比较；实测应是零点几毫秒，但是"平方项" |
| 写样式 | 仅脏行，阈值节流 | 便宜 |
| 合成/绘制 | **真正的成本**：可见行数 ×（1 个文本层 + 逐词遮罩层 + 亮/暗副本 2 层）× `filter: blur` × `plus-lighter` | 见 §3.4 |
| 背景 | 每帧 1 次 FBO 全屏 mesh + 1 次全屏 quad，1920×1200 @ scale 0.75 | 与歌词无关，可独立限帧 |

### 3.3 2K 平板跑 60 / 120Hz 的可行性判断

- **60Hz：可行，但要限档。** 建议 `enableBlur=false`（或接受 blur 上限 5px 的代价）、背景 `setFPS(30)`+`setRenderScale(0.5)`、`overscanPx` 保持默认 300。
- **120Hz：不建议让歌词跟到 120。** 理由不是 JS（JS 很便宜），而是每次 `update` 都可能让"文本 + 遮罩渐变 + 两个 plus-lighter 副本"重新合成；在 120Hz 下这块合成预算减半。做法：把 `update` 限到 60Hz 调用（内部按 `delta` 累计，弹簧是时间函数，**降频调用不会改变轨迹，只降低采样密度**——这是解析解弹簧的额外红利）。
- 注意 `isNarrowViewport` 的阈值是 **1024 CSS px**（`base/index.ts:950`），2K 平板（≈1280 CSS px 宽）**不会**触发"窄视口模糊打折"（`resolveBlurLevel` `:1086` 的 `level * 0.8`）。若要省，得自己在 WebView 里把视口 CSS 宽度设到 ≤1024 或直接关 blur。

### 3.4 已知重绘热点（按危害排序）

1. **逐行 `filter: blur()` + `plus-lighter` 叠加层**。每行是个独立合成层，模糊半径随距焦点距离 1→5px 递增（`base/index.ts:1085-1086`），外加亮度层窗口 ±2 行的**两个 DOM 克隆**（`base/index.ts:58-59,952-962`；克隆逻辑 `line-brightness.ts:172-193`）。这是 GPU 侧的固定开销，行数越多越贵。
2. **强调词的 `textShadow` 动画**。`generateGlowKeyframes` 对每个字符动 `textShadow` + `transform`（`animation/emphasize/index.ts:155-171`），`textShadow` **不可合成**，每个字符每帧重绘字形。CJK 歌词 + 逐字强调时这是最大热点，且与"逐词遮罩"叠加。
3. **遮罩回退路径每帧改 CSS 变量**。`--amll-player-time` 每帧写一次（`dom/index.ts:227-232`），会使所有引用它的 `mask-position` 表达式失效 → 全行样式重算 + 遮罩重绘。**在老 WebView（mask-image 无前缀不支持）上，这条"降级"比正常路径更贵。** 判断依据：`CSS.supports("mask-image","none")`。
4. **遮罩重建时的强制同步布局**。`measureWords()` 逐词读 `clientWidth/clientHeight`（`lyric-line.ts:482-503`），`updateMaskImageSync` 还要 `getComputedStyle`（`:534`）；更狠的是换行平衡器会临时把 `white-space` 改 `nowrap` 并把祖先 `transform` 置 `none` 再测量（`utils/line-balancer.ts:96-110`）——这是写-读-写，**每次"某行首次进视口"或尺寸变化都会触发一次强制重排**。快速滚动/拖动进度条时会成串出现，是最可能被用户感知的卡顿源。
5. **O(N²) 的 `indexOf`**（`lyric-group.ts:46-50`，每行每帧一次）。长歌词（300+ 行）时是稳定的小额浪费；改成构造时缓存索引即可，但那是改源码。

另外：`bg-render` 里有 `enablePerformanceMonitoring` 字段（`mesh-renderer/index.ts:714-718`）但默认关闭、也没有对外 API，**组件本身不提供帧率统计**，需要宿主自己在 WebView 里加 `PerformanceObserver`/`requestAnimationFrame` 计时。

---

## 4. 打包形态

### 4.1 实测体积（npm `@applemusic-like-lyrics/core@0.7.2` 真实产物，非估算）

从 registry 拉 tarball 后本机实测：

| 文件 | 原始 | gzip -9 | esbuild --minify | min+gz |
| --- | --- | --- | --- | --- |
| `dist/amll-core.mjs` | 318,746 B | 91,029 B | **163,080 B** | **48,593 B** |
| `dist/style.css` | 6,653 B | 1,727 B | 5,244 B | 1,575 B |
| `dist/amll-core.cjs` | 322,155 B | — | — | — |
| `dist/amll-core.d.mts` | 93,365 B | 运行时不需要 | — | — |
| `dist/*.map` | 各 ~608 KB | **不要打进 APK** | — | — |

官方产物**没有压缩**（`amll-core.mjs` 8724 行、变量名保留、注释在），因为 `tsdown.base.ts:1-6` 只配了 `format: ["esm","cjs"] / dts / sourcemap`，没有 `minify`。

按 region 切分（脚本统计 `//#region` 边界）：

| 区段 | 字节 | 占比 |
| --- | --- | --- |
| `src/lyric-player` | 154,204 | 48.4% |
| `src/bg-render` | 115,231 | 36.2%（其中 GLSL 原始字符串 16,735 = 5.3%） |
| `src/utils` | 30,753 | 9.6% |
| `src/styles`（CSS 模块映射） | 1,272 | 0.4% |

→ **只做歌词、不要背景**：约 186 KB 未压缩（58.4%），按同比例外推 ≈95 KB min / ≈28 KB gz。

**上面这一步我实测了**（从源码只 import `lyric-player/index.ts`，esbuild `--bundle --format=iife --minify --target=chrome100`，CSS Modules 走 `--loader:.css=local-css`，`--define:import.meta.env.DEV=false`）：

| 产物 | min | gzip -9 |
| --- | --- | --- |
| `amll-lyric.iife.js` | **86,442 B** | **25,266 B** |
| `amll-lyric.iife.css` | **4,694 B** | **1,456 B** |
| 合计 | ≈91 KB | **≈26.7 KB** |

比外推值更小，原因有两个（都很有价值）：

1. **歌词路径根本不需要 `@pixi/*`，也不需要 `gl-matrix`。** 只 import `lyric-player/index.ts` 时，整条依赖链只带出 **2 个运行时依赖**：`bezier-easing`（`dom/animation/emphasize/index.ts:1`）与 `@ungap/structured-clone`（`base/lyric-data-manager.ts:1`）。pixi 只通过 `src/index.ts:2` 的 `export * from "./bg-render/index.ts"` 被牵进来。
2. 遮罩/亮度/CSS 模块映射都不带 GLSL 字符串（17 KB 全在 bg-render 里）。

（构建时因为源码目录里没有 `node_modules`，我把这两个依赖 `--alias` 到临时目录；这也说明**若要打这个 bundle，锁定这两个依赖版本即可，不必安装 pixi 全家桶**。）

### 4.2 能不能打成单文件 ESM / IIFE？能，但要自己做三件事

1. **官方不给现成的 IIFE/CDN 版**：`docs/.../quickstart.mdx:8` 明写「AMLL 不提供 CDN 引入方式，必须使用 bundler」。`tsdown.config.ts:29-33` 也只出 esm+cjs。→ 单文件 IIFE 需要自己 `esbuild --bundle --format=iife`。
2. **必须处理 `@pixi/*` 的静态 import**：发布产物开头 10 行 import 里，有 7 条是 `@pixi/*`（`import { Application } from "@pixi/app"` …，`amll-core.mjs:3-9`），另有 `gl-matrix`/`@ungap/structured-clone`/`bezier-easing` 三条（`:2,10,11`）。虽然你可以只用 `DomLyricPlayer`，但**模块级静态导入**意味着 bundler 必须能解析这 7 个包，否则构建期报错。三种解法：
   - 安装 8 个 `@pixi/*`（peerDeps，`packages/core/package.json:76-84`），让 tree-shaking 丢掉 `PixiRenderer`；
   - 用 `--alias:@pixi/app=./stub.js` 之类打桩（只要不 `new PixiRenderer`，桩不会被调用）；
   - **直接从源码构建，只 import `lyric-player/index.ts`**（不经过 `src/index.ts` 的 `export * from "./bg-render"`），这样 pixi 完全不进图。这是最干净的一条，**已实测 ≈86 KB min / ≈25 KB gz**（见 §4.1），只需保留 `bezier-easing` + `@ungap/structured-clone` 两个依赖。
3. **CSS 必须一起进**：`import "#styles/index.css"` 在 `dom/index.ts:7` 是硬依赖，class 名来自 CSS Modules（`styles.lyricLineWrapper` 等），**两者不能分开维护**。产物形态建议：`bundle.js` + `style.css` 两个文件，或在构建期把 CSS 内联成 `<style>` 注入（更省一次 IO，且避免 `file://` 下的相对路径问题）。

### 4.3 是否需要网络

- **不需要**：没有任何 `@font-face`、没有远程字体、没有 telemetry、没有外部 API（全仓 `grep font-face packages/core` 无命中；`build:docs` 之外无网络调用）。
- **封面例外**：`setAlbum(string)` 走 `fetch`（`mesh-renderer/index.ts:1149`），要求同源或 CORS 允许（`docs/.../background.md:214` 明确列为检查项）。本地封面请直接传 `ImageBitmap`/`HTMLImageElement`，或用 `WebViewAssetLoader` 的同源域。
- **字体在 Android 上是真问题**：CSS 只用 `font-family` 继承（`index.css:14` 只设 `font-size`），实际字形取 WebView 的 fallback。中文会落到系统 Noto Sans CJK——和 Apple Music 的 SF/PingFang 观感差别明显。若在意，需要宿主注入 `@font-face`（自带字体文件，注意字体许可）。

### 4.4 WebView 内的加载方式（三种，按推荐度）

1. **`WebViewAssetLoader` + `https://appassets.androidplatform.net/assets/...`**：同源、`fetch` 可用、可用 CORS、可用 `createImageBitmap`。**推荐**。
2. `loadDataWithBaseURL(baseUrl, html, ...)` + 内联全部资源：单文件、无 IO，但每次改歌词要重载页面（不适用）。
3. `file:///android_asset/...`：最简单，但 `fetch` 本地文件被拒（`setAlbum` 挂掉），且 `file://` 下部分 CSS/字体加载策略更严。**不推荐**。

---

## 5. 许可（这是本评估里最需要拍板的一项）

### 5.1 事实

| 项 | 值 | 证据 |
| --- | --- | --- |
| AMLL 整体 | **AGPL-3.0-only**（"only"，不可升版） | `LICENSE:1`（GNU AFFERO GPL v3 全文）；`package.json:4` |
| core 包 | AGPL-3.0-only | `packages/core/package.json:10` |
| 其余包（lyric/ttml/react/vue/react-full） | 同样 AGPL-3.0-only | `packages/{lyric,ttml,react,vue,react-full}/package.json` 的 `license` 字段 |
| AM++ | **GPL-3.0** | `LICENSE:1`（GNU GENERAL PUBLIC LICENSE v3） |

两条关键条款（本地 `LICENSE` 可查）：
- AGPL §13 第 2 段：**允许**把 AGPL 作品与 GPLv3 作品"link or combine into a single combined work"，但「本许可继续适用于被覆盖的那部分，而被合并的那部分仍受 GPLv3 约束」（`LICENSE:553-559`）。GPLv3 侧有对称条款（`worktrees/line-transition/LICENSE:556`）。→ **GPL-3.0 与 AGPL-3.0 可以合法组合，不存在"许可证冲突"**，冲突只在"你要履行哪一套义务"。
- AGPL §13 第 1 段：**网络交互条款**——若你修改了程序、且它支持"通过网络远程交互"，必须向远程用户提供 Corresponding Source（`LICENSE:540-551`）。→ 对**本地 App**（WebView 在本机渲染、不对外提供网络服务）**不触发**这项额外义务。

### 5.2 情形 A：WebView 加载本地 bundle（**不改** AMLL 代码）

需要的动作：
1. 随包分发 `AMLL.txt`（AGPL 全文）+ 版本/来源/commit（`f329796`）。
2. 提供"获取 Corresponding Source"的途径：AMLL 未改动时，指向 upstream 仓库即可；但你自建的 bundle 构建脚本（esbuild 参数、CSS 内联脚本、`@pixi` 打桩）属于"构建脚本"，AGPL §1 明确把 `scripts to control those activities` 算进 Source Code —— **保守做法：把 bundle 构建脚本一并公开**。
3. 不要给 AMLL 部分加额外限制（不能"仅限非商业"、不能加反调试/加密 bundle 使他人无法替换）。

争点：**WebView 里的 bundle 与 AM++ 是"单一作品"还是"独立程序"？**
- 支持"独立"：FSF FAQ（`gpl-faq.html#MereAggregation`）的经典表述是「pipes、sockets、命令行参数是**通常用于两个独立程序之间**的通信机制」；WebView + `evaluateJavascript` 的 JSON IPC 在形态上接近"两个程序通过一条通道交换数据"。
- 支持"单一"：FAQ 同时给出反例——若通信「交换的是足够复杂的内部数据结构」，则视为一个程序。这里 Kotlin 侧要喂的是**完整的 `LyricLine[]` 时间轴 + 逐帧播放位置 + 封面位图**，且两边共享同一个"歌词模型"语义，边界并不天然干净。
- **结论：定性不确定。** 因为不确定，**必须按"单一作品"的保守解释来设计**，否则一旦被认定为合并作品，"只署名 + 附全文"是不够的。

按保守解释（合并作品）：
- 你分发的是"AM++ 的 GPL 部分 + AMLL 的 AGPL 部分"的组合作品；AGPL 部分仍是 AGPL，GPL 部分仍是 GPL；**但整体发布物必须同时满足两套义务**，实践中等价于：**整个 APK 的完整对应源码必须可获得，且不得对 AMLL 部分附加限制**。
- 是否必须把 AM++ **整体改标为 AGPL-3.0**？严格说：你可以保留"AM++ 代码 = GPL-3.0"的表述，同时声明"含 AGPL-3.0 组件，整体分发按 AGPL-3.0 履行"。但**从工程与合规成本看，最省事、最不易出错的做法是把仓库 LICENSE 换成 AGPL-3.0，或至少新增一份 `LICENSE-AGPL-3.0` 并在 README 首屏声明"本发行物整体按 AGPL-3.0 分发"**。改标不改变你对第三方 GPL 代码的义务（GPL 代码仍是 GPL），只是让"整体"有一个明确说法。
- **用户能改**：AGPL/GPL 都要求接收者能修改并替换该部分；因此 bundle 不得混淆到不可读、不得签名死锁（Android 侧还有一个额外现实问题：Magisk/Xposed 模块天然允许替换，这点反而低风险）。

### 5.3 情形 B：把 TS 代码移植成 Kotlin

- **翻译 = 演绎**。把 `spring.ts`/`lyric-group.ts`/`animator-web.ts` 改写成 Kotlin，产出的是 AGPL 作品的衍生作品，**仍受 AGPL-3.0-only 约束**（不能因为"换了语言"就摆脱）。
- 移植后与 AM++ 同进程、共享 `LyricLine` 数据类、由 `LyricLineSpringFeature` 直接调用——**"合并作品"的认定几乎没有争议**（这是最典型的 linking 形态）。→ 整个 AM++ 必须按 AGPL 分发，**且移植出来的 Kotlin 不能再以 GPL-3.0-only 单独授权**（你无权给 AGPL 代码换许可证）。
- **所以"移植成 Kotlin 以规避 AGPL"是错误直觉：移植让许可问题从"边界争议"变成"确定成立"。**

### 5.4 情形 C：只借鉴"规则/参数/视觉"，不搬代码

- 版权保护**表达**，不保护**思想、方法、算法、参数取值**。你的 `02-amll-posy-spring-spec.md`/`03-amll-spec-v1.md` 已经是这个路子的成品（把行为写成规格，再由 Kotlin 独立实现）。
- 安全区：弹簧参数（`mass/damping/stiffness`）、档位曲线（blur = 1+distance）、`MAX_FRAME_DELTA=100ms` 这类常量、layout 策略、`data-amll-role` 之类的**功能性命名**（改掉更稳）。
- 灰区/危险区：
  - **逐行对照翻译**（哪怕换了语法）——这是最容易被认定为复制的形态；
  - **整段注释/文档文字**照搬（注释受版权保护）；
  - **SVG 矢量路径**（AM++ 已有先例：`THIRD_PARTY_NOTICES.md` 里 AMLL/Speaker/AirPlay 路径都按"美术作品"处理并附来源，这是正确做法）；
  - **GLSL 着色器**（`bg-render/shaders/*.glsl`、`mesh-renderer/*.glsl`）——着色器是有创作性的代码，不要抄。
- 这条路的成本是"重写视觉"，收益是**零 AGPL 传染**。若 AM++ 的定位是"hook 原生歌词视图做增强"（现状），这条路和现有架构还更契合。

### 5.5 现状核查：AM++ 其实已经在 AGPL 的灰区里

- 已随包分发 AMLL 许可全文：`host-applemusic/src/main/assets/licenses/AMLL.txt`（661 行，AGPL 全文）。
- 已在 `THIRD_PARTY_NOTICES.md:57-64` 声明「tablet 布局规则、speaker/AirPlay 矢量路径、音量交互…adapted from the AMLL source bundled in YouLyPlus」。
- 已有"AGPL 代码移植成 Kotlin"的**先例**：`core/src/main/kotlin/dev/amenhancer/module/lyrics/LlmLyricsClient.kt:3-6` 自述「ported prompt text … keep that provenance（AGPL-3.0-only）」。
- 也就是说：**项目当前的实践是"署名 + 附全文"，并未按"合并作品 ⇒ 整体 AGPL"来标 license。** 这在"只借规则/矢量"时尚可辩护（§5.4），但**一旦真把 AMLL 的 TS/GLSL 搬进来（情形 A 的 bundle 或情形 B 的移植），现有做法就不够了**——这是本次评估最需要向用户点明的合规缺口。

### 5.6 保守建议（按优先级）

1. **先决定"要不要 AGPL"**，再决定技术方案。因为这一项决定是不可逆的：一旦发布过含 AGPL 代码的版本，后续版本不能"撤回"。
2. 若**不想 AGPL**：走 §5.4——只保留现有"规格 → Kotlin 独立实现"的路子，**不要**引入 `@applemusic-like-lyrics/core` 的 bundle，也**不要**逐行翻译它的 TS。可视化对照只用于"验证行为"，不用于"照抄结构"。
3. 若**接受 AGPL**：选情形 A（bundle）而非情形 B（移植）——理由是"边界可辩护 + 不改动 AM++ 核心代码 + 上游升级可跟随"，并做到：
   - 仓库新增 `LICENSE-AGPL-3.0`，README/LICENSE 首屏声明"发行物整体按 AGPL-3.0 分发"；
   - `THIRD_PARTY_NOTICES.md` 的 AMLL 段落补上 commit `f329796`、包名与版本 0.7.2、以及**bundle 构建脚本路径**；
   - 提供 `amll-bundle/` 目录（源码 pin + `build.mjs`），保证第三方能一字不差重建 `assets/amll/bundle.js`；
   - 不混淆、不加密该 bundle；
   - 若将来把渲染搬到服务端/远程投屏（真的"网络交互"），额外准备 §13 的源码提供入口。
4. 无论哪条路：**保留现有 `assets/licenses/AMLL.txt` 与署名段落**（成本为零，且已存在）。
5. 长期：把"是否 AGPL"写进 `CONTEXT.md` 的架构约束，避免下一个人再重新发明一遍结论。

---

## 6. 三条路线打分（供决策）

| 路线 | 独立渲染 | 与 AM++ 现状契合 | 视觉保真 | 体积 | 性能可控 | 许可风险 | 工作量 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A. WebView + AMLL bundle | ✅ 完全独立 | 🔴 需新增浮窗 WebView（现零 WebView） | 🟢 1:1 | 🟡 ~50 KB gz | 🟡 需限档 | 🔴 AGPL 传染（保守=整包 AGPL） | 🟡 中（IPC + 资源加载 + 浮窗同步） |
| B. TS→Kotlin 移植 | ✅（但要重做渲染层） | 🟡 可复用现有 Feature 注入 | 🟢 高 | 🟢 0 | 🟢 可控（Android Canvas/Compose） | 🔴 确定成立（合并作品） | 🔴 大（DOM/CSS 语义无处安放） |
| C. 只借规则（现状路线） | — | 🟢 完全契合 | 🟡 需自己调 | 🟢 0 | 🟢 | 🟢 低 | 🟡 中 |

**一句话：技术上是可嵌入的，问题从来不是"能不能渲染"，而是"要不要为它引入一块 Chromium 合成表面 + 一条 AGPL 尾巴"。**

---

## 7. 未验证 / 存疑（诚实标注）

1. **无真机/真 WebView 实测**。体积是实测（npm tarball + esbuild），性能是**结构分析**，不是帧时间测量。2K/120Hz 的判断基于"每帧合成层数量"推理，误差可能 ±1 档。
2. **WebView 版本分布未知**。`mask-image` 无前缀（Chromium 120）是分水岭：≥120 走快路径，<120 走 calc 回退（**更贵**）。目标平板的 WebView 版本需要用 `navigator.userAgent` 打点确认。
3. **`plus-lighter` 的确切首发 Chromium 版本**未能从 BCD 取到单值（BCD 只记录 `mix-blend-mode` 整体 = Chrome 41），本文按"~100 / 2022 年上半年"表述，用 AMLL 自己的 `@supports` 兜底结论不受影响。
4. **"WebView 是否算独立程序"没有判例级答案**，本文按保守解释处理；若确需定论，应找法务，而不是从 FAQ 推断。
5. **bundle 只做了语法检查（`node --check` 通过），没有在浏览器里实跑**；若要把 §4.1 的 86 KB 当成既定事实，还需在目标 WebView 里加载一次并确认 `AMLL.LyricPlayer` 可实例化、首帧无异常。
6. **未测 `dispose()` 后的泄漏**（`ResizeObserver` 对每行元素 `observe` 过，`dispose` 路径看起来完整但未验证）。
7. **未评估 AMLL 的 `react-full` 完整播放器**（含玻璃拟态 UI、歌词搜索等），它才是"整个歌词页"的完整形态；本文聚焦 `core` 的歌词渲染组件，因为那才是可嵌入的最小单元。
