# 审查总清单（Phase 2 后全量审查，11 路报告合并去重）

- 审查日期： 2026-10-11
- 基线版本： 1.0.7-SNAPSHOT（commit 4f9d7a4，Phase 2 已提交）
- 报告来源： 自派 6 路（A 答题判定链 / B AI 与 TTS / C 决斗 / D 挑战签到成就 / E 数据并发安全 / F 社交 GUI 命令）+ 串场 7 份（B/C/D/W/A/L/I 七路 GUI、词库、监听器命令、i18n 配置方向，来源待主人确认，结论已逐条核验采信；其中 L-01/L-11/R-01/L-03/R-07/R-08/R-09 及串场 A-01/A-02/C-01/D-02/D-03 已由编排者读代码核实）
- 处置原则： 本清单只记录不修复；修复排期由主人定夺。凡标注"编排者已核实"的结论为编排者读代码确认过。

---

## P0：高危（建议优先修，共 9 条）

| # | 位置 | 现象 | 触发条件 | 严重 | Phase 2 新引入 | 来源 |
|---|------|------|----------|------|---------------|------|
| R-01 | `MiscGUIListener.java:48`（`onInventoryClick` 用 `getSlot()` 不分区） | 打开 DailyChallenge/ChallengeComplete 等 GUI 时，点自己背包同号槽位误触发 GUI 逻辑：在 DailyChallenge 点背包 11/13/15 会走领取分支（含 deposit + claimReward 真发钱）；庆祝页点 8/22 被送回主菜单 | GUI 打开时点下方背包对应槽位且有物品 | 高（真发钱） | 否（历史遗留；串场 L-08 确认 MainMenu/DuelGUIListener/SocialListener 同 pattern 四处全中，最危险的是对决中点背包 22=直接判负） | 串场 A-02 + L-08（编排者已核实前者） |
| R-02 | `DailyChallengeManager` 行为挑战领取（`MiscGUIListener.java:121-146` deposit-then-mark） | 双击领取可双重到账：Vault 转出两次，guarded UPDATE 只保标记不保转账 | 延迟高时双击领取按钮 | 高（直接刷钱） | 否（M12 设计缺陷） | D-06 |
| R-03 | `AdminCommandHandler.java:33-74`（`/lea reset`） | 清理不彻底：PLAYER_TABLES 只删 4 张表（漏 sessions/profiles/daily_challenges/daily_challenge_words/ai_chat_history/difficult_words/checkin_data）；且没调 `AchievementManager.unloadPlayer()`，unlockedCache 旧成就"复活" | 管理员对老玩家执行 reset 后重进 | 高 | 否 | E-02 |
| R-04 | 个人场景 4 处无参调用（`ScenarioSelectGUI:65`、`ScenarioSelectListener:49,122`、`LearnCommand:183,585`） | 导入成功却无处可用：GUI/补全/回显全看不到个人场景，只能手输全 key | 任意 load_scenario 导入后 | 高 | 否（Phase 1 断链，Phase 2 未收尾） | B-07 |
| R-05 | `SocialListener.java:193-198` vs `:297-304`、`LearnCommand:234-248` | showInSquare 被排行榜直达绕过：广场点击查隐身，排行榜点击与 `/le profile <名>` 直达完整主页（串场 L-03 补刀：三条榜单 SQL 只查 player_data，未关联 player_profiles.show_in_square 过滤，隐身者直接上榜） | 隐身玩家被经排行/命令点开主页 | 高（隐私承诺失信） | 否（L9 未修） | F-05 + L-03 + L-04（合并） |
| R-06 | `WordBank.java:50` + `Word.java:97-108`（自带词库绕过 Validator） | words/*.yml 缺 `english` 键即 `new Word(null,…)`，随后 selectWords/getWord/equals/hashCode/check 任一处 NPE 炸主线程任务 | 管理员改 yml 删错一个 key 后玩家开会话 | 高（唯一可达崩溃链） | 否（存量；自带词库走构造器直载，只在管理员导入路径调 Validator） | A-13 + W-03（同根合并） |
| R-07 | `LearnCommand.java:278-281、308-311`（`/le challenge`） | 单词奖励实际未到账（reward-pending）时仍发"奖励已发放"并打开庆祝页（slot22 显示"已到账"），与事实不符 | Vault 不可用/存款失败后执行 /le challenge | 高（Phase 2 新回归：信任断裂） | 是（FIX-CHAL-01 把 /le challenge 做成重开入口后暴露；命令层只看完成数不看发放状态） | 串场 A-01（编排者已核实：278/308 只看 completed>=total；all-complete-chat 文案写"奖励已发放"） |
| R-08 | `LearningCalendarGUI.java:91-112`（网格锚点） | 最近几天（含今天）经常不显示，today-marker 永不出现；startDate 非周一时必现 | 打开过往学习日历 | 高（致命体验断裂） | 疑似是 | 串场 D-02（编排者已核实：gridStart 锚在 startDate 周一而非 today，尾部 offset 天被 isAfter(today) 丢弃） |
| R-09 | `ConfirmationGUI.java:41-44`（targetLang 实例字段）+ 监听器重建实例 | 二确切换语言错乱/回调丢失：confirmStages 只记 stage 不记 targetLang，跨 inventory 重建实例即丢目标语言，点确认切错语言或无反应 | 阶段1点 slot13 进阶段2（若监听器 new 新实例） | 高（致命，需先确认监听器是否重建实例） | 疑似是 | 串场 D-03（编排者已核实：targetLang 确为实例字段、map 只存 stage；待确认监听器行为） |
| R-09b | `SocialListener.java:36-37,119-132,291-304`（lastSquareGui/lastLeaderboardGui 全局单例） | 两玩家同时开广场/排行榜，B 的翻页/点头像复用 A 的 mode/玩家列表，打开 A 列表里的人（跨玩家串号） | 两人同时开广场或排行榜并点击 | 高 | 否 | 串场 L-01（编排者已核实：确为监听器全局单例字段，非按 viewer 分键） |
| R-09c | `MiscGUIListener.java:87-98` vs `WordBankSelectGUI.java:23` + `WordManagerImpl.java:41`（wordBanks=ConcurrentHashMap） | 词库≥2 个时显示槽位与点开难度可能对不上：GUI 构造快照一次顺序，点击又重取一次顺序，两次无序迭代可不一致 | 点击选择词库 GUI slot10~16 | 高 | 否 | 串场 L-11（编排者已核实：两边各 new ArrayList(getAvailableDifficulties()) 一次，底层确为 ConcurrentHashMap） |
| R-09d | `SocialListener.java:179-199` + `EnglishSquareGUI.java:266-296` | 隐身笑话页点 slot13 药水打开某玩家主页：笑话页标题仍含"英语广场"被同一分支处理，playerIds 取全局 lastSquareGui 列表 | 打开他人隐身页后点中央药水 | 高 | 否 | 串场 L-07 |

---

## P1：中危（功能错 / 体验断 / 隐私，共 30 条）

### 答题与复习

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-10 | `AnswerChecker.java:56-63` | 小词库 choice 局输入不存在的选项字母（C/D）被当错误答案结算（连击清零/-2/错题+1），而非无效输入提示 | 是 | A-01 |
| R-11 | `WordLearningGUI.java:189-205` | fill_blank 卡片无中文题面，只看 GUI 不知道考哪个词 | 是 | A-04 |
| R-12 | `LearnCommand.java:199-220` + `WordManagerImpl.java:188-197` | 有复习通知却开不出局：词库删词后通知与开局判断不一致，玩家被晃且无解释 | 否 | A-08 |
| R-13 | 无死亡/传送/切世界监听 | 倒计时中死亡被记错扣分进错题本（下线已处理，死亡未处理） | 否 | A-11 |
| R-14 | `AnswerChecker.java:152` + 4 处屈折形例句 | fill_blank 挖空正则要求原形，delay/delayed 等屈折形例句匹配失败回落光秃 `_____`，语境丢失（intermediate behave 同类） | 是（旧数据问题被新模式暴露） | W-02 + F-16（合并） |
| R-15 | `ChatAnswerListener.java:172-175` | 迟到答案静默丢弃无反馈（"本题已过"提示缺失） | 否 | A-07 |

### AI 与 TTS

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-16 | `AIChatManager.java:378-390,590` | 开场白触发语占 1 有效输入 + 1 每日轮次（"开→说2句→bye"会被计数） | 否（F1-05 已登记） | B-01 |
| R-17 | `AIChatManager.java:422-454,610-657` | quit 时 in-flight 请求跨会话污染计数器（按 UUID 不按 session 隔离，下线仍被扣轮次） | 否 | B-03 |
| R-18 | `AIChatManager.java:346-372` | startConversation 不清 pending/lastRequestTime，GUI 连点两场景旧会话白聊 + 新会话首条被误拒 | 否 | B-04 |
| R-19 | `AIChatManager.java:568-590` | 失败请求计有效输入不计轮次（口径不对称放宽门槛）；拒绝请求仍烧 3 秒冷却 | 否 | B-02 |
| R-20 | `AIChatManager.java:679-715` | 429/401/5xx/超时统一失败，无重试无熔断（401 后继续打 API 烧钱） | 否 | B-11a |
| R-21 | 存量 AI_CHAT_5 任务 | Key 中途失效后已分配任务永久卡死（分配过滤只管增量，不管存量） | 否（FIX-DAY-01 修一半） | B-11b + D-02（合并） |

### 决斗

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-22 | `DuelGUIListener.java:46` | 点自己背包 22 槽误退对决（点击分支漏 Top 容器检查，拖拽分支是对的） | 否 | C-01 |
| R-23 | 无 `/le duel gui` + `refreshBothGUIs` 全量重开 | ESC 后回不去 + 每题强制弹 GUI 打断背包整理（一对矛盾；Phase 2 超时新增一处刷新放大后半） | 否（放大） | C-02 |
| R-24 | `duel.started` 文案 | 决斗吞所有聊天、闲聊送分，赛前无任何警告 | 否 | C-03 |
| R-25 | `DuelRoom.java:127-142` | 超时/锁题后迟到答案静默吞（哨兵 UUID 副作用），玩家以为消息没发出去 | 是 | C-04 |
| R-26 | `DuelManager.java:462-467` | 宽限"对局已无"分支只 remove 不 cancel，任务泄漏空转（较难触达） | 否 | C-07 |
| R-27 | `DuelManager.java:74-93` | sendDuelRequest 不查答题忙态（M4 只在 accept 侧），quiz 中能发出挑战 | 否���Phase 1 遗留） | C-05 |

### 挑战 / 签到 / 成就 / 奖励

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-28 | `AIChatManager.java:75,442` | AI_CHAT_10/50 计数纯内存，重启清零成幽灵成就 | 否 | D-09 |
| R-29 | `HotReloadManager.java:32-38` + `PlayerDataStore.java:722-734` | reload 语义分裂：金币累计清零但答题/AI 计数保留，reload 后可再拿满 daily-cap | 部分是 | D-10 |
| R-30 | `AchievementManager.java:271-303` | DAILY_STREAK_3/7 判定差一天（check 在 recordSession 之前，当日少算） | 否 | D-08 |
| R-31 | `DailyChallengeManager.java:425-449,591-629` | 跨天丢失 pending 单词奖励（内存 set 午夜清空 + DB 按新日期查，重试钩子永不触发） | 否（F1-14 确认） | D-04 |
| R-32 | `DailyChallengeManager.java:246-269` | updateProgress SELECT 无行直接 return（M8 残留）：跨午夜答题/结算进度静默丢失 | 否 | D-05 |
| R-33 | `DailyChallengeGUI.java:45-46` + `MiscGUIListener.java:123-125` | 领取槽位与挑战列表错位（两次查询无 ORDER BY，行序可变；guarded UPDATE 保不重发，只错位） | 否 | D-14 + 串场 A-03（合并） |
| R-34 | `DailyChallengeManager` + `MiscGUIListener` + `CheckInManager` 发币点 | 挑战/签到发币直连 Vault 绕过 RewardEngine 日限（daily-cap 少计；F1-01/F1-11 已知） | 否 | D-07 |
| R-35 | `PlayerDataStore.java` showResults | 死开关：可翻转但无任何逻辑消费，玩家关了毫无变化 | 否 | F-04 + L-06（合并） |

### 社交 GUI / 命令 / 数据

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-36 | `EnglishSquareGUI.java:170-194` | 尾页不满时上一页按钮消失，玩家卡在尾页回不去（pageFull 门禁同时管两个按钮） | 疑似是 | 串场 C-01（编排者已核实） |
| R-37 | `EnglishSquareGUI.java:77-95` + `LeaderboardGUI.java:95-145` | 隐身玩家仍被广场/排行榜列出（列表页无 showInSquare 过滤，unescape 在点击后） | 疑似是 | 串场 C-02 + C-07（合并） |
| R-38 | `ProfileGUI.java:87-149` | 他人主页无视隐私开关全裸（字数/正确率/场次/金币/成就全渲染） | 否 | 串场 C-05 |
| R-39 | `ProfileGUI.java:121-128` | 年龄无校验向陌生人公开，未设置显示 0 | 疑似是 | 串场 C-04 |
| R-40 | `LearnCommand.java:308-311` | `/le challenge` 庆祝页无 in-duel/in-quiz 守卫、无 pushBack，决斗/答题中执行被强制切屏；且全完成后每次执行都重弹重发、无"今日已展示"去重，奖励数字是命令层重读配置镜像、与实际到账可能不一致 | 是 | F-09 + L-15（合并） |
| R-41 | `GuiNavigationManager.java:22-48` + quit 未清理 | 返回栈 quit 不清：泄漏 + 重进点返回打开过期界面（闭包持旧 Player/GUI） | 否 | F-08 + 串场 D-12（合并） |
| R-42 | `core/progress/Leaderboard.java:117` + PAPI:84 | `%lle_rank%` 开服后永久冻结（updateScore 零调用，只读启动快照） | 否 | E-01 |
| R-43 | `LLEPapiExpansion.java:67-135` | PAPI 无视隐私开关 + 单次最多 4 次同步 DB + 主线程查库（计分板刷新全砸 connectionLock） | 否 | E-11 |
| R-44 | `CheckInManager` + `LearnCommand:412-421` | money.enabled=false 下签到/答题 0 金币仍报成功，无"经济未启用"提示（M14 后半延续） | 否 | F-13 |
| R-45 | `PlayerJoinListener.java:25-38` | 进服零指引（无欢迎语/教程/命令指引） | 否 | F-14 |

### GUI 系统性（串场 B/L 路）

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-46 | `MainMenu.java:37,43` | menu-size 可配但背景与槽位按 27 写死：配 36/54 后半空，配 9/18 越界或抛异常 | 否 | 串场 B-01 |
| R-47 | `MainMenu.java:82` + `MainMenuListener:122-125` | 错题本按钮槽位 14 + 材质写死不走配置（其余 6 按钮都走） | 否 | 串场 B-02 + F-12（合并） |
| R-48 | `WordLearningGUI/ScenarioSelectGUI/MainMenu` 全文件 | `messages.getString()` 裸调直接 replace/translate，缺任一键（尤其 Phase 2 新键）打开界面即 NPE | 部分是 | ��场 B-03 |
| R-49 | `ScenarioSelectGUI.java:62-91` | 场景按钮 HashMap 乱序 + 超 7 场景/3 难度静默截断无分页；点击可能点 A 进 B | 否 | 串场 B-04 + B-05（合并） |
| R-50 | `ScenarioSelectGUI.java:81` | sc.material 无空判，配错即 NPE | 否 | 串场 B-10 |
| R-51 | `DuelGUI.java:41-47,78-87` | 标题 substring(0,32) 拦腰截断颜色码致匹配失败；缺 key 时 NPE | 疑似是 | 串场 D-07 |
| R-52 | 多文件 NPE（AchievementWall/Progress/WordBankSelect/LearningCalendar/Confirmation） | 缺任一 messages key 即 NPE，玩家点开对应 GUI 直接报错 | 否（老写法） | 串场 D-08 |
| R-53 | `WordBankSelectGUI.java:21-24,37-45` | 构造器快照 availableBanks，open 不重查（新词库不显示）；slot 10-16 写死无翻页（第 8 个起点不到） | 否 | 串场 D-09 |
| R-54 | `WrongBookGUI.java:97-109` | 空错题本中央 BOOK 在 slot22（∈WORD_SLOTS），点击触发删词/练习逻辑或 NPE | 疑似否 | 串场 D-10 |
| R-55 | `ChallengeCompleteGUI` 注释 + slot 行为 | slot 8/22 行为一致但注释暗示不同；庆祝页返回绕过返回栈硬回主菜单 | 部分是 | 串场 A-05 + A-06（合并） |
| R-55b | `SocialListener.java:291-292` | 刚用 `/le leaderboard` 打开排行榜点头像无反应，必须先点一次 tab（lastLeaderboardGui==null 直接 return，与广场分支有 store 回退不对称） | 否 | 串场 L-02 |
| R-55c | `PlayerDataStore.java:793-795,858-860` + `EnglishSquareGUI.java:79-86` | 广场 ALL_PLAYERS 混入从未在线的离线 UUID：getActivePlayerIds=profileSettingsCache.keySet()，且 getProfileSettings 用 computeIfAbsent 只读带写副作用 | 否 | 串场 L-05 |
| R-55d | `MainMenuListener.java:98-132` vs `MainMenu.java:60-88` | 管理员一改 gui-layout.main-menu 槽位按钮就静默错位/失效（渲染读配置，监听写死槽位） | 否 | 串场 L-09 |
| R-55e | SocialListener/MiscGUIListener 无 Drag 处理 | 广场/主页/排行/隐私/词库/日历界面可被拖拽塞入异物或拖走展示物（只有主菜单和对决写了 guard） | 否 | 串场 L-10 |

### 配置 / i18n / 权限（串场 I 路）

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-55f | `config.yml:114-131` modes.* 全段 | spelling/choice/fill-blank 子键全死，全仓无 `modes.` 读取，管理员改了无效（AnswerChecker 注释自认写死） | 部分是 | 串场 I-C05 |
| R-55g | `config.yml:147` max-context-pairs:6 | 死配置，AIChatManager 从不读取，与"最近 N 轮"注释矛盾 | 否 | 串场 I-C06 |
| R-55h | `config.yml:443,449-453` challenges-per-day + rewards.* | 死配置，DailyChallengeManager 硬编码 30/50/25/40 与取 3 | 否 | 串场 I-C07 |
| R-55i | 模块级 enabled 开关族 | 除 money.enabled 外全无 getBoolean 门控，关了关不掉 | 否 | 串场 I-C08 |
| R-55j | `config.yml` 九处散键 | check-updates/debug/mysql pool-size/achievements config-file/on-join/custom-textures/difficulty-settings/proxy/leaderboard update-interval 有键无读 | 否 | 串场 I-C09 |
| R-55k | `config.yml:200` vs `EnglishSquareGUI.java:74` | leaderboard.display-count yml=10，代码 fallback=50，删键行为与注释不一致 | 否 | 串场 I-C10 |
| R-55l | `config.yml:22` vs MessageUtil/ConfigManager/LLE/ProxyMessenger | 默认语言 fallback 三处打架（zh vs en），缺键/首启语言不一致 | 否 | 串场 I-C11 |
| R-55m | 场景分享两层门不一致 | 命令查 scenario.import，分享页 UI 门查 admin：有 import 无 admin 者命令可进、GUI 不显示 | 是 | 串场 I-C14 |
| R-55n | `MessageUtil.java:117-154` | 占位符 null 值直接 replace 必 NPE；Safe 版全仓零调用 | 否（Phase 0 遗留） | 串场 I-C15 |
| R-55o | `DailyChallengeManager.java:33` COMBO_5 target=1 | 显示"达到 1 连击"语义错（实际完成走 consecutive>=5），target 应为 5 | 疑似是 | 串场 I-C19 |

---

## P2：低危（边角 / 文案 / 体验瑕疵，共 31 条）

| # | 位置 | 现象 | Phase 2 新引入 | 来源 |
|---|------|------|---------------|------|
| R-56 | `AnswerChecker.java:56-60` | 单字母单词（a/b/c/d）在 choice 下输入全文被按选项下标误判 | 是 | A-02 |
| R-57 | `WordLearningManager.java:195` vs `:100-106` | 超时结算答案纯 "apple"，判分是 "B. apple"，格式不一致 | 是 | A-03 |
| R-58 | `PlayerDataStore.java:647-663` | seedReview 幂等守卫致重学不刷新时钟（设计取舍，备查） | 否 | A-09 |
| R-59 | `WordLearningManager.java:350-357` | 下线 endQuiz 静默落库：rage quit 也算一个 session 计入成就进度 | 否 | A-12 |
| R-60 | `AIChatManager.java:549-557` | getTodayAiRound 异常默认拒绝（纯内存读几乎不抛，误伤极小） | 否 | B-05 |
| R-61 | `AIChatManager.java:751-784` | 同玩家个人场景静默覆盖（同名/归一化/中文名互撞，无提示仍报成功） | 否 | B-06 |
| R-62 | `AIChatManager.java:791-834` | 个人文件坏了只 warn 不隔离（每次重读刷屏）；无大小/条数上限 | 否 | B-08 |
| R-63 | `AIChatManager.java:488-521` + `config.yml:147` | max-context-pairs:6 是死配置，注释"最近 N 轮"与纯 token 预算实现矛盾；system 超 2000 token 时裁不动直发 API | 否 | B-10 |
| R-64 | `WordLearningListener.java:157-168` | TTS 冷却 map 无界 quit 不清（量级可接受）；配 0/负被钳到 1 无法禁用 | 是 | B-12 |
| R-65 | `TtsManager.java:103-112` | Content-Type 空头 fail-open 可被绕过；octet-stream 配错误文本漏网；旧毒 wav 留存至过期 | 是（校验新增，fail-open 分支亦新） | B-13 |
| R-66 | `ChatScenarioListener.java:57-123` | AI 与 quiz/决斗无互斥，会话静默挂起（F1-17 仍在）；& 分支健康 | 否 | B-14 |
| R-67 | `AIChatManager.java:866-879` | shutdown 不管在途请求，关服回调打到已禁用插件刷栈 | 否 | B-15 |
| R-68 | `DuelManager.java:106-122` | 同一发起方 60s 内重复邀请触发"假过期"（remove(k,v) 只防异方不防同方重发） | 是 | C-06 |
| R-69 | `DuelManager.java:462-467` | cancelDuelByAdmin 后离线方重连只收泛用 already-over，不知被 GM 解散 | 是 | C-08 |
| R-70 | `DuelGUI.java` 头像 | 双方头像全是 Steve 头（owner 参数悬空，p1/p2 材质算出未用） | 否 | C-09 + 串场 D-06（合并） |
| R-71 | `/le duel accept/quit/cancel` | 名叫 accept/quit/cancel 的玩家永远无法被挑战（子命令优先路由；cancel 分支是新的） | 否（cancel 入口是新的） | C-10 + F-10 + L-18（合并） |
| R-72 | `LetsLearnEnglish.java:203-245` | onDisable 不清理决斗（记分牌/任务），实际影响小 | 否 | C-11 |
| R-73 | 决斗与 AI 对话 | 可同时开启，AI 侧被饿死（体验问题，无崩溃串话） | 否（F1-17 已知） | C-12 |
| R-74 | `DailyChallengeManager.java:129-145` | 重抽注释概率算错（5 轮全失败约 24% 非"理论不可能"），兜底是高频路径 | 是 | D-01 |
| R-75 | `LetsLearnEnglish.java:370-402` | 午夜任务 24h 固定周期 DST 偏移/停机无 catch-up（reload 不 double-schedule 已验证无问题） | 是 | D-03 |
| R-76 | `CheckInManager.java:23` | playerLocks 只增不删（一次性玩家泄漏）；Vault 缺失签到扣 streak 但 coins=0 仍报 success | 否 | D-11 |
| R-77 | `DatabaseManager.java:984-999` + `RewardEngine.java:137-153` | 终身统计（元）与日限记账（分）精度分叉，小数奖励下差几分 | 否 | D-12 |
| R-78 | `ProxyMessenger.java:159-172` | 跨服广播模板 NPE 半残（split 修了，模板空未修）；achievementName 含 \| 安全已验证 | 否 | D-13 |
| R-79 | 次要打包 | MySQL INSERT 非幂等 double-assign；completed 后 progress 仍累加显示 47/10；checkDailyStreak 全表扫无 LIMIT | 否 | D-15 |
| R-80 | `en.yml` 缺 duel-cancel 三键 | 英文 GM 执行 cancel 看到 Missing message（zh 有 en 无） | 是 | F-03 |
| R-81 | `MessageUtil.java:181-206` | null-safe helper 零调用（只建不用，新 key 缺失即崩非降级） | 否 | F-11 |
| R-82 | 决斗/签到零菜单入口 | 纯 GUI 玩家找不到决斗与签到 | 否 | F-15 |
| R-83 | `advanced.yml:2285` | vanquish 错位插在 d 区段（排序破坏） | 疑似是 | 串场 W-01 |
| R-84 | 音效缺失 6 处 | 学习超时/邀请过期/GM 解散/庆祝即时/签到+作废/败者无音效（不对称，体验） | 部分是（W-07 是） | 串场 W-04~W-09（合并） |
| R-85 | 词库内容瑕疵 | book synonyms 超纲、whose lv3 突兀、跨档 level 非单调、两例句生硬 | 疑似是 | 串场 W-10 |
| R-86 | `MainMenu/ScenarioSelectGUI/SoundManager` | 菜单 menu-open 播两次；点背景玻璃也播 click；未知音效键静默丢弃 | 否 | 串场 B-06 + B-11 + B-12（合并） |
| R-86b | `MiscGUIListener.java:64-67` | 隐私页点返回直接 open 自己主页不走 popBack，返回栈残留旧帧 | 否 | 串场 L-12 |
| R-86c | `LearnCommand.java:134-161` + `WordManagerImpl.java:91-94` | difficulty 无归一化/存在性校验，英文原始异常直接抛给玩家；两处 VALID_MODES 各自为政有漂移风险 | 部分是 | 串场 L-14 |
| R-86d | `LearnCommand.java:318-329` | 行为挑战名缺任一语言 key 时 `/le challenge` 整段报错（getString null 进 translate） | 是 | 串场 L-16 |
| R-86e | `LearnCommand.java:555` + help 文案 | `/le help` duel 行与 duel.usage 漏了 Phase 2 新增的 cancel 双入口 | 是 | 串场 L-17 |
| R-86f | `AIChatManager.java:752-757` | 纯中文名个人场景静默互覆盖（slug 全变下划线/key 碰撞），只剩后者 | 否 | 串场 L-19（与 B-06 同根，中文名子例） |
| R-86g | `PlayerJoinListener.java:25-38` | join 后 3 秒内退服仍发更新提醒（runTaskLater 闭包未复查 isOnline）；新玩家零指引见 R-45 | 否 | 串场 L-20（后半） |
| R-86h | `AdminCommand.java` reset/export | reset 对不存在玩家也报成功；export 失败返回 null 照打"完成"；宽限期离线决斗者 GM 无法解散（cancelDuelByAdmin 要求在线） | 部分是 | 串场 L-21 |
| R-86i | `MainMenu.java:37-46` | menu-size 配 36/54 时多出槽位无填充无意义（与 R-46 同根的表现侧） | 否 | 串场 L-22 |
| R-86j | 死 i18n 键 3 组 | answer-broadcast 旧键 / claim-button 对 / leaderboard 6 键 + word.* 老聊天键一批，有键无读者 | 部分是 | 串场 I-C01~C04（合并；前两组与 F-01/F-02 同事项） |
| R-86k | `config.yml:128` vs 代码 `fill_blank` | 连字符 vs 下划线命名不一，当前 dormant，一旦按 modes.<mode> 拼接接线即爆 | 是 | 串场 I-C12 |
| R-86l | `plugin.yml` 细粒度权限 | word.*/dialogue.*/progress.* 零 hasPermission 引用（M14 摆设依旧） | 否 | 串场 I-C13（与 E-14 同事项） |
| R-86m | `ChatAnswerListener.java:56-69` | 答对广播模板取答题者语言发全员（混服看 чужой 语言）；缺键静默 return；3s 在线缓存致刚进服收不到 | 是 | 串场 I-C16 + I-C17（合并；前半与 F1-25 同事项） |
| R-86n | `DuelManager.java:31` vs 文案 duel.opponent-timeout | "30 秒"硬编码进文案无占位，与 GRACE_PERIOD_SECONDS 脱钩 | 否 | 串场 I-C18 |
| R-86o | `ConfigManager.java:47-56` | REQUIRED_MESSAGE_KEYS 仅 8 键未覆盖 Phase 2 新键，缺键不告警 | 是 | 串场 I-C20 |
| R-86p | `LetsLearnEnglish.java:316-335` vs `daily-reminder.message` | 定时任务只替换 {prefix} 不替换 {player}，全员收到字面 {player} | 否 | 串场 I-C21 |
| R-86q | `plugin.yml:20-21` | learnadmin 把自身列为别名，冗余笔误 | 否 | 串场 I-C22 |

---

## 已确认无问题项（审查员明确 pass，不占编号）

- 提交/超时 CAS 防重入 + 终局快照替代 map 重查（Q1 修复彻底）；choice 快照 key 不串题；复习闭环真通；跨天调用顺序正确。
- 决斗超时 schedule/cancel 全配对、roundId 双防线、4:4 超时语义、宽限交互、重连恢复、平局不可能、发题渲染一致、GM 控制台路径。
- ScenarioCodec 编解码健壮无刷屏；下线/正常结束计数语义一致；quit→重进→bye 不刷计数。
- reload 无 double-schedule；save UPSERT COALESCE 正确；ONLY_FULL_GROUP_BY 合法；个人场景路径无遍历；ScenarioCodec 受聊天长度封顶。
- messages v8 三处对齐；config 新 key 路径与 fallback 全对上；DailyChallengeGUI slot22 确无动作；庆祝页口径一致；SoundManager 五键对上；duel cancel 补全门生效。
- 词库总数 996 = 317+349+330 对数。

---

## 统计

- 去重后共 116 条：P0 高危 12 条（R-01~R-09 + R-09b/c/d，其中 Phase 2 新引入/回归 3 条：R-07、R-08、R-09 待确认）、P1 中危 61 条（R-10~R-55 + R-55b~o）、P2 低危 43 条（R-56~R-86 + R-86b~q，另有 L-13 个人场景三处掉线已并入 R-04 不单列）。
- I 路 22 条处置：10 条入 P1（C05~C11/C14/C15/C19→R-55f~o），8 条入 P2（C01~C04→R-86j、C12→R-86k、C13→R-86l、C16+C17→R-86m、C18→R-86n、C20→R-86o、C21→R-86p、C22→R-86q），4 条为对齐确认无 bug（config-version 三处/新 key 默认值/占位符抽查）。
- L 路 22 条处置：3 条升级入 P0（L-01→R-09b、L-11→R-09c、L-07→R-09d），2 条并入已有（L-03/L-04→R-05、L-08→R-01、L-13→R-04、L-18→R-71），4 条入 P1（L-02→R-55b、L-05→R-55c、L-09→R-55d、L-10→R-55e），1 条并入 R-40（L-15），8 条入 P2（L-12/L-14/L-16/L-17/L-19/L-20 后半/L-21/L-22→R-86b~i），L-06（showResults 死开关）与 R-35 重复、L-20 前半（零指引）与 R-45 重复，不单列。
- Phase 2 新引入（含疑似）：R-07、R-08、R-09、R-10、R-11、R-14、R-25、R-29（部分）、R-36、R-37、R-39、R-40、R-51、R-54（疑似否）、R-56、R-57、R-64、R-65、R-68、R-69、R-74、R-75、R-80、R-83、R-84（部分）、R-85，共约 26 条。
- 与 dev-findings.md 已有条目去重说明：F1-05（开场白占轮次）= R-16；F1-06（answer-broadcast 死键）= F-01（未单列，归入 R-48 死键说明）；F1-10（已领标记线程安全）已在 Phase 2 收掉；F1-14（跨天丢 pending）= R-31；F1-15（吞邀请）已修；F1-17（第四类消费者）= R-66/R-73；F0-04/F0-05 = E-06/E-05（本次重申，未单列新编号，并入 E 路结论）。

---

## 建议修复顺序（编排者建议，主人定夺）

1. **P0 先行**：R-01（背包误触真发钱，四监听器同 pattern）→ R-02（双击重发）→ R-09b（广场串号）→ R-09c（词库点 A 开 B）→ R-07（庆祝虚报）→ R-06（词库 NPE）→ R-03（reset 清不净）→ R-04（个人场景断链）→ R-05（隐身绕过，含榜单 SQL 过滤）→ R-09d（笑话页串主页）→ R-08/R-09（日历锚点/二确丢语言，先确认后修）。
2. **P1 分批**：隐私组（R-37/38/39/43）→ 决斗交互组（R-22/23/24/25）→ 挑战成就组（R-28/29/30/31/32/33/34）→ AI 组（R-16/17/18/19/20/21）→ GUI 系统性组（R-46~R-55）→ 答题边角组（R-10/11/12/13/14/15）。
3. **P2 随缘**：进 Phase 3 顺手带，或单开 polish 批次。
