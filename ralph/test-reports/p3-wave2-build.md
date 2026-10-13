# 编译测试报告 p3-wave2（Phase 3 Wave 2 五流合流）

### 判定：FAIL

编译/测试/打包全绿，但**只读检查发现 1 项阻断级缺陷**（Wave 2 新增 i18n 键缺失），判 NO-GO。

---

## 步骤 1 — `mvn clean verify`（全量：编译 + 33 测试 + JaCoCo check）

```
BUILD SUCCESS
Total time: 40.021 s
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
JaCoCo: "All coverage checks have been met."（BUNDLE LINE ≥ 1% 门通过）
Analyzed bundle 'LetsLearnEnglish' with 128 classes
EXIT_CODE=0
```

- 编译 80 个主源文件 + 5 个测试源文件，0 ERROR、0 新增 WARNING。
- 编译期唯一提示：`VersionAdapter.java` 使用已过时 API（deprecation，按判定标准豁免）+ javac `-proc` 注解处理器提示（环境噪声）。**均非本批引入**。
- 测试类：AchievementDefinitionTest(7) / PlayerProgressTest(2) / LearningSessionTest(12) / WordLearningManagerComboTest(6) / WordTest(6) = 33，与既有 33 项一致（本批未新增测试文件）。

## 步骤 2 — `mvn clean package -DskipTests`

```
BUILD SUCCESS
Total time: 22.124 s
EXIT_CODE=0
```

产物 JAR：`target/Let's Learn English!-1.0.7-SNAPSHOT.jar`（600,815 bytes，finalName=`${project.name}-${project.version}`）。

## 步骤 3 — 失败详情（只读检查发现，非编译期）

### F-1 「Wave 2 新增 `duel.challenge-*` 4 键在 zh/en 双语均缺失」（阻断）

| 类型 | 位置 | 详情 |
|------|------|------|
| ERROR(i18n) | `messages/zh.yml` + `messages/en.yml`（`duel:` 段 274~329 行） | `duel.challenge-title` / `duel.challenge-hint` / `duel.challenge-empty` / `duel.challenge-more` **zh=0 en=0** |

- **引用点**：`gui/DuelChallengeGUI.java:111/125/149/156/163`（`MessageUtil.getGuiMsg(messages, "duel.challenge-*")`）。
- **运行期后果**：`getGuiMsg` 缺键返回可见占位符 `[?key]`（`util/MessageUtil.java:186-192`）。选择器标题将显示 `&6[?duel.challenge-title]`；`listener/DuelChallengeListener.java:122-124` 的 `isDuelChallengeGUI` 走 `DuelChallengeGUI.isDuelChallengeTitle` **完整标题等值**比对——标题两侧同取同一缺键占位符时等值判定仍自洽（W2-B 已实测），故点击不会误判，但**玩家可见的标题/提示全部退化为 `[?key]` 占位符**，功能表面破损。
- **判定归属**：**本批引入（Wave 2 W2-B）**。`gui/DuelChallengeGUI.java`、`listener/DuelChallengeListener.java` 均为 `??` 未跟踪新文件（`git status`）；`duel.challenge-title` 仅存在于规划文档 `tasks/phase3/plan-menu-config.md:313`（plan 键表明确列为新增键），从未 fan-in 落到 zh/en.yml。CHANGELOG 与 construction-tracker:77 声称的「i18n 双侧落盘 638=638 键级对称 / report 62 + square.feed 23 + main-menu 4 + word 2」**未覆盖 duel 4 键**——属 fan-in 遗漏。
- **疑似根因**：Wave 2 fan-in 时只合并了 report/square.feed/main-menu/word 四族键，遗漏 W2-B 的 `duel.challenge-*` 4 键；W2-B story（construction-tracker:71）标注「预 fan-in 窗口 [?key] 占位时等值判定仍自洽」，说明该 story 已知键缺失却以占位符自洽搪塞，未在 fan-in 补齐。

### F-2 「死类 `gui/ProgressGUI.java` 残留于 JAR」（预期清理项，非本批引入）

| 类型 | 位置 | 详情 |
|------|------|------|
| WARN(死类) | `target/...jar` → `com/letslearnenglish/minecraftplugin/gui/ProgressGUI.class` | `jar tf` 收录该 class |

- 全库零实例化点（`grep -rn "new ProgressGUI"` 仅构造函数自身；`LearnCommand` 实际走 `ProfileGUI`），其 `progress-gui.*` i18n 段（zh/en:476）唯一消费者即该类自身。
- **判定归属**：**存量**（`tasks/dev-findings.md` P2-23/P3-04 已登记；`git diff HEAD` 对 ProgressGUI.java 为空 = 本批未改动）。plans `plan-menu-config.md:678` 将死类清理排在 **MC-7 收尾批**，非 Wave 2 范围。
- **影响**：任务书步骤 5 期望 `jar tf | grep ProgressGUI` 无产出——当前**有产出**。因 MC-7 尚未执行，判为「遗留未清理」而非本批回归，不单独阻断，但需在 MC-7 收尾时成对删除类 + `progress-gui.*` 键。

## 步骤 4 — 只读检查结果

| 检查项 | 结果 |
|--------|------|
| `plugin.yml` version=`${project.version}`（filtering 生效） | PASS — JAR 内 `plugin.yml` 解出 `version: 1.0.7-SNAPSHOT` |
| `pom.xml` version=1.0.7-SNAPSHOT | PASS |
| Wave 2 新键 zh/en 双语存在（抽查 5+ 键） | **FAIL** — `duel.challenge-title/-hint/-empty/-more` 4 键双语皆缺；`main-menu.duel/-lore/checkin/-lore`、`module.disabled`、`ai-chat.module-disabled`、`word.mode-disabled`、`word.no-modes-enabled`、`report.*`（title/advice-*/batch-*）、`square.feed.*`（23 键）、`achievement-wall.category-*`（6 键）**均在** zh/en 双语齐备 |
| `config.yml` social / report 段存在 | PASS — `report:`(641) 含 enabled/period/language/minutes-per-question/keep-days/respect-privacy/batch/weak-words-limit；`social:`(666) 含 enabled/feed.retention-days/cheer.*/notify-on-join；另有 `english-square:`(default-mode) |
| `jar tf \| grep -i "ProgressGUI\|sample-"` 无残留 | **FAIL** — `ProgressGUI.class` 在包内（sample-\* 无残留，`words/sample-*.yml` 已被 pom exclude） |

## 步骤 5 — 附加包内容检查

- `words/sample-*.yml`：PASS（pom `maven-resources-plugin` `<exclude>words/sample-*.yml</exclude>` 生效，`jar tf` 无匹配）。
- 打包资源清单：`plugin.yml / config.yml / messages/{zh,en}.yml / words/{advanced,beginner,intermediate}.yml / words/README.md / ai_scenarios.yml / achievements-config.yml / achievements.yml / schema.sql`，无多余样本。

---

## 结论：NO-GO

编译、33 测试、JaCoCo check、JAR 产出全绿，但 Wave 2 W2-B 的 `duel.challenge-*` 4 个新 i18n 键 fan-in 遗漏（zh/en 双语均缺，选择器标题/提示退化为 `[?key]` 占位符），须先补齐双语 4 键再放行；`ProgressGUI` 死类残留属存量的 MC-7 收尾范围，不单独阻断。
