# findings-r1 学习核心链（审查 Agent 产出）
- 审查范围：`core/word/` 全部、`ChatAnswerListener.java`、`WordLearningListener.java`、`WrongBookListener.java`、`WordLearningGUI.java`、`WordBankSelectGUI.java`、`WrongBookGUI.java`、`resources/words/*.yml`
- 审查时间：2026-10-18
- 结论摘要：高 0 条 / 中 2 条 / 低 1 条 / 待验证 1 条 / 豁免挑战 0 条；另有正面结论 4 条
- 备注：本文件由编排者按其返回全文原样保存（该 agent 的 Write 被 harness 拒绝）

## 正面结论（已核验 clean，供汇总参考）

1. **词库内容审计 clean（RH9 核销）**：三档 995 词（317/349/329）——档内/跨档零重复（995/995 唯一）、零缺 english/chinese 必填字段、零可跳过行、difficulty_level 分布与档位吻合（1-3/4-6/7-9 连续带，与 README 表一致）、格式零瑕疵（缩进/UTF-8/无 tab）。仅两条观察（非缺陷）：`advanced.yml` 的 `wrangle` 词性标 noun 存疑；README 说"新词与 beginner 重叠时告警"目前不可达（重叠为 0）。
2. **D23（C-9 CountdownTier 学习侧接线）逐位等价成立**：`WordLearningManager.java:448` `CountdownTier.tierOf(remaining[0], urgentSeconds) == URGENT` 与原 `remaining <= urgentSeconds` 语义逐位一致（`CountdownTier.tierOf`: `remaining > urgentSeconds ? NORMAL : URGENT`），且 UI 侧（WordLearningManager 448-453）与音效侧（459 `remaining[0] <= tickFrom`，独立配置项）互不干扰。
3. **F0-12 冗余守卫删除无回归**：`getComboMultiplier`（607-614）的 for 循环天然覆盖 `<=0` 输入（阈值表首项为 0，返回 1.0），连击乘数 off-by-one 核销——3/5/7/10 阈值 `>=` 判定正确（combo=3→1.5x、5→2.0x、7→2.5x、10→3.0x）。
4. **会话清理链路对照 DuelManager 僵尸任务先例逐一核对**：5 条结束路径全部取消计时器——正常答完（`buildResult` 378-381→`endSession`）、stop（`stopQuiz` 557）、quit（`PlayerJoinListener:104`→`endQuiz` 589-591）、disable（`onDisable` 268→`clearAllSessions` 596-605）、死亡暂停（`onPlayerDeath` 501 `cancelAnswerTimer`）。`cancelAnswerTimer` 对所有路径统一 `answerTaskId` 置 -1 防重复 cancel。**未发现"登记了没取消"模式**。

## 中危

### R1-M1 [logic·中·高] 整场会话进行中可再开局——旧会话被静默作废，做题进度与已得分正落库但玩家无感知
- 位置：`core/word/WordLearningManager.java:56-69`（startQuiz）、`listener/MiscGUIListener.java:161`、`command/LearnCommand.java:253`
- 证据：
  ```java
  public LearningSession startQuiz(Player player, String difficulty, String mode) {
      ...
      cancelAnswerTimer(player);
      quizStates.remove(player.getUniqueId());          // 旧 QuizState 直接丢弃
      LearningSession session = wordManager.startSession(player, difficulty, mode);
  ```
  配合 `WordManagerImpl.startSession:142` `activeSessions.put(player.getUniqueId(), session);`——**同一 UUID 的 activeSessions 槽被新会话覆盖**，旧 LearningSession 引用从此丢失。
  另注意 `startQuiz` 入口**不查** `hasActiveQuiz`：`LearnCommand.startWordQuiz:239-253` 仅查 AI 对话态与引导拦截，既无"进行中会话"守卫也不给二次确认；`MiscGUIListener.handleWordBankSelect:161` 同。
- 复现：玩家在 beginner/choice 答到第 8 题（已得 60 分、连击 x2）时，再执行 `/le word intermediate spelling`（或从主菜单再进词库选择）→ 第 8 题之前的进度处于"GUI 已重开、题号从 1/10 重新计"；同时旧会话仅由 `quizStates.remove` 丢弃、**未走 `endSession` 结算**（掌握度/学习分/复习种子/榜单全部不落），而玩家在旧会话中逐题的今日答题数、金币、挑战进度已即时发过——即"分数丢、钱照发"的不对称。玩家侧零提示（无"旧会话已放弃"文案），且旧会话持有的 `choiceOptions` 缓存等随 state 引用被 GC。
  对比：决斗有 `isBusy` 拒入、AI 对话有 `chat-active` 拒入、练习消费聊天有 M4 守卫——唯独"学习中再开局"裸放行。
- 影响：玩家误操作（手滑再点一次词库）即静默丢整场进度；配合每日挑战 ANSWER_10 进度已计，出现"进度算、会话分不算"的口径分裂。
- 修法：`startQuiz`/`startReviewQuiz`/`startTutorialQuiz` 入口加 `hasActiveQuiz(id)` 守卫——存在活跃会话时回新键 `word.session-in-progress`（提示先停止/答完）或改为先 `stopQuiz` 结算再开新局（需产品裁决）；至少不允许"静默覆盖"。
- 置信度说明：导致"旧会话不结算"的静态事实[确定]；"进度丢但已发奖励保留"是立即推得的行为[高]。

### R1-M2 [logic·中·中] 错题本练习与答题会话互斥缺失——练习中可开答题、答题中可 Shift 练习，两态并存时聊天消费链只剩一条
- 位置：`core/word/WordLearningManager.java:781-783`（startPracticeWord）、`listener/ChatAnswerListener.java:141-169`、`listener/WrongBookListener.java:154-179`
- 证据：`ChatAnswerListener.onPlayerChat` 的消费优先级是硬顺序——
  ```java
  // Check practice mode first (Wrong Book practice)
  if (wlManager.isPracticingWord(player.getUniqueId())) { ... return; }   // L141
  ...
  if (!wlManager.isAnswering(player.getUniqueId())) { return; }           // L171
  ```
  即**练习态优先吞输入**。而 `handlePractice`（WrongBookListener:159-162）只 `closeInventory + startPracticeWord`，不检查/不拒绝 `hasActiveQuiz`；`WordLearningGUI` 的 start 路径也只查 `hasActiveQuiz`（WordLearningListener:181 是 back 键守卫），不查 `isPracticingWord`。`ChatScenarioListener:77` 的 R-66 纵深防御只挡了 quiz/duel，**没挡 practice**。
- 复现：A) 答题会话中（GUI 开着/答题态）主菜单→错题本→Shift 点词进入练习 → 此后玩家以为在答题，输入的词实际被判成练习答案（练习语义：答对扣 wrongCount、答错仅提示）；B) 练习中输入若干题期间 `/le word beginner` 开局 → `startQuiz` 清 quizState 建新会话并开 GUI（不闹矛盾因为 practice 分支先吞输入…但玩家关闭 GUI 后输入的是新会话答案却同时残留 practiceWords？不——startQuiz 不清 practiceWords），此时**练习态仍激活**，新答题会话的任何聊天输入都会被 practice 分支优先吞掉，答题"倒计时开着但输入永远到不了判分"（`isAnswering` 为 true 时会持续超时判错扣分）。
- 影响：输入被错误消费者吞掉 → 超时链连锁判错扣分+入错题本+记错题次数；玩家体验断裂且难以理解。
- 修法：`startPracticeWord` 入口加 `hasActiveQuiz` 拒入；`startQuiz` 入口加 `isPracticingWord` 拒入（或自动 `cancelPractice` 并提示）；`ChatScenarioListener` 的 R-66 守卫同步加 `isPracticingWord`。
- 置信度：代码事实[确定]；实际可达性为[高]（两个入口都无守卫，静态可证）；场景 B 中"练习态吞新会话输入"的后果推演[中]。

## 低危

### R1-L1 [ux·低·中] 词库选择 GUI 显示名大小写路径不一致：`getDisplayName` 用小写查键，键按原样存
- 位置：`gui/WordBankSelectGUI.java:89-103`
- 证据：
  ```java
  String lower = bankKey.toLowerCase();
  String key = "word-bank.difficulty-" + lower;
  String configured = messages == null ? null : messages.getString(key);
  ```
  三内置键（beginner/intermediate/advanced）小写化无差；自定义词库名（如 `MyBank.yml`）小写化后查 `difficulty-mybank`，若管理员按文件名原样配 `difficulty-MyBank` 则 miss，回落"首字母大写化"兜底（`Mybank`——与原文件名 `MyBank` 仍差）。属兜底文案不精确的小瑕疵（F3-03 排序侧同用 `toLowerCase(Locale.ROOT)` 但那是比较、此处是查键）。
- 修法：查键前保持原样的同时补一次原样查询（`messages.getString("word-bank.difficulty-" + bankKey)` 优先，miss 再小写），或文档注明自定义词库键须全小写。

## 待验证区

### R1-V1 错题本 DB 加载语义：移除后重登是否"复活"，以及 practice 答对减计数与 DB 的时序双写
- 位置：`data/PlayerDataStore.java:452-458`（addDifficultWord）、`481-502`（decrementWrongCount）、`DatabaseManager.java:1146-1161`
- 已知静态事实：
  - `addDifficultWord` 仅在内存 `difficultWordsCache` 放入 `DifficultWord(wordLower, Math.max(count,3), now)`，**无任何 DB 写**（文件名表明增量写在别处，但 add 路径零落库）；但 `incrementWrongCount`（433-446）在 reach-3 时先异步 `databaseManager.incrementWrongCount`（UPSERT 建行、count 累加、**无 added_at 的插入语义确认**）——DB 行的存在性依赖异步 increment 而非 add。
  - `WrongBookGUI.open:99-101` 读 `getDifficultWordsData`（cache-first，miss 才 `loadDifficultWordsFromDB`），DB 读（`DatabaseManager.getDifficultWords:1099`）**不带任何 wrong_count 阈值过滤**——凡 increment 过 1 次的行都会出现在 DB 读回的列表里。
  - `removeDifficultWord`（463-476）：删缓存 + 异步删 DB 行；`decrementWrongCount`（481-502）：缓存 merge(-1)，≤0 时删缓存，异步段 `dbCount<=0` 再删 DB。
- 需要运行期验证的点（无 testharness 运行权限）：(a) 玩家答错某词 1 次（只 increment、未入 book）后退服重登 → `loadDifficultWordsFromDB` 把 count=1 的行拉进 `difficultWordsCache`/`wrongCountCache`，**该词是否直接出现于错题本 GUI**（若是：与"答错 3 次才入错题本"的文案/预期不符，属中危 data/ux）；(b) 练习答对减到 0 移出后，异步删 DB 行的竞态窗口内 quit→`saveDifficultWords` 空实现，重登是否复活。
- 验证方法：testharness 跑"答错 1 次→quit→rejoin→/le wrong 看列表"；或直接 sqlite CLI 查 difficult_words 行。请编排者转给有 testharness 的验证 Agent。

## 范围外发现

1. **归 R10**：`LetsLearnEnglish.java:366-370` 复习提醒任务的排程条件在 enable 时只判一次 `word-system.review.enabled`（配置在启动后关闭 → 任务仍在跑，靠 `ReviewScheduler.checkAndNotifyReviews:51-55` 体内门兜底 no-op——这层是刻意设计且注释说明了；但 **reload 后重新打开开关不会再排程**的对称性未在注释覆盖，建议 R10 核 config 生命周期承诺）。
2. **归 R2**：`ChatScenarioListener` R-66 纵深守卫（:77）未包 `isPracticingWord`（与 R1-M2 同源，从 R1 看去是练习侧缺守卫；从 R2 看去是对话侧守卫缺一项）。
3. **归 R6**：`WordLearningManager.submitAnswer:221-226` 逐题调 `DailyChallengeManager.onWordCorrect`——答对词挑战的"当日完成"推进在每答对一步即落，若会话中途覆盖开新局（R1-M1），挑战进度按已答题数保留而会话分丢失，两口径分裂归 R6 核（challenge 结算口径）。
4. **归 R13**：`WordLearningManager.practiceWords`（:38）在 `endQuiz`/`clearAllSessions` 中**未清理**（只有 `cancelPractice` 清；quit 路径 `PlayerJoinListener:105` 有调，disable 路径 `clearAllSessions` 没有）——onDisable 时残留的 practiceWords 会随插件实例销毁，实际无泄漏（插件停用即弃），但作为 quit-symmetry 核对项转 R13。
5. **归 R10/R13**：`ReviewScheduler.lastNotifiedDate` 只由午夜任务 `clearNotifiedDates` 清（LetsLearnEnglish:580），quit **不**清（设计上按日期比对天然失效+释放依赖午夜；单服长期运行有极少离线 UUID 驻留——午夜清空覆盖，属可接受，仅记录）。

## 豁免挑战

无（对照 E 节：ReviewScheduler 间隔需重启=accepted B5 已核、word_progress REPLACE 语义=P3-09 accepted、对决不计答题数=accepted、daily-round-limit 豁免均未误报）。

## 审查覆盖声明

- 已完整读：`AnswerChecker.java`、`LearningSession.java`、`ReviewScheduler.java`、`Word.java`、`WordBank.java`、`WordBankValidator.java`、`WordLearningManager.java`（936 行全文）、`WordManagerImpl.java`、`WordModes.java`、`ChatAnswerListener.java`、`WordLearningListener.java`、`WrongBookListener.java`、`WordLearningGUI.java`、`WordBankSelectGUI.java`、`WrongBookGUI.java`、`CountdownTier.java`、`DifficultWord.java`
- 已读关键上下文：`PlayerDataStore.java`（review/wrong/session/mastered 相关 ~450 行）、`DatabaseManager.java`（difficult_words 段 145 行）、`LetsLearnEnglish.java`（onEnable/onDisable/午夜/监听器注册）、`PlayerJoinListener.java`（quit 链）、`LearnCommand.java`（word/review 入口段）、`MiscGUIListener.java`（词库选择段）、`OnboardingManager.interceptQuizStart`、zh/en.yml 的 word-learning-gui/wrong-book 键
- 词库三档 yml：由只读子代理逐行审计（awk/grep，带自测校验器），995 词全检
- 未做完：RH7 会话入库时序的"双重计数"未逐条对账（recordSession 只在 `showSessionComplete` 调一次、quit 路径不 recordSession 只 updateWordProgress——静态看无双计，但 `updateWordProgress` 的 correctMap/incorrectMap merge 与 `updatePlayerStats` 的 rebuilding 未全链对死，转 R3）；错题本 DB 往返（R1-V1）未运行期验证；未跑 mvn（禁止重型命令）。
- 检查项状态：计分/连击边界（完成）、QuizState 清理五路径（完成）、错题本三入口一致性（左键删/Shift 练习/listener——发现 M2 与 V1）、复习调度+通知去重（完成，除 midnight 交互为静态核查）、AnswerChecker 边界（大小写/a-an-the→无前后缀剥离逻辑、属裸 equals/equalsIgnoreCase；全角半角未归一；单字母撞字母选项由 R-56 守卫覆盖——**注：`letterToIndex` 用 `Character.toUpperCase(charAt(0))`，全角 'Ａ' 会 miss 且非 ASCII 恒 miss → 判错而非无效，属既有设计可接受**）、D23（完成，等价成立）、词库质量（完成，clean）。
