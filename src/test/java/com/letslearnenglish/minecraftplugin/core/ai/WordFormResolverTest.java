package com.letslearnenglish.minecraftplugin.core.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9 · {@link WordFormResolver} 纯逻辑断言。
 *
 * <p>词库谓词用<b>显式集合</b>而不是真实词库 —— 本类只关心「后缀形态 + inBank 命中」两条
 * 规则，用集合能把每条规则的边界钉死，不受词库后续增删影响。集合里刻意包含实测碰撞对
 * （{@code new} / {@code not} / {@code your} / {@code be} / {@code she} / {@code the} /
 * {@code us}），用来验证「假朋友 / 短词干 / 静音 e」三道防线确实生效。
 */
class WordFormResolverTest {

    /** 测试词库：刻意收进会被机械剥离误伤的原形。 */
    private static final Set<String> BANK = Set.of(
            // 正常原形
            "study", "watch", "box", "go", "do", "be", "work", "love", "stop", "run",
            "make", "book", "it", "echo", "use", "hope", "travel", "cry", "try", "carry",
            "play", "close", "shed", "sing", "wish", "singe", "care",
            // 实测碰撞对里的「受害者」原形
            "new", "not", "your", "she", "we", "the", "i", "us", "he");

    private static final Predicate<String> IN_BANK = BANK::contains;

    /** 临时词库谓词（单条用例里只想放 1-2 个原形时用，避免为此另建静态集合）。 */
    private static Predicate<String> inBankOf(String... words) {
        return Set.of(words)::contains;
    }

    // ============================================================
    //  正向：各派生规则
    // ============================================================

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // -s
            "books, book",
            "goes, go",
            "does, do",
            "plays, play",
            "uses, use",
            // -es（词干以 s/x/z/o/ch/sh 结尾）
            "watches, watch",
            "boxes, box",
            "echoes, echo",
            "wishes, wish",
            // -ies
            "studies, study",
            "tries, try",
            "carries, carry",
            // -ed
            "worked, work",
            "played, play",
            "loved, love",
            "stopped, stop",
            "travelled, travel",
            "closed, close",
            // -ied
            "studied, study",
            "tried, try",
            "carried, carry",
            // -ing
            "working, work",
            "making, make",
            "running, run",
            "studying, study",
            "closing, close",
            "playing, play",
            "singing, sing",
            "singeing, singe",
    })
    void testDerivationRules(String token, String expected) {
        assertEquals(expected, WordFormResolver.resolve(token, IN_BANK));
    }

    @Test
    void testDirectHitIsPreferredOverStripping() {
        // 原形直接命中 → 原样返回，绝不剥后缀
        assertEquals("book", WordFormResolver.resolve("book", IN_BANK));
        assertEquals("glass", WordFormResolver.resolve("glass", inBankOf("glass", "glas")),
                "glass 本身在词库时原样返回，不剥成 glas");
        assertEquals("shed", WordFormResolver.resolve("shed", IN_BANK),
                "shed 本身在词库时不应被剥成 she");
        assertEquals("box", WordFormResolver.resolve("box", IN_BANK));
        assertEquals("it", WordFormResolver.resolve("it", IN_BANK));
        assertEquals("echo", WordFormResolver.resolve("echo", IN_BANK),
                "原形 echo 直接命中，不走 -es 还原");
    }

    @Test
    void testCaseAndWhitespaceAreNormalized() {
        assertEquals("book", WordFormResolver.resolve("  Books  ", IN_BANK));
        assertEquals("go", WordFormResolver.resolve("GOES", IN_BANK));
        assertEquals("study", WordFormResolver.resolve("Studies", IN_BANK));
    }

    @Test
    void testSilentEBaseAcceptsUseAndHope() {
        // used → use（3 字母、韵脚 se 在放行集内）、hoped → hope（4 字母静音 e）
        assertEquals("use", WordFormResolver.resolve("used", IN_BANK));
        assertEquals("hope", WordFormResolver.resolve("hoped", IN_BANK));
        assertEquals("hope", WordFormResolver.resolve("hoping", IN_BANK));
        assertEquals("close", WordFormResolver.resolve("closed", IN_BANK));
    }

    @Test
    void testUndoubleOnlyForConsonants() {
        // runn → run（辅音双写去重生效）
        assertEquals("run", WordFormResolver.resolve("running", IN_BANK));
        // see 是元音双写，不是屈折加双写 —— 去重要造出 "se"，必须拒绝
        assertNull(WordFormResolver.resolve("seeing", IN_BANK),
                "seeing 不得得到 se（元音双写不去重），且 BANK 无 see");
    }

    // ============================================================
    //  负向：实测误判候选必须全部安全（R9 冻结契约）
    // ============================================================

    @Test
    void testFrozenMisclassificationCandidatesReturnNull() {
        // 这 5 个是 R9 侦察实测的误判候选 —— 任何规则改动都必须让它们继续返回 null。
        assertNull(WordFormResolver.resolve("went", IN_BANK),
                "went 是不规则过去式，无表 → null（不是 go 的规则派生式）");
        assertNull(WordFormResolver.resolve("children", IN_BANK),
                "children 是不规则复数 → null");
        assertNull(WordFormResolver.resolve("bus", IN_BANK),
                "-us 词尾不剥离（BANK 里也没有 bu）");
        assertNull(WordFormResolver.resolve("glass", IN_BANK),
                "-ss 词尾不剥离（BANK 里也没有 glas）");
        assertNull(WordFormResolver.resolve("is", IN_BANK),
                "-is 词尾不剥离（is 不是 be 的规则派生式）");
    }

    @Test
    void testFalseFriendsAreNeverStripped() {
        // 假朋友闭集：机械剥离会指向词库里的另一个词
        assertNull(WordFormResolver.resolve("news", IN_BANK), "news 不是 new 的复数");
        assertNull(WordFormResolver.resolve("yours", IN_BANK), "yours 是物主代词，不是 your 的复数");
        assertNull(WordFormResolver.resolve("its", IN_BANK), "its 不是 it 的复数");
        assertNull(WordFormResolver.resolve("ours", IN_BANK));
        assertNull(WordFormResolver.resolve("theirs", IN_BANK));
        assertNull(WordFormResolver.resolve("hers", IN_BANK));
    }

    @Test
    void testShortStemGuardBlocksThreeLetterCollisions() {
        // 「剥完只剩 2 字母且恰好是词条」的实测碰撞，MIN_STEM_CHARS 必须挡住
        assertNull(WordFormResolver.resolve("bees", IN_BANK), "bees 的 -es 词干 be 只有 2 字母，不剥离");
        assertNull(WordFormResolver.resolve("notes", IN_BANK),
                "notes 的 -es 词干 not 形状不合 -es 规则；-s 分支得到 note（不在 BANK）→ null");
    }

    @Test
    void testSilentEGuardBlocksSheCollision() {
        // shed → she 是 3 字母韵脚碰撞；静音 e 门必须拒绝（韵脚 "he" 不在放行集）
        assertNull(WordFormResolver.resolve("shed", inBankOf("she")), "shed 不应剥成 she");
        assertNull(WordFormResolver.resolve("sheds", inBankOf("she")), "sheds 不应剥成 she");
        // 对照：4 字母静音 e 词基是合法的
        assertEquals("hope", WordFormResolver.resolve("hoped", inBankOf("hope")));
    }

    @Test
    void testShortIngStemDoesNotTryPlusE() {
        // thing → the 是「短词干 + e」分支的经典误伤；已按「短词干只试原样」封死
        assertNull(WordFormResolver.resolve("thing", inBankOf("the")),
                "thing 的 3 字母词干 th 不得补 e 成 the");
        assertNull(WordFormResolver.resolve("bring", inBankOf("the")),
                "bring 不得还原成 the");
        // 对照：长词干 + e 仍然生效
        assertEquals("make", WordFormResolver.resolve("making", inBankOf("make")));
    }

    // ============================================================
    //  FIX-H3：RT-H1 红队实测的 4 条 P1 假阳性（全部人造 Predicate，不依赖真实词库）
    // ============================================================

    // ---------- P1-A：seed → see ----------

    @Test
    void testSeedDoesNotCollapseToSee() {
        // seed 走的是 -ed 分支（字面以 "ed" 结尾），不是 -s 分支。
        // 旧实现放行 silentE 词基 "see"（3 字母、韵脚 "ee" 在 SHORT_E_RIMES 内）→ 命中 see。
        // seed（种子）与 see（看见）语义无关，必须 null。
        assertNull(WordFormResolver.resolve("seed", inBankOf("see")),
                "seed 不得被 -d 剥离成 see（seed 是独立实词，不是 see 的屈折）");
        assertNull(WordFormResolver.resolve("seed", inBankOf("see", "free", "tree")),
                "词库里有 see/free/tree 也不得把 seed 剥成 see");
        // 对照：真屈折不受影响
        assertEquals("see", WordFormResolver.resolve("sees", inBankOf("see")), "sees → see 是正常复数/三单");
        assertEquals("sees", WordFormResolver.resolve("sees", inBankOf("sees")), "原词命中优先");
    }

    @Test
    void testFourLetterEeWordBasesStillStripped() {
        // 收紧只针对「3 字母 ee 韵脚」；4 字母及以上静音 e 词基照常（freed/agreed）
        assertEquals("free", WordFormResolver.resolve("freed", inBankOf("free")));
        assertEquals("agree", WordFormResolver.resolve("agreed", inBankOf("agree")));
    }

    // ---------- P1-B：toes → to ----------

    @Test
    void testOesWhitelistRejectsToes() {
        // 旧实现的 -oes 例外只卡 MIN_LEMMA_CHARS(=2)，把 toes 剥成介词 to。
        assertNull(WordFormResolver.resolve("toes", inBankOf("to")),
                "toes 不得剥成介词 to（2 字母词干必须落在 OES_LEMMAS 白名单）");
        assertNull(WordFormResolver.resolve("foes", inBankOf("fo")),
                "foes 同理不得剥成 fo");
        // 对照：真实的 2 字母 -oes 屈折白名单放行
        assertEquals("go", WordFormResolver.resolve("goes", inBankOf("go")));
        assertEquals("do", WordFormResolver.resolve("does", inBankOf("do")));
        // 3 字母及以上 o 尾词干走通用规则，不受白名单影响
        assertEquals("echo", WordFormResolver.resolve("echoes", inBankOf("echo")));
    }

    // ---------- P1-C：cared → car（优先级反转）----------

    @Test
    void testSilentEBeforeStripTwoInEdBranch() {
        // 核心：care 与 car 同时在库时，必须是 care（-d 剥离），不能是 car（-ed 剥离）。
        // RT-H1 实测旧实现即使 care 在库也返回 car —— 这是分支顺序问题，不是数据稀疏。
        assertEquals("care", WordFormResolver.resolve("cared", inBankOf("care", "car")),
                "cared 的正确词干是 care（静音 e + d），不是 car");
        assertEquals("care", WordFormResolver.resolve("cared", inBankOf("care")));
        // 只有 car 在库时：care 不在库，strip(2) 的 car 又被 CVC 双写门挡掉
        // （单音节 CVC 动词 stop→stopped 必须双写，所以 "cared" 不可能是 car 的屈折）→ null。
        // 真实词库里恰好只有 car 没有 care，这正是生产环境 cared→car 假阳性的来源。
        assertNull(WordFormResolver.resolve("cared", inBankOf("car")),
                "car 是单音节 CVC，其 -ed 形必为 carred/carred 式的双写；cared 不得还原成 car");
        assertNull(WordFormResolver.resolve("noted", inBankOf("not")),
                "同理：not 是 CVC，noted 不得还原成 not");
        assertNull(WordFormResolver.resolve("caring", inBankOf("car")),
                "caring 同理不得还原成 car（car 的 -ing 形应为 carring）");
        assertEquals("note", WordFormResolver.resolve("noted", inBankOf("note")));
        // 对照：常规 -ed / 双写去重不受 CVC 门影响
        assertEquals("work", WordFormResolver.resolve("worked", inBankOf("work")));
        assertEquals("need", WordFormResolver.resolve("needed", inBankOf("need")),
                "need 已以 d 结尾，need+ed 不需要双写去重");
        assertEquals("stop", WordFormResolver.resolve("stopped", inBankOf("stop")));
        assertEquals("open", WordFormResolver.resolve("opened", inBankOf("open")));
        assertEquals("visit", WordFormResolver.resolve("visited", inBankOf("visit")));
        assertEquals("play", WordFormResolver.resolve("played", inBankOf("play")),
                "play 以 y 结尾不双写，3 字母词干必须放行");
        assertEquals("fix", WordFormResolver.resolve("fixed", inBankOf("fix")),
                "fix 以 x 结尾不双写，3 字母词干必须放行");
        assertEquals("snow", WordFormResolver.resolve("snowed", inBankOf("snow")));
        assertEquals("dance", WordFormResolver.resolve("danced", inBankOf("dance")));
    }

    @Test
    void testHopHopeAmbiguityDocumentedOrder() {
        // -ing 分支「原样词干优先」，但常规词干要过 CVC 双写门：hop 是 CVC，
        // 其 -ing 形应为 hopping，因此 hoping 不再落到 hop，而是走补 e 得到 hope。
        // 这正是 P1-C 的同一条拼写规则，让 hop/hope 歧义在无 POS 数据时也解对了。
        assertEquals("hope", WordFormResolver.resolve("hoping", inBankOf("hop", "hope")));
        assertEquals("hope", WordFormResolver.resolve("hoping", inBankOf("hope")));
        assertEquals("hop", WordFormResolver.resolve("hopping", inBankOf("hop")));
    }

    // ---------- P1-D：using → us ----------

    @Test
    void testUsingDoesNotCollapseToPronounUs() {
        // 旧实现 -ing 分支第一个候选 stem 没有 stemUsable 守卫，us 无条件进候选。
        assertNull(WordFormResolver.resolve("using", inBankOf("us")),
                "using 不得还原成代词 us");
        assertNull(WordFormResolver.resolve("using", inBankOf("us", "car")),
                "无关词在库不影响结论");
        // 补 e 候选 use 只有在库时才命中（use 在真实词库里不在，此处用人造词库验证规则）
        assertEquals("use", WordFormResolver.resolve("using", inBankOf("use")));
        assertEquals("use", WordFormResolver.resolve("using", inBankOf("us", "use")),
                "us 与 use 同时在库时必须选 use");
        // 对照：2 字母白名单内的真实屈折仍生效
        assertEquals("go", WordFormResolver.resolve("going", inBankOf("go")));
        assertEquals("do", WordFormResolver.resolve("doing", inBankOf("do")));
        assertEquals("be", WordFormResolver.resolve("being", inBankOf("be")));
        // 对照：3 字母及以上正常屈折
        assertEquals("work", WordFormResolver.resolve("working", inBankOf("work")));
        assertEquals("run", WordFormResolver.resolve("running", inBankOf("run")));
        assertEquals("make", WordFormResolver.resolve("making", inBankOf("make")));
        assertEquals("sing", WordFormResolver.resolve("singing", inBankOf("sing")));
    }

    @Test
    void testShortIngStemWhitelistIsExhaustive() {
        // 不被白名单放行的 2 字母词干不得进候选（防止未来有人把阈值改回 >=2）
        for (String token : new String[]{"using", "thing", "eating", "allowing"}) {
            String stem = token.substring(0, token.length() - 3);
            if (stem.length() < 3) {
                assertNull(WordFormResolver.resolve(token, inBankOf(stem)),
                        token + " 的短词干 " + stem + " 不在白名单，不得命中");
            }
        }
    }

    // ============================================================
    //  FIX-H3 回归网：全部派生规则的正向锚点（人造词库）
    // ============================================================

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "books, book",
            "goes, go",
            "does, do",
            "working, work",
            "running, run",
            "making, make",
            "needed, need",
            "stopped, stop",
            "singing, sing",
            "cared, care",
            "noted, note",
            "using, use",
    })
    void testRegressionAnchorsAfterFixH3(String token, String expected) {
        // 单条原形的人造词库：验证规则本身，不受词库里其他词干扰
        assertEquals(expected, WordFormResolver.resolve(token, inBankOf(expected)));
    }

    // ============================================================
    //  边界：谓词 / 输入
    // ============================================================

    @Test
    void testEmptyBankNeverProducesResult() {
        Predicate<String> empty = w -> false;
        for (String token : new String[]{"books", "goes", "studied", "running", "book", "worked"}) {
            assertNull(WordFormResolver.resolve(token, empty),
                    "inBank 恒 false 时绝不返回非空: " + token);
        }
    }

    @Test
    void testNullAndEmptyInputsAreSafe() {
        assertNull(WordFormResolver.resolve(null, IN_BANK));
        assertNull(WordFormResolver.resolve("", IN_BANK));
        assertNull(WordFormResolver.resolve("   ", IN_BANK));
        assertNull(WordFormResolver.resolve("books", null), "inBank 为 null 时恒 null");
    }

    @Test
    void testOverlongTokenRejected() {
        String longToken = "a".repeat(60);
        assertNull(WordFormResolver.resolve(longToken, w -> true));
    }

    @Test
    void testNonWordTokensAreNotResolved() {
        // 句子 / 中文 / 数字开头：形态层就不该产出
        assertNull(WordFormResolver.resolve("the books", w -> true));
        assertNull(WordFormResolver.resolve("书籍", w -> true));
        assertNull(WordFormResolver.resolve("123s", w -> true));
    }

    @Test
    void testSingleLetterWordsAreKnownLimitation() {
        // 单字母词在形态层就被 MIN_LEMMA_CHARS 拒绝 —— 这是<b>有意的</b>：单词级正则要求
        // ≥2 字母，a / i 永远进不了纠错闭环。resolve 自身保持一致（不返回单字母「原形」），
        // 避免调用方绕过正则直接喂单字母时拿到一个长度非法的词。
        assertNull(WordFormResolver.resolve("i", inBankOf("i")));
        assertNull(WordFormResolver.resolve("a", inBankOf("a")));
        // "is" 的剥离永远不指向 i（-is 词尾保护）
        assertNull(WordFormResolver.resolve("is", inBankOf("i")));
        // "aes" 也不会剥成 a（词干长度门）
        assertNull(WordFormResolver.resolve("aes", inBankOf("a")));
    }

    @Test
    void testResultIsAlwaysInsideBank() {
        // 核心契约：返回值必须是 inBank 接受的词
        Predicate<String> bank = Set.of("study", "go", "work", "run", "make")::contains;
        for (String token : new String[]{"studied", "goes", "worked", "running", "making", "went"}) {
            String resolved = WordFormResolver.resolve(token, bank);
            assertTrue(resolved == null || bank.test(resolved),
                    "resolve(" + token + ") 返回了词库外的词: " + resolved);
        }
    }

    @Test
    void testNeverReturnsCandidateLongerThanInputPlusOne() {
        // 弱不变量：还原只会「减后缀 / 加一个 e」，不会无故变长（防未来规则失控）
        for (String token : new String[]{"studies", "watches", "worked", "running", "books"}) {
            String resolved = WordFormResolver.resolve(token, IN_BANK);
            assertTrue(resolved == null || resolved.length() <= token.length() + 1,
                    "resolve(" + token + ") 返回了异常长的候选: " + resolved);
        }
    }
}
