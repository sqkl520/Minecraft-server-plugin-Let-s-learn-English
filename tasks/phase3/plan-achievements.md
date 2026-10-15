# Phase 3 规划：成就系统重构（域 3）

- 日期： 2026-10-13
- 施工状态： **已施工**（P3 Wave 1 域 3，提交 `db1e5bc`；三维 tester GO/PASS/PASS）
- 基线： 1.0.7-SNAPSHOT（P2 批次 commit `716d4fd` 之后，工作树含 Phase 3 各域规划只读产出）
- 来源： `tasks/plan-roadmap-usable-to-delightful.md` 第 8 节方向清单第 3 条（"+6 质量型：连续复习/错题清零/正确率90/对决首胜/互助/30天；刷量成就加难度前置"）；`tasks/p2-polish-execution-plan.md` 第五节域 3
- 规划纪律： 本轮为只读规划，未修改任何源码/配置；唯一产出为本文件；规划期发现见第 6 节
- 规模估计： **M+**（约 650~800 行，5 个 story = 4 开发 + 1 编排者 fan-in）

---

## 1. 操作逻辑

### 1.1 现有 16 个成就逐一盘点

判定入口全景（先读后表）：

- `AchievementManager.checkSessionAchievements(playerId, sessionCorrect, sessionIncorrect, sessionWordsCount, mode)` —— `AchievementManager.java:219-251`，由 `WordLearningManager.showSessionComplete` L542-548 调用（会话自然走完的唯一结算点；`ChatAnswerListener.java:244` 与 `WordLearningManager.startAnswerTimer` 超时分支 L315 两条路径都汇至此）。
- `checkStreakAchievement(playerId, currentSessionStreak)` —— L260-264，由 `ChatAnswerListener.java:233` 每次答对后调用；末尾顺带落盘 `longest_streak`（M9）。
- `checkDailyStreak(playerId)` —— L274-312（private），由 `checkSessionAchievements` L247 调用；直查 `player_sessions`（LIMIT 40，R-79/P2）。
- `checkAIChatAchievement(playerId, totalConversations)` —— L327-330，由 `AIChatManager.endConversation` L523 调用（计数走 `PlayerDataStore.incrementConversationCount` R-28，即时落库）。
- `checkCoinAchievements` —— L269-272，**全库零调用死方法**（见 PF-ACH-04）；COINS 实际在 `checkSessionAchievements` L248-250 经 `DatabaseManager.getCoinsEarned`（DB 读）判定。
- `tryUnlock(playerId, achievement)` —— L127-190，唯一写入口；判定链：`isAchievementEligible`（L192-213，读 `achievements-config.yml` 的 `enabled` + `time-limit`）→ `unlockedCache.contains` → 写内存 + DB UPSERT → 在线则聊天双行 + `SoundManager.playSound(player, "complete")` + 跨服广播。

| # | id | 名称（zh） | 判定数据源 | 触发点（现状 file:line） | 刷量型？ | 处置 |
|---|----|-----------|-----------|------------------------|---------|------|
| 1 | `first_word` | 初出茅庐 | 会话参数：`sessionCorrect>=1 && sessionIncorrect==0` | checkSessionAchievements L223-225 | 否 | 保持判定（PF-ACH-06：判定实际要求"零错会话"，与"答对第 1 个"文案有偏差；判定不动、描述文案随 US-ACH-1 校正，见 2.6） |
| 2 | `words_25` | 小有所成 | `stats.getTotalCorrect()` 累计答对 | L228 | 轻度 | 保持（新手节拍不加前置） |
| 3 | `words_100` | 单词达人 | 同上 | L229 | 中度 | 保持 |
| 4 | `words_500` | 学富五车 | 同上 | L230 | **重度** | **加前置**（见 1.3） |
| 5 | `perfect_session` | 完美无缺 | 会话参数：全会话 100% 且题量>0 | L232-234 | 否（质量型） | 保持 |
| 6 | `streak_3` | 小试牛刀 | 会话内连击 `result.consecutiveCorrect` | ChatAnswerListener:233 → L261 | 否 | 保持 |
| 7 | `streak_10` | 势如破竹 | 同上 | L262 | 否 | 保持（并作为 `sessions_50` 前置） |
| 8 | `spelling_master` | 拼写达人 | 会话参数：mode=spelling 且 `sessionCorrect>=1`（FEAT-MODE-01 已收紧） | L239-241 | 否 | 保持 |
| 9 | `daily_streak_3` | 连续三天 | `player_sessions.session_date` 连续日查询 | checkDailyStreak L310 | 否 | 保持 |
| 10 | `daily_streak_7` | 一周全勤 | 同上 | L311 | 否 | 保持 |
| 11 | `sessions_10` | 坚持不懈 | `stats.getTotalSessions()` | L244 | 轻度 | 保持 |
| 12 | `sessions_50` | 学习标兵 | 同上 | L245 | **重度** | **加前置**（见 1.3） |
| 13 | `coins_500` | 小有积蓄 | `DatabaseManager.getCoinsEarned`（DB 读） | L249 | 中度（签到/挑战发币可绕开答题） | **加前置**（见 1.3） |
| 14 | `coins_5000` | 金库满载 | 同上 | L250 | **重度** | **加前置**（见 1.3） |
| 15 | `ai_chat_10` | 初识AI | `conversation_count` 累计（R-28） | AIChatManager:523 → L328 | 轻度 | 保持（`count-min-rounds` 已是质量门） |
| 16 | `ai_chat_50` | AI伙伴 | 同上 | L329 | 重度 | 保持不加前置（累计 50 蕴含 10，前置无意义；有效对话门槛已防进退刷取） |

**id 冻结清单一句话**：以上 16 个字符串是 DB `achievements.achievement_id` 里已存在的值，**永不修改**（详见 4.4）。新增成就一律追加新 id。

**关于 `sessions` 的口径提醒**：`endSession`（`WordManagerImpl.java:149-180`）对 stop/quit 也累计 `totalSessions`（R-59 既定裁决：quit 计入），因此 `sessions_10/50` 天然可被"反复速退开新局"刷——这正是 `sessions_50` 必须加前置的原因。

### 1.2 新增 6 个质量型成就：精确判定条件

设计原则（理由先写）：**每个新成就的判据必须用"已有持久化数据或本域新增的持久化计数"判定，拒绝纯内存态**（对齐 F1-22 教训：重启丢今日计数是防刷上限可接受，但成就是长期资产，判定源必须跨重启稳定）；**挂钩点必须是现有结算事件**，不新建事件总线（见 3.3）。

| # | 新 id | 名称（zh/en 草案见 2.6） | 精确判定条件 | 判定数据源 | 触发点（挂钩在现有方法上） |
|---|-------|------------------------|-------------|-----------|--------------------------|
| N1 | `review_streak_7`（连续复习） | 温故知新 / Memory Keeper | **连续 7 个自然日每天至少完成一次复习会话且当天复习答对 ≥1 词**（防挂机蹭天数，精神同 SPELLING_MASTER 收紧）。判定窗口与 checkDailyStreak 同款回看 30 天 | **需数据补充**：`player_sessions` 新增列 `review_words INT DEFAULT 0`（`recordSession` 当 mode=review 时累加 correctCount，双后端 UPSERT 同步加列）。连续判定：`SELECT session_date FROM player_sessions WHERE uuid=? AND review_words>0 ORDER BY session_date DESC LIMIT 40` + 与 checkDailyStreak 同构的逐日回看循环（今天缺不打断、昨天缺才打断）。**判定读已完成落盘的行**：`result.mode` 在结算快照里（Q1），review 会话自然走完时 `recordSession` 已先写入 | 新方法 `AchievementManager.checkReviewStreak(playerId)`，挂在 `checkSessionAchievements` 内（`checkDailyStreak` 调用点 L247 旁）；**排序保证**：`showSessionComplete` 先 `recordSession`（L534）后判定（L542）——R-30 既定顺序，review_words 自动吃到今天 |
| N2 | `wrong_book_cleared`（错题清零） | 错题清零 / Clean Slate | **通过错题本练习（submitPracticeAnswer 答对导致错误次数归零移出）累计移出 ≥5 个词，且在最后一次移出时错题本为空**。手动点击删除（WrongBookListener.handleRemove）**不计数**——防"点 5 次删除"白拿 | **需数据补充**：`player_data` 新增列 `wrong_book_cleared INT DEFAULT 0`；写侧走 R-28 pattern（`PlayerDataStore` 内存计数权威 + 绝对值 UPSERT，新增 `DatabaseManager.setWrongBookCleared`，列名避开 word_progress 的 INSERT OR REPLACE 陷阱，P3-09）；空判定用现有 `getDifficultWordsData(uuid).isEmpty()`（`PlayerDataStore.java:344-350`） | `WordLearningManager.submitPracticeAnswer` L586-620 的移出分支（L599-603 `newCount<=0 → removeDifficultWord`）后追加：计数 +1 落库 → `checkWrongBookCleared(playerId, newTotal, isEmpty)` |
| N3 | `accuracy_90`（正确率 90） | 精准射手 / Sharpshooter | **累计答题（correct+incorrect）≥100 题且累计正确率 ≥90%**。最小样本 100 的理由：① PERFECT_SESSION 已覆盖"单会话满分"场景，本成就必须比它更"长程"才有区分度；② 防小样本——9/10 即 90% 不能解锁；③ 用的是**总答题数**而非答对数做样本（答错的题也计入分母），无法用"只打简单题"缩小分母 | 零新增数据：`stats.getTotalCorrect()` / `getTotalIncorrect()`（`PlayerDataStore.PlayerStats`，加载时从 word_progress 重建、结算增量更新） | `checkSessionAchievements` 内一行（stats 已在 L221 取好）：`if (correct+incorrect >= 100 && correct/(correct+incorrect) >= 0.90) tryUnlock(...)` |
| N4 | `duel_first_win`（对决首胜） | 初战告捷 / Duelist | **在一场正常打完的对决（有人打满 5 分触发 endDuel）中获胜**。语义裁剪（理由）：退赛/掉线宽限判负**不算**——quitDuel（L521-546）与掉线宽限（L590-607）不经过 `endDuel`，天然排除。若把"对方退赛"也算赢，会给小号互刷送人头留口子；"首胜"只需一次真实对局，收紧后度量更干净 | 零新增数据——**成就行本身就是记录**（P3-08 说"对决零持久化导致无数据源"只对"累计胜场数"成立；"首胜"是二值事件，tryUnlock 的 DB 唯一约束即是持久化） | `DuelManager.endDuel` L479：取到 `winnerId`（L483）后直接 `plugin.getAchievementManager().tryUnlock(winnerId, Achievement.DUEL_FIRST_WIN)`（放在 winner 在线判断之外——赢家此刻掉线的极端窗口也允许落库，tryUnlock 对离线玩家静默跳过通知段） |
| N5 | `helpful`（互助） | 互助之星 / Helping Hand | **鼓励过 ≥10 个不同玩家的学习动态**（distinctTargets ≥10）。用"去重人数"而非"次数"：一人一条动态至多鼓励一次（UNIQUE(feed_id, cheerer_uuid)），若按次数只需对同 1 人 10 条动态连点，门槛形同虚设 | 数据源 = 域 4（广场社交）`SocialManager.getCheerStats(uuid).distinctTargets`（plan-social 4.3 已冻结该接口，`CheerStats(given, distinctTargets, received)`） | **跨域触发**：按 plan-social 4.3 约定，由 `SocialListener` 在 cheer 成功后（主线程回调分支）调 `plugin.getAchievementManager().checkHelpAchievement(playerId)`；AchievementManager 侧 `getSocialManager() != null` 守卫（域 4 未落地时该成就保持锁定，不报错）。**依赖方向**：AchievementManager → SocialManager 单向（防 P2-26 式 Core↔Core 环） |
| N6 | `study_days_30`（30 天） | 学而不厌 / Long Haul | **累计在 30 个不同的自然日完成过至少一次学习会话（不要求连续）**。裁决"累计而非连续"的理由：① 连续变体已被 DAILY_STREAK_3/7 占据，"连续 30 天"对休闲服玩家近乎不可达（一次请假即归零），作为留存钩子弊大于利；② 累计 30 天与 SEASONS（30 天赛季窗口，域 5）形成"同尺度"的长期锚，跨域叙事一致；③ 数据上 COUNT(DISTINCT) 直接可得 | 零新增数据：`player_sessions` 按 `UNIQUE(uuid, session_date)` 一天一行，`SELECT COUNT(DISTINCT session_date) FROM player_sessions WHERE uuid=?` | `checkSessionAchievements` 内（recordSession 先行保证今天算入）：新方法 `checkStudyDays(playerId)`；该查询为"索引前缀 uuid 覆盖"的有界查询（百级行数），与 checkDailyStreak 同主线程同步查库模式（在案风险已接受） |

**数据补充方案汇总（两条，均为加列不改表）**：

1. `player_sessions.review_words INT DEFAULT 0` —— 走 `DatabaseManager.addColumnIfMissing`（L1334-1340 已有机制，MySQL/SQLite 双兼容）+ `recordSession` 双后端 UPSERT 的 UPDATE 列表追加 `review_words = review_words + excluded.review_words`（/ `+ VALUES(...)`）。**跨服合并语义正确**：同服/跨服同一天两行也累加，与 words_count 现有语义一致。
2. `player_data.wrong_book_cleared INT DEFAULT 0` —— `addColumnIfMissing` + `PlayerDataStore` 内存计数（绝对值 UPSERT，同 `conversation_count` R-28 pattern；原因：quit 时监听器顺序不保证，绝对值写使计数与顺序无关）+ `unloadPlayerData`/`clearCache` 同步清理（对齐 conversationCountCache 的清理点）。

**明确不做**：不给新成就做"隐藏成就"（理由见 2.3）；不给 `review_streak` 做 3 天档（域内 7 天一档即可，防成就表膨胀）。

### 1.3 刷量成就的难度前置方案

**问题定义**：现有刷量成就（WORDS_500 / SESSIONS_50 / COINS_500 / COINS_5000）的获取路径存在"绕核心学习"的刷法——`sessions` 计 stop/quit（R-59）、金币经签到/挑战可零学习获取（F1-01/F1-11 后续）、`WORDS_*` 可在 beginner+choice 舒适区无限刷。前置的目标不是"提高数值门槛"（那是量的叠加），而是**要求质量证据**：玩家必须先展示过某种"真学了"的行为，量成就才予解锁。

| 成就 | 前置 | 选它的理由 | 前置判定数据源 |
|------|------|-----------|--------------|
| `sessions_50` | 已解锁 `streak_10` | 直接反制"每局答 1 题速退 ×50"的刷法：10 连击只能在一次会话里真实答对 10 连中产生，无法速退伪造 | `unlockedCache`（hasAchievement，内存零成本） |
| `words_500` | 答题总数 ≥100 **且** 累计正确率 ≥70% | "500 个答对"可能全部来自最简档无脑连点；要求 100 题样本 + 七成正确率 = 证明刷的是"有效的量"而非无效重复。与 N3（90%）形成"70% 通行证 / 90% 荣誉章"的两档关系 | `PlayerStats` 的 `getTotalCorrect()+getTotalIncorrect()` 与比值（内存） |
| `coins_500` | 累计答题 ≥50 | 防纯签到囤币（签到单日最高 100，7~10 天零学习即到 500）；50 题是很轻的门，不阻碍正常经济玩家 | `PlayerStats`（内存） |
| `coins_5000` | 已解锁 `words_100` | 5000 金币纯靠签到/挑战要几十天，前置要求"真答对过 100 词"，堵住"纯挂机经济流" | `unlockedCache` |
| `ai_chat_50` | 不加 | 累计 50 蕴含 10（无信息量）；`count-min-rounds` 有效对话门槛已防进退刷取 | — |
| `words_25`/`words_100`/`sessions_10` | 不加 | 新手正反馈节拍——前 30 分钟内的解锁密度是留存钩子，前置会打碎它；刷量危害随量级上升，低档位不值得设卡 | — |

**实现要点**：前置判定**收口在 `tryUnlock` 内部**（新增 `meetsPrerequisites(playerId, achievement)` private 方法，switch 硬编码四条 + default true）。收口在唯一写入口的理由：任何触发路径（含未来新挂钩）都无法绕过；且判定只读内存（stats/unlockedCache），零 DB 成本。前置描述必须写进成就文案（玩家可预期），见 2.6 的 4 条描述改写。

### 1.4 跨重启 / 多服一致性

**现状（DB 唯一性保障）**：

- 表：`{prefix}achievements(uuid, achievement_id VARCHAR(64), unlocked_at, UNIQUE(uuid, achievement_id))`（`schema.sql:39-45` / `DatabaseManager.createTables` L134-141 双后端同构）。`player_achievements` 为死表（E-03 在案，reset 会删，无写入）。
- 写：`tryUnlock` L139-159 —— SQLite `INSERT OR REPLACE` / MySQL `INSERT ... ON DUPLICATE KEY UPDATE unlocked_at`。**先写内存后写库**；写库异常只 WARNING（内存已解锁、DB 未落，极端下重启丢该条 → 下次触发条件再次满足时补写，可自愈）。
- 读：join 时 `loadPlayerAchievements`（`PlayerJoinListener.java:27`）全量载入 `unlockedCache`（ConcurrentHashMap）；`/lea reset` 先 `unloadPlayer` 清缓存（R-03 在案）再删表行。
- 多服（共享 MySQL）：唯一约束阻止重复行；**已知极端窗口**——同一玩家在两台子服同时满足同一成就 → 两边都广播一次（第二台 DB 层被 UNIQUE 吞掉但聊天已发）。概率极低、后果仅是重复播报，**接受并注释**，不做分布式锁。

**新数据的一致性对照**：`review_words`/`wrong_book_cleared` 都进共享表 → 跨服天然合并（同 `words_count` 语义）；写侧全部走 `connectionLock` 同步块（M16 收口后惯例）。

**主线程化裁决**：本域把 tryUnlock 的"通知段"（L161-188 的聊天/音效/广播）调度回主线程执行（见 3.4），修复 PF-ACH-02 的异步 API 隐患。

### 1.5 解锁瞬间的表现（现状 + 增补）

- 现状：玩家本人收到**聊天双行**（`achievement.unlock-title` 拼 `achievement.unlock-format` 一行 + 描述灰字一行，`AchievementManager` L166-175）+ `SoundManager.playSound(player, "complete")` + 跨服广播（`ProxyMessenger.sendAchievementBroadcast`，仅在线且有代理通道时）。
- 增补（本域，S 级）：
  1. **Title 展示**：`player.sendTitle(unlock-title, 成就名)`（项目当前零 sendTitle 使用，Paper 1.20 API 可用；标题 5s→30t、副标题 10t）。理由：成就解锁是"高光时刻"，聊天行会被刷屏淹没；Title 是零基建的仪式感。保留聊天双行（日志/回看）。
  2. **解锁音效真接线**：现状 `achievement-system.unlock-sound` 配置值**只被当作布尔门使用**，实际恒播 `complete` 键（PF-ACH-01）；改 `SoundManager` 新增 `playSoundByName(Player, String)`（复用其 resolveSound + 未知键 WARNING-once 机制），`AchievementManager` 用配置值直放，配置真生效。
  3. **墙内解锁日期**：`loadPlayerAchievements`（L59-78）的 SELECT 追加 `unlocked_at` 列，写入新增的并行缓存 `unlockedAtCache: Map<UUID, Map<String, Long>>`（**不改** `unlockedCache` 的类型与语义，避免动 `hasAchievement/getUnlockedCount/getUnlockedAchievements` 三个消费点；`tryUnlock` 成功时同步 put 当前时间；`unloadPlayer`/`clearCache` 同步清理）；成就墙已解锁项 lore 追加"解锁于 {date}"（数据本就在库，PF-ACH-09）。

### 1.6 判定发生时机一览（实现期自查表）

| 时机 | 线程 | 判定内容 |
|------|------|---------|
| 会话自然走完（showSessionComplete L515-551） | 主线程（聊天回调 runTask） | 全部 session 系（1-5、N3、N6、N1/N2 的"今天"数据依赖）、checkDailyStreak、checkReviewStreak、COINS |
| 每次答对（ChatAnswerListener L233） | 主线程 | STREAK_3/10 |
| AI 对话有效结束（AIChatManager L523） | **异步聊天线程**（bye 分支直调，见 PF-ACH-02） | AI_CHAT_10/50 |
| 对决打满 5 分（DuelManager.endDuel L479） | 主线程 | N4 |
| 错题练习移出（submitPracticeAnswer L599） | 主线程（runTask 包裹） | N2 |
| cheer 成功（SocialListener，域 4 落地后） | 主线程回调 | N5 |

---

## 2. 用户体验

### 2.1 成就墙的信息架构（分组裁决）

**结论：单页 22 格，按"类别分组排序 + 每项 lore 标注类别与进度"，不做分类跳页、不做稀有度。**

- 容量核对：`AchievementWallGUI` 网格为 54 格中的 28 个内容格（slot 10-16 / 19-25 / 28-34 / 37-43，`AchievementWallGUI.java:46-79`）。22 个成就 ≤ 28，**单页仍放得下**；分类若用独立 header 格会占 5 格（22+5=27，贴顶且脆弱）——所以**不用 header 格**，改为：`Achievement.values()` 前先按类别稳定排序，类别名作为每个条目 lore 第一行（"&7类别：复习"）。0 布局风险，且未来加成就只动排序不动槽位算术。
- 类别划分（enum 新字段 `category`，5 组）：

| 类别 | 成员 |
|------|------|
| LEARNING 学习 | first_word, words_25/100/500, sessions_10/50, streak_3/10, perfect_session, spelling_master, accuracy_90 |
| REVIEW 复习 | review_streak_7, wrong_book_cleared |
| CONSISTENCY 坚持 | daily_streak_3/7, study_days_30 |
| SOCIAL 社交 | duel_first_win, helpful, ai_chat_10/50 |
| ECONOMY 财富 | coins_500, coins_5000 |

- **不按稀有度**的理由：稀有度需要全服解锁率聚合查询（新查询面 + 缓存），收益仅是排序美观；不按完成度：完成度天然由绿/灰两态可视化，排序价值低。
- 标题加计数（可选增强）：`成就墙` → `成就墙 (7/22)`。`SocialListener` 标题判定是 contains（`SocialListener.java:110/133`），追加计数不断匹配——实现时在注释中保留该约束。

### 2.2 进度展示（"距解锁还差 X"）

**裁决：做，但只对"可量化数据源"的成就做（10/22），其余保持二态。** 理由：进度条是质量型成就的"未完成可见性"——没有它，玩家不知道 90% 正确率差多少，成就就只是盲盒；而 tryUnlock 二值本身不需要改造（进度是**只读派生视图**，永远由计数器现算，不引入"进度状态机"）。

| progressSource（enum 新字段） | 成就 | 现算来源 | "距解锁"文案 |
|------|------|---------|-------------|
| TOTAL_CORRECT | words_25/100/500 | stats.getTotalCorrect() | `{current}/{target}` |
| TOTAL_SESSIONS | sessions_10/50 | stats.getTotalSessions() | 同上 |
| COINS_EARNED | coins_500/5000 | getCoinsEarned（1 条有界 DB 查询，打开墙时一次） | 同上 |
| CONVERSATIONS | ai_chat_10/50 | getConversationCount（缓存） | 同上 |
| DAILY_STREAK | daily_streak_3/7 | 现算连续天（复用抽出的纯查询 helper） | 同上 |
| REVIEW_STREAK | review_streak_7 | 现算复习连续天 | 同上 |
| ACCURACY_PCT | accuracy_90 | correct/(correct+incorrect)，附样本进度（`样本 {n}/100`） | `正确率 {p}%（需 90%）` |
| STUDY_DAYS | study_days_30 | COUNT(DISTINCT)（1 条有界查询） | 同上 |
| WRONG_BOOK_CLEARED | wrong_book_cleared | 内存计数 | 同上 |
| NONE | 其余 12 个（会话型/事件型/前置型） | — | 不显示进度行 |

实现：`AchievementManager.getProgressSnapshot(UUID targetId)` 打开成就墙时**一次性**组装 `Map<Achievement, int[2]>{current,target}`（≤3 条有界索引查询：coins 1 + study-days 1 + streak 复用 1；全部走主线程，量级与 PF-SOC-01 同类，接受并在注释标注）。渲染复用**现存死键** `achievement.progress`（zh.yml:106，格式 `成就进度: {name} ({current}/{target})`）——本域将其复活。前置未满足的成就额外一行新键 `achievement.prereq-hint`（`&7前置：{name}`），把"为什么明明够数还没解锁"讲清楚。

### 2.3 隐藏成就？

**裁决：v1 不做隐藏成就。** 理由：① 隐藏成就的价值是探索惊喜，本项目成就全部由系统行为触发、无手动领取，解密空间不存在；② 隐藏会引入"墙内留空 vs 占位 ???"的渲染分支与 i18n 面，收益低；③ 22 个可见成就的收集驱动已足够。若未来要做，最小方案（3 行级）：enum 加 `hidden` 布尔 → 墙 render 时 `hidden && !unlocked` 跳过或渲染 `???` 占位 → 解锁后正常展示；**不预留字段**（YAGNI，加了没消费点的字段就是下一个死配置）。

### 2.4 混服 / 多语言

- 成就名/描述在 **enum 内以双语字面量**存储（`getDisplayName(lang)` / `getDisplayDesc(lang)` 按玩家语言取）——即"成就文案的 i18n 不在 zh.yml/en.yml"，新成就文案直接进 enum（见 2.6）。墙、解锁提示、PAPI 均按**查看者/玩家语言**渲染，现状正确。
- **跨服广播的混服语言是现状缺陷**（PF-ACH-03）：发送名称为解锁者语言、接收模板为服务器默认语言（`ProxyMessenger.handleAchievementBroadcast` L159-180），且一人一条无法按收件人渲染。本域修法：payload 扩展为同时携带 zhName+enName（`name|uuid|zh|en`），接收端**逐在线玩家按各自语言渲染发送**（R-86m 答对广播的 per-recipient 先例）；兼容：payload 仍是 `split("\\|", 3)` 解析，旧格式（3 段单名）回落到 zh=en=该名。改动 S 级、单文件。

### 2.5 解锁反馈流程（改后全貌）

```
[条件达成]
  ├─ tryUnlock：DB UPSERT（唯一约束兜底）→ 内存标记
  ├─ 通知段（runTask 到主线程，离线静默跳过）：
  │   ├─ Title: "成就解锁！"  / 副标题: 成就名            ← 新增
  │   ├─ 聊天双行（标题行 + 描述行，现状保留）
  │   ├─ 音效：achievement-system.unlock-sound 真接线    ← 修复 PF-ACH-01
  │   └─ 跨服广播（在线 + 通道已注册）：双语 payload，
  │        各子服逐收件人按语言渲染                       ← 修复 PF-ACH-03
  └─ （域 4 落地后）社交动态流自动产生 ACHIEVEMENT 条目（plan-social 1.4，零本域改动）
```

### 2.6 i18n / 文案草案

**架构事实（先写清）**：16+6 个成就的**名称与描述全部是 `Achievement.java` 的 Java 字面量**（不在 zh.yml/en.yml；enum 内双语 `zhName/enName/zhDesc/enDesc`）。因此"成就文案 i18n 草案" = enum 字面量草案（下方 6 条新增 + 4 条前置描述改写）；zh.yml/en.yml 只承担 UI 壳文案（墙上分类标签、前置提示等 ~8 个新键）。

**新增 6 成就 enum 字面量草案**：

| id | zhName | enName | zhDesc | enDesc |
|----|--------|--------|--------|--------|
| `review_streak_7` | 温故知新 | Memory Keeper | 连续 7 天完成复习会话，且每天至少答对 1 个复习单词——记忆的守护者！ | Complete a review session for 7 consecutive days (at least 1 due word correct each day) — a true memory keeper! |
| `wrong_book_cleared` | 错题清零 | Clean Slate | 通过错题练习累计攻克 5 个单词，并把错题本清空——从哪里跌倒，就从哪里爬起来！ | Clear 5 words out of your Wrong Book through practice and empty it — rise from where you fell! |
| `accuracy_90` | 精准射手 | Sharpshooter | 累计答题 100 题以上，并保持 90% 以上的正确率——稳定才是最难的！ | Answer 100+ questions total while keeping 90%+ accuracy — consistency is the hardest skill! |
| `duel_first_win` | 初战告捷 | Duelist | 在一场打满的单词对决中拿下首胜——用实力说话！ | Win your first full word duel — let your skill do the talking! |
| `helpful` | 互助之星 | Helping Hand | 鼓励 10 位不同玩家的学习动态——照亮别人的路，也照亮自己！ | Cheer the learning updates of 10 different players — light the way for others and yourself! |
| `study_days_30` | 学而不厌 | Long Haul | 在不同的 30 天里完成过学习会话（无需连续）——坚持三十天，习惯成自然！ | Complete a session on 30 different days (not necessarily consecutive) — thirty days makes a habit! |

**4 条前置成就的描述改写草案**（判定变化必须写进玩家可见文案）：

| id | 现 zhDesc（节选） | 改后 zhDesc | 改后 enDesc |
|----|------------------|------------|------------|
| `words_500` | 累计答对 500 个单词，真正的词汇大师！ | 累计答对 500 个单词（要求答题数 ≥100 且历史正确率 ≥70%），真正的词汇大师！ | Get 500 words correct (requires 100+ answers with 70%+ lifetime accuracy) — a true vocabulary legend! |
| `sessions_50` | 累计完成 50 次学习会话，你是大家的学习榜样！ | 累计完成 50 次学习会话（需先解锁「势如破竹」），你是大家的学习榜样！ | Complete 50 sessions total (requires unlocking "Unstoppable" first) — a role model for learners! |
| `coins_500` | 累计获得 500 金币，知识就是财富！ | 累计获得 500 金币（要求累计答题 ≥50），知识就是财富！ | Earn 500 coins total (requires 50+ answers) — knowledge is wealth! |
| `coins_5000` | 累计获得 5000 金币，你的金库已经满满当当！ | 累计获得 5000 金币（需先解锁「单词达人」），你的金库已经满满当当！ | Earn 5000 coins total (requires unlocking "Word Master" first) — your treasury is overflowing! |

**1 条语义校正（PF-ACH-06，非前置变更）**：

| id | 现 zhDesc | 改后 zhDesc | 改后 enDesc |
|----|-----------|------------|------------|
| `first_word` | 在任意模式中答对第 1 个单词，开启你的英语学习之旅！ | 在任意模式中完成第一个零失误的答题会话，开启你的英语学习之旅！ | Complete your first session with zero mistakes in any mode and start your learning journey! |

**zh.yml / en.yml 新键草案（~8 键，fan-in 落盘，config-version 11→12——与域 4 合批时只 bump 一次）**：

| key | zh | en |
|-----|----|----|
| `achievement-wall.category-learning` | 学习 | Learning |
| `achievement-wall.category-review` | 复习 | Review |
| `achievement-wall.category-consistency` | 坚持 | Consistency |
| `achievement-wall.category-social` | 社交 | Social |
| `achievement-wall.category-economy` | 财富 | Wealth |
| `achievement-wall.category-line` | &7类别: &f{category} | &7Category: &f{category} |
| `achievement-wall.unlocked-at` | &7解锁于 &f{date} | &7Unlocked on &f{date} |
| `achievement.prereq-hint` | &7前置: &c{name} | &7Prerequisite: &c{name} |

**复活键**：`achievement.progress`（zh.yml:106 / en.yml:106，现死键，本域墙上进度行使用）。
**死键处置建议**：`achievement.unlocked`（双文件同段）零引用（PF-ACH-05）——本域不动（不在范围），记录进发现节，随菜单域死键清理一并删。

### 2.7 achievements-config.yml 演进（向后兼容）

- **无需迁移**：`isAchievementEligible` 对缺失键默认 `enabled=true`（L194），老配置文件对新成就是"默认启用"；本域在 `achievements-config.yml` 末尾追加 6 个新条目（复制现有条目模板：enabled: true + time-limit disabled），仅为管理员编辑一致性（每个成就都有一块可改的地方）。`config-version: 1` 不动。
- **配置真相源说明（PF-ACH-10 重述）**：`AchievementManager.getAchievementConfig`（L45-54）与 `AchievementWallGUI.loadAchievementConfig`（L91-97）各自直读 `achievements-config.yml`；`ConfigManager.getAchievementConfig` 实际加载的是废弃 `achievements.yml`（F0-10 在案）——本域**不改**这套（改动面大于收益），但实现时必须在两处注释交叉引用，防后人改错文件。

---

## 3. 实现要点

### 3.1 Achievement enum 扩展方式（含向后兼容）

**新增 3 个字段 + 一个重载构造器**，不改造现有 16 个常量的声明（除 4 条描述改写）：

```java
// 新类型（Achievement.java 内或独立文件）
public enum AchievementCategory { LEARNING, REVIEW, CONSISTENCY, SOCIAL, ECONOMY }
public enum ProgressSource { NONE, TOTAL_CORRECT, TOTAL_SESSIONS, COINS_EARNED,
        CONVERSATIONS, DAILY_STREAK, REVIEW_STREAK, ACCURACY_PCT, STUDY_DAYS, WRONG_BOOK_CLEARED }

// enum 字段：category / progressSource / target(int，NONE 时 0)
// 保留原五参构造器（外部/未来代码兼容）→ 委托全参构造器，默认
//   category=LEARNING、progressSource=NONE、target=0；
// 既有 16 条常量改为全参声明（追加实参，名称/描述/id 语义不动）以获得
//   各自真实 category 与 progressSource——改的是枚举实参列表，不是 id；
// 新 6 条同样全参声明，追加在 enum 末尾——保 values() 前后相对顺序，
//   墙排序用稳定排序，旧展示序不被扰动
```

- **不加 `prerequisite` 字段**：前置判定写死在 `AchievementManager.meetsPrerequisites`（1.3），理由：前置是混合条件（成就引用 + 数值阈值两种形态），塞进 enum 要么造小表达式引擎（过度设计），要么字段语义分裂；收口在管理器反而单点可测、无绕过。enum 不存前置、但**描述字面量写明前置**（2.6）。
- **不加 `hidden` 字段**：2.3 裁决。
- **id 永不变**：1.1 的 16 个 id 是全系统契约（DB + PAPI + 墙 + fromId 扫描）；新增 id 同样一经落地即冻结。
- **单测锚点**（US-ACH-1，纯逻辑、无 Bukkit）：`fromId` 对新旧 id 可解析；`getDisplayName/Desc` 双语非空；`values().length == 22`；类别分组函数覆盖全部 22 条（"无孤儿"断言）。

### 3.2 判定挂钩点逐条清单（file:line 现状定位）

| # | 挂钩位置（现状行号） | 改动 | 归属 story |
|---|---------------------|------|-----------|
| H1 | `AchievementManager.checkSessionAchievements` L219-251 | 追加：N3 accuracy_90（stats 现成）、N6 checkStudyDays 调用、N1 checkReviewStreak 调用 | US-ACH-3 |
| H2 | `AchievementManager` 新增 `checkReviewStreak(UUID)` | 结构照抄 checkDailyStreak L274-312，SQL 加 `AND review_words > 0` | US-ACH-3（SQL）+ US-ACH-2（列） |
| H3 | `AchievementManager` 新增 `checkStudyDays(UUID)` | `COUNT(DISTINCT session_date)` | US-ACH-3 |
| H4 | `AchievementManager` 新增 `checkWrongBookCleared(UUID, int total, boolean empty)` | `total>=5 && empty → tryUnlock` | US-ACH-3 |
| H5 | `AchievementManager` 新增 `checkHelpAchievement(UUID)` | 经 `plugin.getSocialManager()` 取 CheerStats（null 守卫）；域 4 未落地时空转 | US-ACH-3（含 stub 语义） |
| H6 | `AchievementManager.tryUnlock` L127-190 | ① 前置守卫 `meetsPrerequisites` 插在 L130 `isAchievementEligible` 旁；② 通知段（L161-188）抽 `notifyUnlock(...)` 并主线程化（`Bukkit.isPrimaryThread()` 判断 + runTask）；③ `getAchievementConfig` 懒加载改 volatile（F1-03 修复，见 4.1）；④ 音效改 `playSoundByName(配置值)` | US-ACH-3 |
| H7 | `WordLearningManager.submitPracticeAnswer` L586-620 | 移出分支（L599-603）后：`store.incrementWrongBookCleared(id)` + `checkWrongBookCleared(id, total, store.getDifficultWordsData(id).isEmpty())` | US-ACH-3 |
| H8 | `PlayerDataStore.recordSession` L187-210 | **零签名变更**（mode 已是入参）：方法内 `int reviewWords = "review".equalsIgnoreCase(mode) ? correctCount : 0;`，绑定为第 7 参；双后端 SQL 的 INSERT 列清单与 UPDATE 列表同步加 `review_words` | US-ACH-2 |
| H9 | `DuelManager.endDuel` L479-516 | L483 后一行 `tryUnlock(winnerId, DUEL_FIRST_WIN)`（在 L493 在线判断之外） | US-ACH-3 |
| H10 | `SocialListener`（域 4 cheer 成功分支） | `achievementManager.checkHelpAchievement(playerId)` 一行；归属域 4 落地批次执行，本域冻结签名 | 跨域 |
| H11 | `PlayerDataStore` 新增 `incrementWrongBookCleared/getWrongBookCleared` | R-28 pattern：缓存 merge + `databaseManager.setWrongBookCleared(uuid, abs)`；`unloadPlayerData`（L756-772）/`clearCache`（L777-788）同步清理 | US-ACH-2 |
| H12 | `DatabaseManager.addColumnIfMissing` ×2 + `setWrongBookCleared` + `recordSession` 双后端 SQL | 见 1.2 数据补充 | US-ACH-2 |
| H13 | `ProxyMessenger.sendAchievementBroadcast` L78-81 / `handleAchievementBroadcast` L159-180 | 双语 payload + 逐收件人渲染 | US-ACH-4 |
| H14 | `LLEPapiExpansion` L103-141/L218-224 | 动态 params `achievement_<id>` | US-ACH-4 |
| H15 | `AchievementWallGUI.open` L31-89 + `AchievementManager.loadPlayerAchievements` L59-78 | 分组排序 + 类别/进度/解锁日期 lore + 标题计数（可选）；load 追加 SELECT unlocked_at → 新并行缓存 `unlockedAtCache`（tryUnlock/unload/clearCache 三处同步维护） | US-ACH-4 |
| H16 | `SoundManager` | 新增 `playSoundByName(Player, String)`（复用 resolveSound + WARNING-once） | US-ACH-3 |

### 3.3 事件总线？——**不新建，继续直调**（裁决）

理由：① 现状是"业务管理器直调 AchievementManager"（WordLearningManager/AIChatManager 两处先例），新增 4 个挂钩点（practice/duel/quiz-session/…）继续直调只增加 4 行；② 项目已知 Core↔Core 环问题（P2-26）的解法方向是"单向依赖 + Listener 层编排"，本域严格遵循：Duel→Achievement（单向）、Practice→Achievement（单向）、Social→Achievement（Listener 层调用；AchievementManager 读 SocialManager 的 CheerStats 是经 plan-social 4.3 冻结的**唯一允许反向读**，不构成调用环）；③ 事件总线会引入全局监听者注册面与调试难度，与"窄而完整"相悖。**例外说明**：若未来挂钩点超过 ~8 个，再评估 Bukkit 自定义 Event——本域不预留。

### 3.4 线程模型（PF-ACH-02 的正面回答）

| 路径 | 线程 | 手续 |
|------|------|------|
| 会话/连击/对决/练习挂钩 | 主线程 | 直调 tryUnlock |
| AI 对话挂钩（bye 分支） | **异步**（AsyncPlayerChatEvent → handleEndConversation → endConversation → checkAIChatAchievement） | tryUnlock 逻辑段（配置/缓存/DB，全部线程安全）留在调用线程；**通知段（sendMessage/Title/playSound/plugin message）统一 runTask 回主线程**（H6②）。这同时修复了现状的异步 API 隐患（PF-ACH-02），并使 F1-03 的配置懒加载改 volatile 后彻底安全 |
| 进度快照（墙打开） | 主线程 | ≤3 条有界索引查询，对齐现有墙/主页模式，注释标注 |
| 新计数落库（wrong_book_cleared） | 主线程调用、单条 UPSERT（同 persistLongestStreak 先例 L1000-1031 的"单条 SQL 很快直接同步"） | — |

### 3.5 PAPI 暴露

`%lle_achievement_<id>%`（如 `%lle_achievement_duel_first_win%`）：

- 解析：`onRequest` 在 `isKnownParams`（L218-224）**之前**加动态前缀分支——`params.startsWith("achievement_")` → `Achievement.fromId(params.substring(12))`，null → 返回 null（保留"让其他 expansion 尝试"的 legacy 契约，L111 注释）。
- 输出：已解锁 `✓` / 未解锁 `✗`（语言中立的符号，不引入新 i18n；理由：计分板场景空间极小）。隐私：成就状态不在 R-43 三门（showInSquare/showResults/coins）管辖内——现状 `%lle_achievements%` 计数同样不管；保持同口径（假名符号、非数值），不改隐私面，注释说明。
- 缓存：现 30s TTL 缓存按 params 分键（L124），动态 key 天然适配；`isKnownParams` 保持固定集合不动（动态分支短路在它前面）。
- 总数：`%lle_achievements%`（unlocked/total）自动从 16 变 22（`values().length` 动态，L209），零改动——ProfileGUI 的 `{total}`（L112）同理。

### 3.6 AchievementWallGUI 分组渲染改造

- 渲染序：`Arrays.stream(Achievement.values()).sorted(comparingInt(a -> a.getCategory().ordinal()))`（稳定排序，组内保 enum 声明序）。
- 每项 lore：
  - 行 1：类别（`achievement-wall.category-line`）
  - 行 2：原描述
  - 行 3（已解锁）：解锁日期（`achievement-wall.unlocked-at`）+ `profile.achievement-unlocked`
  - 行 3-5（未解锁）：进度行（`achievement.progress`，仅 progressSource≠NONE）+ 前置未满足时 `achievement.prereq-hint` + 时间窗行（现状 getTimeLeftString L104-134 保留）+ `profile.achievement-locked`
- 进度快照从 `plugin.getAchievementManager().getProgressSnapshot(targetId)` 一次取。
- **护栏**：在 `slot > 43 break`（L79）处加"成就数 > 28 时 WARNING 一次"（PF-ACH-07，防未来静默截断）；本域 22 个不触发。
- `SocialListener.handleAchievementWall`（L342-350）零改动（只判返回键 slot 8）。

### 3.7 触及文件清单

| 文件 | 改动 | story |
|------|------|-------|
| `core/achievement/Achievement.java` | 3 字段 + 1 重载构造器 + 6 新常量 + 4 条描述改写 + 2 子枚举 | US-ACH-1 |
| `core/achievement/AchievementManager.java` | 新 check×4 + meetsPrerequisites + tryUnlock 改造（前置/主线程化/volatile/音效）+ getProgressSnapshot + loadPlayerAchievements 读 unlocked_at + checkDailyStreak 查询逻辑抽 helper | US-ACH-3 |
| `core/SoundManager.java` | `playSoundByName` | US-ACH-3 |
| `core/duel/DuelManager.java` | endDuel 一行 | US-ACH-3 |
| `core/word/WordLearningManager.java` | submitPracticeAnswer hook（showSessionComplete 零改动） | US-ACH-2/3 |
| `data/PlayerDataStore.java` | recordSession 体内 review_words 派生 + wrong_book_cleared 缓存/清理 | US-ACH-2 |
| `data/DatabaseManager.java` | addColumnIfMissing ×2 + setWrongBookCleared + recordSession SQL | US-ACH-2 |
| `resources/schema.sql` | 两列 DDL 注释同步 | US-ACH-2 |
| `gui/AchievementWallGUI.java` | 分组/进度/日期渲染 + 截断 WARNING | US-ACH-4 |
| `expansion/LLEPapiExpansion.java` | 动态 params | US-ACH-4 |
| `core/ProxyMessenger.java` | 双语 payload + 逐收件人渲染 | US-ACH-4 |
| `resources/achievements-config.yml` | 追加 6 条目 | US-ACH-5 |
| `resources/messages/zh.yml` `en.yml` | 8 新键 + config-version 12 | US-ACH-5 |
| `src/test/.../AchievementDefinitionTest.java`（新） | enum 纯逻辑断言 4 例 | US-ACH-1 |
| `CHANGELOG.md` | 一行/变更 | US-ACH-5 |

**实施拆分**：US-ACH-1（enum 定义，先行冻结）→ US-ACH-2（数据层，独占 DatabaseManager/PlayerDataStore/schema.sql）∥ US-ACH-3（判定层，独占 AchievementManager/DuelManager/SoundManager；WordLearningManager 行区与域 7 不重叠但同文件需排期协调）→ US-ACH-4（展示层，独占 GUI/PAPI/ProxyMessenger）→ US-ACH-5 fan-in。

---

## 4. 依赖与风险

### 4.1 F1-03 是否先修？

**裁决：不单独立项，并入本域 US-ACH-3 一并修**。理由：F1-03（`getAchievementConfig` 非同步懒加载，`AchievementManager.java:45-54`）就在 tryUnlock 判定链上（L129），本域要改的是同一方法区域的四处（前置守卫/通知区/音效/懒加载），单独先修会产生一次无谓的二次编辑与冲突窗口。修法：`achievementConfig` 字段改 `volatile`；竞态最坏后果（多读一次文件）即消除。**注意**：F1-03 描述的"异步可达"路径真实存在且比记录更严重（PF-ACH-02：bye 分支异步线程直调链），本域的"通知段主线程化"是它的根治。

### 4.2 各新成就的判定数据源支持度核查（无数据源者已给补充方案）

| 成就 | 现成数据？ | 结论 |
|------|-----------|------|
| review_streak_7 | ❌（player_sessions 无复习标记；mode 列因 P3-05 同日合并不可靠） | 补列 `review_words`（1.2） |
| wrong_book_cleared | ❌（difficult_words 只有当前态，无"累计移出"计数；`removeDifficultWord` 调用点含手动删除混杂） | 补列 `wrong_book_cleared` + 练习路径专用计数（1.2） |
| accuracy_90 | ✅（PlayerStats correct/incorrect） | 零改动 |
| duel_first_win | ✅（endDuel 即是事件源；P3-08"无数据源"只对累计胜场成立） | 零改动 |
| helpful | ⚠️（依赖域 4 的 `SocialManager.getCheerStats`，plan-social 4.3 已冻结接口） | 跨域依赖，见 4.3 |
| study_days_30 | ✅（player_sessions COUNT DISTINCT） | 零改动 |

### 4.3 与赛季域（P3-5）/社交域（P3-4）的交叉

- **赛季成就（如"赛季前 10"）本域不做**：plan-season 4.1 已明确"成就定义与判定归 P3-3，本域提供数据"——但 v1 不做它的理由：赛季记账（`season_rewards` 台账）先落地后才有稳定判据源，且成就域先发版不应硬依赖赛季批次；**处理方式**：不写占位代码（占位即死代码），仅在本文件与代码注释中约定接口方向——赛季批次落地后按"AchievementManager 单向读赛季台账"追加 `checkSeasonAchievement`。**本域对"30 天"的解读（N6，累计日）与赛季 30 天窗口零冲突**（不同判据，plan-season 4.1 已预判此点）。
- **helpful 依赖域 4**：按 plan-social 4.3 冻结执行——AchievementManager 单向依赖 SocialManager（读 `getCheerStats`），触发由 SocialListener 编排（H10）。**排期裁决**：若两域同批实施，`AchievementManager.java` 文件锁给**本域**（改动大），域 4 的 `recordAchievement` 单行 hook 由本域一并落（或域 4 先落、本域在其上重构——plan-social 3.6 建议后者，采纳）；若域 4 延后，helpful 成就条目照常落地但保持锁定（`getSocialManager()==null` 守卫），文案不承诺上线时间。
- **文件锁重叠矩阵（与其余 Phase 3 域）**：

| 冲突文件 | 冲突域 | 处置 |
|---------|--------|------|
| `AchievementManager.java` | 域 4（tryUnlock 单行 hook） | 串行（social 先一行，本域后重构） |
| `WordLearningManager.java` | 域 7（倒计时 timer L283-346 重构） | 行区不重叠（本域只碰 L534/L586-620），仍建议同批时串行或明确行区冻结 |
| `PlayerDataStore.java` / `DatabaseManager.java` / `schema.sql` | 域 6 报告（firstLearnedCache 五处平行改动 + 新查询） | **强冲突**——需串行；本域加列改动小，建议本域先（报告域在其上加列/查询） |
| `AIChatManager.java` | 域 2（endConversation 返回 SessionSummary 改造 L313 区） | 本域不改 AIChatManager（挂钩点 L523 不动），零冲突 |
| `messages/zh.yml` / `en.yml` | 全部域（config-version bump） | 编排者 fan-in 合批只 bump 一次（域 4 也提议 12） |

### 4.4 旧成就 ID 兼容（DB 已存 id 冻结清单）

以下 16 个 `achievement_id` 已可能存在于任何生产库，**一个字符都不能改**（改了 = 老玩家进度丢失 + 新老双份）：

```
first_word, words_25, words_100, words_500, perfect_session, streak_3, streak_10,
spelling_master, daily_streak_3, daily_streak_7, sessions_10, sessions_50,
coins_500, coins_5000, ai_chat_10, ai_chat_50
```

保障机制（现状，继续沿用）：`fromId` 字符串扫描（L193-198）、`fromId` 返回 null 时墙/计数跳过（L104-106）、`VARCHAR(64)` 容量足够。新增 6 id 同样遵循 snake_case ≤64 且不与上述重复（US-ACH-1 单测断言全集唯一性）。

### 4.5 解锁广播对混服语言的现有处理（回应现状 + 修法）

现状：**发送端**用解锁者语言取成就名（`AchievementManager:170,186`），**接收端**用服务器默认语言渲染模板（`ProxyMessenger:166-167`），全部收件人同一句——混服服里中文玩家会看到"xxx unlocked: 初出茅庐"式的语言混拼（F1-25 同族问题）。修法见 2.4（双语 payload + 逐收件人渲染，对齐 R-86m 已修的答对广播口径）。**注意**：跨服通道的帧解析缺陷（P3-10/PF-SEA-01）已由编排者即修，本域的 payload 变更基于修后协议，**需双服实测**（单服无法验证，列入验收环境说明）。

### 4.6 风险表

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | `review_words` 列在 `recordSession` UPSERT 的 UPDATE 列表遗漏（复制 SQL 时漏改） → 恒 0，成就永不触发 | 中 | US-ACH-2 双后端 SQL 对照 + 人工用例（复习一天后查库确认非 0）；tester-code 抽查 SQL |
| R2 | 进度快照引入墙打开时同步查库（≤3 条）与"GUI 零 DB"精神相悖 | 低 | 有界索引查询 + 注释标注 + 与 PF-SOC-01 同族记录；若未来投诉再转异步预取 |
| R3 | 前置改变既有成就获取序：已 unlock 的玩家不受影响（缓存 contains 先行短路？——否！**顺序注意**：前置守卫若插在 `unlocked.contains` 检查之前，已解锁玩家重复调用也走前置（无副作用，仅多算）；插在之后则已解锁直接返回） | 低 | H6 实现顺序：`unlocked.contains` 保持最前置（现状 L133），守卫放其后——已解锁成就永不受前置回改影响（**关键验收点**） |
| R4 | Title/音效/广播在玩家恰好下线瞬间触发 | 低 | 通知段 `player.isOnline()` 复检（现有模式 L161-162 保留） |
| R5 | helpful 依赖域 4 未落地 → "22 个成就永远只能拿 21"误导 | 低 | 守卫静默锁定 + 编排者决定两域排期；若域 4 明确砍掉，helpful 从 enum 撤下（未上线前可撤） |
| R6 | COUNT(DISTINCT)/coins 查询在超大数据量服变慢 | 低 | 索引前缀覆盖（UNIQUE(uuid, session_date)）+ 调用频率 = 会话结束/墙打开；R-79 同族警戒线 |
| R7 | `achievements-config.yml` 与 `achievements.yml` 双文件误编辑（PF-ACH-10） | 低 | 两处读点注释交叉引用；本域不扩大该面 |

---

## 5. 验收标准

**环境**：本服 SQLite（广播用例需双服代理环境另注）。测试账号 A（新号）、B（老号）。

### 5.1 新成就逐条人工复测用例

| # | 用例（前置 / 操作 / 预期） |
|---|---------------------------|
| T1 review_streak_7 | 前置：A 有过至少 1 个到期复习词（普通会话答对后等 due）。操作：连续 7 天每天 `/le review` 走完一次且至少答对 1 词（调试可改代码内窗口为 3 验中间态）。预期：第 7 天会话结束瞬间解锁；某天只复习但 0 答对 → 该天不计入、链条中断；墙进度行"连续复习 N/7"逐日递增 |
| T2 wrong_book_cleared | 前置：A 有 ≥5 个词曾答错 3 次进错题本。操作：逐个 Shift+点击练习、答对到错误次数归零（第 5 个移出后错题本变空）。预期：清零一刻解锁；只清 4 个不解锁；**手动左键删除 5 个词不计数**（反例必测）；墙进度"已清 {n}/5" |
| T3 accuracy_90 | 前置：A 累计答题 ≥100。操作：把正确率打到 ≥90% 后完成一次会话。预期：达标会话结束时解锁；**边界正例 90/100（恰 90.0%）解锁**；**边界反例 89/100 不解锁**；新号 9/10（90%）**不解锁**（样本 <100，反例必测）。实现期用整数比较 `correct*100 >= total*90` 防浮点边界（0.9 双精度不可精确表示） |
| T4 duel_first_win | 前置：A、B 在线。操作：`/le duel B` 正常打满 5 分，A 获胜。预期：endDuel 播报时同刻解锁；B 不胜不解锁；**对方退赛/掉线获胜不解锁**（反例必测）；重启后 A 再赢一局不重复解锁（DB 唯一性） |
| T5 helpful | 前置：域 4 已落地。操作：A 鼓励 10 个不同玩家的动态（可用 10 个测试号或 GM 造数据）。预期：第 10 个不同对象鼓励成功时解锁；对同一人 10 条动态鼓励不解锁（反例） |
| T6 study_days_30 | 前置：A 已有 29 个不同学习日（GM 可直接在 player_sessions 造 29 天行）。操作：第 30 个不同的日子完成一次会话。预期：解锁；同一天重复 30 次会话不解锁（反例必测）；墙进度"30 天中的 N 天" |

### 5.2 刷量前置抽查

| # | 用例 | 预期 |
|---|------|------|
| T7 | B 已有 50 会话但未解锁 `streak_10` → 再完成一局 | `sessions_50` **不解锁**（前置未满足）；拿到 streak_10 之后的下一局会话结束 → 补解锁（判定每局重跑，累积条件满足即发） |
| T8 | 签到囤币到 500、零答题 | `coins_500` 不解锁；补答 50 题后下一局解锁 |
| T9 | **关键回归**：老号 B 已解锁 `sessions_50`（前置加入前）→ 部署新版本后任意操作 | 不解锁任何二次提示、不失效（R3 缓解的验证） |
| T10 | 速退刷会话：连开 50 局每局答 1 题即 stop | `sessions_50` 不解锁（前置守卫生效） |

### 5.3 系统面用例

| # | 用例 | 预期 |
|---|------|------|
| T11 | 成就墙：/le profile → 点成就 → B 看 A 的墙 | 22 格按 5 类排序、类别行正确、进度行仅量化成就显示、解锁日期显示、前置未满足显示前置行；**无第 29 条静默截断 WARNING** |
| T12 | PAPI：`/papi parse me %lle_achievement_duel_first_win%` | ✓/✗；未知 id（`%lle_achievement_nope%`）返回空/原样（legacy 不吞）；`%lle_achievements%` 显示 n/22 |
| T13 | 混服广播（双服）：A 在子服 1 解锁 → 子服 2 的英文客户端 | 收英文模板 + 英文成就名（per-recipient）；子服 2 中文客户端收中文 |
| T14 | 解锁标题：任何一次解锁 | Title 大字 + 副标题成就名 + 聊天双行 + 配置音效（改 `achievement-system.unlock-sound` 为 OTHER 音效验证真接线） |
| T15 | `/lea reset A` → A 重上 | 成就全清零（含 6 新）；wrong_book_cleared 计数归零（随 player_data 删除）；墙 n/22 从 0 起 |
| T16 | 重启/`/lea reload` | 成就与进度行、review_words、wrong_book_cleared 全量保留；reload 后成就开关即时生效（H9 语义不回退） |
| T17 | 异步路径：AI 对话中直接输入 `bye` 解锁 AI_CHAT_10 | 无异步 API 报错（Paper 日志零 `Asynchronous` 相关 WARN/Exception 新增），Title/音效正常 |

### 5.4 自动测试

- `AchievementDefinitionTest`（新增）：fromId 全集可解析、id 唯一、双语非空、22 条类别覆盖无孤儿。
- 既有 26 测试保持全绿；`mvn clean verify` BUILD SUCCESS + JaCoCo 门禁。

---

## 6. 规划期发现（只记录，未修复）

| # | 位置 | 现象 | 等级 | 建议 |
|---|------|------|------|------|
| PF-ACH-01 | `AchievementManager.java:177-181` | `achievement-system.unlock-sound` 配置值**未真接线**：只用来判空，实际恒播 `SoundManager` 的 `"complete"` 键——管理员改配置无效果（假配置） | 低-中 | 本域 US-ACH-3 修复（`playSoundByName`）；同时关联：解锁广播仅在 `player.isOnline()` 分支内发送，秒退竞态会丢广播（接受） |
| PF-ACH-02 | `ChatScenarioListener.java:83` → `AIChatManager:523` → `AchievementManager.tryUnlock:161-188` | `bye` 分支（AsyncPlayerChatEvent 异步线程）直调链可达 tryUnlock 通知段，内含 `player.sendMessage` / `SoundManager.playSound` / 插件消息发送——**异步线程调用主线程 API**，现状未观测崩溃但属隐患；F1-03 记录的"异步可达"比其描述的"多读一次文件"更严重 | 中 | 本域 US-ACH-3 通知段主线程化根治（3.4） |
| PF-ACH-03 | `ProxyMessenger.java:159-180` + `AchievementManager:186` | 跨服成就广播语言混拼：名称为解锁者语言、模板为服务器默认语言，且全收件人同文案（R-86m 已修的答对广播是 per-recipient，此处口径未跟进）；`proxy.cross-server-announcements` 仍是死配置（audit 在案） | 低 | 本域 US-ACH-4 双语 payload + per-recipient 渲染；死配置随菜单域清理 |
| PF-ACH-04 | `AchievementManager.java:269-272` | `checkCoinAchievements` 死公共 API（全库零调用；COINS 实际在 checkSessionAchievements 内走 DB 读） | 低 | US-ACH-3 顺手删除或接线（建议删，保留唯一判定路径） |
| PF-ACH-05 | `messages/zh.yml:105` / `en.yml:105` `achievement.unlocked` | 死键（零 Java 引用）；同段 `achievement.progress`（:106）亦死（本域将复活后者） | 低 | progress 本域复活；unlocked 记录待菜单域死键清理一并删 |
| PF-ACH-06 | `AchievementManager.java:223-225` | FIRST_WORD 判定语义与文案偏差：描述"答对第 1 个单词"，实际要求整个会话零错（答错一题后全对也不解锁） | 低 | 本域不改判定（保持宽容语义稳定）；US-ACH-1 校正描述文案为"首个零失误会话中答对单词"（文案维护成本一行） |
| PF-ACH-07 | `AchievementWallGUI.java:79` | `slot > 43 break` 静默丢弃第 29 个及之后的成就，无日志无分页；本域后为 22（安全），但继续扩容会踩 | 低 | US-ACH-4 加 WARNING-once 护栏；>28 时改分页（本次不做） |
| PF-ACH-08 | `AchievementWallGUI.java` → `AchievementManager.getUnlockedAchievements:95-109` | 墙查看**离线目标**玩家时会触发 `loadPlayerAchievements` 主线程同步查库（lazy path）——与 PF-SOC-01 同族 | 低 | 本域进度快照同族记录（R2）；统一转异步属跨域决策 |
| PF-ACH-09 | `AchievementManager.loadPlayerAchievements:61-70` | 只 SELECT `achievement_id`，`unlocked_at` 列**写了从不读**——数据在库未利用 | 低 | US-ACH-4 顺带读取用于墙展示（本域改进项） |
| PF-ACH-10 | `config.yml:213` / `ConfigManager:84` / `AchievementManager:47` / `AchievementWallGUI:92` | 成就配置**四个真相源漂移**：config 声称 `achievements-config.yml`、ConfigManager 加载已废弃 `achievements.yml`（F0-10）、两个消费点各自直读 `achievements-config.yml` 文件名 | 低 | 本域在四处注释交叉引用（2.7）；统一收口随配置面清理域 |

---

## 7. 明确不做（v1 范围外）

1. **隐藏成就**：2.3 裁决（本轮不做、不预留字段）。
2. **成就分级/稀有度/点数系统**：需要全服解锁率聚合与新元货币面，与"窄而完整"相悖。
3. **赛季成就（赛季前10 等）**：判据源（赛季台账）未落地，归赛季批次单向追加（4.3）。
4. **累计胜场/胜负统计类决斗成就**：需要决斗历史表（P3-08 零持久化），立项先决斗落表。
5. **进度条做进 PAPI / BossBar**：PAPI 只做二态成就位；BossBar 归域 7。
6. **`achievements.yml` 废弃文件删除、配置四真相源收口**：记录（PF-ACH-10），随配置清理域。
7. **签到系成就**（连续签到 N 天等）：roadmap 未点名，且与 DAILY_STREAK 语义近重复，不做。

---

## 8. Story 拆分与规模汇总

| # | Story | 内容 | 规模 |
|---|-------|------|------|
| US-ACH-1 | 定义层 | enum 3 字段/重载构造器/2 子枚举/6 新常量/4 描述改写/文案校正 + 纯逻辑单测 | S |
| US-ACH-2 | 数据补充层 | DatabaseManager（2 列迁移 + setWrongBookCleared + recordSession SQL）+ PlayerDataStore（wrong_book_cleared 缓存/清理；recordSession 体内派生 review_words）+ schema.sql | M |
| US-ACH-3 | 判定与管理器 | checkReviewStreak/checkStudyDays/checkWrongBookCleared/checkHelpAchievement + meetsPrerequisites + tryUnlock 改造（前置/主线程化/volatile/音效/Title）+ 3 处 hook + SoundManager.playSoundByName + 删死方法 | M |
| US-ACH-4 | 展示与暴露层 | 墙分组/进度/日期/护栏 + getProgressSnapshot + PAPI 动态位 + 广播双语 per-recipient | M |
| US-ACH-5 | 编排者 fan-in | zh/en 8 键 + config-version 12 + achievements-config.yml 6 条目 + CHANGELOG + 测试补例 | S |

**总规模：M+**（约 650~800 行，14 个触及文件 + 1 新测试类；4 开发 story + 1 fan-in）。
执行顺序：US-ACH-1（接口冻结）→ US-ACH-2 ∥ US-ACH-3（ACH-3 的 PlayerDataStore 调用点依赖 ACH-2 签名——先冻结签名即可并行）→ US-ACH-4 → US-ACH-5 → 三 tester → 门禁。与域 4/域 6 的文件锁串行关系见 4.3。

---

*本文件为 Phase 3 规划期只读产出，方案自定、理由随文；接口签名（enum 字段、新 check 方法、数据列）为实现期冻结基准。规划期未修改任何源码/配置。*
