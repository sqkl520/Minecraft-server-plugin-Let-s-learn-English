package com.letslearnenglish.minecraftplugin.core.word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AnswerChecker - 唯一的三模式判分收敛点 (FEAT-MODE-01)。
 *
 * <p>spelling / choice / fill_blank 三个模式的判定规则、choice 选项生成、
 * 挖空句渲染全部收敛到这里，调用方（WordLearningManager / ChatAnswerListener /
 * WordLearningGUI / WordLearningListener）只做分流调用，不许各自写一份判定。
 */
public final class AnswerChecker {

    public static final String MODE_SPELLING = "spelling";
    public static final String MODE_CHOICE = "choice";
    public static final String MODE_FILL_BLANK = "fill_blank";

    /** choice 选项固定 4 个：1 正确 + 3 干扰项（modes.* 未接线，写死，见 dead-config-audit.md）。 */
    public static final int CHOICE_OPTION_COUNT = 4;

    /** choice 选项字母，与下标一一对应。 */
    public static final char[] CHOICE_LETTERS = {'A', 'B', 'C', 'D'};

    private AnswerChecker() {
    }

    /**
     * 按 mode 判定玩家答案是否正确。
     *
     * <ul>
     *   <li>spelling：全词拼写精确匹配（trim + equalsIgnoreCase，原逻辑原样保留）。</li>
     *   <li>choice：玩家输入选项字母（A/B/C/D，不区分大小写），命中正确项下标即对；
     *       兼容直接输入正确单词全文（等价于拼对，新手友好）。</li>
     *   <li>fill_blank：填缺失词，规则复用 spelling（trim + equalsIgnoreCase）。</li>
     *   <li>其它 mode（含 review）：回落到 spelling 规则，保持旧行为。</li>
     * </ul>
     *
     * @param mode 当前会话 mode（大小写不敏感，null 按 spelling 处理）
     * @param currentWord 当前题目单词（非 null）
     * @param answer 玩家聊天栏输入（null 视为答错）
     * @param choiceOptions 本题 choice 选项（仅 choice 模式需要，null/空则回落拼写判定）
     * @return true = 答对
     */
    public static boolean check(String mode, Word currentWord, String answer,
                                List<Word> choiceOptions) {
        if (currentWord == null || answer == null) {
            return false;
        }
        String input = answer.trim();
        if (isChoice(mode)) {
            if (choiceOptions != null && !choiceOptions.isEmpty()) {
                int picked = letterToIndex(input);
                if (picked >= 0 && picked < choiceOptions.size()) {
                    Word option = choiceOptions.get(picked);
                    return option != null
                            && option.getEnglish().equalsIgnoreCase(currentWord.getEnglish());
                }
                // 非法字母回落：允许直接输入正确单词全文
                return currentWord.getEnglish().equalsIgnoreCase(input);
            }
            // 无选项快照时回落拼写判定（防 NPE，不静默判错）
            return currentWord.getEnglish().equalsIgnoreCase(input);
        }
        // spelling / fill_blank / review / 未知：全词拼写判定
        return currentWord.getEnglish().equalsIgnoreCase(input);
    }

    /**
     * 生成 choice 选项：正确项 + 同词库随机干扰项，shuffle 后返回。
     * 词库过小導致凑不够 4 个时如实返回能凑到的数量，调用方按实际数量渲染。
     *
     * @param bank 同词库（取干扰项用，可为 null）
     * @param answer 本题答案词（非 null）
     * @return 打乱后的选项列表，至少包含答案本身
     */
    public static List<Word> buildChoiceOptions(WordBank bank, Word answer) {
        List<Word> options = new ArrayList<>(CHOICE_OPTION_COUNT);
        options.add(answer);
        if (bank != null) {
            List<Word> distractors = bank.getRandomWords(CHOICE_OPTION_COUNT - 1, answer);
            for (Word w : distractors) {
                if (w != null && !w.getEnglish().equalsIgnoreCase(answer.getEnglish())
                        && options.size() < CHOICE_OPTION_COUNT
                        && options.stream().noneMatch(o -> o.getEnglish().equalsIgnoreCase(w.getEnglish()))) {
                    options.add(w);
                }
            }
        }
        Collections.shuffle(options);
        return options;
    }

    /**
     * 找正确项在选项中的下标，找不到返回 -1。
     */
    public static int indexOfAnswer(List<Word> options, Word answer) {
        if (options == null || answer == null) {
            return -1;
        }
        for (int i = 0; i < options.size(); i++) {
            Word w = options.get(i);
            if (w != null && w.getEnglish().equalsIgnoreCase(answer.getEnglish())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 单字母（A/B/C/D，大小写不敏感）转下标；非法输入返回 -1。
     */
    public static int letterToIndex(String input) {
        if (input == null || input.length() != 1) {
            return -1;
        }
        char c = Character.toUpperCase(input.charAt(0));
        for (int i = 0; i < CHOICE_LETTERS.length; i++) {
            if (CHOICE_LETTERS[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 下标转字母（0-&gt;A），越界返回 '?'。
     */
    public static char indexToLetter(int index) {
        if (index < 0 || index >= CHOICE_LETTERS.length) {
            return '?';
        }
        return CHOICE_LETTERS[index];
    }

    /**
     * 把例句中的目标词挖空（大小写不敏感全词匹配，保留原标点）。
     * 例句缺失或不含目标词时回落为"_____"。
     */
    public static String blankSentence(Word word) {
        if (word == null) {
            return "_____";
        }
        String sentence = word.getExampleSentence();
        String target = word.getEnglish();
        if (sentence == null || sentence.isEmpty() || target == null || target.isEmpty()) {
            return "_____";
        }
        Pattern pattern = Pattern.compile("(?i)\\b" + Pattern.quote(target) + "\\b");
        Matcher matcher = pattern.matcher(sentence);
        if (matcher.find()) {
            return matcher.replaceAll("_____");
        }
        return "_____";
    }

    /**
     * 首字母提示（fill_blank 可选提示位）。目标词缺失时返回 "?"。
     */
    public static String firstLetterHint(Word word) {
        if (word == null || word.getEnglish() == null || word.getEnglish().isEmpty()) {
            return "?";
        }
        return String.valueOf(Character.toUpperCase(word.getEnglish().charAt(0)));
    }

    /**
     * 选项行渲染（聊天栏/GUI lore 共用格式）：{@code "A. apple"}。
     */
    public static String formatChoiceOption(int index, Word option) {
        String text = option != null ? option.getEnglish() : "?";
        return indexToLetter(index) + ". " + text;
    }

    public static boolean isSpelling(String mode) {
        return MODE_SPELLING.equalsIgnoreCase(mode);
    }

    public static boolean isChoice(String mode) {
        return MODE_CHOICE.equalsIgnoreCase(mode);
    }

    public static boolean isFillBlank(String mode) {
        return MODE_FILL_BLANK.equalsIgnoreCase(mode);
    }
}
