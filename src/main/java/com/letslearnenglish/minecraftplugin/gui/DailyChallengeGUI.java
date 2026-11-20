package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeProgress;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeType;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
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
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Daily Challenge GUI — shows today's challenges (up to N, see CHALLENGE_SLOTS)
 * with progress and claim buttons.
 *
 * 27-slot layout:
 *   Row 1 (0-8): header + back button
 *   Row 2 (9-17): challenge slots — first N entries of {@link #CHALLENGE_SLOTS}
 *                  (11/13/15/17; N=3 renders 11/13/15 exactly like before)
 *   Row 3 (18-26): word-challenge visibility line (FIX-CHAL-01, slot 22)
 */
public class DailyChallengeGUI {

    private static final int GUI_SIZE = 27;

    /**
     * MC-5（F1-12 配套）：行为挑战条目槽位（27 格行 2 的均匀间隔满行）。
     * 渲染取前 N 个（N = 今日实际分配数 ≤ 4）；
     * {@link com.letslearnenglish.minecraftplugin.listener.MiscGUIListener} 的
     * 点击→下标映射与本常量共用同一份槽位真相（避免"点 A 领 B"漂移），
     * 不要再出现手写的 {@code (slot-11)/2}。
     *
     * 必须保持升序 —— 点击侧用 {@link java.util.Arrays#binarySearch} 查位。
     */
    public static final int[] CHALLENGE_SLOTS = {11, 13, 15, 17};

    /**
     * R12-M1（E1）：openAsync in-flight 去重 token —— 跨实例单调序号（static，
     * 本类无 per-player 实例概念、{@code new} 即用即弃）+ per-player 最新 token 记录。
     * 主线程首部记 token、回主线程 render 前比对：不等 = 已有更新的打开请求，本次作废
     * （连点/交叉打开时只渲染最新一次，消闪变与双倍查库）。
     *
     * <p>openSeq 条目随每次 openAsync 覆写自身收敛（量级=历史玩家数条 Long），
     * 不挂 quit 清理（与 openSeq 同族的 joinReminderSent 惰性清理裁决一致）。
     */
    private static final AtomicLong SEQ = new AtomicLong();
    private static final Map<UUID, Long> openSeq = new ConcurrentHashMap<>();

    private final LetsLearnEnglish plugin;

    public DailyChallengeGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * C-3：兼容入口 —— 统一委托 {@link #openAsync(Player)}。
     * 打开路径彻底断根：所有调用方与历史入口都走异步两段式，主线程零查库。
     */
    public void open(Player player) {
        openAsync(player);
    }

    /**
     * C-3：异步两段式打开入口。
     *
     * <p>异步段（仅 DB/logger，无 Bukkit 世界 API）：{@code getTodayChallenges}
     * （SELECT + 可能的 degradeStaleChallenges UPDATE）与 {@code getTodayWordProgress}
     * （可能的单词挑战分配写 + SELECT；签名收 Player 但仅用其 UUID，线程安全）。
     * 主线程段只渲染（{@link #openWithData}，零 DB 调用）。
     *
     * <p>R-67：异步体开头与 runTask 前各查一次 {@code isEnabled()}；主线程段
     * {@code isOnline()} 复检（异步间隙玩家可能下线）。
     */
    public void openAsync(Player player) {
        UUID playerId = player.getUniqueId();
        // R12-M1（E1）：主线程首部登记本次打开 token（连点/交叉打开去重）。
        final long token = SEQ.incrementAndGet();
        openSeq.put(playerId, token);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) return;
                List<ChallengeProgress> challenges;
                int[] wordProgress;
                boolean wordPaid; // R5-DC-13：权威「已付款」台账（完成 ≠ 已到账），须在异步段取。
                try {
                    challenges = plugin.getDailyChallengeManager().getTodayChallenges(playerId);
                    wordProgress = plugin.getDailyChallengeManager().getTodayWordProgress(player);
                    wordPaid = plugin.getDailyChallengeManager().isWordRewardPaidToday(playerId);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to preload daily challenge GUI for " + playerId, e);
                    // R11-M3：失败必有反馈 —— 异步段不得直调 Bukkit API，切主线程发提示+失败音。
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                                    "daily.load-failed"));
                            SoundManager.playSound(player, "incorrect");
                        });
                    } catch (IllegalPluginAccessException | IllegalStateException ignored) {
                        // 关服竞态：调度器已关闭，放弃提示（与同方法既有 openWithData 调度守卫同款）
                    }
                    return;
                }
                if (!plugin.isEnabled()) return;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        // R12-M1（E1）：render 前比对 token——已有更新的打开请求则弃本次
                        //（C1 的 catch 提示不受本门约束：失败要告知，语义正确）。
                        if (openSeq.getOrDefault(playerId, -1L) != token) return;
                        openWithData(player, challenges, wordProgress, wordPaid);
                    });
                } catch (IllegalPluginAccessException | IllegalStateException ignored) {
                    // 关服竞态：调度器已关闭，放弃本次渲染
                }
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次打开
        }
    }

    /**
     * C-3：主线程渲染 —— 数据由 {@link #openAsync(Player)} 预取，本方法零 DB 调用。
     */
    private void openWithData(Player player, List<ChallengeProgress> challenges, int[] wordProgress, boolean wordPaid) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "daily.title"));
        // R5-3/R5-16：挂结构身份（holder），holder 是唯一身份，标题仅作显示。
        Inventory inv = Bukkit.createInventory(PluginGuiHolder.of(GuiViewKey.DAILY_CHALLENGE),
                GUI_SIZE, title);

        // MC-5：取前 N 个（N ≤ 4 = CHALLENGE_SLOTS.length，分配侧已按配置/可用数封顶）。
        for (int i = 0; i < Math.min(challenges.size(), CHALLENGE_SLOTS.length); i++) {
            ChallengeProgress cp = challenges.get(i);
            ChallengeType type = cp.type;

            Material mat;
            String status;
            if (cp.claimed) {
                mat = Material.LIME_DYE;
                status = MessageUtil.getGuiMsg(messages, "daily.claimed");
            } else if (cp.completed) {
                mat = Material.GOLD_INGOT;
                status = MessageUtil.getGuiMsg(messages, "daily.completed");
            } else {
                mat = Material.PAPER;
                status = MessageUtil.getGuiMsg(messages, "daily.in-progress");
            }

            String desc = MessageUtil.getGuiMsg(messages, type.descKey)
                    .replace("{target}", String.valueOf(type.target));
            // S7：展示层 clamp —— 已完成挑战的 progress 在写入侧仍可能继续累加（写入侧修复在 S5 流），
            // 这里显示 Math.min(progress, target)，不再出现 47/10；target<=0 时原样显示（本渲染路径无除法，不涉除零）。
            int displayProgress = type.target > 0 ? Math.min(cp.progress, type.target) : cp.progress;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + desc));
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "daily.progress")
                            .replace("{progress}", String.valueOf(displayProgress))
                            .replace("{target}", String.valueOf(type.target))));
            lore.add(ChatColor.translateAlternateColorCodes('&', status));
            if (!cp.claimed) {
                // MC-5：奖励展示走配置化读取口（与领取到账同一数值来源，缺键/非法回落默认）。
                // R-16 残留③：原为 String.valueOf((int) ...getChallengeReward(type))。
                // getChallengeReward 返回 double（DailyChallengeManager:409，读
                // daily-challenges.rewards.<id> 后经 Number.doubleValue()），(int) 强转
                // 把小数部分整个抹掉：管理员配 reward: 99.9 时卡片写「99 金币」，
                // 而实际到账 99.9 —— 展示与到账当场对不上账，玩家对账必投诉。
                // 这是全仓 (int)-金额渲染缺陷类的最后一处残留：LearnCommand:840（签到实发）、
                // :566（reward-pending）已改 formatCoins，MiscGUIListener 与
                // DailyChallengeManager 已改走 RewardEngine.CappedPayout.paidText()。
                // 现改走本类新增的 formatCoins（见类末尾），与到账播报同一套算术。
                double reward = plugin.getDailyChallengeManager().getChallengeReward(type);
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily.reward-hint")
                                .replace("{reward}", formatCoins(reward))));
            }

            inv.setItem(CHALLENGE_SLOTS[i], createItem(mat,
                    ChatColor.translateAlternateColorCodes('&', "&e" + getChallengeName(type, messages)),
                    lore));
        }

        // Back button
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // FIX-CHAL-01: cross-visibility (read-only) — word-challenge progress
        // line at slot 22 (mirrors ChallengeCompleteGUI's center-bottom slot).
        // Clicking does nothing (see MiscGUIListener.handleDailyChallenge);
        // words are completed via learning, not claimed here.
        // C-3：wordProgress 由 openAsync 异步预取（可能含单词挑战分配写），此处只读渲染。
        int wordDone = wordProgress[0];
        int wordTotal = wordProgress[1];
        boolean wordAllDone = wordTotal > 0 && wordDone >= wordTotal;
        Material wordMat = (wordAllDone && wordPaid) ? Material.LIME_DYE : Material.BOOK;
        List<String> wordLore = new ArrayList<>();
        wordLore.add(ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "daily.word-progress")
                        .replace("{progress}", String.valueOf(wordDone))
                        .replace("{total}", String.valueOf(wordTotal))));
        wordLore.add(ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "daily.word-hint")));
        if (wordAllDone && wordPaid) {
            wordLore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "daily.word-done")));
        }
        inv.setItem(22, createItem(wordMat,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily.word-title")),
                wordLore));

        GUIUtils.fillEmpty(inv); // R12-M3：空槽填灰玻璃（防拖纵深；全部 setItem 之后）
        player.openInventory(inv);
    }

    private String getChallengeName(ChallengeType type, FileConfiguration messages) {
        String key = switch (type) {
            case ANSWER_10 -> "daily.name-answer-10";
            case SESSION_3 -> "daily.name-session-3";
            case COMBO_5 -> "daily.name-combo-5";
            case AI_CHAT_5 -> "daily.name-ai-chat-5";
        };
        return ChatColor.translateAlternateColorCodes('&', MessageUtil.getGuiMsg(messages, key));
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

    /**
     * R-16：金币金额的玩家可见格式化 —— 整数值不带小数点（{@code 100}），
     * 非整数值保留两位小数（{@code 99.90}）。仅用于 i18n 占位符 {@code {reward}}。
     *
     * <p><b>这是本仓库第 5 份逐字重复的副本，请勿再复制第 6 份。</b>
     * 现存完整清单（全部是同一个 4 行算术）：
     * <ol>
     *   <li>{@code RewardEngine.formatCoins}（core/progress/RewardEngine.java:440）
     *       —— private static，唯一"源头"，但 GUI/命令侧拿不到；</li>
     *   <li>{@code LearnCommand.formatCoins}（command/LearnCommand.java:722）—— private static；</li>
     *   <li>{@code ChallengeCompleteGUI.formatReward}（gui/ChallengeCompleteGUI.java:168）
     *       —— 方法名还漂了（formatReward ≠ formatCoins）；</li>
     *   <li>{@code SeasonManager.formatCoins}（core/season/SeasonManager.java:2166）—— private 实例方法；</li>
     *   <li>本方法（gui/DailyChallengeGUI.java）。</li>
     * </ol>
     *
     * <p><b>推荐收口（留给有 RewardEngine 写权限的批次做）：</b>把
     * {@code RewardEngine.formatCoins} 提升为 {@code public static}（或抽到
     * {@code util/CoinFormat}），然后删除上面全部 4 份副本改调它 —— 金额格式化是
     * 玩家对账口径，多处各写一份的代价是"界面与余额显示不一致"这类无人认领的漏洞，
     * 本文件这处 {@code (int)} 强转正是从这个缝里漏进去的。
     *
     * <p>行为与其它 4 份逐字一致：{@code 100.0 → "100"}、{@code 99.9 → "99.90"}、
     * {@code 0.2 → "0.20"}、{@code 0.0 → "0"}。用 {@link java.util.Locale#ROOT}
     * 锁死小数点（否则服务端默认区域会把 99.90 渲染成 99,90）。
     */
    private static String formatCoins(double v) {
        return v == Math.floor(v)
                ? String.valueOf((long) v)
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
