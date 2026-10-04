# 插件系统 v1 验证记录

基线 main 865767a；实施分支 codex/plugin-system-v1。任务规划保存在本工作树独立的 planning-with-files 目录。

## 已执行

- SDK 导出、runtime/app 编译、独立 Gradle 示例 D8 ZIP 构建通过。
- 全模块单元测试通过：1019 项，零失败、零跳过。其中 plugin-runtime 25 项，新增插件 SAF 路由测试也通过。
- app lintDebug/lintVitalRelease、glass/host-applemusic/plugin-api/plugin-runtime lintDebug 通过。
- app assembleDebug/assembleRelease 通过；初次隔离构建为未签名 Release。后续通过进程环境使用项目现有本地签名配置构建 Release，APK v2 签名验证通过，签名文件未加入仓库。

- 仓库外样例构建通过：临时目录内仅携带示例源码与 SDK JAR，用独立 Gradle 工程构建 DEX ZIP；该输出在 PluginStore 测试中成功导入。
- 最终架构检查通过，SDK 无内部模块/框架依赖，加载器无宿主私有符号；原 5 个精确 profile、316 个目标与冻结资料检查通过。
- 玻璃参考检查通过：32 个上游文件与 2 项原补丁的哈希保持不变。
- 测试覆盖导入与取消、坏包拒绝、多 DEX、重复 SDK、并发更新预览失效、配置保留、延后删除、回调修改事务、错误与不支持状态、作用域逆序清理、独占/普通冲突、重载与 ClassLoader 区分、SAF 结果隔离。

## PR review 修复

- 插件加载与管理任务分用两个单线程队列。启动时先在管理队列清理文件并读取安装快照，再交给加载队列执行插件代码；卡住的加载不会阻止列表读取、导入、停用和删除，启停仍重启生效。
- 启动清理补充扫描版本目录，删除没有安装索引记录的插件目录，回收首次安装移动文件后、写索引前进程退出留下的文件。
- 新增 3 项回归：阻塞加载时管理操作完成且启动清理先于导入、无索引中断安装的目录清理、清理孤立目录时保留已安装版本及配置/缓存。
- 修复后全模块测试 1019 项（零失败、零跳过）、原 CI Lint、Debug/Release 构建和架构/profile/玻璃参考检查通过。

## 产物

- `app/build/outputs/apk/debug/app-debug.apk`：调试签名，可用于设备验收。
- `app/build/outputs/apk/release/app-release.apk`：使用项目现有 Release 密钥签名的用户测试产物。
- `build/plugin-example/ampp-plugin-api-v1.jar`：独立插件编译 SDK。
- `build/plugin-example/basic-plugin.zip`：仓库外构建并通过导入测试的示例。

CI 新增独立样例构建、SDK/ZIP 上传、fixture 导入测试和新模块 Lint；PR #79 初版的远端 CI 已通过，以下 review 修复的本地完整验证也已通过。

## 设备验收

当前没有连接的 ADB 设备，未执行 LSPosed 或嵌入版真实 Hook、Android DEX 加载、设置 View 渲染、Android 14+ 运行验收。这些项待验收，静态/JVM 通过不代表真机通过。

验收输入：AM++ Debug APK、导出的 SDK、basic-plugin.zip。覆盖导入、启停重启、更新、删除、素材、设置生命周期、冲突和单插件失败。
