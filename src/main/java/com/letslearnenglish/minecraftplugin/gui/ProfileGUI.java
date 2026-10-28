package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.duel.DuelStatsText;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Personal Profile GUI — 27-slot small chest layout.
 *
 * Shows:
 * - Player head with name (top center, slot 4)
 * - Key statistics row (slots 10-15)
 * - Duel record (slot 16, asynchronously filled — R10)
 * - Achievement wall button (bottom-left, slot 18)
 * - Settings button (bottom-right, slot 26, only when viewing own profile)
 */
public class ProfileGUI {

    private static final int GUI_SIZE = 27;

    /**
     * 决斗战绩槽位（R10）。
     *
     * <p>原属 {@link #fillBorder} 的边框玻璃（16），因此 fillBorder 的槽位表已同步
     * 摘掉它 —— 否则边框会在本行写入之后/之前互相覆盖，取决于调用顺序。
     * 该槽为纯展示物：点击侧（SocialListener#handleProfileGUI）没有 16 分支，
     * 上半区点击由 SocialListener 统一 cancel。
     *
     * <p>FIX-H1（RT-H2 高危）订正：本类原注释称「上半区点击由 MiscGUIListener 统一
     * cancel」，这是<b>错的</b> —— {@code MiscGUIListener.isTrackedKey} 只含
     * PROFILE_SETTINGS / WORD_BANK / LEARNING_CALENDAR / DAILY_CHALLENGE /
     * CHALLENGE_COMPLETE，<b>不含 PROFILE</b>。本页上半区的 cancel 由 SocialListener 负责。
     * 这条错误前提正是 slot 16 裸空却无人兜底的根源：误以为另有一层守卫，于是既没铺
     * 空槽、也没补下半区 shift-click 守卫。现在两道都补齐（fillEmpty + SocialListener
     * 的 InventoryAction 前置例外），见 {@link #open} 末尾注释。
     */
    private static final int DUEL_SLOT = 16;
    private final LetsLearnEnglish plugin;

    public ProfileGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, UUID targetId) {
        boolean isOwn = viewer.getUniqueId().equals(targetId);
        // 域 1 新手引导第 3 步登记：打开本人主页（step==2 时）→ 引导完成 +
        // complete 文案/音效 + 默认收书。他人主页不计数、step≠2 时 no-op，
        // 顺序刚性无法跳步（plan-onboarding §1.2）。
        if (isOwn) {
            plugin.getOnboardingManager().onProfileOpened(viewer);
        }
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));

        PlayerDataStore store = plugin.getPlayerDataStore();
        String targetName = store.getPlayerName(targetId);
        if (targetName == null) {
            Player targetPlayer = Bukkit.getPlayer(targetId);
            targetName = targetPlayer != null ? targetPlayer.getName()
                    : MessageUtil.getGuiMsg(messages, "profile.unknown-player");
        }

        String title;
        if (isOwn) {
            title = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.title")
                            .replace("{player}", viewer.getName()));
        } else {
            title = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.viewing-title")
                            .replace("{name}", targetName));
        }

        // R5-4：挂结构身份 holder。payload.Target 记录「这张主页属于谁」——
        // 迁移前点击侧只能用 extractTargetIdFromTitle 拿玩家名去缓存里做「最长子串」
        // 反查（名字互为前缀时还会挑错），昵称含空格/特殊符号/改名窗口都能让它
        // 静默失败。holder 化后归属是打开时写死的 UUID，与文案无关。
        // 注意：本人与他人主页共用同一个 key（PROFILE），归属只由 payload 区分。
        // targetId 为 null 属调用方违约（全仓调用点都传非 null UUID）：Target 构造
        // 会立即抛 NPE，比「一张归属不明的主页被打开、点击侧再靠标题猜人」更安全。
        //
        // R10：holder 先落局部变量再交给 createInventory —— 异步回填要靠这把「钥匙」
        // 判定「我回填的是不是当初那张界面」（见 stillViewing）。若改读
        // inv.getHolder()，纯 JVM 单测里 mock Inventory 返回 null，回填守卫会永远
        // 静默失败；持有构造时的引用则与运行环境无关。
        PluginGuiHolder holder = PluginGuiHolder.of(GuiViewKey.PROFILE,
                new PluginGuiHolder.GuiPayload.Target(targetId));
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, title);

        // L-05：展示路径走只读版，缓存缺失不建条目。
        // L22：缓存 miss（离线玩家）现在返回「隐私最严」副本（showResults/showInSquare=false），
        // 不再假设公开。本人主页必然在线（viewer 自己），join 时已预热，不受影响。
        PlayerDataStore.ProfileSettings ps = store.getProfileSettingsReadOnly(targetId);
        boolean achievementsEnabled = plugin.getConfigManager().isAchievementsEnabled();

        // R-38：他人主页隐私 —— 目标关 showResults 则学习成绩三卡（字数/正确率/场次）
        // 只隐藏数值行；标签行保留。自己主页（isOwn）零改动。
        boolean hideResults = !isOwn && !ps.isShowResults();
        String hiddenValue = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.stats-hidden-value"));
        String ageUnsetLine = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.age-unset"));
        String ageHiddenLine = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.age-hidden"));
        // L21：异步取数期间的占位行。缺键返回空串（guiText 语义）——宁可短暂少一行，
        // 也不把 [?profile.stats-loading] 这种调试占位符写进 lore。
        // 回落顺序：专用键 → 决斗块既有的「加载中…」→ 空串。这样即便 i18n 新键还没
        // 落地（语言文件由编排者统一维护），占位行也不会是空的。
        String loadingLine = guiText(messages, "profile.stats-loading");
        if (loadingLine.isEmpty()) {
            loadingLine = guiText(messages, DuelStatsText.KEY_LOADING);
        }

        // L21：各类数据的「是否要异步取」在<b>主线程一次判定</b>，渲染与回填共用同一组
        // boolean，避免两处各判一次导致「渲染了占位符却没人回填」或反向的「回填了但
        // 主线程已按同步态画了数值」。
        //  - 统计三卡：隐私隐藏态完全不需要数值 → 不查；
        //  - 金币：只有本人主页才展示数值（他人恒为「已隐藏」）→ 他人不查；
        //  - 成就数：模块关闭时按钮根本不渲染 → 不查（L21 的原始 bug 就出在这里）；
        //  - 决斗：模块关闭时整行不渲染 → 不查。
        // duel 一格由下方占位块的门决定（见 duelRowRendered），保证「画了占位就一定会回填」。
        boolean statsAsync = !hideResults;
        boolean coinsAsync = isOwn && plugin.getEconomy() != null;
        boolean duelRowRendered = false;

        fillBorder(inv);

        // 过往学习按钮 — slot 0 (left-top corner)
        String calendarLabel = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.calendar", ""));
        String calendarLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.calendar-lore", ""));
        inv.setItem(0, createItem(Material.CLOCK, calendarLabel,
                Collections.singletonList(calendarLore)));

        // Player head — slot 4
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta hm) {
            hm.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
            hm.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.head-name-color") + targetName));
            head.setItemMeta(hm);
        }
        inv.setItem(4, head);

        // Words learned — slot 11 (R-38: 他人关 showResults 时只隐藏数值行，标签保留)
        inv.setItem(11, createInfoItem(Material.BOOK,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-words-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Collections.singletonList(loadingLine)));

        // Accuracy — slot 12 (R-38)
        inv.setItem(12, createInfoItem(Material.EXPERIENCE_BOTTLE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-accuracy-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Collections.singletonList(loadingLine)));

        // Age — slot 13 (R-39: 0/脏数据经 getAge 校验归 0，显示"未设置"；
        // 非本人看他人主页一律不显示年龄，与 showResults 开关无关)
        // 数据源只有 ProfileSettings（内存读，零 IO），故保持同步渲染。
        int age = ps.getAge();
        String ageLine;
        String ageLore;
        if (!isOwn) {
            ageLine = ageHiddenLine;
            ageLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age-hidden-hint"));
        } else if (age <= 0) {
            ageLine = ageUnsetLine;
            ageLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age-hint"));
        } else {
            ageLine = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age")
                            .replace("{age}", String.valueOf(age)));
            ageLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age-hint"));
        }
        inv.setItem(13, createInfoItem(Material.CAKE, ageLine,
                Collections.singletonList(ageLore)));

        // Sessions — slot 14 (R-38)
        inv.setItem(14, createInfoItem(Material.CLOCK,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-sessions-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Collections.singletonList(loadingLine)));

        // Coins — slot 15 (R-38: 金币余额非本人一律只隐藏数值行，标签保留)
        // L21：Vault 的 getBalance 是同步调用且多数实现背后是数据库（见报告），
        // 与统计三卡同批异步；经济未接入时（economy == null）沿用旧行为显示 0，
        // 无需发那次异步调用。
        List<String> coinsLore;
        if (!isOwn) {
            coinsLore = Collections.singletonList(hiddenValue);
        } else if (coinsAsync) {
            coinsLore = Collections.singletonList(loadingLine);
        } else {
            coinsLore = Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.stats-coins-value")
                            .replace("{coins}", "0")));
        }
        inv.setItem(15, createInfoItem(Material.GOLD_INGOT,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-coins-label")),
                coinsLore));

        // Duel record — slot 16 (R10)
        // 模块门：与 slot 18 成就墙同源 gating —— duel.enabled 关闭时整行不渲染
        // （槽位留空），也<b>不派发</b>那次异步 DB 读：模块关着还每题开主页去查两张
        // 决斗表，纯属白烧 IO。历史战绩仍在库里，重新开启模块后自动恢复显示。
        // 这里内联读配置（而非提前落一个局部量）：B3WiringLintsTest 的 L5
        // 以「if 块条件里出现 isDuelEnabled()」为锚点，钉住「占位落在模块门内」。
        // 每个主页打开多一次点读配置，代价可忽略。
        if (plugin.getConfigManager().isDuelEnabled()) {
            // 主线程只放「加载中」占位；真正的取数在异步段（getDuelStats 是同步阻塞读，
            // DatabaseManager:1600 明写禁止主线程调用）。
            // 占位必须先于 openInventory 落进 inv：玩家开界面后到异步回填之间的这段时间
            // 看到的是「加载中…」而不是一个空槽（空槽=看上去这张主页没有这一行）。
            List<String> loadingLines = List.of(
                    guiText(messages, DuelStatsText.KEY_LABEL),
                    guiText(messages, DuelStatsText.KEY_LOADING));
            inv.setItem(DUEL_SLOT, buildDuelItem(loadingLines));
            duelRowRendered = true;
        }

        // Achievement wall button — slot 18 (bottom-left) with count.
        // MC-2 模块门：progress-system/achievements 关闭时按钮不渲染（槽位留空）；
        // 点击侧（SocialListener）另有分支拒绝，渲染与点击同源 gating。
        // L21：未解锁数走异步（getUnlockedCount 缓存 miss 时会全量查成就表），
        // 主线程只放标签 + 「加载中」占位。
        if (achievementsEnabled) {
            String achWallLabel = ChatColor.translateAlternateColorCodes('&', "&e&l" +
                    MessageUtil.getGuiMsg(messages, "profile.stats-achievements-label"));
            inv.setItem(18, createItem(Material.PAINTING, achWallLabel,
                    Collections.singletonList(loadingLine)));
        }

        // Back button — slot 8
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // Settings button — slot 26 (bottom-right, only own)
        if (isOwn) {
            inv.setItem(26, createItem(Material.COMPARATOR,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-button")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-lore")))));
        } else {
            // 别人主页 — 显示灰色玻璃板表示设置不可用
            inv.setItem(26, createItem(Material.GRAY_STAINED_GLASS_PANE,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-locked")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-locked-lore")))));
        }

        // FIX-H1（RT-H2 高危·物品丢失）：本页有多个<b>条件渲染</b>槽位 —— slot 16 决斗行
        // 在 duel.enabled=false 时整行不渲染、slot 18 成就墙在 achievements 关闭时留空、
        // slot 26 之外的布局未来也可能调整。B3 已把 slot 16 从 fillBorder 的槽位表摘掉
        // （让位给战绩行），于是模块关闭时 27 格里至少有一个裸空槽：原版 shift-click /
        // 双击收集会在整个 view 里找落点，把玩家物品塞进去；而点击侧对 PROFILE 上半区
        // 一律 cancel，物品既取不回也放不下，<b>关界面即丢</b>。
        //
        // 位置铁律：fillBorder 最前、各 setItem 居中、fillEmpty 在<b>全部 setItem 之后
        // 与 openInventory 之前</b> —— fillEmpty 只填空槽、不覆盖已渲染物品，因此对
        // slot 16 的「加载中」占位与异步回填（回填走 setItem 直写）都无影响；
        // 反过来放在前面则会被后续 setItem 覆盖成无效兜底。与 openSettings 的 R5-7
        // 同款写法（那边是 10/12 裸空，这里是 16/18）。
        GUIUtils.fillEmpty(inv);

        viewer.openInventory(inv);

        // L21：界面已经画完并交给玩家，数据读取整段搬到异步（见 scheduleProfileBackfill）。
        // 放在 openInventory 之后：玩家先看到「加载中」，后看到数值，不会出现先数值
        // 后闪烁；同时保持 `GuiOpenIdentityTest` 依赖的同步 createInventory→openInventory 链。
        // needBackfill 用 duelRowRendered 而非 duelEnabled：两者只在「配置在两次点读之间
        // 被 /lea reload 改掉」这种竞态下才可能不同，用渲染时的实际结果才能保证
        // 「画了占位就一定有回填」。
        if (statsAsync || coinsAsync || achievementsEnabled || duelRowRendered) {
            // Bukkit.getOfflinePlayer(UUID) 主线程调用安全（不查库），且 Vault 的
            // getBalance 需要一个 OfflinePlayer 实参——在这里取好交给异步段，
            // 避免异步线程触碰 Bukkit API。只有真要查余额时才取（economic 未接入
            // 或他人主页时这次调用纯属浪费）。
            org.bukkit.OfflinePlayer offlineTarget =
                    coinsAsync ? Bukkit.getOfflinePlayer(targetId) : null;
            scheduleProfileBackfill(viewer, targetId, holder, messages,
                    hideResults, achievementsEnabled, duelRowRendered, coinsAsync, offlineTarget);
        }
    }

    /**
     * Open the settings sub-page (privacy toggles).
     */
    public void openSettings(Player viewer) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.settings-title"));
        // R5-4：设置页与主页同属 ProfileGUI（同尺寸 27 格），标题文案不同但同样可被
        // 管理员改；holder key 让点击侧（SocialListener / MiscGUIListener 的隐私开关）
        // 不必再靠标题等值比对就能认出「这是设置页而不是主页」。
        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.PROFILE_SETTINGS), GUI_SIZE, title);

        UUID playerId = viewer.getUniqueId();
        // P1-1 联动（红队 L）：<b>纯渲染读，绝不能走写路径</b>。
        //
        // 旧实现用 getProfileSettings —— 它内部是 computeIfAbsent，缓存 miss 时会合成一份
        // new ProfileSettings()（showResults / showInSquare 全 true）并<b>写进缓存</b>。
        // 设置页是「看一眼」的最短路径，于是最坏链条是：
        //   打开设置页（此处种下全公开条目）→ 玩家什么都不改直接退出
        //   → onInventoryClose / quit 触发 unloadProfileSettings
        //   → 它先 saveProfileSettings（整对象 UPSERT）再删键
        //   → show_in_square=true 被<b>永久写回 DB</b>，玩家关掉的隐身开关不可撤销地丢了。
        //
        // 改只读后 miss 返回 unresolvedFallback()（隐私位 false）。但那样玩家会看到
        // 「所有开关都是关」的假象，所以这里先确保缓存里是真实值：命中零 IO，
        // 未命中则同步读一次库（本方法本就在主线程、本就由玩家主动触发，单条主键 SELECT）。
        // 读不到就按最严渲染 —— 宁可显示「关」，也不能伪造「开」。
        PlayerDataStore settingStore = plugin.getPlayerDataStore();
        if (settingStore.getCachedProfileSettings(playerId) == null) {
            settingStore.loadProfileSettings(playerId); // 失败保持 miss，读侧 fail-closed
        }
        PlayerDataStore.ProfileSettings ps = settingStore.getProfileSettingsReadOnly(playerId);

        // Fill border
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        int[] borderSlots = {0,1,2,3,5,6,7,9,17,18,19,20,21,22,23,24,25,26};
        for (int s : borderSlots) inv.setItem(s, border);

        // Player head at top
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta hm) {
            hm.setOwningPlayer(Bukkit.getOfflinePlayer(playerId));
            hm.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&6" + viewer.getName()));
            head.setItemMeta(hm);
        }
        inv.setItem(4, head);

        // Show answers toggle — slot 11
        boolean showAnswersOn = ps.isShowAnswers();
        inv.setItem(11, createItem(
                showAnswersOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-show-answers")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showAnswersOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-show-answers-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-show-answers-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Show results toggle — slot 13
        boolean showResultsOn = ps.isShowResults();
        inv.setItem(13, createItem(
                showResultsOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-show-results")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showResultsOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-show-results-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-show-results-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Show in square toggle — slot 15
        boolean showInSquareOn = ps.isShowInSquare();
        inv.setItem(15, createItem(
                showInSquareOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-show-in-square")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showInSquareOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-show-in-square-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-show-in-square-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Sounds toggle — slot 16（Wave 1 3A-4 补建：per-player 音效开关 UI 入口，
        // SoundManager.isPlayerSoundEnabled 门早已接线；plan-audio-feedback §2.2 语义：
        // 关闭后全部 SoundManager 播放静音，ActionBar/BossBar 视觉保留）
        boolean soundsOn = ps.isSoundsEnabled();
        inv.setItem(16, createItem(
                soundsOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-sounds")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', soundsOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-sounds-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-sounds-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Reminders toggle — slot 14（R1/R4 教育骚扰治理：玩家侧提醒总开关 UI 入口。
        // 语义：关闭后不再收到每日学习提醒 / 进服提醒 / 复习提醒；服务器 config 仍是总闸。
        // 槽位：开关行（9-17，9/17 为边框）中 11/13/15/16 已被四个既有开关占用，
        // 剩余空位为 10/12/14；选 14 —— 使 13/14/15/16 连成一段无空洞的四连开关，
        // 10/12 反而会把 11 与 13 之间留出空档。返回键 8 与边框 0-7/9/17-26 不动。
        boolean remindersOn = ps.isRemindersEnabled();
        inv.setItem(14, createItem(
                remindersOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-reminders")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', remindersOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-reminders-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-reminders-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Back button — slot 8
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // R5-7：空槽统一填灰玻璃（照 WordLearningGUI / ClassBoardGUI 的 R12-M3 用法）。
        // 为什么必须补：本页 27 格里 borderSlots 与各开关（4/8/11/13/14/15/16）之外，
        // slot 10 与 12 <b>从未被填充</b>。原版 shift-click / 双击会在整个 view 里找落点，
        // 把玩家物品塞进 10/12；而点击侧（MiscGUIListener）对上半区一律 cancel，物品
        // 既取不回也放不下，关界面即丢。fillEmpty 放在全部 setItem 之后、openInventory
        // 之前 —— 只填空槽、不覆盖已渲染开关，且未来布局再漏格也会被自动兜住（比把
        // 10/12 补进 borderSlots 更稳：后者每加一个开关就要记得同步一张槽位表）。
        GUIUtils.fillEmpty(inv);

        viewer.openInventory(inv);
    }

    /**
     * L21：异步取主页所需的全部数据，回主线程一次性回填。
     *
     * <p><b>线程契约</b>：{@code getPlayerStats}（缓存 miss 时 {@code loadPlayerData}
     * 要扫 {@code word_progress} + {@code player_data}）、{@code getUnlockedCount}
     * （miss 时全量查成就表）、{@code getDuelStats} / {@code getDuelWinStreak}
     * （{@code DatabaseManager:1600} 明写禁止主线程调用）、以及 Vault 的
     * {@code getBalance}（多数经济实现背后是数据库）都是同步阻塞调用。因此本方法严格
     * 两段式：异步段只做取数，主线程段只做渲染（零 DB 调用），照
     * {@link #scheduleProfileBackfill} 的兄弟实现 {@code ChatHistoryGUI#openAsync} 同式。
     *
     * <p><b>为什么每一类数据单独 try</b>：它们是四个互不相关的数据源。任何一处失败
     * 都只应让<b>那一格</b>停在「加载中」，而不是让整张主页的回填全部放弃
     * （旧实现在 {@code open()} 里同步取数，一处抛异常等于整页打不开）。
     * 取不到时对应字段留 {@code null}，回填侧跳过该槽 —— 保持「加载中」占位，
     * 而不是伪造 0（把「不知道」说成「没学过」是另一种谎报）。
     *
     * <p><b>调度器 null 守卫</b>：{@code Bukkit.getScheduler()} 在纯 JVM 单测里被
     * {@code mockStatic(Bukkit.class)} 返回 null。这里显式判空而不是让 NPE 冒出来 ——
     * 主页渲染不该因为「这几个可选信息块取不到」而整体打开失败。
     *
     * @param viewer              打开界面的玩家（回填时复检在线）
     * @param target              主页归属玩家
     * @param opened              打开时构造的 holder 实例，用作「还是不是这张界面」的钥匙
     * @param messages            打开者语言的文案配置（异步段不读，仅捕获给主线程渲染用）
     * @param hideResults         他人主页且对方关闭 showResults 时 true（不取统计）
     * @param achievementsEnabled 成就模块是否开启（关闭时不取解锁数 —— L21 原始 bug）
     * @param duelRowRendered     决斗行是否已渲染占位（= duel.enabled 生效值；未渲染则不取战绩/连胜）
     * @param coinsAsync          是否需要取金币余额（仅本人主页且经济已接入）
     * @param offlineTarget       主线程预先取好的 {@code OfflinePlayer}（Vault 实参；
     *                            异步线程不触碰 Bukkit API）
     */
    private void scheduleProfileBackfill(Player viewer, UUID target, PluginGuiHolder opened,
                                         FileConfiguration messages, boolean hideResults,
                                         boolean achievementsEnabled, boolean duelRowRendered,
                                         boolean coinsAsync,
                                         org.bukkit.OfflinePlayer offlineTarget) {
        Function<String, String> msg = key -> guiText(messages, key);

        org.bukkit.scheduler.BukkitScheduler scheduler = Bukkit.getScheduler();
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) return;

                PlayerDataStore store = plugin.getPlayerDataStore();
                PlayerDataStore.PlayerStats stats = null;
                if (!hideResults) {
                    try {
                        stats = store.getPlayerStats(target);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to preload profile stats for " + target, e);
                    }
                }

                Double coins = null;
                var economy = plugin.getEconomy();
                if (coinsAsync && economy != null && offlineTarget != null) {
                    try {
                        coins = economy.getBalance(offlineTarget);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to read economy balance for " + target, e);
                    }
                }

                Integer unlocked = null;
                if (achievementsEnabled) {
                    try {
                        unlocked = plugin.getAchievementManager().getUnlockedCount(target);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to preload achievement count for " + target, e);
                    }
                }

                DatabaseManager.DuelStats duel = null;
                // L4：连胜与战绩同一批取（同一条 duel_stats 表、同一个模块门）。
                // 语义见 DatabaseManager#getDuelWinStreak：从最近一场往回数，遇负即停；
                // 0 = 无胜绩或最近一场为负 —— DuelStatsText 据此不渲染连胜行。
                int streak = 0;
                if (duelRowRendered) {
                    try {
                        DatabaseManager db = plugin.getDatabaseManager();
                        duel = db.getDuelStats(target);
                        streak = db.getDuelWinStreak(target);
                    } catch (Exception e) {
                        // DB 层内部已把 SQLException 降级成 (0,0)/0（见 getDuelStats 的
                        // 异常分支），走到这里说明是接线/生命周期问题。保留「加载中」
                        // 占位而不是伪造「暂无战绩」—— 后者会把「不知道」说成「没打过」。
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to preload duel stats for " + target, e);
                    }
                }

                ProfileSnapshot snapshot = new ProfileSnapshot(
                        stats, coins, unlocked, duel, streak);
                try {
                    scheduler.runTask(plugin, () -> placeProfileData(
                            viewer, target, opened, snapshot, hideResults,
                            achievementsEnabled, duelRowRendered, coinsAsync, messages, msg));
                } catch (IllegalStateException ignored) {
                    // 关服竞态：调度器已关闭，放弃本次回填（玩家也已不可能看到结果）
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：各槽留在「加载中」，界面不会露出
        }
    }

    /**
     * L21：一次异步取数的结果快照。
     *
     * <p>每个字段 {@code null} 都表示「这一类数据没取到」，回填侧据此<b>跳过</b>对应槽位
     * （保留「加载中」），而不是写 0。{@code streak} 用原始 {@code int}：它只在
     * {@code duel != null} 时才有意义，0 本身就是合法值（无连胜）。
     */
    private record ProfileSnapshot(PlayerDataStore.PlayerStats stats,
                                   Double coins,
                                   Integer unlockedCount,
                                   DatabaseManager.DuelStats duel,
                                   int streak) {
    }

    /**
     * L21：主线程回填整张主页。三道守卫见 {@link #stillViewing}。
     *
     * <p>异步段可能耗时数十毫秒，期间玩家完全可能：关掉主页、打开别人的主页、
     * 或者下线。任何一道守卫失效的后果都不是「看起来旧」，而是<b>把 A 的数据写进
     * B 的界面</b>（跨玩家数据串台，隐私事故）。
     *
     * @param snapshot           异步段取到的数据（字段可为 null = 未取到）
     * @param hideResults        隐私态（true = 不覆盖三卡，主线程已画隐藏行）
     * @param achievementsEnabled 成就模块是否开启
     * @param duelRowRendered    决斗行是否已渲染占位（决定是否回填 slot 16）
     * @param coinsAsync         是否需要回填金币
     * @param messages           文案配置（回填渲染用）
     * @param msg                i18n 取值函数（决斗块用）
     */
    private void placeProfileData(Player viewer, UUID target, PluginGuiHolder opened,
                                  ProfileSnapshot snapshot, boolean hideResults,
                                  boolean achievementsEnabled, boolean duelRowRendered,
                                  boolean coinsAsync, FileConfiguration messages,
                                  Function<String, String> msg) {
        if (!stillViewing(viewer, target, opened)) return;

        Inventory top = viewer.getOpenInventory().getTopInventory();

        // ---- 统计三卡 11 / 12 / 14 ----
        if (!hideResults && snapshot.stats() != null) {
            PlayerDataStore.PlayerStats stats = snapshot.stats();
            int totalWords = stats.getTotalWords();
            int totalCorrect = stats.getTotalCorrect();
            int totalIncorrect = stats.getTotalIncorrect();
            int sessions = stats.getTotalSessions();
            // P3-01 修复（Phase 3 规划期发现，编排者即修）：原公式 correct/getTotalWords() 在
            // M10 语义下分子分母同值（getTotalWords()==totalCorrect），正确率恒显 100%。
            // 改为 correct/(correct+incorrect)，与 LLEPapiExpansion 的 %lle_accuracy% 同口径。
            int accuracy = (totalCorrect + totalIncorrect) > 0
                    ? (int) ((double) totalCorrect / (totalCorrect + totalIncorrect) * 100)
                    : 0;

            top.setItem(11, createInfoItem(Material.BOOK,
                    ChatColor.translateAlternateColorCodes('&', "&e" +
                            MessageUtil.getGuiMsg(messages, "profile.stats-words-label")),
                    Arrays.asList(
                            ChatColor.translateAlternateColorCodes('&',
                                    MessageUtil.getGuiMsg(messages, "profile.stats-words-value")
                                            .replace("{words}", String.valueOf(totalWords))),
                            ChatColor.translateAlternateColorCodes('&',
                                    MessageUtil.getGuiMsg(messages, "profile.stats-words-detail")
                                            .replace("{correct}", String.valueOf(totalCorrect))
                                            .replace("{incorrect}", String.valueOf(totalIncorrect))))));

            top.setItem(12, createInfoItem(Material.EXPERIENCE_BOTTLE,
                    ChatColor.translateAlternateColorCodes('&', "&e" +
                            MessageUtil.getGuiMsg(messages, "profile.stats-accuracy-label")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.stats-accuracy-value")
                                    .replace("{accuracy}", String.valueOf(accuracy))))));

            top.setItem(14, createInfoItem(Material.CLOCK,
                    ChatColor.translateAlternateColorCodes('&', "&e" +
                            MessageUtil.getGuiMsg(messages, "profile.stats-sessions-label")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.stats-sessions-value")
                                    .replace("{sessions}", String.valueOf(sessions))))));
        }

        // ---- 金币 15（仅本人主页；他人恒为「已隐藏」，主线程已画好，不回填） ----
        if (coinsAsync && snapshot.coins() != null) {
            var economy = plugin.getEconomy();
            String value = (economy == null) ? "0" : economy.format(snapshot.coins());
            top.setItem(15, createInfoItem(Material.GOLD_INGOT,
                    ChatColor.translateAlternateColorCodes('&', "&e" +
                            MessageUtil.getGuiMsg(messages, "profile.stats-coins-label")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.stats-coins-value")
                                    .replace("{coins}", value)))));
        }

        // ---- 成就墙按钮 18 ----
        if (achievementsEnabled && snapshot.unlockedCount() != null) {
            String achWallLabel = ChatColor.translateAlternateColorCodes('&', "&e&l" +
                    MessageUtil.getGuiMsg(messages, "profile.stats-achievements-label"));
            String achWallCount = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.stats-achievements-value")
                            .replace("{unlocked}", String.valueOf(snapshot.unlockedCount()))
                            .replace("{total}", String.valueOf(Achievement.values().length)));
            String achWallHint = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.achievements-hint"));
            top.setItem(18, createItem(Material.PAINTING, achWallLabel,
                    Arrays.asList(achWallCount, achWallHint)));
        }

        // ---- 决斗战绩 16（L4：streak 不再是硬编码 0） ----
        // C-P2-6（红队 C）：守卫已在方法开头（stillViewing）过了一次，这里只做渲染 ——
        // 渲染收在 applyDuelRow 里，与测试（反射直调 applyDuelRow 只测渲染）共用同一个
        // 生产实现，不再存在「测试验证的方法生产不走」的分叉。
        if (duelRowRendered && snapshot.duel() != null) {
            applyDuelRow(top, snapshot.streak(), snapshot.duel(), hideResults, msg);
        }
    }

    /**
     * 决斗战绩行（slot 16）的<b>唯一</b>渲染入口。
     *
     * <p><b>本方法不做守卫</b>：调用方必须先过 {@link #stillViewing}（生产路径是
     * {@link #placeProfileData} 的第一行）。守卫与渲染分开是刻意的 —— 一次回填只该
     * 判一次「界面还是不是那一张」，判两次会让同一次失败打出两条一模一样的日志。
     * 行为级守卫由 {@code ProfileOpenAsyncTest}（经 {@code open()} 全链路）覆盖。
     *
     * @param top        目标界面（调用方已确认仍是打开时那一张）
     * @param streak     当前连胜场数（0 = 不渲染连胜行）
     * @param duel       已取到的战绩（非 null；null 由调用方跳过）
     * @param hideResults 隐私态（true = 只留标签行）
     * @param msg        i18n 取值函数
     */
    private void applyDuelRow(Inventory top, int streak, DatabaseManager.DuelStats duel,
                              boolean hideResults, Function<String, String> msg) {
        top.setItem(DUEL_SLOT, buildDuelItem(
                DuelStatsText.render(duel, streak, hideResults, msg, value -> value + "%")));
    }

    /**
     * 主页回填的三道守卫（L3：从「静默 return」改为可诊断日志）。
     *
     * <p>三道缺一不可，是全部回填（统计 / 金币 / 成就 / 决斗）共用的唯一守卫。
     * 日志分级的理由：
     * <ul>
     *   <li>守卫 1（玩家离线）与守卫 2 的「已换界面」是<b>正常交互</b> —— 玩家关掉界面
     *       就是最常见的路径。这两类用 {@code FINE}，默认不输出，排查时开调试即可；</li>
     *   <li>守卫 2 的「holder 换了人」与守卫 3（payload 归属不符）是<b>异常</b>：
     *       前者意味着异步窗口内界面已被替换成另一个人的主页（正是跨玩家串台的形状），
     *       后者意味着 holder 实例对得上但 payload 被改过（只有 bug 能造出来）。
     *       这两类用 {@code WARNING}，因为它们出现即代表上面两道守卫的语义正在漂移。</li>
     * </ul>
     *
     * @return true = 界面仍是「打开时那一张且归属未变」，可以安全回填
     */
    private boolean stillViewing(Player viewer, UUID target, PluginGuiHolder opened) {
        if (viewer == null || !viewer.isOnline()) {
            logFine("Profile backfill dropped: viewer offline (target=" + target + ")");
            return false;
        }

        InventoryView view = viewer.getOpenInventory();
        if (view == null) {
            logFine("Profile backfill dropped: no open inventory (target=" + target + ")");
            return false;
        }
        Inventory top = view.getTopInventory();
        if (top == null) {
            logFine("Profile backfill dropped: no top inventory (target=" + target + ")");
            return false;
        }
        if (top.getHolder() != opened) {
            // L3：区分「同一张主页被重开（正常）」与「换成了别人的主页（异常）」
            if (top.getHolder() instanceof PluginGuiHolder other
                    && other.getKey() == GuiViewKey.PROFILE
                    && !(other.getPayload() instanceof PluginGuiHolder.GuiPayload.Target t
                    && t.targetId().equals(target))) {
                logWarning("Profile backfill refused: GUI belongs to another target (expected="
                        + target + ", actual=" + describePayload(other) + ")");
            } else {
                logFine("Profile backfill dropped: GUI was reopened/closed (target=" + target + ")");
            }
            return false;
        }

        // 守卫 3：界面归属仍是原目标（纵深：防止对上面的实例比对做未来重构时退化）
        if (!(opened.getPayload() instanceof PluginGuiHolder.GuiPayload.Target t)
                || !t.targetId().equals(target)) {
            logWarning("Profile backfill refused: holder payload mismatch (expected="
                    + target + ", actual=" + describePayload(opened) + ")");
            return false;
        }
        return true;
    }

    /**
     * L3 日志辅助（fine 级）：{@code plugin.getLogger()} 在纯 JVM 单测的 mock 插件上为
     * {@code null}，直接调会在守卫里 NPE —— 而这三道守卫的整条价值就是「永远不抛」。
     * 因此统一走本包装：取不到 logger 就静默（单测环境本来也没有日志出口）。
     */
    private void logFine(String message) {
        var logger = plugin.getLogger();
        if (logger != null) {
            logger.fine(message);
        }
    }

    /** L3 日志辅助（warning 级）：丢日志可以，抛异常不行。见 {@link #logFine}。 */
    private void logWarning(String message) {
        var logger = plugin.getLogger();
        if (logger != null) {
            logger.warning(message);
        }
    }

    /** L3 日志辅助：把 holder 的 payload 描述成可读文本（不含任何玩家数据）。 */
    private static String describePayload(PluginGuiHolder holder) {
        if (holder == null) return "null";
        if (holder.getPayload() instanceof PluginGuiHolder.GuiPayload.Target t) {
            return holder.getKey() + "/" + t.targetId();
        }
        return holder.getKey() + "/" + holder.getPayload().getClass().getSimpleName();
    }

    /**
     * 把 {@code DuelStatsText.render} 的输出搬进 ItemStack：第 0 项是 displayName，
     * 其余是 lore。
     *
     * @param lines 至少 1 项（{@code render} 的契约）
     * @return 展示用物品
     */
    private ItemStack buildDuelItem(List<String> lines) {
        String name = lines.isEmpty() ? "" : lines.get(0);
        List<String> lore = (lines.size() > 1)
                ? new ArrayList<>(lines.subList(1, lines.size()))
                : Collections.emptyList();
        return createItem(Material.IRON_SWORD, name, lore);
    }

    /**
     * 光标文案取值：<b>缺键返回空串</b>，而不是 {@code MessageUtil.getGuiMsg} 的
     * {@code [?key]} 占位符。
     *
     * <p>为什么单独一条：决斗块的 {@code profile.stats-duel-streak} 是可选键
     * （<b>现已落地</b>于 {@code messages/zh.yml} / {@code messages/en.yml}，
     * 但仍是「可选」语义：删掉该键时本方法必须优雅退化）。把
     * {@code [?profile.stats-duel-streak]} 写进 lore
     * 等于把内部 key 漏给玩家；空串则让 {@code DuelStatsText.render} 静默跳过该行。
     *
     * @param messages 文案配置，可为 null（返回空串）
     * @param key      键
     * @return 已转颜色码的文案；缺键时空串
     */
    private static String guiText(FileConfiguration messages, String key) {
        if (messages == null || key == null) return "";
        String raw = messages.getString(key);
        if (raw == null) return "";
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private void fillBorder(Inventory inv) {
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        // R10：slot 16 从边框让位给「决斗战绩」行（原属边框玻璃）。
        // FIX-H1（RT-H2 高危）订正：此处原注释称「MiscGUIListener 对全部 5 个 key 的
        // MOVE_TO_OTHER_INVENTORY / COLLECT_TO_CURSOR 有前置整段 cancel」—— 事实不符，
        // MiscGUIListener.isTrackedKey <b>不含 PROFILE</b>，PROFILE 归 SocialListener。
        // 当时缺的那道守卫已补在 SocialListener.onInventoryClick（InventoryAction
        // 前置例外），且 open() 末尾另有 GUIUtils.fillEmpty 把条件渲染留下的空格铺满；
        // 两层都到位后，把 16 换成有 lore 的展示物才是安全的。
        int[] borderSlots = {1,2,3,5,6,7,9,10,17,19,20,21,22,23,24,25};
        for (int slot : borderSlots) {
            inv.setItem(slot, border);
        }
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

    private ItemStack createInfoItem(Material material, String name, List<String> lore) {
        return createItem(material, name, lore);
    }
}
