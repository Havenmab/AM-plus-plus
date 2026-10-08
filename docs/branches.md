# 分支拓扑（2026-10-08 清理后）

仓库只保留三条分支：

| 分支 | 作用 |
| --- | --- |
| `main` | **与上游同步**（`Zennmn/AM-plus-plus`）。不要在此提交功能。 |
| `integ/all-features` | **当前集成所有功能的分支**：地区替换 / 歌曲名与原地区修正 / 平板顶栏 / 在线歌词（含酷狗 v1、发音补全、原生模型交付、自定义歌词补全）。真机包从这里出。 |
| `feat/region-port` | **旧版本线**：为 Apple Music 6.5.3 等旧版本提供地区替换与 iPad 风格顶栏（独立于集成分支，勿与集成分支合并）。 |

## 构建

- CI 的 debug 产物（`AM-plus-plus-debug`）目前由 **pull_request 事件**产出；仓库保留一个 `integ/all-features → main` 的 PR 作为构建载体（每次向集成分支推送都会产出新包）。
- 若要改为 **push 即出包**（不再需要那个 PR），应用 `.prep/ci-push-debug-build.patch`：
  它新增 `.github/workflows/debug-build.yml` 并让 `build.yml` 的 release 步骤在缺少签名密钥时跳过。
  **注意**：推送 workflow 文件需要带 `workflow` scope 的 token。

## 历史

此前 23 条功能/修复分支的内容**全部已并入 `integ/all-features`** 后删除；清理前的分支 SHA 清单保存在
`.prep/branch-inventory-20261008-2111.txt`（必要时可据此恢复任意分支）。
