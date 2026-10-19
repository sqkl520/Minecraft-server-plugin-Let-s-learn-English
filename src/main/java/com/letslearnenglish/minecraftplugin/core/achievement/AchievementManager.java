package com.letslearnenglish.minecraftplugin.core.achievement;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Achievement Manager
 *
 * Tracks achievement progress for all players and handles unlocking logic.
 * Achievement data is persisted in the database via the achievements table.
 * In-memory cache is maintained per-player for fast lookups.
 *
 * Triggered by ProgressManagerImpl after each session completes, and by
 * WordLearningManager after each answer (for streak/perfect checks).
 */
public class AchievementManager {

    private final LetsLearnEnglish plugin;
    private final PlayerDataStore playerDataStore;
    private final Map<UUID, Set<String>> unlockedCache;
    /**
     * PF-ACH-09：并行"解锁时间"缓存（achievement_id → unlocked_at 毫秒）。
     * 与 {@link #unlockedCache} 同生命周期维护（load / tryUnlock / unloadPlayer /
     * clearCache 四处），供成就墙渲染"解锁于 {date}"；不改 unlockedCache 的
     * 类型与语义（hasAchievement 等三个消费点零改动）。
     */
    private final Map<UUID, Map<String, Long>> unlockedAtCache;
    // F1-03：懒加载的成就配置在异步线程可达（AI 对话 bye 分支直调链），改 volatile ——
    // 竞态最坏后果（多读一次文件）即消除。
    private volatile FileConfiguration achievementConfig;

    public AchievementManager(LetsLearnEnglish plugin, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
        this.unlockedCache = new ConcurrentHashMap<>();
        this.unlockedAtCache = new ConcurrentHashMap<>();
    }

    private FileConfiguration getAchievementConfig() {
        if (achievementConfig == null) {
            File file = new File(plugin.getDataFolder(), "achievements-config.yml");
            if (!file.exists()) {
                plugin.saveResource("achievements-config.yml", false);
            }
            achievementConfig = YamlConfiguration.loadConfiguration(file);
        }
        return achievementConfig;
    }

    /**
     * Load a player's unlocked achievements into the cache.
     */
    public void loadPlayerAchievements(UUID playerId) {
        Set<String> unlocked = new HashSet<>();
        // PF-ACH-09：追加 unlocked_at 列——写入并行缓存 unlockedAtCache（不改
        // unlockedCache 的类型与语义，三个消费点零改动）。
        Map<String, Long> unlockedAt = new ConcurrentHashMap<>();
        String sql = "SELECT achievement_id, unlocked_at FROM "
                + plugin.getDatabaseManager().getTablePrefix() + "achievements WHERE uuid = ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("achievement_id");
                    unlocked.add(id);
                    unlockedAt.put(id, rs.getLong("unlocked_at"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to load achievements for " + playerId, e);
        }
        }
        unlockedCache.put(playerId, unlocked);
        unlockedAtCache.put(playerId, unlockedAt);
    }

    /**
     * Check if a player has unlocked an achievement.
     */
    public boolean hasAchievement(UUID playerId, Achievement achievement) {
        Set<String> unlocked = unlockedCache.get(playerId);
        if (unlocked == null) {
            loadPlayerAchievements(playerId);
            unlocked = unlockedCache.get(playerId);
        }
        return unlocked != null && unlocked.contains(achievement.getId());
    }

    /**
     * Get all unlocked achievements for a player.
     */
    public Set<Achievement> getUnlockedAchievements(UUID playerId) {
        Set<String> unlockedIds = unlockedCache.get(playerId);
        if (unlockedIds == null) {
            loadPlayerAchievements(playerId);
            unlockedIds = unlockedCache.get(playerId);
        }
        Set<Achievement> result = new LinkedHashSet<>();
        if (unlockedIds != null) {
            for (String id : unlockedIds) {
                Achievement a = Achievement.fromId(id);
                if (a != null) result.add(a);
            }
        }
        return result;
    }

    /**
     * Get the count of unlocked achievements for a player.
     */
    public int getUnlockedCount(UUID playerId) {
        Set<String> unlocked = unlockedCache.get(playerId);
        if (unlocked == null) {
            loadPlayerAchievements(playerId);
            unlocked = unlockedCache.get(playerId);
        }
        return unlocked != null ? unlocked.size() : 0;
    }

    /**
     * Get the unlock timestamp (epoch millis) of an achievement, or {@code null}
     * if the player has not unlocked it (or the record predates this cache).
     * Backed by the parallel {@link #unlockedAtCache} (PF-ACH-09). Signature frozen
     * for the achievement wall (S-D 流).
     */
    public Long getUnlockedAt(UUID playerId, Achievement achievement) {
        Map<String, Long> map = unlockedAtCache.get(playerId);
        if (map == null) {
            loadPlayerAchievements(playerId);
            map = unlockedAtCache.get(playerId);
        }
        return map != null ? map.get(achievement.getId()) : null;
    }

    /**
     * Try to unlock an achievement. If already unlocked, this is a no-op.
     * The notification (chat/Title/sound/broadcast) is dispatched on the main thread.
     *
     * <p>判定顺序（R3 关键不变量——不可调整）：
     * <ol>
     *   <li>MC-2 模块门（progress-system.achievements.enabled，来自 ConfigManager）；</li>
     *   <li>achievements-config.yml 启用/时间窗（{@link #isAchievementEligible}）；</li>
     *   <li>{@code unlocked.contains} —— 已解锁玩家在此短路返回，前置/时间窗后续
     *       回改永不撤销既有解锁（老号回归用例 T9 的保障）；</li>
     *   <li>前置守卫 {@link #meetsPrerequisites}（§1.3——插在 contains 之后、
     *       写内存之前，唯一写入口全路径收口，任何触发路径无法绕过）；</li>
     *   <li>写内存（unlocked + unlockedAt）+ DB UPSERT（唯一约束兜底多服并发）；</li>
     *   <li>通知段主线程化（PF-ACH-02 根治，见 {@link #notifyUnlock}）。</li>
     * </ol>
     */
    public boolean tryUnlock(UUID playerId, Achievement achievement) {
        // MC-2（plan-menu-config）：成就模块总门——关闭时解锁逻辑整体不触发
        //（连带通知/音效/广播全不触发；已解锁记录保留，重新开启即恢复）。
        if (!plugin.getConfigManager().isAchievementsEnabled()) {
            return false;
        }
        // R4-H1：懒加载（与 hasAchievement 同范式）——原 computeIfAbsent 缺指纹时
        // 直接建空集、绕过 DB 回填：reload 后 clearCache 清缓存，已解锁玩家的
        // contains 短路失效 → 重复解锁并覆盖 unlocked_at。
        Set<String> unlocked = unlockedCache.get(playerId);
        if (unlocked == null) {
            loadPlayerAchievements(playerId);   // 尾部无条件回填（含 SQL 异常路径）
            unlocked = unlockedCache.get(playerId);
            if (unlocked == null) {             // 防御：并发 unload 窗口，维持非 null 不变量
                unlocked = new HashSet<>();
                unlockedCache.put(playerId, unlocked);
            }
        }
        FileConfiguration achConfig = getAchievementConfig();
        if (!isAchievementEligible(achConfig, achievement)) {
            return false;
        }
        if (unlocked.contains(achievement.getId())) {
            return false;
        }
        // §1.3 前置守卫（R3：必须在 contains 之后——已解锁玩家重复调用直接返回，
        // 前置回改不影响既有解锁）
        if (!meetsPrerequisites(playerId, achievement)) {
            return false;
        }

        unlocked.add(achievement.getId());
        long unlockedAt = System.currentTimeMillis();
        unlockedAtCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(achievement.getId(), unlockedAt);

        String sql = "INSERT OR REPLACE INTO "
                + plugin.getDatabaseManager().getTablePrefix()
                + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?)";
        if (!plugin.getDatabaseManager().isSQLite()) {
            sql = "INSERT INTO " + plugin.getDatabaseManager().getTablePrefix()
                    + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE unlocked_at=VALUES(unlocked_at)";
        }

        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, achievement.getId());
            stmt.setLong(3, unlockedAt);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to save achievement " + achievement.getId() + " for " + playerId, e);
        }
        }

        // Wave 2 域 4（广场社交，plan-social §3.6）：新解锁 → 产生/合并一条
        // ACHIEVEMENT 动态。已解锁重复调用与不合格解锁均在上方短路，到不了这里；
        // SocialManager 内部自判 social.enabled 与产生侧隐身门并异步写库
        // （本方法可从异步线程到达——F1-03 路径——recordEvent 入口统一派发异步）。
        if (plugin.getSocialManager() != null) {
            plugin.getSocialManager().recordAchievement(playerId, achievement.getId());
        }

        // PF-ACH-02：通知段（聊天/Title/音效/广播）统一调度回主线程——本方法可从
        // 异步线程到达（AI 对话 bye 分支直调链），其内全是主线程 API。
        // D2-5：同型帮助器见 util.SchedulerGuard（本处为内联 try/catch + 主线程分支，
        // 语义微差），本批未迁移。
        if (Bukkit.isPrimaryThread()) {
            notifyUnlock(playerId, achievement);
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, () -> notifyUnlock(playerId, achievement));
            } catch (IllegalStateException e) {
                // 插件停用窗口（调度器已关闭）：放弃本次通知，解锁记录已落库
            }
        }
        return true;
    }

    /**
     * PF-ACH-02：解锁通知段（原 tryUnlock L161-188 的聊天双行/音效/广播 +
     * §1.5-1 的 Title 展示）。由 {@link #tryUnlock} 调度到主线程执行。
     * 玩家恰在通知前下线时静默跳过（R4：isOnline 复检，原模式保留）。
     */
    private void notifyUnlock(UUID playerId, Achievement achievement) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        // P2 fan-in（S7 上报）：裸 getString 收敛——缺键显示 [?key] 占位而非 NPE
        String title = com.letslearnenglish.minecraftplugin.util.MessageUtil
                .getGuiMsg(messages, "achievement.unlock-title");
        String fmt = com.letslearnenglish.minecraftplugin.util.MessageUtil
                .getGuiMsg(messages, "achievement.unlock-format");
        String name = achievement.getDisplayName(lang);
        String desc = achievement.getDisplayDesc(lang);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                fmt.replace("{title}", title).replace("{name}", name)));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&7" + desc));

        // §1.5-1：Title 展示（标题行 + 副标题成就名；fadeIn 5t / stay 30t / fadeOut 10t）。
        // 走 Adventure（String 版 sendTitle 在 paper-api 1.21.4 已 @Deprecated，
        // 与本项目 VersionAdapter.sendActionBar 的规避口径一致）。
        player.showTitle(Title.title(
                LegacyComponentSerializer.legacySection().deserialize(
                        ChatColor.translateAlternateColorCodes('&', title)),
                Component.text(name),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1500),
                        Duration.ofMillis(500))));

        // PF-ACH-01 修复：achievement-system.unlock-sound 配置值真接线
        //（空串 / "none" = 故意静音，静默跳过——见 SoundManager.playSoundByName）。
        String soundName = plugin.getConfigManager().getMainConfig()
                .getString("achievement-system.unlock-sound", "ENTITY_PLAYER_LEVELUP");
        if (soundName != null) {
            SoundManager.playSoundByName(player, soundName);
        }

        // 跨服成就广播（Velocity / BungeeCord）——PF-ACH-03：双语 payload，
        // 接收端逐收件人按各自语言渲染（不再混拼）。旧格式 3 段兼容见 ProxyMessenger。
        if (plugin.getProxyMessenger() != null && plugin.getProxyMessenger().isRegistered()) {
            plugin.getProxyMessenger().sendAchievementBroadcast(
                    player.getName(), playerId,
                    achievement.getDisplayName("zh"), achievement.getDisplayName("en"));
        }
    }

    /**
     * §1.3 刷量成就的前置守卫（收口在唯一写入口 tryUnlock 内，任何触发路径无法绕过）。
     * 判定只读内存（PlayerStats / unlockedCache），零 DB 成本；整数比较防浮点边界。
     *
     * <p>公开：成就墙用它在未解锁项上渲染"前置未满足"提示（US-ACH-4 / S-D 流）。
     *
     * @return true 表示前置已满足（未列出的成就恒 true）
     */
    public boolean meetsPrerequisites(UUID playerId, Achievement achievement) {
        switch (achievement) {
            case SESSIONS_50 -> {
                // 反制"每局答 1 题速退 ×50"：10 连击只能在一次会话里真实产生
                return hasAchievement(playerId, Achievement.STREAK_10);
            }
            case WORDS_500 -> {
                // 答题总数 ≥100 且历史正确率 ≥70%（与 accuracy_90 形成 70/90 两档）
                PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);
                long total = (long) stats.getTotalCorrect() + stats.getTotalIncorrect();
                return total >= 100 && (long) stats.getTotalCorrect() * 100 >= total * 70;
            }
            case COINS_500 -> {
                // 防纯签到囤币（签到 7~10 天零学习即到 500）：50 题是很轻的门
                PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);
                return (long) stats.getTotalCorrect() + stats.getTotalIncorrect() >= 50;
            }
            case COINS_5000 -> {
                // 堵"纯挂机经济流"：真答对过 100 词才够格
                return hasAchievement(playerId, Achievement.WORDS_100);
            }
            default -> {
                return true;
            }
        }
    }

    private boolean isAchievementEligible(FileConfiguration config, Achievement achievement) {
        if (config == null) return true;
        if (!config.getBoolean("achievements." + achievement.getId() + ".enabled", true)) {
            return false;
        }
        if (!config.getBoolean("achievements." + achievement.getId() + ".time-limit.enabled", false)) {
            return true;
        }
        String startStr = config.getString("achievements." + achievement.getId() + ".time-limit.start");
        String endStr = config.getString("achievements." + achievement.getId() + ".time-limit.end");
        if (startStr == null || endStr == null) return true;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            // P3-12：统一系统时区（原硬编码 Asia/Shanghai，与 checkin/streak 等
            // 其余全项目模块的"服务器本地时区"口径不一致——修正不一致而非产品变更）。
            sdf.setTimeZone(java.util.TimeZone.getDefault());
            Date start = sdf.parse(startStr);
            Date end = sdf.parse(endStr);
            Date now = new Date();
            return !now.before(start) && !now.after(end);
        } catch (java.text.ParseException e) {
            return true;
        }
    }

    /**
     * Check and unlock all applicable achievements after a session completes.
     * Called by ProgressManagerImpl.
     */
    public void checkSessionAchievements(UUID playerId, int sessionCorrect, int sessionIncorrect,
                                          int sessionWordsCount, String mode) {
        PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);

        // PF-ONB-01（Phase 3 规划期发现，编排者即修）：原条件 sessionCorrect>=1 &&
        // sessionIncorrect==0 要求"整场零失误"，与成就描述"答对第 1 个单词"不符——
        // 新玩家第一场只要答错一题就与"初出茅庐"擦肩，伤首会话留存；该语义又与
        // PERFECT_SESSION（整场全对）重复。改为"会话内至少答对 1 题"即解锁。
        if (sessionCorrect >= 1) {
            tryUnlock(playerId, Achievement.FIRST_WORD);
        }

        int totalCorrect = stats.getTotalCorrect();
        if (totalCorrect >= 25) tryUnlock(playerId, Achievement.WORDS_25);
        if (totalCorrect >= 100) tryUnlock(playerId, Achievement.WORDS_100);
        if (totalCorrect >= 500) tryUnlock(playerId, Achievement.WORDS_500);

        if (sessionCorrect == sessionWordsCount && sessionIncorrect == 0 && sessionWordsCount > 0) {
            tryUnlock(playerId, Achievement.PERFECT_SESSION);
        }

        // FEAT-MODE-01：SPELLING_MASTER 只看 mode 会让挂机超时/全错也拿成就；
        // 收紧为「spelling 模式 + 本会话至少拼对 1 题」。阈值语义不变（仍是完成即判），
        // 只是不再奖励零拼对的会话。
        if ("spelling".equalsIgnoreCase(mode) && sessionCorrect >= 1) {
            tryUnlock(playerId, Achievement.SPELLING_MASTER);
        }

        int totalSessions = stats.getTotalSessions();
        if (totalSessions >= 10) tryUnlock(playerId, Achievement.SESSIONS_10);
        if (totalSessions >= 50) tryUnlock(playerId, Achievement.SESSIONS_50);

        // Phase 3 N3（accuracy_90）：累计答题 ≥100 且正确率 ≥90%。用整数比较
        // （correct*100 >= total*90）防 0.9 的双精度边界（90/100 必须解锁）；
        // 分母用总答题数（答错也计入），无法用"只打简单题"缩小样本。
        int totalAnswered = totalCorrect + stats.getTotalIncorrect();
        if (totalAnswered >= 100 && (long) totalCorrect * 100 >= (long) totalAnswered * 90) {
            tryUnlock(playerId, Achievement.ACCURACY_90);
        }

        checkDailyStreak(playerId);
        // Phase 3 N1/N6：review 连续与累计学习日判定。排序保证：showSessionComplete
        // 先 recordSession（R-30）后判定——review_words 与 COUNT(DISTINCT) 自动吃到今天。
        checkReviewStreak(playerId);
        checkStudyDays(playerId);
        long totalCoins = plugin.getDatabaseManager().getCoinsEarned(playerId);
        if (totalCoins >= 500) tryUnlock(playerId, Achievement.COINS_500);
        if (totalCoins >= 5000) tryUnlock(playerId, Achievement.COINS_5000);
    }

    /**
     * Check achievement for in-session streak.
     * Called by WordLearningManager after each correct answer within a session.
     *
     * <p>M9：同时把会话连击峰值落盘到 player_data.longest_streak（只增不减），
     * 供连击榜（getTopCombo）与个人进度读侧使用。
     */
    public void checkStreakAchievement(UUID playerId, int currentSessionStreak) {
        if (currentSessionStreak >= 3) tryUnlock(playerId, Achievement.STREAK_3);
        if (currentSessionStreak >= 10) tryUnlock(playerId, Achievement.STREAK_10);
        playerDataStore.persistLongestStreak(playerId, currentSessionStreak);
    }

    /**
     * Phase 3 N2（wrong_book_cleared）：错题练习累计移出 ≥5 个词、且最后一次移出时
     * 错题本为空 → 解锁。由 {@code WordLearningManager.submitPracticeAnswer} 的
     * 移出分支调用（手动删除不计数——计数只在练习路径累加，见 S-A/H7）。
     * 签名冻结（S-F 流按此调用）。
     */
    public void checkWrongBookCleared(UUID playerId, int totalCleared, boolean empty) {
        if (totalCleared >= 5 && empty) {
            tryUnlock(playerId, Achievement.WRONG_BOOK_CLEARED);
        }
    }

    /**
     * Phase 3 N5（helpful）：鼓励过 ≥10 个不同玩家 → 解锁。
     *
     * <p>数据依赖域 4（广场社交）的 {@code SocialManager.getCheerStats(uuid).distinctTargets()}；
     * 触发点由 {@code SocialListener} 在鼓励成功后按 listener 层编排调用
     * （plan-social §4.3 冻结接口：SocialManager 不反向依赖成就域）。
     *
     * <p><b>线程契约</b>：{@code getCheerStats} 是同步 DB 读（其 javadoc 明示禁止
     * 主线程调用），而本方法的主调用点（cheer 成功回调）在主线程——因此本方法
     * 统一派发到异步线程执行判定；命中后 {@link #tryUnlock} 自身会把通知段调回
     * 主线程（PF-ACH-02 已支持异步入口）。插件停用窗口静默放弃（下次鼓励重判）。
     */
    public void checkHelpAchievement(UUID playerId) {
        if (plugin.getSocialManager() == null) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                SocialManager sm = plugin.getSocialManager();
                if (sm == null) {
                    return;
                }
                if (sm.getCheerStats(playerId).distinctTargets() >= 10) {
                    tryUnlock(playerId, Achievement.HELPFUL);
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次判定（解锁记录不损，下次鼓励重判）
        }
    }

    private void checkDailyStreak(UUID playerId) {
        int consecutive = countConsecutiveDays(fetchSessionDays(playerId));
        if (consecutive >= 3) tryUnlock(playerId, Achievement.DAILY_STREAK_3);
        if (consecutive >= 7) tryUnlock(playerId, Achievement.DAILY_STREAK_7);
    }

    /**
     * Phase 3 N1（review_streak_7）：连续 7 个自然日每天至少完成一次复习会话且
     * 当天复习答对 ≥1 词（review_words > 0，防挂机蹭天数）。判定窗口与
     * {@link #checkDailyStreak} 同款：回看 30 天、今天缺不打断、昨天缺才打断。
     */
    private void checkReviewStreak(UUID playerId) {
        int consecutive = countConsecutiveDays(fetchReviewDays(playerId));
        if (consecutive >= 7) tryUnlock(playerId, Achievement.REVIEW_STREAK_7);
    }

    /**
     * Phase 3 N6（study_days_30）：累计在 30 个不同自然日完成过会话（不要求连续）。
     * COUNT(DISTINCT session_date) 由 UNIQUE(uuid, session_date) 前缀 uuid 覆盖，
     * 是有界查询（量级 = 该玩家会话行数）。
     */
    private void checkStudyDays(UUID playerId) {
        if (countDistinctStudyDays(playerId) >= 30) {
            tryUnlock(playerId, Achievement.STUDY_DAYS_30);
        }
    }

    /**
     * 取玩家最近 40 天的会话日列表（R-79：LIMIT 40 = 判定窗口 30 天 + 10 天裕量，
     * 值为最新行倒序；UNIQUE(uuid, session_date) 保证一天至多一行）。
     * SQL 异常返回空集（连续计数为 0，与旧 checkDailyStreak 的静默 return 等价）。
     */
    private Set<String> fetchSessionDays(UUID playerId) {
        return fetchDaySet(playerId,
                "SELECT session_date FROM " + plugin.getDatabaseManager().getTablePrefix()
                        + "player_sessions WHERE uuid = ? ORDER BY session_date DESC LIMIT 40");
    }

    /**
     * Phase 3 N1：复习日列表——仅统计 {@code review_words > 0} 的日期
     * （review_words 列由 S-A 流落盘：recordSession 当 mode=review 时累加 correctCount）。
     */
    private Set<String> fetchReviewDays(UUID playerId) {
        return fetchDaySet(playerId,
                "SELECT session_date FROM " + plugin.getDatabaseManager().getTablePrefix()
                        + "player_sessions WHERE uuid = ? AND review_words > 0 "
                        + "ORDER BY session_date DESC LIMIT 40");
    }

    private Set<String> fetchDaySet(UUID playerId, String daySql) {
        Set<String> days = new LinkedHashSet<>();
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(daySql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    days.add(rs.getString("session_date"));
                }
            }
        } catch (SQLException e) {
            return days;
        }
        }
        return days;
    }

    /**
     * 从"会话日集合"回看计数连续天数（checkDailyStreak / checkReviewStreak 共用）。
     * 语义与旧 checkDailyStreak 内联循环一致：今天缺不打断（i==0 允许缺），
     * 昨天及更早缺即断；窗口 30 天。
     */
    private int countConsecutiveDays(Set<String> days) {
        int consecutive = 0;
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = 0; i < 30; i++) {
            if (days.contains(today.minusDays(i).toString())) {
                consecutive++;
            } else if (i > 0) {
                break;
            }
        }
        return consecutive;
    }

    /** Phase 3 N6：不同学习日总数（SQL 异常返回 0）。 */
    private int countDistinctStudyDays(UUID playerId) {
        String sql = "SELECT COUNT(DISTINCT session_date) FROM "
                + plugin.getDatabaseManager().getTablePrefix()
                + "player_sessions WHERE uuid = ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
        }
    }

    /**
     * Phase 3 §2.2：成就墙进度快照——遍历全部成就，对 progressSource != NONE 的
     * 组装 {@code int[2]{current, target}}（一次组装、墙渲染直接消费，S-D 流按此
     * 签名调用）。全部数值为只读派生视图，现算不落盘；每项独立 try/catch 兜底回 0。
     *
     * <p>成本（R2 在案）：≤4 条有界查询（coins 1 + 会话日列表 1 + 复习日列表 1 +
     * study-days COUNT 1，同类来源本快照内只算一次），仅墙打开时执行，与
     * PF-SOC-01 同族模式接受。ACCURACY_PCT 的 current = 百分比整数（0~100），
     * 样本量不单独返回（简化：int[2] = {current, target}）。
     *
     * @param targetId 被查看玩家（可离线）
     */
    public Map<Achievement, int[]> getProgressSnapshot(UUID targetId) {
        Map<Achievement, int[]> snapshot = new EnumMap<>(Achievement.class);

        // 懒计算缓存：每类数据源在本次快照内只查/算一次
        PlayerDataStore.PlayerStats stats = null;
        long coinsEarned = -1;
        int dailyStreak = -1;
        int reviewStreak = -1;
        int studyDays = -1;

        for (Achievement a : Achievement.values()) {
            Achievement.ProgressSource source = a.getProgressSource();
            if (source == Achievement.ProgressSource.NONE) {
                continue;
            }
            int current = 0;
            try {
                switch (source) {
                    case TOTAL_CORRECT -> {
                        if (stats == null) stats = playerDataStore.getPlayerStats(targetId);
                        current = stats.getTotalCorrect();
                    }
                    case TOTAL_SESSIONS -> {
                        if (stats == null) stats = playerDataStore.getPlayerStats(targetId);
                        current = stats.getTotalSessions();
                    }
                    case COINS_EARNED -> {
                        if (coinsEarned < 0) {
                            coinsEarned = plugin.getDatabaseManager().getCoinsEarned(targetId);
                        }
                        current = (int) Math.min(Integer.MAX_VALUE, coinsEarned);
                    }
                    case CONVERSATIONS -> current = playerDataStore.getConversationCount(targetId);
                    case DAILY_STREAK -> {
                        if (dailyStreak < 0) {
                            dailyStreak = countConsecutiveDays(fetchSessionDays(targetId));
                        }
                        current = dailyStreak;
                    }
                    case REVIEW_STREAK -> {
                        if (reviewStreak < 0) {
                            reviewStreak = countConsecutiveDays(fetchReviewDays(targetId));
                        }
                        current = reviewStreak;
                    }
                    case ACCURACY_PCT -> {
                        if (stats == null) stats = playerDataStore.getPlayerStats(targetId);
                        int total = stats.getTotalCorrect() + stats.getTotalIncorrect();
                        // 与 N3 判定同口径：整数百分比；无样本时 0
                        current = total > 0 ? (int) ((long) stats.getTotalCorrect() * 100 / total) : 0;
                    }
                    case STUDY_DAYS -> {
                        if (studyDays < 0) studyDays = countDistinctStudyDays(targetId);
                        current = studyDays;
                    }
                    case WRONG_BOOK_CLEARED -> current = playerDataStore.getWrongBookCleared(targetId);
                    default -> {
                        // NONE 已在上方 continue；防御性兜底
                    }
                }
            } catch (Exception e) {
                current = 0;
            }
            snapshot.put(a, new int[]{current, a.getTarget()});
        }
        return snapshot;
    }

    /**
     * Unload achievement data from cache (called on player quit, and by
     * {@code /lea reset} before wiping the achievements rows — R-03：不清此缓存，
     * {@link #tryUnlock} 的 contains 会命中旧内存态，"删掉的成就"在残留会话里复活）。
     */
    public void unloadPlayer(UUID playerId) {
        unlockedCache.remove(playerId);
        unlockedAtCache.remove(playerId);
    }

    /**
     * Check AI chat conversation count achievements.
     * Called when a player ends an AI scenario conversation.
     */
    public void checkAIChatAchievement(UUID playerId, int totalConversations) {
        if (totalConversations >= 10) tryUnlock(playerId, Achievement.AI_CHAT_10);
        if (totalConversations >= 50) tryUnlock(playerId, Achievement.AI_CHAT_50);
    }

    /**
     * Clear all achievement caches (called on reload).
     * H9：同步刷新懒加载的 achievementConfig，下次 tryUnlock 经 getAchievementConfig()
     * 重新从磁盘读取 — 否则 reload 后成就开关/时间窗口改动永不生效。
     */
    public void clearCache() {
        unlockedCache.clear();
        unlockedAtCache.clear();
        achievementConfig = null;
    }
}
