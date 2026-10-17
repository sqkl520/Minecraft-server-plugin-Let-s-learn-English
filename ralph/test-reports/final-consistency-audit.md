# Phase 3 跨波次一致性最终审计报告

> 审计 agent：Phase 3 最终验收（跨波次一致性视角）
> 日期：2026-10-16
> 范围：Wave 1 / 2 / 3A / 3B / 4A / 4B 六批之间的接口一致性——单批 tester 看不到的"各批自洽但批间打架"问题
> 方法：只读审计（未修改任何源文件）；脚本 + 人工双路交叉验证
> 基线：HEAD = e41a9d6（Wave 4 全部提交后）

---

## 一、逐项 verdict 与证据

### 1. i18n 键全量最终审计 —— 基本 PASS，但有 8 个死键漏删（4B 扫除不彻底）

| 检查 | 结果 | 证据 |
|------|------|------|
| audit 脚本 | PASS | `node scripts/audit-i18n-keys.js` → 仅 `achievement-wall.category-` 已知动态拼接误报（520 引用键，零真缺失） |
| SnakeYAML 真解析对称 | PASS | `zh=691 en=691 onlyZh=[] onlyEn=[]`（YKD 编译产物，SnakeYAML 1.33 真解析） |
| 30 键占位符抽查（Wave 2/3 新增） | PASS | 随机 30 键（square.feed 6 / report 8 / season 5 / class 5 / feedback 4 / scenario.manage 2）双侧 zh/en 占位符全一致，且与调用点传参逐键匹配（如 `square.feed.session` 调 `.replace("{player}")/.replace("{mode}")` 对应 zh `{player,mode}`；`season.ended-broadcast` 调参 season/top1/score1 对应同名单） |
| **反向扫描：yml 有键、Java 零引用** | **FAIL（8 个漏网死键）** | 见下方清单 |

**8 个 i18n 死键（zh/en 双侧同位存在，零 Java 引用，非动态拼接）**——4B "MC-7 死键大扫除"按核销表删除 14 项，但下列 8 项从未进入核销表：

| # | 键 | zh/en 行 | 证据 |
|---|----|---------|------|
| 1 | `review.complete` | 56/56 | 全仓 grep 零命中；ReviewScheduler 只发 `review.notification`，会话结束统一走 `word-learning-gui.session-complete`（WordLearningManager:713） |
| 2 | `achievement.unlocked` | 126/126 | 零命中；plan-achievements.md:449（PF-ACH-05）明确记录"死键……记录待菜单域死键清理一并删"——**4B 漏执行该裁决**（同段 `achievement.progress` 已复活为活键） |
| 3 | `progress.view` | 168/168 | 零命中；旧 ProgressGUI 被删（4B），且 git 历史确认旧 ProgressGUI 也从未读过此键（读的是 progress-gui.* 段）；LearnCommand.handleProgressCommand 直接开 ProfileGUI 无提示性消息 |
| 4 | `admin.export-start` | 178/178 | 零命中；/lea export 流程（AdminCommand:66-76）只发 export-failed / export-complete，无 start 提示分支 |
| 5 | `admin.word-add` | 181/181 | 零命中；AdminCommand SUBCOMMANDS 无 addword/word-add 动作，无对应命令实现 |
| 6 | `word-learning-gui.answer-hint-lore-2` | 455/455 | 零命中（值为空串 `""`）；WordLearningGUI 只读 lore-1（:113），无 lore-2 读取 |
| 7 | `word-learning-gui.current-word-lore-2` | 459/459 | 零命中（值为空串）；同上只读 lore-1（:142）与 current-word-lore-choice |
| 8 | `wrong-book.title` | 636/636 | 零命中；WrongBookGUI 用 `wrong-book.title-page`（:80），`title`（无页数版）零引用（CHANGELOG.md:325 曾改过此键，后被 title-page 取代未删） |

其余 11 个反向候选（calendar.back/back-lore/legend-*/today-marker/words-unit、word-bank.difficulty-*）经人工核实均为动态拼接读取（`getCalendarMsg` 的 `"calendar." + key`、`WordBankSelectGUI:91` 的 `"word-bank.difficulty-" + lower`）——**误报，键活**。

### 2. config.yml 全键审计 —— 2 个真死键遗留 + 1 个反向缺键

203 个叶子键逐一扫描 Java 读取点。拼接读取面（`gui-layout.` / `gui.sounds.` / `season.rewards.` / `word-system.modes.fill-blank.`）经读取函数（ConfigManager.getGuiSlot/getGuiMaterial、SoundManager.cacheSound、SeasonManager.rewardCents、WordModes.readFillBlankBoolean）证实全活。

**真死键（零 Java 读取、4B 未删、裁决悬空）：**

| # | 键 | config.yml 行 | 证据 | 严重度 |
|---|----|-------------|------|--------|
| 1 | `progress-system.leaderboard.update-interval` | 263 | plan-menu-config.md:208/363 **明确裁决"保留 + 接线"**（默认改 300、钳位 [30,3600]、注释统一为秒——原注释宣传"秒"但代码硬编码）：但 `LetsLearnEnglish.java:379-384` 刷新任务仍是硬编码 `6000L, 6000L`（ticks=5 分钟），Wave 2 MC-2/MC-5 执行清单含它却未落，4B 死键核销表也未列——**批间打架：计划 → MC 批漏做 → 4B 未兜底，键悬空**（改它完全无效，注释与行为差 10 倍：30 秒 vs 5 分钟） | 中 |
| 2 | `progress-system.rewards.types.money.vault-required` | 283 | 全仓零 `vault-required` Java 引用；Vault 可用性实际由 `eco == null` 运行时判定（RewardEngine），不读此键；dead-config-audit.md 把 money 子树整体判"活"但未单列此键——4B 核销表未列，死键漏网（删除无行为变化） | 低 |

**反向缺键（Java 读、config.yml 无此键）：**

| # | 键 | 证据 | 严重度 |
|---|----|------|--------|
| 3 | `gui-layout.main-menu.wrong-book` | MainMenu.java:97 与 MainMenuListener.java:132 双侧读取（默认 14）；R-47 注释声明"错题本按钮接配置（与其它 6 按钮一致）"——但 config.yml `gui-layout.main-menu` 段从未落地该键（7 老键 + duel/checkin 有，唯独 wrong-book 缺）。fallback 双侧一致（14/BOOKSHELF），行为正确，但"接配置"声明只落了一半（服主看不到、也改不了此槽） | 低 |

**默认值双侧比对**（config.yml 值 vs 代码 getX 默认值）：抽查 40+ 键全部一致——answer-timeout 30、words-per-session 10、daily-cap 500、menu-size 27、option-count 4、time-limit 0、display-count 10、challenges-per-day 3、rewards 30/50/25/40、season anchor 2026-11-02/duration 30、class max-name 32/max-members 50、feedback.countdown 5/3、combo 0.10/1.6、report 0.5/30/50/5、social 7/750、ai-chat feedback 400/15/4000/3、onboarding session-words 3、tts 5、backup 1440/7 等，逐个对照均一致。

### 3. PAPI 占位符 —— PASS（接口与 gating 全对），附 1 条跨批数据源实伤

`LLEPapiExpansion.java` 全清单 14 个占位符逐项核对：

| 占位符 | 数据源 | gating（关模块返回空串） | verdict |
|--------|--------|------------------------|---------|
| score/words/accuracy/sessions/correct/incorrect | PlayerDataStore.getPlayerStats | RESULTS_GATED（showResults=false 非本人 → placeholder.private） | PASS |
| coins_earned | DatabaseManager.getCoinsEarned | 只本人（门禁 3） | PASS |
| rank | Leaderboard.getPlayerRank | 隐身非本人 → rank-unranked（与 P0 R-05 SQL 口径对齐） | PASS |
| streak / longest_streak | player_data 列直查（getStreak） | 隐身门禁 1 | **接口 PASS，数据 FAIL**——`current_streak` 列零写入（见下方遗留账 A） |
| achievements | AchievementManager.getUnlockedCount / values().length（22） | 隐身门禁 1 | PASS |
| achievement_<id>（动态） | Achievement.fromId + hasAchievement | 未知 id 回 null legacy 契约（不缓存）；预检短路 | PASS |
| season | SeasonManager.currentSeasonId | `isAvailable` false → `""` 真 gating（空串优先于一切隐私语义） | PASS |
| season_days_left | SeasonManager.daysLeft（单一真相源，L4 收口） | 同上 | PASS |
| season_rank | SeasonManager.getRankOf（内存快照） | 同上 + 隐身非本人 → rank-unranked | PASS |

`SeasonManager.isAvailable / daysLeft / getRankOf / currentSeasonId / refreshCacheAsync` 全部存在且在冻结接口清单内；`isAvailable`/`daysLeft` 已收口为单一真相源（3A tester L4 的复制实现净化已落）。

**遗留账 A（跨批实伤，非本审计新发现但须在 Phase 3 出口如实记账）**：`%lle_streak%` 读 `player_data.current_streak`，该列全库零写入（`persistLongestStreak` 只写 longest_streak；真数据源是 `checkin_data.streak_count`）——占位符恒 0。dev-findings.md:27（P3-03）状态仍"待排期（Phase 3：streak 占位符改走 CheckInManager.getCheckInInfo，或补 current_streak 写入）"——**Phase 3 六批全部完成，此项未做且文档未标记**。同族 P3-02（learning_log 零写入）亦未关。两 P3-xx 挂账本身记录诚实（未谎报关闭），但"Phase 3 修复"的计划措辞已随 Phase 3 结束而失效，收尾时应改为"未排期"或立即修复。

### 4. DB schema 全审计 —— 表级 PASS，deletePlayerData 有 1 个批间缺漏

**schema.sql（21 个对象）vs DatabaseManager 内嵌 DDL（19 表）逐表逐字比对**：player_data / word_progress / achievements / learning_log / player_sessions / player_profiles / player_achievements / daily_challenges / daily_challenge_words / ai_chat_history / ai_chat_feedback / difficult_words / checkin_data / social_feed / social_cheers / seasons / season_rewards / classes / class_members——**19 表列名/类型/DEFAULT/UNIQUE/PK 全部逐字一致**（仅 AUTOINCREMENT vs AUTO_INCREMENT 双后端差异，属预期；schema.sql 为文档性质写 SQLite 版）。3B/4A 新增表的既有 build tester "DDL 双处逐字一致"结论对全表集复核成立。

**索引文档缺口（低）**：内嵌 DDL 建 10 个索引（ai_chat_history×2、player_sessions×2〔PF-SEA-03 补〕、social_feed×2、social_cheers×2、season_rewards×1、class_members×1）；schema.sql 只文档化 7 个——**缺 `idx_sessions_uuid_date` / `idx_sessions_date` 两条**（PF-SEA-03 补的 player_sessions 索引未回写 schema.sql）。纯文档缺口，运行时不缺索引。

**deletePlayerData 全表覆盖审计（reset 残行风险）**：

覆盖清单（3 组）：uuid 系 9 表（player_data / word_progress / achievements / player_achievements / learning_log / player_sessions / player_profiles / daily_challenges / season_rewards）+ player_uuid 系 5 表（daily_challenge_words / ai_chat_history / difficult_words / checkin_data / ai_chat_feedback）+ social 顺序敏感 3 步（cheers 发出 / cheers 指向自己 feed / 自己 feed）。

**缺漏 1 表：`class_members`（uuid 列）** ——4A 新增表，玩家列名是 `uuid`（DatabaseManager.java:379-385），既不在 uuid 系清单也不在 player_uuid 系清单。后果：`/lea reset <玩家>` 后该玩家的班级成员行残留——他仍占班级名额（getClassMemberCount 含他）、班级榜仍含其 uuid（DB 层 LEFT JOIN player_data 查不到名字回落 uuid 字符串）、`getPlayerClassId` 仍返回旧班（重进游戏后 `/le class` 直接进旧班榜）。**批间缺口实锤**：deletePlayerData 最近一次扩表（ai_chat_feedback）发生在 3B，4A 在其后新建了 class_members 却没有回头补 deletePlayerData（4A tester 按单批视角看不到 deletePlayerData 这个跨批函数）。严重度：中（低频管理操作 + 数据残留非破坏性，但属"数据永不删"纪律的明确破口——reset 的语义就是全删）。

另核实：`seasons`（无玩家列）与 `classes.created_by`（建班者 UUID）不在删除范围——属合理设计（结算台账/班级头是服务器级实体）。

### 5. 线程纪律抽查（10 个 GUI 打开路径）—— 纪律"新功能守住、旧面在案、2 处未入账"

| # | GUI | 打开路径 DB 行为 | verdict |
|---|-----|-----------------|---------|
| 1 | MainMenu | 零 DB，纯缓存/配置 | PASS |
| 2 | ProfileGUI | `getPlayerName`（Bukkit 名册，非 DB）+ `getPlayerStats`（缓存；miss 时 loadPlayerData 触发主线程 3 SQL）+ `getProfileSettingsReadOnly`（缓存不触发）。join 预载后通常全命中 | 在案（F1-20/F2-11 同族既存）；低 |
| 3 | LeaderboardGUI | 4 tab 全走内存缓存（getCached* / getCurrentTopCached / getRankOf 内存扫描）——**零查库**（W3A tester 已证） | PASS |
| 4 | ClassBoardGUI | `open(viewer, className, entries)` 数据在手；LearnCommand:786-828 异步查→`runTaskSafe` 回主线程（含 isOnline 复检）——**异步黄金样板** | PASS |
| 5 | ChatHistoryGUI | `getChatHistoryCount` + `getChatSessions` + N×`getSessionPreview` + 详情页 `getFeedback` 全部主线程直查 DB | 在案（P2-27，dev-findings:18"待排期"；3B arch tester 确认"未触碰未加深"）；低 |
| 6 | AchievementWallGUI | `getUnlockedAchievements`（缓存 miss → 主线程 loadPlayerAchievements）+ `getProgressSnapshot`（getCoinsEarned 直查 + fetchSessionDays 直查等 ≤5 有界查询，注释自认"主线程，可接受"） | 在案（PF-ACH-08"统一转异步属跨域决策"）；低 |
| 7 | LearningCalendarGUI | `getSessionDays(uuid, 35)`（PlayerDataStore:259-275）**主线程直查 player_sessions**（synchronized 全表日期范围查询） | **未入账（新发现）**；低-中 |
| 8 | DailyChallengeGUI | `getTodayChallenges`（:302 起）主线程直查 daily_challenges **且前置 `degradeStaleChallenges` 可执行 UPDATE**；`getTodayWordProgress` 的 `ensureWordChallengesAssigned` 可执行 INSERT + 查库——**打开 GUI 可触发写库** | **未入账（新发现）**；中 |
| 9 | EnglishSquareGUI | FEED/ALL_PLAYERS 零查库（快照）；WEEKLY/MONTHLY/ALL_TIME 走 `getTopPlayers` → `getRankedPlayersForPeriod` 主线程直查；榜单模式自己头像 lore `getCoinsEarned` 直查 | 在案 ×2（PF-SEA-05 / PF-SOC-01）；低 |
| 10 | WrongBookGUI | `getDifficultWordsData` 缓存读取；miss 时 `loadDifficultWordsFromDB` 主线程查库（join 预载后通常命中；reset 后重载场景 miss） | 既有口径；低 |

**纪律判定**：**"0 数据库 GUI 打开"并非全局事实**——10 个中 6 个存在主线程 DB 路径。其中 4 个有明确在案记录（P2-27 / PF-ACH-08 / PF-SEA-05 / PF-SOC-01），属"已知未清面"；2 个（LearningCalendarGUI.getSessionDays、DailyChallengeGUI.getTodayChallenges+写路径）**从未进入 dev-findings 或任何 plan 的记录**——按 Wave 2 起"新功能不得复制旧模式"的纪律（4A ClassBoardGUI、SocialManager 快照、SeasonManager 缓存均正确守住），这两处属**旧面漏检**：日历是 BUG-CALENDAR-LISTENER 批修过标题/监听但无人审其数据路径；挑战 GUI 被 MC-5 批触碰过（CHALLENGE_SLOTS 改造）但审的是槽位映射不是线程。severity 定中（DailyChallenge 含写路径，比纯读的 PF-SOC-01 更重一档；两者操作频率低、查询有界，实际伤害有限）。

### 6. 跨服协议 ProxyMessenger —— PASS（4 子通道全闭环）

| 子通道 | 发送端 | 接收端处理 | verdict |
|--------|--------|-----------|---------|
| GlobalMessage | AdminCommand.java:175（/lea broadcast） | handleGlobalMessage → Bukkit.broadcastMessage（:240-244） | PASS |
| AchievementUnlock | AchievementManager.java:289（tryUnlock 广播，4 段 payload playerName\|uuid\|zhName\|enName） | handleAchievementBroadcast（:255-276）：4 段 + 旧 3 段兼容 + per-recipient 语言渲染 + 模板缺失回落 zh/null | PASS |
| SeasonSettled | SeasonManager.java:418 | handleSeasonRelay(settled=true) → applyRelayedSettlement（:643） | PASS |
| SeasonStarted | SeasonManager.java:419 | handleSeasonRelay(false) → applyRelayedSeasonStarted（:671） | PASS |

帧解析（`[subChannel UTF][short 帧长][载荷字节]`）已按 PF-SEA-01 修复：先消费帧长 + readFully 取载荷；非法帧 try-catch 记 fine 不穿出监听器（:176-202）。origin 协议 v1（`{origin}|{seasonId}`）+ `proxy.server-name` 空值/回环去重语义注释完整。未知子通道静默忽略。`proxy.cross-server-announcements/messages` 死键已随 4B 删除且注释已改写为"无条件注册"诚实版（config.yml:467-470）——批次闭环。**接收端双 servr 行为未实测**（P3-10 记录在案"跨服实测待双服环境"），属环境限制非代码不一致。

### 7. Wave 1 冻结接口兑现 —— 全部兑现，仅 CountdownTier 学习侧未接（低）

tracker"Wave 1 冻结接口一览"逐项 grep 消费点：

| 接口 | 消费点 | verdict |
|------|--------|---------|
| OnboardingManager：interceptQuizStart | LearnCommand:249 / MiscGUIListener:158 | ✓ |
| onTutorialSessionComplete | WordLearningManager:752 | ✓ |
| onProfileOpened | ProfileGUI:44 | ✓ |
| handleJoin | PlayerJoinListener:62 | ✓ |
| unloadPlayer | PlayerJoinListener:120 | ✓ |
| giveGuideBook / handleGuideCommand / getStep / bookKey | 内部调用 + LearnCommand:154 | ✓ |
| AchievementManager：getUnlockedAt | AchievementWallGUI:122 | ✓ |
| meetsPrerequisites | AchievementWallGUI:147 + tryUnlock 内部 | ✓ |
| getProgressSnapshot | AchievementWallGUI:94 | ✓ |
| checkWrongBookCleared | WordLearningManager:819 | ✓ |
| **checkHelpAchievement（"Wave 2 须接线"）** | **SocialListener:686（W2-A 兑现）** | ✓ 核实闭合 |
| ConfigManager 11 门 | word 9 / ai 5 / duel 9 / checkin 3 / daily 7 / leaderboard 5 / achievements 2 / rewards 1 / social 2 / onboarding 4 消费点；isProgressSystemEnabled 外部 0 但被 3 个 AND 方法内部消费（伞语义，设计如此） | ✓ |
| RewardEngine.isMoneyEnabled | LearnCommand:615 / DailyChallengeManager:724+ / CheckInManager / MiscGUIListener（R-44 收口） | ✓ |
| SoundManager.playSound 重载 / playSoundByName / none 静音语义 | 113 处消费 + AchievementManager:283（unlock-sound 真接线）+ isSilenceMarker 双路径 | ✓ |
| VersionAdapter.sendActionBar | WordLearningManager:446 / ChatAnswerListener:225 | ✓ |
| **core.feedback.CountdownTier** | **仅 DuelManager:671 消费；WordLearningManager 学习 timer 未用**（:441 内联 `remaining <= urgentSeconds` 等价判定）——plan-audio-feedback:172 声称"学习 timer、对决 timer 共用 tier 判定"只落了一半（行为等价，无实伤）；且无 CountdownTierTest 单测（plan:269"纯函数按 Phase 0 约定配单测"未配——现有 WordLearningManagerComboTest 测的是 getComboMultiplier，非 getComboPitch/tierOf） | **部分（低）** |
| PlayerDataStore：increment/getWrongBookCleared / ProfileSettings.isSoundsEnabled | WordLearningManager:818 / SoundManager:194 / ProfileGUI:309 / MiscGUIListener:100（设置页 UI 入口已补，8638046） | ✓ |
| DatabaseManager：get/setOnboardingStep / setWrongBookCleared | OnboardingManager:108/462/474 / PlayerDataStore:203 | ✓ |
| WordLearningManager：startTutorialQuiz / getComboPitch / isComboMilestone | OnboardingManager:180 / ChatAnswerListener:232 / :223 | ✓ |
| QuizState.isTutorial / AnswerResult.tutorial | 内部状态机 / ChatAnswerListener:259 | ✓ |

---

## 二、不一致清单（文件:行号 + 证据 + 严重度，按严重度降序）

| # | 严重度 | 位置 | 问题 | 证据 |
|---|--------|------|------|------|
| C-1 | **中** | `config.yml:263` vs `LetsLearnEnglish.java:379-384` | `progress-system.leaderboard.update-interval` 裁决"保留+接线"未执行，键悬空（既没接线也没删）：plan-menu-config.md:208 定案"默认改 300、钳位 [30,3600]、注释统一为秒"，实际代码仍硬编码 `6000L, 6000L`，config 注释"刷新间隔（秒）"与真实周期（5 分钟）差 10 倍且改键无效 | MC-2/MC-5 执行清单（plan:112）含此项但未落；4B 死键核销表（tracker:86）未列此键；三方（计划/MC 批/4B）互相以为对方处理 |
| C-2 | **中** | `DatabaseManager.java:2317-2386`（deletePlayerData） vs `DatabaseManager.java:378-385`（class_members DDL） | `/lea reset` 不清理 `class_members`：玩家 class_members 行以 `uuid` 为列且不在 uuid 系 9 表清单，reset 后残留（占班级名额、班级榜含其 uuid、getPlayerClassId 仍返回旧班） | deletePlayerData 扩表在 3B（ai_chat_feedback），4A 其后新建表未回头补——跨批缺口；4A 单批 tester 不审 deletePlayerData 全局覆盖率 |
| C-3 | **中** | `gui/DailyChallengeGUI.java:61`（getTodayChallenges）+ `core/DailyChallengeManager.java:302-330`（degradeStaleChallenges UPDATE）+ `:792-795`（ensureWordChallengesAssigned INSERT） | 挑战 GUI 打开路径主线程直查 DB **且可触发写库**（degrade 的 UPDATE / 首次分配 INSERT）——线程纪律"打开零查库"破口，且**从未入账**（dev-findings/plan/tester 报告零记录）；MC-5 批触碰过此 GUI（槽位改造）但未审线程 | 与 F2-11/PF-SOC-01 同族；定级比纯读一档（含写） |
| C-4 | 低 | `config.yml:283` | `progress-system.rewards.types.money.vault-required` 死键：零 Java 读取（Vault 判定实际走 eco==null），4B 核销表未列 | dead-config-audit 把 money 子树整体判活未单列；改键零效果 |
| C-5 | 低 | `messages/zh.yml:56,126,168,178,181,455,459,636` + en 同位 | 8 个 i18n 死键漏删（4B 扫除不彻底）：review.complete / achievement.unlocked（PF-ACH-05 明确说"待菜单域死键清理一并删"却漏）/ progress.view / admin.export-start / admin.word-add / answer-hint-lore-2 / current-word-lore-2 / wrong-book.title | 全仓 grep 零引用 + 非动态拼接（拼接面已逐点排除）；audit-i18n-keys.js 只查"引用→缺键"方向，不查"键→无引用"方向，故 4B 后零告警 |
| C-6 | 低 | `gui/MainMenu.java:97` / `listener/MainMenuListener.java:132` vs `config.yml:354-381` | `gui-layout.main-menu.wrong-book` 反向缺键：代码双侧读取（默认 14/BOOKSHELF）但 config.yml 从未落地——R-47"错题本按钮接配置"只落一半，服主不可见不可调 | fallback 双侧一致故行为正确；属配置对称性缺口 |
| C-7 | 低 | `gui/LearningCalendarGUI.java:89` → `PlayerDataStore.java:259`（getSessionDays） | 日历 GUI 打开路径主线程直查 player_sessions（35 天窗口同步查询）——**从未入账**；BUG-CALENDAR-LISTENER/FEAT-calendar-move 两批修过日历但审的是标题/监听/导航，数据路径无人审 | 与 F2-11 同族漏检面 |
| C-8 | 低 | `resources/schema.sql` | 索引文档缺 2 条：`idx_sessions_uuid_date` / `idx_sessions_date`（PF-SEA-03 运行时补建但未回写 schema.sql 的 7 索引清单） | schema.sql:222 注释自称"仅为参考文档"，文档与实建清单不完全一致 |
| C-9 | 低 | `core/feedback/CountdownTier.java` | Wave 1 冻结接口利用不完整：plan 声称学习/对决共用 tier 判定，实际仅 DuelManager 消费（WordLearningManager:441 内联等价逻辑）；且 plan:269 要求纯函数配单测未配（无 CountdownTierTest） | 行为等价无实伤；属账目偏差 |
| C-10 | 低（沿用挂账，须在收尾改措辞） | `expansion/LLEPapiExpansion.java:267`（streak 占位符） | P3-03（player_data.current_streak 零写入）与 P3-02（learning_log 零写入）在 dev-findings 标"待排期（Phase 3：…）"——Phase 3 已终结，两项未做且措辞未更新 | %lle_streak% 恒 0（数据源接口存在但数据死列）；P3-02 影响统计 HTTP 面 |

**范围外发现（只上报不修复）**：无新增范围外 bug；C-1~C-9 均为本审计范围内结论。

---

## 三、出口标准判定："配置面无设了没用的键"

**判定：接近达标但未完全达标（2 个 config 死键 + 8 个 i18n 死键 + 1 个反向缺键）。**

依据：
1. **Config 侧**：203 叶子键中 2 个真死键遗留（`update-interval`——裁决接线未执行属流程悬空更重；`vault-required`——漏网死键），占 1.0%。其余全部有运行时读取点（含拼接读取面经函数级证实）。**不满足"无设了没用的键"字面标准**；但两者删除/接线均为一行级改动，修复成本极低。
2. **i18n 侧**（同口径孪生标准）：691 叶子键中 8 个死键（1.2%），全部来自 4B 核销表未覆盖的漏网（含 PF-ACH-05 已明确记但漏删的 achievement.unlocked）。audit-i18n-keys.js 是单向检查（引用→缺键），无法发现此类，建议补双向模式或一次性人工清尾。
3. **反向**：1 个键代码读但 config 无（wrong-book 槽位）——fallback 正确故无玩家可见影响，但声明与落地不齐。
4. 判断：**"配置面无设了没用的键"应判"条件达标"**——工程上接近零（97%+ 键有读取点或为文档化有意保留），但严格按字面（0 死键）**未达标**。建议收尾批次一次性处理 C-1（接线或按其裁决翻转为删除）、C-4/C-5/C-6（删除/补键），即可正式达标。

---

## 四、不确定项

1. **update-interval 的意图归属**：plan 裁决"保留+接线"，但也存在一种可能——某处后置决策翻了案（如认为 5 分钟固定更合理）而未回写文档。grep tracker/CHANGELOG/报告零记录，无法证实翻案存在，按"计划未执行"记账；最终以用户/编排者裁决为准（接线：一行 + 钳位；或删除：与 4B 同姿态）。
2. **class_members reset 残留的用户可见面**：理论链（名额占用/班级榜 uuid 串渲染/自动回旧班）基于代码推导，未起服实测。若用户认为"reset 不涉班级（班级是管理域）"，则 C-2 可降级为"设计口径争议"——但 deletePlayerData 现有语义（"训练数据全删"）与 R-03 先例（social 残行都要防）倾向应清。
3. **DailyChallengeGUI 写路径的实际触发频率**：`degradeStaleChallenges` 仅在 AI 不可用时执行 UPDATE，`ensureWordChallengesAssigned` 仅在当日未分配时 INSERT——日常稳态为纯读。strict severity 视服务器规模而定（百人服主线程 1-2 条有界 SQL 每点击，可接受；千人高频点击面存在）。
4. **双服未实测面**：ProxyMessenger 修复（PF-SEA-01）与赛季跨服分发（origin 去重/回环）均标注"待双服环境实测"——单服静态审计只能确认代码路径完整，无法证实代理行为假设（Forward ALL 是否回环）。已在案（P3-10），非本审计新增。
5. **PAPI getStreak/getCoinsEarned 直查 DB**：30s TTL 缓存（R-43）兜住大部分计分板刷新频率，标注"可接受"；若计分板插件高频携带不同 params 击穿缓存（5000 上限随机淘汰），极端场景仍可回落到每 30s 每键一次主线程查询——无法静态判定，记录备查。

---

## 五、总结

- **7 项审计中 3 项全 PASS**（PAPI 占位符、DB schema 表级、跨服协议）、**3 项 PASS 但有低量遗留**（i18n、线程纪律、冻结接口）、**1 项 FAIL**（config 全键：2 死键 + 1 反向缺键）。
- **跨批打架的典型模式**（单批 tester 盲区）在本轮全部现形：①**计划裁决在 MC 批漏做、4B 又未兜底**（update-interval——三方互相以为对方处理）；②**后建表没回头补跨批函数**（class_members vs deletePlayerData）；③**旧面数据路径从不入账**（日历/挑战 GUI 线程纪律，改过多轮但无人审数据面）；④**单向审计工具的固有盲区**（audit 脚本只查引用→缺键，8 个反向死键漏网）。
- 总体一致性 verdict：**良好（GO with follow-ups）**——无高危不一致，中危 3 条均为低频/管理面且修复成本一行级；Phase 3 的"新功能新纪律"（异步开 GUI、快照读、gating 三层语义、DDL 双处一致、i18n 对称）经跨批复核确实守住，破口全部集中在存量面与清理批的漏网。
