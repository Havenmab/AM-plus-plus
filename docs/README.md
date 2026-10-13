# 文档目录

本目录保留通用开发、维护和人工验收文档。Apple Music 适配从 [宿主适配手册](host-adaptation-guide.md) 开始，按实际页面族使用清单并核对功能矩阵。精确版本与成员映射以 [host-profiles](../host-applemusic/src/main/resources/host-profiles/) 为准；单次排查、构建和验收记录保存在任务记录中。

| 文档 | 内容 |
|---|---|
| [宿主适配手册](host-adaptation-guide.md) | 当前架构、新版本适配流程、精确 profile、两类宿主契约、布局/视频、安装与回退 |
| [宿主适配清单](host-adaptation-checklist.md) | 每个构建可重复使用的身份、成员/辅助依赖、资源/binding、生命周期和交付检查模板 |
| [功能保全矩阵](feature-preservation.md) | 功能行为、页面族差异、能力边界与可重复使用的人工验收模板 |
| [歌词模糊维护指南](lyrics-blur-guide.md) | 显示规则、状态与动画、独立契约、视图会话、效果所有权与手测场景 |
| [TTML 格式说明](apple-music-ttml-format.md) | 手工歌词编写与转换时使用的格式约定 |
| [插件开发手册](plugin-development.md) | SDK、ZIP 打包、独立开发、生命周期与冲突规则 |
| [插件作者教程](plugin-author-guide.md) | 从零建工程、Hook/设置代码、打包导入、调试与版本适配 |
| [本地化说明](localization.md) | 设置文案、语言选择、共享文本目录和翻译维护规则 |
