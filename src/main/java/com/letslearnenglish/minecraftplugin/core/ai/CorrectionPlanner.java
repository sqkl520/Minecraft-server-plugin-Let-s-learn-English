package com.letslearnenglish.minecraftplugin.core.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * AI 纠错 → 学习闭环的纯规划器（R9 · corrections 闭环收口）。
 *
 * <p>把一次 AI 点评里的 {@code corrections} 与推荐词，映射成两组<b>互不重叠</b>的产出：
 * {@code countAsWrong}（累计错次 → 够阈值进错题本）与 {@code reviewOnly}（只排复习），
 * 并把三类丢弃计数一并返回，供调用方打一行可观测日志。本类不做任何 IO / Bukkit 调用，
 * 可被纯 JUnit 覆盖。
 *
 * <p><b>为什么错题本只收单词级</b>：错题本是「单词库视图」（GUI 按词条渲染、复习调度按词
 * 排程、PAPI 占位符按词拼），把一整句 {@code "I go yesterday"} 写进去会得到一条永远无法
 * 复习、无法渲染、无法统计的脏行。故句级纠错不进错题本，只把它里面的<b>已学过的词</b>
 * 排进复习队列（{@code reviewOnly}）—— 信息不丢，但落地形态合法。
 *
 * <p><b>为什么词形变化记在原形头上</b>：AI 报 {@code original=went}，玩家真实写错的是
 * {@code go} 的过去式形态；错题本的键是原形（词库只有原形）。记在 {@code go} 头上，玩家
 * 下次复习 {@code go} 时正好把这个形态问题带走。若记成 {@code went}，则是一个不在词库里的
 * 孤儿行，永远不会被复习命中。
 *
 * <p><b>为什么不收「未学过」的词进 reviewOnly</b>：给玩家排一个他还没学过的词的复习，
 * 复习会话抽到它时玩家无从答起（词义、拼写都没见过），只会制造挫败感。故句级切出的词必须
 * 通过 {@code knownToPlayer} 谓词。而 {@code countAsWrong} 是错次计数，与「是否学过」
 * 无关 —— 玩家写错的词一定要记账，哪怕还没学过。
 *
 * <p><b>去重口径</b>：同一场点评内，同一个词（经词形还原后的原形）只计一次。AI 完全可能
 * 把 {@code goes} 与 {@code go} 分别报两条，重复计数会让单词在第 2 条时就误判「第 3 次答错」
 * 直接刷进错题本 —— 这是既有实现已识别的风险，此处延续。
 *
 * <p><b>语义优先级</b>：同一个词同时出现在单词级纠错与句级纠错里时，<b>单词级优先</b>
 * （记进 {@code countAsWrong}）。实现上靠「两轮遍历」保证：先扫全部单词级，再扫句级回收 ——
 * 单轮处理会让先遇到的句级把词占进 {@code reviewOnly}，真答错的词反被当重复丢弃。
 *
 * <p>纯函数、无状态、线程安全。
 */
public final class CorrectionPlanner {

    private CorrectionPlanner() {
        // 纯工具类
    }

    /** 与 ConversationFeedback 的单词上限对齐。 */
    private static final int MAX_WORD_CHARS = 40;

    /**
     * 单词级判定 —— 单个英文词：字母开头、≥2 字母、无空格，允许词内连字符/撇号
     * （{@code don't} / {@code well-known}），不允许首尾标点。
     *
     * <p>与既有 {@code ChatFeedbackManager.WORD_LEVEL_PATTERN} 逐字一致（语义冻结，B1 侧不感知）。
     * <b>已知限制</b>：首字符后强制至少 1 个字符，故单字母词 {@code a} / {@code i} 永远不匹配
     * —— 它们无法进入纠错闭环。放宽会引入大量噪音（缩写与单字母拼写错误不可区分）。
     */
    private static final Pattern WORD_LEVEL_PATTERN =
            Pattern.compile("[A-Za-z](?:[A-Za-z]|['’-][A-Za-z])+");

    /** 句子切词：只按字母序列取 token（撇号与连字符也切开，与词库键口径一致）。 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z]+");

    /** 默认每句最多贡献的复习词数（调用方可用 es 覆盖）。 */
    public static final int DEFAULT_MAX_SENTENCE_WORDS = 3;

    /**
     * 规划结果（不可变；集合保插入序，即 AI 输出的语义顺序）。
     *
     * @param countAsWrong     需累计错次的词库原形（调用方负责 incrementWrongCount / 错题本）
     * @param reviewOnly       只排复习的词库原形（推荐词 + 句级切出的已学词）
     * @param droppedSentence  因「句级纠错未能产出任何新复习词」丢弃的条数
     *                         （无可回收词 / 切出的词全未学过 / 词全已被本场收过）
     * @param droppedUnknown   因「单词级但词库 + 词形均未命中」丢弃的条数
     *                         （<b>同时覆盖推荐词</b>：推荐词还原不到词库也记在这里，
     *                         因为 record 只有三个计数位，且两者的处置动作完全相同 —— 丢弃）
     * @param droppedDuplicate 因「本场已计过」丢弃的纠错条数
     */
    public record Plan(LinkedHashSet<String> countAsWrong,
                       LinkedHashSet<String> reviewOnly,
                       int droppedSentence,
                       int droppedUnknown,
                       int droppedDuplicate) {

        /** 防御性构造：集合永不为 null（调用方可无脑遍历）。 */
        public Plan {
            countAsWrong = countAsWrong != null ? countAsWrong : new LinkedHashSet<>();
            reviewOnly = reviewOnly != null ? reviewOnly : new LinkedHashSet<>();
        }
    }

    /**
     * 规划一次点评的落地动作。
     *
     * @param corrections     AI 纠错列表（可为 null / 含 null 元素）
     * @param recommendedWords AI 推荐词（可为 null / 含 null / 空串）
     * @param inBank          词库存在性谓词（为 null 时视为全部未知）
     * @param knownToPlayer   玩家是否学过该词（为 null 时视为全部未学过）
     * @param maxSentenceWords 每句最多贡献的复习词数（≤0 时用 {@value #DEFAULT_MAX_SENTENCE_WORDS}）
     * @return 规划结果，永不为 null
     */
    public static Plan plan(List<ConversationFeedback.Correction> corrections,
                            List<String> recommendedWords,
                            Predicate<String> inBank,
                            Predicate<String> knownToPlayer,
                            int maxSentenceWords) {
        LinkedHashSet<String> countAsWrong = new LinkedHashSet<>();
        LinkedHashSet<String> reviewOnly = new LinkedHashSet<>();
        int droppedSentence = 0;
        int droppedUnknown = 0;
        int droppedDuplicate = 0;

        Predicate<String> bank = inBank != null ? inBank : (w -> false);
        Predicate<String> known = knownToPlayer != null ? knownToPlayer : (w -> false);
        int maxPerSentence = maxSentenceWords > 0 ? maxSentenceWords : DEFAULT_MAX_SENTENCE_WORDS;

        if (corrections != null) {
            // ---- 第一轮：单词级纠错 ----
            // 必须跑在句级回收之前：句级回收会占住 reviewOnly 的位置，若先跑句级，
            // 一个「既被句级顺带切到、又被 AI 单独报为单词错误」的词就会先入 reviewOnly，
            // 随后的单词级纠错按重复丢弃 —— 真答错的词反而不计错次（语义优先级颠倒）。
            // 两轮的成本只是多一次列表遍历，换来的是「countAsWrong 恒优先」这条不变量。
            for (ConversationFeedback.Correction correction : corrections) {
                if (correction == null) {
                    continue;
                }
                String wordLevel = normalizeWordLevel(correction.original());
                if (wordLevel == null) {
                    continue; // 句级留给第二轮
                }
                String lemma = WordFormResolver.resolve(wordLevel, bank);
                if (lemma == null) {
                    droppedUnknown++;
                    continue;
                }
                if (reviewOnly.contains(lemma)) {
                    // 首轮里 reviewOnly 还不可能有值（句级在第二轮），此分支仅作防御
                    droppedDuplicate++;
                    continue;
                }
                if (countAsWrong.contains(lemma)) {
                    droppedDuplicate++;
                    continue;
                }
                countAsWrong.add(lemma);
            }

            // ---- 第二轮：句级 / 短语级回收进 reviewOnly ----
            for (ConversationFeedback.Correction correction : corrections) {
                if (correction == null) {
                    continue;
                }
                String original = correction.original();
                if (normalizeWordLevel(original) != null) {
                    continue; // 单词级已在第一轮处理
                }
                if (original == null || original.isBlank()) {
                    continue; // 空 original 无信息量，不计入任何丢弃口径（与 Parse 侧的跳过一致）
                }
                int harvested = harvestSentenceWords(original, bank, known, maxPerSentence,
                        countAsWrong, reviewOnly);
                if (harvested == 0) {
                    // 句级纠错未产出任何新复习词（无可回收词 / 词全未学过 / 词全已收过）
                    droppedSentence++;
                }
            }
        }

        if (recommendedWords != null) {
            for (String raw : recommendedWords) {
                String wordLevel = normalizeWordLevel(raw);
                if (wordLevel == null) {
                    continue; // 推荐词非单词级：不进复习池（复习池是单词粒度）
                }
                String lemma = WordFormResolver.resolve(wordLevel, bank);
                if (lemma == null) {
                    droppedUnknown++;
                    continue;
                }
                if (countAsWrong.contains(lemma) || reviewOnly.contains(lemma)) {
                    droppedDuplicate++;
                    continue;
                }
                // 推荐词只排复习，不进错题本（推荐词不是错词 —— 既有语义，不改）
                reviewOnly.add(lemma);
            }
        }

        return new Plan(countAsWrong, reviewOnly, droppedSentence, droppedUnknown, droppedDuplicate);
    }

    /**
     * 句级纠错回收：按非字母切词，逐词过 {@code resolve}，只保留 {@code knownToPlayer} 为真的词，
     * 每句最多 {@code maxPerSentence} 个。
     *
     * <p>为什么要求「学过」：见类 javadoc「为什么不收未学过的词」。为什么保留 change 顺序：
     * 一句话里先出现的词通常是主谓，先排它更符合语言直觉。
     *
     * @return 本句实际新增的 reviewOnly 数量（0 表示无可回收词，调用方据此计 droppedSentence）
     */
    private static int harvestSentenceWords(String sentence, Predicate<String> inBank,
                                            Predicate<String> known, int maxPerSentence,
                                            Set<String> countAsWrong,
                                            LinkedHashSet<String> reviewOnly) {
        int added = 0;
        var matcher = TOKEN_PATTERN.matcher(sentence);
        while (matcher.find() && added < maxPerSentence) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() < 2) {
                continue; // 单字母（a / I）已知不可达，见 WORD_LEVEL_PATTERN 注释
            }
            String lemma = WordFormResolver.resolve(token, inBank);
            if (lemma == null) {
                continue;
            }
            if (!known.test(lemma)) {
                continue; // 未学过的词不排复习（玩家答不出）
            }
            if (countAsWrong.contains(lemma) || reviewOnly.contains(lemma)) {
                continue; // 已被本场词级纠错 / 前一句收过
            }
            reviewOnly.add(lemma);
            added++;
        }
        return added;
    }

    /**
     * 单词级候选归一化：trim → 长度 ≤{@value #MAX_WORD_CHARS} → 全串匹配
     * {@link #WORD_LEVEL_PATTERN} → 小写。不合格返回 {@code null}。
     *
     * <p>与 {@code ChatFeedbackManager.normalizeFocusCandidate} 语义一致（迁移而非复制语义：
     * 原方法仍保留给 B1 侧，本类是它的纯函数版本）。
     */
    public static String normalizeWordLevel(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_WORD_CHARS
                || !WORD_LEVEL_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
