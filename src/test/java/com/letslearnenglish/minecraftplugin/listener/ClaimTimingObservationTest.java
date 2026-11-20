package com.letslearnenglish.minecraftplugin.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 领奖慢操作观测（{@code MiscGUIListener.CLAIM_SLOW_THRESHOLD_MS}）的<b>源码 lint</b>。
 *
 * <h2>这条观测在做什么，为什么值得被测</h2>
 * <p>{@code handleDailyChallenge} 的领奖路径把 {@code claimBehavioralChallenge} 包在玩家的
 * stripe lock 里（R-02 的双发保护）。那条临界区里有一次 SELECT + 可能的 Vault 转账 +
 * 一次台账 UPSERT，全部跑在<b>主线程</b>上。稳态 1–10 ms，但午夜窗口（跨天回填与领奖竞争
 * {@code connectionLock}）或 MySQL 远程慢查询时会拉长 —— 而这段时长此前<b>完全不可观测</b>：
 * 本仓库反复出现「注释里写着风险，线上没有任何运行时证据」的形态，服主诊断
 * 「午夜整点卡一下」时只能猜。
 *
 * <p>所以加了一条 WARNING：超过 {@code 50 ms}（一个 tick）就记下玩家、挑战 id、耗时。
 *
 * <h2>为什么用源码 lint 而不是行为测试</h2>
 * <p>「只加日志」的改动在行为测试里<b>不可观测</b>：没有真实 tick 循环、没有真实 MySQL
 * socket，mock 的连接层也不消费 {@code socketTimeout}。为了让它「测得出来」而去 mock 一个
 * 慢 DB，会得到一个计时敏感、在 CI 上随机变红的脆弱测试 —— 而且它红了也说不清是阈值算错了
 * 还是调度抖了。这里要钉住的是<b>代码形状</b>，静态检查才是对的层次（与
 * {@code performance-optimizations-need-source-lint} 的教训同一结论）：
 *
 * <ol>
 *   <li><b>计时起点在 {@code synchronized} 之前</b> —— 要观测的是「等锁 + 锁内执行」的
 *       <b>总</b>时长。从锁内开始计时会整个漏掉「排队等锁」，而那正是玩家实际感受到的一段
 *       （别的线程持着本 stripe 或 {@code connectionLock} 时，本线程就在 monitor 上排队）。</li>
 *   <li><b>日志落在 {@code synchronized} <u>之外</u></b> —— 写日志是 IO（控制台 + 文件
 *       flush）。在锁内记会白白延长持锁时间，恰好是本条观测要防的那种退化。</li>
 *   <li><b>用 {@code nanoTime} 而不是 {@code currentTimeMillis}</b> —— 后者是墙上时钟，
 *       会跟着 NTP 校时 / 手动改时间往回跳，算出负数或离谱耗时，是测时长的经典错误。</li>
 *   <li><b>阈值常量存在且 50 有出处</b> —— 50 ms = 一个 tick，是「玩家可能感觉到卡顿」的
 *       物理边界，不是魔法数；没有出处注释的话下一个人只会当成可随手调的旋钮。</li>
 * </ol>
 *
 * <p><b>全部断言跑在剥注释后的文本上</b>：本条观测的实现里，注释<b>大量</b>提到
 * {@code synchronized} / {@code System.nanoTime()} / {@code 50 ms}（那正是它存在的说明），
 * 不剥注释的 lint 会被自己的文档骗绿。{@link P0Fixture#rawSourceMentionsTimingInComments()}
 * 把这条必要性钉住。
 *
 * <p><b>纯 JVM</b>：只有文件读取与字符串/花括号算术，无 Bukkit / Mockito / DB 依赖。
 */
@DisplayName("领奖慢操作观测：计时起点在锁外、日志在锁外（源码 lint）")
class ClaimTimingObservationTest {

    private static final String LISTENER = "MiscGUIListener.java";

    /** 领奖入口（点击 → 下标 → stripe lock）。观测就是加在这个方法里的。 */
    private static final String HANDLER_SIGNATURE =
            "private void handleDailyChallenge(Player player, int slot)";

    /** 被锁包住的领取体（挑战 id 只有它知道）。 */
    private static final String CLAIM_SIGNATURE =
            "private void claimBehavioralChallenge(Player player, int idx)";

    // ==================================================================
    //  L1：计时起点必须在 synchronized 之前（观测的是「等锁 + 执行」）
    // ==================================================================

    @Nested
    @DisplayName("L1：计时起点在 synchronized 之前")
    class L1TimingStartsBeforeLock {

        /**
         * 第一个 {@code System.nanoTime()} 必须出现在 {@code synchronized} 的起始花括号
         * <b>之前</b>，第二个必须在其<b>之后</b> —— 两次采样把锁整个夹在中间，得到的才是
         * 「等锁 + 锁内执行」的总时长。
         *
         * <p>变异（把计时移进锁内）必须让本条变红，见
         * {@link MutationSelfChecks#movingTimingIntoTheLockTurnsLintRed()}。
         */
        @Test
        @DisplayName("L1：两次 nanoTime 采样把 synchronized 块夹在中间")
        void timeSamplesBracketTheLock() {
            String src = strippedSourceOf(LISTENER);
            String body = handlerBody(src);
            int[] lock = claimLockSpan(body);

            List<Integer> samples = occurrences(body, "System.nanoTime()");
            assertTrue(samples.size() >= 2,
                    "handleDailyChallenge 里找不到两次 System.nanoTime() 采样喵（实测 "
                            + samples.size() + " 处）。\n"
                            + "领奖耗时的观测需要一次起点 + 一次终点；少于两次说明计时被删了或"
                            + "被合并（例如改用 currentTimeMillis）。实测方法体：\n" + body.strip());

            int first = samples.get(0);
            int second = samples.get(1);

            assertTrue(first < lock[0],
                    "第一次 nanoTime 采样落在 synchronized <b>内部或之后</b> 喵"
                            + "（采样@" + first + "，锁起始花括号@" + lock[0] + "）。\n"
                            + "计时起点必须在 synchronized <b>之前</b>：要观测的是「等锁 + 锁内执行」"
                            + "的总时长，而「排队等锁」正是玩家实际感受到的那一段 —— 别的线程持着"
                            + "本 stripe 或 connectionLock 时，本线程就在 monitor 上排队。"
                            + "从锁内开始计时会把这段排队整个漏掉，午夜整点卡顿时日志照样显示很快。\n"
                            + "实测方法体：\n" + body.strip());

            assertTrue(second > lock[1],
                    "第二次 nanoTime 采样不在 synchronized 块<b>结束之后</b> 喵"
                            + "（采样@" + second + "，锁结束花括号@" + lock[1] + "）。\n"
                            + "终点必须取在出锁之后，否则测的是「锁内的前半段」，"
                            + "反倒把锁内最后几步（台账 UPSERT）漏掉。");
        }

        /**
         * 必须用 {@code System.nanoTime()}，不得用 {@code currentTimeMillis()}。
         *
         * <p>{@code currentTimeMillis()} 是墙上时钟：NTP 校时或管理员改系统时间都会让它
         * 往回跳，算出负数耗时（{@code elapsed} 为负 → {@code >= 50} 恒 false → 门禁静默
         * 失效，日志永远不出现）。这是「测时长用错时钟」的经典 bug，且失效方向是<b>静默</b>的。
         */
        @Test
        @DisplayName("L1b：用 nanoTime（单调），不用受系统时间调整影响的 currentTimeMillis")
        void usesMonotonicClock() {
            String body = handlerBody(strippedSourceOf(LISTENER));

            assertFalse(body.contains("currentTimeMillis"),
                    "handleDailyChallenge 里出现了 currentTimeMillis 喵。\n"
                            + "测时长必须用 System.nanoTime()：currentTimeMillis 是墙上时钟，"
                            + "NTP 校时/改系统时间会让它往回跳 → 耗时算出负数 → "
                            + "`elapsed >= 阈值` 恒 false → 日志永不出现，且没有任何报错。\n"
                            + "实测方法体：\n" + body.strip());
        }
    }

    // ==================================================================
    //  L2：日志必须在 synchronized 之外（锁内不做 IO）
    // ==================================================================

    @Nested
    @DisplayName("L2：慢操作日志落在锁外")
    class L2LogOutsideLock {

        /**
         * 锁的<b>块体</b>里不得出现 {@code getLogger} / {@code warning(}，而慢操作的
         * {@code getLogger().warning(} 必须出现在锁块结束之后。
         *
         * <p><b>为什么这条重要</b>：日志写盘是阻塞 IO。锁内多记一行日志，就多一段
         * 「同 stripe 的其它玩家在主线程上一起等」的时间 —— 32 条带、100 人在线时平均每带
         * 3 人。为了观测慢操作而把慢操作本身拖长，是把观测变成致病因素。
         */
        @Test
        @DisplayName("L2：锁块体内无日志 IO，WARNING 在块结束之后")
        void logHappensOutsideTheLock() {
            String src = strippedSourceOf(LISTENER);
            String body = handlerBody(src);
            int[] lock = claimLockSpan(body);
            String lockBody = body.substring(lock[0] + 1, lock[1]);

            assertFalse(lockBody.contains("getLogger"),
                    "领奖 synchronized 块体内出现了 getLogger 喵！\n实测块体：\n"
                            + lockBody.strip() + "\n"
                            + "写日志是阻塞 IO（控制台 + 日志文件 flush）。在锁内记会延长持锁，"
                            + "同 stripe 的其它玩家在主线程上一等 —— 为了观测慢操作而把慢操作拖长。"
                            + "正确形状：锁内只算一个 long，出锁后再判阈值 + 记日志。");
            assertFalse(lockBody.contains("warning("),
                    "领奖 synchronized 块体内出现了 warning( 喵！\n实测块体：\n"
                            + lockBody.strip() + "\n同上：日志调用必须在锁外。");

            int log = body.indexOf("getLogger().warning(");
            assertTrue(log >= 0,
                    "handleDailyChallenge 里找不到 getLogger().warning(...) 喵 —— "
                            + "慢操作告警被删了？本条观测的全部产出就是这一行日志。");
            assertTrue(log > lock[1],
                    "慢操作 WARNING 不在 synchronized 块<b>结束之后</b> 喵（warning@"
                            + log + "，锁结束花括号@" + lock[1] + "）。\n"
                            + "阈值判定与日志都必须出锁后再做，否则等于在锁内做 IO。");
        }

        /**
         * 日志内容必须能定位问题：玩家名 + 挑战 id 都要在。
         *
         * <p>没有玩家名，运维无法确认是「某个人点了很慢的库操作」还是「全服都在慢」；
         * 没有挑战 id，就不知道是哪个挑战走的那条 SQL。只有耗时数字的告警等于没告警。
         */
        @Test
        @DisplayName("L2b：WARNING 带上玩家名与挑战 id（可定位）")
        void warningCarriesPlayerAndChallenge() {
            String body = handlerBody(strippedSourceOf(LISTENER));
            int log = body.indexOf("getLogger().warning(");
            assertTrue(log >= 0, "找不到慢操作 WARNING 喵，本断言无从检查内容。");
            // 必须从 warning( 的实参括号起配对 —— 用 indexOf('(', log) 会命中
            // getLogger() 自己的括号，取出的片段只有 "getLogger()"，
            // 于是「内容里没有玩家名」这类断言在错误的片段上恒红（本条实测过一次）。
            int argsOpen = log + "getLogger().warning".length();
            assertEquals('(', body.charAt(argsOpen),
                    "锚点漂移：getLogger().warning 之后不是 '(' 喵。");
            int end = matchParen(body, argsOpen);
            assertTrue(end > argsOpen, "无法配对 WARNING 的实参括号喵。");
            String message = body.substring(log, end + 1);

            assertTrue(message.contains("player.getName()"),
                    "WARNING 里没有玩家名喵 —— 运维无法区分「某人点了慢操作」还是「全服都慢」。\n"
                            + "实测日志调用：\n" + message);
            assertTrue(message.contains("claimedChallengeId") || message.contains("ChallengeId")
                            || message.contains("challenge="),
                    "WARNING 里没有挑战 id 喵 —— 不知道是哪个挑战走的那条 SQL。\n"
                            + "实测日志调用：\n" + message);
            assertTrue(message.contains("elapsed") || message.contains("Ms") || message.contains("ms"),
                    "WARNING 里没有耗时（毫秒）喵 —— 没有数字就没法判断严重程度。\n"
                            + "实测日志调用：\n" + message);
        }

        /**
         * 挑战 id 必须真的被带出来：{@code claimBehavioralChallenge} 在拿到 {@code cp} 之后
         * 写一次 {@code lastClaimChallengeId}，handler 在锁内把它读回局部变量。
         *
         * <p>为什么不能直接在锁外读 {@code cp}：{@code cp} 是锁内一次 SELECT 的产物，
         * 出锁即不可见；而日志又不能写在锁内。所以必须有一个「锁内写、出锁读」的传递字段 ——
         * 少了这一步，日志里的 challenge 会恒为 {@code <none>}，告警退化成一串耗时时长。
         */
        @Test
        @DisplayName("L2c：挑战 id 有「锁内写 → 出锁读」的传递路径（且非恒 null）")
        void challengeIdIsCarriedOutOfTheLock() {
            String src = strippedSourceOf(LISTENER);
            String claimBody = methodBody(src, CLAIM_SIGNATURE);
            String handlerBody = handlerBody(src);
            int[] lock = claimLockSpan(handlerBody);
            String lockBody = handlerBody.substring(lock[0] + 1, lock[1]);

            assertTrue(claimBody.contains("lastClaimChallengeId = cp.type.id"),
                    "claimBehavioralChallenge 里找不到 `lastClaimChallengeId = cp.type.id` 喵。\n"
                            + "挑战 id 只有查过 getTodayChallenges 之后才知道（那一步在锁内），"
                            + "而日志必须在锁外 —— 所以需要一个「锁内写、出锁读」的传递字段。"
                            + "少了它，WARNING 里的 challenge 恒为 <none>。\n"
                            + "实测方法体：\n" + claimBody.strip());

            assertTrue(lockBody.contains("lastClaimChallengeId = null"),
                    "锁块体开头没有清空 lastClaimChallengeId 喵 —— 本次点击若在「索引越界 / "
                            + "已完成 / 已领取」处提前 return，日志会把<b>上一次</b>领奖的挑战 id "
                            + "错报成本次，运维据此排查会走错方向。");
            // 读回局部变量这一步刻意不锚具体变量名（实现里它叫 targetChallengeId，
            // 但改名是合法重构）。锚的是形状：锁块体内有一处「某变量 = lastClaimChallengeId」，
            // 且该变量在锁外被日志用到。
            assertTrue(lockBody.matches("(?s).*\\w+\\s*=\\s*lastClaimChallengeId\\s*;.*"),
                    "锁块体内没有把 lastClaimChallengeId 读回局部变量喵 —— "
                            + "读回必须在锁内完成（字段随后会被下一次点击覆写），"
                            + "出锁后只用局部变量。\n实测块体：\n" + lockBody.strip());
        }
    }

    // ==================================================================
    //  L3：阈值常量存在，且 50 ms 有出处
    // ==================================================================

    @Nested
    @DisplayName("L3：阈值常量 50 ms 且有出处注释")
    class L3ThresholdConstant {

        /** 常量必须是 {@code 50L}：50 ms = 一个 tick，主线程的物理预算。 */
        @Test
        @DisplayName("L3：CLAIM_SLOW_THRESHOLD_MS 存在且等于 50L")
        void thresholdIsFiftyMillis() {
            String src = strippedSourceOf(LISTENER);
            assertTrue(src.contains("CLAIM_SLOW_THRESHOLD_MS"),
                    "找不到 CLAIM_SLOW_THRESHOLD_MS 喵 —— 阈值被删或被内联成了魔法数。\n"
                            + "内联的 50 无法解释、无法被本 lint 锚定，也没法一眼看出它是 tick 预算。");
            assertTrue(src.contains("CLAIM_SLOW_THRESHOLD_MS = 50L")
                            || src.contains("CLAIM_SLOW_THRESHOLD_MS=50L"),
                    "阈值常量的值不是 50L 喵。\n"
                            + "50 ms = 一个 tick（主线程每 tick 只有 50 ms 预算），"
                            + "一次领奖占掉超过一整个 tick 才会让 TPS 掉 —— 这是玩家可能感觉到"
                            + "卡顿的物理边界，换成别的数就失去了这层含义。");

            String body = handlerBody(src);
            assertTrue(body.contains(">= CLAIM_SLOW_THRESHOLD_MS"),
                    "阈值判定没有用 `elapsed >= CLAIM_SLOW_THRESHOLD_MS` 喵 —— "
                            + "比较方向写反（<=）会让<b>正常</b>领奖每单都刷 WARNING，"
                            + "日志被淹没后真正的慢操作反而看不见。\n实测方法体：\n" + body.strip());
        }

        /**
         * 常量上方的注释必须解释 50 从哪来（tick 预算），而不只是「阈值」两个字。
         *
         * <p>这条用<b>原始源码</b>（不剥注释）判 —— 要看的就是注释本身。
         */
        @Test
        @DisplayName("L3b：阈值常量的注释解释了 50ms = 一个 tick 的来源")
        void thresholdCommentExplainsWhy50() {
            String raw = rawSourceOf(LISTENER);
            int decl = raw.indexOf("CLAIM_SLOW_THRESHOLD_MS = 50");
            assertTrue(decl > 0,
                    "原始源码里找不到 CLAIM_SLOW_THRESHOLD_MS 的声明喵（锚点可能漂移）。");

            int from = Math.max(0, decl - 2500);
            String preceding = raw.substring(from, decl);

            assertTrue(preceding.contains("tick"),
                    "阈值常量上方 2500 字符内没有提到 `tick` 喵。\n"
                            + "50 这个数字唯一的出处就是「主线程一个 tick 只有 50 ms」。"
                            + "不写出来的话，下一个人只会把它当成可随手调的旋钮 —— "
                            + "调到 5 会天天刷屏，调到 500 则永远不响，两种都让本条观测失效。");
            assertTrue(preceding.contains("50"),
                    "阈值常量上方的注释没有复述 50 喵 —— 读者得自己把常量和注释对上。");
        }
    }

    // ==================================================================
    //  P0：夹具守卫（防「在空处通过」）+ 变异自检
    // ==================================================================

    @Nested
    @DisplayName("P0：夹具守卫与变异自检")
    class P0Fixture {

        /**
         * 提取器必须先真的框出东西：一个含 {@code claimBehavioralChallenge} 的
         * synchronize 块体。否则锚点漂移后本类全部断言会在空字符串上恒绿。
         */
        @Test
        @DisplayName("P0a：提取器框到了真实的领奖锁块体（非空且含 claim 调用）")
        void extractorFramesTheRealClaimLock() {
            String body = handlerBody(strippedSourceOf(LISTENER));
            int[] lock = claimLockSpan(body);
            String lockBody = body.substring(lock[0] + 1, lock[1]);

            assertTrue(lockBody.contains("claimBehavioralChallenge"),
                    "框出来的 synchronized 块体里没有 claimBehavioralChallenge 喵 —— "
                            + "提取器框错了位置（例如框到了 synchronized 的表达式）。\n"
                            + "实测块体：\n" + lockBody.strip());
            assertFalse(lockBody.isBlank(),
                    "框出来的锁块体是空的喵 —— 提取器失效时本类的全部断言都会恒绿。");
        }

        /**
         * <b>剥注释是硬要求</b>：原始源码里，领奖相关的说明性注释本身就提到了
         * {@code System.nanoTime()}（解释为什么从锁前开始计时）。
         *
         * <p>不剥注释的 lint 会在<b>注释</b>上判「计时已经在 lock 之前」，于是把计时移进锁内
         * 的变异<b>照样绿</b> —— 这正是本仓 lint 因匹配到注释而假阳性过 4 次以上的形态。
         */
        @Test
        @DisplayName("P0b：原始源码的注释里也提到 nanoTime（所以必须剥注释）")
        void rawSourceMentionsTimingInComments() {
            String rawBody = methodBody(rawSourceOf(LISTENER), HANDLER_SIGNATURE);
            String strippedBody = handlerBody(strippedSourceOf(LISTENER));

            assertTrue(occurrences(rawBody, "nanoTime").size()
                            > occurrences(strippedBody, "nanoTime").size(),
                    "原始方法体里提到 nanoTime 的次数不比剥注释后多喵 —— "
                            + "说明实现里已经没有解释性注释了，本基线的剥注释前置失去意义。"
                            + "若确实是注释被删光，本断言应随之调整（它是夹具守卫，不是契约）。");
        }

        /**
         * <b>变异 1</b>：把 {@code long claimStartNanos = System.nanoTime();} 从
         * synchronized <b>之前</b>挪到块内首行 → L1 用的谓词必须报「计时不在锁外」。
         */
        @Test
        @DisplayName("P0c 变异1：计时移进 synchronized → 计时起点谓词必须变红")
        void movingTimingIntoTheLockTurnsLintRed() {
            String src = strippedSourceOf(LISTENER);
            assertTrue(timeStartsBeforeLock(src),
                    "变异前置失败：真实源码里的计时起点本来就不在锁之前喵（L1 已红）。");

            String mutationAnchor = "synchronized (getClaimStripeLock(player.getUniqueId())) {";
            String mutated = src.replace("long claimStartNanos = System.nanoTime();", "");
            mutated = mutated.replace(mutationAnchor,
                    mutationAnchor + "\n                long claimStartNanos = System.nanoTime();");

            assertNotEquals(src, mutated,
                    "变异注入失败喵 —— 锚点「long claimStartNanos = System.nanoTime();」或"
                            + "「" + mutationAnchor + "」没找到，本自检失去意义"
                            + "（也说明 L1 的锚点可能已漂移）。");

            assertFalse(timeStartsBeforeLock(mutated),
                    "把计时移进 synchronized 之后，L1 的谓词<b>仍然判为绿</b> 喵 —— "
                            + "说明它是恒真的假门禁：将来谁把 nanoTime 挪进锁内（"
                            + "「反正都在这个方法里」是最自然的手滑），"
                            + "「等锁」这段就会静默地从观测里消失，而测试全绿。");
        }

        /**
         * <b>变异 2</b>：把整段慢操作日志（{@code if (claimElapsedMs >= ...) { ... }}）
         * 挪进 synchronized 块体末尾 → L2 用的谓词必须报「锁内有日志 IO」。
         */
        @Test
        @DisplayName("P0d 变异2：日志移进 synchronized → 锁外日志谓词必须变红")
        void movingLogIntoTheLockTurnsLintRed() {
            String src = strippedSourceOf(LISTENER);
            assertTrue(logIsOutsideLock(src),
                    "变异前置失败：真实源码里的慢操作日志本来就不在锁外喵（L2 已红）。");

            String mutated = moveSlowLogIntoLock(src);
            assertNotEquals(src, mutated, "变异注入失败喵 —— 自检无效。");

            assertFalse(logIsOutsideLock(mutated),
                    "把日志挪进 synchronized 之后，L2 的谓词<b>仍然判为绿</b> 喵 —— "
                            + "说明它是恒真的假门禁：锁内写日志是阻塞 IO，"
                            + "会延长持锁时间并把同 stripe 的其它玩家一起拖在主线程上，"
                            + "恰好是本条观测要防的退化。");
        }
    }

    // ==================================================================
    //  被测代码形状的谓词（L1 / L2 与变异自检共用同一份实现）
    //  —— 共用是刻意的：变异自检若另写一份检测逻辑，它证明的就不是主断言。
    // ==================================================================

    /**
     * 计时起点是否在 {@code synchronized} <b>之前</b>。
     *
     * @param strippedSrc 已剥注释的整份源码
     * @return true = 第一个 {@code System.nanoTime()} 采样出现在锁的起始花括号之前
     */
    static boolean timeStartsBeforeLock(String strippedSrc) {
        String body = handlerBody(strippedSrc);
        int[] lock = claimLockSpan(body);
        int first = body.indexOf("System.nanoTime()");
        return first >= 0 && first < lock[0];
    }

    /**
     * 慢操作日志是否落在 {@code synchronized} <b>之外</b>。
     *
     * @param strippedSrc 已剥注释的整份源码
     * @return true = 锁块体内无日志调用，且 WARNING 出现在块结束之后
     */
    static boolean logIsOutsideLock(String strippedSrc) {
        String body = handlerBody(strippedSrc);
        int[] lock = claimLockSpan(body);
        String lockBody = body.substring(lock[0] + 1, lock[1]);
        if (lockBody.contains("getLogger") || lockBody.contains("warning(")) {
            return false;
        }
        int log = body.indexOf("getLogger().warning(");
        return log > lock[1];
    }

    /** {@code handleDailyChallenge} 的方法体（剥注释后）。 */
    static String handlerBody(String strippedSrc) {
        return methodBody(strippedSrc, HANDLER_SIGNATURE);
    }

    /**
     * 领奖 {@code synchronized} 块在 handler 方法体内的 {@code [起始花括号, 结束花括号]}
     * 下标（两者都指向花括号本身）。
     */
    static int[] claimLockSpan(String handlerBody) {
        int sync = handlerBody.indexOf("synchronized");
        if (sync < 0) {
            fail("handleDailyChallenge 里找不到 synchronized 喵 —— 要么 R-02 的双发保护锁被删了"
                    + "（两次快速点击可交错出双发），要么锚点已漂移（本 lint 会静默失效）。");
        }
        int paren = handlerBody.indexOf('(', sync);
        if (paren < 0) {
            fail("synchronized 之后找不到 '(' 喵 —— 提取器失效。");
        }
        int parenEnd = matchParen(handlerBody, paren);
        if (parenEnd < 0) {
            fail("synchronized 的表达式括号配对失败喵 —— 提取器失效。");
        }
        int brace = handlerBody.indexOf('{', parenEnd);
        if (brace < 0) {
            fail("synchronized 表达式之后找不到起始花括号喵 —— 提取器失效。");
        }
        int end = matchBrace(handlerBody, brace);
        if (end < 0) {
            fail("synchronized 块的花括号配对失败喵 —— 提取器失效。");
        }
        return new int[]{brace, end};
    }

    /**
     * 变异 2 的注入：把「算耗时 + 判阈值 + 记日志」这整段尾巴挪进 synchronized 块体末尾。
     *
     * <p>刻意做成「能编译的真实形状」（连 {@code long claimElapsedMs} 声明一起搬，
     * 而不是往锁内随便插一行 {@code getLogger}）：要证明的是
     * {@link #logIsOutsideLock} 能抓到<b>本实现</b>的日志被搬进锁内这一具体退化。
     */
    private static String moveSlowLogIntoLock(String strippedSrc) {
        String body = handlerBody(strippedSrc);
        int bodyStart = strippedSrc.indexOf(body);
        if (bodyStart < 0) {
            fail("无法在整份源码里定位 handleDailyChallenge 方法体喵 —— 变异自检失效。");
        }
        int[] lock = claimLockSpan(body);

        int declIdx = body.indexOf("long claimElapsedMs");
        if (declIdx < 0) {
            fail("找不到 `long claimElapsedMs` 声明喵 —— 变异自检锚点已漂移。");
        }
        int ifIdx = body.indexOf("if (claimElapsedMs");
        if (ifIdx < 0) {
            fail("找不到 `if (claimElapsedMs ...)` 慢操作分支喵 —— 变异自检锚点已漂移。");
        }
        int brace = body.indexOf('{', ifIdx);
        int ifEnd = matchBrace(body, brace);
        if (ifEnd < 0) {
            fail("慢操作 if 块的花括号配对失败喵 —— 变异自检失效。");
        }

        String tail = body.substring(declIdx, ifEnd + 1);
        String withoutTail = body.substring(0, declIdx) + body.substring(ifEnd + 1);
        // 尾巴被摘掉后，锁的结束花括号整体前移 tail.length()
        int lockEndShifted = lock[1] - tail.length();
        if (lockEndShifted < 0) {
            fail("变异位移算错（lockEndShifted=" + lockEndShifted + "）喵 —— 自检失效。");
        }
        String mutatedBody = withoutTail.substring(0, lockEndShifted)
                + "\n" + tail + "\n"
                + withoutTail.substring(lockEndShifted);

        return strippedSrc.substring(0, bodyStart) + mutatedBody
                + strippedSrc.substring(bodyStart + body.length());
    }

    // ==================================================================
    //  工具：源码定位 / 剥注释 / 花括号配对
    //  （本类独立实现一份：与 ClaimLockContentionTest、InMemoryStateDurabilityTest
    //   的提取器失效模式互相独立）
    // ==================================================================

    private static Path srcRoot() {
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

    /** 按文件名唯一定位源码并<b>剥注释</b>（本仓 lint 因匹配到注释假阳性过 4 次以上）。 */
    private static String strippedSourceOf(String fileName) {
        return stripComments(rawSourceOf(fileName));
    }

    /** 按文件名唯一定位源码，原样返回（只有「读注释本身」的断言该用它）。 */
    private static String rawSourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }
        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动 / 改名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (hits.size() > 1) {
            fail("源码树里有 " + hits.size() + " 个 " + fileName + "，无法唯一定位喵。");
        }
        try {
            return Files.readString(hits.get(0), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /** 剥掉行注释与块注释（保留字符串与字符字面量内的内容）。 */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(s.charAt(i));
                        i++;
                        continue;
                    }
                    if (d == '"') {
                        break;
                    }
                }
                continue;
            }
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(s.charAt(i));
                        i++;
                        continue;
                    }
                    if (d == '\'') {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 提取某个方法签名之后、由配对花括号包住的方法体内容。 */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) {
            fail("找不到方法签名喵：" + signature + "\n本 lint 的锚点可能已漂移。");
        }
        int brace = src.indexOf('{', start + signature.length());
        if (brace < 0) {
            fail("方法签名之后找不到起始花括号喵：" + signature);
        }
        int end = matchBrace(src, brace);
        if (end < 0) {
            fail("花括号配对失败喵：" + signature);
        }
        return src.substring(brace + 1, end);
    }

    /** {@code needle} 在 {@code s} 里所有出现的起始下标。 */
    private static List<Integer> occurrences(String s, String needle) {
        List<Integer> hits = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = s.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            hits.add(idx);
            from = idx + needle.length();
        }
        return hits;
    }

    /** 返回与 {@code open} 处 '{' 配对的位置（{@code open} 必须是 '{'）。 */
    private static int matchBrace(String s, int open) {
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

    /** 返回与 {@code open} 处 '(' 配对的位置（{@code open} 必须是 '('）。 */
    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
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
}
