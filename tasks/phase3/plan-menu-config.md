# Phase 3 规划：菜单与入口收口 + 配置面终局（域 8）

- 日期： 2026-10-13
- 施工状态： **已施工**（MC-2/MC-1/MC-3/MC-5/MC-4/MC-6 随 Wave 1/2/3A 分批落地；**MC-7 死键大扫除待 Wave 4 4B**）
- 基线： 1.0.7-SNAPSHOT（commit `716d4fd` 之后；工作树含编排者当轮即修 P3-01/PF-SEA-01/02/03，未提交）
- 来源： `tasks/p2-polish-execution-plan.md` 第五节域 8；`tasks/review-phase2-total.md` P2 段（R-82 / R-55i / R-55f / R-55h / F0-11 等）；`tasks/dev-findings.md` P2 批次段；`tasks/dead-config-audit.md`（Phase 0 清单 + H6 附录）
- 规划纪律： 本轮只读，未修改任何源码/配置；规划期发现见第 6 节
- 域目标（一句话）： **消灭死配置与死入口** —— 到本域施工完成时，config.yml / messages 双文件里不再有"设了没用"的键，主菜单与各功能入口不再有"玩家找不到"的功能
- 规模估计： **L**（触及 ~25 个文件 + 2 个新建 GUI 类 + 1 个新工具类 + 1 个 Store 抽取类；8 个 story）
- 关联本域任务源： R-82（决斗/签到零入口）、R-55i/F0-11（模块开关死族）、R-55f/H6（modes.* 死配置）、个人场景无管理 GUI、F1-19（daily-round-limit=0 语义）、P2-13（裸 getString 二期）、P2-09（export 判定硬耦合）、P2-25 + PF-SOC-03（gui-layout 死键）、P2-23/P3-04（ProgressGUI 死类）、F0-09/F0-10（双真相源/命名漂移）、P2-28/PF-FB-05（AIChatManager 肥化 → 抽 PersonalScenarioStore）

---

## 1. 操作逻辑（逐项定案）

### 1.(a) 主菜单改版（7 按钮 → 9 按钮，维持 menu-size=27）

**现状事实**（已逐行核实）：27 格主菜单，按钮布局占槽 10-18（行 2 的 10/11/13/14/15/17 + 行 3 的 18）：
- 10 每日挑战 / 11 单词学习 / 13 AI 对话 / 14 错题本 / 15 个人主页 / 17 英语广场 / 18 语言切换
- 行 2 的空缺位恰为 **12 与 16**；menu-size 最小合法值 27 由"按钮占用到槽 18"决定（P2 已收紧 [27,54]）

**定案（推荐方案 A：8+1 满行布局，零移动、零扩容）**：

| 槽位 | 按钮 | 材质（默认） | 图标语义 | i18n 键 |
|------|------|------------|---------|---------|
| 10 | 每日挑战 | WRITABLE_BOOK | 不变 | main-menu.daily-challenge |
| 11 | 单词学习 | BOOK | 不变 | main-menu.word-learning |
| **12（新）** | **单词对决** | DIAMOND_SWORD | 与玩家 PvP 词汇 | main-menu.duel（新） |
| 13 | AI 情景对话 | PAPER | 不变 | main-menu.dialogue-practice |
| 14 | 错题本 | BOOKSHELF | 不变 | main-menu.wrong-book |
| 15 | 个人主页 | DIAMOND | 不变 | main-menu.personal-profile |
| **16（新）** | **每日签到** | SUNFLOWER | 连续签到领金币 | main-menu.checkin（新） |
| 17 | 英语广场 | PLAYER_HEAD | 不变 | main-menu.english-square |
| 18 | 语言切换 | RED_BED | 不变（**保留原位，不迁移**） | language.button |

- **为什么加直按钮而不是二级菜单**：加二级菜单需要新开一层 GUI + 返回栈 + 标题白名单 + 监听器分支（≈1 个新 GUI 的工程量），而本菜单恰好存在两个空槽（12/16），直按钮零布局成本；且 R-82 的痛点就是"找不到"，直按钮是发现性最优解。菜单保持单层，`/le menu` 一步到全部功能。
- **语言切换保留原槽 18**：迁移会破坏已自定义 gui-layout 的服主配置（`language-toggle` 键已接线），无收益。
- **两个新按钮同样走 `gui-layout.main-menu.*` 配置**（`slot`/`material`），渲染侧（MainMenu）与点击侧（MainMenuListener）读同一份配置 + 同一份模块门（见 1.(b)），延续 P2 的"双侧同源"模式。
- **点击行为**：
  - 对决 → 若玩家在对决中：重开当前对决界面（等价 `/le duel gui`）；否则打开 **新 GUI「发起对决」（DuelChallengeGUI，在线玩家选择器）**，点击某玩家头像 = `DuelManager.sendDuelRequest(player, target)`（内部既有全部忙态/AI 对话/答题互斥守卫直接复用，零新校验代码）。选择器为空时显示空态提示。**理由**：R-82 的"纯 GUI 玩家"找不到的不仅是按钮，还有 `/le duel <玩家名>` 的输入路径；给一个点头像即发邀请的 GUI 才是完整收口。
  - 签到 → 直接 `player.performCommand("le checkin")`（签到是瞬时动作：结果一条聊天消息 + 音效，无需专属界面；做成 GUI 只增加弹窗噪音）。按钮 lore 静态（不做"今日已签到"动态 lore——菜单打开是高频路径，Read 侧需要一次 `getCheckInData` 同步 DB 查询，为省一次主线程 IO 放弃该 polish，记录在案）。
- **整体门**：每个按钮渲染与点击都先过模块门（1.(b)）：关掉对应模块 → 该槽位回落灰色玻璃板占位（现有填充循环天然覆盖），点击不触发任何动作。

**被否方案（记录理由）**：
- 方案 B（36 格两行分类 + 二级菜单）：扩容 menu-size 默认值会波及所有已自定义 [27,54] 的服主配置，且二级化降低直达性；本域为"收口"，不做信息架构重构。若未来按钮再增长（新手引导域 1 计划加按钮），优先继续消耗 19-26 空槽，其次再考虑 36。
- 方案 C（签到做成 7 天阶梯 GUI）：有留存展示价值，但属新功能（连续奖励可视化），不属"入口收口"最小闭环；记为未来候选。

**预留**：行 3 空槽 19-26 中，**19 预留给域 1（新手引导按钮/引导书）**；其余空白。新增按钮必须走"gui-layout 槽位键 + i18n 双键 + 模块门"三件套规范（本域定契，见 4.1）。

### 1.(b) 模块开关接线（7 个既有 + 2 个新增 = 9 个，统一规范）

**统一规范（"关"的完整语义 = 三层门 + 一条底线）**：

1. **入口隐藏**：主菜单按钮不渲染（玻璃占位）；相关命令拒绝并回 `module.disabled`（新通用键）；相关 GUI 不可达。
2. **事件静默**：相关 listener 分支/管理器方法入口 no-op（不报错、不写数据、不广播）。
3. **定时静默**：定时任务体内加门（点读 config，reload 即时生效——升级现有"仅 enable 时判一次"的语义）。
4. **底线——数据永不删**：关闭只停"入口与新增写入"，绝不删除既有数据行/文件；重新开启立刻恢复显示与累计。唯一例外：签到停用期间连续签到自然断签（语义预期，文档化）。

**热重载语义**：全部门为**点读式**（每个 gating 点实时读 `ConfigManager` 方法，不缓存），`/lea reload` **即时生效**。唯一局限：少数"enable 时排程"的任务（统计 HTTP 服务器启停、数据库备份、每日提醒定点、榜单刷新任务排程）保持现状——reload 不重建任务（本域不扩权改 reload 重排机制，写入 config 注释明示）。

**实现载体（推荐）**：**集中式 `ConfigManager` 门方法**（编译期安全、单一文件、可 grep、易加注释）。不引入通用事件拦截层（每个系统自读最省、无框架税）：

```java
// ConfigManager 新增（9 个方法，全部点读 mainConfig）：
public boolean isWordSystemEnabled()      // word-system.enabled（默认 true）
public boolean isAIChatEnabled()          // ai-chat.enabled（默认 true）
public boolean isDuelEnabled()            // duel.enabled（新增键，默认 true）
public boolean isCheckinEnabled()         // checkin.enabled（新增键，默认 true）
public boolean isDailyChallengesEnabled() // daily-challenges.enabled（默认 true）
public boolean isProgressSystemEnabled()  // progress-system.enabled（默认 true）＝伞
public boolean isLeaderboardEnabled()     // progress-system.enabled && progress-system.leaderboard.enabled
public boolean isAchievementsEnabled()    // progress-system.enabled && progress-system.achievements.enabled
public boolean isRewardsEnabled()         // progress-system.enabled && progress-system.rewards.enabled
// 另：RewardEngine.isMoneyEnabled() 改为 = isRewardsEnabled() && money.enabled（单一真相收口）
```

**九个开关逐个定案**（"关"的语义 / 数据影响 / 什么还活着）：

| # | 开关 | 关闭完整语义（gating 落点） | 数据影响 | 什么还活着（有意保留） |
|---|------|---------------------------|---------|----------------------|
| 1 | `word-system.enabled` | 主菜单隐藏【单词学习】【错题本】；`/le word`、`/le review`、`/le wrong` 拒绝；WordBankSelectGUI/WrongBookGUI 不可达；错题本练习（聊天态）禁开；复习提醒任务体内门（`isWordSystemEnabled() && review.enabled`）；`ensureWordChallengesAssigned` 不再分配新单词挑战（防死任务）；进行中的答题会话**不打断**（自然结束） | 全部保留 | AI 对话、决斗（继续只读词库抽词）、签到、广场/榜单/成就（数据不再增长）、个人主页 |
| 2 | `ai-chat.enabled` | 主菜单隐藏【AI 对话】；`/le dialogue` 拒绝；ScenarioSelectGUI 不可达；`AIChatManager.startConversation/triggerAiOpening` 防御性 no-op（纵深）；`ChatScenarioListener` 不接新对话；**进行中的对话在下一条消息收到 `dialogue.module-disabled` 并自动退出对话模式**（消息不转公屏，与 bye 同口径，避免"输入被吞"）；`DailyChallengeManager.isChallengeAvailable(AI_CHAT_5)` 增查此门（此门是判断 AI 任务可完成性的唯一真相，防分配死任务） | 全部保留（历史可查） | `/le history`（历史只读）、`/le save_scenario`、`/le load_scenario`、场景管理 GUI（数据维护面保留）、TTS 发音（`tts.enabled` 独立）、其余全部 |
| 3 | `progress-system.enabled` | **伞开关**：false → 上述三方法（leaderboard/achievements/rewards）同时为 false（`isLeaderboardEnabled()` 等内部 AND）。不改任何独立读取点之外的行为 | 学习分/统计**继续累计**（数据保全，见底线） | 个人主页展示（停"伞"不停展示——伞下的三个子模块才是被关对象；理由：主页是既有数据的只读视图，关了会制造"数据还在但不给看"的困惑）、其余全部 |
| 4 | `daily-challenges.enabled` | 主菜单隐藏【每日挑战】；`/le challenge` 拒绝；`checkAndAssign`/`updateProgress`/`completeChallenge`/`onWordCorrect`/`ensureWordChallengesAssigned` 全部入口 no-op；`MiscGUIListener.handleDailyChallenge` 领取分支拒绝；午夜 rollover 跳过挑战相关段落（昨日作废提示/分配），复习 notified 清理照常 | 既有行保留；停用期间不产生新行 | 学习/签到/AI/决斗全活；`ChallengeCompleteGUI` 不可达 |
| 5 | `progress-system.leaderboard.enabled` | `/le leaderboard` 拒绝；LeaderboardGUI 不可达；**英语广场的周/月/总榜三个模式按钮隐藏 + 点击分支拒绝**（"广场"全量模式保留——它不是榜单）；启动时榜单刷新任务不排程 + 任务体内门；`%lle_rank%` 返回未上榜文本（不查库）；`updateScore` 内存榜**继续更新**（廉价的"热备"，重新开启无需重启即恢复） | 全部保留 | 广场全量模式、学习全部 |
| 6 | `progress-system.achievements.enabled` | `AchievementManager.tryUnlock` 顶部门（含连带：解锁提示/音效/跨服广播全部不触发）；个人主页【成就墙】按钮隐藏 + SocialListener 分支拒绝；`%lle_achievements%` 保留读取（历史只读） | 已解锁记录保留 | 其余全部；`/le challenge` 的挑战与成就无关 |
| 7 | `progress-system.rewards.enabled` | 等效 money 总闸：`isMoneyEnabled()`（RewardEngine）改为 `isRewardsEnabled() && money.enabled`，五个直读点全部改走此 helper（见 3.(b)）；行为挑战领取点**补门**（PF-MC-01）；经济未启用提示（R-44 三键）文案顺带泛化（"本服未启用金币奖励"） | 不发币，全部记录保留 | 签到仍记 streak；答题/挑战照常推进（只不发币） |
| 8 | `duel.enabled`（**新增键**） | 主菜单隐藏【单词对决】；`/le duel` 发起/接受拒绝（module.disabled）；**quit/gui 在"已在局中"时放行**（保证关开关后进行中对局仍可退出/重开界面，见 4.2）；`/le duel cancel`（GM 通道）放行；DuelChallengeGUI 不可达；`sendDuelRequest`/`acceptDuel` 顶部门；**进行中的对决不打断**（自然收尾；DuelListener disconnect/reconnect 是状态驱动，不门控） | 决斗零持久化，无数据面 | 其余全部；`/lea duel cancel`（GM 通道）保留可用（清零进行中对局的管理手段不应随功能关闭而失效——进行中的对局被关闭后仍需收尾手段） |
| 9 | `checkin.enabled`（**新增键**） | 主菜单隐藏【每日签到】；`/le checkin` 拒绝 | streak 数据保留；停用期间自然断签 | 其余全部 |

**新增键的落点**：`duel.enabled` 与 `checkin.enabled` 为两个新的顶层小节（风格对齐 word-system/ai-chat）：

```yaml
duel:
  # 是否启用单词对决模块
  # 关闭后：主菜单按钮隐藏、/le duel 全部拒绝、新邀请不再受理；
  # 进行中的对局自然收尾；/lea duel cancel（管理员解散）保留可用。
  enabled: true

checkin:
  # 是否启用每日签到模块
  # 关闭后：主菜单按钮隐藏、/le checkin 拒绝；签到记录保留；
  # 停用期间连续签到会自然断签（预期语义）。
  enabled: true
```

**九开关·删除键决议表（终局口径）**——（本域任务口径"逐项定方案"的最终汇总，供施工与合流对齐）：

| 项 | 决议 |
|----|------|
| 接线（真实现 gating） | 9 个模块 enabled（word-system / ai-chat / progress-system / daily-challenges / leaderboard / achievements / rewards / duel[新] / checkin[新]） |
| 接线（数值参数） | challenges-per-day、rewards.*(4)、leaderboard.update-interval、ai-chat.daily-round-limit（语义修正） |
| 接线（模式的 enabled ×3 + 参数 ×4） | modes.spelling/choice/fill_blank.enabled、case-sensitive、option-count、time-limit(语义修正)、show-first-letter |
| 删除 | modes.*.max-attempts ×2、general.debug、database.mysql.pool-size、difficulty-settings.*（整段）、rewards.types.{item,command,exp}.enabled、achievements.config-file、gui.main-menu-title、gui-layout.main-menu.scenario-select.back-button、gui-layout.profile.*（15 叶子）、+ i18n 死键清扫表全量 |
| 保留不动（移交/已活） | gui.custom-textures（→域 7）、tts.*、daily-reminder.*、general.check-updates、statistics-server.*（注释诚实化）、achievement-system.unlock-sound（注释诚实化）、money.* |

**九个开关与权限系统的关系**：本域**不做** per-player / 权限级模块开关（R-86l 既定裁决"细粒度权限暂不接线"）；模块开关是服主级运营闸。两者互不替代，写入注释。

### 1.(c) modes.* 接线定案（接线为主 + 删除 max-attempts）

**决策**：**接线**——子键逐一定义语义，**唯一例外是 `max-attempts` 删除**（理由见下）。接线后该段不再是死配置；命名不一致（`fill-blank` vs `fill_blank`）一并定案。

**命名定案（R-86k）**：canonical 采用**下划线 `fill_blank`**（与代码 `VALID_MODES`/`LearnCommand`/i18n 键一致，改代码名面大、改配置名面小）；**读取侧双兼容**（先读 `modes.fill_blank.*`，miss 回落 `modes.fill-blank.*`），出厂 config 写 `fill_blank` 并注释注明旧名兼容。理由：存量服的 config.yml 不会被插件覆写（主配置无迁移机制），双读保证老文件不静默失效。

**子键逐个语义**：

| 子键 | 定案 | 语义 / 落点 | 默认值等价性 |
|------|------|------------|-------------|
| `modes.spelling.enabled` | **接线** | 模式可用门：`/le word <难度> spelling` 拒绝（`word.mode-disabled`）；补全候选剔除；默认模式解析跳过 | 默认 true = 现状 |
| `modes.choice.enabled` | **接线** | 同上；另：默认模式解析（见下）跳过被关模式 | 默认 true = 现状 |
| `modes.fill_blank.enabled` | **接线** | 同上 | 默认 true = 现状 |
| `modes.spelling.case-sensitive` | **接线** | 仅影响**全词文本比较**（spelling 与 fill_blank 的整词判定、choice 的"直接输全词"兜底路径）；choice 的 **A/B/C/D 字母选择恒大小写不敏感**（那是下标选择器不是文本，不随此键）。适用点为 `AnswerChecker.check` / `isInvalidChoiceInput` 的文本比较。**duel 与错题本练习的判分路径不动**（两者不走 AnswerChecker 三模式链，文档化） | 默认 false = 现状（全词 equalsIgnoreCase） |
| `modes.choice.option-count` | **接线** | 选项数量；读取钳位 **[2, 4]**（字母空间 A-D 上限；<2 无干扰项无意义、>4 无字母）。钳位越界记一次 WARNING | 默认 4 = 现状 |
| `modes.choice.time-limit` | **接线，语义修正** | **0 = 跟随全局 `word-system.answer-timeout`（默认，也是出厂新默认值）；>0 = 覆盖 choice 会话的单题倒计时**。原注释"0 不限制"**删除**——"永不超时"不是现有能力（无计时器即无超时判错链），不为此新建语义。仅 choice 有此键；spelling/fill_blank/review 恒用全局值 | 出厂默认 **30 → 0**（值改写）+ 升级注意：若服主改过 answer-timeout，旧文件里 dormant 的 `time-limit: 30` 接线后会开始覆盖 → CHANGELOG 明示"把 modes.choice.time-limit 改为 0 以跟随全局" |
| `modes.fill_blank.show-first-letter` | **接线** | false = 隐藏首字母提示：GUI 挖空卡不渲染提示行、聊天答题提示不发 `fill-blank-hint` 行（**注意**：只隐藏"首字母提示"，题目本身与判定不变） | 默认 true = 现状 |
| `modes.*.max-attempts` | **删除（两键）** | 理由：现行为"答错即结算进下一题"；实现"每词 N 次重试"是**改变核心循环的新机制**——牵动连击语义（中途答对算不算连击）、扣分时机、超时链、错题本计数口径（按次还是按词）、DailyChallenge 进度、会话结算——属新功能 Story 而非配置接线。本域为收口域，不做机制新增；键删除 + 注释注明"如需三试机制请单开 Story" | —（删键零行为影响：从未被读） |

**"默认模式"第三个散落点一并收口（F2-05 复活）**：`LearnCommand.DEFAULT_WORD_MODE="choice"` 与 `MiscGUIListener.DEFAULT_WORD_MODE="choice"` 两份私有拷贝，改为统一 `WordModes.firstEnabledMode()`（按 spelling→choice→fill_blank 固定序返回第一个 enabled 者；全关时返回 null → 入口回 `word.no-modes-enabled` 拒绝）。

**AnswerChecker 策略适配层设计（不破坏"唯一判分收敛点"约束）**：

- `AnswerChecker` 保持无插件依赖的静态工具（其纯函数性是单测资产），**不注入 config**。
- 新增 `core/word/WordModes.java`（唯一配置真相源，静态方法，入参 `ConfigManager` 或读全量通过调用方传入快照）：
  - `isEnabled(ConfigManager, mode)` / `firstEnabledMode(ConfigManager)` / `enabledModes(ConfigManager)`（保序 LinkedHashSet）
  - `isCaseSensitive(ConfigManager, mode)` / `optionCount(ConfigManager)` / `timeLimitSeconds(ConfigManager)`（0=跟随）/ `showFirstLetter(ConfigManager)`
- `AnswerChecker` 判分签名扩展：新增 `check(mode, word, answer, options, boolean caseSensitive)` 与 `isInvalidChoiceInput(..., boolean caseSensitive)`；**旧签名保留**（委托新签名、caseSensitive=false），保证现有调用与测试零改动。
- `buildChoiceOptions(bank, answer)` 增加重载 `(bank, answer, int optionCount)`（旧签名委托 optionCount=4）。
- **快照语义**：`WordLearningManager.startQuiz` 时把 `caseSensitive`、`effectiveTimeout`（含 choice.time-limit 覆盖计算）、`choiceOptionCount` 快照进 `QuizState`（新增 3 个 final 字段）——**同一会话内判分规则恒定**，reload 中途不翻会话规则（文档化）。`show-first-letter` 属展示层，读取侧实时读（reload 即时生效，无一致性风险）。

**回归面（现写死行为与出厂默认必须等价）**：默认出厂配置下——模式全开、case-insensitive、4 选项、choice 时限=全局 30、提示显示——判分/渲染/超时链与接线前**逐位一致**；验收含逐项对照用例（5.(c)）。

### 1.(d) 个人场景管理 GUI（列出 / 查看 / 删除）

**现状事实**：个人场景（H7 起）存 `player_scenarios/<uuid>.yml`，仅本人可见，经 `importPersonalScenario` 导入（需 `letslearnenglish.scenario.import` 权限，plugin.yml 默认 op）；场景选择页可看到并进入，但**无任何列出/删除面**——导入后只能靠场景选择页 7 格截断展示，删不掉、超量后看不到。归档发现 P2-28/PF-FB-05：AIChatManager（1273 行）同时扛 HTTP + 会话态 + 个人场景文件持久化，本域**一并抽取 `PersonalScenarioStore`**（域 2 AI 反馈域已依赖此抽取，见 4.1）。

**定案**：

1. **入口**：场景选择页（ScenarioSelectGUI，27 格）新增按钮【管理我的场景】于 **slot 24**（空闲位；26 是 import 权限的 API 刷新键、18-20 难度、22 标签）。可见门 = `letslearnenglish.scenario.import` 权限（与个人场景功能全集一致——无此权限者永无个人场景，展示管理页只会困惑）。槽位**硬编码 + 公开常量**（延续 scenario-select 无 layout 配置的既有模式，P2-25 决定删掉该 GUI 的伪配置键，不新增）。空场景玩家也可进入（页面空态提示如何导入 —— 解决"根本不知道有这功能"的发现性问题）。
2. **管理页**：新建 `PersonalScenarioGUI`（54 格，复用 WrongBook 式布局：条目槽 42 个（9-44 + 45/46/48/50/52/53），**单页恒够**——个人场景上限 30 < 42，注释写明"若上限提升需加翻页"）：
   - 条目 = 场景材质（`resolveScenarioMaterial` 同款回退 PAPER）+ 展示名；lore = prompt 摘要（`ScenarioSelectGUI` 同款 45 字符截断多行）+ 分隔 + 操作提示 + 删除确认态（选中后变为 `scenario.manage-confirm` 红字）。
   - slot 4 = 页首标题物（`scenario.manage-count`："个人场景: 3/30"，实时读 `getPersonalScenarios(player).size()` 与 `getMaxPersonalScenarios()`）。
   - slot 8 = 返回（**pushBack 框架**：进入管理页前压「场景选择页」帧，返回弹回场景选择页；与 SocialListener 进入子页模式一致）。
   - 空态：slot 22 提示物（`scenario.manage-empty` + `scenario.manage-empty-lore`）。
   - **无"使用"动作**：进入场景对话由场景选择页承担（避免双入口语义漂移）；管理页专注管理。
3. **删除 = 二次确认（行内确认，不新开确认页）**：首点条目 → 该条目进入"确认删除"态（名字/lore 变红），其它条目/返回/开页面重置；**再次点击同一处于确认态的条目** → 执行删除；期间任何其它点击先复位。理由：复用既有"行内二确"词汇（ConfirmationGUI 是语言切换专用两页流程，不适合复用；新开确认 GUI 对"删一条列表项"过重）；防误点且一步可见。per-player 确认态 `Map<UUID, String>` 存 listener，**自注册 quit 清理**（F2-10/F1-24 教训）。
4. **删除执行（`PersonalScenarioStore.deletePersonalScenario(ownerId, key)` → record `DeleteResult(boolean deleted, boolean notFound, boolean saveFailed)`）**：
   - 校验：key 必须 `personal:` 前缀且登记在该玩家的 `personalScenarios` 内存副本中（纵深：key 已含 uuid8 前缀，再查内存归属，防跨玩家 key 注入）；
   - 文件操作：`YamlConfiguration.loadConfiguration(<uuid>.yml)` → `config.set("scenarios."+key, null)` → `save`（文件空表保留不删文件——reload 对空/缺段有既有容错，删文件反而多一条错误面）；
   - 内存刷新：直接从 `personalScenarios.get(ownerId)` 移除该 key（**同步原地更新，不做全量 reload**）；若玩家正处于该场景的对话中——**对话不打断**（system prompt 已在开局冻结，上下文与此无关；文档化）；
   - 线程：主线程同步执行（文件 ≤512KB、import 路径同为同步，保持一致；不上异步回调复杂度）；
   - 结果反馈：成功 `scenario.manage-deleted`（带场景名）+ menu-click 音；失败/未找到各 i18n 提示；删除后**原地重开管理页**（与 WrongBook 删词同模式）。
5. **与 R-62 上限/损坏容错的衔接**：删除不受上限影响；对 512KB 超限未解析的"空内存副本"玩家，删除只能通过内存登记不可见 → 结果 = notFound 提示（已知限制，记录）。
6. **场景选择页分页（MC-6b，可拆子项）**：管理页解决"删/看"，但选择页 7 格截断（全局 + 个人混排，个人最多 30）仍会让玩家"看不到/进不去"（R-49 尾槽是 no-op 提示）。**纳入本域为可拆子项**：选择页加分页（prev/next 按钮补足槽 17 与空位 + 标题附加 `({page}/{total})`，点击侧共用 `ScenarioSelectGUI` 公开常量与 `GUIUtils.extractPageFromTitle`，排序用既有 `getSortedScenarioKeys(player)` 切片）。若批次预算紧张可整体拆出单 Story（依赖面已在本文冻结），不影响主验收。

### 1.(e) challenges-per-day 与 rewards.* 接线（经济参数，默认零变化）

**现状事实**：`daily-challenges.challenges-per-day: 3` 死键（`checkAndAssign` 硬编码"4 选 3"）；`daily-challenges.rewards.{answer_10,session_3,combo_5,ai_chat_5}` 死键（数值硬编码在 `ChallengeType` 枚举：30/50/25/40）。相关既有硬编码链：`DailyChallengeGUI` 槽位 `{11,13,15}` 写死 + `MiscGUIListener` `idx=(slot-11)/2` 写死 3 项（F1-12 遗留）。

**定案**：

1. **`challenges-per-day` 接线**（读取钳位 **[1, 4]**＝枚举总数；越界 WARNING 并钳位）：
   - `checkAndAssign` 泛化为"从**可用**挑战类型中随机取 N 个"（N=min(配置值, 可用数)）。**同时顺手消灭 R-74 的 5 轮重抽 + 24% 兜底路径**：现逻辑"随机抽 3、含不可用项则重抽、5 轮失败走兜底"与"直接从可用集合 shuffled 取 N"在分布上等价（生效结果恒为可用集合的均匀随机 N 子集），但后者确定性、去掉了重试与兜底分支——推荐按此简化（若求最小 diff，也可保留重抽仅把 3 换成 N，由实施者定，行为等价性验收不变）。
   - **N=4 边界**：全部四类都分配；`allDoneToday`/跨天作废逻辑按"实际行数"工作，天然泛化，无需改。
   - **N > 可用数**：AI Key 缺失时可用=3，配 4 也只得 3（分配处 WARNING 一次，文档化）。
   - **GUI/监听器泛化（F1-12 必做配套）**：`DailyChallengeGUI` 公开常量 `CHALLENGE_SLOTS = {11, 13, 15, 17}`（27 格行 2 满行、均匀间隔；**N=3 时渲染位置与现状 11/13/15 完全一致——默认行为零变化**）；渲染取前 N 个；`MiscGUIListener` 的 idx 映射改为在数组上查位（WrongBook 模式），`(slot-11)/2` 删除。
   - **经济兼容性**：出厂默认 3 = 现状；改动只对被管理员主动编辑的服生效（玩家可见经济参数变化的显式预期）。CHANGELOG 写入"daily-challenges.challenges-per-day 现在生效（默认 3 无变化）"。
2. **`rewards.*` 接线**：`DailyChallengeManager` 新增公开 `getChallengeReward(ChallengeType)`：读 `daily-challenges.rewards.<id>`，**缺键/非法/≤0 一律回落枚举值**（枚举字段降级为"默认值容器"，javadoc 改写），越界在启动审计一次性 WARNING（在 ConfigManager.validateRequiredKeys 加 4 键存在性 + 数值正值检查，只告警不改行为）。**全部读取点改走此方法**：`MiscGUIListener.claimBehavioralChallenge`（`cp.type.reward` → `getChallengeReward`）、`DailyChallengeGUI` reward-hint 展示、`DailyChallengeManager.markClaimed` 返回值。
   - **兼容性**：默认值 = 现状（30/50/25/40）；≤0 钳回默认而非"0 奖励"——避免 spawn "deposit 0 → 判定失败 → 永远领不到" 的畸形分支（记录设计决策）。
   - `word-count` / `word-challenge-reward` 早已接线，不动。
3. **注**：`DailyChallengeManager` 同时是本域其它门的落点（ai-chat 门 for isChallengeAvailable、word-system 门 for 单词挑战分配、daily-challenges 门）——三处集中在同一文件一次改完（见 7. 批次）。

### 1.(f) daily-round-limit=0 终案 + P2-13 二期 + P2-09/P2-25 收口 + 死键终局

1. **`ai-chat.daily-round-limit` 终案：0 或负数 = 不限**（与 `word-system.daily-limit`、`money.daily-cap` 同口径——两者均为"0/负数=不限"，F1-19 的建议方向）。实现：`canSendChat`（AIChatManager:363-373）与 `dispatchChat`（:693-712）两处门改为 `if (dailyRoundLimit > 0 && todayRounds >= dailyRoundLimit)`。**"想彻底禁用 AI 对话"的服主改用什么**：本轮新增的 `ai-chat.enabled=false`（语义清晰、口径正确）；CHANGELOG + config 注释同时明示这两条路径。默认 30 不受影响；语义变化点仅"显式设 0/负数"的服（原为拒绝全部）——写入 CHANGELOG 的 Changed 段。
2. **P2-13 裸 getString 二期收敛（方案同 getGuiMsg）**：白名单 21 处（WordLearningListener 8 / WordLearningManager 9 / WrongBookListener 3 / DailyChallengeManager 1）**+ 本轮新确认的 DuelManager 4 处**（PF-MC-02：`setupScoreboard` 的 `duel.scoreboard-title`/`scoreboard-target`/`scoreboard-title-grace`，以及 `getRawMsg` → `sendDuelRequest:179` 的 `accept-hint`——后者是**真实 NPE 路径**：`translateAlternateColorCodes('&', null)`）。收敛规则：
   - 值直接进 `translate/replace` 的 → `MessageUtil.getGuiMsg(messages, key)`（缺键 `[?key]` 可见占位，不炸）；
   - DuelManager `getRawMsg` 改私有包装走 `getGuiMsg`（或直接替换调用点）；
   - **豁免保留**（P2-14/P2-20 同口径，不动）：`ProfileGUI:82/84`（带默认值）、`ChatScenarioListener:167` / `WordBankSelectGUI:92` / `DuelGUI:135` / `ProxyMessenger:169`（均已有 null 分支兜底）。
   - 验收：对 25 处逐点删区间内任一 key 不炸（不要求逐个跑，代码评审 + 抽样 3 键实测）。
3. **P2-09（export 判定字符串硬耦合）协议化**：`DataExporter.exportAllData()` 返回值由 `String`（含 `"Export failed: ..."` 魔法前缀）改 **record `DataExporter.ExportResult(boolean success, String filePath, String errorMessage)`**；`AdminCommand` 分支改判 `result.success()`。同时 `AdminCommandHandler`/DataExporter 不再有字符串协议。**跨域约定**：格式在此冻结（见 4.1，域 6 报告域若同批触碰 DataExporter 以本 record 为准；"CSV 写到 exports/ 子目录"的目录问题归域 6 报告域口径，不在本域强改——避免与 P3-07 结论打架，记接口备注）。
4. **P2-25（scenario-select.back-button 死键）+ PF-MC-03（gui-layout.profile.* 全死）+ F0-09（gui.main-menu-title 双真相）→ gui-layout 死键大扫除**（终局 = 删键 + 注释，不接线——接线无玩家价值且双侧硬编码已一致）：
   - 删 `gui-layout.main-menu.scenario-select.back-button`（错位嵌套的伪键；ScenarioSelectGUI/listener 双侧硬编码 slot 8 = 唯一真相，保持）
   - 删 `gui-layout.profile` 段全部**无读取者**叶子（PF-MC-03：`border-material`、`back-button`、`player-head`、`age`、`words-learned`、`accuracy`、`sessions`、`coins`、`settings-button`、`settings-back-button`、`settings-toggle-on`、`settings-toggle-off`、`settings-show-answers`、`settings-show-results`、`settings-show-in-square`——ProfileGUI 零 `getGuiSlot` 调用）；改段注释："个人主页/隐私设置页布局固定（写死=唯一真相，与 SocialListener 注释一致），不支持自定义"。保留 `achievement-wall.*-material`（已接线）。
   - 删 `gui.main-menu-title`（F0-09 双真相源中 config 侧的假键；标题唯一真相 = `messages/*.yml` 的 `gui.main-menu-title`）改注释指向 i18n 键。
5. **其余死键终局**（Phase 0 审计的收尾；逐键核对见 2.4 表）：`general.debug`（删——模块级 `ai-chat.debug` 已有；不做"主开关伞"避免再养一个容易脱钩的键）、`database.mysql.pool-size`（删——单连接无池）、`difficulty-settings.*`（全段删——难度列表=words/*.yml 自动发现、显示名=i18n `word-bank.difficulty-*`，注释写明）、`progress-system.rewards.types.{item,command,exp}.enabled`（删——未实现的奖励类型，注释"当前仅 money；如需新类型单开 Story"）、`progress-system.achievements.config-file`（删——F0-10，死链清理见 3.5）、`gui.custom-textures`（**不动**——域 7 资源包计划已占此键做 pack 开关决策，见 4.1）。
6. **半接线键修正**：`achievement-system.unlock-sound`（PF-MC-04）——值仅被空判、实际播放固定 `gui.sounds.complete`。修正 = **config 注释改写为事实**（"留空=静音；非空=播放入 gui.sounds.complete 配置的音效"），**不改代码**（改值经 SoundManager 需新增按名解析入口，属域 7 的 SoundManager 重构领地；若域 7 后续提供 `playConfigured(player, name)` 再升级注释与代码，记接口）。
7. **proxy 开关接线（选接线）**：`proxy.cross-server-announcements` → `ProxyMessenger.sendAchievementBroadcast` 顶部门；`proxy.cross-server-messages` → `onPluginMessageReceived` 的 `GlobalMessage`/`AchievementUnlock` 分支门（收到即忽略）。**通道注册保持无条件**（`/lea broadcast` 是显式管理动作，不随接收开关失效；"服务器不在代理后"已有注册失败容错）。两键从死配置升为真开关（单服关掉可省代理解析噪音）。
8. **`progress-system.leaderboard.update-interval` 接线（PF-MC-06）**：`startScheduledTasks` 的刷新任务周期硬编码 6000L ticks（5 分钟），同段 config 键被注释宣传为"秒"且从未被读（数值语义也漂移：秒 vs ticks）。**定案：接线 + 默认行为零漂移**——出厂默认值 30 改为 **300**（= 现状 5 分钟），钳位 [30, 3600]，注释统一为"秒；原硬编码 5 分钟，30 秒为下限"；任务排程期读该值（reload 不重排任务属既有语义，任务体内照常刷新——周期改动需重启，注释注明）。理由：收成"活键 + 显式默认"，不制造默认行为漂移。
9. **i18n 死键清扫（后附 2.4 表）**：zh/en 双文件对称删除 ~28 键（含 `answer-broadcast`、`daily-challenge.claim-button`(+lore)、`leaderboard.{header,entry,footer,title-weekly,title-monthly,title-alltime}`、`word.{correct,incorrect,session-end,mastery-update,daily-limit-reached}`、`checkin.streak-info`、`general.data-saved`、`admin.reset-pending`、`error.scenario-save-failed`、`dialogue-gui.*` 整段、`progress-gui.*` 整段（P2-23/P3-04 死类配套））。**删除前逐键全路径 grep 复核**（行号会漂，以实施时 grep 为准）；`MESSAGE_CONFIG_VERSION` 11 → 12（与域 2/4/5/6 共同目标版本，编排者合流时统一，后落者保持 12）。
10. **P2-23/P3-04 ProgressGUI 死类**：删除 `gui/ProgressGUI.java`（零实例化点）——与 9 的 `progress-gui.*` 键删除成套。

---

## 2. 用户体验

### 2.1 改版后主菜单 ASCII 布局草案（27 格）

```
┌──────────────────────────────────────────────────────┐
│              一起学英语！ (gui.main-menu-title)         │
├──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┤
│  0   │  1   │  2   │  3   │  4   │  5   │  6   │  7   │  8   │   行 1 全玻璃
│  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │
├──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┤
│  9   │ 10   │ 11   │ 12   │ 13   │ 14   │ 15   │ 16   │ 17   │
│  Ｇ  │每日挑战│单词学习│单词对决│AI对话 │错题本 │个人主页│每日签到│英语广场│
│      │📖金书 │ 📕书 │⚔️钻石剑│ 📄纸 │📚书架 │ 💎钻石│🌻向日葵│👤头像 │
├──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┤
│ 18   │ 19   │ 20   │ 21   │ 22   │ 23   │ 24   │ 25   │ 26   │
│语言切换│ (19 预留给域1  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │  Ｇ  │
│🛏️红床 │  新手引导)     │      │      │      │      │      │      │
└──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┘
Ｇ = GRAY_STAINED_GLASS_PANE 占位（含：被模块开关关闭时该槽按钮回落为 Ｇ）

按钮文案与 lore（i18n）：
  main-menu.duel:           &d&l单词对决    / main-menu.duel-lore:     &7与在线玩家比拼词汇
  main-menu.checkin:        &e&l每日签到    / main-menu.checkin-lore: &7连续签到领取金币奖励
```

**新 GUI「发起对决」DuelChallengeGUI（54 格）草案**：

> **标题避碰红线（重要）**：标题**不得**以 `duel.gui-title`（"单词对决"/"Word Duel"）为前缀、也不得含子串 "单词对决"/"Word Duel"——`DuelGUI.isDuelGUITitle` 做前缀比对 + 关键词兜底，原拟标题"单词对决 - 选择对手"会被 `DuelGUIListener` 误判为对决界面（点 slot 22 会被当成退出对决）。定稿标题 = **「选择对决对手」/ "Pick a Duel Opponent"**。同理，`DuelChallengeListener` 的标题判定必须用**完整标题等值**（读 `duel.challenge-title` 按 zh/en 两份配置）+ 尺寸 54 双重确认，不得用 "对决" 这类宽子串。

```
┌───────────────────────────────────────────────┐
│               选择对决对手               │ 标题键 duel.challenge-title（新）；54 格
├───────┬───┬───┬───┬───┬───┬───┬───┬───────┤
│ 0..8 行1：玻璃 + slot 4 说明物(duel.challenge-hint: 点击头像发起挑战；对方需 /le duel accept 接受) + slot 8 返回
├───────┴───┴───┴───┴───┴───┴───┴───┴───────┤
│ 9..44：在线玩家头像（复用 WrongBook 式 42 槽布局）；
│        头像 lore: 在线状态即可挑战 / 若对方 busy 提示 target-busy 由 manager 回
│        —— 不预判忙态（预判与点击竞态会误拒；交给 sendDuelRequest 既有守卫，提示准确）
├───────────────────────────────────────────────┤
│ 45/46/48/50/52/53：空白玻璃（单页 42 上限；在线 >42 时尾槽提示 duel.challenge-more）
└───────────────────────────────────────────────┘
交互：左键头像 → sendDuelRequest(sender, target)（全部互斥/忙态/离线反馈走既有 manager 文案）
     空在线（只剩自己）→ 空态物 duel.challenge-empty
```

**个人场景管理页 PersonalScenarioGUI（54 格）草案**：

```
┌───────────────────────────────────────────────┐
│            我的个人场景 (scenario.manage-title) │
├───────┬───┬───┬───┬───┬───┬───┬───┬───────┤
│ 0..3 玻璃 │ 4: 场景数(scenario.manage-count "个人场景: 3/30") │ 5..7 玻璃 │ 8: 返回场景选择页
├───────┴───┴───┴───┴───┴───┴───┴───┴───────┤
│ 9..44 + 45/46/48/50/52/53：场景条目（PAPER，共 42 槽 ≥ 上限 30）
│   条目 lore: [prompt 摘要…] 空行 "左键删除（需再次点击确认）"
│   首次点击 → 名称变红 + lore 变 scenario.manage-confirm "&c再次点击确认删除！"
│   再次点击（确认态）→ 删除 → 重开本页
├───────────────────────────────────────────────┤
│ 空列表 → slot 22 空态物（scenario.manage-empty: "还没有个人场景喵~" +
│          scenario.manage-empty-lore: "在 AI 对话中用 /le save_scenario 导出分享码，
│          再用 /le load_scenario 导入即可出现在这里"）
└───────────────────────────────────────────────┘
入口：ScenarioSelectGUI slot 24【管理我的场景】（仅 letslearnenglish.scenario.import 权限可见）
```

### 2.2 模块开关的服主管理剧本

**剧本 1："我想关掉决斗系统"**
1. 编辑 `config.yml` → `duel.enabled: false` → `/lea reload`；
2. 玩家侧即时表现：主菜单 slot 12 变回灰色玻璃（无按钮）；`/le duel` 任何子命令 → "该功能已被服务器管理员关闭喵~"（`module.disabled`）；已打开的对决界面不受影响、现有对局自然打完；挑战邀请失败（`module.disabled`——门在 sendDuelRequest 顶）；
3. 控制台：reload 日志照常，无报错。决斗无持久化数据，无数据影响。

**剧本 2："关掉金币奖励（平衡经济）"**
1. `progress-system.rewards.enabled: false`（或细粒度 `types.money.enabled: false`，两者等效叠加、都 false 才 false）→ reload；
2. 玩家侧：答题/会话完成/复习/挑战/签到**照常进行**，不再出现任何 `+N 金币` 行；每天首次触发发币时收到一次 `progress.economy-disabled` 泛化文案（"本服未启用金币奖励"）；个人主页金币栏显示 Vault 实际余额（0 或历史），签到天数正常累计；
3. 服主侧：`money.daily-cap`、PAPI `%lle_coins_earned%`（历史值）均不受影响；重新开启后立即恢复发币（今日 cap 账随 reload 清，见现状语义）。

**剧本 3："关掉成就系统"**
1. `progress-system.achievements.enabled: false` → reload；
2. 玩家侧：不再解锁任何新成就、无解锁播报/音效/跨服广播；个人主页【成就墙】按钮隐藏（既有解锁历史保留，重开后立刻回归）；PAPI `%lle_achievements%` 继续显示历史数字；
3. 学习/答题流完全不受影响（tryUnlock 是旁路调用，门在顶部）。

**剧本 4："关掉 AI 对话（省 API 费用）"**（两种强度）
- **软关**（保留历史与场景数据维护）：`ai-chat.enabled: false` → 主菜单按钮隐藏、`/le dialogue` 拒绝、进行中对话自动退出（发一行 `dialogue.module-disabled` 后回公屏聊天）；`/le history`、场景导入/管理照常；每日挑战不再抽 AI_CHAT_5（新玩家）、存量 AI 任务走 R-21 降级链。
- **限次（仍保留入口）**：`ai-chat.enabled: true` + `daily-round-limit: <N>`（N≥1，按次限）；注意 **F1-19 新语义下 0 或负数 = 不限**——想"几乎不给用"设 1，想"完全不给用"用上面的软关，不再有"0=拒绝全部"这条隐晦路径。

**剧本 5："关掉整个进度/榜单体系"**
- `progress-system.enabled: false` → 榜单/成就/奖励**全部**随之关闭（伞语义）；个人主页仍可看（展示层保留）；`/le leaderboard`、广场三榜按钮、成就墙全部隐藏/拒绝。重开即全恢复（数据未删）。

### 2.3 i18n key 草案（zh/en 双文件；`MESSAGE_CONFIG_VERSION` 11 → 12）

**新增键**：

| key | zh | en | 用者 |
|-----|----|----|------|
| main-menu.duel | &d&l单词对决 | &d&lWord Duel | MainMenu |
| main-menu.duel-lore | &7与在线玩家比拼词汇 | &7Challenge online players | MainMenu |
| main-menu.checkin | &e&l每日签到 | &e&lDaily Check-in | MainMenu |
| main-menu.checkin-lore | &7连续签到领取金币奖励 | &7Check in daily for coin rewards | MainMenu |
| duel.challenge-title | &6&l选择对决对手 | &6&lPick a Duel Opponent | DuelChallengeGUI（**不得**含"单词对决"/"Word Duel"子串——见 2.1 标题避碰红线） |
| duel.challenge-hint | &7点击头像发起挑战，对方输入 &e/le duel accept &7接受 | &7Click a head to challenge; they accept with &e/le duel accept | DuelChallengeGUI |
| duel.challenge-empty | &7现在没有其他在线玩家可以挑战喵~ | &7No other players online to challenge right now~ | DuelChallengeGUI |
| duel.challenge-more | &7在线玩家过多，仅显示前 {count} 位 | &7Too many players online; showing first {count} | DuelChallengeGUI（>42 兜底） |
| module.disabled | {prefix}&c该功能已被服务器管理员关闭喵~ | {prefix}&cThis feature has been disabled by the server admin~ | 全部 9 个门 |
| ai-chat.module-disabled | {prefix}&cAI 对话已被服务器管理员关闭，本次对话已结束 | {prefix}&cAI chat has been disabled by the server admin; your conversation has ended | ChatScenarioListener（进行中对话） |
| word.mode-disabled | {prefix}&c该学习模式已被服务器管理员关闭喵~ | {prefix}&cThat learning mode has been disabled by the server admin~ | LearnCommand / MiscGUIListener |
| word.no-modes-enabled | {prefix}&c所有学习模式都被关闭了喵~ 请联系管理员 | {prefix}&cAll learning modes are disabled. Please contact the admin. | 默认模式解析 |
| scenario.manage-button | &e&l管理我的场景 | &e&lManage My Scenarios | ScenarioSelectGUI slot 24 |
| scenario.manage-button-lore | &7查看/删除自己导入的个人场景 | &7View/delete your imported personal scenarios | 同上 |
| scenario.manage-title | &6&l我的个人场景 | &6&lMy Personal Scenarios | PersonalScenarioGUI |
| scenario.manage-count | &e个人场景: &f{current}&7/&f{max} | &ePersonal scenarios: &f{current}&7/&f{max} | 同上 slot 4 |
| scenario.manage-confirm | &c再次点击确认删除！ | &cClick again to confirm deletion! | 条目确认态 |
| scenario.manage-deleted | {prefix}&a场景 '{name}' 已删除喵~ | {prefix}&aScenario '{name}' deleted! | 删除成功 |
| scenario.manage-not-found | {prefix}&c未找到该场景喵~（可能已删除） | {prefix}&cScenario not found (maybe already deleted). | notFound |
| scenario.manage-save-failed | {prefix}&c删除保存失败喵~ 请检查日志 | {prefix}&cFailed to save deletion; check logs. | saveFailed |
| scenario.manage-empty | &7还没有个人场景喵~ | &7No personal scenarios yet~ | 空态物 |
| scenario.manage-empty-lore | &7在 AI 对话中用 &e/le save_scenario &7导出分享码，再用 &e/le load_scenario &7导入 | &7Export a share code with &e/le save_scenario &7then import via &e/le load_scenario | 空态 lore |
| scenario.page-prev | &e← 上一页 | &e← Previous | 场景选择页分页（MC-6b，若做） |
| scenario.page-next | &e下一页 → | &eNext → | 同上 |
| scenario.title-page | &b&l场景选择 &7(第 {page}/{total} 页) | &b&lScenario Select &7(page {page}/{total}) | 同上（替代 dialogue.scenario-title 的调用点——原键保留给无分页标题回退或直接改写，二选一由实施定） |

**改写键（3 个）**：
- `progress.economy-disabled` / `daily-challenge.economy-disabled` / `checkin.economy-disabled`：文案泛化 —— "本服未启用金币经济" → "本服未启用金币奖励"（覆盖 `rewards.enabled=false` 与 `money.enabled=false` 两种成因；语义不变只改措辞，或不改文案只留注释——**建议改**，因为新增的 rewards 伞门会让该提示在"经济开着但奖励关着"时出现，旧措辞失准）。
- `help.duel`：补一句"主菜单【单词对决】可直接图形化发起"？（可选 polish，不做也行）。
- `dialogue.more-scenarios-lore`：若实施 MC-6b 分页，删除该键（尾槽提示被真分页替代）；否则保留。

**删除键**（死键终局，zh/en 对称）：见 2.4 表"i18n 死键"列。

### 2.4 config.yml 最终形态建议（保留 / 删除 / 注释改写）

| config 路径 | 处置 | 说明 |
|------------|------|------|
| 各模块顶级 `enabled` × 7（word-system/ai-chat/progress-system/daily-challenges/leaderboard/achievements/rewards） | **保留 + 接线** | 注释逐个写明三层语义（入口隐藏/事件静默/数据保留），见 1.(b) 表 |
| `duel.enabled` / `checkin.enabled` | **新增** | 新顶层小节，出厂 true |
| `word-system.modes.*` | **保留 + 接线 + 修正** | `fill-blank` 键名改 `fill_blank`（双读兼容）；删 `max-attempts` ×2；删 `choice.time-limit` 的"0 不限制"注释改"0=跟随全局"、默认值 30→0；段注释删"预留配置暂不生效"整句 |
| `daily-challenges.challenges-per-day` | **保留 + 接线** | 注释改"分配数量（1-4，受可用类型数限制）" |
| `daily-challenges.rewards.*` | **保留 + 接线** | 注释改"每类挑战奖励金币（缺键/非法回落内置默认 30/50/25/40）" |
| `gui.main-menu-title` | **删除** | F0-09 双真相：标题唯一真相在 messages（`MainMenu.java:36` 与 `MainMenuListener:94` 两处读点都读 i18n）。config 侧键从未被读；删除并在原位置留一行注释"主菜单标题见 messages/*.yml 的 gui.main-menu-title" |
| `gui.menu-size` | 保留（P2 已收紧 [27,54]），注释追加"新增/移动按钮时需同步检查布局占用" | 27 仍是最小合法值（按钮 10-18 + 新增 12/16） |
| `gui.custom-textures` | **保留不动** | 域 7（资源包）已占此键决策，本域不碰 |
| `gui-layout.main-menu.*` | **保留 + 2 新增叶子**（`duel`/`checkin` 各 slot+material） | 与既有 7 叶子同格式 |
| `gui-layout.main-menu.scenario-select.back-button` | **删除** | P2-25 死键（错位嵌套） |
| `gui-layout.profile.*` | **删叶子保段注释** | PF-MC-03（15 个无读取者叶子删光，段注释"布局固定"）；`achievement-wall.*` 保留 |
| `general.debug` | **删除** | 无读取者；不用"总伞 debug"避免与模块 debug 脱钩 |
| `general.check-updates` | 保留（P2 已接线） | — |
| `database.mysql.pool-size` | **删除** | 单连接无池；注释"当前实现为单连接，无池" |
| `difficulty-settings.*` | **整段删除** | 全死；注释指向 words/ 自动发现 + `word-bank.difficulty-*` i18n |
| `progress-system.leaderboard.display-count` | 保留（已活） | R-55k 已对齐（默认 10） |
| `progress-system.leaderboard.update-interval` | **保留 + 接线** | 默认改 300（现状硬编码 5 分钟），钳位 [30, 3600] |
| `progress-system.rewards.types.{item,command,exp}.enabled` | **删除** | 未实现类型 |
| `progress-system.achievements.config-file` | **删除** | F0-10；配套见 3.(c)（死链清理） |
| `achievement-system.unlock-sound` | **保留 + 注释改写** | 半接线（PF-MC-04）：注释写明"留空=静音；非空=播 gui.sounds.complete" |
| `daily-reminder.*` | 保留（P2 已全接线） | — |
| `proxy.cross-server-announcements` / `cross-server-messages` | **保留 + 接线** | 见 1.(f)-7 |
| `statistics-server.*` | 保留（enable 时排程语义，注释"reload 不重建 HTTP 服务，需重启"） | 注释诚实化 |
| `ai-chat.daily-round-limit` | 保留 + **语义修正**（0/负=不限） | 注释重写 + CHANGELOG Changed |
| `money.daily-cap` / `money.enabled` 系列 | 保留（已活）；`money.enabled` 语义 = isRewardsEnabled() && 自身 | 注释注明与 `rewards.enabled` 的叠加关系 |

**i18n 死键清扫表**（zh/en 对称删除；删除前必须逐键全路径 grep 复核——以下判定基于 2026-10-13 快照，行号会漂）：

| 键 | 判定依据 |
|----|---------|
| `word-learning-gui.answer-broadcast` | F1-06 起零引用（C2 已删） |
| `daily-challenge.claim-button` / `claim-button-lore` | 零引用（庆祝页用 claimed-button） |
| `leaderboard.title-weekly/monthly/alltime` | 旧分榜标题，LeaderboardGUI 未用（SocialListener 注释已自认"保留待统一清理"） |
| `leaderboard.header` / `entry` / `footer` | 聊天版榜单残留，零引用 |
| `word.correct` / `word.incorrect` / `word.session-end` / `word.mastery-update` / `word.daily-limit-reached` | 零运行时引用（R-86o 已从审计名单移除 word.correct/incorrect）。注意：`MessageUtil` javadoc 里以 `word.correct` 作举例——那是注释示例非引用，删键不必动 javadoc（顺手换成活键示例更好，可选） |
| `checkin.streak-info` | 零引用 |
| `general.data-saved` | 零引用 |
| `admin.reset-pending` | 零引用（"功能开发中"更属陈旧） |
| `error.scenario-save-failed` | P2-11 死键候选 |
| `dialogue-gui.*`（整段：title/instructions） | 旧 DialogueGUI 已并入 Scenario Select；零 Java 引用 |
| `progress-gui.*`（整段） | 唯一引用者是 `gui/ProgressGUI.java` 自身（本域删除该死类，键随类删——**同批必须成对**，否则删类后键变新死键） |
| `word-learning-gui.answer-broadcast-correct` | **保留**（ChatAnswerListener 在用——与 answer-broadcast 仅一字之差，删除时注意精确匹配） |

---

## 3. 实现要点

### 3.1 触及文件清单（按子项）

**MC-1 主菜单改版（R-82）**：
- `gui/MainMenu.java`（+2 按钮渲染 + 模块门短路为玻璃）
- `listener/MainMenuListener.java`（+2 点击分支 + 模块门）
- `gui/DuelChallengeGUI.java`（**新建**，~150 行：在线玩家头像列表 + 点击发邀请 + 空态 + 返回）
- `listener/DuelChallengeListener.java`（**新建**，~80 行：标题白名单 + text 上半区 guard + quit 清理（无状态可省）+ 点击映射）
- `gui/GUIUtils.java`（isPluginGUI 白名单 +"单词对决 - 选择对手"/"Word Duel - Pick"；SoundManager 开屏音依赖）
- `resources/plugin.yml`（无新命令，无需改）+ `resources/config.yml`（gui-layout 2 叶子）+ zh/en.yml（4 + 4 键）

**MC-2 模块开关接线（R-55i/F0-11）**：
- `config/ConfigManager.java`（+9 门方法 + REQUIRED_CONFIG_KEYS 视情况增补）
- `core/word/WordLearningManager.java`（startQuiz/startReviewQuiz 门）
- `core/word/ReviewScheduler.java`（checkAndNotifyReviews 门——或其调用点 LetsLearnEnglish:317-321 任务体内门）
- `core/ai/AIChatManager.java`（startConversation/triggerAiOpening/dispatchChat 门 + dailyRoundLimit 语义修正）
- `listener/ChatScenarioListener.java`（进行中对话 auto-exit + 门）
- `core/DailyChallengeManager.java`（checkAndAssign/updateProgress/completeChallenge/onWordCorrect/ensureWordChallengesAssigned/isChallengeAvailable 门）
- `core/achievement/AchievementManager.java`（tryUnlock 门）
- `core/progress/RewardEngine.java`（isMoneyEnabled 改道 isRewardsEnabled）
- `core/duel/DuelManager.java`（sendDuelRequest/acceptDuel 门）
- `command/LearnCommand.java`（word/review/wrong/dialogue/challenge/leaderboard/duel/checkin 分支门 + checkin 命令门）
- `listener/MiscGUIListener.java`（handleDailyChallenge 门）
- `listener/SocialListener.java`（成就墙分支门 + 广场三榜模式门）
- `gui/EnglishSquareGUI.java`（三榜模式按钮隐藏——渲染侧）
- `gui/ProfileGUI.java`（成就墙按钮隐藏）
- `gui/DailyChallengeGUI.java`（渲染前门：关闭时不可达，防御性）
- `LetsLearnEnglish.java`（榜单刷新任务体内门 + 任务周期接线）
- `core/ProxyMessenger.java`（2 键接线）

**MC-3 modes.* 接线（R-55f/H6）**：
- `core/word/WordModes.java`（**新建**，~120 行：9 个静态读取方法 + 钳位 + 双读兼容 + WARNING 防刷屏）
- `core/word/AnswerChecker.java`（caseSensitive 参数化的重载；buildChoiceOptions 重载；旧签名委托）
- `core/word/WordLearningManager.java`（QuizState +3 final 字段；startQuiz 快照；submitAnswer/handleTimeout 传参）
- `command/LearnCommand.java`（VALID_WORD_MODES 改为动态（从 WordModes.enabledModes 派生）；mode 校验门 + 默认模式 firstEnabledMode）
- `listener/MiscGUIListener.java`（DEFAULT_WORD_MODE 常量删除改 WordModes）
- `listener/ChatAnswerListener.java`（isInvalidChoiceInput/check 传入 caseSensitive；或经 wlManager 取快照）
- `gui/WordLearningGUI.java`（fill_blank 提示行按 show-first-letter 短路；choice 选项数已由快照覆盖）
- `listener/WordLearningListener.java`（答题提示行 fill-blank-hint 短路）
- `resources/config.yml`（modes 段改造）

**MC-4 个人场景管理（含 PersonalScenarioStore 抽取）**：
- `core/ai/PersonalScenarioStore.java`（**新建**：从 AIChatManager 抽 load/import/delete/list；~200 行）
- `core/ai/AIChatManager.java`（个人场景段落改委托；importPersonalScenario 保留为薄壳或直接迁走——**决定**：迁走方法体，AIChatManager 保留 `getPersonalScenarios/getScenarioConfigs(player)/getSortedScenarioConfigs` 等读取门面委托 Store；ImportResult record 迁 Store（跨文件引用同步改））
- `gui/PersonalScenarioGUI.java`（**新建**，~180 行）
- `listener/PersonalScenarioListener.java`（**新建**，~120 行：行内二确状态 Map + quit 清理 + 点击映射）
- `gui/ScenarioSelectGUI.java`（slot 24 按钮 + 公开常量）
- `listener/ScenarioSelectListener.java`（slot 24 分支）
- `gui/GUIUtils.java`（白名单 +"我的个人场景"/"My Personal Scenarios"）
- （MC-6b 分页若做：+ScenarioSelectGUI/Listener 分页逻辑 + 常量）

**MC-5 挑战经济参数接线（R-55h）**：
- `core/DailyChallengeManager.java`（challenges-per-day 泛化分配（去重抽） + getChallengeReward + 全部读取点）
- `listener/MiscGUIListener.java`（idx 映射改查位 + getChallengeReward）
- `gui/DailyChallengeGUI.java`（CHALLENGE_SLOTS 常量={11,13,15,17} + 渲染前 N）
- `config/ConfigManager.java`（审计名单 +4 键）
- `resources/config.yml`（2 段注释）

**MC-6 语义终案与二期收敛（F1-19/P2-13/P2-09/P2-25）**：
- `core/ai/AIChatManager.java`（`>0` 门 ×2）
- `listener/WordLearningListener.java`、`core/word/WordLearningManager.java`、`listener/WrongBookListener.java`、`core/DailyChallengeManager.java`、`core/duel/DuelManager.java`（getGuiMsg 收敛，~25 处）
- `core/manager/DataExporter.java`（ExportResult record）+ `command/AdminCommand.java`（消费新 record）
- `resources/config.yml`（死键删除/注释改写）+ `resources/messages/zh.yml`/`en.yml`（增删键 + config-version 12）

**MC-7 死配置/死键终局**：
- `resources/config.yml`（上表全量）、`config/ConfigManager.java`（audit 名单同步）
- `gui/ProgressGUI.java`（**删除**）
- `resources/messages/*.yml`（死键清扫）

### 3.2 开关接线的统一模式建议（集中式门 vs 各系统自读）

**推荐：集中式读取方法（ConfigManager 9 方法）+ 分散 gating 落点。**
- 读取集中：所有键的默认值/AND 组合（伞语义）/命名在**一个文件**收口，服主文档与代码注释同步一处；消灭"同一个键 5 个调用点各自 `getBoolean(..., true)` 默认值漂移"（money.enabled 现状已有 5 个直读点，本轮顺带收口）。
- gating 分散：**在"最省的一层"拦**——优先级：命令入口/事件入口 > 管理器公开方法顶 > 内部链路点。理由：Bukkit 插件没有通用 AOP；事件入口拦一次覆盖面最大（如 MainMenuListener 一处门覆盖主菜单全部 9 按钮的"关"态）。不引入 `ModuleGate` 事件拦截框架（性能税 + 调试黑盒 + 与既有 listener 分区判定的模式冲突）。
- **禁止**在渲染热路径（GUI open）做重检查——门全是 `mainConfig.getBoolean` 内存读，无 IO，可接受。
- **命令拒绝的统一回显**：LearnCommand 抽 `private boolean requireEnabled(Player, boolean enabled)`（false 时发 `module.disabled` 并 return），8 个分支各一行。

### 3.3 modes 接线的 AnswerChecker 策略适配层

见 1.(c)：`WordModes`（配置真相源，静态、无 Bukkit 依赖除 ConfigManager 读值）+ `AnswerChecker` 重载签名（旧签名委托保持二进制兼容）+ `QuizState` 快照。单测：WordModes 纯函数测试（钳位/双读/首模式）+ AnswerChecker caseSensitive 参数化用例。

### 3.4 个人场景删除的文件操作与内存刷新路径

```
PersonalScenarioGUI（确认态） ──点击──▶ PersonalScenarioListener
   └─ plugin.getAIChatManager().deletePersonalScenario(player, key)   // 门面（委托 Store）
        └─ PersonalScenarioStore.deletePersonalScenario(ownerId, key):
             1. key 校验（personal: 前缀 + 内存副本 containsKey）
             2. file = player_scenarios/<ownerId>.yml；不存在 → notFound
             3. config = loadConfiguration(file)（若 >512KB → saveFailed 提示，不解析）
             4. config.set("scenarios." + key, null) → config.save(file)
                  ├ save IOException → DeleteResult(false, false, true)（内存不动）
                  └ 成功 ↓
             5. 内存：personalScenarios.get(ownerId).remove(key)（同步原地）
             6. return DeleteResult(true, false, false)
   └─ 成功：sendMessage(scenario.manage-deleted) + playSound(menu-click) + new PersonalScenarioGUI(...).open(player)
```

**边界**：玩家正在进行的该场景对话不打断（system prompt 已冻结于 playerSystemPrompts，删文件不影响）；`.bak` 文件不产生（删除只在有效 YAML 上 set-null + save）；重启后 reload 从文件重建内存（已删除的 key 不再出现）。

### 3.5 死链清理（F0-10 配套，MC-7）
- `ConfigManager.loadAllConfigs()` 里 `achievementConfig = loadConfig("achievements.yml")` 整条删除（`achievements.yml` 资源从 JAR 移除：`resources/achievements.yml` **删除文件**——确认零读取者：唯一读点就是这条 loadConfig，其 getter `getAchievementConfig()` 也零调用者（AchievementManager 自持懒加载 `achievements-config.yml`、AchievementWallGUI 同）。资源文件是已被标注"deprecated 参考用"的旧版，删除属死链终局）。
- `ConfigManager.getAchievementConfig()` 方法一并删除。

### 3.6 死类删除：`gui/ProgressGUI.java` 整文件删除（零实例化点，P2-23/P3-04 闭环）；配套 `progress-gui.*` i18n 段删除。

---

## 4. 依赖与风险

### 4.1 与其它 P3 域的交叉（接口约定清单）

| 对方域 | 交叉点 | 约定 |
|--------|--------|------|
| 域 1 新手引导 | 主菜单新按钮/引导书入口 | **slot 19 预留**给域 1；域 1 若加按钮须用本域三件套规范（gui-layout 键 + i18n 双键 + 模块门）；引导书若作独立 GUI 须加 GUIUtils 白名单 |
| 域 4 社交 | 广场入口 | plan-social 已占 `english-square` slot 6（动态模式）+ 默认落点改造；**本域不碰广场 slot 0-8 的既有布局**；本域的"三榜模式按钮在 leaderboard.enabled=false 时隐藏"与域 4 的 slot 6 新增**互不冲突**（不同 slot、不同配置键）。两域都碰 `SocialListener.java`（本域成就墙门/榜单模式门；域 4 动态流分支）——**同文件串行或合流时编排者对齐**；`messages config-version` 共同目标 12（后落者保持 12） |
| 域 2 AI 反馈 | `ChatScenarioListener`/`LearnCommand`/`ChatHistoryGUI` | 域 2 已声明与本域联动面串行/先后批；本域的 `ai-chat.enabled` 门与域 2 的 `feedback.enabled`（独立门）语义互不消耗；域 2 声明"不引用 ai-chat.enabled"——本域落地后 ai-chat 门语义定案（入口隐藏+进行中退出），域 2 的对话结束摘要路径在 `ai-chat.enabled=false` 时根本不会到达（对话开不了），无冲突 |
| 域 2 依赖项 | `PersonalScenarioStore` 抽取 | plan-ai-feedback §PF-FB-05 明示"建议在域 8 或独立清理 story 排期"——**本域 MC-4 即该抽取**；抽取后 AIChatManager 的 ImportResult/懒加载签名保持（域 2 不直接触碰个人场景，仅受益） |
| 域 5 赛季 | `LeaderboardGUI` tab / `EnglishSquareGUI` | plan-season 占 `leaderboard` slot 3 tab；本域不动 LeaderboardGUI 槽位体系（只加 EnglishSquareGUI 的三榜隐藏逻辑+ConfigManager 门）——若同批，LeaderboardGUI 由域 5 独占 |
| 域 6 报告 | `DataExporter` / `AdminCommand` / `AdminCommandHandler` | plan-reports 会碰 `AdminCommand`（report 子命令）与 `AdminCommandHandler`（resolvePlayerUUID 提权）。本域 MC-6 的 export 协议化（ExportResult record）**冻结该签名**：域 6 若先落 report 再加 ExportResult，以本文为准；P3-07 的"CSV 落 exports/ 子目录"归域 6 口径，本域不改目录 |
| 域 7 音频 | `SoundManager` / modes 展示 | 域 7 占 `gui.sounds` 新键与 `feedback` 节、拥有 ActionBar 化；本域不新增音效键（新按钮复用 menu-click/menu-open 已有键）。`gui.custom-textures` 归域 7（资源包开关），本域不删不动。`modes.fill_blank.show-first-letter=false` 时域 7 的 ActionBar 提示不涉及首字母，无交叉 |
| 域 3 成就 | `AchievementManager` | 域 3 重构成就在 AchievementManager 加 hook（域 4 已备注同文件冲突）。本域对 AchievementManager 的改动仅 tryUnlock 顶部门（~3 行）——**若域 3 同批，本域让行**：门由域 3 在其重构落地时保留（写入约定：tryUnlock 顶部必须保留 `isAchievementsEnabled()` 门） |
| 全部域 | `messages config-version` 12 / `CHANGELOG.md` | 只由编排者 fan-in；本域清单在 2.3 |

### 4.2 开关关闭的边界语义（逐开关"什么还活着"）

见 1.(b) 表"什么还活着"列。**跨开关联动复核**（关键组合矩阵，验收用）：

| 组合 | 预期行为 |
|------|---------|
| word-system=false，AI 对话=开着 | AI 可开（抽词不受 word 门控——AI 走 ai_scenarios.yml 场景，不抽词库；**但**：word-system=false 时 `ensureWordChallengesAssigned` 不再分配；对话照常） |
| word-system=false，决斗=开着 | 决斗可开（DuelManager.getRandomWord 从词库内存抽词，词库加载不受 word-system.enabled 门控——词库加载是基础设施）。**记录**：关 word-system 而留 duel 的服仍消耗词库读，属预期 |
| leaderboard=false，PAPI 在查 %lle_rank% | 返回 `placeholder.rank-unranked` 文案（不查内存榜不查库）；`%lle_score%` 等继续返回（历史只读） |
| leaderboard=false，`updateScore` | 内存榜继续累加（"热备"），重开即时恢复 |
| achievements=false，已解锁者 | 不能解锁新的；主页成就墙按钮隐藏；PAPI 历史数字保留 |
| rewards=false，签到 | 签到照常记 streak；不发币；每天一次泛化提示 |
| daily-challenges=false | 无新挑战行；昨日 pending 补发链不再触达（hasPendingWordRewardForDate 只在 rollover 调——rollover 跳过段落）；已在当日的行保留（重开后继续） |
| ai-chat=false，发放中的 AI_CHAT_5 | 新分配不含（isChallengeAvailable 查两个门）；存量走 R-21 降级（title 提示自动放弃）；玩家侧无卡死 |
| duel=false，进行中对局 | 自然收尾；quit 命令仍可用（**实施要点**：`/le duel quit` 与 `/le duel gui` 在门内会拒绝——duel 分支的 `requireEnabled` 改为 `duel.enabled || duelManager.isInDuel(player)`，保证关开关后进行中对局仍可退出/重开界面；"发起/接受"仍恒被门拦截。写入 impl guide） |
| 全 9 开关 = false 的极端服 | 主菜单全玻璃 + 语言切换仅存；/le 各分支回 module.disabled；PAPI 历史只读；无任何报错 |

### 4.3 modes 接线的回归面（等价性）

默认出厂配置下逐位等价（见 1.(c)）；**不等价点仅 3 处**，全部为"管理员显式改键"才触发：case-sensitive=true、option-count≠4、time-limit≠0、show-first-letter=false。回归验证 = 5.(c) 的对照用例。**风险点**：`time-limit` 从 30→0 的默认值改写——**存量 config.yml 不会被覆写**（主配置无迁移），升级服读旧文件 `time-limit: 30` → 接线后**立即生效为"choice 使用 30 秒"**，若其 answer-timeout 已改（如 45）：choice 会变 30 → **行为变化**！缓解：CHANGELOG 的"升级注意"段明示；且旧注释写"0 不限制"的语义下管理员改过它的概率低（死键无人动）。**实施时可选**：读取侧加"若值恰为默认值 30 且 profiles 无自定义痕迹"不做——过度工程，不做，靠 CHANGELOG。

### 4.4 其它风险

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | 模块门落点遗漏 → 半接线（关不干净） | 中 | 本域产出门清单（1.(b) 表逐格）；验收用"关 X 后 10 项检查清单"逐门过 |
| R2 | DuelChallengeGUI 与竞态（点开列表后对方下线/进对局） | 低 | 点击时 sendDuelRequest 内部全部守卫（player-offline/target-busy）已存在，反馈准确 |
| R3 | 个人场景删除与域 2 的会话摘要写盘并发 | 低 | 删除主线程同步；域 2 写盘走 AI 回调（主线程调度），无同 key 并发写场景 |
| R4 | challenges-per-day=4 后旧 GUI 下标错位 | 中 | F1-12 配套（CHALLENGE_SLOTS 常量 + 查位映射）同批必做，验收含 N=4 用例 |
| R5 | 死键删除误删活键 | 中 | 删除前逐键全路径 grep（本文所有判定基于当前快照，行号会漂）；`MESSAGE_CONFIG_VERSION` 升 12 触发旧文件更新——**升级服的自定义 messages 会被覆盖**（既有机制，v11 时同样发生，注明） |
| R6 | `modes.choice.time-limit` 存量值立即生效 | 中 | CHANGELOG"升级注意"；或实施时对"值==30 且 answer-timeout 未改"不可区分——接受并文档化 |
| R7 | `AchievementManager`/`SocialListener` 与域 3/域 4 同文件 | 中 | 4.1 串行约定（本域改动面小，让行策略） |
| R8 | R-82 的"签到按钮 lore 静态"被玩家预期为动态 | 低 | 设计决策记录（高频路径拒绝 DB 读）；未来若要动态 lore 单开 Story |
| R9 | 主菜单 12/16 与管理员自定义 gui-layout 冲突（服主已把某键指到 12/16） | 低 | 概率极低（12/16 从未有键）；配置读取以服主值为准，服务端启动时若检测"两键同槽"WARNING（**可选**：MainMenu.open 里查重——低成本，做） |

---

## 5. 验收标准

### 5.(a) 主菜单与入口（MC-1）
1. `/le menu` → 9 按钮齐全（10/11/12/13/14/15/16/17/18），标题/图标/文案符合 2.1；hover 无 `[?key]`。
2. 点【单词对决】→ DuelChallengeGUI 打开（有音）→ 点头像 → 对方收 `duel.request-received`；对方 `/le duel accept` → 对决开始。**纯 GUI 路径完成一次对决全流程**（R-82 核心用例）。
3. 对决中再点【单词对决】→ 重开当前对决界面（等价 /le duel gui）。
4. 点【每日签到】→ 等同于 `/le checkin`（首签 success + correct 音；重签 already）。
5. 头像列表含自己则排除；仅自己在线 → 空态物。
6. Escape 关界面后 /le duel gui 可回；DuelChallengeGUI 的展示物不可被拖入背包（drag guard）。
7. gui-layout 把 `main-menu.duel.slot` 改为 16、`checkin.slot` 改为 12 → 渲染与点击依然对齐（双侧读配置）。
8. GUIUtils 白名单命中：新 GUI 开屏播 menu-open（SoundManager 单源）。
9. **标题避碰**：开 DuelChallengeGUI 点 slot 22 不触发退出对决；对决中开 DuelGUI 点任意格不误触 DuelChallenge 逻辑（`DuelGUI.isDuelGUITitle` 前缀比对对两 GUI 标题互不命中——评审项）。

### 5.(b) 模块开关（MC-2）——"关 X 开关后 OM/lobby 10 项检查清单"式矩阵（X=duel 为样例，其余开关按 1.(b) 表逐格同法）
关 `duel.enabled`（reload 后）：
1. 主菜单 slot 12 是灰玻璃、点击无动作；
2. `/le duel <名字>` → module.disabled；
3. `/le duel accept` → module.disabled；
4. 决斗中 quit/gui 仍可用（duel.enabled=false 下）；关开关后进行中的对局可退出/重开界面，随后自然收尾；
5. `/lea duel cancel <人>`（GM）仍可用；
6. 面板全员：服务器 TPS 正常、控制台无异常栈；
7. `/lea reload` 再回 true → 按钮即时恢复（不重启）；
8. 数据面：无决斗表（零持久化）——跳过；
9. PAPI/其它功能抽查不受影响（学习/对话各开一局）；
10. config 注释与行为一致。

逐开关 10 项清单要点（表格形式，供 tester 逐门执行）：
| 开关 | 清单要点（前 8 项共性：入口隐藏/命令拒绝/事件静默/数据保留/reload 往复/无异常/相邻功能抽查/注释一致） | 特有项 |
|------|-----------------------------|--------|
| word-system | +复习提醒停（任务体内门，reload 生效不重启）+错题本练习不可开+进行中答题不被打断+AI/决斗可开 | 第 9：`/le word` 拒绝而 `/le dialogue` 正常；第 10：错题本按钮隐藏 |
| ai-chat | +进行中对话下一条消息 → module-disabled 并退出（不吞消息）+history/save/load/管理页可用+新玩家挑战分配不含 AI_CHAT_5 | 第 9：TTS 发音仍可用；第 10：`/le challenge` 行为挑战列表无 AI 项（新分配） |
| progress-system（伞） | +榜单/成就/奖励三子门同时生效 | 第 9：个人主页仍可看 |
| daily-challenges | +无新行产生+`/le challenge` 拒绝+领取分支拒绝+rollover 跳段（次日 0 点观察或即时手工调） | 第 9：学习/签到照常 |
| leaderboard | +`/le leaderboard` 拒绝+广场仅"广场"模式可切（周/月/总按钮不在）+PAPI rank 未上榜 | 第 9：`updateScore` 内部热备（重开即时恢复排名） |
| achievements | +新解锁无播报/无音效/无跨服广播+主页成就墙按钮隐藏+PAPI 历史保留 | 第 9：`/le profile` 其余卡片正常 |
| rewards | +答题/签到不发币+提示泛化+记录照常 | 第 9：`money.daily-cap` 设置仍被尊重（重开日 cap 从 reload 清后重新计） |
| checkin | +`/le checkin` 拒绝+按钮隐藏+streak 自然断 | 第 9：`checkin_data` 行保留（重开续接） |

### 5.(c) modes 接线（MC-3）——默认等价性 + 逐键生效
1. **默认配置回归**：spelling/choice/fill_blank 三模式开工 → 判定与接线前一致（对照用例：拼写大小写混输判对、choice 输入 A-D 与全词、fill_blank 挖空+首字母提示行存在；超时 30s 链一致；错误结算链一致）。
2. `modes.choice.enabled=false` → `/le word beginner choice` → word.mode-disabled；tab 补全无 choice；默认模式解析跳过（无参 `/le word beginner` 开 spelling）。
3. 全三模式 false → 无参 `/le word` 拒绝 word.no-modes-enabled；`/le word beginner spelling` 分别拒绝。
4. `spelling.case-sensitive=true` → 输 Apple 判错（提示正确答案）；choice 的 A/B/C/D 恒不敏感（输 a 仍选 A）；改回 false → reload 即时生效（**下一局**会话生效；进行中会话保持快照值——验证快照语义）。
5. `choice.option-count: 2` → 选项 2 个（GUI 与聊天提示与判分一致，A/B）；`option-count: 99` → 钳 4 + WARNING 一次；`1` → 钳 2。
6. `choice.time-limit: 10` → choice 局倒计时 10s；改回 0 → 跟随 `word-system.answer-timeout`；spelling 局不受影响恒 30（或全局值）。
7. `fill_blank.show-first-letter=false` → 挖空卡无提示行、聊天无 `fill-blank-hint` 行；判定不变（输全词仍对）。
8. 旧名兼容：config 写 `modes.fill-blank.enabled=false`（连字符版）→ 同样拒绝（双读）；新键优先。
9. fill_blank 单测：`AnswerChecker.check` 新签名 caseSensitive=true 的大小写用例全绿（+WordModes 纯函数单测）。

### 5.(d) 个人场景管理（MC-4）
1. 有 import 权限玩家：场景选择页 slot 24 可见；无权限玩家：不可见、点该槽无反应。
2. 管理页列出 = 我的 3 个导入场景（中文名 slug 哈希版、同名覆盖版各验证一个）；计数 3/30。
3. 删除二确：首点变红；点其它条目/空白 → 复位；再点变红条目 → 删除 → 聊天提示 + 重开页少一条；`player_scenarios/<uuid>.yml` 中该段消失（外部查看）。
4. 删除空格/无效 key/他人 key 注入 → notFound 或拒绝，无异常、无越权删。
5. 30 条满 → 管理页仍可删（删除不占额度）；删后 import 可再进。
6. 返回键：管理页 → 场景选择页 → 返回键 → 主菜单；返回栈无残留（F6 系列语义）。
7. 重启服务器 → 被删场景不复活；未被删的正常可用（`/le dialogue <key>` 或选择页）。
8. quit 清理：打开管理页 → 退出 → 重进 → 确认态无残留（Map 清理验证）。
9. PersonalScenarioStore 抽取回归：import（新增/覆盖/上限/写盘失败四分支）与限-reached/{max} 展示与抽取前逐项一致。
10. （若 MC-6b 分页）8+ 场景（全局 6 + 个人 3）→ 分页可见全部、点 A 开 A；尾槽提示退役。

### 5.(e) 挑战经济参数（MC-5）
1. 默认（3）：今日挑战 3 条、槽位 11/13/15、奖励 30/50/25/40 全链路（展示/领取/到账）与现状一致。
2. `challenges-per-day: 1` → 新玩家（重置后）当日仅 1 条（槽 11）；`4` → 4 条（槽 11/13/15/17）；`99` → 钳 4；`0` → 钳 1。
3. rewards.answer_10: 99 → GUI reward-hint 显示 99、领取到账 99、日志无异常；非法（abc）→ 回落 30 + 启动审计 WARNING。
4. rewards 项设为 -5 → 回落默认 30（不产生 0 金币死领取）。
5. 领取 idx 映射：N=4 时点第 4 槽（17）领第 4 项（不跨项）。`MiscGUIListener` 旧 `(slot-11)/2` 代码已删（评审项）。
6. 跨天/全完成/庆祝链在 N=1（含 AI 不可用时实际 3→更少）下无异常。

### 5.(f) 语义终案与二期（MC-6）
1. `daily-round-limit: 0` → 玩家可无限对话（原拒绝）；`-1` 同；`30` 恢复限次；`1` → 第 2 轮拒绝（边界：第 1 轮成功）。
2. 裸 getString 25 处：删任一对应 key 拍测试正常（不炸）；`duel.accept-hint` 缺键实测（改 zh/en 该键为空 → 邀请消息不炸）。
3. `/lea export` 成功 → export-complete（file 路径）；把导出目录设为只读（或塞大文件占位）造失败 → admin.export-failed；无 "Export failed" 字符串出现在代码库（grep 验收）。
4. `gui-layout.profile.*` 删除后 ~/.minecraft 无关——服务器端：reload 无 WARNING、主页/设置页渲染不变。

### 5.(g) 死配置终局（MC-7）
1. `grep -rn "word-system\.modes\|daily-challenges\.challenges-per-day\|difficulty-settings\|pool-size\|general\.debug\|custom-textures" src/main/java` → 命中集合 = 白名单（modes 相关新代码/域 7 键）。
2. 引擎级检查：删掉 config.yml 中所有被删键后启动 + reload → 零 WARNING（除审计名单期望的）、功能全量冒烟通过。
3. `mvn clean verify` 26 测试 + 新增单测全绿；JaCoCo 门禁过。
4. messages 双文件键级 diff = 空（对称一致）；`MESSAGE_CONFIG_VERSION=12`；死键清扫表中每键全路径零引用复核记录归档。
5. `ProgressGUI.java` 不存在于 JAR（`jar tf` 验收）；`gui/` 无死类。
6. 全量启动日志：`[Config Audit] All expected config and i18n keys present.`

### 5.(h) 新 GUI 面通用项
- `GUIUtils.isPluginGUI` 白名单覆盖新 2 GUI 标题（开屏音实测）；
- 两新 GUI 均实现点击上半区 guard + drag guard（8 listener 模式对齐）；
- 两新 GUI 展示物不可拖出、玩家背包可整理（R-01/R-22 系列语义）；
- 中文/英文语言各自渲染正确（切语言后重开页面核对）。

---

## 6. 规划期发现

> 纪律：仅记录，不自行修复。编号 PF-MC-xx。**注**：其中 PF-MC-01 与本域任务直接相关（须在施工时处理）；其余为途中所见。

| # | 位置 | 现象 | 严重 | 建议 |
|---|------|------|------|------|
| PF-MC-01 | `listener/MiscGUIListener.java:187-208`（`claimBehavioralChallenge`） | 行为挑战领取点**不过 `money.enabled` / rewards 门**：`depositChallengeReward` 只查 Vault 存在与 cap，`money.enabled=false` 时挑战奖励仍照发（RewardEngine.depositCappedReward 注释自认"money.enabled 开关由各调用方自行把关"，而本调用方把关缺失）——与 R-44/挑战单词奖励路径（DailyChallengeManager:683 有门）**不一致**，经济开关存在旁路 | 中 | **本域 MC-2 一并修**（本域任务口径内的经济开关收口）：`claimBehavioralChallenge` 顶补 `isRewardsEnabled() && money.enabled` 预检（false → 走 claim-failed 或新增"经济未启用"提示）；验证 5.(b) rewards 清单第 9 项 |
| PF-MC-02 | `core/duel/DuelManager.java:875,905,948,1071` | 裸 `getString` 4 处（`duel.scoreboard-title` / `duel.scoreboard-target` / `duel.scoreboard-title-grace` / `getRawMsg("duel.accept-hint")`）；其中 `accept-hint` 为真实 NPE 路径（`translateAlternateColorCodes('&', null)`——P2-13 白名单只列了 13 文件 21 处，DuelManager 不在名单，属漏网） | 中 | 纳入 MC-6 二期收敛清单（1.(f)-2 已并入） |
| PF-MC-03 | `config.yml gui-layout.profile.*` 15 叶子 + `resources/config.yml:289` scenario-select 嵌套 | ProfileGUI 零 `getGuiSlot` 调用 → 该段 15 个键全死（社交域 PF-SOC-03 同发现）；叠加 P2-25 | 低 | 本域 MC-7 删除 + 注释（1.(f)-4 已定案） |
| PF-MC-04 | `core/achievement/AchievementManager.java:177-181` vs `config.yml achievement-system.unlock-sound` | 半接线：`unlock-sound` 值只做空判，实际固定播 `gui.sounds.complete`——管理员改 unlock-sound 名称无效（改空=静音有效） | 低 | 本域 MC-7 注释诚实化（1.(f)-6）；真接线待域 7 SoundManager 提供按名播放 |
| PF-MC-05 | `LetsLearnEnglish.java:317-321`（复习提醒任务） | 任务排程期判 `word-system.review.enabled` 一次——任务体内无门：reload 关闭 review 后提醒仍持续（与"reload 即时生效"预期相悖）；本域 MC-2 顺带修（任务体内加门） | 低 | 已并入 MC-2（word-system 门清单） |
| PF-MC-06 | `LetsLearnEnglish.java:335-337` vs config `leaderboard.update-interval` | 刷新任务硬编码 6000L（5 分钟），配置键 30（秒）被注释宣传——数值语义也漂移（秒 vs ticks） | 低 | 本域 MC-2 接线（默认 300s + 钳位），注释修正 |
| PF-MC-07 | `command/LearnCommand.java:54-55` vs `core/word/WordManagerImpl.java:24` | `VALID_WORD_MODES` 在 LearnCommand 与 WordManagerImpl 各一份（内容一致）；本域 MC-3 会引入第三处（WordModes）→ 建议 MC-3 一并把 WordManagerImpl 的 `VALID_MODES` 改为从 WordModes 派生或保留为纯结构校验（文档化分工） | 低 | MC-3 实施注意事项 |
| PF-MC-08 | `gui/WordLearningGUI.java:214` | `getComboLabel` 方法签名行与第一行代码挤在同一行（`{        if (combo <= 0) {`）——格式噪声（P2 遗留） | 低 | 顺手格式化（MC-3 触及该文件时） |
| PF-MC-09 | `core/DailyChallengeManager.java:31-36`（ChallengeType） | `target` 与 `reward` 均为 public final 字段：reward 接线后语义变"默认值容器"，但字段名/注释仍暗示权威值；两处**注释已与实现分叉**（R-55o 修过 target 注释） | 低 | MC-5 实施时 javadoc 改写（record 化可选，不入本域） |
| PF-MC-10 | `resources/config.yml:257-258` vs `MainMenu.java:43-48` | menu-size 注释"按钮布局固定 10-18"在本域加 12/16 后仍成立（10-18 含 12/16），但**注释文字需追加"含决斗/签到"**避免误导——小项 | 低 | MC-1 注释同步 |
| PF-MC-11 | `command/LearnCommand.java:223-242` + `listener/MiscGUIListener.java`（PF-ONB-03 即修后新增 `localizeDifficulty/localizeMode`） | 难度/模式标识符 → i18n label 的映射搬迁出了第二、三份实现（本次规划期内编排者修 PF-ONB-03 时在 MiscGUIListener 新增一份，与 LearnCommand 的 `localizeWordDifficulty/localizeWordMode` 内容相同）——"标识符本地化"成为新的多点重复面 | 低 | MC-3 引入 WordModes 时顺带收敛为 `WordModes.localizeDifficulty(player, key)` / `localizeMode(player, key)` 单一实现（三处调用点改走）；若 MC-3 让行，则记为独立清理项 |

**另**：plan-social / plan-ai-feedback 等兄弟域的"规划期发现"（P3-01~P3-12）已由编排者归档入 dev-findings.md；本域不重复列账，仅引用其接口约定（4.1）。

---

## 7. 规模估计、执行顺序与拆分建议

### 7.1 规模（S/M/L + story 数）

| Story | 内容 | 规模 | 依赖 | 可并行性 |
|-------|------|------|------|---------|
| MC-1 | 主菜单 9 按钮 + DuelChallengeGUI（+Listener）+ GUIUtils | M（~450 行，8 文件） | 无（只读既有 manager API） | 可与 MC-3/MC-5 并行 |
| MC-2 | 9 开关接线（ConfigManager 门 + 24 文件 gating 落点 + PF-MC-01 补门 + PF-MC-05） | L（~500 行改动，24 文件） | 无（但触碰面最广，建议单独成批） | 建议独占批次（避免与它域同文件） |
| MC-3 | modes 接线（WordModes + AnswerChecker + QuizState 快照 + 3 入口） | M（~350 行，8 文件 + 2 测试） | 无 | 可与 MC-1 并行 |
| MC-4 | 个人场景管理（Store 抽取 + 2 新 GUI/Listener + 入口按钮） | M（~600 行，8 文件） | 域 2 已冻结的 ImportResult 接口不变 | 可与 MC-1 并行（不同文件） |
| MC-5 | 挑战经济参数接线（含 F1-12 配套） | S（~150 行，4 文件） | 无 | 与 MC-2 同文件（DailyChallengeManager/MiscGUIListener）→ **串行** |
| MC-6 | 语义终案（F1-19）+ 裸 getString 二期（25 处）+ export 协议化 | M（~200 行，8 文件） | MC-6 的 DuelManager 改动与 MC-2 同文件 → 串行 | 半独立 |
| MC-7 | 死配置/死键终局（config + i18n + ProgressGUI + achievements.yml 死链） | M（~删除为主，8 文件） | 在 MC-2/3/5 全部落盘后做（避免删到正在接线的键） | **收尾批** |
| MC-6b | 场景选择页分页（可选拆分项） | S（~120 行，2 文件） | MC-4 | 可拆出 |
| — | 编排者 fan-in（i18n 双文件 + config-version 12 + CHANGELOG + 键级 diff） | S | 全部回流后 | 必须最后 |

**总计：L**（8-9 个 story，~2500 行新增/改动 + 大量删除；24+ 文件触及）。

### 7.2 执行顺序建议（批次化，按文件冲突分）

**批次 1（可 3 路并行）**：MC-1（主菜单，不动共享 manager）‖ MC-3（modes，动 WordLearningManager 链）‖ MC-4（个人场景，动 AIChatManager 链）
**批次 2（单路）**：MC-2（模块门，24 文件全覆盖；独占批避免与任何域同文件）
**批次 3（串行于 2 后，可与批次 1 乱序）**：MC-5（挑战参数——与 MC-2 同文件串行）→ MC-6（语义终案——DuelManager/AIChatManager 同文件）
**批次 4（收尾）**：MC-7（死键终局，必须在"接线"全部落盘后）
**批次 5**：编排者 fan-in + tester×3 + 修正循环 + CHANGELOG + commit

**先做/后做理由**：
- MC-1/MC-3/MC-4 无共享文件，先行且互相独立；
- MC-2 是"行为开关地基"——其它域（域 4 的 `social.enabled`、域 5 的 `season.enabled`）都要复用 ConfigManager 门模式的先例；且它改动面最宽（24 文件），**必须独占批**（并行禁令：与本批任何流都不同文件才能并行）；
- MC-5/MC-6 顺路搭 MC-2 的文件（同文件串行）；
- MC-7 是"删"——只在接线定稿后删才安全（先接线、后删键，即使接线漏了也会先产生"设了有用"的部分效果拖到验收暴露）；
- MC-6b 可整体拆出（若批次预算不足，先交付管理页，分页留下一批——不阻塞主验收）。

**与其它域的批次配合**：若域 4/域 2 同批施工——`SocialListener`（域 4 动态流）与本域 MC-2（成就墙门）同文件 → 编排者把两域排前后序；`ChatScenarioListener`（域 2）与 MC-2 同文件；`AdminCommand`（域 6 report）与 MC-6 同文件。跨域 fan-in 的 `messages config-version` 共同目标 12。

---

## 附：本域"消灭死配置/死入口"出口自检（施工完成时逐项打勾）

- [ ] 死入口：决斗/签到主菜单可达（R-82 关）；个人场景可列/可删；场景选择页分页可达全部（或 MC-6b 拆出时记为已知缺口）
- [ ] 死配置：Phase 0 审计的 21 必查 + ~17 额外 全部"接线或删除"（唯一例外：`gui.custom-textures` 移交域 7 决策、`tts.*` 等已活键）
- [ ] 死键：i18n 清扫表全删；双文件 diff 空
- [ ] 死类：ProgressGUI 移除；achievements.yml 资源死链移除
- [ ] 裸 getString：GUI 侧=0（P2 达成）；listener/core 侧=0（豁免清单除外，含 DuelManager 4 处）
- [ ] 每个模块开关可"关"且行为符合 1.(b) 表（9 门 × 10 项清单）
- [ ] modes 默认等价回归通过；4 子键+3 enabled 逐一生效
- [ ] `mvn clean verify` 全绿 + 新测试（WordModes/AnswerChecker/ExportResult）
- [ ] CHANGELOG 完整（含 F1-19 语义变更、time-limit 升级注意、challenges-per-day 生效说明）

