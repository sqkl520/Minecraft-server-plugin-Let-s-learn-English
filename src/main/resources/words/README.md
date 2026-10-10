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
| difficulty_level | int | 模板必填 / loader 可选（缺省 1） | 数字难度；beginner 用 1~2 |
| reward | number | 可选（缺省 0.0=用全局配置） | 单词自定义奖励；**现有词库未使用**，sample 也不写 |

说明：
- `WordBankValidator.java` 真正强制的只有 `english`、`chinese`（非空字符串）与各字段
  类型；其余字段“若存在则类型须正确”。
- 本项目模板约定（beginner.yml 的写法）要求 8 个字段齐全。生成脚本的自检按“模板齐全”
  这一更严标准执行。
- `reward` 虽被 loader 支持，但三档词库均未使用，依“以实际文件为准”本 sample 不输出。
- 单文件最多 1000 词（`WordBankValidator.MAX_WORDS`）。
- `difficulty_level` 语义：现有 beginner 全为 1，intermediate 从 4 起。本 sample 采用
  1=最基础，2=仍属 beginner 但略抽象/略低频（family/money/know/open），均 <4。

## 词表来源与许可

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

## 本次扩充产出（CONTENT-02，三档 ≥300 词）

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
- `advanced.yml`：**330 词**（原有 10 词保留 + 新增 320 词）。
  自检：**0 error / 0 warning**（顺带修复 `elaborate` 的 `part_of_speech: "adjective/verb"`
  非法斜杠值 → `verb`；crumb/crust 例句错位、defy/dignify 原形、curfew 缺字段一并修正）。
  `difficulty_level` 分布：7 级 125 / 8 级 134 / 9 级 71。
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
