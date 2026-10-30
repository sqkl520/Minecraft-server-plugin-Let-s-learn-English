# 锐评 R4 · UX 与交互设计

> 2026-10-20 战略锐评 · 互动/教育产品可用性研究员视角 · 只读 · agentId aac054473c58cec8f
> 编排者复核注记：三处关键指控实测成立——① `WordLearningGUI.java:56` `inv.clear()` 后全程无 `fillEmpty`（27 格只放约 7 个物品），`WordLearningListener.java:83-86` 下半区 return 不 cancel 且**无** DuelGUIListener 那套 `MOVE_TO_OTHER_INVENTORY` 拦截（拖拽有 T0-01 guard，shift+双击整理没有）——"物品飞进上半区空槽后取不回"路径成立，关闭容器时物品丢弃还是销毁标 [待验证]（实机）喵；② `cancel` 确在 `intermediate.yml` 词条、`goodbye` 确在 `beginner.yml`——"正确答案就是退出指令"冲突属实喵；③ choice 选项确实只渲染在 slot 13 书 lore，点击侧只认 4 个功能槽喵。

## 1. 一句话判决

代码工程质量与文案打磨远超一般 Minecraft 插件，但它把"英语学习"这件最需要低摩擦的事，绑在了 MC 最差的输入通道（聊天框打字）和最容易被误操作的通道（背包 GUI 空槽）上——**核心学习环的手感是灾难，外围（成就/日历/广场）是精品**喵。

## 2. 致命伤 Top 3

**① 答题输入窗口是隐式的，窗口外打字会污染公屏。**
只有 `isAnswering == true` 时聊天才被吞掉判分（`listener/ChatAnswerListener.java:171-183`）。倒计时仅在**关掉界面那一刻**才启动（`listener/WordLearningListener.java:49-71` → `core/word/WordLearningManager.java:432-455`）。于是新手的自然动作——界面还开着就在聊天里打字——既不计分，又把答案广播给全服（`ChatAnswerListener.java:183` 未 cancel，正常广播）。这是**泄题 + 判无效 + 社交尴尬**三合一，而且没有任何提示告诉你"界面开着的时候打字是没用的"喵。

**② 选择题的选项画在 GUI 里，却点不动。**
`gui/WordLearningGUI.java:166-190` 把 A/B/C/D 渲染成 slot 13 那本书的 lore；而点击侧只认 4 个槽（pron/back/stop/start，`listener/WordLearningListener.java:172-199`）。玩家的直觉是点选项 B——结果是**完全静默的无响应**（上半区被 cancel，无音无字）。于是唯一的作答方式是关界面、去聊天框敲一个字母（`messages/zh.yml:466`）。对手机玩家/Geyser 玩家，这是把核心玩法锁死：一个字母要经历"点聊天按钮 → 唤起软键盘 → 切英文输入法 → 敲键 → 发送"。答对了还给连击倍率，答错了 −2 分并计入错题本（`WordLearningManager.java:30-31, 289-293`）喵。

**③ duel 的空槽漏洞收口了，主循环没收口。**
对决侧做了双保险：铺满 27 格（`gui/DuelGUI.java:151-157`）+ 显式拦 `MOVE_TO_OTHER_INVENTORY`（`listener/DuelGUIListener.java:41-50`）。但**单词学习 GUI 27 格只放 7 个物品**（`WordLearningGUI.java:55-127`，`inv.clear()` 后无 `fillEmpty`），点击侧对下半区直接 `return` 不 cancel（`WordLearningListener.java:83-93`）——开着答题界面 shift+点背包，物品会飞进上半区空格，而上半区任何点击都被 cancel，**取不回来**，物品就此卡在一个即弃的自定义容器里（[待验证]：Paper 关闭时是丢弃还是销毁）。`ClassBoardGUI.java:104-152`（54 格最多只有几个头像）与 `ClassBoardListener` 同型，`ProfileGUI.java:207-212` 成就按钮被模块门隐藏时也在 slot 18 留空。`GUIUtils.fillEmpty` 已经写好且 8 个界面在用，就是漏了玩家停留时间最长的两个喵。

## 3. 分条炮击

**首次 60 秒**：流程本身合格——进服 2 秒发 3 行欢迎 + 引导书（`listener/PlayerJoinListener.java:47-59`、`core/onboarding/OnboardingManager.java:103-131`），**1 条命令 + 2 次点击**就能进迷你会话（`MainMenu.java:109` → `WordBankSelectGUI.java:56-63` → `OnboardingManager.java:169-198`）。但发现性全靠聊天里那 3 行：全项目**没有任何 clickEvent/run_command/hoverEvent**（grep 0 命中），`messages/zh.yml:220` 的"输入 /le"是不可点的纯文本；欢迎语只在 `!hasPlayedBefore()` 时发一次（`PlayerJoinListener.java:47`），错过即永久错过，且没有快捷栏物品/告示牌/服务端标题兜底。手机玩家打一个斜杠命令的成本是桌面的 3 倍，而你要求他打的第一个命令就是入口喵。

**教程挡不挡路**：教程只有 3 步、答错原地重试不判错（`WordLearningManager.java:344-352`）、1-2 态每日最多一条提醒（`OnboardingManager.java:134-146`），设计得相当克制。但：step==1 时**首次 `/le word beginner spelling` 会被劫持成强制 choice**（`OnboardingManager.java:169-190`），显式表达过模式的玩家被无声改写；引导书在每次进服静默补发（`OnboardingManager.java:145`），扔了会回来；完成时**从背包直接删除物品**（`OnboardingManager.java:383-401`），无公告；且玩家侧**没有跳过通道**——`onboarding.enabled` 是服务器开关（`config.yml:549-562`），`/le guide` 只能查看进度（`LearnCommand.java:152-155`），老手重开小号只能硬吃喵。

**倒计时与超时**：30 秒（`config.yml:99-102`），超时与答错同罚：清连击、−2 分、计入错题本（`WordLearningManager.java:313-380`、`trackWrongAnswer` 3 次入书）。这对拼写 13 字母的词（词库里有 `conscientious`/`differentiate`）在触屏上是惩罚而非提醒。倒计时**只活在 ActionBar**（`WordLearningManager.java:480-502`，全项目 `sendActionBar` 仅 2 个调用点），没有 BossBar——而决斗却有（`core/duel/DuelManager.java:666`，`config.yml:344`），同一条时间压力在两个模式给了两种可见性，主模式反而是弱的那个。最后 3 秒的 tick 音是好补位，但静音玩家听不到。判分只有精确整词比较、无 NFKC 归一（`AnswerChecker.java:155-160`）：**全角 `Ａ`、表意空格、首字母大小写外的一个错字母，统统判错**，且提示只说"✖ 错误！答案是"（`zh.yml:482`），不说"你输入的东西因为格式没被识别"。中文输入法玩家最痛的这一点，代码里一行都没防喵。

**聊天框作为社交通道**：决斗被彻底劫持——决斗分支在 `EventPriority.LOWEST` 无条件 cancel + `getRecipients().clear()`（`ChatAnswerListener.java:128-136`），**决斗中无法说任何一句话**，只在开始文案和 lore 里警告过一次（`zh.yml:345`）。AI 对话反而留了后门（`& ` 前缀走公屏，`listener/ChatScenarioListener.java:30-31,127-140`）。两个模式两种契约，是典型的设计漂移喵。错题练习用 `cancel` 当退出词（`ChatAnswerListener.java:153`）而 `cancel` 本身是 `words/intermediate.yml:380` 的教学词条——练到这个词时，**正确答案就是取消指令**（[待验证]实机行为）；AI 退出的 `goodbye`/`end` 同样是 `words/beginner.yml:1041` 的词条（编排者已复核两词条在库）。每答一题 GUI 会自动弹回并再刷一行"请在聊天中输入…"（`ChatAnswerListener.java:277-280`），10 题会话 = 10 次无请求的界面弹出 + 约 40 行聊天。答对广播默认关（`data/PlayerDataStore.java:957`，隐私优先，好评），但一旦开了就是逐题刷屏喵。

**GUI 层级与交互**：主菜单 9 按钮塞在一页 27 格（`MainMenu.java:27-32`），语义图标有可取之处（书/纸/书架/剑），也有迷惑项：`RED_BED` = 切换语言、`DIAMOND` = 个人主页、`SUNFLOWER` = 签到、`COMPARATOR` = 隐私设置。分页是最大的未完成项：词库第 8 个起只给"更多词库…"，**点了是个纯 no-op**（`MiscGUIListener.java:227-230`），文案自己承认"翻页功能即将上线"（`zh.yml:534`）；AI 场景同型（`ScenarioSelectGUI.java:88-97`）。返回栈 `core/GuiNavigationManager.java` 是后加的，只有部分路径压帧，`WordLearningListener.java:180-188` 的返回键在答题中**渲染成可用按钮却拒绝执行**并播失败音——一个看起来能点、点了被骂的控件。误触保护方面：`SocialListener.java:733` 明确拒绝未定义点击类型（赞），`WrongBookListener.java:102` 用 shift 区分"练习/删除"，而**左键删除无二次确认**（`WrongBookListener.java:136-152`，直接落库删词，而个人场景删除却要"再次点击确认"，`zh.yml:287`）——同一项目两种不可逆操作的确认标准不一致。界面路由仍靠 `title.contains(...)` 子串（`WordLearningListener.java:52`、`SocialListener.java:143-150` 一条链上 12 个 contains），新代码已在往等值比对收敛（`ConfirmationGUI.java:48-75`、`MiscGUIListener.java:106-125`），旧的可标题碰撞面还在喵。

**文案抽样（zh/en 各约 20 条）**：
- **术语三套**：同一个机制在 `zh.yml:472-473` 叫"连胜"、`zh.yml:480-481` 叫"连击"、里程碑里叫 `COMBO`；en 侧同屏 `Streak` 与 `combo`（`en.yml:472-481`）。会话/回合/合并三种叫法并存（`zh.yml:474-475` vs `453,462`）喵。
- **en.yml 残留中文 7 行**：`en.yml:35` 发音按钮是 `"发音 / Pronounce"`、`en.yml:155-158,164` 榜单标题 `金币榜 / Coins`——英文界面给英文玩家看中文，且顺序还反了一处（`Season / 赛季榜`）喵。
- **猫娘语气渗进产品**：`zh.yml` 有 99 处"喵"，包括错误提示（`zh.yml:186,487,220`）。给小学生的娱乐文案可以，但同一份文件里教师/家长通道是严肃纯文本（`zh.yml:700+`），语气在两条通道间断裂喵。
- **错误提示质量普遍高**（这是真优点）：几乎每条都带下一步（`zh.yml:486`、`zh.yml:104`、`zh.yml:42`）。但两处破功：`word.invalid-mode` 把内部标识符 `spelling / choice / fill_blank` 直接甩给初学者（`zh.yml:50`），明明有本地化键 `zh.yml:55-58` 不用；`general.error` 会把 `IllegalArgumentException` 的英文原文包装进玩家消息（`LearnCommand.java:278-280` → `WordManagerImpl.java:126` "Unknown difficulty level:"）。缺键时给玩家的占位是 `[?key]`（`MessageUtil.java:173-179`）；`ProfileGUI.java:88-90` 独自用 `getString(...,"")`，缺键渲染成空名字喵。

**反馈闭环**：音效层做得很细（连击升调、里程碑铃、tick 递进，`ChatAnswerListener.java:228-238` + `WordLearningManager.java:638-680`），成就有 Title（`AchievementManager.java:284`）。但答对/答错**零视觉冲击**：全项目 `spawnParticle` 0 处、`sendTitle` 0 处（仅成就用），答对只是一行灰字。进度可视化则是亮点：GitHub 风学习热力图带文字图例与数值 lore（`LearningCalendarGUI.java:212-268`）、主页统计卡、成就墙、周/月/赛季榜、每日挑战——"我学到什么了"在**会话之外**回答得很好，在**会话之中**几乎是空白（分数只在 GUI lore 里，界面一关就没）喵。

**可访问性**：红绿二元在决斗计分上是 `GREEN_WOOL` vs `RED_WOOL`（`DuelGUI.java:170,188`），红绿色盲玩家靠位置区分，勉强及格；隐私开关与日历都用"颜色 + 文字状态行"双编码（`ProfileGUI.java:265-280`、`LearningCalendarGUI.java:237-268`），这点做得专业。但紧急态靠红/黄文字区分（`zh.yml:477-478`）；文案大量依赖 `⏱ ⏳ ⚔ 🎓 ✦ ❤ ①②③` 等非 MC 默认字形（`zh.yml` 内 40+ 处），在无 CJK/emoji 字体的客户端与基岩版上会渲染成方框（[待验证]）喵。**关于手机/无键盘玩家，代码与文档 0 处提及**（grep Bedrock/Geyser/mobile 全空），而核心玩法恰恰强依赖物理键盘喵。

**通知骚扰**：`daily-reminder` 默认开、每天 3 个整点全员广播 + 进服一条（`config.yml:493-505`、`LetsLearnEnglish.java:433-490`），与进服提醒共享当日去重（好）。叠加复习提醒（`ReviewScheduler.java:56-66`，每日一次，好）、引导未完成提醒、鼓励摘要、赛季补发——老玩家一次进服最多多出 4-5 行。**这些全无玩家侧开关**：`ProfileSettings` 只有音效开关（`ProfileGUI.java:305-317`、`schema.sql:82`），消息类提醒只有服务器级 config。教育产品把"提醒"做成不可拒的公屏喇叭，是最容易养成玩家屏蔽插件的路径喵。

## 4. 说句公道话

- 隐私优先是真的：答对广播默认关（`PlayerDataStore.java:957`），他人主页隐藏数值时保留标签行（`ProfileGUI.java:117-135`），隐身玩家在广场被替换为 `???` 且文案有梗（`zh.yml:503-504`）喵。
- 死亡保护（`WordLearningManager.java:531-549`，停表不判错、重生续答）与决斗 ESC 后用 `/le duel gui` 找回（`R-23`），都是被真实投诉打磨出来的细节喵。
- 倒计时从聊天栏迁到 ActionBar（`3A-1`）、缺键不再 NPE 而是 `[?key]`、异常全部 i18n 化——这套止血痕迹说明作者在**认真收自己旧版的债**喵。
- 教程的"答错不推进、原地重试、不显示答案"（`WordLearningManager.java:344-352`）是教育心理学的正确做法，比大多数"错了就罚金币"的同类插件体面喵。
- 三模式（拼写/选择/填空）+ 挖空例句保留原标点 + 单字母词与选项字母相撞时优先判全词（`AnswerChecker.java:96-105`）——判分收敛点写得干净，`fill_blank` 的例句挖空是这类插件少见的认真设计喵。

## 5. 只改一件事

**把选择题改成点 GUI 槽位作答**：给 `WordLearningGUI` 的 choice 渲染加 4 个真按钮（A/B/C/D 各占一格，点击即提交并进入下一题），保留聊天输入作为可选通道。

这一改动同时解掉四个致命症状：不再需要"关界面 → 打字 → 界面弹回来"的节拍器循环；手机/Geyser 玩家从 5 步输入法流程降到 1 次点击；打字慢/IME/全角字符导致的误判被绕过；"选项看得见点不动"的反直觉彻底消失。顺带把 `WordLearningGUI` 与 `ClassBoardGUI` 的 20-45 个空槽用 `GUIUtils.fillEmpty` 填上，shift 点背包丢物品的洞一并堵死——那是同一个界面里、同一次改动半径内就能收掉的第二个致命伤喵。
