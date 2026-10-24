# 锐评 R11 · 服主运维体验

> 2026-10-20 战略锐评 · Minecraft 服务器运维/SRE 视角 · 只读 · agentId ab7d1a0c6ddde44c2
> 编排者复核注记：五处关键指控实测成立——① MySQL 驱动缺失（与 R7 交叉独立验证：pom 无 shade、产物 JAR 内 com/mysql 计数 0）喵；② `ConfigManager.java:218-237` 语言文件 `config-version` 低于内置值即 `saveResource(path, true)` **硬覆盖**、`.bak` 仅单槽 `REPLACE_EXISTING`——服主自定义文案升级即丢，属实喵；③ `aiChatManager.reload()` 全仓唯一调用点是 `ScenarioSelectListener.java:232`（GUI 按钮），`HotReloadManager.reloadAll()` 不覆盖 AI 段——wiki :242 叫服主"改 key 执行 /lea reload"是错的，属实喵；④ `LetsLearnEnglish.java:771-779` 缺 Vault 静默 return 无日志属实；⑤ 与 R7 共同把"改 mysql 即炸"钉成双源实锤喵。

## 1. 一句话判决

功能面是个认真做的插件，运维面是个**没人负责"服主"这个角色**的插件：MySQL 后端开箱即炸、语言文件升级即覆盖、排障全靠读 Java 源码——1262 行 Wiki 和 710 行 config.yml 里没有一行能回答服主第一句话"我金币怎么没发"喵。

## 2. 致命伤 Top3

**① MySQL 是条死路，而且文档在骗人去走它。**
`pom.xml:48-53` 把 `mysql-connector-j` 声明为 `scope=compile`，但 `pom.xml:114-215` 没有任何 shade 插件——我核过产物 `target/Let's Learn English!-1.0.7-SNAPSHOT.jar`，`com/mysql` 条目 **0 个**（编排者 unzip 独立复核同数）；`plugin.yml` 也没有 `libraries:` 键。Paper 不注册 MySQL 驱动，所以 `DatabaseManager.java:94` 的 `DriverManager.getConnection` 直接 `ClassNotFoundException`。`DEPLOYMENT_WIKI.md:70-82`、`:846-867` 通篇教你怎么配 MySQL，**没有一个字**提"自己放驱动"。就算你补上驱动，`DatabaseManager.java:71-74` 连不上时是 `SEVERE + throw RuntimeException`，onEnable 中断，插件被 Bukkit 置为 disabled；更糟的是 `DatabaseManager.java:490-499` 的 `getConnection()` 内部又调 `initialize()`，全仓 25 处裸取连接（`DatabaseManager.java:2950-2953` 自陈）之后每一次都在异步线程抛 RuntimeException——服主看到的不是"cleanly disabled"，是**控制台刷屏**喵。而 `tasks/review-2026-10-18/review-complete.md:30` 明写"MySQL 后端整链（R3/T1）"仍在**需实机验证**清单里喵。

**② 升级会吃掉服主的自定义——而且吃的是最常改的那个文件。**
`ConfigManager.java:216-237`：`messages/zh.yml`、`en.yml` 的 `config-version`（当前 17，`zh.yml:6`）一旦低于内置值，就走 `plugin.saveResource(path, true)`——**硬覆盖**（编排者复核实锤）。所有 GUI 标题、播报文案、玩家自定义消息全没（GUI 标题不在 config.yml，在 `MainMenu.java:45` 读的 `gui.main-menu-title`）。唯一缓冲是 `ConfigManager.java:227-230` 的**单槽** `.bak`，且 `REPLACE_EXISTING` 意味着连续两次升级会拿第二次升级前的文件盖掉第一份。主 config 反过来**完全不管**（`config.yml:670` 自己承认"主 config 无迁移机制"）。所以服主最怕的"更新后配置没了"精确命中语言文件，且新键永不回填——服主的 `config.yml` 与线上生效值长期不一致，无法审计喵。

**③ 零可观测性。**
`/lea` 只有 12 个子命令（`AdminCommand.java:62-168`），**没有** debug / diagnose / status / selfcheck。`/lea info`（`AdminCommand.java:290-303`）只打印版本号、服务端版本、`database.type`——不报 Vault 在不在、AI key 有没有、金币三道闸现在是哪道关着、模块门状态、词库加载数。玩家投诉"奖励没发"时，真实原因有 5 个可能：`progress-system.enabled`、`rewards.enabled`、`rewards.types.money.enabled`（出厂默认 **false**，`config.yml:288`）、`money.daily-cap 500`、`word-system.daily-limit 50`（`RewardEngine.java:119-146`）——服主只能去读代码。且**发币没有任何台账**：全仓无 reward_log 表（`learning_log` 存 score 不存发放，`DatabaseManager.java:157-169`），`RewardEngine` 只在失败时 WARNING（`:298/:348/:393`），日上限拦截只给玩家发一条聊天（`RewardEngine.java:123-127`），**控制台零痕迹**。`setupEconomy()`（`LetsLearnEnglish.java:771-779`）缺 Vault 时 `return` 且**一行日志都不打**（编排者复核属实）喵。

## 3. 分条炮击

**首次启动**：6 步（`README.md:86-93`）读完要消化 `README.md` 149 行 + Wiki `:131-250` 约 120 行。onEnable（`LetsLearnEnglish.java:147-255`）生成 config.yml、words/×3、messages/zh+en、ai_scenarios.yml、achievements-config.yml、playerdata.db，`validateRequiredKeys()` 只审 15 个键（`ConfigManager.java:31-51`）——289 个键的审计覆盖率 5%。唯一做对的是 AI：缺 `ai_config.yml` 只 WARNING 两行（`AIChatManager.java:244-247`），优雅降级，不炸。**但 `ai_config.yml` 没有模板**（`src/main/resources/` 里不存在），Wiki `:236-240` 手抄一段了事喵。

**reload 覆盖度骗人**：Wiki `:260` 与 `:559` 说"全部配置热重载即时生效"。实际需重启的：`general.auto-save-interval`（`config.yml:35`）、`general.backup.*`（`:41`）、`daily-reminder.reminder-hours`（`:496`，`LetsLearnEnglish.java:433-439` 启动冻结）、`tts.enabled/cache-days/voicerss-api-key`（`:569`）、`leaderboard.update-interval`（`:266`）。**`ai-chat.*` 整段永远不重载**：`HotReloadManager.java:28-50` 压根没调 `aiChatManager.reload()`，全仓唯一调用点是 GUI 按钮 `ScenarioSelectListener.java:232`（编排者 grep 实锤）——而 Wiki `:242` 明确叫服主"改完 api-key 执行 /lea reload"。照做 = 换 Key 无效，重启才发现喵。顺带一句，Wiki `:769` 说 `update-interval` 是死键，其实 `LetsLearnEnglish.java:421` 已接线——文档反向说谎喵。

**静默吞错值**：`ConfigManager.java:400-409` `getGuiMaterial` 把 `IllegalArgumentException` 空 catch，材质名打错字 → 图标默默变默认，无日志。`ReviewScheduler.java:34` `getIntegerList("word-system.review.intervals")` 无默认无告警，写错成字符串 → 空列表 → 复习排程静默失效。`money.enabled:false` 出厂关着而 README 只字未提，是"装完没奖励"工单的头号来源喵。

**命名一致性**：同一个"每日上限"三套名字——`word-system.daily-limit` / `money.daily-cap` / `ai-chat.daily-round-limit`；`daily-challenges.rewards.answer_10`（下划线，`:530`）混在全文件 kebab-case 里；`daily-challenges.word-count`（`:523`）对 `word-system.words-per-session`（`:97`）；成就被切成三处——`progress-system.achievements`（开关）、`achievement-system`（音效，`:473`）、`achievements-config.yml`（定义）。前缀定义存在两份：`config.yml:52-54` 与 `messages/zh.yml:8`，`MessageUtil.java:32-41` main 赢，语言文件那份是陷阱喵。

**备份/还原**：`VACUUM INTO` + 独立连接 `quick_check` + 失败即删残缺产物（`LetsLearnEnglish.java:681-708`、`:723-733`）——这是全仓最专业的一段，业余插件里少见。但：仅 SQLite（`:659-664`，MySQL 每周期 info 一行然后跳过）、备份落在**同一个数据目录**（Wiki `:920` 让你 `cp -r plugins/LetsLearnEnglish/`、`:985` 让你"整个删除"，同生共死）、无手动备份命令、无 `/lea restore`。Wiki `:884` 还在描述已被替换掉的 `wal_checkpoint(TRUNCATE)` 喵。

**SQLite→MySQL 迁移**：文档零覆盖，代码零工具。`/lea export` 只导**当前在线**玩家 CSV（`DataExporter.java:48-52`、`AdminCommand.java:72-78`），既不是迁移载体也不算备份载体喵。

**删档（GDPR）**：`/lea reset X` + 两段 confirm（`AdminCommand.java:107-120`）设计不错，`deletePlayerData` 覆盖 15 张表并处理了三套玩家列名和顺序敏感的 social_cheers（`DatabaseManager.java:2553-2625`）。但 `player_scenarios/<uuid>.yml`（`PersonalScenarioStore.java:156/285`）**不删**，`player_lang.yml` 条目不删，且 `DatabaseManager.java:2546-2550` 自陈在途异步 auto-save 可能在删后写回残行——数据主体回来问"删干净了吗"，答案是不能保证喵。

**冲突面**：`ChatAnswerListener.java:122-145` 在 `AsyncPlayerChatEvent` 的 **LOWEST** 优先级 `setCancelled(true) + getRecipients().clear()`，决斗/答题期间整条聊天被吞。ChatControl、DiscordSRM、任何 MONITOR 级审计插件看不见这条消息——Wiki `:830` 只轻描淡写提了一句白名单。GUI 身份靠标题 `contains` 裸匹配通用词"排行榜/单词学习/个人主页/成就墙/选择词库"（`GUIUtils.java:77-102`），别的插件 Inventory 标题带"排行榜"就撞车；该文件自己的注释 `:79-80` 承认改标题文案会静默丢音效。`statistics-server` 出厂 `ip-whitelist` 含 `192.168.1.0/24`（`config.yml:608`）——开箱给整个局域网开门；`changeme` 密码有告警（`StatisticsHttpServer.java:65-67`），这点做对了喵。

**版本矩阵**：`VersionAdapter.java:70-73` 宣称支持 18–21，但 `plugin.yml:4` `api-version:"1.21"` 让 1.18–1.20 根本装不上，且 `isVersionSupported()` 唯一消费者是 `/lea info` 那一行——onEnable **从不检查、从不告警**。Wiki `:84/:800/:819` 三处写"Spigot 1.21+"是错的：Adventure（`VersionAdapter.java:58`）与 `Server#getMinecraftVersion()`（`LetsLearnEnglish.java:143`）spigot-api 没有（与 R5 独立交叉印证）喵。文档抽查 33 条命令/子命令里 31 条与代码一致（这点值得夸），但 Wiki 的 yaml 代码块里至少 5 个键（`vault-required`、`types.item/command/exp`、`gui.custom-textures`、`proxy.cross-server-announcements`）在 config.yml 中不存在且无读取点，另有多处"尚未包含本段/未显式写出"早已过期（`:466`、`:725`、`:1062`、`:213` 列了不再生成的 `achievements.yml`）喵。

**服主第一天必踩 5 个坑**：① 装完 `/le word` 打得开、答对没钱——因为 `money.enabled: false` 出厂关着（README 未提）；② 填了 DeepSeek key 执行 `/lea reload` 以为生效，其实必须重启（`HotReloadManager.java:28-50`）；③ 想改 GUI 中文标题，改完升级一次全被 `saveResource(...,true)` 抹掉（`ConfigManager.java:236`）；④ 照 Wiki 把小服升 MySQL，插件起不来且不知道是缺驱动；⑤ 玩家报"决斗时聊天别人看不见"，因为消息在 LOWEST 就被吞（`ChatAnswerListener.java:131-132`）喵。

## 4. 说句公道话

`addColumnIfMissing` 改成元数据判存在再 ALTER、并明确留注释说明 TOCTOU 残余窗口（`DatabaseManager.java:2959-2978`），`createIndexIfMissing` 处理了 Oracle MySQL 不认 `CREATE INDEX IF NOT EXISTS`（`:291-295`），MySQL 分支还会查 `information_schema`——这是真踩过多服共库的人才写的代码。VACUUM INTO 快照 + 独立连接校验 + 失败清残片是工业级备份。`/lea reset` 两段确认 + 三级 UUID 反查 + 删表前先按 quit 语义落盘顺序（`AdminCommandHandler.java:49-78`）比绝大多数商业插件严谨。config.yml 里"三层语义（入口隐藏/事件静默/数据保留）"的模块门文档化程度，以及诚实标注死键（`:76`、`:586-588`），在中文插件圈属于罕见水平。缺 Vault、缺 AI key 两处降级干净利落，没有拖垮启动。审查台账（review-complete.md）敢把"MySQL 整链未实机"写进残余清单，这份坦诚本身值得尊重——**只是文档没跟着这份坦诚更新**喵。

## 5. 只改一件事改什么

**加一个 `/lea admin diagnose`**（并在 onEnable 末尾自动跑一次，异常时 WARNING 进日志）。它打印**生效值**而非配置值：Vault 是否解析到 Economy（现在 `LetsLearnEnglish.java:771-779` 一行日志都没有）、AI key 是否加载 + `apiKey==null`、MySQL 驱动 `Class.forName` 探活与连接耗时、三道金币闸各自的解析值与 AND 结果、9 个模块门、词库加载条数与校验失败数、DB 类型 + 各表行数 + 最近一次备份时间与 `quick_check` 结果、以及一张"**本会话改了需重启的键**"清单（那 5+1 个启动快照键）。理由：本次炮击的三大致命伤里有两条（钱没发、reload 不生效）本质是**状态不可见**，第三条（MySQL）也需要一条明确的"驱动缺失/连接失败=插件已停用"的可读结论而不是一屏 RuntimeException。一个只读命令能砍掉大部分工单，改动面小、无 schema 风险、不碰玩家数据，同时把 `validateRequiredKeys`（现在只覆盖 5% 的键）从"存在性审计"升级成"生效值快照"，顺带解决 config.yml 与线上不一致的长期不可审计问题喵。
