# 全仓审查基线（2026-10-18，编排者记录）

- **基线提交**：`3bf8eae`（远端 main 同步；工作树干净）
- **规模**：主源 87 文件 / 32,273 行；测试 8 文件 / 49 用例
- **编译基线**：`mvn clean verify` = BUILD SUCCESS（49/49 测试通过，jacoco 覆盖门达标），用时 25.5s
- **i18n 审计基线**：`node scripts/audit-i18n-keys.js` → 引用键 521；唯一告警 `achievement-wall.category-` 为**脚本误报**（代码是前缀拼接 `"achievement-wall.category-" + category.name().toLowerCase()`，五个 category-* 键在 zh/en 均存在，AchievementCategory 枚举 = LEARNING/CONSISTENCY/ECONOMY/SOCIAL/REVIEW 全覆盖）
- **热点文件（近 20 提交改动频次）**：SocialListener(4)、DatabaseManager(3)、WordLearningManager(3)、LetsLearnEnglish(3)、zh/en.yml(各 3)、schema.sql(2)、config.yml(2)、MainMenuListener(2)、AchievementWallGUI(2)、DuelManager(2)、DailyChallengeManager(2)、ConfigManager(2)
- **planner 声称观察点**：tasks/phase3/final-review.md「三盲区模式」= (a) plan 裁决没人执行 (b) 后建表没补旧函数 (c) config 承诺没人对实现——列为本轮重点假设

## 审查任务输出目录
- 计划：tasks/review-2026-10-18/review-plan.md（规划 agent 产出）
- 各域 findings：tasks/review-2026-10-18/findings-*.md（各审查 agent 产出，只写自己文件）
- 红队验证：tasks/review-2026-10-18/verify-*.md
- 汇总：tasks/review-2026-10-18/review-summary.md（编排者最终产出）

## 纪律备忘（派发给每个 agent）
- 只读源码，禁止修改任何 src/ 与 resources/ 文件；只允许写自己名下的 findings-*.md
- 每条 finding 必须附 file:line + 真实代码片段证据 + 复现场景 + 修法建议；禁止猜测性指控（无证据的怀疑标注"待验证"另列）
- 禁止复审 dev-findings.md 已关闭条目（除非有证据表明修复引入回归）
- 发现"看起来像 bug 但属有意设计"的，必须对照 review-plan.md 的豁免清单核验后放行或反驳
