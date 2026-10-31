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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
 * <p><b>输出语言（本批改版）：</b>报告正文全部走 {@code messages/*.yml} 的
 * {@code diagnose.*} 段，由 {@link Msg} 注入解析器。三种调用点的语言来源刻意不同：
 * <ul>
 *   <li>玩家执行命令 → 该玩家的语言（{@code MessageUtil#getSenderMessage}）；</li>
 *   <li>控制台/Rcon 执行命令 → {@code getSenderMessage} 对非玩家固定返回 en，
 *       与插件其余命令一致，控制台维持英文；</li>
 *   <li>开服自检日志 → 服务端默认语言（{@code general.language}）——这是服主自己配的
 *       语言，日志文件是 UTF-8；级别前缀 {@code [OK]/[!]/[X]/[-]} 两种语言都保持
 *       ASCII（见 {@link #render} 的 GBK 注释）。</li>
 * </ul>
 *
 * <p><b>调用点：</b>
 * <ul>
 *   <li>命令：{@code AdminCommand} 的 {@code diagnose} 分支 →
 *       {@link #send(LetsLearnEnglish, CommandSender)} / {@link #save}；</li>
 *   <li>启动自检：{@code LetsLearnEnglish#onEnable} 末尾 → {@link #logStartupSummary(LetsLearnEnglish)}。</li>
 * </ul>
 *
 * <p><b>线程：</b>{@link #build} 必须主线程调用（读 config / Vault / Bukkit 插件管理器）。
 * {@link #save} 的报告生成在主线程、写盘为同线程的小文件同步写（见其 javadoc 说明）。
 */
public final class DiagnoseCommand {

    /** 诊断行的严重级别 —— 决定 i18n 前缀键（{@code diagnose.prefix.*}）。 */
    public enum Severity {
        /** 正常：绿色 [OK] */
        OK,
        /** 需要留意但可能是刻意配置：黄色 [!] */
        WARN,
        /** 会直接让功能失效：红色 [X] */
        FAIL,
        /** 中性事实（版本号等）：灰色 [-] */
        INFO
    }

    /**
     * 一条诊断结果。
     *
     * <p>{@code text} 已经是<b>按某个语言解析过、且已转成 § 颜色码</b>的成品文本
     * （占位符与「生效值」都在 {@link #build} 内填好）。也就是说 {@link Check} 是
     * "某一次、某一语言的快照"，不跨语言复用——这比把模板 + 参数塞进 record 再由
     * 各渲染器二次解析简单得多，也不会出现"文件里带 {@code {model}} 没替换"的半成品。
     */
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
     * 文案解析器：{@code key} 是 {@code diagnose.} 之后的部分（如 {@code "db.sqlite"}），
     * 可变参数为 {@code "name", "value"} 交替的占位符对。
     *
     * <p>抽成函数式接口是为了让同一套 {@link #build} 逻辑服务三个语言来源
     * （玩家语言 / 控制台 en / 启动日志默认语言），而不必给每处检查项都塞一个
     * {@code CommandSender}——检查项与"谁在看"本就无关。
     */
    @FunctionalInterface
    public interface Msg {
        String get(String key, String... placeholders);
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

    /**
     * 尾部装饰线。只有等号、没有任何语言内容，故不建 i18n 键——建了也只是让
     * zh/en 各多一份同样的 43 个字符。头部含标题（{@code diagnose.header}）仍走 i18n。
     */
    private static final String FOOTER = "&6===========================================";

    /**
     * 人工折行的宽度（字符数）；客户端仍会自动换行，这里只为控制台与阅读节奏。
     */
    private static final int WRAP_WIDTH = 78;

    /** 报告文件名的时间戳格式（与 AdminCommandHandler 的词库导出口径一致）。 */
    private static final String FILE_STAMP = "yyyyMMdd-HHmmss";

    /** 报告正文里"生成时间"的格式（人读，含日期与秒）。 */
    private static final String FILE_TIME = "yyyy-MM-dd HH:mm:ss";

    private DiagnoseCommand() {
        // 纯静态工具类
    }

    // ============================================================
    //  对外入口
    // ============================================================

    /**
     * 采集一次完整诊断（只读），报告语言取<b>服务端默认语言</b>。
     *
     * <p>保留这个单参重载是为了不破坏既有调用方（日志/工具链）；需要指定语言时用
     * {@link #build(LetsLearnEnglish, Msg)}。
     */
    public static Report build(LetsLearnEnglish plugin) {
        return build(plugin, defaultLanguage(plugin));
    }

    /**
     * 采集一次完整诊断（只读），文案由 {@code msg} 解析。
     *
     * <p>本方法自身不吞异常——调用方 {@link #send} / {@link #save} /
     * {@link #logStartupSummary} 负责兜底，保证"诊断失败也绝不拖垮命令或开服"。
     */
    public static Report build(LetsLearnEnglish plugin, Msg msg) {
        List<Check> checks = new ArrayList<>(16);
        FileConfiguration cfg = plugin.getConfigManager().getMainConfig();

        String version = plugin.getDescription().getVersion();
        String serverVersion = serverVersion(plugin, msg);
        checks.add(new Check(Severity.INFO,
                msg.get("plugin", "version", version, "server", serverVersion)));

        // --- 数据库（类型 + 文件大小；不查表行数）---
        DatabaseManager db = plugin.getDatabaseManager();
        boolean sqlite = db != null && db.isSQLite();
        if (db == null) {
            checks.add(new Check(Severity.FAIL, msg.get("db.not-initialized")));
        } else if (sqlite) {
            String fileName = cfg.getString("database.sqlite.filename", "playerdata.db");
            File dbFile = new File(plugin.getDataFolder(), fileName);
            checks.add(new Check(Severity.OK, msg.get("db.sqlite",
                    "file", fileName, "size", humanSize(dbFile.length()))));
        } else {
            // MySQL 的连接在 onEnable 已建立；这里只报结果，不重新连。
            String host = cfg.getString("database.mysql.host", "localhost");
            String dbName = cfg.getString("database.mysql.database", "letslearnenglish");
            checks.add(new Check(Severity.OK, msg.get("db.mysql",
                    "host", host, "database", dbName)));
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
        // 否则每台 SQLite 服开局就见一个红叉，红叉会贬值成噪音。
        if (mysqlDriver) {
            checks.add(new Check(Severity.OK,
                    msg.get("driver.bundled", "driver", MYSQL_DRIVER)));
        } else if (!sqlite) {
            checks.add(new Check(Severity.FAIL, msg.get("driver.missing")));
        } else {
            checks.add(new Check(Severity.WARN, msg.get("driver.absent-sqlite")));
        }

        // --- 最近一次备份（读目录时间戳，不打开备份文件）---
        checks.add(backupCheck(plugin, cfg, sqlite, msg));

        // --- Vault 经济 ---
        Economy eco = plugin.getEconomy();
        boolean vaultPresent = plugin.getServer().getPluginManager().getPlugin("Vault") != null;
        checks.add(economyCheck(eco, vaultPresent, msg));

        // --- AI 服务 ---
        boolean aiGate = plugin.getConfigManager().isAIChatEnabled();
        boolean aiKey = plugin.getAIChatManager() != null && plugin.getAIChatManager().isAvailable();
        String model = cfg.getString("ai-chat.model", "deepseek-chat");
        if (aiKey) {
            checks.add(new Check(Severity.OK, msg.get("ai.ok", "model", model)));
        } else if (aiGate) {
            checks.add(new Check(Severity.FAIL, msg.get("ai.missing")));
        } else {
            // 门关着且无 Key：这是"刻意关闭"，不是故障——用 WARN 而非 FAIL。
            checks.add(new Check(Severity.WARN, msg.get("ai.gate-off")));
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
                ? new Check(Severity.FAIL, msg.get("words.none"))
                : new Check(Severity.OK, msg.get("words.loaded",
                        "banks", String.valueOf(banks), "words", String.valueOf(words))));

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
        // 这正是最费工单的"我开了却没反应"——所以额外标注原因，而不是只给一个红叉。
        String seasonText = (seasonCfg && !seasonEffective)
                ? msg.get("modules.season-inactive", "state", mark(seasonEffective))
                : mark(seasonEffective);
        String modules = msg.get("modules.line",
                "word", mark(word),
                "ai", mark(aiGate),
                "daily", mark(daily),
                "duel", mark(duel),
                "season", seasonText,
                "class", mark(clazz),
                "social", mark(social),
                "checkin", mark(checkin),
                "onboarding", mark(onboarding));
        boolean anyModuleOff = !(word && aiGate && daily && duel && seasonEffective
                && clazz && social && checkin && onboarding);
        checks.add(new Check(anyModuleOff ? Severity.WARN : Severity.OK, modules));

        // --- 统计 HTTP（R-P2-1：必须读"是否真的在跑"，不能只看配置门）---
        // 绑定失败（端口被占/被防火墙拦）时 StatisticsHttpServer.start() 会置 running=false，
        // 但 statistics-server.enabled 仍是 true。只报配置值就是"绿灯打不开面板"。
        boolean statsConfigured = cfg.getBoolean("statistics-server.enabled", false);
        if (!statsConfigured) {
            checks.add(new Check(Severity.INFO, msg.get("stats.disabled")));
        } else {
            StatisticsHttpServer statsServer = plugin.getStatisticsHttpServer();
            int port = cfg.getInt("statistics-server.port", 8080);
            if (statsServer != null && statsServer.isRunning()) {
                checks.add(new Check(Severity.OK,
                        msg.get("stats.running", "port", String.valueOf(port))));
            } else {
                checks.add(new Check(Severity.FAIL,
                        msg.get("stats.not-running", "port", String.valueOf(port))));
            }
        }

        // --- 金币三道闸（生效值判定，含 Vault 这一运行时依赖）---
        checks.add(coinCheck(plugin, cfg, eco != null, msg));

        return new Report(List.copyOf(checks), RESTART_ONLY_KEYS);
    }

    /** 纯文本报告（已剥颜色码），供日志/后续工具消费；语言取服务端默认语言。 */
    public static List<String> collectReport(LetsLearnEnglish plugin) {
        Msg msg = defaultLanguage(plugin);
        return plainLines(build(plugin, msg), msg);
    }

    /**
     * 输出给命令发送者（玩家或控制台）。异常自吞——诊断失败只打印一行错误，绝不抛到命令层。
     */
    public static void send(LetsLearnEnglish plugin, CommandSender sender) {
        Msg msg = forSender(plugin, sender);
        sender.sendMessage(msg.get("header"));
        try {
            Report report = build(plugin, msg);
            for (Check c : report.checks()) {
                sender.sendMessage(render(c, msg));
            }
            List<String> wrapped = wrap(msg.get("restart-keys"),
                    color("&e  "), report.restartKeys());
            for (String line : wrapped) {
                // 解析器已把 & 码转成 §；这里不再二次 translate，避免正文里偶发的
                // 字面 '&' 被当成颜色码吃掉一个字符。
                sender.sendMessage(line);
            }
        } catch (Throwable t) {
            sender.sendMessage(msg.get("failed", "error", String.valueOf(t)));
            plugin.getLogger().log(Level.WARNING, "Diagnostics command failed", t);
        }
        sender.sendMessage(color(FOOTER));
    }

    /**
     * {@code /lea diagnose save}：与 {@link #send} <b>同一份</b>报告，改写到
     * {@code <dataFolder>/exports/diagnose-<yyyyMMdd-HHmmss>.txt}（UTF-8）。
     *
     * <p><b>为什么同线程同步写：</b>报告生成必须主线程（读 config / Vault /
     * Bukkit 插件表），能异步的只剩"把一个几 KB 的字符串落盘"这一步；为它多开一个
     * 异步任务再 {@code runTask} 回投，会引入关服竞态（{@code AdminCommand} 的 export
     * 分支就得为 {@code IllegalStateException} 兜底）。几 KB 的顺序写是微秒级，
     * 同步更简单也更可靠。
     *
     * <p>落盘内容已剥掉颜色码：报告是给"整段贴进工单/论坛"用的，{@code §a} 这类
     * 转义字符在 Markdown/网页里只会变成乱码。
     *
     * <p>IO 失败只记 WARNING + 回一句 i18n 错误，绝不抛（命令层不允许异常逃逸）。
     */
    public static void save(LetsLearnEnglish plugin, CommandSender sender) {
        Msg msg = forSender(plugin, sender);
        File file;
        try {
            Report report = build(plugin, msg);
            File exportsDir = new File(plugin.getDataFolder(), "exports");
            if (!exportsDir.exists() && !exportsDir.mkdirs() && !exportsDir.isDirectory()) {
                throw new IOException("cannot create directory: " + exportsDir.getAbsolutePath());
            }
            String stamp = new SimpleDateFormat(FILE_STAMP, Locale.ROOT).format(new Date());
            file = new File(exportsDir, "diagnose-" + stamp + ".txt");
            Files.writeString(file.toPath(), fileContent(plugin, report, msg),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write the diagnostics report", e);
            sender.sendMessage(msg.get("save.failed", "error", String.valueOf(e.getMessage())));
            return;
        } catch (Throwable t) {
            // 报告生成本身炸了（NPE 等）：与 send 同口径，报一句就收手。
            plugin.getLogger().log(Level.WARNING, "Diagnostics command failed", t);
            sender.sendMessage(msg.get("failed", "error", String.valueOf(t)));
            return;
        }
        // 完整路径只进日志（运营排查面）；聊天里给相对路径，与 export/report 的展示口径一致，
        // 不向玩家暴露服务器磁盘布局。
        plugin.getLogger().info("Diagnostics report saved to: " + file.getAbsolutePath());
        sender.sendMessage(msg.get("save.done", "file", "exports/" + file.getName()));
    }

    /**
     * 开服自检：<b>只有存在 FAIL 才打 WARNING 块</b>，否则只打一行 INFO 汇总。
     *
     * <p>异常自吞（{@code Throwable}），保证 {@code onEnable} 永远能继续走到注册监听器/命令之后的收尾。
     * 建议接线位置：{@code LetsLearnEnglish#onEnable} 的最后（updateChecker 之后、收尾 banner 之前），
     * 此时 DB / 词库 / Vault / AI / 统计服务均已就绪，读到的是最终生效值。
     *
     * <p>语言取服务端默认语言（{@code general.language}），级别前缀仍是 ASCII 的
     * {@code [OK]/[!]/[X]/-}——GBK 控制台会把 ✔/✗ 打成 '?'（见 {@link #render} 注释），
     * 中文正文本身在 GBK 码表内，可以安全落到日志。
     */
    public static void logStartupSummary(LetsLearnEnglish plugin) {
        try {
            Msg msg = defaultLanguage(plugin);
            Report report = build(plugin, msg);
            int fails = report.count(Severity.FAIL);
            int warns = report.count(Severity.WARN);
            int oks = report.count(Severity.OK);
            if (fails == 0) {
                plugin.getLogger().info(plain(msg.get("log.ok",
                        "ok", String.valueOf(oks), "warn", String.valueOf(warns))));
                return;
            }
            plugin.getLogger().warning(plain(msg.get("log.problems",
                    "fail", String.valueOf(fails))));
            for (Check c : report.checks()) {
                if (c.severity() == Severity.FAIL || c.severity() == Severity.WARN) {
                    plugin.getLogger().warning(plain(plainPrefixOf(c.severity()) + c.text()));
                }
            }
            plugin.getLogger().warning(plain(msg.get("log.hint")));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Startup diagnostics failed to run", t);
        }
    }

    // ============================================================
    //  语言解析器
    // ============================================================

    /**
     * 按命令发送者取语言：玩家 → 该玩家语言；控制台/Rcon → {@code getSenderMessage}
     * 内部的固定 en（与插件其余命令一致）。
     */
    public static Msg forSender(LetsLearnEnglish plugin, CommandSender sender) {
        return (key, placeholders) -> plugin.getMessageUtil()
                .getSenderMessage(sender, "diagnose." + key, placeholders);
    }

    /** 固定英文（历史上诊断输出就是纯英文；留作默认语言文件读不到时的兜底）。 */
    private static Msg english(LetsLearnEnglish plugin) {
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig("en");
        return messages == null ? rawFallback() : fromConfig(plugin, messages, "en");
    }

    /** 服务端默认语言（{@code general.language} 选中的那份文件）。 */
    public static Msg defaultLanguage(LetsLearnEnglish plugin) {
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig();
        if (messages == null) {
            return english(plugin);
        }
        FileConfiguration main = plugin.getConfigManager().getMainConfig();
        String lang = main != null ? main.getString("general.language", "zh") : "zh";
        return fromConfig(plugin, messages, lang);
    }

    /**
     * 从一份 {@code messages/*.yml} 直接取值。
     *
     * <p>不走 {@code MessageUtil} 的原因：{@code MessageUtil} 只暴露"默认语言"和
     * "发送者语言"两个入口，而 {@code getSenderMessage} 对非玩家（控制台）固定返回 en，
     * 启动日志却要的是服务端默认语言（可能是 zh）。二者都不能由 sender 推导，
     * 所以这里按 {@code FileConfiguration} 取值；占位符与颜色码的语义与
     * {@code MessageUtil#getSenderMessage} 保持一致（{@code {prefix}} + 成对占位符），
     * 前缀解析也照抄 {@code MessageUtil#resolvePrefix} 的优先级（config.yml 的
     * {@code prefix.<lang>} 优先，其次语言文件自己的 {@code prefix}）。
     */
    private static Msg fromConfig(LetsLearnEnglish plugin, FileConfiguration messages, String lang) {
        String prefix = null;
        FileConfiguration main = plugin.getConfigManager().getMainConfig();
        if (main != null) {
            prefix = main.getString("prefix." + lang);
        }
        if (prefix == null || prefix.isEmpty()) {
            prefix = messages.getString("prefix", "");
        }
        final String resolvedPrefix = prefix;
        return (key, placeholders) -> {
            String full = "diagnose." + key;
            String raw = messages.getString(full);
            if (raw == null) {
                // 与 MessageUtil 同款可见占位符：少键时打出键名而不是静默空行——
                // 服主升级后旧 zh.yml 未合并新键时，一眼能看到缺哪一段。
                plugin.getLogger().warning("Missing message key: " + full);
                return color("&c[?" + full + "]");
            }
            raw = raw.replace("{prefix}", resolvedPrefix);
            if (placeholders != null) {
                for (int i = 0; i + 1 < placeholders.length; i += 2) {
                    raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
                }
            }
            return color(raw);
        };
    }

    /** 全部语言文件都读不到时的最后兜底：返回键名，永不 NPE。 */
    private static Msg rawFallback() {
        return (key, placeholders) -> color("&c[?diagnose." + key + "]");
    }

    // ============================================================
    //  各检查项
    // ============================================================

    private static Check backupCheck(LetsLearnEnglish plugin, FileConfiguration cfg,
                                     boolean sqlite, Msg msg) {
        if (!sqlite) {
            return new Check(Severity.WARN, msg.get("backup.unsupported"));
        }
        if (!cfg.getBoolean("general.backup.enabled", true)) {
            return new Check(Severity.WARN, msg.get("backup.disabled"));
        }
        int interval = cfg.getInt("general.backup.interval", 1440);
        File backupDir = new File(plugin.getDataFolder(), "backups");
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".bak"));
        if (files == null || files.length == 0) {
            return new Check(Severity.WARN,
                    msg.get("backup.none", "minutes", String.valueOf(interval)));
        }
        File newest = files[0];
        for (File f : files) {
            if (f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return new Check(Severity.OK, msg.get("backup.latest",
                "file", newest.getName(), "ago", ago(newest.lastModified(), msg)));
    }

    private static Check economyCheck(Economy eco, boolean vaultPresent, Msg msg) {
        if (eco == null) {
            return vaultPresent
                    ? new Check(Severity.FAIL, msg.get("economy.no-provider"))
                    : new Check(Severity.WARN, msg.get("economy.absent"));
        }
        String name;
        try {
            name = eco.getName();
        } catch (Throwable t) {
            name = msg.get("unknown");
        }
        boolean enabled;
        try {
            enabled = eco.isEnabled();
        } catch (Throwable t) {
            enabled = true;
        }
        return enabled
                ? new Check(Severity.OK, msg.get("economy.hooked", "provider", name))
                : new Check(Severity.WARN, msg.get("economy.disabled", "provider", name));
    }

    /**
     * 金币三道闸 + 运行时依赖。
     *
     * <p>注意 {@code RewardEngine.isMoneyEnabled()} 只 AND 三个配置键，<b>不含 Vault</b>
     * ——各发币入口另在 {@code eco == null} 处早退。本行把 Vault 一并列出，才是真正的"生效值"。
     */
    private static Check coinCheck(LetsLearnEnglish plugin, FileConfiguration cfg,
                                   boolean vault, Msg msg) {
        boolean progress = cfg.getBoolean("progress-system.enabled", true);
        boolean rewards = plugin.getConfigManager().isRewardsEnabled();
        boolean moneyType = cfg.getBoolean("progress-system.rewards.types.money.enabled", false);
        boolean pays = progress && rewards && moneyType && vault;
        int cap = cfg.getInt("money.daily-cap", 500);
        String gates = msg.get("coins.gates",
                "progress", mark(progress),
                "rewards", mark(rewards),
                "money", mark(moneyType),
                "vault", mark(vault));
        String capText = cap > 0
                ? msg.get("coins.cap", "cap", String.valueOf(cap))
                : msg.get("coins.no-cap");
        return pays
                ? new Check(Severity.OK, msg.get("coins.paid", "gates", gates, "cap", capText))
                : new Check(Severity.WARN, msg.get("coins.not-paid", "gates", gates, "cap", capText));
    }

    // ============================================================
    //  渲染 / 格式化
    // ============================================================

    /** 级别 → 彩色前缀键（{@code diagnose.prefix.*}），只用于聊天/游戏内展示。 */
    private static String prefixOf(Severity severity, Msg msg) {
        return switch (severity) {
            case OK -> msg.get("prefix.ok");
            case WARN -> msg.get("prefix.warn");
            case FAIL -> msg.get("prefix.fail");
            case INFO -> msg.get("prefix.info");
        };
    }

    /**
     * 级别 → <b>机器可读</b>前缀 {@code [OK]/[WARN]/[FAIL]/[INFO]}（无颜色、不翻译）。
     *
     * <p>刻意不进 i18n：{@code collectReport()} 的返回值是"给日志/后续工具消费"的
     * 纯文本协议（原实现的注释即如此承诺），枚举名本身就是稳定的 ASCII 标记；
     * 让服主把 {@code [FAIL]} 译成别的字，只会让脚本 grep 不到。展示层另走
     * {@link #prefixOf}（{@code [OK]/[!]/[X]/[-]} + 颜色）。
     */
    private static String plainPrefixOf(Severity severity) {
        return "[" + severity + "] ";
    }

    private static String render(Check c, Msg msg) {
        // R-P2-5：前缀必须 ASCII 安全 —— Windows 默认控制台代码页是 GBK(936)，
        // ✔ ✗ • 不在码表内，会被输出成 '?'（实测）。诊断命令的主要读者是服主，
        // 而服主的控制台可能正是 GBK；用 [OK]/[!]/[X]/[-] 换取"一定看得见"。
        // 四个前缀现在走 diagnose.prefix.*，两份 yml 的默认值同样保持 ASCII，
        // 服主若改成符号需自担控制台乱码（zh.yml / en.yml 里已写注释提醒）。
        // 前缀与正文都已由解析器转成 § 码，此处不再二次 translate。
        return prefixOf(c.severity(), msg) + c.text();
    }

    /**
     * 纯文本行（剥 § 颜色码 + 机器可读前缀）：用于文件、日志与 {@link #collectReport}。
     *
     * <p>尾行的 {@code [INFO]} 前缀必须保留：{@link #collectReport} 的返回格式在
     * 本类历史上就是 {@code "[INFO] Keys needing a restart: ..."}，把它换成一把
     * ASCII 破折号会悄悄改掉"给工具消费"的协议（哪怕当前仓库内无调用方，
     * 外部脚本/日志 grep 仍可能依赖）。
     */
    private static List<String> plainLines(Report report, Msg msg) {
        List<String> lines = new ArrayList<>(report.checks().size() + 2);
        for (Check c : report.checks()) {
            lines.add(plain(plainPrefixOf(c.severity()) + c.text()));
        }
        lines.add(plain(plainPrefixOf(Severity.INFO)
                + msg.get("restart-keys")) + String.join(", ", report.restartKeys()));
        return lines;
    }

    /**
     * 报告文件正文：自带三行头注（标题 / 版本 / 生成时间），让"整段贴出去的文本"
     * 不依赖上下文也能说明自己来自哪个插件版本、哪台服务端、什么时候跑的。
     */
    private static String fileContent(LetsLearnEnglish plugin, Report report, Msg msg) {
        String time = new SimpleDateFormat(FILE_TIME, Locale.ROOT).format(new Date());
        StringBuilder sb = new StringBuilder(2048);
        sb.append(plain(msg.get("file.title"))).append('\n');
        sb.append(plain(msg.get("file.meta",
                "version", plugin.getDescription().getVersion(),
                "server", serverVersion(plugin, msg)))).append('\n');
        sb.append(plain(msg.get("file.generated", "time", time))).append('\n');
        sb.append('\n');
        for (String line : plainLines(report, msg)) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String serverVersion(LetsLearnEnglish plugin, Msg msg) {
        return plugin.getVersionAdapter() != null
                ? plugin.getVersionAdapter().getServerVersion()
                : msg.get("unknown");
    }

    private static String mark(boolean on) {
        // R-P2-5：ASCII 安全（GBK 控制台下 ✔/✗ 会变成 '?'，见 render 注释）。
        return on ? "ON" : "OFF";
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String plain(String s) {
        // 先保证 & 码也转成 §（报告里可能出现仍是裸 & 码的片段），再整段剥掉颜色码：
        // 两步都在才保证落盘文件里一个颜色码都不剩。
        return ChatColor.stripColor(color(s));
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

    private static String ago(long timestampMillis, Msg msg) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestampMillis) / 1000);
        if (seconds < 60) {
            return msg.get("ago.seconds", "n", String.valueOf(seconds));
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return msg.get("ago.minutes", "n", String.valueOf(minutes));
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return msg.get("ago.hours", "n", String.valueOf(hours));
        }
        return msg.get("ago.days", "n", String.valueOf(hours / 24));
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
