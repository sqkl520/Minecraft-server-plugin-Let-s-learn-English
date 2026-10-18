# findings-r5 决斗（编排者接管产出）
- 审查范围：`core/duel/DuelManager.java`(1376)、`core/duel/DuelRoom.java`、`gui/DuelGUI.java`、`gui/DuelGUIListener`(内)、`gui/DuelChallengeGUI.java`、`listener/DuelChallengeListener.java`、`listener/DuelListener.java`、`gui/ChallengeCompleteGUI.java`(部分)、`listener/DuelGUIListener.java`
- 审查时间：2026-10-18
- 产出方式：原审查 agent 三度因 API 错误（超时×2、预扣费 403×1）死亡且零落盘；由编排者接管直审（全文精读 + 调用方追溯），可作为半独立审查使用。剩余未尽项转 R5-V 验证 agent。
- 结论摘要：高 0 / 中 1 / 低 1 / 待验证 0（另附大段"已核验 clean"正面清单）

## 中危

### R5-M1 [bug·中·高] 宽限任务覆盖窗口：1 秒内 quit→join→quit 连锁 → 僵尸任务 + 对局无宽限悬挂 + 延迟重连落在已离线玩家
- 位置：`core/duel/DuelManager.java:800-877`（handleDisconnect；876 行覆盖写）、`:821-823`（提前 return）、`:842-845`（expiry 摘除用单参 remove 摘到的是"别人"）；`listener/DuelListener.java:39-41`（join 后 20L 延迟才跑 handleReconnect）
- 证据：
  ```java
  // DuelManager.java:800-805（入口；无"已有宽限任务则先清理"守卫）
  public void handleDisconnect(Player player) {
      UUID playerId = player.getUniqueId();
      DuelRoom room = activeDuels.get(playerId);
      if (room == null || !room.isActive()) return;
      disconnectTimers.put(playerId, new DisconnectInfo(room));
  // :817-823（任务体；提前 return 路径没有 cancel 自己——注释"timer will be cancelled
  //  below"的 'below' 在旧实现里是循环末尾的 remove，改写 runTaskTimer 后该假设只对
  //  "被登记中"的任务成立）
      BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
          remainingSeconds[0]--;
          if (!disconnectTimers.containsKey(playerId)) {
              return; // timer will be cancelled below
          }
  // :842-845（到期分支：从 map 里摘出的 self 可能不是"本任务"）
          BukkitTask self = graceTasks.remove(playerId);
          if (self != null) { self.cancel(); }
  // :876（登记：无条件 put，不先取消旧任务）
      graceTasks.put(playerId, task);
  ```
  ```java
  // DuelListener.java:39-41（handleReconnect 延迟 1 秒执行，期间玩家可能再次退服）
  org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
      duelManager.handleReconnect(event.getPlayer());
  }, 20L); // 1 second delay
  ```
  ```java
  // DuelManager.java:882-891 / 894 / 918-919（handleReconnect 无 isOnline 守卫）
  public void handleReconnect(Player player) {
      DisconnectInfo info = disconnectTimers.remove(playerId);
      if (info == null) return;
      BukkitTask task = graceTasks.remove(playerId);
      if (task != null) { task.cancel(); }
      ...
      if (!room.isActive()) { ... return; }
      ...
      new DuelGUI(plugin).open(player, room);
      setupScoreboard(player, room);
  ```
- 复现（时间线，全部主线程事件，无需真并发）：
  1. t0.0 玩家 A（对局中）quit → handleDisconnect → `disconnectTimers[A]=info1`、taskA 排程并登记 `graceTasks[A]=taskA`；
  2. t0.5 A rejoin → PlayerJoinEvent 排入"t1.0 执行 handleReconnect"的延迟任务；
  3. t0.8 A 再次 quit（1 秒窗口内）→ handleDisconnect 再次进入（room 仍 active）→ `disconnectTimers[A]=info2` 覆盖、排程 taskB、**`graceTasks.put(A, taskB)` 覆盖 taskA 且从未 cancel taskA**；
  4. t1.0 延迟 handleReconnect 执行 → 摘到 info2（非 null）→ `graceTasks.remove(A)` 取到 taskB 并 cancel——**taskA 依然活着且已不在任何登记表**；`room.isActive()` 仍 true → 走"重连成功"路径：对**已离线**的 A 发消息（no-op）、`new DuelGUI(plugin).open(A, room)`、`setupScoreboard(A, room)`（无 isOnline 守卫）；
  5. t≥1.0 taskA 每秒 tick：`disconnectTimers` 已无 A → 走 821-823 提前 return，**永不 cancel 自己** → taskA 每秒空转到关服；cleanup()/shutdown() 只遍历 `graceTasks` 值，taskA 已不在表中→任何清理路径都造访不到它。
- 影响面（三重）：
  a) **僵尸任务**：1Hz 空转的 BukkitTask 泄漏（数量=触发次数），仅服务器整体关停才清；
  b) **对局悬挂**：A 离线但房间 active 且**没有任何宽限计时器**——A 永不自动判负；对手只能靠自己单方面打到 5 分结束（或 quit/管理员解散），否则本题循环无限续题；
  c) **离线玩家 GUI/计分板操作**（步骤 4）：对已离线 Player 调 openInventory/setScoreboard（无守卫）。
- 修法（三处小改，照既有先例）：
  1. handleDisconnect 置入前先清旧：`BukkitTask prev = graceTasks.remove(playerId); if (prev != null) prev.cancel();`（幂等，正常路径恒为空）；
  2. 821-823 提前 return 分支改为"自取消"：持 selfRef 数组（抄同文件 wordTimeout 的 `selfRef[0]` 模式），return 前 `selfCancel`；且 842 的行改用 `graceTasks.remove(playerId, selfRef[0])` 双参（抄 `selfCancelWordTimeout` 注释里已论证的双参理由——防止摘到"同房间新一轮任务"）；
  3. DuelListener 延迟回调 + handleReconnect 入口补 `if (player == null || !player.isOnline()) return;`（延迟回调落在已离线玩家上时直接放弃，A 的再次 quit 自己已排了新宽限）。
- 置信度：[确定]（静态机制闭合：覆盖写无 cancel、提前 return 无 cancel、removers 只有 handleReconnect/cleanup/shutdown 且都以 map 当前值为准——taskA 从步骤 3 起对一切清理不可见）；触发窗口为 1 秒内二次退服 [高]（非必现但完全可达；代理/掉线闪断重连场景下概率不可忽略）。

## 低危

### R5-L1 [quality·低·确定] handleReconnect 缺 isOnline 守卫（与 M1 步骤 4 同源面）
- 位置：`core/duel/DuelManager.java:882-938`
- 证据：入口至 GUI 段（见上引 882-891/918-919）全程无 `player.isOnline()` 检查；唯一上游 `DuelListener.java:39-41` 的延迟回调把 Player 引用捕获进闭包后未复检。
- 复现：M1 时间线步骤 4 即最小复现；另任何"join 后 1 秒内再退"的普通场景（无 M1 覆盖链）也会走到：此时 `disconnectTimers.remove` 摘到的是本次 quit 的 info → cancel 本次 task → `room.isActive()` true → 同样对离线玩家开 GUI。
- 影响：对离线玩家执行 openInventory/setScoreboard——Bukkit 行为未定义（多数版本静默无效，个别版本抛 IllegalStateException 进调度器日志）；至少是无效操作与日志噪音。
- 修法：handleReconnect 入口 `if (player == null || !player.isOnline()) return;`（放行注意：若未来想用"离线重连补通知"，应走 R-69 offlineNotices 队列而不是本路径）。
- 置信度：[确定]（无守卫是静态事实）；[中]（离线 openInventory 的具体行为未实测）。

## 已核验 clean 正面清单（供汇总与防重复报）
1. **任务配对矩阵**（除 M1 场景外全成对）：wordTimeout 自取消三路径（房间拆解/round 过期/正常到点）+ `selfCancelWordTimeout` 双参 remove 语义（注释已自证含"条目已被 cleanup/替换时仍须 cancel 自己"）——直接否证了 grace 链旧伤的同类模式在本链的存在；requestExpiry token 语义（R-68：stale firing 不摘新任务、成功才摘）；acceptDuel 消费后 cancel expiry；cleanup 单漏斗（activeDuels/busyPlayers/disconnectTimers/graceTasks t1t2/wordTimeoutTasks/duelBars.removeAll）；shutdown 幂等（cancelAllTasks×3 + scoreboard 去重遍历 + bars removeAll + 全 map clear）。
2. **DuelRoom 状态机**：`lastCorrectAnswerer` 哨兵 `new UUID(0,0)` 封印超时轮（迟到答案 ALREADY_ANSWERED，注释自证"random UUID can never equal a real player id"）；round token `wordRoundId` 单调递增防 stale timeout（`timeoutCurrentWord` 三重校验）；超时对称不计分语义（掉线不可能送对手分）成立。
3. **回归点 D9/D17/D18 全 PASS**：P2-08 离解散回退遍历用 `new HashSet<>(activeDuels.values())` 快照防并发；F1-18 两分支（room==null / NOT_IN_DUEL）均有 `duel.not-in-duel` 反馈；僵尸宽限任务 self-cancel 已在到期分支落地（但 M1 暴露了"覆盖写"漏网——修复覆盖面不足，非未修）。
4. **F1-15/R-27/R-68 语义链**：accept 先 peek 后校验、全过才 `remove(key, value)` 消费；失败分支（offline/busy/answering/AI 对话）全不消费邀请；发送前对称检查（sender/target 双向 + R-66 AI 对话双查）。
5. **DuelChallenge 选择器**：PDC 存目标 UUID（界面打开后名单变化不会"点 A 挑 B"，无 per-player 状态无需 quit 清理）；标题完整等值（zh/en 双配置）+ 尺寸 54 双重确认（明确注释防与 DuelGUI「单词对决」子串互伤）；drag guard 与 click 守卫同源。
6. **DuelGUI/DuelGUIListener**：`truncateTitleSafe` 不劈颜色码 + listener 同口径截断比对（短标题恒一致）；上半区 raw-slot 双守卫（click 与 drag 各自独立）；slot22 退出键显式补音效（R-86 全局兜底删除后的显式面）；buildInventory 分数格/头像/单词书三区渲染与 WIN_SCORE 循环对齐。
7. **线程纪律**：ChatAnswerListener:128-135 对决分支 LOWEST 优先、`event.setCancelled(true)` + `runTask` 主线程再 `submitAnswer`（线程正确）；DuelManager 全部 Bukkit 面调用点均在命令/GUI/调度器主线程路径内。
8. **模块门**：`sendDuelRequest/acceptDuel` 门 + quit/gui 放行语义（duel.enabled || isInDuel）与 plan 一致；`getRandomWord` null 全路径 `cancelDuelInternal` 兜底（含首词抽不到 → cleanup 释放 bossbar）。
9. **BossBar（3A-3）**：单房一条共享、创建仅 acceptDuel（主线程）、reload 点读开关（开/关双向惰性增减）、renderDuelBossBar 的 tier/progress 钳位、cleanup 单点 removeAll。

## 豁免挑战
无（对决不计答题数/零持久化/展示格静音/重启丢计数均按 §E 豁免核验后放行，无异议）。

## 范围外发现
- `ChatAnswerListener.java:128-135` 对决分支先 `cancel + recipients.clear` 再主线程 submit——若 `submitAnswer` 内判定 NOT_IN_DUEL（窗口态），玩家消息已被吞且只收到 not-in-duel 提示，属 F1-18 设计口径（不重复立案）——仅记录"消息不可达公开聊天"为设计接受面。
- `DuelListener.java:34-43` join 侧用 `EventPriority.MONITOR` + 句柄闭包捕获 Player（M1/L1 的诱因之一）；建议 future 改为 UUID 捕获 + 回调内 `Bukkit.getPlayer(id)` 复取。

## 审查覆盖声明
- 全文精读：DuelManager.java（全部 1376 行，含任务族/清理/shutdown/scoreboard/bossbar/GUI 刷新/查询面）、DuelRoom.java（全部）、DuelGUI.java（全部）、DuelGUIListener.java（全部）、DuelChallengeGUI.java（全部）、DuelChallengeListener.java（全部）、DuelListener.java（全部）、ChallengeCompleteGUI.java（头 80 行）。
- 交叉核证：ChatAnswerListener（对决分支）、LearnCommand（duel 子命令入口/门语义）、MiscGUIListener（CelebrationGUI 返回键）、PlayerJoinListener（consumeOfflineDuelNotice）、zh/en.yml（duel.* 键对称抽查）、schema（对决零持久化复核）。
- 未做（转 R5-V）：ChallengeCompleteGUI 点击/拖拽面逐格；DuelGUI 极端数据（>16 字符名截断组合）逐格；真机复现 M1（静态证据已闭合，真机复现为可选加强）。
