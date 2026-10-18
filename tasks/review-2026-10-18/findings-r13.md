# findings-r13 横切面卫生（审查 Agent 产出）
- 审查范围：全仓 src/main/java（87 文件）+ src/main/resources + src/test + scripts/（8 类机械扫描+语义复核）
- 审查时间：2026-10-18
- 结论摘要：高0条 / 中4条 / 低7条 / 待验证2条 / 豁免挑战0条（回归核验 D14/D20/D22 全 PASS）
- 备注：本文件由编排者按其返回全文原样保存（该 agent 的 Write 被 harness 拒绝两次）

## 回归核验结果（PASS，记录备用）
- **D20（Asia/Shanghai 清零）**：`grep -rn "Asia/Shanghai" src/` → 仅 2 处注释（AchievementManager.java:343、AchievementWallGUI.java:96，均标注"P3-12：统一系统时区（原硬编码）"）。代码面零使用。PASS。
- **D22（C-5/C-6 键面）**：8 个死键（review.complete / achievement.unlocked / admin.export-start / admin.word-add / answer-hint-lore-2 / current-word-lore-2 / wrong-book.title / progress-gui 族）在 zh.yml 均 0 命中；gui-layout.main-menu.wrong-book 已在 config.yml 补录（双侧读点 MainMenu.java:111 + MainMenuListener.java:151）。PASS。
- **D14（config-version 14 三处）**：MESSAGE_CONFIG_VERSION = 14（ConfigManager.java:24）、zh.yml:6 / en.yml:6 config-version: 14。config.yml 为 config-version: 2 且 662 行注释明示"无迁移机制"（与 messages 版本轴不同源，属既有架构）。PASS。

## 高危
（无——线程纪律全图核完后未发现未包裹的异步→Bukkit 直触；SQL 全参数化）

## 中危

### R13-M1 [perf·中·确定] join 主线程同步 DB 写：checkAndAssign 在 PlayerJoinEvent 上执行 SELECT+INSERT 批
- 位置：`listener/PlayerJoinListener.java:93`、`core/DailyChallengeManager.java:145-251`
- 证据：
  ```java
  // PlayerJoinListener.onPlayerJoin（主线程事件）：
  plugin.getDailyChallengeManager().checkAndAssign(event.getPlayer().getUniqueId());
  // DailyChallengeManager.doCheckAndAssign（同步执行）：
  String checkSql = "SELECT COUNT(*) FROM " + tablePrefix + "daily_challenges WHERE uuid = ? AND challenge_date = ?";
  ...
  stmt.executeBatch();   // INSERT 批（分配行为/单词挑战）
  ```
  对比：同函数族在午夜 rollover 已专门移入异步段（LetsLearnEnglish.java:549 注释"原实现逐玩家同步查库串行在主线程，百人服午夜卡顿"），但 join 路径仍同步。
- 复现：多玩家同时进服（或连锁重连风暴）时，每个 join 在主线程串行执行 SELECT COUNT + INSERT batch + ensureWordChallengesAssigned（再一批 INSERT），单连接 connectionLock 排队放大延迟。
- 影响：进服变慢/主线程 tick 抖动（百人服 join 风暴可感知卡顿）。
- 修法：照 DailyChallengeGUI 两段式——join 触发异步 checkAndAssign（其内部已有 stripe lock，异步安全；午夜路径已证明可异步）。

### R13-M2 [perf·中·高] ChatHistoryGUI 打开/翻页主线程同步查询链（计数+列表+逐会话预览 N+1）
- 位置：`gui/ChatHistoryGUI.java:131,138,162`（调用方 `listener/ChatHistoryListener.java:107,117,175`）
- 证据：
  ```java
  int totalSessions = plugin.getDatabaseManager().getChatHistoryCount(player.getUniqueId());
  ...
  List<ChatHistoryEntry> sessions = plugin.getDatabaseManager().getChatSessions(player.getUniqueId(), page, SESSIONS_PER_PAGE);
  ...
  String[] preview = plugin.getDatabaseManager().getSessionPreview(player.getUniqueId(), sessionId); // 循环内 ≤10 次
  ```
- 复现：`/le history` 或聊天历史按钮打开/翻页 → 主线程 1 count + 1 分页 + 每行 1 preview（≤10），共最多 12 次同步查询。
- 影响：页面打开卡顿（单连接下与其它读写互等）。
- 修法：P2-27 已记账（代码注释自认）——按 DailyChallengeGUI.openAsync 两段式异步化，或 DB 层加批量 preview。

### R13-M3 [perf·中·高] 广场/榜单 ALL_PLAYERS 渲染主线程 N+1 滑窗查询（getPlayerStats 逐人懒加载 DB）
- 位置：`core/progress/Leaderboard.java:106-118`（ALL_PLAYERS 分支）、`gui/EnglishSquareGUI.java:132-138,257,316`、`gui/ProfileGUI.java:69`
- 证据：
  ```java
  // Leaderboard.getTopPlayers ALL_PLAYERS 分支：
  List<UUID> activeIds = store.getActivePlayerIds();
  for (UUID id : activeIds) {
      ...
      PlayerDataStore.PlayerStats stats = store.getPlayerStats(id);   // 缓存 miss 时 loadPlayerData 同步查库
      String name = store.getPlayerName(id);
  ```
  代码注释自认 N+1（Leaderboard.java:66-70"getRankedPlayers 还对每行调 getPlayerStats 逐人补读（N+1）"）。
- 复现：打开英语广场"全量玩家"模式或全量榜单——对快照中每个未加载玩家（离线者几乎必 miss）主线程同步 SELECT word_progress。
- 影响：30 人 × 每人一次查询；玩家多时打开广场可观察到卡顿。
- 修法：并入广场两段式异步化，或 ALL_PLAYERS 分支改走已有聚合 SQL getRankedPlayers（per-player N+1 即可消除）。

### R13-M4 [deadcode·中·确定] 长期 map 无清理者清单：4 个 per-player map 只有 shutdown/永不清理
- 位置与证据（扫描：`grep -rn "<map>\.remove\|<map>\.clear" src/main/java`）：
  - `core/ai/AIChatManager.java:110` playerDifficulties（UUID→String）——remove 零处，唯一清理是 1497 行 disable 时 clear()。quit 不清。
  - `core/word/ReviewScheduler.java:23` dueWordCache（UUID→CacheEntry，entry 含词表）——仅三个写路径 remove；中断玩家在途条目无 quit 清理、无 TTL 回收（TTL 只判过期重查，过期 entry 对象仍驻留）。
  - `LetsLearnEnglish.java:136` joinReminderSent（UUID→LocalDate）——quit 不清（javadoc 自认日期懒判定），条目永留。
  - `command/LearnCommand.java:60` celebrationShownDates（UUID→LocalDate）——无任何 remove。
- 复现：长期开服 + 玩家流动 → 4 个 map size = 历史独立玩家数，只增不减。
- 影响：内存缓慢增长。
- 修法：playerDifficulties/dueWordCache 挂 quit（PlayerJoinListener 已有 unload 编排点）；joinReminderSent/celebrationShownDates 挂 quit 或午夜 rollover 按日期值清理旧条目。

## 低危

### R13-L1 [deadcode·低·确定] 零引用方法批（grep 组合核实 count==1）
- 位置（`grep -rn "<name>" src/` 全部命中仅定义处）：
  - core/word/AnswerChecker.java:280 isSpelling（isChoice/isFillBlank 有 5+ 调用，isSpelling 独零）
  - core/word/LearningSession.java:168 getStartTime
  - core/word/WordLearningManager.java:661 getConsecutiveCorrect
  - core/progress/ProgressManagerImpl.java:30 getPlayerProgress（连带 PlayerProgress 类沦为仅测试引用）
  - core/manager/CheckInManager.java:121 getCheckInInfo
  - data/PlayerDataStore.java:507 saveDifficultWords（自述 no-op stub）、:647 getWordMastery
  - data/DatabaseManager.java:526 getChatHistory、:1166 clearWrongCount、:1579 resetCheckInStreak
  - util/MessageUtil.java:224 hasMessageKey
  - util/VersionAdapter.java:46/59/91/98 getMaterial/getSound/getMajorVersion/getMinorVersion
  - core/UpdateChecker.java:199/203 isCheckComplete/getLatestVersion
  - data/ChatFeedbackEntry.java:49 getDifficultyKey
- 修法：批量删除或降 private；API 门面与框架 override（@EventHandler/@Override）已排除。

### R13-L2 [bug·低·确定] 公共 API isPlayerLearning 恒 false：activeSessionPlayers 只有 remove/clear，无 add 调用点
- 位置：`data/PlayerDataStore.java:49,787`、`api/LetsLearnEnglishAPI.java:53-55`
- 证据：
  ```java
  // PlayerDataStore.setPlayerInSession 是唯一 add 点，但全仓零调用（含测试）：
  public void setPlayerInSession(UUID playerId, boolean inSession) {
      if (inSession) { activeSessionPlayers.add(playerId); ...
  // API：
  public boolean isPlayerLearning(Player player) {
      return plugin.getPlayerDataStore().isPlayerInSession(player.getUniqueId());
  ```
  会话真实态在 WordManagerImpl.activeSessions / WordLearningManager.quizStates，setPlayerInSession 从未被这些路径调用。
- 复现：外部插件 api.isPlayerLearning(player) → 永远 false（无论玩家是否在答题）。
- 影响：公共 API 输出错误（外部集成方误判）。
- 修法：在 WordManagerImpl startSession/endSession（或 WordLearningManager 开局/收尾）调 setPlayerInSession(true/false)，或 API 直接查活跃会话真相源。

### R13-L3 [quality·低·确定] ChatFeedbackManager 同段 UPSERT 代码两处逐字重复
- 位置：`core/ai/ChatFeedbackManager.java:211-224` 与 `:237-250`
- 证据：persistAsync 与 persistAfter 的 lambda 体（isEnabled 守卫 + upsertChatFeedback 同 7 参调用 + 同 warning 文案）逐字相同，仅调度方式不同。
- 修法：抽 Runnable writeTask 一处定义，persistAsync 用 runAsync，persistAfter 用 prerequisite.thenRunAsync。

### R13-L4 [quality·低·确定] 调度守卫 helper 在 6 个类重复实现（runAsync/runMain/runTaskSafe/runOnMain 同构）
- 位置：core/social/SocialManager.java:472-486、core/season/SeasonManager.java:735-749、core/manager/ReportGenerator.java:1108-1126、core/manager/AdminCommandHandler.java:837-856、core/achievement/AchievementManager.java:236/451（内联 try/catch）、command/LearnCommand.java:837-843
- 证据：同类 6 行模式（try runTask/runTaskAsynchronously + catch IllegalStateException 静默）在 6 处复制，语义微差（部分带 isEnabled 预检、部分 fine 级日志）。
- 修法：抽 util 静态 SchedulerGuard.runAsync/runMain 单一实现。

### R13-L5 [security·低·高] StatisticsHttpServer 默认密码 "changeme" 且无失败次数限制
- 位置：`core/manager/StatisticsHttpServer.java:57,196-213`
- 证据：
  ```java
  this.authPassword = config.getString("statistics-server.auth.password", "changeme");
  ...
  return authUsername.equals(parts[0]) && authPassword.equals(parts[1]);
  ```
- 复现：管理员仅开 statistics-server.enabled=true 而未改密码 → 白名单内任意机器用 admin/changeme 访问全部统计。
- 影响：局域网内数据暴露（端点只读统计，无写操作）。缓解：IP 白名单先于认证且空名单 fail-closed（403），默认 bind 127.0.0.1。
- 修法：默认密码留空时 start() 记 WARNING 或拒启；比对改 MessageDigest.isEqual 消时序差。

### R13-L6 [quality·低·高] config.yml `general.debug` 键仍零读取（历史审计已记，现状复核仍在）
- 位置：src/main/resources/config.yml（general 段）；grep `general\.debug|"debug"` → 仅 ai-chat.debug（AIChatManager）有读取，general.debug 0 命中。
- 说明：属 dead-config-audit.md 历史清单未清理项（该清单建议"留待专门清理 Story"），非新发现；复核确认仍悬空。
- 修法：删除或接线到全局日志开关。

### R13-L7 [i18n·低·确定] 硬编码玩家可见文本扫描：零新发现
- 扫描：`grep -rn 'sendMessage("' src/main/java --include=*.java` + `setDisplayName("` + 中文串字面量。
- 命中与判定：ReportGenerator 全中文报告文案（豁免 15/19 设计面）；AIChatManager system prompt 英文（AI 提示词非玩家面）；EnglishSquareGUI.java:279 头像名 "&b"+玩家名（玩家名无需 i18n）。
- 结论：零新硬编码玩家文本；sendMessage/getPlayerMessage 玩家面全走 i18n。zh/en 键对称：725/725 完全对称（0 diff，node 展平比对）。audit-i18n-keys.js 重跑 = 基线 521 键 + achievement-wall.category- 唯一误报（与基线一致）。反向扫：23 个 yml 键无全串命中，全部为前缀拼接族（achievement-wall.category-*、word-bank.difficulty-*、calendar.*）——逐一核实调用点（WordBankSelectGUI.java:91、LearningCalendarGUI.java:145-150/220/241、AchievementWallGUI 前缀 + category 枚举），零真死键。

## 待验证区

### R13-V1 [thread·待验证] HttpClient commonPool 回调与 Bukkit 调度器关服窗口竞态
- 假设：AIChatManager 的 CompletableFuture.supplyAsync(...).thenAcceptAsync(..., executor) 自定义 executor 回调为 runTask。R-67 已在多处加 isShuttingDownOrDisabled() 守卫。残留窗口：守卫返回 false 之后、scheduler.runTask 抛 IllegalStateException 之前的一瞬（onDisable 竞态），会向 commonPool/executor 抛未捕获异常（仅日志噪音，无功能损）。
- 验证方法：关服压测+日志搜 IllegalStateException 栈；或代码层把 runTask 也包 try/catch（与 SocialManager.runMain 同款）。

### R13-V2 [data·待验证] 单连接下 Statistics HTTP（2 线程池）与主线程写并发时的查询延迟叠乘
- 假设：StatisticsHttpServer.buildStatsJson 每 60s 被 dashboard 轮询，6 个聚合查询全部走 synchronized(connectionLock) 单连接；玩家高峰时主线程 DB 操作会与该并发互等。
- 验证方法：实机 50+ 人开 dashboard 观察 TPS/轮询耗时；静态面单连接为 accepted B4，本项仅量化影响。

## 豁免挑战
（无——全部命中对照 §E 核验后放行。）

## 范围外发现
- listener/PlayerJoinListener.java:26-31 每次 join 主线程 3 连同步 DB 读（loadPlayerData/loadPlayerAchievements/loadProfileSettings）——归 R3 复核 join 同步加载语义是否接受。
- core/manager/HotReloadManager.java:26-45 reloadAll 未重排任何 runTaskTimer（leaderboard/midnight/social 周期任务），依赖"周期改动需重启"注释语义——归 R10 核对文档承诺一致性（D13 关联）。
- core/manager/StatisticsHttpServer.java:354-475 内嵌 HTML 无玩家输入拼接，非注入面；仅记。

## 正面结论（扫描零命中的类目）
- 线程纪律全图：31 个异步调用点（runTaskAsynchronously 15 + CompletableFuture 10 + new Thread 2 + HttpClient 2 + Executor 2，命令见 grep 'runTaskAsynchronously|CompletableFuture|new Thread|thenAccept|HttpClient|ExecutorService'）逐点回溯：全部包 runTask/仅 DB/仅 HTTP/仅 logger，零未包裹 Bukkit 直触。TtsManager 异步写文件为纯 IO。
- 任务泄漏：runTaskLater/runTaskTimer 全部 22 处逐一核对取消链——DuelManager 三任务族 put/remove/cancel 12 对全成对（含 selfCancel 模式）；midnightTask 自递归 + onDisable cancel 完整；学习倒计时 answerTaskId + cancelAnswerTimer 全路径配对；无孤儿任务。
- SQL：全仓零 createStatement/字符串拼接注入（唯一动态部分是 tablePrefix 与 IN 占位符 ? 展开，值全 setString/setLong）；DatabaseManager 86 个 public 方法中仅 getConnection/getConnectionLock 无锁（设计），其余全部 synchronized(connectionLock) 或 stripe lock。
- D20/D22/D14 三项回归 PASS（见顶部）。

## 审查覆盖声明
- 实际读/grep 的文件：全 87 主源文件中精读 ~50 个核心文件（LetsLearnEnglish / PlayerDataStore / DatabaseManager / WordLearningManager / WordManagerImpl / AIChatManager / TtsManager / ChatFeedbackManager / AchievementManager / DailyChallengeManager / CheckInManager / ReviewScheduler / LearningSession / AnswerChecker / WordModes / Leaderboard / RewardEngine / SeasonManager / SocialManager / DuelManager / UpdateChecker / StatisticsHttpServer / ReportGenerator / AdminCommandHandler / HotReloadManager / LLEPapiExpansion / LetsLearnEnglishAPI / VersionAdapter / SoundManager / ConfirmationGUI / GuiNavigationManager / MainMenu / MainMenuListener / ChatAnswerListener / ChatScenarioListener / ChatHistoryGUI / ChatHistoryListener / DailyChallengeGUI / LearningCalendarGUI / EnglishSquareGUI / ProfileGUI / WordLearningGUI / WordLearningListener / PlayerJoinListener / DuelListener / SocialListener / OnboardingManager / ConfigManager / MessageUtil / WordBankSelectGUI）及 8 个测试文件全部、config.yml / zh.yml / en.yml 全文、scripts/audit-i18n-keys.js 重跑、dead-config-audit.md 对照。
- 未完成/未做（诚实申报）：安全域注入面深挖未闭环——ProxyMessenger 全部子通道字段级 sanitize、ScenarioCodec 解码容错、ReportGenerator/DataExporter CSV/TXT 转义、个人场景名→文件路径穿越（PersonalScenarioStore）四项仅抽查（sanitizeBroadcastName 与 TtsManager.sanitizeFileName 已确认），原派安全子代理被中止——建议转 R2/R3/R9 域复核或补派。权限点表：代码侧仅 3 节点被校验（letslearnenglish.admin/.report/.scenario.import，见 grep hasPermission 16 处），plugin.yml 声明 vs 校验逐条比对未完成（B5 四节点未定位到具体名单）。测试质量评估：8 文件均为纯函数/枚举契约测试（WordTest/LearningSessionTest/ComboTest/PlayerProgressTest/CountdownTierTest/ConversationFeedbackTest/AchievementDefinitionTest/ProxyFrameParseTest），测的是真风险（组合乘数边界、JSON salvage、帧解析实伤场景），但缺：WordModes 判分边界、AnswerChecker 全角/前后缀、SeasonManager.currentSeasonId 纯函数——可测化状态机遗漏，与 R1 域职责重叠。
