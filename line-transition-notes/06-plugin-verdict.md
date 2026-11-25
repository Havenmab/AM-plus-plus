# 插件可行性结论（换行动画，1626）

输入：`04-plugin-runtime-boundary.md`（运行时）、`05-plugin-sdk-v1-capability.md`（SDK 能力面）。

## 结论一句话

**部分可行。** 插件能在宿主进程里 hook 方法/构造器、否决宿主 `animateMove`、拿 `RecyclerView` 实例、写行 View 属性、自跑 `Choreographer` 弹簧、挂设置页；但**不能替换 ItemAnimator 实例**（宿主 ClassLoader 限制），**没有符号定位**（要自带版本表），**没有属性所有权与物理撤销**（停用要冷启）。

## 逐项判定

| 需求 | 判定 | 依据 / 限制 |
| --- | --- | --- |
| (a) hook `K#b(II)` / `u1(II)` | 可行（要自带版本表） | `PluginHooks` 支持 `Executable`；但 `AppleMusicHookProfiles` 是 `internal`，插件拿不到，只能自带混淆名或改用 `animateMove` 代偿换行事件 |
| (b) 接管 ItemAnimator | **部分**：可否决，不能替换 | `PluginCall.returnResult` → `param.result`，`before` 里 `returnResult(false)` 即跳过原方法；但 `ItemAnimator` 已改名 `RecyclerView$k`，插件 DEX 引 androidx 名会 `NoClassDefFoundError`，且拿不到 `code.jar` 路径另起 loader |
| (c) 拿歌词 RecyclerView | 部分 | 无视图/生命周期 API；只能 hook `RecyclerView.<init>`（顶层名保留）再按需筛选 |
| (d) 行 View 写 `translationY`/`RenderEffect`/`scale` | 可行 | 纯 framework API；但宿主已有 3 个 `translationY` 写入者，SDK 不仲裁 |
| (e) 自建 Choreographer 弹簧 | 可行 | 插件自管停表；SDK 无 `frameLoop`、无超时、异常不隔离 |
| (f) 设置开关页 | 可行 | `PluginSettingsSession.getView()` + `getDataDirectory()`；仅插件 ACTIVE 时可开，主题要自己包 Miuix |

## 三条硬限制

1. **不能在宿主 ClassLoader 下定义类型** → “换 ItemAnimator / 自定义 LayoutManager”在插件形态下不通，只能“否决 + 自绘”。
2. **无物理撤销 + 强制冷启**：`HookRegistrationScope.close()` 不保证 unhook，停用/更新/删除都要杀进程；插件 FAILED 后拦截点仍留在热路径。
3. **无属性所有权**：`claimResource` 只是 key 去重，`OwnedHostProperty` 不在 plugin-api。行 `translationY` 多写入者只能靠作者自律。

## 若要“插件形态”跑通，必须先补的 SDK（按必要性）

| 优先级 | API | 解决什么 |
| --- | --- | --- |
| P0 | `getHostProfile()` / `getTargets()`：把 `host-profiles` 的 hookPoint→Executable 暴露给插件（至少补 `lyricLineMotion` 的 `animateMove`/`animateChange`/`K#b`） | 让插件不必自带版本表，跨 1609/1614/1622/1626 通用 |
| P0 | `HookHandle` 物理 unhook + 运行中禁用 | 停用不再需要冷启 |
| P1 | `claimProperty(hostObject, name)` / `ownedProperty` | 与宿主/其他插件协商 `translationY` 等属性所有权 |
| P1 | `frameLoop { dtMs -> }`（随停止/后台自动取消、异常隔离） | 免去每个插件自建 Choreographer + 停表 |
| P2 | `trackHostView(view)`（身份/generation） | 解决 RecyclerView 复用下的视图错位 |
| P2 | 宿主类型子类注入通道 | 唯一能解锁“整只替换 animator”的能力 |
| P2 | 内置 hook 标注 `OBSERVE`/`MODIFY` | 允许插件与内置协商，而不是 `exclusive=true` 直接 BLOCKED |

## 建议路线

- **路线 1（快）**：直接在主项目 `host-applemusic` 里做（方案甲），没有任何 SDK 缺口，P0 打点一周内可上真机。
- **路线 2（插件优先）**：先补 P0 两项（`getTargets`/`getHostProfile` + 物理 unhook），再以插件实现；好处是功能可独立发布与回滚，代价是先做一轮 SDK 工程。
- **不建议**：带着“自带版本表 + 无 unhook + 无所有权”的现状硬做插件——每换一个 Apple Music 版本都要重发插件，且停用要杀进程。

## 交叉引用

- `01-line-change-motion-feasibility.md`：宿主实现与接入点
- `01-lyrics-line-transition-1626.md`：1626 的 DEX 证据
- `03-amll-spec-v1.md`：目标形态与参数
- `04-plugin-runtime-boundary.md` / `05-plugin-sdk-v1-capability.md`：本轮两份研究