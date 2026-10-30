# fix-plan-d.md — 批 D（低危清扫）施工规划

**基线**：HEAD=`d5133da`（批 A/B/C1/C2 全部提交，mvn verify 49/49 全绿）。版本保持 1.0.7-SNAPSHOT。
**范围**：批 D = 全部剩余低危 28 条（已排除前批修复：R8-M1/R8-L2/R11-L4/R5-L1/R7-L5/R10-L3/R4-L4 及全部高危/中危）。
**纪律**：每个执行流只改本流白名单文件；禁跑 mvn（编排者统一 gate）；隔离 javac 自检；禁 touching yml（i18n 键由编排者统一落 zh/en）。

## A. 流划分与白名单

| 流 | 主题 | 白名单文件 | 条目 |
|----|------|-----------|------|
| D1 | 数据安全与持久化 | `data/DatabaseManager.java`、`data/PlayerDataStore.java`、`core/manager/DataExporter.java`（**实际路径 core/manager 非 data/**，C2 后无其他流触碰）、`core/DailyChallengeManager.java`、`config/ConfigManager.java`、`core/manager/StatisticsHttpServer.java`、`core/word/WordManagerImpl.java` | D1-1..D1-8 |
| D2 | 死代码与卫生清扫 | `core/word/AnswerChecker.java`、`core/word/LearningSession.java`、`core/word/WordLearningManager.java`、`core/progress/ProgressManagerImpl.java`、`core/manager/CheckInManager.java`、`util/MessageUtil.java`、`util/VersionAdapter.java`、`core/UpdateChecker.java`、`data/ChatFeedbackEntry.java`、`core/manager/AdminCommandHandler.java`、`core/ai/ChatFeedbackManager.java`、`core/ai/AIChatManager.java`、`core/ai/TtsManager.java`、`gui/ChatHistoryGUI.java`、`util/SchedulerGuard.java`（新建）、**修订补入**：`core/social/SocialManager.java`、`core/season/SeasonManager.java`、`core/manager/ReportGenerator.java`、`core/achievement/AchievementManager.java`（见 D2-5） | D2-1..D2-10 |
| D3 | 社交与 GUI 交互 | `listener/SocialListener.java`、`core/progress/Leaderboard.java`、`core/achievement/Achievement.java`、`gui/WordBankSelectGUI.java`、`gui/EnglishSquareGUI.java`、`listener/MiscGUIListener.java`、`gui/MainMenu.java`、`gui/ConfirmationGUI.java`、`listener/MainMenuListener.java`、`listener/PlayerJoinListener.java` | D3-1..D3-8 |
| D4 | 命令与配置 | `command/LearnCommand.java`、`command/AdminCommand.java`、`resources/config.yml`、`scripts/audit-i18n-keys.js` | D4-1..D4-6 |

**跨流冲突标注**：
- `ConfigManager.java` 同时被 D1-5（R10-L2 getGuiMaterial）与 D3-7（R8-L3 槽位校验）涉及 → **裁决：R8-L3 的 clamp 换位到 `MainMenu.java`（D3 白名单）内做**，ConfigManager 只归 D1。
- `PlayerJoinListener.java` 同时被 D3-8（R8-L1 ConfirmationGUI 清理）与 R7-L3（join 链异步合并，**不进批 D**）涉及 → R7-L3 本批不做，无冲突。
- `CheckInManager/MessageUtil` 在 D2；`gui-layout` 槽位校验收在 MainMenu → 无重叠。
- `command/LearnCommand.java` 于 D4-2（R11-L3 isAvailable 预检）与 D2-5（R13-L4 调度守卫迁移候选点之一 LearnCommand:837-843）→ **裁决：LearnCommand 的 SchedulerGuard 迁移归 D2 执行，D4 执行者若需触碰 LearnCommand 其他方法（handleDialogueCommand），两流串行：先 D2 后 D4**（或 D2 不迁 LearnCommand，改注释指向——见 D2-5 精确方案）。

## B. 每流条目规格

### D1 数据安全与持久化

**D1-1. R3-L2 cleanupOldChatHistory 参数化**（`DatabaseManager.java`，锚 :688-696 现漂至 ~:700 区域，以 `cleanupOldChatHistory` 方法名为锚）
- 改法：SQLite 分支 `... WHERE created_at < datetime('now', ?)` 绑 `"-" + daysToKeep + " days"`；MySQL 分支 `... WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)` 绑 `daysToKeep`（int）。保持单方法双后端 if 结构不变。
- 风险：极低。验证：`cleanupOldChatHistory` 既有调用点（30 常量）行为不变。

**D1-2. R3-L3 DataExporter escapeCsv 补 \r**（`core/manager/DataExporter.java`，锚 :98-104）
- 改法：判定条件 `value.contains(",") || value.contains("\"") || value.contains("\n")` 追加 `|| value.contains("\r")`（一行）。
- 注意：DataExporter 的包路径是 `core/manager/DataExporter.java`（非 `data/`），白名单已更正。

**D1-3. R3-L4 looksLikeDuplicateColumn 改元数据查询**（`DatabaseManager.java`，锚 :2735-2745+ 方法名锚）
- 改法：方法内改判"列是否已存在"：SQLite `PRAGMA table_info(<table>)` 遍历列名等值；MySQL `SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?`。存在 → 返回 true（跳过 ALTER）；SQLException（连接故障等）→ 记 WARNING 并**返回 true 保守跳过**（保持现状语义，不放大风险）；仅"列不存在"才返回 false 走 ALTER。
- 先读该方法全文（唯一必读点：其调用方签名与表名/列名参数形态）再改。
- 风险：中低（迁移路径）；验证：新库建表全链 + 已有库（列已在）不重复 ALTER。

**D1-4. R6-L1 countUnclaimedForDate conn 移入锁内**（`core/DailyChallengeManager.java`，锚 :871-874）
- 改法：照批 A R3-H3 范式 —— `synchronized (plugin.getDatabaseManager().getConnectionLock()) { Connection conn = plugin.getDatabaseManager().getConnection(); try (PreparedStatement ...) {...} }`，即 getConnection 调用移到 synchronized 块内首行。
- 风险：极低。验证：每日挑战领奖计数正常。

**D1-5. R10-L2 getGuiMaterial toUpperCase(Locale.ROOT)**（`ConfigManager.java`，锚 :389）
- 改法：`Material.valueOf(matName.toUpperCase(Locale.ROOT))`；确认文件已 import `java.util.Locale`（F3-03 排序侧已用过 toLowerCase(Locale.ROOT)，大概率已在）。
- 注意：本流唯一 ConfigManager 改动，D3-7 不碰此文件。

**D1-6. R13-L5 StatisticsHttpServer 密码告警 + 常数时间比较**（`core/manager/StatisticsHttpServer.java`，锚 :57、:196-213）
- 改法：① `start()` 起始处：`if (authPassword == null || authPassword.isEmpty() || "changeme".equals(authPassword))` → `plugin.getLogger().warning(...)` 提示修改 `statistics-server.auth.password`（不改行为，只告警）；② auth 比较改 `MessageDigest.isEqual(authUsername.getBytes(StandardCharsets.UTF_8), parts[0].getBytes(StandardCharsets.UTF_8)) && MessageDigest.isEqual(authPassword.getBytes(...), parts[1].getBytes(...))`（两字段都要）。
- 风险：极低；不改 fail-closed/白名单语义。验证：错误密码仍 401、正确密码仍 200、默认密码启动有 WARNING。

**D1-7. R13-L2 isPlayerLearning 接线 + saveDifficultWords 死 stub 删除**（`core/word/WordManagerImpl.java` + `data/PlayerDataStore.java`）
- 已核实：`PlayerDataStore.setPlayerInSession(UUID, boolean)`（:853）与 `isPlayerInSession`（:846）存在；`setPlayerInSession` 全仓仅定义处命中（零调用），`WordManagerImpl.startSession`（:111/:123）与 `endSession(UUID)`（:158）存在。`saveDifficultWords`（:573）全仓仅定义处命中（零调用，自述 no-op stub）。
- 改法：① `WordManagerImpl.startSession(...)` 末尾（session 建立点）调 `plugin.getPlayerDataStore().setPlayerInSession(player.getUniqueId(), true)`；`endSession(UUID playerId)` 内调 `setPlayerInSession(playerId, false)`（在清理完成处）；② 删除 `PlayerDataStore.saveDifficultWords`（:573 起整个方法体）+ 其 javadoc。
- 风险：低。验证：单测/运行期 API `isPlayerLearning` 在会话中 true、结束后 false；删 saveDifficultWords 后 `mvn compile` 过（先 re-grep 零调用）。

**D1-8. R13-L1（D1 域方法删除）**（`DatabaseManager.java` + `PlayerDataStore.java`）
- 已核实零调用（全仓 grep 仅定义处命中）：`DatabaseManager.getChatHistory`（:561）、`clearWrongCount`（:1378）、`resetCheckInStreak`（:1797）。
- `PlayerDataStore.getWordMastery` 已被批 C2-A 删除（现 grep 仅 `getChatHistoryCount` 等存活）→ **跳过，不重复执行**。
- 删除后连带检查：`getChatHistory` 删除若其 `ChatHistoryEntry` record / import 变为仅剩 `getChatHistoryCount` 使用则不动（先读该区域内其它方法再删，防连带死 import 编译错）。
- 风险：极低（纯死代码）；验证：隔离 javac + 编排者 mvn gate。

### D2 死代码与卫生清扫

**D2-1. R13-L1 死方法批删除**（逐方法执行，删除前必须 re-grep 零调用；以下为本轮已核实的定义锚）
- 删除清单（全仓 grep 仅定义处命中，已核实）：`AnswerChecker.isSpelling`（:280）、`LearningSession.getStartTime`（:168）、`WordLearningManager.getConsecutiveCorrect`（:700，findings 记 :661 已漂移）、`CheckInManager.getCheckInInfo`（:125）、`MessageUtil.hasMessageKey`（:224）、`VersionAdapter.getMaterial`（:46）/`getSound`（:59）/`getMajorVersion`（:91）/`getMinorVersion`（:98）、`UpdateChecker.isCheckComplete`（:199）/`getLatestVersion`（:203）、`ChatFeedbackEntry.getDifficultyKey`（:49）。
- `ProgressManagerImpl.getPlayerProgress`（:30）：删除该方法 + 若 `loadStreakData`（:40，private）仅被它调用则一并删（执行者 grep `loadStreakData` 确认）；**`PlayerProgress` 类保留**（Test `PlayerProgressTest` 引用，删类会破 49/49 基线门）。javadoc 注明"类现存仅测试引用，随测试生命周期维护"。
- 删除后检查各文件死 import（VersionAdapter/UpdateChecker 最可能）。
- i18n：无。风险：极低；验证：隔离 javac + 编排者 mvn gate 49/49。

**D2-2. R9-L6 AdminCommandHandler.addWord/removeWord 删除**（锚 :244-284）
- 删除两方法本体（F5-02 台账已裁"入口不可达零调用"）。删除前 re-grep `addWord(`/`removeWord(` 确认仅定义处。风险：极低。

**D2-3. R2-L1 裁决：全都保留，不删**
- `ChatFeedbackManager.unloadPlayer`（:261-264）：空方法保留（编排点形状一致性；findings 自认）——不加改动或仅在 javadoc 一行"经死代码审计豁免：编排点形状保留"。
- `AIChatManager.getRemainingCooldown`（:431）：**保留**——已核实它在 AIChatManager 内部有 2 个消费点（:449 守卫、:816 dispatchChat），属活跃方法（仅"对外"零消费），非死代码。零改动。

**D2-4. R13-L3 ChatFeedbackManager UPSERT 去重**（`core/ai/ChatFeedbackManager.java`，已实读 :217-236 与 :242-262 逐字重复）
- 改法：抽 `private Runnable writeTask(UUID playerId, AIChatManager.SessionSummary summary, String source, String payloadJson)`（内含现 lambda 体：isEnabled 守卫 + upsertChatFeedback 7 参 + WARNING 吞异常）；`persistAsync` 改 `CompletableFuture.runAsync(writeTask(...))`；`persistAfter` 改 `prerequisite.thenRunAsync(writeTask(...))`。
- 注意：`writeTask` 须在每次调用时新建 Runnable（同一实例不可跨 future 复用——无状态亦可，但保守起见 new）。风险：低；验证：两路径落库行为逐字不变。

**D2-5. R13-L4 SchedulerGuard 抽取（裁决：新建工具类 + 仅迁移 2 处 + 其余注释指向）**
- 已实读 6 处：「SocialManager :472-486（runAsync+runMain，最简姿态）」「SeasonManager :792-800（同型对）」「AdminCommandHandler :873-892（runClassAsync 带 fine 日志 + runOnMain 带 isEnabled 预检——语义更肥）」「ReportGenerator :1128+（runAsync）」「AchievementManager :246-250/:469-473（内联 try/catch，非帮助方法）」「LearnCommand :876-888（runTaskSafe + 内联）」。
- 精确方案：① 新建 `util/SchedulerGuard.java`：`public static void runAsync(Plugin plugin, Runnable task)` + `public static void runMain(Plugin plugin, Runnable task)`，体 = try 调度 / catch IllegalStateException 静默（照 SocialManager:472-486）；② 迁移**仅 2 处语义最简且同型**：SocialManager 与 SeasonManager —— 保留其 private runAsync/runMain 为**薄委托**（`SchedulerGuard.runAsync(plugin, task)`），零调用点改动（最小面）；③ 其余 4 处**不迁移**，各加一行注释"同型帮助器见 util.SchedulerGuard（语义微差：带 isEnabled 预检/日志级别），本批未迁移"——AdminCommandHandler（D2 白名单内）就地加；ReportGenerator 与 AchievementManager（**加入 D2 白名单**）各一行；LearnCommand 的注释归 D4 流执行（文件已归 D4，避免双流同文件）。
- 白名单修订：D2 增加 `core/social/SocialManager.java`、`core/season/SeasonManager.java`、`core/manager/ReportGenerator.java`、`core/achievement/AchievementManager.java`（均无其他流触碰）。
- 风险：低（迁移为薄委托不改语义；其余仅注释）。验证：红队重点——委托后 SocialManager/SeasonManager 关服竞态行为不变（javac + 代码级 diff 审读）。

**D2-6. R2-L3 裁决：注释契约方案（不派生重构）**
- 已核实消费面：`activePlayers` 全部 7 处引用（:37/:45/:59/:70/:93/:171/:183）均在 ChatScenarioListener 内部，且含顺序敏感语义（add 先于 startConversation、`remove` 作"曾在场"守卫）——派生重构需动 7 处 + 边界语义，成本 >5 处阈值，按裁决走注释方案。
- 改法：`activePlayers` 字段 javadoc 补漂移契约："本集合与 AIChatManager.sessions 成对写入（进入：LearnCommand ×2 / ScenarioSelectListener；退出：handleEndConversation）。任何新增写入点必须同时维护另一侧，否则改用 aiChatManager.hasActiveConversation(player) 派生。现三入口均成对，无漂移路径（R2-L3 审计记录）。" 零行为改动。

**D2-7. R2-L4 TtsManager 401/403 熔断照抄 AIChatManager R-20**（`core/ai/TtsManager.java`，锚 :161-165）
- 已实读 AIChatManager 机制：「:51 `AUTH_CIRCUIT_COOLDOWN_MS = 5*60*1000L`」「:1074-1076 `isAuthCircuitOpen() { return System.currentTimeMillis() < authCircuitOpenUntil; }`」「:1079-1084 `openAuthCircuit()` 置时间戳 + severe 日志」「成功 200 时 `authCircuitOpenUntil = 0L` 清除（:933）」。
- 改法（照抄同构）：① 加 `private static final long KEY_INVALID_COOLDOWN_MS = 5 * 60 * 1000L;` + `private volatile long keyInvalidUntil = 0L;`；② 请求入口（apiKey 判空处）追加 `|| System.currentTimeMillis() < keyInvalidUntil` 短路返回 completedFuture(null)；③ :161-165 分支改：`this.keyInvalidUntil = System.currentTimeMillis() + KEY_INVALID_COOLDOWN_MS;` + 日志改"N 分钟内暂停 TTS 下载，之后自动探测恢复"（**不再置 apiKey=null**，保留原 key 供到期探测）；④ 成功分支（200 落盘处）追加 `keyInvalidUntil = 0L`。触发码集保持 {400,401,403} 不改（最小面）。
- 风险：低。验证：红队重点——熔断后 5 分钟到期自动恢复（可临时改常量或 mock 时钟验证）；`apiKey=null` 旧语义全仓无依赖消费者（grep 确认）。

**D2-8. R2-L2 ChatHistoryGUI lore 折行防拆代理对**（`gui/ChatHistoryGUI.java`，锚 :326-345）
- 改法：循环尾端加代理对守卫：`int end = Math.min(pos + wrapLen, full.length()); if (end < full.length() && Character.isLowSurrogate(full.charAt(end))) end--;`（防在 high/low 代理对中间截断；wrapLen=40 语义与视觉宽度改进**明示超出本批范围**，注释注明）。
- 风险：极低。验证：含 emoji/生僻字的 AI 回复详情页不出现乱码方块。

**D2-9. R9-L5 applyClassAdd 幂等分支 sendOnMain 移出锁块**（`core/manager/AdminCommandHandler.java`，锚 :718 applyClassAdd）
- 改法：读方法内锁块；幂等分支（已是成员）目前锁内直调 sendOnMain → 改为锁内只置 `boolean alreadyMember = true`（或收集消息参数），`synchronized` 块退出后统一 `if (alreadyMember) sendOnMain(...)`。非幂等分支同理顺带（若也在锁内发消息）。
- 风险：低（并发卫生，无现行 bug）。验证：重复 add 同一玩家 → 仍收到"已在班级"提示。

**D2-10. R9-L3 resetPlayerData 末尾刷榜单缓存**（`core/manager/AdminCommandHandler.java`，已实读 :50-74）
- 改法：在 `:72` "reset complete" 日志前后、`return true` 之前追加 `plugin.getProgressManager().getLeaderboard().refreshCache();`（执行者确认 `LetsLearnEnglish.getProgressManager()` 访问器名——LetsLearnEnglish :225/:425 有同款调用先例）。缓存刷新为同步 DB 读，与本命令既有的主线程删表同量级，可接受。
- 风险：低。验证：reset 后立即 `/lea class list` 榜单分数即刻更新（不再等 5 分钟周期）。

### D3 社交与 GUI 交互

**D3-1. R4-L1 榜单头像点击改只读**（`listener/SocialListener.java`，锚 :583-591）
- 已核实：`getProfileSettingsReadOnly` 存在（PlayerDataStore:1005），全仓 10+ 消费点已用它。改法：:583-591 处 `getProfileSettings(targetId)` → `getProfileSettingsReadOnly(targetId)`（一行）。
- 同文件 :721 已是只读版（核实时所见），仅改 :583-591 一处。风险：极低。

**D3-2. R4-L2 getPlayerRank 并列稳定排序**（`core/progress/Leaderboard.java`，锚 :148-158）
- 改法：`sorted.sort(Map.Entry.<UUID, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))` —— secondary key = UUID（自然序，全序且稳定）。UUID 比较是可解释的确定性 tie-break；不引入"首上榜时间"（需新字段+持久化，超出低危面）。
- 同步检查：同文件其它 `comparingByValue()` 排序点（榜单展示 top-N）可选同款补 tie-break（若有一行超面则只改 getPlayerRank，注释说明）。
- 风险：极低；%lle_rank% 缓存窗口不动。验证：同分两玩家多次查询 rank 恒定。

**D3-3. R4-L3 SPELLING_MASTER 描述微调**（`core/achievement/Achievement.java`，锚 :100-107）
- 改法：enum 内 zh 描述 `"完成一次拼写模式的完整答题会话，手打单词不含糊！"` → `"完成一次拼写模式的答题会话（至少拼对 1 题），手打单词不含糊！"`；en 同步 `"Complete a full spelling-mode session — typing words like a pro!"` → `"Complete a spelling-mode session with at least 1 correct spelling — typing words like a pro!"`。
- 纯 enum 文案（非 yml，属于 achievement 枚举内嵌双语）；zh/en 均在同处改。风险：极低（无键面）。

**D3-4. R1-L1 WordBankSelectGUI 查键先原样后小写**（`gui/WordBankSelectGUI.java`，锚 :89-103）
- 改法：先 `messages.getString("word-bank.difficulty-" + bankKey)`，若 null 再 `"word-bank.difficulty-" + bankKey.toLowerCase(Locale.ROOT)`，仍 null 走原兜底（首字母大写化）。
- 风险：极低。验证：自定义词库 `MyBank.yml` + 配置 `difficulty-MyBank` 正确显示。

**D3-5. R11-L1 openHidden 标题补 {page}**（`gui/EnglishSquareGUI.java`，锚 :453-454）
- 改法：照 `open()`（:169）写法，对 `square.title` 结果做 `.replace("{page}", ...)`；openHidden 无分页语义，替换值为 "1"（或 "1/1"，取 open 同款格式——**执行者读 :169 的替换形态保持一致**）。
- 风险：极低。验证：隐身页标题无 `{page}` 残留。

**D3-6. R8-L4 MiscGUIListener 裸 contains → 配置等值/前缀锚定**（`listener/MiscGUIListener.java`，锚 :48-52 与 :333-337 两处逐字一致）
- 改法：照 `ConfirmationGUI.isConfirmGUI`（:63-74）配置等值先例：写一个私有静态 helper `matchesAnyTitle(String title, String... keys)`，对每个键按 zh/en 读 `getGuiMsg` + `&` 转换后做等值比对（这 5 类标题均为本插件自产、无截断——与 ConfirmationGUI 同理由）；两处调用点（点击 :48-52、拖拽 :333-337）替换为 `matchesAnyTitle(title, KEY1, KEY2, ...)`。
- 5 类标题键：Privacy Settings/隐私设置、Select Word Bank/选择词库、Study History/过往学习、Daily Challenge/每日挑战、Challenge Complete/挑战完成（执行者读两处白名单后与 yml 键逐一对照填 helper 参数）。
- 风险：中（判定面）——若某标题源有截断/占位符则等值不中。**执行者必须逐一确认这 5 个标题的开菜单侧无截断**（照 ConfirmationGUI javadoc 的论证方式）；若有截断的，该类回退"配置前缀锚定 + 长度下限"（照 MainMenuListener:90-104 先例）。

**D3-7. R8-L3 槽位校验落 MainMenu**（`gui/MainMenu.java`，锚 :43-48 size 校验块内扩展）
- 改法（R8-L3 的 clamp 换位到本文件，ConfigManager 不动）：在 `size` 校验块之后，加"槽位校验+fallback"块：
  ```java
  int[] slots = { cm.getGuiSlot("main-menu","word-learning",11), ... /* 9 个按钮槽位，与 :77-142 渲染清单逐一对应 */ };
  for (int s : slots) { if (s < 0 || s >= size) { warning(...); slotFallback -> 用默认槽位表 } }
  ```
  实现建议：定义 `int[] defaults = {11,12,13,14,15,16,17,18,...}`（执行者读 :77-142 取全 9 对键+默认值），任何越界/Bukkit 非法即整组回落默认表 + WARNING。
- **同槽重复告警**：可选——排序后相邻相等则 WARNING（不阻止，仅提示）。若实现复杂可省（findings 次要面）。
- 点击侧（MainMenuListener）不动：渲染与点击同读配置，渲染侧 facade 回落后仍会漂移（点击侧读原配置）——**裁决：本批只保不崩（越界守卫）+ 告警**；点击侧错位在配置合法时不存在，配置非法时属管理员自担（WARNING 已明示）。执行者勿扩面到 MainMenuListener 槽位表。
- 风险：中低；验证：故意配 slot=99 开菜单不崩、WARNING 出现、按钮回落默认位。

**D3-8. R8-L1 quit 清理 confirmStages**（`listener/PlayerJoinListener.java` quit 编排点 + `gui/ConfirmationGUI.java`）
- 已核实：`ConfirmationGUI.resetStage(Player)`（:81-83）存在，confirmStages 为静态 map。改法：`onPlayerQuit` 现清理清单（R-41 的 `clearPlayerNavigation(playerId)` 一带）追加 `ConfirmationGUI.resetStage(player);`（quit 事件有 Player 对象；重置静态 map 条目）。
- 风险：极低。验证：quit 后重登，语言确认流程从阶段 0 开始。

### D4 命令与配置

**D4-1. R9-L2 /lea reset 两段式确认**（`command/AdminCommand.java`，已实读 :103-110 case "reset" 与 :162-174 handleReset）
- 现状：`case "reset": if (args.length > 1) handleReset(sender, args[1]); else 提示 admin.usage-reset`。
- 改法（**裁决：控制台同式，不豁免**——统一路径代码最简，脚本自动化追加 `confirm` 即可）：case "reset" 分支改三段：① `args.length == 2` → 发 `admin.reset-confirm`（占位符 player=args[1]），**不执行**；② `args.length > 2 && "confirm".equalsIgnoreCase(args[2])` → `handleReset(sender, args[1])`；③ 其余（args.length > 2 但第三参非 confirm）→ 重发 `admin.reset-confirm`（防 `/lea reset X y` 手误静默）。
- handleReset 本体零改动（R-86h 未找到玩家逻辑保持）。
- 新键：`admin.reset-confirm`（编排者落 zh/en，草案见 E 节）。
- 风险：低（行为面变更，admin-only 面）；验证：`/lea reset X` 只提示不删；`/lea reset X confirm` 执行；红队重点验。

**D4-2. R11-L3 /le dialogue 命令路径补 isAvailable 预检**（`command/LearnCommand.java`，已实读 :296-350）
- 改法：handleDialogueCommand 内、场景校验（:314-318）之后、addPlayer（:341）之前插入照 `ScenarioSelectListener:121-129` 同款预检：`if (!plugin.getAIChatManager().isAvailable()) { player.sendMessage(... "dialogue.ai-not-configured"); return; }`（命令侧无 GUI/音效面，仅消息+return）。
- 风险：极低。验证：未配 Key 时 `/le dialogue restaurant` 立即拒（不再"已开启"后被每条消息打脸）。

**D4-3. R9-L7 admin-help 补 class 行**（`command/AdminCommand.java`，SUBCOMMANDS :33-36、sendHelpMessage :263-288）
- 改法：sendHelpMessage 增一行读 `admin-help.class`（放与 SUBCOMMANDS 顺序对齐处，执行者按 :33-36 顺序插位）；核对合计 12 行 = SUBCOMMANDS 12 项。新键 `admin-help.class`（编排者落 zh/en）。
- 风险：极低。验证：`/lea help` 出现 class 行；i18n 审计脚本过。

**D4-4. R7-L2 config.yml season 节补 duration 提示注释**（`resources/config.yml`，锚 :667-673）
- 改法：在 duration-days / anchor-date 处补注释："修改 duration-days/anchor-date 会使已建立的赛季行与现推导窗口不一致，可能提前/跳过结算；多子服必须同步修改并同时 reload。"
- 注意：config.yml 归 D4 白名单；config-version 本批 17（编排者统一）。
- 风险：零（注释）。验证：YAML 可解析（编排者 build gate 覆盖）。

**D4-5. R13-L6 裁决：删除 general.debug 死键**（`resources/config.yml`，general 段）
- 改法：删除该键 + 其注释行（grep `general.debug` 全仓零读取已复核；历史 dead-config-audit 已记录此裁决）。若键带注释块则整块删。
- 风险：零。验证：删除后无任何读取断言（本就零读取）。

**D4-6. R10-L5 audit-i18n-keys.js 修正**（`scripts/audit-i18n-keys.js`，锚 :5、:45-48）
- 改法：① `sections` 白名单补 `season`/`class`（或改"取 yml 顶层键集合"动态生成——二者择一，优先补齐白名单最小面）；② 键扫描正则命中以 `-` 或 `.` 结尾的键 → 跳过并计入"疑似拼接键"计数输出（消除 `achievement-wall.category-` 假阳性）。
- 风险：零（脚本）。验证：重跑脚本无该假阳性；基线 521 键判定不回退；season/class 键纳入检查后无新增真缺失。

## C. 执行者纪律模板（每流复制，照 C1/C2 先例精简）

```
你是【批 D · Dx 流执行者】。仓库根：D:\Server\MCServers\Server plugin\Let's learn English!\Minecraft-server-plugin-Let-s-learn-English--1
计划：tasks/review-2026-10-18/fix-plan-d.md（读你流条目 + 附录 A 白名单）。
1. 只改白名单文件；一条不越界。改动前对每个删除/替换目标 re-grep 零调用/调用点（findings 已给，但行号漂移 → 以方法名为锚）。
2. 禁跑 mvn（编排者统一 gate）；禁改任何 .yml（i18n 键由编排者落）；禁 git 操作；禁动 CHANGELOG。
3. 自检：改动文件用隔离 javac（classpath=mvn 已拉依赖的 target/classes + 依赖 jar）逐个编译；或至少 Read 改动区确认语法。
4. 每个条目完成后按表回报：条目号 | 状态(完成/跳过+原因) | 改动文件:方法锚 | 删除清单实际命中数(期望1=仅定义) | 遗留风险。
5. 全部条目回报后停止，等编排者 gate。
```

## D. 验证重点清单（红队必须重点验）

1. **D2-5 SchedulerGuard 迁移**（SocialManager/SeasonManager 薄委托）：关服竞态语义零变更（委托不改 try/catch 时机与日志）；新建 util 类 import 全对。
2. **D2-7 TtsManager 熔断**：401/403 后 5 分钟窗口内不打 API、到期自动恢复探测；成功 200 清熔断；apiKey 不再被置 null（grep 全仓无 `apiKey == null` 语义依赖）。
3. **D3-6 MiscGUIListener 判定替换**：5 类标题逐一实开 GUI 验证点击/拖拽均被捕获（等值判定对占位符/截断敏感——若某标题实开侧有截断会漏判，红队实开测）。
4. **D4-1 reset 两段式**：`/lea reset X` 不删数据只提示；`/lea reset X confirm` 执行；`/lea reset X garbage` 不执行；控制台同式。
5. **D1-7 isPlayerLearning 接线**：会话开始/结束/quit 三路径 API 值正确（quit 走 endSession 或 unload 路径需确认都会置 false——**执行者读 quit 链确认 endSession 被调；若 quit 绕过 endSession 则在 PlayerJoinListener/卸载链补置 false**）。
6. **D2-1 死代码批**：删除后 49/49 单测全绿（PlayerProgress 类必须保留——测试引用）；各文件无死 import 编译错。
7. **D1-3 looksLikeDuplicateColumn**：双后端迁移路径（SQLite 实测建列；MySQL 静态审读）。
8. **D3-7 MainMenu 槽位守卫**：非法 slot 配置不崩、WARNING、默认回落。

## E. 新 i18n 键汇总（编排者统一落 zh/en，执行者禁碰 yml）

| 键 | zh 草案 | en 草案 | 用途 |
|----|---------|---------|------|
| `admin.reset-confirm` | `&c危险：这将删除玩家 &e{player} &c的全部数据（15 张表）。确认请再执行 &e/lea reset {player} confirm` | `&cDANGER: This deletes ALL data for &e{player} &c(15 tables). To confirm, run &e/lea reset {player} confirm` | D4-1 两段式确认 |
| `admin-help.class` | `&7/lea class <create|add|remove|list|delete> ... &8- &7班级管理` | `&7/lea class <create|add|remove|list|delete> ... &8- &7Class management` | D4-3 help 行（执行者按 AdminCommand SUBCOMMANDS :33-36 实际子命令形态微调文案） |

**改值（非新键）**：R11-L2 决斗邀请文案删"点击这里"（编排者 fan-in，已登记）；D3-3 Achievement enum 内嵌双语（非 yml，执行者改）。**

**收敛**：本批新键仅 2 个 + 1 处文案改值 + 1 处 enum 内嵌文案；config-version → 17（编排者）。zh/en 对称性 + audit-i18n-keys.js 由编排者在 gate 前跑。
