[English](SECURITY-EN.md) | 简体中文

# 安全策略

Let's Learn English! 是面向 Paper 系 Minecraft 服务端的**英语学习**插件。它读写玩家学习数据，
在 `plugins/LetsLearnEnglish/` 下写文件与 SQLite 数据库，并且在**你主动开启时**才访问外部服务。

但它和「只读监测插件」有一个本质区别：**它会把玩家的聊天内容发给你配置的 AI 服务商。**
因此本文档刻意写得具体：**它采集什么、发出什么、在你磁盘上存什么。**

---

## 支持范围

安全修复只发给**最新版本**。

| 版本 | 是否支持 | 说明 |
|------|---------|------|
| 1.0.7.x | ✅ 支持 | 安全修复落在这里 |
| 更早版本 | ❌ 不支持 | 请升级 |

本项目发布两个包（源码相同、编译目标不同），**两者都在支持范围内**：

| 产物 | 支持的 Minecraft | 需要 |
|---|---|---|
| `Let.s.Learn.English.-<版本>.jar` | 1.21.4 – 1.21.11 | Java 21+ |
| `Let.s.Learn.English.-<版本>-mc26.jar` | 26.1+ | Java 25+ |

不向更早的版本回移补丁。这是单人维护项目，同时维护多条分支等于哪条都维护不好。

---

## 报告漏洞

**不要为安全问题开公开 issue。** 公开 issue 所有人可见，包括会在修复前利用它的人。

**首选——GitHub Security Advisory（私密报告）：**

> <https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/security/advisories/new>

这条通道给我们一个私密线程、一个共同开发修复的地方，以及发布带署名的安全公告的方式。

**备选**：在 [作者 GitHub 主页](https://github.com/sqkl520) 找到联系方式，说明你要报安全问题 ——
**第一条消息里不要包含利用细节**。

无需加密，本项目没有 PGP 密钥。请**不要**在报告中粘贴生产凭据、API key、真实玩家名或
玩家聊天内容；请先打码。

### 请提供

- 插件版本（`/lea info` 首行会打印，或看 jar 文件名）
- **你装的是哪个包**（普通包 / `-mc26`）
- 服务端软件与构建号，例如 `Paper 1.21.11 build 132`，或 `Purpur` / `Folia` / `Leaf`
- Java 版本与操作系统
- 问题是什么，以及攻击者能因此得到什么——**影响比机制更重要**
- 复现步骤，或 PoC。一份最小复现比一篇长文更有用
- 相关日志片段，**已脱敏**（服务器 IP、玩家名、API key、`inbound-secret`、面板密码）

### 响应时间

| 阶段 | 目标 |
|------|------|
| 确认收到 | **7 天内** |
| 初步判定——接受 / 需要更多信息 / 超出范围 | **14 天内** |
| 修复，或给出你可自行实施的缓解方案 | **90 天内**；高危问题更快 |
| 发布安全公告并署名 | 修复版本可用之后 |

这些是**一个人给出的目标**，不是合同 SLA。本项目免费、由学生用课余时间维护，没有漏洞赏金。
若某份报告需要的专业知识我不具备，我会直接说明，而不是让它一直挂着。
除非你要求匿名，否则会在安全公告与 CHANGELOG 中署名致谢。

### 范围——什么算安全漏洞

**在范围内：**

- 通过本插件实现的远程代码执行、鉴权绕过、权限提升
- 任何让**非 OP 玩家**读到不该读的数据，或触发重置、导出、重载配置、写文件的路径
- **密钥泄露**到日志、报告、错误信息或聊天输出中。
  `ai_config.yml` 的 `api-key`、`tts.voicerss-api-key`、`proxy.inbound-secret`、
  `statistics-server.auth.password` 都算凭据
- **跨服消息伪造**：在未设置 `proxy.inbound-secret` 的多服网络上，伪造
  `GlobalMessage` / `AchievementUnlock` / `SeasonSettled` 帧（这是**已知且已在文档中说明**的
  配置边界，见下方"已知非问题"第 3 条；但如果你能绕过**已设置**的 HMAC 校验，那在范围内）
- 通过 `importwordbank`、报告导出、场景导入实现的路径穿越或任意文件写入
- 非特权命令或正常游戏行为即可触发的拒绝服务
- 构建链问题：被 shade 的 MySQL 驱动、CI 工作流、发布产物

**不在范围内**——这些是预期行为，不是漏洞：

- **任何需要 OP 或控制台权限的问题。** 管理员本来就能执行任意命令、读取服务器文件。
  `/lea importwordbank` 能读 `exports/` 下的文件是**设计如此**。
- **任何需要另一个恶意插件**或改动过的服务端 jar 才能成立的问题。插件共享同一个 JVM，
  现代 Minecraft 也没有 SecurityManager。
- **不在上方支持列表内的版本中的任何问题。**
- **Minecraft / Paper / Purpur / Folia / Bukkit 自身的漏洞。** 请报告给上游。
- **玩家自己能改的客户端**。本插件没有客户端 mod，也不信任客户端发来的任何东西。

### 已知非问题

以下行为常被当作 bug 或安全问题上报，但都是有意为之：

1. **「非 OP 玩家收到 `You do not have permission`。」** 插件只注册 4 个权限节点，全部默认 `op`。
   其余玩家功能由 `config.yml` 的**模块开关**控制，不看权限 —— 这是有意的设计，
   不是权限漏配。见 README 的权限节点一节。
2. **「`statistics-server` 面板用的默认密码是 `changeme`。」** 默认 `enabled: false` 且绑定
   `127.0.0.1`。**当口令仍是默认/空值且绑定地址不是回环时，插件会拒绝启动该面板并打 SEVERE**，
   而不是放行后打一条没人看的警告。见下方"统计面板专项"。
3. **「没设 `proxy.inbound-secret` 时，玩家能伪造跨服消息。」** 这是**已文档化的配置边界**。
   Bukkit 把代理转发来的插件消息和玩家客户端自己发的 payload 派发到同一个回调，
   且该回调拿不到可靠来源信息。设了密钥后所有入站帧必须带正确的 HMAC-SHA256 签名，
   否则丢弃并记 WARNING。**单服或纯局域网留空是合理的**；多服网络请务必设置。
4. **「插件往 `plugins/LetsLearnEnglish/` 写文件。」** 配置、词库、语言文件、数据库、备份、导出。
   文件存在本身不构成信息泄露；如果宿主机上无关的本地用户能读你的服务器目录，
   那是主机层面的问题。
5. **「AI 对话把玩家消息发给了第三方。」** 这是 AI 功能的**定义**，不是漏洞。
   详见下方"AI 对话专项"。
6. **「`/lea report` 生成的报告里有玩家名与学习数据。」** 这是工具用途。报告落
   `exports/reports/`，只发给执行者本人。分享前请自己过一遍。

---

## 数据采集——发什么、不发什么

**本插件没有自建后端、没有账号系统、没有云服务。** 一切在你的服务器上算完。

### 出站连接

只有**三条**可能的出站连接，全部在你主动配置后才发生：

| # | 连接 | 默认 | 目的地 | 由谁控制 |
|---|------|------|--------|---------|
| 1 | AI 对话 | **不会发生**（`ai-chat.enabled` 默认 true，但 `ai_config.yml` 首次启动不生成，无 api-key 即不工作） | 你配置的 `ai-chat.api-url`（默认 DeepSeek） | `ai_config.yml` 的 `api-key` + `ai-chat.enabled` |
| 2 | TTS 语音下载 | **不会发生**（`tts.enabled` 默认 true，但 `voicerss-api-key` 默认为空，无 key 即只显示音标、不联网） | `api.voicerss.org`（仅当你填了 VoiceRSS key） | `tts.enabled` + `tts.voicerss-api-key` |
| 3 | 更新检查 | **开启** | `api.github.com` 的 releases 接口 | `general.check-updates: false` |

**本插件不含 bStats，也不含任何其它遥测。** 你可以自行验证：

```bash
grep -rn "HttpClient\|HttpRequest\|openConnection\|new URL(\|new Socket" src/main/java/
```

所有命中都在三个文件里：`core/ai/AIChatManager.java`（连接 1）、`core/ai/TtsManager.java`（连接 2）、
`core/UpdateChecker.java`（连接 3）。`core/manager/StatisticsHttpServer.java` 是**入站** HTTP 服务，
不向外发起请求。

### 连接 3 会说什么

只向 GitHub 请求本仓库的最新 Release 元数据。**不发送**任何玩家数据、服务器标识或使用统计。
返回结果用于在 OP 进服时提示有新版本。

### AI 对话专项（连接 1，最需要知情的一项）

**玩家的聊天内容会被发送到你配置的 AI 服务商。** 具体是：

- 玩家在 AI 对话模式下输入的每条消息
- 插件生成的 system prompt（场景设定、难度提示词、玩家要练习的目标单词）
- **最近的对话历史**，上限由两个键共同约束：`ai-chat.max-context-pairs`（默认 6 对）
  与 `ai-chat.max-context-tokens`（默认 2000，近似估算）
- 玩家名**不会**主动发送，但玩家如果自己在消息里打出名字，那部分会随正文一起发出

**发出去的内容同时会被存进本地数据库** `ai_chat_history` 表（`content TEXT` 列存全文），
用于 `/le history` 回看。保留期由 `cleanupOldChatHistory` 清理。

> **这是本项目最应该被服主告知玩家的一件事。** 插件目前在玩家侧**没有**弹窗或提示说
> 「你的聊天内容会发给第三方 AI」。请你在自己的服务器规则里说明这一点，
> 或者用 `ai-chat.enabled: false` 关闭该功能 —— 关闭后其余功能完全不受影响。
>
> 如果你所在地区对未成年人数据出境有要求，请特别注意：本插件面向的正是这个年龄段的学生。

**日志面**：`ai-chat.debug` 打开后会在控制台打印 AI 请求/响应详情，**包含对话正文**。
默认 `false`。排障结束请记得关掉。

### 密钥存放位置

全部在 `plugins/LetsLearnEnglish/` 下，**都不上传**，也都**不应该进版本控制**：

| 密钥 | 文件 | 键 | 说明 |
|---|---|---|---|
| DeepSeek API key | `ai_config.yml` | `api-key` | 请求时放在 `Authorization: Bearer` 头里 |
| VoiceRSS key | `config.yml` | `tts.voicerss-api-key` | **拼进 URL 的 query string**（这是 VoiceRSS 的接口形态，不是本插件的选择） |
| 跨服入站签名密钥 | `config.yml` | `proxy.inbound-secret` | HMAC-SHA256 共享密钥，多服网络每台必须相同 |
| 统计面板口令 | `config.yml` | `statistics-server.auth.password` | HTTP Basic Auth |

> ⚠️ **VoiceRSS 的 key 在 URL 里。** 若该请求失败且异常信息含完整 URL，key 会随日志外泄。
> 当前实现对下载失败只记录异常 message（`Failed to download TTS audio for word '...'`），
> 不拼接 URL，但**这依赖 JDK 异常类型的实现细节**。若你对该风险敏感，请不要配置 TTS。

### 统计面板专项（入站 HTTP，默认关闭）

| 项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 需要主动开启 |
| `bind-address` | `127.0.0.1` | 默认只监听本机 |
| `port` | `8080` | |
| `ip-whitelist` | `127.0.0.1` + `192.168.1.0/24` | 支持 IPv4 / IPv6 与 CIDR。**留空 = 拒绝所有**（fail-closed） |
| `auth.username` / `auth.password` | `admin` / `changeme` | Basic Auth |

**双层保护**：IP 白名单 + HTTP Basic Auth。

**默认口令的硬拦截**：当 `auth.password` 仍是默认值（`changeme`）、空串或纯空白，
**且** `bind-address` 不是回环地址时，插件**拒绝启动该面板**并打 SEVERE，
其余功能照常。这个组合没有正当用途 —— 它等于把含全部玩家学习数据的管理面板
以 `admin/changeme` 暴露到网络上。

**面板会展示什么**：玩家名、学习统计、排行榜、会话数据。**面板不会展示 AI 对话正文**。
若你把它暴露到公网，请当作内部管理后台对待。

### 本插件在你磁盘上存什么

全部位于 `plugins/LetsLearnEnglish/` 下，**都不上传**。

| 路径 | 内容 |
|------|------|
| `config.yml` | 你的配置（含 `proxy.inbound-secret`、统计面板口令、VoiceRSS key） |
| `ai_config.yml` | **DeepSeek API key**（明文；首次启动不自动生成，需手动创建） |
| `ai_scenarios.yml` | AI 场景与难度提示词 |
| `achievements-config.yml` | 成就开关 |
| `playerdata.db` | **SQLite 数据库**，含全部玩家学习数据 |
| `playerdata.db-wal` / `-shm` | WAL 副文件。**数据库不是单个 `.db` 文件** |
| `player_lang.yml` | 玩家语言偏好 |
| `messages/{zh,en}.yml` | 语言文件 |
| `words/*.yml` | 词库 |
| `player_scenarios/<uuid>.yml` | 玩家个人 AI 场景 |
| `exports/reports/*.txt` | `/lea report` 生成的报告 |
| `exports/diagnose-*.txt` | `/lea diagnose save` 生成的诊断报告 |
| `audio/*.wav` | TTS 缓存（当前版本不播放） |
| `backups/*.bak` | 数据库自动备份，默认保留 7 份 |

**数据库里存了什么**（运行期 **24** 张表：`schema.sql` 里定义 **23** 张，第 24 张 `daily_coin_counters` 由 `RewardEngine.ensureDailyCoinsTable()` 首次发币时懒建，所以 `schema.sql` 不是完整权威）：

| 数据 | 表 | 敏感度 |
|---|---|---|
| 玩家 UUID + **玩家名** | `player_data`、`player_profiles` | 个人信息 |
| **AI 对话全文** | `ai_chat_history.content` | **高** —— 玩家与 AI 的完整对话正文 |
| 玩家留言（鼓励语） | `social_feed` 相关 | 中 —— 玩家彼此可见 |
| 学习进度、答题记录、成就、金币、赛季、班级 | 其余各表 | 低 —— 聚合学习数据 |

**任何表都不存玩家密码或邮箱。** 插件不收集这些。玩家名与 UUID 来自 Minecraft 本身。

**表结构由插件在启动时自动迁移。** `DatabaseManager.initialize()`（`onEnable` 阶段）里的
`createTables()` 会对存量库执行幂等补列（`addColumnIfMissing`，先查元数据再
`ALTER TABLE ADD COLUMN`），不需要你手工执行任何 `ALTER TABLE`。最新一例是
`daily_challenges.abandoned`（`INT DEFAULT 0`）：`degradeStale`
把因 AI 不可用而作废的挑战行写成 `completed=1/claimed=1` 的同时置 1，报表的"完成挑战数"
据此剔除这些自动放弃行，避免家长看到的完成度虚高。这件事的安全含义是：
**旧备份的库文件在新版插件首次启动时会被就地改写** —— 若你需要保留"升级前"可比对的快照，
请在停服后先复制 `.db` 连同 `-wal` / `-shm` 副文件，不要指望升级后还能拿回原结构。

> 报告与游戏内输出确实包含玩家名与学习数据 —— 这正是工具的用途。把导出的报告贴到公开
> issue 前，请先自己过一遍。

---

## 加固建议

- **多服网络必须设 `proxy.inbound-secret`**。用 `openssl rand -hex 32` 生成，每台子服填同一个值。
  留空等于允许任何改了客户端的玩家伪造跨服广播与成就解锁。
- **所有子服的 JVM 时区必须一致**。这不只是显示问题：日界不同会导致签到在同一自然日被
  领两次（双份真钱）。用 `-Duser.timezone=<IANA 名>` 钉死，**写在 `-jar` 之前**。
  详见 [`DEPLOYMENT_WIKI.md` 5.9 节](DEPLOYMENT_WIKI.md#59-多子服时区一致性硬性前提)。
- **`ai_config.yml` 当密码对待**。它不在版本控制里，也不要放进任何备份上传流程。
- **想清楚再开 AI 对话**。它把玩家输入发给第三方，且在玩家侧没有告知。你若面向未成年人，
  请在你的服务器规则里说明，或关闭该模块。
- **TTS 的 VoiceRSS key 在 URL 里**，对日志外泄更敏感。不需要发音功能就别配。
- **统计面板若必须外网访问**：改成强口令、收紧 `ip-whitelist`、最好再套一层反向代理与 TLS。
- **`/lea export` 与 `/lea report` 会把玩家数据落盘**。只给那些本来就能接触服务器文件的人。
- **用不能读取其它用户文件的账号运行服务器。** 插件以普通插件权限写入
  `plugins/LetsLearnEnglish/`，它无法在宿主机配置错误的情况下保护文件。
- **定期备份**。插件自带定时备份，但 `playerdata.db` 是 WAL 模式 —— 手动备份请用
  `PRAGMA wal_checkpoint(TRUNCATE)` + `.backup`，直接 `cp` 会漏掉未合并事务。
  详见 README 的 SQLite 维护一节。

---

## 本项目的已知安全债（如实记录）

写在这里不是为了让报告者少报，而是为了不误导安全研究者：

1. **`ai_config.yml` 的 API key 是明文存储。** 没有加密，没有系统密钥环集成。
   这与绝大多数 Minecraft 插件一致，但它意味着任何能读该文件的人就拿到了 key。
2. **玩家侧没有 AI 数据外发告知。** 见上文。这是产品决策的缺口，不是实现 bug，
   但它是本插件在隐私面最薄弱的一环。
3. **`ai_chat_history` 存对话全文且无加密。** 数据库文件泄露即等于对话内容泄露。
