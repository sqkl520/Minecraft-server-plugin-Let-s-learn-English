[English](README-EN.md) | 简体中文

# Let's Learn English!

**在 Minecraft 服务器里学英语：玩家不需要装任何客户端 mod，服主装一个插件就能开课。**
答题学词 → 间隔复习 → AI 情景对话 → 老师出学习报告，四件事都在游戏内完成。

[![Build & Verify](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/actions/workflows/build.yml/badge.svg)](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/sqkl520/Minecraft-server-plugin-Let-s-learn-English?display_name=tag&sort=semver)](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)

---

## 快速开始

### 1. 按你的服务端版本选 JAR

**下错包装不上。** 本项目发布两个包，源码相同、编译目标不同：

| 你的服务端 | 下载 | 需要 |
|---|---|---|
| **MC 1.21.4 – 1.21.11** | `Let.s.Learn.English.-<版本>.jar` | **Java 21+** |
| **MC 26.1+**（26.2 / 26.3 / 26.4…） | `Let.s.Learn.English.-<版本>-mc26.jar` | **Java 25+** |

### 2. 装上去

```bash
# 把 JAR 放进服务端插件目录
cp Let.s.Learn.English.-1.0.7.jar /path/to/server/plugins/

# 重启服务端（不要用 /reload）
```

### 3. 确认装对了

游戏内执行：

```
/lea info
```

会打印版本、服务端版本兼容性、数据库类型。若版本一行写着 `26.x detected`，说明你在
26.x 服务端装的是普通包——换成 `-mc26` 那个。

玩家输入 `/le` 打开主菜单。

### 4. （可选）开 AI 对话

编辑 `plugins/LetsLearnEnglish/ai_config.yml`，填入 DeepSeek API 密钥：

```yaml
api-key: "sk-你的密钥"
```

改完用 `/lea reload` 热重载，不用重启。

> ⚠️ **多子服（BungeeCord / Velocity）必读**
>
> 所有连同一数据库的子服，**JVM 时区必须完全一致**。插件按 `yyyy-MM-dd` 纯日期记账，
> 两服日界不同会让**签到在同一天被领两次**（双份真钱），答题日限、金币日限、AI 轮次门、
> 每日挑战也会各算各的。
>
> 插件内没有 `timezone` 配置项，无法统一——必须用启动参数钉死，且**写在 `-jar` 之前**：
>
> ```bash
> java -Duser.timezone=Asia/Shanghai -jar paper.jar
> ```
>
> 详见 [`DEPLOYMENT_WIKI.md` 的 5.9 节](DEPLOYMENT_WIKI.md#59-多子服时区一致性硬性前提)。

---

## 它长什么样

<!--
  截图/GIF 待补：主菜单、答题流程、AI 对话、老师报告。
  这是本 README 目前最大的缺口 —— 读者无法从文字判断交互质量。
-->

*截图待补。*

---

## 它解决什么问题

Minecraft 服务器上的“学英语插件”通常停在**刷词卡**：给一个词、选一个意思、加分。
这能让学生记住，但记不住**怎么用**，也留不住人——一周后没人再打开。

这个插件把四件事接成一条链：

1. **答题学词** —— GUI 显示中文 → 关掉 GUI → 在聊天栏倒计时拼英文。有压力、有连击
2. **间隔复习** —— 按艾宾浩斯曲线自动排期，答错的词会重新出现
3. **AI 情景对话** —— 用刚学的词跟 AI 演一段对话，结束后给语法纠错和推荐词
4. **老师报告** —— `/lea report` 出一份可打印的学习报告，含易错词与进步曲线

区别在于**第 3 步和第 4 步**：市面上多数 MC 英语插件只有第 1 步。


---

## 功能特性

- **单词学习答题系统** —— GUI 展示中文词义 → 关闭 GUI → 聊天栏倒计时拼写英文单词
- **回合制 Combo 连胜系统** —— 连续答对产生递增倍率（1.0x → 3.0x），答错/超时重置
- **基于艾宾浩斯遗忘曲线** —— 智能间隔复习机制，自动安排复习计划
- **新手引导线** —— 首次进服发放《新手引导手册》，三步上手：选词库 → 3 词迷你会话（选择题、答错原地重试）→ 打开个人主页；`/le guide` 随时查看进度或补领手册，老玩家自动跳过
- **AI 情景对话系统** —— 基于 DeepSeek API 的智能英语情景对话，支持 6 个场景
- **AI 会话小结与点评** —— 对话结束自动生成本地统计小结（发言数 / 时长），达标会话异步追加 AI 总评、语法纠错与推荐词；`/le history` 详情页可回看完整点评
- **错题本系统** —— 答错 3 次自动收录，支持独立复习和移除
- **每日单词挑战** —— 每日随机从词库选词挑战，完成后获得奖励
- **每日签到系统** —— 连续签到递增金币奖励，断签重置
- **PvP 单词对决** —— 双人实时抢答对决，先得 5 分者胜，支持掉线重连
- **排行榜系统** —— 金币榜 / 单词榜 / 连击榜 / 赛季榜（启用赛季后可见），数据库持久化
- **赛季系统（默认关闭）** —— 固定周期赛季（时长与首季开始日可配）：榜单第 4 tab 赛季榜 + `/le season` 聊天快览 + `%lle_season%` 系列占位符；赛季结束按名次（top1 / top3 / top10 / 参与奖）自动结算金币奖励，支持结算广播与离线进服补发
- **班级系统（默认关闭）** —— 管理型班级：管理员/老师用 `/lea class` 建班、加人（一人一班）；玩家 `/le class` 查看自己的班级与班级榜（当前赛季窗内成员学习分，尊重成员隐身设置）
- **学习统计 HTTP 仪表盘** —— 基于 ECharts 的 Web 管理面板，IP 白名单 + Basic Auth 防护
- **AI 对话历史回顾** —— 持久化对话记录，分页浏览历史会话
- **词库导入导出** —— 管理员可导入/导出 YAML 词库，含校验和去重
- **单词发音辅助** —— 音标显示 + 提示音（当前版本不播放真实语音：点击发音按钮播放的是音符盒提示音；VoiceRSS 下载的 `.wav` 仅缓存在 `audio/` 目录，供未来资源包预留，插件不会播放它）
- **自定义 AI 场景分享** —— Base64 编码分享自定义场景，导入为个人场景
- **个人场景管理 GUI** —— 场景选择页管理入口，图形化查看/删除个人场景（行内二次确认，每人上限 30 个）
- **英语广场学习动态流** —— 广场新增「动态」模式：完成会话 / 解锁成就 / 签到自动生成学习动态（同类当日合并，每人每日最多 3 条）；可左键鼓励、右键看主页，被鼓励者在线即时收到提醒、离线进服补发摘要
- **学习进度追踪** —— 可视化学习统计、排行榜与成就系统
- **图形化菜单** —— 直观的 GUI 操作界面，配合操作音效
- **主菜单 9 按钮** —— 主菜单整合 9 个入口（新增【单词对决】【每日签到】），按钮槽位/材质可配置，对应模块关闭时自动隐藏
- **多语言支持** —— 主菜单内二次确认切换中英文界面，可自定义消息前缀
- **版本更新检测** —— 启动时自动检查 GitHub Releases，OP 进服通知
- **每日学习提醒** —— 定时推送 + 玩家进服鼓励消息（均可配置）
- **数据库自动备份** —— 定时备份，保留最近 7 份
- **家长报告** —— `/lea report` 生成纯文本学习报告（本期概览 / 每日明细 / 易错单词 / 累计成果 / 给家长的话），支持单人与全服批量对比表，独立权限 `letslearnenglish.report`
- **模块级开关（9 个）** —— word-system / ai-chat / duel / checkin / daily-challenges / leaderboard / achievements / rewards 与 progress-system 伞开关；统一「入口隐藏 + 事件静默 + 数据永不删」三层语义，`/lea reload` 即时生效
- **管理员命令** —— 热重载配置、重置玩家数据、添加/删除单词库

## 命令

### 玩家命令 `/le`（别名 `/learn`、`/english`）

| 命令 | 说明 |
|------|------|
| `/le menu` | 打开主菜单 |
| `/le guide` | 查看新手引导进度 / 重新领取引导手册 |
| `/le word [难度] [模式]` | 开始单词学习（难度：beginner / intermediate / advanced；模式：choice（默认）/ spelling / fill_blank） |
| `/le review` | 复习待复习单词 |
| `/le dialogue [场景]` | 开始 AI 情景对话 |
| `/le progress` | 查看个人主页 |
| `/le profile [玩家名]` | 查看个人资料 |
| `/le stats` | 查看学习统计 |
| `/le square [模式]` | 进入英语广场（模式：feed 动态 / weekly 周榜 / monthly 月榜 / alltime 总榜 / all 玩家列表） |
| `/le leaderboard` | 查看排行榜（金币榜 / 单词榜 / 连击榜 / 赛季榜） |
| `/le challenge` | 查看今日挑战进度 |
| `/le history` | 查看 AI 对话历史（详情页含会话小结与 AI 点评） |
| `/le duel <玩家名>` | 发起单词对决（`/le duel accept` 接受，`/le duel quit` 退出，`/le duel gui` 重开界面） |
| `/le wrong` | 查看错题本 |
| `/le checkin` | 每日签到获取金币 |
| `/le season` | 查看当前赛季、剩余时间与你的排名 |
| `/le class` | 查看我的班级与班级榜 |
| `/le save_scenario <名称>` | 导出当前 AI 场景为分享码 |
| `/le load_scenario <编码>` | 导入分享的场景码（保存为个人场景） |
| `/le help` | 查看帮助 |

### 管理员命令 `/lea`（别名 `/learnadmin`；权限 `letslearnenglish.admin`，`report` 子命令也可用 `letslearnenglish.report`）

| 命令 | 说明 |
|------|------|
| `/lea reload` | 热重载配置、AI 段（`ai_config.yml` / `ai-chat.*` / 场景）、词库、语言文件、音效缓存、统计服务（少数启动快照键需重启，见「配置」一节） |
| `/lea reset <玩家名>` | 重置玩家学习数据（两段式确认：先提示，再执行 `/lea reset <玩家名> confirm` 生效） |
| `/lea info` | 查看插件信息 |
| `/lea export` | 导出玩家数据 |
| `/lea exportwordbank <难度>` | 导出单词库至 YAML 文件 |
| `/lea importwordbank <文件名.yml>` | 从 YAML 文件导入单词库（文件需放在 `plugins/LetsLearnEnglish/exports/`） |
| `/lea broadcast <消息>` | 通过代理向所有子服广播（需 Velocity / BungeeCord） |
| `/lea duel cancel <玩家名>` | 强制解散该玩家的单词对决（不判胜负） |
| `/lea report <玩家\|all> [week\|lastweek\|<N>d]` | 生成家长/老师学习报告（TXT，保存至 `plugins/LetsLearnEnglish/exports/reports/`） |
| `/lea class create\|delete\|add\|remove\|list` | 班级管理（创建 / 删除 / 加入 / 移出 / 查看成员，需启用班级系统） |
| `/lea ignore-update` | 忽略本次更新提醒 |
| `/lea help` | 查看管理帮助 |

### 权限节点

插件共注册 4 个权限节点，全部默认仅 OP 拥有。

**玩家侧功能绝大多数不需要权限** —— `/le menu`、`/le word`、`/le review`、`/le square`、`/le profile`、`/le wrong`、`/le checkin`、`/le duel`、`/le history`、`/le season` 等由 `config.yml` 的模块开关控制，不看权限。
**唯一例外是场景导入**：`/le load_scenario` 与个人场景管理页需要 `letslearnenglish.scenario.import`（见下表），因为它们会把外部内容落进服主的场景列表，需要与普通玩法区分授权。

| 节点 | 默认 | 用途 |
|------|:---:|------|
| `letslearnenglish.admin` | op | `/lea` 的全部子命令（`reload` / `reset` / `info` / `export` / `exportwordbank` / `importwordbank` / `broadcast` / `duel cancel` / `class` / `diagnose` / `ignore-update` / `help`），以及场景管理页的删除入口 |
| `letslearnenglish.report` | op | **仅** `/lea report`。用于把「生成家长报告」单独授予老师/家长代表，而不必给全部管理权限 |
| `letslearnenglish.ai.reload` | op | **仅**重载 AI 配置（`ai_config.yml` + `ai_scenarios.yml`），等价于 GUI 里「刷新 API 密钥」按钮。与 `/lea reload` 的全模块重载能力不同，可单独授予内容维护者 |
| `letslearnenglish.scenario.import` | op | **玩家侧唯一需要显式授权的功能**：`/le load_scenario` 导入分享码、个人场景管理页的查看/删除。语义是「可以把外部内容写进自己的场景列表」 |

> 注意 1：`letslearnenglish.ai.reload` 与 `/lea reload` **不等价**，前者只刷 AI 段。若你要授予「能改 API key」的人，给这个就够，不必给 `admin`。
> 注意 2：场景管理页的**删除**入口要求 `admin`，而其**查看/导入**要求 `scenario.import` —— 两者刻意分开（见 `ScenarioSelectListener` 的权限门注释）。

## 配置

主要配置项（`config.yml`）：

```yaml
general:
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

以上仅为节选。完整配置见 `config.yml` 内置的全中文注释（改完多数键 `/lea reload` 即时生效，少数键需重启，见下），更多部署细节见 `DEPLOYMENT_WIKI.md`。主要配置段：

`general` / `prefix` / `database`（SQLite / MySQL）/ `word-system`（含三模式开关 `modes`）/ `onboarding`（新手引导）/ `duel` / `ai-chat`（含会话小结 `feedback`）/ `scenario` / `money`（每日金币上限）/ `progress-system`（`leaderboard` / `achievements` / `rewards` 子开关）/ `gui` + `gui-layout`（界面与槽位）/ `feedback`（倒计时与连击反馈）/ `achievement-system` / `proxy`（跨服广播）/ `daily-reminder` / `daily-challenges` / `checkin` / `tts`（发音） / `statistics-server`（Web 仪表盘）/ `report`（家长报告）/ `social` + `english-square`（广场动态流）/ `season`（赛季）/ `class-system`（班级）

**需要重启服务器的键**（这些键在启动时被快照或用于排定周期任务，`/lea reload` 不会重排）：

- `general.auto-save-interval`
- `general.backup.enabled` / `general.backup.interval` / `general.backup.max-backups`
- `general.check-updates`（只在启动时决定是否联网检查）
- `daily-reminder.reminder-hours`
- `tts.enabled` / `tts.cache-days` / `tts.voicerss-api-key`
- `progress-system.leaderboard.update-interval`

其余键（含全部模块开关 `enabled`、`ai-chat.*`、语言文件、词库、`gui-layout`、成就开关、统计仪表盘）均支持 `/lea reload` 即时生效。

### 跨服网络的安全配置（`proxy` 段）

如果你用 **BungeeCord / Velocity** 把多台子服串成一张网，`proxy` 段有两个键需要配置：

| 键 | 说明 |
|----|------|
| `proxy.server-name` | 本子服在代理配置里的名字。用于赛季结算/开始的回环去重。留空 = 单服模式（最多重复播报一次，奖励发放仍受台账幂等保护） |
| `proxy.inbound-secret` | **入站消息的共享密钥。多服网络强烈建议设置** |

**为什么 `inbound-secret` 不是可选项建议而是真正的安全需求**：Bukkit 把「代理转发来的插件消息」和「任何玩家客户端自己发的自定义 payload」派发到**同一个回调**，而该回调拿不到可靠的来源信息 —— 一个改了客户端的玩家就能伪造以下任一帧，效果与真正的子服发送完全相同：

- `GlobalMessage` → 向全服广播任意文本（可以伪装成服主公告）
- `AchievementUnlock` → 给任意玩家名挂上任意成就并广播
- `SeasonSettled` → 触发赛季奖励发放与「赛季结束」广播（金额受台账幂等保护不会重复发，但发钱**时机**由攻击者控制）

设置任意一串**只有你知道的长随机字符串**（推荐 `openssl rand -hex 32` 的输出）后，所有入站帧都必须带正确的 HMAC-SHA256 签名，否则一律丢弃并记 WARNING。**多服网络中的每一台子服必须填同一个值**，否则它们之间也互相丢弃。

留空（默认）= 不校验签名，行为与本功能加入前**逐字节一致** —— 单服或纯局域网可以保持留空。

> 注意：这不是 HTTPS 那种传输加密。代理网络内的消息仍是明文经过代理，本机制防的是「普通玩家伪造跨服指令」，不防「能抓包并读到你密钥的人」。

> 例外：`word-system.review.intervals` 与 `mastered-review-interval-days`（复习间隔在 `ReviewScheduler` 构造时快照）、`database.*`（仅 onEnable 初始化）也需要重启。详见 `DEPLOYMENT_WIKI.md` 5.7.1。

> 例外补充：`daily-reminder.enabled` 与 `word-system.review.enabled` 只能在启动时已排定任务的前提下用 reload 关闭；若启动时就是 `false`（任务未排程），再改回 `true` 需要重启。详见 `DEPLOYMENT_WIKI.md` 5.7.1。

### SQLite 数据库整理（长期运行的服务器）

SQLite 的 `DELETE` / `UPDATE` 只把页面标记为可复用，**不会把空间还给磁盘**。`ai_chat_history`、`social_feed`、`word_progress` 这类持续增删的表跑久了，`playerdata.db` 会只涨不缩，碎片化还会拖慢查询。自动备份用的是 `VACUUM INTO`，它只是**生成一份快照副本**，主库一个字节都不会变，因此不能解决这个问题。

插件为此提供了 `DatabaseManager.runMaintenance()`（`PRAGMA optimize` + `VACUUM`），**当前版本尚未暴露为游戏内命令**，是给未来的 `/lea maintenance` 与运维脚本预留的入口。手动整理的做法：

1. 停服（或至少确认没有备份任务在跑）
2. 进入 `plugins/LetsLearnEnglish/`，先备份。**注意：插件启用了 WAL 模式，数据库不是单个 `.db` 文件**——必须先 checkpoint 再用 SQLite 自己的备份命令，直接 `cp playerdata.db` 会漏掉还在 WAL 里的未合并事务：
   ```bash
   sqlite3 playerdata.db "PRAGMA wal_checkpoint(TRUNCATE);"
   sqlite3 playerdata.db ".backup 'playerdata.db.bak'"
   ```
3. 用 SQLite 命令行整理，再校验：
   ```bash
   sqlite3 playerdata.db "PRAGMA optimize; VACUUM;"
   sqlite3 playerdata.db "PRAGMA quick_check;"   # 期望输出 ok
   ```
4. 重启服务器

> 若通过代码/命令入口调用 `runMaintenance()`，**必须放到异步线程**——`VACUUM` 会持有写锁且耗时与库体积成正比，在主线程执行会直接卡服。

## 依赖

- Paper **1.21.4 – 1.21.11**（用 `Let's Learn English!-<ver>.jar`，需 **Java 21+**）
- Paper **26.1+**（用 `Let's Learn English!-<ver>-mc26.jar`，需 **Java 25+**）
- 两个包源码相同、仅编译目标不同；在 26.x 上装普通包、或在 1.21.x 上装 `-mc26` 包都会出问题，详见 `DEPLOYMENT_WIKI.md`
- 不保证 Spigot 兼容
- Java 21
- 可选：Vault（金币奖励）、PlaceholderAPI（`%lle_*%` 占位符）

> 为什么只保证 Paper：插件使用了 Paper 专属 API（Adventure `net.kyori` 组件、`Server#getMinecraftVersion`），这些在 Spigot 上不存在。

> MySQL 驱动（`mysql-connector-j`）已通过 shade 随 JAR 内置，使用 MySQL 后端时无需额外放置驱动。若你使用自建 JAR 并报 `ClassNotFoundException: com.mysql.cj.jdbc.Driver`，请确认构建时 `maven-shade-plugin` 的 `shade-mysql-driver` execution 执行过（`mvn package` 产物应约 3MB，且 `unzip -l` 能看到 `com/mysql/`），或直接使用官方发布的 JAR。

## 构建

```bash
mvn verify
```

`mvn verify` 会编译、运行测试并通过 JaCoCo 覆盖率门禁，是验证改动的标准命令。

> `mvn package -DskipTests` 仅用于快速本地打包，勿用于验证改动。

JAR 文件将生成在 `target/` 目录。

## 许可证

GNU General Public License v3.0 only（GPL-3.0-only），全文见仓库根目录 `LICENSE`。

依赖与词库引用声明：打包进产物的第三方组件（MySQL Connector/J 等）见 `THIRD-PARTY-NOTICES.md`；词库选词来源见 `words/README.md`（源码位于 `src/main/resources/words/README.md`；NGSL 使用 CC BY-SA 4.0 许可，仅用于选词）。

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
 </picture>
</a>
