# P2 清理批次执行计划（甲+乙合并批）+ Phase 3 规划阶段

- 日期： 2026-10-12
- 基线： commit 15c6f39（P1 已提交，工作树干净），版本保持 1.0.7-SNAPSHOT（不 bump）
- 范围来源： tasks/review-phase2-total.md P2 段（43 条）+ tasks/dev-findings.md 积压（F/T0/F2R 系）
- 前置动作： 6 路只读核实 agent 已完成逐条现况核实（2026-10-12），下表中"裁决"列为核实结论
- 处置纪律： agent 只找 bug 上报不自己修范围外问题；编排者统一 fan-in i18n / CHANGELOG；每流文件锁互不重叠；并行期间禁止 mvn

---

## 一、核实结论（去伪存真）

### 已被 P0/P1 顺手修复（本批不再动，计入"已关闭"）

R-10（越界选项字母）、R-25（迟到答案反馈）、R-26（宽限任务泄漏）、R-27（发起侧忙态）、R-33（ORDER BY）、R-34/F1-01（发币走 depositCappedReward）、R-36（尾页回翻）、R-44（经济未启用提示）、R-45（新玩家欢迎）、R-55d（主菜单监听读配置）、R-55e/T0-01 部分（drag guard 已加 4 处）、R-56（单字母单词）、R-57（超时答案格式）、R-80（duel cancel 键 zh/en 对齐，全文件键级 diff 为空）、R-16/F1-05（开场白不计数）、R-21（存量 AI 任务降级）、R-86g（isOnline 复查）、T0-03（兜底重建过滤隐身）、F2-09（countdown-urgent 两侧齐备）、F4-02（场景尾槽 no-op）

### 现状比原报告严重（升级处理）

- **R-46/R-86i（升级为本批最高优先）**：`gui.menu-size` 配 9 或 18 通过校验，但 MainMenu 按钮槽位 10-18 固定 setItem → `ArrayIndexOutOfBoundsException` 开菜单即崩
- **R-69**：GM 解散后离线方重连**完全静默**（连泛用提示都收不到，disconnectTimers 被 cleanup 清掉）——原报告低估

### 现状比原报告轻（降级/关闭）

- R-62：坏文件懒加载最多 1 次/玩家/进程，"每次重读刷屏"已不成立；剩余问题=无大小/条数上限、无隔离
- F2-12：零 UUID 哨兵全库零消费点，隐患不成立（仅卫生项）
- F2-04：AnswerChecker 英文去重兜底已在，剩余为 equals 语义卫生
- F0-08/R-86p/R-86c：部分修复态（详见流表）

### 本批既定决策（不再回议，文档化备查）

- R-59：保留现行为（quit 计入 session 与分数），仅校正误导性注释
- R-71：不修（保留词优先级），文档化；名为 accept/quit/cancel 的玩家属极端边角
- R-86l：细粒度权限暂不接线（默认 true 接线无收益；Phase 3 模块开关方案一并处理）
- F1-22：重启丢今日计数保留（防刷上限非账本）
- R-77 / R-79(进度显示 clamp 除外) / F1-20 / F1-21 / F5-02(死路径) / F5-03 / F2R-01 / F2R-02 / F4-04：文档化不修
- R-82（决斗/签到菜单入口）：划给 Phase 3（随新手引导与菜单改版）
- R-85(b)(c)（whose level / 档内 level 散布）：内容调优暂缓，仅修 (a)(d)

---

## 二、流与文件锁表（8 流并行）

| 流 | 内容 | 白名单文件 | 规模 |
|----|------|-----------|------|
| S1 GUI 崩溃与音效 | menu-size 崩溃、音效去重、F6-01/02、R-86b、F2-05、R-66 答题入口 | gui/MainMenu.java、gui/ConfirmationGUI.java、gui/ScenarioSelectGUI.java、core/SoundManager.java、listener/MainMenuListener.java、listener/MiscGUIListener.java | M |
| S2 AI 健壮性 | R-61/86f、R-62、R-63、R-65、R-67、F4-01、F4-03、F1-08、R-66 AI 入口、开场白失败提示 | core/ai/AIChatManager.java、core/ai/TtsManager.java、listener/ChatScenarioListener.java、listener/ScenarioSelectListener.java | M |
| S3 决斗全套 | R-68、R-69、R-70、R-72(shutdown)、R-84 决斗音效、F2-06/07/08、F1-15、R-86n 传参、R-66 决斗入口 | core/duel/DuelManager.java、core/duel/DuelRoom.java、gui/DuelGUI.java、listener/DuelGUIListener.java、listener/DuelListener.java | M |
| S4 命令与配置校验 | R-86c、无词崩溃兜底、F2-13、R-86e 文案需求、R-86d、R-86h、R-86q、F5-01 配套、签到音效、R-66 答题入口、tab 补全 | command/LearnCommand.java、command/AdminCommand.java、core/manager/AdminCommandHandler.java、resources/plugin.yml | M |
| S5 生命周期与数据卫生 | R-64、T0-01 残留 drag guard、R-41、map 清理、R-76、F1-04、F2-01、F2-04、R-74 注释、R-79 LIMIT、join 提醒接线、学习超时音效、R-59 注释、F3-03 词库语义序 | listener/PlayerJoinListener.java、listener/WordLearningListener.java、listener/WrongBookListener.java、listener/ChatHistoryListener.java、data/PlayerDataStore.java、core/manager/CheckInManager.java、core/progress/RewardEngine.java、core/DailyChallengeManager.java、core/GuiNavigationManager.java、core/word/Word.java、core/word/WordLearningManager.java、core/word/WordManagerImpl.java | M |
| S6 词库内容 | 字母序恢复（R-83 系统性）、R-85(a)(d) | resources/words/*.yml、scripts/(可选脚本) | S |
| S7 GUI 硬化 | 裸 getString 机械收敛、DailyChallengeGUI NPE/clamp、ChatHistoryGUI menu-open 移除、MessageUtil javadoc | gui/ProfileGUI.java、gui/EnglishSquareGUI.java、gui/WrongBookGUI.java、gui/DailyChallengeGUI.java、gui/LeaderboardGUI.java、gui/ChallengeCompleteGUI.java、gui/WordBankSelectGUI.java、gui/ChatHistoryGUI.java、util/MessageUtil.java | M |
| S8 入口与配置 | R-75、R-86p、F0-08、T0-04、R-72 对接、午夜清理、R-86o、config 注释修 | LetsLearnEnglish.java、config/ConfigManager.java、resources/config.yml | S |

**跨流冻结接口**（并行安全关键，各流严格按此签名实现/消费）：

- `DuelManager.shutdown()`（S3 实现，S8 在 onDisable 调用，均含 null 防护）
- `DuelManager.consumeOfflineDuelNotice(UUID playerId)`（S3 实现，返回 `DuelManager.OfflineDuelNotice` record(messageKey, otherPlayerName) 或 null；S3 在 handleReconnect 消费、S5 在 onPlayerJoin 消费）
- `AIChatManager.importPersonalScenario(...)`（S2 实现，返回值改为 record `AIChatManager.ImportResult(String key, boolean updated, boolean saveFailed)`；S4 在 LearnCommand 调用点按新签名消费）
- `AIChatManager.hasActiveConversation(Player)`（已有，S2/S3/S4/S5 消费）
- `WordLearningManager.hasActiveQuiz(UUID)/isPracticingWord(UUID)`（已有，S2/S4 消费）
- `DuelManager.isInDuel(UUID)`（已有，S2/S5 消费）
- `GuiNavigationManager.clearPlayer(UUID)`（S5 实现并调用）
- `CheckInManager.unloadPlayer(UUID)`（S5 实现并调用）
- `RewardEngine.unloadPlayer(UUID)`（S5 实现并调用）
- S5 独占 PlayerJoinListener：R-41 清理、TTS 冷却清理、playerLocks 清理、join 每日提醒均在本流内闭环
- zh.yml / en.yml / CHANGELOG.md：**仅编排者**编辑；config.yml 仅 S8

---

## 三、i18n 冻结清单（编排者 fan-in 统一落 zh/en 双文件 + config-version 10→11）

新增键（写代码时直接引用，文案由编排者落盘）：

| key | zh | en | 用者 |
|-----|----|----|------|
| scenario.load-updated | 场景 '{name}' 已更新喵~ | Scenario '{name}' updated! | S2 |
| scenario.load-save-failed | 场景保存失败喵~ 请检查日志或联系管理员 | Failed to save scenario; check logs | S2 |
| scenario.limit-reached | 个人场景数量已达上限喵~（最多 {max} 个） | Personal scenario limit reached (max {max}). | S2 |
| dialogue.finish-quiz-first | 你正在答题中喵~ 请先完成或结束当前答题，再开始 AI 对话 | You're in a quiz. Finish it first before starting AI dialogue. | S2/S4 |
| dialogue.chat-active | 你正在与 AI 对话中喵~ 请先输入 bye 结束对话 | You're chatting with AI. Type bye to end it first. | S1/S4/S5 |
| dialogue.scenario-not-found | 场景 '{key}' 不存在喵~ 输入 /le dialogue 查看可用场景 | Scenario '{key}' not found. Use /le dialogue to list scenarios. | S4 |
| word.invalid-difficulty | 无效的难度喵~ 可用难度: {options} | Invalid difficulty. Available: {options} | S4 |
| word.no-words-available | 该难度暂时没有可学习的单词喵~ 换个难度试试吧 | No words available for this difficulty yet. | S4 |
| word.difficulty-beginner / intermediate / advanced | 初级 / 中级 / 高级 | Beginner / Intermediate / Advanced | S4 |
| word.mode-spelling / mode-choice / mode-fill_blank | 拼写 / 选择题 / 填空题 | Spelling / Choice / Fill-blank | S4 |
| admin.player-no-data | 未找到该玩家的数据喵~（可能已重置或从未加入） | No data found for that player. | S4 |
| admin.export-failed | 数据导出失败喵~ 请查看控制台日志 | Export failed; check console logs. | S4 |
| duel.sender-chatting | 你正在 AI 对话中喵~ 请先输入 bye 结束对话，再发起对决 | You're in an AI chat. Type bye first, then send the duel request. | S3 |
| duel.target-chatting | {player} 正在 AI 对话中，暂时无法接受挑战喵~ | {player} is chatting with AI and can't duel right now. | S3 |

修改现键：

- `duel.opponent-timeout`：硬编码 "30 秒 / 30s" → `{seconds}` 占位（S3 传 GRACE_PERIOD_SECONDS）
- `duel.usage` + help 的 duel 行：补 `/le duel cancel`（管理员）说明（S4 提供文案，编排者落盘）
- `scenario.load-success` 等既有键不动

编辑前先 grep 确认清单键不存在；若某键实际已存在则复用（以代码为准）。

---

## 四、协议与门禁

1. 8 流并行（mc-dev ×7 + general-purpose ×1），文件锁互斥；并行期间 **禁止 mvn / git**，编排者统一构建。
2. 各流范围外发现：只在报告列出，不改不修（用户铁律）。
3. 各流保留自主性：核实事实为准，实现方式自决；不可行项可跳过并说明。
4. 流返回后：编排者更新 dev-findings.md → 落 i18n 双文件 + config-version 11 + CHANGELOG → 3 路 tester（build/code/arch）→ 打回修复循环 → `mvn clean verify` → commit。
5. 出口标准：三 tester GO；`mvn clean verify` 26 测试全绿 + JaCoCo 门禁过；CHANGELOG 完整；无新增硬编码玩家可见文案。

---

## 五、Phase 3 规划阶段（P2 提交后启动）

8 路只读规划 agent（general-purpose），每路一个功能域，各写一份 `tasks/phase3/plan-<域>.md`（互不重叠），产出：操作逻辑（入口/状态机/边角）、用户体验（流程/文案/i18n key/config key 草案）、实现要点（触及文件/数据模型）、依赖与风险、验收标准。域划分：

1. 新手引导线（首进 3 步 + 引导书）2. AI 学习反馈闭环（结束总结/纠错/推荐词/历史详情）
3. 成就系统重构（质量型成就 + 难度前置）4. 广场社交最小循环（动态流/点赞/求助帖）
5. 赛季与班级 + 跨服榜 6. 老师/家长报告（周报 + 导出）
7. 资源包音频 + 反馈层（ActionBar/BossBar 倒计时/连击 pitch）
8. 菜单与入口收口（R-82 决斗签到入口、模块开关 R-55i、modes 接线决、个人场景管理 GUI、daily-round-limit 语义）

编排者合流为 `tasks/phase3/plan-master.md`（优先级 + 排期建议 + 待用户决策清单）后向用户汇报。

---

## 六、执行状态跟踪

- [x] 8 流并行开发（S1~S8）—— S1/S5 首实例中断，**重跑实例已启动**（S1 补 R-86b；S5 补 checkDailyStreak LIMIT，白名单扩 AchievementManager）
  - [x] S6 词库内容：done（三档全量重排 317/349/330 零丢失、book tome 删、leisure/idle 例句修；额外发现：dingle 疑生僻噪声、beginner 同义词超纲面大于 tome、README level 文档不一致——留 Phase 3）
  - [x] S4 命令与配置校验：done（difficulty 校验/no-words 兜底/标识符本地化/挑战名 NPE/补全收敛/场景校验与互斥/Admin 三连/plugin.yml/F5-01 配套/签到音效；已按 S2 的 ImportResult 4 组件消费）。S4 报告：`error.scenario-save-failed` 成死键候选；`getMaxPersonalScenarios()` 无公开 getter（{max} 用当前条数推断）
  - [x] S2 AI 健壮性：done（slug 哈希短码防中文名碰撞 / 同名幂等更新 + ImportResult(key,updated,saveFailed,limitReached) / 512KB·30 条上限与 .bak 备份 / max-context-pairs 接线 / TTS 魔数校验 / shutdown 在途守卫 / F4-01 懒加载 / F4-03 防御拷贝 / canSendChat 预检 / R-66 对话侧互斥）。S2 报告跨流缺口：PlayerJoinListener 调用的 CheckInManager/RewardEngine.unloadPlayer 属 S5 白名单，待 S5 落盘（已知冻结接口）；ScenarioSelectListener 答题互斥检查 S2 已落盘（98-104），S1 若重复需去重
  - [x] S3 决斗全套：done（PendingDuel token 防假过期 / OfflineDuelNotice 离线通知队列 + consumeOfflineDuelNotice / 头像 setOwningPlayer / shutdown() 幂等 / 音效对称 5 处 / F1-15 token 原子消费 / R-86n {seconds} 传参 / R-66 双方 chatting 检查 / 死方法 cancelDuel 删除）。S3 范围外：hasPendingRequest/getDuelRoom 零调用死 API、DisconnectInfo.disconnectTime 死字段、双离线 GM 无法解散（已知限制）——记录待 Phase 3
  - [x] S8 入口与配置：done（午夜自递归调度 / join 提醒接线 / onDisable 决斗清理对接 / check-updates 接线 / config 注释 / T0-04 / 审计 11 键）
  - [x] S7 GUI 硬化：done（8 GUI 收敛 145 处 getGuiMsg、131 唯一键双语零缺键；getChallengeName 防 NPE；进度展示 clamp；标题 NPE 面清零；MessageUtil javadoc 校正；ChallengeCompleteGUI 注释校正）。S7 范围外：LearnCommand:429 聊天侧 47/10 超量显示（编排者 fan-in 处理）、**音效政策缺口——S1 删全局 onInventoryOpen 后仅剩 4 处显式 menu-open，其余 13 GUI 开屏静音（编排者定策略）**、白名单外裸 getString 家族（记录）
  - [x] S1 GUI 崩溃与音效（重跑实例 done）：R-86b 已修复（SocialListener pushBack + MiscGUIListener popBack，深链两层栈验证通过）；全量验证 9 项已落盘点无破损（menu-open 全库仅 SoundManager:114 一处、全局 click 已删净、drag guard 白名单 5 类对齐等）。范围外：ProgressGUI 死类（已记录 P2-23）、ProgressGUI/ScenarioSelect 无权限分支无音（P2-24）、MainMenu 陈旧注释（编排者 fan-in 修）、config.yml scenario-select.back-button 死配置（记录）
  - [x] S5 生命周期与数据卫生（重跑实例 done）：唯一新增改动 = `AchievementManager.checkDailyStreak` 加 `LIMIT 40`（≥30 天判定窗口，无截断风险）；其余 15 项全部"已验证已落盘"。确认：drag guard 实为 8 listener 全覆盖（全库所有处理库存点击的 listener）；WordLearningListener 无 startQuiz 入口（任务 16 结论：无需守卫）；CheckInManager 无"无经济误报断签"（语义核实）。范围外：白名单内剩余 21 处裸 getString（记录，Phase 3 GUI 硬化二期候选）
  - [x] 编排者 fan-in 续（S1 中断残留 + S7 上报）：ChatHistoryGUI 两处 menu-open 显式播放移除（音效单源收口）；DuelGUIListener 退出键补 menu-click；ScenarioSelectListener 补 drag guard（T0-01 残留清零）;LearnCommand/MiscGUIListener 开答题前补 chat-active 守卫（R-66 反向对称）
- [x] 编排者 fan-in 先行项（跨流交叉、无主文件）：R-86m 答对广播按收件人语言渲染（ChatAnswerListener）、R-78 跨服广播模板缺键兜底（ProxyMessenger）、ChatAnswerListener 裸 getString 收敛 getGuiMsg
- [x] 编排者 i18n fan-in：zh/en 双文件 14 新键落盘（invalid-difficulty/no-words-available/difficulty-*/mode-*/finish-quiz-first/chat-active/scenario-not-found/load-updated/load-save-failed/limit-reached/sender-chatting/target-chatting/player-no-data/export-failed）+ duel.usage·help.duel·opponent-timeout{seconds} 改写 + config-version 10→11（ConfigManager.MESSAGE_CONFIG_VERSION 同步）
- [x] 编排者 fan-in（S7 上报项）：LearnCommand:438 聊天侧行为挑战进度 clamp（与 GUI 同口径，消除 47/10 显示）
- [x] 8 流全部回流（S1/S5 重跑实例补齐）
- [ ] 编排者 fan-in：CHANGELOG 落笔
- [x] 编排者 fan-in（S3 冻结接口补齐）：发现 `consumeOfflineDuelNotice` 入队无消费端（S5 重跑未接），已由编排者补在 `PlayerJoinListener.onPlayerJoin`（GM 解散离线方进服收 duel.cancelled-by-gm，读后即清）
- [x] 3 路 tester 全部回流：构建 **GO**（verify 全绿）/ 架构 **PASS**（2 低危已收口）/ 代码质量 **PASS**（四维度零违规：配置路径/i18n 双侧 508 键对齐/线程安全/资源清理；2 非阻断项已收口——config 注释校正 + acceptDuel R-66 对称守卫）
- [x] 编排者 fan-in（Code tester 反馈收口）：acceptDuel 补 AI 对话互斥（不消费邀请语义）、config.yml menu-size 注释校正——全树编译 EXIT=0
- [x] 最终门禁：`mvn clean verify` 全量重跑——BUILD SUCCESS / 26 测试全绿 / JaCoCo 过（覆盖全部 fan-in 补丁）
- [x] 提交：`716d4fd`（P2 批次，58 文件，+4896/-2960）
- [ ] Phase 3 规划 8 路 + 合流
  - [x] 域 4 广场社交：done（`tasks/phase3/plan-social.md`——动态流+cheer+回应闭环，2 新表/sqlite+mysql 兼容/异步快照防主线程查库/隐私四层延伸/互助成就单向接口，M 规模 5 story，15 条验收用例；规划期发现 5 条已记录）
  - [x] 域 7 音频反馈层：done（`tasks/phase3/plan-audio-feedback.md`——推荐"反馈层先做(3A)/资源包后置(3B 默认关待拍板)"；否决全自定义路线的硬证据=TTS 缓存 .wav 与资源包只吃 .ogg 冲突+枚举音效模型承载不了命名空间键；3A：倒计时改 ActionBar（复用现有定时器）、对决倒计时 30s 全静默重构为 BossBar 排空+三处清理、连击 pitch 公式 clamp(1.0+0.1×(n-1),0.5,1.6)+里程碑铃音、per-player 音效开关（player_profiles 新列槽 16 空闲）；API 已 javap 验证 1.21.4 废弃面；M 规模 11 story + 3B L 4 story；规划期发现 11 条）
  - [x] 域 2 AI 反馈闭环：done（`tasks/phase3/plan-ai-feedback.md`——混合两段式：本地小结卡同步秒出 + 达标会话异步 AI 结构化点评（JSON+salvage+全败降级）；纠错唯一来源=AI JSON corrections（否决正则扫描）；总结调用不计轮次/不烧冷却（独立 pendingSummaries 去重防 R-18 类坑）；新表 ai_chat_feedback UNIQUE(player_uuid,session_id)；前提=AIChatManager 会话状态收敛为 ChatSessionState + transcript 缓冲；M 偏重 900-1000 行 6 story 25 i18n 键；18 条验收；规划期发现 6 条含 PF-FB-05 该类 1273 行逼近维护极限）
  - [x] 域 6 家长报告：done（`tasks/phase3/plan-reports.md`——主读者=家长/出报告人=老师；`/lea report <玩家|all> [week|lastweek|<N>d]` + 新权限节点 letslearnenglish.report；周=周一锚点；报告零网络外发/TXT 强制中文/不对玩家隐私开关设限（权限即同意边界+可选 respect-privacy）；主线程零查库（全异步+Result record 协议化）；M 700-900 行 4 story；规划期发现 8 条——其中 PF-RPT-01/02/03 为**实伤**（正确率恒 100%/learning_log 零写入致统计恒 0/current_streak 零写入致 streak 占位符恒 0），已归档 dev-findings P3-01~09）
  - [x] 域 5 赛季跨服：done（`tasks/phase3/plan-season.md`——拆两批：A 跨服榜+赛季先行（赛季=时间窗口视图零数据重置，复用 getRankedPlayersForPeriod；唯一新表 seasons+season_rewards 台账；多服单发保护=条件 UPDATE 抢占结算权；段位明确不做）→ B 班级（收窄为 OP/老师管理型，玩家自建留域 4）；L 规模 5 story；规划期发现含 **PF-SEA-01 跨服消息通道接收端帧解析缺陷（中-高）**、PF-SEA-02 MySQL 建索引兼容、PF-SEA-03 player_sessions 缺日期索引、PF-SEA-04 learning_log 零写入、PF-SEA-06 硬编码时区）
  - [x] 域 1 新手引导：done（`tasks/phase3/plan-onboarding.md`——player_data.onboarding_step 四态状态机（老号回填 3 fail-open）；三项关键改造：迷你会话答错原地重试（使"3 题必全对→首成就确定性触发"成结构保证，修掉原案 choice 三连对仅 42% 的漏洞）/领成就改为打开个人主页/手册即唯一交互界面（规避 GUIUtils·拖拽·音效三套扩张）；引导书 WRITTEN_BOOK+PDC+三重补发；M 450-600 行 4 story；规划期发现 7 条）
  - [x] 域 8 菜单与入口收口：done（`tasks/phase3/plan-menu-config.md`——主菜单 7→9 按钮（空槽 12/16 加对决直按钮+DuelChallengeGUI 头像选择器/签到直按钮，规避 DuelGUI 标题子串误判陷阱）；9 模块开关统一"两层门+数据永不删"规范+边界矩阵；modes.* 7 子键逐一定义语义+max-attempts 两键删除+WordModes 配置真相源+QuizState 快照；PersonalScenarioGUI+PersonalScenarioStore 抽取；challenges-per-day/rewards 接线默认零变化；daily-round-limit 0/负=不限终案；P2-13 二期 25 处裸 getString（新发现 DuelManager 4 处含真 NPE 路径）；死键大扫除 ~28 i18n 键+ProgressGUI+achievements.yml 死链；L 规模 8 story 5 批次；11 条规划期发现 PF-MC-01~11）
  - [x] 域 3 成就重构（重跑实例 done）：`tasks/phase3/plan-achievements.md`——6 新成就精确判据（review_streak_7/wrong_book_cleared/accuracy_90 整数比较防浮点/duel_first_win 只认打满 5 分/helpful 依赖域 4 getCheerStats/study_days_30 累计制）；数据补充仅两列（player_sessions.review_words + player_data.wrong_book_cleared，均 addColumnIfMissing 零重建）；刷量前置收口 tryUnlock 内且已解锁玩家永不受前置回改；墙 22 格按 5 类分组+10 个可量化成就进度条（复活 achievement.progress 死键）；不做隐藏成就；F1-03 并入本域修；M+ 5 story；规划期发现 10 条（PF-ACH-02 异步线程实伤已即修、PF-ACH-01 假配置、PF-ACH-03 跨服广播语言）
  - [x] 编排者即修（PF-ACH-02 线程安全实伤）：bye 退出分支 AsyncPlayerChatEvent 异步线程直调 endConversation 链（可达 tryUnlock 通知段与每日挑战记分）→ 切主线程 + 句柄加"仍在场景模式"守卫防 1 tick 窗口重复结算；`mvn clean verify` 26 测试全绿
  - [x] 编排者即修（PF-MC-01 实伤）：行为挑战领取补 money.enabled 门（关币+vault 时此前仍实发金币，与签到/单词挑战 economy-disabled 语义不一致）——关闭时终态处理（标记已领+提示），编译 EXIT=0，CHANGELOG 已记
  - [x] 编排者即修（PF-ONB-01/PF-ONB-03 实伤）：FIRST_WORD 成就语义与描述对齐（原需"首场零失误"且与 PERFECT_SESSION 重复，改为至少答对 1 题）；MiscGUIListener 词库选择路径补标识符本地化（F2-13 漏网的新手主路径）；`mvn clean verify` 26 测试全绿 + JaCoCo 过
  - [x] 编排者即修（PF-SEA-01/02/03 实伤，规划 agent 上报）：跨服接收端按官方协议补帧长消费（原跳过帧长导致短消息带前缀、长消息截断丢失）；`createIndexIfMissing` 修正 MySQL 不支持 `CREATE INDEX IF NOT EXISTS`（查 information_schema 判存在再建）+ 补 player_sessions 两个防全扫索引；编译 EXIT=0，CHANGELOG 已记；跨服实测需双服（单服无法验证）
  - [x] 合流 `tasks/phase3/plan-master.md` 完成（八域一览/域间冻结接口 7 条/4 波施工顺序/待用户决策 D7-D14/即修实伤表/出口标准）→ 向用户汇报（本会话末尾消息）
  - [x] 提交 `fb9604b`：规划期即修 7 项实伤 + 8 域计划 + 总纲（18 文件，+4358/-36；verify 26 测试全绿）
