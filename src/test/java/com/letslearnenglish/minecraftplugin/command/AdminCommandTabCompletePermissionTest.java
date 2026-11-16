package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * round12 · A4-LOW-1 回归：{@code AdminCommand.onTabComplete} 的「args≥2 权限复核」
 * 曾经只覆盖 {@code args.length == 2}，注释却写「args≥2」。
 *
 * <h2>缺陷本体</h2>
 * 原实现的复核守卫嵌在 {@code else if (args.length == 2)} 里，于是三条 {@code args==3}
 * 分支（{@code duel cancel <玩家>} / {@code class add|remove <玩家>} /
 * {@code report <玩家> <周期>}）<b>完全不复核</b>：一个只持
 * {@code letslearnenglish.report} 的老师，甚至一个权限全无的普通玩家，敲
 * {@code /lea duel cancel <tab>} 就能拿到在线玩家名单，并由此确认 duel 子命令存在。
 *
 * <h2>两条修法，本类为什么钉「改守卫」而不是「改注释」</h2>
 * 注释写的是<b>设计意图</b>（补全不得泄露子命令的存在性），实现只覆盖了意图的一半。
 * 把注释降级成「args==2」等于承认意图不成立，而意图本身是对的、对每个参数深度都成立。
 * 所以守卫升成 args≥2。<b>唯一的例外</b>是 report 权限者的合法用法 ——
 * 判据里显式保留 {@code (isReport && "report".equalsIgnoreCase(args[0]))}，
 * 因此 {@code /lea report <玩家> <tab>} 仍然补全（本类有正向用例钉住这一格）。
 *
 * <h2>被本类封死的变异（每条断言都要能红）</h2>
 * <ol>
 *   <li><b>把守卫挪回 {@code args.length == 2} 分支内</b> → 三条
 *       {@code args>=3 均不复核} 的用例红（无权限者拿到玩家名）；</li>
 *   <li><b>把 {@code args[0]} 的 report 例外删掉</b>（守卫变成纯 admin 门） →
 *       {@code reportTeacherStillCompletesThirdArg} 红；</li>
 *   <li><b>把守卫换成 {@code return completions;} 无条件</b>（过度修复）→
 *       同一个正向用例红（admin 也拿不到补全）；</li>
 *   <li><b>删掉 {@code if (args.length < 2) return}</b> → 空 args 用例红（AIOOBE）。</li>
 * </ol>
 */
@DisplayName("AdminCommandTabCompletePermission — args≥2 统一复核")
class AdminCommandTabCompletePermissionTest {

    /** 只持报告权限的老师（US-RPT-3 的目标用户）。 */
    private static final String PERM_ADMIN = "letslearnenglish.admin";
    private static final String PERM_REPORT = "letslearnenglish.report";

    private LetsLearnEnglish plugin;
    private Command command;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        command = mock(Command.class);
        // 补全方法的第一个动作是插件总开关；本类只测权限分支，必须让它为 true，
        // 否则所有用例都从「关闭态只给 reload」提前 return（等于什么都没测）。
        when(plugin.isFullyEnabled()).thenReturn(true);
        // **在线玩家必须始终可用**（不只是正向用例）。
        // 理由（红队视角）：守卫坏掉时，三条 args>=3 分支会真的走到取玩家名的代码。
        // 若这里不 stub，失败形态是 NPE —— 而 NPE 与「测试自己写错」不可区分，
        // 断言消息也不会说出「泄露了谁的名字」。stub 之后失败形态变成
        // 「期望空表，实际 [Alice, Bob]」，变异信号才是精确的。
        stubOnlinePlayers();
    }

    /** 造一个只有给定权限为 true 的 sender（其余 hasPermission 恒 false）。 */
    private CommandSender senderWith(String... granted) {
        CommandSender sender = mock(CommandSender.class);
        for (String perm : granted) {
            when(sender.hasPermission(perm)).thenReturn(true);
        }
        return sender;
    }

    private List<String> complete(CommandSender sender, String... args) {
        return new AdminCommand(plugin).onTabComplete(sender, command, "lea", args);
    }

    // ==================================================================
    // 缺陷本体：args>=3 的三条分支此前完全不复核
    // ==================================================================

    @Nested
    @DisplayName("组 A —— args>=3 必须复核（缺陷本体）")
    class DeepArgsAreGuarded {

        /**
         * 这条是缺陷的最直接复现：无任何权限的人敲 {@code /lea duel cancel <tab>}。
         *
         * <p>若把守卫挪回 {@code args.length == 2}，这里会拿到在线玩家名。
         * {@code plugin.getServer()} 未 stub → mock 返回 null，一旦走到补全体就 NPE，
         * 所以「不抛 + 空表」同时证明了两件事：守卫真的拦住了，且没走到取玩家名的代码。
         */
        @Test
        @DisplayName("无权限者 /lea duel cancel <tab> → 空表（不得泄露在线玩家名）")
        void duelCancelThirdArgIsGuardedForNobody() {
            List<String> out = complete(senderWith(), "duel", "cancel", "");

            assertTrue(out.isEmpty(),
                    "无任何权限的玩家不得通过 /lea duel cancel <tab> 拿到在线玩家名喵 —— "
                            + "拿到名字就同时确认了 duel 子命令的存在。实际返回：" + out);
        }

        @Test
        @DisplayName("无权限者 /lea class add <tab> → 空表")
        void classAddThirdArgIsGuardedForNobody() {
            assertTrue(complete(senderWith(), "class", "add", "").isEmpty(),
                    "class add 的第三参数（在线玩家名）同样必须被 args≥2 复核挡住喵");
        }

        @Test
        @DisplayName("无权限者 /lea class remove <tab> → 空表")
        void classRemoveThirdArgIsGuardedForNobody() {
            assertTrue(complete(senderWith(), "class", "remove", "").isEmpty(),
                    "class remove 与 add 是同一条分支，必须一起被挡住喵");
        }

        @Test
        @DisplayName("无权限者 /lea report <玩家> <周期> → 空表")
        void reportThirdArgIsGuardedForNobody() {
            assertTrue(complete(senderWith(), "report", "Alice", "").isEmpty(),
                    "无权限者的 report 第三参数也不得补全周期候选喵");
        }

        /**
         * 只持 report 权限的老师去补全 <b>duel</b> —— 这一格是「升守卫」唯一的
         * 行为变化，也正是设计意图要求的变化：report 权限不该换来 duel 的信息面。
         */
        @Test
        @DisplayName("只持 report 的老师 /lea duel cancel <tab> → 空表（权限不跨子命令）")
        void reportTeacherCannotCompleteDuelPlayers() {
            List<String> out = complete(senderWith(PERM_REPORT), "duel", "cancel", "");

            assertTrue(out.isEmpty(),
                    "letslearnenglish.report 只覆盖 report 子命令，不该让持它的人补全 duel 的"
                            + "在线玩家名喵。实际返回：" + out);
        }

        @Test
        @DisplayName("只持 report 的老师 /lea class add <tab> → 空表")
        void reportTeacherCannotCompleteClassPlayers() {
            assertTrue(complete(senderWith(PERM_REPORT), "class", "add", "").isEmpty(),
                    "同上：class 是 admin 动作，report 权限不得换来它的补全喵");
        }
    }

    // ==================================================================
    // 正向对照：修复不能把合法用法一起挡掉
    // ==================================================================

    @Nested
    @DisplayName("组 B —— 合法用法必须仍然补全")
    class LegitimateUseStillWorks {

        /**
         * <b>过度修复的证伪用例</b>：把守卫写成无条件 {@code return completions;}
         * 会让这条红 —— 而所有组 A 的断言仍然全绿。少了这条，「一律不放行」这种
         * 明显错误的实现能通过整个类。
         */
        @Test
        @DisplayName("admin 补全 /lea duel cancel <tab> → 走到补全体（不因新守卫被挡）")
        void adminStillReachesDuelCompletionBody() {
            List<String> out = complete(senderWith(PERM_ADMIN), "duel", "cancel", "");

            assertEquals(List.of("Alice", "Bob"), out,
                    "admin 必须仍能补全 duel cancel 的玩家名喵 —— 新守卫只该挡住没有权限的人，"
                            + "不该把 admin 一起挡住（那是过度修复）。实际返回：" + out);
        }

        /**
         * report 权限者的核心用法：{@code /lea report <玩家> <tab>} 补全周期。
         *
         * <p>变异指向：守卫若丢掉 {@code (isReport && "report".equalsIgnoreCase(args[0]))}
         * 这个例外（变成纯 admin 门），本用例立刻红。这是「升守卫」唯一需要小心的地方，
         * 也正是它必须被钉死的原因。
         */
        @Test
        @DisplayName("只持 report 的老师 /lea report <玩家> <tab> → 仍补全周期候选")
        void reportTeacherStillCompletesThirdArg() {
            List<String> out = complete(senderWith(PERM_REPORT), "report", "Alice", "");

            assertTrue(out.contains("week"),
                    "report 权限者必须仍能补全 /lea report <玩家> <周期> 喵 —— "
                            + "守卫的例外分支被删掉就是这个用例变红的时刻。实际返回：" + out);
            assertTrue(out.contains("lastweek"), "周期候选集应与实现一致喵。实际返回：" + out);
        }

        @Test
        @DisplayName("只持 report 的老师 /lea report <tab> → 仍补全玩家名与 all")
        void reportTeacherStillCompletesSecondArg() {

            List<String> out = complete(senderWith(PERM_REPORT), "report", "");

            assertTrue(out.contains("all"), "第二参数的 all 保留字必须仍可补全喵：" + out);
            assertTrue(out.contains("Alice"), "在线玩家名必须仍可补全喵：" + out);
        }

        @Test
        @DisplayName("admin 补全 /lea class add <tab> → 走到补全体")
        void adminStillReachesClassCompletionBody() {

            assertEquals(List.of("Alice", "Bob"),
                    complete(senderWith(PERM_ADMIN), "class", "add", ""),
                    "admin 的 class add 第三参数补全不得被新守卫影响喵");
        }

        @Test
        @DisplayName("admin 补全 /lea report <玩家> <tab> → 周期候选")
        void adminAlsoCompletesReportPeriod() {
            List<String> out = complete(senderWith(PERM_ADMIN), "report", "Alice", "");
            assertTrue(out.contains("week"), "admin 走 isAdmin 分支，必须同样能补全周期喵：" + out);
        }
    }

    // ==================================================================
    // args==1 的既有行为不得被新守卫破坏
    // ==================================================================

    @Nested
    @DisplayName("组 C —— args==1 既有行为不变")
    class SingleArgUnchanged {

        /**
         * args==1 走的是另一个分支（按子命令名过滤），在本轮改动<b>之前</b>就 return 了。
         * 这里钉住它没被新守卫波及。
         */
        @Test
        @DisplayName("admin 补全 /lea <tab> → 全部子命令")
        void adminSeesAllSubcommands() {
            List<String> out = complete(senderWith(PERM_ADMIN), "");
            assertTrue(out.contains("reload"), "admin 必须看到全部子命令喵：" + out);
            assertTrue(out.contains("duel"), "含 duel 喵：" + out);
            assertTrue(out.contains("class"), "含 class 喵：" + out);
        }

        @Test
        @DisplayName("只持 report 的老师 /lea <tab> → 只看到 report")
        void reportTeacherSeesOnlyReport() {
            assertEquals(List.of("report"), complete(senderWith(PERM_REPORT), ""),
                    "US-RPT-3：只有 report 权限的老师只该看到 report 一个子命令喵");
        }

        @Test
        @DisplayName("无权限者 /lea <tab> → 空表")
        void nobodySeesNothing() {
            assertTrue(complete(senderWith(), "").isEmpty(),
                    "无权限者不该看到任何子命令喵");
        }

        /**
         * args.length==0 在 Bukkit 里理论上不会出现（敲 {@code /lea } 是 {@code [""]}），
         * 但方法要读 {@code args[0]}，长度 0 会 ArrayIndexOutOfBounds。新守卫里
         * {@code if (args.length < 2) return} 就是挡这个 —— 删掉它本用例红。
         */
        @Test
        @DisplayName("args 长度 0 不崩（不得 ArrayIndexOutOfBounds）")
        void emptyArgsDoesNotThrow() {
            List<String> out = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> complete(senderWith(PERM_ADMIN), new String[0]),
                    "args 长度为 0 时不得抛 AIOOBE喵");
            assertTrue(out.isEmpty(), "长度为 0 的分支没有可补全的内容，必须返回空表喵");
        }
    }

    // ==================================================================
    // 静态守卫：缺陷形态（守卫嵌在 args==2 里）不得回来
    // ==================================================================

    @Nested
    @DisplayName("组 D —— 源码 lint：args≥2 守卫必须在 args==2 分支之外")
    class GuardMustStayHoisted {

        private static final Path SOURCE = Paths.get(
                "src/main/java/com/letslearnenglish/minecraftplugin/command/AdminCommand.java");

        /**
         * 判定函数：{@code onTabComplete} 方法体内，「权限复核」那三行出现的<b>位置</b>
         * 必须早于第一个 {@code args.length == 2} 分支入口。
         *
         * <p><b>为什么要 lint 而不是只靠行为测试</b>：行为测试能证明当前实现正确，
         * 但「下一轮有人把守卫挪回 args==2 里」是一个纯结构变更，而挪回去之后
         * 组 A 的 6 条用例都会红 —— 那已经够了。本 lint 的价值是<b>在失败消息里说清原因</b>：
         * 行为测试红了只会显示「期望空表实际有 2 个元素」，读的人未必立刻知道
         * 「守卫被挪进 args==2 分支了」。两类断言一起，才能让下次的修复方向无歧义。
         *
         * <p><b>防空转</b>：判定函数先在合成源码上自检两个方向，再应用到真实文件 ——
         * 否则「正则恒不命中」和「真实文件正确」在全绿的输出里不可区分。
         */
        @Test
        @DisplayName("判定函数自检：守卫在 args==2 之前 → 通过；嵌在 args==2 之内 → 判红")
        void detectorSelfCheck() throws Exception {
            String hoisted = String.join("\n",
                    "class Probe {",
                    "  List<String> onTabComplete(String[] args) {",
                    "    if (!isAdmin && !(isReport && \"report\".equalsIgnoreCase(args[0]))) {",
                    "        return completions;",
                    "    }",
                    "    if (args.length == 2) {",
                    "        return completions;",
                    "    }",
                    "    return completions;",
                    "  }",
                    "}");

            String nested = String.join("\n",
                    "class Probe {",
                    "  List<String> onTabComplete(String[] args) {",
                    "    if (args.length == 2) {",
                    "        if (!isAdmin && !(isReport && \"report\".equalsIgnoreCase(args[0]))) {",
                    "            return completions;",
                    "        }",
                    "        return completions;",
                    "    }",
                    "    return completions;",
                    "  }",
                    "}");

            assertTrue(guardPrecedesTwoArgBranch(hoisted),
                    "自检失败：守卫早于 args==2 的合成源码被判成非法 —— 判定函数写反了");
            assertFalse(guardPrecedesTwoArgBranch(nested),
                    "自检失败：守卫嵌在 args==2 里的合成源码被判成合法 —— 判定函数空转了");
        }

        @Test
        @DisplayName("真实文件：args≥2 复核必须早于 args.length == 2 分支")
        void realSourceKeepsGuardHoisted() throws Exception {
            if (!Files.isRegularFile(SOURCE)) {
                fail("找不到 " + SOURCE.toAbsolutePath() + "（测试必须以仓库根为工作目录）");
            }
            String src = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertTrue(guardPrecedesTwoArgBranch(src),
                    "AdminCommand.onTabComplete 里的 args≥2 权限复核必须出现在 "
                            + "`args.length == 2` 分支**之外**（早于它）。\n"
                            + "把守卫挪回 args.length==2 分支内 = round12 A4-LOW-1 的原始缺陷："
                            + " args>=3 的 duel/class/report 分支将完全不复核，"
                            + "无权限者可通过 /lea duel cancel <tab> 拿到在线玩家名。");
        }

        /**
         * 真相判定：找出 {@code onTabComplete} 方法体内「权限复核」的位置与
         * 「args.length == 2」分支入口的位置，要求前者严格更早。
         *
         * <p><b>必须先剥注释</b>：本仓（含本文件自己刚写的）注释里到处写着
         * {@code args.length == 2} 作为说明文字。不剥的话第一次命中就是注释里的那一处，
         * 判定结果与被测代码结构无关 —— 本类第一版正是这么假红的（实际守卫已前置，
         * 注释里的旧代码片段却被当成了分支）。这与 {@code PermissionContractLintTest}
         * 的「必须剥注释」是同一条教训。
         *
         * <p>用 {@code args.length == 2} 而不是 {@code else if (args.length == 2)}：
         * 重构可能把 else-if 拆成独立 if，判据不该因为这种等价改写而失效 ——
         * 但只要那个<b>条件</b>作为分支条件出现，它就必须晚于守卫。
         */
        private boolean guardPrecedesTwoArgBranch(String src) {
            String code = stripComments(src);
            int methodAt = code.indexOf("List<String> onTabComplete(");
            if (methodAt < 0) {
                return false;
            }
            // 守卫的判据表达式（实现里写成一行 if 的条件）
            int guardAt = code.indexOf(
                    "!isAdmin && !(isReport && \"report\".equalsIgnoreCase(args[0]))", methodAt);
            // 第一个 args.length == 2 分支
            int twoArgAt = code.indexOf("args.length == 2", methodAt);
            if (guardAt < 0 || twoArgAt < 0) {
                return false; // 两者都必须在：缺任一个说明实现形状已变，宁可变红
            }
            return guardAt < twoArgAt;
        }

        /**
         * 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，字符串字面量整体照搬
         * （避免把 {@code "http://..."} 里的 {@code //} 误当行注释）。
         *
         * <p>本仓无字符字面量 {@code '"'}、无文本块，已 grep 核过；若将来引入，
         * 这里会连同注释一起把内容当字符串，判定仍旧只看「有没有那两段文本」，
         * 最多是保守地多扫几行，不会误判成缺陷形态。
         */
        private String stripComments(String src) {
            StringBuilder out = new StringBuilder(src.length());
            int i = 0;
            int n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                    while (i < n && src.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i = Math.min(n, i + 2);
                } else if (c == '"') {
                    out.append(c);
                    i++;
                    while (i < n) {
                        char d = src.charAt(i);
                        out.append(d);
                        i++;
                        if (d == '\\' && i < n) {
                            out.append(src.charAt(i));
                            i++;
                        } else if (d == '"') {
                            break;
                        }
                    }
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }
    }

    // ------------------------------------------------------------------
    /**
     * {@code getOnlinePlayers()} 与补全体里用到的 {@code Player::getName} 都要有值，
     * 否则走到补全体就是 NPE（而 NPE 会伪装成「守卫生效」）。
     */
    private void stubOnlinePlayers() {
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        Player alice = mock(Player.class);
        Player bob = mock(Player.class);
        when(alice.getName()).thenReturn("Alice");
        when(bob.getName()).thenReturn("Bob");
        // getOnlinePlayers() 返回 Collection<? extends Player>。Mockito 的 thenReturn
        // 对这一形参做 wildcard 捕获推断会失败（"capture of ? extends Player"），
        // 无类型检查的 doReturn(...).when(...) 是这类 API 的标准写法。
        org.mockito.Mockito.doReturn(List.of(alice, bob)).when(server).getOnlinePlayers();
        when(plugin.getServer()).thenReturn(server);
    }
}
