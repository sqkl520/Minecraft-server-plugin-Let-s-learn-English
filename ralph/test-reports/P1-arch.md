# 架构报告 P1（并行缺陷修复批次）

### 判定：PASS

> 审查对象：v1.0.7 缺陷修复批次（中文服止血 / 并发收口 / 仓库卫生），共约 15 个文件。
> 审查方式：只读源码 + 静态引用核对，未修改任何代码文件。
> 结论：本批次**未引入**任何四维度违规；列出的问题均为**存量架构债**，按既有标准记警告、不 FAIL。

---

## 问题表格

| # | 维度 | 位置 | 原因 | 严重程度 |
|---|------|------|------|----------|
| W1 | 分层正确性（存量） | `core/SoundManager.java:4` → `gui.GUIUtils`；`core/duel/DuelManager.java:7` → `gui.DuelGUI`；`core/word/WordLearningManager.java:17` → `gui.WordLearningGUI` | `core` 层存在对上层 `gui` 的反向依赖（core → gui）。`SoundManager` 本批次被改动（`soundCache` 改 `ConcurrentHashMap`），但 `GUIUtils` import 为**存量**，非本批次引入。`GUIUtils` 是纯静态工具，真正合适的位置是 `util/` | 警告（存量） |
| W2 | 分层正确性 / 常量漂移 | `gui/LeaderboardGUI.java:160,172` | 本批次把 `BACK_SLOT` / `TAB_*_SLOT` 提为 `public static final` 供 `listener/SocialListener` 引用，但 GUI 实际落位仍走 `cm.getGuiSlot("leaderboard", key, CONST)` —— 监听器只认常量、GUI 认配置。若管理员在 `gui-layout.leaderboard.*` 覆盖槽位，按钮会渲染在 A 处而监听器判定 B 处 → 点击失效。当前 `config.yml` **无** `gui-layout.leaderboard` 段，故现网一致，属**潜在**漂移 | 警告 |
| W3 | 单一职责（存量） | `data/PlayerDataStore.java`(845)、`gui/ChatHistoryGUI.java`(332)、`listener/SocialListener.java`(325)、`core/duel/DuelManager.java`(643)、`command/LearnCommand.java`(~520)、`core/manager/StatisticsHttpServer.java`(462) | 超 300 行阈值。**均为既有类**，本批次未新增类，按规则仅警告不 FAIL | 警告（存量） |
| W4 | 方法长度（存量） | `gui/ProfileGUI.java:37-187`（`open()` ~150 行）、`gui/ChatHistoryGUI.java:76-197`（`open()` ~120 行）、`data/PlayerDataStore.java` 多个方法 >60 行 | 单方法过长，可读性差。存量问题，非本批次引入 | 警告（存量） |
| W5 | DI 暴露（可接受） | `LetsLearnEnglish.java:102,184` | `statisticsHttpServer` 无 public getter。该对象仅在 `onEnable`/`onDisable` 内部生命周期使用，无外部访问需求 —— 「注册或暴露」二者满足其一，判可接受 | 提示 |
| W6 | 文档准确性（存量） | `api/LetsLearnEnglishAPI.java:24` | 类注释示例 `api.getWordManager().getPlayerMastery(player, "apple")` 引用了**不存在**的方法（`WordManagerImpl` 从未有 `getPlayerMastery`）。本批次删除 `getSession()`/`getWordMastery()` 后更显突兀，但该注释为存量，且非编译期错误 | 提示 |

---

## 维度 1 — 分层正确性

### 1a. `command` / `listener` / `gui` 是否直接写 SQL 绕过 `data` 层 —— PASS

对三个包全量检索 `java.sql` / `prepareStatement` / `executeQuery` / `executeUpdate` / `Statement`：

| 包 | 命中 |
|----|------|
| `command/`（`AdminCommand`, `LearnCommand`） | 0 |
| `listener/`（13 个 Listener） | 0 |
| `gui/`（17 个 GUI） | 0 |

所有持久化均经 `data/PlayerDataStore` 或 `data/DatabaseManager`。本批次改动的 `ChatHistoryGUI`（经 `plugin.getDatabaseManager()`）、`WrongBookGUI`（经 `plugin.getPlayerDataStore()`）均走数据层，无越层。

### 1b. `data` 层是否反向依赖 `gui` —— PASS

`data/` 包的 `com.letslearnenglish.*` 依赖仅：`LetsLearnEnglish`、`config.ConfigManager`、`core.progress.Leaderboard.LeaderboardEntry`、`core.word.LearningSession`、`data.DifficultWord`。**无任何 `gui.*` 引用**。

`PlayerDataStore.getRankedPlayersForPeriod()` 新增的 `LEFT JOIN` 为纯 SQL 组装 + 结果集映射，不含业务决策，符合数据层职责。

### 1c. `core` 是否反向依赖 `gui` —— 存量违规（见 W1），非本批次引入

`gui → listener` 反向引用：**无**（仅 `LetsLearnEnglish` 顶层聚合器 import listener，属正常）。
`gui → core`：正常前向依赖。
`core → gui`：**存在** 3 处（`SoundManager→GUIUtils`、`DuelManager→DuelGUI`、`WordLearningManager→WordLearningGUI`）。其中 `DuelManager`/`WordLearningManager` 本批次未改动；`SoundManager` 本批次仅改字段类型，import 为存量。

按既有判定惯例（如 `BUG-005-006-arch` 对 834 行存量类的处理：警告不 FAIL），记警告 W1，不计入本批次判定。

---

## 维度 2 — 单一职责

本批次**未新增类**，全部为既有类修改。超阈值类均为存量（W3），方法过长均为存量（W4）。本批次新增/修改的方法长度均正常：

| 方法 | 行数 | 结论 |
|------|------|------|
| `DuelGUIListener.onInventoryDrag()` | 13 | PASS |
| `MainMenuListener.onInventoryDrag()` | 14 | PASS |
| `HotReloadManager.reloadAll()` | 14 | PASS |
| `ProfileGUI.open()` 中 Vault 余额判定 | 2 行内联判空 | PASS |

---

## 维度 3 — DI 注入完整性

`onEnable()` 创建对象与暴露情况逐一核对：

| 对象 | 赋值行 | getter | 结论 |
|------|--------|--------|------|
| `configManager` | L120 | L418 | ✓ |
| `messageUtil` | L125 | L430 | ✓ |
| `databaseManager` | L127 | L422 | ✓ |
| `playerDataStore` | L135 | L426 | ✓ |
| `wordManager` | L137 | L476 | ✓ |
| `wordLearningManager` | L138 | L442 | ✓ |
| `achievementManager` | L139 | L446 | ✓ |
| `aiChatManager` | L140 | L480 | ✓ |
| `chatScenarioListener` | L141 | L484 | ✓ |
| `guiNavigationManager` | L142 | L516 | ✓ |
| `dailyChallengeManager` | L143 | L520 | ✓ |
| `progressManager` | L144 | L488 | ✓ |
| `duelManager` | L146 | L524 | ✓ |
| `checkInManager` | L148 | L528 | ✓ |
| `adminCommandHandler` | L150 | L492 | ✓ |
| `dataExporter` | L151 | L496 | ✓ |
| `hotReloadManager` | L152 | L500 | ✓ |
| `soundManager` | L154 | L508 | ✓ |
| `ttsManager` | L156 | L512 | ✓ |
| `proxyMessenger` | L163 | L532 | ✓ |
| `api` | L170 | L504 | ✓ |
| `statisticsHttpServer` | L184 | — | 可接受（见 W5） |
| `updateChecker` | L194 | L438 | ✓ |

**新增构造器依赖接线核对（本批次重点）—— PASS：**

- `HotReloadManager.reloadAll()` 调 `plugin.getSoundManager().reloadSoundCache()`（`HotReloadManager.java:33-35`）。
  - `onEnable` 中 `hotReloadManager`(L152) 先于 `soundManager`(L154) 赋值，与探针假设的「soundManager 在前」**不符**。
  - 但 `HotReloadManager` 构造函数**不调用** `reloadAll()`；`reloadAll()` 仅经 `AdminCommand.java:54`（`/lea reload`）在运行期触发，此时 `soundManager` 必已赋值。
  - 且 `reloadAll()` 对 `getSoundManager()` 做了 **null 判空**（L33）。
  - 结论：**无 NPE 风险**，判定 PASS（备注：赋值顺序与假设相反，但因运行期触发 + 判空兜底而安全）。

- `ProfileGUI` 引用 `plugin.getEconomy()`（Vault，可能为 null）：
  - `ProfileGUI.java:91-92`：`plugin.getEconomy() == null ? 0.0 : ...getBalance(...)` —— 三元判空完整。
  - `ProfileGUI.java:147-148`：`plugin.getEconomy() == null ? "0" : ...format(coins)` —— 判空完整。
  - 两处均先判空再调用，**无 NPE**，判定 PASS。

---

## 维度 4 — 事件注册

对全项目 `implements Listener` 的 13 个类逐一比对 `registerListeners()`（`LetsLearnEnglish.java:243-257`）：

| Listener | 注册行 | 结论 |
|----------|--------|------|
| `PlayerJoinListener` | L244 | ✓ |
| `MainMenuListener` | L245 | ✓ |
| `WordLearningListener` | L246 | ✓ |
| `ScenarioSelectListener` | L247 | ✓ |
| `SocialListener` | L248 | ✓ |
| `MiscGUIListener` | L249 | ✓ |
| `ChatScenarioListener` | L250 | ✓ |
| `ChatAnswerListener` | L251 | ✓ |
| `ChatHistoryListener` | L252 | ✓ |
| `DuelGUIListener` | L253 | ✓ |
| `DuelListener` | L254 | ✓ |
| `WrongBookListener` | L255 | ✓ |
| `SoundManager`（`implements Listener`） | L256 | ✓ |

**本批次重点：新增 `onInventoryDrag` 的注册核对 —— PASS：**

| 类 | 新增方法 | 所在行 | 类是否已注册 | 结论 |
|----|----------|--------|--------------|------|
| `DuelGUIListener` | `onInventoryDrag(InventoryDragEvent)` | L61-73 | ✓ L253 | 新方法可触发 |
| `MainMenuListener` | `onInventoryDrag(InventoryDragEvent)` | L61-74 | ✓ L245 | 新方法可触发 |

两个类的 `@EventHandler` 方法（`onInventoryClick` + `onInventoryDrag`）均随类注册生效，无「方法存在但类未注册」的静默失效。

---

## 本批次专项核查项结论

| 核查项 | 结论 |
|--------|------|
| `DuelGUIListener` / `MainMenuListener` 新增 `onInventoryDrag` 且类已注册 | **PASS**（L253 / L245） |
| `HotReloadManager.reloadAll()` → `getSoundManager().reloadSoundCache()` 的 NPE 风险 | **PASS**（运行期触发 + null 判空；赋值顺序与假设相反但不致 NPE） |
| `LeaderboardGUI` 常量被 `SocialListener` 跨层引用是否可接受 | **可接受**（listener→gui 为向下引用，非违规） |
| `LeaderboardGUI` 是否存在两处定义漂移隐患 | **存在潜在隐患**（见 W2：常量 vs `getGuiSlot` 配置默认值；当前配置无该段故一致） |
| `ChatHistoryGUI.SESSION_SLOTS` / `WrongBookGUI.WORD_SLOTS` 同类评估 | **PASS**（两 GUI 均不读 `gui-layout` 配置，常量即唯一真相，无漂移面） |
| `PlayerDataStore.getRankedPlayersForPeriod` 的 `LEFT JOIN` 前缀拼接与注入面 | **PASS**（`tablePrefix` 拼接方式与同文件 L666/686/741/743 一致；`since`/`until`/`count` 全走 `PreparedStatement` `?` 占位符，无注入面） |
| `RewardEngine` 删除 3 个方法后是否有残留引用 | **PASS**（`giveSessionReward` / `giveExpReward` / `giveAchievementReward` 全项目 0 引用） |
| `WordManagerImpl` 删除 2 个方法后是否有残留引用 | **PASS**（`getSession` / `getWordMastery` 全项目 0 引用；`PlayerDataStore.getWordMastery` 为同名异类方法，不受影响） |
| `ProfileGUI` 引用 `plugin.getEconomy()`（Vault 缺失返回 null）判空是否完整 | **PASS**（L91-92、L147-148 两处均先判空） |

---

## 小结

- **本批次引入的严重/一般违规：0**
- **警告项**：6（W1/W3/W4/W6 为存量架构债，W2 为潜在配置漂移，W5 为可接受提示）
- **专项核查项**：9 项全部通过
- **总体判定：PASS** —— 四维度在本批次改动范围内零违规；列出的 core→gui 反向依赖、超长类、常量-配置双源均为**存量**问题，按既有报告惯例记警告不 FAIL。
