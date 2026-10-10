package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;

/**
 * Admin Command - Administrator command for plugin management.
 *
 * Subcommands:
 * - /lea reload     Hot-reload all configuration files
 * - /lea export     Export player learning data
 * - /lea reset      Reset a player's learning data
 * - /lea info       Show plugin information
 * - /lea duel cancel &lt;player&gt;  Force-dismiss an active word duel (GM adjudication)
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final LetsLearnEnglish plugin;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "reload", "export", "reset", "info", "help", "ignore-update", "broadcast",
            "exportwordbank", "importwordbank", "duel"
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
            case "broadcast":
                handleBroadcast(sender, args);
                break;
            case "duel":
                handleDuelCancel(sender, args);
                break;
            case "exportwordbank":
                if (args.length > 1) {
                    plugin.getAdminCommandHandler().exportWordBank(args[1], sender);
                } else {
                    sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "admin.usage-exportwordbank"));
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "admin.available-difficulties", "list",
                            String.join(", ", plugin.getWordManager().getAvailableDifficulties())));
                }
                break;
            case "importwordbank":
                if (args.length > 1) {
                    plugin.getAdminCommandHandler().importWordBank(args[1], sender);
                } else {
                    sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "admin.usage-importwordbank"));
                    sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "admin.importwordbank-hint"));
                }
                break;
            default:
                sender.sendMessage(plugin.getMessageUtil()
                        .getSenderMessage(sender, "admin.unknown-command"));
                break;
        }

        return true;
    }

    private void handleReset(CommandSender sender, String playerName) {
        plugin.getAdminCommandHandler().resetPlayerData(playerName);
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin.player-reset", "player", playerName));
    }

    private void handleIgnoreUpdate(CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.getUpdateChecker().dismiss(player);
        } else {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "admin.player-only"));
        }
    }

    private void handleBroadcast(CommandSender sender, String[] args) {
        if (plugin.getProxyMessenger() == null || !plugin.getProxyMessenger().isRegistered()) {
            sender.sendMessage(plugin.getMessageUtil()
                    .getSenderMessage(sender, "admin.proxy-unavailable"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.getMessageUtil()
                    .getSenderMessage(sender, "admin.usage-broadcast"));
            sender.sendMessage(plugin.getMessageUtil()
                    .getSenderMessage(sender, "admin.broadcast-hint"));
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        // 广播前缀走 i18n（服务端默认语言），消息本身通过代理发给所有子服
        String formatted = ChatColor.translateAlternateColorCodes('&',
                plugin.getMessageUtil().getMessage("admin.broadcast-prefix") + message);
        plugin.getProxyMessenger().sendGlobalMessage(formatted);
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "admin.broadcast-sent"));
        plugin.getLogger().info("Global broadcast sent by " + sender.getName() + ": " + message);
    }

    /**
     * FIX-DUEL-01: GM duel dismissal — {@code /lea duel cancel <player>}.
     * Works from console too (CommandSender, not just Player). Only one
     * verb (cancel) is supported; anything else prints usage.
     */
    private void handleDuelCancel(CommandSender sender, String[] args) {
        if (args.length < 3 || !"cancel".equalsIgnoreCase(args[1])) {
            sender.sendMessage(plugin.getMessageUtil()
                    .getSenderMessage(sender, "admin.usage-duel-cancel"));
            return;
        }
        var duelManager = plugin.getDuelManager();
        if (duelManager == null) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "general.error", "message",
                    plugin.getMessageUtil().getSenderMessage(sender, "error.duel-unavailable")));
            return;
        }
        duelManager.cancelDuelByAdmin(sender, args[2]);
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
                "admin-help.exportwordbank"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.importwordbank"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.ignore-update"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.broadcast"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.duel-cancel"));
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
        } else if (args.length == 2) {
            if ("duel".equalsIgnoreCase(args[0])) {
                // FIX-DUEL-01: /lea duel <cancel>
                if ("cancel".startsWith(args[1].toLowerCase())) {
                    completions.add("cancel");
                }
            } else if ("reset".equalsIgnoreCase(args[0])) {
                plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .forEach(completions::add);
            } else if ("exportwordbank".equalsIgnoreCase(args[0])) {
                String prefix = args[1].toLowerCase();
                for (String diff : plugin.getWordManager().getAvailableDifficulties()) {
                    if (diff.toLowerCase().startsWith(prefix)) {
                        completions.add(diff);
                    }
                }
            } else if ("importwordbank".equalsIgnoreCase(args[0])) {
                String prefix = args[1].toLowerCase();
                File exportsDir = new File(plugin.getDataFolder(), "exports");
                if (exportsDir.exists() && exportsDir.isDirectory()) {
                    File[] files = exportsDir.listFiles(
                            (dir, name) -> name.endsWith(".yml"));
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().toLowerCase().startsWith(prefix)) {
                                completions.add(f.getName());
                            }
                        }
                    }
                }
            }
        } else if (args.length == 3
                && "duel".equalsIgnoreCase(args[0])
                && "cancel".equalsIgnoreCase(args[1])) {
            // FIX-DUEL-01: /lea duel cancel <player> — complete online players
            plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .forEach(completions::add);
        }

        return completions;
    }
}
