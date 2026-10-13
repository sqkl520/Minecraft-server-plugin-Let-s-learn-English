package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

/**
 * Data Exporter
 *
 * Exports player learning data to CSV/JSON format for external analysis.
 *
 * <p>US-RPT-3（域 6，plan-reports §P2-09）：返回协议化——{@link #exportAllData()}
 * 自 P2 起返回 {@code "Export failed: …"} 字符串供 AdminCommand 前缀判定，
 * 属字符串协议（教训项）。现改为返回 {@link ExportResult} record，成败按
 * {@code success()} 字段判定；AdminCommand 消费点同步迁移（旧串返回语义废弃）。
 * 导出内容与文件位置（插件根目录 CSV）保持现状——读写范围不在本流（PF-RPT-06
 * 已记录目录不一致欠账，独立排期）。
 */
public class DataExporter {

    /**
     * US-RPT-3：导出结果协议对象。判定一律走 {@link #success()} 与
     * {@link #filePath()}，永不解析错误串内容。
     *
     * @param success      是否导出成功
     * @param filePath     成功时的绝对路径；失败为 null
     * @param errorMessage 失败原因；成功为 null
     */
    public record ExportResult(boolean success, String filePath, String errorMessage) {}

    private final LetsLearnEnglish plugin;
    private final PlayerDataStore playerDataStore;

    public DataExporter(LetsLearnEnglish plugin, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
    }

    /**
     * Export online players' learning data to a CSV file.
     * Note: Only exports data for currently online players.
     *
     * <p>US-RPT-3：返回 {@link ExportResult}（协议化）。成功时 {@code filePath}
     * 为绝对路径，失败时 {@code errorMessage} 为原因——调用方按字段判定，
     * 不再解析字符串前缀（P2-09 教训落地）。
     *
     * @return 导出结果（永不 null）
     */
    public ExportResult exportAllData() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "export_" + timestamp + ".csv";
        File exportFile = new File(plugin.getDataFolder(), fileName);

        try (FileWriter writer = new FileWriter(exportFile)) {
            writer.write("Player UUID,Player Name,Total Words,Total Sessions,Total Score,Correct,Incorrect\n");

            plugin.getServer().getOnlinePlayers().forEach(player -> {
                try {
                    PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(player.getUniqueId());
                    writer.write(escapeCsv(player.getUniqueId().toString()) + ","
                            + escapeCsv(player.getName()) + ","
                            + stats.getTotalWords() + ","
                            + stats.getTotalSessions() + ","
                            + stats.getTotalScore() + ","
                            + stats.getTotalCorrect() + ","
                            + stats.getTotalIncorrect() + "\n");
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Export write error for player: "
                            + player.getName(), e);
                }
            });

            plugin.getLogger().info("Data exported to: " + exportFile.getAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to export data", e);
            return new ExportResult(false, null, e.getMessage());
        }

        return new ExportResult(true, exportFile.getAbsolutePath(), null);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}