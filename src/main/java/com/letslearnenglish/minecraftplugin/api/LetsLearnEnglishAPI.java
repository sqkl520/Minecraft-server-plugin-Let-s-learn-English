package com.letslearnenglish.minecraftplugin.api;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

/**
 * Let's Learn English! Public API Interface
 *
 * Provides API for other plugin developers to extend learning functionality.
 * All methods are designed to be thread-safe.
 *
 * Usage example:
 * <pre>
 *   LetsLearnEnglishAPI api = LetsLearnEnglishAPI.getInstance();
 *   api.getWordManager().getPlayerMastery(player, "apple");
 * </pre>
 */
public class LetsLearnEnglishAPI {

    /**
     * 全局单例引用。
     *
     * <p><b>为什么是 volatile</b>：它同时被
     * <ul>
     *   <li>插件主线程写（构造期 {@code new LetsLearnEnglishAPI(this)}、停用期
     *       {@link #clearInstance()}）；</li>
     *   <li>第三方插件在<b>任意线程</b>读（{@link #getInstance()}，无法约束调用方线程）。</li>
     * </ul>
     * 非 volatile 时第三方线程可能读到陈旧的非 null 值 —— 而它指向的那个实例在停用后
     * 所有 getter 都会返回 null Manager，等于给调用方一个「看起来可用、用起来全 null」的对象。
     * volatile 保证「停用后立刻读到 null」这个可见性。</p>
     */
    private static volatile LetsLearnEnglishAPI instance;
    private final LetsLearnEnglish plugin;

    public LetsLearnEnglishAPI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static LetsLearnEnglishAPI getInstance() {
        return instance;
    }

    /**
     * 注销单例引用（{@code /lea reload} 拆运行时、以及插件停用时调用）。
     *
     * <p><b>为什么必须有它</b>：{@code getInstance()} 是第三方插件拿本插件 API 的唯一入口。
     * 运行时被拆掉后，若 {@code instance} 仍指向旧对象，第三方插件会拿到一个
     * <b>所有 getter 都返回 null</b> 的僵尸对象 —— 那是比「拿不到 API」更糟的状态：
     * 调用方通常只判 {@code getInstance() != null}，于是继续调 getter 并 NPE 在自己那一侧。
     * 置 null 让「本插件未启用」这件事对第三方<b>可见且一致</b>
     * —— 与「启动时就是 server.enabled=false」的状态完全同构（那时 instance 本来就是 null）。</p>
     *
     * <p><b>只清指向自己的引用</b>：若 {@code instance} 已被另一个实例覆盖（例如热重载换了
     * ClassLoader 后新实例先注册），这里不能把<b>别人</b>的注册抹掉 —— 否则一次迟到的
     * 停用会把刚启用的 API 变成 null。这个判断在正常情况下恒真（同一时刻只有一个实例），
     * 存在只是为了在异常时序下不制造新问题。</p>
     */
    public static void clearInstance() {
        if (instance != null && instance.plugin != null) {
            // 只有当 instance 确实属于「正在停用的那个插件」时才清。
            // 用 plugin 的 isEnabled() 判断：停用流程里它已是 false；
            // 若已是 false 而 instance 仍在，说明这是我们自己的残留，安全清除。
            // 若它仍 enabled（说明 instance 属于一个活着的插件实例），不动。
            if (!instance.plugin.isEnabled()) {
                instance = null;
            }
        }
    }

    /**
     * 强制注销单例（不考虑插件是否仍启用）。仅供插件自身的停用流程调用。
     *
     * <p>与 {@link #clearInstance()} 的区别：后者有「别抹掉别人注册」的保护，
     * 而本方法无条件清 —— 用在 {@code disableAll()} 的 D10（那时已经确定是本实例在拆）。
     *
     * <p><b>为什么是 public 而不是包级</b>：调用方 {@code LetsLearnEnglish} 在
     * {@code minecraftplugin} 包，本类在 {@code minecraftplugin.api} 包，包级可见跨不过去。
     * 方法名带 {@code force} 前缀 + 这段 javadoc 是给第三方读者的唯一提示：
     * <b>这不是给你调的</b>。
     */
    public static void forceClearInstance() {
        instance = null;
    }

    public WordManagerImpl getWordManager() {
        return plugin.getWordManager();
    }

    public AIChatManager getAIChatManager() {
        return plugin.getAIChatManager();
    }

    public ProgressManagerImpl getProgressManager() {
        return plugin.getProgressManager();
    }

    public boolean isPlayerLearning(Player player) {
        return plugin.getPlayerDataStore().isPlayerInSession(player.getUniqueId());
    }

    public String getPluginVersion() {
        return plugin.getDescription().getVersion();
    }

    public AchievementManager getAchievementManager() {
        return plugin.getAchievementManager();
    }

    public PlayerDataStore getPlayerDataStore() {
        return plugin.getPlayerDataStore();
    }

    public boolean hasAchievement(UUID playerId, Achievement achievement) {
        return plugin.getAchievementManager().hasAchievement(playerId, achievement);
    }

    public Set<Achievement> getUnlockedAchievements(UUID playerId) {
        return plugin.getAchievementManager().getUnlockedAchievements(playerId);
    }

    public int getPlayerAge(UUID playerId) {
        // L-写路径收口：这是最危险的一处 —— 第三方插件可传任意（含离线）UUID，
        // 而旧 getProfileSettings 会 computeIfAbsent 在 profileSettingsCache 里种下
        // 一份「全公开」条目，且该条目会被 getActivePlayerIds() 当作广场候选。
        // 一次第三方调用，泄漏到重启。改用 loadProfileSettingsForDisplay：
        //   缓存命中 → 零 IO 返回真实实例；
        //   miss     → 同步查一行 player_profiles（离线玩家因此能拿到真实年龄，
        //              语义比 getProfileSettingsReadOnly 的 age=0「未设置」更准）；
        //   DB 无行  → new ProfileSettings()（新玩家默认值，与 loadProfileSettings 同口径）；
        //   查询失败 → unresolvedFallback()（fail-closed）。
        // 关键契约：该方法绝不写 profileSettingsCache。
        return plugin.getPlayerDataStore().loadProfileSettingsForDisplay(playerId).getAge();
    }

    public void setPlayerAge(UUID playerId, int age) {
        plugin.getPlayerDataStore().setAge(playerId, age);
    }
}