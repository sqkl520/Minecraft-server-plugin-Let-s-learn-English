package com.letslearnenglish.minecraftplugin.core.word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.FileConfiguration;

public class WordBank {

    private final String difficulty;
    private final String description;
    private final List<Word> words;
    private final Random random;

    private volatile Set<String> cachedMasteredKey;
    private volatile List<Word> cachedUnmastered;
    private volatile List<Word> cachedMastered;

    public WordBank(String difficulty, FileConfiguration config) {
        this.difficulty = difficulty;
        this.description = config.getString("description", "");
        this.words = new ArrayList<>();
        this.random = new Random();

        loadWords(config);
    }

    private void loadWords(FileConfiguration config) {
        List<?> rawList = config.getList("words");
        if (rawList == null) {
            return;
        }

        int index = 0;
        for (Object item : rawList) {
            String entryId = difficulty + ".yml words[" + index + "]";
            index++;
            if (!(item instanceof Map)) {
                warnSkip(entryId, "词条不是 Map（实际类型="
                        + (item == null ? "null" : item.getClass().getSimpleName()) + "）");
                continue;
            }
            // Build a type-checked Map<String, Object> from the raw YAML map
            Map<String, Object> wordData = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (entry.getKey() instanceof String key) {
                    wordData.put(key, entry.getValue());
                }
            }
            // R-06：english/chinese 是判分与渲染的必填键，坏一条跳过一条，
            // 绝不 new Word(null, …) 把 NPE 炸弹埋进词库。
            String english = asStringOrNull(wordData.get("english"));
            if (english == null || english.trim().isEmpty()) {
                warnSkip(entryId, describeBadField(wordData.get("english"), "english"));
                continue;
            }
            String chinese = asStringOrNull(wordData.get("chinese"));
            if (chinese == null || chinese.trim().isEmpty()) {
                warnSkip(entryId, describeBadField(wordData.get("chinese"), "chinese"));
                continue;
            }
            String partOfSpeech = asStringOrNull(wordData.get("part_of_speech"));
            String phonetic = asStringOrNull(wordData.get("phonetic"));
            String exampleSentence = asStringOrNull(wordData.get("example_sentence"));

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

            // freq_rank：NGSL 1.2 频次排名（1 = 最常用），**可选字段**。
            // 缺省 = Word.FREQ_RANK_UNKNOWN(0) 是正常数据：1025 词里约 530 个
            // （apple / banana / pencil 这类具象名词）本来就不在通用频次表里，
            // 所以**绝不能**把它加进上面的 english/chinese 必填校验，也**绝不能**
            // 加进 WordBankValidator 的报错列表 —— 否则一次正常导入会刷出 500+ 条假错误。
            // 非 Number（字符串 "42"、null、List）按未知处理而非抛异常：YAML 手写
            // 把数字加引号是常态，宽容降级比炸掉整档加载划算（与 asStringOrNull 同一取舍）。
            // 注意：Number 分支接受 double 并截断（42.7 → 42）—— 频次排名只有整数语义，
            // YAML 里写 42.0 的常见成因是导出工具把整数序列化成了浮点，截断是正确还原。
            // 这里只负责「把数字读出来」，负数/超界的归一统一由 Word 构造器做
            // （见 Word.normalizeFreqRank），保证本处与 /lea importwordbank 两条路径口径一致。
            int freqRank = Word.FREQ_RANK_UNKNOWN;
            Object freqObj = wordData.get("freq_rank");
            if (freqObj instanceof Number num) {
                freqRank = num.intValue();
            }

            Word word = new Word(english, chinese, partOfSpeech, phonetic,
                    exampleSentence, synonyms, antonyms, difficulty, difficultyLevel, reward,
                    freqRank);
            words.add(word);
        }
    }

    /**
     * 宽容取 String：非 String 按 null 处理（YAML 用户手写时 number/boolean 误入字符串
     * 字段是常态，直接强转会抛 ClassCastException 炸掉整档加载）。
     */
    private static String asStringOrNull(Object value) {
        return value instanceof String str ? str : null;
    }

    /**
     * 描述坏字段的诊断串：缺失 / 非 String（含实际类型）/ 空白，三选一。
     *
     * <p><b>只服务于必填键</b>（english / chinese）。{@code freq_rank} 这类可选派生
     * 元数据<b>刻意不接入本方法</b>，也不接入 {@link WordBankValidator}：词库 1025 词里
     * 约 530 个本来就没有 NGSL 排名，「缺失」是正常数据而非缺陷，接进来只会让每次
     * 正常加载/导入刷出 500+ 条假错误。同理不要给它加类型校验（写错类型按未知降级即可，
     * 见 {@link #loadWords} 里的解析注释）。
     */
    private static String describeBadField(Object value, String field) {
        if (value == null) {
            return "缺少必填字段 '" + field + "'";
        }
        if (!(value instanceof String)) {
            return "字段 '" + field + "' 不是 String（实际类型=" + value.getClass().getSimpleName() + "）";
        }
        return "字段 '" + field + "' 为空或空白";
    }

    /**
     * R-06 跳过日志：统一走 Bukkit 日志器（WordBank 无 plugin 引用，与现有本类唯一
     * 日志调用点风格一致）。
     */
    private static void warnSkip(String entryId, String reason) {
        org.bukkit.Bukkit.getLogger().warning(
                "[LetsLearnEnglish] 跳过坏词条 " + entryId + "： " + reason + "，已跳过不加载。");
    }

    /**
     * 旧 2 参入口（无近期已答词排除）。
     *
     * <p>B2b（R3-b）：实现整体迁入 3 参重载，本方法只补一个空排除集 ——
     * 既保证「排除集为空 == 改造前行为」是<b>同一条代码路径</b>（而不是两套
     * 需要人工对照的等价实现），也让 {@code cachedMasteredKey} 分桶逻辑保持单一真相。
     */
    public List<Word> selectWordsForSession(int count, Set<String> masteredWords) {
        return selectWordsForSession(count, masteredWords, Set.of());
    }

    /**
     * B2b（R3-b）：带「近期已答词」排除的开局抽词。
     *
     * <p><b>排除语义</b>：{@code excluded} 里的词（大小写不敏感，内部统一
     * {@code toLowerCase} 归一 —— 与 {@code masteredWords} 的键口径同源）在<b>每次调用时</b>
     * 对候选池做一次过滤。开销 O(n)，每会话一次，词库 n ≤ 1000，可忽略。
     *
     * <p><b>缓存</b>：{@code cachedMasteredKey} 分桶逻辑与改造前逐字一致，且
     * <b>不</b>把 {@code excluded} 掺进缓存键 —— 缓存只按「已掌握集合」分桶，
     * 排除集每次调用现算，所以不会因为窗口滑动而产生脏缓存。
     *
     * <p><b>降级链（绝不返回空 / 绝不少于 {@code min(count, 词库总数)}）</b>：
     * ① 未掌握且非排除 → ② 未掌握（被排除也收）→ ③ 已掌握且非排除 → ④ 已掌握（被排除也收）。
     * 四级池两两互斥（未掌握/已掌握按 english 划分，排除/非排除是同一集合的二分），
     * 并集恰好是整个词库，因此只要 {@code count ≤ 词库总数} 就一定能凑满。
     *
     * <p>为什么降级链要把「被排除的未掌握词」排在「未排除的已掌握词」之前：
     * 排除的目的是「别马上重出同一题」，而不是「宁可给已掌握的词」。当未掌握池被排除光时，
     * 重复一道刚答过的未掌握词仍比抽一道玩家早已掌握的词更有学习价值；而
     * {@code WordManagerImpl.startSession} 有 {@code isEmpty → IllegalStateException}
     * 的硬约定，所以最后两档必须兜住「词库全被排除」这种极端输入。
     */
    public List<Word> selectWordsForSession(int count, Set<String> masteredWords,
                                            Set<String> excluded) {
        List<Word> unmastered;
        List<Word> mastered;

        if (cachedMasteredKey != null && cachedMasteredKey.equals(masteredWords)
                && cachedUnmastered != null && cachedMastered != null) {
            unmastered = cachedUnmastered;
            mastered = cachedMastered;
        } else {
            unmastered = new ArrayList<>();
            mastered = new ArrayList<>();
            for (Word w : words) {
                if (masteredWords.contains(w.getEnglish().toLowerCase())) {
                    mastered.add(w);
                } else {
                    unmastered.add(w);
                }
            }
            cachedUnmastered = unmastered;
            cachedMastered = mastered;
            cachedMasteredKey = masteredWords;
        }

        // count ≤ 0 → 空结果（与旧实现 count=0 的 subList(0,0) 一致，且不会因负数抛异常）。
        // count > 词库总数 → 词库全量；两级池并集就是整个词库，循环自然凑满。
        int want = Math.max(0, Math.min(count, words.size()));
        if (want == 0) {
            return new ArrayList<>();
        }

        // 大小写归一：词库侧用 w.getEnglish().toLowerCase() 作键（与分桶、dirtyWords 同口径），
        // 排除集来自 PlayerDataStore（写入时已 toLowerCase），但调用方可能传原始大小写，
        // 这里统一归一 —— "Book" 与词库里的 "book" 视为同一个词。
        Set<String> excludedLower = new HashSet<>();
        if (excluded != null) {
            for (String w : excluded) {
                if (w != null && !w.isEmpty()) {
                    excludedLower.add(w.toLowerCase());
                }
            }
        }

        List<Word> tierUnmasteredClean = new ArrayList<>();
        List<Word> tierUnmasteredExcluded = new ArrayList<>();
        for (Word w : unmastered) {
            if (excludedLower.contains(w.getEnglish().toLowerCase())) {
                tierUnmasteredExcluded.add(w);
            } else {
                tierUnmasteredClean.add(w);
            }
        }
        List<Word> tierMasteredClean = new ArrayList<>();
        List<Word> tierMasteredExcluded = new ArrayList<>();
        for (Word w : mastered) {
            if (excludedLower.contains(w.getEnglish().toLowerCase())) {
                tierMasteredExcluded.add(w);
            } else {
                tierMasteredClean.add(w);
            }
        }

        // 每档独立打乱后按优先级依次取：与旧实现「先洗未掌握、再洗已掌握、最后整体再洗」
        // 的随机性质量相同，只是多了两档只在降级时才有元素的池。
        Collections.shuffle(tierUnmasteredClean, random);
        Collections.shuffle(tierUnmasteredExcluded, random);
        Collections.shuffle(tierMasteredClean, random);
        Collections.shuffle(tierMasteredExcluded, random);

        List<Word> selected = new ArrayList<>(want);
        for (List<Word> tier : List.of(tierUnmasteredClean, tierUnmasteredExcluded,
                tierMasteredClean, tierMasteredExcluded)) {
            for (Word w : tier) {
                if (selected.size() >= want) {
                    break;
                }
                selected.add(w);
            }
            if (selected.size() >= want) {
                break;
            }
        }

        Collections.shuffle(selected, random);
        return selected;
    }

    public Word getWord(String english) {
        return words.stream()
                .filter(w -> w.getEnglish().equalsIgnoreCase(english))
                .findFirst()
                .orElse(null);
    }

    public List<Word> getRandomWords(int count, Word exclude) {
        List<Word> pool = words.stream()
                .filter(w -> !w.equals(exclude))
                .collect(Collectors.toList());
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    public void addWord(Word word) {
        words.add(word);
        invalidateCache();
    }

    private void invalidateCache() {
        cachedMasteredKey = null;
        cachedUnmastered = null;
        cachedMastered = null;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getDescription() {
        return description;
    }

    public int getWordCount() {
        return words.size();
    }

    /**
     * 全部单词的只读快照。
     *
     * <p>F4-03 先例 + 中危修复批 arch tester O-3 收口：原返回 {@code unmodifiableList}
     * **视图**（底层是裸 ArrayList），F2-11 后 {@link #getAllWords()} 的消费点首次
     * 进入异步线程（DailyChallengeManager.ensureWordChallengesAssigned 经午夜任务），
     * 与主线程 {@link #addWord}（/lea addword 导入）并发时理论可 CME。返回防御拷贝
     * （单词对象本身不可变，浅拷贝足够）——读侧任意线程安全。
     */
    public List<Word> getAllWords() {
        return List.copyOf(words);
    }
}