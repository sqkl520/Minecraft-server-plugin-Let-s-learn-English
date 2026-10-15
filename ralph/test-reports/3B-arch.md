# 架构报告 3B（Phase 3 · Wave 3 · Batch 3B — US-FB AI 反馈闭环）

### 判定：PASS

四个维度（分层正确性 / 单一职责 / DI 完整性 / 事件注册）**零硬违规**。冻结接口全命中。
下列问题为 WARN 级改进项，不阻塞发布。

审查范围（只读）：
`core/ai/ChatFeedbackManager.java`(新，251 行) · `core/ai/ConversationFeedback.java`(新，167 行) ·
`core/ai/AIChatManager.java`(改，1506 行) · `data/ChatFeedbackEntry.java`(新，58 行) ·
`data/DatabaseManager.java`(改，新表方法) · `gui/ChatHistoryGUI.java`(改，新增 renderFeedbackItem) ·
`listener/ChatScenarioListener.java`(改) · `listener/PlayerJoinListener.java`(改) ·
`command/LearnCommand.java`(改) · `LetsLearnEnglish.java`(DI) · `resources/schema.sql` ·
`src/test/.../ConversationFeedbackTest.java`

---

## 一、逐维度检查结果

### 1. 分层合规 —— PASS

**新增点无越层**

| 调用点 | 方向 | 结论 |
|---|---|---|
| `ChatHistoryGUI.java:358-359` `plugin.getChatFeedbackManager().getFeedback(uuid, sessionId)` | GUI → Core 门面 | ✓ 走门面，非直调 Data |
| `ChatHistoryGUI.java:123/130/148/273` 直调 `getDatabaseManager()` | GUI → Data | 既有 P2-27 四点，**本批未触碰、未加深**（renderFeedbackItem 未新增任何 DB 直调） |
| `ChatScenarioListener.java:174/190` `getChatFeedbackManager().persistOnQuit/deliverEnd` | Listener → Core | ✓ |
| `PlayerJoinListener.java:129` `getChatFeedbackManager().unloadPlayer` | Listener → Core | ✓ |
| `LearnCommand.java:289` → `getChatScenarioListener().removePlayer` | Command → Listener | ✓（沿声明链 API→Command→Listener→Core，命令侧不直接持 ChatFeedbackManager） |
| `ChatFeedbackManager.java:191/207` → `plugin.getDatabaseManager()` | Core → Data | ✓ 下行合法 |
| ChatFeedbackManager 无任何 HTTP 栈 | — | ✓ HTTP 单源在 AIChatManager（本类 import 无 `java.net.http`/`HttpClient`） |

全仓 `getDatabaseManager()` 在 gui/listener/command 的命中点仅：ChatHistoryGUI 四点（既有 P2-27）、
EnglishSquareGUI:272（既有）。**本批新增代码零新增越层点**，plan §2.2 / §3.5 门面约定命中。

### 2. 依赖方向无环 —— PASS

- **ChatFeedbackManager → AIChatManager 单向**：`ChatFeedbackManager.java:29,31-34` 字段 + 构造注入；
  反向核查 AIChatManager 全文对 ChatFeedbackManager 的引用**仅出现在 javadoc 注释**（:187/:1123/:1136/:1233），
  **零代码引用** → 无环。
- **DI 构造顺序合理**：`LetsLearnEnglish.java:168` `new AIChatManager(this)` →
  `:169` `new ChatFeedbackManager(this, aiChatManager)`。ChatFeedbackManager 构造体仅存引用、不做任何调用，
  AIChatManager 在传入时已就绪 ✓。
- **PlayerJoinListener → 服务单向**：`onPlayerQuit` 只出向调用（unloadPlayerData/unloadPlayer/
  clearPendingSummary/unloadPlayer），无服务反向引用 Listener，不成环 ✓（与 S5 quit 编排点既有姿态一致）。
- **DatabaseManager 无反向引用 Core**：全文件无 `core.ai.*` import、无 ChatFeedbackManager/ConversationFeedback
  引用（行内自查零命中）✓。`ChatFeedbackEntry` 的 DTO 定义在 data 层，由 Core 侧引用为下行 ✓。

### 3. 类职责单一 —— PASS

- **ChatFeedbackManager（251 行，< 300）**：职责 = 显示编排 + 落库 + 单行查询门面，三类分区注释清晰
  （:36 结束路径 / :176 持久化与查询 / :215 展示辅助）。HTTP 逻辑零复制（已核实 import 与正文）。
- **ConversationFeedback（record + 纯静态 parse）**：import 仅 Gson 三件 + `java.util.ArrayList/List`
  （:3-9），**零 Bukkit 依赖** ✓，可脱离服务器单测（ConversationFeedbackTest 已证）。
- **ChatFeedbackEntry**：纯 DTO，import 仅 `java.sql.Timestamp` ✓ 无 Bukkit。
- **ChatFeedbackManager 无隐藏静态状态**：全文 `static` 零命中 ✓，状态仅 `final plugin` + `final aiChatManager`。

### 4. DI 完整性 —— PASS

- `new ChatFeedbackManager` 全仓**仅 1 处**：`LetsLearnEnglish.java:169`；getter 单点 `:697-699`。无绕过容器的实例化。
- **暴露面最小**：public 方法与冻结清单 `deliverEnd/persistOnQuit/getFeedback/unloadPlayer` **逐一对齐，无多余 public** ✓。
- `DatabaseManager.upsertChatFeedback/getChatFeedback` 为 Data 层合法 public API ✓。
- **事件注册**：本批无新 Listener，`PlayerJoinListener` 已在 `registerListeners()`（:315）注册，
  改动仅为 `onPlayerQuit` 内加两行 ✓。本批无新命令 → `plugin.yml` 无需变更 ✓。
- import 卫生：ChatFeedbackManager 六条 import 全部有使用点（LetsLearnEnglish:28 / ChatFeedbackEntry:4,203 /
  Bukkit:48 / Player:44 / UUID:9,181,203,211 / CompletableFuture:9,186），**无冗余无遗漏** ✓。

### 5. 冻结接口命中 —— PASS（逐签名）

| 冻结签名 | 实现位置 | 一致 |
|---|---|---|
| `ChatFeedbackManager.deliverEnd(Player, SessionSummary)` | :44 | ✓ |
| `ChatFeedbackManager.persistOnQuit(Player, SessionSummary)` | :113 | ✓（见问题 #1：plan 文档写的是无 Player 版） |
| `ChatFeedbackManager.getFeedback(UUID, String)` | :203 | ✓ |
| `ChatFeedbackManager.unloadPlayer(UUID)` | :211 | ✓ |
| `ConversationFeedback.parse(String)` → nullable | :51 | ✓（永不抛异常，失败返回 null） |
| `DatabaseManager.upsertChatFeedback(UUID, String, String, String, int, int, int, String, String)` | :689 | ✓ 与 plan §3.5 九参数逐位相符 |
| `DatabaseManager.getChatFeedback(UUID, String)` | :738 | ✓ |
| `AIChatManager.SessionSummary(sessionId/scenarioKey/difficultyKey/validInputs/aiReplyCount/qualified/startTimeMs/durationSeconds/transcript)` | :193-195 | ✓ 字段名与顺序全对 |
| `AIChatManager.TranscriptLine(role, content)` | :184 | ✓ |
| `requestConversationSummary(Player, SessionSummary, BiConsumer<ConversationFeedback,String>)` | :1158-1159 | ✓ 主线程回调约定与 javadoc 一致 |
| `ChatFeedbackEntry` data class（11 字段 + getter） | :14-58 | ✓ 形态对齐 ChatHistoryEntry |

### 6. quit 编排一致性 —— PASS（附观察）

方向正确（Listener → 服务，单向不成环），与 S5 既有模式一致。但**同一域（AI 会话态）的 quit 清理被拆在两个
监听器**，存在隐式执行顺序依赖（见问题 #2）。

### 7. 范围外发现

见问题 #3 / #4 / #5 / #6。另：既有 P2-27 四个越层点**未被本批触碰**，与本批"不加深"约定相符（确认件）。

---

## 二、问题清单（按严重度）

| # | 严重度 | 维度 | 位置 | 现象与证据 | 建议 |
|---|---|---|---|---|---|
| 1 | 低 | 文档一致性 | `plan-ai-feedback.md:336` vs `ChatFeedbackManager.java:113` | plan §3.5 冻结写 `persistOnQuit(SessionSummary)`，实现为 `persistOnQuit(Player, SessionSummary)`。实现更合理（SessionSummary 不含玩家 UUID，必须借 Player 取 UUID；调用点 ChatScenarioListener:174/PlayerJoinListener 语境均有 Player）。冻结清单（construction-tracker:109）只列方法名不列签名，故非硬违规 | 回写 plan §3.4/§3.5 为 `persistOnQuit(Player, SessionSummary)`，消文档漂移 |
| 2 | 低 | quit 编排（隐式顺序） | `PlayerJoinListener.java:128-129` ↔ `ChatScenarioListener.java:170-175` | AI 会话态 quit 清理分裂在**两个监听器**：PlayerJoinListener 清 `pendingSummaries`+`unloadPlayer`；ChatScenarioListener 走 `endConversationOnQuit`+`persistOnQuit`。二者同为 NORMAL 优先级，Bukkit 按注册序执行 → PlayerJoinListener（:315 注册）先于 ChatScenarioListener（:325 注册）执行。当前无功能缺陷（`endConversation` 不碰 `pendingSummaries`，两次 remove 幂等），但顺序依赖仅存于注释、未用 EventPriority 固化 | 若依赖顺序，用 `@EventHandler(priority=...)` 显式固化；或把 persistOnQuit 收口进 PlayerJoinListener 单点。当前可接受 |
| 3 | 低 | 分层（DRY / 契约重复） | `ChatHistoryGUI.java:385-426` | GUI 自行用 Gson 解析 `payload` 的 comment/corrections/words 字段（:390/:397/:420），与 `ConversationFeedback` 封装的同一 JSON 契约**重复两份实现**。注释 :404 已自认"payload 侧 ConversationFeedback.parse 已硬上限 5 条"——即 GUI 依赖 parse 的约束却不复用它。属 GUI→Core 门面合法调用（非越层），但字段名/上限漂移风险实在 | 在 ChatFeedbackManager 门面暴露 `getFeedbackParsed(...)`（内部调 `ConversationFeedback.parse(payload)`），GUI 只渲染 DTO；或给 ChatFeedbackEntry 加惰性解析访问器 |
| 4 | 低 | 类职责（超长类，WARN） | `AIChatManager.java`（1506 行） | 3A 报告问题 #2/#139 曾建议借 3B 抽 `ChatSessionState` 压规模；本批反而在同类**净增约 350 行**（transcript 缓冲 :651-674 + 总结通道 :1116-1285），较 3A 的 1155 行进一步增长，为全仓最大类。按 300 行准则属警告级（不 FAIL） | 后续 Wave 把 `ChatSessionState`+transcript+总结通道拆为 `core/ai/ConversationSessionStore` 或 `SessionSummaryService`；本批不阻塞 |
| 5 | 低 | 主线程 IO（既有口径张力） | `ChatHistoryGUI.java:335 → :359`（`getFeedback` 同步查库） | 详情页渲染在主线程同步执行一次有界索引查询（`ChatFeedbackEntry` 单行）。plan §3.5 明示授权（"有界索引查询，与既有 GUI 查询同姿态，P2-27 留账"），且与同文件既有四点同口径 | 随 P2-27 统一整改（批量/GUI 门面化）；单点保持现状可接受 |
| 6 | 低 | 风格 / 微效 | `ChatFeedbackManager.java:173`；`ChatHistoryGUI.java:358,385,406` | ① 每次 AI 点评成功 `new com.google.gson.Gson().toJson(feedback)` 新建实例，而 AIChatManager 已持共享 `gson` 字段；② 两文件均以全限定名内联引用（`com.letslearnenglish...ChatFeedbackEntry`、`com.google.gson.JsonObject`），未走 import，与同文件其余短名风格不一致（同 3A 报告问题 #4 的既有风格噪声） | 复用共享 Gson（或门面提供序列化）；补 import 改短名 |

**未发现**：新增越层直调 DatabaseManager、绕过容器的 `new`、监听器漏注册、双向依赖环、
隐藏静态状态、冻结签名与消费点错配、import 冗余/遗漏。

---

## 三、不确定项

1. **事件执行顺序的实际影响**：问题 #2 的顺序推断基于 Bukkit"同优先级按注册序执行"的常规语义。
   当前两个 quit 处理器互不干扰（已逐一核对无共享可变状态的非幂等操作），故无论顺序如何均无功能缺陷；
   若后续任一方改为非幂等操作，需重新评估。
2. **`unloadPlayer(UUID)` 当前为空实现**（:211-213，javadoc 明示"预留扩展点"）：属冻结接口要求保留的形状占位，
   非死代码违规；但 `PlayerJoinListener:129` 对空方法的调用目前为 no-op 接线，若长期无内容建议并入注释或移除调用点。
3. **`getFeedback` 的线程契约**：本类 javadoc 未声明"须主线程调用"，而 GUI（主线程）与潜在异步调用方均可调；
   DB 层以 `connectionLock` 同步保护，故无并发正确性问题，仅缺契约文档（低）。
