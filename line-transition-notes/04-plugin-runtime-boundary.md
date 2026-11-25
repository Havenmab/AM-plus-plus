# 04 — AM++ 插件运行时（SDK v1）能力边界：能否接管歌词换行动画

范围：只读审阅 `plugin-runtime/`（9 个 Kotlin 文件）、`plugin-api/`（SDK 表面）、`hook-runtime/`（Hook 原语）与宿主接线（`app/`、`host-api/`、`host-applemusic/`）。
目标：判断 **AM++ 插件（SDK v1 / ZIP）** 能否完成 `line-transition-notes/03-amll-spec-v1.md` §5 的 P2–P3，即 (a)–(f)。

---

## 0. 一页结论

| 需求 | 判定 | 一句话理由 |
| --- | --- | --- |
| (a) hook `PlayerLyricsViewFragment.K#b(II)` / `LinearLayoutManager#u1(II)` | **可行** | `PluginHooks.hook(Executable, …)` 直接转成 libxposed intercept，可读/改参/改返回 |
| (b) 拦截/替换 ItemAnimator（`mc.y#m` animateMove） | **部分** | 拦截并"否决"可行；**换成自己的 ItemAnimator 实例不可行**（不能定义宿主类型的子类） |
| (c) 拿到歌词 `RecyclerView` 实例 | **部分** | SDK 无任何视图/生命周期 API，只能自己 hook + 遍历视图树 |
| (d) 在行 View 上写 `translationY`/`RenderEffect`/`scale` | **可行** | `android.view.View` 是 boot class，插件可直接使用；但所有权仲裁要自建 |
| (e) 自建 `Choreographer` 弹簧循环 | **可行** | 纯 Android API；运行时不管、也不隔离你起的帧循环 |
| (f) 自己的设置开关界面 | **可行** | `createSettings(Context)` 返回 `View`，配 `getDataDirectory()` 持久化 |

**总判定：部分可行。** 一个第三方插件在"手写全部宿主适配 + 自管线程/视图所有权 + 接受必须重启才能关掉"的前提下，**能做出"接管换行位移"的最小版本**；但 **"完整替换 ItemAnimator"、"热启停/热调试"、"与宿主/内置写入者的属性仲裁"三件事在运行时层面没有 API**，见 §5。

---

## 1. 运行时事实

### 1.1 进程：Apple Music 主进程内，不是独立进程

- `PluginManager` 由 `HookEntry` 在 **目标进程内** `Application#onCreate` 的 after 钩子里创建并 `start()`：`app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt:190-194`。
- 只在该进程的主进程里跑：`HookEntry.kt:96`（`isTargetMainProcess`）、`HookEntry.kt:239-241`；libxposed 的 `PackageReadyParam` 也先过 `EmbeddedBootstrap.prepare` 的 `processName == mainProcessName` 判断（`app/src/main/java/dev/amenhancer/module/hook/EmbeddedBootstrap.kt:23`）。
- **插件与宿主同进程、无沙箱**，这是文档明写的契约：`docs/plugin-development.md:5`（"插件与宿主同进程执行，不是隔离沙箱"）。
- 连带后果：插件拿到的 `Application` 就是 **Apple Music 的 Application 实例**（`HookEntry.kt:93-95` 的 `param.thisObject as? Application` → `PluginManager(application, …)` → `PluginManager.kt:132-133`），因此插件可以直接 `registerActivityLifecycleCallbacks`、`getSystemService`、`Looper.getMainLooper()`。

**门禁**：插件只在"AM++ 已登记为 production 的宿主版本"上才会被创建——`HookEntry.kt:98-103` 在 `bootstrap.supports(build)` 为假时直接 return，而 `supports` = `AppleMusicHostProfiles.isProductionBuild(...)`（`EmbeddedBootstrap.kt:34-37`）。也就是说 **插件不能扩大宿主版本支持范围**（`docs/plugin-author-guide.md:264`）。

### 1.2 ClassLoader：三层，插件在最外层子加载器

```
boot (android.*)  ←  target PathClassLoader (com.apple.android.music + 宿主自带 androidx)  ←  模块 loader (AM++ APK: plugin-api/hook-runtime/host-api/host-applemusic/core)
                                     ↑ getHostClassLoader()                                     ↑ 插件的 parent
                                                                                     DexClassLoader(code.jar)  ← 插件代码
```

- 插件加载器：`DexClassLoader(<install>/code.jar, application.codeCacheDir.path, null, AmppPlugin::class.java.classLoader)`——`plugin-runtime/src/main/java/dev/amenhancer/plugin/runtime/PluginManager.kt:63-64`。
  - **parent 是模块 loader**（`AmppPlugin` 由模块定义），所以插件"天然"能看到 `dev.amenhancer.plugin.api.*` 与 `dev.amenhancer.module.*`。
  - 两层之间是否还能看到宿主类**不是 SDK 契约**：SDK 只承诺 `getHostClassLoader()`（`PluginManager.kt:134`；`docs/plugin-development.md:75`；`docs/plugin-author-guide.md:188`）。
- 入口类必须是插件加载器自己定义的类（`PluginEntries.kt:11-12`：`type.classLoader === loader`），且 **DEX 里不允许自带 SDK 类**（`PluginPackages.kt:211` 拒 `Ldev/amenhancer/plugin/api/` 定义）。
- `code.jar` 只允许 `classes*.dex` / `META-INF/`（`PluginPackages.kt:183`），且被 `setReadOnly`（`PluginPackages.kt:102`）；ZIP 只允许 `plugin.json`/`code.jar`/`assets/`（`PluginPackages.kt:96`）→ **没有 XML 资源、没有 `.so`、没有插件 Activity/Service**（`docs/plugin-development.md:39`）。
- **拿不到自己的安装路径**：`PluginContext` 只给 `getDataDirectory()`/`getCacheDirectory()`（`plugin-api/src/main/java/dev/amenhancer/plugin/api/PluginContext.java:10-21`），因此"再开一个 parent=hostLoader 的 DexClassLoader 去定义宿主类型子类"这条路**连 dex 文件都定位不到**。

> 副作用（值得记一笔）：因为插件 loader 的父是模块 loader，插件在**运行时**可以反射到 `ModernXposedRuntime`、`AppleMusicHookProfiles` 甚至 `core` 的 `OwnedHostProperty`。这属于未承诺旁路，会绕过 `HookRegistrations` 冲突登记（`docs/plugin-development.md:102` 明说"绕过 SDK 注册的 Hook 不在分析范围内"），**不能当成 API 用**。

### 1.3 加载 / 启停时序

| 阶段 | 线程 | 证据 |
| --- | --- | --- |
| 索引读取 `cleanupAtStartup` | `ampp-plugin-management`（单线程 daemon） | `PluginTasks.kt:7,11-21`；`PluginManager.kt:45-49` |
| 逐插件 `DexClassLoader` + `onLoad` | `ampp-plugin-onload`（cached pool，**各插件并发**） | `PluginTasks.kt:9,24-28`；`PluginManager.kt:58-69` |
| 冲突检查 → `onStart` | 主线程 | `PluginManager.kt:70-77`（`main.post`） |
| Hook `before/after` | **宿主调用该方法的线程**（可能是 render/主线程） | `PluginManager.kt:150-177`；`docs/plugin-author-guide.md:171` |
| `createSettings` / 打开设置 | 主线程（有 `check`） | `PluginManager.kt:96-112` |
| `onClose` cleanup | **执行关闭的那个线程** | `HookRegistrationScope.kt:25-34`；`docs/plugin-author-guide.md:173` |
| `onStop` | 主线程，尽力一次 | `PluginManager.kt:54`（dispatch 到主线程）；`PluginLifecycle.kt:31` |

---

## 2. SDK 表面能力清单（v1 全部能力）

`plugin-api` 一共 10 个类型，没有任何依赖（`plugin-api/build.gradle.kts` 无 `dependencies`）。能力清单：

| 能力 | 入口 | 关键证据 | 限制 |
| --- | --- | --- | --- |
| 宿主身份 | `getHostPackageName/VersionName/VersionCode` | `PluginContext.java:12-14` | 只有字符串/long，**无 profile / 无 hookPoint 语义** |
| 宿主 ClassLoader | `getHostClassLoader()` | `PluginManager.kt:134` | 只给 loader，不给"目标解析" |
| 宿主 Application | `getApplication()` | `PluginManager.kt:133` | 可直接挂 Activity 生命周期回调 |
| 只读观察 | `observe(Executable, PluginObserver)` | `PluginManager.kt:148-161` | 参数是副本，不能改（`PluginObservation.java:12-18`） |
| 可写 Hook | `hook(Executable, exclusive, PluginHook)` | `PluginManager.kt:162-178` | 改参/改返回/抛异常/跳过原方法；目标**只能是 `Executable`**（方法或构造器），无字段/类初始化 hook 入口 |
| 逻辑资源独占 | `claimResource(key, exclusive)` | `PluginManager.kt:144-147` | 只做冲突声明，**不挂 UI、不仲裁属性**（`docs/plugin-author-guide.md:18`） |
| 单条注册关闭 | `PluginRegistration.close()` | `plugin-api/.../PluginRegistration.java:3` | 逻辑关闭：已装回调透传，**不物理 unhook** |
| 清理登记 | `onClose(Runnable)` | `PluginManager.kt:143` | 逆序执行；不管未登记线程（`docs/plugin-development.md:88`） |
| 设置页 | `createSettings(Context) → View` | `AmppPlugin.java:8-9`；`PluginManager.kt:100-111` | 仅 `ACTIVE` 时可开（`PluginManager.kt:99`） |
| 存储/素材/日志 | data/cache 目录、`openAsset`、`log` | `PluginManager.kt:139-142` | 无 SharedPreferences 约定、无资源表 |

**没有**的东西（对照需求逐项都能感觉到）：视图/ViewHolder 生命周期、属性所有权、帧循环、目标解析、类注入、宿主事件总线、热重载、超时/看门狗。

---

## 3. 逐条对照 (a)–(f)

### (a) hook `K#b(II)` / `LinearLayoutManager#u1(II)` → **可行**

- 注册路径：`context.getHooks().hook(method, exclusive, callback)` → `PluginManager.kt:162-178` → `ModernXposedRuntime.hookMethod(...)`（`PluginManager.kt:163`）。
- 这是**真 libxposed 拦截**，不是运行时自己做分发：`runCatching { activeModule.deoptimize(executable) }` + `activeModule.hook(executable).intercept { chain -> … }`（`hook-runtime/src/main/java/dev/amenhancer/module/hook/ModernXposedRuntime.kt:60-61`）。
  - 先 `deoptimize` 的理由写在 `ModernXposedRuntime.kt:56-59`（防止 ART 内联导致"装了但回调不触发"）。
  - `scope` 关闭后回调直接 `chain.proceed()`（`ModernXposedRuntime.kt:62`）。
- **能阻止执行 / 改参数 / 改返回值**（这是问题 2 的核心）：
  - `before` 里 `returnResult(v)` → `param.result` 置位并把 `returnEarly` 打开（`ModernXposedRuntime.kt:20-26,34`），拦截器于是**不调用 `chain.proceed`**（`ModernXposedRuntime.kt:69-75`）→ 原方法被跳过。
  - `before` 里改 `getArguments()` → 提交后 `call.arguments.copyInto(param.args)`，再 `chain.proceed(param.args)`（`PluginManager.kt:168-172`；`ModernXposedRuntime.kt:71`）→ 改参数生效。
  - `throwException` → `param.throwable` → 拦截器 `throw`（`ModernXposedRuntime.kt:77`）。
  - 类型检查不通过/回调抛错 → 不提交（`PluginCallbacks.kt:10-16`），宿主保留进入回调前的语义。
- **构造器 hook：支持**。SDK 只签名到 `Executable`（`PluginHooks.java:5-8`），`PluginCallbacks.validate` 对非 `Method` 跳过返回值检查（`PluginCallbacks.kt:20-22`），libxposed 的 `hook(Executable)` 本就同时接受 `Constructor`（[libxposed API 102 `XposedInterface.java`](https://github.com/libxposed/api/blob/master/api/src/main/java/io/github/libxposed/api/XposedInterface.java)：`HookBuilder hook(@NonNull Executable origin)`，注："Hook a method / constructor"）。构造器拦截时返回值为 `null`、`proceed(args)` 即"用新参数构造"。
- **但**：`PluginHook` 得到的是 `Executable`，**目标定位完全是作者责任**。`K#b` 是宿主 `fragment` 包下混淆类的成员（笔记记作 `fragment.K#b(II)`，`01-line-change-motion-feasibility.md:171`），`u1(II)` 属于宿主自带的 `androidx.recyclerview.widget.LinearLayoutManager`，二者在 1609/1614/1626 的类名/方法名不一致（`line-transition-notes/01-line-change-motion-feasibility.md:155` 明确"1614/1622 需要各自解 DEX 确认"），SDK 没有版本表（`docs/plugin-development.md:11`）。
- 代偿：`animateMove(VH,fromX,fromY,toX,toY)` 同时给出"换行了"与位移量（`01-line-change-motion-feasibility.md:39` R2、`:208`），且不依赖任何混淆名（见 (b)）——所以 (a) 的定位缺口可以绕开，不必 pin `K#b`。
- 冲突面：对已被内置 HLE 登记的同一个 `Executable`，**`exclusive=true` 会被直接判为"内置优先"并把插件关掉**（见 §4.2）。

### (b) 拦截/替换 ItemAnimator（`mc.y#m` animateMove）→ **部分可行**

**能做的（拦截 + 否决）**

1. 从 `RecyclerView#getItemAnimator()` 拿到宿主实例——**这一步零混淆名**：`getItemAnimator` 在 1626 保留原名（`01-line-change-motion-feasibility.md:67`），返回类型被改成 `RecyclerView$k` 无妨；`animator.getClass()` 即宿主 animator `mc.y`（唯一构造点 `PlayerLyricsViewFragment$u#<init>`，`01-line-change-motion-feasibility.md:69`，可用来认定"这是歌词那个 animator"）；
2. `getDeclaredMethods()` 按形状筛出 `boolean (ViewHolder,int,int,int,int)`（= `mc.y#m`）与 `boolean (ViewHolder,ViewHolder,int,int,int,int)`（= `mc.y#l`），
3. `hooks().hook(it, false, …)`，在 `before` 里 `call.returnResult(false)` → 宿主动画被跳过，且 `chain.proceed` 不执行（`ModernXposedRuntime.kt:69-75`）。
   这正是 `03-amll-spec-v1.md:65` 描述的"拒绝 `animateMove` 的位移动画、自己跑标量弹簧"路线；注意必须**同时拒 `animateChange`**（`mc.y#l` 内部再调 `mc.y#m`，见 `03-amll-spec-v1.md:66`、`01-line-change-motion-feasibility.md:221`）。

**不能做的（替换成自己的 ItemAnimator 实例）**

- 要 `setItemAnimator(ours)`，插件必须定义一个 `extends androidx.recyclerview.widget.ItemAnimator`（或 `extends mc.y`）的类。两条路都被堵：
  - 继承 `mc.y`：插件 DEX 里不能出现宿主混淆类引用（无法编译），运行时也无法可靠解析；
  - 继承 androidx `ItemAnimator` 直接不可能：宿主里这个类已改名成 `RecyclerView$k`（`01-line-change-motion-feasibility.md:67`；`ViewHolder`→`RecyclerView$D` 同理 `:41`），插件 DEX 引 `androidx/recyclerview/widget/RecyclerView$ItemAnimator` 会 `NoClassDefFoundError`。
  - 退一步只按 androidx 名字继承，也还有第二层问题：**Class 身份不可控**。模块自己也 `implementation("androidx.recyclerview:recyclerview:1.4.0")`（`host-applemusic/build.gradle.kts:32`），宿主 APK 里另有一份；仓库自己的 HLE 代码因此**刻意避免从模块 ClassLoader 解析 androidx**（`host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleLyricsPresentationRebind.kt:28-32`："no androidx class needs to be resolved from the module classloader"）。插件 loader 的 parent 就是模块 loader，解析到哪一份取决于框架实现细节；一旦不是宿主那份，`RecyclerView#setItemAnimator` 会因参数类型不匹配抛 `IllegalArgumentException`。
  - 也没有"给插件一个 parent=hostLoader 的加载器"的 API，且插件拿不到自己的 `code.jar` 路径（§1.2）。
- 退一步的"全禁用"方案可行但更粗暴：hook `RecyclerView#setItemAnimator` 把参数改成 `null`（`PluginManager.kt:168-172` 支持改参），宿主就完全没有行动画；但 `getItemAnimator()` 变成 null 后，宿主若调用 `isRunning(...)` 会 NPE（`01-line-change-motion-feasibility.md:229-231` 记录宿主确实会读它）。

**结论**：拦截 `mc.y#m/l` 并否决 = **可行**；"替换 ItemAnimator 对象" = **不可行**（不是权限问题，是 Class 身份 + 无类注入通道）。

### (c) 拿到歌词 `RecyclerView` 实例（`onCreateView` 之后）→ **部分可行**

- SDK **没有任何**"fragment/activity/视图"接口；`PluginContext` 的全部方法见 `PluginContext.java:10-21`。
- 可行路径（都要自己搭）：
  1. 首选：hook `androidx.recyclerview.widget.RecyclerView` 的**构造器**（顶层类名保留，`TargetSymbols.kt:548`；构造器也是 `Executable`）→ `after` 里 `getReceiver()` 直接是实例，零混淆名。
  2. 次选：hook `androidx.recyclerview.widget.RecyclerView#setAdapter`（1626 保名）→ 回调里 `receiver` 就是 RecyclerView，用 `args[0].getClass().getName()` 判定是不是歌词 adapter（宿主在 `player.V0#P` 的 `B2` 里重设 adapter，`01-line-change-motion-feasibility.md:193-198,253`）。
  3. 或 hook `android.app.Activity#onResume` / `androidx.fragment.app.Fragment#onViewCreated` → 从 `receiver` 拿 `getView()` / `getWindow().getDecorView()` 遍历视图树。
  4. 或 hook `android.view.View#onAttachedToWindow`（boot class，最稳）再按类名/父链筛 RecyclerView。
- 缺的是**生命周期绑定**：SDK 没有 `HostViewSessionController`（`core/src/main/kotlin/dev/amenhancer/module/host/HostViewSessionController.kt:4`）那种 "view 身份 + generation" 语义，插件只能 `WeakHashMap` + `onClose` 自己收敛；宿主 fragment 重建、平板双栏两个歌词 RecyclerView 的歧义要自己解（`01-line-change-motion-feasibility.md:248-250`）。

### (d) 在行 View 上写 `translationY` / `RenderEffect` / `setScaleX/Y` → **可行**

- `android.view.View` 来自 boot classloader，**插件可以直接编译期引用并强转**（`android.jar` 对所有 dex 可见）；拿到 `View` 之后（holder 不必碰，见下条） `view.translationY = …`、`view.setRenderEffect(...)`（API 31+）、`view.scaleX = …` 都是普通调用，不走反射。
- 连 holder 都不用碰：把 `RecyclerView` 当 `ViewGroup` 用，`getChildCount()/getChildAt(i)` 都是 public framework API（历史分支就这么写，`01-line-change-motion-feasibility.md:234`）。**不要**按 `RecyclerView$ViewHolder` 找类型——宿主里叫 `RecyclerView$D`（`:41,67`），`getItemView()` 之类的名字也未验证。真正需要反射的只有「行身份」（`RecyclerView.M(View)→int`，`:134`）。
- **缺的是所有权原语**：仓库内部靠 `OwnedHostProperty`（`core/.../HostViewSessionController.kt:25`，"宿主改过就放弃所有权"）解决"宿主也在写 transform"的问题；SDK 没有导出，插件只能自建同一套（`01-line-change-motion-feasibility.md:244-246`，其中还包含"禁止写 `view.tag`"的仓库硬规则）。行 `translationY` 的已有写入者（`A#l`/`A#u0`/`fragment.C#c`）会与插件互相覆盖，运行时不会替任何一方仲裁。

### (e) 自建 `Choreographer` 弹簧循环 → **可行（但完全自担）**

- 允许：插件是普通同进程代码，`Choreographer.getInstance().postFrameCallback(...)`、`Handler`、`HandlerThread`、`ExecutorService` 都能用；仓库内置的同类写法就是直接 `Choreographer`（`host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewSession.kt:32,67`）。
- **登记/清理机制只有 `context.onClose { … }`**（`PluginManager.kt:143`）：运行时不会替你 `removeFrameCallback`、不会在前后台切换时暂停、不会在宿主 Activity 销毁时停表。文档明说"加载器不能回滚作者直接修改的对象或未登记线程"（`docs/plugin-development.md:88`）、"不管理你未登记的线程"（`docs/plugin-author-guide.md:20`）。
- 崩溃面：帧回调跑在主线程，插件异常**不会被 SDK 捕获**（SDK 的 try/catch 只在 `PluginCallbacks`/`observe` 的回调体内，见 `PluginCallbacks.kt:10-16`、`PluginManager.kt:152-157,164-174`）→ 主线程未捕获异常 = **宿主进程崩溃**。

### (f) 自己的设置开关界面 → **可行**

- `createSettings(Context)` 返回 `PluginSettingsSession`（`AmppPlugin.java:8-9`），运行时在主线程调用并做 ACTIVE/scope 校验（`PluginManager.kt:96-112`），宿主把 `getView()` 挂成插件管理下的子页（`app/src/main/java/dev/amenhancer/module/ui/EmbeddedPluginPages.kt:480-508`）。
- 只在插件 `state == ACTIVE` 时才给入口（`PluginManager.kt:99`；管理页按钮同样只在 ACTIVE 显示：`EmbeddedPluginPages.kt:292-300`）。
- 持久化用 `getDataDirectory()`（`PluginManager.kt:139`）；**没有 XML 布局/资源表**（`docs/plugin-development.md:39`），要用 Miuix 就得自己带依赖并按 DEX 边界打包（`docs/plugin-author-guide.md:272-276,352-362`）。
- 语义上够用：开关只影响插件自己的写入逻辑（自己读自己的配置），不需要运行时配合。

---

## 4. 结论：为什么"模块能做、插件不能"

### 4.1 总表

| 需求 | 判定 | 硬限制 |
| --- | --- | --- |
| (a) 方法 hook | 可行 | 目标定位每版本自建 |
| (b) 接管 ItemAnimator | 部分 | 宿主类型已改名（`ItemAnimator`→`RecyclerView$k`）且无类注入通道 → 只能"否决"不能"替换" |
| (c) 拿 RecyclerView | 部分 | 无视图/生命周期 API，自建发现 + 弱引用 |
| (d) 写行属性 | 可行 | 所有权/冲突自建 |
| (e) 帧循环 | 可行 | 无登记/无超时/异常不隔离 |
| (f) 设置页 | 可行 | 仅 View、仅 ACTIVE |

### 4.2 三个"结构性"限制（不是缺一个函数，而是缺一层）

1. **没有目标解析层。** 内置侧有 `AppleMusicHookProfiles`（`internal object`，`host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicHookProfiles.kt:432`）、`TargetAdaptation` 能力包（`host-api/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt:9-58`）、`AppleHookRegistrar` 的统一安装/记账（`host-applemusic/.../hooks/AppleHookRegistrar.kt:117-130`）。插件侧**一个都没有**：`plugin-api` 零依赖，只有 `getHostVersionName/Code`。所以 1609/1614/1626 的混淆差异 100% 落在插件作者身上（`docs/plugin-author-guide.md:243-264` 明确"作者维护 Apple Music 目标定位及版本适配"）。
2. **没有视图/属性所有权层。** 内置侧有 `HostViewSessionController` + `OwnedHostProperty`（`core/.../HostViewSessionController.kt:4,25`）、`HookRegistrations` 的方法级冲突登记（`hook-runtime/.../HookRegistrations.kt:29-35`）。插件侧只有 `claimResource(key)`——**逻辑 key，不落到具体 View/属性**（`PluginManager.kt:144-147`），冲突分析也只比较 `Executable` 与资源 key（`PluginRuntime` 侧：`PluginConflicts.kt:14-25`）。因此"行 translationY 归谁写"运行时完全不知道。
3. **没有代码/类的生命周期。** 启停/更新/删除**必须重启**（`docs/plugin-development.md:104`；UI 只写 `enabled` 标记：`EmbeddedPluginPages.kt:267-282` → `PluginPackages.kt:72-76`，并由 `PluginManager.kt:90-95` 计算出 `pendingRestart`）。已经装上的 libxposed hook 也没有物理撤销：`ModernXposedRuntime.hookMethod` 丢弃了 `HookHandle`（`ModernXposedRuntime.kt:61`），`HookRegistrationScope` 的注释直接写明"close does not assume a framework unhook handle exists"（`HookRegistrationScope.kt:1-3`），`PluginRegistration` 同样声明"does not promise physical framework unhook"（`PluginRegistration.java:3`）。虽然 libxposed API 102 本身提供 `HookHandle.unhook()`，运行时没用它。

### 4.3 另一个容易被忽略的坑：`exclusive` 会把自己锁死

- 内置 HLE 会给**每一个**它装的 hook 记一条 `HookRegistrations.register("AM++ HLE: …", builtin = true, target = executable)`（`AppleHookRegistrar.kt:127-128`），且**不设 access/exclusive**（默认 `HookAccess.UNKNOWN`、`exclusive=false`，`HookRegistrations.kt:7,29-31`）。
- 冲突判定：`blocking = a.exclusive || b.exclusive`，且只跳过"方法 + 任一方 OBSERVE"的组合（`PluginConflicts.kt:20-21`）。
- 所以插件若对**内置已经 hook 的同一个方法**声明 `exclusive = true` → 直接 `BLOCKED` 并关掉整个插件（`PluginManager.kt:120-127`）。歌词链路上内置 hook 覆盖很密（vmLine/lineCommit/payload 等，见 `01-line-change-motion-feasibility.md:132-140`），**插件对这类目标只能 `exclusive=false` 或纯 `observe`**。`docs/plugin-author-guide.md:325` 的建议（"不要给所有 Hook 默认开启 exclusive"）在这里不是风格问题，是硬约束。

### 4.4 隔离与超时（问题 3 的直接回答）

- **异常**：`PluginCallbacks.invoke` 捕获回调内 `Throwable` → `fail(session, …)`（`PluginManager.kt:156,173`）→ `session.close(FAILED, …)` 关掉作用域并把 `onStop` 投回主线程（`PluginLifecycle.kt:24-32`、`PluginManager.kt:54`）。此时**不提交**参数/返回值，宿主方法按原语义继续 → **单个插件回调抛异常不会拖垮宿主**。libxposed 侧还有 `ExceptionMode` 默认 PROTECTIVE 兜底（`module.prop` 未配置 exceptionMode）。
- **但不覆盖**：插件自建线程、`Choreographer` 回调、`View` 监听器、`ActivityLifecycleCallbacks` 里的异常 → 主线程崩 = 宿主崩；阻塞（哪怕 500 ms）→ 直接表现为掉帧/ANR。
- **超时/看门狗：没有。** `onLoad` 卡住只是让该插件永远停在 `LOADING`（`PluginTasks.kt:24-28` 无线程超时；`PluginLifecycle.kt:12-14` 初始态），不阻塞其他插件（`docs/plugin-development.md:92`）。Hook 回调也没有超时/耗时上限。

---

## 5. 需要补的 API（按优先级）

> 目标不是"让插件能做任何事"，而是"让换行动画这类**帧级视觉接管**有可控、可回滚、可仲裁的通道"。

**P0 — 不做则这类插件只能"黑盒硬怼"**

1. `PluginContext.getTargets(): PluginTargetResolver`
   `resolve(hookPoint: String, role: String): ResolvedTarget?`，返回 `Executable` + 契约版本 + 验证状态（`Verified/Degraded`），内部复用 `AppleMusicHookProfiles`（`AppleMusicHookProfiles.kt:432`）而不是让每个插件自己扫 DEX。至少需要 `lyricLineMotion` 区的 `animateMove`/`animateChange`（`01-line-change-motion-feasibility.md:144-153` 已给出 profile 形状）。
   → 补上后 (a)(b) 的"每版本重做"成本从"自己解 DEX"降为"声明 key"。
2. `PluginContext.getHostProfile(): PluginHostProfile`
   暴露 profile id + 支持的 hookPoint 列表 + `PluginUnsupportedException` 推荐文案；现在只有 `versionName/versionCode`（`PluginContext.java:12-14`），且不支持时插件**根本不会被加载**（`HookEntry.kt:98-103`），作者无法给用户解释"是这个 AM 版本没登记"。
3. 物理撤销 + 运行中停用
   `ModernXposedRuntime` 保留 `HookHandle` 并实现 `unhook()`（API 102 已有，`ModernXposedRuntime.kt:61` 目前丢弃）；`PluginManager` 增加 `disable(id)`（当前只有 `start()`，`PluginManager.kt:43-89`），把"启停必须重启"（`docs/plugin-development.md:104`）降为可选。
   → 补上后插件作者才可能做"改一行代码→重启插件"的迭代，而不是每次冷启 Apple Music。

**P1 — 视觉类插件的正确性基石**

4. `PluginContext.trackHostView(view): HostViewSession`
   把 `HostViewSessionController` 的 view 身份 + generation 语义开放（`core/.../HostViewSessionController.kt:4`），提供 `onDetach`/`onRecycled` 回调；顺带解决"平板双栏两个歌词 RecyclerView"的歧义（`01-line-change-motion-feasibility.md:248-250`）。
5. `PluginContext.claimProperty(scope, property)` + `ownedProperty(view, "translationY")`
   `OwnedHostProperty` 的公开版（`core/.../HostViewSessionController.kt:25`）：宿主写过就放弃所有权、只回滚仍是自己的值。并把 `claimResource` 升级成"我能看到别人也声明了这个属性"，让内置写入者与插件能互相让位（`claimResource` 现在只是 key 去重，`PluginManager.kt:144-147`）。
6. `PluginContext.frame(callback)` / `frameLoop`
   运行时代为 `postFrameCallback`：插件停止/宿主进后台/Activity 销毁自动取消；回调异常按 `PROTECTIVE` 处理而不是让主线程崩；重复注册去重。现在作者要自己 `Choreographer`（`LyricBlurViewSession.kt:32`）+ 自己 `onClose`，一旦漏了就泄漏帧回调。

**P2 — 让"替换"成为可能（当前唯一的硬不可行项）**

7. 宿主类型子类的注入通道
   两种等价形态：`PluginContext.defineClassHostChild(name, bytes)`，或给插件一个 `parent = getHostClassLoader()` 的加载器 + 暴露 `code.jar` 路径。没有它，(b) 的"`setItemAnimator(ours)`"就不可能（§3(b)）；顺带也解锁"自定义 Compose/RecyclerView 适配器"。
8. 冲突登记粒度
   内置 hook 目前以 `HookAccess.UNKNOWN` 记账（`AppleHookRegistrar.kt:127`），导致插件一旦 `exclusive` 就被"内置优先"一刀切（§4.3）。应让内置侧标注 `OBSERVE/MODIFY`，并给插件"协商/排队"而不是"二选一"。

---

## 6. 参考

- 运行时：`plugin-runtime/src/main/java/dev/amenhancer/plugin/runtime/PluginManager.kt`、`PluginPackages.kt`、`PluginLifecycle.kt`、`PluginTasks.kt`、`PluginCallbacks.kt`、`PluginConflicts.kt`、`PluginConflictChecks.kt`、`PluginEntries.kt`
- SDK：`plugin-api/src/main/java/dev/amenhancer/plugin/api/*.java`
- Hook 原语：`hook-runtime/src/main/java/dev/amenhancer/module/hook/{ModernXposedRuntime,HookRegistrationScope,HookRegistrations}.kt`
- 宿主接线：`app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt`、`EmbeddedBootstrap.kt`、`app/src/main/java/dev/amenhancer/module/ui/EmbeddedPluginPages.kt`
- 文档契约：`docs/plugin-development.md`、`docs/plugin-author-guide.md`
- 并行评估：同目录 `05-plugin-sdk-v1-capability.md`（SDK 表面视角，结论一致；本篇侧重 plugin-runtime 与宿主接线）
- 需求侧：`line-transition-notes/01-line-change-motion-feasibility.md`、`03-amll-spec-v1.md`
- 外部：libxposed API 102 `XposedInterface.java`（`hook(Executable)` 支持构造器、`HookHandle.unhook()`、`ExceptionMode.PROTECTIVE`）
