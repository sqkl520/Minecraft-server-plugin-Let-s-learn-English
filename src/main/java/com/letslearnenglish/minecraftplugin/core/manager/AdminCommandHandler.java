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
import com.letslearnenglish.minecraftplugin.data.repository.ClassRepository;
import com.letslearnenglish.minecraftplugin.data.repository.PlayerDirectoryRepository;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class AdminCommandHandler {

    private final LetsLearnEnglish plugin;

    /** R6-A：player_data 目录查询（名字↔UUID、前缀补全名源）仓储。 */
    private final PlayerDirectoryRepository playerDirectory;

    /** R6-F：班级成员原子编排（换班 = 查当前班 + 判满员 + 退旧班 + 进新班）。 */
    private final ClassRepository classRepository;

    /**
     * R6-A：tab 补全名源短 TTL 缓存 —— 按前缀缓存 30 秒，上限 64 条（简单整体
     * clear 防无界增长）。补全在主线程每次按键都会触发，缓存把"每次按键一次主
     * 线程查库"降为"每前缀每 30 秒一次"。写路径（reset/join 落盘名）最迟 30 秒
     * 后可见，补全场景可接受。用 ConcurrentHashMap 而非普通 HashMap：现有唯一
     * 调用方在主线程，但该类无显式线程契约，避免将来被异步调用时踩坏普通 map。
     */
    private static final long NAME_CACHE_TTL_MILLIS = 30_000L;
    private static final int NAME_CACHE_MAX_ENTRIES = 64;
    private final Map<String, CachedNames> knownNamesCache = new ConcurrentHashMap<>();

    private static final class CachedNames {
        final List<String> names;
        final long expiresAt;

        CachedNames(List<String> names, long expiresAt) {
            this.names = names;
            this.expiresAt = expiresAt;
        }
    }

    public AdminCommandHandler(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.playerDirectory = new PlayerDirectoryRepository(plugin.getDatabaseManager(),
                plugin.getLogger());
        this.classRepository = new ClassRepository(plugin.getDatabaseManager(), plugin.getLogger());
    }

    /**
     * R-86h：重置玩家数据。
     *
     * <p><b>R11：{@code deletePlayerData} 现在返回成败，失败必须报出去。</b>它是
     * 「任一表失败即整批 rollback」的单事务，{@code false} 意味着<b>一条数据都没删</b>；
     * 原实现在它返回后无条件打印 "Player data reset complete" 并 {@code return true}，
     * 于是管理员看到"已重置成功"而数据原封不动（隐私删除语义下这是最重的一类谎报）。
     *
     * <p><b>失败方向的处置</b>：本方法只有 {@code boolean} 这一个信号通道，而它有
     * <b>三种</b>结局（查无此人 / 已重置 / 重置失败）。{@code false} 只能表达"没有成功"，
     * 因此失败时返回 {@code false} —— 上层 {@code AdminCommand.handleReset} 会落到
     * "未找到该玩家的数据"分支：文案不够准，但<b>不会再说"已重置"</b>，管理员会去查日志。
     * 真正的现场在本方法打出的那条 SEVERE 里（含"一条都没删"和上层会显示什么）。
     *
     * <p>⚠ 不能靠删表结果去动内存态：内存 unload 已在本方法前半段完成，而 DB 删除失败时
     * 内存是空的、DB 仍有行 —— 玩家下次加载会从 DB 读回，行为与"没 reset 过"一致，
     * 无需也不应做反向补偿（把内存"恢复"需要重读全量数据，代价远大于收益）。
     *
     * @return true = 找到玩家<b>且</b>删表事务已 commit（数据确实清空）；
     *         false = 查无此人，<b>或</b>删表失败已整批回滚（数据一条都没删，详见控制台 SEVERE）
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
        // R7-D（P1-1）：每日挑战的 per-player 内存态必须一起失效。deletePlayerData 会删掉
        // daily_challenge_words 与 daily_word_reward_claims 两表的本玩家行，而
        // DailyChallengeManager 的 dailyWordStateCache（挑战词集合 / 已完成集合 / 已付位）
        // 与 wordChallengeClaimedToday（"今日已付"内存快路径）都是进程内状态，删表不会碰。
        // 不清理的后果（在线玩家 reset 后真实可复现）：缓存日期仍是今日 → getOrLoadWordState
        // 命中旧缓存不重建 → 旧 completedWords 短路 onWordCorrect 的写入分支（DB 行已删，
        // 该词当天永远不再落库）；同时 getTodayWordProgress 经 ensureWordChallengesAssigned
        // 给 DB 分配一批新词，而 onWordCorrect 仍拿旧 challengeWords 做 contains → 新词
        // 永不命中，GUI 显示 5 词、进度恒 0/5。已付内存位则会造成"DB 台账已删、内存仍说已付"，
        // 重刷完挑战当天也拿不到奖励。故这里调 resetPlayer（清缓存 + 清已付内存位），
        // 而不是只调 unloadPlayer（后者刻意不动已付位，见其 javadoc 的 quit 语义）。
        // 顺序：与 dataStore.unloadPlayerData 同理，内存清理必须先于删表；resetPlayer 只做
        // 两次进程内集合 remove（不落盘、不查库），所以不存在"把旧态写回刚删的
        // 表"的风险，放在 deletePlayerData 之前只是保持与其他 unload 一致的时序纪律。
        plugin.getDailyChallengeManager().resetPlayer(playerId);

        // R5-DC-02（round6）：日限账本也必须一起归零。deletePlayerData 现在会删
        // daily_coin_counters 的行（DB 侧），但 RewardEngine.todayCoinsCache 是进程内
        // 状态，删表碰不到它；而该表自 F-09-02 起是日限的写穿真源 —— 内存不清、表已删，
        // 下一次 getTodayCoinsEarnedCents 会 miss 后回读（表里已无行 → 0），看似也能归零，
        // 但 reset 前若该玩家仍在线且内存有值，就会继续按旧额度压制，直到跨天或 reload。
        // 故这里显式清一次（用 reset 专用入口，不是 quit 语义的 unloadPlayer）。
        plugin.getProgressManager().getRewardEngine().resetDailyCoinsFor(playerId);

        // R11：删表成败必须被检查。false = 整批 rollback（或事务没开起来），数据一条都没删。
        boolean deleted = plugin.getDatabaseManager().deletePlayerData(playerId);

        // R9-L3：删表后立即刷榜单缓存，/lea class list 等不等到 5 分钟周期才更新。
        // 同步 DB 读，与本命令既有主线程删表同量级（照 LetsLearnEnglish :225/:425 先例）。
        // 失败路径也刷：删表 0 行时缓存无需变化，但这里不做分支（refresh 是幂等重读，
        // 且"失败时反而跳过刷新"会在部分成功（不会发生）的假设上编码）。
        plugin.getProgressManager().getLeaderboard().refreshCache();

        if (!deleted) {
            // 不报成功。这条 SEVERE 是唯一能把「哪个玩家、后果是什么、上层会显示什么」
            // 串起来的现场 —— 上层只会显示 "admin.player-no-data"，看起来像"查无此人"。
            plugin.getLogger().severe("Admin: Player data reset FAILED for: " + playerName
                    + " (" + playerId + ") — nothing was deleted (transaction rolled back)."
                    + " The sender will be told 'no data found' (admin.player-no-data);"
                    + " check the WARNING lines above for the failing DELETE.");
            return false;
        }

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

        UUID fromDb = playerDirectory.resolveUuidByName(playerName);
        if (fromDb != null) {
            return fromDb;
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
     * 仅主线程安全）；③ 把"目标是否在线"交给
     * {@link ReportGenerator#generateSingle}——累计快照
     * （{@link com.letslearnenglish.minecraftplugin.core.manager.ReportGenerator.CumulativeSnapshot}）
     * 取数与全部 DB 查询/渲染/写文件都在其异步段执行，报告管线零主线程 DB IO/文件 IO
     * （F3：快照三读在 cache miss 时同步查库，故不得在本方法取）。
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

        // F3：只传"是否在线"，快照取数在 generateSingle 的异步段完成
        // （三读在 cache miss 时会同步查库，主线程取即破坏零 DB IO 契约）。
        Player online = Bukkit.getPlayer(playerId);
        String name = online != null ? online.getName() : playerName;
        plugin.getReportGenerator().generateSingle(sender, playerId, name, period,
                online != null);
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
     * 前缀查（最多 limit 条）。R6-A 起 SQL 收口在 {@link PlayerDirectoryRepository}，
     * 并在本类加 30 秒内存缓存（按前缀分键，上限 64 条）——补全每次按键都在主线程
     * 触发，缓存把主线程查库降为"每前缀每 30 秒最多一次"。
     *
     * @param prefix 已输入前缀（空串全量前缀）
     * @param limit  最大返回条数
     * @return 名字列表（无命中返回空列表）
     */
    public List<String> getKnownPlayerNames(String prefix, int limit) {
        String key = prefix + "|" + limit;
        long now = System.currentTimeMillis();
        CachedNames cached = knownNamesCache.get(key);
        if (cached != null && cached.expiresAt > now) {
            return new ArrayList<>(cached.names);
        }
        List<String> names = playerDirectory.findNamesByPrefix(prefix, limit);
        if (knownNamesCache.size() >= NAME_CACHE_MAX_ENTRIES) {
            knownNamesCache.clear(); // 简单整体清空：条目少、重建代价低
        }
        knownNamesCache.put(key, new CachedNames(new ArrayList<>(names),
                now + NAME_CACHE_TTL_MILLIS));
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
            // freq_rank 是可选派生元数据，约定值 0（未知）时不写键 —— 与上面 reward
            // 「默认值不落盘」同一取舍：1025 词里 530 个没有排名，写出 530 个冗余的
            // "freq_rank: 0" 只会让人工编辑的 YAML 变噪音。有排名则**必须**写，否则
            // export → import 一趟就把频次数据静默抹掉（静默数据丢失比报错难查得多）。
            if (w.hasFreqRank()) {
                wordData.put("freq_rank", w.getFreqRank());
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

    /** 用户可控单段名 → baseDir 内的 File；含分隔符、或归一化后越界（..、绝对路径、非法字符）返回 null。 */
    private static File resolveInside(File baseDir, String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null;
        try {
            var base = baseDir.toPath().toAbsolutePath().normalize();
            var target = base.resolve(name).normalize();
            return target.startsWith(base) && !target.equals(base) ? target.toFile() : null;
        } catch (java.nio.file.InvalidPathException e) { return null; }
    }

    /**
     * Import a word bank from a YAML file.
     * Looks for the file in plugins/LLEP/exports/ first, then in the plugin data folder.
     * Validates the file, then imports new words (skipping duplicates).
     *
     * <h2>提交顺序：先落盘、后 commit 到内存（本方法的核心契约）</h2>
     * <p>本方法<b>绝不允许</b>先改内存再落盘。历史上的形态是循环里直接
     * {@code targetBank.addWord(word)}，然后才在末尾统一写盘 —— 一旦写盘失败：
     * 内存的 {@link WordBank} 已含新词、磁盘一个字节都没变，管理员重试导入还会被
     * 报成 {@code skipped N duplicates}（因为内存里已经有了），形成「内存与磁盘分叉，
     * 且分叉本身把修复路径也堵死」的僵局。
     *
     * <p>现在的顺序（每个难度档独立走完，互不连坐）：
     * <ol>
     *   <li><b>解析 + 去重</b>：只在 {@link PendingBank#words} 这个<b>暂存区</b>里累积待加词，
     *       全程不碰 {@code wordManager} 里的活 {@link WordBank}。</li>
     *   <li><b>落盘</b>：用活词库的既有词 + 暂存区新词拼一份一次性快照
     *       （{@link WordBank#snapshotWith}），交给 {@link #saveWordBankToYaml} 序列化。
     *       快照与活词库走的是同一条序列化代码，字段集不可能分叉。</li>
     *   <li><b>commit</b>：<b>只有该档写入成功</b>，才把暂存区的词逐个
     *       {@link WordBank#addWord} 进活词库。写入失败的档位，活词库<b>一个词都不加</b>。</li>
     * </ol>
     *
     * <p>为什么 commit 阶段不需要回滚：{@code addWord} 只是往
     * {@link java.util.concurrent.CopyOnWriteArrayList} 追加，不会失败；且落盘已经成功、
     * 磁盘与即将提交的内存内容一致，此时中断服务重启后从磁盘重建得到的也是同一份内容。
     *
     * <p>为什么用「原地 append」而不是「替换 {@code wordBanks} 里的引用」：替换引用需要
     * 同步 {@code WordManagerImpl.globalWordIndex}（漏了就是 {@code getWord} 查不到新词），
     * 还会让在途 {@code LearningSession} 持有的 {@link Word} 对象与词库脱钩。append 两个坑
     * 都不存在，且 {@code addWord} 之后 {@code WordManagerImpl} 只需补一次索引（见
     * {@link #indexImportedWords}）。
     *
     * <p><b>部分成功</b>：多档导入里有的成功有的失败时，成功档的内存与磁盘都含新词，
     * 失败档的内存与磁盘都<b>不含</b>新词。整体照旧报失败（{@code import-persist-failed}），
     * 但不再谎称「已进内存」—— 失败档位确实没有进内存，重试一次即可真正导入。
     *
     * @param fileName the file name (relative to exports/ or data folder)
     * @param sender   the command sender (for feedback messages)
     */
    public void importWordBank(String fileName, org.bukkit.command.CommandSender sender) {
        plugin.getLogger().info("Admin: Importing word bank from file: " + fileName);

        // Look for the file in exports/ first, then in the plugin data folder.
        // 文件名用户可控，两条候选路径都必须先经边界归一化，否则 "../" 会读到数据目录之外。
        File importFile = resolveInside(new File(plugin.getDataFolder(), "exports"), fileName);
        if (importFile == null || !importFile.exists()) importFile = resolveInside(plugin.getDataFolder(), fileName);
        // slice-08-security::F-08-S1 的 new_issues_found（round10 补修）：原实现此处只判
        // `importFile.exists()`，而上面两次 resolveInside **都可能返回 null**（文件名越界）。
        // null.exists() → NPE，管理员收到的是内部错误栈，而不是「路径不安全」。
        //
        // 修法不是「加个 null 检查就完了」——那样会把「路径被拒绝」折叠成
        // 「文件不存在」，管理员照样不知道真正原因。这里分开两条信息：
        //   · importFile == null  → 真的越界了，报 reason-unsafe-path（安全拦截成功）
        //   · !exists()           → 路径合法但文件不在，报 reason-file-not-found
        if (importFile == null) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-failed",
                    "reason", plugin.getMessageUtil().getSenderMessage(sender,
                            "wordbank.reason-unsafe-path", "file", fileName)));
            plugin.getLogger().warning("Admin: Import rejected - unsafe path: " + fileName);
            return;
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
        // 难度档 → 暂存区。循环期间<b>只</b>往这里累积，绝不改活 WordBank（见方法 javadoc）。
        // 用 LinkedHashMap 而非 HashMap：落盘失败日志里的档位顺序由此固定为「源文件出现顺序」，
        // 便于管理员对照源文件定位，也不会让同一次导入两次运行报出不同顺序的失败列表。
        Map<String, PendingBank> affectedBanks = new java.util.LinkedHashMap<>();

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
            if (resolveInside(new File(plugin.getDataFolder(), "words"), targetDifficulty + ".yml") == null) {
                errors++;
                plugin.getLogger().warning("Admin: Rejected unsafe difficulty '" + targetDifficulty + "'");
                continue;
            }
            // 08-logic-LG-01：先取本批次已建好的暂存区 —— 新难度在 wordManager 里恒为 null
            // （getWordBank 是纯 map 读），每轮重建会让本批已累积的词被丢掉。
            PendingBank pending = affectedBanks.get(targetDifficulty);
            if (pending == null) {
                // commit 目标：已存在的档位是活引用（getWordBank 返回 map 里的对象本身）；
                // 不存在的档位暂为 null，commit 阶段才按需新建 —— 落盘失败就一个对象都不留。
                pending = new PendingBank(targetDifficulty, wordManager.getWordBank(targetDifficulty));
                affectedBanks.put(targetDifficulty, pending);
            }

            // Check if word already exists in any WordBank (by english name, case-insensitive)
            // 08-logic-LG-01：新难度不在 getAvailableDifficulties 里，必须单独查在途暂存区。
            // 同时查暂存区里的词：同一批次内重复的同名词也要按 duplicate 跳过，
            // 否则会往暂存区塞两条，落盘后词库里出现重复词条。
            boolean exists = pending.contains(english);
            for (String diff : wordManager.getAvailableDifficulties()) {
                if (exists) break;
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

            // freq_rank：可选派生元数据。本导入循环**不经过** WordBank.loadWords
            // （自行拼 Word），所以必须在这里独立读一次，否则 export → import 会丢字段。
            // 归一规则不在此重复实现，统一交给 Word 构造器（见 Word.normalizeFreqRank），
            // 保证两条解析路径口径必然一致。
            int freqRank = Word.FREQ_RANK_UNKNOWN;
            Object freqObj = wordData.get("freq_rank");
            if (freqObj instanceof Number num) {
                freqRank = num.intValue();
            }

            Word word = new Word(english, chinese, partOfSpeech, phonetic,
                    exampleSentence, synonyms, antonyms,
                    targetDifficulty, difficultyLevel, reward, freqRank);
            // 关键：这里只是进暂存区，活 WordBank 一个词都没加（落盘成功后才 commit）。
            pending.words.add(word);
            imported++;
        }

        // ============================================================
        //  逐档「先落盘、后 commit」：写入失败的档位，内存与磁盘都保持原样
        // ============================================================
        // 每档独立成败：一档写失败不代表别的档也失败，必须逐档记录而不是「循环完就当全成」。
        List<String> failedDifficulties = new ArrayList<>(0);
        for (PendingBank pending : affectedBanks.values()) {
            // 落盘视图 = 活词库既有词 + 本批新词。对「档位尚不存在」的情况，基线是空词库。
            // 注意这里**没有**改 pending.existing：commit 前它必须保持落盘前的状态，
            // 否则落盘失败时无法证明「内存没变」。
            WordBank baseline = pending.existing != null
                    ? pending.existing : WordBank.empty(pending.difficulty);
            if (!saveWordBankToYaml(pending.difficulty, baseline.snapshotWith(pending.words))) {
                failedDifficulties.add(pending.difficulty);
                continue;
            }
            // 落盘成功 → 才 commit 到内存。addWord 只做 CopyOnWriteArrayList 追加，不会失败。
            pending.commit();
        }

        // commit 之后补索引（WordManagerImpl.globalWordIndex 是 getWord 的唯一来源）。
        indexImportedWords(wordManager, affectedBanks, failedDifficulties);

        if (failedDifficulties.isEmpty()) {
            // Log summary to console
            plugin.getLogger().info("Admin: Import complete. Imported " + imported
                    + " words, skipped " + skipped + " duplicates, " + errors + " errors.");

            // Send summary to sender
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-summary",
                    "imported", String.valueOf(imported),
                    "skipped", String.valueOf(skipped),
                    "errors", String.valueOf(errors));
            sender.sendMessage(msg);
            return;
        }

        // ============================================================
        //  落盘失败分支：绝不可以说 "Import complete"
        // ============================================================
        // 此刻失败档位的磁盘 words/*.yml 一个字节都没变，内存也<b>没有</b>含这些新词
        // （commit 只在落盘成功后执行）。因此失败档位的内存与磁盘是一致的 —— 管理员
        // 修好磁盘后直接重试导入即可，不需要重启，这些词也不会再被报成 duplicate。
        // 成功档位则内存与磁盘都已更新（各自一致），不需要任何补偿动作。
        String failedList = String.join(", ", failedDifficulties);
        // 只有「落盘成功的档位」里的词真正进了内存。{imported} 占位符在失败文案里
        // 语义是「已进内存的词数」，所以必须扣掉失败档位的词 —— 传总 imported 会让
        // 文案声称内存有词（实际没有），传错方向同样是谎报。
        int committedWordCount = imported;
        for (String failed : failedDifficulties) {
            PendingBank pending = affectedBanks.get(failed);
            if (pending != null) {
                committedWordCount -= pending.words.size();
            }
        }
        int failedWordCount = imported - committedWordCount;
        plugin.getLogger().severe("Admin: Import PERSIST FAILED for difficulty: " + failedList
                + ". " + failedWordCount + " word(s) could NOT be written to words/*.yml, and were"
                + " NOT added to the in-memory word bank either (memory and disk are both unchanged"
                + " for these difficulties). Disk may be full, the file may be locked/read-only, or"
                + " the directory permissions may deny writing. Fix the cause and run"
                + " /lea importwordbank again — no restart is needed, and these words will NOT be"
                + " reported as duplicates.");

        String failMsg = plugin.getMessageUtil().getSenderMessage(sender,
                "wordbank.import-persist-failed",
                "failed", failedList,
                "imported", String.valueOf(committedWordCount),
                "skipped", String.valueOf(skipped),
                "errors", String.valueOf(errors));
        sender.sendMessage(failMsg);
    }

    /**
     * 单档导入的暂存区：本批待加词 + commit 目标。
     *
     * <p>存在意义是把「解析」与「往活词库写」彻底隔开：解析阶段只碰 {@link #words}，
     * 活词库在落盘成功前保持落盘前的状态。<b>不要</b>在解析循环里调 {@link #commit}。
     */
    private static final class PendingBank {
        final String difficulty;
        /** commit 目标；{@code null} = 该难度档当前不存在，commit 时新建并注册。 */
        final WordBank existing;
        final List<Word> words = new ArrayList<>();

        PendingBank(String difficulty, WordBank existing) {
            this.difficulty = difficulty;
            this.existing = existing;
        }

        /** 暂存区里是否已有同名（大小写不敏感）的词。 */
        boolean contains(String english) {
            for (Word w : words) {
                if (w.getEnglish().equalsIgnoreCase(english)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 落盘成功后的内存提交。
         *
         * <p><b>已存在档位</b>：对活引用逐个 {@link WordBank#addWord}（原地 append）。
         * 用 append 而不是替换 {@code WordManagerImpl.wordBanks} 里的引用，是为了不拆散
         * 在途 {@code LearningSession} 已持有的 {@link Word} 对象，也避免为此引入一次
         * 跨模块的原子替换。
         *
         * <p><b>不存在档位</b>：不在此处注册 —— 注册与 {@code globalWordIndex} 补索引
         * 必须成对完成（只注册不补索引 = {@code getWord} 查不到新词；只补索引不注册 =
         * 玩家选不到这个难度）。两者统一由 {@link #indexImportedWords} 收口。
         */
        void commit() {
            if (existing == null) {
                return;
            }
            for (Word w : words) {
                existing.addWord(w);
            }
        }
    }

    /**
     * commit 之后把本次导入的词登记进 {@code WordManagerImpl}：
     * <ul>
     *   <li><b>已存在档位</b>：{@code commit()} 已把词 append 进活 {@link WordBank}，
     *       这里只补 {@code globalWordIndex}（{@code getWord} 的唯一来源）。</li>
     *   <li><b>新建档位</b>：创建词库对象、注册难度档、再补索引 —— 三件事在这里成对完成。</li>
     * </ul>
     *
     * <p>只处理<b>落盘成功</b>的档位：失败档位的词既不在磁盘也不该出现在内存索引里，
     * 否则 {@code getWord} 会返回一个「重启后就不存在」的词。
     *
     * @param failed 落盘失败的难度档（这些档位一律跳过）
     */
    private void indexImportedWords(WordManagerImpl wordManager,
                                    Map<String, PendingBank> affectedBanks,
                                    List<String> failed) {
        for (PendingBank pending : affectedBanks.values()) {
            if (failed.contains(pending.difficulty)) {
                continue;
            }
            WordBank target = pending.existing;
            if (target == null) {
                // 落盘成功但档位原本不存在：把已落盘的内容作为新档位注册。
                target = WordBank.empty(pending.difficulty);
                for (Word w : pending.words) {
                    target.addWord(w);
                }
                wordManager.registerWordBank(pending.difficulty, target);
            }
            for (Word w : pending.words) {
                wordManager.indexWord(w);
            }
        }
    }

    /**
     * 把词库落盘回 {@code words/<difficulty>.yml}（导入后回写 + 新建词库时回写）。
     *
     * <p><b>slice-10-dc-02：顶层 {@code difficulty} 键必须写。</b>本方法原先只写
     * {@code description} + {@code words[*]}，与 {@link #exportWordBank}（:277 写了
     * {@code config.set("difficulty", …)}）字段集**分叉**。后果是一条静默的往返断裂链：
     * 落盘文件失去顶层难度键 → 该文件仍能被 {@code WordManagerImpl} 按<b>文件名</b>加载
     * （所以看起来一切正常）→ 但再 {@link #importWordBank} 一次时，两级兜底
     * （先读每词 {@code wordData.get("difficulty")}、再读顶层 {@code config.getString("difficulty")}）
     * 双双为 null → 逐词 {@code No difficulty} 跳过 → {@code Imported 0 words}。
     *
     * <p><b>为什么写顶层而不是每词键</b>：导入侧的兜底链本来就是「每词优先、顶层兜底」。
     * 只写每词键也能被读到，但 1000+ 词的词库里每个词都多一个完全相同的重复键，人工编辑
     * 的 YAML 立刻变噪音；顶层一个键语义也更强 —— 一个文件就是一个难度档（文件名与
     * {@code WordManagerImpl.loadAllWordBanks} 的档位来源都是它）。
     *
     * <p><b>取值为什么用入参 {@code difficulty} 而不是 {@code bank.getDifficulty()}</b>：
     * 落盘目标文件名就是 {@code difficulty + ".yml"}，而该文件下次被加载时难度又是由
     * <b>文件名</b>反推给 {@code new WordBank(name, config)} 的。用入参写顶层键，保证
     * 「文件内记录的难度 == 文件名 == 下次加载得到的难度」三者恒等。唯一调用方
     * （见 {@code importWordBank} 末段的 {@code affectedBanks} 循环）传的正是
     * {@code targetDifficulty}，而 bank 也是用同一个字符串构造的，因此本取值与
     * {@code exportWordBank} 的 {@code bank.getDifficulty()} 在该路径上必然相等 ——
     * 两条导出路径的产物字段集与取值口径一致（这正是原缺陷的根因形态：两份代码各写各的）。
     *
     * @return {@code true} 已落盘；{@code false} 目标路径被拒绝（越界难度）或
     *         {@link com.letslearnenglish.minecraftplugin.config.ConfigManager#saveConfig}
     *         写失败。唯一调用方 {@link #importWordBank} 必须检查它 —— 这是「落盘失败却报
     *         导入完成」缺陷的修复点。
     */
    private boolean saveWordBankToYaml(String difficulty, WordBank bank) {
        FileConfiguration config = new YamlConfiguration();
        // 与 exportWordBank 的字段集逐键对齐：顶层 difficulty 在前、description 紧随其后。
        // ConfigManager.saveConfig 是纯覆写（config.save(file)，无 merge），所以少写一个键
        // 就是真丢一个键，不存在「旧文件里的值被保留」这个替代解释。
        config.set("difficulty", difficulty);
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
            // freq_rank 是可选派生元数据，约定值 0（未知）时不写键 —— 与上面 reward
            // 「默认值不落盘」同一取舍：1025 词里 530 个没有排名，写出 530 个冗余的
            // "freq_rank: 0" 只会让人工编辑的 YAML 变噪音。有排名则**必须**写，否则
            // export → import 一趟就把频次数据静默抹掉（静默数据丢失比报错难查得多）。
            if (w.hasFreqRank()) {
                wordData.put("freq_rank", w.getFreqRank());
            }
            wordList.add(wordData);
        }
        config.set("words", wordList);

        // 双保险：污点源已拒绝越界 difficulty，此处再守 sink（ConfigManager.saveConfig 无归一化）
        File target = resolveInside(new File(plugin.getDataFolder(), "words"), difficulty + ".yml");
        if (target == null) {
            plugin.getLogger().warning("Admin: Refused unsafe difficulty: " + difficulty);
            return false;
        }
        return plugin.getConfigManager().saveConfig(config, "words/" + target.getName());
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

    /**
     * {@code /lea class create <班名>}：长度/非空/空格校验 → 异步建班。
     *
     * <p><b>R-18：按三态分支，不再把 SQL 故障说成重名。</b>{@code createClassDetailed}
     * 的三个结局各有一条提示：{@code CREATED} → 已创建；{@code ALREADY_EXISTS} →
     * 已存在同名班级（正常幂等拒绝，不用查日志）；{@code FAILED} → 创建失败、请查控制台。
     * 改回 boolean 即会把第三档折叠进第二档（管理员被指向"那个同名的班"，
     * 而那个班并不存在，真正的现场在日志里）。
     */
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
            switch (db.createClassDetailed(className, createdBy, now)) {
                case CREATED ->
                        sendOnMain(sender, "class.created", "class", className);
                case ALREADY_EXISTS ->
                        sendOnMain(sender, "class.name-exists");
                case FAILED ->
                        // 与 ALREADY_EXISTS 分开：管理员必须去查日志，且不得以为班已存在
                        sendOnMain(sender, "class.create-failed", "class", className);
                default ->
                        // 枚举非封闭：新增常量即会落到这里。兜底必须报错，不得报成功
                        sendOnMain(sender, "class.create-failed", "class", className);
            }
        });
    }

    /**
     * {@code /lea class delete <班名>}：按三态分支。
     *
     * <p><b>R-18：三档提示各司其职。</b>改造前 {@code deleteClass} 的 {@code false}
     * 同时表示"班不存在"与两种 SQL 故障，三条都提示 {@code class.not-found}
     * （"找不到班级"）。于是数据库故障时管理员会去核对班名 —— 方向完全错了，
     * 而真正的现场（{@code DatabaseManager} 那条 "rolled back" WARNING）没人打开。
     * 现在：{@code DELETED} → 已删除；{@code NOT_FOUND} → 找不到班级（正常，不查日志）；
     * {@code FAILED} → 删除失败、已回滚、请查控制台。
     *
     * <p>注：{@code FAILED} 的文案必须说"没有删除任何数据"—— 因为本次改动把两条 DELETE
     * 收进了同一事务，失败即整批 rollback。若只说"失败"，管理员会以为至少成员被移出了。
     */
    private void handleClassDelete(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender, "class.usage"));
            return;
        }
        String className = args[2];
        DatabaseManager db = plugin.getDatabaseManager();
        runClassAsync(() -> {
            switch (db.deleteClassDetailed(className)) {
                case DELETED ->
                        sendOnMain(sender, "class.deleted", "class", className);
                case NOT_FOUND ->
                        sendOnMain(sender, "class.not-found", "name", className);
                case FAILED ->
                        // 不得提示 not-found：那是"班名打错了"，而这里是数据库故障
                        sendOnMain(sender, "class.delete-failed", "class", className);
                default ->
                        // 枚举非封闭：新增常量即会落到这里。兜底必须报错，不得报成功
                        sendOnMain(sender, "class.delete-failed", "class", className);
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
        int maxMembers = resolveClassMaxMembers();
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
     * R-16：{@code class-system.max-members} 的<b>唯一读取点</b>（此前裸读散落在
     * {@code handleClassAdd} 内，且是全仓唯一一个不钳位的数值配置）。
     *
     * <p><b>原缺陷</b>：{@link ClassRepository#addMemberAtomic} 用
     * {@code getClassMemberCount(classId) >= maxMembers} 判满员。配置写成 {@code 0} 或负数时，
     * 空班的 {@code COUNT(*)} = 0 也满足 {@code 0 >= 0}，于是<b>任何 add 都返回 FULL</b> ——
     * 管理员对着一个空班收到"班级人数已满（上限 0 人）"，且没有任何配置错误提示。
     *
     * <p><b>为什么选"钳到 1"而不是"0 = 不限"</b>（诚实交代，别当成最优解）：
     * 全仓另三个上限（{@code word-system.daily-limit}、{@code money.daily-cap}、
     * {@code ai-chat.daily-round-limit} / {@code opening-daily-cap}）的"≤0 = 不限"是
     * <b>调用侧</b>实现的（{@code dailyLimit > 0 && ...} / {@code cap > 0 && ...} /
     * {@code if (dailyCap <= 0) return amount;}），语义不在被调方。而这里的 {@code >=} 比较
     * 在 {@code ClassRepository} 内部，调用侧无法表达"不限"：传 {@code Integer.MAX_VALUE}
     * 只是"近似不限"，一旦某个班真达到 MAX_VALUE 行就会静默变成满员；
     * 而且 {@link #applyClassAdd} 的 {@code class.member-full} 文案会把 {@code 2147483647}
     * 原样渲染给管理员（"上限 2147483647 人"）。
     * <u>干净的做法是让 {@code ClassRepository} 自己跳过 {@code maxMembers <= 0} 的判定</u>，
     * 但该文件不在本批授权写入范围内 —— 故此处取"安全但不优雅"的一侧：
     * <b>把非正值钳到 1 并记 WARNING</b>，让 {@code 0} 明确等于"1 人班"而非"永远满"，
     * 管理员能从日志看出配置写错了。
     *
     * <p><b>为什么不给 {@code ClassRepository} 传哨兵</b>：{@code 0} 会在
     * {@code getClassMemberCount(...) >= 0} 处返回 FULL（与"不限"正好相反），
     * 而 "<=0 = 不限" 的判定完全在被调方 —— 传什么值都绕不开 {@code >=}，
     * 唯一能在调用侧表达的上界就是 {@code Integer.MAX_VALUE}（损失见上）。
     *
     * <p><b>钳制行为表</b>（{@code maxMembers} = 返回给 {@code addMemberAtomic} 的值）：
     * <ul>
     *   <li>{@code 0}    → 钳成 {@code 1}，WARNING。行为：空班可加 1 人，第 2 人起 FULL。</li>
     *   <li>{@code -5}   → 钳成 {@code 1}，WARNING。同上（负值无任何合理解释，必定是手误）。</li>
     *   <li>{@code 50}   → 原样 {@code 50}，无日志。行为不变（默认值）。</li>
     *   <li>天文数字（如 {@code 2000000000}）→ 原样透传，无日志，实际等于不限
     *       （SQLite 整表也到不了 20 亿行），且不会溢出 —— 钳只向上，不做上界截断。</li>
     *   <li>键缺失 / 非数字 → {@code getInt} 返回默认 {@code 50}。</li>
     * </ul>
     *
     * <p><b>线程</b>：只在主线程（{@code handleClassAdd} 的门与参数校验段）调用一次，
     * 之后值随 lambda 捕获进异步段 —— 与原有数据流一致（原实现也是主线程读、异步用）。
     *
     * @return 保证 {@code >= 1} 的成员上限
     */
    private int resolveClassMaxMembers() {
        int configured = plugin.getConfigManager().getMainConfig()
                .getInt("class-system.max-members", 50);
        if (configured >= 1) {
            return configured;
        }
        // 非正值必是配置手误：钳到 1 并明确报出来（否则管理员只会看到"人数已满（上限 0 人）"，
        // 完全无法定位到 config.yml）。
        plugin.getLogger().warning("class-system.max-members = " + configured
                + " is not a valid member cap (must be >= 1); clamped to 1."
                + " With the raw value every /lea class add would be rejected as FULL."
                + " Please fix config.yml — an unlimited class is NOT expressible by this key.");
        return 1;
    }

    /**
     * 成员行变更（异步线程执行）：一人一班编排 —— 已在本班幂等回 member-added；
     * 换班先判新班满员（满则拒绝，防丢班）再退旧班进新班。
     *
     * <p>R6-F：四条语句的原子段（查当前班 → 判同班 → 判满员 → 退旧班 + 进新班）
     * 已整体下沉到 {@link ClassRepository#addMemberAtomic}，本方法<b>不再持有</b>
     * {@code connectionLock} —— {@code data/} 之外对连接锁的引用归零。这里只做
     * "按结果投递消息"，且投递仍在锁外（R9-L5：锁内零消息投递）。
     */
    private void applyClassAdd(org.bukkit.command.CommandSender sender,
                               DatabaseManager.ClassRow classRow, UUID playerId,
                               String playerName, int maxMembers) {
        ClassRepository.AddResult result = classRepository.addMemberAtomic(
                classRow.classId(), playerId, System.currentTimeMillis(), maxMembers);
        switch (result) {
            case ALREADY_MEMBER:
            case ADDED:
                // 幂等提示与首次加入共用 member-added（冻结键无"重复加入"专键）
                sendOnMain(sender, "class.member-added",
                        "player", playerName, "class", classRow.className());
                break;
            case FULL:
                sendOnMain(sender, "class.member-full", "max", String.valueOf(maxMembers));
                break;
            case REASSIGNED:
                sendOnMain(sender, "class.reassigned",
                        "player", playerName, "class", classRow.className());
                break;
            case WRITE_FAILED:
                // 写入未生效且库内状态已回到调用前 —— 不得报成功
                sendOnMain(sender, "class.member-write-failed",
                        "player", playerName, "class", classRow.className());
                break;
            default:
                // 枚举非封闭：新增常量即会落到这里。兜底必须报错，不得报成功
                sendOnMain(sender, "class.member-write-failed",
                        "player", playerName, "class", classRow.className());
                break;
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
        } catch (IllegalPluginAccessException | IllegalStateException e) {
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
        } catch (IllegalPluginAccessException | IllegalStateException e) {
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
