package com.letslearnenglish.minecraftplugin.core.word;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AnswerChecker#blankSentence} 的挖空渲染契约。
 *
 * <h2>为什么这组用例存在</h2>
 * 这个方法是 fill_blank 模式唯一的题面渲染点（{@code WordLearningGUI} 与
 * {@code WordLearningListener} 都调它），但在本次修复前<b>零测试</b>。于是它退化成
 * {@code \b原形\b} 一条正则、匹配不上就丢整句时，没有任何测试会红——只有玩家进游戏才会
 * 看到一道没有语境的裸空格题。实测全词库 1357 词里 208 个（15.3%）落到这条降级路径上。
 *
 * <h2>这组用例守什么</h2>
 * <ol>
 *   <li>规则屈折能挖掉（{@code work} → {@code works}）；</li>
 *   <li>不规则形能挖掉（{@code mean} → {@code meant}）；</li>
 *   <li>双写末辅音能挖掉（{@code commit} → {@code committed}）；</li>
 *   <li>短词<b>不</b>误挖（{@code a} 不能吃掉 {@code "As"} 的首字母）；</li>
 *   <li>大小写不敏感，且替换后保留原句的大小写外观与标点；</li>
 *   <li>四条降级路径（word 为 null / 例句为 null / 例句为空 / 一个形都没命中）仍回落 {@code "_____"}。</li>
 * </ol>
 *
 * <p>反向样本（第 4 条）是本类的核心：只断言"能挖掉"很容易写成永远为真的空断言，
 * 一旦有人把 {@link WordInflection#formsOf} 的短词阈值删掉，只有这一条会红。
 */
@DisplayName("AnswerCheckerBlankTest — fill_blank 挖空渲染")
class AnswerCheckerBlankTest {

    /** 挖空占位符。与 AnswerChecker 内部的字面量一致（这里是断言侧，必须是独立字面量）。 */
    private static final String BLANK = "_____";

    /** 造一个只关心 english + exampleSentence 的词条，其余字段与本组断言无关。 */
    private static Word word(String english, String sentence) {
        return new Word(english, "释义-" + english, "verb", "/test/",
                sentence, List.of(), List.of(), "beginner", 1);
    }

    @Test
    @DisplayName("规则屈折：work 挖掉 works，整句其余部分原样保留")
    void blanksRegularInflection() {
        // 实测词库原句（beginner/works）。接线前这条会回落成裸 "_____"。
        assertEquals("She _____ in a hospital.",
                AnswerChecker.blankSentence(word("work", "She works in a hospital.")));
    }

    @Test
    @DisplayName("规则屈折：-ing / -ed 形态同样能挖掉")
    void blanksIngAndEd() {
        // 去尾 e 词干的 -ing：move → moving（实测语料原句）。
        assertEquals("A fish swims by _____ its tail.",
                AnswerChecker.blankSentence(word("move", "A fish swims by moving its tail.")));
        // 去尾 e 词干的 -ed：create → created。
        assertEquals("They _____ a new club.",
                AnswerChecker.blankSentence(word("create", "They created a new club.")));
    }

    @Test
    @DisplayName("不规则形：mean 挖掉 meant（IRREGULAR 表生效）")
    void blanksIrregularForm() {
        assertEquals("Humans were never _____ to live forever.",
                AnswerChecker.blankSentence(word("mean", "Humans were never meant to live forever.")));
    }

    @Test
    @DisplayName("不规则形：bad 挖掉 worst（一对多映射里取表中靠前的命中项）")
    void blanksIrregularComparative() {
        assertEquals("You should be ready for the _____.",
                AnswerChecker.blankSentence(word("bad", "You should be ready for the worst.")));
    }

    @Test
    @DisplayName("双写末辅音：commit 挖掉 committed")
    void blanksDoubledConsonant() {
        assertEquals("She _____ to finish the course on time.",
                AnswerChecker.blankSentence(word("commit", "She committed to finish the course on time.")));
    }

    @Test
    @DisplayName("短词不误匹配：a 遇到 \"As …\" 必须回落裸空格，不能挖掉 A")
    void shortLemmaDoesNotMatchLongerWord() {
        // 红队 F1 踩过的坑：a 的规则屈折会生成 as，于是 "As I was leaving" 被判为含 a，
        // 玩家会看到 "___ I was leaving" —— 正确答案根本不是那个 A。
        assertEquals(BLANK,
                AnswerChecker.blankSentence(word("a", "As I was leaving, the phone rang.")));
    }

    @Test
    @DisplayName("短词仍能挖掉自己的原形（阈值不是把短词整体禁用）")
    void shortLemmaStillMatchesItself() {
        assertEquals("I saw _____ cat on the wall.",
                AnswerChecker.blankSentence(word("a", "I saw a cat on the wall.")));
    }

    @Test
    @DisplayName("大小写不敏感：原句首字母大写与全大写都要命中")
    void matchesRegardlessOfCase() {
        assertEquals("_____ hard every day.",
                AnswerChecker.blankSentence(word("work", "Work hard every day.")));
        assertEquals("They _____ as a team.",
                AnswerChecker.blankSentence(word("work", "They WORK as a team.")));
        // 词条本身大小写不敏感同样成立（词库理论上可能出现首字母大写写法）。
        assertEquals("She _____ in a hospital.",
                AnswerChecker.blankSentence(word("Work", "She works in a hospital.")));
    }

    @Test
    @DisplayName("替换保留原标点：句中的逗号 / 句号 / 问号不受影响")
    void keepsPunctuation() {
        assertEquals("Have you _____ the news?",
                AnswerChecker.blankSentence(word("hear", "Have you heard the news?")));
    }

    @Test
    @DisplayName("降级路径：word 为 null、例句为 null、例句为空都回落裸空格")
    void fallsBackOnMissingInput() {
        assertEquals(BLANK, AnswerChecker.blankSentence(null));
        assertEquals(BLANK, AnswerChecker.blankSentence(word("work", null)));
        assertEquals(BLANK, AnswerChecker.blankSentence(word("work", "")));
    }

    @Test
    @DisplayName("降级路径：例句不含任何候选词形时回落裸空格")
    void fallsBackWhenNoFormMatches() {
        // "The weather is nice." 里没有 work 的原形、规则屈折或不规则形。
        assertEquals(BLANK,
                AnswerChecker.blankSentence(word("work", "The weather is nice.")));
        // 部分匹配不算命中：句子只有 "workshop" 这一个词，\bwork\b / \bworks\b 都不该命中 ——
        // 这条同时守住"别退化成子串匹配"（str.replace("work", "_____") 会把句子挖成 "_____shop"）。
        assertEquals(BLANK,
                AnswerChecker.blankSentence(word("work", "He runs a small workshop.")));
    }

    @Test
    @DisplayName("自检：屈折层真的参与了判定（若 formsOf 退化成只返回原形，本用例会红）")
    void inflectionLayerIsActuallyEngaged() {
        String sentence = "She works in a hospital.";
        // 前置断言：句子里确实没有 work 这个独立词。没有它，本用例可能因为原形命中而"碰巧"
        // 通过，那就证明不了屈折层参与了。
        assertFalse(Pattern.compile("\\bwork\\b").matcher(sentence).find(),
                "例句里原形 work 本不该出现，这条前置断言防止用例自身失去区分力");
        assertNotEquals(BLANK, AnswerChecker.blankSentence(word("work", sentence)),
                "挖空成功只能来自屈折层 —— 例句里没有 work 的原形");
    }
}
