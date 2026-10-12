# 编译测试报告 P2（合流构建门禁）

### 判定：PASS

## 步骤 1 — `mvn clean verify`（全量，含单测 + JaCoCo check）

- BUILD：SUCCESS
- EXIT code：0
- Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
- 测试明细：
  - PlayerProgressTest: 2/0/0/0
  - LearningSessionTest: 12/0/0/0
  - WordLearningManagerComboTest: 6/0/0/0
  - WordTest: 6/0/0/0
- JaCoCo check：`All coverage checks have been met.`（Analyzed bundle 'LetsLearnEnglish' with 103 classes）
- 编译：72 个 main source files 编译通过；4 个 test source files 编译通过
- 唯一告警：VersionAdapter.java 使用/覆盖已过时 API（deprecation，属既有告警，按规则不计入失败；-Xlint:deprecation 可查看细节）
- 总耗时：33.106 s

## 步骤 2 — `mvn clean package -DskipTests`（JAR 口径确认）

- BUILD：SUCCESS
- EXIT code：0
- Surefire：Tests are skipped（预期）
- JaCoCo report：Skipped（无 exec 数据，预期）
- 产出 JAR：`target/Let's Learn English!-1.0.7-SNAPSHOT.jar`
- 总耗时：18.161 s

## 步骤 3 — 失败详情

无。两步均 BUILD SUCCESS，无编译错误、无测试失败。

## 步骤 4 — 顺带只读检查

| 检查项 | 期望 | 实际 | 结论 |
|--------|------|------|------|
| `src/main/resources/plugin.yml` version | `${project.version}`（不得写死） | `version: ${project.version}` | OK |
| `pom.xml` project version | `1.0.7-SNAPSHOT` | `<version>1.0.7-SNAPSHOT</version>` | OK |

## 结论

**GO** — P2 合流构建门禁全绿，26 单测全过、JaCoCo 门禁通过、JAR 正常产出，无本批引入问题。
