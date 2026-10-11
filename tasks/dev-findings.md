# 开发期发现汇总（未修复）

> 记录规则：开发 agent 在任务范围外发现的 bug / 疑点，一律不得自行修复，汇总到这里，由编排者统一归档排期。
> 状态取值：待排期 / 已排入 Phase 1 / 已排入 Phase 2 / 需用户决策 / 已关闭。
> 本文件累积所有批次，最新批次在上。

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
| F3-01 | `LearnCommand` 命令直开榜单/广场 | 不登记快照：排行榜点头像无反应（与 L-02 同事项）；广场 fallback 重建 ALL_PLAYERS 语义可能与所见页错位 | 命令入口不登记 | 中 | 待排期（P1：命令入口补登记或监听器加无快照兜底） |
| F3-02 | 笑话页返回 | 直达主菜单而非返回栈（与旧行为一致，刻意保持） | 旧行为 | 低 | 待排期（如产品要"回广场原页"需记页码/模式） |
| F3-03 | 词库展示顺序 | TreeSet 后为字母序（advanced 排第一），非"初级→中级→高级"语义序 | 排序选型 | 低 | 待排期（Phase 3 polish：显式排序表） |
| F3-04 | R-09d 尺寸分流约束 | 若未来 openHidden 改 54 格会重新塌回主页分支（约束已写注释） | 尺寸耦合 | 低 | 已记录（code-review 关注点） |

### 来自流 4（隐私与场景）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| 已关闭 | 广场内周/月/总榜仍列隐身者 | getRankedPlayers 未过滤 | 同根残留 | 高 | 已关闭（编排者 fan-in 时补：双方法 SQL 过滤 + ALL_PLAYERS 装配跳过） |
| F4-01 | `AIChatManager.buildSystemPrompt(ownerId)` | 重启后首次纯命令进个人场景 prompt 回落通用句（不走懒加载） | 读内存不触发加载 | 低 | 待排期 |
| F4-02 | ScenarioSelectGUI 7 格上限 | 个人场景 ≥2 个时 slot 17 玻璃可点，开启"看不见"的场景 | 无分页/扩容 | 低 | 待排期（GUI 分页范畴） |
| F4-03 | `getPersonalScenarios` 返回活引用 | 暂无写入方，安全但属潜在污染面 | API 设计 | 低 | 待排期 |
| F4-04 | 他人成就墙无独立隐私检查 | 目前无直达命令（依赖主页入口守卫），暂无绕过路径 | 纵深缺失 | 低 | 待排期（判断是否需要纵深检查） |

### 来自流 5（数据崩溃）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F5-01 | `resolvePlayerUUID` 幂等缺口 | reset 删了 player_data 行后，再对同一离线玩家 reset 报 not found（半删状态无法补删） | 解析与被删数据同表 | 低 | 待排期（改走 getOfflinePlayer 或独立 UUID 映射） |
| F5-02 | `AdminCommandHandler.addWord` | chinese 空串不断言，写入后下次加载被跳过但管理员无感知 | 入口无校验 | 低 | 待排期（加空校验 + i18n 提示） |
| F5-03 | deletePlayerData 与自动保存竞态 | reset 后 persistLongestStreak/recordCoinsEarned 可能重建残行（重进覆盖，影响极小） | 时序 | 低 | 待排期（备查） |
| F5-04 | `clearCache()` 不清今日计数器 | reload 后防刷上限残留（"reload 不重置防刷"语义可接受但反直觉） | 语义未对齐 | 低 | 待排期（与 D-10 reload 语义分裂一并定） |

### 来自流 6（日历二确）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F6-01 | `MainMenuListener.java:170-173` vs `:150-154` | stage1 确认 2-tick 延迟 reopen 与取消竞态：点了取消仍被拽回二确第二页 | 延迟体内无 stage 校验 | 中 | 待排期（P1 D-04 批次一并收敛） |
| F6-02 | `ConfirmationGUI.isConfirmGUI` 标题子串匹配 | 未来他 GUI 标题含 Confirm/确认会被误判 | 匹配过宽 | 低 | 待排期 |

### 来自流 2 重跑（领取幂等）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2R-01 | `hasPendingWordRewardForDate` 时序契约 | "必须在 clearAllWordChallengeClaims 前调用"是隐式契约，调错顺序误判 | 契约隐式 | 低 | 待排期 |
| F2R-02 | `onWordCorrect` pending 重试 return | 补发失败时本次答对的挑战词不走标记（好在已在全完成集合内，无实际丢失） | 重试与标记耦合 | 低 | 待排期 |
| F2R-03 | `getTodayChallenges` 无 ORDER BY | 领取 idx 下标与展示可能错位（与 R-33 同根） | 无稳定排序 | 中 | 待排期（P1 R-33 一并修：加 ORDER BY challenge_id） |

### 来自合流测试（建议级，非阻塞）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| T0-01 | 6 处监听器缺 onInventoryDrag guard | Shift+拖拽可带出展示物（仅 DuelGUI/MainMenu 有） | 漏 guard | 低 | 待排期（P1 R-55e/L-10 同事项，合并修） |
| T0-02 | `clearCache()` 漏清今日计数器 + profile 缓存 | reload 后沿用旧内存值 | 清理不全 | 低 | 待排期（与 F5-04/D-10 一并定 reload 语义） |
| T0-03 | `SocialListener` 无快照回退重建未过滤隐身 | 与渲染错位但点击仍查开关，只进笑话页不泄主页 | 回退路径漏过滤 | 低 | 待排期 |
| T0-04 | `loadPlayerLanguages` 缺省 en vs `getPlayerLanguage` 缺省 zh | 默认语言不一致 | fallback 未统一 | 低 | 待排期（与 R-55l 一并定） |

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
| F2-01 | `DailyChallengeManager.checkAndAssign` MySQL 分支 | INSERT 无 `ON DUPLICATE KEY`，并发重入可能抛 duplicate（SQLite 分支有 OR REPLACE） | 双后端不对称 | 低 | 待排期 |
| F2-02 | `player_sessions.score` / 周榜月榜口径 | `recordSession` 仍存会话学习分，榜单 SQL 未动；周榜月榜是"学习分榜"还是"金币榜"口径未定 | 口径文档缺失 | 低 | 需用户决策（定口径后再看是否改 SQL） |

> 流 A 上报的 `RewardEngine` fallback 残留（review-multiplier 0.5）已由编排者在 fan-in 时修复（0.5→1.2），不再列账。

### 来自流 B（真三模式）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2-03 | `ChatAnswerListener` 练习分支 | `getPracticingWord(...)` 取出后从未使用（死变量） | 历史残留 | 低 | 待排期 |
| F2-04 | `Word.equals()` 含 `reward` 字段 | 同英文不同 reward 的词判不等，`getRandomWords` 的 exclude 可能放行同英文词做干扰项（本批次已在 `AnswerChecker` 内加英文去重兜底） | equals 语义过宽 | 低 | 待排期（根因：给 `Word` 加注释或收窄 equals） |
| F2-05 | `MiscGUIListener.handleWordBankSelect` 写死 `"choice"` | 默认模式第三个散落点（另两处已收敛为常量/回落值）；将来改默认模式时此处会漏 | 散落常量 | 低 | 待排期 |

> 流 B 上报的旧 `answer-broadcast` 死键与 F1-06 同一事项，不重复列账。

### 来自流 C（决斗挑战）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2-06 | `DuelManager.rewardEngine` 字段 | 构造即赋值，之后零读取（死字段） | 历史残留 | 低 | 待排期 |
| F2-07 | `DuelRoom.submitAnswer` | `answer.trim()` 无 null 防护（上游理论非 null） | 防御缺失 | 低 | 待排期 |
| F2-08 | `DuelManager.cancelDuel` 约 550 行 | `opponentId` 算而不用 | 历史残留 | 低 | 待排期 |
| F2-09 | `en.yml` 缺 `word-learning-gui.countdown-urgent` | 中英 key 不对称（本次新增 key 双语已对齐，这是历史遗留） | 历史遗留 | 低 | 待排期 |

> 流 C 上报的 `sendDuelRequest` 过期任务裸 `remove` 已由编排者在 fan-in 时修复（改 `remove(k,v)`），不再列账。

### 来自合流测试（三维冒烟）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F2-10 | `WordLearningListener.pronounceCooldown` | 只 put 不删，quit 不清，无驱逐（每人一条，量级极小） | 无清理 | 低 | 待排期（quit 清理或复用午夜清理模式） |
| F2-11 | `scheduleMidnightRollover` 主线程同步查库 | 每玩家 1~3 条同步 SQL，百人服午夜会卡一下主线程 | 主线程 IO | 中 | 待排期（Phase 3：搬异步快照 + 主线程发消息） |
| F2-12 | `DuelRoom.timeoutCurrentWord` 零 UUID 哨兵 | `getLastCorrectAnswerer()` 可能对外吐 `new UUID(0,0)`，若 `DuelGUI` 按此查名会有怪显示（未读 `DuelGUI`，待确认非定论） | 哨兵外泄 | 低 | 待排期（先确认 `DuelGUI` 是否消费该方法） |
| F2-13 | `/le word` 成功提示 `{difficulty}/{mode}` | 填原始英文标识符（如 `choice`/`beginner`），中文玩家看到英文 | 未走 i18n label（review 路径已有 `mode-review` label，普通路径未跟进） | 低 | 待排期 |

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
| F1-01 | `DailyChallengeManager.java:367`、`MiscGUIListener.java:132`、`CheckInManager.java:130` | 每日挑战/签到发币直接 `eco.depositPlayer`，不经过 `RewardEngine`，`money.daily-cap` 管不住这三处 | cap 只覆盖 RewardEngine 4 条路径 | 中 | 待排期（Phase 2：cap 口径决策 + 3 处各加一行 `addTodayCoinsEarned`，或将发币收口） |
| F1-02 | `DuelManager.submitAnswer` | 对决答题不计入今日答题数（不走 `submitAnswer`，不调 `incrementTodayAnswerCount`） | 口径未覆盖 | 低 | 需用户决策（对决题算不算"题"？对决本身无发币故无刷币口） |
| F1-03 | `AchievementManager.getAchievementConfig()` | 非同步懒加载，`tryUnlock` 可达异步路径，与 `clearCache` 置 null 竞态（最坏多读一次文件，无脏数据） | 懒加载未同步 | 低 | 待排期（加 volatile/同步） |
| F1-04 | cap 提示 `{earned}` 显示 | `String.valueOf(double)` 显示 `490.0` 式小数 | 未格式化 | 低 | 待排期（可选 `eco.format()`，但各服货币格式不一） |

### 来自流 B（AI 与公平）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-05 | `AIChatManager.triggerAiOpening` 经 `chatAsync` | 开场白触发语占 1 条有效输入并消耗 1 次每日轮次（GUI 入口每次开对话都调） | 开场白走正常计数通道 | 低 | 待排期（Phase 2：开场白走独立计数或不计轮次） |
| F1-06 | `messages/zh.yml:351`、`en.yml:351` `answer-broadcast`（含 `{answer}`） | 已无代码引用，留存易被后人误用 | 兼容旧包保留 | 低 | 待排期（Phase 2 确认全员升级后删除） |
| F1-07 | 个人场景可见性 | 个人场景只能 `/le dialogue <个人key>` 进入，场景选择 GUI 和 Tab 补全看不到；`LearnCommand` 回显个人 key 时显示原始 key 而非展示名 | 白名单未覆盖 GUI/Tab 类，刻意收敛 | 中 | 待排期（Phase 2：个人场景 GUI 入口 + `getScenarioDisplayName(Player, key)` 接入） |
| F1-08 | `ChatScenarioListener` 超限体验 | 超限时先显示一次"AI 正在输入…"再报错 | `aiTyping` 提示在轮次检查之前 | 低 | 待排期 |

### 来自流 C（挑战领奖）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-09 | `MiscGUIListener.handleChallengeComplete` slot 8/22 | 庆祝 GUI 的"领取奖励"按钮纯装饰（只回主菜单），奖励早已自动到账，文案误导 | M18 统一入口未做 | 低 | 待排期（Phase 2，随 M18） |
| F1-10 | `wordChallengeClaimedToday`（`HashSet`） | 非线程安全 + `clearWordChallengeClaim` 零调用，跨天/长期运行残留过期条目 | 无午夜清理任务 | 中 | 待排期（Phase 2，随跨天 M8） |
| F1-11 | 经济门不一致 | `CheckInManager.giveCoins` 检查 `rewards.types.money.enabled`，挑战发放点与 `RewardEngine` 不检查——服主关了金币奖励挑战仍发钱 | 开关未统一 | 中 | 待排期（Phase 2：统一经济门） |
| F1-12 | `DailyChallengeGUI` / `handleDailyChallenge` `idx=(slot-11)/2` | 槽位映射假设恰好 3 个挑战，分配数量变化会错位 | 硬编码映射 | 低 | 待排期（Phase 2，随挑战分配改造） |
| F1-13 | 领奖双击 interleaving | "到账成功但标记返回 0"极小概率（���次点击交错），钱已发出但无成功消息 | 无按玩家串行锁 | 低 | 待排期（Phase 2 可选：按玩家串行锁，参考 `CheckInManager.playerLocks`） |
| F1-14 | 单词挑战补发跨天丢失 | 待补发 pending 在跨天（新日期重新分配单词）后按新日期判定会丢失 | 跨天逻辑未做 | 低 | 待排期（Phase 2，随 M8） |

### 来自流 D（决斗冲突）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-15 | `DuelManager.acceptDuel` 开头 | 先 `pendingRequests.remove(targetId)`，后继任一校验失败都会吞掉邀请，需发起方重发 | remove 先于校验 | 低 | 待排期（改：校验全过再 remove） |
| F1-16 | `DuelManager.sendDuelRequest` | 只查 `isBusy` 不查答题态；单槽 `pendingRequests` 被后请求覆盖 + 先请求的 60s 过期任务误删后请求 | 既有逻辑 | 低 | 待排期 |
| F1-17 | `ChatScenarioListener`（AI 情景对话） | 第四类聊天消费者，与 quiz/决斗/练习均无互斥 | 超出 M4 三态范围 | 中 | 待排期（Phase 2：四态互斥或明确共存规则） |
| F1-18 | 决斗分支异步判定 | `isInDuel` 后 cancel 事件、主线程才 `submitAnswer`，若决斗恰在两次之间结束，该条聊天被吞且无反馈 | 判定与消费不在同一线程/时刻 | 低 | 待排期 |

### 来自流 E（数据 Owner）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-19 | `daily-round-limit: 0` 语义 | 流 B 实现 `todayRounds >= dailyRoundLimit` 无 `>0` 门，0 = 拒绝所有 AI 对话而非"不限"（流 A 两道门有 `>0` 门，语义不统一） | 门条件不一致 | 低 | 需用户决策（0=禁用 or 0=不限？建议统一为"0/负数=不限"，与 A 一致） |
| F1-20 | `PlayerDataStore.loadPlayerData` | 两个独立同步块之间复用同一 `Connection` 对象：若另一线程恰好触发连接重建，第二个块拿旧连接 | 存量模式 | 低 | 待排期 |
| F1-21 | `PlayerDataStore.getPlayerName` | 仍直调 `Bukkit.getOfflinePlayer`（异步可达性存疑），但已无排行榜路径调用 | 存量 | 低 | 待排期 |
| F1-22 | 重启丢失今日计数 | 今日答题/AI/金币计数器内存态，重启丢失（设计接受：防刷上限不是账本） | 设计决定 | 低 | 需用户决策（如运营要求重启不丢，需 DB 持久化 Story） |

### 来自合流测试（三维冒烟 + 打回修复）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F1-23 | `DailyChallengeManager.depositWordChallengeReward` vs `MiscGUIListener.depositChallengeReward` | 两份 Vault deposit + `recordCoinsEarned` 拷贝；Listener 直接调 `DatabaseManager.recordCoinsEarned` 属越层写 DB | 流 C 白名单收敛所致 | 低 | 待排期（Phase 2：收拢到一处，建议收进 RewardEngine/DatabaseManager 门面并让 Listener 只调一层） |
| F1-24 | 今日计数 Map 无驱逐 | 三处今日计数 Map + `todayCoinsCache` 只增不删（quit/reload 不清，靠日期懒清零），UUID 缓慢堆积 | 无驱逐 | 低 | 待排期（定期 `removeIf` 或 unload 时清理） |
| F1-25 | `broadcastCorrectAnswer` 语言 | 取模板用答题者语言，发给所有收件人同一语言版本，混服收件人可能看到对方语言 | 单模板广播 | 低 | 待排期 |

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
| F0-03 | `AdminCommandHandler.java:80-82`（`resolvePlayerUUID`） | 按 `player_name=?` 查库恒空，离线玩家按名 `/lea reset <name>` 失效 | `player_data.player_name` 全代码库零写入点（同 H10） | 中 | 待排期（Phase 1，随 player_name 写入方案一并修） |
| F0-04 | `DatabaseManager.java:257-264`（`getConnection` 快路径） | 无锁读 `connection` 并 `isClosed()`，与 `shutdown()`/重连存在极窄 TOCTOU 竞态窗口 | 快路径为性能省略加锁 | 低 | 待排期 |
| F0-05 | `DatabaseManager.java:46`（`initialize()`） | 直调会覆盖 `connection` 字段而不关闭旧连接，无幂等保护（本次仅就地加固为锁内，未加"先关旧连接"） | 原实现假设 initialize 只跑一次 | 低 | 待排期 |

### 来自流 B（配置 / i18n 基线）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-06 | `ProxyMessenger.java:168-169` | `messages.getString("dialogue.proxy-achievement")` 返回 null 时紧接 `template.replace(...)` 会 NPE，无兜底 | 直接读原始 config，未走安全封装 | 中 | 待排期（Phase 1；可用新 helper `getPlayerMessageSafe` 修） |
| F0-07 | `MainMenu.java:36` | `translateAlternateColorCodes('&', messages.getString("gui.main-menu-title"))` 无 null 守卫，key 缺失即 NPE | 同上，未走安全封装 | 中 | 待排期（Phase 1） |
| F0-08 | `UpdateChecker.checkAsync()` 于 `LetsLearnEnglish.java:194-195` | `general.check-updates` 是死配置且注释误导：称"关闭后不连 GitHub API 适合内网"，但代码无条件联网，内网/隐私场景无法通过配置关闭 | 开关从未接线 | 中 | 待排期（Phase 2） |
| F0-09 | `config.yml` `gui.main-menu-title` vs `messages/*.yml` 同名键 | 重复定义：管理员改 config.yml 的标题不生效；且 `MainMenuListener` 拿 i18n 标题做点击判定，两者漂移会导致主菜单点击检测失效 | 双真相源 | 中 | 待排期（Phase 2） |
| F0-10 | `config.yml` `progress-system.achievements.config-file: "achievements-config.yml"` vs `ConfigManager.loadAllConfigs()` 硬编码 `loadConfig("achievements.yml")` | 文件名不一致且该 key 零读取 | 死配置 + 命名漂移 | 低 | 待排期 |
| F0-11 | 模块级 `enabled` 开关族（`word-system`/`ai-chat`/`progress-system`/`daily-challenges`/`leaderboard`/`achievements`/`rewards`） | 设为 `false` 是静默 no-op，管理员以为关了实际没关 | 开关从未 gating | 中 | 待排期（Phase 2） |

> 流 B 另产出 `tasks/dead-config-audit.md`（21 条必查死配置 + 约 17 条额外死配置），此处不重复。

### 来自流 A（测试安全网）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-12 | `WordLearningManager.java:338` | `if (consecutiveCorrect <= 0) return 1.0;` 是冗余守卫（删掉结果完全一致），纯死分支 | 可读性冗余 | 低 | 待排期（清理积压） |

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
