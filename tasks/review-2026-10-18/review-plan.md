# 全仓审查计划（review-plan）

- 日期：2026-10-18
- 基线：`3bf8eae`（mvn clean verify 全绿：49 测试 + jacoco 门达标；i18n 审计 521 引用键，唯一告警为脚本误报）
- 性质：本文件是 13 路审查 Agent 的作战地图。每个 Agent 先读 `dispatch-template.md`（公共纪律+输出格式），再读本文件 §A-§E 中自己域的部分，然后动手。
- 材料来源：规划 Agent 卡死（~1h 零落盘）后由编排者接管直写；事实基础 = git 热点分析 + dev-findings 全账册 + final-review 三盲区模式 + accepted-deviations + 源码结构侦察。

---

## §A 域拆分结论（13 域，文件清单精确到文件）

> 口径：审查对象 = `src/main/java`（87 文件 / 32,273 行）+ `src/main/resources`（config.yml / messages / words / schema.sql / ai_scenarios.yml / achievements-config.yml / plugin.yml）+ `src/test` + `pom.xml` + `scripts/`。`tasks/`、`ralph/`、`docs/` 非代码不在审查面（引用它们做背景可以）。
> 文件清单先行——**只审清单内文件**；越界发现有价值时写"范围外"节。

### R1 学习核心链
文件：`core/word/` 全部（AnswerChecker, LearningSession, ReviewScheduler, Word, WordBank, WordBankValidator, WordLearningManager, WordManagerImpl, WordModes）、`listener/ChatAnswerListener.java`、`listener/WordLearningListener.java`、`gui/WordLearningGUI.java`、`gui/WordBankSelectGUI.java`、`gui/WrongBookGUI.java`、`listener/WrongBookListener.java`、`resources/words/beginner|intermediate|advanced.yml`、`resources/words/README.md`

### R2 AI 对话链
文件：`core/ai/` 全部（AIChatManager, ChatFeedbackManager, ConversationFeedback, PersonalScenarioStore, ScenarioCodec, TtsManager）、`listener/ChatScenarioListener.java`、`listener/ChatHistoryListener.java`、`gui/ChatHistoryGUI.java`、`gui/ScenarioSelectGUI.java`、`listener/ScenarioSelectListener.java`、`gui/PersonalScenarioGUI.java`、`listener/PersonalScenarioListener.java`、`resources/ai_scenarios.yml`

### R3 数据层
文件：`data/` 全部（DatabaseManager, PlayerDataStore, ChatFeedbackEntry, ChatHistoryEntry, CheckInData, DifficultWord）、`resources/schema.sql`、`core/manager/DataExporter.java`

### R4 社交+成就+榜单+档案
文件：`listener/SocialListener.java`、`core/social/`（SocialManager, FeedTextFormatter）、`gui/EnglishSquareGUI.java`、`core/achievement/`（Achievement, AchievementManager）、`gui/AchievementWallGUI.java`、`gui/ProfileGUI.java`、`gui/LeaderboardGUI.java`、`core/progress/`（Leaderboard, PlayerProgress, ProgressManagerImpl, RewardEngine）、`resources/achievements-config.yml`

### R5 决斗
文件：`core/duel/`（DuelManager, DuelRoom）、`gui/DuelGUI.java`、`gui/DuelChallengeGUI.java`、`gui/ChallengeCompleteGUI.java`、`listener/DuelGUIListener.java`、`listener/DuelChallengeListener.java`、`listener/DuelListener.java`

### R6 每日挑战+签到+日历
文件：`core/DailyChallengeManager.java`、`gui/DailyChallengeGUI.java`、`core/manager/CheckInManager.java`、`gui/LearningCalendarGUI.java`

### R7 赛季+跨服+班级+感官反馈+新手引导
文件：`core/season/SeasonManager.java`、`core/ProxyMessenger.java`、`gui/ClassBoardGUI.java`、`listener/ClassBoardListener.java`、`core/SoundManager.java`、`core/feedback/CountdownTier.java`、`core/onboarding/OnboardingManager.java`、`listener/PlayerJoinListener.java`

### R8 玩家命令+菜单导航
文件：`command/LearnCommand.java`、`gui/MainMenu.java`、`listener/MainMenuListener.java`、`listener/MiscGUIListener.java`、`gui/GUIUtils.java`、`gui/ConfirmationGUI.java`、`core/GuiNavigationManager.java`

### R9 管理面
文件：`command/AdminCommand.java`、`core/manager/AdminCommandHandler.java`、`core/manager/ReportGenerator.java`、`core/manager/StatisticsHttpServer.java`

### R10 基础设施与接线
文件：`LetsLearnEnglish.java`、`config/ConfigManager.java`、`util/MessageUtil.java`、`util/VersionAdapter.java`、`core/manager/HotReloadManager.java`、`core/UpdateChecker.java`、`api/LetsLearnEnglishAPI.java`、`expansion/LLEPapiExpansion.java`、`pom.xml`、`resources/plugin.yml`、`scripts/audit-i18n-keys.js`

### R11 UX 旅程专项（跨域）
不占用专属文件（静态走查 + 只读检索全仓）；产物为一幅"旅程地图"式的体验与逻辑审查。与 R12 的分工：R11 走"玩家视角旅程穿行"，R12 走"状态机/竞态穿行"。

### R12 操作逻辑与状态机专项（跨域）
状态机清单：QuizState（WordLearningManager）、ChatSessionState/AI 会话（AIChatManager）、Onboarding step 0/1/2/3、Duel（invite/active/grace/ended）、Challenge claim、CheckIn、Season settlement、GUI openAsync in-flight、ConfirmationGUI。逐状态机画转移图（进入/退出/取消/超时/断线/关服/重入），找不完备转移。

### R13 横切面卫生（跨域）
线程纪律全仓扫描、资源与内存增长、i18n 硬编码与对称、死配置/死键反向扫描、安全面（权限校验/注入/路径穿越/SQL）、性能热点（主线程 IO、循环内查询）、死代码与重复代码、测试质量（8 个测试文件是否测到真风险）。

---

## §B 每域查什么

### B-R1 学习核心链
风险假设：
- RH1 计分/连击在边界漂移：超时记错、答错重置、连击乘数 off-by-one（近期 F0-12 冗余守卫删除过，回归核验）
- RH2 QuizState 定时器在 GUI 关闭/会话结束/断线路径未 cancel → 幽灵题目/幽灵计时器（照 DuelManager 僵尸任务先例）
- RH3 错题本三入口（GUI 左键删/Shift 练习/listener）行为漂移；SP 练习消费聊天输入与 R2 域竞争
- RH4 复习调度 Ebbinghaus 间隔计算 + 通知去重（clearNotifiedDates 与运行时通知交互）
- RH5 词库加载（mastered 过滤、难度切换、reload 中）与 in-flight 会话交互
- RH6 AnswerChecker 判定边界：大小写/前后缀（a/an/the）/多解/全角半角/unicode
- RH7 会话入库时序（recordSession UPSERT + word_progress REPLACE 语义）与成就/挑战计数联动是否有双重计数或漏计
- RH8 ChatAnswerListener 消息拦截范围与 ChatScenarioListener/ChatHistoryListener 的竞争（谁 cancel、谁先收到）
- RH9 词库内容质量：三个 yml 是否有重复词、缺字段、难度错位（脚本化抽查）
清单：combo 公式逐分支演算；每一条会话结束路径（正常/quit/断线/插件 disable/GUI 强关）核对清理；wrong book 持久化往返；复习通知去重语义；词库 validator 规则 vs 实际文件；i18n 反馈键全对；words-per-session 边界（0/负/超池）。

### B-R2 AI 对话链
风险假设：
- RH1 异步线程直触 Bukkit 面残留（历史 PF-ACH-02 修了 bye 分支——扫其余每条 end/exit 路径）
- RH2 HTTP 回调（HttpClient thenAccept）里所有 Bukkit 调用点逐一核实
- RH3 进行中会话遇模块门关闭/场景切换/断线/重连的状态残留
- RH4 日限/速率限制在失败请求/超时下计不计（daily-round-limit 0/负=不限，D10 豁免）
- RH5 历史插入 role 序列完整性（system/user/assistant 配对；断流半截会话；GUI 按 role 渲染的 default 分支）
- RH6 TTS 缓存清理/临时文件/关闭顺序
- RH7 个人场景 CRUD（抽 store 后）：名称校验（路径穿越）、删除确认、import 码（ScenarioCodec 编解码边界/容错/注入）
- RH8 反馈闭环：JSON salvage 降级链、小结卡与 AI 点评并发
- RH9 chat-history GUI 分页边界（P2-27 越层直调 DB 已知——看分页/翻页/点开是否附带 bug）
- RH10 ai_scenarios.yml 内容完整性（键与代码引用对称）
清单：每条异步链追溯线程；每处 sendMessage/playSound 归属线程；会话状态转移逐条走；TTS/HTTP 失败路径；场景 codec roundtrip；历史清理（30 天）与新表联动（R3 缝线）。

### B-R3 数据层
风险假设：
- RH1 单连接共享（accepted 债）——但找"具体新竞态"：事务边界、autoCommit 切换、锁块外的连接使用
- RH2 UPSERT 双后端语义差（SQLite ON CONFLICT vs MySQL ON DUPLICATE KEY）与 schema 两源漂移
- RH3 deletePlayerData（reset）覆盖全部新表——**逐表核对**（历史盲区：后建表没补旧函数；2026 新表：ai_chat_feedback/social_feed/social_cheers/seasons/season_rewards/class_members/…）
- RH4 建表/迁移幂等（MySQL 兼容、索引创建、新列 ALTER 缺失）
- RH5 PlayerDataStore cache-first 与 DB 的漂移窗口（何时缓存与库打架）
- RH6 备份任务（wal_checkpoint→copy→quick_check 的锁与顺序、只 SQLite 的限制）
- RH7 cleanupOldChatHistory(30) 是否漏清理新表（反馈表/社交表）
- RH8 DataExporter CSV 转义（逗号/引号/换行入值）、列头匹配、重名文件
- RH9 查询索引覆盖（常用 WHERE 列 vs 已建索引清单）
清单：每张表逐列比对 schema.sql ↔ DatabaseManager 建表 ↔ 读写点三源；每条 SQL 参数化核查；锁的使用逐处（getConnectionLock / stripe locks / synchronized）；型别（时间戳列存字符串还是 long、时区）；连接失效重连路径。

### B-R4 社交+成就+榜单+档案
风险假设：
- RH1 SocialListener（近 20 提交改动第一名）：点击分发槽位映射、快照注册/清除配对（leaderboardGuis 类 map 泄漏/失配）
- RH2 成就判据 vs 文案一致性（22 个成就逐个对：判定条件与描述是否同一语义——诚实性维度）
- RH3 成就进度条 ProgressSource 计算（进度>目标、负值、除零）
- RH4 成就墙分组/排序/点击纵深/门（J-4 修复回归）
- RH5 榜单口径（学习分）与分页/期间切换/排名并列/空榜；%lle_rank% 缓存窗口
- RH6 广场动态流：FeedCache 预热/刷新时序、FeedTextFormatter 语言切换、隐私四层
- RH7 鼓励/回应幂等（重复点、自我鼓励、频控、离线圈）
- RH8 Profile 页统计口径全对（P3-01 正确率修复回归、P3-03 streak 真源）
- RH9 RewardEngine 连击乘数/复习奖励/money 门/daily-cap 交互
- RH10 进服摘要的 gate/隐私/去重
清单：每个 GUI 快照 map 的 put/remove 配对清点；成就有判定点逐一对齐描述；榜单数据源（Leaderboard.updateScore 零调用历史债 B5 核销状态）；Vault 调用的线程面（发币全在主线程？）；SocialManager 与 AchievementManager 单向接口。

### B-R5 决斗
风险假设：
- RH1 宽限/超时任务注册↔取消配对（僵尸任务历史伤；quit/rejoin 窗口、cancelDuelByAdmin 路径）
- RH2 答题态 token（R-27/R-68）极端序列：同题双击、两面同时答、超时前 0.x 秒
- RH3 acceptDuel 原子消费（校验全过再消费 R-68 回归）
- RH4 对局结束窗口聊天（F1-18 修复后两分支回归）
- RH5 离线/跨世界/旁观者/被踢/关服路径（双方离线解散 P2-08 回归）
- RH6 结算与胜者判定（平局、重连续局？、掉线判负）与成就/挑战联动（对决不计答题数豁免）
- RH7 DuelGUI/DuelChallengeGUI 展示同步（分数/倒计时/BossBar）与主线程纪律
- RH8 /le duel cancel 与模块门放行语义（gate 关时进行中对局的收尾）
清单：所有任务（grace/scoreboard/timer）创建点×取消点配对表；每状态转移画图（含取消/超时）；DuelRoom 死 API 是否清理干净（P2-06/07 回归）；决斗成绩与最终分差展示正确性；GUI 关闭/换手时的句柄清理。

### B-R6 挑战+签到+日历
风险假设：
- RH1 stripe lock（32 槽 UUID hash 分片）正确性：同玩家同槽、跨玩家无关、锁内 DB 操作
- RH2 领取幂等（条件 UPDATE）与 money 门（PF-MC-01 回归；关币时终态标记+提示）
- RH3 进度计数源（答题/会话/连击/AI 对话）跨午夜进行中场次计哪日、重复计
- RH4 午夜 rollover 两段式（F2-11 回归）：voidedBehavior map 交接、clearAllWordChallengeClaims、并发 quit→map 竟态
- RH5 签到 streak 跨日边界/时区（P3-12 统一后）、断签恢复、补签（无）
- RH6 日历 GUI（C-7 openAsync 回归；session days 计算、空月、月份切换）
- RH7 挑战 GUI 降级路径（degrade UPDATE/首次分配 INSERT 的 openAsync C-3 回归）
- RH8 挑战完成 GUI 展示/奖励提示与实际发放一致
清单：claim 全路径（有奖励/无/已领/离线/并发双击）；stripe lock 所有获取点；每日重置的日期基准（LocalDate.now 时区）；挑战类型枚举与 i18n；DB 行 challenge_date 语义。

### B-R7 赛季+跨服+班级+感官+引导
风险假设：
- RH1 赛季窗口推导（anchor-date 纯函数）边界：锚点当天、锚点未来（J-3 WARNING）、闰年/时区
- RH2 结算抢占（条件 UPDATE 唯一赢家）+ 广播 origin 回环（ProxyMessenger 近期多次改动）
- RH3 奖励发放：Vault 失败回滚 claimed、离线 join 补发、无 Vault pending 保留
- RH4 赛季缓存（5 分钟热备）与结算后即时刷新的一致性；跨服 relay 后本地缓存
- RH5 班级系统：class_members 生命周期（加入/退出/解散/踢人/reset 遗留）、老师权限校验、班级榜
- RH6 SoundManager：播放名/音量、per-player sounds_enabled、reload；CountdownTier 双域接线一致
- RH7 引导：状态机 0/1/2/3 推进刚性、fail-open 回填、引导书 PDC 三重补发去重、迷你会话原地重试跳过计数
- RH8 教程豁免 modes.choice（J-5）结构性保证成立性
- RH9 PlayerJoinListener 多任务顺序/去重（引导/提醒/补发/season/update 通知互斥）
清单：season 表中条件 UPDATE 的完整 SQL；Vault 调用的线程与失败回滚；ProxyMessenger 全部子通道的收发对称（含 sanitize R-01 回归）；origin 空值/回环行为；join 事件链逐任务 gate 表。

### B-R8 命令+菜单
风险假设：
- RH1 LearnCommand（1151 行）子命令全集 vs help 列表 vs 权限：有无隐藏命令/孤儿帮助/文档未提命令
- RH2 MainMenu 9 槽渲染与 MainMenuListener 点击映射同源（gui-layout 读取 vs 硬编码漂移——历史 D-03/D-04 类型）
- RH3 模块门 4 渲染+点击门（J-1）后全入口再扫：命令路径/别名/GUI/PAPI 面是否仍有后门
- RH4 MiscGUIListener 分发（词库选择/行为挑战领取/localized 回显 PF-ONB-03 回归）
- RH5 ConfirmationGUI 取消路径/D-18 回归/误触
- RH6 GuiNavigationManager 返回栈一致性（返回丢帧/深栈）
- RH7 命令参数解析边界（无参/错参/多余参/玩家名不存在）
清单：subcommand switch 逐个走；每个 GUI 槽位"渲染方/点击方"双读对照表；门失效回显键；命令与 GUI 入口功能对照矩阵（同一功能两入口行为对齐）。

### B-R9 管理面
风险假设：
- RH1 AdminCommand 逐子命令权限判定（report 特例）与误授权（无权限玩家的路径）
- RH2 reset 数据删除范围与二次确认交互（危险操作防护）
- RH3 ReportGenerator TXT：转义（玩家名含换行）、数据真源（P3-02/03 回归）、全异步、强制中文
- RH4 StatisticsHttpServer：HTTP 线程 vs 单连接（并发查询竞态）、绑定端口/认证/模板注入
- RH5 DataExporter 在线导出语义与 exports/ 路径
- RH6 管理命令与热重载（reload 后生效面 vs 需重启项的提示一致性）
清单：每条子命令权限代码路径核实；危险操作的确认链；HTTP 请求处理线程与 DB 交互；报告数值与源表抽查一致性。

### B-R10 基础设施
风险假设：
- RH1 onEnable DI 顺序（某模块构造依赖后置字段→NPE）；onDisable 顺序（save→unregister→shutdown 正确性与遗漏）
- RH2 ConfigManager：MESSAGE_CONFIG_VERSION 升级 saveResource(path,true) 覆盖用户编辑的内容？loadConfig 缓存与 reload 交互
- RH3 MessageUtil：语言解析、fallback、{placeholders}、GUI 标题路径
- RH4 HotReloadManager：reload 覆盖面 vs 文档承诺
- RH5 UpdateChecker：异步 HTTP/join 通知/禁用路径（F0-08 回归）
- RH6 LLEPapiExpansion：占位符真源/缓存/隐私/P3-03 streak 回归
- RH7 API 门面完整性（外部插件面）
- RH8 startScheduledTasks 各周期任务的 gate/cancel/重复调度（reload 后重复排任务？）
- RH9 pom.xml：遮蔽（shading）配置、排除、jacoco 门值合理性
- RH10 plugin.yml 权限/命令注册 vs 代码校验点
清单：字段声明顺序 vs onEnable 赋值顺序对照；每任务的生命周期（谁排/谁 cancel）；每 config 键的读取点与 reload 生效性；PAPI 缓存窗口。

### B-R11 UX 旅程专项
任务：静态代码+文案走查，画 3 条旅程并逐点判定。
- 旅程 A 新人：join →（提醒/引导书）→ /le menu → 引导会话 3 题 → 首成就 → 每日挑战领取 → 签到 → 个人主页 → 成就墙 → 广场 → AI 对话首轮 → 决斗邀请 → 榜单 → （老师）/lea report
- 旅程 B 老玩家：复习（/le review?）→ 错题本 → 连续签到断签恢复 → 广场回访 → 赛季结算知晓
- 旅程 C 中断：对话中途 quit、学习中关 GUI、决斗中重连、挑战领取时断线
每步判定 6 项：入口可达（菜单/命令/别名三面）；门失效时行为合理（提示而非静默）；文案完整（无 Missing message/占位符残留/{prefix} 未替换）；反馈存在（音效/消息/无静默失败）；可退出（不困在 GUI/模式）；与相邻步骤衔接（流程不断裂）。
产物要求：旅程地图（每步一行：步骤/入口文件:行/判定/问题引用 findings 编号）；"静默失败清单"（所有无反馈的死路）；"文案巡检表"（发现问题抄原文）。

### B-R12 操作逻辑与状态机专项
任务：把 §A-R12 列的状态机逐个静态仿真。
- 每状态机：枚举全部状态、枚举全部事件（玩家动作/超时/断线/关服/模块门/管理员操作）、画转移表、找：不完备（无转移）、非法（错误转移）、缺失副作用（转移无清理/无通知）
- 竞态仿真：GUI openAsync 飞行中玩家关服/开另一个 GUI/双击；领取双击；答题双击；玩家切世界/死亡/传送中触发交互
- 物品交互安全：**Shift-click/数字键换位/光标拖动** 能否把物品塞进插件 GUI（复制/丢失漏洞）、InventoryDragEvent 覆盖表（对每个 GUI 标题判定类 listener 列 drag 处理与否）
- 取消链：ConfirmationGUI 取消、对话退出、对决取消、引导中断的全部路径副作用
产物：状态机转移表 + 竞态发现清单 + 物品交互履历表（GUI × {click/drag/shift/double/numkey} × 处理与否）。

### B-R13 横切面卫生
任务：全仓机械扫描+语义复核。
- 线程：grep 全部 `runTaskAsynchronously|CompletableFuture|new Thread|thenAccept|HttpClient|Executor` → 每处回溯 Bukkit API 接触面（白名单：包在 runTask 内的）
- 资源/内存：长期 map（playerLanguage/joinReminderSent/activeDuels/leaderboardGuis/graceTasks/caches…）增长面无清理者清单；任务泄漏（runTaskLater 无 cancel 引用）
- i18n：重跑 `node scripts/audit-i18n-keys.js`（基线：521 键 + 1 误报）；反向扫（yml 键→代码引用）；硬编码玩家可见文本扫描（sendMessage/ItemMeta 里的中英文面）；zh/en 键对称
- config：正向（代码读→config 定义缺）+ 反向（config 定义→零读取）；对照 `tasks/dead-config-audit.md` 历史清单看新死键
- 安全：权限校验点表（每个命令/GUI 动作→权限节点；含 B5 四个零校验节点的现状复核）；注入面（ProxyMessenger/ScenarioCodec/报告/CSV）；SQL 参数化全查；文件路径（场景名/导出名/词库名→路径拼接）
- 性能：主线程 IO（文件/DB/HTTP/网络）扫描；循环内查询/循环内 DB；高频事件（PlayerMove？）监听器注册面
- 死代码：零引用方法/字段/类（提示：IDE 无法用，用 grep 组合核实）；重复逻辑簇
- 测试：8 个测试文件逐个评"测的是真风险吗"；遗漏的高危纯函数（如可测化的状态机）
产物：候选清单每类 ≤15 条（按证据强弱排序），每条附扫描命令与命中行。

---

## §C 跨域缝线清单（域边界最易藏 bug 处，各域从自己一侧查）

1. **GUI 关闭 vs 答题/会话监听**：WordLearningGUI 关闭→聊天答题流；会话存在性判定；GUI 关闭钩子与 listener 的先后
2. **异步写 vs 主线程读**：PlayerDataStore cache↔DB 双向漂移窗口
3. **模块门点读 vs 在途事件**：关模块瞬间——进行中会话/对局/领取的收尾语义（每个域各自核）
4. **聊天消息多 listener 竞争**：ChatScenarioListener / ChatAnswerListener / ChatHistoryListener / 普通聊天，谁 cancel、优先级、场景模式下答题被吞或反之
5. **午夜 rollover 三域联动**：挑战（F2-11 两段式）× 复习通知 × 赛季跨季检测
6. **赛季三方幂等合成**：结算 UPDATE 赢家 → 本地发放 → relay → 对端 applyRelayedSettlement/applyRelayedSeasonStarted
7. **Vault 发币线程面**：挑战/成就/签到/赛季/对决所有发币调用点的线程归属（Vault API 主线程约定）
8. **join 时刻多任务顺序与去重**：引导书/每日提醒/离线补发/season catch-up/update 通知
9. **语言切换对已渲染面的影响**：setPlayerLanguage 后进行中会话/已开 GUI 是否混语（PF-ACH-03 双语先行）
10. **GUI 快照登记↔点击配对**：leaderboardGuis/duelGui 等 map 的注册/清除（F3-01 类：登记缺失→点头无反应）
11. **config-version 升级 vs 用户改动**：saveResource(path,true) 覆盖用户自定义的窗口
12. **单服 vs 双服**：ProxyMessenger 无玩家信使/origin 空/回环行为差
13. **schema.sql 文档 ↔ 运行时建表双源**：漂移点
14. **计数源共享时序**：player_sessions/player_data 被学习/挑战/成就/报告多方读写（一课多计/漏计）
15. **PAPI 缓存窗口**：%lle_rank%/streak/season 显示的陈旧窗口与数据更新时延

---

## §D 回归核验清单（近期大修逐条核验是否仍正确、无副作用）

| # | 修复 | 核验点 | 归域 |
|---|------|--------|------|
| D1 | P3-02 统计真源 | getTotalSessions/getLast7DaysStats 走 player_sessions | R3+R9 |
| D2 | P3-03 streak 真源 | %lle_streak% 与 ProgressManagerImpl 走 checkin_data.streak_count | R4+R10 |
| D3 | F2-11 午夜两段式 | 异步段/主线程段边界、异常路径、自续 finally | R6+R10 |
| D4 | C-3 挑战 GUI openAsync | 两处 DB 面全异步、close 竞态 | R6 |
| D5 | C-7 日历 GUI openAsync | 同上 | R6 |
| D6 | R-01 跨服清洗 | sanitizeBroadcastName 全 payload 字段 | R7 |
| D7 | R-03 帧解析 | parseForwardFrame 与 ProxyFrameParseTest | R7 |
| D8 | R-02 死变量删 | ReportGenerator 统计无双计/漏计 | R9 |
| D9 | P2-08 离线解散 | cancelDuelByAdmin 快照遍历 | R5 |
| D10 | F1-20/F1-21 | 锁内重取 conn、cache-first+主线程守卫 | R3 |
| D11 | 模块门 4+3 | J-1 全入口复核（含 WrongBook 双门） | R8 |
| D12 | F3-01 榜单快照 | openLeaderboardWithSnapshot 所有入口 | R4 |
| D13 | update-interval | C-1 接线+钳位 [30,3600]+需重启注释 | R10 |
| D14 | config-version 14 | zh/en 头 + MESSAGE_CONFIG_VERSION 三处 | R10+R13 |
| D15 | PF-MC-01 money 门 | 行为挑战领取终态标记+提示 | R6 |
| D16 | PF-ACH-02 bye 线程 | bye 分支切主线程+守卫 | R2 |
| D17 | F1-18 决斗窗口反馈 | 两分支 duel.not-in-duel | R5 |
| D18 | 僵尸宽限任务 | self-cancel 模式覆盖所有摘除分支 | R5 |
| D19 | P3-07 exports 路径 | CSV 落 exports/ 且 wiki 一致 | R3 |
| D20 | P3-12 时区统一 | 全仓 Asia/Shanghai 清零（grep 复核） | R13 |
| D21 | H-1 死链移除 | achievements.yml 引用清零、启动无释放 | R10 |
| D22 | C-5/C-6 键面 | 8 死键删干净 + wrong-book 键补录 | R13+R8 |
| D23 | C-9 CountdownTier | 学习侧接线逐位等价 | R1 |
| D24 | J-3/J-4 | 赛季休眠 WARNING、成就墙点击门 | R7+R4 |
| D25 | PF-SEA-02/03 | createIndexIfMissing、player_sessions 索引 | R3 |
| D26 | P2-24 音效盲点 | 三处补齐回归 | R8 |
| D27 | PF-ONB-01/03 | FIRST_WORD 语义、session-start 本地化 | R7+R8 |
| D28 | 低危批次 M 系 | F1-20 已列；P3-05/06/16 注释面 | R3 |

---

## §E 设计意图豁免清单（防误报——先对照再立案）

1. 16 成就双语文案硬编码在 enum（accepted A1，设计决策）
2. ChatHistoryGUI `default:` 分支拼 role（accepted A2，不可达）
3. WordBankValidator 内部错误文本硬编码（accepted A3，数据注入型）
4. `gui-layout` 部分槽位键零读取（accepted A4——已部分收敛，残留按 4B 结论核销）
5. core→gui 反向依赖 3 处（accepted B1）；超 300 行类（accepted B2）；GUI 标题字符串匹配/holder=null（accepted B3——**但匹配模式内部的不一致/新 bug 可报**）；单 JDBC 连接共享（accepted B4——**但具体新竞态可报**）
6. 4 个权限节点零校验（accepted B5/D-14——现状复核可在 R13 报告现状但不作新立案；若发现"新加权限面也漏"另论）
7. ReviewScheduler 间隔数组需重启生效（accepted B5，文档已注）
8. 对决零持久化/对决题不计入答题数（P3-08 + 用户决策默认值）
9. 重启丢对决计数（用户决策"设计接受"）
10. 榜单口径 = 学习分（用户决策）
11. daily-round-limit 0/负 = 不限（D10）
12. 成就"30 天"= 累计非连续（D12）
13. 班级老师管理型（D13）；段位不做（D14）
14. 赛季奖励不受 daily-cap（D8）；7-3B 资源包不做（D7）
15. 家长报告默认不受隐私开关约束（D11，有 report.respect-privacy 收紧）
16. 教程强制 choice 绕过 modes.choice（J-5，教学结构保证）
17. 对决展示格点击无声（P2-02 设计确认）
18. 午夜 rollover 不做停机 catch-up（R-75 语义；join 兜底分配）
19. player_sessions.mode = 当日首场模式（P3-05 语义）；checkin_data 无逐日历史（P3-06）；word_progress REPLACE 语义（P3-09）
20. 个人场景 30+ 截断上限（P2-03）；.bak 单份尽力而为（P2-04）
21. 命令直开 GUI 音效白名单维护约束（P2-01）
22. 服务器从主线程外收到 PluginMessage 的线程归属以 Paper 实际行为为准（若发现真实异步情况可报）
23. `dialogue.proxy-achievement` 等键在 message config 而非 config.yml（既有架构）
24. sample-词库手动拷贝会被加载（F0-14 运维提示）

---

## §F 严重度 rubric 与输出格式

- **高**：崩溃/异常中断玩家流程、数据丢失或损坏、主线程阻塞（卡服）、安全漏洞（注入/权限绕过/物品复制）、刷奖励刷钱
- **中**：功能错误（结果不对）、误导玩家（文案与行为不符）、体验断裂（静默失败/困死/无出口）、状态污染（残留影响后续）、并发正确性（竞态但不易触发）
- **低**：卫生问题（死代码/冗余/命名/注释过期/性能小瑕疵）、极端边界、可维护性
- 每条必须四件套（file:line + 代码原文 + 复现场景 + 修法）；置信度标注 [确定]/[高]/[中]/[待验证]；见 `dispatch-template.md` §D 完整格式
- findings 文件命名：`findings-r1.md` … `findings-r13.md`（Agent 只允许写自己这一个文件）

---

## §G 探索优先级排序（给红队与汇总的权重参考）

| 序 | 域 | 理由 |
|----|----|------|
| 1 | R5 决斗 | 1376 行管理器；历史 bug 密度最高（僵尸任务/离线路径/token 化三次伤）；玩家对局核心 |
| 2 | R12 状态机专项 | 历轮 bug 主源 = 竞态与不完备转移（GUI 开关、取消链、双击） |
| 3 | R2 AI 对话 | 最大交互面（1500 行 + 异步 HTTP + 多 listener 竞争） |
| 4 | R4 社交/成就/榜单 | SocialListener 热点第一；成就判据诚实性 = 口径类 bug 温床 |
| 5 | R13 横切面 | 线程/泄漏/安全大网——跨域违规唯一系统检查点 |
| 6 | R1 学习核心 | 核心闭环正确性（计分/连击/状态机） |
| 7 | R3 数据层 | 数据正确性底线（reset 覆盖面/双后端语义/并发） |
| 8 | R6 挑战/签到 | 并发领取 + 午夜交接（两个历史重灾区已修——回归核验价值高） |
| 9 | R7 赛季/跨服/班级 | 最新代码；跨服协议真实双服未实测 |
| 10 | R10 基础设施 | 生命周期/DI/调度——牵一发动全身，但近期改动较稳 |
| 11 | R11 UX 旅程 | 静态走查依赖各域证据，独立价值在"静默失败/文案巡检" |
| 12 | R8 命令/菜单 | 门与槽位（J-1 修复后回归为主） |
| 13 | R9 管理面 | 玩家不可感面，但 reset/报告危险操作值得看 |
