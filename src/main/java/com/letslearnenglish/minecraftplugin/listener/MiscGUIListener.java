package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.WordModes;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.DailyChallengeGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import com.letslearnenglish.minecraftplugin.gui.WordBankSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Listener for Privacy Settings, Word Bank Select, and Learning Calendar GUI interactions.
 */
public class MiscGUIListener implements Listener {

    private final LetsLearnEnglish plugin;

    /**
     * R-02: behavioral-claim stripe locks. A fixed array of 32 monitors —
     * indexed by UUID hash — so the full "check → deposit → mark" critical
     * section runs serialized per player with no growing map and no cleanup.
     */
    private final Object[] claimStripeLocks = new Object[32];

    public MiscGUIListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        for (int i = 0; i < claimStripeLocks.length; i++) {
            claimStripeLocks[i] = new Object();
        }
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

        // R-01/R-22: 只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

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
            // R-86b：设置页返回走返回栈 —— SocialListener 在进入设置前压入「本人主页」帧，
            // 这里弹回；无栈时 fallback 也开本人主页（防无动作）。与其它返回键语义一致。
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, player.getUniqueId()));
            backAction.run();
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
        if (slot < WordBankSelectGUI.BANK_SLOT_START
                || slot >= WordBankSelectGUI.BANK_SLOT_START + WordBankSelectGUI.BANK_SLOT_COUNT) {
            return;
        }
        // MC-2 word-system 门（plan-menu-config 1.(b)："WordBankSelectGUI 不可达"）：
        // 关闭时词库点击整体拒绝——正常路径由主菜单按钮隐藏 + /le word 命令门覆盖，
        // 本处是僵尸 GUI（关开关前已打开）与边缘入口的兜底防线（S-F 流已记录
        // "入口层门由 S-G 落盘"）。空白/填充格点击仍零行为（上方范围检查先行）。
        if (!plugin.getConfigManager().isWordSystemEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "module.disabled"));
            return;
        }
        // R-53 fan-in：与 GUI 同序（getAvailableDifficulties 的 TreeSet 快照）、同截断。
        List<String> banks = new ArrayList<>(plugin.getWordManager().getAvailableDifficulties());
        boolean hasMore = banks.size() > WordBankSelectGUI.BANK_SLOT_COUNT;
        int moreSlot = WordBankSelectGUI.BANK_SLOT_START + WordBankSelectGUI.BANK_SLOT_COUNT - 1;
        // R-49/R-53：尾槽"更多词库…"提示点击当 no-op（仅播音），不反算打开被隐藏的词库
        if (hasMore && slot == moreSlot) {
            SoundManager.playSound(player, "menu-click");
            return;
        }
        int shownCount = hasMore ? WordBankSelectGUI.BANK_SLOT_COUNT - 1 : banks.size();
        int index = slot - WordBankSelectGUI.BANK_SLOT_START;
        if (index < 0 || index >= shownCount) return;
        SoundManager.playSound(player, "menu-click");
        String difficulty = banks.get(index);
        // R-66 反向（P2 编排者 fan-in）：AI 对话进行中不开新答题（与 /le word 入口对称守卫）。
        if (plugin.getAIChatManager().hasActiveConversation(player)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.chat-active"));
            return;
        }
        // MC-3（F2-05 收口）：默认模式从私有常量改为 WordModes.firstEnabledMode ——
        // Wave 2 裁决：默认模式解析优先 choice（FEAT-MODE-01）；choice 被关时按
        // spelling→fill_blank 回落；三模式全关时拒绝。
        // （word.no-modes-enabled），不再回落到可能已关闭的 choice。
        // 顺序对齐 /le word 命令路径（模式门先于引导接管）：全关时不落进 step==1 教程。
        String mode = WordModes.firstEnabledMode(plugin.getConfigManager());
        if (mode == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.no-modes-enabled"));
            return;
        }
        // 域 1 新手引导拦截：step==1 的"首次答题"改开 3 词迷你会话（接管开局则直接返回）。
        if (plugin.getOnboardingManager().interceptQuizStart(player, difficulty)) {
            return;
        }
        var session = plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
        // Wave 1 fan-in（S-F 上报纵深）：word 门关闭时 startQuiz 返回 null 已发
        // module.disabled——此处不再开 GUI / 发 session-start（上方兜底门之外的第二道防线）。
        if (session == null) {
            return;
        }
        new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
        // PF-ONB-03（Phase 3 规划期发现，编排者即修）：F2-13 只修了命令路径，词库选择
        // 这条新手主路径此前仍把英文标识符（beginner/choice）透传给中文玩家；
        // MC-3（PF-MC-11 收口）：本地化改走 WordModes 单一实现（本类私有副本删除）。
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "word.session-start",
                "difficulty", WordModes.localizeDifficulty(plugin.getMessageUtil(), player, difficulty),
                "mode", WordModes.localizeMode(plugin.getMessageUtil(), player, mode)));
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
        // MC-2 模块门：daily-challenges.enabled=false 时领取分支整体拒绝
        // （僵尸 GUI 的迟到点击不进任何写路径；点读式门，/lea reload 即时生效）。
        if (!plugin.getConfigManager().isDailyChallengesEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "module.disabled"));
            return;
        }
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
            return;
        }
        // Claim buttons：槽位查位映射（MC-5/F1-12）——与 DailyChallengeGUI.CHALLENGE_SLOTS
        // 共用同一份槽位真相（WrongBook 模式），不再手写 (slot-11)/2 魔法反算。
        // 索引语义与渲染侧一致：列表第 i 项渲染在 CHALLENGE_SLOTS[i]。
        int idx = Arrays.binarySearch(DailyChallengeGUI.CHALLENGE_SLOTS, slot);
        if (idx >= 0) {
            SoundManager.playSound(player, "menu-click");
            // R-02: run the whole claim under the player's stripe lock so two
            // rapid clicks of the same button can never interleave a double pay.
            synchronized (getClaimStripeLock(player.getUniqueId())) {
                claimBehavioralChallenge(player, idx);
            }
        }
    }

    /**
     * R-02: behavioral-challenge claim body. MUST be called with the player's
     * stripe lock held: state is re-read inside the lock and the full
     * "check → deposit → mark" sequence stays serialized, so money is paid at
     * most once for a challenge in any timing. M12 preserved: a failed
     * deposit leaves the reward unmarked and re-claimable.
     *
     * @param player the clicking player
     * @param idx index into today's challenge list (0..CHALLENGE_SLOTS.length-1)
     */
    private void claimBehavioralChallenge(Player player, int idx) {
        var challenges = plugin.getDailyChallengeManager().getTodayChallenges(player.getUniqueId());
        if (idx < 0 || idx >= challenges.size()) return;
        var cp = challenges.get(idx);
        if (!cp.completed || cp.claimed) return;
        // M12: deposit-then-mark. Transfer first, mark claimed only
        // after money arrived. On failure the reward stays claimable
        // and the GUI is refreshed unclaimed for retry.
        // MC-5：奖励金额走 DailyChallengeManager.getChallengeReward（唯一读取口，
        // 读 daily-challenges.rewards.<id>，缺键/非法/≤0 回落枚举默认），
        // 直读 cp.type.reward 已废除（PF-MC-09）。
        double reward = plugin.getDailyChallengeManager().getChallengeReward(cp.type);
        // PF-MC-01（Phase 3 规划期发现，编排者即修）：行为挑战领取此前直接走
        // depositCappedReward，未过 money.enabled 门 —— 服主关币（默认 false）且
        // 装了 Vault 时仍会实发金币，与签到/单词挑战的 economy-disabled 语义不一致
        // （F1-11/R-44 的残留缺口）。关闭时终态处理：标记已领（防按钮永远可点、
        // 重复提示）+ 一次 economy-disabled 提示，与单词挑战侧同口径。
        // Wave 1 fan-in（S-H 上报收口）：判定改走 RewardEngine 公开并集
        // （isRewardsEnabled() && money.enabled）——原先只读 money 键，关奖励伞时漏判。
        boolean moneyEnabled = plugin.getProgressManager().getRewardEngine().isMoneyEnabled();
        if (!moneyEnabled) {
            plugin.getDailyChallengeManager().claimReward(player.getUniqueId(), cp.type);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.economy-disabled"));
            new DailyChallengeGUI(plugin).open(player);
            return;
        }
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

    /**
     * R-02: stripe-lock lookup. Fixed-size array, so there is nothing to
     * evict — unlike a per-player lock map which would leak without cleanup.
     *
     * @param playerId the player
     * @return the stripe monitor for this player
     */
    private Object getClaimStripeLock(UUID playerId) {
        return claimStripeLocks[(playerId.hashCode() & 0x7fffffff) % claimStripeLocks.length];
    }

    /**
     * R-34/F1-23: route the behavioral-challenge payout through
     * {@link com.letslearnenglish.minecraftplugin.core.progress.RewardEngine#depositCappedReward}
     * so it obeys {@code money.daily-cap} and updates the today-coins ledger, and so coin
     * stats are recorded inside the reward layer — this listener no longer calls Vault or
     * {@code DatabaseManager.recordCoinsEarned} directly. Returns the actual amount paid;
     * {@code >0} means money arrived (caller marks claimed), {@code 0} on no-Vault / cap /
     * failure keeps the reward unclaimed and re-claimable (M12 preserved).
     *
     * @param player the player
     * @param reward the reward amount
     * @return true only if money actually arrived (paid &gt; 0)
     */
    private boolean depositChallengeReward(Player player, double reward) {
        var eco = plugin.getEconomy();
        if (eco == null) {
            plugin.getLogger().warning("Daily challenge reward of " + reward
                    + " for " + player.getName() + " not paid: Vault economy unavailable");
            return false;
        }
        double paid = plugin.getProgressManager().getRewardEngine().depositCappedReward(player, reward);
        return paid > 0;
    }

    private void handleChallengeComplete(Player player, int slot) {
        if (slot == 8 || slot == 22) {
            SoundManager.playSound(player, "menu-click");
            // F1-09: slot 22 is the "reward already arrived, back to menu"
            // button (NOT a claim action — payout happened before this page
            // opened).
            // R-55: 庆祝页返回改走 popBack（有栈回栈、无栈回主菜单），
            // 与 DailyChallenge 的 slot 8 返回语义一致，不再绕栈直开主菜单。
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
        }
    }

    /**
     * R-55e: 拖拽 guard（抄 DuelGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 本监听器辖下各 GUI（隐私设置/词库选择/过往学习/每日挑战/庆祝页）
     * 的展示物品可被拖进玩家背包。
     *
     * 标题白名单与 onInventoryClick 入口保持一致；只在拖拽触及界面
     * 上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title == null) return;

        if (!title.contains("Privacy Settings") && !title.contains("隐私设置")
                && !title.contains("Select Word Bank") && !title.contains("选择词库")
                && !title.contains("过往学习") && !title.contains("Study History")
                && !title.contains("Daily Challenge") && !title.contains("每日挑战")
                && !title.contains("Challenge Complete") && !title.contains("挑战完成")) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
