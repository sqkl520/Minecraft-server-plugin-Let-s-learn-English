package com.letslearnenglish.minecraftplugin.build;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code plugin.yml} 的 {@code permissions:} 段与代码里的 {@code hasPermission(...)}
 * 调用点之间的<b>双向契约 lint</b>。
 *
 * <h2>为什么必须是 lint，不能是行为测试</h2>
 * 这条契约的两侧都是「静态文本 vs 静态文本」，在运行时没有任何观测点：
 * <ul>
 *   <li>声明了一个没人查的节点 → 插件照常启动、功能全对，服主却在 LuckPerms 里
 *       配了个永远不生效的开关（{@code default: true} 时甚至看不出异常）；</li>
 *   <li>查了一个没声明的节点 → {@code hasPermission} 回落到
 *       {@code Permission.DEFAULT_PERMISSION}（= OP），普通玩家恒 false，
 *       服主<b>连配都配不出来</b>（Bukkit 只知道 plugin.yml 里注册过的节点）。</li>
 * </ul>
 * 两者都是「绿灯下的静默失效」，只有读源码 + 读 plugin.yml 才能钉住。这正是本仓
 * {@code IndexAndPragmaLintsTest} / {@code GlobalGuiActionGuardListenerTest} 确立的惯例：
 * 结构性契约用 lint，行为用 mock 测试。
 *
 * <h2>本类钉死的四条</h2>
 * <ol>
 *   <li><b>L1 正向</b>：plugin.yml 声明的每个节点，代码里至少有一处
 *       {@code hasPermission("<该节点>")}；</li>
 *   <li><b>L2 反向</b>：代码里出现的每个 {@code hasPermission("<字面量>")}，
 *       plugin.yml 都声明了（否则服主配不出来）；</li>
 *   <li><b>L3 通配守卫</b>：任何以 {@code .*} 结尾的节点，必须带 {@code children:} 段。
 *       Bukkit 的通配展开只发生在 {@code PermissionAttachment.calculateChildPermissions}，
 *       输入是 {@code Permission.getChildren()}；没有 children 的 {@code .*} 是空转的。</li>
 *   <li><b>L4 命令级门守卫</b>：{@code commands:} 段里不得给 {@code learnadmin}
 *       设命令级 {@code permission:} —— AdminCommand 是逐子命令判定
 *       （{@code hasPermissionFor}），命令级门会让仅持 {@code letslearnenglish.report}
 *       的老师在执行器之前就被 Bukkit 拦下。</li>
 * </ol>
 *
 * <h2>关于 D-14 的收口</h2>
 * 本轮删除了 {@code letslearnenglish.use} / {@code .word.*} / {@code .dialogue.*} /
 * {@code .progress.*} 四个「声明了但零判定」的节点（{@code ralph/accepted-deviations.md}
 * 的 D-14，自 R1 起挂账）。L1 就是它们的墓碑：谁把这类节点加回去而忘了加 {@code hasPermission}，
 * 本类立刻变红。
 *
 * <h2>L3 的自我验证（防空转）</h2>
 * 现状下 plugin.yml 已经没有 {@code .*} 节点了，若 L3 只对真实文件跑，它会是一条
 * 「永远绿、什么都没测」的空断言。因此 L3 先用<b>合成 ConfigurationSection</b> 验证
 * 判定函数本身两种方向都对（带 children 放行 / 不带则判红），再把同一函数应用到真实文件。
 * 这样「守卫被改成恒 return 空」和「真实文件被加回空转通配节点」都会变红。
 *
 * <p><b>与其它 lint 类零耦合</b>：注释剥离、文件遍历等工具在此独立实现一遍，
 * 理由同 {@code IndexAndPragmaLintsTest} 的类注释 —— 共用工具会让若干组 lint
 * 同时因一处语法边缘情况（例如新增一种注释写法）静默失效。
 */
@DisplayName("PermissionContractLint — plugin.yml 权限节点与 hasPermission 双向契约")
class PermissionContractLintTest {

    /** plugin.yml（参与 Maven 资源过滤，故此处读的是源码副本）。 */
    private static final Path PLUGIN_YML = Paths.get("src/main/resources/plugin.yml");

    /** 主源码根，lint 只在 {@code src/main/java} 上生效（测试自己 mock 的 hasPermission 不算）。 */
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** 匹配 {@code hasPermission("字面量")}。 */
    private static final Pattern HAS_PERMISSION_LITERAL =
            Pattern.compile("hasPermission\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

    /**
     * 参数非字面量：{@code hasPermission(} 后面第一个非空白字符不是双引号。
     * <b>注意</b>：{@code hasPermissionFor(} 不会命中（{@code hasPermission} 与 {@code (}
     * 之间隔着 {@code For}）。
     */
    private static final Pattern HAS_PERMISSION_NON_LITERAL =
            Pattern.compile("hasPermission\\s*\\(\\s*[^\"\\s]");

    /** 节点前缀（本项目全部节点都在此命名空间下，用于从字面量里筛出插件自己的节点）。 */
    private static final String NODE_PREFIX = "letslearnenglish.";

    private static ConfigurationSection declaredPermissions;
    private static Set<String> declaredNodeNames;
    private static Set<String> checkedNodeNames;

    @BeforeAll
    static void parseBothSidesOnce() throws IOException, InvalidConfigurationException {
        declaredPermissions = loadPluginYml().getConfigurationSection("permissions");
        if (declaredPermissions == null) {
            fail("plugin.yml 里找不到 permissions: 段");
        }
        declaredNodeNames = new TreeSet<>(leafNodeNames(declaredPermissions));
        checkedNodeNames = new TreeSet<>(collectCheckedNodes());
    }

    /**
     * 取出 {@code permissions:} 段下所有<b>叶子节点</b>的完整名字。
     *
     * <p><b>为什么不能直接 {@code getKeys(false)}</b>：Bukkit 的 {@code MemorySection}
     * 把 YAML 里的点号当<b>路径分隔符</b>。{@code letslearnenglish.admin:} 解析后
     * <b>不是</b>一个名为 {@code "letslearnenglish.admin"} 的键，而是一个名为
     * {@code "letslearnenglish"} 的 section，其下才是 {@code "admin"}（可无限嵌套）。
     * 因此 {@code getKeys(false)} 只返回 {@code ["letslearnenglish"]} —— 本类第一版就是
     * 这么写的，L1/L2 双双退化成「拿一个不存在的节点名去比对」，被
     * {@code bothSidesAreNonEmpty} 当场抓住（它断言集合内容恰好是那三个节点）。
     *
     * <p>判据：一个 section 若<b>自身带 {@code description} / {@code default} / {@code children}</b>，
     * 它就是一个权限节点（plugin.yml 的权限声明必带这几项之一）；否则继续向下钻。
     * 这样 {@code .*} 这类名字里本来就带点的节点也能被正确还原成完整名。
     *
     * @param root {@code permissions:} 段
     * @return 叶子节点完整名集合
     */
    private static Set<String> leafNodeNames(ConfigurationSection root) {
        Set<String> nodes = new TreeSet<>();
        collectLeaves(root, "", nodes);
        return nodes;
    }

    /**
     * {@link #leafNodeNames(ConfigurationSection)} 的递归体。
     *
     * @param section 当前段
     * @param prefix  已走过的键路径（空串或以点结尾之前的形式）
     * @param out     收集容器
     */
    private static void collectLeaves(ConfigurationSection section, String prefix, Set<String> out) {
        for (String key : section.getKeys(false)) {
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) {
                // 标量值：`letslearnenglish.foo: true` 这种简写形式也算一个节点声明
                out.add(full);
                continue;
            }
            if (child.isSet("description") || child.isSet("default") || child.isSet("children")) {
                // 已是节点声明层，不再下钻（其 children 不是 permissions: 的直接子节点）
                out.add(full);
                continue;
            }
            collectLeaves(child, full, out);
        }
    }

    // ------------------------------------------------------------------
    // L1 / L2 —— 双向契约
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L1 —— 声明的节点必须真的被判定")
    class L1 {

        @Test
        @DisplayName("plugin.yml 每个权限节点都有 hasPermission 调用点（否则是纯装饰）")
        void everyDeclaredNodeIsCheckedInCode() {
            Set<String> orphans = new TreeSet<>(declaredNodeNames);
            orphans.removeAll(checkedNodeNames);

            assertTrue(orphans.isEmpty(),
                    "plugin.yml 声明了这些权限节点，但 src/main/java 里没有任何 "
                            + "hasPermission(\"<节点>\") 调用：" + orphans
                            + "。声明了却从不判定 = 服主配了也不生效的假开关（D-14 的原形态）。"
                            + "要么在对应入口接上真实判定，要么从 plugin.yml 删掉声明。");
        }

        @Test
        @DisplayName("本 lint 确实读到了东西（防空转：解析出 0 个节点会让上面两条恒绿）")
        void bothSidesAreNonEmpty() {
            // 反向自检。没有这条，一个把 getKeys 写成 getKeys(true) 或把根路径
            // 写错的改动会让 L1/L2 变成「空集减空集 = 空」，永远绿。
            assertTrue(declaredNodeNames.size() >= 3,
                    "plugin.yml 的 permissions: 段解析出 " + declaredNodeNames.size()
                            + " 个节点（预期至少 admin / scenario.import / report 三个）："
                            + declaredNodeNames);
            assertTrue(checkedNodeNames.size() >= 3,
                    "src/main/java 里只扫到 " + checkedNodeNames.size() + " 个 hasPermission 字面量："
                            + checkedNodeNames + "。扫描器可能没读懂源码（注释剥离/文件遍历出错）。");
            // 逐个点名：这三个是当前唯一的节点，删/改名都会让本断言失败，
            // 从而强制同步这里的清单（而不是让契约在无人察觉时收缩）。
            assertEquals(Set.of("letslearnenglish.admin",
                            "letslearnenglish.report",
                            "letslearnenglish.scenario.import",
                            // R12-H1：GUI「刷新 API 密钥」从 admin 改成独立节点 —— 该按钮只
                            // 重载 AI 配置（ai_config.yml + ai_scenarios.yml），与 /lea reload
                            // 的全模块重载能力不同，服主应能单独授予而不必给整个 admin。
                            "letslearnenglish.ai.reload"),
                    declaredNodeNames,
                    "权限节点集合与本 lint 登记的清单不一致 —— 增删节点时必须同步本断言，"
                            + "以确认「新节点已接线 / 老节点已无人引用」。");
        }
    }

    @Nested
    @DisplayName("L2 —— 判定的节点必须已被声明")
    class L2 {

        @Test
        @DisplayName("每个 hasPermission 字面量都在 plugin.yml 声明过（否则服主配不出来）")
        void everyCheckedNodeIsDeclared() {
            Set<String> undeclared = new TreeSet<>(checkedNodeNames);
            undeclared.removeAll(declaredNodeNames);

            assertTrue(undeclared.isEmpty(),
                    "代码里检查了这些权限节点，但 plugin.yml 没声明：" + undeclared
                            + "。未注册的节点在 Bukkit 里只会回落到 Permission.DEFAULT_PERMISSION"
                            + "（= OP），普通玩家恒 false 且服主无从配置 —— 必须在 plugin.yml 补声明。");
        }

        @Test
        @DisplayName("不存在变量形态的 hasPermission 调用（否则本 lint 会静默漏检）")
        void noNonLiteralHasPermissionCalls() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path file : javaSources()) {
                int lineNo = 0;
                for (String line : readStripped(file).split("\n", -1)) {
                    lineNo++;
                    if (!HAS_PERMISSION_NON_LITERAL.matcher(line).find()) continue;
                    offenders.add(file + ":" + lineNo + " :: " + line.trim());
                }
            }

            assertTrue(offenders.isEmpty(),
                    "发现 hasPermission(...) 的<b>非字面量</b>参数，PermissionContractLintTest "
                            + "无法静态求值，会静默漏检。请把节点名写成字面量，或在 L1/L2 里显式登记该形态："
                            + offenders);
        }
    }

    // ------------------------------------------------------------------
    // L3 / L4 —— 结构性守卫
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L3 —— 通配节点必须有 children")
    class L3 {

        /** 合成用例：不带 children 的 .* 节点，必须被判定为空转。 */
        @Test
        @DisplayName("判定函数自检：无 children 的 .* 被识别为空转")
        void detectorFlagsHollowWildcard() throws InvalidConfigurationException {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("permissions.letslearnenglish.word.*.description", "hollow");
            yml.set("permissions.letslearnenglish.word.*.default", true);

            assertEquals(List.of("letslearnenglish.word.*"),
                    hollowWildcards(yml.getConfigurationSection("permissions")),
                    "判定函数没能识别空转通配节点 —— 本 lint 已被改成恒空，等于关掉了守卫");
        }

        /** 合成用例：带 children 的 .* 节点必须放行（否则守卫过紧，把正确写法也判红）。 */
        @Test
        @DisplayName("判定函数自检：带 children 的 .* 放行")
        void detectorAllowsWildcardWithChildren() {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("permissions.letslearnenglish.word.*.description", "real parent");
            yml.set("permissions.letslearnenglish.word.*.children.letslearnenglish.word.review", true);

            assertTrue(hollowWildcards(yml.getConfigurationSection("permissions")).isEmpty(),
                    "带 children 的通配父节点是合法写法（Bukkit 会真的展开），不应被判红");
        }

        /** 真实文件：当前不存空转通配节点。 */
        @Test
        @DisplayName("plugin.yml 里没有不带 children 的 .* 节点")
        void realFileHasNoHollowWildcard() {
            List<String> hollow = hollowWildcards(declaredPermissions);

            assertTrue(hollow.isEmpty(),
                    "这些权限节点以 .* 结尾但没有 children: 段：" + hollow
                            + "。Bukkit 不做前缀通配 —— PermissibleBase.hasPermission(String) 是纯"
                            + "精确名字查表，通配展开只发生在有 children 的父节点计算附件时。"
                            + "没有 children 的 .* 节点授予零权限、拒绝零行为，只会让服主以为已经关掉了功能。");
        }
    }

    @Nested
    @DisplayName("L4 —— /lea 不得设命令级权限门")
    class L4 {

        @Test
        @DisplayName("/learnadmin 的 commands: 段没有 permission: 字段")
        void learnAdminHasNoCommandLevelPermission() throws IOException, InvalidConfigurationException {
            ConfigurationSection learnAdmin =
                    loadPluginYml().getConfigurationSection("commands.learnadmin");
            if (learnAdmin == null) {
                fail("plugin.yml 的 commands: 段里找不到 learnadmin");
                return;
            }

            assertTrue(!learnAdmin.isSet("permission"),
                    "/learnadmin 不得设命令级 permission: —— AdminCommand 是逐子命令判定的"
                            + "（hasPermissionFor：report 接受 letslearnenglish.report 或 admin，"
                            + "其余一律 admin）。命令级门会在执行器之前就被 Bukkit 拦下，"
                            + "让仅持 letslearnenglish.report 的老师/家长代表彻底用不了 /lea report。");
        }
    }

    // ------------------------------------------------------------------
    // 工具（类内独立实现，刻意不与其它 lint 共用）
    // ------------------------------------------------------------------

    /**
     * 找出 {@code permissions:} 段里「名字以 {@code .*} 结尾、但没有 {@code children:} 子段」
     * 的节点。这类节点在 Bukkit 里是空转的（见类注释 L3）。
     *
     * <p>节点枚举同样走 {@link #leafNodeNames(ConfigurationSection)}（点号是路径分隔符，
     * {@code getKeys(false)} 只给第一层）。<b>children 的存在性用 {@code isSet} 判定</b>，
     * 不用 {@code isConfigurationSection} —— 后者要求它解析成 section，而
     * {@code children: {a: true}} 在没有子键时也可能是空段。
     *
     * @param permissions {@code permissions:} 段；null 视为空
     * @return 空转通配节点名列表（按字典序）
     */
    private static List<String> hollowWildcards(ConfigurationSection permissions) {
        if (permissions == null) {
            return List.of();
        }
        List<String> hollow = new ArrayList<>();
        for (String node : leafNodeNames(permissions)) {
            if (!node.endsWith(".*")) continue;
            if (permissions.isSet(node + ".children")) continue;
            hollow.add(node);
        }
        return hollow;
    }

    /**
     * 读 plugin.yml 并解析。它参与 Maven 资源过滤，源码里带 {@code ${...}} 占位符；
     * {@link YamlConfiguration} 把未加引号的 {@code ${project.version}} 当普通标量，
     * 因此这里能直接解析（与 Bukkit 运行时解析的是同一套实现）。
     *
     * @return 解析后的 plugin.yml
     * @throws IOException 读文件失败
     */
    private static YamlConfiguration loadPluginYml() throws IOException, InvalidConfigurationException {
        if (!Files.isRegularFile(PLUGIN_YML)) {
            fail("找不到 " + PLUGIN_YML.toAbsolutePath() + "（测试必须以仓库根为工作目录）");
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString(Files.readString(PLUGIN_YML, StandardCharsets.UTF_8));
        return yml;
    }

    /**
     * 收集 src/main/java 下所有 {@code hasPermission("...")} 的字面量节点名。
     * 只保留 {@link #NODE_PREFIX} 命名空间下的 —— 第三方节点（Vault / PlaceholderAPI 等）
     * 不在本插件的 plugin.yml 管辖范围内。
     *
     * @return 去重后的节点名集合
     * @throws IOException 遍历失败
     */
    private static Set<String> collectCheckedNodes() throws IOException {
        Set<String> nodes = new TreeSet<>();
        for (Path file : javaSources()) {
            Matcher m = HAS_PERMISSION_LITERAL.matcher(readStripped(file));
            while (m.find()) {
                String node = m.group(1);
                if (node.startsWith(NODE_PREFIX)) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    /**
     * 枚举 src/main/java 下全部 {@code .java} 文件。
     *
     * @return 源文件路径列表
     * @throws IOException 遍历失败
     */
    private static List<Path> javaSources() throws IOException {
        if (!Files.isDirectory(MAIN_JAVA)) {
            fail("找不到 " + MAIN_JAVA.toAbsolutePath() + "（测试必须以仓库根为工作目录）");
        }
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 读源码并剥掉注释。
     *
     * <p><b>必须剥注释</b>：本仓多处注释里写着 {@code hasPermission("letslearnenglish.admin")}
     * 作为说明文字（例如 AdminCommand 的权限最小化设计注释、ScenarioSelectListener 的
     * R-55m 沿革注释）。不剥的话这些注释会被当成真实调用点，L1 会把一个纯装饰节点
     * 判成「已接线」—— 那正是本 lint 最该防住的假绿。
     *
     * <p>字符串字面量整体照搬，避免把 {@code "http://..."} 里的 {@code //} 误当行注释。
     * 本仓无字符字面量 {@code '"'}、无文本块（{@code """}），已 grep 核实；若将来引入，
     * 需要同步扩展本方法。
     *
     * @param file 源文件
     * @return 剥掉 {@code //} 与块注释后的文本
     * @throws IOException 读文件失败
     */
    private static String readStripped(Path file) throws IOException {
        String src = Files.readString(file, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
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
