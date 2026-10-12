# 代码质量报告 p2（P2 清理批次，甲+乙 8 流合流）

### 判定：PASS

审查范围 = 本批次改动质量（S1~S8 + 编排者 fan-in），非全仓历史代码。四个审查维度（配置读取路径 / i18n 覆盖 / 线程安全 / 资源清理）零违规。

---

## 逐项结论

### 1. i18n 覆盖 —— PASS
- `zh.yml` 与 `en.yml` 双侧 `config-version: 11`（zh.yml:6 / en.yml:6）；代码 `ConfigManager.MESSAGE_CONFIG_VERSION = 11`（ConfigManager.java:25），三者一致。
- 两文件键级完全对齐：顶层键各 36 个、二级键各 508 个，数量一致。
- 逐键核对（16 键，zh/en 双命中）：
  - `word.invalid-difficulty` zh:42 / en:42；`word.no-words-available` zh:43 / en:43
  - `word.difficulty-beginner|intermediate|advanced` zh:44-46 / en:44-46
  - `word.mode-spelling|choice|fill_blank` zh:47-49 / en:47-49
  - `dialogue.finish-quiz-first` zh:98 / en:98；`dialogue.chat-active` zh:99 / en:99；`dialogue.scenario-not-found` zh:100 / en:100
  - `scenario.load-updated` zh:247 / en:247；`scenario.load-save-failed` zh:248 / en:248；`scenario.limit-reached` zh:249 / en:249
  - `duel.sender-chatting` zh:271 / en:271；`duel.target-chatting` zh:272 / en:272
  - `admin.player-no-data` zh:170 / en:170；`admin.export-failed` zh:171 / en:171

### 2. 占位符匹配 —— PASS
逐一核对使用点与 yml 模板：
- `word.invalid-difficulty` ← `options`（LearnCommand.java:168-170，yml `{options}`）✓
- `dialogue.scenario-not-found` ← `key`（LearnCommand.java:266，yml `{key}`）✓
- `scenario.limit-reached` ← `max`（LearnCommand.java:752-754，yml `{max}`）✓
- `scenario.load-updated|load-success` ← `name`（LearnCommand.java:764-766，yml `{name}`）✓
- `duel.target-chatting` ← `player`（DuelManager.java:141，yml `{player}`）✓
- `word.no-words-available` / `dialogue.finish-quiz-first` / `dialogue.chat-active` / `scenario.load-save-failed` / `duel.sender-chatting` / `admin.player-no-data` / `admin.export-failed` 均为无占位符键，调用点未多传参数 ✓
- `scenario.load-updated` 在 LearnCommand 用 `result.updated()` 分流（LearnCommand.java:764-766）✓
- 附带核对：`duel.*` 其余使用点占位符（`{seconds}`/`{player}`/`{word}`/`{score1}`/`{score2}`/`{opponent}`）与 yml 一致 ✓

### 3. 线程安全 —— PASS
- **DuelManager 新结构**：`pendingRequests`/`offlineNotices`/`requestExpiryTasks`/`graceTasks`/`wordTimeoutTasks` 全为 `ConcurrentHashMap`（DuelManager.java:42-52），`requestTokenSeq` 为 `AtomicLong`（:54）。所有读写路径（sendDuelRequest/acceptDuel/handleDisconnect/cleanup/shutdown）均在主线程 Bukkit 事件/调度回调内执行；`pendingRequests.remove(key, value)` 令牌式原子删除（:193、:266）防旧任务误删新邀请。迭代中删除走 CHM 安全语义（:148-157）。`PendingDuel`/`OfflineDuelNotice` 为不可变 record（:63、:75）。
- **SoundManager.warnedMissingKeys**：`ConcurrentHashMap.newKeySet()`（SoundManager.java:43），`add()` 的原子返回值保证"首次告警一次"（:128）；`reloadSoundCache` 清空可重置（:53）。`soundCache` 亦为 CHM、`enabled` volatile（:40-41）。
- **AIChatManager.shuttingDown**：volatile（:87），守卫方法 `isShuttingDownOrDisabled()`（:219-221）覆盖全部在途回调链入口——用户消息写库 `runAsync`（:752）、`supplyAsync` 起止（:767、:776）、`thenAcceptAsync`（:824）、自定义 executor（:828）、`exceptionally`（:834）、`startConversation` 写库（:428）。`maxContextPairs` 等配置字段 volatile（:72-85），`conversationContexts` 为 CHM 且同步块内裁剪（:742、:786）。

### 4. 资源清理 —— PASS
- **quit 编排点**（PlayerJoinListener.java:80-102）：`endQuiz` / `cancelPractice` / `unloadPlayerData` / `unloadPlayer`(成就) / `unloadProfileSettings` / `clearPlayer`(返回栈) / `unloadPlayer`(签到锁) / `RewardEngine.unloadPlayer`(经济去重)，经核对各服务确有对应 map 清理实现（RewardEngine.java:199-200 等）。
- **WordLearningListener 自清**（:42-44）：`pronounceCooldown` quit 移除，因该监听器匿名注册无外部引用，自持有 quit 处理器合理。
- **offlineNotices 结论**：`shutdown()` 清理（DuelManager.java:842）；**quit 不应清** —— 其语义是"离线方下次进服收 GM 解散通知"（:736-754），由 `consumeOfflineDuelNotice` 读后即清（:751-754），PlayerJoinListener.java:67-74 消费。每 UUID 至多一条，为有界残留（已在 javadoc:733-734 明示）。
- **requestExpiryTasks 配对**：创建于 sendDuelRequest（:192-208）；全部消费路径均 cancel —— 同 sender 重发（:151-155）、同 target 被替换（:166-169）、自然过期自清（:195）、acceptDuel 消费（:272-275）、shutdown 全清（:815）。`cleanup(room)` 不碰该容器属正确（挂起邀请独立于活动房间）。
- **graceTasks/wordTimeoutTasks 配对**：`wordTimeoutTasks` 以 player1 为键（:409、:416），schedule/cancel/handleTimeout/cleanup 全对称（:401-420、:428、:792-793）；`graceTasks` 在 handleDisconnect/handleReconnect/cleanup/shutdown 各路径均 cancel（:609、:621-624、:786-789、:813）。

### 5. 硬编码检查 —— PASS
- 本批改动零新增玩家可见硬编码字符串。`sendMessage("<含字母/中文的裸串>")` 全仓零命中。
- 现存两处诊断性回退（`MessageUtil.java:61/92/140` "Missing message: "、`ChatScenarioListener.java:169` "Missing: "）为**历史代码**，不在本批范围内；且属开发者可见的缺键诊断，非面向玩家的本地化文案。
- 本批新增文本均走 i18n（LearnCommand/AdminCommand/DuelManager/SoundManager 日志为控制台英文，非玩家可见）。

### 6. 配置读取路径 —— PASS
- 全仓 `plugin.getConfig()` 仅一处，位于 `ConfigManager.loadAllConfigs()`（ConfigManager.java:82），符合约定；无业务代码直连 config.yml。
- 四个新接线键均带默认值且路径正确：
  - `ai-chat.max-context-pairs` 默认 6（AIChatManager.java:189，`Math.max(1, ...)` 下限保护）
  - `general.check-updates` 默认 true（LetsLearnEnglish.java:209）
  - `daily-reminder.on-join` 默认 true（LetsLearnEnglish.java:391；另 `daily-reminder.enabled` 默认 true，:388）
  - `tts.pronounce-cooldown-seconds` 默认 5（WordLearningListener.java:208，`Math.max(0, ...)` 支持 0=关冷却语义）
- 四个键在 config.yml 均实际存在（config.yml:152/33/436/481）。

### 7. 回归风险抽查 —— PASS
- **MainMenu 27 下限**：config.yml 默认 `menu-size: 27`（config.yml:258），恰在下限，无影响（MainMenu.java:43-48 校验 `[27,54]` 且 9 的倍数）。
- **Word.equals 收窄**（Word.java:106-120）：判等收窄为 `english`（忽略大小写）+ `partOfSpeech`，移除 reward。消费点核对：
  - `WordBank.getRandomWords`（WordBank.java:186-192）：`filter(w -> !w.equals(exclude))`，exclude 为 null 时 `equals(null)` 返回 false，池保留全部（行为不变）；exclude 非 null 时排除同英文同词性词 —— 对干扰项排除语义更稳（reward 漂移不再逃逸排除）。
  - `AnswerChecker.buildChoiceOptions`（AnswerChecker.java:120-135）：另有英文名 `equalsIgnoreCase` 二次去重兜底，双重保险。
  - 全仓无 `Set<Word>`/`Map<Word,*>` 容器（已 grep 核实），无去重集合行为回归。
- **DIFFICULTY_ORDER 对未知自定义词库**（WordManagerImpl.java:30-33、210-217）：未列出键回落优先级 100，按 `thenComparing` 字母序排在 beginner(0)/intermediate(1)/advanced(2) 之后 —— 内置"由易到难"动线保持，自定义词库稳定字母序排列，无 NPE、无顺序抖动。

---

## 问题清单（按严重度排序）

| # | 严重度 | 位置 | 现象 | 建议 |
|---|--------|------|------|------|
| 1 | WARN（文档一致性） | `src/main/resources/config.yml:257` | 注释仍写"菜单大小：9 / 18 / 27 / 36 / 45 / 54"，但代码已把最小合法值提为 27（MainMenu.java:44），9/18 会被回落 27 | 更新注释为"27 / 36 / 45 / 54（按钮布局至少需 27 格）"，避免管理员误配 9/18 后困惑为何不生效 |
| 2 | 观察（非缺陷） | `core/duel/DuelManager.java:222-306`（acceptDuel） | acceptDuel 未加 AI 对话互斥检查（R-66 仅在 sendDuelRequest 侧）；理论上 target 收邀后先开 AI 对话再 `/le duel accept` 可让对决与 AI 会话并存 | 实测无冲突：ChatScenarioListener 先于 ChatAnswerListener 执行，且其在 isInDuel 时提前 return（ChatScenarioListener.java:72-76）；如需与 sender 侧完全对称，可在 acceptDuel 补同一守卫。当前不影响正确性 |
