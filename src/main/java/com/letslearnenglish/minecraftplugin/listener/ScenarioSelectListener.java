package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
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

    private void handleScenarioSelect(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }
        // R-04：与 GUI 渲染同序 —— 按玩家版场景列表（全局 + 该玩家个人场景）。
        List<String> scenarios = plugin.getAIChatManager().getAvailableScenarios(player);
        int index = slot - 10;
        if (index >= 0 && index < scenarios.size()) {
            if (!plugin.getAIChatManager().isAvailable()) {
                player.closeInventory();
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "dialogue.ai-not-configured"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            String scenario = scenarios.get(index);
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
                if (aiResponse != null) {
                    String aiLabel = plugin.getMessageUtil().getPlayerMessage(player, "dialogue.ai-label");
                    player.sendMessage(aiLabel + aiResponse);
                }
            });
        } else if (slot == 26) {
            handleReloadAPI(player);
        } else if (slot >= 18 && slot <= 20) {
            handleDifficultySelect(player, slot);
        }
    }

    private void handleDifficultySelect(Player player, int slot) {
        int index = slot - 18;
        List<String> diffKeys = new ArrayList<>(plugin.getAIChatManager().getDifficulties().keySet());
        if (index >= 0 && index < diffKeys.size()) {
            String diffKey = diffKeys.get(index);
            plugin.getAIChatManager().setPlayerDifficulty(player, diffKey);
            SoundManager.playSound(player, "menu-click");
            new ScenarioSelectGUI(plugin).open(player);
        }
    }

    private void handleReloadAPI(Player player) {
        if (!player.hasPermission("letslearnenglish.admin")) return;
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
