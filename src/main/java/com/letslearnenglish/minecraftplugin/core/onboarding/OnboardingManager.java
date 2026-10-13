package com.letslearnenglish.minecraftplugin.core.onboarding;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新手引导管理器（plan-onboarding §3.1，方法签名与键名为冻结基准）。
 *
 * <p>三步引导线状态机（持久化于 {@code player_data.onboarding_step}）：
 * <ul>
 *   <li>{@link #STEP_UNINITIALIZED}=0 未初始化（瞬时态；老号回填 3 / 首进写 1 的入口）</li>
 *   <li>{@link #STEP_IN_PROGRESS}=1 引导中（等首次答题 / 迷你会话进行中，二者共用一个值）</li>
 *   <li>{@link #STEP_SESSION_DONE}=2 迷你会话已完成（等打开个人主页）</li>
 *   <li>{@link #STEP_COMPLETE}=3 引导完成（终态，之后零打扰）</li>
 * </ul>
 *
 * <p>防重复：step 写统一走 {@link #advanceStep(UUID, int)}（单调，不回退）；
 * 拦截条件要求 step 恰为 1（老号回填失败也 fail-open 不误拦）；
 * 手册发放前扫主背包 + 副手 PDC 去重；进服提醒按时自然日去重。
 *
 * <p>线程模型：全部入口均为主线程（join 事件 / GUI 点击 / 交互事件）。
 * DB 读 = 每 join 一条小 SELECT；DB 写 = 生命周期内 ≤3 次小 UPSERT + 老号回填一次；
 * 无调度任务、无 Timer。全部缓存在 quit 时经 {@link #unloadPlayer(UUID)} 清理。
 */
public class OnboardingManager implements Listener {

    public static final int STEP_UNINITIALIZED = 0;
    /** 等首次答题 / 迷你会话进行中。 */
    public static final int STEP_IN_PROGRESS = 1;
    /** 迷你会话已完成，等打开个人主页。 */
    public static final int STEP_SESSION_DONE = 2;
    public static final int STEP_COMPLETE = 3;

    /** 引导迷你会话强制模式（D4：难度尊重玩家选择，仅强制 choice）。 */
    private static final String TUTORIAL_MODE = "choice";

    /** 手册物品的 PDC 标记键（静态访问器照 {@code ChatHistoryGUI.sessionIdKey} 模式）。 */
    private static final String BOOK_KEY = "onboarding_book";

    /**
     * 手册页渲染器：{@code BookMeta.setPages(List<String>)} 在 1.21.4 已废弃，
     * 页文案走 Adventure（{@code meta.pages(List<Component>)}）；文案仍是 {@code &} 码
     * 风格（与 messages 双文件同源），逐页交给 legacyAmpersand 转换为 Component。
     */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final LetsLearnEnglish plugin;

    /** step 缓存（权威读面，miss 懒加载；quit 清防泄漏）。 */
    private final Map<UUID, Integer> stepCache = new ConcurrentHashMap<>();

    /** 未完成引导的进服提醒去重：UUID → 最近发送的自然日（照 joinReminderSent 懒判定模式；quit 清）。 */
    private final Map<UUID, LocalDate> reminderSent = new ConcurrentHashMap<>();

    public OnboardingManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        // 自注册 PlayerInteractEvent（照 WordLearningManager 先例；本管理器在 onEnable
        // 内构造，registerEvents 合法）。getServer() 判空：纯 mock 单测构造本类时
        // 跳过注册即可，不影响运行期（生产环境 getServer 恒非 null）。
        if (plugin.getServer() != null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
    }

    // ============================================================
    //  生命周期（PlayerJoinListener 各一行接线）
    // ============================================================

    /**
     * join 接线：读库 step → 缓存，随后按状态分流：
     * <ul>
     *   <li>0 且老号（hasPlayedBefore）：静默回填 3（零打扰；写失败也 fail-open 不误拦）</li>
     *   <li>0 且首进：写 1 + （配置允许）发手册 + 40L 延迟发 join-intro（复查 isOnline）</li>
     *   <li>1/2 态：当日去重发 reminder（配置允许）+ 背包无标记书则静默补发</li>
     * </ul>
     */
    public void handleJoin(Player player) {
        if (!plugin.getConfigManager().isOnboardingEnabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        int step = plugin.getDatabaseManager().getOnboardingStep(playerId);
        stepCache.put(playerId, step);

        if (step == STEP_UNINITIALIZED) {
            if (player.hasPlayedBefore()) {
                // 回填失败（写库异常）时拦截条件要求 step 恰为 1，老玩家不会被误拦
                //（宁可漏引导，不误伤老号——fail-open 结构上成立）。
                advanceStep(playerId, STEP_COMPLETE);
                return;
            }
            // 首进：写 1 + 发书 + 40L 延迟 intro（复查 isOnline，照 R-45/L-20 模式）。
            // give-guide-book=false 时只发 intro 不发放手册（/le guide 手动补发仍可用）。
            advanceStep(playerId, STEP_IN_PROGRESS);
            if (giveGuideBookEnabled()) {
                giveGuideBook(player, false);
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "onboarding.join-intro"));
            }, 40L);
            return;
        }

        if (step == STEP_IN_PROGRESS || step == STEP_SESSION_DONE) {
            if (reminderOnJoinEnabled()) {
                LocalDate today = LocalDate.now();
                if (!today.equals(reminderSent.get(playerId))) {
                    reminderSent.put(playerId, today);
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "onboarding.reminder"));
                }
            }
            // 补发不随 give-guide-book 门（该键语义=「首次进服是否发放」，见 config 注释）；
            // 已持有则 giveGuideBook 扫背包去重后静默 no-op。
            giveGuideBook(player, false);
        }
    }

    /** quit 接线：清 step 缓存 + 提醒去重 map（F1-24 泄漏面直接规避）。 */
    public void unloadPlayer(UUID playerId) {
        stepCache.remove(playerId);
        reminderSent.remove(playerId);
    }

    // ============================================================
    //  答题拦截（MiscGUIListener / LearnCommand 各一行）
    // ============================================================

    /**
     * 引导拦截：{@code enabled && step==1} 时把"首次答题"改开 3 词迷你会话
     * （mode 强制 choice，答错原地重试由 WordLearningManager 的 tutorial 分支处理）。
     *
     * <p>调用点已保证 chat-active 等互斥守卫在前（与既有语义一致）；
     * 非引导玩家（step≠1）逐位零行为。拦截失败（词库缺失/无词可学）回落 false，
     * 由调用点既有错误处理给出标准提示。
     *
     * @return true = 已接管开局，调用点应直接 return；false = 走普通流程
     */
    public boolean interceptQuizStart(Player player, String difficulty) {
        if (!plugin.getConfigManager().isOnboardingEnabled()) {
            return false;
        }
        if (getStep(player.getUniqueId()) != STEP_IN_PROGRESS) {
            return false;
        }
        int sessionWords = plugin.getConfigManager().getMainConfig()
                .getInt("onboarding.session-words", 3);
        LearningSession session;
        try {
            session = plugin.getWordLearningManager().startTutorialQuiz(player, difficulty, sessionWords);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 与普通入口同口径：词库被删/无词可学时回落 false，调用点照常报错。
            return false;
        }
        if (session == null) {
            // S-F 在教程方法内做 word-system 模块门（关闭时已发 module.disabled 并返回
            // null）。此处接管返回，避免调用点回落普通路径再发一次提示 + 打开空 GUI。
            return true;
        }
        new WordLearningGUI(plugin).openWithMessage(player, difficulty, TUTORIAL_MODE);
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "onboarding.session-start",
                "difficulty", localizeDifficulty(player, difficulty),
                "count", String.valueOf(sessionWords)));
        return true;
    }

    // ============================================================
    //  完成登记（WordLearningManager / ProfileGUI 各一行）
    // ============================================================

    /**
     * 迷你会话走完（S-F 在 showSessionComplete 末尾调用，仅 tutorial 会话）：
     * step 1→2 + step2-done 文案；{@code require-profile-step=false} 时直达 3
     * （收书 + complete 文案，不重播音效——会话完成的 complete 刚播过，R-86 教训）。
     *
     * <p>不做 enabled 门：进行中的引导会话允许自然打完（状态真相优先，
     * plan-onboarding §1.2 开关中途关闭一节）。
     */
    public void onTutorialSessionComplete(Player player) {
        UUID playerId = player.getUniqueId();
        if (getStep(playerId) != STEP_IN_PROGRESS) {
            return; // 幂等：重复回调 / 非 walk-in 状态不进
        }
        if (requireProfileStep()) {
            advanceStep(playerId, STEP_SESSION_DONE);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "onboarding.step2-done"));
        } else {
            finishGuide(player, false);
        }
    }

    /**
     * 打开本人主页（ProfileGUI 在 isOwn 时一行调用）：step==2 时 → 3：
     * complete 文案 + complete 音效 + （默认）收回手册。step==1 提前打开不计数
     * （no-op），顺序刚性、无法跳步。
     *
     * <p>不做 enabled 门（同 {@link #onTutorialSessionComplete(Player)}：在途引导
     * 的收尾允许自然完成；已发手册在关闭期间由 onInteract 快闸退回普通可读书）。
     */
    public void onProfileOpened(Player viewer) {
        if (getStep(viewer.getUniqueId()) != STEP_SESSION_DONE) {
            return;
        }
        finishGuide(viewer, true);
    }

    /** step 2/3 收尾共用：写终态 + complete 文案 +（可选）音效 +（默认）收书。 */
    private void finishGuide(Player player, boolean playCompleteSound) {
        advanceStep(player.getUniqueId(), STEP_COMPLETE);
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "onboarding.complete"));
        if (playCompleteSound) {
            SoundManager.playSound(player, "complete");
        }
        if (removeBookOnComplete()) {
            removeGuideBook(player);
        }
    }

    // ============================================================
    //  手册（WRITTEN_BOOK + PDC 标记）
    // ============================================================

    /** 手册 PDC 标记键构造（监听器/发放/扫描共用同一真相源，照 sessionIdKey 模式）。 */
    public static NamespacedKey bookKey(LetsLearnEnglish plugin) {
        return new NamespacedKey(plugin, BOOK_KEY);
    }

    /**
     * 发放引导手册：先扫主背包 + 副手 PDC 去重（已持有 → false no-op），
     * 建书后 addItem；背包满时 addItem 的剩余落脚下（dropItemNaturally）。
     *
     * @param announce true 时发 {@code onboarding.guide-given} 确认文案（/le guide 路径），
     *                 静默补发（join/首进）传 false
     * @return true = 本次实际发放；false = 已持有（或空手 no-op 面）
     */
    public boolean giveGuideBook(Player player, boolean announce) {
        if (hasGuideBook(player)) {
            return false;
        }
        ItemStack book = buildGuideBook(player);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(book);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        if (announce) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "onboarding.guide-given"));
        }
        return true;
    }

    /**
     * 建一本当前语言 + 当前 step 现渲染的手册（每次发放/翻开都重建 —
     * 语言切换后翻开即英文，ConfigManager 语言机制零改动）。
     * step==3（keep-book 纪念态）渲染毕业页：三行全勾。
     */
    private ItemStack buildGuideBook(Player player) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(player));
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (book.getItemMeta() instanceof BookMeta meta) {
            meta.title(render(MessageUtil.getGuiMsg(messages, "onboarding.book.title")));
            meta.author(render(MessageUtil.getGuiMsg(messages, "onboarding.book.author")));
            meta.pages(buildGuidePages(player, messages));
            meta.getPersistentDataContainer().set(bookKey(plugin),
                    PersistentDataType.BYTE, (byte) 1);
            book.setItemMeta(meta);
        }
        return book;
    }

    /** 组装页列表：封面 / 动态进度页 / 答题小贴士页；完成态为单张毕业页。 */
    private List<Component> buildGuidePages(Player player, FileConfiguration messages) {
        List<Component> pages = new ArrayList<>();
        int step = getStep(player.getUniqueId());

        if (step >= STEP_COMPLETE) {
            // 毕业页（remove-book-on-complete=false 的纪念态）：标题 + 三行全勾。
            StringBuilder graduation = new StringBuilder(
                    MessageUtil.getGuiMsg(messages, "onboarding.book.page-steps-header"));
            appendLine(graduation, MessageUtil.getGuiMsg(messages, "onboarding.book.step1-done"));
            appendLine(graduation, MessageUtil.getGuiMsg(messages, "onboarding.book.step2-done"));
            appendLine(graduation, MessageUtil.getGuiMsg(messages, "onboarding.book.step3-done"));
            pages.add(render(graduation.toString()));
            return pages;
        }

        pages.add(render(MessageUtil.getGuiMsg(messages, "onboarding.book.page-cover")));

        boolean sessionDone = step >= STEP_SESSION_DONE;
        StringBuilder progress = new StringBuilder(
                MessageUtil.getGuiMsg(messages, "onboarding.book.page-steps-header"));
        appendLine(progress, MessageUtil.getGuiMsg(messages,
                sessionDone ? "onboarding.book.step1-done" : "onboarding.book.step1-pending"));
        appendLine(progress, MessageUtil.getGuiMsg(messages,
                sessionDone ? "onboarding.book.step2-done" : "onboarding.book.step2-pending"));
        appendLine(progress, MessageUtil.getGuiMsg(messages, "onboarding.book.step3-pending"));
        appendLine(progress, MessageUtil.getGuiMsg(messages,
                sessionDone ? "onboarding.book.hint-profile" : "onboarding.book.hint-session"));
        pages.add(render(progress.toString()));

        pages.add(render(MessageUtil.getGuiMsg(messages, "onboarding.book.page-tips")));
        return pages;
    }

    /**
     * 主手右键手册接线（plan-onboarding §1.4）：
     * 主手 + 右键 + PDC 标记书 + 引导活跃（step 1/2，或 step3+keep-book）→
     * 重建当前语言/step 的页塞回手位并 openBook（每次翻开现渲染）。
     * 点方块且非潜行时 cancel —— 防止"右键手册误开箱子"。
     * step==3 且默认收书时：残留/副本标记书静默清除（§1.2 边角二选一取此）。
     * 引导关闭时快闸退回原生阅读（书变普通可读书）。
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() == null || !event.getAction().isRightClick()) {
            return;
        }
        if (!isGuideBook(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.getConfigManager().isOnboardingEnabled()) {
            return; // 运营快闸：标记书变普通成书（原生翻页），本监听器不接管
        }
        int step = getStep(player.getUniqueId());
        if (step >= STEP_COMPLETE && removeBookOnComplete()) {
            // 已完成 + 默认收书：残留（reset 后回填 / 箱子取回 / 复制）标记书静默清除。
            event.setCancelled(true);
            removeGuideBook(player);
            return;
        }
        boolean active = step >= STEP_IN_PROGRESS;
        if (!active) {
            return;
        }
        if (event.getClickedBlock() != null && !player.isSneaking()) {
            event.setCancelled(true);
        }
        ItemStack fresh = buildGuideBook(player);
        player.getInventory().setItemInMainHand(fresh);
        player.openBook(fresh);
    }

    /** 扫描主背包 + 副手，移除全部 PDC 标记手册（静默，完成/清理路径用）。 */
    private void removeGuideBook(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean removed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isGuideBook(contents[i])) {
                inventory.setItem(i, null);
                removed = true;
            }
        }
        if (isGuideBook(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(null);
            removed = true;
        }
        if (removed) {
            player.updateInventory();
        }
    }

    /** 是否已持有标记手册（主背包 + 副手）。发放前查 PDC 即去重，无状态需要维护。 */
    private boolean hasGuideBook(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isGuideBook(item)) {
                return true;
            }
        }
        return isGuideBook(player.getInventory().getItemInOffHand());
    }

    /** 物品是否本插件的标记手册（WRITTEN_BOOK + PDC 键；PDC 随物品 NBT 走，丢弃拾回保留）。 */
    private boolean isGuideBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(bookKey(plugin), PersistentDataType.BYTE);
    }

    // ============================================================
    //  命令面与查询
    // ============================================================

    /**
     * /le guide：进度总览 + 补书。分支：未启用 → guide-disabled；已完结 → guide-done；
     * 其余 → guide-header + 三行步骤状态（复用 book.step*-* 键）+ 补发手册
     *（已持有时 giveGuideBook 静默 no-op）。
     */
    public void handleGuideCommand(Player player) {
        if (!plugin.getConfigManager().isOnboardingEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "onboarding.guide-disabled"));
            return;
        }
        int step = getStep(player.getUniqueId());
        if (step >= STEP_COMPLETE) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "onboarding.guide-done"));
            return;
        }
        boolean sessionDone = step >= STEP_SESSION_DONE;
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "onboarding.guide-header"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                sessionDone ? "onboarding.book.step1-done" : "onboarding.book.step1-pending"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                sessionDone ? "onboarding.book.step2-done" : "onboarding.book.step2-pending"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "onboarding.book.step3-pending"));
        giveGuideBook(player, true);
    }

    /** step 读面：缓存权威，miss 时懒加载（一条小 SELECT）并缓存。 */
    public int getStep(UUID playerId) {
        Integer cached = stepCache.get(playerId);
        if (cached != null) {
            return cached;
        }
        int loaded = plugin.getDatabaseManager().getOnboardingStep(playerId);
        stepCache.put(playerId, loaded);
        return loaded;
    }

    /** step 单调写：to ≤ 当前不写（杜绝 quit/rejoin 竞态与重复回调把进度写回退）。 */
    private void advanceStep(UUID playerId, int to) {
        int current = getStep(playerId);
        if (to <= current) {
            return;
        }
        stepCache.put(playerId, to);
        plugin.getDatabaseManager().setOnboardingStep(playerId, to);
    }

    // ============================================================
    //  配置 / 文案小工具
    // ============================================================

    private boolean giveGuideBookEnabled() {
        return plugin.getConfigManager().getMainConfig()
                .getBoolean("onboarding.give-guide-book", true);
    }

    private boolean reminderOnJoinEnabled() {
        return plugin.getConfigManager().getMainConfig()
                .getBoolean("onboarding.reminder-on-join", true);
    }

    private boolean requireProfileStep() {
        return plugin.getConfigManager().getMainConfig()
                .getBoolean("onboarding.require-profile-step", true);
    }

    private boolean removeBookOnComplete() {
        return plugin.getConfigManager().getMainConfig()
                .getBoolean("onboarding.remove-book-on-complete", true);
    }

    /**
     * {difficulty} 本地化：复用既有 {@code word.difficulty-*} 键（F2-13 同口径），
     * 未知标识符回落原值保证信息不丢。
     */
    private String localizeDifficulty(Player player, String difficulty) {
        return switch (difficulty.toLowerCase(java.util.Locale.ROOT)) {
            case "beginner" -> plugin.getMessageUtil().getPlayerMessage(player, "word.difficulty-beginner");
            case "intermediate" -> plugin.getMessageUtil().getPlayerMessage(player, "word.difficulty-intermediate");
            case "advanced" -> plugin.getMessageUtil().getPlayerMessage(player, "word.difficulty-advanced");
            default -> difficulty;
        };
    }

    /** {@code &} 码文案 → Adventure Component（页/题头渲染统一入口）。 */
    private static Component render(String legacyText) {
        return LEGACY.deserialize(legacyText);
    }

    /**
     * 页内追加一行：仅在 buffer 结尾无 \n 时补换行 —— 兼容 i18n 值自带/不带
     * 尾换行的两种形态（page-steps-header 草案自带 \n，不做双重空行）。
     */
    private static void appendLine(StringBuilder buffer, String line) {
        if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) != '\n') {
            buffer.append('\n');
        }
        buffer.append(line);
    }
}
