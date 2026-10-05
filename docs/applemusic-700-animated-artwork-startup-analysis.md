# 1606 双栏启动动态封面停留旧帧：排查记录

## 用户现象

启动时恢复的上一首歌有动态封面，平板双栏中的封面停在静止画面。之后换歌，双栏歌名和歌词正常更新，mini 封面正常，完整封面仍显示旧画面。退出重进可以恢复。底部间距测试包已经用户确认可用，需要保留。

## 当前确认的源码路径

- 7.0 使用 `FragmentEditorialVideoTarget`，没有安装旧版平板动态封面 URL 抑制。
- `FragmentTabletDualPaneSession` 挂载原生 SONG 和独立 LYRICS，调用原生 `PlayerMainFragment.r1()` 注册并初始化媒体监听。它不缓存歌曲封面 Drawable 或视频 URL。
- `PlayerSongViewFragment$PlayerListener.onMediaMetadataChanged(z3.w)` 分别处理标题/艺人和动态封面。标题更新不能证明视频交接完成。
- `PlayerSongViewFragment.U1(q8.Uf,float,pi.a)` 先让 `motion_preview_frame` 可见，再异步加载 DETAIL_TALL 的预览图。
- `player.j1.h(...)` 为选中的 TextureView 安装原生 `Y7.d` SurfaceTextureListener 和 MotionMgrRegistry。
- `fragment.C0.invoke()` 是视频交接完成回调：隐藏 `motion_preview_frame`，执行预览完成动作，再调用 `P0.a()` 完成 ViewSwitcher/封面交叉渐变。
- `player.j1.g(TextureView,newUrl,oldUrl)` 对相同 URL 和已安装相同 URL 的监听器会跳过重新加载。需要现场确认使用的 TextureView、显示中的 ViewSwitcher child、预览层及其监听器状态。

## 已排除的静态猜测

`PlayerSongViewFragment.onViewCreated` 从父 View 查找原生 `q8.a5` binding。虽然模块双栏增加了容器，但 `androidx.databinding.g.c(View)` 会逐层搜索祖先，不是固定父层数；没有证据表明模块普通 wrapper 会阻断这个搜索，不能据此强行改 binding。

`PlayerMainFragment$i.e()` 只恢复缩放/圆角并清除动画几何缓存，不负责隐藏动态预览图或重新加载视频。直接反复 reset artwork 缓存没有针对已确认的图片/视频交接路径，也可能影响已验收的原生动画。

## 待现场区分

1. 当前显示的旧画面来自 `motion_preview_frame`、歌曲 `artwork_view`，还是 TextureView 的最后一帧。
2. 换歌后 ViewSwitcher 当前 child 是否与原生新 URL 选中的 TextureView 一致。
3. 原生首帧回调、SurfaceTexture 创建/销毁及视频监听器是否完成；玻璃对 `motion_switcher` 的透明度是否影响原生初始化分支。
4. 单栏与双栏、玻璃开关的对照是否改变现象；对照前保留当前异常现场。

## 2026-10-05 平板现场

7065756b / OPD2413 的原始进程 10719 保存了异常截图、AM 专用 Activity dump 和日志。歌名、歌词和 MediaSession 已是《果实》/陈粒，完整封面却是《Lover》。具体层为歌曲页 `video_surface` TextureView：可见且 alpha=1；其 `artwork_image` 为 INVISIBLE、alpha=0。`motion_switcher` alpha=0，因此原先怀疑的 DETAIL_TALL 预览层不是现场中盖住封面的层。SONG 和独立 LYRICS 都处于 RESUMED、非 hidden。

安装包 SHA-256 确认为已验收底部间距试用包 `37720232f4a300c6d7b2f2eff56e5e270169d17f1db85976273cf752401d6c55`。Java heap 读取被系统以非 debuggable 拒绝，没有改变系统调试或 SELinux 策略。

## 当前修复候选

原生 `j1.b(TextureView)` 清除 Editorial 的 `Y7.d` 监听器后，歌曲页 `u1()` 可能返回 null。原生 `l.M1(float,int,int)` 在零视频尺寸、切回普通图片时先查询 `u1()`，取不到就提前返回；`E1(false,Size)` 在真正改图片/视频透明度之前也查询一次。这能解释原生旧视频帧盖住已经更新的图片。

新增宿主层 `FragmentTabletVideoHandoff`：只在当前 1606 双栏 SONG 执行原生零视频尺寸更新的调用期间，为原生 getter 提供仍挂载且已测量的真实 TextureView；M1 和 E1 均能完成查询，调用结束即释放。图片显隐、透明度、尺寸动画仍由原生 E1 执行，模块不写这些值。其他页面、正视频尺寸更新和调用期间以外的 getter 保持原生结果。

元数据更新后，仅在旧视频模式仍有效、旧 surface 可用且无视频监听器、图片透明且当前媒体视频尺寸为零时，补发原生 M1 的静态模式刷新。延迟动作校验 Fragment view 身份及双栏启用条件。反射成员和资源名称来自精确 profile，没有禁用动态封面。

第一轮候选曾因 `getMediaBrowser()` 的接口默认实现不在父类声明链内，造成双栏契约失败和原生回退。已立即恢复验收包，然后改为公共接口继承查找，新增 Java default-interface 和子类回归测试。第二轮候选在平板确认双栏及独立歌词恢复，普通图片呈现 image alpha=1、surface alpha=0。严格的“启动恢复 Lover 后切下一首”由用户接手测试；不能将当前结果写成这条场景已全部通过。

## 启动 mini 消失

用户补充它发生在刚启动。第二轮现场截图中 mini 确实消失；原生 `mini_player_blur` 为 GONE，`player_coordinator` alpha=0，原生 mini 内容仍存在，模块玻璃跟随隐藏。短暂更新播放/暂停状态后原生 blur 可见、coordinator alpha=1，mini 随之出现。

用户随后报告启动稳定消失。重新安装此前验收通过的底部间距包后同样得到 blur GONE / coordinator alpha=0，说明不是新视频修复单独引入。

原生 `MusicContentFragment.s1(boolean)` 同时更新 mini 背景 visibility、coordinator alpha/translationY 以及 liveMiniPlayerHeight。原生启动观察者和 `h` 协程的显示条件均为 `A.c.d()`（账号已登录）且 `browser.c()`（当前 timeline item）存在。

最新候选在 tablet Fragment view 会话中补上启动阶段的有限重查：原生已经显示 mini 就停止；账号和当前歌曲均就绪则调用一次原生 s1(true)，不直接写玻璃/播放器透明度；否则每 250ms 重试，最多 10 秒，view 销毁时移除 callback，原生无歌曲/未登录不显示。手机路径不变。

最新候选安装后，两次重开原生 blur 都已可见，coordinator alpha=1，mini 与双栏正常。两次日志未出现“补查恢复”信息，意味着原生自身先完成了显示；因此不能把恢复严格归因于新增补查，也不能声称启动竞态已完全验证。用户继续复测，保留这一因果限制。

## 用户验收

2026-10-05 用户反馈“可以了，两个问题都修好了”，确认当前组合候选的 mini 启动显示和动态封面换歌均正常。前述现场、因果边界和失败候选保留作为诊断记录，用户功能验收已通过。

用户随后补充“关了 AM++ 好像没问题”。这支持问题由模块接入条件或初始化时序触发，不能仅因现场中的原生控件被隐藏就归因于 Apple Music 原版。当前修复补齐模块接入下的原生状态交接；具体竞态写入调用栈尚未捕获，不能把它作为原版独立缺陷的证明。

最终 978 项单元测试、Debug Lint、Release Lint、签名 Release 构建和架构/profile/玻璃参考检查通过。用户验收时的签名试用包保存为 `AMpp-1.6.3-113-tablet-startup-accepted.apk`，SHA-256 `d3f99c9e3b797d96cdaceef44c46dc501839cf924bf420f89c061f7d27f50b3c`；沿用原发布证书、1.6.3/113 和重构后的模块边界。
