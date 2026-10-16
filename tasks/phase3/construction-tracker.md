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

### Wave 4 施工设计（编排者规划，2026-10-15）
**分两批串行（用户"分批干"指令）**：

#### 批次 4A：班级系统（2 流并行 + fan-in）
| 流 | 内容 | 白名单 |
|----|------|--------|
| 4A-1 数据层+管理命令 | classes/class_members 建表（DatabaseManager 内嵌 DDL 双后端） + 数据方法族 + `/lea class create\|delete\|add\|remove\|list` 族（AdminCommand + AdminCommandHandler） | DatabaseManager、AdminCommand、AdminCommandHandler |
| 4A-2 班级榜+玩家入口 | `getRankedPlayersForPeriodFiltered`（PlayerDataStore） + ClassBoardGUI(新) + `/le class` 入口（LearnCommand）+ 点击处理（返回键——接入既有 listener 或新 listener，含 drag guard 纪律） | PlayerDataStore、ClassBoardGUI(新)、LearnCommand、点击监听（SocialListener 或新文件） |

**4A 冻结接口（派发前固定，两流互斥不撞车）**：
- `DatabaseManager.createClass(String className, UUID createdBy, long createdAt) -> boolean`（false=重名）
- `deleteClass(String className) -> boolean`（false=不存在；级联删成员行）
- `getClassByName(String className) -> ClassRow|null`；`listClasses() -> List<ClassRow>`
- `getClassMembers(int classId) -> List<ClassMemberRow>`（管理路径全量，LEFT JOIN player_data 取名）
- `getPlayerClassId(UUID) -> Integer|null`（一人一班）
- `addClassMember(int classId, UUID, long joinedAt) -> boolean`（换班：先删玩家现有班成员行再插；false=已在班内）
- `removeClassMember(int classId, UUID) -> boolean`；`getClassMemberCount(int classId) -> int`
- `record ClassRow(int classId, String className, String displayName, String createdBy, long createdAt)`；`record ClassMemberRow(int classId, UUID uuid, String playerName, long joinedAt)`
- `PlayerDataStore.getRankedPlayersForPeriodFiltered(int count, LocalDate since, LocalDate until, Collection<UUID> members) -> List<LeaderboardEntry>`（空集合早退→空表；SQL 同 getRankedPlayersForPeriod + `AND s.uuid IN (?,?…)`；**保留** show_in_square 隐身过滤——同学视角口径）
- `ClassBoardGUI(LetsLearnEnglish).open(Player viewer, String className, List<LeaderboardEntry> entries)`（标题 class.board-title{class}；成员按当前赛季窗聚合分排序；**数据由调用方异步拉好传入，GUI 零查库**——4A 实现期签名扩展，architecture tester 收口回写）
- 上限校验在调用方（AdminCommandHandler）：`class-system.max-members`(50)/`max-name-length`(32)

#### 批次 4B：MC-7 死键/死类大扫除（1 流 + fan-in）
- 死类：删除 `gui/ProgressGUI.java`（零 Java 引用已复核）+ `progress-gui.*` 键整段（同批成对）
- i18n 死键对称删（**修正版表，见下方侦察备忘录**）
- config 死键：`general.debug`、`database.mysql.pool-size`、`difficulty-settings.*` 整段、`progress-system.rewards.types.{item,command,exp}.enabled` 三子键、`progress-system.achievements.config-file`、`gui.main-menu-title`、`gui-layout.profile.*` 段（15 叶子）、`gui-layout.main-menu.scenario-select.back-button`
- P3-18 尾项：DuelManager 等 4 处裸 getString 收敛（如未在 3A 已收）
- **删除前逐键全路径 grep 复核（行号漂移教训）**

#### 4B 死键核销表（编排者 2026-10-15/16 复核版，ZH/EN 行号快照）
| 键 | zh 行 | en 行 | 判定 |
|----|------|------|------|
| `word-learning-gui.answer-broadcast` | 476 | 476 | 删（`answer-broadcast-correct` 保留勿删） |
| `daily-challenge.claim-button` + `-lore` | 449/450 | 449/450 | 删 |
| `leaderboard.title-weekly/monthly/alltime` | 155/156/157 | 同 | 删 |
| `leaderboard.header/entry/footer` | 152/153/154 | 同 | **保留（活键！/le season 快览在用，LearnCommand ~795-810）** |
| `word.session-end/correct/incorrect/mastery-update/daily-limit-reached` | 37/38/39/40/41 | 同 | 删（`dialogue.daily-limit-reached` L100 是活键，别误删） |
| `checkin.streak-info` | 279 | 279 | 删 |
| `general.data-saved` | 14 | 14 | 删 |
| `admin.reset-pending` | 193 | 193 | 删 |
| `error.scenario-save-failed` | 33 | 33 | 删 |
| `dialogue-gui.*` 整段（title/instructions） | 506-511 | 506-511 | 删段 |
| `progress-gui.*` 整段（9 键） | 513-521 | 513-521 | 删段 + 同批删 `gui/ProgressGUI.java` 死类 |
| MessageUtil javadoc 两处 `word.correct` 示例 | MessageUtil.java:47/198 | — | 顺手换活键示例（如 `word.timeout`） |

**config.yml 死键核销**：`general.debug`（零引用已核）、`database.mysql.pool-size`（L78，单连接无池）、`difficulty-settings.*` 整段（L634 起）、`progress-system.rewards.types.{item,command,exp}.enabled`（L284-293；money 子树保留！）、`progress-system.achievements.config-file`、config 版 `gui.main-menu-title`（L323；messages 版是活的，别删）、`gui-layout.profile.*` 段（L474 起 15 叶子，SocialListener:83 注释自认死配置）、`gui-layout.main-menu.scenario-select.back-button`
**追加（2026-10-16 DEPLOYMENT_WIKI agent 报告核实）**：`proxy.cross-server-announcements` / `proxy.cross-server-messages`（两键零 Java 读取已核——通道无条件注册/接收，与注释承诺不符；处理：**删键 + 注释改写**，勿接线（跨服通道无独立关闭语义需求，删比接简单且注释本来就误导））；`gui.custom-textures`（零 Java 读取已核；D7 已裁决不做资源包，此键随 4B 一并删除并注释）；~~`statistics-server.*`~~（**编排者复核推翻**：StatisticsHttpServer.java:53-57 读 port/bind-address/ip-whitelist/auth.username/auth.password 五键——**是活键不可删**，DEPLOYMENT_WIKI agent 的"死键"标注有误）


### Wave 4 侦察备忘录（编排者 2026-10-15 只读侦察，MC-7 派发时必读）
- **plan 死键表两处过期**：① `leaderboard.header`/`entry`/`footer` **已复活**——`LearnCommand.handleSeasonCommand`（约 795-810 行）复用为 `/le season` 前 10 快览行格式（注释"复用榜单行格式…与聊天榜样式统一"），**不可删**；② `word.correct`/`word.incorrect` 的 2 处引用是 `MessageUtil.java:47/198` javadoc 举例（非运行时引用），可删键但顺手把 javadoc 示例换成活键（如 `word.timeout`）
- 其余 plan 死键表逐键 grep 复核（2026-10-15）：`word-learning-gui.answer-broadcast` / `daily-challenge.claim-button(+lore)` / `leaderboard.title-weekly|monthly|alltime` / `word.session-end` / `word.mastery-update` / `word.daily-limit-reached` / `checkin.streak-info` / `general.data-saved` / `admin.reset-pending` / `error.scenario-save-failed` / `dialogue-gui.*` 全段 / `progress-gui.*` 全段（ProgressGUI 死类本身零 Java 引用，可整删）——全部零运行时引用确认
- `word-learning-gui.answer-broadcast-correct`（2 活引用）**保留**——与 answer-broadcast 一字之差，删除时精确匹配
- 班级系统落点：config.yml 尚无 `class-system` 段（需新增）；AdminCommand SUBCOMMANDS 列表在 `AdminCommand.java:33-36`（新增 "class" 需同步）；`PlayerDataStore.getRankedPlayersForPeriod`（1149 行）为班级榜过滤查询的基底；`SeasonManager.SeasonWindow/windowOf(id)` 提供季节窗口；`LeaderboardEntry` 复用

### Wave 4 派发要点备忘（4A）
- **4A-1 i18n 键消耗清单**（编排者 fan-in 落稿，agent 直接用）：`class.created` / `class.deleted` / `class.member-added{player}` / `class.member-removed{player}` / `class.member-full{max}` / `class.name-exists` / `class.not-found{name}` / `class.name-invalid{max}` + admin 命令反馈（含 `/lea class list` 输出头 `class.list-header{class}`）。plan §2.3 已定草案文本：title/board-title/not-in-class/empty（CLS-2 GUI 用）+ created/deleted/member-added/member-removed/member-full/name-exists/not-found/name-invalid（CLS-1 用）
- **4A-1 线程纪律**：所有班级 DB 读写异步（AdminCommandHandler 既有模式）；`/lea class list` 的异步查 → 主线程 sendMessage
- **4A-2 班级榜线程**：GUI 打开**不得主线程查库**（PF-SEA-04 教训）——异步查 `getRankedPlayersForPeriodFiltered` → `runTask` 回主线程渲染；窗口取 `season.windowOf(season.currentSeasonId(LocalDate.now()))`；赛季不可用时 `/le class` 给 `season.disabled`（班级榜依赖赛季窗）
- **4A-2 点击处理**：与 LeaderboardGUI 同姿态注册进 SocialListener？还是 ClassBoardGUI 自持 listener？——`/le class` 直达路径快照陈旧问题（PF-SOC-06 同族）需自决并注明；返回键 slot 8 → 主菜单
- **4A-2 隐私**：同学视角保留 show_in_square 过滤（SQL 内 COALESCE(pf.show_in_square,1)<>0 沿用）；管理路径 `/lea class list` 全量含隐身（plan §1.5 明示）


## 收尾（用户点名）
- 验收 + 锐评 agent（数路并行）
- 全部相关文档更新（README / CHANGELOG / tasks/ / dev-findings / 规划文档状态回写）

### 收尾文档更新清单（编排者预写，2026-10-16）
1. **dev-findings.md 施工期回流**：Wave 1/2/3A/3B 各流 agent 上报的范围外发现（散见 tracker 各流 done 行）需逐条回写到 dev-findings 表（含状态列）。已知待回流项：W2-C 的 PF-MC-07（WordManagerImpl VALID_MODES）/PF-MC-11 第四份（OnboardingManager:502，3A-3 已收）/word.invalid-mode 不动态收窄；3A-3 的 AIChatManager Material.valueOf 无保护；MC-7 的 answer-broadcast-correct 删除警戒（保留键，无需动作，仅归档）；3B build tester 低 2 项 + arch tester 低 6 项
2. **plan-master.md**：已回写进度表（本次完成）；出口标准保持不动
3. **README.md / README-EN.md / DEPLOYMENT_WIKI.md**：等文档侦察 agent 报告后逐项更新（Phase 2/3 新功能入列表：新手引导/广场社交/赛季/家长报告/个人场景管理/主菜单 9 按钮/AI 对话小结/班级系统）
4. **CHANGELOG.md**：Wave 4 条目 + 收尾条目补入
5. **construction-tracker.md**：3B/Wave4/收尾状态收口 + 本文件转归档形态



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

### Batch 3A 回流状态
- [x] 3A-3 语义清理：done（25 处全收敛 + PF-MC-11 收敛为单一实现 + DuelManager 崩溃路径消除；对抗性验证过；**plan 措辞需回写：1.21.4 抛 IAE 非 NPE**）。范围外：AIChatManager Material.valueOf 无保护（配置健壮性）；**MC-7 删 answer-broadcast-correct 死键时 ChatAnswerListener null→null 会静默失去广播（需知晓）**；WordManagerImpl VALID_MODES 残留
- [ ] 3A-1 赛季核心 / 3A-2 个人场景管理（重跑）/ 3A-4 赛季展示
- [x] 3A-1 赛季核心：done（SeasonManager 708 行 + 2 表 + 抢占式结算 + origin 协议跨服分发 + 首次启用/catch-up 语义 + 午夜零 IO 跨季检测；**两个自决：发钱不经 cap 直发（台账幂等）+ 修正 plan 缺陷（无 Vault 保留 pending 不销毁）**；SQLite 真库冒烟 24 断言 + 纯逻辑 23 断言全过）。DB 内嵌 Row 类（SeasonRow/SeasonRewardRow）；冻结接口全命中 + 3 个新增 public（refreshCacheAsync/parseTopSnapshot/applyRelayed*）
- [x] 编排者 fan-in（3A-1/3A-4 键）：season 12 键 + leaderboard.tab-season/entry-season 双语落盘（655=655 对称，audit 只剩动态拼接误报）
- [ ] 3A-2 个人场景管理（第3实例，前两实例 524 零落盘）/ 3A-4 赛季展示（半落地——LeaderboardGUI 4 私有方法待补）
- [x] 3A-2 个人场景管理（第3实例）：done（PersonalScenarioStore ~330 行抽取 + GUI/Listener 新建 + 入口 slot 24；**自决：确认态改条目 PDC 标记（屏幕即真相，无状态残留）**；标题互不命中双向核实闭合；ImportResult 留 AIChatManager 保零改动；javac 全仓 EXIT=0）
- [x] 编排者 fan-in（3A-2/3A-4 键）：scenario.manage 11 键 + help.season 双语落盘（669=669 对称）；全仓编译 EXIT=0（3A-4 半落地已自愈）
- [ ] 3A-4 赛季展示（编译已绿，等正式报告）
- [x] W3A 三维 tester：构建 **GO**（verify 全绿 137 classes / DDL 双处逐字一致 / 668=668 对称 / 窗口纯函数 7 边界断言过）；架构 **PASS**（无 FAIL；防环命中——SeasonManager 零引用成就/社交；ProxyMessenger 双向用 var 规避 import 成"类型无环"；Store↔AIChat 仅类型互引可接受）。**待收口 2 中 + 5 低**（① seasonDaysLeft 双份复制→上收 SeasonManager 单一真相源；② AIChatManager 行数未降 1155；低：Store 类型互引固化/FQN 风格/join null 守卫/同值双常量/纯函数缺单测——②⑤ 建议随 3B 处理）；代码质量 tester 运行中
- [x] **Batch 3A 提交 `a25d861`**（三维 tester GO/PASS/PASS；2中8低收口6项；i18n 668=668）
- [ ] Batch 3B 启动：3B-1a 会话核心（ChatSessionState 收敛 + SessionSummary + ConversationFeedback.parse）先行——1b（持久化/集成/显示）待 1a 落盘后放，避免 AIChatManager 撞车
- [x] 3B-1a 会话核心重构：done（ChatSessionState 5map→1map 收敛 + SessionSummary/TranscriptLine record + requestConversationSummary 总结通道（守卫表逐行）+ ConversationFeedback.parse（salvage/上限/清洗）+ 单测 6/6；**接口要点：endConversation 无会话返回 null（1b 判空）；canRequestConversationSummary 预检含 dedup；clearPendingSummary 供 quit 钩**）。自决：超时重载/预检签名/开场白不入 transcript/解析器 string-only 收紧
- [x] 3B-1b 持久化与集成：done（编排者接管完成——agent 反复 API 中断后由编排者编写 ChatFeedbackManager + ChatFeedbackEntry + DatabaseManager 数据层 + schema.sql + 四处集成（ChatScenarioListener/LearnCommand/ChatHistoryGUI/PlayerJoinListener）+ LetsLearnEnglish DI + config feedback 块）。**fan-in 收口三项**：① persistOnQuit 补 feedback.enabled 总开关门（plan §3.4"四处真接线"）；② ChatHistoryGUI 反馈物品补总开关门；③ GUI 补场景行 + 纠错 note 行 + 纠错改全量显示（config 注释"历史详情页可见全部"口径，聊天栏才是 ≤N 条）
- [x] 编排者 fan-in（3B 键）：dialogue.feedback.* 16 键（+2 子键 duration-minutes/seconds）+ chat-history.feedback-* 10 键双语落盘（叶子键 695=695、SnakeYAML 真解析 694=694 零差异）；audit 脚本只剩已知动态拼接误报；`mvn clean verify` BUILD SUCCESS（39 测试全过含 6 新解析器单测，144 类）
- [ ] 3B 三维 tester（构建/代码/架构）
- [x] 3B 构建 tester：**GO**（verify 全绿 39 测试 / DDL 12 列逐字一致 / 695=695 对称 / 6 单测真实）。低 2：ConversationFeedback 不可达防御分支注释、测试注释归因小偏差
- [x] 3B 架构 tester：**PASS**（零高零中；分层合规零新增越层 / 无环 / 职责单一 / 冻结接口 11 项全命中 / DI 单点）。低 6：①plan 签名漂移（已回写修正）；②quit 清理分裂两监听器（幂等无害，记录）；③GUI 重复实现 payload JSON 解析契约（与 ConversationFeedback.parse 双份）；④AIChatManager 1506 行（300 行准则警告级）；⑤详情页主线程单行查询（plan 授权，随 P2-27）；⑥new Gson() 每次点评 + FQN 内联风格
- [x] 3B 代码质量 tester：**GO**（2 中 4 低；线程/清理/i18n 占位符/防御性/幂等五维 PASS）
- [x] 3B 收口批次：done（6 项全收：getPlayerMessageSafe 修 {prefix} 泄露 / persistAfter 串行化 / MESSAGE_CONFIG_VERSION 13 / deliverEnd 主线程同步 / GUI 复用 parse 单一契约 / Gson static final）；`mvn clean verify` BUILD SUCCESS（144 类 / 39 测试）
- [x] **Batch 3B 提交 `459a4e8`**（三维 tester GO/PASS/GO；2中4低全收口；i18n 695=695、config-version 13）
- [x] 收尾期文档核实发现 Wave 1 漏项并已修 **`3beaeb5`**：3A-4 per-player 音效开关**只有数据层+SoundManager 门、无 UI 入口**（setSoundsEnabled 零调用者）——设置页 slot 16 开关补建（ProfileGUI+MiscGUIListener+sounds 键×4）。3A 其余 story（1/2/3/5）接线抽检完好
- [x] Batch 4A 施工：done（4A-1 第 2 实例：两表+九方法+两 record+/lea class 族；4A-2 第 2 实例：过滤查询+ClassBoardGUI+ClassBoardListener+/le class 入口——第 1 实例各 4 小时零落盘被停后重派，重派带"立即落盘"纪律两小时内全部完成）
  - **4A fan-in done**：① class.* 18 键 + help.class 双语落盘（716=716 对称）；② DDL 双处逐字一致（build tester 复核）；③ MESSAGE_CONFIG_VERSION 14（三处对齐）；④ list 分数列=赛季缓存反查方案（4A-1）与班级榜=窗口过滤查询（4A-2）双口径共存合理（前者零查库榜单快览、后者窗口精确榜——各自注释）；⑤ mvn clean verify BUILD SUCCESS（148 类/39 测试）；⑥ 三维 tester 全回：构建 **GO** / 架构 **PASS** / 代码 **GO**；⑦ GUIUtils.isPluginGUI 已补班级榜两子串；⑧ help.class 已接线 sendHelpMessage
- [x] **4A tester 收口 4 项**：isOnline 复检 / deleteClass TOCTOU 同锁+0 行报 false / tab 补全 class 动作+玩家名 / class-system.enabled 入诊断名单；arch 低项 tracker 签名回写 + javadoc 悬空引用修复。留档 2 不确定项（max-members 双服抢末位理论超额——低频可接受；GUI 隐身后过滤 vs 管理全量——有意设计）
- [x] **Batch 4A 提交 `2a9823e`**（三维 tester GO/PASS/GO；4 低项收口；i18n 716=716、config-version 14）
- [x] Batch 4B 施工：done（14 项 i18n 死键 zh/en 对称删 + ProgressGUI 死类成对删 + 11 项 config 死键 + MessageUtil javadoc 替换；每键删前全路径 grep 复核；**agent 上报 2 白名单外发现已收口**：getGuiBorderMaterial 死方法删（编排者）/ README 配置清单去 difficulty-settings（编排者）；**agent 偏差记录**：建议示例键 word.timeout 不存在（全库零命中），改用真活键 word.session-start）；javac EXIT=0、audit 零新增缺失、692=692 对称、mvn verify BUILD SUCCESS（147 类/39 测试）
- [x] **Batch 4B 提交 `753148f`**（三维 tester 全过：构建 **GO** / 代码 **GO** / 架构 **PASS** 零问题；tester 上报收口 4 项：GUIUtils 死启发式 / P3-15 死类 / getGuiBorderMaterial 死方法 / README 清理；dev-findings P2-23/P2-25/P3-04/P3-15 同步回写已关闭）
- [x] **Wave 4 全部完成**——Phase 3 施工（Wave 1/2/3A/3B/4A/4B）全部提交
- [ ] **最终验收 + 锐评 3 路 agent**（计划见 `tasks/phase3/final-review-plan.md`）
- [ ] 收尾文档最终回写（tracker 转归档 + CHANGELOG 收尾）

  - **4A fan-in 检查单**（编排者）：① class.* 15 键 + help.class 双语落盘（help 段需加 `help.class` 键 + `LearnCommand.sendHelpMessage` 一行接线——/le class 已进 SUBCOMMANDS，帮助列表需同步）；② schema.sql vs 内嵌 DDL 双处比对；③ **MESSAGE_CONFIG_VERSION 13→14**（裁决：3B 已以 13 提交，4A 新增键对已持 v13 的服不可达，与 3B tester 同理由升 14；4B 死键删除不涉新键，不重复升）；④ 4A-1 的 list 分数列方案与 4A-2 的班级榜口径一致性核对；⑤ 全仓 mvn clean verify；⑥ 三维 tester；⑦ **`GUIUtils.isPluginGUI` 补班级榜标题白名单**（`班级榜`/`Class Board` 子串——GUIUtils.java 不在 4A-2 白名单，若不补 ClassBoardGUI 会静默丢失打开/点击音效，与既有 `'s Profile`/`的主页` 动态标题同姿态）
