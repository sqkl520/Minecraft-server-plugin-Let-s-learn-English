# findings-r2 AI 对话链（审查 Agent 产出）
- 审查范围：`core/ai/` 全部（AIChatManager, ChatFeedbackManager, ConversationFeedback, PersonalScenarioStore, ScenarioCodec, TtsManager）+ `listener/ChatScenarioListener` + `listener/ChatHistoryListener` + `gui/ChatHistoryGUI` + `gui/ScenarioSelectGUI` + `listener/ScenarioSelectListener` + `gui/PersonalScenarioGUI` + `listener/PersonalScenarioListener` + `resources/ai_scenarios.yml`（含对 LearnCommand/PlayerDataStore/PlayerJoinListener/MainMenuListener/ChatAnswerListener/LetsLearnEnglish 的只读跨文件核证）
- 审查时间：2026-10-18
- 结论摘要：高2条（定稿 1 条）/ 中4条 / 低4条 / 待验证4条 / 豁免挑战0条
- 纪律说明：本 Agent 在审查中做了大量"先立案→自我复核→撤回"的过程（撤回误报 6 条：ChatHistoryGUI 分页/checkpoint 分页标记、TtsManager 线程面、个人场景删除二确绕过、quit 双路径竞态、importPersonalScenario 覆盖、ScenarioCodec 注入、"更多"计数公式）——撤回过程体现"先证实再立案"纪律，以下为经复核的最终定稿。
- 备注：本文件由编排者按该 agent 返回全文原样保存（harness 禁止子代理写文件）

## 高危

### R2-H1 [thread·高·确定] dispatchChat 成功分支在异步线程直调 `insertChatHistory`（未经 `runAsync` 包裹，与同文件 user 写入路径不一致）
- 位置：
  - `core/ai/AIChatManager.java:960-967`（assistant 写入：直调，在 supplyAsync 链内）
  - 对照 `core/ai/AIChatManager.java:897-907`（user 写入：正确包了 `CompletableFuture.runAsync`）
- 证据：
  ```java
  // :897-907 写入 user 消息 —— 有 runAsync 包裹（正确）：
  if (sessionId != null) {
      CompletableFuture.runAsync(() -> {
          if (isShuttingDownOrDisabled()) return;
          try {
              plugin.getDatabaseManager().insertChatHistory(playerId, sessionId, "user", finalSanitized);
          } catch (Exception e) { ... }
      });
  }

  // :959-967 写入 assistant 回复 —— 无 runAsync，直接在这个 supplyAsync 的线程执行：
  // Write AI response to DB asynchronously (sessionId captured from enclosing scope)
  if (sessionId != null) {
      try {
          plugin.getDatabaseManager().insertChatHistory(playerId, sessionId, "assistant", response);
      } catch (Exception e) {
          // DB write failure should not affect the chat flow
          debugLog(...);
      }
  }
  ```
- 线程解剖（追溯链）：`chatAsync` ← `ChatScenarioListener.onPlayerChat`（AsyncPlayerChatEvent，Async Chat Thread）→ `dispatchChat` 内 `CompletableFuture.supplyAsync(...)`（ForkJoinPool.commonPool 线程）→ 成功分支到这里仍是 commonPool 线程。`DatabaseManager.insertChatHistory` 内部走 `synchronized (connectionLock)` + 单连接 JDBC 写——不是 Bukkit API，但它是**一处与同方法内 user 写入路径不对称的线程纪律破例**：同一函数对两条写库用了两种线程模型，且此调用点未再守 `isShuttingDownOrDisabled`（:924 有短路但这里没有）。风险实质：commonPool 线程持 `connectionLock` 做 JDBC 写时，主线程（如 GUI 打开查 `getChatSession`、join 时 `loadPlayerData`）会被阻塞在锁上——主线程阻塞（卡服）判据成立时点=DB 慢（SQLite 写放大/被外部进程锁库）。
- 复现：单人服务器，玩家在 AI 对话中每轮回复到达时，若同时有另一玩家触发 `loadPlayerData`（join）或打开 ChatHistoryGUI（主线程查库），两者的 DB 访问经同一 `connectionLock` 串行；只需 DB 写耗时被放大（备份任务、杀毒扫描、机械盘）即可观察到主线程 TPS 尖峰。
- 影响：极端时点主线程卡顿（取决于写耗时）；线程模型不对称属确定性事实。
- 修法：把 :960-967 的插入包进 `CompletableFuture.runAsync(() -> { if (isShuttingDownOrDisabled()) return; try { ... } catch ... })`，与 :897-907 完全同形。
- 置信度：[确定]（代码路径与线程来源均可从源码直接判读；"是否构成玩家可感卡顿"取决于 DB 耗时，但纪律破例本身确定）
- 编排者注：本条归类为"线程纪律不对称"，DB 访问在 connectionLock 保护下无数据竞争；建议汇总时与 R13 全图结论（"31 个异步点全部包 runTask/仅 DB"）合并评估，严重度可商榷为中-高。

## 中危

### R2-M1 [logic·中·确定] `ScenarioSelectGUI` 场景超过 7 个时，"更多…"占用尾槽导致**第 7 个可见场景不可点击打开**（R-49 截断语义与玩家预期不符：7 槽位 → hasMore 时仅前 6 个可开）
- 位置：`gui/ScenarioSelectGUI.java:91-94`（渲染截断）与 `listener/ScenarioSelectListener.java:107-119`（点击截断）
- 证据：
  ```java
  // ScenarioSelectGUI.java:91-94 —— 渲染：超 7 时只画前 6 个（subList(0, SCENARIO_SLOTS.length - 1)）
  List<ScenarioConfig> shownScenarios = sortedScenarios.size() > SCENARIO_SLOTS.length
          ? sortedScenarios.subList(0, SCENARIO_SLOTS.length - 1)
          : sortedScenarios;
  boolean hasMoreScenarios = sortedScenarios.size() > SCENARIO_SLOTS.length;
  // :117-123 —— 尾槽（slot 16）画"更多…"提示替代第 7 个场景
  if (hasMoreScenarios) {
      gui.setItem(SCENARIO_SLOTS[SCENARIO_SLOTS.length - 1], createItem(Material.BOOK, ...));
  }
  ```
  点击侧同一截断（`shownCount = ScenarioSelectGUI.SCENARIO_SLOT_COUNT - 1`）。
  个人场景上限 30 个（`PersonalScenarioStore.MAX_PERSONAL_SCENARIOS`），全局默认 6 场景（ai_scenarios.yml）→ 玩家导入第 2 个个人场景即进入 hasMore 状态：**只要场景总数 ≥ 8，则按排序第 7 位的场景既不出现在 GUI 也无法点击**（`/le dialogue <key>` 命令入口仍可达，故非死路，但 GUI 玩家不可见且无翻页）。
- 复现：`/le load_scenario <code>` 导入 ≥2 个个人场景（或服务端配置 8+ 全局场景）→ `/le dialogue` 打开场景选择页 → 排序第 7+ 位的场景不可见（"更多…"提示 4 个被折叠，但玩家无任何方法在 GUI 里打开它们），只能猜 `/le dialogue <key>`。
- 影响：功能可见性断裂——玩家导入的场景"消失"（实际在排序后段被折叠）；"更多…"文案若未说明命令入口即为死路提示。
- 修法：短期在 `dialogue.more-scenarios-lore` 文案中明示 `/le dialogue <场景名>` 命令入口（若文案已含则降为低危文档向）；正解是加翻页（GUI 已有槽位空闲可放翻页箭头）。
- 置信度：[确定]

### R2-M2 [data·中·确定] 今日 AI 轮次计数器重启即清零 —— daily-round-limit 可经"重启/换服"绕过，与 config.yml 承诺"次日自动清零"及跨服场景不符
- 位置：`data/PlayerDataStore.java:58-62, 145-155`（内存 TodayCounter）+ `core/ai/AIChatManager.java:953`（消费点）
- 证据：
  ```java
  // PlayerDataStore.java:58-62 注释自认语义：
  // 重启/关服丢失是可接受的：这是防刷上限而非账本，账本以 DB 的 lifetime 字段为准。
  private final Map<UUID, TodayCounter> todayAiRoundCache;
  // AIChatManager.java:953 —— 成功回复时只写内存计数器：
  plugin.getPlayerDataStore().incrementTodayAiRound(playerId);
  ```
  无任何 DB 落库（对比 `incrementConversationCount` 是 `databaseManager.setConversationCount` 即时落库）。
- 复现：玩家当日打满 30 轮 → 等服务器重启（或玩家在 Velocity 双服间切换子服，各自内存独立）→ 轮次计数归零 → 继续无限对话。
- 影响：`ai-chat.daily-round-limit`（默认 30）作为"防 API 滥用/烧钱"上限被绕过——直接成本面（API 账单）。
- 修法：把 `TodayCounter` 日期+计数落 `player_data`，或至少接受现状但在 config.yml 注释明示"重启清零、跨服不共享"（现注释写"次日自动清零"，遗漏重启口径）。
- 置信度：[确定]（内存结构无持久化路径；"重启可绕过"为直接推论）
- 编排者注：与 F1-22（todayCoinsCache 裁决"防刷上限非账本，可丢"）同族——但本条是 API 成本面，建议按同一口径裁决或明确接受。

### R2-M3 [ux·中·确定] `ChatFeedbackManager.handleSummaryResult` 迟到的主线程回调会把上一会话的反馈块发给已开新会话的玩家（跨会话反馈串台）
- 位置：`core/ai/ChatFeedbackManager.java:150-159`（回调消费）+ `core/ai/AIChatManager.java:1195-1212`（回调发出点）
- 证据：
  ```java
  // AIChatManager.requestConversationSummary 的 thenAcceptAsync 内（主线程回调）：
  ConversationFeedback feedback = ConversationFeedback.parse(raw);
  ...
  callback.accept(feedback, null);   // ← 无"玩家是否已开新会话/已下线"检查
  ```
  对比 `dispatchChat` 的对话回调有 R-17 会话隔离检查（`sessionId.equals(current.sessionId)`），**总结回调链没有等价检查**。链路：玩家 A 结束会话收到占位 `ai-pending` → 立即 `/le dialogue` 开新会话（或已下线后重连）→ 15 秒超时内总结返回 → 主线程回调 `handleSummaryResult` → 向玩家发送上一会话的 `ai-header`/`ai-comment`/纠错块——玩家在新会话进行中收到上一场点评，与当前 `ai-typing` 等消息交织。
- 复现：结束一场达标对话（等 `ai-pending` 占位出现）→ 立即开新会话并发一条消息（AI 请求在途）→ 上一场总结返回 → 观察聊天框两场内容交织。
- 影响：体验断裂（消息串台、玩家困惑）；若玩家已下线，消息静默丢失——可接受；在线串台为实际可见问题。
- 修法：回调时校验 `summary.sessionId()` 是否为玩家**最近一场**（可在 ChatFeedbackManager 记录 lastEndedSession 比对），不匹配则静默丢弃。
- 置信度：[高]

### R2-M4 [logic·中·确定] `/le dialogue <场景>` 直启命令路径**无答题/决斗守卫顺序反转**：命令先查决斗再查答题（正序正确），但 GUI 路径 (`ScenarioSelectListener.handleScenarioClick`) 只查答题**不查决斗**——决斗中的玩家可从主菜单进场景选择页开 AI 对话，与决斗答题输入形成双消费竞争
- 位置：`listener/ScenarioSelectListener.java:131-138`（只查答题）vs `command/LearnCommand.java:313-326`（决斗+答题+已有会话三查）
- 证据：
  ```java
  // ScenarioSelectListener.java:131-138 —— 仅答题两查，无 isInDuel：
  if (plugin.getWordLearningManager().hasActiveQuiz(player.getUniqueId())
          || plugin.getWordLearningManager().isPracticingWord(player.getUniqueId())) {
      player.closeInventory();
      player.sendMessage(... "dialogue.finish-quiz-first");
      return;
  }
  // LearnCommand.java:313-318 —— 命令侧有三查（含决斗最优先）：
  if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
      player.sendMessage(... "duel.finish-duel-first");
      return;
  }
  ```
  ChatScenarioListener:77-80 的 R-66 纵深防御会在消息层把决斗中的消息让给决斗路径——但此时玩家已"进入"AI 对话模式（addPlayer + startConversation 已执行、开场白 API 已发），自己发的消息全被决斗吃掉，AI 开场白到达后也无人回应；玩家困在"AI 对话模式 + 决斗模式"双模式直至 bye/对决结束。
- 复现：玩家 A 发起决斗并被 B 接受（进入 active 答题态）→ A 打开主菜单 → 情景对话 → 点击任一场景（GUI 检查只查答题不查决斗）→ 进入 AI 对话 → 发消息被决斗消费。
- 影响：双模式困局（AI 对话占位挂起、消息被吞）。
- 修法：`ScenarioSelectListener.handleScenarioClick` 补决斗检查（与 LearnCommand 完全对齐）。
- 置信度：[高]（GUI 侧缺检查为确定）

## 低危

### R2-L1 [deadcode·低·确定] `ChatFeedbackManager.unloadPlayer` 为空方法（保留方法维持形状一致，注释自认"当前无自持 map"）；`AIChatManager.getRemainingCooldown` public 但对外零消费——属预留扩展点
- 位置：`core/ai/ChatFeedbackManager.java:261-264`、`core/ai/AIChatManager.java:431-436`
- 修法：保留（编排点形状一致性有维护价值）或在 R13 死代码审计中显式豁免登记。

### R2-L2 [i18n·低·确定] ChatHistoryGUI 详情页 lore 里 40 字符硬折行（wrapLen = 40），中文内容按 UTF-16 单元切分——不产生异常但不按视觉宽度折行
- 位置：`gui/ChatHistoryGUI.java:326-345`
- 证据：
  ```java
  int wrapLen = 40;
  int pos = 0;
  while (pos < full.length()) {
      int end = Math.min(pos + wrapLen, full.length());
      lore.add(ChatColor.WHITE + full.substring(pos, end));
      pos = end;
  }
  ```
- 影响：仅有轻微显示不齐；不影响数据。修法：可接受现状（低优先）。

### R2-L3 [quality·低·确定] `ChatScenarioListener` 双 `activePlayers`/`sessions` 状态源需手工保持同步——现无漂移路径（三入口均成对），但属未来维护陷阱
- 位置：`listener/ChatScenarioListener.java:37` + `core/ai/AIChatManager.java:118`
- 修法：可选重构：activePlayers 直接由 `sessions.containsKey` 派生。
- 置信度：[确定]（现状无 bug 也是确定的——纯 quality 记录）

### R2-L4 [logic·低·确定] `TtsManager` API Key 失效（400/401/403）后 `apiKey = null` 永久失效至重启/reload，无窗口恢复；对比 AIChatManager 的 401/403 熔断 5 分钟策略不一致
- 位置：`core/ai/TtsManager.java:161-165`
- 证据：
  ```java
  } else if (responseCode == 400 || responseCode == 401 || responseCode == 403) {
      plugin.getLogger().warning("TTS VoiceRSS API returned " + responseCode
              + " — API key may be invalid. Disabling audio downloads for this session.");
      this.apiKey = null;
  ```
- 复现：VoiceRSS 账户临时超限（返回 403）→ 被解读为"Key 永久无效"→ 服务器整个生命周期内 TTS 无音频。
- 修法：对齐 AIChatManager 熔断模式（记 keyInvalidUntil 时间戳），或仅在 401/403 永久化、400 视为单次失败。
- 置信度：[确定]（恢复面是否可经 reload 取决于 HotReload 覆盖面——列待验证）

## 待验证区

- R2-V1 [thread] ChatHistoryGUI 反馈物品渲染时 `getFeedback` 的时序——本地行由 `persistAsync` 异步落库，玩家若在 `ai-pending` 占位后立即开历史详情页，本地行可能尚未落库（渲染为留白而非 no-ai 行）；需实机复现竞态窗口。
- R2-V2 [i18n] `ai_scenarios.yml`（资源文件）场景 key vs `loadBuiltinScenarios` 硬编码 6 key 的逐项比对未完成；验证方法：diff 资源文件 scenarios 段 key 与 :391 scenarioKeys 数组。
- R2-V3 [logic] `HotReloadManager` 对 TTS/AIChatManager 的 reload 覆盖面：TtsManager 无 reload 方法（apiKey 只在构造读入）——推测 `/lea reload` 不恢复 TTS（R2-L4 影响面放大）；验证方法：读 HotReloadManager 确认。
- R2-V4 [data] `chat_history` 30 天清理任务与 `chat_feedback` 表的联动（R3 缝线）：`cleanupOldChatHistory(30)` 是否同时清 `chat_feedback` 未核——若否，孤儿数据增长（不影响正确性）；归 R3 主责。

## 豁免挑战
（无——E 节豁免清单在 R2 域内核对后未发现错误豁免。备注：E2（ChatHistoryGUI default 分支）复核确认不可达；E20（个人场景 30+ 截断）确认与实现一致。）

## 范围外发现
1. [归 R8] `/le dialogue` 无参路径（LearnCommand:342）未检查答题/决斗态即打开场景选择 GUI（GUI 打开本身无害，但顺序上依赖点击时检查）——已在 R2-M4 覆盖点击侧。
2. [归 R3] `PlayerDataStore.TodayCounter` 重启清零的**答案计数侧**同问题（`todayAnswerCountCache`）——请 R3/汇总去重。
3. [归 R10] `LetsLearnEnglish.onEnable:204-206` TTS 缓存清理内 `cacheDir.listFiles` 是文件 IO 且无 `isEnabled` 守卫——启动期安全，reload 面未核。
4. [归 R12] `ScenarioSelectGUI` 无翻页但按钮槽位有空闲——能力缺口非 bug。
5. [归 R5] 决斗进行中的玩家可经主菜单进 AI 对话（R2-M4）——R5 侧可查"决斗中 GUI 门是否应全局收紧"。

## 回归核验（本域）
- **D16 回归（bye 切主线程+守卫）核证通过**：`ChatScenarioListener:112-114` 仍切主线程 + `handleEndConversation:182` 的 `activePlayers.remove` 原子守卫防重复结算——D16 保持正确。
- ChatHistoryGUI 引擎侧（翻页/点开/返回）链路已全量核证无附带 bug（分页 clamp、PDC 页码、binarySearch、详情页返回页全链路自洽）。

## 审查覆盖声明
- **全量精读**（13/14 文件）：AIChatManager（1508 行全量）、ChatFeedbackManager（302 全量）、ConversationFeedback（167 全量）、PersonalScenarioStore（331 全量）、ScenarioCodec（42 全量）、TtsManager（326 全量）、ChatScenarioListener（208 全量）、ChatHistoryListener（178 全量）、ChatHistoryGUI（505 全量）、ScenarioSelectGUI（224 全量）、ScenarioSelectListener（239 全量）、PersonalScenarioGUI（270 全量）、PersonalScenarioListener（188 全量）；`resources/ai_scenarios.yml`——**未读文件本体**，仅经代码加载路径推断（见 V2）。
- **定向核证**：LearnCommand（265-344、900-1061、tab 补全局部）、PlayerDataStore（56-188、817-826）、DatabaseManager（561-682 部分）、MainMenuListener（100-159）、PlayerJoinListener（85-130）、ChatAnswerListener（95-214）、LetsLearnEnglish（175、192-221、320-340 局部）、config.yml（168-203 局部）、zh.yml+en.yml（关键键 grep 核证）。
- **未完成清单**（诚实申报）：①ai_scenarios.yml 逐 key 比对（V2）②HotReloadManager TTS/AI 覆盖面（V3）③ChatFeedback 与 chat_history 30 天清理联动（V4，R3 主责）④ChatHistoryGUI.renderFeedbackItem 时序窗口实机验证（V1）⑤TtsManager 调用点（WordLearningListener:202-246）的 thenAccept 消费面未精读（初步判定安全）。
