# 架构报告 p3-wave2（Phase 3 Wave 2 五流合流）

### 判定：PASS（含 WARN / 建议，无阻断违规）

审查范围：SocialManager / FeedTextFormatter / ReportGenerator / SocialListener /
EnglishSquareGUI / AchievementManager（Wave 2 改动面）/ WordLearningManager /
PlayerJoinListener / LearnCommand / AdminCommand / AdminCommandHandler / LetsLearnEnglish（DI 容器）/
DatabaseManager（social + report 查询面）。

---

## 1. 依赖方向无环 —— PASS

逐链结论：

| 链 | 方向 | 结论 | 证据 |
|---|---|---|---|
| SocialManager → ConfigManager/PlayerDataStore/DatabaseManager | Core → Config/Data | PASS | 经 `plugin.getConfigManager()/getPlayerDataStore()/getDatabaseManager()` 取用；无 `AchievementManager` import、无 `plugin.getAchievementManager()` 调用（`SocialManager.java` 全文核对） |
| SocialManager → AchievementManager | 不应存在 | PASS | 零引用。SocialManager 依赖面仅：LetsLearnEnglish / SoundManager / DatabaseManager / Bukkit |
| AchievementManager → SocialManager | Core → Core 单向 | PASS | `AchievementManager.java:5` import；`:227` `recordAchievement`；`:454` `getCheerStats`。反向无 |
| SocialListener → SocialManager | Listener → Core | PASS | `SocialListener.java:8` import；`:133/272/605` `plugin.getSocialManager()`。SocialManager 不引用监听层 |
| WordLearningManager → SocialManager | Core → Core 单向 | PASS | `WordLearningManager.java:758-760` `recordSessionDone`。SocialManager 不引用 WordLearningManager |

**结论**：无环，与 plan-social §4.3「SocialManager 不反向依赖成就域」一致。防 P2-26 式 Core↔Core 环的目标达成。

附带观察（非本批引入，不计判定）：`SocialManager.java:4` 静态引用 `core.SoundManager`，而 `SoundManager` 依已知偏差 B1 反向依赖 `gui.GUIUtils`——属存量，未新增环。

---

## 2. 冻结接口一致性 —— PASS

- **CheerStats record**：`SocialManager.java:98` `record CheerStats(int given, int distinctTargets, int received)`。消费点 `AchievementManager.java:454` `sm.getCheerStats(playerId).distinctTargets() >= 10` —— 字段名与判据（plan-social §4.3 / plan-achievements §2.2 判据 `distinctTargets >= 10`）逐字一致。数据源 `DatabaseManager.CheerStatsRow(given, distinctTargets, received)`（`:1766`）同形，SocialManager `:459-460` 位置映射正确。PASS。
- **FeedItem 字段**：`SocialManager.java:80-82`。与三处消费点核对：
  - `FeedTextFormatter`：用到 `type/eventsCount/authorName/lastMode/lastStreak/achievementId/sumCorrect/sumTotal` —— 全在。
  - `EnglishSquareGUI.renderFeedEntries`：用到 `id/authorId/authorName/type/eventsCount/lastMode/lastStreak/achievementId/sumCorrect/sumTotal/cheerCount` —— 全在。
  - `SocialListener`：用到 `authorId/id` —— 全在。
  - 与 `DatabaseManager.FeedRow`（`:1420`，末字段 `eventType`）位置映射（`SocialManager.java:234-236`）逐位对齐。PASS。
  - 附：`FeedItem` 字段名为 `type`，`FeedRow` 为 `eventType`（同义不同名），位置映射正确、无歧义。观察项。
- **getCheerStats 线程契约**：javadoc `SocialManager.java:452-457` 明示「同步 DB 读，禁止主线程调用，调用方负责异步派发」。调用方 `AchievementManager.checkHelpAchievement`（`:449` `runTaskAsynchronously`）确为异步派发，且 `:445` 有 `getSocialManager() == null` 守卫、`:451` 二次读守卫、`:458` 停用窗口 catch。合规。PASS。

**观察项（WARN-级，非违规）**：`getCheerStats` 的「禁主线程」是**纯 javadoc 约定**，无运行期断言/主线程检测。若未来新增调用点忘记异步派发，会在主线程阻塞且无告警。plan 已冻结此设计，接受；建议后续可选加 `Bukkit.isPrimaryThread()` 断言日志。

---

## 3. DI 完整性 —— PASS

- **实例化点唯一**：全库 `new SocialManager` 仅 `LetsLearnEnglish.java:177`；`new ReportGenerator` 仅 `LetsLearnEnglish.java:181` —— 均在 `onEnable()` 容器内。PASS。
- **注入方式**：SocialManager 构造仅收 `plugin`（DI 走 plugin 门面 getter），与 DuelManager/CheckInManager 同款；ReportGenerator 构造注入 `(plugin, databaseManager, wordManager)`（`:181`），与 plan §3.1「仿 DataExporter 位置」一致。PASS。
- **消费点接线**：
  - `SocialListener` 注册：`LetsLearnEnglish.java:305`。PASS。
  - `socialManager.startupWarmup()`：`:212`，在启动异步块内（预热+排程）。PASS。
  - `PlayerJoinListener`：`:86` `notifyPendingCheers`、`:118` `unloadPlayer`（PlayerJoinListener 非本流白名单但在其内自包含接入，未新增监听器）。PASS。
  - `ReportGenerator` 消费：`AdminCommandHandler.java:173/199` 经 `plugin.getReportGenerator()`。PASS。
- **构建顺序安全**：SocialManager 构造于 `:177`，`registerListeners()` 在 `:202`，故 join 事件到达时 `getSocialManager()` 必非 null。PlayerJoinListener `:86/118` 未加 null 守卫但顺序上安全。PASS。
- **import 完整性**：容器 `LetsLearnEnglish.java:20/17` 新增 SocialManager / ReportGenerator import，无遗漏。PASS。

**观察项（文档不一致，WARN）**：`LetsLearnEnglish.java:674` `getReportGenerator()` javadoc 称「未启用前为 null，命令侧不触碰」，但实现上 `:181` **总是**构造（非惰性）。javadoc 与实际不符，建议改为「enable 后恒非 null」。仅注释问题，不影响运行。同类文案亦见于 `AchievementManager.java:445` 的 null 守卫（该守卫为防御性，无害）。

---

## 4. 分层合规 —— PASS（含 1 项收拢建议）

- **新 Listener/GUI 是否越层直调 DatabaseManager**：
  - `SocialListener.java`：**无** `DatabaseManager` import，全部经 `SocialManager` 门面（`getFeedSnapshot/getCheeredFeedIds/cheer/getCheerersAsync/isEnabled`）。PASS——本批新增的 listener 代码零越层。
  - `EnglishSquareGUI`：FEED 新增分支（`renderFeedEntries`）经 `socialManager.getCheeredFeedIds`+`getProfileSettingsReadOnly` 门面，未直调 DB。PASS。
  - 存量观察（不计判定）：`EnglishSquareGUI.java:272`（非 FEED 的榜单 rank-entry 分支）直调 `plugin.getDatabaseManager().getCoinsEarned(...)` —— GUI→Data 越层。系既有代码，本次未新增，建议下批次收拢。
- **SocialManager 的查询位置**：全部 SQL 委托 `DatabaseManager`（upsertFeedEvent / getFeedCacheRows / cheerFeed / getCheeredFeedIds / getCheerStatsRow / countUnreadCheers / markFeedCheersRead / markSingleCheerRead / getCheerers / cleanupOldFeedEvents）。SocialManager **自持零 JDBC**、零 `getConnection()`。分层干净。PASS。
- **ReportGenerator 持 databaseManager 直调（W2-E「搬迁」）——结论：可接受为并行开发权宜，但建议收拢**：
  - 现状：`ReportGenerator.java:74` 注入 `DatabaseManager`，并在本类内**直接跑原始 SQL**（`synchronized(databaseManager.getConnectionLock())` + `prepareStatement`，见 `:326/:362/:387/:410/:430/:464/:489/:539/:577`）。plan §3.1 原设计把这些 SQL（Q1~Q9）放在 `DatabaseManager`。
  - 分层含义：`core/manager`（Core）穿透 Data 层的**公开 API 门面**，直取 `getConnection()/getConnectionLock()` 内部结构 → 属 Core→Data-internals 泄漏，非标准「Core 调 Data API」。
  - 可接受性理由：本批 `DatabaseManager` 被列为并行兄弟流的文件白名单，SQL 宿主他处以避文件重叠（`AdminCommandHandler.java:204-205` 自述同因，`getKnownPlayerNames` 同款落位）。
  - **结论：可接受（不阻断），建议下批次收拢**——把 Q1~Q9 与 `getKnownPlayerNames` 迁回 `DatabaseManager`，ReportGenerator/AdminCommandHandler 只调方法名。收拢后 Core 层不再出现原始 SQL 与 connectionLock。
- **Command 层是否只经门面**：
  - `LearnCommand.java:726` 经 `plugin.getSocialManager().recordCheckin(...)` —— 门面。PASS。
  - `AdminCommand.java:235/237` 经 `plugin.getAdminCommandHandler().generateBatchReport/generateReport(...)`，不直接触 `ReportGenerator` —— 门面。PASS。
  - `AdminCommandHandler`（Core）经 `plugin.getReportGenerator()` 派发 —— 合理。

---

## 5. 类职责 —— WARN（ReportGenerator 建议拆分）

- **ReportGenerator（1130 行）**：单类内混合 **查询**（collectSingle/queryDailyRows/queryWeakWords/queryBatchRows/isHiddenInSquare，约 260 行）+ **渲染**（renderSingle/renderBatch，约 250 行）+ **周期纯函数**（`ReportPeriod` 嵌套 record + compute）+ **文件 IO**（writeSingleFile/writeBatchFile/writeTextFile/cleanupOldReportsIfDue）+ 设置快照/格式化/调度。
  - 对照 plan §3.1 原拆分：本应 **2 个类** —— `ReportGenerator`（编排）+ `ReportTextFormatter`（纯函数渲染，可无 Bukkit 单测）。
  - 偏差：① 未落地 `ReportTextFormatter` 纯函数类，渲染以内联 private 方法存在，**不可无 Bukkit 单测**（与 plan §3.1「可单测」及 US-RPT-4 测试要求相悖）；② 包位为 `core/manager/` 而非 plan 的 `core/report/`（都在 Core 层内，分层上无违规，仅与原规划不一致）。
  - 1130 > 300 → 按项目约定为**警告（不 FAIL）**。建议：抽出 `ReportPeriod` 顶层类 + `ReportTextFormatter` 纯渲染类 + 查询下沉 DatabaseManager，主类回归「编排」单一职责。
- **SocialManager（487 行）**：职责内聚（广场动态流 + 鼓励 + 摘要 + 统计 + 调度，单一域；计划估 ~250 行，实际近倍）。多个 record 嵌套（FeedItem/CheerStats）与 DuelManager 形态对齐。**内聚，接受**；487 > 300 亦为警告级。建议：无强制拆分需求，可将调度工具/缓存刷新内聚为私有 helper（现已是私有方法，边界清晰）。

---

## 6. import 卫生 —— PASS

抽查本批新建/改动文件的未用 import：

- `SocialManager.java`：`Map/Set/AtomicLong/AtomicReference/Consumer/ConcurrentHashMap/LocalDate/Collections/ArrayList/List/UUID` —— 全部被用（`Set` 用于 getCheeredFeedIds 返回、`AtomicReference` 用于 lastCleanupDay、`Consumer` 用于 getCheerersAsync 回调）。无未用。PASS。
- `FeedTextFormatter.java`：仅 `core.achievement.Achievement`，被 `resolveAchievementName` 使用。PASS。
- `ReportGenerator.java`：`DayOfWeek/ZoneId/ChronoUnit/LinkedHashMap/AtomicBoolean/StandardCharsets/Files/IOException/Level/...` —— 逐一核对均被使用。无未用。PASS。
- `SocialListener.java`：`LinkedHashSet`（extractTargetIdFromTitle）、`ChatColor`（titleMatchesLabel）、`GUIUtils`、各 GUI 类均被使用。无未用。PASS。
- `AchievementManager.java`、`AdminCommandHandler.java`：无新增未用 import（含既有 `java.util.*` 通配，存量风格）。PASS。
- `LetsLearnEnglish.java`：新增 `SocialManager`/`ReportGenerator` 均被使用。PASS。

**无未用 import，无缺失 import。** PASS。

备注（非 import 卫生本身）：`FeedTextFormatter` 引用 `core.achievement.Achievement`（enum 定义层，非 Manager），属 Core→Core 读纯定义，不构成管理器环，可接受。

---

## 7. 测试资产 —— PASS（surface 评审，另有建议）

- `AchievementDefinitionTest`：`NEW_IDS`（`:35-37`）已含 6 个 Phase-3 新 id（review_streak_7 / wrong_book_cleared / accuracy_90 / duel_first_win / helpful / study_days_30），`testValuesCountAndFrozenIdSet` 断言 22、`testPhase3AssignmentMapping` 覆盖 6 新的 category/source/target（HELPful 断言 SOCIAL/NONE/0）。Wave 2 **未新增成就定义**，故该测试**无需扩展即保持有效**。PASS（绿）。
- **建议（非阻断）**：
  1. `FeedTextFormatter` 被设计为「可脱离服务器单测」（plan-social §2.3），但全库无 `FeedTextFormatterTest` —— 建议补（SESSION_DONE 合并/ACHIEVEMENT/CHECKIN/未知类型 null、detail 行 showResults 降级分支）。
  2. `ReportPeriod.compute` 为纯函数（plan 要求 ReportPeriodTest ≥4 例：周一锚点/lastweek/Nd 边界/非法），当前无对应测试文件（`src/test` 仅 5 个既有测试，无 report 相关）。建议补。
  3. `SocialManager.CheerStats` 判定链（cheer→distinctTargets）无单测；属集成面，可留 build tester 覆盖。

---

## 问题清单（按严重度）

| # | 严重度 | 维度 | 位置 | 现象 | 建议 |
|---|---|---|---|---|---|
| 1 | WARN | 类职责/测试资产 | `ReportGenerator.java`（全类，1130 行） | 查询+渲染+周期纯函数+文件 IO 单类混合；未落地 plan 的 `ReportTextFormatter` 纯函数类，渲染不可无 Bukkit 单测 | 拆出 `ReportTextFormatter`（纯渲染）+ `ReportPeriod` 顶层类；查询下沉 DatabaseManager |
| 2 | WARN | 分层 | `ReportGenerator.java:326/362/387/410/430/464/489/539/577`（+ `AdminCommandHandler.java:213-236`） | Core 层穿透 Data 门面直取 `getConnection()/getConnectionLock()` 跑原始 SQL（W2-E「搬迁」）；plan §3.1 原定 SQL 在 DatabaseManager | 可接受为并行开发权宜；下批次把 Q1~Q9、`getKnownPlayerNames` 迁回 DatabaseManager |
| 3 | WARN | DI（文档） | `LetsLearnEnglish.java:674` | `getReportGenerator()` javadoc 称「未启用前为 null」，实际 `:181` 恒构造，注释与实现不符 | 修正 javadoc 为「enable 后恒非 null」 |
| 4 | 建议 | 冻结接口 | `SocialManager.java:452-457` | 「禁主线程调用」仅 javadoc 约定，无运行期断言 | 可选加 `Bukkit.isPrimaryThread()` 断言日志（plan 已冻结设计，非必须） |
| 5 | 建议 | 测试资产 | `core/social/`、`core/manager/ReportGenerator.ReportPeriod` | `FeedTextFormatter` / `ReportPeriod` 无单测，与 plan「可无 Bukkit 单测」锚点不符 | 补 `FeedTextFormatterTest`、`ReportPeriodTest`（≥4 例） |
| 6 | 建议 | 分层（存量） | `EnglishSquareGUI.java:272` | 榜单 rank-entry 分支 GUI→DatabaseManager 直调 `getCoinsEarned`（既有，非本批新增） | 下批次经 Core 门面收拢 |
| 7 | 观察 | 记录命名 | `SocialManager.FeedItem.type` vs `DatabaseManager.FeedRow.eventType` | 同义字段异名，靠位置映射 | 可选统一命名，降低映射脆弱性 |

---

## 结论

七个重点检查项：**依赖无环 PASS / 冻结接口一致 PASS / DI 完整 PASS / 分层合规 PASS（含收拢建议）/ 类职责 WARN / import 卫生 PASS / 测试资产 PASS（surface）**。

四个硬维度（分层正确性 / 单一职责 / DI 完整性 / 事件注册）**无阻断违规**：300+ 行按项目约定为警告不 FAIL；ReportGenerator 的 SQL 落位属 plan 允许的并行权宜，判为可接受并建议收拢。**总判定 PASS**，问题清单均为 WARN/建议级，建议将 #1、#2 排入下批次（报告域收尾 / Phase 3 配置面清理）一并处理。
