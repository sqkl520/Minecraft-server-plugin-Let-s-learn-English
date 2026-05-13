package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Admin Command - Administrator command for plugin management.
 *
 * Subcommands:
 * - /lea reload     Hot-reload all configuration files
 * - /lea export     Export player learning data
 * - /lea reset      Reset a player's learning data
 * - /lea info       Show plugin information
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final LetsLearnEnglish plugin;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "reload", "export", "reset", "info", "help"
    );

    public AdminCommand(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("letslearnenglish.admin")) {
            sender.sendMessage(plugin.getMessageUtil().getMessage("general.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                plugin.getHotReloadManager().reloadAll();
                sender.sendMessage(plugin.getMessageUtil().getMessage("general.reload-success"));
                break;
            case "export":
                String filePath = plugin.getDataExporter().exportAllData();
                sender.sendMessage(plugin.getMessageUtil().getMessage("admin.export-complete",
                        "file", filePath));
                break;
            case "reset":
                if (args.length > 1) {
                    handleReset(sender, args[1]);
                } else {
                    sender.sendMessage("Usage: /lea reset <player>");
                }
                break;
            case "info":
                sendPluginInfo(sender);
                break;
            case "help":
                sendHelpMessage(sender);
                break;
            default:
                sender.sendMessage("Unknown subcommand. Use /lea help for help.");
                break;
        }

        return true;
    }

    private void handleReset(CommandSender sender, String playerName) {
        // TODO: Implement actual player data reset using AdminCommandHandler.resetPlayerData()
        sender.sendMessage("Player data reset for: " + playerName + " (feature pending)");
    }

    private void sendPluginInfo(CommandSender sender) {
        sender.sendMessage("§6===== Let's Learn English! Info =====");
        sender.sendMessage("§eVersion: §f" + plugin.getDescription().getVersion());
        sender.sendMessage("§eServer Version: §f" + plugin.getVersionAdapter().getServerVersion());
        sender.sendMessage("§eVersion Supported: §f" + plugin.getVersionAdapter().isVersionSupported());
        sender.sendMessage("§eDatabase: §f" + plugin.getConfigManager().getMainConfig().getString("database.type"));
        sender.sendMessage("§6======================================");
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage("§6===== Let's Learn English! Admin =====");
        sender.sendMessage("§e/lea reload §7- Hot-reload all configs");
        sender.sendMessage("§e/lea export §7- Export player data");
        sender.sendMessage("§e/lea reset <player> §7- Reset player data");
        sender.sendMessage("§e/lea info §7- Show plugin info");
        sender.sendMessage("§6======================================");
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
        } else if (args.length == 2 && "reset".equalsIgnoreCase(args[0])) {
            plugin.getServer().getOnlinePlayers().stream()
                    .map(p -> p.getName())
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .forEach(completions::add);
        }

        return completions;
    }
}