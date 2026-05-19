package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.DialogueGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProgressGUI;
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
            "menu", "word", "dialogue", "progress", "review", "stats", "help"
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
        String difficulty = args.length > 1 ? args[1] : "beginner";
        String mode = args.length > 2 ? args[2] : "choice";

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
        String scene = args.length > 1 ? args[1] : "restaurant";
        new DialogueGUI(plugin).open(player, scene);
    }

    private void handleProgressCommand(Player player) {
        new ProgressGUI(plugin).open(player);
    }

    private void handleReviewCommand(Player player) {
        java.util.List<String> dueWords = plugin.getWordManager()
                .getReviewScheduler().getDueReviewWords(player.getUniqueId());
        int count = dueWords.size();
        if (count > 0) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "review.notification", "count", String.valueOf(count)));
            plugin.getWordManager().getReviewScheduler().checkAndNotifyReviews();
        } else {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "review.no-words"));
        }
    }

    private void handleStatsCommand(Player player) {
        new ProgressGUI(plugin).open(player);
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.header"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.menu"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.word"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.dialogue"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.progress"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.review"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "help.stats"));
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
        }

        return completions;
    }
}