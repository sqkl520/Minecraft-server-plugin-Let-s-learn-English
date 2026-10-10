package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.ai.ScenarioCodec;
import com.letslearnenglish.minecraftplugin.gui.ChatHistoryGUI;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
            "menu", "word", "dialogue", "progress", "review", "stats", "help", "square", "profile", "leaderboard", "challenge", "history", "duel", "wrong", "checkin", "save_scenario", "load_scenario"
    );

    private static final java.util.Set<String> VALID_WORD_MODES =
            java.util.Set.of("spelling", "choice", "fill_blank");

    /** FEAT-MODE-01：choice 是新手默认模式（与 GUI 入口、getMode 回落一致）。 */
    private static final String DEFAULT_WORD_MODE = "choice";

    public LearnCommand(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageUtil().getMessage("general.player-only"));
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
                handleStatsCommand(player);
                break;
            case "square":
                handleSquareCommand(player);
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
            case "save_scenario":
                handleSaveScenarioCommand(player, args);
                break;
            case "load_scenario":
                handleLoadScenarioCommand(player, args);
                break;
            case "help":
                sendHelpMessage(player);
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
        if (args.length <= 1) {
            new WordBankSelectGUI(plugin).open(player);
        } else {
            String difficulty = args[1];
            String mode = args.length > 2 ? args[2].toLowerCase() : DEFAULT_WORD_MODE;
            // FEAT-MODE-01：mode 参数先校验再透传。非法 mode 回 i18n 提示，不再把
            // WordManagerImpl 的 IllegalArgumentException 原始英文抛给玩家。
            if (!VALID_WORD_MODES.contains(mode)) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.invalid-mode", "mode", args[2]));
                return;
            }
            startWordQuiz(player, difficulty, mode);
        }
    }

    private void startWordQuiz(Player player, String difficulty, String mode) {
        try {
            plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
            new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.session-start", "difficulty", difficulty, "mode", mode));
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message", e.getMessage()));
        }
    }

    private void handleDialogueCommand(Player player, String[] args) {
        if (plugin.getChatScenarioListener() == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.scenario-unavailable")));
            return;
        }
        if (plugin.getChatScenarioListener().isInScenarioMode(player)) {
            plugin.getChatScenarioListener().removePlayer(player);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.ended"));
            return;
        }
        if (args.length > 1) {
            // 直接开始指定场景
            String scenario = args[1].toLowerCase();
            plugin.getChatScenarioListener().addPlayer(player);
            plugin.getAIChatManager().startConversation(player, scenario);
            // 场景名走 ai_scenarios.yml 的 display_name / display_name_zh，与 GUI 入口保持一致
            // （原先直接塞原始 key，玩家会看到 airport 这样的英文标识符）
            AIChatManager.ScenarioConfig sc = plugin.getAIChatManager().getScenarioConfigs().get(scenario);
            boolean zh = "zh".equalsIgnoreCase(plugin.getPlayerLanguage(player));
            String scenarioName = (sc != null) ? sc.getDisplayName(zh) : scenario;
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.started", "scenario", scenarioName));
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.instructions"));
        } else {
            new ScenarioSelectGUI(plugin).open(player);
        }
    }

    private void handleProgressCommand(Player player) {
        new ProfileGUI(plugin).open(player, player.getUniqueId());
    }

    private void handleReviewCommand(Player player) {
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
            }
        } else {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "review.no-words"));
        }
    }

    private void handleStatsCommand(Player player) {
        new ProfileGUI(plugin).open(player, player.getUniqueId());
    }

    private void handleSquareCommand(Player player) {
        new EnglishSquareGUI(plugin).open(player, 1);
    }

    private void handleLeaderboardCommand(Player player) {
        new LeaderboardGUI(plugin).open(player);
    }

    private void handleProfileCommand(Player player, String[] args) {
        if (args.length > 1) {
            String targetName = args[1];
            Player target = plugin.getServer().getPlayer(targetName);
            if (target != null) {
                new ProfileGUI(plugin).open(player, target.getUniqueId());
            } else {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "general.error", "message",
                        plugin.getMessageUtil().getPlayerMessage(player, "error.player-not-found")));
            }
        } else {
            new ProfileGUI(plugin).open(player, player.getUniqueId());
        }
    }

    private void handleChallengeCommand(Player player) {
        var dcm = plugin.getDailyChallengeManager();
        int[] progress = dcm.getTodayWordProgress(player);
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
        var details = dcm.getTodayWordDetails(player);
        for (var wordProgress : details) {
            String statusKey = wordProgress.completed
                    ? "daily-challenge.word-done"
                    : "daily-challenge.word-pending";
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    statusKey, "word", wordProgress.wordId));
        }

        if (completed >= total && total > 0) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.all-complete-chat"));
        }

        // FIX-CHAL-01: cross-visibility (read-only) — show behavioral challenge
        // progress right below the word list so players can see both systems
        // from one entry point. Claim path stays inside DailyChallengeGUI.
        var challenges = dcm.getTodayChallenges(player.getUniqueId());
        if (!challenges.isEmpty()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.behavioral-header"));
            for (var cp : challenges) {
                String statusKey = cp.claimed
                        ? "daily-challenge.behavioral-claimed"
                        : (cp.completed
                                ? "daily-challenge.behavioral-completed"
                                : "daily-challenge.behavioral-progress");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        statusKey,
                        "name", getBehavioralName(player, cp.type),
                        "progress", String.valueOf(cp.progress),
                        "target", String.valueOf(cp.type.target)));
            }
        }

        // FIX-CHAL-01: celebration de-forced (Phase 1 stream C used to force-open
        // the GUI on completion). Opened last, after all chat output, so the
        // player can read the full progress list first. /le challenge becomes
        // the opt-in entry point for the celebration page.
        if (completed >= total && total > 0) {
            new com.letslearnenglish.minecraftplugin.gui.ChallengeCompleteGUI(plugin)
                    .open(player, getWordChallengeReward());
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
        String lang = plugin.getPlayerLanguage(player);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                plugin.getConfigManager().getMessageConfig(lang).getString(key));
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

        if ("quit".equals(sub)) {
            duelManager.quitDuel(player);
        } else if ("accept".equals(sub)) {
            duelManager.acceptDuel(player);
        } else if ("cancel".equals(sub)) {
            // FIX-DUEL-01: GM dismissal entry on the /le side.
            // Real execution lives in /lea duel cancel (AdminCommand);
            // here we only forward GMs and refuse non-GMs cleanly so a plain
            // player named "cancel" can never be misrouted into admin logic.
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
            duelManager.cancelDuelByAdmin(player, args[2]);
        } else {
            // Treat as a player name — send duel request
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
        new com.letslearnenglish.minecraftplugin.gui.WrongBookGUI(plugin).open(player, 1);
    }

    private void handleCheckinCommand(Player player) {
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
            String coinsStr = String.valueOf((int) result.coinsEarned());
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "checkin.success",
                    "streak", String.valueOf(result.streak()),
                    "coins", coinsStr));
        } else {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "checkin.already"));
        }
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
        String key = aiChatManager.importPersonalScenario(player, name, prompt);
        if (key == null) {
            plugin.getLogger().warning("Failed to save imported personal scenario for "
                    + player.getName());
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.scenario-save-failed")));
            return;
        }

        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "scenario.load-success", "name", name));
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
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.save_scenario"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.load_scenario"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // 二级补全：与 SUBCOMMANDS 中有参数的子命令一一对应
        // word [难度] [模式] / dialogue [场景] / duel <玩家名|accept|quit>
        // profile [玩家名] / save_scenario、load_scenario 为自由文本，无候选
        if (args.length >= 2 && "word".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                for (String diff : plugin.getWordManager().getAvailableDifficulties()) {
                    if (diff.toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(diff);
                    }
                }
            } else if (args.length == 3) {
                for (String mode : Arrays.asList("spelling", "choice", "fill_blank")) {
                    if (mode.startsWith(args[2].toLowerCase())) {
                        completions.add(mode);
                    }
                }
            }
        } else if (args.length == 2 && "dialogue".equalsIgnoreCase(args[0])) {
            for (String scenario : plugin.getAIChatManager().getAvailableScenarios()) {
                if (scenario.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(scenario);
                }
            }
        } else if (args.length == 2 && "duel".equalsIgnoreCase(args[0])) {
            String input = args[1].toLowerCase();
            // 子命令：accept / quit（cancel 仅 GM 可见，由 /lea duel cancel 实际执行）
            for (String sub : Arrays.asList("accept", "quit")) {
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