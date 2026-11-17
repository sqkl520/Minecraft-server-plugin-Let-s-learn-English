[English](README-EN.md) | 简体中文

<div align="center">

# Let's Learn English!

**在 Minecraft 服务器里学英语 —— 玩家不用装任何客户端 mod，服主装一个插件就能开课**

答题学词 → 间隔复习 → AI 情景对话 → 学习报告，四件事都在游戏内完成

![MC Version](https://img.shields.io/badge/Minecraft-1.21.4%20~%201.21.11%20%7C%2026.1%2B-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21%2B%20%7C%2025%2B%20(mc26)-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Paper-blue?style=flat-square)
![Words](https://img.shields.io/badge/word%20bank-1357%20words-success?style=flat-square)
![Tests](https://img.shields.io/badge/tests-1341%20passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0--only-blue?style=flat-square)

**[⬇️ 下载最新版](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases)** ·
[部署文档](DEPLOYMENT_WIKI.md) ·
[问题反馈](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/issues)

</div>

> ⚠️ **一个人维护的开源项目，仍在持续迭代中**
>
> 有问题、有想法都欢迎提 [Issues](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/issues)；
> 觉得有用就 Star 一下喵 ⭐
>
> **对外可下载的包以 [Releases 页](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases) 为准。**
> 本 README 不单独维护版本号 —— 与 Releases 页对不上时一律以 Releases 页为准。

---

## ✨ 它是什么

市面上的 Minecraft「学英语插件」通常停在**刷词卡**：给一个词、选一个意思、加个分。
这能让学生记住单词，但记不住**怎么用**，也留不住人 —— 一周后就没谁再打开了。

这个插件把四件事接成一条链：

| 步骤 | 做什么 | 为什么重要 |
|---|---|---|
| **1. 答题学词** | GUI 显示中文 → 关掉 GUI → 在聊天栏倒计时拼英文 | 有压力、有连击，不是无脑点选 |
| **2. 间隔复习** | 按艾宾浩斯曲线自动排期，答错的词会重新出现 | 记忆曲线，不是学完就丢 |
| **3. AI 情景对话** | 用刚学的词跟 AI 演一段对话，结束给语法纠错 | 从「认识」到「会用」 |
| **4. 学习报告** | `/lea report` 出可打印报告，含易错词与进步曲线 | 老师/家长看得见结果 |

区别在**第 3 步和第 4 步**：多数 MC 英语插件只有第 1 步。

---

## 🚀 快速开始

### 1. 按你的服务端版本选 JAR

**下错包装不上。** 本项目发布两个包，源码相同、编译目标不同：

| 你的服务端 | 下载这个 | 需要 |
|---|---|---|
| **Paper 1.21.4 – 1.21.11** | `Let.s.Learn.English.-<版本>.jar` | **Java 21+** |
| **Paper 26.1+**（26.2 / 26.3 / 26.4…） | `Let.s.Learn.English.-<版本>-mc26.jar` | **Java 25+** |

> 为什么是两个包：Paper 从 26.x 起要求 Java 25，用 Java 21 编译的 class 在 26.x 上加载不了。
> 两个包代码完全一样，只是编译目标不同。在 26.x 上装普通包、或在 1.21.x 上装 `-mc26` 包都会出问题。

### 2. 装上去

```bash
# 把 JAR 放进服务端插件目录
cp Let.s.Learn.English.-1.0.7.jar /path/to/server/plugins/

# 重启服务端（不要用 /reload）
```

首次启动会在 `plugins/LetsLearnEnglish/` 下自动生成配置文件、词库、语言文件和数据库。

### 3. 确认装对了

游戏内执行：

```
/lea info
```

会打印版本、服务端版本兼容性、数据库类型。如果版本那一行写着 `26.x detected`，
说明你在 26.x 服务端上装了普通包 —— 换成 `-mc26` 那个。

玩家输入 `/le` 打开主菜单。

### 4. （可选）开 AI 对话

首次启动**不会**自动生成 `ai_config.yml`，需要手动创建并填入 DeepSeek API 密钥：

```bash
# 文件路径：plugins/LetsLearnEnglish/ai_config.yml
```

```yaml
api-key: "sk-你的密钥"
```

改完用 `/lea reload` 热重载，不用重启。没配置时控制台会告警 `ai_config.yml not found!`，
AI 对话不可用，**其余功能不受影响**。

---

## ❓ 常见问题

| 问题 | 解法 |
|---|---|
| 装上了但 `/le` 没反应 | 先 `/lea info` 看插件是否启用；再查 `config.yml` 的 `general.enabled`（多子服时**生存服/PVP 服**常被设成 `false`）。详见 [WIKI 5.1](DEPLOYMENT_WIKI.md) |
| 提示版本不兼容 / 报 `Unsupported class file major version` | 你装了普通包但要跑 26.x。换成 `-mc26` 那个包，并把服务端升到 Java 25 |
| AI 对话一直不回话 | 99% 是 `ai_config.yml` 缺失或密钥无效。看控制台有没有 `ai_config.yml not found!` / `Invalid API key`；补好后 `/lea reload`。详见 [WIKI 5.8.1](DEPLOYMENT_WIKI.md) |
| 多子服时签到能领两次 / 金币翻倍 | **JVM 时区不一致**。插件按 `yyyy-MM-dd` 纯日期记账，两个子服日界不同就各算各的。必须在启动参数里钉死（写在 `-jar` **之前**），见下方折叠段 |
| 改完配置 `/lea reload` 没生效 | 有些键是**启动快照**，reload 不重排。清单见下方折叠段 |
| 玩家数据存在哪？怎么备份 | `plugins/LetsLearnEnglish/playerdata.db`（SQLite）。插件自带定时备份到 `backups/`，默认留 7 份。手动备份注意 WAL：见下方折叠段 |
| 数据库文件只涨不缩 | SQLite 的 `DELETE` 只标页可复用，不还磁盘。需要手动 `VACUUM`，见下方折叠段 |
| 词库能改吗？能加自己的词吗 | 能。改 `plugins/LetsLearnEnglish/words/*.yml` 后 `/lea reload`；也可用 `/lea importwordbank` 导入。模板见 [`words/README.md`](src/main/resources/words/README.md) |
| 支持 Spigot / Purpur / Folia 吗 | **只保证 Paper**。插件用了 Paper 专属 API（Adventure 组件、`Server#getMinecraftVersion`），Spigot 上没有 |
| 要不要装 Vault / PlaceholderAPI | 都是可选。Vault 用于金币奖励，PlaceholderAPI 用于 `%lle_*%` 占位符。不装也能正常玩 |

---

## 📦 功能特性

### 学习核心

- **单词学习答题** —— GUI 显示中文词义 → 关闭 GUI → 聊天栏倒计时拼写英文单词，三种模式（choice / spelling / fill_blank）
- **回合制 Combo 连胜** —— 连续答对产生递增倍率（1.0x → 3.0x），答错或超时重置
- **艾宾浩斯间隔复习** —— 按遗忘曲线自动安排复习计划（1 小时 ~ 168 小时），答错的词会重新出现
- **错题本** —— 答错 3 次自动收录，支持独立复习和移除
- **新手引导线** —— 首次进服发放《新手引导手册》：选词库 → 3 词迷你会话 → 打开个人主页；`/le guide` 随时补领，老玩家自动跳过
- **三档词库 1357 词** —— beginner 487 / intermediate 355 / advanced 515，按 NGSL 词频排序（对人教版初中课标覆盖率 85%）

### AI 对话

- **AI 情景对话** —— 基于 DeepSeek API，6 个内置场景（村庄交易 / 附魔台 / 下界探险 / 建筑 / 餐厅 / 自由聊天），3 档难度
- **会话小结与点评** —— 对话结束自动生成本地统计小结，达标会话异步追加 AI 总评、语法纠错与推荐词；`/le history` 可回看
- **自定义场景分享** —— Base64 编码分享自定义场景，导入为个人场景（每人上限 30 个）
- **个人场景管理 GUI** —— 图形化查看/删除个人场景，行内二次确认
- **思考模式可控** —— 针对 DeepSeek V4 系列默认思考模式的适配开关（`thinking-mode` / `reasoning-effort`），默认一个字节都不多发

### 社交与竞争

- **PvP 单词对决** —— 双人实时抢答，先得 5 分者胜，支持掉线重连
- **英语广场** —— 学习动态流（完成会话/解锁成就/签到自动生成，同类当日合并）+ 周榜/月榜/总榜 + 玩家列表
- **排行榜** —— 金币榜 / 单词榜 / 连击榜 / 赛季榜，数据库持久化
- **赛季系统**（默认关闭） —— 固定周期赛季，按名次（top1 / top3 / top10 / 参与奖）结算金币，支持广播与离线补发
- **班级系统**（默认关闭） —— 管理员/老师建班、加人（一人一班），玩家查看班级与班级榜
- **22 个成就** —— 枚举定义 + 双语名称，可在 `achievements-config.yml` 里逐个开关

### 运营与管理

- **每日单词挑战 / 每日签到** —— 挑战完成后发奖；签到连续天数递增金币，断签重置
- **家长/老师学习报告** —— `/lea report` 生成纯文本报告（本期概览 / 每日明细 / 易错词 / 累计成果 / 给家长的话），支持单人 + 全服批量对比
- **学习统计 HTTP 仪表盘** —— 基于 ECharts 的 Web 面板，IP 白名单（含 CIDR）+ HTTP Basic Auth 双层防护
- **模块级开关（9 个）** —— word-system / ai-chat / duel / checkin / daily-challenges / leaderboard / achievements / rewards + progress-system 伞开关；统一「入口隐藏 + 事件静默 + 数据永不删」三层语义，`/lea reload` 即时生效
- **多语言** —— 中英文界面切换（主菜单内二次确认），可自定义消息前缀
- **中文全注释配置** —— `config.yml` 836 行，每个键都写明行为和副作用

### 运维

- **数据库自动备份** —— 定时备份，保留最近 7 份，用 `VACUUM INTO` 生成快照
- **热重载** —— `/lea reload` 覆盖大部分配置（少数启动快照键需重启，见折叠段）
- **跨服支持** —— Velocity / BungeeCord 代理网络，入站消息可选 HMAC-SHA256 签名校验
- **版本更新检测** —— 启动时检查 GitHub Releases，OP 进服通知
- **发音辅助** —— 音标显示 + 音符盒提示音（当前版本不播放真实语音，见下方折叠段）

---

## 🎮 命令

### 玩家命令 `/le`（别名 `/learn`、`/english`）

| 命令 | 说明 |
|---|---|
| `/le menu` | 打开主菜单 |
| `/le word [难度] [模式]` | 开始单词学习（难度：beginner / intermediate / advanced；模式：choice / spelling / fill_blank） |
| `/le review` | 复习待复习单词 |
| `/le wrong` | 查看错题本 |
| `/le guide` | 查看新手引导进度 / 重新领取引导手册 |
| `/le dialogue [场景]` | 开始 AI 情景对话 |
| `/le history` | 查看 AI 对话历史（详情页含会话小结与 AI 点评） |
| `/le save_scenario <名称>` | 导出当前 AI 场景为分享码 |
| `/le load_scenario <编码>` | 导入分享的场景码（保存为个人场景） |
| `/le stats` | 查看学习统计 |
| `/le progress` | 查看个人主页 |
| `/le profile [玩家名]` | 查看个人资料 |
| `/le leaderboard` | 查看排行榜（金币榜 / 单词榜 / 连击榜 / 赛季榜） |
| `/le challenge` | 查看今日挑战进度 |
| `/le checkin` | 每日签到获取金币 |
| `/le duel <玩家名>` | 发起单词对决（`accept` 接受 / `quit` 退出 / `gui` 重开界面） |
| `/le square [模式]` | 进入英语广场（feed 动态 / weekly 周榜 / monthly 月榜 / alltime 总榜 / all 玩家列表） |
| `/le season` | 查看当前赛季、剩余时间与你的排名 |
| `/le class` | 查看我的班级与班级榜 |
| `/le help` | 查看帮助 |

### 管理员命令 `/lea`（别名 `/learnadmin`）

| 命令 | 说明 |
|---|---|
| `/lea reload` | 热重载配置、AI 段、词库、语言文件、音效缓存、统计服务（少数启动快照键需重启） |
| `/lea info` | 查看插件信息（版本 / 兼容性 / 数据库类型） |
| `/lea diagnose` | 自诊断（配置、数据库、依赖、权限） |
| `/lea report <玩家\|all> [week\|lastweek\|<N>d]` | 生成家长/老师学习报告（TXT，存至 `exports/reports/`） |
| `/lea reset <玩家名>` | 重置玩家学习数据（两段式确认：先提示，再执行 `confirm` 生效） |
| `/lea export` | 导出玩家数据 |
| `/lea exportwordbank <难度>` | 导出单词库至 YAML |
| `/lea importwordbank <文件名.yml>` | 从 `exports/` 目录导入词库 |
| `/lea class create\|delete\|add\|remove\|list` | 班级管理（需启用班级系统） |
| `/lea broadcast <消息>` | 通过代理向所有子服广播（需 Velocity / BungeeCord） |
| `/lea duel cancel <玩家名>` | 强制解散该玩家的单词对决（不判胜负） |
| `/lea ignore-update` | 忽略本次更新提醒 |
| `/lea help` | 查看管理帮助 |

### 权限节点

插件只注册 **4 个**权限节点，全部默认仅 OP 拥有。

**玩家侧功能绝大多数不需要权限** —— `/le menu`、`/le word`、`/le review`、`/le square`、
`/le profile`、`/le wrong`、`/le checkin`、`/le duel`、`/le history`、`/le season` 等
由 `config.yml` 的**模块开关**控制，不看权限。

**唯一例外是场景导入** —— `/le load_scenario` 与个人场景管理页需要 `letslearnenglish.scenario.import`，
因为它们会把外部内容写进服主的场景列表，需要与普通玩法区分授权。

| 节点 | 默认 | 用途 |
|---|:---:|---|
| `letslearnenglish.admin` | op | `/lea` 全部子命令，以及场景管理页的删除入口 |
| `letslearnenglish.report` | op | **仅** `/lea report` —— 可单独授予老师/家长代表，而不必给全部管理权限 |
| `letslearnenglish.ai.reload` | op | **仅**重载 AI 配置（`ai_config.yml` + `ai_scenarios.yml`），可单独授予内容维护者 |
| `letslearnenglish.scenario.import` | op | 场景导入与个人场景管理页的查看/删除 |

> 注意：`letslearnenglish.ai.reload` 与 `/lea reload` **不等价** —— 前者只刷 AI 段。
> 若你要授予「能改 API key」的人，给这个就够，不必给 `admin`。

---

## ⚙️ 配置

主要配置项（`config.yml`，全中文注释）：

```yaml
general:
  enabled: true           # 子服总开关（多子服时生存服/PVP 服常设 false）
  language: "zh"          # 默认语言 (zh/en)
  check-updates: true     # 启动时检查更新

prefix:
  zh: "&8[&b英语学习&8] &f"
  en: "&8[&bEnglish&8] &f"

word-system:
  enabled: true           # 模块开关（入口隐藏 + 事件静默 + 数据保留）
  words-per-session: 10   # 每轮单词数
  answer-timeout: 30      # 答题超时秒数

daily-reminder:
  enabled: true
  on-join: true           # 进服时发送提醒
  reminder-hours: [9, 14, 20]
```

完整配置段：`general` / `prefix` / `database`（SQLite / MySQL）/ `word-system` /
`onboarding` / `duel` / `ai-chat` / `scenario` / `money` / `progress-system` /
`gui` + `gui-layout` / `feedback` / `achievement-system` / `proxy` /
`daily-reminder` / `daily-challenges` / `checkin` / `tts` / `statistics-server` /
`report` / `social` + `english-square` / `season` / `class-system`

### 需要重启服务器的键

以下键在启动时被快照或用于排定周期任务，`/lea reload` **不会**重排：

`general.auto-save-interval` · `general.backup.*` · `general.check-updates` ·
`daily-reminder.reminder-hours` · `tts.enabled` / `tts.cache-days` / `tts.voicerss-api-key` ·
`progress-system.leaderboard.update-interval` · `word-system.review.intervals` ·
`mastered-review-interval-days` · `database.*`

其余键（含全部模块开关 `enabled`、`ai-chat.*`、语言文件、词库、`gui-layout`、成就开关、
统计仪表盘）均支持 `/lea reload` 即时生效。详见 [`DEPLOYMENT_WIKI.md` 5.7.1](DEPLOYMENT_WIKI.md#571-需重启服务器才生效的键启动快照--周期任务)。

<details>
<summary><b>⚠️ 多子服（BungeeCord / Velocity）必读：时区必须完全一致</b></summary>

所有连同一数据库的子服，**JVM 时区必须完全一致**。插件按 `yyyy-MM-dd` 纯日期记账，
两服日界不同会让**签到在同一天被领两次**（双份真钱），答题日限、金币日限、AI 轮次门、
每日挑战也会各算各的。赛季轴由 `anchor-date` 按日界纯函数推导，对日界偏差尤其敏感。

插件内**没有** `timezone` 配置项，无法统一 —— 必须用启动参数钉死，且**写在 `-jar` 之前**：

```bash
java -Duser.timezone=Asia/Shanghai -jar paper.jar
```

这是**部署前提，不是插件缺陷**。详见 [`DEPLOYMENT_WIKI.md` 5.9 节](DEPLOYMENT_WIKI.md#59-多子服时区一致性硬性前提)。

</details>

<details>
<summary><b>🔒 跨服网络的安全配置（<code>proxy.inbound-secret</code>）</b></summary>

Bukkit 把「代理转发来的插件消息」和「任何玩家客户端自己发的自定义 payload」派发到
**同一个回调**，而该回调拿不到可靠的来源信息 —— 一个改了客户端的玩家就能伪造以下任一帧：

- `GlobalMessage` → 向全服广播任意文本（可伪装成服主公告）
- `AchievementUnlock` → 给任意玩家名挂上任意成就并广播
- `SeasonSettled` → 触发赛季奖励发放与「赛季结束」广播（金额受台账幂等保护不会重复发，但发钱**时机**由攻击者控制）

设置 `proxy.inbound-secret` 为任意一串**只有你知道的长随机字符串**
（推荐 `openssl rand -hex 32` 的输出）后，所有入站帧都必须带正确的 HMAC-SHA256 签名，
否则一律丢弃并记 WARNING。**多服网络中每一台子服必须填同一个值**。

留空（默认）= 不校验签名，行为与本功能加入前**逐字节一致**。

> 这不是 HTTPS 那种传输加密。代理网络内的消息仍是明文经过代理，本机制防的是
> 「普通玩家伪造跨服指令」，不防「能抓包并读到你密钥的人」。

</details>

<details>
<summary><b>💾 SQLite 数据库整理（长期运行的服务器）</b></summary>

SQLite 的 `DELETE` / `UPDATE` 只把页面标记为可复用，**不会把空间还给磁盘**。
`ai_chat_history`、`social_feed`、`word_progress` 这类持续增删的表跑久了，
`playerdata.db` 会只涨不缩，碎片化还会拖慢查询。自动备份用的是 `VACUUM INTO`，
它只是**生成一份快照副本**，主库一个字节都不会变，因此不能解决这个问题。

插件已内置一条索引优化（`idx_chat_timestamp`）把 `cleanupOldChatHistory` 从全表扫描
降到索引范围扫描（MariaDB 50 万行实测 1557 ms → 1 ms）。手动整理的做法：

1. 停服（或至少确认没有备份任务在跑）
2. **注意：插件启用了 WAL 模式，数据库不是单个 `.db` 文件** —— 必须先 checkpoint
   再用 SQLite 自己的备份命令，直接 `cp playerdata.db` 会漏掉还在 WAL 里的未合并事务：

   ```bash
   sqlite3 playerdata.db "PRAGMA wal_checkpoint(TRUNCATE);"
   sqlite3 playerdata.db ".backup 'playerdata.db.bak'"
   ```

3. 整理并校验：

   ```bash
   sqlite3 playerdata.db "PRAGMA optimize; VACUUM;"
   sqlite3 playerdata.db "PRAGMA quick_check;"   # 期望输出 ok
   ```

4. 重启服务器

> `DatabaseManager.runMaintenance()`（`PRAGMA optimize` + `VACUUM`）已实现但**尚未暴露为
> 游戏内命令**。若通过代码/命令入口调用，**必须放到异步线程** —— `VACUUM` 会持有写锁且
> 耗时与库体积成正比，在主线程执行会直接卡服。

</details>

<details>
<summary><b>🔊 关于发音功能：当前版本不播放真实语音</b></summary>

音标显示是完整的。但**点击发音按钮播放的是音符盒提示音**，不是真人语音。

`tts` 段配置后，VoiceRSS 下载的 `.wav` 会缓存在 `audio/` 目录，供未来资源包预留 ——
**插件不会播放它**。这是有意的现状，不是 bug。

</details>

---

## 🏗️ 它是怎么工作的

```
玩家（无需客户端 mod）
   └─ /le → 主菜单 GUI → 答题 / 复习 / AI 对话 / 广场
        │
        └─ Paper 服务端插件（本仓库，Java 21 / 25）
             ├─ 答题状态机  WordLearningManager（倒计时、连击计分）
             ├─ 间隔复习    ReviewScheduler（艾宾浩斯 1h ~ 168h）
             ├─ AI 对话     AIChatManager → HTTPS → DeepSeek API
             ├─ 数据层      PlayerDataStore（内存缓存 + 持久化）
             │                ├─ SQLite（默认，单文件，WAL）
             │                └─ MySQL（多子服共享）
             └─ 可选集成    Vault（金币）· PlaceholderAPI（%lle_*%）· Web 仪表盘
```

### 技术栈

| 层 | 用了什么 |
|---|---|
| 服务端 | Paper 1.21.4 – 1.21.11（Java 21）／ Paper 26.1+（Java 25） |
| 语言 | Java 21（104 个源文件，约 57,500 行） |
| 数据库 | SQLite（默认，shade 内置）／ MySQL（`mysql-connector-j` 已 shade 进 JAR） |
| AI | DeepSeek API，`java.net.http.HttpClient`，异步 + 每玩家限流 |
| 测试 | JUnit 5 + Mockito，**1341 条**测试 + JaCoCo 覆盖率门禁 |
| 构建 | Maven，双 profile（默认 / `-Pmc26`） |

---

## 🔧 构建

**环境要求**：JDK 21（`-Pmc26` 档需要 JDK 25）、Maven 3.9+

```bash
# 标准构建（Paper 1.21.4 – 1.21.11）
mvn verify

# 26.x 构建（Paper 26.1+）
mvn -Pmc26 verify
```

`mvn verify` 会编译、运行测试并通过 JaCoCo 覆盖率门禁，是验证改动的标准命令。

> `mvn package -DskipTests` 仅用于快速本地打包，**勿用于验证改动** —— 它会同时跳过覆盖率门禁。

JAR 生成在 `target/` 目录。两个 profile 的产物名分别是
`Let's Learn English!-<版本>.jar` 和 `Let's Learn English!-<版本>-mc26.jar`
（GitHub 上传时会把 `!` 和空格替换成 `.`，即 Release 页上的 `Let.s.Learn.English.-<版本>.jar`）。

---

## 🔗 依赖

- **必需**：Paper 1.21.4+（见上方 JAR 选择表）
- **可选**：Vault（金币奖励）、PlaceholderAPI（`%lle_*%` 占位符）
- **不保证**：Spigot / Purpur / Folia 兼容

> 为什么只保证 Paper：插件使用了 Paper 专属 API（Adventure `net.kyori` 组件、
> `Server#getMinecraftVersion`），这些在 Spigot 上不存在。

> MySQL 驱动已通过 shade 随 JAR 内置，使用 MySQL 后端时无需额外放置驱动。
> 若你自建 JAR 后报 `ClassNotFoundException: com.mysql.cj.jdbc.Driver`，
> 确认 `maven-shade-plugin` 的 `shade-mysql-driver` execution 执行过
>（`mvn package` 产物应约 3 MB，`unzip -l` 能看到 `com/mysql/`）。

---

## 🤝 开发与贡献

代码不算漂亮，但每一轮修复都有测试钉着。想让它跑得更好 —— 欢迎来玩，Issue、PR 都收。

- **贡献指南**：[`CONTRIBUTING.md`](CONTRIBUTING.md) —— 怎么报问题、怎么提 PR、代码风格与测试要求
- **安全问题**：[`SECURITY.md`](SECURITY.md) —— 漏洞报告渠道
- **构建与测试**：`mvn verify`（见上方「构建」一节）
- **报 bug 请带上**：插件版本（`/lea info`）、服务端版本、Java 版本、复现步骤、控制台日志

---

## 📚 文档

| 文档 | 内容 |
|---|---|
| [`DEPLOYMENT_WIKI.md`](DEPLOYMENT_WIKI.md) | **部署总纲**：环境要求、安装步骤、全部配置项、故障排查、命令参考 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 贡献指南 |
| [`SECURITY.md`](SECURITY.md) | 安全策略与漏洞报告 |
| [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) | 第三方组件与词库来源声明 |
| [`src/main/resources/words/README.md`](src/main/resources/words/README.md) | 词库字段模板、来源、许可与已知质量限制 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本变更记录 |
| [Releases](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases) | **JAR 下载**（免 token） |

---

## ⚖️ 许可证

GNU General Public License v3.0 only（GPL-3.0-only），全文见 [`LICENSE`](LICENSE)。

**词库来源与许可**（详见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 与
[`words/README.md`](src/main/resources/words/README.md)）：

- **NGSL 1.2**（New General Service List，Browne, Culligan & Phillips）—— **CC BY-SA 4.0**，
  仅用于选词与排序
- **ECDICT**（MIT）—— 中文释义与词性参考
- **CMUdict**（BSD）—— 新增词的 ARPAbet 音标转写（美式）
- **Tatoeba**（CC BY 2.0 FR）—— 例句来源

逐词内容（释义、音标、例句、同/反义词）为本项目原创编写，随项目许可一并发布。

> **已知质量限制**（如实记录）：新增 332 词的音标由 CMUdict 美式发音转换而来，
> 与词库其余部分的英式 IPA 一致率约 58%–68%，**未经逐词人工核验**；
> 约 37% 的 Tatoeba 例句含 1–3 个超纲实词，**未经逐句人工核验**。
> 发现不妥可直接改 YAML 后 `/lea reload`。

---

## ⭐ Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&theme=light&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
 </picture>
</a>
