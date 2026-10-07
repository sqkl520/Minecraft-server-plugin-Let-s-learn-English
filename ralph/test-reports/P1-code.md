# 代码质量报告 P1（并行缺陷修复批次）

### 判定：FAIL

> 审查对象：本轮并行缺陷修复批次（GUI / Listener / Core / Command / 资源），约 25 个文件。
> 审查方式：只读源码 + 静态引用核对，未修改任何代码文件。
> 结论：**维度 1（配置读取路径）/ 维度 3（线程安全）/ 维度 4（资源清理）零违规**；**维度 2（i18n 覆盖）违规** —— 本批次 CHANGELOG 声称「全项目补齐 fallback 参数清理」（CHANGELOG.md:51），但**本批次改动的 GUI/Listener 文件仍大面积保留 `getString(key, "硬编码兜底")` fallback 参数**，且部分 fallback 为硬编码中文（英文玩家可见）。按既定规则（`getString(key, "硬编码兜底")` 的 fallback 参数即违规），判定 FAIL。

---

## 问题表格

| # | 维度 | 位置 | 原因 | 严重程度 |
|---|------|------|------|----------|
| 1 | i18n | `gui/LeaderboardGUI.java:83,87,91,101,107,109,111,127,131,135,152,159,164,177,178-179` | 15 处 `messages.getString(key, "硬编码兜底")`。fallback 含中英混合硬编码（如 `"&e单词榜 / Words"`、`"&6&l排行榜 / Leaderboard"`、`"Back to Menu"`、`"&aSelected"`）。`addTabButton(..., String fallback)` 更是把 fallback 当形参一路透传 | 严重 |
| 2 | i18n | `gui/ChatHistoryGUI.java:82,97,98,114,115,142,145,149,153,162,172,173,181,182,189,208,223,224,249,253,257` | 21 处 `msg.getString(key, "硬编码英文")` fallback（`"&6&lAI Chat History"`、`"&c&lBack to Menu"`、`"&e← Previous Page"`、`"&a[You]"` 等）。玩家可见标题/按钮/lore 全部带硬编码兜底 | 严重 |
| 3 | i18n | `gui/WrongBookGUI.java:71,103,104,129,133,136,140,144,146,157,159,168,178,180,186,188` | 16 处 fallback；其中 `:178` = `"&e返回主菜单"`、`:180` = `"&7回到主菜单界面"` 是**硬编码中文**（`gui.back-button`/`gui.back-lore` 在 en.yml 有对应键），英文玩家在 fallback 路径下会看到中文 | 严重 |
| 4 | i18n | `listener/WrongBookListener.java:101-102,125-126,131-132` | 3 处 `messages.getString(key, "硬编码英文")` fallback（`"&a'{word}' has been removed..."`、`"&6★ Practice Mode: ..."`、`"&7(Type &ecancel &7to stop)"`），直接 `player.sendMessage` 给玩家 | 严重 |
| 5 | i18n | `command/LearnCommand.java:156,224,273,310,346,387,440` | 走 `general.error` 键，但把**硬编码英文**作为 `{message}` 占位符值注入（`"Chat scenario is not available."` / `"Player not found"` / `"Duel system is not available."` / `"Check-in system is not available."` / `"Usage: /le save_scenario <name>"` / `"Usage: /le load_scenario <code>"` / `"Failed to save scenario file."`）。键存在但玩家可见文本仍是硬编码英文，中英玩家都只看得到英文 | 一般 |
| 6 | i18n | `command/LearnCommand.java:196` | `word.session-start` 的 `{difficulty}`/`{mode}` 占位符值写死中文 `"复习"`，英文玩家会看到「Difficulty: 复习, Mode: 复习」 | 一般 |
| 7 | i18n | `gui/ChatHistoryGUI.java:159,229-231` | 内部标记 `"&7&oSessionID: ..."` 与 `"&7&oReturnPage: ..."` / `"&7SessionID: ..."` 直接渲染进物品 lore/displayName，悬停即玩家可见，属硬编码英文文本（非装饰性物品名、非空串） | 建议 |

### 说明

- 问题 1~4 均为**本批次改动文件**（`LeaderboardGUI` 改 BACK_SLOT、`ChatHistoryGUI` 改 SESSION_SLOTS、`WrongBookGUI` 改 WORD_SLOTS/占位、`WrongBookListener` 改槽位判定），fallback 未随本批次清理，与 CHANGELOG.md:51「全项目补齐 fallback 参数清理……共去除 50 余处」的声称不符。
- 本批次确实已清理 fallback 的文件：`listener/ChatAnswerListener.java`、`core/word/WordLearningManager.java`、`gui/ProfileGUI.java`（仅剩 `getString(key, "")` 空串豁免）—— 这三个文件符合规则。
- 问题 5/6 为 `LearnCommand`（本批次改动文件：help 补 3 命令 + Tab 重写），键本身存在，但占位符值硬编码，仍属玩家可见硬编码文本。

---

## 通过项（本批次专项核查）

### 维度 1 — 配置读取路径：PASS

- 全项目仅 `config/ConfigManager.java:44` 出现 `plugin.getConfig()`（配置管理器自身，属合法入口）；其余一律经 `ConfigManager.getMainConfig()` / `getMessageConfig(lang)`。
- 本批次改动文件核对：`AdminCommand.java:163`、`TtsManager.java:44-47`、`SoundManager.java:34,47`、`HotReloadManager.java`（经 `configManager`）全部走 `ConfigManager`，无硬编码配置路径。
- 唯一直接 `new File(getDataFolder(), "ai_config.yml")` 的是 `AIChatManager.loadConfig()`（独立 API Key 文件，设计如此，非 config.yml 绕过）。

### 维度 3 — 线程安全：PASS

- `AIChatManager`：9 个配置字段（`apiKey`/`apiUrl`/`model`/`maxContextTokens`/`timeoutSeconds`/`rateLimitSeconds`/`maxInputLength`/`maxPromptLength`/`debug`）均已 `volatile`（:57-65）；`conversationContexts` 的 `ArrayDeque` 值在主线程与 `supplyAsync` 异步线程的读写**均已 `synchronized (context)` 包裹**（:522-526 与 :549-552），请求体用 `new ArrayDeque<>(context)` 快照下发，无并发修改。
- `TtsManager.apiKey`（:35）已 `volatile`。
- `SoundManager`：`soundCache` 改 `ConcurrentHashMap`（:23）、`enabled` 加 `volatile`（:24）。
- `ChatAnswerListener`：`cachedOnlinePlayers` / `playersCacheTimestamp` 均 `volatile`（:23-24）。
- 异步线程未触碰 Bukkit API：`AIChatManager` 的 `supplyAsync` 分支内只做 HTTP + `DatabaseManager` 写入，回调经 `getScheduler().runTask` 切回主线程（:567-579）。

### 维度 4 — 资源清理：PASS

- `PlayerDataStore.getRankedPlayers()`（:750-761）与 `getRankedPlayersForPeriod()`（:806-820）：`PreparedStatement` + `ResultSet` 均在 try-with-resources 内；`Connection` 为共享单连接，未关闭，符合设计。
- `StatisticsHttpServer.stop()`（:90-100）：`server.stop(0)` + `executor.shutdownNow()`，`executor` 置 null，`running=false`。

### 附加核查项

| 核查项 | 结论 |
|--------|------|
| `getRankedPlayersForPeriod` 新 SQL 的 `Object[]` 下标与 SELECT 列序一致 | **PASS** —— SELECT `uuid, player_name, period_score, total_correct, total_words` ↔ `row[0..4]` 收集顺序完全相同（:812-818）；读取 `row[0]=uuid` / `row[1]=name` / `row[2]=periodScore(Long)` / `row[3]=periodCorrect(Integer)` / `row[4]=periodWords(Integer)` 与写入类型一一对应（:830-837） |
| `getRankedPlayers` / `getRankedPlayersForPeriod` 残留 `getPlayerName(...)` 调用 | **PASS** —— 两方法体内 0 处调用；`getPlayerName` 仅剩方法定义（:723）与 `Leaderboard.getTopPlayers` ALL_PLAYERS 分支（非本方法） |
| `ChatHistoryGUI.SESSION_SLOTS` 升序 | **PASS** —— `{9,10,11,12,13,14,15,16,17,18}` 严格升序（:52-54），`Arrays.binarySearch` 有效 |
| `WrongBookGUI.WORD_SLOTS` 升序 | **PASS** —— `9..44, 45, 46, 48, 50, 52, 53` 严格升序（:49-55），`Arrays.binarySearch` 有效 |
| `WORD_SLOTS` 与 `PREV_SLOT=47`/`NEXT_SLOT=51`/`PAGE_INDICATOR_SLOT=49`/`BACK_SLOT=8` 交集 | **PASS** —— 无交集（WORD_SLOTS 不含 8/47/49/51）；`ITEMS_PER_PAGE = WORD_SLOTS.length = 42` 由数组推导（:58） |
| `LeaderboardGUI.ENTRY_SLOTS` 是否含 `BACK_SLOT=8` | **PASS** —— ENTRY_SLOTS = `{10,16,19,25,28,34,37,43,46,52}`，不含 8（:35-41）；`BACK_SLOT` 已统一为 8 并提为 `public static final`（:48） |
| `zh.yml` / `en.yml` 键集一一对应 | **PASS** —— 两文件嵌套键均为 378 个，逐行比对键名与顺序完全一致（`config-version: 5` 同步） |
| 本批次新增/改名的键在两边均存在 | **PASS** —— `progress.money-reward`/`money-session-bonus`/`money-review-reward`/`money-review-penalty`、`profile.settings-locked`/`settings-locked-lore`、`help.leaderboard`/`help.challenge`、`admin-help.ignore-update`/`broadcast`、`admin.usage-*`/`available-difficulties`/`importwordbank-hint`/`player-only`/`proxy-unavailable`/`usage-broadcast`/`broadcast-hint`/`broadcast-sent`/`broadcast-prefix`、`language.cancel-item`/`cancel-lore`、`word-learning-gui.correct`/`incorrect`/`combo-message`/`timeout`/`countdown-normal`、`wrong-book.practice-cancelled` 在两文件均存在 |
| 改动文件引用的 i18n 键是否均存在于两 yml | **PASS** —— 逐个核对 `LeaderboardGUI` / `ChatHistoryGUI` / `WrongBookGUI` / `ConfirmationGUI` / `ProfileGUI` / `SocialListener` / `ChatHistoryListener` / `WrongBookListener` / `DuelGUIListener` / `MainMenuListener` / `ChatAnswerListener` / `AIChatManager` / `RewardEngine` / `AdminCommand` / `LearnCommand` 的全部 `getString` / `getPlayerMessage` / `getSenderMessage` 键，**无缺失键**（问题 5/6 是占位符值硬编码，非键缺失） |
| `ConfirmationGUI` 是否已去 fallback | **PASS** —— `:79,81,83,85,87` 全部 `getString(key)` 无 fallback 参数 |
| `ProfileGUI` fallback 状态 | **PASS** —— 仅 `:70,72` 为 `getString(key, "")` 空串（豁免） |

---

## 小结

- **维度 1 / 3 / 4：零违规。**
- **维度 2（i18n）：违规 7 项** —— 核心是本批次改动文件仍保留大量 `getString(key, "硬编码兜底")` fallback 参数（问题 1~4），与 CHANGELOG「全项目补齐 fallback 参数清理」的声称矛盾；另有 `LearnCommand` 占位符值硬编码（问题 5/6）。
- **附加核查项 10 项全部通过。**
- **总体判定：FAIL**（任一维度违规即 FAIL）。
