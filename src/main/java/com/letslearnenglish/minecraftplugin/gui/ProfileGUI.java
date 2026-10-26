package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
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
        // 判定「我回填的是不是当初那张界面」（见 placeDuelRow）。若改读
        // inv.getHolder()，纯 JVM 单测里 mock Inventory 返回 null，回填守卫会永远
        // 静默失败；持有构造时的引用则与运行环境无关。
        PluginGuiHolder holder = PluginGuiHolder.of(GuiViewKey.PROFILE,
                new PluginGuiHolder.GuiPayload.Target(targetId));
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, title);
        PlayerDataStore.PlayerStats stats = store.getPlayerStats(targetId);
        // L-05：展示路径走只读版，缓存缺失不建条目。
        PlayerDataStore.ProfileSettings ps = store.getProfileSettingsReadOnly(targetId);
        AchievementManager am = plugin.getAchievementManager();

        // R-38：他人主页隐私 —— 目标关 showResults 则学习成绩三卡（字数/正确率/场次）
        // 只隐藏数值行；标签行保留。自己主页（isOwn）零改动。
        boolean hideResults = !isOwn && !ps.isShowResults();
        String hiddenValue = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.stats-hidden-value"));
        String ageUnsetLine = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.age-unset"));
        String ageHiddenLine = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.age-hidden"));

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

        // Stats
        int totalWords = stats.getTotalWords();
        int totalCorrect = stats.getTotalCorrect();
        int totalIncorrect = stats.getTotalIncorrect();
        int sessions = stats.getTotalSessions();
        double coins = plugin.getEconomy() == null ? 0.0
                : plugin.getEconomy().getBalance(Bukkit.getOfflinePlayer(targetId));
        // P3-01 修复（Phase 3 规划期发现，编排者即修）：原公式 correct/getTotalWords() 在
        // M10 语义下分子分母同值（getTotalWords()==totalCorrect），正确率恒显 100%。
        // 改为 correct/(correct+incorrect)，与 LLEPapiExpansion 的 %lle_accuracy% 同口径。
        int accuracy = (totalCorrect + totalIncorrect) > 0
                ? (int) ((double) totalCorrect / (totalCorrect + totalIncorrect) * 100)
                : 0;
        int unlockedCount = am.getUnlockedCount(targetId);
        int totalAchievements = Achievement.values().length;

        // Words learned — slot 11 (R-38: 他人关 showResults 时只隐藏数值行，标签保留)
        inv.setItem(11, createInfoItem(Material.BOOK,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-words-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-words-value")
                                        .replace("{words}", String.valueOf(totalWords))),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-words-detail")
                                        .replace("{correct}", String.valueOf(totalCorrect))
                                        .replace("{incorrect}", String.valueOf(totalIncorrect)))
                )));

        // Accuracy — slot 12 (R-38)
        inv.setItem(12, createInfoItem(Material.EXPERIENCE_BOTTLE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-accuracy-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-accuracy-value")
                                        .replace("{accuracy}", String.valueOf(accuracy)))
                )));

        // Age — slot 13 (R-39: 0/脏数据经 getAge 校验归 0，显示"未设置"；
        // 非本人看他人主页一律不显示年龄，与 showResults 开关无关)
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
                        : Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-sessions-value")
                                        .replace("{sessions}", String.valueOf(sessions)))
                )));

        // Coins — slot 15 (R-38: 金币余额非本人一律只隐藏数值行，标签保留)
        String coinsLine = isOwn
                ? ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "profile.stats-coins-value")
                                .replace("{coins}", plugin.getEconomy() == null ? "0"
                                        : plugin.getEconomy().format(coins)))
                : hiddenValue;
        inv.setItem(15, createInfoItem(Material.GOLD_INGOT,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-coins-label")),
                Collections.singletonList(coinsLine)));

        // Duel record — slot 16 (R10)
        // 模块门：与 slot 18 成就墙同源 gating —— duel.enabled 关闭时整行不渲染
        // （槽位留空），也<b>不派发</b>那次异步 DB 读：模块关着还每题开主页去查两张
        // 决斗表，纯属白烧 IO。历史战绩仍在库里，重新开启模块后自动恢复显示。
        if (plugin.getConfigManager().isDuelEnabled()) {
            // 主线程只放「加载中」占位；真正的取数在异步段（getDuelStats 是同步阻塞读，
            // DatabaseManager:1600 明写禁止主线程调用）。
            // 占位必须先于 openInventory 落进 inv：玩家开界面后到异步回填之间的这段时间
            // 看到的是「加载中…」而不是一个空槽（空槽=看上去这张主页没有这一行）。
            List<String> loadingLines = List.of(
                    guiText(messages, DuelStatsText.KEY_LABEL),
                    guiText(messages, DuelStatsText.KEY_LOADING));
            inv.setItem(DUEL_SLOT, buildDuelItem(loadingLines));
            scheduleDuelRow(viewer, targetId, holder, messages, hideResults);
        }

        // Achievement wall button — slot 18 (bottom-left) with count.
        // MC-2 模块门：progress-system/achievements 关闭时按钮不渲染（槽位留空）；
        // 点击侧（SocialListener）另有分支拒绝，渲染与点击同源 gating。
        if (plugin.getConfigManager().isAchievementsEnabled()) {
            String achWallLabel = ChatColor.translateAlternateColorCodes('&', "&e&l" +
                    MessageUtil.getGuiMsg(messages, "profile.stats-achievements-label"));
            String achWallCount = plugin.getMessageUtil().getPlayerMessage(viewer,
                    "profile.stats-achievements-value",
                    "unlocked", String.valueOf(unlockedCount),
                    "total", String.valueOf(totalAchievements));
            String achWallHint = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.achievements-hint"));
            inv.setItem(18, createItem(Material.PAINTING, achWallLabel,
                    Arrays.asList(achWallCount, achWallHint)));
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
        // slot 16 的「加载中」占位与异步回填（placeDuelRow 走 setItem 直写）都无影响；
        // 反过来放在前面则会被后续 setItem 覆盖成无效兜底。与 openSettings 的 R5-7
        // 同款写法（那边是 10/12 裸空，这里是 16/18）。
        GUIUtils.fillEmpty(inv);

        viewer.openInventory(inv);
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
        PlayerDataStore.ProfileSettings ps = plugin.getPlayerDataStore().getProfileSettings(playerId);

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
     * R10：异步取决斗战绩，回主线程回填 slot 16。
     *
     * <p><b>线程契约</b>：{@code getDuelStats} 是同步阻塞读，
     * {@code DatabaseManager:1600} 明写禁止主线程调用。因此这里严格两段式：异步段只做
     * DB 读，主线程段只做渲染（零 DB 调用），照 {@code ChatHistoryGUI#openAsync} 同式。
     *
     * <p><b>为什么不再查连胜</b>（FIX-H2）：{@code profile.stats-duel-streak} 键在
     * {@code messages/zh.yml} / {@code en.yml} 里都不存在，{@link DuelStatsText#render}
     * 会因空串跳过该行 —— 即「每次开主页多跑一条 50 行有界扫描，结果永远不显示」。
     * 与其留一次纯浪费的 IO，不如先摘掉查询；键落地后重新接上即可（{@code render}
     * 的 {@code streak} 形参仍在，语义未变）。
     *
     * <p><b>为什么先建界面再回填</b>（而不是先取数再建界面）：{@code open()} 必须同步
     * 走完 {@code createInventory} + {@code openInventory}，否则玩家点了按钮却什么都不
     * 发生；同时 {@code GuiOpenIdentityTest} 的 {@code verify(viewer).openInventory(same(...))}
     * 也依赖这条同步链。
     *
     * <p><b>调度器 null 守卫</b>：{@code Bukkit.getScheduler()} 在纯 JVM 单测里被
     * {@code mockStatic(Bukkit.class)} 返回 null。这里显式判空而不是让 NPE 冒出来 ——
     * 主页渲染不该因为「这一个可选信息块取不到」而整体打开失败。
     *
     * @param viewer     打开界面的玩家（回填时复检在线）
     * @param target     战绩归属玩家
     * @param opened     打开时构造的 holder 实例，用作「还是不是这张界面」的钥匙
     * @param messages   打开者语言的文案配置（异步段不读，仅捕获给主线程渲染用）
     * @param hideValues 他人主页且对方关闭 showResults 时 true（只显示标签行）
     */
    private void scheduleDuelRow(Player viewer, UUID target, PluginGuiHolder opened,
                                 FileConfiguration messages, boolean hideValues) {
        Function<String, String> msg = key -> guiText(messages, key);

        org.bukkit.scheduler.BukkitScheduler scheduler = Bukkit.getScheduler();
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) return;
                DatabaseManager.DuelStats stats;
                try {
                    DatabaseManager db = plugin.getDatabaseManager();
                    stats = db.getDuelStats(target);
                } catch (Exception e) {
                    // DB 层内部已把 SQLException 降级成 (0,0)/0（见 getDuelStats 的
                    // 异常分支），走到这里说明是接线/生命周期问题。保留「加载中」
                    // 占位而不是伪造「暂无战绩」—— 后者会把「不知道」说成「没打过」。
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to preload duel stats for " + target, e);
                    return;
                }
                try {
                    scheduler.runTask(plugin,
                            () -> placeDuelRow(viewer, target, opened, stats, msg, hideValues));
                } catch (IllegalStateException ignored) {
                    // 关服竞态：调度器已关闭，放弃本次回填（玩家也已不可能看到结果）
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：占位行留在「加载中」，界面不会露出
        }
    }

    /**
     * R10：主线程回填决斗战绩。三道守卫缺一不可。
     *
     * <p>异步段可能耗时数十毫秒，期间玩家完全可能：关掉主页、打开别人的主页、
     * 或者下线。任何一道守卫失效的后果都不是「看起来旧」，而是<b>把 A 的战绩写进
     * B 的界面</b>（跨玩家数据串台，隐私事故）。
     *
     * @param viewer  打开者
     * @param target  战绩归属
     * @param opened  打开时的 holder 实例
     * @param stats   已取到的战绩（可能为 null，{@code DuelStatsText} 内部防御）
     * @param msg     i18n 取值函数
     * @param hideValues 隐私态
     */
    private void placeDuelRow(Player viewer, UUID target, PluginGuiHolder opened,
                              DatabaseManager.DuelStats stats,
                              Function<String, String> msg, boolean hideValues) {
        // 守卫 1：玩家还在线（下线后 getOpenInventory 可能已不可用）
        if (viewer == null || !viewer.isOnline()) return;

        // 守卫 2：当前打开的仍是「打开时那张界面」——按持有者实例比对，而不是标题
        // 或 key：同 key 的两次打开（连点/来回切人）holder 值相等，必须是同一实例。
        InventoryView view = viewer.getOpenInventory();
        if (view == null) return;
        Inventory top = view.getTopInventory();
        if (top == null) return;
        if (top.getHolder() != opened) return;

        // 守卫 3：界面归属仍是原目标（纵深：防止对上面的实例比对做未来重构时退化）
        if (!(opened.getPayload() instanceof PluginGuiHolder.GuiPayload.Target t)
                || !t.targetId().equals(target)) {
            return;
        }

        top.setItem(DUEL_SLOT, buildDuelItem(
                // FIX-H2：streak 传 0 —— 本类已不再查连胜（键未落地，查了也不显示）；
                // DuelStatsText.render 在 streak <= 0 时本来就不追加连胜行，语义等价。
                DuelStatsText.render(stats, 0, hideValues, msg, value -> value + "%")));
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
     * （当前语言文件里尚未落地）。把 {@code [?profile.stats-duel-streak]} 写进 lore
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
