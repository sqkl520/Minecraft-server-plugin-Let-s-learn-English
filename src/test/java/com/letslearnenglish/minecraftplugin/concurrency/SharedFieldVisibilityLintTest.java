package com.letslearnenglish.minecraftplugin.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R18（并发专场 · LOW 级）：<b>跨线程共享字段的可见性</b>源码 lint。
 *
 * <h2>被钉住的缺陷</h2>
 * <p>本仓已经写下过一条明确纪律（{@code LetsLearnEnglish} 的
 * 「R4-CON-01：API 把下列字段转发给第三方插件，故 volatile 保证可见性」）——
 * 说明作者<b>知道</b>这条规矩，但只对一部分字段执行了。于是同一批「主线程写、
 * 异步线程读」的管理器字段里，有的带 {@code volatile}、有的不带，看代码的人
 * 无法从形状上判断哪些是刻意的。</p>
 *
 * <p>本次补齐的两个文件：</p>
 * <ol>
 *   <li>{@code LetsLearnEnglish}：{@code instance}（static 单例）、{@code configManager}、
 *       {@code databaseManager}、{@code dailyChallengeManager}、{@code seasonManager}、
 *       {@code socialManager}、{@code checkInManager}；</li>
 *   <li>{@code DatabaseManager}：{@code tablePrefix} / {@code isSQLite} —— 这两个是
 *       本批唯一有<b>可指认的半更新窗口</b>的：写侧在
 *       {@code initialize()} 的 {@code synchronized (connectionLock)} 内，读侧经
 *       {@code getTablePrefix()} / {@code isSQLite()} <b>完全无锁</b>，全仓 36 处
 *       调用点都在「拼 SQL 之前」取它们。{@code /lea reload} 改前缀与并发发币重叠时，
 *       读侧可以拿到不一致的组合（方言已是 MySQL、前缀还是 SQLite 的），拼出一条
 *       两边都不对的 SQL。</li>
 * </ol>
 *
 * <h2>为什么必须是 lint 而不是并发行为测试</h2>
 * <p>数据竞争在压测里是<b>概率性</b>的：跑绿了不能证明没有，跑红了也未必能定位。
 * 而「字段声明上有没有 volatile」是<b>确定性</b>的源码事实，零 flake，且能抓到
 * 未来新加的同名类字段。<b>本类不试图证明可见性正确</b> —— 它只保证「这个已达成
 * 共识的纪律没有被悄悄回退」，这恰恰是 review 里最容易漏的一类改动
 * （删掉一个关键字，编译通过、全部功能测试通过）。
 *
 * <h2>先剥注释（本仓踩过 4 次以上的坑）</h2>
 * <p>这些字段的 javadoc 里<b>大量出现 "volatile" 这个词</b>（正是本次写下的论证）。
 * 若直接在原始源码上匹配「这个字段附近有没有 volatile 字样」，那么把真正的
 * {@code volatile} 关键字删掉后，lint <b>仍然会绿</b>（注释里的词充当了假阳性）。
 * 本类因此先做一遍状态机式的注释/字符串剥离（不是简单的正则去 {@code //}），
 * 并用一条独立的「前提」用例证明剥离真的生效。
 *
 * <h2>锚字段名，不锚行号</h2>
 * <p>全部断言锚在<b>字段名</b>上（正则要求「修饰符 + 类型 + 字段名 + 结束符」出现在
 * 同一行），行号一律不出现：这个仓库的文件一天内被多个并行批次改过几十次，行号锚会
 * 静默失效。反过来，<b>找不到字段也必须红</b>（重命名会让 lint 变成空转 ——
 * 这是本仓 MEMORY 记录的「闸门未触及」形态）。
 */
@DisplayName("SharedFieldVisibilityLint — 跨线程共享字段必须 volatile")
class SharedFieldVisibilityLintTest {

    private static final String ENTRY =
            "src/main/java/com/letslearnenglish/minecraftplugin/LetsLearnEnglish.java";
    private static final String DB =
            "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java";

    /**
     * {@code LetsLearnEnglish} 里必须带 {@code volatile} 的字段。
     *
     * <p>前五个是<b>本次之前就已经做对的</b>（{@code R4-CON-01} 那一批），一并纳入
     * 断言是为了防止「补齐新的、回退了旧的」——那会让问题以不同形状重新出现。
     */
    private static final List<String> ENTRY_FIELDS = List.of(
            // ---- R4-CON-01 既有（回归保护）----
            "playerDataStore", "wordManager", "aiChatManager", "progressManager", "achievementManager",
            // ---- R18 补齐 ----
            "instance", "configManager", "databaseManager",
            "dailyChallengeManager", "seasonManager", "socialManager", "checkInManager"
    );

    /**
     * {@code DatabaseManager} 里必须带 {@code volatile} 的字段。
     *
     * <p>只列这两个：本批的可写范围就是它们，且它们是唯一「锁内写、锁外读」且有
     * 36 处热路径读点的字段。{@code connection} / {@code shuttingDown} 已经是
     * volatile，不需要本 lint 提醒。
     */
    private static final List<String> DB_FIELDS = List.of("tablePrefix", "isSQLite");

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LetsLearnEnglish 的共享字段全部是 volatile")
    void entryPointSharedFieldsAreVolatile() throws IOException {
        assertVolatileFields(ENTRY, ENTRY_FIELDS);
    }

    @Test
    @DisplayName("DatabaseManager 的 tablePrefix / isSQLite 是 volatile")
    void databaseManagerDialectFieldsAreVolatile() throws IOException {
        assertVolatileFields(DB, DB_FIELDS);
    }

    /**
     * 前提检查：注释剥离确实生效。
     *
     * <p>没有这条，上面两条断言就是<b>可能自证为空转</b>的：如果剥离坏掉（例如有人
     * 重写了 {@link #stripComments}），javadoc 里那些 "volatile" 会重新参与匹配。
     * 这条用「只可能出现在注释里」的短语做探针 —— 它们在任何代码位置都不成立。
     */
    @Test
    @DisplayName("前提：注释剥离生效（否则注释里的 volatile 会冒充关键字）")
    void commentStrippingIsEffective() throws IOException {
        // 这两个短语只出现在本批新增的 javadoc 正文里（不在任何字符串字面量中）。
        assertStripped(ENTRY, "为什么是 volatile");
        assertStripped(DB, "为什么是 volatile");
        assertStripped(DB, "刻意不给 getter 加锁");

        // 反向确认：剥离后的源码必须仍然含真实代码（避免「剥成空串」这种
        // 让所有断言都无处可匹配的失败模式）。
        //
        // 刻意<b>不</b>在这里断言 volatile：那会让本用例与「字段可见性」用例
        // 同红，把两个信号混成一个 —— 看到红的先怀疑剥离坏了，而真正的原因是
        // 有人删了 volatile。这里只钉「代码本体还在」。
        String stripped = stripComments(read(ENTRY));
        assertTrue(stripped.contains("public static LetsLearnEnglish getInstance()"),
                "剥离后应当留下真实的方法声明；若这里红了，说明 stripComments 把代码也吃掉了");
    }

    // ------------------------------------------------------------------
    // 断言核心
    // ------------------------------------------------------------------

    /**
     * 断言每个字段都能被找到<b>恰好一次</b>，且修饰符里含 {@code volatile}。
     *
     * <p>三类失败被刻意分开报（否则「找不到」会被误读成「不是 volatile」）：
     * <ol>
     *   <li>找不到 —— 字段被删/改名 → lint 已失去意义，必须红；</li>
     *   <li>找到多次 —— 出现重复声明 → 人必须看一眼；</li>
     *   <li>找到但缺 volatile —— 真正的缺陷。</li>
     * </ol>
     */
    private static void assertVolatileFields(String relPath, List<String> fields) throws IOException {
        String code = stripComments(read(relPath));

        List<String> missing = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        List<String> notVolatile = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();

        for (String field : fields) {
            List<String> declarations = findDeclarations(code, field);
            if (declarations.isEmpty()) {
                missing.add(field);
                continue;
            }
            if (declarations.size() > 1) {
                duplicated.add(field + " × " + declarations.size());
            }
            String decl = declarations.get(0);
            seen.put(field, decl);
            String modifiers = modifierRun(decl);
            if (!containsWord(modifiers, "volatile")) {
                notVolatile.add(field + "  →  " + decl);
            }
        }

        assertTrue(missing.isEmpty(),
                "以下字段在 " + relPath + " 里找不到声明。字段被删/改名后本 lint 会变成空转"
                        + "（本仓 MEMORY：闸门未触及 = 假绿），请同步更新字段清单:\n  "
                        + String.join("\n  ", missing));

        assertTrue(duplicated.isEmpty(),
                "以下字段在本类里出现多次声明，本 lint 只检查第一处，结果不可信:\n  "
                        + String.join("\n  ", duplicated));

        assertTrue(notVolatile.isEmpty(),
                "以下跨线程共享字段缺少 volatile（" + relPath + "）。它们由插件主线程写入、"
                        + "被异步线程（会话结算 / 赛季 sweep / 报表 / PAPI 补读 / 静态音效工具）读取；"
                        + "非 volatile 时异步段理论上可能读到 null 或陈旧句柄。"
                        + "补齐 volatile 即可，<b>不要</b>改成加锁或 double-checked locking:\n  "
                        + String.join("\n  ", notVolatile));

        // 找到的每一处声明都必须真的读到过 —— 防止未来有人把清单写错却因为
        // "missing 为空但 seen 少一项" 而静默通过。
        assertTrue(seen.size() == fields.size(),
                "字段清单与实际找到的声明数不一致：期望 " + fields.size() + "，实得 " + seen.size());
    }

    private static void assertStripped(String relPath, String commentOnlyPhrase) throws IOException {
        String stripped = stripComments(read(relPath));
        assertTrue(!stripped.contains(commentOnlyPhrase),
                "剥离后仍能看到只存在于注释里的短语 \"" + commentOnlyPhrase + "\"（" + relPath
                        + "）。说明 stripComments 没生效（或该短语被写进了字符串字面量）——"
                        + "此时注释里的 \"volatile\" 会冒充关键字，本 lint 会假绿");
    }

    // ------------------------------------------------------------------
    // 源码解析
    // ------------------------------------------------------------------

    /**
     * 找出字段 {@code name} 的全部声明行，返回<b>整行文本</b>（已剥注释、已去首尾空白）。
     *
     * <p>匹配形状（同一行内）：一串修饰符 → 类型 → 字段名 → {@code ;}（或 {@code = 初值 ;}）。
     * 这个形状刻意排除了三类容易误伤的同名行：
     * <ul>
     *   <li>{@code instance = this;}（赋值语句，无修饰符）；</li>
     *   <li>{@code return instance;}（返回语句）；</li>
     *   <li>{@code public boolean isSQLite() {} }（方法声明，名字后是 {@code (} 不是 {@code ;}）。</li>
     * </ul>
     */
    private static List<String> findDeclarations(String code, String name) {
        String modifier = "(?:public|protected|private|static|final|volatile|transient|synchronized)";
        Pattern p = Pattern.compile(
                "(?m)^[ \\t]*(" + modifier + "[ \\t]+)+[\\w$.<>\\[\\]?, \\t]*?[ \\t]*\\b"
                        + Pattern.quote(name) + "\\b[ \\t]*(?:=[^;\\n]*)?[ \\t]*;");
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(code);
        while (m.find()) {
            out.add(m.group().trim().replaceAll("\\s+", " "));
        }
        return out;
    }

    /** 取出一行声明里「类型之前的修饰符串」（到第一个非修饰符 token 为止）。 */
    private static String modifierRun(String declaration) {
        StringBuilder out = new StringBuilder();
        for (String token : declaration.split("\\s+")) {
            if (token.equals("public") || token.equals("protected") || token.equals("private")
                    || token.equals("static") || token.equals("final")
                    || token.equals("volatile") || token.equals("transient")
                    || token.equals("synchronized")) {
                out.append(token).append(' ');
            } else {
                break;
            }
        }
        return out.toString();
    }

    private static boolean containsWord(String haystack, String word) {
        for (String token : haystack.trim().split("\\s+")) {
            if (token.equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static String read(String relPath) {
        Path p = Paths.get(relPath);
        if (!Files.exists(p)) {
            fail("源码文件不存在（工作目录应为项目根）: " + p.toAbsolutePath());
        }
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取失败：" + p + " -> " + e.getMessage());
        }
    }

    /**
     * 剥掉行注释、块注释（含 javadoc）、字符串与文本块字面量的<b>内容</b>，
     * 其余字符（含换行）原样保留。
     *
     * <p>为什么不是 {@code replaceAll("//.*", "")}：那样会把字符串里的
     * {@code "https://..."} 也剥掉半行，并且完全不处理块注释与文本块。
     * 本仓已有四处独立实现的版本（{@code IndexAndPragmaLintsTest} /
     * {@code GuiWiringLintsTest} / {@code ServerToggleLintsTest} 等），
     * 按同一约定本类<b>不共用</b>它们：共用会让一组语法边缘情况同时打瘫多组 lint。
     */
    private static String stripComments(String src) {
        int n = src.length();
        char[] out = src.toCharArray();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);

            // 1) 行注释：跳到行尾，抹成空格（保留换行，行号不错位）
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && src.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            // 2) 块注释 / javadoc：跳到配对的 */（未闭合则到文末）
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(src.charAt(j) == '*' && j + 1 < n && src.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            // 3) 文本块 """...""" ：整段抹掉（含定界符）
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j + 2 < n && !(src.charAt(j) == '"' && src.charAt(j + 1) == '"'
                        && src.charAt(j + 2) == '"')) {
                    if (src.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = Math.min(n, j + 3);
                blank(out, i, j);
                i = j;
                continue;
            }

            // 4) 普通字符串：抹掉内容，留一对 "" 让 token 边界不粘连
            if (c == '"') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '"') {
                    if (src.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = Math.min(n, j + 1);
                blank(out, i + 1, Math.max(i + 1, j - 1));
                i = j;
                continue;
            }

            // 5) 字符字面量：同理（'\\'' 这种转义要吃完三格）
            if (c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '\'') {
                    if (src.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = Math.min(n, j + 1);
                blank(out, i + 1, Math.max(i + 1, j - 1));
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    /** 把 [from, to) 内的非换行字符替换成空格。 */
    private static void blank(char[] out, int from, int to) {
        for (int k = Math.max(0, from); k < Math.min(out.length, to); k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }
}
