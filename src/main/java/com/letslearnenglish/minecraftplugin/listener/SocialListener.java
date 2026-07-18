package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.AchievementWallGUI;
import com.letslearnenglish.minecraftplugin.gui.EnglishSquareGUI;
import com.letslearnenglish.minecraftplugin.gui.LeaderboardGUI;
import com.letslearnenglish.minecraftplugin.gui.LearningCalendarGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Listener for English Square, Profile, and Achievement Wall GUI interactions.
 * Uses GuiNavigationManager for back-navigation between these GUIs.
 */
public class SocialListener implements Listener {

    private final LetsLearnEnglish plugin;
    private EnglishSquareGUI lastSquareGui;
    private LeaderboardGUI lastLeaderboardGui;

    /** Player head slots: rows 2-5, skipping first and last columns */
    private static final int[] SQUARE_PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    public SocialListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("English Square") && !title.contains("英语广场")
                && !title.contains("周榜") && !title.contains("月榜") && !title.contains("总榜")
                && !title.contains("Weekly Ranking") && !title.contains("Monthly Ranking")
                && !title.contains("All-Time Ranking")
                && !title.contains("Leaderboard") && !title.contains("排行榜")
                && !title.contains("'s Profile") && !title.contains("的主页")
                && !title.contains("个人主页")
                && !title.contains("Achievement Wall") && !title.contains("成就墙")) {
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        if (title.contains("Leaderboard") || title.contains("排行榜")) {
            handleLeaderboardGUI(player, slot);
        } else if (title.contains("English Square") || title.contains("英语广场")) {
            handleEnglishSquareGUI(player, slot, title);
        } else if (title.contains("'s Profile") || title.contains("的主页") || title.contains("个人主页")) {
            handleProfileGUI(player, slot, title);
        } else if (title.contains("Achievement Wall") || title.contains("成就墙")) {
            handleAchievementWall(player, slot);
        }
    }

    private void handleEnglishSquareGUI(Player player, int slot, String title) {
        GuiNavigationManager nav = plugin.getGuiNavigationManager();
        PlayerDataStore store = plugin.getPlayerDataStore();

        // Determine mode and player list early (needed for slot 0 and head clicks)
        RankingMode currentMode;
        List<UUID> playerIds;
        if (lastSquareGui != null) {
            currentMode = lastSquareGui.getCurrentMode();
            playerIds = lastSquareGui.getCurrentPlayerIds();
        } else {
            currentMode = RankingMode.ALL_PLAYERS;
            List<UUID> raw = store.getActivePlayerIds();
            playerIds = new ArrayList<>();
            for (UUID id : raw) {
                PlayerDataStore.PlayerStats s = store.getPlayerStats(id);
                if (s.getTotalCorrect() + s.getTotalIncorrect() > 0) {
                    playerIds.add(id);
                }
            }
        }
        int page = extractPageFromTitle(title);

        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            lastSquareGui = null;
            new MainMenu(plugin).open(player);
            return;
        }
        // Viewer's own head at slot 0 — open own profile
        if (slot == 0) {
            SoundManager.playSound(player, "menu-click");
            int cp = page;
            RankingMode cm = currentMode;
            nav.pushBack(player.getUniqueId(), () -> {
                EnglishSquareGUI backGui = new EnglishSquareGUI(plugin);
                backGui.open(player, cp, cm);
            });
            new ProfileGUI(plugin).open(player, player.getUniqueId());
            return;
        }
        // Mode toggle buttons
        if (slot >= 2 && slot <= 5) {
            SoundManager.playSound(player, "menu-click");
            RankingMode mode = switch (slot) {
                case 2 -> RankingMode.WEEKLY;
                case 3 -> RankingMode.MONTHLY;
                case 4 -> RankingMode.ALL_TIME_COINS;
                default -> RankingMode.ALL_PLAYERS;
            };
            lastSquareGui = new EnglishSquareGUI(plugin);
            lastSquareGui.open(player, 1, mode);
            return;
        }

        if (slot == 47) {
            SoundManager.playSound(player, "menu-click");
            lastSquareGui = new EnglishSquareGUI(plugin);
            lastSquareGui.open(player, Math.max(1, page - 1), currentMode);
        } else if (slot == 51) {
            SoundManager.playSound(player, "menu-click");
            lastSquareGui = new EnglishSquareGUI(plugin);
            lastSquareGui.open(player, page + 1, currentMode);
        } else {
            int slotIdx = getPlayerSlotIndexOnSquare(slot);
            if (slotIdx < 0) return;
            int globalIndex = (page - 1) * getPlayersPerPageOnSquare() + slotIdx;
            if (globalIndex < 0 || globalIndex >= playerIds.size()) return;

            UUID targetId = playerIds.get(globalIndex);
            SoundManager.playSound(player, "menu-click");
            int cp = page;
            RankingMode cm = currentMode;
            nav.pushBack(player.getUniqueId(), () -> {
                EnglishSquareGUI backGui = new EnglishSquareGUI(plugin);
                backGui.open(player, cp, cm);
            });
            PlayerDataStore.ProfileSettings ps = store.getProfileSettings(targetId);
            if (ps.isShowInSquare()) {
                new ProfileGUI(plugin).open(player, targetId);
            } else {
                new EnglishSquareGUI(plugin).openHidden(player, targetId);
            }
        }
    }

    private void handleProfileGUI(Player player, int slot, String title) {
        GuiNavigationManager nav = plugin.getGuiNavigationManager();

        if (slot == 0) {
            SoundManager.playSound(player, "menu-click");
            UUID targetId = extractTargetIdFromTitle(player, title);
            UUID finalTargetId = targetId;
            nav.pushBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, finalTargetId));
            new LearningCalendarGUI(plugin).open(player);
            return;
        }
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
        } else if (slot == 18) {
            SoundManager.playSound(player, "menu-click");
            UUID targetId = extractTargetIdFromTitle(player, title);
            if (targetId != null) {
                UUID backToTarget = targetId;
                nav.pushBack(player.getUniqueId(),
                        () -> new ProfileGUI(plugin).open(player, backToTarget));
                new AchievementWallGUI(plugin).open(player, targetId);
            }
        } else if (slot == 26) {
            SoundManager.playSound(player, "menu-click");
            new ProfileGUI(plugin).openSettings(player);
        }
    }

    private void handleAchievementWall(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, player.getUniqueId()));
            backAction.run();
        }
    }

    /** Player head slots for LeaderboardGUI */
    private static final int[] LEADERBOARD_ENTRY_SLOTS = {
        10, 16, 19, 25, 28, 34, 37, 43, 46, 52
    };

    private void handleLeaderboardGUI(Player player, int slot) {
        GuiNavigationManager nav = plugin.getGuiNavigationManager();

        // Back button
        if (slot == 49) {
            SoundManager.playSound(player, "menu-click");
            lastLeaderboardGui = null;
            new MainMenu(plugin).open(player);
            return;
        }

        // Tab buttons (slots 0, 1, 2)
        if (slot == 0 || slot == 1 || slot == 2) {
            SoundManager.playSound(player, "menu-click");
            int tab = slot; // 0=coins, 1=words, 2=combo
            lastLeaderboardGui = new LeaderboardGUI(plugin);
            lastLeaderboardGui.open(player, tab);
            return;
        }

        // Player head slots
        int entryIndex = getLeaderboardSlotIndex(slot);
        if (entryIndex < 0) return;

        LeaderboardGUI gui = lastLeaderboardGui;
        if (gui == null) return;

        List<UUID> playerIds = gui.getDisplayedPlayerIds();
        if (entryIndex >= playerIds.size()) return;

        UUID targetId = playerIds.get(entryIndex);
        SoundManager.playSound(player, "menu-click");
        int currentTab = gui.getActiveTab();
        nav.pushBack(player.getUniqueId(), () -> {
            LeaderboardGUI backGui = new LeaderboardGUI(plugin);
            backGui.open(player, currentTab);
        });
        new ProfileGUI(plugin).open(player, targetId);
    }

    private int getLeaderboardSlotIndex(int slot) {
        for (int i = 0; i < LEADERBOARD_ENTRY_SLOTS.length; i++) {
            if (LEADERBOARD_ENTRY_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    private int getPlayerSlotIndexOnSquare(int slot) {
        for (int i = 0; i < SQUARE_PLAYER_SLOTS.length; i++) {
            if (SQUARE_PLAYER_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    private int getPlayersPerPageOnSquare() {
        return SQUARE_PLAYER_SLOTS.length;
    }

    private UUID extractTargetIdFromTitle(Player viewer, String title) {
        PlayerDataStore store = plugin.getPlayerDataStore();
        List<UUID> activeIds = store.getActivePlayerIds();
        for (UUID id : activeIds) {
            String name = store.getPlayerName(id);
            if (name == null) continue;
            if (title.contains(name)) {
                return id;
            }
        }
        return viewer.getUniqueId();
    }

    private int extractPageFromTitle(String title) {
        String[] parts = title.split(" ");
        for (int i = 0; i < parts.length; i++) {
            if ("Page".equalsIgnoreCase(parts[i]) && i + 1 < parts.length) {
                try {
                    return Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 1;
    }
}
