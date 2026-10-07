package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.AchievementWallGUI;
import com.letslearnenglish.minecraftplugin.gui.EnglishSquareGUI;
import com.letslearnenglish.minecraftplugin.gui.GUIUtils;
import com.letslearnenglish.minecraftplugin.gui.LeaderboardGUI;
import com.letslearnenglish.minecraftplugin.gui.LearningCalendarGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Listener for English Square, Profile, and Achievement Wall GUI interactions.
 * Uses GuiNavigationManager for back-navigation between these GUIs.
 *
 * 槽位一律读 {@code gui-layout} 配置（默认值与 GUI 渲染时一致），
 * 避免管理员改配置后出现「按钮渲染在 A、监听器判定 B」的静默失效。
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

    /**
     * ProfileGUI / AchievementWallGUI 的返回键槽位。
     *
     * 这两个 GUI 目前把返回键**写死**在 slot 8，并不读 {@code gui-layout}（ProfileGUI 里
     * {@code gui-layout.profile.back-button.slot} 实际是死配置，无人读取）。
     * 因此监听器**不能**改成读配置 —— 一旦管理员改了配置，GUI 仍渲染在 8，
     * 监听器却去判定新槽位，反而制造出 W2 那类静默失效。
     * 唯一真相 = GUI 的写死值，这里用常量表达同一份事实。
     */
    private static final int PROFILE_BACK_SLOT = 8;
    private static final int ACHIEVEMENT_WALL_BACK_SLOT = 8;

    /** 广场里「查看者自己的头像」槽位（EnglishSquareGUI 写死 slot 0，无配置项）。 */
    private static final int SQUARE_OWN_HEAD_SLOT = 0;
    /** ProfileGUI 的其余固定槽位（GUI 写死，无配置项）。 */
    private static final int PROFILE_CALENDAR_SLOT = 0;
    private static final int PROFILE_ACHIEVEMENT_SLOT = 18;
    private static final int PROFILE_SETTINGS_SLOT = 26;

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
        ConfigManager cm = plugin.getConfigManager();

        // 槽位一律读 gui-layout 配置（EnglishSquareGUI 渲染时也读同一份配置）
        int backSlot = cm.getGuiSlot("english-square", "back-button", 8);
        int prevSlot = cm.getGuiSlot("english-square", "prev-page", 47);
        int nextSlot = cm.getGuiSlot("english-square", "next-page", 51);
        int modeWeeklySlot = cm.getGuiSlot("english-square", "mode-weekly", 2);
        int modeMonthlySlot = cm.getGuiSlot("english-square", "mode-monthly", 3);
        int modeAllTimeSlot = cm.getGuiSlot("english-square", "mode-alltime", 4);
        int modeAllSlot = cm.getGuiSlot("english-square", "mode-all", 5);

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
        int page = GUIUtils.extractPageFromTitle(title);

        if (slot == backSlot) {
            SoundManager.playSound(player, "menu-click");
            lastSquareGui = null;
            new MainMenu(plugin).open(player);
            return;
        }
        // Viewer's own head at slot 0 — open own profile
        if (slot == SQUARE_OWN_HEAD_SLOT) {
            SoundManager.playSound(player, "menu-click");
            int cp = page;
            RankingMode cmode = currentMode;
            nav.pushBack(player.getUniqueId(), () -> {
                EnglishSquareGUI backGui = new EnglishSquareGUI(plugin);
                backGui.open(player, cp, cmode);
            });
            new ProfileGUI(plugin).open(player, player.getUniqueId());
            return;
        }
        // Mode toggle buttons（槽位读配置，逐个分支判定，避免全部塌成同一个数字）
        RankingMode toggledMode = null;
        if (slot == modeWeeklySlot) {
            toggledMode = RankingMode.WEEKLY;
        } else if (slot == modeMonthlySlot) {
            toggledMode = RankingMode.MONTHLY;
        } else if (slot == modeAllTimeSlot) {
            toggledMode = RankingMode.ALL_TIME_COINS;
        } else if (slot == modeAllSlot) {
            toggledMode = RankingMode.ALL_PLAYERS;
        }
        if (toggledMode != null) {
            SoundManager.playSound(player, "menu-click");
            lastSquareGui = new EnglishSquareGUI(plugin);
            lastSquareGui.open(player, 1, toggledMode);
            return;
        }

        if (slot == prevSlot) {
            SoundManager.playSound(player, "menu-click");
            lastSquareGui = new EnglishSquareGUI(plugin);
            lastSquareGui.open(player, Math.max(1, page - 1), currentMode);
        } else if (slot == nextSlot) {
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
            RankingMode cmode = currentMode;
            nav.pushBack(player.getUniqueId(), () -> {
                EnglishSquareGUI backGui = new EnglishSquareGUI(plugin);
                backGui.open(player, cp, cmode);
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

        if (slot == PROFILE_CALENDAR_SLOT) {
            // 过往学习按钮：需要知道主页归属才能正确「返回」，
            // 标题解析不出目标时不猜（否则会把返回栈指向查看者自己的主页）
            UUID targetId = extractTargetIdFromTitle(player, title);
            if (targetId == null) return;
            SoundManager.playSound(player, "menu-click");
            UUID finalTargetId = targetId;
            nav.pushBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, finalTargetId));
            new LearningCalendarGUI(plugin).open(player);
            return;
        }
        if (slot == PROFILE_BACK_SLOT) {
            SoundManager.playSound(player, "menu-click");
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
        } else if (slot == PROFILE_ACHIEVEMENT_SLOT) {
            SoundManager.playSound(player, "menu-click");
            UUID targetId = extractTargetIdFromTitle(player, title);
            if (targetId != null) {
                UUID backToTarget = targetId;
                nav.pushBack(player.getUniqueId(),
                        () -> new ProfileGUI(plugin).open(player, backToTarget));
                new AchievementWallGUI(plugin).open(player, targetId);
            }
        } else if (slot == PROFILE_SETTINGS_SLOT) {
            // 设置只能改自己的：主页归属解析不出、或不是本人时，忽略这次点击
            // （别人主页的 slot 26 是「设置已锁定」灰色玻璃板）
            UUID targetId = extractTargetIdFromTitle(player, title);
            if (targetId != null && targetId.equals(player.getUniqueId())) {
                SoundManager.playSound(player, "menu-click");
                new ProfileGUI(plugin).openSettings(player);
            }
        }
    }

    private void handleAchievementWall(Player player, int slot) {
        if (slot == ACHIEVEMENT_WALL_BACK_SLOT) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, player.getUniqueId()));
            backAction.run();
        }
    }

    private void handleLeaderboardGUI(Player player, int slot) {
        GuiNavigationManager nav = plugin.getGuiNavigationManager();
        ConfigManager cm = plugin.getConfigManager();

        // 槽位与 LeaderboardGUI 渲染时读同一份 gui-layout 配置（默认值与 GUI 常量一致），
        // 否则管理员改了配置就会出现「标签渲染在 A、监听器判定 B」的点击失效
        int backSlot = cm.getGuiSlot("leaderboard", "back-button", LeaderboardGUI.BACK_SLOT);
        int tabCoinsSlot = cm.getGuiSlot("leaderboard", "tab-coins", LeaderboardGUI.TAB_COINS_SLOT);
        int tabWordsSlot = cm.getGuiSlot("leaderboard", "tab-words", LeaderboardGUI.TAB_WORDS_SLOT);
        int tabComboSlot = cm.getGuiSlot("leaderboard", "tab-combo", LeaderboardGUI.TAB_COMBO_SLOT);

        // Back button
        if (slot == backSlot) {
            SoundManager.playSound(player, "menu-click");
            lastLeaderboardGui = null;
            new MainMenu(plugin).open(player);
            return;
        }

        // Tab buttons（逐分支映射 tab 下标，不能直接拿 slot 当下标 —— 槽位可被配置改掉）
        int tab = -1;
        if (slot == tabCoinsSlot) {
            tab = 0;
        } else if (slot == tabWordsSlot) {
            tab = 1;
        } else if (slot == tabComboSlot) {
            tab = 2;
        }
        if (tab >= 0) {
            SoundManager.playSound(player, "menu-click");
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
        int[] entrySlots = LeaderboardGUI.ENTRY_SLOTS;
        for (int i = 0; i < entrySlots.length; i++) {
            if (entrySlots[i] == slot) return i;
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

    /**
     * 从主页标题反查主页归属玩家。
     *
     * 标题形如 {@code xxx的主页} / {@code xxx的个人主页} / {@code xxx's Profile}，
     * 需要从缓存里反查出 UUID。
     *
     * 规则：
     * 1. 取**最长的匹配名字** —— 昵称互为前缀时（如 Bob / Bobby），
     *    短名会命中长名的主页标题，只取第一个命中就会把操作作用到错误的玩家身上。
     * 2. 候选集合 = PlayerDataStore 的 profile 缓存 + 当前排行榜/广场列表里展示过的玩家 ——
     *    玩家在你看他主页的期间退出（QuitEvent 会 unloadProfileSettings 把对方从缓存摘掉）时，
     *    补上展示列表仍能反查出正确的人，而不是解析失败。
     * 3. 解析不出来时返回 {@code null}（而不是静默回落到查看者自己）——
     *    回落会让「查看别人主页」时点击返回/设置等操作**作用在查看者本人身上**，
     *    可能误改自己的隐私设置。调用方必须显式处理 null。
     *
     * @return 匹配到的玩家 UUID；标题里没有任何已知玩家名时返回 null
     */
    private UUID extractTargetIdFromTitle(Player viewer, String title) {
        PlayerDataStore store = plugin.getPlayerDataStore();

        // 候选集合：profile 缓存里的玩家优先，再补上当前展示列表里的玩家
        LinkedHashSet<UUID> candidates = new LinkedHashSet<>(store.getActivePlayerIds());
        if (lastLeaderboardGui != null) {
            candidates.addAll(lastLeaderboardGui.getDisplayedPlayerIds());
        }
        if (lastSquareGui != null) {
            candidates.addAll(lastSquareGui.getCurrentPlayerIds());
        }

        UUID bestMatch = null;
        int bestLength = 0;
        for (UUID id : candidates) {
            String name = store.getPlayerName(id);
            if (name == null || name.isEmpty()) continue;
            if (title.contains(name) && name.length() > bestLength) {
                bestMatch = id;
                bestLength = name.length();
            }
        }

        if (bestMatch == null) {
            // 标题里的玩家一个候选都没匹配上 —— 不猜，交给调用方处理
            plugin.getLogger().warning("无法从 GUI 标题解析主页归属玩家: \"" + title
                    + "\" (viewer=" + viewer.getName() + ")");
            return null;
        }
        return bestMatch;
    }
}
