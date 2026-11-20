package com.letslearnenglish.minecraftplugin.listener;

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
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Round 21：领奖 stripe lock 的<b>争用形状</b>与<b>付款顺序</b>门禁。
 *
 * <h2>本类钉住的三条事实（都不依赖 Bukkit 运行时）</h2>
 * <ol>
 *   <li><b>条纹数量</b>：{@code new Object[32]} 这个 32 是「同 stripe 会撞几个玩家」的
 *       唯一决定因素。把它悄悄改成 8 或 4，单测里<b>什么都看不出来</b> —— 没有并发压测，
 *       mock 也不会真的争锁。故用源码 lint 钉住数量下界，并把碰撞期望值算出来写进断言消息。</li>
 *   <li><b>锁内不得再取第二条 stripe</b>：死锁的唯一可能形状是「持 A 带锁，再取 B 带」。
 *       实测当前实现没有这种嵌套；本 lint 保证将来也不会出现。</li>
 *   <li><b>先付款、后记账</b>：{@code depositChallengeReward} 必须早于
 *       {@code markClaimedOrFail}（在 paid 分支内）。顺序一反过来就是本仓已经踩过的
 *       「台账已写、钱没发」——比「钱已发、台账未落」更糟（玩家看到已领取却一分没拿到，
 *       且该行永远不可重试）。方案 D（缩小锁粒度）恰好会破坏这条，本 lint 会红。</li>
 * </ol>
 *
 * <h2>为什么是源码 lint 而不是行为测试</h2>
 * 「主线程持锁等多久」在本仓的单测环境里<b>不可观测</b>：没有真实 tick 循环，没有真实
 * MySQL socket，mock 的连接层也不消费 {@code socketTimeout}。把 6 条 JDBC 语句塞回锁内，
 * 所有行为断言依然全绿 —— 唯一会变的是<b>代码形状</b>。与
 * {@code InMemoryStateDurabilityTest.N12StripeLockReachability} 同一层次判断，但那条钉的是
 * 「sleep 链不可达」，本条钉的是「条纹数与付款顺序」，失效模式互相独立。
 *
 * <p><b>纯 JVM</b>：只有文件读取与算术，无 Bukkit / Mockito / DB 依赖。
 */
@DisplayName("R21：领奖 stripe lock 的争用形状与付款顺序")
class ClaimLockContentionTest {

    private static final String LISTENER = "MiscGUIListener.java";
    private static final String MANAGER = "DailyChallengeManager.java";

    // ==================================================================
    //  L1：条纹数量（争用形状的唯一决定因素）
    // ==================================================================

    @Nested
    @DisplayName("L1：条纹数量决定同 stripe 碰撞期望")
    class L1StripeCount {

        /**
         * {@code getClaimStripeLocks()} 返回的数组必须有足够多的条带。
         *
         * <p><b>为什么 32 是下界而不是随便一个数</b>：同 stripe 的玩家会<b>互相等待</b>
         * 整个「check → deposit → mark」临界区。条纹越少，撞的概率越高，且这个代价随在线
         * 人数超线性增长。32 条时 100 人在线的期望撞车伙伴数约 3.1 人；若改成 8 条，同样
         * 100 人会变成约 12.4 人 —— 而<b>任何单测都不会因此变红</b>，因为没人真的并发点击。
         */
        @Test
        @DisplayName("L1a：条纹数组长度为 32（缩小即争用放大，且无行为测试能发现）")
        void stripeArrayHasThirtyTwoSlots() {
            String src = sourceOf(MANAGER);

            assertTrue(src.contains("new Object[32]"),
                    "DailyChallengeManager 里找不到 `new Object[32]` 喵 —— 条纹数组可能被改名、"
                            + "改成常量、或改了长度。\n"
                            + "本 lint 的锚点是字面量 32：若改成常量请同步本断言（否则门禁静默失效）。"
                            + "\n理由：条纹数是同 stripe 争用的唯一决定因素，而争用在单测里不可观测。");
        }
    }

    // ==================================================================
    //  L2：锁内不得取第二条 stripe（死锁的唯一形状）
    // ==================================================================

    @Nested
    @DisplayName("L2：领奖锁块内不得嵌套第二条 stripe（死锁形状）")
    class L2NoNestedStripe {

        /**
         * {@code synchronized (getClaimStripeLock(...))} 的<b>块体</b>里不得再出现
         * {@code getClaimStripeLock}。
         *
         * <p>当前实现全程只持一条 stripe（{@code checkAndAssign} → 同一条可重入，
         * {@code DailyChallengeManager:701} 已注明「锁可重入」）。一旦有人在锁内取<b>另一个
         * 玩家</b>的 stripe（例如「帮好友代领」），两个玩家同时互领就构成经典的 AB-BA
         * 死锁，而且是在<b>主线程</b>上死锁 = 整个服务器卡死，比性能问题严重一个数量级。
         *
         * <p>注意：{@code getClaimStripeLock} 出现在 {@code synchronized} 的<b>表达式</b>里是
         * 正常的（那正是取锁本身）；本断言只看<b>块体</b>。
         */
        @Test
        @DisplayName("L2：领奖 synchronized 块体内不得再调用 getClaimStripeLock")
        void claimBlockDoesNotNestASecondStripeLock() {
            String src = sourceOf(LISTENER);
            List<String> blocks = synchronizedBlockBodies(src);

            assertFalse(blocks.isEmpty(),
                    "MiscGUIListener 里找不到任何 synchronized 块喵 —— 要么锁被删了（R-02 的"
                            + "双发保护失效），要么提取器锚点与代码形状脱节（门禁静默失效）。");

            List<String> claimBlocks = bodiesContaining(blocks, "claimBehavioralChallenge");
            assertFalse(claimBlocks.isEmpty(),
                    "没有任何 synchronized 块体包含 claimBehavioralChallenge 喵 ——"
                            + " 提取器可能框错了位置。实测块体数=" + blocks.size()
                            + "\n若在空集合上断言，本 lint 会恒绿。");

            for (String body : claimBlocks) {
                assertFalse(body.contains("getClaimStripeLock"),
                        "领奖锁块体内又取了一次 stripe lock 喵！\n实测块体：\n" + body.strip()
                                + "\n这是 AB-BA 死锁的形状（持 A 带取 B 带）。领奖跑在<b>主线程</b>，"
                                + "一旦死锁就是整服卡死。若确需跨玩家操作，请改成：先释放本玩家"
                                + " stripe，再按 UUID 升序逐个取（固定全局顺序）。");
            }
        }
    }

    // ==================================================================
    //  L3：先付款、后记账（至多一次付款的前提）
    // ==================================================================

    @Nested
    @DisplayName("L3：deposit 必须早于 mark（顺序反转比现状更糟）")
    class L3DepositBeforeMark {

        /**
         * paid 分支内 {@code markClaimedOrFail} 必须出现在 {@code depositChallengeReward}
         * <b>之后</b>。
         *
         * <p><b>为什么这条比性能更值得钉</b>：仓库里已有三态 {@code ClaimMark} 的存在理由
         * 就是「钱可能已转账、台账未落」（{@code DailyChallengeRepository:309-311}）。
         * 顺序反过来变成「台账已写、钱没发」，后果严格更差：
         * <ul>
         *   <li>玩家看到「已领取」但余额一分没动；</li>
         *   <li>台账已收盘（{@code claimed=1}），{@code markClaimedOrFail} 的
         *       {@code completed=1 AND claimed=0} 护栏从此永远不满足 →
         *       <b>这笔钱永久不可重试</b>；</li>
         *   <li>跨天后 {@code getTodayChallenges} 只读当日行，昨天的 pending 行再没有补发入口。</li>
         * </ul>
         * 这正是 {@code MiscGUIListener:461-467} 那段裁决在防的形态（「永远点不完的按钮」）。
         *
         * <p><b>本断言会红的两种变异</b>：① 方案 D 把 deposit 挪出锁、mark 留在锁内（顺序
         * 反转）；② 方案 C 的「先抢占再异步发钱」（mark 提前到 deposit 之前）。两者都被本
         * lint 拦下 —— 这是本类对「该不该改」这个问题的可执行答案。
         */
        @Test
        @DisplayName("L3：claimBehavioralChallenge 的 paid 分支内 deposit 早于 mark")
        void depositHappensBeforeMark() {
            String src = sourceOf(LISTENER);
            String body = methodBody(src, "private void claimBehavioralChallenge(Player player, int idx)");

            int deposit = body.indexOf("depositChallengeReward(player, reward)");
            assertTrue(deposit >= 0,
                    "claimBehavioralChallenge 里找不到 depositChallengeReward(player, reward) 喵 ——"
                            + " 方法体提取可能失败，或付款入口被改名。本 lint 不能在空处通过。");

            // 必须有一次 mark 在 deposit 之后 —— 付款完不记账 = 可重复领取（R-02 双发）。
            // 注意不能用 indexOf(needle, deposit) 来证明「在后面」：带 fromIndex 的 indexOf
            // 只要命中就必然 >= fromIndex，那样的断言永远为真（空洞门禁）。
            int markAfterDeposit = body.indexOf("markClaimedOrFail", deposit);
            assertTrue(markAfterDeposit >= 0,
                    "deposit 之后找不到 markClaimedOrFail 喵 —— 付款完却不记账，"
                            + "玩家可重复领取同一格（R-02 双发）。实测方法体片段：\n"
                            + body.substring(Math.max(0, deposit - 200)));

            // 真正的顺序门禁：deposit 之前**只允许**有一次 mark，即 !moneyEnabled 分支
            // （那条路径没有任何付款，标已领是正确的终态处理）。
            // 若有人把 mark 提到 deposit 之前（方案 C 的「先抢占再异步发钱」），或把 deposit
            // 挪到锁外而 mark 留在锁内（方案 D），deposit 前的 mark 次数就会变成 2 —— 本断言红。
            List<Integer> marksBefore = occurrencesBefore(body, "markClaimedOrFail", deposit);
            assertEquals(1, marksBefore.size(),
                    "depositChallengeReward 之前出现了 " + marksBefore.size()
                            + " 次 markClaimedOrFail（期望恰好 1 次 = !moneyEnabled 分支）喵。\n"
                            + "实测位置：" + marksBefore + "，deposit 位于 " + deposit + "\n"
                            + "≥2 次意味着「台账已写、钱没发」：玩家看到已领取但余额未动，"
                            + "且 claimed=1 使 completed=1 AND claimed=0 护栏永不满足 → "
                            + "这笔钱永久不可重试（跨天后无补发入口）。\n"
                            + "0 次则说明连 economy-disabled 分支的收盘都没了。\n"
                            + "实测方法体：\n" + body.strip());
        }

        /** {@code needle} 在 {@code s} 的 {@code [0, limit)} 区间内所有出现的起始下标。 */
        private List<Integer> occurrencesBefore(String s, String needle, int limit) {
            List<Integer> hits = new ArrayList<>();
            int from = 0;
            while (true) {
                int idx = s.indexOf(needle, from);
                if (idx < 0 || idx >= limit) {
                    break;
                }
                hits.add(idx);
                from = idx + needle.length();
            }
            return hits;
        }
    }

    // ==================================================================
    //  争用量化（纯算术 —— 把「32 条够不够」写成可复核的数字）
    // ==================================================================

    @Nested
    @DisplayName("L4：碰撞期望的量化（给「该不该改」提供数字）")
    class L4CollisionMath {

        private static final int STRIPES = 32;

        /**
         * 同 stripe 碰撞期望：{@code E[伙伴数] = (N-1) / 32}，
         * 玩家自身被迫排队的概率 {@code = 1 - (31/32)^(N-1)}。
         *
         * <p>这组数字是本轮「不改」结论的依据之一：100 人在线时也只有约 3.1 个潜在伙伴，
         * 而<b>潜在伙伴 ≠ 同时点击</b>。领奖是低频操作（每人每天每挑战至多一次），两次
         * 点击落在同一个几毫秒窗口内的概率远小于碰撞概率本身。
         */
        @Test
        @DisplayName("L4：在线 20 / 50 / 100 人的碰撞期望")
        void collisionExpectationIsSmall() {
            // N=20：19/32 = 0.59375；1-(31/32)^19 = 0.4529
            assertEquals(0.59375, expectedPartners(20), 1e-9,
                    "在线 20 人时，一个玩家的同 stripe 期望伙伴数喵");
            assertTrue(Math.abs(blockedProbability(20) - 0.4529) < 5e-4,
                    "在线 20 人时被迫排队概率应约 45.3%，实测 " + blockedProbability(20));

            // N=50：49/32 = 1.53125；1-(31/32)^49 = 0.7890
            assertEquals(1.53125, expectedPartners(50), 1e-9,
                    "在线 50 人时，期望伙伴数喵");
            assertTrue(Math.abs(blockedProbability(50) - 0.7890) < 5e-4,
                    "在线 50 人时被迫排队概率应约 78.9%，实测 " + blockedProbability(50));

            // N=100：99/32 = 3.09375；1-(31/32)^99 = 0.9568
            assertEquals(3.09375, expectedPartners(100), 1e-9,
                    "在线 100 人时，期望伙伴数喵");
            assertTrue(Math.abs(blockedProbability(100) - 0.9568) < 5e-4,
                    "在线 100 人时被迫排队概率应约 95.7%，实测 " + blockedProbability(100));

            // 条纹数若被缩小，争用会显著放大 —— 这条把 L1 的「为什么 32 是下界」量化。
            assertTrue(partnersIfStripesWere(100, 8) > 12.0,
                    "若条纹数从 32 降到 8，100 人在线的期望伙伴数会升到 "
                            + partnersIfStripesWere(100, 8) + " 人（现状 3.09 人）—— "
                            + "这是 L1 钉住 new Object[32] 的定量理由。");
        }

        /** 期望伙伴数 = (N-1) / stripes。 */
        private double expectedPartners(int online) {
            return (double) (online - 1) / STRIPES;
        }

        /** 若条纹数改成 {@code stripes}，N 人在线的期望伙伴数。 */
        private double partnersIfStripesWere(int online, int stripes) {
            return (double) (online - 1) / stripes;
        }

        /** 一个玩家至少与另一人同带（因而可能被迫排队）的概率：1 - ((S-1)/S)^(N-1)。 */
        private double blockedProbability(int online) {
            return 1.0 - Math.pow((STRIPES - 1.0) / STRIPES, online - 1);
        }
    }

    // ==================================================================
    //  工具：源码定位 / 剥注释 / 花括号配对 / synchronized 块提取
    //  （独立实现一份：与 InMemoryStateDurabilityTest 的提取器失效模式互相独立）
    // ==================================================================

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

    private List<Path> allMainSources() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(files::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }
        return files;
    }

    /** 按文件名唯一定位源码，<b>剥注释后</b>返回（本仓 lint 因匹配到注释假阳性过 4 次以上）。 */
    private String sourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        for (Path p : allMainSources()) {
            if (p.getFileName().toString().equals(fileName)) {
                hits.add(p);
            }
        }
        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动 / 改名，"
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

    /**
     * 剥掉行注释与块注释（保留字符串字面量内的内容）。
     *
     * <p>必须剥：{@code claimBehavioralChallenge} 的 javadoc 里就写着
     * {@code markClaimedOrFail} 与 {@code claimBehavioralChallenge} —— 不剥会让
     * 「块体内出现了某名字」这类断言在纯注释上误判。
     */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' ) {
                // 复制整个字符串字面量（含转义）
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

    /** 提取全文件里每个 {@code synchronized (...)} 的花括号<b>块体</b>内容（不含表达式本身）。 */
    private static List<String> synchronizedBlockBodies(String src) {
        List<String> bodies = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = src.indexOf("synchronized", from);
            if (idx < 0) {
                break;
            }
            // 跳过 "synchronized (" 到其配对 ')'，再取后面的 '{'
            int paren = src.indexOf('(', idx);
            if (paren < 0) {
                break;
            }
            int parenEnd = matchParen(src, paren);
            if (parenEnd < 0) {
                break;
            }
            int brace = src.indexOf('{', parenEnd);
            if (brace < 0) {
                break;
            }
            int end = matchBrace(src, brace);
            if (end < 0) {
                break;
            }
            bodies.add(src.substring(brace + 1, end));
            from = end + 1;
        }
        return bodies;
    }

    private static List<String> bodiesContaining(List<String> bodies, String needle) {
        List<String> hits = new ArrayList<>();
        for (String b : bodies) {
            if (b.contains(needle)) {
                hits.add(b);
            }
        }
        return hits;
    }

    /** 返回与 {@code open} 处 '}' 配对的位置（{@code open} 必须是 '{'）。 */
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
