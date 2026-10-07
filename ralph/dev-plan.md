# 开发计划 — v1.0.7 迭代开发

## 项目信息
- PRD：`ralph/prd.json`
- 主类：`com.letslearnenglish.minecraftplugin.LetsLearnEnglish`
- 总 Story 数：13 (3 Bug + 10 Feature)
- 创建时间：2026-06-19

## 总体状态：全部完成

经过代码审查，PRD 中的 13 个条目均已实现。所有 Java 类已创建，数据库 schema 已更新，config.yml 配置项已添加，i18n 消息键已写入 zh.yml/en.yml。

---

## 任务清单

### 阶段零：紧急 Bug 修复

| # | Story ID | 标题 | 涉及文件 | 依赖 | 状态 |
|---|---------|------|---------|------|------|
| 1 | BUG-001 | WordBank 未解析 reward 字段 | Word.java, WordBank.java | 无 | ✅ |
| 2 | BUG-002 | ReviewScheduler 复习完所有间隔后永久排除单词 | ReviewScheduler.java | 无 | ✅ |
| 3 | BUG-003 | AIChatManager 上下文裁剪可能误删 System Prompt | AIChatManager.java | 无 | ✅ |

### 阶段一：高优先级功能

| # | Story ID | 标题 | 涉及文件 | 依赖 | 状态 |
|---|---------|------|---------|------|------|
| 4 | FEAT-001 | 排行榜系统持久化 | Leaderboard.java, LeaderboardGUI.java, DatabaseManager.java, LetsLearnEnglish.java | 无 | ✅ |
| 5 | FEAT-002 | 强化每日挑战（关联真实词库） | DailyChallengeManager.java, DailyChallengeGUI.java, ChallengeCompleteGUI.java, DatabaseManager.java | 无 | ✅ |
| 6 | FEAT-003 | AI 对话历史回顾 | AIChatManager.java, ChatHistoryGUI.java, ChatHistoryListener.java, ChatHistoryEntry.java, DatabaseManager.java | 无 | ✅ |
| 7 | FEAT-004 | 词库导入导出（管理员工具） | AdminCommandHandler.java, AdminCommand.java, WordBankValidator.java, DataExporter.java | 无 | ✅ |

### 阶段二：中优先级功能

| # | Story ID | 标题 | 涉及文件 | 依赖 | 状态 |
|---|---------|------|---------|------|------|
| 8 | FEAT-005 | 学习统计网页面板（HTTP 服务） | StatisticsHttpServer.java, LetsLearnEnglish.java | 无 | ✅ |
| 9 | FEAT-006 | 单词发音辅助（音标 + TTS） | TtsManager.java, Word.java, WordLearningGUI.java | BUG-001 (Word 构造器) | ✅ |
| 10 | FEAT-007 | PvP 单词实时对决（双人抢答） | DuelManager.java, DuelRoom.java, DuelGUI.java, DuelGUIListener.java, DuelListener.java, LearnCommand.java | 无 | ✅ |
| 11 | FEAT-008 | 错题本 / 难点词标记 | WrongBookGUI.java, WrongBookListener.java, DifficultWord.java, PlayerDataStore.java, DatabaseManager.java, WordLearningManager.java | 无 | ✅ |

### 阶段三：低优先级功能

| # | Story ID | 标题 | 涉及文件 | 依赖 | 状态 |
|---|---------|------|---------|------|------|
| 12 | FEAT-009 | 每日签到系统 | CheckInManager.java, CheckInData.java, LearnCommand.java, DatabaseManager.java | 无 | ✅ |
| 13 | FEAT-010 | 自定义 AI 场景分享 | ScenarioCodec.java, LearnCommand.java, AIChatManager.java | 无 | ✅ |

---

## 资源文件状态

| 文件 | 状态 | 备注 |
|------|------|------|
| `schema.sql` | ✅ | 包含所有 4 张新表（daily_challenges, ai_chat_history, difficult_words, checkin_data）及 daily_challenge_words |
| `config.yml` | ✅ | config-version: 2，所有 PRD 要求的配置项已添加 |
| `messages/zh.yml` | ✅ | config-version: 5，所有新功能的 i18n 键已写入 |
| `messages/en.yml` | ✅ | config-version: 5，与 zh.yml 对应 |

## 数据库表汇总

| 表名 | 用途 | 状态 |
|------|------|------|
| `lle_player_data` | 玩家基础数据 | ✅ 已有 |
| `lle_word_progress` | 单词学习进度 | ✅ 已有 |
| `lle_achievements` | 成就记录 | ✅ 已有 |
| `lle_learning_log` | 学习日志 | ✅ 已有 |
| `lle_player_sessions` | 每日会话记录 | ✅ 已有 |
| `lle_player_profiles` | 玩家偏好设置 | ✅ 已有 |
| `lle_player_achievements` | 玩家成就 | ✅ 已有 |
| `lle_daily_challenges` | 每日行为挑战 | ✅ v1.0.7 新增 |
| `lle_daily_challenge_words` | 每日单词挑战 | ✅ v1.0.7 新增 |
| `lle_ai_chat_history` | AI 对话历史 | ✅ v1.0.7 新增 |
| `lle_difficult_words` | 错题本 | ✅ v1.0.7 新增 |
| `lle_checkin_data` | 每日签到 | ✅ v1.0.7 新增 |
