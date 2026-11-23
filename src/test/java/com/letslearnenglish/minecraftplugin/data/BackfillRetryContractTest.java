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
 * 钉住「一次性迁移失败后必须能重试」这条契约。
 *
 * <h2>为什么只能用源码 lint</h2>
 * <p>要行为测试这条，得让 {@code backfillAnsweredCount()} 里的 {@code executeUpdate}
 * 抛 {@code SQLException}，再重启一次验证「是否又跑了一遍」。本仓的
 * {@code UpgradeMigrationTest} 用的是<b>真 SQLite 文件库</b>（刻意不用 mock，见该类的
 * javadoc），在那套装置里注入一次「UPDATE 失败但建表成功」的故障需要包一层会挑语句抛
 * 异常的 {@code Connection} 代理——可行但收益/成本不划算，而断言的本质是「标记的清除
 * 时机」，它是纯源码形状。
 *
 * <h2>缺陷长什么样</h2>
 * <p>回填的一次性标记若在调用<b>之前</b>就清掉：
 * <pre>{@code
 * if (flag) {
 *     flag = false;        // ← 先清
 *     backfill();          // ← 失败也不管
 * }
 * }</pre>
 * 那么回填一旦因数据库抖动失败（表锁 / 只读挂载 / 连接刚重连），重启后列已存在 →
 * {@code addColumnIfMissing} 返回 false → 标记不再置位 → <b>永远不再尝试回填</b>。
 * 后果是老玩家的 {@code answered_count} 停在 0，从英语广场与 {@code %lle_rank%}
 * <b>整批消失</b>，而唯一的提示是一条 SEVERE 日志。
 *
 * <p>正确形状是把「清标记」放进成功分支：
 * <pre>{@code
 * if (flag && backfill()) {   // 或 if (flag) { if (backfill()) flag = false; }
 *     flag = false;
 * }
 * }</pre>
 *
 * <h2>自检</h2>
 * <p>末尾用例对一段人造的错误形状跑同一个检查器，证明它确实会报。
 */
class BackfillRetryContractTest {

    private static final String FLAG = "answeredCountColumnJustAdded";

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

    /**
     * 检查器：找出「在调用 backfill 之前就把标记清掉」的形状。
     *
     * <p><b>必须先剥注释</b>：生产代码在那段上方有一条解释性注释，里面原样写着
     * {@code answeredCountColumnJustAdded = false}（讲的是「原实现在调用前就置 false」）。
     * 不剥注释的话，第一版 lint 会把注释里的那行当成代码，于是<b>真正的缺陷形态
     * 反而判成合规</b> —— 变异验证当场抓到了这个假绿。
     *
     * @return true = 存在「先清标记再回填」的写法（缺陷态）
     */
    static boolean clearsFlagBeforeBackfill(String source) {
        int idx = source.indexOf("if (" + FLAG + ")");
        if (idx < 0) {
            return false; // 找不到消费点 —— 由主用例单独报
        }
        String window = source.substring(idx, Math.min(idx + 2000, source.length()));
        String code = stripComments(window);
        // 缺陷态：`flag = false;` 出现在 `backfillAnsweredCount(` 之前
        int clearAt = code.indexOf(FLAG + " = false");
        int callAt = code.indexOf("backfillAnsweredCount(");
        if (clearAt < 0 || callAt < 0) {
            return false;
        }
        return clearAt < callAt;
    }

    /** 去掉 {@code //} 行注释与 {@code /* *&#47;} 块注释，保留其余字符。 */
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
    @DisplayName("answered_count 回填失败后必须保留标记，以便下次启动重试")
    void flagIsClearedOnlyAfterSuccessfulBackfill() throws IOException {
        Path f = repoRoot().resolve(
                "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java");
        String src = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(src.contains("if (" + FLAG + ")") || src.contains("if (" + FLAG + " &&"),
                "找不到 answered_count 回填标记的消费点 —— 逻辑被改写了？同步更新本 lint");
        assertTrue(Pattern.compile("private\\s+boolean\\s+backfillAnsweredCount\\s*\\(")
                .matcher(src).find(),
                "backfillAnsweredCount 必须返回 boolean（成功与否），调用方据此决定是否清标记");
        assertFalse(clearsFlagBeforeBackfill(src),
                "回填标记在调用 backfillAnsweredCount 之前就被清掉了。"
                        + "回填失败（表锁 / 只读挂载 / 刚重连）后，重启时列已存在 →"
                        + " addColumnIfMissing 返回 false → 标记不再置位 → 永远不再回填。"
                        + "后果是所有老玩家的 answered_count 停在 0，从广场和 %lle_rank% 整批消失。"
                        + "改成『回填成功才清标记』：if (flag && backfill()) flag = false;");
    }

    @Test
    @DisplayName("自检：检查器能认出「先清标记再回填」的错误形状")
    void detectorFlagsClearBeforeCall() {
        String bad = "if (" + FLAG + ") {\n"
                + "    " + FLAG + " = false;\n"
                + "    backfillAnsweredCount();\n"
                + "}\n";
        assertTrue(clearsFlagBeforeBackfill(bad),
                "检查器必须报出这个形状，否则主用例的『未违规』是假的");
    }

    @Test
    @DisplayName("自检：检查器不误伤「成功才清」的正确形状")
    void detectorAcceptsCorrectShape() {
        String good = "if (" + FLAG + ") {\n"
                + "    if (backfillAnsweredCount()) {\n"
                + "        " + FLAG + " = false;\n"
                + "    }\n"
                + "}\n";
        assertFalse(clearsFlagBeforeBackfill(good), "『成功才清标记』不该被误报");
    }
}
