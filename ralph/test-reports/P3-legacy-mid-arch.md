# 架构报告 P3 遗留中危修复批（F2-11 + C-3 + C-7 + P3-02 + P3-03）

审查范围：工作树未提交改动，共 6 文件 —
`LetsLearnEnglish.java`（午夜两段式）、`gui/DailyChallengeGUI.java`、`gui/LearningCalendarGUI.java`（openAsync）、
`data/DatabaseManager.java`（统计口径）、`expansion/LLEPapiExpansion.java` + `core/progress/ProgressManagerImpl.java`（streak 读侧）。
只读审查，未执行 mvn / 未修改任何文件。

### 判定：PASS

六个审查维度零违规。全部发现均为 INFO/低（范围外项与一致性观测），无 FAIL 行。

---

## 1. 分层合规 — PASS

| 位置 | 调用 | 判定 |
|------|------|------|
| `LLEPapiExpansion.java:350` | expansion → `plugin.getDatabaseManager().getCheckInData(playerId)` | **未新增越层**（净减少） |
| `ProgressManagerImpl.java:63` | core → `databaseManager.getCheckInData(playerId)` | 合规（Core→Data 正向） |

**P3-03 expansion 侧是净改善，不是新越层。** 同一文件的既有姿态本就直调 Data 层：`:254` `getCoinsEarned`、`:361-364` 直接取 `getConnection()`/`getConnectionLock()` 手写 SQL。旧实现读 `player_data.current_streak` 走的是同一层级的裸 SQL，新实现 `getCheckInData` 反而是 **DatabaseManager 的公开 API 方法**（自带 `synchronized(connectionLock)`，`DatabaseManager.java:1518-1537`）——越层深度持平或更浅。判为确认项，不作为违规。

`ProgressManagerImpl`（core/progress）→ `DatabaseManager`（data）为既有方向，`databaseManager` 字段已在构造函数注入（`:19/:27`），未新增依赖。合规。

## 2. DI / 依赖方向 — PASS

**F2-11 两段式零新依赖、零新环。** `LetsLearnEnglish.java:508-617` 重写后：无新字段、无新 getter、无新构造；`dailyChallengeManager` / `wordManager` / `messageUtil` / `seasonManager` 全为既有成员；跨线程数据只经 `final` 局部量（`:543 yesterday`、`:544-547 onlineIds` → `:551 voidedBehaviorByPlayer`）传递，未把 `this` 或可变成员暴露给异步段。反向引用面核实：`DailyChallengeManager` / `WordManagerImpl` / `SeasonManager` 均不引用 `LetsLearnEnglish` 的新增成员。无环。

**GUI openAsync 的调度姿态正确。** 两处均为标准 Bukkit 姿势：`Bukkit.getScheduler().runTaskAsynchronously(plugin, …)`（`DailyChallengeGUI:72`、`LearningCalendarGUI:91`）+ 回主线程 `runTask(plugin, …)`（`:86`/`:103`），未自造线程池、未用 `CompletableFuture` 直跑。数据以参数传入 `openWithData`（`DailyChallengeGUI:102`、`LearningCalendarGUI:119`），渲染段零 DB 调用——职责切分干净。

## 3. 一致性（async 守卫链）— PASS（含 2 条一致性观测）

守卫链逐项对照既有惯用法：

| 位置 | 停用窗口守卫 | 异步段入口复检 | 主线程段复检 |
|------|-------------|---------------|-------------|
| `SocialManager.java:472-486`（既有 runAsync/runMain） | try/catch `IllegalStateException` | ✗ | ✗ |
| `SeasonManager.java:735-748`（既有） | try/catch `IllegalStateException` | ✗ | ✗ |
| `DailyChallengeGUI.java:69-97`（本批） | try/catch ×2 | `isEnabled()` ×2 | `isOnline()` |
| `LearningCalendarGUI.java:88-114`（本批） | try/catch ×2 | `isEnabled()` ×2 | `isOnline()` |
| `LetsLearnEnglish.java:548-612`（本批） | try/catch ×2 | `isEnabled()` ×2 | —（自续由 `scheduleNextMidnightRollover` 首行守卫） |

结论：本批三处复用**同一既有惯用法内核**（try/catch `IllegalStateException` 静默放弃，与 `SocialManager`/`AchievementManager` PF-ACH-02 同款），且是其**严格超集**（额外补了 `isEnabled()` + `isOnline()` 复检，比既有两例更严）。未发明新变体。判 PASS。

**INFO-1（一致性，低）**：本批把 try/catch 惯用法**内联复制 4 处**（两 GUI 各 2 处 + 午夜块 2 处），未复用 `SocialManager`/`SeasonManager` 已抽出的 `runAsync`/`runMain` 私有 helper 姿势。姿态一致但收敛度下降；若要统一，建议抽 `util` 层共享 helper（GUI 层够得到，core 私有 helper 够不到）。

**INFO-2（javadoc 命名漂移，低）**：`DailyChallengeGUI.java:66` 与 `LearningCalendarGUI.java:85` 的 javadoc 写「R-67」，但 R-67 的设施实为 `AIChatManager.isShuttingDownOrDisabled()`（`shuttingDown || !plugin.isEnabled()`，`AIChatManager.java:299-305`）；两 GUI 只查 `isEnabled()`。GUI 无 shutdown 标志位，`isEnabled()` 是正确且充分的守卫，**姿态无问题**——仅 javadoc 引用了一个 AI 专属设施名，语义上应对齐「停用窗口静默放弃」（SocialManager/AchievementManager 口径）。建议改措辞，不改代码。

## 4. 遗留列处理（P3-03）— PASS

`DatabaseManager.java:109-112`：`current_streak` 保留不删 + 注释「历史遗留列、零写入恒 0，真源是 checkin_data.streak_count；本列保留不删（删列有 schema 变更风险），读侧已改走 checkin_data」。符合「数据永不删」架构纪律（`plan-menu-config §4 底线`：关闭只停入口与新增写入，绝不删既有数据行）。读侧零残留核实：全库 `current_streak` grep 仅命中注释 + 建表 DDL 字符串，**无任何读取点**，废弃彻底且无破坏性 schema 变更。PASS。

**INFO-3（文档面缺口，低）**：`schema.sql:20` 与 `:49-50` 仍是无注释的裸列/裸表（`current_streak INT DEFAULT 0`；`learning_log` 只写 "Learning log (session-level records)"），本批的「零写入/废弃」标注只落在 Java DDL 侧。schema.sql 自述为「文档镜像」（`:85-87`），P3-02/P3-03 的废弃口径未镜像过去。与既往 C-8（schema.sql 索引文档缺口）同类，建议回写注释。

## 5. 口径文档（P3-02）— PASS

四处口径均已写清，且诚实标注了数据源固有属性：

| 位置 | 内容 | 判定 |
|------|------|------|
| `DatabaseManager.java:1177-1181` | `DailyStats` record javadoc：「sessions 口径（P3-02）：学习日次 = 当日 player_sessions 行数（每人每日一行）」 | 清晰 |
| `DatabaseManager.java:1225-1231` | `getTotalSessions` javadoc：原查 learning_log（历史遗留、全库零写入）→ player_sessions；口径「总学习日次」 | 清晰 |
| `DatabaseManager.java:1432-1438` | `getLast7DaysStats` javadoc：明示「每人每日一行，故每日 sessions 数值上等于当日 players」 | 清晰且诚实（未回避两序列重合） |
| `StatisticsHttpServer.java:329` | 消费点注释「口径 = 累计学习日次」 | 一致 |

口径从「session 数」改为「学习日次」是本批的语义变更，javadoc 已在类型与两个方法三级说明，消费点亦标注。PASS。

## 6. 范围外发现（只上报，不修）

| # | 严重 | 位置 | 现象 |
|---|------|------|------|
| O-1 | 低 | `gui/LearningCalendarGUI.java:15` | `import java.time.temporal.ChronoUnit;` 未使用（全文件仅出现在 import 行；`DayOfWeek`/`LocalDate` 有用）。该文件本批被 C-7 触碰，属 import 卫生残留 |
| O-2 | 低 | `core/progress/ProgressManagerImpl.java:30-65` | `getPlayerProgress`（本批 P3-03 修改的 `loadStreakData` 的唯一调用方）在插件内**零调用点**（grep 仅命中定义处）——仅经 `LetsLearnEnglishAPI.getProgressManager()` 对外暴露。故 P3-03 对 `ProgressManagerImpl` 的修复**对插件自身零实效**，仅对 API 消费者生效；`%lle_streak%` 那半才是活的。CHANGELOG 表述「%lle_streak% 与 ProgressManagerImpl 改走 checkin_data」技术上属实但需知悉命中面 |
| O-3 | 低（新引入） | `LetsLearnEnglish.java:557` → `DailyChallengeManager.checkAndAssign` → `ensureWordChallengesAssigned` → `WordBank.getAllWords()`（`WordBank.java:225-227` 为可变 `ArrayList` 的 `unmodifiableList` 视图） | F2-11 把 `checkAndAssign` 首次推入异步线程。主线程 `AdminCommandHandler.addWord`（`:256`/`:542` → `WordBank.addWord` `:194-197`）并发时可对同一 `words` 列表读/写 → 理论 `ConcurrentModificationException` / 撕裂读。概率极低（须管理员恰在午夜分配瞬间加词），但为**真实新增的跨线程数据面**；本批注释「线程干净已逐一核实」只覆盖 Bukkit 世界 API，未覆盖此面。C-3 的 `getTodayWordProgress`（`getTodayWordProgress`→同链）同源 |
| O-4 | 低 | `gui/DailyChallengeGUI.java:70 vs :78` | 同方法内 `playerId` 已捕获（:70），异步段却直接传 `player` 对象给 `getTodayWordProgress(player)`（:78，内部仅用 `getUniqueId()`）。功能正确（javadoc 已说明），但捕获口径不统一；建议传 `playerId` 与 C-1 修法（异步段只吃值）一致 |

## 不确定项

1. **O-3 是否属「本批引入」的判定依据**：确认 `checkAndAssign` 在本批前仅由主线程调用（`PlayerJoinListener.java:93`、`AIChatManager.java:626` 经 `updateProgress`，均主线程）。若另有未识别的主线程外调用点，则 O-3 为存量面——已 grep 全部调用点，未发现。仓库非 git 仓库，无法用 diff 二次确认改动边界。
2. **C-7 的 `ChronoUnit` 未用 import（O-1）是否本批引入**：无 git 通道无法判定，可能是 R-08（日历网格锚点修复）期的残留。因不在编排者列出的 6 个审查维度内，归「范围外发现」，不影响判定。
3. **`DailyStats.sessions` 字段名**：字段仍名叫 `sessions` 而语义已是「学习日次」，javadoc 在类型级说明、无字段级 javadoc。对只读字段名的调用方（`StatisticsHttpServer.java:337`）无歧义风险，未列问题行。

## 结论

五条修复的架构姿态成立：F2-11 两段式依赖面零增长且自续语义保持（finally 续排）；两 GUI 的 openAsync 彻底断开同步后门（`open(Player)` 纯委托，无残留同步路径——grep 确认三处调用方全改 `openAsync`，无 `.open(player)` 直调 `DailyChallengeGUI`/`LearningCalendarGUI`）；P3-02/P3-03 的数据源切换在正确层、正确方向、零 schema 破坏，口径文档三级写清。**PASS**。范围外 4 项（O-1~O-4）建议入 dev-findings 挂账。
