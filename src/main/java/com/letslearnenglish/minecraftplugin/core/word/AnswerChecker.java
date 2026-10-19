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
 *
 * <p>MC-3（modes.* 接线）：判分与选项生成增加 caseSensitive / optionCount 参数化重载，
 * 值由调用方从会话快照（QuizState）或 WordModes 配置读取传入；旧签名保留并委托
 * 默认值（不敏感 / 4 选项），既有调用与测试零改动。case-sensitive 只影响全词文本
 * 比较（spelling / fill_blank 的整词判定与 choice 的"直接输全词"兜底）；choice 的
 * A/B/C/D 字母选择是下标选择器，恒大小写不敏感。
 */
public final class AnswerChecker {

    public static final String MODE_SPELLING = "spelling";
    public static final String MODE_CHOICE = "choice";
    public static final String MODE_FILL_BLANK = "fill_blank";

    /** choice 选项数量合法下限：&lt;2 时无干扰项、选择无意义。 */
    public static final int CHOICE_OPTION_MIN = 2;

    /** choice 选项数量合法上限（字母空间 A-D 的自然上限：选项超过 4 个没有可用的选择字母）。 */
    public static final int CHOICE_OPTION_MAX = 4;

    /** choice 选项默认数量：1 正确 + 3 干扰项。MC-3 接线后由 {@code modes.choice.option-count}
     *  覆盖（钳位 [MIN, MAX]）；本常量亦是旧签名 {@link #buildChoiceOptions(WordBank, Word)} 的委托值。 */
    public static final int CHOICE_OPTION_COUNT = CHOICE_OPTION_MAX;

    /** choice 选项字母，与下标一一对应。 */
    public static final char[] CHOICE_LETTERS = {'A', 'B', 'C', 'D'};

    private AnswerChecker() {
    }

    /**
     * 按 mode 判定玩家答案是否正确（旧签名，保留兼容）。
     *
     * <p>委托 {@link #check(String, Word, String, List, boolean)}，caseSensitive=false
     * （全词比较大小写不敏感＝接线前的历史行为）。
     */
    public static boolean check(String mode, Word currentWord, String answer,
                                List<Word> choiceOptions) {
        return check(mode, currentWord, answer, choiceOptions, false);
    }

    /**
     * 按 mode 判定玩家答案是否正确（MC-3 参数化签名）。
     *
     * <ul>
     *   <li>spelling：全词拼写匹配（trim + 大小写按 caseSensitive）。</li>
     *   <li>choice：玩家输入选项字母（A/B/C/D，恒大小写不敏感），命中正确项下标即对；
     *       兼容直接输入正确单词全文（按 caseSensitive 做全词比较——新手友好路径）。</li>
     *   <li>fill_blank：填缺失词，规则复用 spelling（按 caseSensitive）。</li>
     *   <li>其它 mode（含 review）：回落到 spelling 规则，保持旧行为。</li>
     * </ul>
     *
     * @param mode 当前会话 mode（大小写不敏感，null 按 spelling 处理）
     * @param currentWord 当前题目单词（非 null）
     * @param answer 玩家聊天栏输入（null 视为答错）
     * @param choiceOptions 本题 choice 选项（仅 choice 模式需要，null/空则回落拼写判定）
     * @param caseSensitive 全词文本比较是否区分大小写（choice 的字母选择不受影响）
     * @return true = 答对
     */
    public static boolean check(String mode, Word currentWord, String answer,
                                List<Word> choiceOptions, boolean caseSensitive) {
        if (currentWord == null || answer == null) {
            return false;
        }
        String input = answer.trim();
        if (isChoice(mode)) {
            if (choiceOptions != null && !choiceOptions.isEmpty()) {
                // R-56：先判"输入=正确单词全文"。单字母单词（如 "a"/"I"）的文本会与
                // 选项字母标签（A/B/...）相撞，必须让全词精确匹配先命中为对，否则
                // 玩家敲 "a" 会被当成"选 A"而误判；也顺带保留新手直接输全词的友好路径。
                // MC-3：全词兜底按 caseSensitive；字母选择（下方 letterToIndex）恒不敏感。
                if (wholeWordEquals(currentWord.getEnglish(), input, caseSensitive)) {
                    return true;
                }
                int picked = letterToIndex(input);
                if (picked >= 0 && picked < choiceOptions.size()) {
                    Word option = choiceOptions.get(picked);
                    return option != null
                            && option.getEnglish().equalsIgnoreCase(currentWord.getEnglish());
                }
                // 越界选项字母 / 非字母且非正确全词：一律判为不正确。
                // 其中"越界选项字母"的无效输入语义由 isInvalidChoiceInput 在调用方前置
                // 拦截（提示重答、不判错），不会走到这里；此处 false 仅兜底，不吞合法答案。
                return false;
            }
            // 无选项快照时回落拼写判定（防 NPE，不静默判错）
            return wholeWordEquals(currentWord.getEnglish(), input, caseSensitive);
        }
        // spelling / fill_blank / review / 未知：全词拼写判定
        return wholeWordEquals(currentWord.getEnglish(), input, caseSensitive);
    }

    /**
     * R-10：判断是否为 choice 模式下的"无效选项输入"（旧签名，保留兼容）。
     *
     * <p>委托 {@link #isInvalidChoiceInput(String, Word, String, List, boolean)}，
     * caseSensitive=false（＝接线前行为）。
     */
    public static boolean isInvalidChoiceInput(String mode, Word currentWord, String answer,
                                               List<Word> choiceOptions) {
        return isInvalidChoiceInput(mode, currentWord, answer, choiceOptions, false);
    }

    /**
     * R-10：判断是否为 choice 模式下的"无效选项输入"——玩家敲了一个落在当前
     * 可用选项范围之外的选项字母（例如小词库只有 A/B 两项却输入 C/D）。
     *
     * <p>这类输入既不是正确答案也不是一次有意义的"选错"，调用方应据此给出
     * "无效选项"提示并让玩家在剩余倒计时内重答，而不是当答错去清连击 / 扣分 /
     * 记错题本。判定顺序与 {@link #check} 一致：先排除"输入=正确单词全文"（那是对，
     * 不是无效；按 caseSensitive 比较），再看选项字母下标是否越界。非选项字母的乱敲
     * （如多字符的错词）仍按答错处理，不算无效。
     *
     * @param mode          当前会话 mode
     * @param currentWord   当前题目单词（可为 null）
     * @param answer        玩家输入
     * @param choiceOptions 本题选项快照
     * @param caseSensitive 全词文本比较是否区分大小写（与 {@link #check} 同口径，
     *                      保证"判分判对的输入在同一参数下也不会被判无效"）
     * @return true = 越界选项字母（无效输入，应提示重答而非判错）
     */
    public static boolean isInvalidChoiceInput(String mode, Word currentWord, String answer,
                                               List<Word> choiceOptions, boolean caseSensitive) {
        if (!isChoice(mode) || choiceOptions == null || choiceOptions.isEmpty() || answer == null) {
            return false;
        }
        String input = answer.trim();
        // 输入恰为正确单词全文 → 是对的答案，绝不算无效
        if (currentWord != null && wholeWordEquals(currentWord.getEnglish(), input, caseSensitive)) {
            return false;
        }
        int picked = letterToIndex(input);
        // picked 为 A~D 的下标（非字母返回 -1）；越过当前可用选项数 → 该字母没有
        // 对应选项 → 无效。picked<0（非选项字母）不算无效，交给 check 按答错处理。
        return picked >= 0 && picked >= choiceOptions.size();
    }

    /**
     * MC-3：全词文本比较 —— caseSensitive 时 trim 后精确 equals，否则 equalsIgnoreCase。
     * 三模式判分链（spelling / fill_blank 整词 + choice 全词兜底）的唯一文本比较点。
     */
    private static boolean wholeWordEquals(String expected, String input, boolean caseSensitive) {
        if (expected == null || input == null) {
            return false;
        }
        return caseSensitive ? expected.equals(input) : expected.equalsIgnoreCase(input);
    }

    /**
     * 生成 choice 选项（旧签名，保留兼容）：正确项 + 同词库随机干扰项，shuffle 后返回。
     *
     * <p>委托 {@link #buildChoiceOptions(WordBank, Word, int)}，optionCount=4
     * （＝接线前的固定行为）。
     */
    public static List<Word> buildChoiceOptions(WordBank bank, Word answer) {
        return buildChoiceOptions(bank, answer, CHOICE_OPTION_COUNT);
    }

    /**
     * 生成 choice 选项：正确项 + 同词库随机干扰项，shuffle 后返回。
     * 词库过小导致凑不够 optionCount 个时如实返回能凑到的数量，调用方按实际数量渲染。
     *
     * @param bank 同词库（取干扰项用，可为 null）
     * @param answer 本题答案词（非 null）
     * @param optionCount 目标选项数量（调用方保证已钳位到 [CHOICE_OPTION_MIN, CHOICE_OPTION_MAX]）
     * @return 打乱后的选项列表，至少包含答案本身
     */
    public static List<Word> buildChoiceOptions(WordBank bank, Word answer, int optionCount) {
        int target = Math.max(CHOICE_OPTION_MIN, Math.min(CHOICE_OPTION_MAX, optionCount));
        List<Word> options = new ArrayList<>(target);
        options.add(answer);
        if (bank != null) {
            List<Word> distractors = bank.getRandomWords(target - 1, answer);
            for (Word w : distractors) {
                if (w != null && !w.getEnglish().equalsIgnoreCase(answer.getEnglish())
                        && options.size() < target
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

    public static boolean isChoice(String mode) {
        return MODE_CHOICE.equalsIgnoreCase(mode);
    }

    public static boolean isFillBlank(String mode) {
        return MODE_FILL_BLANK.equalsIgnoreCase(mode);
    }
}
