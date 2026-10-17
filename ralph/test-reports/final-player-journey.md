# Phase 3 最终验收报告 — 玩家旅程贯通性（整体成品视角）

- 日期：2026-10-16
- 方法：只读走代码，不跑 mvn、不启服务器。对每个旅程步骤从入口（命令/GUI/事件）追踪到终点（结算/落库/显示）。
- 基线：Wave 1/2/3A/3B/4A/4B 全部提交后的工作树（pom 1.0.7-SNAPSHOT）。
- 说明：本报告不重复单批 tester 已覆盖的批内质量项；重点找**跨批次衔接断裂**与"声明 vs 代码"落差。

---

## 一、逐步 verdict

### 1. 首次进服 — 贯通

- 链路：`PlayerJoinListener.onPlayerJoin`（PlayerJoinListener.java:25-94）→ welcome×3（`:46-58`，hasPlayedBefore 门 + 40L 延迟 + isOnline 复检）→ `OnboardingManager.handleJoin`（`:62`；OnboardingManager.java:103-147：step 0 + 首进 → 写 1 + 发 WRITTEN_BOOK+PDC 手册 + 40L join-intro）→ 首进当天 join 每日提醒被抑制（PlayerJoinListener.java:69 `hasPlayedBefore` 门，D2 决策落地）→ `/le guide`（LearnCommand.java:152-155）与引导书右键（OnboardingManager.onInteract `:350-381`，含"点方块防误开箱"cancel）双入口成立。
- 迷你会话：两条开局路径（`LearnCommand.startWordQuiz:249`、`MiscGUIListener.handleWordBankSelect:158`）均在既有互斥守卫之后接 `interceptQuizStart` → `startTutorialQuiz`（WordLearningManager.java:108-121，mode 强制 choice、题数 onboarding.session-words）。答错原地重试（submitAnswer tutorial 分支 `:236-244` 零推进 + ChatAnswerListener.java:259-261 retry-wrong 文案 + 重开同题 GUI）；超时同待遇（`:294-302` + retry-timeout）。4 选 1 必中 + 重试不判错 ⇒ 3/3 结构保证。
- 结算钩子：`showSessionComplete` 末尾 `onTutorialSessionComplete`（WordLearningManager.java:751-753）→ step 1→2 + step2-done；`ProfileGUI.open` isOwn 分支 `onProfileOpened`（ProfileGUI.java:43-45）→ step 2→3 + complete 文案/音效 + 收书。step 单调写（advanceStep `:468-475`）+ 老号 fail-open 回填 3（`:112-117`）。FIRST_WORD 由 `checkSessionAchievements` sessionCorrect≥1 判定（AchievementManager.java:365-367，PF-ONB-01 修复已落码）。
- 玩家可见：欢迎三行 → 引导 intro → 背包出现引导书 → 3 题小会话（答错"没关系，再试一次"）→ 会话完成 + 首成就 toast/Title → step2-done 指路 `/le progress` → 开主页得 complete 文案与音效、书被收回。叙事顺序在 `showSessionComplete` 内正确（成就广播先、引导 toast 后）。
- 小瑕疵（不构成断裂）：若管理员关闭 `modes.choice.enabled`，迷你会话仍强制 choice 模式（TUTORIAL_MODE 硬编码，OnboardingManager.java:61、startTutorialQuiz 显式传 "choice"）——管理意图与引导体验冲突，属边缘配置。

### 2. 第二次进服 — 贯通

- 无 welcome 重复（hasPlayedBefore 门）；join 每日提醒经 `sendJoinDailyReminderIfEnabled`（LetsLearnEnglish.java:431-448，enabled+on-join+自然日去重三重门）。
- `/le word` → `WordModes.firstEnabledMode` 默认仲裁（优先 choice）→ `startQuiz` → GUI + session-start；关界面 → `onInventoryClose` → `startAnswerForCurrentWord`（WordLearningListener.java:49-71, 121-170）→ 聊天答题提示 + `startAnswerTimer`。
- 倒计时：ActionBar（WordLearningManager.java:436-446），urgent 阈值 + tick 音随秒升调（`:448-455`）；答对连击音 pitch（ChatAnswerListener.java:223-232；getComboPitch WordLearningManager.java:620-631）、里程碑铃音 + ActionBar 闪光（`:223-229`）。
- 会话走完：complete 音 + `showSessionComplete` → RewardEngine 会话金币 + recordSession（R-30 先落库后判成就）+ 成就 + 挑战 SESSION_3 + 广场 SESSION_DONE 动态；`endSession` 内榜单 updateScore 增量（WordManagerImpl.java:163-165，R-42 已接线）。
- 玩家可见：一句话提醒（若当日未提醒）→ 词库选择 → 关闭界面后聊天输入选项 → 每秒 ActionBar 倒计时、最后 3 秒 tick → 答对 +分与连击文案 → 会话结算行 + 金币提示。

### 3. AI 对话闭环 — 贯通

- `/le dialogue restaurant`（LearnCommand.java:298-340）：场景存在性（大小写不敏感归一）+ 四态互斥 + `addPlayer` + `startConversation` → started/instructions。
- 聊天消费：ChatScenarioListener（HIGH）→ bye/再见 等 → 切主线程 `handleEndConversation`（:178-193，PF-ACH-02 修复在案）：ended（getPlayerMessageSafe，{prefix} 泄露已在 3B 修）→ `ChatFeedbackManager.deliverEnd`（主线程同步，ended → 卡 → return-menu 时序与 plan 一致）→ 小结卡（stats/counts/passed 或 not-passed/empty 三态）→ 本地行先落库（persistAsync）→ AI 点评占位 → `requestConversationSummary`（独立通道：豁免冷却/轮次，独立去重 pendingSummaries）→ 点评块（≤3 纠错 + 推荐词 + hint），AI 覆写经 future 串行（persistAfter，本地行永不覆盖 AI 行）。
- `/le history` → 详情页 slot 49 反馈物品（ChatHistoryGUI.renderFeedbackItem `:349-433`，复用 ConversationFeedback.parse 单一契约；坏 JSON 降级"无 AI 点评"；feedback.enabled=false 时零物品零查库）。
- 全程 695=695 i18n 对称、总开关四处真接线（deliver/quit persist/GUI 物品/落库）均已核实。

### 4. 广场社交 — 贯通

- `/le square` → 默认落点 feed（EnglishSquareGUI.resolveDefaultMode `:87-91`，config english-square.default-mode: "feed"）。渲染读 volatile 快照（≤200 条），唯一同步查询 = 每页 ≤28-id 已鼓励集合。
- 左键鼓励：SocialListener.handleFeedSlot（:657-698）→ `SocialManager.cheer` 校验链（门/条目存在/自赞/作者隐身复查/750ms 防抖）→ 异步写（唯一约束兜底）→ 主线程分发：cheer 音 + cheer-success + 乐观 +1 + 作者在线即时 notify-online + 异步标已读；作者离线留 read_by_owner=0 → 下次进服 `notifyPendingCheers` 摘要（异步查 → 主线程发 → 异步标已读，未发出不标已读）。
- helpful 成就：鼓励成功回调 → `checkHelpAchievement`（AchievementManager.java:444-461，异步判定 getCheerStats().distinctTargets≥10，tryUnlock 通知段自回主线程）。墙进度行为 NONE（事件型，plan 既定）。
- FEED 页"点 A 开 B"缺口已由 renderedFeedPages 打开时冻结 + detectModeFromTitle 治理（Wave 2 集成修正，已核实）。

### 5. 榜单 — 大体贯通，存在一个静默陷阱（部分断裂）

- `/le leaderboard` → 4 tab 渲染正确：`TAB_SEASON_SLOT=3` + 信息牌 slot 4 + 既有三 tab 零挪位（LeaderboardGUI.java:52-65）；赛季 tab 渲染/点击双侧同门（SocialListener.java:486-504）。
- 点头像 → 主页：**断裂点在"直开列表的首次头像点击"**。`LearnCommand.handleLeaderboardCommand`（LearnCommand.java:427）直开 GUI 但**不登记** `SocialListener.leaderboardGuis` 快照；点击头像分支要求快照存在：`LeaderboardGUI gui = leaderboardGuis.get(...); if (gui == null) return;`（SocialListener.java:526-527）。快照只在点击 tab（`:517`）或"主页返回榜单"（`:538`）时登记。
- 玩家实际看到：`/le leaderboard` 打开后直接点第 1 名头像——**无任何反应**（无音效、无消息、无界面）。先点任意 tab（含当前 tab）后点头像即恢复正常。
- 这正是 dev-findings.md:164 的 F3-01（"排行榜点头像无反应"，状态"待排期"）与 CHANGELOG.md:39 明示的 PF-SOC-06（"同类面在 leaderboardGuis 未修——记 Wave 3"）。**Wave 3/3A/3B/4A/4B 全程未修**，跨批次留账未清。广场同类面已修（renderedFeedPages + 兜底重建），榜单面被遗漏。

### 6. 赛季（season.enabled=true 时）— 逻辑贯通，但工厂默认配置下有"开而不动"陷阱

- `/le season`（LearnCommand.java:851-908）：零查库读缓存（title/remaining/rank/top10 快览复用 leaderboard.header/entry/footer 活键）。PAPI 三占位符（LLEPapiExpansion.java:191-208）真 gating（未启用一律空串，隐私同口径），赛季信息牌/标题与 SeasonManager 单一真相源（daysLeft/isAvailable，L4 收口已核实）。结算链已核实为真：条件 UPDATE 抢占（DatabaseManager.tryClaimSettlement:2074-2090）、tester M1 的付款失败回滚 claimed（SetSeasonRewardClaimed `:2181+`）、无 Vault 保留 pending 不销毁。
- **陷阱**：`SeasonManager.isEnabled()`（:131-136）折叠"锚点在未来 → id=0 → 全系统休眠"，且**零提示、零日志**。config 出厂 `anchor-date: "2026-11-02"`（config.yml:663）——以本环境日期 2026-10-16 计，运营者按验收清单把 `season.enabled: true` 打开后，`/le season`、赛季 tab、PAPI、结算……**全部静默无反应**，看起来像功能没做。这不是代码缺陷（plan 明示"锚点在未来视为未开启"），但是验收/上手路径上的实际断点。

### 7. 班级（class-system.enabled=true 时）— 贯通（依赖赛季前提，已有明确提示）

- `/le class`（LearnCommand.handleClassCommand:762-831）：class-system 门 → 赛季可用性门（不可用明确回 season.disabled，非静默）→ 未入班回 class.not-in-class → 异步链（getPlayerClassId → listClasses 反查班名 → getClassMembers → getRankedPlayersForPeriodFiltered）→ 主线程 isOnline 复检 → ClassBoardGUI 纯渲染（零查库）。
- 管理族 `/lea class create/add/remove/list`（AdminCommandHandler.java:612+）：重名拒绝、换班先判新班满再动旧班（applyClassAdd `:712-748`）、阈值/权限/异步纪律齐备。
- 点击：ClassBoardListener 锚定前缀 + 尺寸双重确认；返回键 slot 8 → 主菜单；GUIUtils 白名单已补班级榜（开屏音效不丢）。
- 玩家可见：入班后 `/le class` 开出"班级榜 - X"，成员按赛季分降序，底部返回。链条唯一前提：赛季必须真的处于开启窗口（见第 6 步陷阱）。

### 8. 家长报告 — 贯通

- `/lea report <玩家> week`：逐子命令权限最小化（report 或 admin，AdminCommand.java:212-218；plugin.yml 命令级门已删防前置拦截）→ resolvePlayerUUID 三级查找 → 在线快照（与 /le stats 同源）→ ReportGenerator 全异步（8 路查询各持 connectionLock）→ 规则引擎"给家长的话"零 AI → UTF-8+BOM+CRLF TXT 落 `exports/reports/`（writeTextFile:648-655）→ 主线程摘要卡。`all` 批量上限 50 截断标注 + respect-privacy 开关。ExportResult 协议化已在案。

### 9. 决斗 — 贯通

- 主菜单【单词对决】slot 12（渲染 + 点击双侧同 gui-layout 键）→ 非局中开 DuelChallengeGUI（在线头像选择器，PDC 存目标 UUID，"点谁是谁"）→ 点头像 `sendDuelRequest`（忙态/AI/答题互斥在 manager 内复用）→ 对方 `/le duel accept` → 对局（倒计时 BossBar + tick 音）→ 打满 WIN_SCORE → `endDuel` → `DUEL_FIRST_WIN` tryUnlock（DuelManager.java:724，只认打满；退赛/掉线不走此方法——语义裁剪在案）。
- 标题避碰（挑战选择器标题不含"单词对决"子串 + 等值判定 + 尺寸双确认）与 `duel.enabled || inDuel` 放行语义均已核实。

### 10. 签到 — 贯通

- 主菜单【每日签到】slot 16 → `performCommand("le checkin")`（点击侧僵尸界面门在 MainMenuListener.java:174-179）→ CheckInManager.checkIn → `depositCappedReward`（纳入 money.daily-cap + 今日记账）→ success 文案 + correct 音 + 广场 CHECKIN 动态；economyDisabled 时补 checkin.economy-disabled 一行；断签有 streak-broken 提示。

### 11. 模块开关 — 断裂（"入口隐藏"层大面积落空 + 错题本完全无门）

逐模块核对代码（ConfigManager.java:283-383 的 11 门 + 全库调用点 grep）：

| 模块 | 命令/管理器门 | 事件静默 | 数据保留 | "入口隐藏"声明 | 实际 |
|---|---|---|---|---|---|
| word-system | 全 ✓（word/review/wrong 命令、三开局入口、复习任务体、挑战分配） | ✓（唯一例外见下） | ✓ | config.yml:83 声称"主菜单【单词学习】【错题本】隐藏；WordBankSelectGUI/WrongBookGUI 不可达；错题本练习（聊天态）禁开" | **假**：MainMenu 只对 duel/checkin 有渲染门（MainMenu.java:110-128），单词学习(slot 11)/错题本(slot 14)按钮照常渲染；两个 GUI 均可打开；**WrongBookGUI/WrongBookListener 全链零门**——左键可删词、Shift 可进练习并消费聊天输入（handlePractice WrongBookListener.java:144-169） |
| ai-chat | ✓（对话命令、auto-exit、startConversation/triggerAiOpening 纵深） | ✓ | ✓ | config.yml:169"主菜单【AI 对话】隐藏" | 半假：按钮照常渲染，点击被命令门拒（module.disabled）——功能安全但 UI 未隐藏；僵尸场景选择页点击会先收 started 文案、下一条消息才 auto-exit（软断点） |
| duel | ✓（含 quit/gui 放行语义） | ✓ | n/a | ✓ 渲染+点击双侧真隐藏 | ✓ |
| checkin | ✓ | ✓ | ✓ | ✓ 真隐藏 | ✓ |
| daily-challenges | 命令 ✓、领取侧 ✓（MiscGUIListener:190）、五入口 no-op ✓ | ✓ | ✓ | config.yml:509"主菜单【每日挑战】按钮隐藏" | 假：按钮照常渲染（MainMenu 无该门），DailyChallengeGUI 可打开列出既有行；任意点击回 module.disabled |
| leaderboard | ✓（命令、广场三榜按钮渲染+点击、tab 切换） | ✓ | ✓（updateScore 热备） | ✓ | ✓ |
| achievements | tryUnlock 门 ✓；个人主页成就墙按钮渲染门 ✓（ProfileGUI:198） | ✓ | ✓ | ProfileGUI 注释声称"点击侧（SocialListener）另有分支拒绝"（ProfileGUI.java:196-197） | 半假：SocialListener 成就墙点击分支无门（:425-433）——僵尸主页界面的 PAINTING 点击可开只读成就墙 |
| rewards | ✓（isMoneyEnabled 并集全收口） | ✓ | ✓ | n/a | ✓ |
| progress-system（伞） | ✓ 三子门 AND | ✓ | ✓ | ✓ | ✓ |

- 结论：命令层与事件层（后防线）质量高——关模块后玩家**做不了**任何新动作、数据不删、reload 方向往复。但**第一层"入口隐藏"只在 duel/checkin（Wave 2 新增按钮自带门）+ 广场三榜按钮 + 主页成就墙按钮上成立**；word/ai/daily-challenges 三个既有按钮的隐藏从未落到任何流：Wave 1 S-C/S-G 白名单不含 MainMenu/WrongBook，Wave 2 W2-B 只改两个新按钮——跨批次分工缝隙。最重的是 **WrongBook 的完全无门**：word-system 关闭后错题本练习仍可开、聊天输入仍被练习消费、可删词——这违反了该模块"事件静默"与"数据永不删"的对外承诺（后者由玩家手删触发，但模块关闭期间本不应可达）。
- 另：SV 级影响有限（默认全开），但 config.yml 注释与 CHANGELOG"统一三层语义"在 word/ai/daily 三项上**不诚实地超标声明**。

### 12. 个性设置 — 贯通

- `/le profile` → 设置页（slot 16 音效开关，ProfileGUI.java:306-320 渲染 + MiscGUIListener.java:91-104 点击切换）→ `setSoundsEnabled` + saveProfileSettings（sounds_enabled 列已随 S-A 落库）→ SoundManager.isPlayerSoundEnabled 读取（fail-open）。11/13/15 三隐私开关 + 返回栈（R-86b）均正常。3A-4 的"只有数据列无 UI 入口"漏项已在收尾期（8638046）修复——本轮复核确认当前 UI 入口存在且生效。

---

## 二、总评：Phase 3 可否称为"完整体验层"

**可以称为"完整体验层"，但带三个明确缺口。** 八域的玩家可感知主链全部真实贯通：新手引导从首进欢迎到 step3 毕业形成完整闭环且首成就确定性触发；AI 对话从 bye 到小结卡、AI 点评、历史详情回看是全链真接线（两段落库、时序、失败降级都经得起读码级推敲）；广场鼓励的"在线即时/离线摘要"双通道与赛季"抢占结算+台账幂等+付款失败回滚"这些跨批协作面在整个仓库范围内质量突出。短板集中在两类：一是"跨批次分工缝隙"——MC-2 宣称的 9 模块"入口隐藏"层因各波次文件白名单始终不覆盖 MainMenu 既有按钮与 WrongBook，导致 word/ai/daily-challenges 的隐藏层落空、错题本完全无门（这是 Phase 3 内唯一"关闭后仍可玩"的功能）；二是"已知留账未清"——F3-01/PF-SOC-06 的榜单直开点头像静默无响应被明示"记 Wave 3"后全部波次无人认领。另外赛季默认锚点在 2026-11-02（未来 17 天），开启即"静默休眠"，是验收与运营路径上的实际陷阱。总体评价：作为体验层它是成立且大部分诚实的，但 CHANGELOG/config 在模块开关一节的"统一入口隐藏"声明超出实际交付，属于最需要回写的部分。

---

## 三、三个最伤旅程的问题

1. **WrongBook 全链零门 + 主菜单四个既有按钮不隐藏（旅程 11）**——`WrongBookGUI.java` / `WrongBookListener.java:144-169`（零门）vs config.yml:83 的"隐藏/不可达/禁开"声明；MainMenu.java:110-128 仅 duel/checkin 有门。玩家实际看到：关闭 word-system 后主菜单"单词学习""错题本"按钮原样在列，错题本可打开、可删词、可进练习并让聊天输入被练习吞掉。这是"9 模块三层语义"旗舰声明下唯一的功能性破口。
2. **`/le leaderboard` 直开时首次点头像静默无反应（旅程 5）**——SocialListener.java:526-527（快照缺即 return）vs LearnCommand.java:427（直开不登记）。玩家实际看到：打开榜单点第 1 名头像毫无反应（无音效/无消息），先点任意 tab 后才正常。F3-01（dev-findings.md:164）与 PF-SOC-06（CHANGELOG.md:39 "记 Wave 3"）双重留账，Wave 3/4 全未清。
3. **赛季/班级的"开而不动"陷阱（旅程 6/7）**——SeasonManager.java:131-136 + config.yml:656/663。玩家/运营者实际看到：按清单把 `season.enabled: true` 打开，`/le season`、赛季 tab、PAPI、结算全部静默无反应、无日志无提示——因为出厂锚点 2026-11-02 尚未到达，全系统按"未开启"休眠。验收者极易误判为"功能未实现"。（class-system 依赖赛季，同样被拖住，但 `/le class` 至少会回一句 season.disabled，而赛季自身连一句话都没有。）

## 四、三个最亮的设计

1. **新手引导的确定性正反馈结构**：错答原地重试 + 强制 choice 4 选 1 + sessionCorrect≥1 判定 ⇒ "3 题全对、首成就必达"是结构保证而非概率；step 状态机单调写 + 老号 fail-open 回填 + PDC 手册防复制/防误开箱；引导 hook 放在会话结算最末尾保证叙事顺序。这是全项目"留存钩子"思路最完整的一处。
2. **广场鼓励的三态闭环**：乐观计数 + 唯一约束幂等 + 在线即时/离线 join 摘要（未发出不标已读）+ renderedFeedPages 打开时冻结根治"点 A 开 B" + 隐私四层同口径。跨玩家、跨在线状态的闭环在纯 Java 插件里做出了产品级完成度。
3. **AI 会话反馈的两段落库与消息时序**：local 行先落（崩溃可回看）→ AI 覆写经 future 串行（杜绝乱序覆盖）→ parse 永不抛异常 + 逐字段降级 + 硬上限；deliverEnd 主线程同步保证 ended → 卡 → return-menu 顺序。以及赛季结算的"条件 UPDATE 抢占 + 台账幂等 + 付款失败回滚 claimed + 无 Vault 保留 pending"——两处都把"失败路径"想在了前面。

## 五、不确定项

1. **环境日期假设**：第 6/7 步的"锚点在未来"结论依赖当前日期为 2026-10-16。若实际部署时已过 2026-11-02，陷阱自动消失（但 config 出厂值与部署日期的错位本身就是出厂资产的缺陷）。
2. **未实测**：所有线程时序结论（40L 双排队列顺序、deliverEnd 同 tick 消息序、ActionBar/BossBar 渲染）均来自读码推演，未在真实 Paper 客户端验证。
3. **僵尸 GUI 类软断点**：关 ai-chat 后点已打开的场景选择页，会先收到"dialogue started"三行、下一条消息才 auto-exit——理论窗口小，未实测其手感。
4. **tutorial 强制 choice 与 modes.choice.enabled=false 的冲突**：边缘配置下的行为未定义（引导会绕过模式门），未实测。
5. **PAPI isSelfView 语义**：跨玩家计分板场景（A 的板读 B 的占位符）恒按"非本人"收紧是文档化行为，未实测第三方插件调用形态。
6. **数据面**：未核 words/*.yml 是否满足 option-count≥2、choice 干扰项充足等前置（P2 已排过序/校验，按已绿采信）。

---
*验收方式：全部结论来自对真实代码路径的逐段阅读（关键处已引文件:行号）；对三个最怀疑声明做了代码级反证——① 9 模块"统一入口隐藏"（部分证伪，见第 11 步）、② 榜单/广场快照一致性（广场已修、榜单 F3-01 未修，证伪）、③ 赛季结算多服单发与失败回滚（证实）。*
