package com.letslearnenglish.minecraftplugin.core.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DC-01：{@code /lea export} 的 CSV 不得把「统计读失败」的零值当可信真值发布。
 *
 * <h2>这个类盯的是哪条缺陷</h2>
 * <p>slice-08-dc-08-dc-DC-01（HIGH）。{@link PlayerDataStore#getPlayerStats} 在统计列
 * 读失败时返回<b>四字段全 0</b> 的实例，且该实例已入 {@code playerStatsCache}、
 * 不再重试 —— 但标记读失败的 {@code statsUnloaded} 集合当时是 {@code private} 且
 * <b>零 accessor</b>。于是所有对外发布统计值的出口都无从判别手里那份 stats 是空壳：
 * {@link DataExporter} 直接把 {@code 0,0,0,0,0} 写进 CSV，与「真的一分未得的新玩家」
 * <b>逐字节相同</b>。服主拿这份文件对账，一行无标记的零值比报错更糟
 * （它看起来是可信的）。
 *
 * <h2>本类如何瞄准它（而不是只跑一遍套件）</h2>
 * <ul>
 *   <li>{@link AccessorContract} —— 缺陷的<b>前置条件</b>：{@code statsUnloaded}
 *       必须有公开只读 accessor，且封装不能被破坏（集合仍 private，不交出引用、
 *       不给写入口）。这两条一起断言，防止「为了让测试过而把集合改成 public」；</li>
 *   <li>{@link ExportMarksUntrustedRows} —— 出口 ① 闭合：读失败的行必须在 CSV 里
 *       <b>可区分</b>；仅打日志不算（服主看的是文件）；</li>
 *   <li>{@link TrustedRowsStillCarryRealValues} —— <b>过滤可靠性正向对照</b>：
 *       统计真实的玩家行，数值必须与 store 给出的一致。没有这一条，「一律标不可信」
 *       这种过度修复也能让上面的断言全绿；</li>
 *   <li>{@link ColumnCountStaysConsistent} —— 列数契约：列头字段数必须与每行字段数
 *       相等（加列忘了改表头会让整份 CSV 错位，且下游按列号取值会静默读错列）。</li>
 * </ul>
 *
 * <p><b>判别力（mutant 检查）</b>：把 {@code DataExporter} 里那列 {@code trusted}
 * 去掉（写回 {@code "0"} 常量或删列）→ {@link ExportMarksUntrustedRows} 变红；
 * 把 {@code trusted} 恒写 {@code "0"}（一律标不可信）→
 * {@link TrustedRowsStillCarryRealValues} 变红；只改行不改表头 →
 * {@link ColumnCountStaysConsistent} 变红。
 *
 * <p>mock 姿势沿用仓库既有范式（真 {@code Logger} 让失败路径 log 不 NPE；
 * {@code getDataFolder} 指到 {@link TempDir}，绝不往仓库落文件）。
 */
class DataExporterTrustedColumnTest {

    private static final UUID TRUSTED_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UNTRUSTED_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FRESH_ZERO_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @TempDir
    Path tempDir;

    private LetsLearnEnglish plugin;
    private PlayerDataStore store;
    private DataExporter exporter;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        store = mock(PlayerDataStore.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("dc01-export-test"));
        exporter = new DataExporter(plugin, store);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 造一份"真读数"：4 个字段都给非零且互不相等的值，错列立刻能看出来。 */
    private static PlayerDataStore.PlayerStats stats(int correct, int incorrect,
                                                     int sessions, long score) {
        PlayerDataStore.PlayerStats s = new PlayerDataStore.PlayerStats();
        s.addCorrect(correct);
        s.addIncorrect(incorrect);
        s.setSessions(sessions);
        s.setScore(score);
        return s;
    }

    /** 跑一次导出，返回 CSV 全文（按 {@code \n} 切行后原样返回）。 */
    private String exportCsv(List<UUID> ids, List<String> names) throws Exception {
        DataExporter.ExportResult result = exporter.exportAllData(ids, names);
        assertTrue(result.success(), "导出本身应成功（失败原因：" + result.errorMessage() + "）");
        File csv = new File(tempDir.toFile(),
                "exports/" + new File(result.filePath()).getName());
        assertTrue(csv.isFile(), "CSV 应落在 exports/ 下，实际：" + csv);
        return Files.readString(csv.toPath(), StandardCharsets.UTF_8);
    }

    /** 取某 UUID 所在的 CSV 行（按逗号切好的字段数组）。 */
    private static String[] rowOf(String csv, UUID id) {
        for (String line : csv.split("\n")) {
            if (line.startsWith(id.toString())) {
                return line.split(",", -1);
            }
        }
        throw new AssertionError("CSV 里找不到玩家行：" + id + "\n全文：\n" + csv);
    }

    private static String[] headerOf(String csv) {
        return csv.split("\n")[0].split(",", -1);
    }

    // ------------------------------------------------------------------
    // 1) 前置条件：accessor 存在且封装未被破坏
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("statsUnloaded 的公开只读出口")
    class AccessorContract {

        @Test
        @DisplayName("PlayerDataStore 暴露了公开的只读 accessor，且集合本身仍是 private")
        void statsUnloadedHasPublicReadOnlyAccessor() throws Exception {
            // 1. 集合字段必须仍是 private —— 修 accessor 不能靠"把集合改 public"。
            var field = PlayerDataStore.class.getDeclaredField("statsUnloaded");
            assertTrue(Modifier.isPrivate(field.getModifiers()),
                    "statsUnloaded 必须保持 private 封装，实际修饰符："
                            + Modifier.toString(field.getModifiers()));
            assertFalse(Modifier.isStatic(field.getModifiers()),
                    "statsUnloaded 是实例字段（每 store 一份），不应变成 static");

            // 2. 必须存在至少一个 public 方法能查询"某玩家是否读失败"。
            Method accessor = null;
            for (Method m : PlayerDataStore.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                if (!m.getName().toLowerCase().contains("statsunloaded")) continue;
                // 只读查询：入参恰好一个 UUID，返回 boolean
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == UUID.class
                        && m.getReturnType() == boolean.class) {
                    accessor = m;
                    break;
                }
            }
            assertTrue(accessor != null,
                    "PlayerDataStore 必须提供 public boolean <name>StatsUnloaded(UUID) "
                            + "形式的只读 accessor —— 零 accessor 正是本缺陷成立的前提");

            // 3. 不得交出集合引用（返回 Set 就等于给了外部写入能力）。
            for (Method m : PlayerDataStore.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                if (!m.getName().toLowerCase().contains("statsunloaded")) continue;
                assertFalse(java.util.Set.class.isAssignableFrom(m.getReturnType()),
                        "accessor 不得返回 Set（会破坏 private 封装并给出写入口）：" + m);
            }
        }

        @Test
        @DisplayName("accessor 反映真实标记状态：命中为 true、未命中为 false、null 不抛")
        void accessorReflectsRealMarkedState() throws Exception {
            // 真 store：构造只赋字段、不连库（DatabaseManager 全 mock）。
            // 不用 mock(PlayerDataStore).thenCallRealMethod() —— inline mock 不走构造函数，
            // statsUnloaded 会是 null，那种"通过"是假的。
            PlayerDataStore realStore =
                    new PlayerDataStore(plugin, mock(DatabaseManager.class));

            // 标记由 loadPlayerData 的读失败分支落下（private）。测试用反射把状态
            // 摆到"已标记"，只验证 accessor 的读语义 —— 读失败的触发路径本身
            // 由 PlayerDataStore 既有测试覆盖，不在本缺陷范围内。
            @SuppressWarnings("unchecked")
            java.util.Set<UUID> marked = (java.util.Set<UUID>) readField(realStore, "statsUnloaded");
            assertFalse(realStore.isStatsUnloaded(UNTRUSTED_ID), "未标记时应为 false");
            marked.add(UNTRUSTED_ID);
            assertTrue(realStore.isStatsUnloaded(UNTRUSTED_ID),
                    "被标记的玩家必须被 accessor 报为不可信");
            assertFalse(realStore.isStatsUnloaded(TRUSTED_ID),
                    "其他玩家不受影响（accessor 不得退化成'任一失败即全员不可信'）");
            assertFalse(realStore.isStatsUnloaded(null),
                    "null 入参应返回 false（不抛 NPE）—— 导出侧不保证 UUID 非空");
        }

        private Object readField(Object target, String name) throws Exception {
            var f = PlayerDataStore.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        }
    }

    // ------------------------------------------------------------------
    // 2) 出口 ①：不可信行必须在 CSV 里可区分
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("出口 ①：/lea export 的 CSV")
    class ExportMarksUntrustedRows {

        @Test
        @DisplayName("统计读失败的玩家行必须被标记为不可信，且与'真零值'行可区分")
        void untrustedRowIsDistinguishableFromGenuineZero() throws Exception {
            // 读失败：真实 store 返回的是全 0 空壳（loadPlayerData 的 catch 分支）。
            when(store.getPlayerStats(UNTRUSTED_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            when(store.isStatsUnloaded(UNTRUSTED_ID)).thenReturn(true);

            // 真零值：新玩家一分未得，但读取是成功的。
            when(store.getPlayerStats(FRESH_ZERO_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            when(store.isStatsUnloaded(FRESH_ZERO_ID)).thenReturn(false);

            String csv = exportCsv(List.of(UNTRUSTED_ID, FRESH_ZERO_ID),
                    List.of("ReadFailed", "NewPlayer"));

            String untrustedRow = String.join(",", rowOf(csv, UNTRUSTED_ID));
            String genuineRow = String.join(",", rowOf(csv, FRESH_ZERO_ID));

            // 核心断言：两行数值部分完全相同，但整行必须不同 —— 否则服主无法区分。
            assertNotEquals(untrustedRow, genuineRow,
                    "读失败的零值行与真零值行在 CSV 里逐字节相同 —— "
                            + "这正是本缺陷：服主会把不可信零值当可信真值。\n"
                            + "不可信行：" + untrustedRow + "\n真零值行：" + genuineRow);

            // 再钉死方向：不可信行必须被明确标 0，可信行标 1（不能反过来）。
            assertTrue(untrustedRow.endsWith(",0"),
                    "统计读失败的行末列应为不可信标记 0，实际行：" + untrustedRow);
            assertTrue(genuineRow.endsWith(",1"),
                    "读取成功的行末列应为可信标记 1，实际行：" + genuineRow);
        }

        @Test
        @DisplayName("不可信标记不能只写日志：CSV 文件内容本身必须携带它")
        void untrustedMarkLandsInTheFileNotJustLogs() throws Exception {
            when(store.getPlayerStats(UNTRUSTED_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            when(store.isStatsUnloaded(UNTRUSTED_ID)).thenReturn(true);

            String csv = exportCsv(List.of(UNTRUSTED_ID), List.of("ReadFailed"));

            // 不许出现"整行缺失"这种静默处理：UUID 与名字必须仍在（服主要能对账）。
            assertTrue(csv.contains(UNTRUSTED_ID.toString()),
                    "不可信玩家仍应出现在 CSV 里（否则对账会少一行）：\n" + csv);
            assertTrue(csv.contains("ReadFailed"),
                    "不可信玩家的名字仍应出现在 CSV 里：\n" + csv);
            // 而且必须有一条能让机器读出来的标记。
            String[] row = rowOf(csv, UNTRUSTED_ID);
            assertEquals("0", row[row.length - 1],
                    "CSV 里的不可信行末列必须是 0（只打日志不算闭合）：\n" + csv);
        }
    }

    // ------------------------------------------------------------------
    // 3) 正向对照：过滤可靠性，防止"一律标不可信"的过度修复
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("正向对照：可信行的数值必须原样落进 CSV")
    class TrustedRowsStillCarryRealValues {

        @Test
        @DisplayName("统计读取成功的玩家，CSV 数值与 store 给出的一致，且标为可信")
        void trustedRowValuesMatchStoreExactly() throws Exception {
            when(store.getPlayerStats(TRUSTED_ID)).thenReturn(stats(37, 4, 9, 1234L));
            when(store.isStatsUnloaded(TRUSTED_ID)).thenReturn(false);

            String csv = exportCsv(List.of(TRUSTED_ID), List.of("GoodPlayer"));
            String[] row = rowOf(csv, TRUSTED_ID);

            assertEquals(TRUSTED_ID.toString(), row[0], "第 0 列是 UUID");
            assertEquals("GoodPlayer", row[1], "第 1 列是玩家名");
            assertEquals("37", row[2], "第 2 列是 Total Words（口径 = totalCorrect）");
            assertEquals("9", row[3], "第 3 列是 Total Sessions");
            assertEquals("1234", row[4], "第 4 列是 Total Score");
            assertEquals("37", row[5], "第 5 列是 Correct");
            assertEquals("4", row[6], "第 6 列是 Incorrect");
            assertEquals("1", row[7], "第 7 列是可信标记 —— 过滤不能把可信行也打成不可信");
        }

        @Test
        @DisplayName("混合批次：可信/不可信各自标记正确，可信行数值不被污染")
        void mixedBatchMarksOnlyTheFailedPlayer() throws Exception {
            UUID secondTrusted = UUID.fromString("44444444-4444-4444-4444-444444444444");
            when(store.getPlayerStats(TRUSTED_ID)).thenReturn(stats(12, 1, 3, 500L));
            when(store.getPlayerStats(secondTrusted)).thenReturn(stats(88, 7, 21, 9001L));
            when(store.getPlayerStats(UNTRUSTED_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            when(store.isStatsUnloaded(TRUSTED_ID)).thenReturn(false);
            when(store.isStatsUnloaded(secondTrusted)).thenReturn(false);
            when(store.isStatsUnloaded(UNTRUSTED_ID)).thenReturn(true);

            String csv = exportCsv(
                    List.of(TRUSTED_ID, UNTRUSTED_ID, secondTrusted),
                    List.of("A", "B", "C"));

            assertEquals("1", rowOf(csv, TRUSTED_ID)[7], "A 应可信");
            assertEquals("0", rowOf(csv, UNTRUSTED_ID)[7], "B 应不可信");
            assertEquals("1", rowOf(csv, secondTrusted)[7], "C 应可信");
            // 可信行数值不被"矫正"成任何占位值。
            assertEquals("88", rowOf(csv, secondTrusted)[2], "C 的 Total Words 应原样");
            assertEquals("9001", rowOf(csv, secondTrusted)[4], "C 的 Total Score 应原样");
        }

        @Test
        @DisplayName("判定必须在 getPlayerStats 之后读（cache-miss 才落标记）")
        void trustFlagIsReadAfterStatsSoCacheMissStillMarks() throws Exception {
            // 真实路径：getPlayerStats 内部才触发 loadPlayerData（读库），
            // 标记是在那次读取里落的。若实现先读标记再取 stats，这里会漏判。
            final boolean[] statsRead = {false};
            when(store.getPlayerStats(UNTRUSTED_ID)).thenAnswer(inv -> {
                statsRead[0] = true;
                return new PlayerDataStore.PlayerStats();
            });
            when(store.isStatsUnloaded(UNTRUSTED_ID)).thenAnswer(inv ->
                    statsRead[0] ? Boolean.TRUE : Boolean.FALSE);

            String csv = exportCsv(List.of(UNTRUSTED_ID), List.of("ReadFailed"));

            assertTrue(statsRead[0], "导出必须先取 stats");
            assertEquals("0", rowOf(csv, UNTRUSTED_ID)[7],
                    "标记必须反映 getPlayerStats 之后的状态 —— "
                            + "先读标记再取 stats 会把 cache-miss 的读失败漏判成可信");
        }
    }

    // ------------------------------------------------------------------
    // 4) 列数契约
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("列数契约")
    class ColumnCountStaysConsistent {

        @Test
        @DisplayName("表头字段数 == 每行字段数（加列必须同步改表头）")
        void headerFieldCountMatchesEveryRow() throws Exception {
            when(store.getPlayerStats(TRUSTED_ID)).thenReturn(stats(1, 2, 3, 4L));
            when(store.getPlayerStats(UNTRUSTED_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            when(store.isStatsUnloaded(TRUSTED_ID)).thenReturn(false);
            when(store.isStatsUnloaded(UNTRUSTED_ID)).thenReturn(true);

            String csv = exportCsv(List.of(TRUSTED_ID, UNTRUSTED_ID), List.of("A", "B"));
            String[] header = headerOf(csv);

            assertEquals(8, header.length,
                    "表头应为 8 列（7 个原列 + 1 个可信标记列），实际：" + String.join(",", header));
            assertEquals("Trusted", header[7].trim(),
                    "第 8 列表头应声明其语义，实际：" + header[7]);
            for (String line : csv.split("\n")) {
                if (line.isEmpty()) continue;
                assertEquals(header.length, line.split(",", -1).length,
                        "行字段数与表头不一致（下游按列号取值会静默读错列）：" + line);
            }
        }

        @Test
        @DisplayName("名字里含逗号/引号时仍保持 8 列（转义不能被新列破坏）")
        void escapingKeepsColumnCountWithNewColumn() throws Exception {
            when(store.getPlayerStats(TRUSTED_ID)).thenReturn(stats(5, 0, 1, 10L));
            when(store.isStatsUnloaded(TRUSTED_ID)).thenReturn(false);

            String csv = exportCsv(List.of(TRUSTED_ID), List.of("Weird,Name\"X"));
            List<String> row = parseCsvLine(rowLineOf(csv, TRUSTED_ID));

            assertEquals(8, row.size(),
                    "含逗号的名字应被引号包裹而不增加列数，实际字段数 " + row.size() + "：" + row);
            assertEquals("Weird,Name\"X", row.get(1),
                    "第 1 列应是还原后的原始名字，实际：" + row.get(1));
            assertEquals("1", row.get(7), "末列仍是可信标记");
        }

        /**
         * 最小的 RFC4180 单行解析器（只支持本测试用到的形态：逗号分隔 + 双引号包裹 +
         * {@code ""} 转义）。用它而不是 {@code split(",")} —— 后者会把被引号包裹的
         * 逗号也当分隔符，从而把「转义正确」误判成「列数膨胀」。
         */
        private static List<String> parseCsvLine(String line) {
            List<String> out = new java.util.ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            cur.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cur.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            out.add(cur.toString());
            return out;
        }

        private static String rowLineOf(String csv, UUID id) {
            for (String line : csv.split("\n")) {
                if (line.startsWith(id.toString())) return line;
            }
            throw new AssertionError("CSV 里找不到玩家行：" + id);
        }
    }
}
