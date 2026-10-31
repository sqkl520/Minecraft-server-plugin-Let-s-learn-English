package com.letslearnenglish.minecraftplugin.core.word;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code freq_rank} 词频排名字段的解析契约（纯附加元数据，不改变任何抽词/计分行为）。
 *
 * <h2>为什么这些用例值得存在</h2>
 * <p>该字段本阶段<b>没有任何运行时消费者</b>，所以在「玩家体验」层面它是不可观测的：
 * 删掉 {@code WordBank} 里那段读取，插件照常启动、照常出题、全部用例仍绿。唯一的破坏
 * 面是<b>静默数据丢失</b> —— 后续按词频重排时发现 1025 个词一个排名都没有，而那时距离
 * 引入字段的改动已经很远。因此这里钉的是「数据进得来、脏值进不来、往返不丢」三件事。
 *
 * <h2>怎么在不碰 Bukkit 服务端的前提下跑真 YAML</h2>
 * <p>用 {@code new YamlConfiguration()} + {@link YamlConfiguration#loadFromString}，
 * <b>不用</b> {@code loadConfiguration(File)}：后者在解析失败时会去摸 Bukkit logger，
 * 纯 JVM 下直接 NPE（仓库里 {@code ServerToggleTest} 已踩过并留了注释，本类照抄同一手法）。
 * {@code loadFromString} 只走 shading 进来的 SnakeYAML，不触日志器。
 *
 * <h2>失败消息的约定</h2>
 * <p>每条断言都写清「哪种实现会把这条弄红」，避免后来者看到红灯时用改断言的方式消灭它。
 */
class WordFreqRankTest {

    // ------------------------------------------------------------------
    // 真 YAML 端到端：证明 SnakeYAML 解析出来的类型能落到字段上
    // ------------------------------------------------------------------

    /**
     * 用真 {@link YamlConfiguration} 造词库（不是 mock）。
     *
     * <p>这里刻意不 mock {@code FileConfiguration}：mock 的 {@code getList} 返回的是
     * 我们手动塞进去的 {@code Map}，YAML 里 {@code 42} 究竟解析成 {@code Integer} 还是
     * {@code Long}/{@code String} 完全测不出来 —— 而 {@code instanceof Number} 这条分支
     * 恰恰只在「真解析器给出的类型」下才有意义。
     */
    private static WordBank bankFromYaml(String... entryBlocks) {
        StringBuilder yaml = new StringBuilder("difficulty: test\ndescription: 频次字段测试\nwords:\n");
        for (String block : entryBlocks) {
            yaml.append(block);
        }
        FileConfiguration config = new YamlConfiguration();
        try {
            // loadFromString 抛的是受检的 InvalidConfigurationException，这里连受检一起接：
            // 用例里的 YAML 是常量字面量，解析失败只可能是测试自己写错，直接 fail 掉。
            config.loadFromString(yaml.toString());
        } catch (Exception e) {
            fail("测试用 YAML 本身不合法（是测试写错了，不是产品代码的问题）喵：" + e);
        }
        return new WordBank("test", config);
    }

    /** 单个词条的 YAML 片段；{@code extra} 是插在 english/chinese 之后的额外行。 */
    private static String entry(String english, String... extra) {
        StringBuilder sb = new StringBuilder("  - english: \"").append(english)
                .append("\"\n    chinese: \"释义-").append(english).append("\"\n");
        for (String line : extra) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static Word only(WordBank bank, String english) {
        Word word = bank.getWord(english);
        if (word == null) {
            fail("词库里找不到 " + english + " —— 该词条可能被 loader 当成坏词条跳过了喵");
        }
        return word;
    }

    // ------------------------------------------------------------------
    // 1. 有 freq_rank → 原样落到 field
    // ------------------------------------------------------------------

    @Test
    @DisplayName("freq_rank: 42 → getFreqRank() == 42（真 YAML，非 mock）")
    void parsesFreqRankFromRealYaml() {
        WordBank bank = bankFromYaml(entry("the", "freq_rank: 42"));

        Word the = only(bank, "the");

        assertEquals(42, the.getFreqRank(),
                "YAML 里写了 freq_rank: 42，字段就必须是 42 —— 若为 0，说明 WordBank "
                        + "根本没读这个键，或读了却没传给构造器（两种都是静默丢数据）");
        assertTrue(the.hasFreqRank(), "42 是真实排名，hasFreqRank() 必须为 true");
    }

    @Test
    @DisplayName("同一文件里有排名/无排名混排：各自独立，不串味")
    void mixedEntriesKeepTheirOwnRank() {
        // 三条不同词头、只有首尾带排名：钉住「解析写在循环内、每条各自读」。
        // 若有人把读取提到循环外，中间那条会继承上一条的排名，尾条也拿不到 530。
        WordBank bank = bankFromYaml(
                entry("the", "freq_rank: 1"),
                entry("apple"),
                entry("pear", "freq_rank: 530"));

        assertEquals(1, only(bank, "the").getFreqRank());
        assertEquals(Word.FREQ_RANK_UNKNOWN, only(bank, "apple").getFreqRank(),
                "没写 freq_rank 的词必须是 0，不能被同文件里邻近词条的排名污染");
        assertEquals(530, only(bank, "pear").getFreqRank(),
                "第三条有排名 → 530；若为 0，说明读取只对第一条生效（典型是解析写在循环外）");
    }

    // ------------------------------------------------------------------
    // 2. 缺字段 → 未知，且不崩
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无 freq_rank 字段 → FREQ_RANK_UNKNOWN(0)，不抛异常（530/1025 词的常态）")
    void missingFreqRankIsUnknownNotCrash() {
        WordBank bank = assertDoesNotThrow(
                () -> bankFromYaml(entry("apple")),
                "缺 freq_rank 是正常数据（词库里约 530 个词没有 NGSL 排名），"
                        + "绝不能因此抛异常或跳过词条");

        Word apple = only(bank, "apple");
        assertEquals(Word.FREQ_RANK_UNKNOWN, apple.getFreqRank());
        assertEquals(0, apple.getFreqRank(), "哨兵值必须就是 0（后续重排代码按 0 分桶）");
        assertFalse(apple.hasFreqRank(), "0 表示没有真实排名");
        assertEquals(1, bank.getWordCount(), "缺字段的词条必须照常入词库，不能被当成坏词条跳过");
    }

    @Test
    @DisplayName("空字符串 / null / 布尔 等非数字 → 未知，不抛 ClassCastException")
    void nonNumericFreqRankFallsBackToUnknown() {
        WordBank bank = assertDoesNotThrow(
                () -> bankFromYaml(
                        entry("a", "freq_rank: \"42\""),      // 加引号的数字字符串
                        entry("b", "freq_rank: \"\""),        // 空串
                        entry("c", "freq_rank: ~"),           // null
                        entry("d", "freq_rank: true")),       // 布尔
                "YAML 手写把数字加引号是常态；非数字必须降级为未知，而不是炸掉整档加载");

        for (String word : List.of("a", "b", "c", "d")) {
            assertEquals(Word.FREQ_RANK_UNKNOWN, only(bank, word).getFreqRank(),
                    word + " 的 freq_rank 不是数字 → 必须是未知(0)。"
                            + "若这里是 42，说明字符串被 Integer.parseInt 硬转了；"
                            + "若抛了异常，说明没走 instanceof Number 的宽容分支");
        }
    }

    // ------------------------------------------------------------------
    // 3. 脏数值 → 未知（负数 / 超界），不是夹值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("freq_rank: -5 → 未知(0)（负数不是排名，也不能当 0 以外的哨兵）")
    void negativeFreqRankIsUnknown() {
        WordBank bank = bankFromYaml(entry("apple", "freq_rank: -5"));
        assertEquals(Word.FREQ_RANK_UNKNOWN, only(bank, "apple").getFreqRank(),
                "负数必须归一到未知(0)。若字段里留着 -5，后续排序会把该词排到最前"
                        + "（-5 < 1），凭空造出一个「比 the 还常用」的假排名");
    }

    @Test
    @DisplayName("freq_rank: 200000 → 未知(0)（超界脏值归一，不是夹到上界）")
    void absurdFreqRankIsUnknown() {
        WordBank bank = bankFromYaml(entry("apple", "freq_rank: 200000"));
        assertEquals(Word.FREQ_RANK_UNKNOWN, only(bank, "apple").getFreqRank(),
                "20 万远超 NGSL 规模（约 2800），必是脏数据（多半把词频填进了排名栏）。"
                        + "必须掉进未知桶，而不是被夹成 100000 —— 夹值会伪造一个「最罕见」排名");
    }

    @Test
    @DisplayName("freq_rank: 42.7 → 42（浮点截断，导出工具把整数写成 42.0 是常见成因）")
    void floatingPointFreqRankIsTruncated() {
        WordBank bank = bankFromYaml(entry("apple", "freq_rank: 42.7"));
        assertEquals(42, only(bank, "apple").getFreqRank(),
                "排名只有整数语义；YAML 里出现 42.0 多半是导出工具把整数序列化成了浮点，"
                        + "截断是正确还原。若改成「非整数即未知」，会把这类正常导出的排名全丢掉");
    }

    // ------------------------------------------------------------------
    // 4. 构造器重载：老签名默认未知，新签名收值
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("构造器重载")
    class Constructors {

        @Test
        @DisplayName("9 参构造 → 未知（25 个既有调用点用的就是这个）")
        void nineArgConstructorDefaultsToUnknown() {
            Word w = new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1);
            assertEquals(Word.FREQ_RANK_UNKNOWN, w.getFreqRank(),
                    "老的 9 参构造必须委托成未知(0)，否则 25 个既有调用点会拿到未定义值");
        }

        @Test
        @DisplayName("10 参构造 → 未知（reward 版，既有调用点）")
        void tenArgConstructorDefaultsToUnknown() {
            Word w = new Word("apple", "苹果", "noun", null, null, null, null,
                    "beginner", 1, 2.5);
            assertEquals(Word.FREQ_RANK_UNKNOWN, w.getFreqRank());
            assertEquals(2.5, w.getReward(), "新增参数不得影响既有 reward 的传递");
        }

        @Test
        @DisplayName("11 参构造 → 收值并归一")
        void elevenArgConstructorAcceptsAndNormalises() {
            Word ranked = new Word("the", "这个", "determiner", null, null, null, null,
                    "beginner", 1, 0.0, 1);
            Word dirty = new Word("apple", "苹果", "noun", null, null, null, null,
                    "beginner", 1, 0.0, -9);

            assertEquals(1, ranked.getFreqRank());
            assertEquals(Word.FREQ_RANK_UNKNOWN, dirty.getFreqRank(),
                    "归一在构造器入口做 —— 这是三条解析路径唯一的口径来源");
        }

        @Test
        @DisplayName("normalizeFreqRank 边界：0/负数/恰好上限/超上限")
        void normaliseBoundaries() {
            assertEquals(Word.FREQ_RANK_UNKNOWN, Word.normalizeFreqRank(0));
            assertEquals(Word.FREQ_RANK_UNKNOWN, Word.normalizeFreqRank(-1));
            assertEquals(1, Word.normalizeFreqRank(1), "1 = 最常用，必须保留");
            assertEquals(100_000, Word.normalizeFreqRank(100_000), "上限本身是合法值");
            assertEquals(Word.FREQ_RANK_UNKNOWN, Word.normalizeFreqRank(100_001));
        }
    }

    // ------------------------------------------------------------------
    // 5. 不参与词身份：equals/hashCode 不得因 freqRank 变化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("freqRank 不进 equals/hashCode（否则补数据会让去重/干扰项排除随数据漂移）")
    void freqRankDoesNotAffectWordIdentity() {
        Word noRank = new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1);
        Word withRank = new Word("apple", "苹果", "noun", null, null, null, null,
                "beginner", 1, 0.0, 42);
        Word otherPos = new Word("apple", "苹果", "verb", null, null, null, null,
                "beginner", 1, 0.0, 42);

        assertEquals(noRank, withRank,
                "同词同词性、只有 freqRank 不同 → 必须仍判等（词身份口径见 Word 的 F2-04 注释）");
        assertEquals(noRank.hashCode(), withRank.hashCode(),
                "equals 判等时 hashCode 必须一致，否则 HashSet 去重失效");
        assertNotEquals(withRank, otherPos, "part_of_speech 仍是身份的一部分");
    }

    // ------------------------------------------------------------------
    // 6. 源码级 lint：字段可选（不得进必填校验）+ 导出必须写回
    // ------------------------------------------------------------------

    /**
     * 源码根：与仓库既有 lint（{@code *LintsTest}）同一取法 —— 从仓库根起按文件名定位。
     *
     * <p>为什么这一组必须是 lint 而不是行为测试：{@code exportWordBank} 需要真实的
     * {@code plugin} / {@code WordManager} / 文件系统，纯 JVM 起不来；而这里要钉的恰恰是
     * 「那几行 put 还在不在」。行为测试写不出来，lint 是唯一可行的手段。
     */
    private static String sourceOf(String fileName) {
        Path root = Paths.get(System.getProperty("user.dir"), "src", "main", "java",
                "com", "letslearnenglish", "minecraftplugin");
        List<Path> hits = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (IOException e) {
            fail("遍历 " + root.toAbsolutePath() + " 失败喵：" + e.getMessage());
        }
        if (hits.size() != 1) {
            fail(fileName + " 的命中数是 " + hits.size() + "（期望恰好 1）喵 —— "
                    + "文件被移动/改名时请同步本 lint，否则门禁会静默失效。");
        }
        try {
            return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /**
     * 剥掉 {@code //} 行注释与 <code>/* ... *&#47;</code> 块注释，保留字符串字面量原样。
     *
     * <p><b>为什么必须剥</b>：本类的三条 lint 都在找「某行代码在不在」。若直接扫原文，
     * 一句<b>解释</b>「这里不要写 freq_rank」就能满足正向断言（假绿），而一句「历史上曾
     * 把 freq_rank 加进必填校验」的注释又能让反向断言误红。剥注释后，命中只能来自真代码。
     *
     * <p>字符串字面量不剥：本类要精确匹配的 {@code "freq_rank"} 就在字面量里，剥掉它
     * 等于把锚点本身删了。扫描时按「引号内不看注释标记」跳过即可。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(src.charAt(i));
                        i++;
                    }
                    out.append(src.charAt(i));
                    i++;
                }
                if (i < n) {
                    out.append(src.charAt(i));
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    @Test
    @DisplayName("lint：WordBank 必须读 freq_rank，且不得把它接进必填字段诊断")
    void wordBankReadsFreqRankButNeverTreatsItAsRequired() {
        String src = sourceOf("WordBank.java");

        assertTrue(src.contains("wordData.get(\"freq_rank\")"),
                "WordBank.java 里找不到对 freq_rank 的读取 —— 字段将永远读不进来"
                        + "（YAML 写了也白写），这是静默数据丢失");
        assertTrue(src.contains("Word.FREQ_RANK_UNKNOWN"),
                "读取的缺省值必须显式用 Word.FREQ_RANK_UNKNOWN 哨兵，而不是裸 0 魔法数");

        assertFalse(src.contains("describeBadField(wordData.get(\"freq_rank\")"),
                "freq_rank 绝不能进 describeBadField 的必填诊断 —— 1025 词里约 530 个"
                        + "本来就没有排名，接进来会让每次正常加载刷出 500+ 条假错误。"
                        + "（这条 lint 的职责就是拦住「顺手加个校验」的下一位贡献者）");
    }

    @Test
    @DisplayName("lint：WordBankValidator 不得校验 freq_rank（否则正常词库会被判非法）")
    void validatorDoesNotRequireFreqRank() {
        String src = sourceOf("WordBankValidator.java");

        assertFalse(src.contains("freq_rank"),
                "WordBankValidator.java 出现了 freq_rank —— 导入路径会开始对"
                        + "「没有排名」的 530 个词报错，整档词库无法导入。"
                        + "该字段是可选派生元数据，类型写错也只按未知降级（见 WordBank 注释）");
    }

    @Test
    @DisplayName("lint：两处导出（exportWordBank / saveWordBankToYaml）都必须写回 freq_rank")
    void bothExportPathsWriteFreqRank() {
        String src = sourceOf("AdminCommandHandler.java");

        int occurrences = countOccurrences(src, "wordData.put(\"freq_rank\", w.getFreqRank())");
        assertEquals(2, occurrences,
                "AdminCommandHandler 里有两条导出路径：exportWordBank（导出到 exports/）"
                        + "与 saveWordBankToYaml（/lea importwordbank 落盘回 words/）。"
                        + "两处都必须在有排名时写 freq_rank，否则一次 export → import"
                        + "或一次导入落盘就会把频次数据静默抹掉（实际命中 " + occurrences + " 处）");

        assertTrue(src.contains("wordData.get(\"freq_rank\")"),
                "importWordBank 自己拼 Word、不经过 WordBank.loadWords，必须独立读一次"
                        + "freq_rank，否则导入侧永远是未知");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
