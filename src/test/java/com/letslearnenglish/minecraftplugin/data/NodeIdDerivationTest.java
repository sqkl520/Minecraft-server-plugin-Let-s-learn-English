package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;

/**
 * S2（Round 16）：{@link DatabaseManager#getNodeId()} 的<b>跨重启稳定性</b>与回落语义。
 *
 * <h2>为什么这个性质值一整条测试流</h2>
 * {@code seasons.claim_owner} 存的不是「进程身份」而是「节点身份」——
 * 整个 {@code claim_owner} 抢占闸门的正确性都压在一条不变式上：
 * <b>同一台服、同一份数据目录、下一个进程 → 必须算出同一个 nodeId</b>。
 * 若它跨重启漂移，服重启后自己上一次留下的脏 claim 在库里看起来就是「他服」的，
 * {@code SeasonManager.rebuildPendingReleases} 只能走
 * {@code FOREIGN_CLAIM_STALE_MS}（15 分钟）的过期路径，而它自己的恢复 sweep 发生在
 * 启动后<b>几秒</b>内 —— 正好永远赶不上，脏 claim 要拖到下个季边界（默认 30 天）。
 * 那就是 R-16 修掉的那个缺陷的形态。
 *
 * <h2>为什么是「真 SQLite + 两个 DatabaseManager」</h2>
 * nodeId 在构造函数里算完（{@code DatabaseManager:121}），本身不碰库；但<b>重启</b>这件事
 * 在生产上同时意味着「新进程 + 同一个 .db 文件」。所以本类每次都
 * {@code new DatabaseManager(...)} 后真的 {@code initialize()} 到同一个
 * {@code @TempDir} 下的同一个 db 文件上 —— 两个实例共享一份库，谁也不是 mock。
 * 只断言「两个 getter 相等」而不落地库，等于什么都没证明。
 *
 * <h2>独立对照（oracle）</h2>
 * {@link #expectedNodeId} 是本类自己重写一遍的派生公式（{@code String.format("%02x")}
 * 而不是生产代码手搓的 {@code Character.forDigit}；前像按 {@code len + ":" + field} 拼，
 * 与生产编码器的单射形式一致）。它同时钉住三件事：
 * ① 前像确实是「UTF-8 字节长度前缀 + 原文」两段相接（<b>不是</b> R-17 之前的
 * {@code name + "|" + path}）；② 服名被 {@code trim()}；③ 前导 0 不被吃掉（生产代码为此
 * 手写了 hex 循环，见 {@code :182-188}）。没有它，「两次都返回了同一个 UUID 回落值」这种假绿
 * 是看不出来的，而「前像被改回无长度前缀版本」也是看不出来的。
 *
 * <h2>R-17：拼接歧义已修（原 {@code @Disabled} 用例已转为常规回归用例）</h2>
 * <p>{@link #separatorPositionAmbiguityCollidesNodeIds} 记录的缺陷 —— 前像
 * {@code serverName + "|" + dataFolderPath} <b>没有长度前缀</b>，两组不同的
 * {@code (名字, 目录)} 可以拼出逐字节相同的前像从而算出同一个 nodeId —— 已改为
 * 「每段前加其 UTF-8 字节长度 + {@code ':'}」的单射编码（论证见
 * {@code DatabaseManager#computeNodeId} 的 javadoc ①）。用例保留原有 {@code assertNotEquals}
 * 断言并摘掉 {@code @Disabled}，另加三组恶意/边界输入（空字段两两区分、字段含 {@code |}、
 * 字段字面以 {@code "3:"} 开头）作为回归防线。
 */
@DisplayName("S2 nodeId 派生：跨重启稳定 / 安装隔离 / 回落 / 前像单射")
class NodeIdDerivationTest {

    /** 两个实例必须指向同一个库文件，才叫「一份安装」。 */
    private static final String DB_FILE = "node-id-test.db";
    private static final String PREFIX = "lle_";

    /** 正常派生值：16 位小写十六进制。 */
    private static final Pattern HEX16 = Pattern.compile("^[0-9a-f]{16}$");
    /** 回落值：{@code UUID.randomUUID().toString()} 的形状。 */
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /**
     * plugin mock 在整类内复用：{@code LetsLearnEnglish} 继承链很深，单次
     * {@code mock()} 约十秒级（同 {@code SqliteMaintenanceTest} 的记载）。每个用例
     * 自己重打 {@code getDataFolder()} 的桩即可，后打的覆盖前一个。
     */
    private static LetsLearnEnglish plugin;

    /** 本用例开过的库，{@code @AfterEach} 统一关，避免 SQLite 句柄/锁泄漏到下一个用例。 */
    private final List<DatabaseManager> opened = new ArrayList<>();

    @BeforeAll
    static void createPluginMock() {
        plugin = mock(LetsLearnEnglish.class);
        Logger logger = Logger.getLogger("NodeIdDerivationTest");
        logger.setUseParentHandlers(false);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @AfterEach
    void closeDatabases() {
        for (DatabaseManager db : opened) {
            try {
                db.shutdown();
            } catch (RuntimeException ignored) {
                // 关库失败不该掩盖断言结果
            }
        }
        opened.clear();
    }

    // ==================================================================
    // 1. 核心不变式：同一份安装重启 → 同一个 nodeId
    // ==================================================================

    @Test
    @DisplayName("同配置同数据目录，两次启动 → nodeId 完全相同（重启不变式）")
    void restartOnTheSameInstallDerivesTheSameNodeId(@TempDir Path dir) {
        File dataFolder = dir.toFile();

        // 两次独立的 new + initialize = 生产上的「重启」（新对象、新 instanceId、同一个 .db）
        DatabaseManager first = boot(dataFolder, "lobby");
        DatabaseManager second = boot(dataFolder, "lobby");

        String idAtFirstBoot = first.getNodeId();
        String idAfterRestart = second.getNodeId();

        // 正对照：先证明这个值确实是按文档公式算出来的，而不是碰巧相等的两个回落 UUID
        assertEquals(expectedNodeId("lobby", dataFolder), idAtFirstBoot,
                "nodeId 必须 = 前 16 位十六进制(sha256(len+\":\"+名字 + len+\":\"+dataFolder 绝对路径))"
                        + "（R-17 的长度前缀编码，不是 \"名字|路径\"）");
        assertEquals(expectedNodeId("lobby", dataFolder), idAfterRestart,
                "重启后的值也必须落在同一条公式上（否则下面那条相等断言可能是两个回落值撞巧）");
        assertEquals(idAtFirstBoot, idAfterRestart,
                "重启（同机、同数据目录、同配置）必须派生出同一个 nodeId —— "
                        + "否则服重启后自己上一次留下的脏 claim 会被当成他服的，恢复要等 15 分钟过期路径");
        assertTrue(HEX16.matcher(idAfterRestart).matches(),
                "正常派生值必须是 16 位小写十六进制（实际 " + idAfterRestart + "）");
    }

    // ==================================================================
    // 2/3. 两种「必须被区分开」的身份
    // ==================================================================

    @Test
    @DisplayName("同一份安装、不同 proxy.server-name → nodeId 必须不同")
    void differentProxyServerNameOnTheSameInstallDerivesDifferentNodeIds(@TempDir Path dir) {
        File dataFolder = dir.toFile();

        String lobbyId = boot(dataFolder, "lobby").getNodeId();
        String survivalId = boot(dataFolder, "survival").getNodeId();

        // 正对照：两个值都要落在各自的公式上，才能排除「两个都是回落 UUID，所以肯定不等」
        assertEquals(expectedNodeId("lobby", dataFolder), lobbyId, "lobby 的 id 必须是 lobby 的公式值");
        assertEquals(expectedNodeId("survival", dataFolder), survivalId, "survival 的 id 必须是 survival 的公式值");
        assertNotEquals(lobbyId, survivalId,
                "同机两个子服（名字不同、数据目录相同）必须能互相区分，否则一台服会把另一台的活 claim 认成自己的");
    }

    @Test
    @DisplayName("同 server-name、不同数据目录（同机两份安装）→ nodeId 必须不同")
    void differentDataFolderWithTheSameServerNameDerivesDifferentNodeIds(@TempDir Path dir) {
        File installA = dir.resolve("install-a").toFile();
        File installB = dir.resolve("install-b").toFile();
        assertTrue(installA.isDirectory() || installA.mkdirs(), "前置条件：装 A 的目录要能建出来");
        assertTrue(installB.isDirectory() || installB.mkdirs(), "前置条件：装 B 的目录要能建出来");

        String idA = boot(installA, "lobby").getNodeId();
        String idB = boot(installB, "lobby").getNodeId();

        assertEquals(expectedNodeId("lobby", installA), idA, "装 A 的 id 必须落在装 A 的公式上");
        assertEquals(expectedNodeId("lobby", installB), idB, "装 B 的 id 必须落在装 B 的公式上");
        assertNotEquals(idA, idB,
                "同机两份安装（服名相同、数据目录不同）必须能互相区分，否则两台服的脏 claim 会互相误判");
    }

    // ==================================================================
    // 4. 默认配置（proxy.server-name 留空）—— 最常见的那种服
    // ==================================================================

    @Test
    @DisplayName("默认配置（server-name 为空）+ 非空数据目录 → 仍跨重启稳定，且不退化成路径无关的 UUID")
    void defaultEmptyServerNameIsStillStableAcrossRestarts(@TempDir Path dir) {
        File dataFolder = dir.toFile();

        // 默认配置是 `server-name: ""`；老配置文件里这个键可能压根不存在，
        // 升级后不能因此换掉 nodeId（那等于把所有存量脏 claim 判给他服）。
        String idWithEmptyKey = boot(dataFolder, "").getNodeId();
        String idWithKeyAbsent = boot(dataFolder, null).getNodeId();

        // 留空时的前像退化成 "0:" + 长度+":"+绝对路径 —— 这正是生产 javadoc 描述的降级方向
        assertEquals(expectedNodeId("", dataFolder), idWithEmptyKey,
                "server-name 留空时必须仍按数据目录派生（前像 = \"0:\" + len + \":\" + 绝对路径），"
                        + "而不是回落到 per-process UUID");
        assertEquals(idWithEmptyKey, idWithKeyAbsent,
                "键缺失与键为空必须算出同一个 nodeId，否则老配置文件升级后身份会漂移");
        assertEquals(idWithEmptyKey, boot(dataFolder, "").getNodeId(),
                "默认配置下重启也必须算出同一个 nodeId —— 这是绝大多数单服/未上代理的服的实际情况");
        assertTrue(HEX16.matcher(idWithEmptyKey).matches(),
                "留空也仍走哈希派生，格式必须是 16 位小写十六进制（实际 " + idWithEmptyKey + "）");
    }

    // ==================================================================
    // 5. 两个输入都取不到 → per-process 回落（钉住：它不跨重启稳定）
    // ==================================================================

    @Test
    @DisplayName("两个输入都为空 → 回落成 per-process UUID，且该回落值不跨重启稳定（已知限制）")
    void bothInputsEmptyFallsBackToAPerProcessIdThatIsNotStable(@TempDir Path dir) {
        // 不 initialize：dataFolder 为 null 时库文件无处落地。nodeId 在构造期就算完了，
        // 本用例判的正是构造期行为，不需要库。
        when(plugin.getDataFolder()).thenReturn(null);
        DatabaseManager first = new DatabaseManager(plugin, configWith(""));
        DatabaseManager second = new DatabaseManager(plugin, configWith(""));

        String firstId = first.getNodeId();
        String secondId = second.getNodeId();

        assertEquals(36, firstId.length(),
                "回落值就是 UUID 字符串，长度必须是 36（实际 \"" + firstId + "\"）");
        assertTrue(UUID_SHAPE.matcher(firstId).matches(),
                "回落值必须是 UUID 形状（实际 \"" + firstId + "\"）");
        assertFalse(HEX16.matcher(firstId).matches(),
                "回落值不能长得像正常派生值 —— 否则本类其它用例的「16 位十六进制」断言会把回落路径当成正常路径放过");

        assertNotEquals(firstId, secondId,
                "已知限制（不是缺陷）：proxy.server-name 与 dataFolder 都取不到时，nodeId 回落成 per-process UUID，"
                        + "因此它【不跨重启稳定】—— 重启后的自己会被当成他服，脏 claim 只能等 FOREIGN_CLAIM_STALE_MS"
                        + "(15 分钟) 的过期路径恢复。这里刻意把该行为钉住：将来若要「修」成稳定值，必须是一次有意识的改动"
                        + "（并且方向不能是「回落成所有服共享的常量」，那会让每台服都以为别人的活 claim 是自己的）");
    }

    // ==================================================================
    // 6. 格式：16 位小写十六进制，前像里的分隔符不许漏进来
    // ==================================================================

    @Test
    @DisplayName("派生值恒为 16 位小写十六进制，且不含 ':' / '|' 等前像字符")
    void derivedIdIsAlwaysSixteenLowercaseHexChars(@TempDir Path dir) {
        File dataFolder = dir.toFile();
        String[] serverNames = {"lobby", "survival-2", ""};

        for (String serverName : serverNames) {
            String id = boot(dataFolder, serverName).getNodeId();

            assertEquals(16, id.length(),
                    "server-name=\"" + serverName + "\" 的 nodeId 长度必须是 16（实际 " + id.length()
                            + "，值 \"" + id + "\"）—— 长度漂移说明手搓 hex 循环被换回了会吃掉前导 0 的实现");
            assertTrue(HEX16.matcher(id).matches(),
                    "server-name=\"" + serverName + "\" 的 nodeId 必须是 16 位小写十六进制（实际 \"" + id + "\"）");
            assertFalse(id.contains(":"),
                    "前像里的路径分隔符 ':' 不许漏进派生值（实际 \"" + id + "\"）");
            assertFalse(id.contains("|"),
                    "前像里的拼接分隔符 '|' 不许漏进派生值（实际 \"" + id + "\"）");
        }
    }

    // ==================================================================
    // 7. 冲突健全性（R-17）：前像必须单射 —— 两组不同部署身份不许算出一个 id
    // ==================================================================

    /**
     * R-17 回归用例（原为 {@code @Disabled} 的缺陷记录，修复后摘掉注解并保持断言不变）：
     * {@code nodeId} 的前像曾是 {@code serverName + "|" + dataFolderPath}，<b>没有长度前缀</b>，
     * 所以「分隔符落在哪」由内容自己决定 —— 两组不同的 {@code (名字, 目录)} 可以拼出逐字节
     * 相同的前像，从而算出同一个 16 位 id。现在是「UTF-8 字节长度 + {@code ':'}」的单射编码。
     *
     * <p><b>构造</b>：用一个真实绝对路径 {@code P} 演出 ({@code "a|b"}, {@code "c"}) vs
     * ({@code "a"}, {@code "b|c"}) 的等价形式 ——</p>
     * <pre>
     *   甲：名字 "n|" + P  ，目录 P
     *   乙：名字 "n"       ，目录 P + "|" + P
     *   旧前像两边都是  "n|" + P + "|" + P   （= 碰撞）
     * </pre>
     *
     * <p><b>乙的目录不需要真的存在</b>：{@code computeNodeId} 只读
     * {@code File.getAbsolutePath()}，从不碰磁盘。而 {@code getAbsolutePath()} 对<b>已成绝对</b>
     * 的路径原样返回（实测 {@code new File("D:\\x|D:\\x").getAbsolutePath()} 仍是
     * {@code "D:\\x|D:\\x"}），所以前像就是我们要的那个精确形式。</p>
     *
     * <p>构造必须用 {@link File} 而不是 {@link Path#resolve}：{@code Path} 在 Windows 上会直接抛
     * {@code InvalidPathException: Illegal char <|>}（实测），而 {@link File} 接受它。
     * 也<b>不要</b>在这里 {@code mkdirs()}：{@code |} 在 Windows 上是<b>非法文件名字符</b>
     * （{@code mkdirs()} 必失败），而本用例只需要那个字符串。反过来，在 Linux 上
     * {@code |} 合法 —— 这正是本缺陷在真实部署里可达的原因。</p>
     *
     * <p><b>这里刻意不写具体的十六进制值</b>：{@code P} 是 {@code @TempDir} 的随机绝对路径，
     * 每次运行都不同，把某一轮的值钉进注释等于给后续维护者一个永远对不上的常量。
     * 唯一而不变的事实是「甲 != 乙」。</p>
     *
     * <p><b>生产侧复核</b>：断言用真的 {@link DatabaseManager#getNodeId()}（不是本类的
     * {@link #expectedNodeId} 对照实现），碰撞与否发生在生产代码路径上。</p>
     */
    @Test
    @DisplayName("R-17 回归：分隔符位置不同的两组输入必须算出不同 nodeId（前像长度前缀后单射）")
    void separatorPositionAmbiguityCollidesNodeIds(@TempDir Path dir) {
        File folderA = dir.toFile();
        String absolutePath = folderA.getAbsolutePath();
        File folderB = new File(absolutePath + "|" + absolutePath);
        // 这一条是整条用例成立的前提：若 getAbsolutePath() 改写了乙的路径（例如拼上 cwd），
        // 旧编码就不再碰撞，assertNotEquals 会因为「本来就不撞」而假绿。
        assertEquals(absolutePath + "|" + absolutePath, folderB.getAbsolutePath(),
                "前置条件：乙的路径必须原样保留（getAbsolutePath() 对绝对路径不改写）");
        assertEquals(folderA.getAbsolutePath(), absolutePath, "前置条件：甲的路径就是 @TempDir");
        String nameA = "n|" + absolutePath;
        String nameB = "n";

        // 先钉住「旧编码确实碰撞」这个机械前提 —— 若这一条不成立，下面的 assertNotEquals
        // 就证明不了「修复生效」，只能证明「这组输入本来就不撞」。
        assertEquals(nameB + "|" + folderB.getAbsolutePath(), nameA + "|" + folderA.getAbsolutePath(),
                "前置：旧编码（无长度前缀）下两组不同的部署身份必须拼出逐字节相同的前像，歧义才成立");

        when(plugin.getDataFolder()).thenReturn(folderA);
        String idA = new DatabaseManager(plugin, configWith(nameA)).getNodeId();
        when(plugin.getDataFolder()).thenReturn(folderB);
        String idB = new DatabaseManager(plugin, configWith(nameB)).getNodeId();

        assertEquals(expectedNodeId(nameA, folderA), idA, "甲的前像-哈希链必须与公式一致（用于定位，不是判据）");
        assertEquals(expectedNodeId(nameB, folderB), idB, "乙的前像-哈希链必须与公式一致（用于定位，不是判据）");

        assertNotEquals(idA, idB,
                "【R-17】nodeId 前像已改为「长度前缀」单射编码，所以「名字含 '|' 的甲」与"
                        + "「目录名含 '|' 的乙」必须算出不同的 id（甲=\"" + idA + "\"，乙=\"" + idB
                        + "\"）。旧编码 serverName + \"|\" + 绝对路径 下这两组输入的前像逐字节相同，"
                        + "于是两台不同部署共用一个 fencing token → 互相把对方的 live claim 认成自己的"
                        + "（R-16 的 HIGH 缺陷重开，且因为是永久相等、永不走 15 分钟过期路径）。"
                        + "预置数据里已实测同一组输入在旧编码下相等、新编码下不等");
    }

    @Test
    @DisplayName("R-17：空字段与有值字段两两可区分（(\"\",\"P\") / (\"\",\"\") / (\"P\",\"\") 互不相同）")
    void emptyFieldsAreDistinguishedFromEachOtherAndFromNonEmptyOnes(@TempDir Path dir) {
        File installA = dir.toFile();
        String absolutePath = installA.getAbsolutePath();

        // 编码层面三态必须互不相同。用**生产编码器**构造（不在这里重写一遍），否则这三条断言
        // 只是在比较测试自己的三个字符串字面量（恒真），生产编码器怎么改都看不出来。
        String encodingEmptyAndPath = encode("", absolutePath);
        String encodingBothEmpty = encode("", "");
        String encodingPathAndEmpty = encode("lobby", "");
        assertEquals("0:" + Integer.toString(absolutePath.getBytes(StandardCharsets.UTF_8).length)
                        + ":" + absolutePath, encodingEmptyAndPath,
                "(\"\",\"P\") 的编码形状必须是 \"0:\" + len + \":\" + P");
        assertEquals("0:0:", encodingBothEmpty, "(\"\",\"\") 的编码形状必须是 \"0:0:\"");
        assertNotEquals(encodingEmptyAndPath, encodingBothEmpty, "编码本身必须能区分 (\"\",\"P\") 与 (\"\",\"\")");
        assertNotEquals(encodingEmptyAndPath, encodingPathAndEmpty,
                "编码本身必须能区分 (\"\",\"P\") 与 (\"P\",\"\") —— 否则「服名空、目录有值」与"
                        + "「服名有值、目录空」两份部署会共用 fencing token");
        assertNotEquals(encodingBothEmpty, encodingPathAndEmpty, "编码本身必须能区分 (\"\",\"\") 与 (\"P\",\"\")");

        // 走生产路径。注意回落只在**两个字段都空**时发生（见 computeNodeId ④）：
        //   ("", "P")  → 正常派生（前像 "0:" + len + ":" + P）
        //   ("P", "")  → 正常派生（前像 len + ":" + P + "0:"）—— 仍有名字这个身份，不是回落
        //   ("", "")   → per-process UUID 回落（没有任何身份）
        String emptyNameId = boot(installA, "").getNodeId();
        when(plugin.getDataFolder()).thenReturn(null);
        String emptyPathId = new DatabaseManager(plugin, configWith("lobby")).getNodeId();
        String bothEmptyId = new DatabaseManager(plugin, configWith("")).getNodeId();

        assertTrue(HEX16.matcher(emptyNameId).matches(),
                "(\"\", \"P\") 仍是正常派生值（实际 \"" + emptyNameId + "\"）");
        assertTrue(HEX16.matcher(emptyPathId).matches(),
                "(\"P\", \"\") 仍是正常派生值 —— 有名字这一个身份就够了（实际 \"" + emptyPathId + "\"）");
        assertTrue(UUID_SHAPE.matcher(bothEmptyId).matches(),
                "(\"\", \"\") 必须回落成 per-process UUID（实际 \"" + bothEmptyId + "\"）—— "
                        + "两个字段都空时前像恒为 \"0:0:\"，若把它当身份就等于所有服共享同一个 nodeId");
        assertNotEquals(emptyNameId, emptyPathId,
                "(\"\", \"P\") 必须与 (\"P\", \"\") 区分：前者是「无服名 + 有目录」，后者是「有服名 + 无目录」，"
                        + "两份不同部署不许共用 fencing token");
    }

    /**
     * R-17：字段内容里出现分隔符 / 长度前缀字面量 / 多字节字符时，仍然不许碰撞。
     *
     * <p><b>这里的每一对都是「旧编码下真的会撞」的输入</b>（不是随便挑的两组）：取任意前缀
     * {@code A} 与绝对路径 {@code P}，</p>
     * <pre>
     *   甲：名字 A + "|" + P ，目录 P
     *   乙：名字 A          ，目录 P + "|" + P
     *   旧编码：A + "|" + P + "|" + P   （两边逐字节相同 → 同一个 nodeId）
     * </pre>
     * <p>两边的目录都<b>以盘符开头</b>，所以 {@code getAbsolutePath()} 原样返回，焊接关系成立。
     * 用不同的 {@code A} 覆盖三类注入性内容：空、字面量 {@code "3:"}（试图冒充长度前缀）、
     * 多字节字符（试图让 char 长度与 UTF-8 字节长度不一致）。</p>
     *
     * <p>另有一条<b>直接钉住编码器口径</b>的断言：长度必须是 UTF-8 <b>字节</b>数而不是
     * {@code String.length()} —— 否则自描述性质在字节层面失效，单射论证就断了。</p>
     */
    @Test
    @DisplayName("R-17：字段含 '|' / 字面量 \"3:\" / 多字节字符等注入性内容都不产生碰撞")
    void hostileFieldsContainingSeparatorOrLengthPrefixDoNotCollide(@TempDir Path dir) {
        String p = dir.toFile().getAbsolutePath();
        String[] prefixes = {
                "",            // 纯分隔符注入
                "3:",          // 字面量冒充长度前缀
                "猫ü", // 多字节："猫" 3 字节 + "ü" 2 字节，char 长度 2
                "0:0:",        // 冒充「两段都空」的编码形状
        };
        for (String a : prefixes) {
            String nameA = a + "|" + p;
            File folderA = new File(p);
            String nameB = a;
            File folderB = new File(p + "|" + p);

            // 前置：旧编码下这两组输入必须真的同形 —— 否则断言证明不了任何事。
            assertEquals(nameB + "|" + folderB.getAbsolutePath(),
                    nameA + "|" + folderA.getAbsolutePath(),
                    "前置（A=\"" + a + "\"）：旧编码下必须逐字节同形，本对输入才有判别力");
            assertEquals(p + "|" + p, folderB.getAbsolutePath(),
                    "前置（A=\"" + a + "\"）：乙的路径必须是绝对路径且原样保留");

            when(plugin.getDataFolder()).thenReturn(folderA);
            String idA = new DatabaseManager(plugin, configWith(nameA)).getNodeId();
            when(plugin.getDataFolder()).thenReturn(folderB);
            String idB = new DatabaseManager(plugin, configWith(nameB)).getNodeId();

            assertNotEquals(idA, idB, "A=\"" + a + "\" 时甲（名字含 '|'）与乙（目录含 '|'）"
                    + "必须算出不同 nodeId，实际两者都是 \"" + idA + "\" —— 相等说明前像又被改回了"
                    + "无长度前缀的拼接，R-16 的 fencing 保证随之失效");
        }

        // 编码器口径：长度按 UTF-8 字节，不按 char。
        StringBuilder multi = new StringBuilder();
        DatabaseManager.appendLengthPrefixed(multi, "猫");     // "猫"：1 char / 3 bytes
        assertEquals("3:猫", multi.toString(),
                "长度前缀必须是 UTF-8 字节数（\"猫\" → 3，不是 char 数 1）—— 哈希的输入是字节，"
                        + "声称的长度与实际字节数不符会让「自描述」失效");
        StringBuilder latin = new StringBuilder();
        DatabaseManager.appendLengthPrefixed(latin, "ü");     // "ü"：1 char / 2 bytes
        assertEquals("2:ü", latin.toString(), "\"ü\" 是 2 字节，长度前缀必须是 2");
        StringBuilder literal = new StringBuilder();
        DatabaseManager.appendLengthPrefixed(literal, "3:abc");
        assertEquals("5:3:abc", literal.toString(),
                "内容字面以 \"3:\" 开头时，前缀必须是它自己的真实长度 5；"
                        + "若解析端把内容开头的 '3:' 当成边界，两条不同的字段就会重解析成同一个");
    }

    // ==================================================================
    // 8. 服名首尾空白不该改变身份
    // ==================================================================

    @Test
    @DisplayName("server-name 首尾空白被 trim：\" lobby \" 与 \"lobby\" 必须算出同一个 nodeId")
    void surroundingWhitespaceInServerNameDoesNotChangeTheNodeId(@TempDir Path dir) {
        File dataFolder = dir.toFile();

        String trimmed = boot(dataFolder, "lobby").getNodeId();
        String padded = boot(dataFolder, "  lobby  ").getNodeId();

        assertEquals(trimmed, padded,
                "配置里多打两个空格不该换掉 nodeId —— 换了就等于把所有存量 claim_owner 判给他服，"
                        + "脏 claim 要等 15 分钟过期路径才能恢复");
        assertEquals(expectedNodeId("lobby", dataFolder), padded,
                "前像里必须是 trim 后的服名");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 起一次「进程」：打桩数据目录 → 构造 → 真 {@code initialize()} 到 {@code @TempDir} 下的
     * 同一个 db 文件上。nodeId 在构造函数里算完，所以桩必须<b>先</b>打好。
     *
     * @param serverName {@code proxy.server-name}；{@code null} 表示配置里根本没有这个键
     */
    private DatabaseManager boot(File dataFolder, String serverName) {
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        DatabaseManager db = new DatabaseManager(plugin, configWith(serverName));
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：本类必须走真 SQLite 分支");
        opened.add(db);
        return db;
    }

    /** 每次都给一个新的 {@link ConfigManager} mock —— 两台服本来就各有各的配置对象。 */
    private static ConfigManager configWith(String serverName) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", DB_FILE);
        config.set("database.mysql.table-prefix", PREFIX);
        if (serverName != null) {
            config.set("proxy.server-name", serverName);
        }
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getMainConfig()).thenReturn(config);
        return configManager;
    }

    /**
     * 用<b>生产编码器</b>拼出两段前像 —— 用来在不知道/不复制实现细节的前提下断言编码性质。
     */
    private static String encode(String serverName, String dataFolderPath) {
        StringBuilder out = new StringBuilder();
        DatabaseManager.appendLengthPrefixed(out, serverName);
        DatabaseManager.appendLengthPrefixed(out, dataFolderPath);
        return out.toString();
    }

    /**
     * 独立对照实现：{@code sha256(len(name.trim()) + ":" + name.trim() + len(path) + ":" + path)}
     * 前 8 字节 → 16 位小写十六进制（{@code len} 均为 <b>UTF-8 字节</b>数，与生产编码器同口径）。
     *
     * <p>故意用 {@code String.format("%02x")} 而不是复制生产代码里手搓的
     * {@code Character.forDigit} 循环 —— 这样「前导 0 被吃掉」这类 bug 才会被对照出来。
     * 前像也在这里<b>独立重写</b>（而不是调 {@code DatabaseManager.appendLengthPrefixed}）：
     * 若两边共用同一个编码器，「编码被改回无长度前缀版本」这件事就没有任何断言看得见，
     * 而它正是本类 R-17 用例要守的东西。</p>
     *
     * <p><b>前提（真实部署必然满足，{@code @TempDir} 也满足）</b>：{@code dataFolder} 是绝对路径，
     * 因此 {@code getAbsolutePath()} 只做「空串时补 cwd」这一件事。若传入匿名相对路径且
     * {@code getUserDir()} 抛 {@link java.io.IOException}，{@code getAbsolutePath()} 会把它原样
     * 返回 —— 此时生产侧与对照侧仍然读到<b>同一个字符串</b>，对照关系不受影响。</p>
     */
    private static String expectedNodeId(String serverName, File dataFolder) {
        String name = serverName.trim();
        String path = dataFolder.getAbsolutePath();
        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
        int pathLen = path.getBytes(StandardCharsets.UTF_8).length;
        String material = nameLen + ":" + name + pathLen + ":" + path;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("JVM 缺 SHA-256，本用例的独立对照无法建立", e);
        }
    }
}
