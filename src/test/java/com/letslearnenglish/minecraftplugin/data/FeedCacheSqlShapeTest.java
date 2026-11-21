package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@code getFeedCacheRows} 的 SQL 形状：不许写 {@code GROUP BY}。
 *
 * <h2>为什么这条只能用源码 lint</h2>
 * <p>本仓所有测试都跑在 SQLite 上（内存库或临时文件库），而这个问题 <b>只在 MariaDB
 * 上才暴露</b>。SQLite 接受 {@code GROUP BY 主键} 并自由选取其余列，所以任何行为测试
 * 在 SQLite 上都会绿——包括一条在 MariaDB 上必然 1055 报错的写法。
 *
 * <p>MariaDB 没有实现「函数依赖检测」。在 {@code ONLY_FULL_GROUP_BY} 下：
 * <ul>
 *   <li>{@code GROUP BY id} 且选其他非聚合列 → {@code ERROR 1055 'x' isn't in GROUP BY}</li>
 *   <li>{@code GROUP BY} 全列 → 合法，但会让 SQLite 把计划从「覆盖索引范围扫」
 *       降级成「全表扫」（本机 210k 行实测 75 ms → 217 ms）</li>
 *   <li>完全不写 {@code GROUP BY} → 合法、最快、跨方言一致</li>
 * </ul>
 *
 * <p>而这条查询根本不需要 {@code GROUP BY}：两个 {@code LEFT JOIN} 的关联列都是各自
 * 表的主键（{@code player_data.uuid} / {@code player_profiles.uuid} 均为
 * {@code PRIMARY KEY}），是严格的 1:1，不产生行乘数。
 *
 * <h2>自检</h2>
 * <p>末尾用例对一段人造源码跑同一个检查器，证明它不是永远返回 true 的假检查器。
 */
class FeedCacheSqlShapeTest {

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

    /** 抽出 getFeedCacheRows 方法体（从方法签名到下一个同级方法）。 */
    static String extractMethod(String source) {
        int start = source.indexOf("public List<FeedRow> getFeedCacheRows");
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inBody = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                inBody = true;
            } else if (c == '}') {
                depth--;
                if (inBody && depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }

    /** 检查器：方法体内出现 GROUP BY 即违规。 */
    static boolean hasGroupBy(String methodBody) {
        // 只认真正的 SQL 片段，排除注释里的说明文字
        for (String line : List.of(methodBody.split("\n", -1))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            if (Pattern.compile("GROUP\\s+BY", Pattern.CASE_INSENSITIVE).matcher(trimmed).find()) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("getFeedCacheRows 不许写 GROUP BY（MariaDB 上 GROUP BY 主键会 1055）")
    void feedCacheQueryHasNoGroupBy() throws IOException {
        Path f = repoRoot().resolve(
                "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java");
        String src = Files.readString(f, StandardCharsets.UTF_8);
        String body = extractMethod(src);
        assertFalse(body.isEmpty(), "没找到 getFeedCacheRows —— 方法被重命名了？同步更新本 lint");
        assertFalse(hasGroupBy(body),
                "getFeedCacheRows 里出现了 GROUP BY。两个 LEFT JOIN 的关联列都是主键（严格 1:1），"
                        + "GROUP BY 是冗余的；而 MariaDB 在 ONLY_FULL_GROUP_BY 下对"
                        + "『GROUP BY 主键 + 取其他非聚合列』会直接报 ERROR 1055，"
                        + "写成全列 GROUP BY 又会让 SQLite 计划降级成全表扫。"
                        + "这条查询不需要 GROUP BY。");
    }

    @Test
    @DisplayName("getFeedCacheRows 的 ORDER BY 带 id 做 tie-break")
    void feedCacheQueryHasDeterministicTieBreak() throws IOException {
        Path f = repoRoot().resolve(
                "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java");
        String body = extractMethod(Files.readString(f, StandardCharsets.UTF_8));
        assertTrue(Pattern.compile("ORDER BY\\s+f\\.updated_at\\s+DESC\\s*,\\s*f\\.id\\s+DESC",
                        Pattern.CASE_INSENSITIVE).matcher(body).find(),
                "ORDER BY 必须带 f.id 做 tie-break：updated_at 大量重复时顺序不定，"
                        + "会让内存快照出现同一行两次或漏行。");
    }

    @Test
    @DisplayName("idx_feed_updated 索引在 DDL 与 schema.sql 双侧存在")
    void feedUpdatedIndexDeclaredInBothPlaces() throws IOException {
        String dm = Files.readString(repoRoot().resolve(
                "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java"),
                StandardCharsets.UTF_8);
        String schema = Files.readString(repoRoot().resolve(
                        "src/main/resources/schema.sql"), StandardCharsets.UTF_8);
        assertTrue(dm.contains("idx_feed_updated"), "运行时 DDL 缺 idx_feed_updated");
        assertTrue(schema.contains("idx_feed_updated"), "schema.sql 缺 idx_feed_updated 镜像");
        // 索引必须是单列 updated_at，写成复合就满足不了全局序
        assertTrue(Pattern.compile("idx_feed_updated[^;]*social_feed\\(updated_at\\)")
                        .matcher(dm.replaceAll("\\s+", "")).find()
                        || dm.replaceAll("\\s+", "").contains("social_feed(updated_at)\""),
                "idx_feed_updated 必须是 social_feed(updated_at) 单列索引");
    }

    @Test
    @DisplayName("自检：检查器能认出 GROUP BY（证明它不是永远返回 false）")
    void detectorFlagsGroupBy() {
        String bad = "public List<FeedRow> getFeedCacheRows(int a, int b) {\n"
                + "    String sql = \"SELECT ... GROUP BY f.id ORDER BY x LIMIT ?\";\n"
                + "    return null;\n"
                + "}";
        assertTrue(hasGroupBy(extractMethod(bad)), "检查器必须能认出 GROUP BY，否则主用例是假绿");
    }

    @Test
    @DisplayName("自检：注释里提到 GROUP BY 不算违规（避免误伤说明文字）")
    void detectorIgnoresComments() {
        String clean = "public List<FeedRow> getFeedCacheRows(int a, int b) {\n"
                + "    // 这里刻意不写 GROUP BY，因为 MariaDB 会 1055\n"
                + "    String sql = \"SELECT ... ORDER BY f.updated_at DESC, f.id DESC LIMIT ?\";\n"
                + "    return null;\n"
                + "}";
        assertFalse(hasGroupBy(extractMethod(clean)), "注释里的 GROUP BY 不该被当成违规");
    }
}
