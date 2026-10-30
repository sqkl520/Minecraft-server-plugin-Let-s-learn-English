# words/ 词库说明

本目录存放英语学习词库 YAML。插件在运行时从 **数据目录** `plugins/<plugin>/words/`
下按 `*.yml` 自动发现并加载（见 `WordManagerImpl.loadAllWordBanks()`）。JAR 内置并
自动释放到数据目录的只有三个固定名：`beginner.yml` / `intermediate.yml` /
`advanced.yml`（见 `ConfigManager.loadAllConfigs()` 的 `saveResourceIfNotExists`）。

## 词条模板（权威字段结构）

字段结构以 `beginner.yml` 实际文件为准，并经 `Word.java` / `WordBank.java` /
`WordBankValidator.java` 交叉确认。

文件顶层：

```yaml
difficulty: "beginner"      # 词库难度标识（字符串）
description: "..."           # 词库描述（字符串）
words:                       # 词条列表
  - english: "apple"
    ...
```

单个词条字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| english | string | 是（loader 必填，非空） | 英文单词 |
| chinese | string | 是（loader 必填，非空） | 中文释义 |
| part_of_speech | string | 模板必填 / loader 可选 | 词性 noun/verb/adjective/... |
| phonetic | string | 模板必填 / loader 可选 | IPA 音标，形如 `/ˈæp.əl/` |
| example_sentence | string | 模板必填 / loader 可选 | 例句，应包含该单词 |
| synonyms | list<string> | 可选（缺省 `[]`） | 同义词；无则 `[]` |
| antonyms | list<string> | 可选（缺省 `[]`） | 反义词；无则 `[]` |
| difficulty_level | int | 模板必填 / loader 可选（缺省 1） | 数字难度 1~9；三档各占连续 3 级：beginner 1~3（285/36/26）、intermediate 4~6（134/146/69）、advanced 7~9（125/134/70） |
| reward | number | 可选（缺省 0.0=用全局配置） | 单词自定义奖励；**现有词库未使用**，sample 也不写 |

说明：
- `WordBankValidator.java` 真正强制的只有 `english`、`chinese`（非空字符串）与各字段
  类型；其余字段“若存在则类型须正确”。
- 本项目模板约定（beginner.yml 的写法）要求 8 个字段齐全。生成脚本的自检按“模板齐全”
  这一更严标准执行。
- `reward` 虽被 loader 支持，但三档词库均未使用，依“以实际文件为准”本 sample 不输出。
- 单文件最多 1000 词（`WordBankValidator.MAX_WORDS`）。
- `difficulty_level` 语义：1=最基础，数字越大越难；beginner 内 2 级为略抽象/略低频词
  （family/money/know/open 等）。各档区间与分布见上表（与三档实际数据一致）。

## 词表来源、分级与许可

**选词参考 NGSL / GSL / 中国课标，但未逐词校验词频排名。** 具体地：

- 选词时参考了 **NGSL（New General Service List）**、公有领域 **GSL** 与中国《义务教育
  英语课程标准（2022）》词汇表的**头部高频词印象**，用于决定“收哪些词”；但**没有任何
  一条词条保存或校对过它在上述词表中的真实排名**，也没有按排名做全库排序或裁剪。因此
  “本词库按词频选词”这一说法**只能理解为选词来源参考，不能理解为逐词词频校验**。
- 词条**没有 `freq_rank` / `cefr` 字段**。当前 8 字段模板（english / chinese /
  part_of_speech / phonetic / example_sentence / synonyms / antonyms /
  difficulty_level）不包含任何外部词频或 CEFR 等级信息。若后续要做“按词频加权出题”
  或“按 CEFR 对齐”，属于**新增字段 + 消费端接线**的独立改动，不是改几个 YAML 值即可。
- **`difficulty_level` 当前是人工标注，且运行时尚未被消费。** `WordBank` 会把该字段
  读进 `Word`（缺省 1，见 `WordBank.java` 的 `difficultyLevel` 解析），`Word` 也提供了
  `getDifficultyLevel()`，但全仓仅 `AdminCommandHandler` 在**导出词库**时把它写回 YAML
  （`words[...].difficulty_level`），**没有任何出题/选词/复习/结算逻辑按它筛选或加权**。
  换言之：该字段目前只影响“数据是否原样保留”，不影响玩家体验。出题难度实际由**玩家
  选择的词库档位（beginner / intermediate / advanced 文件级 `difficulty`）**决定。
  三档内的 1~3 / 4~6 / 7~9 分级是**人工难度标注**，不是运行时分层。
- `beginner.yml` 内 `difficulty_level` 的实际分布为 1 级 285 / 2 级 36 / 3 级 26
  （功能词一律标注为 1，因为它们是初学者最先接触的词）。
- **同义/反义字段**：功能词（冠词、代词、助动词、介词）通常没有可靠的同反义词，统一
  写 `[]`，不硬凑。
- **撞车释义**：`AnswerChecker.buildChoiceOptions()` 会按 `;；,，、` 切分中文释义做
  “义项撞车”判定，任一分段相同即视为撞车并把该候选从正常干扰项池剔除。因此**释义里
  用分号堆多个义项会放大撞车面**（如 `能；会` 会和 `将；会` 撞“会”）。新增功能词时
  已按此规则收敛：只用**一个主释义 + 括号用法提示**，括号内不再用分号/逗号堆义项。

**许可与内容来源：**

- **选词依据**：高频通用英语词汇，参考以下公开/开放许可词表（仅用于“选哪些词”）：
  - **NGSL（New General Service List）**，Browne, Culligan & Phillips，许可
    **CC BY-SA 4.0**（http://www.newgeneralservicelist.org/ ）。
  - 公有领域的 **GSL（General Service List, West 1953）**。
  - 中国《义务教育英语课程标准（2022）》词汇表（公开政府文件）中的小学/初中基础词。

  以上均为公开资料，**未抓取任何需登录鉴权的资源**。
- **逐词内容**（中文释义、IPA 音标、例句、同/反义词）为本项目**原创编写**，随项目许可
  一并发布；音标采用 Cambridge 英式（UK）风格，与现有 `beginner.yml`（如 `/ˈæp.əl/`、
  `/ˈwɔː.tər/`）保持一致。
- 单个常用词本身属事实性信息、不受著作权保护；引用上述开放/公有词表仅用于选词，内容为
  自撰，故无版权风险。

## 生成脚本

- 位置：`scripts/gen-words.js`（项目根 `scripts/` 目录，**在 `src/main/resources` 之外，
  不会被 Maven 打进 JAR**）。
- 运行环境：Node.js（已验证 v24），**零第三方依赖**（仅用核心模块）。
- 用法：

  ```bash
  node scripts/gen-words.js              # 生成 sample-beginner.yml 并自检
  node scripts/gen-words.js generate     # 同上
  node scripts/gen-words.js check <file> # 校验任意词库 YAML
  ```

- 自检规则（镜像 `WordBankValidator.java` + 模板齐全 + 质量项）：
  - english/chinese 必填非空；各字段类型正确；模板 8 字段齐全；
  - `difficulty_level` 为整数；synonyms/antonyms 为字符串列表；
  - 音标须 `/.../` 包裹；例句须含该词；sample 内英文不重复；与 beginner.yml 重叠给 warning；
  - emit→parse 往返一致性校验（证明输出 YAML 语法合法且忠实）。
  - 退出码：有 error 非 0；纯 warning/REVIEW 返回 0。
- 注意：脚本做的是自包含结构 + 往返校验；**最终权威校验仍以插件 SnakeYAML 加载为准**
  （由编排者在汇总时随 Java 构建执行）。

### 释义撞车扫描：`scripts/check-word-collisions.js`

`gen-words.js` **不检查**中文释义是否互相撞车，而 `AnswerChecker.buildChoiceOptions()`
在 choice 模式下会按 `[;；,，、]` 把 `chinese` 切成义项集合，任一义项相同就把该候选
从正常干扰项池剔除（退到 soft/hard 兜底）。因此释义撞车会造成干扰项“悄悄变少”，
极端情况下才触发兜底、出现两个都对的选项。

```bash
node scripts/check-word-collisions.js                    # 扫三档（默认档内），完整报告
node scripts/check-word-collisions.js beginner.yml       # 只扫指定文件
node scripts/check-word-collisions.js --cross            # 附带跨档撞车
node scripts/check-word-collisions.js --quiet            # 只输出计数
```

- 归一化与切分规则**逐条对齐 `AnswerChecker`**（NFKC / 去零宽 / 折叠空白 / 去末尾标点 /
  按 `;；,，、` 切义项）。
- 分类：`[HARD]` = 整条释义归一化后完全相同（必然双正确，最该修）；`[soft]` = 仅某个
  义项重叠（多半是真实近义，酌情保留）。
- 退出码：0 = 无 HARD 撞车；1 = 存在 HARD；2 = 读文件失败。可用于 CI 门禁。
- 现状：`beginner.yml` HARD=0 / soft=5（5 对均为真实近义，见 CONTENT-03 一节）；
  三档合并扫描仍有 HARD 撞车（主要在 intermediate/advanced 内部），属后续批次范围。

## 本次扩充产出（CONTENT-02，三档 ≥300 词）

> 以下数字为 CONTENT-02 时点的快照；beginner 档已在 CONTENT-03 增至 347 词
> （见下一节）。

- `beginner.yml`：**317 词**（原有 10 词逐字节保留 + `sample-beginner.yml` 50 词全数合并 + 新增 257 词）。
  自检 `node scripts/gen-words.js check`：**0 error / 0 warning**。
  `difficulty_level` 分布：1 级 255 / 2 级 36 / 3 级 26。
  抽查 20 词（sun、river、knife、panda、elephant、tooth、grey、hundred、woman、
  homework、answer、dance、thirsty、autumn、honest、sometimes、whose、without、
  because、clever）：音标、例句、释义全部合格，无硬伤。
  样本词剔除：无（50 词全部并入，仅去掉 REVIEW 头注与行内 `# REVIEW:` 注释）。
- `intermediate.yml`：**349 词**（原有 10 词保留 + 新增 339 词）。
  自检：**0 error / 0 warning**（首轮 5 警告：4 个与 beginner 重叠词 angry/brave/clever/
  hobby 已替换为 annoyed/bold/witty/leisure；fling 例句过去式 flung 已改含原形）。
  `difficulty_level` 分布：4 级 134 / 5 级 146 / 6 级 69。
  抽查 20 词（annoyed、bold、witty、leisure、fling、durable、eclipse、embarrass、
  enhance、enquire、dormitory、finale、heir、fragile、fragrant、frugal、frustrate、
  furnish、elastic、dusk）：全部合格，无硬伤。
- `advanced.yml`：**329 词**（原有 10 词保留 + 新增 320 词，低危批次内容调优再删生僻词
  `dingle` → 329）。
  自检：**0 error / 0 warning**（顺带修复 `elaborate` 的 `part_of_speech: "adjective/verb"`
  非法斜杠值 → `verb`；crumb/crust 例句错位、defy/dignify 原形、curfew 缺字段一并修正）。
  `difficulty_level` 分布：7 级 125 / 8 级 134 / 9 级 70（dingle 删除后）。
  抽查 20 词（advocate、behalf、capable、chronic、combat、compliment、confer、
  constrict、cordial、crisis、cult、decade、default、delicate、depreciate、deviate、
  dignify、dismal、diversify、phenomenon）：全部合格，无硬伤。
  跨档查重：终验确认三档之间零重复词头（intermediate×advanced 的 16 个重叠词
  capable/caution/charm/crisis/crisp/cruise/damp/debate/decent/decline/defeat/
  delicate/dense/deposit/devote/dismiss 已在 advanced 侧替换为
  proficient/vigilance/enchantment/turmoil/brisk/excursion/musty/wrangle/becoming/
  wane/vanquish/exquisite/succinct/instalment/consecrate/traverse）。
- 词表来源：NGSL/GSL/中国课标高频词（仅用于“选哪些词”，事实性词汇无版权风险）；
  全部释义、音标、例句为原创编写；音标采用 Cambridge 英式风格（rhotic-r 惯例与
  现有文件一致）。
- `sample-beginner.yml` 已全数并入 `beginner.yml` 并删除（此前 pom 已将其排除出 JAR）。

## 功能词补充（CONTENT-03）

- 问题：CONTENT-02 的 995 词里，NGSL 头部 30 个功能词 **29 个全库 0 命中**（仅 `our`
  已在库），词库“号称按 NGSL 选词”与事实不符。本批次补 **30 个**。
- `beginner.yml`：317 → **347 词**，自检 **0 error / 0 warning**（插入后重新按字母序
  验证 0 处乱序，全库 0 重复词头）。`difficulty_level` 分布变为 1 级 285 / 2 级 36 /
  3 级 26。
- 新增词（按字母序）：a / about / be / been / can / different / do / have / if /
  important / its / just / more / only / out / over / people / said / see / such /
  than / the / them / then / think / time / up / us / want / will。
- 词性：冠词 a / the 归入既有 `determiner`（未新增 `article` 枚举）；be / been / do /
  have / can / will 归入 `verb`（未新增 `auxiliary`）。全部为初学者最先接触的词，
  统一 `difficulty_level: 1`；功能词不硬凑同反义词，除 `different` 反义词 `same` 外
  均为空数组。
- 释义写法：一个主释义 + 括号内用法提示，且**括号内不用 `，` / `；` / `、`**（改用
  `：` 分隔），例如 `the: 这个/那个（定冠词：指双方都知道或特定的人或事物）`。
  原因见下条：`AnswerChecker` 会把括号里的分句也切成一个义项。
- 同批次修复 beginner 内释义撞车（`AnswerChecker` 义项级判定口径）：
  - 既有精确重复：`little` / `small` 同为“小的” → `little` 改“小巧的；少量的”；
    `clever` / `smart` 同为“聪明的” → `clever` 改“机灵的；巧妙的”。
  - 跨档重复：`talk` 释义“交谈”与 advanced 的 `converse` 完全同义 → `talk` 改“谈话”。
  - 新增词自检撞车：`can` 原写“能；会”与 `will` 的“将；会”撞“会” →
    `can` 改“能够（情态动词：表示能力）”、`will` 改“将要（情态动词：表示将来）”；
    `have` 原写“有；吃，喝”与 `eat` 的“吃”撞 → 改“拥有（表示所属：第三人称单数
    用 has）”；`them` 原写“他们；她们；它们”与 `they` 的“他们”撞 → 改“他们（宾格：
    作动词或介词的宾语）”。
  - 顺带修复同文件内重复例句：`four` 与 `leg` 都用了 “The table has four legs.” →
    `four` 改 “There are four seasons in a year.”。
  - 剩余 5 对分词级同义（非完全重复，按设计落入 softFallback 兜底，未改动）：
    `also`/`too`（也）、`any`/`some`（一些）、`for`/`give`（给）、`no`/`not`（不）、
    `no`/`without`（没有）。这些是真实的近义关系，强行改写会牺牲释义准确性，故保留。
- 新增只读校验脚本 `scripts/check-word-collisions.js`（见“生成脚本”一节），
  按 `AnswerChecker` 同一套归一化/切分规则扫描释义撞车。

## 同义词级别审查（低危批次内容调优）

- 原则：synonyms 中每个词若单独出现，也应属于该词条所在档位（或更低档）；会被放进更高
  档的属超纲，移除。
- 本次对 `beginner.yml` 全部 317 条词条的 synonyms 做了泛化扫描，删除 10 个超纲/语域不符的
  同义词：feline、canine、consume、dine、bold、breeze、companion、automobile、academy、
  institution；灰色地带一律保留（如 infant、limb、volume、shore，宁缺毋滥）。
- `advanced.yml` 同步删除生僻噪声词 `dingle`（与 dingy 形近易混）。同原则适用于
  intermediate/advanced 档的同义词面（尚未执行，留后续批次评估）。

## 提升为正式词库的流程（人工）

1. 人工抽查新词条（重点核对音标与全部例句；CONTENT-02 已按每档 20 词抽查，
   结论见上方“本次扩充产出”）。
2. 核准后，把词条合并进目标档文件（追加到 `words:` 列表）。
3. 删除或移出评审样本文件（`sample-beginner.yml` 已于 CONTENT-02 并入后删除）。
4. 由编排者执行一次 `mvn clean package`，让插件 SnakeYAML 做最终校验。

## 注意（运行时与打包）

- 评审样本文件说明（历史）：`sample-beginner.yml` 曾位于 `src/main/resources/words/`，
  运行时**不会被加载**（`ConfigManager` 只释放 beginner/intermediate/advanced 三个固定名
  到数据目录），且 pom 已将其排除出 JAR。该文件已于 CONTENT-02 全数并入
  `beginner.yml` 并删除。
