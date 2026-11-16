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
     * <p><b>DC-01（可信列）</b>：{@link PlayerDataStore#getPlayerStats} 在统计列读失败时
     * 返回的是<b>四字段全 0 的空壳</b>，且已入缓存不再重试。旧实现把这行零值当普通行写出，
     * 与「真的一分未得的新玩家」在 CSV 里<b>完全无法区分</b>——服主拿这份文件对账时，
     * 一行无标记的 {@code 0,0,0,0,0} 比报错更糟（它看起来是可信的）。
     * 现在每行多写一列 {@code Trusted}：{@code 1} = 该行统计值来自成功的库读取，
     * {@code 0} = 统计列读取失败，<b>该行其余统计字段不可作真值使用</b>
     * （判定口径见 {@link PlayerDataStore#isStatsUnloaded}，与落盘侧 fail-closed
     * 读同一个集合）。列头同步更新以保证列数一致（8 列）。
     *
     * <p>为什么用「加标注列」而不是「整行丢弃」：导出文件是服主的对账/备份产物，
     * 静默少一行同样会导致对不上账；标注列让「不可信」在文件里可判别，且保留
     * UUID/名字以便人工复查。
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
            // DC-01：末列 Trusted（1=统计值可信 / 0=统计列读失败，其余统计列不可作真值）。
            // 列头必须与下面每行的字段数严格一致（8 列）。
            writer.write("Player UUID,Player Name,Total Words,Total Sessions,Total Score,Correct,Incorrect,Trusted\n");

            for (int i = 0; i < playerIds.size(); i++) {
                UUID playerId = playerIds.get(i);
                String playerName = playerNames.get(i);
                try {
                    PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);
                    // DC-01：必须在 getPlayerStats 之后读——cache miss 会先触发
                    // loadPlayerData，读失败标记是在那里落的（先读会漏判）。
                    boolean trusted = !playerDataStore.isStatsUnloaded(playerId);
                    writer.write(escapeCsv(playerId.toString()) + ","
                            + escapeCsv(playerName) + ","
                            + stats.getTotalWords() + ","
                            + stats.getTotalSessions() + ","
                            + stats.getTotalScore() + ","
                            + stats.getTotalCorrect() + ","
                            + stats.getTotalIncorrect() + ","
                            + (trusted ? "1" : "0") + "\n");
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

    /**
     * CSRF/CSV 注入双关防护：既做 RFC 4180 的引号转义，也中和「公式注入」。
     *
     * <p><b>F-08-S5（R9 补齐）</b>：原实现只处理 {@code , " \n \r}。但导出的
     * {@code Player Name} 来自玩家可控的名字，而 Excel / LibreOffice 会把以
     * {@code = + - @} 或 TAB/CR 开头的单元格当<b>公式</b>执行。一个叫
     * {@code =HYPERLINK("http://x","click")} 的玩家，会让服主打开 CSV 时静默执行它。
     *
     * <p>可达性确实受限（Bukkit 用户名只允许 {@code [a-zA-Z0-9_]}），但：
     * <ul>
     *   <li>本方法同时导出 {@code playerName}，而 {@code resolveName} 的回退值是
     *       {@code LEFT JOIN player_data} 里存的名字 —— 那一列在 [[player_data]]
     *       的 UPSERT 中来自 {@code Player#getName()}，历史数据未经校验；</li>
     *   <li>防护成本是一个字符判断，而漏防的后果是服主本机执行任意公式。</li>
     * </ul>
     *
     * <p>中和方式：前置一个单引号（Excel 的「按文本处理」标记），**不修改** 原值语义，
     * 只在序列化层面脱敏。已带引号的转义路径不受影响。
     */
    private String escapeCsv(String value) {
        if (value == null) return "";
        String v = value;
        // 公式注入中和：必须放在引号转义之前，否则前缀会落在引号外。
        if (!v.isEmpty()) {
            char c = v.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                v = "'" + v;
            }
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}