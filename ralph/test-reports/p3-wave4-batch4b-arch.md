# 架构报告 Phase 3 · Wave 4 · Batch 4B（MC-7 死键/死类大扫除）

### 判定：PASS

审查对象：删除型批次——死类 `gui/ProgressGUI.java`、`ConfigManager.getGuiBorderMaterial`、i18n 14 项死键、config 11 项死键（未提交，工作树）。
审查核心：删除后架构完整性与残留引用面。

---

## 逐维度结论

### 1. 删除完整性 — PASS
- **`gui/ProgressGUI.java` 零残留（代码面）**：
  - 文件不存在：`**/gui/*.java` glob = 19 个类，无 ProgressGUI。
  - `src/` 全树 `grep ProgressGUI` = 0 命中（import / `new` / getter / 注释 / javadoc 均无）。
  - `target/classes/.../gui/*.class` glob 无 `ProgressGUI.class`；`target/` 全树 grep `ProgressGUI` = 0。
  - 无悬空 javadoc 指向该类（`grep ProgressGUI` 在 `src/` 零命中，javadoc 面自然为零）。
- **`getGuiBorderMaterial` 零残留（代码面）**：`src/` 全树 = 0 命中。唯一文本命中为 `CHANGELOG.md:454`（旧版本 Added 条目历史描述，存档性质，非代码残留）。
- **成对删除已落实**：`progress-gui.*` i18n 段与死类同期消失（`src/main/resources/messages/{zh,en}.yml` grep `progress-gui` = 0），未产生"删类留键"的新死键。
- **`gui-layout.profile.*` 段**：config.yml:451-452 保留一行说明注释（"旧 profile 段（15 个叶子键）已整体移除…死配置"），`SocialListener.java:82-83` 注释同步改写指向 MC-7——删除痕迹有据可查，无悬空指向活配置的注释。

### 2. DI/注册面 — PASS
- `LetsLearnEnglish.java` 全文件无 `ProgressGUI`（无字段、无 `new`、无 getter、`registerListeners()` :314-335 无该监听器注册行）。死类从未注册，删除后 DI 面无痕。
- 反证：`onEnable()` 构造块（:145-249）与 getter 区（:632-772）逐段核对，无任何孤儿注入点。
- 无 hidden static / 无残留 getter（该类本就走 `ProfileGUI`，`/le progress` → `LearnCommand.handleProgressCommand` :360，路径完整）。

### 3. 包结构一致性 — PASS
- `gui/` 现有 19 个 `.java`：LearningCalendarGUI / DuelGUI / WrongBookGUI / ChallengeCompleteGUI / WordBankSelectGUI / ConfirmationGUI / AchievementWallGUI / DailyChallengeGUI / WordLearningGUI / MainMenu / DuelChallengeGUI / EnglishSquareGUI / ScenarioSelectGUI / PersonalScenarioGUI / LeaderboardGUI / ChatHistoryGUI / ProfileGUI / GUIUtils / ClassBoardGUI。
- `README.md` / `README-EN.md` **无类清单**（仅命令表 + 配置段列表），无 ProgressGUI 引用 → 无需更新，无漂移。
- `docs/PROJECT_STRUCTURE.md` grep `Progress` = 0。
- 类数 148→147 与 tracker :205 记录一致（`mvn verify` 147 类）。

### 4. config 键删除的架构面 — PASS
- **`getGuiSlot` / `getGuiMaterial` 调用方全部传合法 guiName，无死参数回归**：全树 80+ 调用点逐个核对 guiName ∈ {`main-menu`, `word-learning`, `english-square`, `leaderboard`, `achievement-wall`}——**无一处传 `"profile"`**。删 `gui-layout.profile.*` 后无调用方悬空。
- **校验名单无残留引用**：`ConfigManager.REQUIRED_CONFIG_KEYS`(:32-52) 与 `CHALLENGE_REWARD_KEYS`(:59-64) 逐项核对，无一条被删键（`gui-layout.*` 本就不在名单）。
- `getGuiBorderMaterial` 删除后 `Material` import 仍被 `getGuiMaterial` 返回类型使用，无冗余/遗漏 import。
- 其余删除键代码面复核：`difficulty-settings` / `database.mysql.pool-size` / `gui.custom-textures` / `proxy.cross-server-*` / `gui.main-menu-title`（config 版）/ `progress-system.rewards.types.{item,command,exp}` / `progress-system.achievements.config-file` / `gui-layout.main-menu.scenario-select.back-button` 在 `src/` 均 0 命中。
- **活键保护核验**：
  - `progress-system.rewards.types.money.*` 保留 → `RewardEngine` 12 处 `money.*` 读取（:77/328/330/370/372/422/424/426/464/468/533）全部仍可解析。
  - config 版 `gui.main-menu-title` 删除**但 messages 版保留**（`ConfigManager:86` REQUIRED_MESSAGE_KEYS + zh/en:378）——`MainMenu.java:36` / `MainMenuListener.java:95` 读的是 messages 版，未误删。
  - `leaderboard.header/entry/footer` 保留 → `LearnCommand:890-907`（`/le season` 快览）仍引用。

### 5. messages 键删除的架构面（抽查 14 键 ≥ 5）— PASS
删除键在 `src/` 全树 0 引用：`word-learning-gui.answer-broadcast`（一字之差活键 `-correct` 保留，`ChatAnswerListener:90/93` 仍引用）、`daily-challenge.claim-button(+lore)`、`leaderboard.title-weekly|monthly|alltime`、`word.session-end|correct|incorrect|mastery-update|daily-limit-reached`（活键 `dialogue.daily-limit-reached` 保留，`AIChatManager:845` 引用）、`checkin.streak-info`、`general.data-saved`、`admin.reset-pending`、`error.scenario-save-failed`、`dialogue-gui.*` 全段、`progress-gui.*` 全段。
`ConfigManager.REQUIRED_MESSAGE_KEYS`(:75-87) 无残留被删键；`word.session-start` 仍为必检活键且 zh/en:34 保留。

### 6. 范围外发现（只上报，不修复）
| # | 位置 | 内容 | 严重度 |
|---|------|------|--------|
| O-1 | `src/main/java/.../gui/GUIUtils.java:66-67` | `isPluginGUI` 仍含 `title.contains("Learning Progress")` / `title.contains("学习进度")` 分支——**已删 ProgressGUI 的历史标题**，分支现为死代码，且**无注释标注**（相邻 :63 行 "旧 DialogueGUI 标题…保留待统一清理" 有标注，此二行无）。GUIUtils.java 不在 4B 白名单，属范围外 | 低 |
| O-2 | `src/main/java/.../gui/GUIUtils.java:71-73` | `leaderboard.title-*` 注释承诺"保留待统一清理"，而本次已删该三键——相邻的 `Weekly Ranking`/`Monthly Ranking`/`All-Time Ranking` 分支随之成死分支（注释自认陈旧） | 低 |
| O-3 | `D:\...\Let's learn English!\rules\CLAUDE.md:25` | 上级目录 rules 文件 Layer Map 仍写 "— ProgressGUI is a dead class pending Wave 4 removal"。属上级存档文档（非本仓 src），未随本批更新 | 信息 |
| O-4 | `D:\MCServers\...\CHANGELOG.md:454` | 旧版本 Added 条目描述 `getGuiBorderMaterial()`——历史存档，正常保留 | 信息 |

> 说明：O-1/O-2 均为 *string 字面量* 死分支（非对 ProgressGUI 类名的结构化引用），且 4B 为白名单制删除批（GUIUtils 未在白名单），故按"范围外只上报"处理，不构成 FAIL。

---

## 不确定项
- 无阻塞性不确定项。
- 说明：leaf key 692=692 对称、SnakeYAML 真解析零差异、`audit-i18n-keys.js` 零新增缺失、`mvn verify` BUILD SUCCESS（147 类/39 测试）均由 build/code tester 覆盖；本报告只做架构面复核（引用面/注册面/包结构/校验名单），未重复执行构建。

## 与登记债务的核对（PASS 确认项，非新违规）
- P2-23 / P3-04（`ProgressGUI` 死类）：**本批闭环**——类 + `progress-gui.*` 键成对删除，代码面零残留。
- PF-ONB-07（观察项：删 ProgressGUI 后 `core/progress/PlayerProgress` 是否成孤儿）：**未成孤儿**——`PlayerProgress` 仍被 `ProgressManagerImpl.getPlayerProgress`(:29-37，`loadStreakData` 见 :39-57) 与 `PlayerProgressTest`(单测 3 处) 引用，类保留合理。
- P3-15（`core/progress/Achievement.java` 死类）：本批范围外，仍未清理（死亡类仍存），保持可见。

## 结论
四维（分层/单一职责/DI 注入/事件注册）在本删除批语境下等价展开为"删除完整性 / DI 注册残留 / 包结构与文档一致性 / 校验名单与调用面"，**均零违规**。判定 **PASS**，附 2 低 + 2 信息级范围外发现。
