package com.letslearnenglish.minecraftplugin.core.ai;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 词形还原器（R9 · corrections 闭环收口）：把「疑似词形变化」的 token 还原成词库里的原形。
 *
 * <p><b>硬约束</b>：返回值一定通过调用方给的 {@code inBank} 谓词 —— 本类绝不猜、绝不造词。
 * 任何还原不出词库原形的输入一律返回 {@code null}，由调用方按「未知词」处理（计入丢弃统计）。
 *
 * <p><b>为什么不能用编辑距离 / 模糊匹配</b>：词库只有 1025 条原形，常见词形与某个原形之间的
 * 编辑距离经常只有 1（实测 {@code news→new}、{@code notes→not}、{@code yours→your}），
 * 距离阈值挡不住这类误判，只会把「随机邻近词」当成词形变化。本类坚持「只看后缀形态 +
 * 词库必须命中」两条硬规则。
 *
 * <p><b>为什么没有不规则动词表</b>（R9 裁决，不加）：
 * <ol>
 *   <li>有限白名单覆盖不到全部不规则式 —— 同一个 {@code -ed} 规则在表内命中、表外返回 null，
 *       对调用方是<b>不可预测的不对称</b>，排查成本高于收益；</li>
 *   <li>表一旦收录 {@code went→go}，{@code went} 就不再是「安全返回 null」的实测误判候选，
 *       与 R9 冻结的回归契约（{@code went} / {@code children} / {@code bus} / {@code glass} /
 *       {@code is} 全部 null）直接冲突；</li>
 *   <li>本类定位是「宁可漏还原，不可错还原」—— 漏掉的词形会进
 *       {@link CorrectionPlanner.Plan#droppedUnknown()} 并有 FINE 级日志，属<b>可见的失败</b>，
 *       而不是静默污染错题本。</li>
 * </ol>
 * 若未来确实要做不规则还原，正确做法是新增独立的 {@code IrregularVerbTable} 并单独开关，
 * 不要把有限表塞进本类。
 *
 * <p><b>已知限制</b>：
 * <ul>
 *   <li>单字母词（{@code a} / {@code i}）永远返回 null —— 词库里有它们，但单词级纠错的正则
 *       （{@link CorrectionPlanner#normalizeWordLevel(String)}）要求 ≥2 字母，因此
 *       {@code a} / {@code i} <b>无法进入纠错闭环</b>。放宽正则会引入大量噪音（缩写、单字母
 *       拼写错误无法与正常输入区分），故维持现状并在此显式记录。</li>
 *   <li>词干歧义（{@code hop} / {@code hope} 类）：只有「是否在词库」一个判据，没有词性/词频。
 *       {@code -ing} 分支按「原样词干 → 双写去重 → 补 e」的顺序取第一个命中，但 3 字母
 *       CVC 词干要过 {@link #bareStemAllowed} 双写门 —— {@code hopping} 才可能是 {@code hop}
 *       的屈折，因此 {@code hoping} 会正确落到补 e 的 {@code hope}。仍无法区分的只有
 *       「同一拼写对应两个词性」这类数据问题（如 {@code saw} 既是名词也是 see 的过去式）。</li>
 *   <li>2 字母词干（{@code -ing} / {@code -oes}）只认白名单内的真实屈折
 *       （{@code being / doing / going / goes / does}）：{@code using} 的 {@code us}、
 *       {@code toes} 的 {@code to} 属语义无关碎片，一律 null。</li>
 *   <li>不规则式（{@code went} / {@code children} / {@code bought}）一律 null，见上。</li>
 * </ul>
 *
 * <p>纯函数、无状态、线程安全。
 */
public final class WordFormResolver {

    private WordFormResolver() {
        // 纯工具类
    }

    /** 与 ConversationFeedback 的单词上限对齐（防异常长 token 参与后缀运算）。 */
    private static final int MAX_CHARS = 40;

    /** 原形 / 候选的最小长度：2（{@code go} / {@code do} / {@code be} / {@code us} 都是 2 字母）。 */
    private static final int MIN_LEMMA_CHARS = 2;

    /**
     * 剥离 {@code -es/-s/-ed/-ing} 后，词干的<b>常规</b>最小长度（3）。
     *
     * <p>为什么单独提高这条门槛：剥掉 2 个及以上字母后只剩 2 个字母，在本词库里几乎全是巧合
     * —— 实测 {@code bees → be}、{@code notes → not} 同属此类。而 {@code goes → go} /
     * {@code does → do} / {@code going → go} / {@code being → be} 是真实的 2 字母词干，
     * 由 {@link #OES_LEMMAS} 与 {@link #SHORT_ING_STEMS} 两个显式白名单单独放行。
     */
    private static final int MIN_STEM_CHARS = 3;

    /**
     * 形似屈折、实为独立词的闭集（机械剥离会指向词库里的另一个词）。
     *
     * <p>{@code news} 是物质名词，不是 {@code new} 的复数；{@code yours/ours/theirs/hers/its}
     * 是物主代词，与 {@code your/our/their/her/it} 词性不同 —— 把它们记成「写错 your」会往
     * 错题本里塞入玩家根本没犯的错。集合刻意保持极小：只收实测 / 语言学上确定的假朋友，
     * 不做通用停用词表（那会把正常屈折一起挡掉）。
     */
    private static final Set<String> NON_INFLECTION_TOKENS =
            Set.of("news", "yours", "ours", "theirs", "hers", "its");

    /**
     * 允许作为 3 字母 {@code -e} 尾原形的韵脚（{@code -d} 剥离分支专用）。
     *
     * <p>为什么需要：{@code -d} 剥离把 {@code shed} 变成 {@code she}、{@code wed} 变成
     * {@code we} —— 后者都在词库里，会被误记成「写错 she/we」。英语里 3 字母 e 尾实词基本
     * 只落在 {@code use / die / lie / tie} 这几类韵脚上，闭集足够小且可解释。
     *
     * <p><b>为什么没有 {@code ee}</b>（FIX-H3 收口 P1-A）：{@code ?ee} 这个 3 字母韵脚让
     * {@code seed} 被 {@code -d} 剥离成 {@code see}（{@code see} 在词库）—— 而 {@code seed}
     * 本身就是独立的实词（种子），与 {@code see}（看见）语义毫无关系。这条误判来自
     * 「{@code seed} 以 {@code -ed} 结尾」这一点：{@code -ed} 字面尾串对 {@code seed} 是
     * 假信号，真正的 {@code X + d} 静音 e 词基是 {@code care / love / use} 这类
     * {@code 辅音 + e} 结尾的词，{@code s + ee} 不在其中。4 字母及以上词基不受影响
     * （{@code freed → free}、{@code agreed → agree} 走长度分支，不查本集合）。
     */
    private static final Set<String> SHORT_E_RIMES =
            Set.of("se", "ce", "ve", "ge", "ze", "te", "de", "ne", "le", "pe", "me", "ue", "ie");

    /**
     * {@code -oes → -o} 允许的 2 字母词干白名单（FIX-H3 收口 P1-B）。
     *
     * <p>为什么用白名单而不是「长度 ≥2」阈值：{@code -oes} 里 2 字母词干的真实屈折只有
     * {@code goes → go} / {@code does → do}。阈值会把 {@code toes → to}、{@code foes → fo}
     * 一起放行 —— {@code toes}（脚趾）与介词 {@code to} 语义无关，属典型假阳性。
     * 3 字母及以上的 o 尾词干（{@code echoes → echo} / {@code heroes → hero} /
     * {@code potatoes → potato}）仍走 {@link #esStemAllowed} 的通用 o 尾规则。
     */
    private static final Set<String> OES_LEMMAS = Set.of("go", "do");

    /**
     * {@code -ing} 允许的 2 字母词干白名单（FIX-H3 收口 P1-D）。
     *
     * <p>{@code MIN_STEM_CHARS} 之下，未被白名单放行的 2 字母词干一律不进候选：它们是
     * 「补 e 型动词被机械截断」的产物 —— {@code using} 的 {@code us} 是代词，
     * {@code thing} 的 {@code th} 是碎片。真实的 2 字母词干屈折只有
     * {@code being / doing / going}（以及其 {@code -s} 形 {@code goes / does}）。
     */
    private static final Set<String> SHORT_ING_STEMS = Set.of("be", "do", "go");

    /**
     * 把 token 还原成词库原形。
     *
     * <p>顺序：① 原词本身命中词库 → 原样返回（原形优先，绝不画蛇添足）；② 假朋友闭集 → null；
     * ③ 按 {@code -ies / -ied / -es / -s / -ed / -ing} 依次尝试，第一个命中的候选返回。
     * 所有候选都必须 {@code inBank.test(candidate)} 且长度 ≥ {@value #MIN_LEMMA_CHARS}。
     *
     * @param token  待还原的 token（大小写、首尾空白会被归一化；null 安全）
     * @param inBank 词库存在性谓词（为 null 时恒返回 null）
     * @return 词库内的小写原形；任何一步不命中返回 {@code null}
     */
    public static String resolve(String token, Predicate<String> inBank) {
        if (token == null || inBank == null) {
            return null;
        }
        String lower = token.trim().toLowerCase(Locale.ROOT);
        if (lower.length() < MIN_LEMMA_CHARS || lower.length() > MAX_CHARS
                || !looksLikeWord(lower)) {
            return null;
        }
        // ① 原词本身就在词库：它是自己的原形，不做任何剥离（bed ← be+d 类碰撞在此消解）。
        if (inBank.test(lower)) {
            return lower;
        }
        // ② 假朋友：形态像屈折，剥离后会命中另一个词 —— 一律不还原。
        if (NON_INFLECTION_TOKENS.contains(lower)) {
            return null;
        }

        String hit;
        // ③ -ies → -y（studies → study / tries → try）
        if (lower.endsWith("ies") && lower.length() - 3 >= MIN_LEMMA_CHARS) {
            hit = firstHit(inBank, strip(lower, 3) + "y");
            if (hit != null) {
                return hit;
            }
        }
        // ④ -ied → -y（studied → study / carried → carry）
        if (lower.endsWith("ied") && lower.length() - 3 >= MIN_LEMMA_CHARS) {
            hit = firstHit(inBank, strip(lower, 3) + "y");
            if (hit != null) {
                return hit;
            }
        }
        // ⑤ -es：仅当词干形状符合英语 -es 拼写规则（s/x/z/o 或 ch/sh 结尾）才剥离
        if (lower.endsWith("es")) {
            String stem = strip(lower, 2);
            if (esStemAllowed(lower, stem)) {
                hit = firstHit(inBank, stem);
                if (hit != null) {
                    return hit;
                }
            }
        }
        // ⑥ -s：glass / bus / is 类结尾不剥离（那不是复数/三单）
        if (lower.endsWith("s") && sStemAllowed(lower)) {
            hit = firstHit(inBank, strip(lower, 1));
            if (hit != null) {
                return hit;
            }
        }
        // ⑦ -ed：去 -d（cared → care / loved → love）、去 -ed（worked → work）、双写去重（stopped → stop）
        if (lower.endsWith("ed") && lower.length() >= 4) {
            String stem = strip(lower, 2);
            String silentE = strip(lower, 1);
            boolean stemUsable = stem.length() >= MIN_STEM_CHARS;
            // 顺序不可反（FIX-H3 收口 P1-C）：静音 e 词基必须排在 strip(2) 之前。
            // 英语里以 e 结尾的动词加 -ed 只加 -d，所以 cared 的真实词干是 care 而不是 car；
            // 反过来，car 在词库而 care 也在词库时，strip(2) 优先会把 cared 误记成「写错 car」——
            // 这是优先级反转（RT-H1 实测：care 在库仍返回 car），不是数据稀疏问题。
            // 静音 e 词基不在词库时才回落到 strip(2) / 双写去重，保证 worked / stopped / needed
            // 等常规 -ed 不受影响（它们的 silentE 形 worke / stoppe / neede 都不在词库）。
            hit = firstHit(inBank,
                    silentEBase(silentE) ? silentE : null,
                    stemUsable && bareStemAllowed(stem) ? stem : null,
                    stemUsable ? undouble(stem) : null);
            if (hit != null) {
                return hit;
            }
        }
        // ⑧ -ing：去 -ing（working → work）、双写去重（running → run）、加 e（making → make）
        if (lower.endsWith("ing") && lower.length() - 3 >= MIN_LEMMA_CHARS) {
            String stem = strip(lower, 3);
            // 2 字母词干只有白名单里的真实屈折（being / doing / going）能进候选。
            // 不设此门 → using 的词干 us（代词）会被当成原形命中（FIX-H3 收口 P1-D）。
            boolean stemUsable = stem.length() >= MIN_STEM_CHARS || SHORT_ING_STEMS.contains(stem);
            // 先试「词干本身就是词」，再试双写去重，最后才补 e。
            // 顺序理由：词干命中（sing / work / study）永远是更可信的那个；只有当词干不在词库
            // 或词干被白名单挡掉（mak / clos / us）时才轮到补 e，此时 make / close / use 才是真原形。
            // 与 -ed 分支同理，常规词干也要过 CVC 双写门（caring → car 由此挡掉）。
            // 补 e 的候选仍过 silentEBase 形态门（3 字母基必须落在 SHORT_E_RIMES 内）：
            // thing → the（韵脚 he 不在集合）与 using → use（韵脚 se 在集合）由此区分开。
            String plusE = !stem.endsWith("e") && silentEBase(stem + "e") ? stem + "e" : null;
            hit = firstHit(inBank,
                    stemUsable && bareStemAllowed(stem) ? stem : null,
                    stemUsable ? undouble(stem) : null,
                    plusE);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 按顺序返回第一个「在词库内且长度合法」的候选；全不中返回 null。
     *
     * <p>长度下限 {@value #MIN_LEMMA_CHARS} 是硬契约（防 2 字母以下的碎片；单字母词
     * {@code a}/{@code i} 的已知限制见类 javadoc）。
     */
    private static String firstHit(Predicate<String> inBank, String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (candidate.length() < MIN_LEMMA_CHARS || candidate.length() > MAX_CHARS) {
                continue;
            }
            if (inBank.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 去掉末尾 {@code n} 个字符（调用方已保证长度足够）。 */
    private static String strip(String word, int n) {
        return word.substring(0, word.length() - n);
    }

    /**
     * token 形态门：必须以字母开头、只含字母与词内连字符/撇号。
     *
     * <p>防的是 {@code "123s"} 这类「以数字开头但在词库里恰好有后缀匹配」的路径 —— 词库
     * 索引里不存在数字开头的键，但 {@code inBank} 由调用方提供（可能是任意谓词），
     * 形态层拒绝比信任谓词更安全。
     */
    private static boolean looksLikeWord(String lower) {
        char first = lower.charAt(0);
        if (first < 'a' || first > 'z') {
            return false;
        }
        for (int i = 1; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || c == '-' || c == '\'') {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * {@code -es} 的词干形状门：英语里只有词干以 s/x/z/o/ch/sh 结尾时才加 {@code -es}。
     *
     * <p>这一条同时是假朋友防线：{@code bees}（词干 {@code be}）与 {@code notes}
     * （词干 {@code not}，而 {@code not} 是词库词条）都不满足形状，直接不剥离。
     *
     * <p>{@code -oes} 是 2 字母词干的唯一放行口，且必须落在 {@link #OES_LEMMAS} 白名单内
     * （FIX-H3 收口 P1-B）：{@code goes → go} / {@code does → do} 是真实屈折，而
     * {@code toes → to} / {@code foes → fo} 只是长度阈值放出来的假阳性。
     * 3 字母及以上的 o 尾词干（{@code echoes → echo}）继续走下面的通用规则。
     */
    private static boolean esStemAllowed(String word, String stem) {
        if (stem.isEmpty()) {
            return false;
        }
        if (word.endsWith("oes")) {
            if (stem.length() < MIN_LEMMA_CHARS) {
                return false;
            }
            if (stem.length() < MIN_STEM_CHARS) {
                return OES_LEMMAS.contains(stem);
            }
        }
        if (stem.length() < MIN_STEM_CHARS) {
            return false;
        }
        char last = stem.charAt(stem.length() - 1);
        if (last == 's' || last == 'x' || last == 'z' || last == 'o') {
            return true;
        }
        return stem.endsWith("ch") || stem.endsWith("sh");
    }

    /**
     * {@code -s} 剥离门：词干 ≥3 字母，且词尾不是 {@code ss}/{@code us}/{@code is}。
     *
     * <p>{@code glass}（剥成 {@code glas} 无意义）、{@code bus}（拉丁词尾）、{@code is}
     * （系动词，剥成 {@code i}）都必须在形态层就拒绝 —— 而不是靠「候选恰好不在词库」蒙混过关，
     * 那条防线在词库扩容后会失效。
     */
    private static boolean sStemAllowed(String word) {
        if (word.length() - 1 < MIN_STEM_CHARS) {
            return false;
        }
        return !(word.endsWith("ss") || word.endsWith("us") || word.endsWith("is"));
    }

    /**
     * {@code -d} 剥离的静音 e 词基门：{@code love → loved} 合法，{@code she → shed} 不合法。
     *
     * <p>规则：长度 ≥4 且以 {@code e} 结尾；或长度 3 且落在 {@link #SHORT_E_RIMES} 内。
     * 这挡住了实测碰撞 {@code she / we / the}（3 字母、韵脚不在集合里）。
     */
    private static boolean silentEBase(String base) {
        int n = base.length();
        if (n < 3 || base.charAt(n - 1) != 'e') {
            return false;
        }
        if (n >= 4) {
            return true;
        }
        return SHORT_E_RIMES.contains(base.substring(1));
    }

    /**
     * 去掉 {@code -ed} / {@code -ing} 后的裸词干是否可能是真实原形（拼写门，FIX-H3 收口 P1-C）。
     *
     * <p>规则：<b>3 字母、辅-元-辅（CVC）且结尾不是 {@code x/w/y}</b> 的词干一律拒绝 ——
     * 这类单音节动词加 {@code -ed/-ing} 时英语强制双写末辅音（{@code stop → stopped}、
     * {@code hug → hugged}），所以形如 {@code Xed / Xing} 的拼写根本不可能是它的屈折。
     * 反例全部由此挡掉：{@code cared → car}、{@code noted → not}、{@code caring → car}、
     * {@code hoped → hop}（{@code hop} 的真屈折是 {@code hopped / hopping}）。
     *
     * <p>为什么 {@code x/w/y} 豁免：英语里这三个字母不双写（{@code fix → fixed}、
     * {@code snow → snowed}、{@code play → played}），它们的 3 字母词干是合法原形。
     * 长度 ≠ 3 的词干不受此门约束（{@code work / open / visit / need} 全放行）。
     */
    private static boolean bareStemAllowed(String stem) {
        if (stem.length() != 3) {
            return true;
        }
        char first = stem.charAt(0);
        char mid = stem.charAt(1);
        char last = stem.charAt(2);
        if (isVowel(first) || !isVowel(mid) || isVowel(last)) {
            return true;
        }
        return last == 'x' || last == 'w' || last == 'y';
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    /**
     * 双写辅音去重：{@code stopp → stop} / {@code runn → run} / {@code travell → travel}。
     *
     * <p>只处理辅音 —— 元音双写（{@code see} / {@code too}）不是屈折加双写，去重要造出错词。
     * 结果长度不足 {@value #MIN_STEM_CHARS} 时返回 null（由调用方跳过该候选）。
     */
    private static String undouble(String stem) {
        int n = stem.length();
        if (n < MIN_STEM_CHARS) {
            return null;
        }
        char last = stem.charAt(n - 1);
        if (last != stem.charAt(n - 2)) {
            return null;
        }
        if ("aeiou".indexOf(last) >= 0) {
            return null;
        }
        return stem.substring(0, n - 1);
    }
}
