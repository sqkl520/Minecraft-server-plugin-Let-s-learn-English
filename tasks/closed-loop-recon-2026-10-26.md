# 闭环断裂（C1）现状侦察 · 2026-10-26

> 输入：15 路战略锐评 `tasks/sharp-review-2026-10-20/sharp-review-summary.md` §1 C1（5 源互证，判为最高优先级）
> 方法：只读侦察 + 编排者逐条 grep/读实现复核
> 结论：5 条子问题中 **2 条未做、2 条半成品、1 条只写不读**

## 一、五条子问题现状

| 子问题 | 状态 | 关键证据 | 剩余缺口 |
|---|---|---|---|
| **R3** 答错泄答案 / 重考背答案 | 基本未做 | `ChatAnswerListener:310` 立即发 `word-learning-gui.incorrect`；`zh.yml:482` 文案含 `{answer}` 明文 | ①答案显隐无配置项；②**无任何冷却**——`WordBank.selectWordsForSession` 只按 mastery 过滤 + shuffle，答错后立刻开新会话可再抽到同词；③普通会话答错不重置复习间隔 |
| **R9** corrections 不落实物 | 半成品（词级已做） | `ChatFeedbackManager:267-347` `applyToLearningLoop`；`:314-319` 计数→第 3 次入错题本；`:320/:329` `seedReview` | ①**句级纠错静默丢弃**（`:303-305`）；②**词形变化丢弃**（`:307-309` `existsInWordBank` 失败即 continue，词库只有原形）；③丢弃无日志无计数 |
| **R12** AI 场景 ↔ 词库不通 | 未做 | `AIChatManager:871-903` `buildSystemPrompt` 只拼场景 prompt + 难度；全类 `WordBank`/`mastery`/`getMasteredWords` **grep 零命中** | ①无词库进度注入；②`{scenario}`/`{difficulty}` 变量**是死配置**（`:37` 注释与 `ai_scenarios.yml:6-7` 都声称支持，全类无 `replace`） |
| **R2** 推荐词只打印一行 | 半成品 | `ChatFeedbackManager:391-397` `getSuggestedFocusWords` **全仓零调用点**（仅 javadoc + 定义）；`:386-389` 自陈"尚无注入 API" | 数据算出来但没人消费 |
| **R10** 决斗零持久化 | 只写不读 | 写：`DuelManager:751` → `:808-829` → `DatabaseManager:1521-1541`；表+三索引+365 天保留齐全 | ①`getDuelStats`/`getDuelWinStreak`/`DuelStats` 在 `DatabaseManager` 外**零调用**；②forfeit/掉线/管理员解散不落库→胜率系统性高估；③`DuelStats.total()/winRate()` 是死方法 |

## 二、施工切分（3 流并行，文件白名单互斥）

### B1 · AI 内容线打通（R12 + R2）
- 白名单：`core/ai/AIChatManager.java`、`LetsLearnEnglish.java`（仅 DI 接线段）、`resources/ai_scenarios.yml`（仅注释）
- 产出：`buildSystemPrompt` 注入目标词（数据源经 provider 注入，避免与 `ChatFeedbackManager` 成环）+ `{scenario}`/`{difficulty}` 替换 + `{focus_words}` 占位符

### B2 · 学习闭环收口（R3 + R9 尾巴）
- 白名单：`core/ai/ChatFeedbackManager.java`、`listener/ChatAnswerListener.java`、`core/word/WordBank.java`
- 产出：corrections 词形兜底（去 `-s/-ed/-ing` 回查词库）+ 丢弃计数日志 + 答错答案显隐配置 + 选题排除最近已答（不碰 DB 表结构）

### B3 · 决斗战绩读侧（R10）
- 白名单：`core/duel/DuelManager.java`、`gui/ProfileGUI.java`、`expansion/LLEPapiExpansion.java`
- 产出：forfeit 落库 + 主页战绩行 + 2 个 PAPI 占位符

### 编排者 fan-in（三流都别碰）
`config.yml`、`messages/zh.yml` + `en.yml`、`CHANGELOG.md`

## 三、需要产品决策的点

1. **B1 的"注入目标词到 prompt"** —— 属教学法假设，无 A/B 数据。缓解：加 `ai-chat.focus-words.enabled` 开关 + 条数上限。
2. **B3 的 forfeit 落库** —— 会改变历史胜率口径（`CHANGELOG.md:41` 曾明说"刻意不记"）。属产品决策。

## 四、已确认完成、不要重复施工

- R9 的词级链路（corrections → 错题本 → 复习种子）：`ChatFeedbackManager:299-321`
- R10 的正常结束写 `duel_stats`：`DuelManager:751`
- R2 的推荐词进复习队列：`ChatFeedbackManager:323-330`

## 五、只能推断、未验证的

- "注入目标词能提升学习效果"——教学法假设，无数据
- 玩家 1h 内开新会话导致同词连考的实际频率——取决于玩家行为，未统计
- AI 纠错中词级 vs 句级的真实比例——未跑真实样本（但 prompt 要求报 grammar/politeness，句级占多数是高置信推断）
