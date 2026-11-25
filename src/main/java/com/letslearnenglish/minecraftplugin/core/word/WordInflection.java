package com.letslearnenglish.minecraftplugin.core.word;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 英文词形展开：给定词元（lemma）返回它可能出现在例句里的全部候选词形。
 *
 * <h2>为什么这份逻辑必须在生产侧</h2>
 * 它原先只活在测试里（{@code WordBankContentTest#containsWord}），用来断言"每个例句真的
 * 含目标词"。于是出现了一个荒诞的分裂：测试认为 {@code mean} 的例句里 {@code meant}
 * 是合法的，生产侧的 {@link AnswerChecker#blankSentence} 却只认 {@code \bmean\b}，
 * 匹配不上就把整句丢掉、只回一个光秃秃的 {@code "_____"}。
 *
 * <p>实测代价：全词库 1357 词里有 <b>208 个</b>（15.3%，beginner 20 / intermediate 61 /
 * advanced 127）在 fill_blank 模式丢掉了例句语境——而这些词的例句<b>没有一条</b>是真的
 * 不含目标词，全部是屈折形没匹配上。一份只在测试里生效的语言学知识，等于没生效。
 *
 * <p>所以规则的唯一真相源搬到这里，测试与生产共用同一份 {@link #formsOf}。
 * 两条路径一旦再次分叉，就会重现"测试全绿、玩家看到裸空格"的旧故障。
 *
 * <h2>纯函数</h2>
 * 无状态、无缓存、无 I/O，可任意线程并发调用。返回值是不可变集合，调用方不得修改。
 */
public final class WordInflection {

    /**
     * 不规则变化的封闭表。
     *
     * <p>条目<b>原样</b>搬自 {@code WordBankContentTest}：那张表是逐个对照现有词库例句
     * 校对出来的（哪个例句用了 {@code meant} / {@code told} / {@code worst} 才补哪一条），
     * 不是通用英语不规则动词表。随意增删会让"例句含目标词"这条既有断言的覆盖面漂移，
     * 所以这里刻意不做"顺手补全"。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code HashMap}/{@code Map.ofEntries}：
     * {@link #formsOf} 的返回顺序被 {@link AnswerChecker#blankSentence} 当作优先级用
     * （先命中的词形先替换），需要跨进程稳定；容器迭代序不定会让同一道题的挖空结果漂移。
     */
    private static final Map<String, List<String>> IRREGULAR;

    static {
        Map<String, List<String>> table = new LinkedHashMap<>();
        table.put("mean", List.of("meant"));
        table.put("tell", List.of("told"));
        table.put("keep", List.of("kept"));
        table.put("bring", List.of("brought"));
        table.put("hold", List.of("held"));
        table.put("lead", List.of("led"));
        table.put("spend", List.of("spent"));
        table.put("send", List.of("sent"));
        table.put("forbid", List.of("forbidden"));
        table.put("compel", List.of("compelled"));
        table.put("dispel", List.of("dispelled"));
        table.put("bad", List.of("worse", "worst"));
        table.put("activity", List.of("activities"));
        table.put("program", List.of("programming"));
        table.put("feel", List.of("felt"));
        table.put("get", List.of("got", "gotten"));
        table.put("find", List.of("found"));
        table.put("say", List.of("said"));
        table.put("think", List.of("thought"));
        table.put("come", List.of("came"));
        table.put("go", List.of("went"));
        table.put("see", List.of("saw", "seen"));
        table.put("take", List.of("took", "taken"));
        table.put("give", List.of("gave", "given"));
        table.put("make", List.of("made"));
        table.put("know", List.of("knew", "known"));
        table.put("become", List.of("became"));
        table.put("begin", List.of("began", "begun"));
        table.put("run", List.of("ran"));
        table.put("write", List.of("wrote", "written"));
        table.put("speak", List.of("spoke", "spoken"));
        table.put("break", List.of("broke", "broken"));
        table.put("choose", List.of("chose", "chosen"));
        table.put("drive", List.of("drove", "driven"));
        table.put("eat", List.of("ate", "eaten"));
        table.put("fall", List.of("fell", "fallen"));
        table.put("forget", List.of("forgot", "forgotten"));
        table.put("hear", List.of("heard"));
        table.put("lose", List.of("lost"));
        table.put("meet", List.of("met"));
        table.put("pay", List.of("paid"));
        table.put("rise", List.of("rose", "risen"));
        table.put("sell", List.of("sold"));
        table.put("sit", List.of("sat"));
        table.put("sleep", List.of("slept"));
        table.put("stand", List.of("stood"));
        table.put("teach", List.of("taught"));
        table.put("understand", List.of("understood"));
        table.put("wear", List.of("wore", "worn"));
        table.put("win", List.of("won"));
        table.put("child", List.of("children"));
        table.put("person", List.of("people"));
        table.put("man", List.of("men"));
        table.put("woman", List.of("women"));
        table.put("foot", List.of("feet"));
        table.put("tooth", List.of("teeth"));
        table.put("life", List.of("lives"));
        table.put("wife", List.of("wives"));
        IRREGULAR = Collections.unmodifiableMap(table);
    }

    /** 规则屈折后缀：先拼到"去尾 e 词干"上（move→moving / move→moved）。 */
    private static final String[] STEM_SUFFIXES = {"s", "es", "ed", "d", "ing"};

    /** 规则屈折后缀：直接拼到原形上（work→works / work→worked / work→working）。 */
    private static final String[] LEMMA_SUFFIXES = {"s", "ed", "ing"};

    /** 需要双写末辅音的词尾（commit→committed、hug→hugged、grin→grinned）。 */
    private static final Pattern DOUBLE_CONSONANT_TAIL = Pattern.compile(".*[bcdfghjklmnpqrstvwxz]$");

    /**
     * 短词阈值：≤ 2 个字母时只认原形，不做任何屈折展开。
     *
     * <p>红队 F1 踩过的坑，别拆：{@code a} 的规则屈折会生成 {@code as}（以及
     * {@code ad} / {@code aes}），于是 {@code "As I was leaving…"} 里那个 "as"
     * 会被判成包含目标词 "a" —— 挖空时更糟，玩家会看到句子被挖成 {@code "___ I was leaving"}，
     * 而正确答案根本不是 "As" 里的 A。同样的坑还有 {@code it → its}、{@code be → bed}。
     * 这几恰好是词频最高的功能词，本来最该被守住，所以短词走精确匹配。
     */
    private static final int SHORT_LEMMA_MAX_LENGTH = 2;

    private WordInflection() {
    }

    /**
     * 返回该词元的全部候选词形：原形 + 规则屈折 + {@link #IRREGULAR} 里的不规则形。
     *
     * <p>全部小写（调用方用大小写不敏感的整词匹配消费它）。形如 {@code work} 的结果是
     * {@code [work, works, worked, working]}；{@code mean} 额外带 {@code meant}。
     *
     * <p>结果<b>保序</b>且不可变：原形打头，其后依次是去 e 词干后缀、原形后缀、
     * 双写末辅音形、最后一律是不规则形。顺序即优先级——{@link AnswerChecker#blankSentence}
     * 取第一个命中项替换，所以"原形优先"意味着句子同时含原形与屈折形时挖掉原形
     * （题面更直观），而不会反过来把屈折形挖掉。
     *
     * <p>刻意<b>不</b>做词性区分：同一拼写在名词/动词两侧的屈折本来就重叠
     * （{@code report} 的 {@code reported}），词库里也没有可用的词性关联数据。
     *
     * @param lemma 词元（大小写不敏感；null / 空白返回空集合）
     * @return 不可变的候选词形集合，永不包含 null 或空串
     */
    public static Set<String> formsOf(String lemma) {
        if (lemma == null) {
            return Set.of();
        }
        String word = lemma.trim().toLowerCase(Locale.ROOT);
        if (word.isEmpty()) {
            return Set.of();
        }
        Set<String> forms = new LinkedHashSet<>();
        forms.add(word);
        if (word.length() <= SHORT_LEMMA_MAX_LENGTH) {
            return Collections.unmodifiableSet(forms);
        }

        String stem = word.endsWith("e") ? word.substring(0, word.length() - 1) : word;
        for (String suffix : STEM_SUFFIXES) {
            forms.add(stem + suffix);
        }
        for (String suffix : LEMMA_SUFFIXES) {
            forms.add(word + suffix);
        }
        // 双写末辅音是英语拼写规则，不是特例补丁：commit / hug / cram / drag / grin
        // 的真实屈折形都是 committed / hugged / crammed / dragged / grinned，
        // 漏了这一条会让这 5 个词重新退回裸 "_____"。
        if (DOUBLE_CONSONANT_TAIL.matcher(word).matches()) {
            String doubled = word + word.charAt(word.length() - 1);
            forms.add(doubled + "ed");
            forms.add(doubled + "ing");
        }
        forms.addAll(IRREGULAR.getOrDefault(word, List.of()));
        return Collections.unmodifiableSet(forms);
    }
}
