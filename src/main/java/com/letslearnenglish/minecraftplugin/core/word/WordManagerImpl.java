package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Word Manager Implementation
 *
 * Responsible for:
 * - Word bank loading and management
 * - Multiple learning mode scheduling
 * - Player word mastery tracking
 * - Spaced repetition review scheduling
 */
public class WordManagerImpl {

    private static final Set<String> VALID_MODES = Set.of("spelling", "choice", "fill_blank");

    /**
     * F3-03：难度语义序优先级表（内置档位）。未列出的键（自定义词库）优先级 100
     * 排在其后，同优先级按字母序。
     */
    private static final Map<String, Integer> DIFFICULTY_ORDER = Map.of(
            "beginner", 0,
            "intermediate", 1,
            "advanced", 2);

    private final LetsLearnEnglish plugin;
    private final ConfigManager configManager;
    private final PlayerDataStore playerDataStore;
    private final ReviewScheduler reviewScheduler;

    private final Map<String, WordBank> wordBanks;
    private final Map<UUID, LearningSession> activeSessions;
    private final Map<String, Word> globalWordIndex;

    public WordManagerImpl(LetsLearnEnglish plugin, ConfigManager configManager,
                           PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.playerDataStore = playerDataStore;
        this.reviewScheduler = new ReviewScheduler(plugin, this, playerDataStore);
        this.wordBanks = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.globalWordIndex = new ConcurrentHashMap<>();

        loadAllWordBanks();
    }

    public void loadAllWordBanks() {
        wordBanks.clear();
        globalWordIndex.clear();

        File wordsDir = new File(plugin.getDataFolder(), "words");
        if (!wordsDir.exists() || !wordsDir.isDirectory()) {
            plugin.getLogger().warning("Words directory not found: " + wordsDir.getAbsolutePath());
            return;
        }

        File[] yamlFiles = wordsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (yamlFiles == null || yamlFiles.length == 0) {
            plugin.getLogger().warning("No .yml word bank files found in words directory.");
            return;
        }

        for (File file : yamlFiles) {
            String name = file.getName().replace(".yml", "");
            FileConfiguration config = configManager.loadWordBank(name);
            WordBank bank = new WordBank(name, config);
            // R-06 降级：校验后 0 有效词的词库整档跳过不注册 —— 该难度不可选
            // （玩家输 /le word <坏档> 走既有 "Unknown difficulty" 错误提示，开局不炸服）。
            // 不做跨档 fallback：难度对玩家是显式契约，静默换档等于偷换答题内容。
            if (bank.getWordCount() == 0) {
                plugin.getLogger().severe("Word bank '" + name
                        + "' has 0 valid words after validation (check warnings above for "
                        + name + ".yml): skipping registration, difficulty '" + name
                        + "' will be unavailable.");
                continue;
            }
            wordBanks.put(name, bank);
            for (Word w : bank.getAllWords()) {
                globalWordIndex.put(w.getEnglish().toLowerCase(), w);
            }
            plugin.getLogger().info("Loaded word bank: " + name
                    + " (" + bank.getWordCount() + " words)");
        }

        // R-06：全档皆空 = 所有学习/挑战/复习入口都会落空，开服即告警但不断启动流程。
        if (wordBanks.isEmpty()) {
            plugin.getLogger().severe("No usable word bank loaded (all files empty or "
                    + "failed validation). Learning, review and daily word challenges "
                    + "are unavailable until the words/*.yml files are fixed.");
        }
    }

    /**
     * Start a new learning session for a player.
     *
     * @param player     the player
     * @param difficulty difficulty level
     * @param mode       learning mode (spelling/choice/fill_blank)
     * @return the learning session
     */
    public LearningSession startSession(Player player, String difficulty, String mode) {
        return startSession(player, difficulty, mode,
                configManager.getMainConfig().getInt("word-system.words-per-session", 10));
    }

    /**
     * Phase 3 onboarding（plan-onboarding §3.3）：题数参数化重载 —— 教程迷你会话
     * 传 onboarding.session-words（3）。既有 3 参版委托本方法取 words-per-session，
     * 行为零变化（仅提取参数）。
     *
     * @param wordCount 本次会话题数（调用方保证 ≥1）
     */
    public LearningSession startSession(Player player, String difficulty, String mode, int wordCount) {
        WordBank bank = wordBanks.get(difficulty);
        if (bank == null) {
            throw new IllegalArgumentException("Unknown difficulty level: " + difficulty);
        }

        if (!VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException("Unknown learning mode: " + mode
                    + ". Valid modes: " + VALID_MODES);
        }

        Set<String> masteredWords = playerDataStore.getMasteredWords(player.getUniqueId());

        // B2b（R3-b）：近期已答词排除 —— 开新学习会话时跳过窗口内刚答过的词，
        // 避免「刚答错就重抽同一题」。配置 0 = 关闭（getRecentlyAnsweredWords 收到
        // <= 0 直接返回空集，所以这里不做条件分支，保持单一取值路径）。
        // 只作用于普通学习会话；复习会话（startReviewSession）刻意不排除 —— 复习就是要重考。
        Set<String> excluded = playerDataStore.getRecentlyAnsweredWords(
                player.getUniqueId(),
                configManager.getMainConfig()
                        .getInt("word-system.recent-word-exclusion-minutes", 30) * 60_000L);
        List<Word> sessionWords = bank.selectWordsForSession(wordCount, masteredWords, excluded);

        if (sessionWords.isEmpty()) {
            throw new IllegalStateException("No words available for session in difficulty: " + difficulty);
        }

        LearningSession session = new LearningSession(player.getUniqueId(), difficulty, mode, sessionWords);
        activeSessions.put(player.getUniqueId(), session);
        // R13-L2：公共 API isPlayerLearning 的真相标记（原 setPlayerInSession 零调用，
        // activeSessionPlayers 恒空 → API 恒 false）。startSession/endSession 成对维护。
        plugin.getPlayerDataStore().setPlayerInSession(player.getUniqueId(), true);
        return session;
    }

    /**
     * End a player's learning session.
     *
     * <p>结算与调用来源无关：正常答完（buildResult）、手动停止（stopQuiz）、下线
     * （endQuiz）三条路径都走这里 —— 落掌握度、累计学习分与会话数、写复习种子。
     * R-59 校正：下线结算属既定裁决（rage quit 不豁免，session 与分数照记），
     * 区别只在调用方是否继续发"走完会话才有"的金币奖励/庆祝。
     *
     * <p>H8：结算时把会话学习分累加入 PlayerStats.totalScore（学习分口径，
     * 与金币 total_coins_* 完全分离）。复习结算只对答对词 recordReview 晋级，
     * 答错词 resetReview 回到 1h 间隔；普通学习结算对答过的词写复习种子。
     */
    public void endSession(UUID playerId) {
        LearningSession session = activeSessions.remove(playerId);
        // R13-L2：与 startSession/startReviewSession 的置位成对（清理完成处；
        // 无会话时调用亦安全——幂等清位，防 API 卡在 true）。
        playerDataStore.setPlayerInSession(playerId, false);
        if (session != null) {
            playerDataStore.updateWordProgress(playerId, session.getResults());
            playerDataStore.getPlayerStats(playerId).addScore(session.getScore());
            // R-42：结算时增量同步内存榜（updateScore 语义为 merge-累加，此处传会话增量）。
            // 没有这一行，内存榜只在启动时 loadFromDB 快照一次，%lle_rank% 永远冻结在进服时刻。
            plugin.getProgressManager().getLeaderboard().updateScore(playerId, session.getScore());

            if ("review".equals(session.getMode())) {
                for (LearningSession.WordResult result : session.getResults().values()) {
                    String english = result.getWord().getEnglish();
                    // 本次复习未作答的词（correct+incorrect=0，例如中途 stop）不碰间隔
                    if (result.getCorrectCount() + result.getIncorrectCount() == 0) {
                        continue;
                    }
                    if (result.getCorrectCount() > 0 && result.getIncorrectCount() == 0) {
                        reviewScheduler.recordReview(playerId, english);
                    } else {
                        reviewScheduler.resetReview(playerId, english);
                    }
                }
            } else {
                for (LearningSession.WordResult result : session.getResults().values()) {
                    // 只有实际答过的词才碰复习状态：未作答的词（stop 残留）不进复习池
                    if (result.getCorrectCount() + result.getIncorrectCount() == 0) {
                        continue;
                    }
                    if (result.getIncorrectCount() > 0) {
                        // B2b（R3-c）：普通会话答错也必须把间隔打回起点。此前普通分支只调
                        // seedReview，而 seedReview 对已有种子的词是 no-op
                        // （PlayerDataStore 的 seedReview 在 lastReviewTime 已存在时直接 return），
                        // 所以"学过的词答错后复习间隔分毫不动"，下次提醒仍按旧的长间隔走。
                        // resetReview：lastReviewTime=now、reviewCount=0 → 下次 due = now + intervals[0]。
                        reviewScheduler.resetReview(playerId, result.getWord().getEnglish());
                    } else {
                        reviewScheduler.seedReview(playerId, result.getWord().getEnglish());
                    }
                }
            }
        }
    }

    /**
     * Look up a word by its English name across all word banks.
     * Returns null if the word is not found in any bank.
     */
    public Word getWord(String english) {
        return globalWordIndex.get(english.toLowerCase());
    }

    /**
     * Get the word bank for a specific difficulty.
     */
    public WordBank getWordBank(String difficulty) {
        return wordBanks.get(difficulty);
    }

    /**
     * Get all available difficulty levels.
     *
     * R-09c: 返回排好序的稳定快照 —— 底层是 ConcurrentHashMap，直接包 keySet
     * 会让 GUI 构造快照与点击侧重取两次迭代出不同顺序（词库点 A 开 B）。
     * 排序后两侧恒一致；命令/挑战等只读遍历方不受影响。
     *
     * F3-03：排序从纯字母序改为语义序（beginner → intermediate → advanced，
     * 自定义词库按字母序排其后）——字母序会把 advanced 排最前，词库选择界面
     * 首屏全是高阶档，与"由易到难"的学习动线相反。返回类型保持 Set
     * （LinkedHashSet 保序），WordBankSelectGUI 渲染与 MiscGUIListener 点击
     * 下标映射按此顺序自动传导，两侧依旧同序。
     */
    public Set<String> getAvailableDifficulties() {
        List<String> sorted = new ArrayList<>(wordBanks.keySet());
        sorted.sort(Comparator
                .comparingInt((String name) -> DIFFICULTY_ORDER.getOrDefault(
                        name.toLowerCase(Locale.ROOT), 100))
                .thenComparing(Comparator.<String>naturalOrder()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    public ReviewScheduler getReviewScheduler() {
        return reviewScheduler;
    }

    /**
     * Start a review session using words that are due for review.
     *
     * @param player the player
     * @return the review session, or null if no words are due
     */
    public LearningSession startReviewSession(Player player) {
        List<String> dueWords = reviewScheduler.getDueReviewWords(player.getUniqueId());
        if (dueWords.isEmpty()) {
            return null;
        }

        List<Word> reviewWords = new ArrayList<>(Math.min(dueWords.size(),
                configManager.getMainConfig().getInt("word-system.words-per-session", 10)));
        for (String english : dueWords) {
            Word word = globalWordIndex.get(english.toLowerCase());
            if (word != null) {
                reviewWords.add(word);
            }
        }

        if (reviewWords.isEmpty()) {
            return null;
        }

        int limit = configManager.getMainConfig().getInt("word-system.words-per-session", 10);
        if (reviewWords.size() > limit) {
            reviewWords = reviewWords.subList(0, limit);
        }

        LearningSession session = new LearningSession(player.getUniqueId(), "review", "review", reviewWords);
        activeSessions.put(player.getUniqueId(), session);
        // R13-L2：复习会话同走 endSession 收尾，开局侧成对置位（否则 API 在复习中恒 false）。
        plugin.getPlayerDataStore().setPlayerInSession(player.getUniqueId(), true);
        return session;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }
}