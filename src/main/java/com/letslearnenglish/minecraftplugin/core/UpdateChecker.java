package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker {

    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases/latest";
    private static final String GITHUB_RELEASES_URL =
            "https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases";

    private static final int MAX_RETRIES = 2;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;
    /**
     * FIX 2（round14 F4）：响应体上限（字符数 ≈ 字节数，GitHub 的 release JSON 是纯 ASCII）。
     *
     * <p>READ_TIMEOUT 只约束「单次读多久没数据」，不约束「总共读多少」—— 一个
     * 足够快的服务端可以在 8 秒内推完几百 MB，插件线程会一路 append 到 OOM。
     * 正常的 latest-release JSON 只有几 KB，1 MiB 已是 200 倍余量。
     */
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    private final LetsLearnEnglish plugin;
    private volatile String latestVersion;
    private volatile boolean updateAvailable;
    /**
     * FIX 3（round14 F4）：「检查已停止尝试」—— 不是「检查成功」。HTTP 非 200、重试耗尽、以及响应
     * 超限都会把它置 true，但 {@code updateAvailable} 只在真正解析出更新的 tag
     * 时才为 true。因此通知门禁必须两个一起看（见 {@link #notifyPlayer}），
     * 任何一个单独用都不安全。
     */
    private volatile boolean checkFinished;
    /**
     * FIX 1（round14 F4）：已忽略更新提醒的玩家。用 ConcurrentHashMap 的 keySet 而非 HashSet：
     * {@code notifyPlayer} 由延迟任务与 listener 上下文调用、{@code dismiss} 由
     * 玩家指令/点击触发，两者都不保证同一线程，裸 HashSet 会在并发 add/contains
     * 时静默损坏内部表。
     */
    private final Set<UUID> dismissedPlayers = ConcurrentHashMap.newKeySet();

    public UpdateChecker(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int attempts = 0;
            Exception lastException = null;

            while (attempts < MAX_RETRIES) {
                attempts++;
                HttpURLConnection conn = null;
                try {
                    if (attempts > 1) {
                        // 重试前等待一段时间（指数退避）
                        Thread.sleep((long) Math.pow(2, attempts) * 1000);
                    }

                    String currentVersion = plugin.getDescription().getVersion();
                    String cleanCurrent = currentVersion.replace("-SNAPSHOT", "");

                    URL url = new URL(GITHUB_API_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "LetsLearnEnglish-Plugin");
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setInstanceFollowRedirects(true);

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        if (responseCode == 403 && attempts < MAX_RETRIES) {
                            // Rate limited, retry after backoff
                            plugin.getLogger().info("Update check rate limited (attempt "
                                    + attempts + "/" + MAX_RETRIES + "), retrying...");
                            conn.disconnect();
                            continue;
                        }
                        plugin.getLogger().warning("Update check failed: HTTP " + responseCode);
                        checkFinished = true;
                        conn.disconnect();
                        return;
                    }

                    StringBuilder response = new StringBuilder();
                    boolean overflow = false;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        // FIX 2（round14 F4）：有界读取 —— 超过上限立刻停手并丢弃这段响应，按失败处理。
                        //
                        // 用定长 char[] 而不是 readLine()：readLine 会先把整行读进内存再返回，
                        // 一个「几千字节里没有换行」的恶意/异常响应可以让它在我的上限判定
                        // 生效之前就把堆吃满 —— 上限形同虚设。定长缓冲区每次最多只多持有
                        // 一个 chunk，上限才是真的上限。
                        char[] buf = new char[8192];
                        int n;
                        while ((n = reader.read(buf)) != -1) {
                            if (response.length() + n > MAX_RESPONSE_CHARS) {
                                overflow = true;
                                break;
                            }
                            response.append(buf, 0, n);
                        }
                    }
                    conn.disconnect();

                    if (overflow) {
                        // FIX 2（round14 F4）：不能当作成功 —— 响应被截断后正则大概率匹配不到，
                        // 若静默走「已是最新」分支，运维会误以为检查跑通了。
                        plugin.getLogger().warning("Update check failed: response exceeded "
                                + MAX_RESPONSE_CHARS + " chars, discarded");
                        checkFinished = true;
                        return;
                    }

                    Pattern pattern = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
                    Matcher matcher = pattern.matcher(response.toString());

                    if (matcher.find()) {
                        latestVersion = matcher.group(1);

                        if (isNewer(latestVersion, cleanCurrent)) {
                            updateAvailable = true;
                            plugin.getLogger().info("Update available! Current: v" + cleanCurrent
                                    + ", Latest: v" + latestVersion);
                            plugin.getLogger().info("Download: " + GITHUB_RELEASES_URL);

                            notifyOnlineAdmins();
                        } else {
                            plugin.getLogger().info("Plugin is up to date (v" + cleanCurrent + ")");
                        }
                    } else {
                        // FIX 4（round14 F4）：解析不到 tag_name（GitHub 改了 JSON 形状 / 该 release 无 tag）
                        // 时，原实现整段都在 if 里 —— 一个字都不打日志，更新提示就此
                        // 永久静默而日志干净得像一切正常。这里必须留痕，只截一小段
                        // 响应体片段（不 dump 整个 body），够定位即可。
                        plugin.getLogger().warning("Update check could not parse 'tag_name' from GitHub response"
                                + " (JSON shape changed?). Response excerpt: " + excerpt(response));
                    }

                    // 走完一次完整检查（无论解析成功与否），退出重试循环
                    checkFinished = true;
                    return;

                } catch (SocketTimeoutException e) {
                    lastException = e;
                    if (attempts >= MAX_RETRIES) {
                        plugin.getLogger().info("Update check skipped (network unreachable after "
                                + MAX_RETRIES + " attempts)");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    lastException = e;
                    if (attempts >= MAX_RETRIES) {
                        plugin.getLogger().warning("Could not check for updates: " + e.getMessage());
                    }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }

            // 重试耗尽或线程被中断走到了这里：检查已停止尝试，但没成功。
            // checkFinished 置 true 只是为了让通知门禁不再无限等待，
            // updateAvailable 仍为 false，所以不会有人被误导收到「有新版本」。
            checkFinished = true;
        });
    }

    /**
     * FIX 4（round14 F4）辅助：给运维看的响应体片段。硬截断，且压掉换行避免刷屏。
     *
     * <p>先按长度切再压空白：响应体最大可达 {@link #MAX_RESPONSE_CHARS}，
     * 直接对整段跑 replaceAll 会白拷一份 1 MiB 字符串，而这里只需要开头几百字符。
     */
    private static String excerpt(StringBuilder response) {
        final int limit = 200;
        if (response.length() == 0) {
            return "<empty body>";
        }
        String head = response.length() <= limit ? response.toString() : response.substring(0, limit);
        String flat = head.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            // 开头全是空白（或整段就 200 个空格）—— 别谎报「响应体为空」。
            return "<whitespace only>";
        }
        return response.length() <= limit ? flat : flat + "...";
    }

    private void notifyOnlineAdmins() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                notifyPlayer(player);
            }
        });
    }

    public void notifyPlayer(Player player) {
        // FIX 3（round14 F4）：门禁显式写明两个条件各自的作用。
        // updateAvailable 才是「检查的确找到了新版本」，checkFinished 只表示
        // 「检查已经停下来了，不用再等」—— 单独用后者会在失败检查上放行通知。
        if (!checkFinished || !updateAvailable) {
            return;
        }
        if (dismissedPlayers.contains(player.getUniqueId())) {
            return;
        }
        if (!player.isOp() && !player.hasPermission("letslearnenglish.admin")) {
            return;
        }

        String current = plugin.getDescription().getVersion().replace("-SNAPSHOT", "");

        // 空行仅作视觉分隔，内容全部走 i18n
        player.sendMessage("");
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "update.header"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "update.current",
                "version", current));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "update.latest",
                "version", latestVersion));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "update.download",
                "url", GITHUB_RELEASES_URL));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "update.dismiss-hint"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "update.footer"));
        player.sendMessage("");
    }

    public void dismiss(Player player) {
        dismissedPlayers.add(player.getUniqueId());
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "update.dismissed"));
    }

    private boolean isNewer(String latest, String current) {
        // LOG-02：只取主版本数字段。原实现把 "7-r2" 抠成 "72"，旧 tag 被判为新版本。
        String[] latestParts = mainSegments(latest);
        String[] currentParts = mainSegments(current);

        int maxLen = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < maxLen; i++) {
            int l = i < latestParts.length ? parseIntSafe(latestParts[i]) : 0;
            int c = i < currentParts.length ? parseIntSafe(currentParts[i]) : 0;
            if (l > c) {
                return true;
            }
            if (l < c) {
                return false;
            }
        }
        return false;
    }

    /** LOG-02：切出连续数字版本段，遇首个非数字段（{@code -r2} / {@code -rc1} / {@code +build}）即截断。 */
    private static String[] mainSegments(String version) {
        if (version == null) return new String[0];
        String[] raw = version.trim().split("[.\\-+_]");
        int n = 0;
        while (n < raw.length && raw[n].matches("\\d+")) n++;
        return java.util.Arrays.copyOf(raw, n);
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }
}