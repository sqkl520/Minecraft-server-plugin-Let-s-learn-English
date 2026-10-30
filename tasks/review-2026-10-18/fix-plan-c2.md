# Block C2 中危修复计划（fix-plan-c2）— review-2026-10-18

- 基线：HEAD=`300c699`（批 A/B 高危修复已提交；mvn verify 49/49 全绿）。版本保持 1.0.7-SNAPSHOT。
- 产出：Block C2 规划 Agent（只读源码）。规格一律以方法名/锚点文本定位；标有 **[待C1]** 的文件需在 C1 提交后复核行号（C1 触及：WordLearningManager、MiscGUIListener、WrongBookListener、ChatScenarioListener、LearnCommand、CheckInManager、ScenarioSelectGUI、ScenarioSelectListener、ChatFeedbackManager、AIChatManager、DuelManager、DuelGUI、DuelListener、WordLearningGUI、DailyChallengeGUI、LearningCalendarGUI）。
- 行号基准：当前工作树（300c699），仅作参考；与源码冲突以源码为准。

---

## A. 修复流划分与白名单互斥表（6 流，文件零重叠）

| 流 | 修复项 | 文件白名单 | 备注 |
|----|--------|-----------|------|
| C2-A 数据层 | R2-M2, R3-M1, R3-M2, R3-M3, R3-M4 + R9-M4 索引侧 | `data/PlayerDataStore.java`、`data/DatabaseManager.java`、`resources/schema.sql` | R9-M4 索引在此（跨流声明：C2-C 只做前缀守卫） |
| C2-B 社交档案 | R4-M1, R4-M2 + R13-M4(celebrationShownDates) | `listener/SocialListener.java`、`command/LearnCommand.java`[待C1]、`gui/LearningCalendarGUI.java`[待C1] | |
| C2-C 管理面 | R7-M1, R7-M2, R7-L5, R9-M1(服务侧/接线), R9-M2, R9-M4(守卫) | `core/season/SeasonManager.java`、`core/manager/AdminCommandHandler.java`、`core/manager/StatisticsHttpServer.java`、`core/manager/HotReloadManager.java`、`core/manager/DataExporter.java`、`command/AdminCommand.java` | R9-M1 依赖 C2-D 的 `LetsLearnEnglish.restartStatisticsServerIfNeeded()` |
| C2-D 配置调度 | R10-M1, R10-M2, R10-M3, R10-M5 + R13-M4(joinReminderSent) + R9-M1(LetsLearnEnglish 侧) | `LetsLearnEnglish.java`、`config/ConfigManager.java`、`resources/config.yml` | |
| C2-E GUI 基建 | R12-M1, R12-M2, R12-M3 | `gui/GUIUtils.java`、`gui/DailyChallengeGUI.java`[待C1]、`gui/LearningCalendarGUI.java`[待C1]、`gui/ConfirmationGUI.java`、`gui/WordBankSelectGUI.java`、`gui/ChallengeCompleteGUI.java`、`gui/AchievementWallGUI.java`、`gui/LeaderboardGUI.java`、`gui/EnglishSquareGUI.java` | DuelGUI 已由 C1 修，不在本流 |
| C2-F 线程与清理 | R13-M1, R13-M2, R13-M3 + R13-M4(playerDifficulties, dueWordCache) | `listener/PlayerJoinListener.java`、`core/ai/AIChatManager.java`[待C1]、`core/word/ReviewScheduler.java`、`gui/ChatHistoryGUI.java`、`listener/ChatHistoryListener.java`、`core/progress/Leaderboard.java` | |

**跨流依赖（执行顺序）**：C2-A（索引+表）与 C2-D（LetsLearnEnglish 方法）先落地；C2-C 的 `HotReloadManager` 调 C2-D 方法；C2-C 的 `AdminCommandHandler` 前缀守卫独立可做。LearningCalendarGUI 同时出现在 C2-B 与 C2-E——**同一文件跨两流**，执行时合并为同一执行者或串行（见 §B-C2B/C2E 标注）。

---

## B. 每流详细规格

**锚点复核记录**（已对 300c699 工作树逐条验证）：`getReviewScheduler` getter 链存在（WordManagerImpl:228，LetsLearnEnglish :368/:580 同式消费先例）；`decrementWrongCount` DB 侧现为 `WHERE ... AND wrong_count > 0`（:1240-1257）；`getKnownPlayerNames` 调用侧前缀守卫现为 `prefix.length() >= 1`（AdminCommand :361）；`createIndexIfMissing` 支持 MySQL 查重（:410-443）；`LetsLearnEnglish` 无 statistics getter（需新增）。

### C2-A 数据层

#### A1. R2-M2 今日 AI 轮次计数器持久化（新表方案）
**根因**：`todayAiRoundCache`（PlayerDataStore:62，内存 TodayCounter）是 daily-round-limit（默认 30）唯一判据源；`incrementTodayAiRound`（:145-147）只写内存；重启/换服/quit-rejoin（unloadPlayerData :822-824 会 remove 该条目）均清零 → 上限可无限绕过（API 成本面）。与批 A 的 R10-H1（挑战奖励已付台账持久化）同类成本面，采用同式持久化，**不**降级为 config 注释。
**改法（DatabaseManager.java）**：
1. `createTables` 内紧跟 `daily_word_reward_claims` 建表块（:230-240）之后新增（两后端同式）：
   ```sql
   CREATE TABLE IF NOT EXISTS {prefix}ai_round_counters (
     player_uuid VARCHAR(36) NOT NULL,
     round_date DATE NOT NULL,
     rounds INT NOT NULL DEFAULT 0,
     UNIQUE(player_uuid, round_date)
   )
   ```
2. 同步三处（批 A 教训，缺一即后建表盲区）：① `createTables`；② `resources/schema.sql`（沿 daily_word_reward_claims 条目 :123 后追加同 DDL）；③ `deletePlayerData` 的 `playerUuidTables` 数组（:2437 附近）补 `"ai_round_counters"`。
3. 新方法（照 markDailyWordRewardClaimed :1086 / incrementWrongCount 的锁风格）：
   - `public void incrementAiRound(UUID playerId, String date)`：SQLite `INSERT INTO {p}ai_round_counters (player_uuid, round_date, rounds) VALUES (?, ?, 1) ON CONFLICT(player_uuid, round_date) DO UPDATE SET rounds = rounds + 1`；MySQL `INSERT INTO ... VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE rounds = rounds + 1`；`synchronized (connectionLock)`；SQLException → WARNING（静默降级：最坏后果=重启可重复，与修复前持平）。
   - `public int getAiRoundCount(UUID playerId, String date)`：`SELECT rounds FROM ... WHERE player_uuid=? AND round_date=?`；无行 → 0；SQLException → 0 + WARNING（降级语义同上）。
**改法（PlayerDataStore.java）**：
1. `incrementTodayAiRound`（:145-147）改三层：
   - 取/建 `TodayCounter`；若该 counter 日期 ≠ 今日（或从未 seed），先 `seedAiCounter(playerId, counter)`：`int db = databaseManager.getAiRoundCount(playerId, today); counter.seed(today, db);`（读失败按 0 降级）。
   - `int v = counter.increment();`（内存立即生效）。
   - 持久化：`try { plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> databaseManager.incrementAiRound(playerId, today)); } catch (IllegalStateException ignored) {}`（**线程归属**：调用点 AIChatManager:953 在 `supplyAsync` 的 commonPool 链内 —— 与 R2-H1 修复纪律一致，绝不在 commonPool 直调 JDBC，统一走调度器异步；关服窗口静默弃）。
   - 返回 `v`。
2. `getTodayAiRound`（:152-155）改：cache miss 或 counter 日期 ≠ 今日 → 同步 `databaseManager.getAiRoundCount(playerId, today)` 并 `counter.seed(...)` 后返回 seed 值（**先例**：getConversationCount :161-168 同款"miss 时同步 loadPlayerData 再读"模式；本处仅一条 SELECT）。**线程归属**：读取点两处均在异步线程（canSendChat :455 = AsyncPlayerChatEvent 线程；dispatchChat :836 限流检查 = chatAsync 异步链），同步 DB 读持 connectionLock，可接受（与 getConversationCount 同面）。
3. `TodayCounter`（:99-123）增两方法（不改 increment/get 现有语义，答题计数侧 answer cache 不受影响）：`LocalDate date()` 访问器；`void seed(LocalDate d, int v)`（synchronized 双检，`date=d; count.set(v);`）。
4. `unloadPlayerData` 的 remove（:824）保留不动——quit/rejoin 后首次 `getTodayAiRound` 会从 DB 回读真值，正是本修复要闭合的绕过路径。
**旧行清理裁决：并入 `cleanupOldChatHistory`**（DatabaseManager :688-709，批 A 已在此方法追加 ai_chat_feedback 清理，先例在案）：追加 `DELETE FROM {p}ai_round_counters WHERE round_date < <30 天阈值>`——SQLite 用 `date('now', '-" + daysToKeep + " days')`（round_date 为 ISO TEXT，字典序可比）；MySQL 用 `DATE_SUB(CURDATE(), INTERVAL ? DAY)` 或同式字面量（跟随该方法既有拼接口径）。理由：与 R3-H2 同族"新表必须有清理者"教训；追加成本仅一条 DELETE。
**i18n**：无新键（仅 debug/日志）。
**回归验证点**：① 当日打满 N 轮 → 重启 → 再发消息 → `dialogue.daily-limit-reached` 仍出现（DB 回读值生效）；② quit→rejoin 同日 → 轮次不回零；③ 跨午夜（任务体懒清零 + seed 新日期）→ 次日正常重新计数；④ `/lea reset <player>` 后重进 → 计数归零（deletePlayerData 清表）；⑤ DB 写失败（模拟）→ 计数仍在内存生效（降级不崩）。
**风险与交互**：① `incrementTodayAiRound` 增了一次"每玩家每日首次"的同步 SELECT（seed）——位于异步线程，可接受；② 与 C2-A/A5 同文件（DatabaseManager）编辑需同执行者串行；③ `TodayCounter.seed` 与 `increment` 的日期竞态：seed 后到午夜 rollover 场景由 increment 内既有双检重置覆盖，seeded 值最坏差 1（防刷上限语义容忍）。**[待 C1]**：AIChatManager 被 C1 触碰，:953 锚点以"`plugin.getPlayerDataStore().incrementTodayAiRound(playerId)` 调用行"定位。

#### A2. R3-M1 getWordMastery 删死方法
**根因**：`getWordMastery`（PlayerDataStore :648-656）以 `computeIfAbsent` 回调内调 `loadPlayerData`（后者 put 同一 CHM 同一 key）= JavaDoc 明禁的递归更新反模式；当前零调用（findings R3-M1 已 grep 确认；R13-L1 死代码批亦列 :647）。接线后可能 NPE/空 map。
**裁决：删除方法本体**（与 R13-L1 死代码批同式）。理由：修复反模式（loadPlayerData 先行+普通 get，照 :851 getPlayerStats 范式）与删除成本相同，但保留无调用 API 无收益；且删除后 CHM 反模式整体消失，无需回归面。
**处置 R13-L1 交叉**：R13-L1 清单里的 `:647 getWordMastery` 条目由本项执行；若 R13-L1 批在他处执行，重复删除无害（执行时 re-grep 确认零引用即可）。`wordMasteryCache` 字段本身仍被 loadPlayerData/savePlayerData/updateWordProgress 使用，**不动**。
**回归验证点**：`mvn test` 全绿；全仓 re-grep `getWordMastery` 零命中（含测试）。
**风险**：极低（纯死代码删除）。

#### A3. R3-M2 decrementWrongCount 缓存-DB 竞态（原子 SQL 裁决）
**根因**：练习答对路径（PlayerDataStore :482-503）：缓存 merge -1（同步，主线程）后，异步任务做 `decrement → getWrongCount 读回 → ≤0 则 removeDifficultWord` 三段独立取锁——decrement 与读回之间并发 increment 落地（如第 3 次答错的增量写）会让读回 >0 而不删行；同时 increment/decrement 两异步任务执行序无保证。结果：缓存已移出、DB 仍在书 → 重登后词"复活"（状态漂移）。
**裁决：单条原子 SQL（核心修复）+ 接受残余乱序窗口（不引入 executor 队列）**。理由：确定性的"读回分裂"（三段取锁窗口）由原子 SQL 彻底消除；残余的"increment 任务整体晚于 decrement 任务"乱序窗口要求异步池对同一玩家的两个跨秒提交任务乱序执行，概率极低，且最坏结果=词留书（可重试清除，无数据损坏）；executor 保序队列方案会同时改动 increment 热路径（:438-444，每次答错都跑），收益/风险不成比。残余窗口在方法 javadoc 注明。
**改法**：
1. `DatabaseManager.decrementWrongCount`（:1240-1257）：SQL 的 WHERE 改 `wrong_count > 1`（**双后端同一 SQL**，纯标准 UPDATE 无方言差）：
   `UPDATE {p}difficult_words SET wrong_count = wrong_count - 1 WHERE player_uuid = ? AND word = ? AND wrong_count > 1`
   语义变更注释：返回 false = "计数已达 1（或行不存在），调用方应删行"。
2. `PlayerDataStore.decrementWrongCount`（:495-502）异步任务改：
   ```java
   plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
       if (!databaseManager.decrementWrongCount(playerId, wordLower)) {
           databaseManager.removeDifficultWord(playerId, wordLower); // 计数≤1 → 移出（幂等）
       }
   });
   ```
   删除 `getWrongCount` 读回调用。
3. 缓存侧（:484-494）**不需调整**：`merge(wordLower, -1, Integer::sum)` 后 ≤0 即 remove，缓存地板为 0；与 DB 新语义（>1 才减、==1 删行）逐值对齐（cache 3→2 ↔ db 3→2；cache 1→0 删 ↔ db 1→删）。**边界核对**：counts==null（玩家未加载）时缓存跳过而 DB 仍执行——与既有行为一致（DB 侧独立）；重复练习答对同词 → cache 与 db 同步各减一次，仍对齐。
**i18n**：无。
**回归验证点**：① 错 3 次进书 → 练习答对 1 次 → 重登仍在书（count 2）；再答对 1 次 → 移出且重登不复活；② Shift 练习答对（count=1 词）→ 立即移出，DB 行删除；③ 手动删除路径（removeDifficultWord 直调）不受影响；④ removeDifficultWord 对不存在行幂等。
**风险与交互**：[待 C1] 文件外（PlayerDataStore 非 C1 白名单，行号安全）；与 A4 同文件不同方法，无冲突。

#### A4. R3-M3 updateWordProgress 补全 5 map（最小修）
**根因**：`savePlayerData`（:537-548）按 masteryMap 全量遍历、7 列全写（INSERT OR REPLACE / ON DUPLICATE KEY UPDATE 全列赋值）；lastReviewTimeCache/reviewCountCache 缺失时 `getLongValue/getIntValue(..., word)` 回落 0 → 覆盖 DB 非零值（复习调度被清零）。`updateWordProgress`（:662-668）只曾确保 3 个 map（mastery/correct/incorrect），而 review 两 map 靠 :710/:717 懒建或 loadPlayerData 填充——晚于 save 的路径产生覆盖窗口。
**改法（已核 :662-668 实际行为）**：在 incorrectMap 的 computeIfAbsent 之后追加两兄弟调用：
```java
lastReviewTimeCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
reviewCountCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
```
**残余覆盖窗口核证（已做）**：① loadPlayerData :315-327 五 map 全填（异常路径 :326-327 也 putIfAbsent 保底）；② masteryMap 的创建点仅 updateWordProgress 与 loadPlayerData 两处 → 修复后**凡 savePlayerData 能被触达（masteryMap 非 null）的状态下，5 map 必同存**，逐词读到 0 的真实来源仅剩"该词确实无值"（与 DB 一致）；③ :712/:719 (`getLastReviewTimes/getReviewCounts`) 晚建不再构成窗口（save 遍历读的是查表结果，map 缺失时回 0 的问题已被 ① 的保底+本修复封住）。结论：最小修足够，**再加一层不需要**。
**i18n**：无。
**回归验证点**：① 有 review_count=3 的词 → 学习会话结算（updateWordProgress+save）→ 重登 review_count 仍 3；② 异常路径（模拟 load 失败）后 save 不覆盖：word 无值回落 0 与 DB 无值一致；③ 现有 49 测试全绿。
**风险与交互**：与 A3/A1 同文件（PlayerDataStore）——C2-A 单执行者串行编辑。

#### A5. R3-M4 total_sessions 双口径文档化（裁决：文档化，不改语义）
**根因**：`total_sessions`（player_data 列）每次 endSession +1（updatePlayerStats :701-706 incrementSessions，同日两场 +2）= "局数"口径；`player_sessions` 表每人每日一行（recordSession UPSERT 合并）= "学习天数"口径（getTotalSessions/报表/统计 HTTP 读它）。ProfileGUI（内存 stats）与 StatisticsHttpServer（DB 行数）对"会话数"展示两值。
**裁决：文档化双口径，不改判定语义**。理由：① AchievementManager :387/:604（促为中心的涨幅判定）读 `stats.getTotalSessions()`——"同日多局更快解锁"属正向激励且已过 22 成就逐项核验（R4 覆盖声明），改判定语义有成就回归风险、违反"中危最小面"原则；② 改为"按天去重"需要在 endSession 前查当日 player_sessions 行（热路径加查询）且成就进度会回退式变化（已解锁的按局数门槛，改口径后新玩家更难）。两项均为行为变更，收益仅"两处数字一致"，不成比。
**改法（纯注释）**：
1. `PlayerDataStore.PlayerStats.incrementSessions`（:705 附近）与 `updatePlayerStats` javadoc：注明"total_sessions = 学习局数（每次会话结束 +1，同日多局累计）——与 player_sessions 表'每人每日一行'的天数口径**不同源**；成就判定以本口径为准"。
2. `DatabaseManager.getTotalSessions`（:1236-1254 附近）javadoc：注明"本方法返回 player_sessions 行数（天数口径）；管理报表/统计 HTTP 消费；如需局数口径走 player_data.total_sessions"。
**i18n**：无。
**回归验证点**：零行为变更（只改注释）；mvn package 通过即可。
**风险与交互**：无运行时风险。

#### A6. R9-M4 索引侧：player_data(player_name) 建索引
**根因**：`getKnownPlayerNames`（AdminCommandHandler :218-242）主线程 `LIKE` 全表扫；player_data 无 player_name 索引。
**改法（索引部分归本流；前缀守卫归 C2-C/C6）**：`DatabaseManager.createTables` 的索引段（:411-412 `idx_class_members_uuid` 之后）追加：
```java
createIndexIfMissing("idx_player_data_name", "player_data(player_name)");
```
`createIndexIfMissing`（:410-443）已处理 SQLite `IF NOT EXISTS` 与 MySQL information_schema 查重（PF-SEA-02 先例），索引名含表前缀自动拼（idx_ 前缀 + 全库唯一，命名无冲突）。`resources/schema.sql` 若有索引段同步追加 `CREATE INDEX IF NOT EXISTS`（执行者开 schema.sql 核对既有索引条目风格后照抄）。
**i18n**：无。
**回归验证点**：① 启动日志无索引建表失败（fine 级静默为正常）；② `/lea report <前缀> Tab` 响应无全表扫感知；③ MySQL 后端（如可测）information_schema 查重路径不报错。
**风险**：极低（幂等建索引，失败静默不阻断）。

#### A7. [附] R7-M1 DB 侧批量查询（跨流：本流实现，C2-C 调用）
**动机**：见 §C2-C/C1。`DatabaseManager` 新增 `public Map<UUID, List<SeasonRewardRow>> getPendingSeasonRewardsFor(Collection<UUID> playerIds)`：空集合 → 空 map；`IN` 占位符展开（照 getRankedPlayersForPeriodFiltered :1244+ 的 members IN 写法），`WHERE season_reward claimed IS NULL/false 等条件同 getPendingSeasonRewards`，按 player_uuid 分组返回；异常 → 空 map + WARNING（静默降级=发放推迟到 join 路径，与 deliverPending 现有"秒退留待下次"语义同族）。
**回归验证点**：单玩家 join 补发路径不受影响（deliverPending 单查保留）；批量结果与逐个 getPendingSeasonRewards 等价（可写单测或手测双人）。
**风险**：IN 列表为在线 UUID 快照（主线程抓取），超大服（>千人）IN 展开长度注意——MySQL 无硬上限（SQLite 999 变量上限！）。**防御**：若集合 >500 分批查询合并（或直接封顶在线数语义已满足）；执行者按 SQLite SQLITE_MAX_VARIABLE_NUMBER 先例（全仓其他 IN 用法若有分批，照抄）。

### C2-B 社交档案

#### B1. R4-M1 广场直达无快照（openSquareWithSnapshot）
**根因**：`/le square weekly|monthly|alltime|feed` 直达（LearnCommand `handleSquareCommand` 尾部 `new EnglishSquareGUI(plugin).open(player, 1, mode);`）不登记 `squareGuis` → 未翻页前每次头像点击走 SocialListener 重建分支现查库（:302-314），窗口内排名变化即点错人。
**改法（SocialListener.java）**：在 `openLeaderboardWithSnapshot`（:497-501）之后新增同式方法：
```java
/**
 * R4-M1：命令/主菜单直达入口 —— 打开广场 GUI 的同时登记 squareGuis 快照
 * （照 openLeaderboardWithSnapshot F3-01 先例；mode 可为 null=默认落点）。
 * 登记后 SocialListener 点击侧信任快照，直达窗口期零重建。
 */
public void openSquareWithSnapshot(Player player, RankingMode mode) {
    EnglishSquareGUI gui = new EnglishSquareGUI(plugin);
    gui.open(player, 1, mode);
    squareGuis.put(player.getUniqueId(), gui);
}
```
**改法（LearnCommand.java [待C1]）**：`handleSquareCommand` 尾部单行替换：
`new EnglishSquareGUI(plugin).open(player, 1, mode);` → `plugin.getSocialListener().openSquareWithSnapshot(player, mode);`
**入口核实（已完成）**：主菜单广场按钮走 `player.performCommand("le square")`（MainMenuListener :138-140）→ 同一条命令路径，**单点收口**；全仓 `new EnglishSquareGUI` 其余调用点均为 SocialListener 内部（已各自 put 快照）或 openHidden，勿动。
**回归验证点**：① `/le square weekly` 停留期间他人上榜变化 → 点头像仍打开原第 N 位（快照生效）；② 翻页/切 tab/返回后快照链（:332/372/380/385/403）不破坏；③ ESC→重开正常重建（新 open 重新 put）；④ `/le square`（无参）与 full `all` 模式同样生效。
**风险与交互**：低。重建分支（:302-314）保留为陈旧窗口兜底（不删）。**[待 C1]**：LearnCommand 行号漂移，锚点=方法名 `handleSquareCommand` 与其尾部最后的 `new EnglishSquareGUI(plugin).open(player, 1, mode)`。

#### B2. R4-M2 他人主页"过往学习"打开自己日历
**根因**：SocialListener `handleProfileGUI` 的 PROFILE_CALENDAR_SLOT 分支（:417-428）解析出 targetId 只用于返回栈，`new LearningCalendarGUI(plugin).openAsync(player)` 传 viewer → 展示查看者自己的日历。
**裁决（隐私门语义）**：日历=学习量（每日单词数）属"学习结果"类数据，**他人查看须过目标 `showResults` 门**（与 FeedTextFormatter SESSION_DONE detail 降级同口径；D11 家长报告不受此约束、不经本路径，无冲突）。门语义：
- target==viewer → 直开本人日历；
- target ≠ viewer 且 `isShowInSquare()==false` → 进 `openHidden` 笑话页（对齐成就墙 F4-04 纵深先例 :446-462；陈旧界面窗口防绕过）；
- target ≠ viewer 且 showInSquare=true 但 `isShowResults()==false` → **拒绝打开 + 提示**（不进笑话页——人在广场可见、只是不公开结果），ipushBack **不压**（同 F4-04 隐身路径"不放行不压返回帧"纪律）。
**改法（SocialListener.java, handleProfileGUI 日历分支）**：在 `UUID targetId = extractTargetIdFromTitle(...)` 与 `if (targetId == null) return;` 之后、pushBack 之前插入：
```java
PlayerDataStore store = plugin.getPlayerDataStore();
if (!targetId.equals(player.getUniqueId())) {
    var ps = store.getProfileSettingsReadOnly(targetId);
    if (!ps.isShowInSquare()) {
        new EnglishSquareGUI(plugin).openHidden(player, targetId);   // F4-04 纵深同款
        return;
    }
    if (!ps.isShowResults()) {
        SoundManager.playSound(player, "menu-click");
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "calendar.not-public"));
        return;
    }
}
```
然后 `new LearningCalendarGUI(plugin).openAsync(player);` 改为 `new LearningCalendarGUI(plugin).openAsync(player, finalTargetId);`
**改法（LearningCalendarGUI.java [待C1]）**：
1. 新增重载 `public void openAsync(Player viewer, UUID targetId)`；旧 `openAsync(Player player)` 保留并委托 `openAsync(player, player.getUniqueId())`（兼容入口 `open(Player)` 不变）。
2. 异步段 `getSessionDays(playerId, 35)` 改查 `targetId`（targetId 为闭包 final 捕获）。
3. `openWithData` 增 `UUID targetId` 参数；标题：`targetId.equals(viewer.getUniqueId())` → 既有 `calendar.title`；否则 → 新键 `calendar.viewing-title`（`{player}` 替换 target 名，名字取 `plugin.getPlayerDataStore().getPlayerName(targetId)`——照 EnglishSquareGUI :316 渲染先例，主线程段调用）。
4. 返回键（slot 8）行为不变（MiscGUIListener 统一处理；返回落 SocialListener 已压的 target 主页帧）。
**i18n 新键**：
- `calendar.viewing-title`：zh `"{player} 的学习日历"`；en `"{player}'s Learning Calendar"`（所在节 `calendar:` 现有节，紧跟 `calendar.title`）。
- `calendar.not-public`：zh `"{prefix}&7对方未公开学习日历"`；en `"{prefix}&7This player hasn't shared their learning calendar."`（同节；参照 `square.hidden-lore` 语感）。
zh.yml :530 `calendar:` 节 / en.yml 对应节同步（节点对称性测试 + audit 脚本会检查）。
**回归验证点**：① A 看 B 主页（B showResults=true）→ 日历标题为 B、数据为 B；返回落 B 主页；② B showResults=false → 提示 + 不开（返回键仍回 B 主页——pushBack 已在禁用路径外？**注意**：本规格在门检失败时 return，pushBack 未压 → 返回键落更下层来源帧，与 F4-04 纪律一致）；③ B showInSquare=false（查看期间改隐身）→ 笑话页；④ 本人日历零回归（标题/数据/返回不变）；⑤ viewer 快速 quit→rejoin 异步间隙 → isOnline 复检既有守卫覆盖。
**风险与交互**：中。LearningCalendarGUI 被 C2-B 与 C2-E 双触——**串行约束：先 B（本重载）、后 E（token）**，同执行者或按序提交。**[待 C1]**：SocialListener 非 C1 白名单行号安全；LearningCalendarGUI/（无 LearnCommand 涉及）。

#### B3. R13-M4 celebrationShownDates 跨日惰性清理
**根因**：`LearnCommand.celebrationShownDates`（:60 UUID→LocalDate）无任何 remove——每完成过会话的玩家永久留条（值仅当日内有意义）。
**改法（LearnCommand.java [待C1]，惰性清理裁决——零新接线）**：在庆祝显示判定处（锚点：`if (today.equals(celebrationShownDates.get(player.getUniqueId())))` 所在块，:562 一带）之前插入一行：
```java
// R13-M4：跨日惰性清扫（值仅当日有效；保留今日条目，不影响当日去重语义）。
celebrationShownDates.values().removeIf(d -> !today.equals(d));
```
**理由**：挂 quit 需要 LearnCommand 提供 unloadPlayer 并在 PlayerJoinListener 接线（跨流且需 getter）；惰性清扫零接线、自包含、每场庆祝触发一次（removeIf 遍历小 map，可忽略）。代价：若当日无人庆祝则旧条目滞留至下次庆祝（内存量级=历史玩家数×约16B，可接受）。
**i18n**：无。
**回归验证点**：① 同日两次完成会话 → 庆祝只显示一次（去重不受清扫影响）；② 跨日后首次庆祝 → 前日条目被清、当日正常显示。
**风险**：极低。**[待 C1]**：LearnCommand 在 C1 白名单，锚点=该方法内 `celebrationShownDates.get(player.getUniqueId())` 文本。

### C2-C 管理面

#### C1. R7-M1 参与奖结算规模（裁决：SETTLE 首轮发放批量查询）
**根因**：SETTLE 服首轮发放 `settle` 回主线程段 `for (Player online : Bukkit.getOnlinePlayers()) deliverPending(online, true);`（SeasonManager :445-447 / :685）——每人在线玩家一个异步任务、各自 `getPendingSeasonRewards(playerId)` 单查，N 人在单连接上排队；台账侧 `getSessionParticipants`（:477-481）行数无界。
**裁决：实施批量查询优化（非仅文档化）**。理由：批量查询在 DB 层新增一个方法即可（IN 展开，C2-A/A7 已给规格），调用点改动小；同时 config.yml 注释补规模提示（双管，注释侧归本流 D4 同批）。台账侧 `getSessionParticipants` 无 LIMIT 属"参与奖开启后合理规模"（参与奖本身就是给全窗口活跃玩家发钱，行数=应发行数），**文档化**（config.yml participated 键注释加"开启后结算将为本窗口全部活跃玩家建台账，大服注意"）。
**改法（SeasonManager.java）**：
1. 结算回主线程段（`runMain` 内 `for (Player online : Bukkit.getOnlinePlayers()) deliverPending(online, true);` 处，:445-447）：替换为一次批量：
   ```java
   List<UUID> onlineIds = new ArrayList<>();
   for (Player online : Bukkit.getOnlinePlayers()) onlineIds.add(online.getUniqueId());
   deliverPendingBatch(onlineIds, true);
   ```
2. 新增 `private void deliverPendingBatch(Collection<UUID> playerIds, boolean settleTime)`（**与 deliverPending 同构**，照其 :555-595 全文仿写，差异仅在数据获取）：
   - 空集合/`!isEnabled()` → return；
   - `runAsync(() -> { Map<UUID, List<SeasonRewardRow>> byPlayer = plugin.getDatabaseManager().getPendingSeasonRewardsFor(playerIds); runMain(() -> { for 每 playerId→rows: 在线复检（Bukkit.getPlayer/isOnline）→ economy null 检查（静默降级同单发）→ for 每行 markSeasonRewardClaimed 条件 UPDATE 先于发钱 → payReward → sendRewardMessage / 失败回滚 unmark } ) })`——发钱与消息全部回主线程（**保持**单发版约束：Vault 调用与玩家消息主线程）。
3. `deliverPending`（单发版）**保留**——join 补发路径（:552）继续用；两份主线程发放循环体可提取私有 `deliverRows(Player, List<SeasonRewardRow>, boolean)` 共用（可选重构，执行者按最小 diff 自行决定抽或不抽）。
**i18n**：无。
**回归验证点**：① 双在线玩家均 pending → SETTLE 后一次批量查询两行都发出（消息/金额正确）；② 批量查询间隙玩家下线 → 该行保持 pending（在线复检拦下，join 补发兜底）；③ economy null → 不标记不发放保持 pending；④ payReward 失败 → 回滚标记重试（与单发同）；⑤ 无 pending → 零输出。
**风险与交互**：依赖 C2-A/A7 的新 DB 方法（跨流配对，C2-A 先行）；SQLite IN 变量上限防御见 A7。**[待 C1]**：SeasonManager 非 C1 白名单，行号安全；但与 R7-H2 批 B 修复（S6 流）已改过本文件——以方法名 `deliverPending` / 结算 runMain 段定位。

#### C2. R7-M2 班名含 &/§ 校验
**根因**：`handleClassCreate`（AdminCommandHandler :637-643）只查 blank/长度/空格；`&`/`§`/换行可进 className → ClassBoardGUI 标题染色注入。
**改法**：校验条件（`className.contains(" ")` 处）扩为：
```java
if (className.isBlank() || className.length() > maxLen || className.contains(" ")
        || className.contains("&") || className.contains("§") || className.contains("\n") || className.contains("\r")) {
```
拒绝走既有 `class.name-invalid` 键。
**其他写入面核实（已 grep）**：全仓无 class rename/setClassName/updateClassName 入口（grep 零命中）——create 是唯一班级名写入面，**单点收口**。
**i18n 文案微调（可选但建议）**：`class.name-invalid` zh :789 / en :790 现文案"不能含空格"→ 改为"不能含空格或颜色符号" / "(no spaces or color codes)"——**改键值不改键名**，两语言同步。
**回归验证点**：`/lea class create "&cRed"` → 拒绝 + name-invalid；`/lea class create 正常名` → 建成；GUI 标题对既有合法班名渲染不变。
**风险**：极低；既有已建含 & 的班名（存量）不迁移（展示层 translateAlternateColorCodes 原样——注释说明接受）。

#### C3. R7-L5 class list 与 /le class GUI 两口径
**根因**：`sendClassList`（AdminCommandHandler :812-817）读 `season.getCurrentTopCached(50)` 快照反查——全服前 50 之外成员显示 0 分；`/le class` GUI（LearnCommand :817-820）走 `getRankedPlayersForPeriodFiltered(ENTRY_SLOTS.length, window.start(), window.end(), memberIds)` 班级过滤查询（真实分数）。同数据两口径。
**裁决：命令面改与 GUI 同源（班级过滤查询）**。理由：`getRankedPlayersForPeriodFiltered` 已存在且 GUI 在用（成员名单 ≤ max-members 有界，无规模担忧）；改成同源即消灭矛盾，比"文档化 list 分数=前 50"对管理员更有用。快照反查是旧实现残留。
**改法（AdminCommandHandler.java，sendClassList）**：
1. 签名增 `int seasonId` 或窗口参数（调用点 `handleClassList` :804 已异步段内、主线程段 `runOnMain(() -> sendClassList(...))`——需把 season 窗口在**异步段内**算好传入，或 sendClassList 内现读 `season.windowOf(season.currentSeasonId(LocalDate.now()))`（纯函数零 IO，主线程段可调，照 LearnCommand :786-787 先例））。
2. 方法体内：`scoreByUuid` 快照构建替换为：
   ```java
   List<UUID> memberIds = members.stream().map(ClassMemberRow::uuid).toList();
   List<LeaderboardEntry> entries = plugin.getPlayerDataStore()
           .getRankedPlayersForPeriodFiltered(members.size(), window.start(), window.end(), memberIds);
   ```
   排序/渲染改为直接遍历 entries（分数随行返回）；未上榜成员（无分数行）要否保留"0 分列出"？——**对齐 GUI 口径**（GUI 只显示查询返回者）→ 未上榜成员不列出；若运行期发现"成员消失"体验问题，回退列出 0 分（执行者按 GUI 实际渲染核对后定，建议先直读 GUI 渲染循环确认其空榜语义）。
3. 异步化注意：sendClassList 当前在主线程段（runOnMain）——新增的 DB 查询**不能**在主线程跑：把 `getRankedPlayersForPeriodFiltered` 调用移入 `handleClassList` 的异步段（`db.getClassMembers` 之后），runOnMain 只做渲染。
**i18n**：无（list-header/list-entry 现有键复用）。
**回归验证点**：① 大服 >50 有分玩家下班级成员榜显示真实分数（与 GUI 一致）；② 赛季未启用（season null/disabled）→ 保持既有降级（空 scoreByUuid 行为——执行者核对既有"未启用时全 0 分排序"路径的保留：season 关时无窗口，fallback 按名单原序或 0 分，与 GUI 门（season.disabled 拒开）不矛盾——命令侧不拒，给注释说明）；③ 成员空班 → 只发 header。
**风险与交互**：中（查询语义变更）。执行者需实读 `getRankedPlayersForPeriodFiltered` 的排序/过滤语义（members IN + ORDER BY score）后核对展示排序键与旧实现的次键（名字 CASE_INSENSITIVE）差异。

#### C4. R9-M1 statistics-server reload（HotReloadManager 接线侧）
**根因**：reloadAll（:28-47）不触碰统计服务。
**改法（HotReloadManager.java）**：`reloadAll` 尾部（`plugin.getLogger().info("All configurations hot-reloaded successfully.")` 之前）追加：
```java
// R9-M1：统计服务配置为构造快照——按最新配置重启（enabled 切换/端口/认证生效）。
plugin.restartStatisticsServerIfNeeded();
```
**边界**：方法本身幂等（见 D6）；异常自吞（D6 内 try/catch），不阻断 reload 主流程。
**i18n**：无。
**回归验证点**：见 D6；另核 reload 连点两次无端口/线程泄漏（jcmd 或日志观察 stop/start 两条）。
**风险与交互**：依赖 D6 方法先存在（跨流配对，C2-D 先行或同执行者）。

#### C5. R9-M2 export 返回相对路径
**根因**：`DataExporter.exportAllData` :106 `new ExportResult(true, exportFile.getAbsolutePath(), null)` 绝对路径暴露服务器目录；对照组：ReportGenerator `"exports/reports/" + file.getName()`（:252/280）、AdminCommandHandler 词库导出 `"exports/" + fileName`。
**改法**：成功分支改 `return new ExportResult(true, "exports/" + exportFile.getName(), null);`——javadoc（:60-61"filePath 为绝对路径"）同步改"为相对路径（exports/ 下，与报告/词库导出展示口径一致）；完整路径见日志"。日志行（:100 `getLogger().info("Data exported to: " + exportFile.getAbsolutePath())`）**保留**绝对路径（日志属运营排查面）。
**调用点核对**：AdminCommand :74-75 成功消息 `admin.export-complete` 的 "file" 占位符直接展示——无路径解析逻辑，无需改。
**i18n**：无（admin.export-complete 键复用；若其文案含"路径"字眼执行者核对无害）。
**回归验证点**：`/lea export` → 消息显示 `exports/export_<ts>.csv`；文件实际存在于 `plugins/.../exports/`。
**风险**：极低。

#### C6. R9-M4 tab 补全前缀守卫
**根因**：`getKnownPlayerNames` 主线程 LIKE 全表扫（每次按键）。
**改法（AdminCommand.java :361-366 调用侧）**：`prefix.length() >= 1` 条件改 `prefix.length() >= 2`（**防抖**：单字符前缀命中面过大且价值低，在线名分支（:359-363）已覆盖常见输入；库内名查询 2 字符起）。同步注释"R9-M4：前缀 <2 不查库（在线名分支已覆盖）"。
**索引侧**：归 C2-A/A6（已给）。
**i18n**：无。
**回归验证点**：`/lea report a<Tab>` → 只出在线匹配 + all；`/lea report ab<Tab>` → 现库内名补全；功能无回归（原来 1 字符也能出库内名——属被有意收窄，注释说明）。
**风险**：极低（UX 微调，管理员无感知丢失——2 字符后恢复库内名）。

### C2-D 配置调度

#### D1. R10-M1 daily-reminder 任务体加 enabled 门
**根因**：`scheduleDailyReminders`（LetsLearnEnglish :409-430）的 lambda 只在调度时判过一次 enabled；`/lea reload` 关掉 daily-reminder 后任务照跑（对比 ReviewScheduler 任务体首行点读门"reload 不重建任务，体内门是唯一保障"）。
**改法**：lambda 首行（`Calendar cal = Calendar.getInstance();` 之前）加：
```java
// R10-M1：reload 不重建任务，体内点读门是唯一保障（对齐 ReviewScheduler 先例）。
if (!configManager.getMainConfig().getBoolean("daily-reminder.enabled", true)) return;
```
**联动核实（同族决定）**：`effectiveHours` 构造时快照（:404-405）——reload 改 `reminder-hours` 后仍用旧小时集。**裁决：本批不修**（保持一行修最小面）；理由：改小时集需要任务体重读 list 并与 remindedHours 对齐，属行为增强而非缺陷修复；`joined` 通道（sendJoinDailyReminderIfEnabled）每次点读 enabled，无此问题。给 config.yml 注释补"改 reminder-hours 需重启"（并入 D4 同一节注释）。
**i18n**：无。
**回归验证点**：① reminder-hours 命中分钟前 `/lea reload` 关 enabled → 到点不发；② reload 重开 → 下一命中点恢复；③ join 通道不受影响（即时门）。
**风险**：极低（首行短路，无状态）。

#### D2. R10-M2 messages 升级前 .bak 备份
**根因**：`ConfigManager.loadMessageConfig`（:211-228）在 `version < MESSAGE_CONFIG_VERSION` 时直接 `plugin.saveResource(path, true)` 覆盖用户编辑、无备份。
**改法（已核源码路径）**：`loadMessageConfig` 的升级分支内、`saveResource(path, true)` 之前插入备份：
```java
try {
    java.nio.file.Files.copy(file.toPath(),
            java.nio.file.Path.of(file.getAbsolutePath() + ".bak"));
} catch (java.io.IOException e) {
    plugin.getLogger().warning("Failed to back up " + path + " before upgrade: " + e.getMessage());
}
plugin.saveResource(path, true);
```
**边界**：① 首次安装路径：`file.exists()` 走 false 分支 `saveResource(path,false)`——不进升级分支，不涉备份（要求满足）；② `.bak` 单槽语义：每次升级覆盖旧 .bak（文档化：保留"升级前最后一份"而非历史全量，与 backupDatabase 的滚存策略区分）；③ 备份失败不阻断升级（仅 warning，防新装环境权限问题卡启动）；④ **reload 抖动**：升级成功后文件 version 已新，后续 reload 不再进分支——单次触发。日志行追加 " (old version backed up to <path>.bak)"。
**i18n**：无（logger）。
**回归验证点**：把 zh.yml 的 config-version 手改回 13 + 改一条文案 → `/lea reload` → 生成 zh.yml.bak 且含改动后文案；再 reload 不重复备份。
**风险**：极低。

#### D3. R10-M3 auto-save-interval 钳位
**根因**：`startScheduledTasks`（:355-364）`getInt("general.auto-save-interval", 5)` 零校验；CraftScheduler period=0→1 tick → 保存风暴（config 注释自称范围 1-60）。
**改法**（照 update-interval 钳位先例 :390-392）：
```java
int saveInterval = configManager.getMainConfig().getInt("general.auto-save-interval", 5);
int clamped = Math.max(1, Math.min(60, saveInterval));
if (clamped != saveInterval) {
    getLogger().warning("general.auto-save-interval=" + saveInterval
            + " out of range [1,60], clamped to " + clamped + ".");
    saveInterval = clamped;
}
```
（clamp 后再用于 `saveInterval * 1200L`。**不做** 0=禁用的语义化——注释声称范围 1-60、0 与"风暴"的歧义应消除而非赋予新义。）
**i18n**：无。
**回归验证点**：设 0 → 启动 WARNING + 60 分钟周期（日志或 debug 观察）；设 61 → 钳到 60；正常 5 不变。
**风险**：极低。

#### D4. R10-M5 config.yml "需重启生效"注释（只补注释不改值）
**锚点（已逐节定位，当前工作树）**：
1. `general.auto-save-interval`（:34 上方注释块）追加一行：`# 改动需重启生效（reload 不重排周期任务）。`
2. `general.backup.*`（:39-45 节头）追加：`# backup.* 各键在启动时快照，改动需重启生效。`
3. `daily-reminder` 节（:490-505）：在 enabled 注释块追加 `# enabled 可经 /lea reload 即时生效；reminder-hours 为启动快照，改动需重启。`
4. `tts` 节（:565-579）：节头追加 `# 本节各键为启动快照（TtsManager 构造读取），改动需重启生效（reload 不重建）。`
5. `statistics-server` 节（:585-604）：节头追加 `# 本节各键为启动快照；改动需重启生效（或由 reload 的自动重启逻辑处理——见代码注释）。`（若 C4 实施了 restart 方案，改文案为"enabled/port 等经 /lea reload 将自动重启服务生效"——**执行者按 C4 裁决结果二选一**。）
6. 头部 :7 的 blanket 承诺句：追加一行限定 `# 例外：标注"需重启生效"的键为启动快照，不受 reload 影响。`
**i18n**：无（config.yml 中文注释）。
**回归验证点**：零行为变更；YAML 解析无误（启动 load 无 error）。
**风险**：无。

#### D5. R13-M4 joinReminderSent 午夜 rollover 清理
**根因**：`joinReminderSent`（:136 UUID→LocalDate）quit 不清（当日去重需要）、跨日无清理 → 每曾进服玩家永久留条。
**改法（午夜 rollover 主线程段挂点，已核）**：`scheduleMidnightRollover` 主线程段的 `dailyChallengeManager.clearAllWordChallengeClaims();` / `wordManager.getReviewScheduler().clearNotifiedDates();`（:576-577 一带）之后追加：
```java
// R10-L3/R13-M4：跨日后旧日期条目一律失效（当日去重语义不受影响）。
LocalDate today = LocalDate.now();
joinReminderSent.values().removeIf(d -> !today.equals(d));
```
（Lambda 内已有 `LocalDate today` 则复用，避免重复声明——执行者按变量冲突重命名。）
**i18n**：无。
**回归验证点**：① 当日 join → map 有今日条目 → 午夜 rollover 后清空 → 次日 join 正常收提醒；② 定时提醒写 map（:425）与 on-join 去重（:454-458）语义不变。
**风险**：极低（主线程段单线程写，CHM removeIf 安全）。

#### D6. R9-M1 LetsLearnEnglish 侧 restartStatisticsServerIfNeeded
**根因**：statistics-server 全部配置在 `StatisticsHttpServer` 构造期读取（:47-58）；reload 不触碰 → 改 port/password 静默不生效。
**裁决：实施 restart 方案（C4 配套）**。理由：配置项涉及"网络监听端口 + 认证密码"——password 改动不生效属安全面静默不同步（比 tts 类纯体验键更严重）；restart 逻辑量小（stop→new→start 三行 + 空守卫），与 R9-M1 修法建议一致。
**前置核实（已完成）**：`LetsLearnEnglish` **无** `getStatisticsHttpServer()` getter（grep 仅字段 :126 与 onEnable/onDisable 使用）——需新增 getter（:787-805 getters 区，照 getDataExporter/getSoundManager 风格）。
**改法（LetsLearnEnglish.java）**：
1. 新增方法（:236-237 的 onEnable 建实例逻辑保持；将"读取 enabled → 建 → start"提为私有方法复用——或最小化：新方法内自括）：
```java
/** R9-M1：reload 后按最新配置重启统计服务（enabled 切换/端口/认证改动生效）。 */
public void restartStatisticsServerIfNeeded() {
    if (statisticsHttpServer != null && statisticsHttpServer.isRunning()) {
        statisticsHttpServer.stop();
    }
    boolean enabled = configManager.getMainConfig()
            .getBoolean("statistics-server.enabled", false);
    if (!enabled) {
        statisticsHttpServer = null;
        return;
    }
    try {
        statisticsHttpServer = new StatisticsHttpServer(this, databaseManager);
        statisticsHttpServer.start();
    } catch (Exception e) {
        getLogger().log(java.util.logging.Level.WARNING,
                "Failed to restart statistics HTTP server after reload", e);
    }
}
```
2. 新增 getter：`public StatisticsHttpServer getStatisticsHttpServer() { return statisticsHttpServer; }`（HotReloadManager 消费）。
**幂等/端口占用边界**：`stop()`（:90-103）`server.stop(0)` + executor.shutdownNow 后立即 new+start——Java HttpServer 的 stop(0) 同步释放监听 socket（立即关闭语义），重启循环内端口复用无 TIME_WAIT 问题（服务端 socket 不落 TIME_WAIT；若遇极小概率 bind 失败，start 内 catch 已记 SEVERE 不崩插件）。reload 连点：stop 前判 isRunning 防御，连续两次 reload = stop+start+stop+start，每步幂等。
**i18n**：无（logger）。
**回归验证点**：① enabled=true 改 port → reload → 新端口监听；② enabled true→false → reload → 端口释放（curl 拒绝）；③ false→true → reload → 起服务；④ 原启停路径（onEnable/onDisable）不受影响。
**风险与交互**：与 C4（HotReloadManager 调用点）跨流配对——C2-C 依赖本方法先存在（建议同执行者或 C2-D 先行）。

### C2-E GUI 基建

#### E1. R12-M1/M2 openAsync token 去重（两 GUI 同式）
**根因**：DailyChallengeGUI.openAsync 与 LearningCalendarGUI.openAsync 均无 in-flight 去重/序号——连点/交叉打开时两次异步预取 + 两次 runTask 渲染，后者覆盖前者（闪变 + 双倍查库，无数据损坏）。
**裁决：token 方案（per-GUI-class static AtomicLong 序列 + per-player 记录）**。核对 GUI 结构（两类均无 per-player 实例概念、`new` 即用即弃）→ 用 `private static final Map<UUID, Long> openSeq = new ConcurrentHashMap<>();` + `private static final AtomicLong SEQ = new AtomicLong();`（static，跨实例单调）。
**改法（每个 GUI 类同式）**：
1. `openAsync` 主线程首部：
   ```java
   final long token = SEQ.incrementAndGet();
   openSeq.put(playerId, token);
   ```
2. 异步段末尾回主线程的 lambda 里、`openWithData(...)` 之前：
   ```java
   if (openSeq.getOrDefault(playerId, -1L) != token) return; // 已有更新的打开请求，本次作废
   ```
   （放在既有 `if (!player.isOnline()) return;` 旁边）
**R11-M3 共存（C1 先执行）**：C1 在这两文件加了 catch 提示（异步段 catch 里的玩家提示）——本 token 只加"主线程 render 前比对"，与 C1 的 catch 段无重叠；若 C1 的 catch 里也回主线程发消息，其消息不受 token 门约束（提示语义正确：失败要告知），**不要**给 catch 消息套 token。**[待 C1]**：两文件在 C1 白名单——执行者以方法名 `openAsync` 定位，先读 C1 后的现状再落 token 行。
**quit 清理**：`openSeq` 是 static —— **挂 quit 清理**（PlayerJoinListener quit 段）还是惰性？量级=历史玩家数条（Long 值），与 joinReminderSent 同族。**裁决：并入 C2-F 的 quit 清理清单外——两 GUI 的 openSeq 用 `ConcurrentHashMap` 且每 join 玩家少个位数条目；为省跨流接线，采用"每次 openAsync 覆写自身条目"的天然收口 + 不挂 quit（量级可接受，注释说明）**。若执行者有余力可在 PlayerJoinListener quit 段顺手 `DailyChallengeGUI.clearOpenSeq(playerId); LearningCalendarGUI.clearOpenSeq(playerId);`（各加静态清理方法）——**可选，不强制**。
**i18n**：无。
**回归验证点**：① 连点主菜单"每日挑战"两次 → 只渲染一次（无闪变）；② 领取后自动重开（MiscGUIListener :251/265）与手动打开交错 → 后者胜（token 最新）；③ 登录→打开→quit→rejoin→打开 → 无陈旧作废（rejoin 新 UUID 条目新 token）；④ 日历/挑战交叉打开 → 后发起者胜（先发起者 render 被 token 拦弃）。
**风险与交互**：低。static map 内存注释说明。**[待 C1]**：两文件均在 C1 白名单。

#### E2. R12-M3 GUIUtils.fillEmpty 基建 + 8 GUI 调用点
**根因**：8 个 GUI（ConfirmationGUI/WordBankSelectGUI/DailyChallengeGUI/ChallengeCompleteGUI/AchievementWallGUI/LeaderboardGUI/EnglishSquareGUI + DuelGUI[已由 C1 铺格]）空槽无填充——防拖安全完全依赖各 listener 的标题判定正确性（纵深单点）。
**改法（GUIUtils.java）**：新增：
```java
/** R12-M3：把空槽统一填为玻璃板（防拖纵深；照 LearningCalendarGUI filler 先例）。 */
public static void fillEmpty(Inventory inv) {
    ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    ItemMeta meta = filler.getItemMeta();
    if (meta != null) { meta.setDisplayName(" "); filler.setItemMeta(meta); }
    for (int i = 0; i < inv.getSize(); i++) {
        if (inv.getItem(i) == null) inv.setItem(i, filler.clone());
    }
}
```
（效果与 LearningCalendarGUI :226-232 的既有内联 filler 逐字等价——该处可顺手换调 GUIUtils.fillEmpty，也可不动；执行者抉择，倾向顺手统一。）
**调用点（每 GUI 在 `player.openInventory(inv)` 之前、全部 setItem 之后插入 `GUIUtils.fillEmpty(inv);`）**：
| GUI | 锚点（方法） | 状态 |
|---|---|---|
| ConfirmationGUI | `open(Player)` 渲染段（createInventory :126 后至 openInventory 间）——注意 `openSecond` 复用/第二个窗口需逐一检视是否同库渲染 | 直接改 |
| WordBankSelectGUI | `open(Player)`（:35-41 起） | 直接改 |
| ChallengeCompleteGUI | `open(Player, double)`（:49-55 起） | 直接改 |
| AchievementWallGUI | `open(Player, UUID)`（:54-88 起） | 直接改 |
| LeaderboardGUI | `open(Player, int)`（:92-160 起）；注意 renderSeasonInfoBoard（:294）是否二次渲染同一 inv（若同 inv，fillEmpty 应在最外层最后） | 直接改 |
| EnglishSquareGUI | `open(Player,int,RankingMode)`（:93 起 createInventory :171）+ `openHidden`（:450，27 格） | 直接改（两处） |
| DailyChallengeGUI | `openWithData` 尾部（C-3 方法） | **[待C1]** 复核锚点 |
| LearningCalendarGUI | `openWithData` 尾部——**注意**：该 GUI 已有内联 filler（:226-232），替换为 `GUIUtils.fillEmpty(inv)` 或保留现状（等价）。若 B2 已改 openWithData 签名，以签名后版本为准 | [待C1] 与 B2 串行 |
**i18n**：无。
**回归验证点**：① `inspect` 各 GUI 空槽=灰玻璃、无 shift 可塞；② 视觉回归：GRAY filler 与各 GUI 既有配色不冲突（ConfirmationGUI 等按钮配色原样）；③ 与各 listener 的 click 守卫共存（filler 槽点击仍被 cancel 分支吞——守卫不变）；④ 物品拖动测试（drag 进空区被 listener drag guard 拦，filler 为第二层）。
**风险与交互**：低-中（8 文件多点）。与 C1（DuelGUI 已铺格、DailyChallengeGUI/LearningCalendarGUI 在 C1 白名单）——**E 流执行前先 rebase C1 提交**。

### C2-F 线程与清理

#### F1. R13-M1 join checkAndAssign 异步化
**根因**：PlayerJoinListener.onPlayerJoin 尾部 `plugin.getDailyChallengeManager().checkAndAssign(player.getUniqueId());` 主线程同步 SELECT+INSERT batch。
**前置核实（已完成）**：`checkAndAssign`（DailyChallengeManager :145-160）整段在 per-UUID stripe lock 内（F2-11 收口），且 doCheckAndAssign 内部只碰 DB + LocalDate——**无 Bukkit API 调用**（读声明+代码段确认 count/insert 路径纯 JDBC）。午夜路径（LetsLearnEnglish :547-553）已证明可异步。**异步安全成立**。
**改法（PlayerJoinListener.java）**：
```java
// R13-M1：DB 分配移出主线程（stripe lock 内幂等；午夜路径已证异步安全）。
plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
    if (!plugin.isEnabled()) return;
    plugin.getDailyChallengeManager().checkAndAssign(event.getPlayer().getUniqueId());
});
```
（照同文件 quit 段与 SocialManager 的调度守卫风格；`getUniqueId()` 在事件对象上先取出为 final UUID——闭包不捕 Player 对象，防 quit 竞态。）
**R-86p join 提醒顺序核实**：`sendJoinDailyReminderIfEnabled`（:439-459）与 join 摘要（notifyPendingCheers）/赛季补发（claimPendingRewards）均在 checkAndAssign **之前**（PlayerJoinListener :85-94 段）——本改动只把最末的 checkAndAssign 异步化，**前序顺序不变**。
**边界**：首进玩家当日挑战行在主线程事件后 ~ms 级才落库——玩家立刻 `/le challenge` 打开 GUI 时 openAsync 的 `getTodayChallenges` 有 R-32 补分配兜底（:422 `ensure 今日挑战已分配 checkAndAssign 幂等`）——**竞态安全**（先例注释在案）。
**i18n**：无。
**回归验证点**：① 多假人 join 风暴主线程无 SELECT 尖峰（Timings）；② 首进玩家 /le challenge 立刻打开 → 挑战正常显示（R-32 补分配）；③ 午夜 rollover 与 join 并发 → stripe lock 串行无双批（F2-11 语义保持）。
**风险与交互**：低-中。

#### F2. R13-M2 ChatHistoryGUI 两段式异步化
**根因**：`ChatHistoryGUI.open(player, page)` 主线程 1 count + 1 sessions + ≤10 preview = 至多 12 连查（代码内 P2-27 自认）。
**裁决：照 C-3 DailyChallengeGUI.openAsync 两段式**（已给完整先例源码结构，同式照抄）。
**改法（ChatHistoryGUI.java）**：
1. 新增 `openAsync(Player player, int page)`：
   - 主线程首部：E1 token（见 E1，本文件不在 C1 白名单，直接加）。
   - 异步段（`runTaskAsynchronously`，`if (!plugin.isEnabled()) return;` 首尾双查）：预取 `int totalSessions = db.getChatHistoryCount(uuid);`、clamp page、`List<ChatHistoryEntry> sessions = db.getChatSessions(uuid, page, SESSIONS_PER_PAGE);`、以及**在异步段内**对每 session 调 `getSessionPreview` 装入 `Map<String,String[]>`（N+1 保持但移出主线程——与裁决"两段式优先，DB 批 API 非必需"一致）。
   - 回主线程（`runTask` + isOnline 复检 + token 比对 + catch IllegalStateException）：`openWithData(player, page, totalSessions, sessions, previews)`（新私有方法，接收预取数据）。
2. `open(Player, int)` 改为 `resetAndOpen`？——不：保持 `open(Player, int)` 签名但**体内直调 openAsync**（兼容；照 LearningCalendarGUI `open(Player){ openAsync(player); }` 先例）。**渲染方法**：现 `open` 方法体（:110-250 渲染段）抽为 `private void openWithData(...)`，其中所有 `plugin.getDatabaseManager()` 调用删除、改读参数（含 preview map）。空态（sessions.isEmpty）与分页 clamp 逻辑整体平移（clamp 在异步段做）。
3. **会话详情页**（`openSession(player, sessionId, returnPage)` :256 起，含 `getChatSession` :294）——R13-M2 立案范围是"打开/翻页"，但详情页同为打开时同步查。**裁决：本批一并异步化**（同式两段式；详情页数据 = getChatSession 单查 + renderFeedbackItem）。若执行者评估 diff 过大（详情页渲染复杂含反馈物品 :370），**底线**：列表页（open）必做，详情页可标注留 P2-27 后续——执行者按实际 diff 量抉择并在提交说明记录。
**改法（ChatHistoryListener.java）**：三个调用点（:107 上一页 / :117 下一页 / :175 返回）由 `new ChatHistoryGUI(plugin).open(player, targetPage);` 改 `new ChatHistoryGUI(plugin).openAsync(player, targetPage);`；:134 详情页入口 `openSession(...)` 按上一段裁决同步改或留原样。**LearnCommand :622** 的 `new ChatHistoryGUI(plugin).open(player);`（[待C1] LearnCommand）改 `openAsync(player, 1)`——若不便触（C1 已改该文件），保留同步 open 亦可（open 会委托 openAsync，无需改——**若 open(Player) 也委托 openAsync 则 LearnCommand 零改动**。采用"open(Player)->openAsync(player,1)"委托设计，实现单点）。同理 ChatHistoryListener 三处若走 `open(player, page)` 委托也零改动——**设计收敛：`open(Player,int)` 与 `open(Player)` 均委托 openAsync，全部既有调用点零修改**。**但注意**：翻页/返回调用点在主线程点击事件内直调——委托后行为=原语义（异步化）✓。**此设计使本修复对 listener 零 diff**（仅 ChatHistoryGUI 内改动），采纳。
**i18n**：无（渲染键全复用）。
**回归验证点**：① 打开/翻页主线程零 DB（Timings 或断点）；② 分页 clamp（page>totalPages）在异步段后 render 正确；③ 空态渲染；④ 快速连翻页 → token 只渲染最新页；⑤ 渲染输出与修复前逐像素（物品/槽位/lore 全同——预览 map 传参接对）。
**风险与交互**：中（大方法重构，渲染平移要逐行核）。无跨流冲突（ChatHistoryGUI/ChatHistoryListener 均不在 C1 白名单）。

#### F3. R13-M3 广场 ALL_PLAYERS 主线程 N+1
**根因**：两处 ALL_PLAYERS 装配对 cache-miss 玩家逐人 `getPlayerStats(id)`（内部 loadPlayerData 查库）：Leaderboard.getTopPlayers ALL_PLAYERS 分支（:106-118）与 EnglishSquareGUI 渲染装配（:128-140）。
**改法**：**走聚合 SQL `getRankedPlayers(count)`**——已核其实力（PlayerDataStore :1123-1170）：单 SQL 取 player_data（LEFT JOIN profiles 隐身过滤 `COALESCE(p.show_in_square,1)<>0`，ORDER BY total_score DESC LIMIT）**预过滤隐身**——语义与 ALL_PLAYERS 差三点：① 它是 top-N 截断（ALL_PLAYERS 语义"全部"）；② 它按 score 排序（ALL_PLAYERS 无排序承诺、R4-L4 记载键序漂移）；③ correct/incorrect 侧它仍对每行 `getPlayerStats` 补读（:1159-1164 注释"N+1"——**聚合并未消除 N+1**！仅消除"全量枚举"）。**结论：getRankedPlayers 不能直接复用**（既 TOP-N 又 N+1）。
**替代裁决：快照异步刷新（推荐方案，本流实施）**——照 FEED 模式（SocialManager volatile 快照）给 ALL_PLAYERS 建"活跃玩家统计快照"：
1. PlayerDataStore 新增聚合方法 `getActiveSquareEntries(int limit)`：单 SQL `SELECT d.uuid, d.player_name, d.total_score, d.total_sessions FROM {p}player_data d LEFT JOIN {p}player_profiles p ON p.uuid=d.uuid WHERE COALESCE(p.show_in_square,1)<>0 AND (d.total_score>0 或 word_progress 有行？)`——**难点**：ALL_PLAYERS 过滤条件是"答过题"（totalCorrect+totalIncorrect>0），该数据在 word_progress 聚合、不在 player_data。聚合 SQL 写法：`LEFT JOIN (SELECT uuid, SUM(correct_count+incorrect_count) AS cnt FROM word_progress GROUP BY uuid) w ON w.uuid=d.uuid WHERE COALESCE(w.cnt,0)>0`——**一次查询消除全部 N+1**（correct/incorrect 也能顺带 SUM 出来：correct=SUM(correct_count), incorrect=SUM(incorrect_count)，四列全聚合）。**这是可行且干净的方案**（word_progress 有 uuid 索引？——无专门 uuid 索引但 PK/UNIQUE(uuid,word) 前缀= uuid → 可走）。执行者按 PlayerDataStore:1123 SQL 风格写，MySQL/SQLite 双兼容（GROUP BY 子查询标准 SQL）。
2. **快照层**：LetsLearnEnglish 周期任务（:391-395 leaderboard 刷新班车）追加 `playerDataStore.refreshSquareEntriesCache();`？——**或**更简单：**同步聚合查询已是单查**（比 N+1 好一个量级），且 ALL_PLAYERS 是低频打开——**裁决收窄：仅做聚合 SQL 替换两处装配的**数据源，不加快照层**：
   - EnglishSquareGUI :128-140：`List<UUID> allPlayers = store.getActivePlayerIds(); 逐人过滤+getPlayerStats` → `List<LeaderboardEntry> entries2 = store.getActiveSquareEntries(Integer.MAX_VALUE);` 直接得（uuid,name,stats 聚合值）；渲染循环改读 entries（名字/统计已在行内，:257/:316 的 front-collect 与按行渲染均复用 entries——接受一次全量 List 内存（活跃玩家数×entry，百人服 KB 级））。
   - Leaderboard.getTopPlayers ALL_PLAYERS 分支（:106-124）：同样改调 `store.getActiveSquareEntries(count)`（**保留其"再排序+重排行号"语义**：SQL 已按 score DESC，重排循环可保留或删——保留=零语义漂移）。
   - **线程**：两处均在主线程（GUI 装配）——聚合单查主线程执行，单查耗时 << N+1；**不迁异步**（范围收窄，异步化留给 E1 族 GUI 两段式未来改造）。**或**若执行者愿一步到位：EnglishSquareGUI 属 C2-E 白名单可做 openAsync——**裁决：不做**（避免与 E2 冲突，主线程单查已达标）。
3. `getActivePlayerIds`（:1023-1034）保留（SocialListener :292 兜底重建分支仍用；那份走 getProfileSettingsReadOnly + getPlayerStats 的 N+1 属"无快照窗口"低频路径——R4-M1 快照登记修复后更少触发）。
**i18n**：无。
**回归验证点**：① 广场全量模式列表/统计值与修复前一致（同玩家数、同 correct/incorrect 显示）；② 隐身玩家不出列表（SQL COALESCE 过滤）；③ 答过题判定（cnt>0）与旧 `totalCorrect+totalIncorrect>0` 等价；④ 无数据玩家不列；⑤ `%lle_*` 等其它消费面不受影响（本方法全新，不动旧方法）。
**风险与交互**：中（新 SQL + 两处装配重写；word_progress 全表 GROUP BY 大服首查耗时——百人服万词级毫秒级；如有顾虑给 SQL 加 LIMIT？不能（ALL_PLAYERS 语义）——接受，注释说明）。

#### F4. R13-M4 playerDifficulties / dueWordCache quit 清理
**根因**：两 map 无 quit 清理（见 R13-M4）。
**改法（AIChatManager.java [待C1]）**：`playerDifficulties`（:110）——新增 `public void unloadPlayer(UUID playerId) { playerDifficulties.remove(playerId); }`（照 clearPendingSummary :1240 先例，同文件既有 quit 编排形态）；**接线（PlayerJoinListener.java quit 段）**：在 `plugin.getAIChatManager().clearPendingSummary(playerId);`（:127-128 一带）旁追加 `plugin.getAIChatManager().unloadPlayer(playerId);`（或把难度清理并入 clearPendingSummary——**裁决：独立方法**，命名语义单一，照 CheckInManager.unloadPlayer 先例）。
**改法（ReviewScheduler.java）**：`dueWordCache`（:23）——新增 `public void unloadPlayer(UUID playerId) { dueWordCache.remove(playerId); }`；**接线**：PlayerJoinListener quit 段追加 `plugin.getWordManager().getReviewScheduler().unloadPlayer(playerId);`（该 getter 链已存在——ReviewScheduler 经 wordManager 暴露，quit 段其他调用同式）。**语义**：quit 清缓存 → rejoin 后 miss → 重查 DB 重建（TTL 语义不变，仅内存回收）；中断玩家在途条目不再永留。
**TTL 实体回收裁决**：dueWordCache 的 CacheEntry 过期后对象仍驻留（TTL 只判过期重查）——**quit 清理已覆盖"人走了"的 99% 场景**，在线长挂玩家的过期 entry 每次重查覆写（单对象替换）；**不另加 TTL 扫描**（收益低，扫描成本非零）。
**i18n**：无。
**回归验证点**：① 玩家进服→复习提醒→quit→rejoin → 提醒正常（重查重建）；② join 后 quit 立即（无 due 查询）→ 清理 no-op 安全；③ 两 map size 随 quit 收敛（debug 观察）。
**风险与交互**：极低。**[待 C1]**：AIChatManager 被 C1 触（R2-H1 已含在 300c699——本方法新增不受影响；行号以 `playerDifficulties` 字段与 `clearPendingSummary` 锚点定位）。

---

## C. i18n 新键清单（汇总）

| 键 | zh 草案 | en 草案 | 节 | 来源 |
|---|---|---|---|---|
| `calendar.viewing-title` | `"{player} 的学习日历"` | `"{player}'s Learning Calendar"` | `calendar:`（Zh :530 / En 对应） | B2 |
| `calendar.not-public` | `"{prefix}&7对方未公开学习日历"` | `"{prefix}&7This player hasn't shared their learning calendar."` | 同上 | B2 |
| （改值）`class.name-invalid` | `"{prefix}&c班名无效（1-{max} 字符，不能含空格或颜色符号）"` | `"{prefix}&cInvalid class name (1-{max} chars, no spaces or color codes)"` | `class:`（Zh :789 / En :790） | C2 |

**收敛**：新键仅 2 个（calendar 节）；1 处改值。zh/en 必须同步（对称性测试 + scripts/audit-i18n-keys.js 校验）。`calendar:` 节已在 audit sections 白名单？——【待执行者核实：若不白名单，随 R10-L5 脚本修正一并处理或手动核对】。

## D. 回归验证点汇总（跨流 gate）

1. **mvn verify 全绿**（基线 49/49 不得回退）；每个执行流结束 `mvn clean package -DskipTests` 过。
2. **R2-M2 重启绕过关闭**（A1）：打满→重启→限流仍在。
3. **R3-M3 复习数据不覆盖**（A4）：学习会话后 review_count 保持。
4. **R4-M1 快照**（B1）：直达窗口点头像一致。
5. **R4-M2 隐私门**（B2）：showResults=false 提示；隐身笑话页。
6. **R10 系**（D1-D4）：reload 后提醒关断；.bak 生成；clamp WARNING；config 注释。
7. **R12 openAsync**（E1/E2）：连点无闪变；8 GUI 空槽灰玻璃。
8. **R13 系**（F1-F4）：join 无主线程查库；聊天历史打开异步；广场全量单查；quit 清理收敛。
9. **跨流联测**：/lea reload 全家（D6+C4）、season SETTLE 批量发放（C1+A7）、ChatHistory 委托设计零 listener diff（F2）。

## E. 风险与交互

1. **跨流文件重叠**：LearningCalendarGUI（B2+E1 串行）、AIChatManager（C1 已提交，F4 只加方法）、DatabaseManager/PlayerDataStore（A1-A7 单执行者）；LearnCommand/DailyChallengeGUI（C1 白名单，[待C1] 复核后再动）。
2. **批量安置建议**（若 6 流并行）：A（数据层，最重）→ D（方法提供者 letstatistics）→ C（消费 D）；B/E 串行于 LearningCalendarGUI；F 独立。
3. **不改动项声明**：R7-L5 的 /le class GUI 侧不动（只改命令侧）；R3-M4 零行为；rebuild snapshot 分支（SocialListener :302-314）保留兜底。
4. **[待 C1] 清单**：LearnCommand（B1 尾部锚点、B3 庆祝锚点）、LearningCalendarGUI（B2/E1/E2）、DailyChallengeGUI（E1/E2）、AIChatManager（F4 方法、A1 调用点参照）。
5. **版本**：保持 1.0.7-SNAPSHOT；CHANGELOG.md 由编排者统一维护（每流一行）。
