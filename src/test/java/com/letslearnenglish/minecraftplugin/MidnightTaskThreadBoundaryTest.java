package com.letslearnenglish.minecraftplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager;

/**
 * R-17：午夜 rollover 的<b>线程边界</b>——主线程段不得再被
 * {@code readWordChallengeCompletionWithRetry} 的 {@code Thread.sleep(50ms)} 拖住。
 *
 * <h2>被修的缺陷</h2>
 * <p>F2-11 把 {@code countUnclaimedForDate} / {@code checkAndAssign} 挪进了异步段，但
 * {@code hasPendingWordRewardForDate} 仍留在主线程的 per-online-player 循环里。它的读链含
 * {@code readWordChallengeCompletionWithRetry}：3 次尝试、尝试之间 {@code Thread.sleep(50ms)}
 * ——<b>每个在线玩家最坏 100ms 主线程停顿</b>。100 人在线 = 10 秒卡服。
 *
 * <p>让它难以被发现的细节：调用点注释只写了"含 {@code Bukkit.getPlayer} / Vault 发币 /
 * {@code sendMessage}"，<b>没有任何一处提到 sleep</b>。下一个读这段的人会以为主线程只在做
 * 消息投递，而实际上主线程在睡觉。
 *
 * <h2>为什么这里必须包含源码 lint（而不是只做行为测试）</h2>
 * <p>「主线程不睡觉」在单测里<b>不可观测</b>：没有真实 tick 循环，mock 的
 * {@code loadWordChallengeCompletion} 也不消费 {@code Thread.sleep} 的真实时长。
 * 把一个 async 调度改回主线程，所有行为断言仍然全绿 —— 唯一变红的是"代码形状"。
 * 这与 {@code AiChatExecutorIsolationTest}（执行器归属）和 {@code StripeLockSingleSourceLintTest}
 * （锁数组单一真相源）是同一类判断：缺陷的主体是<b>静态属性</b>，lint 才是对的层次。
 *
 * <h2>变异验证（本类的存在理由）</h2>
 * <ul>
 *   <li><b>M1</b> 把 {@code hasPendingWordRewardForDate} 的调用点从"预读闸门之后"改回
 *       "对每个在线玩家无条件调用" → {@code L2} 变红（闸门锚点消失）；</li>
 *   <li><b>M2</b> 把 {@code Thread.sleep} 提到主线程段（例如在循环里直接 sleep） →
 *       {@code L1} 变红；</li>
 *   <li><b>M3</b> 把预读的"不确定 → true"改成"不确定 → false" → {@code L3} 与行为组的
 *       {@code failingReadIsTreatedAsNeedingSettlement} 同时变红（后者是真行为断言）。</li>
 * </ul>
 *
 * <p><b>剥注释后匹配</b>：本仓的 lint 已因"匹配到注释"产生过 4 次以上假阳性，
 * 所以 {@link #stripComments} 先抹掉注释与字面量再断言（工具方法本类独立实现一份，
 * 理由同 {@code ServerToggleLintsTest} 的类注释：失效模式要互相独立）。
 */
class MidnightTaskThreadBoundaryTest {

    // ==================================================================
    //  A. 源码 lint：主线程段不得含 sleep，补发调用点必须在预读闸门之后
    // ==================================================================

    @Nested
    @DisplayName("A. 源码 lint：午夜主线程段")
    class MidnightThreadBoundaryLint {

        /**
         * L1：午夜任务体（含两段派的整块 lambda）里，{@code Thread.sleep} 只允许出现在
         * {@code preReadNeedsSettlement} / {@code readCompletionWithRetryForPreRead} 这两个
         * <b>异步专用</b>方法里，且不得出现在 {@code runTask(} 派发的主线程段内。
         *
         * <p>断言的是<b>位置</b>而不是"有没有 sleep"：退避本身是对的（连接自愈需要它），
         * 错的是它睡在主线程上。所以 lint 检查「主线程段切片里 sleep 计数为 0」。
         */
        @Test
        @DisplayName("L1：runTask 派发的主线程段内不得出现 Thread.sleep")
        void mainThreadPhaseHasNoSleep() {
            String mainPhase = mainThreadPhaseOfMidnightTask();
            assertFalse(mainPhase.contains("Thread.sleep"),
                    "午夜 rollover 的<b>主线程段</b>里出现了 Thread.sleep 喵！\n"
                            + "实测主线程段：\n" + mainPhase.strip() + "\n"
                            + "后果：runTask 的 lambda 跑在服务端 tick 线程上，sleep 的每一毫秒都是"
                            + "整服卡顿。而这段是 per-online-player 循环 —— 100 人在线时，"
                            + "每人 100ms 退避 = 10 秒卡服（这正是 R-17 要修的缺陷）。\n"
                            + "正确位置：退避只能出现在 preReadNeedsSettlement /"
                            + " readCompletionWithRetryForPreRead 里（它们只被异步段调用）。\n"
                            + "注意本 lint 已剥掉注释 —— 注释里提到 Thread.sleep 不会误报。");
        }

        /**
         * L2：{@code hasPendingWordRewardForDate} 的调用必须被预读闸门守着。
         *
         * <p>这是 M1 变异的判别点：把闸门删掉、或把它挪到调用之后，这条立刻红。
         * 只查"方法里有没有预读"是不够的 —— 预读存在但结果没被用于跳过，缺陷原样保留。
         */
        @Test
        @DisplayName("L2：hasPendingWordRewardForDate 必须挂在预读闸门的 continue 分支之后")
        void settlementCallIsGuardedByPreRead() {
            String mainPhase = mainThreadPhaseOfMidnightTask();

            assertTrue(mainPhase.contains("needsSettlementByPlayer"),
                    "主线程段找不到预读结果 needsSettlementByPlayer 喵。\n"
                            + "实测主线程段：\n" + mainPhase.strip() + "\n"
                            + "预读被删掉了 → 每个在线玩家又要在主线程走一遍"
                            + " isDailyWordRewardClaimed(SELECT) + 三态重试读（含 sleep 退避）。");

            int gate = mainPhase.indexOf("needsSettlementByPlayer");
            int call = mainPhase.indexOf("hasPendingWordRewardForDate");
            assertTrue(call > 0,
                    "主线程段找不到 hasPendingWordRewardForDate 调用喵 —— 若该方法被改名/"
                            + "下线，请同步本 lint（否则门禁静默失效）。");
            assertTrue(gate < call,
                    "预读闸门必须出现在 hasPendingWordRewardForDate 调用<b>之前</b>喵。\n"
                            + "实测 gate@" + gate + " call@" + call + "\n"
                            + "闸门在调用之后 = 门闩装在门后面，每个玩家照样在主线程跑完整"
                            + "重试读（M1 变异形状）。");

            // 形状：闸门必须以 continue（或 return）真正跳过，不能只是读了一下变量。
            // 按花括号配对取 if 体 —— 不能用「找下一个分号」：闸门体里第一条语句自己就带
            // 分号（int voidedBehaviorOnly = ...;），按分号切会把它误当成整个体。
            int gateBrace = mainPhase.indexOf('{', gate);
            assertTrue(gateBrace > 0, "预读闸门的 if 之后找不到左花括号喵。");
            int gateBraceEnd = findBlockEnd(mainPhase, gateBrace);
            assertTrue(gateBraceEnd > gateBrace, "预读闸门的 if 体花括号不配对喵。");
            String gateBody = mainPhase.substring(gateBrace + 1, gateBraceEnd);
            assertTrue(gateBody.contains("continue"),
                    "预读闸门的 if 体里必须 continue（跳过本玩家）喵，实测 if 体：\n"
                            + gateBody.strip() + "\n"
                            + "只读变量不跳过 = 预读白做，缺陷原样在。");
        }

        /**
         * L3：预读的失败方向必须是 {[@code true}（保守：交给主线程判定）。
         *
         * <p>这是 M3 变异的判别点。<b>方向错了会静默吞掉一笔该结清的钱</b>：
         * 读不到就说"不用结算"，玩家昨日确实全完成却永久拿不到奖励，且没有任何日志。
         */
        @Test
        @DisplayName("L3：preReadNeedsSettlement 的 catch 分支必须返回 true（保守方向）")
        void preReadFailureFallsBackToConservativeDirection() {
            String body = methodBody("LetsLearnEnglish.java",
                    "private boolean preReadNeedsSettlement(UUID playerId, String date)");

            assertTrue(body.contains("catch (RuntimeException"),
                    "preReadNeedsSettlement 缺少 RuntimeException 兜底喵 —— 连接层故障"
                            + "（getConnection 抛未检查异常）不是 SQLException，数据层接不到，"
                            + "异常会冲出异步段。");
            // catch 块必须以 return true 收尾（保守）。按花括号配对取 catch 体。
            int catchIdx = body.indexOf("catch (RuntimeException");
            int catchBodyStart = body.indexOf('{', catchIdx);
            int catchBodyEnd = findBlockEnd(body, catchBodyStart);
            assertTrue(catchBodyStart > 0 && catchBodyEnd > catchBodyStart,
                    "preReadNeedsSettlement 的 catch 块花括号不配对喵。");
            String catchBody = body.substring(catchBodyStart + 1, catchBodyEnd);
            assertTrue(catchBody.contains("return true"),
                    "preReadNeedsSettlement 的 catch 分支必须 return true（不确定 → 交给主线程）喵。\n"
                            + "实测 catch 体：" + catchBody.strip() + "\n"
                            + "返回 false 的后果：库连不上时，昨日确实全完成、奖励还没发的玩家"
                            + "被<b>静默跳过</b> —— 钱永久丢失，且没有任何日志（主线程段才是记 SEVERE"
                            + " 的地方）。失败方向必须选\"多发一次问\"，不是\"少发一笔钱\"。");

            // 变量泄漏/语义漂移防线：NOT_COMPLETED 才允许 false，其余一律 true。
            assertTrue(body.contains("NOT_COMPLETED"),
                    "preReadNeedsSettlement 里找不到 NOT_COMPLETED 喵 —— 它是<b>唯一</b>"
                            + "允许得出 false 的确定性依据（该日未全完成 ⇒ 确实无事可结清）。");
        }

        /**
         * L4：预读的两个方法必须只在异步线程可达（不得从主线程段调用）。
         *
         * <p>L1 管的是"主线程段里没有 sleep"，本条件管的是反方向：sleep 所在的方法
         * <b>不能</b>被主线程段调用。两者合起来才是"主线程不睡觉"的完整证明。
         */
        @Test
        @DisplayName("L4：主线程段不得调用带退避的预读方法")
        void mainThreadPhaseDoesNotCallRetryHelper() {
            String mainPhase = mainThreadPhaseOfMidnightTask();
            assertFalse(mainPhase.contains("readCompletionWithRetryForPreRead"),
                    "主线程段直接调用了 readCompletionWithRetryForPreRead 喵 —— 那里面就是"
                            + " Thread.sleep 的退避循环，调用它等于把 L1 绕过去。\n"
                            + "实测主线程段：\n" + mainPhase.strip());
            assertFalse(mainPhase.contains("preReadNeedsSettlement"),
                    "主线程段直接调用了 preReadNeedsSettlement 喵 —— 它内部会走到带 sleep 的"
                            + "重试读。预读只能在异步段做，主线程段只消费结果。");
        }

        /**
         * L5（反向锚点）：{@code DailyChallengeManager} 里那份 {@code sleep} 仍在
         * <b>带重试的方法内</b>，且该方法的调用方仍是 {@code hasPendingWordRewardForDate}。
         *
         * <p>为什么要钉住"不该改的地方"：R-17 的正确修法是"把读前置"，<b>不是</b>把退避删掉。
         * 删掉退避会让连接自愈彻底失去第二次机会（READ_FAILED 直接落到"保留 pending"），
         * 看似"修好了卡顿"，实际把偶发 DB 抖动放大成永久待结清。这条 lint 防止后来者那样"修"。
         */
        @Test
        @DisplayName("L5：退避不得被直接删除 —— sleep 仍在 readWordChallengeCompletionWithRetry 内")
        void backoffIsNotSimplyDeleted() {
            String mgr = sourceOf("DailyChallengeManager.java");
            String body = extractBody(mgr,
                    "private DatabaseManager.WordChallengeCompletion readWordChallengeCompletionWithRetry(");
            assertTrue(body.contains("Thread.sleep"),
                    "readWordChallengeCompletionWithRetry 里的退避被删掉了喵。\n"
                            + "R-17 的修法是把这个<b>读</b>前置到异步线程，而不是取消退避 ——"
                            + "删掉它会让连接自愈失去重试窗口，一次 DB 抖动就变成永久待结清"
                            + "（READ_FAILED 落到\"保留 pending\"，而昨日之后再无回访点）。");
            String pending = extractBody(mgr,
                    "public boolean hasPendingWordRewardForDate(UUID playerId, String date)");
            assertTrue(pending.contains("readWordChallengeCompletionWithRetry"),
                    "hasPendingWordRewardForDate 不再调用重试读喵 —— 若读取路径被重构，"
                            + "请同步本 lint 并重新确认新路径是否含阻塞退避。");
        }

        /**
         * L6：预读结果必须是"缺键按 true"的保守默认。
         *
         * <p>{@code getOrDefault(id, Boolean.TRUE)}：异步段若在填表前提前 return
         * （reload/停用守卫），主线程段拿到的是空 map。默认值写成 {@code FALSE} 会让
         * <b>全部</b>玩家被静默跳过 —— 一次 reload 恰好跨过午夜就能吞掉全服的补发。
         */
        @Test
        @DisplayName("L6：主线程段取预读结果时默认值必须是 TRUE")
        void missingPreReadEntryDefaultsToConservative() {
            String mainPhase = mainThreadPhaseOfMidnightTask();
            assertTrue(mainPhase.contains("Boolean.TRUE"),
                    "主线程段读 needsSettlementByPlayer 时默认值不是 Boolean.TRUE 喵。\n"
                            + "实测主线程段：\n" + mainPhase.strip() + "\n"
                            + "缺键会发生在异步段提前 return 时（reload/停用守卫）——"
                            + "默认 FALSE 等于\"没预读到的玩家全部不结算\"，"
                            + "一次跨午夜的 reload 就能静默吞掉全服昨日补发。");
        }

        // ---------------- 切片工具 ----------------

        /**
         * 取「午夜任务体里 {@code runTask(} 派发的主线程段」——即
         * {@code midnightMainPhaseTask = ...runTask(this, () -> { ... })} 的 lambda 体。
         *
         * <p>锚在 <b>{@code runTask(this}</b> 这个形状而不是行号/方法名：主线程段是这段代码里
         * 唯一的 {@code runTask(this, () -> {} 在午夜任务体内
         * （{@code runTaskLater} / {@code runTaskAsynchronously} 都是不同形状，不会误命中）。
         */
        private String mainThreadPhaseOfMidnightTask() {
            String midnight = methodBody("LetsLearnEnglish.java",
                    "private void scheduleNextMidnightRollover()");
            int anchor = midnight.indexOf("runTask(this,");
            assertTrue(anchor > 0,
                    "午夜任务体里找不到 runTask(this, —— 主线程段的派发形状变了喵。\n"
                            + "本 lint 锚在「runTask(this,」上（与 runTaskLater / "
                            + "runTaskAsynchronously 天然区分）。若派发改写，请同步本 lint，"
                            + "但必须仍能唯一框出主线程段 —— 否则 L1/L4 的门禁会静默失效。");
            int lambdaOpen = midnight.indexOf('{', anchor);
            assertTrue(lambdaOpen > 0, "runTask(this, 之后找不到 lambda 左花括号喵。");
            int lambdaClose = findBlockEnd(midnight, lambdaOpen);
            assertTrue(lambdaClose > lambdaOpen, "主线程段 lambda 的花括号不配对喵。");
            return midnight.substring(lambdaOpen + 1, lambdaClose);
        }

        private String methodBody(String fileName, String signature) {
            return extractBody(sourceOf(fileName), signature);
        }

        private Path srcRoot() {
            Path direct = Paths.get("src", "main", "java");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            while (p != null) {
                Path candidate = p.resolve("src").resolve("main").resolve("java");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                p = p.getParent();
            }
            return fail("找不到 src/main/java —— lint 无法读源码；"
                    + " workingDirectory=" + System.getProperty("user.dir"));
        }

        private String sourceOf(String fileName) {
            List<Path> hits = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(srcRoot())) {
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
                // 剥注释再匹配：本仓 lint 因「匹配到注释」假阳性过 4 次以上。
                return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
            } catch (IOException e) {
                fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
                return ""; // 不可达
            }
        }
    }

    // ==================================================================
    //  B. 行为：preReadNeedsSettlement 的返回值语义（真实反射调用）
    // ==================================================================

    /**
     * 行为侧只覆盖 lint 抓不到的部分，即<b>预读本身的三态语义</b>。
     *
     * <p>用 {@code mock(LetsLearnEnglish.class)} + 反射注入 {@code databaseManager} 字段，
     * 直接调 private 方法 —— 与 {@code DailyChallengeRolloverDateTest} 同一套 mock 组合，
     * 不引入新的测试基座。
     */
    @Nested
    @DisplayName("B. 行为：预读的三态语义")
    class PreReadSemantics {

        private static final UUID PLAYER_ID =
                UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        private static final String YESTERDAY = "2026-10-19";

        private LetsLearnEnglish plugin;
        private DatabaseManager databaseManager;
        private Method preRead;
        private Logger logger;

        @BeforeEach
        void setUp() throws Exception {
            plugin = mock(LetsLearnEnglish.class);
            databaseManager = mock(DatabaseManager.class);
            logger = mock(Logger.class);

            lenient().when(plugin.getLogger()).thenReturn(logger);

            // 反射注入 DI 字段（mock 实例的字段保持默认 null，与 Objenesis 语义一致）。
            var f = LetsLearnEnglish.class.getDeclaredField("databaseManager");
            f.setAccessible(true);
            f.set(plugin, databaseManager);

            preRead = LetsLearnEnglish.class.getDeclaredMethod(
                    "preReadNeedsSettlement", UUID.class, String.class);
            preRead.setAccessible(true);
        }

        private boolean invoke() {
            try {
                return (Boolean) preRead.invoke(plugin, PLAYER_ID, YESTERDAY);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("preReadNeedsSettlement 反射调用失败喵", e);
            }
        }

        @Test
        @DisplayName("B1：该日已付（DB 台账命中）→ false（主线程可跳过）")
        void alreadyPaidDateDoesNotNeedSettlement() {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(true);
            assertFalse(invoke(),
                    "昨日已付 → 预读必须返回 false，否则主线程白跑一次补发判定喵");
            verify(databaseManager, never()).loadWordChallengeCompletion(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("B2：未付且该日未全完成 → false（确定无事可结清）")
        void notCompletedDoesNotNeedSettlement() {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
            when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, YESTERDAY))
                    .thenReturn(DatabaseManager.WordChallengeCompletion.NOT_COMPLETED);
            assertFalse(invoke(), "未全完成 = 确定没有待结清的钱喵");
        }

        @Test
        @DisplayName("B3：未付且该日全完成 → true（交给主线程判定，不是直接发钱）")
        void completedNeedsSettlement() {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
            when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, YESTERDAY))
                    .thenReturn(DatabaseManager.WordChallengeCompletion.COMPLETED);
            assertTrue(invoke(),
                    "全完成且未付 → 预读只能说\"值得问一次\"，由主线程的"
                            + " hasPendingWordRewardForDate 做真正的补发判定喵");
            // 预读本身绝不发钱：它是纯读，连 RewardEngine 都够不着。
            verify(databaseManager, never()).markDailyWordRewardClaimed(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("B4：三态 READ_FAILED → true（不确定必须保守，不静默跳过）")
        void failingReadIsTreatedAsNeedingSettlement() {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
            when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, YESTERDAY))
                    .thenReturn(DatabaseManager.WordChallengeCompletion.READ_FAILED);
            assertTrue(invoke(),
                    "读失败 → 预读必须返回 true 喵。<b>这条是 R-17 的失败方向断言</b>：\n"
                            + "返回 false 会让主线程跳过该玩家 —— 而玩家昨日可能确实全完成、"
                            + "奖励还没发，那笔钱就永久丢失，且没有任何日志（记 SEVERE 的地方在"
                            + "主线程段）。方向必须选\"多发一次问\"而不是\"少发一笔钱\"。");
        }

        @Test
        @DisplayName("B5：null 三态（测试替身）→ true，绝不当成\"全完成\"或\"无事\"")
        void nullCompletionIsConservative() {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
            when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, YESTERDAY))
                    .thenReturn(null);
            assertTrue(invoke(),
                    "null 只可能来自替换实现/测试替身（生产三态永不返回 null）→ 必须按"
                            + "「未知」处理并保守返回 true 喵。");
        }

        @Test
        @DisplayName("B6：连接层运行期异常 → true，且不向外抛（异常不得冲出异步段）")
        void runtimeExceptionIsSwallowedAndConservative() {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
            when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, YESTERDAY))
                    .thenThrow(new IllegalStateException("db unavailable"));
            assertTrue(invoke(),
                    "连接层 RuntimeException 必须被预读自己接住并保守返回 true 喵 —— 否则"
                            + "异常会冲出午夜异步段，整段 rollover（含今日分配与自续）中断。");
        }

        @Test
        @DisplayName("B7：重试确实发生（3 次尝试），且成功后立刻停止重试")
        void retriesBeforeGivingUp() {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
            when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, YESTERDAY))
                    .thenReturn(DatabaseManager.WordChallengeCompletion.READ_FAILED)
                    .thenReturn(DatabaseManager.WordChallengeCompletion.NOT_COMPLETED);

            assertFalse(invoke(),
                    "第二次尝试成功（NOT_COMPLETED）→ 预读应得出 false（确定无事可结清）喵");
            verify(databaseManager, times(2))
                    .loadWordChallengeCompletion(eq(PLAYER_ID), eq(YESTERDAY));
        }
    }

    // ==================================================================
    //  工具：剥注释 + 花括号配对（本类独立实现，不与他组 lint 共享）
    // ==================================================================

    /** 剥掉 Java 注释并把字符串/字符字面量内容抹成空格（保留换行，偏移不错位）。 */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }
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
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j + 2 < n && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
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

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }

    /** 从 {@code open}（必须指向 '{'）找到配对的 '}'，返回其下标；不配对返回 -1。 */
    private static int findBlockEnd(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
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

    /** 按方法签名截出方法体（花括号之间，含花括号）。签名必须唯一出现。 */
    private static String extractBody(String strippedSource, String signature) {
        int sigIndex = strippedSource.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名/改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵。");
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
        int close = findBlockEnd(strippedSource, bodyStart);
        if (close < 0) {
            fail("方法签名「" + signature + "」的花括号没有配对结束喵 —— 源码可能不完整。");
        }
        return strippedSource.substring(bodyStart, close + 1);
    }
}
