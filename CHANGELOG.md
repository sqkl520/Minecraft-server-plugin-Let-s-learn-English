# Changelog

## [1.0.7] - 2026-10-07

> 本版本包含两批改动：v1.0.7 迭代开发（13 个 Story）+ 全项目只读扫描后的缺陷修复批次（中文服止血 / 并发收口 / 仓库卫生）。

### Fixed
- **[Phase 1 fan-in]** `RewardEngine` 头部注释修正：今日金币缓存清空入口已由 `HotReloadManager.reloadAll` 直调 `resetDailyCoinsCache()`，移除过时的"须由流 E 调用"表述
- **[Phase 1 fan-in]** `ConfigManager.MESSAGE_CONFIG_VERSION` 6→7：messages/zh.yml 与 en.yml 已随 Phase 1 新增键升至 v7，代码侧同步升版，已部署服务器重启/重载后才会覆盖下发新消息文件
- **[Phase 1 fan-in]** `HotReloadManager.reloadAll` 补调 `RewardEngine.resetDailyCoinsCache()`：RewardEngine 实例常驻不重建，今日金币累计缓存随 reload 清空（day 边界仍按自然日懒清零）
- **[Phase 1 止血流 C / M12]** 行为挑战领奖先到账后标记：`MiscGUIListener` 转账成功后才调 `claimReward` 置 `claimed=1`；转账失败不标记、可重领，玩家收到 `daily.claim-failed` 失败提示（不暴露异常细节）
- **[Phase 1 止血流 C / M12]** 单词挑战自动到账先到账后标记：`onAllWordChallengesComplete` 到账成功后才记 `wordChallengeClaimedToday`；失败时发 `daily-challenge.reward-pending` 提示并延迟到下次 `onWordCorrect` 触发时重试补发（庆祝 GUI 仅成功时弹出）
- **[Phase 1 止血流 A / H2]** `stopQuiz` 中途停止不再无条件发放全额会话奖励：已答题数 ≤1（含超时题）零奖励且告知玩家；比例 ≥50% 全额发放；中间段按答题比例折算发放（跨 `WordLearningManager.stopQuiz` + `RewardEngine` 比例门）
- **[Phase 1 止血流 A / C3]** `word-system.daily-limit`（默认 50）真正生效：`submitAnswer`/`handleTimeout` 判分处经流 E 接口 `incrementTodayAnswerCount` 累计今日答题数（含对错与超时）；计数达上限后继续答题仍允许（防刷不防学），`RewardEngine` 跳过单题/会话/复习发币并告知玩家（`progress.daily-limit-no-reward`）
- **[Phase 1 止血流 A / C3]** 新增 `money.daily-cap`（默认 500/人/天）真正生效：`RewardEngine` 每次发币前检查当日已发金币（本类 `todayCoinsCache` 以"分"记账，跨天清零），大额单笔按"部分发放到 cap"绝不超发，超限告知玩家（`progress.daily-cap-reached`），金币统计改以实际发放额记账
- **[Phase 1 止血流 A / C3]** `RewardEngine` 新增 `resetDailyCoinsCache()`：每日金币累计缓存的 reload 清空入口（实例常驻不重建），由流 E 在 reload 流程调用
- **[Phase 1 止血流 A / H9]** `HotReloadManager.reloadAll` 清缓存前先对在线玩家落盘（`savePlayerData` + `saveProfileSettings`），修复 reload 丢进度
- **[Phase 1 止血流 A / H9]** `PlayerJoinListener.onQuit` 接入 `endQuiz` + `cancelPractice`，下线不留僵尸 QuizState 与练习态
- **[Phase 1 止血流 A / H9]** `AchievementManager.clearCache` 同步刷新懒加载的 `achievementConfig` 缓存，reload 后成就配置即时生效
- **[Phase 1 打回修复 / Q1]** 自然走完会话拿不到奖励：`buildResult` 在 completed 时先 `quizStates.remove(id)`，随后 `showSessionComplete` 内 `quizStates.get` 必为 null，会话金币/成就检查/学习日历/SESSION_3 挑战整段跳过；改为把 `maxComboReached`/`mode` 装进 `AnswerResult` 带过去，结算直接用 result 携带的数据不再 map 重查（stopQuiz 比例门路径不受影响，奖励公式不变）
- **[Phase 1 打回修复 / B2]** 答对广播查错隐私对象：`ChatAnswerListener.broadcastCorrectAnswer` 循环内取的是答题者自己的 `ProfileSettings` 而非收件人的；改为查收件人 `online.getUniqueId()` 的 `isShowAnswers()`，叠加 showAnswers 默认 false 后广播恢复正常可达
- **[Phase 1 打回修复 / Q2]** 删除 `PlayerDataStore` 的死记账 API：`addTodayCoinsEarned`/`getTodayCoinsEarned` 零外部调用者（RewardEngine 自有分级精度 `todayCoinsCache` 已接线），连带删除 `TodayCoins` 内部类与 `todayCoinsEarnedCache` 字段；今日答题计数与今日 AI 轮次两套计数器保留

### Added
- **[测试安全网]** 新增 `WordLearningManagerComboTest`：以纯逻辑单测锁定连击倍率表（0~2 连=1.0x、3~4=1.5x、5~6=2.0x、7~9=2.5x、≥10=3.0x，含负数回落 1.0x），直接测真实的 `WordLearningManager.getComboMultiplier(int)`（倍率表实际在此类，非 RewardEngine），构造器用 Mockito mock 的插件实例满足，不拉起任何 Bukkit 运行时
- **[测试安全网]** JaCoCo 新增 `check` 门禁（绑定 `verify` 阶段，BUNDLE 行覆盖最低 0.01=1%）：这是起步地板，当前行覆盖约 4%，后续逐版本上调；故意不在 `package -DskipTests` 快速构建触发，运行 `mvn verify` 才校验
- **[Phase 0 安全基线]** `MessageUtil` 新增 null-safe 取消息 helper：`getMessageSafe(key, ...)` / `getPlayerMessageSafe(player, key, ...)` 在 message 配置缺失、key 缺失或占位符为 null 时，统一记 WARNING 并回落到可见诊断占位串 `&c[?key]`，绝不返回 null 或抛 NPE；配套 `hasMessageKey(lang, key)` 供启动审计使用。仅新增 API、不重构现有调用点，不改既有行为
- **[Phase 0 安全基线]** `ConfigManager.validateRequiredKeys()`：插件启动时（`onEnable` 载入 config 与 messages 后调用一次）扫描预期必填的 config key 与 i18n key，缺失则打 WARNING（`[Config Audit]` / `[i18n Audit]`）。纯告警，绝不改控制流、不禁用任何功能、不改写配置
- **[Phase 0 安全基线]** 新增 `tasks/dead-config-audit.md` 死配置审计清单：以全路径 grep 查证，确认 `word-system.modes.*`（H6：整棵子树死，模式硬编码于 `WordManagerImpl.VALID_MODES`）、`word-system.daily-limit`、`daily-challenges.rewards.*` / `challenges-per-day`、`difficulty-settings.*`、`proxy.*` 等共 21 条必查死配置，另记录约 17 条额外死配置（含模块级 `enabled` 开关族从不 gating）。仅审计不删除
- **[Phase 0 词库流水线]** 新增 `scripts/gen-words.js` 词库生成与自检脚本（零第三方依赖，位于 resources 之外不进 JAR；支持 `generate` 与 `check <file>`，镜像 `WordBankValidator` 规则并校验 YAML 往返一致性），并产出 50 词 beginner 评审样本 `resources/words/sample-beginner.yml`（未激活，pom 已排除出 JAR；待人工抽查音标后提升为正式词库）；`resources/words/README.md` 记录词表来源与许可
- **[Phase 0 安全基线]** 新增 `tasks/db-baseline.md` 数据库基线：`schema.sql` 与代码建表语句逐表逐列对齐（12 表 + 2 索引，无字段漂移/无缺列/无缺索引）、`player_name` 写入方案（仅方案，Phase 1 落地）、`synchronized` 串行化 vs 单线程 DB 执行器的评估与升级建议，并记录外部类仍裸用 `getConnection()` 的残余缺口

### Refactored
- 彻底分离学习分数与金币统计：新增 `total_coins_earned`/`total_coins_spent`，金币榜、成就和金币奖励记账不再读取 `total_score`
- 统计 HTTP 服务默认绑定 `127.0.0.1`，可通过 `statistics-server.bind-address` 显式开放到内网地址
- SQLite 自动备份执行 WAL checkpoint 和 quick_check，并将玩家数据保存从 `INSERT OR REPLACE` 改为保留金币字段的冲突更新
- 每日挑战和单词挑战的 Vault 金币奖励也纳入独立金币累计统计
- 修复 DatabaseManager 使用共享 JDBC Connection 时被 try-with-resources 意外关闭的问题，查询只关闭 Statement/ResultSet
- 修复自动保存任务在异步线程访问 Bukkit 在线玩家集合的问题，改为主线程快照 UUID 后异步持久化
- 为 AI 对话上下文增加按玩家同步保护，并使用不可变请求快照，避免主线程与 HTTP 回调并发修改 ArrayDeque
- 统计 HTTP 服务保存并关闭专用 ExecutorService，running 状态改为 volatile，避免插件停用后线程泄漏
- 增加 AI 单玩家请求进行中保护，避免连续输入导致 API 响应乱序
- **[缺陷修复批次]** GUI 槽位定义收敛为单一真相：`ChatHistoryGUI.SESSION_SLOTS`、`WrongBookGUI.WORD_SLOTS`、`LeaderboardGUI.ENTRY_SLOTS`/`BACK_SLOT`/`TAB_*_SLOT` 全部提为 `public static final` 常量，监听器引用常量而不再各写一份魔法数字（此前 GUI 与监听器各有一份槽位表，改一处就会静默错位）
- **[缺陷修复批次]** `SocialListener` / `WordLearningListener` 里 8 处硬编码槽位改为读 `gui-layout` 配置（`english-square` 的 back/prev-page/next-page/四个模式键、`word-learning` 的 back/stop/start、`leaderboard` 的 back/三个 tab）—— 与「GUI 渲染读配置、监听器判定写死数字」这一 D-03/D-04/D-05/D-06 的同一根因同源，配置一旦改动点击就会静默失效
- **[缺陷修复批次]** `config.yml` `gui-layout` 新增 `leaderboard` 段（`back-button` / `tab-coins` / `tab-words` / `tab-combo`，值 8/0/1/2）
- **[缺陷修复批次]** `SocialListener.handleLeaderboardGUI` 原先 `int tab = slot;` 直接把槽位当 tab 下标，配置一挪就会打开错误的榜单；改为按槽位显式映射到 tab 序号
- **[缺陷修复批次]** `SocialListener` 的页码解析改调 `GUIUtils.extractPageFromTitle()`，`LeaderboardGUI` 的 `ENTRY_SLOTS` 重复数组与 `SocialListener` 的副本一并删除
- **[缺陷修复批次]** `PlayerDataStore.getRankedPlayers()` / `getRankedPlayersForPeriod()` 不再调用 Bukkit API：`player_name` 为空时回落到 `uuid.toString()`；后者 SQL 改为 `LEFT JOIN player_data` 取 `MAX(p.player_name)`，兼容 MySQL `ONLY_FULL_GROUP_BY`（原实现在插件启动的异步任务里调用 `Bukkit.getOfflinePlayer()`，属未定义行为）
- **[缺陷修复批次]** `RewardEngine` 删除从未接线的经验奖励链与成就奖励方法（`giveSessionReward`/`giveExpReward`/`giveAchievementReward`，均零调用者），文件 228 → 186 行
- **[缺陷修复批次]** `WordManagerImpl` 删除零调用方法 `getSession()`/`getWordMastery()`，并修正 `getWordBank` 挂错的 javadoc
- **[缺陷修复批次]** `AIChatManager` 9 个配置字段（`apiKey`/`apiUrl`/`model`/`maxContextTokens`/`timeoutSeconds`/`rateLimitSeconds`/`maxInputLength`/`maxPromptLength`/`debug`）补 `volatile`；删除零读者的死字段 `maxContextPairs`
- **[缺陷修复批次]** `SoundManager.soundCache` 由 `HashMap` 改为 `ConcurrentHashMap`、`enabled` 加 `volatile`（`playSound` 存在异步可达路径：`AIChatManager.endConversation` → `AchievementManager.tryUnlock`，会与 `reloadSoundCache()` 的 clear+重建竞态）
- **[缺陷修复批次]** `HotReloadManager.reloadAll()` 补调 `reloadWordBanks()`（消除重复代码）与 `SoundManager.reloadSoundCache()`（此前是死方法），`/lea reload` 现在会刷新音效缓存
- **[缺陷修复批次]** `ProfileGUI` 金币统计卡改为显示真实 Vault 余额（原实现把 `stats-coins-value` 的 `{coins}` 塞进了 `getTotalScore()`，同文件已算好的余额从未使用）；无 Vault 时显示 `0`
- **[缺陷修复批次]** `ConfirmationGUI` 两阶段统一由 `showConfirmPage()` 生成，取消按钮抽为 `CANCEL_SLOT`/`CONFIRM_SLOT` 常量，物品构建抽为带 `Material` 参数的 `createItem()`

### Fixed
- **[缺陷修复批次]** 中文服「单词对决」退出按钮完全失效：`DuelGUIListener` 的标题判定写死 `&6&lWord Duel`，而中文标题是 `&6&l单词对决`，默认语言为 zh 时判据不命中；改为读取 `duel.gui-title` 配置比对（依次尝试玩家语言 → zh → en），并保留 `contains` 回退。文件里算了却从未使用的 `duelTitleZh`/`duelTitleEn` 死变量一并清理
- **[缺陷修复批次]** 对决界面 barrier 可被拖走：原 `return` 位于 `setCancelled(true)` 之前，未取消路径可达；改为判定命中后立刻 `setCancelled(true)`
- **[缺陷修复批次]** 补齐 `InventoryDragEvent` 处理（`DuelGUIListener` 与 `MainMenuListener`）：拖拽是独立事件，`InventoryClickEvent` 的 `setCancelled` 拦不住，此前展示用玻璃板/绿宝石可被拖进玩家背包；只在拖拽触及界面上半部分时取消，不影响玩家整理自己背包
- **[缺陷修复批次]** 中文服英语广场翻页永远停在第 1 页：`extractPageFromTitle()` 按空格切分找 `"Page"` 词元，中文标题 `英语广场 - 第1/3页` 切不出该词元而返回默认值 1；新增 `GUIUtils.extractPageFromTitle()` 用正则取标题中第一个整数，覆盖 `Page 2` / `第2页` / `第 2 / 3 页` 等格式
- **[缺陷修复批次]** 英语广场/个人主页的玩家名子串误配：`extractTargetIdFromTitle()` 改用「最长匹配」避免 `Bob` 命中 `Bobby`；解析失败改为返回 `null` + 记录 warning，不再静默回落到查看者自己；候选集合并入排行榜与广场的展示列表，修复排行榜上离线玩家主页解析失败的问题
- **[缺陷修复批次]** 个人主页 slot 26「设置」在查看他人主页时可修改查看者自己的隐私设置；改为仅当 `targetId` 为本人时才打开设置页（他人主页该槽位本就是「设置已锁定」）
- **[缺陷修复批次]** AI 对话历史第 9 条会话点不开：会话条目从 slot 0 起连占 0~9，第 9 条覆盖了 `BACK_SLOT = 8`，而监听器优先判断返回键；会话起始槽改为 9，`SESSION_SLOTS` 显式列出
- **[缺陷修复批次]** 对话历史点开的会话可能与所选不符：原实现在点击时重查数据库并用 slot 反算下标，翻页期间若有新会话插入会指向错误会话；改为在条目 lore 写入 `SessionID` 标记，监听器据此定位，不再查库
- **[缺陷修复批次]** 错题本每页最后 9 个单词显示正常但点不动：槽位映射产出 slot 9~53，而监听器只接受 `slot <= 44`；改为 GUI 与监听器共用 `WORD_SLOTS`（9~46 + 48/50/52/53，避开 47/49/51 导航键），每页容量由数组长度推导
- **[缺陷修复批次]** 错题本单词网格空槽裸露、玩家可拖入物品；改为开界面前统一填充灰色玻璃板占位（填充顺序：占位 → 单词 → 导航键）
- **[缺陷修复批次]** 答题反馈永不本地化（5 组键名错位，被硬编码英文兜底掩盖）：代码请求 `word-learning-gui.correct`/`incorrect`/`combo-message`/`timeout`/`countdown-normal`，而 yml 里是 `correct-answer`/`wrong-answer`/`correct-answer-combo`/`time-up`/`countdown`；yml 键名统一改为代码请求名，并按占位符契约调整 `correct`（注入 `{score}`+`{combo}`）与 `combo-message`（改为无前导空格的片段，占位符 `{count}`/`{multiplier}`）
- **[缺陷修复批次]** 玩家屏幕出现红色 `Missing message: progress.money-reward`：`progress` 段只有 `view` 一个键，而 `RewardEngine` 请求 4 个 `money-*` 键；补齐 `money-reward`/`money-session-bonus`/`money-review-reward`/`money-review-penalty`
- **[缺陷修复批次]** `/le help` 漏掉 3 个命令（`leaderboard`/`challenge`/`duel`），其中 `help.duel` 的翻译早已写好却从未被打印；按 `SUBCOMMANDS` 顺序补齐，并新增 `help.leaderboard`/`help.challenge`
- **[缺陷修复批次]** 错题本练习模式输入 `cancel` 的反馈 `"&7Practice cancelled."` 是裸硬编码英文，中英玩家都只看得到英文；改为走 `wrong-book.practice-cancelled`
- **[缺陷修复批次]** 确认界面没有取消路径，玩家只能按 ESC 退出；新增 slot 22 barrier 取消按钮（`language.cancel-item`/`cancel-lore`），`MainMenuListener` 增加对应分支
- **[缺陷修复批次]** 确认界面语言读错：`open()` 硬编码读 `en` 配置、`openSecond()` 硬编码读 `zh`，导致英文玩家在第二阶段看到中文按钮；改为按 `plugin.getPlayerLanguage(player)` 读取，`applySwitch()` 改用 `MessageUtil.getPlayerMessage()`（切换后按新语言播报，前缀正确解析）
- **[缺陷修复批次]** 排行榜返回键是全项目唯一没统一到 slot 8 的 GUI（`BACK_SLOT = 49`，监听器还硬编码 `slot == 49`）；统一为 slot 8 并改用常量引用
- **[缺陷修复批次]** 学习日历任何语言都不播打开音效：`GUIUtils.isPluginGUI()` 白名单仍写 `学习日历`/`Learning Calendar`，而实际标题早已改为 `过往学习`/`Study History`；同时修正 zh 版对话历史标题漂移（`聊天记录` → `对话历史`）
- **[缺陷修复批次]** `ProfileGUI` 成就墙按钮整块使用硬编码中英三元表达式，玩家可见文本绕开 i18n；改用 `profile.stats-achievements-label`/`.stats-achievements-value`/`.achievements-hint`（走 `getPlayerMessage` 以正确替换 `{unlocked}`/`{total}`）
- **[缺陷修复批次]** `ProfileGUI` 的 `profile.settings-locked`/`settings-locked-lore` 带 fallback 参数，其中后者是硬编码中文 `"&7只能修改自己的设置"`，英文玩家会看到中文；去掉 fallback
- **[缺陷修复批次]** `AdminCommand` 整块裸英文字符串直接发给玩家（usage / 提示 / 广播前缀）；全部改走 i18n（`admin.usage-exportwordbank`、`admin.available-difficulties`、`admin.usage-importwordbank`、`admin.importwordbank-hint`、`admin.player-only`、`admin.proxy-unavailable`、`admin.usage-broadcast`、`admin.broadcast-hint`、`admin.broadcast-sent`、`admin.broadcast-prefix`），管理帮助补齐 `ignore-update` 与 `broadcast` 两行
- **[缺陷修复批次]** `LearnCommand` 的 Tab 补全补齐缺失的 `profile` 玩家名补全；`dialogue` 场景名与 `word` 难度比对补 `toLowerCase()`；`args.length == 1` 分支移到末尾，避免挡住 `>= 2` 的分支
- **[缺陷修复批次]** 全项目清理 `getString(key, "硬编码兜底")` 的 fallback 参数，共 177 处（从 180 处降到仅剩 3 处空串豁免）：`ChatAnswerListener`、`WordLearningManager`、`ProfileGUI`、`LeaderboardGUI`、`ChatHistoryGUI`、`WrongBookGUI`、`WrongBookListener`、`WordLearningListener`、`LearnCommand`、`SocialListener`、`DuelManager`、`DuelGUI`、`MainMenu`、`EnglishSquareGUI`、`ScenarioSelectGUI`、`WordBankSelectGUI`、`WordLearningGUI`、`DailyChallengeGUI`、`ChallengeCompleteGUI`、`AchievementWallGUI`、`AchievementManager`、`DailyChallengeManager`、`ProxyMessenger`、`ProgressGUI`。fallback 会掩盖键名错误，本批次 5 组键名错位即因此被隐藏数月；`LeaderboardGUI.addTabButton()` 与 `EnglishSquareGUI.addModeButton()` 的 `String fallback` 形参一并从签名移除
- **[缺陷修复批次]** 清除整句硬编码玩家可见文本（未走 messages 的裸字符串与中英三元表达式）：`LeaderboardGUI` 的「点击查看玩家主页」、`ChatHistoryGUI` 的 `"Unknown"`、`WordLearningListener` 的「答题中无法返回主菜单」、`AchievementWallGUI` 的标题/已解锁/未解锁/时间提示、`WordBankSelectGUI` 的标题/提示/三档难度名、`WordLearningGUI` 的 `Score:`、`EnglishSquareGUI` 的 `"Unknown"` 玩家名、`LearnCommand` 的 7 处错误消息与 `"复习"` 占位符值
- **[缺陷修复批次]** `messages/*.yml` 的 `config-version` 由 5 升至 6（`ConfigManager.MESSAGE_CONFIG_VERSION` 同步）：本批次新增 21 个消息键，若不升版本，已部署服务器不会覆盖旧的消息文件，新键取不到会返回 null 并引发 NPE
- **[缺陷修复批次]** `ChatHistoryGUI` 改用 `ItemMeta.getPersistentDataContainer()` 存放会话 ID 与返回页码，取代原先「解析物品 lore/displayName 文本反推内部数据」的做法（`ChatColor.stripColor(line).startsWith("SessionID: ")` 这类文本即协议的实现，译文一改就静默失效）；`ChatHistoryListener` 侧同步改读 PDC，删除约 60 行脆弱解析代码
- **[缺陷修复批次]** `ScenarioSelectListener` 整块绕开 i18n（同功能的 `ChatScenarioListener` 全程走 `dialogue.*` 键，只有这个类用中英三元硬编码）：AI 未配置提示、对话开启三条消息、AI 标签、`/lea` 刷新结果全部改走 `dialogue.*`；`formatScenarioName()` 的 6 个硬编码场景名 `switch` 整块删除，改查 `ai_scenarios.yml` 的 `display_name` / `display_name_zh`（顺带统一了 GUI 与聊天栏的场景名显示，原先 GUI 显示「机场值机」而聊天栏回报「airport」）
- **[缺陷修复批次]** `/le dialogue <场景>` 命令路径把**原始 key**（如 `airport`）塞进 `dialogue.started` 的 `{scenario}`，走命令入口的玩家看到英文标识符，与 GUI 入口不一致；改为同样查 `ai_scenarios.yml` 的展示名
- **[缺陷修复批次]** `LLEPapiExpansion` 的 `%lle_rank%` 未上榜文本用中英三元硬编码；改为走 `placeholder.rank-unranked`（在线玩家按自身语言解析，离线玩家按服务端默认语言）
- **[缺陷修复批次]** `AdminCommandHandler` 的导入/导出失败消息把硬编码英文注入 `{reason}` 占位符（共 4 处：找不到难度、IO 错误、找不到文件、未找到 words 列表）；改为内层 `wordbank.reason-*` 键 + `MessageUtil` 解析。校验失败的逐条错误行（`"  - " + error`、`"... and N more errors"`）也改走 `wordbank.validation-error-line` / `validation-more-errors`
- **[缺陷修复批次]** `UpdateChecker` 的更新可用横幅（7 行）与忽略提示为硬编码英文；改为走 `update.*` 段 7 个键，`ChatColor` import 随之移除
- 修复签到同一玩家并发重复领取：按玩家串行化签到流程；断签提示明确旧连续签到归零，重新签到从 1 开始
- LearningCalendarGUI 空 slot 未填充，玩家可将背包物品拖入 GUI；改为在 openInventory 前遍历所有 54 格，对 null slot 填入灰色玻璃板占位
- PlayerDataStore.getRankedPlayers() 在 ResultSet 迭代中调用 getPlayerStats() 触发嵌套 Statement，导致 SQLite "stmt pointer is closed"；改为两步法：先收集原始行，关闭 ResultSet 后再查 stats
- PlayerDataStore.getRankedPlayersForPeriod() 同样在 ResultSet 内调用 getPlayerName()；同步改为两步法修复嵌套 Statement 问题
- 主菜单"学习日历"按钮缺少中文 i18n 键（calendar/calendar-lore），改为从 zh.yml/en.yml 读取
- 错题本标题硬编码 "Page N" 页数，改为 i18n 键 `wrong-book.title-page` 支持中英双语（第 {page} 页 / Page {page}）
- 错题本顶部装饰行（9 格红色玻璃板）移除，只保留功能性物品
- 错题本"返回"按钮从底部中央（slot 49）移至右上角（slot 8），与其他 GUI 统一
- ChatHistoryGUI 列表视图"返回主菜单"按钮从 slot 49 移至 slot 8，与其他 GUI 统一
- ChatHistoryGUI 分页按钮 lore 显示当前页而非目标页，改为显示目标页码（page-1/page+1）
- ChatHistoryListener 分页导航使用 lore 提取页码后二次加减导致跳页错误，改为直接导航到目标页
- WrongBookListener.extractPageFromTitle() 和 ChatHistoryListener.extractPageFromLore() 新增中文页码格式（"第 N 页"）解析支持
- Calendar 中文星期标签从单字缩写（一/二/三）改为完整格式（周一/周二/.../周日）
- ChatHistoryGUI 对话详情视图"返回列表"按钮从 slot 49 移至 slot 8，新增 `BACK_SLOT = 8` 常量统一管理
- ChatHistoryListener 返回按钮槽位引用改为 `ChatHistoryGUI.BACK_SLOT`，避免魔法数字
- WordBank 加载 YAML 时未解析 reward 字段，导致单词自定义奖励永远为 0；改为 10 参数构造器，读取 reward 并传递
- Word.equals() 和 hashCode() 包含 reward 字段，确保带自定义奖励的单词正确参与相等性比较
- AdminCommandHandler.saveWordBankToYaml() 保存单词时写入 reward 字段（仅当非零时），防止导出/重载后丢失奖励
- ReviewScheduler 完成所有艾宾浩斯间隔后永久排除单词；改为将已掌握单词以 30 天周期重新加入低频复习池
- GuiNavigationManager 使用 `Map<UUID, Runnable>` 单槽存储导致多层 GUI 导航时 back 路径被覆盖；改为 `Map<UUID, Deque<Runnable>>` 栈结构，保证"从哪里进的就要从哪出"的导航正确性
- AIChatManager 上下文裁剪可能误删 System Prompt；重构为按 token 估算精确裁剪，System Prompt 永远固定在消息列表首位不可删除
- 错题本中文标题显示 "错题本/Wrong Book|第X页" 含英文后缀；修改 zh.yml `wrong-book.title` 和 `wrong-book.title-page`，中文模式下只显示中文
- 英语广场（English Square）不显示自己；改为按答题活跃度过滤（totalCorrect+totalIncorrect>0），包含自己
- 学习日历布局错误：列优先填充未做星期对齐导致日期错位；改为基于 LocalDate.getDayOfWeek() 的正确行列映射，每个日期总显示在对应星期列下
- 学习日历颜色方案倒置：亮色=学得多改为深色=学得少（0→GRAY, 1-5→GREEN, 6-15→LIME, 16+→YELLOW），颜色指示图例从底行移至右侧列
- 主菜单"学习日历"改为"过往学习"（zh.yml: 过往学习, en.yml: Study History），GUI 标题同步更新
- 过往学习按钮从主菜单 slot 9 移至个人主页左上角 slot 0（CLOCK 材质），主菜单不再显示该入口
- LearningCalendarGUI 移除 getCalendarMsg()/getDayLabels() 中的硬编码中英文 fallback 字符串，改为 msg.getString(key, "") 直接读取 i18n 配置
- **[Phase 0 安全基线]** M16 数据库并发止血：`DatabaseManager` 所有访问共享 Connection 的查询/更新方法统一在 `connectionLock` 上 `synchronized`（`initialize`/`executeUpdate`/排行榜/聊天历史/每日挑战/错题本/统计/签到/`shutdown` 等共 36 处加锁点），保证同一时刻只有一个线程使用这条连接，消除 SQLite 单连接并发 `SQLITE_BUSY` 与跨线程事务交叉读；对外暴露 `getConnectionLock()` 供尚未收编的外部调用方复用同一 monitor；无嵌套锁、无持锁回调，功能结果零变化
- **[Phase 1 止血流 D]** M4 三态冲突互吞：`DuelManager.acceptDuel()` 新增答题忙态检查，有 quiz（`hasActiveQuiz`）或错题练习（`isPracticingWord`）进行中的玩家不能接受对决，发起方答题中同样拒绝建房，双向各走 i18n 提示（`duel.busy-answering` / `duel.target-answering`）
- **[Phase 1 止血流 D]** M4：`ChatAnswerListener` 的 quiz/练习答案分支入口新增决斗中守卫，决斗中玩家误入分支时不消费答案并提示先完成对决或 `/le duel quit` 退出（`duel.finish-duel-first`），同时堵住异步聊天线程分支判定与主线程 `acceptDuel` 之间的竞态窗口
- **[Phase 1 止血流 D]** M4：i18n 新增 `duel.busy-answering` / `duel.target-answering` / `duel.finish-duel-first`（zh/en 双语同位新增）；`config-version` 未升版（`ConfigManager.MESSAGE_CONFIG_VERSION` 非本流白名单，须由编排者统一升版后已部署服才能收到新键）
- **[Phase 1 止血流 E]** M16 残余缺口收口（F0-01）：外部类裸用 `getConnection()` 的 SQL 执行点统一包上 `synchronized(getConnectionLock())`（PlayerDataStore 8 处、DailyChallengeManager 8 处、AchievementManager 3 处、AdminCommandHandler 2 处、ProgressManagerImpl 1 处、LLEPapiExpansion 1 处、LetsLearnEnglish 备份任务 2 处），仅加锁包装、零逻辑改动
- **[Phase 1 止血流 E]** F0-02 收口：`PlayerDataStore.savePlayerData` 整段事务（setAutoCommit(false)→batch→commit/rollback→恢复 autoCommit）收进 `synchronized(getConnectionLock())`，消除与他线程共享连接的事务污染
- **[Phase 1 止血流 E]** `PlayerDataStore` 新增今日计数器 API（内存懒清零、服务器时区自然日）：今日答题 `incrementTodayAnswerCount`/`getTodayAnswerCount`、今日 AI 有效轮次 `incrementTodayAiRound`/`getTodayAiRound`、今日已发金币 `addTodayCoinsEarned`/`getTodayCoinsEarned`（写入侧由 RewardEngine 每次 deposit 成功后显式调用，与 recordCoinsEarned 两行同记账；故意不在 recordCoinsEarned 内自动累加，避免与流 A 的显式调用双倍计数；挑战/签到发币暂未纳入 cap）
- **[Phase 1 止血流 E]** `config.yml` 新增 `money.daily-cap`（默认 500）、`ai-chat.daily-round-limit`（默认 30）、`ai-chat.max-tokens`（默认 128）、`ai-chat.count-min-rounds`（默认 3），均带中文注释
- **[Phase 1 止血流 E]** showAnswers 默认值改 `false`（隐私优先 D2 决策）：`ProfileSettings.showAnswers` 字段默认值、`player_profiles` 建表语句（`DatabaseManager` + `schema.sql`）的列默认值同步为 0；messages `config-version` 升至 7（流 A 新增 `progress.daily-limit-no-reward` / `progress.daily-cap-reached`，流 E 未新增键；需编排者同步 bump `ConfigManager.MESSAGE_CONFIG_VERSION` 6→7）
- **[Phase 1 止血]** H7 场景导入无权限写全局：`/le load_scenario` 新增权限节点 `letslearnenglish.scenario.import`（默认 op），无权限走 `scenario.load-no-permission` 拒绝；导入改为玩家个人场景（落盘 `plugins/LetsLearnEnglish/player_scenarios/<uuid>.yml`，重载只刷新导入者个人场景，不写全局 `ai_scenarios.yml`、不 reload 全局）；`/le save_scenario` 仅读不写，无需改动
- **[Phase 1 止血]** H3+C4 AI 对话刷计数：`endConversation` 仅当本轮"有效对话 ≥ N 条"（N 读 `ai-chat.count-min-rounds` 默认 3；有效 = 收到玩家非退出词消息且至少一次成功 API 回复）才计 `conversationCount`、触发成就与每日挑战；下线结束走同一标准，不足不计数
- **[Phase 1 止血]** H3+C4 AI 消费上限：发 API 请求前查 `getTodayAiRound`，超 `ai-chat.daily-round-limit`（默认 30）走 `dialogue.daily-limit-reached` 拒绝；每次 API 回复成功 `incrementTodayAiRound`；`max_tokens` 由硬编码 256 改为读 `ai-chat.max-tokens`（默认 128）
- **[Phase 1 止血]** H1+C2 发音泄题：发音按钮聊天反馈移除英文原词明文，只显示音标（`word.phonetic-feedback`）
- **[Phase 1 止血]** H1+C2 答案广播泄题：答题广播改为答对后广播"答对+分"（不含答案文本，答案文本永不出现在广播里）；玩家名广播前 `stripColor` + 截断 50 字符防颜色码注入与超长刷屏

### Added
- `config.yml` review 段新增 `mastered-review-interval-days` 配置项（默认 30 天），控制已掌握单词低频复习周期
- `config.yml` ai-chat 段新增 `max-context-tokens` 配置项（默认 2000），基于 token 估算值裁剪对话上下文
- AIChatManager 新增 `trimContextByTokens()` 方法：按估算 token 数裁剪，只移除最早 user/assistant 交替对，System Prompt 不可删除
- AIChatManager 新增 `estimateTokens()` 辅助方法：按 1 token ≈ 4 字符近似估算
- Leaderboard 排行榜持久化缓存系统：Leaderboard 新增 coinsCache/wordsCache/comboCache 三个缓存列表，每 5 分钟自动从数据库刷新
- DatabaseManager 新增 `getTopCoins()`、`getTopWordsLearned()`、`getTopCombo()` 三个排行榜查询方法，从 player_data 表读取数据
- 新增 LeaderboardGUI（54 格 GUI）：支持金币榜/单词榜/连击榜三个 Tab 切换，显示 Top 10 玩家头像和排名信息
- `/le leaderboard` 命令打开排行榜 GUI；LearnCommand 新增 `leaderboard` 子命令和 Tab 补全
- **AI 对话历史回顾**：新增 `ai_chat_history` 数据库表持久化存储所有 AI 对话消息（system/user/assistant）；AIChatManager 在 startConversation/chatAsync/AI 回复时通过 CompletableFuture.runAsync 异步写入 DB，不阻塞主线程
- ChatHistoryGUI（54 格 GUI）：分页展示历史对话会话列表（10 条/页），每个会话显示日期/场景/首条消息预览；点击进入完整对话详情视图查看所有消息
- DatabaseManager 新增 `insertChatHistory()`、`getChatSessions()`、`getSessionPreview()`、`getChatSession()`、`getChatHistoryCount()`、`cleanupOldChatHistory()` 方法；插件启动时异步清理 30 天前的旧记录
- ChatHistoryListener 处理会话列表和对话详情的 GUI 交互（分页导航、返回主菜单/返回列表）
- `/le history` 命令打开 AI 对话历史 GUI；i18n 新增 `chat-history.*` 段（中英双语）和 `help.history` 帮助条目
- i18n 新增 `leaderboard.title`、`leaderboard.tab-coins`、`leaderboard.tab-words`、`leaderboard.tab-combo`、`leaderboard.empty` 等消息键
- WordBankValidator 新增词库导入校验器：验证 YAML 结构、必填字段类型、列表子元素类型、最大 1000 词限制
- AdminCommandHandler 新增 `exportWordBank()` 方法：导出指定难度词库至 `exports/{difficulty}_export_{timestamp}.yml`，包含所有单词字段
- AdminCommandHandler 新增 `importWordBank()` 方法：从 YAML 文件导入词库，校验后跳过重复单词（不覆盖），保存至对应难度词库文件
- AdminCommand 新增 `/lea exportwordbank <difficulty>` 和 `/lea importwordbank <filename>` 子命令，含 Tab 补全
- 每日单词挑战系统：从词库随机选择 N 个真实单词作为每日挑战（默认 5 个，可在 `config.yml` 的 `daily-challenges.word-count` 配置）
- `daily_challenge_words` 数据库表：记录每位玩家每日分配的单词挑战及其完成状态
- DatabaseManager 新增 `getDailyChallengeWords()`、`assignDailyChallengeWords()`、`markDailyChallengeWordComplete()`、`isDailyChallengeWordsCompleted()`、`getDailyChallengeWordsProgress()` 方法
- DailyChallengeManager 新增 `WordManagerImpl` 依赖注入；新增 `ensureWordChallengesAssigned()`（玩家加入时自动分配单词挑战）、`onWordCorrect()`（答对时自动标记完成）、`getTodayWordProgress()`、`getTodayWordDetails()` 方法
- WordLearningManager.submitAnswer() 增加钩子：答对单词时自动调用 `DailyChallengeManager.onWordCorrect()` 检查并标记每日挑战单词
- ChallengeCompleteGUI：27 格庆祝界面，玩家完成所有每日单词挑战后弹出，显示玩家头像、烟花庆祝、金币奖励和领取按钮
- `/le challenge` 命令：以聊天文本形式展示今日单词挑战进度（已完成/总数及各单词状态）
- `config.yml` `daily-challenges` 段新增 `word-count`（默认 5）和 `word-challenge-reward`（默认 100.0）配置项
- i18n 新增 `daily-challenge.*` 消息键（中英双语）：header、word-done、word-pending、all-complete、complete-title 等
- **单词发音助手（音标 + TTS）**：WordLearningGUI 当前单词物品 lore 中显示音标符号（如 /wɜːd/）；新增发音按钮（NOTE_BLOCK 材质，slot 14），点击播放 NOTE_BLOCK_PLING 提示音并异步下载 TTS 音频缓存；新增 TtsManager 管理 VoiceRSS API 音频下载与缓存清理；音频下载为可选功能，需配置 API 密钥
- **每日签到系统**：`/le checkin` 命令进行每日签到，连续签到每日递增金币奖励（Day 1=10, Day 2=20, Day 3=30, Day 4=50, Day 5=70, Day 6=85, Day 7=100），第8天循环回第1天；断签自动重置为1天；基于服务器时区的自然日判断
- 新增 `checkin_data` 数据库表：记录玩家上次签到日期和连续天数；DatabaseManager 新增 `getCheckInData()`、`updateCheckIn()`、`resetCheckInStreak()` 方法
- 新增 CheckInData 数据类和 CheckInManager 管理器：处理签到逻辑、连击奖励计算（含 >7 天循环）、Vault 经济发放
- LearnCommand 新增 `checkin` 子命令和 Tab 补全；LetsLearnEnglish 新增 `getCheckInManager()` 访问器
- i18n 新增 `checkin.*` 消息键（中英双语）：success、already、streak-broken、streak-info
- `config.yml` 新增 `tts` 配置段（enabled / cache-days / voicerss-api-key），控制发音功能开关和音频缓存策略
- `config.yml` gui-layout.word-learning 新增 `pronunciation-button` 按钮槽位和材质配置
- i18n 新增 `word.pronunciation-button`、`word.pronunciation-button-lore`、`word.phonetic-label`、`word.pronunciation-unavailable` 消息键（中英双语）
- **PvP 单词对决系统**：实时 1v1 单词对决，两名玩家抢答同一单词，先得 5 分者获胜；DuelManager 管理对决生命周期（发起/接受/退出/弃权）；DuelRoom 线程安全状态机（synchronized 提交答案防并发）；DuelGUI 27 格界面展示双方分数（彩色羊毛进度条）、当前单词和弃权按钮；Bukkit Scoreboard API 侧边栏实时显示比分和 Grace 倒计时；30 秒断线保护（DuelListener 监听 PlayerJoinEvent/PlayerQuitEvent）；聊天答案优先路由到对决系统，答案不广播；`/le duel <玩家名>` 发起挑战，`/le duel accept` 接受，`/le duel quit` 退出，Tab 补全支持玩家名和子命令
- 新增 DuelRoom（core/duel/）、DuelManager（core/duel/）、DuelGUI（gui/）、DuelGUIListener（listener/）、DuelListener（listener/）共 5 个文件
- i18n 新增 `duel.*` 段 30 个消息键（中英双语）、`help.duel` 帮助条目
- GUIUtils.isPluginGUI() 新增 `Word Duel` 和 `单词对决` 标题识别
- ChatAnswerListener 新增对决答案拦截逻辑（优先于普通答题和错题练习）
- LearnCommand 新增 `duel` 子命令和 Tab 补全（accept/quit + 在线玩家名）
- **错题本 / Wrong Book**：答错 3 次的单词自动加入错题本；WrongBookGUI（54 格 GUI）分页展示错词，显示中文释义/错误次数/加入时间；左键移出错题本，Shift+点击进入独立练习模式（不计入艾宾浩斯复习）；练习正确降低错误计数，归零自动移除
- 新增 `difficult_words` 数据库表持久化存储错词；DatabaseManager 新增 `incrementWrongCount()`、`getWrongCount()`、`getDifficultWords()`、`removeDifficultWord()`、`decrementWrongCount()`、`clearWrongCount()` 方法
- PlayerDataStore 新增 difficultWordsCache/wrongCountCache 内存缓存及对应操作方法，异步写 DB
- WordLearningManager 新增 `trackWrongAnswer()`、`startPracticeWord()`、`submitPracticeAnswer()` 方法及 practiceWords 状态管理
- ChatAnswerListener 新增练习模式答案拦截（优先于普通答题）；练习完成后自动重新打开 WrongBookGUI
- `/le wrong` 命令打开错题本 GUI；MainMenu 新增错题本按钮（第 14 槽位/书架材质）
- i18n 新增 `wrong-book.*` 段 18 个消息键（中英双语）和 `help.wrong` 帮助条目
- 主菜单新增 `main-menu.wrong-book` / `main-menu.wrong-book-lore` i18n 键（中英双语）
- **学习统计 HTTP 仪表盘**：基于 Java 内置 HttpServer 的 Web 管理面板，展示学习统计数据与 ECharts 图表；双重安全防护（IP 白名单 + CIDR 支持 + HTTP Basic Auth）；`GET /` 返回深色主题 HTML 仪表盘（含 7 日学习趋势折线图、每日活跃玩家柱状图、摘要卡片、60 秒自动刷新）；`GET /api/stats` 返回 JSON 格式统计数据
- **自定义 AI 场景分享**：`/le save_scenario <名称>` 将当前 AI 对话的 System Prompt 编码为 Base64 分享码；`/le load_scenario <编码>` 解码并导入场景到 `ai_scenarios.yml`，自动热重载生效；ScenarioCodec 编解码器（Base64 含 null 分隔符）；场景名称校验（最长 32 字符，仅允许中英文/数字/下划线）；Prompt 长度限制（`config.yml` `scenario.max-prompt-length`，默认 2000）；AIChatManager 跟踪每玩家当前 System Prompt 用于导出
- 新增 `StatisticsHttpServer` 类（core/manager/）：服务生命周期管理（start/stop）、IP CIDR 匹配、Basic Auth 认证、内联 HTML 仪表盘
- `config.yml` 新增 `statistics-server` 配置段（enabled / port / ip-whitelist / auth），默认 disabled
- DatabaseManager 新增 `getTotalWordCount()`、`getTodayActivePlayers()`、`getTotalSessions()`、`getTotalCoinsEarned()`、`getTotalCoinsSpent()`、`getLast7DaysStats()` 统计查询方法和 `DailyStats` 记录类
- `LetsLearnEnglish.onEnable()` 读取 `statistics-server.enabled` 配置，按需启动 HTTP 服务器；`onDisable()` 优雅停止
- **[缺陷修复批次]** i18n 新增 54 个叶子键 + 5 组键改名（zh/en 各 401 个可寻址消息键，键集、顺序、占位符多重集逐一对应）：
  - 金币奖励 4 键：`progress.money-reward` / `money-session-bonus` / `money-review-reward` / `money-review-penalty`
  - 新增 `error:` 段 7 键：`scenario-unavailable` / `player-not-found` / `duel-unavailable` / `checkin-unavailable` / `usage-save-scenario` / `usage-load-scenario` / `scenario-save-failed`
  - 新增 `achievement-wall:` 段 4 键：`title` / `time-opens` / `time-expired` / `time-remaining`
  - 新增 `word-bank:` 段 5 键：`title` / `click-hint` / `difficulty-beginner` / `difficulty-intermediate` / `difficulty-advanced`
  - `profile.settings-locked` / `settings-locked-lore`；`help.leaderboard` / `challenge`；`admin-help.ignore-update` / `broadcast`
  - `admin:` 段 10 键：`usage-exportwordbank` / `available-difficulties` / `usage-importwordbank` / `importwordbank-hint` / `player-only` / `proxy-unavailable` / `usage-broadcast` / `broadcast-hint` / `broadcast-sent` / `broadcast-prefix`
  - `language.cancel-item` / `cancel-lore`；`wrong-book.practice-cancelled`；`word.mode-review`；`word-learning-gui.cannot-return` / `score`；`leaderboard.click-hint`；`chat-history.unknown-date`
  - 5 组键改名（旧名删除，新名 = 代码实际请求名）：`word-learning-gui.time-up`→`timeout`、`countdown`→`countdown-normal`、`correct-answer`→`correct`、`correct-answer-combo`→`combo-message`、`wrong-answer`→`incorrect`
- **[缺陷修复批次]** `GUIUtils` 新增 `extractPageFromTitle(String)`：正则提取标题中第一个整数，覆盖中英双语页码格式，`Pattern` 提为 `static final` 常量避免重复编译
- **[缺陷修复批次]** `ConfirmationGUI` 新增 `CONFIRM_SLOT`/`CANCEL_SLOT` 常量；`ChatHistoryGUI` 新增 `SESSION_SLOTS`/`PAGE_INDICATOR_SLOT`/`RETURN_PAGE_SLOT`；`WrongBookGUI` 新增 `WORD_SLOTS`；`LeaderboardGUI` 的 `BACK_SLOT`/`ENTRY_SLOTS`/`TAB_*_SLOT` 提为 `public static final`
- **[缺陷修复批次]** `DuelGUIListener` 新增 `QUIT_SLOT` 常量与 `isDuelGUI()` 判定方法

## [1.0.6] - 2026-05-30

### Added
- **AI 情景对话系统**：替换原有 NPC 对话模块，集成 DeepSeek API 实现基于聊天框的智能英语情景对话；支持 6 个场景（机场/餐厅/购物/酒店/医院/自由对话）；AIChatManager 实现异步 HTTP 请求、每玩家频率限制（3 秒/条）、对话上下文管理（保留最近 6 轮）、输入安全过滤（控制字符清理 + 200 字符长度限制）；API 响应实时显示在聊天栏；API 不可用时优雅降级显示错误提示
- **场景选择 GUI**：新增 ScenarioSelectGUI（27 格 GUI 展示 6 个场景，含返回主菜单按钮）
- **AI 配置独立安全存储**：API 密钥存储于插件数据文件夹的 `ai_config.yml` 文件，不纳入版本控制；主配置 `config.yml` 仅保留 API 地址、模型、超时等通用参数

### Changed
- **彻底移除 NPC 对话功能模块**：删除 NPCInteractionListener、DialogueGUI、DialogueManagerImpl、NPC、DialogueNode、DialogueScene、GrammarChecker 类及 dialogues/*.yml 配置文件；移除 Citizens 插件软依赖
- **聊天框情景对话监听器**：新增 ChatScenarioListener，进入对话模式后玩家聊天消息自动转发给 AI，正常聊天功能不受影响
- 主菜单对话按钮更名为"AI 情景对话 / AI Scenario Chat"
- `/le dialogue` 无参数时打开场景选择 GUI，带参数直接开始指定场景；输入"再见"/"bye"退出对话
- `config.yml` 新增 `ai-chat` 配置段（api-url/model/timeout/rate-limit 等 6 个配置项），移除 `dialogue-system` 段
- ConfigManager 移除 `loadDialogueScene()` 方法
- LetsLearnEnglishAPI 新增 `getAIChatManager()`，移除 `getDialogueManager()`
- **许可证更换为 GPLv3**：从 MIT License 更换为 GNU General Public License v3.0

### Fixed
- **修复 UpdateChecker 网络超时告警**：连接/读取超时从 5 秒增加到 8 秒，新增最多 2 次重试机制（指数退避），403 限流也自动重试，超时失败日志从 WARNING 降级为 INFO 避免刷屏
- **修复 AIChatManager 线程池关闭后提交任务异常**：`executor.submit()` 外层包裹 try-catch `RejectedExecutionException`，插件关闭时优雅降级提示而非崩溃
- **修复 AIChatManager HTTP 错误流空指针风险**：`conn.getErrorStream()` 返回 null 时抛出明确 IOException 而非 NPE
- **修复所有 GUI 类 ItemMeta/SkullMeta 空指针风险**：EnglishSquareGUI、ProfileGUI、AchievementWallGUI、WordBankSelectGUI、ScenarioSelectGUI、MainMenu 共 9 处 `getItemMeta()` 调用添加 null 检查，防止极端情况下 NPE
- **修复 LearnCommand 空指针风险**：`handleDialogueCommand()` 添加 `getChatScenarioListener()` 空指针检查
- **Velocity / BungeeCord 代理兼容**：新增 `ProxyMessenger` 类通过 BungeeCord 插件消息通道实现跨服通信（Velocity 向下兼容此协议）；支持成就解锁全服广播、`/lea broadcast <消息>` 跨服公告；配置新增 `proxy` 段控制跨服功能开关
- **修复 DatabaseManager 竞态条件**：`getConnection()` 在两个线程同时发现连接为空时可能出现双重初始化；已添加同步双重检查锁定（`connectionLock`），防止连接泄漏
- **修复 WordBank YAML 类型安全**：`loadWords()` 移除 `@SuppressWarnings("unchecked")` 和原始 Map 强制转换；添加 Map 条目键（String）和难度级别（Number）的 instanceof 检查，防止 ClassCastException
- **修复 QuizState 线程安全**：`consecutiveCorrect`、`maxComboReached` 和 `isAnswering` 字段改为 `volatile`；`answerTaskId` 从 `int` 改为 `AtomicInteger`，防范未来异步调用时的线程可见性问题
- **AIChatManager HTTP 客户端现代化**：用 `java.net.http.HttpClient` 替换旧式 `HttpURLConnection`（无需新增依赖）；移除手动 `ExecutorService`，改用 `CompletableFuture` 进行异步处理
- **AIChatManager JSON 解析空值安全**：`sendHttpRequest()` 添加对 choices 数组、message 对象和 content 字段的 null/空值检查，防止 API 返回异常响应时崩溃

### Refactored
- **重构 GUIMenuListener（523行→5个专用监听器）**：拆分为 MainMenuListener、WordLearningListener、ScenarioSelectListener、SocialListener、MiscGUIListener；提取 `isPluginGUI()` 到新的 `GUIUtils` 工具类；提取 `profileBackActions` 返回导航逻辑到新的 `GuiNavigationManager` 共享服务；提高可维护性和关注点分离
- **排行榜功能激活**：Leaderboard 类从死代码升级为完整排行榜系统；新增 RankingMode 枚举（周榜/月榜/总榜/广场）；新增 LeaderboardEntry 记录类；PlayerDataStore 新增 getRankedPlayers() 和 getRankedPlayersForPeriod() 跨玩家聚合查询；启动时异步从数据库加载排行榜数据

### Added
- **PlaceholderAPI 集成**：新增 `LLEPapiExpansion` 支持 %lle_score%/%lle_rank%/%lle_words%/%lle_accuracy%/%lle_sessions%/%lle_streak%/%lle_longest_streak%/%lle_achievements%/%lle_correct%/%lle_incorrect% 共 10 个占位符；%lle_rank% 支持中英文切换（"未上榜"/"Unranked"）；pom.xml 新增 PlaceholderAPI provided 依赖
- **每日挑战任务系统**：新增 `DailyChallengeManager` 管理每日随机 3 个挑战（答对10个单词/完成3次会话/达到5连击/AI对话5轮）；新增 `lle_daily_challenges` 数据库表；玩家进服自动分配当日挑战；完成挑战点击领取金币奖励；新增 `DailyChallengeGUI` 27格展示界面；主菜单新增每日挑战按钮
- **英语广场排行榜模式**：英语广场新增 4 个模式切换按钮（周榜/月榜/总榜/广场），支持按时间段和金币排名而非仅按活跃玩家列表排序；排行榜模式下显示排名、金币和答对/答错数；gui-layout 配置化模式按钮材质和位置
- **GUI 界面物品完全配置化**：`config.yml` 新增 `gui-layout` 段，可自定义所有 GUI 中每个按钮的材质和位置（main-menu/word-learning/dialogue-practice/english-square/profile）；ConfigManager 新增 `getGuiSlot()`/`getGuiMaterial()`/`getGuiBorderMaterial()` 方法
- **语言文件 100% 覆盖**：所有 GUI 界面文字（英语广场、个人主页、隐私设置、成就等）全部改为从 zh.yml/en.yml 可配置读取，消除 30+ 处硬编码三元表达式
- **英语广场系统**：`/le square` 命令打开分页玩家列表，展示所有答过题的玩家；点击玩家头像查看其个人主页；PlayerDataStore 新增 `getActivePlayerIds()`/`getPlayerName()` 查询活跃玩家
- **个人主页系统**：`/le profile [玩家名]` 查看自己或他人的个人主页，集成年龄（只读，其他插件可通过 API 设置）、学习统计（已学单词/正确率/会话次数/金币）、成就展示（已解锁+未解锁预览）和隐私设置
- **成就系统**：14 个成就定义（初出茅庐/小有所成/单词达人/学富五车/完美无缺/小试牛刀/势如破竹/拼写达人/连续三天/一周全勤/坚持不懈/学习标兵/小有积蓄/金库满载），每个成就带详细的中英文描述注释；AchievementManager 管理解锁逻辑、缓存和广播
- **隐私设置系统**：`player_profiles` 数据库表存储 `show_answers`/`show_results`/`show_in_square` 三个开关，支持个人主页内一键切换；ChatAnswerListener 根据 `show_answers` 过滤答题广播
- **隐身彩蛋**：若玩家设置"不可在英语广场中看到"，点击其头像会打开一个箱子页面，中间放着喷溅药水提示"对方正在乱扔隐身喷溅药水..."
- **年龄系统**：`player_profiles` 表 `age` 字段，未设置时默认 0 岁，点击提示"不可以在这里设置年龄哦~"；LetsLearnEnglishAPI 新增 `setPlayerAge()`/`getPlayerAge()` 供其他插件调用
- **成就配置独立文件**：`achievements-config.yml` 支持每个成就独立启用/禁用开关和时间限制窗口（time-limit: start/end），未启用的成就既不能获得也不会显示
- **成就墙 GUI**：`AchievementWallGUI` 54 格界面网格展示全部 14 个成就，已解锁显示绿色玻璃板+描述，未解锁灰色玻璃板+描述，时间限制成就显示倒计时/过期状态
- 主菜单新增"英语广场"按钮（玩家头颅图标），"进度与统计"按钮更名为"个人主页"
- 语言文件新增 `square.*`/`profile.*`/`help.profile`/`help.square` 中英文翻译键
- LetsLearnEnglishAPI 新增 `getAchievementManager()`/`hasAchievement()`/`getUnlockedAchievements()`/`getPlayerDataStore()` 公共 API
- **Vault 货币奖励系统**：答题获得金币奖励，支持基础金额、Combo 倍率叠加、会话完成奖励和最高连击奖励
- **复习模式独立金币配置**：复习答对使用独立倍率（`review-multiplier`），避免与学习模式金币混淆
- **复习答错扣钱开关**：`review-penalty` 配置项，开启后复习答错扣除配置金额
- `pom.xml` 新增 Vault API 依赖（JitPack 仓库）
- `config.yml` 新增 8 个经济奖励配置项（`base-amount`/`combo-bonus`/`session-complete-bonus`/`max-combo-bonus`/`review-multiplier`/`review-penalty`/`review-penalty-amount`）
- `LetsLearnEnglish` 新增 `Economy` 注册器，`setupEconomy()` 在 onEnable 自动检测 Vault
- `RewardEngine` 新增 `giveMoneyForCorrectAnswer()`/`giveSessionCompleteMoneyReward()`/`giveMoneyForReviewCorrect()`/`giveMoneyForReviewIncorrect()` 四种奖励方法
- 语言文件新增 `progress.money-reward`/`progress.money-session-bonus`/`progress.money-review-reward`/`progress.money-review-penalty` 消息键
- **单词学习 GUI 返回主菜单按钮**：在单词学习界面 slot 18 添加"回到主菜单"按钮，点击后结束答题并返回主菜单
- **动态词库系统**：`words/` 目录下所有 `.yml` 文件自动发现并加载，通过 `/le word <文件名>` 直接选择词库开始答题；`/lea reload` 支持热刷新词库
- **单词单独自定义奖励**：每个单词可在 YAML 中设置 `reward` 字段（如 `reward: 30`），答对该词时直接获得指定金额（不受 combo 倍率影响），未设置则走全局 config.yml 默认金额
- **词库选择 GUI**：`/le word` 无参数时打开 27 格 `WordBankSelectGUI` 界面，展示所有可用词库（初级/中级/高级等），点击即可选择词库开始答题
- **答题中阻止返回主菜单**：单词学习 GUI 中答题进行时点击返回按钮会提示无法返回，必须先完成或停止当前答题会话

### Fixed
- **修复英语广场分页失效**：`extractPageFromTitle()` 原本硬编码返回 1，现通过标题编码 "| Page X/Y" 正确提取页码
- **修复 Profile 标题检测过于宽泛**：`isPluginGUI` 中 `title.contains("Profile")` 改为 `title.contains("'s Profile")` 和 `title.contains("的主页")`，避免与其他插件标题冲突
- PlayerDataStore 缺失 `java.util.ArrayList`/`java.util.List` 导入
- **修复 `/le review` 命令无法启动答题**：`handleReviewCommand()` 仅显示待复习数量而未创建答题会话，现已改为调用 `startReviewQuiz()` 完整启动复习流程
- **修复复习模式金币奖励不一致**：学习与复习共用同一套 combo 倍率导致复习奖励波动，现通过 `review-multiplier: 0.5` 独立控制
- **修复切换中文后主菜单标题仍显示英文**：zh.yml 中 `gui:` 段出现两次导致 `gui.main-menu-title` 被末尾的 `gui:` 覆盖丢失，现已合并为单个 `gui:` 段
- **修复 GUI 稳定性问题（物品可移动/界面瘫痪）**：`onInventoryClose` 从即时启动答题倒计时改为 2 tick 延迟检查——如果 2 tick 内打开了其他插件 GUI（如主菜单），则跳过启动，解决在 WordLearningGUI 未关闭时输入命令打开主菜单导致状态错乱
- **修复 `startQuiz()`/`startReviewQuiz()` 覆盖旧答题状态时未取消残留倒计时**：新增 `cancelAnswerTimer()` + `quizStates.remove()` 前置清理，防止旧计时器在后台继续触发超时
- **修复成就管理器未读取配置**：`AchievementManager.tryUnlock()` 未检查 `achievements-config.yml` 的启用/时间窗口设置，现已添加 `getAchievementConfig()` + `isAchievementEligible()` 配置感知检查
- **修复每日连续天数成就和金币成就未触发**：`checkSessionAchievements()` 未调用 `checkDailyStreak()` 和金币阈值检查，现已接入会话完成流程
- **修复答题中可返回主菜单**：`handleWordLearningGUI` 返回按钮（slot 8）在答题进行中也直接返回主菜单，现已添加 `hasActiveQuiz` 检查阻止返回

### Changed
- 进度与统计页面重构为个人主页（`ProfileGUI`），集成成就展示与隐私设置
- `PlayerJoinListener` 进服时加载成就数据和档案设置，下线时卸载
- `HotReloadManager.reloadAll()` 新增清空成就缓存和档案缓存
- `onDisable` 新增保存档案设置和清空成就缓存
- 自动保存任务新增 `saveProfileSettings()` 调用
- `WordLearningManager` 新增 `startReviewQuiz()` 方法，QuizState 新增 `isReviewMode` 标记
- `WordManagerImpl` 新增 `startReviewSession()` 方法，`endSession()` 自动记录复习进度
- `submitAnswer()`/`handleTimeout()` 区分复习与学习模式，分别调用不同的奖励/惩罚方法
- 语言文件 config-version 升至 6，新增 `word-learning-gui.back-button`/`word-learning-gui.back-lore` 语言键
- `WordManagerImpl.loadAllWordBanks()` 从硬编码数组改为动态扫描 `words/` 目录所有 `.yml` 文件
- `Word` 类新增 `reward` 字段和 `getReward()` 方法
- `WordBank.loadWords()` 解析单词的 `reward` 字段（可选，默认 0）
- `RewardEngine.giveMoneyForCorrectAnswer()` 和 `giveMoneyForReviewCorrect()` 新增 `Word` 参数：单词有自定义 reward 时直接使用，否则走全局配置
- **答题消息顺序优化**：打开 GUI 时仅显示"准备开始学习会话"+ 加油口令"开始！"，点击"开始答题"后才显示"请在聊天中输入英文单词"，三阶段递进提示更清晰（新增 `word.session-ready` 语言键）
- **DialogueGUI 物品栏位和材质配置化**：回到主菜单按钮/NPC 头像/练习信息 三个按钮的 slot 和 material 改为从 `config.yml` 的 `gui-layout.dialogue-practice` 段动态读取，不再硬编码
- **ProfileGUI 物品栏位和材质配置化**：个人主页和隐私设置页面中所有物品的 slot 和 material 改为从 `config.yml` 的 `gui-layout.profile` 段动态读取，包括玩家头像、年龄、学习统计、成就展示、返回按钮、设置按钮、隐私开关 toggle 状态材质以及边框材质，不再硬编码
- **英语广场玩家头像下移一行**：`HEADS_START` 从 9 改为 10，GUIMenuListener 中玩家点击范围从 `slot 9-44` 调整为 `slot 10-45`
- **ProfileGUI 重构为 27 格小箱子布局**：从 54 格大箱子改为 27 格，移除旧的成就展示区域，新增成就墙按钮（slot 18）和隐私设置一键切换；修复 `{player}` 占位符在看自己主页时替换为玩家名
- **GUIMenuListener 增强**：新增成就墙界面标题检测和 `handleAchievementWall()` 点击处理；`handleProfileGUI` 支持成就摘要点击（slot 15）、成就墙按钮（slot 18）和设置按钮（slot 26）；`handlePrivacySettings` 支持隐私开关点击切换（slot 11/13/15）
- **config.yml profile 布局更新**：所有物品 slot 改为 27 格布局对应位置（age: 22→12, words: 20→10, accuracy: 21→11, sessions: 23→13, coins: 24→14, achievements: 25→15, settings-button: 51→26）
- **`/le word` 改为词库选择流程**：不带参数时打开 `WordBankSelectGUI` 让玩家选择词库，带参数时直接启动指定词库答题
- **主菜单单词按钮改为直接打开词库选择**：`handleMainMenu` slot 11 从执行 `le word` 命令改为调用 `WordBankSelectGUI`，不再经过命令层
- **ChatAnswerListener 接入连击成就**：每次答对后调用 `AchievementManager.checkStreakAchievement()` 检查组内连击成就
- **WordLearningManager.showSessionComplete 接入会话成就**：会话完成时调用 `AchievementManager.checkSessionAchievements()` 触发会话级别成就检查
- **MainMenu / WordLearningGUI 物品栏位和材质配置化**：所有按钮的 slot 和 material 改为从 `config.yml` 的 `gui-layout` 段动态读取，不再硬编码；MainMenu 新增 GRAY_STAINED_GLASS_PANE 边框填充

### Performance
- **数据库自动保存异步化**：`auto-save-interval` 定时保存从主线程 `runTaskTimer` 改为异步 `runTaskTimerAsynchronously`，通过独立连接写入避免阻塞主线程 TPS
- **复习词查询 O(n*m)→O(m)**：`WordManagerImpl` 新增 `globalWordIndex` 全局词索引，`startReviewSession()` 消除跨词库嵌套循环
- **聊天广播缓存**：`ChatAnswerListener` 缓存在线玩家列表（3 秒刷新），避免每答一题调用 `getOnlinePlayers()` 分配新集合
- **ConfigManager 消除消息文件双重加载**：`loadMessageConfig()` 改为条件加载，仅在文件被覆盖时才重新读取
- **NPCInteractionListener 预编译正则**：`replaceAll("&[0-9a-fk-or]")` → `COLOR_CODE.matcher().replaceAll()`，避免每次交互重新编译正则
- **SoundManager 空槽过滤**：`onInventoryClick` 增加 `getCurrentItem() == null` 提前返回
- **WordBank 集合预分配容量**：同反义词列表 `new ArrayList<>(4)`，消除扩容开销

## [1.0.5] - 2026-05-19

### Added
- **版本更新检测**：启动时自动检查 GitHub Releases 获取最新版本，OP 玩家加入时通知更新
- **单词学习答题系统**：GUI 展示单词 → 关闭 GUI → 聊天栏倒计时答题 → 自动进入下一题
- **回合制 Combo 连胜系统**：连续答对产生连击倍率（1-2连击1.0x → 10+连击3.0x），答错/超时重置
- **停止当前回合**：GUI 第 16 格屏障方块按钮，点击立即结束并结算分数
- **数据备份**：自动定时备份数据库（默认每 1440 分钟），保留最多 7 份
- **每日学习提醒**：可配置多个提醒时间点（默认 9/14/20 点），新增玩家进服时发送学习鼓励消息（`on-join` 配置项）
- **消息前缀可自定义**：在 `config.yml` 中 `prefix.zh` / `prefix.en` 分别配置中英文消息前缀，热重载生效
- **GUI 音效系统**：菜单打开/点击/答题正确/错误/完成均有音效反馈
- **数据库 UPSERT 优化**：INSERT OR REPLACE 替代 DELETE+INSERT，写入量减少 50%
- **配置文件大幅扩展**：新增 backup/daily-reminder/gui.sounds/difficulty-settings 等配置项
- 答题超时时间可配置（`word-system.answer-timeout`，范围 10-120 秒）

### Changed
- **全面 i18n 国际化**：所有聊天消息、GUI 标题、倒计时提示、答题反馈、连击消息均支持中英双语
- **GUI 与聊天栏互斥重新设计**：关闭 GUI → 启动计时器 → 聊天答题流程，适配 Minecraft 客户端限制
- **主菜单全部项目支持双语切换**（单词学习/对话练习/进度统计随语言变化）
- 主菜单打开/按钮点击新增音效
- `maven-clean-plugin` 配置保护 `*.jar`，以后 `mvn clean` 不再删除旧版本

### Fixed
- **修复计时器无限循环**：答对后系统继续倒计时不停止，三重防护（volatile cancelled + AtomicBoolean 重入锁 + 主线程调度）
- **修复倒计时重叠**：计时器到期与聊天输入同时触发时产生双计时器雪崩
- **修复中文界面仍有英文混显**：`getPlayerLanguage()` 硬编码默认 `"en"` 不读 `config.yml` 的 `general.language` 配置，导致新玩家语言永远是英语；`config-version` 版本号升级至 4 强制刷新语言文件使新翻译生效
- **修复语言切换消息前缀未替换**：`ConfirmationGUI.applySwitch()` 绕过 `MessageUtil` 直接读语言文件，导致 `{prefix}` 显示为原始文本
- **修复 GUI 物品防盗**：所有插件 GUI 阻止玩家拿走展示物品
- **修复 `loadPlayerData()` SQL 参数顺序 bug 导致数据从未加载**
- 修复切换语言后消息提示方向错误（切中文提示"已切换为英语"等）
- 修复切换语言后主菜单项目文字不变的问题
- 修复 MessageUtil 感知玩家语言的多处遗漏

### Refactored
- **答题流程完整推演与冗余清除**：12 处冗余代码清理（死构造函数/重复双 cancel/死语言键 10 个等）
- `buildResult()` 公有方法：消除 submitAnswer 与 handleTimeout 间 15 行重复统计代码
- `GUIMenuListener` 提取 `startAnswerForCurrentWord()`：消除 onInventoryClose / slot22 两处重复逻辑
- 语言文件精简化：删除 10 个死键，补充 8 个新汉化键
- `answer-prompt` 超时时间从硬编码改为配置注入 `{seconds}` 占位符
- 倒计时/答题反馈/超时提示已全部移除硬编码字符串，改用语言文件键
- `getPlayerLanguage()` 重构为读取 `config.yml` 的 `general.language` 配置项
- `MessageUtil` 新增 `resolvePrefix()` 方法，优先读取 `config.yml` 的 `prefix.{lang}` 配置
- `ConfirmationGUI.applySwitch()` 改为通过 `MessageUtil.getPlayerMessage()` 发送切换消息
- `PlayerJoinListener` 新增 `sendJoinReminder()` 方法，支持按玩家语言发送进服提醒

### Tests
- 新增 20 个单元测试（LearningSessionTest/PlayerProgressTest/WordTest），全部通过

## [1.0.4] - 2026-05-17

### Added
- 新增中英双语 README（顶部带语言切换链接）
- 新增 README 顶部开发警告提示
- 新增 `CHANGELOG.md` 版本更新日志

### Fixed
- 修复语言切换确认界面两次都显示同一种语言的问题：第一次英文"Really?"，第二次中文"真的吗？"

### Changed
- 构建时自动清理 maven-status 缓存，避免重复编译冲突

## [1.0.3] - 2026-05-14

### Added
- 主菜单左下角新增中英文切换按钮
- 切换语言需要两次确认
- 新增 `en.yml` 和 `zh.yml` 双语消息配置文件

### Changed
- 构建输出目录改为上级 `target/`，保留历史版本 JAR

## [1.0.1] - 2026-05-14

### Fixed
- 修复 GUI 菜单中颜色代码（`&7`、`&6`、`&l` 等）显示为原始文本的问题
- 修复 `/le menu` 等命令只发送文字消息没有打开 GUI 的问题
- 修复多处编译警告和空指针隐患

## [1.0.0] - 2026-05-14

### Added
- 初始版本发布
- 单词学习系统（基于艾宾浩斯遗忘曲线复习）
- 情景对话练习（餐厅、机场、购物等场景）
- 学习进度追踪与成就系统
- GUI 图形化菜单
- SQLite 数据库支持
- 管理员命令系统
