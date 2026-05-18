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
     * Export all player learning data to a CSV file.
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
                    writer.write(player.getUniqueId() + ","
                            + player.getName() + ","
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
}