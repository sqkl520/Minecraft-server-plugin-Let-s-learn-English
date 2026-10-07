# 代码质量报告 P1（第二轮复测）

### 判定：FAIL

> 审查对象：v1.0.7 缺陷修复批次 + 其后两轮「fallback / 整句硬编码」清理（全项目约 70 个 Java 文件 + `messages/*.yml`）。
> 审查方式：**只读源码 + 静态引用核对**，未修改任何代码文件（`Write` 仅用于本报告）。
> 复测目标：验证上一轮 `ralph/test-reports/P1-code.md` 的 **7 项 i18n 违规**是否消除。
>
> **结论**：上一轮 7 项**全部已修复** —— `getString(key, "硬编码兜底")` 的 fallback 字面量已全项目清零（仅剩空串豁免 + 配置文件默认值）；`LearnCommand` 占位符值已改内层键；`ChatHistoryGUI` 内部标记已改 `PersistentDataContainer`。
> **但维度 2（i18n 覆盖）仍存在违规** —— 复测要求的「玩家可见文本是否还有未走 messages 的整句硬编码或中英三元表达式」在 `listener/ScenarioSelectListener.java` 命中 8 处（含任务明示的 `"zh".equalsIgnoreCase(lang) ? "中文" : "English"` 模式），另有 `LLEPapiExpansion` / `Achievement` / `AdminCommandHandler` / `UpdateChecker` 同类命中。按既定规则，任一维度违规即 FAIL。

---

## 问题表格

| # | 维度 | 位置 | 原因 | 严重程度 |
|---|------|------|------|----------|
| 1 | i18n | `listener/ScenarioSelectListener.java:56-58` | 玩家可见整句硬编码 + 中英三元：`"zh".equalsIgnoreCase(lang) ? "&c[AI] API 密钥未配置，AI 对话不可用。请联系管理员配置。" : "&c[AI] API key not configured. AI chat is unavailable. Contact admin."` 直接 `sendMessage`。**对应键 `dialogue.ai-not-configured` 已存在**（zh.yml:55 / en.yml:55）却未被使用 | 严重 |
| 2 | i18n | `listener/ScenarioSelectListener.java:74-85` | 玩家可见整句硬编码（3 条中英三元）：场景已开启 / 操作说明 / 退出提示。键 `dialogue.started` / `dialogue.instructions` / `dialogue.return-menu` 均已存在但被绕过 | 严重 |
| 3 | i18n | `listener/ScenarioSelectListener.java:90-91` | 硬编码 AI 标签 `"&b[English AI] &f"`，键 `dialogue.ai-label` 已存在（zh.yml:52 / en.yml:52） | 一般 |
| 4 | i18n | `listener/ScenarioSelectListener.java:120-124` | 玩家可见整句硬编码 + 中英三元：`/lea` 刷新 API 结果两条消息。键 `dialogue.api-reload` / `api-configured` / `api-not-configured` 已存在（zh.yml:70-72 / en.yml:70-72）却未被使用 | 一般 |
| 5 | i18n | `listener/ScenarioSelectListener.java:128-138` | `formatScenarioName()` 用 `switch` + `zh ? "机场" : "Airport"` 硬编码 6 个场景名（中英三元），玩家可见；场景名本应从 `ai_scenarios.yml` 的 `display_name` / `display_name_zh` 取（`AIChatManager.ScenarioConfig.getDisplayName(boolean)` 已提供） | 一般 |
| 6 | i18n | `expansion/LLEPapiExpansion.java:113` | `return "zh".equalsIgnoreCase(lang) ? "未上榜" : "Unranked";` —— 与任务明示的三元模式**完全一致**，`%lle_rank%` 占位符输出玩家可见 | 一般 |
| 7 | i18n | `core/achievement/Achievement.java:186,190` | `getDisplayName()` / `getDisplayDesc()` 用 `"zh".equalsIgnoreCase(language) ? zhName : enName` 选择**枚举内硬编码**的双语名/描述（zh/en 字面量硬编码在 Java，非 messages） | 建议 |
| 8 | i18n | `core/manager/AdminCommandHandler.java:270-271,274` | 管理端可见整句硬编码英文：`"... and " + N + " more errors"` / `"  - " + error`。键 `wordbank.validation-failed` 只覆盖表头，逐条错误行未走 i18n | 建议 |
| 9 | i18n | `core/manager/AdminCommandHandler.java:247,285` | 与上一轮「问题 5」同型：`wordbank.import-failed` 的 `{reason}` 注入硬编码英文（`"File not found: " + fileName` / `"No words list found"`），管理端可见 | 建议 |
| 10 | i18n | `core/UpdateChecker.java:151-170` | 管理端（OP / `letslearnenglish.admin`）可见整句硬编码英文：更新可用横幅 7 行 + `"&aUpdate notification dismissed..."`，未走 messages | 建议 |

### 说明

- 问题 1~5 是本次复测的**核心残留**：`ScenarioSelectListener` 整块绕开 i18n，且**对应的 i18n 键早已存在**（`dialogue.*`），属「键存在但玩家可见文本仍是硬编码」，与上一轮问题 5/6 的形态相同（当时判 `一般`）。
- 问题 6/7 属任务明示的「中英三元表达式」模式；问题 6（`LLEPapiExpansion`）与任务示例字符串逐字一致。
- 问题 8~10 为管理端可见文本，按规则同属玩家/用户可见范畴；严重程度较低。
- 本批次（修复轮）**未引入**新违规，问题 1~10 均为**存量**（`ScenarioSelectListener` 自 GUIMenuListener 拆分以来从未接入 i18n；`LLEPapiExpansion` / `Achievement` 为初版设计）。但因复测标准明确要求核查「全项目」整句硬编码与中英三元，**这些存量项仍计入维度 2 判定**。

---

## 上一轮 7 项问题的逐项复测结论

| 上一轮 # | 上一轮位置 | 复测结论 | 证据 |
|---|---|---|---|
| 1 | `gui/LeaderboardGUI.java` 15 处 `getString(key, "硬编码兜底")` | **已修复** | 全文件 `getString` 均无 fallback 字面量（`:83,87,91,101,127,130,133,140,150,157,162,175,176` 等）；`addTabButton(...)` 的 `String fallback` 形参已从签名移除（现签名 `defaultSlot, tabIndex, configKey, defaultMaterial, messageKey`，`:167-169`） |
| 2 | `gui/ChatHistoryGUI.java` 21 处 fallback | **已修复** | 全文件 `getString` 无 fallback 字面量（`:101,116,117,133,134,153,161,164,168,172,175,191,192,202,203,212,234,249,250,285,289,293`）；仅 `setDisplayName(" ")` 为装饰性物品名（豁免） |
| 3 | `gui/WrongBookGUI.java` 16 处 fallback（含 2 处硬编码中文 `"&e返回主菜单"` / `"&7回到主菜单界面"`） | **已修复** | `:177,179` 现为 `messages.getString("gui.back-button")` / `messages.getString("gui.back-lore")`，无 fallback；全文件无 fallback 字面量 |
| 4 | `listener/WrongBookListener.java` 3 处 fallback | **已修复** | `:101,124,129` 现为 `messages.getString("wrong-book.removed"/"practice-start"/"practice-cancel-hint").replace(...)`，无 fallback |
| 5 | `command/LearnCommand.java` 7 处 `general.error` 注入硬编码英文 | **已修复** | 现改为内层键 + `MessageUtil` 解析：`:157 error.scenario-unavailable`、`:228 error.player-not-found`、`:278 error.duel-unavailable`、`:316 error.checkin-unavailable`、`:353 error.usage-save-scenario`、`:395 error.usage-load-scenario`、`:449 error.scenario-save-failed`；7 个键在 zh.yml:18-25 / en.yml:18-25 均存在 |
| 6 | `command/LearnCommand.java:196` `word.session-start` 的 `{difficulty}` 塞中文 `"复习"` | **已修复** | `:197` 现为 `plugin.getMessageUtil().getPlayerMessage(player, "word.mode-review")`；zh.yml:38 `mode-review: "复习"` / en.yml:38 `mode-review: "Review"` |
| 7 | `gui/ChatHistoryGUI.java` `"&7&oSessionID: ..."` 内部标记悬停可见 | **已修复** | 已改 `PersistentDataContainer`：`ChatHistoryGUI` 定义 `SESSION_ID_KEY`/`RETURN_PAGE_KEY`（`:52-63`），会话身份写 PDC（`setSessionId` `:344-350`），页码写 PDC（`setPageIndex` `:353-359`），详情页标记物品 displayName 为 `" "`（`:255-267`）；`ChatHistoryListener` 侧同步改读 PDC（`readSessionId`/`readPage`/`readPdc` `:104-122`），**已删除全部 `stripColor(...).startsWith(...)` 文本解析** |

**7 项全部已修复。**

---

## 通过项（维度 2 的 fallback 规则 + 维度 1/3/4 + 附加核查项）

### 维度 2 — `getString(key, "硬编码兜底")` fallback 规则：PASS

全项目按 `(messages|msgs|msg|messageConfig)\.getString\("[^"]+", *"` 扫描（含多行写法），**仅命中 4 处，全部为空串豁免**：

| 位置 | 调用 | 判定 |
|---|---|---|
| `gui/LearningCalendarGUI.java:75` | `msg.getString("calendar.title", "")` | 空串豁免 |
| `gui/LearningCalendarGUI.java:198` | `msg.getString("calendar." + key, "")` | 空串豁免 |
| `gui/ProfileGUI.java:70` | `messages.getString("main-menu.calendar", "")` | 空串豁免 |
| `gui/ProfileGUI.java:72` | `messages.getString("main-menu.calendar-lore", "")` | 空串豁免 |

其余带第二参数的 `getString` 全部为**配置文件默认值**（豁免项），逐条核对：

| 位置 | 判定 |
|---|---|
| `LetsLearnEnglish.java:309-310`（`getMainConfig().getString("daily-reminder.message", ...)`）、`:327`、`:358`、`:364`、`:538`、`:555` | `getMainConfig()` / `player_lang.yml`，配置默认值 —— 豁免 |
| `config/ConfigManager.java:57` | `getMainConfig()` —— 豁免 |
| `data/DatabaseManager.java:48,49,70,75,77,78,79` | 数据库配置默认值 —— 豁免 |
| `util/MessageUtil.java:40` | `messageConfig.getString("prefix", DEFAULT_PREFIX)` —— 第二参为**常量标识符**（非字面量），且为 prefix 兜底常量；不在本次扫描命中内，沿用上一轮判定 —— 非违规 |
| `core/achievement/AchievementManager.java:171` | `achievement-system.unlock-sound` 音效名配置 —— 豁免 |
| `core/ai/TtsManager.java:47` | TTS 配置 —— 豁免 |
| `core/ai/AIChatManager.java:145,153,154,209,210,211,229,230,232,236` | `ai_config.yml` / `ai_scenarios.yml` 配置（`""`/`PAPER`/键名兜底） —— 豁免 |
| `core/ProxyMessenger.java:166` | `getMainConfig()` —— 豁免 |
| `core/manager/StatisticsHttpServer.java:54,56,57` | 统计服务配置 —— 豁免 |
| `core/SoundManager.java:47` | `gui.sounds.*` 配置，第二参为变量 `defaultSound` —— 豁免 |
| `core/word/WordBank.java:26` | `words/*.yml` 的 `description`，`""` —— 豁免 |

结论：**`getString(key, "硬编码兜底")` 的 fallback 字面量已全项目清零**（上一轮 55+ 处 → 0），符合豁免规则。

### 维度 2 — zh.yml / en.yml 键集与占位符一一对应：PASS

- 两文件**键路径有序列表完全一致**（逐行比对 `zh.yml` 与 `en.yml` 的嵌套键名与顺序，1:1 无差异；嵌套键总数一致）。
- 占位符**多重集完全一致**：对两文件抽取全部 `{xxx}` 占位符，列表逐条相同（`{prefix}` / `{message}` / `{difficulty}` / `{mode}` / `{accuracy}` / `{score}` / `{answer}` / `{word}` / `{mastery}` / `{count}` / `{scenario}` / `{seconds}` / `{achievement}` / `{player}` / `{title}` / `{name}` / `{description}` / `{current}` / `{target}` / `{time}` / `{rank}` / `{page}` / `{amount}` / `{file}` / `{list}` / `{reason}` / `{imported}` / `{skipped}` / `{errors}` / `{streak}` / `{coins}` / `{code}` / `{length}` / `{max}` / `{opponent}` / `{score1}` / `{score2}` / `{progress}` / `{reward}` / `{completed}` / `{total}` / `{correct}` / `{incorrect}` / `{words}` / `{age}` / `{sessions}` / `{unlocked}` / `{combo}` / `{multiplier}` / `{date}` / `{msg}` / `{scene}` / `{version}` / `{server_version}` / `{supported}` / `{db_type}`）。
- 上一轮问题 5/6 涉及的 8 个新键（`error.*` 7 个 + `word.mode-review`）在两边均存在。

### 维度 2 — 其余核对项

| 核查项 | 结论 |
|---|---|
| `LearnCommand` 是否已改内层键 + `MessageUtil` 解析 | **PASS**（见逐项复测 #5/#6） |
| 内部标记是否已改 `PersistentDataContainer`，且 listener 同步、无 `stripColor(...).startsWith(...)` 式文本解析 | **PASS** —— `ChatHistoryGUI`/`ChatHistoryListener` 均走 PDC；全项目 `stripColor` 仅剩 2 处：`LeaderboardGUI.java:102`（把副标签去色后拼进标题，非内部数据协议）、`WrongBookListener.java:174`（从展示名提取**单词本身**，非内部标记，且无 `.startsWith(...)`） |

### 维度 1 — 配置读取路径：PASS

- 全项目 `plugin.getConfig()` 仅出现于 `config/ConfigManager.java:44`（配置管理器自身，合法入口）；其余一律经 `ConfigManager.getMainConfig()` / `getMessageConfig(lang)` / `getGuiSlot(...)` / `getGuiMaterial(...)`。
- `ScenarioSelectListener` 本类虽在 i18n 上违规，但**未**绕过配置读取路径（槽位判定用硬编码 `slot == 8` / `slot == 26` / `18..20`，属槽位魔法数字，非配置路径绕过）。
- `new File(getDataFolder(), ...)` 仅出现在 `LetsLearnEnglish.java:365,371,547,564`（DB 文件 / 备份目录 / `player_lang.yml`）与 `AIChatManager.loadConfig()`（`ai_config.yml`，独立 API Key 文件，设计如此），非 config.yml 绕过。

### 维度 3 — 线程安全：PASS

- `AIChatManager`：9 个配置字段（`apiUrl`/`apiKey`/`model`/`maxContextTokens`/`timeoutSeconds`/`rateLimitSeconds`/`maxInputLength`/`maxPromptLength`/`debug`）均 `volatile`（`:57-65`）；`conversationContexts` 的 `ArrayDeque` 值在主线程（`:522-526`）与 `supplyAsync` 异步线程（`:549-552`）的读写**均 `synchronized (context)` 包裹**，请求体用 `new ArrayDeque<>(context)` 快照（`:525`）；`pendingRequests` 为 `ConcurrentHashMap.newKeySet()`（`:68`）。
- `TtsManager.apiKey`（`:34,47`）`volatile`；`SoundManager.soundCache` 为 `ConcurrentHashMap`（`:23`）、`enabled` 为 `volatile`（`:24`）；`ChatAnswerListener.cachedOnlinePlayers` / `playersCacheTimestamp` `volatile`（`:23-24`）；`StatisticsHttpServer.running` `volatile`（`:36`）。
- 异步线程未触碰 Bukkit API：`AIChatManager` 的 `supplyAsync` 分支只做 HTTP + `DatabaseManager` 写入，回调经 `getScheduler().runTask` 切回主线程（`:567-570` / `:575-577`）。

### 维度 4 — 资源清理：PASS

- `PlayerDataStore.getRankedPlayers()`（`:746-782`）与 `getRankedPlayersForPeriod()`（`:802-843`）：`PreparedStatement` + `ResultSet` 均在 try-with-resources 内；共享 `Connection` 未关闭，符合设计。
- `StatisticsHttpServer.stop()`（`:94`）：`executor.shutdownNow()`；`executor` 为唯一 `ExecutorService`（`:37,66`）。

### 附加核查项

| 核查项 | 结论 |
|--------|------|
| `ChatHistoryGUI.SESSION_SLOTS` 升序 | **PASS** —— `{9,10,11,12,13,14,15,16,17,18}` 严格升序（`:72-74`），`Arrays.binarySearch` 有效（`ChatHistoryListener.java:91`） |
| `WrongBookGUI.WORD_SLOTS` 升序且与 `PREV_SLOT=47`/`NEXT_SLOT=51`/`PAGE_INDICATOR_SLOT=49`/`BACK_SLOT=8` 无交集 | **PASS** —— `{9..44, 45, 46, 48, 50, 52, 53}` 严格升序（`:49-55`）；不含 8/47/49/51；`ITEMS_PER_PAGE = WORD_SLOTS.length = 42`（`:58`） |
| `LeaderboardGUI.ENTRY_SLOTS` 不含 `BACK_SLOT=8` | **PASS** —— `{10,16,19,25,28,34,37,43,46,52}` 不含 8（`:35-41`）；`BACK_SLOT = 8`（`:48`） |
| `getRankedPlayers()` / `getRankedPlayersForPeriod()` 无 `getPlayerName(...)` 调用 | **PASS** —— 两方法体内 0 处调用；`getPlayerName` 仅剩方法定义（`:723`）与注释 |
| `getRankedPlayersForPeriod()` 的 `Object[]` 下标与 SELECT 列序一致 | **PASS** —— SELECT `uuid, player_name, period_score, total_correct, total_words` ↔ `row[0..4]` 收集顺序完全相同（`:812-818`）；读取 `row[0]`uuid / `row[1]`name / `row[2]`periodScore(Long) / `row[3]`periodCorrect(Integer) / `row[4]`periodWords(Integer) 与写入类型一一对应（`:830-837`） |
| `SocialListener` / `WordLearningListener` 槽位是否读 `getGuiSlot(...)` | **PASS** —— `SocialListener.java:108-114,258-261`（english-square / leaderboard 各槽）；`WordLearningListener.java:85-89`（word-learning 的 pronunciation/back/stop/start）。注：`SocialListener` 的 profile/achievement-wall 槽位（`:56-64`）为常量 + 注释说明 GUI 侧未读配置，属一致写法 |
| `messages/*.yml` 的 `config-version` 与 `ConfigManager.MESSAGE_CONFIG_VERSION` 一致 | **PASS** —— `zh.yml:6` = 6、`en.yml:6` = 6、`ConfigManager.java:25` `MESSAGE_CONFIG_VERSION = 6` |

---

## 小结

- **上一轮 7 项 i18n 违规：全部已修复**（fallback 字面量清零、`LearnCommand` 内层键、`ChatHistoryGUI` 改 PDC）。
- **维度 1（配置路径）/ 维度 3（线程安全）/ 维度 4（资源清理）：零违规。**
- **附加核查项 7 项：全部通过。**
- **维度 2（i18n 覆盖）：仍违规 10 项** —— 核心残留是 `listener/ScenarioSelectListener.java`（5 组，整句硬编码 + 中英三元，且对应 `dialogue.*` 键早已存在却未被使用）；另有 `LLEPapiExpansion`（与任务示例逐字一致的三元）、`Achievement`、`AdminCommandHandler`、`UpdateChecker` 同类命中。这些均为**存量**（非修复轮引入），但按复测标准（全项目核查整句硬编码 / 中英三元）计入判定。
- **总体判定：FAIL**（任一维度违规即 FAIL）。
