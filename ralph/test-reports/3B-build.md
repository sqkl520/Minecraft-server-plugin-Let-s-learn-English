# 编译测试报告 3B（Phase 3 · Wave 3 · Batch 3B，US-FB AI 反馈闭环）

### 判定：GO（构建全绿，无阻塞问题）

## 一、构建结果

| 项 | 值 |
|----|----|
| 命令 | `JAVA_HOME=D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64 ; mvn clean verify` |
| 结果 | **BUILD SUCCESS** |
| 耗时 | 51.155 s（Finished 2026-10-15T23:01:08+08:00） |
| 主源码 | 87 source files，javac [debug target 21] |
| 测试源码 | 6 source files，javac [debug target 21] |
| 测试总数 | **39** run / 0 Failures / 0 Errors / 0 Skipped |
| 新增测试 | `ConversationFeedbackTest` 6 tests，0.242 s，全绿 |
| Jacoco | `jacoco:check` — All coverage checks have been met. |
| 产物 | `target/Let's Learn English!-1.0.7-SNAPSHOT.jar` |

原始日志：`ralph/test-reports/_build-3B-raw.log`

## 二、逐项检查结果

### 1. 编译警告 — PASS
- 构建日志 **无任何 `[WARNING]` 行**（grep `\[WARNING\]` = 0 命中）。
- 唯一提示为 `VersionAdapter.java` 的既有 `-Xlint:deprecation` 提示（INFO 级，deprecation 按判定标准豁免；文件非 3B 改动范围）。
- annotation-processing 提示与 surefire 动态加载 byte-buddy-agent 的 JVM WARNING 均为工具链固有噪声，非新增编译警告。
- 结论：**零新增编译警告**。

### 2. schema.sql DDL 与 DatabaseManager.createTables 内嵌 DDL 一致性 — PASS
- `schema.sql:131-144` vs `DatabaseManager.java:241-254`（内嵌 SQLite 分支）。
- 用 Node 脚本提取两处并做 **括号感知** 的列切分（避免 `UNIQUE(a, b)` 逗号误切），归一化 `AUTO_INCREMENT`→`AUTOINCREMENT` + 空白后逐列比对。
- 结果：**12 列全部逐字一致**（id / player_uuid / session_id / scenario_key / difficulty_key / user_messages / ai_messages / duration_seconds / source / ai_payload / created_at / UNIQUE(player_uuid, session_id)）。
- 附带核对 upsert 语句列清单（`DatabaseManager.java:694-702`）与 `getChatFeedback` SELECT 列清单（`:739-741`）均与 DDL 列名匹配，含 MySQL 分支 `AUTO_INCREMENT` 映射正确。

### 3. zh.yml / en.yml 键对称 — PASS
- 叶子键计数：**zh = 695，en = 695**，双向差集均为 **0**（`only in zh: 0`，`only in en: 0`），**零差异**，命中基线（695=695）。
- 本批新增键覆盖：`dialogue.feedback.*` zh=17 / en=17；`chat-history.feedback*` zh=10 / en=10，两侧完全对称。
- `node scripts/audit-i18n-keys.js`：referenced dotted keys 515，MISSING 仅报 `achievement-wall.category-`（[zh-miss] [en-miss]）—— **即已知的动态拼接误报**，非本批引入，属正常。
- 附：10 个 `chat-history.feedback-*` 键在 `ChatHistoryGUI.java:373-438` **全部有真实消费点**（含 `feedback-no-ai` 在 catch 与 else 两分支使用），无僵尸键；`FEEDBACK_SLOT=49` 落在 `GUI_SIZE=54` 边界内。

### 4. config.yml ai-chat.feedback.* 六键与代码读取匹配 — PASS
| config 键（config.yml） | 读取点 |
|-------------------------|--------|
| `ai-chat.feedback.enabled` | ChatFeedbackManager.java:53, 120；ChatHistoryGUI.java:355 |
| `ai-chat.feedback.ai-summary` | ChatFeedbackManager.java:92 |
| `ai-chat.feedback.ai-timeout-seconds` | AIChatManager.java:275 |
| `ai-chat.feedback.ai-max-tokens` | AIChatManager.java:273 |
| `ai-chat.feedback.transcript-max-chars` | AIChatManager.java:277 |
| `ai-chat.feedback.max-corrections-display` | ChatFeedbackManager.java:148 |
- 六键 **逐一匹配**，且代码默认值与 config.yml 值一致（400/15/4000/3/true/true）。
- 阈值复用键 `ai-chat.count-min-rounds`（ChatFeedbackManager.java:64，默认 3）与 config.yml `ai-chat.count-min-rounds: 3` 一致，未复制第二套阈值。

### 5. 新增测试 ConversationFeedbackTest 真实度 — PASS（真实，非假测试）
- 6 个测试均断言解析器的关键契约，且我能从源码反推断言为何成立：
  - `testWellFormedJsonParses`：正常 JSON → 全字段映射（真实走 Gson 解析）。
  - `testMarkdownFencesAndChatterAreSalvaged`：```json 围栏 + 前后话术 → salvage 后取 `{...}`（对应 `salvageJsonObject` 的 fence 剥离 + first`{`/last`}`）。
  - `testGarbageOutputReturnsNull`：null/空白/纯话术/`{ broken`/非对象/原始字符串 → null（6 个断言，覆盖多降级入口）。
  - `testHardCapsAreEnforced`：comment 600→500、corrections 7→5、words 10→8、单字段 300→200、单词 60→40（逐条对应 §3.2 上限常量）。
  - `testMissingOrWrongTypedFieldsDegrade`：`{}`→空降级（非 null）、类型错字段各自降级、corrections 内 null/字符串/空对象跳过 + 部分条目保留。
  - `testControlCharsAndSectionSignAreStripped`：源码确认为 `\u0007`(BEL, 0x07) 与 `§`(0xA7)，落在 `CONTROL_CHARS_REGEX` 命中范围，断言 `"Nice cwork"` 真实成立。
- 无 `assertTrue(true)` / 无空断言 / 无被吞异常 / 无 `@Disabled` 跳过；断言用 `assertEquals` 精确值而非仅 `assertNotNull`，未发现假测试。

### 6. 集成点接线（顺带核对）— PASS
- `LetsLearnEnglish.java:83/169/697`：字段 + `new ChatFeedbackManager(this, aiChatManager)` + getter 齐备。
- `ChatScenarioListener.java:172-174`（quit→persistOnQuit）、`:185-190`（正常结束→deliverEnd）双路径接入。
- `LearnCommand.java:286-290`：`/le dialogue` 退出改走 `removePlayer` 去重（PF-FB-01），无重复 ended 文案。
- `PlayerJoinListener.java:128-129`：`clearPendingSummary` + `unloadPlayer` quit 编排点。
- 重构收敛无残留：旧五 map 名（conversationContexts / activeSessionIds / playerSystemPrompts / sessionValidInputs / sessionHasAiReply）仅出现在注释中，无代码引用。
- Gson 版本 2.11.0（paper-api 传递依赖）≥ 2.10，`ChatFeedbackManager.java:173` 对 record 反射序列化成立。

## 三、发现的问题

| 严重度 | 问题 | 证据 |
|--------|------|------|
| 低 | `ConversationFeedback.parse` 的 `!parsed.isJsonObject()` 分支经公开 API **不可达**：`salvageJsonObject` 已保证子串以 `{` 开头、`}` 结尾，故解析结果必为 object。属纯防御分支（源码注释亦自认"防御底线"），无功能影响。 | `ConversationFeedback.java:61-63` vs `:85-90` |
| 低 | `ConversationFeedbackTest.testGarbageOutputReturnsNull` 中 `parse("[1, 2, 3]")` / `parse("\"just a string\"")` 的断言注释写"non-object JSON must fail"，但实际是在 `salvageJsonObject` 的"无成对花括号"处提前返回 null，**未真正触及 `isJsonObject` 那条路径**。断言结果正确，仅注释归因略有偏差。 | `ConversationFeedbackTest.java:60-61` |

无阻塞 / 中等问题。未发现范围外 bug。

## 四、不确定项
- 无。（构建、DDL 一致性、键对称、config 键匹配、测试真实度 5 项均以实跑/脚本实证，未依赖任务描述。）
- 备注：本仓 `git status` 显示 working tree 与 origin/main 分叉（35/3 commits），3B 改动确在工作树未提交状态；这属交付流程事实，非构建问题。
