# 开发期发现汇总（未修复）

> 记录规则：开发 agent 在任务范围外发现的 bug / 疑点，一律不得自行修复，汇总到这里，由编排者统一归档排期。
> 状态取值：待排期 / 已排入 Phase 1 / 已排入 Phase 2 / 需用户决策 / 已关闭。
> 本文件累积所有批次，最新批次在上。

---

## 批次：Phase 0 基建冻结（4 流）

### 来自流 C（数据库基线）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-01 | `PlayerDataStore.java:83,107,130,349,671,688,746,802`；`DailyChallengeManager.java:107,135,162,203,229,250,269,417`；`AchievementManager.java:63,146,265`；`AdminCommandHandler.java:56,83`；`ProgressManagerImpl.java:42`；`LLEPapiExpansion.java:121`；`LetsLearnEnglish.java:384,389` | 外部类裸用 `getConnection()` 自执行 SQL，绕过本次新增的 `connectionLock`，与加锁方法可并行操作同一条连接 | M16 残余缺口：锁只加在 `DatabaseManager` 内部，连接本身仍被外部类直取 | 高 | 待排期（Phase 1） |
| F0-02 | `PlayerDataStore.java:349-393`（`savePlayerData`） | 在共享连接上 `setAutoCommit(false)` / `commit()`，与他线程并发时 A 的事务可能把 B 的语句卷入 → 跨线程事务污染 | 同上，事务边界未与锁对齐 | 高 | 待排期（Phase 1，PlayerDataStore 为 owner） |
| F0-03 | `AdminCommandHandler.java:80-82`（`resolvePlayerUUID`） | 按 `player_name=?` 查库恒空，离线玩家按名 `/lea reset <name>` 失效 | `player_data.player_name` 全代码库零写入点（同 H10） | 中 | 待排期（Phase 1，随 player_name 写入方案一并修） |
| F0-04 | `DatabaseManager.java:257-264`（`getConnection` 快路径） | 无锁读 `connection` 并 `isClosed()`，与 `shutdown()`/重连存在极窄 TOCTOU 竞态窗口 | 快路径为性能省略加锁 | 低 | 待排期 |
| F0-05 | `DatabaseManager.java:46`（`initialize()`） | 直调会覆盖 `connection` 字段而不关闭旧连接，无幂等保护（本次仅就地加固为锁内，未加"先关旧连接"） | 原实现假设 initialize 只跑一次 | 低 | 待排期 |

### 来自流 B（配置 / i18n 基线）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-06 | `ProxyMessenger.java:168-169` | `messages.getString("dialogue.proxy-achievement")` 返回 null 时紧接 `template.replace(...)` 会 NPE，无兜底 | 直接读原始 config，未走安全封装 | 中 | 待排期（Phase 1；可用新 helper `getPlayerMessageSafe` 修） |
| F0-07 | `MainMenu.java:36` | `translateAlternateColorCodes('&', messages.getString("gui.main-menu-title"))` 无 null 守卫，key 缺失即 NPE | 同上，未走安全封装 | 中 | 待排期（Phase 1） |
| F0-08 | `UpdateChecker.checkAsync()` 于 `LetsLearnEnglish.java:194-195` | `general.check-updates` 是死配置且注释误导：称"关闭后不连 GitHub API 适合内网"，但代码无条件联网，内网/隐私场景无法通过配置关闭 | 开关从未接线 | 中 | 待排期（Phase 2） |
| F0-09 | `config.yml` `gui.main-menu-title` vs `messages/*.yml` 同名键 | 重复定义：管理员改 config.yml 的标题不生效；且 `MainMenuListener` 拿 i18n 标题做点击判定，两者漂移会导致主菜单点击检测失效 | 双真相源 | 中 | 待排期（Phase 2） |
| F0-10 | `config.yml` `progress-system.achievements.config-file: "achievements-config.yml"` vs `ConfigManager.loadAllConfigs()` 硬编码 `loadConfig("achievements.yml")` | 文件名不一致且该 key 零读取 | 死配置 + 命名漂移 | 低 | 待排期 |
| F0-11 | 模块级 `enabled` 开关族（`word-system`/`ai-chat`/`progress-system`/`daily-challenges`/`leaderboard`/`achievements`/`rewards`） | 设为 `false` 是静默 no-op，管理员以为关了实际没关 | 开关从未 gating | 中 | 待排期（Phase 2） |

> 流 B 另产出 `tasks/dead-config-audit.md`（21 条必查死配置 + 约 17 条额外死配置），此处不重复。

### 来自流 A（测试安全网）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-12 | `WordLearningManager.java:338` | `if (consecutiveCorrect <= 0) return 1.0;` 是冗余守卫（删掉结果完全一致），纯死分支 | 可读性冗余 | 低 | 待排期（清理积压） |

### 来自流 D（词库流水线）

| # | 位置 | 现象 | 疑似根因 | 严重 | 状态 |
|---|------|------|----------|------|------|
| F0-13 | `resources/words/sample-beginner.yml` | 样本文件位于 resources 下会被 Maven 打进 JAR（运行时不会被加载，安全） | 无排除规则 | 低 | 已关闭（编排者在 pom.xml 加 `words/sample-*.yml` 排除） |
| F0-14 | 运维提示 | 若管理员把 sample 手动拷进服务器 `words/`，会被当作名为 `sample-beginner` 的难度加载 | 无 | 低 | 已记录（`resources/words/README.md`） |

---

## 交叉引用

- H6 悬案结论：`word-system.modes.*` 整棵子树死配置（答案 NO），见 `tasks/dead-config-audit.md` 附录。
- M16 现状与升级建议：见 `tasks/db-baseline.md`。
- 完整原始 bug 清单（H1~H11 / M1~M20）：见 `tasks/plan-roadmap-usable-to-delightful.md`。
