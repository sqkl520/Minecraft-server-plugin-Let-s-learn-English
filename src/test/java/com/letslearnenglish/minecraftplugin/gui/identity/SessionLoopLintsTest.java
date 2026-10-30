package com.letslearnenglish.minecraftplugin.gui.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B2b（R3-b / R3-c）的<b>源码级 lint</b>（不是行为测试）。
 *
 * <p>这三条钉住的都是「单测里不可观测、但线上语义会静默退化」的实现细节：
 * <ol>
 *   <li><b>R3-c 分支归属</b>：{@code endSession} 的普通会话分支里，{@code resetReview}
 *       必须落在 {@code getIncorrectCount() > 0} 的条件块<b>内</b>。行为测试跑不起
 *       Bukkit 调度器，而"把 resetReview 挪到 if 外面"会让<b>答对</b>的词也被重置间隔 ——
 *       任何现有测试都看不见（{@code ReviewScheduler} 的调用在 {@code WordManagerImpl}
 *       内部，构造需要 plugin + 配置）。</li>
 *   <li><b>R3-b 接线</b>：{@code startSession} 必须真的调用
 *       {@code getRecentlyAnsweredWords}。少了这一行，{@code WordBank} 的排除逻辑
 *       完好无损、所有单测全绿，但功能整体不生效 —— 这正是"接线失踪"类缺陷的典型形态。</li>
 *   <li><b>红线：窗口不落库</b>：{@code savePlayerDataInternal} 方法体内不得出现
 *       {@code recentAnswerCache}。它是纯内存窗口；一旦被塞进写路径，每轮保存多一次
 *       无意义的 SQL 绑定，且让人误以为窗口跨重启保留。</li>
 * </ol>
 *
 * <p><b>本类与 {@code GuiWiringLintsTest} 零耦合</b>：{@code stripComments} /
 * {@code extractBody} 在这里各自独立实现一遍。复用会带来真实的维护陷阱 ——
 * 那边为「GUI 接线」调宽了剥注释的容错（例如把未闭合的 {@code /*} 当成注释结束），
 * 这三条 lint 会跟着静默失效，失败消息却指向另一个文件。重复几十行换来两组 lint
 * 的失效模式互相独立，是划算的。
 *
 * <p><b>失败消息约定</b>：每条都写明「这是语义锚点检查」并给出合法等价重构的同步方式。
 * lint 检查的是<b>文本形状</b>，等价重构会让它误红；误红必须由人裁决并同步 lint，
 * 绝不允许为了让 lint 过而改回错误实现。
 */
class SessionLoopLintsTest {

    // ==================================================================
    // 1. R3-c：resetReview 必须在 incorrectCount() > 0 的条件块内
    // ==================================================================

    /**
     * R3-c：普通学习会话里，答错的词必须走 {@code resetReview}（而不只是
     * {@code seedReview} —— 后者对已有种子的词是 no-op）。
     *
     * <p>断言分三层，缺一不可：
     * <ol>
     *   <li>普通会话的 {@code else} 分支（即含 {@code seedReview} 的那个块）里出现
     *       {@code resetReview}；</li>
     *   <li>{@code resetReview} 落在某个 {@code if (...getIncorrectCount() > 0...)}
     *       的 then 块<b>内</b>（括号配对求区间，防"if 上面一行直接调用"的摆拍）；</li>
     *   <li>未作答的 continue 门仍在 —— 没有它，stop 残留的词（correct+incorrect=0）
     *       会被当成"答对"播种子。</li>
     * </ol>
     */
    @Test
    @DisplayName("R3-c：endSession 普通分支里 resetReview 位于 incorrectCount() > 0 条件块内")
    void endSessionGuardsIncorrectWithResetReview() {
        String body = methodBody("WordManagerImpl.java",
                "public void endSession(UUID playerId)");

        // 普通会话分支 = 含 seedReview 的那个 else 块（review 分支只有 recordReview/resetReview）。
        int elseOpen = elseBlockContaining(body, "seedReview");
        assertTrue(elseOpen >= 0,
                "在 endSession 方法体里找不到含 seedReview 的 else 分支 喵。\n"
                        + "R3-c 要求普通学习会话结算保持「未作答跳过 + 答错 resetReview + 答对 seedReview」"
                        + "三态；整段被删掉会让普通会话答错后复习间隔分毫不动。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若把结算抽成辅助方法，请同步本 lint —— "
                        + "但三态判断必须留在调用链上。");

        int elseClose = findBlockByBraceMatching(body, elseOpen);
        assertTrue(elseClose > elseOpen, "普通会话分支的花括号没有配对结束喵，源码可能不完整");
        String elseBody = body.substring(elseOpen + 1, elseClose);

        int reset = elseBody.indexOf("resetReview");
        assertTrue(reset >= 0,
                "普通会话分支里没有 resetReview 喵。\n"
                        + "R3-c：此前该分支只调 seedReview，而 seedReview 对已有种子的词是 no-op"
                        + "（PlayerDataStore.seedReview 在 lastReviewTime 已存在时直接 return），"
                        + "所以「学过的词在普通会话里答错」复习间隔分毫不动，"
                        + "下次提醒仍按旧的长间隔走。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                        + "（例如统一走一个带 reset 语义的调用），请同步本 lint。");

        // 条件块：所有含 getIncorrectCount() > 0 的 if 的 then 区间。
        List<int[]> incorrectGuards = ifBlocksWithCondition(elseBody, "getIncorrectCount() > 0");
        assertFalse(incorrectGuards.isEmpty(),
                "普通会话分支里找不到 if (... getIncorrectCount() > 0 ...) 喵。\n"
                        + "R3-c 的三态必须由「答错」这个条件驱动：没有条件块，"
                        + "resetReview 一旦被无差别调用，答对的词也会被重置回 1h 间隔 —— "
                        + "玩家答对得越多、复习提醒反而越频繁。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若改成 switch / 谓词函数，请同步本 lint。");

        boolean insideGuard = false;
        for (int[] guard : incorrectGuards) {
            if (reset > guard[0] && reset < guard[1]) {
                insideGuard = true;
                break;
            }
        }
        assertTrue(insideGuard,
                "resetReview 不在 getIncorrectCount() > 0 的条件块<b>内</b> 喵"
                        + "（resetReview 偏移=" + reset + "，条件块区间=" + renderRanges(incorrectGuards) + "）。\n"
                        + "典型绕过形态：把 resetReview 写在 if 之前/之后一行（对每个答过的词都重置），"
                        + "再把 if 留着当摆设 —— 文本上不断言「在块内」就抓不到。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

        assertTrue(elseBody.contains("seedReview"),
                "普通会话分支里找不到 seedReview 喵 —— 答对且从未有过种子的词必须播下种子，"
                        + "否则它永远进不了复习池。");

        // continue 门：未作答的词（stop 残留）不碰复习状态。
        // 必须同时出现「correct + incorrect 求和的判据」与 continue —— 只看 continue
        // 太弱（任何 for 循环都可能因为别的原因带一个 continue）。
        boolean hasSkipGate = Pattern.compile(
                        "getCorrectCount\\s*\\(\\s*\\)\\s*\\+\\s*\\w+\\s*\\.\\s*getIncorrectCount\\s*\\(\\s*\\)\\s*==\\s*0")
                .matcher(elseBody).find()
                && Pattern.compile("\\bcontinue\\s*;").matcher(elseBody).find();
        assertTrue(hasSkipGate,
                "普通会话分支里找不到「未作答跳过」的门 喵"
                        + "（需要 correctCount()+incorrectCount()==0 判据 + continue）。\n"
                        + "没有它时，中途 stop 残留的词（correct+incorrect=0）会被当成答对播种子 —— "
                        + "玩家没答过的词凭空进入复习池；若答错的判定改成 >0 之外的形式，"
                        + "还会连带把未作答词也送去 resetReview。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");
    }

    // ==================================================================
    // 2. R3-b：startSession 必须取近期已答词并传给 WordBank
    // ==================================================================

    /**
     * R3-b：{@code startSession} 必须调用
     * {@code getRecentlyAnsweredWords(...)}，且把结果交给 3 参
     * {@code selectWordsForSession}。
     *
     * <p>为什么要 lint 而不只测 {@code WordBank}：{@link com.letslearnenglish.minecraftplugin.core.word.WordBank}
     * 的排除逻辑可以被 10 个用例钉得死死的，但<b>接线</b>少一行时全部照绿 ——
     * 排除集恒为空，功能整体不生效。这类"库改了、调用方没改"的缺陷只能靠源码断言抓。
     */
    @Test
    @DisplayName("R3-b：startSession 取 getRecentlyAnsweredWords 并传给 3 参 selectWordsForSession")
    void startSessionAppliesRecentExclusion() {
        String body = methodBody("WordManagerImpl.java",
                "public LearningSession startSession(Player player, String difficulty, String mode, int wordCount)");

        int call = body.indexOf("getRecentlyAnsweredWords");
        assertTrue(call >= 0,
                "startSession 方法体里没有 getRecentlyAnsweredWords 喵。\n"
                        + "R3-b：没有这一行时 WordBank 的排除逻辑永远收到空集 —— "
                        + "「刚答错就重抽同一题」的现象原样存在，而所有单测全绿。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若取值被抽成辅助方法"
                        + "（如 recentExclusion(player)），请同步本 lint —— 但取值必须留在本题的调用链上。");

        assertTrue(body.contains("60_000") || body.contains("60000"),
                "startSession 里没有把「分钟」换算成毫秒（* 60_000L）喵。\n"
                        + "getRecentlyAnsweredWords 的窗口参数是<b>毫秒</b>：直接把配置值传进去，"
                        + "30 会被当成 30ms，排除窗口实际等于不存在（刚答完立刻又能抽到）。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若换算被抽成辅助方法，请同步本 lint。");

        // 配置键的<b>字面量</b>检查必须走未剥注释的原文：stripComments 会把字符串内容
        // 抹成空格（本类其他断言正依赖这一点），所以键名在 body 里是查不到的。
        String rawSource = rawSourceOf("WordManagerImpl.java");
        assertTrue(rawSource.contains("\"word-system.recent-word-exclusion-minutes\""),
                "WordManagerImpl.java 里找不到配置键字面量 "
                        + "\"word-system.recent-word-exclusion-minutes\" 喵。\n"
                        + "配置键是运营侧的开关（0 = 关闭）：键名写错时 getInt 静默回落默认值，"
                        + "关不掉的排除窗口没有任何报错。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若键名迁移，请同步本 lint。");

        Matcher m = Pattern.compile("selectWordsForSession\\s*\\(([^;]*?)\\)\\s*;").matcher(body);
        boolean passesExclusion = false;
        String matchedArgs = "";
        while (m.find()) {
            String args = m.group(1);
            if (args.split(",").length >= 3) {
                passesExclusion = true;
                matchedArgs = args.strip();
            }
        }
        assertTrue(passesExclusion,
                "startSession 里没有调用 3 参 selectWordsForSession(count, mastered, excluded) 喵"
                        + (matchedArgs.isEmpty() ? "" : "（实际调用实参=" + matchedArgs + "）") + "。\n"
                        + "只调 2 参版本等于排除集恒空：从 PlayerDataStore 取来的 excluded 被丢掉，"
                        + "R3-b 整体失效。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                        + "（例如改走一个接受 excluded 的包装方法），请同步本 lint。");
    }

    // ==================================================================
    // 3. 红线：recentAnswerCache 绝不进 savePlayerDataInternal
    // ==================================================================

    /**
     * 红线：{@code savePlayerDataInternal} 方法体内不得出现 {@code recentAnswerCache}。
     *
     * <p>{@code recentAnswerCache} 是<b>纯内存</b>的近期已答词窗口，生命周期到
     * quit / reload 为止。把它绑进写路径的后果不是"多写一列"，而是：
     * ① 每个保存轮次多一次无意义的 SQL 绑定（窗口默认 30 分钟，绝大多数时间没有新词）；
     * ② 让后来的人误以为"窗口跨重启保留"，而实际不会 —— 这种错误预期会催生
     * "用窗口做成绩/复习判定"的二次缺陷。
     *
     * <p>为什么锚在 {@code savePlayerDataInternal} 而不是整个文件：{@code PlayerDataStore}
     * 里必然有对它的<b>合法</b>引用（字段声明、{@code updateWordProgress} 的写入、
     * {@code getRecentlyAnsweredWords} 的读取、两处清理）。全文件 grep 只会假红；
     * 只有"写库方法体"是禁区。
     */
    @Test
    @DisplayName("红线：savePlayerDataInternal 方法体内不得出现 recentAnswerCache")
    void recentAnswerCacheIsMemoryOnly() {
        String body = methodBody("PlayerDataStore.java",
                "private void savePlayerDataInternal(UUID playerId, Collection<String> wordFilter)");

        int hit = body.indexOf("recentAnswerCache");
        assertFalse(hit >= 0,
                "savePlayerDataInternal 方法体里出现了 recentAnswerCache 喵（偏移=" + hit + "）。\n"
                        + "B2b 红线：近期已答词窗口是纯内存态，不落库 —— 它既不该被读取拼进 "
                        + "word_progress 的列，也不该新增 SQL 绑定。\n"
                        + "把它塞进写路径的代价：① 每轮保存多一次无意义的绑定/语句；"
                        + "② 后人会误以为窗口跨重启保留（实际 quit/reload 即清），"
                        + "从而写出依赖错误前提的二次逻辑。\n"
                        + "这是语义锚点检查，比字符串匹配更严格；若确实需要持久化窗口，"
                        + "那是新需求（要建表 + 迁移 + 清理策略），请先与架构侧裁决，"
                        + "绝不允许用「顺手塞进 savePlayerDataInternal」实现。");

        // 反向锚点：写库方法体必须仍然碰 word_progress 与 player_data ——
        // 防止有人为了过上面这条断言把整个方法体掏空/改名。
        assertTrue(body.contains("word_progress") || body.contains("upsertStmt"),
                "savePlayerDataInternal 里找不到 word_progress / upsertStmt 喵 —— "
                        + "方法体可能被掏空或整体搬走了，上面的红线断言会因此静默失效。\n"
                        + "请把本 lint 的锚点同步到新的写库实现所在方法。");
    }

    // ==================================================================
    // 辅助方法（本类独立实现，刻意不复用 GuiWiringLintsTest）
    // ==================================================================

    /** 插件源码根；找不到直接 fail，绝不 skip。 */
    private static Path srcRoot() {
        Path root = Paths.get(System.getProperty("user.dir"), "src", "main", "java",
                "com", "letslearnenglish", "minecraftplugin");
        if (!Files.isDirectory(root)) {
            fail("找不到源码根 " + root.toAbsolutePath()
                    + " —— 测试须从仓库根目录运行喵。");
        }
        return root;
    }

    /** 按文件名在源码树里定位唯一源码，返回「剥注释 + 抹字面量」后的内容。 */
    private static String sourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }

        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动/改名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (hits.size() > 1) {
            fail("源码树里有 " + hits.size() + " 个 " + fileName + "，无法唯一定位喵。");
        }

        try {
            return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /** 按文件名在源码树里定位唯一源码，返回<b>未剥注释</b>的原文（字符串字面量可读）。 */
    private static String rawSourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }
        if (hits.size() != 1) {
            fail("源码树里 " + fileName + " 的命中数=" + hits.size() + "，无法唯一定位喵。");
        }
        try {
            return Files.readString(hits.get(0), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    private static String methodBody(String fileName, String signature) {
        return extractBody(sourceOf(fileName), signature);
    }

    /**
     * 按方法签名截出方法体（花括号之间的文本）。
     *
     * <p>签名必须在源码中<b>唯一出现</b>：重载请把参数表写全（{@code startSession}
     * 有 3 参与 4 参两个重载，本类锚的是 4 参那个 —— 真正的实现体）。
     */
    private static String extractBody(String strippedSource, String signature) {
        int sigIndex = strippedSource.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名/改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵 —— "
                    + "若新增了重载，请把本 lint 的锚点改成更精确的签名。");
        }

        int bodyStart = strippedSource.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            fail("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        String between = strippedSource.substring(sigIndex + signature.length(), bodyStart);
        if (between.indexOf(';') >= 0) {
            fail("方法签名「" + signature + "」与左花括号之间出现了分号，"
                    + "匹配到的可能不是方法体喵：" + between.strip());
        }

        int depth = 0;
        for (int i = bodyStart; i < strippedSource.length(); i++) {
            char c = strippedSource.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return strippedSource.substring(bodyStart + 1, i);
                }
            }
        }
        fail("方法签名「" + signature + "」的花括号没有配对结束喵 —— 源码可能不完整。");
        return ""; // 不可达
    }

    /** 从 {@code openBraceIdx} 处的左花括号出发，用括号计数找配对的右花括号。 */
    private static int findBlockByBraceMatching(String s, int openBraceIdx) {
        if (openBraceIdx < 0 || openBraceIdx >= s.length() || s.charAt(openBraceIdx) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = openBraceIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 从 {@code openIdx} 处的 {@code (} 出发找到配对的 {@code )}。 */
    private static int findMatchingParen(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int from) {
        int i = Math.max(0, from);
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }

    /**
     * 找出方法体内「{@code else} 块中含有 {@code needle}」的那个块的<b>左花括号下标</b>。
     *
     * <p>为什么用"内容定位"而不是"第 N 个 else"：{@code endSession} 里有
     * {@code if (session != null)} 与 {@code if ("review".equals(...))} 两层分支，
     * 按序数定位会在任何一次结构调整后指向另一个块 —— 那种 lint 的失败消息会误导人。
     * 用「哪个 else 分支里有 seedReview」定位，语义直接可读。
     */
    private static int elseBlockContaining(String body, String needle) {
        Matcher m = Pattern.compile("\\belse\\s*\\{").matcher(body);
        while (m.find()) {
            int braceOpen = body.indexOf('{', m.start());
            int braceClose = findBlockByBraceMatching(body, braceOpen);
            if (braceClose < 0) {
                continue;
            }
            if (body.substring(braceOpen + 1, braceClose).contains(needle)) {
                return braceOpen;
            }
        }
        return -1;
    }

    /**
     * 找出方法体内所有 {@code if (条件)} 且条件（去空白后）包含 {@code condition} 的
     * then 块区间 {@code [左花括号, 右花括号]}。
     *
     * <p>只认带花括号的 then 块：单语句 {@code if (x) doThing();} 无法用括号配对求区间，
     * 也就不该被当成"条件块内"的可信证据（保守方向，宁可误红）。
     */
    private static List<int[]> ifBlocksWithCondition(String body, String condition) {
        List<int[]> blocks = new ArrayList<>();
        String wanted = normalizeWhitespace(condition);
        Matcher m = Pattern.compile("\\bif\\s*\\(").matcher(body);
        while (m.find()) {
            int parenOpen = body.indexOf('(', m.start());
            int parenClose = findMatchingParen(body, parenOpen);
            if (parenClose < 0) {
                continue;
            }
            String cond = normalizeWhitespace(body.substring(parenOpen + 1, parenClose));
            if (!cond.contains(wanted)) {
                continue;
            }
            int braceOpen = skipWhitespace(body, parenClose + 1);
            if (braceOpen >= body.length() || body.charAt(braceOpen) != '{') {
                continue; // 单语句 if：不认
            }
            int braceClose = findBlockByBraceMatching(body, braceOpen);
            if (braceClose < 0) {
                continue;
            }
            blocks.add(new int[]{braceOpen, braceClose});
        }
        return blocks;
    }

    private static String renderRanges(List<int[]> ranges) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ranges.get(i)[0]).append("..").append(ranges.get(i)[1]);
        }
        return sb.append(']').toString();
    }

    /**
     * 剥掉 Java 注释，并把字符串/字符字面量的内容抹成空格（单遍字符扫描状态机）。
     *
     * <p>与 {@code GuiWiringLintsTest.stripComments} <b>刻意各写一份</b>（理由见类注释）。
     * 抹成空格（保留换行）而非删除，保证偏移与原文一致、失败消息里的偏移可直接对照源码；
     * 同时「注释里写的 recentAnswerCache」不会被误判成代码。
     */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // 1) 行注释
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            // 2) 块注释（未闭合则到文末）
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(source.charAt(j) == '*' && j + 1 < n
                        && source.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            // 3) 文本块 """
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j + 2 < n
                        && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
                        && source.charAt(j + 2) == '"')) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = (j + 2 < n) ? j + 3 : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            // 4) 普通字符串（遇换行视为未闭合，容错）
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                blank(out, i, j);
                i = j;
                continue;
            }

            // 5) 字符字面量
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '\'' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                blank(out, i, j);
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    /** 把 {@code [from, to)} 内的非换行字符替换为空格（保持偏移与行结构）。 */
    private static void blank(char[] out, int from, int to) {
        int end = Math.min(to, out.length);
        for (int k = Math.max(0, from); k < end; k++) {
            if (out[k] != '\n' && out[k] != '\r') {
                out[k] = ' ';
            }
        }
    }
}
