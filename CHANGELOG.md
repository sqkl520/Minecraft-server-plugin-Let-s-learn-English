# Changelog

## [1.0.7] - 2026-10-18

> 本版本包含多批次累积改动：v1.0.7 迭代开发（13 个 Story）+ Phase 0 冻结加固 + Phase 1 止血流 + Phase 2 复活流 + Phase 3 体验层（Wave 1/2/3A/3B/4 四波，8 域）+ 全项目只读扫描后的缺陷修复批次（中文服止血 / 并发收口 / 仓库卫生）。

### Added
- **[P3 Wave 3 Batch 3A / 域 5 赛季]** 赛季系统全链（默认关，运营属性）：`SeasonManager`（新类 730 行：anchor-date 纯函数窗口推导 + 抢占式结算（条件 UPDATE 唯一赢家）+ 首次启用不追既往 + catch-up 游标 + 5 分钟热备缓存 + 跨服 origin 协议分发与回环去重）；2 新表 `seasons`（结算台账头 + top_snapshot 回顾）+ `season_rewards`（发放台账，rank_position 避保留字；**付款失败回滚 claimed 供重试**，W3A tester M1 收口）；分层奖励 top1/top3/top10/参与奖（config 可配，直发不经 daily-cap、台账幂等）；结算广播 + 在线即时发放 + 离线 join 补发（`claimPendingRewards`）；**无 Vault 时台账保留 pending 不销毁**（修正 plan 字面缺陷）；午夜跨季零 IO 检测（日期纯比较）+ 启动 catch-up 挂载
- **[P3 Wave 3 Batch 3A / 赛季展示]** 榜单第 4 tab【赛季】（TAB_SEASON_SLOT=3 + 信息牌 slot 4，既有三 tab 零挪位；可用性门渲染/点击双侧同源）+ `%lle_season%` / `%lle_season_days_left%` / `%lle_season_rank%` 三 PAPI 占位符（真 gating 空串 + R-43 缓存 + 隐私同口径）+ `/le season` 命令（赛季号/剩余/本人名次/top10 快览，零查库）
- **[P3 Wave 3 Batch 3A / MC-4 个人场景管理]** `PersonalScenarioStore` 抽取（~330 行：个人场景文件 IO 全迁，AIChatManager 保留零改动门面——P2-28 兑现）+ `PersonalScenarioGUI` 管理页（54 格：计数/条目列表/prompt 摘要 lore/**行内二确删除（条目 PDC 标记——"屏幕即真相"无状态残留）**/空态）+ 场景选择页 slot 24 入口（import 权限可见门）+ `DeleteResult` 三态反馈
- **[P3 Wave 1 / 域 1 新手引导]** 新手引导线全链：`OnboardingManager`（新类，状态机 0/1/2/3 + 老号 fail-open 回填 3 + 首进发《新手引导手册》WRITTEN_BOOK+PDC + 每日去重提醒 + PDC 扫描去重发放 + Adventure BookMeta 三页现渲染）；迷你会话（`startTutorialQuiz` + 错答**原地重试**——不清连击不扣分不进错题本不推进题号，choice 4 选 1 必中使"3 题全对→首成就确定性触发"成结构保证）；完成登记（step2 由会话完成钩子、step3 由本人主页打开钩子，顺序刚性不可跳步）；`/le guide` 命令；D2 首进当天抑制 join 每日提醒
- **[P3 Wave 2 / 域 4 广场社交]** 学习动态流全链：`SocialManager`（新类 487 行）+ `FeedTextFormatter`（纯函数文案组装）；2 新表 `social_feed`（UNIQUE(player_uuid,feed_date,event_type) UPSERT 合并防刷屏，3 行/人/日封顶）+ `social_cheers`（UNIQUE(feed_id,cheerer_uuid) 一人一条一次）；广场新增"动态"模式（第 5 模式，BELL 图标 slot 6，默认落点 `english-square.default-mode`）+ 左键鼓励/右键主页/自条目看鼓励名单；被鼓励者在线即时提醒 + 离线进服摘要（异步查+主线程发+异步标已读）；写全异步/广场打开零查库（volatile 快照 200 条内存分页）/唯一同步=28-id 有界已鼓励集合；隐私四层同口径（产生门/ SQL JOIN 过滤/点击守卫/getProfileSettingsReadOnly 防缓存污染）；混服按 viewer 语言渲染；鼓励零经济收益（杜绝刷币旁路）+ 750ms 防抖；helpful 成就接线（distinctTargets≥10，异步派发守线程契约）；`deletePlayerData` 顺序敏感三循环防 reset 残行
- **[P3 Wave 2 / MC-1 主菜单与对决选择器]** 主菜单 7→9 按钮（空槽 12 增【单词对决】DIAMOND_SWORD + 16 增【每日签到】SUNFLOWER，menu-size 维持 27、19 槽预留域 1、语言切换不动）；`DuelChallengeGUI`（新，在线玩家头像选择器——纯 GUI 玩家全流程闭环 R-82：点头像即发邀请，忙态/AI 对话/答题互斥全部由 sendDuelRequest 既有守卫反馈；**标题避碰实测 16 项断言**——不含"单词对决"子串防 DuelGUI 前缀比对误判 + 等值判定防反向误判，含对抗性证明）；对决按钮在局中重开当前界面（`duel.enabled || isInDuel` 放行语义与命令侧一致）；签到按钮直走 /le checkin；两新按钮同走 gui-layout 配置 + 模块门双侧
- **[P3 Wave 2 / MC-3 modes 接线]** `WordModes`（新类配置唯一真相源）：三模式 enabled 门（/le word 三分支拒绝语义：未知→invalid-mode / 已知关闭→mode-disabled / 默认→firstEnabledMode，全关→no-modes-enabled）+ case-sensitive（仅全词文本比较，choice 字母选择恒不敏感）+ option-count（钳 [2,4]）+ choice.time-limit（0=跟随全局——语义修正，默认值 30→0）+ fill_blank.show-first-letter（展示层短路）；**命名定案 fill_blank 下划线 + 读取侧双兼容旧连字符名**；`AnswerChecker` 参数化重载（四旧签名全保留委托、行为逐位兼容）；`QuizState` 三 final 快照字段（同会话规则恒定）；删 max-attempts 死键 ×2；PF-MC-11 标识符本地化三处收敛；默认模式解析**优先 choice**（fan-in 裁决修复 plan 内部矛盾，恢复 FEAT-MODE-01 新手默认语义）
- **[P3 Wave 2 / MC-5 挑战经济参数]** `challenges-per-day` 真接线（钳 [1,4] + 从可用集合直接取 N——整链删除 R-74 的 5 轮重抽与 24% 兜底路径）；`rewards.*` 四键真接线（`getChallengeReward` 公开 API，缺键/非法/≤0/NaN 回落枚举默认——整数装箱经 `instanceof Number` 判定防 `isDouble` 误判）；F1-12 槽位泛化 `CHALLENGE_SLOTS={11,13,15,17}` + binarySearch 查位映射（N=3 渲染/领取逐位同现状）；默认配置零行为变化 + ConfigManager 审计 4 键数值正值
- **[P3 Wave 2 / 域 6 家长报告]** `/lea report <玩家|all> [week|lastweek|<N>d]` + 新权限节点 `letslearnenglish.report`（默认 op；AdminCommand 权限最小化改造为逐子命令判定——老师只发 report 权限不泄漏 reset/reload，配套删 plugin.yml 命令级权限门防 Bukkit 前置拦截）；`ReportGenerator`（新类 1130 行）：周一锚点周期纯函数 + 8 路异步查询（每查询自持 connectionLock）+ 规则引擎"给家长的话"（零 AI 调用）+ UTF-8+BOM+CRLF TXT 落 exports/reports/ + 惰性清理 + `ReportSettings` 主线程快照（异步渲染零 ConfigManager 访问）；批量 all=全服对比表（上限 50 截断标注 + respect-privacy 过滤开关）；`DataExporter.ExportResult` record 协议化（消 "Export failed" 字符串硬耦合，P2-09 闭环）
- **[P3 Wave 1 / 域 3 成就重构]** 6 个质量型新成就：`review_streak_7`（连续 7 天复习且每天答对≥1）/`wrong_book_cleared`（练习移出 5 词且清空错题本，手动删除不计数）/`accuracy_90`（≥100 题样本 + 整数比较防浮点边界）/`duel_first_win`（只认打满 5 分的 endDuel，退赛掉线不算）/`helpful`（鼓励 ≥10 个不同玩家，暂无 SocialManager 时锁定）/`study_days_30`（累计 30 个不同学习日）；刷量成就难度前置（sessions_50←streak_10、words_500←100 题+70%、coins_500←50 题、coins_5000←words_100，收口 tryUnlock 内且已解锁者永不受回改影响）；成就墙 22 格按 5 类稳定排序分组渲染 + 类别/解锁日期/进度/前置四新 lore 行（复活 `achievement.progress` 死键）+ 标题计数；`%lle_achievement_<id>%` PAPI 动态位；解锁瞬间新增 Adventure Title 展示；`achievement-system.unlock-sound` 真接线（playSoundByName）；`getProgressSnapshot` 进度快照；删死方法 `checkCoinAchievements`
- **[P3 Wave 1 / 域 7 音频反馈 3A]** 学习倒计时改 ActionBar（不再刷聊天栏——原 30s 题刷 30 行、10 题会话约 300 行）；最后 3 秒每秒 tick 音随剩余秒升调；urgent 阈值可配；连击音高公式 `clamp(1.0+0.1×(n-1), 0.5, 1.6)` + 里程碑（3/5/7/10）ActionBar 闪光 + 铃音替代升调（不叠音）；对决每题倒计时从 30 秒**全静默**重构为 BossBar 排空条（最后 5s 变红 + tick 音，清理挂钩 cleanup/shutdown/reconnect 三处 + 三条自清理路径 + 双参 remove 防误摘新任务）；per-player 音效开关（`player_profiles.sounds_enabled` + 设置 GUI 挂 PrivacySettings）；`gui.sounds` 三新键（combo-milestone/countdown-tick/timeout）+ "none/空串=故意静音"语义；P2-24 音效盲点三处收口
- **[P3 Wave 1 / MC-2 模块开关]** 9 个模块级 enabled 开关**真接线**（word-system/ai-chat/duel[新]/checkin[新]/daily-challenges/progress-system 伞/leaderboard/achievements/rewards）：统一"入口隐藏 + 事件静默 + 数据永不删"三层语义；ConfigManager 11 个点读门方法（伞语义 AND 组合，reload 即时生效）；config.yml 三层语义注释 + duel/checkin 新段；`module.disabled` 统一回显；ai-chat 关闭时进行中对话下一条消息自动退出（不吞消息）；`/le duel quit/gui` 在关开关后进行中对局仍可用；`RewardEngine.isMoneyEnabled` 收口为公开并集判定（rewards 伞 && money 键）
- **[P3 Wave 1 / 数据层]** 四新列（`player_data.onboarding_step` / `player_sessions.review_words`（recordSession 内派生累加）/ `player_data.wrong_book_cleared`（R-28 pattern 绝对值 UPSERT）/ `player_profiles.sounds_enabled`），全部 `addColumnIfMissing` 老库零迁移，schema.sql 同步
- **[P3 Wave 1 / 跨服广播]** 成就解锁广播双语 payload + 逐收件人按语言渲染（R-86m 口径），兼容旧 3 段格式；顺带修复旧实现把字面 `{prefix}` 广播出去的缺陷
- **[P3 Wave 3 Batch 3B / US-FB AI 反馈闭环]** 会话级 AI 对话反馈全链：`ChatSessionState` 五 map 收敛单 map + transcript 缓冲 + `SessionSummary`/`TranscriptLine` record（`endConversation`/`endConversationOnQuit` 返回快照，无会话返回 null）；`requestConversationSummary` 独立总结通道（temperature 0.3 + 独立超时 `ai-chat.feedback.ai-timeout-seconds` + 独立去重 `pendingSummaries`；豁免冷却/每日轮次/`pendingRequests`）+ `canRequestConversationSummary` 只读预检（占位不收回防护）；`ConversationFeedback` 防御式解析（围栏 salvage + 逐字段降级 + 硬上限 comment≤500/corrections≤5/words≤8 + 控制字符与 § 清洗，永不抛异常）；新表 `ai_chat_feedback`（UNIQUE(player_uuid,session_id)，两段落库：达标即落 `source=local` 本地统计行 → AI 点评到达 UPSERT 覆写 `source=ai`+payload JSON，中断也能回看）；`ChatFeedbackManager` 显示编排（小结卡 stats/counts/passed 或 not-passed/empty 三态 + AI 点评块 ≤3 纠错 + 推荐词 + ai-failed 降级）；`/le history` 详情页反馈物品（FEEDBACK_SLOT=49 只读 WRITTEN_BOOK：场景行 + 统计行 + 全量纠错含 note + 推荐词；坏 JSON 降级"无 AI 点评"）；quit 路径只落本地行（人走不请 AI）；`ai-chat.feedback.*` 六键 config 块（enabled/ai-summary/ai-timeout-seconds/ai-max-tokens/transcript-max-chars/max-corrections-display）；`dialogue.feedback.*` 16 键 + `chat-history.feedback-*` 10 键双语落盘（695=695 对称）
- **[P3 Wave 4 Batch 4A / US-CLS-1+2 班级系统]** 管理型班级全链（默认关，运营属性）：`classes`（班名 UNIQUE 业务键 + created_by/created_at）+ `class_members`（PK(class_id,uuid) + uuid 反查索引）两新表（schema.sql 双处 DDL 一致）；DatabaseManager 九冻结方法（createClass/deleteClass/getClassByName/listClasses/getClassMembers 含 player_data JOIN 取名/getPlayerClassId/addClassMember/removeClassMember/getClassMemberCount + ClassRow/ClassMemberRow record）；`/lea class create|delete|add|remove|list` 管理族（`class-system.enabled` 门 / 班名校验 / 重名拒绝 / **换班防丢班顺序**（先判新班满再动旧班）/ list 全量含隐身穿透 + 赛季缓存分数反查零查库）；`PlayerDataStore.getRankedPlayersForPeriodFiltered`（动态 IN 占位符 + 空集合早退 + 保留 show_in_square 过滤）；`ClassBoardGUI`（54 格班级榜：成员头像按赛季分降序 45 格 + 空态 + 返回键；**零查库**——命令侧异步链拉数据，GUI 纯渲染）；`ClassBoardListener`（锚定前缀标题判定 startsWith 非 contains + drag guard）；`/le class` 玩家入口（模块门 → 赛季可用性门 → 未入班提示 → 异步拉成员+分数 → 主线程开 GUI）+ help.class 帮助行；`class-system` config 段（enabled:false / max-name-length:32 / max-members:50）；`class.*` 18 键 + help.class 双语（716=716 对称）；`MESSAGE_CONFIG_VERSION` 13→14；`GUIUtils.isPluginGUI` 补班级榜白名单（ClassBoardGUI 音效识别）

### Changed
- **[文档对账]** 全仓库文档与代码事实对齐（仅改文档）：MySQL 驱动内置说明（README / DEPLOYMENT_WIKI 原教配 MySQL 但未说明驱动来源，并补"连接失败 SEVERE + 禁用"行为）、热重载覆盖度（wiki 原称"全部配置即时生效"，现列 `general.auto-save-interval` / `general.backup.*` / `daily-reminder.reminder-hours` / `tts.enabled|cache-days|voicerss-api-key` / `progress-system.leaderboard.update-interval` 五个需重启键，并修正 `update-interval` 死键误标）、发音表述（原称"音频已下载缓存、客户端播放需资源包"，实为音符盒提示音、`.wav` 从不播放）、许可证（MIT→GPL-3.0-only，与仓库 LICENSE 一致，补 `words/README.md` 引用声明）、版本矩阵（Spigot 1.21+→Paper 1.21+，附 Paper 专属 API 依据）、构建指令（`mvn package -DskipTests`→`mvn verify`）
- **[P3 Wave 4 Batch 4A / 收口]** 三维 tester（构建 GO / 架构 PASS / 代码 GO）低项收口 4 项：①`LearnCommand` 异步→主线程开班级榜 GUI 补 `player.isOnline()` 复检（同 SocialListener/SeasonManager 既有守卫姿态）；②`DatabaseManager.deleteClass` 查名+删行收进同锁（消除共享 MySQL 双服并发删 TOCTOU；班级行 DELETE 影响 0 行报 false 不虚报成功）；③`AdminCommand` tab 补全补 class 动作级与 add/remove 玩家名候选（班名候选需查库，tab 补全保持零 DB 访问不补）；④`class-system.enabled` 入启动诊断名单。另记录 2 不确定项（max-members 满员判定仅本 JVM 串行化，共享 MySQL 双服抢末位理论可超额——管理型班级低频操作可接受；GUI 隐身过滤 vs 管理路径全量的口径差异为有意设计，plan §1.5 明示）
- **[P3 Wave 3 Batch 3B / 收口]** 三维 tester 收口 2 中 4 低：①`ChatScenarioListener.getMessageLine` 收敛 `getPlayerMessageSafe`——修复 `dialogue.ended`/`return-menu` 字面 `{prefix}` 泄露（3A M2 改用 `getGuiMsg` 时遗留的存量伤，同屏与小结卡不一致）；②两段落库乱序窗口根治——AI 覆写经 `persistAfter`（thenRunAsync 链）严格排本地行之后，杜绝极端调度下 `source=ai` 被 `source=local` 覆写丢点评；③`MESSAGE_CONFIG_VERSION` 12→13（3B 新增 26 键，v12 存量服不升版本会收不到新键）；④`deliverEnd` 主线程时同步执行——修 `ended → return-menu → 卡片` 时序错位（plan §3.6/AC-1 为 `ended → 卡片 → 占位 → return-menu`）；⑤`ChatHistoryGUI` 反馈物品改复用 `ConversationFeedback.parse` 单一 JSON 契约（消第二份内联解析；部分字段异常不再渲染出"点评+无点评"自相矛盾 lore）；⑥Gson 提 static final 复用 + FQN 内联清理
- **[P3 Wave 3 Batch 3B]** `ChatScenarioListener.removePlayer` 与 `handleEndConversation` 合一（PF-FB-01：命令退出与聊天 bye 同流程，`LearnCommand` 不再补发重复 ended 文案）；`AIChatManager` 行数 1155→1506（W3A tester ② 项：失降反增，属总结通道+record 净增，记录在案）
- **[P3 Wave 3 Batch 3A]** P2-13 二期裸 getString 收尾：25 处全收敛 `getGuiMsg`（含 DuelManager 的 `duel.accept-hint` 真实崩溃路径——1.21.4 实测抛 IllegalArgumentException 非 NPE）；PF-MC-11 标识符本地化收敛为 `WordModes` 单一实现（第四份副本清除）
- **[P3 Wave 3 Batch 3A]** tester 收口：`seasonDaysLeft`/`isSeasonAvailable` 上收 SeasonManager 单一真相源（原 GUI/PAPI 双份复制 → `daysLeft()`/`isAvailable()`，L4）；赛季 tab 点击门序对齐（榜单模块门优先，L7）；`getRawMessage` 死方法删除（L2）；`ChatScenarioListener.getMessageLine` 硬编码英文兜底收敛 getGuiMsg（M2）
- **[P3 Wave 2 / MC-3 升级注意]** `modes.choice.time-limit` 出厂默认 30→0（0=跟随全局）；存量 config.yml 若曾改过该键，接线后立即生效（CHANGELOG 明示改 0 以跟随全局）
- **[P3 Wave 2]** `MESSAGE_CONFIG_VERSION` 12（维持）：Wave 2 新增 report 62 键 / square.feed 23 键 / main-menu 4 键 / word.mode-disabled 等，经键级对称校验 638=638 零差异；占位符逐键对照代码调用点校正 16 处；重复顶级键清理
- **[P3 Wave 1 / F1-19 终案]** `ai-chat.daily-round-limit` 0/负数 = **不限**（原语义为拒绝全部）；想禁用 AI 对话改走 `ai-chat.enabled: false`（config 注释 + CHANGELOG 明示）
- **[P3 Wave 1]** `MESSAGE_CONFIG_VERSION` 11→12：zh/en 双语落盘约 45 新键（onboarding 26 / module.disabled / ai-chat.module-disabled / duel.round-bossbar×2 / combo-milestone / achievement-wall 7 / achievement.prereq-hint / help.guide 等），键级对称校验 548=548 零差异

### Fixed
- **[P1 有效性与手感]** 六条修复（6 施工流并行 + 编排者 fan-in + 1 路独立红队 GO-with-fixes）：①**P1-A 判分信号加固**——`buildChoiceOptions` 改 `getAllWords()` 本地分桶，干扰项三重约束（排除同释义〔按 `;；/／` 切义项段 + 剔括号注释〕、同词性优先、词长相近优先，六桶 + 软/硬兜底保证选项数契约不退让）；`normalizeInput`（NFKC + 去零宽 + 空白折叠）接入全部判分入口，全角/零宽/多余空格不再误判；新增 `isFormatOnlyMismatch` 供上层归因格式问题；②**P1-B 纠错回闭环**——AI 会话点评的 `corrections` 单词级词条经 `incrementWrongCount` 计数（第 3 次入错题本，与 `trackWrongAnswer` 同阈值）+ `seedReview` 排复习；推荐词只入复习不进错题本；同场 `Set` 去重；`getSuggestedFocusWords` 备好下场目标词（AIChatManager 注入点待接）；③**P1-C AI 按钮灰态**——`api-key` 未配置时主菜单 AI 按钮渲染 `GRAY_DYE` + 复用 `dialogue.api-not-configured` 状态行，点击侧补 `isAvailable()` 兜底；④**P1-D 选择题点击作答**——选项渲染为 4 个独立槽位（PDC 存选项下标 + 渲染时题号，点击侧不读槽位号故永不漂移），点击即 `submitAnswer` 提交，`WordLearningGUI.refresh` 就地重画不触发 `InventoryCloseEvent`，300ms 节流 + 跨题校验防误答；结果呈现抽为 `presentAnswerResult` 供聊天/GUI 两通道共用；`cancel` 教学词与退出指令解冲突（练到该词时不再被当退出）；⑤**P1-E 玩家提醒开关**——`ProfileSettings.remindersEnabled` + `player_profiles.reminders_enabled`（CREATE TABLE 与 addColumnIfMissing 双侧 + schema.sql 同步）+ 个人设置页 slot 14 开关；四处发送门接线（定时广播/进服提醒/复习提醒/新手引导提醒，均置于去重位写入之前，关闭者不占当日额度）；⑥**P1-F 词库补种**——beginner 317→347 词，补齐 NGSL 高频功能词（the/a/be/have/do/will/if/can/think/see/only/more/than/up/out/over/such/then 等 24+），冠词等给用法说明而非裸译；`words/README.md` 诚实标注"参考 NGSL/GSL/课标但未逐词校验词频、difficulty_level 运行时未消费"；新增 `scripts/check-word-collisions.js` 撞车扫描（与 Java 侧归一化同口径，42 对：硬 16 / 软 26）。**红队收口**：单字母词条 `a` 与 choice 字母标签歧义（chat 通道敲 A 白拿分）→ `AnswerChecker.check` 统一"单字母输入优先按选项字母解读"；`the` 的 `/` 并列释义漏判撞车（与 this/that 同现率 26%）→ 分隔符增补 `/／`；AI 复习种子只写内存不落库（重启即失）→ `seedReview` 补 `masteryMap.putIfAbsent(0.0)` 让其随 UPSERT 落库；脚本漏报 2 对硬撞车 → 补括号剔除与分隔符对齐；引导提醒漏接总开关 → 补第四处门（6 施工流并行 + 编排者复核补漏）：①**P0-1 MySQL 驱动**——`pom.xml` 增 `maven-shade-plugin` 3.5.1，`artifactSet` 白名单只 shade `com.mysql:mysql-connector-j`（不 relocate，保住 `META-INF/services/java.sql.Driver`；排除签名/INDEX.LIST/module-info，`ServicesResourceTransformer` 合并服务文件），`finalName` 不变；`initializeMySQL` 补显式 `Class.forName`，驱动缺失时抛带排查指引的 SQLException（原症状为晦涩的 `No suitable driver`）；pom 补 `<licenses>GPL-3.0-only</licenses>` 与仓库 LICENSE 对齐；②**P0-2 语言文件合并式升级**——`loadMessageConfig` 不再 `saveResource(path,true)` 硬覆盖：JAR 内新版经 `getResource`+UTF-8 Reader 读入内存（不落盘），递归合并保留用户全部取值、补齐缺失键、保留废弃键、类型冲突以新版结构为准（旧值在 `.bak`），写回走 `.tmp`+`ATOMIC_MOVE` 原子替换，YAML 损坏时只读兜底不覆盖，任一步失败降级为"原文件不动+旧内容继续用"；③**P0-3 AI 热重载**——`HotReloadManager.reloadAll()` 在 `reloadAllConfigs()` 之后调用 `AIChatManager.reload()`（异常只 WARNING 不阻断），改 `ai_config.yml` 的 api-key 后 `/lea reload` 真正生效；④**P0-4 开场白资损**——`dispatchChat` 的 `boolean countsTowardUsage` 改为 `ChatTrigger` 枚举，每日轮次检查去掉条件外壳（对话与开场白请求都过 `daily-round-limit`；会话 AI 总结为独立通道、不占此额度）；开场白发请求前**预扣**落库轮次（堵住"点场景 A→立刻切 B→A 响应因 sameSession=false 永不计数"的绕过路径），**未到达上游的失败（超时/连接失败/5xx/429）自动退还**（红队 R-P1-1：防上游持续故障时耗尽当天额度锁死玩家；4xx 不退，上游已受理计费）+ 独立 `lastOpeningTime` 5 秒防连点 + 内存 `openingCounters` 每日上限（新键 `ai-chat.opening-daily-cap` 默认 30，≤0 不限）且 quit 只清跨日条目（防重登重置额度）；`loadScenariosConfig` 改"解析到临时表、成功且非空才整体替换"（红队 P2-1：场景 yml 语法错时不再静默清空自定义场景，改 SEVERE + 保留旧表）；⑤**P0-5 主学习界面空槽**——`WordLearningGUI`/`ClassBoardGUI` 补 `GUIUtils.fillEmpty`（含 state==null 早退路径），两 listener 照 DuelGUIListener 补下半区 `MOVE_TO_OTHER_INVENTORY` + `COLLECT_TO_CURSOR` cancel（红队 R-P3-1 补双击收集面；置于越界 return 之前，DuelGUIListener 同族同步），与已有 drag guard 形成双保险；⑥**P0-6 文档对账**——发音表述改为"音标显示+提示音，不播放真实语音"（含出厂 config.yml 注释）、MySQL 驱动内置与连接失败后果（补"确认 shade 执行过/用官方 JAR"兜底，取代无效的"放入 plugins/ 目录"建议）、reload 覆盖度与 6 个启动快照键清单（含 `general.check-updates` 与 `word-system.review.intervals` / `database.*` 两组后补项）、Paper-only 版本矩阵及依据、构建指令 `mvn verify`、许可证统一 GPL-3.0-only、新建 `THIRD-PARTY-NOTICES.md`（MySQL Connector/J 许可声明）并随包释放（红队 R-P2-6：shade 排除驱动自带 LICENSE/README，改由项目文件承担声明）、语言文件合并式升级语义（wiki 5.4/5.7/§7 同步改写）；发布注意：shade 产物为 `target/` 内**不带 `original-` 前缀**的 JAR（发布勿拷 `original-*.jar`，红队 R-P2-4）
- **[全仓审查 Block D]** 低危 28 条终局清扫（4 施工流并行 + 2 路红队验证）：①**D1 数据安全**——cleanupOldChatHistory 三语句参数化 / escapeCsv 补 \r / addColumnIfMissing 改元数据查询（PRAGMA table_info + information_schema，消除非英文 locale 误判）/ countUnclaimedForDate 锁内取连接 / getGuiMaterial Locale.ROOT / 统计服务默认弱口令 WARNING + MessageDigest.isEqual 常数时间比较 / isPlayerLearning 真接线（startSession/startReviewSession/endSession 三点）/ 删 getChatHistory+clearWrongCount+resetCheckInStreak+saveDifficultWords 死方法；②**D2 死代码与卫生**——删 17 个零引用方法（含 VersionAdapter×4/UpdateChecker×2/AnswerChecker.isSpelling 等）+ addWord/removeWord / ChatFeedbackManager UPSERT 去重（writeTask 工厂）/ TtsManager 401/403 改 5 分钟熔断（不再永久置 null，到期自动探测）/ ChatHistoryGUI lore 折行防拆代理对 / applyClassAdd 锁外发送 / resetPlayerData 刷榜单缓存 / 新建 util/SchedulerGuard（SocialManager+SeasonManager 薄委托迁移，4 处注释指向）+ activePlayers 漂移契约注释；③**D3 社交 GUI**——榜单头像点击改只读设置 / getPlayerRank 并列稳定排序 / SPELLING_MASTER 描述补"至少拼对 1 题" / 自定义词库查键先原样 / openHidden 标题补 {page} / MiscGUIListener 判定改配置等值+占位符前缀锚定（5 类标题，含 viewing-title 变体）/ MainMenu 槽位越界守卫+同槽告警 / quit 清理 confirmStages；④**D4 命令配置**——/lea reset 两段式确认（admin.reset-confirm）/ /le dialogue 命令路径补 AI 可用性预检 / admin-help 补 class 行（12 项对齐）/ config.yml season 节修改警示注释 / audit-i18n-keys.js 补 season/class 且跳过拼接键（achievement-wall.category- 假阳性消除，audit 全绿）。i18n：新 2 键（admin.reset-confirm/admin-help.class）+ accept-hint 改值（删"点击这里"不可点承诺），config-version 16→17
- **[全仓审查 Block C2]** 数据/逻辑中危 23 条（6 施工流并行 + 3 路红队验证）：①**R2-M2 AI 轮次持久化**——新表 `ai_round_counters`（UNIQUE(player_uuid, round_date)）承载每日 AI 对话轮次，increment 内存即时+调度器异步落库、读取 miss 时 DB 回读 seed（daily-round-limit 重启/换服绕过消除；createTables+schema.sql+deletePlayerData+保留期清理四同步）；②**R3-M1** 删 getWordMastery 死方法（CHM 递归反模式消除）；③**R3-M2** decrementWrongCount 改原子 SQL（`WHERE wrong_count > 1` 返回 false 即删行），读回分裂窗口消除；④**R3-M3** updateWordProgress 补全 lastReviewTime/reviewCount 两 map（savePlayerData 覆盖窗口封死）；⑤**R3-M4** total_sessions 双口径注释（局数 vs 学习日次，判定语义不动）；⑥**R9-M4** player_data(player_name) 索引 + tab 补全前缀 ≥2 防抖；⑦**R4-M1** 广场直达登记快照（openSquareWithSnapshot，照 F3-01 先例）；⑧**R4-M2** 他人主页日历三层隐私门（隐身笑话页/未公开拒绝+calendar.not-public/本人直开）+ LearningCalendarGUI targetId 重载；⑨**R7-M1** 赛季发放批量查询（deliverPendingBatch + getPendingSeasonRewardsFor 分批 IN）；⑩**R7-M2** 班名过滤 &/§/\n/\r；⑪**R7-L5** class list 与 GUI 同源（班级过滤查询）；⑫**R9-M1** statistics-server reload 自动重启（restartStatisticsServerIfNeeded，端口/密码改动生效）；⑬**R9-M2** export 相对路径；⑭**R10-M1** daily-reminder 任务体点读门；⑮**R10-M2** messages 升级前 .bak 备份；⑯**R10-M3** auto-save-interval 钳位 [1,60]；⑰**R10-M5** config.yml 六处"需重启生效"注释；⑱**R12-M1/M2** openAsync token 去重（挑战/日历/聊天历史三 GUI）；⑲**R12-M3** GUIUtils.fillEmpty 基建 + 8 GUI 调用点（防拖纵深统一）；⑳**R13-M1** join checkAndAssign 异步化；㉑**R13-M2** ChatHistoryGUI 两段式（列表+详情页全异步，open 委托使 listener 零改动）；㉒**R13-M3** 广场 ALL_PLAYERS 聚合单查（getActiveSquareEntries，N+1 消除）；㉓**R13-M4** 四 per-player map 清理收口（unloadPlayer ×2 + 午夜 rollover + 庆祝惰性清扫）。i18n：新 2 键（calendar.viewing-title/calendar.not-public）+ class.name-invalid 值更新，config-version 15→16
- **[全仓审查 Block C1]** 玩家可感中危 12 条（4 施工流并行 + 3 路红队验证 GO-with-fixes + 收口流）：①**R1-M1 会话覆盖**——startQuiz/startReviewQuiz/startTutorialQuiz 三入口加活跃会话守卫（拒入 + word.session-in-progress），旧会话不再被静默作废（分数丢钱照发的口径分裂消除）；②**R1-M2 练习/答题互斥**——三点守卫（practice 入口拒 quiz、quiz 入口拒 practice、ChatScenarioListener R-66 补 practice），两态并存输入被吞的超时判错链消除；③**R2-M1 场景折叠**——more-scenarios-lore 文案补 `/le dialogue <名>` 命令入口（Tab 补全可达）；④**R2-M3 点评串台**——handleSummaryResult 顶部新会话分流（hasActiveConversation 判据；命中不显示仍落库）；⑤**R2-M4 场景选择决斗门**——与命令侧对称（duel.finish-duel-first）；⑥**R5-M1 宽限链三修**（照 R5V 红队证实修法）——覆盖前置清理 + selfRef/selfCancelGrace 双参自取消三处 + handleReconnect isOnline 守卫（disconnectTimers 前）+ DuelListener UUID 捕获；1 秒连锁僵尸任务/对局悬挂链条闭合（红队逐步复算证实）；⑦**R5V-1 DuelGUI 空槽**——27 格灰玻璃铺满 + shift-click 纵深 guard（InventoryAction 正确 API，红队二进制核验纠正规格笔误）；⑧**R8-M1 返回栈残留帧**——五处命令直开（/le challenge、/le profile ×2、/le progress、/le stats）各压主菜单帧（残留零危害）；⑨**R11-M1 GUI 标题本地化**——WordLearningGUI 难度/review 标签走 localizeDifficulty（F2-13 漏面收口）；⑩**R11-M2 词库点击 catch**——ISE/IAE 对齐命令路径双 catch（与 R8-L2 合并）；⑪**R11-M3 预载失败提示**——两 GUI 异步 catch 补 runTask 回主线程发 daily.load-failed + 失败音；⑫**R11-M4 签到失败语义**——CheckInResult 增 storageFailed（双 back-compat ctor），DB 写失败不再伪装"已签到"。**N1/N2 收口**（红队发现同族守卫缺口）：决斗/对话中开学习/复习/练习的四入口补守卫（duel 优先→对话次之）。i18n：新 4 键（word.session-in-progress/word.practice-in-progress/checkin.storage-failed/daily.load-failed），config-version 14→15
- **[全仓审查修复批 A]** 高危四修（多 Agent 模式：8 施工流并行 + 3 路红队验证）：①**R10-H1 同日重启刷钱**——单词挑战奖励"已付"台账原仅内存 Set（`wordChallengeClaimedToday`），同日重启后答对任意词即重入 `hasPendingWordReward`（内存空 + DB 已完成=true）重复发放且 daily-cap 同为内存态不拦；修复=新表 `daily_word_reward_claims`（UNIQUE(player_uuid, challenge_date)）承载持久化台账，`markDailyWordRewardClaimed`（INSERT OR IGNORE 幂等）在成功发放与经济禁用终态两分支写入，`hasPendingWordReward` 改三层（内存快路径 → DB 台账命中回填内存 → 全完成判定），rollover 补发按昨日日期标记（不误封今日），`isWordRewardPaidToday` 改内存短路+DB 回查；**新表同步进 createTables + schema.sql + deletePlayerData**（防后建表盲区）+ ai_chat_feedback 保留期清理（R3-H2 附带）；②**R4-H1 reload 重复解锁**——`tryUnlock` 原 `unlockedCache.computeIfAbsent(k -> new HashSet<>())` 在 /lea reload 清缓存后用空 Set 跳库，已解锁成就重走 UPSERT 覆盖 unlocked_at 时间戳并重播通知/跨服广播；修复=与 `hasAchievement` 同款懒加载（get → null 则 loadPlayerAchievements → 重取 → 防御兜底）；③**R3-H3 锁外取 Connection 引用**——PlayerDataStore 10 处（recordSession/getSessionDays/loadPlayerData/savePlayerData/saveProfileSettings/loadProfileSettings/persistLongestStreak/getRankedPlayers×3）`conn` 在 synchronized 前赋值锁内使用，重连替换后旧引用关闭静默丢写；修复=全部收敛为锁内首行赋值（F1-20 范式全库化；loadPlayerData 方法级声明适配）；④**R3-H1 备份安全网**——原 wal_checkpoint(TRUNCATE) 对非 WAL 库是空操作、Files.copy 在锁外可撕裂、quick_check 查活连接（查错对象）；修复=VACUUM INTO 原子一致快照（锁内执行、路径单引号转义）+ 独立只读连接校验备份文件（`checkBackupIntegrity`）
- **[全仓审查修复批 B]** 高危四修 + 附带（同上多 Agent 模式）：①**R9-H1 export 主线程 IO**——`/lea export` 原主线程做全量文件 IO + cache-miss 查库（同族 report 已全异步的唯一漏网）；修复=`exportAllData(List<UUID>, List<String>)` 改纯同步方法（主线程仅快照，IO 与查库全异步，内外两层 IllegalStateException 兜底）；②**R9-H3/R7-H1 广播无长度上限**——超长广播 writeUTF 异常逃逸/跨服静默丢帧/可能踢无辜信使玩家；修复=`ProxyMessenger.MAX_PAYLOAD_BYTES=30000` 单源常量 + sendForward 兜底截断（`truncateUtf8` 不劈多字节）+ sendPluginMessage 异常捕获 + AdminCommand 前置拒绝（新键 `admin.broadcast-too-long` 双语）；③**R9-H2/R3-H2 时区族**——报告 Q4 AI 对话数本地日期边界 vs SQLite UTC 存储 8 小时错位（修复=SQLite 分支边界换算 UTC 排他上界、MySQL 维持）、ChatHistoryGUI 时间显示转本地（`formatHistoryTimestamp`，SQLite 按 UTC 解析后系统时区渲染）、cleanupOldChatHistory 补 ai_chat_feedback 清理（同阈值）+ 保留期窗口语义注释；④**R7-H2 赛季窗口校验**——结算扫描只处理"真正走完"的季（`window.end().isBefore(today)` 守卫 + WARNING），修 anchor-date/duration 点读可改下对进行中窗口提前切割发奖；附**R2-H1**：AIChatManager assistant 回复写库包 `CompletableFuture.runAsync`（与 user 路径同形，统一线程模型）
- **[P3 低危尾账 / P2-08 收口]** `cancelDuelByAdmin` 双方离线 GM 无法解散修复：原实现目标离线时直接回 `admin.duel-cancel-offline`（R-69 队列只管重连补发、不管入口）——现离线目标按人名回退遍历 `activeDuels`（快照拷贝防并发修改）找房间，找到后照常走解散+双方离线通知队列，找不到才回离线提示。附：`addWord`/`removeWord` 状态修正（核实为保留的潜在功能面，非死代码——零外部调用但含完整实现与 `saveWordBankToYaml` 持久化）
- **[P3 低危尾账 / R-01~R-03 收口]** 红队新发现三条修复：①**R-01 ProxyMessenger 跨服广播注入面**——`handleAchievementBroadcast` 的 playerName/成就名来自代理 payload 未经清洗直接进广播（恶意/被攻陷子服可注入颜色码或换行刷屏全部收件人），照 `ChatAnswerListener.sanitizeBroadcastName` 同款清洗（stripColor + `&`色码正则剥离 + 换行转空格 + 截断 50 + 空回落"?"；成就名同款——发送端为枚举纯文本、清洗幂等，纵深防御对恶意 payload 必要）；②**R-02 ReportGenerator 死变量**——`achTotalDb` 只写不读（与 `unlockedDb` 读同列的历史残留），声明+赋值两行删除；③**R-03 帧解析可测化**——PF-SEA-01 的 Forward 帧解析从"内联在 Bukkit 接口、单服无法自动验证"抽为包私有静态纯函数 `parseForwardFrame(byte[]) → ParsedFrame(subChannel, payloadBytes)`（非法帧返 null 不抛），新增 `ProxyFrameParseTest` 5 用例覆盖短帧往返/长帧 ≥128 字节（原 UTFDataFormatException 实伤场景回归）/截断帧 null/垃圾字节 null/中文多字节载荷逐位相等——**双服实测依赖降级为单机自动回归**（真机跨服联调仍建议做一次）
- **[P3 低危积压清零 / 五流合流]** dev-findings 全量清零行动（多 Agent 模式：4 路只读核实红队 + 5 施工流并行 + 2 路红队验证）：**53 条挂账核实 = 27 条已修（状态滞后回写）+ 1 条已过时 + 25 条当批修复**。施工内容：①**流 A 决斗系**——删死 API `getDuelRoom`/`hasPendingRequest`/`getLastCorrectAnswerer`/`containsPlayer`/`getWordStartTime` + 死字段 `DisconnectInfo.disconnectTime`/`DuelRoom.wordStartTime`（7 符号全仓零引用复核）；**F1-18** 对局结束窗口的聊天被吞改发 `duel.not-in-duel` 反馈（复用既有键）；**僵尸任务泄漏修复**（红队新发现真伤：宽限到期分支只摘登记不 cancel 任务本体，每次判负留 20-tick 空转任务到关服——照 R-26 selfCancel 模式修复）；②**流 B 数据层**——**F1-20** `loadPlayerData` 第二锁块锁内重取 Connection（E-04 范式，消除重连换引用后旧连接使用）；**F1-21** `getPlayerName` 改 cache-first + `isPrimaryThread` 守卫（主线程回落名册/非主线程回落 uuid）；**P3-07** 导出 CSV 落 `exports/` 子目录（wiki 同步）；**P3-12** 三处 `Asia/Shanghai` → `TimeZone.getDefault()`（与 checkin/streak 全链系统时区口径统一；判定与展示同区）；P3-05/06/16/F5-03 口径注释 4 处；③**流 D i18n/社交 GUI**——**P2-19** 新键 `chat-history.unknown-scenario`（双语，消除玩家可见 "Unknown" 硬编码）；**F4-04** 成就墙点击补 showInSquare 纵深守卫（返回帧只在放行路径压入防自绕过）；**F3-02** 笑话页返回改 popBack（消除上游帧栈残留）；P2-27 越层注记；④**流 E 词库内容**——删生僻词 `dingle`（advanced 330→329）+ beginner 同义词越级清理 10 个（feline/canine/consume/dine/bold/breeze/companion/automobile/academy/institution，泛化扫描全 317 词条；`baby→infant` 保留）+ README 审查方法论；⑤**流 F 挑战/单点**——F2R-01/02 契约注释 + P2-26 结构环双向注记 + **F0-12** 删冗余守卫（319 输入逐位等价实证 + 既有单测 6/6） + `clearWordChallengeClaim` 死方法删。**红队验证：流 A 7/7 + 流 B 5/5 证实零证伪**；附 sqlite/词表/YAML 多重守恒验证；`mvn clean verify` BUILD SUCCESS（44 测试）
- **[P3 遗留中危 / F2-11+C-3+C-7 线程纪律收口]** 三条"主线程查库"遗留修复：①**F2-11 午夜 rollover**（`LetsLearnEnglish` 任务体重写为两段式：重活（昨日作废计数 + 今日分配 = DB + 词库读）入异步段，R-31 补发（含 `Bukkit.getPlayer`/Vault 发币/sendMessage）与内存清理留主线程段——原逐玩家同步 SQL 串行在主线程，百人服午夜卡顿；重排安全性论证：checkAndAssign 碰"今日"行、countUnclaimed 只读"昨日"、hasPendingWordReward 碰"昨日"+内存标记，集合不相交无语义漂移；R-75 自续移入主线程段 finally，异步异常路径亦汇入；停用窗口 `IllegalStateException` 双兜底）；②**C-3 DailyChallengeGUI**（打开路径两处 DB 面——`getTodayChallenges` 与 `getTodayWordProgress`——全部移入 `openAsync` 异步链，`open(Player)` 兼容入口统一委托异步（彻底断根不留同步后门），`openWithData` 渲染零查库；三个调用方改调，pushBack 顺序保持）；③**C-7 LearningCalendarGUI**（`getSessionDays` 同款 `openAsync` 两段式；SocialListener 调用点改调）。三处线程干净性逐一核实（getTodayChallenges/getTodayWordProgress/ensureWordChallengesAssigned/getSessionDays/countUnclaimedForDate 均无 Bukkit 世界 API）；守卫纪律三处齐全（异步段首+runTask 前 isEnabled、主线程段 isOnline 复检、try/catch 记 WARNING + IllegalStateException 停用兜底）。**三维 tester 收口**（架构 PASS/代码 GO）：①F2-11 把 `checkAndAssign` 首次推入异步线程后与主线程 R-32 补分配路径存在并发窗口（COUNT 预检与 INSERT 分处两个临界区，当日行数最多 2N）——按 R-02 先例加 UUID stripe lock 串行化整个"预检+分配"段（L→M 同向不死锁）；②`WordBank.getAllWords` 返回 `List.copyOf` 防御拷贝（原 `unmodifiableList` 视图与主线程 addWord 并发理论 CME——F4-03 先例，单词对象不可变浅拷贝足够）；③LearningCalendarGUI 未用 import 清理
- **[P3 遗留中危 / P3-02+P3-03 数据链收口]** 两条自 P2 挂账的"恒 0 数据源"修复：①**P3-02 `learning_log` 零写入**——`getTotalSessions()` 与 `getLast7DaysStats()` 每日会话数改走 `player_sessions`（真数据源；删 learning_log 双后端毫秒换算双分支改单条同构 SQL，口径"学习日次=每人每日一行"已入 javadoc；统计面板 `totalLearningSessions` 与 7 天曲线不再恒 0。注：因每人每日一行，7 天曲线的 sessions 与 players 两序列数值重合属数据源固有属性，已注明）；②**P3-03 `current_streak` 零写入**——`%lle_streak%`（`LLEPapiExpansion.getCurrentCheckInStreak`）与 `ProgressManagerImpl.loadStreakData` 的 current_streak 改走签到真源 `checkin_data.streak_count`（`player_data.current_streak` 废弃列保留不删、建表处注释标注；`longest_streak` 语义=答题连击峰值保持直查该表列）。附 sqlite-jdbc 真库冒烟 7 断言（COUNT(*)=6 防 UPSERT 重复计、窗口过滤、日期同构）
- **[P3 最终验收 / 诚实性收口]** 三路锐评 agent 之三的诚实性审查（`ralph/test-reports/final-honesty-review.md`）新发现：`ConfigManager.achievementConfig` 是 Java 死链（字段+getter 零消费），且 `loadConfig("achievements.yml")` 会把**已废弃文件释放到数据目录**误导管理员（文件自带 deprecated 头注、运行期真源是 `achievements-config.yml`）——整链移除（字段/getter/加载调用/废弃文件本身从 JAR 删除，AchievementWallGUI 交叉引用注释同步改写）
- **[P3 最终验收 / 玩家旅程收口]** 三路锐评 agent 之一的旅程验收（`ralph/test-reports/final-player-journey.md`）发现 4 项跨批断裂，编排者当轮收口：①**模块开关"入口隐藏"大面积落空**（config 三层语义承诺与实现不符的实伤，config.yml 注释带头吹）——`MainMenu` 渲染门只落了 duel/checkin，`word-system`/`ai-chat`/`daily-challenges` 四按钮关闭后照常渲染，现补齐 4 个渲染门 + `MainMenuListener` 同款点击门（僵尸界面兜底，module.disabled 拒绝）；②**WrongBook 全链零门**——关 word-system 后错题本可开、可删词、Shift 可进练习并消费聊天输入（唯一"关闭后仍可玩"的破口），现 `WrongBookGUI.open` 单点门 + `WrongBookListener` 点击门（返回键放行保退出）+ 练习入口一并拒绝；③**F3-01 榜单直开点头像无反应**（三 Phase 未清的留账）——`/le leaderboard` 直开不登记 `leaderboardGuis` 快照，现 `SocialListener.openLeaderboardWithSnapshot` 登记式打开 + `LearnCommand` 改调 + `LetsLearnEnglish` 持有实例与 getter；④`ProfileGUI` 成就墙点击分支补 achievements 门（渲染与点击同源 gating 闭环）。另：**赛季"开而不动"静默陷阱**——`season.enabled=true` 但锚点在未来时全系统休眠无任何提示（验收/运营易误判"功能没做"），`settleIfNeededAsync` 启动打一次 WARNING 说明原因与检查点（dormancyWarned 防刷屏）
- **[P3 最终验收 / 跨波一致性收口]** 三路锐评 agent 之一的跨波一致性审计（`ralph/test-reports/final-consistency-audit.md`）发现跨批打架项 C-1~C-10，编排者当轮收口 8 项：①**C-1 `update-interval` 三方推诿**——plan 裁决"接线"未执行（键悬空，注释与行为差 10 倍），现接线 `LetsLearnEnglish` 刷新任务（默认 300 秒 = 原硬编码 5 分钟零漂移，钳位 [30,3600]，config 注释同步）；②**C-2 reset 残行**——4A 新表 `class_members` 未补进 `deletePlayerData`（后建表没回头补跨批函数的模式），补进 uuidTables；③C-4 `money.vault-required` 零读取死键删除（Vault 缺失由检测驱动，无需开关）；④C-5 8 个漏网 i18n 死键删除（`review.complete`/`achievement.unlocked`/`progress.view`/`admin.export-start`/`admin.word-add`/`answer-hint-lore-2`/`current-word-lore-2`/`wrong-book.title`——审计脚本单向"引用→缺键"不查"键→无引用"故 4B 后零告警）；⑤C-6 `gui-layout.main-menu.wrong-book` 反向缺键补录（代码双侧读 config 无定义，值与代码默认一致零漂移）；⑥C-8 schema.sql 补录 player_sessions 两防扫索引文档；⑦C-9 `CountdownTier` 学习侧接线（逐位等价收敛单源）+ 5 项单测；⑧C-10 P3-02/P3-03 挂账措辞校正。**C-3/C-7（DailyChallengeGUI 与 LearningCalendarGUI 打开主线程查库旧面漏检）记入 dev-findings 待排期**（含写路径比纯读重一档）
- **[P3 Wave 4 Batch 4B / MC-7 死键死类大扫除]** 终局清理（**必须在全部接线后**—— Wave 1-4A 完成后执行）：**i18n 死键 zh/en 对称删 14 项**（`word-learning-gui.answer-broadcast`（保留一字之差的活键 `answer-broadcast-correct`）/ `daily-challenge.claim-button`+`-lore` / `leaderboard.title-weekly`|`title-monthly`|`title-alltime` / `word.session-end`|`correct`|`incorrect`|`mastery-update`|`daily-limit-reached`（保留活键 `dialogue.daily-limit-reached`）/ `checkin.streak-info` / `general.data-saved` / `admin.reset-pending` / `error.scenario-save-failed` / `dialogue-gui.*` 整段 / `progress-gui.*` 整段）+ **死类 `gui/ProgressGUI.java` 删除**（零实例化点，与 progress-gui 键成对删防新死键）；**config.yml 死键删 11 项**（`general.debug` / `database.mysql.pool-size`（单连接无池）/ `difficulty-settings.*` 整段（难度=words/*.yml 自动发现）/ `progress-system.rewards.types.{item,command,exp}.enabled`（未实现奖励类型；money 子树保留）/ `progress-system.achievements.config-file`（F0-10，代码硬编码）/ config 版 `gui.main-menu-title`（messages 版是活键只删 config 份）/ `gui-layout.profile.*` 整段 15 叶（P2-25+PF-MC-03）/ `gui-layout.main-menu.scenario-select.back-button` / `proxy.cross-server-announcements`|`cross-server-messages`（通道无条件注册，注释同步改写）/ `gui.custom-textures`（资源包 D7 裁决不做））——每键删前全路径 grep 复核（**编排者修正了 plan 死键表两处过期判定**：`leaderboard.header/entry/footer` 已被 `/le season` 快览复用为活键、`statistics-server.*` 被 StatisticsHttpServer 读取，两者均保留）；`ConfigManager.getGuiBorderMaterial` 死方法连带删除；MessageUtil javadoc 示例替换为活键；`SocialListener` 死配置注释更新。全批删除后：叶子键 692=692 对称、SnakeYAML 真解析零差异、audit 零新增缺失、类数 148→147、`mvn clean verify` BUILD SUCCESS（39 测试）。**三维 tester 共同点名的收口**：①`GUIUtils.isPluginGUI` 历史死启发式清除（旧 DialogueGUI「Dialogue/情景对话」/ 旧 ProgressGUI「Learning Progress/学习进度」/ 旧分榜「Weekly|Monthly|All-Time Ranking + 周榜/月榜/总榜」/ 旧错题本「Difficult Words」——逐一核实现行 GUI 标题零命中、保留项全部有活标题覆盖；广场标题含模式词但恒含「英语广场」先行命中）；②P3-15 死类 `core/progress/Achievement.java` 删除（与 ProgressGUI 同名歧义陷阱，零引用全维度核实）——两处为 tester 上报后编排者收口
- **[P3 Wave 1 / 3A-4 漏项补建]** per-player 音效开关补 UI 入口：设置页 slot 16 新增"音效反馈"开关（ProfileGUI 渲染 + MiscGUIListener 点击切换 + `profile.settings-sounds(-on/-off)` 双语 4 键）——Wave 1 曾声明该 story 完成，但 `setSoundsEnabled` 除定义外零调用者、设置页只渲染 11/13/15 三开关，实际玩家无 UI 可关（数据列 + SoundManager 门均已接线，仅缺入口；DEPLOYMENT_WIKI 核对时发现）
- **[P3 Wave 2 / 集成修正]** `SocialListener` 模式识别按标题后缀（`detectModeFromTitle`）+ `renderedFeedPages` 冻结动态页渲染列表：plan "FEED 界面打开即登记快照"对命令/主菜单直达路径不成立且 ESC 重开快照陈旧——修正防"点 A 开 B"缺口；同类面在 `leaderboardGuis` 未修（PF-SOC-06 记 Wave 3）
- **[P3 Wave 1 / 架构]** tryUnlock 通知段主线程化（`Bukkit.isPrimaryThread()` 判定 + runTask）——根治 PF-ACH-02 bye 分支异步线程直调 Bukkit API 隐患；`achievementConfig` 懒加载改 volatile（F1-03）；`CountdownTier` 纯函数（学习/对决共用 tier 判定）；`VersionAdapter.sendActionBar`（Adventure Component，规避已废弃的 String 重载）
- **[P3 Wave 1 fan-in]** S-F/S-H 上报缺口收口：`MiscGUIListener` 词库选择补 word 门兜底 + startQuiz null 防御；`LearnCommand.startWordQuiz` 补 startQuiz null 防御；`OnboardingManager.interceptQuizStart` 补 startTutorialQuiz null 接管（S-G 自落）；money 三处直读收口（LearnCommand.isEconomyAvailable / MiscGUIListener 领取判定 / CheckInManager.isMoneyDisabledByConfig 全部改走 `getRewardEngine().isMoneyEnabled()`）
- **[P3 规划期即修 / PF-ACH-02]** AI 对话 bye 退出路径异步线程直调 Bukkit API 修复（编排者即修）：`ChatScenarioListener` 运行在 AsyncPlayerChatEvent 异步线程，bye 分支链上可达 `AchievementManager.tryUnlock` 通知段（sendMessage/playSound）与每日挑战记分——Bukkit API 不得异步直调；bye 分支改为切主线程执行 `handleEndConversation`，并给其加"仍在场景模式才结算"守卫（防 1 tick 窗口内 quit/命令退出后的重复结算）；此缺陷比 F1-03 记录的更严重，属线程安全实伤
- **[P3 规划期即修 / PF-MC-01]** 行为挑战领取补 `money.enabled` 门（编排者即修）：此前 `claimBehavioralChallenge` 直接走 `depositCappedReward`，服主关币（默认 false）且装了 Vault 时仍会实发金币，与签到/单词挑战的 economy-disabled 语义不一致（F1-11/R-44 残留缺口）；关闭时终态处理（标记已领防按钮永远可点 + 一次 daily-challenge.economy-disabled 提示），与单词挑战侧同口径
- **[P3 规划期即修 / PF-ONB-01]** FIRST_WORD 成就语义与描述对齐（编排者即修）：原判定 `sessionCorrect>=1 && sessionIncorrect==0` 要求"首场零失误"，与成就描述"答对第 1 个单词"不符、且与 PERFECT_SESSION（整场全对）语义重复——新玩家第一场答错一题就与"初出茅庐"擦肩，伤首会话留存；改为"会话内至少答对 1 题"即解锁（该成就仅解锁不涉经济，无发放面影响）
- **[P3 规划期即修 / PF-ONB-03]** 词库选择路径标识符本地化补齐（编排者即修）：F2-13 此前只修了 `/le word` 命令路径，`MiscGUIListener.handleWordBankSelect`（新手主路径）仍把 beginner/choice 英文标识符透传给中文玩家；现与 LearnCommand 同口径本地化难度与模式（word.difficulty-*/mode-* 键）
- **[P3 规划期即修 / PF-SEA-01]** 跨服消息通道接收端帧解析修复（Phase 3 规划 agent 发现、编排者即修）：BungeeCord/Velocity 的 Forward 投递帧为 `[子通道 UTF][short 帧长][载荷字节]`，原接收端跳过帧长直接 `readUTF`——短消息带 2 字节不可见前缀，长消息（modified-UTF ≥128 字节）解码抛异常整条丢失，跨服公告/成就广播通道现状大概率不可用；现按官方协议先消费帧长、readFully 取载荷再解析，非法帧/载荷异常均 fine 记录不穿出监听器（单服无法验证，需双服实测确认）
- **[P3 规划期即修 / PF-SEA-02+03]** 幂等建索引双后端修正与补索引（编排者即修）：`CREATE INDEX IF NOT EXISTS` 是 MariaDB 扩展、Oracle MySQL 不支持（原异常被 fine 吞，`ai_chat_history` 两索引在 MySQL 后端可能从未建立）——新增 `createIndexIfMissing`（SQLite 原生 IF NOT EXISTS / MySQL 查 information_schema 判存在再建，全程收进 connectionLock 符合 E-04 范式）；顺带补 `player_sessions(uuid, session_date)` 与 `(session_date)` 两个防全扫索引（周/月/赛季/streak/日历多条日期范围查询受益）
- **[P3 规划期即修 / P3-01]** `ProfileGUI` 正确率恒显 100% 修复：M10 后 `getTotalWords()` 语义为"只计答对"（==totalCorrect），原公式 `correct/getTotalWords()` 分子分母同值；改为 `correct/(correct+incorrect)`，与 PAPI 的 `%lle_accuracy%` 同口径（Phase 3 规划 agent 上报、编排者即修，属数据展示实伤）
- **[P2 fan-in]** Code tester 收口：`acceptDuel` 复刻 sendDuelRequest 的 R-66 AI 对话互斥（邀请发出后任一方可能已进入对话，接受校验不过不消费邀请、双方提示；接受方用 dialogue.chat-active 通用键）；config.yml menu-size 注释校正（9/18 已不支持，合法域 27/36/45/54）
- **[P2 fan-in]** Arch tester 收口：`LearnCommand` limit-reached 的 {max} 改读 `getMaxPersonalScenarios()` 公开 getter（消除死 getter、与 AIChatManager 上限常量单一真相源）；`DuelManager.PendingDuel` 收窄为 private（仅本类使用）；`MiscGUIListener` 两处未用 import 清理（ChallengeType/LearningCalendarGUI）
- **[P2 fan-in / R-69 消费端]** `consumeOfflineDuelNotice` 接入 `PlayerJoinListener.onPlayerJoin`：GM 解散对局时离线的一方下次进服收到 `duel.cancelled-by-gm` 通知（此前仅 S3 侧入队、S5 未接消费端，属集成缺口，编排者 fan-in 补齐）
- **[P2 fan-in]** `MESSAGE_CONFIG_VERSION` 10→11：zh.yml/en.yml 随 P2 新增 14 键（invalid-difficulty/no-words-available/difficulty-*/mode-*/finish-quiz-first/chat-active/scenario-not-found/load-updated/load-save-failed/limit-reached/sender-chatting/target-chatting/player-no-data/export-failed）、改写 3 键（duel.usage·help.duel 补 cancel 双入口、opponent-timeout 改 {seconds} 占位）升至 v11
- **[P2 S1 / R-46+R-86i]** 主菜单 menu-size 崩溃根治：合法域收紧为 [27,54] 且 9 的倍数（按钮槽位固定 10-18，9/18 会 ArrayIndexOutOfBounds 开菜单即崩），非法回落 27 + WARNING
- **[P2 S1 / R-86]** 音效双声根治：menu-open 收口为 SoundManager 全局 InventoryOpen 唯一播点（MainMenu/ScenarioSelectGUI/ChatHistoryGUI 显式播放删除，16 个插件 GUI 全覆盖）；menu-click 全局 InventoryClick 删除（点背景玻璃/自己背包不再误响），各 listener 显式播放为唯一来源，DuelGUIListener 退出键补播；未知音效键首次 WARNING 防刷屏
- **[P2 S1 / F6-01]** 二确 stage1 延迟 reopen 竞态：延迟体内三连复查（isOnline + stage==1 + targetLang 与落盘一致），已取消的玩家不再被拽回第二页
- **[P2 S1 / F6-02]** `ConfirmationGUI.isConfirmGUI` 由裸子串匹配改为与标题生成同源等值比对（zh/en 双查、同 key 同颜色转换），不再误吞其它含"确认"字样的界面点击
- **[P2 S1 / F2-05]** `MiscGUIListener` 默认模式写死 "choice" 收敛为本地常量（与 LearnCommand.DEFAULT_WORD_MODE 对齐注释）
- **[P2 S1 / R-86b]** 隐私设置页返回走返回栈：SocialListener 进入设置前 pushBack「本人主页」帧、MiscGUIListener 返回键 popBack（fallback 开本人主页），返回栈不再残留旧帧
- **[P2 S2 / R-61+R-86f]** 个人场景覆盖与崩溃链：slug 尾附 CRC32 短码（纯中文名"开会/购物"归一化后不再互撞）；同名幂等更新语义；save 失败/上限拒绝不再报成功；返回值改 `ImportResult(key, updated, saveFailed, limitReached)`（LearnCommand 四路分支消费）；损坏文件 save 前 .bak 备份防清空
- **[P2 S2 / R-62]** 个人场景文件上限：>512KB 不解析（warn 视为空表）、单玩家 >30 条只加载前 30 并 warn；import 未达上限才放行新增（同名更新永远放行）
- **[P2 S2 / R-63]** `ai-chat.max-context-pairs` 死配置接线：上下文构建按"最多最近 N 对完整 user/assistant"截取（默认 6，与 token 预算双约束）；system prompt 永不裁语义保留，单独超预算 WARNING 一次
- **[P2 S2 / R-65]** TTS 内容校验加固：空 / octet-stream Content-Type 不再 fail-open，过魔数校验（WAV RIFF / MP3 ID3 / 0xFFEx）才落盘；毒缓存发现即删；空响应体不落盘
- **[P2 S2 / R-67]** shutdown 在途请求守卫：volatile shuttingDown + 所有 HTTP 回调链入口 `isShuttingDownOrDisabled()` 检查（含 user 写库 try/catch），关服回调不再碰 scheduler/DB
- **[P2 S2 / F4-01+F4-03]** 个人场景懒加载补漏：`buildSystemPrompt` 对 personal: key 内存 miss 时先触发懒加载再构建（重启后纯命令进入不再回落通用句）；`getPersonalScenarios` 返回防御性拷贝；开场白请求失败不再静默（发 dialogue.request-failed）
- **[P2 S2 / F1-08]** "AI 正在输入"提示移到计数检查之后：新增只读预检 `canSendChat`，超限/冷却时不再先显示输入中再报错
- **[P2 S3 / R-68+F1-15]** 决斗邀请假过期根治：pendingRequests 值改 `PendingDuel(senderId, token)`（AtomicLong 令牌），过期任务按 token 匹配（同方重发不再误删新邀请）；acceptDuel 全校验通过才原子消费（失败发 request-expired-target 而非吞掉）
- **[P2 S3 / R-69]** GM 解散离线通知：新增 `Map<UUID, OfflineDuelNotice>` 队列（覆盖式），`cancelDuelByAdmin` 对离线方入队（duel.cancelled-by-gm + 对方玩家名），`consumeOfflineDuelNotice(UUID)` 供 join 消费；重连静默问题根治（already-over 残分支保留为纵深防御并补注释）
- **[P2 S3 / R-70]** 对决头像真设置皮肤：`createSkullItem(UUID)` 用 SkullMeta.setOwningPlayer（此前 owner 参数悬空双方都是 Steve 头）；p1/p2Material 死变量清理
- **[P2 S3 / R-72]** 新增 `DuelManager.shutdown()`（幂等）：取消全部任务容器、重置在场玩家记分板+关 GUI、清空 5 个 map/set；LetsLearnEnglish.onDisable 接线（null 防护 + try/catch）
- **[P2 S3 / R-84+R-86n]** 决斗音效对称：邀请过期双方/GM 解散双方 menu-click、败者 incorrect（均 isOnline 内）；opponent-timeout 调用点补传 {seconds}=GRACE_PERIOD_SECONDS（文案脱钩硬编码 30）
- **[P2 S3]** 决斗卫生清理：rewardEngine 死字段删除、`cancelDuel(UUID)` 死方法整体删除（opponentId 死变量随之消灭）、`DuelRoom.submitAnswer` answer null 防护、isBusy javadoc 语义说明
- **[P2 S4 / R-86c+连带]** `/le word` difficulty 归一化 + 存在性校验（resolveDifficulty 大小写不敏感解析回规范 key，非法发 word.invalid-difficulty 列可用难度）；无可学词 IllegalStateException 兜底（word.no-words-available，不再冒泡零反馈）
- **[P2 S4 / F2-13]** `/le word` 成功提示 difficulty/mode 走 i18n label（word.difficulty-*/mode-*，未知标识符回落原值），中文玩家不再看到 beginner/choice 英文标识符
- **[P2 S4 / R-86d]** `getBehavioralName` 裸 getString 防护（缺 key 回落 key 名，不再 NPE 中断整段挑战列表）
- **[P2 S4 / R-66 对称]** `/le dialogue` 启动前守卫：决斗中（duel.finish-duel-first）→ 答题中（dialogue.finish-quiz-first）→ 已有对话（dialogue.chat-active）；场景 key 存在性校验（dialogue.scenario-not-found，大小写不敏感解析）；tab 补全 mode 列表从 VALID_WORD_MODES 常量派生
- **[P2 S4 / R-86h]** Admin 三连：reset 未找到玩家发 admin.player-no-data 不再报成功（Handler resetPlayerData 返回 boolean）；export 失败（返回串 "Export failed" 前缀）发 admin.export-failed 不再打"完成"；F5-01 配套：resolvePlayerUUID 补 Bukkit.getOfflinePlayer 三级查找（半删状态可补删）
- **[P2 S4 / R-86q]** plugin.yml learnadmin aliases 清理（[lea, learnadmin]→[lea]）；签到成功补 correct 音效
- **[P2 S5 / R-64]** TTS 发音冷却收口：WordLearningListener 自注册 quit 清 pronounceCooldown（匿名 new 无实例引用的自包含方案）；冷却下限 1→0（0/负数=不启用，跳过判定与时间戳写入）
- **[P2 S5 / T0-01]** drag guard 补装三个 listener（WordLearningListener/WrongBookListener/ChatHistoryListener）+ 编排者补 ScenarioSelectListener：全库 8 个处理库存点击的 listener 全部有 onInventoryDrag 上半区守卫
- **[P2 S5 / R-41+R-76+F1-24]** quit 内存卫生收口：新增 `GuiNavigationManager.clearPlayer` / `CheckInManager.unloadPlayer`（playerLocks 泄漏）/ `RewardEngine.unloadPlayer`（economyDisabledNotified 泄漏），统一在 PlayerJoinListener.onPlayerQuit 编排（Listener→服务单向无环，PlayerDataStore 不反向调用）
- **[P2 S5 / F1-04]** 金币提示小数格式化：`{earned}` 走 formatCoins（整数值显示 490 非 490.0，小数两位），三处替换
- **[P2 S5 / F2-01]** 每日挑战 MySQL 分支补 `ON DUPLICATE KEY UPDATE`（与 SQLite OR REPLACE 对齐幂等）
- **[P2 S5 / F2-04]** `Word.equals/hashCode` 收窄为 english(ignoreCase)+part_of_speech（reward 漂移不再影响集合去重/干扰项排除；消费点已核查、单测语义兼容）
- **[P2 S5 / R-79+R-74+R-59]** `checkDailyStreak` 查询加 LIMIT 40（≥30 天判定窗口无截断）；重抽概率注释校正（24% 高频路径非"理论不可能"）；quit 结算误导注释三处校正（下线照常结算、仅不发走完会话金币/庆祝）
- **[P2 S5 / R-84+F3-03]** 学习超时补 incorrect 音效（与答错对称）；词库展示语义序 beginner→intermediate→advanced→其它字母序（DIFFICULTY_ORDER + LinkedHashSet 保序，WordBankSelectGUI 展示随之修正）
- **[P2 S6]** 三档词库全量字母序重排（beginner 123 / intermediate 4 / advanced 17 处乱序归零，317/349/330 零丢失、双库解析验证）；book 超纲同义词 tome 删除；leisure/idle 例句改自然搭配
- **[P2 S7]** 8 个 GUI 裸 getString 收敛为 MessageUtil.getGuiMsg（145 处，131 唯一键双语零缺键；缺键显示 [?key] 不再 NPE 炸界面），标题 NPE 面清零；DailyChallengeGUI 挑战名防护 + 进度展示 clamp；MessageUtil.getGuiMsg javadoc 校正；ChallengeCompleteGUI 注释校正
- **[P2 S8 / R-75]** 午夜任务改自递归一次性调度（每次触发后按墙钟重算次日 0:00:01 的 delay，DST 不再漂移；重载先 cancel 旧任务防 double-schedule；onDisable cancel）
- **[P2 S8 / R-86p]** 每日提醒占位符全量替换（{player}/{prefix}，含 config.yml 文案）、新增 join 提醒通道 `sendJoinDailyReminderIfEnabled`（enabled + on-join 双条件 + Map<UUID,LocalDate> 每日去重，与定时通道共享去重）；on-join 死配置接线
- **[P2 S8 / F0-08+T0-04+R-86o]** `general.check-updates` 开关接线（关=不连 GitHub API）；`loadPlayerLanguages` 缺省语言与 getPlayerLanguage 同源（en 残留消除）；REQUIRED_MESSAGE_KEYS 审计扩展至 11 活键（移除死键 word.correct/incorrect，新增 5 个核心流程键）
- **[P2 S8 / config]** config.yml 注释诚实化：check-updates / on-join 已接线、max-context-pairs 语义、modes.* 预留说明、challenges-per-day 与 rewards.* 如实标注"当前硬编码修改暂不生效"（键名数值零改动）
- **[P2 fan-in / R-86m]** 答对广播按收件人各自语言渲染（zh/en 各渲染一次按收件人语言取用，不再是答题者语言全员共用）；模板缺键 zh 兜底、两语言都缺才放弃
- **[P2 fan-in / R-78]** 跨服成就广播模板缺键兜底（两语言都缺才放弃，不再 NPE 半残）
- **[P2 fan-in / R-66 反向]** AI 对话中不可开新答题：`/le word`（LearnCommand.startWordQuiz）与词库选择 GUI（MiscGUIListener）补 dialogue.chat-active 守卫，与"答题中不可开对话"对称
- **[P2 fan-in / R-79 显示]** `/le challenge` 聊天侧行为挑战进度 clamp（min(progress,target)），与 GUI 侧同口径消除 "47/10" 显示；AchievementManager 解锁提示两处裸 getString 收敛 getGuiMsg；ChatAnswerListener 裸 getString 收敛；MainMenu 关于错题本槽位的陈旧注释校正
- **[P1 fan-in]** 单词挑战被动完成路径 R-44 对齐：`DailyChallengeManager.onAllWordChallengesComplete` 在 `money.enabled=false` 时改发 `daily-challenge.economy-disabled` 并标记已处理（避免每答对一题重入刷 `reward-pending`，默认配置即触发）；Vault 瞬时失败/超 cap 仍走 `reward-pending` 可重试
- **[P1 fan-in]** `WordLearningManager` 构造器自注册监听器加 `getServer()` 判空：R-13 死亡保护让本类 implements Listener 并在构造器 registerEvents，纯逻辑单测用 mock 插件构造（getServer 返回 null）会 NPE 炸测试；判空后测试环境跳过注册、生产不受影响
- **[P1 fan-in]** `ConfigManager.MESSAGE_CONFIG_VERSION` 9→10：messages/zh.yml 与 en.yml 随 P1 新增约 30 键升至 v10，代码侧同步升版
- **[P1 fan-in / R-49]** 场景/难度点击侧同序根治（原"点 A 开 B"）：`AIChatManager` 新增共享排序真相源 `getSortedScenarioConfigs/Keys(player)`（按当前语言展示名排序、key tie-break）与 `getSortedDifficultyConfigs/Keys()`（按 key 排序），`ScenarioSelectGUI` 渲染与 `ScenarioSelectListener` 点击映射统一调用同一方法；截断阈值用 GUI 新公开常量 `SCENARIO_SLOT_START/SCENARIO_SLOT_COUNT/DIFFICULTY_SLOT_COUNT`（难度 cap 由写死 3 收敛到该常量），两侧顺序与"更多…"位置恒一致
- **[P1 fan-in / R-49+R-53]** "更多…"提示槽点击置为 no-op（仅播 click 音，不开隐藏项）：`ScenarioSelectListener` 场景尾槽 16 / 难度尾槽 20、`MiscGUIListener.handleWordBankSelect` 词库尾槽 16 超限时命中提示物品不再按下标反算打开隐藏的第 7 场景 / 第 7 词库 / 第 3 难度
- **[P1 fan-in / R-55m]** 场景分享权限对齐：`ScenarioSelectGUI`（刷新 API 按钮可见性）与 `ScenarioSelectListener.handleReloadAPI`（执行门禁）两处 `letslearnenglish.admin` 改为 `letslearnenglish.scenario.import`，与 `LearnCommand` load_scenario 一致（持 import 权限者 GUI 侧也可见/可用该功能）
- **[P1 fan-in / R-47]** `MainMenuListener` 错题本点击判定由写死 `slot == 14` 改读 `cm.getGuiSlot("main-menu","wrong-book",14)`，与渲染侧 `MainMenu`（流 5 已改配置读取）对齐，自定义槽位后点击不再错位；同步更正该处"两边均写死"的过时注释
- **[P1 fan-in / R-34+F1-23]** `MiscGUIListener.depositChallengeReward` 由直连 Vault + 直调 `recordCoinsEarned` 改走 `RewardEngine.depositCappedReward`（纳入 `money.daily-cap` 与今日金币记账），返回实发额 `>0` 视为已付；失败 / 超限 cap 不标记 claimed、保持可重领语义
- **[P1 fan-in / L-05]** `EnglishSquareGUI` 与 `Leaderboard` 的 ALL_PLAYERS 分支 `getProfileSettings(id)` 改为只读 `getProfileSettingsReadOnly(id)`，枚举活跃玩家不再因 computeIfAbsent 写副作用建条目
- **[P1 fan-in / R-37]** `SocialListener` 无快照兜底重建分支（`squareGuis.get==null` 时按活跃度重建 playerIds）补隐身过滤：循环内 `getProfileSettingsReadOnly(id).isShowInSquare()==false` 直接跳过，与主装配口径一致
- **[P1 fan-in]** `AIChatManager.conversationCount` 死字段清理：R-28 后计数已落 `PlayerDataStore`，该内存字段仅剩声明与 `shutdown()` 清空、无读取者，一并删除
- **[P1 fan-in / R-55k]** `EnglishSquareGUI` 的 `progress-system.leaderboard.display-count` 代码 fallback 50→10，与 config.yml 默认值一致
- **[P1 fan-in / R-44]** `RewardEngine` 四个发币入口（答题 / 会话完成 / 复习答对 / 中途停止）在 `money.enabled=false` 但 Vault 存在时发 `progress.economy-disabled`，经新增 `Map<UUID,LocalDate>` per-player 每自然日去重一次（不再每答对一题刷屏）；`eco==null`（无 Vault）仍静默返回
- **[P1 fan-in / R-44]** `DailyChallengeManager.depositWordChallengeReward` 加 `money.enabled` 门：关闭时返回 false 走既有 reward-pending / economy-disabled 语义，不再静默吞奖励
- **[P1 修复流 1/6 / R-10]** choice 越界选项字母改"无效输入"：小词库只有 A/B 却输入 C/D 时不再当答错结算（不清连击/不扣分/不进错题本）；`AnswerChecker` 新增 `isInvalidChoiceInput`，`ChatAnswerListener` 在 submitAnswer 之前拦截并发 `word-learning-gui.invalid-option` 提示，计时器不取消，玩家可在剩余倒计时内重答
- **[P1 修复流 1/6 / R-56]** choice 判分先判全词精确匹配：单字母单词（如 "a"/"I"）文本与选项字母标签相撞时，`AnswerChecker.check` 让"输入=正确单词全文"先命中为对，不再把 "a" 误当成"选 A"
- **[P1 修复流 1/6 / R-11]** fill_blank 卡片加中文题面：物品名改用"当前单词: {中文}"（与 spelling/choice 卡一致），挖空例句下沉到 lore 第一行，只看 GUI 也知道考哪个词（槽位/材质不动）
- **[P1 修复流 1/6 / R-12]** 有复习通知却开不出局：`startReviewQuiz` 返回 null（词库删词致到期词已不存在）时 `LearnCommand.handleReviewCommand` 补 else 分支发 `review.unavailable`（"部分复习词已失效"），不再静默无事晃玩家
- **[P1 修复流 1/6 / R-13]** 答题倒计时中死亡被记错：`WordLearningManager` 自注册监听 `PlayerDeathEvent`/`PlayerRespawnEvent`（在 onEnable 内构造、仅一处 new，自注册合法，不改 LetsLearnEnglish），死亡时 `cancelAnswerTimer` 停表不判错（不扣分/不进错题本），QuizState 保留并标记死亡暂停，重生后下一 tick 重开答题 GUI 续答（关界面即按既有流程重启倒计时）
- **[P1 修复流 1/6 / R-14]** fill_blank 挖空正则命中屈折形例句：intermediate `delay`/`incline`/`behave` 与 advanced `concede`/`crave` 五条例句改用原形（`delay`/`incline`/`behave`/`concede`/`crave`），`AnswerChecker.blankSentence` 的 `\b原形\b` 正则可命中、不再回落光秃 `_____` 丢语境
- **[P1 修复流 1/6 / R-15]** 迟到答案不再静默吞：`ChatAnswerListener` 在 `submitAnswer` 返回 null（倒计时已判定/会话已结束，state 被移出或占用）时发 `word-learning-gui.answer-expired`（"本题已过"）
- **[P1 修复流 1/6 / R-57]** 超时答案格式对齐：`WordLearningManager.handleTimeout` 在 choice 模式下把"正确答案"按 `AnswerChecker.formatChoiceOption` 渲染为"B. apple"，与 submitAnswer 答错格式一致（buildResult 不动，仅传入已格式化的字符串）
- **[P1 修复流 1/6 / R-16]** AI 开场白走独立通道：`chatAsync` 抽出私有 `dispatchChat(...,countsTowardUsage)`，`triggerAiOpening` 以 `false` 调用——开场白不计有效输入、不计每日轮次、不查每日上限（F1-05 收尾）
- **[P1 修复流 1/6 / R-17]** in-flight 请求跨会话污染修复：成功回调按 session 隔离——仅当发起时的 sessionId 仍是该玩家当前 activeSession 才计本轮（`sessionHasAiReply`/`sessionValidInputs`/`incrementTodayAiRound`），下线/切场景后旧 in-flight 回调不再污染新会话计数与每日轮次
- **[P1 修复流 1/6 / R-18]** `startConversation` 补清 `pendingRequests`/`lastRequestTime`：GUI 连点两个场景时旧会话 in-flight 标记/冷却时间戳不再误拒新会话首条消息
- **[P1 修复流 1/6 / R-19]** 检查点与计数点重排序：有效输入计数移到成功回调（失败不计输入）；`markRequestTime` 移到所有拒绝检查（上下文/pending）通过之后（拒绝请求不再烧 3 秒冷却）
- **[P1 修复流 1/6 / R-20]** API 失败分类 + 401 熔断：`sendHttpRequest` 抛带状态码的 `AiApiException`，`classifyFailure` 区分 401/403（`dialogue.auth-failed` + 熔断 5 分钟不再打 API）/ 429（`dialogue.rate-limited`）/ 超时（`dialogue.timeout`）/ 其它（`dialogue.request-failed`）；成功 200 或 reload（含换 Key）清掉熔断；指数退避重试未做（可选项，留待后续）
- **[P1 修复流 1/6 / R-21]** 存量不可用 AI 挑战降级：`DailyChallengeManager.getTodayChallenges` 读取前调 `degradeStaleChallenges`，AI 不可用时把"未完成且未领取"的 AI_CHAT_5 标记 `completed=1,claimed=1`（reward 记 0，自动放弃不发奖），不再永久卡死拖垮当天整组挑战；已完成未领取的保留应得奖励不动（claim/分配/跨天/庆祝主流程不碰）
- **[P1 修复流 1/6]** i18n 新增 8 键（zh/en 双语同位）：`review.unavailable`、`dialogue.auth-failed`/`rate-limited`/`timeout`、`word-learning-gui.invalid-option`/`answer-expired`/`death-paused`/`death-resumed`
- **[P1 修复流 3/6 / R-28]** AI 对话成就重启丢失：`conversationCount` 由纯内存改为落 `player_data.conversation_count` 列（schema.sql + DatabaseManager 建表/补列同步）；`PlayerDataStore` 新增 conversation_count 缓存，join 随 `loadPlayerData` 载入、每次有效对话结束经 `incrementConversationCount` 即时 UPSERT 落库（不受 quit 事件两个监听器调用顺序影响）；`AIChatManager.endConversation` 计数改走 PlayerDataStore，`AI_CHAT_10/50` 成就重启后不再归零
- **[P1 修复流 3/6 / R-30]** `DAILY_STREAK_3/7` 差一天：`WordLearningManager.showSessionComplete` 把 `recordSession` 调到 `checkSessionAchievements` 之前，今日会话先落 `player_sessions` 再判定连续天数，`checkDailyStreak` 当天即算入，连续 3/7 天成就按时解锁（选 a 调序，判定逻辑不动）
- **[P1 修复流 3/6 / R-31]** 跨天丢失 pending 单词奖励：`DailyChallengeManager` 的"已付"标记由无日期 `Set<UUID>` 改为按日期 `Map<UUID,Set<String>>`，`onWordCorrect` 在今日外额外重试昨日"已完成但未到账"的奖励（答对任意单词即补发），午夜 rollover 的 `clearAllWordChallengeClaims` 改为裁剪早于昨日的标记而非全清，保留已付日期防重复补发（选 b 跨天可重试）
- **[P1 修复流 3/6 / R-32]** `updateProgress` 查无行静默丢失：SELECT 无行时先 `checkAndAssign` 补分配当日挑战再重查一次，确有该类挑战则推进；仍无行（该类型今日未抽中）才返回，不再丢失进度
- **[P1 修复流 3/6 / R-33]** 每日挑战领取槽位错位：`getTodayChallenges` 查询追加 `ORDER BY challenge_id`，展示（`DailyChallengeGUI` 槽位 11/13/15）与领取（`MiscGUIListener` 按下标映射）两侧顺序恒一致，不再领到错项（与 F2R-03 同根）
- **[P1 修复流 3/6 / R-34]** 挑战/签到发币绕过日限：`RewardEngine` 新增 public 门面 `depositCappedReward`（走 `clampToDailyCap` + `recordCoinsEarned` + `todayCoinsCache` 记账，只过 money.daily-cap 不过答题数门）；`DailyChallengeManager` 单词挑战发币与 `CheckInManager` 签到发币改走该门面纳入 `money.daily-cap`；F1-23 越层顺手收：`DailyChallengeManager` 不再直调 `DatabaseManager.recordCoinsEarned`
- **[P1 修复流 5/6 / R-46]** 主菜单 menu-size 非法回落：`gui.menu-size` 非 9/18/27/36/45/54 时回落 27 并记 WARNING，玻璃板填充循环按实际 size 走（原写死 27）
- **[P1 修复流 5/6 / R-47]** 主菜单错题本按钮接 `gui-layout.main-menu.wrong-book` 配置（slot/material，默认 14/BOOKSHELF），与其它 6 按钮一致
- **[P1 修复流 5/6 / R-48+R-52]** 8 个 GUI 的 `getString` 裸调加 null 兜底：经新增 `MessageUtil.getGuiMsg(msg, key)` 薄封装统一解析（静态、无日志，沿用调用方原有颜色转换），缺键显示 `[?key]` 占位不再让占位符替换/颜色转换炸界面（涉及 MainMenu/WordLearning/ScenarioSelect/Duel/AchievementWall/Progress/WordBankSelect/Confirmation；LearningCalendar 原已有 `""` 默认值不动逻辑）
- **[P1 修复流 5/6 / R-49]** 场景选择稳定排序 + 截断提示：场景按钮按展示名（当前语言，tie-break key）排序，难度按钮按 key 排序（点击侧 `ScenarioSelectListener` 需编排者 fan-in 同步改序，否则点 A 开 B）；超 7 场景/超 3 难度时在尾槽放"更多…"提示物品（`dialogue.more-scenarios`/`more-difficulties`，分页留 Phase 3）
- **[P1 修复流 5/6 / R-50]** 场景材质无空判：`sc.material` 为 null/LEGACY/AIR 时回退 PAPER（加载层已有校验，此为渲染层纵深防御）
- **[P1 修复流 5/6 / R-51]** 对决标题按可见字符截断：新增 `truncateTitleSafe`（stripColor 取可见文本计 32，& 颜色码保留不计数、绝不在 § 中间下刀），`open()` 与界面识别侧 `matchesTitle` 同口径（`DuelGUIListener` 经 `isDuelGUITitle` 委托到同一真相源，零改动）；标题与其余文案同步加缺 key 兜底
- **[P1 修复流 5/6 / R-53]** 词库选择 open() 时重查可用词库（构造快照改为仅兜底，新词库热重载后可见）；第 8 个词库起不再静默截断，尾槽放 `word-bank.more-banks` 提示行，翻页留 Phase 3
- **[P1 修复流 5/6 / R-54]** 空错题本 BOOK 展示位从 slot 22（WORD_SLOTS 内）移到 slot 4 顶部中央：点中央玻璃板无反应且不再误入单词分支（选型：纯 GUI 侧自包含移动，WrongBookListener 零改动；PDC/empty 特判留 Phase 3）
- **[P1 修复流 5/6 / R-55]** ChallengeCompleteGUI 头部注释补 slot 8/22 行为一致说明；庆祝页 slot 8/22 返回改走 `popBack`（有栈回栈、无栈回主菜单），与 DailyChallenge 一致（改动点在 `MiscGUIListener.handleChallengeComplete`）
- **[P1 修复流 5/6 / R-55e]** `SocialListener`/`MiscGUIListener` 新增 `onInventoryDrag` guard（抄 DuelGUIListener/MainMenuListener 上半区模式）：拖拽触及上半区即 cancel，玩家整理自己背包不受影响
- **[P1 修复流 4/6 / R-38]** 他人主页隐私：`ProfileGUI.open(viewer,targetId)` 非本人时查目标 `showResults` 开关，关闭则字数/正确率/场次三卡显示"已隐藏"；金币余额非本人一律不显示余额（隐私默认）；年龄与成就规则见 R-39；自己主页零改动
- **[P1 修复流 4/6 / R-39]** 年龄校验：`PlayerDataStore.setAge` 写侧钳制到 0~150（非法写 WARNING + 钳制），`ProfileSettings.getAge` 读侧越界回 0；`ProfileGUI` 年龄 0 显示"未设置"，非本人看他人主页一律不显示年龄（隐私默认）
- **[P1 修复流 4/6 / R-37]** 验证结论（零改动）：三条榜单 SQL（getTopCoins/getTopWordsLearned/getTopCombo）已 LEFT JOIN 过滤隐身者；`getRankedPlayers/getRankedPlayersForPeriod` 已过滤；`EnglishSquareGUI` ALL_PLAYERS 装配、`Leaderboard` ALL_PLAYERS 分支已跳过隐身；广场/榜单点击与 `/le profile` 直达均有门禁；唯一残留是 SocialListener 兜底重建分支缺装配过滤（展示与点击映射一致、无越权直达，风险低），因 SocialListener 不在白名单未修，已点名由编排者 fan-in 补
- **[P1 修复流 4/6 / R-43]** `LLEPapiExpansion.onRequest` 加隐私门禁：目标隐身（showInSquare=false）非榜单占位符返回 `placeholder.private`，`rank` 返回未上榜文本；`lle_score/words/accuracy/sessions/correct/incorrect` 叠加目标 showResults=false 检查；`coins_earned` 非本人查看返回 private（与 ProfileGUI 金币规则对齐）；全占位符加 ConcurrentHashMap 缓存（key=uuid+params+查看者语言/关系Bucket，TTL 30s，超 5000 条随机淘汰），计分板刷新不再每秒打库
- **[P1 修复流 4/6 / R-44]** `CheckInManager.CheckInResult` 加 `economyDisabled` 字段并在 `giveCoins` 的 money.enabled=false 路径置位（发币日限是流 3 的，未碰）；`LearnCommand` 签到成功分支追加 `checkin.economy-disabled` 提示，`/le challenge` 的 reward-pending 分支加经济可用性检查（money.enabled=false 或无 Vault 时发 `daily-challenge.economy-disabled`，替代"稍后补发"承诺；庆祝页本身已有 allDone&&paid 双门禁，paid=false 时天然不开，无需再动）；答题/复习/挑战到账侧的提示在 RewardEngine/DailyChallengeManager（禁区），已点名由编排者 fan-in 按最小方案补（RewardEngine 四发币入口 `isMoneyEnabled` 早退处发 `progress.economy-disabled` 去重一次；DailyChallengeManager.depositWordChallengeReward 加 moneyEnabled 检查）
- **[P1 修复流 4/6 / R-45]** 新玩家进服指引：`PlayerJoinListener` 中 `Bukkit.hasPlayedBefore=false` 的纯新号发 `join.welcome-1~3` 三行欢迎语 + `/le` 指引（老玩家零打扰）；UpdateChecker 延迟 notify 加 `isOnline` 复查（L-20 后半顺手收）
- **[P1 修复流 4/6 / L-05]** `PlayerDataStore.getActivePlayerIds` 改走"profile 缓存键 ∪ 当前在线玩家"（在线/离线全覆盖，quit 清理语义不变）；新增只读 `getProfileSettingsReadOnly`（缓存缺失返回默认副本不建条目），白名单内展示路径 `ProfileGUI.open`/`LLEPapiExpansion` 改走只读版；`EnglishSquareGUI`/`Leaderboard.ALL_PLAYERS` 的同类调用在禁区未动，已点名由编排者 fan-in 补（各一行，改读只读版即可）
- **[P0 fan-in]** `ConfigManager.MESSAGE_CONFIG_VERSION` 8→9：messages/zh.yml 与 en.yml 随 P0 新键（`profile.hidden`）升至 v9，代码侧同步升版
- **[P0 fan-in]** R-01 补漏：ChatHistoryListener / ScenarioSelectListener / WordLearningListener / WrongBookListener 四处同类不分区残留（流 1 上报）按统一模式补 Top 容器判断，下半区直接 return 不 cancel
- **[P0 fan-in]** R-05 补漏：`PlayerDataStore.getRankedPlayers/getRankedPlayersForPeriod` 加 `LEFT JOIN player_profiles` 隐身过滤；`EnglishSquareGUI` ALL_PLAYERS 装配与 `Leaderboard` ALL_PLAYERS 分支跳过隐身玩家（流 4 上报，PlayerDataStore 禁区残留）
- **[P0 修复流 2/6 / R-02]** 行为挑战领取防重：`handleDailyChallenge` 领取分支改走 per-player 条纹锁串行化（固定 32 槽位、无 map 增长、无需 quit 清理），锁内重读最新领取态后再执行"判定→转账→标记"全程持锁，任何时序下同一挑战只发一次钱；转账失败仍不标记、走既有 `daily.claim-failed` 可重领（M12 语义不变）
- **[P0 修复流 2/6 / R-02]** `DailyChallengeManager.claimReward` 加同构 per-player 条纹锁串行化（guarded `UPDATE ... AND claimed=0` 保留为第二道防线）；listener 锁与 manager 锁恒定 L→M 顺序嵌套、无逆序路径，不死锁
- **[P0 修复流 2/6 / R-07]** `/le challenge` 完成/到账双条件：两处 `completed>=total` 判定追加 `isWordRewardPaidToday` 已到账检查，全完成但未到账时改发既有 `daily-challenge.reward-pending` 提示且不开庆祝页；`DailyChallengeManager` 新增 `isWordRewardPaidToday(UUID)` 只读查询（读已领取标记、零 DB 开销），`hasPendingWordReward` 保持 private 不动
- **[P0 修复流 5/6 / R-06]** `WordBank.loadWords` 加必填校验：`english`/`chinese` 缺失、非 String 或空白的词条整条跳过并记 WARNING（词库文件名 + words[序号] + 坏字段原因），不再 `new Word(null, …)`；可选字符串字段非 String 时按 null 宽容处理，不再抛 ClassCastException
- **[P0 修复流 5/6 / R-06]** `WordManagerImpl.loadAllWordBanks` 降级：校验后 0 有效词的词库整档跳过不注册（该难度不可选，玩家走既有未知难度错误提示），控制台记 SEVERE；全档皆空时额外 SEVERE 告警但不炸服
- **[P0 修复流 5/6 / R-03]** `/lea reset` 删表补全：PLAYER_TABLES 由 4 表扩至 12 表（新增 player_sessions、player_profiles、player_achievements、daily_challenges、daily_challenge_words、ai_chat_history、difficult_words、checkin_data），后 4 表按 `player_uuid` 列删；player_achievements 虽为死表（E-03）仍一并清理免留残行
- **[P0 修复流 5/6 / R-03]** `/lea reset` 内存态按 quit 语义清理：先 endQuiz/cancelPractice（在线答题中 reset 不留僵尸计时器），再 unloadPlayerData + unloadProfileSettings + AchievementManager.unloadPlayer（成就缓存不清会导致"成就复活"），最后执行删表（顺序不可反，unload 会先落盘）
- **[P0 修复流 5/6 / R-03]** `PlayerDataStore.unloadPlayerData` 补清今日答题/AI 轮次计数器，reset 与 quit 后防刷上限即时归零（原路径遗漏）
- **[P0 修复流 6/6 / R-08]** 学习日历网格锚点改锚 today：`gridStart` 取 today 所在周周一往前推 4 周（`today.with(MONDAY).minusWeeks(4)`），35 格恒覆盖 [today-34, today]，尾部最近几天与 today-marker 不再被 `isAfter(today)` 丢弃（仅修锚点，不动 filler/配色/图例）
- **[P0 修复流 6/6 / R-09]** 二确 targetLang 收敛到 per-player 存储：`confirmStages` 由 `Map<UUID,Integer>` 改存 (stage, targetLang) 二元组，阶段 1 落盘目标语言，阶段 2 点击与 `applySwitch` 读存储里的语言而不再从"当前语言反推"（中途语言变化不再切错方向；取不到存储时回退反推保底）
- **[P0 修复流 3/6 / R-09b]** 广场/排行榜跨玩家串号根治：`SocialListener` 的 `lastSquareGui`/`lastLeaderboardGui` 全局单例字段改为按 viewer UUID 分键的 `Map<UUID, ...>`，B 的点击不再复用 A 的玩家列表；新增 `PlayerQuitEvent` 清理该玩家条目防泄漏
- **[P0 修复流 3/6 / R-09d]** 隐身笑话页点药水串主页：`handleEnglishSquareGUI` 开头按 inventory 尺寸分流（27 格=笑话页 / 54 格=广场主页），笑话页 slot 13 药水只发 `square.hidden-lore` 聊天提示、绝不打开任何玩家主页，其余槽位忽略
- **[P0 修复流 3/6 / R-09c]** 词库点 A 开 B：`WordManagerImpl.getAvailableDifficulties()` 改返回 `TreeSet` 稳定排序快照，GUI 构造快照与点击侧重取两侧迭代顺序恒一致（选型 a，一行排序根治；`WordBankSelectGUI`/`MiscGUIListener` 零改动）
- **[P0 修复流 3/6 / R-36]** 广场尾页卡死：`EnglishSquareGUI` 分页按钮拆除 `pageFull` 联合门禁，上一页只看 `page>1`、下一页只看 `page<totalPages`，尾页不满也能正常回翻
- **[P0 修复流 / R-01+R-22]** 四个 GUI 监听器点击分区：`MiscGUIListener` / `DuelGUIListener` / `MainMenuListener` / `SocialListener` 的点击入口统一只处理上半区（Top 容器），玩家背包（下半区）点击直接 return；`setCancelled(true)` 移到确认上半区之后，下半区不再 cancel，玩家开着 GUI 也能正常整理背包
- **[P0 修复流 / R-01]** 堵住两条高危误触发：DailyChallenge 界面点背包 11/13/15 不再领奖发钱；对决中点背包 22 不再误触退出判负
- **[P1 修复流 2/6 / R-23]** 新增 `/le duel gui` 重开命令：决斗中 ESC 关界面后可重开当前对局 GUI（含 tab 补全），未在对决中回既有 `duel.not-in-duel`；`refreshBothGUIs` 改为仅当玩家正看着决斗 GUI 时刷新（经 `DuelGUIListener.isViewingDuelGUI` 复用点击分区同源标题判定），ESC 后每题不再强制弹回界面
- **[P1 修复流 2/6 / R-24]** 赛前警告：`duel.started` 文案追加"决斗中所有聊天都算作答案"警告句；对决 GUI 中央单词物品 lore 加同警告一行（新键 `duel.chat-counts-warning`，zh/en 双语）
- **[P1 修复流 2/6 / R-25]** 超时/锁题后迟到答案不再静默吞：`submitAnswer` 的 `ALREADY_ANSWERED` 分支向答题者本人回新键 `duel.round-ended`（"本题已结束"）提示，不广播
- **[P1 修复流 2/6 / R-26]** 宽限计时器"对局已无"分支补 `task.cancel()`：从 `graceTasks` 取出任务后取消，不再留下每秒空转的泄漏任务
- **[P1 修复流 2/6 / R-27]** `sendDuelRequest` 加答题忙态对称检查：发送者 quiz/练习中回新键 `duel.sender-answering`，目标答题中复用既有 `duel.target-answering`，与 `acceptDuel` 的 M4 门禁对称，挑战发出去前即拦截
- **[P1 修复流 6/6 / R-40]** `/le challenge` 庆祝页顶屏三守卫：决斗中（复用 `duel.finish-duel-first`）/答题中（quiz+错题练习，新键 `daily-challenge.celebrate-busy`）只刷聊天不弹页；同一玩家同一自然日只自动弹一次，重复执行聊天末尾追新键 `daily-challenge.celebrate-shown-today`
- **[P1 修复流 6/6 / R-55d]** `MainMenuListener.handleMainMenu` 槽位写死改读 `gui-layout.main-menu` 配置（`getGuiSlot` + 与渲染侧同 fallback 值，switch 改 if-else 链），slot 14 错题本两边写死沿用魔法数字（config.yml 不新增 key）
- **[P1 修复流 6/6 / E-04]** `DatabaseManager.getConnection()` 整体收进 `connectionLock` 内（原锁外快路径删除，锁自愈重连保留）；`getLast7DaysStats` 首锁块开头/次锁块开头各锁内重取引用；外部 23 处锁外 `getConnection()` 留编排者 fan-in
- **[P1 修复流 6/6 / E-05]** `DatabaseManager.initialize()` 重连先关旧连接（判 null + `isClosed`，异常吞掉记 WARNING），`getConnection` 自愈路径重入不再泄漏旧句柄
- **[P1 修复流 6/6 / R-42]** `%lle_rank%` 冻结根治（选型 a）：`WordManagerImpl.endSession` 结算时调 `Leaderboard.updateScore(playerId, +session分)` 增量同步内存榜，`getPlayerRank` 即时反映本次结算（`updateScore` 语义为 merge-累加，调用点传增量而非全量）；`loadFromDB` 仍为启动快照
- **[P1 修复流 6/6 / R-42 附带 E-10]** `Leaderboard.loadFromDB` 的 `getRankedPlayers(Integer.MAX_VALUE)` 改为 `RANK_SNAPSHOT_LIMIT = 1000`（下游 `getPlayerStats` N+1 逐人补读也被截断在千行内；读时查库的大改留 Phase 3）
- **[P1 修复流 6/6 / R-55o]** `DailyChallengeManager.ChallengeType.COMBO_5` target 1→5（"达到 5 连击"语义对；触发路径 `completeChallenge` 二进制完成不受影响，该值仅用于聊天/GUI 显示的分母）
- **[P1 修复流 6/6 / R-55l]** 默认语言 fallback 打架统一为 zh：`ConfigManager` 启动语言与 `MessageUtil` 三处 `general.language` 缺 key 回落由 `en` 改 `zh`（与 yml 默认 + `getPlayerLanguage` 的 zh 回落对齐）；`getSenderMessage` 控制台路径 `en` 不动（T0-04 同根一并收）
- **[P1 修复流 6/6 / R-55k]** 注释对齐：`config.yml` 的 `progress-system.leaderboard` 注释说明 `display-count` 生效口径（缺 key 回落见代码注释，缺 key 告警以启动审计为准）；代码 `EnglishSquareGUI` fallback 50→10 的一行改动点名编排者 fan-in（文件禁区）
- **[P1 修复流 6/6]** i18n 新增 2 键（zh/en 双语同位）：`daily-challenge.celebrate-busy`、`daily-challenge.celebrate-shown-today`
- **[P0 流 4 / R-05]** 排行榜点击直达加 `showInSquare` 检查：`SocialListener.handleLeaderboardGUI` 点开主页前先查目标开关，隐身者进 `openHidden` 笑话页（与广场点击分支同语义）；看自己不受影响
- **[P0 流 4 / R-05]** `/le profile <玩家>` 加 `showInSquare` 检查：目标隐身时回 `profile.hidden` 聊天提示、不再开界面；看自己不受影响
- **[P0 流 4 / R-05]** 三条榜单 SQL（`getTopCoins`/`getTopWordsLearned`/`getTopCombo`）`LEFT JOIN player_profiles` 过滤 `show_in_square=0`，无档案行视为默认可见；SQLite/MySQL 同一写法双后端生效
- **[P0 流 4 / R-04]** `ScenarioSelectGUI` 场景列表改按玩家版 `getScenarioConfigs(Player)`，个人场景出现在列表（展示名与全局同规则取 `display_name`/`display_name_zh`）
- **[P0 流 4 / R-04]** `ScenarioSelectListener` 场景点击索引改按玩家版 `getAvailableScenarios(Player)`（与 GUI 同序）；`formatScenarioName` 改按玩家解析（走 `getScenarioDisplayName(Player, key)`），个人 key 显示展示名
- **[P0 流 4 / R-04]** `/le dialogue <场景>` 回显改按玩家版 `getScenarioDisplayName(Player, key)`，个人 key 显示展示名；tab 补全改按玩家版 `getAvailableScenarios(Player)`（控制台回落全局列表）
- **[P0 流 4]** i18n 新增 `profile.hidden`（zh/en 双语同位新增）；需编排者同步 bump `ConfigManager.MESSAGE_CONFIG_VERSION` 已部署服才能收到新键

### Fixed
- **[Phase 2 fan-in]** 合流测试建议顺手收：`PlayerDataStore.clearCache()` 补 `playerNameCache.clear()`，reload 后不再把旧名写回 `player_data.player_name`（改名场景）
- **[Phase 2 fan-in]** 合流测试建议顺手收：决斗邀请 60s 过期任务改 `remove(key, value)` 原子校验，旧过期任务不再误清 60s 内发给同一目标的新邀请（与 F1-15 同源）
- **[Phase 2 fan-in]** `ConfigManager.MESSAGE_CONFIG_VERSION` 7→8：messages/zh.yml 与 en.yml 随 Phase 2 新增键（决斗超时/GM 解散/挑战可见性/真三模式/跨天/TTS 冷却）升至 v8，代码侧同步升版，已部署服务器重启/重载后才会覆盖下发新消息文件
- **[Phase 2 fan-in]** 庆祝去强制化收尾：`DailyChallengeManager.onAllWordChallengesComplete` 尾部强制弹 `ChallengeCompleteGUI` 改为发送 `daily-challenge.reopen-hint` 聊天提醒，玩家按需 `/le challenge` 重开庆祝页（流 C 点名的禁区文件配合）
- **[Phase 2 fan-in]** `RewardEngine` 的 `review-multiplier` 代码 fallback 默认值 0.5→1.2，与 config.yml 新默认对齐（已部署旧服缺 key 时不再走旧值；流 A 上报的已知残留）
- **[Phase 2 复活流 C / FIX-DUEL-01]** 对决单题超时：`DuelRoom` 新增单题超时（默认 30s，读现有 `word-system.answer-timeout`），超时双方都不加分、直接下一题；`DuelManager` 每题调度计时任务，判分后取消旧任务
- **[Phase 2 复活流 C / FIX-DUEL-01]** GM 解散：新增 `/lea duel cancel <玩家>`（`letslearnenglish.admin` 权限，控制台可用），强制解散对局不判胜负；`/le duel cancel <玩家>` 仅 GM 可执行（同权限二次校验），非 GM 提示无权限
- **[Phase 2 复活流 C / FIX-DUEL-01 / F1-15]** `acceptDuel` 开头延后吞邀请：`pendingRequests.remove` 移到全部校验之后，校验失败不再吞掉对方邀请
- **[Phase 2 复活流 C / FIX-CHAL-01]** 挑战双向可见：`/le challenge` 聊天列表追加行为挑战 3 项进度（只读）；`DailyChallengeGUI` 追加单词挑战进度行（只读）；领取路径各自不变
- **[Phase 2 复活流 C / FIX-CHAL-01]** 庆祝去强制化：单词挑战全完成时不再强制弹庆祝 GUI，改为聊天提醒 + 玩家手动 `/le challenge` 重开庆祝页（调用点需流 A / 编排者 fan-in 配合，见报告文件冲突点名）
- **[Phase 2 复活流 C / FIX-CHAL-01 / F1-09]** 庆祝按钮去装饰化：slot 22 改为"奖励已到账，点击返回主菜单"的真实语义，不再误导玩家以为要点才到账
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
- **[Phase 2 流 A / FIX-REVIEW-01 / H5]** 普通学习结算写复习种子：`endSession` 非 review 模式下对本次答过的词调 `seedReview`（仅无种子时写入 `lastReviewTime=now`、`reviewCount=0`，已有种子/已有复习进度不动），新玩家学完首轮后 `getDueReviewWords` 到期即非空
- **[Phase 2 流 A / FIX-REVIEW-01]** 复习结算只对答对词 `recordReview`（间隔晋级）；答错词调 `resetReview`（`reviewCount` 归零、`lastReviewTime=now`，下次 due = now + intervals[0]，1h 后再提醒）；读侧 `getDueReviewWords` 不动逻辑（种子刚写入未到期不 due、答错重置后按 intervals[0] 计时，已验证两种语义恰当）
- **[Phase 2 流 A / FIX-REVIEW-01]** 复习通知"今日已提醒"去重：`checkAndNotifyReviews` 对同一玩家同一自然日内只提醒一次（内存态 `lastNotifiedDate`，跨天自动失效）
- **[Phase 2 流 A / FIX-BOARD-01 / H8]** `endSession` 学习结算调 `PlayerStats.addScore(session.getScore())` 累加会话学习分，`total_score` 落盘不再冻结（仅学习分口径，金币仍走独立 `total_coins_*`）
- **[Phase 2 流 A / FIX-BOARD-01 / H10]** `player_name` 落盘：`PlayerJoinListener` 进服写 `PlayerDataStore` 内部名字缓存（`cachePlayerName`，超长截 32 字符），`savePlayerData` 的 player_data UPSERT 携带 `player_name` 列，排行榜人名不再回落为 UUID
- **[Phase 2 流 A / FIX-BOARD-01 / M9]** `checkStreakAchievement` 每次连击推进时取 max 落盘 `longest_streak`（INSERT 保行 + 条件 UPDATE 只增不减），连击榜不再全 0；读侧走既有 `DatabaseManager.getTopCombo`/`ProgressManagerImpl.loadStreakData`，零改动
- **[Phase 2 流 A / FIX-BOARD-01 / M10]** `PlayerStats.getTotalWords()` 口径改为 `totalCorrect`（答对数），`loadPlayerData` 重建与 `savePlayerData` 落盘两侧一致使用该方法，单词榜不再把答错也计入学过
- **[Phase 2 流 A / FIX-DAY-01 / M8]** 新增跨天定时任务（`LetsLearnEnglish.scheduleMidnightRollover`，启动对齐下个午夜、每 24h 触发）：在线玩家重 `checkAndAssign`、清单词挑战已领标记与复习提醒态；昨日未领奖励作废（行为挑战数据天然按日期隔离），玩家会收到 `daily-challenge.rollover-voided` 提示
- **[Phase 2 流 A / FIX-DAY-01]** `checkAndAssign` 分配时过滤不可用挑战：无 AI Key 服务器不再抽到 `AI_CHAT_5`（抽中时整体重抽一次，最多重试 5 轮）
- **[Phase 2 流 A / FIX-DAY-01 / M8 附带]** `wordChallengeClaimedToday` 实为 Phase 1 流 C 的"已领取标记"（有 `hasPendingWordReward` 读取），保留并改为线程安全实现 + 新增 `clearAllWordChallengeClaims()` 供跨天任务午夜清理
- **[Phase 2 流 A / FIX-NUM-01]** 复习数值反转：`config.yml` 中 `review-multiplier` 默认 0.5→1.2（复习应比首学更值钱）、`review-penalty` 默认 true→false（答错不再倒扣，用错题本承载纠错），附中文注释说明原因；Java 逻辑零改动
- **[Phase 2 流 A / FIX-TTS-01 / M20]** 发音按钮加 per-player 5 秒冷却：冷却内点击走 `word.pronunciation-cooldown` 提示且不调 API；冷却时长读 `tts.pronounce-cooldown-seconds`（新增 config key，默认 5）
- **[Phase 2 流 A / FIX-TTS-01 / M20]** `TtsManager` 落盘前校验 `Content-Type` 必须为音频类型（`audio/*`、`application/octet-stream`、空头放行），错误文本（`text/*` 如 VoiceRSS 的"ERROR: ...")不再毒化 `.wav` 缓存；7 天过期逻辑保留
- **[Phase 2 复活流 B / FEAT-MODE-01]** choice/fill_blank 模式不再走"聊天栏拼写全词精确匹配"：`submitAnswer` 判分收敛到新 `AnswerChecker`（spelling 原样保留全词拼写；choice 判选项字母 A/B/C/D；fill_blank 判挖空填词），答错/超时反馈在 choice 下展示"字母. 单词"
- **[Phase 2 复活流 B / FEAT-MODE-01]** `SPELLING_MASTER` 收紧：仅 spelling 模式且本会话至少拼对 1 题才解锁，挂机超时/全错不再能拿
- **[Phase 2 复活流 B / FEAT-MODE-01]** `/le word <难度> <模式>` 增加 mode 参数校验，非法 mode 回 `word.invalid-mode` 提示（此前直接抛 IllegalArgumentException 原始英文信息）

### Added
- **[Phase 2 复活流 B / FEAT-MODE-01]** 新增 `core.word.AnswerChecker`：三模式判分/选项生成/挖空渲染的唯一收敛点（`check`/`buildChoiceOptions`/`blankSentence`/`formatChoiceOptions`），review/决斗/错题练习行为不变
- **[Phase 2 复活流 B / FEAT-MODE-01]** `WordLearningGUI` 按 mode 渲染：choice 展示 4 选项（1 正确 + 3 同词库随机干扰项，正确项不加亮）、fill_blank 展示挖空例句 + 首字母提示；答题提示按模式分流（`answer-prompt-choice`/`answer-prompt-fill-blank`）
- **[Phase 2 复活流 B / FEAT-MODE-01]** choice 确认为新手默认模式：`/le word` 缺省与 GUI 入口均为 choice，spelling/fill_blank 仍可显式指定
- **[Phase 2 复活流 B / FEAT-MODE-01]** i18n 新增 7 键（zh/en 双语同位）：`word.invalid-mode`、`word-learning-gui.answer-prompt-choice`/`choice-option`/`answer-prompt-fill-blank`/`fill-blank-sentence`/`fill-blank-hint`/`current-word-lore-choice`
- **[测试安全网]** 新增 `WordLearningManagerComboTest`：以纯逻辑单测锁定连击倍率表（0~2 连=1.0x、3~4=1.5x、5~6=2.0x、7~9=2.5x、≥10=3.0x，含负数回落 1.0x），直接测真实的 `WordLearningManager.getComboMultiplier(int)`（倍率表实际在此类，非 RewardEngine），构造器用 Mockito mock 的插件实例满足，不拉起任何 Bukkit 运行时
- **[测试安全网]** JaCoCo 新增 `check` 门禁（绑定 `verify` 阶段，BUNDLE 行覆盖最低 0.01=1%）：这是起步地板，当前行覆盖约 4%，后续逐版本上调；故意不在 `package -DskipTests` 快速构建触发，运行 `mvn verify` 才校验
- **[Phase 0 安全基线]** `MessageUtil` 新增 null-safe 取消息 helper：`getMessageSafe(key, ...)` / `getPlayerMessageSafe(player, key, ...)` 在 message 配置缺失、key 缺失或占位符为 null 时，统一记 WARNING 并回落到可见诊断占位串 `&c[?key]`，绝不返回 null 或抛 NPE；配套 `hasMessageKey(lang, key)` 供启动审计使用。仅新增 API、不重构现有调用点，不改既有行为
- **[Phase 0 安全基线]** `ConfigManager.validateRequiredKeys()`：插件启动时（`onEnable` 载入 config 与 messages 后调用一次）扫描预期必填的 config key 与 i18n key，缺失则打 WARNING（`[Config Audit]` / `[i18n Audit]`）。纯告警，绝不改控制流、不禁用任何功能、不改写配置
- **[Phase 0 安全基线]** 新增 `tasks/dead-config-audit.md` 死配置审计清单：以全路径 grep 查证，确认 `word-system.modes.*`（H6：整棵子树死，模式硬编码于 `WordManagerImpl.VALID_MODES`）、`word-system.daily-limit`、`daily-challenges.rewards.*` / `challenges-per-day`、`difficulty-settings.*`、`proxy.*` 等共 21 条必查死配置，另记录约 17 条额外死配置（含模块级 `enabled` 开关族从不 gating）。仅审计不删除
- **[Phase 0 词库流水线]** 新增 `scripts/gen-words.js` 词库生成与自检脚本（零第三方依赖，位于 resources 之外不进 JAR；支持 `generate` 与 `check <file>`，镜像 `WordBankValidator` 规则并校验 YAML 往返一致性），并产出 50 词 beginner 评审样本 `resources/words/sample-beginner.yml`（未激活，pom 已排除出 JAR；待人工抽查音标后提升为正式词库）；`resources/words/README.md` 记录词表来源与许可
- **[Phase 0 安全基线]** 新增 `tasks/db-baseline.md` 数据库基线：`schema.sql` 与代码建表语句逐表逐列对齐（12 表 + 2 索引，无字段漂移/无缺列/无缺索引）、`player_name` 写入方案（仅方案，Phase 1 落地）、`synchronized` 串行化 vs 单线程 DB 执行器的评估与升级建议，并记录外部类仍裸用 `getConnection()` 的残余缺口
- **[Phase 2 CONTENT-02 词库扩充]** `beginner.yml` 扩至 317 词（原有 10 词保留 + 50 词评审样本全数并入 + 新增 257 词；difficulty_level 分布 1/2/3=255/36/26），`sample-beginner.yml` 并入后删除；`intermediate.yml` 扩至 349 词（4/5/6=134/146/69）；`advanced.yml` 扩至 330 词（7/8/9=125/134/71，顺带修复 `elaborate` 的 `part_of_speech: "adjective/verb"` 非法斜杠值→`verb`）；三档自检均为 0 error / 0 warning 且跨档零重复词头

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
