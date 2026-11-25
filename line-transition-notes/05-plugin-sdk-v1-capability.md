# AM++ 插件 SDK v1 能力面评估（能否接管歌词换行动画）

- 工作树：`worktrees/line-transition`，分支 `feat/lyric-line-transition`（干净，只读研究）
- 对象：`plugin-api`（SDK v1，10 个 Java 文件）、`plugin-runtime`、`hook-runtime`、`app` 的插件装配、`docs/plugin-author-guide.md`、`docs/plugin-development.md`、`examples/basic-plugin/`
- 对照需求：(a)～(f)，来自 `01-line-change-motion-feasibility.md` / `01-lyrics-line-transition-1626.md` / `03-amll-spec-v1.md`
- 证据分级：**[SDK]** = `plugin-api` 公共接口；**[runtime]** = `plugin-runtime`/`hook-runtime` 实现（决定"文档说的"到底怎么落地）；**[app]** = `app` 模块装配与 UI；**[host]** = `host-profiles/*.json` 与另三份笔记的 DEX 结论；**[doc]** = 文档声明

---

## 0. 结论先行

**一句话**：SDK v1 的拦截面（方法/构造器 hook + 参数改写 + 返回值改写 + 跳过原方法）足以表达"拒绝宿主 `animateMove`、自己逐帧写行 `translationY`、自跑 `Choreographer` 弹簧、自带 Miuix 设置页"这套方案；但它不提供任何**符号定位**（无 DexKit、无 profile 只读接口），也不允许**子类化宿主里已改名的 androidx 类型**（`RecyclerView$ItemAnimator` 在宿主里叫 `RecyclerView$k`），所以"换掉整只 ItemAnimator / 自定义 LayoutManager"这条路在插件形态下**做不了**，只能 hook 拒绝 + 自己写属性。

三句话展开：

1. **能做的核心是 (b)(c)(d)(e)(f)**，而且其中 (b)(c) 可以**完全绕开混淆名**：hook `RecyclerView.<init>` 抓实例 → `getItemAnimator()` → 按形状 `(VH,int,int,int,int)boolean` 找 `animateMove` → `before` 里 `returnResult(false)` = Xposed 的 `setResult`。
2. **(a) 只能"作者自带版本表"**：`K#b(II)` / `LinearLayoutManager#u1(II)` 都是每版变化的混淆成员，SDK 只给 `getHostClassLoader()`；不过 (a) 的功能目的（知道"换行了、位移多少"）可以完全由 (b) 的 `animateMove(fromY,toY)` 代偿，所以 (a) 不是硬阻断。
3. **两个硬限制**：① 回调里抛任何异常 = **整个插件停机**（不是跳过这一次），60 fps 热路径上这是必须自己 try/catch 兜住的；② **没有物理 unhook、没有 hook 顺序、没有属性所有权原语** —— 与宿主已有的 `translationY` 写入者（`A#l`/`A#u0`）共存只能靠作者自律，SDK 不记账。

---

## 1. SDK v1 到底暴露了哪些原语

### 1.1 入口与生命周期（5 个回调）

| 原语 | 签名/位置 | 线程与时机 | 证据 |
|---|---|---|---|
| `onLoad(PluginContext)` | `AmppPlugin.java:5` | 后台线程，各插件并发；此时本插件回调**尚未激活** | [SDK] + [runtime] `PluginTasks.kt:24-28`、[doc] `plugin-author-guide.md:168` |
| `onStart()` | `AmppPlugin.java:6` | 主线程，冲突检查通过、作用域激活后 | [runtime] `PluginManager.kt:71-74`（`main.post`） |
| `onStop()` | `AmppPlugin.java:7` | 主线程，**尽力调用一次**（进程被杀不保证） | [runtime] `PluginLifecycle.kt:31`、[doc] `plugin-author-guide.md:174` |
| `createSettings(Context)` | `AmppPlugin.java:9`（注释即"Called on the UI thread, only while this plugin is active"） | 主线程，只有 ACTIVE 插件可开 | [runtime] `PluginManager.kt:96-99` |
| 入口类约束 | public、非抽象、无参构造、`classLoader === 插件 loader` | — | [runtime] `PluginEntries.kt:10-13` |

### 1.2 拦截原语：只有两条，都以 `java.lang.reflect.Executable` 定位

```java
// PluginHooks.java:5-7
PluginRegistration observe(Executable target, PluginObserver callback);
PluginRegistration hook(Executable target, boolean exclusive, PluginHook callback);
```

| 问题 | 答案 | 证据 |
|---|---|---|
| 方法 hook | ✅ `hook(Method, …)` | [SDK] `PluginHooks.java:7` |
| **构造器 hook** | ✅ `Executable` 包含 `Constructor`；运行时真的走 libxposed `hook(Executable)` | [SDK] 同上；[runtime] `ModernXposedRuntime.kt:54-61`；仓库自己在生产里 hook 构造器：`AppleHookRegistrar.kt:117-130` + 调用点 `AppleLibrarySurfaceHooks.kt:224-227`（`getDeclaredConstructor(...)` → `installHook(...)`）；[doc] `plugin-author-guide.md:16` |
| **静态方法** | ✅ 没有限制；`thisObject` 本就是 `Any?` | [runtime] `ModernXposedRuntime.kt:12`、`PluginCall.java:17` |
| 参数改写（replace） | ✅ `hook` 的 `PluginCall.getArguments()` 是**可变数组**，成功后 `copyInto(param.args)` | [SDK] `PluginCall.java:18`；[runtime] `PluginManager.kt:166-172` |
| 返回值改写 | ✅ `returnResult(value)`；`after` 里替换会顺带**清掉宿主异常** | [SDK] `PluginCall.java:21`；[runtime] `PluginManager.kt:170-172` |
| **阻止原方法执行**（≈`param.setResult`） | ✅ `before` 里 `returnResult(v)` → `returnEarly` → **不调 `chain.proceed`** | [runtime] `ModernXposedRuntime.kt:21-26, 34, 69-75`；`PluginManager.kt:168-172` |
| 指定异常 | ✅ `throwException(t)`（非 null 校验） | [SDK] `PluginCall.java:22`；[runtime] `PluginManager.kt:171` |
| 观察回调 | ✅ `PluginObserver` + `PluginObservation`，**严格只读**：`getArguments()` 返回 clone，类上无任何 setter | [SDK] `PluginObservation.java:5,17`；[runtime] 测试断言无 setter：`PluginRuntimeTest.kt:31-36` |
| **字段读写** | ❌ 没有任何原语（无 `getField`/`setField`/FieldHook）。只能自己对 `getReceiver()` 做普通 `java.lang.reflect`，且**不进入冲突记录** | [SDK] 10 个文件里无 Field 相关符号；[runtime] `HookRegistrations.kt:8-17` 只记 `Executable`/`resource` |
| 资源声明 `claimResource` | ✅ 但**只是冲突声明**：注册一条 `target=null, resource=key, access=MODIFY` 的记录，回调体为空 | [SDK] `PluginContext.java:21`；[runtime] `PluginManager.kt:144-147`；[doc] `plugin-author-guide.md:18,334`（"不是 UI 挂载工具"） |
| 设置页 View | ✅ `PluginSettingsSession.getView()` | [SDK] `PluginSettingsSession.java:3-5` |
| 卸载 | ⚠️ `PluginRegistration.close()` 只关"逻辑生命周期"，**不承诺物理 unhook** | [SDK] `PluginRegistration.java:3`；[runtime] `HookRegistrationScope.kt:3`、`ModernXposedRuntime.kt:62`（关闭后 `chain.proceed()` 透传）；[doc] `plugin-development.md:88` |

### 1.3 调用约定（最容易被误判的一块）

| 约定 | 事实 | 证据 |
|---|---|---|
| **线程** | 回调跑在**宿主调用目标方法的线程**；不保证主线程。歌词/动画路径天然是 UI 线程 | [doc] `plugin-author-guide.md:171`；[runtime] `ModernXposedRuntime.kt:61-79`（同步内联 intercept） |
| **事务性** | `PluginCall` 是**调用局部副本**；回调返回后才 commit；回调抛错 → 不提交、保留进入前的参数/结果/异常 | [SDK] `PluginCall.java:5`；[runtime] `PluginCallbacks.kt:8-16` |
| **提交前类型检查** | 逐参 `isInstance` 校验（含装箱）；`Method` 还要校验返回类型（`void` 放行）；`Constructor` 跳过返回类型检查 | [runtime] `PluginCallbacks.kt:17-38`（第 20 行是构造器分支） |
| **异常处理** | 回调抛错 / 类型校验失败 → `fail(session)` → **整个插件 FAILED、作用域关闭、`onStop` 派发一次**；已提交的前序修改保留 | [runtime] `PluginManager.kt:156,173` → `PluginLifecycle.kt:24-32`；[doc] `plugin-author-guide.md:237,262` |
| **跳过原方法的副作用** | `before` 里 `returnResult` 对**构造器**也生效 → 会跳过构造体（对象未初始化）；对 `void` 方法等价"吞掉" | [runtime] `ModernXposedRuntime.kt:69-75`（无方法种类判断） |
| **同一目标可注册多次** | ✅ 每次 `hook` 都是一次独立 `module.hook(...)`；冲突分析对同 owner 直接跳过 | [runtime] `PluginManager.kt:162-177`、`PluginConflicts.kt:16` |
| **注册时机** | `onLoad` 之外也可以（`onStart`/回调内），只要作用域未关；但**动态注册会同步触发冲突复查**，可能在宿主调用栈里就把插件关掉 | [runtime] `PluginManager.kt:182-196`、`HookRegistrations.kt:32-36`、`PluginConflictChecks.kt:8-15` |
| **deopt** | 安装前对目标 `deoptimize`，避免"已 AOT 编译 → hook 装了但绕过去" | [runtime] `ModernXposedRuntime.kt:56-60` |
| **虚拟派发** | ⚠️ hook 的是**那一个 Executable**；父类方法装 hook **不会**拦到子类 override（除非 override 里 `super` 调用）。这是 (a) 必须精确命中宿主实现类的原因 | [runtime] 无"覆盖集"辅助；`examples/basic-plugin/…/BasicPlugin.java:15` 观察 `Activity.onResume` 只能收到"没 override 的 Activity" |

### 1.4 `PluginContext` 里到底有什么（全文 22 行）

```java
int API_VERSION = 1;                 // :9
Application getApplication();        // :10
ClassLoader getHostClassLoader();    // :11  ← 唯一拿宿主 loader 的入口
String getHostPackageName();         // :12
String getHostVersionName();         // :13
long getHostVersionCode();           // :14
PluginHooks getHooks();              // :15
File getDataDirectory();             // :16
File getCacheDirectory();            // :17
InputStream openAsset(String);       // :18
void log(String, Throwable);         // :19
void onClose(Runnable);              // :20
PluginRegistration claimResource(String, boolean); // :21
```

- `getHostClassLoader()` 的真实值 = `HookEntry` 里 libxposed 给的 `param.classLoader`（即 Apple Music 的 PathClassLoader）→ [app] `HookEntry.kt:67,191`。
- 插件的 `DexClassLoader` 父加载器是 **AM++ 自己的 classloader**（`AmppPlugin::class.java.classLoader`）→ [runtime] `PluginManager.kt:63-64`。后果：插件能看到 AM++ 内部类与模块资源（**非契约**），但**看不到别的插件**。
- **按名字找类/方法只有裸反射**：`loader.loadClass(...)` + `getDeclaredMethod(...)`。SDK 不含任何反射辅助、形状匹配、签名校验、DexKit 包装、profile 查询。[doc] 给的范式就是手写 `resolveNoArgMethod`（`plugin-author-guide.md:246-258`），并明确"混淆名/版本适配是作者的责任"（`plugin-author-guide.md:12,16,264`；`plugin-development.md:11`）。
- **没有任何**"读 AM++ 设置/能力"的入口（22 行里没有），插件无法直接问"AM++ 的歌词模糊/读音是否开着"，只能自己 hook 观测。

### 1.5 设置页（`PluginSettingsSession`）

| 能力 | 事实 | 证据 |
|---|---|---|
| 交付方式 | 返回一个 **`android.view.View`**，宿主塞进 AM++ 设置 → 插件 → 具体插件 → "插件设置" 子页 | [SDK] `PluginSettingsSession.java:3-5`；[app] `EmbeddedPluginPages.kt:292-300`、`EmbeddedSettingsPages.kt:135-139,273-274` |
| 入口位置 | **只能**在"AM++ 设置 → 插件管理 → 该插件"下；不能注册到 AM++ 主设置分类，也没有播放器按钮/菜单插槽 | [doc] `plugin-author-guide.md:18`；[app] 仅有 `openPluginSettings` 一条路径 |
| 顶栏/返回/转场 | 宿主提供；插件只给正文，**不得重复画顶栏、不得调 Activity 返回** | [doc] `plugin-author-guide.md:270`；[app] `EmbeddedSettingsPages.kt:334-339` |
| View 所有权 | 每次 `createSettings` 必须返回**未 attach** 的新 View（`check(it.parent == null)`）；宿主关闭会话时调 `close()`，插件要能重复调用 | [app] `EmbeddedPluginPages.kt:438`、`PluginManager.kt:104-110` |
| 滚动 | 宿主把正文根的 `isNestedScrollingEnabled` 置 true，并要求正文**真的派发**嵌套滚动事件；Compose 正文还得自己接到 View 父级 | [app] `EmbeddedSettingsPages.kt:324-333`；[doc] `plugin-author-guide.md:274-276` |
| Context 主题 | 传进来的是 `ContextThemeWrapper(activity, Theme_Material[_Light])`，**不是 Miuix 主题**；Miuix 主题要在插件自己的 Compose 树里包 | [app] `EmbeddedSettingsTheme.kt:63-66` |
| 开关/滑杆 | SDK 层只认 View；**控件本身是纯客户端实现**（自带 View 或自带 Compose+Miuix），SDK 不提供控件 | [doc] `plugin-author-guide.md:16,272,276,280`；示例用原生 `Switch`：`examples/basic-plugin/…/BasicPlugin.java:34` |
| 持久化 | ✅ 只有"给你两个目录"：`getDataDirectory()`/`getCacheDirectory()`（按插件 id 隔离，更新保留、删除清理）；无 SharedPreferences 命名空间、无设置 schema | [SDK] `PluginContext.java:16-17`；[runtime] `PluginManager.kt:139-140`；[doc] `plugin-author-guide.md:284-291`；示例写文件：`BasicPlugin.java:33-38` |
| 兼容桥 | `PluginSettingsScrollBridge.updateTitle/updateLargeTitle/report` 仍存在，但**不在 SDK 里**，是 `app` 的 `internal object`，只作老插件兼容 | [app] `EmbeddedPluginPages.kt:451-478` |

### 1.6 明确不存在的能力（一次列清）

| 不存在 | 依据 |
|---|---|
| 字段 hook / 字段读写原语 | [SDK] 无符号；[runtime] 只登记 `Executable` |
| 混淆符号解析（DexKit / 按形状扫描 / profile 查询） | `plugin-author-guide.md:180-200,246-260` 全程手写反射；`plugin-development.md:39` 明确 SO/AAR 资源不属于 v1 |
| 访问 `host-profiles/*.json`（模块已有 `lyricBlur` 符号表：`lineBind/linePayload/rowPosition`、`fields.recycler` 等） | [host] `host-applemusic/src/main/resources/host-profiles/7.0.3-1626.json`；SDK 无入口（`AppleMusicHostProfiles` 属 `host-applemusic` 模块，非 plugin-api） |
| 播放器按钮/菜单等 UI 插槽、歌词来源接口 | [doc] `plugin-author-guide.md:18` |
| Android XML 布局 / `R.drawable` / AAR 资源 / SO / 插件自己的 Activity/Service | [doc] `plugin-development.md:39`、`plugin-author-guide.md:280,352-362` |
| 物理 unhook（真卸载） | [SDK] `PluginRegistration.java:3`；停用/更新/删除**都要重启生效**（`plugin-development.md:104`） |
| hook 顺序/优先级 | [runtime] `HookRegistrations.kt:19`（"Never changes framework interception or registration order"）；[doc] `plugin-author-guide.md:325` |
| 属性所有权 / 回滚 / 撤销已写入的宿主对象 | [doc] `plugin-author-guide.md:20`（"无法回滚你直接修改的宿主对象"） |
| 线程/帧循环托管 | [doc] `plugin-author-guide.md:20`（"不管理你未登记的线程"） |
| hook 级故障隔离（只废掉一条注册） | [runtime] `PluginManager.kt:156,173` 全部走 `fail(session)` |

---

## 2. 逐条对照需求 (a)–(f)

### 2.0 先讲一条贯穿全局的硬约束：宿主里的 androidx 类型**已经改名**

- `RecyclerView` 顶层类名保留，但**嵌套类型全改名**：`ItemAnimator` → `RecyclerView$k`、`ViewHolder` → `RecyclerView$D`、`LayoutManager` → `RecyclerView$n`、`Adapter` → `RecyclerView$f`（[host] `01-lyrics-line-transition-1626.md:37,219`；`01-line-change-motion-feasibility.md:39,67`）。
- 后果 1：插件**不能**用 `compileOnly("androidx.recyclerview:recyclerview")` 直接写 `extends RecyclerView.ItemAnimator` —— 编译产物引用的 `androidx/recyclerview/widget/RecyclerView$ItemAnimator` 在运行时不存在（真名是 `$k`）→ `NoClassDefFoundError`。
- 后果 2：**"整只替换 ItemAnimator"和"自定义 LayoutManager"在插件形态下做不了**（除非运行时生成 DEX，不在 v1 边界内）。
- 后果 3：宿主侧类型必须**全程 `Object`/`Class<?>` + 反射**；只有 `android.view.View`/`ViewGroup` 这类 framework 类型可直接强转（不会被改名）。

### (a) hook `PlayerLyricsViewFragment.K#b(II)` 或 `LinearLayoutManager#u1(II)`

| 维度 | 结论 |
|---|---|
| SDK 原语够不够 | **够**。`hook(Method, false, PluginHook)` 能在 `before` 读两个 int、也能 `returnResult` 直接吞掉；只观测就用 `observe(...)`（永不参与冲突）[SDK] `PluginHooks.java:6-7`、[runtime] `PluginConflicts.kt:20` |
| 能不能定位 | **要作者自己带版本表**。`K#b`、`u1` 都是每版变的混淆成员；`u1(II)` 在 1626 还有 `y1`/`z1` 两个同形状候选（[host] `01-line-change-motion-feasibility.md:88,90`）。SDK 只给 loader，没有形状匹配/歧义上报 |
| 有没有更省事的代偿 | **有**：`animateMove(VH,fromX,fromY,toX,toY)Z` 同时给出"换行了"和"位移多少"，且可以**零混淆名**拿到（见 (b)）。(a) 的本质是"换行事件 + 位移量"，(b) 的 hook 完全覆盖 |
| 判定 | **部分可行**：机制可行，定位不可行（除非作者维护 `{1614,1622,1626}→"K"/"u1"` 之类的表，或干脆走 (b)） |

风险补充：`K#b` 是"当前行"的唯一判定点（[host] `01-lyrics-line-transition-1626.md:15`、`01-line-change-motion-feasibility.md:208`），但它同时被宿主用来做高亮/记账；在 `before` 里 `returnResult` 会连高亮都吞掉。**只读观测必须用 `observe`，不要用 `hook`**。

### (b) 拦截/替换自定义 ItemAnimator（`mc.y#m` / `PlayerLyricsViewFragment$u`）

| 子目标 | 可行性 | 做法 / 依据 |
|---|---|---|
| 拿到 animator 对象 | ✅ 可零混淆名 | hook `androidx.recyclerview.widget.RecyclerView` 的**构造器**（类名保留、构造器是 `Executable`）→ `after` 里 `getReceiver()` 就是实例；再反射调 `getItemAnimator()`（1626 保留原名，[host] `01-line-change-motion-feasibility.md:67`）。兜底：对 `RecyclerView` 的 0 参方法逐个 `invoke`，按"返回对象是否含 `(VH,int,int,int,int)boolean`"反向确认 |
| 找到 `animateMove` | ✅ 可零混淆名 | 对 `animator.getClass().getDeclaredMethods()` 做形状匹配：非 static、返回 `boolean`、5 参、第 0 参类型名前缀 `androidx.recyclerview.widget.RecyclerView$`、其余 `int`（历史分支已验证同形状在 1614/1622/1626 成立，[host] `01-line-change-motion-feasibility.md:39`） |
| 拒绝宿主动画 | ✅ | `hook(animateMove, true, …)` + `before` 里 `call.returnResult(Boolean.FALSE)`（= Xposed `setResult`，跳过原方法）[runtime] `ModernXposedRuntime.kt:69-75` |
| 保留宿主记账、只砍视觉 | ⚠️ 可表达，语义待真机验证 | 改用 `after` 里 `returnResult(false)`：原方法（含 `V0` 队列/`U0`/`J4.t2` 记账）先跑完，再把 false 交回 RecyclerView → 不进入 pending 动画。`mc.y#m` 内部已写过 `setTranslationX/Y`（[host] `01-line-change-motion-feasibility.md:181`），残留值需自己归零 |
| **替换整只 ItemAnimator** | ❌ | 需 `extends RecyclerView$k`，而该类型已改名（见 2.0）；`setItemAnimator` 的参数改写技术上可行（参数可写），但没有可用的 animator 实现类可传 |
| 独占性 | ✅ | AM++ **完全没有** hook ItemAnimator（全仓 0 处 `ItemAnimator/animateMove/getItemAnimator`），`exclusive=true` 不会与内置冲突；第二个插件抢同一目标才会被拦 |
| 判定 | **可行（hook 拒绝 + 自绘位移）**；"整只替换"不可行 |

### (c) 拿到歌词 `RecyclerView` 实例（`onCreateView` 之后）

| 做法 | 可行性 | 说明 |
|---|---|---|
| hook `RecyclerView.<init>` 抓所有实例 | ✅ | 类名保留、构造器可 hook（见 1.2）。零混淆名 |
| 从 fragment 侧拿 | ⚠️ 要版本表 | `lyricBlur.fields.recycler` = `q8.X4#d0`、`fields.binding` = `l0`（[host] `7.0.3-1626.json`），每版字段名变；或 hook `PlayerLyricsViewFragment#onCreateView`（类名+方法名都保留）→ `after` 里从 `getResult()` 的 View 树里找 |
| 区分"哪个 RV 是歌词列表" | ⚠️ 启发式 | 可用 `rv.getAdapter().getClass().getName()`（类名保留，含 `PlayerLyricsViewFragment`）、child 数、`getItemAnimator()` 的类名含 `PlayerLyricsViewFragment$`。平板双栏可能同时存在两个（[host] `01-line-change-motion-feasibility.md:248-250` 风险 8） |
| 判定 | **可行（启发式需作者真机验证）** |

### (d) 在行 View 上写 `translationY` / `RenderEffect` / `setScaleX/Y`

| 维度 | 结论 |
|---|---|
| 能不能写 | ✅ 完全是 framework API：`Object` 强转 `ViewGroup` → `getChildCount()/getChildAt(i)`（public，不需反射）→ `View.setTranslationY/setScaleX/setRenderEffect`。`ViewGroup` 是 framework 类型，**不受宿主改名影响** |
| `RenderEffect` | 需 API 31+，插件 `minAndroidApi` 至少 26，要自己判 `Build.VERSION.SDK_INT`；<31 降级（[host] `02-amll-per-line-visual-model.md:434-438`） |
| SDK 有没有帮忙 | ❌ 没有属性所有权原语。仓库自己的 `OwnedHostProperty`（`core/…/HostViewSessionController.kt:25`）**不在 plugin-api 里**，插件要用只能自己重写 |
| 风险（宿主已有写入者） | 行 `translationY` 已有 `A#l`/`A#u0`/`fragment.C#c` 三个写入者；行缩放归宿主（`V0$N` 写 pivot、`V0$b`/`A$s#onAnimationEnd` 写 scale、`V0#l` 读 scale）→ [host] `01-line-change-motion-feasibility.md:239-246`。SDK 对此**不记账、不回滚**（[doc] `plugin-author-guide.md:20`） |
| 判定 | **可行**（技术无障碍，正确性靠作者自律：每帧写绝对值、`lineRecycle` 里清零、不写 `view.tag`） |

### (e) 跑一个 `Choreographer` 弹簧循环

| 维度 | 结论 |
|---|---|
| 可行性 | ✅ 纯客户端代码。在 hook 回调（UI 线程）里 `Choreographer.getInstance()` 最稳，或用 `Handler(Looper.getMainLooper())` |
| 生命周期 | ⚠️ SDK **不托管**线程/帧循环：必须自己 `runtime.onClose(...)` 里 `removeFrameCallback` + 停表（`PluginContext.java:20`，[doc] `plugin-author-guide.md:306-313`）。忘停 = 插件停了还在写 View |
| 性能 | 单循环写 N 个 child 的 `translationY` 可忽略；**不要**改成每行一个 `ViewPropertyAnimator`（[host] `01-line-change-motion-feasibility.md:233-237`） |
| 钩子自身开销 | ⚠️ 每次 hook 调用都会 new `PluginCall` + clone args + 逐参校验 + `copyInto`（[runtime] `PluginCallbacks.kt:8-38`）。每次换行 ~10–20 次调用无感；但**别把逐帧逻辑放进 hook 回调**，只放"起表" |
| 判定 | **可行** |

### (f) 提供自己的设置开关界面（Miuix 规范）

| 维度 | 结论 |
|---|---|
| 开关/滑杆 | ✅ 自己实现（原生 View 或 Compose）。SDK 只给 View 容器；Miuix 主题要自己包（传入 context 是 `Theme_Material`，[app] `EmbeddedSettingsTheme.kt:63-66`） |
| 出现在宿主设置里 | ✅ 但**只有一条路径**：AM++ 设置 → 插件 → 本插件 → "插件设置"（仅 ACTIVE 时显示）[app] `EmbeddedPluginPages.kt:292-300` |
| 持久化 | ✅ 自己写 `getDataDirectory()` 下的文件（示例即如此：`BasicPlugin.java:33-38`）。无 sharedPreferences、无 schema、无迁移工具 |
| 即时生效 | ✅ 同进程：设置页写的内存状态，hook 回调里直接读。但**没有"设置变更广播"**，需自己维护 volatile/Atomic 字段 |
| Miuix 依赖 | ⚠️ 作者自带。v1 不允许 AAR 资源（`plugin-development.md:39`），自带资源表有风险；靠宿主已打包的 Miuix 走父加载器"能跑但非契约"（[doc] `plugin-author-guide.md:276`） |
| 滚动互操作 | ⚠️ 宿主强制 `isNestedScrollingEnabled=true` 并要求正文派发嵌套滚动；Compose 正文还要自己接父级（[app] `EmbeddedSettingsPages.kt:324-333`；[doc] `plugin-author-guide.md:274-276`） |
| 判定 | **可行（有样板量）** |

---

## 3. 冲突与隔离

### 3.1 `claimResource` 的真实语义

- 实现：`register(session, target=null, resource=key, access=MODIFY, exclusive)`，回调体 `{ _, _ -> }` → [runtime] `PluginManager.kt:144-147`。
- 冲突判定（`PluginConflicts.kt:14-25`）：
  - 只有 `resource` 相同才比较；**两个都非独占 → 直接 `continue`（连警告都不发）**（第 22 行）。
  - 任一 `exclusive` → `blocking=true` → 相关插件被 `close(BLOCKED)` 并提示重启（`PluginManager.kt:120-127`）。
- **它不挂 UI、不仲裁歌词来源、不扫描/回滚视图修改**（[doc] `plugin-author-guide.md:18,334`）。

### 3.2 多插件同时 hook 同一方法

| 情形 | 行为 | 证据 |
|---|---|---|
| 观察者 × 观察者 / 观察者 × 修改者 | 不算冲突 | `PluginConflicts.kt:20` |
| 修改者 × 修改者（都非独占） | **警告**（非阻断），两者都跑；**顺序无保证** | `PluginConflicts.kt:21-24`；`HookRegistrations.kt:19`；[doc] `plugin-author-guide.md:322,325` |
| 任一 `exclusive` | **阻断**，双方插件都被关，用户改启用状态后重启 | `PluginManager.kt:120-127`；测试 `PluginRuntimeTest.kt:77-91` |
| 插件独占 × AM++ 内置（内置记 `HookAccess.UNKNOWN`） | **内置优先，插件被阻断** | 内置登记不带 access：`ModernXposedRuntime.kt:81-83`、`AppleHookRegistrar.kt:127-129`；测试 `PluginRuntimeTest.kt:92-99` |
| 同一插件注册多次 | 不冲突（`a.owner == b.owner` 跳过） | `PluginConflicts.kt:16` |

**实践含义**：想独占 `animateMove` 可以（AM++ 没碰它）；但如果插件也要 hook `RecyclerView#onTouchEvent/setScrollState`（AM++ 的 `lyricBlur` 已 hook），**必须 `exclusive=false`**，否则自己被挡。

### 3.3 插件之间可见性

| 维度 | 事实 |
|---|---|
| 类级别 | 每个插件一个 `DexClassLoader`，父加载器是 AM++ 的 classloader（[runtime] `PluginManager.kt:63-64`）→ **插件 A 看不见插件 B 的类**；`PluginEntries` 还要求入口类必须由自己的 loader 定义（`PluginEntries.kt:11`） |
| 进程级别 | 同进程、同 UI 线程、共享宿主对象。插件 A 写的 `translationY` 插件 B 能看见（但不知道是谁写的） |
| 对 AM++ | 插件**能**从父加载器摸到 AM++ 内部类与模块资源（含 `host-profiles/*.json`）——**非契约、无兼容承诺**；而 `host-profiles` 的 `lyricBlur` 区恰好含 `lineBind/linePayload/rowPosition/registerChildListener` 与 `fields.recycler`（[host] `7.0.3-1626.json`），但**不含** `animateMove`/`animateChange`/`K#b` |
| 隔离强度 | 与文档一致："同进程执行，不是隔离沙箱"（[doc] `plugin-development.md:5`） |

---

## 4. 结论：对照 (a)–(f)

| 需求 | 判定 | 关键理由 | 缺什么 |
|---|---|---|---|
| (a) hook `K#b(II)` 或 `u1(II)` | **部分** | 机制够（`hook`/`observe` 任意 `Executable`），但符号是每版混淆名，SDK 无定位辅助 | 符号表只读接口 / 形状匹配 + 歧义上报 |
| (b) 拦截 ItemAnimator `animateMove` | **可行（仅"hook 拒绝"）** | 形状匹配 + `before→returnResult(false)` 完全可表达；AM++ 未占用，可独占 | "整只替换"需可子类化的宿主类型 → 不可行 |
| (c) 拿到歌词 RecyclerView | **可行（启发式）** | `RecyclerView` 类名保留 + 构造器可 hook + `getItemAnimator/getAdapter` 保留 | 无"按角色取宿主对象"的稳定接口，双栏/多实例靠启发式 |
| (d) 行 View 写 `translationY/RenderEffect/scale` | **可行** | 纯 framework View API，无 SDK 参与 | 属性所有权/租约原语（宿主已有 3 个 `translationY` 写入者） |
| (e) `Choreographer` 弹簧循环 | **可行** | 纯客户端；只要自己在 `onClose` 里停 | 帧循环托管（可选） |
| (f) 自带设置开关页 | **可行（样板量偏大）** | `PluginSettingsSession` + `getDataDirectory` + 插件管理子页入口齐全 | 设置 schema / Miuix 设置页构建器（可选） |
| **整体：接管歌词换行动画** | **部分可行** | (b)(c)(d)(e)(f) 可做 → 方案 A / 方案乙 的"拒绝 animateMove + 整块 `translationY` 弹簧"落地路径通；方案 C（自定义 LayoutManager / 替换 animator）不通 | 见 §5 |

> **与 `04-plugin-runtime-boundary.md` 的口径差异（同一结论的两种记法）**：那份笔记把 (a) 记为"可行"，本文记为"部分"。差别只在记账口径——(a) 的**机制**在 SDK 里完全可行（任意 `Executable` 都能 `hook`/`observe`、能读参、能改参、能跳过），本文扣分点仅是"SDK 不提供符号定位，混淆名与版本表按契约属作者责任"。若把"作者自带版本表"视为正常适配成本，则 (a) 应记为**可行**；若把"作者必须自己反编译每个 AM 版本"视为能力缺口，则为**部分**。两者都不改变落地路径。
**风险不变**：SDK 可行性 ≠ 宿主语义安全。`mc.y#m` 里夹着宿主换行记账（`V0` 队列 / `U0` / `J4.t2` 投递），拒绝它会一起跳过（[host] `01-line-change-motion-feasibility.md:13,75`）。这条风险和插件形态无关。

---

## 5. 需要补的 API（按价值排序）

> 前 4 条决定"插件能不能做到主项目同级别的事"，后 5 条决定"做得累不累、错得快不快"。

1. **只读宿主符号/契约查询**（最高价值）
```java
interface PluginContext { HostSymbols getHostSymbols(); }        // 版本已 pin 的只读视图
interface HostSymbols {
    Set<String> profiles();                    // "lyricBlur@1" …
    Class<?>   type(String role);              // 用宿主 loader 解析
    Executable method(String role);            // 精确签名；缺失返回 null（不猜）
    Field      field(String role);
    Executable methodByShape(Class<?> owner, String returnType, String... parameterTypes);
}
```
   理由：模块已有 `host-profiles/7.0.3-1626.json`（`lyricBlur` 区的 `lineBind/linePayload/rowPosition/fields.recycler` 齐全），只是没暴露；这是唯一能让 (a) 从"部分"变"可行"的改动。同时需补两个 profile 区：`lyricLineMotion`（`animateMove`/`animateChange`）与 `lineChanged`（`K#b`）。

2. **hook 级故障隔离**
```java
enum HookFailure { FAIL_PLUGIN, DISABLE_THIS_HOOK, LOG_ONLY }
PluginRegistration hook(Executable t, boolean exclusive, HookFailure policy, PluginHook cb);
```
   理由：现在回调一抛错就是整插件 FAILED（`PluginManager.kt:156,173`）。换行动画是 UI 热路径，一次 NPE 就静默失效到重启。

3. **属性所有权 / 租约**
```java
interface PluginContext { PropertyLease lease(Object target, String property); } // 只在仍是自己的值时才回滚
```
   理由：仓库内部已有 `OwnedHostProperty`，插件拿不到；行 `translationY` 有 3 个宿主写入者，没有这套原语就只能"看起来在改、其实被覆盖"。

4. **可子类化的宿主类型，或明确的"禁止替换"约束**
   至少文档化："`RecyclerView$k` / `RecyclerView$n` 已改名，插件不得 `extends`"。更好的是提供 **hook 组合原语**（前后插入/返回值序列），替代"整只替换 animator"。

5. **帧循环绑定作用域**：`getFrameClock()`，`onClose` 自动 `removeFrameCallback`；省掉"插件停了还在写 View"。

6. **物理 unhook，或至少"停用即完全静默"**：现在 `close()` 只透传，拦截点永久留在热路径（`ModernXposedRuntime.kt:62`）。

7. **hook 顺序/优先级**：现在完全无保证（`HookRegistrations.kt:19`）。两个插件都拒绝 `animateMove` 时，行为取决于安装顺序。

8. **`claimResource` 与宿主 key 对齐**：让 AM++ 自己也声明 `lyrics:row-transform` 之类的 key，插件才能"和内置协商"而不是"被内置压制"。

9. **设置项 schema + Miuix 设置页构建器**（减样板）：`SettingsSpec`（bool/float/enum）→ 自动生成 Miuix 页并落盘，省掉每个插件重写开关+持久化+滚动互操作。

10. **构造器 hook 的安全模式**：现在 `before→returnResult` 可以吞掉构造器（对象未初始化）；建议 API 层禁止对 `Constructor` 使用 `returnResult`。

---

## 6. 如果真要做，最小真机验证路径（ZIP 插件形态）

1. 造一个"只观测"的 ZIP 插件：hook `RecyclerView.<init>` → 收集实例 → 反射 `getItemAnimator()` → 形状匹配 `(VH,int,int,int,int)Z` → `observe`（**不是 hook**，不产生冲突也不改行为）→ 打一行限流日志 `dy=fromY-toY, type, childCount`。
2. 验收（照 `01-line-change-motion-feasibility.md:262-281` 的 H1）：连续换行 30–60 s，`animateMove` 次数 / 行变化次数 ≈ 1，`dy` 方差 ≈ 0 且 ≈ 行高。
3. H1 成立 → 换 `hook` + `before→returnResult(false)`，跑整块 `translationY` 弹簧；不成立 → 转"上一帧位置表"（方案 D）。
4. 期间盯两个 SDK 侧指标：插件状态是否被打成 FAILED（任何回调异常都会）、以及 `adb logcat -s AppleMusicEnhancer` 里有没有 `Plugin … failed`。

---

## 7. 未决 / 不确定

| # | 不确定点 | 影响 | 怎么解 |
|---|---|---|---|
| U1 | 插件能否**稳定**通过父加载器读到 `host-profiles/*.json`（模块资源是否随 APK 打包、R8 是否保留该路径） | 决定"绕开 SDK 拿符号表"这条旁路是否可用 | 真机：`AmppPlugin.class.getClassLoader().getResourceAsStream("host-profiles/7.0.3-1626.json")` |
| U2 | `RecyclerView#setItemAnimator/getItemAnimator` 在 1614/1622 是否同样保留原名 | (b) 的入口；保留则零混淆名，否则退化为形状探测 | 各版本 DEX 核对（目前只有 1626 证据） |
| U3 | `hook` 的 `after` 里 `returnResult(false)` 能否"保留宿主记账 + 砍掉视觉动画" | 决定 (b) 是"低破坏"还是"必须复刻记账" | 真机数 `i1#G/I/M/N` 与 `V0` 队列长度 |
| U4 | 插件自带的 Compose/Miuix 在无资源表 ZIP 里能否正常运行 | 决定 (f) 是"自带 Miuix"还是"只能原生 View" | 打一个最小 ComposeView 插件试 |
| U5 | 平板双栏下是否同时存在两个歌词 RecyclerView | (c) 的实例选择 | 真机计数 |
