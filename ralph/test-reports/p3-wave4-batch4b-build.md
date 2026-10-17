# 编译测试报告 P3-W4-Batch4B (MC-7 死键/死类大扫除)

### 判定：PASS (GO)

构建维度验收通过。`mvn clean verify` BUILD SUCCESS，39/39 测试绿，JaCoCo 147 类 + 覆盖率门通过，零新增编译警告。活键全部存活，死键/死类零残留，zh/en 对称，三 YAML 真解析无重复键，CRLF 风格保持。

---

## 逐项结论

### 1. 构建 — PASS
- `export JAVA_HOME="D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64" && mvn clean verify` → **BUILD SUCCESS**（43.3s）
- 测试：Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
- JaCoCo report/check：`Analyzed bundle ... with 147 classes` + `All coverage checks have been met.`
- JAR 生成：`target/Let's Learn English!-1.0.7-SNAPSHOT.jar`
- 注：本批期间编排者在 07:53 提交了 2f586e1（ConfigManager getGuiBorderMaterial + README difficulty-settings 收口），首次 verify(07:52) 与终态 verify(08:02) **两次均 BUILD SUCCESS**，结论不受影响。

### 2. 编译警告 — PASS
- 无 javac 编译警告。
- 日志中仅有的 WARNING 全部来自 JaCoCo/ByteBuddy 运行时动态 agent（`byte-buddy-agent`、`Dynamic loading of agents`、`Sharing is only supported for boot loader classes`），非源码警告，可忽略。

### 3. zh/en 对称 — PASS
| 方法 | zh | en | only-zh | only-en |
|------|----|----|---------|---------|
| node 3-level（scripts/audit-i18n-keys.js 口径，预期 692=692） | 692 | 692 | [] | [] |
| SnakeYAML 全递归（YmlCheck） | 691 | 691 | [] | [] |

- 692 vs 691 差值 = node 只解析到 3 级、把 `dialogue.feedback` / `square.feed` / `onboarding.book` 三个**含子键的父 map** 误计为叶子；全递归口径 691 为真叶子数。两口径均**对称零差异**。
- SnakeYAML 真解析（allowDuplicateKeys=false）：zh.yml / en.yml / config.yml 三文件均 `no duplicate keys`，无解析错误。
- `node scripts/audit-i18n-keys.js`：仅 1 条 `achievement-wall.category-`（[zh-miss][en-miss]）—— **已知误报**（动态拼接键，符合预期）。

### 4. 活键存活验证（防误删）— PASS
| 活键 | 引用方 | zh/en 键在 | 编译 |
|------|--------|-----------|------|
| `leaderboard.header` / `entry` / `footer` | LearnCommand.java:897/901/907 | 144-147 ✓ 两份 | ✓ |
| `word-learning-gui.answer-broadcast-correct` | ChatAnswerListener.java:90/93 | 463 ✓ 两份 | ✓ |
| `dialogue.daily-limit-reached` | AIChatManager.java:845 + ConfigManager:81(REQUIRED) | 93 ✓ 两份 | ✓ |
| `statistics-server.*` | StatisticsHttpServer.java:53-57, LetsLearnEnglish:230 | config.yml:579 ✓ | ✓ |
| `money.daily-cap` | RewardEngine.java:133/235 | config.yml:234 ✓ | ✓ |
| `gui.main-menu-title`（messages 版） | MainMenu.java:36, MainMenuListener:95, ConfigManager:86 | 378 ✓ 两份 | ✓ |

- REQUIRED_MESSAGE_KEYS 11 键逐一核验：全部在 zh/en 双份存在（`prefix` 为顶层键，行 8）。

### 5. 死键确实消失 — PASS
i18n 删除（zh/en 各 16 叶子 + 2 整段，对称）：`word-learning-gui.answer-broadcast`、`daily-challenge.claim-button`(+lore)、`leaderboard.title-weekly|monthly|alltime`、`word.session-end|correct|incorrect|mastery-update|daily-limit-reached`、`checkin.streak-info`、`general.data-saved`、`admin.reset-pending`、`error.scenario-save-failed`、`dialogue-gui.*` 整段、`progress-gui.*` 整段 —— src/ 全仓零残留。

config 删除（零 Java 引用）：`general.debug`、`database.mysql.pool-size`、`difficulty-settings.*` 整段、`rewards.types.{item,command,exp}.enabled`、`achievements.config-file`、config 版 `gui.main-menu-title`、`gui-layout.profile.*` 整段、`scenario-select.back-button`、`cross-server-announcements|messages`、`custom-textures` —— 全仓零残留（仅存在于"已移除"提示注释中）。

- **误报排除**：config.yml:192 `debug:` 属 `ai-chat.debug`（活键，AIChatManager:279 读取），非删除的 `general.debug`；config.yml:383/405/439 `back-button:` 属 word-learning / english-square / leaderboard 三段活配置，非删除的 scenario-select.back-button / gui-layout.profile.back-button。`rewards.types.money.*` 为活键（RewardEngine 全量引用），未被删。
- 动态拼接键排查：`gui-layout.<gui>.<key>` 的全部调用点（MainMenu/EnglishSquareGUI/LeaderboardGUI/WordLearningGUI/SocialListener 等）均无 `profile` guiName；`leaderboard.title` 仍存在且被 LeaderboardGUI:147 消费。

### 6. ProgressGUI 删除完整性 — PASS
- `src/` 全仓零 import / 零引用（grep "ProgressGUI" = 空）
- `target/` 无 `*ProgressGUI*`；JAR 内 `unzip -l | grep -i progressgui` = 无
- `progress-gui` 键：src/main/resources 零残留；JAR 内 messages/zh.yml 计数 0
- HEAD 89 → worktree 88 main java 文件（差值 1，正好 ProgressGUI）

### 7. config.yml 真解析 + 行尾 — PASS
- SnakeYAML 解析成功，无重复键。
- 行尾风格保持：config.yml = CRLF（与 HEAD 一致）；zh.yml/en.yml = LF（与 HEAD 一致）；无 .gitattributes，core.autocrlf=false，改动未引入行尾翻转。

### 8. 类数变化 — PASS
- JaCoCo bundle 147 classes（预期 148→147 ✓）；JAR `*.class` 计数 156（含内部类 `$`），无 ProgressGUI。

---

## 其他核查
- `util/MessageUtil.java`：两处 javadoc 示例 `word.correct` → `word.session-start`（行 47 / 198）已替换，零残留。
- `listener/SocialListener.java`：:81-83 注释更新（gui-layout.profile 死配置已移除），仅注释变更。
- `config/ConfigManager.java`：`getGuiBorderMaterial` 全仓零残留（已删除）；REQUIRED_MESSAGE_KEYS 注释同步更新。
- `README.md` / `README-EN.md`：配置清单已去 difficulty-settings，无死键残留。

## 问题清单
无。严重度均为 N/A。

## 不确定项
1. **node 692 vs 全递归 691 口径差**：非缺陷（父 map 误计），但若后续把 692 当"真叶子数"比对会漂移 —— 属观察项，不影响本批 GO。
2. **本批构建期间编排者中途提交（2f586e1）**：已用终态复跑 verify 覆盖，两次均绿；若后续再有提交，需以最终 commit 为准。
