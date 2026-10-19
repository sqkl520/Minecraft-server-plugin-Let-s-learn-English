package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordBankValidator;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class AdminCommandHandler {

    private final LetsLearnEnglish plugin;

    public AdminCommandHandler(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * R-86h：重置玩家数据。
     *
     * @return true = 找到玩家并已执行重置；false = 在线、数据库、服务器名册都查不到此人
     *         （调用方据此提示"无数据"，不再对不存在的玩家报成功）
     */
    public boolean resetPlayerData(String playerName) {
        plugin.getLogger().info("Admin: Resetting data for player: " + playerName);

        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            plugin.getLogger().warning("Admin: Player not found: " + playerName);
            return false;
        }

        PlayerDataStore dataStore = plugin.getPlayerDataStore();
        // R-03：内存态按 quit 语义清理，且必须先于删表（unload 会先落盘，顺序反了会
        // 把旧内存态写回刚删干净的表）。endQuiz/cancelPractice 在前：在线玩家答题中
        // 被 reset 时，不先清 QuizState 会留下僵尸倒计时；unloadPlayer 清成就缓存，
        // 否则 tryUnlock 的 contains 命中旧缓存，"删掉的成就"会复活。
        plugin.getWordLearningManager().endQuiz(playerId);
        plugin.getWordLearningManager().cancelPractice(playerId);
        dataStore.unloadPlayerData(playerId);
        dataStore.unloadProfileSettings(playerId);
        plugin.getAchievementManager().unloadPlayer(playerId);

        plugin.getDatabaseManager().deletePlayerData(playerId);

        // R9-L3：删表后立即刷榜单缓存，/lea class list 等不等到 5 分钟周期才更新。
        // 同步 DB 读，与本命令既有主线程删表同量级（照 LetsLearnEnglish :225/:425 先例）。
        plugin.getProgressManager().getLeaderboard().refreshCache();

        plugin.getLogger().info("Admin: Player data reset complete for: " + playerName);
        return true;
    }

    /**
     * 解析玩家名对应的 UUID，三级查找：在线玩家 → player_data 表 → 服务器名册
     * （{@link org.bukkit.OfflinePlayer#hasPlayedBefore()}，覆盖"曾玩过但
     * player_data 残留/半删"的情况，允许补删干净）。
     *
     * <p>调用上下文：{@link #resetPlayerData}（/lea reset）与
     * {@link #generateReport}（/lea report，US-RPT-3 提权复用）调用，两者均执行在
     * 命令主线程上，故 {@code Bukkit.getOfflinePlayer(name)} 的阻塞式名册查询安全。
     *
     * @return 查到的 UUID；三级都查不到返回 null
     */
    public UUID resolvePlayerUUID(String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target != null) {
            return target.getUniqueId();
        }

        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT uuid FROM " + tablePrefix
                + "player_data WHERE player_name = ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("uuid"));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to lookup player UUID: " + playerName, e);
        }
        }

        // F5-01：表里查不到（已被删过一次的行不会再出现在 player_data）时，
        // 回落服务器名册 —— 名册命中的旧 UUID 仍可能有其余表的残留行可补删。
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (offline != null && offline.hasPlayedBefore()) {
            return offline.getUniqueId();
        }
        return null;
    }

    /**
     * US-RPT-3：/lea report 派发入口（主线程，plan §3.4 线程模型）。
     *
     * <p>本方法只做三件事：① 门与参数/周期校验；② 经
     * {@link #resolvePlayerUUID} 解析目标 UUID（离线名册查询是 Bukkit 阻塞 API，
     * 仅主线程安全）；③ 目标在线时抓取与 /le stats 同源的累计快照
     * （{@link com.letslearnenglish.minecraftplugin.core.manager.ReportGenerator.CumulativeSnapshot}），
     * 随后把全部 DB 查询/渲染/写文件交给
     * {@link ReportGenerator#generateSingle} 内部异步执行——主线程零 DB IO、零文件 IO。
     *
     * @param sender      命令执行者（玩家/控制台）
     * @param playerName  目标玩家名（"all" 由调用方拦下走批量路径）
     * @param period      已解析的统计周期
     * @param periodSpec  原始周期参数（null/空 = 使用 config 默认周期）
     * @param defaultSpec config.yaml 的 report.period 默认值
     */
    public void generateReport(org.bukkit.command.CommandSender sender, String playerName,
                               String periodSpec, String defaultSpec) {
        if (!plugin.getConfigManager().getMainConfig().getBoolean("report.enabled", true)) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "report.disabled"));
            return;
        }

        ReportGenerator.ReportPeriod period = ReportGenerator.ReportPeriod.compute(
                (periodSpec == null || periodSpec.isBlank()) ? defaultSpec : periodSpec,
                java.time.LocalDate.now());
        if (period == null) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "report.period-invalid", "input",
                    periodSpec == null ? String.valueOf(defaultSpec) : periodSpec));
            return;
        }

        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            // 冻结键 admin.player-no-data 无占位符（既有约定），三级查找失败复用
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "admin.player-no-data"));
            return;
        }

        // 在线快照（主线程读 PlayerDataStore 缓存，与 ProfileGUI /le stats 同源）；
        // 离线传 null，累计数在异步线程读 player_data 直查（plan §1.5）。
        ReportGenerator.CumulativeSnapshot snapshot = null;
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            PlayerDataStore.PlayerStats stats =
                    plugin.getPlayerDataStore().getPlayerStats(playerId);
            snapshot = new ReportGenerator.CumulativeSnapshot(
                    stats.getTotalCorrect(),
                    stats.getTotalSessions(),
                    stats.getTotalScore(),
                    plugin.getPlayerDataStore().getConversationCount(playerId),
                    plugin.getAchievementManager().getUnlockedCount(playerId));
            // 权威名字：在线用 Bukkit 实时名（大小写规范化，报告展示与查找一致）
            playerName = online.getName();
        }

        plugin.getReportGenerator().generateSingle(sender, playerId, playerName, period, snapshot);
    }

    /**
     * US-RPT-3：/lea report all 批量派发（主线程；异步查询/渲染/写文件在
     * {@link ReportGenerator#generateBatch} 内）。
     *
     * @param sender      命令执行者
     * @param periodSpec  原始周期参数（null/空 = config 默认）
     * @param defaultSpec config.yaml 的 report.period 默认值
     */
    public void generateBatchReport(org.bukkit.command.CommandSender sender,
                                    String periodSpec, String defaultSpec) {
        if (!plugin.getConfigManager().getMainConfig().getBoolean("report.enabled", true)) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "report.disabled"));
            return;
        }
        ReportGenerator.ReportPeriod period = ReportGenerator.ReportPeriod.compute(
                (periodSpec == null || periodSpec.isBlank()) ? defaultSpec : periodSpec,
                java.time.LocalDate.now());
        if (period == null) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "report.period-invalid", "input",
                    periodSpec == null ? String.valueOf(defaultSpec) : periodSpec));
            return;
        }
        plugin.getReportGenerator().generateBatch(sender, period);
    }

    /**
     * US-RPT-3：/lea report 第二参数的 tab 补全名源 —— player_data.player_name
     * 前缀查（最多 limit 条）。plan §3.3 将该查询画在 DatabaseManager；本批
     * DatabaseManager 为并行兄弟流文件白名单，方法宿于本 Handler 以免文件重叠，
     * SQL 与 plan 草案逐字一致。单表小查询，主线程调用量级可接受（既有 reset
     * 补全同样在主线程做在线玩家遍历）。
     *
     * @param prefix 已输入前缀（空串全量前缀）
     * @param limit  最大返回条数
     * @return 名字列表（无命中返回空列表）
     */
    public List<String> getKnownPlayerNames(String prefix, int limit) {
        List<String> names = new ArrayList<>();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT DISTINCT player_name FROM " + tablePrefix
                + "player_data WHERE player_name IS NOT NULL AND player_name LIKE ? LIMIT ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, prefix + "%");
                stmt.setInt(2, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("player_name");
                        if (name != null && !name.isBlank()) {
                            names.add(name);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query known player names for tab completion", e);
            }
        }
        return names;
    }

    /**
     * Export a word bank to a YAML file in plugins/LLEP/exports/.
     *
     * @param difficulty the difficulty name of the word bank to export
     * @param sender     the command sender (for feedback messages)
     */
    public void exportWordBank(String difficulty, org.bukkit.command.CommandSender sender) {
        plugin.getLogger().info("Admin: Exporting word bank: " + difficulty);

        WordManagerImpl wordManager = plugin.getWordManager();
        WordBank bank = wordManager.getWordBank(difficulty);
        if (bank == null) {
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.export-failed",
                    "reason", plugin.getMessageUtil().getSenderMessage(sender,
                            "wordbank.reason-unknown-difficulty", "difficulty", difficulty));
            sender.sendMessage(msg);
            plugin.getLogger().warning("Admin: Export failed - unknown difficulty: " + difficulty);
            return;
        }

        // Build YAML configuration
        FileConfiguration config = new YamlConfiguration();
        config.set("difficulty", bank.getDifficulty());
        config.set("description", bank.getDescription());

        List<Map<String, Object>> wordList = new ArrayList<>();
        for (Word w : bank.getAllWords()) {
            Map<String, Object> wordData = new LinkedHashMap<>();
            wordData.put("english", w.getEnglish());
            wordData.put("chinese", w.getChinese());
            if (w.getPartOfSpeech() != null) {
                wordData.put("part_of_speech", w.getPartOfSpeech());
            }
            if (w.getPhonetic() != null) {
                wordData.put("phonetic", w.getPhonetic());
            }
            if (w.getExampleSentence() != null) {
                wordData.put("example_sentence", w.getExampleSentence());
            }
            if (!w.getSynonyms().isEmpty()) {
                wordData.put("synonyms", w.getSynonyms());
            }
            if (!w.getAntonyms().isEmpty()) {
                wordData.put("antonyms", w.getAntonyms());
            }
            wordData.put("difficulty_level", w.getDifficultyLevel());
            if (w.getReward() > 0.0) {
                wordData.put("reward", w.getReward());
            }
            wordList.add(wordData);
        }
        config.set("words", wordList);

        // Create exports directory
        File exportsDir = new File(plugin.getDataFolder(), "exports");
        if (!exportsDir.exists()) {
            exportsDir.mkdirs();
        }

        // Generate filename with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String fileName = difficulty + "_export_" + timestamp + ".yml";
        File exportFile = new File(exportsDir, fileName);

        try {
            config.save(exportFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to save exported word bank: " + exportFile.getAbsolutePath(), e);
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.export-failed",
                    "reason", plugin.getMessageUtil().getSenderMessage(sender,
                            "wordbank.reason-io-error", "error", String.valueOf(e.getMessage())));
            sender.sendMessage(msg);
            return;
        }

        String relativePath = "exports/" + fileName;
        // Log full path to console
        plugin.getLogger().info("Admin: Word bank '" + difficulty
                + "' exported to: " + exportFile.getAbsolutePath()
                + " (" + bank.getWordCount() + " words)");

        // Send relative path to sender (for in-game use, players see the relative path)
        String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.export-success",
                "difficulty", difficulty,
                "file", relativePath,
                "count", String.valueOf(bank.getWordCount()));
        sender.sendMessage(msg);
    }

    /**
     * Import a word bank from a YAML file.
     * Looks for the file in plugins/LLEP/exports/ first, then in the plugin data folder.
     * Validates the file, then imports new words (skipping duplicates).
     *
     * @param fileName the file name (relative to exports/ or data folder)
     * @param sender   the command sender (for feedback messages)
     */
    public void importWordBank(String fileName, org.bukkit.command.CommandSender sender) {
        plugin.getLogger().info("Admin: Importing word bank from file: " + fileName);

        // Look for the file in exports/ first, then in the plugin data folder
        File importFile = new File(plugin.getDataFolder(), "exports/" + fileName);
        if (!importFile.exists()) {
            importFile = new File(plugin.getDataFolder(), fileName);
        }
        if (!importFile.exists()) {
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-failed",
                    "reason", plugin.getMessageUtil().getSenderMessage(sender,
                            "wordbank.reason-file-not-found", "file", fileName));
            sender.sendMessage(msg);
            plugin.getLogger().warning("Admin: Import failed - file not found: " + fileName);
            return;
        }

        // Load as YamlConfiguration
        FileConfiguration config = YamlConfiguration.loadConfiguration(importFile);

        // Validate
        WordBankValidator.ValidationResult result = WordBankValidator.validate(config);
        if (!result.valid()) {
            plugin.getLogger().warning("Admin: Import validation failed for: " + fileName);
            for (String error : result.errors()) {
                plugin.getLogger().warning("  Validation error: " + error);
            }

            String headerMsg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.validation-failed");
            sender.sendMessage(headerMsg);
            // Send each error (limit to first 15 to avoid spam)
            int shown = 0;
            for (String error : result.errors()) {
                if (shown >= 15) {
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "wordbank.validation-more-errors",
                            "count", String.valueOf(result.errors().size() - 15)));
                    break;
                }
                // {error} 是校验器产出的原始文本，按数据注入，不做翻译
                sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                        "wordbank.validation-error-line", "error", error));
                shown++;
            }
            return;
        }

        // Parse words from validated config
        WordManagerImpl wordManager = plugin.getWordManager();
        List<?> rawList = config.getList("words");
        if (rawList == null) {
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-failed",
                    "reason", plugin.getMessageUtil().getSenderMessage(sender,
                            "wordbank.reason-no-words-list"));
            sender.sendMessage(msg);
            return;
        }

        int imported = 0;
        int skipped = 0;
        int errors = 0;
        Map<String, WordBank> affectedBanks = new java.util.HashMap<>();

        for (Object item : rawList) {
            if (!(item instanceof Map)) {
                errors++;
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> wordData = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (entry.getKey() instanceof String key) {
                    wordData.put(key, entry.getValue());
                }
            }

            String english = (String) wordData.get("english");
            String chinese = (String) wordData.get("chinese");

            if (english == null || english.trim().isEmpty()
                    || chinese == null || chinese.trim().isEmpty()) {
                errors++;
                plugin.getLogger().warning("Admin: Skipping word entry with missing english/chinese");
                continue;
            }

            // Determine target difficulty: use the 'difficulty' field from the word,
            // falling back to a 'difficulty' key at the top level of the config
            String targetDifficulty = (String) wordData.get("difficulty");
            if (targetDifficulty == null || targetDifficulty.trim().isEmpty()) {
                targetDifficulty = config.getString("difficulty");
            }
            if (targetDifficulty == null || targetDifficulty.trim().isEmpty()) {
                errors++;
                plugin.getLogger().warning("Admin: No difficulty for word: " + english);
                continue;
            }

            WordBank targetBank = wordManager.getWordBank(targetDifficulty);
            if (targetBank == null) {
                // Bank doesn't exist yet — create a word list file for it
                targetBank = new WordBank(targetDifficulty, new YamlConfiguration());
                // We'll track affected banks to save them later
            }

            // Check if word already exists in any WordBank (by english name, case-insensitive)
            boolean exists = false;
            for (String diff : wordManager.getAvailableDifficulties()) {
                WordBank bank = wordManager.getWordBank(diff);
                if (bank != null && bank.getWord(english) != null) {
                    exists = true;
                    break;
                }
            }

            if (exists) {
                skipped++;
                plugin.getLogger().info("Admin: Skipping duplicate word: " + english);
                continue;
            }

            // Build the Word object
            String partOfSpeech = (String) wordData.get("part_of_speech");
            String phonetic = (String) wordData.get("phonetic");
            String exampleSentence = (String) wordData.get("example_sentence");

            List<String> synonyms = new ArrayList<>(4);
            Object synObj = wordData.get("synonyms");
            if (synObj instanceof List) {
                for (Object s : (List<?>) synObj) {
                    if (s instanceof String str) synonyms.add(str);
                }
            }

            List<String> antonyms = new ArrayList<>(4);
            Object antObj = wordData.get("antonyms");
            if (antObj instanceof List) {
                for (Object a : (List<?>) antObj) {
                    if (a instanceof String str) antonyms.add(str);
                }
            }

            int difficultyLevel = 1;
            Object diffObj = wordData.get("difficulty_level");
            if (diffObj instanceof Number num) {
                difficultyLevel = num.intValue();
            }

            double reward = 0.0;
            Object rewardObj = wordData.get("reward");
            if (rewardObj instanceof Number num) {
                reward = num.doubleValue();
            }

            Word word = new Word(english, chinese, partOfSpeech, phonetic,
                    exampleSentence, synonyms, antonyms,
                    targetDifficulty, difficultyLevel, reward);
            targetBank.addWord(word);
            affectedBanks.put(targetDifficulty, targetBank);
            imported++;
        }

        // Save all affected word banks to YAML
        for (Map.Entry<String, WordBank> entry : affectedBanks.entrySet()) {
            saveWordBankToYaml(entry.getKey(), entry.getValue());
        }

        // Log summary to console
        plugin.getLogger().info("Admin: Import complete. Imported " + imported
                + " words, skipped " + skipped + " duplicates, " + errors + " errors.");

        // Send summary to sender
        String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-summary",
                "imported", String.valueOf(imported),
                "skipped", String.valueOf(skipped),
                "errors", String.valueOf(errors));
        sender.sendMessage(msg);
    }

    private void saveWordBankToYaml(String difficulty, WordBank bank) {
        FileConfiguration config = new YamlConfiguration();
        config.set("description", bank.getDescription());

        List<Map<String, Object>> wordList = new ArrayList<>();
        for (Word w : bank.getAllWords()) {
            Map<String, Object> wordData = new LinkedHashMap<>();
            wordData.put("english", w.getEnglish());
            wordData.put("chinese", w.getChinese());
            if (w.getPartOfSpeech() != null) {
                wordData.put("part_of_speech", w.getPartOfSpeech());
            }
            if (w.getPhonetic() != null) {
                wordData.put("phonetic", w.getPhonetic());
            }
            if (w.getExampleSentence() != null) {
                wordData.put("example_sentence", w.getExampleSentence());
            }
            if (!w.getSynonyms().isEmpty()) {
                wordData.put("synonyms", w.getSynonyms());
            }
            if (!w.getAntonyms().isEmpty()) {
                wordData.put("antonyms", w.getAntonyms());
            }
            wordData.put("difficulty_level", w.getDifficultyLevel());
            if (w.getReward() > 0.0) {
                wordData.put("reward", w.getReward());
            }
            wordList.add(wordData);
        }
        config.set("words", wordList);

        plugin.getConfigManager().saveConfig(config, "words/" + difficulty + ".yml");
    }

    // ============================================================
    //  US-CLS-1（Wave 4 批次 4A）：/lea class 班级管理命令族
    // ============================================================

    /**
     * US-CLS-1：{@code /lea class} 派发入口（主线程调用，args[0]="class"）。
     *
     * <p>子命令：{@code create|delete|add|remove|list}。门与参数校验在主线程；
     * DB 操作全异步（{@link #runClassAsync}），反馈经 {@link #sendOnMain} 回
     * 主线程。add/remove 的玩家名解析走既有 {@link #resolvePlayerUUID}（离线
     * 名册查询是 Bukkit 阻塞 API，仅主线程安全——先在异步查完班存在性，回主线程
     * 解析玩家，再进异步做成员行变更，对齐"主线程做完 Bukkit 部分再进异步"纪律）。
     */
    public void handleClassCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (!plugin.getConfigManager().getMainConfig()
                .getBoolean("class-system.enabled", false)) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.disabled"));
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        plugin.getLogger().info("Admin: Class command '" + action + "' by " + sender.getName());
        switch (action) {
            case "create" -> handleClassCreate(sender, args);
            case "delete" -> handleClassDelete(sender, args);
            case "add" -> handleClassAdd(sender, args);
            case "remove" -> handleClassRemove(sender, args);
            case "list" -> handleClassList(sender, args);
            default -> sender.sendMessage(plugin.getMessageUtil()
                    .getSenderMessage(sender, "class.usage"));
        }
    }

    /** {@code /lea class create <班名>}：长度/非空/空格校验 → 异步建班。 */
    private void handleClassCreate(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.usage"));
            return;
        }
        String className = args[2];
        int maxLen = plugin.getConfigManager().getMainConfig()
                .getInt("class-system.max-name-length", 32);
        // R7-M2：拒绝 &/§/换行——班名会进 ClassBoardGUI 标题并走
        // translateAlternateColorCodes，染色注入面在写入点收口（全仓 create 是唯一
        // 班名写入面，无 rename/setClassName 入口）。存量含 & 的班名不迁移（展示层
        // 原样翻译，接受）。
        if (className.isBlank() || className.length() > maxLen || className.contains(" ")
                || className.contains("&") || className.contains("§")
                || className.contains("\n") || className.contains("\r")) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "class.name-invalid", "max", String.valueOf(maxLen)));
            return;
        }
        // 控制台建班：created_by 记全零 UUID 哨兵（列 NOT NULL，展示层目前不消费）
        UUID createdBy = (sender instanceof Player player) ? player.getUniqueId() : new UUID(0L, 0L);
        long now = System.currentTimeMillis();
        DatabaseManager db = plugin.getDatabaseManager();
        runClassAsync(() -> {
            if (db.createClass(className, createdBy, now)) {
                sendOnMain(sender, "class.created", "class", className);
            } else {
                sendOnMain(sender, "class.name-exists");
            }
        });
    }

    /** {@code /lea class delete <班名>}：不存在 → class.not-found。 */
    private void handleClassDelete(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.usage"));
            return;
        }
        String className = args[2];
        DatabaseManager db = plugin.getDatabaseManager();
        runClassAsync(() -> {
            if (db.deleteClass(className)) {
                sendOnMain(sender, "class.deleted", "class", className);
            } else {
                sendOnMain(sender, "class.not-found", "name", className);
            }
        });
    }

    /**
     * {@code /lea class add <班名> <玩家>}：异步查班存在 → 主线程解析玩家 →
     * 异步做满员判定与成员行变更（换班 = 退旧班 + 进新班）。
     */
    private void handleClassAdd(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.usage"));
            return;
        }
        String className = args[2];
        String playerName = args[3];
        int maxMembers = plugin.getConfigManager().getMainConfig()
                .getInt("class-system.max-members", 50);
        DatabaseManager db = plugin.getDatabaseManager();
        runClassAsync(() -> {
            DatabaseManager.ClassRow classRow = db.getClassByName(className);
            if (classRow == null) {
                sendOnMain(sender, "class.not-found", "name", className);
                return;
            }
            // resolvePlayerUUID 是 Bukkit 阻塞 API：回主线程解析后再次进异步
            runOnMain(() -> {
                UUID playerId = resolvePlayerUUID(playerName);
                if (playerId == null) {
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "class.player-not-found"));
                    return;
                }
                runClassAsync(() -> applyClassAdd(sender, classRow, playerId, playerName, maxMembers));
            });
        });
    }

    /**
     * 成员行变更（异步线程执行）：一人一班编排 —— 已在本班幂等回 member-added；
     * 换班先判新班满员（满则拒绝，防丢班）再退旧班进新班；Data 层只做单班成员行。
     */
    private void applyClassAdd(org.bukkit.command.CommandSender sender,
                               DatabaseManager.ClassRow classRow, UUID playerId,
                               String playerName, int maxMembers) {
        DatabaseManager db = plugin.getDatabaseManager();
        boolean alreadyMember = false;
        boolean reassigned = false;
        boolean full = false;
        // 满员判定 + 换班（退旧进新）整段持 connectionLock（可重入，内层 DB 方法
        // 同锁嵌套安全）——防两笔并发 add 抢最后一个名额时本地超额加入。
        // R9-L5：锁内零消息投递——幂等分支只置标志，sendOnMain 统一移到锁外
        //（调度器投递不占 DB 锁，缩短持锁区间）。
        synchronized (db.getConnectionLock()) {
            Integer currentClassId = db.getPlayerClassId(playerId);
            if (currentClassId != null && currentClassId == classRow.classId()) {
                // 已在本班：幂等提示（冻结键无"重复加入"专键，复用 member-added）
                alreadyMember = true;
            } else {
                full = db.getClassMemberCount(classRow.classId()) >= maxMembers;
                reassigned = currentClassId != null;
                if (!full) {
                    if (reassigned) {
                        db.removeClassMember(currentClassId, playerId);
                    }
                    db.addClassMember(classRow.classId(), playerId, System.currentTimeMillis());
                }
            }
        }
        if (alreadyMember) {
            sendOnMain(sender, "class.member-added",
                    "player", playerName, "class", classRow.className());
            return;
        }
        if (full) {
            sendOnMain(sender, "class.member-full", "max", String.valueOf(maxMembers));
            return;
        }
        if (reassigned) {
            sendOnMain(sender, "class.reassigned",
                    "player", playerName, "class", classRow.className());
        } else {
            sendOnMain(sender, "class.member-added",
                    "player", playerName, "class", classRow.className());
        }
    }

    /** {@code /lea class remove <班名> <玩家>}：不在班 → class.member-not-in。 */
    private void handleClassRemove(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.usage"));
            return;
        }
        String className = args[2];
        String playerName = args[3];
        DatabaseManager db = plugin.getDatabaseManager();
        runClassAsync(() -> {
            DatabaseManager.ClassRow classRow = db.getClassByName(className);
            if (classRow == null) {
                sendOnMain(sender, "class.not-found", "name", className);
                return;
            }
            runOnMain(() -> {
                UUID playerId = resolvePlayerUUID(playerName);
                if (playerId == null) {
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "class.player-not-found"));
                    return;
                }
                runClassAsync(() -> {
                    if (db.removeClassMember(classRow.classId(), playerId)) {
                        sendOnMain(sender, "class.member-removed", "player", playerName);
                    } else {
                        sendOnMain(sender, "class.member-not-in", "player", playerName);
                    }
                });
            });
        });
    }

    /**
     * {@code /lea class list <班名>}：成员列表 + 赛季窗口分数。R7-L5 起与 /le class
     * 班级榜 GUI 同源——班级过滤聚合查询 {@code getRankedPlayersForPeriodFiltered}
     * 按行返回真实分数，取代旧"赛季榜前 50 快照反查"：榜外/窗口内无会话成员不再
     * 误显 0 分，而是不列入（对齐 GUI"只渲染查询返回者"口径；查询随带的
     * show_in_square 过滤与 GUI 一致，旧快照路径的数据同为过滤后口径）。
     * 查询在异步段执行（成员名单天然 ≤ max-members，有界）；season 未启用时保持
     * 既有降级（{@code entries=null}，渲染侧全成员 0 分按名字序列出，命令侧不拒
     * ——与 GUI 的 season.disabled 门不矛盾）。行序按赛季分降序（查询侧 ORDER BY）。
     */
    private void handleClassList(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.usage"));
            return;
        }
        String className = args[2];
        DatabaseManager db = plugin.getDatabaseManager();
        // R7-L5：赛季窗口在主线程先算好（纯函数零 IO，读内存配置，照 LearnCommand
        // /le class 先例）；未启用（null / season 休眠）→ window=null 走降级路径。
        SeasonManager season = plugin.getSeasonManager();
        final SeasonManager.SeasonWindow window;
        if (season != null && season.isEnabled()) {
            window = season.windowOf(season.currentSeasonId(java.time.LocalDate.now()));
        } else {
            window = null;
        }
        runClassAsync(() -> {
            DatabaseManager.ClassRow classRow = db.getClassByName(className);
            if (classRow == null) {
                sendOnMain(sender, "class.not-found", "name", className);
                return;
            }
            List<DatabaseManager.ClassMemberRow> members = db.getClassMembers(classRow.classId());
            // R7-L5：分数查询移入异步段（不在主线程跑 DB）。
            final List<LeaderboardEntry> entries;
            if (window != null) {
                List<UUID> memberIds = new ArrayList<>(members.size());
                for (DatabaseManager.ClassMemberRow member : members) {
                    memberIds.add(member.uuid());
                }
                entries = plugin.getPlayerDataStore().getRankedPlayersForPeriodFiltered(
                        members.size(), window.start(), window.end(), memberIds);
            } else {
                entries = null; // 赛季未启用：渲染侧保留既有降级（全成员 0 分）
            }
            runOnMain(() -> sendClassList(sender, classRow, members, entries));
        });
    }

    /**
     * list 渲染（主线程零查库）：赛季启用时直接遍历异步段取回的班级过滤榜
     * （分数随行返回、rank 为查询名次——与 GUI 同源同序）；窗口内无会话的成员
     * 不被查询返回、不列出（对齐 GUI 口径）。{@code count} 为班级真实成员数
     * （非展示行数）。赛季未启用降级（{@code entries == null}）：保留既有
     * "空 scoreByUuid"行为——全成员列出、分数 0、按名排序。
     */
    private void sendClassList(org.bukkit.command.CommandSender sender,
                               DatabaseManager.ClassRow classRow,
                               List<DatabaseManager.ClassMemberRow> members,
                               List<LeaderboardEntry> entries) {
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.list-header",
                "class", classRow.className(), "count", String.valueOf(members.size())));
        if (entries != null) {
            for (LeaderboardEntry entry : entries) {
                sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.list-entry",
                        "rank", String.valueOf(entry.rank),
                        "player", entry.playerName,
                        "score", String.valueOf(entry.score)));
            }
            return;
        }
        // 赛季未启用降级：既有"空 scoreByUuid"行为原样（队伍成员 0 分 + 名字序）
        List<DatabaseManager.ClassMemberRow> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(DatabaseManager.ClassMemberRow::playerName,
                String.CASE_INSENSITIVE_ORDER));
        int rank = 1;
        for (DatabaseManager.ClassMemberRow member : sorted) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.list-entry",
                    "rank", String.valueOf(rank++),
                    "player", member.playerName(),
                    "score", "0"));
        }
    }

    /**
     * 异步执行（调度器关闭/插件停用时静默放弃，对齐 ReportGenerator.runAsync）。
     * D2-5：同型帮助器见 util.SchedulerGuard（语义微差：本处带 fine 日志级别），
     * 本批未迁移（薄委托面只收 SocialManager/SeasonManager 两处）。
     */
    private void runClassAsync(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalStateException e) {
            plugin.getLogger().fine("Class task skipped: scheduler unavailable (plugin disabling).");
        }
    }

    /**
     * 回主线程执行（Bukkit API / sendMessage 前置；调度器关闭时静默放弃）。
     * D2-5：同型帮助器见 util.SchedulerGuard（语义微差：本处带 isEnabled 预检），
     * 本批未迁移。
     */
    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (IllegalStateException e) {
            // 调度器已在停用窗口关闭：放弃本次投递
        }
    }

    /** 回主线程解析消息并发送（i18n 按 sender 语言，占位符与冻结清单逐位对齐）。 */
    private void sendOnMain(org.bukkit.command.CommandSender sender, String key,
                            String... placeholders) {
        runOnMain(() -> sender.sendMessage(
                plugin.getMessageUtil().getSenderMessage(sender, key, placeholders)));
    }
}
