package com.letslearnenglish.minecraftplugin.core.word;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    /** R2：零宽字符（零宽空格/非连接/连接符 + BOM），输入通道最常见的隐形噪声。 */
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");

    /** R2：连续空白。覆盖 ASCII 空白 + 常见 Unicode 空格（NBSP、窄 NBSP、全角空格、
     *  U+2000~U+200A 系列），统一折叠为单个半角空格。零宽字符另行删除。 */
    private static final Pattern WHITESPACE =
            Pattern.compile("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+");

    /** R2：中文释义归一化时要去掉的末尾标点（句号/分号/逗号/顿号/冒号/括号残留等）。 */
    private static final Pattern DEFINITION_TRAILING =
            Pattern.compile("[\\s。．.,，、;；:：!！?？/／|｜()（）\\[\\]【】]+$");

    /** R2：中文释义的多义分隔符。按分号与斜杠切分——词库里大量释义含"（……，……）"式
     *  括号注释（如 "一个（不定冠词，指第一次提到的一个人或事物）"），按逗号切会把
     *  注释拆成无意义片段，制造假撞车。斜杠必须切：词库中 "这个/那个" 这类并列释义
     *  （如 the / either）若整条算一个义项，会与单义项 "这个"、"那个" 判为不撞车，
     *  从而在同题出现时构成双正确答案（P1 红队实测 the vs this 同现率 26%）。 */
    private static final Pattern DEFINITION_SEPARATOR = Pattern.compile("[;；/／]");

    /** R2：括号注释（全角/半角），词库里大量释义带用法说明，比较义项前先剔除。 */
    private static final Pattern DEFINITION_ANNOTATION = Pattern.compile("[（(][^（()）]*[)）]");

    /** R2：词性归一化时去掉首尾空白与常见缩写点（n. / adj. → n / adj）。 */
    private static final Pattern POS_TRAILING_DOT = Pattern.compile("[.\\s]+$");

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
     *   <li>spelling：全词拼写匹配（归一化 + 大小写按 caseSensitive）。</li>
     *   <li>choice：玩家输入选项字母（A/B/C/D，恒大小写不敏感），命中正确项下标即对；
     *       兼容直接输入正确单词全文（按 caseSensitive 做全词比较——新手友好路径）。</li>
     *   <li>fill_blank：填缺失词，规则复用 spelling（按 caseSensitive）。</li>
     *   <li>其它 mode（含 review）：回落到 spelling 规则，保持旧行为。</li>
     * </ul>
     *
     * <p>R2：所有文本输入先过 {@link #normalizeInput(String)}（NFKC / 去零宽 / 空白折叠），
     * 消除"全角字符、零宽字符、多余空格"这类与答案无关的输入通道噪声；判分标准仍是
     * 整词相等，不做同义/近似匹配，大小写语义不变。
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
        // R2：统一归一化入口（NFKC / 去零宽 / 空白折叠）。全角 Ａ 在此变为 A，
        // 后续全词比较与选项字母判定共用同一份干净文本。
        String input = normalizeInput(answer);
        if (isChoice(mode)) {
            if (choiceOptions != null && !choiceOptions.isEmpty()) {
                // P1-RT-1：单字母答案的歧义消解 —— 当答案词本身是单字母（词库里的 "a" / "I"）
                // 且玩家输入的正是该字母时，"按全文判对"与"按选项字母判"两种解读会冲突：
                // 若正确项并不在 A 位，玩家敲 "A" 会被全文分支判对（白拿分），
                // 或若正确项恰在 A 位，玩家敲 "a" 想选选项 A 也会被当成全文匹配。
                // 消解原则：**单字母输入一律优先按"选项字母"解读**（玩家在 choice 题里
                // 敲单个字母的第一直觉是选选项，而不是拼一个单词）；只有该字母没有对应
                // 选项（越界）或不是选项字母时，才回落到全文匹配。
                boolean singleLetterInput = input != null && input.length() == 1
                        && letterToIndex(input) >= 0;
                if (!singleLetterInput && wholeWordEquals(currentWord.getEnglish(), input, caseSensitive)) {
                    return true;
                }
                int picked = letterToIndex(input);
                if (picked >= 0 && picked < choiceOptions.size()) {
                    Word option = choiceOptions.get(picked);
                    // 选项文本来自词库、玩家输入已归一化；这里再用同一比较点兜住
                    // 词库中可能存在的全角/多余空白写法（仍恒大小写不敏感）。
                    return option != null
                            && wholeWordEquals(currentWord.getEnglish(), option.getEnglish(), false);
                }
                // 单字母输入但越界（小词库只有 A/B 却敲 C/D）：交调用方 isInvalidChoiceInput 拦；
                // 若调用方未拦，此处回落全文匹配，保证 "a" 作为答案时仍可输全词判对。
                if (singleLetterInput) {
                    return wholeWordEquals(currentWord.getEnglish(), input, caseSensitive);
                }
                // 越界选项字母 / 非字母且非正确全词：一律判为不正确。
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
        String input = normalizeInput(answer);
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
     * MC-3：全词文本比较 —— 两侧先做输入归一化（NFKC / 去零宽 / 空白折叠），
     * caseSensitive 时精确 equals，否则 equalsIgnoreCase。
     * 三模式判分链（spelling / fill_blank 整词 + choice 全词兜底）的唯一文本比较点。
     *
     * <p>R2 判分信号去噪：归一化只消除"输入通道噪声"（全角字符、零宽字符、多余空白），
     * 不做大小写转换（大小写仍由 caseSensitive 决定），也不做同义/拼写近似匹配——
     * 判分依然是"整词相等"，只是把玩家无法感知的格式差异从判分信号里剔除。
     */
    private static boolean wholeWordEquals(String expected, String input, boolean caseSensitive) {
        String left = normalizeInput(expected);
        String right = normalizeInput(input);
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return caseSensitive ? left.equals(right) : left.equalsIgnoreCase(right);
    }

    /**
     * R2：输入归一化（判分前唯一入口，幂等）。
     *
     * <ol>
     *   <li>NFKC 兼容分解再组合：全角 Ａ→A、全角空格 U+3000→普通空格、NBSP→空格、
     *       ﬁ/① 等兼容字符折叠为常规形式。</li>
     *   <li>删除零宽字符 U+200B~U+200D、U+FEFF（BOM / 复制粘贴残留）。</li>
     *   <li>trim + 连续空白（含换行、制表）折叠为单个半角空格。</li>
     * </ol>
     *
     * <p>不做大小写转换（由 {@code caseSensitive} 参数决定）；null 原样返回 null，
     * 全空白输入归一化为空串，由调用方按答错处理。
     */
    private static String normalizeInput(String raw) {
        if (raw == null) {
            return null;
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        // 先折叠再 trim：NBSP 等 Unicode 空格 trim() 不认，折叠成半角空格后才能被去净。
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return text;
    }

    /**
     * R2：判断"玩家只是格式问题"——判分已经按归一化结果给分，本方法只做归因，
     * 供上层决定是否额外提示"格式已自动纠正"（i18n 文案由上层提供）。
     *
     * <p>判定口径（三步，纯大小写差异不算格式问题）：
     * <ol>
     *   <li>原始输入与答案原文完全相等 → false（没有可归因的差异）。</li>
     *   <li>归一化后两者不相等（忽略大小写）→ false（是真的拼错）。</li>
     *   <li>归一化后完全相等 → true；归一化后仅大小写不同时，再看原始输入是否
     *       "忽略大小写也不等于答案"——是（如全角 ＡＰＰＬＥ 对 apple）才归因为格式。</li>
     * </ol>
     *
     * @param word 当前题目单词（可为 null，null 返回 false）
     * @param rawInput 玩家聊天栏原始输入（可为 null，null 返回 false）
     * @return true = 差异仅来自全角字符 / 零宽字符 / 多余空白
     */
    public static boolean isFormatOnlyMismatch(Word word, String rawInput) {
        if (word == null || rawInput == null || word.getEnglish() == null) {
            return false;
        }
        String expected = word.getEnglish();
        if (expected.equals(rawInput)) {
            return false;
        }
        String normalizedExpected = normalizeInput(expected);
        String normalizedInput = normalizeInput(rawInput);
        if (normalizedExpected == null || normalizedExpected.isEmpty()
                || normalizedInput == null
                || !normalizedExpected.equalsIgnoreCase(normalizedInput)) {
            return false;
        }
        if (normalizedExpected.equals(normalizedInput)) {
            return true;
        }
        // 归一化后只差大小写：若原始输入忽略大小写就已等于答案，那差异属于大小写
        // （判分语义问题，不是格式噪声），不归因为格式。
        return !expected.equalsIgnoreCase(rawInput);
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
     * 生成 choice 选项：正确项 + 同词库干扰项（R2 三重约束过滤），shuffle 后返回。
     * 词库过小导致凑不够 optionCount 个时如实返回能凑到的数量，调用方按实际数量渲染。
     *
     * <p>R2 干扰项三重约束（按优先级分桶，桶内 shuffle 后依次取，保证同一词多次出题
     * 干扰项不固定）：
     * <ol>
     *   <li><b>排除同释义</b>（硬约束）：候选中文与答案中文归一化后（NFKC / 去零宽 /
     *       去括号注释 / 去空白 / 去末尾标点）整条相等，或任一分号义项段完全相同
     *       → 不作为干扰项。否则会出现"两个正确选项"的废题（如 breath/breathe
     *       同为"呼吸"、little/small 同为"小的"）。</li>
     *   <li><b>优先同词性</b>：partOfSpeech 归一化相同（n. 与 n 视为相同）的候选排在前面。</li>
     *   <li><b>词形长度相近优先</b>：按 {@code |len(候选) - len(答案)|} 分档
     *       （≤2 / ≤5 / 其余），同档内随机。</li>
     * </ol>
     * 桶顺序 = (同词性?0:1, 长度档)，即 (0,0)(0,1)(0,2)(1,0)(1,1)(1,2)；前桶不足时
     * 自然由后桶补齐（词性或长度约束不强制，绝不因此少给选项）。全部干净候选耗尽
     * 仍不够 target 时，才从被释义约束排除的候选里按"轻度重叠 → 完全同义"补足。
     *
     * @param bank 同词库（取干扰项用，可为 null）
     * @param answer 本题答案词（非 null）
     * @param optionCount 目标选项数量（调用方保证已钳位到 [CHOICE_OPTION_MIN, CHOICE_OPTION_MAX]）
     * @return 打乱后的选项列表，至少包含答案本身
     */
    public static List<Word> buildChoiceOptions(WordBank bank, Word answer, int optionCount) {
        int target = Math.max(CHOICE_OPTION_MIN, Math.min(CHOICE_OPTION_MAX, optionCount));
        List<Word> options = new ArrayList<>(target);
        if (answer == null) {
            return options;
        }
        options.add(answer);
        if (bank == null) {
            return options;
        }

        // getAllWords() 返回防御拷贝（不可变）；这里复制一份可变列表供后续分桶，
        // 不修改 bank 内部状态。随机性由各桶内 shuffle 保证，不依赖这里的顺序。
        List<Word> pool = new ArrayList<>(bank.getAllWords());

        String answerEnglish = answer.getEnglish();
        String answerPos = normalizePos(answer.getPartOfSpeech());
        int answerLength = answerEnglish == null ? 0 : normalizeEnglishKey(answerEnglish).length();

        // 6 个优先级桶：下标 = posRank * 3 + lengthRank。
        List<List<Word>> tiers = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            tiers.add(new ArrayList<>());
        }
        // 兜底池（仅在干净候选不足时启用）：soft = 只有某个义项段撞车（语义重叠但
        // 未必等价，如 "干净的；打扫" vs "干净的；整洁的"）；hard = 整条释义完全相同
        // （必定双正确，最不该用，排在最后）。
        List<Word> softFallback = new ArrayList<>();
        List<Word> hardFallback = new ArrayList<>();
        String answerDefinition = normalizeDefinition(answer.getChinese());
        Set<String> answerSegments = definitionSegments(answer.getChinese());
        // 排除答案本身 + 同英文重复项（忽略大小写），与旧实现语义一致。
        Set<String> takenEnglish = new HashSet<>();
        takenEnglish.add(normalizeEnglishKey(answerEnglish));
        for (Word candidate : pool) {
            if (candidate == null || candidate.getEnglish() == null) {
                continue;
            }
            String key = normalizeEnglishKey(candidate.getEnglish());
            if (key.isEmpty() || !takenEnglish.add(key)) {
                continue;
            }
            // 硬约束：同释义候选默认不进正常池（否则构成两个正确选项）。
            if (definitionCollides(answerSegments, candidate.getChinese())) {
                if (!answerDefinition.isEmpty()
                        && answerDefinition.equals(normalizeDefinition(candidate.getChinese()))) {
                    hardFallback.add(candidate);
                } else {
                    softFallback.add(candidate);
                }
                continue;
            }
            int posTier = posRank(answerPos, candidate.getPartOfSpeech());
            int lengthTier = lengthRank(answerLength, key);
            tiers.get(posTier * 3 + lengthTier).add(candidate);
        }

        // 按桶优先级补齐；桶内先 shuffle，保证随机性（玩家无法背选项位置）。
        for (List<Word> tier : tiers) {
            if (options.size() >= target) {
                break;
            }
            Collections.shuffle(tier);
            for (Word candidate : tier) {
                if (options.size() >= target) {
                    break;
                }
                options.add(candidate);
            }
        }
        // 兜底：约束内候选耗尽仍不够 → 从被排除的候选补齐（先语义轻微重叠、后完全
        // 同义）。宁可让极端小词库出现一道不完美的题，也不破坏"凑满 optionCount"
        // 与"至少包含答案本身"的既有契约——正常词库（≥百词）永远不会走到这里。
        if (options.size() < target) {
            Collections.shuffle(softFallback);
            Collections.shuffle(hardFallback);
            for (Word candidate : softFallback) {
                if (options.size() >= target) {
                    break;
                }
                options.add(candidate);
            }
            for (Word candidate : hardFallback) {
                if (options.size() >= target) {
                    break;
                }
                options.add(candidate);
            }
        }
        Collections.shuffle(options);
        return options;
    }

    /**
     * R2：英文键归一化（NFKC + 去零宽 + 空白折叠 + 小写），仅用于"这是不是同一个词"的
     * 去重/排除判断，不参与判分（判分大小写由 caseSensitive 决定）。
     */
    private static String normalizeEnglishKey(String english) {
        String text = normalizeInput(english);
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /** R2：词性归一化（空白折叠 + 去首尾缩写点 + 小写），空/缺省返回空串。 */
    private static String normalizePos(String pos) {
        String text = normalizeInput(pos);
        if (text == null || text.isEmpty()) {
            return "";
        }
        return POS_TRAILING_DOT.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** R2：词性优先档位，0 = 同词性（或答案无词性信息，此时该约束无意义），1 = 其它。 */
    private static int posRank(String answerPos, String candidatePos) {
        if (answerPos.isEmpty()) {
            return 0;
        }
        return answerPos.equals(normalizePos(candidatePos)) ? 0 : 1;
    }

    /** R2：词形长度档位，0 = 差 ≤2，1 = 差 ≤5，2 = 其余。 */
    private static int lengthRank(int answerLength, String candidateEnglish) {
        int diff = Math.abs(candidateEnglish.length() - answerLength);
        if (diff <= 2) {
            return 0;
        }
        return diff <= 5 ? 1 : 2;
    }

    /**
     * R2：中文释义归一化 —— NFKC/零宽/空白处理后：剔除括号注释 → 去掉全部空白 →
     * 去掉末尾标点。
     * 例：" 小的。 " → "小的"；"是；存在（动词原形）" → "是;存在"。
     */
    private static String normalizeDefinition(String chinese) {
        String text = normalizeInput(chinese);
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = DEFINITION_ANNOTATION.matcher(text).replaceAll("");
        // 整条释义都被括号包住时不要归零，否则该词会被当成"无释义"而漏掉真撞车。
        if (!WHITESPACE.matcher(stripped).replaceAll("").isEmpty()) {
            text = stripped;
        }
        text = WHITESPACE.matcher(text).replaceAll("");
        return DEFINITION_TRAILING.matcher(text).replaceAll("");
    }

    /**
     * R2：把中文释义拆成"义项集合"（按分号切分，每段各自归一化，丢弃空段）。
     * 单义释义返回单元素集合；释义缺失返回空集。
     */
    private static Set<String> definitionSegments(String chinese) {
        String full = normalizeDefinition(chinese);
        Set<String> segments = new HashSet<>();
        if (full.isEmpty()) {
            return segments;
        }
        for (String part : DEFINITION_SEPARATOR.split(full)) {
            String seg = normalizeDefinition(part);
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }
        return segments;
    }

    /**
     * R2：判断候选释义是否与答案释义"撞车"（任一义项段完全相同即视为撞车）。
     * 答案义项集合由调用方预算一次（每道题只算一次，候选逐个比对）。
     * 释义缺失（null/空）时无从判断，返回 false（不影响旧行为）。
     *
     * @param answerSegments 答案释义的义项集合（{@link #definitionSegments} 结果，可为空集）
     * @param candidateChinese 候选词的中文释义
     */
    private static boolean definitionCollides(Set<String> answerSegments, String candidateChinese) {
        if (answerSegments == null || answerSegments.isEmpty()) {
            return false;
        }
        Set<String> candidateSegments = definitionSegments(candidateChinese);
        if (candidateSegments.isEmpty()) {
            return false;
        }
        for (String segment : candidateSegments) {
            if (answerSegments.contains(segment)) {
                return true;
            }
        }
        return false;
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
            // R2：与判分同口径（归一化后比较，仍大小写不敏感），保证"选中正确项"与
            // check 的判定结果一致（全角/空白写法差异不会导致定位失败）。
            if (w != null && wholeWordEquals(answer.getEnglish(), w.getEnglish(), false)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 单字母（A/B/C/D，大小写不敏感）转下标；非法输入返回 -1。
     *
     * <p>R2：入口先做输入归一化，玩家用全角 Ａ 或带零宽字符的输入也能命中选项；
     * 仍保持"字母选择恒不敏感"的既有语义（不做额外大小写规则变更）。
     */
    public static int letterToIndex(String input) {
        String normalized = normalizeInput(input);
        if (normalized == null || normalized.length() != 1) {
            return -1;
        }
        char c = Character.toUpperCase(normalized.charAt(0));
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
