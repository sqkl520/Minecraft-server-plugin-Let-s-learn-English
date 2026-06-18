package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.ChatHistoryGUI;
import com.letslearnenglish.minecraftplugin.gui.EnglishSquareGUI;
import com.letslearnenglish.minecraftplugin.gui.LeaderboardGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import com.letslearnenglish.minecraftplugin.gui.ProgressGUI;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordBankSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
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
            "menu", "word", "dialogue", "progress", "review", "stats", "help", "square", "profile", "leaderboard", "challenge", "history", "duel", "wrong"
    );

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
            String mode = args.length > 2 ? args[2] : "choice";
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
                    "general.error", "message", "Chat scenario is not available."));
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
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.started", "scenario", scenario));
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
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.session-start", "difficulty", "复习", "mode", "复习"));
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
                        "general.error", "message", "Player not found"));
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
    }

    private void handleHistoryCommand(Player player) {
        new ChatHistoryGUI(plugin).open(player);
    }

    private void handleDuelCommand(Player player, String[] args) {
        com.letslearnenglish.minecraftplugin.core.duel.DuelManager duelManager = plugin.getDuelManager();
        if (duelManager == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message", "Duel system is not available."));
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
        } else {
            // Treat as a player name — send duel request
            Player target = plugin.getServer().getPlayer(sub);
            if (target == null) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "general.error", "message", "Player " + sub + " is not online."));
                return;
            }
            duelManager.sendDuelRequest(player, target);
        }
    }

    private void handleWrongCommand(Player player) {
        new com.letslearnenglish.minecraftplugin.gui.WrongBookGUI(plugin).open(player, 1);
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
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.history"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.wrong"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && "dialogue".equalsIgnoreCase(args[0])) {
            for (String scenario : plugin.getAIChatManager().getAvailableScenarios()) {
                if (scenario.startsWith(args[1].toLowerCase())) {
                    completions.add(scenario);
                }
            }
        } else if (args.length == 2 && "word".equalsIgnoreCase(args[0])) {
            for (String diff : plugin.getWordManager().getAvailableDifficulties()) {
                if (diff.startsWith(args[1].toLowerCase())) {
                    completions.add(diff);
                }
            }
        } else if (args.length == 3 && "word".equalsIgnoreCase(args[0])) {
            for (String mode : Arrays.asList("spelling", "choice", "fill_blank")) {
                if (mode.startsWith(args[2].toLowerCase())) {
                    completions.add(mode);
                }
            }
        } else if (args.length == 2 && "duel".equalsIgnoreCase(args[0])) {
            String input = args[1].toLowerCase();
            // Suggest accept/quit sub-commands
            for (String sub : Arrays.asList("accept", "quit")) {
                if (sub.startsWith(input)) {
                    completions.add(sub);
                }
            }
            // Also suggest online player names (excluding self)
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (!p.getName().equals(sender.getName())
                        && p.getName().toLowerCase().startsWith(input)) {
                    completions.add(p.getName());
                }
            }
        }

        return completions;
    }
}