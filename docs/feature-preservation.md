# 旧版功能保全与验收矩阵

基线：97c4b69；schema=15；Apple Music 1583/1586/1599。自动测试不等同真机验收。

7.0.0-beta/1606 的实现、自动验证与逐项待验收状态见 [1606 适配记录](applemusic-700-adaptation.md)。下表保留旧版验收基线；新平板玻璃按 7.0 顶部导航和独立底部 mini 实现。

按最新用户要求，1606 v9 试用版交回原生封面过渡，并保留双栏原生动态封面，详见 [原生封面试用记录](applemusic-700-native-tablet-artwork-trial.md)。这一变化仅针对 7.0；下表 6.5.x 的 Editorial 保全语义保持不变。

| 功能 | 保全语义 | 自动验证入口 | 真机状态 |
|---|---|---|---|
| 双栏 | 官方平板横屏、原生 SONG/QUEUE、独立右歌词、事务/旋转/锚点/渐变/封面 | DualPane、Tablet、StateAccessors 用例 | 待三版本对照 |
| Editorial | 双栏控制、仅平板横屏 URL、保留预览及 Music Video | TargetAdaptationBehavior | 待对照 |
| 模糊 | 双向、焦点、滚动暂停恢复、换歌、API31、-10..10px | Blur/Highlight 用例 | 待对照 |
| CJK | 单词作用域、汉字/日文/韩文、动画清理、不抢位移 | CjkKaraoke 用例 | 待对照 |
| 字体 | TTF/OTF、后台与重试、行词一致、恢复原字体 | Font、LyricsTypeface | 待导入/恢复 |
| 自定义歌词 | 获取ID、多ID、增删改查/禁用、TTML/指针校验、异步重应用 | CustomLyrics、Ttml 用例 | 待播放对照 |
| 在线/自动 | 三手动来源；自动 AMLL→Lunabeat→AM-Lyrics；manual/disabled优先；generation/退避/哈希/ETag | Online、AutoLyrics、Lunabeat 用例 | 待网络/离线/切歌 |
| 更新/ZIP | 批量、取消、进度、覆盖/保留冲突、事务恢复 | Update、Backup、Restore | 待恢复对照 |
| 元数据/地区替换 | 七档地区（原地区/cn/us/hk/tw/kr/jp）、原名还原与检索库独立开关、所有展示表面、广播/歌词/播放回退账号、ISRC/原名证据、离线/持久缓存/优先级；执行器参数与 amp-api 层仅 1599/1606 声明 | Metadata、Catalog、Cache、TitleCorrectionMode 用例 | 待真机对比/页面 |
| 玻璃 | API33，1586/1599；手机/旧平板横屏、导航/mini/手势/过渡/采样/恢复 | Glass、TabletGlass、LayerAlpha | 待帧/视觉/手势 |
| 玻璃参数/补偿 | gap16/0..48，blur4/0..24；独立恢复与显隐；玻璃接管补偿 | ModuleSettingsSchema、SettingsDraft | 待显隐/占位 |
| 蜂窝 | 默认false，1586/1599；构建作用域仅一次；availability独立 | CellularDataEntry | 待原生入口 |
| DPI | 0或160..640、仅宿主、冷启动/配置变化/失败失活 | AppleMusicDpiOverride | 待重启/旋转 |
| 设置 | 无launcher、原生入口、草稿/SAF归属/页面重建/清理 | EmbeddedSettings、EmbeddedOnlyArtifact | 待全流程 |
| 存储/迁移 | 原键/目录/ID/marker/schema/ZIP/SQLite；ordinary不覆盖资产；原子发布；只读重试 | ConfigurationMigration/Session/Storage/Index/Transaction | 待升级/回滚 |

1583 的玻璃/蜂窝保持不支持。6.5.0/1580 仅参考，不在生产注册范围。普通目录流量只在用户选中地区档位时改写；未选中时仍跟随账号地区。

## 既有降级（独立修复）

| 子面 | 基线状态 | 重构验收规则 |
|---|---|---|
| 6.5.3 操作表元数据 | 已知目标歧义/降级 | 单独取证和修复，不能扩大到任意 binding |
| 6.5.3 Listen Now 封面连续性 | 已知降级 | 保持其余主页/元数据；单独取证、测试和真机验证 |

真机使用相同设备、宿主、设置、歌曲与操作脚本对照；每场景留日志/截图或录屏。性能至少五次，持续>10%退化须修复。缺少设备/原始旧包时保持待验收，不能写 PASS。
