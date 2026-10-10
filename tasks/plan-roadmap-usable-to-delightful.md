# 大型开发计划：从"大概能用"到"能用"再到"好用"

- 创建日期： 2026-10-09
- 适用项目： Minecraft-server-plugin-Let-s-learn-English--1（当前 1.0.7-SNAPSHOT）
- 状态： 前期计划（已完成调研，待用户下令启动开发）
- 本文档性质： 总路线图 + Phase 0 / Phase 1 详细施工图。Phase 2 / Phase 3 为轮廓，待 Phase 1 完成后再细化。
- 配套调研输入： 代码基线盘点、Bug 清单核实（19 条）、测试基建调研、外部最佳实践调研、tasks/prd-v1.0.7-iteration.md、rules/project_rules.md、rules/CLAUDE.md

---

## 0. 调研结论（事实基线）

### 0.1 代码基线

- 主代码 69 文件 / 约 15337 行。最重的三层：GUI（17 文件 / 2961 行）、数据层（5 文件 / 2095 行）、监听器（12 文件 / 1808 行）。
- 最臃肿的 3 个文件：`DatabaseManager.java`（1132 行，单连接双检锁）、`PlayerDataStore.java`（845 行，缓存+持久化混合）、`AIChatManager.java`（679 行，HTTP+限流+上下文混杂）。
- 资源文件约 2195 行 + schema.sql 137 行。词库 beginner/intermediate/advanced 各仅 10 词，共 30 词；每词 7 字段（english/chinese/part_of_speech/phonetic/example_sentence/synonyms/antonyms/difficulty_level/reward 口径以 beginner.yml 为准）。
- CHANGELOG 当前版本 [1.0.7] - 2026-10-07。tasks/ 仅有 prd-v1.0.7-iteration.md。源码子目录另有 ralph/（dev-plan、impl-guide、多轮 test-reports）与外层 .claude/agent-memory/ 承担流程规范角色。

### 0.2 Bug 核实结论（19 条：16 CONFIRMED + 3 PARTIAL，无完全证伪）

确认成立的高危 Top5（按危害排序）：

| 排名 | 编号 | 一句话 | 修复规模 |
|------|------|--------|----------|
| 1 | H7 | 任意玩家可经 load_scenario 写全局 ai_scenarios.yml 并 reload，全服提示词注入 | S |
| 2 | H2 | stopQuiz 无条件发全额会话奖励，开局秒退无限刷 50 金币 | S |
| 3 | H9 | /lea reload 直接 clearCache 不先存盘，丢在线玩家未保存进度 | S |
| 4 | H5 | 普通学习从不写 lastReviewTimeCache，复习队列恒空死锁；且复习结算不看对错无条件 recordReview | M |
| 5 | H4 | 下线不清 QuizState（endQuiz 全库零调用），僵尸状态泄漏 | S |

其余 CONFIRMED：H1（发音键泄答案）、H3（AI 进退即计数）、H8（addScore 零调用，total_score 冻结）、H10（player_name 零写入，榜单显示 UUID）、H11（每局抽干 10 词库）、M4（决斗分支吞 quiz 答案且 acceptDuel 不查 hasActiveQuiz）、M8（挑战只在 join 分配，跨天白干；wordChallengeClaimedToday 零读取）、M9（longest_streak 只读不写）、M10（total_words_learned 含答错）、M12（先标已领再转账，失败丢奖励）、M13（无 Key 仍分配 AI_CHAT_5）、M16（单 Connection 跨线程混用）、M18（两套挑战互不可见 + 单词挑战强制弹窗）。

PARTIAL 说明：M5（答错送对方分是故意设计，不算 bug；无单题超时 + cancelDuel 死代码成立）、M20（TTS 缓存 7 天过期，"永久"不成立；无冷却 + 错误文本落盘成立）、H6（拼写判定写死成立；modes.* 零读取子项因 Grep 模式可能漏检标为 UNVERIFIED，需补一次 `word-system.modes` 全路径 Grep）。

### 0.3 测试基建结论

- 构建：Java 21，JUnit 5.10.1 + Mockito 5.8.0（test scope，实际零使用），surefire 仅匹配 `**/*Test.java`，JaCoCo 0.8.11 只生成报告无 check 门禁，无 CI（无 .github/），构建靠 rules/build.ps1 与 rules/test.ps1 本地脚本。
- 测试：src/test 仅 3 类共 238 行（Word/LearningSession/PlayerProgress 纯 POJO 测试），71 个 main 文件覆盖约 4%，核心业务零覆盖。
- 可测性障碍 Top3：构造器硬绑定 LetsLearnEnglish（脱离 Bukkit ��爆）、业务层 Bukkit API 直调且无 MockBukkit、AIChatManager 的 HttpClient 与 DatabaseManager 的 Connection 无注入点。
- 最小补齐方向：引入 MockBukkit（test scope）、JaCoCo 加 check 门禁、加 GitHub Actions、新纯逻辑必须配单测、给 HttpClient/Connection 加注入点。

### 0.4 外部最佳实践要点（知识库整理，上线前复核官网）

- SM-2 简化三档：答对间隔翻倍（封顶 168h）、模糊不变、答错重置 1h，与现有 1h~168h 区间天然兼容。
- 答题防作弊：限时压到 15~30 秒、setCancelled 隐藏答案、per-player 速率限制、出题随机化；休闲学习服不建议上行为检测。
- AsyncPlayerChatEvent：碰世界/玩家/计分板/BossBar 必须 runTask 切主线程；纯数据与判分可在异步做；Map 用 ConcurrentHashMap；DB IO 永不放主线程。
- DeepSeek 成本控制：单玩家每分钟 N 次 + 每日上限 + 全服配额三层限流、月度 token 熔断降级、小 max_tokens（200~300，本计划取 128）、只带最近 3~5 轮历史、429/5xx 才指数退避重试。
- 自定义音频标准链路：sounds.json + sounds/*.ogg + setResourcePack 下发 + playSound 播放；拒绝资源包时回退原版音效。
- 留存：签到 7 天阶梯 + 断签温和重计、每日 3~5 任务 10~20 分钟可完成、固定刷新点 + 登录提醒、社交炫耀位（连续签到榜/学习天数展示）。

---

## 1. 总体路线图：三阶段定义与出口标准

```
Phase 0 基建冻结 ──▶ Phase 1 止血 ──▶ Phase 2 复活（能用）──▶ Phase 3 体验（好用）
   不改行为          修高危漏洞        核心功能可用           留存与口碑
```

### 1.1 阶段定义

| 阶段 | 名称 | 一句话目标 | 允许改什么 |
|------|------|-----------|-----------|
| Phase 0 | 基建冻结 | 建立基线与护栏，后续开发不裸奔 | 测试基建、配置/i18n 安全封装、DB 基线方案、词库流水线（零行为变更） |
| Phase 1 | 止血（大概能用→能用的入场券） | 消灭安全漏洞、数据丢失、经济击穿 | 高危 S/M 修复、AI 消费上限、奖励上限、DB 同步包装 |
| Phase 2 | 复活（能用） | 核心学习闭环真正转起来 | 复习死锁、榜单三修、跨天、决斗冲突、挑战统一、真三模式、词库 300×3 |
| Phase 3 | 体验（好用） | 新手留存与长期运营 | 新手引导、AI 反馈闭环、成就重构、广场社交、赛季、家长报告、资源包音频 |

### 1.2 出口标准（Exit Criteria）

- Phase 0 出口：`mvn test` 全绿且含新增门禁用例、JaCoCo check 生效、死配置清单落盘、DB 基线方案评审通过、词库生成脚本跑通并产出 sample（≥50 词通过 WordBankValidator）。
- Phase 1 出口：Top5 高危复测全关、无新增高危、经济刷取路径（H2/H3/M12）复测封堵、AI 有每日上限与熔断、`/lea reload` 不丢进度（人工用例通过）、CHANGELOG 完整、`mvn clean package -DskipTests` BUILD SUCCESS。
- Phase 2 出口（预告）：`/le review` 对新玩家可用且答错重置间隔、三榜显示玩家名且非零可变、跨天在线挑战刷新、choice/fill_blank 与 spelling 行为可区分、三档词库各 ≥300 词。
- Phase 3 出口（预告）：新玩家首轮完成率目标 ≥70%（人工抽测）、AI 对话有纠错反馈页、成就含 ≥6 个质量型、广场有动态流。

### 1.3 版本与发布纪律（重申，不可违背）

- 不 bump 版本：全程在 1.0.7-SNAPSHOT 下迭代，除非用户明确说"更新版本号"。
- 每次代码变更：先更新 CHANGELOG.md（当前版本段下追加，一行一条），再编译。
- 每次代码变更后：`mvn clean package -DskipTests`（构建用 rules/build.ps1 口径），确认 BUILD SUCCESS。
- JAR 留在 target/，绝不自动复制到 versions/；仅当用户明确说"可以发布"/"发布版本"时才归档。
- 玩家可见文字必��走 zh.yml/en.yml 双文件；新增配置项带注释；schema 变更同步 schema.sql。

---

## 2. 前期计划范围：Phase 0 + Phase 1 施工图

### 2.1 Phase 0：基建冻结（预计 1 轮多智能体批次，不改行为）

| ID | 内容 | 触及文件 | 规模 | 验收 |
|----|------|----------|------|------|
| INFRA-01 | 测试基建最小闭环：引入 MockBukkit（test scope）、JaCoCo 加 check 门禁（行覆盖 50% 起步）、现有 3 测试保持全绿、为 RewardEngine 倍率表新增纯逻辑单测 | pom.xml、src/test/**、core/progress/RewardEngine.java（仅抽取纯函数，不改行为） | M | mvn test 全绿；覆盖率不达标则构建失败；PR 级可复现 |
| INFRA-02 | 配置与 i18n 安全基线：MessageUtil 加 getOrDefault 安全封装、全量 Grep 产出死配置清单（落盘为 tasks/dead-config-audit.md）、启动时校验必填 key 并告警（只告警不改行为） | util/MessageUtil.java、config/ConfigManager.java、LetsLearnEnglish.java（启动校验） | S | 删任一被引用 key 只告警不断服；死配置清单有"key/声称用途/实际引用点"三列 |
| INFRA-03 | DB 基线方案定稿并落地最轻包装：DatabaseManager 查询路径加 synchronized(connectionLock) 包装（M16 止血版）、schema.sql 与建表语句 diff 对齐报告、player_name 写入方案定稿（join 时 UPSERT name，读侧保留 uuid 回落） | data/DatabaseManager.java、schema.sql（仅对齐注释/缺列，不改行为）、tasks/db-baseline.md | M | 并发冒烟（10 线程 × 100 次 save/load）无 SQLITE_BUSY 刷屏；方案文档评审通过 |
| INFRA-04 | 词库扩充流水线：定 YAML 字段模板（以 beginner.yml 为准）、选开源词表来源并记录版权、写生成脚本（输出 YAML + 跑 WordBankValidator）、产出 sample 50 词人工抽查 | scripts/（新建目录，不进 JAR）、resources/words/sample-*.yml（评审用，不上线） | M（内容） | 脚本一键生成；sample 100% 过 Validator；人工抽查 20 词零硬伤 |

Phase 0 并行度：4 个 work item 文件零重叠，可 3 个 mc-dev + 1 个 general-purpose（INFRA-04）全并行。

### 2.2 Phase 1：止血（预计 2 轮多智能体批次）

Work item 清单（全部来自已核实 Bug + P0 建议）：

| ID | 来源 | 内容 | 触及文件 | 规模 |
|----|------|------|----------|------|
| FIX-SEC-01 | H7 | load_scenario 加权限（plugin.yml 声明 learnenglish.scenario.import，默认仅 OP；无权限拒绝并提示）+ 导入改为玩家个人场景（不写全局文件、不全局 reload） | command/LearnCommand.java、plugin.yml、core/ai/AIChatManager.java（个人场景加载点）、zh/en.yml（2~3 key） | S |
| FIX-ECON-01 | H2 + P0-5 | stopQuiz 按已答题数比例发奖励（0~1 题零奖励；正常走完才全额）+ daily-limit:50 真正生效（超限仍可答题但 RewardEngine 跳过发币）+ money.daily-cap（默认 500/人/天，发放前检查） | core/word/WordLearningManager.java、core/progress/RewardEngine.java、data/PlayerDataStore.java（今日计数）、config.yml、zh/en.yml | M |
| FIX-DATA-01 | H9 + H4 | HotReloadManager.reloadAll 先存盘（saveAllOnline）再清缓存 + PlayerJoinListener.onPlayerQuit 调 endQuiz/cancelPractice + endQuiz 复活（可被 quit 调用）+ AchievementManager.reload 后重置 achievementConfig 缓存 | core/manager/HotReloadManager.java、listener/PlayerJoinListener.java、core/word/WordLearningManager.java、core/achievement/AchievementManager.java | S |
| FIX-AI-01 | H3 + P0-4 | endConversation 仅当本轮 ≥N 条有效对话（默认 3，可配置）才计数 + ChatScenarioListener 下线不计数（或同标准）+ ai-chat.daily-round-limit（默认 30/人/天，自然日清零）+ ai-chat.max-tokens 可配置化（默认 128）+ 超限 i18n 提示 | core/ai/AIChatManager.java、listener/ChatScenarioListener.java、data/PlayerDataStore.java（计数+清零）、config.yml、zh/en.yml | M |
| FIX-FAIR-01 | H1 + P0-2 | 发音按钮只发音标不发英文 + showAnswers 默认改 false + answer-broadcast 改为只广播"答对+分"不含答案文本 + 广播前 stripColor + 截断 50 字 | listener/WordLearningListener.java、listener/ChatAnswerListener.java、data/PlayerDataStore.java（默认值）、zh/en.yml（文案） | S |
| FIX-ECON-02 | M12 | 挑战领奖改为先转账后标记（转账失败不置 claimed，可重领）+ 单词挑战自动到账失败可补领（查 claim 状态页或下次 login 补发，二选一，impl-guide 定） | listener/MiscGUIListener.java、core/DailyChallengeManager.java、zh/en.yml | S |
| FIX-STAB-01 | M16（加固） | 若 INFRA-03 的 synchronized 包装在压测下仍有事务交叉，则升级为单线程 DB 执行器（SingleThreadExecutor 串行所有写；读走同执行器或加锁，二选一） | data/DatabaseManager.java（为主，调用方不动或最小动） | M |

H6 遗留核实微任务（Phase 1 第一批开始前由 orchestrator 执行，约 10 分钟）：补一次 `word-system.modes` 全路径 Grep，确认 modes.* 是否真零读取，结论写入 tasks/dead-config-audit.md 附录。若真零读取，H6 的真三模式实现划入 Phase 2（已预留）。

### 2.3 Phase 1 文件冲突矩阵与流分组

冲突分析（同文件即冲突）：

- WordLearningManager：FIX-ECON-01 × FIX-DATA-01 冲突 → 同流串行。
- PlayerDataStore：FIX-ECON-01 × FIX-AI-01 × FIX-FAIR-01 冲突 → 所有 PlayerDataStore 编辑收归同一 agent（Stream 3 拥有该文件，其他流只提字段需求接口）。
- AIChatManager：FIX-SEC-01 × FIX-AI-01 冲突 → 同流串行。
- DailyChallengeManager：Phase 1 仅 FIX-ECON-02 碰它（M8/M18 划 Phase 2）→ 无冲突。
- DatabaseManager：Phase 1 仅 FIX-STAB-01 碰它 → 无冲突。

流分组（3 个 mc-dev 流 + 1 个内容流，全并行）：

| 流 | 拥有文件 | 承担 item | 说明 |
|----|----------|-----------|------|
| Stream 1 学习经济链（mc-dev） | WordLearningManager、RewardEngine、HotReloadManager、PlayerJoinListener、AchievementManager（仅 reload 缓存点） | FIX-ECON-01 → FIX-DATA-01（串行，先 ECON 后 DATA） | 内部串行是因为同文件；对外与其他流并行 |
| Stream 2 AI 与公平（mc-dev） | AIChatManager、ChatScenarioListener、WordLearningListener、ChatAnswerListener、LearnCommand、plugin.yml | FIX-AI-01 → FIX-SEC-01 → FIX-FAIR-01（串行） | LearnCommand 的改动面需与 Phase 2 的 /challenge 改造错开行区，impl-guide 标注 |
| Stream 3 数据与挑战（mc-dev） | PlayerDataStore（Phase 1 独占）、DatabaseManager、MiscGUIListener、DailyChallengeManager（仅 claim 相关方法） | FIX-ECON-02 + PlayerDataStore 全部字段/默认值改动 + FIX-STAB-01 | Stream 1/2 需要的 PlayerDataStore 字段先在 impl-guide 定接口，Stream 3 统一落地 |
| Stream 4 内容（general-purpose） | resources/words/*、scripts/* | H11 首批扩充：beginner 扩至 ≥300 词（intermediate/advanced 留 Phase 2） | 零 Java 改动，全程并行；输出过 Validator 后由 orchestrator 合入 |

跨流接口冻结点（impl-guide 必须先定，流开工前评审）：PlayerDataStore 新增字段名与方法签名、config.yml 新增 key 全名与默认值、zh/en.yml 新增 key 全名。三者由 orchestrator 在 fan-out 前写入 tasks/phase1-interface-freeze.md，各流只许消费不许私自加 key。

---

## 3. Agent 调度规程（Phase 0 / Phase 1 通用）

### 3.1 标准批次流程

```
1. Scout（orchestrator）：确认本批 work item 与文件锁，更新 tasks/phase{N}-interface-freeze.md
2. Fan-out：并行 spawn mc-dev（每流 1 个）+ 内容流 general-purpose；mc-dev 只写自己流的文件
3. mc-dev 自检：每流结束前跑 rules/build.ps1 口径编译（-DskipTests），失败自修
4. Fan-in：orchestrator 收结果，处理跨流合并（i18n/config/schema/CHANGELOG）
5. 测试：并行 spawn mc-tester-build + mc-tester-code + mc-tester-arch（每流一套或全量一套，视改动面定）
6. 修正循环：测试报告返给对应 mc-dev 修，最多 2 轮；第 2 轮仍不过则 orchestrator 接管
7. Gate：mvn clean package（跑全量 test）+ JaCoCo 门禁 + CHANGELOG 检查，三者全过才 commit
8. Commit：git add -A && git commit（message 尾带 Co-Authored-By 行）
```

角色纪律：mc-dev 是纯代码写手，不 spawn tester；orchestrator（main agent）不直接改 Java/YAML，只做合并、i18n、CHANGELOG、config、接口冻结与门禁。

### 3.2 文件锁协议

- 每批开工前 orchestrator 公布文件锁表（文件 →  owning 流），任何 agent 不得写非拥有的 Java 文件。
- PlayerDataStore、DatabaseManager、config.yml、zh/en.yml 为"独占文件"，每批最多 1 个 agent 写，其他流提需求。
- 共享只读文件（Word.java、MessageUtil.java 等）可多流读，不可写（除非锁表明确）。

### 3.3 每 agent 的 Done 定义

- 编译通过（build.ps1 口径，BUILD SUCCESS）。
- 改动行有对应测试或人工复测步骤（写在报告里，格式：前置条件/操作/预期）。
- 无新增硬编码玩家可见字符串（tester-code 抽查）。
- 报告触及文件清单 + 回滚方式（一句话）。

---

## 4. 质量门禁与测试策略

### 4.1 门禁（每批 Gate 必查）

1. `mvn clean package` 全量通过（含 test，不跳过）。
2. JaCoCo check 通过（Phase 0 起生效，阈值 50% 行覆盖，后续只升不降）。
3. 新增/修改的玩家可见文案 zh/en 双文件齐备（tester-code 必查项）。
4. CHANGELOG.md 已更新（当前版本段，一行一条，分类正确）。
5. 死方法/死配置：本批若复活或删除 dead code，CHANGELOG 写明。

### 4.2 Phase 1 回归测试矩阵（人工 + 自动）

| 用例 | 来源 | 类型 | 预期 |
|------|------|------|------|
| 非 OP 执行 load_scenario | H7 | 人工 | 被拒绝，全局文件 mtime 不变 |
| 开局 0 题点停止 | H2 | 人工 | 零奖励；走完 10 题才全额 |
| 每日答题超 50 题 | P0-5 | 人工 | 第 51 题起可答但零金币 |
| 每日金币超 500 | P0-5 | 人工 | 超出部分不发放，有提示 |
| 在线有进度时 /lea reload | H9 | 人工 | 进度不丢；成就开关即时生效 |
| 答题中下线重进 | H4 | 人工 | 无僵尸状态，可正常开新局 |
| 进对话直接 bye ×5 | H3 | 人工 | conversationCount 不涨，AI 任务无进度 |
| AI 单日超 30 轮 | P0-4 | 人工 | 第 31 轮被拒，有 i18n 提示 |
| 点发音按钮 | H1 | 人工 | 聊天栏无英文原词 |
| 他人答题时旁观 | P0-2 | 人工 | 看不到答案文本，只看到加分播报 |
| Vault 关闭时领挑战奖励 | M12 | 人工 | 不丢 claimed，可重领 |
| 10 线程 × 100 次 save/load | M16 | 自动（INFRA-03 压测） | 无 BUSY 刷屏，数据一致 |
| RewardEngine 倍率表 | INFRA-01 | 自动单测 | combo 0~10+ 档位正确 |
| 现有 3 测试类 | 基线 | 自动 | 全绿 |

---

## 5. 突发错误处理流程（开发期间）

用户已预告开发期间可能出现突发错误需要临时派 agent。执行以下流程：

### 5.1 分级

- P0 Blocker：编译不过、服启崩溃、数据丢失、主流程（答题/结算）不可用 → 立即停下当前批次，派 1 个 mc-dev 专修。
- P1 Workaround：某 work item 阻塞但有绕行方案 → 该流挂起，agent 转去做同批其他 item，记入 tasks/blockers.md，批次结束前回头处理。
- P2 Backlog：新发现的非阻塞 bug → 记入 tasks/backlog.md（编号 BUG-NEW-xxx，含复现与触及文件），不进当批，Phase 2 再排。

### 5.2 临时 agent 调度规则

- P0 专修 agent 与原流 agent 不写同一文件：若同文件，原流 agent 先停手（避免冲突），修好后再继续。
- P0 修复同样走 Gate（编译 + 相关回归用例 + CHANGELOG 一行），但可跳过多余 tester 轮次（至少保留 mc-tester-build）。
- 任何 P0/P1 的处理结论追加到 tasks/blockers.md（时间/现象/根因/处理/状态），防止重复踩坑。

### 5.3 回滚

- 每批 commit 前打 tag 前快照：commit 粒度为"一批一次"，P0 修复单独 commit。
- P0 修坏导致 Gate 不过：`git revert` 该 commit，blocker 升级处理，不带病合入。

---

## 6. 风险清单

| # | 风险 | 概率 | 影响 | 缓解 |
|---|------|------|------|------|
| R1 | PlayerDataStore 多流争写冲突 | 高 | 中 | Phase 1 独占给 Stream 3；接口先冻结 |
| R2 | 词库扩充质量（机翻腔/错音标） | 中 | 高 | 脚本生成 + 人工抽查 20 词/批；sample 先行 |
| R3 | MockBukkit 引入拖慢构建或与 Paper API 版本打架 | 中 | 中 | 先在分支验证；若打架则降级为纯逻辑抽取 + POJO 单测 |
| R4 | synchronized 包装治不住 M16（事务交叉） | 中 | 高 | 预留 FIX-STAB-01 升级单线程执行器；INFRA-03 压测提前暴露 |
| R5 | AI 每日上限误伤正常玩家 | 低 | 中 | 默认 30 轮宽松值；超限提示友好；i18n 可配 |
| R6 | 答案广播默认关闭引起老玩家困惑 | 低 | 低 | CHANGELOG 写明；服主公告模板附在 README 变更段 |
| R7 | H6 真三模式工作量超预期 | 中 | 中 | 明确划 Phase 2；Phase 1 只做核实微任务，不动判定逻辑 |

---

## 7. 待用户决策事项（均有默认值，不 блокируют开工）

| # | 决策 | 默认值（不回复即按此执行） | 影响 |
|---|------|---------------------------|------|
| D1 | money.enabled 默认值 | 保持 false，但 README/开服提示明确告知"开经济前先设 daily-cap" | 经济 |
| D2 | showAnswers 默认值 | 改 false（隐私优先） | 公平/隐私 |
| D3 | 词库来源与版权 | 开源大纲词表 + 脚本生成 + 人工抽查；来源记录在 words/README.md | 内容 |
| D4 | DB 并发方案 | Phase 1 用 synchronized 包装止血；压测不过再升级单线程执行器；连接池（HikariCP）留 Phase 2 评估 | 稳定 |
| D5 | M5 决斗答错送分 | 保留设计（注释明确），Phase 2 只加单题超时与 GM 解散 | 玩法 |
| D6 | 版本号 | 全程 1.0.7-SNAPSHOT，不 bump | 发布 |

---

## 8. Phase 2 / Phase 3 轮廓（Phase 1 完成后再细化为施工图）

### Phase 2：复活（能用）—— work item 预排

1. FIX-REVIEW-01（H5 根治）：普通学习写 lastReviewTime 种子；复习结算只对答对词 recordReview，答错重置间隔；ReviewScheduler 答错回 1h。触及 WordManagerImpl/ReviewScheduler/PlayerDataStore。M。
2. FIX-BOARD-01（H8+H10+M9+M10）：endSession 调 addScore；join 时 UPSERT player_name；checkStreak 处落盘 longest_streak；total_words_learned 改存 correct。触及 PlayerDataStore/DatabaseManager/AchievementManager/PlayerJoinListener。M。
3. FIX-DAY-01（M8+M13）：跨天定时刷新挑战（LetsLearnEnglish 加 scheduled task）；分配时过滤 !isAvailable；删 write-only 集合。触及 DailyChallengeManager/LetsLearnEnglish。M。
4. FIX-DUEL-01（M4+M5）：acceptDuel 检查 hasActiveQuiz；ChatAnswerListener 冲突时提示而非吞答案；DuelRoom 加单题超时；加 GM 解散命令。触及 DuelManager/DuelRoom/ChatAnswerListener/LearnCommand。M。
5. FIX-CHAL-01（M18）：两套挑战统一入口（/le challenge 与 GUI 双向可见）；单词挑战完成改为非强制提醒（ActionBar + 可选 GUI）。触及 LearnCommand/DailyChallengeGUI/DailyChallengeManager。M。
6. FEAT-MODE-01（H6 真三模式）：AnswerChecker 策略接口（spelling/choice/fill_blank）；choice 渲染 4 选项（同库干扰项）；fill_blank 例句挖空；choice 作为新手默认。触及 WordLearningGUI/WordLearningListener/ChatAnswerListener/WordLearningManager/WordBank。L。
7. FIX-NUM-01：review-multiplier 默认 1.2、review-penalty 默认 false；复习通知加"今日已提醒"去重。触及 config.yml/LetsLearnEnglish。S。
8. CONTENT-02：intermediate/advanced 各���至 ≥300 词；difficulty_level 补断层。内容流。M。
9. FIX-TTS-01（M20）：发音按钮加冷却；TtsManager 校验 Content-Type，错误体不落盘。S。

### Phase 3：体验（好用）—— 方向清单

1. 新手引导线（首次 3 步：选库 → 3 词迷你会话 → 领成就；WRITTEN_BOOK 引导书）。
2. AI 学习反馈闭环（endConversation 追加总结：句子数/纠错/推荐词；history 详情页展示）。
3. 成就重构（+6 质量型：连续复习/错题清零/正确率90/对决首胜/互助/30天；刷量成就加难度前置）。
4. 广场社交最小循环（动态流/点赞/求助帖）。
5. 赛季与班级（30 天一季，赛季榜重置，总榜保留）。
6. 老师端/家长报告（/api/player 周报 + 一键导出文本）。
7. 资源包音频（sounds.json + ogg + 回退原版音效；连击 pitch 递增）。
8. 倒计时改 ActionBar/BossBar（治刷屏与主线程压力）。
9. 跨服榜（同 MySQL 库多服读同一张表，零成本先行）。

---

## 9. 启动检查清单（用户说"开始"后 orchestrator 依次执行）

- [ ] 确认 D1~D6 默认值（用户无回复即按默认执行）
- [ ] 执行 H6 核实微任务（word-system.modes 全路径 Grep），结论记入 tasks/dead-config-audit.md 附录
- [ ] 写 tasks/phase0-interface-freeze.md（Phase 0 接口极少，可简化为文件锁表）
- [ ] spawn mc-planner：基于本计划产出 Phase 0 impl-guide（含 INFRA-01~04 的类/方法级步骤）
- [ ] Phase 0 fan-out（3 mc-dev + 1 general-purpose）→ fan-in → testers → Gate → commit
- [ ] 写 tasks/phase1-interface-freeze.md（PlayerDataStore 字段/config key/i18n key 三冻结）
- [ ] spawn mc-planner：基于本计划产出 Phase 1 impl-guide（含 Stream 1~3 的串行顺序与行区标注）
- [ ] Phase 1 批次 A（FIX-SEC-01/ECON-01/DATA-01/AI-01 前半）→ Gate → commit
- [ ] Phase 1 批次 B（剩余 + FIX-STAB-01）→ Gate → commit
- [ ] 输出 Phase 1 完成报告 + Phase 2 细化施工图（届时再规划）

---

## 附录：关键文件速查（impl-guide 编写用）

- 学习核心：core/word/WordLearningManager.java、core/word/WordManagerImpl.java、core/word/ReviewScheduler.java、core/word/WordBank.java、gui/WordLearningGUI.java、listener/WordLearningListener.java、listener/ChatAnswerListener.java
- AI：core/ai/AIChatManager.java、core/ai/TtsManager.java、listener/ChatScenarioListener.java、gui/ScenarioSelectGUI.java
- 决斗：core/duel/DuelManager.java、core/duel/DuelRoom.java
- 挑战签到：core/DailyChallengeManager.java、core/manager/CheckInManager.java、gui/DailyChallengeGUI.java、gui/ChallengeCompleteGUI.java、listener/MiscGUIListener.java
- 成就进度：core/achievement/Achievement.java、core/achievement/AchievementManager.java、core/progress/RewardEngine.java、core/progress/ProgressManagerImpl.java
- 数据：data/PlayerDataStore.java、data/DatabaseManager.java
- 命令：command/LearnCommand.java、command/AdminCommand.java、core/manager/AdminCommandHandler.java
- 入口与调度：LetsLearnEnglish.java、listener/PlayerJoinListener.java、core/manager/HotReloadManager.java
- 资源：resources/config.yml、resources/messages/zh.yml、resources/messages/en.yml、resources/words/*.yml、resources/ai_scenarios.yml、resources/achievements-config.yml、resources/schema.sql、resources/plugin.yml
