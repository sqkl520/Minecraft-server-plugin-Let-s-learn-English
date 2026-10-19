package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.PersonalScenarioGUI;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
/**
 * Listener for Scenario Select, Difficulty Select, and Reload API GUI interactions.
 */
public class ScenarioSelectListener implements Listener {

    private final LetsLearnEnglish plugin;

    public ScenarioSelectListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Scenario Select") && !title.contains("场景选择")
                && !title.contains("情景对话")) {
            return;
        }

        // R-01 补漏：只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleScenarioSelect(player, event.getSlot());
    }

    /**
     * T0-01 残留（P2 fan-in 补装）：拖拽是独立事件，点击的 setCancelled(true) 拦不住它 ——
     * 不拦的话场景选择 GUI 的展示物品（场景图标/难度/玻璃板）可被拖进玩家背包。
     * 标题判定与 {@link #onInventoryClick} 入口一致；只在拖拽触及界面上半部分时取消，
     * 玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title == null) return;

        if (!title.contains("Scenario Select") && !title.contains("场景选择")
                && !title.contains("情景对话")) {
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

    private void handleScenarioSelect(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }
        if (slot == ScenarioSelectGUI.MANAGE_SLOT) {
            handleManage(player);
            return;
        }
        if (slot == ScenarioSelectGUI.RELOAD_API_SLOT) {
            handleReloadAPI(player);
            return;
        }
        // 难度槽（DIFFICULTY_SLOT_START .. +COUNT-1）
        if (slot >= ScenarioSelectGUI.DIFFICULTY_SLOT_START
                && slot < ScenarioSelectGUI.DIFFICULTY_SLOT_START + ScenarioSelectGUI.DIFFICULTY_SLOT_COUNT) {
            handleDifficultySelect(player, slot);
            return;
        }
        // 场景槽（SCENARIO_SLOT_START .. +COUNT-1）
        if (slot >= ScenarioSelectGUI.SCENARIO_SLOT_START
                && slot < ScenarioSelectGUI.SCENARIO_SLOT_START + ScenarioSelectGUI.SCENARIO_SLOT_COUNT) {
            handleScenarioClick(player, slot);
        }
    }

    /**
     * R-49 fan-in：场景按钮点击 —— 与 GUI 完全同序（共享 {@code AIChatManager.getSortedScenarioKeys}），
     * 并与 GUI 同样截断：超 SCENARIO_SLOT_COUNT 时尾槽是"更多…"提示 → no-op（R-49/R-53），
     * 绝不按下标反算打开被隐藏的场景。
     */
    private void handleScenarioClick(Player player, int slot) {
        List<String> sortedKeys = plugin.getAIChatManager().getSortedScenarioKeys(player);
        boolean hasMore = sortedKeys.size() > ScenarioSelectGUI.SCENARIO_SLOT_COUNT;
        int moreSlot = ScenarioSelectGUI.SCENARIO_SLOT_START
                + ScenarioSelectGUI.SCENARIO_SLOT_COUNT - 1;
        if (hasMore && slot == moreSlot) {
            SoundManager.playSound(player, "menu-click");
            return;
        }
        int shownCount = hasMore
                ? ScenarioSelectGUI.SCENARIO_SLOT_COUNT - 1
                : sortedKeys.size();
        int index = slot - ScenarioSelectGUI.SCENARIO_SLOT_START;
        if (index < 0 || index >= shownCount) return;

        if (!plugin.getAIChatManager().isAvailable()) {
            // P2-24 音效盲点收口（3A-5）：失败分支给一声失败音，与有效点击的
            // menu-click 区分开，玩家能"听见"这次点击没有生效。
            SoundManager.playSound(player, "incorrect");
            player.closeInventory();
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.ai-not-configured"));
            return;
        }
        // R2-M4：决斗优先判（与 /le dialogue 命令入口逐字对齐）。决斗中已开
        // 对话时的消息竞争由 ChatScenarioListener R-66 让路，但入口直接拒绝最干净。
        var duelManager = plugin.getDuelManager();
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.finish-duel-first"));
            return;
        }
        // R-66：答题优先 —— 答题/错题练习中不允许开启 AI 对话（否则对话静默挂起、
        // 聊天输入被两系统争抢）。条件与 /le dialogue 命令入口的答题分支一致。
        if (plugin.getWordLearningManager().hasActiveQuiz(player.getUniqueId())
                || plugin.getWordLearningManager().isPracticingWord(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.finish-quiz-first"));
            return;
        }
        SoundManager.playSound(player, "menu-click");
        String scenario = sortedKeys.get(index);
        String difficulty = plugin.getAIChatManager().getPlayerDifficulty(player);
        AIChatManager.DifficultyConfig dc = plugin.getAIChatManager().getDifficulties().get(difficulty);

        plugin.getChatScenarioListener().addPlayer(player);
        plugin.getAIChatManager().startConversation(player, scenario);
        player.closeInventory();

        String lang = plugin.getPlayerLanguage(player);
        boolean zh = "zh".equalsIgnoreCase(lang);
        String diffName = (dc != null) ? dc.getDisplayName(zh) : difficulty;
        String scenarioName = formatScenarioName(player, scenario);
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.started-with-difficulty",
                "scenario", scenarioName,
                "difficulty", diffName));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.chat-hint"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.exit-hint"));

        // Let AI start the conversation with an opening line
        plugin.getAIChatManager().triggerAiOpening(player, (aiResponse, errorMsg) -> {
            if (errorMsg != null) {
                // 开场白失败不再静默 —— 给玩家明确提示（tests: dialogue.request-failed 键已存在）
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "dialogue.request-failed"));
                return;
            }
            if (aiResponse != null) {
                String aiLabel = plugin.getMessageUtil().getPlayerMessage(player, "dialogue.ai-label");
                player.sendMessage(aiLabel + aiResponse);
            }
        });
    }

    private void handleDifficultySelect(Player player, int slot) {
        // R-49 fan-in：与 GUI 同序（共享 AIChatManager.getSortedDifficultyKeys），并同样截断：
        // 超 DIFFICULTY_SLOT_COUNT 时尾槽是"更多…"提示 → no-op，不反算切到隐藏难度。
        List<String> diffKeys = plugin.getAIChatManager().getSortedDifficultyKeys();
        boolean hasMore = diffKeys.size() > ScenarioSelectGUI.DIFFICULTY_SLOT_COUNT;
        int moreSlot = ScenarioSelectGUI.DIFFICULTY_SLOT_START
                + ScenarioSelectGUI.DIFFICULTY_SLOT_COUNT - 1;
        if (hasMore && slot == moreSlot) {
            SoundManager.playSound(player, "menu-click");
            return;
        }
        int shownCount = hasMore
                ? ScenarioSelectGUI.DIFFICULTY_SLOT_COUNT - 1
                : diffKeys.size();
        int index = slot - ScenarioSelectGUI.DIFFICULTY_SLOT_START;
        if (index < 0 || index >= shownCount) return;
        String diffKey = diffKeys.get(index);
        plugin.getAIChatManager().setPlayerDifficulty(player, diffKey);
        SoundManager.playSound(player, "menu-click");
        new ScenarioSelectGUI(plugin).open(player);
    }

    /**
     * Phase 3 · MC-4：进入个人场景管理页。
     * 与按钮可见门同口径纵深（无权限者点不到，但页面残留/权限撤换时兜底）；
     * pushBack 压「场景选择页」帧 —— 管理页返回时弹回本页（SocialListener 子页模式）。
     */
    private void handleManage(Player player) {
        if (!player.hasPermission("letslearnenglish.scenario.import")) {
            SoundManager.playSound(player, "incorrect");
            return;
        }
        SoundManager.playSound(player, "menu-click");
        plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                () -> new ScenarioSelectGUI(plugin).open(player));
        new PersonalScenarioGUI(plugin).open(player, null);
    }

    private void handleReloadAPI(Player player) {
        // R-55m：权限与 /le load_scenario 对齐（原 letslearnenglish.admin）
        if (!player.hasPermission("letslearnenglish.scenario.import")) {
            // P2-24 音效盲点收口（3A-5）：无权限分支与 AI 未配置分支同用失败音，
            // 与有效点击（menu-click）听觉可区分。
            SoundManager.playSound(player, "incorrect");
            return;
        }
        SoundManager.playSound(player, "menu-click");
        plugin.getAIChatManager().reload();
        player.closeInventory();
        String resultKey = plugin.getAIChatManager().isAvailable()
                ? "dialogue.api-reload-success"
                : "dialogue.api-reload-failed";
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, resultKey));
    }

    /**
     * 场景显示名统一走场景配置（display_name / display_name_zh），
     * R-04：按玩家解析 —— 个人 key 显示其展示名而非原始标识。
     * 未配置时兜底返回场景 key（标识符，非面向玩家的文案）。
     */
    private String formatScenarioName(Player player, String key) {
        return plugin.getAIChatManager().getScenarioDisplayName(player, key);
    }
}
