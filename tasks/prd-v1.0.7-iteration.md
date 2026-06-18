# PRD: LetsLearnEnglish v1.0.7 迭代开发

> **创建日期:** 2026-06-18
> **当前版本:** 1.0.6-SNAPSHOT → 1.0.7-SNAPSHOT
> **目标平台:** Paper/Spigot 1.21.4 | Java 21
> **构建工具:** Maven (`mvn clean package`)
> **总条目数:** 13 (3 Bug 修复 + 10 新功能)  <!-- 注：原文头部写 3+7 但实际列出 3+10，已修正 -->

---

## 项目背景

LetsLearnEnglish 是一款 Paper/Spigot 1.21.4 的游戏化英语学习插件。现有架构采用 api/config/data/core/gui/listener/command/util 分层结构。数据层通过 DatabaseManager 支持 SQLite/MySQL 双后端，PlayerDataStore 负责缓存层。当前版本 1.0.6-SNAPSHOT，本次迭代目标升级至 1.0.7。

---

## 硬性约束

| # | 约束 | 说明 |
|---|------|------|
| C1 | 数据库操作必须通过 DatabaseManager | 新增表需同步更新 `src/main/resources/schema.sql`（本次新建） |
| C2 | GUI 必须复用 GUIUtils 和 GuiNavigationManager | 导航栈一致性 |
| C3 | 玩家可见文字从 zh.yml/en.yml 读取 | 严禁硬编码字符串 |
| C4 | 新增配置项必须带 config-version 字段 | 实现 ConfigManager 版本迁移 |
| C5 | 每个任务完成后编译通过 | `mvn clean package` + 更新 CHANGELOG.md |
| C6 | 全程基于 master 分支 | 每个大阶段完成后 commit |
| C7 | 新增包归属 | 所有新类必须放入 api/config/data/core/gui/listener/command/util 对应包 |

---

## 阶段零：紧急 Bug 修复 (最高优先级, 顺序执行)

### Bug 1：WordBank 未解析 reward 字段

**文件:** `WordBank.java` 第 78-79 行
**问题:** 使用了 8 参数构造器创建 Word，忽略了 YAML 中的 reward 字段
**修复方案:**
- 改为 9 参数构造器（增加 `reward` 参数）
- 同步更新 `Word.equals()` 和 `Word.hashCode()` 包含 reward 字段
- 检查所有调用 Word 构造器的地方，确保传入 reward 值

**验收标准:**
- [ ] YAML 中为单词设置的 reward 能在答题后正确发放
- [ ] `Word.equals()` 和 `hashCode()` 包含 reward 字段
- [ ] 编译通过

---

### Bug 2：ReviewScheduler 复习完所有间隔后永久排除单词

**文件:** `ReviewScheduler.java` 第 65-67 行
**问题:** `reviewCount >= reviewIntervalsSize` 时直接 `continue`，导致单词永不出现
**修复方案:**
- 将完成所有间隔的单词标记为 `MASTERED`
- 以 30 天为周期重新加入低频复习池（每 30 天复习一次）
- 30 天周期作为可配置项放入 `config.yml`

**验收标准:**
- [ ] 已掌握所有间隔的单词在 30 天后会再次出现在复习列表
- [ ] MASTERED 状态正确写入数据库
- [ ] 30 天周期可在 config.yml 中配置
- [ ] 编译通过

---

### Bug 3：AIChatManager 上下文裁剪可能误删 System Prompt

**文件:** `AIChatManager.java` 第 435-438 行
**问题:** 使用 Iterator 按对删除，消息数为奇数时可能删除 System Prompt
**修复方案:**
- 重构为按 token 数精确裁剪
- System Prompt 永远固定在消息列表第一个位置且不可删除
- 裁剪时只删除 user/assistant 交替对，从最早的消息开始移除

**验收标准:**
- [ ] 上下文超长时 System Prompt 永远不会被移除
- [ ] 裁剪后消息列表仍以 user/assistant 交替格式正确
- [ ] 编译通过

---

## 阶段一：高优先级功能 (4 个, 可并行)

### 功能 1：排行榜系统持久化

**命令:** `/le leaderboard` (支持金币榜 / 单词数榜 / 连击榜)
**现状:** Leaderboard 目前只存在内存中，重启丢失

**需求:**
- 在 DatabaseManager 中新增 leaderboard 相关查询方法
- 启动时从数据库加载 Top 10 到内存缓存
- 新建 `LeaderboardGUI`（Tab 切换三个榜单）
- 点击玩家头像可跳转其个人主页（通过 GuiNavigationManager）
- 添加 5 分钟定时缓存刷新，避免高频查库

**验收标准:**
- [ ] 服务器重启后排行榜数据恢复
- [ ] Tab 切换三种榜单正常
- [ ] 点击头像正确跳转玩家主页
- [ ] 5 分钟缓存刷新不产生数据库压力
- [ ] 编译通过

---

### 功能 2：强化每日挑战（关联真实词库）

**现状:** `DailyChallengeManager` 骨架存在但未真正关联词库

**需求:**
- 每日 0 点从 WordBank 中随机抽取 N 个单词（数量在 `config.yml` 可配置，默认 5 个）
- 新建 `daily_challenges` 表记录每个玩家今日完成进度（已答对几个）
- 全部完成后弹出 `ChallengeCompleteGUI`，调用 RewardEngine 发放额外金币奖励
- 支持 `/le challenge` 命令查看今日进度

**数据库变更:**
```sql
CREATE TABLE daily_challenges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    challenge_date DATE NOT NULL,
    word_id INTEGER NOT NULL,
    completed BOOLEAN DEFAULT FALSE,
    completed_at TIMESTAMP NULL,
    UNIQUE(player_uuid, challenge_date, word_id)
);
```

**验收标准:**
- [ ] 每日挑战与真实词库联动
- [ ] 完成全部挑战后获得奖励
- [ ] `/le challenge` 可查看今日进度
- [ ] 跨天后自动刷新新一批单词
- [ ] 编译通过

---

### 功能 3：AI 对话历史回顾

**命令:** `/le history` 打开 GUI

**需求:**
- 新建 `ai_chat_history` 表（player_uuid, session_id, role, content, timestamp）
- 修改 AIChatManager，每次 AI 对话完成后异步写入数据库
- 新建 `ChatHistoryGUI`，分页展示历史记录（每页 10 条）
- 点击可展开查看完整上下文（通过 GuiNavigationManager 导航）
- 仅保留最近 30 天数据，超期自动清理（插件启动时执行一次清理任务）

**数据库变更:**
```sql
CREATE TABLE ai_chat_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_player_time (player_uuid, timestamp),
    INDEX idx_session (session_id)
);
```

**验收标准:**
- [ ] 玩家可查看自己过往的 AI 对话内容
- [ ] 分页展示正常（每页 10 条）
- [ ] 展开查看完整上下文
- [ ] 30 天前数据自动清理
- [ ] 编译通过

---

### 功能 4：词库导入导出（管理员工具）

**命令:**
- `/lea export <词库名>` — 导出为 YAML
- `/lea import <文件名>` — 校验并导入

**需求:**
- 导出：将指定词库导出为 YAML 文件，保存到 `plugins/LLEP/exports/` 目录，在控制台输出文件路径
- 导入：校验 YAML 格式合法性，合并至 WordBank 并热刷新缓存
- **合并策略：跳过重复单词（保留现有），不覆盖**
- 新建 `WordBankValidator` 类，防止恶意输入破坏 YAML 结构（同时解决原报告中"缺少输入验证"问题）
- 在 `ai_scenarios.yml` 的同级目录管理导入导出 YAML 文件

**验收标准:**
- [ ] 导出的 YAML 可通过 `/lea import` 完整导入且功能正常
- [ ] 重复单词被跳过，不产生数据冲突
- [ ] 非法 YAML 被拒绝并给出明确错误提示
- [ ] 控制台输出文件路径
- [ ] 编译通过

---

## 阶段二：中优先级功能 (4 个, 可并行)

### 功能 5：学习统计网页面板（HTTP 服务）

**需求:**
- 新建 `StatisticsHttpServer`，在可配置端口（默认 8080）启动 HTTP 服务
- 插件 `onDisable` 时优雅关闭（调用 `server.stop()` 并 await 终止）
- 提供 `/api/stats` 接口返回 JSON：总单词数、今日活跃玩家数、总学习次数、金币总产出/消耗
- 内嵌 HTML 管理页面（使用 ECharts CDN），展示近 7 天学习趋势折线图
- **安全：IP 白名单（config.yml）+ HTTP Basic Auth 双重保护**
  - IP 白名单：只有白名单内的 IP 能访问
  - HTTP Basic Auth：用户名/密码在 `config.yml` 中配置
  - 白名单外的 IP 直接返回 403

**配置项 (config.yml):**
```yaml
statistics-server:
  enabled: false
  port: 8080
  ip-whitelist:
    - "127.0.0.1"
    - "192.168.1.0/24"
  auth:
    username: "admin"
    password: "changeme"
```

**验收标准:**
- [ ] 管理员浏览器访问 `http://服务器IP:8080` 可查看可视化数据
- [ ] `/api/stats` 返回正确 JSON 数据
- [ ] IP 不在白名单时返回 403
- [ ] HTTP Basic Auth 认证失败返回 401
- [ ] 插件卸载时 HTTP 服务正确关闭
- [ ] 编译通过

---

### 功能 6：单词发音辅助（音标 + TTS）

**需求:**
- 在 `Word` 类增加 `phonetic` 字段（YAML 可选配置），GUI 中显示音标
- 在 `WordLearningGUI` 中添加"发音"按钮（喇叭图标）
- 新建 `TtsManager`，调用免费 TTS API（优先微软 Edge TTS，VoiceRSS 作为备选）
- 异步生成音频，缓存到 `plugins/LLEP/audio/` 目录
- 使用 SoundManager 播放音频
- 缓存有效期 7 天，过期重新生成
- 考虑到 Minecraft 客户端音频限制，播放方案可以降级为 ResourcePack 方案或使用 NOTE_BLOCK 模拟

**Word 类变更:**
```java
// 新增字段
private String phonetic; // 音标，如 "/ˈeksəsaɪz/"

// 更新构造器（10 参数：增加 phonetic）
// 更新 equals() 和 hashCode()
```

**验收标准:**
- [ ] 点击发音按钮，播放单词的正确读音
- [ ] GUI 中正确显示音标
- [ ] 音频缓存 7 天后自动重新生成
- [ ] 无 TTS API 时优雅降级（显示音标但不播放）
- [ ] 编译通过

---

### 功能 7：PvP 单词实时对决（双人抢答）

**命令:**
- `/le duel <玩家名>` — 发起挑战
- `/le duel accept` — 接受挑战
- `/le duel quit` — 退出对局

**需求:**
- 新建 `DuelManager`（单例），维护匹配池和 DuelRoom 状态（使用 `ConcurrentHashMap`）
- 新建 `DuelGUI`，显示在线玩家列表，点击发起挑战（对方收到确认弹窗）
- **对局逻辑：**
  - 系统从 WordBank 随机出题，两人抢答
  - 答对得 1 分，答错对方得 1 分
  - 先得 5 分者胜
- 对局期间使用 Bukkit Scoreboard 显示实时比分
- 对局结束后调用 RewardEngine 发放奖励（胜者多，败者少）
- **掉线处理：给 30 秒重连宽限期，超时判负**
  - 掉线时通过 Scoreboard 向对方显示倒计时
  - 30 秒内重连恢复对局，超时或对局结束前未重连则判负

**DuelManager 核心结构:**
```java
public class DuelManager {
    private final Map<UUID, DuelRoom> activeDuels; // player_uuid -> room
    private final Set<UUID> matchmakingPool;         // waiting players
    private final Map<UUID, Long> disconnectTimers;   // disconnect grace period
}
```

**验收标准:**
- [ ] 两名玩家可正常匹配并完成完整对局
- [ ] 奖励正确发放（胜者多，败者少）
- [ ] Scoreboard 实时显示比分
- [ ] 掉线 30 秒内重连可恢复对局
- [ ] 超时未重连自动判负
- [ ] `/le duel quit` 正常退出
- [ ] 编译通过

---

### 功能 8：错题本 / 难点词标记

**命令:** `/le wrong` 打开错题本 GUI

**需求:**
- 在 PlayerDataStore 中为每个玩家新增 `Set<String> difficultWords`
- 在 WordLearningManager 中，答错累计 3 次的单词自动加入错题本
- 新建 `WrongBookGUI`，展示所有错题
- 支持点击"移除"（移出错题本）
- 支持"重新练习"（进入独立复习模式，不影响艾宾浩斯进度）
- 错题本数据持久化到数据库（新增字段或表）

**数据库变更:**
```sql
CREATE TABLE difficult_words (
    player_uuid VARCHAR(36) NOT NULL,
    word VARCHAR(100) NOT NULL,
    wrong_count INTEGER DEFAULT 0,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_uuid, word)
);
```

**验收标准:**
- [ ] 答错 3 次的单词自动加入错题本
- [ ] 错题本 GUI 正确展示所有错题
- [ ] "移除"功能正常
- [ ] "重新练习"独立于艾宾浩斯复习进度
- [ ] 服务器重启后错题本数据保留
- [ ] 编译通过

---

## 阶段三：低优先级功能 (2 个, 可并行)

### 功能 9：每日签到系统

**命令:** `/le checkin` 进行签到

**需求:**
- 新建 `CheckInManager` 和 `checkin_data` 表
- 连续签到奖励递增：第 1 天 +10 金币, 第 2 天 +20, ..., 第 7 天 +100
- 第 8 天起重置为 +10 金币并重新开始计数
- 断签后 `streak_count` 重置为 0
- 每天仅可签到一次（按自然日判定，使用服务器时区）

**数据库变更:**
```sql
CREATE TABLE checkin_data (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    last_checkin_date DATE NOT NULL,
    streak_count INTEGER DEFAULT 1
);
```

**验收标准:**
- [ ] 跨天签到连续计数正确
- [ ] 断签后 streak_count 重置为 0
- [ ] 第 7 天正确发放 100 金币
- [ ] 第 8 天正确重置为 10 金币
- [ ] 每天仅可签到一次
- [ ] 编译通过

---

### 功能 10：自定义 AI 场景分享

**命令:**
- `/le save_scenario <名称>` — 导出场景码
- `/le load_scenario <Base64编码>` — 导入场景码

**需求:**
- `save_scenario`：将当前 AI 会话的 System Prompt 保存为自定义场景，编码为 Base64 字符串输出给玩家
- `load_scenario`：解码 Base64 并写入 `ai_scenarios.yml`，调用 AIChatManager 热加载，无需重启服务器
- Prompt 长度限制：最大 2000 字符（超出时拒绝保存并提示）
- 场景名称限制：最长 32 字符，仅允许中英文/数字/下划线

**编码格式:**
```
Base64(场景名称 + "\0" + SystemPrompt内容)
```

**验收标准:**
- [ ] 玩家可导出场景码分享给其他人
- [ ] 对方加载后立即可用（无需重启）
- [ ] 超长 Prompt 被拒绝（>2000 字符）
- [ ] 非法 Base64 被拒绝并提示错误
- [ ] 编译通过

---

## 数据库变更汇总

本次迭代新增以下表（写入 `src/main/resources/schema.sql`）：

| 表名 | 所属功能 | 用途 |
|------|---------|------|
| `daily_challenges` | 功能 2 | 每日挑战进度 |
| `ai_chat_history` | 功能 3 | AI 对话历史 |
| `difficult_words` | 功能 8 | 错题本 |
| `checkin_data` | 功能 9 | 每日签到 |

---

## 配置项变更汇总

| 配置路径 | 默认值 | 说明 |
|---------|--------|------|
| `daily-challenge.word-count` | 5 | 每日挑战单词数量 |
| `review.mastered-review-interval-days` | 30 | 已掌握单词低频复习周期 |
| `statistics-server.enabled` | false | HTTP 统计面板开关 |
| `statistics-server.port` | 8080 | HTTP 服务端口 |
| `statistics-server.ip-whitelist` | ["127.0.0.1"] | IP 白名单 |
| `statistics-server.auth.username` | "admin" | Basic Auth 用户名 |
| `statistics-server.auth.password` | "changeme" | Basic Auth 密码 |
| `tts.enabled` | true | TTS 发音开关 |
| `tts.cache-days` | 7 | 音频缓存天数 |
| `scenario.max-prompt-length` | 2000 | 场景 Prompt 最大长度 |

---

## 执行策略

1. **严格按阶段顺序执行：** 阶段零 → 阶段一 → 阶段二 → 阶段三
2. **阶段零内三个 Bug 顺序执行**（可能相互影响）；阶段一/二/三内部各功能可并行开发
3. **每个阶段完成后运行 `mvn clean package`** 确认编译通过，并提交 commit
4. 遇到数据库表结构变更时，同步更新 `src/main/resources/schema.sql`
5. 编译成功后备份 JAR 到 `versions/` 目录

---

## 质量门禁

- 所有新增类必须有对应的包归属（data/core/gui 等）
- 禁止使用已废弃的 Bukkit/Paper API
- 所有异步任务必须正确处理异常，防止线程池泄漏
- 所有玩家数据变更必须经过 PlayerDataStore 缓存层，禁止直接操作数据库
- 新增 i18n key 必须在 zh.yml 和 en.yml 中同时添加
- 数据库操作必须使用 try-with-resources 确保连接正确关闭

---

## 关键决策记录

| 决策 # | 问题 | 选择 |
|--------|------|------|
| D1 | 构建系统 | Maven (`mvn clean package`)，非 Gradle |
| D2 | Schema 管理 | 新建 `src/main/resources/schema.sql` 统一管理 DDL |
| D3 | 词库导入合并 | 跳过重复单词，保留现有 |
| D4 | PvP 掉线处理 | 30 秒重连宽限期，超时判负 |
| D5 | HTTP 面板安全 | IP 白名单 + HTTP Basic Auth 双重保护 |
