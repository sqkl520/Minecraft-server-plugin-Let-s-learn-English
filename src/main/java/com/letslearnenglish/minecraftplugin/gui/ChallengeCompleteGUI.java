package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Challenge Complete GUI — 27-slot celebration screen shown when
 * a player finishes all daily word challenges.
 *
 * Layout:
 *   Row 1 (0-8):  decorative border + back button (slot 8)
 *   Row 2 (9-17): player head (11), fireworks (13), reward (15)
 *   Row 3 (18-26): decorative border + "claimed, back" button (slot 22)
 *
 * R-55: slot 8（返回键）与 slot 22（"奖励已到账"按钮）行为一致 ——
 * 都走 GuiNavigationManager.popBack（有栈回栈、无栈回主菜单），
 * 与 DailyChallenge 的 slot 8 返回语义相同。不要把其中一个改成直开主菜单。
 *
 * F1-09: the word-challenge reward is auto-deposited BEFORE this page opens
 * (see DailyChallengeManager M12 deposit-then-mark), so slot 22 is NOT a
 * "click to claim" button — it honestly says the reward already arrived and
 * just pops the GUI stack back (popBack — identical handler to slot 8,
 * see MiscGUIListener#handleChallengeComplete; NOT a direct main-menu open).
 */
public class ChallengeCompleteGUI {

    private static final int GUI_SIZE = 27;

    private final LetsLearnEnglish plugin;

    public ChallengeCompleteGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, double reward) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "daily-challenge.complete-title"));
        // R5-3/R5-16：挂结构身份（holder），holder 是唯一身份，标题仅作显示。
        Inventory inv = Bukkit.createInventory(PluginGuiHolder.of(GuiViewKey.CHALLENGE_COMPLETE),
                GUI_SIZE, title);

        // Fill with decorative glass
        ItemStack glass = createItem(Material.YELLOW_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < GUI_SIZE; i++) {
            if (i == 8 || i == 11 || i == 13 || i == 15 || i == 22) continue;
            inv.setItem(i, glass);
        }

        // Player head (slot 11)
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            skullMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "daily-challenge.player-head")
                            .replace("{player}", player.getName())));
            List<String> headLore = new ArrayList<>();
            headLore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "daily-challenge.player-head-lore")));
            skullMeta.setLore(headLore);
            head.setItemMeta(skullMeta);
        }
        inv.setItem(11, head);

        // Fireworks / celebration item (slot 13)
        ItemStack firework = createItem(Material.FIREWORK_ROCKET,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily-challenge.firework")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily-challenge.firework-lore"))));
        inv.setItem(13, firework);

        // Reward display (slot 15)
        //
        // slice-13-F5（round1 遗留 LOW，round10 裁决）：显示的是**名义奖励**（配置里的
        // `daily-challenges.word-challenge-reward` 原值），不是实发值 ——
        // `RewardEngine.depositCappedReward` 会按日限截断，截断结果被 `paid > 0` 折叠成
        // 布尔丢弃，GUI 拿不到。
        //
        // 三条路都试过，最终选「撤回承诺」而不是「显示实发值」：
        //   ① 改成实发值 —— 需在 GUI 打开路径新增一次 SELECT，而 `withConnection` 是
        //      单连接串行锁；午夜窗口正撞上 `cleanupOldChatHistory` 的全表 DELETE，
        //      主线程会等锁数秒（红队实测该场景，代码里 `LetsLearnEnglish:1266` 已有同型教训）。
        //   ② 加 `paid_amount` 列 —— 才是根治，但属 schema 变更，规模远超一条 LOW。
        //   ③ 撤回「已到账」这句承诺 —— 零查询、零 schema、零阻塞，且诚实。
        //
        // 采用 ③：文案改为「面值」，不再断言到账。玩家若真被日限截断，会收到
        // `progress.daily-cap-reached` 的独立提示，那条才是权威。
        //
        // 同时修掉 `(int)` 截断：`RewardEngine` 内部统一按「分」计（`Math.round(amount*100)`）
        // 并已有 `formatCoins`（整数去 `.0`、非整数保留两位）。`(int) 99.9 = 99` 会让
        // 玩家对账差 0.9。
        String rewardStr = formatReward(reward);
        ItemStack rewardItem = createItem(Material.GOLD_INGOT,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily-challenge.reward-title")
                                .replace("{reward}", rewardStr)),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily-challenge.reward-lore")
                                .replace("{reward}", rewardStr))));
        inv.setItem(15, rewardItem);

        // F1-09: "already claimed, back to menu" button (slot 22).
        // The reward auto-arrived before this page opened — the old
        // "Claim Reward!" text misled players into thinking the click
        // itself pays out. Behavior unchanged (popBack: back-stack if any,
        // main menu otherwise — same handler as slot 8), but the
        // wording now matches reality.
        ItemStack claimedBtn = createItem(Material.LIME_DYE,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily-challenge.claimed-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily-challenge.claimed-button-lore"))));
        inv.setItem(22, claimedBtn);

        // Back button (slot 8)
        ItemStack backBtn = createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "gui.back-lore"))));
        inv.setItem(8, backBtn);

        // R12-M3：空槽兜底填灰玻璃 —— 当前布局 27 格全有物品（本调用为 no-op），
        // 作为未来布局改动漏格时的防拖纵深防线。
        GUIUtils.fillEmpty(inv);
        SoundManager.playSound(player, "complete");
        player.openInventory(inv);
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
     * 与 {@code RewardEngine.formatCoins} 同款格式化：整数去小数、非整数保留两位。
     *
     * <p>`RewardEngine` 的那份是 private 实例方法，GUI 侧没有实例可用；两处保持同一
     * 规则（整数 `String.valueOf((long) v)`、非整数 `%.2f` + `Locale.ROOT`）比各写各的
     * 更安全 —— 玩家对账时界面与余额显示要一致。
     */
    private static String formatReward(double v) {
        return v == Math.floor(v)
                ? String.valueOf((long) v)
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}