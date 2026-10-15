# 代码质量报告 P3-W4-4A（班级系统 US-CLS-1+2）

### 判定：PASS

结论先行：**GO**（零阻断违规；4 项低风险/信息级记录，2 项不确定项）

审查口径：本仓库无 `LanguageManager` / `ConfigManager.getActiveConfig()`，i18n 入口实为
`MessageUtil`（`getPlayerMessage`/`getSenderMessage`/`getGuiMsg`），配置入口为
`ConfigManager.getMainConfig()`——按实际口径统计（见 agent 记忆 `code-review-api-entry-points`）。

## 逐维度结果

| # | 维度 | 结果 | 关键证据 |
|---|------|------|----------|
| 1 | i18n 覆盖 | PASS | class.* 18 键 zh/en 双侧齐全（zh.yml:812-830 / en.yml:813-831）；占位符与调用点逐一匹配；无硬编码透传 |
| 2 | 线程安全 | PASS | 命令链严格「主线程门/校验 → 异步 DB → runTask 回主线程」；`LearnCommand` 异步链合规；ClassBoardGUI 零查库 |
| 3 | DB 层 | PASS | 九方法全部 `synchronized (connectionLock)`；`getClassMembers` ResultSet 关闭后组装；`applyClassAdd` 换班顺序正确 + 同锁可重入 |
| 4 | 防御性 | PASS | 无参/半参全走 usage；`getClassByName` null 全处判空；entries null/empty 兜底；标题判定 null 安全 |
| 5 | 资源与生命周期 | PASS | ClassBoardListener 已注册（LetsLearnEnglish.java:333）；本批零新增 per-player map |
| 6 | 与既有纪律一致性 | PASS | 锚定前缀 startsWith（非 contains，未犯 F6-02）；drag guard 完整；menu-click + GUIUtils 白名单已 fan-in |
| 7 | 范围外 bug | 2 项 INFO | 仅上报（见问题清单） |

## 维度 1 — i18n 详情

- 玩家可见文案全部经 `MessageUtil`：`AdminCommandHandler`（getSenderMessage）、`LearnCommand`
  （getPlayerMessage）、`ClassBoardGUI`（getGuiMsg）。全仓 `plugin.getConfig()` 裸用点仅
  `ConfigManager.java:104`（既有唯一合法点），本批零新增。
- class.* 18 键：board-title / not-in-class / empty / disabled / created / deleted /
  member-added / reassigned / member-removed / member-not-in / member-full / name-exists /
  not-found / name-invalid / player-not-found / usage / list-header / list-entry —— zh/en 键名一一对应。
- help.class 存在（zh.yml:274 / en.yml:274）。
- 占位符逐一匹配（独立复核，与 build 报告一致）：
  created{class} / deleted{class} / member-added{player,class} / reassigned{player,class} /
  member-removed{player} / member-not-in{player} / member-full{max} / not-found{name} /
  name-invalid{max} / list-header{class,count} / list-entry{rank,player,score}；
  无占位符五键：usage / disabled / empty / not-in-class / name-exists。
- `class.usage` 的 `|` 位于 YAML 双引号标量内 → 字面管道符，非块标量指示符，合法（zh/en 均已实解析通过）。
- ClassBoardGUI 复用既有活键 `leaderboard.entry-season` / `gui.back-button` / `gui.back-lore`，
  非新造键；`"&6#" + rank + " &f" + playerName`（:132-133）与 LeaderboardGUI:193-194 逐字同款，
  为「动态值 + 颜色码固定包装」非玩家文案（既有先例，非违规）。

## 维度 2 — 线程安全详情

- `AdminCommandHandler.handleClassCommand`（:612）/ create / delete（:649,666）/ remove（:759）/
  list（:797）：门与参数校验在主线程，DB 段经 `runClassAsync`，反馈经 `sendOnMain`（内部 `runOnMain`）回主线程。严格。
- `handleClassAdd`（:679）三段式正确：**异步**查班存在 → **主线程** `resolvePlayerUUID`
  （离线名册 `Bukkit.getOfflinePlayer` 是阻塞 API，仅主线程安全）→ **异步** `applyClassAdd`。
  与 `resolvePlayerUUID` javadoc（:81-85）声明的调用契约一致。
- `runOnMain`（:847）先判 `plugin.isEnabled()` 再 runTask，并捕获 `IllegalStateException`；`runClassAsync`（:838）同捕获。停用窗口静默放弃，无半途异常。
- `LearnCommand.handleClassCommand`（:762）：主线程仅做门 + 纯函数窗口计算（`windowOf`/`currentSeasonId`）；
  全部 DB（getPlayerClassId / listClasses / getClassMembers / getRankedPlayersForPeriodFiltered）
  在 `runTaskAsynchronously` 内；结果经 `runTaskSafe` 回主线程后才 `new ClassBoardGUI().open(...)`。严格。
  `runTaskSafe`（:828）捕获 `IllegalStateException`（未判 isEnabled，但与 SeasonManager.runMain 姿态一致，见问题清单 L-1）。
- **ClassBoardGUI 零查库已验证**：grep `DatabaseManager|PlayerDataStore|plugin.getDatabase` 全部命中仅 javadoc 注释
  （:26），类体内零调用。数据由调用方传入（快照语义），`open` 内仅 Bukkit 渲染 API（createInventory /
  getOfflinePlayer / openInventory）——主线程执行，合规。

## 维度 3 — DB 层详情

- 九方法全部包在 `synchronized (connectionLock)`：createClass(:2421) / deleteClass(:2452) /
  getClassByName(:2481) / listClasses(:2506) / getClassMembers(:2532) / getPlayerClassId(:2572) /
  addClassMember(:2593) / removeClassMember(:2632) / getClassMemberCount(:2651)。
- `getClassMembers`（:2527）：先 `List<Object[]> raw` 收集（try-with-resources 内），
  **锁外**再组装 UUID/名字回落——纯内存操作，不碰 Bukkit API、不持语句句柄；脏 uuid 行 `continue` 跳过不拖垮列表。符合 E-04 范式。
- `applyClassAdd`（:712）换班顺序：`synchronized(db.getConnectionLock())` 内先
  `getClassMemberCount >= maxMembers` 判满（:728）→ **仅当不满**才 `removeClassMember(旧)` 再
  `addClassMember(新)`（:731-734）。已在本班走幂等早退（:722）。**不会丢班**。
- 可重入性：外层显式持 `connectionLock`，内层 `getPlayerClassId`/`getClassMemberCount`/
  `removeClassMember`/`addClassMember` 各自 `synchronized (connectionLock)` 为同一 monitor
  对象（`getConnectionLock()` 返回同一 `connectionLock` 字段，:2710-2712）。Java 内置锁可重入 → 无死锁。实现无误。
- `createClass` 同锁内先 `getClassByName` 后 INSERT（:2422）——同锁可重入，安全；跨服并发由 UNIQUE 约束兜底归 false。

## 维度 4 — 防御性详情

- 参数边界：create/delete/list 要求 `args.length >= 3`；add/remove 要求 `>= 4`；不足即
  `class.usage`。`args[1]` 读取有 `args.length > 1` 守卫（:618）。无参（args.length==1 → action=""）
  落 default 分支回 usage。全边界覆盖。
- `getClassByName` null 传播：createClass(:2422)、deleteClass(:2449)、handleClassAdd(:691)、
  handleClassRemove(:761)、handleClassList(:799) 全部判空。无 NPE 面。
- ClassBoardGUI：`entries == null → Collections.emptyList()`（:121）；空列表渲染 BARRIER 空态（:146-150）；
  `className == null → ""`（:118）。SkullMeta 用 `instanceof` 模式匹配守卫（:129）。
- ClassBoardListener 标题兜底：先比尺寸再看 `isClassBoardGUI`，后者对 plugin/title/空串返回 false（:86-88）；
  缺键时 `getGuiMsg` 返回 `[?class.board-title]`，`indexOf("{class}")=-1` → 取全串做前缀 → 不误命中。降级安全。
- `getRankedPlayersForPeriodFiltered`：`members == null || isEmpty()` 早退空列表（:1222）。

## 维度 5 — 资源与生命周期详情

- `ClassBoardListener` 注册确认：LetsLearnEnglish.java:333 `registerEvents(new ClassBoardListener(this), this)`。PASS。
- 零新 per-player map：ClassBoardGUI 仅持 `plugin`；ClassBoardListener 仅持 `plugin`；
  AdminCommandHandler.sendClassList 用**局部** `HashMap<UUID,Long>`（:812，方法内瞬态）。无泄漏面。
- 无 per-player 状态遗留 → PlayerQuitEvent 无需新增清理。PASS。

## 维度 6 — 纪律一致性详情

- 标题判定：`isClassBoardGUI` 采用「从 i18n 模式串截取 {class} 前固定前缀 → startsWith」，
  显式论证非 contains（:70-80），符合 F6-02 禁裸子串教训。与 ConfirmationGUI 的等值比对（isConfirmGUI）
  属不同形态（本界面标题含运行时班级名，无法整串等值），折中合理且判定/渲染读同一键（无双源漂移）。
  双重确认（前缀 + GUI_SIZE=54）姿态与 ChatHistoryListener/PersonalScenarioListener 一致。
- drag guard：`onInventoryDrag` 遍历 `event.getRawSlots()`，任一 `rawSlot < topSize` 即 cancel（:79-84）；
  `onInventoryClick` 只 cancel 上半区、下半区 return 不 cancel（:46-51）——与 ChatHistoryListener 逐字同款，完整。
- 音效：返回键 `SoundManager.playSound(player, "menu-click")`（:56）；开屏音由 SoundManager.onInventoryOpen
  经 `GUIUtils.isPluginGUI` 白名单统一播（GUIUtils.java:114-115 已补「班级榜」「Class Board」），无重复播放。

## 问题清单（均为非阻断）

| # | 严重度 | 位置 | 说明 |
|---|--------|------|------|
| L-1 | LOW | LearnCommand.java:820 / runTaskSafe:828 | 异步完成→主线程 open 之间无 `player.isOnline()` 复检。玩家在该窗口退服时 `openInventory` 对已离线 Player 无兜底守卫（`sendMessage` 分支安全）。同项目 SocialListener:665/687、SeasonManager:545 均有 isOnline 守卫，此处缺省。建议 fan-in 时补 |
| L-2 | LOW | DatabaseManager.java:2448-2452 | `deleteClass` 先锁外 `getClassByName` 再取锁删行——跨服并发删同名班时存在 TOCTOU：间隙被删则后续 DELETE 影响 0 行仍返回 true（报「已删除」）。仅多服并发管理场景，幂等语义下影响极轻 |
| I-1 | INFO | AdminCommand.java:306-380 | `onTabComplete` 未补全 `class` 的二/三级参数（create/delete/add/remove/list、班名、玩家名）；`class` 在 args.length==1 已列出，故非缺陷，仅补全体验缺口 |
| I-2 | INFO | ConfigManager.java:32-50 | `class-system.enabled/max-name-length/max-members` 未入 REQUIRED_CONFIG_KEYS 启动诊断名单（与 season/money 先例一致，仅告警不改行为；build 报告已记同项） |

## 不确定项

1. **跨服满员判定**：`applyClassAdd` 的 `synchronized` 仅串行化**本 JVM**；共享 MySQL 双服并发抢最后名额时可能超额。
   代码注释自述为「本地超额加入」防护（:718-719），US 描述为「数据跨服」——数据一致性成立，
   但 max-members 的**全局**硬上限未做 DB 级约束。属设计取舍，需确认是否接受（4B 若要求严格上限需另议）。
2. **隐藏成员可见性口径**：`/le class` GUI 路径用 `getRankedPlayersForPeriodFiltered`，保留
   `COALESCE(pf.show_in_square,1)<>0` 过滤（隐身成员不出现在班级榜）；而 `/lea class list`（管理路径）
   走 SeasonManager 缓存、plan 明示含隐身穿透。两者口径差异与 CHANGELOG 描述一致，确认为有意设计。

## 独立验证方式

逐一 Read 真源码：ClassBoardGUI(全) / ClassBoardListener(全) / AdminCommandHandler:560-864 /
DatabaseManager:340-460,2388-2665,2690-2712 / PlayerDataStore:1128-1294 / LearnCommand:720-835,1030-1143 /
AdminCommand:25-384 / ConfigManager:100-270 / MessageUtil(全) / GUIUtils:50-117 / messages/zh.yml:806-830 /
messages/en.yml:808-832 / config.yml:372-410,744-759 / schema.sql:225-244 / LetsLearnEnglish:315-334。
