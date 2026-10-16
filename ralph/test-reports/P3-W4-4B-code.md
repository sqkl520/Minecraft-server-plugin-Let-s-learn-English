# 代码质量报告 P3-W4-4B（MC-7 死键/死类大扫除）

### 判定：PASS（GO）

测试类型：静态只读审查（防误删 + 删后一致性）。build tester 已跑 mvn，本报告不重复。

---

## 维度 1 · 误删检查（活键保护名单）— 全部存活

| 保护键 | Java 引用方 | yml 键 | 结论 |
|--------|-------------|--------|------|
| `leaderboard.header/entry/footer` | LearnCommand.java:897/901/907 | zh/en.yml:145-147 | 活 ✓ |
| `word-learning-gui.answer-broadcast-correct` | ChatAnswerListener.java:90/93 | zh/en.yml:463 | 活 ✓ |
| `dialogue.daily-limit-reached` | AIChatManager.java:845 + ConfigManager.java:81(审计) | zh/en.yml:93 | 活 ✓ |
| `statistics-server.*`(port/bind-address/ip-whitelist/auth.*) | StatisticsHttpServer.java:53-57 | config.yml:579-594 | 活 ✓ |
| `progress-system.rewards.types.money.*` | RewardEngine.java 12 处(67-533) | config.yml:281-301 | 活 ✓ |
| messages 版 `gui.main-menu-title` | MainMenu.java:36(getGuiMsg) + MainMenuListener.java:95(getMessageConfig 版) | zh/en.yml:378 | 活 ✓ |
| `gui.menu-size` | MainMenu.java:43 + ConfigManager.java:39(审计) | config.yml:309 | 活 ✓ |
| `gui-layout` 剩余段 | getGuiSlot/getGuiMaterial 多 GUI | config.yml:353-455 | 活 ✓ |
| `achievement-wall.unlocked/locked-material` | AchievementWallGUI.java:100/101 | config.yml:453-455 | 活 ✓ |
| `word.session-start`（对照：session-end 已删） | LearnCommand:266/383 + MiscGUIListener:172 | zh/en.yml:34 | 活 ✓ |

关键区分核验：
- 删除版 `gui.main-menu-title` = config.yml 段（已删，零 config 读取）；存活版 = messages 段（MainMenuListener:95 读 `getMessageConfig`，非 mainConfig）。两者同键不同文件，判定正确。
- config.yml:192 `debug: false` 属 `ai-chat:` 段（行 166-226），非已删的 `general.debug`；`general:` 段（行 19-42）现仅 language/check-updates/auto-save-interval/backup，`general.debug` 确已移除。正确。
- zh.yml:479/482 `correct:`/`incorrect:` 属 `word-learning-gui:` 段（活键），非已删的 `word.correct`/`word.incorrect`。正确。

## 维度 2 · 删除彻底性 — 零残留

14 项 i18n 死键（`claim-button`/`title-weekly|monthly|alltime`/`mastery-update`/`streak-info`/`data-saved`/`reset-pending`/`scenario-save-failed`/`dialogue-gui`/`progress-gui`/`word.session-end`/旧 `answer-broadcast`/`word.correct|incorrect`）：src/main 全路径 grep = **0 命中**（唯二命中为删除说明注释 ChatAnswerListener.java:185、GUIUtils.java:71）。

- `gui/ProgressGUI.java`：文件已不存在（gui/ 目录 19 文件中无此文件）；`ProgressGUI` 全路径 grep = 0 引用 ✓
- `getGuiBorderMaterial`：全路径 grep = 0 引用，方法体已删 ✓
- config 死键（`general.debug`/`pool-size`/`difficulty-settings.*`/`rewards.types.{item,command,exp}.enabled`/`achievements.config-file`/config 版 `main-menu-title`/`gui-layout.profile.*`/`scenario-select.back-button`/`cross-server-*`/`custom-textures`）：src/main 零残留，命中项均为删除说明注释（config.yml:73/469-470/451、SocialListener.java:83）✓

## 维度 3 · 删后一致性 — 无动态拼接漏网

- **消息键动态拼接**：`getGuiMsg/getPlayerMessage/getSenderMessage/getMessage(... + ...)` grep = **0 命中**；MessageUtil 无 `contains`/`getKeys`/`index` 式模糊解析（仅 prefix 回落）。无 `"gui-layout.profile." + x` 形态。
- **getGuiSlot/getGuiMaterial 参数展开核**：动态调用点（EnglishSquareGUI:420/438、LeaderboardGUI:337）的 configKey 实参为 `mode-weekly/monthly/alltime/all/feed`、`tab-coins/words/combo/season` —— 全部落在保留段内。**无** `getGuiSlot("profile",...)` / `("scenario-select",...)` 调用点 → 删段确实无读者。
- **ConfigManager 审计名单**：REQUIRED_CONFIG_KEYS(32-52) 与 REQUIRED_MESSAGE_KEYS(75-87) 均**不含**任何已删键；11 个消息审计键在 zh/en 双双存在（no-permission/reload-success/proxy-achievement/rollback-voided/cancelled-by-gm/opponent-timeout/prefix/session-start/session-ended/daily-limit-reached/main-menu-title）✓
- **zen/en 对称**：两文件嵌套键各 689 条，`^[a-z]` 顶层段名集合逐一对应 ✓

## 维度 4 · 删除安全性 — 无外部引用风险

- messages 侧：MessageUtil 无 `contains` 动态键解析、无 `getKeys()` 遍历消息文件 → 不存在"删键后 index 误判/前缀误命中"形态。
- config 侧：全仓唯一 `plugin.getConfig()` 裸用点为 `ConfigManager.loadAllConfigs():106`（记忆已登记）；`plugin.getConfig().getX(...)` 直接读取 = 0 命中。管理员外部 config 中残留旧键仅被忽略，无报错风险。

## 不确定项 / 范围外发现（只上报，不修复）

1. **GUIUtils 遗留标题启发式**（GUIUtils.java:70-73）：`title.contains("周榜/月榜/总榜")` 与 `"Weekly/Monthly/All-Time Ranking"` 对应本批删除的 `leaderboard.title-weekly|monthly|alltime`。因是硬编码子串而非读 yml 键，删键**不影响**运行；属既有注释已自认的"保留待统一清理"死启发式，非本批引入。**建议排期**（不阻断）。
2. 同类遗留：GUIUtils.java:63-67 旧 DialogueGUI/情景对话、:110-111 旧错题本标题等启发式分支，同为历史遗留，本批未处理。

## 结论

四维度零违规 → **GO**。删除清单逐键零残留、活键保护名单逐键存活、无动态拼接漏网、审计名单干净、zh/en 对称。
