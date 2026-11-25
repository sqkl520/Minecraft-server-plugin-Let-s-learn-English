package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeProgress;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.WordChallengeProgress;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.ai.ScenarioCodec;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.core.word.WordModes;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.data.repository.PlayerDirectoryRepository;
import com.letslearnenglish.minecraftplugin.gui.ChatHistoryGUI;
import com.letslearnenglish.minecraftplugin.gui.ClassBoardGUI;
import com.letslearnenglish.minecraftplugin.gui.EnglishSquareGUI;
import com.letslearnenglish.minecraftplugin.gui.LeaderboardGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordBankSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learn Command - Main player command for English learning.
 *
 * Subcommands:
 * - /le menu        Open the main learning menu
 * - /le word        Start word learning
 * - /le dialogue    Start dialogue practice
 * - /le progress    View learning progress
 * - /le review      Start review session
 * - /le stats       View learning statistics
 * - /le help        Show help message
 */
public class LearnCommand implements CommandExecutor, TabCompleter {

    private final LetsLearnEnglish plugin;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "menu", "word", "dialogue", "progress", "review", "stats", "help", "guide", "square", "profile", "leaderboard", "challenge", "history", "duel", "wrong", "checkin", "season", "class", "save_scenario", "load_scenario"
    );

    /**
     * R-40：庆祝页"今日已展示"去重（内存态，按自然日键）。
     * 同一玩家同一天只自动弹一次；跨天键不同自动失效，无需定时清理。
     */
    private final Map<UUID, java.time.LocalDate> celebrationShownDates = new ConcurrentHashMap<>();

    public LearnCommand(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * MC-2 模块门统一回显（plan-menu-config §3.2）：enabled=false 时发
     * {@code module.disabled} 并返回 true（调用方直接 return）；enabled=true 返回 false。
     * 门全部点读 ConfigManager（reload 即时生效），命令入口是"最省的一层"。
     */
    private boolean requireEnabled(Player player, boolean enabled) {
        if (enabled) {
            return false;
        }
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "module.disabled"));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            // 控制台唯一的放行口：`/le stats <玩家名>`（纯文本战绩，见 handleTextStatsCommand）。
            // 为什么只能在守卫**之前**插一个短路分支、而不是放宽守卫本身：这个
            // player-only 判定是几十个子命令的共同保护（它们全都要 Player 对象去开 GUI /
            // 写会话状态），一旦整体放开，控制台敲 `/le word` 就会在强转处炸。
            // 这里只把「有目标参数的那一个子命令」放行，爆炸半径恰好是一行。
            if (isTextStatsRequest(args)) {
                handleTextStatsCommand(sender, args);
                return true;
            }
            sender.sendMessage(plugin.getMessageUtil().getMessage("general.player-only"));
            return true;
        }

        // 子服总开关（server.enabled=false）：/le 及全部别名、全部子命令无条件拒绝。
        // 控制台在上一行就被挡下（本命令本就只服务玩家），因此这里只需覆盖玩家侧。
        // 拦在 args 判空**之前**：无参 /le 原本会直接 openMainMenu，而关闭态下
        // MainMenu 依赖的 wordManager / socialManager 等一律为 null，必须在这里截住。
        // 提示用 server.disabled（i18n 键，见 messages/zh.yml + en.yml）。
        if (!plugin.isFullyEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "server.disabled"));
            return true;
        }

        if (args.length == 0) {
            openMainMenu(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "menu":
                openMainMenu(player);
                break;
            case "word":
                handleWordCommand(player, args);
                break;
            case "dialogue":
                handleDialogueCommand(player, args);
                break;
            case "progress":
                handleProgressCommand(player);
                break;
            case "review":
                handleReviewCommand(player);
                break;
            case "stats":
                // 带目标玩家的 `/le stats <玩家名>` 走纯文本战绩（玩家互查用，与
                // 控制台/QQ 侧同一条链路）；无参形态一字不动，仍是 ProfileGUI。
                if (isTextStatsRequest(args)) {
                    handleTextStatsCommand(player, args);
                } else {
                    handleStatsCommand(player);
                }
                break;
            case "square":
                handleSquareCommand(player, args);
                break;
            case "profile":
                handleProfileCommand(player, args);
                break;
            case "leaderboard":
                handleLeaderboardCommand(player);
                break;
            case "challenge":
                handleChallengeCommand(player);
                break;
            case "history":
                handleHistoryCommand(player);
                break;
            case "duel":
                handleDuelCommand(player, args);
                break;
            case "wrong":
                handleWrongCommand(player);
                break;
            case "checkin":
                handleCheckinCommand(player);
                break;
            case "season":
                handleSeasonCommand(player);
                break;
            case "class":
                handleClassCommand(player);
                break;
            case "save_scenario":
                handleSaveScenarioCommand(player, args);
                break;
            case "load_scenario":
                handleLoadScenarioCommand(player, args);
                break;
            case "help":
                sendHelpMessage(player);
                break;
            case "guide":
                // 域 1 新手引导：进度总览 + 手册补发（enabled/完结分支在管理器内自判）。
                plugin.getOnboardingManager().handleGuideCommand(player);
                break;
            default:
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "general.unknown-command"));
                break;
        }

        return true;
    }

    private void openMainMenu(Player player) {
        new MainMenu(plugin).open(player);
    }

    private void handleWordCommand(Player player, String[] args) {
        // MC-2 模块门：word-system.enabled=false → /le word 整体拒绝（含无参打开词库选择 GUI）。
        if (requireEnabled(player, plugin.getConfigManager().isWordSystemEnabled())) {
            return;
        }
        if (args.length <= 1) {
            // MC-3：三模式全关时词库选择 GUI 的每次点击都必然被拒（无可用模式），
            // 无参 /le word 直接回 word.no-modes-enabled（验收 5.(c).3），不再开死界面。
            if (WordModes.firstEnabledMode(plugin.getConfigManager()) == null) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.no-modes-enabled"));
                return;
            }
            new WordBankSelectGUI(plugin).open(player);
        } else {
            // D7：/le word 的合法形态只有 `<难度>` 与 `<难度> <模式>` 两种 —— 与
            // onTabComplete 的补全层数同源（args.length==2 补难度、==3 补模式、>=4 无候选）。
            // 在此之前 args.length > 3 的多余 token 被**静默丢弃**：玩家从别处抄来
            // `/le word beginner choice 3` 想「来 3 题」，实际走的是默认题数，既不报错
            // 也不提示，等于骗了玩家一次。这里显式拒绝并把多余的 token 原样回显。
            // 位置说明：参数**语法**错误先于难度/模式的**语义**校验判定 —— 先告诉玩家
            // 「你多敲了词」，再让他去改词；否则 `/le word xyz choice 3` 会先报
            // invalid-difficulty，玩家改完难度又被 extra-arguments 打脸第二次。
            if (args.length > 3) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.extra-arguments",
                        "extra", String.join(" ", Arrays.copyOfRange(args, 3, args.length)),
                        "usage", plugin.getMessageUtil().getPlayerMessage(player, "word.usage")));
                return;
            }
            // R-86c：difficulty 先归一化再校验存在性（大小写不敏感匹配后解析回词库
            // 规范 key）。原先直接透传原始输入，大小写不符 / 难度不存在都会抛英文
            // IllegalArgumentException 给玩家。
            String normalizedDifficulty = args[1].toLowerCase(Locale.ROOT);
            // MC-3：默认模式（无参）从固定常量改为 WordModes.firstEnabledMode ——
            // Wave 2 裁决：优先 choice（FEAT-MODE-01 新手默认），choice 被关时按
            // spelling→fill_blank 回落；全关时拒绝（word.no-modes-enabled）。
            String mode;
            if (args.length > 2) {
                mode = args[2].toLowerCase(Locale.ROOT);
                // MC-3 模式门（R-55f）：先辨"未知模式"（invalid-mode，信息里带可用列表）
                // 与"已知但被管理员关闭"（word.mode-disabled）两种拒绝语义，再放行。
                if (!WordModes.isKnownMode(mode)) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "word.invalid-mode", "mode", args[2]));
                    return;
                }
                if (!WordModes.isEnabled(plugin.getConfigManager(), mode)) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "word.mode-disabled"));
                    return;
                }
            } else {
                mode = WordModes.firstEnabledMode(plugin.getConfigManager());
                if (mode == null) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "word.no-modes-enabled"));
                    return;
                }
            }
            String difficulty = resolveDifficulty(normalizedDifficulty);
            if (difficulty == null) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.invalid-difficulty",
                        "options", String.join(", ",
                                plugin.getWordManager().getAvailableDifficulties())));
                return;
            }
            startWordQuiz(player, difficulty, mode);
        }
    }

    /**
     * R-86c：把归一化后的难度输入解析回 {@link com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl}
     * 的规范难度 key（词库名可能带自定义大小写）；不存在返回 null。
     */
    private String resolveDifficulty(String normalizedInput) {
        for (String candidate : plugin.getWordManager().getAvailableDifficulties()) {
            if (candidate.toLowerCase(Locale.ROOT).equals(normalizedInput)) {
                return candidate;
            }
        }
        return null;
    }

    private void startWordQuiz(Player player, String difficulty, String mode) {
        // C1-N1 收口（R2-M4 同族，存量守卫缺口）：决斗中不开新答题会话 —— 决斗分支
        // 优先吞全部聊天（ChatAnswerListener），答题输入静默超时判错。决斗判先于
        // 对话判，与 handleDialogueCommand 的守卫顺序一致。
        var duelManager = plugin.getDuelManager();
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.finish-duel-first"));
            return;
        }
        // R-66 反向（P2 编排者 fan-in）：AI 对话进行中不开新答题会话 ——
        // ChatScenarioListener 以 HIGH 优先级消费聊天，答题输入会被 AI 侧拦走
        // （与"答题中开对话"对称的饥饿态）。先提示结束对话（冻结键 chat-active）。
        if (plugin.getAIChatManager().hasActiveConversation(player)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.chat-active"));
            return;
        }
        // 域 1 新手引导拦截：step==1 的"首次答题"改开 3 词迷你会话（接管开局则直接返回）。
        if (plugin.getOnboardingManager().interceptQuizStart(player, difficulty)) {
            return;
        }
        try {
            var session = plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
            // Wave 1 fan-in（S-F 上报纵深）：word 门关闭时 startQuiz 返回 null 已发
            // module.disabled——此处不再开 GUI / 发 session-start（子命令入口已有门，
            // 这是"僵尸 GUI/边缘路径"的兜底）。
            if (session == null) {
                return;
            }
            new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
            // F2-13：{difficulty}/{mode} 的值走 i18n label 键，不再把英文标识符
            // （beginner/choice）直接透传给玩家；未知标识符回落原值保证信息不丢。
            // MC-3：default 用 WordModes 单一实现（PF-MC-11 收口：原本类私有
            // localizeWordMode 删除）。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.session-start",
                    "difficulty", WordModes.localizeDifficulty(plugin.getMessageUtil(), player, difficulty),
                    "mode", WordModes.localizeMode(plugin.getMessageUtil(), player, mode)));
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            // S4：难度存在但无词可学（selectWordsForSession 空）时 WordManagerImpl 抛
            // IllegalStateException，原先冒泡出 onCommand —— 玩家零反馈。改为 i18n 提示。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.no-words-available"));
        }
    }

    private void handleDialogueCommand(Player player, String[] args) {
        // MC-2 模块门：ai-chat.enabled=false → /le dialogue 整体拒绝
        //（含"退出对话"分支——对话根本开不了；历史/场景维护面不属本门）。
        if (requireEnabled(player, plugin.getConfigManager().isAIChatEnabled())) {
            return;
        }
        if (plugin.getChatScenarioListener() == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.scenario-unavailable")));
            return;
        }
        if (plugin.getChatScenarioListener().isInScenarioMode(player)) {
            // PF-FB-01 去重（US-FB 3B）：ended 文案与小结卡统一由 removePlayer →
            // handleEndConversation 流程发送（此前此处重复补发 ended）。
            plugin.getChatScenarioListener().removePlayer(player);
            return;
        }
        if (args.length > 1) {
            // 直接开始指定场景
            String scenario = resolveScenarioKey(player, args[1].toLowerCase(Locale.ROOT));
            // S4/R-86e 场景校验：key 不存在时原先静默以通用 prompt 兜底并回显原始
            // key。resolveScenarioKey 先查玩家可见全集（getScenarioConfigs 会懒加载
            // 个人场景，personal: 前缀），与 GUI 路径的可见范围完全一致；大小写
            // 不敏感匹配后解析回规范 key，再判不存在才拒绝。
            if (scenario == null) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "dialogue.scenario-not-found", "key", args[1]));
                return;
            }
            // R11-L3：命令路径补 AI 可用性预检（照 ScenarioSelectListener:121-129 先例）。
            // 原先命令路径直接开始对话，玩家看到"已开启"后每条消息才被
            // dialogue.ai-not-configured 打脸；GUI 路径会拦、命令路径放行，双入口语义漂移。
            if (!plugin.getAIChatManager().isAvailable()) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "dialogue.ai-not-configured"));
                return;
            }
            // S4 互斥：答题 / 错题练习 / 决斗 / 已有 AI 对话进行中时不重复开聊
            // （/le dialogue 的退出语义在上面 isInScenarioMode 分支已提前处理，不受影响）。
            // 决斗优先判：决斗中本身就有答题态，报"先结束对决"比报"先结束答题"更准确。
            var duelManager = plugin.getDuelManager();
            if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "duel.finish-duel-first"));
                return;
            }
            var wordManager = plugin.getWordLearningManager();
            if (wordManager != null
                    && (wordManager.hasActiveQuiz(player.getUniqueId())
                            || wordManager.isPracticingWord(player.getUniqueId()))) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "dialogue.finish-quiz-first"));
                return;
            }
            if (plugin.getAIChatManager().hasActiveConversation(player)) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "dialogue.chat-active"));
                return;
            }
            plugin.getChatScenarioListener().addPlayer(player);
            plugin.getAIChatManager().startConversation(player, scenario);
            // R-04：场景名按玩家版解析（全局 + 个人场景），与 GUI 入口保持一致
            // （原先只查全局配置，个人 key 会回落显示原始标识符）
            String scenarioName = plugin.getAIChatManager().getScenarioDisplayName(player, scenario);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.started", "scenario", scenarioName));
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.instructions"));
        } else {
            new ScenarioSelectGUI(plugin).open(player);
        }
    }

    /**
     * S4：把归一化（小写）的场景输入解析回玩家可见场景的规范 key ——
     * 全局 + 个人场景大小写不敏感匹配（个人场景 key 前缀 personal:）；
     * 不存在返回 null。getScenarioConfigs(player) 内部会懒加载个人场景文件。
     */
    private String resolveScenarioKey(Player player, String normalizedInput) {
        for (String candidate : plugin.getAIChatManager().getScenarioConfigs(player).keySet()) {
            if (candidate.toLowerCase(Locale.ROOT).equals(normalizedInput)) {
                return candidate;
            }
        }
        return null;
    }

    private void handleProgressCommand(Player player) {
        // R8-M1 收口（C1-A 范围外面）：/le progress 同为 ProfileGUI 直开——
        // 同式压「主菜单」帧，防返回键消费无关残留帧。
        plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                () -> new MainMenu(plugin).open(player));
        new ProfileGUI(plugin).open(player, player.getUniqueId());
    }

    private void handleReviewCommand(Player player) {
        // MC-2 模块门：review 属 word-system 域（关闭后复习提醒任务亦静默，本处拦手动入口）。
        if (requireEnabled(player, plugin.getConfigManager().isWordSystemEnabled())) {
            return;
        }
        // N1 收口：复习同属答题链——决斗中聊天会被决斗吞掉，复习答题将静默超时，
        // 决斗优先判（与 startWordQuiz / 词库点击 / 练习入口同式）。
        var reviewDuelManager = plugin.getDuelManager();
        if (reviewDuelManager != null && reviewDuelManager.isInDuel(player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.finish-duel-first"));
            return;
        }
        java.util.List<String> dueWords = plugin.getWordManager()
                .getReviewScheduler().getDueReviewWords(player.getUniqueId());
        int count = dueWords.size();
        if (count > 0) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "review.notification", "count", String.valueOf(count)));
            com.letslearnenglish.minecraftplugin.core.word.LearningSession session =
                    plugin.getWordLearningManager().startReviewQuiz(player);
            if (session != null) {
                new com.letslearnenglish.minecraftplugin.gui.WordLearningGUI(plugin)
                        .openWithMessage(player, "review", "review");
                // {difficulty}/{mode} 的值走 i18n 键，不再写死中文
                String reviewLabel = plugin.getMessageUtil().getPlayerMessage(player, "word.mode-review");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.session-start", "difficulty", reviewLabel, "mode", reviewLabel));
            } else if (!plugin.getWordLearningManager().hasActiveQuiz(player.getUniqueId())
                    && !plugin.getWordLearningManager().isPracticingWord(player.getUniqueId())) {
                // R-12：有复习通知却开不出局 —— 词库被删词后，到期词在词库里已不存在，
                // startReviewQuiz 返回 null。原先静默无事（玩家被"你有 N 个待复习"晃一下
                // 却点不动），改为明确告知"部分复习词已失效"，不再无解释地吞掉。
                // R1-M1/M2 收口：会话进行中 / 错题练习中的 null 是入口守卫拒入，
                // 守卫已发 word.session-in-progress / word.practice-in-progress ——
                // 不再补发本键（双发且误导）。
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "review.unavailable"));
            }
        } else {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "review.no-words"));
        }
    }

    private void handleStatsCommand(Player player) {
        // R8-M1 收口（C1-A 范围外面）：同 handleProgressCommand——直开主页压帧。
        plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                () -> new MainMenu(plugin).open(player));
        new ProfileGUI(plugin).open(player, player.getUniqueId());
    }

    /**
     * `stats` 子命令是否走**纯文本**战绩分支：必须带目标玩家名。
     *
     * <p>无参的 `/le stats` 一字不动（仍是 {@link ProfileGUI}，见 {@link #handleStatsCommand}）——
     * 那条路径服务于「玩家看自己」，与「查某个人的战绩」是两种诉求，不该互相污染。
     */
    private static boolean isTextStatsRequest(String[] args) {
        return args != null && args.length >= 2 && "stats".equalsIgnoreCase(args[0]);
    }

    /**
     * `/le stats &lt;玩家名&gt;` 的纯文本战绩查询 —— 控制台 / QQ 机器人 / 玩家互查共用一条链路。
     *
     * <p><b>为什么需要它</b>：无参形态开 GUI，而 GUI 对控制台毫无意义，也回传不了 QQ 群。
     * 服主通过 HuHoBot 一类扩展以**控制台身份**执行命令并转发输出，所以这条路径必须
     * ① 不为非玩家发送者开任何界面、② 输出里不带颜色码（QQ 侧显示 `§6` 是乱码）、
     * ③ 不依赖 `{prefix}`。
     *
     * <p><b>线程</b>：`onCommand` 跑在主线程。这里的三次取数都可能落到 JDBC
     * （`resolveUuidByName` 是 SELECT；`getPlayerStats` / `getUnlockedCount` /
     * `loadProfileSettingsForDisplay` 缓存 miss 时各是一次主键查询），因此**全部**
     * 挪进 `runTaskAsynchronously`，结果经 {@link #runTaskSafe} 回主线程渲染 ——
     * 与 {@code handleChallengeCommand} / {@code handleClassCommand} 同一形状。
     * 远程 MySQL 抖动时一次 `/le stats` 就能把主线程钉住 30 秒，而功能表现完全正常。
     *
     * <p><b>隐私门（与 PAPI 侧同一口径）</b>：非本人查看时叠两道，任一命中都只输出
     * 「已隐藏」，且**不去读统计值**（不是取到再丢弃 —— 那种写法一旦被改错就会真泄露）：
     * <ol>
     *   <li>门禁 1 `showInSquare=false`：照 {@code LLEPapiExpansion.resolveRequest} 的
     *       隐身者判定 —— 该开关的语义是「他人能不能看到我」，成绩同样属于「关于我」；</li>
     *   <li>门禁 2 `showResults=false`：即 {@code LLEPapiExpansion.RESULTS_GATED} 那一族
     *       （score / words / accuracy / sessions / correct / incorrect / achievements）。
     *       本命令输出的六个字段**逐个**都在该集合内，所以整条命令受它约束，
     *       而不是像 PAPI 那样按占位符逐键判。</li>
     * </ol>
     * 控制台（含 QQ 机器人）与目标**不同**于任何人的视角，但它是管理员/机器人通道，
     * 刻意**不套**这两道门 —— 否则服主自己都查不了。判定只发生在「执行者是玩家」时。
     */
    private void handleTextStatsCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendTextStatsKey(sender, StatsTextFormatter.KEY_USAGE);
            return;
        }
        if (args.length > 2) {
            // D7 同族：多余 token 不得静默丢弃。玩家从别处抄来 `/le stats Bob 7d`
            // 会以为时间窗生效了，实际查的是全量 —— 宁可回一句用法。
            sendTextStatsKey(sender, StatsTextFormatter.KEY_USAGE);
            return;
        }

        // 子服总开关：控制台的短路分支排在 onCommand 的 player-only 守卫**之前**，
        // 因此它绕过了那条路径上的 isFullyEnabled 判定。关闭态下 databaseManager /
        // playerDataStore / achievementManager 一律为 null，必须在这里先拦一道。
        if (!plugin.isFullyEnabled()) {
            sendTextStatsKey(sender, "server.disabled");
            return;
        }

        final String targetName = args[1];
        // 在线玩家查名册（主线程专属 API，且零 IO）；在线名优先用规范大小写，
        // 离线时回显调用方输入 —— 异步段里取不到 Bukkit 名册。
        final Player online = Bukkit.getPlayerExact(targetName);
        final UUID onlineId = (online != null) ? online.getUniqueId() : null;
        final String displayName = (online != null) ? online.getName() : targetName;
        // 查看者身份在主线程定好：控制台（含 QQ 机器人）没有 UUID ⇒ 非本人视角。
        final UUID viewerId = (sender instanceof Player viewer) ? viewer.getUniqueId() : null;

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                final UUID targetId;
                try {
                    if (onlineId != null) {
                        targetId = onlineId;
                    } else {
                        // 离线查名唯一入口：player_data 的名字目录（player_name 有索引，
                        // 见 DatabaseManager 的 Block C2）。
                        // 刻意**不**走 Bukkit.getOfflinePlayer(name).hasPlayedBefore()：
                        // 那是会阻塞的名册/网络查询，AdminCommandHandler 只在主线程用它。
                        targetId = new PlayerDirectoryRepository(plugin.getDatabaseManager(),
                                plugin.getLogger()).resolveUuidByName(targetName);
                    }
                } catch (RuntimeException e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Failed to resolve player for /le stats: " + targetName, e);
                    runTaskSafe(() -> sendTextStatsKey(sender, StatsTextFormatter.KEY_LOAD_FAILED));
                    return;
                }

                if (targetId == null) {
                    runTaskSafe(() -> sendTextStatsKey(sender,
                            StatsTextFormatter.KEY_PLAYER_NOT_FOUND, "player", targetName));
                    return;
                }

                final PlayerDataStore store = plugin.getPlayerDataStore();
                final boolean selfView = viewerId != null && viewerId.equals(targetId);
                final StatsTextFormatter.StatsSnapshot snapshot;
                try {
                    // L-05/P1-1：展示路径用 loadProfileSettingsForDisplay（miss 时同步查一行，
                    // 无行=新玩家→默认公开），不能用 getProfileSettingsReadOnly ——
                    // 后者 miss 的 fail-closed 会把**所有离线玩家**判成「已隐藏」，
                    // 与 PAPI 侧当年踩过的坑逐字相同。
                    PlayerDataStore.ProfileSettings settings =
                            store.loadProfileSettingsForDisplay(targetId);
                    if (!selfView && (!settings.isShowInSquare() || !settings.isShowResults())) {
                        // 隐私优先：不取任何统计值，直接回「已隐藏」（连「打过几题」都不透露）。
                        // 用 renderHidden 而不是 render(null, true, ...)：后者的形参里还留着
                        // 一个「战绩」，将来有人把真快照塞进去就会泄露 —— 类型上断开这条退路。
                        runTaskSafe(() -> sendTextStatsLines(sender,
                                StatsTextFormatter.renderHidden(displayName,
                                        textStatsMessages(sender))));
                        return;
                    }

                    PlayerDataStore.PlayerStats stats = store.getPlayerStats(targetId);
                    // DC-01：读失败后补的空壳四字段全 0，把它当真值发出去就是「报了一个
                    // 看起来很可信的假数」—— 宁可回一句取数失败。
                    if (stats == null || store.isStatsUnloaded(targetId)) {
                        runTaskSafe(() -> sendTextStatsKey(sender,
                                StatsTextFormatter.KEY_LOAD_FAILED));
                        return;
                    }

                    var achievements = plugin.getAchievementManager();
                    // achievements 为 null 只可能是运行时已拆（上面 isFullyEnabled 已拦），
                    // 这里只做防御：不解引用，宁可少一行成就数。
                    int unlocked = (achievements != null)
                            ? achievements.getUnlockedCount(targetId)
                            : 0;
                    snapshot = new StatsTextFormatter.StatsSnapshot(
                            displayName,
                            stats.getTotalScore(),
                            stats.getTotalCorrect(),
                            stats.getTotalIncorrect(),
                            stats.getTotalSessions(),
                            unlocked,
                            Achievement.values().length);
                } catch (RuntimeException e) {
                    // 异步段不得直调 Bukkit API：切回主线程提示 + 记日志。
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Failed to load /le stats data for " + targetId, e);
                    runTaskSafe(() -> sendTextStatsKey(sender, StatsTextFormatter.KEY_LOAD_FAILED));
                    return;
                }

                runTaskSafe(() -> {
                    // 异步间隙玩家可能已下线（控制台不会）—— 渲染前复检，
                    // 与 handleChallengeCommand / handleClassCommand 同一条守卫。
                    if (sender instanceof Player p && !p.isOnline()) {
                        return;
                    }
                    sendTextStatsLines(sender, StatsTextFormatter.render(
                            snapshot, textStatsMessages(sender)));
                });
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次异步查询
        }
    }

    /**
     * 主线程发送一行 i18n 文案（语言按发送者解析）。
     *
     * <p><b>刻意不用 {@code MessageUtil.getSenderMessage}</b>：它对非玩家发送者**写死**
     * {@code lang = "en"}，于是中文服的服主通过控制台/QQ 机器人查战绩时会拿到英文文案。
     * 这里改用 {@code getMessage}（服务端默认语言，即 config.yml 的
     * {@code general.language}）—— 控制台代表的正是「这台服」，不是某个玩家。
     */
    private void sendTextStatsKey(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(statsText(sender, key, placeholders));
    }

    /** 主线程逐行发送（已在 {@link StatsTextFormatter} 渲染完成，此处零加工）。 */
    private void sendTextStatsLines(CommandSender sender, List<String> lines) {
        for (String line : lines) {
            sender.sendMessage(line);
        }
    }

    /**
     * 战绩文案的取值函数：把 {@code MessageUtil} 适配成 {@link StatsTextFormatter}
     * 需要的 {@code key → 文案}。
     *
     * <p>只在**主线程**调用（{@code runTaskSafe} 的回调里 / 命令入口），
     * MessageUtil 要按语言挑一份 FileConfiguration，与命令层其余取文案的位置一致。
     */
    private java.util.function.Function<String, String> textStatsMessages(CommandSender sender) {
        return key -> statsText(sender, key);
    }

    /**
     * 单条文案取值的唯一实现：玩家走自己的语言，其他发送者走服务端默认语言。
     *
     * @see #sendTextStatsKey(CommandSender, String, String...) 中关于「为什么不用
     *      getSenderMessage」的说明
     */
    private String statsText(CommandSender sender, String key, String... placeholders) {
        return (sender instanceof Player p)
                ? plugin.getMessageUtil().getPlayerMessage(p, key, placeholders)
                : plugin.getMessageUtil().getMessage(key, placeholders);
    }

    /**
     * /le square [feed|weekly|monthly|alltime|all]（Wave 2 域 4）：无参 / 非法值
     * 走默认落点（{@code EnglishSquareGUI.open} 读 {@code english-square.default-mode}，
     * 缺省 feed——两处不重复读配置）；显式模式直达对应页。榜单/social 模块门
     * 的回落由 GUI open 内的防御分支统一处理（本方法不重复判门）。
     */
    private void handleSquareCommand(Player player, String[] args) {
        RankingMode mode = null;
        if (args.length > 1) {
            mode = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "feed" -> RankingMode.FEED;
                case "weekly" -> RankingMode.WEEKLY;
                case "monthly" -> RankingMode.MONTHLY;
                case "alltime" -> RankingMode.ALL_TIME_COINS;
                case "all" -> RankingMode.ALL_PLAYERS;
                default -> null; // 非法值忽略，开默认落点（plan-social §3.4）
            };
        }
        // R4-M1：走 SocialListener 登记快照的入口（直达窗口点头像不再现查库重建）
        plugin.getSocialListener().openSquareWithSnapshot(player, mode);
    }

    private void handleLeaderboardCommand(Player player) {
        // MC-2 模块门：progress-system.enabled && leaderboard.enabled。
        if (requireEnabled(player, plugin.getConfigManager().isLeaderboardEnabled())) {
            return;
        }
        // F3-01 收口（旅程验收）：此前直开 LeaderboardGUI 不登记快照，玩家点头像
        // 静默无反应（点击侧要求 SocialListener.leaderboardGuis 快照非空）——改走
        // 登记式打开（GUI 与快照同刻，主线程）。
        plugin.getSocialListener().openLeaderboardWithSnapshot(player);
    }

    private void handleProfileCommand(Player player, String[] args) {
        if (args.length > 1) {
            String targetName = args[1];
            Player target = plugin.getServer().getPlayer(targetName);
            if (target != null) {
                // R-05：/le profile 直达同样查开关 —— 目标隐身且非本人时回 i18n 提示，不开界面。
                // L-写路径收口：纯读判定走只读版。旧 getProfileSettings 会在缓存 miss 时
                // computeIfAbsent 合成「全公开」条目并留在缓存里（getActivePlayerIds 取键集），
                // 等于「看一眼 /le profile 就把隐私未知的玩家重新放回广场候选集」。
                if (!target.getUniqueId().equals(player.getUniqueId())
                        && !plugin.getPlayerDataStore().getProfileSettingsReadOnly(target.getUniqueId()).isShowInSquare()) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "profile.hidden"));
                } else {
                    // R8-M1：命令直开主页同样压「主菜单」帧（返回键不再消费无关残留帧）。
                    plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                            () -> new MainMenu(plugin).open(player));
                    new ProfileGUI(plugin).open(player, target.getUniqueId());
                }
            } else {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "general.error", "message",
                        plugin.getMessageUtil().getPlayerMessage(player, "error.player-not-found")));
            }
        } else {
            // R8-M1：命令直开本人主页同样压「主菜单」帧（返回键回到主菜单，不再
            // 消费无关残留帧跳去无关页）。与上方 /le profile <target> 分支同式。
            plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            new ProfileGUI(plugin).open(player, player.getUniqueId());
        }
    }

    /**
     * R31：{@code /le challenge} 的<b>主线程段</b>只做模块门与配置判定（零 JDBC），
     * 四次查库全部挪进 {@code runTaskAsynchronously} 的 lambda，结果经
     * {@link #runTaskSafe} 回主线程由 {@link #renderChallenge} 渲染。
     *
     * <p><b>为什么必须异步（R31 缺陷 1）</b>：本方法原实现在主线程同步走了四条 JDBC 链路 ——
     * {@code getTodayWordProgress} → {@code getDailyChallengeWordsProgress}、
     * {@code getTodayWordDetails} → {@code loadDailyChallengeWords}（+ 逐词
     * {@code isWordCompleted}）、{@code isWordRewardPaidToday} →
     * {@code isDailyWordRewardClaimed}、{@code getTodayChallenges} →
     * {@code findTodayChallenges}。远程 MySQL 抖动时一次 {@code /le challenge}
     * 就能把主线程钉住（{@code socketTimeout=30000} ×4），
     * <b>全服 TPS 跟着这个命令走</b>，而功能表现完全正常 —— 这类退化没有任何单测能观测。
     * 现由 {@code MainThreadIoContractTest}（已扩展扫 {@code command/}）静态守住。
     *
     * <p><b>渲染决策全部前置到主线程</b>（{@code reward} / {@code economyAvailable}）：
     * 它们读的是内存配置与 Vault 句柄，零 IO；放在异步段反而要跨线程读插件状态。
     * 与 {@code handleClassCommand} 同一形状（赛季窗口也在主线程先算好）。
     *
     * <p><b>取数顺序刻意保留原样</b>：{@code total == 0} 时不再读后三个 ——
     * {@code getTodayWordDetails} 内部会走 {@code ensureWordChallengesAssigned}
     * （可能触发一次分配写入），阅读顺序若变，一条"没有挑战"的只读展示就可能
     * 变成一次分配尝试。异步化只改线程，不改语义。
     */
    private void handleChallengeCommand(Player player) {
        // MC-2 模块门：daily-challenges.enabled=false → /le challenge 拒绝。
        if (requireEnabled(player, plugin.getConfigManager().isDailyChallengesEnabled())) {
            return;
        }
        // 主线程段（零 IO）：奖励金额与经济可用性都是内存读，且决定渲染分支。
        final double reward = getWordChallengeReward();
        final boolean economyAvailable = isEconomyAvailable();
        final UUID playerId = player.getUniqueId();

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                final int[] progress;
                final List<WordChallengeProgress> details;
                final boolean paid;
                final List<ChallengeProgress> challenges;
                try {
                    var dcm = plugin.getDailyChallengeManager();
                    progress = dcm.getTodayWordProgress(player);
                    if (progress[1] == 0) {
                        // 无挑战词：后三次查询原实现也不会执行，这里同样跳过。
                        details = List.of();
                        paid = false;
                        challenges = List.of();
                    } else {
                        details = dcm.getTodayWordDetails(player);
                        paid = dcm.isWordRewardPaidToday(playerId);
                        challenges = dcm.getTodayChallenges(playerId);
                    }
                } catch (RuntimeException e) {
                    // 异步段不得直调 Bukkit API：切回主线程提示 + 记日志（照抄 DailyChallengeGUI.openAsync）。
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Failed to load /le challenge data for " + playerId, e);
                    runTaskSafe(() -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                                    "daily.load-failed"));
                        }
                    });
                    return;
                }
                runTaskSafe(() -> {
                    // 异步间隙玩家可能已下线 —— 渲染前复检（同 handleClassCommand 的 4A tester L-1 收口）。
                    if (!player.isOnline()) {
                        return;
                    }
                    renderChallenge(player, progress, details, paid, challenges,
                            reward, economyAvailable);
                });
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次异步查询
        }
    }

    /**
     * R31：{@code /le challenge} 的<b>主线程渲染段</b> —— 数据由
     * {@link #handleChallengeCommand} 预取，本方法零 DB 调用。
     *
     * <p>顺序与原实现逐行一致（先 header、再词表、再庆祝提示、再行为挑战、最后庆祝页），
     * 因为玩家读到的段落顺序就是这段代码的顺序。
     */
    private void renderChallenge(Player player, int[] progress,
                                 List<WordChallengeProgress> details, boolean paid,
                                 List<ChallengeProgress> challenges,
                                 double reward, boolean economyAvailable) {
        int completed = progress[0];
        int total = progress[1];

        if (total == 0) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.no-words"));
            return;
        }

        // Show overall progress header
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "daily-challenge.header",
                "completed", String.valueOf(completed),
                "total", String.valueOf(total)));

        // Show individual word status
        for (var wordProgress : details) {
            String statusKey = wordProgress.completed
                    ? "daily-challenge.word-done"
                    : "daily-challenge.word-pending";
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    statusKey, "word", wordProgress.wordId));
        }

        // R-07: celebrate only on "all done AND money arrived". Word rewards are
        // paid asynchronously-ish (M12 deposit-then-mark), so completion alone
        // must not trigger the "reward granted" message.
        boolean allDone = completed >= total && total > 0;
        if (allDone && paid) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.all-complete-chat"));
        } else if (allDone) {
            // Completed but payout pending (e.g. Vault missing) — tell the
            // player the coins are parked and how to retry; no celebration.
            // R-44: money.enabled=false is NOT a "pending retry" case — the
            // coins will never arrive, so hint "economy disabled" instead of
            // promising an auto re-issue that can never happen.
            if (!economyAvailable) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "daily-challenge.economy-disabled"));
            } else {
                // R-16 残留①（同款顺手修）：这里的 {reward} 是「暂存待补发」的金额，
                // 来源是 daily-challenges.word-challenge-reward 这个 double 配置项。
                // 原 (int) 强转下，管理员把它配成 99.9 就会告诉玩家「99 金币暂存中」，
                // 而补发时实际到账 99.9 —— 又是「报的数与落账的数不同源」。
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "daily-challenge.reward-pending",
                        "reward", formatCoins(reward)));
            }
        }

        // FIX-CHAL-01: cross-visibility (read-only) — show behavioral challenge
        // progress right below the word list so players can see both systems
        // from one entry point. Claim path stays inside DailyChallengeGUI.
        if (!challenges.isEmpty()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.behavioral-header"));
            for (var cp : challenges) {
                String statusKey = cp.claimed
                        ? "daily-challenge.behavioral-claimed"
                        : (cp.completed
                                ? "daily-challenge.behavioral-completed"
                                : "daily-challenge.behavioral-progress");
                // P2 fan-in（S7 上报）：聊天侧进度与 GUI 展示层同口径 clamp，
                // 不再出现 "47/10" 式超量显示（target<=0 时原样）。
                int displayProgress = cp.type.target > 0
                        ? Math.min(cp.progress, cp.type.target)
                        : cp.progress;
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        statusKey,
                        "name", getBehavioralName(player, cp.type),
                        "progress", String.valueOf(displayProgress),
                        "target", String.valueOf(cp.type.target)));
            }
        }

        // FIX-CHAL-01: celebration de-forced (Phase 1 stream C used to force-open
        // the GUI on completion). Opened last, after all chat output, so the
        // player can read the full progress list first. /le challenge becomes
        // the opt-in entry point for the celebration page.
        // R-07: same dual condition as the chat message above — pending
        // payouts must not open the "reward received" page.
        // R-40: (1) in-duel/in-quiz 守卫 —— 决斗/答题中执行只刷聊天不弹页，
        // 弹页会盖掉决斗 GUI / 打断聊天答题输入流；(2) "今日已展示"去重 ——
        // 同一玩家同一自然日只自动弹一次，重复执行只刷聊天。
        if (allDone && paid) {
            var duelManager = plugin.getDuelManager();
            var wordManager = plugin.getWordLearningManager();
            boolean inDuel = duelManager != null && duelManager.isInDuel(player.getUniqueId());
            boolean inQuiz = wordManager != null
                    && (wordManager.hasActiveQuiz(player.getUniqueId())
                            || wordManager.isPracticingWord(player.getUniqueId()));
            if (inDuel || inQuiz) {
                // 忙态只给提示，不弹页（聊天进度上面已刷）。
                // inDuel 复用既有精确键，inQuiz 无现成"庆祝忙"键则用新键。
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        inDuel ? "duel.finish-duel-first" : "daily-challenge.celebrate-busy"));
                return;
            }
            java.time.LocalDate today = java.time.LocalDate.now();
            // R13-M4：跨日惰性清扫（值仅当日有效；保留今日条目，不影响当日去重语义）。
            celebrationShownDates.values().removeIf(d -> !today.equals(d));
            if (today.equals(celebrationShownDates.get(player.getUniqueId()))) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "daily-challenge.celebrate-shown-today"));
                return;
            }
            celebrationShownDates.put(player.getUniqueId(), today);
            // R8-M1：命令直开的庆祝页此前不压帧，返回键会消费无关残留帧
            // （如广场→头像链遗留）跳去无关页。压一枚「主菜单」帧 —— 与
            // MainMenuListener 每日挑战同模式；以主菜单为帧内容即使残留也零危害。
            plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            new com.letslearnenglish.minecraftplugin.gui.ChallengeCompleteGUI(plugin)
                    .open(player, reward);
        }
    }

    /**
     * FIX-CHAL-01: resolve the display name of a behavioral challenge type
     * for the chat list. Mirrors {@code DailyChallengeGUI.getChallengeName}.
     */
    private String getBehavioralName(Player player,
            com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeType type) {
        String key = switch (type) {
            case ANSWER_10 -> "daily.name-answer-10";
            case SESSION_3 -> "daily.name-session-3";
            case COMBO_5 -> "daily.name-combo-5";
            case AI_CHAT_5 -> "daily.name-ai-chat-5";
        };
        // R-86d：裸 getString 缺键返回 null 会让 translateAlternateColorCodes NPE，
        // 中断整段挑战列表输出。缺键回落 key 名（可见且可定位），不再炸。
        String raw = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(player)).getString(key);
        if (raw == null) {
            return key;
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }

    /**
     * FIX-CHAL-01: reward shown on the manually re-opened celebration page.
     * Reuses the existing config key — no new key introduced. Frozen
     * DailyChallengeManager owns the real getter; this read-only mirror keeps
     * the celebration page self-consistent (money already arrived earlier).
     */
    private double getWordChallengeReward() {
        return plugin.getConfigManager().getMainConfig()
                .getDouble("daily-challenges.word-challenge-reward", 100.0);
    }

    /**
     * R-44: 经济是否真能发币（Vault 在线 且 money.enabled=true）。
     * 只读检查，不发币。/le challenge 的"到账待定"分支用它区分
     * "暂时没到、稍后补发"和"经济都没开、永远到不了"两种语义。
     */
    private boolean isEconomyAvailable() {
        if (plugin.getEconomy() == null) {
            return false;
        }
        // Wave 1 fan-in（S-H 上报收口）：改走 RewardEngine 的公开并集判定
        // （isRewardsEnabled() && money.enabled）——原先只读 money 键，
        // "关奖励伞 + money 键还开着"组合下这里判"可用"而实际拒发，提示语义会走错。
        return plugin.getProgressManager().getRewardEngine().isMoneyEnabled();
    }

    /**
     * R-16 残留①：金币金额的玩家可见格式化（整数去小数点、非整数保留两位）。
     *
     * <p><b>为什么不直接复用 {@code RewardEngine.formatCoins} 而是再写一份</b>：
     * 本轮修复的约束是「只许改 LearnCommand.java」，而 {@code RewardEngine.formatCoins}
     * 在 {@code RewardEngine.java:440} 是 {@code private static} —— 编译期根本调不到。
     * 三条路各有权衡，选第三条：
     * <ol>
     *   <li>把它改成 {@code public static}：<b>技术最优</b>（全仓唯一实现，本项目的既有偏好
     *       —— 见 {@code ChallengeCompleteGUI:161-167} 与 {@code SeasonManager:2165} 两处
     *       都已经在注释里抱怨过「private 拿不到只好各写一份」）。但它要动 LearnCommand
     *       之外的文件，本轮明确禁止。</li>
     *   <li>改走 {@code new RewardEngine.CappedPayout(v, v).paidText()}：
     *       <b>能用、且零重复</b>（{@code paidText()} 正是转调 {@code formatCoins}），
     *       但这是拿一个「应发 vs 实发截断语义」的值对象当格式化工具用 —— 后来人看一眼
     *       会以为这里在判截断，语义噪音比重复代码更贵。故不选。</li>
     *   <li>本地方案：拷贝同一套算术（整数 {@code (long)}、非整数 {@code %.2f} +
     *       {@link Locale#ROOT}）。<b>刻意与 {@code ChallengeCompleteGUI.formatReward}
     *       逐字一致</b>，两处注释互相点名，谁改了另一边会看到。</li>
     * </ol>
     *
     * <p>这里是<b>第三份</b>拷贝，因此留成待办：若后续允许改 RewardEngine，应把
     * {@code formatCoins} 提升为 {@code public static}，然后本方法、{@code formatReward}、
     * {@code SeasonManager.formatCoins} 三处一并删除，只留一份真相源。
     *
     * <p>⚠️ 与 {@code (int)} 强转的区别（本方法存在的理由）：{@code (int) 0.20 == 0}、
     * {@code (int) 99.9 == 99}，两条都是对玩家的谎报；本方法对 0.20 给 {@code 0.20}、
     * 对 99.9 给 {@code 99.90}、对 100.0 给 {@code 100}。
     *
     * @param v 金币金额（元）
     * @return 玩家可见文本，非 null
     */
    private static String formatCoins(double v) {
        return v == Math.floor(v)
                ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.2f", v);
    }

    private void handleHistoryCommand(Player player) {
        new ChatHistoryGUI(plugin).open(player);
    }

    private void handleDuelCommand(Player player, String[] args) {
        com.letslearnenglish.minecraftplugin.core.duel.DuelManager duelManager = plugin.getDuelManager();
        if (duelManager == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.duel-unavailable")));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.usage"));
            return;
        }

        String sub = args[1].toLowerCase();

        // D7 同族缺陷：/le duel 的多余参数此前也被静默丢弃（`/le duel accept now` 照常
        // 接受挑战，`/le duel Bob extra` 照常发邀请）。合法形态与 onTabComplete 的补全
        // 层数同源：
        //   /le duel <玩家名> | accept | quit | gui  → 2 个 token（含 "duel"）
        //   /le duel cancel <玩家名>（GM）           → 3 个 token
        // cancel 的长度判定刻意留在它自己的分支里（权限门之后）—— 无权限者应先收到
        // no-permission，不因"参数多了"而把拒绝语义换成 usage。
        if (args.length > 2 && !"cancel".equals(sub)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.usage"));
            return;
        }

        // MC-2 模块门（plan-menu-config §4.2 特殊语义）：duel.enabled=false 时
        // "发起 / 接受"恒被拦（module.disabled）；"quit / gui"在"已在局中"时放行 ——
        // 关开关后进行中的对局仍可退出 / 重开界面，随后自然收尾；
        // /le duel cancel 是 GM 解散通道（进行中对局的收尾手段），不随功能关闭失效。
        boolean duelInProgress = duelManager.isInDuel(player.getUniqueId());

        if ("quit".equals(sub)) {
            if (requireEnabled(player,
                    plugin.getConfigManager().isDuelEnabled() || duelInProgress)) {
                return;
            }
            duelManager.quitDuel(player);
        } else if ("accept".equals(sub)) {
            if (requireEnabled(player, plugin.getConfigManager().isDuelEnabled())) {
                return;
            }
            duelManager.acceptDuel(player);
        } else if ("gui".equals(sub)) {
            if (requireEnabled(player,
                    plugin.getConfigManager().isDuelEnabled() || duelInProgress)) {
                return;
            }
            // R-23: ESC 后重开当前对局界面（决斗中可用，非对决中回 not-in-duel）。
            duelManager.reopenDuelGUI(player);
        } else if ("cancel".equals(sub)) {
            // FIX-DUEL-01: GM dismissal entry on the /le side.
            // Real execution lives in /lea duel cancel (AdminCommand);
            // here we only forward GMs and refuse non-GMs cleanly so a plain
            // player named "cancel" can never be misrouted into admin logic.
            // MC-2：本分支刻意不加 duel.enabled 门（见上方注释）。
            if (!player.hasPermission("letslearnenglish.admin")) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "general.no-permission"));
                return;
            }
            if (args.length < 3) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "duel.cancel-usage"));
                return;
            }
            // D7 同族：cancel 的合法长度恰为 3（duel cancel <玩家名>），多敲的 token
            // 同样不能被静默丢弃 —— 否则 GM 会以为 `/le duel cancel Bob now` 里的
            // 附加条件生效了（实际只解散 Bob）。
            if (args.length > 3) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "duel.cancel-usage"));
                return;
            }
            duelManager.cancelDuelByAdmin(player, args[2]);
        } else {
            // Treat as a player name — send duel request（发起路径：恒过 duel.enabled 门）
            if (requireEnabled(player, plugin.getConfigManager().isDuelEnabled())) {
                return;
            }
            Player target = plugin.getServer().getPlayer(sub);
            if (target == null) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "duel.player-offline"));
                return;
            }
            duelManager.sendDuelRequest(player, target);
        }
    }

    private void handleWrongCommand(Player player) {
        // MC-2 模块门：错题本属 word-system 域（关闭后 GUI 与独立练习不可达）。
        if (requireEnabled(player, plugin.getConfigManager().isWordSystemEnabled())) {
            return;
        }
        new com.letslearnenglish.minecraftplugin.gui.WrongBookGUI(plugin).open(player, 1);
    }

    private void handleCheckinCommand(Player player) {
        // MC-2 模块门：checkin.enabled=false → /le checkin 拒绝（streak 数据保留）。
        if (requireEnabled(player, plugin.getConfigManager().isCheckinEnabled())) {
            return;
        }
        com.letslearnenglish.minecraftplugin.core.manager.CheckInManager checkInManager =
                plugin.getCheckInManager();
        if (checkInManager == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.checkin-unavailable")));
            return;
        }

        com.letslearnenglish.minecraftplugin.core.manager.CheckInManager.CheckInResult result =
                checkInManager.checkIn(player);

        if (result.success()) {
            if (result.streakBroken()) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "checkin.streak-broken"));
            }
            // R-16 残留①：原为 String.valueOf((int) result.coinsEarned())。
            // (int) 强转把「角分」整个抹掉：日限截断实发 0.20 时渲染成 "0"，
            // 玩家刚真的收到币却读到「获得 0 金币」—— 这正是 R-16 要消灭的谎报，
            // 只是上一轮修到 giveCoins/CheckInResult 就收工了，渲染这一层漏了。
            // 改走 formatCoins（见本类新增的私有助手），与 RewardEngine 同一套算术。
            String coinsStr = formatCoins(result.coinsEarned());
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "checkin.success",
                    "streak", String.valueOf(result.streak()),
                    "coins", coinsStr));
            // R-16 残留②：CheckInResult.rewardCapped() 已就绪却没人消费 ——
            // 上半段只说「获得 X」，而 X 被 cap 砍过时玩家不知道「应发 Y、差额不补发」，
            // 而签到的裁决是「截断也照常收盘」（claim 在发钱前就落库，今天再签只会被
            // 幂等拒绝），差额没有任何重试窗口 —— 不说明就等于让玩家以为剩下的还在。
            // 键名用 daily.reward-capped：全仓只有这一个 reward-capped 键（zh/en 各一条，
            // 占位符 {paid}/{nominal}），DailyChallengeManager 与 MiscGUIListener 已在消费它。
            // （CheckInManager:135 的注释写作 checkin.reward-capped，是上一轮留下的笔误，
            //   该键在 zh.yml/en.yml 里并不存在 —— 按注释照写会触发缺键占位符 [?...]。）
            // 位置：紧跟 success 之后。与 economy-disabled 不会撞：后者要求 paid==0
            // （economyDisabled && !paid），而 rewardCapped() 要求 paid>0，二者互斥。
            if (result.rewardCapped()) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "daily.reward-capped",
                        "paid", formatCoins(result.coinsEarned()),
                        "nominal", formatCoins(result.nominalCoins())));
            }
            // S4：签到成功加"正向反馈"音效（复用 correct 键，与判对/成就同语义）。
            // 已签过分支不加音效 —— 那是失败路径，给"正确"音反而误导。
            SoundManager.playSound(player, "correct");
            // Wave 2 域 4（广场社交）：签到成功 → 产生/合并一条 CHECKIN 动态
            // （SocialManager 内部自判 social.enabled 与产生侧隐身门，异步写库；
            // 重复签/断签失败分支到不了这里，与 plan-social §1.4 产生规则一致）。
            plugin.getSocialManager().recordCheckin(player.getUniqueId(), result.streak());
            // R-44：0 金币不是"成功发了 0 块"，是经济开关没开 —— 补一行说明，
            // 免得玩家以为签到奖励吞了。只在 money.enabled=false 时出现。
            if (result.economyDisabled()) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "checkin.economy-disabled"));
            }
        } else {
            // R11-M4：存储失败与"今日已签到"同为 success=false，但语义不同 ——
            // 前者要玩家重试（否则当日 streak 断），后者才是幂等提示。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    result.storageFailed() ? "checkin.storage-failed" : "checkin.already"));
        }
    }

    /**
     * /le class（Phase 3 · Wave 4 · US-CLS-2）：打开本班班级榜 GUI。
     *
     * <p>gating 顺序：{@code class-system.enabled=false} → class.disabled；
     * SeasonManager 不可用（null / enabled=false / 锚点在未来）→ season.disabled
     * （班级榜按赛季窗口计分，赛季休眠时无意义 —— 复用既有活键）；本人无班 →
     * class.not-in-class。
     *
     * <p><b>零查库链路（PF-SEA-04 教训）</b>：主线程只做权限/配置判定；成员列表与
     * 赛季分聚合全部在异步线程（getPlayerClassId → listClasses 找班名 →
     * getClassMembers → getRankedPlayersForPeriodFiltered）；结果经 runTask 回主线程
     * 后才 {@code new ClassBoardGUI(plugin).open(...)}。GUI 内不触任何 DB。
     */
    private void handleClassCommand(Player player) {
        // 模块门：class-system.enabled（与 /lea class 族同一键）
        if (!plugin.getConfigManager().getMainConfig().getBoolean("class-system.enabled", false)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "class.disabled"));
            return;
        }

        SeasonManager season = plugin.getSeasonManager();
        if (!LeaderboardGUI.isSeasonAvailable(season)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "season.disabled"));
            return;
        }

        UUID uuid = player.getUniqueId();
        DatabaseManager db = plugin.getDatabaseManager();

        // 赛季窗口在主线程先算好（纯函数零 IO，读 SeasonManager 内存配置）
        java.time.LocalDate today = java.time.LocalDate.now();
        var window = season.windowOf(season.currentSeasonId(today));

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Integer classId = db.getPlayerClassId(uuid);
                if (classId == null) {
                    runTaskSafe(() -> player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "class.not-in-class")));
                    return;
                }

                // 班名反查：班级量小（管理员手工建班），listClasses 找 id 匹配行即可
                String className = null;
                for (DatabaseManager.ClassRow row : db.listClasses()) {
                    if (row.classId() == classId) {
                        className = row.className();
                        break;
                    }
                }
                if (className == null) {
                    // 竞态兜底：异步查询间隙班级被管理员删除
                    runTaskSafe(() -> player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "class.not-in-class")));
                    return;
                }

                // 成员 UUID 集合（限制查询范围到本班；名单天然 ≤ max-members）
                List<DatabaseManager.ClassMemberRow> memberRows = db.getClassMembers(classId);
                List<UUID> memberIds = new ArrayList<>(memberRows.size());
                for (DatabaseManager.ClassMemberRow row : memberRows) {
                    memberIds.add(row.uuid());
                }

                List<LeaderboardEntry> entries = plugin.getPlayerDataStore()
                        .getRankedPlayersForPeriodFiltered(ClassBoardGUI.ENTRY_SLOTS.length,
                                window.start(), window.end(), memberIds);

                String finalClassName = className;
                // 4A tester L-1 收口：异步→主线程开 GUI 之间复检在线（同 SocialListener/
                // SeasonManager 既有守卫姿态；玩家在查询间隙下线则放弃开界面）。
                runTaskSafe(() -> {
                    if (player.isOnline()) {
                        new ClassBoardGUI(plugin).open(player, finalClassName, entries);
                    }
                });
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次异步查询
        }
    }

    /**
     * 主线程回调薄封装（停用窗口静默放弃；照抄 SeasonManager.runMain 姿态）。
     * D2-5：同型帮助器见 util.SchedulerGuard（语义微差：其余调用点带 isEnabled
     * 预检/日志级别），本批未迁移，仅留注释指向。
     */
    private void runTaskSafe(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // 插件停用窗口：放弃主线程回调
        }
    }

    /**
     * /le season（Phase 3 · 域 5 赛季，plan-season §2.1 可选项）：
     * 聊天输出当前赛季号 / 剩余天数 / 本人名次 + 前 10 快览。
     * 发现性入口 —— 榜单 GUI 的赛季 tab 是主入口，本命令是聊天侧轻量视图。
     *
     * <p>gating（R-55i 真 gating 同口径）：SeasonManager 为 null / enabled=false /
     * 赛季未开启（锚点在未来）→ season.disabled 提示。数据全部读 SeasonManager
     * 缓存（getCurrentTopCached / getRankOf），零查库。
     */
    private void handleSeasonCommand(Player player) {
        SeasonManager season = plugin.getSeasonManager();
        if (!LeaderboardGUI.isSeasonAvailable(season)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "season.disabled"));
            return;
        }

        java.time.LocalDate today = java.time.LocalDate.now();
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "season.title", "season", String.valueOf(season.currentSeasonId(today))));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "season.remaining",
                "days", String.valueOf(LeaderboardGUI.seasonDaysLeft(season)),
                "hours", String.valueOf(LeaderboardGUI.seasonHoursLeft(season))));

        // 赛季榜缓存（最多 50 行；显示前 10，本人分数反查全量 50）
        var topRows = season.getCurrentTopCached(50);

        // 本人名次（getRankOf 走缓存；-1 = 未上榜 → season.unranked 与 GUI/PAPI 同语义）
        int rank = season.getRankOf(player.getUniqueId());
        if (rank > 0) {
            // 分数反查缓存（榜外无分数可查，与信息牌同表现）
            long score = 0;
            for (SeasonManager.SeasonRankRow row : topRows) {
                if (row.uuid().equals(player.getUniqueId())) {
                    score = row.score();
                    break;
                }
            }
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "season.your-rank",
                    "rank", String.valueOf(rank),
                    "score", String.valueOf(score)));
        } else {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "season.unranked"));
        }

        // 前 10 快览（复用榜单行格式 leaderboard.entry/header/footer，与聊天榜样式统一）
        if (topRows.isEmpty()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "season.empty"));
            return;
        }
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "leaderboard.header"));
        for (int i = 0; i < Math.min(10, topRows.size()); i++) {
            SeasonManager.SeasonRankRow row = topRows.get(i);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "leaderboard.entry",
                    "rank", String.valueOf(row.rank()),
                    "player", row.name() != null ? row.name() : "",
                    "score", String.valueOf(row.score())));
        }
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "leaderboard.footer"));
    }

    private void handleSaveScenarioCommand(Player player, String[] args) {
        AIChatManager aiChatManager = plugin.getAIChatManager();

        // Check active conversation
        if (!aiChatManager.hasActiveConversation(player)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.save-no-conversation"));
            return;
        }

        // Check name argument
        if (args.length < 2) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.usage-save-scenario")));
            return;
        }

        String name = args[1];

        // Validate name: max 32 chars, only Chinese/English/digits/underscore
        if (name.length() > 32 || !name.matches("[\\u4e00-\\u9fa5a-zA-Z0-9_]+")) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.save-name-invalid"));
            return;
        }

        // Get current system prompt
        String systemPrompt = aiChatManager.getCurrentSystemPrompt(player);
        if (systemPrompt == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.save-no-conversation"));
            return;
        }

        // Validate prompt length
        int maxLength = aiChatManager.getMaxPromptLength();
        if (systemPrompt.length() > maxLength) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.save-prompt-too-long",
                    "length", String.valueOf(systemPrompt.length()),
                    "max", String.valueOf(maxLength)));
            return;
        }

        // Encode and send
        String code = ScenarioCodec.encode(name, systemPrompt);
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "scenario.save-success", "code", code));
    }

    private void handleLoadScenarioCommand(Player player, String[] args) {
        // H7：导入需要权限，无权限拒绝
        if (!player.hasPermission("letslearnenglish.scenario.import")) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.load-no-permission"));
            return;
        }

        // Check code argument
        if (args.length < 2) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.usage-load-scenario")));
            return;
        }

        String code = args[1];

        // Decode
        ScenarioCodec.DecodedScenario decoded = ScenarioCodec.decode(code);
        if (decoded == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.load-invalid"));
            return;
        }

        String name = decoded.name();
        String prompt = decoded.prompt();

        // Validate name
        if (name.length() > 32 || !name.matches("[\\u4e00-\\u9fa5a-zA-Z0-9_]+")) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.load-name-invalid"));
            return;
        }

        // Validate prompt length
        AIChatManager aiChatManager = plugin.getAIChatManager();
        int maxLength = aiChatManager.getMaxPromptLength();
        if (prompt.length() > maxLength) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.save-prompt-too-long",
                    "length", String.valueOf(prompt.length()),
                    "max", String.valueOf(maxLength)));
            return;
        }

        // H7：导入改为玩家个人场景 — 不写全局 ai_scenarios.yml、不 reload 全局，
        // 只落盘到该玩家的个人文件并刷新其内存副本，不影响他人。
        // R-61/86f（S2 冻结接口，执行计划明确 S4 在此调用点消费）：返回 ImportResult
        // record —— 上限拒绝 / 写盘失败 / 覆盖更新 / 新增成功四种结果分别提示，
        // 失败不再落入"已加载"报成功。
        AIChatManager.ImportResult result = aiChatManager.importPersonalScenario(player, name, prompt);
        if (result.limitReached()) {
            // limitReached=true 时个人场景数恰为上限，直接读公开上限 getter 填 {max}
            //（P2 fan-in：Arch tester 指出 getMaxPersonalScenarios 无调用方成死 getter，
            // 改用它取真实上限值，与 AIChatManager 私有常量单一真相源）。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.limit-reached",
                    "max", String.valueOf(aiChatManager.getMaxPersonalScenarios())));
            return;
        }
        if (result.key() == null || result.saveFailed()) {
            plugin.getLogger().warning("Failed to save imported personal scenario for "
                    + player.getName());
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.load-save-failed"));
            return;
        }
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                result.updated() ? "scenario.load-updated" : "scenario.load-success",
                "name", name));
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.header"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.menu"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.word"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.dialogue"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.progress"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.review"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.stats"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.profile"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.square"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.leaderboard"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.challenge"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.history"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.duel"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.wrong"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.checkin"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.season"));
        // Wave 4 · 4A fan-in：/le class 帮助行
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.class"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.guide"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.save_scenario"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.load_scenario"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // 子服总开关（server.enabled=false）：补全是**独立于 onCommand 的入口**，Bukkit
        // 会在玩家敲 Tab 时直接调用本方法。关闭态下 wordManager / aiChatManager /
        // adminCommandHandler 等一律为 null，不拦就会在 getAvailableDifficulties() 处 NPE。
        // 返回空列表（不是 null）：Bukkit 契约要求非 null，返回 null 会让客户端补全报错。
        if (!plugin.isFullyEnabled()) {
            return completions;
        }

        // 二级补全：与 SUBCOMMANDS 中有参数的子命令一一对应
        // word [难度] [模式] / dialogue [场景] / duel <玩家名|accept|quit|gui>
        // profile [玩家名] / save_scenario、load_scenario 为自由文本，无候选
        if (args.length >= 2 && "word".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                for (String diff : plugin.getWordManager().getAvailableDifficulties()) {
                    if (diff.toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(diff);
                    }
                }
            } else if (args.length == 3) {
                // MC-3：补全候选从 WordModes.enabledModes 派生（保序 spelling→choice→
                // fill_blank），被管理员关闭的模式剔除；与 mode 校验共用同一真相源。
                for (String mode : WordModes.enabledModes(plugin.getConfigManager())) {
                    if (mode.startsWith(args[2].toLowerCase())) {
                        completions.add(mode);
                    }
                }
            }
        } else if (args.length == 2 && "dialogue".equalsIgnoreCase(args[0])) {
            // R-04：补全含该玩家的个人场景 key；控制台等非玩家发送者回落全局列表
            List<String> scenarios = (sender instanceof Player tabPlayer)
                    ? plugin.getAIChatManager().getAvailableScenarios(tabPlayer)
                    : plugin.getAIChatManager().getAvailableScenarios();
            for (String scenario : scenarios) {
                if (scenario.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(scenario);
                }
            }
        } else if (args.length == 2 && "duel".equalsIgnoreCase(args[0])) {
            String input = args[1].toLowerCase();
            // 子命令：accept / quit / gui（R-23 重开界面；cancel 仅 GM 可见，由 /lea duel cancel 实际执行）
            for (String sub : Arrays.asList("accept", "quit", "gui")) {
                if (sub.startsWith(input)) {
                    completions.add(sub);
                }
            }
            // FIX-DUEL-01: GM-only cancel hint on the /le side
            if (sender.hasPermission("letslearnenglish.admin")
                    && "cancel".startsWith(input)) {
                completions.add("cancel");
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getName().equals(sender.getName())
                        && p.getName().toLowerCase().startsWith(input)) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 2 && "profile".equalsIgnoreCase(args[0])) {
            String profileInput = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getName().equals(sender.getName())
                        && p.getName().toLowerCase().startsWith(profileInput)) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 2 && "square".equalsIgnoreCase(args[0])) {
            // Wave 2 域 4：/le square 的可选模式参数（与 handleSquareCommand 的白名单同源）
            String squareInput = args[1].toLowerCase();
            for (String mode : Arrays.asList("feed", "weekly", "monthly", "alltime", "all")) {
                if (mode.startsWith(squareInput)) {
                    completions.add(mode);
                }
            }
        } else if (args.length == 3
                && "duel".equalsIgnoreCase(args[0])
                && "cancel".equalsIgnoreCase(args[1])
                && sender.hasPermission("letslearnenglish.admin")) {
            // FIX-DUEL-01: /le duel cancel <player> — GM-only name completion
            String cancelInput = args[2].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(cancelInput)) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        }

        return completions;
    }
}