# findings-r9 管理面（审查 Agent 产出）
- 审查范围：command/AdminCommand.java（401 行）、core/manager/AdminCommandHandler.java（864 行）、core/manager/ReportGenerator.java（1127 行）、core/manager/StatisticsHttpServer.java（476 行）（辅助核验：DataExporter.java、ProxyMessenger.java、HotReloadManager.java、PlayerDataStore.java、DatabaseManager.java、AchievementManager.java、SeasonManager.java、WordManagerImpl.java、WordBankValidator.java、MessageUtil.java、LetsLearnEnglish.java、plugin.yml、config.yml、messages/zh.yml、messages/en.yml、F5-01..04 台账）
- 审查时间：2026-10-18
- 结论摘要：高3条 / 中4条 / 低6条 / 待验证2条 / 豁免挑战0条
- 备注：本文件由编排者按该 agent 返回全文原样保存（harness 禁止子代理写文件）

## 高危

### R9-H1 [thread·高·确定] /lea export 在主线程做全量文件 IO + 逐玩家 getPlayerStats（cache miss 时查库），与同族 report 全异步（前任待核① 已核成立）
- 位置：`command/AdminCommand.java:68`（主线程调用点）→ `core/manager/DataExporter.java:59-96`；对照面 `core/manager/ReportGenerator.java:187`
- 证据（AdminCommand 主线程直调）：
  ```java
  // AdminCommand.onCommand（命令主线程）
  case "export":
      DataExporter.ExportResult exportResult = plugin.getDataExporter().exportAllData();
  ```
  ```java
  // DataExporter.exportAllData（在调用者线程，即主线程执行）
  try (FileWriter writer = new FileWriter(exportFile)) {
      plugin.getServer().getOnlinePlayers().forEach(player -> {
          PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(player.getUniqueId());
          writer.write(... + stats.getTotalWords() + ...);
  ```
  ```java
  // PlayerDataStore.getPlayerStats:851 —— cache miss 走 loadPlayerData（DB 查询）
  PlayerStats stats = playerStatsCache.get(playerId);
  if (stats == null) {
      loadPlayerData(playerId);   // ← word_progress + player_data 两条 SELECT
  ```
- 复现：多名在线玩家时执行 `/lea export`。命令线程 = 服务器主线程；FileWriter 创建/写入 + 每人 getPlayerStats 在 cache miss 时触发 loadPlayerData 双 SELECT。IO 与 DB 均在主线程串行执行。
- 影响：主线程阻塞（卡服）——§F rubric 的"高"标准；report 路径已全异步，export 是 /lea 命令族中唯一漏网的主线程 IO 路径。
- 修法：仿 ReportGenerator 模式——主线程先抓在线玩家快照 → runTaskAsynchronously 内逐个取值并写文件 → runOnMainThread 发完成消息。

### R9-H2 [data·高·确定] 报告 Q4 AI 对话数：本地日期边界字符串比较对 SQLite UTC CURRENT_TIMESTAMP 时区错位（前任待核② 已核准）
- 位置：`core/manager/ReportGenerator.java:382-399`；写入侧 `data/DatabaseManager.java:233-241、506-521`
- 证据：
  ```java
  // ReportGenerator（收集侧——边界由 LocalDate.now() 本地日期生成）
  String sql4 = "SELECT COUNT(DISTINCT session_id) FROM " + prefix
          + "ai_chat_history WHERE player_uuid = ? AND timestamp >= ? AND timestamp <= ?";
  stmt.setString(2, period.from() + " 00:00:00");
  stmt.setString(3, period.to() + " 23:59:59");
  ```
  ```java
  // DatabaseManager（建表）：" timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
  // insertChatHistory（INSERT 不含 timestamp 列——值全部由 DB 默认值产生，SQLite = UTC）
  ```
- 复现：SQLite 下 CURRENT_TIMESTAMP 取 UTC。中国 (UTC+8) 服务器：本地 8/18 07:59 写入的行，DB 里存 "2026-08-17 23:59"（UTC）。报告 Q4 边界按本地日期比较 → 当天 00:00-07:59 的对话漏算、昨天 08:00 后的行算进今天——8 小时整段错位。
- 影响：报告的"本期 AI 对话轮数"偏错（中国服错位 8 小时窗口）。仅影响 Q4 一个数字；跨夜长期大致对冲，短期（7d/30d 边界）不准。
- 修法：① 写入侧显式写本地时间戳；或 ② 读取侧把边界转换为 UTC 格式化后比较。与 P3-12 时区统一口径对齐（当时统 Java 侧未统 DB 默认值）。

### R9-H3 [security·高·高] /lea broadcast 消息无长度上限——payload 走 writeUTF 上限 + Minecraft 单包 32767 字节限制，超限异常逃逸无 catch（前任待核③ 已核）
- 位置：`command/AdminCommand.java:171-177` → `core/ProxyMessenger.java:123-147`
- 证据：
  ```java
  // AdminCommand.handleBroadcast：消息只做拼接，无任何长度校验
  String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
  plugin.getProxyMessenger().sendGlobalMessage(formatted);
  ```
  ```java
  // ProxyMessenger.sendForward：payloadOut.writeUTF(data) 超 65535 字节抛 UTFDataFormatException
  ```
- 复现：`/lea broadcast <超长文本>` → writeUTF 抛 UTFDataFormatException（unchecked 逃逸）→ 一路无 catch 到 onCommand → Bukkit 捕获并打印堆栈，广播不送达且成功消息不发。双重限制：① writeUTF 65535 字节；② Minecraft 网络层 32767 字节单包上限（超限静默失败/踢信使玩家——`getAnyOnlinePlayer` 随机选的无辜玩家）。
- 影响：广播静默失败（管理员只看到控制台堆栈）；极端下踢掉无辜信使玩家。无越权面（仅 admin）。
- 修法：handleBroadcast 加长度校验（>20000 字节拒绝 + admin.broadcast-too-long 提示）；sendForward 内包 try/catch 记 WARNING。i18n 需 zh/en 新增。

## 中危

### R9-M1 [config·中·确定] statistics-server 全部配置在构造期读取一次，/lea reload 后不生效且无"需重启"提示（前任待核⑤ 已核，与 R10-M5 交叉）
- 位置：`core/manager/StatisticsHttpServer.java:47-58`（构造读配置）；`core/manager/HotReloadManager.java:28-47`（reloadAll 不触碰该服务）；`LetsLearnEnglish.java:234-238`；`resources/config.yml:589-604`（无"需重启"注释）
- 复现：改 `statistics-server.port: 8080 → 9090` → `/lea reload` → 提示成功但仍在 8080 监听；改密码同样不生效（安全面静默不同步）；`enabled` 切换需重启（无提示）。
- 修法：① HotReloadManager 增 restartStatisticsServerIfNeeded()（stop→重建→start）；或 ② config.yml 该节补"改动需重启"注释 + reload-success 追加提示。

### R9-M2 [ux·中·确定] /lea export 成功后返回绝对路径，报告与词库导出均返回相对路径——三处同族命令展示不一致
- 位置：`core/manager/DataExporter.java:95`、`AdminCommand.java:74-75`；对照 `ReportGenerator.java:279、251`、`AdminCommandHandler.java:362`
- 证据：`return new ExportResult(true, exportFile.getAbsolutePath(), null);` ← 绝对路径暴露服务器目录结构；对照 ReportGenerator `"exports/reports/" + file.getName()` 与 Handler `"exports/" + fileName`。
- 修法：ExportResult.filePath 改相对路径（日志保留绝对路径）。

### R9-M3 [logic·中·确定→实际低危] /lea class add 已在本班时在持有 connectionLock 的 synchronized 块内调用 sendOnMain——非死锁但违反锁块最小化纪律
- 位置：`core/manager/AdminCommandHandler.java:712-748`
- 复现：已在本班分支在锁块内 sendOnMain → runTask 投递（线程安全，下一 tick 执行）；Integer==int 拆箱比较正确。**当前无实际 bug**，属并发卫生。实际定级 [低]（见 R9-L5 合并说明）。
- 修法：把幂等分支的 sendOnMain 移出锁块。

### R9-M4 [logic·中·确定] tab 补全 getKnownPlayerNames 在主线程做 DB LIKE 查询——player_data 无 player_name 索引，Tab 连按全表扫 + 持锁阻塞
- 位置：`command/AdminCommand.java:329-336` → `AdminCommandHandler.java:218-242`
- 证据：`SELECT DISTINCT player_name FROM player_data WHERE player_name LIKE ? LIMIT 25` 在主线程持 connectionLock 执行；索引清单（createIndexIfMissing 全部调用）无 player_data 索引。
- 复现：op 输入 `/lea report a<Tab>` → 每次按键全表扫 + 持锁阻塞其他 DB 操作。
- 修法：① 为 player_data(player_name) 建索引；② 或补全候选改为在线玩家 + 内存缓存；③ 至少前缀长度 <2 不查。

## 低危

### R9-L2 [ux·低·确定] /lea reset 无二次确认——查 R-86h 注释原文后定论：非"设计确认"，属未防守的危险操作
- 位置：`command/AdminCommand.java:77-84、136-148`；`core/manager/AdminCommandHandler.java:50-74`
- 证据：R-86h 注释只修"不存在玩家误报成功"，**全文无"无二次确认属设计"声明**；全仓无 reset 确认的设计决策记录（grep 二次确认/confirm 零命中）。
- 复现：`/lea reset <玩家名>` 输入即立即删除该玩家全部数据（15 张表）。拼错有保护（三级查找），"故意删+手滑选错人"无缓冲。
- 修法：`/lea reset <player> confirm` 两段式或 5 秒双击（命令侧推荐，控制台可豁免）。定级低危因 admin-only + 精确匹配 + 可从备份恢复；建议入修。

### R9-L3 [logic·低·确定] reset 后 SeasonManager.getCurrentTopCached/Leaderboard 内存快照不清——5 分钟内 class list 可能显示旧榜单分数（下个刷新周期自愈）
- 位置：`core/manager/AdminCommandHandler.java:59-70`；`LetsLearnEnglish.java:391-394`
- 备注：前任待核④（PlayerDataStore 缓存）核销——unloadPlayerData 822-824 行已清 todayAnswerCountCache/todayAiRoundCache/conversationCountCache（R-03 注释为证）。
- 修法：resetPlayerData 末尾调 progressManager.getLeaderboard().refreshCache()。

### R9-L5 [thread·低·确定] applyClassAdd 锁块内 sendOnMain（M3 降级收录）——现状安全，建议幂等分支 sendOnMain 移出锁块

### R9-L6 [quality·低·确定] AdminCommandHandler.addWord/removeWord 为不可达死方法（零调用）且入口校验缺失（F5-02 关联）
- 位置：`core/manager/AdminCommandHandler.java:244-284`
- 证据：全仓 grep `handler.addWord(`/`Handler.addWord` 零命中；F5-02 台账已标"入口不可达零调用；方法本体随低危批次清理"——代码仍在（未执行清理）。
- 修法：删除两方法。

### R9-L7 [i18n·低·确定] admin-help 缺 class 子命令帮助行——help 列表 11 行 vs SUBCOMMANDS 12 项不对齐（class 不可发现）
- 位置：`command/AdminCommand.java:33-36（SUBCOMMANDS 含 "class"）`、`263-288（sendHelpMessage 无 class 行）`；zh.yml:350-362、en.yml:355-367（无 admin-help.class 键）
- 复现：`/lea help` 无 `/lea class ...` 任何说明；tab 补全有 class。唯一缺口 = class（其余对齐）。
- 修法：zh/en 增 `admin-help.class` 键 + sendHelpMessage 增行。

### R9-L8 [deadcode·低·确定] StatisticsHttpServer 绑定安全面复核：默认 127.0.0.1 + 空 whitelist fail-closed 双保险正确（R13-L5 已报 changeme/无失败限制——R9 复核补充）
- 位置：`core/manager/StatisticsHttpServer.java:54、114-124、226-238`；`config.yml:589-604`
- 复核补充：① 默认 bind 127.0.0.1 正确；② 空 whitelist 全 403（fail-closed）正确；③ 缺失败限制（R13-L5 已报）+ 密码比较 `authUsername.equals(parts[0]) && authPassword.equals(parts[1])` 非常数时间（时序侧信道，与无失败限制叠加）；④ 固定 2 线程池，慢查询时排队（DoS 面小）；⑤ dashboard HTML 引 CDN echarts——离线环境图表不渲染（纯展示瑕疵）。
- 修法（归 R13 汇总）：MessageDigest.isEqual；失败计数/延迟；config 提示改默认密码。

## 待验证区

### R9-V1 [data·待验证] 报告文件名同秒碰撞：时间戳仅到秒（HHmmss），同玩家同周期同秒双击 → 第二次覆盖第一次（内容几乎相同）。**玩家名清洗为幂等映射（[A-Za-z0-9_]），不同玩家碰撞实际不可能**。定低影响。
- 位置：`core/manager/ReportGenerator.java:598-603`

### R9-V2 [thread·待验证] StatisticsHttpServer 请求线程 buildStatsJson 五个 DB 方法逐个自持锁，**非整体原子**——HTTP 线程看到的数字来自不同时间点快照；请求线程与主线程争抢单连接锁（单连接共享债具象化）。需压力实测。诚实申报：未在运行期验证。

## 豁免挑战
（无。E15（家长报告 D11）经核验与实现一致：config.yml:627 respect-privacy 默认 false + ReportGenerator isHiddenInSquare 仅在 respectPrivacy=true 时调用（189 行）——自洽。）

## 范围外发现

### R9-X1 [已证伪·编排者裁决] 声称 deletePlayerData 未覆盖 social_feed/social_cheers —— **证伪，R3 正确**
- **裁决（2026-10-18 编排者读源码原文）**：`DatabaseManager.java:2369-2399` 明确存在 R-03 清收三步——① `DELETE FROM social_cheers WHERE cheerer_uuid = ?`（2384-2386 行附近：发出）；② `WHERE feed_id IN (SELECT id FROM social_feed WHERE player_uuid = ?)`（收到，先于③）；③ `DELETE FROM social_feed WHERE player_uuid = ?`。R9 该条只读了函数头注释的"13/15 表"字样、未读全 2331-2401 函数体——**R3 的逐表核对表正确（其证据行 2376/2384/2393 与实际源码一致）**。本条撤销，不计入最终账册。

### R9-X2 [i18n·归 R13] MessageUtil.getSenderMessage 对控制台默认英文（115 行 `: "en"`）——/lea report 控制台执行时聊天反馈英文，报告文件强制中文。归 R13 判断是否属设计（与 R10-L4 同根）。

## 前任台账处置（本域）
- 前任 5 条待核：4 条成立（①export 主线程 IO→H1；②Q4 时区→H2；③broadcast 长度→H3；⑤statistics reload→M1），1 条**核销**（④reset 缓存其实已清，PlayerDataStore:822-824，R-03 注释为证）。
- 前任 11 条"已核"全部采纳为正面清单（标注 [前任核验采纳]）：权限逐子命令/report 特例/tab 补全权限过滤/reset 清理顺序/generateSingle 线程模型/StatisticsHttpServer 持锁查库+静态 HTML 无注入/报告真源 player_sessions（D1 回归通过）/文件名白名单防穿越/resolvePlayerUUID 主线程/applyClassAdd 满员持锁+拆箱正确/报告强制中文+[?key] fail-visible。
- 补注（R9 新观察）：报告 TXT 为纯文本非 CSV，玩家名 [A-Za-z0-9_] 无换行面——项目约束下无注入面；DataExporter escapeCsv 正确。
- D8 回归补记：R-02 死变量删除后未引入统计双计/漏计（sumAnswered/sumCorrect/sumScore 单次累计 1050-1072）。

## 审查覆盖声明
- **实际读了**（全文或关键段）：AdminCommand.java（全文 401 行）、AdminCommandHandler.java（全文 864 行）、StatisticsHttpServer.java（全文 476 行）、DataExporter.java（全文）、ReportGenerator.java（约 90% 分段）。
- 辅助核验（grep/局部读）：ProxyMessenger、HotReloadManager（全文 67 行）、PlayerDataStore 相关段、DatabaseManager 建表/deletePlayerData/索引/统计、AchievementManager、SeasonManager、WordManagerImpl、WordBankValidator、MessageUtil、LetsLearnEnglish 接线、plugin.yml、config.yml、zh/en.yml、dev-findings 台账。
- **没做完/没做**：ReportGenerator 的 adviceLines/dominantDifficulty/activeDaySuffix/weekdayName 具体运算未逐行推演；StatisticsHttpServer 运行期行为未跑；/lea report 离线玩家完整路径未实机跑；class 命令族异步链未验证 scheduler 停用窗口的所有分支（守卫已阅读核过）。
