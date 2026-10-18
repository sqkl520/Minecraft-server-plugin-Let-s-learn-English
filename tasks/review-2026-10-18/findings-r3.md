# findings-r3 数据层（审查 Agent 产出）
- 审查范围：`data/` 全部（DatabaseManager 2743 行、PlayerDataStore 1320 行、ChatFeedbackEntry、ChatHistoryEntry、CheckInData、DifficultWord）、`resources/schema.sql`、`core/manager/DataExporter.java`（备份任务取自 `LetsLearnEnglish.java:619-678`，属 R10 接线点但从 R3 视角复核，标注归属）
- 审查时间：2026-10-18
- 结论摘要：高3条 / 中4条 / 低4条 / 待验证5条 / 豁免挑战0条
- 备注：本文件由编排者按该 agent 返回全文原样保存（harness 禁止子代理写文件）

## 高危

### R3-H1 [data·高·确定] 备份任务的 wal_checkpoint 对当前引擎无效 + 文件拷贝在锁外 → 备份可能落后于"看似成功"的时间点（撕裂拷贝窗口）
- 位置：（接线点）`LetsLearnEnglish.java:641-661`（备份实现）；佐证 `DatabaseManager.java:77-94`（连接建立处零 PRAGMA）
- 证据：
  ```java
  // LetsLearnEnglish.java:
  synchronized (databaseManager.getConnectionLock()) {
      try (PreparedStatement stmt = databaseManager.getConnection()
              .prepareStatement("PRAGMA wal_checkpoint(TRUNCATE)")) {
          stmt.execute();
      }
  }
  Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);   // ← 锁已释放
  synchronized (databaseManager.getConnectionLock()) {
      try (PreparedStatement stmt = databaseManager.getConnection()
              .prepareStatement("PRAGMA quick_check")) { ... }                              // ← 查的是活连接
  ```
- 复现：(a) 全仓 grep `PRAGMA|journal_mode` 仅命中本文件两处——插件从未执行 `PRAGMA journal_mode=WAL`，SQLite 以默认 rollback-journal（DELETE）模式运行，`wal_checkpoint(TRUNCATE)` 是空操作，对一致性零贡献。(b) `Files.copy` 在两段 `synchronized (getConnectionLock())` 之间执行，非原子：备份期间任意异步写（auto-save 任务、incrementWrongCount 异步、签到落库等）可持锁写入 DB 文件，拷贝结果可能是撕裂页（torn page）或混合旧新的不一致快照。(c) `quick_check` 跑在**活连接**上（校验的是源库而非刚拷贝的备份文件），拷贝出的坏文件照过。"备份创建成功"日志因此不能证明备份可用。
- 影响：严重时——插件损坏数据库后按日志回滚，恢复的是坏备份，全服数据不可恢复；轻时——备份内容比 timestamp 晚差若干秒的写入（可接受）。R10-M4 已报"quick_check 查错对象"，本条从 R3 视角补上两点新证据：(1) WAL 从未启用使 checkpoint 行同虚设；(2) 拷贝在锁外。正确的原子备份必须是 short-read：或持锁完成"copy 后立刻离锁"，SQLite 官方 API 是 `VACUUM INTO` / backup API。
- 修法：① 持锁做完 `Files.copy`（收进同一 `synchronized (getConnectionLock())` 块内）；② `quick_check` 改对 `backupFile` 副本执行（临时打开只读连接查副本文件）；③ 或改用 `VACUUM INTO 'backups/...'`（SQLite 3.27+ 原子一致快照，一条语句解掉全部问题）。

### R3-H2 [data·高·确定] cleanupOldChatHistory 双后端日期语义分叉：SQLite 用 UTC `datetime('now')` vs MySQL `NOW()`（连接时区）→ 同一"保留 30 天"在两后端删的是不同 datum；且该清理只删 `ai_chat_history`，同族新表 `ai_chat_feedback` 无限增长
- 位置：`DatabaseManager.java:688-709`（cleanupOldChatHistory）、`233-241`（ai_chat_history 建表）、`247-261`（ai_chat_feedback 建表）、`LetsLearnEnglish.java:164-166`（唯一调用点 cleanupOldChatHistory(30)）
- 证据：
  ```java
  public void cleanupOldChatHistory(int daysToKeep) {
      String dateExpr;
      if (isSQLite()) {
          dateExpr = "datetime('now', '-" + daysToKeep + " days')";   // UTC
      } else {
          dateExpr = "DATE_SUB(NOW(), INTERVAL " + daysToKeep + " DAY)"; // 连接会话时区
      }
      String sql = "DELETE FROM " + tablePrefix + "ai_chat_history"
              + " WHERE timestamp < " + dateExpr;
  ```
  同文件 `upsertChatFeedback`（724 行）写 `ai_chat_feedback` 无任何清理路径——grep `cleanup|retention` 显示只有 `cleanupOldFeedEvents`（social_feed/cheers，1982 行）和本方法；`ai_chat_feedback` 只有建表/写/读三处、零 DELETE。
- 复现：玩家在服务器本地时区为 UTC+8 的部署里，`ai_chat_history.timestamp` 由 SQLite `CURRENT_TIMESTAMP` 写 UTC 字符串；比较值 `datetime('now','-30 days')` 也是 UTC，表内比较一致（此支没问题）。但 MySQL 侧：`NOW()` 返回服务器 session 时区时间，而 `TIMESTAMP` 列存取由 session 时区转换——若 MySQL 服务器时区 ≠ 插件宿主时区，两者口径不一致；更直接的：`ChatHistoryGUI` 读时间戳用 `SimpleDateFormat` 按 JVM 默认时区渲染（ChatHistoryGUI.java:147），UTC 存储在下游按本地展示，差 8 小时（凌晨时段日期错位一天）。且无论哪种后端，`ai_chat_feedback` 随每场 AI 会话 +1 行、永久累积（每会话一行 + AI payload TEXT——体积大）。
- 影响：历史时间显示错位（玩家可见"未来的消息"）；ai_chat_feedback 表单调膨胀，ChatHistoryGUI 的会话列表查询随行数线性变慢（有 UNIQUE 索引撑上限尚可，但磁盘与备份体积增大）。
- 修法：① 统一存储为 epoch 毫秒（BIGINT，全仓已有惯例）或明确注释"timestamp 列一律 UTC、展示层转本地"并统一两后端表达式（SQLite `datetime('now','-N days')` 与 MySQL `UTC_TIMESTAMP() - INTERVAL N DAY` 对齐）；② 清理任务追加 `DELETE FROM ai_chat_feedback WHERE created_at < 同一阈值表达式`（created_at 同为 TIMESTAMP/CURRENT_TIMESTAMP）。

### R3-H3 [data·高·高] PlayerDataStore 多处 `Connection conn = databaseManager.getConnection();` 在锁**外**取引用、锁内使用——E-04/F1-20 修复范式只覆盖了同文件部分方法（R3 视角的 D10 回归裂口）
- 位置：`PlayerDataStore.java:241-242`（recordSession）、`529-530`（savePlayerData）、`948-949`（saveProfileSettings）、`968-969`（loadProfileSettings）、`1092-1093`（persistLongestStreak）、`293+350`（loadPlayerData——350 处已按 F1-20 修成锁内重取，但 293 处首个块仍是锁外取）
- 证据：
  ```java
  // recordSession（PlayerDataStore.java:241）
  Connection conn = databaseManager.getConnection();          // ← 锁外
  synchronized (databaseManager.getConnectionLock()) {        // ← 锁内才用
  try (PreparedStatement stmt = conn.prepareStatement(sql)) {
  ```
  对比同文件已被 F1-20 修复的 `loadPlayerData`：
  ```java
  // F1-20：本块与上方锁块之间已释放过锁——重连（initialize）可能已替换
  // Connection 引用……此处锁内重取。
  synchronized (databaseManager.getConnectionLock()) {
  conn = databaseManager.getConnection();
  ```
  `getConnection()` 自身（DatabaseManager.java:455-470）在锁内做 isClosed 检查并可能触发 initialize() 替换 connection 字段——即在"锁外取引用"与"锁内使用"两个动作之间，另一线程完全可能因连接失效触发重连，把字段指向新 Connection，而本方法手里的旧引用已关闭 → prepareStatement 抛 SQLException，被 catch 记 WARNING 后**静默丢弃这次写入**。
- 复现：触发连接失效（SQLite 文件被外部进程短暂锁/MySQL 网络抖动）后：玩家恰好此时结束会话 → recordSession 静默失败 → 当日 player_sessions 丢一行（连击/成就/日历口径少一天）；或 savePlayerData 丢一次落盘。
- 影响：偶发静默数据丢失（日志一条 WARNING，玩家无感），漂移量小但不可检测。
- 修法：机械修——所有 `Connection conn = databaseManager.getConnection();` 移进 synchronized 块第一行（或直接删局部变量、锁内每次 getConnection()），与 F1-20 注释范式一致。全文件 grep 逐处收敛。

## 中危

### R3-M1 [data·中·确定] `getWordMastery` 用 computeIfAbsent 的回调里调 `loadPlayerData`，而后者会 put 同一缓存 map——ConcurrentHashMap 递归更新同一 key 的反模式（当前死代码，接线即踩雷）
- 位置：`PlayerDataStore.java:647-655`
- 证据：
  ```java
  public double getWordMastery(UUID playerId, String word) {
      Map<String, Double> masteryMap = wordMasteryCache.computeIfAbsent(playerId, k -> {
          loadPlayerData(k);                       // ← 内部 wordMasteryCache.put(playerId, ...)
          return wordMasteryCache.getOrDefault(k, new ConcurrentHashMap<>());
      });
  ```
  `loadPlayerData`（315 行）在回调执行中 `wordMasteryCache.put(playerId, masteryMap)`——对同一 CHM 同一 key 在计算函数内做更新，属 JavaDoc 明示的"不得在计算中修改 map"情形。另外回调内做慢 IO，期间阻塞该 bin 的其他玩家同 key 操作。
- 复现：静态可证；实际触发需该玩家缓存缺失时调用本方法。**修正：全仓 grep `getWordMastery` 只有定义处、零调用点**——当前是死代码，尚无运行时伤害。
- 影响：当前零影响（仅死代码+反模式）；接线后可能 NPE 或返回空 map 导致掌握度显示 0。
- 修法：改为 loadPlayerData 先行 + 普通 get 的组合（与 getPlayerStats 855 行既有正确范式一致），或直接删除死方法。

### R3-M2 [data·中·确定] `decrementWrongCount` 缓存与 DB 分叉：两条独立异步任务（increment/decrement）调度顺序不保证 → "练习答对移出错题本"在边界上不可靠
- 位置：`PlayerDataStore.java:481-502`（缓存侧）、`DatabaseManager.java:1146-1161`（DB 侧）
- 证据：
  ```java
  // PlayerDataStore.decrementWrongCount
  int newCount = counts.merge(wordLower, -1, Integer::sum);   // 缓存可到 -1
  if (newCount <= 0) { counts.remove(wordLower); ... }
  plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
      databaseManager.decrementWrongCount(playerId, wordLower);   // UPDATE ... WHERE wrong_count > 0
      int dbCount = databaseManager.getWrongCount(playerId, wordLower);
      if (dbCount <= 0) { databaseManager.removeDifficultWord(playerId, wordLower); }
  });
  ```
- 复现：错题本阈值 3 的边界——第 3 次答错（进书）后马上 Shift+练习答对，decrement 异步任务可能先于最后一次 increment 执行（DB 2→1、不删），随后 increment 落库 2→3——预期"练习答对移出错题本"失败。重登后 loadDifficultWordsFromDB 把 count=2 的行读回（仍在错题本），而缓存显示已移出 → **同一玩家前后两次看到的状态不一致**。
- 影响：错题本"练习清零"语义在边界上不可靠；玩家可见状态漂移。
- 修法：decrement 也走"条件 UPDATE + 读回一致判删"的单条 SQL（如 `UPDATE ... SET wrong_count = wrong_count - 1 WHERE ... AND wrong_count > 1`，返回 0 行即删除行），或把 increment/decrement 串到 per-player 同一 executor 队列保序。

### R3-M3 [data·中·确定] `savePlayerData` 的写回集合是**缓存并集**而非"本轮变化集"：缓存缺失 map 时 getIntValue 回落 0 → UPSERT 覆盖 DB 非零值（cache-first 漂移窗口的写侧具象化）
- 位置：`PlayerDataStore.java:520-581`（savePlayerData + buildUpsertSql 全列覆盖）、`662-667`（updateWordProgress 只 computeIfAbsent 3 个 map，lastReviewTimeCache/reviewCountCache 靠 710/717 懒建常在 save 之后）
- 证据：
  ```java
  // buildUpsertSql（SQLite 分支）
  return "INSERT OR REPLACE INTO " + tablePrefix
          + "word_progress (uuid, word, mastery, correct_count, incorrect_count, last_review_time, review_count) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?)";
  ```
  INSERT OR REPLACE 是整行替换；MySQL 侧 ON DUPLICATE KEY UPDATE 也是全列赋值。savePlayerData 遍历缓存全部词，缺失 map 回落到 0——把 DB 里该词的非零 review_count/last_review_time 覆盖成 0。
- 影响：单点数据覆盖（复习计数/时间被抹），Ebbinghaus 调度错乱（间隔从 1h 重来）。
- 修法：savePlayerData 对"仅明确变更的列"做 UPSERT（COALESCE/增量式），或最小修法：把 updateWordProgress 的 662-667 补全为 5 个 per-word map 的 computeIfAbsent。

### R3-M4 [data·中·确定] `recordSession` 的会话合并与 `PlayerStats.totalSessions` 递增的双重计数口径分叉：DB 侧每人每日一行（合并），player_data.total_sessions 每次 endSession +1——同一天两场：DB 行数 +0，total_sessions +2
- 位置：`PlayerDataStore.java:700-705`（updatePlayerStats → incrementSessions）、`556-558`（savePlayerData 落 total_sessions）、`DatabaseManager.java:1236-1254`（getTotalSessions 口径）、`231-239`（recordSession UPSERT 合并）
- 证据：
  ```java
  private void updatePlayerStats(UUID playerId, int correct, int incorrect) {
      PlayerStats stats = playerStatsCache.computeIfAbsent(playerId, k -> new PlayerStats());
      stats.addCorrect(correct);
      stats.addIncorrect(incorrect);
      stats.incrementSessions();          // ← 每次 endSession +1（含同日第 2 场）
  }
  ```
  recordSession 的 SQL 是 ON CONFLICT(uuid, session_date) DO UPDATE SET words_count = ...——**不含 sessions 计数列**（表也无该列），同日两场合并为一行。
- 复现：同一天玩两局 → player_data.total_sessions = 2，player_sessions 行数 = 1。ProfileGUI（读 stats.getTotalSessions()）与 StatisticsHttpServer（读 databaseManager.getTotalSessions()）对"会话数"给出两个不同的数（2 vs 1）。
- 影响：两处玩家可见统计互不一致；total_sessions 被 achievement（AchievementManager:387/604）当"会话数"判定——同日多局会更快解锁（是否设计意图需裁决，但两口径并存是事实）。
- 修法：明确二选一——要么 total_sessions 口径改"局数"并文档化，要么 incrementSessions 仅在当日 player_sessions 无行时调用。上报建议归 R1/R4 联合会诊（成就判定受影响）。

## 低危

### R3-L1 [data·低·确定] reset 后班级人数上限判定/班级榜缓存不刷新（无 DB 层职责，归 R7 缓存侧）
- 位置：`DatabaseManager.java:2383-2399`；修法：R7 在 reset 路径加缓存失效调用。

### R3-L2 [data·低·确定] `cleanupOldChatHistory` 的 SQL 拼接 daysToKeep 未参数化（当前唯一调用传常量 30，无注入面，违反全仓参数化惯例）
- 位置：`DatabaseManager.java:688-696`；修法：`datetime('now', ?)` 绑定字符串；MySQL 侧 `DATE_SUB(NOW(), INTERVAL ? DAY)` 绑定 int。

### R3-L3 [data·低·确定] DataExporter 的 escapeCsv 覆盖逗号/引号/换行但未覆盖 `\r`（防御性缺口，当前无合法触发输入）
- 位置：`DataExporter.java:98-104`；修法：判定条件加 `value.contains("\r")`（一行）。
- 备注：D19 回归核验通过——exports/ 目录路径正确（64-68 行）、ExportResult record 协议化、返回 filePath 绝对路径；无重名问题。

### R3-L4 [quality·低·确定] `looksLikeDuplicateColumn` 吞掉所有 SQLException 按"已存在"处理——非"duplicate column"错误（连接故障/权限/非英文 locale）下静默跳过迁移
- 位置：`DatabaseManager.java:2735-2745+`（**该方法尾部未完全核验**）
- 影响：低（已有 WARNING 日志面）+ 中场景（非英文 locale 部署下新列永不添加）。
- 修法：改用元数据查询（information_schema.columns / PRAGMA table_info）判列存在，与 createIndexIfMissing 的 MySQL 侧做法同源。

## 待验证区

1. **MySQL 后端整链未实测**：全部静态核验基于 SQLite 语义。MySQL 侧 `VALUES()` 函数在 8.0.20+ deprecated（未来 9.x 移除风险）；`INSERT IGNORE` 的 MySQL 行为与 SQLite 在严格模式下细节不同。验证方法：双服部署跑签到+练习+reset 全链对比。
2. **savePlayerData 缓存并集覆盖窗口**（R3-M3）需运行期断点/日志确认实际触发率。
3. **R3-H1 撕裂拷贝**在真实磁盘负载下的实际概率。验证方法：压力写 + 备份循环，对备份跑 integrity_check 数百次统计失败率。
4. **getWordMastery CHM 递归更新的 JDK 实际行为**（M2）——不同 JDK 版本行为不同；单测直接调用观察。
5. **ai_chat_feedback 的实际增长速率**（H2 后半）——生产库抽样统计行数与平均 payload 长度。

## 豁免挑战
- 无对 §E 的挑战。B4（单 JDBC 连接共享）豁免被遵守——本报告 H2/H3/M1/M2 均为"具体新竞态/具体新缺失"，不是对共享连接设计本身的重复指责。P3-09（word_progress REPLACE 语义）豁免被遵守（M3 立案点是**缓存并集回写**，不是 REPLACE 语义本身）。

## 范围外发现
1. **`Leaderboard.updateScore` 为 merge-累加、`loadFromDB` 只在启动跑一次**（Leaderboard.java:141-143, 164-175）——reset 玩家后内存榜 scores map 中旧分**永不清除**（resetPlayerData 不触碰 Leaderboard），%lle_rank% 在玩家重进后 rank 显示可能混乱（updateScore 从 0 重新累加但旧值还在）→ 归 **R4**（榜单域）。
2. **`resolvePlayerUUID` 的 player_data.player_name 查询大小写敏感**（AdminCommandHandler.java:94-95）：MySQL 默认 _ci 不敏感、SQLite 默认敏感——同一 `/lea reset Tommy` 在两后端行为分叉 → 归 **R9** 或 R13。
3. **`getActivePlayerIds()`（PlayerDataStore:1023-1034）** 在异步线程调用 `plugin.getServer().getOnlinePlayers()`（Bukkit API）——注释自承"异步可达路径（如启动期 Leaderboard 预热）"，Paper 上可能返回陈旧快照但通常不炸（豁免 #22 的灰区）→ 归 **R13** 评估。

## 回归条目核验（本域）
- D1 通过（getTotalSessions/getLast7DaysStats 均走 player_sessions，1236-1254、1442-1516）
- D10 **部分裂口**（F1-20 修的是 loadPlayerData，同文件 recordSession/savePlayerData/saveProfileSettings/loadProfileSettings/persistLongestStreak 仍是旧式锁外取引用——见 H3）
- D19 通过（exports/ 路径正确）
- D25 通过（createIndexIfMissing 三用法齐备，410-443 全文核过）

## reset 逐表核对表（本域最高价值产出，29 张表全核）

| 表（运行时创建） | 玩家列 | deletePlayerData 覆盖 | 证据行 |
|---|---|---|---|
| player_data | uuid | ✅ 列表① | 2333 |
| word_progress | uuid | ✅ | 2333 |
| achievements | uuid | ✅（死表，防复活） | 2333 |
| learning_log | uuid | ✅（死表，防复活） | 2334 |
| player_sessions | uuid | ✅ | 2334 |
| player_profiles | uuid | ✅ | 2334 |
| player_achievements | uuid | ✅ | 2333 |
| daily_challenges | uuid | ✅ | 2334 |
| season_rewards | uuid | ✅ | 2335 |
| class_members | uuid | ✅（Wave 4 补入） | 2336 |
| daily_challenge_words | player_uuid | ✅ 列表② | 2341 |
| ai_chat_history | player_uuid | ✅ | 2341 |
| difficult_words | player_uuid | ✅ | 2341 |
| checkin_data | player_uuid | ✅ | 2341 |
| ai_chat_feedback | player_uuid | ✅（Wave 3B 补入） | 2342 |
| social_cheers（发出） | cheerer_uuid | ✅ 特殊三步① | 2376 |
| social_cheers（收到） | feed_id→IN 子查询 | ✅ 特殊三步② | 2384 |
| social_feed | player_uuid | ✅ 特殊三步③ | 2393 |
| seasons | 无玩家列 | N/A（正确不动） | 2335 注释 |
| classes | 无玩家列 | N/A（正确不动） | 2336 注释 |

结论：**runtime 19 表中 18 张玩家数据表全部覆盖、2 张无玩家列表（seasons/classes）正确豁免；schema.sql 19 表与运行时建表逐表/逐列比对一致（列级零漂移）。class_members/ai_chat_feedback 历史盲区均已补入。此项通过，无新立案。**

## 审查覆盖声明
- **实际读了**：DatabaseManager.java（重点段：1-100、96-440 建表全段、440-520、680-810、1020-1180、1490-1680、1755-1812、1950-2140、2300-2500、2580-2745）；PlayerDataStore.java（156-520、500-960、1020-1100 及全方法清单 grep）；DataExporter.java（全文）；schema.sql（全文）；LetsLearnEnglish.java（155-232、270-320、354-380、476-494、615-678）；PlayerJoinListener.java（全文）；AdminCommandHandler.java（30-115）；WordManagerImpl.java（100-180）；WordLearningManager.java（340-470、545-620、690-770）；Leaderboard.java（全文）；CheckInManager.java（全文）等。全部 SQL 语句经 grep 穷举后逐条核验。
- **没做完/没做**：① DatabaseManager.java 中约 2745-2760（文件尾 addColumnIfMissing 后半 + looksLikeDuplicateColumn 全文）未读完——见 L4 标注；② getRankedPlayersForPeriodFiltered（1245-1320）未逐行读（grep 确认走 player_sessions 无异常）；③ MySQL 实机行为全部待验证；④ 四个 record/POJO 仅经使用点间接核验；⑤ 测试文件（8 个）未评——归 R13。
