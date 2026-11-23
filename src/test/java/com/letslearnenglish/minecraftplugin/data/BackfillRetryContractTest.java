package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@code answered_count} 回填的<b>可重试性</b>。
 *
 * <h2>这里踩过的两个坑（都靠变异验证抓出来）</h2>
 *
 * <p><b>坑 1：一次性标记是实例字段，重启即丢。</b>原实现用
 * {@code answeredCountColumnJustAdded} 做「只跑一次」的闸门，而它只活在进程内存里。
 * 回填一旦失败（表锁 / 只读挂载 / 连接刚重连），服主重启后新实例里该字段又是
 * {@code false}，而那时列已存在、{@code addColumnIfMissing} 返回 false —— 标记再也
 * 不会置位，<b>回填永远不跑</b>。更糟的是 SEVERE 日志当时写着「下次启动会重试」，
 * 那是一条不会兑现的承诺。
 *
 * <p>修法是取消标记，<b>每次启动都调用</b>，代价由 SQL 自身的
 * {@code WHERE answered_count = 0 AND EXISTS(...)} 限定 —— 稳态下是 0 行。
 *
 * <p><b>坑 2：只写 {@code WHERE answered_count = 0} 不够。</b>「真的答过 0 个词」的
 * 玩家（新号、只上线没答题的）本来就是 0，那个条件仍然匹配它们，每次启动都重写
 * 一遍 0：值没错，但在大服上是一次常态全表扫。必须再加 {@code EXISTS} 才算
 * 「该填但还空着」。这条是本仓用真 sqlite-jdbc 实测出来的 —— 只写
 * {@code = 0} 时第二次回填受影响 2 行（那两个本来就该是 0 的玩家），
 * 加上 EXISTS 后才是 0 行。
 *
 * <h2>为什么只能用源码 lint</h2>
 * <p>「重启后会不会再跑」是跨进程的事实，单个 JVM 内的行为测试构造不出来
 * （要起两个 JVM 或伪造一个全新实例并模拟「列已存在」）。而这条契约的形状是
 * 纯源码的：有没有那个内存闸门、SQL 里有没有 EXISTS。
 */
class BackfillRetryContractTest {

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

    private static String databaseManagerSource() throws IOException {
        return Files.readString(repoRoot().resolve(
                "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java"),
                StandardCharsets.UTF_8);
    }

    /** 抽 {@code backfillAnsweredCount} 的方法体。 */
    static String extractBackfill(String source) {
        int start = source.indexOf("private void backfillAnsweredCount()");
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

    @Test
    @DisplayName("回填不许依赖进程内的「只跑一次」标记（重启后它会丢，回填就再也不跑）")
    void backfillIsNotGatedByInMemoryFlag() throws IOException {
        String src = databaseManagerSource();
        assertTrue(src.contains("backfillAnsweredCount()"),
                "找不到 backfillAnsweredCount 的调用点 —— 逻辑被改写了？同步更新本 lint");
        // 「用内存标记做闸门」的形态：某个 boolean 字段同时被赋 true 和用于 if 判断
        // 来控制 backfill 调用。修好之后这个字段应当完全不存在。
        assertFalse(src.contains("answeredCountColumnJustAdded"),
                "回填又被内存标记闸住了。该标记是实例字段，重启后新实例里是 false；"
                        + "而那时列已存在、addColumnIfMissing 返回 false，标记再不会置位 ——"
                        + "「回填失败 → 重启 → 重试」这条路根本不存在。"
                        + "正确做法：每次启动无条件调用 backfillAnsweredCount()，"
                        + "由 SQL 的 WHERE answered_count = 0 AND EXISTS(...) 限定代价。");
    }

    @Test
    @DisplayName("回填 SQL 必须同时限定 answered_count = 0 与 EXISTS（否则每次启动全表扫）")
    void backfillSqlLimitsToRowsThatActuallyNeedFilling() throws IOException {
        String body = extractBackfill(databaseManagerSource());
        assertFalse(body.isEmpty(), "没找到 backfillAnsweredCount —— 方法被重命名了？");

        String normalized = body.replaceAll("\\s+", " ");
        assertTrue(normalized.contains("answered_count = 0"),
                "回填缺少 answered_count = 0 限定，每次启动都会重写全部玩家行");
        assertTrue(Pattern.compile("EXISTS\\s*\\(\\s*SELECT 1 FROM").matcher(normalized).find(),
                "回填缺少 EXISTS 子句。「真的答过 0 个词」的玩家（新号、只上线没答题的）"
                        + "answered_count 本来就是 0，只有 `= 0` 会匹配它们，每次启动都重写一遍 0；"
                        + "值没错但在大服上是一次常态全表扫（实测 1000 人里 999 个是 0 行）。"
                        + "必须再加 EXISTS 才算「该填但还空着」。");
    }

    @Test
    @DisplayName("回填 SQL 不许给 UPDATE 加表别名（SQLite 直接语法错）")
    void backfillSqlHasNoUpdateAlias() throws IOException {
        String body = extractBackfill(databaseManagerSource());
        // `UPDATE xxx player_data SET` 这种写法在 SQLite 上抛 near "player_data": syntax error
        // 正确形状是 `UPDATE xxx player_data SET`（表名本身就是 player_data，无别名）
        // 用 `UPDATE <prefix>player_data <alias> SET` 形式来判定：SET 前出现两个标识符
        assertFalse(Pattern.compile("UPDATE\\s+\"?\\s*\\+\\s*tablePrefix[^\"]*\"\\s*\\+\\s*\"\\s+\\w+\\s+SET")
                        .matcher(body).find(),
                "回填 SQL 给 UPDATE 加了表别名。实测 SQLite 对 `UPDATE t d SET ...` "
                        + "抛 near \"d\": syntax error —— 插件会起不来。用全名限定，一份语法跑三方言。");
    }
}
