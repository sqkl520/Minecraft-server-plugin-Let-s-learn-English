# Let's Learn English! — 服务器管理员部署 Wiki

> **适用版本**: 1.0.7+（含 Phase 3 全部功能批次）
> **最后更新**: 2026-10-16
> **作者**: sqkl520
> **GitHub**: https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English

---

## 目录

1. [插件概述与功能说明](#1-插件概述与功能说明)
2. [系统环境要求](#2-系统环境要求)
3. [前置依赖组件](#3-前置依赖组件)
4. [详细安装步骤](#4-详细安装步骤)
5. [服务端配置指南](#5-服务端配置指南)
6. [常见问题排查与解决方案](#6-常见问题排查与解决方案)
7. [更新与维护流程](#7-更新与维护流程)
8. [命令行指令参考](#8-命令行指令参考)

---

## 1. 插件概述与功能说明

### 1.1 简介

**Let's Learn English!** 是一款面向 Minecraft 服务器的游戏化英语学习插件。它将英语学习与 Minecraft 的游戏机制深度结合，让玩家在游戏过程中自然地学习和巩固英语词汇、练习情景对话。

### 1.2 核心功能模块

| 模块 | 说明 |
|------|------|
| **单词学习系统** | 三种答题模式（拼写 / 选择 / 填空），支持连击 Combo 系统，基于艾宾浩斯遗忘曲线智能复习；`word-system.enabled` 模块开关 + 各模式独立开关 |
| **错题本** | 答错 3 次自动收录的困难单词列表，支持独立练习与移除（属 `word-system` 域） |
| **AI 情景对话系统** | 6 个内置场景（餐厅、机场、购物、酒店、医院、自由对话）+ 3 个难度档（简单/中等/困难），基于 DeepSeek API，支持历史回顾、会话小结与 AI 点评（`ai-chat.feedback`） |
| **个人场景管理** | 玩家可用分享码导入/导出 AI 场景为「个人场景」（每人上限 30 个），场景选择页内置管理 GUI（查看/删除、行内二次确认） |
| **单词对决** | 双人实时抢答对决，先得 5 分者胜，支持掉线宽限与 BossBar 倒计时 |
| **每日挑战与签到** | 每日行为挑战（答题/会话/连击/AI 对话）+ 每日单词挑战；每日签到按连续天数递增金币 |
| **赛季系统**（默认关闭） | 固定周期赛季（时长与首季开始日可配）：榜单第 4 tab、`/le season` 聊天快览、`%lle_season%` 系列占位符、按名次自动结算金币（top1/top3/top10/参与奖） |
| **进度与奖励系统** | 排行榜（金币/单词/连击/赛季榜）、成就系统（22 个）、金币奖励（Vault） |
| **英语广场与动态流** | 玩家列表 + 周/月/总榜 + 「动态」模式（学习动态流 + 鼓励按钮 + 进服摘要） |
| **个人主页** | 学习统计（已学单词、正确率、学习次数、金币、成就），隐私设置，过往学习日历 |
| **成就墙** | 54 格界面按 5 类（学习/复习/坚持/社交/财富）分组展示 22 个成就，含解锁日期、进度行、前置提示与限时倒计时 |
| **家长/老师报告** | `/lea report <玩家\|all> [week\|lastweek\|<N>d]` 生成纯文本 TXT 学习报告（独立权限 `letslearnenglish.report`） |
| **新手引导** | 首次 3 步引导线（选词库 → 3 词迷你会话 → 查看成就），发放《新手引导手册》，`/le guide` 可查进度 |
| **中英双语界面** | 全界面 100% 双语覆盖，玩家可在主菜单一键切换语言 |
| **模块级开关（9 个）** | word-system / ai-chat / duel / checkin / daily-challenges / leaderboard / achievements / rewards 与 progress-system 伞开关，统一「入口隐藏 + 事件静默 + 数据保留」三层语义，`/lea reload` 即时生效 |
| **班级系统**（默认关闭） | 管理型班级：管理员用 `/lea class` 建班/加人（一人一班），玩家 `/le class` 查看班级与班级榜 |
| **配置全自定义** | 所有 GUI 物品材质和位置可配置，语言文件全可控 |

### 1.3 技术架构概览

```
┌─────────────────────────────────────────────────────┐
│                 Let's Learn English!                 │
├─────────────────────────────────────────────────────┤
│  Commands   │  /le (玩家)  │  /lea (管理员)          │
├─────────────────────────────────────────────────────┤
│  GUI Layer  │  MainMenu / WordLearning / Profile    │
│             │  EnglishSquare / AchievementWall / etc│
├─────────────────────────────────────────────────────┤
│  Core       │  WordManager / WordLearningManager    │
│             │  AIChatManager / DuelManager          │
│             │  DailyChallengeManager / SeasonManager│
│             │  SocialManager / OnboardingManager    │
│             │  ProgressManager / AchievementManager │
├─────────────────────────────────────────────────────┤
│  Data       │  SQLite / MySQL  (via DatabaseManager) │
├─────────────────────────────────────────────────────┤
│  Listeners  │  ChatAnswer / GUIMenu / Chat / Join   │
├─────────────────────────────────────────────────────┤
│  API        │  LetsLearnEnglishAPI (供其他插件调用)  │
└─────────────────────────────────────────────────────┘
```

---

## 2. 系统环境要求

### 2.1 服务端要求

| 项目 | 最低要求 | 推荐配置 |
|------|---------|---------|
| **服务端核心** | Paper 1.21+ / Spigot 1.21+ | Paper 1.21.4（开发与测试版本） |
| **API 版本** | 1.21 | 1.21.4 |
| **Java 版本** | Java 21 | Java 21 |
| **操作系统** | Windows / Linux / macOS | Linux (推荐) |

> `plugin.yml` 中 `api-version: "1.21"`；`VersionAdapter` 的运行期版本判定范围为 1.18.x–1.21.x（超出会提示不受支持）。

### 2.2 硬件资源占用

| 指标 | 预估值 |
|------|--------|
| **内存占用** | ~15-30 MB |
| **CPU 占用** | 极低（仅定时保存和复习通知有微量调度开销） |
| **磁盘占用** | ~5 MB（插件 JAR 约 0.6 MB + 数据库 + 配置文件；词库扩充后随数据目录增长） |

> 插件所有定时任务（自动保存、复习通知、备份）均为异步执行，不会阻塞主线程影响服务器 TPS。

---

## 3. 前置依赖组件

### 3.1 必需依赖

**无硬性必需依赖。** 插件可独立运行。

### 3.2 软依赖（可选，增强功能）

| 插件 | 用途 | 下载 |
|------|------|------|
| **PlaceholderAPI** | 提供 `%lle_*%` 占位符变量，供其他插件（如计分板）显示学习数据 | [SpigotMC](https://www.spigotmc.org/resources/placeholderapi.6245/) |
| **Vault** | 经济系统对接，答题获得金币奖励 | [SpigotMC](https://www.spigotmc.org/resources/vault.34315/) |

> 历史版本的 Citizens NPC 交互已移除（当前代码无任何 Citizens / NPC 引用），不再需要安装 Citizens。

### 3.3 依赖加载顺序

```
Plugins 目录加载顺序（由 Bukkit 自动处理）:
  1. Vault（如有）
  2. PlaceholderAPI（如有）
  3. LetsLearnEnglish ← 本插件
```

> 插件的 `plugin.yml` 中已声明 `softdepend`，Bukkit 会自动确保软依赖插件先于本插件加载。

---

## 4. 详细安装步骤

### 4.1 首次安装

#### 步骤 1：获取插件 JAR 文件

从以下渠道获取最新版本的 JAR 文件：
- GitHub Releases: `https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English-/releases`
- 自行编译: `mvn clean package`（源码仓库中）

文件名为: `Let's Learn English!-{版本号}.jar`（如 `Let's Learn English!-1.0.7-SNAPSHOT.jar`）

#### 步骤 2：放置 JAR 文件

将 JAR 文件复制到服务器的 `plugins/` 目录中：

```
服务器根目录/
├── plugins/
│   ├── Let's Learn English!-1.0.7-SNAPSHOT.jar  ← 放这里
│   ├── Vault.jar (可选)
│   ├── PlaceholderAPI.jar (可选)
│   └── ...
├── server.properties
└── ...
```

#### 步骤 3：重启服务器或热加载

**方法 A — 重启服务器（推荐）**:
```bash
# 在服务器控制台中
stop
# 然后重新启动服务器
```

**方法 B — 热加载（仅适用于已安装旧版本、正在升级的情况）**:
```
plugman load LetsLearnEnglish
```
> 不推荐使用 PlugMan 进行首次安装，部分功能可能无法正确初始化。

#### 步骤 4：验证安装

服务器启动后，在控制台中应能看到以下日志输出：

```
[LetsLearnEnglish] ============================================
[LetsLearnEnglish]   Let's Learn English! v1.0.7-SNAPSHOT
[LetsLearnEnglish]   Plugin enabled successfully! (Time: XXXms)
[LetsLearnEnglish] ============================================
[LetsLearnEnglish] Vault economy integration enabled.         (如果安装了 Vault)
[LetsLearnEnglish] Loaded 0 player language preferences.
[LetsLearnEnglish] Daily study reminder scheduled at hours: [9, 14, 20]
[LetsLearnEnglish] Database auto-backup scheduled every 1440 minutes, keeping up to 7 backups.
```

在游戏中用管理员身份执行:
```
/lea info
```

应看到:
```
===== 英语学习 信息 =====
版本: 1.0.7-SNAPSHOT
服务端版本: 1.21.4
版本兼容性: true
数据库: sqlite
======================================
```

#### 步骤 5：首次生成的目录结构

插件首次启动后，会在 `plugins/LetsLearnEnglish/` 下自动生成以下文件（目录名取自 `plugin.yml` 的插件名 `LetsLearnEnglish`，不是中文名）:

```
plugins/LetsLearnEnglish/
├── config.yml                    ← 主配置文件
├── ai_config.yml                 ← AI 对话：DeepSeek API 密钥（需手动创建，见下）
├── ai_scenarios.yml              ← AI 场景与难度提示词配置
├── achievements-config.yml       ← 成就开关/时间限制配置（唯一生效的成就配置文件）
├── achievements.yml              ← 旧版成就定义（已废弃，仅供参考，勿编辑）
├── playerdata.db                 ← SQLite 数据库
├── player_lang.yml               ← 玩家语言偏好（自动维护）
├── messages/
│   ├── zh.yml                    ← 中文语言文件
│   └── en.yml                    ← 英文语言文件
├── words/
│   ├── beginner.yml              ← 初级词库（317 词）
│   ├── intermediate.yml          ← 中级词库（349 词）
│   └── advanced.yml              ← 高级词库（330 词）
├── player_scenarios/             ← 玩家个人 AI 场景（按 UUID 落盘，导入分享码时生成）
│   └── <uuid>.yml
├── exports/                      ← 导出目录（管理员导出/报告落盘）
│   └── reports/                  ← /lea report 生成的报告 TXT
├── audio/                        ← TTS 发音音频缓存（目录总是创建；配置 VoiceRSS 密钥后存放 .wav）
└── backups/                      ← 数据库自动备份目录
    ├── playerdata.db.20260601-143022.bak
    └── ...
```

> `dialogues/` 目录为旧版功能残留，当前代码零加载点，已废弃——目录结构里不再列出。

**配置 AI 对话（可选）**：首次启动不会自动生成 `ai_config.yml`，需手动在 `plugins/LetsLearnEnglish/` 下创建并填入 DeepSeek API 密钥:

```yaml
# plugins/LetsLearnEnglish/ai_config.yml
api-key: "你的DeepSeek_API密钥"
```

未配置时控制台会告警 `ai_config.yml not found!`，AI 对话不可用（其余功能不受影响）；改好后可执行 `/lea reload` 或点击场景选择页的「刷新 API 密钥」按钮热加载。

### 4.2 配置文件权限建议

```bash
# Linux 上建议设置
chown -R minecraft:minecraft plugins/LetsLearnEnglish/
chmod 755 plugins/LetsLearnEnglish/
chmod 644 plugins/LetsLearnEnglish/*.yml
chmod 644 plugins/LetsLearnEnglish/*.db
```

---

## 5. 服务端配置指南

### 5.1 主配置文件: `config.yml`

配置文件使用 YAML 格式，修改后执行 `/lea reload` 即可热重载，无需重启服务器。

#### 5.1.1 通用设置 (general)

```yaml
general:
  language: "zh"              # 默认语言: zh(中文) / en(英文)
  check-updates: true         # 启动时检查更新
  auto-save-interval: 5       # 自动保存间隔(分钟), 范围 1-60
  backup:
    enabled: true             # 启用数据库自动备份
    interval: 1440            # 备份间隔(分钟), 0 禁用
    max-backups: 7            # 最大保留备份数
```

> **调试开关说明**: `general.debug` 是历史遗留死键（代码零读取点），出厂 config.yml 中仍可能保留此行但调整无任何效果；如需 AI 对话的调试日志请用 `ai-chat.debug: true`。
>
> `check-updates: false` 时跳过启动联网检查（含玩家进服的更新提示），适合无网络的内网服务器。

#### 5.1.2 消息前缀 (prefix)

```yaml
prefix:
  zh: "&8[&b英语学习&8] &f"
  en: "&8[&bEnglish&8] &f"
```

> 颜色代码使用 `&` 符号:
> - `&0` 黑色, `&1` 深蓝, `&2` 深绿, `&3` 湖蓝, `&4` 深红
> - `&5` 紫色, `&6` 金色, `&7` 灰色, `&8` 深灰, `&9` 蓝色
> - `&a` 绿色, `&b` 天蓝, `&c` 红色, `&d` 粉色, `&e` 黄色, `&f` 白色
> - `&l` 粗体, `&o` 斜体, `&n` 下划线, `&k` 乱码, `&m` 删除线, `&r` 重置

#### 5.1.3 数据库设置 (database)

**SQLite 模式（默认，适合小型服务器）**:

```yaml
database:
  type: "sqlite"
  sqlite:
    filename: "playerdata.db"
```

**MySQL 模式（适合大型服务器/跨服互通）**:

```yaml
database:
  type: "mysql"
  mysql:
    host: "localhost"
    port: 3306
    database: "letslearnenglish"
    username: "root"
    password: "your_password_here"
    table-prefix: "lle_"
```

> **说明**: 出厂 config.yml 中可能保留 `pool-size: 10` 一行（历史遗留），它是**死键**——插件使用单连接 + synchronized 双检锁、无连接池，调整该项无实际效果。
>
> **MySQL 前置条件**: 需先手动创建数据库:
> ```sql
> CREATE DATABASE letslearnenglish DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
> ```

#### 5.1.4 单词学习系统 (word-system)

```yaml
word-system:
  enabled: true               # 模块总开关（三层语义：入口隐藏 / 事件静默 / 数据保留）
  daily-limit: 50             # 每日答题计数的发币上限, 0/负数不限制（超限后仍可答题, 仅停发金币）
  words-per-session: 10       # 每会话单词数, 范围 5-30
  answer-timeout: 30          # 答题超时(秒), 范围 10-120
  review:
    enabled: true
    intervals: [1, 6, 24, 72, 168]  # 复习间隔(小时): 1h, 6h, 1天, 3天, 7天
    mastery-threshold: 0.85         # 掌握阈值(0.0-1.0), 达到后退出复习
    mastered-review-interval-days: 30  # 已掌握单词的低频复习周期(天)
  modes:
    spelling:
      enabled: true
      case-sensitive: false   # 仅影响全词文本比较（choice 的 A/B/C/D 字母选择恒不敏感）
    choice:
      enabled: true
      option-count: 4         # 选项数量，读取时钳位 [2, 4]
      time-limit: 0           # 单题倒计时(秒): 0 = 跟随全局 answer-timeout；仅 choice 有此键
    fill_blank:
      enabled: true
      show-first-letter: true # false: 挖空卡不渲染首字母提示行
```

**要点（Phase 3 Wave 2 起已真接线，`/lea reload` 即时生效）**:

- `word-system.enabled: false` 时：主菜单【单词学习】【错题本】隐藏，`/le word`、`/le review`、`/le wrong` 拒绝；复习提醒停止、不再分配新单词挑战；已进行中的答题会话不打断，数据全保留。
- 三模式 `enabled` 开关：`/le word` 指定被关闭的模式会收到 `word.mode-disabled` 提示；三模式全关时无参 `/le word` 直接拒绝（不开死界面）；`/le word` 无参默认模式优先 `choice`（新手默认），choice 被关时按 `spelling → fill_blank` 回落。
- 判分参数（大小写 / 选项数 / 倒计时）在**开局时快照**，改动对下一局生效；提示显隐实时生效。
- 填空模式 canonical 键名为 `fill_blank`（下划线），旧名 `fill-blank`（连字符）仍被兼容读取（新名存在时优先）。
- 历史上曾存在过的 `modes.*.max-attempts`（每词尝试次数）键**已被删除**——从未被代码读取，配置文件中不再包含。

#### 5.1.5 每日金币上限 (money)

```yaml
money:
  daily-cap: 500              # 每人每天金币发放总额上限, 0 不限制; 次日自动清零
```

> 金币总闸的实际生效条件是三键叠加（内部 AND）：`progress-system.enabled` && `progress-system.rewards.enabled` && `progress-system.rewards.types.money.enabled`（默认 false，需 Vault）。超过每日上限后当天不再发币（答题可继续，只是不发奖励）。

#### 5.1.6 经济奖励系统 (progress-system.rewards.money)

```yaml
progress-system:
  enabled: true               # 进度系统伞开关（关闭后排行榜/成就/奖励三个子开关随之关闭）
  rewards:
    enabled: true             # 奖励总门（等效金币总闸）
    types:
      item:
        enabled: true         # 物品奖励开关（历史遗留键，当前无实际发放链）
      command:
        enabled: true         # 指令奖励开关（同上）
      exp:
        enabled: true         # 经验奖励开关（同上）
      money:
        enabled: false        # 需要 Vault 插件 + 经济插件
        vault-required: true
        base-amount: 10.0           # 每答对一题基础金币
        combo-bonus: true           # Combo 倍率是否影响金币
        session-complete-bonus: 50.0  # 会话完成额外奖励
        max-combo-bonus: 25.0        # 最高连击额外奖励
        review-multiplier: 1.2       # 复习金币倍率（复习比首学更值得鼓励，正向加成）
        review-penalty: false        # 复习答错是否扣钱（默认不扣，错题本已在纠错）
        review-penalty-amount: 5.0   # 复习答错扣多少
```

> 升版注意：`review-multiplier` 默认值已由旧版 `0.5`（惩罚复习）反转为 `1.2`；`review-penalty` 默认值已由 `true` 反转为 `false`。存量配置文件建议手动同步这两个默认值。
>
> `types.item` / `types.command` / `types.exp` 三键当前无实际发放链（历史遗留），仅 `types.money` 被实际读取。

#### 5.1.7 GUI 布局自定义 (gui-layout)

所有 GUI 界面的物品材质（Material）和位置（slot）都可在 `gui-layout` 段中自定义。

**slot 编号对照图**:

```
27 格箱子 (主菜单、个人主页等):
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│ 9 │10 │11 │12 │13 │14 │15 │16 │17 │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│18 │19 │20 │21 │22 │23 │24 │25 │26 │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘

54 格箱子 (成就墙、英语广场):
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │  ← 第一行
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│ 9 │10 │11 │12 │13 │14 │15 │16 │17 │  ← 第二行
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│18 │19 │20 │21 │22 │23 │24 │25 │26 │  ← 第三行
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│27 │28 │29 │30 │31 │32 │33 │34 │35 │  ← 第四行
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│36 │37 │38 │39 │40 │41 │42 │43 │44 │  ← 第五行
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│45 │46 │47 │48 │49 │50 │51 │52 │53 │  ← 第六行
└───┴───┴───┴───┴───┴───┴───┴───┴───┘
```

**主菜单示例配置**（9 个按钮，Phase 3 起为完整布局）:

```yaml
gui-layout:
  main-menu:
    word-learning:
      slot: 11
      material: "BOOK"
    dialogue-practice:
      slot: 13
      material: "PAPER"
    personal-profile:
      slot: 15
      material: "DIAMOND"
    english-square:
      slot: 17
      material: "PLAYER_HEAD"
    language-toggle:
      slot: 18
      material: "RED_BED"
    daily-challenge:
      slot: 10
      material: "WRITABLE_BOOK"
    duel:                        # Phase 3 新增：单词对决
      slot: 12
      material: "DIAMOND_SWORD"
    checkin:                     # Phase 3 新增：每日签到
      slot: 16
      material: "SUNFLOWER"
  # 另有 word-learning / english-square / leaderboard / profile / achievement-wall 等段
  # 可自定义对应界面物品的 slot 与 material（见 config.yml 注释）
```

> **注意**:
> - 主菜单按钮布局占用槽位 10-18，因此 `gui.menu-size` 最小为 27（非法值一律回落 27 并记 WARNING）。
> - 模块关闭时（`duel.enabled` / `checkin.enabled` 为 false）对应按钮不渲染，该槽回落灰玻璃占位。
> - 错题本按钮的槽位/材质（`main-menu.wrong-book`，默认 slot 14 / BOOKSHELF）也走本段配置，但 config.yml 出厂文件中未显式写出（走内置默认值）。
> - 可用 Material 完整列表: https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/Material.html
>
> 注意: 必须是 1.21+ 版本中存在的材质名称（如 `RED_STAINED_GLASS_PANE`，而非旧版 `STAINED_GLASS_PANE:14`）

### 5.2 成就配置文件: `achievements-config.yml`

每个成就可独立启用/禁用，并可设置限时获取窗口（**这是唯一生效的成就开关文件**）：

```yaml
achievements:
  first_word:
    enabled: true           # false: 无法获得也不显示
    time-limit:
      enabled: true         # 开启限时获取
      start: "2026-06-01 00:00"
      end: "2026-06-30 23:59"

  words_500:
    enabled: true
    time-limit:
      enabled: false        # 不限时
```

> 时间格式为 `yyyy-MM-dd HH:mm`，插件会在成就物品上自动显示倒计时/过期状态。
>
> 运行期真正生效的成就配置文件是 `achievements-config.yml`（由 `AchievementManager` 与成就墙 GUI 直读，文件名硬编码）。config.yml 中的 `progress-system.achievements.config-file` 键**无读取点（死键）**，改它不会切换配置文件——自定义文件名需改源码。

### 5.3 旧版成就定义文件: `achievements.yml`（已废弃）

`achievements.yml` 文件自带 `NOTE: This file is deprecated.` 头注，**仅作参考，请勿编辑**。成就定义（ID、中英文名称、描述、类别、目标）已硬编码在 `core/achievement/Achievement.java` 枚举中，奖励链已移除。管理员要调整的只有两类：

1. **启用/禁用与限时窗口** → 改 `achievements-config.yml`（见 5.2）。
2. **成就名称/描述** → 属于代码内枚举，需改源码（提交 Issue 或自行编译）。

当前共有 **22 个成就**（详见附录 A），按 5 类分组在成就墙展示：学习 / 复习 / 坚持 / 社交 / 财富。

### 5.4 语言文件: `messages/zh.yml` 和 `messages/en.yml`

语言文件支持 PlaceholderAPI 变量和颜色代码。版本标识为 `config-version: 13`。

> 版本升级机制：启动时若检测到本地语言文件的 `config-version` 低于插件内置版本，会自动用 JAR 内置文件**覆盖**本地文件（`saveResource(path, true)`）。自定义过语言文件的服主请在升级前备份，语言版本落后时自定义内容会被官方文件替换。

**新增英文翻译**（以 `en.yml` 为例）：

```yaml
general:
  no-permission: "{prefix}&cYou don't have permission to use this command!"

word:
  session-start: "{prefix}&aStarting new learning session! Difficulty: &e{difficulty}&a, Mode: &e{mode}"
```

> 占位符使用 `{key}` 格式，在插件内部动态替换。可用占位符取决于具体的消息键。

### 5.5 词库文件: `words/*.yml`

词库文件命名即词库名称。例如 `words/beginner.yml` 对应的词库名为 `beginner`。

```yaml
difficulty: "beginner"
description: "Beginner English Word Bank"

words:
  - english: "apple"
    chinese: "苹果"
    part_of_speech: "noun"
    phonetic: "/ˈæp.əl/"
    example_sentence: "I eat an apple every day."
    synonyms: ["fruit"]
    antonyms: []
    difficulty_level: 1
    reward: 15                  # 可选, 单独覆盖该单词的金币奖励（>0 时生效；内置三词库均未使用此字段）
```

> 词条必填字段只有 `english` 与 `chinese`（非空）；`part_of_speech` / `phonetic` / `example_sentence` / `difficulty_level` 等为模板约定字段（loader 可选）。

**添加自定义词库**:

1. 在 `plugins/LetsLearnEnglish/words/` 下新建一个 `.yml` 文件（如 `animals.yml`）
2. 按上述格式编写词汇列表
3. 执行 `/lea reload` 热加载
4. 玩家即可在词库选择 GUI 中看到新词库

> 词库显示名优先查语言文件 `word-bank.difficulty-<词库名>` 键；缺失时回落为「首字母大写化」的词库名。JAR 内置自动释放的只有 `beginner.yml` / `intermediate.yml` / `advanced.yml` 三个固定名，其余词库文件按 `*.yml` 自动发现。

### 5.6 对话文件: `dialogues/*.yml`（已废弃）

> **此功能已废弃**：`dialogues/` 目录为旧版（非 AI）对话系统的残留，当前代码零加载点，AI 情景对话已完全改由 `ai_scenarios.yml`（全局场景）+ `player_scenarios/`（玩家个人场景）驱动。请勿再新增 `dialogues/*.yml` 文件。

### 5.7 `/lea reload` 热重载范围

执行 `/lea reload` 会重新加载:
- `config.yml`（全部配置段，含模块开关 `enabled` 键——均为点读式，reload 即时生效）
- `achievements-config.yml`（成就开关与时间窗口；reload 会清空成就缓存，下次判定时重新读盘）
- `messages/zh.yml` 和 `messages/en.yml`（含语言版本落后时的自动升级覆盖）
- `words/` 下所有词库文件
- `gui-layout` 与 GUI 音效缓存（`gui.sounds.*`）
- 玩家数据缓存（reload 前会先对在线玩家落盘，防止缓存丢失）与档案缓存
- 每日金币累计缓存（`money.daily-cap` 计数清零重算）

> **不再重载/不再读取的部分**: `achievements.yml`（废弃文件，不参与运行）；`dialogues/*.yml`（幽灵目录，代码零加载点）。
>
> **AI 场景热加载**：`ai_scenarios.yml` 与个人场景的重新读取不在 `/lea reload` 中，而是走场景选择 GUI 的「刷新 API 密钥」按钮（权限与 `/le load_scenario` 对齐：`letslearnenglish.scenario.import`）——该按钮同时重载 API 密钥与场景文件。
>
> **赛季/社交/报告等新段**：无独立 reload 钩子，但均为点读式（每次使用时直读 `config.yml`），`/lea reload` 后改动立即生效（如 `season.enabled`、`social.feed.retention-days`、`report.period` 等）。

### 5.8 Phase 3 新增配置段

#### 5.8.1 AI 对话 (ai-chat) 与会话小结 (ai-chat.feedback)

```yaml
ai-chat:
  enabled: true               # 模块门：false 时主菜单【AI 对话】隐藏、/le dialogue 拒绝；
                              # 进行中的对话在收到下一条消息时自动退出（不吞消息）
  api-url: "https://api.deepseek.com/v1/chat/completions"
  model: "deepseek-chat"
  max-context-pairs: 6        # 携带的最近对话轮数上限
  max-context-tokens: 2000    # 上下文 token 估算上限（System Prompt 永不裁剪）
  timeout-seconds: 15
  rate-limit-seconds: 3       # 玩家发消息频率限制
  max-input-length: 200
  debug: false                # AI 请求/响应详情日志（排查问题用）
  daily-round-limit: 30       # 每人每日 AI 有效轮次上限, 0 或负数 = 不限
  max-tokens: 128             # 单次回复最大 token
  count-min-rounds: 3         # 记一次"有效对话"所需的最少玩家消息数（成就/挑战计数用）
  feedback:                   # 对话小结 / AI 学习反馈（US-FB）
    enabled: true             # 对话结束时显示学习小结（本地统计，零 API 成本）
    ai-summary: true          # 追加一次 AI 总结调用（总评/纠错/推荐词），仅"达标会话"触发
    ai-timeout-seconds: 15    # AI 总结请求独立超时，超时降级为仅本地统计
    ai-max-tokens: 400        # AI 总结的 max_tokens（需容纳 JSON 结构化输出）
    transcript-max-chars: 4000  # 传给 AI 总结的对话文本上限
    max-corrections-display: 3  # 聊天栏最多展示的纠错条数（完整数据入库，历史详情页可见全部）
```

> 想彻底禁用 AI 对话请用 `ai-chat.enabled: false`（语义清晰）；`daily-round-limit: 0` 是不限而非禁用。
>
> AI 总结不计每日轮次、不占玩家冷却；`/le history` 详情页可回看完整小结与 AI 点评。

#### 5.8.2 场景分享 (scenario)

```yaml
scenario:
  max-prompt-length: 2000     # 场景 Prompt 最大长度（字符），超出拒绝保存/导入
```

#### 5.8.3 单词对决 (duel)

```yaml
duel:
  enabled: true               # 模块门：false 时主菜单按钮隐藏、发起/接受拒绝；
                              # 进行中的对局不打断，quit/gui 仍可用（自然收尾）；
                              # /lea duel cancel（管理员解散）保留可用
```

> 对决先得 5 分者胜；每题倒计时复用 `word-system.answer-timeout`（钳位 [5, 300] 秒）；BossBar 倒计时条由 `feedback.countdown.duel-bossbar` 控制。

#### 5.8.4 每日挑战 (daily-challenges)

```yaml
daily-challenges:
  enabled: true               # 模块门
  challenges-per-day: 3       # 每天分配的行为挑战数, 读取时钳位 [1, 4]（可用类型不足时按可用数分配）
  word-count: 5               # 每日单词挑战数量（从词库随机）
  word-challenge-reward: 100.0  # 完成所有单词挑战后奖励金币
  rewards:                    # 各行为挑战的奖励金币（缺键/非法/≤0 回落内置默认）
    answer_10: 30             # 答对 10 题
    session_3: 50             # 完成 3 次会话
    combo_5: 25               # 达成 5 连击
    ai_chat_5: 40             # 完成 5 轮 AI 对话
```

> 挑战奖励走 `money.daily-cap` 与金币总闸（无经济时不发币，界面提示 economy-disabled）。

#### 5.8.5 每日签到 (checkin)

```yaml
checkin:
  enabled: true               # 模块门：false 时主菜单按钮隐藏、/le checkin 拒绝；
                              # 签到记录保留（停用期间连续签到自然断签属预期语义）
```

> 签到金币按连续天数递增（第 1-7 天：10 / 20 / 30 / 50 / 70 / 85 / 100；超过 7 天按 7 天循环）。奖励金额为代码内置，不受 config 配置。断签后重新从第 1 天计。

#### 5.8.6 新手引导 (onboarding)

```yaml
onboarding:
  enabled: true               # 引导线总开关（false 时不给引导书、拦截不生效、无任何提醒）
  session-words: 3            # 迷你会话单词数（复用选择题模式；答错原地重试，不判错不推进）
  give-guide-book: true       # 首次进服发放《新手引导手册》(WRITTEN_BOOK)
  require-profile-step: true  # 第 3 步（打开个人主页）是否必需；false = 迷你会话完成即整段完成
  remove-book-on-complete: true  # 引导完成时收回手册；false = 留作纪念（翻页显示毕业页）
  reminder-on-join: true      # 未完成引导的玩家进服提醒（同一玩家同一自然日至多一条）
```

> 引导 3 步：选词库 → 3 词迷你会话 → 查看成就（`/le guide` 可查看进度或补领手册）。

#### 5.8.7 广场社交 (social) 与英语广场 (english-square)

```yaml
social:
  enabled: true               # 广场社交总门：false 时不产生动态、动态按钮隐藏、鼓励无效、
                              # 进服摘要不发
  feed:
    retention-days: 7         # 动态保留天数，过期动态连同其鼓励记录一并清理；0 = 永久保留（不推荐）
  cheer:
    enabled: true             # 鼓励按钮开关
    click-debounce-ms: 750    # 连点防抖（毫秒），防宏连点
  notify-on-join: true        # 进服提示"离线期间收到的鼓励"

english-square:
  default-mode: "feed"        # 广场打开时的默认模式: feed（动态流，推荐）/ all（玩家列表）
                              # 非法值回落 feed
```

> 动态每人每日上限 3 条（重复事件按天合并），鼓励一人对一条动态仅一次；鼓励零经济收益（杜绝刷币旁路）。

#### 5.8.8 学习报告 (report)

```yaml
report:
  enabled: true               # /lea report 功能开关；false 时命令明确提示（非静默无操作）
  period: "week"              # 默认统计周期: week(本周一~今天) / lastweek(上一完整周)
  language: "zh"              # 报告文件语言（给家长看，独立于玩家与执行者语言）
  minutes-per-question: 0.5   # 时长估算系数（每题折合分钟数）
  keep-days: 30               # 报告文件保留天数（exports/reports/ 清理阈值），0 = 不清理
  respect-privacy: false      # 是否尊重玩家隐私开关（showInSquare）。
                              # false（默认）= 报告属管理/监护通道，不受玩家展示开关约束；
                              # true = 隐身玩家不出现在批量报告、单报拒发
  batch:
    max-players: 50           # /lea report all 的单次最大玩家数（超出截断并标注）
  weak-words-limit: 5         # 错题列表截断长度
```

> 报告文件为纯文本 TXT（UTF-8 + BOM + CRLF），落 `plugins/LetsLearnEnglish/exports/reports/`，由老师/服主自行转发给家长。命令：`/lea report <玩家|all> [week|lastweek|<N>d]`。

#### 5.8.9 赛季系统 (season)

```yaml
season:
  enabled: false              # 默认关（运营属性功能，先让用户确认再开）
  duration-days: 30           # 单季时长（天）
  anchor-date: "2026-11-02"   # 首季开始日（服务器本地时区，yyyy-MM-dd）。
                              # 修改本键 = 重定义全局赛季轴，慎改；
                              # 锚点在未来时全系统视为"赛季未开启"，所有赛季功能休眠
  rewards:                    # 结算奖励分层（金币；需 Vault，无 Vault 只公告不发钱）
    enabled: true
    top1: 5000.0
    top3: 2000.0              # 第 2-3 名
    top10: 500.0              # 第 4-10 名
    participated: 0.0         # 参与奖（>0 时窗口内有过会话即发；含隐身者）
  announce: true              # 开始/结束是否全服广播（跨服经 proxy.server-name 子通道）
```

> 赛季奖励不走 `money.daily-cap`（一次性、有排名门槛、台账幂等防重），直发 Vault + lifetime 记账。多子服连同一数据库时必须与主机同时区。

#### 5.8.10 班级系统 (class-system)

<!-- TODO: 待确认：班级系统为 Wave 4 并行施工项，当前仓库 config.yml 尚未包含本段；以下为已冻结规格，若最终实现有出入以发布版 config.yml 为准 -->

```yaml
class-system:
  enabled: false              # 默认关；启用后管理员可用 /lea class 建班并在班级榜展示
  max-name-length: 32         # 班级名最大长度
  max-members: 50             # 单班成员上限
```

> 管理命令：`/lea class create|delete|add|remove|list`（管理型，需 `letslearnenglish.admin`）；玩家命令：`/le class` 查看自己的班级与班级榜（当前赛季窗内成员学习分，尊重成员隐身设置）。

#### 5.8.11 跨服代理 (proxy)

```yaml
proxy:
  cross-server-announcements: true  # 是否启用跨服公告（成就解锁时广播到全服）
  cross-server-messages: true       # 是否启用跨服全局消息接收（接收来自其他子服的公告）
  server-name: ""                   # 本子服在代理网络中的服务器名（与 BungeeCord/Velocity 配置一致）。
                                    # 用于跨服广播回环去重（赛季结算/开始子通道）；留空 = 单服模式
```

> `cross-server-announcements` / `cross-server-messages` 检查代码后确认**当前无读取点**（通道注册后无条件广播/接收），改动无实际效果——如需彻底关闭跨服请移除代理端通道注册或让本服不装代理。`server-name` 是被实际读取的键（赛季广播去重），多子服建议显式配置。

#### 5.8.12 每日学习提醒 (daily-reminder)

```yaml
daily-reminder:
  enabled: true               # 总开关；false 时定时提醒与进服提醒都不再发送
  on-join: true               # 玩家进服时发送提醒（每自然日首次进服，次日去重）
  reminder-hours: [9, 14, 20] # 提醒时间（24 小时制小时数），可设多个
  message: "{prefix}&6是时候学英语了！输入 &e/le menu &6开始学习吧！"
                              # 支持 {player} 玩家名 / {prefix} 前缀占位符
```

#### 5.8.13 其他配置段（既有段落补全）

- **进度系统 (progress-system)**：
```yaml
progress-system:
  enabled: true               # 伞开关：false 时排行榜/成就/奖励三个子开关全部随之关闭（内部 AND）
  leaderboard:
    enabled: true             # 排行榜门：false 时 /le leaderboard、榜单 GUI、广场三榜按钮隐藏；
                              # 内存榜继续更新作"热备"，重开无需重启即恢复排名
    display-count: 10         # 排行榜显示人数（英语广场榜单模式一次拉取的条数）
    update-interval: 30       # （死键：刷新周期实际硬编码为 5 分钟，改动无效）
  achievements:
    enabled: true             # 成就门：false 时不再解锁新成就（无播报/音效/跨服广播）；
                              # 已解锁记录保留，重开后立即回归
    config-file: "achievements-config.yml"  # （死键：文件名硬编码，改动无效）
```
- **GUI 设置 (gui)**：`main-menu-title`（config.yml 中为死键，实际标题读语言文件同键 `gui.main-menu-title`）、`menu-size`（27/36/45/54，非法值回落 27）、`custom-textures`（历史键，无读取点）、`sounds.*`（含 `combo-milestone` / `countdown-tick` / `timeout`；配为 `none` 或空串表示该事件故意静音）。
- **反馈层 (feedback)**：`countdown.urgent-seconds`（紧急档阈值，默认 5）、`countdown.tick-sound-seconds`（最后 N 秒 tick 音，0 = 不播）、`countdown.duel-bossbar`（对决 BossBar 倒计时条）、`combo.pitch-enabled` / `combo.pitch-step` / `combo.pitch-max`（连击音高）、`combo.milestone-enabled`（连击里程碑提示）。
- **成就系统 (achievement-system)**：`unlock-sound`（解锁音效，留空/"none" 静音）。
- **TTS 发音 (tts)**：`enabled`、`cache-days`（音频缓存保留天数）、`voicerss-api-key`（留空仅显示音标不下载音频）、`pronounce-cooldown-seconds`（发音按钮冷却）。音频缓存落 `audio/` 目录。
- **难度等级 (difficulty-settings)**：当前无读取点——难度列表由 `words/` 目录自动发现，显示名走语言文件 `word-bank.difficulty-*` 键，改动本段无实际效果。
- **统计仪表盘 (statistics-server)**：`enabled`（默认 false）、`bind-address`、`port`（默认 8080）、`ip-whitelist`（支持 CIDR）、`auth.username` / `auth.password`（HTTP Basic Auth，默认 admin/changeme，务必修改）。
- **数据库版本 (config-version)**：主 config 当前为 `2`；插件对缺失的新键采用「读取时回落默认值」策略，无需迁移。

---

## 6. 常见问题排查与解决方案

### 6.1 插件无法加载

**症状**: 服务器启动后控制台无 `[LetsLearnEnglish]` 日志，或出现红色错误。

**排查步骤**:

1. **检查 Java 版本**
   ```bash
   java -version
   ```
   如果输出 `java version "1.8.0_xxx"` 或 `17.x`，你需要安装 **Java 21**。从 [Adoptium](https://adoptium.net/) 下载。

2. **检查服务端版本**
   服务端核心必须是 Paper 1.21+ 或 Spigot 1.21+。在控制台执行:
   ```
   version
   ```

3. **检查 JAR 文件完整性**
   确认 JAR 文件大小正常（约 0.6-0.7 MB，约 200 个文件），尝试重新下载。

4. **查看完整错误日志**
   检查 `logs/latest.log` 文件查找 `LetsLearnEnglish` 相关的错误堆栈。

### 6.2 GUI 界面打不开 / 点击没反应

**症状**: 输入 `/le menu` 后界面一闪而过，或完全没有反应。

**可能原因与解决**:

| 原因 | 解决方案 |
|------|---------|
| 使用了 1.20 及以下的服务端 | 本插件要求 Paper/Spigot 1.21+ 与 Java 21，请先升级服务端（见第 2 章） |
| 存在 GUI 插件冲突 | 检查 `plugins/` 目录下是否有其他 GUI 管理插件（如 DeluxeMenus），尝试临时禁用排查 |
| 玩家正在答题会话中 | 必须先完成或停止当前答题会话才能返回主菜单（模块/模式关闭提示为 `module.disabled` / `word.mode-disabled`） |
| 物品被其他插件拦截 | 检查是否有反作弊/物品保护插件拦截了 GUI 交互 |

### 6.3 答题功能异常

**症状**: 倒计时重复、答对无反馈、关闭 GUI 后无法在聊天栏答题。

**解决**:

1. **开启调试模式**：AI 对话相关设置 `ai-chat.debug: true`，执行 `/lea reload`，观察控制台输出（`general.debug` 已废弃，无效果）。
2. **确保玩家不在其他插件交互中**：某些聊天过滤插件（如 ChatControl）可能会拦截答题输入。将该插件的答题频道添加到白名单。
3. **检查每日学习上限**：达到 `daily-limit`（默认 50）后当天仍可答题，但**不再发放金币奖励**（含每日金币上限 `money.daily-cap` 同理）。
4. **检查模块/模式开关**：`word-system.enabled: false` 或某一个模式被单独关闭时，对应入口会被拒绝，提示文案为 `module.disabled` / `word.mode-disabled`。

### 6.4 成就无法解锁

**症状**: 满足条件后未收到成就解锁提示。

**排查**:

1. 检查 `achievements-config.yml` 中该成就是否 `enabled: true`
2. 检查时间限制：如果 `time-limit.enabled: true`，确认当前时间在 start/end 范围内
3. 查看控制台是否有 `[LetsLearnEnglish]` 相关的 `WARNING` 或 `SEVERE`
4. 执行 `/lea reload` 清空成就缓存后重试

### 6.5 MySQL 连接失败

**症状**: 控制台报 `CommunicationsException` 或 `Access denied for user`。

**解决**:

```yaml
# 检查 MySQL 配置
database:
  type: "mysql"
  mysql:
    host: "localhost"          # 确认 MySQL 服务器地址
    port: 3306                 # 确认端口
    database: "letslearnenglish"  # 数据库必须已手动创建
    username: "root"
    password: "correct_password"
```

1. 确保 MySQL 服务器正在运行
2. 确保用户名/密码正确
3. 确保数据库 `letslearnenglish` 已手动创建
4. 检查防火墙是否放行 3306 端口

### 6.6 数据库文件损坏

**症状**: 控制台报 `database disk image is malformed`（SQLite）。

**恢复方案**:

1. 停止服务器
2. 进入 `plugins/LetsLearnEnglish/backups/` 目录
3. 找到最新的备份文件（按时间戳命名）
4. 将备份文件复制覆盖 `playerdata.db`:
   ```bash
   cp backups/playerdata.db.20260601-143022.bak playerdata.db
   ```
5. 重启服务器

> 备份机制自带完整性校验：备份复制前会执行 `PRAGMA wal_checkpoint(TRUNCATE)` 落盘 WAL，复制后执行 `PRAGMA quick_check`，校验失败会自动删除该备份文件并告警（避免生成损坏备份）。

### 6.7 内存溢出

**症状**: 服务器崩溃报 `OutOfMemoryError`。

> 插件自身内存占用极低（~15-30MB），如遇 OOM 通常不是本插件导致，但可采取以下优化:

1. 降低 `auto-save-interval`：从默认 5 分钟改为 10-15 分钟
2. 禁用自动备份：设置 `general.backup.enabled: false`
3. 若装了 PlaceholderAPI，占位符解析结果自带 30 秒缓存，通常无需额外调整

（注：`database.mysql.pool-size` 为死键，插件使用单连接无连接池，调整该项无效果。）

### 6.8 调试日志启用

需要提交 Bug Report 时，请提供以下信息:

1. 启用调试模式：AI 对话相关设置 `ai-chat.debug: true`（`general.debug` 已废弃，无读取点）→ `/lea reload`
2. 复现问题
3. 收集日志：
   ```bash
   # 在服务器控制台
   pastebin upload logs/latest.log
   ```
4. 同时提供 `/lea info` 的输出

---

## 7. 更新与维护流程

### 7.1 更新插件

#### 步骤 1: 备份

```bash
# 停止服务器前备份
cp -r plugins/LetsLearnEnglish/ ~/lle_backup_$(date +%Y%m%d)/
```

或者在游戏中执行:
```
/lea export
```
导出当前在线玩家数据到 `plugins/LetsLearnEnglish/exports/` 目录下的（P3-07：原根目录已迁移 exports/） `export_YYYYMMDD_HHmmss.csv`（仅导出当前在线玩家的数据，CSV 格式）。

#### 步骤 2: 更新 JAR

```bash
# 删除旧 JAR
rm "plugins/Let's Learn English!"-*.jar

# 放置新 JAR
cp ~/downloads/"Let's Learn English!"-1.0.7-SNAPSHOT.jar plugins/
```

#### 步骤 3: 检查配置文件

每次更新后，检查 `config.yml` 中的 `config-version` 是否与新版兼容（当前为 `2`）。主 config 采用「读取时回落默认值」策略，新增键无需手动迁移；但语言文件（`messages/*.yml`）版本落后时会被 JAR 内置文件**自动覆盖**，请先备份自定义内容。另注意部分历史键的默认值发生过反转（如 `review-multiplier` / `review-penalty`），存量文件建议手动对齐新默认。

#### 步骤 4: 重启服务器

```bash
stop
# 重新启动服务器
```

### 7.2 日常维护

| 任务 | 频率 | 方法 |
|------|------|------|
| 数据库备份 | 每日（自动） | 默认 1440 分钟自动备份，建议额外手动备份 |
| 日志检查 | 每周 | 查看 `logs/latest.log`，搜索 `LetsLearnEnglish` |
| 过期备份清理 | 每月 | 删除 `backups/` 中超过一个月的老备份，或降低 `max-backups` |
| 词库更新 | 按需 | 修改 `words/*.yml` 后 `/lea reload` |
| 报告文件清理 | 自动 | `exports/reports/` 下超过 `report.keep-days`（默认 30 天）的 TXT 在下次生成报告时惰性清理 |
| 动态流清理 | 自动 | `social.feed.retention-days`（默认 7 天）过期的学习动态连同鼓励记录一并清理 |

### 7.3 安全最佳实践

1. **限制管理权限**: 仅将 `letslearnenglish.admin` 权限授予信任的管理员；家长/老师角色只需 `letslearnenglish.report`（仅可生成学习报告，不泄漏 reset/reload/broadcast 等管理能力）
2. **配置文件权限**: 确保 `config.yml` 等文件不被普通用户通过 FTP 修改
3. **数据库密码**: 使用 MySQL 时不要使用 `root` 账户，创建专用低权限账户:
   ```sql
   CREATE USER 'lle_plugin'@'localhost' IDENTIFIED BY 'secure_password';
   GRANT SELECT, INSERT, UPDATE, DELETE ON letslearnenglish.* TO 'lle_plugin'@'localhost';
   FLUSH PRIVILEGES;
   ```
4. **定期导出**: 使用 `/lea export` 定期导出玩家数据到独立文件
5. **关闭更新检查**: 内网服务器设置 `general.check-updates: false`
6. **Web 仪表盘**: 如启用 `statistics-server`，务必修改默认密码（admin/changeme）并保持 `ip-whitelist` 最小化

### 7.4 卸载插件

1. 在控制台执行: `/lea export`（导出数据备份）
2. 停止服务器
3. 删除 `plugins/Let's Learn English!-*.jar`
4. 保留 `plugins/LetsLearnEnglish/` 文件夹（含数据库和配置），或整个删除
5. 重启服务器

---

## 8. 命令行指令参考

### 8.1 玩家指令 `/le` (别名: `/learnenglish`, `/learn`, `/english`)

| 命令 | 说明 | 权限 |
|------|------|------|
| `/le menu` | 打开英语学习主菜单 | `letslearnenglish.use` |
| `/le` | 同上，无参数默认打开主菜单 | `letslearnenglish.use` |
| `/le word` | 打开词库选择 GUI | `letslearnenglish.word.*` |
| `/le word <词库名>` | 直接开始指定词库答题（默认模式：优先 choice） | `letslearnenglish.word.*` |
| `/le word <词库名> <模式>` | 指定词库和模式开始答题 | `letslearnenglish.word.*` |
| `/le review` | 查看待复习单词数量及开始复习 | `letslearnenglish.word.*` |
| `/le wrong` | 查看错题本（答错 3+ 次的困难单词） | `letslearnenglish.word.*` |
| `/le dialogue` | 打开 AI 情景对话场景选择菜单 | `letslearnenglish.dialogue.*` |
| `/le dialogue <场景名>` | 直接开始指定场景对话（再次执行可退出当前对话） | `letslearnenglish.dialogue.*` |
| `/le history` | 查看 AI 对话历史（详情页含会话小结与 AI 点评） | `letslearnenglish.dialogue.*` |
| `/le save_scenario <名称>` | 将当前对话场景导出为分享码 | `letslearnenglish.dialogue.*` |
| `/le load_scenario <编码>` | 导入分享的场景码（保存为个人场景） | `letslearnenglish.scenario.import` |
| `/le progress` | 查看个人主页 | `letslearnenglish.progress.*` |
| `/le profile` | 查看自己的个人主页 | `letslearnenglish.progress.*` |
| `/le profile <玩家名>` | 查看指定玩家的个人主页（隐身玩家会被拒绝） | `letslearnenglish.progress.*` |
| `/le stats` | 查看学习统计（同个人主页） | `letslearnenglish.progress.*` |
| `/le leaderboard` | 查看排行榜（金币/单词/连击/赛季榜） | `letslearnenglish.progress.*` |
| `/le challenge` | 查看今日挑战进度（全部完成后重开庆祝页） | `letslearnenglish.use` |
| `/le square [模式]` | 进入英语广场（模式: feed/weekly/monthly/alltime/all） | `letslearnenglish.use` |
| `/le duel <玩家名>` | 发起单词对决；`accept` 接受 / `quit` 退出 / `gui` 重开界面 | `letslearnenglish.use` |
| `/le checkin` | 每日签到获取金币 | `letslearnenglish.use` |
| `/le season` | 查看当前赛季、剩余时间与你的排名 | `letslearnenglish.use` |
| `/le guide` | 查看新手引导进度 / 重新领取引导手册 | `letslearnenglish.use` |
| `/le class` | 查看我的班级与班级榜 <!-- TODO: 待确认（班级系统 Wave 4 施工中；若最终未上线将随该批移除） --> | `letslearnenglish.use` |
| `/le help` | 查看帮助 | `letslearnenglish.use` |

> 补充说明:
> - 表中"权限"列为节点归属参考——`use` / `word.*` / `dialogue.*` / `progress.*` 当前为声明性节点（不加限制即可使用，详见 8.3 节说明）；实际强制执行的节点已在表中标注专名。
> - `/le duel cancel <玩家名>` 仅对拥有 `letslearnenglish.admin` 的玩家可见与执行（管理员解散通道，功能等同 `/lea duel cancel`）。
> - 所有子命令在对应模块被关闭（如 `duel.enabled: false`）时会收到统一提示「该功能已被服务器管理员关闭」。

**答题模式参数**:

| 参数 | 模式 | 说明 |
|------|------|------|
| `spelling` | 拼写模式 | 看中文输入英文单词 |
| `choice` | 选择模式 | 看中文从选项中选出正确答案（无参默认模式） |
| `fill_blank` | 填空模式 | 看英文句子填入缺失单词（旧名 `fill-blank` 兼容） |

**可用场景参数**（全局内置 6 个，另可含个人场景 key 前缀 `personal:`）:
- `airport` — 机场值机
- `restaurant` — 餐厅点餐
- `shopping` — 购物中心
- `hotel` — 酒店入住
- `hospital` — 医院就诊
- `free_talk` — 自由对话

**示例**:
```
/le word beginner choice        # 初级词库，选择题模式
/le word intermediate spelling  # 中级词库，拼写模式
/le dialogue restaurant         # 餐厅对话
/le profile Notch               # 查看 Notch 的个人主页
/le duel Steve                  # 向 Steve 发起单词对决
/le square feed                 # 直达广场动态流
```

### 8.2 管理员指令 `/lea` (别名: `/learnadmin`)

| 命令 | 说明 | 权限 |
|------|------|------|
| `/lea reload` | 热重载所有配置、词库、音效缓存 | `letslearnenglish.admin` |
| `/lea export` | 导出在线玩家学习数据为 CSV 文件（落 exports/ 目录） | `letslearnenglish.admin` |
| `/lea reset <玩家名>` | 重置指定玩家的学习数据 | `letslearnenglish.admin` |
| `/lea info` | 查看插件版本和运行状态信息 | `letslearnenglish.admin` |
| `/lea exportwordbank <难度>` | 导出词库为 YAML 文件（落 `exports/`） | `letslearnenglish.admin` |
| `/lea importwordbank <文件名.yml>` | 从 `exports/` 导入词库 YAML（含校验与去重） | `letslearnenglish.admin` |
| `/lea broadcast <消息>` | 通过代理向所有子服广播（需 Velocity / BungeeCord） | `letslearnenglish.admin` |
| `/lea duel cancel <玩家名>` | 强制解散该玩家的单词对决（不判胜负） | `letslearnenglish.admin` |
| `/lea report <玩家\|all> [week\|lastweek\|<N>d]` | 生成家长/老师学习报告（TXT，落 `exports/reports/`） | `letslearnenglish.report` 或 `letslearnenglish.admin` |
| `/lea class create\|delete\|add\|remove\|list` | 班级管理（需启用班级系统） <!-- TODO: 待确认（班级系统 Wave 4 施工中；权限节点名以待最终实现为准，预计仍为 admin，或另设 letslearnenglish.class） --> | `letslearnenglish.admin` |
| `/lea ignore-update` | 忽略本次启动的更新提醒 | `letslearnenglish.admin` |
| `/lea help` | 查看管理命令帮助 | `letslearnenglish.admin` |

> **权限最小化设计**: `plugin.yml` 中 `/lea` 命令**故意不设命令级权限**，由 AdminCommand 逐子命令判定——只有 `letslearnenglish.report` 权限的老师/家长代表可执行 `/lea report`，而 reset/reload/broadcast 等仍被拒绝；tab 补全同样按权限过滤。

**示例**:
```
/lea reload                     # 修改配置后无需重启
/lea export                     # 导出 CSV 格式的在线玩家数据
/lea reset Steve                # 重置玩家 Steve 的数据
/lea info                       # 查看版本、数据库类型等
/lea report Anna week           # 生成 Anna 的本周学习报告
/lea report all                 # 全服批量对比报告（上限 50 人，超出截断标注）
/lea duel cancel Steve          # 强制解散 Steve 所在的对决
```

### 8.3 权限节点完整列表

| 权限节点 | 说明 | 默认授予 |
|----------|------|---------|
| `letslearnenglish.use` | 使用基本学习功能（声明性节点，见下方说明） | 所有玩家 (true) |
| `letslearnenglish.admin` | 使用管理员功能（实际强制执行） | OP (op) |
| `letslearnenglish.word.*` | 使用所有单词学习功能（声明性节点） | 所有玩家 (true) |
| `letslearnenglish.dialogue.*` | 使用所有 AI 情景对话功能（声明性节点） | 所有玩家 (true) |
| `letslearnenglish.progress.*` | 查看所有进度信息（声明性节点） | 所有玩家 (true) |
| `letslearnenglish.scenario.import` | 导入/管理个人场景（`/le load_scenario`、场景管理 GUI、刷新 API 密钥；实际强制执行） | OP (op) |
| `letslearnenglish.report` | 生成学习报告（`/lea report`，可单独授予老师/家长代表；实际强制执行） | OP (op) |

> **强制执行范围说明**: 代码中实际做权限判定的只有 `letslearnenglish.admin`、`letslearnenglish.scenario.import`、`letslearnenglish.report` 三个节点（前者门控 `/lea` 全部管理子命令与 `/le duel cancel`，后两者见上表）。`use` / `word.*` / `dialogue.*` / `progress.*` 四个节点在 `plugin.yml` 中声明（默认所有玩家拥有），供权限管理插件做分组管理，但当前版本代码未对其进行判定——**玩家功能默认对所有人开放**，无法用这几个节点单独禁用某类功能（如需关闭请用模块开关，见 5.8 节）。

**LuckPerms 权限配置示例**:
```
# 授予玩家基本使用权限（通常不需要，默认已有）
/lp user Steve permission set letslearnenglish.use true

# 授予管理员
/lp user Admin permission set letslearnenglish.admin true

# 授予老师：只发报告权限（不含 reset/reload 等）
/lp user Teacher permission set letslearnenglish.report true

# 授予个人场景导入权限
/lp user Steve permission set letslearnenglish.scenario.import true

# 授予组
/lp group default permission set letslearnenglish.use true
/lp group admin permission set letslearnenglish.admin true
```

### 8.4 可选 PlaceholderAPI 占位符

如果安装了 PlaceholderAPI（标识符 `lle`），可以使用以下占位符（在其他插件如计分板、TAB 中显示）:

| 占位符 | 说明 |
|--------|------|
| `%lle_score%` | 学习总分 |
| `%lle_words%` | 已学单词数（只计答对） |
| `%lle_accuracy%` | 正确率（如 `85.3%`） |
| `%lle_sessions%` | 累计学习会话数 |
| `%lle_correct%` | 累计答对题数 |
| `%lle_incorrect%` | 累计答错题数 |
| `%lle_coins_earned%` | 累计获得金币（仅本人可见，他人视角显示隐私占位） |
| `%lle_rank%` | 总榜名次（如 `#1`，未上榜显示"未上榜"/"Unranked"） |
| `%lle_streak%` | 当前连续学习天数 |
| `%lle_longest_streak%` | 历史最长连续学习天数 |
| `%lle_achievements%` | 成就进度（如 `3/22`） |
| `%lle_achievement_<id>%` | 单个成就解锁状态（`✓`/`✗`，如 `%lle_achievement_first_word%`） |
| `%lle_season%` | 当前赛季号（如 `3`；赛季未启用/未开启时为空串） |
| `%lle_season_days_left%` | 当前赛季剩余天数（赛季未启用时为空串） |
| `%lle_season_rank%` | 赛季榜名次（如 `#7`，未上榜显示"未上榜"；赛季未启用时为空串） |

> **隐私规则（R-43）**: 非本人视角查看时遵守玩家隐私开关——隐身（不展示于广场）玩家的占位符返回"已隐藏"（`rank` 例外显示"未上榜"）；未开启"展示学习结果"的玩家的分数族（score/words/accuracy/sessions/correct/incorrect）返回隐私占位。**建议计分板只对玩家本人解析 `%lle_*%`**。
>
> **性能**: 占位符结果按（玩家, 参数, 视角, 语言）缓存 30 秒，计分板高频刷新不会持续查库。
>
> 未知占位符返回 `null`（遵守 PlaceholderAPI 惯例，让其他 expansion 有机会解析）。

---

## 附录

### A. 默认配置文件完整内容

#### config.yml 完整参考

见 [5.1 节](#51-%E4%B8%BB%E9%85%8D%E7%BD%AE%E6%96%87%E4%BB%B6-configyml) 及插件生成的默认文件。建议时常对比官方最新版与本地版本的差异。

#### 内置成就列表（22 个）

成就定义硬编码在 `core/achievement/Achievement.java`；开关与限时窗口在 `achievements-config.yml` 配置（**旧 `achievements.yml` 已废弃，勿编辑**）。**成就解锁本身不再附带经验/物品/金币奖励**（旧奖励链已移除），解锁时仅播报（聊天 + Title + 音效 `achievement-system.unlock-sound` + 可选跨服广播）。

**学习类**:

| 成就 ID | 中文名 | 条件 |
|---------|--------|------|
| `first_word` | 初出茅庐 | 会话内答对第 1 个单词 |
| `words_25` | 小有所成 | 累计答对 25 个单词 |
| `words_100` | 单词达人 | 累计答对 100 个单词 |
| `words_500` | 学富五车 | 累计答对 500 个单词（前置：答题数 ≥100 且历史正确率 ≥70%） |
| `perfect_session` | 完美无缺 | 完成一次 100% 正确率的完整答题会话 |
| `streak_3` | 小试牛刀 | 一次会话中连续答对 3 题 |
| `streak_10` | 势如破竹 | 一次会话中连续答对 10 题 |
| `spelling_master` | 拼写达人 | 完成一次拼写模式的完整答题会话 |
| `sessions_10` | 坚持不懈 | 累计完成 10 次学习会话 |
| `sessions_50` | 学习标兵 | 累计完成 50 次学习会话（前置：先解锁「势如破竹」） |
| `accuracy_90` | 精准射手 | 累计答题 100 题以上且正确率 ≥90% |

**复习类**:

| 成就 ID | 中文名 | 条件 |
|---------|--------|------|
| `review_streak_7` | 温故知新 | 连续 7 天完成复习会话且每天至少答对 1 个复习单词 |
| `wrong_book_cleared` | 错题清零 | 通过错题练习累计攻克 5 个单词并清空错题本 |

**坚持类**:

| 成就 ID | 中文名 | 条件 |
|---------|--------|------|
| `daily_streak_3` | 连续三天 | 连续 3 天完成至少一次学习会话 |
| `daily_streak_7` | 一周全勤 | 连续 7 天完成至少一次学习会话 |
| `study_days_30` | 学而不厌 | 在不同的 30 天里完成过学习会话（无需连续） |

**社交类**:

| 成就 ID | 中文名 | 条件 |
|---------|--------|------|
| `ai_chat_10` | 初识 AI | 完成 10 次 AI 情景对话 |
| `ai_chat_50` | AI 伙伴 | 完成 50 次 AI 情景对话 |
| `duel_first_win` | 初战告捷 | 在一场打满的单词对决中拿下首胜（退赛/掉线不算） |
| `helpful` | 互助之星 | 鼓励 10 位不同玩家的学习动态 |

**财富类**:

| 成就 ID | 中文名 | 条件 |
|---------|--------|------|
| `coins_500` | 小有积蓄 | 累计获得 500 金币（前置：累计答题 ≥50） |
| `coins_5000` | 金库满载 | 累计获得 5000 金币（前置：先解锁「单词达人」） |

> 成就墙按 5 类分组展示（学习/复习/坚持/社交/财富），条目含解锁日期、进度行与前置提示；部分成就带进度行（如 `words_100` 显示"还差 X 个单词"）。

#### 内置 AI 场景对话列表

| 场景 key | 名称（中） | 说明 |
|----------|-----------|------|
| `airport` | 机场值机 | 护照验证 / 行李托运 / 座位安排 / 登机口 |
| `restaurant` | 餐厅点餐 | 迎宾 / 点餐 / 饮料 / 甜点 |
| `shopping` | 购物中心 | 尺码推荐 / 颜色价格 / 试衣间 |
| `hotel` | 酒店入住 | 确认预订 / 房间偏好 / 设施介绍 |
| `hospital` | 医院就诊 | 症状描述 / 就医建议 |
| `free_talk` | 自由对话 | 自由话题聊天练习 |

另有 3 个难度档（`easy` 简单 / `medium` 中等 / `hard` 困难），每个场景可用 `{scenario}` / `{difficulty}` 变量编写提示词，均可自由修改（`ai_scenarios.yml`）。

#### 内置词库列表

| 词库文件 | 等级 | 单词数 |
|---------|------|--------|
| `beginner` | 初级 | 317 词 |
| `intermediate` | 中级 | 349 词 |
| `advanced` | 高级 | 330 词 |

### B. 文件结构速查

```
plugins/LetsLearnEnglish/
├── config.yml                 ← 主配置文件 (修改后 /lea reload)
├── ai_config.yml              ← AI 对话 API 密钥 (需手动创建)
├── ai_scenarios.yml           ← AI 场景与难度提示词 (可自由修改)
├── achievements-config.yml    ← 成就开关和时间限制 (唯一生效的成就配置文件)
├── achievements.yml           ← 旧版成就定义 (已废弃，勿编辑)
├── playerdata.db              ← SQLite 数据库 (勿手动修改)
├── player_lang.yml            ← 玩家语言偏好 (自动维护)
├── messages/
│   ├── zh.yml                 ← 中文翻译
│   └── en.yml                 ← 英文翻译
├── words/
│   ├── beginner.yml           ← 初级词库 317 词 (可自定义添加新词库文件)
│   ├── intermediate.yml       ← 中级词库 349 词
│   └── advanced.yml           ← 高级词库 330 词
├── player_scenarios/          ← 玩家个人 AI 场景 (uuid.yml, 导入分享码时生成)
├── exports/                   ← 导出目录
│   ├── <difficulty>_export_<时间戳>.yml    ← /lea exportwordbank 词库导出
│   └── reports/               ← 报告落盘目录 (keep-days 清理)
│       ├── report_<玩家名>_<起>-<止>_<时刻>.txt   ← /lea report 单人报告
│       └── report_all_<起>-<止>_<时刻>.txt        ← /lea report all 批量报告
├── audio/                     ← TTS 发音音频缓存 (自动清理)
├── backups/                   ← SQLite 自动备份
│   └── playerdata.db.XXXXX.bak
└── export_YYYYMMDD_HHmmss.csv ← /lea export 导出的玩家数据 CSV (exports/ 目录，P3-07)
```

### C. 技术支持与反馈

- **GitHub Issues**: https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English-/issues
- **功能请求**: 请在 Issues 中使用 `Feature Request` 标签
- **Bug 报告**: 请附上 `/lea info` 输出、`logs/latest.log` 相关片段、以及复现步骤

---

> 本文档将持续更新，建议关注 GitHub 仓库获取最新版本。