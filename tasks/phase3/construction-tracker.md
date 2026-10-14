# Phase 3 施工跟踪

- 基线： commit fb9604b（规划期即修 7 项实伤 + 八域规划已提交）
- 总纲： tasks/phase3/plan-master.md（4 波施工顺序）；各域细节以 plan-*.md 为准
- 纪律： 每波内多流并行（文件白名单严格互斥）；编排者统一 fan-in（i18n/config-version/CHANGELOG）；每波三维 tester + `mvn clean verify` + commit

## Wave 1（9 流并行，文件所有权严格互斥）

| 流 | 域 | 白名单文件 | 任务来源 |
|----|----|-----------|---------|
| S-A 数据层 | 1+3+7 共用 | DatabaseManager、PlayerDataStore、schema.sql | plan-onboarding §3.2 / plan-achievements §1.2+§3.2(H8/H11/H12) / plan-audio-feedback §3.1 |
| S-B 音效底座 | 7+3 | SoundManager、VersionAdapter、core/feedback/CountdownTier(新) | plan-audio-feedback §3.2/§3.3 / plan-achievements H16 |
| S-C 配置门 | 8 | ConfigManager、config.yml | plan-menu-config 1.(b)/2.4（仅 MC-2 部分+域7 config 段） |
| S-D 成就定义展示 | 3 | Achievement.java、AchievementWallGUI、LLEPapiExpansion、achievements-config.yml、新测试 | plan-achievements US-ACH-1/4 |
| S-E 成就管理器广播 | 3+8 | AchievementManager、ProxyMessenger | plan-achievements US-ACH-3/2.4 广播 / MC-2 成就门 |
| S-F 会话核心 | 1+7+3+8 | WordLearningManager、WordManagerImpl、ChatAnswerListener | plan-onboarding US-ONB-3 / plan-audio-feedback 3A-1/3A-2 / plan-achievements H7 / MC-2 word 门 |
| S-G 引导核心菜单入口 | 1+8 | OnboardingManager(新)、LetsLearnEnglish、PlayerJoinListener、MiscGUIListener、LearnCommand、ProfileGUI | plan-onboarding US-ONB-2/3 / MC-2 命令与 GUI 门 |
| S-H AI与挑战门 | 8 | AIChatManager、ChatScenarioListener、DailyChallengeManager、RewardEngine、ReviewScheduler、EnglishSquareGUI、SocialListener | plan-menu-config MC-2（ai/daily/leaderboard/rewards 门 + F1-19 语义） |
| S-I 对决与音效盲点 | 7+8+3 | DuelManager、ScenarioSelectListener、WrongBookListener | plan-audio-feedback 3A-3/3A-5 / MC-2 duel 门 / plan-achievements H9 |

Wave 1 冻结接口：见各流提示词内清单（数据层/音效/配置门/成就/引导管理器）。

## Wave 2（待 Wave 1 提交后）
- 域 4 广场社交（SocialManager 全新 + 2 新表 + 广场动态页）
- MC-1 主菜单 9 按钮 + DuelChallengeGUI
- MC-3 modes 接线（WordModes + AnswerChecker 参数化 + QuizState 快照）
- MC-5 挑战经济参数接线（challenges-per-day/rewards.* + F1-12 配套）
- 域 6 家长报告

## Wave 3（待 Wave 2 提交后）
- 域 2 AI 反馈闭环（ChatSessionState 重构 + 新表 ai_chat_feedback）
- 域 5 批次 A 赛季+跨服榜
- MC-4 个人场景管理（PersonalScenarioStore 抽取 + 2 新 GUI）
- MC-6 语义终案收尾（裸 getString 二期 + ExportResult 协议化；F1-19 已在 Wave 1 提前落）

## Wave 4（收尾）
- 域 5 批次 B 班级
- MC-7 死键/死类终局大扫除（必须在全部接线落盘后）
- 3B 资源包（默认不做，待用户拍板）

## 收尾（用户点名）
- 验收 + 锐评 agent（数路并行）
- 全部相关文档更新（README / CHANGELOG / tasks/ / dev-findings / 规划文档状态回写）


## Wave 1 冻结接口一览（Wave 2+ 消费）

- `OnboardingManager`：interceptQuizStart / onTutorialSessionComplete / onProfileOpened / handleJoin / unloadPlayer / giveGuideBook / handleGuideCommand / getStep / bookKey
- `AchievementManager`：getUnlockedAt / meetsPrerequisites / getProgressSnapshot / checkWrongBookCleared(UUID,int,boolean) / checkHelpAchievement（**Wave 2 须接线**）
- `ConfigManager`：11 门方法（isWordSystemEnabled / isAIChatEnabled / isDuelEnabled / isCheckinEnabled / isDailyChallengesEnabled / isProgressSystemEnabled / isLeaderboardEnabled / isAchievementsEnabled / isRewardsEnabled / isSocialEnabled / isOnboardingEnabled）
- `RewardEngine.isMoneyEnabled()`（public，并集语义）
- `SoundManager.playSound(Player,String,float[,float])` 重载 / playSoundByName / none 静音语义
- `VersionAdapter.sendActionBar(Player,String)`；`core.feedback.CountdownTier`
- `PlayerDataStore`：increment/getWrongBookCleared / ProfileSettings.isSoundsEnabled；`DatabaseManager`：get/setOnboardingStep / setWrongBookCleared
- `WordLearningManager.startTutorialQuiz(Player,String,int)` / getComboPitch(int) / isComboMilestone(int)；`QuizState.isTutorial`；`AnswerResult.tutorial`

## Wave 2 施工清单（下一批）

| 流 | 域 | 内容 | 主要文件 |
|----|----|------|---------|
| W2-A | 域 4 | SocialManager（新）+ 2 新表 + 广场动态页 + cheer + 摘要 + **helpful 成就接线** | core/social/、DatabaseManager、EnglishSquareGUI、SocialListener、PlayerJoinListener、LearnCommand、SoundManager、Leaderboard(RankingMode.FEED) |
| W2-B | MC-1 | 主菜单 9 按钮 + DuelChallengeGUI（新）+ Listener（新）+ GUIUtils 白名单 + 模块门渲染 | MainMenu、MainMenuListener、DuelChallengeGUI/Listener(新)、GUIUtils、config.yml gui-layout |
| W2-C | MC-3 | WordModes（新）+ AnswerChecker 参数化 + QuizState 快照 + 三入口接线（含 modes 死配置复活 + PF-MC-11 标识符本地化收口） | core/word/WordModes(新)、AnswerChecker、WordLearningManager、LearnCommand、MiscGUIListener、ChatAnswerListener、WordLearningGUI、WordLearningListener |
| W2-D | MC-5 | challenges-per-day/rewards.* 接线 + F1-12 槽位泛化 | DailyChallengeManager、DailyChallengeGUI、MiscGUIListener、ConfigManager |
| W2-E | 域 6 | /lea report + 权限节点 + ReportGenerator（新）+ 全异步 | command/AdminCommand、core/manager/（新类）、AdminCommandHandler、plugin.yml、DataExporter(ExportResult 协议化-可选) |
| W2-F | 复核 | 三路 tester（build/code/arch）+ 修正 + verify + commit | — |

### Wave 2 回流状态
- [x] W2-D 挑战经济参数：done（challenges-per-day 钳 [1,4] + 可用集合 shuffled 取 N（R-74 重抽链整体删除）+ rewards.* 四键接线（getChallengeReward 公开 API，缺键/非法/≤0 回落枚举默认；注意 YAML Integer 装箱不能用 isDouble 判定——改用 instanceof Number）+ F1-12 CHALLENGE_SLOTS={11,13,15,17} + binarySearch 查位映射；默认 3 逐位等价冒烟过）
- [ ] W2-A 广场社交 / W2-B 主菜单 / W2-C modes / W2-E 家长报告
- [x] W2-B 主菜单与对决选择器：done（主菜单 9 按钮双侧同源 + 模块门渲染/点击 + DuelChallengeGUI 在线选择器 + Listener 等值标题判定；**标题避碰实测 16 项断言全绿**——含"被否标题确实会炸"的对抗性证明 + 预 fan-in 窗口 [?key] 占位时等值判定仍自洽；EOL 考场记：git ls-files --eol 才是判据）
- [ ] W2-A 广场社交 / W2-C modes / W2-E 家长报告
- [x] W2-C modes 接线：done（WordModes 209 行配置真相源双读兼容 + AnswerChecker 参数化四签名保留 + QuizState 快照 + 三入口接线；**fan-in 裁决：firstEnabledMode 优先 choice**——修回 plan §1.(c) 固定序与 §4.3 逐位一致的内部矛盾，恢复 FEAT-MODE-01 "choice 新手默认" 语义）。范围外：PF-MC-07 残留（WordManagerImpl VALID_MODES）、PF-MC-11 第四份 localize（OnboardingManager:502）、word.invalid-mode 文案不动态收窄——记 Wave 4
- [ ] W2-A 广场社交 / W2-E 家长报告
- [ ] W2-A 广场社交：**首实例 API 502 中断**（已落盘：SocialManager 487 行 / FeedTextFormatter / DatabaseManager 两表+8方法 / Leaderboard FEED / EnglishSquareGUI FEED 渲染 / schema.sql；编译绿）；**重跑实例已启动**续写剩余：SocialListener 路由 / PlayerJoinListener 摘要 / LearnCommand 参数 / checkHelpAchievement 接线 / showSessionComplete 钩子 / DI 预热
- [x] W2-E 家长报告：done（ReportGenerator 1130 行全异步 + ReportPeriod 纯函数周一锚点 + 权限最小化 hasPermissionFor（plugin.yml 命令级门删除——Bukkit 前置拦截）+ ExportResult 协议化 + 规则引擎"给家长的话"零 AI 调用 + 降级路径（first_learned_at 未落地 → 新词节省略，不写死键）；**62 i18n 键待 fan-in**（41 文件模板 + 14 聊天 + 1 改键 admin-help.report）；范围外：plan 键名与实际代码不一致以代码为准（report.generated 非 chat-generated）、ReportGenerator 合并单类（原 plan 拆两文件）
- [x] 编排者 fan-in（Wave 2 主体）：i18n 双侧落盘完成（638=638 键级对称：report 62 键 / square.feed 23 键 / main-menu 4 键 / word 2 键，占位符逐键对照代码调用点校正 16 处）+ config.yml social/english-square 段补齐（真解析过；report 段 W2-E 已落）+ 重复顶级键清理（square/main-menu/word 三次合并）
- [ ] W2-A 第 3 实例运行中（第 1/2 实例先后被 API 502/524 中断；主体已落盘，续写 SocialListener 路由 / PlayerJoinListener / LearnCommand / checkHelpAchievement 接线）
- [x] W2 三维 tester：架构 PASS（七项全过，仅 WARN 建议级）；构建 GO 修复后复验（F-1 duel.challenge-* 4 键遗漏已补 + **新增自动化键核对 scripts/audit-i18n-keys.js** 又抓出 admin-help.report 漏网已补；现 643=643 对称 + verify 33 测试全绿；F-2 ProgressGUI 死类残留归 MC-7 不阻断）；代码质量 tester 运行中

## Wave 3 施工（分批）

### Batch 3A（4 流并行）
| 流 | 内容 | 白名单 |
|----|------|--------|
| 3A-1 赛季核心 | SeasonManager(新) + seasons/season_rewards 表 + 结算器(抢占/台账/广播/join补发) + 调度挂载(LLE×2) + ProxyMessenger + PlayerJoinListener + config season 段 | core/season/(新)、DatabaseManager、schema.sql、LetsLearnEnglish、PlayerJoinListener、ProxyMessenger、config.yml(season 段) |
| 3A-2 个人场景管理 | PersonalScenarioStore 抽取 + PersonalScenarioGUI(新) + Listener(新) + 场景选择页 slot 24 + GUIUtils | core/ai/PersonalScenarioStore(新)、AIChatManager、gui/PersonalScenarioGUI(新)、listener/PersonalScenarioListener(新)、ScenarioSelectGUI、ScenarioSelectListener、GUIUtils、LetsLearnEnglish(注册一行) |
| 3A-3 语义清理 | 裸 getString 二期(25处) + PF-MC-11 第四份 localize 收敛 | WordLearningListener、WordLearningManager、WrongBookListener、DailyChallengeManager、DuelManager、OnboardingManager、WordModes |
| 3A-4 赛季展示 | LeaderboardGUI 赛季 tab + PAPI 3 占位符 + (可选)/lea season info | LeaderboardGUI、LLEPapiExpansion、LearnCommand |

### Batch 3B（AI 反馈闭环，2 流并行——依赖 3A-2 的 Store 抽取先落地）
| 流 | 内容 | 白名单 |
|----|------|--------|
| 3B-1a 会话核心 | ChatSessionState 收敛 + transcript 缓冲 + SessionSummary + requestConversationSummary + ConversationFeedback.parse + 单测 | AIChatManager、ConversationFeedback(新)、ConversationFeedbackTest(新) |
| 3B-1b 持久化与集成 | ChatFeedbackEntry(新) + DatabaseManager(ai_chat_feedback) + ChatFeedbackManager(新) + ChatFeedbackManager 显示编排 + 三处集成(ChatScenarioListener/LearnCommand/ChatHistoryGUI) + config feedback 块 | data/ChatFeedbackEntry(新)、DatabaseManager、schema.sql、ChatFeedbackManager(新)、ChatScenarioListener、LearnCommand、ChatHistoryGUI、config.yml(feedback 块) |

### Wave 3 冻结接口（3B 用）
- `AIChatManager.SessionSummary` record(sessionId/scenarioKey/difficultyKey/validInputs/aiReplyCount/qualified/startTimeMs/durationSeconds/transcript)
- `AIChatManager.TranscriptLine` record(role, content)
- `endConversation(Player)`/`endConversationOnQuit(Player)` 返回 SessionSummary
- `requestConversationSummary(Player, SessionSummary, BiConsumer<ConversationFeedback,String>)`（主线程回调，errorKey null=成功）
- `ConversationFeedback` record(comment, corrections, words) + nested Correction(original,corrected,note) + static parse(String)→nullable
- `ChatFeedbackManager`(deliverEnd/persistOnQuit/getFeedback/unloadPlayer)
- `ChatFeedbackEntry` data class
- `DatabaseManager.upsertChatFeedback(...)` / `getChatFeedback(UUID,String)`
- `SeasonManager.SeasonWindow(int id, LocalDate start, LocalDate end)` / `SeasonRankRow(int rank, UUID uuid, String name, long score)` / `isEnabled()` / `getCurrentTopCached(int)` / `getRankOf(UUID)`
