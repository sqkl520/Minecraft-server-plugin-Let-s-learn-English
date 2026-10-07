# 代码质量报告 P1（第三轮复测 · 终验）

### 判定：PASS

> 审查对象：v1.0.7 缺陷修复批次中 **上一轮 FAIL 的 10 项** + 编排者本轮额外修复的 `LearnCommand` 场景名一处。
> 审查方式：**只读源码 + 静态引用核对**，未修改任何代码文件（`Write` 仅用于本报告）。
> 判定口径：依 `ralph/accepted-deviations.md`（审查范围 = 本批次改动过的文件 + 项目规则明列的维度；已登记存量偏差不计入判定）。
>
> **结论**：上一轮 10 项中，**9 项已修复**（1~6、8、9、10），**1 项（#7）已登记豁免**（A1）。
> 编排者本轮额外修复的 `LearnCommand:173-175` 场景名走 `ai_scenarios.yml`，**改动正确、有 null 兜底、与 `ScenarioSelectListener` 同类逻辑一致**。
> **本批次 i18n 违规已清零，剩余项为已登记的存量偏差。**

---

## 一、上一轮 10 项逐项复测结论

| 上轮 # | 位置 | 期望状态 | 复测结论 | 证据 |
|---|---|---|---|---|
| 1 | `listener/ScenarioSelectListener.java:56-58` AI 未配置 | 走 `dialogue.ai-not-configured` | **已修复** | `:54-55` `player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "dialogue.ai-not-configured"))`；无中英三元。键 zh.yml:58 / en.yml:58 |
| 2 | 同文件 `:74-85` 三条消息 | 走 `started-with-difficulty` / `chat-hint` / `exit-hint` | **已修复** | `:71-78` 三次 `getPlayerMessage`，占位符 `"scenario"` / `"difficulty"`；键 zh.yml:48-50 / en.yml:48-50 |
| 3 | 同文件 `:90-91` AI 标签 | 走 `dialogue.ai-label` | **已修复** | `:83` `String aiLabel = ...getPlayerMessage(player, "dialogue.ai-label")`，`:84` `sendMessage(aiLabel + aiResponse)`；键 zh.yml:55 / en.yml:55 |
| 4 | 同文件 `:120-124` `/lea` 刷新结果 | 走 `api-reload-success` / `api-reload-failed` | **已修复** | `:110-113` `resultKey` 三元只选**键名**（不是文案），一次性 `getPlayerMessage(player, resultKey)`；键 zh.yml:76-77 / en.yml:76-77 |
| 5 | 同文件 `:128-138` `formatScenarioName` switch | 改查 `ScenarioConfig.getDisplayName(zh)` | **已修复** | `:120-127` 全 switch 已删除，改 `getScenarioConfigs().get(key)` + `sc != null` 兜底 + `sc.getDisplayName(zh)`；兜底值 `key` 为标识符（非面向玩家文案），有 javadoc 说明 |
| 6 | `expansion/LLEPapiExpansion.java:113` 未上榜三元 | 走 `placeholder.rank-unranked` | **已修复** | `:110-116` `getUnrankedText()`：在线 → `getPlayerMessage(player.getPlayer(), "placeholder.rank-unranked")`，离线 → `getMessage("placeholder.rank-unranked")`；键 zh.yml:155 / en.yml:155。三元表达式已完全消除 |
| 7 | `core/achievement/Achievement.java:186,190` | 已登记 A1 | **已登记豁免（不计入判定）** | `accepted-deviations.md` A1；代码未动，`"zh".equalsIgnoreCase(language) ? zhName : enName` 仍在（`:186,190`），属设计决策 |
| 8 | `core/manager/AdminCommandHandler.java:270-271,274` 校验错误逐条 | 走 `validation-more-errors` / `validation-error-line` | **已修复** | `:272-274` `getSenderMessage(sender, "wordbank.validation-more-errors", "count", ...)`；`:278-279` `getSenderMessage(sender, "wordbank.validation-error-line", "error", error)`；键 zh.yml:151-152 / en.yml:151-152 |
| 9 | 同文件 `{reason}` 注入硬编码 | 走 `wordbank.reason-*` | **已修复（实测确为 4 处，与 agent 报称一致）** | `:153-154` `reason-unknown-difficulty`（占位 `{difficulty}`）、`:210-211` `reason-io-error`（`{error}`）、`:248-249` `reason-file-not-found`（`{file}`）、`:290-291` `reason-no-words-list`；4 个键 zh.yml:147-150 / en.yml:147-150 |
| 10 | `core/UpdateChecker.java:151-170,174-176` 更新横幅 | 走 `update.*` 7 键 | **已修复** | `:152-160` 六条 + `:166` `update.dismissed`，共 **7 个键**：`update.header` / `current` / `latest` / `download` / `dismiss-hint` / `footer` / `dismissed`（zh.yml:157-164 / en.yml:157-164）。`:151` / `:161` 的 `sendMessage("")` 为空行视觉分隔，非文案 |

**汇总：已修复 9 项，已登记豁免 1 项，仍未修复 0 项。**

---

## 二、本轮新增改动复核：`command/LearnCommand.java:166-179`

**改动内容**：`/le dialogue <场景>` 路径原先把原始 key（`airport`）塞进 `dialogue.started` 的 `{scenario}`，现改为查 `ai_scenarios.yml` 的展示名。

```java
AIChatManager.ScenarioConfig sc = plugin.getAIChatManager().getScenarioConfigs().get(scenario);
boolean zh = "zh".equalsIgnoreCase(plugin.getPlayerLanguage(player));
String scenarioName = (sc != null) ? sc.getDisplayName(zh) : scenario;
player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
        "dialogue.started", "scenario", scenarioName));
```

| 核查点 | 结论 |
|---|---|
| 改动正确性 | **PASS** —— `{scenario}` 现取 `display_name` / `display_name_zh`，与 `/le dialogue` 玩家输入 key（`args[1].toLowerCase()`）解耦，玩家不再看到英文标识符 |
| **NPE 风险** | **无** —— `getScenarioConfigs()`（`AIChatManager:649`）直接返回 `final` 字段 `scenarios`（`LinkedHashMap`，构造时 `= new LinkedHashMap<>()`，`loadScenariosConfig()` 只 `clear()` 不置 null），永不为 null；`.get(key)` 可能返回 null，已由 `(sc != null)` 三元兜底为 `scenario`（标识符字符串，非空） |
| `getDisplayName(zh)` 是否可能返回 null | **不会** —— `ScenarioConfig.displayName` 来自 `sec.getString("display_name", key)`（`:229`）必非 null；`displayNameZh` 来自 `sec.getString("display_name_zh", sc.displayName)`（`:230`）同样有兜底 |
| 与 `ScenarioSelectListener` 一致性 | **一致** —— 两侧均为「查 key（无则用 key）→ `getDisplayName(zh)`」，同用 `"zh".equalsIgnoreCase(lang)` 判语言；`AIChatManager.buildSystemPrompt():376-378` 是同一写法的第三处，三处收敛同一模式 |
| 语言判定来源 | **PASS** —— `plugin.getPlayerLanguage(player)`（`LetsLearnEnglish:536`），与监听器同一入口 |

---

## 三、四个维度判定

### 维度 2 — i18n 覆盖：**PASS（本批次改动范围内零违规）**

**（a）`getString(key, "硬编码兜底")` fallback 字面量 —— 已清零**

全项目按 `(messages|msgs|msg|messageConfig)\.getString\("[^"]+", *"` 扫描，**仅命中 3 处，全部为空串豁免**：

| 位置 | 调用 | 判定 |
|---|---|---|
| `gui/LearningCalendarGUI.java:75` | `msg.getString("calendar.title", "")` | 空串豁免 |
| `gui/ProfileGUI.java:70` | `messages.getString("main-menu.calendar", "")` | 空串豁免 |
| `gui/ProfileGUI.java:72` | `messages.getString("main-menu.calendar-lore", "")` | 空串豁免 |

与任务给定的「应只剩 3 处」完全吻合。其余带第二参数的 `getString` 逐条核对后**全部为配置文件默认值（豁免项）**：`LetsLearnEnglish:309-310,327,358,364,538,555`、`ConfigManager:57`、`DatabaseManager:48,49,70,75,77,78`、`MessageUtil:54,84`（`general.language` 默认值）、`AchievementManager:171`（音效名）、`ProxyMessenger:166`、`AIChatManager:232`（Material 名）、`StatisticsHttpServer:54,56,57`、`SoundManager:47`（第二参为变量 `defaultSound`）、`WordBank:26`（`""`）。另 `LearningCalendarGUI:191,198` 的第二参为变量/空串，同样豁免。

**（b）本批次改动过的文件 —— 无未走 messages 的整句硬编码 / 无玩家可见中英三元**

| 文件 | 扫描结果 |
|---|---|
| `listener/ScenarioSelectListener.java` | 剩余字符串字面量仅 3 类：`title.contains("Scenario Select"/"场景选择"/"情景对话")`（GUI 标题匹配启发式，属登记项 B3，非玩家可见文案）、`SoundManager.playSound(player, "menu-click")`（音效 key）、`"letslearnenglish.admin"`（权限节点）、`"zh"`（语言判定）。**零文案字面量** |
| `expansion/LLEPapiExpansion.java` | 全文件中文仅出现在 **javadoc 注释**（`:19`）。玩家可见输出全部走 `getPlayerMessage` / `getMessage` |
| `core/UpdateChecker.java` | 剩余英文全在 `plugin.getLogger()`（控制台）。玩家可见 7 条全走 `update.*` |
| `core/manager/AdminCommandHandler.java` | 剩余英文全在 `plugin.getLogger()`（控制台）。玩家可见全走 `getSenderMessage` |
| `command/LearnCommand.java` | 全部 `sendMessage` 均经 `getPlayerMessage` / `getMessage`（逐行核对 `:55,119,145,148,155,162,176,178,194,203,207,231,247,253,264,269,281,288,303,319,330,334,339,349,356,366,374,382,391,398,409,419,428,452,461,466-483`）。`:418` 的中文是**正则字符类** `[一-龥a-zA-Z0-9_]+`，非文案 |

全项目范围内 `equalsIgnoreCase(...) ? "字面量"` 模式与 `sendMessage("字面量")` 模式**均为零命中**。

**（c）`zh.yml` / `en.yml` 键集、顺序、占位符多重集完全对应 —— PASS**

- **键路径有序列表**：逐行导出两文件的嵌套键名（`^[ ]*[A-Za-z0-9_-]+:`），**完全一致**（含 `main-menu` / `daily` / `daily-challenge` / `word-learning-gui` / `dialogue-gui` / `progress-gui` / `square` / `word-bank` / `calendar` / `profile` / `chat-history` / `wrong-book` 全部尾段）。
- **占位符多重集**：两文件 `\{[a-z_]+\}` 抽取结果**逐条相同**（共 60+ 个不同占位符，`{prefix}` / `{scenario}` / `{difficulty}` / `{mode}` / `{accuracy}` / `{score}` / `{answer}` / `{word}` / `{mastery}` / `{count}` / `{seconds}` / `{achievement}` / `{player}` / `{title}` / `{name}` / `{description}` / `{current}` / `{target}` / `{time}` / `{rank}` / `{page}` / `{amount}` / `{file}` / `{list}` / `{reason}` / `{error}` / `{imported}` / `{skipped}` / `{errors}` / `{streak}` / `{coins}` / `{code}` / `{length}` / `{max}` / `{opponent}` / `{score1}` / `{score2}` / `{progress}` / `{reward}` / `{completed}` / `{total}` / `{correct}` / `{incorrect}` / `{words}` / `{age}` / `{sessions}` / `{unlocked}` / `{combo}` / `{multiplier}` / `{date}` / `{msg}` / `{scene}` / `{version}` / `{server_version}` / `{supported}` / `{db_type}` / `{url}` / `{chinese}` / `{message}`）。

**（d）本轮涉及的全部新键在两个 yml 均存在 —— 逐键核对（17 个）**

| # | 键名 | zh.yml | en.yml |
|---|---|---|---|
| 1 | `dialogue.ai-not-configured` | 58 | 58 |
| 2 | `dialogue.started-with-difficulty` | 48 | 48 |
| 3 | `dialogue.chat-hint` | 49 | 49 |
| 4 | `dialogue.exit-hint` | 50 | 50 |
| 5 | `dialogue.ai-label` | 55 | 55 |
| 6 | `dialogue.api-reload-success` | 76 | 76 |
| 7 | `dialogue.api-reload-failed` | 77 | 77 |
| 8 | `placeholder.rank-unranked` | 155 | 155 |
| 9 | `wordbank.validation-more-errors` | 151 | 151 |
| 10 | `wordbank.validation-error-line` | 152 | 152 |
| 11 | `wordbank.reason-unknown-difficulty` | 147 | 147 |
| 12 | `wordbank.reason-io-error` | 148 | 148 |
| 13 | `wordbank.reason-file-not-found` | 149 | 149 |
| 14 | `wordbank.reason-no-words-list` | 150 | 150 |
| 15 | `update.header` / `current` / `latest` / `download` / `dismiss-hint` / `footer` / `dismissed`（7 键） | 158-164 | 158-164 |

**（e）观察项（不计入判定，已说明理由）**

| 位置 | 现象 | 为何不计入 |
|---|---|---|
| `gui/ChatHistoryGUI.java:297` | `default:` 分支 `rolePrefix = "&7[" + entry.getRole() + "]"`（拼接原始 role 标识符） | 该文件属**上一批次**改动集（PDC 改造），非本批次；且 `insertChatHistory` 的 role 实参仅 `"system"` / `"user"` / `"assistant"`（`AIChatManager:351,531,556`），`default` 为**不可达死分支**；上一轮 r2 已审查该文件并判 PASS，未提出此项。建议下批次随手接入键或改为空串 |
| `core/word/WordBankValidator.java:30-175` | 校验器内部错误文本为硬编码英文（如 `Missing required key 'words'`），经 `{error}` 注入玩家可见行 | 该文件**不在本批次改动集**；`{error}` 属「数据注入」而非「文案拼接」，与 `general.error` 的 `{message}` 同型，且本轮任务把 #8 的期望状态明确为「走 `validation-more-errors` / `validation-error-line`」，已达成 |

### 维度 1 — 配置读取路径：**PASS（回归无变化）**

- 全项目 `plugin.getConfig()` **仅 1 处**：`config/ConfigManager.java:44`（配置管理器自身，合法唯一入口）。四个本批次文件均未出现。
- 槽位/材质一律经 `ConfigManager.getGuiSlot(...)` / `getGuiMaterial(...)`；GUI 参数经 `getMainConfig()`。
- `new File(plugin.getDataFolder(), ...)` 仅用于资源文件（`words/`、`exports/`、`ai_config.yml`、`ai_scenarios.yml`、`player_lang.yml`、DB 文件、备份目录），非 config.yml 绕过。
- 本批次未新增任何字段，无需 GameConfig getter/setter 变更。

### 维度 3 — 线程安全：**PASS（回归无变化）**

- `AIChatManager` 9 个配置字段全部 `volatile`（`:57-65`）；`conversationContexts` 值 `ArrayDeque` 在主线程（`:522-526`）与 `supplyAsync`（`:549-552`）读写**均 `synchronized (context)`**；请求体取快照 `new ArrayDeque<>(context)`（`:525`）；`pendingRequests` = `ConcurrentHashMap.newKeySet()`（`:68`）。
- `UpdateChecker`：`latestVersion` / `updateAvailable` / `checkComplete` 均 `volatile`（`:30-32`），异步线程只写、主线程只读，经 `runTask` 切回主线程后 `notifyOnlineAdmins()`（`:130`）→ `notifyPlayer` 读值。**注**：`dismissedPlayers`（`:33`）为普通 `HashSet`，但仅在主线程读写（`dismiss()` 由命令触发、`notifyPlayer` 由 `runTask` 调度），无跨线程访问，不构成违规。
- `LLEPapiExpansion` 无实例可变状态（仅 `final plugin`），`onRequest` 可在 PAPI 线程调用；其内部只读 `getPlayerMessage` / DB 查询，**未触达 Bukkit API 主线程限制**（`getPlayer()` 仅在 `isOnline()` 判定后取语言字符串；`PlayerDataStore` 全 `ConcurrentHashMap`）。
- `ScenarioSelectListener` / `AdminCommandHandler` 均为事件/命令主线程执行，无共享可变状态。
- 异步分支未触碰 Bukkit API：`AIChatManager` 的 `supplyAsync` 只做 HTTP + DB 写入，回调经 `getScheduler().runTask`（`:570` / `:575`）切回主线程。

### 维度 4 — 资源清理：**PASS（回归无变化）**

- `PlayerJoinListener:37-43` `PlayerQuitEvent` 清理三类 per-player 状态：`playerDataStore.unloadPlayerData`（`:573-585` 逐 cache `remove`，含 `playerStatsCache` / `activeSessionPlayers` / `wordMasteryCache` / `lastReviewTimeCache` / `reviewCountCache` / `correctCountCache` / `masteredWordsCache` / `difficultWordsCache` / `wrongCountCache`）、`achievementManager.unloadPlayer`、`playerDataStore.unloadProfileSettings`。
- `ChatScenarioListener:126-131` 退出时 `activePlayers.remove` + `aiChatManager.endConversation`（`:393-398` 清 5 个 per-player map）。
- `DuelListener:27-32` 退出交 `DuelManager.handleDisconnect`（含 30s 宽限定时器）。
- `UpdateChecker` 无定时器；`checkAsync` 的 `HttpURLConnection` 各分支均 `disconnect()`（`:70,75,87`）。
- `StatisticsHttpServer.stop():90-100` `server.stop(0)` + `executor.shutdownNow()` + 置 null；`UpdateChecker`/`LLEPapiExpansion` 无 `ExecutorService`。
- `PreparedStatement` / `ResultSet` 在 `AdminCommandHandler`（`:59,84`）与 `PlayerDataStore` 排行榜查询内均 try-with-resources；共享 `Connection` 不关闭，符合设计。

---

## 四、附加核查项（与上轮相同，确认未回归）

| 核查项 | 结论 |
|--------|------|
| `ChatHistoryGUI.SESSION_SLOTS` 升序与交集 | **PASS** —— `{9,10,11,12,13,14,15,16,17,18}` 严格升序（`:72-74`），`Arrays.binarySearch`（`ChatHistoryListener:91`）有效 |
| `WrongBookGUI.WORD_SLOTS` 升序且与 `BACK_SLOT`/`PREV_SLOT`/`PAGE_INDICATOR_SLOT`/`NEXT_SLOT` 无交集 | **PASS** —— `{9..44,45,46,48,50,52,53}` 严格升序（`:49-55`）；`BACK_SLOT=8`（`:37`）、`PREV_SLOT=47`（`:36`）、`PAGE_INDICATOR_SLOT=49`（`:39`）、`NEXT_SLOT=51`（`:38`），均不在数组内；`ITEMS_PER_PAGE = WORD_SLOTS.length = 42`（`:58`） |
| `LeaderboardGUI.ENTRY_SLOTS` 不含 `BACK_SLOT=8` | **PASS** —— `{10,16,19,25,28,34,37,43,46,52}`（`:35-41`）不含 8；`BACK_SLOT = 8`（`:48`） |
| `getRankedPlayers*` 无 `getPlayerName` 调用 | **PASS** —— `getPlayerName` 全文件仅 3 处命中：方法定义（`:723`）与 2 条注释（`:768`、`:826`）；两个方法体内 0 处调用 |
| `getRankedPlayersForPeriod()` 的 `Object[]` 下标与 SELECT 列序一致 | **PASS** —— SELECT `uuid, player_name, period_score, total_correct, total_words`（`:794-796`）↔ 收集顺序 `row[0..4]`（`:812-818`）；读取 `row[0]`UUID / `row[1]`name / `row[2]`Long / `row[3]`Integer / `row[4]`Integer（`:830-837`）与类型一一对应 |
| `messages/*.yml` 的 `config-version` 与 `ConfigManager.MESSAGE_CONFIG_VERSION` | **PASS** —— `zh.yml:6` = **6**、`en.yml:6` = **6**、`ConfigManager.java:25` `MESSAGE_CONFIG_VERSION = **6**` |
| `SocialListener` / `WordLearningListener` 槽位读 `getGuiSlot(...)` | **PASS** —— `SocialListener:108-114`（english-square 7 槽）、`:258-261`（leaderboard 4 槽）；`WordLearningListener:85-89`（word-learning 4 槽） |
| `ScenarioSelectListener` 槽位 | 未回归 —— 仍用硬编码 `slot == 8` / `26` / `18..20`（与 `ScenarioSelectGUI` 的 `RELOAD_API_SLOT=26` / `DIFFICULTY_SLOT_START=18` 常量同值），属**槽位魔法数字**而非配置路径绕过，上一轮已判非违规 |

---

## 五、结论

- **上一轮 10 项：9 项确认已修复，1 项（#7 `Achievement`）为已登记豁免（A1），0 项仍未修复。**
- **本轮新增改动（`LearnCommand:173-175`）：正确、无 NPE 风险、与 `ScenarioSelectListener` 及 `AIChatManager.buildSystemPrompt` 逻辑一致。**
- **维度 1（配置路径）/ 维度 3（线程安全）/ 维度 4（资源清理）：零违规。**
- **维度 2（i18n 覆盖）：本批次改动范围内零违规。**
  - `getString(key, "硬编码兜底")` fallback 字面量**全项目清零**（仅剩 3 处空串豁免，与任务给定完全吻合）；
  - 本批次 5 个文件无未走 messages 的整句硬编码，无玩家可见中英三元；
  - `zh.yml` / `en.yml` 键集、顺序、占位符多重集**完全对应**；本轮涉及 17 个键（含 `update.*` 7 键）在两文件均存在。
- **附加核查项 8 项：全部通过，无回归。**

> **本批次 i18n 违规已清零，剩余项为已登记的存量偏差**（`Achievement.java` 双语硬编码 = A1；`core→gui` 反向依赖 / 超长类 / 标题字符串匹配 GUI 识别 / 单连接共享 / 未接线功能 / 死代码 = B1-B7，均不参与判定）。

### **最终判定：PASS**
