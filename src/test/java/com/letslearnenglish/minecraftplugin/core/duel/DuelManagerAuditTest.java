package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.core.word.Word;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 23 轮审计：{@code DuelManager}（1760 行，jacoco 行覆盖 2.5%）的<b>纯逻辑可测面</b>。
 *
 * <p><b>本类不追求覆盖率</b>，只钉住这几件事：
 * <ol>
 *   <li><b>胜负判定与"平局"的真实语义</b> —— 这套子系统<b>没有</b>平局：
 *       {@link DuelRoom#WIN_SCORE} 未达即 {@code isFinished() == false}，
 *       而回合上限到了走 {@code cancelDuelInternal}（不判胜负、不记战绩）。
 *       把这条钉下来，是因为"平局发什么奖"这类问题在代码里根本没有对应实现，
 *       下一个人不该再去翻 1700 行找它。</li>
 *   <li><b>回合上限 / 超时 / 抢答三条互斥路径的边界</b> —— 它们是
 *       {@code advanceOrEnd} 与 {@code handleWordTimeout} 的全部判定依据。</li>
 *   <li><b>结构 lint</b> —— 钉住「决斗不发钱」「决斗不写学习进度」这两条<b>存在即缺陷</b>
 *       的架构不变量，防止未来有人把 {@code RewardEngine} 接进来后悄悄过掉日限。</li>
 *   <li><b>设计决策护栏（A2 改写）</b> —— 「停盘不解除占用」不是缺陷：{@code isInDuel}
 *       刻意不看 {@code paused}，论证在 {@code DuelRoom.java:204}。见
 *       {@link #pausedDuelKeepsOccupancyAndRejectsBothDirections()}。</li>
 *   <li><b>接线 lint（R26）</b> —— 纯值类 {@link DuelGraceOutcome} 有测试 ≠ 生产代码调了它：
 *       {@code DuelGraceOutcomeTest} 全是纯函数用例，把 {@code DuelManager} 那行
 *       {@code decide(...)} 删掉它照样全绿。见
 *       {@link #duelGraceOutcomeMustBeWiredAndOwnItsKeys()}。</li>
 * </ol>
 *
 * <p><b>为什么这些测试都跑在纯 JVM 上</b>：{@code DuelRoom} 只依赖 {@link UUID} /
 * {@link Word}，零 Bukkit。{@code DuelManager} 的判定全部内联在需要
 * {@code Player}/{@code Scheduler} 的方法里，除了反射没有便宜的驱动面 ——
 * 那部分归"可测性分界"报告，不在本类伪造。
 *
 * <p><b>源码 lint 的 cwd 前提</b>：{@link #readMainSource} 用相对路径
 * {@code src/main/java/...}，工作目录必须是项目根；在别处跑会红成「找不到源码文件」，
 * 与代码改动无关。
 */
class DuelManagerAuditTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-00000000d101");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-00000000d102");

    /** DuelRoom 只读 getEnglish()；其余字段给最小合法值（签名沿用 DuelRoomPauseTest）。 */
    private static Word word(String en) {
        return new Word(en, "中文", "n.", "/" + en + "/", "a " + en + " here",
                List.of(), List.of(), "beginner", 1);
    }

    private static DuelRoom room() {
        return new DuelRoom(P1, "Alice", P2, "Bob");
    }

    // ==================================================================
    // 1. 胜负判定：这套子系统没有"平局"，也没有任何平局分支
    // ==================================================================

    @Test
    @DisplayName("未打满 WIN_SCORE 时 getWinner/getLoser 均为 null —— 不存在可发奖的平局态")
    void noWinnerBeforeWinScore() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));

        assertNull(r.getWinner(), "0 分时不得有胜者");
        assertNull(r.getLoser(), "0 分时不得有负者");
        assertFalse(r.isFinished(), "0 分时不得判定结束");

        // 4:4 —— 双方都差一分。这是最像"平局"的一刻，语义上仍然是谁都没赢。
        for (int i = 0; i < 4; i++) {
            r.setCurrentWord(word("w" + i));
            assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P1, "w" + i));
            r.setCurrentWord(word("v" + i));
            assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P2, "v" + i));
        }
        assertEquals(4, r.getScore1());
        assertEquals(4, r.getScore2());
        assertNull(r.getWinner(), "4:4 不是胜者 —— 决斗没有平局判定，只有'还没结束'");
        assertNull(r.getLoser(), "4:4 也不是负者");
        assertFalse(r.isFinished(), "4:4 必须继续打");
    }

    @Test
    @DisplayName("先到 WIN_SCORE 即定胜负；getWinner/getLoser 互补且与比分一致")
    void winnerAndLoserAreComplementary() {
        DuelRoom r = room();
        for (int i = 0; i < DuelRoom.WIN_SCORE; i++) {
            r.setCurrentWord(word("w" + i));
            assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P1, "w" + i));
        }
        assertTrue(r.isFinished(), "打满 WIN_SCORE 必须判定结束");
        assertEquals(P1, r.getWinner());
        assertEquals(P2, r.getLoser());
        assertEquals(DuelRoom.WIN_SCORE, r.getScore1());
        assertEquals(0, r.getScore2());
        assertSame(P2, r.getOpponent(P1), "对手映射必须与胜负映射自洽");
        assertSame(P1, r.getOpponent(P2));
    }

    @Test
    @DisplayName("答错把分送给对手 —— 这是设计，不是 bug")
    void wrongAnswerFeedsOpponent() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));
        assertEquals(DuelRoom.AnswerResult.WRONG, r.submitAnswer(P1, "banana"),
                "答错不得静默丢弃，必须走 WRONG 让对手得分");
        assertEquals(0, r.getScore1(), "答错方不加分");
        assertEquals(1, r.getScore2(), "答错方必须把这一分送给对手（这是设计，不是 bug）");
    }

    @Test
    @DisplayName("同一题只结算一次：先答者定生死，后答者恒 ALREADY_ANSWERED")
    void onlyFirstAnswerCounts() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));

        assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P1, "apple"));
        assertEquals(DuelRoom.AnswerResult.ALREADY_ANSWERED, r.submitAnswer(P2, "apple"),
                "抢答语义：第一个答对的人锁定本题，后到者不得翻盘");
        assertEquals(1, r.getScore1());
        assertEquals(0, r.getScore2(), "后答者一分为不得");
    }

    // ==================================================================
    // 2. 回合上限：到点是"作废"，不是"判负"
    // ==================================================================

    @Test
    @DisplayName("回合上限按已发题数计；MAX_ROUNDS 前恒未到点")
    void roundCapCountsDealtWords() {
        DuelRoom r = room();
        assertFalse(r.isRoundCapReached(), "一题未发时不得到点");

        for (int i = 1; i < DuelRoom.MAX_ROUNDS; i++) {
            r.setCurrentWord(word("w" + i));
            assertEquals(i, r.getWordRoundId(), "wordRoundId 必须等于已发题数");
            assertFalse(r.isRoundCapReached(), "第 " + i + " 题时不该到点");
        }
        r.setCurrentWord(word("last"));
        assertEquals(DuelRoom.MAX_ROUNDS, r.getWordRoundId());
        assertTrue(r.isRoundCapReached(), "发满 MAX_ROUNDS 题必须到点");
    }

    @Test
    @DisplayName("到点时双方分数都远未达标 —— 上限是独立于胜负的第二条终止路径")
    void capCanFireWithoutAnyWinner() {
        DuelRoom r = room();
        // 30 题全超时（不计分）：这是「双方挂机」的真实形状。
        for (int i = 0; i < DuelRoom.MAX_ROUNDS; i++) {
            long round = r.setCurrentWord(word("w" + i));
            assertTrue(r.timeoutCurrentWord(round), "无人作答时超时必须生效");
        }
        assertTrue(r.isRoundCapReached(), "挂机 30 题后必须到点");
        assertFalse(r.isFinished(), "到点 != 有人赢了 —— 所以只能走 cancel，不能走 endDuel");
        assertNull(r.getWinner(), "挂机到点没有胜者（这正是 cancelDuelInternal 存在的理由）");
        assertEquals(0, r.getScore1());
        assertEquals(0, r.getScore2());
    }

    // ==================================================================
    // 3. 单题超时的竞态语义
    // ==================================================================

    @Test
    @DisplayName("超时不计分（与答错相反）—— 掉线方不会因超时白送对手一分")
    void timeoutNeverScores() {
        DuelRoom r = room();
        long round = r.setCurrentWord(word("apple"));

        assertTrue(r.timeoutCurrentWord(round));
        assertEquals(0, r.getScore1(), "超时对双方都是 0 分");
        assertEquals(0, r.getScore2(), "超时不得像答错那样送分");
        assertFalse(r.isFinished());
    }

    @Test
    @DisplayName("陈旧超时（round 已推进）必须被拒 —— 否则旧计时器会封死新题")
    void staleTimeoutIsRejected() {
        DuelRoom r = room();
        long oldRound = r.setCurrentWord(word("apple"));
        r.setCurrentWord(word("banana"));       // 推进一题
        assertFalse(r.timeoutCurrentWord(oldRound),
                "旧 round 的超时不得作用于新题 —— 这是 selfCancel 之外的第二道闸");

        // 且新题必须仍可正常作答（没被封住）
        assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P1, "banana"));
    }

    @Test
    @DisplayName("已答过的题超时输给答题 —— 先答者不被超时抹掉")
    void answeredRoundWinsAgainstTimeout() {
        DuelRoom r = room();
        long round = r.setCurrentWord(word("apple"));
        assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P1, "apple"));

        assertFalse(r.timeoutCurrentWord(round), "已作答的题超时必须是 no-op");
        assertEquals(1, r.getScore1(), "超时不得回滚已得分");
    }

    @Test
    @DisplayName("超时封题后，迟到答案返回 ALREADY_ANSWERED 而非 CORRECT")
    void lateAnswerAfterTimeoutIsSealed() {
        DuelRoom r = room();
        long round = r.setCurrentWord(word("apple"));
        assertTrue(r.timeoutCurrentWord(round));

        assertEquals(DuelRoom.AnswerResult.ALREADY_ANSWERED, r.submitAnswer(P1, "apple"),
                "超时后用哨兵 UUID 封题，迟到正确答案不得翻盘");
        assertEquals(0, r.getScore1());
    }

    @Test
    @DisplayName("超时封题后双方都答不了，且当前词仍保留（供超时播报揭示答案）")
    void timeoutSealsRoundButKeepsWord() {
        DuelRoom r = room();
        long round = r.setCurrentWord(word("apple"));
        assertTrue(r.timeoutCurrentWord(round));
        assertNotNull(r.getCurrentWord(), "超时后必须保留当前词，超时播报要用它揭示答案");
        assertEquals(DuelRoom.AnswerResult.ALREADY_ANSWERED, r.submitAnswer(P1, "apple"));
        assertEquals(DuelRoom.AnswerResult.ALREADY_ANSWERED, r.submitAnswer(P2, "apple"));
    }

    // ==================================================================
    // 4. 房间活性与"成员表 ≠ 进行中"
    // ==================================================================

    @Test
    @DisplayName("setActive(false) 后一切答案被拒 —— 拆解窗口不得再计分")
    void inactiveRoomRejectsEverything() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));
        r.setActive(false);

        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, r.submitAnswer(P1, "apple"),
                "已拆解房间不得再计分（endDuel 先置 active=false、cleanup 后才摘键）");
        assertFalse(r.isFinished());
    }

    @Test
    @DisplayName("非房间成员提交答案返回 NOT_IN_DUEL，且不改任何分数")
    void nonMemberIsRejected() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));
        UUID outsider = UUID.fromString("00000000-0000-0000-0000-00000000d1ff");

        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, r.submitAnswer(outsider, "apple"));
        assertEquals(0, r.getScore1());
        assertEquals(0, r.getScore2());
    }

    @Test
    @DisplayName("null 答案归一化为空串走 WRONG（不抛 NPE，且对手得分）")
    void nullAnswerIsNormalizedNotNpe() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));

        assertEquals(DuelRoom.AnswerResult.WRONG, r.submitAnswer(P1, null),
                "null 答案必须走 WRONG 而非 NPE（F2-07 的安全网）");
        assertEquals(1, r.getScore2(), "空答案 = 答错 = 对手得分");
    }

    @Test
    @DisplayName("答案判分 trim + 忽略大小写 —— 与学习侧口径一致")
    void answerMatchingIsTrimmedAndCaseInsensitive() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));
        assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P1, "  ApPlE  "));
        assertEquals(1, r.getScore1());
    }

    @Test
    @DisplayName("setCurrentWord 会清掉上一题的作答锁 —— 新题必须能答题")
    void newWordResetsAnswerLock() {
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));
        assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P1, "apple"));

        r.setCurrentWord(word("banana"));
        assertEquals(DuelRoom.AnswerResult.CORRECT, r.submitAnswer(P2, "banana"),
                "换题必须解锁，否则第二题起全员 ALREADY_ANSWERED，对局静默挂死");
        assertEquals(1, r.getScore1());
        assertEquals(1, r.getScore2());
    }

    // ==================================================================
    // 5. 架构不变量（结构 lint）：决斗不发钱、不写学习进度
    // ==================================================================

    /**
     * <b>存在即缺陷</b>：决斗一旦经 {@code RewardEngine} 发钱，就会过每日金币上限
     * （{@code money.daily-cap}）并写进学习侧台账；一旦直发 Vault，就绕开全部审计。
     * 当前实现<b>一分钱都不发</b>（唯一的持久化副作用是 {@code duel_stats} 一行 +
     * {@code duel_first_win} 成就）。本用例钉住这个"没有"。
     */
    @Test
    @DisplayName("lint：duel 包内不得出现任何发钱/发奖调用（发钱会过日限、污染学习侧台账）")
    void duelPackageMustNotPayMoney() throws Exception {
        String src = readMainSource("core/duel/DuelManager.java");
        for (String forbidden : new String[]{
                "RewardEngine", "addCoins", "depositPlayer", "getEconomy()",
                "rewardPlayer", "payout"}) {
            assertFalse(src.contains(forbidden),
                    "DuelManager 不得引用 " + forbidden
                            + " —— 决斗发奖会过日限、写学习侧台账；如需发奖必须先有预算/审计设计");
        }
    }

    /**
     * <b>存在即缺陷</b>：决斗中的答题若同时计入 {@code word_progress} / 每日挑战 /
     * 错题本，就是<b>数据污染</b> —— 玩家可以在决斗里刷学习进度（无需开学习会话、
     * 无 token 成本、可两人对刷）。当前实现刻意<b>完全不碰</b>学习侧数据。
     */
    @Test
    @DisplayName("lint：duel 包内不得写 word_progress / 每日挑战 / 错题本")
    void duelPackageMustNotWriteLearningProgress() throws Exception {
        String src = readMainSource("core/duel/DuelManager.java");
        for (String forbidden : new String[]{
                "PlayerDataStore", "DailyChallengeManager", "WrongBook",
                "recordAnswer", "word_progress", "getProgressManager()"}) {
            assertFalse(src.contains(forbidden),
                    "DuelManager 不得引用 " + forbidden
                            + " —— 决斗中的答题不得计入学习进度/每日挑战/错题本（数据污染）");
        }
    }

    /**
     * 决斗的<b>唯一</b>持久化副作用是 {@code recordDuelResult}，且必须发生在异步线程上：
     * 它是同步阻塞写、全程持 {@code connectionLock}，主线程调用会在磁盘慢/MySQL 抖动时卡 TPS。
     */
    @Test
    @DisplayName("lint：战绩落库必须经 SchedulerGuard.runAsync，不得在主线程直调")
    void duelStatsWriteMustBeAsync() throws Exception {
        String src = readMainSource("core/duel/DuelManager.java");
        int asyncIdx = src.indexOf("SchedulerGuard.runAsync(plugin");
        assertTrue(asyncIdx > 0,
                "战绩落库必须经 SchedulerGuard.runAsync（关服窗口会静默放弃，不冒泡进结算链）");
        int writeIdx = src.indexOf("db.recordDuelResult(");
        assertTrue(writeIdx > asyncIdx,
                "recordDuelResult 必须在 runAsync 的 lambda 内 —— 同步阻塞写不得落在主线程");
    }

    /**
     * {@code endDuel} 的 {@code cleanup(room)} 必须在 {@code finally} 里：
     * 任一步（成就/播报/落库/计分板）抛异常都必须摘掉 {@code activeDuels} 双键，
     * 否则双方永久 busy，且 {@code isBusy} 会让之后所有邀请被拒 —— 只能重启修复。
     */
    @Test
    @DisplayName("lint：endDuel 的 cleanup 必须在 finally（异常也不得让双方永久 busy）")
    void endDuelCleansUpInFinally() throws Exception {
        String src = readMainSource("core/duel/DuelManager.java");
        int endIdx = src.indexOf("public void endDuel(DuelRoom room)");
        assertTrue(endIdx > 0, "endDuel 必须存在");
        String body = src.substring(endIdx, src.indexOf("\n    /**", endIdx));
        assertTrue(body.contains("finally"),
                "endDuel 必须有 finally 兜底 cleanup —— 否则异常会让双方永久 busy");
    }

    /** 读真实源文件（工作目录 = 项目根）。路径拼装集中在这里，便于未来搬目录时一处改。 */
    private static String readMainSource(String relPathUnderJava) throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/" + relPathUnderJava);
        assertTrue(java.nio.file.Files.exists(p),
                "必须读真实源文件（工作目录应为项目根）: " + p.toAbsolutePath());
        return java.nio.file.Files.readString(p);
    }

    /**
     * 剥掉 {@code //} 行注释、{@code /* *}{@code /} 块注释（含 Javadoc）与文本块定界，
     * 但<b>保留字符串字面量的内容</b> —— 本类的两条 lint 要检的正是「i18n 键有没有被
     * 硬写进代码」，字面量是证据，剥掉它等于把证据也删了。
     *
     * <p><b>为什么必须剥注释</b>：本仓踩过坑 —— 旧 A2 判据 {@code body.contains("paused")}
     * 能被<b>一行注释</b>满足；反过来，{@code DuelManager} 里有 5 处 javadoc 提到
     * {@code duel.lost-by-disconnect} / {@code duel.duel-already-over}，不剥注释就会把
     * 「文档里解释了这个键」误判成「代码里硬写了这个键」，反向断言随即假红。
     *
     * <p>逐字符扫描而非正则：正则处理 {@code "http://..."} 这类含 {@code //} 的字面量
     * 必定出错，而本仓库大量 i18n 键正好以 {@code //} 之外的形态出现在字符串里。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            // 文本块：DuelManager 今天没有，但复制这段代码的地方将来可能有。
            if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                out.append("\"\"\"");
                i += 3;
                while (i < n) {
                    if (src.charAt(i) == '"' && i + 2 < n
                            && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                        out.append("\"\"\"");
                        i += 3;
                        break;
                    }
                    out.append(src.charAt(i++));
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        out.append(d).append(src.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    out.append(d);
                    i++;
                    if (d == quote) break;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i < n) {
                    if (src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    // 保留换行，避免拼接后行粘连（不影响子串匹配，只为失败消息可读）。
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 取 {@code signature} 之后第一对花括号内的<b>方法体文本</b>（配平括号）。
     *
     * <p>为什么不能 {@code indexOf(signature) + 400}：旧 A2 用的就是这个固定窗口 ——
     * 窗口既可能截断到隔壁方法（假绿：隔壁方法里出现了被找的词），也可能跨过方法体
     * （假红）。配平括号只认结构，重构加一行注释/空行不会让它漂移。
     *
     * <p><b>调用前必须先剥注释</b>（{@link #stripComments}）：本方法不做字符串感知，
     * 注释里一个孤立的 {@code '{'} 就会让配平走偏。
     */
    private static String methodBody(String code, String signature) {
        int sig = code.indexOf(signature);
        assertTrue(sig >= 0, "源码里找不到方法签名：" + signature
                + " —— 改名/搬家后请同步本 lint 的锚点，不要直接删断言喵。");
        int open = code.indexOf('{', sig);
        assertTrue(open > sig, "方法签名后没有方法体（是不是写成了接口/抽象方法？）：" + signature);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("花括号不配平，无法定位方法体：" + signature);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ==================================================================
    // 6. 缺陷复现（A1 已修复并转绿；A2 已裁决改写成设计决策护栏；A3 已由 D2 修复并转绿）
    // ==================================================================

    /**
     * <b>缺陷 A1（MEDIUM）—— 第 23 轮已修复</b>：{@code quitDuel} 不排队离线通告 ——
     * 决斗者<b>断线期间</b>被对手 {@code /le duel quit} 收掉的房间，该玩家重连后<b>零反馈</b>。
     *
     * <h2>为什么这是缺陷而不是"合理的裁剪"</h2>
     * 同一份代码对另外两条拆解路径都做了补发：
     * <ul>
     *   <li>{@code cancelDuelByAdmin}（:1213/:1222）—— 离线方排队 {@code cancelled-by-gm}；</li>
     *   <li>{@code handleDisconnect} 宽限到点（:1023/:1037）—— 排队
     *       {@code lost-by-disconnect} / {@code duel-already-over}。</li>
     * </ul>
     * 唯独 {@code quitDuel}（:858-883）只给<b>在线</b>的对手发消息，离线一方什么也拿不到。
     * 而 {@code handleReconnect} 的门（:1070-1071，{@code disconnectTimers.remove} 返回 null
     * 就 return）在 {@code cleanup}（:1413-1414，把双方 {@code disconnectTimers} 条目都摘掉）
     * 之后恒为 null —— 所以重连路径也不会补一句。
     *
     * <h2>复现时间线</h2>
     * <pre>
     * t=0    A 断线            → handleDisconnect(A) 起 30s 宽限，room.paused=true
     * t=5    B 输入 /le duel quit → quitDuel(B)：给 B 发 forfeit，room.setActive(false)，cleanup
     *         └ cleanup 把 A、B 的 disconnectTimers 都摘掉；A 的宽限任务被 cancel
     * t=10   A 重连            → handleReconnect(A)：disconnectTimers.remove(A) == null → 静默 return
     *         └ 玩家 A 看到：一个已经消失的对局界面、分数不变、聊天栏一句话都没有
     * </pre>
     *
     * <h2>修复与本用例的关系</h2>
     * 修复是在 {@code quitDuel} 的 {@code if (opponent != null && opponent.isOnline())}
     * 上补 {@code else} 分支排队新键 {@code duel.opponent-quit-while-disconnected}。
     * <b>行为</b>验收在 {@link DuelQuitOfflineNoticeTest}（它驱动真实 {@code quitDuel}，
     * 并把「在线时也排队」与「不排队」两个变异方向都钉住）。本用例只保留<b>结构</b>断言：
     * 「排队调用必须落在 else 分支里」—— 因为纯 {@code body.contains(...)} 会被
     * 一行注释满足，也会被「放在 if 里、在线时也排队」满足。
     */
    @Test
    @DisplayName("缺陷 A1（已修）：quitDuel 必须在对手离线的 else 分支里排队通告")
    void quitDuelShouldNotifyOfflineOpponent() throws Exception {
        String src = readMainSource("core/duel/DuelManager.java");
        int quitIdx = src.indexOf("public void quitDuel(Player player)");
        assertTrue(quitIdx > 0, "quitDuel 必须存在");
        int nextMethod = src.indexOf("\n    /**", quitIdx);
        String body = src.substring(quitIdx, nextMethod > 0 ? nextMethod : src.length());

        assertTrue(body.contains("enqueueOfflineDuelNotice"),
                "期望：quitDuel 在对手离线时应排队一条通告（与 cancelDuelByAdmin / 宽限超时对称）喵。");

        // 新键必须是独立键：cancelled-by-gm 说的是「管理员解散」（此场景是谎话），
        // lost-by-disconnect 说的是「你被判负」（quit 不判负、不记战绩）。
        assertTrue(body.contains("duel.opponent-quit-while-disconnected"),
                "必须用说明「对手退出了这场对决」的独立键，不得复用 cancelled-by-gm / "
                        + "lost-by-disconnect —— 那两条的文案与事实相反喵。");

        // 结构断言：排队调用必须在 else 之后。放在 if 里会在对手在线时也入队，
        // 于是该玩家下次进服被重复通知一次（consume 的读后即清挡不住「先发后排队」）。
        int onlineBranch = body.indexOf("if (opponent != null && opponent.isOnline())");
        int elseBranch = body.indexOf("} else {", onlineBranch);
        int enqueueIdx = body.indexOf("enqueueOfflineDuelNotice");
        assertTrue(onlineBranch > 0 && elseBranch > onlineBranch,
                "quitDuel 必须保留「在线 sendMessage / 离线 enqueue」两分支结构喵。");
        assertTrue(enqueueIdx > elseBranch,
                "排队必须落在 else（离线）分支里 —— 放 if 里会让在线对手下次进服被重复通知喵。");
        assertFalse(body.substring(onlineBranch, elseBranch).contains("enqueueOfflineDuelNotice"),
                "在线分支内不得出现排队调用（重复通知）喵。");
    }

    /**
     * <b>A2 已裁决：这是一条设计决策，不是缺陷。</b>
     *
     * <p>本条原先是 {@code @Disabled("缺陷 A2：宽限期 paused 时 isInDuel 仍为 true…")}
     * 挂着的一条<b>永红的期望</b>：{@code isInDuel} 的方法体里必须出现 {@code paused}。
     * 它背后的前提（"{@code DuelRoom} 只有 {@code setPaused}、没有 {@code isPaused}，
     * 所以修不了"）现已被推翻：
     * <ul>
     *   <li>{@code DuelRoom.isPaused()} 早已存在（{@code DuelRoom.java:210}）；</li>
     *   <li>{@code DuelManager} 也真的用它做了分流（{@code DuelManager.java:432}，
     *       宽限期拒收答案时改回 {@code duel.paused-opponent-disconnected}）；</li>
     *   <li>而 {@code isInDuel}（{@code DuelManager.java:1783}）<b>刻意</b>仍然只有
     *       {@code return isActiveDuel(playerId);}。</li>
     * </ul>
     *
     * <h2>裁决依据写在哪</h2>
     * 产品语义是「<b>停盘不解除占用</b>」。论证在 {@code DuelRoom.java:204-208} 的
     * {@code isPaused()} javadoc 里，原话是「刻意<b>不</b>把 {@code paused} 折进
     * {@code DuelManager.isInDuel}」：那个判据被 13 处调用方用来表达「这名玩家已被一场
     * 对决<b>占用</b>」（不开新答题 / 不开 AI 对话 / 不进错题练习 / 主菜单重开对决界面
     * 而非发起挑战）。停盘并不改变占用事实 —— 房间还在、GUI 还开着、30 秒后自动复赛；
     * 把占用一并解除会让这些守卫在宽限期内集体失效，制造出「答题 / 练习 / 对话与对决
     * 并存」的半死态（正是那些守卫当初要防的缺陷类）。
     *
     * <p>A2 真正该修的是第三段文案（拒收时谎称「你当前不在对决中」），已修在
     * {@code DuelManager.java:432}，行为验收在
     * {@link DuelQueryAndNoticeQueueTest#pausedRoomShouldNotBeAnswerConsumable()}。
     * 所以本条<b>不再是缺陷期望</b>，改写成<b>钉住该决策的正向护栏</b>：谁哪天把审计
     * 当初提的路线 (a)（让 {@code isInDuel} 排除 {@code paused}）偷偷做进去，这里会红，
     * 逼他先面对那 13 处调用方的语义，而不是悄悄拓宽一个被四处依赖的判据。
     *
     * <h2>钉三件事（纯 JVM + 读源码，不启服务器）</h2>
     * <ol>
     *   <li>{@code DuelRoom.isPaused()} 真的反映停盘位 —— 否则管理侧的文案分流是空中楼阁；</li>
     *   <li>{@code DuelManager.isInDuel} / {@code isActiveDuel} 的<b>代码</b>里不得出现
     *       {@code paused}（「停盘不解除占用」这条决策的源码层护栏）；</li>
     *   <li>停盘期间 {@code DuelRoom.submitAnswer} <b>双向</b>拒收（答对不给分、答错不送分）。</li>
     * </ol>
     *
     * <p><b>为什么必须剥注释再匹配</b>：旧判据 {@code body.contains("paused")} 既使这条
     * 用例永红（设计上就不该有那三个字母），又能被一行注释满足 —— 本仓已栽过这个坑
     * （见 {@link DuelQueryAndNoticeQueueTest} 类注释里对它的复盘）。
     */
    @Test
    @DisplayName("设计决策护栏：停盘不解除占用（isInDuel/isActiveDuel 恒不含 paused）+ 房间双向拒收")
    void pausedDuelKeepsOccupancyAndRejectsBothDirections() throws Exception {
        // ---------- 1. 停盘位可读、可反映（DuelRoom.java:210） ----------
        DuelRoom r = room();
        r.setCurrentWord(word("apple"));
        assertFalse(r.isPaused(), "前置：新房间默认不在停盘态");
        r.setPaused(true);
        assertTrue(r.isPaused(),
                "DuelRoom.isPaused() 必须反映 setPaused(true) —— DuelManager.java:432 的文案分流"
                        + "（duel.paused-opponent-disconnected）全靠它；读不到就只能继续谎称"
                        + "「你当前不在对决中」");
        assertTrue(r.isActive(), "停盘 ≠ 结束：paused 不得动 active 位（占用事实未变）");

        // ---------- 2. 源码护栏：两个判据体内都不得出现 paused ----------
        String code = stripComments(readMainSource("core/duel/DuelManager.java"));
        for (String signature : new String[]{
                "public boolean isInDuel(UUID playerId)",
                "private boolean isActiveDuel(UUID playerId)"}) {
            String body = methodBody(code, signature);
            assertFalse(body.contains("paused") || body.contains("isPaused"),
                    signature + " 的方法体里出现了 paused —— 这是设计决策，不是缺陷："
                            + "停盘不解除占用（论证见 DuelRoom.java:204-208 的 isPaused() javadoc）。"
                            + "把 paused 折进来会让 13 处按「已被一场对决占用」理解的守卫"
                            + "（不开新答题 / 不开 AI 对话 / 不进错题练习 / 主菜单重开对决界面）"
                            + "在 30 秒宽限期内集体失效，制造「答题 / 练习 / 对话与对决并存」的半死态。"
                            + "若确要推翻这条裁决，请先同步 DuelRoom.java:204 的论证与"
                            + " DuelQueryAndNoticeQueueTest 的双向夹，再改本 lint 喵。实际方法体："
                            + body);
        }
        // 正向：A2 真正修掉的那一段（文案分流点）必须仍在。
        assertTrue(code.contains("room.isPaused()"),
                "DuelManager 必须仍按 room.isPaused() 分流 NOT_IN_DUEL 的文案"
                        + "（DuelManager.java:432）—— 删掉它就退回「宽限期内谎称不在对决中」");

        // ---------- 3. 停盘期间房间双向拒收（R4-07-logic-F01 的既定契约） ----------
        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, r.submitAnswer(P1, "apple"),
                "停盘期间在场方答对也必须被拒收 —— 否则连答 5 题就把掉线局刷成 5-0 战绩");
        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, r.submitAnswer(P2, "banana"),
                "停盘期间答错也必须被拒收 —— 否则缺席方白拿一分，两个方向都要封死");
        assertEquals(0, r.getScore1(), "被拒收不得计分");
        assertEquals(0, r.getScore2(), "被拒收不得给对手计分");
        assertFalse(r.isFinished(), "停盘期一题都未成，绝不得判定结束");
    }

    /**
     * <b>缺陷 A3（LOW，可自愈；D2 已修复并转绿）</b>：{@code lastInviteAt} 原先只在
     * {@code shutdown()} 清，<b>不在 {@code quit} 清</b> —— 邀请冷却表的键集合随
     * "本服历史上出现过的邀请者"单调增长。
     *
     * <h2>为什么曾定为 LOW</h2>
     * 条目是 {@code UUID → long}（约 40 字节），且只在 {@code sendDuelRequest} 里<b>写</b>，
     * 不随在线人数增长；一个 100 人服一年也就几千条。不是泄漏，是<b>无界增长</b>。
     * 但同一份代码对其它四张表（{@code activeDuels} / {@code pendingRequests} /
     * {@code disconnectTimers} / {@code graceTasks}）都在 quit 或 cleanup 里有清理点，
     * 只有这张表没有 —— 属"清理矩阵漏了一格"，会在将来的内存审计里被反复翻出来。
     *
     * <h2>D2 的修法（本用例现在钉的三条）</h2>
     * <ol>
     *   <li>{@code DuelManager} 真的新增了 per-player 清理点：全文件除 {@code shutdown()}
     *       的 {@code lastInviteAt.clear()} 之外，还有对 {@code lastInviteAt.remove(} 的调用；</li>
     *   <li>该清理点包在 {@code public void unloadPlayer(UUID)} 里（quit 编排要能调到）；</li>
     *   <li>行为面：调 {@code unloadPlayer} 后该键真的从表里消失，且<b>别的玩家条目不受影响</b>
     *       —— 第三条是本用例从 {@code @Disabled} 转绿时唯一新增的行为断言，
     *       其余两条是结构 lint（因为 {@code sendDuelRequest} 需要 Player/Bukkit 才能驱动，
     *       冷却表的读侧无法在纯 JVM 上跑；写入面则用反射直接塞条目）。</li>
     * </ol>
     *
     * <p><b>为什么必须同时钉第 1 条（结构）与第 3 条（行为）</b>：只钉结构，
     * "在 {@code unloadPlayer} 里写一行注释掉的 {@code remove}" 或"remove 了错的键
     * （例如误用 {@code clear()} 之外的变量）"都能骗过；只钉行为，删掉 quit 编排的接线
     * 也能全绿（本用例直接调 {@code unloadPlayer}，看不到 Listener）。接线由
     * {@code PlayerQuitResilienceTest#everyCleanupStepGoesThroughTheGuard} 的 17 次计数钉住。
     */
    @Test
    @DisplayName("lint + 行为：lastInviteAt 在 quit 有 per-player 清理点（不只靠 shutdown）")
    void inviteCooldownMapShouldBeSweptOnQuit() throws Exception {
        String code = stripComments(readMainSource("core/duel/DuelManager.java"));

        // ---------- 1. 结构：shutdown 的 clear() 之外必须还有 per-player remove ----------
        // 注意：不能只数 "lastInviteAt.clear()" —— 修复后 clear() 仍然只有 shutdown 一处
        // （clear 是全局重置，不是 per-player 清理）。per-player 清理的正确形状是
        // lastInviteAt.remove(playerId)。
        assertEquals(1, countOccurrences(code, "lastInviteAt.clear()"),
                "全文件应恰好保留 1 处 lastInviteAt.clear()（shutdown 的全局重置）喵 ——"
                        + " 多出来的一处意味着有人在 per-player 路径上写了 clear()："
                        + "那会把<b>别的在线玩家</b>的冷却一起抹掉。");
        int perPlayerRemoves = countOccurrences(code, "lastInviteAt.remove(");
        assertTrue(perPlayerRemoves >= 1,
                "期望：lastInviteAt 除 shutdown 外还有 per-player 清理点（quit 或 cleanup）。"
                        + "实际 lastInviteAt.remove( 次数=" + perPlayerRemoves
                        + "，键集合会随历史邀请者单调增长。");

        // ---------- 2. 结构：清理点必须在 public unloadPlayer(UUID) 里 ----------
        assertTrue(code.contains("public void unloadPlayer(UUID playerId)"),
                "DuelManager 必须暴露 public void unloadPlayer(UUID playerId) 喵 ——"
                        + " quit 编排点（PlayerJoinListener）调不到私有方法，接线会断。");
        int unloadAt = code.indexOf("public void unloadPlayer(UUID playerId)");
        int removeAt = code.indexOf("lastInviteAt.remove(", unloadAt);
        assertTrue(removeAt > unloadAt,
                "unloadPlayer 之后找不到 lastInviteAt.remove( 喵 —— 方法体里没有真正的清理，"
                        + "结构 lint 会被一行注释骗过（本仓踩过：lint 锚点被注释满足）。");

        // ---------- 3. 行为：remove 精确命中该玩家，不误伤他人 ----------
        // sendDuelRequest 需要 Player + ConfigManager + MessageUtil 才能驱动，纯 JVM 上
        // 成本过高；这里直接反射塞两个条目，验证 unloadPlayer 的<b>选择性</b>。
        DuelManager manager = new DuelManager(org.mockito.Mockito.mock(
                com.letslearnenglish.minecraftplugin.LetsLearnEnglish.class));
        java.lang.reflect.Field f = DuelManager.class.getDeclaredField("lastInviteAt");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Long> invites = (java.util.Map<UUID, Long>) f.get(manager);
        UUID quitter = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
        UUID stayer = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        invites.put(quitter, 1000L);
        invites.put(stayer, 2000L);

        manager.unloadPlayer(quitter);

        assertNull(invites.get(quitter),
                "unloadPlayer 必须移除该玩家的冷却条目喵 —— 否则键集合仍只增不减。");
        assertEquals(Long.valueOf(2000L), invites.get(stayer),
                "unloadPlayer 不得误伤<b>别的玩家</b>的冷却条目喵 ——"
                        + " 若这里变成 null，说明实现用了 clear() 而不是 remove(playerId)，"
                        + " 在线玩家的冷却会被一起抹掉（等于关闭 R4-07-sec-F02 的防轰炸）。");

        // 幂等：重复调用（quit 与 shutdown 都走这条路）不得抛。
        manager.unloadPlayer(quitter);
        manager.unloadPlayer(null); // 防御分支：不得抛 NPE 截断整条清理链
    }

    // ==================================================================
    // 7. DuelGraceOutcome 的接线 lint（纯值类有测试 ≠ 生产代码调了它）
    // ==================================================================

    /**
     * <b>R26 接线 lint</b>：{@link DuelGraceOutcome} 抽出后，生产代码必须<b>真的</b>走它，
     * 且三条宽限到点文案的所有权必须留在它手里。
     *
     * <h2>为什么必须有这一条</h2>
     * {@link DuelGraceOutcomeTest} 全是纯函数用例（喂参数、断言返回值），
     * 它<b>根本看不到 {@code DuelManager}</b>。于是存在一个完美的假绿：把
     * {@code DuelManager} 里那行 {@code DuelGraceOutcome.decide(...)} 删掉、改回原来的
     * 两个 {@code if} + {@code containsKey}，甚至改成直接硬写 {@code "duel.opponent-timeout"}，
     * 整个 {@code DuelGraceOutcome} 变成<b>死代码</b>，而 {@code DuelGraceOutcomeTest}
     * 依然 100% 全绿。纯值类只能证明「判决函数算得对」，<b>不能</b>证明「有人在调它」——
     * 与本仓 {@code IndexAndPragmaLintsTest} 类注释里记的「假绿：闸门未触及」同型。
     *
     * <h2>断言内容（三条，缺一不可）</h2>
     * <ol>
     *   <li><b>正向接线</b>：{@code DuelManager} 里 {@code DuelGraceOutcome.decide(} 恰好 1 处
     *       —— 0 处 = 判决函数成了死代码；多处 = 「谁才是到点那一刻的唯一判决」说不清。
     *       并且返回值必须被真的消费（{@code instanceof DuelGraceOutcome.Forfeit}）。</li>
     *   <li><b>判决域零字面量</b>：从到点判定 {@code if (remainingSeconds[0] <= 0)} 到拆房
     *       {@code room.setActive(false);} 之间，<b>不允许</b>出现任何 {@code "duel.…"}
     *       字面量。这一段正是 {@link DuelGraceOutcome} 独占的判决域，三个键必须经
     *       {@code Direct.key()} / {@code QueueNeutral.key()} / {@code forfeiterKey()} 取得。</li>
     *   <li><b>全文件所有权 + 例外清单</b>：{@link DuelGraceOutcome#KEY_WINNER_ONLINE} 与
     *       {@link DuelGraceOutcome#KEY_FORFEITER} 全文件 0 处；
     *       {@link DuelGraceOutcome#KEY_PEER_NEUTRAL} 恰好 3 处，且这三处全在判决域之外
     *       （「房间已无」兜底 2 处 + {@code handleReconnect} 深防御 1 处）。
     *       它是唯一有合法例外的键 —— 因为那三处时序上拿不到判决结果，只能确认「房间没了」，
     *       无从判断谁被判负，取中性文案是它们的职责。</li>
     * </ol>
     *
     * <p>硬写任一键的后果都是<b>第二份真相源</b>：将来只改常量时，判决与文案脱节，
     * 而 {@code DuelGraceOutcomeTest} 只测纯函数、照样全绿。
     *
     * <p><b>已知且接受的误报</b>：{@code DuelManager} 里有 6 处 javadoc/行内注释提到这几个
     * 键名（解释为什么选中性文案等；实测 9 处原始出现 = 6 注释 + 3 代码）。
     * 本 lint <b>先剥注释再匹配</b>，所以它们不会被误报；
     * 反之若哪天 {@code stripComments} 被删掉，这条会立刻假红 —— 失败消息里写了这一点。
     */
    @Test
    @DisplayName("lint：DuelManager 必须真的调 DuelGraceOutcome.decide，且不得硬写它拥有的 i18n 键")
    void duelGraceOutcomeMustBeWiredAndOwnItsKeys() throws Exception {
        String raw = readMainSource("core/duel/DuelManager.java");
        String code = stripComments(raw);

        // ---------- 1. 正向接线：判决函数被调用，且只在一处 ----------
        int decideCount = countOccurrences(code, "DuelGraceOutcome.decide(");
        assertEquals(1, decideCount,
                "DuelManager 里 DuelGraceOutcome.decide( 必须恰好 1 处，实际 " + decideCount + " 处。"
                        + "0 处 = 宽限到点的判决退回「两个 if + containsKey」的隐式约定，"
                        + "整个 DuelGraceOutcome 变成死代码（而 DuelGraceOutcomeTest 会照样全绿，"
                        + "因为它只测纯函数、根本不看 Manager）；多处 = 到点那一刻出现多个判决入口，"
                        + "「谁说了算」不再可断言喵。");

        // 判决结果必须真的被消费（不是 decide 完就丢），否则接线只是装饰。
        assertTrue(code.contains("instanceof DuelGraceOutcome.Forfeit"),
                "decide(...) 的返回值必须被真的消费（instanceof DuelGraceOutcome.Forfeit）"
                        + "—— 调而不判等于没接线：Defer/Forfeit 的分支会退化成恒执行");

        // ---------- 2. 反向所有权：宽限到点那一块里不得出现任何键字面量 ----------
        // 精确锚点：从「到点」判定开始，到拆房（room.setActive(false)）之前 ——
        // 这一段就是 DuelGraceOutcome 独占的判决域，三个键必须全部从判决结果取。
        int expired = code.indexOf("if (remainingSeconds[0] <= 0) {");
        assertTrue(expired > 0,
                "找不到宽限到点的判定分支（if (remainingSeconds[0] <= 0)）—— 结构被重排了，"
                        + "请把本 lint 的锚点上移到新的到点判定处，不要删断言喵。");
        int teardown = code.indexOf("room.setActive(false);", expired);
        assertTrue(teardown > expired,
                "找不到到点分支里的拆房点（room.setActive(false);）—— 锚点失效，同上喵。");
        String graceBlock = code.substring(expired, teardown);
        assertFalse(graceBlock.contains("\"duel."),
                "宽限到点的判决域里出现了 i18n 键字面量（\"duel.…\"）。该块的三个键"
                        + "（duel.opponent-timeout / duel.duel-already-over / duel.lost-by-disconnect）"
                        + "全部归 DuelGraceOutcome 所有，必须经 Direct.key() / QueueNeutral.key() /"
                        + " forfeiterKey() 取得。硬写会制造第二份真相源：将来只改常量时，判决与文案"
                        + "脱节，而 DuelGraceOutcomeTest 只测纯函数、照样全绿（假绿）。判决域全文："
                        + graceBlock);

        // ---------- 3. 全文件层的键所有权（含合法例外清单） ----------
        // duel.opponent-timeout / duel.lost-by-disconnect：全文件必须 0 处 —— 它们
        // 只出现在判决域里，而判决域已被上面那条钉成「零字面量」。
        for (String key : new String[]{DuelGraceOutcome.KEY_WINNER_ONLINE,
                DuelGraceOutcome.KEY_FORFEITER}) {
            assertEquals(0, countOccurrences(code, "\"" + key + "\""),
                    "DuelManager 里不得硬写 i18n 键 \"" + key + "\" —— 它归 DuelGraceOutcome 所有，"
                            + "必须由 decide(...) 的返回值带过来。若红在注释上，说明 stripComments"
                            + "失效了，先修它喵。");
        }

        // duel.duel-already-over 有 3 处**合法例外**，全部在判决域之外、且时序上拿不到
        // 判决结果：① ② 宽限计时里「房间已无」的兜底（在线直发 / 离线排队，
        // DuelManager.java:1017/:1020）；③ handleReconnect 的深防御
        // （room 已 !active，DuelManager.java:1160）。这三处只能确认「房间没了」，
        // 无从判断谁被判负，因此取中性文案是它们的职责，不是 DuelGraceOutcome 的。
        // 上限钉在 3：多出来的第 4 处必须由人裁决是新增例外还是又一次硬写。
        assertEquals(3, countOccurrences(code, "\"" + DuelGraceOutcome.KEY_PEER_NEUTRAL + "\""),
                "DuelManager 里 \"" + DuelGraceOutcome.KEY_PEER_NEUTRAL + "\" 字面量应恰好 3 处"
                        + "（房间已无兜底 2 处 + handleReconnect 深防御 1 处，见本方法注释）。"
                        + "数量变了意味着有人在判决域之外新增了硬写点，或删掉了既有兜底 ——"
                        + "两种都必须由人裁决。宽限到点那条链必须用 QueueNeutral.key() 取喵。");
    }
}
