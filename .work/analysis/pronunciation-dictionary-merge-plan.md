# 方案 D 探究：用词典判断日语音译词边界

> 只读设计产出：**未改代码、未 commit、未 push**。
> 前置：[pronunciation-word-merge-rules.md](pronunciation-word-merge-rules.md)（规则分组，`group()`/`joins()`/`isWordLevelLine()`）、[pronunciation-llm-boundary-plan.md](pronunciation-llm-boundary-plan.md)（LLM 判边界）。
> 基线：`feat/lyrics-pronunciation-merge-only`（纯规则）。本文写在 `worktrees/centring`（当前检出 `feat/lyrics-pronunciation-centring`），是纯新增文件，未动分支、未改源码。本方案若落地，接入点是 `LyricsPronunciationWordAlignment.group(tokens, …, plan)`——词典产出与 LLM 同一形状的 `PronunciationBoundary.BoundaryLine`，视图层零改动。
> 本文所有数字均标注来源：**实测**＝本次在本机对 IPADIC 2.7.0 原始包跑的脚本（附录 A 可复现）；**引用**＝官方页面/Maven/PyPI 元数据；**估算**＝按格式常量推算，给出公式。

---

## 0. 决策摘要

| 问题 | 结论 |
|---|---|
| 词典方案整体可行吗 | **部分可行**。只能做「规则之上的局部证据源」，不能替代 `group()` 的规则；做完整形态素解析（MeCab 等价）体积/收益都不划算 |
| 主选词典 | **IPADIC 2.7.0**（NAIST/ICOT 许可，可随 APK 分发，需附 COPYING） |
| 备选 | **UniDic**（GPL2/LGPL2.1/New BSD 三选一，取 New BSD）或 **SudachiDict-small**（Apache-2.0，最省心）；体积都明显更大 |
| 推荐体积档 | **+2 MB 档**：自研纯 Kotlin + 假名**表记**索引（实测 73,102 词条 / 112,831 trie 节点 → 估算 **0.6–0.9 MB**），无 native 依赖 |
| 解析器 | **自研容器格 + 词典证据判定**，不用 MeCab/Kuromoji/Sudachi 运行时 |
| 关键算法结论 | **同一索引下，最长匹配与 unigram Viterbi 结果 7/7 完全相同**——短串上算法不是变量，词表覆盖与同形竞争才是。所以不必上 bigram/MeCab，先用「保守证据 + 只拆不合」的仲裁层 |
| 它能解决什么 | 拆分方向很可靠（实测）：`赤い`\|`トマト`\|`を`、`ダメ`\|`ダメ`、`仕掛け`\|`てる`、`ステップ`\|`アップ`、`こんな`\|`んじゃ` |
| 它解决不了什么 | 合并方向不可靠（实测）：`マジカルトリックで` → `マジ`\|`カルト`\|`リック`\|`で`（`カルト` 3,657 < `トリック` 3,721 且 `マジカル` 无词条）；`メタモッて` → `メタ`\|`モッて`（读音 `もって` 吸走 `モッ+て`，**规则本来是对的**） |
| 与 LLM 关系 | **共存**：规则 → 词典（离线、零延迟）→ LLM（可选，只处理词典仍不确定的行）。词典可把 LLM 的调用面缩小一个量级 |
| 需要拍板 | 体积预算（+2/+8/+30 MB）、是否内置离线、许可取舍（IPADIC 附 COPYING / UniDic BSD / Sudachi Apache）、是否保留 LLM 通道 |

---

## 1. 词典选型

### 1.1 候选对比

| 词典 | 许可 | 可随 APK | 体积（实测/引用） | 词条数 | 含读音 | 含成本/词类 | 维护 |
|---|---|---|---|---|---|---|---|
| **IPADIC 2.7.0** | NAIST/ICOT Free Software（BSD 式，允许再分发+修改，须附 COPYING 与免责声明） | ✅ | 源 tar.gz **12,208,105 B（12.2 MB）**；Ubuntu `mecab-ipadic` deb **6,560.2 kB** / 安装后 **53,075.0 kB**；PyPI 编译版 wheel **13,413,189 B（12.79 MiB）** | **392,126**（实测，26 个 CSV 全量） | ✅ 片假名读音列 | ✅ cost + left/right id + 词类 4 列 | ❌ 2007-08-01 冻结 |
| NAIST-jdic | 同 IPADIC（NAIST 系） | ✅ | Ubuntu `naist-jdic` deb **3,282.6 kB** / 安装后 **27,723.0 kB** | 约 37 万（未核实） | ✅ | ✅ | 冻结（2011 前后） |
| UniDic | **GPL v2.0 / LGPL v2.1 / New BSD**（官网原文） | ✅（取 New BSD） | `unidic-lite` 精简包 **47,356,746 B（45.16 MiB）**；完整版显著更大（未实测） | 约 70 万级（未核实） | ✅ | ✅ 词类/活用更细 | ✅ 国立国語研持续维护 |
| SudachiDict small / core / full | **Apache-2.0**（README 原文） | ✅ | PyPI wheel：small **42,492,049 B（40.52 MiB）**、core **76,946,458 B（73.38 MiB）**、full 仅为下载器 | README 未给数字（未核实） | ✅ | ✅（含连接成本，二进制格式） | ✅（2026-10 版本） |
| mecab-ipadic-NEologd | Apache-2.0（但含 はてなキーワード / 郵便番号数据） | ❌ | README：「当前最大二进制 **1.2 GB**」，构建需 2 GB RAM；仓库 **463,690 KB** | 「约 **322 万** 组表记/读音（含重复）」 | ✅ | ✅ | ✅（定期更新） |
| JUMANdic | 未核实（通行说法为 GPL；需以发行包 LICENSE 为准） | ⚠️ | 未实测 | 未核实 | ✅ | ✅ | 冻结 |
| JMdict | **CC BY-SA 4.0**（EDRDG licence 页原文） | ⚠️ 需 SA + 署名 | 未实测（XML，通常 10 MB 级压缩） | 未实测 | ✅ 假名形 | ❌ 无连接成本/词类 | ✅ |

**关键许可细节（原文要点）**

- IPADIC `COPYING`（实测，包内文件）：「Use, reproduction, and distribution of this software is permitted. Any copy … must include both the above copyright notice and the following paragraphs.」＋ NAIST 免责；并注明「A large portion of the dictionary entries originate from ICOT Free Software」，要求 ICOT 的「NO WARRANTY」段落随分发保留。→ **可以随 APK 分发**，只需在 NOTICE/关于页附上该文本。
- UniDic 下载页「ライセンス GPL v2.0 / LGPL v2.1 / 修正BSD」——选 **New BSD** 分支即可闭源分发。
- SudachiDict README：「licensed under the Apache License, Version2.0」。
- NEologd README：许可 Apache-2.0，但数据源含 Hatena 关键词（版权属 Hatena）与日本邮便番号数据——分发时要连带声明；加上 1.2 GB 二进制，**排除**。
- JMdict：CC BY-SA 4.0 是**传染性**条款（改动的词典数据须同许可发布）；作为 APK 内置数据可行但要单独声明，且它是翻译词典、无连接成本，**不适合做本方案的边界词典**。

### 1.2 推荐

- **主选 IPADIC**：唯一同时满足「许可可分发 + 体积可压到 1 MB 级 + 有成本与词类 + 有完整片假名读音」的候选；冻结反而是优点——词表稳定，构建可复现。
- **备选 UniDic（New BSD）**：词类更现代（助动词/补助动词分得细，`てる`/`しかけ` 类更好），但导出体积约为 IPADIC 的 3–5 倍，落 +8 MB 档。
- **SudachiDict-small**：许可最省心（Apache），但 40.5 MiB 的分布格式是为 Sudachi 运行时准备的，**不能直接当紧凑索引**；要用还得自己导出，性价比不如 IPADIC。

---

## 2. 运行时可行性

### 2.1 三档体积预算（模块 APK 现约 13 MB）

| 档位 | 能做到什么 | 组成 | 结论 |
|---|---|---|---|
| **+2 MB** | 自研纯 Kotlin + IPADIC 假名表记索引（A 档） | 索引 **0.6–0.9 MB**（估算，见 §4.3）+ 解析器代码 < 80 KB + COPYING 文本 | ✅ **推荐**，无 native、无额外依赖 |
| **+8 MB** | A 档 + 读音扩展（B 档）或换 UniDic 子集 | 索引 **2.2–3.0 MB**（估算）+ 可选 bigram 矩阵 **3.46 MB**（1316×1316 int16） | ⚠️ 可选；bigram 只在需要「全局最优切分」时才值得 |
| **+30 MB** | 完整 MeCab 等价：NDK 编 libmecab + 完整编译词典 | 编译词典 **12.8–13.3 MB**（PyPI/kuromoji 实测锚点），运行展开 **53 MB**；matrix.bin 3.46 MB；4 个 ABI 的 .so | ❌ 不建议：收益（相对 +2 MB 档）主要是同形词仲裁，代价是 native 依赖 + 30 MB+ 展开内存 |

### 2.2 解析器选项

| 方案 | 语言/依赖 | 体积 | 首次初始化 | 备注 |
|---|---|---|---|---|
| MeCab + NDK | C++ native | 词典 12.8 MB + 4×.so（约 1–2 MB） | 100–300 ms（估算） | 需 NDK 构建、4 ABI、libmecab（LGPL/GPL/BSD 三许可）；内存展开 50 MB+ |
| Kuromoji | 纯 Java（`kuromoji-ipadic-0.9.0.jar` **13,343,016 B**，实测 Maven 目录） | 13.3 MB | 2–4 s（Android 上构建 Tokenizer 的已知开销，估算） | Apache-2.0；词条 392k；内存 20–40 MB（估算） |
| Sudachi | 纯 Java | sudachi jar ~2 MB + core 词典 **73.4 MiB** | 1–3 s（估算） | Apache-2.0；对「只判边界」过重 |
| **自研 Kotlin + 紧凑索引** | 纯 Kotlin（无依赖） | **0.6–0.9 MB** | **10–30 ms**（估算：顺序读 0.9 MB + 建 73k 词条表） | ✅ 推荐；只实现「查词 + 证据判定」，不实现完整形态素解析 |

### 2.3 内存、首次加载、每行耗时

| 指标 | 自研 A 档 | 说明 |
|---|---|---|
| 常驻内存 | **≈ 1.5–3 MB**（索引 0.6–0.9 MB + DAWG/双数组 + 词条表 + 临时数组） | 与 13 MB APK 的播放器进程相比可忽略 |
| 首次加载 | **10–30 ms**（估算） | 若 assets 不压缩（`noCompress`）可直接读；B 档建议先复制到 filesDir 再 mmap |
| 每行耗时 | **< 100 µs**（估算：容器数 n≈10–20，候选边 ≤ n×8≈160，每次 trie 查词 2–4 步 → ≈ 500 步；JVM 单步 ~10 ns） | 与 `alignRow` 现有绘制开销同量级，**不需要后台线程** |
| 总账 | 一首 50 行 ≈ **< 5 ms** 一次性 | 可在「歌词文档发布」后同步跑完，播放路径只读结果 |

### 2.4 内置 vs 网络下载

- **内置**（推荐）：离线可用、无校验/回退逻辑、无隐私问题；A 档 0.9 MB 完全可接受。
- **下载**（仅当将来要换 UniDic/Sudachi 大词典）：必须 SHA-256 校验 + 版本号 + 失败回退内置 A 档；且 Android 上 assets→filesDir 的首次复制会被用户感知，不如内置。
- 构建期生成（CI）：把 IPADIC CSV 转成 `kana_index.bin` 并作为 assets 提交/打包；转换脚本放仓库 `scripts/`，**不在设备上做转换**。

---

## 3. 算法（关键）

### 3.1 输入到底是什么

`LyricsPronunciationWordAlignment.ContainerToken`：容器下标、`kana`（**表记**，可能是汉字/片假名/平假名/拉丁）、`reading`（拉丁罗马字，不是假名读音）、`begin/end`、`readingIsWholeWord`。`group()` 的规则已经在用：标点容器、trailingBlank、跨行、片假名助词、整词读音容器 = 硬边界。

**要点**：输入不是「纯假名」——汉字容器会出现在序列里（`仕|掛|け|て|る`）。因此词典只对**纯假名连续段**（片假名连写 / 平假名连写）有发言权；汉字与假名的交界规则已能证明，不需要词典。

### 3.2 表记索引，还是读音索引？

**必须用「表记（surface）索引」，不能用「读音（reading）索引」单独支撑。**

- 歌词写的是「は / を / で / と」，读音是「ワ / ヲ / デ / ト」；用读音索引会把助词与音读混在一起（实测：`ト` 作为读音存在，cost 3,177；`と` 作为表记也存在）。
- 但**读音列仍有价值**：`仕掛け` 只以汉字表记存在，其读音「シカケ」是片假名，歌词里却可能写成平假名「しかけ」。要用读音，就必须**把片假名折叠成平假名**再建索引（实测：不折叠时 `しかけ` 查不到 `シカケ`）。
- 结论：索引键 = 表记（全假名词条）∪ 读音（折叠成平假名）。这两类合并后就是本文的 B 档；只用表记就是 A 档。

### 3.3 三种算法的实测（最长匹配 / unigram Viterbi / 容器格）

实验设置：IPADIC 2.7.0 全量；两边都做片假名→平假名折叠；词成本取条目 cost 最小值；每词加 800 惩罚；OOV = 9,000 + 1,800/字。**最长匹配**与 **Viterbi** 都跑在容器格（节点 = 容器边界）上；同时验证过字符格，短串（≤ 16 假名）上两者结果一致。

| 例子 | 规则现状（`group()`） | A 档·最长匹配 | A 档·Viterbi | B 档·Viterbi | 期望 |
|---|---|---|---|---|---|
| `マジカルトリックで` | `マジカル`\|`ト`\|`リック`\|`で`（把 `トリック` 切开） | `マジ`\|`カルト`\|`リック`\|`で` ❌ | 同左 ❌ | 同左 ❌ | `マジカル`\|`トリック`\|`で` |
| `赤いトマトを` | `赤い`\|`ト`\|`マ`\|`ト`\|`を` | `あかい`\|`トマト`\|`を` ✅ | 同左 ✅ | 同左 ✅ | `赤い`\|`トマト`\|`を` |
| `ダメダメ` | 合并成一词（同脚本 4 假名 ≤ 6，过度合并） | `ダメ`\|`ダメ` ✅ | 同左 ✅ | 同左 ✅ | `ダメ`\|`ダメ` |
| `こんなんじゃ` | 合并成一词（过度合并） | `こんな`\|`んじゃ` | 同左 | `こんなん`\|`じゃ` | `こんな`\|`ん`\|`じゃ` |
| `仕掛けてる` | 合并成一词（过度合并） | `しかけ`\|`てる` ✅ | 同左 ✅ | 同左 ✅ | `仕掛け`\|`てる` |
| `メタモッて` | `メタモッ`\|`て`（促音桥接，**规则正确**） | `メタ`\|`モッて` ❌ | 同左 ❌ | 同左 ❌ | `メタモッ`\|`て` |
| `ステップアップ` | 依规则合并/断开 | `すてっぷ`\|`あっぷ` ✅ | 同左 ✅ | 同左 ✅ | `ステップ`\|`アップ` |

四条可复用的结论：

1. **同一索引下，最长匹配与 unigram Viterbi 结果完全相同（7/7）**：短串上「搜索算法」不是变量，**词表覆盖与同形竞争才是**。→ 不必上 bigram/MeCab，先解决索引与仲裁。
2. **词典在 4 条上明显优于规则**：トマト（把硬断的 `ト` 归位）、ダメ|ダメ（规则过度合并）、しかけ|てる（规则过度合并）、すてっぷ|あっぷ。
3. **词典在 2 条上明显劣于规则**：`メタモッて`（`もって` 这个读音把 `モッ+て` 吸走）与 `マジカルトリック`（`カルト` 3,657 比 `トリック` 3,721 便宜，且 `マジカル` 无词条）。→ 词典**不能无条件覆盖规则**：规则已证明的连接（促音桥接、整词读音容器）必须优先。
4. A/B 档只在 `こんなんじゃ` 这类上不同：B 档（含读音）更长词、A 档更碎；两条都不算错，需真机对比观感。

### 3.4 推荐算法：保守证据 + 只拆不合并（规则优先）

由 §3.3 的实测结论：词典**能可靠地拆**（ダメ|ダメ、しかけ|てる），但**不可靠地合**（カルト、モッて）。所以推荐方向与直觉相反——**默认只让词典"拆"，几乎不让它"合"**：

```text
输入：一条自由段（两个硬边界之间的容器序列）+ 规则 baseline
输出：该自由段的分组（默认等于 baseline）

A. 拆分（安全，先做）：
   若 baseline 一个块覆盖 ≥2 容器，且词典能把它切成两段 w1|w2，
   满足 w1、w2 都命中词条、且 w1 是块首、w2 是块尾（不留孤立容器）：
       → 采用拆分（ダメ|ダメ、しかけ|てる、こんな|んじゃ）

B. 合并（危险，默认关闭，需真机验证后才开）：
   仅当规则因「片假名助词硬断」切开的边界，且
     (a) 候选词覆盖 ≥2 容器；
     (b) 成本 ≤ 5,000（IPADIC 中位 9,126 的一半，取偏常见词）；
     (c) 与另一条同样覆盖该容器的词不冲突，或成本差 ≥ 800；
       → 采用合并（トリック 类；カルト 类因 (c) 被拒）

C. 规则已证明的连接（促音桥接 / 整词读音容器 / 同脚本连写）不因词典而拆开，
   除非拆分满足 A 的全部条件（メタモッ|て 因此保持规则结果）
```

**为什么 A 安全**：拆分只把「规则本来靠 `maxKana=6` 和同脚本连写猜出来的块」切开，切错了最坏是多一个词形间隔，不会把两个词合成一个错词。

**为什么 B 默认关闭**：实测 `マジカルトリックで` 无论用最长匹配还是 Viterbi 都会得到 `マジ|カルト|リック|で`；只有叠加「冲突检测 + 成本阈值」才可能保留 `ト`，但 `マジカル` 无词条，基线仍是 `マジカル|ト|リック`——**B 在真实语料上是否净收益为正，必须先 DRY_RUN 统计再决定**。

**这直接回答了"词典能否替代 LLM"**：不能。词典能覆盖 A 类（拆分类）+ 部分 B 类，`マジカル` 这类词表外词、以及 `カルト` 这类同形竞争，仍然只能靠 LLM 或接受规则结果。

### 3.5 与现有规则/LLM 的优先级

```text
硬边界（标点 / trailingBlank / 跨行 / 片假名助词 / 整词读音容器）   ← 规则，最高
   ↓ 自由段
词典证据（strong 才改判，冲突回落）                            ← 本方案
   ↓ 仍不确定
LLM（可选，整首一次，只处理自由段）                             ← 现有方案 C
```

- 词典是**离线确定性**层，LLM 是**联网兜底层**；两者写同一份 `BoundaryLine`，缓存/校验/合并代码完全复用。
- 开关设计：`dictionaryBoundaryEnabled`（默认关）独立于 `llmPronunciationBoundaryEnabled`；两者都关 = 与 merge-only 基线逐字节一致。

---

## 4. 数据管线

### 4.1 从 IPADIC 导出

IPADIC 源包 = 26 个 EUC-JP CSV，每行 13 列（表记, left_id, right_id, cost, 词类×4, 活用×2, 读音, 发音）。实测 **392,126** 行、无畸形行。

```text
for each csv row:
    surface = col0; reading = col11; cost = int(col3)
    if surface is all-kana:            emit(key = fold(surface), cost)
    if reading is all-kana:            emit(key = fold(reading), cost)     # 可选（B 档）
fold: 片假名 U+30A1..U+30F6 → 平假名（-0x60），长音符 ー 原样
dedupe by key, keep min(cost)
```

### 4.2 实测规模（本次对 IPADIC 2.7.0 全量跑的）

| 指标 | A 档（表记） | B 档（表记 + 折叠读音） |
|---|---|---|
| 词条数（去重后） | **73,102** | **202,417** |
| 原始去重数（未折叠） | 75,612 | 201,932（读音）/ 75,612（表记） |
| 假名字符总数 | 357,954 | 1,120,260 |
| 平均词长 | 4.73 | 5.55 |
| trie 节点 / 边 | **112,831 / 112,830** | 429,398 / 429,397 |
| DAWG 最小化状态数 | **15,085** | 67,536 |
| 纯片假名表记 | 16,691 | — |
| cost 分布 | min −807 / 中位 9,126 / max 16,437 | — |
| ≤2 假名词条（过度切分风险源） | 2,026 | 2,699 |

### 4.3 二进制格式与体积（估算，给出公式）

格式（自研、纯 Kotlin 可读）：

```text
header: magic(4) version(1) entryCount(3) nodeCount(3) costMin(2) ...
DAWG:   states × 1B(flags) + edges × (2B 标签 + 3B 目标)   # 标签用平假名 UTF-16 低字节
entries: entryCount × (2B cost + 1B flags + 1B POS 粗类)
```

| 档 | DAWG 边数（上限 = trie 边） | 估算体积 |
|---|---|---|
| A | 112,830 | 112,830×5 B = **0.56 MB** + 73,102×4 B = 0.28 MB → **≈ 0.85 MB** |
| B | 429,397 | 429,397×5 B = **2.15 MB** + 202,417×4 B = 0.81 MB → **≈ 2.96 MB** |
| 双数组替代（darts-clone 风格 4B/unit，装载率 ~0.7） | A: 112,831/0.7≈161k unit×4 B = **0.64 MB** | 与 DAWG 同量级 |

→ **A 档 0.6–0.9 MB，B 档 2.2–3.0 MB**；都比 Kuromoji 的 13.3 MB 小一个量级。

### 4.4 常驻 vs mmap

- A 档：直接 `assets.open().readBytes()` 进堆（0.9 MB），启动 10–30 ms，最简单。
- B 档 / bigram 矩阵：建议首次复制到 `filesDir` 后 `MappedByteBuffer`；否则 3 MB 常驻堆也可接受。
- **不要**在播放路径做磁盘 I/O：加载时机同 LLM 方案（歌词文档发布后），播放期只读内存数组。

---

## 5. 与 LLM 方案对比

### 5.1 逐例对照（规则 / 词典实测 / LLM 预期）

| 例子 | 规则 | 词典（本文实测） | LLM（方案 C 设计目标） |
|---|---|---|---|
| `マジカルトリックで` | `マジカル`\|`ト`\|`リック`\|`で` ❌ | ❌ 实测 `マジ`\|`カルト`\|`リック`\|`で`（`カルト` 3,657 < `トリック` 3,721；`マジカル` 无词条） | `マジカル`\|`ト`\|`リック`\|`で`（few-shot 明确教过） |
| `赤いトマトを` | `赤い`\|`ト`\|`マ`\|`ト`\|`を` ❌ | ✅ `あかい`\|`とまと`\|`を`（トマト 3,511） | ✅（few-shot） |
| `ダメダメ` | 合并成一词 ❌（过度合并） | ✅ `だめ`\|`だめ` | ✅ 叠词不拆？设计示例里是「`ダメダメ` 一个词」——**与词典相反**，需统一口径 |
| `こんなんじゃ` | 合并成一词 ❌ | ⚠️ A 档 `こんな`\|`んじゃ`；B 档 `こんなん`\|`じゃ`（两解） | ✅ |
| `仕掛けてる` | 合并成一词 ❌ | ✅ `しかけ`\|`てる`（A/B 档一致） | ✅ |
| `メタモッて` | `メタモッ`\|`て` ✅ | ❌ `メタ`\|`モッて`（`もって` 读音吸走 `モッ+て`） | ✅ |

> 结论：词典**不是 LLM 的替代品**，而是「离线可用的第一层」；实测 6 例里 **4 例正确、2 例不如规则**（合并方向），且 `マジカル` 这类词表外词永远无解。LLM 在 6 例里预期 6 例正确（此前 14 行真机全对）。

### 5.2 成本对照

| 维度 | 词典（A 档） | LLM（方案 C） |
|---|---|---|
| APK 体积 | **+0.9 MB** | +0（复用现有 HTTP/缓存代码，增量 < 50 KB） |
| 运行内存 | **1.5–3 MB** | 可忽略（请求期临时对象） |
| 首字延迟 | **0（同步，10–30 ms 加载）** | 首次请求 3–8 s（设计文档目标），之后命中缓存 |
| 每次播放耗电 | **≈ 0** | 每首 1 次网络请求（≈ 3.3k in + 0.9k out tokens） |
| 离线 | ✅ | ❌（无缓存的新歌不可用） |
| 隐私 | ✅ 数据不出设备 | ⚠️ 歌词整首上传 |
| 维护 | 词表冻结（IPADIC 2007），新词/外来语永不覆盖 | 模型升级即改进 |
| 失败模式 | 词表外词 → 规则兜底（不会更差） | 网络失败/越权 → 整首拒绝，回落规则 |

### 5.3 共存

**建议：词典优先、LLM 兜底**（词典先跑，只把「词典仍不确定」的行留给 LLM）。

- 收益：LLM 请求面从「整首所有自由段」缩到「词典弃权的少数行」，token 量可降 ~50–70%（估算，按实测 6 例中 4 例词典可定）；离线时仍有词典收益。
- 反向（LLM 优先）也可行：LLM 质量最高，但离线/省电场景就完全没增益。
- 两者共用 `BoundaryLine` + 校验 + 缓存，工程上无额外分叉。

---

## 6. 实施路径与风险

### 6.1 最小可行版（1 周内可验证）

1. **D1–D2 数据**：`scripts/export_ipadic_kana_index.py`（附录 A 的脚本精简版）→ `kana_index.bin`（A 档，0.9 MB）+ `NOTICE-IPADIC.txt`；CI 校验生成物哈希（不再从网络拉）。
2. **D3–D4 纯 JVM 核心**：`core/.../lyrics/online/PronunciationDictionary.kt`——只读二进制、`lookup(kana): Int?`、`evidence(containers): DictionaryEvidence?`（纯函数，jvmTest 覆盖 §3.3 的 7 个例子）。
3. **D5 接入**：`group(tokens, plan = dictionaryPlan)`；不新增渲染代码（复用 `PronunciationBoundary.BoundaryLine` + `mergeLine` 校验）。
4. **D6–D7 真机**：5 首日文歌，日志记录「词典改判了哪些边界、冲突回落几次、与规则的一致性」，先 `DRY_RUN`（只记录不改渲染），确认无越权/无闪烁后再启用。

### 6.2 三个最可能失败的点与回退

| # | 失败点 | 表现 | 回退 |
|---|---|---|---|
| 1 | unigram 成本在「合并」方向系统性偏向短的常见词 | 把 `カルト`、`もって` 这类常见词并进相邻容器（实测 2/7 例） | 默认「只拆不合」；合并单独开关 + 冲突阈值；必要时上 bigram（+3.46 MB）或直接交给 LLM |
| 2 | 同形词竞争/词表外词（カルト vs トリック、マジカル、メタモ） | 改判错误或该改不改 | 冲突阈值 + 无证据回落规则；`マジカル` 类交给 LLM；开关默认关 |
| 3 | 词典词边界落在容器内部 | 词典说 `とりっく`，Apple 容器是 `ト|リッ|ク`（对齐）或 `ト|リック`（不对齐）→ 无法按容器渲染 | 只采纳「与容器边界对齐」的词；日志统计对齐率，低于 ~90% 就放弃该词典方案 |

### 6.3 需要用户拍板

1. **体积预算**：+2 MB（A 档）／+8 MB（B 档 + bigram）／+30 MB（完整 MeCab）。建议 +2 MB 先验证。
2. **内置 vs 下载**：建议内置（离线、可复现）；下载方案要额外的校验/回退/隐私说明。
3. **许可取舍**：IPADIC（附 COPYING，最省体积）／UniDic New BSD（词类更好、体积大）／Sudachi Apache（许可最省心、体积最大）。
4. **是否保留 LLM 通道**：建议保留为可选兜底（词典无法覆盖的新词），并明确「关掉 = 规则基线」的语义。

---

## 附录 A：实测方法与可复现命令

环境：本机 Linux + Python 3.12；IPADIC 源包取自 Ubuntu 归档：

```bash
curl -L -o ipadic.tar.gz \
  http://archive.ubuntu.com/ubuntu/pool/main/m/mecab-ipadic/mecab-ipadic_2.7.0-20070801+main.orig.tar.gz
tar xzf ipadic.tar.gz   # 12,208,105 B -> 53 MB, 26 个 EUC-JP CSV
```

统计与切分实验（脚本要点，全文可在 .work 复现）：

```python
# 1) 词条与索引规模
rows = 解析 26 个 CSV（euc_jp），共 392,126 行
kana_surface = {fold(surface): min(cost)}      # 73,102 条（折叠后）
kana_reading = {fold(reading): min(cost)}      # 202,417 条（折叠后，含表记并集）
# 2) trie / DAWG
trie 节点 = 所有前缀去重            # A: 112,831
DAWG 状态 = 自底向上最小化 trie     # A: 15,085
# 3) 切分
Viterbi(dp) over 字符格 / 容器格，cost 取条目最小 cost，OOV = 9000 + 1800*len，词惩罚 800
```

## 附录 B：参考来源

- IPADIC 2.7.0 源包与 `COPYING`：Ubuntu 归档（实测包内文件）；许可要点见 §1.1。
- UniDic 许可（GPL v2.0 / LGPL v2.1 / New BSD）：https://clrd.ninjal.ac.jp/unidic/download.html
- SudachiDict 许可（Apache-2.0）与三种词典定义：https://github.com/WorksApplications/SudachiDict
- mecab-ipadic-NEologd 许可与规模：https://github.com/neologd/mecab-ipadic-neologd（README / COPYING）
- JMdict 许可（CC BY-SA 4.0）：https://www.edrdg.org/edrdg/licence.html
- 体积锚点：PyPI 元数据 `pypi.org/pypi/{ipadic,unidic,SudachiDict-small,SudachiDict-core,unidic-lite}/json`；Maven Central `com/atilika/kuromoji/kuromoji-ipadic/0.9.0/`（jar = 13,343,016 B）；Ubuntu `packages.ubuntu.com/jammy/{mecab-ipadic,naist-jdic}`（6,560.2 kB / 53,075.0 kB；3,282.6 kB / 27,723.0 kB）。
- 现有规则与接入点：`host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsPronunciationWordAlignment.kt`（`group()` / `joins()` / `isWordLevelLine()` / `boundaryTokens()`）；`core/src/main/kotlin/dev/amenhancer/module/lyrics/online/PronunciationBoundary.kt`（`hardBoundaries` / `uncertain` / `mergeLine`）。