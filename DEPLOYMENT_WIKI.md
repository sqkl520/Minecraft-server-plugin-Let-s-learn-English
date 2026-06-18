# Let's Learn English! — 服务器管理员部署 Wiki

> **适用版本**: 1.0.6+
> **最后更新**: 2026-06-01
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
| **单词学习系统** | 三种答题模式（拼写 / 选择 / 填空），支持连击 Combo 系统，基于艾宾浩斯遗忘曲线智能复习 |
| **情景对话系统** | 多场景英语对话练习（餐厅、机场、购物），支持 Citizens NPC 交互，自适应难度 |
| **进度与奖励系统** | 排行榜、成就系统、金币奖励（Vault）、经验奖励、物品奖励、指令奖励 |
| **英语广场** | 分页展示所有学习者的答题情况，点击查看他人个人主页 |
| **个人主页** | 学习统计（已学单词、正确率、学习次数、金币、成就），隐私设置 |
| **成就墙** | 54 格网格展示全部成就（含锁定/解锁状态），时间限制成就显示倒计时 |
| **词库选择 GUI** | 图形化选择学习词库（初级/中级/高级/自定义），支持动态热加载 |
| **中英双语界面** | 全界面 100% 双语覆盖，玩家可在主菜单一键切换语言 |
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
│  Core       │  WordManager / DialogueManager         │
│             │  ProgressManager / AchievementManager  │
├─────────────────────────────────────────────────────┤
│  Data       │  SQLite / MySQL  (via DatabaseManager) │
├─────────────────────────────────────────────────────┤
│  Listeners  │  ChatAnswer / GUIMenu / NPC / Join     │
├─────────────────────────────────────────────────────┤
│  API        │  LetsLearnEnglishAPI (供其他插件调用)  │
└─────────────────────────────────────────────────────┘
```

---

## 2. 系统环境要求

### 2.1 服务端要求

| 项目 | 最低要求 | 推荐配置 |
|------|---------|---------|
| **服务端核心** | Paper 1.20+ / Spigot 1.20+ | Paper 1.20.4 |
| **API 版本** | 1.20 | 1.20.4 |
| **Java 版本** | Java 17 | Java 17/21 |
| **操作系统** | Windows / Linux / macOS | Linux (推荐) |

### 2.2 硬件资源占用

| 指标 | 预估值 |
|------|--------|
| **内存占用** | ~15-30 MB（含连接池缓存） |
| **CPU 占用** | 极低（仅定时保存和复习通知有微量调度开销） |
| **磁盘占用** | ~2 MB（插件 JAR + 数据库 + 配置文件） |

> 插件所有定时任务（自动保存、复习通知、备份）均为异步执行，不会阻塞主线程影响服务器 TPS。

---

## 3. 前置依赖组件

### 3.1 必需依赖

**无硬性必需依赖。** 插件可独立运行。

### 3.2 软依赖（可选，增强功能）

| 插件 | 用途 | 下载 |
|------|------|------|
| **PlaceholderAPI** | 提供占位符变量，供其他插件（如计分板）显示学习数据 | [SpigotMC](https://www.spigotmc.org/resources/placeholderapi.6245/) |
| **Vault** | 经济系统对接，答题获得金币奖励 | [SpigotMC](https://www.spigotmc.org/resources/vault.34315/) |
| **Citizens** | NPC 创建工具，用于情景对话系统中的 NPC 交互 | [SpigotMC](https://www.spigotmc.org/resources/citizens.13811/) |

### 3.3 依赖加载顺序

```
Plugins 目录加载顺序（由 Bukkit 自动处理）:
  1. Vault（如有）
  2. PlaceholderAPI（如有）
  3. Citizens（如有）
  4. Let's Learn English! ← 本插件
```

> 插件的 `plugin.yml` 中已声明 `softdepend`，Bukkit 会自动确保软依赖插件先于本插件加载。

---

## 4. 详细安装步骤

### 4.1 首次安装

#### 步骤 1：获取插件 JAR 文件

从以下渠道获取最新版本的 JAR 文件：
- GitHub Releases: `https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English-/releases`
- 自行编译: `mvn clean package`（源码仓库中）

文件名为: `Let's Learn English!-{版本号}.jar`

#### 步骤 2：放置 JAR 文件

将 JAR 文件复制到服务器的 `plugins/` 目录中：

```
服务器根目录/
├── plugins/
│   ├── Let's Learn English!-1.0.6.jar  ← 放这里
│   ├── Vault.jar (可选)
│   ├── PlaceholderAPI.jar (可选)
│   └── Citizens.jar (可选)
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
plugman load "Let's Learn English!"
```
> 不推荐使用 PlugMan 进行首次安装，部分功能可能无法正确初始化。

#### 步骤 4：验证安装

服务器启动后，在控制台中应能看到以下日志输出：

```
[LetsLearnEnglish] ============================================
[LetsLearnEnglish]   Let's Learn English! v1.0.6
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
版本: 1.0.6
服务端版本: 1.20.4
版本兼容性: true
数据库: sqlite
======================================
```

#### 步骤 5：首次生成的目录结构

插件首次启动后，会在 `plugins/Let's Learn English!/` 下自动生成以下文件：

```
plugins/Let's Learn English!/
├── config.yml                    ← 主配置文件
├── achievements-config.yml       ← 成就开关/时间限制配置
├── achievements.yml              ← 成就定义（可自定义添加）
├── playerdata.db                 ← SQLite 数据库
├── player_lang.yml               ← 玩家语言偏好（自动维护）
├── messages/
│   ├── zh.yml                    ← 中文语言文件
│   └── en.yml                    ← 英文语言文件
├── words/
│   ├── beginner.yml              ← 初级词库
│   ├── intermediate.yml          ← 中级词库
│   └── advanced.yml              ← 高级词库
├── dialogues/
│   ├── restaurant.yml            ← 餐厅对话
│   ├── airport.yml               ← 机场对话
│   └── shopping.yml              ← 购物对话
└── backups/                      ← 数据库自动备份目录
    ├── playerdata.db.20260601-143022.bak
    └── ...
```

### 4.2 配置文件权限建议

```bash
# Linux 上建议设置
chown -R minecraft:minecraft plugins/Let\'s\ Learn\ English\!/
chmod 755 plugins/Let\'s\ Learn\ English\!/
chmod 644 plugins/Let\'s\ Learn\ English\!/*.yml
chmod 644 plugins/Let\'s\ Learn\ English\!/*.db
```

---

## 5. 服务端配置指南

### 5.1 主配置文件: `config.yml`

配置文件使用 YAML 格式，修改后执行 `/lea reload` 即可热重载，无需重启服务器。

#### 5.1.1 通用设置 (general)

```yaml
general:
  language: "zh"              # 默认语言: zh(中文) / en(英文)
  debug: false                # 调试模式: 控制台详细日志
  check-updates: true         # 启动时检查更新
  auto-save-interval: 5       # 自动保存间隔(分钟), 范围 1-60
  backup:
    enabled: true             # 启用数据库自动备份
    interval: 1440            # 备份间隔(分钟), 0 禁用
    max-backups: 7            # 最大保留备份数
```

> 调试模式 (`debug: true`) 会产生大量日志，仅建议在排查问题时临时开启。

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
    pool-size: 10
    table-prefix: "lle_"
```

> **MySQL 前置条件**: 需先手动创建数据库:
> ```sql
> CREATE DATABASE letslearnenglish DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
> ```

#### 5.1.4 单词学习系统 (word-system)

```yaml
word-system:
  enabled: true
  daily-limit: 50             # 每日学习上限, 0 不限制
  words-per-session: 10       # 每会话单词数, 范围 5-30
  answer-timeout: 30          # 答题超时(秒), 范围 10-120
  review:
    enabled: true
    intervals: [1, 6, 24, 72, 168]  # 复习间隔(小时): 1h, 6h, 1天, 3天, 7天
    mastery-threshold: 0.85         # 掌握阈值(0.0-1.0), 达到后退出复习
  modes:
    spelling:
      enabled: true
      max-attempts: 3
      case-sensitive: false
    choice:
      enabled: true
      option-count: 4
      time-limit: 30
    fill-blank:
      enabled: true
      show-first-letter: true
      max-attempts: 3
```

#### 5.1.5 经济奖励系统 (progress-system.rewards.money)

```yaml
money:
  enabled: false              # 需要 Vault 插件 + 经济插件
  vault-required: true
  base-amount: 10.0           # 每答对一题基础金币
  combo-bonus: true           # Combo 倍率是否影响金币
  session-complete-bonus: 50.0  # 会话完成额外奖励
  max-combo-bonus: 25.0        # 最高连击额外奖励
  review-multiplier: 0.5       # 复习金币倍率
  review-penalty: true         # 复习答错是否扣钱
  review-penalty-amount: 5.0   # 复习答错扣多少
```

#### 5.1.6 GUI 布局自定义 (gui-layout)

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

**主菜单示例配置**:

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
```

> **可用 Material 完整列表**: https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/Material.html
>
> 注意: 必须是 1.20+ 版本中存在的材质名称（如 `RED_STAINED_GLASS_PANE`，而非旧版 `STAINED_GLASS_PANE:14`）

### 5.2 成就配置文件: `achievements-config.yml`

每个成就可独立启用/禁用，并可设置限时获取窗口：

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

### 5.3 成就定义文件: `achievements.yml`

定义成就名称、描述、类别、目标和奖励：

```yaml
achievements:
  first_word:                        # 成就 ID
    name: "First Steps"              # 显示名称
    description: "Learn your first English word"
    category: "word"                 # 类别: word / dialogue / streak / special
    target: 1                        # 目标值
    reward:
      type: "exp"                    # 奖励类型: exp / item / command
      amount: 50                     # 数量

  word_master_50:
    name: "Word Master"
    description: "Learn 50 English words"
    category: "word"
    target: 50
    reward:
      type: "item"
      item: "DIAMOND"               # 物品奖励材质名
      amount: 1

  streak_7:
    name: "Weekly Warrior"
    description: "Maintain a 7-day learning streak"
    category: "streak"
    target: 7
    reward:
      type: "item"
      item: "EMERALD"
      amount: 1
```

### 5.4 语言文件: `messages/zh.yml` 和 `messages/en.yml`

语言文件支持 PlaceholderAPI 变量和颜色代码。版本标识为 `config-version: 3`。

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
    reward: 15                  # 可选, 单独覆盖该单词的金币奖励
```

**添加自定义词库**:

1. 在 `plugins/Let's Learn English!/words/` 下新建一个 `.yml` 文件（如 `animals.yml`）
2. 按上述格式编写词汇列表
3. 执行 `/lea reload` 热加载
4. 玩家即可在词库选择 GUI 中看到新词库

### 5.6 对话文件: `dialogues/*.yml`

```yaml
scene: "restaurant"
description: "Restaurant Conversation Practice"

dialogues:
  - npc: "Waiter"
    text: "Good evening! Welcome to our restaurant."
    player_responses:
      - "A table for two, please."
      - "I'd like to see the menu."
```

### 5.7 `/lea reload` 热重载范围

执行 `/lea reload` 会重新加载:
- `config.yml`
- `achievements-config.yml`
- `achievements.yml`
- `messages/zh.yml` 和 `messages/en.yml`
- `words/` 下所有词库文件
- `dialogues/` 下所有对话文件
- GUI 音效缓存
- 成就数据缓存和档案缓存

---

## 6. 常见问题排查与解决方案

### 6.1 插件无法加载

**症状**: 服务器启动后控制台无 `[LetsLearnEnglish]` 日志，或出现红色错误。

**排查步骤**:

1. **检查 Java 版本**
   ```bash
   java -version
   ```
   如果输出 `java version "1.8.0_xxx"`，你需要安装 Java 17+。从 [Adoptium](https://adoptium.net/) 下载。

2. **检查服务端版本**
   服务端核心必须是 Paper 1.20+ 或 Spigot 1.20+。在控制台执行:
   ```
   version
   ```

3. **检查 JAR 文件完整性**
   确认 JAR 文件大小正常（约 2-5 MB），尝试重新下载。

4. **查看完整错误日志**
   检查 `logs/latest.log` 文件查找 `LetsLearnEnglish` 相关的错误堆栈。

### 6.2 GUI 界面打不开 / 点击没反应

**症状**: 输入 `/le menu` 后界面一闪而过，或完全没有反应。

**可能原因与解决**:

| 原因 | 解决方案 |
|------|---------|
| 玩家没有 `letslearnenglish.use` 权限 | 权限管理插件中授予该权限（默认所有玩家拥有） |
| 存在 GUI 插件冲突 | 检查 `plugins/` 目录下是否有其他 GUI 管理插件（如 DeluxeMenus），尝试临时禁用排查 |
| 玩家正在答题会话中 | 必须先完成或停止当前答题会话才能返回主菜单 |
| 物品被其他插件拦截 | 检查是否有反作弊/物品保护插件拦截了 GUI 交互 |

### 6.3 答题功能异常

**症状**: 倒计时重复、答对无反馈、关闭 GUI 后无法在聊天栏答题。

**解决**:

1. **开启调试模式**：设置 `general.debug: true`，执行 `/lea reload`，观察控制台输出。
2. **确保玩家不在其他插件交互中**：某些聊天过滤插件（如 ChatControl）可能会拦截答题输入。将该插件的答题频道添加到白名单。
3. **检查每日学习上限**：如果玩家已达到 `daily-limit`（默认 50），当天无法继续学习。

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
2. 进入 `plugins/Let's Learn English!/backups/` 目录
3. 找到最新的备份文件（按时间戳命名）
4. 将备份文件复制覆盖 `playerdata.db`:
   ```bash
   cp backups/playerdata.db.20260601-143022.bak playerdata.db
   ```
5. 重启服务器

### 6.7 内存溢出

**症状**: 服务器崩溃报 `OutOfMemoryError`。

> 插件自身内存占用极低（~15-30MB），如遇 OOM 通常不是本插件导致，但可采取以下优化:

1. 降低 `auto-save-interval`：从默认 5 分钟改为 10-15 分钟
2. 降低 `database.mysql.pool-size`：从 10 改为 5
3. 禁用自动备份：设置 `general.backup.enabled: false`

### 6.8 调试日志启用

需要提交 Bug Report 时，请提供以下信息:

1. 启用调试模式：`general.debug: true` → `/lea reload`
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
cp -r plugins/Let\'s\ Learn\ English\!/ ~/lle_backup_$(date +%Y%m%d)/
```

或者在游戏中执行:
```
/lea export
```
导出玩家数据到 `plugins/Let's Learn English!/exports/` 目录。

#### 步骤 2: 更新 JAR

```bash
# 删除旧 JAR
rm plugins/Let\'s\ Learn\ English\!-*.jar

# 放置新 JAR
cp ~/downloads/Let\'s\ Learn\ English\!-1.0.7.jar plugins/
```

#### 步骤 3: 检查配置文件

每次更新后，检查 `config.yml` 中的 `config-version` 是否与新版兼容。插件会自动合并新增的配置键，但建议手动对比确认。

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

### 7.3 安全最佳实践

1. **限制管理权限**: 仅将 `letslearnenglish.admin` 权限授予信任的管理员
2. **配置文件权限**: 确保 `config.yml` 等文件不被普通用户通过 FTP 修改
3. **数据库密码**: 使用 MySQL 时不要使用 `root` 账户，创建专用低权限账户:
   ```sql
   CREATE USER 'lle_plugin'@'localhost' IDENTIFIED BY 'secure_password';
   GRANT SELECT, INSERT, UPDATE, DELETE ON letslearnenglish.* TO 'lle_plugin'@'localhost';
   FLUSH PRIVILEGES;
   ```
4. **定期导出**: 使用 `/lea export` 定期导出玩家数据到独立文件
5. **关闭更新检查**: 内网服务器设置 `general.check-updates: false`

### 7.4 卸载插件

1. 在控制台执行: `/lea export`（导出数据备份）
2. 停止服务器
3. 删除 `plugins/Let's Learn English!-*.jar`
4. 保留 `plugins/Let's Learn English!/` 文件夹（含数据库和配置），或整个删除
5. 重启服务器

---

## 8. 命令行指令参考

### 8.1 玩家指令 `/le` (别名: `/learnenglish`, `/learn`, `/english`)

| 命令 | 说明 | 权限 |
|------|------|------|
| `/le menu` | 打开英语学习主菜单 | `letslearnenglish.use` |
| `/le` | 同上，无参数默认打开主菜单 | `letslearnenglish.use` |
| `/le word` | 打开词库选择 GUI | `letslearnenglish.word.*` |
| `/le word <词库名>` | 直接开始指定词库答题 | `letslearnenglish.word.*` |
| `/le word <词库名> <模式>` | 指定词库和模式开始答题 | `letslearnenglish.word.*` |
| `/le dialogue` | 打开情景对话菜单（默认餐厅场景） | `letslearnenglish.dialogue.*` |
| `/le dialogue <场景名>` | 打开指定对话场景 | `letslearnenglish.dialogue.*` |
| `/le progress` | 查看个人主页 | `letslearnenglish.progress.*` |
| `/le profile` | 查看自己的个人主页 | `letslearnenglish.progress.*` |
| `/le profile <玩家名>` | 查看指定玩家的个人主页 | `letslearnenglish.progress.*` |
| `/le stats` | 查看学习统计（同个人主页） | `letslearnenglish.progress.*` |
| `/le review` | 查看待复习单词数量及开始复习 | `letslearnenglish.word.*` |
| `/le square` | 进入英语广场（玩家排行榜） | `letslearnenglish.use` |
| `/le help` | 查看帮助 | `letslearnenglish.use` |

**答题模式参数**:

| 参数 | 模式 | 说明 |
|------|------|------|
| `spelling` | 拼写模式 | 看中文输入英文单词 |
| `choice` | 选择模式 | 看中文从选项中选出正确答案 |
| `fill_blank` | 填空模式 | 看英文句子填入缺失单词 |

**可用场景参数**:
- `restaurant` — 餐厅场景
- `airport` — 机场场景
- `shopping` — 购物场景

**示例**:
```
/le word beginner choice        # 初级词库，选择题模式
/le word intermediate spelling  # 中级词库，拼写模式
/le dialogue restaurant         # 餐厅对话
/le profile Notch                # 查看 Notch 的个人主页
```

### 8.2 管理员指令 `/lea` (别名: `/learnadmin`)

| 命令 | 说明 | 权限 |
|------|------|------|
| `/lea reload` | 热重载所有配置文件 | `letslearnenglish.admin` |
| `/lea export` | 导出全部玩家学习数据到文件 | `letslearnenglish.admin` |
| `/lea reset <玩家名>` | 重置指定玩家的学习数据 | `letslearnenglish.admin` |
| `/lea info` | 查看插件版本和运行状态信息 | `letslearnenglish.admin` |
| `/lea help` | 查看管理命令帮助 | `letslearnenglish.admin` |
| `/lea ignore-update` | 忽略本次启动的更新提醒 | `letslearnenglish.admin` |

**示例**:
```
/lea reload                     # 修改配置后无需重启
/lea export                     # 导出 JSON 格式的玩家数据
/lea reset Steve                # 重置玩家 Steve 的数据
/lea info                       # 查看版本、数据库类型等
```

### 8.3 权限节点完整列表

| 权限节点 | 说明 | 默认授予 |
|----------|------|---------|
| `letslearnenglish.use` | 使用基本学习功能 | 所有玩家 (true) |
| `letslearnenglish.admin` | 使用管理员功能 | OP (op) |
| `letslearnenglish.word.*` | 使用所有单词学习功能 | 所有玩家 (true) |
| `letslearnenglish.dialogue.*` | 使用所有对话练习功能 | 所有玩家 (true) |
| `letslearnenglish.progress.*` | 查看所有进度信息 | 所有玩家 (true) |

**LuckPerms 权限配置示例**:
```
# 授予玩家基本使用权限（通常不需要，默认已有）
/lp user Steve permission set letslearnenglish.use true

# 授予管理员
/lp user Admin permission set letslearnenglish.admin true

# 授予组
/lp group default permission set letslearnenglish.use true
/lp group admin permission set letslearnenglish.admin true
```

### 8.4 可选 PlaceholderAPI 占位符

如果安装了 PlaceholderAPI，可以使用以下占位符（在其他插件如计分板、TAB 中显示）:

> 注意: PlaceholderAPI 集成功能可能在后续版本中扩展，请以实际版本为准。

---

## 附录

### A. 默认配置文件完整内容

#### config.yml 完整参考

见 [5.1 节](#51-%E4%B8%BB%E9%85%8D%E7%BD%AE%E6%96%87%E4%BB%B6-configyml) 及插件生成的默认文件。建议时常对比官方最新版与本地版本的差异。

#### 内置成就列表

| 成就 ID | 名称 | 条件 | 奖励 |
|---------|------|------|------|
| `first_word` | First Steps | 学习第一个单词 | 50 经验 |
| `word_collector_10` | Word Collector | 学习 10 个单词 | 100 经验 |
| `word_master_50` | Word Master | 学习 50 个单词 | 1 钻石 |
| `word_expert_100` | Word Expert | 学习 100 个单词 | 3 钻石 |
| `first_dialogue` | Conversation Starter | 完成第一次对话 | 50 经验 |
| `dialogue_master` | Fluent Speaker | 完成 10 次对话 | 200 经验 |
| `streak_3` | Consistent Learner | 连续 3 天学习 | 100 经验 |
| `streak_7` | Weekly Warrior | 连续 7 天学习 | 1 绿宝石 |
| `perfect_score` | Perfect Score | 单次会话 100% 正确率 | 300 经验 |

#### 内置场景对话列表

| 场景文件 | 说明 |
|---------|------|
| `restaurant` | 餐厅点餐对话 |
| `airport` | 机场登机对话 |
| `shopping` | 购物对话 |

#### 内置词库列表

| 词库文件 | 等级 | 单词数 |
|---------|------|--------|
| `beginner` | 初级 | 10 个基础词汇 |
| `intermediate` | 中级 | 10 个进阶词汇 |
| `advanced` | 高级 | 10 个高级词汇 |

### B. 文件结构速查

```
plugins/Let's Learn English!/
├── config.yml                 ← 主配置文件 (修改后 /lea reload)
├── achievements-config.yml    ← 成就开关和时间限制
├── achievements.yml           ← 成就定义 (名称、奖励)
├── playerdata.db              ← SQLite 数据库 (勿手动修改)
├── player_lang.yml            ← 玩家语言偏好 (自动维护)
├── messages/
│   ├── zh.yml                 ← 中文翻译
│   └── en.yml                 ← 英文翻译
├── words/
│   ├── beginner.yml           ← 初级词库 (可自定义添加)
│   ├── intermediate.yml       ← 中级词库
│   └── advanced.yml           ← 高级词库
├── dialogues/
│   ├── restaurant.yml
│   ├── airport.yml
│   └── shopping.yml
├── backups/                   ← SQLite 自动备份
│   └── playerdata.db.XXXXX.bak
└── exports/                   ← /lea export 导出目录
    └── export_XXXXX.json
```

### C. 技术支持与反馈

- **GitHub Issues**: https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English-/issues
- **功能请求**: 请在 Issues 中使用 `Feature Request` 标签
- **Bug 报告**: 请附上 `/lea info` 输出、`logs/latest.log` 相关片段、以及复现步骤

---

> 本文档将持续更新，建议关注 GitHub 仓库获取最新版本。