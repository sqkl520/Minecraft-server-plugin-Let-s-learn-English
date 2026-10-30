# 高危修复计划（fix-plan）— review-2026-10-18 批 A/B + R2-H1

- 基线：`3bf8eae`（版本保持 1.0.7-SNAPSHOT，不动 pom）
- 产出：规划 Agent 骨架落盘后停滞（14 分钟无填充）被停止；编排者接管直写（全部行号已对当前工作树逐处核实）
- 行号基准：当前工作树；与 findings 不符处以源码为准

---

## A. 修复流划分与白名单互斥表（8 流，文件零重叠）

| 流 | 修复项 | 文件白名单 | 依赖 |
|----|--------|-----------|------|
| S1 | R10-H1（挑战奖励持久化）+ R3-H2 清理侧（ai_chat_feedback 清理） | `core/DailyChallengeManager.java`、`data/DatabaseManager.java`、`resources/schema.sql` | 无 |
| S2 | R4-H1（tryUnlock 懒加载） | `core/achievement/AchievementManager.java` | 无 |
| S3 | R3-H3（锁外取 Connection 引用，10 处） | `data/PlayerDataStore.java` | 无 |
| S4 | R3-H1（备份 VACUUM INTO） | `LetsLearnEnglish.java` | 无 |
| S5 | R9-H1（export 异步化）+ R9-H3/R7-H1（广播长度上限） | `command/AdminCommand.java`、`core/manager/DataExporter.java`、`core/ProxyMessenger.java` | 无 |
| S6 | R7-H2（结算窗口校验） | `core/season/SeasonManager.java` | 无 |
| S7 | R2-H1（assistant 写入包 runAsync） | `core/ai/AIChatManager.java` | 无 |
| S8 | 时区读侧（R9-H2 + R3-H2 显示侧） | `core/manager/ReportGenerator.java`、`gui/ChatHistoryGUI.java` | 无 |

**冲突分析**：R3-H2 清理侧原计划独立成流与 S1 同触 DatabaseManager → 已合并进 S1。其余流文件两两不重叠。S3 与 S1 同属 data 包但不同文件，无冲突。

---

## B. 每流详细规格

### B-S1：R10-H1 挑战奖励持久化 + R3-H2 清理侧

**根因**：`wordChallengeClaimedToday`（内存 Set）是"已付"唯一台账；`daily_challenge_words` 表无 claimed 列。同日重启 → Set 空 + DB `isDailyChallengeWordsCompleted=true` → `onWordCorrect`（:664-670）重入 `onAllWordChallengesComplete` 重复发钱；daily-cap 记忆同样重启清零不拦。

**改法（DatabaseManager.java）**：
1. `createTables` 内新增表（照 `daily_challenge_words` 建表风格，SQLite/MySQL 两分支同式）：
   ```sql
   CREATE TABLE IF NOT EXISTS {prefix}daily_word_reward_claims (
     player_uuid VARCHAR(36) NOT NULL,
     challenge_date DATE NOT NULL,
     claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
     UNIQUE(player_uuid, challenge_date)
   )
   ```
2. 增两方法（照 updateCheckIn 的锁风格）：
   - `public void markDailyWordRewardClaimed(UUID playerId, String date)`：SQLite `INSERT OR IGNORE INTO`；MySQL `INSERT IGNORE INTO`；synchronized(connectionLock)；SQLException → WARNING（静默降级：标记失败最坏后果=重启后可重复，与修复前持平，不崩）。
   - `public boolean isDailyWordRewardClaimed(UUID playerId, String date)`：`SELECT COUNT(*) ... WHERE player_uuid=? AND challenge_date=?`；异常 → false + WARNING。
3. `deletePlayerData` 的 `playerUuidTables` 数组（:2340-2343）补 `"daily_word_reward_claims"` —— **这是审查反复警告的"后建表盲区"，必须补**。
4. `cleanupOldChatHistory`（:688-709）追加 ai_chat_feedback 清理：同一 `dateExpr` 阈值、列 `created_at`（已核 :258 为 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`）：
   ```java
   String sql2 = "DELETE FROM " + tablePrefix + "ai_chat_feedback WHERE created_at < " + dateExpr;
   ```
   并补注释：保留期窗口语义（非精确边界），两后端各自自洽、8h 级偏移可接受（R3-H2 裁决）。保留期逻辑（不断言被删行的时区精确性）不改行为。

**改法（DailyChallengeManager.java）**：
1. `hasPendingWordReward`（:704-711）改为三层：
   ```java
   if (wordChallengeClaimedToday.contains(playerId)) return false;           // 内存快路径
   if (plugin.getDatabaseManager().isDailyWordRewardClaimed(playerId, today)) {
       wordChallengeClaimedToday.add(playerId);                              // 回填内存
       return false;
   }
   return plugin.getDatabaseManager().isDailyChallengeWordsCompleted(playerId, today);
   ```
2. `onAllWordChallengesComplete(Player player)`（:729+）增加重载 `onAllWordChallengesComplete(Player player, String claimDate)`：
   - 正常调用点（:666-670 的 onWordCorrect、rollover 补发外的其他）传 `LocalDate.now().toString()`；
   - **rollover 补发路径**（`hasPendingWordRewardForDate` :905-925 内的 `onAllWordChallengesComplete(player)` 调用）传 `date` 参数（昨日）——**关键：不能把昨日补发标记成"今日已领"，否则会误封今日挑战奖励**；
   - 成功发放分支（deposit 成功）：`wordChallengeClaimedToday.add(playerId); plugin.getDatabaseManager().markDailyWordRewardClaimed(playerId, claimDate);`
   - 经济禁用终态分支（:736-748）：同样写 DB 标记（claimDate）——防重启后每答对一题重复刷"economy-disabled"提示。
3. `hasPendingWordRewardForDate`（:905+）在内存检查后、completed 检查前插入：
   ```java
   if (plugin.getDatabaseManager().isDailyWordRewardClaimed(playerId, date)) {
       return false; // 该日已付（DB 真源）
   }
   ```
4. `isWordRewardPaidToday`（:698-700）：先 grep 全仓调用点确认语义；如有 UI 消费（"已领"展示类），body 改为 `wordChallengeClaimedToday.contains(playerId) || db.isDailyWordRewardClaimed(playerId, LocalDate.now().toString())`——内存已含则短路免查库。
5. `clearAllWordChallengeClaims`（:859-861）保持不变（内存缓存清空；DB 台账是防重启真源）。

**回归验证点**：正常"完成全部词 → 发奖 → 同日再答对 → 不重发（内存路径）"；"重启后答对 → hasPending 查 DB claim → 不重发"；"跨午夜 rollover：昨日 pending 补发 → 标昨日 → clearAll 清内存 → 今日答对正常发"；"经济禁用 → 标记 → 重启不刷提示"；"deletePlayerData 后重进 → isDailyWordRewardClaimed false → 当日可正常获得"。

### B-S2：R4-H1 tryUnlock 懒加载

**位置**：`AchievementManager.java:181`（已核）。**改法**：把
```java
Set<String> unlocked = unlockedCache.computeIfAbsent(playerId, k -> new HashSet<>());
```
改为（与同文件 hasAchievement :104-111 范式一致）：
```java
Set<String> unlocked = unlockedCache.get(playerId);
if (unlocked == null) {
    loadPlayerAchievements(playerId);
    unlocked = unlockedCache.get(playerId);
    if (unlocked == null) {                    // 防御：load 异常路径也无空指纹
        unlocked = new HashSet<>();
        unlockedCache.put(playerId, unlocked);
    }
}
```
**先核实 `loadPlayerAchievements` 尾部保证 put**（若它内部已经 put 则外层防御分支略；执行者读 :120-160 段确认后照实微调）。

### B-S3：R3-H3 锁外取 Connection 引用（10 处机械修）

**已核清单（PlayerDataStore.java，全部为"conn 在 synchronized 之前赋值、块内使用"）**：
`:241` recordSession / `:268` getSessionDays / `:293` loadPlayerData 第一块（:350 第二块已是 F1-20 范式，勿动）/ `:529` savePlayerData / `:948` saveProfileSettings / `:968` loadProfileSettings / `:1092` persistLongestStreak / `:1131` getRankedPlayers / `:1191` getRankedPlayersForPeriod / `:1272` getRankedPlayersForPeriodFiltered

**改法**（每处）：把 `Connection conn = databaseManager.getConnection();` 行**移进**紧随其后的 `synchronized (databaseManager.getConnectionLock()) {` 块内首行。**注意**：1131/1191/1272 三处 conn 与 synchronized 之间隔有注释与列表初始化——仅把赋值语句移到锁内首行，其余语句原地不动。若某方法在 sync 块结束后仍有对 conn 的使用（执行者逐处读到块尾确认），在块后补一次 `conn = databaseManager.getConnection();` 重取（照 loadPlayerData:350 范式）。
**验收**：改完后 `grep -n "Connection conn = databaseManager.getConnection()" PlayerDataStore.java` 的每一处命中行号都必须 > 对应 `synchronized` 行号。

### B-S4：R3-H1 备份改 VACUUM INTO

**位置**：`LetsLearnEnglish.java:641-666`（backupDatabase 内 wal_checkpoint + Files.copy + quick_check 三段）。

**改法**：
```java
private void backupDatabase(int maxBackups) {
    // ... dbType / dbFile / backupDir / timestamp / backupFile 构造逻辑保持不变 ...
    try {
        synchronized (databaseManager.getConnectionLock()) {
            // VACUUM INTO：SQLite 3.27+ 原子一致快照（替代"wal_checkpoint(TRUNCATE)
            // + Files.copy"——原 PRAGMA 对非 WAL 库是空操作、拷贝在锁外可撕裂；
            // R3-H1）。路径为内部构造（数据目录+时间戳），单引号转义防御。
            String target = backupFile.getAbsolutePath().replace("'", "''");
            try (PreparedStatement stmt = databaseManager.getConnection()
                    .prepareStatement("VACUUM INTO '" + target + "'")) {
                stmt.execute();
            }
        }
        // 独立只读连接校验产物（原实现查的是活连接=查错对象，R3-H1 第二修）
        boolean ok = checkBackupIntegrity(backupFile);
        if (!ok) {
            Files.deleteIfExists(backupFile.toPath());
            getLogger().warning("Database backup rejected: backup quick_check failed.");
            return;
        }
        getLogger().info("Database backup created: " + backupFile.getName());
    } catch (IOException | SQLException e) {
        getLogger().warning("Failed to create database backup: " + e.getMessage());
        return;
    }
    // ... retention（maxBackups 清理）逻辑保持不变 ...
}

private boolean checkBackupIntegrity(File backupFile) {
    String url = "jdbc:sqlite:" + backupFile.getAbsolutePath();
    try (Connection conn = java.sql.DriverManager.getConnection(url);
         PreparedStatement stmt = conn.prepareStatement("PRAGMA quick_check");
         ResultSet rs = stmt.executeQuery()) {
        return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
    } catch (SQLException e) {
        getLogger().warning("Backup integrity check failed to run: " + e.getMessage());
        return false;
    }
}
```
**注意**：① 驱动已由 `jdbc:sqlite:` 前缀自动加载（同 :80 先例），无需 Class.forName；② `VACUUM INTO` 目标已存在时报错——时间戳文件名天然唯一，无需处理；③ 保留 `isSQLite()` 门（非 SQLite 后端照旧跳过备份）；④ 删除旧的三段（wal_checkpoint/copy/quick_check-on-live）与相关注释；⑤ Files.copy/StandardCopyOption 若因此不再使用需清理 import（执行者 grep 确认）。

### B-S5：R9-H1 export 异步化 + R9-H3/R7-H1 广播长度上限

**R9-H1 改法**：
1. `DataExporter.java`：把 `exportAllData()` 重构为**纯文件 IO 的同步方法**（调用方保证线程）：
   ```java
   public ExportResult exportAllData(java.util.List<java.util.UUID> playerIds,
                                     java.util.List<String> playerNames) {
   ```
   —— 快照参数：`playerIds.get(i)` 与 `playerNames.get(i)` 对应；内部循环用 `playerNames.get(i)` 写名字列、`playerDataStore.getPlayerStats(id)` 取统计；不再触 `plugin.getServer().getOnlinePlayers()`（该调用移入 AdminCommand 主线程段）。目录创建/文件名/escapeCsv 逻辑不变。**保留**原无参方法吗？——不保留（防死代码）；直接改签名（唯一调用点在 AdminCommand）。
2. `AdminCommand.java` case "export"（:66-76）改为：
   ```java
   case "export":
       // R9-H1：主线程仅取快照（UUID+名字），文件 IO 与 cache-miss 查库全异步
       var online = new java.util.ArrayList<>(plugin.getServer().getOnlinePlayers());
       java.util.List<java.util.UUID> ids = new java.util.ArrayList<>(online.size());
       java.util.List<String> names = new java.util.ArrayList<>(online.size());
       for (var p : online) { ids.add(p.getUniqueId()); names.add(p.getName()); }
       try {
           plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
               DataExporter.ExportResult r = plugin.getDataExporter().exportAllData(ids, names);
               plugin.getServer().getScheduler().runTask(plugin, () -> {
                   if (r.success()) {
                       sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                               "admin.export-complete", "file", r.filePath()));
                   } else {
                       sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                               "admin.export-failed"));
                   }
               });
           });
       } catch (IllegalStateException e) {
           sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "admin.export-failed"));
       }
       break;
   ```
   （`sender` 需 effectively final——已是方法参数，OK；内层 runTask 也包 try/catch(IllegalStateException) 兜底关服窗口。）
3. **线程安全确认**：`getPlayerStats` cache-miss 走 loadPlayerData（锁内 DB + cachePlayerName 有 `Bukkit.isPrimaryThread()` 守卫，F1-21）——异步调用安全（R13 已核该路径"仅 DB"）。名字列用快照名，不依赖异步 getPlayerName。

**R9-H3/R7-H1 改法**：
1. `ProxyMessenger.java`：加常量与防御：
   ```java
   /** 插件消息通道安全上限（字节）。Minecraft 网络层单包 32767 字节含通道名/
    *  子通道/长度前缀等开销，载荷保守上限取 30000（R9-H3/R7-H1）。 */
   private static final int MAX_PAYLOAD_BYTES = 30000;
   ```
   `sendForward`（:123-147）首部加：
   ```java
   if (data == null) return;
   byte[] utf8 = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
   if (utf8.length > MAX_PAYLOAD_BYTES) {
       // 纵深防御：命令层已拒绝超长（AdminCommand），此处兜底截断防异常逃逸
       plugin.getLogger().warning("Proxy payload truncated ("
               + utf8.length + " > " + MAX_PAYLOAD_BYTES + " bytes)");
       data = truncateUtf8(data, MAX_PAYLOAD_BYTES);
   }
   ```
   尾部 `messenger.sendPluginMessage(...)` 包 `try { } catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Failed to send proxy message", e); }`（防 32767 网络层异常逃逸；Level 已 import）。
   私有 helper：`private static String truncateUtf8(String s, int maxBytes)`——逐字符累计 UTF-8 长度（char 判断不超过 max 即可，简化：循环 substring 累计 `String.valueOf(c).getBytes(UTF_8).length`，中文 3 字节）截到 ≤ maxBytes。
2. `AdminCommand.handleBroadcast`（:171-177）前置拒绝：
   ```java
   String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
   String formatted = ChatColor.translateAlternateColorCodes('&',
           plugin.getMessageUtil().getMessage("admin.broadcast-prefix") + message);
   if (formatted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 30000) {
       sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
               "admin.broadcast-too-long"));
       return;
   }
   ```
   （常量与 ProxyMessenger 各自持有（跨类不引），或 AdminCommand 引用 `ProxyMessenger.MAX_PAYLOAD_BYTES`——若改 public 常量则两处单源；执行者任选其一，推荐把 ProxyMessenger 常量改 `public static final` 并引用。）
3. **i18n 键（编排者 fan-in 统一补，不在流内改 yml）**：`admin.broadcast-too-long`：
   - zh：`"&c广播内容过长（上限 30000 字节），请缩短后重试"`（admin.* 键风格照现有 admin.broadcast-hint——执行者 grep 确认该节 key 是否自带前缀）
   - en：`"&cBroadcast too long (30000 bytes max). Please shorten it."`

### B-S6：R7-H2 结算窗口校验

**位置**：`SeasonManager.runSettleSweep`（:358+）的 `for (int s = from; s <= targetId; s++)` 循环（:391-393 起）。

**改法**：循环体开头（`db.ensureSeasonRow` 之前）插入：
```java
SeasonWindow window = windowOf(s);
// R7-H2：只结算"真正走完"的季——anchor-date/duration 点读可改，
// 改小锚点会让 currentSeasonId 跳变、targetId 命中仍在进行中的窗口；
// 未走完的窗口跳过（不写台账不发放），防提前切割发奖。
if (!window.end().isBefore(today)) {
    plugin.getLogger().warning("Skipping settlement for season " + s
            + " (window " + window.start() + ".." + window.end()
            + " not complete; anchor/duration changed?)");
    continue;
}
```
并删去循环体内原有的 `SeasonWindow window = windowOf(s);`（已提前声明，避免重复声明冲突——执行者读 :391-394 原文调整变量声明位置）。
**正常路径回归**：正常跨季时 `today ≥ 新季首日`，`windowOf(s)`（s ≤ currentId-1）的 end 恒 < today → 全部照常结算。**验证**：构造"anchor 改小 30 天"推演（静态）：s=1 的 end ≥ today → skip → 不再提前发奖。注意 `ensureSeasonRow` 被跳过——旧行已在表中不受影响（表行由首次启用时建立）。

### B-S7：R2-H1 assistant 写入包 runAsync

**位置**：`AIChatManager.java:960-968`（已核）。

**改法**：把
```java
// Write AI response to DB asynchronously (sessionId captured from enclosing scope)
if (sessionId != null) {
    try {
        plugin.getDatabaseManager().insertChatHistory(playerId, sessionId, "assistant", response);
    } catch (Exception e) {
        debugLog("[" + player.getName() + "] 写入 AI 回复到数据库失败: " + e.getMessage());
    }
}
```
改为（与同文件 :897-907 user 写入同形）：
```java
if (sessionId != null) {
    CompletableFuture.runAsync(() -> {
        if (isShuttingDownOrDisabled()) return;
        try {
            plugin.getDatabaseManager().insertChatHistory(playerId, sessionId, "assistant", response);
        } catch (Exception e) {
            debugLog("[" + player.getName() + "] 写入 AI 回复到数据库失败: " + e.getMessage());
        }
    });
}
```
（`response`/`playerId`/`sessionId` 需 effectively final——执行者确认闭包捕获合法性，必要时补 `final` 局部别名。）

### B-S8：时区读侧（R9-H2 + R3-H2 显示侧）

**冻结约定（上下文）**：SQLite 后端 `ai_chat_history.timestamp` 由 `CURRENT_TIMESTAMP` 写入 = **UTC 字符串**（"yyyy-MM-dd HH:mm:ss"）；MySQL 侧 TIMESTAMP 列按会话时区自洽。**读侧按后端分支**：SQLite → 把"本地日期边界/显示"换算到 UTC；MySQL → 维持本地口径不变。`databaseManager.isSQLite()` 可用（public，全仓有先例）。

**改法（ReportGenerator.java :380-399 Q4）**：
```java
// R9-H2：SQLite 的 timestamp 是 UTC 字符串（CURRENT_TIMESTAMP）；边界必须按
// "本地日 → UTC 区间 [start, nextDayStart)" 换算后比较。MySQL 侧 TIMESTAMP
// 自动时区转换，维持本地字符串边界。
String lowerBound, upperBound;
boolean exclusiveUpper;
if (databaseManager.isSQLite()) {
    java.time.format.DateTimeFormatter F = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    java.time.ZoneId sys = java.time.ZoneId.systemDefault();
    lowerBound = period.from().atStartOfDay(sys)
            .withZoneSameInstant(java.time.ZoneOffset.UTC).format(F);
    upperBound = period.to().plusDays(1).atStartOfDay(sys)
            .withZoneSameInstant(java.time.ZoneOffset.UTC).format(F);
    exclusiveUpper = true;
} else {
    lowerBound = period.from() + " 00:00:00";
    upperBound = period.to() + " 23:59:59";
    exclusiveUpper = false;
}
String op = exclusiveUpper ? "<" : "<=";
String sql4 = "SELECT COUNT(DISTINCT session_id) FROM " + prefix
        + "ai_chat_history WHERE player_uuid = ? AND timestamp >= ? AND timestamp " + op + " ?";
// stmt.setString(2, lowerBound); stmt.setString(3, upperBound);
```
（`period.from()/to()` 为 LocalDate——执行者核实类型；若是 String 则 LocalDate.parse 后同式。）

**改法（ChatHistoryGUI.java :147 与 :296）**：新增私有 helper：
```java
/** R9-H2：SQLite 的 ai_chat_history.timestamp 存储为 UTC；显示转系统时区。
 *  MySQL 维持原样（列按会话时区自洽）。 */
private String formatHistoryTimestamp(String raw, String pattern) {
    java.text.SimpleDateFormat out = new java.text.SimpleDateFormat(pattern);
    if (raw == null) return "";
    if (plugin.getDatabaseManager().isSQLite()) {
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = in.parse(raw);
            if (d != null) return out.format(d);
        } catch (java.text.ParseException ignored) { }
    }
    return raw; // MySQL 或解析失败：原样（防御）
}
```
两处显示点（:147 / :296）改为 `formatHistoryTimestamp(entry.getTimestamp(), "yyyy-MM-dd HH:mm")` / `"MM-dd HH:mm"`（执行者读原文取实际字段名与调用形式适配）。
**核对**：执行者 grep 全仓 `ai_chat_history` 的其他 timestamp 显示消费者（如 getSessionPreview/getChatSessions 返回时间给别处渲染）——发现即用同 helper 语义处理或上报（若属白名单外文件 → 只报不修）。
**Q4 边界回归验算示例（写入验证报告）**：中国时区 period=2026-08-18 → 下界 = 2026-08-17 16:00:00(UTC)，上界 = 2026-08-18 16:00:00(UTC) 排他——此前本地 8/18 07:59 写入的行（UTC 8/17 23:59）落入区间 ✓。

---

## C. 执行者纪律模板（编排者转发时逐字附加）

1. **只改白名单文件**。发现白名单外 bug：只上报（写进最终消息"范围外发现"节），绝不修。
2. **禁止运行 mvn**（并行流会 clobber target/ 目录）——编排者在全部流返回后统一编译。你的自检义务 = 逐处核对修改后的代码语法/括号/import 完整性 + 报告中列出"我改的每个文件每个方法"。
3. **禁止修改**：pom.xml / 版本号 / messages/*.yml / config.yml（i18n 键由编排者 fan-in 统一补——你的代码直接按规格引用新键，运行时缺键返回 [?key] 不崩，属预期中间态）。
4. **行号会漂移**：以规格中的锚点文本（代码片段/方法名）定位，不依赖绝对行号。
5. **改完立即回报**：≤15 行——每文件改了什么（方法级）+ 任何偏离规格之处及理由 + 范围外发现。**不要尝试写报告文件**（harness 禁止），全部放最终消息。
6. 若发现规格与源码不符（如方法名/范式不同）：**以源码为准**按规格意图适配，并在回报中注明。

---

## D. CHANGELOG 条目草案（编排者 fan-in 时使用，追加到 [1.0.7] 节 Fixed 下）

```
- **[修复批 A]** R10-H1：单词挑战奖励已付台账持久化（新表 daily_word_reward_claims + INSERT OR IGNORE 幂等 + rollover 补发按日期标记）——修复同日重启后奖励可重复发放
- **[修复批 A]** R4-H1：tryUnlock 改懒加载（与 hasAchievement 同范式）——修复 /lea reload 后已解锁成就重复解锁并覆盖 unlocked_at
- **[修复批 A]** R3-H3：PlayerDataStore 全部 10 处锁外取 Connection 引用收敛为锁内重取（F1-20 范式全库化）——消除重连窗口静默丢写
- **[修复批 A]** R3-H1：数据库备份改 VACUUM INTO 原子快照 + 独立连接校验备份文件——修复非 WAL 库 checkpoint 空操作 / 锁外拷贝撕裂 / 校验查错对象
- **[修复批 B]** R9-H1：/lea export 异步化（主线程仅快照，文件 IO 与查库全异步）——消除主线程阻塞
- **[修复批 B]** R9-H3/R7-H1：跨服广播长度上限（命令层拒绝 + sendForward 兜底截断/异常捕获）——修复超长广播异常逃逸与跨服丢帧
- **[修复批 B]** R9-H2/R3-H2：时区族（报告 Q4 边界按后端换算 UTC + 历史时间显示转本地）与 ai_chat_feedback 保留期清理——修复中国服 8 小时窗口错位与表无限增长
- **[修复批 B]** R7-H2：赛季结算加窗口完成校验——修复改锚点可对进行中赛季提前切割发奖
- **[修复批 附]** R2-H1：assistant 回复写库包 runAsync（与 user 写入路径同形）——统一线程模型
```

## E. 验证批次计划（3 路红队，全部返回后并行）

- **V1 深度语义（R10-H1 + R7-H2）**：对 S1 修复逐路径反证——重启幂等/午夜补发日期标记/经济禁用终态/deletePlayerData 覆盖/schema 与建表一致；R7-H2 正常跨季照常结算 + 改锚点场景 skip。重点：找**新引入的语义坑**（如补发标错日期导致今日误封）。
- **V2 数据安全（R3-H1 + R3-H3 + S5）**：VACUUM INTO 锁内执行/路径转义/失败路径/保留期不受影响；10 处 conn 改动的逐处核对（锁内重取后变量作用域/块后使用）；广播限长（CJK 字节计数正确性、截断不会劈坏 UTF-8、拒绝消息可读）。
- **V3 全量交叉（S8 时区 + S2/S7 机械 + 整体一致性）**：时区双向换算用具体日期算例验证（含 period 单日/跨月边界）；tryUnlock 懒加载与 hasAchievement 行为等价；runAsync 包裹后闭包变量正确；复查全部 8 流的 diff 无白名单外改动。
- 验证后编排者：全量 `mvn clean verify`（49+ 测试 + jacoco 门）→ 如失败修复 → 补 i18n 键 → CHANGELOG → git 提交（用户未说"可以发布"→ JAR 留 target/，不复制 versions/、不推 GitHub）。
