package com.letslearnenglish.minecraftplugin.core.manager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Statistics HTTP Dashboard Server
 *
 * Serves an admin web dashboard showing learning statistics with charts.
 * Uses Java's built-in com.sun.net.httpserver.HttpServer (no external HTTP dependencies).
 *
 * Security: dual-layer protection — IP whitelist (CIDR support) + HTTP Basic Auth.
 * Endpoints: GET / (HTML dashboard), GET /api/stats (JSON stats).
 */
public class StatisticsHttpServer {

    /**
     * R15（wave3 后继审计 #4）：错误响应前最多读掉这么多请求体。
     *
     * <p>这些端点全是 GET，正常请求体为空。设上限是为了让
     * {@code Content-Length: 半个 GB} 的攻击请求不会把工作线程耗在读体上 ——
     * 超限即放弃读完并关闭连接，JDK 的 drain 就不会阻塞在 SocketChannel.read()。
     */
    private static final int MAX_DISCARD_BYTES = 64 * 1024;

    private final LetsLearnEnglish plugin;
    private final DatabaseManager databaseManager;
    private HttpServer server;
    private volatile boolean running;
    private ExecutorService executor;
    private final Gson gson;

    // Config values — read once at construction
    private final int port;
    private final String bindAddress;
    private final List<String> ipWhitelist;
    private final String authUsername;
    private final String authPassword;

    public StatisticsHttpServer(LetsLearnEnglish plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.gson = new Gson();

        var config = plugin.getConfigManager().getMainConfig();
        this.port = config.getInt("statistics-server.port", 8080);
        this.bindAddress = config.getString("statistics-server.bind-address", "127.0.0.1");
        this.ipWhitelist = config.getStringList("statistics-server.ip-whitelist");
        this.authUsername = config.getString("statistics-server.auth.username", "admin");
        this.authPassword = config.getString("statistics-server.auth.password", "changeme");
    }

    /**
     * Start the HTTP server and bind to the configured port.
     */
    public void start() {
        // R13-L5：默认/弱口令启动告警（不改行为——只是让"忘了改密码"可见）
        if (hasDefaultPassword()) {
            plugin.getLogger().warning("Statistics HTTP dashboard is using the default/empty auth password!"
                    + " Please set 'statistics-server.auth.password' in config.yml.");
        }
        // R12-G1（round12 A4-LOW-2）：**只在真正危险的组合上拒启**。
        //
        // 判据 = 「默认/空口令」× 「绑定地址不是回环」。
        // 只凭前者拒启是错的：默认配置 bind-address=127.0.0.1，面板本来就出不了本机，
        // 服主把 enabled 改成 true 就该能用 —— 拒启会把「开箱即用」变成「开了没反应」。
        // 而叠加「非回环绑定」之后，Basic Auth 就是最后一道门：默认口令等于把管理面板
        // （含全部玩家学习数据）以 admin/changeme 暴露到网络上。这种组合没有正当用途，
        // 拒启比放行后打一条没人看的 WARNING 更负责。
        //
        // 拒启**不阻断插件主流程**：只是本组件 running 保持 false（与端口被占同一形态），
        // DiagnosticCommand 会把它报成 stats.not-running，服主看得见、能修。
        if (hasDefaultPassword() && !isLoopbackBind()) {
            plugin.getLogger().severe("Statistics HTTP dashboard REFUSED to start:"
                    + " bind-address='" + bindAddress + "' is reachable from other machines while"
                    + " the auth password is still the default/empty value. That combination exposes"
                    + " this admin dashboard (and every player's learning data) to the network with"
                    + " known credentials. Either set 'statistics-server.auth.password' to a real"
                    + " password, or set 'statistics-server.bind-address' back to '127.0.0.1'."
                    + " The rest of the plugin is unaffected.");
            running = false;
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "lle-statistics-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);

            server.createContext("/", new DashboardHandler());
            server.createContext("/api/stats", new StatsHandler());

            // 先于 start() 置位，否则 accept 已开而标志为 false 的窗口全判 503。
            running = true;
            server.start();
            plugin.getLogger().info("Statistics HTTP dashboard started on " + bindAddress + ":" + port
                    + " (whitelist: " + ipWhitelist.size() + " entries)");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to start statistics HTTP server on port " + port, e);
            running = false;
        } catch (IllegalArgumentException e) {
            // R15（wave3 后继审计 #4，HIGH→已修）：`statistics-server.port` 配成越界值
            // （70000 / -1）时 `new InetSocketAddress(host, port)` 抛的是
            // **IllegalArgumentException**，不是 IOException。原实现只 catch IOException，
            // 于是这个异常一路冒到 `enableAll` 的 catch(Throwable) → disableAll 回滚 →
            // **整个插件被禁用**，而本该只是"统计面板没起来"。
            //
            // HotReloadManager 与 DiagnoseCommand 的注释都建立在
            // 「启动失败 ⇒ running=false 且插件其余部分照常」之上 —— 那对 BindException
            // 成立，对端口手滑不成立。现在补齐。
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to start statistics HTTP server: invalid port " + port
                            + " (must be 0-65535). The dashboard is DISABLED; the rest of the"
                            + " plugin is unaffected. Fix statistics-server.port and run /lea reload.",
                    e);
            running = false;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            server = null;
        }
    }

    /**
     * R12-G1：口令是否仍是「等于没设」的状态（默认值 / 空 / 纯空白）。
     *
     * <p>空白也算：{@code password: "   "} 在 Basic Auth 里是一个可猜的凭据，
     * 与 {@code changeme} 等价。
     */
    private boolean hasDefaultPassword() {
        return authPassword == null
                || authPassword.trim().isEmpty()
                || "changeme".equalsIgnoreCase(authPassword.trim());
    }

    /**
     * R12-G1：绑定地址是否只在本机可达（回环）。
     *
     * <p>认三种写法：
     * <ul>
     *   <li>{@code 127.0.0.0/8} 全集（不只是 127.0.0.1 —— 127.0.0.2 等同样不可外部访问）；</li>
     *   <li>{@code localhost}（{@link InetSocketAddress} 会解析成回环）；</li>
     *   <li>{@code ::1} / {@code [::1]}（IPv6 回环）。</li>
     * </ul>
     *
     * <p>空/null 视为<b>非</b>回环：{@code new InetSocketAddress(null, port)} 的语义是
     * 「绑定全部网卡」，那正是最该拦的那一档。解析不出主机名时同样按非回环处理
     * （保守方向：宁可拒启，也不要在判断不出来时放出管理面板）。
     */
    private boolean isLoopbackBind() {
        if (bindAddress == null) {
            return false;
        }
        String host = bindAddress.trim();
        if (host.isEmpty()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host)) {
            return true;
        }
        String octets = host;
        if (octets.startsWith("[")) {
            return false; // 其它 IPv6 字面量：不尝试判定，按非回环处理
        }
        String[] parts = octets.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            if (Integer.parseInt(parts[0]) != 127) {
                return false;
            }
            for (int i = 1; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Stop the HTTP server gracefully.
     */
    public void stop() {
        if (server != null) {
            // 08-c-C1：先置关闭位再停 server 并等 handler 退出，避免其在 shutdown() 后重开库。
            running = false;
            server.stop(0);
            if (executor != null) {
                executor.shutdownNow();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        plugin.getLogger().warning("Statistics HTTP handler survived stop(0).");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            plugin.getLogger().info("Statistics HTTP dashboard stopped.");
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ============================================================
    //  Security — Layer 1: IP Whitelist (CIDR support)
    // ============================================================

    /**
     * Check if the client IP is in the whitelist.
     * Supports single IPs and CIDR notation (e.g. "192.168.1.0/24", "fd00::/8").
     *
     * <p>空白名单 = <b>全拒</b>（fail-closed，已回归验证，不得改成放行）。
     */
    private boolean isIpWhitelisted(String clientIp) {
        if (ipWhitelist.isEmpty()) {
            return false;
        }
        for (String entry : ipWhitelist) {
            if (matchesCidr(clientIp, entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * R-16（低危积压 FIX 1）：<b>单条白名单项 vs 客户端地址的匹配</b>。
     *
     * <p><b>原实现的缺陷</b>：两侧都走 {@code ipv4ToBytes}（{@code split("\\.")} + 必须 4 段）。
     * 而 {@code checkSecurity} 喂进来的是
     * {@code exchange.getRemoteAddress().getAddress().getHostAddress()} —— IPv6 客户端拿到的是
     * {@code 0:0:0:0:0:0:0:1} 这种<b>没有点</b>的串，于是 {@code octets.length != 4} → 返回 null
     * → 本方法返回 false → <b>每一个 IPv6 客户端都吃 403</b>；同理白名单里任何非点分四段写法
     * （{@code ::1}、域名、写错一位）也只是"静默 deny-all"，服主看不出是哪一条配错了。
     * 结果是这个面板在 IPv6 网络上<b>完全不可用</b>，且无任何诊断。
     *
     * <p><b>修法</b>：两侧统一用 {@link java.net.InetAddress} 解析成字节数组再按位比。
     * 不用手写字符串解析的另一个理由：{@code InetAddress.getByName} 会把
     * <b>IPv4-mapped IPv6</b>（{@code ::ffff:127.0.0.1}）归一化成 {@code Inet4Address}（实测），
     * 于是"客户端从 mapped 通道来 + 白名单写 {@code 127.0.0.0/8}"能正常命中 ——
     * 双栈 socket 上这种情况很常见，手写解析只会把它当成 16 字节的 IPv6 而永远匹配不上。
     *
     * <p><b>IPv4 的位运算语义原样保留</b>（此前已对独立参考实现做过 fuzz，0 处不一致）：
     * 先比 {@code prefix/8} 个整字节，再用 {@code 0xFF << (8 - 剩余位)} 比最后一个不完整字节。
     * 唯一变化是"前缀长度上限"从写死的 32 改成 {@code 地址字节数 * 8}：
     * IPv4 仍是 32（{@code /33} 照旧拒绝），IPv6 则是 128（{@code /129} 拒绝）。
     * 家族不同（4 字节 vs 16 字节）直接判不匹配 —— 跨族无从比较位。
     *
     * <p>解析失败一律返回 false（<b>deny</b>，不是放行）：一条写坏的白名单项只能"少放行一个人"，
     * 绝不能变成"多放行一个人"。
     */
    private boolean matchesCidr(String ip, String cidr) {
        try {
            String entry = cidr == null ? "" : cidr.trim();
            if (entry.isEmpty()) {
                return false;
            }

            int slash = entry.indexOf('/');
            String networkPart = slash < 0 ? entry : entry.substring(0, slash);

            InetAddress client = parseIpLiteral(ip);
            InetAddress network = parseIpLiteral(networkPart);
            if (client == null || network == null) {
                return false;
            }

            byte[] clientBytes = client.getAddress();
            byte[] networkBytes = network.getAddress();

            // 地址族不同 → 不匹配。IPv4-mapped 已在 parseIpLiteral 里被归一化成 4 字节，
            // 所以走到这里还长度不等的是真·跨族（如 IPv6 客户端 vs 纯 IPv4 白名单项）。
            if (clientBytes.length != networkBytes.length) {
                return false;
            }

            if (slash < 0) {
                // 单地址 —— 精确匹配（按字节比，而不是原文比：::1 与 0:0:0:0:0:0:0:1 是同一地址）
                return java.util.Arrays.equals(clientBytes, networkBytes);
            }

            int prefixLength;
            try {
                prefixLength = Integer.parseInt(entry.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }

            final int addressBits = clientBytes.length * 8;
            if (prefixLength < 0 || prefixLength > addressBits) {
                // /33（IPv4）、/129（IPv6）等越界前缀：按配置错误拒绝，不截到合法值。
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // Compare full bytes
            for (int i = 0; i < fullBytes; i++) {
                if (clientBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            // Compare remaining bits
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                if ((clientBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            // 兜底：任何意外都按"不匹配"处理（fail-closed）。
            return false;
        }
    }

    /**
     * R-16：把<b>IP 字面量</b>解析成 {@link InetAddress}，解析不出返回 null。
     *
     * <p><b>为什么不直接用 {@code InetAddress.getByName}：它会做 DNS 查询。</b>
     * 白名单项是每次请求逐条解析的，若服主把某条写成了主机名（{@code my-host}）或写坏成
     * {@code dead.beef}，每个 HTTP 请求都会在 handler 线程上卡一次解析（实测一个不存在的
     * 域名 75ms；解析器卡住时更长），2 线程的 worker 池很容易被拖死。
     * 所以先用 {@link #looksLikeIpLiteral} 卡一道：不是"数字点分"或"十六进制冒号"的形状
     * 就直接拒绝，{@code getByName} 便只会走纯字面量分支，<b>不会</b>触碰解析器。
     *
     * <p>（面板白名单按设计只接受 IP，不接受主机名 —— 主机名会引入 DNS 依赖，
     * 而白名单的意义恰恰是"不依赖任何可被篡改的外部解析"。）
     */
    private static InetAddress parseIpLiteral(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        // 允许 IPv6 的方括号写法（"[::1]"）：与 isLoopbackBind / config 的写法保持一致
        if (text.length() > 1 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']') {
            text = text.substring(1, text.length() - 1).trim();
        }
        // 网卡作用域后缀（fe80::1%eth0）先剥掉，原因见 stripIpv6Scope
        text = stripIpv6Scope(text);
        if (!looksLikeIpLiteral(text)) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * R-16：形状判定 —— 只为"避免 DNS 查询"而存在，不负责判定地址是否合法
     * （它返回 true 后 {@code InetAddress.getByName} 仍可能抛）。
     *
     * <p><b>为什么值得多写这十几行</b>：实测 {@code InetAddress.getByName("999.999.999.999")}
     * 会走<b>解析器</b>（63ms），而 {@code "dead.beef"} 同样是 60ms 级。白名单项是每个请求
     * 逐条解析的，2 线程 handler 池上一次 60ms 的解析足以在并发下把队列堆起来。
     * 所以凡是"一眼能看出不是 IP 字面量"的形状，在进 {@code getByName} 之前就拒掉。
     *
     * <p>三条规则：
     * <ul>
     *   <li>含 {@code :} → 按 IPv6 形状：允许十六进制位、{@code :}、{@code %}（scope id），
     *       以及 {@code .}（IPv4-mapped 的 {@code ::ffff:127.0.0.1} 必须放行）；</li>
     *   <li>只有 {@code .} → 按 IPv4 形状：只允许数字与点，<b>必须正好四段</b>，且每段
     *       1-3 位、值 ≤ 255。（段数严格对齐旧实现的 {@code octets.length != 4}；
     *       否则 {@code 1.2.3} 会被 JDK 的兼容写法解析成 {@code 1.2.0.3}，等于悄悄放行一个
     *       服主没写过的网段。范围检查则是为了不把 {@code 999.999.999.999} 送进解析器。）</li>
     *   <li>两者都没有 → 拒绝（单标签主机名如 {@code localhost}）。</li>
     * </ul>
     * 例：{@code dead.beef} 含字母 → 拒绝；{@code 10.1.2.3} 与 {@code 010.1.1.1}
     * （JDK 按十进制解析成 {@code 10.1.1.1}）→ 放行。
     */
    private static boolean looksLikeIpLiteral(String text) {
        if (text.isEmpty()) {
            return false;
        }
        boolean hasColon = text.indexOf(':') >= 0;
        boolean hasDot = text.indexOf('.') >= 0;
        if (hasColon) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex && c != ':' && c != '.' && c != '%') {
                    return false;
                }
            }
            return true;
        }
        if (!hasDot) {
            return false;
        }
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int value = 0;
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * R-16：去掉 IPv6 的 scope id（{@code fe80::1%eth0} 的 {@code %eth0} 部分）。
     *
     * <p>为什么必须去：{@code Inet6Address.getHostAddress()} <b>在有网卡作用域时会带回
     * 一个 {@code %eth0} 后缀</b>（{@code getRemoteAddress().getAddress()} 走的就是这条，
     * JDK 会把 scope 套回去）。而 {@code InetAddress.getByName("fe80::1%eth0")} 只会把
     * {@code %} 之后当<b>数字网卡索引</b>解析，写名字就抛 {@code UnknownHostException}（实测）
     * —— 于是链路本地地址的客户端又会退化成"永远 403"。
     *
     * <p>当前生产路径上出现的概率很低（HTTP 客户端一般报的是全局地址），但这是同一族
     * "静默 403"缺陷的最后一个入口，去掉后缀的代价是两行。
     *
     * <p>只对含 {@code :} 的写法去（IPv4 没有 scope 概念），且只在 {@code %} 不是首字符时去。
     */
    private static String stripIpv6Scope(String text) {
        if (text.indexOf(':') < 0) {
            return text;
        }
        int percent = text.indexOf('%');
        return percent < 0 ? text : text.substring(0, percent);
    }

    // ============================================================
    //  Security — Layer 2: HTTP Basic Auth
    // ============================================================

    /**
     * Check Basic Auth credentials from the Authorization header.
     * Returns true if valid, false if missing or invalid.
     */
    private boolean isAuthenticated(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }

        try {
            String encoded = authHeader.substring(6);
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                return false;
            }
            // R13-L5：常数时间比较（防时序侧信道）——两字段都走 MessageDigest.isEqual；
            // 用户名比较保持"长度泄漏"与密码同口径（Basic Auth 用户名非秘密，但
            // 统一写法避免后人只改一半）。
            return java.security.MessageDigest.isEqual(
                    authUsername.getBytes(StandardCharsets.UTF_8),
                    parts[0].getBytes(StandardCharsets.UTF_8))
                    && java.security.MessageDigest.isEqual(
                    authPassword.getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ============================================================
    //  Security check — called before every request handling
    // ============================================================

    /**
     * Run both security checks. Returns null if passed, or the error status code.
     * Side effect: writes error response if security check fails.
     */
    private int checkSecurity(HttpExchange exchange) throws IOException {
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        // Layer 1: IP whitelist
        if (!isIpWhitelisted(clientIp)) {
            sendError(exchange, 403, "Forbidden: IP not in whitelist");
            return 403;
        }

        // Layer 2: Basic Auth
        if (!isAuthenticated(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate",
                    "Basic realm=\"LLE Stats\"");
            sendError(exchange, 401, "Unauthorized");
            return 401;
        }

        return 0; // Passed
    }

    // ============================================================
    //  HTTP helpers
    // ============================================================

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        // R15（wave3 后继审计 #4，HIGH —— worker 池永久耗尽）：必须在回响应**之前**
        // 把请求体读干净（或明确关掉），否则 JDK 的 `FixedLengthOutputStream.close()`
        // 会走 `LeftOverInputStream.drain()` 做一次**阻塞** SocketChannel.read()：
        // 攻击者发 `Content-Length: 500000` 但只给 3 字节然后停住，两个 handler 线程
        // 就永久 park 在 drain 里（2 线程池 = 面板彻底失联，实测 52 秒仍无恢复）。
        //
        // 这里主动读掉（有上限），读不完就先关连接 —— 两者都能让 drain 无事可做。
        discardRequestBody(exchange);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * R15：读掉请求体（有上限），让 JDK 的 drain 不会阻塞。
     *
     * <p>上限取 64 KiB：正常请求体为空（这些端点是 GET），有体也只是少量。超限时
     * 不再读、直接让连接不可复用 —— `close()` 时 JDK 会尝试 drain，但已被上面
     * 的 `setAttribute`/提前 close 打断。宁可牺牲一条连接，也不能占住工作线程。
     */
    private void discardRequestBody(HttpExchange exchange) {
        try {
            try (java.io.InputStream in = exchange.getRequestBody()) {
                byte[] buf = new byte[4096];
                int total = 0;
                int n;
                while (total < MAX_DISCARD_BYTES && (n = in.read(buf)) > 0) {
                    total += n;
                }
                if (total >= MAX_DISCARD_BYTES) {
                    // 体太大：不读完。关闭 exchange 让连接作废，避免 drain 继续等。
                    exchange.close();
                }
            }
        } catch (IOException ignored) {
            // 读体失败不影响错误响应本身；连接会由 JDK 回收。
        }
    }

    private void sendHtml(HttpExchange exchange, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ============================================================
    //  Handler: GET / — Dashboard HTML
    // ============================================================

    private class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            if (checkSecurity(exchange) != 0) {
                return;
            }

            sendHtml(exchange, 200, DASHBOARD_HTML);
        }
    }

    // ============================================================
    //  Handler: GET /api/stats — JSON statistics
    // ============================================================

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 08-c-C1：stop() 后到达/在途的请求一律拒绝，本路径不得再碰 databaseManager。
            if (!running) {
                sendError(exchange, 503, "Service Unavailable");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            if (checkSecurity(exchange) != 0) {
                return;
            }

            try {
                JsonObject stats = buildStatsJson();
                sendJson(exchange, 200, gson.toJson(stats));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to build stats JSON for HTTP dashboard", e);
                sendError(exchange, 500, "Internal Server Error");
            }
        }
    }

    // ============================================================
    //  JSON building
    // ============================================================

    private JsonObject buildStatsJson() {
        JsonObject root = new JsonObject();

        root.addProperty("totalWords", databaseManager.getTotalWordCount());
        root.addProperty("todayActivePlayers", databaseManager.getTodayActivePlayers());
        // P3-02：口径 = 累计学习日次（player_sessions 每人每日一行；原 learning_log 零写入已弃用）
        root.addProperty("totalLearningSessions", databaseManager.getTotalSessions());
        root.addProperty("totalCoinsEarned", databaseManager.getTotalCoinsEarned());
        root.addProperty("totalCoinsSpent", databaseManager.getTotalCoinsSpent());

        // P3-02：7 天曲线亦来自 player_sessions；sessions = 当日学习日次（每人每日一行）
        List<DatabaseManager.DailyStats> last7Days = databaseManager.getLast7DaysStats();
        JsonArray daysArray = new JsonArray();
        for (DatabaseManager.DailyStats day : last7Days) {
            JsonObject dayObj = new JsonObject();
            dayObj.addProperty("date", day.date());
            dayObj.addProperty("sessions", day.sessions());
            dayObj.addProperty("players", day.players());
            dayObj.addProperty("wordsLearned", day.wordsLearned());
            daysArray.add(dayObj);
        }
        root.add("last7Days", daysArray);

        return root;
    }

    // ============================================================
    //  Dashboard HTML (inline, no external CSS files)
    // ============================================================

    private static final String DASHBOARD_HTML = "<!DOCTYPE html>\n"
            + "<html lang=\"en\">\n"
            + "<head>\n"
            + "<meta charset=\"UTF-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "<title>Let's Learn English! - Statistics Dashboard</title>\n"
            // F-08-S4（round1 遗留 MEDIUM，本轮补修）：子资源完整性（SRI）。
            //
            // 原实现只固定了版本号（echarts@5.5.0，非 latest），但没有 integrity ——
            // 该脚本以**面板同源**身份执行，CDN 或依赖被投毒时，脚本可读取 DOM、向
            // /api/stats 发同源请求（浏览器自动附带 Basic 凭据）、把结果外发。
            // 管理面板的信任边界因此被外包给了外部 CDN。
            //
            // 现在加 integrity + crossorigin：浏览器会用 SHA-384 校验字节，任何改动都会
            // 让脚本拒绝执行（控制台报 SRI 错误），面板图表降级为空白但凭据不外泄。
            //
            // 下面的哈希是**实测**值，不是抄的：
            //   curl -sS -o echarts.min.js https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js
            //   openssl dgst -sha384 -binary echarts.min.js | openssl base64 -A
            // 升级 echarts 版本时必须用同样方式重算并同步更新，否则图表会静默不再渲染 ——
            // 忘了改的表现是「页面打开但图表空白」，不是报错，所以在这里写清楚。
            + "<script src=\"https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js\""
            + " integrity=\"sha384-o5uz97et3bErHvpKfD4Jz4n0JfhJDWABFuF4NP+iEEDxE1VwMWJ19QGR0lqFZnr6\""
            + " crossorigin=\"anonymous\"></script>\n"
            + "<style>\n"
            + "* { margin: 0; padding: 0; box-sizing: border-box; }\n"
            + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; "
            + "background: #0f0f1a; color: #e0e0e0; min-height: 100vh; }\n"
            + ".header { background: linear-gradient(135deg, #1a1a3e 0%, #2a1a4e 100%); "
            + "padding: 24px 32px; border-bottom: 2px solid #3a3a6e; "
            + "display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }\n"
            + ".header h1 { font-size: 1.6em; color: #b8b8ff; }\n"
            + ".auto-refresh { font-size: 0.85em; color: #888; }\n"
            + ".summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); "
            + "gap: 16px; padding: 24px 32px; }\n"
            + ".card { background: #1a1a2e; border: 1px solid #2a2a4e; border-radius: 10px; "
            + "padding: 20px; text-align: center; transition: border-color 0.3s; }\n"
            + ".card:hover { border-color: #5a5a9e; }\n"
            + ".card .label { font-size: 0.8em; color: #888; text-transform: uppercase; "
            + "letter-spacing: 1px; margin-bottom: 8px; }\n"
            + ".card .value { font-size: 2em; font-weight: bold; color: #b8b8ff; }\n"
            + ".card .value.coins { color: #ffd700; }\n"
            + ".charts { display: grid; grid-template-columns: repeat(auto-fit, minmax(400px, 1fr)); "
            + "gap: 16px; padding: 0 32px 24px; }\n"
            + ".chart-box { background: #1a1a2e; border: 1px solid #2a2a4e; border-radius: 10px; "
            + "padding: 16px; }\n"
            + ".chart-box h3 { font-size: 1em; color: #888; margin-bottom: 8px; text-align: center; }\n"
            + ".chart { width: 100%; height: 320px; }\n"
            + ".footer { text-align: center; padding: 16px; color: #555; font-size: 0.8em; "
            + "border-top: 1px solid #1a1a2e; }\n"
            + ".error { color: #ff6b6b; text-align: center; padding: 8px; display: none; }\n"
            + "@media (max-width: 500px) {\n"
            + "  .header { padding: 16px; }\n"
            + "  .header h1 { font-size: 1.2em; }\n"
            + "  .summary { padding: 12px 16px; gap: 8px; }\n"
            + "  .card .value { font-size: 1.4em; }\n"
            + "  .charts { padding: 0 8px 12px; grid-template-columns: 1fr; }\n"
            + "  .chart { height: 250px; }\n"
            + "}\n"
            + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "<div class=\"header\">\n"
            + "<h1>Let's Learn English! - Statistics Dashboard</h1>\n"
            + "<span class=\"auto-refresh\" id=\"refreshLabel\">Auto-refresh: 60s</span>\n"
            + "</div>\n"
            + "<div class=\"error\" id=\"errorBox\"></div>\n"
            + "<div class=\"summary\">\n"
            + "<div class=\"card\"><div class=\"label\">Total Words</div><div class=\"value\" id=\"totalWords\">--</div></div>\n"
            + "<div class=\"card\"><div class=\"label\">Today Active</div><div class=\"value\" id=\"todayActive\">--</div></div>\n"
            + "<div class=\"card\"><div class=\"label\">Total Sessions</div><div class=\"value\" id=\"totalSessions\">--</div></div>\n"
            + "<div class=\"card\"><div class=\"label\">Coins Earned</div><div class=\"value coins\" id=\"coinsEarned\">--</div></div>\n"
            + "<div class=\"card\"><div class=\"label\">Coins Spent</div><div class=\"value coins\" id=\"coinsSpent\">--</div></div>\n"
            + "</div>\n"
            + "<div class=\"charts\">\n"
            + "<div class=\"chart-box\"><h3>7-Day Learning Sessions</h3><div class=\"chart\" id=\"sessionsChart\"></div></div>\n"
            + "<div class=\"chart-box\"><h3>Daily Active Players</h3><div class=\"chart\" id=\"playersChart\"></div></div>\n"
            + "</div>\n"
            + "<div class=\"footer\">Let's Learn English! Plugin &mdash; Statistics Dashboard</div>\n"
            + "<script>\n"
            + "var sessionsChart = echarts.init(document.getElementById('sessionsChart'));\n"
            + "var playersChart = echarts.init(document.getElementById('playersChart'));\n"
            + "var countdown = 60;\n"
            + "function updateCountdown() {\n"
            + "  document.getElementById('refreshLabel').textContent = 'Auto-refresh: ' + countdown + 's';\n"
            + "  countdown--;\n"
            + "  if (countdown < 0) countdown = 60;\n"
            + "}\n"
            + "setInterval(updateCountdown, 1000);\n"
            + "function formatNumber(n) { return n != null ? n.toLocaleString() : '--'; }\n"
            + "function formatCoins(n) { return n != null ? n.toFixed(2) : '--'; }\n"
            + "function fetchStats() {\n"
            + "  countdown = 60;\n"
            + "  fetch('/api/stats')\n"
            + "    .then(function(r) {\n"
            + "      if (!r.ok) throw new Error('HTTP ' + r.status);\n"
            + "      return r.json();\n"
            + "    })\n"
            + "    .then(function(data) {\n"
            + "      document.getElementById('errorBox').style.display = 'none';\n"
            + "      document.getElementById('totalWords').textContent = formatNumber(data.totalWords);\n"
            + "      document.getElementById('todayActive').textContent = formatNumber(data.todayActivePlayers);\n"
            + "      document.getElementById('totalSessions').textContent = formatNumber(data.totalLearningSessions);\n"
            + "      document.getElementById('coinsEarned').textContent = formatCoins(data.totalCoinsEarned);\n"
            + "      document.getElementById('coinsSpent').textContent = formatCoins(data.totalCoinsSpent);\n"
            + "      var days = data.last7Days || [];\n"
            + "      var dates = days.map(function(d) { return d.date; });\n"
            + "      var sessionsData = days.map(function(d) { return d.sessions; });\n"
            + "      var playersData = days.map(function(d) { return d.players; });\n"
            + "      sessionsChart.setOption({\n"
            + "        tooltip: { trigger: 'axis' },\n"
            + "        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },\n"
            + "        xAxis: { type: 'category', data: dates, axisLabel: { color: '#888' } },\n"
            + "        yAxis: { type: 'value', axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#2a2a4e' } } },\n"
            + "        series: [{ data: sessionsData, type: 'line', smooth: true, "
            + "lineStyle: { color: '#7b7bff', width: 2 }, itemStyle: { color: '#7b7bff' }, "
            + "areaStyle: { color: 'rgba(123,123,255,0.15)' } }]\n"
            + "      });\n"
            + "      playersChart.setOption({\n"
            + "        tooltip: { trigger: 'axis' },\n"
            + "        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },\n"
            + "        xAxis: { type: 'category', data: dates, axisLabel: { color: '#888' } },\n"
            + "        yAxis: { type: 'value', axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#2a2a4e' } } },\n"
            + "        series: [{ data: playersData, type: 'bar', "
            + "itemStyle: { color: '#5a5adc', borderRadius: [4, 4, 0, 0] } }]\n"
            + "      });\n"
            + "    })\n"
            + "    .catch(function(err) {\n"
            + "      var box = document.getElementById('errorBox');\n"
            + "      box.style.display = 'block';\n"
            + "      box.textContent = 'Failed to fetch stats: ' + err.message + '. Retrying...';\n"
            + "    });\n"
            + "}\n"
            + "fetchStats();\n"
            + "setInterval(fetchStats, 60000);\n"
            + "window.addEventListener('resize', function() { sessionsChart.resize(); playersChart.resize(); });\n"
            + "</script>\n"
            + "</body>\n"
            + "</html>";
}
