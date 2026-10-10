# 死配置审计清单 (Dead Config Audit)

> Phase 0 基建流 B 产出。本文仅做**只读审计**，不改动任何玩法行为。
> 所有结论均以 `grep` 全路径查证 `src/main/java` 下的实际读取点为准。
> 版本：1.0.7-SNAPSHOT ｜ 审计日期：2026-10-10

## 术语与判定方法

- **死配置**：`config.yml` 中声明了该 key，但**代码里没有任何读取点**（或读取点被硬编码值绕过），
  即改动该值对运行时行为**零影响**。
- **查证方式**：对每个 key 的**全路径**在 `src/main/java` 做 `grep`（含 `getInt/getString/getBoolean/getDouble/getConfigurationSection/isSet`）。
  “无匹配” = 死；给出“活”判定时附带文件:行号证据。
- 读取点行号基于审计时的源码快照，后续若有重构可能漂移，但“死/活”结论以是否存在读取点为准。

---

## 1. 必查覆盖项（6 大类）

| 配置 key | 注释/PRD 声称的用途 | 代码实际引用点（grep 查证） | 结论 |
|---------|-------------------|--------------------------|------|
| `word-system.daily-limit` | 每日学习上限（个单词），0 不限制 | 全路径 grep `daily-limit` → Java 侧**无匹配**。无任何限额判定逻辑；i18n 键 `word.daily-limit-reached` 也无触发点 | **死** |
| `word-system.modes.*`（整棵子树） | 拼写/选择/填空三种模式的开关与参数 | grep `modes` 仅命中注释与 `WordManagerImpl.java:24` 的硬编码 `VALID_MODES = Set.of("spelling","choice","fill_blank")`；无任何 `word-system.modes` 读取点。详见 **附录 H6** | **死** |
| `daily-challenges.challenges-per-day` | 每天分配给玩家的任务挑战数量 | grep `challenges-per-day` → **无匹配**。数量在 `DailyChallengeManager.java:126` 硬编码 `subList(0, 3)`（行 123 注释“pick 3 from 4 types”） | **死** |
| `daily-challenges.rewards.answer_10` | 各挑战奖励金币数 | grep `answer_10` 仅命中 `DailyChallengeManager.java:30` 枚举定义 `ANSWER_10("answer_10","daily.p1",10,30.0)`，奖励值 **30.0 硬编码在枚举**，不读 config | **死** |
| `daily-challenges.rewards.session_3` | 同上 | `DailyChallengeManager.java:31` 枚举 `SESSION_3(...,50.0)` 硬编码 | **死** |
| `daily-challenges.rewards.combo_5` | 同上 | `DailyChallengeManager.java:32` 枚举 `COMBO_5(...,25.0)` 硬编码 | **死** |
| `daily-challenges.rewards.ai_chat_5` | 同上 | `DailyChallengeManager.java:33` 枚举 `AI_CHAT_5(...,40.0)` 硬编码 | **死** |
| `difficulty-settings.available` | 可用难度等级列表 | grep `difficulty-settings` → **无匹配**。难度列表来自自动发现的词库文件：`WordManagerImpl.java:58` `listFiles(*.yml)` → `getAvailableDifficulties()`(行 145-146) 返回 `wordBanks.keySet()` | **死** |
| `difficulty-settings.display-names.*` | 各难度显示名 | grep `display-names` → **无匹配**。GUI 难度名实际走 i18n 键 `word-bank.difficulty-beginner/-intermediate/-advanced`（见 messages/*.yml） | **死** |
| `proxy.cross-server-announcements` | 成就解锁时广播到全服 | grep `cross-server-announcements` → **无匹配**。`ProxyMessenger` 在 `LetsLearnEnglish.java:163-164` 无条件注册；`AchievementManager.java:177-178` 只要通道已注册就广播，从不读此开关 | **死** |
| `proxy.cross-server-messages` | 接收其他子服公告 | grep `cross-server-messages` → **无匹配**。`ProxyMessenger.onPluginMessageReceived` 无条件处理收到的消息，从不读此开关 | **死** |

## 2. 额外发现的死配置（任务外，仅审计不修复）

| 配置 key | 注释声称的用途 | grep 查证 | 结论 |
|---------|--------------|----------|------|
| `general.debug` | 开启后输出详细日志 | grep `general\.debug` → **无匹配**（只有 `ai-chat.debug` 被 `AIChatManager.java:160` 读取）。全局 debug 开关不接线 | **死** |
| `general.check-updates` | 关闭后不连 GitHub API | grep `check-updates` → **无匹配**。`UpdateChecker.checkAsync()` 在 `LetsLearnEnglish.java:194-195` **无条件执行**；注释“关闭后不会连接 GitHub”与实际不符 | **死（且误导）** |
| `daily-reminder.on-join` | 玩家进服时发送学习提醒 | grep `on-join` → **无匹配**。`PlayerJoinListener.onPlayerJoin` 只做数据加载/更新提醒/挑战分配，**无进服学习提醒逻辑** | **死** |
| `progress-system.leaderboard.update-interval` | 排行榜刷新间隔（秒），默认 30 | grep `update-interval` → **无匹配**。刷新周期硬编码 `6000L` ticks（5 分钟）于 `LetsLearnEnglish.java:297-299` | **死** |
| `gui.custom-textures` | 是否使用自定义材质 | grep `custom-textures` → **无匹配** | **死** |
| `gui.main-menu-title`（**config.yml 的 gui 段**） | 主菜单标题 | 实际标题读的是 **messages/*.yml 同名 key**：`MainMenu.java:36` 与 `MainMenuListener.java:86-87` 都走 `getMessageConfig(lang)`。config.yml 这条从不被读（活的是 i18n 版） | **死** |
| `progress-system.rewards.types.item.enabled` | 物品奖励开关 | grep → **无匹配**。`rewards.types` 下只有 `money.*` 被 `RewardEngine`/`CheckInManager` 读取 | **死** |
| `progress-system.rewards.types.command.enabled` | 指令奖励开关 | 同上，无读取点 | **死** |
| `progress-system.rewards.types.exp.enabled` | 经验奖励开关 | 同上，无读取点（经验奖励链在历史批次已被删除） | **死** |
| `progress-system.achievements.config-file` | 成就配置文件名 | grep → **无匹配**。`ConfigManager.loadAllConfigs()` 硬编码 `loadConfig("achievements.yml")`；config 声称 `achievements-config.yml`，键不读且**文件名不一致** | **死（且不一致）** |

### 2.1 模块级 `enabled` 开关族（功能性死配置）

以下顶层/模块级开关在配置中存在，但**没有任何 gating 读取点**——即把它们设为 `false` 也**不会**禁用对应功能：

| 开关 key | grep 结论 |
|---------|----------|
| `word-system.enabled` | 无匹配（唯一活的 word-system 开关是 `word-system.review.enabled`，`LetsLearnEnglish.java:282`） |
| `ai-chat.enabled` | 无匹配 |
| `progress-system.enabled` | 无匹配 |
| `daily-challenges.enabled` | 无匹配 |
| `progress-system.leaderboard.enabled` | 无匹配 |
| `progress-system.achievements.enabled` | 无匹配 |
| `progress-system.rewards.enabled` | 无匹配（活的是更深一层的 `progress-system.rewards.types.money.enabled`） |

> 对照组（**活**的模块开关，证明 grep 方法有效）：`tts.enabled`（`TtsManager.java:45`）、`word-system.review.enabled`（`LetsLearnEnglish.java:282`）、`progress-system.rewards.types.money.enabled`（`RewardEngine.java:29` / `CheckInManager.java:125`）。

---

## 附录 H6：`word-system.modes` 全路径查证（替主计划核实遗留疑点）

**问题**：全路径 `word-system.modes`（及子项 `max-attempts` / `case-sensitive` / `option-count` / `show-first-letter`）到底有没有被代码读取？

**结论：没有（NO）。整棵 `word-system.modes` 子树是死配置。**

### 查证证据

1. grep `word-system`（全部读取点）：命中 `review.enabled`、`review.mastery-threshold`、`review.intervals`、`review.mastered-review-interval-days`、`answer-timeout`、`words-per-session`——**唯独没有任何 `word-system.modes` 开头的读取**。
2. grep `max-attempts|case-sensitive|option-count|show-first-letter`：Java 侧 **0 条匹配**。
3. grep `time-limit`：命中的全是 `achievements.<id>.time-limit`（`AchievementManager.java:190/193/194`、`AchievementWallGUI.java:106/108/109`），**与 `word-system.modes.choice.time-limit` 无关**。
4. 模式集合是**硬编码常量**，非配置读取：`WordManagerImpl.java:24`
   ```java
   private static final Set<String> VALID_MODES = Set.of("spelling", "choice", "fill_blank");
   ```
   模式来源是命令参数/GUI 固定传入（如 `LearnCommand.java:136` 默认 `"choice"`、`MiscGUIListener.java:93` 固定 `"choice"`），不读 config 的 enabled 开关。

### 逐 key 判定

| 全路径 key | 是否被读取 | 证据 |
|-----------|-----------|------|
| `word-system.modes.spelling.enabled` | **否** | grep `word-system.modes` 无任何读取点 |
| `word-system.modes.spelling.max-attempts` | **否** | grep `max-attempts` 0 匹配 |
| `word-system.modes.spelling.case-sensitive` | **否** | grep `case-sensitive` 0 匹配（大小写处理在别处硬编码，未读此 key） |
| `word-system.modes.choice.enabled` | **否** | 同 1 |
| `word-system.modes.choice.option-count` | **否** | grep `option-count` 0 匹配（选项数在选择题生成处另行硬编码） |
| `word-system.modes.choice.time-limit` | **否** | grep `time-limit` 仅命中 achievements 段；答题时限实际走 `word-system.answer-timeout`（`WordLearningManager.java:40/56/288`） |
| `word-system.modes.fill-blank.enabled` | **否** | 同 1 |
| `word-system.modes.fill-blank.show-first-letter` | **否** | grep `show-first-letter` 0 匹配 |
| `word-system.modes.fill-blank.max-attempts` | **否** | grep `max-attempts` 0 匹配 |

### 附带发现（命名不一致）

即便将来有人想接线此子树也会踩坑：config 用连字符 **`fill-blank`**，而代码里的模式 id 是下划线 **`fill_blank`**（`VALID_MODES`、`LearnCommand.java:501`）。两者不一致，直接按 config 段名读取将取不到。

---

## 汇总

- **必查 6 大类**：全部确认为死配置。按叶子 key 计 **18 条**
  （`daily-limit` 1 + `modes.*` 9 + `challenges-per-day` 1 + `rewards.*` 4 + `difficulty-settings`（`available` 1 + `display-names` 3）4 — 其中 `modes.*` 的 9 条见 H6；`proxy.*` 2 条）。
  精确计：`daily-limit`(1) + `modes.*`(9) + `challenges-per-day`(1) + `rewards.*`(4) + `difficulty-settings.*`(4) + `proxy.*`(2) = **21 条**。
- **额外发现**：另有约 **17 条** 死配置
  （`general.debug`、`general.check-updates`、`daily-reminder.on-join`、`leaderboard.update-interval`、`gui.custom-textures`、`gui.main-menu-title`[config 段]、`rewards.types.item/command/exp.enabled` 3、`achievements.config-file`、模块级 enabled 开关族 7）。
- **H6 结论**：`word-system.modes` 整棵子树 = **死（NO）**，模式集合硬编码于 `WordManagerImpl.VALID_MODES`。

> 本清单仅为审计证据，**不建议在 Phase 0 删除这些 key**——删配置属于玩法/文档行为变更，应留待后续专门的清理 Story，并同步更新 README 与 config 注释。

