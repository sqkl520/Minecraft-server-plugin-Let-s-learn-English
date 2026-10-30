# findings-r4 社交+成就+榜单+档案（审查 Agent 产出）
- 审查范围：listener/SocialListener.java、core/social/（SocialManager, FeedTextFormatter）、gui/EnglishSquareGUI.java、core/achievement/（Achievement, AchievementManager）、gui/AchievementWallGUI.java、gui/ProfileGUI.java、gui/LeaderboardGUI.java、core/progress/（Leaderboard, PlayerProgress, ProgressManagerImpl, RewardEngine）、resources/achievements-config.yml（另交叉核对：PlayerDataStore、DatabaseManager、LearnCommand、HotReloadManager、DuelManager/DuelRoom、PlayerJoinListener、AIChatManager、LLEPapiExpansion、LearningCalendarGUI、schema.sql）
- 审查时间：2026-10-18
- 结论摘要：高1条 / 中2条 / 低4条 / 待验证6条 / 豁免挑战0条
- 说明：前任 Agent 已完成 14 文件全量精读并留下 10 条 TODO；本 Agent 承接完成其 TODO ①-⑦⑨⑩ 的核证（结论并入下列条目与"覆盖声明"），中途遭网关 502 击杀一次，剩余少量未读完段如实申报于待验证区。本文件由编排者按其返回全文原样保存。

## 高危

### R4-H1 [data·高·确定] reload 后 tryUnlock 用空 Set 跳库：已解锁成就重复解锁，INSERT OR REPLACE 覆盖 unlocked_at 时间戳并重复播放解锁通知
- 位置：`core/achievement/AchievementManager.java:181`（主犯）、`:197-207`（SQL 覆盖）、`:672-676`（clearCache）、`core/manager/HotReloadManager.java:39-41`（运行期调用点）、`core/achievement/AchievementManager.java:104-111`（正确写法对照）
- 证据：

```java
// AchievementManager.java L175-198（tryUnlock 内，唯一写入口）
public boolean tryUnlock(UUID playerId, Achievement achievement) {
    ...
    Set<String> unlocked = unlockedCache.computeIfAbsent(playerId, k -> new HashSet<>());   // L181 ← 缓存缺失时建空 Set，不查库！
    ...
    if (unlocked.contains(achievement.getId())) {                                          // L186 ← 空 Set 恒 false
        return false;
    }
    ...
    unlocked.add(achievement.getId());
    long unlockedAt = System.currentTimeMillis();                                          // L196 ← 以"现在"为准
```

对照同文件其余四个读取点全部是"get 后 null → loadPlayerAchievements"懒加载：
```java
// L104-111 hasAchievement（getUnlockedAchievements L117 / getUnlockedCount L136 / getUnlockedAt L151 同款）
public boolean hasAchievement(UUID playerId, Achievement achievement) {
    Set<String> unlocked = unlockedCache.get(playerId);
    if (unlocked == null) {
        loadPlayerAchievements(playerId);
        unlocked = unlockedCache.get(playerId);
    }
    return unlocked != null && unlocked.contains(achievement.getId());
}
```

DB 侧两侧分支均覆盖时间列（schema.sql L41-47：`UNIQUE(uuid, achievement_id)`；`unlocked_at BIGINT`）：
```java
// L200-207
String sql = "INSERT OR REPLACE INTO " + ... + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?)";
if (!plugin.getDatabaseManager().isSQLite()) {
    sql = "INSERT INTO " + ... + "(uuid, achievement_id, unlocked_at) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE unlocked_at=VALUES(unlocked_at)";
}
// stmt.setLong(3, unlockedAt);  ← 旧行时间戳被无条件覆盖
```

清缓存链（运行期唯一调用点）：
```java
// HotReloadManager.java L39-41（/lea reload，AdminCommand.java:61 → reloadAll）
if (plugin.getAchievementManager() != null) {
    plugin.getAchievementManager().clearCache();
}
// AchievementManager.java L672-676
public void clearCache() {
    unlockedCache.clear();
    unlockedAtCache.clear();
    achievementConfig = null;
}
```
（`LetsLearnEnglish.java:292-294` onDisable 也调 clearCache，但属停机路径无影响；运行期只有 reload。）

- 复现：在线玩家 A 已解锁 first_word/words_25（DB 有行）→ 管理员执行 `/lea reload`（AdminCommand L61 → reloadAll → clearCache 清空全部玩家两套缓存）→ A 未打开成就墙（不走懒加载重建）直接完成一次会话 → `WordLearningManager.showSessionComplete L743` → `checkSessionAchievements` → 条件命中（如 `totalCorrect>=25`）→ `tryUnlock(WORDS_25)` → computeIfAbsent 建空 Set → contains=false → 前置通过 → **再次"解锁"**：UPSERT 覆盖 unlocked_at 为当前时间 + `notifyUnlock`（聊天双行/Title/音效/**跨服成就广播**）+ `SocialManager.recordAchievement`。reload 后每个条件命中的已解锁成就各重复一次；下一条 `hasAchievement`（懒加载）重建后又恢复正常，形成"reload 窗口内重复、窗口外正常"的间歇性症状。
- 影响：(1) 成就墙"解锁于 {date}"（`AchievementWallGUI.java:124-129` 读 `unlockedAtCache`/DB）被刷新为 reload 后的时间——历史失真；(2) 玩家重复收到已解锁成就通知与跨服广播（社交面尴尬）；(3) 触发 `recordAchievement` 重复写动态（同日常量按 `UNIQUE(player_uuid, feed_date, event_type)` 合并可能虚增 events_count，跨日 reload 则新增虚假动态——见待验证 2）；(4) `getProgressSnapshot` 与统计无影响（派生读）。
- 修法：把 L181 改为与 hasAchievement 同款懒加载：`Set<String> unlocked = unlockedCache.get(playerId); if (unlocked == null) { loadPlayerAchievements(playerId); unlocked = unlockedCache.get(playerId); }`（loadPlayerAchievements 内部已 put 保证非 null 且同时填 unlockedAtCache）。此处是唯一写入口，改一处全链修复；另建议补一条"reload 后重复 tryUnlock 不产生副作用"的单测（现有 49 用例未覆盖）。

## 中危

### R4-M1 [logic·中·高] /le square weekly|monthly|alltime 直达（含主菜单广场按钮）不登记 squareGuis 快照：每次头像点击走现查库重建，打开→点击窗口内排名变化即点错人
- 位置：`command/LearnCommand.java:419`（直达无登记）、`listener/SocialListener.java:302-314`（重建分支）、`:285-287`（有快照时信任）、`:59-67`（域名头部注释声称"任一点击登记后即关闭"）
- 证据：

```java
// LearnCommand.java L410-419（handleSquareCommand，/le square [mode] 直达；主菜单广场按钮走 performCommand("le square")，MainMenuListener.java:138-140）
mode = switch (args[1].toLowerCase(Locale.ROOT)) { ... case "weekly" -> RankingMode.WEEKLY; ... };
new EnglishSquareGUI(plugin).open(player, 1, mode);   // ← 直接 open，未登记 squareGuis 快照
```

```java
// SocialListener.java L302-314（点击侧：无快照时的重建分支）
} else {
    // 排名模式（/le square weekly|monthly|alltime 直达的无快照窗口）：
    // 与 open() 同源重建（有界，display-count 行；本窗口只在无/陈旧
    // 快照的首次点击前存在，任一点击登记后即关闭）
    int displayCount = cm.getMainConfig().getInt("progress-system.leaderboard.display-count", 10);
    var entries = plugin.getProgressManager().getLeaderboard().getTopPlayers(currentMode, displayCount);
    playerIds = new ArrayList<>();
    for (var e : entries) { playerIds.add(e.playerId); }
}
```

全仓 `squareGuis.put` 仅在 SocialListener 内（L332/372/380/385/403/685/740），LearnCommand 与主菜单直达均不登记；且普通头像点击分支（L390-411）**不 put**——只有翻页/切模式/返回闭包才登记，注释"任一点击登记后即关闭"与实际不符：直达后未翻页/未切模式前，**每次**头像点击都实时重建。

- 复现：玩家 A `/le square weekly` → 屏幕显示 T0 时刻 Top10；停留 30 秒（期间其他玩家完成会话使周榜变化）→ A 点击第 3 个位置的头像 → 点击侧在 T1 现查 `getTopPlayers(WEEKLY,10)` 重建 → 第 3 位已是另一名玩家 → **点开的是别人主页（显示与作用错位）**。对比：经任何 tab/翻页点击后（有快照）则零重建、绝无漂移——同 UI 两种行为。
- 影响：点错玩家、打开非预期主页；与 R-09b/F3-01 的"快照严格一致"口径冲突（该口径为鼓励设计，此处头像点击同样适用）。无远程/持久后果，但属 R11 转来的真实 UX 裂缝。
- 修法：在 SocialListener 增一个 `openSquareWithSnapshot(Player, int page, RankingMode mode)`（put 与 open 同刻），LearnCommand L419 与任何其他直达点改走它；或在重建分支改为读 `EnglishSquareGUI` 渲染时冻结的列表（与 renderedFeedPages 同模式）。

### R4-M2 [logic·中·高] 他人主页的"过往学习"按钮打开的是查看者自己的日历
- 位置：`listener/SocialListener.java:417-427`（调用点）、`gui/LearningCalendarGUI.java:87-94`（数据源）
- 证据：

```java
// SocialListener.java L417-427（handleProfileGUI，slot 0 = 过往学习按钮）
if (slot == PROFILE_CALENDAR_SLOT) {
    // 过往学习按钮：需要知道主页归属才能正确「返回」，
    // 标题解析不出目标时不猜（否则会把返回栈指向查看者自己的主页）
    UUID targetId = extractTargetIdFromTitle(player, title);
    if (targetId == null) return;
    SoundManager.playSound(player, "menu-click");
    UUID finalTargetId = targetId;
    nav.pushBack(player.getUniqueId(),
            () -> new ProfileGUI(plugin).open(player, finalTargetId));
    new LearningCalendarGUI(plugin).openAsync(player);      // ← 传的是 viewer，不是 targetId
    return;
}
```

```java
// LearningCalendarGUI.java L87-94
public void openAsync(Player player) {
    UUID playerId = player.getUniqueId();                   // ← 永远取查看者本人
    ...
    dayData = plugin.getPlayerDataStore().getSessionDays(playerId, 35);
```

- 复现：玩家 A 在广场点击 B 的头像 → 打开 B 的主页（标题"B的主页"）→ A 点击 B 主页左上角的"过往学习"按钮 → 界面展示的是 **A 自己的**学习日历（标题/渲染无 B 的任何标识），返回键却回到 B 的主页（L424-425 pushBack 指向 finalTargetId）。
- 影响：语境错位/误导（看着别人的主页却打开自己的日历）；虽是"显示自己的"无隐私泄漏，但 UI 语义与显式解析 targetId 的注释意图相反（注释只把 targetId 用于返回），疑为半成品。修正方向二选一：日历页传 targetId 扩展显示目标日历（需先过 showResults 隐私口径），或他人主页不渲染该按钮/点击回"仅自己可见"提示。
- 修法：`LearningCalendarGUI.openAsync` 增 `UUID targetId` 重载（含隐私门下目标可见性判定与标题归属名），SocialListener L426 传 `finalTargetId`。

## 低危

### R4-L1 [quality·低·高] 榜单头像点击的隐身复核用写版 getProfileSettings（建缓存条目），与全仓 L-05 只读口径不一致
- 位置：`listener/SocialListener.java:583-591`
- 证据：

```java
// R-05：与广场点击分支同语义 —— 看别人主页先查 showInSquare，
// 隐身者进 openHidden 笑话页；看自己主页不受影响。
if (targetId.equals(player.getUniqueId())) {
    new ProfileGUI(plugin).open(player, targetId);
} else if (plugin.getPlayerDataStore().getProfileSettings(targetId).isShowInSquare()) {   // ← 写版：会建缓存条目
    new ProfileGUI(plugin).open(player, targetId);
} else {
    new EnglishSquareGUI(plugin).openHidden(player, targetId);
}
```
（对照：EnglishSquareGUI L135 / SocialManager L322 / SocialListener L296/L690 等展示路径均已改 `getProfileSettingsReadOnly`。）
- 复现：点击榜单第 N 位头像时，若目标尚未加载过 profile，会经写版在 `profileSettingsCache` 建条目（`PlayerDataStore.java:930` 注释即警示需避免"只读展示污染 getActivePlayerIds"）。
- 影响：无实害（目标为榜单在册玩家，候选集本来包含他），但违反同仓已收口的口径。
- 修法：改为 `getProfileSettingsReadOnly`。

### R4-L2 [perf·低·中] %lle_rank% 并列名次不稳定 + 每次解析全量重排序
- 位置：`core/progress/Leaderboard.java:148-158`
- 证据：

```java
public int getPlayerRank(UUID playerId) {
    List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(scores.entrySet());   // ConcurrentHashMap 迭代序
    sorted.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());          // 并列值时先后取决于哈希序
    for (int i = 0; i < sorted.size(); i++) {
        if (sorted.get(i).getKey().equals(playerId)) {
            return i + 1;
        }
    }
    return NOT_RANKED;
}
```
- 复现：两名玩家同分时，%lle_rank% 的先后由 ConcurrentHashMap 内部桶序决定（同一 JVM 周期内稳定，重登/重建条目后可能互换）；且每次解析 O(n log n) 现算。
- 影响：并列名次展示可能漂移（低概率、可感知性低）；E-10 已封顶 1000 行，性能量级在案可接受。
- 修法：并列时按 secondary key（如 UUID 或首次上榜时间）排序稳定化；可留低优先级。

### R4-L3 [i18n·低·高] SPELLING_MASTER 判定收紧（≥1 拼对）后描述未同步微调
- 位置：`core/achievement/Achievement.java:100-107` vs `core/achievement/AchievementManager.java:380-385`
- 证据：

```java
// Achievement.java L100-107
SPELLING_MASTER(
        "spelling_master",
        "拼写达人", "Spelling Bee",
        "完成一次拼写模式的完整答题会话，手打单词不含糊！",
        "Complete a full spelling-mode session — typing words like a pro!", ...);
// AchievementManager.java L383-385（FEAT-MODE-01 收紧）
if ("spelling".equalsIgnoreCase(mode) && sessionCorrect >= 1) {
    tryUnlock(playerId, Achievement.SPELLING_MASTER);
}
```
- 复现：拼写会话全错/全超时走完 → 不判解锁，与描述"完成…完整会话"字面不完全一致（"手打单词不含糊"含糊覆盖）。
- 影响：轻微文案-判据间隙；防挂机收紧本身正确（FEAT-MODE-01 在案）。
- 修法：描述补"（至少拼对 1 题）"级并入既有限定词；可核销。

### R4-L4 [quality·低·中] 广场 ALL_PLAYERS 页依赖 getActivePlayerIds 无序输出，列表顺序随缓存键集变化漂移
- 位置：`data/PlayerDataStore.java:1023-1034`（数据源）、`gui/EnglishSquareGUI.java:128-140`（渲染，无显式排序）
- 证据：

```java
// PlayerDataStore.java L1023-1033
public List<UUID> getActivePlayerIds() {
    java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>(profileSettingsCache.keySet());  // ConcurrentHashMap 键集 = 哈希序
    try {
        for (org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
            ids.add(online.getUniqueId());
        }
    } ...
// EnglishSquareGUI.java L132-140：遍历过滤后直接成页，无 sort
```
- 复现：广场全量页显示顺序 = CHM 键序 ∪ 在线序；他人 quit（unloadProfileSettings 摘键）或新玩家进服可能改变后续页玩家的顺序。
- 影响：显示序波动（无排序语义本就无承诺）；叠加 M1 的无快照重建，构成轻微漂移面。真正稳定的排名语义走排行榜模式（SQL ORDER BY）。
- 修法：如希望稳定，按名字或首答时间显式排序；可核销。

## 待验证区

1. **SocialManager 300s 周期刷新任务的 reload/停用配对**：`startupWarmup`（SocialManager.java:198）内含周期排程，本 Agent 未读完其调度段（仅确认 LetsLearnEnglish L228 启动挂入一次）。验证方法：搜 `runTaskTimer` 的 BukkitTask 引用是否有 cancel 面、reloadAll 是否会重复调用 startupWarmup。
2. **H1 影响面扩展——recordAchievement 的合并语义**：reload 后重复解锁会再调 `SocialManager.recordAchievement`（AchievementManager.java:226-228）；同日是否仅 `events_count` 合并虚增（`UNIQUE(player_uuid, feed_date, event_type)`，schema.sql:185）需读 SocialManager 产生段确认；跨日 reload 是否产生虚假"再次解锁"动态。
3. **DUEL_FIRST_WIN 的宽限窗口语义**：掉线宽限期内（DuelManager.java:817-859）对手继续答题至 WIN_SCORE → advanceOrEnd → endDuel → tryUnlock 成立；描述"在一场打满的单词对决中拿下首胜"是否应排除对手已掉线的对局（当前注释声明"quit/掉线宽限判负不走本方法"，但"对手掉线时自己打满"仍走）。验证方法：双客户端复现。
4. **FEED 渲染↔冻结双快照窗口**：EnglishSquareGUI.open() L126 取 `getFeedSnapshot()` 渲染，InventoryOpenEvent（SocialListener L134-136）再次取快照冻结；两次取值间若恰逢 5s 去抖刷新，渲染列表与点击列表不同源（微秒窗口）。验证方法：注入延迟的定向测试。
5. **%lle_achievements% 的模块门**：LLEPapiExpansion L271-274 直读解锁数/total，未见 isAchievementsEnabled 判定（achievements 门关时占位符仍返回真实计数）。属"已解锁记录保留"语义的延伸，需与 R10 域对门口径。
6. **RewardEngine.java:500-555 未读完段**（giveStoppedSessionMoneyReward 尾部及其余方法）：比例门折算与日限门交互的最终收尾未静态核完，申报未覆盖。

## 豁免挑战
无。（核对过 §E：#1 成就文案硬编码=A1 设计决策、#10 榜单口径=学习分、#12 "30 天"=累计非连续——STUDY_DAYS_30 描述已写"无需连续"，豁免与实现一致；未发现需挑战项。）

## 范围外发现
- `gui/ProfileGUI.java:88-90`：`messages.getString("main-menu.calendar", "")` 裸读 + 默认空串——缺键时按钮渲染空标题（i18n 硬编码面）。归 R13/R8。
- `gui/LearningCalendarGUI.java:87` 结构性无 targetId 参数（M2 已从 R4 调用点立案；GUI 内部实现面归 R6 复审）。
- 正面确认（并入 D12/D24/D2/D27 回归台账）：`new LeaderboardGUI` 全仓仅 3 处且全部登记快照（F3-01 收口有效）；隐身过滤六层 SQL+两层代码全链完整（getRankedPlayers L1128 / ForPeriod L1188/L1268 / getTopCoins L819 / getTopWords L851 / getTopCombo L883 / getFeedCacheRows L1694 + Leaderboard L112 + SocialListener L296）；updateScore 唯一调用点 WordManagerImpl L165（B5 债已修复）；SocialManager 三接线（startupWarmup/notifyPendingCheers/unloadPlayer）无缺口；22 个成就判据 vs 文案逐一对齐全部通过（含 PF-ONB-01 修复后的 FIRST_WORD、FEAT-MODE-01 后的 SPELLING_MASTER、5 条前置文案与 meetsPrerequisites 语义一致）；recordSession review_words 真源确认（PlayerDataStore L235）；getWrongBookCleared 真源确认（仅练习路径 increment）；DUEL_FIRST_WIN 唯一挂点 DuelManager L731。

## 审查覆盖声明
- 全文读完：Achievement.java、AchievementWallGUI.java、EnglishSquareGUI.java、LeaderboardGUI.java、Leaderboard.java、ProgressManagerImpl.java、FeedTextFormatter.java、achievements-config.yml、SocialManager.java L270-467、AchievementManager.java L60-145/L175-330/L330-566/L600-677、RewardEngine.java L1-500、SocialListener.java L60-208/L208-376/L376-488/L485-635/L686-816、ProfileGUI.java L1-280、schema.sql 关键表段。
- 部分/未读完：SocialManager L1-270（缓存/调度段仅跳读）、RewardEngine L500-555、ProfileGUI L280-354、PlayerProgress.java（68 行未读——由 getPlayerProgress 消费面间接核对）、SocialListener L635-686 部分（handleFeedSlot 右键段已读，缺 L656-686 数行）。
- 未做/没做完：LeaderboardGUI 无分页设计确认（单页 10 格，与 entry 数一致，无分页 bug 面）；%lle_rank% 缓存窗口的实机陈旧性（R-43 30s TTL 已读，行为推定正确）；SocialManager FeedCache 预热时序实机验证（待验证 1）。
- 前任 10 条 TODO 核销状态：① → R4-H1 完成；② → 范围外"正面确认"完成（D12 通过）；③ → 完成（唯一调用点）；④ → 完成（三接线齐全）；⑤ → 完成（唯一挂点+语义核对，边缘留待验证 3）；⑥ → L4 + 待碰；⑦ → 完成（六层全过滤，无泄漏）；⑧ → R4-M2 立案；⑨ → 完成（真源确认）；⑩ → 完成（真源确认）。
- 8 项重点方向覆盖：1✓（H1+D12）、2✓（全 22 成就核完毕）、3✓（快照 put/remove 配对清点：3 map 全配对，quit L113-119 统一清理）、4✓（隐身无泄漏）、5~✓（分页/并列/空榜已看，%lle_rank% 窗口推定性确认）、6✓（cheer 幂等/自赞/离线全链核对）、7✓（P3-01 口径正确、D2 真源正确）、8✓（连击乘数/复习/门/cap 全部核对、发币主线程）、9✓（D24/J-4 回归通过）。
