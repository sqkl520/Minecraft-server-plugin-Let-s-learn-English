# 代码质量报告 p3-wave2（Phase 3 Wave 2 五流合流 + i18n fan-in）

### 判定：PASS（无阻断违规；含 4 项 WARN）

审查范围（只读）：SocialManager / FeedTextFormatter / SocialListener / EnglishSquareGUI /
PlayerJoinListener / DatabaseManager（social 8 方法 + deletePlayerData）/ AchievementManager
（checkHelpAchievement + recordAchievement）/ LearnCommand / AdminCommand / AdminCommandHandler /
DataExporter / ReportGenerator / WordModes / AnswerChecker / WordLearningManager（快照）/
WordLearningGUI / WordLearningListener / ChatAnswerListener / MiscGUIListener / MainMenu /
MainMenuListener / DuelChallengeGUI / DuelChallengeListener / GUIUtils / DailyChallengeManager /
DailyChallengeGUI / config.yml / messages/zh.yml + en.yml / plugin.yml。

> 说明：本仓库的配置入口为 `ConfigManager.getMainConfig()`（点读式），i18n 入口为
> `MessageUtil` + `LanguageManager` 不存在（无此类）。任务模板里的
> `ConfigManager.getActiveConfig()` / `LanguageManager.getFor()` 系通用占位命名，本仓库
> 对应实现分别是 `getMainConfig()`、`MessageUtil.getPlayerMessage()` —— 以此口径审查。

---

## 1. i18n 覆盖与占位符 —— PASS（含 2 项低危 WARN）

抽查范围远超 5 键（覆盖 Wave 2 全部新键）。键级对称：zh/en 两侧逐一核对一致。

- `square.feed.*`（mode-label/empty/session/session-detail/session-merged/achievement/
  achievement-merged/checkin/hidden-detail/cheer-count/cheer-hint/own-hint/cheered/
  cheer-success/cheer-already/cheer-own/cheer-gone/cheer-hidden/cheerers/cheerers-none/
  notify-online/notify-join）—— zh:502-524 / en:502-524，全对称。
- `report.*`（文件模板 + 聊天反馈，共 ~70 键）—— zh:679-743 / en:680-744，全对称。
- `duel.challenge-title/hint/empty/more` —— zh:329-332 / en:329-332 对称。
- `admin-help.report` —— zh:346 / en:346 对称。
- `word.mode-*`、`module.disabled`、`word.no-modes-enabled`、`word.mode-disabled`、
  `word.invalid-mode`、`word.invalid-difficulty` —— 双侧齐备。

占位符一一对应（调用点 vs 模板）：随机核 15+ 键对，全部匹配 ——
`cheerers{names}{count}`(SocialListener:656-658)、`notify-join{count}`(SocialManager:427)、
`notify-online{player}`(SocialManager:355)、`cheer-success{player}`(:350)、
`cheer-count{count}`(EnglishSquareGUI:396)、`session{player}{mode}`、
`session-detail{correct}{total}`、`session-merged{player}{count}`、
`achievement{player}{achievement}`、`checkin{player}{streak}`（FeedTextFormatter:64-99）、
`challenge-more{count}`(DuelChallengeGUI:164)、`chat-overview{from}{to}{days}{correct}{accuracy}`
(ReportGenerator:282-288)、`batch-complete{file}{count}{truncated}`(:258-262)、
`period-invalid{input}`(AdminCommandHandler:143) —— 全部吻合。

| # | 位置 | 现象 | 建议 |
|---|---|---|---|
| W1 | ReportGenerator.java:882 / :890 对 zh.yml:714 / en.yml:714 与 zh.yml:717 | `t(settings,"report.advice-good","days",...)` 与 `t(...,"report.advice-weak","k",...)` 传了模板中**不存在**的 `{days}` / `{k}` 占位符（两语言模板都无该占位符） | 无害（多余实参被静默忽略，玩家端不出现 `{days}`），属冗余；建议二选一：模板补 `{days}`/`{k}` 或调用点去掉多余实参，保持"占位符一一对应"纪律 |

---

## 2. 线程安全 —— PASS

- **SocialManager 异步边界**：
  - `feedCache` 为 `volatile`（:64），写侧经 `synchronized(cacheLock)` 整体替换
    （:238-240、:375-390），读侧无锁 —— 与 Leaderboard.refreshCache 同范式，无撕裂读。
  - 缓存刷新去抖 `nextRefreshAt`（AtomicLong CAS，:244-256）；每日清理去重
    `lastCleanupDay`（AtomicReference CAS，:215-226）—— 无 TOCTOU。
  - `cheerDebounceAt` 为 ConcurrentHashMap（:69），quit 经 `unloadPlayer` 清理
    （:464-466 ← PlayerJoinListener:118）—— F2-10/F1-24"只增不删"教训已闭环。
  - 异步回调一律经 `runMain`（:480-486）回主线程；`runAsync/runMain` 均吞
    `IllegalStateException`（停用窗口静默放弃）。
  - cheer 校验链在主线程完成（含缓存查找/自赞/隐身复查/防抖），写库异步，结果回主线程
    —— 主线程零写库，契约成立。
- **cheerFeed 事务与 connectionLock**（DatabaseManager:1581-1632）：存在性复核、INSERT、
  计数 +1 全程在 `synchronized(connectionLock)` 内；`setAutoCommit(false)`…`finally
  setAutoCommit(true)`，ALREADY 分支先 `rollback()` 再返回 —— 连接状态不外泄，无 TOCTOU。
  （与既有 savePlayerData 同范式。）
- **ReportGenerator 异步查询持锁模式**：所有 DB 查询各自 `synchronized(getConnectionLock())`
  单次持锁（:326、:362、:387、:410、:430、:464、:489、:539、:577），不跨查询持锁；
  配置快照在主线程 `captureSettings()` 取（:1015-1031），异步线程不再读配置；
  主线程反馈 `sendOnMainThread` 带 `plugin.isEnabled()` 守卫（:1120-1129）。
- **checkHelpAchievement 异步派发契约**（AchievementManager:444-459）：主线程入口 →
  `runTaskAsynchronously` → 异步内 `getCheerStats`（同步 DB 读）→ `tryUnlock`（其通知段
  自带回主线程派发，:232-240）—— 与 SocialManager.getCheerStats 的"禁止主线程调用"契约
  一致；停用窗口吞 IllegalStateException。
- **SocialListener 快照 map**：`squareGuis/leaderboardGuis/renderedFeedPages` 均
  ConcurrentHashMap，主线程读写。

---

## 3. 资源清理 —— PASS

- **SocialListener.onPlayerQuit**（:113-119）清 `squareGuis` + `leaderboardGuis` +
  `renderedFeedPages` 三张 per-viewer map —— Wave 2 新增的 `renderedFeedPages` 已纳入。
- **SocialManager.unloadPlayer** 由 PlayerJoinListener.onPlayerQuit:118 调用，清防抖 map。
- **ReportGenerator**：无 FileWriter（`Files.write(file.toPath(), out)`，:654，自动关闭；
  不泄句柄）；`reportsDir()` 按需 mkdirs；惰性清理闸 `cleanupDone` AtomicBoolean CAS 单次
  （:663）。
- **ReportSettings** 仅持 FileConfiguration 引用（语言文件快照），无句柄。
- **DataExporter**：`try(FileWriter writer = ...)`（:60）—— try-with-resources，无泄。
- **AdminCommandHandler.getKnownPlayerNames**：try-with-resources PreparedStatement/ResultSet。

---

## 4. 硬编码检查 —— PASS

- Wave 2 全部玩家可见文案均经 `MessageUtil.getPlayerMessage/getSenderMessage/getGuiMsg`
  或 `MessageUtil.getGuiMsg` + i18n 模板；grep 未发现 `sendMessage("<中英文字面串>")`。
- 唯一的非 i18n 字符串均为**非文案**：`"&b"+playerName`（玩家名，DuelChallengeGUI:183 /
  EnglishSquareGUI:279/312/376）、GUI 填充空格 `" "`、ReportGenerator.BORDER=`"="` 符号
  （:68）、报告缩进空格、`activeDaySuffix` 的 `"、"/", "` 分隔符（:960，语言相关分隔符，
  非消息）。
- 带 `[?key]` fail-visible 回落由 getGuiMsg/t 保证（ReportGenerator:993-1003）。

---

## 5. 配置读取 —— PASS（含 1 项 WARN）

- 全部经 `ConfigManager.getMainConfig()`（点读，reload 即时生效）；**无** `plugin.getConfig()`
  裸用（全仓仅 ConfigManager.java:104 一处合法）。GameConfig getter/setter 模式在本仓库
  不适用（本仓库为点读式，非 POJO 缓存）。
- social.* 默认值齐全且与 config.yml 一致：`social.enabled`(ConfigManager:370 默认 true)、
  `social.cheer.enabled`(默认 true)、`social.feed.retention-days`(默认 7)、
  `social.cheer.click-debounce-ms`(默认 750)、`social.notify-on-join`(默认 true)。
- `english-square.default-mode` 默认 "feed"（EnglishSquareGUI:88-90），路径正确。
- report.* 默认值齐全：enabled/period/language/minutes-per-question/keep-days/
  respect-privacy/batch.max-players/weak-words-limit（ReportGenerator:1015-1031），
  含防非法钳位（minutesPerQuestion<=0→0.5、weakLimit/batchMax≥1）。

| # | 位置 | 现象 | 建议 |
|---|---|---|---|
| W2 | config.yml gui-layout.english-square（:410-434） | 新增 FEED 按钮的 `gui-layout.english-square.mode-feed` 条目**未写入 config.yml**（同级 mode-weekly/monthly/alltime/all 皆有）。渲染侧 `getGuiSlot("english-square","mode-feed",6)`/`getGuiMaterial(...,Material.BELL)` 走**代码默认值**，功能正常（slot 6 / BELL），但管理员**无法**通过 config 调整该按钮槽位/材质，与同屏其它模式按钮不一致 | 补 `mode-feed: {slot: 6, material: "BELL"}` 到 gui-layout.english-square |

---

## 6. 权限（AdminCommand 逐子命令）—— PASS（含 1 项 WARN）

`hasPermissionFor`（:209-215）：`report` → `letslearnenglish.report || letslearnenglish.admin`；
其余一律 `letslearnenglish.admin`。逐子命令判定：

| 子命令 | 判定 | 结果 |
|---|---|---|
| reload | admin | ✓ |
| export | admin | ✓ |
| reset | admin | ✓ |
| info | admin | ✓ |
| help | admin | ✓ |
| ignore-update | admin | ✓ |
| broadcast | admin | ✓ |
| exportwordbank | admin | ✓ |
| importwordbank | admin | ✓ |
| duel | admin | ✓ |
| report | report‖admin | ✓ |
| （无参）/ 未知子命令 | admin（subCommand="" / default 分支） | ✓ |

无遗漏子命令；`report` 最小权限化落地（plugin.yml 无命令级 permission，避免 Bukkit 前置
拦截破坏仅持 report 权限者，plugin.yml:22-25 注释已记录）。tab 补全 args==1 按
`isAdmin/isReport` 正确过滤（:291-302）。

| # | 位置 | 现象 | 建议 |
|---|---|---|---|
| W3 | AdminCommand.java:303-370 | `onTabComplete` 仅在 `args.length==1` 做权限过滤；`args≥2` 的各补全分支（reset/exportwordbank/importwordbank 在线名、report 的库内名与 period 候选）**未复核权限** | 低危（仅信息性候选泄露、无越权动作），但与 args==1 的过滤纪律不一致；建议在 args≥2 前统一 `if(!sender.hasPermission(admin) && !report) return empty` 早退 |

---

## 7. 回归风险抽查 —— PASS（含 1 项 WARN）

- **WordModes 出厂等价**：`firstEnabledMode`（WordModes.java:81-95）显式**优先 choice**，
  miss 才遍历 MODE_ORDER（spelling→choice→fill_blank）；默认全开时恒返回 `choice`
  —— 与接线前 `DEFAULT_WORD_MODE=choice` 逐位一致（javadoc 已固化该理由）。
- **DailyChallengeManager N=3 等价**：`checkAndAssign`（:178-204）读
  `challenges-per-day`(默认 3)、钳位 [1,4]、从 available shuffle 取 N；
  默认配置/AI 可用下取 3，与旧"随机 3"生效分布等价。GUI `CHALLENGE_SLOTS[11,13,15,17]`
  渲染前 N=3 → 11/13/15，与接线前逐槽一致；点击侧改 `Arrays.binarySearch`（升序数组，
  MiscGUIListener:203）与渲染共用同一槽位真相。
- **MainMenu 既有 7 按钮无改动**：slot 11/13/15/17/10/14/18 与旧值一致（:71-103），
  新增 duel(12)/checkin(16)，不移动旧按钮。
- **校验项无关回归**：EnglishSquareGUI FEED 模式在 socialManager==null 或门关闭时
  防御性回落 ALL_PLAYERS（:110-117），不会 NPE。

| # | 位置 | 现象 | 建议 |
|---|---|---|---|
| W4 | LearnCommand.java:178-180、MiscGUIListener.java:144-147 | 两处调用点注释声称默认模式解析为"**spelling→choice→fill_blank 固定序第一个 enabled 者**"，与 `WordModes.firstEnabledMode` 实际实现（**choice 优先**）相反。行为正确，但注释与实现漂移，易误导后续维护 | 更正两处注释为"优先 choice（FEED-MODE-01 默认新手模式），miss 后按 MODE_ORDER 遍历"，与 WordModes:81-95 javadoc 对齐 |

---

## 问题清单（按严重度）

**阻断（Blocker/High）：无。**

**中（Medium）：无。**

**低（WARN / 建议）**
1. W1 [i18n] ReportGenerator.java:882/890 —— `report.advice-good`/`advice-weak` 传了模板不存在的
   占位符 `{days}`/`{k}`（无害冗余）。
2. W2 [config] config.yml gui-layout.english-square —— 缺 `mode-feed` 条目，新 FEED 按钮
   不可配置（默认 6/BELL 正常工作）。
3. W3 [权限] AdminCommand.java:303-370 —— tab 补全 args≥2 分支未复核权限（低危泄露）。
4. W4 [文档] LearnCommand.java:178-180 / MiscGUIListener.java:144-147 —— 注释称默认模式
   按"spelling→choice→fill_blank 固定序"，与 choice 优先的实现相反。

**结论**：四个强制维度（配置读取 / i18n 覆盖 / 线程安全 / 资源清理）零阻断违规，判定 PASS。
上述 4 项为文档/一致性层面的低危改进项，不阻断合流。
