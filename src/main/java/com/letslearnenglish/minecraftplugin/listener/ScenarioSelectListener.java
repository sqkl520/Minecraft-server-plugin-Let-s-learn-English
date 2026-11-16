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
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;

import java.util.List;
/**
 * Listener for Scenario Select, Difficulty Select, and Reload API GUI interactions.
 *
 * <p>R5-11：界面识别<b>只认 holder</b> —— {@link ScenarioSelectGUI#open} 打开时已挂
 * {@link com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder}
 * （{@link GuiViewKey#SCENARIO_SELECT}）。原标题 {@code contains} 兜底已删除。
 */
public class ScenarioSelectListener implements Listener {

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

        // FIX-C2：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）与双击收集
        //（COLLECT_TO_CURSOR）的前置例外，照 SocialListener / MiscGUIListener 同式。
        //
        // 位置铁律：必须在下面 rawSlot 越界 return 之前 —— 这两类 action 的 rawSlot 落在
        // 下半区（玩家背包），一旦让越界 return 先吃掉就永远走不到本分支。
        //
        // 为什么必需：MOVE_TO_OTHER_INVENTORY 会把玩家背包物品合并进上半区同类堆叠
        //（不依赖空槽）；COLLECT_TO_CURSOR 会把上半区展示物扫进光标（反向失窃）。
        // 本页虽靠自铺满维持无空槽，但合堆与收集两条路径都不经过空槽。
        //
        // 为什么不含 PICKUP_ALL：Paper 1.21.4 把「光标空着、左键拾取整叠」映射为
        // PICKUP_ALL（点玩家自己背包的格子也走这个），拦它等于把「开着 GUI 整理背包」
        // 一并吞掉，与 R-01 语义冲突；双击收集的落点是 COLLECT_TO_CURSOR，已在本守卫内。
        // 归属判定必须写在这里（本类的 holder 判定在第 3 步、位于越界 return 之后）。
        if (rawSlot >= topSize
                && (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || event.getAction() == InventoryAction.COLLECT_TO_CURSOR)) {
            if (isScenarioSelectView(event.getView())) {
                event.setCancelled(true);
            }
            return;
        }

        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 3) 然后才做身份判定：只认 holder（结构身份）。
        if (!isScenarioSelectView(event.getView())) return;

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
     * 身份判定与 {@link #onInventoryClick} 入口同源（R5-11：只认 holder）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!isScenarioSelectView(event.getView())) return;

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
     * R5-11：解析「本监听器负责的界面」—— <b>只认 holder</b>。
     *
     * <p>holder 命中 {@link GuiViewKey#SCENARIO_SELECT} 即为本页；其余（含 {@code null}）
     * 一律不是。原标题 {@code contains} 兜底（含已删除的死串 {@code "情景对话"}）已删。
     *
     * @param view 事件界面，可为 null
     * @return true 表示本页属于场景选择页
     */
    private boolean isScenarioSelectView(InventoryView view) {
        return GuiIdentity.resolve(view) == GuiViewKey.SCENARIO_SELECT;
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

    /**
     * 「刷新 API 密钥」按钮（slot 26）的执行尾。
     *
     * <p><b>权限门是 {@code letslearnenglish.admin}，不是 {@code scenario.import}</b>。
     * 理由是这个按钮的动作是 {@link AIChatManager#reload()} —— 全局重读
     * {@code ai_config.yml}（API Key / 模型 / 超时 / 每日轮次上限 …）+ {@code ai_scenarios.yml}
     * （全部场景与难度）。命令侧最接近的等价物 {@code /lea reload} 要求 {@code admin}，
     * 而 {@code scenario.import} 的语义是「导入分享码 / 管理自己的个人场景」（会被下发给
     * 老师、内容作者），不该顺带授予「让全局 AI 配置从磁盘重载」的能力。
     *
     * <p><b>R-55m 沿革与本次回退</b>：R-55m 曾把这里从 {@code admin} 放宽到
     * {@code scenario.import}，理由是「与 /le load_scenario 对齐」。但两者动作不同量级——
     * 导入分享码写的是<b>自己</b>的个人场景，本方法写的是<b>全服</b>的 AI 运行时配置，
     * 且 {@code AIChatManager.loadScenariosConfig()} 内的
     * {@code maybeUpgradeScenariosFile} 在旧版出厂文件上会 {@code saveResource(..., true)}
     * <b>覆盖磁盘文件</b>。对齐的应该是「动作」，不是「按钮所在页面」。
     *
     * <p><b>这是行为变更</b>：见 {@link ScenarioSelectGUI#RELOAD_API_PERMISSION} 的注释与
     * DEPLOYMENT_WIKI 的升级说明。
     *
     * <p>节点名写字面量（不用常量）：{@code PermissionContractLintTest} 只认字面量，
     * 常量间接会让「代码查了哪个节点」静态不可知；两处门的一致性由
     * {@code ScenarioSelectReloadPermissionTest} 的行为用例钉住。
     *
     * @param player 点击者
     */
    private void handleReloadAPI(Player player) {
        if (!player.hasPermission("letslearnenglish.ai.reload")) {
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
