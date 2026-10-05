# Apple Music 1606 平板底部间距修复

AM++ 的“底栏高度”保存为 `phoneLiquidGlassBottomGapDp`，语义是底部间距，范围 0～48dp，默认 16dp。7.0 平板会话此前没有读取这个值，mini 和玻璃直接跟随原生布局，因此调整没有效果。

## 原生布局证据

- `layout-w600dp/fragment_music_content.xml`：`mini_player_blur` 是内容根下的底部约束 sibling，播放器是原生 BottomSheet。
- `fragment_player_main.xml` / 各宽度 `mini_player.xml`：mini 内容位于播放器容器顶部，其触摸、按钮和封面属于原生播放器。
- `q8.T4` 绑定把 mini 背景的底部 margin 设置为系统导航栏留白加 `miniplayer_to_navtabs_spacing`；该资源在 `values-w600dp` 为 10dp。
- `MusicContentFragment$initializePlayerUI$1` 的原生高度观察者将 mini 高度、导航高度、系统栏高度相加后调用 `BottomSheetBehavior.L(int, boolean)`。
- `q8.H0.A(View, float, float)` 是上述 margin 的静态写入入口，最终只改上下 margin 并提交原生 LayoutParams。
- `PlayerMainFragment$i.d(float)` 在 `player_root` 内读取 mini 封面坐标并执行原生缩放、位移、圆角和视频 Matrix。

## 实现

`app/FragmentTabletGlassSession` 读取现有间距设置并按当前 density 转成像素，通过 `host-api/FragmentTabletChromePort.setMiniBottomGap` 交给宿主适配器。间距保存后最多在下一次 500ms 设置轮询更新，不需要重建玻璃。

`host-applemusic/FragmentTabletChromeBinding` 将“设置间距减原生 10dp 间距”的同一偏差应用于原生 mini 背景 bottom margin 和播放器 peek 高度。系统栏留白、mini 尺寸、顶部导航及展开终点保持原生布局。收起高度变化由原生 BottomSheet 重排整个 mini 内容与触摸区域；模块玻璃继续从原生背景采样边界，不独立移动封面。

原生高度和 margin 写入通过现有 Fragment hook scope 按具体 Behavior/View 身份处理。精确 profile 新增 tablet margin 方法和间距资源资料；包括与模块当前值相同的原生写入也更新恢复基准。`core/OwnedHostIntOffset` 分离原生基准与模块偏差，防止重入累加；无当前歌曲的 0 高度和自动 peek 的 -1 保留原生语义。关闭、隐藏 mini、宿主替换及会话销毁时释放对应属性，仅恢复仍由模块持有的值。

同时调整 margin/peek 后等待一次布局，再读取原生边界，避免玻璃先移动而内容晚一帧。封面动画、原生双栏封面居中、40% 玻璃材质和手机接入均不改变。

## 验证与待验收

新增 JVM 行为测试覆盖 48/64px mini 在 0/16/48 间距下的背景与 sheet 对齐、重复更新、旋转后的系统栏留白、等值原生写入、重入写入、0/-1 语义及属性恢复。

执行 core、host-api、host-applemusic、app 单元测试，Debug Lint、Release Lint 和签名 Release 构建，以及架构/profile/玻璃源码检查。提供安装包的 1606 静态 profile 校验和新增 margin/dimension 契约另行核验；具体结果记录在本任务 progress 中。

当前未连接设备。真机需要检查 0、16、48 三档，横竖屏、背景返回、mini 点击/按钮/拖动、单栏与双栏展开/收起及关闭玻璃后的恢复。静态和 JVM 检查不能替代这些视觉验收。
