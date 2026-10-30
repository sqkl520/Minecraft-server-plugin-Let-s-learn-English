# 锐评 R2 · 学习科学与教学法

> 2026-10-20 战略锐评 · SLA 研究员视角 · 只读 · agentId a197dd6c896ad228c
> 编排者复核注记：本路三项硬数据指控全部实测属实喵——① NGSL 最高频 30 个功能词（the/a/be/have/do/will/about/them/was/been/people/said/if/can/want/think/time/there/just/see/only/more/than/up/out/its/us/over/such/then）扫库结果 **29 缺 1 存**（仅 our）——"号称照 NGSL 选词却把 NGSL 头部删干净"实锤喵；② `breath/breathe` 同中文释义"呼吸"分 noun/verb 两行入库存证，撞车释义真实存在；③ `AnswerChecker.java:182-199` `buildChoiceOptions` 确实只做"同库随机抽取 + 排除拼写重复"，**无释义/词性/语义距离约束**，撞车词条可同时入题构成双正确答案废题；④ `config.yml:109 intervals: [1,6,24,72,168]` 固定阶梯常量表属实喵。

## 一句话判决

这是一套工程严谨、内容自律、教学法外包的"词汇闪卡 + 倒计时通知"系统——它把 Ebbinghaus 当文案，把 NGSL 当免责声明，把答题量当学习量喵。

## 致命伤 Top 3

1. **"间隔重复"不是间隔重复，是固定阶梯的通知器。** 任何 SRS 的个性化都来自"回忆质量"这一输入，本实现根本没有这个输入喵。
2. **995 词里没有 `the`、`a`、`be`、`have`、`do`、`will`。** 号称照 NGSL/GSL 选词，却把 NGSL 的头 20 名删掉了一大半——覆盖度声明与数据自相矛盾喵。
3. **判分信号被三重污染（同释义歧义选项 + 4 选 1 的 25% 猜测基线 + 零反应时采集）**，而掌握度、复习调度、错题本、成就、周报全部建立在这个信号之上。地基错，全楼歪喵。

## 分条炮击

**① SRS 是硬编码常量表，不是遗忘曲线喵。**
证据：`config.yml:109` `intervals: [1,6,24,72,168]`；`ReviewScheduler.java:33-43` 原样转毫秒；`ReviewScheduler.java:100-121` 调度式 `nextReview = lastReview + intervals[reviewCount]`，索引只有一个 `reviewCount` 计数器（`PlayerDataStore.java:782-792` 仅 time=now / count+1），答错则整条清零回 1h（`:823-833`）。全库无 ease / stability / difficulty / 反应时字段（`schema.sql` word_progress 只 7 列），`Ebbinghaus` 一词只存在于 `ReviewScheduler.java:111` 的注释里喵。
后果：所有人的曲线一模一样，且**它连文档承诺都没做到**——`config.yml:110-112` 写"掌握度达阈值后排除出复习计划"，但 `ReviewScheduler` 全程不读 mastery（唯一消费者是 `PlayerDataStore.java:685`，只影响新词抽取）。更糟：`WordManagerImpl.java:254-266` 遍历 `ConcurrentHashMap` 后 `subList(0,10)` 截断，被截掉的词因无序、无 aging 而在积压时**随机饿死**喵。
正确做法：至少上 SM-2（逐题记 quality 0-5 + ease），理想是 FSRS/DHR；schema 加 `ease/stability/reps/last_latency`；把 mastery 真正接进 due 判定；截断改按 overdue 时长降序喵。

**② 词汇选择：来源可声明、不可验证，且数据自身打脸喵。**
证据：`words/README.md:49-56` 声称依据 NGSL/GSL/2022 课标，但代码层零词频/零等级字段（`Word.java` 无 frequency；全库 grep `CEFR|COCA|BNC|NGSL` 在 java 与 yml 中零命中）。实测 60 个最高频词，**27 个缺失**（`the/a/be/have/do/will/about/them/was/been/people/said/if/can/want/think/time/there/just/see/only/our/more/than/up/out/its/us/over/such/then`）（编排者抽测 30 词仅 our 命中，方向一致）。三档首字母覆盖畸形：`advanced.yml` 329 词只覆盖 14 个字母，c=143、d=125；`intermediate.yml` 只有 11 个首字母——这是 `README.md:96-112` 记录的"按字母序批量追加 + 撞车替换"的生成副产物，不是教学设计喵。
抽样 20 条词目：**释义、IPA、例句、词性四项结构上 995/995 齐全，无硬伤**（这点比多数同人词库强）。但撞车释义触目：`breath/breathe` 同为"呼吸"（编排者复核实锤）、`complain/grumble`"抱怨"、`embrace/hug`"拥抱"、`little/small`"小的"、`clever/smart`"聪明的"、`glance/glimpse`"瞥见"、`concise/succinct`"简明的"、`condemn/denounce`"谴责"喵。
后果：NGSL 前 1000 词约覆盖通用文本 85%+ 频次，其主力是功能词；缺 the/be/have 的 995 词，覆盖率被系统性高估，玩家"学完 995 词"仍写不出一个合法句子喵。
正确做法：把选词从文档声明变成数据字段（`freq_rank` + `cefr` + `ngsl_sublist`），按 rank 排序而非字母序生成；补齐功能词并单独做"语法词词库"喵。

**③ 题型：只有中英映射，输出式产出为零喵。**
证据：三模式 spelling/choice/fill_blank（`AnswerChecker.java:24-26`），本质同一件事——词的形式↔中文对译。默认模式是 choice（`WordModes.java:86-95` 优先返回 choice），`option-count: 4`（`config.yml:137`），题面只有中文（`WordLearningListener.java:122-167`；`zh.yml:465-466` "请拼写/请选择: {chinese}"）。干扰项**同词库纯随机抽**（`AnswerChecker.java:181-190` → `WordBank.getRandomWords:186-194`，无词性/语义/长度约束，编排者已复核实锤），于是上面那些撞车词条直接构成"两个正确选项"的废题喵。fill_blank 只是把目标词从自家例句里挖掉（`AnswerChecker.java:245-260`），且同时仍给中文词义与首字母提示（`WordLearningListener.java:141-153`），提示过量、几乎无推断需求。无听写、无语境语法填空、无连词成句、无写作评分喵。
后果：违反 retrieval practice（最低检索强度的识别型选择题被设成新手默认），且 25% 猜测基线**从未被校正**，直接喂给 `mastery += 0.2`（`PlayerDataStore.java:739-741`）与 `isMastered()=正确率≥0.8`（`LearningSession.java:202-205`）——用猜对率拟合掌握度是测噪声。Swain 的可理解输出在词库路径完全缺席；唯一的真实产出是 AI 自由写作，而它与词库彻底解耦（`core/ai/` 下 grep `WordBank|getMasteredWords|seedReview|addDifficultWord` **零命中**）。Krashen 的 i+1 也不存在：难度由玩家手选（`LearnCommand.java:214`），选题是"排除已掌握后 shuffle"（`WordBank.java:135-177`），无基于正确率的自动升降喵。
正确做法：把题型按检索强度阶梯化（新词 recognition → 复习 recall → 语境 cloze → 产出），默认走拼写而非选择；干扰项加"同 POS + 排除同释义 + 词形相近"三重约束；引入按 `accuracy×latency` 的动态难度路由喵。

**④ 中文学习者难点：不是没设计，是词库结构上无法承载喵。**
证据：全库 grep `冠词|可数|不可数|时态|介词搭配|中式英语|grammar` 零命中（唯一命中是 `ai_scenarios.yml:28` 的一句 prompt）。无 `plural / past_tense / comparative / collocation / [U]` 任何形态或搭配字段。`advice`（intermediate）标 `noun`、例句 "gave me good advice"，却没有不可数标记与 `*an advice` 负面示例，而同义词 `suggestion` 是可数的——正是中国学生必错处，代码只把它当一条翻译对。`breath/breathe` 这对名词/动词撞车本是绝佳词性辨析材料，实现里却当两个孤立词条随机丢进选择题喵。
后果：MLC 的核心难点（冠词、主谓一致、时态、介词搭配、可数性）在系统里没有任何表征位置；玩家练得再久，也在练"翻译对"而非"能用"喵。
正确做法：词条加 `collocations / grammar_note / l1_warning / word_forms` 四字段，先给 100 个高风险词补上；出"语法辨析选择题"子题型专吃这类对喵。

**⑤ AI 对话：即时纠错无协议，延迟纠错不闭环，fossilization 有温床喵。**
证据：即时侧只有软措辞——`AIChatManager.java:552-588` 拼装的 system prompt 里纠错只有一句 "About correcting mistakes: follow the difficulty instructions above"（`:587`），而难度指令是 `ai_scenarios.yml:15-20` easy"Be very patient and encouraging with mistakes / Praise the player"、`:33-39` hard"let minor errors slide"，无结构化 recast/prompt 标记，因此下游无法统计。延迟侧是本项目最像教学法的一块：会话结束独立请求总结，强制 JSON `{comment, corrections[original/corrected/note], words}`（`AIChatManager.java:1256-1295`），解析带硬上限与永不抛异常（`ConversationFeedback.java:29-38`），聊天栏显示 original→corrected 但**丢弃 note**（`ChatFeedbackManager.java:178-192`，note 只在 `ChatHistoryGUI.java:572-582` 可见）。然而纠错结果止步于 `ai_chat_feedback.payload` 供回看（`ChatFeedbackEntry.java`），**既不注入错题本、也不进复习队列、更不作为下一场对话目标**；AI 推荐的"接下来可学的 3 个词"只 `String.join` 打印一行（`ChatFeedbackManager.java:193-197`）喵。
后果：场景全是服务性脚本（值机/点餐/入住/就医，`ai_scenarios.yml:42-95`），AI 作为 waiter 会自动简化并脑补意图，学生只需传递 gist；配合无 recast 设计、无最低句长门槛（`max-input-length: 200` 允许 "ok"，`config.yml:193`）、"有效发言≥3 即判达标并发奖励成就"（`ChatFeedbackManager.java:74-98`，`count-min-rounds: 3`），这套反馈回路奖励的是"聊过"，不是"聊准"。意义被对方兜底 + 错误无强制重述 = 石化教科书条件喵。
正确做法：prompt 里加显式 recast 协议（先隐性重述正确形式、必要时再元语言说明，区分即时/延迟的触发条件：可理解性中断优先）；把 corrections 的 original 词形落进错题本、words 落进下一场会话的目标词喵。

**⑥ 发音：整句朗读确实是摆设，而且是"假按钮"喵。**
证据：`TtsManager.java:19-28` 自己写明"由于 Minecraft 不能原生播放自定义音频，发音播放用一个 NOTE_BLOCK 音效作为指示"；`playPronunciationCue:210-220` 播 `BLOCK_NOTE_BLOCK_PLING`；下载的 .wav 只落盘缓存，注释是 "for potential future resource-pack integration"（`:27`），**任何路径都不播放**。玩家点"发音"→ 一声叮 + 聊天栏打印 IPA（`WordLearningListener.java:201-242`，`zh.yml:40`）。无最小对立对、无音素训练（grep `minimal|phoneme` 零命中）喵。
后果：/θ ð/、/l r/、/v w/、/æ ʌ/、词尾 -s/-ed 这些中文母语者核心音段难点零设计；对读不出 IPA 的学习者，音标显示等于噪声，而"叮"声建立的是"我听了发音"的错误自我认知喵。
正确做法：走官方 resource pack + `/playsound` 播真实音频（技术上可行）；加 20 组最小对立对（think/sink、light/right、very/wear）专练；把 IPA 与音频绑定，无音频时明说"仅显示音标"喵。

**⑦ 测量：完全没有"学习"的证据，只有"玩过"的证据喵。**
证据：grep `experiment|ab.test|baseline|pre.test|post.test` 只在 `MessageUtil.java:146` 命中一条无关注释。现有全部指标是投入量：`schema.sql` 的 `total_words_learned/total_sessions/total_score/conversation_count/current_streak/longest_streak`，`Achievement.java:257-258` 的 ProgressSource 枚举（TOTAL_CORRECT/TOTAL_SESSIONS/COINS_EARNED/CONVERSATIONS/DAILY_STREAK/REVIEW_STREAK/ACCURACY_PCT/STUDY_DAYS/WRONG_BOOK_CLEARED），`ReportGenerator.java:1095-1098` accuracy=correct/answered。无逐题时间戳（`WordLearningManager.java:432-452` 只有倒计时；`LearningSession.java:124-126` 是会话级耗时）；成就阈值 25/100/500（`Achievement.java:45-64`）是**累计答对题数**，不是掌握词数喵。
后果：无法回答"这套系统让人多记住了几个词"。连"复习完成率 vs 长期保持"这条免费自然实验都没结构化。任何教学法改进都无法被证伪喵。
正确做法：加一个不刷奖励的独立"留存探测"（每 N 天抽若干旧词做跨期 free recall，记 latency 与置信度），配 pre/post 词表测与按加入时间分层的保持曲线；把"答对数"与"延迟正确率"在报告里并列喵。

## 说句公道话

三处真有判断力喵。**其一，动机设计修对了**：`config.yml:299-305` 复习答对加成 1.2×、复习答错不倒扣（Phase 2 FIX-NUM-01 把 0.5/true 主动反转，注释直书"旧默认等于惩罚复习，没人愿意点复习"）——把惩罚从学习回路里摘出去，这是对自我决定论的直觉。**其二，复习模式恒定回落拼写判定**（`AnswerChecker.java:102-103` + `WordManagerImpl.java:175-186`），检索强度远高于新学的选择题，"难者先练"的排序是对的。**其三，内容工程有真纪律**：995 词条结构齐全、`WordBankValidator` + `gen-words.js` 自检（含 emit/parse 往返、音标包裹、例句含头词）、同义词做了档位一致性审查并删掉 10 个超纲词、三档跨档零重复 headword（实测 `uniq -d` 为空）。AI 总结的 JSON 契约 + 硬上限 + 永不抛异常降级，是在同级插件里少见的健壮实现。此外每日提醒同日去重（`ReviewScheduler.java:24-25,59-66`）、教程迷你会话答错原地重试不显示答案（`WordLearningManager.java:380-397`）、错题本"3 次进本 + 练对递减移出"（`:396-403`，`DatabaseManager.java:1331`），都是朴素但正确的低焦虑设计喵。**它不是伪科学包装，它是没来得及做科学的工程。**

## 只改一件事

**修"答对"这个信号本身**——在 `AnswerChecker.buildChoiceOptions`（181-190）里加三重约束：剔除与答案 `chinese` 释义相同的候选、要求同 `part_of_speech`、词形长度相近；并对撞车词条直接拒绝入题喵。理由是最小改动撬最大因果：这套判分二值信号是 mastery（`PlayerDataStore.java:739-741`）、复习晋级（`ReviewScheduler.java:110-116`）、错题本（`WordLearningManager.java:396`）、22 个成就与周报的唯一上游。现状是"两个正确答案、其中一个被判错、玩家靠 25% 概率猜对却被记成掌握"——在这种噪声上做 FSRS、做前后测、做任何教学优化，都只是把垃圾算得更精确。**先把题目变成一次有效测量，其余改造才有可评估的基线**喵。
