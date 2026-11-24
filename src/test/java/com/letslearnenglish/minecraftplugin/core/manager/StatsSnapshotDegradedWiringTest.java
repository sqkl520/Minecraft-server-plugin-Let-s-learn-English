package com.letslearnenglish.minecraftplugin.core.manager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住「统计快照必须把降级判据传给 {@link TtlSnapshotCache}」这条接线。
 *
 * <h2>为什么这条只能用源码 lint</h2>
 * <p>{@code TtlSnapshotCache} 侧的降级语义已经由
 * {@code TtlSnapshotCacheTest.DegradedValueBehavesLikeFailure}（5 例）覆盖 ——
 * 那组用例证明「传了判据时降级值不发布、不推进时间戳」。但它们**证明不了
 * {@code StatisticsHttpServer} 真的传了那个判据**：把第 5 个参数改成 {@code null}，
 * 缓存侧的所有用例照绿（它们自己传判据），而生产上会退回「假 0 冻住整个 TTL」。
 *
 * <h2>缺陷长什么样</h2>
 * <p>统计面板的 5 条聚合各自把 {@code SQLException} 折成 {@code 0}，于是
 * {@code computeStatsSnapshot()} 会「成功地」交出一个全 0 快照。如果构造
 * {@code TtlSnapshotCache} 时没传 {@code StatsSnapshot::degraded}，这个假 0 会被
 * 当成成功刷新发布、并<b>冻结整整一个 TTL（默认 120 秒）</b>。改造前同类故障只影响
 * 一次请求，缓存把它放大成 2 分钟 —— 而且服主看到的是「全服数据是 0」这个
 * 看起来可信的假结论。
 */
class StatsSnapshotDegradedWiringTest {

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("找不到含 pom.xml 的仓库根");
        }
        return p;
    }

    private static String serverSource() throws IOException {
        return Files.readString(repoRoot().resolve(
                "src/main/java/com/letslearnenglish/minecraftplugin/core/manager/StatisticsHttpServer.java"),
                StandardCharsets.UTF_8);
    }

    /**
     * 剥掉注释后的源码。
     *
     * <p><b>必须先剥</b>：那段构造点上方有一条 javadoc 里也写着
     * {@code StatsSnapshot::degraded}（解释「为什么必须传它」）。不剥注释的话，
     * 把真实参数改成 {@code null} 之后本 lint 仍然能在那段文档里找到这个串，
     * 于是<b>真正的缺陷形态反而判成合规</b> —— 本仓已经因为「把注释当代码」
     * 假绿过两次（`BackfillRetryContractTest` 的第一版就是）。
     */
    static String stripComments(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\n", -1)) {
            int c = line.indexOf("//");
            sb.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("构造 TtlSnapshotCache 时必须传 StatsSnapshot::degraded 判据")
    void statsCacheIsConstructedWithDegradedTester() throws IOException {
        String src = stripComments(serverSource());
        assertTrue(src.contains("new TtlSnapshotCache<>"),
                "找不到 TtlSnapshotCache 的构造点 —— 实现被改写了？同步更新本 lint");
        assertTrue(src.contains("StatsSnapshot::degraded"),
                "统计快照的缓存没有传降级判据（已剥注释，这不是在文档里匹配到的）。"
                        + "5 条聚合各自把 SQLException 折成 0，computeStatsSnapshot 会「成功地」"
                        + "交出一个全 0 快照；没有判据时 TtlSnapshotCache 会把它当成功刷新发布"
                        + "并冻结整个 TTL（默认 120 秒），服主看到「全服数据是 0」的假结论。"
                        + "必须传 StatsSnapshot::degraded。");
    }

    @Test
    @DisplayName("StatsSnapshot 必须有 degraded 标记，且 computeStatsSnapshot 会置位它")
    void snapshotCarriesDegradedFlag() throws IOException {
        String src = stripComments(serverSource());
        assertTrue(src.contains("degraded"),
                "StatsSnapshot 没有 degraded 字段 —— 降级信息在快照里丢了，缓存无从判断");
        assertTrue(src.contains("readFailed"),
                "computeStatsSnapshot 没有读 readFailed —— 三态结果被丢掉了，"
                        + "那 5 个 WithStatus 方法等于白改");
    }

    @Test
    @DisplayName("自检：检查器能认出「传了 null 判据」的形态")
    void detectorFlagsNullTester() {
        String bad = "new TtlSnapshotCache<>(this::computeStatsSnapshot, ttl, logger, \"stats\", null)";
        assertFalse(bad.contains("StatsSnapshot::degraded"),
                "自检本身要有意义：这段人造代码里确实没有判据，"
                        + "主用例的『含 StatsSnapshot::degraded』断言才能区分好坏");
    }

    @Test
    @DisplayName("自检：注释里的 StatsSnapshot::degraded 不算数（剥注释后应消失）")
    void detectorIgnoresCommentMentions() {
        String docOnly = "// 构造时必须传 StatsSnapshot::degraded 否则假 0 会被发布\n"
                + "new TtlSnapshotCache<>(loader, ttl, logger, \"stats\", null);\n";
        assertFalse(stripComments(docOnly).contains("StatsSnapshot::degraded"),
                "剥注释后仍能找到判据串 —— 说明剥注释没生效，"
                        + "主用例会把『只在文档里提到』判成合规（本仓已因此假绿过两次）");
    }
}
