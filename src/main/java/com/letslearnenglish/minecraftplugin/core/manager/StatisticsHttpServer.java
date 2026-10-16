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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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

            server.start();
            running = true;
            plugin.getLogger().info("Statistics HTTP dashboard started on " + bindAddress + ":" + port
                    + " (whitelist: " + ipWhitelist.size() + " entries)");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to start statistics HTTP server on port " + port, e);
            running = false;
        }
    }

    /**
     * Stop the HTTP server gracefully.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            running = false;
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
     * Supports single IPs and CIDR notation (e.g. "192.168.1.0/24").
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

    private boolean matchesCidr(String ip, String cidr) {
        try {
            if (!cidr.contains("/")) {
                // Single IP — exact match
                return ip.equals(cidr.trim());
            }

            String[] parts = cidr.split("/", 2);
            String networkAddr = parts[0].trim();
            int prefixLength = Integer.parseInt(parts[1].trim());

            if (prefixLength < 0 || prefixLength > 32) {
                return false;
            }

            byte[] ipBytes = ipv4ToBytes(ip);
            byte[] networkBytes = ipv4ToBytes(networkAddr);

            if (ipBytes == null || networkBytes == null) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // Compare full bytes
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            // Compare remaining bits
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                if ((ipBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] ipv4ToBytes(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int val = Integer.parseInt(octets[i]);
            if (val < 0 || val > 255) {
                return null;
            }
            bytes[i] = (byte) val;
        }
        return bytes;
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
            return authUsername.equals(parts[0]) && authPassword.equals(parts[1]);
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
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
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
            + "<script src=\"https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js\"></script>\n"
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
