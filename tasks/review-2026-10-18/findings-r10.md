# findings-r10 基础设施与接线（审查 Agent 产出）
- 审查范围：LetsLearnEnglish.java / ConfigManager.java / MessageUtil.java / VersionAdapter.java / HotReloadManager.java / UpdateChecker.java / LetsLearnEnglishAPI.java / LLEPapiExpansion.java / pom.xml / plugin.yml / scripts/audit-i18n-keys.js
- 审查时间：2026-10-18
- 结论摘要：高1条 / 中5条 / 低5条 / 待验证2条 / 豁免挑战0条
- 备注：本文件由编排者按其返回全文原样保存（该 agent 的 Write 被 harness 拒绝）

## 高危

### R10-H1 [data·高·高] 同日重启后单词挑战奖励可重复发放（刷金币，daily-cap 不拦）
- 位置：`core/DailyChallengeManager.java:103,130,665-671,704-711,744`；调用链 `core/word/WordLearningManager.java:226`；`listener/PlayerJoinListener.java:106`
- 证据：
```java
// DailyChallengeManager.java:704-711 —— 已付判定唯一依据是内存 Set，重启即空
private boolean hasPendingWordReward(UUID playerId, String today) {
    if (wordChallengeClaimedToday.contains(playerId)) {
        return false;
    }
    return plugin.getDatabaseManager().isDailyChallengeWordsCompleted(playerId, today);
}
// :665-671 onWordCorrect 每次答对首查该判定
if (hasPendingWordReward(playerId, today)) {
    onAllWordChallengesComplete(player);
    return;
}
// :744 发放成功后只写内存：wordChallengeClaimedToday.add(playerId);
```
`daily_challenge_words` 表无 claimed/reward 列（grep 全库无 word 侧 claimed 持久化；行为挑战表 `daily_challenges.claimed` 有列——两者不对称）。
- 复现：玩家当天完成全部单词挑战领到奖励（默认 100 金币）→ 同日重启服务器 → 玩家答对任意一个单词 → `onWordCorrect` 首查 pending：内存 Set 空 + DB「今日已完成」=true → `onAllWordChallengesComplete` 再次发放 + 庆祝文案。每次同日重启可再刷一次；`RewardEngine.todayCoinsCache` 同为内存态（F1-22 已裁决可丢），重启后日上限从 0 重计，**cap 也不拦**。
- 影响：可复现的刷钱路径（100 金币/次重启）；R-31/M12 的 javadoc 明确声称"绝不会重复补发/无双发风险"（`:891-899`），其论证只覆盖午夜路径，遗漏重启路径——属修复引入的回归性盲区，不能按 F1-22「防刷上限非账本」豁免（本处丢失的是"已付台账"而非上限）。
- 修法：持久化已付标记——`daily_challenge_words` 增 `reward_paid`（或独立台账行，发放成功条件 UPDATE 置位）；`hasPendingWordReward` 改以 DB 标记为准。至少：重启后不得对"DB 已完成且无 pending 标记"的玩家自动补发——需要持久化的 pending 真源。

## 中危

### R10-M1 [logic·中·确定] `/lea reload` 关闭 daily-reminder 后定时提醒照发——任务体无 enabled 门，config 注释称"关闭后不再发送"
- 位置：`LetsLearnEnglish.java:372-374,409-429`；`resources/config.yml:491-493`
- 证据：
```java
// LetsLearnEnglish.java:409-419（任务体——只在 enable 时判过一次 enabled）
getServer().getScheduler().runTaskTimer(this, () -> {
    ...
    if (currentMinute == 0 && effectiveHours.contains(currentHour) && !remindedHours.contains(currentHour)) {
        ... for (Player player : getServer().getOnlinePlayers()) { player.sendMessage(buildDailyReminderMessage(player)); }
// config.yml:492 "关闭后：定时提醒与进服提醒（on-join）都不再发送"
```
对照：ReviewScheduler 任务体有 reload 生效门（`checkAndNotifyReviews` 首行点读，注释明言"reload 不重建任务，体内门是唯一保障"），本任务没有。
- 复现：`daily-reminder.enabled: false`（或改 reminder-hours）→ `/lea reload` → 收到 reload-success 提示 → 到 reminder-hours 仍照发（旧计划）。join 通道有实时门（`sendJoinDailyReminderIfEnabled`），两通道行为分裂。
- 影响：管理员无法热关闭提醒；注释与行为不符（误导）。
- 修法：任务体首行加 `if (!configManager.getMainConfig().getBoolean("daily-reminder.enabled", true)) return;`，或 config 注释改为"改动需重启"。

### R10-M2 [data·中·高] messages 版本升级 `saveResource(path,true)` 无备份静默覆盖用户编辑
- 位置：`config/ConfigManager.java:211-228`
- 证据：
```java
// ConfigManager.java:216-222
FileConfiguration existing = YamlConfiguration.loadConfiguration(file);
int version = existing.getInt("config-version", 0);
if (version < MESSAGE_CONFIG_VERSION) {
    plugin.getLogger().info("Updating " + path + " (v" + version + " -> v" + MESSAGE_CONFIG_VERSION + ")");
    plugin.saveResource(path, true);   // 直接覆盖，无 .bak、无提示用户
}
```
每次 `loadAllConfigs`（含 `/lea reload`）都走此路径。
- 复现：用户把 zh.yml 文案改过、config-version 保持 ≤13（老 jar 或忘改）→ 升级到本版首次 reload → 整文件被 JAR 内 v14 覆盖，自定义全部丢失，仅 info 日志。
- 影响：静默丢失用户定制（文案/翻译）；数据不可恢复。D14 三处（zh=14/en=14/常量=14）本身一致（回归 PASS），这是升级窗口机制问题。
- 修法：升级前 `Files.copy(file, file+".bak")` 并在日志/i18n 中提示旧文件已备份。

### R10-M3 [perf·中·高] auto-save-interval 无钳位/校验：设 0 被 Bukkit 强制为 period=1 tick 的保存风暴（文档称范围 1-60）
- 位置：`LetsLearnEnglish.java:355-364`；`resources/config.yml:31-34`
- 证据：
```java
// LetsLearnEnglish.java:355-364
int saveInterval = configManager.getMainConfig().getInt("general.auto-save-interval", 5);
getServer().getScheduler().runTaskTimer(this, () -> {
    List<UUID> onlinePlayerIds = ...
    getServer().getScheduler().runTaskAsynchronously(this, () -> { onlinePlayerIds.forEach(...savePlayerData...); });
}, saveInterval * 1200L, saveInterval * 1200L);
```
CraftScheduler 字节码实测（1.21.4）：`period == 0 → 1`（`lconst_0 → lconst_1`），delay 负数 → 0。对照 `update-interval` 有 `Math.max(30, Math.min(3600, ...))` 钳位，此处零校验。
- 复现：`auto-save-interval: 0`（或非数字值被 getInt 归 0）→ 重启 → 每个 tick 主线程复制 UUID 列表 + 提交异步批量保存 → 对单连接 DB 形成 20Hz 写压，可观测卡服。
- 影响：配置误设即服务器级性能事故（注释明文写"设置过短会增加数据库写入压力"，但没防呆）。
- 修法：照 update-interval 钳位 `Math.max(1, Math.min(60, saveInterval))` 并 WARNING；或 0 显式语义化（禁用）。

### R10-M4 [data·中·高] 备份完整性校验对象错误：quick_check 查的是源库不是拷贝件；拷贝在锁外且非 WAL
- 位置：`LetsLearnEnglish.java:641-666`
- 证据：
```java
// :649 拷贝（不在 connectionLock 内）
Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
// :650-661 校验的是 databaseManager.getConnection()（源库），不是 backupFile
synchronized (databaseManager.getConnectionLock()) {
try (PreparedStatement stmt = databaseManager.getConnection()
        .prepareStatement("PRAGMA quick_check")) {
    ... Files.deleteIfExists(backupFile.toPath());
        getLogger().warning("Database backup rejected: source quick_check failed.");
```
全仓无 `PRAGMA journal_mode=WAL`（grep 仅此处 wal_checkpoint），即 rollback-journal 模式下 checkpoint 是 no-op（注释"Flush SQLite WAL pages"失实）；拷贝期间并发写可能撕裂拷贝件。
- 复现：备份任务与 auto-save 写并发 → 拷贝可能不自洽；quick_check 对健康源库恒 ok → 撕裂备份被记 "Database backup created"。
- 影响：灾难恢复时才发现备份不可用（安全网失效）。
- 修法：对备份文件单独开连接 `jdbc:sqlite:<backup>` 跑 `PRAGMA quick_check`（或 integrity_check）；拷贝移入 connectionLock 保护段。

### R10-M5 [config·中·确定] config.yml 头部"reload 即可热重载"的 blanket 承诺 vs 多个键快照后零生效（告知一致性）
- 位置：`resources/config.yml:7`；快照点 `LetsLearnEnglish.java:355,379,400,479`、`core/ai/TtsManager.java:45-48`、`core/manager/StatisticsHttpServer.java:52-57`
- 证据：
```yaml
# config.yml:7  - 修改此文件后，输入 /lea reload 即可热重载，无需重启服务器
```
```java
// TtsManager.java:45-48 构造时快照，reload 不重建实例 → tts.* 改动全不生效
var config = plugin.getConfigManager().getMainConfig();
this.enabled = config.getBoolean("tts.enabled", true);
this.cacheDays = Math.max(1, config.getInt("tts.cache-days", 7));
this.apiKey = config.getString("tts.voicerss-api-key", "");
```
同类：`general.backup.*`（:479-486 快照）、`auto-save-interval`、`daily-reminder.*`（M1）、`statistics-server.*`（构造快照；端口/认证/白名单改动需重启）。仅 `update-interval` 与 `review.intervals` 有"需重启"注释（D13/B5 已注）。
- 复现：管理员改 `tts.enabled: false` → `/lea reload` → 收到"配置已重新加载！" → 发音功能照旧。
- 影响：误导操作者；reload-success 文案放大误解。
- 修法：给上述键补齐"需重启生效"注释（对齐 update-interval 先例），或把这些值改点读/重建实例。

## 低危

### R10-L1 [deadcode·低·确定] VersionAdapter.getMaterial/getSound 零调用
- 位置：`util/VersionAdapter.java:46-65`。证据：`grep -rn "\.getMaterial(\|\.getSound(" src/main/java`（排除本类）零命中。修法：删除或保留但 javadoc 标注预留。

### R10-L2 [bug·低·高] getGuiMaterial 的 `toUpperCase()` 未指定 Locale（tr_TR 下 "i"→"İ" 设不了 Material）
- 位置：`config/ConfigManager.java:389`：`return Material.valueOf(matName.toUpperCase());`。土耳其语环境管理员小写写 material 名（如 "item_frame"→"ITEM_FRAME" 含 i）会静默回落默认材质。修法：`toUpperCase(Locale.ROOT)`。

### R10-L3 [quality·低·确定] joinReminderSent 无任何驱逐（长跑内存缓涨）
- 位置：`LetsLearnEnglish.java:136,425,458`。quit/disable 均不清（R-86p 注释"quit 不清"是当日去重需要），但跨日后也无清理点——每个曾进服 UUID 永久留一条。量级小（F1-24 同级），修法：午夜 rollover 主线程段顺手 `removeIf(date != today)`。

### R10-L4 [i18n·低·确定] 非玩家 sender（控制台）硬编码 "en" 语言
- 位置：`util/MessageUtil.java:112-118`：`String lang = (sender instanceof Player player) ? ... : "en";`。中文服务器控制台/命令方块收到的都是英文文案。修法：回落 `general.language`（与 R-55l 统一口径一致）。

### R10-L5 [quality·低·确定] audit-i18n-keys.js：sections 白名单遗漏 `season`/`class`（扫描盲区）+ 尾缀 '-' 误报机理
- 位置：`scripts/audit-i18n-keys.js:5,45-48`。证据：zh.yml/en.yml 顶层存在 `season:` 与 `class:` 且代码大量引用 `season.started-broadcast` / `class.board-title` 等（grep 实据），但白名单 `sections` 无这两个顶层——这些键的"引用但缺键"漂移永不被脚本发现。尾缀误报源自正则 `..."([a-z][a-z0-9_.-]+)"` 把前缀拼接 `"achievement-wall.category-" +` 当字面键。修法：sections 补齐 season/class（建议改为"取 yml 顶层键集合"动态生成），对以 `-`/`.` 结尾的键跳过并计数提示"疑似拼接键"。

## 待验证区

- T1：onDisable 与在途异步保存的窗口——`databaseManager.shutdown()`（DatabaseManager.java:2691）关连接后，正在执行的异步 auto-save 调 `getConnection()`（:455-467）会触发 `initialize()` **重开** 连接（关服后复活 DB 句柄）。排队任务会被 cancelTasks 清掉，只有恰在运行的会命中，窗口极窄。验证法：临时在 savePlayerData 里 count 一次 getConnection().isClosed() 前的 initialize 调用，跑 `/stop` 压测。
- T2：`%lle_streak%`/`%lle_achievements%` 不在 RESULTS_GATED（LLEPapiExpansion.java:65-66,252-279），`showResults=false` 的他人查看仍返回真实值——是隐私族定义 intentionally（javadoc 明言成就位不外扩）还是泄漏缺口，需与 R4/P3-03 口径裁决对齐。

## 豁免挑战
（无——E6 四零校验权限现状按裁决只报现状；E18 停机 catch-up 语义已遵；未发现豁免清单有错。）

## 范围外发现
- 1 行：plugin.yml 声明 `letslearnenglish.use / word.* / dialogue.* / progress.*` 四权限零校验（B5 现状，登记给 R13/E6）；Tab 补全经字节码核实走 `PluginCommand` executor-as-TabCompleter 回落（无 bug，供 R8 参考）；`pom.xml` 的 mysql-connector-j(8.3.0, compile) 未随 JAR 打包（无 shade、无 plugin.yml `libraries:`），实测 Paper 1.21.4 自带 mysql-connector-j 9.1.0（META-INF/libraries），故运行时可用但依赖宿主实现——建议改 provided+文档注明或加 libraries 声明（归 R13/发布面记录，未立案）。

## 审查覆盖声明
- 全文精读：LetsLearnEnglish.java、ConfigManager.java、MessageUtil.java、VersionAdapter.java、HotReloadManager.java、UpdateChecker.java、LetsLearnEnglishAPI.java、LLEPapiExpansion.java、pom.xml、plugin.yml、scripts/audit-i18n-keys.js、target JAR 核对、CraftScheduler/PluginCommand 字节码核对。
- 按需读（为验证调用链）：DatabaseManager、PlayerDataStore、DailyChallengeManager、RewardEngine、WordLearningManager、WordManagerImpl、ReviewScheduler、PlayerJoinListener、TtsManager、StatisticsHttpServer、SocialManager、SeasonManager、ProxyMessenger、AdminCommand、LearnCommand、MainMenu 节选、zh/en.yml、config.yml。
- 回归核验：D2（streak 真源 checkin_data，PAPI 与 ProgressManagerImpl 同源）PASS；D3（午夜两段式：异步异常吞获+finally 自续+isEnabled 门+IllegalStateException 双捕获）PASS；D13（update-interval 接线+钳位+需重启注释）PASS；D14（14/14/14 三处一致；升级覆盖窗口另见 M2）PASS；D21（achievements.yml 死链已清，仅注释残留）PASS；F0-08（禁用路径+notifyPlayer 守卫未发起 NPE）PASS；RH1 onEnable DI 顺序全链核对（各 manager 构造体不触碰后置字段；WordLearningManager 构造期 `plugin.getWordManager()` 在赋值后）PASS，onDisable 顺序（sessions→duel→lang→save→unregister→shutdown(HTTP/TTS/AI/DB 最后)）PASS，未发现缺失 flush（todayCoinsCache 属 F1-22 已裁决；H1 即该裁决的放大回归）；RH8 重复调度（reload 不重排任何任务，midnightTask 先 cancel 再排）PASS。
- 未做：未跑服务器实测（静态审查），H1/T1 的运行期坐实需按复现步骤实测。
