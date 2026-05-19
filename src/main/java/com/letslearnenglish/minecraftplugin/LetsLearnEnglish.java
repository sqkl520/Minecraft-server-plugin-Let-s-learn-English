package com.letslearnenglish.minecraftplugin;

import com.letslearnenglish.minecraftplugin.api.LetsLearnEnglishAPI;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.dialogue.DialogueManagerImpl;
import com.letslearnenglish.minecraftplugin.core.manager.AdminCommandHandler;
import com.letslearnenglish.minecraftplugin.core.manager.DataExporter;
import com.letslearnenglish.minecraftplugin.core.manager.HotReloadManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.listener.ChatAnswerListener;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.listener.GUIMenuListener;
import com.letslearnenglish.minecraftplugin.listener.NPCInteractionListener;
import com.letslearnenglish.minecraftplugin.listener.PlayerJoinListener;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import com.letslearnenglish.minecraftplugin.util.VersionAdapter;
import com.letslearnenglish.minecraftplugin.core.UpdateChecker;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LetsLearnEnglish extends JavaPlugin {

    private static LetsLearnEnglish instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PlayerDataStore playerDataStore;
    private MessageUtil messageUtil;
    private VersionAdapter versionAdapter;

    private WordManagerImpl wordManager;
    private DialogueManagerImpl dialogueManager;
    private ProgressManagerImpl progressManager;

    private AdminCommandHandler adminCommandHandler;
    private DataExporter dataExporter;
    private HotReloadManager hotReloadManager;

    private LetsLearnEnglishAPI api;

    private UpdateChecker updateChecker;

    private WordLearningManager wordLearningManager;

    private SoundManager soundManager;

    private final Map<UUID, String> playerLanguage = new ConcurrentHashMap<>();

    @Override
    public void onLoad() {
        instance = this;
        this.versionAdapter = new VersionAdapter(this);
        getLogger().info("Version adapter initialized for server version: "
                + getServer().getMinecraftVersion());
    }

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();

        this.configManager = new ConfigManager(this);
        this.configManager.loadAllConfigs();

        loadPlayerLanguages();

        this.messageUtil = new MessageUtil(this, configManager);

        this.databaseManager = new DatabaseManager(this, configManager);
        this.databaseManager.initialize();

        this.playerDataStore = new PlayerDataStore(this, databaseManager);

        this.wordManager = new WordManagerImpl(this, configManager, playerDataStore);
        this.wordLearningManager = new WordLearningManager(this);
        this.dialogueManager = new DialogueManagerImpl(configManager, playerDataStore);
        this.progressManager = new ProgressManagerImpl(this, playerDataStore);

        this.adminCommandHandler = new AdminCommandHandler(this);
        this.dataExporter = new DataExporter(this, playerDataStore);
        this.hotReloadManager = new HotReloadManager(this, configManager);

        this.soundManager = new SoundManager(this);

        this.api = new LetsLearnEnglishAPI(this);

        registerListeners();
        registerCommands();
        startScheduledTasks();

        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info("============================================");
        getLogger().info("  Let's Learn English! v" + getDescription().getVersion());
        getLogger().info("  Plugin enabled successfully! (Time: " + loadTime + "ms)");
        getLogger().info("============================================");

        this.updateChecker = new UpdateChecker(this);
        updateChecker.checkAsync();
    }

    @Override
    public void onDisable() {
        getLogger().info("Saving player data...");

        if (wordLearningManager != null) {
            wordLearningManager.clearAllSessions();
            getLogger().info("Cleared all active word learning sessions.");
        }

        savePlayerLanguages();

        if (playerDataStore != null) {
            getServer().getOnlinePlayers().forEach(p -> playerDataStore.savePlayerData(p.getUniqueId()));
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("Let's Learn English! plugin has been safely disabled.");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new NPCInteractionListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatAnswerListener(this), this);
        getServer().getPluginManager().registerEvents(soundManager, this);
    }

    private void registerCommands() {
        var learnCmd = getCommand("learnenglish");
        if (learnCmd != null) {
            learnCmd.setExecutor(new com.letslearnenglish.minecraftplugin.command.LearnCommand(this));
        }
        var adminCmd = getCommand("learnadmin");
        if (adminCmd != null) {
            adminCmd.setExecutor(new com.letslearnenglish.minecraftplugin.command.AdminCommand(this));
        }
    }

    private void startScheduledTasks() {
        int saveInterval = configManager.getMainConfig().getInt("general.auto-save-interval", 5);
        getServer().getScheduler().runTaskTimer(this, () -> {
            getServer().getOnlinePlayers().forEach(p -> playerDataStore.savePlayerData(p.getUniqueId()));
        }, saveInterval * 1200L, saveInterval * 1200L);

        if (configManager.getMainConfig().getBoolean("word-system.review.enabled", true)) {
            getServer().getScheduler().runTaskTimer(this, () -> {
                wordManager.getReviewScheduler().checkAndNotifyReviews();
            }, 6000L, 6000L);
        }

        if (configManager.getMainConfig().getBoolean("daily-reminder.enabled", true)) {
            scheduleDailyReminders();
        }

        if (configManager.getMainConfig().getBoolean("general.backup.enabled", true)) {
            scheduleDatabaseBackup();
        }
    }

    private void scheduleDailyReminders() {
        List<Integer> reminderHours = configManager.getMainConfig().getIntegerList("daily-reminder.reminder-hours");
        if (reminderHours.isEmpty()) {
            getLogger().warning("Daily reminder is enabled but no reminder-hours configured.");
            return;
        }

        String rawMessage = configManager.getMainConfig().getString("daily-reminder.message",
                "{prefix}&6It's time to learn English! Use &e/le menu &6to start!");

        Set<Integer> remindedHours = new HashSet<>();
        final Set<Integer> effectiveHours = new HashSet<>(reminderHours);

        getServer().getScheduler().runTaskTimer(this, () -> {
            Calendar cal = Calendar.getInstance();
            int currentHour = cal.get(Calendar.HOUR_OF_DAY);
            int currentMinute = cal.get(Calendar.MINUTE);

            if (currentHour == 0 && currentMinute == 0) {
                remindedHours.clear();
            }

            if (currentMinute == 0 && effectiveHours.contains(currentHour) && !remindedHours.contains(currentHour)) {
                remindedHours.add(currentHour);
                String prefix = configManager.getMainConfig().getString("prefix."
                        + configManager.getMainConfig().getString("general.language", "en"), "");
                String message = ChatColor.translateAlternateColorCodes('&',
                        rawMessage.replace("{prefix}", prefix));
                for (Player player : getServer().getOnlinePlayers()) {
                    player.sendMessage(message);
                }
                getLogger().info("Daily study reminder sent to all online players at hour " + currentHour + ":00");
            }
        }, 1200L, 1200L);

        getLogger().info("Daily study reminder scheduled at hours: " + effectiveHours);
    }

    private void scheduleDatabaseBackup() {
        int intervalMinutes = configManager.getMainConfig().getInt("general.backup.interval", 1440);
        if (intervalMinutes <= 0) {
            getLogger().info("Database auto-backup is disabled (interval set to 0).");
            return;
        }

        int maxBackups = configManager.getMainConfig().getInt("general.backup.max-backups", 7);
        long intervalTicks = intervalMinutes * 1200L;

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            backupDatabase(maxBackups);
        }, intervalTicks, intervalTicks);

        getLogger().info("Database auto-backup scheduled every " + intervalMinutes
                + " minutes, keeping up to " + maxBackups + " backups.");
    }

    private void backupDatabase(int maxBackups) {
        String dbType = configManager.getMainConfig().getString("database.type", "sqlite");
        if (!"sqlite".equalsIgnoreCase(dbType)) {
            getLogger().info("Database backup skipped: only SQLite backup is supported.");
            return;
        }

        String dbFileName = configManager.getMainConfig().getString("database.sqlite.filename", "playerdata.db");
        File dbFile = new File(getDataFolder(), dbFileName);
        if (!dbFile.exists()) {
            getLogger().warning("Database file not found for backup: " + dbFile.getAbsolutePath());
            return;
        }

        File backupDir = new File(getDataFolder(), "backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File backupFile = new File(backupDir, dbFileName + "." + timestamp + ".bak");

        try {
            Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("Database backup created: " + backupFile.getName());
        } catch (IOException e) {
            getLogger().warning("Failed to create database backup: " + e.getMessage());
            return;
        }

        File[] backupFiles = backupDir.listFiles((dir, name) -> name.startsWith(dbFileName) && name.endsWith(".bak"));
        if (backupFiles != null && backupFiles.length > maxBackups) {
            java.util.Arrays.sort(backupFiles, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int toDelete = backupFiles.length - maxBackups;
            for (int i = 0; i < toDelete; i++) {
                if (backupFiles[i].delete()) {
                    getLogger().info("Old backup deleted: " + backupFiles[i].getName());
                }
            }
        }
    }

    public static LetsLearnEnglish getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }

    public MessageUtil getMessageUtil() {
        return messageUtil;
    }

    public VersionAdapter getVersionAdapter() {
        return versionAdapter;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public WordLearningManager getWordLearningManager() {
        return wordLearningManager;
    }

    public WordManagerImpl getWordManager() {
        return wordManager;
    }

    public DialogueManagerImpl getDialogueManager() {
        return dialogueManager;
    }

    public ProgressManagerImpl getProgressManager() {
        return progressManager;
    }

    public AdminCommandHandler getAdminCommandHandler() {
        return adminCommandHandler;
    }

    public DataExporter getDataExporter() {
        return dataExporter;
    }

    public HotReloadManager getHotReloadManager() {
        return hotReloadManager;
    }

    public LetsLearnEnglishAPI getAPI() {
        return api;
    }

    public SoundManager getSoundManager() {
        return soundManager;
    }

    public String getPlayerLanguage(Player player) {
        String defaultLang = getConfig().getString("general.language", "en");
        if (!"en".equals(defaultLang) && !"zh".equals(defaultLang)) {
            defaultLang = "en";
        }
        return playerLanguage.getOrDefault(player.getUniqueId(), defaultLang);
    }

    public void setPlayerLanguage(Player player, String lang) {
        playerLanguage.put(player.getUniqueId(), lang);
        savePlayerLanguages();
    }

    private void loadPlayerLanguages() {
        File langFile = new File(getDataFolder(), "player_lang.yml");
        if (!langFile.exists()) {
            return;
        }
        FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        for (String key : langConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                playerLanguage.put(uuid, langConfig.getString(key, "en"));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID in player_lang.yml: " + key);
            }
        }
        getLogger().info("Loaded " + playerLanguage.size() + " player language preferences.");
    }

    private void savePlayerLanguages() {
        File langFile = new File(getDataFolder(), "player_lang.yml");
        FileConfiguration langConfig = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : playerLanguage.entrySet()) {
            langConfig.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            langConfig.save(langFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save player language preferences: " + e.getMessage());
        }
    }
}