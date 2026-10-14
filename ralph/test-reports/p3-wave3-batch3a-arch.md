# 架构报告 p3-wave3-batch3a（Phase 3 Wave 3 Batch 3A 四流合流）

### 判定：PASS

四个维度（分层正确性 / 单一职责 / DI 完整性 / 事件注册）**零违规**；下列 WARN 为改进建议，不阻塞发布。
（3A-3 语义清理流的文件不在本次白名单内，未纳入本报告取证范围；本报告覆盖 3A-1 / 3A-2 / 3A-4 与本批新增接线点。）

审查范围（只读）：
`core/season/SeasonManager.java`(708 行，新) · `core/ai/PersonalScenarioStore.java`(331 行，新) · `listener/PersonalScenarioListener.java`(188 行，新) · `gui/PersonalScenarioGUI.java`(≈240 行，新) · `gui/LeaderboardGUI.java`(≈352 行，改) · `gui/ScenarioSelectGUI.java`(改) · `listener/ScenarioSelectListener.java`(改) · `core/ai/AIChatManager.java`(1155 行，改) · `core/ProxyMessenger.java`(改) · `listener/PlayerJoinListener.java`(改) · `listener/SocialListener.java`(改) · `command/LearnCommand.java`(改) · `expansion/LLEPapiExpansion.java`(改) · `LetsLearnEnglish.java`(DI) · `data/DatabaseManager.java`(改) · `config.yml` / `messages/{zh,en}.yml` / `plugin.yml` / `schema.sql`

---

## 重点检查项逐项结论

### 1. 依赖方向无环 —— PASS

**SeasonManager 依赖面（逐 import，SeasonManager.java:3-33）**

| import | 目标层 | 用途 |
|---|---|---|
| `LetsLearnEnglish`(:8) | 容器 | 手动 DI 查找 |
| `core.SoundManager`(:9) | Core 同级 | `broadcastStarted` 的 complete 音效（:684） |
| `core.progress.Leaderboard.LeaderboardEntry`(:10) | Core 同级 | 复用窗口聚合结果（:271/:359） |
| `data.DatabaseManager` + 内嵌 `SeasonRewardRow`/`SeasonRow`(:11-13) | Data ↓ | 台账读写 |
| `net.milkbowl.vault.economy.*`(:14-15) | 外部 | 直发奖励 |
| `Gson`×5 / `Bukkit` / `Player` / `java.time.*` / `java.util.*` | 工具 | 快照 JSON、调度 |

- **未引用 `AchievementManager`、未引用 `SocialManager`** —— 执行计划要求的"防 Core↔Core 环"命中（核对：全文件无此二类出现）。Achievement 若要读赛季台账，方向应为 AchievementManager → `season_rewards` 表（plan-season §4.1 单向）。
- **`SeasonManager → DatabaseManager` 单向**：DB 侧内嵌 `SeasonRow`(:1886) / `SeasonRewardRow`(:1902) 而非反向引用 SeasonManager，DatabaseManager.java:1892-1894 注释明示理由，与 `FeedRow`/`CheerStatsRow` 惯例一致。无 Data→Core 反向 import。PASS
- **SeasonManager ↔ ProxyMessenger：结论 = 容器中介的双向引用，但编译期无 import 环、运行期调用图无环 → 可接受**
  - 发送向：`SeasonManager.runSettleSweep` 主线程段调 `plugin.getProxyMessenger().sendSeasonSettled/sendSeasonStarted`（SeasonManager.java:394-396）。SeasonManager **未 import** ProxyMessenger（仅以表达式接收者调用方法，Java 允许）。
  - 接收向（`applyRelayed*` 回投例外）：`ProxyMessenger.handleSeasonRelay`(:214-238) 以 `var seasonManager = plugin.getSeasonManager()`(:229) 取容器实例，调 `applyRelayedSettlement`(:234) / `applyRelayedSeasonStarted`(:236)。ProxyMessenger **未 import** SeasonManager（刻意用 `var` 规避）。
  - 环判定：类型层无环（两侧 import 均为 0）；调用图无环 —— ProxyMessenger→SeasonManager 分支只做广播/发放（:631-637 不回调 send*），SeasonManager→ProxyMessenger 分支只做 Forward 写包（:123-147 无回调）。**无递归栈/无重复投递回路**。回环去重由 origin 过滤承担（ProxyMessenger.java:225-228），发放另有 `claimed=0` 条件 UPDATE 兜底（:533-536）。PASS
- **PersonalScenarioStore ↔ AIChatManager：类型级互引（同包），行为单向 → 3A-2 报告的取舍成立，可接受**
  - Store→AIChat：仅**类型**引用，零行为调用 —— `ScenarioConfig`（Store:4 import、:138/:183 构造）与 `ImportResult`（Store:88/:108/:135/:144 构造返回）。二者同属 `core.ai` 包，`ImportResult` 定义留在 AIChatManager 以保证 LearnCommand 调用点零改动（AIChatManager.java:1004 注释）。
  - AIChat→Store：**组合（生命期所有）** —— 字段 `personalScenarioStore`(AIChatManager.java:64)、构造内 `new`(:153)、`shutdown()` 委托 `clearAll()`(:1148)、9 处薄壳委托(:477/:481/:483/:1021/:1035/:1043/:1048/:1132/:1148)。
  - 评：这是"数据契约反向引用 + 生命期正向组合"，非行为环，无初始化顺序风险（Store 不反向 `new` 容器） → **可接受**。彻底单向的代价是把 `ImportResult`/`ScenarioConfig` 提为顶层类型（破坏冻结接口的零改动约束），收益不抵成本。建议（非必须）Wave 3B 借 Store 成熟时再评估（见问题 #3）。
- **PersonalScenarioListener → Store 单向**：仅 `PersonalScenarioStore.DeleteResult` 类型 + `plugin.getAIChatManager()` 门面调用（Listener:6 import、:136-137）；未 import / 未调用 AIChatManager 以外的 Core 内部方法；无反向引用 Store→Listener。PASS

### 2. 冻结接口一致性 —— PASS（逐签名对照）

**SeasonManager public API × 消费点**

| 冻结签名（SeasonManager.java） | 3A-4 / 本批消费点 | 一致 |
|---|---|---|
| `boolean isEnabled()` :131 | LeaderboardGUI:253（经 isSeasonAvailable）、LLEPapiExpansion:299、SocialListener:489、LearnCommand:754 | ✓ |
| `int currentSeasonId(LocalDate)` :180 | LeaderboardGUI:266/295、LearnCommand:762、PAPI:199、LetsLearnEnglish:541-542 | ✓ |
| `SeasonWindow windowOf(int)` :199 | LeaderboardGUI:271、PAPI:313、内部 :219/:270/:333/:356 | ✓ |
| `long millisUntilEnd()` :214 | LeaderboardGUI:279 | ✓ |
| `List<SeasonRankRow> getCurrentTopCached(int)` :233 | LeaderboardGUI:124、LearnCommand:769 | ✓ |
| `int getRankOf(UUID)` :247 | LeaderboardGUI:305、LearnCommand:772、PAPI:207 | ✓ |
| `void claimPendingRewards(Player)` :506 | PlayerJoinListener:91 | ✓ |
| `void refreshCacheAsync()` :260 | LetsLearnEnglish:377（5 分钟班车） | ✓ |
| `void settleIfNeededAsync()` :297 | LetsLearnEnglish:223（启动）、:546（午夜跨季） | ✓ |
| `applyRelayedSettlement(int)` :614 / `applyRelayedSeasonStarted(int)` :642 | ProxyMessenger:234/236 | ✓ |
| `static List<SeasonRankRow> parseTopSnapshot(String)` :468 | 内部 :623（"上一季冠军"回顾；外部暂无消费方，公开为 plan §3.6 预留） | ✓ |
| `record SeasonWindow(int id, LocalDate start, LocalDate end)` :105 | 全字段被 :219/:271/:313 消费 | ✓ |
| `record SeasonRankRow(int rank, UUID uuid, String name, long score)` :115 | GUI:126-130/309-313、LearnCommand:776-805 四访问器全用到 | ✓ |

- 记录类访问器命名与消费端一致（`uuid()/name()/score()/rank()`），无 "record 更名未同步" 缺口。
- `LeaderboardEntry` 适配层（LeaderboardGUI:127-130）字段顺序 `(uuid,name,score,correct,incorrect,sessions,rank)` 与 `core.progress.Leaderboard.LeaderboardEntry`(:36-47) 构造签名逐位相符。✓

**LeaderboardGUI 公开常量 × SocialListener 消费点**

| 常量（LeaderboardGUI.java） | 消费点 | 一致 |
|---|---|---|
| `TAB_SEASON_SLOT = 3` :60 | SocialListener:486（`cm.getGuiSlot(...,LeaderboardGUI.TAB_SEASON_SLOT)`） | ✓ |
| `SEASON_TAB_INDEX = 3` :62 | SocialListener:495 → `open(player, tab)` | ✓ |
| `ENTRY_SLOTS` :47-53 | SocialListener:544（`getLeaderboardSlotIndex`） | ✓ |
| `isSeasonAvailable(SeasonManager)` :252 | SocialListener:489 | ✓ |
| `BACK_SLOT/TAB_COINS_SLOT/TAB_WORDS_SLOT/TAB_COMBO_SLOT` :56-66 | SocialListener:465-468 | ✓ |

- 渲染侧与点击侧**同读 gui-layout 同一键**（GUI:174/:292/:331 ↔ Listener:465-486），未出现"渲染 A / 判定 B"。✓
- `TAB_SEASON_SLOT == SEASON_TAB_INDEX == 3` 同值双常量，语义区分仅靠命名与注释；SocialListener 两处用法各自正确（槽位判定用前者、tab 下标用后者），当前无缺陷（见问题 #6）。

**Store.DeleteResult × Listener 消费**

`record DeleteResult(boolean deleted, boolean notFound, boolean saveFailed)`（Store:256）三态与 `PersonalScenarioListener.handleDelete`(:133-154) 三分支一一对应：`deleted()`→manage-deleted、`notFound()`→manage-not-found、else→manage-save-failed；Store 三产点语义互斥（:275/:283/:287 notFound；:293/:302 saveFailed；:305 deleted）。✓
`ImportResult` 定义留在 AIChatManager(:1006)，LearnCommand:914-921 与 Store:88/:108/:135/:144 双侧引用同一类型。✓

### 3. DI 完整性 —— PASS

- 全库 `new SeasonManager` **仅 1 处**：`LetsLearnEnglish.java:185`（`onEnable`，字段声明 :117、getter :756）。全库 `new PersonalScenarioStore` **仅 1 处**：`AIChatManager.java:153`（构造内，符合"由 AIChatManager 构造持有"的计划设计）。无绕过容器的实例化。✓
- **初始化顺序**：seasonManager 建于 :185；其运行期依赖 `ConfigManager`(:143)、`PlayerDataStore`(:161)、`DatabaseManager`(:154)、`Economy`(:204 `setupEconomy`)、`ProxyMessenger`(:201-202 register) **均先于**其首次被调度（:215-224 异步块挂 `settleIfNeededAsync`，注释 :221-223 明示该顺序理由）。无"构造期取容器依赖"的时序陷阱（SeasonManager 构造体仅存 plugin 引用 :117-119）。✓
- **新 Listener 注册**：`PersonalScenarioListener` 在 `registerListeners()` 注册（LetsLearnEnglish.java:318-319），早于 `registerCommands()`/`startScheduledTasks()`。✓ 本批无新命令，`plugin.yml` 无需变更（`/le season` 复用既有 `learnenglish` 命令与 tab 补全列表 LearnCommand:50/:134）。✓
- SeasonManager 无 `onDisable` 清理面属正确设计（无 per-player 地图；`topCache` 随实例回收；在途异步任务由 `settleInFlight` 复位 :311/:318 与停用窗口 try/catch(:316/:696/:704) 兜底）。✓
- `PlayerJoinListener:91` 对 `getSeasonManager()` 无 null 守卫 —— 与同文件 `getSocialManager()`(:86) 一致，且赋值先于监听器注册，非缺陷（见问题 #5）。

### 4. 分层合规 —— PASS

- **PersonalScenarioListener（Listener 层）对 Data 层零接触**：全文无 `getDatabaseManager()` / `getPlayerDataStore()`；数据面全部经 `AIChatManager` 门面（:135 displayName、:137 删除）与 `PersonalScenarioStore` 类型；导航经 `GuiNavigationManager`（:61-64）。✓
- **LeaderboardGUI（GUI 层）不查库**：赛季榜走 `SeasonManager.getCurrentTopCached`（:124）内存快照，信息牌走 `getRankOf`(:305) 与缓存行反查(:309-313)，剩余时间走 `millisUntilEnd`(:279) 纯计算 —— 未复制 PF-SEA-05 记录的"开界面主线程查库"旧模式。其余三 tab 走 `Leaderboard` 既有内存缓存(:112/:116/:135)。✓
- **LearnCommand `/le season` 只经 SeasonManager 门面**：`handleSeasonCommand`(:752-809) 仅用 `getSeasonManager()` + `LeaderboardGUI` 静态纯函数 + `MessageUtil`，无 `DatabaseManager` 直调（该文件:431 的 `getPlayerDataStore()` 属既有 profile 分支，非本批路径）。✓
- **ProxyMessenger（Core）→ SeasonManager（Core）** 经容器查找 + null 守卫(:229-232)，非跨层直调；PAPI 扩展层（expansion）不引 gui 类，自行复制同源公式（:298-314），方向合规（见问题 #1）。✓
- 无 Data→Core、Util→Core 的新增反向依赖。

### 5. 类职责 —— WARN（不 FAIL，300+ 行为警告级）

- **SeasonManager（708 行）**：单类内聚 6 个面 —— ①配置门/点读(:131-170) ②窗口纯函数(:180-223) ③榜单缓存(:233-278) ④结算扫描+台账构建(:297-462) ⑤发放入账(:506-600) ⑥跨服回投+播报(:614-687) + 调度工具(:693-707)。分区注释清晰（:121/:172/:225/:280/:495/:602/:649/:689 八个分隔块），单一域（赛季生命周期）内高内聚。
  - 对照 plan-season：§3.1 明确设计为 **单类 `core/season/SeasonManager.java`**（plan-season.md:289、:320），包位选择亦有授权（`core/season/` 或 `core/manager/`）。**实现与计划一致**，无擅自分包。
  - 评：708 行超 300 行警告线，但职责边界（窗口/结算/缓存/协议）有天然接缝。**建议（可选，不阻塞）**：若 Wave 3B/4 再向本类追加功能，优先拆 `SeasonSettlementService`（结算+台账+发放）与保留 `SeasonManager`（窗口纯函数+缓存+协议门面），或至少把窗口纯函数提为 `SeasonWindowMath`（纯静态，可单测，顺带解问题 #7）。
- **PersonalScenarioStore（331 行）抽取后 AIChatManager 行数变化 —— P2-28 兑现度结论**
  - **职责口径：达标。** 个人场景持久化（File/YamlConfiguration/`config.save`/`.bak` 备份/CRC32 slug/上限常量）已全部迁出：AIChatManager 内 `grep player_scenarios|\.bak|CRC32|Files\.|MAX_PERSONAL` **零命中**（仅 :988 注释提及路径）；残留 `YamlConfiguration` 使用点 :172/:236-247 全为 `ai_config.yml`/全局 `ai_scenarios.yml`（非个人场景）；个人场景节现为纯门面（:986-1074 薄壳 + 定义块 + :1128-1133 getter + :1148 shutdown 委托）。边界与 Store javadoc 声明一致（Store:25-27 "只负责文件+内存，不碰 HTTP/会话态/全局场景"）。
  - **行数口径：未降。** AIChatManager 现 1155 行（含注释空行；非空行 1027），对照 P2 期基线 p2-arch.md:56 记录 1140 行（计数口径可能不同，仅作趋势参考）——抽取未带来可观行数下降，**远高于 300 行准则，亦未缓解 plan-ai-feedback(PF-FB-05) 记录的"该类 1273 行逼近维护极限"担忧**。主因：本批新增了较厚 javadoc/分隔注释（:986-1007 约 22 行文档），且同类在 Wave 2/3 其他流继续增长。
  - **结论**：P2-28 以"职责分离"验收达标；以"降低单类规模"验收不达标。建议 Wave 3B（依赖 Store 的 AI 反馈闭环）落地时把会话状态收敛（`ChatSessionState` + transcript 缓冲）一并抽为第二刀，才能真正压规模。

### 6. import 卫生 —— PASS（抽查零未用 import）

- `SeasonManager`（:3-33，31 条 import）逐条核对全部有使用点：Gson 族→:461/:474-487；SoundManager→:684；LeaderboardEntry→:271/:359；DatabaseManager 族→:323/:331/:433/:516/:533/:563/:621；Economy/EconomyResponse→:555-561；Bukkit/Player→:400/:633/:659/:677；java.time 族→:135/:156/:188/:220；util 族（含 HashSet/LinkedHashMap/Map/Set/AtomicBoolean/Level/Locale）→:419/:454/:301-305/:308/:596。**零未用**。✓
- `PersonalScenarioStore`（:3-19）全部命中（Map→:44、CRC32→:322、Files/StandardCopyOption→:116、ConfigurationSection→:93/:168/:181）。✓
- `PersonalScenarioListener`（:3-16）全部命中（GuiNavigationManager→:61、ScenarioSelectGUI→:63、PersonalScenarioGUI→:59(:125/:162/:171/:181)、PersistentDataType→:162/:172/:182）。✓
- `LeaderboardGUI`（:3-23）全部命中（ConfigManager→:94、SkullMeta→:190、ChronoUnit→:271、LocalDate→:266/:295）。✓
- `AIChatManager`（:3-26）中 `Material`/`ConfigurationSection`/`YamlConfiguration`(:10-12) 仍被全局场景与配置加载使用（:251/:254/:271/:274/:280/:236-247），`java.io.*`(:15) 与 `StandardCharsets`(:20) 仍用于 IOException/File(:166/:226/:898-910) —— 抽取后无孤儿 import。✓
- 反向：`LetsLearnEnglish` 中 `ProxyMessenger`(:7/:100) 与 `SeasonManager`(:20/:117) import 均被使用。✓
- 唯一风格噪声见问题 #4（FQN 替代 import）。

### 7. 测试资产 —— WARN（建议补，非阻塞）

- 本批**零新单测**（`src/test` 仍 5 个类 / 33 项，与 p3-wave3-batch3a-build.md:20 一致）。
- **缺口**：SeasonManager 刻意留了两个包级纯静态函数 `seasonId(LocalDate,LocalDate,int)`(:188) 与 `windowFor(int,LocalDate,int)`(:204)，注释自称"脱离 Bukkit 可直接冒烟"（:185-187/:203）；`parseTopSnapshot(String)`(:468) 亦为纯函数（坏数据返回空列表、脏 UUID 行跳过 :481-483）。三者**零 Bukkit 依赖**，是最便宜的单测资产，却只由单机 javac 冒烟覆盖（未入库）。
- **建议（Wave 3B/4）**：新增 `src/test/.../core/season/SeasonManagerTest.java`，覆盖 ≥8 断言：`date<anchor→0`、`date==anchor→1`、季末日→1、季首次日→2、`durationDays=1`、`durationDays<=0`/null 防御→0、`windowFor` 闭区间 `end=start+du-1`、`parseTopSnapshot` 的 null/空串/非 JSON/坏 UUID/合法 JSON 五态。收益：纯函数零 mock，且 JaCoCo 覆盖率门（pom.xml:177 `COVEREDRATIO 0.01`，注释明示"每个 release 上调一档"）顺带受益 —— 这是推进门限的自然抓手。
- PersonalScenarioStore/Listener 属文件 IO + Bukkit GUI，单测成本高，**不建议**本阶段补（可用真机冒烟）。

---

## 事件注册维度（补充核对）

- `PersonalScenarioListener` 已在 `onEnable` 注册（LetsLearnEnglish.java:318-319），与 `ScenarioSelectListener`(:315) 并存；两页判定互斥性经核实：管理页 `isPersonalScenarioTitle` 为 zh/en **完整标题等值 + GUI_SIZE 双重确认**（Listener:124-127 ↔ PersonalScenarioGUI:204-216），场景选择页为 `contains("场景选择"/"Scenario Select"/"情景对话")`（ScenarioSelectListener:30-31）——「我的个人场景 / My Personal Scenarios」不含上述子串，**双向不命中**。✓
- 入口权限纵深三处同口径 `letslearnenglish.scenario.import`（ScenarioSelectGUI:169-173 可见门、ScenarioSelectListener:204 点击门、PersonalScenarioListener:71 页内门），plugin.yml:43 节点存在。✓
- `GUIUtils.isPluginGUI` 白名单已补管理页标题（GUIUtils.java:86-89），SoundManager 打开音不漏。✓

---

## 问题清单（按严重度）

| # | 严重度 | 维度 | 位置 | 现象 | 建议 |
|---|--------|------|------|------|------|
| 1 | 中 | 分层（跨层重复） | `LeaderboardGUI.java:265-272` vs `LLEPapiExpansion.java:298-314`（第三处同源 `SeasonManager.millisUntilEnd():214-223`） | `seasonDaysLeft` 公式在 GUI 与 expansion 各存一份实现（双方 javadoc 互指"改动须两侧同步"），"含今天 +1" 的闭区间语义漂移风险持续存在 | 将 `daysLeft()` 提为 `SeasonManager` 公共纯函数（Core 已有 `windowOf`/`millisUntilEnd`），GUI/PAPI/Command 三处改调同一实现；`isSeasonAvailable` 可一并提为 `SeasonManager.isAvailable()` 消掉两处复制 |
| 2 | 中 | 类职责 | `AIChatManager.java`（1155 行，非空 1027） | Store 抽取达成职责分离，但行数未降（P2 期 1140 行基线）；PF-FB-05"逼近维护极限"未缓解 | Wave 3B 落 AI 反馈闭环时同步抽 `ChatSessionState`+transcript 缓冲（该流已计划），本轮不阻塞 |
| 3 | 低 | 依赖（类型级互引） | `PersonalScenarioStore.java:4,88,108,135,144` ↔ `AIChatManager.java:64,153` | Store 反向引用 AIChatManager 的 `ScenarioConfig`/`ImportResult` 类型（同包、无行为环、无初始化顺序风险），已知取舍 | 保持现状可接受；若 Wave 3B 需再次改 Store，可借机把 `ImportResult`/`ScenarioConfig` 提为 `core/ai` 顶层类型，彻底单向 |
| 4 | 低 | import 卫生（风格） | `LetsLearnEnglish.java:319`（另有既有同类 :328） | `PersonalScenarioListener` 用全限定名内联 `new`，未 import，与同文件其余 15 个 Listener 的短名风格不一致 | 补 import 并改短名（一行级） |
| 5 | 低 | DI（一致性） | `PlayerJoinListener.java:91` | `plugin.getSeasonManager().claimPendingRewards(...)` 无 null 守卫（`getSocialManager()` :86 同款既有风格） | 保持现状可接受；若采纳"DI 后置化"再统一加守卫 |
| 6 | 低 | 常量语义 | `LeaderboardGUI.java:60` vs `:62` | `TAB_SEASON_SLOT` 与 `SEASON_TAB_INDEX` 同值 3，语义靠命名区分，误用在编译期不可检出 | 保持现状（值与用途均正确）；若未来赛季 tab 槽位配置化，考虑把槽位→下标映射收敛为单一方法 |
| 7 | 低 | 测试资产 | `SeasonManager.java:188,204,468` | 三个纯静态函数（可脱离 Bukkit 单测）零 JUnit 覆盖，仅单机冒烟 | Wave 3B/4 补 `SeasonManagerTest`（≥8 断言）；顺带推动 JaCoCo 0.01 门限上调 |

**未发现**：越层直调 DatabaseManager 的本批新代码、绕过容器的 `new`、监听器漏注册、import 冗余、冻结签名与消费点错配。

---

## 给 Wave 3B / 4 的架构建议（汇总）

1. **公式单一真相源化**：`SeasonManager.daysLeft()` / `isAvailable()` 上收，消 GUI/PAPI 双份复制（问题 #1）——3B 若再动赛季展示面（如赛季回顾）必然踩此坑。
2. **AIChatManager 第二刀**：3B 的 `ChatSessionState` 抽取是本类唯一可行的压规模路径，建议在该流验收标准里写入行数目标（如 ≤900 行）。
3. **Store 类型的最终去处**：3B 动 Store 时顺带把 `ImportResult`/`ScenarioConfig` 提顶层，避免互引固化（问题 #3）。
4. **纯函数单测补票**：`SeasonManagerTest` 成本极低、收益（覆盖率门推进 + 窗口边界回归网）明确，建议排在 3B 首个 fan-in 位。
5. **主线程台账写的边界记录**：`deliverPending` 在主线程执行 `markSeasonRewardClaimed` 条件 UPDATE 与 `recordCoinsEarned`（SeasonManager.java:533/:563），虽对齐 CheckInManager"先标记后发钱"先例、为原子性必要妥协，但与本域 F2-11"不在主线程查库"标语存在口径张力 —— 建议在 `rules/` 或类 javadoc 中明示该例外（现状已在类 javadoc:53-72 记录线程模型，可再补一句"发放入账的条件 UPDATE 在主线程"）。
