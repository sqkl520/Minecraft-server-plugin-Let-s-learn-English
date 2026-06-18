package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import org.bukkit.ChatColor;
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
        List<String> scenarios = plugin.getAIChatManager().getAvailableScenarios();
        int index = slot - 10;
        if (index >= 0 && index < scenarios.size()) {
            if (!plugin.getAIChatManager().isAvailable()) {
                player.closeInventory();
                String lang = plugin.getPlayerLanguage(player);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "zh".equalsIgnoreCase(lang)
                        ? "&c[AI] API 密钥未配置，AI 对话不可用。请联系管理员配置。"
                        : "&c[AI] API key not configured. AI chat is unavailable. Contact admin."));
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
            String scenarioName = formatScenarioName(scenario, lang);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    zh
                        ? "&a[英语学习] &eAI 情景对话已开启！&b场景：&f" + scenarioName + " &e难度：&f" + diffName
                        : "&a[LearnEnglish] &eAI Scenario started! &bScenario: &f" + scenarioName + " &eDifficulty: &f" + diffName));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    zh
                        ? "&7在聊天栏输入任意文字与 AI 对话，输入 &f& &7+ 消息与其他玩家聊天（例：&f& Hello!&7）"
                        : "&7Type anything to chat with AI, use &f& &7+ message to talk to other players (e.g. &f& Hello!&7)"));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    zh
                        ? "&7输入 &f再见 &7或 &fbye &7退出对话"
                        : "&7Type &fbye &7to exit"));

            // Let AI start the conversation with an opening line
            plugin.getAIChatManager().triggerAiOpening(player, (aiResponse, errorMsg) -> {
                if (aiResponse != null) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&b[English AI] &f" + aiResponse));
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
        String lang = plugin.getPlayerLanguage(player);
        boolean zh = "zh".equalsIgnoreCase(lang);
        if (plugin.getAIChatManager().isAvailable()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    zh ? "&a[AI] API 密钥已重新加载，AI 对话可用！" : "&a[AI] API key reloaded. AI chat is available!"));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    zh ? "&c[AI] API 密钥未配置或无效，AI 对话不可用。" : "&c[AI] API key not configured / invalid. AI chat unavailable."));
        }
    }

    private String formatScenarioName(String key, String lang) {
        boolean zh = "zh".equalsIgnoreCase(lang);
        return switch (key) {
            case "airport" -> zh ? "机场" : "Airport";
            case "restaurant" -> zh ? "餐厅" : "Restaurant";
            case "shopping" -> zh ? "购物" : "Shopping";
            case "hotel" -> zh ? "酒店" : "Hotel";
            case "hospital" -> zh ? "医院" : "Hospital";
            case "free_talk" -> zh ? "自由对话" : "Free Talk";
            default -> key;
        };
    }
}
