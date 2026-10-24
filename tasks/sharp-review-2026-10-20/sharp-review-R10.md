# 锐评 R10 · 社交与多人系统

> 2026-10-20 战略锐评 · 社交产品增长顾问视角 · 只读 · agentId a3dedaf67d55bb8f2
> 编排者复核注记：本路曾空结果、经催办凭既有侦察续跑交卷（0 新工具调用），故全部硬指控经编排者独立抽查实锤喵——① 全仓无 friend/follow/blacklist 任何实现（grep 仅命中无关词）；② `SocialManager.java:138-156` 动态类型确实只有 SESSION_DONE/ACHIEVEMENT/CHECKIN 三种系统事件，零玩家输入；③ `config.yml:161` 自陈"决斗零持久化，无数据面"；④ `DuelManager.java` grep coin/reward/deposit 零命中（赢家只拿 `DUEL_FIRST_WIN` 成就）；⑤ 聊天消息无 run_command/hoverEvent 可点击组件（23 处 ClickEvent 命中全为 InventoryClickEvent，与 R4 指控互证无矛盾）喵。

## 1. 一句话判决

这不是社交插件，是**单人学习插件外面套了一层"围观别人学习"的玻璃壳**喵——广场里没有一句话是玩家写的，决斗打完什么都不留下，好友/关注/私聊/举报在库里连表都没有，它的"社交"= 系统自动播报 + 一个点赞按钮喵。

## 2. 致命伤 Top3

**① 决斗是彻底的装饰品：零持久化 + 零奖励 + 无对手即孤岛。** 唯一奖励是 `DUEL_FIRST_WIN` 一个成就（`core/achievement/Achievement.java:217-224`），赢家无金币无胜率无战绩；`resources/config.yml:161` 自陈"决斗零持久化，无数据面"，`DuelManager.shutdown()`（`core/duel/DuelManager.java:1150-1193`）关服即蒸发；无人可挑战时只有一句"现在没有其他在线玩家可以挑战喵~"（`gui/DuelChallengeGUI.java:145-151`）——而 `AIChatManager` 有 1500 行 LLM 能力却不愿造个 bot 对手喵。

**② 匹配漏斗第一环就漏光，且出题结构性的欺负弱者。** 接受邀请必须手打命令（`DuelManager.java:192-201` + `messages/zh.yml:309`），全仓无可点击消息组件（编排者复核实锤），60 秒静默过期（`:212-228`），且**没有拒绝通道**——最礼貌的处理是沉默。`getRandomWord()`（`:1336-1354`）随机抽难度、随机抽词，完全无视双方词库与掌握度；答错直接给对方送分（`core/duel/DuelRoom.java:104-112`），5 分即终局（`:15`）。低水平者每一次猜测都在替对手上分，形成"越被虐越不敢接"的负反馈喵。

**③ 广场不是 UGC，榜单不是公平榜。** 动态只有 SESSION_DONE/ACHIEVEMENT/CHECKIN 三种系统事件（`core/social/SocialManager.java:138-156`），文案由 i18n 模板拼装（`core/social/FeedTextFormatter.java:59-104`），**零玩家输入**；每人每天每类 UPSERT 合并一行（`data/DatabaseManager.java:334-351`）+ 默认仅存 7 天（`config.yml:651`）→ 小服广场永远 15 格且第 8 天开始清空，热闹不起来。榜单全为历史累计（`DatabaseManager.java:855-926`）、只有 10 个头像位（`gui/LeaderboardGUI.java:46-52`）、零分段零新人榜；`daily-limit` 只砍金币不砍分（`core/progress/RewardEngine.java:119-124` vs `WordLearningManager.java:249-251`），choice 模式 A/B 瞎蒙同得 `BASE_SCORE=10` 与连击倍率（`:30-33`）——挂机刷榜不但可行，还比认真拼写省力喵。

## 3. 分条炮击

- **决斗匹配漏斗。** 断言：五步每步掉人——只列在线玩家的 42 格箱子（`gui/DuelChallengeGUI.java:98-172`，超 42 截断为前 41）、手打 accept、约 1-3 分钟 5 分短局、退服 30 秒宽限读秒（`DuelManager.java:38`、`:811-892`）、quit 直接判对方赢（`:779-804`）。后果：一次掉线同时毁两人心情且无复仇/战绩钩子。修法：接受改可点击消息 + AI 影子对手 + `duel_stats` 落库进周榜。置信度高喵。
- **异步对决（打同一题榜）。** 断言：**完全没有**，决斗纯内存。后果：社交拉新拉留存两条路径全断。修法：`social_feed` 增 `DUEL_DONE` 事件复用现有 UPSERT 合并。置信度高喵。
- **广场 UGC 性质/审核/垃圾/冷启动。** 断言：是"学习行为广播"不是 UGC；因此无敏感词、无举报、无屏蔽（全仓无相关代码，22 张表里只有 `social_feed`/`social_cheers`），防刷靠频率限制（`:330-333` 750ms 防抖）与结构聚合（`:349` UNIQUE）。后果：零风险也零内容，前 100 条只能等玩家学习掉落。修法：加"例句打卡"轻 UGC（≤50 字符 + 模板 + 复用 `save_scenario` 的 op 门先例，`LearnCommand.java:1023`）。置信度高喵。
- **好友/关注系统实际用途。** 断言：**不存在**，搜 friend/follow/blacklist 只命中无关文本（编排者复核确认）。能互看主页/日历/成就墙（`SocialListener.java:414-508`、`gui/ProfileGUI.java:38-235`），但无关系边、无"提醒朋友打卡"回路。后果：不是"两张没用的表"，是"零张表 + 一个孤零零的赞"。修法：先做"关注=动态流过滤视图"这一张表，cheer 后推约战入口。置信度高喵。
- **cheer 的价值密度。** 断言：唯一真实互动，但不可自赞（`SocialManager.java:318-322`）、一人一次、点赞后什么也不会发生，仅累计换 `HELPFUL`（`AchievementManager.java:459-476`）。后果：社交被做成"只读 + 一个布尔值"。修法：cheer 附带一句预设短语（可举报）并触发私信/约战。置信度高喵。
- **排行榜公平性与刷榜。** 断言：见致命伤③；`total_coins_earned`/`longest_streak` 累计口径让新人永远在榜外。后果：初学者看不见自己 = 榜单零激励。修法：按掌握词数分档（`word_progress` 现成）+ 周榜默认落点 + 蒙对题降权。置信度高喵。
- **社交压力/沉默螺旋/骚扰。** 断言：广场不收发言所以无发帖羞耻，代价是全服没有任何公开英语表达；唯一公屏播报只报"谁答对得几分"不含答案（`ChatAnswerListener.java:62-88`，设计正确）。真实表达全在 1v1 AI 对话，且切回公屏要说 `& ` 前缀（`ChatScenarioListener.java:26-28`）构成隐性门槛。骚扰面：cheer spam 有防抖，无屏蔽/拉黑。后果：低水平者只是被围观对象。修法：加 block（一张 `social_block` 表 + 读取侧 SQL 过滤，接现成三层守卫 `SocialListener.java:427-483`）。置信度中高喵。
- **孤独还是社交，选边了吗。** 断言：彻底选了孤独——四类每日挑战全是个人 KPI（`core/DailyChallengeManager.java:40-46`）；班级是唯一组织形态却默认关（`config.yml:706`）且"不做玩家自建/邀请/审批"，班级榜点成员头像**静默忽略**（`listener/ClassBoardListener.java:60`）。后果：一整个班的人同屏却不能互相说一句话或约一局。修法：班级目标共担任务 + 头像点开→约战/鼓励。置信度高喵。
- **5 活跃玩家兜底。** 断言：学习侧极强（签到/复习/引导/错题/AI 单人闭环），社交侧等于零，且动态流默认还是首页（`config.yml:660-662`），新手第一眼是近乎空白页 + "广场还很安静~"（`zh.yml:505`）。修法：官方预制种子动态 + AI 对手。置信度高喵。
- **跨服可能性。** 断言：广播通道已通（`core/ProxyMessenger.java:77-113`），赛季台账已做多服同库抢占（`DatabaseManager.java:375-399`），共享 MySQL 时动态流天然聚合（`SocialManager.java:401` 自陈）；但决斗被 `DuelRoom` 这个内存 record 判死刑。后果：跨服围观可以、跨服对战不可。修法：决斗改 DB 房间表 + 代理 plugin message 转发答案。置信度中喵。

## 4. 说句公道话

它做对了很多教育产品做不到的事：**没有制造垃圾**——不收发言就没有审核负担、没有小号刷屏、没有联系方式泛滥；结构聚合根除刷屏，7 天自动清理保证不长脏历史（`SocialManager.java:216-227`）。隐私设计看得出真懂未成年人场景：隐身者进"对方正在乱扔隐身喷溅药水"笑话页（`gui/EnglishSquareGUI.java:475-509`、`zh.yml:502`）比"用户不存在"幽默且诚实；`showResults` 降级文案、日历二次守卫、超时不送分保持对称（`DuelRoom.java:129-144`）、GM 解散不判胜负并给离线方补发通知（`DuelManager.java:976-1048`、`PlayerJoinListener.java:75-87`）都很讲究喵。工程防御性（per-viewer 快照、标题解析不猜 null、写库全异步、拖拽 guard、quit 清 map）**远超同类插件**——而这正是病灶：**80% 精力花在"点 A 不会作用到 B"，没有一分花在"人为什么要再来一次"**喵。

## 5. 只改一件事

**给决斗加 AI 影子对手，把 1v1 从同步 PvP 改成异步挑战。** 先修出题公平（`DuelManager.java:1336-1354` 改为取双方词库难度交集、优先未掌握词），再用现成 `AIChatManager` 扮演对手（按其"真实掌握度"设错误率与响应延迟，同规可 quit、可复仇），战绩落 `duel_stats(uuid,win,lose,opponent_uuid,day)` 并进周榜/赛季榜，动态流新增 `DUEL_DONE` 事件。这一步同时补三个洞：无对手时从"空箱子"变成"随时能打一局"；决斗从零持久化装饰变成有战绩、有复仇、有排名后果的留存钩子；广场冷启动不再依赖别人今天有没有学习——"我赢了 Teacher-Bot 三局"就是别人会点赞的第一条内容喵。
