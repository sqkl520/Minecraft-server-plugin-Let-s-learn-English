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
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Listener for Privacy Settings, Word Bank Select, and Learning Calendar GUI interactions.
 */
public class MiscGUIListener implements Listener {

    private final LetsLearnEnglish plugin;

    /** R8-L4：本监听器辖下 5 类 GUI 的标题配置键（判定与渲染同源）。 */
    private static final String[] PRIVACY_TITLE_KEYS = { "profile.settings-title" };
    private static final String[] WORD_BANK_TITLE_KEYS = { "word-bank.title" };
    /** 日历有两个标题（本人 / 查看他人）——viewing 变体含 {player} 占位符。 */
    private static final String[] CALENDAR_TITLE_KEYS = { "calendar.title", "calendar.viewing-title" };
    private static final String[] DAILY_TITLE_KEYS = { "daily.title" };
    private static final String[] COMPLETE_TITLE_KEYS = { "daily-challenge.complete-title" };
    /** 占位符模板做前缀锚定时的可见长度下限（防"占位符在开头"的退化配置过宽误配）。 */
    private static final int MIN_ANCHOR_MATCH = 4;

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
        // ── R5-3 判定顺序（四条铁律，顺序不可调整）────────────────────────────
        // 1) 先判窗外：getClickedInventory() 只在点到界面之外时为 null。
        if (event.getClickedInventory() == null) return;

        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        // ── R5-7 前置守卫：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）与双击收集
        //（COLLECT_TO_CURSOR）必须放在下面的 rawSlot 越界 return <b>之前</b>——这两类
        // action 的 rawSlot 落在下半区，否则会被那个 return 吞掉、永不执行。
        //
        // 为什么必须拦：原版会为这两类 action 在<b>整个 view</b> 里找落点，包括上半区。
        // 上半区任何空格都会成为落点，物品塞进去后本类的「上半区点击一律 cancel」
        // 又会把它锁死（玩家点不出来、shift-click 也回不去，关界面即丢）。COLLECT_TO_CURSOR
        // 还会从整个 view（含上半区展示物）收集同材质物品，把提示物扫进光标。
        //
        // 裁决：对<b>全部 5 个 key</b> 生效（不是只对 PROFILE_SETTINGS）。理由：
        //   - 破坏性与「这一页当前有没有空槽」无关，只与「原版有没有落点」有关。当前
        //     PROFILE_SETTINGS 的 slot 10/12 是空槽（本次已在 ProfileGUI 侧用 fillEmpty
        //     补上），但其余四页的满填充是靠各 GUI 自己逐个 setItem 维持的、没有任何
        //     结构性保证，未来布局改动随时可能漏格；按 key 列白名单等于把「今天恰好没
        //     空格」写成不变量，而 shift-click 对任何上半区都是纯破坏操作（本类没有任何
        //     合法的「把物品移进上半区」语义），全拦没有功能损失。
        //   - 普通下半区点击仍然一步不拦（R5-3 铁律 2 的「玩家能整理背包」语义不变）。
        // 归属判定仍先走 resolveOwnedView：非本插件界面（别的插件的 GUI）绝不被误 cancel。
        if (rawSlot >= topSize
                && (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || event.getAction() == InventoryAction.COLLECT_TO_CURSOR)) {
            if (resolveOwnedView(event.getView()) != null) {
                event.setCancelled(true);
            }
            return;
        }

        // 2) 再判下半区（玩家背包）：rawSlot 落在 top 之外一律不处理、也不 cancel，
        //    玩家开着 GUI 仍能正常整理背包。
        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 3) 然后才做身份判定：holder 优先（结构身份），未命中才回落标题（迁移期兜底）。
        GuiViewKey key = resolveOwnedView(event.getView());
        if (key == null) return;

        // R5-3 切换点插桩：holder 与标题说法不一致时暴露漂移（去重 WARNING），不改变行为。
        GuiIdentity.logMismatch(plugin, event.getView());

        // 4) 只有「本类辖管界面的上半区点击」才 cancel —— 绝不允许只判 holder 就 cancel
        //    （event.getInventory() 恒为上半区，与玩家实际点击的位置无关）。
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        switch (key) {
            case PROFILE_SETTINGS -> handlePrivacySettings(player, slot);
            case WORD_BANK -> handleWordBankSelect(player, slot);
            case LEARNING_CALENDAR -> handleLearningCalendar(player, slot);
            case DAILY_CHALLENGE -> handleDailyChallenge(player, slot);
            case CHALLENGE_COMPLETE -> handleChallengeComplete(player, slot);
            // 不可达：resolveOwnedView 只返回以上五个 key（其余一律 null）。
            default -> {
            }
        }
    }

    /**
     * R5-3：解析「本监听器负责的界面」的<b>点击</b>归属 —— holder 优先、标题兜底。
     *
     * <p><b>关于 PROFILE_SETTINGS 的让路（重要，实机核查后的裁决）</b>：
     * 本类<b>继续</b>处理隐私设置页，<b>不</b>在 holder 命中
     * {@link GuiViewKey#PROFILE_SETTINGS} 时让路。理由是可核实的事实链：
     * <ol>
     *   <li>{@code ProfileGUI#openSettings} 已经挂上 {@code PROFILE_SETTINGS} holder
     *       （R5-4 流已完成 GUI 侧接线）；</li>
     *   <li>但 {@code SocialListener#resolveSocialKey} 把 {@code PROFILE_SETTINGS}
     *       显式排除在外（{@code default -> null}），其 switch 也没有该分支；</li>
     *   <li>全仓再<b>没有第三个</b> listener 处理该页点击。</li>
     * </ol>
     * 也就是说：此刻若「holder 命中 PROFILE_SETTINGS 就 return」，隐私设置页的
     * 五个开关与返回键会<b>全部变成死键</b>（点击被 cancel、却无人处理）。
     * 因此让路被判定为<b>不可实施</b>，本类保留属主。
     *
     * <p><b>R5-4 移交清单</b>（届时由 R5-4 流一次性完成，不可只做一半）：
     * 在 {@code SocialListener} 加上 {@code case PROFILE_SETTINGS} 并接管
     * {@link #handlePrivacySettings} 的槽位语义；<b>同时</b>从本方法的
     * {@link #isTrackedKey} 与 switch 中删除 PROFILE_SETTINGS。两件事必须同一个提交，
     * 否则要么死键（只删本类）、要么双触发（只加对方，开关是 toggle 会点一次切两次）。
     * 本处保留标题兜底 {@link #resolveByTitleKey}（{@code profile.settings-title}
     * 等值命中），因此即使 GUI 侧 holder 存在，判定也照样稳定。
     *
     * @param view 事件界面，可为 null
     * @return 本类辖管的 key；不归本类管时返回 null
     */
    private GuiViewKey resolveOwnedView(InventoryView view) {
        GuiViewKey byHolder = GuiIdentity.resolve(view);
        if (byHolder != null) {
            // 结构身份压倒文案：holder 说了算，不再用标题翻案。
            return isTrackedKey(byHolder) ? byHolder : null;
        }
        // ── 迁移期兜底（holder 未命中：尚未挂 holder 的旧界面）──
        return resolveByTitleKey(view);
    }

    /**
     * R5-3：解析「本监听器负责的界面」的<b>拖拽</b>归属 —— holder 优先、标题兜底。
     *
     * <p>与 {@link #resolveOwnedView(InventoryView)} 判定口径<b>完全一致</b>（同一个
     * {@link #isTrackedKey}），区别只在返回值形态：拖拽守卫只需 boolean。
     *
     * <p>标题兜底直接复用 {@link #matchesTrackedTitle}（旧布尔并集，原样保留），
     * 保证拖拽路径在迁移期与改造前逐字等价。
     *
     * @param view 事件界面，可为 null
     * @return true 表示本类应守卫该界面
     */
    private boolean isTrackedView(InventoryView view) {
        GuiViewKey byHolder = GuiIdentity.resolve(view);
        if (byHolder != null) {
            return isTrackedKey(byHolder);
        }
        // ── 迁移期兜底（holder 未命中）──
        String title = view == null ? null : view.getTitle();
        return title != null && matchesTrackedTitle(title);
    }

    /**
     * R5-3：本监听器辖下的五类屏幕。
     *
     * <p>四类本轮已挂 holder（{@link GuiViewKey#WORD_BANK} /
     * {@link GuiViewKey#LEARNING_CALENDAR} / {@link GuiViewKey#DAILY_CHALLENGE} /
     * {@link GuiViewKey#CHALLENGE_COMPLETE}）；{@link GuiViewKey#PROFILE_SETTINGS}
     * 虽已由 {@code ProfileGUI} 挂上 holder，但点击仍归本类处理 —— 移交条件与
     * 顺序要求见 {@link #resolveOwnedView(InventoryView)} 的 Javadoc。
     */
    private static boolean isTrackedKey(GuiViewKey key) {
        return key == GuiViewKey.PROFILE_SETTINGS
                || key == GuiViewKey.WORD_BANK
                || key == GuiViewKey.LEARNING_CALENDAR
                || key == GuiViewKey.DAILY_CHALLENGE
                || key == GuiViewKey.CHALLENGE_COMPLETE;
    }

    /**
     * R5-3：标题兜底 —— 把标题映射回身份键，优先级与本方法改造前的 if/else 链
     * <b>逐字一致</b>（privacy &gt; wordBank &gt; calendar &gt; daily &gt; complete），
     * 保证迁移期行为不变。
     *
     * <p>底层仍走 {@link #matchesAnyTitle}（i18n 键等值 + 占位符前缀锚定），本方法
     * 只负责把「命中哪一类」翻译成 key；两个旧方法原样保留到 Wave 3 收尾。
     *
     * @param view 事件界面，可为 null
     * @return 命中的 key；未命中时返回 null
     */
    private GuiViewKey resolveByTitleKey(InventoryView view) {
        String title = view == null ? null : view.getTitle();
        if (title == null) return null;
        // 与改造前 onInventoryClick 的布尔链同序：隐私页最先，庆祝页最后。
        if (matchesAnyTitle(title, PRIVACY_TITLE_KEYS)) return GuiViewKey.PROFILE_SETTINGS;
        if (matchesAnyTitle(title, WORD_BANK_TITLE_KEYS)) return GuiViewKey.WORD_BANK;
        if (matchesAnyTitle(title, CALENDAR_TITLE_KEYS)) return GuiViewKey.LEARNING_CALENDAR;
        if (matchesAnyTitle(title, DAILY_TITLE_KEYS)) return GuiViewKey.DAILY_CHALLENGE;
        if (matchesAnyTitle(title, COMPLETE_TITLE_KEYS)) return GuiViewKey.CHALLENGE_COMPLETE;
        return null;
    }

    /**
     * R8-L4：标题归属判定 —— 与各 GUI 开菜单侧同源：按 zh/en 读标题配置键，经与
     * 渲染侧相同的 {@code &} 颜色码转换后做等值比对（照 {@code ConfirmationGUI.isConfirmGUI}
     * 先例），替代原裸子串白名单（过宽：任何未来标题含这些词都会被本监听器吞掉点击）。
     *
     * <p>开菜单侧截断核查（逐类读 createInventory 的标题构造）：
     * profile.settings-title / word-bank.title / daily.title / daily-challenge.complete-title
     * 均为「纯 translate → createInventory」，无截断无占位符 → 等值安全；
     * calendar.viewing-title（他人日历）含 {@code {player}} 占位符，替换后标题与模板
     * 不等（等值必不中）→ 对该类按「占位符前的字面前缀」锚定（照 MainMenuListener:90-104
     * 的截断宽松比对先例），锚（剥色后）至少 {@link #MIN_ANCHOR_MATCH} 个可见字符
     * 防退化配置过宽误配。
     */
    private boolean matchesAnyTitle(String title, String... keys) {
        if (title == null || title.isEmpty()) return false;
        for (String lang : new String[] { "zh", "en" }) {
            FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
            for (String key : keys) {
                String raw = MessageUtil.getGuiMsg(messages, key);
                String expected = ChatColor.translateAlternateColorCodes('&', raw);
                if (title.equals(expected)) return true;
                int placeholderAt = raw.indexOf('{');
                if (placeholderAt > 0) {
                    String anchor = ChatColor.translateAlternateColorCodes('&',
                            raw.substring(0, placeholderAt));
                    if (ChatColor.stripColor(anchor).length() >= MIN_ANCHOR_MATCH
                            && title.startsWith(anchor)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        } else if (slot == 11 || slot == 13 || slot == 14 || slot == 15 || slot == 16) {
            SoundManager.playSound(player, "menu-click");
            PlayerDataStore store = plugin.getPlayerDataStore();
            PlayerDataStore.ProfileSettings ps = store.getProfileSettings(player.getUniqueId());
            switch (slot) {
                case 11 -> ps.setShowAnswers(!ps.isShowAnswers());
                case 13 -> ps.setShowResults(!ps.isShowResults());
                // R1/R4：提醒总开关（发送侧门由编排者接线：LetsLearnEnglish 三处 + ReviewScheduler）
                case 14 -> ps.setRemindersEnabled(!ps.isRemindersEnabled());
                case 15 -> ps.setShowInSquare(!ps.isShowInSquare());
                // Wave 1 3A-4 补建：per-player 音效开关（SoundManager.isPlayerSoundEnabled 门已接线）
                case 16 -> ps.setSoundsEnabled(!ps.isSoundsEnabled());
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
        // C1-N1 收口（R2-M4 同族，存量守卫缺口）：决斗中不开新答题 —— 决斗分支
        // 优先吞全部聊天（ChatAnswerListener），答题输入静默超时判错。守卫先于
        // 对话判，与 /le dialogue 命令入口的守卫顺序一致。
        var duelManager = plugin.getDuelManager();
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.finish-duel-first"));
            return;
        }
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
        try {
            var session = plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
            // Wave 1 fan-in（S-F 上报纵深）：word 门关闭时 startQuiz 返回 null 已发
            // module.disabled——此处不再开 GUI / 发 session-start（上方兜底门之外的第二道防线）。
            // R1-M1/M2：session-in-progress / practice-in-progress 的 null 同样已由
            // 守卫内发提示，此处一并静默 return。
            if (session == null) {
                return;
            }
        } catch (IllegalStateException e) {
            // R11-M2（含 R8-L2 合并）：空词池窗口 selectWordsForSession 抛 ISE 原先冒泡
            // 出事件处理器——玩家零反馈且困在原界面。逐字对齐命令路径
            // （LearnCommand.startWordQuiz 的 catch）：界面不关，玩家可改点其它词库。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.no-words-available"));
            return;
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message", e.getMessage()));
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
            new DailyChallengeGUI(plugin).openAsync(player);
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
        new DailyChallengeGUI(plugin).openAsync(player);
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
     * R5-3：入口判定改为 holder 优先（{@link #isTrackedView}），标题兜底保留。
     */
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView() == null) return;

        // R5-3：与 onInventoryClick 同一套 holder 优先判定（共用 isTrackedKey）。
        // 标题兜底保留（matchesTrackedTitle），迁移期行为不变。
        if (!isTrackedView(event.getView())) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * R8-L4：任意一类被本监听器辖管的 GUI 标题（拖拽守卫用，布尔并集）。
     *
     * <p>R5-3：保留为<b>迁移期 fallback</b>（{@link #isTrackedView} 在 holder 未命中时
     * 调用），Wave 3 收尾时才与 {@link #matchesAnyTitle} 同批删除。
     */
    private boolean matchesTrackedTitle(String title) {
        return matchesAnyTitle(title, PRIVACY_TITLE_KEYS)
                || matchesAnyTitle(title, WORD_BANK_TITLE_KEYS)
                || matchesAnyTitle(title, CALENDAR_TITLE_KEYS)
                || matchesAnyTitle(title, DAILY_TITLE_KEYS)
                || matchesAnyTitle(title, COMPLETE_TITLE_KEYS);
    }
}
