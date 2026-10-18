# findings-r7 赛季+跨服+班级+感官反馈+新手引导（审查 Agent 产出）
- 审查范围：`core/season/SeasonManager.java`、`core/ProxyMessenger.java`、`gui/ClassBoardGUI.java`、`listener/ClassBoardListener.java`、`core/SoundManager.java`、`core/feedback/CountdownTier.java`、`core/onboarding/OnboardingManager.java`、`listener/PlayerJoinListener.java`；邻域核验：`DatabaseManager`（season/class 段）、`AdminCommandHandler`（class 族）、`LearnCommand`（class/season 命令）、`GUIUtils`、`LetsLearnEnglish`（接线）、config.yml / zh.yml / en.yml 对应段
- 审查时间：2026-10-18
- 结论摘要：高2条 / 中2条 / 低5条 / 待验证3条 / 豁免挑战0条
- 备注：本 Agent 被上游网关 502 瞬时击杀后恢复，按编排者指令于恢复后立即交卷。该 agent 在审查中做了大量"先立案→自我复核→撤回误报"的过程（撤回 8 条：relay 冠军名/参与奖文案/引导直通/线程面×3/孤儿行降级/no-op 槽位），以下为**经复核的最终定稿**；撤回过程本身体现"先证实再立案"纪律，予以记录。

## 高危

### R7-H1 [bug·高·确定] GlobalMessage 无长度上限：writeUTF 上限被击穿时 sendPluginMessage 异常/静默截断 → `/lea broadcast` 崩溃或跨服静默丢帧
- 位置：`core/ProxyMessenger.java:71-73`（sendGlobalMessage）→ `:123-147`（sendForward）→ `:140`（payloadOut.writeUTF(data)）；调用点 `command/AdminCommand.java:175`
- 证据：
  ```java
  // ProxyMessenger.java:139-146
  ByteArrayDataOutput payloadOut = ByteStreams.newDataOutput();
  payloadOut.writeUTF(data);        // ← data 超 65535 modified-UTF 字节抛 UTFDataFormatException
  byte[] payloadBytes = payloadOut.toByteArray();
  out.writeShort(payloadBytes.length);  // ← >65535 时 writeShort 静默截断（长度字段撒谎）——接收端 readFully 按错误长度切帧
  messenger.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
  ```
  `sendForward` 无任何长度校验、无 try-catch；`sendGlobalMessage`、`sendAchievementBroadcast`、`sendSeasonSettled` 全走同一路径。接收侧 `parseForwardFrame` `:218` 用 `readShort() & 0xFFFF` 表示 ≤65535；发送侧 `writeShort(payloadBytes.length)`（Java short，>32767 溢出为负）→ 接收端 `& 0xFFFF` 把它解回原值（对 32768..65535 侥幸自洽），但 >65535 静默截断长度 → 载荷错位/解析失败整条丢弃（接收侧静默 fine 日志）。
- 复现：`/lea broadcast <凡超过约 65500 字节的文本>`（如从文档粘贴超长公告）→ 发送侧要么 `writeUTF` 抛 `UTFDataFormatException` 向上冒泡到 AdminCommand 执行路径（Bukkit 捕获命令异常并打印，命令静默无反馈）；要么长度截断导致跨服接收端静默丢帧。
- 影响：管理员广播长公告时本地异常/跨服静默失败；无用户可见反馈。（R9 转来待复核项——本域从发送侧证实了缺口存在。）
- 修法：`sendForward` 首行 `if (data.getBytes(StandardCharsets.UTF_8).length > 60000) { data = 截断; }`（或以字符数 20000 粗钳 + WARNING），照 `sanitizeBroadcastName` 的截断先例（`:318`）对齐。

### R7-H2 [data·高·高] 赛季"首次启用"行建立后 currentSeasonId 回退（管理员改 anchor-date / duration）→ `from = minId` 锚定错位，可能对"从未结算过的当前季"重复补结算并发奖
- 位置：`core/season/SeasonManager.java:366-415`；`data/DatabaseManager.java:2246-2260`（minSeasonId）
- 证据：
  ```java
  // SeasonManager.java:366-385
  Integer minId = db.minSeasonId();
  if (minId == null) { ... 建立当前季行 ... return; }
  int targetId = currentId - 1;
  if (targetId < 1) return;
  Long cursorObj = db.maxSettledSeasonId();
  int from = (cursorObj != null) ? (int) (long) cursorObj + 1 : minId;   // ← 无已结算季时起点 = minId
  if (from > targetId) return;
  ```
  `minId` 语义靠 javadoc "行只会由本方法建立，等价于'启用时刻的季'"支撑——但 `anchor-date` 是**点读式配置**（`:152-167` anchorDate() 每次现读，注释明示'/lea reload 即时生效'，config.yml:670-672 仅提示"慎改"未加锁定）。管理员改小 anchor-date（如从 2026-11-02 改为 2026-10-01）→ `currentSeasonId` 突然变大（如从 1 变 2）→ 扫描发现 `targetId = currentId-1 = 1`，`maxSettledSeasonId` 仍 null（从没结算过）→ `from = minId = 1`（此前首次启用建立的行）→ **对 season 1 执行 tryClaimSettlement**——而 season 1 正是当前玩家正在积累分数的"当前季"窗口之一（改 anchor 后 season 1 窗口变更或与当前真实窗口重叠），在窗口中点提前结算并立即发奖。
- 复现：季节开启（seasons 表只有当前季一行，settled_at NULL）→ 管理员 `/lea reload` 前把 `season.anchor-date` 从 T 改为 T-30d → 下一次启动或午夜跨季检测 `settleIfNeededAsync` → 对"仍在进行中"的季结算 → 依据 config.yml:670 注释"修改本键 = 重定义全局赛季轴，慎改"这是**已知的运营风险**，但代码层没有防护（无"settled 行只允许对已过去窗口"的校验，`tryClaimSettlement` 只查 settled_at IS NULL，不查 window.end() < today）。
- 影响：不当时序配置改动将进行中赛季提前切割结算+按当时榜单发奖（钱已发出不可撤销；与后续 join 补发叠加时靠台账幂等不重发，但奖值依据被截断的窗口，玩家可见"赛季提前结束"广播）。
- 修法：`runSettleSweep` 内对每个待结算 s 校验 `windowOf(s).end().isBefore(today)`（未真正走完的季跳过），不满足则 `continue` 且不写 settled 行；或锚点改动时打 WARNING（对比 seasons 表内已存窗口 start_date/end_date 与现算窗口不一致即告警）。

## 中危

### R7-M1 [data·中·高] 参与奖结算规模无上限（participated>0 时台账行=全服窗口内玩家数；大服一次结算可产生数万行 + per-player 并发查询）
- 位置：`core/season/SeasonManager.java:467-475`；`data/DatabaseManager.java:2105-2138, 2285-2308`
- 证据：
  ```java
  long participated = rewardCents("participated", 0.0);
  if (participated > 0) {
      for (UUID participant : plugin.getDatabaseManager()
              .getSessionParticipants(window.start(), window.end())) {   // ← 全服 DISTINCT uuid，无 LIMIT
  ```
  配 `insertSeasonRewards` 单事务 `executeBatch`（good）但行数无界；发放段 `deliverPending` 每玩家 join 时逐行发。默认 0.0 关闭，仅开启后生效。
- 复现：`participated: 100.0` + 万级活跃服 → 季末结算产生万行台账；每玩家进服一次查全量 pending 行（`getPendingSeasonRewards` 无 LIMIT）→ 大 N 时 join 异步查询即可；但 SETTLE 服首轮发放 `for (Player online : Bukkit.getOnlinePlayers()) deliverPending(...)` 每人在线玩家一异步任务（N 人 → N 个并发异步 DB 查询在单连接上排队）——主线程不卡（合规），DB 排队时长 = N × 查询时间。
- 影响：大服性能面可见延迟（结算瞬时 N 并发查询），奖励发放正确性不受影响。
- 修法：文档化 config 注释提示规模；或将 SETTLE 首轮发放改单次批量查询（一次查全部在线 UUID 的 pending 行）替代 per-player 查询。

### R7-M2 [ux·中·高] 班级名含 `&`/`§` 未过滤 → GUI 标题与列表文案染色注入（管理输入面）
- 位置：`command/AdminCommandHandler.java:637-643`（创建校验）、`gui/ClassBoardGUI.java:116-118`
- 证据：
  ```java
  // AdminCommandHandler.java:640
  if (className.isBlank() || className.length() > maxLen || className.contains(" ")) {
      sender.sendMessage(... "class.name-invalid" ...);
  ```
  无 `&`/`§`/`\n` 过滤；`ClassBoardGUI.open` 对 className 直接进 translateAlternateColorCodes 的字符串。
- 复现：`/lea class create "&cRed"` → 建班成功（无空格、长度合法）→ `/le class` 打开 GUI → 标题被后段染色；**判定端不受影响**（前缀固定段在 {class} 之前）。若 className 含 `\n`（命令方块/RCON 输入可携带）→ Inventory 标题含换行（Paper 表现未实测）。
- 影响：管理员误输入污染展示；无数据后果。
- 修法：create 校验加 `className.contains("&") || className.contains("§")` → 拒绝（或建班时剥离颜色码存储）。

## 低危

### R7-L1 [quality·低·确定] ClassBoardGUI.ENTRY_SLOTS 手写 45 槽数组可用循环生成（卫生）
- 位置：`gui/ClassBoardGUI.java:53-59`；修法：`for (int s = 9; s <= 53; s++) list.add(s);`。（slot 22 与 EMPTY_SLOT 互斥成立，无 bug。）

### R7-L2 [config·低·确定] season.duration-days 中途修改无提示（config 注释缺口，与 H2 同根）
- 位置：`core/season/SeasonManager.java:170-173`、`resources/config.yml:667-673`
- 证据：duration 点读 → 窗口中途改 duration 使 currentSeasonId 瞬间跳变（如 30→20，id 1→2）→ 午夜检测 `todaySeason != yesterdaySeason` 不成立（2==2）→ **不触发结算**，但窗口突变压缩；seasons 表旧行 start/end 与新推导不符（ensureSeasonRow 是 INSERT OR IGNORE 不更新）。config.yml:667-669 注释只说"停服/漂移不累计误差"。
- 修法：config 注释补"修改 duration-days/anchor-date 会使已建立的赛季行与现推导窗口不一致，可能提前/跳过结算；多子服必须同步修改并同时 reload"。

### R7-L3 [thread·低·确定] join 链 claimPendingRewards 与 notifyPendingCheers 各起一个异步 DB 查询，单连接排队（低危 perf 提示）
- 位置：`listener/PlayerJoinListener.java:86-91`；修法：可合并为单异步任务次序查询（可选）。

### R7-L4 [data·低·高] class_members 删班与并发 add 的孤儿成员行（多服窄窗口，有自愈）
- 位置：`data/DatabaseManager.java:2462-2472`（deleteClass 先删成员再删头行，注释"无外键，排序防孤儿"）、`core/manager/AdminCommandHandler.java:720-736`
- 复现：删班瞬间并发 add（多服）的 applyClassAdd 已在锁内判过班存在 → add 成功 → 孤儿成员行。`getPlayerClassId` 返孤儿 id → 换班路径自愈（currentClassId != null → removeClassMember(旧) + add）。单服窗口≈0。
- 修法：deleteClass 后顺带清孤儿（启动时一次性 `DELETE FROM class_members WHERE class_id NOT IN (SELECT class_id FROM classes)`），或 add 前重查班存在。

### R7-L5 [logic·低·高] `/lea class list`（全服前 50 快照反查）与 `/le class` GUI（班级过滤查询）同数据两口径，>50 分段不一致
- 位置：`core/manager/AdminCommandHandler.java:812-817`（快照反查）vs `command/LearnCommand.java:818-820`（`getRankedPlayersForPeriodFiltered` 班级过滤）
- 复现：大服 >50 有分玩家、班级成员 A 赛季第 60 名（班内第 1）→ `/le class` GUI 显示真实分数；`/lea class list` 显示 0（快照 50 名外）。同数据两个管理/玩家面矛盾。
- 修法：命令面改与 GUI 同源（班级过滤查询），或文档化"list 分数=全服前 50 内"。

## 待验证区

- T1（R13 转来 join 3 连同步 DB 读语义）：`PlayerJoinListener.onPlayerJoin` 首三行（loadPlayerData/loadPlayerAchievements/loadProfileSettings）+ cachePlayerName + handleJoin 内 getOnboardingStep（1 SELECT）+ checkAndAssign——全在主线程同步查 DB。cache-first 面：首进必查、老玩家命中缓存则跳过；错过命中则每次 join 5+ 条同步 SELECT 串行。与"主线程不查库"纪律的既有裁决关系需与 R3/R13 口径对齐。验证法：压测 100 假人同时 join 观测主线程 stall（Timings）。注：既有架构，非新 bug。
- T2（赛季 origin 回环 + 双服实测）：`proxy.server-name: ""` 默认空串（config.yml:485）→ 回环去重退化"可能重复播报一次"（注释与 code 一致，`ProxyMessenger:249` `!self.isEmpty() &&` 门）。单服无影响；双服未配 server-name 时结算服可能收到自己的回环再 applyRelayedSettlement → 重复 broadcastEnded（发放幂等受 claimed 保护）。**代码行为与注释一致，是否重复取决于代理实际回环行为（未实测）**。验证法：双服 Velocity 实测。
- T3（season 结算扫描与 auto-save 同连接排队）：结算全流程持 connectionLock 多次（ensureSeasonRow → getRankedPlayersForPeriod → tryClaimSettlement → insertSeasonRewards → getSessionParticipants），与 auto-save 异步批次交错——单连接排队时长；结算一次性、量级小。仅提示，不立案。

## 豁免挑战
（无。E14 赛季奖励不受 daily-cap——本域核实代码与裁决一致：payReward 直发 + recordCoinsEarned 不注入 todayCoinsCache（SeasonManager:602-611）。E18 停机 catch-up——settle 的"首次启用不追既往"与 R-75 join 兜底语义一致。E16 教程强制 choice——TUTORIAL_MODE="choice" 硬定（OnboardingManager:61）+ startTutorialQuiz 内 word-system 门，结构成立。）

## 范围外发现
- `LearnCommand.handleClassCommand`（:773-778）用 `LeaderboardGUI.isSeasonAvailable(season)` 做赛季门——gating 两跳，正确但绕（归 R8 参考）。
- `ClassBoardListener` drag guard 用 `event.getRawSlots()` 判断（正确保守）；尺寸先判标题后判（双重确认，正确）——PASS 记录。

## 回归核验（本域）
- D6（R-01 清洗：sanitizeBroadcastName 对 playerName/zhName/enName 三字段均清洗 ProxyMessenger:284-289）**PASS**
- D7（parseForwardFrame 先消费帧长 readFully，五场景测试文件 ProxyFrameParseTest.java 存在）**PASS**
- D24（J-3 赛季休眠 WARNING：settleIfNeededAsync:328-334 一次性告警 + J-4 成就墙属 R4）**PASS**
- D27（PF-ONB-01/03：interceptQuizStart + tutorial 分支在 WordLearningManager（R1 域）——接线面 PASS；PF-ONB-03 session-start 本地化走 WordModes.localizeDifficulty（OnboardingManager:193-196）PASS）
- D23（CountdownTier 双域：学习侧 WordLearningManager:444-448 与对决侧 DuelManager:469,678-683 同读 feedback.countdown.urgent-seconds 默认 5，同调 CountdownTier.tierOf）**PASS**
- R10-M5 邻近面：SeasonManager 全部配置点读式（isEnabled/isAnnounceEnabled/isRewardsEnabled/anchorDate/durationDays/rewardCents 均每次现读），**无构造快照面**——reload 生效性优于 TtsManager 模式，PASS；SoundManager 有 reloadSoundCache 且 HotReloadManager:44 接线，PASS。

## 审查覆盖声明
- 全文精读：`SeasonManager.java`（750 行全文）、`ProxyMessenger.java`（350 行全文）、`SoundManager.java`（283 行全文）、`CountdownTier.java`、`OnboardingManager.java`（516 行全文）、`ClassBoardGUI.java`、`ClassBoardListener.java`、`PlayerJoinListener.java`。
- 定向读（调用链核验）：`DatabaseManager.java` season 段（2055-2325）+ class 段（2403-2686）+ deletePlayerData（2330-2401）；`AdminCommandHandler.java` class 族（600-849）；`LearnCommand.java` class/season 命令（750-899）；`GUIUtils.isPluginGUI`；`LetsLearnEnglish.java`（191-396, 560-640 接线段）；`LeaderboardGUI` season 引用；config.yml（season/class/onboarding/proxy/countdown 段）；zh.yml/en.yml（season/class/onboarding 键）。
- 未完成/未做（诚实申报，被 502 击杀前正在核）：(a) `getRankedPlayersForPeriodFiltered` 的实现细节（SQL 层 IN 过滤 vs 内存过滤）未打开源码核读——影响 L5 严重度定级；(b) 双服 Forward 回环的代理实测（T2）；(c) startTutorialQuiz（WordLearningManager 内）的 tutorial 分支明细属 R1 域，仅接线面验证；(d) `getRankedPlayersForPeriod` 与 `getSessionParticipants` 的时间口径（session_date 字符串比较）未与 R3 交叉；(e) OnboardingManager 的迷你会话"原地重试跳过计数"完整语义依赖 WordLearningManager tutorial 分支，未打开。
