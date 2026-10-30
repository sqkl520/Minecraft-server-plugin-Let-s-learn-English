package com.letslearnenglish.minecraftplugin.core.word;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B2b（R3-b）：{@link WordBank#selectWordsForSession(int, Set, Set)} 的抽取契约。
 *
 * <p>本类钉住的是<b>降级链与不变量</b>，不是随机分布：
 * <ol>
 *   <li>排除集命中时，返回结果与排除集零交集；</li>
 *   <li>未掌握池被排除光时落到已掌握池，仍凑满 {@code count}；</li>
 *   <li>排除集为空时与旧 2 参入口<b>逐词等价</b>（回归锚点）；</li>
 *   <li><b>绝不返回空、绝不少于 {@code min(count, 词库总数)}</b> ——
 *       {@code WordManagerImpl.startSession} 有 {@code isEmpty → IllegalStateException}
 *       的硬约定，契约一旦退让就是"玩家点开学习直接报错"。</li>
 * </ol>
 *
 * <p>mock 姿势照 {@code GuiOpenIdentityTest} 的 {@code mock(FileConfiguration.class)}：
 * 只喂 {@code getList("words")}，不引入 MockBukkit（词库加载不碰任何注册表）。
 * {@code description} 走双参 {@code getString}，mock 默认返回 null，{@code WordBank}
 * 对它没有任何非空假设。
 */
class WordBankSelectionTest {

    // ------------------------------------------------------------------
    // 词库构造
    // ------------------------------------------------------------------

    /**
     * 造一个含 {@code english} 个词的词库。
     *
     * <p>每个词条只填 {@code english} / {@code chinese} 两个必填键（其余键在
     * {@code loadWords} 里都有默认值），这样用例里的"词库内容"只由入参决定，
     * 断言读起来就是词表本身。
     */
    private static WordBank bank(String... english) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String word : english) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("english", word);
            entry.put("chinese", "释义-" + word);
            entries.add(entry);
        }
        FileConfiguration config = mock(FileConfiguration.class);
        // getList 的返回类型是 List<?>，Mockito 的 thenReturn 对通配符实参不做捕获，
        // 因此这里显式标注 raw/wildcard 形态（与 GuiOpenIdentityTest 里 mock 配置的手法同源）。
        when(config.getList("words")).thenReturn((List) entries);
        return new WordBank("test", config);
    }

    /** 取返回结果的英文词集合（大小写按词库原样）。 */
    private static Set<String> words(List<Word> selected) {
        return selected.stream().map(Word::getEnglish).collect(Collectors.toCollection(HashSet::new));
    }

    /** 词库内全部英文词，确认"返回的每个词都来自本词库"。 */
    private static Set<String> allOf(WordBank bank) {
        return bank.getAllWords().stream().map(Word::getEnglish)
                .collect(Collectors.toCollection(HashSet::new));
    }

    // ------------------------------------------------------------------
    // 1. 排除集全命中且够数 → 与排除集零交集
    // ------------------------------------------------------------------

    @Test
    @DisplayName("排除集命中：返回词与排除集零交集（未掌握池仍够 count）")
    void exclusionIsHonouredWhenUnmasteredPoolIsLargeEnough() {
        WordBank bank = bank("alpha", "beta", "gamma", "delta", "epsilon");
        Set<String> excluded = Set.of("alpha", "beta");

        List<Word> selected = bank.selectWordsForSession(3, Set.of(), excluded);

        assertEquals(3, selected.size());
        Set<String> picked = words(selected);
        for (String word : picked) {
            assertFalse(excluded.contains(word),
                    "词 " + word + " 在排除集里，却仍被抽进本局喵（R3-b 的核心契约被破坏）");
        }
        assertEquals(Set.of("gamma", "delta", "epsilon"), picked,
                "未掌握池剩 3 个词、count=3，必须恰好是这三个（集合级零交集 + 凑满）");
    }

    // ------------------------------------------------------------------
    // 2. 未掌握池被排除光 → 降级到「未掌握（含被排除）」，仍凑满 count
    // ------------------------------------------------------------------

    @Test
    @DisplayName("降级②：未掌握池被排除光时回退到「未掌握（被排除也收）」，仍凑满 count")
    void fallsBackToExcludedUnmasteredBeforeTouchingMastered() {
        WordBank bank = bank("alpha", "beta", "gamma", "delta", "epsilon");
        // alpha / beta / gamma 未掌握但全被排除；delta / epsilon 已掌握。
        Set<String> mastered = Set.of("delta", "epsilon");
        Set<String> excluded = Set.of("alpha", "beta", "gamma");

        List<Word> selected = bank.selectWordsForSession(2, mastered, excluded);

        assertEquals(2, selected.size(), "必须凑满 count");
        Set<String> picked = words(selected);
        assertTrue(Set.of("alpha", "beta", "gamma").containsAll(picked),
                "降级链第②档是「未掌握（被排除也收）」：未掌握池被排除光时，"
                        + "重复刚答过的未掌握词仍比抽早已掌握的词有学习价值。"
                        + "实际返回=" + picked + "（含 delta/epsilon 说明降级链被改成"
                        + "「排除优先于学习价值」）");
        assertEquals(2, picked.size(), "两个词不得重复 —— 池里每词只出现一次");
    }

    // ------------------------------------------------------------------
    // 2b. 未掌握池本身为空 → 落到已掌握且非排除（降级③）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("降级③：全部词都已掌握时，优先返回「已掌握且非排除」")
    void fallsBackToMasteredCleanWhenNoUnmasteredWordsExist() {
        WordBank bank = bank("alpha", "beta", "gamma", "delta");
        Set<String> mastered = Set.of("alpha", "beta", "gamma", "delta");
        Set<String> excluded = Set.of("alpha", "beta");

        List<Word> selected = bank.selectWordsForSession(2, mastered, excluded);

        assertEquals(2, selected.size());
        assertEquals(Set.of("gamma", "delta"), words(selected),
                "没有未掌握词时，必须先取「已掌握且非排除」，"
                        + "而不是把刚答过的 alpha/beta 再抽一遍");
    }

    @Test
    @DisplayName("降级④：全部已掌握且全部被排除时，仍然返回 min(count, 词库总数)")
    void fallsBackToExcludedMasteredAsLastResort() {
        WordBank bank = bank("alpha", "beta", "gamma");
        Set<String> allMastered = Set.of("alpha", "beta", "gamma");

        List<Word> selected = bank.selectWordsForSession(2, allMastered,
                Set.of("alpha", "beta", "gamma"));

        assertEquals(2, selected.size(),
                "四级池全被排除时仍必须凑满 —— startSession 的 isEmpty → "
                        + "IllegalStateException 硬约定优先于排除语义");
    }

    // ------------------------------------------------------------------
    // 3. 排除集为空 → 与旧 2 参行为逐词等价（回归锚点）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回归锚点：排除集为空时 3 参版与旧 2 参入口逐词等价")
    void emptyExclusionIsEquivalentToLegacyTwoArgCall() {
        // 刻意构造成"池大小恰好等于 count"，让返回值与随机顺序无关，
        // 于是"逐词等价"可以被断言成集合相等，而不是靠概率。
        WordBank bank = bank("alpha", "beta", "gamma", "delta");
        Set<String> mastered = Set.of("delta");

        List<Word> legacy = bank.selectWordsForSession(3, mastered);
        List<Word> withEmpty = bank.selectWordsForSession(3, mastered, Set.of());

        assertEquals(words(legacy), words(withEmpty),
                "空排除集必须与旧 2 参入口返回同一批词（改造前行为是回归锚点）");
        assertEquals(Set.of("alpha", "beta", "gamma"), words(withEmpty),
                "未掌握池恰好 3 个词、count=3，应原样返回");
    }

    @Test
    @DisplayName("回归锚点：排除集为空时「未掌握不够、已掌握补位」的分配比例也与旧实现一致")
    void emptyExclusionMatchesLegacyWhenMasteredFillsTheGap() {
        WordBank bank = bank("alpha", "beta", "gamma", "delta");
        Set<String> mastered = Set.of("gamma", "delta");
        Set<String> unmastered = Set.of("alpha", "beta");

        List<Word> legacy = bank.selectWordsForSession(3, mastered);
        List<Word> withEmpty = bank.selectWordsForSession(3, mastered, Set.of());

        // 两版都含随机洗牌，"逐词相同"不可断言；可断言的是<b>池间分配比例</b>：
        // 未掌握 2 个必须全取，剩下的 1 个名额从已掌握池补 —— 这正是旧实现的
        // 「先取完未掌握、再用已掌握补位」语义。
        assertEquals(3, legacy.size());
        assertEquals(3, withEmpty.size());
        assertArrayEquals(composition(withEmpty, unmastered, mastered),
                composition(legacy, unmastered, mastered),
                "空排除集时 3 参版的「未掌握/已掌握」配额必须与 2 参版完全一致");
        assertEquals(2, countIn(withEmpty, unmastered),
                "未掌握池只有 2 个词、count=3 → 两个都必须被选中（旧实现同语义）");
    }

    /** 返回 {@code [未掌握数, 已掌握数]}，用于比较两版的池间配额。 */
    private static int[] composition(List<Word> selected, Set<String> unmastered, Set<String> mastered) {
        return new int[]{countIn(selected, unmastered), countIn(selected, mastered)};
    }

    private static int countIn(List<Word> selected, Set<String> pool) {
        int n = 0;
        for (Word word : selected) {
            if (pool.contains(word.getEnglish())) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 4. 排除集大于词库 → 不抛异常、返回 min(count, size)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("排除集大于词库：不抛异常，按契约降级返回 min(count, 词库总数)")
    void oversizedExclusionSetDegradesGracefully() {
        WordBank bank = bank("alpha", "beta", "gamma");
        Set<String> excluded = Set.of("alpha", "beta", "gamma", "zeta", "omega", "not-in-bank");

        List<Word> selected = assertDoesNotThrow(
                () -> bank.selectWordsForSession(2, Set.of(), excluded),
                "排除集含词库外的词/大于词库时不得抛异常喵");

        assertEquals(2, selected.size(),
                "全库被排除时仍必须返回 min(count, size) = 2 —— 契约不退让");
        assertEquals(allOf(bank), words(bank.selectWordsForSession(5, Set.of(), excluded)),
                "count 超过词库总数时，全库被排除也只能返回全库 3 个词");
    }

    // ------------------------------------------------------------------
    // 5. count > 词库总数 → 返回全部
    // ------------------------------------------------------------------

    @Test
    @DisplayName("count 超过词库总数：返回全部词（不重复、不多返）")
    void countLargerThanBankReturnsEverything() {
        WordBank bank = bank("alpha", "beta", "gamma");

        List<Word> selected = bank.selectWordsForSession(10, Set.of());

        assertEquals(3, selected.size(), "count=10 而词库只有 3 个词，必须返回 3 个");
        assertEquals(allOf(bank), words(selected), "返回的必须是全库，且每个词只出现一次");
    }

    // ------------------------------------------------------------------
    // 6. 词库 1 词且被排除 → 返回该词（契约不退让）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("契约不退让：词库只有 1 个词且被排除，仍返回该词而不是空")
    void singleWordBankNeverReturnsEmpty() {
        WordBank bank = bank("alpha");

        List<Word> selected = bank.selectWordsForSession(1, Set.of(), Set.of("alpha"));

        assertEquals(1, selected.size(),
                "唯一词被排除时返回空会让 startSession 抛 IllegalStateException —— "
                        + "「绝不返回空」优先于排除语义");
        assertEquals("alpha", selected.get(0).getEnglish());
    }

    // ------------------------------------------------------------------
    // 7. count = 0 → 空
    // ------------------------------------------------------------------

    @Test
    @DisplayName("count = 0：返回空列表（且不因负数下标抛异常）")
    void zeroCountReturnsEmpty() {
        WordBank bank = bank("alpha", "beta");

        assertTrue(bank.selectWordsForSession(0, Set.of()).isEmpty(), "count=0 必须返回空");
        assertTrue(bank.selectWordsForSession(0, Set.of(), Set.of("alpha")).isEmpty(),
                "count=0 时排除集不该改变结果");
        assertTrue(assertDoesNotThrow(() -> bank.selectWordsForSession(-3, Set.of(), Set.of())).isEmpty(),
                "负数 count 不得抛 IndexOutOfBounds（旧实现的 subList(0, -3) 会炸）");
    }

    // ------------------------------------------------------------------
    // 8. 大小写归一（裁决：toLowerCase 归一，视为同一个词）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("大小写：排除集写 \"Book\" 必须命中词库里的 \"book\"（统一 toLowerCase 归一）")
    void exclusionIsCaseInsensitive() {
        WordBank bank = bank("book", "pen", "desk");

        List<Word> selected = bank.selectWordsForSession(2, Set.of(), Set.of("Book"));

        assertEquals(2, selected.size());
        assertFalse(words(selected).contains("book"),
                "排除集是 \"Book\"、词库是 \"book\" 时若不归一，同一个词会被重新抽出来 —— "
                        + "而这正是 R3-b 要防的「刚答错就重抽同一题」");
        assertEquals(Set.of("pen", "desk"), words(selected));
    }

    // ------------------------------------------------------------------
    // 9. 防御：null / 含 null 与空串的排除集
    // ------------------------------------------------------------------

    @Test
    @DisplayName("防御：excluded 为 null 或含 null/空串时不抛 NPE、不影响抽词")
    void nullAndBlankExclusionEntriesAreIgnored() {
        WordBank bank = bank("alpha", "beta", "gamma");

        List<Word> nullSet = assertDoesNotThrow(
                () -> bank.selectWordsForSession(3, Set.of(), null),
                "excluded=null 不得抛 NPE（调用方可能传 null 表示不排除）");
        assertEquals(allOf(bank), words(nullSet));

        Set<String> dirty = new HashSet<>();
        dirty.add(null);
        dirty.add("");
        dirty.add("  ");
        List<Word> withBlanks = assertDoesNotThrow(
                () -> bank.selectWordsForSession(3, Set.of(), dirty));
        assertEquals(allOf(bank), words(withBlanks),
                "null/空串条目不能被当成「排除所有词」的键");
    }

    // ------------------------------------------------------------------
    // 10. 与已掌握池的先后：未掌握优先于已掌握（即使已掌握的更「干净」）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("优先级：未掌握（含被排除）优先于已掌握（未排除）—— 学习价值优先于排除")
    void unmasteredExcludedOutranksMasteredClean() {
        WordBank bank = bank("alpha", "beta", "gamma");
        // alpha/beta 未掌握但被排除；gamma 已掌握且未被排除。
        Set<String> mastered = Set.of("gamma");
        Set<String> excluded = Set.of("alpha", "beta");

        List<Word> selected = bank.selectWordsForSession(1, mastered, excluded);

        assertNotNull(selected.get(0));
        assertTrue(Set.of("alpha", "beta").contains(selected.get(0).getEnglish()),
                "未掌握池被排除光时，应先回退到「未掌握（含被排除）」而不是直接给已掌握词 —— "
                        + "重复一道刚答过的未掌握词仍比给一道早已掌握的词有学习价值；"
                        + "实际返回=" + selected.get(0).getEnglish());
    }
}
