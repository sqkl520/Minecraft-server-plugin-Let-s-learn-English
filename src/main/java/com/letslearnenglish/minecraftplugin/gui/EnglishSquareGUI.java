package com.letslearnenglish.minecraftplugin.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

/**
 * English Square GUI with ranking modes — paginated list of players.
 *
 * Supports 4 modes:
 * - WEEKLY: this week's top players by coin
 * - MONTHLY: this month's top players by coin
 * - ALL_TIME_COINS: all-time top players by total score
 * - ALL_PLAYERS: all active players (original English Square behavior)
 *
 * Players who set "show_in_square = false" get the invisibility joke screen.
 */
public class EnglishSquareGUI {

    private static final int GUI_SIZE = 54;

    private static final int[] PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int PLAYERS_PER_PAGE = PLAYER_SLOTS.length;

    private final LetsLearnEnglish plugin;
    private List<UUID> currentPlayerIds = new ArrayList<>();
    private RankingMode currentMode = RankingMode.ALL_PLAYERS;

    public EnglishSquareGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public List<UUID> getCurrentPlayerIds() {
        return currentPlayerIds;
    }

    public RankingMode getCurrentMode() {
        return currentMode;
    }

    public void open(Player viewer, int page) {
        open(viewer, page, RankingMode.ALL_PLAYERS);
    }

    public void open(Player viewer, int page, RankingMode mode) {
        ConfigManager cm = plugin.getConfigManager();
        String lang = plugin.getPlayerLanguage(viewer);
        FileConfiguration messages = cm.getMessageConfig(lang);
        PlayerDataStore store = plugin.getPlayerDataStore();
        this.currentMode = mode;

        int displayCount = cm.getMainConfig().getInt("progress-system.leaderboard.display-count", 50);

        List<LeaderboardEntry> rankedEntries;
        if (mode == RankingMode.ALL_PLAYERS) {
            // Original mode: show active players (include self if answered at least once).
            // R-05 补漏：隐身玩家（show_in_square=false）不列出。
            List<UUID> allPlayers = store.getActivePlayerIds();
            currentPlayerIds = new ArrayList<>();
            for (UUID id : allPlayers) {
                if (!store.getProfileSettings(id).isShowInSquare()) continue;
                PlayerDataStore.PlayerStats s = store.getPlayerStats(id);
                if (s.getTotalCorrect() + s.getTotalIncorrect() > 0) {
                    currentPlayerIds.add(id);
                }
            }
            rankedEntries = Collections.emptyList();
        } else {
            // Ranking mode: query leaderboard (include self if has activity)
            rankedEntries = plugin.getProgressManager().getLeaderboard().getTopPlayers(mode, displayCount);
            currentPlayerIds = new ArrayList<>();
            for (LeaderboardEntry e : rankedEntries) {
                currentPlayerIds.add(e.playerId);
            }
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) currentPlayerIds.size() / PLAYERS_PER_PAGE));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        // Title always contains "English Square" / "英语广场" so listener always matches
        String baseTitle = messages.getString("square.title");
        String modeLabel = switch (mode) {
            case WEEKLY -> messages.getString("leaderboard.mode-weekly");
            case MONTHLY -> messages.getString("leaderboard.mode-monthly");
            case ALL_TIME_COINS -> messages.getString("leaderboard.mode-alltime");
            default -> "";
        };
        String title = ChatColor.translateAlternateColorCodes('&',
                (modeLabel.isEmpty() ? baseTitle : baseTitle + " | " + modeLabel)
                        .replace("{page}", currentPlayerIds.isEmpty() ? "1/1" : page + "/" + totalPages));
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        // ---- Mode toggle buttons (slots 2-5) ----
        addModeButton(inv, cm, messages, 2, RankingMode.WEEKLY, "mode-weekly", Material.CLOCK,
                "leaderboard.mode-weekly");
        addModeButton(inv, cm, messages, 3, RankingMode.MONTHLY, "mode-monthly", Material.BOOK,
                "leaderboard.mode-monthly");
        addModeButton(inv, cm, messages, 4, RankingMode.ALL_TIME_COINS, "mode-alltime", Material.GOLD_INGOT,
                "leaderboard.mode-alltime");
        addModeButton(inv, cm, messages, 5, RankingMode.ALL_PLAYERS, "mode-all", Material.PLAYER_HEAD,
                "leaderboard.mode-all");

        // ---- Player heads ----
        int startIdx = (page - 1) * PLAYERS_PER_PAGE;
        int endIdx = Math.min(startIdx + PLAYERS_PER_PAGE, currentPlayerIds.size());

        for (int i = startIdx; i < endIdx; i++) {
            int slot = PLAYER_SLOTS[i - startIdx];
            UUID targetId = currentPlayerIds.get(i);
            String targetName = store.getPlayerName(targetId);
            if (targetName == null) targetName = messages.getString("profile.unknown-player");

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + targetName));
                List<String> lore = new ArrayList<>();

                if (mode == RankingMode.ALL_PLAYERS) {
                    PlayerDataStore.PlayerStats stats = store.getPlayerStats(targetId);
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            messages.getString("square.player-info")
                                    .replace("{correct}", String.valueOf(stats.getTotalCorrect()))
                                    .replace("{incorrect}", String.valueOf(stats.getTotalIncorrect()))));
                } else {
                    // Find the entry for this player
                    LeaderboardEntry entry = findEntry(rankedEntries, targetId);
                    if (entry != null) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                messages.getString("leaderboard.rank-entry")
                                        .replace("{rank}", String.valueOf(entry.rank))
                                        .replace("{score}", String.valueOf(entry.score))));
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                messages.getString("square.player-info")
                                        .replace("{correct}", String.valueOf(entry.correct))
                                        .replace("{incorrect}", String.valueOf(entry.incorrect))));
                    }
                }
                lore.add("");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("square.click-hint")));
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }

        // ---- Pagination ----
        // R-36: 上一页/下一页各自独立门禁（上一页只看 page>1，下一页只看 page<totalPages）。
        // 旧 pageFull 联合门禁会让不满的尾页两按钮都不放，玩家卡死翻不回去。
        if (page > 1) {
            String prevLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.prev-page"));
            String prevLore = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.prev-page-lore")
                            .replace("{page}", String.valueOf(page - 1)));
            int prevSlot = cm.getGuiSlot("english-square", "prev-page", 47);
            Material prevMaterial = cm.getGuiMaterial("english-square", "prev-page", Material.ARROW);
            inv.setItem(prevSlot, createItem(prevMaterial, prevLabel,
                    Collections.singletonList(prevLore)));
        }
        if (page < totalPages) {
            String nextLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.next-page"));
            String nextLore = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.next-page-lore")
                            .replace("{page}", String.valueOf(page + 1)));
            int nextSlot = cm.getGuiSlot("english-square", "next-page", 51);
            Material nextMaterial = cm.getGuiMaterial("english-square", "next-page", Material.ARROW);
            inv.setItem(nextSlot, createItem(nextMaterial, nextLabel,
                    Collections.singletonList(nextLore)));
        }

        // ---- Back button ----
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button"));
        int backSlot = cm.getGuiSlot("english-square", "back-button", 8);
        Material backMaterial = cm.getGuiMaterial("english-square", "back-button", Material.RED_STAINED_GLASS_PANE);
        inv.setItem(backSlot, createItem(backMaterial, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore")))));

        if (currentPlayerIds.isEmpty()) {
            String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.empty"));
            int emptySlot = cm.getGuiSlot("english-square", "empty-icon", 22);
            Material emptyMaterial = cm.getGuiMaterial("english-square", "empty-icon", Material.BOOK);
            inv.setItem(emptySlot, createItem(emptyMaterial, emptyLabel, Collections.emptyList()));
        }

        // Viewer's own head — slot 0
        PlayerDataStore.PlayerStats ownStats = store.getPlayerStats(viewer.getUniqueId());
        String ownInfo;
        if (mode == RankingMode.ALL_PLAYERS) {
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.player-info")
                            .replace("{correct}", String.valueOf(ownStats.getTotalCorrect()))
                            .replace("{incorrect}", String.valueOf(ownStats.getTotalIncorrect())));
        } else {
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("leaderboard.rank-entry")
                            .replace("{rank}", "-")
                            .replace("{score}", String.valueOf(plugin.getDatabaseManager().getCoinsEarned(viewer.getUniqueId()))));
        }
        String ownClickHint = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.click-hint"));
        ItemStack ownHead = new ItemStack(Material.PLAYER_HEAD);
        if (ownHead.getItemMeta() instanceof SkullMeta ownMeta) {
            ownMeta.setOwningPlayer(Bukkit.getOfflinePlayer(viewer.getUniqueId()));
            ownMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + viewer.getName()));
            List<String> ownLore = new ArrayList<>();
            ownLore.add(ownInfo);
            ownLore.add("");
            ownLore.add(ownClickHint);
            ownMeta.setLore(ownLore);
            ownHead.setItemMeta(ownMeta);
        }
        inv.setItem(0, ownHead);

        viewer.openInventory(inv);
    }

    private void addModeButton(Inventory inv, ConfigManager cm, FileConfiguration messages,
                                int defaultSlot, RankingMode mode, String configKey,
                                Material defaultMaterial, String messageKey) {
        int slot = cm.getGuiSlot("english-square", configKey, defaultSlot);
        Material mat = cm.getGuiMaterial("english-square", configKey, defaultMaterial);
        boolean selected = (currentMode == mode);
        Material displayMat = selected ? Material.LIME_DYE : mat;
        String label = ChatColor.translateAlternateColorCodes('&',
                (selected ? "&a&l" : "&7") + messages.getString(messageKey));
        String hint = messages.getString(selected ? "leaderboard.selected" : "leaderboard.switch-hint");
        inv.setItem(slot, createItem(displayMat, label,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', hint))));
    }

    private LeaderboardEntry findEntry(List<LeaderboardEntry> entries, UUID playerId) {
        for (LeaderboardEntry e : entries) {
            if (e.playerId.equals(playerId)) return e;
        }
        return null;
    }

    public void openHidden(Player viewer, UUID targetId) {
        ConfigManager cm = plugin.getConfigManager();
        FileConfiguration messages = cm.getMessageConfig(plugin.getPlayerLanguage(viewer));
        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.title"));
        Inventory inv = Bukkit.createInventory(null, 27, title);

        String jokeName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.hidden-title"));
        String jokeLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.hidden-lore"));

        ItemStack potion = new ItemStack(Material.POTION);
        ItemMeta meta = potion.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(jokeName);
            meta.setLore(Collections.singletonList(jokeLore));
            potion.setItemMeta(meta);
        }
        inv.setItem(13, potion);

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button"));
        int backSlot = cm.getGuiSlot("english-square", "back-button", 8);
        Material backMaterial = cm.getGuiMaterial("english-square", "back-button", Material.RED_STAINED_GLASS_PANE);
        inv.setItem(backSlot, createItem(backMaterial, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore")))));

        viewer.openInventory(inv);
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
