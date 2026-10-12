# Phase 3 规划：新手引导线（域 1）

- 日期： 2026-10-13
- 基线： 1.0.7-SNAPSHOT（P2 批次 commit `716d4fd` 之后）
- 来源： `tasks/plan-roadmap-usable-to-delightful.md` 第 8 节方向清单第 1 条；`tasks/p2-polish-execution-plan.md` 第五节域 1
- 规划纪律： 本轮为只读规划，未修改任何源码/配置；规划期发现见第 8 节
- 规模估计： **M**（净新增约 450~600 行，13 个触及文件 + 1 个新类 + 1 个新列），**4 个 story**（3 开发 + 1 编排者 fan-in）

---

## 1. 操作逻辑

### 1.1 方案总述：三步引导线 = 拦截式迷你会话 + 手册（WRITTEN_BOOK）+ 渐进式完成登记

沿路线图骨架（选库 → 3 词迷你会话 → 领成就）做四处关键改造（与路线图原案不同的取舍，理由注明）：

1. **"领成就"改为"看成就"**：不新增引导专属成就、不发引导奖励币（避免与成就重构域冲突、避免经济面）。迷你会话走完时 FIRST_WORD / PERFECT_SESSION / STREAK_3 会由**既有成就管线自然触发**（见 1.6），玩家已经"拿到"成就；第 3 步的动作是**打开个人主页查看**，把"被奖励"转成"被引导去发现"，同时也让玩家看到"已学单词 3"——个人主页从第一分钟起就不是空的。
2. **迷你会话 = 全程复用既有答题管线**（`WordLearningManager.startQuiz` 家族 + `ChatAnswerListener`），只加两个开关：会话词数 3、答错**原地重试**（不判错 / 不扣分 / 不进错题本 / 不推进题号）。重试机制保证 3 题必然全对（choice 只有 4 个选项，上限 4 次必中），从而"引导结束必有成就"是**确定性**而非概率事件——这是原案最大的漏洞（choice 4 选 1 三连对概率仅 ≈42%，见 1.6 决策项）。
3. **不做独立引导 GUI**：手册本身即交互界面（右键翻开、页内动态渲染进度）。避免新 GUI 引发 GUIUtils 标题白名单 / 拖拽 guard / 音效单源三套扩张（P2-01/P2-24 教训）。
4. **进度持久化到 `player_data` 新列 `onboarding_step`**（理由与备选对比见 3.3）。

### 1.2 状态机与数据模型

```
onboarding_step（player_data 新列，INT，默认 0）:
  0 = 未初始化（仅"老号回填前"的瞬时态；全新玩家首进即写 1）
  1 = 引导中（等首次答题 → 迷你会话进行中，二者共用一个值，见 1.5 说明）
  2 = 迷你会话已完成（等打开个人主页）
  3 = 引导完成（终态，之后零打扰）
```

```
            首进(hasPlayedBefore=false)               首次答题(任意入口)
  ┌────┐  ─────────────────────▶ ┌────┐  ─────────────────────▶ ┌───────────┐
  │ 0  │  （写 1 + 发手册+intro） │ 1  │   拦截 → 3 词迷你会话    │ 引导会话中 │
  └────┘                         └────┘ ◀──────────────────────  └───────────┘
     ▲ 老号首进（hasPlayedBefore=true）       会话完成(showSessionComplete, tutorial=true)
     └──────────── 回填写 3（静默） ─────────────▶ ┌────┐  打开本人 ProfileGUI   ┌────┐
                                                  │ 2  │ ────────────────────▶ │ 3  │
                                                  └────┘  （收回手册+祝贺文案）  └────┘
   断线/中途退出：step 停在 1（会话按 R-59 照常结算）；重进→当日一条提醒+补书；再答题重新进入迷你会话
   跳步：不存在（1→2 只能由引导会话完成触发；2→3 才能由主页触发；step 写单调递增，不回退）
```

**每步的触发与完成判定**：

| 步 | 触发 | 完成判定 | 完成动作 |
|----|------|----------|----------|
| 第 1 步 选词库 | 玩家任意入口发起首次答题：`WordBankSelectGUI` 点击（`MiscGUIListener.handleWordBankSelect`）或 `/le word <难度> [模式]`（`LearnCommand.startWordQuiz`） | = 拦截成功即"选库"成立（与第 2 步共用一个 step 值，见 1.5）。不做独立的"已选库未开会话"中间值——该状态观察不到差异，加值只会加状态 | 引导迷你会话开局（choice 模式强制）+ `onboarding.session-start` 文案 + 打开答题 GUI |
| 第 2 步 3 词迷你会话 | 第 1 步拦截时自动开局 | `showSessionComplete` 被调用且 `AnswerResult.tutorial==true`（自然走完 3 题；stop/quit/超时都不会触发它） | step 写 2 + `onboarding.step2-done` 文案（晚于成就广播发出，见 1.6） |
| 第 3 步 查看成就 | 玩家打开**本人** `ProfileGUI`（`/le progress`、`/le stats`、主菜单个人主页按钮、广场自己头像等全部路径共用同一入口类） | `ProfileGUI.open` 且 `viewer==target` 且当时 step==2 | step 写 3 + `onboarding.complete` 文案 + complete 音效 +（默认）收回手册 |

**边角情形**（验收全覆盖，见第 7 节）：

- **提前开主页**：step==1 时开主页不计数（no-op），迷你会话完成后需再打开一次——顺序刚性，无法跳步。
- **断线/中途退出**：`endQuiz` 走既有 R-59 结算（会话/分数照记，不发走完奖励）；step 保持 1。重进：当日首次一条 `onboarding.reminder` + 手册补发（若丢失）；再发起答题重新进入迷你会话（词重新抽，从 0 起，不续答）。
- **引导会话中 stop/返回**：`stopQuiz` 不触发 `showSessionComplete` → step 不动，行为同上；不拦"停止"按钮（玩家自主权）。
- **`/lea reset`**：删行 → step 归 0；重进时 `hasPlayedBefore=true` → 回填 3（与 R-45 注释的哲学一致：reset 不代表是新玩家）；背包里残留的标记书右键显示"毕业页"或被静默清除。
- **旧号回填**：更新后首进的老玩家（step==0 且有游玩史）→ 静默写 3，零打扰。回填失败（写库异常）→ fail-open：拦截条件要求 step **恰为 1**，老玩家不会被误拦（宁可漏引导，不误伤老号）。
- **开关中途关闭**：`onboarding.enabled=false` 后新答题不再拦截、不再提醒、手册不补发；进行中的引导会话允许自然打完（step 照写，状态真相优先）；手册留在背包变普通可读书（无害）。

### 1.3 重复触发防护清单

| 面 | 防护 |
|----|------|
| 拦截重复 | 条件 `step == 1`（缓存权威值，写库失败也保持内存 1，同一会话内行为一致）；step≥2 后永久不再拦截 |
| step 写重复/回退 | 统一走 `advanceStep(uuid, to)`：`to <= 当前` 时不写（单调），杜绝 quit/rejoin 竞态与重复回调把进度写回退 |
| 完成 hook 重复 | `showSessionComplete` 的引导 hook 只在 `result.tutorial==true` 且 step==1 时写 2；重复调用天然幂等 |
| 手册重复发放 | 发放前扫描主背包 + 副手（`getContents()` + `getItemInOffHand()`）找 PDC 标记，已持有则跳过；`/le guide` 同一套检查 |
| 提醒刷屏 | `Map<UUID, LocalDate>` 每日去重（照抄 `joinReminderSent` 懒判定模式，quit 不清、按日期判）；quit 时清 step 缓存与提醒 map 防泄漏 |
| 右键手册 | 仅对 step∈{1,2}（及 keep-book 下的 3）生效；`openBook` 幂等；重建物品对象再打开（页随语言刷新，见 3.6） |

### 1.4 引导书方案（WRITTEN_BOOK）

- **给什么**：`Material.WRITTEN_BOOK` + `BookMeta`（title=`onboarding.book.title`，author=`onboarding.book.author`，3 页：封面 / 动态进度页 / 答题小贴士页）。物品加 PDC 标记：`new NamespacedKey(plugin, "onboarding_book")` = Byte 1（照 `ChatHistoryGUI.sessionIdKey` 的静态访问器模式，监听器与发放点共用同一常量）。
- **何时给**：① 首进（`handleJoin`，与欢迎语同一 40L 延迟任务内，先复查 `isOnline`——R-45/L-20 模式）；② 未完成玩家进服时发现背包无标记书 → 静默补发；③ `/le guide` 手动补发（带 `onboarding.guide-given` 确认）。
- **PDC 标记而非内存标记**（推荐）：物品可被丢、被拿、被放进箱子、跨重启——内存标记会失去追踪；PDC 随物品 NBT 走（丢弃拾回/箱子取出都保留），且"扫描背包找标记"就是"是否已持有"的判定，无状态需要维护。发放前查 PDC 即去重。
- **被丢弃/合成掉怎么办**：WRITTEN_BOOK 无原版合成用途（不会被"合成掉"）；丢弃允许（不拦 drop，少打扰），丢失后的补发链路 = 重进静默补发 + `/le guide` 即刻补发 + 手册末页写明"/le guide 可重新领取"。创新模式复制（中键）产生的副本只是普通视觉道具，无任何授予面，无害。
- **交互**：`PlayerInteractEvent`（主手、右键、非潜行时对"点到了方块"的情况 `setCancelled(true)` 防误开箱子）；处理时**重建**一个当前语言的新标记书塞回手位再 `player.openBook(newItem)`——页与题头每次翻开都按当前 step 与当前语言现渲染（语言切换即时生效）。完成态（step==3 且 keep-book）翻开显示"毕业页"。
- **收回**：step 2→3 完成时默认收回（配置 `remove-book-on-complete`，默认 true；false = 留作纪念，翻页显示毕业页）。收回时静默从背包移除标记物品（主背包+副手扫一遍）。

### 1.5 与既有系统的衔接

- **首次进服检查条件**：`Player#hasPlayedBefore()`（与 R-45 欢迎语同源，PlayerJoinListener:46 已有先例）。**不用**查 `player_data` 行判新——数据行可被 `/lea reset` 删除但那不代表新玩家（R-45 注释既定哲学）。老号与新号的区分完全由"首进写 1 / 老号回填 3"两个动作落地，之后一切判断只看 step。
- **选库与迷你会话共用一个 step 值（论证）**：第 1 步唯一的完成事件（拦截开局）与第 2 步的开始是同一瞬间，独立存储 1→"已选库" 只是不可观测的中间态；手册与 `/le guide` 的进度页把 1/2 两步合并为一条"完成 3 词迷你会话"任务行展示，信息不丢。
- **每日挑战**：迷你会话计分的 3 次答对照常走 `submitAnswer` 内既有 hooks（`ANSWER_10` 进度、词库挑战词勾选、`SESSION_3`），零额外接线；重试的错答不计数（不调 `incrementTodayAnswerCount`，与"非法选项字母"不结算的口径一致）。
- **成就**：不做专属成就、不改 `AchievementManager`。会话走完由既有 `checkSessionAchievements`（FIRST_WORD / PERFECT_SESSION）与 `checkStreakAchievement`（STREAK_3）自然触发；引导 hook 放在 `showSessionComplete` 的**末尾**（成就广播先出、引导 toast 后出，叙事顺序正确）。注意 FIRST_WORD 现判定过严（见 PF-ONB-01），本设计通过"重试不判错 ⇒ 3/3"使其**确定性触发**，绕开了概率问题；即使 P3-3 未来重构该判定，第 3 步只要求"打开主页"，叙事不依赖任何具体成就。
- **ChatAnswerListener 答题流**：主体零重构。新增判定全部收敛在 `AnswerResult` 的 `tutorial` 标志上：错答分支 `result.tutorial ? onboarding.retry-wrong : word-learning-gui.incorrect`（音效保持 incorrect 不变）；随后既有 `else { new WordLearningGUI(...).openWithMessage }` 重开 GUI 的循环原样复用——玩家关界面 → 计时 → 同题重答。超时路径在 `startAnswerTimer` 超时分支同样分流 `onboarding.retry-timeout`（不显示答案）。答对路径（连击/发币/广播/成就）全部保持原样。
- **互斥矩阵**：拦截点放在各入口既有的 `chat-active` / `finish-duel-first` 守卫**之后**，与决斗/AI 对话互斥语义天然一致；迷你会话进行中，四态互斥（决斗/AI/练习/答题）照既有规则工作。
- **每日提醒**：见 D2 决策项（建议首进当天抑制 join 提醒，避免首屏 5 行文案堆叠）。

### 1.6 已裁决的设计决策（默认执行，供合流评审）

| # | 决策 | 默认 | 备选 |
|---|------|------|------|
| D1 | 迷你会话答错语义 | **原地重试**：不清连击、不扣分、不入错题本、不进复习种子、不推进题号、不显示答案；超时同待遇。哲学对齐 R-13 死亡保护（"非正常中断不判错"） | 正常判错（问题：4 选 1 下"引导必得 FIRST_WORD"只剩 ≈42% 概率，首胜体验靠运气——否决） |
| D2 | 首进当天是否发 join 每日提醒 | **抑制**（`sendJoinDailyReminderIfEnabled` 加 `hasPlayedBefore()` 前置判断；欢迎语三行已覆盖"去 /le menu"的教学作用） | 照发（首屏 = 3 欢迎 + 1 提醒 + 1 引导 intro = 5 行，噪音偏大） |
| D3 | 迷你会话是否允许同时触发 PERFECT_SESSION / STREAK_3 | **允许**（引导 3 分钟内连爆 3 个早期成就 = 留存正反馈；三者均为门槛最低档）。`sessionIncorrect` 恒 0 是"重试不判错"的必然后果，属接受的设计代价 | 在 tutorial 会话屏蔽 PERFECT_SESSION 判定（需在 AnswerResult 流上再加特判，违反"成就域只由 P3-3 动"的分界） |
| D4 | 难度选择 | 玩家在拦截入口自由选择（GUI 点哪个库就是哪个库；命令带难度尊重难度）；仅强制 mode=choice | 强制 beginner（否决：违背"选库"步的自主性，且高难度玩家被降档会困惑） |
| D5 | 引导奖励 | **不发钱不发物**：正反馈只来自成就广播 + 会话结算 + 手册收回仪式感 | 发 50 金币（经济面新增一次性发放点，收益低、评审面大——否决） |

---

## 2. 用户体验

### 2.1 玩家视角：第 1 分钟 → 第 5 分钟

```
[第 0~1 分钟 · 进服]
  聊天栏（全部 40L 延迟后连发）:
    [英语学习] 欢迎 XX 来到一起学英语！            ← 既有 welcome-1
    [英语学习] 在这里玩游戏、做挑战就能学英语~       ← 既有 welcome-2
    [英语学习] 输入 /le 打开主菜单开始学习吧！       ← 既有 welcome-3
    [英语学习] 新同学你好呀！我在你的背包里放了一本《新手引导手册》，
              右键翻开看看，跟着做 3 件小事就能上手啦喵~   ← 新 onboarding.join-intro
  背包：第一空位出现紫色封面的成书（有入包动画）        ← giveGuideBook

[第 1~2 分钟 · 翻书 + 进入学习]
  右键手册 → 原版翻书界面:
    封面页: 「新手引导手册 / 跟着我做 3 件小事，5 分钟就能上手：① 选词库 ② 3 词迷你会话 ③ 看成就」
    进度页: 「当前进度」+ 三行 ○/✔ 状态 + 下一步提示行
    小贴士页: 「关闭答题界面后，在聊天栏输入 A/B/C/D；答错可以重来；/le 呼出菜单；/le guide 重新领手册」
  输入 /le → 主菜单 → 点「单词学习」→ 词库选择 → 点「初级」

[第 2~4 分钟 · 迷你会话]
  聊天栏: [英语学习] 🎓 迷你试炼开始！难度 初级，共 3 个单词（选择题）。答错不要紧，再试一次就好喵~
  自动弹出答题 GUI（标题「单词学习 - beginner」）→ 玩家关掉界面
  聊天栏: 「请选择: 苹果 (剩余时间: 30秒，输入 A/B/C/D)」+ 4 行选项（既有渲染）
    · 答对 → 「✔ 正确！(+10)」+ correct 音效（+偶尔连击文案）
    · 答错 → 「没关系，再试一次就好喵~」+ incorrect 音效 → GUI 重开同题（不扣分不推进）
  3 题走完 → 会话完成文案 + complete 音效 + 成就广播连发（FIRST_WORD、PERFECT_SESSION、STREAK_3）
  聊天栏: [英语学习] 引导第 2 步完成！最后一步：输入 /le progress 打开个人主页，看看你刚拿到的成就喵~

[第 4~5 分钟 · 收尾]
  /le progress → 个人主页: 已学单词 3 / 成就 3/16 / 可点成就墙
  聊天栏: [英语学习] ★ 引导全部完成喵！手册已收回，之后的英语之旅就交给你啦~
  complete 音效 + 手册从背包消失（收回动画即物品消失）

[第 6 分钟以后]
  一切安静。没有任何引导残留消息；玩家已是"老玩家"路径（每日提醒/挑战/复习照常）。
```

### 2.2 每步文案与提示音一览

| 触点 | 文案 key | 音效（复用既有 5 键，不新增） |
|------|---------|------------------------------|
| 首进 intro | `onboarding.join-intro` | 无 |
| 手册入包 | —（静默，入包动画本身就是反馈） | 无 |
| 会话开局 | `onboarding.session-start` | 无（开 GUI 自带 menu-open） |
| 答错重试 | `onboarding.retry-wrong` | `incorrect`（既有分支不变） |
| 超时重试 | `onboarding.retry-timeout` | `incorrect`（既有 R-84 补播不变） |
| 第 2 步完成 | `onboarding.step2-done` | 无（会话完成的 `complete` 已播，避免双声——R-86 教训） |
| 未完成提醒 | `onboarding.reminder` | 无 |
| 第 3 步完成 | `onboarding.complete` | `complete` |
| /le guide 补发 | `onboarding.guide-given` | `menu-click` |
| 手册页面 | `onboarding.book.*`（7 键） | — |

### 2.3 防打扰设计（老玩家零感知）

- 老玩家（`hasPlayedBefore=true` 且 step 回填 3）：**无 intro、无手册、无提醒、无拦截、无任何新聊天输出**。可观测行为与 1.0.7 完全一致（唯一差异 = player_data 多一列 + 首次 join 一条后台 UPSERT）。
- 完成引导的玩家：同上。
- 引导中的玩家：每天至多 1 行提醒（当日去重）；手册补发静默；拦截只发生在"本来就要开答题"的时刻——不是额外打扰，只是把第一次答题变得友好。
- 全部出口一个配置总闸（`onboarding.enabled`），服主可一键静默。

### 2.4 i18n key 草案（zh/en，config-version 11→12，若与广场社交同批则串行升 13）

沿用前缀惯例（聊天消息带 `{prefix}`，手册页/标题不带）与"喵"风格（仅 zh 文案萌化，key 名全规范英式小写连字）。

| key | zh（草案） | en（草案） |
|-----|-----------|-----------|
| onboarding.join-intro | `{prefix}&a新同学你好呀！我在你的背包里放了一本 &e《新手引导手册》&a，右键翻开看看，跟着做 3 件小事就能上手啦喵~` | `{prefix}&aWelcome! I put a &eStarter Guide &ain your inventory — right-click it and follow the 3 little steps to get started~` |
| onboarding.reminder | `{prefix}&e你的新手引导还没完成哦~ 右键背包里的引导书看看下一步，或输入 &e/le word &e继续喵！` | `{prefix}&eYour starter guide isn't finished yet~ Right-click the guide book, or type &e/le word &eto continue!` |
| onboarding.session-start | `{prefix}&6🎓 迷你试炼开始！难度 &e{difficulty}&6，共 &e{count} &6个单词（选择题）。答错不要紧，再试一次就好喵~` | `{prefix}&6🎓 Mini trial starts! Difficulty &e{difficulty}&6, &e{count} &6words (multiple choice). Mistakes are fine — just try again!` |
| onboarding.retry-wrong | `{prefix}&e没关系，再试一次就好喵~` | `{prefix}&eNo worries — give it another try~` |
| onboarding.retry-timeout | `{prefix}&e时间到啦，本题再来一次喵~` | `{prefix}&eTime's up — same question, one more try~` |
| onboarding.step2-done | `{prefix}&a引导第 2 步完成！最后一步：输入 &e/le progress &a打开个人主页，看看你刚拿到的成就喵~` | `{prefix}&aStep 2 done! Last one: type &e/le progress &ato open your profile and see your achievements~` |
| onboarding.complete | `{prefix}&6★ &a引导全部完成喵！手册已收回，之后的英语之旅就交给你啦~` | `{prefix}&6★ &aStarter guide complete! The handbook is collected — enjoy the rest of your journey~` |
| onboarding.guide-given | `{prefix}&a已把引导手册放回你的背包喵~` | `{prefix}&aThe starter guide is back in your inventory~` |
| onboarding.guide-done | `{prefix}&7引导已经全部完成啦，不需要手册了喵~` | `{prefix}&7You've already completed the starter guide — no handbook needed~` |
| onboarding.guide-disabled | `{prefix}&7新手引导功能未启用喵~` | `{prefix}&7The starter guide feature is disabled~` |
| onboarding.guide-header | `{prefix}&6===== 新手引导进度 =====` | `{prefix}&6===== Starter Guide Progress =====` |
| onboarding.book.title | `新手引导手册` | `Starter Guide` |
| onboarding.book.author | `一起学英语` | `Let's Learn English` |
| onboarding.book.page-cover | `&1&l新手引导手册\n\n你好呀，我是引导小书喵~\n跟着我做 3 件小事，\n5 分钟就能上手：\n\n① 选一个词库\n② 完成 3 词的小会话\n③ 打开个人主页看成就\n\n&7翻到下一页看进度喵~` | `&1&lStarter Guide\n\nHi! I'm your little guide book~\nDo 3 small things to get started\nin 5 minutes:\n\n1. Pick a word bank\n2. Finish a 3-word mini session\n3. Open your profile\n\n&7Turn the page for progress~` |
| onboarding.book.page-steps-header | `&1&l当前进度\n` | `&1&lProgress\n` |
| onboarding.book.step1-done | `&a✔ 第 1 步 选词库` | `&a✔ Step 1  Pick a word bank` |
| onboarding.book.step1-pending | `&7○ 第 1 步 选词库（/le → 单词学习）` | `&7○ Step 1  Pick a word bank (/le → Word Learning)` |
| onboarding.book.step2-done | `&a✔ 第 2 步 3 词迷你会话` | `&a✔ Step 2  3-word mini session` |
| onboarding.book.step2-pending | `&7○ 第 2 步 完成 3 词迷你会话（/le word）` | `&7○ Step 2  Finish a 3-word mini session (/le word)` |
| onboarding.book.step3-done | `&a✔ 第 3 步 打开个人主页` | `&a✔ Step 3  Open your profile` |
| onboarding.book.step3-pending | `&7○ 第 3 步 打开个人主页（/le progress）` | `&7○ Step 3  Open your profile (/le progress)` |
| onboarding.book.hint-session | `&e下一步：随便打开一个词库，完成 3 个单词的小会话吧喵~` | `&eNext: open any word bank and finish a 3-word mini session~` |
| onboarding.book.hint-profile | `&e下一步：输入 /le progress，看看你的成就喵~` | `&eNext: type /le progress to see your achievements~` |
| onboarding.book.page-tips | `&1&l答题小贴士\n\n&0· 选好词库后会弹出答题界面\n· &l关闭界面&r&0 后在聊天栏输入选项字母\n· 答错可以重来，不要怕喵~\n· 任何时候输入 /le 都能呼出主菜单\n· 输入 /le guide 可重新领取这本小册子` | `&1&lAnswering Tips\n\n&0· A quiz UI pops up after you pick a bank\n· &lClose it&r&0, then type the option letter in chat\n· Mistakes are retryable — don't worry~\n· Type /le anytime for the main menu\n· Type /le guide to get this book again` |
| help.guide（追加/修改 `help` 段） | `&e/le guide &7- 查看新手引导进度 / 重新领取引导手册` | `&e/le guide &7- View starter guide progress / get the guide book` |

落地注意：① `onboarding.session-start` 的 `{difficulty}` 复用既有 `word.difficulty-*` 键本地化（与 F2-13 同口径）；② 手册页文案用 `\n` 手动折行（成书单页约 14 行 × 19 字符，草案已压行数）；③ 落地前先 grep 确认键不存在；两文件同步 + `ConfigManager.MESSAGE_CONFIG_VERSION` 11→12。

---

## 3. 实现要点

### 3.1 新增类：`core/onboarding/OnboardingManager.java`（唯一新类，~280 行）

```java
public class OnboardingManager implements Listener {
    public static final int STEP_UNINITIALIZED = 0;
    public static final int STEP_IN_PROGRESS   = 1;   // 等首次答题 / 迷你会话进行中
    public static final int STEP_SESSION_DONE  = 2;   // 等打开个人主页
    public static final int STEP_COMPLETE      = 3;

    // 构造：自注册 PlayerInteractEvent（照 WordLearningManager 先例，含 getServer()!=null 守卫，
    //       纯 mock 单测可构造）；缓存在 quit 清理；无其他轮询任务。
    public OnboardingManager(LetsLearnEnglish plugin)

    // ==== 生命周期（PlayerJoinListener/Quit 各一行接线）====
    public void handleJoin(Player player)      // 同步读 step（一条小查询）→ 首进写 1+40L 延迟发 intro/发书；
                                               // 老号 step0 回填 3（静默）；1/2 态 → 当日去重提醒 + 补书
    public void unloadPlayer(UUID playerId)    // 清 step 缓存 + 提醒 map（挂 quit 编排点）

    // ==== 答题拦截（两个入口各一行）====
    public boolean interceptQuizStart(Player player, String difficulty)
        // enabled && cachedStep==1 && AI 对话互斥由调用点既有守卫保证 → 
        // wordLearningManager.startTutorialQuiz(player, difficulty, sessionWords) +
        // WordLearningGUI.openWithMessage + onboarding.session-start → true；否则 false

    // ==== 完成登记（WordLearningManager / ProfileGUI 各一行）====
    public void onTutorialSessionComplete(Player player)   // step 1→2（require-profile-step=false 时直达 3 并收书）；发 step2-done
    public void onProfileOpened(Player viewer)             // step==2 时 → 3：收书 + complete 文案 + complete 音效

    // ==== 手册 ====
    public static NamespacedKey bookKey(LetsLearnEnglish plugin)   // "onboarding_book"
    public boolean giveGuideBook(Player player, boolean announce)  // 扫描去重 → 建书 → addItem 失败落脚下；announce 控制 guide-given
    private ItemStack buildGuideBook(Player player)                // title/author/pages 按当前语言+step 现渲染
    @EventHandler public void onInteract(PlayerInteractEvent e)    // 主手右键 + 标记书 + 引导活跃 → 重建书塞回手位 + openBook；点方块且非潜行 → cancel

    // ==== 查询面（命令/单测）====
    public int getStep(UUID playerId)          // 缓存权威；未加载时懒加载（读库）
    public void handleGuideCommand(Player player)  // 输出 guide-header + 三行步骤状态（复用 book.step*-* 键）+ 补书/已完结/未启用分支
    private void advanceStep(UUID playerId, int to)  // 单调：to > 当前才 写缓存 + databaseManager.setOnboardingStep
}
```

线程模型：全部入口均为主线程（join 事件 / GUI 点击 / 交互事件）；DB 读 = 每 join 一条小 SELECT（与既有 `loadPlayerData` / `loadPlayerAchievements` / `loadProfileSettings` 的 join 同步查询同模式，F2-11 欠账不加重——仅 1 条索引查询）；DB 写 = 玩家生命周期内 ≤3 次小 UPSERT；无任务无 Timer。

### 3.2 数据模型：为什么是 `player_data` 新列

| 方案 | 结论 | 理由 |
|------|------|------|
| **`player_data.onboarding_step` INT（选定）** | ✅ | ① 双后端既有 UPSERT 模式直接复用（`setConversationCount`/`adjustCoins` 先例）+ `addColumnIfMissing` 老库零迁移；② `/lea reset` 的 `deletePlayerData` 天然覆盖（删行即重置）；③ 共享 MySQL 多子服跨服天然一致；④ 一处小查询/join，量级与既有 join 查询同级 |
| PDC on player | ❌ | 玩家实体无持久 PDC；物品 PDC 只适合"手册"这一个载体，不适合进度本体 |
| 独立 onboarding.yml | ❌ | 多一个 IO 面、文件级并发、reset 需额外接线；收益为零 |
| `player_profiles` 表 | ❌ | 该表语义是隐私设置，混入进度污染语义（与 `getActivePlayerIds` 等读侧面搅在一起） |
| 纯内存 | ❌ | 首会话跨重启是必然场景（新玩家首日退出重启），进度必丢 |

DDL（两侧）：`DatabaseManager.createTables` 的 `player_data` CREATE 语句加列 + `addColumnIfMissing(tablePrefix + "player_data", "onboarding_step", "INT DEFAULT 0")`；`resources/schema.sql` 同步注释。

`DatabaseManager` 新增两方法（全程持 `connectionLock`，风格对齐 `setConversationCount`）：

```java
public int getOnboardingStep(UUID playerId)        // SELECT onboarding_step FROM ... WHERE uuid=?；无行 → 0
public void setOnboardingStep(UUID playerId, int step)  // INSERT (uuid, onboarding_step, updated_at) ... 
                                                        // ON CONFLICT(uuid)/ON DUPLICATE KEY 绝对值 UPSERT
```

### 3.3 会话管线改造（精确到方法，全部为小改）

| 文件 | 方法 | 改动 |
|------|------|------|
| `core/word/WordManagerImpl.java` | `startSession` | 新增 4 参重载 `startSession(player, difficulty, mode, wordCount)`；既有 3 参版委托（wordCount 取 `words-per-session`）。**行为零变化**，仅提取参数 |
| `core/word/WordLearningManager.java` | `QuizState` | 加 `public volatile boolean isTutorial;`（照 `isReviewMode` 的构造后赋值模式，不动构造器签名） |
| | 新方法 `startTutorialQuiz(Player, String difficulty, int wordCount)` | 照 `startQuiz` 镜像：cancelAnswerTimer → quizStates.remove → `wordManager.startSession(..., "choice", wordCount)` → QuizState + `isTutorial=true`。~20 行 |
| | `submitAnswer` | 错答分支开头加教程守卫：`if (state.isTutorial) { return 不推进的结果(tutorial=true); }`——不清连击 / 不加 -2 分 / 不 recordIncorrect / 不 incrementTodayAnswerCount / 不入错题本 / 不发复习惩罚。~10 行 |
| | `handleTimeout` | 同款守卫（不判错不推进，补播 incorrect 照旧）。~8 行 |
| | `AnswerResult` | 加 `public final boolean tutorial`（构造器加参，两个构造点同步；外部零构造点已核实——grep 全库仅本文件两处 `new AnswerResult`） |
| | `showSessionComplete` | 末尾一行：`if (result.tutorial) plugin.getOnboardingManager().onTutorialSessionComplete(player);` |
| | `startAnswerTimer` 超时分支 | 分流：`timeoutResult.tutorial ? onboarding.retry-timeout : word-learning-gui.timeout`（1 个条件） |
| `listener/ChatAnswerListener.java` | 主线程回调错答分支 | `result.tutorial ? onboarding.retry-wrong : word-learning-gui.incorrect`（音效不变）；其余（重开 GUI 循环 / 答对路径 / 广播）零改动 |
| `listener/MiscGUIListener.java` | `handleWordBankSelect` | `chat-active` 守卫后 + `startQuiz` 前插一行：`if (plugin.getOnboardingManager().interceptQuizStart(player, difficulty)) return;` |
| `command/LearnCommand.java` | `startWordQuiz` | 同款拦截一行（`chat-active` 守卫后、try 块前）；`SUBCOMMANDS` 加 `"guide"`；`case "guide"` → `onboardingManager.handleGuideCommand(player)`；`sendHelpMessage` 加 `help.guide` 行 |
| `gui/ProfileGUI.java` | `open` | isOwn 时一行：`plugin.getOnboardingManager().onProfileOpened(viewer);` |
| `listener/PlayerJoinListener.java` | `onPlayerJoin` | 欢迎语块之后一行：`plugin.getOnboardingManager().handleJoin(joining);`；D2 决策：`sendJoinDailyReminderIfEnabled` 调用加 `hasPlayedBefore()` 前置 |
| | `onPlayerQuit` | 既有 quit 编排点加一行：`plugin.getOnboardingManager().unloadPlayer(playerId);` |
| `LetsLearnEnglish.java` | 字段/构造/getter | 新字段 `onboardingManager`；构造位置在 `checkInManager` 之后、`registerListeners` 之前（自注册监听，无需改 registerListeners）；加 getter |
| `data/DatabaseManager.java` | 见 3.2 | 列 + 2 方法 |
| `resources/schema.sql` | — | player_data 加 `onboarding_step INT DEFAULT 0` 行 |
| `config/ConfigManager.java`（可选） | `REQUIRED_CONFIG_KEYS` | 追加 `"onboarding.enabled"`（审计面，一行为） |

**路径核实**：迷你会话的"关 GUI → 聊天栏答题 → 重开 GUI"完整链路 = `WordLearningListener.onInventoryClose`（无需改，按 mode=choice 渲染既有提示）→ `ChatAnswerListener.onPlayerChat`（错答分支改一处）→ 重开循环（既有）。GUI 渲染零改动（choice 模式走 `renderChoiceOptions` 既有分支）。

### 3.4 config.yml 新键草案

```yaml
# ============================================================
#  新手引导 (Onboarding)
# ============================================================
onboarding:
  # 是否启用新手引导线（首次 3 步：选词库 → 3 词迷你会话 → 查看成就）
  # 真接线：false 时不给引导书、拦截不生效、无任何提醒（四处门槛同源）
  enabled: true
  # 迷你会话单词数（复用选择题模式；答错原地重试，不判错不推进）
  session-words: 3
  # 是否在首次进服发放《新手引导手册》（WRITTEN_BOOK）
  give-guide-book: true
  # 第 3 步（打开个人主页）是否必需；false = 迷你会话完成即整段完成并发 complete 文案
  require-profile-step: true
  # 引导完成时是否收回手册；false = 留作纪念（翻页显示毕业页）
  remove-book-on-complete: true
  # 未完成引导的玩家进服时是否提醒（同一玩家同一自然日至多一条）
  reminder-on-join: true
```

主 `config-version` 维持 2（不做主配置迁移，注释追加即惯例）；全部键读取点都走 `getBoolean/getInt(key, 默认)` 现读（不缓存 enabled），`/lea reload` 自动生效，HotReloadManager 零改动。

### 3.5 与多语言系统的配合

- 所有聊天文案走 `MessageUtil.getPlayerMessage(player, key, ...)`（per-player 语言）；`{difficulty}` 走 `word.difficulty-*` 键（F2-13 口径）。
- 手册**每次翻开重建**（title/author/pages 全部按当次语言与当次 step 渲染）→ 语言切换后翻开即英文，`ConfigManager` 语言机制零改动。
- 新增 `onboarding.*` 键两文件同步，`MESSAGE_CONFIG_VERSION` 随批 11→12（与广场社交域同批时由编排者串行递增，谁后谁 13）。

### 3.6 开工前 5 分钟核实项（避免踩 API 差异）

1. Paper 1.21.4 的 `BookMeta` 页面 API：优先 `meta.setPages(List<String>)`（若该版本仍在）；若 String 版已废弃/移除，改用 `meta.pages(List<Component>)`（Paper 自带 Adventure，`LegacyComponentSerializer.legacyAmpersand()` 逐行转换）。`setTitle(String)`/`setAuthor(String)` 同理核实。
2. `Player#openBook(ItemStack)` 在该版本可用（1.14+ 原生，预期无问题）。
3. `PlayerInteractEvent#getAction().isRightClick()` 与 `getHand()==EquipmentSlot.HAND` 组合判定（防副手双触发）。

### 3.7 规模与 Story 拆分

| # | Story | 内容 | 规模 | 文件锁 |
|---|-------|------|------|--------|
| US-ONB-1 | 数据层 | DatabaseManager 列 + 2 方法；schema.sql；ConfigManager 审计键（可选） | S | data/DatabaseManager.java、resources/schema.sql、config/ConfigManager.java |
| US-ONB-2 | 引导核心 | OnboardingManager 全量（join/quit/手册/拦截/完成登记/命令面）；LetsLearnEnglish DI；PlayerJoinListener 3 行；回答 ProfileGUI 一行（也可归 3） | M | core/onboarding/*（新）、LetsLearnEnglish.java、listener/PlayerJoinListener.java |
| US-ONB-3 | 会话管线接入 | WordManagerImpl 重载、WordLearningManager（tutorial 全改）、ChatAnswerListener 分支、MiscGUIListener/LearnCommand 拦截 + /le guide、ProfileGUI hook | M | core/word/WordManagerImpl.java、core/word/WordLearningManager.java、listener/ChatAnswerListener.java、listener/MiscGUIListener.java、command/LearnCommand.java、gui/ProfileGUI.java |
| US-ONB-4 | 编排者 fan-in | i18n 23 键双文件 + config 段 + message config-version + CHANGELOG + 单测（OnboardingManager step 转换 ≥3 例，mock plugin 模式照 WordLearningManagerComboTest） | S | messages/zh.yml、messages/en.yml、config.yml、CHANGELOG.md、src/test/** |

**总规模：M**（净新增约 450~600 行，13 个触及文件 + 1 新类 + 1 新列）。
执行顺序：先冻结本文 3.1/3.2 的方法签名与 2.4 键名 → US-ONB-1 ∥ US-ONB-2（接口先冻结可并行）→ US-ONB-3（依赖 2 的 API）→ US-ONB-4 → 三 tester → 门禁。单测可行性：`OnboardingManager` 构造走 mock plugin（`getServer()` 返回 null 跳过注册、`getDatabaseManager()` 返回 mock），step 转换/单调写/拦截条件可直接断言，不需要 MockBukkit。

---

## 4. 依赖与风险

### 4.1 与其它 Phase 3 域的交叉（文件级，供编排者排期）

| 冲突文件 | 本域改动 | 对方域改动（已规划） | 处置建议 |
|----------|---------|---------------------|----------|
| `core/word/WordLearningManager.java` | tutorial 全改（~45 行） | 社交域 `showSessionComplete` 加 recordSessionDone 一行；音频域或将改造倒计时消息 | **三方串行**；本域改动最大，建议本域先落，社交 hook 在其上追加一行 |
| `command/LearnCommand.java` | /le guide + 拦截 | 社交域 square 参数 + checkin 行；菜单收口域 | 与社交域可合并同一流；与菜单域错开行区（拦截点在 startWordQuiz 头部、guide 在 switch 区，冲突面小） |
| `listener/PlayerJoinListener.java` | handleJoin + quit 行 + D2 一行 | 社交域 join 摘要 + quit 行 | 同批时串行给一个流 |
| `listener/MiscGUIListener.java` | 拦截一行 | 菜单收口域可能动其槽位入口 | 拦截行独立、冲突面小 |
| `gui/ProfileGUI.java` | 一行 hook | 菜单收口域（个人场景管理 GUI 等）不预期碰它 | 低 |
| `core/achievement/AchievementManager.java` | **零改动**（刻意） | 成就重构域（P3-3）主场 | 设计已解耦：引导不依赖任何成就判定细节（见 PF-ONB-01 备注） |
| `data/DatabaseManager.java` | 列 + 2 方法 | 社交域 7 方法 + deletePlayerData 扩展 | **必须串行**（同文件重改）；本域 2 方法与社交 7 方法可同一流先后落 |
| `messages/*.yml` / `config.yml` / `CHANGELOG.md` | 23+1 键 / 1 段 / 若干行 | 社交域 22+1 键 / 1 段 | 编排者 fan-in 独占（既定纪律） |

### 4.2 对现有事件流的侵入度（量化）

- 新增 EventHandler 仅 1 个（`PlayerInteractEvent`，只对"引导活跃玩家 + 手上有标记书"提前 return，其余路径一次 `hasActiveOnboarding` 布尔判就退出——对全服每秒交互量无感）。
- 既有热路径（`submitAnswer` / `ChatAnswerListener` 回调）新增分支各 1 个，且都在原有判断树上最短路径外（`isTutorial` volatile 读一字节；非引导玩家零额外行为）。
- `startAnswerTimer` 超时分支 1 个三元式；join/quit 各 ≤3 行。
- 不新增调度任务、不新增 GUI、不新增音效键、不新增权限节点。

### 4.3 性能影响

| 面 | 估算 |
|----|------|
| DB 读 | +1 小索引查询/join（≈`SELECT onboarding_step FROM player_data WHERE uuid=?`） |
| DB 写 | ≤3 次小 UPSERT / 玩家生命周期（首进 1、step2 1、step3 1）+ 老号回填一次性 1 次 |
| 内存 | `Map<UUID,Integer>` step 缓存 + `Map<UUID,LocalDate>` 提醒 map，均随 quit 清理，无泄漏面（F1-24 教训直接规避） |
| 主线程 | join 时一次背包扫描（37 格）+ 手册构建（3 页字符串拼接）——微秒级；其余全在既有答题节奏内 |

### 4.4 最坏情况回滚方案

| 层级 | 动作 | 影响面 |
|------|------|--------|
| 运营快闸 | `onboarding.enabled: false` + `/lea reload` | 立即停止全部新行为（拦截/发书/提醒，四处门槛同源真接线）；在途引导玩家静默解冻（可正常学习）；已发手册变普通可读书（无标记处理即原版阅读） |
| 版本回退 | revert 本批次 commit | 孤儿列 `onboarding_step` 无害：旧代码全部 UPSERT 均显式列名，不 select *、不写该列；PDC 标记书无监听器接收 = 普通成书。无数据破坏面（全程只增不改：新列 + 新文件，不触碰任何既有数据形态） |
| 数据面 | 无需清理 | 无删改既有表/行；`/lea reset` 语义自动覆盖新列 |

### 4.5 风险清单

| # | 风险 | 概率 | 影响 | 缓解 |
|---|------|------|------|------|
| R1 | `WordLearningManager` 重试守卫引入边界 bug（CAS/计时器交互） | 低 | 中 | 守卫位置仅在错答/超时分支 `recordIncorrect` 之前 return，不动 `submitting` CAS 与 `cancelAnswerTimer` 时序；验收用例 3 专测连错/超时循环；`mvn clean verify` 全量含既有回归 |
| R2 | 与社交/成就/菜单域的撞文件 | 中 | 中 | 4.1 串行表 + 冻结接口先行 |
| R3 | BookMeta API 版本差异（String 页移除） | 中 | 低 | 3.6 开工前核实，Adventure 回退方案已备 |
| R4 | 首屏消息堆叠（欢迎×3 + intro + 成就广播×3） | 中 | 低 | D2 抑制首进 daily-reminder；成就广播保留（正反馈>噪音） |
| R5 | 老号误拦（backfill 失败/竞态） | 低 | 中 | 拦截条件要求 step **恰为 1**（首进写 1 成功才可能进入拦截态）——fail-open 结构上排除误伤 |
| R6 | 玩家长时间停留 step 1/2 永不完成后手册常驻 | 低 | 低 | 提醒每日 1 条封顶、不强制；未来可加"N 天自动静默完成"，列入明确不做 |
| R7 | 迷你会话的 3 题是否被玩家用 `/le word` 反复重开 | 低 | 低 | 重开即重入迷你会话（不产生额外收益，3 题计分固定）；step2 后恢复正常 |

---

## 5. 验收标准（人工用例清单 + 目标度量）

**前置条件（环境准备，含一条隐性约束）**：本服 SQLite、`onboarding.enabled: true` 默认配置；"全新玩家"必须用**从未连接过的账号**（`hasPlayedBefore()` 为 false 只在服务器首次见到该账号时成立；`/lea reset` 或删库**无法**复现新玩家路径，需换 UUID/清 usercache——PF-ONB-06）。

**主剧本（新号 5 分钟三步走）**：

| # | 前置 | 操作 | 预期 |
|---|------|------|------|
| 1 | 全新账号 A | 进服 | 40L 后连收 4 行（welcome×3 + join-intro）；主背包出现《新手引导手册》；当日**无** daily-reminder 行（D2）；step=1（DB 可查） |
| 2 | A 重进 | 退出再进 | **无**第二本手册（背包扫描去重）；收到当日引导提醒 **1 条**；再进出不重复提醒（当日去重） |
| 3 | A 在引导中 | `/le` → 单词学习 → 点「初级」 | 不是普通会话：收 `session-start` 引导文案（含难度本地化名"初级"），会话为 3 词、4 选项 choice |
| 4 | 同 3（命令路径） | 改用 `/le word beginner spelling`（另一账号 B） | 同样被拦截进迷你会话（mode 强制 choice，文案有说明）——两条入口全覆盖 |
| 5 | A 迷你会话中 | 第 1 题故意输入错误字母 | 收 `retry-wrong` 文案 + incorrect 音效；**不扣分/不推进题号/不进错题本**（`/le wrong` 为空、分数无 -2）；GUI 重开仍是**同一题** |
| 6 | 同上 | 对同一题连续输错 3 回后第 4 次 | 必中（仅 4 选项，含正确答案那一次）——3 题走完必然全对 |
| 7 | A 走完 3 题 | 观察结算 | 会话完成文案 + 成就广播（FIRST_WORD / PERFECT_SESSION / STREAK_3，D3 默认允许）+ `step2-done` 文案；step=2（DB）；引导会话在 `player_sessions` / 榜 / 日历均有记录（已学单词 3） |
| 8 | step=2 | A 打开**他人**主页（广场点 B 头像） | step **不**变（非本人主页不计数） |
| 9 | step=2 | A 执行 `/le progress` | `complete` 文案 + complete 音效；手册从背包消失；step=3（DB） |
| 10 | step=3 | A 再开主页 / 再进出服 / 再学习 | **零**引导输出；答题为普通 10 词会话（不拦截）；老玩家路径完全恢复 |
| 11 | 中断恢复（账号 C） | 迷你会话答 1 题后直接退服 | 会话按 R-59 结算；step 保持 1；重进 → 当日提醒 1 条；再发起答题重新进入 3 词迷你会话（从 0 起） |
| 12 | 手册丢失（账号 D，中途） | 把手册丢进岩浆/交给别人/放箱子；然后 `/le guide` | 收 `guide-given` + 新手册（此时箱子里的旧书仍在——已知可接受的小重复面）；再 `/le guide` 不再重复发 |
| 13 | 满背包（账号 E 首进前塞满） | 首进 | 手册落脚下（dropItemNaturally）+ intro 文案照发；拾起后功能正常 |
| 14 | 老号（账号 F，更新前已游玩） | 进服 | 零新增输出、零手册、零拦截（答题行为与旧版一致）；DB 回填 step=3 |
| 15 | 开关（账号 G） | `onboarding.enabled: false` + `/lea reload` 后首进 | 无手册无 intro；答题不拦截；`/le guide` 回 `guide-disabled`；重新开启后 G 因 hasPlayedBefore 已为 true 且 step 回填 3 → 不补发引导（文档化语义） |
| 16 | reset（账号 H 引导中） | `/lea reset H` → H 重进 → 答题 | 回填 step=3、零引导、答题为普通会话；残留手册右键显示毕业页/被静默清除（二选一实现，取一断言） |
| 17 | 混服语言（账号 I 用 en） | 全流程（intro/手册页/retry/step2-done/complete 抽查 5 处） | 全英文；语言在引导中切换后重开手册 → 页与题头变新语言 |
| 18 | 并发卫生 | A 引导中执行 `/lea reload` | 引导不中断、step 不丢（已即时落库）；reload 后功能正常 |

**目标度量（Phase 3 出口标准"新玩家首轮完成率"对齐）**：

- **5 分钟度量**：全新账号从进服到 step==3，人工秒表 ≤5 分钟（正常操作：1 次 /le + 3 次点击 + 3-6 次聊天输入 + 1 次 /le progress）。
- **完成率度量**：`SELECT onboarding_step, COUNT(*) FROM lle_player_data GROUP BY onboarding_step`——步 1 与步 2 之和为漏斗中段；正式出口标准仍按路线图"人工抽测 ≥70%"执行（老号回填的 step=3 会稀释该口径，故不把 SQL 比率直接当 KPI，仅作趋势观察；若要精确口径需先接线 `created_at` 写入——PF-ONB-03）。
- **自动**：`mvn clean verify` 全绿（含 OnboardingManager 单测 ≥3 例：step 单调写 / 拦截条件（非 1 不拦）/ 完成登记幂等）；既有 26 测试零回归。

---

## 6. 明确不做（v1 范围外）

1. **第 4+ 步扩展**（AI 对话引导、个人场景引导）——三步封顶，防引导变成作业。
2. **独立引导 GUI / 强制自动打开界面**——不抢玩家屏幕；入口靠提示与手册（也避免 GUIUtils 白名单/音效单源/拖拽 guard 三处扩张）。
3. **引导奖励（金币/物品/专属成就）**——经济面与成就域双避让（见 D5）；正反馈 = 成就广播 + 结算 + 收书仪式感。
4. **手册 title/author 的管理员自定义配置**——i18n 键即真相源，避免又一组 config/i18n 双真相源（F0-09 教训）。
5. **step 2 长期停留的自动静默完成**——保留为未来选项（当前：每日 1 条提醒封顶，不骚扰）。
6. **首进开场演出（Title/自定义音效）**——留给音频反馈域（域 7），本域只用既有 5 个音效键。
7. **求助式引导（玩家提问触发教程）**——对话理解成本高，与社交域明确不做面一致。

---

## 7. 规划期发现（只记录，未修复）

| # | 位置 | 现象 | 等级 | 建议 |
|---|------|------|------|------|
| PF-ONB-01 | `core/achievement/AchievementManager.java:223-225` | FIRST_WORD 实际判定为「会话结束时 `sessionCorrect >= 1 && sessionIncorrect == 0`」= 需要一场**零失误**会话（≥1 答对）才解锁，与成就描述「答对第 1 个单词」不符；新玩家首会话错一题就与首胜成就无缘（首会话 10 题全对概率很低），与新手留存目标相悖。P2 已发现 SPELLING_MASTER 同类过严并收紧过一处，此处属遗留 | 中 | 随 P3-3 成就重构一并裁决（倾向改为「累计答对 ≥1」）；本域设计已解耦（重试机制使其确定性触发，但不依赖具体判定） |
| PF-ONB-02 | `core/progress/Achievement.java` | 41 行**死类**（全库零引用，仅自身定义；与实用类 `core.achievement.Achievement` 同名，构成导入歧义陷阱） | 低 | Phase 3 清理批次删除（与 P2-23 `ProgressGUI` 死类同批） |
| PF-ONB-03 | `player_data.created_at` / `last_login` | 两列仅存在于 DDL 与 schema.sql，全库**零写入点**（死列）；若曾接线，`created_at` 就能支撑"按注册时间切片的新玩家完成率"精确统计 | 低 | 如运营要精确漏斗口径，需先给这两列接线（一行 UPSERT 即可）；本域指标走人工+step 分布 |
| PF-ONB-04 | `listener/MiscGUIListener.java:140-143` | 词库选择 GUI 点击后的 `word.session-start` 提示直接填**原始标识符**（`difficulty=beginner`、`mode=choice`）——F2-13 只修了 LearnCommand 命令路径，而 GUI 主路径（恰是新手最常走的路径）未修 | 低 | 随本域顺手接线（复用 `word.difficulty-*` / `word.mode-*` 键，~4 行）或记入 i18n 二期；拦截路径因被引导文案接管而不受影响 |
| PF-ONB-05 | `gui/WordLearningGUI.java:32-34` | GUI 标题「单词学习 - beginner」对所有玩家显示原始难度标识（无 i18n label）——新手看到的第一个答题界面即为中英混排 | 低 | i18n 二期（与 PF-ONB-04 同批；注意标题改名须同步 `GUIUtils.isPluginGUI` 白名单与 listener 标题匹配，P2-01 维护约束） |
| PF-ONB-06 | 验收环境 | `hasPlayedBefore()==false` 只在"服务器首次见到该账号"成立——本地测试证反复 `/lea reset` / 删库**无法**复现"全新玩家"路径（R-45 同款约束），验收需真新账号或换 UUID | 低 | 已写入验收前置条件（第 5 节）；测试服可准备一批从未登录的小号 |
| PF-ONB-07 | `core/progress/PlayerProgress.java`（附带观察） | 该类唯一主代码消费方是死类 `ProgressGUI`（PF-ONB-02/P2-23）；若清理批次删 `ProgressGUI`，`PlayerProgress` 将成孤儿（但 `PlayerProgressTest` 单测仍引用它并存活）。删除决策需连带评估测试文件 | 低 | 留给清理评估（非本域阻塞） |

---

*本文件为 Phase 3 规划期只读产出，方案自定，未修改任何源码/配置；3.1/3.2 的方法签名与 2.4 的键名清单为实现期冻结基准。*
