# 架构报告 P3-W4-4A（Phase 3 · Wave 4 · Batch 4A — US-CLS-1 + US-CLS-2 班级系统）

### 判定：PASS

七个审查维度零硬违规。冻结接口 9/9 方法 + 1/1 过滤签名逐位命中（GUI `open` 有 1 处
注册表漂移，见低-1）。下列为 WARN/低级别改进项，不阻塞发布。

审查范围（只读，未修改任何文件）：
`data/DatabaseManager.java`(改：班级两表 DDL + 9 方法 + 2 record) ·
`data/PlayerDataStore.java`(改：`getRankedPlayersForPeriodFiltered`) ·
`gui/ClassBoardGUI.java`(新，172 行) · `listener/ClassBoardListener.java`(新，86 行) ·
`command/LearnCommand.java`(改：SUBCOMMANDS + case + `handleClassCommand`) ·
`command/AdminCommand.java`(改：SUBCOMMANDS + case) ·
`core/manager/AdminCommandHandler.java`(改：班级命令族 ~265 行) ·
`LetsLearnEnglish.java`(改：监听注册 1 行) · `resources/schema.sql` · `resources/config.yml` ·
`resources/messages/{zh,en}.yml` · `gui/GUIUtils.java`(fan-in 白名单)

---

## 一、逐维度检查结果

### 1. 分层合规 —— PASS

| 检查点 | 证据 | 结论 |
|---|---|---|
| `ClassBoardGUI` 零 Data 层依赖 | `ClassBoardGUI.java:3-18` import 仅 LetsLearnEnglish / Leaderboard.LeaderboardEntry / MessageUtil / Bukkit / java.util；全文零 `DatabaseManager`/`PlayerDataStore`/`getConnection` 引用 | ✓ 零查库链路成立（PF-SEA-04 纪律守住） |
| `ClassBoardListener` 零 Data 层依赖 | `ClassBoardListener.java:3-11` import 仅 LetsLearnEnglish / SoundManager / ClassBoardGUI / MainMenu / Bukkit | ✓ |
| `AdminCommandHandler` → `DatabaseManager` | `AdminCommandHandler.java:648/665/688/715/758/796` 直调 `plugin.getDatabaseManager()`；先例 `:70`（resetPlayerData）、`:93/:96`（generateReport） | ✓ **既有方向**（core/manager → Data 下行，4A-1 白名单显式同批授权） |
| `LearnCommand.handleClassCommand` → `DatabaseManager` | `LearnCommand.java:778/786/795/809`（getPlayerClassId / listClasses / getClassMembers）+ `:815` PlayerDataStore | ⚠ 见低-2：姿态与 `handleSeasonCommand` **不同款**，但为派发提示词显式授权（见下） |
| `handleSeasonCommand` 对照 | `LearnCommand.java:845-899` 全程读 `SeasonManager` 内存缓存（`getCurrentTopCached`/`getRankOf`），**零查库** | 本批 `/le class` 因需"成员集合过滤的窗口聚合查询"必须触 Data；无 Core 门面可走 |
| 是否加深 P2-27 | `dev-findings.md:18` P2-27 定义域 = **GUI** 越层（ChatHistoryGUI 四点）；本批零 GUI 越层点（ClassBoardGUI 干净），LearnCommand 为 **Command** 侧 | ✓ 未触碰、未加深 P2-27 |

**LearnCommand → DatabaseManager 的授权链核实**（低-2 定级依据）：
`tasks/phase3/wave4-dispatch.md:81` 原文——"班级存在性/成员列表读的是 4A-1 的
`DatabaseManager.getClassByName/getClassMembers` 冻结签名……按签名写代码"——**显式**授权
LearnCommand 直调 DatabaseManager。且 construction-tracker DI 检查项明示"班级逻辑全在既有类
（如需 `getClassManager` 之类不应存在）"。故本处为**已裁决的设计落点**，非新违规。

### 2. 依赖方向 / 无环 —— PASS

- **`DatabaseManager` 无反向引用**：全文 import 仅 `LetsLearnEnglish`/`ConfigManager`/
  `Leaderboard.LeaderboardEntry`/Bukkit/`java.sql|util`；grep `gui|listener|command|core.manager`
  **零命中** ✓
- **`PlayerDataStore` 无反向引用**：import 无 gui/listener/command ✓
- **反向 grep（新类被谁引用）**：`ClassBoardGUI` 仅被 LetsLearnEnglish / LearnCommand /
  ClassBoardListener 引用；`ClassBoardListener` 仅被 LetsLearnEnglish:333 引用。
  `GUIUtils.java:113` 的 `ClassBoardGUI` 命中为 **javadoc 注释**（零代码引用）✓ 无环
- **`ClassBoardGUI → Leaderboard.LeaderboardEntry` 类型一致**：
  `ClassBoardGUI.java:4` 与 `LearnCommand.java:7` 同引
  `com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry` ✓ 同名同型
- **`core.progress.Leaderboard ↔ DatabaseManager` 双向引用为既有**：`Leaderboard:183-185`
  → `getDatabaseManager().getTopCoins/...`；`DatabaseManager:5` → `LeaderboardEntry` 类型。
  该双向自 Wave 3A 前即存在（`getTopCoins` 等），4A 未新增，非本批引入 ✓

### 3. 职责单一 —— PASS（1 项 WARN）

- **`ClassBoardGUI`（172 行）**：纯渲染——`open()` 只做 i18n 取文案 + 建 Inventory + 装配
  物品；零查询、零 Bukkit 阻塞 IO；`isClassBoardGUI` 为纯静态前缀判定 ✓
- **`ClassBoardListener`（86 行）**：只做 标题双确认（前缀 + `GUI_SIZE`）+ 上半区守卫 +
  返回键 slot 8 → MainMenu ✓（形态照抄 ChatHistoryListener）
- **`AdminCommandHandler`（864 行，> 300）**：WARN 低-4。既有职责 = Admin 命令业务
  （reset/report/wordbank/duel…），班级族（`:599-864`，~265 行）为**纵向增长但同职责**，
  非新职责混入 ✓。306 行阈值属"已有长类"，非本批破坏

### 4. 冻结接口逐位对照 —— PASS（1 项注册表漂移）

`DatabaseManager`（`tasks/phase3/construction-tracker.md:51-58` 对照）：

| # | 冻结签名 | 实现 | 命中 |
|---|---|---|---|
| 1 | `createClass(String, UUID, long) -> boolean` | `:2420` | ✓ |
| 2 | `deleteClass(String) -> boolean` | `:2447` | ✓ |
| 3 | `getClassByName(String) -> ClassRow\|null` | `:2478` | ✓ |
| 4 | `listClasses() -> List<ClassRow>` | `:2502` | ✓ |
| 5 | `getClassMembers(int) -> List<ClassMemberRow>` | `:2527` | ✓ |
| 6 | `getPlayerClassId(UUID) -> Integer\|null` | `:2569` | ✓ |
| 7 | `addClassMember(int, UUID, long) -> boolean` | `:2592` | ✓ |
| 8 | `removeClassMember(int, UUID) -> boolean` | `:2629` | ✓ |
| 9 | `getClassMemberCount(int) -> int` | `:2648` | ✓ |
| R1 | `record ClassRow(int, String, String, String, long)` | `:2401` | ✓ |
| R2 | `record ClassMemberRow(int, UUID, String, long)` | `:2412` | ✓ |

`PlayerDataStore.getRankedPlayersForPeriodFiltered(int count, LocalDate since, LocalDate until,
Collection<UUID> members) -> List<LeaderboardEntry>`（tracker:59）：
实现 `PlayerDataStore.java:1219-1220` **逐位命中**；空集合早退 `:1222-1224` ✓；
`show_in_square` 隐身过滤保留 `:1242`（`COALESCE(pf.show_in_square,1)<>0`）✓；
ResultSet 关闭后组装（异步安全）✓。

`ClassBoardGUI.open` —— **注册表漂移**，见低-1。

### 5. DI / 事件注册 —— PASS

- **`ClassBoardListener` 注册**：`LetsLearnEnglish.java:333`（`registerListeners()` 内，
  与既有 15 个监听器同款 `registerEvents(new X(this), this)`）✓ 单点
- **`new ClassBoardListener(...)` 单点**：全仓仅 `:333` ✓
- **`new ClassBoardGUI(...)` 单点**：全仓仅 `LearnCommand.java:820`（GUI 按用即建，与
  `new LeaderboardGUI(plugin).open(player)`（`:427`）同姿态，非 DI 容器托管）✓
- **无新 getter 泄漏**：`LetsLearnEnglish` getter 面为 `getConfigManager`/`getDatabaseManager`/
  `getPlayerDataStore`/`getSeasonManager`——**无 `getClassManager`** ✓（班级逻辑全在既有类，
  零新 DI 单例）
- **无隐藏静态状态**：`ClassBoardGUI` 静态成员仅 `GUI_SIZE`/`BACK_SLOT`/`EMPTY_SLOT`/
  `ENTRY_SLOTS`（常量）+ 纯静态方法 ✓

### 6. record 姿态一致性 —— PASS（1 项 cosmetic）

- `ClassRow`/`ClassMemberRow` 与既有同族同类同层（`DatabaseManager` 内嵌读取投影，
  对齐 `FeedRow:1591`/`CheerStatsRow:1937`/`SeasonRow:2027`/`SeasonRewardRow:2043`）✓
  javadoc 逐字段说明齐全 ✓
- **样式微差**：`ClassRow`/`ClassMemberRow` 写 `public static record`，而同类内 5 个
  兄弟 record 写 `public record`（嵌套 record 隐式 static，语义等价）。既有
  `AIChatManager` 亦用 `public static record`，属全仓两种并存风格。见低-3（cosmetic）

### 7. 范围外发现 —— 见第五节

---

## 二、问题清单

| # | 严重度 | 位置 | 维度 | 原因 / 说明 |
|---|---|---|---|---|
| 低-1 | 低 | `ClassBoardGUI.java:112` vs `construction-tracker.md:60` | 冻结接口 | 注册表冻结为 `open(Player, String className)`（2 参），实现为 `open(Player, String, List<LeaderboardEntry>)`（3 参）。3 参形式是"GUI 零查库"设计的必然（数据须由调用方传入，dispatch:80 已授权），**注册表条目过期未回写**。建议 fan-in 时回写注册表 |
| 低-2 | 低 | `LearnCommand.java:778/786/795/809` | 分层姿态 | `handleClassCommand` 直调 `DatabaseManager`（本仓 `command/` 包内**首个** `getDatabaseManager()` 站点），与 `handleSeasonCommand`（零查库走 SeasonManager 缓存）**不同款**。经核为 dispatch:81 显式授权 + 无 Core 门面可走（设计裁决），**非违规**；不加深 P2-27（GUI 域）。记录供后续"Command 直调 Data"收口时参考 |
| 低-3 | 低 | `DatabaseManager.java:2401/2412` | record 风格 | `public static record` vs 同类兄弟 `public record`（语义等价，全仓两种并存）。cosmetic |
| 低-4 | 低 | `AdminCommandHandler.java`（864 行） | 职责单一 | 超 300 行准则（WARN 级，永不 FAIL）。既有长类，班级族为同职责纵向增长（~265 行） |
| 信-1 | 信息 | `ClassBoardGUI.java:78` | 文档 | javadoc 前缀唯一性论证引用 `class.title`「我的班级 / My Class」作为反例，但该键**未落盘**（zh/en 的 `class:` 块均无 `title` 子键；全仓 `class.title` 仅此 javadoc 一处命中）。论证本身仍成立（`class.board-title` 前缀「班级榜 - / Class Board - 」在 GUIUtils 白名单内唯一），仅引用了一个不存在的键 |

**无中/高/阻断项。**

---

## 三、确认项（已知债务，非本次审查对象）

- **P2-27（GUI 越层直调 DatabaseManager）**：本批**零新增 GUI→Data 站点**
  （ClassBoardGUI/ClassBoardListener 双侧 import 零 Data 依赖，实核）。既有四点
  （ChatHistoryGUI）/EnglishSquareGUI:272 未被触碰 ✓ 未加深。
- **P2-28**：已于 Wave 3A 关闭，无关。

---

## 四、落点合理性裁决（任务点名）

**`getRankedPlayersForPeriodFiltered` 落在 `PlayerDataStore`（而非 `DatabaseManager`）——判定合理**：

1. `plan-season.md:319` 表格行原文即为 `data/PlayerDataStore.java |
   getRankedPlayersForPeriodFiltered（CLS）`——**位置本就写的是 PlayerDataStore**，
   "CLS-1" 仅是 story 归属标签，非落点指定；
2. `construction-tracker.md:59` 冻结接口逐字写 `PlayerDataStore.getRankedPlayersForPeriodFiltered(...)`；
3. 4A-2 流白名单（`construction-tracker.md:48`）含 `PlayerDataStore`；
4. PlayerDataStore 是 period 查询的**既有归属**（`getRankedPlayersForPeriod:1150` 是模板，
   `:1219` 实现与之 SQL 同构只多 `IN` 过滤），同层同位、零跨层重组。

故"§3.4 标给 CLS-1"系 story 归属与文件归属的措辞混淆，实现落点正确。

---

## 五、范围外发现（只上报，未修）

| 位置 | 现象 | 严重 |
|---|---|---|
| `LearnCommand.java:794-800` | 班名反查采用遍历 `listClasses()` 找 id 匹配（O(n) 全表），注释自认"班级量小（管理员手工建班）"——量级假设成立，仅记录 | 信息 |
| `ClassBoardGUI.java:47` `EMPTY_SLOT = 22` | 空态槽 22 与"成员格 slot 9-53"在 `ENTRY_SLOTS` 中**重叠**（22 属 ENTRY_SLOTS）。javadoc 声称"与成员格互斥，不会同屏"——互斥由 `safeEntries.isEmpty()` 保证（空态只在零条目时渲染，此时成员循环 `displayCount=0` 不占格），逻辑成立；但 slot 重叠属**潜在坑**（未来若改"满员也显示空态提示"即冲突）。建议后续注释或改槽 | 低 |
| `AdminCommandHandler.java:720` | `synchronized (db.getConnectionLock())` 包裹"满员判定 + 换班"整段（可重入，内层 DB 方法同锁嵌套安全）——锁粒度跨越多次 DB 调用，属正确但重型；记录 | 信息 |

---

## 六、不确定项

1. **`class.title` 键的意图**：dispatch:46 草案表列有 `class.title「我的班级」`，但两语言
   文件均未落盘，且无代码消费（`/le class` 直达班级榜，无"我的班级"页）。判断为 4A-2
   实现自决砍掉该页（只做班级榜 GUI），非缺漏；若产品要求"我的班级"摘要页则属未覆盖需求。
   建议编排者向用户确认（此为需求面，非架构面）。
2. **`LearnCommand:815` 的 `getRankedPlayersForPeriodFiltered` 调用位于异步块内**——
   该方法是 PlayerDataStore 既有异步安全姿态（ResultSet 关闭后组装，不碰 Bukkit API），
   核实通过；但"异步块内调用链是否全程无 Bukkit API"属线程纪律维度（code/build tester 域），
   本报告仅从层间方向确认合规。

---

## 七、结论

分层方向 `Command/Core → Data` 单向成立，`GUI/Listener` 零 Data 依赖，无环，
冻结接口 9+2+1 项命中（1 项注册表漂移已定级为低），DI 单点无泄漏，
record 姿态合族。**PASS**，低级别改进项 4 条 + 信息项 1 条，不阻塞。
