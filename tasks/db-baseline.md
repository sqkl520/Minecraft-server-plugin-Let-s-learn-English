# 数据库基线 (Database Baseline)

> Phase 0 基建流 C 产出。本文仅做**只读审计 + 方案记录**：真正的 `player_name` 写入与执行器改造留到 Phase 1。
> 行号基于本次源码快照（版本 `1.0.7-SNAPSHOT`，日期 2026-10-10），后续重构可能漂移。
> 相关文件：`src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java`、`src/main/resources/schema.sql`。

---

## 1. 背景与范围

- 已知问题 **M16**：所有查询/更新在异步线程共用**同一条 JDBC Connection**（SQLite 默认）。SQLite 单连接不允许并发读写，
  会抛 `SQLITE_BUSY`，或在「读的同时被另一线程写/提交事务」时读到错乱结果。
- 本批次（Phase 0）只做**止血**：把 `DatabaseManager` 内部所有访问共享 Connection 的查询/更新方法，统一在
  `connectionLock` 上 `synchronized`，保证「同一时刻只有一个线程在用这条连接」。
- 硬约束：**只改并发排序，不改任何功能结果**。

---

## 2. M16 止血现状（锁口径）

### 2.1 锁对象与入口
- `private final Object connectionLock = new Object();` — `DatabaseManager.java:33`
- `getConnection()` — `DatabaseManager.java:255`
  - **快路径**（`:257-264`）无锁，仅返回连接引用（`connection` 为 `volatile`）。它只读引用、不开 Statement，因此不需要加锁。
  - **慢路径**（`:267`）在 `connectionLock` 上串行，供首次/重连时初始化连接，防止并发初始化。

### 2.2 加锁覆盖
- 全类共 **37 处** `synchronized (connectionLock)`（其中 1 处位于 `getConnectionLock()` 的 javadoc，实际加锁点 36 处）。
- 覆盖方法（均在 `connectionLock` 上串行）：`initialize`、`executeUpdate`、`insertChatHistory`、`getChatHistory`、
  `getChatSession`、`getChatSessions`、`getSessionPreview`、`getChatHistoryCount`、`cleanupOldChatHistory`、`getTopCoins`、
  `getTopWordsLearned`、`getTopCombo`、`getDailyChallengeWords`、`assignDailyChallengeWords`、`markDailyChallengeWordComplete`、
  `isDailyChallengeWordsCompleted`、`getDailyChallengeWordsProgress`、`incrementWrongCount`、`getWrongCount`、`getDifficultWords`、
  `removeDifficultWord`、`decrementWrongCount`、`clearWrongCount`、`getTotalWordCount`、`getTodayActivePlayers`、`getTotalSessions`、
  `getTotalCoinsEarned`、`getTotalCoinsSpent`、`adjustCoins`（`recordCoinsEarned/Spent` 的公共实现）、`getCoinsEarned`、
  `getLast7DaysStats`（两段独立加锁）、`getCheckInData`、`updateCheckIn`、`resetCheckInStreak`、`shutdown`。
- 辅助方法 `addColumnIfMissing` 经由 `executeUpdate` 间接加锁。

### 2.3 锁粒度
- **粒度 = 整条 Connection（一个全局 monitor）**。等价于「任意时刻只有一个线程在跑 SQL」。
- 这不是行级/表级锁，是**连接级全串行**——这是 M16 最直接的止血方式，也正是 SQLite 单连接语义所要求的。

### 2.4 死锁审查（结论：无）
- `incrementWrongCount`（`:738`）先执行完自己的 `synchronized` 块（写 UPSERT），**释放锁后**再调用 `getWrongCount`（`:758`），
  两段独立加锁，**不嵌套**。
- `initialize()` 由 `getConnection()` 慢路径**持锁**调用（`:270`），而 `initialize()` 自身也 `synchronized (connectionLock)`
  —— 同线程**重入**（Java monitor 可重入），`initialize → createTables → executeUpdate → synchronized` 同样重入，**不会死锁**。
- 全程未出现「持锁时回调外部代码」的写法。

### 2.5 本批次补齐项
- **已补齐**：`initialize()`（`:46`）原先未加锁，却是唯一会**改写 `connection` 字段**的方法，与 `getConnection()` 慢路径存在
  理论竞态。现已包进 `synchronized (connectionLock)`（重入安全，功能零变化）。补齐后，`getConnectionLock()` javadoc 中
  「本类所有触碰共享连接的方法都在锁内」的说法才真正成立。

---

## 3. schema.sql 与代码 CREATE TABLE 对齐报告

**真源说明**：`schema.sql` **从不被代码读取**（全项目 `grep -rn "schema.sql" src` 无匹配），仅是参考文档；
运行时表结构以 `DatabaseManager.createTables()`（`:87`）为准。

逐表逐列比对（代码 `createTables` ↔ `schema.sql`）：

| # | 表 | 列/约束 | 对齐 |
|---|----|--------|------|
| 1 | `player_data` | uuid, player_name, total_words_learned, total_sessions, total_score, total_coins_earned, total_coins_spent, current_streak, longest_streak, last_login, created_at, updated_at（PK uuid，12 列） | 一致 |
| 2 | `word_progress` | id, uuid, word, mastery, correct_count, incorrect_count, last_review_time, review_count + UNIQUE(uuid,word) | 一致 |
| 3 | `achievements` | id, uuid, achievement_id, unlocked_at + UNIQUE(uuid, achievement_id) | 一致 |
| 4 | `learning_log` | id, uuid, session_type, difficulty, words_count, correct_count, score, started_at, ended_at | 一致 |
| 5 | `player_sessions` | id, uuid, session_date, words_count, correct_count, score, mode + UNIQUE(uuid, session_date) | 一致 |
| 6 | `player_profiles` | uuid(PK), age, show_answers, show_results, show_in_square | 一致 |
| 7 | `player_achievements` | uuid, achievement_id, unlocked_at + PK(uuid, achievement_id) | 一致 |
| 8 | `daily_challenges` | uuid, challenge_date, challenge_id, progress, completed, claimed + PK(uuid, challenge_date, challenge_id) | 一致 |
| 9 | `daily_challenge_words` | id, player_uuid, challenge_date, word_id, completed, completed_at + UNIQUE(player_uuid, challenge_date, word_id) | 一致 |
| 10 | `ai_chat_history` | id, player_uuid, session_id, role, content, timestamp（+ 索引 `idx_chat_player_time`、`idx_chat_session`） | 一致 |
| 11 | `difficult_words` | player_uuid, word, wrong_count, added_at + PK(player_uuid, word) | 一致 |
| 12 | `checkin_data` | player_uuid(PK), last_checkin_date, streak_count | 一致 |

**结论**：
- **无字段漂移、无缺列、无缺索引。** `schema.sql` 是代码建表语句的忠实文档（仅用 `{prefix}` 占位）。
- **本批次未改动 `schema.sql`**（无可对齐项）。
- 文档级提示（非 bug）：`schema.sql` 中每个自增 `id` 固定写 `AUTOINCREMENT`（SQLite 方言），而代码按 `isSQLite()`
  在 `AUTOINCREMENT` / `AUTO_INCREMENT` 间分支（`:110/124/134/148/192/204`）。作为文档可接受。
- 迁移补列说明：`createTables` 对 `player_data` 额外调用 `addColumnIfMissing(total_coins_earned/total_coins_spent)`（`:105-106`），
  用于旧库补列——这两列**已**在 `CREATE TABLE` 与 `schema.sql` 中，属幂等迁移，不构成漂移。

---

## 4. `player_name` 写入方案（**仅方案**，本批不实现）

### 4.1 现状（问题）
- 列 `player_data.player_name` 存在，但**全代码库无任何写入点**（`grep -rn "player_name" src/main/java` 仅命中读取与按名查询）。
- 读取方：
  - `DatabaseManager.getTopCoins`(`:513`)/`getTopWordsLearned`(`:542`)/`getTopCombo`(`:571`)。
  - `PlayerDataStore.getRankedPlayers`(`:742`)/`getRankedPlayersForPeriod`(`:794`)。
  - `AdminCommandHandler.resolvePlayerUUID`(`:80-82`)：`SELECT uuid FROM ...player_data WHERE player_name = ?`。
- 读侧**已具备 uuid 回落**：`name == null || isEmpty()` 时回落 `uuid.toString()`
  （`DatabaseManager.java:523/552/581`；`PlayerDataStore.java:775-777、827`）。
- 后果：排行榜玩家名恒显示为 uuid；`AdminCommandHandler` 按名查库**恒空**，导致「按名重置离线玩家」只能命中在线玩家
  （`Bukkit.getPlayer(name)` 分支）。

### 4.2 建议方案（Phase 1 落地）
- **写入方法（首选）**：`PlayerDataStore.savePlayerData(UUID)`（`PlayerDataStore.java:340`）里对 `player_data` 的那条 UPSERT
  （`:374-376`）。把 `player_name` 加进 **INSERT 列清单** 与 **`ON CONFLICT(uuid) DO UPDATE SET ...` / `ON DUPLICATE KEY UPDATE ...`**，
  即 **UPSERT 列 `player_data.player_name`**。优点：与现有统计写入同一事务、同一 `PreparedStatement`，无额外交互。
- **名字来源（关键约束）**：必须**主线程**取 `Player.getName()`。`savePlayerData` 只吃 `UUID`、不保证在主线程，
  因此需要在主线程钩子（`PlayerJoinEvent`，或 `loadPlayerData` 的调用点）把 `player.getName()` 缓存进一个
  `Map<UUID,String>`（复用 `PlayerDataStore` 现有缓存或 `PlayerStats`），`savePlayerData` 从缓存取值写入。
  **禁止在异步 save 内直接调 Bukkit API**（`PlayerDataStore.getPlayerName`(`:723-729`) 走 `getOfflinePlayer`，明确只能在主线程）。
- **写入方法（可选替代）**：在 `DatabaseManager` 新增 `updatePlayerName(UUID, String)`，SQL 形如
  `INSERT INTO prefix_player_data (uuid, player_name, updated_at) VALUES (?,?,?)
   ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name`（MySQL 侧换 `ON DUPLICATE KEY UPDATE`），
  在 `PlayerJoinEvent` 主线程直接调用。优点：写入留在 `DatabaseManager`（本流文件），不侵入 `PlayerDataStore` 的保存事务。
- **读侧**：**保持现有 uuid 回落不变**，以兼容尚未写过名字的历史行。

### 4.3 与「按名查询」配套
`AdminCommandHandler.resolvePlayerUUID` 依赖 `player_name` 才有意义；写入补齐后，该方法对离线玩家才能生效。

---

## 5. 评估：`synchronized` 串行化 是否够用？是否升级单线程执行器？

### 5.1 `synchronized` 止血的优点
- 把并发度压到 1，吞吐上限 = 单连接串行——恰好吻合 SQLite 单写语义，能消除 `SQLITE_BUSY` 与跨线程事务交叉。
- 零行为变化、改动最小，1.0.7 上线风险极低。

### 5.2 `synchronized` 的局限
1. **调用线程阻塞**：持锁期间其他线程（主线程、统计 HTTP 的 Netty 线程、异步任务）互相等待。`getLast7DaysStats`
   这类多段查询、`cleanupOldChatHistory` 这类批量删除会**拖住所有 DB 调用**。
2. **无公平/顺序保证**：JVM monitor 不保证 FIFO。
3. **共享 monitor 跨类复用**易误用（`getConnectionLock()` 暴露给外部，见 §6）。
4. **不解决外部绕锁**：执行器/锁只覆盖「经过 `DatabaseManager` 的访问」；外部类直接 `getConnection()` 仍绕过。

### 5.3 建议
- **1.0.7：维持 `synchronized` 止血**（正确、低风险）。是否足够取决于压力——单服几十人、DB 调用不频繁时够用。
- **Phase 1+：建议升级为单线程 DB 执行器**（`SingleThreadExecutor` 串行所有读/写，返回 `CompletableFuture`）：
  调用方**永不持锁阻塞**，主线程不再因 DB 卡 tick，天然有序、可设队列上限、可优雅停机。
- **升级前提**：必须同时把外部类对 `getConnection()` 的裸用法收编（改走执行器 API，或至少包 `synchronized(getConnectionLock())`），
  否则「执行器 + 裸连接」仍是两个并行 actor。可选措施：将 `getConnection()` 降级为 `private` 或标注 `@Deprecated`。
- **触发信号**：一旦出现**主线程直接调 DB**、或统计接口**高频轮询**，应尽快上执行器。

---

## 6. 额外发现（未修复，超出本流文件范围）

### 6.1 外部类绕过 `connectionLock`（M16 止血的**残余缺口**）
以下位置仍直接 `plugin.getDatabaseManager().getConnection()` 并自行执行 SQL，**未包** `synchronized(getConnectionLock())`，
与 `DatabaseManager` 的加锁方法可并行，M16 未完全闭合：
- `PlayerDataStore.java:83, 107, 130, 349, 671, 688, 746, 802`
- `DailyChallengeManager.java:107, 135, 162, 203, 229, 250, 269, 417`
- `AchievementManager.java:63, 146, 265`
- `AdminCommandHandler.java:56, 83`
- `ProgressManagerImpl.java:42`
- `LLEPapiExpansion.java:121`
- `LetsLearnEnglish.java:384, 389`（自动备份里的 `PRAGMA wal_checkpoint` / `quick_check`）

**最严重的一处**：`PlayerDataStore.savePlayerData`(`:349-393`) 在共享连接上 `conn.setAutoCommit(false)` / `commit()`，
若与另一线程（同样共享该连接）并发，**A 线程的 `setAutoCommit(false)` 会把 B 线程的语句卷进 A 的事务**，造成跨线程事务污染/错乱。
（`PlayerDataStore` 为 Phase 1 owner，本流未改动，仅记录。）

### 6.2 `AdminCommandHandler.resolvePlayerUUID` 按名查库恒空
- 位置：`AdminCommandHandler.java:80-82`。
- 现象：`SELECT uuid FROM ...player_data WHERE player_name = ?` 永远返回空（`player_name` 无写入点，见 §4）。
- 根因：`player_name` 从未落库。→ 修复方式即 §4 的写入方案（Phase 1）。

### 6.3 `getConnection()` 快路径的 TOCTOU（轻微）
- 位置：`DatabaseManager.java:257-264`。
- 现象：快路径无锁读 `connection` 并调 `isClosed()`；若此时另一线程正在 `shutdown()` 关闭/重连，存在极窄竞态窗口。
- 影响：可忽略（正常运行时连接不变），仅记录。

### 6.4 `initialize()` 直接赋值连接且缺少幂等保护（已就地加固）
- 位置：`DatabaseManager.java:46`。
- 现象：直接调用会覆盖旧 `connection` 而**不关闭**旧连接（潜在连接泄漏）；本批次已将其纳入锁内，但**未**增加「先关旧连接」逻辑。
- 影响：仅当运行期被重复直接调用时才触发；正常仅 `onEnable` 调一次。→ 记录，不扩范围。
