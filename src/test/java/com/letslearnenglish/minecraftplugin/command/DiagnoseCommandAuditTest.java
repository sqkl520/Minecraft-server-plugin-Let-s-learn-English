package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.manager.StatisticsHttpServer;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round 25 audit: {@code /lea diagnose} 的判定正确性、失败方向与敏感信息面。
 *
 * <p><b>为什么不能走 {@code build(plugin)} 全链路：</b>{@code ConfigManager#getMainConfig()}
 * 可以 mock，但 {@code getDataFolder()} / {@code getServer()} / {@code getDescription()}
 * 在 {@code JavaPlugin} 上是 <b>final</b>（已用 javap 核实），默认 mock maker 无法 stub。
 * 为了不改 pom（引入 mockito-inline 属跨切面改动、归编排者），本测试对
 * {@code ConfigManager} 用 mock + 真实 {@link YamlConfiguration} 实例，
 * 并对包内可见的纯判定函数（{@code economyCheck} / {@code backupCheck} / {@code coinCheck} /
 * {@code ago} / {@code humanSize} / {@code wrap}）逐个喂边界值。
 *
 * <p><b>定位：</b>审计的「可观测断言」层。R25 时发现真缺陷但<b>不允许改 main/</b>，
 * 于是用 {@code @Disabled} + 期望断言记录（见 F 段）。<b>R26 三个 P0 的修复已落地，
 * 对应用例全部摘掉 {@code @Disabled} 并按新契约断言</b>——F 段现在是回归护栏，
 * 不再是"待修清单"。除原 F1/F1b/F2/F3 外还补了 F1c/F1d/F1e（阈值为负同判、正常路径不许被
 * 新分支吞掉、诊断门槛必须与 {@code scheduleDatabaseBackup} 一致）与
 * F3b/F3c/F3d（库文件缺失、探测不得关共享连接、语句失败也算坏）。
 */
class DiagnoseCommandAuditTest {

    private static final String ZH = "messages/zh.yml";
    private static final String EN = "messages/en.yml";

    @TempDir
    Path tempDir;

    private Logger logger;
    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration main;
    private FileConfiguration zh;
    private FileConfiguration en;
    private CommandSender sender;
    private final List<String> sentMessages = new ArrayList<>();
    /** R26：一条真实可用的 SQLite 连接（内存库），供连通性探测通过。 */
    private java.sql.Connection healthyConnection;

    /** 记录型 Msg：把 key 与占位符原样拼出，便于断言「选了哪个 key」「填了什么值」。 */
    private static final class RecordingMsg implements DiagnoseCommand.Msg {
        final List<String> keys = new ArrayList<>();

        @Override
        public String get(String key, String... placeholders) {
            keys.add(key);
            StringBuilder sb = new StringBuilder("[").append(key);
            if (placeholders != null) {
                for (int i = 0; i + 1 < placeholders.length; i += 2) {
                    sb.append(' ').append(placeholders[i]).append('=').append(placeholders[i + 1]);
                }
            }
            return sb.append(']').toString();
        }

        String last() {
            return keys.get(keys.size() - 1);
        }
    }

    /** 收集 Logger 输出，用于断言启动自检的级别与内容。 */
    private static final class CollectingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<String> messagesAt(Level level) {
            List<String> out = new ArrayList<>();
            for (LogRecord r : records) {
                if (r.getLevel().equals(level)) {
                    out.add(r.getMessage() + (r.getThrown() == null ? "" : " | thrown=" + r.getThrown()));
                }
            }
            return out;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        logger = Logger.getLogger("DiagnoseCommandAuditTest." + System.nanoTime());
        logger.setUseParentHandlers(false);

        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        main = new YamlConfiguration();
        zh = load(ZH);
        en = load(EN);

        // R26：连通性探测需要一条<b>真</b>连接 —— 用内存 SQLite（sqlite-jdbc 是 test scope）。
        // 不能用 mock(Connection.class)：探测考的是"语句真的能跑通吗"，
        // 而 mock 的 createStatement() 默认返回 null → NPE → 全部用例变成 FAIL 分支。
        healthyConnection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(main);
        when(configManager.getMessageConfig()).thenReturn(zh);
        when(configManager.getMessageConfig("zh")).thenReturn(zh);
        when(configManager.getMessageConfig("en")).thenReturn(en);

        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getVersion()).thenReturn("1.0.7");
        when(plugin.getDescription()).thenReturn(desc);
        // 模块门默认值与 ConfigManager 实现一致（全部 true）
        when(configManager.isAIChatEnabled()).thenReturn(true);
        when(configManager.isWordSystemEnabled()).thenReturn(true);
        when(configManager.isDailyChallengesEnabled()).thenReturn(true);
        when(configManager.isDuelEnabled()).thenReturn(true);
        when(configManager.isSocialEnabled()).thenReturn(true);
        when(configManager.isCheckinEnabled()).thenReturn(true);
        when(configManager.isOnboardingEnabled()).thenReturn(true);
        when(configManager.isRewardsEnabled()).thenReturn(true);

        sender = mock(CommandSender.class);
        doAnswer(inv -> {
            sentMessages.add(inv.getArgument(0, String.class));
            return null;
        }).when(sender).sendMessage(anyString());
    }

    /** 健康的 DatabaseManager 桩：连接是真的，锁是真的。 */
    private DatabaseManager healthyDb(boolean sqlite) {
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.isSQLite()).thenReturn(sqlite);
        when(db.getConnectionLock()).thenReturn(new Object());
        when(db.getConnection()).thenReturn(healthyConnection);
        return db;
    }

    /**
     * 坏的 DatabaseManager 桩：{@code getConnection()} 抛异常
     * （与真实的 UNAVAILABLE 代理同口径 —— 它除 isClosed/close 外一律抛 SQLException）。
     *
     * <p>这是「连接已死」的可观测替身，比 {@code mock(Connection.class)} 更贴近现实：
     * 真实 DatabaseManager 在自愈失败时返回的正是这种"一碰就抛"的句柄。
     *
     * <p>这里抛的是 {@link RuntimeException} 而不是 {@code SQLException}：
     * {@code DatabaseManager#getConnection()} 未声明受检异常（javap 核实），
     * 编译期不允许在调用点抛受检异常。真实实现里"自愈失败"也会在
     * {@code catch (SQLException | RuntimeException)} 内被折算成 UNAVAILABLE 代理，
     * 所以两种形态对探测等价 —— 探测必须<b>两种都算失败</b>。
     */
    private DatabaseManager deadDb(boolean sqlite) {
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.isSQLite()).thenReturn(sqlite);
        when(db.getConnectionLock()).thenReturn(new Object());
        when(db.getConnection()).thenThrow(new RuntimeException("Database connection unavailable"));
        return db;
    }

    private static FileConfiguration load(String resource) {
        InputStream in = DiagnoseCommandAuditTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, "bundled resource missing: " + resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    // ============================================================
    //  A. 敏感信息面（最高优先级）
    // ============================================================

    /**
     * 静态扫描：诊断链路里<b>根本不应该读取</b>的机密配置键。
     *
     * <p>诊断报告会落 {@code exports/diagnose-*.txt}，而它的使用场景是
     * 「服主截图 / 整段贴到 QQ 群求助」。当前实现只输出
     * {@code database.mysql.host} + {@code database.mysql.database}，不碰
     * {@code database.mysql.password} / {@code database.mysql.username} / {@code api-key}，
     * 也不输出玩家 UUID / IP。本用例把这条不变量钉住，防未来某次"顺手多报一点"。
     *
     * <p><b>R26 改动：</b>{@code proxy.inbound-secret} 从"一律禁读"放宽为
     * "允许读存在性，禁止把值写进任何文案"。原因是本轮 F2 修复必须知道它空不空
     * （空 = 入站帧不校验签名）。这里保留静态层判据，真正权威的保证是
     * {@link #secretValueNeverAppearsInTheReport()}。
     */
    @Test
    @DisplayName("A1 源码不读取任何机密配置键（password / username / api-key / voicerss）")
    void sourceNeverReadsSecretConfigKeys() throws IOException {
        String source = readSource();
        for (String forbidden : List.of(
                "database.mysql.password",
                "database.mysql.username",
                "\"api-key\"",
                "voicerss-api-key")) {
            assertFalse(source.contains("getString(\"" + forbidden + "\"")
                            || source.contains("get(\"" + forbidden + "\"")
                            || source.contains("getInt(\"" + forbidden + "\""),
                    "DiagnoseCommand reads a secret config key: " + forbidden
                            + " — the report gets pasted into public chat/forums");
        }
        // R26：proxy.inbound-secret 允许读存在性（用来判断"是否配置"），
        // 但密钥值不得出现在任何 msg.get(...) 的实参里。
        for (String callSite : msgGetCallSites(source)) {
            assertFalse(callSite.contains("inboundSecret"),
                    "the proxy secret value must never be passed into a message: " + callSite);
        }
    }

    /**
     * 行为断言（R26 F2 的权威护栏）：把一个显眼的哨兵值塞进
     * {@code proxy.inbound-secret}，用<b>真实 zh.yml 文案</b>生成报告，
     * 报告正文里必须一个字符都不出现。
     *
     * <p>为什么需要它而不只靠 A1 的源码扫描：源码扫描靠变量名匹配，
     * 改名（{@code inboundSecret} → {@code sec}）就静默失效；而报告是否泄露
     * 密钥只由最终文本决定，这是唯一不会自证的判据。
     */
    @Test
    @DisplayName("A5 密钥值绝不出现在报告正文（哪怕它已配置）")
    void secretValueNeverAppearsInTheReport() {
        stubFullBuildPath(true);
        main.set("proxy.server-name", "survival-1");
        main.set("proxy.inbound-secret", "SENTINEL-7f3a9c-DO-NOT-LEAK");

        String text = allText(buildWithRealText());
        assertFalse(text.contains("SENTINEL-7f3a9c-DO-NOT-LEAK"),
                "the proxy secret leaked into the report: " + text);
        assertTrue(text.contains("proxy.inbound-secret"),
                "the report must still say the key is configured: " + text);
    }

    /**
     * 从源码里按括号深度切出每个 {@code msg.get(...)} 的完整实参文本。
     *
     * <p>行级 {@code contains} 判据不成立：一个调用点常跨 3-5 行
     * （{@code backup.interval-off-stale} 就是），单行匹配会把跨行实参漏掉。
     */
    private static List<String> msgGetCallSites(String source) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = source.indexOf("msg.get(", from);
            if (start < 0) {
                return out;
            }
            int open = start + "msg.get".length();
            int depth = 0;
            boolean inQuote = false;
            int close = -1;
            for (int i = open; i < source.length(); i++) {
                char c = source.charAt(i);
                if (inQuote) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inQuote = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inQuote = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            if (close < 0) {
                return out;
            }
            out.add(source.substring(start, close + 1));
            from = close + 1;
        }
    }

    /** 源码里不能出现把玩家 UUID / IP 塞进报告正文的调用。 */
    @Test
    @DisplayName("A2 源码不把玩家 UUID / IP 写进报告")
    void sourceNeverEmitsPlayerIdentifiers() throws IOException {
        String source = readSource();
        for (String forbidden : List.of("getUniqueId()", "getAddress()", "getPlayer()")) {
            assertFalse(source.contains(forbidden),
                    "DiagnoseCommand embeds player identifier: " + forbidden);
        }
    }

    /**
     * 行为断言：MySQL 分支只输出 host + database。
     *
     * <p>故意把敏感值塞进配置 —— 若实现哪天开始打印它们，本断言会挂。
     * 用的是真实 {@code messages/zh.yml} 文案（不是桩），才能证明服主眼睛看到的
     * 那一行确实干净。
     */
    @Test
    @DisplayName("A3 MySQL 诊断行只出现 host/database，不出现用户名或口令")
    void mysqlLineCarriesNoCredentials() {
        main.set("database.type", "mysql");
        main.set("database.mysql.host", "db.example.com");
        main.set("database.mysql.database", "lle");
        main.set("database.mysql.password", "hunter2-SUPER-SECRET");
        main.set("database.mysql.username", "lle_admin");

        DatabaseManager db = healthyDb(false);
        when(plugin.getDatabaseManager()).thenReturn(db);
        Server server = serverWithPluginManager(mock(Plugin.class));
        when(plugin.getServer()).thenReturn(server);

        // 用真实 zh.yml 文案 + 真实占位符代入，断言服主实际看到的那一行。
        DiagnoseCommand.Report report = buildWithRealText();
        String text = allText(report);
        assertTrue(text.contains("db.example.com"), "host should be reported: " + text);
        assertFalse(text.contains("hunter2-SUPER-SECRET"), "password leaked into the report!");
        assertFalse(text.contains("lle_admin"), "username leaked into the report!");
    }

    /**
     * SQLite 分支报的是文件名（playerdata.db），不是绝对路径。
     * 绝对路径会暴露服务器的磁盘布局（D:\Server\MCServers\...），
     * 截图发群里就等于公布了目录结构。
     */
    @Test
    @DisplayName("A4 SQLite 诊断行只报文件名，不报绝对路径")
    void sqliteLineCarriesFileNameOnly() throws Exception {
        main.set("database.type", "sqlite");
        main.set("database.sqlite.filename", "playerdata.db");
        // R26：库文件必须真实存在且非空，否则走的是 db.sqlite-missing 分支。
        writeFile(tempDir.resolve("playerdata.db"), "x");

        DatabaseManager db = healthyDb(true);
        when(plugin.getDatabaseManager()).thenReturn(db);
        Server server = serverWithPluginManager(null);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        String text = allText(buildWithRealText());
        assertTrue(text.contains("playerdata.db"), "filename should be reported: " + text);
        assertFalse(text.contains(tempDir.toAbsolutePath().toString()),
                "absolute data folder path leaked into the report: " + text);
    }

    // ============================================================
    //  B. 判定逻辑边界
    // ============================================================

    /**
     * {@code economyCheck} 的三态判据：
     * eco==null && vaultPresent -> FAIL；eco==null && !vaultPresent -> WARN；
     * eco.isEnabled() -> OK，否则 WARN。
     */
    @Test
    @DisplayName("B1 economyCheck：无 Vault 是 WARN，不是 FAIL（避免红叉贬值）")
    void economyWithoutVaultIsWarning() throws Exception {
        DiagnoseCommand.Check c = invokeEconomyCheck(null, false);
        assertEquals(DiagnoseCommand.Severity.WARN, c.severity());
        assertTrue(c.text().contains("economy.absent"));
    }

    @Test
    @DisplayName("B2 economyCheck：装了 Vault 但无 provider 是 FAIL")
    void economyWithVaultButNoProviderFails() throws Exception {
        DiagnoseCommand.Check c = invokeEconomyCheck(null, true);
        assertEquals(DiagnoseCommand.Severity.FAIL, c.severity());
        assertTrue(c.text().contains("economy.no-provider"));
    }

    @Test
    @DisplayName("B3 economyCheck：provider 自身禁用只报 WARN，并带上 provider 名")
    void economyDisabledProviderIsWarning() throws Exception {
        Economy eco = mock(Economy.class);
        when(eco.getName()).thenReturn("Essentials");
        when(eco.isEnabled()).thenReturn(false);
        DiagnoseCommand.Check c = invokeEconomyCheck(eco, true);
        assertEquals(DiagnoseCommand.Severity.WARN, c.severity());
        assertTrue(c.text().contains("provider=Essentials"), "provider name must be shown: " + c.text());
    }

    @Test
    @DisplayName("B4 economyCheck：provider 正常时报 OK 并带上 provider 名")
    void economyHookedIsOk() throws Exception {
        Economy eco = mock(Economy.class);
        when(eco.getName()).thenReturn("CMI");
        when(eco.isEnabled()).thenReturn(true);
        DiagnoseCommand.Check c = invokeEconomyCheck(eco, true);
        assertEquals(DiagnoseCommand.Severity.OK, c.severity());
        assertTrue(c.text().contains("economy.hooked"));
    }

    /**
     * 失败方向审计锚点：eco.isEnabled() 抛异常时，实现把 enabled 兜底成 true，
     * 最终渲染 [OK] Vault 经济: 已接入。这是「检查本身出错却报 OK」的唯一一处。
     * 当前行为如实记录在此；缺陷结论写在 AUDIT.md，改动权在编排者。
     */
    @Test
    @DisplayName("B5 失败方向：isEnabled() 抛异常时被兜底成 OK（现状记录，非期望行为）")
    void economyCheckSwallowsIsEnabledFailureAsHealthy() throws Exception {
        Economy eco = mock(Economy.class);
        when(eco.getName()).thenReturn("BrokenEco");
        when(eco.isEnabled()).thenThrow(new IllegalStateException("provider blew up"));
        DiagnoseCommand.Check c = invokeEconomyCheck(eco, true);
        assertEquals(DiagnoseCommand.Severity.OK, c.severity(),
                "CURRENT behaviour: an exploding isEnabled() is reported as OK — "
                        + "this is the audit finding, not a desired contract");
    }

    /**
     * coinCheck 的四闸 AND：progress && rewards && moneyType && vault。
     * 关键契约：money.daily-cap 只影响文案（cap / no-cap），不影响 pays ——
     * cap=0 是「不限制」而不是「不发钱」。搞反了服主会去调一个根本不是病因的键。
     */
    @Test
    @DisplayName("B6 coinCheck：四闸全开才 OK；Vault 缺失即 WARN")
    void coinCheckRequiresAllFourGates() throws Exception {
        main.set("progress-system.enabled", true);
        main.set("progress-system.rewards.types.money.enabled", true);
        when(configManager.isRewardsEnabled()).thenReturn(true);

        assertEquals(DiagnoseCommand.Severity.OK, invokeCoinCheck(true).severity(),
                "all four gates on -> paid");
        assertEquals(DiagnoseCommand.Severity.WARN, invokeCoinCheck(false).severity(),
                "Vault missing -> not paid even when every config gate is on");
    }

    @Test
    @DisplayName("B7 coinCheck：四闸缺一即降级 WARN（逐闸验证，不许遗漏任何一闸）")
    void coinCheckDegradesOnAnySingleGate() throws Exception {
        main.set("progress-system.enabled", false);
        main.set("progress-system.rewards.types.money.enabled", true);
        assertEquals(DiagnoseCommand.Severity.WARN, invokeCoinCheck(true).severity(),
                "progress-system.enabled=false must block payouts");

        main.set("progress-system.enabled", true);
        main.set("progress-system.rewards.types.money.enabled", false);
        assertEquals(DiagnoseCommand.Severity.WARN, invokeCoinCheck(true).severity(),
                "money.enabled=false must block payouts");

        main.set("progress-system.rewards.types.money.enabled", true);
        when(configManager.isRewardsEnabled()).thenReturn(false);
        assertEquals(DiagnoseCommand.Severity.WARN, invokeCoinCheck(true).severity(),
                "rewards master gate off must block payouts");

        when(configManager.isRewardsEnabled()).thenReturn(true);
        assertEquals(DiagnoseCommand.Severity.WARN, invokeCoinCheck(false).severity(),
                "vault missing must block payouts");
    }

    /**
     * money.daily-cap 分档：cap > 0 才显示具体上限。
     * RewardEngine（399/795 行）与 config.yml 都写「0 = 不限制」，coinCheck 用 cap > 0
     * 分档，负数同样落到 no-cap —— 与 RewardEngine 的 <= 0 语义一致。本用例钉住这条对齐。
     */
    @Test
    @DisplayName("B8 coinCheck：daily-cap 0 与负数都报「无每日上限」，正数报具体值")
    void dailyCapZeroAndNegativeBothMeanUnlimited() throws Exception {
        main.set("progress-system.enabled", true);
        main.set("progress-system.rewards.types.money.enabled", true);

        main.set("money.daily-cap", 0);
        assertTrue(invokeCoinCheck(true).text().contains("coins.no-cap"),
                "cap=0 must render as 'no cap'");

        main.set("money.daily-cap", -1);
        assertTrue(invokeCoinCheck(true).text().contains("coins.no-cap"),
                "negative cap must render as 'no cap' (RewardEngine treats <=0 as unlimited)");

        main.set("money.daily-cap", 500);
        assertTrue(invokeCoinCheck(true).text().contains("cap=500"),
                "positive cap must be printed");
    }

    /**
     * backupCheck 四分支：非 SQLite / 关闭 / 无备份 / 有备份。
     * 「关闭」与「还没有」必须是两条不同文案：前者该去改配置，后者只需等待。
     * 混成一条会让服主修错方向。
     */
    @Test
    @DisplayName("B9 backupCheck：MySQL 不支持备份只报 WARN")
    void backupOnMysqlIsWarning() throws Exception {
        DiagnoseCommand.Check c = invokeBackupCheck(false, main);
        assertEquals(DiagnoseCommand.Severity.WARN, c.severity());
        assertTrue(c.text().contains("backup.unsupported"));
    }

    @Test
    @DisplayName("B10 backupCheck：enabled=false 报「已关闭」，不与「还没有备份」混淆")
    void backupDisabledIsDistinguishedFromMissing() throws Exception {
        main.set("general.backup.enabled", false);
        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertEquals(DiagnoseCommand.Severity.WARN, c.severity());
        assertTrue(c.text().contains("backup.disabled"),
                "must not be confused with backup.none: " + c.text());
        assertFalse(c.text().contains("backup.none"),
                "disabled must not render as none: " + c.text());
    }

    @Test
    @DisplayName("B11 backupCheck：无备份时报 WARN 并给出间隔分钟数")
    void backupNoneShowsInterval() throws Exception {
        main.set("general.backup.enabled", true);
        main.set("general.backup.interval", 90);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertEquals(DiagnoseCommand.Severity.WARN, c.severity());
        assertTrue(c.text().contains("minutes=90"), "interval must be surfaced: " + c.text());
    }

    /**
     * 有备份时取 mtime 最新那份。File#listFiles 的返回顺序不保证；原实现先 files[0]
     * 再线性找最大，本用例钉住「最终拿到的是最新那份」而不是「碰巧排在最前那份」，
     * 并验证 .bak 过滤（目录里的 .txt 不得被当成备份）。
     */
    @Test
    @DisplayName("B12 backupCheck：多份备份时取 mtime 最新，且只认 .bak")
    void backupPicksNewestByMtime() throws Exception {
        main.set("general.backup.enabled", true);
        File dir = tempDir.resolve("backups").toFile();
        assertTrue(dir.mkdirs() || dir.isDirectory());

        File older = new File(dir, "playerdata.db.20260101-000000.bak");
        File middle = new File(dir, "playerdata.db.20260115-000000.bak");
        File newer = new File(dir, "playerdata.db.20260202-000000.bak");
        for (File f : List.of(older, middle, newer)) {
            assertTrue(f.createNewFile(), "cannot create " + f);
        }
        assertTrue(older.setLastModified(1_000_000_000_000L));
        assertTrue(middle.setLastModified(1_500_000_000_000L));
        assertTrue(newer.setLastModified(1_700_000_000_000L));

        File noise = new File(dir, "readme.txt");
        assertTrue(noise.createNewFile());
        assertTrue(noise.setLastModified(1_900_000_000_000L));

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertEquals(DiagnoseCommand.Severity.OK, c.severity());
        assertTrue(c.text().contains(newer.getName()),
                "must pick the newest .bak, got: " + c.text());
        assertFalse(c.text().contains("readme.txt"), ".bak filter must exclude other files");
        assertFalse(c.text().contains(older.getName()), "must not pick an older backup");
    }

    /**
     * backupCheck 的过滤器只有 endsWith(".bak")，不带 startsWith(dbFileName)。
     * 与 retention 的过滤器（startsWith(dbFileName) && endsWith(".bak")）不一致：
     * backups/ 里若有别的插件/旧库名留下的 .bak，诊断会把那份「不是本库的备份」
     * 报成 [OK] 备份: xxx.bak（刚刚），而本库真正的备份可能已经很旧甚至不存在。
     * 本用例把当前（宽松）行为钉住，缺陷结论写在 AUDIT.md。
     */
    @Test
    @DisplayName("B13 备份归属：backups/ 里非本库的 .bak 会被当成「最近备份」上报（现状记录）")
    void backupCheckAcceptsForeignBakFiles() throws Exception {
        main.set("database.sqlite.filename", "playerdata.db");
        main.set("general.backup.enabled", true);

        File dir = tempDir.resolve("backups").toFile();
        assertTrue(dir.mkdirs() || dir.isDirectory());

        File realBackup = new File(dir, "playerdata.db.20250101-000000.bak");
        assertTrue(realBackup.createNewFile());
        assertTrue(realBackup.setLastModified(1_400_000_000_000L));

        File foreign = new File(dir, "otherplugin-data.20260301-000000.bak");
        assertTrue(foreign.createNewFile());
        assertTrue(foreign.setLastModified(System.currentTimeMillis()));

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertEquals(DiagnoseCommand.Severity.OK, c.severity());
        assertTrue(c.text().contains(foreign.getName()),
                "CURRENT behaviour: a foreign .bak is reported as the latest backup, got: " + c.text());
    }

    /** humanSize 三档边界：小于 1024 用严格小于 —— 1024 必须落到 KB 档。 */
    @Test
    @DisplayName("B14 humanSize：0/1023/1024/1MB 边界；1048575 舍入成 1024.0 KB（现状）")
    void humanSizeBoundaries() throws Exception {
        assertEquals("0 B", invokeHumanSize(0));
        assertEquals("1023 B", invokeHumanSize(1023));
        assertEquals("1.0 KB", invokeHumanSize(1024));
        // 现状：1048575 B 经 "%.1f KB" 四舍五入成 "1024.0 KB"（不是 "1.0 MB"）。
        // 纯显示瑕疵，不产生错误结论，故按现状断言并在 AUDIT.md 记录。
        assertEquals("1024.0 KB", invokeHumanSize(1024L * 1024L - 1));
        assertEquals("1.0 MB", invokeHumanSize(1024L * 1024L));
    }

    /** ago 四档边界 + 未来时间戳（时钟回拨 / mtime 被改到未来）必须钳成 0。 */
    @Test
    @DisplayName("B15 ago：四档边界正确，未来时间戳被钳成 0 秒")
    void agoBoundariesAndFutureClamp() throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(invokeAgo(now - 1_000L).contains("ago.seconds"),
                "1s ago should use the seconds branch");
        assertTrue(invokeAgo(now - 90_000L).contains("ago.minutes"),
                "90s ago should use the minutes branch");
        assertTrue(invokeAgo(now - 2L * 3600L * 1000L).contains("ago.hours"),
                "2h ago should use the hours branch");
        assertTrue(invokeAgo(now - 72L * 3600L * 1000L).contains("ago.days"),
                "72h ago should use the days branch");

        String future = invokeAgo(now + 300_000L);
        assertTrue(future.contains("n=0"),
                "future timestamp must clamp to 0, never a negative age: " + future);
    }

    /** wrap 折行：短列表一行、超宽列表多行且不丢元素、空列表仍出一行。 */
    @Test
    @DisplayName("B16 wrap：短列表一行、超宽列表折行不丢元素、空列表仍出一行")
    void wrapBehaviour() throws Exception {
        List<String> oneLine = invokeWrap(List.of("tts.enabled", "database.type"));
        assertEquals(1, oneLine.size(), "short list should stay on one line: " + oneLine);

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("some.very.long.configuration.key.number" + i);
        }
        List<String> wrapped = invokeWrap(many);
        assertTrue(wrapped.size() > 1, "long list should wrap, got " + wrapped.size() + " lines");
        String joined = String.join(" ", wrapped);
        for (String key : many) {
            assertTrue(joined.contains(key), "wrap dropped element: " + key);
        }

        List<String> empty = invokeWrap(List.of());
        assertEquals(1, empty.size(), "empty list still yields one (prefix-only) line");
    }

    /**
     * Report.count 必须按级别精确计数 —— 启动自检的「有 FAIL 才打 WARNING 块」全靠它，
     * 数错就等于漏报/误报。
     */
    @Test
    @DisplayName("B17 Report.count：按级别精确计数，空报告全为 0")
    void reportCountIsExact() {
        DiagnoseCommand.Report report = new DiagnoseCommand.Report(List.of(
                new DiagnoseCommand.Check(DiagnoseCommand.Severity.OK, "a"),
                new DiagnoseCommand.Check(DiagnoseCommand.Severity.OK, "b"),
                new DiagnoseCommand.Check(DiagnoseCommand.Severity.WARN, "c"),
                new DiagnoseCommand.Check(DiagnoseCommand.Severity.FAIL, "d"),
                new DiagnoseCommand.Check(DiagnoseCommand.Severity.INFO, "e")), List.of());
        assertEquals(2, report.count(DiagnoseCommand.Severity.OK));
        assertEquals(1, report.count(DiagnoseCommand.Severity.WARN));
        assertEquals(1, report.count(DiagnoseCommand.Severity.FAIL));
        assertEquals(1, report.count(DiagnoseCommand.Severity.INFO));

        DiagnoseCommand.Report empty = new DiagnoseCommand.Report(List.of(), List.of());
        for (DiagnoseCommand.Severity s : DiagnoseCommand.Severity.values()) {
            assertEquals(0, empty.count(s), "empty report should count 0 for " + s);
        }
    }

    /**
     * 模块门判决：任一模块关 -> WARN；全开 -> OK。
     * season 用的是「生效值」而非配置值：enabled=true 但锚点在未来时 SeasonManager 全休眠。
     * 这是最费工单的「我开了却没反应」形态，所以必须额外标注原因而不只给一个 OFF。
     */
    @Test
    @DisplayName("B18 模块门：任一关即 WARN，全开即 OK；season 用生效值并解释原因")
    void moduleGateAggregatesEverySwitch() {
        stubFullBuildPath(true);
        DiagnoseCommand.Check ok = findCheck(buildReport(), "modules.line");
        assertNotNull(ok, "no modules.line check produced");
        assertEquals(DiagnoseCommand.Severity.OK, ok.severity(),
                "every module on -> OK: " + ok.text());

        stubFullBuildPath(false);
        DiagnoseCommand.Check warn = findCheck(buildReport(), "modules.line");
        assertNotNull(warn, "no modules.line check produced");
        assertEquals(DiagnoseCommand.Severity.WARN, warn.severity(),
                "season enabled but inactive must downgrade to WARN");
        assertTrue(warn.text().contains("season-inactive"),
                "must explain WHY season is off, not just show OFF: " + warn.text());
    }

    /** 词库为零必须是 FAIL（不是 WARN）—— 一个词都没有时所有学习入口都会落空。 */
    @Test
    @DisplayName("B19 词库：零档是 FAIL；一档以上报 OK 并给出档数与词条数")
    void emptyWordBanksFail() {
        stubFullBuildPath(true);
        when(plugin.getWordManager()).thenReturn(null);
        DiagnoseCommand.Check none = findCheck(buildReport(), "words.none");
        assertNotNull(none, "expected a words.none check");
        assertEquals(DiagnoseCommand.Severity.FAIL, none.severity(),
                "zero word banks must be FAIL, not WARN");

        WordManagerImpl wm = mock(WordManagerImpl.class);
        WordBank bank = mock(WordBank.class);
        when(wm.getAvailableDifficulties()).thenReturn(java.util.Set.of("beginner"));
        when(wm.getWordBank("beginner")).thenReturn(bank);
        when(bank.getWordCount()).thenReturn(42);
        when(plugin.getWordManager()).thenReturn(wm);

        DiagnoseCommand.Check loaded = findCheck(buildReport(), "words.loaded");
        assertNotNull(loaded, "expected a words.loaded check");
        assertEquals(DiagnoseCommand.Severity.OK, loaded.severity());
        assertTrue(loaded.text().contains("banks=1") && loaded.text().contains("words=42"),
                "must surface bank count and word count: " + loaded.text());
    }

    /** AI 三态：有 key -> OK；无 key 但门开 -> FAIL；门关且无 key -> WARN。 */
    @Test
    @DisplayName("B20 AI：有 key 报 OK；门开无 key 报 FAIL；门关无 key 只报 WARN")
    void aiThreeStates() {
        stubFullBuildPath(true);

        AIChatManager ai = mock(AIChatManager.class);
        when(plugin.getAIChatManager()).thenReturn(ai);

        when(ai.isAvailable()).thenReturn(true);
        when(configManager.isAIChatEnabled()).thenReturn(true);
        DiagnoseCommand.Check ok = findCheck(buildReport(), "ai.ok");
        assertNotNull(ok);
        assertEquals(DiagnoseCommand.Severity.OK, ok.severity());

        when(ai.isAvailable()).thenReturn(false);
        when(configManager.isAIChatEnabled()).thenReturn(true);
        DiagnoseCommand.Check missing = findCheck(buildReport(), "ai.missing");
        assertNotNull(missing);
        assertEquals(DiagnoseCommand.Severity.FAIL, missing.severity(),
                "ai-chat.enabled=true with no api-key must be FAIL");

        when(configManager.isAIChatEnabled()).thenReturn(false);
        DiagnoseCommand.Check gateOff = findCheck(buildReport(), "ai.gate-off");
        assertNotNull(gateOff);
        assertEquals(DiagnoseCommand.Severity.WARN, gateOff.severity(),
                "deliberately disabled AI must not be a red X");
    }

    /**
     * 统计 HTTP：只看配置值会给出「绿灯但打不开面板」。
     * 必须在 enabled=true 且 isRunning()=false 时报 FAIL。
     */
    @Test
    @DisplayName("B21 统计 HTTP：enabled=true 但服务没在跑 -> FAIL；关闭 -> INFO")
    void statisticsHttpReportsEffectiveState() {
        stubFullBuildPath(true);
        main.set("statistics-server.enabled", true);
        main.set("statistics-server.port", 8080);

        StatisticsHttpServer notRunning = mock(StatisticsHttpServer.class);
        when(notRunning.isRunning()).thenReturn(false);
        when(plugin.getStatisticsHttpServer()).thenReturn(notRunning);
        DiagnoseCommand.Check fail = findCheck(buildReport(), "stats.not-running");
        assertNotNull(fail, "should report stats.not-running when the server is dead");
        assertEquals(DiagnoseCommand.Severity.FAIL, fail.severity(),
                "configured-but-not-running must be FAIL");

        StatisticsHttpServer running = mock(StatisticsHttpServer.class);
        when(running.isRunning()).thenReturn(true);
        when(plugin.getStatisticsHttpServer()).thenReturn(running);
        DiagnoseCommand.Check ok = findCheck(buildReport(), "stats.running");
        assertNotNull(ok);
        assertEquals(DiagnoseCommand.Severity.OK, ok.severity());

        main.set("statistics-server.enabled", false);
        DiagnoseCommand.Check disabled = findCheck(buildReport(), "stats.disabled");
        assertNotNull(disabled);
        assertEquals(DiagnoseCommand.Severity.INFO, disabled.severity(),
                "deliberately disabled stats must be INFO, not WARN/FAIL");
    }

    /** 数据库未初始化必须是 FAIL —— 这是「检查本身没数据」时唯一正确的方向。 */
    @Test
    @DisplayName("B22 数据库：null 报 FAIL；SQLite 报 OK 并带文件名与大小")
    void databaseNotInitializedFails() throws Exception {
        stubFullBuildPath(true);
        when(plugin.getDatabaseManager()).thenReturn(null);
        DiagnoseCommand.Check fail = findCheck(buildReport(), "db.not-initialized");
        assertNotNull(fail, "null DatabaseManager must produce db.not-initialized");
        assertEquals(DiagnoseCommand.Severity.FAIL, fail.severity());

        DatabaseManager db = healthyDb(true);
        when(plugin.getDatabaseManager()).thenReturn(db);
        main.set("database.sqlite.filename", "playerdata.db");
        writeFile(tempDir.resolve("playerdata.db"), "x");
        DiagnoseCommand.Check ok = findCheck(buildReport(), "db.sqlite");
        assertNotNull(ok);
        assertEquals(DiagnoseCommand.Severity.OK, ok.severity());
        assertTrue(ok.text().contains("file=playerdata.db"),
                "must surface the db file name: " + ok.text());
    }

    // ============================================================
    //  C. 端到端：失败方向不得报成 OK
    // ============================================================

    /**
     * mainConfig 为 null 时 build 的第一句就 NPE。send/save 会兜住并报一句 failed ——
     * 这是正确的失败方向。本用例钉住它，防止将来有人「顺手加个 null 守卫」
     * 把 NPE 变成一份静默的半空报告。
     */
    @Test
    @DisplayName("C1 mainConfig 为 null 时 build 抛异常（不静默吞成空报告）")
    void nullMainConfigPropagatesToCaller() {
        when(configManager.getMainConfig()).thenReturn(null);
        boolean threw = false;
        try {
            DiagnoseCommand.build(plugin, new RecordingMsg());
        } catch (Throwable t) {
            threw = true;
        }
        assertTrue(threw, "build() must not silently swallow a null main config");
    }

    /** send 在 build 抛异常时必须回一句 failed，而不是半个报告。 */
    @Test
    @DisplayName("C2 send：build 抛异常时回 failed，并仍打尾部分隔线")
    void sendReportsFailureInsteadOfHalfReport() {
        when(configManager.getMainConfig()).thenReturn(null);
        stubMessageUtilKeyEcho();
        final List<String> sent = sentMessages;

        DiagnoseCommand.send(plugin, sender);

        assertTrue(sent.stream().anyMatch(s -> s.contains("diagnose.failed")),
                "must report a failure line, got: " + sent);
        assertTrue(sent.stream().anyMatch(s -> s.contains("====")),
                "footer must still be printed, got: " + sent);
    }

    /**
     * save 在建目录失败时回 save.failed，绝不谎报 save.done。
     * 构造手法：把 exports 先建成一个普通文件，mkdirs() 必然失败。
     */
    @Test
    @DisplayName("C3 save：exports 无法创建时报 save.failed，不报 save.done")
    void saveDoesNotLieWhenDirectoryCannotBeCreated() throws Exception {
        stubFullBuildPath(true);
        stubMessageUtilKeyEcho();
        final List<String> sent = sentMessages;

        File dataFolder = tempDir.toFile();
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        File exports = new File(dataFolder, "exports");
        assertTrue(exports.createNewFile(), "cannot create blocker file");

        DiagnoseCommand.save(plugin, sender);

        assertTrue(sent.stream().anyMatch(s -> s.contains("diagnose.save.failed")),
                "must report save.failed, got: " + sent);
        assertFalse(sent.stream().anyMatch(s -> s.contains("diagnose.save.done")),
                "must NOT claim success when the write failed: " + sent);
    }

    /**
     * 成功路径：exports/ 下真的落了 diagnose-*.txt，内容无颜色码，
     * 且同一秒两次保存不互相覆盖（uniqueFileStamp 的三段式）。
     */
    @Test
    @DisplayName("C4 save：落盘 diagnose-*.txt，无颜色码，同秒两次不覆盖")
    void saveWritesPlainTextAndUniqueNames() throws Exception {
        stubFullBuildPath(true);
        stubMessageUtilKeyEcho();
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DiagnoseCommand.save(plugin, sender);
        DiagnoseCommand.save(plugin, sender);

        File[] files = new File(tempDir.toFile(), "exports")
                .listFiles((d, n) -> n.startsWith("diagnose-") && n.endsWith(".txt"));
        assertNotNull(files, "exports dir should exist after a successful save");
        assertEquals(2, files.length,
                "two saves in the same second must not overwrite each other");

        for (File f : files) {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            assertFalse(content.contains("\u00a7"),
                    "saved report must be colour-code free: " + f.getName());
            assertTrue(content.contains("diagnose.file.title"),
                    "report should carry the file.title header: " + f.getName());
            assertTrue(content.contains("diagnose.restart-keys"),
                    "report should carry the restart-keys trailer: " + f.getName());
        }
    }

    // ============================================================
    //  D. 启动自检的播报方向
    // ============================================================

    /**
     * 启动自检：零 FAIL 时只打一行 INFO，不刷 WARNING 块。
     * 反向契约（真 FAIL 必须打 WARNING）由 D2 覆盖。
     */
    @Test
    @DisplayName("D1 logStartupSummary：零 FAIL 时只打一行 INFO，不打 WARNING")
    void logStartupSummaryQuietWhenHealthy() throws Exception {
        stubFullBuildPath(true);
        // 造一个「零 FAIL」的配置：DB 已初始化、词库有词、AI 有 key、stats 关闭
        DatabaseManager db = healthyDb(true);
        when(plugin.getDatabaseManager()).thenReturn(db);
        writeFile(tempDir.resolve("playerdata.db"), "x");
        WordManagerImpl wm = mock(WordManagerImpl.class);
        WordBank bank = mock(WordBank.class);
        when(wm.getAvailableDifficulties()).thenReturn(java.util.Set.of("beginner"));
        when(wm.getWordBank("beginner")).thenReturn(bank);
        when(bank.getWordCount()).thenReturn(10);
        when(plugin.getWordManager()).thenReturn(wm);
        AIChatManager ai = mock(AIChatManager.class);
        when(ai.isAvailable()).thenReturn(true);
        when(plugin.getAIChatManager()).thenReturn(ai);
        Economy ecoD1 = mock(Economy.class);
        when(ecoD1.getName()).thenReturn("TestEco");
        when(ecoD1.isEnabled()).thenReturn(true);
        when(plugin.getEconomy()).thenReturn(ecoD1);
        when(plugin.getSeasonManager()).thenReturn(null);

        CollectingHandler handler = new CollectingHandler();
        logger.addHandler(handler);
        try {
            DiagnoseCommand.logStartupSummary(plugin);
        } finally {
            logger.removeHandler(handler);
        }

        // 契约：零 FAIL 时只打一行 INFO 汇总，一条 WARNING 都不打。
        List<String> warnings = handler.messagesAt(Level.WARNING);
        List<String> infos = handler.messagesAt(Level.INFO);
        assertEquals(List.of(), warnings,
                "a FAIL-free config must not print any WARNING: " + warnings);
        assertEquals(1, infos.size(),
                "exactly one INFO summary line expected: " + infos);
    }

    /**
     * 有 FAIL 时必须打 WARNING，且逐条列出 FAIL/WARN 行（不能只报一个数字）。
     */
    @Test
    @DisplayName("D2 logStartupSummary：有 FAIL 时打 WARNING 并逐条列出问题")
    void logStartupSummaryListsProblemsWhenFailing() {
        stubFullBuildPath(true);
        // DB 未初始化 = 必然 FAIL
        when(plugin.getDatabaseManager()).thenReturn(null);

        CollectingHandler handler = new CollectingHandler();
        logger.addHandler(handler);
        try {
            DiagnoseCommand.logStartupSummary(plugin);
        } finally {
            logger.removeHandler(handler);
        }

        List<String> warnings = handler.messagesAt(Level.WARNING);

        // 文案取自真实 messages/zh.yml（defaultLanguage 用服务端默认语言 zh），
        // 所以这里比对的是「服主在控制台真的会看到的那几句」，而不是 key 名。
        String problemsHeader = stripColor(zh.getString("diagnose.log.problems")
                .replace("{fail}", "1"));
        assertTrue(warnings.stream().anyMatch(m -> m != null && m.contains(problemsHeader)),
                "must announce the problems header [" + problemsHeader + "], got: " + warnings);

        // 启动日志走 plainPrefixOf（机器可读 [FAIL]），不是游戏内那个 [X]（见 prefixOf 注释）。
        String failPrefix = "[" + DiagnoseCommand.Severity.FAIL + "] ";
        assertTrue(warnings.stream().anyMatch(m -> m != null && m.contains(failPrefix)),
                "must print the FAIL-prefixed line per problem (" + failPrefix + "), got: " + warnings);

        String hint = stripColor(zh.getString("diagnose.log.hint"));
        assertTrue(warnings.stream().anyMatch(m -> m != null && m.contains(hint)),
                "must tell the admin how to see the full report, got: " + warnings);
    }

    // ============================================================
    //  E. 语言解析器
    // ============================================================

    /**
     * defaultLanguage 用的是 active 消息文件（getMessageConfig()），而不是按
     * general.language 原始串再去查一次 —— 所以 general.language: cn 这种非规范写法
     * 不会退化成英文（R14 修过一次同类缺陷，这里钉住）。
     */
    @Test
    @DisplayName("E1 defaultLanguage：general.language=cn 仍解析到 zh 文件")
    void defaultLanguageResolvesNonCanonicalLanguage() {
        main.set("general.language", "cn");
        DiagnoseCommand.Msg msg = DiagnoseCommand.defaultLanguage(plugin);
        assertEquals(ChatColor.translateAlternateColorCodes('&', zh.getString("diagnose.header")), msg.get("header"),
                "general.language=cn must still resolve to the zh file");
    }

    /** active 文件读不到时必须回落到 en（历史上诊断输出就是英文），不得 NPE。 */
    @Test
    @DisplayName("E2 defaultLanguage：active 文件读不到时回落到 en")
    void defaultLanguageFallsBackToEnglish() {
        when(configManager.getMessageConfig()).thenReturn(null);
        DiagnoseCommand.Msg msg = DiagnoseCommand.defaultLanguage(plugin);
        assertEquals(ChatColor.translateAlternateColorCodes('&', en.getString("diagnose.header")), msg.get("header"),
                "must fall back to the en file when the active one is unavailable");
    }

    /** 全部语言文件都读不到时，键名必须可见（不是空行，也不 NPE）。 */
    @Test
    @DisplayName("E3 所有语言文件缺失时返回可见的键名占位符")
    void rawFallbackShowsTheMissingKey() {
        when(configManager.getMessageConfig()).thenReturn(null);
        when(configManager.getMessageConfig("en")).thenReturn(null);
        DiagnoseCommand.Msg msg = DiagnoseCommand.defaultLanguage(plugin);
        String rendered = msg.get("header");
        assertTrue(rendered.contains("diagnose.header"),
                "a missing key must be visible as [?diagnose.header], got: " + rendered);
        assertFalse(rendered.isEmpty(), "never return an empty line for a missing key");
    }

    /**
     * 前缀覆盖优先级：config.yml 的 prefix.&lt;canonical&gt; 优先于语言文件自带的 prefix。
     * 归一化必须走 canonicalLanguage，否则 general.language: cn 会去查 prefix.cn（不存在），
     * 静默丢掉服主配的 prefix.zh —— 这正是 R14 GAP-2 修过的缺陷。
     */
    @Test
    @DisplayName("E4 前缀：general.language=cn 时仍能取到 config.yml 的 prefix.zh 覆盖")
    void prefixOverrideUsesCanonicalLanguage() {
        main.set("general.language", "cn");
        main.set("prefix.zh", "&8[自定义]&r ");
        DiagnoseCommand.Msg msg = DiagnoseCommand.defaultLanguage(plugin);
        String rendered = msg.get("failed", "error", "boom");
        assertTrue(rendered.contains("[自定义]"),
                "prefix.zh override must be applied even when general.language=cn: " + rendered);
    }

    // ============================================================
    //  F. R25 审计缺陷的回归护栏（R26 已全部修复并启用）
    // ============================================================

    /**
     * <b>F1（本审计最严重的「误报 OK」，R26 已修）：{@code general.backup.interval=0} 时
     * 诊断仍报「备份正常」或「首次备份在 0 分钟后」。
     *
     * <p>仓库里 {@code config.yml:91} 明写「interval: 备份间隔（分钟），0 表示禁用自动备份」，
     * {@code LetsLearnEnglish.scheduleDatabaseBackup()} 也确实在 {@code intervalMinutes <= 0}
     * 时直接 return（不排程）。但 {@code backupCheck} 只看 {@code general.backup.enabled}
     * 与 {@code backups/*.bak} 是否存在，<b>完全不看 interval</b>：
     * <ul>
     *   <li>无 .bak 时 → {@code [!] 备份: 还没有（首次备份在 0 分钟后）}
     *       ——「0 分钟后」既不可能发生又暗示马上就到；</li>
     *   <li>有旧 .bak 时 → {@code [OK] 备份: xxx.bak（40 天前）}
     *       —— 备份实际上<b>永久不会再生成</b>，诊断却给了绿灯。</li>
     * </ul>
     * 服主会因此以为自己有备份，直到真需要恢复时才发现最新那份是好几天前的。
     */
    @Test
    @DisplayName("F1 interval=0 时必须报「自动备份已停摆」，不得报「首次备份在 0 分钟后」")
    void backupIntervalZeroShouldNotLookHealthy() throws Exception {
        main.set("general.backup.enabled", true);
        main.set("general.backup.interval", 0); // config.yml: 0 = 禁用自动备份
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertFalse(c.text().contains("minutes=0"),
                "printing 'first backup in 0 minutes' is impossible and misleading: " + c.text());
        assertTrue(c.text().contains("backup.interval-off-none"),
                "must name the real cause (interval off), got: " + c.text());
        assertEquals(DiagnoseCommand.Severity.FAIL, c.severity(),
                "interval<=0 means backups will never run; must not be OK/WARN: " + c.text());
    }

    /**
     * <b>F1b：有旧备份 + interval=0 时给出绿灯（最危险的那一半）。</b>
     */
    @Test
    @DisplayName("F1b interval=0 且有旧备份时不得报 OK，且要报出最后一份有多旧")
    void staleBackupWithDisabledIntervalShouldNotBeOk() throws Exception {
        main.set("general.backup.enabled", true);
        main.set("general.backup.interval", 0);

        File dir = tempDir.resolve("backups").toFile();
        assertTrue(dir.mkdirs() || dir.isDirectory());
        File old = new File(dir, "playerdata.db.20200101-000000.bak");
        assertTrue(old.createNewFile());
        assertTrue(old.setLastModified(1_577_836_800_000L)); // 2020-01-01

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertEquals(DiagnoseCommand.Severity.FAIL, c.severity(),
                "backups are permanently disabled; a 2020 .bak must not earn an OK: " + c.text());
        assertTrue(c.text().contains(old.getName()),
                "must say which backup is the last one left: " + c.text());
        assertTrue(c.text().contains("backup.interval-off-stale"),
                "must name the real cause (interval off), got: " + c.text());
        assertFalse(c.text().contains("backup.latest"),
                "must not render the healthy 'latest backup' line: " + c.text());
    }

    /** F1c：负 interval 与 0 同义（scheduleDatabaseBackup 用的是 <= 0）。 */
    @Test
    @DisplayName("F1c interval<0 与 interval=0 同判（<= 0 都是一个语义）")
    void negativeIntervalBehavesLikeZero() throws Exception {
        main.set("general.backup.enabled", true);
        main.set("general.backup.interval", -5);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertEquals(DiagnoseCommand.Severity.FAIL, c.severity(),
                "negative interval must be treated as disabled, not as a huge interval: " + c.text());
        assertTrue(c.text().contains("backup.interval-off-none"),
                "negative interval must use the same 'off' branch: " + c.text());
    }

    /** F1d：interval 正常时行为不变（防止新分支把正常路径吞掉）。 */
    @Test
    @DisplayName("F1d interval=1440 且无备份 -> 仍是「还没有」的 WARN，不是停摆的 FAIL")
    void healthyIntervalStillReportsNoneAsWarning() throws Exception {
        main.set("general.backup.enabled", true);
        main.set("general.backup.interval", 1440);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DiagnoseCommand.Check c = invokeBackupCheck(true, main);
        assertEquals(DiagnoseCommand.Severity.WARN, c.severity(),
                "a scheduled-but-not-yet-run backup is only a WARN: " + c.text());
        assertTrue(c.text().contains("backup.none"), "must stay on the backup.none branch: " + c.text());
        assertFalse(c.text().contains("interval-off"),
                "must not claim backups are dead when they are scheduled: " + c.text());
    }

    /**
     * F1e（契约钉死）：诊断判「停摆」的门槛必须与真正排程备份的那段代码<b>一致</b>。
     *
     * <p>这是 F1 修复的<b>根因护栏</b>：F1 之所以是缺陷，本质是"诊断复述的生效值"与
     * "排程逻辑真正用的值"之间存在落差。若将来有人只改一边（比如把
     * {@code scheduleDatabaseBackup} 的阈值改成 {@code < 0}，或把诊断改成
     * {@code interval == 0}），diagnose 会重新开始给出错误结论——而两个方法各自
     * 的单测都仍然全绿。
     *
     * <p>做法：静态读两处源码，断言两边都用 {@code <= 0} 且都从
     * {@code general.backup.interval} 取值。改任一边而不同步就会红。
     */
    @Test
    @DisplayName("F1e 诊断的停摆门槛与 scheduleDatabaseBackup 的排程门槛必须一致（<= 0）")
    void intervalThresholdMatchesTheRealScheduler() throws IOException {
        String diagnose = readSource();

        String scheduleSrc = readRelative(
                "src/main/java/com/letslearnenglish/minecraftplugin/LetsLearnEnglish.java");
        int idx = scheduleSrc.indexOf("private void scheduleDatabaseBackup()");
        assertTrue(idx >= 0, "找不到 scheduleDatabaseBackup() —— 该方法被改名/移走了，本护栏失效");
        String scheduleBody = scheduleSrc.substring(idx, Math.min(scheduleSrc.length(), idx + 900));
        assertTrue(scheduleBody.contains("getInt(\"general.backup.interval\", 1440)"),
                "排程侧读取 interval 的方式变了，诊断侧必须同步：\n" + scheduleBody);
        assertTrue(scheduleBody.contains("intervalMinutes <= 0"),
                "排程侧的停摆判据不再是 <= 0，诊断侧必须同步：\n" + scheduleBody);

        // 诊断侧：同一键、同一默认值、同一 <= 0 判据
        assertTrue(diagnose.contains("getInt(\"general.backup.interval\", 1440)"),
                "诊断侧必须用与排程侧相同的键与默认值");
        assertTrue(diagnose.contains("if (interval <= 0)"),
                "诊断侧的停摆判据必须与排程侧同为 <= 0");
    }

    /** 从项目根读任意相对路径源码（readSource 只服务 DiagnoseCommand 这一个文件）。 */
    private static String readRelative(String relative) throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative + " from "
                + System.getProperty("user.dir"));
    }

    /**
     * <b>F2（漏报，安全相关，R26 已修）：{@code proxy.inbound-secret} 不在诊断覆盖范围内。</b>
     *
     * <p>该键为空时 {@code ProxyMessenger} <b>跳过全部入站帧签名校验</b>
     * （见 {@code ProxyMessenger:354} 的注释），于是任何改了客户端的玩家都能伪造
     * {@code GlobalMessage}（假服主公告）/ {@code AchievementUnlock} / {@code SeasonSettled}。
     * 键的注释本身写着「<b>强烈建议在多服网络中设置</b>」。
     *
     * <p>判据：{@code proxy.server-name} 为空 = 单服模式，不配是正常的；
     * 非空 = 多服，缺密钥才 WARN。
     */
    @Test
    @DisplayName("F2 多服网络 + 空 inbound-secret 必须 WARN；单服不配不打扰")
    void emptyInboundSecretOnMultiServerNetworkShouldBeWarned() throws Exception {
        // 多服 + 未配密钥 -> WARN，且要报出是哪个子服（RecordingMsg 只回显 key + 占位符，
        // 所以这里断的是"选了哪个 key / 传了什么值"；文案本身由下面的真实 zh.yml 段落覆盖）
        main.set("proxy.server-name", "survival-1");
        main.set("proxy.inbound-secret", "");
        DiagnoseCommand.Check warn = invokeProxySecretCheck(main);
        assertEquals(DiagnoseCommand.Severity.WARN, warn.severity(),
                "an unsigned multi-server network must not get a clean bill of health: " + warn.text());
        assertTrue(warn.text().contains("proxy.secret-missing"),
                "must name the missing-secret branch: " + warn.text());
        assertTrue(warn.text().contains("server=survival-1"),
                "must name the server it applies to: " + warn.text());

        // 多服 + 已配密钥 -> OK
        main.set("proxy.inbound-secret", "some-long-secret");
        DiagnoseCommand.Check ok = invokeProxySecretCheck(main);
        assertEquals(DiagnoseCommand.Severity.OK, ok.severity(),
                "a configured secret must be reported as OK: " + ok.text());
        assertTrue(ok.text().contains("proxy.secret-set"), "must use the secret-set branch: " + ok.text());

        // 单服 -> INFO（不配是开箱默认，不该是黄灯）
        main.set("proxy.server-name", "");
        main.set("proxy.inbound-secret", "");
        DiagnoseCommand.Check single = invokeProxySecretCheck(main);
        assertEquals(DiagnoseCommand.Severity.INFO, single.severity(),
                "single-server setups must not be nagged: " + single.text());
        assertTrue(single.text().contains("proxy.single-server"),
                "must use the single-server branch: " + single.text());
    }

    /**
     * F2 的文案层：真实 zh.yml 文案必须能让服主看懂"该去配哪个键"。
     *
     * <p>反射断言只能证明"选了哪个分支"，证明不了"那句话是否可执行"——
     * 把文案写成「代理签名: 未配置」而不点出 {@code proxy.inbound-secret}
     * 时 WARN 仍然正确出现，但服主不知道改什么。这里用真实文案补上这一环。
     */
    @Test
    @DisplayName("F2b 真实文案必须点名 proxy.inbound-secret，且同时出现在多服与单服两种情形")
    void inboundSecretWordingNamesTheKey() {
        stubFullBuildPath(true);
        main.set("proxy.server-name", "survival-1");
        main.set("proxy.inbound-secret", "");

        String text = allText(buildWithRealText());
        assertTrue(text.contains("proxy.inbound-secret"),
                "the warning must name the exact key to fix: " + text);
        assertTrue(text.contains("survival-1"),
                "the warning must name the affected backend server: " + text);

        // 已配置时也要说清楚"键名 + 已配置"，不能只给一句"已启用"
        main.set("proxy.inbound-secret", "x");
        String configured = allText(buildWithRealText());
        assertTrue(configured.contains("proxy.inbound-secret"),
                "the OK line must still say which key is protecting the network: " + configured);
    }

    /** F2b：只含空白的密钥等同未配置（ProxyMessenger 里是 trim 后判空）。 */
    @Test
    @DisplayName("F2b 空白字符的 inbound-secret 视为未配置")
    void blankInboundSecretCountsAsMissing() throws Exception {
        main.set("proxy.server-name", "survival-1");
        main.set("proxy.inbound-secret", "   ");
        DiagnoseCommand.Check c = invokeProxySecretCheck(main);
        assertEquals(DiagnoseCommand.Severity.WARN, c.severity(),
                "whitespace-only secret disables signature checks just like an empty one: " + c.text());
    }

    /**
     * <b>F3（报 OK 但实际已坏，R26 已修）：数据库连接不可用时仍报 {@code [OK] 数据库: ...}。</b>
     *
     * <p>{@code build()} 原先判数据库健康的全部依据只有 {@code db != null} 与
     * {@code db.isSQLite()}（后者是 {@code initialize()} 时按配置置位的 volatile 布尔），
     * <b>没有任何一步验证连接可用</b>。
     *
     * <p>现在 {@code probeConnection} 在锁内跑一句 {@code SELECT 1}：
     * 连接是 {@code UNAVAILABLE} 代理时它必抛 SQLException → 必须 FAIL。
     */
    @Test
    @DisplayName("F3 连接不可用时数据库行不得报 OK（SQLite / MySQL 两分支）")
    void deadConnectionShouldNotReportOk() {
        stubFullBuildPath(true);
        main.set("database.sqlite.filename", "playerdata.db");

        // SQLite：库文件在，但连接一碰就抛
        DatabaseManager sqliteDb = deadDb(true);
        when(plugin.getDatabaseManager()).thenReturn(sqliteDb);
        DiagnoseCommand.Check sqlite = findCheck(buildReport(), "db.sqlite-unavailable");
        assertNotNull(sqlite, "a dead SQLite connection must not report db.sqlite(OK)");
        assertEquals(DiagnoseCommand.Severity.FAIL, sqlite.severity(),
                "config-derived health is not health: " + sqlite.text());

        // MySQL：同样不得因"配置里写了 host"就报 OK
        main.set("database.type", "mysql");
        main.set("database.mysql.host", "db.example.com");
        main.set("database.mysql.database", "lle");
        DatabaseManager mysqlDb = deadDb(false);
        when(plugin.getDatabaseManager()).thenReturn(mysqlDb);
        DiagnoseCommand.Check mysql = findCheck(buildReport(), "db.mysql-unavailable");
        assertNotNull(mysql, "a dead MySQL connection must not report db.mysql(OK)");
        assertEquals(DiagnoseCommand.Severity.FAIL, mysql.severity(),
                "config-derived health is not health: " + mysql.text());
    }

    /**
     * F3b：审计漏掉的那一半 —— SQLite 库文件<b>不存在</b>（length()==0）时，
     * 原实现照样报 {@code [OK] 数据库: SQLite（playerdata.db，0 B）}。
     * 「0 字节的数据库」恰恰是最该报红的形态（文件被删/权限丢了/盘换了）。
     */
    @Test
    @DisplayName("F3b SQLite 库文件不存在或 0 字节时必须 FAIL")
    void missingSqliteFileMustFail() {
        stubFullBuildPath(true);
        main.set("database.sqlite.filename", "playerdata.db");
        // stubFullBuildPath 已写了一个非空文件；这里换成"文件不存在"。
        File dbFile = tempDir.resolve("playerdata.db").toFile();
        assertTrue(dbFile.delete() || !dbFile.exists());

        DiagnoseCommand.Check missing = findCheck(buildReport(), "db.sqlite-missing");
        assertNotNull(missing, "a missing sqlite file must not report db.sqlite(OK)");
        assertEquals(DiagnoseCommand.Severity.FAIL, missing.severity(),
                "a missing database file means all player data is gone: " + missing.text());

        // 0 字节同样算坏
        try {
            writeFile(tempDir.resolve("playerdata.db"), "");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        DiagnoseCommand.Check empty = findCheck(buildReport(), "db.sqlite-missing");
        assertNotNull(empty, "a zero-byte sqlite file must not report db.sqlite(OK)");
        assertEquals(DiagnoseCommand.Severity.FAIL, empty.severity(),
                "0 B is not a healthy database: " + empty.text());
    }

    /**
     * F3c：<b>探测不得关掉共享连接。</b>
     *
     * <p>本仓全局只有一个 Connection（{@code DatabaseManager} 单连接无池）。
     * 若探测写成 {@code try (Connection c = db.getConnection())}，诊断一跑完
     * 整个插件的连接就被关了 —— 一次排障动作把服务器打瘫。本用例用一条真连接
     * 验证探测之后它仍然可用。
     */
    @Test
    @DisplayName("F3c 探测结束后共享连接仍然可用（绝不被 close）")
    void probeDoesNotCloseTheSharedConnection() throws Exception {
        stubFullBuildPath(true);
        DiagnoseCommand.Check ok = findCheck(buildReport(), "db.sqlite");
        assertNotNull(ok, "healthy DB should report db.sqlite: " + allText(buildReport()));
        assertEquals(DiagnoseCommand.Severity.OK, ok.severity());

        assertFalse(healthyConnection.isClosed(),
                "the probe closed the shared connection — one diagnose run would take the server down");
        // 还能跑语句（不只是"没关上"）
        try (java.sql.Statement stmt = healthyConnection.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertTrue(rs.next(), "the shared connection must still work after a probe");
        }
    }

    /**
     * F3d：探测语句失败（非连接获取失败）同样要 FAIL —— 例如库被锁 / 表结构破坏 /
     * 只读文件系统。这里用"连接对象拿得到，但 createStatement 抛"的桩模拟。
     */
    @Test
    @DisplayName("F3d 取到连接但语句执行失败，同样不得报 OK")
    void statementFailureIsFail() throws Exception {
        stubFullBuildPath(true);
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.isSQLite()).thenReturn(true);
        when(db.getConnectionLock()).thenReturn(new Object());
        java.sql.Connection broken = mock(java.sql.Connection.class);
        when(broken.createStatement()).thenThrow(new java.sql.SQLException("database is locked"));
        when(db.getConnection()).thenReturn(broken);
        when(plugin.getDatabaseManager()).thenReturn(db);

        DiagnoseCommand.Check c = findCheck(buildReport(), "db.sqlite-unavailable");
        assertNotNull(c, "a failing probe statement must not report db.sqlite(OK)");
        assertEquals(DiagnoseCommand.Severity.FAIL, c.severity(),
                "'database is locked' is exactly what an admin needs to see: " + c.text());
    }

    /**
     * <b>F4（现状记录，不改）：{@code humanSize} 在 1 MB 边界前一字节四舍五入成 "1024.0 KB"。</b>
     *
     * <p>{@code String.format("%.1f KB", kb)} 把 1048575 B 打成 {@code 1024.0 KB}，
     * 而 1024 KB = 1 MB —— 「1024.0 KB」是个不存在于正常单位制的读数。
     * 纯显示瑕疵，<b>不产生错误结论</b>（服主仍能判断库有多大），故不列为需修缺陷。
     */
    @Test
    @DisplayName("F4[现状] humanSize：1048575 B 显示为 1024.0 KB（四舍五入，纯显示瑕疵）")
    void humanSizeRoundsUpToImpossibleReading() throws Exception {
        assertEquals("1024.0 KB", invokeHumanSize(1024L * 1024L - 1),
                "documented cosmetic quirk: should ideally read 1.0 MB");
    }


    // ============================================================
    //  反射桥：包内 private 判定函数
    // ============================================================

    private static java.lang.reflect.Method method(String name, Class<?>... params)
            throws NoSuchMethodException {
        java.lang.reflect.Method m = DiagnoseCommand.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private DiagnoseCommand.Check invokeEconomyCheck(Economy eco, boolean vault) throws Exception {
        return (DiagnoseCommand.Check) method("economyCheck", Economy.class, boolean.class,
                DiagnoseCommand.Msg.class).invoke(null, eco, vault, new RecordingMsg());
    }

    private DiagnoseCommand.Check invokeCoinCheck(boolean vault) throws Exception {
        return (DiagnoseCommand.Check) method("coinCheck", LetsLearnEnglish.class,
                FileConfiguration.class, boolean.class, DiagnoseCommand.Msg.class)
                .invoke(null, plugin, main, vault, new RecordingMsg());
    }

    private DiagnoseCommand.Check invokeBackupCheck(boolean sqlite, FileConfiguration cfg) throws Exception {
        return (DiagnoseCommand.Check) method("backupCheck", LetsLearnEnglish.class,
                FileConfiguration.class, boolean.class, DiagnoseCommand.Msg.class)
                .invoke(null, plugin, cfg, sqlite, new RecordingMsg());
    }

    private DiagnoseCommand.Check invokeProxySecretCheck(FileConfiguration cfg) throws Exception {
        return (DiagnoseCommand.Check) method("proxySecretCheck",
                FileConfiguration.class, DiagnoseCommand.Msg.class)
                .invoke(null, cfg, new RecordingMsg());
    }

    private static String invokeHumanSize(long bytes) throws Exception {
        return (String) method("humanSize", long.class).invoke(null, bytes);
    }

    private String invokeAgo(long ts) throws Exception {
        return (String) method("ago", long.class, DiagnoseCommand.Msg.class)
                .invoke(null, ts, new RecordingMsg());
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeWrap(List<String> items) throws Exception {
        return (List<String>) method("wrap", String.class, String.class, List.class)
                .invoke(null, "", "  ", items);
    }
    private static DiagnoseCommand.Check findCheck(DiagnoseCommand.Report report, String key) {
        for (DiagnoseCommand.Check c : report.checks()) {
            if (c.text() != null && c.text().startsWith("[" + key)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 用真实 messages/zh.yml 文案构造报告：把 diagnose.&lt;key&gt; 取出、代入占位符、
     * 再转成 § 颜色码 —— 与 fromConfig/defaultLanguage 的解析语义一致，
     * 这样断言的是「服主眼睛看到的文本」而不是 key 名。
     */
    private DiagnoseCommand.Report buildWithRealText() {
        return DiagnoseCommand.build(plugin, (key, placeholders) -> {
            String raw = zh.getString("diagnose." + key);
            if (raw == null) {
                return "[?diagnose." + key + "]";
            }
            String prefix = zh.getString("prefix", "");
            raw = raw.replace("{prefix}", prefix == null ? "" : prefix);
            if (placeholders != null) {
                for (int i = 0; i + 1 < placeholders.length; i += 2) {
                    raw = raw.replace("{" + placeholders[i] + "}",
                            placeholders[i + 1] == null ? "" : placeholders[i + 1]);
                }
            }
            return ChatColor.translateAlternateColorCodes('&', raw);
        });
    }

    /** 把报告的所有行拼成一段，便于做「不得包含 X」类断言。 */
    private static String allText(DiagnoseCommand.Report report) {
        StringBuilder sb = new StringBuilder();
        for (DiagnoseCommand.Check c : report.checks()) {
            sb.append(c.text()).append('\n');
        }
        return sb.toString();
    }

    private DiagnoseCommand.Report buildReport() {
        return DiagnoseCommand.build(plugin, new RecordingMsg());
    }

    /** 剥掉 & 颜色码与 § 转义，用于比对「服主实际看到的文本」。 */
    private static String stripColor(String s) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
    }

    /**
     * 读被测源码。
     *
     * <p>从 {@code user.dir} 向上最多 6 层找 {@code src/main/java/.../DiagnoseCommand.java}：
     * mvn 从模块根跑（第一跳即命中），而手工 runner/审计脚本可能从子目录跑。
     * 找不到就<b>抛异常而不是跳过</b> —— 安全护栏静默跳过等于没有护栏。
     */
    private static String readSource() throws IOException {
        Path relative = Path.of("src/main/java/com/letslearnenglish/minecraftplugin/command/"
                + "DiagnoseCommand.java");
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative + " from "
                + System.getProperty("user.dir") + " — the source-scan guards cannot run");
    }

    /** 让 MessageUtil 回显 key，便于断言「命令层选了哪一句文案」。 */
    private void stubMessageUtilKeyEcho() {
        MessageUtil util = mock(MessageUtil.class);
        when(plugin.getMessageUtil()).thenReturn(util);
        when(util.getSenderMessage(any(), anyString(), any(String[].class)))
                .thenAnswer(inv -> "MSG[" + inv.getArgument(1, String.class) + "]");
    }
    /** 造一个带 PluginManager 的 Server 桩；vaultPlugin 传 null 表示未安装 Vault。 */
    private static Server serverWithPluginManager(Plugin vaultPlugin) {
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(pm);
        when(pm.getPlugin("Vault")).thenReturn(vaultPlugin);
        return server;
    }

    /** 写一个小文件（库文件预置 / 非空判定用）。 */
    private static void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private void stubFullBuildPath(boolean seasonEffective) {
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getVersion()).thenReturn("1.0.7");
        when(plugin.getDescription()).thenReturn(desc);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(pm);
        when(pm.getPlugin("Vault")).thenReturn(mock(Plugin.class));
        when(plugin.getServer()).thenReturn(server);

        when(plugin.getVersionAdapter()).thenReturn(null);

        // R26：默认用健康 DB（真连接 + 真锁），并预置非空的库文件，
        // 这样"零 FAIL"用例才真的零 FAIL（否则 db.sqlite-missing 会插一条红叉）。
        DatabaseManager db = healthyDb(true);
        when(plugin.getDatabaseManager()).thenReturn(db);
        try {
            writeFile(tempDir.resolve("playerdata.db"), "x");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        Economy eco = mock(Economy.class);
        when(eco.getName()).thenReturn("TestEco");
        when(eco.isEnabled()).thenReturn(true);
        when(plugin.getEconomy()).thenReturn(eco);

        AIChatManager ai = mock(AIChatManager.class);
        when(ai.isAvailable()).thenReturn(true);
        when(plugin.getAIChatManager()).thenReturn(ai);

        WordManagerImpl wm = mock(WordManagerImpl.class);
        WordBank bank = mock(WordBank.class);
        when(wm.getAvailableDifficulties()).thenReturn(java.util.Set.of("beginner"));
        when(wm.getWordBank("beginner")).thenReturn(bank);
        when(bank.getWordCount()).thenReturn(20);
        when(plugin.getWordManager()).thenReturn(wm);

        SeasonManager season = mock(SeasonManager.class);
        when(season.isEnabled()).thenReturn(seasonEffective);
        when(plugin.getSeasonManager()).thenReturn(season);

        main.set("season.enabled", true);
        main.set("class-system.enabled", true);
        main.set("statistics-server.enabled", false);
        main.set("general.backup.enabled", false);
        main.set("database.type", "sqlite");
        main.set("database.sqlite.filename", "playerdata.db");
        main.set("ai-chat.model", "deepseek-chat");
    }
}
