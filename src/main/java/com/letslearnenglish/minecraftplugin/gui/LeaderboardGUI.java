package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Leaderboard GUI — a 54-slot inventory showing top players by coins, words learned, combo,
 * or current-season score (plan-season §2.1).
 *
 * Row 0: Tab buttons (Coins / Words / Combo / Season) + season info board (slot 4) + back button (slot 8)
 * Rows 1-5: Top 10 entries with player heads, rank, name, and score
 *
 * Season gating (R-55i true gating): when the season system is disabled or not yet
 * started (anchor in the future → season id 0) the season tab button is replaced by a
 * gray-glass placeholder (same visual language as the S-H module gates). Click-side
 * routing for the season tab lives in SocialListener.handleLeaderboardGUI — it needs a
 * follow-up branch reading {@link #TAB_SEASON_SLOT}/{@link #SEASON_TAB_INDEX} plus the
 * {@link #isSeasonAvailable(SeasonManager)} gate.
 */
public class LeaderboardGUI {

    private static final int GUI_SIZE = 54;

    /**
     * Slots for the top 10 entries: 2 per row across rows 1-5.
     * 注意：不得包含 {@link #BACK_SLOT}（slot 8，第一行）—— 否则返回键会被玩家头像覆盖。
     */
    public static final int[] ENTRY_SLOTS = {
        10, 16,  // Rank 1, 2
        19, 25,  // Rank 3, 4
        28, 34,  // Rank 5, 6
        37, 43,  // Rank 7, 8
        46, 52   // Rank 9, 10
    };

    /** 三个榜单切换标签页槽位（监听器直接引用，避免两处魔法数字漂移）。 */
    public static final int TAB_COINS_SLOT = 0;
    public static final int TAB_WORDS_SLOT = 1;
    public static final int TAB_COMBO_SLOT = 2;
    /** 第四个标签页【赛季】槽位（plan-season §2.1 默认 slot 3，行 0 空闲位）。 */
    public static final int TAB_SEASON_SLOT = 3;
    /** 赛季 tab 下标（0=coins, 1=words, 2=combo, 3=season；监听器 slot→tab 映射用）。 */
    public static final int SEASON_TAB_INDEX = 3;
    /** 赛季信息牌槽位（plan-season §2.1：PAPER，赛季号 / 剩余 / 本人名次）。 */
    public static final int SEASON_INFO_SLOT = 4;
    /** 返回主菜单按钮 —— 与其余 GUI 统一在右上角 slot 8。 */
    public static final int BACK_SLOT = 8;

    /** 赛季榜缓存读取上限（plan-season §1.7：与 Leaderboard.DEFAULT_CACHE_LIMIT=50 对齐）。 */
    private static final int SEASON_CACHE_LIMIT = 50;

    private final LetsLearnEnglish plugin;
    private int activeTab; // 0=coins, 1=words, 2=combo, 3=season
    private List<UUID> displayedPlayerIds;

    public LeaderboardGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.activeTab = 0;
        this.displayedPlayerIds = new ArrayList<>();
    }

    public int getActiveTab() {
        return activeTab;
    }

    public List<UUID> getDisplayedPlayerIds() {
        return displayedPlayerIds;
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int tab) {
        ConfigManager cm = plugin.getConfigManager();
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = cm.getMessageConfig(lang);

        SeasonManager season = plugin.getSeasonManager();
        boolean seasonAvailable = SeasonManager.isAvailable(season);
        // R-55i 真 gating：赛季关闭/未开启时 season tab 不可达 —— 旧界面残留的 3 号
        // tab 重开在此静默回落 coins（干净容器化旧态）；点击新开路径由
        // SocialListener 赛季分支给 season.disabled 提示（tester L8 语义：残留
        // 回落 vs 主动点击提示，两路各自合理且均已收口）。
        if (tab == SEASON_TAB_INDEX && !seasonAvailable) {
            tab = 0;
        }
        this.activeTab = tab;

        List<LeaderboardEntry> entries;
        String subtabLabel;
        List<SeasonManager.SeasonRankRow> seasonRows = null;
        switch (tab) {
            case 1:
                entries = plugin.getProgressManager().getLeaderboard().getCachedWordsLeaderboard();
                subtabLabel = MessageUtil.getGuiMsg(messages, "leaderboard.tab-words");
                break;
            case 2:
                entries = plugin.getProgressManager().getLeaderboard().getCachedComboLeaderboard();
                subtabLabel = MessageUtil.getGuiMsg(messages, "leaderboard.tab-combo");
                break;
            case SEASON_TAB_INDEX:
                // plan-season §2.1：赛季榜数据走 SeasonManager 的 5 分钟缓存快照
                //（getCurrentTopCached，零查库 —— 不复制广场"开界面主线程查库"旧模式
                // PF-SEA-04）。SeasonRankRow → LeaderboardEntry 适配（correct/incorrect/
                // sessions 置 0，仅本渲染路径消费），复用既有头像渲染与点击快照契约。
                seasonRows = season.getCurrentTopCached(SEASON_CACHE_LIMIT);
                entries = new ArrayList<>(seasonRows.size());
                for (SeasonManager.SeasonRankRow row : seasonRows) {
                    entries.add(new LeaderboardEntry(row.uuid(),
                            row.name() != null ? row.name()
                                    : MessageUtil.getGuiMsg(messages, "profile.unknown-player"),
                            row.score(), 0, 0, 0, row.rank()));
                }
                subtabLabel = MessageUtil.getGuiMsg(messages, "leaderboard.tab-season");
                break;
            default:
                entries = plugin.getProgressManager().getLeaderboard().getCachedCoinsLeaderboard();
                subtabLabel = MessageUtil.getGuiMsg(messages, "leaderboard.tab-coins");
                break;
        }

        this.displayedPlayerIds = new ArrayList<>();
        for (LeaderboardEntry e : entries) {
            displayedPlayerIds.add(e.playerId);
        }

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "leaderboard.title")
                        + " | " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', subtabLabel)));

        // 赛季 tab：标题追加剩余时间（与信息牌 / PAPI %lle_season_days_left% 同源公式；
        // 复用 season.remaining 单键 —— 不再新增 days-only 键，见 plan-season §2.1/§2.3）。
        String remainingLine = null;
        if (tab == SEASON_TAB_INDEX) {
            remainingLine = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "season.remaining")
                            .replace("{days}", String.valueOf(seasonDaysLeft(season)))
                            .replace("{hours}", String.valueOf(seasonHoursLeft(season))));
            title += " | " + ChatColor.stripColor(remainingLine);
        }
        // R5-4：挂结构身份 holder。payload 用 NoPayload —— tab 由点击侧按槽位
        // 反查（{@link #TAB_COINS_SLOT} 等），不依赖标题也不依赖 payload；
        // 标题里的 tab 名 + 赛季剩余行只是展示，永不参与判定。
        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.LEADERBOARD), GUI_SIZE, title);

        // ---- Tab buttons (slots 0, 1, 2, 3) ----
        addTabButton(inv, cm, messages, TAB_COINS_SLOT, 0, "tab-coins",
                Material.GOLD_INGOT, "leaderboard.tab-coins");
        addTabButton(inv, cm, messages, TAB_WORDS_SLOT, 1, "tab-words",
                Material.BOOK, "leaderboard.tab-words");
        addTabButton(inv, cm, messages, TAB_COMBO_SLOT, 2, "tab-combo",
                Material.DIAMOND_SWORD, "leaderboard.tab-combo");
        if (seasonAvailable) {
            addTabButton(inv, cm, messages, TAB_SEASON_SLOT, SEASON_TAB_INDEX, "tab-season",
                    Material.NETHER_STAR, "leaderboard.tab-season");
        } else {
            // S-H 灰玻璃模式（EnglishSquareGUI.addDisabledModePlaceholder 同款）：模块
            // 关闭的入口以灰玻璃占位出镜；点击侧读同一 gui-layout 键回落模块提示。
            int disabledSlot = cm.getGuiSlot("leaderboard", "tab-season", TAB_SEASON_SLOT);
            inv.setItem(disabledSlot, createItem(Material.GRAY_STAINED_GLASS_PANE, " ",
                    Collections.emptyList()));
        }

        // ---- Season info board (season tab only; plan-season §2.1 slot 4) ----
        if (tab == SEASON_TAB_INDEX) {
            renderSeasonInfoBoard(inv, cm, messages, player, season, seasonRows, remainingLine);
        }

        // ---- Top 10 entries ----
        int displayCount = Math.min(entries.size(), ENTRY_SLOTS.length);
        for (int i = 0; i < displayCount; i++) {
            LeaderboardEntry entry = entries.get(i);
            int slot = ENTRY_SLOTS[i];
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.playerId));
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                        "&6#" + entry.rank + " &f" + entry.playerName));
                List<String> lore = new ArrayList<>();
                String scoreLabel;
                switch (tab) {
                    case 1:
                        scoreLabel = MessageUtil.getGuiMsg(messages, "leaderboard.entry-words");
                        break;
                    case 2:
                        scoreLabel = MessageUtil.getGuiMsg(messages, "leaderboard.entry-combo");
                        break;
                    case SEASON_TAB_INDEX:
                        // plan-season §2.1：赛季榜分数 = 窗口内"学习分"
                        scoreLabel = MessageUtil.getGuiMsg(messages, "leaderboard.entry-season");
                        break;
                    default:
                        scoreLabel = MessageUtil.getGuiMsg(messages, "leaderboard.entry-coins");
                        break;
                }
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        scoreLabel.replace("{score}", String.valueOf(entry.score))));
                lore.add("");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "leaderboard.click-hint")));
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }

        // ---- Empty state ----
        if (entries.isEmpty()) {
            // plan-season §2.1：赛季空态用专用文案（"本赛季还没有人上榜…"，slot 22
            // 既有空态位）；其余 tab 保持 leaderboard.empty。
            String emptyKey = (tab == SEASON_TAB_INDEX) ? "season.empty" : "leaderboard.empty";
            String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, emptyKey));
            ItemStack emptyItem = createItem(Material.BARRIER, emptyLabel, Collections.emptyList());
            inv.setItem(22, emptyItem);
        }

        // ---- Back button (slot 8) ----
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        int backSlot = cm.getGuiSlot("leaderboard", "back-button", BACK_SLOT);
        Material backMaterial = cm.getGuiMaterial("leaderboard", "back-button", Material.RED_STAINED_GLASS_PANE);
        inv.setItem(backSlot, createItem(backMaterial, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // R12-M3：空槽填灰玻璃（防拖纵深）——renderSeasonInfoBoard 与本方法共享同一
        // inv；fillEmpty 放在全部 setItem 之后的最外层，晚者不覆盖信息牌（只填空槽）。
        GUIUtils.fillEmpty(inv);
        player.openInventory(inv);
    }

    /**
     * 赛季 tab 是否可用（渲染侧与点击侧共用的判定，plan-season §2.1 / R-55i 真 gating）：
     * SeasonManager 为 null（DI 未就位）或 {@code isEnabled()=false} 即不可用 ——
     * 落盘的 isEnabled() 已折叠 {@code season.enabled=false} 与"锚点在未来、
     * currentSeasonId=0（赛季未开启）"两种情况。public static —— SocialListener
     * 的赛季分支 fan-in 时直接调用，避免两处判定漂移。
     */
    /**
     * 可用性薄委托（tester L4 收口：真相源 {@link SeasonManager#isAvailable}）。
     * 落盘的 isEnabled() 已折叠 {@code season.enabled=false} 与"锚点在未来、
     * currentSeasonId=0（赛季未开启）"两种情况。
     */
    public static boolean isSeasonAvailable(SeasonManager season) {
        return SeasonManager.isAvailable(season);
    }

    /**
     * 剩余天数（显示侧与 PAPI {@code %lle_season_days_left%} 同源公式）：
     * 当前季 {@code windowOf(currentSeasonId(today)).end} 减今天、**含今天的 +1**
     * —— 闭区间 [start, end] 下首日显示整季天数、末日显示 1（plan 验收
     * "第 2 赛季 / 剩余 30 天"）。零 IO 纯计算；LeaderboardGUI（标题/信息牌）与
     * LearnCommand /le season 共用。LLEPapiExpansion 复制了同一公式（跨层不引 gui
     * 类），改动须两侧同步。赛季未开启（id≤0，锚点在未来）返回 0（调用方已判
     * {@link #isSeasonAvailable}）。
     */
    /**
     * 剩余天数（tester L4 收口：上收 {@link SeasonManager#daysLeft()} 单一真相源，
     * 原 LeaderboardGUI/LLEPapiExpansion 两份复制实现已废；本方法保留为薄委托
     * 以兼容既有调用点）。赛季未开启返回 0。
     */
    public static long seasonDaysLeft(SeasonManager season) {
        return season == null ? 0 : season.daysLeft();
    }

    /**
     * 剩余小时数细节（信息牌 lore / /le season 的 {hours}）：{@code millisUntilEnd()}
     * 的一日内余量（% 24）—— 天数为主、小时补充（plan-season §2.1）。纯内存计算零 IO。
     */
    public static long seasonHoursLeft(SeasonManager season) {
        return (Math.max(0, season.millisUntilEnd()) / 3_600_000L) % 24;
    }

    /**
     * 赛季信息牌（plan-season §2.1，slot 4 PAPER）：
     * 第一行赛季号（season.title）、剩余整行（season.remaining，{days}/{hours}）、
     * 本人名次（season.your-rank 或 season.unranked；{rank} 复用 placeholder.rank-unranked
     * 未上榜文本 — 与 PAPI %lle_season_rank% 同语义）。全部读内存缓存，零查库。
     */
    private void renderSeasonInfoBoard(Inventory inv, ConfigManager cm, FileConfiguration messages,
                                       Player player, SeasonManager season,
                                       List<SeasonManager.SeasonRankRow> seasonRows,
                                       String remainingLine) {
        int slot = cm.getGuiSlot("leaderboard", "season-info", SEASON_INFO_SLOT);
        Material material = cm.getGuiMaterial("leaderboard", "season-info", Material.PAPER);

        int seasonId = season.currentSeasonId(LocalDate.now());
        String displayName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "season.title")
                        .replace("{season}", String.valueOf(seasonId)));

        List<String> lore = new ArrayList<>();
        lore.add(remainingLine);

        // 本人名次：getRankOf 走缓存（-1 = 未上榜）。分数从缓存 top 中反查
        //（榜外无分数可查，与 PAPI 的"未上榜"同表现）。
        int rank = season.getRankOf(player.getUniqueId());
        if (rank > 0) {
            long score = 0;
            if (seasonRows != null) {
                for (SeasonManager.SeasonRankRow row : seasonRows) {
                    if (row.uuid().equals(player.getUniqueId())) {
                        score = row.score();
                        break;
                    }
                }
            }
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "season.your-rank")
                            .replace("{rank}", String.valueOf(rank))
                            .replace("{score}", String.valueOf(score))));
        } else {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "season.unranked")));
        }

        inv.setItem(slot, createItem(material, displayName, lore));
    }

    private void addTabButton(Inventory inv, ConfigManager cm, FileConfiguration messages,
                              int defaultSlot, int tabIndex, String configKey,
                              Material defaultMaterial, String messageKey) {
        int slot = cm.getGuiSlot("leaderboard", configKey, defaultSlot);
        Material mat = cm.getGuiMaterial("leaderboard", configKey, defaultMaterial);
        boolean selected = (activeTab == tabIndex);
        Material displayMat = selected ? Material.LIME_STAINED_GLASS_PANE : mat;
        String label = ChatColor.translateAlternateColorCodes('&',
                (selected ? "&a&l" : "&7") + MessageUtil.getGuiMsg(messages, messageKey));
        String hint = MessageUtil.getGuiMsg(messages, selected ? "leaderboard.selected" : "leaderboard.switch-hint");
        inv.setItem(slot, createItem(displayMat, label,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', hint))));
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
