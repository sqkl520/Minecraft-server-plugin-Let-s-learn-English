package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

/**
 * 进度管理器：榜单与奖励引擎的懒加载持有者。
 *
 * <p>注：{@link PlayerProgress} 类现存仅测试（PlayerProgressTest）引用，随测试生命周期
 * 维护；原消费方法 getPlayerProgress/loadStreakData 已于 D2-1 死代码清扫删除。
 */
public class ProgressManagerImpl {

    private final LetsLearnEnglish plugin;
    private final PlayerDataStore playerDataStore;
    private final DatabaseManager databaseManager;

    private volatile Leaderboard leaderboard;
    private volatile RewardEngine rewardEngine;

    public ProgressManagerImpl(LetsLearnEnglish plugin, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
        this.databaseManager = plugin.getDatabaseManager();
    }

    public Leaderboard getLeaderboard() {
        if (leaderboard == null) {
            synchronized (this) {
                if (leaderboard == null) {
                    leaderboard = new Leaderboard(plugin);
                }
            }
        }
        return leaderboard;
    }

    public RewardEngine getRewardEngine() {
        if (rewardEngine == null) {
            synchronized (this) {
                if (rewardEngine == null) {
                    rewardEngine = new RewardEngine(plugin);
                }
            }
        }
        return rewardEngine;
    }
}
