package com.letslearnenglish.minecraftplugin.build;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 源码编码守卫：断言仓库里<b>没有任何 U+FFFD 替换字符</b>。
 *
 * <h2>为什么需要这条（这不是假想的风险，已经发生过）</h2>
 * 提交 {@code 7f6ac81}（"修复 round1 审计的 14 条缺陷"）里，
 * {@code VersionAdapter.java} 的一行中文注释从
 * <pre>（本轮就被坑过一<span>次</span>）。</pre>
 * 变成了
 * <pre>（本轮就被坑过[U+FFFD]次）。</pre>
 * ——原字符被两个替换字符吃掉。当次提交声称「1327 个测试全绿」，**确实全绿**：
 * 1210 个 {@code @Test} 里没有任何一条会读注释。直到下一次有人逐字节比对历史才发现。
 *
 * <p>成因大概率是某次经由 Windows 工具的读写（PowerShell 5.1 对无 BOM 的 UTF-8
 * 中文按 ANSI 解析，是这仓库有书面记录的老毛病），也可能是编辑器/脚本的编码推断。
 * 无论哪种，<b>后果都一样：文件内容静默损坏，编译照过，测试照绿</b>。
 *
 * <p>本仓库随后要做的批量注释整理，正属于「改坏了自己看不出来」的高危操作。
 * 所以先立这道门，再动文件——顺序不能反。
 *
 * <h2>为什么允许显式豁免</h2>
 * 有少数地方**故意**包含 U+FFFD：{@code ScenarioCodec} 的注释在描述"非法字节被
 * {@code new String(bytes, UTF_8)} 替换成 U+FFFD"这个技术事实，{@code CHANGELOG}
 * 记着同一条修复。删掉它们反而会让那段说明失去所指。这类行用行内标记
 * {@code enc-guard-allow} 显式豁免，并在断言失败信息里列出来供复核——
 * 豁免必须一眼可见，不能靠文件白名单静默放过。
 */
@DisplayName("SourceEncodingTest — 源码无 U+FFFD 替换字符")
class SourceEncodingTest {

    /** 替换字符本身。用码点构造，避免这个测试文件自己被误扫。 */
    private static final String REPLACEMENT_CHAR = new String(Character.toChars(0xFFFD));

    /** 显式豁免的行内标记。出现在同一行即视为"作者知道这里为什么有替换字符"。 */
    private static final String ALLOW_MARKER = "enc-guard-allow";

    private static final List<String> SCANNED_DIRS = List.of(
            "src/main/java", "src/main/resources", "src/test/java");
    private static final List<String> SCANNED_ROOT_FILES = List.of(
            "README.md", "README-EN.md", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md",
            "DEPLOYMENT_WIKI.md", "THIRD-PARTY-NOTICES.md");

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("src/main/java"))) return dir;
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根");
    }

    private static List<Path> collectFiles(Path root) {
        List<Path> out = new ArrayList<>();
        for (String d : SCANNED_DIRS) {
            Path dir = root.resolve(d);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".java") || n.endsWith(".yml")
                                    || n.endsWith(".sql") || n.endsWith(".md")
                                    || n.endsWith(".json");
                        })
                        .forEach(out::add);
            } catch (IOException e) {
                fail("遍历 " + d + " 失败: " + e.getMessage());
            }
        }
        for (String f : SCANNED_ROOT_FILES) {
            Path p = root.resolve(f);
            if (Files.isRegularFile(p)) out.add(p);
        }
        return out;
    }

    @Test
    @DisplayName("源码与文档中不存在未豁免的 U+FFFD")
    void noUnescapedReplacementChar() {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();

        for (Path file : collectFiles(root)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 连 UTF-8 都读不了，本身就是问题
                offenders.add(path(root, file) + " : 不是合法 UTF-8 (" + e.getMessage() + ")");
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains(REPLACEMENT_CHAR) && !line.contains(ALLOW_MARKER)) {
                    offenders.add(path(root, file) + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "发现 " + offenders.size() + " 处未豁免的 U+FFFD 替换字符。\n"
                        + "这通常意味着某次编辑把中文写坏了（编译照过、测试照绿，只能靠这道门发现）。\n"
                        + "修法：按上下文还原原文。若某行确实需要描述该字符本身，"
                        + "在该行加注释标记 " + ALLOW_MARKER + " 说明原因。\n\n"
                        + String.join("\n", offenders));
    }

    private static String path(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
