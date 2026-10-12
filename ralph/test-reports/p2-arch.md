# 架构报告 P2（清理批次 甲+乙，8 流 fan-in）

审查范围：P2 合流批次的新增/改动代码（依据 CHANGELOG v1.0.7 的 `[P2 *]` 条目 + 代码注释锚点定位）。
只读审查，未运行 mvn / git 写操作。仓库根无可用 `git diff`（本环境未暴露 git 执行通道），批次边界以 CHANGELOG 条目与代码内 `// P2`/`R-xx` 锚点交叉推断。

### 判定：FAIL（仅 1 项 import 卫生违规，均为低严重度，不影响编译/运行）

---

## 1. 依赖方向无环（最关键）— PASS

| 链 | 方向 | 结论 |
|----|------|------|
| `PlayerJoinListener.onPlayerQuit` → `GuiNavigationManager.clearPlayer` (PlayerJoinListener.java:99) | Listener→Core | 合规，单向 |
| `PlayerJoinListener.onPlayerQuit` → `CheckInManager.unloadPlayer` (:100) | Listener→Core | 合规，单向 |
| `PlayerJoinListener.onPlayerQuit` → `ProgressManagerImpl.getRewardEngine().unloadPlayer` (:101) | Listener→Core | 合规，单向 |
| `PlayerJoinListener.onPlayerJoin` → `DuelManager.consumeOfflineDuelNotice` (:67-69) | Listener→Core | 合规，单向 |
| `PlayerDataStore.unloadPlayerData` (PlayerDataStore.java:756-772) | Data→Data（仅自身 map） | 合规。方法体只 `savePlayerData` + 移除自有 13 个 cache map，**未**反向调用 GuiNavigationManager/CheckInManager/RewardEngine；javadoc (:748-754) 明确写出"不在这里反向调用" |
| `WordLearningListener.onPlayerQuit` (:41-44) | Listener 内自包含 | 合规。仅清本类 `pronounceCooldown`，不依赖 PlayerDataStore，避免跨层反调 |
| `LetsLearnEnglish.onDisable` → `DuelManager.shutdown` (LetsLearnEnglish.java:236) | 容器→Core | 合规，单向 |
| `CheckInManager.giveCoins` → `RewardEngine.depositCappedReward` (CheckInManager.java:164) | Core→Core | 合规。无回边 `RewardEngine→CheckInManager` |
| `MiscGUIListener.depositChallengeReward` → `RewardEngine.depositCappedReward` (MiscGUIListener.java:243) | Listener→Core | 合规 |
| `DuelManager` → `AIChatManager.hasActiveConversation` / `WordLearningManager` (DuelManager.java:134-141, 1043) | Core→Core | 合规。无回边 |

**无环确认**：新增跨层链全为 Listener→Core / 容器→Core / Core→Core 单向，PlayerDataStore 保持"只管自己 map"。
**既有观察（非本批引入）**：`AIChatManager→DailyChallengeManager.updateProgress`（AIChatManager.java:525）与 `DailyChallengeManager→AIChatManager.isAvailable`（DailyChallengeManager.java:225）构成 Core↔Core 互引（经容器查找，非构造注入）。当前无递归调用栈风险，但属结构性环，建议后续以事件/回调打断。

## 2. 跨流冻结接口一致性 — PASS

**`AIChatManager.ImportResult`（三处一致，逐点核对）**
- 定义：`public static record ImportResult(String key, boolean updated, boolean saveFailed, boolean limitReached)`（AIChatManager.java:993-994）
- 内部返回路径：1042 `(null,false,true,false)`=mkdir 失败(saveFailed)；1064 `(null,false,false,true)`=limitReached；1091 `(null,false,true,false)`=save IOException；1100 `(key,exists,false,false)`=成功(updated=exists)
- 消费点：LearnCommand.java:747-766 —— 先 `limitReached()`（748）、再 `key()==null || saveFailed()`（757）、再 `updated() ? load-updated : load-success`（765）
- 结论：字段顺序/类型/语义三处一致，四路分支消费与返回路径一一对应。PASS

**`DuelManager.OfflineDuelNotice`（定义/入队/消费三处核对）**
- 定义：`public record OfflineDuelNotice(String messageKey, String otherPlayerName)`（DuelManager.java:75）
- 入队：`cancelDuelByAdmin` 对离线方 `enqueueOfflineDuelNotice(...)` → `new OfflineDuelNotice("duel.cancelled-by-gm", name)`（DuelManager.java:702/711 → 736-739）
- 消费：**存在** —— `PlayerJoinListener.onPlayerJoin` 经 `plugin.getDuelManager().consumeOfflineDuelNotice(uuid)` 读取并按 `notice.messageKey()` / `notice.otherPlayerName()` 渲染发送（PlayerJoinListener.java:65-74）；`DuelManager.consumeOfflineDuelNotice` 定义于 DuelManager.java:751-754（读后即清，幂等）
- 结论：**不存在集成缺口**。入队点、消费点、record 定义三处一致（`duel.cancelled-by-gm` 键在 zh.yml:275 / en.yml:275 双语存在）。PASS

> 说明：`DuelListener.onPlayerJoin` 的 `handleReconnect`（延迟 20 tick）与本消费点并存且互不冲突：前者处理宽限期内重连续局，后者处理 GM 解散的离线通告；两者均以 `disconnectTimers`/`offlineNotices` 各自独立 map 为判据，无重复消费。

**`DuelManager.PendingDuel`**：仅 DuelManager 内部使用（grep 全库命中均在 DuelManager.java:42/63/148/161/225）。未外泄。PASS（低：声明为 `public record`，可收窄为 private，见问题清单）

## 3. 分层合规抽查 — PASS（本批）

- **Listener/GUI 越层直调 DatabaseManager**：本批新增/改动文件中未见新增越层直调。
  - `ChatHistoryGUI` 确有 `plugin.getDatabaseManager()` 直调（:121/128/146/271），但属 chat-history 既有功能，非本批 P2 引入（本批对该文件仅做 R-86 音效删除，:221-223）。
  - `EnglishSquareGUI:227`、`LLEPapiExpansion`、`DailyChallengeManager`、`AchievementManager`、`Leaderboard` 的直调均为历史遗留，非本批 diff。
- **Command 层只经 Core 门面**：LearnCommand 走 `plugin.getAIChatManager()` / `getDuelManager()` / `getCheckInManager()` / `getWordManager()`（Core 门面）。未 import 也未调用 `DatabaseManager`。
  - 既有观察：LearnCommand.java:371 直读 `plugin.getPlayerDataStore().getProfileSettings()`（Command→Data），系历史实现，非本批。

## 4. 类职责 — PASS（附 WARN）

- **AIChatManager（1140 行）**：本批新增方法按"个人场景"分区聚集（分隔注释 976-980），`canSendChat`（:363）归入"频率限制"分区、`ImportResult`/`importPersonalScenario`/`reloadPersonalScenarios`/`getPersonalScenarios`/`getMaxPersonalScenarios` 归入"个人场景"分区、`shutdown` 布尔守卫内聚。**无职责混入到无关分区**。但该类现同时承担：配置加载、HTTP 客户端、频率限制、难度管理、上下文裁剪、全局场景、个人场景**文件持久化（File/YamlConfiguration/save/.bak 备份，:1039-1100）**。个人场景落盘属"仓储"职责，建议后续抽 `PersonalScenarioStore`。WARN（非本批阻塞）
- **SoundManager**：R-86 单源裁决后职责清晰 —— `Listener`（唯一 menu-open 全局播放点，:109-115）+ `soundCache`/`enabled` 解析；全局 `menu-click` 监听已删除。`static playSound` 经 `LetsLearnEnglish.getInstance()` 取实例（:118-120），属既有全局门面设计，可接受。PASS

## 5. DI 注入完整性 — PASS

| 新增 public 方法 | 调用方 | 取实例方式 | 结论 |
|----|----|----|----|
| `sendJoinDailyReminderIfEnabled` | PlayerJoinListener.java:63 | `plugin.sendJoinDailyReminderIfEnabled(...)` | 容器 |
| `DuelManager.shutdown` | LetsLearnEnglish.java:236 | `duelManager` 字段 | 容器 |
| `consumeOfflineDuelNotice` | PlayerJoinListener.java:69 | `plugin.getDuelManager()` | 容器 |
| `GuiNavigationManager.clearPlayer` | PlayerJoinListener.java:99 | `plugin.getGuiNavigationManager()` | 容器 |
| `CheckInManager.unloadPlayer` | PlayerJoinListener.java:100 | `plugin.getCheckInManager()` | 容器 |
| `RewardEngine.unloadPlayer` | PlayerJoinListener.java:101 | `plugin.getProgressManager().getRewardEngine()` | 容器（嵌套访问器） |
| `AIChatManager.canSendChat` | ChatScenarioListener.java:116 | 构造注入的 `aiChatManager`字段 | 容器 |
| `getMaxPersonalScenarios` | 无调用方 | — | 死 getter（见问题清单） |

全库 `new AIChatManager|new GuiNavigationManager|new CheckInManager|new RewardEngine|new SoundManager|new DuelManager` 仅出现在 `LetsLearnEnglish.onEnable()` 与 `ProgressManagerImpl.getRewardEngine()` 懒加载处，**无绕过容器的 new 实例**。PASS

## 6. import 卫生 — FAIL

抽查 6 文件结论：
- SoundManager：import 全被使用（含 `Listener`/`InventoryOpenEvent`，onEnable:291 注册）。PASS
- ChatHistoryGUI：import 全被使用。PASS
- DuelGUIListener：import 全被使用（`DuelManager`/`DuelGUI`/`InventoryDragEvent` 均有用）。PASS
- LearnCommand：import 全被使用（`ScenarioCodec`/`Bukkit`/`Locale`/`Map`/`ConcurrentHashMap` 等均有引用）。PASS
- SocialListener：import 全被使用（`LearningCalendarGUI`:310、`RankingMode`、`GUIUtils` 均有引用）。PASS
- **MiscGUIListener：2 处未用 import**
  - `import ...core.DailyChallengeManager.ChallengeType;`（:4）—— 全文件仅此一处出现，无任何 `ChallengeType` 引用（`cp.type.reward` 是字段访问，非类型名）
  - `import ...gui.LearningCalendarGUI;`（:9）—— 全文件仅 import 行，`handleLearningCalendar`（:148-156）返回逻辑已改走 `nav.popBack(...) → MainMenu`，不再引用 `LearningCalendarGUI` 类型

---

## 问题清单（按严重度排序）

| # | 严重度 | 位置 | 现象 | 建议 |
|---|--------|------|------|------|
| 1 | 低 | MiscGUIListener.java:4 | `import ...DailyChallengeManager.ChallengeType;` 未使用（R-49/R-53 等改动后残留） | 删除该 import |
| 2 | 低 | MiscGUIListener.java:9 | `import ...gui.LearningCalendarGUI;` 未使用（返回逻辑已改走 `popBack`，不再 new 该 GUI） | 删除该 import |
| 3 | 低 | AIChatManager.java:1249 | `getMaxPersonalScenarios()` 无任何调用方（LearnCommand 用当前条数实填 `{max}`，已在 dev-findings P2-12 记录） | 删除，或让 LearnCommand 改用它消死代码 |
| 4 | 低 | DuelManager.java:63 | `public record PendingDuel` 仅本类内使用 | 收窄为 private |
| 5 | 低（既有，非本批） | AIChatManager.java:525 ↔ DailyChallengeManager.java:225 | Core↔Core 互引（AIChatManager→DailyChallengeManager.updateProgress 与反向 isAvailable） | 后续以事件/回调打断，消除结构性环 |
| 6 | 低（既有，非本批） | ChatHistoryGUI.java:121/128/146/271 | GUI 层直调 `DatabaseManager`（越层） | 后续抽 Core 门面（如 ChatHistoryService）收敛 |

**复核项**：第 2 项（OfflineDuelNotice）**无集成缺口** —— 入队点 `cancelDuelByAdmin`（DuelManager.java:702/711）与消费点 `PlayerJoinListener.onPlayerJoin`（PlayerJoinListener.java:67-74）已闭环，`consumeOfflineDuelNotice` 定义于 DuelManager.java:751。
