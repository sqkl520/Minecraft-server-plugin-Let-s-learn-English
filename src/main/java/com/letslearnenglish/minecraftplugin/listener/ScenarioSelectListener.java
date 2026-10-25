package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.PersonalScenarioGUI;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;

import java.util.List;
/**
 * Listener for Scenario Select, Difficulty Select, and Reload API GUI interactions.
 *
 * <p>R5-7：界面识别改为 holder 优先、标题兜底 —— {@link ScenarioSelectGUI#open} 打开时已挂
 * {@link com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder}，标题的
 * {@code contains} 判定只在 holder 缺失时作为迁移期 fallback。
 */
public class ScenarioSelectListener implements Listener {

    /**
     * 迁移期标题兜底关键词（场景选择页 {@code dialogue.scenario-title}）。
     *
     * <p>R5-7 顺手清理：原第三条 {@code "情景对话"} 是已删除的旧 DialogueGUI 的标题残留
     * （见 {@code GUIUtils} 的 "历史死启发式已移除" 注释），全仓无任何界面以此命名 ——
     * 留着只会让未来的同名界面被本监听器误认领，故随 holder 化一并删除。
     */
    private static final String[] SCENARIO_SELECT_TITLE_MARKERS = {
            "Scenario Select", "场景选择"
    };

    private final LetsLearnEnglish plugin;

    public ScenarioSelectListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // ── R5-7 判定顺序（顺序不可调整）────────────────────────────────────
        // 1) 先判窗外：getClickedInventory() 只在点到界面之外时为 null。
        if (event.getClickedInventory() == null) return;

        // 2) 再判下半区（玩家背包）：rawSlot 落在 top 之外一律不处理、也不 cancel，
        //    玩家开着 GUI 仍能正常整理背包（R-01 语义原样保留）。
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 3) 然后才做身份判定：holder 优先（结构身份），未命中才回落标题（迁移期兜底）。
        if (!isScenarioSelectView(event.getView())) return;

        // R5-7 切换点插桩：holder 与标题说法不一致时暴露漂移（去重 WARNING），不改变行为。
        GuiIdentity.logMismatch(plugin, event.getView());

        // 4) 只有「本页上半区点击」才 cancel —— 绝不允许只判 holder 就 cancel
        //    （event.getInventory() 恒为上半区，与玩家实际点击的位置无关）。
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleScenarioSelect(player, event.getSlot());
    }

    /**
     * T0-01 残留（P2 fan-in 补装）：拖拽是独立事件，点击的 setCancelled(true) 拦不住它 ——
     * 不拦的话场景选择 GUI 的展示物品（场景图标/难度/玻璃板）可被拖进玩家背包。
     * 身份判定与 {@link #onInventoryClick} 入口同源（R5-7：holder 优先、标题兜底）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!isScenarioSelectView(event.getView())) return;

        GuiIdentity.logMismatch(plugin, event.getView());

        // 身份判定不蕴含「拖的是上半区」，rawSlot < topSize 的判断必须保留。
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * R5-7：解析「本监听器负责的界面」—— holder 优先、标题兜底。
     *
     * <p>holder 已答出别的屏幕时<b>不再</b>用标题翻案（结构身份压倒文案），避免个人场景
     * 管理页等 54 格界面因标题含相同子串被本类吞掉点击。holder 缺失（旧界面/旧事件路径）
     * 时才回落标题，且兜底判定与改造前的 {@code contains} 语义逐字一致（已剔除死串）。
     *
     * @param view 事件界面，可为 null
     * @return true 表示本页属于场景选择页
     */
    private boolean isScenarioSelectView(InventoryView view) {
        GuiViewKey byHolder = GuiIdentity.resolve(view);
        if (byHolder != null) {
            return byHolder == GuiViewKey.SCENARIO_SELECT;
        }
        String title = view == null ? null : view.getTitle();
        if (title == null) return false;
        for (String marker : SCENARIO_SELECT_TITLE_MARKERS) {
            if (title.contains(marker)) return true;
        }
        return false;
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
