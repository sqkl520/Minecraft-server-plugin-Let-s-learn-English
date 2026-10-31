package com.letslearnenglish.minecraftplugin.core.word;

import java.util.List;
import java.util.Objects;

/**
 * Word entity class
 *
 * Represents a single word entry in the word bank, containing all
 * learning-related information for the word.
 */
public class Word {

    /**
     * freqRank 的「未知」哨兵：<b>0 表示该词不在 NGSL 1.2 频次表内</b>。
     *
     * <p>刻意用 0 而不是 -1 / Integer.MAX_VALUE：词库 1025 词里有 530 个是
     * apple / banana / pencil 这类具象名词，通用语料频次表本来就不收 ——
     * 「没有排名」是<b>正常数据</b>而非错误值，所以需要一个能直接当 int 默认值
     * 用、又能与真实排名（从 1 起）区分开的哨兵。后续按频次重排时，0 必须
     * 单独归到「无排名」桶（不能被当成「最常出现」排到最前，也不能当
     * 「最罕见」排到最后），零值内存语义（JVM 对象字段默认 0）也自动对上。
     */
    public static final int FREQ_RANK_UNKNOWN = 0;

    /**
     * freqRank 的合理上界：NGSL 1.2 约 2800 词，10 万已是 35 倍余量。
     *
     * <p>超过它的值一定是脏数据（典型是手滑把语料词频 12345678 填进排名栏），
     * 归一为「未知」而不是夹到上界：夹值会凭空造出一个「最罕见」的假排名，
     * 而未知桶在重排时是被显式处理的，脏数据掉进去至少语义诚实。
     */
    private static final int MAX_PLAUSIBLE_FREQ_RANK = 100_000;

    private final String english;
    private final String chinese;
    private final String partOfSpeech;
    private final String phonetic;
    private final String exampleSentence;
    private final List<String> synonyms;
    private final List<String> antonyms;
    private final String difficulty;
    private final int difficultyLevel;
    private final double reward; // 0 means use global default from config

    /**
     * 该词在 NGSL 1.2 频次表里的排名（1 = 最常用），{@link #FREQ_RANK_UNKNOWN}
     * 表示不在表内。
     *
     * <p>纯派生元数据：不参与 {@link #equals}/{@link #hashCode}（词身份仍只由
     * english + part_of_speech 决定，见下方 F2-04 注释），也不参与任何计分 / 抽词
     * 逻辑 —— 本阶段只做「把数据存进来」这一件事。
     */
    private final int freqRank;

    public Word(String english, String chinese, String partOfSpeech, String phonetic,
                String exampleSentence, List<String> synonyms, List<String> antonyms,
                String difficulty, int difficultyLevel) {
        this(english, chinese, partOfSpeech, phonetic, exampleSentence,
                synonyms, antonyms, difficulty, difficultyLevel, 0.0);
    }

    public Word(String english, String chinese, String partOfSpeech, String phonetic,
                String exampleSentence, List<String> synonyms, List<String> antonyms,
                String difficulty, int difficultyLevel, double reward) {
        this(english, chinese, partOfSpeech, phonetic, exampleSentence,
                synonyms, antonyms, difficulty, difficultyLevel, reward, FREQ_RANK_UNKNOWN);
    }

    /**
     * 全参构造：在既有 10 参签名<b>尾部</b>追加 freqRank。
     *
     * <p>为什么是重载而不是直接改签名：主代码 2 处、测试 29 处 {@code new Word(...)} 构造点
     * 关心的都是「词怎么背」，没有一处关心频次；加在尾部既不动老调用点，也不会
     * 与 10 参版产生重载歧义（11 个实参只可能匹配本构造）。
     *
     * <p>{@code freqRank} 在入口处归一（见 {@link #normalizeFreqRank}），所以
     * 任何构造点都不可能把负数 / 超界脏值塞进字段 —— 归一规则只有这一处真相。
     */
    public Word(String english, String chinese, String partOfSpeech, String phonetic,
                String exampleSentence, List<String> synonyms, List<String> antonyms,
                String difficulty, int difficultyLevel, double reward, int freqRank) {
        this.english = english;
        this.chinese = chinese;
        this.partOfSpeech = partOfSpeech;
        this.phonetic = phonetic;
        this.exampleSentence = exampleSentence;
        this.synonyms = synonyms != null ? synonyms : List.of();
        this.antonyms = antonyms != null ? antonyms : List.of();
        this.difficulty = difficulty;
        this.difficultyLevel = difficultyLevel;
        this.reward = reward;
        this.freqRank = normalizeFreqRank(freqRank);
    }

    /**
     * freqRank 归一：{@code <= 0}（负数 / 0）与 {@code > 上限} 一律视为
     * {@link #FREQ_RANK_UNKNOWN}。
     *
     * <p>放在这里而不是只放在 YAML 解析处，是因为构造点不止一个
     * （{@link WordBank} 与 {@code AdminCommandHandler.importWordBank} 各一条），
     * 未来还会有。解析层只负责「读出数字」，口径由本方法统一。
     */
    public static int normalizeFreqRank(int raw) {
        if (raw <= FREQ_RANK_UNKNOWN || raw > MAX_PLAUSIBLE_FREQ_RANK) {
            return FREQ_RANK_UNKNOWN;
        }
        return raw;
    }

    public String getEnglish() {
        return english;
    }

    public String getChinese() {
        return chinese;
    }

    public String getPartOfSpeech() {
        return partOfSpeech;
    }

    public String getPhonetic() {
        return phonetic;
    }

    public String getExampleSentence() {
        return exampleSentence;
    }

    public List<String> getSynonyms() {
        return synonyms;
    }

    public List<String> getAntonyms() {
        return antonyms;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public int getDifficultyLevel() {
        return difficultyLevel;
    }

    /**
     * Get the custom reward amount for this word.
     * Returns 0 if no custom reward is set (fall back to global config).
     */
    public double getReward() {
        return reward;
    }

    /**
     * 该词在 NGSL 1.2 频次表里的排名（1 = 最常用）；{@link #FREQ_RANK_UNKNOWN}
     * 表示不在表内（词库 1025 词里约 530 个属此类，属正常数据）。
     *
     * <p>调用方注意：0 不是"排到最前"，也不是"排到最后"，是<b>独立的一档</b>。
     * 后续按频次重排词库时，未知档要么按 english 兜底排序、要么整体垫底，
     * 但绝不能参与 {@code rank < N} 这类数值比较后混进有排名序列。
     */
    public int getFreqRank() {
        return freqRank;
    }

    /** 便捷判定：本词是否有真实频次排名（语义糖，避免调用点到处写 {@code != 0}）。 */
    public boolean hasFreqRank() {
        return freqRank != FREQ_RANK_UNKNOWN;
    }

    @Override
    public String toString() {
        return "Word{english='" + english + "', chinese='" + chinese + "'}";
    }

    /**
     * F2-04：词身份收窄为 english（大小写不敏感）+ part_of_speech。
     *
     * <p>原先把 reward（可调的经济属性）纳入判等，同一词在奖励调整后即判"不同词"，
     * 使依赖 equals 的集合去重 / 干扰项排除随 reward 漂移而失效。收窄后同英文不同
     * reward 视为同一个词；已核对消费点：{@link WordBank#getRandomWords(int, Word)}
     * 的 exclude 过滤（语义反而更稳：同词各变体一律排除）、
     * {@link AnswerChecker#buildChoiceOptions} 另有英文名二次过滤兜底；
     * 全项目无 Set&lt;Word&gt;/Map&lt;Word,*&gt; 容器消费（已 grep 核实）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Word word)) return false;
        String pos = partOfSpeech == null ? "" : partOfSpeech;
        String otherPos = word.partOfSpeech == null ? "" : word.partOfSpeech;
        return english.equalsIgnoreCase(word.english)
                && pos.equalsIgnoreCase(otherPos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(english.toLowerCase(),
                partOfSpeech == null ? "" : partOfSpeech.toLowerCase());
    }
}