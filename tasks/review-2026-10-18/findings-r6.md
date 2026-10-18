# findings-r6 每日挑战+签到+日历（编排者接管产出）
- 审查范围：`core/DailyChallengeManager.java`（922 行）、`gui/DailyChallengeGUI.java`、`core/manager/CheckInManager.java`、`gui/LearningCalendarGUI.java`（交叉日：R10-H1/R11-M4/R12/R13 已完成的相关结论由编排者复核后并入）
- 审查时间：2026-10-18
- 产出方式：R6 审查 agent 因 API 502 击杀 + 续跑后长期停滞（transcript 43 分钟无更新）被停止；**其死亡前零落盘**。本域多数关键面已有 R10/R11/R12/R13 交叉覆盖，编排者逐条复核源码后产出本文件（关键机制均读源码原文证实/证伪）。
- 结论摘要：高 0 / 中 2 / 低 1 / 待验证 3（不含并入的 R10-H1/R11-M4——它们已各自立案）

## 中危

### R6-M1 [data·中·确定] 同日重启后单词挑战奖励可重复发放（R10-H1 的 R6 视角复核——**证实，认定成立**）
- 复核结论：**R10-H1 成立，判高危**（从 R6 视角独立走链证实；此处不再重复立案，以 R10-H1 为主账）
- 复核证据（编排者读源码原文）：
  1. `daily_challenge_words` 建表（DatabaseManager.java:221-230）列 = id/player_uuid/challenge_date/word_id/completed/completed_at——**无 claimed/reward 列**（证实：已付台账无 DB 持久化）
  2. `onWordCorrect`（DailyChallengeManager.java:664-670）：每次答对首查 `hasPendingWordReward` → 命中即 `onAllWordChallengesComplete` 再发放
  3. `hasPendingWordReward`（:704-711）：唯一拦截 = 内存 Set `wordChallengeClaimedToday.contains` + DB `isDailyChallengeWordsCompleted`——重启后内存 Set 空 + DB completed=true → **放行** → 重复发放
  4. **关键 javadoc 反驳**（:897-901）："重启跨午夜时该定时任务本就不触发，故也不会在重启后误补（无双发风险）"——该论证**只覆盖"跨午夜重启"**（午夜 rollover 任务不触发 → 不补发）；但 **"同日白天重启"**路径是：重启 → 玩家继续答题 → `onWordCorrect` 直接重入 `hasPendingWordReward`（不依赖午夜任务）→ 重复发钱。R10-H1 的缺口正处于该 javadoc 的论证盲区——**R10-H1 证实**。
- 补充面（R6 视角新发现）：该 bug 在**每次同日重启后"答对任意一个词"即可再刷一次**（不需要重新完成挑战——`hasPendingWordReward` 的 pending 判定只看 DB completed=true + 内存空）。100 金币/次（默认值），且 `RewardEngine.todayCoinsCache` 同为内存态 → daily-cap 也不拦。
- 修法：同 R10-H1（持久化已付标记）。

### R6-M2 [logic·中·确定] 签到 DB 写失败伪装成"你今天已经签到过了"（R11-M4 的 R6 视角复核——**证实，认定成立**）
- 复核结论：**R11-M4 成立**（从 R6 视角独立走链证实；以 R11-M4 为主账）
- 复核证据（编排者读源码原文）：
  1. `CheckInManager.checkIn`（:98-100）：`if (!plugin.getDatabaseManager().updateCheckIn(...)) { return new CheckInResult(false, ...); }`
  2. `DatabaseManager.updateCheckIn`（:1550-1574）：返回 false 的唯一路径 = `catch (SQLException e) { log WARNING; return false; }`——即 **DB 瞬时故障**
  3. `LearnCommand.java:746-749`（R11 引用）：`if (result.success()) {...} else { ... "checkin.already" ... }`——"已经签到过"文案与真实失败共用
  4. **数据不一致**：注释（CheckInManager:97）"Claim in the database first. This makes a crash unable to produce a second claim."——该注释论证**防重复**方向正确（先落库防双领），但**未考虑"落库失败"被当成"已领"** 的方向：玩家看到"已经签到过"→不再重试→当日 streak 断、奖励丢，且玩家永远不会报告（文案告诉他一切正常）。
- 修法：同 R11-M4（CheckInResult 增加 storageFailed 语义）。

## 低危

### R6-L1 [quality·低·确定] `countUnclaimedForDate` 的锁外取连接引用（R3-H3 同族在 R6 域的实例）
- 位置：`core/DailyChallengeManager.java:871-874`
- 证据：
  ```java
  Connection conn = plugin.getDatabaseManager().getConnection();   // ← 锁外
  synchronized (plugin.getDatabaseManager().getConnectionLock()) {
  try (PreparedStatement stmt = conn.prepareStatement(sql)) {      // ← 锁内用
  ```
  与 R3-H3 完全同型（锁外取引用 → 重连窗口替换 → 旧引用关闭 → 静默丢查询）；此处失败后果更轻（只是作废计数少算，用 0 兜底）。
- 修法：并入 R3-H3 的机械修批（全仓 `Connection conn = databaseManager.getConnection();` 锁内收敛）。

## 正面核验（已读源码证实，供汇总参考）

1. **stripe lock 分片正确**（DailyChallengeManager.java:112,131-132,580）：32 槽 `(hashCode & 0x7fffffff) % 32`，同玩家恒同槽（同一 UUID hashCode 恒定）、跨玩家 hash 碰撞才同槽（仅串行化损失，无正确性损失）；构造期全初始化无 null。R12-L1 已报"MiscGUIListener 与 DailyChallengeManager 两套独立 Object[32] 语义等价"——正确。
2. **午夜 rollover 两段式（D3 回归）成立**：异步段 countUnclaimedForDate+checkAndAssign；主线程段 hasPendingWordRewardForDate（补发）+ 发消息 + clearAllWordChallengeClaims + 自续 finally（LetsLearnEnglish.java:543-613 已由 R10 核过 PASS）。
3. **rollover 时序约束自洽**：`hasPendingWordRewardForDate` 的 javadoc（:899）明示"必须在 clearAllWordChallengeClaims 之前调用"，实际调用序（LetsLearnEnglish:571-579）补发在前、clear 在后——一致。
4. **挑战领取幂等（R12-L1/R12-MIC 已核）**：deposit-then-mark + GUI 侧 stripe lock 外层 + manager 内层条件 UPDATE 双保险；主线程事件串行下无双领。
5. **LGC 日历数据源**：`getSessionDays`（PlayerDataStore:262-273）走 player_sessions（真源，D1 一致）；空月/月份切换由 openAsync 两段式承载（C-7 回归已核 PASS）。
6. **挑战奖励键回退链（ConfigManager CHALLENGE_REWARD_KEYS）**：MC-5 已核（存在性+正值双审计）。
7. **money 门（D15/PF-MC-01 回归）**：`onAllWordChallengesComplete` 的 economy-disabled 终态标记分支（:736-748）与 Vault 瞬时失败"保留 pending 可重试"分支（:750+）区分正确——MC-2 收口后走 `RewardEngine.isMoneyEnabled()` 单源。

## 待验证区
1. [待验证] 挑战进度计数跨午夜（进行中场次）：`onWordCorrect` 每答对词即调、`challenge_date` 取 `LocalDate.now()`——午夜前开局的会话跨过午夜后继续答对，后半段计入新一天（属自然语义但需产品确认；与 R1-M1 会话覆盖开新局的挑战口径分裂相关）。
2. [待验证] `DailyChallengeGUI` 降级路径（degradeStaleChallenges UPDATE）与 `ensureWordChallengesAssigned` INSERT 在 openAsync 异步段的幂等性——R12 已静态核过"stripe lock + INSERT OR IGNORE/ON DUPLICATE 幂等"，实机压测未做。
3. [待验证] `LearningCalendarGUI` 空态/月份切换的边界（R12-V1/V2 同族 openAsync 竞态）；R4-M2 从调用侧立案的"他人主页打开自己日历"已单列（归 R4）。

## 范围外发现
- `CheckInManager` STREAK_REWARDS 数组循环（`((newStreak-1)%7)+1`）在 newStreak 极大时（长年连签）无溢出面（int 且取模先于索引）——正面确认。
- R4-M2 的 `LearningCalendarGUI.openAsync(player)` 无 targetId 参数是 GUI 侧结构性缺口（R4 从调用点立案；GUI 实现面如需扩展 targetId 归 R4 修复批）。

## 审查覆盖声明
- 编排者读源码原文核过：DailyChallengeManager（:655-925 关键链全读）、DatabaseManager（daily_challenge_words 建表、updateCheckIn、deletePlayerData 全函数）、CheckInManager（:80-130）、PlayerDataStore.getSessionDays、LetsLearnEnglish 午夜段（已在 R10 覆盖中核过）。
- 未做：DailyChallengeGUI 全文逐行（R12 已精读全文并立案 3 条）；LearningCalendarGUI 全文（R12 已精读）；实机验证（无 testharness 权限）。
