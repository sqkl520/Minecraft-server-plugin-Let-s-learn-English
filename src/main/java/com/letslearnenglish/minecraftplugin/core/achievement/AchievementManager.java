package com.letslearnenglish.minecraftplugin.core.achievement;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.api.event.PlayerAchievementUnlockEvent;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.data.repository.AchievementRepository;
import com.letslearnenglish.minecraftplugin.util.EventDispatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    /** 成就表持久化（原 4 处裸 JDBC 已搬入；本类保留缓存、判定与通知）。 */
    private final AchievementRepository achievementRepository;
    private final Map<UUID, Set<String>> unlockedCache;
    /**
     * PF-ACH-09：并行"解锁时间"缓存（achievement_id → unlocked_at 毫秒）。
     * 与 {@link #unlockedCache} 同生命周期维护（load / tryUnlock / unloadPlayer /
     * clearCache 四处），供成就墙渲染"解锁于 {date}"；不改 unlockedCache 的
     * 类型与语义（hasAchievement 等三个消费点零改动）。
     */
    private final Map<UUID, Map<String, Long>> unlockedAtCache;
    /** DC-02（round4）：待补写——仓储 upsertUnlock 返回 void 无成败可判；quit/reload 幂等重放。 */
    private final Map<UUID, Map<String, Long>> pendingUnlockWrites = new ConcurrentHashMap<>();
    // F1-03：懒加载的成就配置在异步线程可达（AI 对话 bye 分支直调链），改 volatile ——
    // 竞态最坏后果（多读一次文件）即消除。
    private volatile FileConfiguration achievementConfig;

    public AchievementManager(LetsLearnEnglish plugin, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
        this.achievementRepository = new AchievementRepository(
                plugin.getDatabaseManager(), plugin.getLogger());
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
        // round4 F1：内层必须并发（外层 map 只护到"拿到 Set 引用"，并发 add 会丢更新）。
        Set<String> unlocked = ConcurrentHashMap.newKeySet();
        // PF-ACH-09：追加 unlocked_at 列——写入并行缓存 unlockedAtCache（不改
        // unlockedCache 的类型与语义，三个消费点零改动）。
        Map<String, Long> unlockedAt = new ConcurrentHashMap<>();
        // slice-12-logic-L-01（round10 裁决实施 A6-1）：读失败（null）时**不落缓存**。
        //
        // 原实现读失败返回空 Map，这里无条件 put —— 于是「不知道」被写成「没有解锁任何
        // 成就」。随后 `tryUnlock` 的 contains 恒为 false，已解锁的成就会被重新解锁、
        // 重新弹通知，动态计数也随之虚增。
        //
        // 现在：null → 直接 return，缓存保持「未加载」状态。调用方（hasAchievement /
        // tryUnlock 等）看到 null 会重试加载；真值仍在库里，下次会话结算重新触发
        // tryUnlock 即可补上，**不丢解锁**，只是延后。
        Map<String, Long> loaded = achievementRepository.findUnlocked(playerId);
        if (loaded == null) {
            plugin.getLogger().fine("Achievement load skipped for " + playerId
                    + ": read failed, cache left unloaded so the next attempt retries"
                    + " instead of treating 'unknown' as 'none unlocked'.");
            return;
        }
        for (Map.Entry<String, Long> e : loaded.entrySet()) {
            unlocked.add(e.getKey());
            unlockedAt.put(e.getKey(), e.getValue());
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
     * R18：批量版本的 {@link #getUnlockedAt(UUID, Achievement)} —— 成就墙专用。
     *
     * <p><b>为什么需要它</b>：墙渲染对每个<b>已解锁</b>成就各调一次 {@code getUnlockedAt}，
     * 每次都要「读缓存 → miss 就 {@code loadPlayerAchievements}」。缓存未命中时那是一次
     * 全量 SELECT 重复 N 遍；即便命中，逐条调用也让渲染代码把取数与画物品缝在一起，
     * 无法整体异步。
     *
     * <p>改为<b>一次</b>确保缓存就绪、然后只做 map 查询，产出<b>值快照</b>
     * （新 {@link EnumMap}，不泄漏缓存内部引用）。语义逐项等价：
     * 未解锁 / 记录早于缓存 的成就<b>不出现在 map 里</b>（渲染侧 {@code get} 得 null，
     * 与原来 {@code getUnlockedAt} 返回 null 完全一致）。
     *
     * <p>线程：只读缓存 + 最多一次懒加载，与 {@link #getProgressSnapshot} 同一契约
     * （调用方可在异步线程调用）。
     *
     * @param playerId 被查看玩家（可离线）
     * @param achievements 关心的成就集（通常 = 墙内可见成就）；null 视作空集
     * @return 成就 → 解锁时刻（毫秒）；只含确实有解锁记录的项
     */
    public Map<Achievement, Long> getUnlockedAtSnapshot(UUID playerId,
                                                        Collection<Achievement> achievements) {
        Map<Achievement, Long> snapshot = new EnumMap<>(Achievement.class);
        if (achievements == null || achievements.isEmpty()) {
            return snapshot;
        }
        // 一次确保缓存就绪（miss 时只会 SELECT 一次），循环内不再触发懒加载。
        Map<String, Long> map = unlockedAtCache.get(playerId);
        if (map == null) {
            loadPlayerAchievements(playerId);
            map = unlockedAtCache.get(playerId);
        }
        if (map == null) {
            return snapshot;
        }
        for (Achievement a : achievements) {
            Long at = map.get(a.getId());
            if (at != null) {
                snapshot.put(a, at);
            }
        }
        return snapshot;
    }

    /**
     * R18：批量版本的 {@link #meetsPrerequisites(UUID, Achievement)} 的<b>取反</b> ——
     * 返回「前置未满足」的成就集（成就墙 lore 只关心未满足那部分）。
     *
     * <p><b>为什么需要它</b>：墙渲染对每个未解锁成就各调一次 {@code meetsPrerequisites}，
     * 其中 {@code WORDS_500} / {@code COINS_500} 两支会走 {@code getPlayerStats}
     * （缓存 miss 时触发 {@code loadPlayerData}）。逐条调用把「一次取数」变成「按成就数
     * 重复取数」，而且和渲染缝在一起没法整体异步。
     *
     * <p>判定语义<b>逐支复用</b> {@link #meetsPrerequisites}（不复制那份 switch）：
     * 任何前置规则变更（含未来新增分支）都只需改一处，本方法自动跟上 —— 复制一份
     * 判定逻辑才是真正的漂移风险。
     *
     * <p><b>只判定传入的成就</b>：调用方传墙内可见成就集，于是管理员禁用的成就不参与
     * 判定（也不白查 {@code getPlayerStats}）。
     *
     * <p>线程：与 {@link #getProgressSnapshot} 同一契约（只读缓存 + 懒加载，可在异步线程调用）。
     *
     * @param playerId     被查看玩家（可离线）
     * @param achievements 关心的成就集；null 视作空集
     * @return 前置<b>未</b>满足的成就集（未列出的成就前置恒满足）
     */
    public Set<Achievement> getPrereqUnmetSnapshot(UUID playerId,
                                                   Collection<Achievement> achievements) {
        Set<Achievement> unmet = EnumSet.noneOf(Achievement.class);
        if (achievements == null) {
            return unmet;
        }
        for (Achievement a : achievements) {
            if (!meetsPrerequisites(playerId, a)) {
                unmet.add(a);
            }
        }
        return unmet;
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
            loadPlayerAchievements(playerId);
            unlocked = unlockedCache.get(playerId);
            if (unlocked == null) {
                // slice-12-logic-L-01（round10 裁决实施 A6-1）：**不再建空集**。
                //
                // 原实现这里 `unlocked = ConcurrentHashMap.newKeySet(); put(...)` 维持
                // 「非 null 不变量」—— 但 A6-1 之后 `unlockedCache` 里没有该键的语义是
                // 「读失败、还不知道」，不是「没有解锁任何成就」。建空集等于把「不知道」
                // 固化成「没有」，随后 `contains` 恒 false → 已解锁成就被重新解锁、
                // 重复弹通知，正是本条缺陷的症状。
                //
                // 改为 fail-closed：缓存仍为 null（下次调用会重试加载），本次解锁请求
                // 直接放弃。真值仍在库里，下次会话结算会重新触发 tryUnlock 补上，
                // **不丢解锁**，只是延后到能读到库的那一刻。
                plugin.getLogger().fine("tryUnlock(" + achievement.getId() + ") skipped for "
                        + playerId + ": achievement cache still unloaded after a failed read;"
                        + " refusing to treat 'unknown' as 'not unlocked'.");
                return false;
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

        // F1（round4）：add 返回值才是并发权威闸门——contains 与 add 跨线程可交错。
        if (!unlocked.add(achievement.getId())) {
            return false;
        }
        long unlockedAt = System.currentTimeMillis();
        unlockedAtCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(achievement.getId(), unlockedAt);

        // 写库（方言分叉 / 表前缀 / 连接锁 / 失败只记 WARNING）已搬入 AchievementRepository：
        // 写失败不抛出，内存态保留，解锁链与通知照常继续（与搬迁前一致）。
        // DC-02：仓储无返回值 → 先登记待补写，quit/reload 幂等重放兜住 DB 抖动。
        pendingUnlockWrites.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(achievement.getId(), unlockedAt);
        achievementRepository.upsertUnlock(playerId, achievement.getId(), unlockedAt);

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
            } catch (IllegalPluginAccessException | IllegalStateException e) {
                // 插件停用窗口（调度器已关闭）：放弃本次通知，解锁记录已落库
            }
        }

        // 对外事件（api.event）：只在这里 fire —— 上面所有「不是首次解锁」的路径都已
        // 短路返回，所以事件与「真的解锁了一次」严格一一对应，重复调用不会重复广播。
        //
        // **刻意 fire 在写内存/写库之后**：取消它无法回滚已写入的 unlockedCache 与
        // 已发出的 UPSERT（唯一并发闸门是上面 unlocked.add 的返回值，插在它之前会让并发
        // 触发重复事件）。因此本事件不是 Cancellable —— 见 PlayerAchievementUnlockEvent
        // 的 javadoc。线程归属由 EventDispatch 收口（本方法可从异步线程到达）。
        PlayerAchievementUnlockEvent unlockEvent = new PlayerAchievementUnlockEvent(
                playerId, achievement.getId(), achievement.getDisplayName("en"));
        EventDispatch.fire(plugin, unlockEvent);
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
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
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
     * SQL 异常时返回"已读到的部分"（首次失败即空集，连续计数为 0，与旧
     * checkDailyStreak 的静默 return 等价）；降级与告警在仓储内完成。
     */
    private Set<String> fetchSessionDays(UUID playerId) {
        return fetchDaySet(playerId, achievementRepository.sessionDaysSql());
    }

    /**
     * Phase 3 N1：复习日列表——仅统计 {@code review_words > 0} 的日期
     * （review_words 列由 S-A 流落盘：recordSession 当 mode=review 时累加 correctCount）。
     */
    private Set<String> fetchReviewDays(UUID playerId) {
        return fetchDaySet(playerId, achievementRepository.reviewDaysSql());
    }

    private Set<String> fetchDaySet(UUID playerId, String daySql) {
        // 顺序集语义（LinkedHashSet，SQL 的 DESC 序）在调用方保持；仓储只返回 List。
        // 读失败时仓储返回"已读到的部分"（可能为空），与原"静默返回半截数据"等价。
        return new LinkedHashSet<>(achievementRepository.findRecentDays(playerId, daySql));
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

    /** Phase 3 N6：不同学习日总数（SQL 异常返回 0；失败降级在仓储内）。 */
    private int countDistinctStudyDays(UUID playerId) {
        return achievementRepository.countDistinctStudyDays(playerId);
    }

    /**
     * Phase 3 §2.2：成就墙进度快照——遍历全部成就，对 progressSource != NONE 的
     * 组装 {@code int[2]{current, target}}（一次组装、墙渲染直接消费，S-D 流按此
     * 签名调用）。全部数值为只读派生视图，现算不落盘；每项独立 try/catch 兜底回 0。
     *
     * <p><b>返回值是值快照，不是活对象</b>：{@link EnumMap} 与其中每个 {@code int[2]}
     * 都是本次新分配，不持有 {@link PlayerDataStore} 的任何缓存引用 —— 因此本方法产出的
     * 结果可以安全跨线程传给主线程渲染。
     *
     * <p><b>成本与线程（R18 修订）</b>：≤5 条查询（stats 触发的 {@code loadPlayerData}
     * 2 条 + coins 1 + 会话日列表 1 + 复习日列表 1 + study-days COUNT 1，同类来源本快照内
     * 只算一次）<b>外加</b> {@code getConversationCount} / {@code getWrongBookCleared}
     * 各自的懒加载。原注释写「≤4 条、主线程可接受」是错的：看<b>离线玩家</b>时全部走
     * cache-miss 路径，实际条数更高。因此 R18 起本方法的调用方
     *（{@code AchievementWallGUI}）把它整体放进异步段执行，本方法本身的线程契约是
     * 「只读缓存 + 最多一次懒加载」，与 {@link #getUnlockedAtSnapshot} /
     * {@link #getPrereqUnmetSnapshot} 相同 —— 与 {@code ProfileGUI} 早已在异步段调
     * {@code getPlayerStats} 的做法一致。
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
        // DC-02（round4）：清缓存前先重放待补写，否则内存与待补写一起被清 = 成就永久丢失。
        flushPendingUnlockWrites(playerId);
        unlockedCache.remove(playerId);
        unlockedAtCache.remove(playerId);
    }

    /** DC-02（round4）：幂等重放待补写（UPSERT 同值覆盖，不改 unlocked_at 语义）。
     *
     *  <p><b>R4-DC-02（round6）：按返回值判定，不再 catch 异常。</b>仓储的
     *  {@code upsertUnlock} 把 {@code SQLException} 在内部吞掉了，原实现的
     *  {@code catch (RuntimeException)} 对「SQL 写失败」这条原始缺陷的实际触发路径
     *  <b>完全不生效</b> —— 待补写被 {@code remove} 走却不回填，一次 DB 抖动即静默丢弃
     *  该玩家的全部待补写成就。现在改为看返回值：任一失败就整批回填，等下个 quit/reload
     *  重放（UPSERT 幂等，重复写无害）。 */
    private void flushPendingUnlockWrites(UUID playerId) {
        // 先原子摘走：并发新增落进新 map（computeIfAbsent 重建），不会被这次 clear 带走。
        Map<String, Long> pending = pendingUnlockWrites.remove(playerId);
        if (pending == null) return;
        Map<String, Long> failed = new java.util.LinkedHashMap<>();
        try {
            pending.forEach((id, at) -> {
                if (!achievementRepository.upsertUnlock(playerId, id, at)) {
                    failed.put(id, at);
                }
            });
        } catch (RuntimeException e) {
            // 连接层故障（getConnection 抛 RuntimeException）：整批视作失败。
            failed.putAll(pending);
        }
        if (!failed.isEmpty()) {
            pendingUnlockWrites.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).putAll(failed);
            plugin.getLogger().warning("Achievement write retry deferred: " + playerId
                    + " (" + failed.size() + "/" + pending.size() + " rows)");
        }
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
        // DC-02：reload 清缓存前先重放所有人的待补写，否则同样永久丢失。
        new HashSet<>(pendingUnlockWrites.keySet()).forEach(this::flushPendingUnlockWrites);
        unlockedCache.clear();
        unlockedAtCache.clear();
        achievementConfig = null;
    }
}
