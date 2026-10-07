# 已接受的偏差登记（Accepted Deviations）

> 用途：记录**已知但本轮不修**的合规偏差，避免每一轮 tester 重复报同一批存量问题，
> 也避免「修一批 → 扫出新存量 → 再修 → 再扫出」的无限循环。
>
> 规则：tester 审查时，本文件列出的条目**不计入判定**；但**新增**条目需由编排者（主 agent）确认后写入。
> 每轮批次开始前，编排者应把本文件中「下批次处理」的条目纳入 PRD／实现指南。

最后更新：2026-10-07（v1.0.7 缺陷修复批次）

---

## 判定口径说明（重要）

本项目的代码质量审查维度 2（i18n 覆盖）规则为：
**玩家可见文本必须走 `messages/zh.yml` + `messages/en.yml`；`getString(key, "硬编码兜底")` 的 fallback 参数本身即违规。**

豁免项（不算违规）：
- 空串 `""` 作为 fallback
- 纯装饰性物品名（如占位玻璃板的 displayName 是 `" "` 或纯颜色码）
- **配置文件**的默认值（`getMainConfig().getString(...)`、`getGuiSlot(...)`、`getGuiMaterial(...)`、`DatabaseManager` / `StatisticsHttpServer` / `AIChatManager` 的配置兜底）—— 这些不是 i18n fallback

**审查范围口径**：审查「本批次改动过的文件」+「项目规则明列的维度」，而非「全项目所有历史代码」。
全项目扫描发现的**存量**问题，若已登记在本文件，不计入判定。

---

## A. v1.0.7 缺陷修复批次 — 已接受，下批次处理

### A1. `core/achievement/Achievement.java` — 16 个成就的双语名/描述硬编码在 enum 内

- **位置**：`Achievement.java:186`（`getDisplayName`）、`:190`（`getDisplayDesc`）
- **现状**：`"zh".equalsIgnoreCase(language) ? zhName : enName`，其中 `zhName`/`enName`/`zhDesc`/`enDesc` 是 enum 常量构造参数里的**中英双语文案字面量**（如 `FIRST_WORD("first_word", "初出茅庐", "Fresh Start", "在任意模式中答对第 1 个单词…", "Answer your first word…")`）。
- **为什么本轮不修**：这是**设计决策**而非缺陷 —— 中英两套文案都在，切换语言工作正常。要搬到 `messages/*.yml` 属于**独立重构**（16 个成就 × 4 字段 = 64 条文案迁移 + 键设计 + 与 `achievements-config.yml` 的关系梳理），会把本批「止血」性质的修复变成 i18n 全量重构。
- **下批次处理方式**：迁移到 `messages/*.yml` 的 `achievement.<id>.name` / `.desc`，enum 只保留 `id`；同步更新 `AchievementWallGUI`、`AchievementManager`、`ProfileGUI`、`LLEPapiExpansion` 的读取点。
- **来源**：`ralph/test-reports/P1-code-r2.md` 问题 7（严重程度：建议）

### A2. `gui/ChatHistoryGUI.java:297` — `default:` 分支拼接原始 role 标识符

- **位置**：`ChatHistoryGUI.java:297`，`rolePrefix = "&7[" + entry.getRole() + "]"`
- **为什么本轮不修**：该 `default` 分支**不可达** —— `insertChatHistory` 的 role 实参只有 `"system"` / `"user"` / `"assistant"`（`AIChatManager:351,531,556`），三种都有显式 `case`。
- **下批次**：随手接入 messages 键或改为空串。
- **来源**：`ralph/test-reports/P1-code-r3.md` 观察项（e）

### A3. `core/word/WordBankValidator.java:30-175` — 校验器内部错误文本为硬编码英文

- **现象**：如 `Missing required key 'words'`，经 `{error}` 占位符注入玩家可见的校验结果行。
- **为什么本轮不修**：属「**数据注入**」而非「文案拼接」—— 与 `general.error` 的 `{message}`、`wordbank.import-failed` 的 `{reason}` 同型；外层表头/行格式已 i18n 化（`wordbank.validation-failed` / `validation-error-line`），注入的是校验器的原始诊断文本。要翻译它需给每条校验规则设计键，属独立重构。
- **下批次**：为每条校验规则设计错误键（`wordbank.error.<rule>`）。
- **来源**：`ralph/test-reports/P1-code-r3.md` 观察项（e）

### A4. 死配置：`gui-layout` 下没有代码读取的槽位项

- `config.yml:257-260` `gui-layout.main-menu.scenario-select.back-button.slot: 8` —— 全仓库无 Java 读它（`MainMenu` 渲染的是 `dialogue-practice`@13，没有 scenario-select 项）；`ScenarioSelectGUI` 也不读 `gui-layout`（全部 `setItem` 写死数字）。
- `gui-layout.profile.back-button.slot: 8` —— `ProfileGUI` 的 `getGuiSlot` 命中 0 次，同样无人读。
- **风险方向**：与 D-03/D-04 同源 —— 一旦有人"照配置改"，GUI 仍渲染在写死的槽位，listener 若读配置就会漂移。当前 **GUI 与 listener 都是写死值，两者一致**，故无实际故障。`SocialListener` 的 profile / achievement-wall 槽位因此改用**共享常量**而非配置读取（加了注释说明），避免制造出比原 bug 更糟的状态。
- **下批次**：二选一 —— 让 `ProfileGUI` / `AchievementWallGUI` / `ScenarioSelectGUI` 真读 `gui-layout` 并让 listener 同步读；或删掉这些死配置项。
- **来源**：`ralph/test-reports/P1-arch.md` W2；Agent F／G 报告

---

## B. 已知但暂不修的技术债（下一版本规划）

> 这些是架构层面的，不属于「缺陷」，不参与 code tester 的判定。

### B1. `core` → `gui` 反向依赖（3 处）

- `core/SoundManager.java:4` → `gui.GUIUtils`
- `core/duel/DuelManager.java:7` → `gui.DuelGUI`
- `core/word/WordLearningManager.java:17` → `gui.WordLearningGUI`
- **建议**：`GUIUtils` 是纯静态工具，应移到 `util/`
- **来源**：`ralph/test-reports/P1-arch.md` W1

### B2. 超 300 行的类（6 个）

`data/PlayerDataStore`(845)、`gui/ChatHistoryGUI`(332)、`listener/SocialListener`(325)、`core/duel/DuelManager`(643)、`command/LearnCommand`(~520)、`core/manager/StatisticsHttpServer`(462)
- **来源**：`P1-arch.md` W3

### B3. GUI 识别的标题字符串匹配（本批次未动的根本改造）

17 个 GUI 全部用 `Bukkit.createInventory(null, size, title)`，holder 为 null；9 处 listener 靠标题字符串判定界面。
- 本批次修掉了**由它引发的具体 bug**（D-03 / D-04 / D-13a），但**没有**做根本改造。
- **建议下批次**：引入 `InventoryHolder`（每个 GUI 一个 holder，listener 用 `instanceof`），同时补全 `InventoryDragEvent` 处理与空槽兜底填充。
- **来源**：`docs/reports/项目现状体检报告.html` P2 阶段

### B4. 单一 JDBC 连接被多线程共写

`data/DatabaseManager.java:34` 全插件一个 `Connection`；`PlayerDataStore` 在共享连接上 `setAutoCommit(false)`。
- **建议**：引入 HikariCP 或把所有 DB 访问串行化到单一执行器。
- **来源**：体检报告 D-09；`docs/reports/优化总结报告_v1.1.0.md` 列为 🔴 高优先
- **注**：本批次已切断「异步线程调 Bukkit API」那条路径（`getRankedPlayers` / `getRankedPlayersForPeriod`），但连接共享本身未动。

### B5. 未接线的功能（键/配置存在但代码不用）

| 项 | 说明 |
|---|---|
| `learning_log` 表 | 建表 + SELECT 有，**全项目零 INSERT** → 统计面板会话数恒为 0（体检报告 D-11） |
| `Leaderboard.updateScore()` | 零调用 → `%lle_rank%` 从开服冻结到重启（D-08） |
| `config.yml` `word-system.modes.*` | 10 个键零引用，答题模式配置未接线（D-17） |
| 4 个权限节点 | `letslearnenglish.use` / `.word.*` / `.dialogue.*` / `.progress.*` 零校验（D-14） |
| `ReviewScheduler` 间隔数组 | `final` + 构造时一次性读，`/lea reload` 改 `review.intervals` 不生效，需重启 |

### B6. 死代码（已确认零引用，下批次可安全删除）

- `gui/ProgressGUI.java`（94 行，无 listener、全项目从未 `new`）—— 本批次已做 i18n 收口，删除时改动会被一并带走
- `core/progress/Achievement.java`（40 行 class；`giveAchievementReward` 删除后已**彻底零引用**）
- `player_achievements` 表（建表但零读写）
- `achievements.yml` + `ConfigManager.getAchievementConfig()`（实际生效的是 `achievements-config.yml`）
- `data/PlayerDataStore.saveDifficultWords()`（空实现）、`getWordMastery()`（唯一调用者已删）
- `data/DatabaseManager.clearWrongCount()` / `resetCheckInStreak()`
- `core/SoundManager.reloadSoundCache()` —— **本批次已接线**，移出此清单

### B7. 文档与代码脱节（本批次已修部分）

**已修**：README 命令表（中英）、README 的 TTS 措辞
**未修**：
- `DEPLOYMENT_WIKI.md` 版本滞后（标注「适用版本 1.0.6+」）
- `ralph/test-reports/v1.0.7-arch.md` / `v1.0.7-code.md` 的 FAIL 状态滞留（其中多数条目实代码已修，但无 r2 复测）
- `ralph/prd.json` 的 `branchName: ralph/v1.0.7-iteration` 从未创建
- `.claude/workflows/ralph-pipeline.js` 的「修正循环」在代码里不存在，`MAX_ROUNDS` 未使用，Phase 4 只打印提示
- `rules/CLAUDE.md` 与 `rules/project_rules.md` 关于「编译后是否复制 JAR 到 versions/」互相矛盾

---

## C. 本批次已修（供对照，不要重复报）

以下问题在本批次（v1.0.7 缺陷修复）已修复，tester 若在新报告里再次命中，请先核对代码再判定：

- 中文服对决退出按钮失效（D-03）、广场翻页失效（D-04）、对话历史第 9 条点不开（D-05）、错题本 9 个单词点不动（D-06）
- 答题反馈永不本地化 5 组键名错位（D-02）、`Missing message: progress.money-*`（D-01）
- `learning_log` 之外：`InventoryDragEvent` 缺失（D-13）、确认界面无取消路径（D-18）
- 全项目 fallback 参数 177 处、整句硬编码与中英三元（`ScenarioSelectListener` / `LLEPapiExpansion` / `AdminCommandHandler` / `UpdateChecker`）
- `%lle_rank%` 之外：`PlayerDataStore` 两条排行榜查询在异步线程调 Bukkit API
- `RewardEngine` / `WordManagerImpl` 死方法、`SoundManager` 竞态、`AIChatManager` 9 字段 volatile、`HotReloadManager` 热重载补全
- GUI 槽位漂移（`SocialListener` / `WordLearningListener` 8 处改为读 `gui-layout`）
- `ChatHistoryGUI` 内部标记改 `PersistentDataContainer`
