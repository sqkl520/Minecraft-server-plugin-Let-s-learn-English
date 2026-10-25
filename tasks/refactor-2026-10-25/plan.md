# 三项重构 · 施工编排计划（2026-10-25）

> 输入：规划 Agent + 2 路侦察 + 2 路批判（红队）交叉验证后的结论
> 纪律：每波内文件白名单零重叠；子代理禁跑 mvn；编排者在波次间单点编译

## 一、事实基线（实测，非锐评转述）

| 项 | 数值 |
|---|---|
| `createInventory(null,...)` | 21 处 / 18 文件（全仓 holder 引用 0） |
| listener 标题判定 | 29 处 `getTitle()` / 14 文件；`contains` 67 处（SocialListener 独占 22） |
| `GUIUtils.isPluginGUI` 白名单 | 38 条 contains（唯一生产调用点 SoundManager:216） |
| data/ 外裸 JDBC | **27 处**（DCM 10 / RG 9 / AM 4 / ACH 2 / PAPI 1 / 入口 1） |
| data/ 外 `getTablePrefix` | 22 处；`getConnectionLock` 27 处；SQL 字面量 29 条 |
| 答对一题同步 SQL | **7~10 条**，全在主线程、全过同一 connectionLock |
| `dirty` 机制 | 全仓 0 命中 |
| `PlayerStats` 字段 | 非 volatile，主线程写 / 异步线程读（现存 bug） |
| 测试 | 49 个 @Test，**零 DB / 零 GUI 覆盖**；pom 无 sqlite-jdbc 依赖 |

## 二、六路裁决摘要

- **R5 做全量**（用户指令），但按安全顺序：先注入 holder（行为零变化）→ 再切判定（带标题 fallback）→ 最后删白名单。
- **R6 做全量 27 处**，逐方法"加仓储→切调用→删旧块"，仓储只搬 SQL 不搬语义。
- **R7 做**：dirty 标记（含 12 周期全量兜底）+ `persistLongestStreak` 异步 + `updateProgress`/`onWordCorrect` 查询合并 + `PlayerStats` volatile。
- **不做**（六路一致反对，写入 CHANGELOG 备查）：答对路径压到 1~2 条（3 条是控制流谓词，压到 1~2 只能改"本地乐观判定+后台对账"=资损路径重写）；`SqlDialect` 抽象（仅 2 处真方言分叉）；路由塞进 GuiNavigationManager；合并 11 个 listener。

## 三、波次与文件白名单

### Wave 1（6 流并行 · 基础设施 + 独立域）

| 流 | 独占文件 |
|---|---|
| R5-0 | `gui/GUIUtils.java`、`core/SoundManager.java`、新建 `gui/identity/{GuiViewKey,PluginGuiHolder,GuiIdentity}.java` |
| R6-A | `core/manager/ReportGenerator.java`、`core/manager/AdminCommandHandler.java`、`expansion/LLEPapiExpansion.java`、新建 `data/repository/{ReportRepository,PlayerDirectoryRepository}.java` |
| R6-B | `core/DailyChallengeManager.java`、新建 `data/repository/DailyChallengeRepository.java` |
| R6-C | `core/achievement/AchievementManager.java`、新建 `data/repository/AchievementRepository.java` |
| R6-D | `data/DatabaseManager.java`、`LetsLearnEnglish.java` |
| R7-A | `data/PlayerDataStore.java` |

### Wave 2（8 流并行 · R5 家族 + R7-B）

| 流 | 独占文件 |
|---|---|
| R5-1 | `gui/WordLearningGUI.java`、`gui/WrongBookGUI.java`、`listener/WordLearningListener.java`、`listener/WrongBookListener.java` |
| R5-2 | `gui/MainMenu.java`、`gui/ConfirmationGUI.java`、`listener/MainMenuListener.java` |
| R5-3 | `listener/MiscGUIListener.java`、`gui/{WordBankSelectGUI,DailyChallengeGUI,ChallengeCompleteGUI,LearningCalendarGUI}.java` |
| R5-4 | `listener/SocialListener.java`、`gui/{EnglishSquareGUI,LeaderboardGUI,ProfileGUI,AchievementWallGUI}.java` |
| R5-5 | `gui/ChatHistoryGUI.java`、`listener/ChatHistoryListener.java`、`gui/ClassBoardGUI.java`、`listener/ClassBoardListener.java` |
| R5-6 | `gui/{DuelGUI,DuelChallengeGUI}.java`、`listener/{DuelGUIListener,DuelChallengeListener}.java`、`core/duel/DuelManager.java` |
| R5-7 | `gui/{ScenarioSelectGUI,PersonalScenarioGUI}.java`、`listener/{ScenarioSelectListener,PersonalScenarioListener}.java` |
| R7-B | `core/DailyChallengeManager.java`、`data/DatabaseManager.java` |

### Wave 3（5 路红队）

RT-A R5 正确性 / RT-B R6 等价性 / RT-C R7 正确性 / RT-D 构建与文档一致性 / RT-E 跨流集成接缝

RT-E 是编排者在四路之外追加的接缝审查（专查"单流各自自洽、合流后语义打架"的跨流缺陷）。

本轮红队实际发现并已修：

- **RT-A**：2 条 P1（`WordLearningListener` / `WrongBookListener` 缺标题 fallback）+ 1 条 P2（`ProfileGUI` slot 10/12 空槽）
- **RT-B**：3 条 P2（表前缀构造期快照 / 静默失败被升 WARNING / 死代码）
- **RT-C**：3 条 P1（`saveDirty` 未接线 / 12 轮兜底不可达 / 快照锁外取）+ 3 条 P2
- **RT-D**：2 条 P0（与 RT-C 同源）+ CHANGELOG 三处虚数
- **RT-E**：2 条 P1（`/lea reset` 不清 `DailyChallengeManager` 缓存 / 跨服共享 MySQL 的 `completedWords` 缓存背离）+ 2 条 P2

## 四、硬约束（写进每个 agent 的提示词）

1. **禁跑 mvn**（并行会 clobber `target/`），编译由编排者在波次间单点执行。
2. **禁写报告文件**（harness 限制），最终消息携带全文。
3. **R5 判定顺序铁律**：`event.getInventory()` 恒为上半区，holder 命中只证明"这个 view 属于我"，**不证明"这一下点击属于我"**；`getClickedInventory()==null`（窗外）与 `rawSlot>=topSize`（下半区）的守卫必须原样保留在 cancel 之前。
4. **R5 双轨并存**：holder 优先、标题 fallback 保留；任何一处漏改只走回退，不得静默失联。
5. **R6 锁语义逐字复刻**：仓储方法内 `synchronized(db.getConnectionLock())` + 锁内首行 `getConnection()`；stripe lock 永远在调用方外层；不新增 `setAutoCommit` 调用。
6. **R7 漏标即静默丢数据**：`markDirty` 必须覆盖全部写入点，且保留每 12 周期全量兜底。
7. 范围外 bug 只报告不修。
