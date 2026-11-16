package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * round12 · A4-LOW-2 回归：{@code StatisticsHttpServer} 对默认口令
 * <b>只告警不拒启</b>。
 *
 * <h2>为什么这条判 LOW，以及为什么「一律拒启」是错的</h2>
 * 默认配置是 {@code bind-address: 127.0.0.1} + {@code password: changeme}。
 * 只回环可达 ⇒ 面板本来就出不了本机，默认口令没有可利用的远端攻击面。
 * 因此「见默认口令就拒启」会把<b>开箱即用</b>变成「开了没反应」——
 * 服主把 {@code enabled} 改成 true 之后什么都不会发生，而这是他最不该踩的坑。
 *
 * <p>真正危险的是<b>组合</b>：默认/空口令 <b>×</b> 绑定到非回环地址。
 * 那时 Basic Auth 是最后一道门，而门锁是 {@code admin/changeme}，
 * 面板（含全部玩家学习数据）就以公开凭据暴露在网络上。这个组合没有正当用途。
 *
 * <h2>本类的三层判据</h2>
 * <ol>
 *   <li><b>默认配置（127.0.0.1 + changeme）必须能启动</b> —— 开箱即用是第一优先；</li>
 *   <li><b>默认口令 + 非回环绑定必须拒启</b>，且拒启只影响本组件
 *       （{@code isRunning()} 为 false，可被 diagnose 看见），不抛异常；</li>
 *   <li><b>改了口令 + 非回环绑定必须能启动</b> —— 拒启不得过度到「非回环一律拒」。</li>
 * </ol>
 *
 * <h2>被本类封死的变异</h2>
 * <ol>
 *   <li><b>把拒启条件改成「只要默认口令就拒」</b>→ {@code defaultConfigStillStarts} 红；</li>
 *   <li><b>删掉拒启</b>（回到「只告警」）→ {@code defaultPasswordOnNonLoopbackIsRefused} 红；</li>
 *   <li><b>把 {@code isLoopbackBind} 改成恒 true</b> → 同上红；</li>
 *   <li><b>把拒启改成 {@code throw}</b> → {@code defaultPasswordOnNonLoopbackIsRefused}
 *       的「不抛」断言红（拒启绝不能阻断插件主流程）。</li>
 * </ol>
 *
 * <h2>为什么用真 HttpServer 而不是 mock</h2>
 * 「能启动」与「拒启」的真实判据是 {@code isRunning()} 与端口是否真的在监听。
 * 这里刻意<b>不</b> mock {@code HttpServer}（com.sun.net.httpserver 是 JDK 内置类，
 * mock 它要额外依赖）。但为了在纯 JVM（无 Bukkit 服务端）里安全跑，
 * <b>端口选 0</b>（操作系统分配空闲端口）—— 这样既真的走 {@code HttpServer.create}，
 * 又不会与其它测试/服务抢 8080，也不会因为端口被占而随机红。
 */
@DisplayName("StatisticsHttpServer 默认口令与绑定的组合门（A4-LOW-2）")
class StatisticsHttpServerPasswordGateTest {

    /** 测试用的真实口令（非默认值）。 */
    private static final String REAL_PASSWORD = "s3cr3t-not-default";

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration config;
    private Logger logger;
    private CapturingHandler handler;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        config = new YamlConfiguration();

        logger = Logger.getLogger("StatisticsHttpServerPasswordGateTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
    }

    /** 造一个被测服务器；{@code bindAddress} 与 {@code password} 写进配置后构造。 */
    private StatisticsHttpServer newServer(String bindAddress, String password) {
        // port=0：让 OS 分配空闲端口。这样 create() 走真实路径又不会撞端口
        // （撞端口会走 IOException 分支，把「拒启」与「端口被占」混成同一种红）。
        config.set("statistics-server.port", 0);
        if (bindAddress != null) {
            config.set("statistics-server.bind-address", bindAddress);
        }
        if (password != null) {
            config.set("statistics-server.auth.password", password);
        }
        config.set("statistics-server.auth.username", "admin");
        config.set("statistics-server.ip-whitelist", List.of("127.0.0.1"));
        return new StatisticsHttpServer(plugin, mock(DatabaseManager.class));
    }

    // ==================================================================
    // 组 A —— 默认配置（回环绑定 + 默认口令）必须能启动
    // ==================================================================

    @Nested
    @DisplayName("组 A —— 开箱即用：默认配置必须能启动")
    class DefaultIsUsable {

        /**
         * <b>过度修复的证伪用例</b>：把拒启条件写成「只要默认口令就拒」会让这条红。
         * 默认 bind-address 是 127.0.0.1，面板出不了本机，默认口令没有远端攻击面。
         */
        @Test
        @DisplayName("bind-address=127.0.0.1 + 默认口令 changeme → 必须启动")
        void defaultConfigStillStarts() {
            StatisticsHttpServer server = newServer("127.0.0.1", "changeme");
            try {
                server.start();

                assertTrue(server.isRunning(),
                        "默认配置（回环绑定 + 默认口令）必须能启动喵 —— "
                                + "拒启会把「开箱即用」变成「开了没反应」，"
                                + "这是本组件最不该有的体验。"
                                + "\n日志：" + handler.describe());
            } finally {
                server.stop();
            }
        }

        @Test
        @DisplayName("bind-address 缺失（回落到 127.0.0.1）+ 默认口令 → 必须启动")
        void missingBindAddressFallsBackToLoopbackAndStarts() {
            StatisticsHttpServer server = newServer(null, "changeme");
            try {
                server.start();
                assertTrue(server.isRunning(),
                        "缺 bind-address 时构造器回落的默认值就是 127.0.0.1，"
                                + "与显式写出来等价，必须能启动喵。日志：" + handler.describe());
            } finally {
                server.stop();
            }
        }

        @Test
        @DisplayName("默认口令下启动仍必须留一条 WARNING（可见性不因新门而丢）")
        void defaultPasswordStillWarns() {
            StatisticsHttpServer server = newServer("127.0.0.1", "changeme");
            try {
                server.start();
                assertTrue(handler.hasAtLeast(Level.WARNING),
                        "默认口令启动仍必须打 WARNING 喵 —— 新加的「拒启」是针对危险组合，"
                                + "不该顺手把原有的可见性提示删掉。日志：" + handler.describe());
            } finally {
                server.stop();
            }
        }
    }

    // ==================================================================
    // 组 B —— 危险组合必须拒启
    // ==================================================================

    @Nested
    @DisplayName("组 B —— 默认口令 × 非回环绑定 → 拒启")
    class DangerousCombinationIsRefused {

        /**
         * 缺陷本体：{@code 0.0.0.0}（全部网卡）+ changeme。面板对整个网络开放，
         * 唯一凭据是公开的默认值。
         */
        @Test
        @DisplayName("bind-address=0.0.0.0 + 默认口令 → 不启动，且不抛异常")
        void defaultPasswordOnNonLoopbackIsRefused() {
            StatisticsHttpServer server = newServer("0.0.0.0", "changeme");
            try {
                // 「不抛」是独立且重要的契约：拒启绝不能冒泡成异常。
                // start() 的调用方是 onEnable / restartStatisticsServerIfNeeded，
                // 异常会阻断插件主流程（onEnable 抛异常 = 插件被 Bukkit 禁用）。
                server.start();

                assertFalse(server.isRunning(),
                        "0.0.0.0 + 默认口令必须拒启喵 —— 这一组合把管理面板"
                                + "（含全部玩家学习数据）以 admin/changeme 暴露到网络上。"
                                + "\n日志：" + handler.describe());
            } finally {
                server.stop();
            }
        }

        @Test
        @DisplayName("拒启必须留 SEVERE 并说明两条修法（运维要知道怎么改）")
        void refusalLogsSevereWithBothRemedies() {
            StatisticsHttpServer server = newServer("0.0.0.0", "changeme");
            try {
                server.start();

                assertTrue(handler.hasAtLeast(Level.SEVERE),
                        "拒启必须留 SEVERE喵 —— 否则服主只看到「面板没起来」，"
                                + "与端口被占不可区分，也不知道该改密码还是改绑定。"
                                + "\n日志：" + handler.describe());
                String all = handler.describe();
                assertTrue(all.contains("statistics-server.auth.password"),
                        "SEVERE 消息里必须点出要改的配置键（密码）喵：" + all);
                assertTrue(all.contains("statistics-server.bind-address"),
                        "SEVERE 消息里必须点出另一条修法（绑定地址）喵：" + all);
            } finally {
                server.stop();
            }
        }

        /**
         * 空口令与默认口令等价（Basic Auth 里 {@code password: ""} 一样是可猜的凭据）。
         */
        @Test
        @DisplayName("bind-address=0.0.0.0 + 空口令 → 同样拒启")
        void emptyPasswordIsTreatedAsDefault() {
            StatisticsHttpServer server = newServer("0.0.0.0", "");
            try {
                server.start();
                assertFalse(server.isRunning(),
                        "空口令与 changeme 等价（都是「等于没设」），必须一起拒启喵。"
                                + "\n日志：" + handler.describe());
            } finally {
                server.stop();
            }
        }

        /**
         * 纯空白口令：{@code password: "   "} 在 Basic Auth 里同样是可猜的。
         */
        @Test
        @DisplayName("bind-address=0.0.0.0 + 纯空白口令 → 同样拒启")
        void blankPasswordIsTreatedAsDefault() {
            StatisticsHttpServer server = newServer("0.0.0.0", "   ");
            try {
                server.start();
                assertFalse(server.isRunning(),
                        "纯空白口令必须与默认口令同样处理喵（trim 后为空）。"
                                + "\n日志：" + handler.describe());
            } finally {
                server.stop();
            }
        }
    }

    // ==================================================================
    // 组 C —— 改了口令就必须能启动（拒启不得过度）
    // ==================================================================

    @Nested
    @DisplayName("组 C —— 非回环绑定 + 真口令 → 必须启动")
    class ProperPasswordAllowsNonLoopback {

        /**
         * 局域网访问是本功能的正当用法（部署文档明确写了「需要局域网访问时显式改为
         * 服务器内网地址」）。只要口令是真口令，就必须照常启动。
         *
         * <p>变异指向：把拒启条件写成「非回环一律拒」→ 本用例红。
         */
        @Test
        @DisplayName("bind-address=0.0.0.0 + 真口令 → 必须启动")
        void realPasswordOnAnyBindStarts() {
            StatisticsHttpServer server = newServer("0.0.0.0", REAL_PASSWORD);
            try {
                server.start();
                assertTrue(server.isRunning(),
                        "非回环绑定 + 真口令是本功能的正当用法，必须启动喵 —— "
                                + "拒启条件只能是「默认口令 × 非回环」这个组合，"
                                + "不能退化成「非回环一律拒」。\n日志：" + handler.describe());
            } finally {
                server.stop();
            }
        }
    }

    // ==================================================================
    // 组 D —— isLoopbackBind 的判定面（纯函数，反射直调）
    // ==================================================================

    @Nested
    @DisplayName("组 D —— isLoopbackBind 判定面")
    class LoopbackDetection {

        /**
         * 判定函数是「拒启条件」的一半，必须单独钉住它的边界。
         * 走反射是因为它是 private（无外部入口）—— 但它可靠的观测点就是返回值。
         */
        @Test
        @DisplayName("回环写法全部识别为 true；非回环/无法解析为 false")
        void loopbackDetectionMatrix() throws Exception {
            // 每一组：[bindAddress, 期望]
            String[][] matrix = {
                    {"127.0.0.1", "true"},
                    {"127.0.0.2", "true"},      // 127.0.0.0/8 全集，不只是 .0.1
                    {"127.255.255.254", "true"},
                    {"localhost", "true"},
                    {"LOCALHOST", "true"},      // 大小写不敏感
                    {"::1", "true"},
                    {"[::1]", "true"},
                    {" 127.0.0.1 ", "true"},    // 首尾空白
                    {"0.0.0.0", "false"},       // 全部网卡 —— 最该拦的一档
                    {"192.168.1.10", "false"},
                    {"10.0.0.5", "false"},
                    {"::", "false"},            // IPv6 通配
                    {"", "false"},              // 空 = 全部网卡
                    {"example.com", "false"},   // 主机名：判断不出来 → 保守按非回环
                    {"127.0.0", "false"},       // 不是 4 段
                    {"127.0.0.1.5", "false"},   // 5 段
                    {"not-an-ip", "false"},
            };
            for (String[] row : matrix) {
                StatisticsHttpServer server = newServer(row[0], REAL_PASSWORD);
                boolean expected = Boolean.parseBoolean(row[1]);
                assertEquals(expected, invokeIsLoopbackBind(server),
                        "isLoopbackBind('" + row[0] + "') 必须是 " + expected + "喵。"
                                + "这个判定是「拒启条件」的一半 —— 把 0.0.0.0 误判成回环"
                                + "会让危险组合被放行，把 127.0.0.1 误判成非回环会让"
                                + "默认配置开箱即用失效。");
            }
        }

        private boolean invokeIsLoopbackBind(StatisticsHttpServer server) throws Exception {
            Method m = StatisticsHttpServer.class.getDeclaredMethod("isLoopbackBind");
            m.setAccessible(true);
            return (boolean) m.invoke(server);
        }
    }

    // ------------------------------------------------------------------

    /** 收集日志的 Handler（与 SeasonSettlementRollbackTest 同款）。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized boolean hasAtLeast(Level level) {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= level.intValue()) {
                    return true;
                }
            }
            return false;
        }

        synchronized String describe() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord record : records) {
                sb.append(record.getLevel().getName()).append(':')
                        .append(record.getMessage()).append(" | ");
            }
            return sb.append(']').toString();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
