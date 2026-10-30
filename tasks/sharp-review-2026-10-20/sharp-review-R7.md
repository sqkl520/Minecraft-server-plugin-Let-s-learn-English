# 锐评 R7 · 性能与扩展天花板

> 2026-10-20 战略锐评 · Minecraft 服务器性能工程师视角 · 只读 · agentId a671bd44741e48311
> 编排者复核注记：三处关键指控实测属实——① `pom.xml:49-53` mysql-connector-j 为 `scope=compile` 且全 pom 无 shade/assembly、plugin.yml 无 libraries、产物 JAR 内 `com/mysql` 类计数为 0——"database.type 改 mysql 即 ClassNotFoundException"实锤喵；② `DatabaseManager` 仅 :2990 一处 `PRAGMA table_info`（迁移元数据用），确无 `journal_mode=WAL`/`busy_timeout`——"零调优 PRAGMA"属实喵；③ `cleanupOldChatHistory` 全库唯一调用点 `LetsLearnEnglish.java:165`（启动期）——"长 uptime 不重启则 AI 历史只增不清"属实喵；④ `learning_log` 零写入历史遗留属实（`:1402` 注释自陈）。
> **编排者勘误两条**：(a) "pronounceCooldown 只 put 不删、quit 无人管"**证伪**——实际在 `WordLearningListener.java:29`（非 ChatAnswerListener），且 :45 已有 quit remove（:38 注释明记此坑已修），R7 引用了错误文件与过期状态喵；(b) "EnglishSquareGUI 全量聚合在主线程"**已过时**——C1 批 R8-M1 修复已改 `openSquareWithSnapshot` 两段式（查询在异步线程），主线程冻结不成立，但全表 GROUP BY 本身仍独占 connectionLock，压力从"卡 tick"转为"排队头"，问题仍在、性质不同喵。3.6 节"join 风暴+全库聚合"天花板判断方向仍成立。

## 1. 一句话判决

**功能密度是商业级的，数据访问路径是单机桌面级的**：一个由全局 `connectionLock` 串起来的单连接同步 SQL 模型，被直接接到了答题/签到/开界面/进服这些"每人每分钟几十次"的热路径上——50 人同时在线就是纸面上的舒适区上限，百人规模要靠运气，跨服则是数据正确性问题而不是性能问题喵。

## 2. 致命伤 Top3

**① 一次答对 ≈ 6~9 次串行同步 SQL，且全部经过同一把全局锁**（`WordLearningManager.java:256-270` → `ChatAnswerListener.java:255-256`）。
**② 自动保存是"全量重写"而非增量脏写**（`PlayerDataStore.java:578-640`：持锁遍历整个 mastery map 逐行 `addBatch`），100 人 × 1000 词的每 5 分钟保存窗会独占 DB 通道 3~12 秒喵。
**③ 跨服/多实例没有并发安全模型**：`savePlayerData` 的绝对值全量 UPSERT + 各服独立缓存 = 后写者整片覆盖喵。

## 3. 分条炮击

### 3.1 主线程 IO 清单（同步 = 调用线程直接持锁做 JDBC）

**主线程硬命中（tick 阻塞面）**

| 路径 | 证据 | 每次调用 SQL 往返 |
|---|---|---|
| 进服加载 | `PlayerJoinListener.java:27`（3 条 SELECT，其中 `PlayerDataStore.java:347` 全量读 word_progress）→ `:28` 成就 SELECT → `:29` profile SELECT → `:65` onboarding `getOnboardingStep`（`OnboardingManager.java:108`）| 5~6 |
| 退出 | `PlayerJoinListener.java:118` → `PlayerDataStore.java:867` = savePlayerData 全量批 + profile UPSERT；再 `:120` 又 save 一次 profile | 2 + N 行 |
| 答对一题 | `DailyChallengeManager.java:411`（SELECT+UPDATE，缺失时再 +`checkAndAssign`+重查 SELECT）、`:498`（combo≥5）、`:661`（`hasPendingWordReward` 每会话首题查库）、`:673`+`:677`(+可能`:680`)、`RewardEngine.java:294`(`adjustCoins` `DatabaseManager.java:1471`)、`AchievementManager.java:427`→`PlayerDataStore.java:1144`（≥3 连击每题 2 条写）| **6~9** |
| 会话结算 | `WordLearningManager.java:768` recordSession + `AchievementManager.java:372-419`（fetchSessionDays/fetchReviewDays/COUNT DISTINCT/getCoinsEarned + 每成就 1~2 写）| 6~10 |
| 打开"英语广场/全量页" | `EnglishSquareGUI.java:137` `getActiveSquareEntries(Integer.MAX_VALUE)`——**含 `word_progress` 全表 GROUP BY 聚合，在主线程**；`:146` 周/月榜 `getRankedPlayersForPeriod`（GROUP BY）；`:285` `getCoinsEarned` | 2~3 个重查询 |
| 点隐私开关 | `MiscGUIListener.java:152` 同步 saveProfileSettings | 1 |
| PAPI 计分板（30s TTL 过期后主线程重建） | `LLEPapiExpansion.java:324` `getCheckInData`、`:339` longest_streak SELECT、`:317` getCoinsEarned、`:344` `getPlayerStats` **cache miss 时全量 loadPlayerData**（离线目标必 miss）、`:217` `getPlayerRank` 每次对 `scores` 全表 `sort` = O(N log N) | 4 + 排序 |
| 决斗 | 双方都进倒计时 → 每秒 tick ×2（`DuelManager.java:528`）、`:835` | 0 |

**异步但持同一把锁的（间接卡主线程）**：AI 每轮 2~3 次 `insertChatHistory`（`AIChatManager.java:902/965/522`，跑在 **ForkJoinPool.commonPool** 而非 Bukkit 异步池）、`incrementTodayAiRound` 读+写（`PlayerDataStore.java:177/206`）、`incrementWrongCount`（`:533`）、每日挑战/签到/领奖全链。

**串行化排队估算**：把 `connectionLock` 看成单服务台，利用率 ρ = λ×S。百人活跃答题（每人 6 秒一答 → λ≈17/s，×7 语句 ≈120 trx/s，S=0.15ms 内网 MySQL）→ ρ≈0.02，平均 <1ms，**但排队完全由队头阻塞决定**：保存窗里一个玩家的全量批（S≈30~120ms SQLite / 15~60ms MySQL）落在队首，后面所有等待者的完成时间就是"前面剩余批量之和"；百人一轮保存累计持锁 3~12 s（`LetsLearnEnglish.java:389-397`）。MySQL 侧稍好（可并发读），SQLite 更糟——**全程零调优 PRAGMA**：没有 `journal_mode=WAL`、没有 `busy_timeout`（`DatabaseManager.java:78-82` 只有裸 `DriverManager.getConnection`，编排者复核属实），每写一次就 fsync。若有人把 join 链挪到异步（`PlayerJoinListener.java:99` 那个块），主线程仍会在 GUI/PAPI 路径上撞上这把锁，尾延迟同样爆喵。

**顺带一个部署级地雷**：`pom.xml:49-54` 的 mysql-connector 是 `compile`，但 `pom.xml` 无 shade/assembly（grep 零命中）、`plugin.yml` 无 `libraries:`，产物 `target/*.jar` 里 0 个 `com/mysql` 类（编排者 unzip 复核实锤）——**把 `database.type` 改成 mysql 就 ClassNotFoundException**，wiki 也没写要手动放驱动喵。

### 3.2 内存模型

按"在线玩家 × 该玩家全部学过的词"线性增长，**无淘汰、只靠 quit 清理**（`PlayerDataStore.java:867-883` 15 个 map 全清，设计意图正确）。问题：`wordMasteryCache` 等 **5 个 map 各存一份同一个词字符串引用** + boxed Double/Long/Integer，粗估 400~500 B/词/玩家；重度玩家 2000~5000 词 → 单人 0.8~2.5 MB；百人满缓存 80~250 MB 常驻堆——挤的就是 MC 自己的堆喵。只增不删的漏点：`ChatAnswerListener.java:40` 发音冷却 `pronounceCooldown` 全仓无 remove（连 quit 编排点 `PlayerJoinListener.java:108-146` 都没管它）喵。启动代价本身不大（词库 995 词 + 双语 messages 89 KB + config 34 KB，同步在 `onEnable`），成就靠 `Achievement.values()` 无需加载，真正的启动风险是 `databaseManager.initialize()`（`LetsLearnEnglish.java:157`）在主线程跑 17 个 CREATE TABLE + 13 次 `addColumnIfMissing`/`createIndexIfMissing` 元数据查询——大表首启能把服务器卡到看门狗喵。

### 3.3 无界增长与索引

- **有清理**：`ai_chat_history`/`ai_chat_feedback`/`ai_round_counters`（`DatabaseManager.java:698-742`，30 天）+ `social_feed`/`social_cheers`（`:2138`）。但 `cleanupOldChatHistory` **只在启动跑一次**（`LetsLearnEnglish.java:165`，编排者复核全库唯一调用点），长周期不重启的服 AI 历史只增不清喵。
- **零清理**：`player_sessions`（1 行/人/天）、`word_progress`（1 行/人/词，永久）、`daily_challenges`(3/人/天)、`daily_challenge_words`(5/人/天)、`learning_log`（`DatabaseManager.java:158` 建表但**全库零写入**）、`player_achievements`（死表，`:2555` 还跟着删）。
- **索引缺口**：`ai_chat_history(player_uuid)` 单列前缀已被 `idx_chat_player_time` 覆盖（OK），但 `cleanupOldChatHistory` 的 `WHERE timestamp <` **无 timestamp 索引 → 全表扫删**；`learning_log` 零索引；`getRankedPlayers` 的 `ORDER BY total_score DESC`（`PlayerDataStore.java:1188`）无索引 → 全表排序，而这条在每次开广场"总榜"和 PAPI 路径上；`getActiveSquareEntries` 的 `word_progress GROUP BY uuid` 随全库行数线性变重（万人×千词 = 千万行聚合，SQLite 上 1~5 s 主线程冻结）喵。
- **无 `VACUUM`/`ANALYZE` 例行任务**：SQLite 死行只涨文件（备份 `VACUUM INTO` 会重写目标库但不收缩源库）。MySQL 侧 `addColumnIfMissing`（`DatabaseManager.java:2969`）在亿级 `word_progress` 上是 INSTANT 或全表重建五五开，且 `createIndexIfMissing` 的 `CREATE INDEX` 是阻塞 DDL，全都在主线程 `onEnable` 里喵。

### 3.4 定时任务

每秒/每 tick：**零**（倒计时是 per-player 20tick，主线程纯内存渲染）。每 6000tick(5min) 主线程：`ReviewScheduler.checkAndNotifyReviews`（`LetsLearnEnglish.java:400`）——**O(在线玩家 × 该玩家全部词数)**，100 人×1000 词 = 10 万次 map 遍历 + 装箱，估 5~20 ms 单 tick 尖刺；自动保存快照也在此节拍上。每 300s 异步：榜单 3 查询 + 赛季榜（`player_sessions` 季窗 GROUP BY）+ feed 刷新。午夜（`LetsLearnEnglish.java:570-640`）已两段式化（重活进异步，`countUnclaimedForDate`+`checkAndAssign` 每人各一次），是这份审计里少见的"已修好"喵。`/lea reload` 是主线程全员落盘 + clearCache 风暴（`HotReloadManager.java:29-67`）喵。

### 3.5 网络

**全局并发上限：无**。只有 per-player 冷却 3 s（`AIChatManager.java:888`）+ 每日 30 轮（`:836`）+ 每人单在途（`:865`）。100 人理论上 ~33 req/s 打 DeepSeek，无信号量、无排队、无退避重试（`isAuthCircuitOpen` `:1074` 只熔断 401/403，429 不熔断不重试——这点反而是对的，不会打爆上游，但也意味着高峰直接失败给用户）。真正的隐患是**HTTP 阻塞跑在 `CompletableFuture.supplyAsync` 的 commonPool 上**（`:913`），executor 参数（`:1219`）把主线程 scheduler 当 executor 交给 `thenAcceptAsync`——占用/阻塞通用池线程 15 s，同池还跑着 AI 写库；机器上其他用 commonPool 的插件会互相拖累喵。TTS：VoiceRSS 下载到磁盘缓存（`TtsManager.java:85-180`），**从不推给客户端**，玩家听到的是 `playPronunciationCue` 的一声 NOTE_BLOCK（`:212`）——音频下发根本没做喵。

### 3.6 扩展天花板

500 人先炸在 **join 风暴（5~6 条串行同步 SQL/人）+ 保存窗队头阻塞 + `getActiveSquareEntries` 全库聚合** 三处，表现为固定周期的 TPS 塌陷而非持续低 TPS。多服共享 **MySQL**：跨进程写同一 SQLite 会直接 SQLITE_BUSY 锁库（无 busy_timeout），所以多服只能走 MySQL——但 `savePlayerData` 全量绝对值覆盖 + 每服独立内存缓存 = **A 服在线玩家的学习进度会被 B 服的过期快照整片写回覆盖**，`setConversationCount`/`setWrongBookCleared` 同样是绝对值 UPSERT；`ProxyMessenger` 只解决了消息广播，没解决数据归属喵。赛季结算用条件 UPDATE 抢占（`SeasonManager.java:407`+`tryClaimSettlement`）是唯一做对的多实例安全点喵。

### 3.7 可观测性

**基本为零**：只有启动耗时一行（`LetsLearnEnglish.java:245`）和 `ai-chat.debug` 的 API 毫秒日志（`AIChatManager.java:919`），没有慢查询日志、没有锁等待埋点、没有 bstats/metrics 依赖（`pom.xml` 仅 paper-api/mysql/vault/papi）。最小成本方案：包一个 `executeTracked(sql, Runnable)`——`nanoTime` 计语句耗时 + 进锁前记录等待时长，维护 `ConcurrentHashMap<String,LongAdder>`，每 60 s 在异步任务里打印"Top5 慢语句 / connectionLock 累计等待 / 最长持锁"三行；再加 `/lea perf` 输出缓存条目数与 map 尺寸喵。

### 3.8 上线前必须压测的 5 个场景

1. **20 人 join 风暴**：同时进服，主线程 p99 tick 与连接锁等待（当前必现 100~600 ms 尖刺）。
2. **百人持续答题 30 分钟 + 跨过 5 分钟自动保存窗**：统计每秒 SQL 往返、保存窗内答题响应延迟膨胀倍数。
3. **20 人连点 `/le square all` 与周/月榜**：`word_progress` 全表聚合 × 主线程，这是最可能直接把 TPS 打到 <5 的一格。
4. **长 uptime 不重启 + AI 重度使用**：验证 `cleanupOldChatHistory` 只在启动跑导致的表增长与 GUI 分页查询退化曲线。
5. **双 Paper 服共享一个 MySQL 库**：A/B 各 30 人并发答题 + 同服 reload + 午夜 rollover 撞车，验证全量 UPSERT 的进度覆盖与成就/轮次计数错乱（这个现在一定会脏数据）喵。

## 4. 说句公道话

线程纪律的**意识**明显高于同类插件：异步两段式开 GUI（`DailyChallengeGUI.openAsync`、`ChatHistoryGUI.openAsync`）、volatile FeedCache 快照、PAPI 30s TTL + 5000 条随机淘汰、自动保存早已异步、午夜 rollover 专门拆成两段、`getConnectionLock()` 把裸用点全部收编并在 javadoc 里留了迁移账本（`DatabaseManager.java:487-505`）——这些都是对的方向，而且注释里连自己欠的债都写清楚了喵。SQLite 默认对 20~50 人的教学服完全够用，不是所有批评都适用它的实际部署规模喵。

## 5. 只改一件事

**把每次答对路径上的 SQL 从 6~9 条压到 1~2 条**：将每日挑战进度/连击峰值/金币累计/对话计数全部改成内存累加 + 30~60 s 脏标记批量落库（同一条合并事务），顺手把 `savePlayerData` 从"全量重写 mastery map"改成只写脏词。这一改动同时消掉主线程 IO 的最大宗、保存窗的队头阻塞、以及跨服全量覆盖的杀伤半径——单变量、不动功能语义、投入产出比全仓第一喵。
