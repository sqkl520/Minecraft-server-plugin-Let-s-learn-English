package com.letslearnenglish.minecraftplugin;

import com.letslearnenglish.minecraftplugin.api.LetsLearnEnglishAPI;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.dialogue.DialogueManagerImpl;
import com.letslearnenglish.minecraftplugin.core.manager.AdminCommandHandler;
import com.letslearnenglish.minecraftplugin.core.manager.DataExporter;
import com.letslearnenglish.minecraftplugin.core.manager.HotReloadManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.listener.GUIMenuListener;
import com.letslearnenglish.minecraftplugin.listener.NPCInteractionListener;
import com.letslearnenglish.minecraftplugin.listener.PlayerJoinListener;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import com.letslearnenglish.minecraftplugin.util.VersionAdapter;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Let's Learn English! - Minecraft English Learning Plugin Main Class
 *
 * This plugin guides players to learn English through gamification, including:
 * - Word memory training system
 * - Scenario dialogue practice module
 * - Progress tracking and reward system
 * - Admin console
 *
 * @author sqkl520
 * @version 1.0.0
 */
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
        this.dialogueManager = new DialogueManagerImpl(configManager, playerDataStore);
        this.progressManager = new ProgressManagerImpl(this, configManager, playerDataStore);

        this.adminCommandHandler = new AdminCommandHandler(this);
        this.dataExporter = new DataExporter(this, playerDataStore);
        this.hotReloadManager = new HotReloadManager(this, configManager);

        this.api = new LetsLearnEnglishAPI(this);

        registerListeners();
        registerCommands();
        startScheduledTasks();

        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info("============================================");
        getLogger().info("  Let's Learn English! v" + getDescription().getVersion());
        getLogger().info("  Plugin enabled successfully! (Time: " + loadTime + "ms)");
        getLogger().info("============================================");
    }

    @Override
    public void onDisable() {
        getLogger().info("Saving player data...");

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
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            getServer().getOnlinePlayers().forEach(p -> playerDataStore.savePlayerData(p.getUniqueId()));
        }, saveInterval * 1200L, saveInterval * 1200L);

        if (configManager.getMainConfig().getBoolean("word-system.review.enabled", true)) {
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                wordManager.getReviewScheduler().checkAndNotifyReviews();
            }, 6000L, 6000L);
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

    public String getPlayerLanguage(Player player) {
        return playerLanguage.getOrDefault(player.getUniqueId(), "en");
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