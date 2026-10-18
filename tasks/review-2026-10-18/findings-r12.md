# findings-r12 操作逻辑与状态机专项（审查 Agent 产出）
- 审查范围：跨域状态机/竞态/物品交互穿行——已完成深挖：①全仓物品交互安全全表（11 个 GUI listener 的 click/drag 守卫全覆盖扫描）②DailyChallengeGUI/LearningCalendarGUI openAsync in-flight 竞态（C-3/C-7 修复面全文精读）③Challenge claim / CheckIn 双击竞态（stripe lock、条件 UPDATE、deposit-then-mark 全文精读）④MainMenu/ConfirmationGUI/MiscGUIListener 中央分发面
- 审查时间：2026-10-18
- 结论摘要：高 0 条 / 中 3 条 / 低 2 条 / 待验证 3 条 / 豁免挑战 0 条
- 备注：本文件由编排者按该 agent 返回全文原样保存（harness 禁止子代理写文件；中途曾遭网关 502 击杀，由编排者指令收卷）

## 物品交互履历表（GUI × {click / drag / shift / numkey / double} × 处理与否）

全仓 `createInventory` 21 处 = 17 个 GUI 面（ProfileGUI/ChatHistoryGUI/EnglishSquareGUI 各含第二个窗口）。11 个 listener 实现了 click：ChatHistoryListener、ClassBoardListener、DuelChallengeListener、DuelGUIListener、MainMenuListener、MiscGUIListener（5 GUI 合一，含隐私设置/词库选择/过往学习/每日挑战/挑战完成）、PersonalScenarioListener、ScenarioSelectListener、SocialListener（广场/榜单/主页/成就墙四合一分发）、WordLearningListener、WrongBookListener。

| GUI | click 守卫 | drag guard | shift-click | numkey | double-click | 空槽填充（shift 塞入面） |
|---|---|---|---|---|---|---|
| MainMenu | ✅ rawSlot 上半区+null 双查+cancel | ✅ 同源标题 | 半弹出即 cancel（上半区），安全 | cancel 覆盖 | cancel 覆盖 | ✅ 全 27/36/45/54 槽填玻璃 |
| ConfirmationGUI | ✅ MainMenuListener 统一 | ✅ 同源 | 安全 | 安全 | 安全 | ❌ **仅 2 槽有物品，25 槽空**（缺口见 R12-M3） |
| WordBankSelectGUI | ✅ MiscGUIListener | ✅ | 安全（cancel 于上半区） | 安全 | 安全 | ❌ 仅 8 槽（7 词库+返回），其余空 |
| WordLearningGUI | ✅ WordLearningListener | ✅ | 安全 | 安全 | 安全 | ❌ 部分槽空（选项/按钮）|
| WrongBookGUI | ✅ WrongBookListener + ClickType 分流 | ✅ | **有语义**（Shift=练习）✅ | 安全 | 安全 | ✅ 54 全填玻璃 |
| DailyChallengeGUI | ✅ MiscGUIListener | ✅ | 安全 | 安全 | 安全 | ❌ 仅 4+2 槽，21 槽空 |
| ChallengeCompleteGUI | ✅ MiscGUIListener | ✅ | 安全 | 安全 | 安全 | ❌ 仅 8 槽，19 槽空 |
| LearningCalendarGUI | ✅ MiscGUIListener | ✅ | 安全 | 安全 | 安全 | ✅ 54 全填（显式注释"prevent item dragging"）|
| ProfileGUI ×2 | ✅ SocialListener | ✅ 同源全标题 | 安全 | 安全 | 安全 | 部分（border 19/17 槽 + 内容）|
| AchievementWallGUI | ✅ SocialListener | ✅ | 安全 | 安全 | 安全 | ❌ 仅 3 类槽 |
| LeaderboardGUI | ✅ SocialListener | ✅ | 安全 | 安全 | 安全 | ❌ 仅 displayCount 槽 |
| EnglishSquareGUI ×2 | ✅ SocialListener | ✅ | 安全 | 安全 | 安全 | ❌ 仅页面条目槽 |
| ScenarioSelectGUI | ✅ ScenarioSelectListener | ✅ | 安全 | 安全 | 安全 | ✅ 71 行循环全填 |
| PersonalScenarioGUI | ✅ PersonalScenarioListener + 尺寸双确认 | ✅ 同源 | 安全 | 安全 | 安全 | ✅ 103-110 全填 clone |
| ChatHistoryGUI ×2 | ✅ ChatHistoryListener + PDC 身份 | ✅ 同源 | 安全 | 安全 | 安全 | ✅ 121/267 两处全填 |
| ClassBoardGUI | ✅ ClassBoardListener + 尺寸双确认 | ✅ 同源 | 安全 | 安全 | 安全 | 部分（displayCount + 返回）|
| DuelGUI | ✅ DuelGUIListener + 截断同口径 | ✅ 同源 | 安全 | 安全 | 安全 | ✅ 分数格循环 + 关键槽覆盖，但见注 |
| DuelChallengeGUI | ✅ DuelChallengeListener + 等值标题+54 尺寸 | ✅ 同源 | 安全 | 安全 | 安全 | ✅ 117-119 全填 clone |

**关键机械事实**：全仓 `isShiftClick`/`MOVE_TO_OTHER_INVENTORY`/`NUMBER_KEY`/`HOTBAR`/`COLLECT_TO_CURSOR`/`DOUBLE_CLICK` 专项检查 = **零命中**。安全不靠 action 型检查，而靠"rawSlot < topSize → setCancelled(true)"的上半区封禁模式——该模式在 11 个 listener 全部到位，且 drag 全部有独立 guard（drag 是独立事件，click 的 cancel 拦不住，全部有显式处理）。履历表结论：**无 D-13 类型（展示物品可拖出）残留；shift/numkey/double 在"上半区 cancel"模式下天然安全**（Bukkit 对 cancelled InventoryClickEvent 不再执行任何移动逻辑）。唯一真正的语义差异面是 WrongBookGUI 的 Shift=练习（有意设计）。

注（DuelGUI 一行）：DuelGUI 无全槽填充（如 0-8、18-21 有间隙），但 DuelGUIListener 的上半区 cancel 覆盖全部 rawSlot（click 与 drag 独立双守卫），fill 不是安全必需而是纵深冗余——与 LearningCalendarGUI 的"filler 防拖"注释属同层但非唯一防线。

## 中危

### R12-M1 [logic·中·高] DailyChallengeGUI.openAsync 双击/连点窗口：同一玩家连续两次 openAsync → 两次异步预取 + 两次 runTask 渲染，后者覆盖前者界面（含中途 flash）
- 位置：`gui/DailyChallengeGUI.java:69-97`；触发点 `listener/MiscGUIListener.java:251,265`（领取成功/失败后自动重开）、`listener/MainMenuListener.java:150`
- 证据：
  ```java
  public void openAsync(Player player) {
      UUID playerId = player.getUniqueId();
      try {
          Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
              if (!plugin.isEnabled()) return;
              ...
              try {
                  Bukkit.getScheduler().runTask(plugin, () -> {
                      if (!player.isOnline()) return;
                      openWithData(player, challenges, wordProgress);
                  });
  ```
  无任何 in-flight 去重/序号（对比 DuelGUI 的 token 先例与 R12-M2 的日历同形）。领取路径（MiscGUIListener:211-265）在主线程内 `synchronized(stripeLock){claim…}` 之后立即 `openAsync`——玩家"领取→ESC 关→ 立即再点主菜单每日挑战"序列会产生 2 个异步任务：第二个在第一个的 render 之后执行 → `player.openInventory(inv2)` 覆盖，界面重建（无数据损坏，纯体验闪动 + 双倍 DB 预取）。
  更深一层：两次异步预取的 `getTodayChallenges` 含 `degradeStaleChallenges` UPDATE 与 `getTodayWordProgress` 的 `ensureWordChallengesAssigned`（可能 INSERT）——均幂等（stripe lock + INSERT OR IGNORE/ON DUPLICATE），无重复分配；但双份 SELECT+UPDATE 在主线程外的 connectionLock 上排队，放大为串行延迟。
- 复现：打开每日挑战 → 领取任一完成挑战（触发自动 openAsync）→ 立刻 ESC → 立刻再点主菜单【每日挑战】。或快速连点主菜单按钮两次（第二次点击在第一次 render 前到达）。观察：界面闪烁一次重建；服务器日志无异常。
- 影响：中等体验瑕疵 + 无意义的双倍查库；不产生数据错误（幂等链完整）。与 R11 待验证 1（领取后自动重开"拽回"）同根面——本 finding 补充了"连点/双窗口"维度。
- 修法：openAsync 内对`playerId`做 in-flight 去重（同 NBT/C-3 注释中的两段式模式，可加 static `Map<UUID, Long> openSeq` 或直接在重开点 251/265 前判 `player.getOpenInventory().getTopInventory()` 标题——更简单：领取重开仅在玩家当前仍开着每日挑战界面时才执行）。

### R12-M2 [logic·中·确定] LearningCalendarGUI 与 DailyChallengeGUI 的 openAsync"火并"未定义：两 GUI 交叉打开时后到者覆盖，无版本号/无取消
- 位置：`gui/LearningCalendarGUI.java:87-113` vs `gui/DailyChallengeGUI.java:69-97`；交叉入口 `listener/SocialListener.java:426`（主页日历）与 `listener/MainMenuListener.java:150`
- 证据（两个 openAsync 同构，均无 in-flight 序号）：
  ```java
  // LearningCalendarGUI.openAsync 主线程段
  Bukkit.getScheduler().runTask(plugin, () -> {
      if (!player.isOnline()) return;
      openWithData(player, dayData);
  });
  ```
  对比 DuelManager 的 token 先例（`wordRoundId` 单调递增防 stale timeout 落地）与 R-68 的 requestExpiry token——本族两 GUI 无同类防护。
- 复现（时间线，全部主线程事件）：
  1. t0.0 玩家从主页点【过往学习】→ 日历 openAsync 异步段开跑（预取 ~5-50ms）；
  2. **异步间隙内**玩家 quit→迅速 rejoin 或直接点击其他入口（如主菜单已开着无法点——实际可达路径：/le menu 命令或另一窗口）；更现实的：t0 时刻打开日历预取，t0+5ms 玩家执行 `/le challenge`（庆祝页直开）或点主菜单每日挑战（pushBack 后 openAsync）；
  3. 后者的主线程 render 先到 → 界面 A 打开；前者的主线程 render 后到（异步先完成回主线程排队）→ 界面 B 覆盖 A。
  玩家看到：刚打开的界面被"拽"成另一个界面（无崩溃、无数据损坏）。
- 影响：体验闪变（"我点的是日历，怎么变成挑战了"）；两个系统都正常，纯 UX 竞态。触发需要 ~50ms 内的两个入口操作，手速可达（键盘命令 + 鼠标连点）。
- 修法：与 M1 统一——两 GUI 增加 per-player `openToken`（AtomicLong，render 前比对），或编排层集中门（打开任何异步 GUI 前取消该玩家在途渲染）。最小改：两处 `openWithData` 前加 `if (player.getOpenInventory().getTopInventory().getViewers().isEmpty())` 不够（窗口已开着时新 GUI 合法覆盖是常态）——正解是 token 或 openSeq 比较。

### R12-M3 [security·中·高] ConfirmationGUI 空槽无填充 + 非 cancel 不覆盖全部槽位时 shift-click 理论面——实际核验为"被 MainMenuListener 上半区 cancel 全兜"，但空槽 = 玩家把 GUI 当"储物区"的可用性缺口（防御纵深缺口）
- 位置：`gui/ConfirmationGUI.java:126-127`（仅 2 槽 setItem）；`listener/MainMenuListener.java:47-56`（上半区全 cancel）
- 证据：
  ```java
  Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);
  inv.setItem(CONFIRM_SLOT, createItem(Material.EMERALD, itemName, itemLore));
  inv.setItem(CANCEL_SLOT, createItem(Material.BARRIER, cancelName, cancelLore));
  ```
  MainMenuListener 的 click guard 覆盖 confirm GUI（`ConfirmationGUI.isConfirmGUI(title)`），rawSlot<topSize 全 cancel → **空槽塞入被拦，无复现路径**。此条目因此降为可用性观测而非安全立案：与 LearningCalendarGUI:226 注释（"Fill remaining empty slots with filler panes to prevent item dragging"）/ WrongBookGUI:93 注释（"防止玩家把物品拖进空槽"）的既有口径相比，ConfirmationGUI/WordBankSelectGUI/DailyChallengeGUI/ChallengeCompleteGUI/AchievementWallGUI/LeaderboardGUI/EnglishSquareGUI/DuelGUI 8 个 GUI 未做全槽填充。防线的唯一依赖 = listener cancel 的正确性（若未来某 listener 标题匹配失配/被 rename，空槽即暴露）。
- 复现（实战不可达，展示纵深价值）：假设未来对 ConfirmationGUI 标题做了 i18n 自定义而 isConfirmGUI 的 zh/en 双配置比对漏改，则 click cancel 失配 → 空槽可塞物品、关 GUI 后退还玩家（无复制，仅塞入）。当前全部标题守卫闭合（isConfirmGUI 读配置双语言 + fallback），记为"逐步逼近防线"。
- 影响：当前零玩家可见后果；属防御纵深单点依赖。
- 修法：统一基建——GUIUtils 提供 `fillEmpty(Inventory)` 工具（抄 LearningCalendarGUI:227-232 三行），8 个 GUI 在 render 末尾调用；一劳永逸把"安全"从"每个 listener 标题判定全对"解耦。

## 低危

### R12-L1 [logic·低·确定] Challenge claim 的"deposit-then-mark"与 stripe lock 分属两层：MiscGUIListener 持锁段与 DailyChallengeManager.claimReward 内层锁同名同分片但对象不同
- 位置：`listener/MiscGUIListener.java:35,211-213,275-276`；`core/DailyChallengeManager.java:537,579-581`
- 证据：两处都以 `(playerId.hashCode() & 0x7fffffff) % 32` 分片，但 `MiscGUIListener.claimStripeLocks` 与 `DailyChallengeManager.claimStripeLocks` 是**两个独立的 Object[32]**（各自字段）。MiscGUIListener 在自锁内调 `depositChallengeReward` → `RewardEngine.depositCappedReward`，然后调 `DailyChallengeManager.claimReward`（进入 manager 自己的 stripe lock）。语义上外层锁保证"检查→发币→标记"整段串行（对 GUI 双击成立），内层锁保证 markClaimed 对其它调用点（如 /le challenge 命令路径若也走 claimReward）串行。
- 复现（静态）：GUI 双击 → 两次点击都在主线程事件队列，第一个点击走完整个 synchronized 段才轮到第二个 → 第二次进入时 `cp.claimed` 已重读为 true → return。**无双领**。命令路径与 GUI 路径并发（理论上跨线程？不——两者都主线程）——全主线程下双层锁冗余但无害。
- 影响：无实际 bug；锁命名/分片重复实现在两处（若未来改用 by-player lock map，两处可能失配）。属维护性注释。
- 修法：（可选）抽公共 stripe lock 工具类两处共用，或注释交叉标注"两锁独立但语义等价"。
- 置信度：[确定]（无双领成立：主线程事件串行 + 内层条件 UPDATE 双保险）；冗余本身是历史 R-02 的刻意设计（defense in depth 注释），不构成缺陷。

### R12-L2 [logic·低·确定] CheckIn 双击：playerLocks 按 UUID 的 computeIfAbsent 锁 + quit 时 unloadPlayer 移除——移除与"主线程内持锁"的时序安全成立，但 unload 只挂 quit 漏了关服路径的对齐
- 位置：`core/manager/CheckInManager.java:44-47,58-60`；`listener/PlayerJoinListener.java`（quit 编排点）
- 证据：
  ```java
  public CheckInResult checkIn(Player player) {
      Object lock = playerLocks.computeIfAbsent(player.getUniqueId(), ignored -> new Object());
      synchronized (lock) { return checkInLocked(player); }
  }
  public void unloadPlayer(UUID playerId) { playerLocks.remove(playerId); }
  ```
  quit 事件（主线程）时玩家已不可能并发 checkIn（checkIn 调用方 = 命令/GUI，均主线程）→ remove 安全。双击场景：两次 `/le checkin` 或 GUI 双击都在主线程串行 → 第二次进锁时 `today.equals(data.getLastCheckinDate())` 已 true → 返回 already。**无双领成立**（DB 条件在锁内重读）。R-76 注释自证安全前提。
- 复现：双击签到按钮/快速二连命令 → 第二次收"已经签到过"。正常幂等。
- 影响：无 bug。仅记录：unloadPlayer 不挂 onDisable（关服时 map 随实例销毁，无需）。
- 修法：无需改动（本条为正面核验记录，列低危仅为账册完整性）。

## 待验证区

### R12-V1 [logic·待验证] R11 待验证 1 的量化：领取成功后 openAsync 自动重开 vs 玩家 ESC 的"拽回"火候
- 方法：testharness 实机——领取瞬间按 ESC，观察 1-2 秒后界面是否重开、是否打断其它操作（如已切到另一个 GUI/正在输入聊天）。静态已确认路径存在（MiscGUIListener:251/265 → openAsync → openWithData 无"玩家当前界面"检查），舒适度需人评。

### R12-V2 [logic·待验证] openAsync 异步段跨玩家实例生命周期：deserialize 后的 Player 引用与 quit→rejoin 的同 UUID 复取
- 假设：`openAsync(Player player)` 闭包捕获 Player 实例；quit→rejoin 后旧实例 `isOnline()` 返回 false（两次 isOnline 复检均拦），新会话不复用旧闭包 → 无 stale render。静态推演安全，但"quit 与 rejoin 同一 tick 内"（Paper 快速重连）的 Player 实例语义未在 1.21.4 实测。
- 方法：实机 quit→rejoin 在异步间隙内（~5-50ms），观察是否有 stale 界面/重复打开。

### R12-V3 [logic·待验证] EnglishSquareGUI 的 `event.getClick()` 消费（SocialListener:170）对 double-click 的语义
- 已知：`handleEnglishSquareGUI(player, slot, title, event.getClick())` 是全仓两处 ClickType 消费点之一（另一处 WRONGBOOK Shift 分流）。FEED 模式右键等分支的 double-click 组合（DOUBLE_CLICK 是独立 ClickType，非 LEFT×2）是否落入预期分支——需读 handler 全文核（本轮预算内未完成，见未走查清单）。
- 方法：静态读 SocialListener.handleEnglishSquareGUI 的点击分支 switch/if 链，确认 DOUBLE_CLICK 的 default 行为无副作用；或实机双击广场头像。

## 豁免挑战

无（E 节豁免逐条对照未发现错误项；R12-L1 双层 stripe lock 的冗余是 R-02 刻意 defense-in-depth，非豁免争议）。

## 范围外发现

- **归 R6**：`DailyChallengeManager.getTodayChallenges:317-320` 在读取路径调 `degradeStaleChallenges`（写 UPDATE）——DailyChallengeGUI.openAsync 异步段调它 = "读接口带写副作用"在异步线程执行（connectionLock 保护下线程安全，但语义上读取即降级的状态迁移在 R6/R3 缝线上值得一次口径确认）。
- **归 R6**：`onWordCorrect` 的 M12 retry 链（hasPendingWordReward → onAllWordChallengesComplete）在**每答对一词**重入（:666-670），P3-08 口径下频繁 DB 查询（getDailyChallengeWords 每词一查）；量级评估归 R6/R13。
- **归 R8**：MainMenuListener.handleConfirmGUI 的语言切换两阶段（stage token 落 confirmStages map）与 quit 清理（确认 quit 清 confirmStages——本轮未核，见未走查清单）。
- **归 R4**：SocialListener:170 的 getClick 族分支细节（同 V3）。

## 审查覆盖声明

- **已全文精读**：`MiscGUIListener.java`（349 行全文，含 stripe lock、claim 全链、drag guard）、`DailyChallengeGUI.java`（208 行全文）、`LearningCalendarGUI.java`（261 行全文）、`CheckInManager.java`（190 行全文）、`DailyChallengeManager.java`（145-252、317-355、370-461、467-518、520-580、590-770、804-878 段）
- **已读关键段**：11 个 listener 的 onInventoryClick/onInventoryDrag 守卫段与标题判定函数（ChatHistory/ClassBoard/DuelChallenge/DuelGUI/MainMenu/PersonalScenario/ScenarioSelect/Social/WordLearning/WrongBook）；MainMenu/WordBankSelectGUI/WordLearningGUI/DuelGUI/ConfirmationGUI/WrongBookGUI/ProfileGUI 的填充与渲染段；zh.yml 全部 GUI 标题键；DatabaseManager 的 assignDailyChallengeWords/isDailyChallengeWordsCompleted/getDailyChallengeWordsProgress/updateCheckIn 语义
- **已核回归**：D4（C-3 openAsync 两处 DB 面全异步、close 竞态 R-67 isEnabled 双查+isOnline 复检——PASS，无主线程查库残留）、D5（C-7 同上 PASS）、D15（PF-MC-01 money 门终态标记+提示 PASS——MiscGUIListener:246-253 与 DailyChallengeManager:740-747 双分支都在）、物品交互履历表全表（17 GUI × 5 交互型）
- **未做完（诚实申报，未走查清单）**：①QuizState（WordLearningManager）逐事件转移表——R1 域已深挖五路径清理，R12 未复做 ②Onboarding step 0/1/2/3 转移表——未走查 ③Duel 状态机——R5 域已深挖（R5-M1 宽限覆盖僵尸任务），按要求不重复 ④Season settlement 抢占——未走查（R7 域范围） ⑤ChatSessionState/AI 会话——未走查（R2 域范围） ⑥EnglishSquareGUI 渲染是否对 `event.getClick()` 有覆盖不全（V3） ⑦confirmStages map 的 quit 清理对——未核 ⑧DuelGUI 空槽（0-8/18-21）与 DuelGUIListener 上半区 cancel 的逐槽对照（履历表已给结论"安全"，逐槽明细未列） ⑨ChallengeCompleteGUI 渲染面 8 槽的 detail（读了 click，未读 render 全文） ⑩openAsync 族"跨域火并"的更多组合（Calendar×DuelGUI 重开、openAsync×同步 open 的混排）
