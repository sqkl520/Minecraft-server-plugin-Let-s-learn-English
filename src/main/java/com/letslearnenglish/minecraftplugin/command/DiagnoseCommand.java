package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.manager.StatisticsHttpServer;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * R11 "只改一件事" — 管理员诊断（{@code /lea diagnose}）。
 *
 * <p><b>为什么打印"生效值"而不是配置值：</b>服主工单的绝大多数是"我明明开了却没效果"——
 * 配置键写了 true，但上游还有伞开关（{@code progress-system.enabled}）、
 * 运行时依赖（Vault 未装 / api-key 为空 / 词库全空）、或"该键只在启动时快照、
 * reload 不生效"。本命令把这三类"配置值 ≠ 生效值"的落差一次性摊开，并给出
 * 该改哪里、要不要重启。
 *
 * <p><b>只读契约（硬约束）：</b>
 * <ul>
 *   <li>不写库、不改内存状态、不发任何网络请求；</li>
 *   <li>MySQL 探活只用 {@code Class.forName(driver, false, loader)}（{@code initialize=false}
 *       连静态块都不跑），<b>绝不建连接</b>——{@code database.type=mysql} 时连接在
 *       {@code onEnable} 已建立，这里只报"已连接"；</li>
 *   <li>不读表行数（避免全表扫），DB 侧只报类型 + 数据文件大小 + 最近备份时间。</li>
 * </ul>
 *
 * <p><b>输出语言：</b>纯英文技术行（管理员诊断，服主多为技术人员），不新增任何 i18n 键
 * （本批不允许改 {@code messages/*.yml}）。头/尾各一行为本类内置英文标题。
 *
 * <p><b>调用点：</b>
 * <ul>
 *   <li>命令：{@code AdminCommand} 的 {@code diagnose} 分支 → {@link #send(LetsLearnEnglish, CommandSender)}；</li>
 *   <li>启动自检：{@code LetsLearnEnglish#onEnable} 末尾 → {@link #logStartupSummary(LetsLearnEnglish)}。</li>
 * </ul>
 *
 * <p><b>线程：</b>全部方法须在主线程调用（读 config / Vault / Bukkit 插件管理器）。
 * 无阻塞 IO（只 {@code File#length()} 与目录列举）。
 */
public final class DiagnoseCommand {

    /** 诊断行的严重级别 —— 同时决定前缀符号与颜色。 */
    public enum Severity {
        /** 正常：绿色 [OK] */
        OK,
        /** 需要留意但可能是刻意配置：黄色 ! */
        WARN,
        /** 会直接让功能失效：红色 [X] */
        FAIL,
        /** 中性事实（版本号等）：灰色 [-] */
        INFO
    }

    /** 一条诊断结果（纯文本，不含颜色码；渲染在 {@link #render(Check)}）。 */
    public record Check(Severity severity, String text) {
    }

    /**
     * 完整诊断快照。
     *
     * @param checks      逐条状态（顺序即展示顺序）
     * @param restartKeys 本次会话内改了不生效、必须重启服务端的配置键
     */
    public record Report(List<Check> checks, List<String> restartKeys) {

        /** 统计某一级别的条数。 */
        public int count(Severity severity) {
            int n = 0;
            for (Check c : checks) {
                if (c.severity() == severity) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 启动时快照、{@code /lea reload} <b>不会</b>重新读取的配置键（硬编码清单）。
     *
     * <p>清单来源（逐条对应代码里的"构造期读取 / 排程期读取"点）：
     * <ul>
     *   <li>{@code tts.*} — {@code TtsManager} 构造时读取；</li>
     *   <li>{@code database.*} — {@code DatabaseManager.initialize()} 只在 enable 调；</li>
     *   <li>{@code general.backup.*} — {@code scheduleDatabaseBackup()} 启动排程时读取；</li>
     *   <li>{@code general.check-updates} — {@code onEnable} 内一次性判定；</li>
     *   <li>{@code general.auto-save-interval} — 周期任务排程时读取；</li>
     *   <li>{@code daily-reminder.reminder-hours} — {@code scheduleDailyReminders()} 读取；</li>
     *   <li>{@code word-system.review.intervals} / {@code mastered-review-interval-days} —
     *       {@code ReviewScheduler} 构造时读取（{@code reloadWordBanks()} 不重建它）；</li>
     *   <li>{@code progress-system.leaderboard.update-interval} — 周期任务排程时读取。</li>
     * </ul>
     * 注意：{@code ai_config.yml} 的 {@code api-key} 与 {@code ai-chat.*} <b>不在</b>此列——
     * {@code /lea reload} 会重读（{@code HotReloadManager} → {@code AIChatManager.reload()}）。
     */
    public static final List<String> RESTART_ONLY_KEYS = List.of(
            "tts.enabled",
            "tts.cache-days",
            "tts.voicerss-api-key",
            "database.type",
            "database.sqlite.filename",
            "database.mysql.*",
            "general.backup.enabled",
            "general.backup.interval",
            "general.backup.max-backups",
            "general.check-updates",
            "general.auto-save-interval",
            "daily-reminder.reminder-hours",
            "word-system.review.intervals",
            "word-system.review.mastered-review-interval-days",
            "progress-system.leaderboard.update-interval"
    );

    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    private static final String HEADER = "&6===== Let's Learn English! Diagnostics =====";
    private static final String FOOTER = "&6===========================================";

    /** 人工折行的宽度（字符数）；客户端仍会自动换行，这里只为控制台与阅读节奏。 */
    private static final int WRAP_WIDTH = 78;

    private DiagnoseCommand() {
        // 纯静态工具类
    }

    // ============================================================
    //  对外入口
    // ============================================================

    /**
     * 采集一次完整诊断（只读）。
     *
     * <p>本方法自身不吞异常——调用方 {@link #send} / {@link #logStartupSummary} 负责兜底，
     * 保证"诊断失败也绝不拖垮命令或开服"。
     */
    public static Report build(LetsLearnEnglish plugin) {
        List<Check> checks = new ArrayList<>(16);
        FileConfiguration cfg = plugin.getConfigManager().getMainConfig();

        String version = plugin.getDescription().getVersion();
        String serverVersion = plugin.getVersionAdapter() != null
                ? plugin.getVersionAdapter().getServerVersion() : "unknown";
        checks.add(new Check(Severity.INFO,
                "Plugin: v" + version + " / server " + serverVersion));

        // --- 数据库（类型 + 文件大小；不查表行数）---
        DatabaseManager db = plugin.getDatabaseManager();
        boolean sqlite = db != null && db.isSQLite();
        if (db == null) {
            checks.add(new Check(Severity.FAIL, "Database: not initialized"));
        } else if (sqlite) {
            String fileName = cfg.getString("database.sqlite.filename", "playerdata.db");
            File dbFile = new File(plugin.getDataFolder(), fileName);
            checks.add(new Check(Severity.OK, "Database: SQLite (" + fileName + ", "
                    + humanSize(dbFile.length()) + ")"));
        } else {
            // MySQL 的连接在 onEnable 已建立；这里只报结果，不重新连。
            String host = cfg.getString("database.mysql.host", "localhost");
            String dbName = cfg.getString("database.mysql.database", "letslearnenglish");
            checks.add(new Check(Severity.OK, "Database: MySQL connected (" + host + "/" + dbName + ")"));
        }

        // --- MySQL 驱动探活（只加载类，不建连接、不跑静态块）---
        boolean mysqlDriver;
        try {
            Class.forName(MYSQL_DRIVER, false, DiagnoseCommand.class.getClassLoader());
            mysqlDriver = true;
        } catch (Throwable t) {
            // ClassNotFoundException / NoClassDefFoundError / LinkageError 一视同仁
            mysqlDriver = false;
        }
        // 分级：只有"当前就是 MySQL 且驱动缺失"才是 FAIL；SQLite 服缺驱动只是潜在风险（WARN），
        // 否则每台 SQLite 服开局就见一个红 ✗，红叉会贬值成噪音。
        if (mysqlDriver) {
            checks.add(new Check(Severity.OK, "MySQL driver: bundled (" + MYSQL_DRIVER + ")"));
        } else if (!sqlite) {
            checks.add(new Check(Severity.FAIL,
                    "MySQL driver: MISSING — database.type=mysql cannot work"));
        } else {
            checks.add(new Check(Severity.WARN,
                    "MySQL driver: not bundled (harmless while database.type=sqlite)"));
        }

        // --- 最近一次备份（读目录时间戳，不打开备份文件）---
        checks.add(backupCheck(plugin, cfg, sqlite));

        // --- Vault 经济 ---
        Economy eco = plugin.getEconomy();
        boolean vaultPresent = plugin.getServer().getPluginManager().getPlugin("Vault") != null;
        checks.add(economyCheck(eco, vaultPresent));

        // --- AI 服务 ---
        boolean aiGate = plugin.getConfigManager().isAIChatEnabled();
        boolean aiKey = plugin.getAIChatManager() != null && plugin.getAIChatManager().isAvailable();
        String model = cfg.getString("ai-chat.model", "deepseek-chat");
        if (aiKey) {
            checks.add(new Check(Severity.OK, "AI service: api-key OK (model=" + model + ")"));
        } else if (aiGate) {
            checks.add(new Check(Severity.FAIL, "AI service: api-key MISSING — set 'api-key' in "
                    + "ai_config.yml, then run /lea reload"));
        } else {
            // 门关着且无 Key：这是"刻意关闭"，不是故障——用 WARN 而非 FAIL。
            checks.add(new Check(Severity.WARN,
                    "AI service: off by ai-chat.enabled=false (api-key missing)"));
        }

        // --- 词库（档数 + 词条总数；getAvailableDifficulties 已排序）---
        int banks = 0;
        int words = 0;
        if (plugin.getWordManager() != null) {
            for (String difficulty : plugin.getWordManager().getAvailableDifficulties()) {
                WordBank bank = plugin.getWordManager().getWordBank(difficulty);
                if (bank == null) {
                    continue;
                }
                banks++;
                words += bank.getWordCount();
            }
        }
        checks.add(banks == 0
                ? new Check(Severity.FAIL, "Word banks: none loaded — check words/*.yml")
                : new Check(Severity.OK, "Word banks: " + banks + " loaded / " + words + " words"));

        // --- 模块门（生效值；季节额外标注"开了但没开赛"）---
        boolean word = plugin.getConfigManager().isWordSystemEnabled();
        boolean daily = plugin.getConfigManager().isDailyChallengesEnabled();
        boolean duel = plugin.getConfigManager().isDuelEnabled();
        boolean seasonCfg = cfg.getBoolean("season.enabled", false);
        boolean seasonEffective = plugin.getSeasonManager() != null
                && plugin.getSeasonManager().isEnabled();
        boolean clazz = cfg.getBoolean("class-system.enabled", false);
        boolean social = plugin.getConfigManager().isSocialEnabled();
        boolean checkin = plugin.getConfigManager().isCheckinEnabled();
        boolean onboarding = plugin.getConfigManager().isOnboardingEnabled();

        // season 用"生效值"而非配置值：enabled=true 但锚点在未来时 SeasonManager 全系统休眠，
        // 这正是最费工单的"我开了却没反应"——所以额外标注原因，而不是只给一个 ✗。
        String seasonText = mark(seasonEffective) + (seasonCfg && !seasonEffective
                ? " (no active season)" : "");
        String modules = "Modules: word " + mark(word)
                + " | ai " + mark(aiGate)
                + " | daily " + mark(daily)
                + " | duel " + mark(duel)
                + " | season " + seasonText
                + " | class " + mark(clazz)
                + " | social " + mark(social)
                + " | checkin " + mark(checkin)
                + " | onboarding " + mark(onboarding);
        boolean anyModuleOff = !(word && aiGate && daily && duel && seasonEffective
                && clazz && social && checkin && onboarding);
        checks.add(new Check(anyModuleOff ? Severity.WARN : Severity.OK, modules));

        // --- 统计 HTTP（R-P2-1：必须读"是否真的在跑"，不能只看配置门）---
        // 绑定失败（端口被占/被防火墙拦）时 StatisticsHttpServer.start() 会置 running=false，
        // 但 statistics-server.enabled 仍是 true。只报配置值就是"绿灯打不开面板"。
        boolean statsConfigured = cfg.getBoolean("statistics-server.enabled", false);
        if (!statsConfigured) {
            checks.add(new Check(Severity.INFO,
                    "Statistics HTTP: disabled (statistics-server.enabled=false)"));
        } else {
            StatisticsHttpServer statsServer = plugin.getStatisticsHttpServer();
            int port = cfg.getInt("statistics-server.port", 8080);
            if (statsServer != null && statsServer.isRunning()) {
                checks.add(new Check(Severity.OK,
                        "Statistics HTTP: running on port " + port));
            } else {
                checks.add(new Check(Severity.FAIL,
                        "Statistics HTTP: enabled but NOT running on port " + port
                                + " — the port is probably already in use; see the startup log"));
            }
        }

        // --- 金币三道闸（生效值判定，含 Vault 这一运行时依赖）---
        checks.add(coinCheck(plugin, cfg, eco != null));

        return new Report(List.copyOf(checks), RESTART_ONLY_KEYS);
    }

    /** 纯文本报告（无颜色码），供日志/后续工具消费。 */
    public static List<String> collectReport(LetsLearnEnglish plugin) {
        Report report = build(plugin);
        List<String> lines = new ArrayList<>(report.checks().size() + 2);
        for (Check c : report.checks()) {
            lines.add("[" + c.severity() + "] " + c.text());
        }
        lines.add("[INFO] Keys needing a restart: " + String.join(", ", report.restartKeys()));
        return lines;
    }

    /**
     * 输出给命令发送者（玩家或控制台）。异常自吞——诊断失败只打印一行错误，绝不抛到命令层。
     */
    public static void send(LetsLearnEnglish plugin, CommandSender sender) {
        sender.sendMessage(color(HEADER));
        try {
            Report report = build(plugin);
            for (Check c : report.checks()) {
                sender.sendMessage(render(c));
            }
            List<String> wrapped = wrap("&eKeys read at startup — changing them needs a restart: ",
                    "&e  ", report.restartKeys());
            for (String line : wrapped) {
                sender.sendMessage(color(line));
            }
        } catch (Throwable t) {
            sender.sendMessage(color("&cDiagnostics failed: " + t));
            plugin.getLogger().log(Level.WARNING, "Diagnostics command failed", t);
        }
        sender.sendMessage(color(FOOTER));
    }

    /**
     * 开服自检：<b>只有存在 FAIL 才打 WARNING 块</b>，否则只打一行 INFO 汇总。
     *
     * <p>异常自吞（{@code Throwable}），保证 {@code onEnable} 永远能继续走到注册监听器/命令之后的收尾。
     * 建议接线位置：{@code LetsLearnEnglish#onEnable} 的最后（updateChecker 之后、收尾 banner 之前），
     * 此时 DB / 词库 / Vault / AI / 统计服务均已就绪，读到的是最终生效值。
     */
    public static void logStartupSummary(LetsLearnEnglish plugin) {
        try {
            Report report = build(plugin);
            int fails = report.count(Severity.FAIL);
            int warns = report.count(Severity.WARN);
            int oks = report.count(Severity.OK);
            if (fails == 0) {
                plugin.getLogger().info("Diagnostics: " + oks + " OK, " + warns
                        + " warning(s), no problems. Run /lea diagnose for details.");
                return;
            }
            plugin.getLogger().warning("Diagnostics found " + fails
                    + " problem(s) — the features behind them will not work:");
            for (Check c : report.checks()) {
                if (c.severity() == Severity.FAIL || c.severity() == Severity.WARN) {
                    plugin.getLogger().warning("  [" + c.severity() + "] " + c.text());
                }
            }
            plugin.getLogger().warning("Run /lea diagnose in-game (or from console) for the full report.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Startup diagnostics failed to run", t);
        }
    }

    // ============================================================
    //  各检查项
    // ============================================================

    private static Check backupCheck(LetsLearnEnglish plugin, FileConfiguration cfg, boolean sqlite) {
        if (!sqlite) {
            return new Check(Severity.WARN, "Backup: unsupported for MySQL (only SQLite is backed up)");
        }
        if (!cfg.getBoolean("general.backup.enabled", true)) {
            return new Check(Severity.WARN, "Backup: disabled (general.backup.enabled=false)");
        }
        File backupDir = new File(plugin.getDataFolder(), "backups");
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".bak"));
        if (files == null || files.length == 0) {
            return new Check(Severity.WARN, "Backup: none yet (first backup after "
                    + cfg.getInt("general.backup.interval", 1440) + " min)");
        }
        File newest = files[0];
        for (File f : files) {
            if (f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return new Check(Severity.OK, "Backup: " + newest.getName()
                + " (" + ago(newest.lastModified()) + ")");
    }

    private static Check economyCheck(Economy eco, boolean vaultPresent) {
        if (eco == null) {
            return vaultPresent
                    ? new Check(Severity.FAIL,
                            "Vault economy: Vault installed but no Economy provider registered")
                    : new Check(Severity.WARN, "Vault economy: not installed (coin rewards unavailable)");
        }
        String name;
        try {
            name = eco.getName();
        } catch (Throwable t) {
            name = "unknown";
        }
        boolean enabled;
        try {
            enabled = eco.isEnabled();
        } catch (Throwable t) {
            enabled = true;
        }
        return enabled
                ? new Check(Severity.OK, "Vault economy: hooked (" + name + ")")
                : new Check(Severity.WARN, "Vault economy: provider " + name + " reports disabled");
    }

    /**
     * 金币三道闸 + 运行时依赖。
     *
     * <p>注意 {@code RewardEngine.isMoneyEnabled()} 只 AND 三个配置键，<b>不含 Vault</b>
     * ——各发币入口另在 {@code eco == null} 处早退。本行把 Vault 一并列出，才是真正的"生效值"。
     */
    private static Check coinCheck(LetsLearnEnglish plugin, FileConfiguration cfg, boolean vault) {
        boolean progress = cfg.getBoolean("progress-system.enabled", true);
        boolean rewards = plugin.getConfigManager().isRewardsEnabled();
        boolean moneyType = cfg.getBoolean("progress-system.rewards.types.money.enabled", false);
        boolean pays = progress && rewards && moneyType && vault;
        int cap = cfg.getInt("money.daily-cap", 500);
        String gates = "progress-system " + mark(progress)
                + " / rewards " + mark(rewards)
                + " / money.enabled " + mark(moneyType)
                + " / Vault " + mark(vault);
        String capText = cap > 0 ? cap + "/day cap" : "no daily cap";
        return pays
                ? new Check(Severity.OK, "Coins: " + gates + " -> paid (" + capText + ")")
                : new Check(Severity.WARN, "Coins: " + gates + " -> NOT paid (" + capText + ")");
    }

    // ============================================================
    //  渲染 / 格式化
    // ============================================================

    private static String render(Check c) {
        // R-P2-5：前缀必须 ASCII 安全 —— Windows 默认控制台代码页是 GBK(936)，
        // ✔ ✗ • 不在码表内，会被输出成 '?'（实测）。诊断命令的主要读者是服主，
        // 而服主的控制台可能正是 GBK；用 [OK]/[!]/[X]/[-] 换取"一定看得见"。
        String prefix = switch (c.severity()) {
            case OK -> "&a[OK] ";
            case WARN -> "&e[!] ";
            case FAIL -> "&c[X] ";
            case INFO -> "&7[-] ";
        };
        return color(prefix + c.text());
    }

    private static String mark(boolean on) {
        // R-P2-5：ASCII 安全（GBK 控制台下 ✔/✗ 会变成 '?'，见 render 注释）。
        return on ? "ON" : "OFF";
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    private static String ago(long timestampMillis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestampMillis) / 1000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }

    /** 按宽度折行，逗号分隔；续行统一缩进（含颜色码时长度估算略偏大，可接受）。 */
    private static List<String> wrap(String firstPrefix, String indent, List<String> items) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder(firstPrefix);
        boolean first = true;
        for (int i = 0; i < items.size(); i++) {
            String token = items.get(i) + (i == items.size() - 1 ? "" : ", ");
            if (!first && line.length() + token.length() > WRAP_WIDTH) {
                out.add(line.toString());
                line = new StringBuilder(indent);
            }
            line.append(token);
            first = false;
        }
        out.add(line.toString());
        return out;
    }
}
