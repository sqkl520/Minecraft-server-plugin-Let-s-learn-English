package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

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
            "reload", "export", "reset", "info", "help", "ignore-update"
    );

    public AdminCommand(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("letslearnenglish.admin")) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "general.no-permission"));
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
                sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                        "general.reload-success"));
                break;
            case "export":
                String filePath = plugin.getDataExporter().exportAllData();
                sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                        "admin.export-complete", "file", filePath));
                break;
            case "reset":
                if (args.length > 1) {
                    handleReset(sender, args[1]);
                } else {
                    sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "admin.usage-reset"));
                }
                break;
            case "info":
                sendPluginInfo(sender);
                break;
            case "help":
                sendHelpMessage(sender);
                break;
            case "ignore-update":
                handleIgnoreUpdate(sender);
                break;
            default:
                sender.sendMessage(plugin.getMessageUtil()
                        .getSenderMessage(sender, "admin.unknown-command"));
                break;
        }

        return true;
    }

    private void handleReset(CommandSender sender, String playerName) {
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin.reset-pending", "player", playerName));
    }

    private void handleIgnoreUpdate(CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.getUpdateChecker().dismiss(player);
        } else {
            sender.sendMessage("This command can only be used by a player.");
        }
    }

    private void sendPluginInfo(CommandSender sender) {
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "plugin-info.header"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "plugin-info.version", "version",
                plugin.getDescription().getVersion()));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "plugin-info.server-version", "server_version",
                plugin.getVersionAdapter().getServerVersion()));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "plugin-info.version-supported", "supported",
                String.valueOf(plugin.getVersionAdapter().isVersionSupported())));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "plugin-info.database", "db_type",
                plugin.getConfigManager().getMainConfig().getString("database.type")));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "plugin-info.footer"));
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.header"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.reload"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.export"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.reset"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.info"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.footer"));
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