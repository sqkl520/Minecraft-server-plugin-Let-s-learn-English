package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeType;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.DailyChallengeGUI;
import com.letslearnenglish.minecraftplugin.gui.LearningCalendarGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Listener for Privacy Settings, Word Bank Select, and Learning Calendar GUI interactions.
 */
public class MiscGUIListener implements Listener {

    private final LetsLearnEnglish plugin;

    public MiscGUIListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Privacy Settings") && !title.contains("隐私设置")
                && !title.contains("Select Word Bank") && !title.contains("选择词库")
                && !title.contains("过往学习") && !title.contains("Study History")
                && !title.contains("Daily Challenge") && !title.contains("每日挑战")
                && !title.contains("Challenge Complete") && !title.contains("挑战完成")) {
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        if (title.contains("Privacy Settings") || title.contains("隐私设置")) {
            handlePrivacySettings(player, slot);
        } else if (title.contains("Select Word Bank") || title.contains("选择词库")) {
            handleWordBankSelect(player, slot);
        } else if (title.contains("过往学习") || title.contains("Study History")) {
            handleLearningCalendar(player, slot);
        } else if (title.contains("Daily Challenge") || title.contains("每日挑战")) {
            handleDailyChallenge(player, slot);
        } else if (title.contains("Challenge Complete") || title.contains("挑战完成")) {
            handleChallengeComplete(player, slot);
        }
    }

    private void handlePrivacySettings(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            new ProfileGUI(plugin).open(player, player.getUniqueId());
        } else if (slot == 11 || slot == 13 || slot == 15) {
            SoundManager.playSound(player, "menu-click");
            PlayerDataStore store = plugin.getPlayerDataStore();
            PlayerDataStore.ProfileSettings ps = store.getProfileSettings(player.getUniqueId());
            switch (slot) {
                case 11 -> ps.setShowAnswers(!ps.isShowAnswers());
                case 13 -> ps.setShowResults(!ps.isShowResults());
                case 15 -> ps.setShowInSquare(!ps.isShowInSquare());
            }
            store.saveProfileSettings(player.getUniqueId());
            new ProfileGUI(plugin).openSettings(player);
        }
    }

    private void handleWordBankSelect(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }
        if (slot >= 10 && slot <= 16) {
            List<String> banks = new ArrayList<>(plugin.getWordManager().getAvailableDifficulties());
            int index = slot - 10;
            if (index >= 0 && index < banks.size()) {
                SoundManager.playSound(player, "menu-click");
                String difficulty = banks.get(index);
                plugin.getWordLearningManager().startQuiz(player, difficulty, "choice");
                new WordLearningGUI(plugin).openWithMessage(player, difficulty, "choice");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.session-start", "difficulty", difficulty, "mode", "choice"));
            }
        }
    }

    private void handleLearningCalendar(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
        }
    }

    private void handleDailyChallenge(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
            return;
        }
        // Claim buttons at slots 11, 13, 15
        if (slot == 11 || slot == 13 || slot == 15) {
            SoundManager.playSound(player, "menu-click");
            var challenges = plugin.getDailyChallengeManager().getTodayChallenges(player.getUniqueId());
            int idx = (slot - 11) / 2; // 11->0, 13->1, 15->2
            if (idx >= 0 && idx < challenges.size()) {
                var cp = challenges.get(idx);
                if (cp.completed && !cp.claimed) {
                    // M12: deposit-then-mark. Transfer first, mark claimed only
                    // after money arrived. On failure the reward stays claimable
                    // and the GUI is refreshed unclaimed for retry.
                    double reward = cp.type.reward;
                    if (depositChallengeReward(player, reward)) {
                        double marked = plugin.getDailyChallengeManager()
                                .claimReward(player.getUniqueId(), cp.type);
                        if (marked > 0) {
                            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                                    "daily.reward-claimed", "reward", String.valueOf((int) marked)));
                        }
                    } else {
                        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                                "daily.claim-failed", "reward", String.valueOf((int) reward)));
                    }
                    new DailyChallengeGUI(plugin).open(player);
                }
            }
        }
    }

    /**
     * Transfer a behavioral-challenge reward via Vault. Stats are recorded
     * only when money actually arrived.
     *
     * @param player the player
     * @param reward the reward amount
     * @return true only if the deposit succeeded
     */
    private boolean depositChallengeReward(Player player, double reward) {
        var eco = plugin.getEconomy();
        if (eco == null) {
            plugin.getLogger().warning("Daily challenge reward of " + reward
                    + " for " + player.getName() + " not paid: Vault economy unavailable");
            return false;
        }
        var response = eco.depositPlayer(player, reward);
        if (response.transactionSuccess()) {
            plugin.getDatabaseManager().recordCoinsEarned(player.getUniqueId(), reward);
            return true;
        }
        plugin.getLogger().warning("Daily challenge reward deposit failed for "
                + player.getName() + ": " + response.errorMessage);
        return false;
    }

    private void handleChallengeComplete(Player player, int slot) {
        if (slot == 8 || slot == 22) {
            SoundManager.playSound(player, "menu-click");
            // Back to main menu or claim button — both go to main menu
            new MainMenu(plugin).open(player);
        }
    }
}
