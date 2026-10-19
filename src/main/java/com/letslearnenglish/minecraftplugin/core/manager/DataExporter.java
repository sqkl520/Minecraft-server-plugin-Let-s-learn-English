package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Data Exporter
 *
 * Exports player learning data to CSV/JSON format for external analysis.
 *
 * <p>US-RPT-3（域 6，plan-reports §P2-09）：返回协议化——{@link #exportAllData(List, List)}
 * 自 P2 起返回 {@code "Export failed: …"} 字符串供 AdminCommand 前缀判定，
 * 属字符串协议（教训项）。现改为返回 {@link ExportResult} record，成败按
 * {@code success()} 字段判定；AdminCommand 消费点同步迁移（旧串返回语义废弃）。
 * P3-07 收口：CSV 落点由插件根目录改至 {@code exports/} 子目录（并入
 * AdminCommandHandler 词库导出 / ReportGenerator 报告的同族约定）。
 */
public class DataExporter {

    /**
     * US-RPT-3：导出结果协议对象。判定一律走 {@link #success()} 与
     * {@link #filePath()}，永不解析错误串内容。
     *
     * @param success      是否导出成功
     * @param filePath     成功时的相对路径（exports/ 下，R9-M2 展示口径）；失败为 null
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
     * Export the given players' learning data to a CSV file.
     * Note: only exports the passed-in snapshot — by design this covers
     * online players at command time (offline players have no cache snapshot;
     * exporting them would need direct DB reads of player_data/word_progress,
     * P3-07 records this as backlog).
     *
     * <p>R9-H1：本方法为纯文件 IO 的同步方法，不触碰 Bukkit 在线玩家表——
     * 调用方负责线程（AdminCommand 在主线程取快照后于异步线程调用）。
     * {@code playerIds.get(i)} 与 {@code playerNames.get(i)} 逐位对应
     * （同一份主线程快照）；cache-miss 查库由 {@link PlayerDataStore#getPlayerStats}
     * 自持锁完成，异步调用安全（R13 已核该路径"仅 DB"）。
     *
     * <p>US-RPT-3：返回 {@link ExportResult}（协议化）。成功时 {@code filePath}
     * 为相对路径（R9-M2：{@code exports/<文件名>}，与 ReportGenerator 报告 /
     * AdminCommandHandler 词库导出的展示口径一致，不暴露服务器目录；完整路径见
     * 日志行），失败时 {@code errorMessage} 为原因——调用方按字段判定，
     * 不再解析字符串前缀（P2-09 教训落地）。
     *
     * @param playerIds   快照玩家 UUID 列表（与 {@code playerNames} 逐位对应）
     * @param playerNames 快照玩家名列表（写名字列，不依赖异步线程的 getPlayerName）
     * @return 导出结果（永不 null）
     */
    public ExportResult exportAllData(List<UUID> playerIds, List<String> playerNames) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "export_" + timestamp + ".csv";
        // P3-07：落 exports/ 子目录（同 AdminCommandHandler 词库导出 / ReportGenerator
        // 报告目录约定），根目录不再散落 CSV；目录不存在则创建。
        File exportsDir = new File(plugin.getDataFolder(), "exports");
        if (!exportsDir.exists()) {
            exportsDir.mkdirs();
        }
        File exportFile = new File(exportsDir, fileName);

        try (FileWriter writer = new FileWriter(exportFile)) {
            writer.write("Player UUID,Player Name,Total Words,Total Sessions,Total Score,Correct,Incorrect\n");

            for (int i = 0; i < playerIds.size(); i++) {
                UUID playerId = playerIds.get(i);
                String playerName = playerNames.get(i);
                try {
                    PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);
                    writer.write(escapeCsv(playerId.toString()) + ","
                            + escapeCsv(playerName) + ","
                            + stats.getTotalWords() + ","
                            + stats.getTotalSessions() + ","
                            + stats.getTotalScore() + ","
                            + stats.getTotalCorrect() + ","
                            + stats.getTotalIncorrect() + "\n");
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Export write error for player: "
                            + playerName, e);
                }
            }

            plugin.getLogger().info("Data exported to: " + exportFile.getAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to export data", e);
            return new ExportResult(false, null, e.getMessage());
        }

        // R9-M2：返回相对路径（exports/ 下，与报告/词库导出展示口径一致，不暴露
        // 服务器目录）；完整路径仅记录在日志行（运营排查面）。
        return new ExportResult(true, "exports/" + exportFile.getName(), null);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}