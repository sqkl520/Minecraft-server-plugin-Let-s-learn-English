package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker {

    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/sqkl520/Minecraft-server-plugin-Let-s-learn-English-/releases/latest";
    private static final String GITHUB_RELEASES_URL =
            "https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English-/releases";

    private final LetsLearnEnglish plugin;
    private String latestVersion;
    private boolean updateAvailable;
    private boolean checkComplete;
    private final Set<UUID> dismissedPlayers = new HashSet<>();

    public UpdateChecker(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String currentVersion = plugin.getDescription().getVersion();
                String cleanCurrent = currentVersion.replace("-SNAPSHOT", "");

                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "LetsLearnEnglish-Plugin");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    plugin.getLogger().warning("Update check failed: HTTP " + responseCode);
                    checkComplete = true;
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
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

            } catch (Exception e) {
                plugin.getLogger().warning("Could not check for updates: " + e.getMessage());
            } finally {
                checkComplete = true;
            }
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

        player.sendMessage("");
        player.sendMessage("§6§l===== Let's Learn English! Update Available =====");
        player.sendMessage("§7Current: §fv" + current);
        player.sendMessage("§7Latest: §a§lv" + latestVersion);
        player.sendMessage("§7Download: §b§n" + GITHUB_RELEASES_URL);
        player.sendMessage("§7(Use §e/lea ignore-update §7to dismiss this message)");
        player.sendMessage("§6==================================================");
        player.sendMessage("");
    }

    public void dismiss(Player player) {
        dismissedPlayers.add(player.getUniqueId());
        player.sendMessage("§aUpdate notification dismissed. You will not be reminded again this session.");
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

    public boolean isCheckComplete() {
        return checkComplete;
    }

    public String getLatestVersion() {
        return latestVersion;
    }
}