package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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

    private final LetsLearnEnglish plugin;
    private volatile String latestVersion;
    private volatile boolean updateAvailable;
    private volatile boolean checkComplete;
    private final Set<UUID> dismissedPlayers = new HashSet<>();

    public UpdateChecker(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int attempts = 0;
            Exception lastException = null;

            while (attempts < MAX_RETRIES) {
                attempts++;
                try {
                    if (attempts > 1) {
                        // 重试前等待一段时间（指数退避）
                        Thread.sleep((long) Math.pow(2, attempts) * 1000);
                    }

                    String currentVersion = plugin.getDescription().getVersion();
                    String cleanCurrent = currentVersion.replace("-SNAPSHOT", "");

                    URL url = new URL(GITHUB_API_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
                        checkComplete = true;
                        conn.disconnect();
                        return;
                    }

                    StringBuilder response = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }
                    conn.disconnect();

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
                    }

                    // 成功完成，退出重试循环
                    checkComplete = true;
                    return;

                } catch (SocketTimeoutException e) {
                    lastException = e;
                    if (attempts >= MAX_RETRIES) {
                        plugin.getLogger().info("Update check skipped (network unreachable after "
                                + MAX_RETRIES + " attempts)");
                    }
                } catch (Exception e) {
                    lastException = e;
                    if (attempts >= MAX_RETRIES) {
                        plugin.getLogger().warning("Could not check for updates: " + e.getMessage());
                    }
                }
            }

            checkComplete = true;
        });
    }

    private void notifyOnlineAdmins() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                notifyPlayer(player);
            }
        });
    }

    public void notifyPlayer(Player player) {
        if (!updateAvailable || !checkComplete) {
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
        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");

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