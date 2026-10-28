package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.ProxyMessenger;
import com.letslearnenglish.minecraftplugin.core.manager.DataExporter;
import com.letslearnenglish.minecraftplugin.core.manager.HotReloadManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.io.File;

/**
 * Admin Command - Administrator command for plugin management.
 *
 * Subcommands:
 * - /lea reload     Hot-reload all configuration files
 * - /lea export     Export player learning data
 * - /lea reset      Reset a player's learning data
 * - /lea info       Show plugin information
 * - /lea diagnose   Read-only health report: prints EFFECTIVE values (not config values)
 * - /lea duel cancel &lt;player&gt;  Force-dismiss an active word duel (GM adjudication)
 * - /lea report     Generate a learning report for a player or all active players
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final LetsLearnEnglish plugin;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "reload", "export", "reset", "info", "diagnose", "help", "ignore-update",
            "broadcast", "exportwordbank", "importwordbank", "duel", "report", "class"
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

        // 子服总开关（server.enabled=false）：除 /lea reload 外一律拒绝。
        // 位置刻意在权限检查**之后** —— 未授权玩家先吃 no-permission，探测不到本服
        // 开关状态（否则任何人执行 /lea 就能得知这台子服是否装了英语学习功能）。
        // 放行 reload 的理由：它是开关的**装配闸门本身** —— reloadAll() 会用
        // RuntimeModules.classify 判断「该装 / 该拆 / 只刷配置」，改完 server.enabled
        // 执行一次 reload 就能双向生效，无需重启（见 HotReloadManager 与 config.yml 顶部注释）。
        if (!plugin.isFullyEnabled() && !"reload".equals(subCommand)) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "server.disabled"));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (subCommand) {
            case "reload":
                // 用返回的 ReloadResult 选文案，而不是 requery 一次 isFullyEnabled()：
                // reload 现在有五种结局（装上了 / 拆掉了 / 完整重载 / 只刷配置 / 失败），
                // 二元判据必然把其中至少两种成功折叠成同一个提示 —— 上一轮「绿字说已加载、
                // 玩家却发现功能没恢复」的谎报就是这么来的。命令层只管映射，不猜状态。
                HotReloadManager.ReloadResult reloadResult =
                        plugin.getHotReloadManager().reloadAll();
                sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                        reloadMessageKey(reloadResult)));
                break;
            case "export":
                // R9-H1：主线程仅取快照（UUID+名字），文件 IO 与 cache-miss 查库全异步。
                // US-RPT-3：DataExporter 返回 ExportResult 协议对象（原 "Export failed: ..."
                // 字符串前缀判定已消——P2-09 教训落地）。失败按字段判定，不需要嗅探内容。
                List<Player> onlineSnapshot = new ArrayList<>(plugin.getServer().getOnlinePlayers());
                List<UUID> exportIds = new ArrayList<>(onlineSnapshot.size());
                List<String> exportNames = new ArrayList<>(onlineSnapshot.size());
                for (Player p : onlineSnapshot) {
                    exportIds.add(p.getUniqueId());
                    exportNames.add(p.getName());
                }
                try {
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        DataExporter.ExportResult r = plugin.getDataExporter()
                                .exportAllData(exportIds, exportNames);
                        try {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (r.success()) {
                                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                                            "admin.export-complete", "file", r.filePath()));
                                } else {
                                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                                            "admin.export-failed"));
                                }
                            });
                        } catch (IllegalStateException e) {
                            // 关服竞态：调度器已关闭，放弃回投消息
                        }
                    });
                } catch (IllegalStateException e) {
                    // 插件停用窗口（调度器已关闭）：放弃本次导出
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "admin.export-failed"));
                }
                break;
            case "reset":
                // R9-L2 两段式确认：/lea reset <player> 只提示不执行；
                // /lea reset <player> confirm 才真正删除。控制台同式（统一路径，
                // 脚本自动化追加 confirm 即可），防手滑选错人一次性删光 15 张表。
                if (args.length == 2) {
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "admin.reset-confirm", "player", args[1]));
                } else if (args.length > 2 && "confirm".equalsIgnoreCase(args[2])) {
                    handleReset(sender, args[1]);
                } else if (args.length > 2) {
                    // 第三参数写了但不是 confirm（如 /lea reset X y 手误）：
                    // 重发确认提示，不做静默丢弃
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "admin.reset-confirm", "player", args[1]));
                } else {
                    sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "admin.usage-reset"));
                }
                break;
            case "info":
                sendPluginInfo(sender);
                break;
            case "diagnose":
                // R11：只读诊断——打印"生效值"（伞开关 / 运行时依赖 / 重启快照键），
                // 与 /lea info 的区别就在这里：info 报配置值，diagnose 报最终会不会真的工作。
                DiagnoseCommand.send(plugin, sender);
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

    /**
     * {@code /lea reload} 的结局 → i18n 键。抽成静态纯函数是为了能被单测直接钉住：
     * 任何一个枚举值漏映射都会退回「配置已重新加载」这条绿字，而绿字正是上一轮谎报的载体。
     *
     * <p>五分支说明：
     * <ul>
     *   <li>{@code ENABLED} —— 开关刚由 false 改成 true，运行时已装好，功能此刻可用；</li>
     *   <li>{@code DISABLED} —— 开关刚由 true 改成 false，运行时已拆掉，数据已落盘；</li>
     *   <li>{@code FULL} —— 本来就开着，常规完整重载；</li>
     *   <li>{@code CONFIG_ONLY} —— 运行时未装配，只读了配置。文案必须与「需重启」脱钩：
     *       reload 已能装卸，此状态的含义是「本服 server.enabled=false」或「上次装配失败」；</li>
     *   <li>{@code FAILED} —— 装配/拆解抛了异常，必须明确说「已回滚到关闭态，看控制台」，
     *       不能让服主误以为功能还在。</li>
     * </ul>
     *
     * <p>{@code null} 落到 {@code FAILED}：{@code reloadAll()} 正常不会返回 null，
     * 但旧版 HotReloadManager（或 mock 未打桩）会 —— 宁可报失败让人查，也不假装成功。
     */
    static String reloadMessageKey(HotReloadManager.ReloadResult result) {
        if (result == null) {
            return "server.enable-failed";
        }
        return switch (result) {
            case ENABLED -> "server.reload-enabled";
            case DISABLED -> "server.reload-disabled";
            case FULL -> "general.reload-success";
            case CONFIG_ONLY -> "server.reload-restart-required";
            case FAILED -> "server.enable-failed";
        };
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
        // R9-H3/R7-H1：命令层前置拒绝超长（上限与 ProxyMessenger 单源），
        // 防 writeUTF/网络层 32767 异常逃逸与踢信使玩家
        if (formatted.getBytes(StandardCharsets.UTF_8).length > ProxyMessenger.MAX_PAYLOAD_BYTES) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "admin.broadcast-too-long"));
            return;
        }
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
        // R9-L7：class 行补齐——与 SUBCOMMANDS 顺序对齐（class 紧随 report 之后），
        // 消除"tab 有 class、help 无说明"的不可发现缺口。
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.class"));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "admin-help.footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // 子服总开关（server.enabled=false）：补全是独立于 onCommand 的入口，
        // Bukkit 在玩家敲 Tab 时直接调用本方法。关闭态下绝大多数子命令会被 onCommand 拒，
        // 补全列表就只保留真正可用的那一个（reload）—— 既避免误导（补全出按了会被拒的
        // 子命令），也顺手绕开 args≥2 分支对 adminCommandHandler / wordManager /
        // duelManager 等 null Manager 的访问（那些分支在关闭态会 NPE）。
        if (!plugin.isFullyEnabled()) {
            if (args.length == 1 && sender.hasPermission("letslearnenglish.admin")
                    && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                completions.add("reload");
            }
            return completions;
        }

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
                // R9-M4：前缀 <2 不查库（在线名分支已覆盖；2 字符起库内名补全，
                // 防单字符前缀触发 player_data 全表扫）
                if (prefix.length() >= 2) {
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
