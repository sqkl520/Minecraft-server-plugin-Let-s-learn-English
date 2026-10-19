# fix-plan-c1 — Block C1 玩家可感中危 12 条施工规划

- 基线：HEAD=`300c699`（批 A/B 已提交，mvn verify 49/49 全绿，工作树干净）；版本保持 1.0.7-SNAPSHOT。
- 行号说明：以当前工作树为准（标注"以方法名为锚"，行号供定位，允许 ±数行漂移）。
- 纪律：每条含 目标/根因 + 修法规格（方法名+行号+行为语义+边界）+ i18n 新键清单 + 回归验证点 + 风险与交互。
- 裁决点结论见每条"裁决"小节。

## 流划分与文件白名单（零重叠）

| 流 | 条目 | 文件白名单（互斥） |
|---|---|---|
| C1-A 学习核心与互斥 | R1-M1, R1-M2, R8-M1, R11-M2, R11-M4 | `core/word/WordLearningManager.java`、`listener/MiscGUIListener.java`、`listener/WrongBookListener.java`、`listener/ChatScenarioListener.java`、`command/LearnCommand.java`、`core/manager/CheckInManager.java` |
| C1-B AI 对话 | R2-M1, R2-M3, R2-M4 | `gui/ScenarioSelectGUI.java`、`listener/ScenarioSelectListener.java`、`core/ai/ChatFeedbackManager.java`、`core/ai/AIChatManager.java` |
| C1-C 决斗 | R5-M1, R5V-1 | `core/duel/DuelManager.java`、`gui/DuelGUI.java`、`listener/DuelListener.java` |
| C1-D GUI 反馈与本地化 | R11-M1, R11-M3 | `gui/WordLearningGUI.java`、`gui/DailyChallengeGUI.java`、`gui/LearningCalendarGUI.java` |

跨流共享文件（由编排者统一处理）：`messages/zh.yml` + `messages/en.yml`（新键）、`CHANGELOG.md`。

## 条目索引（12 条）

1. R1-M1 整场会话进行中可再开局 → C1-A（WordLearningManager 入口守卫）
2. R1-M2 错题本练习与答题互斥缺失 → C1-A（三点守卫）
3. R2-M1 场景 >7 时第 7+ 位 GUI 不可见不可点 → C1-B（更多键文案补命令入口）
4. R2-M3 总结回调串台到新会话 → C1-B（handleSummaryResult 会话比对）
5. R2-M4 场景选择 GUI 点击无决斗门 → C1-B（ScenarioSelectListener 补 isInDuel）
6. R5-M1 宽限任务覆盖三处小改 → C1-C（DuelManager + DuelListener）
7. R5V-1 DuelGUI 13 空槽铺满 → C1-C（DuelGUI 玻璃 filler + 纵深）
8. R8-M1 庆祝页/直开主页 pushBack → C1-A（LearnCommand 两处 pushBack）
9. R11-M1 WordLearningGUI 标题本地化 → C1-D（localizeDifficulty + review 特判）
10. R11-M2 词库点击 catch → C1-A（MiscGUIListener try/catch）
11. R11-M3 预载失败提示 → C1-D（两 GUI catch 分支 runTask 回主线程发消息）
12. R11-M4 签到失败语义 → C1-A（CheckInResult.storageFailed + 新键）

---

# C1-A 学习核心与互斥（R1-M1, R1-M2, R8-M1, R11-M2, R11-M4）

文件白名单：`core/word/WordLearningManager.java`、`listener/MiscGUIListener.java`、`listener/WrongBookListener.java`、`listener/ChatScenarioListener.java`、`command/LearnCommand.java`、`core/manager/CheckInManager.java`（六文件，与其它三流零重叠）。

---

## R1-M1 整场会话进行中可再开局 → 入口守卫拒入

**目标/根因**：`startQuiz`/`startReviewQuiz`/`startTutorialQuiz` 入口不查 `hasActiveQuiz`，进行中会话被 `quizStates.remove` 静默覆盖丢弃（旧会话不结算，已发奖励不回滚）。

**裁决**：守卫放 **三个 start* 方法内部**（单一真相源；`startQuiz` 现有调用方 LearnCommand:253 / MiscGUIListener:161 对 null 均为静默 return——提示必须由守卫自己发，与 `module.disabled` 同模式）；行为 = **拒入 + 提示**（默认保守方案，不做"停止旧会话再开"）。守卫位于模块门**之后**（module.disabled 优先）：
```java
// 在各自模块门 if 块之后插入：
if (hasActiveQuiz(player.getUniqueId())) {
    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
            "word.session-in-progress"));
    return null;
}
```
- `startQuiz`（以方法名为锚，当前 :56）：插入于 :62 模块门后、:63 `cancelAnswerTimer` 前。
- `startReviewQuiz`（锚 :77）：同式，插于 :83 后。
- `startTutorialQuiz`（锚 :109）：同式，插于 :114 后。**tutorial 路径核实**：教程开局走 `startTutorialQuiz`（OnboardingManager:180 调用），**不经 startQuiz**；`interceptQuizStart` 对 null 的处理是 `return true`（已接管、不再回落，:185-189）——守卫发的提示不会与调用点重复。玩家答 tutorial 中再点词库 → intercept → startTutorialQuiz → 拒入提示，正确。

**唯一需要改的调用点**：`LearnCommand.handleReviewCommand`（锚 :364）的 null 分支（:384-390）——startReviewQuiz 因"会话进行中"返回 null 时守卫已发提示，此处再发 `review.unavailable` 会双发且误导。改为：
```java
} else if (!plugin.getWordLearningManager().hasActiveQuiz(player.getUniqueId())) {
    player.sendMessage(... "review.unavailable");
}
```
（"到期词失效"真实 null 时 hasActiveQuiz 为 false，照旧发 review.unavailable，语义不变。）

**边界**：R-13 死亡暂停玩家仍持 quizState——重生走 `onPlayerRespawn` 直开 GUI，不落 startQuiz，不受影响；quit/disable 路径本清理 quizStates，无泄漏面。

**i18n 新键**：
- `word.session-in-progress`：zh `"{prefix}&c你还有一场学习会话正在进行中喵~ 请先答完它，或点停止按钮结束本局后再开新局"` / en `"{prefix}&cYou already have a learning session in progress! Finish it (or press Stop) before starting a new one."`

**回归验证点**：①无会话时三入口开局逐位不变（GUI 打开 + session-start 消息照旧）；②`/le word`（会话中）→ 单条 word.session-in-progress、无 GUI、旧会话 GUI 不受扰；③`/le review` 会话中 → 单条 word.session-in-progress（不再双发 review.unavailable）；④到期词失效路径仍发 review.unavailable。

**风险与交互**：与 R1-M2 共用同一批方法（同流同文件，先做 R1-M1 再加 practice 守卫）；WordLearningListener 的 stop/back 键流程不涉入口，无连带。

---

## R1-M2 错题本练习与答题互斥缺失 → 三点守卫

**目标/根因**：`ChatAnswerListener` 消费优先级 hard-order（practice 分支 :141 先于 quiz 分支 :171），两态并存时答题输入被 practice 吞掉 → 超时连锁判错。而 practice 入口不查 quiz、quiz 入口不查 practice、ChatScenarioListener 的 R-66 守卫也不含 practice。

**三处守卫（逐点核实于当前工作树）**：
1. **practice 入口拒 quiz**：`WrongBookListener.handlePractice`（锚 :154）——`startPracticeWord` 只收 UUID（WordLearningManager:781），无法在 manager 内发消息；该方法是全仓唯一调用方，守卫放 **listener 入口**（:159 `player.closeInventory()` 之前）：
```java
var wl = plugin.getWordLearningManager();
if (wl.hasActiveQuiz(player.getUniqueId())) {
    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
            "word.session-in-progress"));   // 复用 R1-M1 键（"你已有会话进行中"语义吻合）
    return;   // 不关界面、不进入练习
}
```
2. **quiz 入口拒 practice**：`WordLearningManager.startQuiz`/`startReviewQuiz`/`startTutorialQuiz`（同 R1-M1 插入点，紧随 hasActiveQuiz 守卫后）：
```java
if (isPracticingWord(player.getUniqueId())) {
    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
            "word.practice-in-progress"));
    return null;
}
```
3. **R-66 纵深守补充**：`ChatScenarioListener.onPlayerChat`（锚 :77-81）的守卫条件补 practice：
```java
if (plugin.getWordLearningManager().hasActiveQuiz(player.getUniqueId())
        || plugin.getWordLearningManager().isPracticingWord(player.getUniqueId())   // R1-M2：练习同样优先于对话
        || (plugin.getDuelManager() != null
                && plugin.getDuelManager().isInDuel(player.getUniqueId()))) {
    return;
}
```
（注释同步改为"答题/错题练习/决斗优先"。）

**边界**：practice 中 `cancel` 输入路径（ChatAnswerListener:153-160）不受影响；两态并存的历史残留（修复前已进入双态）在输入层仍由 practice 先吞——但新入口不再允许构建双态，存量状态随 quit 自然清除（practiceWords 在 PlayerJoinListener:105 清理）。

**i18n 新键**：
- `word.practice-in-progress`：zh `"{prefix}&c你正在错题练习中喵~ 请在聊天栏输入 &fcancel &c结束练习后再开始答题"` / en `"{prefix}&cYou're practicing a wrong-book word! Type &fcancel &cin chat to stop, then start the quiz."`

**回归验证点**：①无 quiz 时 Shift 点错题本词 → 练习照常（音效/提示/输入消费链不变）；②正常答题中 Shift 练习 → 拒入提示、GUI 不关、无 practiceWords 写入；③练习中输入 `/le word beginner` → 拒入提示、原练习不受扰；④练习中输入 cancel → 正常取消；⑤对话中进入练习（step 与 R-66）→ 消息不再被对话侧吞。

**风险与交互**：与 R1-M1 同文件同方法区段——两守卫在同一插入位置（先 hasActiveQuiz 后 isPracticingWord），需一次性落笔；`DuelManager.isBusyAny` 是否也已含 practice（:1368 已查 `hasActiveQuiz || isPracticingWord`）——不动。

---

## R8-M1 庆祝页/直开主页 pushBack → 最小修复（两条路径）

**目标/根因**：`GuiNavigationManager.popBack` 无条件弹栈顶（锚 :51-61），而 `/le challenge` 庆祝页直开（LearnCommand:568-569）与 `/le profile` 直开（:444/:452）**不 push 自己的帧**，返回键会消费无关残留帧。

**裁决：最小修复 = 在两个直开点各 push 一枚「主菜单」帧**（不做根治残帧改造——那是 Phase 3 导航契约重构）。重要性质：**以主菜单为帧内容即使成为残留也零危害**——pop 出来落地=fallback 落地（同为 MainMenu），不会造成"跳去无关页"。push 位置精确到行：
1. `LearnCommand.handleChallengeCommand` 庆祝页直开处（锚 :567-569）：在 `new ChallengeCompleteGUI(plugin).open(...)` 之前插：
```java
plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
        () -> new MainMenu(plugin).open(player));   // 与 MainMenuListener:148 每日挑战同模式
```
2. `LearnCommand.handleProfileCommand`（锚 :433）：两处 `new ProfileGUI(plugin).open(...)`（:444 指定玩家、:452 本人）**各自之前**插同式 pushBack 主菜单帧。

**消费点不动**：MiscGUIListener:303-315（庆祝页返回）、SocialListener:429-433（主页返回）保持 popBack+MainMenu fallback 原样。

**与 F3-02 笑话页修复不冲突**：F3-02 的 push 纪律改在 EnglishSquareGUI.openHidden/SocialListener 隐身路径（该路径刻意不压帧，SocialListener:456-458 注释自证）——本修复只加"命令直开入口"的帧，两处无共享代码。

**回归验证点**：①广场→头像→ESC（残留帧）→`/le challenge`→点返回 → **回主菜单**（不再回广场）；②`/le profile`→返回 → 主菜单；③广场→头像→主页→日历→返回 → 回本人主页（既有 push/pop 配对不被新帧干扰——新帧只出现在命令直开路径，LIFO 下不切入既有链）；④无残留帧时逐位不变（原有 fallback 语义）。

**风险与交互**：pushBack 无配对 pop 时残留（quit 由 PlayerJoinListener:116 clearPlayer 兜底）；R11-L4 是本条的命令入口面，已合并——不得再单独立案重复修。

---

## R11-M2（含 R8-L2 合并）词库点击 try/catch

**目标/根因**：`MiscGUIListener.handleWordBankSelect`（锚 :107）在 `startQuiz` 调用处（锚 :161）无 try/catch；空词池窗口 `selectWordsForSession` 抛 ISE 冒泡出事件处理器，玩家零反馈且困在原界面。

**修法**：把 :161-166 段包入 try，catch 对**逐字对齐 LearnCommand.startWordQuiz:269-277**：
```java
try {
    var session = plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
    if (session == null) {
        return;   // module.disabled / session-in-progress / practice-in-progress 已由 startQuiz 内发提示
    }
} catch (IllegalStateException e) {
    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
            "word.no-words-available"));
    return;   // 界面不关：与命令路径同口径（玩家可改点其它词库）
} catch (IllegalArgumentException e) {
    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
            "general.error", "message", e.getMessage()));
    return;
}
new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
player.sendMessage(... "word.session-start", ...);   // 原 :171-174 不变
```
注意：`interceptQuizStart`（:158）的接管判定在 try **外**（其内部已自 catch，行为不变）。

**i18n**：零新键（复用 word.no-words-available / general.error，均已存在）。

**回归验证点**：①正常点击词库 → GUI+session-start 逐位不变；②零词词库（reload 制造）→ 玩家收 word.no-words-available、界面留存、控制台无堆栈（对比修复前冒泡）；③word 模块关闭 → module.disabled（第二道门在 startQuiz 内）单条发送。

**风险与交互**：R8-L2 与本条同一位置合并立案（findings-r8 明确"与 R11-M2 同位置，合并"）；R1-M1 守卫触发的 null 分支在此处静默 return，无双重提示。

---

## R11-M4 签到失败语义（storageFailed）

**目标/根因**：`CheckInManager.checkInLocked` 的 DB 写失败分支（锚 :98-100）返回 `CheckInResult(false,...)`，与"今天已签到"分支（:79）同为 success=false，LearnCommand:746-749 统一发 `checkin.already`——真实存储失败被伪装成正常幂等，玩家不再重试、当日 streak 断。

**修法规格**：
1. `CheckInResult`（锚 :181-189）增加字段 `storageFailed`，record 组件序：
```java
public record CheckInResult(boolean success, int streak, double coinsEarned,
                            boolean streakBroken, boolean economyDisabled,
                            boolean storageFailed) {
    /** Back-compat ctor（保留：与 economyDisabled 同批加的兼容面一致）。 */
    public CheckInResult(boolean success, int streak, double coinsEarned, boolean streakBroken) {
        this(success, streak, coinsEarned, streakBroken, false, false);
    }
    /** R44 5-arg ctor（现有调用点兼容）。 */
    public CheckInResult(boolean success, int streak, double coinsEarned, boolean streakBroken,
                         boolean economyDisabled) {
        this(success, streak, coinsEarned, streakBroken, economyDisabled, false);
    }
}
```
2. **构造点全部更新**（全仓仅本文件 3 处；消费点仅 LearnCommand:720-749，`grep` 已核无测试引用）：
   - 已签到分支（:79）→ `(false, data.getStreakCount(), 0, false)` 保持（storageFailed=false，语义="幂等拒绝"）。
   - DB 写失败分支（:98-100）→ 显式六参：`new CheckInResult(false, data == null ? 0 : data.getStreakCount(), 0, false, false, true)`。
   - 成功分支（:110-112）→ 加末参 false（`economyDisabled && !paid` 语义不动）。
3. `LearnCommand.handleCheckinCommand` else 分支（:746-749）：
```java
} else {
    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
            result.storageFailed() ? "checkin.storage-failed" : "checkin.already"));
}
```
**不与 GiveCoinsOutcome 混淆**：经济禁用 = 成功路径的 `economyDisabled=true`（:742-745 提示不变）；存储失败 = 失败路径新字段。两者互斥不重叠。

**i18n 新键**：
- `checkin.storage-failed`：zh `"{prefix}&c签到写入失败喵~ 本次签到没有记录成功，请稍后再试一次"` / en `"{prefix}&cCheck-in could not be saved! It was NOT recorded — please try again in a moment."`（checkin 节带 {prefix} 风格，已与 zh.yml:261 核对。）

**回归验证点**：①正常签到 → success 全链（音效/社交动态/economy-disabled 行）逐位不变；②重复签到 → checkin.already 照旧；③模拟 updateCheckIn false（断 DB）→ checkin.storage-failed（且不含 already 文案）；④断签提示 streak-broken 序位不变。

**风险与交互**：record 是公开类型，新增组件属二进制不兼容——但项目内全量构造点在 CheckInManager、消费在 LearnCommand，已逐点核对；保留双 back-compat ctor 与新 6 参主 ctor 泾渭不混。

---

# C1-B AI 对话（R2-M1, R2-M3, R2-M4）

文件白名单：`gui/ScenarioSelectGUI.java`、`listener/ScenarioSelectListener.java`、`core/ai/ChatFeedbackManager.java`、`core/ai/AIChatManager.java`。

---

## R2-M1 场景 >7 时第 7+ 位 GUI 不可见不可点 → 文案补命令入口

**目标/根因**：渲染侧 `subList(0, SCENARIO_SLOTS.length - 1)`（ScenarioSelectGUI:91-93）与点击侧同截断（ScenarioSelectListener:108-119），第 7+ 位场景在"更多…"折叠下对 GUI 玩家完全不可达；`dialogue.more-scenarios-lore` 说"翻页功能即将上线"但无翻页。

**裁决：不新增翻页，走文案方案**。理由：(1) 命令入口真实可达——`/le dialogue <场景名>` 存在且带 **Tab 补全**（LearnCommand:1086-1094 补全个人场景 key），文案指明即可用；(2) 翻页需 per-player 页状态 + quit 清理 + 9/17/21/23/25 空闲槽上的点击路由 + 新 i18n 键，属结构性改动，超出中危修复批次（可留 Phase 3 story）。槽位勘察结论备查：free slots = 0-7、9、17、21、23、25（8 返回/10-16 场景/18-20 难度/22 标签/24 管理/26 API 刷新占用）——翻页箭头可放 9/17。

**修法（仅改文案，两文件均不动代码）**：改 `dialogue.more-scenarios-lore` 现有键（zh.yml:97 / en.yml:97）：
- zh：`"&7还有 {count} 个场景未显示，输入 &e/le dialogue <场景名> &7可直接进入（支持 Tab 补全）"`
- en：`"&7{count} more scenario(s) not shown — use &e/le dialogue <name> &7to open directly (Tab-completes)"`

宽度核对：本 lore 为"更多…"物品 lore，长行可接受（与现文案同量级）。

**回归验证点**：①≤7 场景时该键不渲染，零影响；②≥8 场景点"更多…"仍 no-op 播音（R-49/R-53 语义不动）；③按新文案执行 `/le dialogue <名字>` → 正常开局。

**风险与交互**：纯文案；与 R2-V2（资源 yml 与硬编码 6 key 比对）无关。

---

## R2-M3 总结回调串台到新会话 → handleSummaryResult 单点隔离

**目标/根因**：`requestConversationSummary` 的 thenAcceptAsync 主线程回调（AIChatManager:1198-1215）无会话隔离，玩家结束会话后立即开新会话（或重连）时，迟到的上一场点评块与当前会话消息交织。

**裁决：单点最小修 = ChatFeedbackManager.handleSummaryResult 顶部按"玩家是否已开新会话"分流**，不动 AIChatManager 回调链。判据 `aiChatManager.hasActiveConversation(player)`（AIChatManager:642，公开既有方法）：上一场会话在 `endConversation` 时已从 sessions 移除（:598 `sessions.remove`），回调到达时若 sessions 仍有该玩家，**必然是新的会话**（sessionId 必不同）——无需新增"最近会话"状态，直接以此判据等价实现 findings 的"比对不匹配静默丢弃"，且天然覆盖 quit-rejoin（sessions 已空→不丢，可接受）与离线（sendMessage 无操作）。

**修法规格**：`ChatFeedbackManager.handleSummaryResult`（锚 :150，private，调用方 deliverEndMain:119-121 的主线程 lambda）方法体最前插入：
```java
// R2-M3：玩家已开新会话 —— 上一场点评不显示（防串台）。仍照常落库：
// 成功分支经 persistAfter 写 source=ai 行（数据不丢）；失败分支无库写，直接丢。
if (aiChatManager.hasActiveConversation(player)) {
    if (errorKey == null && feedback != null) {
        persistAfter(player.getUniqueId(), summary, localWrite,
                "ai", GSON.toJson(feedback));
    }
    return;
}
```
（字段 `aiChatManager` 已存在于本类构造注入 :34-38；`persistAfter` 为既有私有方法。）

**边界**：正常单会话流（bye → 占位 → 点评到达时无新会话）逐位不变；同场会话不可能命中（session 已移除）；R-17 先例（dispatchChat 的 `sessionId.equals(current.sessionId)`，AIChatManager:938-944）保持不动——本条只补总结链缺口。

**i18n**：零新键。

**回归验证点**：①单会话正常收点评（三链：header/comment/corrections + hint）；②结束达标会话→立即开新会话→点评到达 → 新会话聊天框无旧点评（且 DB: 旧 sessionId 行 source=ai payload 正常覆写可查）；③点评到达时玩家空闲 → 照常显示；④失败分支（errorKey）在空闲时照旧发 ai-failed。

**风险与交互**：与 R2-L1（unloadPlayer 空实现）无耦合；不需清任何新状态（无新增字段）。

---

## R2-M4 场景选择 GUI 点击无决斗门 → 补 isInDuel（与命令对齐）

**目标/根因**：`ScenarioSelectListener.handleScenarioClick`（锚 :106）只查答题/练习（:132-138），不查决斗；决斗中玩家可经主菜单→情景对话进入 AI 对话 → 双模式困局（开场白 API 已发、消息全被决斗吃掉）。

**修法**：在 `handleScenarioClick` 内、答题/练习守卫（:130-138）**之前**插入决斗守卫（顺序对齐 LearnCommand:313-318 "决斗优先判"——决斗本身含答题态，报"先结束对决"更准确）：
```java
// R2-M4：决斗优先判（与 /le dialogue 命令入口逐字对齐）。决斗 AIM 已开
// 对话时的消息竞争由 ChatScenarioListener R-66 让路，但入口直接拒绝最干净。
var duelManager = plugin.getDuelManager();
if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
    player.closeInventory();
    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
            "duel.finish-duel-first"));
    return;
}
```
安置点：:121-129 的 isAvailable 分支之后、:132 答题守卫之前；closeInventory 与答题分支同款。**不**补 chat-active 守卫（越出 R2-M4 范围；命令侧 :327 的 chat-active 属另一面，保持 GUI 现状）。

**i18n**：零新键（复用 duel.finish-duel-first，zh:299/en:299）。

**回归验证点**：①无决斗时点击场景逐位不变（音效/开局/开场白链）；②决斗中点击 → closeInventory + duel.finish-duel-first，无 addPlayer/无 startConversation/无开场白请求；③答题中点击 → 仍 dialogue.finish-quiz-first（原分支不回归）。

**风险与交互**：`getDuelManager()` 可空（duel 模块未启用），null 判必留（与 LearnCommand 同式）；范围外发现"`/le dialogue` 无参打开 GUI 不预检"（findings-r2 归 R8）不在本条。

---

# C1-C 决斗（R5-M1, R5V-1）

文件白名单：`core/duel/DuelManager.java`、`gui/DuelGUI.java`、`listener/DuelListener.java`。
（R5V-1 纵深面需在 `listener/DuelGUIListener.java` 加 guard——若非流白名单，则可选/或由编排者并流处理；见条目注。**推荐把 DuelGUIListener.java 加入 C1-C 白名单**，其在其它三流均未被触碰，白名单仍零重叠。）

---

## R5-M1 宽限任务覆盖三处小改

**目标/根因**（findings-r5 + r5v 证实闭环）：1 秒内 quit→join→quit 连锁 → ①:876 `graceTasks.put` 无条件覆盖 taskA 且不 cancel（僵尸任务，1Hz 空转到关服）②:821-823 提前 return 无自取消（注释"cancelled below"为 stale）③:842 单参 remove 可摘到"别人"（同房间新一轮任务）→ 对局悬挂 + 对离线玩家 openInventory/setScoreboard。

**修法三处**（锚点为方法名/当前行号）：
1. **handleDisconnect 置入前先清旧**（锚 :800，插于 :803 房间守卫后、:805 `disconnectTimers.put` 前）：
```java
// R5-M1(a)：覆盖写前先取消旧宽限任务（幂等；正常路径恒为空）。
BukkitTask prevGrace = graceTasks.remove(playerId);
if (prevGrace != null) { prevGrace.cancel(); }
```
2. **任务体改自引用自取消**（锚 :817-876，抄同文件 wordTimeout 的 `selfRef` 模式 :527-568 与 `selfCancelWordTimeout` :580-584 双参语义）：
   - `BukkitTask task = runTaskTimer(...)` 改 `final BukkitTask[] selfRef = new BukkitTask[1]; selfRef[0] = runTaskTimer(...)`；末尾 `graceTasks.put(playerId, task)` 改 `graceTasks.put(playerId, selfRef[0])`。
   - :821-823 提前 return 分支改：
```java
if (!disconnectTimers.containsKey(playerId)) {
    selfCancelGrace(playerId, selfRef[0]);   // 原"return; // timer will be cancelled below"——下方不可达，改自取消
    return;
}
```
   - :826-834 房间已拆分支（对称统一，防未来回归）：`BukkitTask self = graceTasks.remove(playerId); if (self != null) { self.cancel(); }` 改 `selfCancelGrace(playerId, selfRef[0]);`（disconnectTimers.remove 保留原位）。
   - :842-845 到期分支：`BukkitTask self = graceTasks.remove(playerId); if (self != null) { self.cancel(); }` 改 `selfCancelGrace(playerId, selfRef[0]);`（双参 remove：绝不摘同房间新一轮任务）。
   - 新增私有 helper（放 `selfCancelWordTimeout` 邻近，语义注释同款）：
```java
/** R5-M1(b)：宽限任务自清理 —— remove(key, value) 双参只摘"仍是自己"的条目，
 *  无论条目在否都 cancel 自己（cancel 幂等）；防 stale 任务每秒空转。
 *  模式抄 {@link #selfCancelWordTimeout}（:580-584）。 */
private void selfCancelGrace(UUID playerId, BukkitTask task) {
    if (task == null) return;
    graceTasks.remove(playerId, task);
    task.cancel();
}
```
3. **handleReconnect 入口 isOnline 守卫 + DuelListener UUID 捕获**：
   - `DuelManager.handleReconnect`（锚 :882）方法体**第一行**（必须在 `disconnectTimers.remove` 之前——守卫命中不得消费 info，玩家再次 quit 已排新宽限）：
```java
if (player == null || !player.isOnline()) {
    return;   // R5-M1(c)/R5-L1：延迟回调落在已离线玩家 —— 放弃（新 quit 的宽限不受扰）。
              // 注：离线补通知属 R-69 offlineNotices 链路，不走本方法。
}
```
     此守卫一并收口 findings-r5v 低观察的 `duelBars.removePlayer/addPlayer`（:928-932）与 `getPlayerLanguage`（经 setupScoreboard）越界面——全在守卫之后，自然短路。
   - `DuelListener.onPlayerJoin`（锚 :34-43）：改为 UUID 捕获 + 回调内复取：
```java
@EventHandler(priority = EventPriority.MONITOR)
public void onPlayerJoin(PlayerJoinEvent event) {
    DuelManager duelManager = plugin.getDuelManager();
    if (duelManager != null) {
        UUID playerId = event.getPlayer().getUniqueId();   // 不闭包持有 Player 句柄
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) return;
            duelManager.handleReconnect(p);
        }, 20L);
    }
}
```
   （import 增 `java.util.UUID`；`Player` 可全限定或导包，风格随文件。）

**i18n**：零新键。

**回归验证点**：①正常掉线→宽限倒计时→重连 → 复联链不变（GUI/计分板/BossBar/文案）；②宽限到期对方判胜 → 结算+cleanup 不变且任务自取消（`/le duel` 后可再三检查无空转）；③正常 quit→join（无连锁）→ handleReconnect 照常；④1 秒连锁（quit→join→quit→等 1s）→ 旧任务被 cancel、无僵尸；对局仍持新宽限（30s 后判负正常落）；⑤离线窗口 join→1s 内 quit → 延迟回调直接放弃、无 GUI open 日志噪音。

**风险与交互**：与批 B 先例 `selfCancelWordTimeout`（:580-584）**同式不冲突**；:826 分支的 `disconnectTimers.remove(playerId)` 语义保持（修复 1 后 stale 任务已不可能 tick，双保险面仅任务表）；`cleanup`/`shutdown` 单参遍历逻辑不动（现在所有任务都恒可经 map 触及或被自取消）；R5V-1 与本条同文件不同方法（DuelGUI），无冲突。

---

## R5V-1 DuelGUI 13 空槽铺满（+监听器纵深）

**目标/根因**：`DuelGUI.buildInventory`（锚 :150）只填 9-17/10-12/14-16/19-20/13/22，空槽 0-8、18、21、25、26 共 13 格无占位；`DuelGUIListener` 的 rawSlot 守卫对下半区（含 shift-click）直接 return，不 cancel → 原版将背包物品移入空槽 → 下一题 `refreshBothGUIs` 重建界面（DuelManager:1304-1313）→ 物品静默消失。

**修法**：
1. **首选：铺满 27 格**——`buildInventory` 开头（`inv.clear()` :151 之后、现有 setItem 段之前）插入（材料/文案照 DuelChallengeGUI:115-120 灰玻璃 filler 风格）：
```java
// R5V-1：灰色玻璃铺满全部槽位 —— 根因封堵 shift-click 塞入（原版无落点），
// 保持与 DuelChallengeGUI 全格填充同模式（该界面因此天然免疫）。
ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
for (int i = 0; i < inv.getSize(); i++) {
    inv.setItem(i, filler.clone());
}
```
   （DuelGUI.createItem 为 `(Material, String, String...)` 变参，单参调用合法；现有 score/skull/word/quit 各 setItem 随后覆盖，渲染内容零变化。）
2. **纵深（推荐同做）**：`DuelGUIListener.onInventoryClick`（锚 :29）在 rawSlot 守卫（:39-41）**之前**插：
```java
// R5V-1 纵深：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）补 cancel ——
// 铺满后原版已无落点，此处防未来布局改动回归。其余下半区点击不 cancel
//（玩家整理背包不受影响，R-01/R-22 语义保持）。
if (event.getClick() == org.bukkit.event.inventory.ClickType.MOVE_TO_OTHER_INVENTORY
        && rawSlot >= topSize) {
    event.setCancelled(true);
    return;
}
```
   安置注意：`topSize`/`rawSlot` 局部变量在 :39-41 之后才计算——把该 guard 放在 `int topSize = ...` 之后、`if (rawSlot < 0 || rawSlot >= topSize) return;` 之前即可。

**i18n**：零新键（filler 名 " " 与 DuelChallengeGUI 同款）。

**回归验证点**：①决斗 GUI 视觉逐格不变（灰玻璃只填原空格，分数格/头像/单词书/退出键覆盖顺序在后）；②shift-click 背包物品 → 无物入顶（铺满 + cancel 双保险）；③拖拽守卫/点击退出键逐位不变；④`isViewingDuelGUI` 标题判读不受影响。

**风险与交互**：`refreshBothGUIs` 重建频率高，filler 循环 27 次 setItem 成本可忽略；R5V-2（WIN_SCORE 与 5 槽数组隐式耦合）不属本条（可选随修 `Math.min`，但保持本批最小化——标注不做）。

---

# C1-D GUI 反馈与本地化（R11-M1, R11-M3）

文件白名单：`gui/WordLearningGUI.java`、`gui/DailyChallengeGUI.java`、`gui/LearningCalendarGUI.java`。

---

## R11-M1 WordLearningGUI 标题本地化

**目标/根因**：`WordLearningGUI.open`（锚 :29）把原始 difficulty key 直接 replace 进标题（:33-35 `word-learning-gui.title` = "单词学习 - {difficulty}"）→ 中文玩家看到"单词学习 - beginner"；复习会话传 "review" 显示"单词学习 - review"。聊天侧已走 `WordModes.localizeDifficulty`（F2-13/PF-ONB-03 先例），GUI 标题漏修。

**修法**：`open()` 中构造 title 前解析显示值（`getPlayerMessage` 用法照 LearnCommand:381 先例；`word.mode-review` 特判因 localizeDifficulty 的 switch 默认回落原值——:170-177 无 review 分支）：
```java
// R11-M1：标题难度本地化（F2-13 同根漏面收口）；"review" 特判 ——
// localizeDifficulty 的 switch 只覆盖三档，review 会原样回落。
String difficultyLabel = "review".equalsIgnoreCase(difficulty)
        ? plugin.getMessageUtil().getPlayerMessage(player, "word.mode-review")
        : WordModes.localizeDifficulty(plugin.getMessageUtil(), player, difficulty);
String title = ChatColor.translateAlternateColorCodes('&',
        MessageUtil.getGuiMsg(messages, "word-learning-gui.title")
                .replace("{difficulty}", difficultyLabel));
```
（`WordModes`、`MessageUtil` 已 import；translate 只动 & 码，label 内 § 不受影响。）**调用点全部不动**：三个入口（MiscGUIListener:167 banks 值、ChatAnswerListener:278-279 getDifficulty()、LearnCommand:260/379 规范化 key）继续传 raw key——GUI 内部消化，无接口变更；自定义词库名走 default 回落原值（与现状逐位一致）。

**i18n**：零新键（复用 word.difficulty-beginner/intermediate/advanced 与 word.mode-review，均已存在 zh:41-48 / en:41-48）。

**回归验证点**：①zh 玩家 /le word beginner → 标题"单词学习 - 初级"；/le review → "单词学习 - 复习"；②en 玩家 → "beginner"/"Review"（现 en label 值核对）；③自定义词库（如 mybank）→ 标题原样回落（不炸、不空值）；④GUI 其它格渲染/打开逻辑零变化。

**风险与交互**：`openWithMessage` 走 open()，一处修全入口覆盖；与 R11-M3 同文件族不同文件，无冲突。

---

## R11-M3 预载失败提示（两处 catch 补消息+音效）

**目标/根因**：`DailyChallengeGUI.openAsync` catch（锚 :79-83）与 `LearningCalendarGUI.openAsync` catch（锚 :93-98）在**异步线程段**打印日志后 return——主线程段不执行、界面永不打开、玩家零反馈（无消息无音效）。

**修法规格**（两文件同式；**关键：catch 在 runTaskAsynchronously 的异步段内，必须 runTask 回主线程再发**）：
```java
} catch (Exception e) {
    plugin.getLogger().log(Level.WARNING,
            "Failed to preload daily challenge GUI for " + playerId, e);   // calendar: "learning calendar data"
    // R11-M3：失败必有反馈 —— 异步段不得直调 Bukkit API，切主线程发提示+失败音。
    try {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily.load-failed"));
            SoundManager.playSound(player, "incorrect");
        });
    } catch (IllegalStateException ignored) {
        // 关服竞态：调度器已关闭，放弃提示（与同方法既有 openWithData 调度守卫同款）
    }
    return;
}
```
（两文件均需补 `import com.letslearnenglish.minecraftplugin.core.SoundManager;`——DailyChallengeGUI 当前未导入；LearningCalendarGUI 亦未导入，已核对。）

**i18n 新键**（daily 节风格核对：daily 节消息多不带 {prefix}，但失败类文案与 checkin 系带 {prefix} 更一致——取带 {prefix}）：
- `daily.load-failed`：zh `"{prefix}&c界面数据加载失败喵~ 请稍后再试一次"` / en `"{prefix}&cFailed to load the interface data. Please try again in a moment."`
两 GUI 共用一键（文案通用）；若编排者偏好分键可加 `calendar.load-failed` 别名，非必需。

**回归验证点**：①正常路径（DB 可用）逐位不变；②模拟 DB 异常（断库/reload 窗口）→ 玩家收 daily.load-failed + incorrect 音，日志 WARNING 照旧；③异步线程纪律：消息发送必在主线程（无 AsyncCatcher 报错）；④玩家在异步窗口内下线 → 主线程段 isOnline 静默短路。

**风险与交互**：`player.isOnline()` 在异步段读吗？——不，本修法只在主线程段读（异步段只用捕获的 `player` 对象引用做调度，不直调其 API）；与 R11-V1（领取后 openAsync 重开"拽回"待验证项）无关。

---

# 执行顺序、i18n 汇总与整合

**建议执行顺序**（并行度按文件白名单天然隔离；每条流内顺序为文件冲突序）：
1. **C1-A**（文件最多、影响面最大）：①WordLearningManager（R1-M1+R1-M2 三入口守卫同笔落，先 hasActiveQuiz 后 isPracticingWord）→ ②ChatScenarioListener（R1-M2 补 practice）→ ③WrongBookListener（R1-M2 入口守卫）→ ④LearnCommand（R1-M1 review null 分支 + R8-M1 两处 pushBack）→ ⑤MiscGUIListener（R11-M2 try/catch）→ ⑥CheckInManager + LearnCommand（R11-M4）。
2. **C1-C**（最短）：DuelManager（R5-M1 三处 + helper）→ DuelListener → DuelGUI + DuelGUIListener（R5V-1）。
3. **C1-B / C1-D** 与 C1-C 可全并行。

**i18n 汇总（编排者统一落 zh.yml + en.yml，编译前完成）**：
- 新增 4 键：`word.session-in-progress`（R1-M1/M2 共用）、`word.practice-in-progress`（R1-M2）、`checkin.storage-failed`（R11-M4）、`daily.load-failed`（R11-M3）。
- 修改 1 键：`dialogue.more-scenarios-lore`（R2-M1，加 /le dialogue 命令入口提示）。
- 复用既有键：`word.no-words-available`、`general.error`、`duel.finish-duel-first`、`word.mode-review`、`checkin.already`、`module.disabled`。

**CHANGELOG.md**：每流完工后按 1.0.7-SNAPSHOT 段落追加（一行/条），编排者统一落笔。

**编译门禁**：每流完成即 `mvn clean package -DskipTests`（BUILD SUCCESS 才能报告）；全部四流完成后由编排者跑 `mvn verify`（49/49 基线不回归）。

**与批 A/B 的邻近代码不冲突声明**：R5-M1 照抄 `selfCancelWordTimeout` 双参先例（:580-584，批 B 产物）——同式扩展不推翻；R11-M4 record 扩展保留双 back-compat ctor；R1-M1/M2 守卫置于 `module.disabled` 门之后（批 B 的模块门顺序保持）；R8-M1 pushBack 与 F3-02 笑话页修复（SocialListener 隐身路径不压帧，:456-458）无共享代码；`daily_word_reward_claims` 等批 B schema 面零触碰。

**回滚要点**：四流均无 DB/schema 变更、无配置键变更；i18n 新键为纯增量——单流回滚 = git revert 该流 commit，键残留无害。


