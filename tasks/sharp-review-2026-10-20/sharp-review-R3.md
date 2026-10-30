# 锐评 R3 · 游戏化与激励设计

> 2026-10-20 战略锐评 · 游戏设计顾问视角 · 只读 · agentId abf9c37dfcf115840
> 编排者核验注记：本条"GrowthCalculator 不存在 / getElapsedSeconds 生产代码零调用 / 全项目唯一 withdrawPlayer"三条关键引用 grep 复核属实喵。

## 1. 一句话判决

这是一台**做工精良的答题机，外面裹了一层没有下一集的糖衣**：金币有产无销、奖励挂"做对"而非"学会"、难度永不适应、终点只有数字喵。

## 2. 金币流水表（产 vs 销）

| 方向 | 位置 | 数值 |
|---|---|---|
| 产 | `RewardEngine.java:313` 答对一题 | 10 × combo（最高 3.0）=30 |
| 产 | `RewardEngine.java:360` 会话完成 | 50（+连击≥5 再 25）|
| 产 | `RewardEngine.java:407` 复习答对 | 10 × 1.2 × combo |
| 产 | `RewardEngine.java:499` 中途停会话 | 按答题比例折算 50 |
| 产 | `CheckInManager.java:29` 签到 | 10/20/30/50/70/85/100 **7 天循环** |
| 产 | `MiscGUIListener.java:371` 行为挑战 | 30/50/25/40（`config.yml:529`）|
| 产 | `DailyChallengeManager.java:590` 单词挑战 | 100 |
| 产 | `SeasonManager.java:463` 赛季 | top1 5000 / top3 2000 / top10 500 |
| **销** | **`RewardEngine.java:459/470` 复习答错扣 5** | **全项目唯一 `withdrawPlayer`，且 `review-penalty: false` 默认关** |

结论：**默认出厂配置下，金币净产出为 0、净消耗也为 0（`money.enabled: false`，`config.yml:288`）喵。** 全库 grep `shop|商店|购买|兑换|unlock.*bank` 零命中，没有任何 sink。玩家一天可产 `money.daily-cap` 500，30 天 15000，纯通胀喵。金币走 Vault 公共钱包（`RewardEngine.java:292`），若服上有别的经济插件，钱能花——但那不是本插件的闭环，而是"在 MC 里学英语可以刷服务器商店物资"的越界耦合喵 [待验证：`total_coins_earned` 是 BIGINT（`DatabaseManager.java:1472`）而发放额含 .5 小数]。

## 3. 致命伤 Top3

**① 循环不闭合——死胡同而非飞轮。** 唯一"变强"路径是掌握度：`PlayerDataStore.java:735`（答对 +0.2，阈值 0.85）→ `WordBank.java:160` 优先出未掌握词。但词库只有 317/349/329 = 995 词，10 词/会话约 100 会话打光；打光后 `WordBank.java:165-172` 从已掌握池洗牌补齐，题目变成"你早就会的词"喵。学习 → 拿金币 → 金币无处可花 → 内容不变化 → 地位不变化。没有等级（**`GrowthCalculator` 这个类在代码库里根本不存在**，grep 全零命中）、没有 XP 曲线、没有段位、没有毕业认证。

**② 奖励与"做了题"挂钩，不与"学会了"挂钩，且刷分几乎无成本。** `WordLearningManager.java:243-272`：判对即发钱，不看难度、**不看反应时间**——`LearningSession.getElapsedSeconds()`（`LearningSession.java:124`）生产代码零调用（仅测试自用）喵。choice 干扰项由 `AnswerChecker.java:181-186` 的 `bank.getRandomWords()` **从同词库纯随机抽取**，不取同义词/同类词，语义距离随机，蒙题只需识字首字母；`CHOICE_OPTION_MIN = 2`（`AnswerChecker.java:29`）甚至允许二选一 = 50% 蒙对率。最露骨的是教程：`WordLearningManager.java:154-157` 注释自陈"choice 4 选 1 必中……结构保证"，即**用出题机制保证成就必解锁**，测评效度为游戏化确定性让路喵。过度辩护风险满格：一个真想学英语的人，被"答对 → 叮一声 +15 金币 + 升调音效"反复训练，注意力会从"这个词我会不会"迁移到"这题能不能快点了结"。

**③ 惩罚只有断签一记闷棍，且毫无补救。** `CheckInManager.java:86-90` 断签直接归 1，`zh.yml:268` 只说"旧连续签到已归零"——**没有 streak-freeze、没有补签、没有"花金币买回连胜"**。讽刺的是：金币无处可花，而买回连胜正是最天然的 sink 设计，作者把两边都错过了喵。更糟的是 `CheckInManager.java:93` 的奖励是 7 天取模循环，连胜 30 天和连胜 8 天的日均收益完全相同——**惩罚的力度来自仪式感而非经济，而仪式感又被循环削平了**。

## 4. 分条炮击

**错题代价被设计反了。** `WordLearningManager.java:303` 答错 → `LearningSession.java:86` 的 `recordIncorrect()` 直接 `advance()`，不重试；当场把答案打在聊天框（`ChatAnswerListener.java:271` `{answer}`），然后 `ReviewScheduler.java:110` 排在 **1 小时**后再考。1 小时后你当然还记得刚才屏幕上那行字——**间隔重复退化成了背答案**，测试效应被反向利用喵。复习答错再 `resetReview` 弹回 1h（`ReviewScheduler.java:152`）+ 扣 0.1 mastery。错题本更要错满 3 次才收录（`WordLearningManager.java:396`）。

**combo 专治慢思考者。** `WordLearningManager.java:244` 只累计"连续答对"，答错归零（`:289`），**超时同样归零**（`:348`），而 `answer-timeout: 30`（`config.yml`）。一个正在心里拼 `necessary` 的人被判错、清连击、扣 2 分、进错题本——三重重罚，而 2 秒乱按 A 的人拿 3× 倍率喵。缓解只有 choice 一个独立 `time-limit`，默认还跟随全局。

**心流通道不存在。** 难度只有 3 档手选词库，永不解锁、永不推荐（grep `recommend|adaptive|unlockDifficulty` 全零）；**词库里逐词标注的 `difficulty_level`（beginner.yml 里就有 1/2/3）在出题与判分逻辑中零消费**——只被 `AdminCommandHandler.java:292` 导出成 JSON，是纯粹的死数据喵。会话内顺序 `Collections.shuffle`（`WordBank.java:162`），没有由易入难的坡度。掌握门槛 0.85（连对约 5 次）意味着"越不熟的词越反复砸你"，"刚会一点的词"这一最爽的心流带被压得极窄。

**成就是 22 块绿色玻璃板。** 枚举实测 22 个（`Achievement.java:36-242`）。垃圾计数型占多数：`WORDS_25/100/500`、`SESSIONS_10/50`、`COINS_500/5000`、`AI_CHAT_10/50`——典型"点了 100 次"成就。**`COINS_500/5000` 尤其荒诞：拿"攒够无处可花的金币"当荣誉**喵。展示价值 = 0：`AchievementWallGUI.java:102-106` 全墙只用一个 `GREEN_STAINED_GLASS_PANE` 和一个 `GRAY_STAINED_GLASS_PANE`，22 个成就长得一模一样，类注释里说的"icon 由 GUI 层决定"从未落地喵。没有稀有度、没有称号、没有 chat tag、没有 100% 完成奖励、没有赛季限定（time-limit 默认 false）。成就**不发任何奖励**——`achievements-config.yml` 只有 `enabled` 与 `time-limit` 两个键。

**决斗是"问卷软件对战模式"。** `DuelRoom.java:106-110`：答错直接送对手 1 分；赢家只解锁 `DUEL_FIRST_WIN`（`DuelManager.java:742`），**没有金币、没有积分、没有战绩档案、零持久化**（`config.yml` duel 注释自陈"决斗零持久化"）。两个人各盯一个箱子界面、往同一个聊天框抢打字——这不需要 Minecraft 喵。

**Minecraft 被彻底浪费了。** 13 个 listener 全是 Inventory GUI / AsyncPlayerChat / PlayerJoin；grep `teleport|Block|redstone|Villager|ArmorStand` 在生产代码里只有 `OnboardingManager.java:276` 扔出一本成书（WRITTEN_BOOK）和对决的 BossBar/Scoreboard。没有建筑、没有探索、没有红石、没有 NPC 对话发生在世界里、没有协作建造。词汇塔的每一层、需要正确拼写才能通过的传送门、用刚学句型向村民问路——一个都没有。这就是把 Anki 塞进了 MC 聊天框喵。

**没有终点。** 无周目、无毕业、无证书、无 prestige。`PlayerProgress.java:11-18` 只有五个数字：totalWordsLearned / totalSessions / totalScore / currentStreak / longestStreak。赛季默认关（`config.yml:674`），赛季奖励又是金币（又无处花）。金币榜还是排行榜的**第一个 tab**（`LeaderboardGUI.java:137`），而 `money.enabled: false` 时它是一张恒为 0 的空榜——首屏就演了一出不存在的经济喵。

## 5. 说句公道话

这项目的**工程诚实度远超它的游戏化设计**喵。`RewardEngine` 的四道闸门（`checkDailyCaps` `:116`、`clampToDailyCap` `:233`、答题数门 + 金币日额、"部分发放"而非整笔吞掉）是真做过防刷经济的；`meetsPrerequisites`（`AchievementManager.java:309-336`）逐条写明了反刷理由——"每局答 1 题速退 ×50""纯签到囤币""纯挂机经济流"，**作者完全清楚外在奖励会被滥用**，只是没把这层聪明用在金币发放上。`FIX-NUM-01` 把复习倍率从 0.5 反转成 1.2、把复习扣分默认关掉（`config.yml` 注释写着"旧默认 true，试运行反馈复习越学越穷"）——**这是真的在做激励设计而不是堆数值**，负反馈被主动削掉了喵。22 个成就里有 4 个是设计过的：`WRONG_BOOK_CLEARED`（要求真清空错题本）、`ACCURACY_90`（质量而非数量）、`PERFECT_SESSION`、`HELPFUL`（鼓励 10 个他人）。`H2 比例门`（`RewardEngine.java:509-526`）拒绝 rage-quit 骗会话奖。签到先落库再付款、成就通知按玩家语言逐收件人渲染，都说明这些"游戏化"是被当真做的。而且 **`ReportGenerator` + 班级系统**才是这个项目真正的教育价值所在——它绕开了游戏化，直接服务老师和家长喵。

## 6. 只改一件事

**把金币发放点从"答对那一题"移到"间隔复习成功取回那一题"。** 具体：`WordLearningManager.java:267-272` 答对不再即时到账，改为写入一个 pending 台账（机制现成——`DailyChallengeManager.java:683` 的 M12 pending-payout 已是同一套）；答错时**不打印 `{answer}`**（`ChatAnswerListener.java:271`），改为首字母/例句降级提示并允许原地重试，把答案留给失败的最后一次；真正发币的唯一凭证是 `ReviewScheduler` 判定"隔了 N 小时后仍取回成功"喵。

这一刀同时砍中四处：**过度辩护**（奖励终于与"学会了"挂钩，与"做了题"脱钩，蒙对 30 秒内选 A 一分钱拿不到）；**背答案漏洞**（1 小时后再考不再等于送钱，测试效应回到正轨）；**循环闭合**（pending 余额天然成为玩家"看得见但还没拿到"的目标，而"提前解锁某笔 pending"或"花金币买回断签"就是那个缺失的 sink）；**心流**（发币延后意味着 combo 不再是现金乘数，慢思考者不再被 30 秒倒计时抢走钱包，`COMBO_MULTIPLIERS` 可以还原成纯粹的表现反馈喵）。

工程量集中在一个文件的一条路径上，不新增表、不动词库、不改 GUI。剩下的 21 块绿色玻璃板，等这一刀落地之后再谈喵。
