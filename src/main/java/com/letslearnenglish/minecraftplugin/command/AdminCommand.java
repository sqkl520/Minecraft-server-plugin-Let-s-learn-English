package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.manager.DataExporter;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * - /lea report     Generate a learning report for a player or all active players
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final LetsLearnEnglish plugin;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "reload", "export", "reset", "info", "help", "ignore-update", "broadcast",
            "exportwordbank", "importwordbank", "duel", "report", "class"
    );

    public AdminCommand(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // US-RPT-3 权限最小化（plan-reports §1.1）：不再顶部统一 admin 门。
        // report 子命令接受 letslearnenglish.report 或 admin 任一；其余子命令
        // （含无参 help 显示）维持 letslearnenglish.admin。只发报告权限的老师/
        // 家长代表可用 /lea report，而 reset/reload/broadcast 仍被拒绝。
        String subCommand = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!hasPermissionFor(sender, subCommand)) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "general.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (subCommand) {
            case "reload":
                plugin.getHotReloadManager().reloadAll();
                sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                        "general.reload-success"));
                break;
            case "export":
                // US-RPT-3：DataExporter 返回 ExportResult 协议对象（原 "Export failed: ..."
                // 字符串前缀判定已消——P2-09 教训落地）。失败按字段判定，不需要嗅探内容。
                DataExporter.ExportResult exportResult = plugin.getDataExporter().exportAllData();
                if (!exportResult.success()) {
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "admin.export-failed"));
                    break;
                }
                sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                        "admin.export-complete", "file", exportResult.filePath()));
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
            case "report":
                handleReport(sender, args);
                break;
            case "class":
                plugin.getAdminCommandHandler().handleClassCommand(sender, args);
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
        // R-86h：Handler 返回 false = 在线/库里/名册都查不到此人，原先照报"已重置"。
        // 未找到时明确告知，不再对不存在的玩家报成功。
        boolean found = plugin.getAdminCommandHandler().resetPlayerData(playerName);
        if (!found) {
            // 冻结键 admin.player-no-data 无占位符（执行计划 §三），不传多余参数
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "admin.player-no-data"));
            return;
        }
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

    /**
     * US-RPT-3 权限最小化（plan-reports §1.1）：逐子命令权限判定。
     *
     * <p>{@code report} 子命令接受 {@code letslearnenglish.report} 或
     * {@code letslearnenglish.admin} 任一；其余一切子命令（以及隐式的子命令名）
     * 维持 {@code letslearnenglish.admin}。只看子命令名、不看后续参数——权限判定
     * 不受 args[1]（玩家名/period）影响。
     *
     * <p>无参（{@code args.length == 0}）走 admin：help 列表含 reset/broadcast 等
     * 管理操作说明，仅 report 权限者不应看到全量管理帮助。
     */
    private boolean hasPermissionFor(CommandSender sender, String subCommand) {
        if ("report".equals(subCommand)) {
            return sender.hasPermission("letslearnenglish.report")
                    || sender.hasPermission("letslearnenglish.admin");
        }
        return sender.hasPermission("letslearnenglish.admin");
    }

    /**
     * US-RPT-3：{@code /lea report <玩家|all> [week|lastweek|<N>d]}。
     *
     * <p>本方法只做参数解析与转派：目标解析/UUID 三级查找/在线快照在主线程
     * （{@link AdminCommandHandler#generateReport}），DB 查询/渲染/写文件在
     * ReportGenerator 内部异步执行。{@code all} 为保留字（plan §1.1 文档化：
     * 名为 all 的玩家走不了单报）。
     */
    private void handleReport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "report.usage"));
            return;
        }
        String target = args[1];
        String periodSpec = args.length > 2 ? args[2] : null;
        String defaultSpec = plugin.getConfigManager().getMainConfig()
                .getString("report.period", "week");
        if ("all".equalsIgnoreCase(target)) {
            plugin.getAdminCommandHandler().generateBatchReport(sender, periodSpec, defaultSpec);
        } else {
            plugin.getAdminCommandHandler().generateReport(sender, target, periodSpec, defaultSpec);
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
                "admin-help.report"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            boolean isAdmin = sender.hasPermission("letslearnenglish.admin");
            boolean isReport = sender.hasPermission("letslearnenglish.report");
            for (String sub : SUBCOMMANDS) {
                // US-RPT-3：补全按权限过滤——只有 report 权限的老师不看到管理子命令名
                if (!isAdmin && !(isReport && "report".equals(sub))) {
                    continue;
                }
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            // W2 代码质量 tester 收口：args≥2 分支补权限复核——只有 report 权限的老师
            // 不应通过补全看到重置/决斗管理等子命令的存在（args==1 已过滤，此处对齐）。
            boolean isAdmin = sender.hasPermission("letslearnenglish.admin");
            boolean isReport = sender.hasPermission("letslearnenglish.report");
            if (!isAdmin && !(isReport && "report".equalsIgnoreCase(args[0]))) {
                return completions;
            }
            if ("duel".equalsIgnoreCase(args[0])) {
                // FIX-DUEL-01: /lea duel <cancel>
                if ("cancel".startsWith(args[1].toLowerCase())) {
                    completions.add("cancel");
                }
            } else if ("report".equalsIgnoreCase(args[0])) {
                // US-RPT-3: /lea report <在线名 ∪ 库内名 ∪ all>（plan §3.3）
                String prefix = args[1].toLowerCase();
                if ("all".startsWith(prefix)) {
                    completions.add("all");
                }
                plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .forEach(completions::add);
                if (prefix.length() >= 1) {
                    for (String name : plugin.getAdminCommandHandler()
                            .getKnownPlayerNames(args[1], 25)) {
                        if (!completions.contains(name)) {
                            completions.add(name);
                        }
                    }
                }
            } else if ("reset".equalsIgnoreCase(args[0])) {
                plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .forEach(completions::add);
            } else if ("class".equalsIgnoreCase(args[0])) {
                // Wave 4 · 4A tester I-1 收口：/lea class <create|delete|add|remove|list>
                String prefix = args[1].toLowerCase();
                for (String action : new String[]{"create", "delete", "add", "remove", "list"}) {
                    if (action.startsWith(prefix)) {
                        completions.add(action);
                    }
                }
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
        } else if (args.length == 3
                && "class".equalsIgnoreCase(args[0])
                && ("add".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
            // Wave 4 · 4A tester I-1 收口：/lea class add|remove <班名> <玩家>——第三参数补在线玩家名
            //（班名候选需查库，tab 补全在主线程不做 DB 访问，故第二参数不补班名）。
            plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .forEach(completions::add);
        } else if (args.length == 3 && "report".equalsIgnoreCase(args[0])) {
            // US-RPT-3: /lea report <玩家> <周期>（plan §3.3 第三参数候选）
            String prefix = args[2].toLowerCase();
            for (String period : new String[]{"week", "lastweek", "7d", "14d", "30d"}) {
                if (period.startsWith(prefix)) {
                    completions.add(period);
                }
            }
        }

        return completions;
    }
}
