package com.letslearnenglish.minecraftplugin.config;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>语言文件迁移闸门的版本号契约</b>（R31 · 存量服必现的 P0）。
 *
 * <h2>缺的是什么（为什么既有 160 个测试全绿也没挡住）</h2>
 * <p>{@code ConfigManager.MESSAGE_CONFIG_VERSION} 是 {@code messages/*.yml} 的<b>迁移闸门</b>：
 * 磁盘上用户文件 {@code config-version < 该常量} 才会把 JAR 内新增的键合并进去
 * （{@code ConfigManager#loadMessageConfig} → {@code migrateMessageConfig}）。
 *
 * <p>R31 之前它停在 <b>26</b>，而工作树相较已发布的 1.0.7 新增了 <b>75 个叶子键</b>
 * （711 → 786），JAR 内两份 yml 头部的 {@code config-version} 也停在 26。于是：
 *
 * <pre>
 *   int version = existing.getInt("config-version", 0);   // 26
 *   if (version &lt; MESSAGE_CONFIG_VERSION) {                // 26 &lt; 26 == false
 *       ...migrateMessageConfig(...)                      // 永不进入
 *   }
 *   return cacheMessageConfig(path, file);                // 直接用旧文件
 * </pre>
 *
 * <p>后果：<b>任何跑过 1.0.7 的服</b>升级到新 JAR 后，语言文件仍是 711 个键，那 75 个新键
 * 全部缺失，玩家在本该显示新文案的地方看到 {@code &c[?key]} 字面量
 * （{@code MessageUtil} 的 fail-visible 占位符）。而<b>全新安装</b>从 JAR 拷一份新 yml，
 * 一切正常 —— 这正是它逃过全部单测的原因：每个测试都在全新临时目录里跑。
 *
 * <h2>本类钉死的三条不变量</h2>
 * <ol>
 *   <li><b>闸门版本必须严格大于任何曾发布过的版本</b>（{@link #gateVersionIsStrictlyAheadOfReleased}）。
 *       这是本轮缺陷的唯一直接判据：常量回退到 26，这条立刻红。</li>
 *   <li><b>闸门版本必须等于 JAR 内两份 yml 头部的 {@code config-version}</b>
 *       （{@link #bundledYmlDeclaresTheGateVersion}）。否则用户文件迁移后被写回的版本号与
 *       JAR 资源不一致，下次升级会重复合并。</li>
 *   <li><b>键集合变化 ⇒ 版本号必须涨</b>（{@link #leafCountMatchesDeclaredVersion} +
 *       {@link #keysetFingerprintMatchesBaseline}）。见下节。</li>
 * </ol>
 *
 * <h2>为什么选「版本 → 叶子键数量」映射 + 「键集合指纹」，而不是别的</h2>
 * <p>把这个缺陷的成因说清楚：<b>没有任何机制约束「改了 yml 就必须 bump 版本号」</b>。
 * 三种候选做法的取舍：
 *
 * <ul>
 *   <li><b>只断言「yml 头部 == 常量」</b>：挡不住本轮缺陷 —— 缺陷发生时两边都是 26，
 *       完全自洽。它只防「改了一侧忘了另一侧」。</li>
 *   <li><b>把叶子键数量硬编码进测试</b>：能挡住「加键不 bump」，但<b>只要重新 baseline
 *       就能骗过</b>（把 786 改成 800 而不动版本号，测试照样绿）。强度不够。</li>
 *   <li><b>【采用】版本号 → 历史数量映射表 {@code 26→711, 27→786}</b>：加键后数量不再匹配
 *       表里"当前版本"那一项 ⇒ 红。要让测试变绿，<b>唯一自然的做法是新增一行更高版本号</b>
 *       （{@code 28→800}）并 bump 常量与 yml 头部 —— 也就是把"必须 bump"变成结构性要求。
 *       想绕过只剩"篡改历史行"（把 27→786 改成 27→800），那是显式、可见的谎言，而不是
 *       顺手重新 baseline。<b>强度足够，且假阳性为零</b>：文案（值）改动、注释改动、
 *       行序调整都不会触发。</li>
 * </ul>
 *
 * <p>再叠一层 {@link #keysetFingerprintMatchesBaseline}（对<b>排序后的叶子键路径集合</b>
 * 取 SHA-256）：数量相同但"删一个加一个"的键集合变化能被抓住，而值改动仍然不触发
 * —— 这一点很关键，本仓文案调优频繁，指纹<b>刻意只覆盖键名、不覆盖值</b>。
 *
 * <h2>行为验证：真的把一份 711 键的 v26 文件升级到<b>当前</b>版本</h2>
 * <p>只改常量不算修好 —— 还要证明 {@code migrateMessageConfig} 在这个具体场景下是对的。
 * {@link #releasedShapedFileIsUpgradedToCurrent()} 用<b>推导</b>的方式构造出与已发布版本
 * 同形的 711 键文件（当前键数减去 {@link #KEYS_ADDED_IN} / {@link #SECTIONS_ADDED_IN}
 * 记录的全部新增键与新增 section），再走真实 {@code loadMessageConfig}，断言迁移后键集合与
 * JAR 内完全一致、用户改过的值被保留、落盘成功并留了 {@code .bak} 快照。
 *
 * <p>{@link #derivationReproducesTheReleasedShape()} 顺手钉住一条更强的结论：
 * {@code 当前键数 - 已登记新增键数 == 711} 且"从当前文件里只删掉那些新增键就精确得到
 * 711 键"，等价于证明 <b>1.0.7 → 当前之间没有删除任何键</b>（纯新增），
 * 因此合并是纯 add-only 操作。
 */
class I18nVersionGateTest {

    private static final String ZH = "messages/zh.yml";
    private static final String EN = "messages/en.yml";

    /**
     * 上一个<b>已发布</b>到用户手里的闸门版本（1.0.7 的 JAR 内 {@code messages/*.yml} 头部
     * 就是 26）。这是"存量服"的现实起点，也是本类全部断言的锚。
     *
     * <p>刻意<b>不从常量推导</b>：从常量推导等于让断言自我指涉（常量改了，锚跟着变，
     * 永远相等），本轮缺陷就永远抓不到。
     */
    private static final int LAST_RELEASED_VERSION = 26;

    /**
     * 版本号 → 该版本 {@code messages/*.yml} 的<b>叶子键总数</b>（含 {@code config-version}
     * 这个元数据叶子，与本类 {@link #leafKeys} 的口径一致：711 / 786 / 788 / 799 正是这几个数）。
     *
     * <p>新增一行 = 一次语言文件变更的永久档案。加键后若忘了 bump 常量，
     * {@code LEAF_COUNTS.get(常量)} 取到的仍是旧数量，与实际数量不等 ⇒ 红。
     */
    private static final Map<Integer, Integer> LEAF_COUNTS = new LinkedHashMap<>();

    /**
     * 版本号 → 该版本<b>新增</b>的顶层 section 名（整段新增，段内所有叶子键都算新键）。
     *
     * <p>单独记录是因为 {@code diagnose:} 整段在 1.0.7 里不存在（旧文件里
     * {@code diagnose} 字样出现 0 次），它贡献的 58 个叶子键逐个列出来只会让本表难以核对。
     */
    private static final Map<Integer, Set<String>> SECTIONS_ADDED_IN = new LinkedHashMap<>();

    /**
     * 版本号 → 该版本新增的<b>叶子键</b>（不含 {@link #SECTIONS_ADDED_IN} 覆盖的整段）。
     *
     * <p>使用者有两个：{@link #derivationReproducesTheReleasedShape()} 用它反推已发布版本的
     * 键集合（从而证明"纯新增"），{@link #releasedShapedFileIsUpgradedToCurrent()} 用它构造
     * 那份 711 键的迁移输入。
     */
    private static final Map<Integer, Set<String>> KEYS_ADDED_IN = new LinkedHashMap<>();

    static {
        // 1.0.7 → 本轮（闸门 26 → 27）：净增 75 个叶子键，删除 0 个。
        LEAF_COUNTS.put(LAST_RELEASED_VERSION, 711);
        LEAF_COUNTS.put(27, 786);
        // 27 → 28（D7 多余参数）：净增 2 个（word.usage + word.extra-arguments），删除 0 个。
        // 键数用真 YAML 解析数出来（788 = 788，zh/en 一致），不是 grep 数缩进行。
        LEAF_COUNTS.put(28, 788);
        // 28 → 29（/le stats <玩家名> 的纯文本战绩）：净增 11 个
        // （command.stats-text.* 共 11 条：header / score / correct / incorrect /
        //  accuracy / sessions / achievements / hidden / player-not-found /
        //  load-failed / usage），删除 0 个。799 = 799（zh/en 一致）。
        LEAF_COUNTS.put(29, 799);

        SECTIONS_ADDED_IN.put(27, Set.of("diagnose"));
        // 29 → 顶层新增 command: 整段（11 条 command.stats-text.*）。
        // 必须登记在 SECTIONS_ADDED_IN 而**不是** KEYS_ADDED_IN：mergeMissing 对
        // 「用户整段缺失」只走一次 `user.put(key, deepCopy(...)); stats.added++`，
        // 不逐个下钻计数，所以日志里的 added 是 1 而不是 11。登记成 11 个键会让
        // registeredBranchInsertionsSince 算出 11（差 10），与真实合并日志对不上。
        SECTIONS_ADDED_IN.put(29, Set.of("command"));

        // 17 个散落在既有 section 里的新键。58 个 diagnose.* 由 SECTIONS_ADDED_IN 覆盖。
        KEYS_ADDED_IN.put(27, Set.of(
                "admin.broadcast-failed",
                "admin-help.diagnose",
                "class.create-failed",
                "class.delete-failed",
                "class.member-write-failed",
                "daily.reward-capped",
                "duel.lost-by-disconnect",
                "duel.opponent-quit-while-disconnected",
                "duel.paused-opponent-disconnected",
                "gui.english-square-slot-conflict",
                "gui.main-menu-slot-conflict-footer",
                "gui.main-menu-slot-conflict-header",
                "gui.main-menu-slot-conflict-line",
                "report.incomplete-notice",
                "square.feed.cheer-failed",
                "wordbank.import-persist-failed",
                "wordbank.reason-unsafe-path"));

        // 27 → 28：D7 仅两键，都落在既有的 word: 段（该段 27 时就存在，故不进 SECTIONS_ADDED_IN）。
        KEYS_ADDED_IN.put(28, Set.of(
                "word.extra-arguments",
                "word.usage"));

        // 28 → 29 的 11 条 command.stats-text.* **不在这里登记**，见上方
        // SECTIONS_ADDED_IN.put(29, Set.of("command")) 的注释。
        // 这里曾有一版把它们逐个登记在 KEYS_ADDED_IN，注释还写着「用 SECTIONS_ADDED_IN
        // 也能过」—— 那句话是错的：mergeMissing 对整段缺失只计 1 次，而本函数逐键计 11 次，
        // 于是 releasedShapedFileIsUpgradedToCurrent 断言 31 而真实合并日志是 21，当场变红。
        // 教训：新增顶层 section 时先确认 mergeMissing 的计数口径，别把「键数」当「插入次数」。
    }

    /**
     * <b>「当前版本」</b> —— 一律从 {@link #gateVersion()} 派生，<b>绝不写字面量</b>。
     *
     * <p>本类正文此前散落着 23 处 {@code 27} / {@code 786} 字面量：每次 bump 版本都要靠人
     * 逐个手改，R31 → D7 那次就有 4 个用例因此变红。用一个派生值取代后，正文只表达
     * 「当前版本应当满足什么」，下一轮 bump 只需在 {@link #LEAF_COUNTS} /
     * {@link #KEYS_ADDED_IN} 里<b>新增一行历史记录</b>，正文一个字都不用动。
     *
     * <p>注意区分两类数字：{@link #LEAF_COUNTS} 里的 {@code 27→786} 是<b>历史档案</b>
     * （v27 当时的键数，不可改写），必须原样保留；而断言里的「当前键数」必须走
     * {@link #currentLeafCount()}。
     */
    private static final int CURRENT_VERSION = gateVersion();

    /**
     * 当前版本在 {@link #LEAF_COUNTS} 里登记的唯一合法键数。
     *
     * <p>不做裸 {@code LEAF_COUNTS.get(CURRENT_VERSION)}：漏登记时那会抛一个无从下手的
     * {@code NullPointerException}，把「你 bump 了版本却没在表里加行」这个真因藏起来
     * （本类 {@link #gateVersionIsStrictlyAheadOfReleased} 刻意避开 NPE 是同一个理由）。
     */
    private static int currentLeafCount() {
        Integer count = LEAF_COUNTS.get(CURRENT_VERSION);
        if (count == null) {
            throw new AssertionError("LEAF_COUNTS 里没有当前版本 " + CURRENT_VERSION + " 的登记。"
                    + "改语言文件后必须新增一行（而不是修改旧行）。现有登记: " + LEAF_COUNTS);
        }
        return count;
    }

    /**
     * 当前 {@code messages/*.yml} 叶子键集合（排序后按 {@code key\n} 拼接）的 SHA-256。
     *
     * <p>zh 与 en 指纹<b>相同</b>，因为它们键集合本来就完全一致（全量平价由
     * {@code I18nFullParityTest} 管）。这里断言两份各自等于本值，等于同时钉住了平价。
     */
    private static final String KEYSET_SHA256 =
            "50340c67ebf53031385234dae3d86b4cdec0b9e59e0027a06df0c5d53013898b";

    // ============================================================
    //  A. 闸门版本契约（lint）
    // ============================================================

    @Test
    @DisplayName("闸门版本必须严格大于已发布版本 26 —— 否则迁移永不触发（本轮 P0 的唯一直接判据）")
    void gateVersionIsStrictlyAheadOfReleased() {
        int gate = gateVersion();
        // 分支外先算差值：缺陷态下 gate 可能等于 LAST_RELEASED_VERSION 甚至根本没有
        // LEAF_COUNTS 行（未 bump 时 get() 返回 null）。直接在消息里 get(gate) 会抛
        // NPE，或算出 0 并报出「新增的 0 个键全部缺失」这种自相矛盾的句子 —— 两种都会
        // 把「你忘了 bump」这个真因藏起来。
        Integer gateCount = LEAF_COUNTS.get(gate);
        String delta;
        if (gateCount == null) {
            delta = "（LEAF_COUNTS 里没有版本 " + gate + " 的行 —— bump 版本时必须在 LEAF_COUNTS "
                    + "和 SECTIONS_ADDED_IN / KEYS_ADDED_IN 里同步登记）";
        } else {
            int added = gateCount - LEAF_COUNTS.getOrDefault(LAST_RELEASED_VERSION, 0);
            delta = added > 0
                    ? "本轮新增的 " + added + " 个键全部缺失"
                    : "（版本号没动，所以按 LEAF_COUNTS 算出的新增键数是 " + added
                            + " —— 这个 0 本身就是缺陷态，真实新增量请比对 yml 叶子数）";
        }
        assertTrue(gate > LAST_RELEASED_VERSION,
                "MESSAGE_CONFIG_VERSION = " + gate + "，而 1.0.7 已发布的语言文件头部是 "
                        + LAST_RELEASED_VERSION + "。闸门判定是 `version < MESSAGE_CONFIG_VERSION`，"
                        + "等号意味着『已是最新，跳过合并』—— 存量服升级后拿到的仍是旧文件，"
                        + delta
                        + "，玩家会看到 [?key] 字面量。必须 bump 到 "
                        + (LAST_RELEASED_VERSION + 1) + " 以上（并同步 yml 头部与本类的 LEAF_COUNTS）。");
    }

    @Test
    @DisplayName("JAR 内 zh/en 的 config-version 必须等于闸门常量")
    void bundledYmlDeclaresTheGateVersion() {
        int gate = gateVersion();
        for (String path : List.of(ZH, EN)) {
            YamlConfiguration cfg = loadBundled(path);
            assertEquals(gate, cfg.getInt("config-version", -1),
                    path + " 头部的 config-version 与 ConfigManager.MESSAGE_CONFIG_VERSION 不一致"
                            + "（" + cfg.getInt("config-version", -1) + " vs " + gate + "）。"
                            + "迁移写回时用的是常量，两者不一致会让用户文件的版本号与 JAR 资源"
                            + "分叉，下次升级重复合并。");
        }
    }

    @Test
    @DisplayName("当前叶子键数量必须与 LEAF_COUNTS 里「当前版本」登记的数量一致 —— 键集合变了就必须新增版本行")
    void leafCountMatchesDeclaredVersion() {
        int gate = gateVersion();
        String mismatch = countMismatch(LEAF_COUNTS, gate, leafKeys(loadBundled(ZH)).size());
        assertNull(mismatch, mismatch);
    }

    @Test
    @DisplayName("LEAF_COUNTS 必须记录已发布版本与当前版本，且数量只增不减（今日纯新增）")
    void leafCountTableCoversReleasedAndCurrent() {
        int gate = gateVersion();
        assertTrue(LEAF_COUNTS.containsKey(LAST_RELEASED_VERSION),
                "LEAF_COUNTS 丢了已发布版本 " + LAST_RELEASED_VERSION + " 的登记，"
                        + "「存量服起点」这个锚就没了");
        assertTrue(LEAF_COUNTS.containsKey(gate),
                "LEAF_COUNTS 里没有当前版本 " + gate + " 的登记。改语言文件后必须新增一行"
                        + "（而不是修改旧行），这样版本号才被迫往前走。现有登记: " + LEAF_COUNTS);

        int previousVersion = -1;
        int previousCount = -1;
        for (Map.Entry<Integer, Integer> e : LEAF_COUNTS.entrySet()) {
            if (previousVersion >= 0) {
                assertTrue(e.getKey() > previousVersion,
                        "LEAF_COUNTS 的版本号必须递增（声明顺序即时间顺序）: " + LEAF_COUNTS);
                assertTrue(e.getValue() > previousCount,
                        "版本 " + e.getKey() + " 的键数量 (" + e.getValue() + ") 不大于上一版本 "
                                + previousVersion + " (" + previousCount + ")。截至 R31 语言文件只增不减；"
                                + "若本次真的删了键，请在本类注释里写明并显式放宽这条断言。");
            }
            previousVersion = e.getKey();
            previousCount = e.getValue();
        }
        assertEquals(gate, previousVersion,
                "LEAF_COUNTS 的最大版本号必须是当前闸门版本 " + gate + "（实际 " + previousVersion + "）");
    }

    @Test
    @DisplayName("叶子键集合指纹必须匹配 baseline —— 数量不变但「删一个加一个」也要被抓住")
    void keysetFingerprintMatchesBaseline() {
        for (String path : List.of(ZH, EN)) {
            String actual = keysetSha256(leafKeys(loadBundled(path)));
            assertEquals(KEYSET_SHA256, actual,
                    path + " 的叶子键集合变了（指纹不符）。若本次是<b>有意</b>的语言文件变更："
                            + "bump ConfigManager.MESSAGE_CONFIG_VERSION 与两份 yml 头部，"
                            + "在 LEAF_COUNTS / KEYS_ADDED_IN 里新增一行，并把本类 KEYSET_SHA256 "
                            + "更新为该指纹。");
        }
    }

    // ============================================================
    //  B. 行为验证：真把 711 键的 v26 文件升级到当前版本
    // ============================================================

    @Test
    @DisplayName("反推：当前键数减去已登记的全部新增键，精确得到已发布版本的 711 键（=纯新增）")
    void derivationReproducesTheReleasedShape() {
        YamlConfiguration bundled = loadBundled(ZH);
        Set<String> bundledLeaves = leafKeys(bundled);
        int currentCount = bundledLeaves.size();

        Map<String, Object> released = stripKeysAddedAfter(bundled, LAST_RELEASED_VERSION);
        int derivedCount = leafKeys(toConfig(released)).size();

        int recordedAdded = registeredLeavesSince(LAST_RELEASED_VERSION, bundledLeaves);
        int tableDelta = currentLeafCount() - LEAF_COUNTS.get(LAST_RELEASED_VERSION);

        assertEquals(tableDelta, recordedAdded,
                "自 1.0.7 以来登记的全部新增键数 (" + recordedAdded + ") 与 LEAF_COUNTS 里"
                        + "「当前版本 " + CURRENT_VERSION + " 减已发布版本 " + LAST_RELEASED_VERSION
                        + "」的差值 (" + tableDelta + ") 不一致 —— 说明 KEYS_ADDED_IN / "
                        + "SECTIONS_ADDED_IN 记漏或记多了");
        assertEquals(LEAF_COUNTS.get(LAST_RELEASED_VERSION), derivedCount,
                "从当前文件里删掉已登记的全部新增键（" + recordedAdded + " 个）后应得到 "
                        + LEAF_COUNTS.get(LAST_RELEASED_VERSION) + " 个叶子键（已发布版本的形状），"
                        + "实际 " + derivedCount + " 个。差值说明 1.0.7 → 当前之间<b>还有</b>未登记的"
                        + "键变化（新增或删除）。本测试的意义正是证明「纯新增、零删除」，"
                        + "有删除就必须显式面对。");
        assertEquals(currentCount, currentLeafCount(),
                "当前语言文件的叶子键数量与 LEAF_COUNTS 里当前版本 " + CURRENT_VERSION
                        + " 的登记不符");
    }

    @Test
    @DisplayName("P0 复现：已发布形状（711 键 / v26）经真实 loadMessageConfig 后应变成当前版本 / 当前键数")
    void releasedShapedFileIsUpgradedToCurrent(@TempDir Path tempDir) throws Exception {
        int currentCount = currentLeafCount();
        Path file = writeReleasedShapedFile(tempDir, ZH);

        // 前置：夹具真的停在已发布形态（否则下面的升级断言就是假绿）。
        YamlConfiguration before = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(LAST_RELEASED_VERSION, before.getInt("config-version", -1), "夹具版本号不是 26");
        assertEquals(LEAF_COUNTS.get(LAST_RELEASED_VERSION), leafKeys(before).size(),
                "夹具不是 711 键，升级断言失去意义");

        Logger logger = mock(Logger.class);
        ConfigManager manager = managerWith(tempDir, logger);

        FileConfiguration loaded = manager.loadMessageConfig(ZH);

        assertEquals(currentCount, leafKeys(loaded).size(),
                "迁移后内存里的键数量不对。这正是存量服的症状：闸门没触发 → 仍是 "
                        + LEAF_COUNTS.get(LAST_RELEASED_VERSION) + " 键，新键全部缺失");
        for (String key : playerVisibleNewKeys()) {
            assertTrue(loaded.contains(key),
                    "迁移后仍缺键 " + key + " —— 玩家会看到 &c[?" + key + "]");
        }
        assertEquals(gateVersion(), loaded.getInt("config-version", -1),
                "迁移后内存里的 config-version 必须是闸门版本");

        // 落盘 + 快照：升级是"就地合并"，不落盘等于下次启动重来一遍（且玩家本次仍是缺键）。
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(currentCount, leafKeys(onDisk).size(), "合并结果没有写回磁盘");
        assertEquals(gateVersion(), onDisk.getInt("config-version", -1), "磁盘上的版本号没更新");
        assertTrue(Files.exists(Path.of(file + ".bak")),
                "升级前快照 messages/zh.yml.bak 不存在 —— 服主改过的文案没有回退余地");

        YamlConfiguration backup = YamlConfiguration.loadConfiguration(
                Path.of(file + ".bak").toFile());
        assertEquals(LAST_RELEASED_VERSION, backup.getInt("config-version", -1),
                "快照应保留升级前的内容（v26）");
        assertEquals(LEAF_COUNTS.get(LAST_RELEASED_VERSION), leafKeys(backup).size(),
                "快照应保留升级前的 711 键");

        // 日志：一次成功合并必须有且只有一条 "Merged ..." INFO —— 既是可运维性，也是幂等断言的锚。
        //
        // 统计数字<b>从真实数据派生</b>，不写死：
        //   preserved = 升级前文件的叶子数 - config-version（版本号被单独剔除，不参与统计）
        //               711 - 1 = 710。前提是"已发布文件的每个叶子在新版里都存在且类型一致"，
        //               而这正是 {@link #derivationReproducesTheReleasedShape} 钉住的纯新增结论。
        //   added     = 自 1.0.7 以来登记的<b>分支级</b>插入数：散落的新叶子每缺一个计 1，
        //               整段新增的 section 在顶层只计 1（mergeMissing 不逐个下钻计数）。
        // "0 type conflict(s)" 是这条场景最重要的一个数字：711 个既有键与新 JAR 的类型完全一致，
        // 没有任何键被"以新版结构为准"覆盖 —— 因此本轮升级对服主文案是<b>零风险</b>的纯补键。
        int expectedPreserved = leafKeys(before).size() - 1;
        int expectedAdded = registeredBranchInsertionsSince(LAST_RELEASED_VERSION);
        int bundledLeavesExcludingVersion = leafKeys(loadBundled(ZH)).size() - 1;
        int registeredLeaves = registeredLeavesSince(LAST_RELEASED_VERSION, leafKeys(loadBundled(ZH)));
        // 记账恒等式：保留下来的叶子 + 登记的新增叶子，必须正好铺满 JAR 内除 config-version
        // 以外的全部叶子 —— 既不重复计数，也不漏键。这条与上面两条精确值互为交叉验证。
        assertEquals(bundledLeavesExcludingVersion, expectedPreserved + registeredLeaves,
                "合并记账不闭合：preserved(" + expectedPreserved + ") + 登记的新增叶子("
                        + registeredLeaves + ") 不等于 JAR 内叶子数减 config-version("
                        + bundledLeavesExcludingVersion + ")");

        verify(logger, times(1)).info(contains("Merged " + ZH));
        verify(logger, times(1)).info(contains(expectedPreserved + " user value(s) kept, "
                + expectedAdded + " new key(s) added"));
        verify(logger, times(1)).info(contains("0 obsolete key(s) kept"));
        verify(logger, times(1)).info(contains("0 type conflict(s) resolved"));

        // 幂等：第二次调用时磁盘版本已是当前版本，闸门不再触发，不得再合并一次。
        FileConfiguration second = manager.loadMessageConfig(ZH);
        assertEquals(currentCount, leafKeys(second).size(), "第二次加载把键搞丢/搞多了");
        verify(logger, times(1)).info(contains("Merged " + ZH));
    }

    @Test
    @DisplayName("服主改过的文案在合并中被保留（用户值优先，升级不得回退文案）")
    void userCustomisedValuesArePreserved(@TempDir Path tempDir) throws Exception {
        Path file = writeReleasedShapedFile(tempDir, ZH);

        YamlConfiguration custom = YamlConfiguration.loadConfiguration(file.toFile());
        custom.set("prefix", "&8[&a我家服&8] &f");
        custom.set("general.no-permission", "&c你没这个权限啦，别硬来~");
        custom.set("duel.win", "&a你赢了自己那一份");
        custom.save(file.toFile());

        Logger logger = mock(Logger.class);
        ConfigManager manager = managerWith(tempDir, logger);
        FileConfiguration loaded = manager.loadMessageConfig(ZH);

        assertEquals("&8[&a我家服&8] &f", loaded.getString("prefix"),
                "迁移把服主自定义的 prefix 覆盖成默认值了");
        assertEquals("&c你没这个权限啦，别硬来~", loaded.getString("general.no-permission"),
                "迁移把服主改过的既有键覆盖了");
        assertEquals("&a你赢了自己那一份", loaded.getString("duel.win"),
                "迁移把服主改过的既有键覆盖了");
        // 同时新键仍然补齐 —— 保留用户值与补齐缺键必须同时成立。
        assertTrue(loaded.contains("diagnose.header"), "补新键与保留用户值不能二选一");
        assertEquals(currentLeafCount(), leafKeys(loaded).size(),
                "键数量不对：保留用户值的同时必须把自 1.0.7 以来新增的键全部补齐");
    }

    @Test
    @DisplayName("JAR 资源缺失时降级：打 WARNING、文件一字不动、内存用旧内容（不静默）")
    void missingBundledResourceDegradesLoudly(@TempDir Path tempDir) throws Exception {
        Path file = writeReleasedShapedFile(tempDir, ZH);
        byte[] originalBytes = Files.readAllBytes(file);

        Logger logger = mock(Logger.class);
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getResource(anyString())).thenReturn(null); // 模拟资源读不到

        FileConfiguration loaded = new ConfigManager(plugin).loadMessageConfig(ZH);

        assertEquals(LEAF_COUNTS.get(LAST_RELEASED_VERSION), leafKeys(loaded).size(),
                "降级后内存里应是旧内容（711 键）");
        assertArrayEqualsBytes(originalBytes, Files.readAllBytes(file),
                "降级时原文件必须一字不动（迁移失败不得破坏服主的文件）");
        verify(logger).warning(contains("Cannot upgrade " + ZH));
    }

    // ============================================================
    //  C. 检查器自检：确认上面的断言真的会变红
    // ============================================================

    @Test
    @DisplayName("自检：countMismatch 对「版本对不上」真的会报，对正确组合才返回 null")
    void countMismatchReallyFires() {
        // 这里刻意用<b>历史版本 v27</b> 当测试数据，而不是 {@link #CURRENT_VERSION}：
        // 本用例测的是 countMismatch 这个纯函数的判别力，需要一对"已知且永远不变"的
        // 版本/数量组合（v27 的档案值 786 已封存，不会随 bump 漂移）。
        // 若改用当前版本，它只会跟着表一起变，反而失去"已知输入 → 已知输出"的锚。
        int archivedVersion = 27;
        int archivedCount = LEAF_COUNTS.get(archivedVersion);
        assertNull(countMismatch(LEAF_COUNTS, archivedVersion, archivedCount),
                "正确组合被误判为不一致");
        assertNotNull(countMismatch(LEAF_COUNTS, archivedVersion, archivedCount - 1),
                "数量差 1 时必须报");
        assertNotNull(countMismatch(LEAF_COUNTS, LAST_RELEASED_VERSION, archivedCount),
                "用旧版本号对当前数量时必须报");
        assertNotNull(countMismatch(LEAF_COUNTS, 99, archivedCount),
                "未登记的版本号必须报（不许静默放过）");
    }

    @Test
    @DisplayName("自检：指纹对键集合变化敏感、对顺序不敏感")
    void fingerprintReallyDiscriminates() throws Exception {
        Set<String> base = new TreeSet<>(Set.of("a.one", "a.two", "b"));
        assertEquals(keysetSha256(base), keysetSha256(new LinkedHashSet<>(List.of("b", "a.two", "a.one"))),
                "指纹对声明顺序敏感了：行序调整不应触发版本 bump");
        Set<String> added = new TreeSet<>(base);
        added.add("a.three");
        assertNotEqualsHashes(keysetSha256(base), keysetSha256(added), "加一个键指纹必须变");
        Set<String> swapped = new TreeSet<>(Set.of("a.one", "a.three", "b"));
        assertNotEqualsHashes(keysetSha256(base), keysetSha256(swapped),
                "删一个加一个（数量不变）指纹也必须变");
    }

    @Test
    @DisplayName("自检：stripKeysAddedAfter 真的删掉了登记的键与整段，且只删这些")
    void stripKeysAddedAfterReallyStrips() {
        YamlConfiguration bundled = loadBundled(ZH);
        Map<String, Object> released = stripKeysAddedAfter(bundled, LAST_RELEASED_VERSION);
        YamlConfiguration releasedCfg = toConfig(released);

        assertFalse(releasedCfg.contains("diagnose.header"), "整段 diagnose 没被删掉");
        assertFalse(releasedCfg.contains("duel.lost-by-disconnect"), "登记的新键没被删掉");
        assertFalse(releasedCfg.contains("diagnose"), "空的 diagnose section 没被清理");
        // 未登记的既有键必须原样留下，否则"711 键"是删多了凑出来的假象。
        assertTrue(releasedCfg.contains("duel.win"), "删多了：既有键 duel.win 被误删");
        assertTrue(releasedCfg.contains("general.no-permission"), "删多了：既有键被误删");
        assertTrue(releasedCfg.contains("prefix"), "删多了：prefix 被误删");
    }

    // ============================================================
    //  工具
    // ============================================================

    /** 读私有常量 {@code MESSAGE_CONFIG_VERSION}（与 {@code ConfigManagerGuiMaterialTest} 同一手法）。 */
    private static int gateVersion() {
        try {
            java.lang.reflect.Field f = ConfigManager.class.getDeclaredField("MESSAGE_CONFIG_VERSION");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法读取 MESSAGE_CONFIG_VERSION", e);
        }
    }

    /**
     * 自 {@code afterVersion} 之后<b>所有</b>版本登记的<b>叶子键</b>数量（历史记录的全量和）。
     *
     * <p>刻意不写死某一版（原先写的是 {@code KEYS_ADDED_IN.get(27)}）：版本每涨一档就可能
     * 又多登记几个键，逐版点名会让每次 bump 都要回来改断言。这里改成"把表里
     * {@code > afterVersion} 的行全加起来"，于是新增一版只需新增表行。
     *
     * <p>整段新增（{@link #SECTIONS_ADDED_IN}）的贡献用<b>当前文件里该段的实际叶子数</b>计算，
     * 而不是"记得当初有 58 个"——段内少了一个键会立刻让数量对不上，而不是被历史数字掩盖。
     */
    private static int registeredLeavesSince(int afterVersion, Set<String> bundledLeaves) {
        int total = 0;
        Set<String> coveredBySections = new HashSet<>();
        for (Map.Entry<Integer, Set<String>> e : SECTIONS_ADDED_IN.entrySet()) {
            if (e.getKey() <= afterVersion) {
                continue;
            }
            for (String section : e.getValue()) {
                String prefix = section + ".";
                total += (int) bundledLeaves.stream().filter(k -> k.startsWith(prefix)).count();
                coveredBySections.add(prefix);
            }
        }
        for (Map.Entry<Integer, Set<String>> e : KEYS_ADDED_IN.entrySet()) {
            if (e.getKey() <= afterVersion) {
                continue;
            }
            for (String key : e.getValue()) {
                if (coveredBySections.stream().anyMatch(key::startsWith)) {
                    continue; // 已被整段新增覆盖，不重复计数
                }
                total++;
            }
        }
        return total;
    }

    /**
     * 自 {@code afterVersion} 之后登记的<b>分支级插入</b>数量 —— 即 {@code mergeMissing}
     * 的 {@code stats.added} 口径：整段新增的 section 在顶层只计 <b>1</b> 次，段内叶子不逐个下钻。
     *
     * <p>这是日志 {@code "N new key(s) added"} 的期望值来源。与
     * {@link #registeredLeavesSince} 的差别只在于整段 section 的记账粒度，两个数配合
     * {@code preserved} 可以交叉验证合并是否既不重复计数也不漏键。
     */
    private static int registeredBranchInsertionsSince(int afterVersion) {
        int total = 0;
        Set<String> sectionPrefixes = new HashSet<>();
        for (Map.Entry<Integer, Set<String>> e : SECTIONS_ADDED_IN.entrySet()) {
            if (e.getKey() <= afterVersion) {
                continue;
            }
            for (String section : e.getValue()) {
                total++;
                sectionPrefixes.add(section + ".");
            }
        }
        for (Map.Entry<Integer, Set<String>> e : KEYS_ADDED_IN.entrySet()) {
            if (e.getKey() <= afterVersion) {
                continue;
            }
            for (String key : e.getValue()) {
                if (sectionPrefixes.stream().anyMatch(key::startsWith)) {
                    continue; // 整段新增已按 1 次计入
                }
                total++;
            }
        }
        return total;
    }

    /** 纯函数：数量与登记不符时返回说明，一致时返回 {@code null}（便于自检）。 */
    private static String countMismatch(Map<Integer, Integer> table, int version, int actualCount) {
        Integer expected = table.get(version);
        if (expected == null) {
            return "LEAF_COUNTS 里没有版本 " + version + " 的登记；改语言文件后必须新增一行。"
                    + "现有登记: " + table;
        }
        if (expected != actualCount) {
            return "版本 " + version + " 登记的叶子键数量是 " + expected + "，实际 " + actualCount
                    + "（差 " + (actualCount - expected) + "）。语言文件的键集合变了却没 bump 版本号："
                    + "存量服的迁移闸门不会触发，新键对老服永远不可见。"
                    + "正确做法是 bump MESSAGE_CONFIG_VERSION + 两份 yml 头部，并新增 "
                    + version + 1 + "→" + actualCount + " 这一行。";
        }
        return null;
    }

    /** 叶子键集合（{@code getKeys(true)} 里所有非 section 的路径）。含 {@code config-version}。 */
    private static Set<String> leafKeys(ConfigurationSection cfg) {
        Set<String> out = new TreeSet<>();
        for (String key : cfg.getKeys(true)) {
            if (!cfg.isConfigurationSection(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** 排序后按 {@code key\n} 拼接取 SHA-256 —— 与声明顺序、注释、文案值都无关。 */
    private static String keysetSha256(Set<String> keys) {
        try {
            StringBuilder sb = new StringBuilder(keys.size() * 24);
            for (String key : new TreeSet<>(keys)) {
                sb.append(key).append('\n');
            }
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("JDK 必须自带 SHA-256", e);
        }
    }

    /** 明确断言"两个指纹不同"（避免用 assertEquals 时语义读反）。 */
    private static void assertNotEqualsHashes(String expectedDifferent, String actual, String message) {
        if (expectedDifferent.equals(actual)) {
            throw new AssertionError(message + "（两个指纹相同: " + actual + "）");
        }
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual, String message) {
        assertEquals(expected.length, actual.length, message + "（长度不同）");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError(message + "（首个差异在字节 " + i + "）");
            }
        }
    }

    /** 内置语言文件（测试 classpath = target/classes，与生产 {@code readBundledConfig} 同入口）。 */
    private static YamlConfiguration loadBundled(String resource) {
        InputStream in = I18nVersionGateTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, "测试 classpath 上找不到 " + resource + "（target/classes 没进 classpath？）");
        try (InputStream stream = in;
             Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("读取内置资源失败: " + resource, e);
        }
    }

    /**
     * 推导"已发布版本形状"的配置树：从当前文件里删掉 {@code afterVersion} 之后所有登记的
     * 新增 section 与新增叶子键，并清理因删除而变空的 section。
     */
    private static Map<String, Object> stripKeysAddedAfter(ConfigurationSection current,
                                                           int afterVersion) {
        Map<String, Object> tree = toTree(current);
        for (Map.Entry<Integer, Set<String>> e : SECTIONS_ADDED_IN.entrySet()) {
            if (e.getKey() > afterVersion) {
                for (String section : e.getValue()) {
                    tree.remove(section);
                }
            }
        }
        for (Map.Entry<Integer, Set<String>> e : KEYS_ADDED_IN.entrySet()) {
            if (e.getKey() > afterVersion) {
                for (String path : e.getValue()) {
                    removePath(tree, path);
                }
            }
        }
        pruneEmptySections(tree);
        return tree;
    }

    private static Map<String, Object> toTree(ConfigurationSection section) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            tree.put(key, value instanceof ConfigurationSection child ? toTree(child) : value);
        }
        return tree;
    }

    @SuppressWarnings("unchecked")
    private static void removePath(Map<String, Object> tree, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            tree.remove(path);
            return;
        }
        Object child = tree.get(path.substring(0, dot));
        if (child instanceof Map<?, ?> map) {
            removePath((Map<String, Object>) map, path.substring(dot + 1));
        }
    }

    @SuppressWarnings("unchecked")
    private static void pruneEmptySections(Map<String, Object> tree) {
        Iterator<Map.Entry<String, Object>> it = tree.entrySet().iterator();
        while (it.hasNext()) {
            Object value = it.next().getValue();
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> child = (Map<String, Object>) map;
                pruneEmptySections(child);
                if (child.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    private static YamlConfiguration toConfig(Map<String, Object> tree) {
        YamlConfiguration cfg = new YamlConfiguration();
        writeTree(cfg, tree);
        return cfg;
    }

    private static void writeTree(ConfigurationSection target, Map<String, Object> tree) {
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) raw;
                writeTree(target.createSection(e.getKey()), child);
            } else {
                target.set(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 在 {@code tempDir/messages/zh.yml} 落一份"已发布形状"（v26 / 711 键）的语言文件。
     *
     * <p>由当前 JAR 文件反推，因此在键集合变动后会自动跟着动（并先在
     * {@link #derivationReproducesTheReleasedShape()} 处因数量不符而红，不会静默漂移）。
     * 推导出的文件没有注释 —— 不影响解析与合并语义。
     */
    private static Path writeReleasedShapedFile(Path tempDir, String resource) throws Exception {
        Map<String, Object> released = stripKeysAddedAfter(loadBundled(resource), LAST_RELEASED_VERSION);
        // 版本号必须<b>最后</b>写：stripKeysAddedAfter 保留的键集里含 config-version（它是叶子键，
        // 指纹与数量都算它），先写会被 writeTree 用当前文件的版本号覆盖回去 —— 夹具就悄悄变成
        // 「已是最新版」，升级分支永不进入。本条断言放在测试里做前置校验，正是为了不让它静默。
        YamlConfiguration cfg = new YamlConfiguration();
        writeTree(cfg, released);
        cfg.set("config-version", LAST_RELEASED_VERSION);

        Path dir = tempDir.resolve("messages");
        Files.createDirectories(dir);
        Path file = dir.resolve("zh.yml");
        Files.writeString(file, cfg.saveToString(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 最小可用的 {@link ConfigManager}：数据目录指向临时目录，JAR 资源<b>真的</b>从测试
     * classpath 读（这样 {@code readBundledConfig} 走的是与生产完全相同的解析路径）。
     */
    private static ConfigManager managerWith(Path tempDir, Logger logger) {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            InputStream in = I18nVersionGateTest.class.getClassLoader().getResourceAsStream(path);
            return in;
        });
        return new ConfigManager(plugin);
    }

    /**
     * 玩家在 {@code [?key]} 状态下最显眼的几条新键 —— 断言它们迁移后必须存在。
     *
     * <p>选这几条是因为它们落在<b>高频玩家路径</b>上（日常奖励结算 / 对决断线判负 /
     * 广场鼓励写库失败），不是只有管理员敲 {@code /lea diagnose} 才碰得到。
     */
    private static List<String> playerVisibleNewKeys() {
        return List.of(
                "daily.reward-capped",          // 金币日限截断的结算提示（每次触顶都发）
                "duel.lost-by-disconnect",      // 断线超时判负（对决双方都看到）
                "square.feed.cheer-failed",     // 英语广场「鼓励」写库失败
                "report.incomplete-notice",     // 报告正文与聊天摘要卡的数据不完整声明
                "gui.main-menu-slot-conflict-header", // 主菜单槽位冲突 -> 玩家收到提示
                "class.delete-failed");         // 删班失败（管理员路径，同一批新键）
    }

}
