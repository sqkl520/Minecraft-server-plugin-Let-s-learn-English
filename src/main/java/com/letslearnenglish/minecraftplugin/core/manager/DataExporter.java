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
 */
public class DataExporter {

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
     * @return the path to the exported file
     */
    public String exportAllData() {
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
            return "Export failed: " + e.getMessage();
        }

        return exportFile.getAbsolutePath();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}