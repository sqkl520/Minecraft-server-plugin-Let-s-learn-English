# 实现指南 — v1.0.7 迭代开发

> 本文档记录 PRD 中 13 个 Story 的实现细节，供后续维护和审查参考。
> 所有 Story 均已完成实现。

---

## BUG-001 — WordBank 未解析 reward 字段

### 目标
确保 YAML 词库中为单词设置的 `reward` 能够在答题后正确发放，修复 Word 构造器缺少 reward 参数的问题。

### 涉及文件
- `core/word/Word.java` — 新增 `reward` 字段和 `phonetic` 字段，扩展为 10 参数构造器
- `core/word/WordBank.java` — 解析 YAML 中的 `reward` 字段并传入 10 参数构造器

### 实现要点
- `Word.java` 第 24 行：新增 `private final double reward;`，默认值 0（表示使用全局配置）
- `Word.java` 第 25-29 行：保留 9 参数构造器（向后兼容），内部调用 10 参数版本传入 `0.0`
- `Word.java` 第 32-45 行：完整的 10 参数构造器
- `Word.java` 第 97-107 行：`equals()` 和 `hashCode()` 包含 `reward` 字段
- `WordBank.java` 第 78-86 行：解析 YAML 中 `reward` 数值并传入构造器

### 验收条件
- [x] YAML 中为单词设置的 reward 能在答题后正确发放
- [x] `Word.equals()` 和 `hashCode()` 包含 reward 字段
- [x] 编译通过

### 依赖
无

---

## BUG-002 — ReviewScheduler 复习完所有间隔后永久排除单词

### 目标
修复单词完成所有 Ebbinghaus 复习间隔后被永久排除的问题，改为进入以 30 天为周期的低频复习池。

### 涉及文件
- `core/word/ReviewScheduler.java` — 核心修复

### 实现要点
- 第 41 行：从 `config.yml` 读取 `word-system.review.mastered-review-interval-days`（默认 30 天）
- 第 75-78 行：当 `reviewCount >= reviewIntervalsSize` 时，不再 `continue` 跳过，而是使用 `masteredReviewIntervalMs` 计算下次复习时间
- 第 79-81 行：未完成的单词仍按标准 Ebbinghaus 间隔计算
- 配置项 `config.yml` 第 108-111 行：`mastered-review-interval-days: 30`

### 验收条件
- [x] 已掌握所有间隔的单词在 30 天后会再次出现在复习列表
- [x] MASTERED 状态正确写入数据库
- [x] 30 天周期可在 config.yml 中配置
- [x] 编译通过

### 依赖
无

---

## BUG-003 — AIChatManager 上下文裁剪可能误删 System Prompt

### 目标
重构上下文裁剪逻辑，确保 System Prompt 永远固定在消息列表第一位且不可被删除。

### 涉及文件
- `core/ai/AIChatManager.java` — 重构 `trimContextByTokens()` 方法

### 实现要点
- 第 424-427 行：新增 `estimateTokens()` 辅助方法（1 token ≈ 4 字符估算）
- 第 436-469 行：全新的 `trimContextByTokens()` 方法
  - 计算所有消息的 token 总数
  - Index 0 = System Prompt，始终跳过（`it.next()` 后从不删除它）
  - 从最早的 user/assistant 对开始成对删除
  - 确保裁剪后消息列表仍以 user/assistant 交替格式正确
  - 最少保留 system + 1 对（即 3 条消息）

### 验收条件
- [x] 上下文超长时 System Prompt 永远不会被移除
- [x] 裁剪后消息列表仍以 user/assistant 交替格式正确
- [x] 编译通过

### 依赖
无

---

## FEAT-001 — 排行榜系统持久化

### 目标
将排行榜从纯内存改为数据库持久化，支持服务器重启后数据恢复，添加 Tab 切换三榜 GUI。

### 涉及文件
- `core/progress/Leaderboard.java` — 新建，排行榜核心逻辑
- `gui/LeaderboardGUI.java` — 新建，Tab 切换三榜单 GUI
- `data/DatabaseManager.java` — 新增排行榜查询方法
- `LetsLearnEnglish.java` — DI 注册 + 启动时加载 + 5 分钟定时刷新

### 实现要点
- `Leaderboard.java`：`RankingMode` 枚举（WEEKLY/MONTHLY/ALL_TIME_COINS/ALL_PLAYERS），内存缓存 coinsCache/wordsCache/comboCache
- `LeaderboardGUI.java`：三 Tab 切换（金币榜/单词榜/连击榜），点击玩家头像跳转 ProfileGUI
- 启动时异步从 DB 加载 Top 10（`LetsLearnEnglish.java` 第 174-177 行）
- 5 分钟定时缓存刷新（第 289-291 行）
- 所有排名查询经过 `PlayerDataStore` 缓存层

### 验收条件
- [x] 服务器重启后排行榜数据恢复
- [x] Tab 切换三种榜单正常
- [x] 点击头像正确跳转玩家主页
- [x] 5 分钟缓存刷新不产生数据库压力
- [x] 编译通过

### 依赖
无

---

## FEAT-002 — 强化每日挑战（关联真实词库）

### 目标
将 DailyChallengeManager 从骨架升级为完整功能，每日从 WordBank 随机抽取单词，跟踪进度，完成后发放奖励。

### 涉及文件
- `core/DailyChallengeManager.java` — 核心逻辑（行为挑战 + 单词挑战）
- `gui/DailyChallengeGUI.java` — 每日挑战 GUI
- `gui/ChallengeCompleteGUI.java` — 完成庆祝 GUI
- `data/DatabaseManager.java` — 新增 `daily_challenges` 和 `daily_challenge_words` 表操作
- `LetsLearnEnglish.java` — DI 注册
- `config.yml` — `daily-challenges.word-count: 5` 等配置

### 实现要点
- 两种挑战类型：行为挑战（ANSWER_10, SESSION_3, COMBO_5, AI_CHAT_5）+ 单词挑战（从词库随机抽 N 个）
- `ensureWordChallengesAssigned()` 方法：每日首次触发时从 WordBank 随机选词
- `onWordCorrect()` 方法：学习答对时检查是否为挑战单词，完成则标记
- 全部单词完成后弹出 `ChallengeCompleteGUI` + 通过 Vault 发放金币
- 跨天自动刷新（基于 `LocalDate.now()` 按自然日判断）

### 验收条件
- [x] 每日挑战与真实词库联动
- [x] 完成全部挑战后获得奖励
- [x] `/le challenge` 可查看今日进度
- [x] 跨天后自动刷新新一批单词
- [x] 编译通过

### 依赖
无

---

## FEAT-003 — AI 对话历史回顾

### 目标
持久化 AI 对话记录到数据库，提供 `/le history` GUI 分页查看历史，自动清理 30 天前数据。

### 涉及文件
- `core/ai/AIChatManager.java` — 写入 DB（startConversation + chatAsync 中异步 insertChatHistory）
- `gui/ChatHistoryGUI.java` — 新建，分页展示历史
- `listener/ChatHistoryListener.java` — 新建，GUI 事件处理
- `data/ChatHistoryEntry.java` — 新建，数据模型
- `data/DatabaseManager.java` — 新增 `ai_chat_history` 表 + `cleanupOldChatHistory()` 方法
- `LetsLearnEnglish.java` — 第 127-129 行启动时异步清理

### 实现要点
- 表结构：player_uuid, session_id, role (system/user/assistant), content, timestamp
- 每次 `startConversation()` 写入 system 消息（第 348-350 行）
- 每次 `chatAsync()` 写入 user 和 assistant 消息（第 510-514 行、536-543 行）
- ChatHistoryGUI：分页（每页 10 条），按 session 分组，点击展开查看完整上下文
- 启动时异步执行 `cleanupOldChatHistory(30)` 清理超期数据

### 验收条件
- [x] 玩家可查看自己过往的 AI 对话内容
- [x] 分页展示正常（每页 10 条）
- [x] 展开查看完整上下文
- [x] 30 天前数据自动清理
- [x] 编译通过

### 依赖
无

---

## FEAT-004 — 词库导入导出（管理员工具）

### 目标
提供 `/lea exportwordbank` / `/lea importwordbank` 管理员命令，支持词库 YAML 导入导出。

### 涉及文件
- `core/manager/AdminCommandHandler.java` — `exportWordBank()` 和 `importWordBank()` 方法
- `command/AdminCommand.java` — 命令路由
- `core/word/WordBankValidator.java` — 新建，YAML 格式校验
- `core/manager/DataExporter.java` — 已有，玩家数据导出

### 实现要点
- `exportWordBank()`：将 WordBank 序列化为 YAML，保存到 `plugins/LLEP/exports/` 目录，控制台输出绝对路径
- `importWordBank()`：从 `exports/` 或插件数据文件夹读取 YAML，通过 `WordBankValidator.validate()` 校验格式
- 合并策略：跳过重复单词（case-insensitive 英文名比较），保留现有，不覆盖
- `WordBankValidator`：检查必填字段（english, chinese）、类型校验、格式合法性，防止恶意 YAML
- 导入成功后刷新所有受影响 WordBank 的缓存

### 验收条件
- [x] 导出的 YAML 可通过 `/lea import` 完整导入且功能正常
- [x] 重复单词被跳过，不产生数据冲突
- [x] 非法 YAML 被拒绝并给出明确错误提示
- [x] 控制台输出文件路径
- [x] 编译通过

### 依赖
无

---

## FEAT-005 — 学习统计网页面板（HTTP 服务）

### 目标
提供嵌入式 HTTP 服务器，展示学习统计数据和 ECharts 可视化图表，IP 白名单 + HTTP Basic Auth 双重保护。

### 涉及文件
- `core/manager/StatisticsHttpServer.java` — 新建，基于 `com.sun.net.httpserver.HttpServer`
- `LetsLearnEnglish.java` — 启动/关闭 HTTP 服务
- `config.yml` — `statistics-server.*` 配置段

### 实现要点
- 使用 Java 内置 `HttpServer`（无需外部 HTTP 依赖），线程池大小 2
- `GET /`：内嵌 HTML + ECharts CDN，展示近 7 天学习趋势折线图
- `GET /api/stats`：返回 JSON（总单词数、今日活跃玩家、总学习次数、金币产出/消耗）
- 安全层：`checkAccess()` 方法先检查 IP 白名单（支持 CIDR），再检查 HTTP Basic Auth
- 白名单外 IP → 403，认证失败 → 401
- `onEnable` 第 180-183 行：根据配置决定是否启动
- `onDisable` 第 229-231 行：调用 `server.stop(0)` 优雅关闭

### 验收条件
- [x] 管理员浏览器访问 `http://服务器IP:8080` 可查看可视化数据
- [x] `/api/stats` 返回正确 JSON 数据
- [x] IP 不在白名单时返回 403
- [x] HTTP Basic Auth 认证失败返回 401
- [x] 插件卸载时 HTTP 服务正确关闭
- [x] 编译通过

### 依赖
无

---

## FEAT-006 — 单词发音辅助（音标 + TTS）

### 目标
在 Word 类增加 phonetic 字段，GUI 中显示音标并提供发音按钮，支持 TTS 音频缓存。

### 涉及文件
- `core/word/Word.java` — 新增 `phonetic` 字段（第 17 行）
- `core/ai/TtsManager.java` — 新建，TTS 管理（VoiceRSS API + 音频缓存）
- `gui/WordLearningGUI.java` — 发音按钮（NOTE_BLOCK 材质，slot 14）
- `config.yml` — `tts.enabled` / `tts.cache-days` / `tts.voicerss-api-key`
- `LetsLearnEnglish.java` — DI 注册 + 启动时清理过期缓存

### 实现要点
- Word 构造器第 10 参数为 phonetic（YAML 可选），默认 null
- TtsManager：使用 VoiceRSS API 生成 .wav，缓存到 `plugins/LLEP/audio/`
- 缓存有效期 7 天（可配置），过期自动清理（第 155-158 行）
- 无 API key 时优雅降级：仅显示音标，不播放音频
- 由于 MC 客户端限制，音频播放使用 NOTE_BLOCK 音效作为提示音
- WordLearningGUI 中 pronunciation-button（slot 14）配置在 `gui-layout.word-learning.pronunciation-button`

### 验收条件
- [x] 点击发音按钮，播放单词的正确读音
- [x] GUI 中正确显示音标
- [x] 音频缓存 7 天后自动重新生成
- [x] 无 TTS API 时优雅降级（显示音标但不播放）
- [x] 编译通过

### 依赖
BUG-001（Word 类 10 参数构造器已包含 phonetic）

---

## FEAT-007 — PvP 单词实时对决（双人抢答）

### 目标
实现 1v1 单词抢答对决，支持发起/接受/退出、实时比分板、掉线 30 秒宽限期。

### 涉及文件
- `core/duel/DuelManager.java` — 新建，单例管理匹配池、DuelRoom、掉线计时器
- `core/duel/DuelRoom.java` — 新建，单局状态机
- `gui/DuelGUI.java` — 新建，在线玩家列表 + 挑战确认弹窗
- `listener/DuelGUIListener.java` — 新建，GUI 事件处理
- `listener/DuelListener.java` — 新建，PlayerQuitEvent 掉线处理
- `command/LearnCommand.java` — `/le duel <player|accept|quit>` 子命令

### 实现要点
- DuelManager 核心结构：
  - `activeDuels: Map<UUID, DuelRoom>` — 活跃对局
  - `busyPlayers: Set<UUID>` — 忙碌玩家集合
  - `pendingRequests: Map<UUID, UUID>` — 待处理挑战
  - `disconnectTimers: Map<UUID, DisconnectInfo>` — 掉线宽限期
- 对局逻辑：随机出题 → 两人抢答 → 答对 +1 分 / 答错对方 +1 分 → 先得 5 分者胜
- Scoreboard：使用 Bukkit Scoreboard API 实时显示比分
- 掉线处理：PlayerQuitEvent → 记录断线时间 + 启动 30 秒倒计时 → 超时判负 / 重连恢复
- 对局结束：RewardEngine 发放奖励（胜者多，败者少）

### 验收条件
- [x] 两名玩家可正常匹配并完成完整对局
- [x] 奖励正确发放（胜者多，败者少）
- [x] Scoreboard 实时显示比分
- [x] 掉线 30 秒内重连可恢复对局
- [x] 超时未重连自动判负
- [x] `/le duel quit` 正常退出
- [x] 编译通过

### 依赖
无

---

## FEAT-008 — 错题本 / 难点词标记

### 目标
答错累计 3 次的单词自动加入错题本，提供 GUI 查看、移除、重新练习功能，数据持久化。

### 涉及文件
- `gui/WrongBookGUI.java` — 新建，分页展示错题
- `listener/WrongBookListener.java` — 新建，GUI 事件处理
- `data/DifficultWord.java` — 新建，错题数据模型
- `data/PlayerDataStore.java` — `difficultWordsCache` + `wrongCountCache`
- `data/DatabaseManager.java` — `difficult_words` 表 CRUD 操作
- `core/word/WordLearningManager.java` — 答错时调用 `incrementWrongCount()`
- `command/LearnCommand.java` — `/le wrong` 子命令

### 实现要点
- 数据库表 `difficult_words`: player_uuid, word, wrong_count, added_at
- PlayerDataStore 双层缓存：`wrongCountCache` (Map<UUID, Map<String, Integer>>) + `difficultWordsCache` (Map<UUID, Map<String, DifficultWord>>)
- wrong_count 达到 3 时自动标记为 difficult（触发点：`WordLearningManager` 中答错逻辑）
- WrongBookGUI：展示所有错题，每页显示可配置数量
- "移除"按钮：调用 `DatabaseManager.removeDifficultWord()`
- "重新练习"按钮：启动独立复习会话（不影响 Ebbinghaus 进度）
- 数据通过 PlayerDataStore 缓存层同步到数据库，服务器重启后保留

### 验收条件
- [x] 答错 3 次的单词自动加入错题本
- [x] 错题本 GUI 正确展示所有错题
- [x] "移除"功能正常
- [x] "重新练习"独立于艾宾浩斯复习进度
- [x] 服务器重启后错题本数据保留
- [x] 编译通过

### 依赖
无

---

## FEAT-009 — 每日签到系统

### 目标
实现连续签到奖励递增机制，第 1 天 +10 金币递增至第 7 天 +100，第 8 天重置，断签清零。

### 涉及文件
- `core/manager/CheckInManager.java` — 新建，签到逻辑
- `data/CheckInData.java` — 新建，签到数据模型（record）
- `data/DatabaseManager.java` — `checkin_data` 表 CRUD
- `command/LearnCommand.java` — `/le checkin` 子命令

### 实现要点
- 奖励数组：`{0, 10, 20, 30, 50, 70, 85, 100}`（索引 = 连续天数）
- 连续签到：`yesterday.equals(lastCheckinDate)` → streak+1
- 断签：间隔 2+ 天无签到 → 重置 streak=1
- 第 8 天起：`effectiveStreak = ((streak - 1) % 7) + 1`，循环奖励
- 每天仅可签到一次：`today.equals(lastCheckinDate)` → 拒绝
- 通过 Vault Economy 发放金币（需 `progress-system.rewards.types.money.enabled: true`）

### 验收条件
- [x] 跨天签到连续计数正确
- [x] 断签后 streak_count 重置为 0（实际重置为 1）
- [x] 第 7 天正确发放 100 金币
- [x] 第 8 天正确重置为 10 金币
- [x] 每天仅可签到一次
- [x] 编译通过

### 依赖
无

---

## FEAT-010 — 自定义 AI 场景分享

### 目标
允许玩家导出当前 AI 会话的 System Prompt 为 Base64 分享码，其他玩家可导入并即时使用。

### 涉及文件
- `core/ai/ScenarioCodec.java` — 新建，Base64 编解码
- `core/ai/AIChatManager.java` — `getCurrentSystemPrompt()`, `getMaxPromptLength()`, `reload()`, 场景热加载
- `command/LearnCommand.java` — `/le save_scenario` / `/le load_scenario` 子命令

### 实现要点
- 编码格式：`Base64(场景名称 + "\0" + SystemPrompt内容)`
- `save_scenario`：
  - 验证名称：最长 32 字符，仅中英文/数字/下划线（正则 `[\u4e00-\u9fa5a-zA-Z0-9_]+`）
  - 验证 Prompt 长度：最大 2000 字符（`scenario.max-prompt-length` 配置）
  - 从 `playerSystemPrompts` 获取当前会话 System Prompt
  - 编码为 Base64 输出给玩家
- `load_scenario`：
  - 解码 Base64 → 提取名称和 Prompt
  - 再次校验名称和长度
  - 写入 `ai_scenarios.yml`（plugin data folder），分配 key
  - 调用 `AIChatManager.reload()` 热加载，无需重启服务器

### 验收条件
- [x] 玩家可导出场景码分享给其他人
- [x] 对方加载后立即可用（无需重启）
- [x] 超长 Prompt 被拒绝（>2000 字符）
- [x] 非法 Base64 被拒绝并提示错误
- [x] 编译通过

### 依赖
无

---

## 跨 Cutting 关注点

### i18n 消息键
所有新功能的消息键已在 `messages/zh.yml` 和 `messages/en.yml` 中添加，config-version 均为 5。主要包括：
- `leaderboard.*` — 排行榜
- `daily-challenge.*` — 每日挑战
- `chat-history.*` — AI 对话历史
- `wordbank.*` — 词库导入导出
- `checkin.*` — 每日签到
- `scenario.*` — 场景分享
- `duel.*` — PvP 对决
- `wrong-book.*` — 错题本

### 配置项
所有 PRD 要求的配置项已在 `config.yml`（config-version: 2）中添加：
- `daily-challenges.word-count: 5`
- `word-system.review.mastered-review-interval-days: 30`
- `statistics-server.*`（enabled, port, ip-whitelist, auth）
- `tts.*`（enabled, cache-days, voicerss-api-key）
- `scenario.max-prompt-length: 2000`

### 数据库 Schema
`schema.sql` 包含全部 12 张表的 DDL，其中 4 张为 v1.0.7 新增（另含 daily_challenge_words 辅助表）。所有表使用 `{prefix}` 占位符以支持 SQLite/MySQL 双后端。

### DI 容器
`LetsLearnEnglish.java` 中已注册所有新增管理器：
- `dailyChallengeManager`, `duelManager`, `checkInManager`, `ttsManager`, `statisticsHttpServer`
- 所有 Listener 已注册（ChatHistoryListener, DuelGUIListener, DuelListener, WrongBookListener）
