package com.letslearnenglish.minecraftplugin.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住「调度器拒收异常必须被正确捕获」这条跨全仓的契约。
 *
 * <h2>为什么需要源码 lint 而不是行为测试</h2>
 * <p>Paper 的 {@code CraftScheduler.validate()} 在插件已禁用时抛的是
 * {@code org.bukkit.plugin.IllegalPluginAccessException}，它 <b>extends
 * {@link RuntimeException}</b> 而 <b>不是</b> {@link IllegalStateException} 的子类
 * （已用 paper-api 的 {@code javap} 与真实 Paper 1.21.4 jar 的 {@code CraftScheduler.class}
 * 反汇编双向确认：offset 56 是 {@code new IllegalPluginAccessException}，offset 66 是
 * {@code athrow}）。
 *
 * <p>因此任何 <code>catch (IllegalStateException)</code> 在关服窗口都接不住调度器抛的异常，
 * 异常会穿透到调用方。这个缺陷在本仓存在了很长时间，而 <b>1929 条测试一条都没红</b> ——
 * 因为测试替身自己也抛 {@code IllegalStateException}，把生产代码的错误假设原样复刻了一遍。
 *
 * <p>修好之后要防止它退回去。逐个 mock 28 处派发点的成本过高（且大部分在 GUI 里，
 * 需要完整的 Inventory 栈），而这条契约的性质是「源码形状」，用 lint 钉住更直接：
 * <b>凡是紧邻 {@code runTask*} / {@code getScheduler()} 的 catch，不允许只捕获
 * {@code IllegalStateException}</b>。
 *
 * <h2>这条 lint 自己也会变红</h2>
 * <p>末尾的自检用例直接对一段人造源码跑同一个检查器，证明它不是一个永远返回空集的假检查器。
 */
class SchedulerRejectionCatchTest {

    /** 仓库根：从 target/test-classes 往上两级就是项目根。 */
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
     * 检查器本体：返回「紧邻调度器调用、且只捕获 IllegalStateException」的位置。
     *
     * <p>判定方式：逐个 {@code catch} 位置往上找 8 行，若出现 {@code runTask} 或
     * {@code getScheduler()}，再检查该 catch 的参数里有没有 {@code IllegalPluginAccessException}
     * 或 {@code RuntimeException}。两者都没有 = 违规。
     */
    static List<String> findViolations(String source, String label) {
        List<String> bad = new ArrayList<>();
        List<String> lines = List.of(source.split("\n", -1));
        Pattern catchPat = Pattern.compile("catch\\s*\\(([^)]*)\\)");
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = catchPat.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            String params = m.group(1);
            if (!params.contains("IllegalStateException")) {
                continue;
            }
            // 已正确并列捕获 / 用父类型收口 → 合规
            if (params.contains("IllegalPluginAccessException") || params.contains("RuntimeException")) {
                continue;
            }
            // 往上 8 行找调度器调用
            int from = Math.max(0, i - 8);
            boolean guardsScheduler = false;
            for (int j = from; j < i; j++) {
                String up = lines.get(j);
                if (up.contains("runTask") || up.contains("getScheduler()")) {
                    guardsScheduler = true;
                    break;
                }
            }
            if (guardsScheduler) {
                bad.add(label + ":" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
        return bad;
    }

    @Test
    @DisplayName("全仓：紧邻调度器调用的 catch 不允许只接 IllegalStateException")
    void noSchedulerCatchOnlyCatchesIllegalStateException() throws IOException {
        Path src = repoRoot().resolve("src/main/java");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = repoRoot().relativize(f).toString().replace('\\', '/');
                violations.addAll(findViolations(
                        Files.readString(f, StandardCharsets.UTF_8), rel));
            }
        }
        assertTrue(violations.isEmpty(),
                "以下 catch 在关服窗口接不住 CraftScheduler 抛的 IllegalPluginAccessException"
                        + "（它 extends RuntimeException，不是 IllegalStateException 的子类）。"
                        + "改成 catch (IllegalPluginAccessException | IllegalStateException …)"
                        + " 或用 catch (RuntimeException …) 收口：\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("自检：检查器对只捕获 ISE 的调度器守卫会报违规（证明它不是永远返回空集）")
    void detectorFlagsBareIllegalStateExceptionGuard() {
        String bad = """
                try {
                    plugin.getServer().getScheduler().runTask(plugin, action);
                } catch (IllegalStateException ignored) {
                }
                """;
        assertEquals(1, findViolations(bad, "<self-check>").size(),
                "检查器必须能认出裸 IllegalStateException 守卫，否则主用例的『零违规』是假的");
    }

    @Test
    @DisplayName("自检：并列捕获与 RuntimeException 收口都不算违规")
    void detectorAcceptsCorrectGuards() {
        String both = """
                try {
                    scheduler.runTask(plugin, action);
                } catch (IllegalPluginAccessException | IllegalStateException ignored) {
                }
                """;
        String parent = """
                try {
                    scheduler.runTaskAsynchronously(plugin, task);
                } catch (RuntimeException e) {
                }
                """;
        assertEquals(0, findViolations(both, "<both>").size(), "并列捕获应合规");
        assertEquals(0, findViolations(parent, "<parent>").size(), "RuntimeException 收口应合规");
    }

    @Test
    @DisplayName("自检：包的不是调度器调用时，裸 ISE 不算违规（避免误伤业务异常）")
    void detectorIgnoresNonSchedulerCatches() {
        String business = """
                try {
                    manager.startQuiz(player, difficulty);
                } catch (IllegalStateException e) {
                    player.sendMessage("session in progress");
                }
                """;
        assertEquals(0, findViolations(business, "<business>").size(),
                "业务异常不是调度器异常，不该被本 lint 误报");
    }
}
