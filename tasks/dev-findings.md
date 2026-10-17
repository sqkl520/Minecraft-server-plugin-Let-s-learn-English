# 开发期发现汇总（未修复）

> 记录规则：开发 agent 在任务范围外发现的 bug / 疑点，一律不得自行修复，汇总到这里，由编排者统一归档排期。
> 状态取值：待排期 / 已排入 Phase 1 / 已排入 Phase 2 / 需用户决策 / 已关闭。
> 本文件累积所有批次，最新批次在上。

---

## 批次：低危积压清零（用户点名，多 Agent 模式）

> 4 路只读核实 agent 先行（红队模式：假设"待排期"描述过时、用代码反驳/证实），随后施工流并行修复。本节记录核实结论与施工收口。

### 全量核实总账（2026-10-17）
**53 条挂账 = 已修 27（状态滞后，已批量回写）+ 已过时 1（F5-02）+ 真未修 25（分 5 流施工）**。核实的意外收获：状态滞后远超预估（诚实性审查估 8 条，实际 27 条）——Phase 1/2/P2 批次修复时在代码注释里埋了条目号，但表没同步。

**5 施工流**（白名单互斥并行）：
- 流 A（决斗系）：P2-06/P2-07/F2-12 死代码删 + F1-18 补反馈 + **僵尸任务泄漏修复（红队新发现）** + P2-21 注释
- 流 B（数据层）：F1-20 锁内重取 + F1-21 cache-first + P3-05/06/16/F5-03 注释 + P3-07 导出落 exports/ + P3-12 时区统一（Asia/Shanghai → 系统时区，修正不一致）
- 流 D（i18n/社交 GUI）：P2-19 unknown-scenario 键 + F4-04 成就墙纵深 + F3-02 笑话页返回（顺手修栈残留帧）+ P2-27 越层注记
- 流 E（词库内容）：P2-15 dingle 删 + P2-16 同义词越级清理（含泛化扫描）+ README 修正
- 流 F（挑战注释/单点）：F2R-01/02 契约注释 + P2-26 结构环注记 + F0-12 冗余守卫删 + clearWordChallengeClaim 死方法删

**红队新发现（本批顺带/入账）**：DuelManager 宽限到期僵尸任务（流 A 修）/ ChatHistoryGUI 循环内逐会话同步查询（流 D 评估）/ ProxyMessenger playerName 无注入清洗（上报待排期）/ 泛化 capture-before-lock 窄窗口约 26 处（流 B 记录，只修最宽者）；**顺带确认已修的更多项**：F3-01（J-2）、T0-02/T0-03/T0-04、F0-03/04/05/06/07/09/10、F1-03/04/06/07/23/24、F4-02（R-49）、F5-04、F6-01/02、F2R-03、P2-17 主表。

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| R-01 |  | 红队发现： 的 playerName 来自代理 payload 未经清洗直接进广播（恶意子服可注入颜色码/换行刷屏）；修法=颜色码剥离或玩家名正则校验 | 低 | 待排期（跨服自营场景危害有限；修复需 ProxyMessenger 白名单批） |
| R-02 |  |  死局部变量（370 行又赋回 unlockedDb，两变量读同一 total 列）——核实 agent 发现 | 低 | 待排期（一行级清理） |
| R-03 |  帧解析路径 | PF-SEA-01 修复已落地但**单服无法验证**——需双服环境实测（跨服广播/成就/赛季通道） | 中 | 待实测（环境依赖，非代码面） |

### Duel 系核实（agent 完成，2026-10-17）
- **状态滞后回写 4 条**：F1-15（acceptDuel 已改"校验全过再原子消费" R-68 token 化）/ F1-16（答题态对称检查 R-27 + token 匹配根治过期误删）/ F2-07（DuelRoom.submitAnswer 已有 null 防护）/ F2-08（cancelDuel 死方法已随 P2 S3 删除）
- **确认未修 4 条**：F1-18（对局结束窗口聊天被吞静默——修法：364-367 静默 return 改发反馈键）/ P2-06（getDuelRoom/hasPendingRequest 死 API 删）/ P2-07（DisconnectInfo.disconnectTime 死字段删）/ P2-08（双方离线 GM 无法解散——入口按人名回退遍历 activeDuels）
- **已过时 1 条**：F2-12（零 UUID 哨兵仍在但 getLastCorrectAnswerer 全仓零消费者，实害为零——可选清理）
- **红队新发现（未入账旧伤）**：`DuelManager.handleDisconnect` 宽限到期分支（830-847）只摘登记不 cancel 任务本体 → 每次判负留一个每 20 tick 空转僵尸任务到关服（cleanup/shutdown 均覆盖不到）——修法：照 R-26 selfRef 自取消模式
- 附：DuelRoom 另三个零调用 API（containsPlayer/getLastCorrectAnswerer/getWordStartTime + wordStartTime 只写字段）、cancelDuelInternal 的 reason 参数零使用（新账，随本批清理）

---

## 批次：Phase 3 最终验收（锐评 3 路 agent + 编排者收口）

> 跨波一致性审计（`ralph/test-reports/final-consistency-audit.md`）发现的跨批打架项与收口记录。C-1/C-2/C-4/C-5/C-6 已由编排者当轮收口；C-3/C-7 为"旧面从未入账"记入待排期。

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| C-1 | `config.yml` update-interval ↔ `LetsLearnEnglish` 刷新任务 | plan 裁决"接线"未执行（键悬空、注释与行为差 10 倍）—— MC-2/MC-5/4B 三方互相以为对方处理 | 中 | **已关闭**（Phase 3 收尾）：接线 + 默认 300（零漂移）+ 钳位 [30,3600] |
| C-2 | `DatabaseManager.deletePlayerData` 缺 `class_members` | 4A 新表未回头补进 reset 函数（后建表没回头补跨批函数的模式）——reset 后残行（占名额/榜 uuid 串） | 中 | **已关闭**（Phase 3 收尾）：补进 uuidTables |
| C-3 | `DailyChallengeGUI:61` + `DailyChallengeManager:302-330/792-795` | 挑战 GUI 打开主线程直查 DB **且可触发写库**（degrade UPDATE / 首次分配 INSERT）——从未入账；同族 F2-11/PF-SOC-01 | 中 | **已关闭**（P3 遗留中危修复批）：DailyChallengeGUI openAsync 两段式（两处 DB 面全异步） |
| C-4 | `config.yml` money.vault-required | 零读取真死键 | 低 | **已关闭**（Phase 3 收尾）：删键 + 注释说明（Vault 检测驱动，无需开关） |
| C-5 | zh/en 8 个 i18n 死键 | 4B 扫除不彻底（含 PF-ACH-05 明确记账却漏删的 `achievement.unlocked`）；audit 脚本单向不查"键→无引用" | 低 | **已关闭**（Phase 3 收尾）：8 键删除（review.complete / achievement.unlocked / progress.view / admin.export-start / admin.word-add / answer-hint-lore-2 / current-word-lore-2 / wrong-book.title） |
| C-6 | `gui-layout.main-menu.wrong-book` 反向缺键 | 代码双侧读、config 无定义（管理员改不动） | 低 | **已关闭**（Phase 3 收尾）：补录键（值与代码默认一致零漂移） |
| C-7 | `LearningCalendarGUI.getSessionDays` | 日历 GUI 主线程直查未入账（旧面漏检，同 C-3 族） | 低 | **已关闭**（P3 遗留中危修复批）：LearningCalendarGUI openAsync 两段式 |
| C-8 | `schema.sql` 缺 2 索引文档 | 4 个防扫索引中 2 个未在 schema.sql 注释（运行时由 createIndexIfMissing 建，文档面缺口） | 低 | **已关闭**（Phase 3 收尾）：schema.sql 已补录两索引 |
| C-9 | `CountdownTier` 学习侧未接 + 无单测 | 纯函数已抽出但学习侧仍走旧路径（对决侧已接）；缺单测 | 低 | **已关闭**（Phase 3 收尾）：学习侧接线 CountdownTier（逐位等价）+ 5 项单测 |
| C-10 | P3-02/P3-03 挂账措辞 | 措辞引用 ProgressGUI（已删），随 Phase 3 结束失效 | 低 | **已关闭**（Phase 3 收尾）：P3-02/P3-03 措辞已校正（本体仍挂账，见对应行） |
| J-1 | MainMenu 模块门 | 旅程验收：渲染门只落 duel/checkin，config 承诺的 word-system/ai-chat/daily-challenges "入口隐藏"落空（含 WrongBook 全链零门——唯一"关闭后仍可玩"破口） | 中 | **已关闭**（Phase 3 收尾）：4 渲染门 + MainMenuListener 点击门 + WrongBook GUI/listener 双层门 |
| J-2 | LearnCommand 榜单直开 | F3-01：/le leaderboard 直开不登记 leaderboardGuis 快照，点头像静默无反应（三个 Phase 未清） | 中 | **已关闭**（Phase 3 收尾）：SocialListener.openLeaderboardWithSnapshot 登记式打开 |
| J-3 | 赛季"开而不动" | season.enabled=true 但锚点在未来时全系统休眠无提示（验收/运营易误判"功能没做"） | 低 | **已关闭**（Phase 3 收尾）：settleIfNeededAsync 启动 WARNING 一次性提示 |
| J-4 | ProfileGUI 成就墙点击 | 渲染有 achievements 门、点击分支无（僵尸主页可开只读成就墙） | 低 | **已关闭**（Phase 3 收尾）：SocialListener 点击分支补门 |
| J-5 | tutorial 强制 choice | 迷你会话绕过 modes.choice.enabled（D4 规划有意豁免——必中性结构保证依赖） | 低 | 已记录（设计豁免+代码注释显式说明；运营要求受门约束属产品变更） |
| H-1 | ConfigManager.achievementConfig | 诚实性审查：Java 死链（字段+getter 零消费），loadConfig 还把废弃 achievements.yml 释放到数据目录误导管理员 | 中 | **已关闭**（Phase 3 收尾）：整链移除 + 废弃文件从 JAR 删除 |

---

## 批次：P2 清理（甲+乙合并批，8 流 + 2 重跑实例）

> 范围：P2 43 条（核实后实修约 38）+ dev-findings 积压。S1/S5 首实例中断（API 500 / watchdog），重跑实例已补全。各流上报的任务外发现归档如下（一律未修）。

### 来自合流 tester（P2 批次）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-26 | `AIChatManager:525` ↔ `DailyChallengeManager:225` | Core↔Core 互引（AIChatManager→updateProgress / DailyChallengeManager→isAvailable），当前无递归栈风险，属结构性环（既有，非本批） | 低 |**已关闭**（低危批次流 F）：双向结构环注记（AIChatManager/DailyChallengeManager 各一处） |
| P2-27 | `ChatHistoryGUI:121/128/146/271` | GUI 越层直调 DatabaseManager（chat-history 既有功能，非本批） | 低 |**部分收口**（低危批次流 D）：越层注记 + 主线程 IO 成本注释；门面抽取属重构批次（保留） |
| P2-28 | `AIChatManager` 个人场景持久化 | 类同时承担文件持久化（File/YamlConfiguration/save/.bak，1039-1100 行），建议后续抽 `PersonalScenarioStore`（Arch tester 建议，非本批阻塞） | 低 | **已关闭**（随 Wave 3A 3A-2 收口）：`PersonalScenarioStore` 抽取完成 |

### 来自 Phase 3 规划期（各域 agent 上报，未修）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P3-01 | `gui/ProfileGUI.java:105` | 正确率恒显 100%：M10 后 `getTotalWords()` 语义=只计答对（=totalCorrect），GUI 却用 `correct/getTotalWords()`，分子分母同值；PAPI 的 accuracy 是正确算法（correct/(correct+incorrect)），两者打架 | 中 | **已关闭（本轮由编排者修复）**：改为 `totalCorrect/(totalCorrect+totalIncorrect)`，与 PAPI 同口径 |
| P3-02 | `learning_log` 表 | 全库零写入（无任何 INSERT），`DatabaseManager.getTotalSessions()`（统计 HTTP 服务的 totalLearningSessions 与 7 天会话曲线）恒 0——真数据源是 player_sessions | 中 | **已关闭**（P3 遗留中危修复批）：getTotalSessions/getLast7DaysStats 改走 player_sessions（真数据源；sqlite 冒烟 7 断言） |
| P3-03 | `player_data.current_streak` | 零写入（persistLongestStreak 只写 longest_streak），`%lle_streak%` 与 ProgressGUI 当前连续恒 0——真数据源是 checkin_data.streak_count | 中 | **已关闭**（P3 遗留中危修复批）：%lle_streak% 与 ProgressManagerImpl 改走 checkin_data.streak_count 真源 |
| P3-04 | `ProgressGUI.java` | 死类（无实例化点，P2-23 同根）——`/le progress` 实际走 ProfileGUI | 低 | **已关闭**（随 Wave 4 4B 收口）：死类已删除 |
| P3-05 | `player_sessions.mode` | 同日合并（UPSERT）时 mode 列不被更新，记录的是当日首场模式 | 低 |**已关闭**（低危批次流 B）：recordSession 注释标注当日首场模式语义 + ReportGenerator 同口径交叉引用 |
| P3-06 | `checkin_data` | 无逐日历史（只有 last_checkin_date + streak_count），报告域做"本周签到 X 天"需从 player_sessions 或新表推导 | 低 |**已关闭**（低危批次流 B）：checkin_data 建表注释（Java+schema.sql 双处）说明无逐日历史与推导方向 |
| P3-07 | `DataExporter` | 仅导出在线玩家 + CSV 落根目录（非 exports/）；失败判定字符串硬耦合（P2-09 同根） | 低 |**已关闭**（低危批次流 B）：CSV 改落 exports/ 子目录（wiki 同步）+ 在线导出约束注释 |
| P3-08 | 决斗 | 零持久化（无对决历史表），对决类统计与"对决首胜"成就无数据源 | 低 |已记录（依赖项：对决持久化需新表设计，非本批范围——成就域已用快照替代） |
| P3-09 | `word_progress` | INSERT OR REPLACE 语义使增量列不能靠 SQL COALESCE 累加，只能靠缓存侧维护 | 低 | 已记录（数据层约束，Phase 3 新功能设计输入） |
| P3-10 | `ProxyMessenger` 接收端 | 跨服消息通道 Forward 帧解析缺"帧长消费"（短消息带 2 字节前缀、长消息 UTFDataFormatException 丢失）—— **已由编排者即修**（PF-SEA-01）；单服无法验证，需双服实测确认 | 中-高 | **已关闭**（本轮修复，跨服实测待双服环境） |
| P3-11 | `DatabaseManager` 建索引 | `CREATE INDEX IF NOT EXISTS` Oracle MySQL 不支持（异常被 fine 吞，ai_chat_history 两索引 MySQL 后端可能从未建立）—— **已由编排者即修**（PF-SEA-02/03：createIndexIfMissing + player_sessions 补两索引） | 中 | **已关闭**（本轮修复） |
| P3-12 | `AchievementManager:205` | 硬编码 Asia/Shanghai 时区，与全项目"系统时区"口径并存（成就连续天判定在非东八区服会有偏差） | 低 |**已关闭**（低危批次流 B）：三处 Asia/Shanghai → TimeZone.getDefault（与 checkin/streak 口径统一；判定与展示同区） |
| P3-13 | `AchievementManager:224` | FIRST_WORD 实际要求"整场零失误"（sessionIncorrect==0），与成就描述"答对第 1 个单词"不符且与 PERFECT_SESSION 重复，伤首会话留存 —— **已由编排者即修**（PF-ONB-01：改为会话内至少答对 1 题） | 中 | **已关闭**（本轮修复） |
| P3-14 | `MiscGUIListener:141` | 词库选择路径的 word.session-start 回显原始英文标识符（F2-13 只修了命令路径，新手主路径漏网）—— **已由编排者即修**（PF-ONB-03：与 LearnCommand 同口径本地化） | 低 | **已关闭**（本轮修复） |
| P3-15 | `core/progress/Achievement.java` | 死类（零引用），与 core/achievement/Achievement.java 同名构成导入歧义陷阱 | 低 | **已关闭**（随 Wave 4 4B 收口）：死类已删（与 ProgressGUI 同批） |
| P3-16 | `player_data.created_at/last_login` | 死列（影响注册漏斗统计口径） | 低 |**已关闭**（低危批次流 B）：last_login/created_at 死列注释（Java+schema.sql）保留不删照 current_streak 先例 |
| P3-17 | `MiscGUIListener.claimBehavioralChallenge` | 行为挑战领取绕过 money.enabled 门（关币+Vault 时仍实发金币）—— **已由编排者即修**（PF-MC-01：关闭时终态标记+economy-disabled 提示，与单词挑战同口径） | 中 | **已关闭**（本轮修复） |
| P3-18 | `DuelManager` 4 处裸 getString | 其一为真实 NPE 路径（域 8 规划新发现，P2-13 白名单外）；另含 `WordLearningGUI` 标题中英混排、`AchievementManager` 解锁瞬间混服语言取单语言（Domain 8 记录） | 低-中 | **已关闭**（随 Wave 3A 3A-3 收口）：DuelManager 裸 getString 全收敛 |
| P3-19 | `ChatScenarioListener` bye 分支 | AsyncPlayerChatEvent 异步线程直调 Bukkit API（endConversation 链上可达 tryUnlock 通知段 sendMessage/playSound + 每日挑战记分）—— **已由编排者即修**（PF-ACH-02：bye 切主线程 + handleEndConversation 加"仍在场景模式"守卫） | 中 | **已关闭**（本轮修复，verify 26 测试全绿） |
| P3-20 | `AchievementManager` unlock-sound | 假配置：`achievement-system.unlock-sound` 只被"判空"消费（留空=不播生效），播放值恒为 SoundManager 的 complete 键、配置的音效名被忽略 | 低 | **已关闭**（随 Wave 1 收口）：`SoundManager.playSoundByName` 真播放配置值（PF-ACH-01 修复） |

> P3-01~20 来源：Phase 3 规划各域 agent 的"规划期发现"节（plan-social/plan-ai-feedback/plan-reports/plan-audio-feedback/plan-season/plan-onboarding/plan-menu-config/plan-achievements 等文件）。P3-01/P3-10/P3-11/P3-13/P3-14/P3-17/P3-19 因属"可即修的实伤"已由编排者当轮修复并记 CHANGELOG（verify 26 测试全绿）；其余待 Phase 3 施工时按域排期。

### 来自 S1（GUI 崩溃与音效）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-01 | `LearnCommand` 等各命令入口的 GUI 直开 | 音效单源收口后（menu-open 仅 SoundManager 全局播放），命令直开的插件 GUI 仍会走 InventoryOpen 全局音——覆盖完整；但若将来标题改名未同步 GUIUtils 白名单，该 GUI 静默丢音（既有注释已警示） | 低 | 已记录（维护约束） |
| P2-02 | `DuelGUIListener` 展示格 | 删全局 InventoryClick 后，对决界面除退出键外其余展示格点击无声——按设计静音（无可点击动作不应有反馈音），非缺陷 | 低 | 已记录（设计确认） |
| P2-23 | `gui/ProgressGUI.java` | 全库零实例化点（`new ProgressGUI` 仅构造函数），实际 `/le progress` 走 ProfileGUI —— 死类残留 | 低 | **已关闭**（随 Wave 4 4B 收口）：死类已删除（成对删 progress-gui.* 键） |
| P2-24 | `ScenarioSelectListener` AI 未配置分支 / 无权限分支 | 关界面+发消息但零音效（handleScenarioClick 116-121、handleReloadAPI 192）；`WrongBookListener.handlePractice`（Shift+点练习）有效动作无点击音（同页左键删除有音） | 低 | **已关闭**（随 Wave 1 收口）：三处音效盲点补齐（menu-click/incorrect） |
| P2-25 | `config.yml` `main-menu.scenario-select.back-button` | 疑似死配置（ScenarioSelectGUI 与 listener 双侧硬编码 slot 8，一致无 bug） | 低 | **已关闭**（随 Wave 4 4B 收口）：config 键已删除 |

### 来自 S2（AI 健壮性）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-03 | `LearnCommand` 纯命令入口（F4-01 连带） | 场景 key 校验已加（S4），但 `getSortedScenarioKeys` 之外若玩家有 30+ 个人场景且被截断加载，命令直达被截断的场景 key 会报 not-found（数据仍在盘，不出错但不可用）——上限语义的可预期后果 | 低 | 已记录（上限语义） |
| P2-04 | `AIChatManager.importPersonalScenario` 备份机制 | .bak 只保留一份（覆盖旧 bak）——多次损坏重写后最早备份被顶掉。属尽力而为设计 | 低 | 已记录 |
| P2-05 | `PlayerJoinListener:89-90`（并行期跨流缺口） | 曾引 CheckInManager/RewardEngine.unloadPlayer 未落盘——S5 重跑已补齐，编译恢复绿色 | — | 已关闭 |

### 来自 S3（决斗全套）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-06 | `DuelManager.hasPendingRequest` / `getDuelRoom` | 全库零调用死公共 API | 低 |**已关闭**（低危批次流 A）：两死 API 删除（全仓零引用复核） |
| P2-07 | `DuelManager.DisconnectInfo.disconnectTime` | 只写不读死字段 | 低 |**已关闭**（低危批次流 A）：DisconnectInfo.disconnectTime 删字段+构造简化 |
| P2-08 | `DuelManager.cancelDuelByAdmin` | 双方都离线时 GM 仍无法解散（R-69 队列只覆盖一名在线场景） | 低 | 已记录（已知限制，待 Phase 3 决策是否扩权） |

### 来自 S4（命令与配置校验）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-09 | `AdminCommand` export 判定 | 成功判定与 DataExporter 返回串 `"Export failed"` 前缀字符串硬耦合——DataExporter 改失败文案则静默回归 | 低 | **已关闭**（随 Wave 2 域 6 收口）：`ExportResult` record 协议化 |
| P2-10 | `AdminCommandHandler.addWord/removeWord` | 未知难度只打 console warning 无 sender 反馈（无调用方传 sender，签名改造量大） | 低 | 已记录 |
| P2-11 | `error.scenario-save-failed` | 被 S4 改造后失去唯一使用点，成死键候选（已在 dev 文档记录） | 低 | **已关闭**（随 Wave 4 收口）：`error.scenario-save-failed` 键已从 zh/en 删除 |
| P2-12 | `AIChatManager.getMaxPersonalScenarios()` | S2 落盘的公开 getter 当前无引用（S4 用当前条数实填 {max}），保留备用 | 低 | 已记录 |

### 来自 S5（生命周期与数据卫生）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-13 | 白名单 13 文件 | 剩余 21 处裸 `messages.getString(`（WordLearningListener 8 / WordLearningManager 9 / WrongBookListener 3 / DailyChallengeManager 1），缺键仍 NPE——GUI 侧已清零，listener/core 侧为二期候选 | 中 | **已关闭**（随 Wave 3A 3A-3 收口）：21 处裸 getString 全收敛 getGuiMsg |
| P2-14 | `AchievementManager:178` / `:200` | 带默认值 / 紧跟 null 检查的 getString 保持不动（非缺陷，记录豁免原因） | 低 | 已记录 |

### 来自 S6（词库内容）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-15 | `advanced.yml` dingle | 疑似生成期噪声词（极度生僻、与 dingy 形近易混） | 低 |**已关闭**（低危批次流 E）：dingle 词条删除（330→329） |
| P2-16 | `beginner.yml` 同义词面 | 超纲面大于 tome 单点：cat→feline、dog→canine、eat→consume/dine、baby→infant 等 | 低 |**已关闭**（低危批次流 E）：beginner 同义词越级清理 10 个（泛化扫描全 317 词条；infant 保留） |
| P2-17 | words/README | "beginner 用 1~2 级"与数据（26 个 level 3）不一致 | 低 |**已关闭**（words/README 主表已校正 1~3 级 255/36/26；残留旧句随低危批次流 E 清理） |
| P2-18 | 词库字母覆盖面 | beginner 均匀、intermediate 偏 a-i、advanced 96% 集中 a-d——Phase 3 扩充选词参考 | 低 | 已记录 |

### 来自 S7（GUI 硬化）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-19 | `ChatHistoryEntry.extractScenarioFromSystemPrompt` | 缺省返回硬编码英文 "Unknown"（数据层，白名单外） | 低 |**已关闭**（低危批次流 D）：chat-history.unknown-scenario 双语键 + GUI 显示侧三态替换 |
| P2-20 | `WordBankSelectGUI:92` | 保留 getString+null 判的字母大写化兜底（体验优于 [?key]，S7 自决保留，加 config null 护） | 低 | 已记录（设计确认） |

### 来自 S8（入口与配置）

| # | 位置 | 现象 | 严重 | 状态 |
|---|------|------|------|------|
| P2-21 | `ChatAnswerListener.broadcastCorrectAnswer` 3 秒在线缓存 | 刚进服玩家 3 秒内收不到答对广播（R-86m 修语言时保留的既有缓存） | 低 |**已关闭**（低危批次流 A）：3s 缓存注释（意图+副作用+保留裁定） |
| P2-22 | `daily-reminder` 双通道去重 | 定时发全员时也写 joinReminderSent（S8 自决共享去重）——若编排者要两通道独立，删一行即可 | 低 | 已记录（设计确认，保留共享） |

### 本批次语义裁决记录（已定，无需再议）

- 音效单源 = menu-open 仅 SoundManager 全局 InventoryOpen 播（白名单 16 GUI 全覆盖）；menu-click 仅各 listener 显式播放（全局 InventoryClick 已删，装饰格/背包点击不再误响）；未知音效键首次 WARNING 防刷屏。
- 音效对称补齐 = 学习超时/决斗败者播 incorrect；邀请过期/GM 解散/对决退出键播 menu-click；签到成功播 correct。
- menu-size = 合法域 [27,54] 且 9 的倍数（<27 布局不够，按钮槽位固定 10-18），非法回落 27 + WARNING。
- 个人场景 = slug 尾附 CRC32 短码防碰撞；同名幂等更新（updated 语义）；512KB / 30 条上限（超限 limitReached 拒绝）；损坏文件 .bak 备份后重写；ImportResult(key, updated, saveFailed, limitReached)。
- AI 上下文 = max-context-pairs 真接线（默认 6 完整 user/assistant 对）+ token 预算双约束；system 永不裁（单独超预算 WARNING 一次）。
- TTS = 空/octet-stream Content-Type 过魔数校验（RIFF/ID3/0xFFEx）才落盘；毒缓存发现即删；空响应体不落盘。
- 决斗 = PendingDuel(senderId, token) 原子消费；OfflineDuelNotice 离线通知队列（consumeOfflineDuelNotice）；shutdown() 幂等（S8 onDisable 调用）；对手超时文案 {seconds} 占位脱钩。
- 互斥矩阵（对称） = 答题中/决斗中不可开 AI 对话（finish-quiz-first / finish-duel-first）；AI 对话中不可开答题（chat-active）、不可发起/接受决斗（sender/target-chatting）。
- 挑战进度显示 = 写入侧（updateProgress）+ 读侧（getTodayChallenges）+ GUI 渲染 + 聊天渲染四处同口径 clamp（min(progress, target)）。
- 词库语义序 = beginner→intermediate→advanced→其它字母序（DIFFICULTY_ORDER 表 + LinkedHashSet 保序）。
- reload 语义维持 = clearCache 不清两套今日计数器（防刷不重置）；quit 清（unloadPlayerData）；今日金币 cache 由 HotReloadManager 单独重置（既定）。
- 阈值裁决保留项 = R-59 quit 计入 session/分数；R-71 保留词优先（accept/quit/cancel 名玩家不可被挑战）；R-86l 细粒度权限暂不接线；F1-22 重启丢今日计数；F1-19 daily-round-limit=0=拒绝全部（已文档化）。

---

## 批次：P0 高危修复（6 流 + 合流测试 GO）

> P0 12 条（R-01~R-09 + R-09b/c/d）全部修复，合流测试 verdict：GO，零阻断。
> 各流上报的任务外发现归档如下（一律未修）。

### 来自流 1（背包误触）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| 已关闭 | 4 处同类不分区残留（ChatHistory/ScenarioSelect/WordLearning/WrongBookListener） | 与 R-01 同病 | 同根 | 高 | 已关闭（编排者 fan-in 时按统一模式补修） |

### 来自流 3（社交串号）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F3-01 | `LearnCommand` 命令直开榜单/广场 | 不登记快照：排行榜点头像无反应（与 L-02 同事项）；广场 fallback 重建 ALL_PLAYERS 语义可能与所见页错位 | 命令入口不登记 | 中 |**已关闭**（Phase 3 收尾 J-2：SocialListener.openLeaderboardWithSnapshot 登记式打开） |
| F3-02 | 笑话页返回 | 直达主菜单而非返回栈（与旧行为一致，刻意保持） | 旧行为 | 低 |**已关闭**（低危批次流 D）：笑话页返回改 popBack（消除上游帧栈残留；fallback 主菜单） |
| F3-03 | 词库展示顺序 | TreeSet 后为字母序（advanced 排第一），非"初级→中级→高级"语义序 | 排序选型 | 低 | **已关闭**（随 P2 清理批次收口）：DIFFICULTY_ORDER 显式排序 |
| F3-04 | R-09d 尺寸分流约束 | 若未来 openHidden 改 54 格会重新塌回主页分支（约束已写注释） | 尺寸耦合 | 低 | 已记录（code-review 关注点） |

### 来自流 4（隐私与场景）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| 已关闭 | 广场内周/月/总榜仍列隐身者 | getRankedPlayers 未过滤 | 同根残留 | 高 | 已关闭（编排者 fan-in 时补：双方法 SQL 过滤 + ALL_PLAYERS 装配跳过） |
| F4-01 | `AIChatManager.buildSystemPrompt(ownerId)` | 重启后首次纯命令进个人场景 prompt 回落通用句（不走懒加载） | 读内存不触发加载 | 低 | **已关闭**（随 P2 清理批次收口）：懒加载触发 |
| F4-02 | ScenarioSelectGUI 7 格上限 | 个人场景 ≥2 个时 slot 17 玻璃可点，开启"看不见"的场景 | 无分页/扩容 | 低 |**已关闭**（R-49/R-53 收口：截断+more 槽+shownCount 边界，点隐藏场景缺陷已消） |
| F4-03 | `getPersonalScenarios` 返回活引用 | 暂无写入方，安全但属潜在污染面 | API 设计 | 低 | **已关闭**（随 Wave 3A 3A-2 收口）：经 PersonalScenarioStore 返回防御性拷贝 |
| F4-04 | 他人成就墙无独立隐私检查 | 目前无直达命令（依赖主页入口守卫），暂无绕过路径 | 纵深缺失 | 低 |**已关闭**（低危批次流 D）：成就墙点击补 showInSquare 纵深守卫（返回帧只在放行路径压入） |

### 来自流 5（数据崩溃）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F5-01 | `resolvePlayerUUID` 幂等缺口 | reset 删了 player_data 行后，再对同一离线玩家 reset 报 not found（半删状态无法补删） | 解析与被删数据同表 | 低 | **已关闭**（随 P2 清理批次收口）：R-86h 补 getOfflinePlayer 三级查找 |
| F5-02 | `AdminCommandHandler.addWord` | chinese 空串不断言，写入后下次加载被跳过但管理员无感知 | 入口无校验 | 低 |**已过时**（addWord 入口不可达零调用；可达导入路径 importWordBank 已有非空校验——方法本体随低危批次清理） |
| F5-03 | deletePlayerData 与自动保存竞态 | reset 后 persistLongestStreak/recordCoinsEarned 可能重建残行（重进覆盖，影响极小） | 时序 | 低 |**已关闭**（低危批次流 B）：deletePlayerData 竞态注释（备查） |
| F5-04 | `clearCache()` 不清今日计数器 | reload 后防刷上限残留（"reload 不重置防刷"语义可接受但反直觉） | 语义未对齐 | 低 |**已关闭**（裁决注释在位：reload 不重置防刷为既定语义，计数器按自然日懒清零+quit 清） |

### 来自流 6（日历二确）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F6-01 | `MainMenuListener.java:170-173` vs `:150-154` | stage1 确认 2-tick 延迟 reopen 与取消竞态：点了取消仍被拽回二确第二页 | 延迟体内无 stage 校验 | 中 |**已关闭**（P2 S1：延迟体三连复查 isOnline+stage+targetLang） |
| F6-02 | `ConfirmationGUI.isConfirmGUI` 标题子串匹配 | 未来他 GUI 标题含 Confirm/确认会被误判 | 匹配过宽 | 低 |**已关闭**（P2 S1：isConfirmGUI 等值比对重写） |

### 来自流 2 重跑（领取幂等）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2R-01 | `hasPendingWordRewardForDate` 时序契约 | "必须在 clearAllWordChallengeClaims 前调用"是隐式契约，调错顺序误判 | 契约隐式 | 低 |**已关闭**（低危批次流 F）：显式调用约束入 javadoc |
| F2R-02 | `onWordCorrect` pending 重试 return | 补发失败时本次答对的挑战词不走标记（好在已在全完成集合内，无实际丢失） | 重试与标记耦合 | 低 |**已关闭**（低危批次流 F）：重试分支无丢失注释 |
| F2R-03 | `getTodayChallenges` 无 ORDER BY | 领取 idx 下标与展示可能错位（与 R-33 同根） | 无稳定排序 | 中 |**已关闭**（R-33：ORDER BY challenge_id 在位） |

### 来自合流测试（建议级，非阻塞）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| T0-01 | 6 处监听器缺 onInventoryDrag guard | Shift+拖拽可带出展示物（仅 DuelGUI/MainMenu 有） | 漏 guard | 低 | **已关闭**（随 P2 清理批次收口）：全库 listener 均有 onInventoryDrag |
| T0-02 | `clearCache()` 漏清今日计数器 + profile 缓存 | reload 后沿用旧内存值 | 清理不全 | 低 |**已关闭**（profile 缓存 HotReload 成对清；计数器为 F5-04 裁决语义） |
| T0-03 | `SocialListener` 无快照回退重建未过滤隐身 | 与渲染错位但点击仍查开关，只进笑话页不泄主页 | 回退路径漏过滤 | 低 |**已关闭**（R-37 fan-in：无快照兜底重建已补隐身过滤） |
| T0-04 | `loadPlayerLanguages` 缺省 en vs `getPlayerLanguage` 缺省 zh | 默认语言不一致 | fallback 未统一 | 低 |**已关闭**（P2 S8：loadPlayerLanguages 与 getPlayerLanguage 缺省同源 zh） |

### 本批次语义裁决记录（已定，无需再议）

- GUI 分区判断统一模式 = `getClickedInventory()==null → return；rawSlot<0 或 >=topSize → return；确认上半区后才 cancel`（8 个监听器全覆盖）。
- 领取防重 = per-player 32 槽条纹锁串行化（Listener 层锁内重读 + Manager 层 guarded UPDATE 双防线，L→M 单向不死锁），失败可重领语义保留。
- 庆祝双条件 = `allDone && paid`（paid 读内存标记零 DB 开销），pending 时只提示不开页。
- 社交快照 = 按 viewer UUID 分键双 Map + quit 清理；笑话页按 inventory 尺寸（27/54）分流；词库顺序 = TreeSet 字母序快照。
- 隐私口径 = GUI 装配过滤 + SQL LEFT JOIN 过滤 + 点击守卫 + 命令直达检查四层；`/le profile` 隐身回聊天提示不开界面。
- reset = 12 表 + quit 语义内存清理（QuizState→落盘→成就缓存→删表，不可调序）；今日计数器 quit/reset 即时归零。
- 日历锚点 = `today.with(MONDAY).minusWeeks(4)` 保尾部；二确语言 = per-player (stage, targetLang) 二元组存储。
- messages 版本 = v9（三处对齐）。

---

## 批次：Phase 2 复活（4 流 + 合流测试）

> 合流测试 verdict：GO。阻断项为零。以下为本批次上报的任务外发现，一律未修（除注明"已顺手收"两条）。

### 来自流 A（数据链复活）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2-01 | `DailyChallengeManager.checkAndAssign` MySQL 分支 | INSERT 无 `ON DUPLICATE KEY`，并发重入可能抛 duplicate（SQLite 分支有 OR REPLACE） | 双后端不对称 | 低 | **已关闭**（随 P2 清理批次收口）：补 `ON DUPLICATE KEY UPDATE` |
| F2-02 | `player_sessions.score` / 周榜月榜口径 | `recordSession` 仍存会话学习分，榜单 SQL 未动；周榜月榜是"学习分榜"还是"金币榜"口径未定 | 口径文档缺失 | 低 |已记录（默认口径文档化：周/月/赛季榜=学习分（total_score），金币走独立 coins 列——既有实现即此口径，无需改 SQL） |

> 流 A 上报的 `RewardEngine` fallback 残留（review-multiplier 0.5）已由编排者在 fan-in 时修复（0.5→1.2），不再列账。

### 来自流 B（真三模式）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2-03 | `ChatAnswerListener` 练习分支 | `getPracticingWord(...)` 取出后从未使用（死变量） | 历史残留 | 低 |**已关闭**（低危批次流 A）：practiceWord 死变量随清理删除 |
| F2-04 | `Word.equals()` 含 `reward` 字段 | 同英文不同 reward 的词判不等，`getRandomWords` 的 exclude 可能放行同英文词做干扰项（本批次已在 `AnswerChecker` 内加英文去重兜底） | equals 语义过宽 | 低 | **已关闭**（随 P2 清理批次收口）：equals 收窄为 english(ignoreCase)+part_of_speech |
| F2-05 | `MiscGUIListener.handleWordBankSelect` 写死 `"choice"` | 默认模式第三个散落点（另两处已收敛为常量/回落值）；将来改默认模式时此处会漏 | 散落常量 | 低 | **已关闭**（随 P2 清理批次收口）：散落常量收敛 |

> 流 B 上报的旧 `answer-broadcast` 死键与 F1-06 同一事项，不重复列账。

### 来自流 C（决斗挑战）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2-06 | `DuelManager.rewardEngine` 字段 | 构造即赋值，之后零读取（死字段） | 历史残留 | 低 | **已关闭**（随 P2 清理批次收口）：死字段已删 |
| F2-07 | `DuelRoom.submitAnswer` | `answer.trim()` 无 null 防护（上游理论非 null） | 防御缺失 | 低 | **已关闭**（P2 S3 已修）：submitAnswer null 防护在位 |
| F2-08 | `DuelManager.cancelDuel` 约 550 行 | `opponentId` 算而不用 | 历史残留 | 低 | **已关闭**（P2 S3 已修）：死方法 cancelDuel(UUID) 已删除 |
| F2-09 | `en.yml` 缺 `word-learning-gui.countdown-urgent` | 中英 key 不对称（本次新增 key 双语已对齐，这是历史遗留） | 历史遗留 | 低 | **已关闭**（随 P2 清理批次收口）：en.yml 已补键 |

> 流 C 上报的 `sendDuelRequest` 过期任务裸 `remove` 已由编排者在 fan-in 时修复（改 `remove(k,v)`），不再列账。

### 来自合流测试（三维冒烟）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2-10 | `WordLearningListener.pronounceCooldown` | 只 put 不删，quit 不清，无驱逐（每人一条，量级极小） | 无清理 | 低 | **已关闭**（随 P2 清理批次收口）：quit 清 pronounceCooldown |
| F2-11 | `scheduleMidnightRollover` 主线程同步查库 | 每玩家 1~3 条同步 SQL，百人服午夜会卡一下主线程 | 主线程 IO | 中 | **已关闭**（P3 遗留中危修复批）：午夜任务两段式重写（异步重活 + 主线程 Bukkit 面） |
| F2-12 | `DuelRoom.timeoutCurrentWord` 零 UUID 哨兵 | `getLastCorrectAnswerer()` 可能对外吐 `new UUID(0,0)`，若 `DuelGUI` 按此查名会有怪显示（未读 `DuelGUI`，待确认非定论） | 哨兵外泄 | 低 |**已关闭**（低危批次流 A）：getLastCorrectAnswerer 死 getter 删除（哨兵随字段保留） |
| F2-13 | `/le word` 成功提示 `{difficulty}/{mode}` | 填原始英文标识符（如 `choice`/`beginner`），中文玩家看到英文 | 未走 i18n label（review 路径已有 `mode-review` label，普通路径未跟进） | 低 | **已关闭**（随 P2 清理批次收口）：i18n label 接线（PF-ONB-03 补词库路径） |

> 合流测试建议的 `clearCache` 补 `playerNameCache.clear()` 已由��排者在 fan-in 时顺手收，不再列账。

### 本批次语义裁决记录（已定，无需再议）

- 复习闭环 = 普通结算写种子（幂等）→ 到期 due → 答对晋级 / 答错重置（count 归零 + now，下次 due = now + intervals[0]）→ 未作答词不动间隔；通知按自然日去重，手动 `/le review` 不受去重影响。
- 榜单口径 = total_score 是学习分（含 combo 与 -2 扣分），与 total_coins_* 零交叉；total_words_learned = 答对数；longest_streak 只增不减。
- 跨天 = 对齐次日 0:00:01 触发，每 24h 一次；先查昨日未领（只读）→ 再分配今日 → 最后清标记；昨日未领作废 + 提示（`rollover-voided`），领完的不打扰；离线玩家上线走 join 分配，无作废提示。
- 真三模式 = AnswerChecker 唯一收敛；choice 聊天栏字母（兼容全文兜底，GUI 点击留 Phase 3）；fill_blank 拼写判定；review 回落拼写；SPELLING_MASTER 要求 spelling + 至少拼对 1 题；modes.* 仍是死配置（有意为之，Phase 3 再定）。
- 决斗 = 单题超时读 answer-timeout（默认 30s，钳位 5~300），超时双方不加分直接下一题；GM 解散不判胜负；邀请消费与过期均为 `remove(k,v)` 原子校验。
- 挑战 = 双向只读可见，领取路径不变；单词全完成改聊天提醒 + `/le challenge` 重开庆祝页；庆祝按钮"已到账"语义。
- 词库 = beginner 317 / intermediate 349 / advanced 330，共 996 词；level 梯度 1-3/4-6/7-9；三档零重复；样本已并入删除。

---

## 批次：Phase 1 止血（5 流 + 打回修复 + 合流测试）

> Q1（自然走完会话拿不到奖励，已修）、B2（答对广播查错对象，已修）、Q2（两套金币记账，已删死代码）均已在本批次修复，不再列账。
> F0-01、F0-02 已由流 E 在本批次收口，状态更新为已关闭（见 Phase 0 批次备注）。

### 来自流 A（学习经济链）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-01 | `DailyChallengeManager.java:367`、`MiscGUIListener.java:132`、`CheckInManager.java:130` | 每日挑战/签到发币直接 `eco.depositPlayer`，不经过 `RewardEngine`，`money.daily-cap` 管不住这三处 | cap 只覆盖 RewardEngine 4 条路径 | 中 | **已关闭**（随 P2 清理批次收口）：三处均走 `RewardEngine.depositCappedReward` |
| F1-02 | `DuelManager.submitAnswer` | 对决答题不计入今日答题数（不走 `submitAnswer`，不调 `incrementTodayAnswerCount`） | 口径未覆盖 | 低 |已记录（默认口径：对决答题不计入今日答题数——对决无发币无刷币口，保持现状） |
| F1-03 | `AchievementManager.getAchievementConfig()` | 非同步懒加载，`tryUnlock` 可达异步路径，与 `clearCache` 置 null 竞态（最坏多读一次文件，无脏数据） | 懒加载未同步 | 低 |**已关闭**（P2：achievementConfig volatile 在位） |
| F1-04 | cap 提示 `{earned}` 显示 | `String.valueOf(double)` 显示 `490.0` 式小数 | 未格式化 | 低 |**已关闭**（P2 S5：formatCoins 整数判断消除 490.0 式显示） |

### 来自流 B（AI 与公平）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-05 | `AIChatManager.triggerAiOpening` 经 `chatAsync` | 开场白触发语占 1 条有效输入并消耗 1 次每日轮次（GUI 入口每次开对话都调） | 开场白走正常计数通道 | 低 | **已关闭**（随 P2 清理批次收口）：`countsTowardUsage` 接线 |
| F1-06 | `messages/zh.yml:351`、`en.yml:351` `answer-broadcast`（含 `{answer}`） | 已无代码引用，留存易被后人误用 | 兼容旧包保留 | 低 |**已关闭**（4B：旧 answer-broadcast 已删，保留 answer-broadcast-correct） |
| F1-07 | 个人场景可见性 | 个人场景只能 `/le dialogue <个人key>` 进入，场景选择 GUI 和 Tab 补全看不到；`LearnCommand` 回显个人 key 时显示原始 key 而非展示名 | 白名单未覆盖 GUI/Tab 类，刻意收敛 | 中 |**已关闭**（R-04：个人场景 GUI/Tab 补全/展示名接线） |
| F1-08 | `ChatScenarioListener` 超限体验 | 超限时先显示一次"AI 正在输入…"再报错 | `aiTyping` 提示在轮次检查之前 | 低 | **已关闭**（随 P2 清理批次收口）：`canSendChat` 只读预检先行 |

### 来自流 C（挑战领奖）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-09 | `MiscGUIListener.handleChallengeComplete` slot 8/22 | 庆祝 GUI 的"领取奖励"按钮纯装饰（只回主菜单），奖励早已自动到账，文案误导 | M18 统一入口未做 | 低 | **已关闭**（随 P2 清理批次收口）：庆祝页 slot 22 语义修正（"奖励已到账"） |
| F1-10 | `wordChallengeClaimedToday`（`HashSet`） | 非线程安全 + `clearWordChallengeClaim` 零调用，跨天/长期运行残留过期条目 | 无午夜清理任务 | 中 | **已关闭**（随 P2 清理批次收口）：`ConcurrentHashMap.newKeySet` + 跨天清理 |
| F1-11 | 经济门不一致 | `CheckInManager.giveCoins` 检查 `rewards.types.money.enabled`，挑战发放点与 `RewardEngine` 不检查——服主关了金币奖励挑战仍发钱 | 开关未统一 | 中 | **已关闭**（随 P2 清理批次收口）：`isMoneyEnabled` 单一真相 |
| F1-12 | `DailyChallengeGUI` / `handleDailyChallenge` `idx=(slot-11)/2` | 槽位映射假设恰好 3 个挑战，分配数量变化会错位 | 硬编码映射 | 低 | **已关闭**（随 Wave 2 MC-5 收口）：`CHALLENGE_SLOTS` + binarySearch |
| F1-13 | 领奖双击 interleaving | "到账成功但标记返回 0"极小概率（���次点击交错），钱已发出但无成功消息 | 无按玩家串行锁 | 低 | **已关闭**（随 P2 清理批次收口）：32 槽 stripe lock |
| F1-14 | 单词挑战补发跨天丢失 | 待补发 pending 在跨天（新日期重新分配单词）后按新日期判定会丢失 | 跨天逻辑未做 | 低 | **已关闭**（随 P2 清理批次收口）：跨天清理链 |

### 来自流 D（决斗冲突）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-15 | `DuelManager.acceptDuel` 开头 | 先 `pendingRequests.remove(targetId)`，后继任一校验失败都会吞掉邀请，需发起方重发 | remove 先于校验 | 低 | **已关闭**（P2 R-68 已修，状态滞后回写）：peek 校验全过 → remove(targetId, pending) 原子消费 |
| F1-16 | `DuelManager.sendDuelRequest` | 只查 `isBusy` 不查答题态；单槽 `pendingRequests` 被后请求覆盖 + 先请求的 60s 过期任务误删后请求 | 既有逻辑 | 低 | **已关闭**（P2 R-27/R-66/R-68 已修）：答题态+AI 对话对称检查 + token 匹配 |
| F1-17 | `ChatScenarioListener`（AI 情景对话） | 第四类聊天消费者，与 quiz/决斗/练习均无互斥 | 超出 M4 三态范围 | 中 | **已关闭**（随 P2 清理批次收口）：R-66 纵深防御 |
| F1-18 | 决斗分支异步判定 | `isInDuel` 后 cancel 事件、主线程才 `submitAnswer`，若决斗恰在两次之间结束，该条聊天被吞且无反馈 | 判定与消费不在同一线程/时刻 | 低 |**已关闭**（低危批次流 A）：两分支补 duel.not-in-duel 反馈（复用既有键） |

### 来自流 E（数据 Owner）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-19 | `daily-round-limit: 0` 语义 | 流 B 实现 `todayRounds >= dailyRoundLimit` 无 `>0` 门，0 = 拒绝所有 AI 对话而非"不限"（流 A 两道门有 `>0` 门，语义不统一） | 门条件不一致 | 低 |**已关闭**（Wave 1 终案）：0/负数=不限（config 注释 + CHANGELOG 明示；禁用走 ai-chat.enabled）——状态滞后回写 |
| F1-20 | `PlayerDataStore.loadPlayerData` | 两个独立同步块之间复用同一 `Connection` 对象：若另一线程恰好触发连接重建，第二个块拿旧连接 | 存量模式 | 低 |**已关闭**（低危批次流 B）：第二锁块锁内重取 Connection（E-04 范式） |
| F1-21 | `PlayerDataStore.getPlayerName` | 仍直调 `Bukkit.getOfflinePlayer`（异步可达性存疑），但已无排行榜路径调用 | 存量 | 低 |**已关闭**（低危批次流 B）：cache-first + isPrimaryThread 守卫（主线程回落名册/非主线程回落 uuid） |
| F1-22 | 重启丢失今日计数 | 今日答题/AI/金币计数器内存态，重启丢失（设计接受：防刷上限不是账本） | 设计决定 | 低 |已记录（设计接受默认：防刷上限非账本，重启丢失可容忍；若运营要求持久化需单开 Story——默认不修） |

### 来自合流测试（三维冒烟 + 打回修复）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-23 | `DailyChallengeManager.depositWordChallengeReward` vs `MiscGUIListener.depositChallengeReward` | 两份 Vault deposit + `recordCoinsEarned` 拷贝；Listener 直接调 `DatabaseManager.recordCoinsEarned` 属越层写 DB | 流 C 白名单收敛所致 | 低 |**已关闭**（R-34/F1-23：两侧统一走 RewardEngine.depositCappedReward，不再直调 recordCoinsEarned） |
| F1-24 | 今日计数 Map 无驱逐 | 三处今日计数 Map + `todayCoinsCache` 只增不删（quit/reload 不清，靠日期懒清零），UUID 缓慢堆积 | 无驱逐 | 低 |**已关闭**（quit 清 todayAnswerCount/todayAiRound/economyDisabledNotified；todayCoinsCache 裁决语义；wordChallengeClaimedToday 午夜全清有界） |
| F1-25 | `broadcastCorrectAnswer` 语言 | 取模板用答题者语言，发给所有收件人同一语言版本，混服收件人可能看到对方语言 | 单模板广播 | 低 | **已关闭**（随 P2 清理批次收口）：R-86m 按收件人语言渲染 |

### 本批次语义裁决记录（已定，无需再议）

- daily-cap 口径 = **部分发放到 cap**（剩余额度不足时发到 cap 为止，绝不超发；额度用尽整笔拦截）。流 E 验收曾写"整笔拦截"，经读代码确认真相为部分发放，以此为准。
- 答题数门 = `getTodayAnswerCount > daily-limit` 拦截（判分即+1 配套：第 50 题及当题触发的会话完成奖正常发，第 51 题起拦截）。流 E 曾报"门休眠"，系检查时流 A 未落盘的时间差，已证伪。
- `count-min-rounds` = 对话结束时有效性门槛（有效输入 ≥N 且有成功 AI 回复才计一次有效对话），不参与 `daily-round-limit` 累加。
- 个人场景存储 = `plugins/LetsLearnEnglish/player_scenarios/<UUID>.yml`，key 形如 `personal:<uuid前8>:<slug>`，仅本人可用，不写全局、不全局 reload。
- 单词挑战补发 = 下次答对自动重试（不跨天场景下有效），跨天丢失已知（F1-14）。

---

## 批次：Phase 0 基建冻结（4 流）

### 来自流 C（数据库基线）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-01 | `PlayerDataStore.java` 等 25 个 `getConnection()` 站点 | 外部类裸用 `getConnection()` 自执行 SQL，绕过 `connectionLock` | M16 残余缺口 | 高 | 已关闭（Phase 1 流 E 收口：26 个同步块全覆盖，零跳过） |
| F0-02 | `PlayerDataStore.java:349-393`（`savePlayerData`） | 共享连接上 `setAutoCommit(false)` / `commit()` 跨线程事务污染 | 事务边界未与锁对齐 | 高 | 已关闭（Phase 1 流 E：save 整段事务收进同一锁） |
| F0-03 | `AdminCommandHandler.java:80-82`（`resolvePlayerUUID`） | 按 `player_name=?` 查库恒空，离线玩家按名 `/lea reset <name>` 失效 | `player_data.player_name` 全代码库零写入点（同 H10） | 中 |**已关闭**（H10 player_name 落盘 + F5-01 三级查找） |
| F0-04 | `DatabaseManager.java:257-264`（`getConnection` 快路径） | 无锁读 `connection` 并 `isClosed()`，与 `shutdown()`/重连存在极窄 TOCTOU 竞态窗口 | 快路径为性能省略加锁 | 低 |**已关闭**（E-04：getConnection 整体收进 connectionLock，锁外快路径已删） |
| F0-05 | `DatabaseManager.java:46`（`initialize()`） | 直调会覆盖 `connection` 字段而不关闭旧连接，无幂等保护（本次仅就地加固为锁内，未加"先关旧连接"） | 原实现假设 initialize 只跑一次 | 低 |**已关闭**（E-05：initialize 先 closeQuietly 旧连接再覆盖） |

### 来自流 B（配置 / i18n 基线）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-06 | `ProxyMessenger.java:168-169` | `messages.getString("dialogue.proxy-achievement")` 返回 null 时紧接 `template.replace(...)` 会 NPE，无兜底 | 直接读原始 config，未走安全封装 | 中 |**已关闭**（R-78：resolveAchievementTemplate null 兜底回落 zh） |
| F0-07 | `MainMenu.java:36` | `translateAlternateColorCodes('&', messages.getString("gui.main-menu-title"))` 无 null 守卫，key 缺失即 NPE | 同上，未走安全封装 | 中 |**已关闭**（getGuiMsg 收敛，永不 null） |
| F0-08 | `UpdateChecker.checkAsync()` 于 `LetsLearnEnglish.java:194-195` | `general.check-updates` 是死配置且注释误导：称"关闭后不连 GitHub API 适合内网"，但代码无条件联网，内网/隐私场景无法通过配置关闭 | 开关从未接线 | 中 | **已关闭**（随 Wave 1 收口）：`LetsLearnEnglish` 已读 `general.check-updates` 接线（F0-08 修复） |
| F0-09 | `config.yml` `gui.main-menu-title` vs `messages/*.yml` 同名键 | 重复定义：管理员改 config.yml 的标题不生效；且 `MainMenuListener` 拿 i18n 标题做点击判定，两者漂移会导致主菜单点击检测失效 | 双真相源 | 中 |**已关闭**（4B：config 版键已删，双真相源消除） |
| F0-10 | `config.yml` `progress-system.achievements.config-file: "achievements-config.yml"` vs `ConfigManager.loadAllConfigs()` 硬编码 `loadConfig("achievements.yml")` | 文件名不一致且该 key 零读取 | 死配置 + 命名漂移 | 低 |**已关闭**（4B 删键 + H-1 死链整链移除） |
| F0-11 | 模块级 `enabled` 开关族（`word-system`/`ai-chat`/`progress-system`/`daily-challenges`/`leaderboard`/`achievements`/`rewards`） | 设为 `false` 是静默 no-op，管理员以为关了实际没关 | 开关从未 gating | 中 | **已关闭**（随 Wave 1 MC-2 收口）：`ConfigManager` 11 个 `isXxxEnabled` 门真接线 |

> 流 B 另产出 `tasks/dead-config-audit.md`（21 条必查死配置 + 约 17 条额外死配置），此处不重复。

### 来自流 A（测试安全网）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-12 | `WordLearningManager.java:338` | `if (consecutiveCorrect <= 0) return 1.0;` 是冗余守卫（删掉结果完全一致），纯死分支 | 可读性冗余 | 低 |**已关闭**（低危批次流 F）：冗余守卫删除（319 输入逐位等价实证 + 单测 6/6） |

### 来自流 D（词库流水线）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-13 | `resources/words/sample-beginner.yml` | 样本文件位于 resources 下会被 Maven 打进 JAR（运行时不会被加载，安全） | 无排除规则 | 低 | 已关闭（编排者在 pom.xml 加 `words/sample-*.yml` 排除） |
| F0-14 | 运维提示 | 若管理员把 sample 手动拷进服务器 `words/`，会被当作名为 `sample-beginner` 的难度加载 | 无 | 低 | 已记录（`resources/words/README.md`） |

---

## 交叉引用

- H6 悬案结论：`word-system.modes.*` 整棵子树死配置（答案 NO），见 `tasks/dead-config-audit.md` 附录。
- M16 现状与升级建议：见 `tasks/db-baseline.md`。
- 完整原始 bug 清单（H1~H11 / M1~M20）：见 `tasks/plan-roadmap-usable-to-delightful.md`。
