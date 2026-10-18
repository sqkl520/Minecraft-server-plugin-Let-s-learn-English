# findings-r5v 决斗域红队验证报告（R5-V）
- **验证对象**：`tasks/review-2026-10-18/findings-r5.md`（编排者接管产出）中的 R5-M1、R5-L1，及其"已核验 clean 正面清单"、review-plan §B-R5 未尽项
- **验证方法**：不信任转述，全部回源精读——DuelManager.java 全部任务族/清理/重连/结算区段、DuelRoom.java、DuelGUI.java、DuelGUIListener.java、DuelChallengeGUI/Listener.java、DuelListener.java、MiscGUIListener.java、ChallengeCompleteGUI.java、LearnCommand duel 分支；对每条主张做"链路闭合/反证搜索"（含全仓 grep 兜底通道），另补做 ChallengeCompleteGUI drag 面、DuelGUI 极端数据面、模块门/粒子音效面
- **总判定：GO-with-fixes** —— R5-M1 三子主张全部**证实**（机制静态闭合）；R5-L1 缺陷**证实**但复现表述需修正（"无 M1 覆盖链"的独立构造不成立）；clean 清单抽查 5 条全部站得住，未见漏网反驳；另立 **1 条新中危 R5V-1**（DuelGUI 空槽 shift-click 塞入 → 刷新即丢物）+ 2 条低级观察
- 审查时间：2026-10-18
- 备注：本文件由编排者按该 agent 返回全文原样保存（harness 禁止子代理写文件）

---

## 一、逐条验证表

| findings 编号 | 判定 | 证据（file:line + 原文） | 备注 |
|---|---|---|---|
| **R5-M1 子主张 (a)**：graceTasks.put 覆盖旧任务无 cancel | **证实** | `DuelManager.java:800-805` 入口仅 `disconnectTimers.put(...)`，无"已有任务先清理"守卫；`:876` `graceTasks.put(playerId, task);` 无条件覆盖，全方法体（800-877）无任何 `graceTasks.remove/cancel` 前置。对照 `selfCancelWordTimeout` 双参先例（:580-584）与 `sendDuelRequest` 的 R-68 先例（:164-173、:182-185 反复出现"先 cancel 旧任务再 put"模式）——本处独缺 | 全覆盖 grep 确认 `graceTasks` 全仓只此 6 处触碰（805/821/828/842/876 + 884-891/1099-1102/1134），无隐藏清理点 |
| **R5-M1 子主张 (b)**：821-823 提前 return 不 cancel 自己；注释"below"不存在 | **证实** | `:821-823` `if (!disconnectTimers.containsKey(playerId)) { return; // timer will be cancelled below }` —— 该 return 之后任务体即刻退出，下方 (:826 房间拆解分支 / :842 到期分支) 永不可达；全文件无第二次向该 lambda 注入取消的机制。"below" 在 runTaskTimer 改写后确为**过期注释** | 补充闭合：taskA 自步骤 3 起不在 `graceTasks` → `cleanup`(:1099-1102)、`shutdown`→`cancelAllTasks`(:1134)、`handleReconnect`(:888-891) 三处 remover 全部以 map 当前值为准，对 taskA **不可见**；全仓 grep `cancelTasks(` **零命中**（无 catch-all 兜底）→ 僵尸任务至服务器关停，与 findings 影响面 a) 完全一致 |
| **R5-M1 子主张 (c)**：步骤 4 handleReconnect 在已离线玩家上执行，下游无 isOnline 守卫 | **证实** | `:882-885` 入口仅 `info == null` 检查（步骤 4 时 info2 非 null，**不能**拦下）；`:918` `new DuelGUI(plugin).open(player, room)` → `DuelGUI.java:42-52` 直达 `player.openInventory(inv)` **无守卫**；`:919` `setupScoreboard(player, room)` → `:1225` `player.setScoreboard(board)` **无守卫**（对照：同类 `removeScoreboard` :1270-1271 **有** `if (player.isOnline())`——同文件内自证守卫意识被漏掉）；`:910` `player.sendMessage`、`:928-932` BossBar removePlayer/addPlayer 同样无守卫。下游唯一带守卫的是 `refreshBothGUIs`(:1307/1310) 与 `updateBothScoreboards`(:1231/1234)，均不在本路径 | 影响面 a/b/c 三重全部成立：b) "对局悬挂"再证——全仓无第二掉线判负通道，宽限任务链是唯一入口（双方掉线场景原本靠到期分支 30s 收口，被 M1 破坏后 A 侧永不判负）；c) 离线 openInventory 行为仍为 [中]（未实测），机制无守卫为 [确定] |
| **R5-M1 触发窗口描述**（1 秒连锁时间线） | **证实** | `DuelListener.java:34-43` MONITOR 优先级 + 闭包捕获 Player + `runTaskLater(..., 20L)`——0.8s 二次 quit 落在窗口内完全可达；`handleDisconnect` 二次进入时 room 仍 `isActive()`（:803 守卫放行） | findings 给出的 5 步时间线逐步复算无误；M1 整体**证实** |
| **R5-L1**：handleReconnect 缺 isOnline 守卫 | **部分**（缺陷证实 / 复现表述需修正） | 缺陷 F 本身：证实（同 M1-(c) 证据链）。**但**："另任何 join 后 1 秒内再退的普通场景（**无 M1 覆盖链**）也会走到"句不成立——构造分析：HR 回调非空摘取（info 非 null）要求 (join, join+1s) 窗口内发生过 quit，而该 quit 进入 handleDisconnect 时，上一轮 quit（rejoin 前的那个）留下的 taskA **必然仍在 graceTasks 中未被取消**（唯一取消者 HR 未跑、cleanup 会同时清 info、到期分支 30s>1s 不可能）→ quit#2 的 put **必然**覆盖 taskA（即必然产生 M1 覆盖）。故 L1 的每次现实触发都伴随 M1 链；**但**：一旦按 M1 修法 1 修复覆盖（先 cancel 旧任务），该"无 M1 链"复现将立即变为**独立可达**（此时 quit#2 正常 cancel taskA、taskB 被 HR 误取消 → A 新掉线无宽限 → 与 M1 同款对局悬挂）——两处缺陷必须同修，先修 M1 不修 L1 会换一条路径复发 | 修法建议（入口 `!player.isOnline() return` + 改用 UUID 捕获）与原 findings 一致，认同 |

### 对"已核验 clean 正面清单"的抽查（5 条，未见漏网反驳）

| 抽查项 | 结论 | 证据 |
|---|---|---|
| 清单 1：wordTimeout 自取消三路径 + 双参 remove | **站得住** | `:531-533` 房间拆解 / `:537-539` round 不匹配 / `:545-547` 正常到点，三路径均 `selfCancelWordTimeout`；`:580-584` `remove(room.getPlayer1(), task)` 双参 + 无条件 `task.cancel()`（注释 :571-578 自证"条目已摘仍须 cancel 自己"）。与 grace 链对比：wordTimeout 即便被 put 覆盖，stale 任务因"每路径都自取消"最多多活 1 tick——**僵尸模式确实在本链不存在**，清单断言成立 |
| 清单 2：DuelRoom 哨兵语义 | **站得住** | `:142` `lastCorrectAnswerer = new UUID(0L, 0L);` 封印 + `:84`/`:136` 双处 `!= null` 拒答；`:139-141` 注释自证。`timeoutCurrentWord` :129-138 三重校验（active+currentWord / round 匹配 / 未答）齐全 |
| 清单 3：P2-08 快照遍历 | **站得住** | `:971` `for (DuelRoom candidate : new java.util.HashSet<>(activeDuels.values()))` 快照防并发；:958-989 离线目标按名字回退扫描、:990-993 NOT_IN_DUEL 分支反馈齐全 |
| 清单 4：accept 消费语义 | **站得住（抽查）** | `:242-269` 门→peek(:252)→校验链（offline :261 / busy :266 等）→失败分支全不消费；R-68 token 语义在 sendDuelRequest :203-228 复核一致（`remove(key,value)`+成功才清 task 表）。最终 consume 行未逐字回读（在 :270+ 未读区段），依据注释与上下游模式判 [高] |
| 清单 6 部分：DuelGUI/DuelGUIListener 双守卫 | **站得住** | `DuelGUIListener.java:36-44` 上半区 raw-slot 守卫 + `:69-81` 独立 DragEvent 守卫齐全；slot22 显式音效 :50-54（R-86 注释自证） |

---

## 二、补完部分 findings

### 补完-1（R11-L4 从物品交互安全角度复核）：ChallengeCompleteGUI 点击/拖拽面 —— **无新 bug**

- **点击**：`MiscGUIListener.java:44-80` 标题白名单含 "Challenge Complete"/"挑战完成"；上半区命中即 `:63 setCancelled(true)`；`:303-316` slot 8/22 走 `popBack`（R55/F1-09 语义与注释一致）。**路由安全性主动排雷**：if-else 链中 "Daily Challenge"（:75）先于 "Challenge Complete"（:77）判定——若后者标题含前者子串会被错路由；实际配置 en `complete-title: "&6&lChallenge Complete!"` / "Daily Challenge"（en.yml:386/426）+ zh "挑战完成！"/"每日挑战"，**无子串包含关系，路由正确**。
- **拖拽**：R11-L4 说的 pushBack 是导航面（另属 R8 交叉）；物品面 **drag guard 已存在**——`MiscGUIListener.java:328-348`（R-55e，注释自证"抄 DuelGUIListener"），拖拽触及上半区即 cancel。
- **物品能否拖出**：不能。拖出（top→bottom）drag 的 rawSlots 含 top 槽 → :343-345 取消；shift-click 顶部物品（rawSlot<topSize）→ :61-63 取消。**shift-click 从背包塞入**：27 格被 :59-62 循环 + 五件套**填满全部 27 格**（0-7/9/10/12/14/16-21/23-26 玻璃 + 8/11/13/15/22），原版找不到落点 → 天然免疫。结论：干净。

### 补完-2：DuelGUI 极端数据逐格抽查

- **玩家名 >16 截断一致性**：scoreboard `:1202`/`:1211` `substring(0, 16)` 硬截断，GUI 侧 `:170-173`/`:188-191` 用**全名**渲染 {player} —— 存在口径差异，但原版 Java 账号名 ≤16 恒成立，**实际不可达**；残留理论面仅"截断点若劈 surrogate/多字节"（原版名限 ASCII 字符集，亦不可达）。判：**不立案，观察项**。
- **分数 5 格渲染循环边界**：`:160-167`/`:178-185` `for (i < DuelRoom.WIN_SCORE)` 索引 5 元素数组 `{10,11,12,19,20}`；WIN_SCORE=5（DuelRoom:15）→ i∈[0,4] 安全。分数不会 >5：submitAnswer 得分后经 advanceOrEnd→isFinished→endDuel 即刻置 inactive（:423-436/:149-151），后续答案走 NOT_IN_DUEL。渲染顺序上 :390-392 先刷新再判结束，score=5 时 i<5 全绿、无越界。判：**当下安全，但存在隐式耦合** → 立 R5V-2。

### 补完-3：review-plan §B-R5 前任未尽项核验

- **DuelChallengeGUI 空态/尾槽 PDC no-op**：**证实为真 no-op**。空态物（:147-150 BOOK）与尾槽"更多"物（:161-165 BOOK）无 PDC 键；监听器 `DuelChallengeListener.java:71-73` 先查 `getType() != Material.PLAYER_HEAD` 即 return、:128-140 `readTargetId` 对无键/坏值返回 null——双重拒收。全 54 格预填（:117-120）+ drag guard（:104-115 同源标题等值+尺寸双重确认）→ 拖拽面亦干净。>42 人截断文案 {count}=shownCount=41 与语义"前 41 位"一致（:153-166）。
- **/le duel cancel 与模块门放行语义**：**与 plan 一致**。`LearnCommand.java:642-645` 注释自证特殊语义；quit/gui 门 `isDuelEnabled() || duelInProgress`（:648-663）；accept/发起恒过门（:655/:685）；cancel 分支刻意**不加** duel.enabled 门（:666-682）+ `letslearnenglish.admin` 权限门（:672-676）+ 用法提示（:677-681）→ 转发 `cancelDuelByAdmin`。
- **粒子/音效面**：duel 包**无任何 Particle 调用**（grep 零命中，粒子面无核查对象）。音效 15 处调用点逐一核对接收者守卫：expiry(:217-224 守卫)、correct/incorrect(:380/:396 事件源必然在线)、countdown-tick(:559-564 守卫)、timeout(:618-626 守卫)、endDuel(:740-760 守卫)、quitDuel(:780-786 守卫)、grace 到期(:848-856 守卫)、GM 解散(:998-1013 守卫)、DuelChallenge(:93 点击源)、ChallengeCompleteGUI(:120 命令源在线)。**全部有 isOnline 或源即在线的天然保证，干净**。

---

## 三、新发现

### R5V-1 [bug·中·高] DuelGUI 空槽未封 → shift-click 从背包塞入物品，下一题刷新即随旧 Inventory 弃置丢失

- **位置**：`gui/DuelGUI.java:150-222`（buildInventory 只填 9-17/10-12/14-16/19-20/13/22，**空槽 0-8、18、21、25、26 共 13 格无占位**）；`listener/DuelGUIListener.java:39-41`；`core/duel/DuelManager.java:1304-1313`（refreshBothGUIs）
- **证据原文**：
  ```java
  // DuelGUIListener.java:39-44 —— bottom 点击（含 shift-click）在 rawSlot 检查处直接 return，永不进入 setCancelled
  int topSize = event.getView().getTopInventory().getSize();
  int rawSlot = event.getRawSlot();
  if (rawSlot < 0 || rawSlot >= topSize) return;
  event.setCancelled(true);
  ```
  ```java
  // DuelManager.java:1307-1308 —— 每题推进/超时都对仍在看的玩家重建新 Inventory
  if (p1 != null && p1.isOnline() && DuelGUI.isViewingDuelGUI(plugin, p1)) {
      new DuelGUI(plugin).open(p1, room);
  ```
  （`DuelGUI.java:49-51` 每次 `Bukkit.createInventory(...)` 新建对象，旧对象无引用保留）
- **复现**：决斗进行中，玩家在自己背包 shift-click 一件物品（ClickType.MOVE_TO_OTHER_INVENTORY；事件 rawSlot = 下半区下标 ≥ 27 → 监听器不 cancel）→ 原版把物品移入对决界面空槽 0-8 等 → 下一题答对/超时触发 refreshBothGUIs 重建界面（或 ESC 关闭）→ 旧 Inventory 对象弃置，物品**静默消失**。拖拽路径已被 DragEvent 守卫封死，唯独 shift-click 面漏。对照：`DuelChallengeGUI.java:117-120` 与 `ChallengeCompleteGUI.java:59-62` 均全格填充天然免疫——DuelGUI 是全决斗域唯一裸空槽界面。
- **修法**：(1) 首选——`buildInventory` 照 DuelChallengeGUI 模式用灰色玻璃铺满 13 个空槽（视觉统一 + 根因封堵）；(2) 纵深——DuelGUIListener 对 `event.getClick() == ClickType.MOVE_TO_OTHER_INVENTORY` 的 bottom 点击补 cancel。同模式（rawSlot 守卫放行 bottom shift-click + 界面留空槽）在 MainMenuListener.java:46-48 等 GUI 可能存在，**跨域，另记 R8/R13 面，不在此立案**。
- **置信度**：[高]（代码静态 + 原版 shift-click 语义成立；素材已备）；物品丢失的确切时机（旧对象 GC/弃置 vs 关闭即丢，均为不回退）[中]（vanilla 自定义容器语义未真机实测）。

### R5V-2 [quality·低·中] WIN_SCORE 常量与 5 元素槽位数组的隐式耦合（渲染层无防护）

- **位置**：`gui/DuelGUI.java:160/178`（`for (i < DuelRoom.WIN_SCORE)` 配 `int[] p1Slots = {10,11,12,19,20}`）
- **证据**：数组注释 "up to 5 slots"，循环上界取 `DuelRoom.WIN_SCORE`（当前 =5，DuelRoom.java:15）——两处独立常量靠"恰好相等"工作；scoreboard 分数渲染(:1206/:1215)无此耦合，GUI 侧一旦有人调 WIN_SCORE=6（或做成配置）未同步数组 → buildInventory 首答即 `ArrayIndexOutOfBoundsException`，对局全线炸。
- **复现**：静态推演即可；无当下触发（WIN_SCORE=5 恒定）。
- **修法**：循环上界改 `Math.min(DuelRoom.WIN_SCORE, p1Slots.length)` 或在两处数组旁加断言注释。
- **置信度**：[确定]（耦合事实）。

### 低观察（不单独立案，汇总备忘）
- `handleReconnect` 对离线玩家还会执行 `bar.removePlayer/addPlayer`（:928-932）与 `plugin.getPlayerLanguage(player)`（:1188 经 setupScoreboard）——同为 M1-(c)/L1 的越界操作面，随同一条 isOnline 守卫修复一并收口即可。
- `DuelListener` join 侧闭包捕获 Player 引用（:39-41）为 M1/L1 共同诱因，建议随修法改为 UUID + `Bukkit.getPlayer(id)` 复取（与 findings 范围外建议一致，认同）。

---

## 四、审查覆盖声明

- **逐字回源精读**：DuelManager.java（约 70% 篇幅：任务族 505-659/770-877/980-1019、重连 882-938、清理/关停 1085-1183、scoreboard 1187-1296、刷新/查询 1289-1357）、DuelRoom.java（1-144 全）、DuelGUI.java（全）、DuelGUIListener.java（全）、DuelChallengeGUI.java（全）、DuelChallengeListener.java（全）、DuelListener.java（全）、ChallengeCompleteGUI.java（全）、MiscGUIListener.java（1-120/290-349）、LearnCommand.java（duel 相关全分支）。
- **全仓兜底 grep**：`graceTasks|disconnectTimers|wordTimeoutTasks` 全触碰点、`cancelTasks(`（零命中，僵尸任务闭证关键）、`handleReconnect|offlineNotices`、`ChallengeComplete|challenge-complete` 全引用、`isShiftClick|MOVE_TO_OTHER_INVENTORY`（零处理点 → R5V-1 依据）、`InventoryMoveItemEvent`（零命中）。
- **未复读/未实测**：DuelManager 剩余约 30%（含 acceptDuel 尾部 consume 行 :270+、调度器下界区段）、ChatAnswerListener/PlayerJoinListener/AdminCommand 原文（未再核）；M1 真机复现未做（三子主张静态闭合，真机为可选加强）；clean 清单抽查 5/9 条；离线 openInventory 具体行为未实测（维持 [中]）。
- **预算**：约 34 次工具调用，符合 30-50 上限。
