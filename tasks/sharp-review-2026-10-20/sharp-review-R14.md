# 锐评 R14 · 工程实践与测试

> 2026-10-20 战略锐评 · 工程效能教练视角 · 只读 · agentId aa154a0d2b35d47af

## 1. 一句话判决

一个**文档与文化近乎专业、执行与门禁近乎裸奔**的项目：97 份 PRD/审查报告证明了团队知道哪里会塌，而 49 个测试、1.86% 覆盖率、零 CI、`mvn package -DskipTests` 被写进 README 这一事实，证明了它每次都不敢确定没塌——**这是一套用纸面审查代替自动化回归的手工业质量体系**。

## 2. 致命伤 Top3

**① 回归安全网等于零，而改代码的手速是全速的。** `src/main/java` 88 个文件 32,925 行，测试 8 个文件 754 行；JaCoCo 实测行覆盖 269/14440 = **1.86%**（指令 2.07%）。核心路径全线零覆盖（`target/site/jacoco/jacoco.csv`）：`DatabaseManager` missed=1381 cov=0、`PlayerDataStore` 659/0、`DuelManager` 626/0、`RewardEngine` 242/0、`WordBank` 113/0、`WordBankValidator` 98/0、**`AnswerChecker` 77/0**、`ReviewScheduler` 69/0、`WordLearningManager.QuizState` 13/0。**答题状态机、Ebbinghaus 间隔、奖励发放、duel 结算、DB 迁移——五条命脉一条都没有测试。**"能跑 mvn test"与"敢改代码"之间，隔着 32,925 行无保护代码。

**② 没有任何 CI。** 仓库无 `.github/` 目录（`ls -a` 确认不存在），无 git hooks（`.git/hooks` 全是 sample）。有 origin（`github.com/sqkl520/...`）但一条 workflow 都没写。唯一的"门禁"是 `pom.xml` 里绑在 `verify` 阶段的 jacoco `minimum 0.01`——而真实覆盖是 1.86%，**这道门连自己当前的地板都碰不到**，且注释自己承认："STARTING FLOOR ONLY... Ratchet this up one notch every release"。1.0.7 已发布，门仍是 1%。这不是防御性测试，是**装饰性门配装饰性测试**——它的作用只是让 `mvn verify` 输出里多一行 SUCCESS。

**③ 配置/契约漂移全靠人肉记忆，且校验器在错误的路径上。** `scripts/audit-i18n-keys.js` 64 行手写正则解析 YAML（无 `package.json`、无 js-yaml），**只查"引用→缺键"单向**，查不出"键→无引用"死键；`ralph/test-reports/final-consistency-audit.md:155` 亲自记录了后果：691 叶子键里 **8 个死键（1.2%）全部来自 4B 大扫除的漏网**，且"audit 全绿"。已知假阳性（`achievement-wall.category-`）靠 `.claude/agent-memory/mc-dev/verify-yml-parity-without-python.md` 里的口头判读口径消化。`WordBankValidator`（98 行，零覆盖）只在管理员导入路径被调用，**自带 `words/*.yml` 绕过校验**——这正是 `tasks/review-phase2-total.md` R-06 记录的高危 NPE 崩溃链：审查发现了、修了、**至今没有测试钉住**。

## 3. 分条炮击

**测试金字塔的形状是倒的。** 49 个测试的分布：`LearningSessionTest` 12 / `AchievementDefinitionTest` 7 / `ConversationFeedbackTest` 6 / `WordTest` 6 / `WordLearningManagerComboTest` 6 / `ProxyFrameParseTest` 5 / `CountdownTierTest` 5 / `PlayerProgressTest` 2。抽样十个断言：好的一半确实好——`CountdownTierTest.boundaryIsStrictlyGreater` 双侧钉死阈值（防"学习/对决两侧判定不同源"）、`ConversationFeedbackTest.testMarkdownFencesAndChatterAreSalvaged` 覆盖模型吐脏 JSON 的降级路径、`ProxyFrameParseTest` 覆盖截断帧/垃圾字节/中文多字节、`LearningSessionTest.testResultsImmutable` 断 `UnsupportedOperationException`、`AchievementDefinitionTest` 冻结 16 个 legacy id 防 DB 进度丢失。**坏的一半是位置错了**：`PlayerProgressTest.testSetters`（41 行 2 个测试）就是 setter/getter 同义反复，纯噪音；而 `getComboMultiplier` 一个静态查表函数独占 6 个测试。测试密度分布与风险分布严重错配——全部落在叶子值对象上，业务核心全在零覆盖区。**唯一的 Mock 使用是 `WordLearningManagerComboTest` 里 `mock(LetsLearnEnglish.class)` 喂构造函数**，注释明确写"hence no MockBukkit needed"。**MockBukkit 一次都没用；pom 里连 test-scope 的 `org.xerial:sqlite-jdbc`、H2 都没有**——所以 DB 层不是"用 mock 还是用真 SQLite"的问题，是**根本没测**。脆弱测试迹象：`LearningSessionTest.testElapsedSeconds` 里 `Thread.sleep(100)` 然后断 `>= 0`——这个断言永真，是典型的会随时间漂移的假测试；`WordLearningManagerComboTest` 耗时 6.334s（其余全在 0.2s 内），说明有隐藏等待。

**DB 迁移：无版本化、无测试。** `DatabaseManager.java` 3031 行，迁移就是 `:2970` 的 `addColumnIfMissing` 逐个 `ALTER TABLE`（1.0.7 审查报告还记着它曾因非英文 locale 误判而改元数据查询——**这个 bug 修完仍无回归测试**）。没有 Flyway/Liquibase，`schema.sql` 存在但没有"从旧 schema 启动一遍"的测试。这是最危险的空白：**改坏了 SQL 只是编译通过，玩家数据在下次升级时才炸**。

**提交历史：前缀规范、粒度失控。** 80 个提交，Conventional Commits 风格做得像模像样（`fix(review-c1)` / `feat(p3-w4)` / `docs(tasks)` / `chore(phase0)`），中文描述信息量大。但原子性是反面教材：`300c699` **37 文件 +3309 行**、`44ce642` 21 文件 +1298、`d5133da` 30 文件 +984、`794107e` 14 文件 +2573——一个提交里混着 pom 改动、`DatabaseManager` 928 行重排、i18n helper、新脚本、4 份规划文档。这是"多 Agent 并行施工 + 一次性合流"的直接产物：**Agent 流是工作单元，PR 不是**。另有卫生级丑闻：`d5ad3a3` 提交的内容是"修复文档里被 bash 转义吃掉的反引号"，`ede8100`/`3bf8eae` 是"历史重写后同步文档里的 SHA 引用（18 文件 54 处 / 6 文件 24 处）"——**做过 `git filter-branch` 级历史重写，然后靠后续提交去补文档里失效的 SHA**，还留了仓库根的 `repo-backup-before-rewrite-20261018.bundle`。约 30/80 个提交是纯 `docs(tasks)` 状态回写，代码提交与台账提交交织，bisect 基本不可用。

**版本与发布：SNAPSHOT 当版本号用。** `pom.xml:11` = `1.0.7-SNAPSHOT`，CHANGELOG 第一节 = `## [1.0.7] - 2026-10-18`（已发布），`git tag` 列出 `1.0.0-SNAPSHOT … 1.0.7-SNAPSHOT` 和一个裸 `SNAPSHOT`——**把 SNAPSHOT 标签打在发布版上，直接违反 Maven 语义版本约定**：SNAPSHOT 意味着可变开发态，用户拿到的 jar 名带 `-SNAPSHOT`，同一个 tag 可以被反复覆盖。`versions/` 里 7 个二进制 jar 提交进 git（85KB→684KB），缺 1.0.2，且 `git ls-files` 确认它们被跟踪——**没有 GitHub Release、没有 artifact 仓库，发布物就是仓库里的手拷二进制**（CLAUDE.md 规则 6"发布是手动的、绝不自动拷 jar 到 versions/"，规则 3"除非用户说'更新版本号'否则不升版本"——所以 SNAPSHOT 会一直挂着）。CHANGELOG 631 行**只给开发者看**：1.0.7 条目里塞满 `[P3 Wave 3 Batch 3A / 域 5 赛季]`、`anchor-date 纯函数窗口推导`、`catch-up 游标`、`fan-in`、`tester 收口` 这种内部施工黑话——玩家根本读不下去，面向用户的更新日志**不存在**（README 里没有 changelog 链接）。

**新人第一周能合 PR 吗？不能，而且没有 PR 可合。** 无 PR 模板、无 issue 模板、无 CODEOWNERS、无 CONTRIBUTING、无 `docs/`、无分支保护（CI 都没有，无从谈门禁）。README 的"构建"章节全部两行：`mvn package -DskipTests` —— **官方文档告诉新人跳过测试**，`mvn test`/`verify` 一次都没在 README 出现。新人真正需要的入口（分层架构、i18n 三处同步规则、config-version 迁移纪律、词库来源许可）全在 `CLAUDE.md` 与 `.claude/agents/*.md`，而 `.gitignore` 第 40 行明确写着 `.claude/` 忽略，且 `CLAUDE.md` 在**仓库的父目录**——**克隆仓库拿不到任何一份项目知识**。硬编码绝对路径 `D:\maven\apache-maven-3.9.9\bin\mvn.cmd`、`D:\Java\zulu21...`，无 mvnw wrapper（`.mvn`/`mvnw` 均不存在）。脚本要求 node（agent-memory 记录"python 未安装，node v24 在"）——机器绑定。唯一可复现路径是原作者本人那台 Windows 机。

**公道话（这部分我给得很实在）。** 三个子系统在业余项目里属于**超纲**：(1) **i18n 是全项目最接近零缺陷的资产**——691 叶子键 zh/en 对称、`MESSAGE_CONFIG_VERSION` 正确升到 v17、每加键同步三处并明示"已部署服不升版本收不到新键"的运维后果；(2) **`tasks/review-phase2-total.md` 的证据密度超过多数商业项目的季度技术债台账**——每条带 `文件:行号`、触发条件、严重度、**"是否 Phase 2 新引入"列**（专列回归归因），还有"编排者已核实/疑似是"的置信度标注，49 条分级审查后按 P0/P1/P2 分批清零并在 commit message 里回指条号；(3) `CountdownTierTest` / `ProxyFrameParseTest` / `ConversationFeedbackTest` 说明团队**会写测试**——`ProxyFrameParseTest` 的注释"把只能在双服环境验证的修复变为可单机自动回归"是教科书级的可测性重构意识。问题从来不是能力，是**投入比例（754 行测试 vs 32,925 行代码）和没有 CI 把纪律接上**。

## 4. 最应该先补的 3 个测试 + 1 条流水线

1. **`AnswerCheckerTest`（ROI 最高，零改造成本）** — 287 行 `final` 静态类，无 Bukkit 依赖，`check(mode, word, answer, options, caseSensitive)` + `buildChoiceOptions`。覆盖三模式 × caseSensitive × 边界（大小写、trim、非法字母 C/D 越界=R-10、`optionCount` 钳位 [2,4]、屈折形例句挖空回落=R-14）。**~77 行代码即可，钉住两个已修高危回归。**
2. **`ReviewScheduleIntervalTest`（先做一次纯函数抽取）** — 把 `ReviewScheduler.java:109-120` 的逻辑提为 `static long nextReviewAt(long lastReview, int reviewCount, long[] intervals, long masteredMs)`，测 6 行表：count=0/1/size-1、**count≥size 进 MASTERED 长周期**（1.0.7 PRD Bug 2 正是"复习完所有间隔后永久排除单词"）、`seedReview`（now+intervals[0] 不立即 due）、`resetReview`（count→0）。顺手把 `dueWordCache` 失效断上。
3. **`DatabaseManagerMigrationTest`（test-scope 加 `org.xerial:sqlite-jdbc`，真 SQLite + `@TempDir`）** — 用现成的 `src/main/resources/schema.sql` 建基线，插入旧行后跑迁移，断：列补齐、旧数据不丢、UPSERT 幂等、`addColumnIfMissing` 对已存在列不抛异常。这是**唯一会让未来某天安静毁掉玩家存档的层**，1,381 行零覆盖不可接受。
4. **一条 CI：`.github/workflows/build.yml`** — `on: pull_request + push(main)`，job：`setup-java@v4 (temurin 21)` → `mvn -B verify`（跑 surefire + jacoco，**不允许 -DskipTests**）→ `node scripts/audit-i18n-keys.js`（**先改成有缺键即 `process.exit(1)`，现在是纯 print，永远退 0**）→ `node scripts/gen-words.js check src/main/resources/words/*.yml`（该脚本已有 exit 1/2）→ `codecov/upload` 传覆盖率 → `actions/upload-artifact` 传 jar（**取代 `versions/` 目录里的二进制提交**）。配套三件小事：删掉 `.gitignore` 里的 `.claude/` 并把 `CLAUDE.md` + agents 纳入版本控制、加 mvnw wrapper、`jacoco minimum` 从 0.01 提到 0.06 并在每次发布上调一格（棘轮只有真的被 CI 跑起来才有意义）。

**发版另加一条**：tag 改成不带 `-SNAPSHOT` 的 `v1.0.7`（`pom` 在 release 时升 `1.0.7`，合并后回 `1.0.8-SNAPSHOT`），GitHub Release 挂 jar + **面向玩家的精简 changelog**，与开发者版分离。

## 5. 只改一件事，改什么

**给仓库加上 `.github/workflows/build.yml`，让 `mvn -B verify` 在每一次 push 和 PR 上强制运行——只此一件。** 理由很冷：这个项目不缺知识、不缺审查眼光、不缺文档纪律，它缺的是**把已知结论变成不可回退的机器裁决**。今天 49 个测试全靠人（和 Agent）记得去跑，8 个死键正是在"全绿"的口头结论里漏掉的；1% 的 jacoco 门之所以可以永远是 1%，正因为没有任何外力强迫它变。有了这条流水线，上面 3 个测试的缺失会从"没人想起来"变成"构建不绿"，覆盖率棘轮第一次具有物理意义，新人也才第一次拥有一个可验证的"改对了"信号。反过来说，**没有 CI，补再多测试都只是把装饰做得更贵一点喵**——而补测试这件事本身，也会在下一次赶版本时和 i18n 死键一起漏掉喵。
