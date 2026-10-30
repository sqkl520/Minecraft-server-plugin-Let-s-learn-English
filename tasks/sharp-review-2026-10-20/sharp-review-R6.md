# 锐评 R6 · 代码质量与可维护性（修完 72 条之后）

> 2026-10-20 战略锐评 · 开源维护者/接盘侠视角 · 只读 · agentId ac95eb546e978b3c8
> 编排者复核注记：抽查四条全中——① `data/` 之外 `getDatabaseManager().getConnection()` 实测 **17 处**（与报告一致）；② `LearnCommand.java:71` `requireEnabled` 确实"enabled→false、被拦→true"，javadoc 虽有说明但命名仍诱导出反（名字说"要求启用"，返回 true 意思是"已拒绝"）；③ `UpdateChecker.java:33` `dismissedPlayers` 确为裸 `HashSet`（:165 异步写、:141 判定）；④ 工单号风格注释实测 288 行命中其列举模式（R12-M1/MC-x/R-6x/PF-ACH/Fx-xx）。喵。

## 1. 一句话判决

**过程纪律一流、代码工程三流**——这不是一副"能交给别人接手"的库，而是一份用 4200 行审查文档给 3.3 万行单体代码打补丁的病历喵。

## 2. 致命伤 Top 3

1. **数据访问层根本没有边界**：`data/` 之外的业务层直接握着 JDBC——17 处 `getDatabaseManager().getConnection()`（`core/DailyChallengeManager.java:169,235,331,382,448,471,507,556,863,901`、`core/achievement/AchievementManager.java:81,220,529,568`、`core/manager/AdminCommandHandler.java:99,226`、`expansion/LLEPapiExpansion.java:363`），22 处 `getTablePrefix()`、29 条裸 SQL 全在 DAO 之外。`isSQLite()` 方言分叉 38 处散在 6 个文件，连 `gui/ChatHistoryGUI.java:613` 都在渲染层判后端方言。想换连接池/加读写分离？得改 6 个包喵。
2. **测试是装饰**：jacoco.csv 实测行覆盖 **1.86%**（269/14440），分支 1.35%，`listener/gui/data/command/config/api/expansion` **7 个包 0.0%**；49 个测试、754 行测试代码 vs 32,947 行主代码。1% 门不是"安全网"，是"证明门能跑"的自我安慰。无 CI（无 `.github/`）、无 git hook、pom 里零 SpotBugs/PMD/checkstyle 喵。
3. **主线程 IO 未修干净**：本轮把 3 个 GUI 改成 `openAsync` 两段式，其余照旧——`gui/EnglishSquareGUI.java:285` 在 `open()` 里 `getCoinsEarned()`，而它由主线程 `SocialListener.java:530/540` 直调；`WrongBookGUI.java:99,135`、`ProfileGUI.java:49,248` 同款。修的是点，不是面喵。

## 3. 分条炮击

**命名与抽象层次**。标识符全英文（零中文变量名，值得肯定），但注释 56% 中文 / 44% 英文混编（`DatabaseManager` 非 ASCII 注释 333 行 vs 纯英文 166 行，同一文件两种语言）。命名家族分裂：错题本同时叫 `WrongBookGUI`/`wrongBookClearedCache` 和 `difficult_words`/`DifficultWord`。布尔语义反向对撞：`CheckInManager.java:134` `isMoneyDisabledByConfig()`（返 `!isMoneyEnabled()`）vs `LearnCommand.java:662` `isEconomyAvailable()`（同一真源、正向表述）；`LearnCommand.java:71` `requireEnabled(...)` 返回 `true` 表示"被拦下了"——名字说"要求启用"，值说"已拒绝"，11 个调用点全靠读者脑内取反。`String[] getSessionPreview()`（`DatabaseManager.java:634`，注释 `[0]=system,[1]=user`）、`int[] getTodayWordProgress()`（`DailyChallengeManager.java:835`）用下标代替字段名。超过 80 行的方法 **44 个**（>120 行 17 个，>200 行 3 个）：`data/DatabaseManager.java:97 createTables` **339 行**、`gui/EnglishSquareGUI.java:93 open` 214、`listener/SocialListener.java:212 handleEnglishSquareGUI` 201、`core/ai/AIChatManager.java:801 dispatchChat` 200、`gui/ProfileGUI.java:38 open` 198、`core/manager/AdminCommandHandler.java:345 importWordBank` 179。IO+业务+UI 同体的最脏一处是 `AIChatManager.dispatchChat`（902 写库 / 920 HTTP / 965 写库 / 983-995 回主线程回调）与 `DailyChallengeManager.onWordCorrect:652-682`（门→查库→写库→发钱→发聊天）喵。

**复制粘贴**。① `private ItemStack createItem(...)` **20 份拷贝**分布在 18 个 GUI 文件（`AchievementWallGUI:253`…`WrongBookGUI:213`），GUIUtils 早该有；② 异步两段式打开模板 4 份逐字重复（`DailyChallengeGUI.java:84-128`、`LearningCalendarGUI.java:111-155`、`ChatHistoryGUI.java:150-200` 与 `:355-382`）——同一 token 去重 + 同一 `关服竞态` 注释 + 同一失败提示，只抽了 `SchedulerGuard` 没抽流程；③ `RewardEngine` 发币六连拷贝（292/337/382/437/538 deposit + 470 withdraw，门+cap+clamp+落账+消息全同）；④ `DatabaseManager` 三个榜 `getTopCoins:855/getTopWordsLearned:888/getTopCombo:920` 仅列名不同；⑤ `claimStripeLocks` 32 锁数组在 `DailyChallengeManager:112` 与 `MiscGUIListener:48` 各一份且互不相同对象（审查 R12-L1 已记录但未抽公共类）喵。

**异常处理**。零 `printStackTrace`（好）。242 个 catch，其中 182 个抓宽类型；**16 个不打任何日志**，7 个连注释都没有——最糟三处：`AchievementManager.java:538`（SQL 异常→静默返回半截 days 集合，玩家连击直接算错）、`:575`（异常→`return 0`，成就进度悄悄归零）、`:653`（`catch (Exception)`→`current=0`）；`DailyChallengeManager.java:874` 只有 `// ignore`；`LLEPapiExpansion.java:359-377` 的 `getLongestStreak` 直接吞掉（注释写着 "Silently return 0"——诚实但危险）。另有 30 个 `catch (... ignored)`，其中 `ReportGenerator.java:1060` 与 `TtsManager.java:335` 抓的是 `Exception`/`Throwable`，把 `NPE`/`OOM` 一并吞了。137 条日志不带玩家标识；`updateChecker` 的 `dismissedPlayers` 是裸 `HashSet`（`core/UpdateChecker.java:33`，异步线程 `add`、主线程 `contains`）——SpotBugs `ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD`/并发类必抓喵。

**注释与文档**。密度 23.6%（7,779/32,947），但**注释主体是给审查台账回味的**：903 处工单号（`R12-M1`/`MC-5`/`R-67`/`PF-ACH-02` 等 223 个不同 ID），`AIChatManager` 一个文件 66 处。这些 ID 不在代码里、不在 issue 里，只在 `tasks/review-2026-10-18/`——外部贡献者读到 `// R12-M1（E1）：主线程首部登记本次打开 token` 只能懵。还有注释指向易碎的行号：`AchievementWallGUI.java:29`（`SocialListener L110/L133/L159`）、`ChatScenarioListener.java:40`（`LearnCommand:349`）、`AchievementManager.java:258`（"原 tryUnlock L161-188"）。javadoc 撒谎两处：`api/LetsLearnEnglishAPI.java:19` "All methods are designed to be thread-safe" 而它委托的 `PlayerDataStore.getProfileSettings:1290` 用 `computeIfAbsent` 建可变对象并写缓存（跨线程可见性无保证）；`data/PlayerDataStore.java:36` "All cache operations are thread-safe via ConcurrentHashMap" 但内层值是普通 `HashMap`/`HashSet`（`AchievementManager.java:75,189`）。魔法数字：`translateAlternateColorCodes` 344 处无封装；`AchievementWallGUI.java:245-247` 裸 `86400000/3600000`（`long` 除 `int` 字面量，无常量无来源），`ReportGenerator.java:689` `keepDays()*86400000L` 靠 `L` 救命；`20L` tick 常量 9 处散落。成就阈值**三源并存**：`Achievement.java` 枚举 target、`AchievementManager:387,418` 硬编码 `>=500`、成就文案里的中文数字——改一个阈值要碰三处，`getTarget()` 只被 `:656` 的进度行用喵。

**死代码与冗余**。`util/VersionAdapter.java:21` 的 `minorVersion` 赋值后从未被读（本轮删了 getMaterial/getSound 后留下的半截）；`core/progress/PlayerProgress.java` 68 行 POJO 自述"仅测试引用"（`ProgressManagerImpl.java:10` 注释承认消费方法已删）；`api/LetsLearnEnglishAPI` 的 `getPluginVersion/getPlayerAge/setPlayerAge` 全仓零调用，而该类是唯一"对外 API"，插件本身不发布接口层；`SeasonManager.java:794/798`、`SocialManager.java:474/478`、`ReportGenerator:1132`、`LearnCommand:894` 保留着只转调 `SchedulerGuard` 的薄委托——6 个空壳方法。过度包装同样明显：`LetsLearnEnglish.java` 49 字段 + 32 getter 的全局服务定位器，`plugin.getMessageUtil()` 364 次、`getConfigManager()` 193 次、`getDatabaseManager()` 125 次，每个 manager 都反向持有它，没有一处真正的依赖注入。`versions/` 里 7 个 JAR（1.4 MB）提交进 git 喵。

**可读性 Top5**（LOC / >80行方法 / 工单号注释）：① `data/DatabaseManager.java` 3031/1/43——339 行 `createTables` 内嵌 21 张表 DDL，与 `resources/schema.sql` **双真源**；新人 2 周。② `core/ai/AIChatManager.java` 1522/1/60——HTTP+DB+Bukkit 调度+调试日志绞在一起；1 周。③ `listener/SocialListener.java` 849/3/18——标题反解模式路由 + 快照契约隐式跨文件对齐；1 周。④ `core/duel/DuelManager.java` 1395/3/45——状态机散在 10 个 `Map<UUID,...>`；1.5 周。⑤ `command/LearnCommand.java` 1208/3/48——37 段 if 派发、单方法 123 行；4 天喵。

**静态分析视角**。引入工具预计爆量顺序：SpotBugs 以 **EI_EXPOSE_REP**（`getScenarioConfigs()` 直接返回可变 `LinkedHashMap`，`AIChatManager.java:1345/1429`）、**NP_NULL_ON_SOME_PATH / 吞异常 REC_CATCH_EXCEPTION**、并发（IS2_INCONSISTENT_SYNC）为主；PMD 以 **ExcessiveMethodLength（44 个）**、**CyclomaticComplexity**（`MiscGUIListener:157 handleWordBankSelect` 94 行 switch 化槽位）、**GuardLogStatement**、**UnusedPrivateField** 为主；checkstyle 首先死在**行长**（310 行 >120 字符，`AIChatManager` 34 行）与**重复字面量**（`"IllegalStateException"` 块、玻璃板材质）。之所以只有 1% 门：绝大多数代码是 Bukkit 静态耦合（`Bukkit.getScheduler()`/`ChatColor`/`Inventory`），不引入 mock-bukkit 就写不出单测——门低不是态度问题，是可测试性欠账的诚实记账，但 pom 注释说"每次升一档"却没有任何一档真升过喵。

**一致性**。同族 GUI：4 个有 `openAsync`（DB 移出主线程），5 个仍在 `open()` 里查库——同一个类名、两种契约。参数顺序：`AchievementWallGUI:54`/`ProfileGUI:38`/`LearningCalendarGUI:111` 用 `(Player viewer, UUID targetId)`，`DailyChallengeGUI:84` 用 `(Player)`，`EnglishSquareGUI:93` 用 `(Player,int,RankingMode)`；参数命名 8 种变体（`UUID playerId` 222 / `uuid` 20 / `id` 16 / `playerUuid` 7）。null 策略三套并存：48 个方法 `return null`、Optional 仅 1 文件 7 处、`@Nullable` 6 处——同一个 `getFeedback` miss 返 null、`getProfileSettingsReadOnly` 返新默认对象（`PlayerDataStore.java` 注释自己解释两种选择）喵。

## 4. 说句公道话

卫生面在这个体量的单人项目里算罕见：零 `System.out`、零 `printStackTrace`、零中文标识符、零 SQL 拼接注入、i18n 键 hyphen 统一 518:3、725/725 双语键对称、注释密度 23.6% 且大量写的是"为什么"而不是"是什么"、`SchedulerGuard` 这类抽取说明作者知道什么是重复、`dead-config-audit.md` 用 grep 逐键证死活的功夫比多数开源仓库的 CI 还扎实。**它不是烂码，是没有第二双眼睛的码**喵。

## 5. 只改一件事

**把 JDBC 从业务层赶出去**：新增 `repository/` 层（按表分文件，`SqlDialect.upsert(table,keyCols,valCols)` 统一 `INSERT OR REPLACE`/`ON DUPLICATE KEY` 分叉，锁与重连只在 DAO 内部），删掉 `DailyChallengeManager`/`AchievementManager`/`AdminCommandHandler`/`ChatHistoryGUI`/`LLEPapiExpansion` 里的 17 处 `getConnection()`、22 处 `getTablePrefix()`、29 条裸 SQL。它一次性解决分层缺失、方言散布、吞异常难定位（DAO 统一记 `playerId+SQL`）、以及"GUI 主线程查库"（DAO 天生提供 async 入口），且不需要引入新框架。别碰覆盖率数字——先碰调用点数量喵。
