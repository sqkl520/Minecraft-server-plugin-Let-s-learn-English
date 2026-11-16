package com.letslearnenglish.minecraftplugin.gui.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 词库版本升级机制的<b>源码级 lint</b>。
 *
 * <h2>为什么必须是 lint</h2>
 * {@code ConfigManager.upgradeWordBanksIfNeeded()} 要真实 {@code JavaPlugin}、
 * 真实数据目录、真实 {@code saveResource}（走 Bukkit jar 内资源解析）才能跑起来，
 * 纯 JVM 里无法执行。而它保护的是一个**纯粹部署期才暴露**的缺陷：
 *
 * <p>{@code saveResourceIfNotExists} 只在文件<b>缺失</b>时释放资源。任何已经跑过一次
 * 插件的服务器，数据目录里都有旧的 {@code words/*.yml}，于是升级插件后
 * {@code loadAllWordBanks()} 读的仍是旧文件 —— 「扩充词库」这类内容更新对**所有存量服主
 * 完全不可见**，日志里没有任何提示，玩家感知到的就是「说好的新词呢」。开发机上
 * 因为经常删档重装，这个问题<b>永远不会被发现</b>。
 *
 * <p>因此这组 lint 断言的是「机制存在且顺序正确」：
 * <ol>
 *   <li>L1 存在 {@code WORD_BANK_VERSION} 常量，且 {@code upgradeWordBanksIfNeeded} 真的用它比较；</li>
 *   <li>L2 {@code upgradeWordBanksIfNeeded()} 在 {@code loadAllConfigs} 里被调用，
 *       且排在三个 {@code saveResourceIfNotExists("words/…")} <b>之后</b>（先补缺失再升级）；</li>
 *   <li>L3 替换前必须先备份（备份失败则<b>不替换</b>，否则会静默冲掉服主自己改过的词库）；</li>
 *   <li>L4 替换用 {@code saveResource(path, true)}（true = 覆盖），用 false 等于什么都没做；</li>
 *   <li>L5 替换成功后写版本文件（否则每次启动都重复升级）。</li>
 * </ol>
 *
 * <p><b>与其它 Lint 类零耦合</b>：{@code stripComments} / {@code sourceOf} 在此独立实现，
 * 理由同 {@code ServerToggleLintsTest} —— 共用工具会让多组 lint 因一处语法边缘同时失效。
 */
@DisplayName("WordBankUpgradeLintsTest — 词库版本升级机制 lint")
class WordBankUpgradeLintsTest {

    private static final String CONFIG_MANAGER =
            "src/main/java/com/letslearnenglish/minecraftplugin/config/ConfigManager.java";

    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        boolean inLine = false, inBlock = false, inStr = false, inText = false;
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (inLine) { if (c == '\n') { inLine = false; out.append(c); } i++; continue; }
            if (inBlock) {
                if (c == '*' && next == '/') { inBlock = false; i += 2; continue; }
                if (c == '\n') out.append(c);
                i++; continue;
            }
            if (inText) {
                if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') { inText = false; i += 3; continue; }
                out.append(c); i++; continue;
            }
            if (inStr) {
                if (c == '\\' && i + 1 < n) { out.append(c).append(next); i += 2; continue; }
                if (c == '"') inStr = false;
                out.append(c); i++; continue;
            }
            if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') { inText = true; i += 3; continue; }
            if (c == '"') { inStr = true; out.append(c); i++; continue; }
            if (c == '/' && next == '/') { inLine = true; i += 2; continue; }
            if (c == '/' && next == '*') { inBlock = true; i += 2; continue; }
            out.append(c); i++;
        }
        return out.toString();
    }

    private static String code() {
        Path p = Paths.get(CONFIG_MANAGER);
        if (!Files.exists(p)) fail("源码文件不存在：" + p.toAbsolutePath());
        try {
            return stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return fail("读取失败：" + e.getMessage());
        }
    }

    @Nested
    @DisplayName("L1 版本常量存在且参与比较")
    class VersionConstant {

        @Test
        @DisplayName("存在 WORD_BANK_VERSION 且 upgradeWordBanksIfNeeded 用它比较")
        void versionConstantUsed() {
            String c = code();
            assertTrue(c.contains("WORD_BANK_VERSION"),
                    "缺少 WORD_BANK_VERSION 常量 —— 没有版本号就无法判断数据目录里的词库是不是旧的，"
                            + "升级后将永远走不到替换分支");
            assertTrue(c.contains("installed >= WORD_BANK_VERSION"),
                    "升级方法没有做「已装版本 >= 内置版本则跳过」的比较 —— 缺了这个守卫，"
                            + "每次启动都会覆盖服主自己改过的词库");
        }
    }

    @Nested
    @DisplayName("L2 调用点与顺序")
    class CallSiteOrder {

        @Test
        @DisplayName("upgradeWordBanksIfNeeded() 被调用")
        void called() {
            String c = code();
            // 不能只 `contains("upgradeWordBanksIfNeeded()")` —— **方法定义**里也有这个字符串，
            // 于是"删掉调用"的变异会全绿（实测踩过）。这里只数调用点：
            // 出现次数必须 >= 2（1 次定义 + 1 次调用），且必须存在一处不以
            // `private void` / `public void` 开头的出现。
            int total = 0;
            boolean hasCall = false;
            int idx = 0;
            String needle = "upgradeWordBanksIfNeeded()";
            while ((idx = c.indexOf(needle, idx)) >= 0) {
                total++;
                // 往前看 30 字符，判断这是不是方法声明
                int from = Math.max(0, idx - 30);
                String prefix = c.substring(from, idx);
                if (!prefix.contains("void ") && !prefix.contains("protected ")) {
                    hasCall = true;
                }
                idx += needle.length();
            }
            assertTrue(total >= 2,
                    "upgradeWordBanksIfNeeded() 只出现 " + total + " 次（应 >= 2：1 次定义 + 1 次调用）——"
                            + "方法写得再对也不会执行。这是本仓库反复出现的"
                            + "『可编译、能启动、只是少功能』型缺陷。");
            assertTrue(hasCall,
                    "upgradeWordBanksIfNeeded() 只有定义、没有调用点 —— 升级逻辑永远不会跑，"
                            + "存量服务器永远拿不到新词库");
        }

        @Test
        @DisplayName("调用排在三个 saveResourceIfNotExists(words/...) 之后")
        void afterResourceRelease() {
            String c = code();
            int lastSave = Math.max(
                    Math.max(c.indexOf("saveResourceIfNotExists(\"words/beginner.yml\")"),
                            c.indexOf("saveResourceIfNotExists(\"words/intermediate.yml\")")),
                    c.indexOf("saveResourceIfNotExists(\"words/advanced.yml\")"));
            int upgrade = c.indexOf("upgradeWordBanksIfNeeded()");
            assertTrue(lastSave > 0, "找不到 words 的 saveResourceIfNotExists 调用");
            assertTrue(upgrade > 0, "找不到 upgradeWordBanksIfNeeded() 调用");
            assertTrue(upgrade > lastSave,
                    "upgradeWordBanksIfNeeded() 必须排在 saveResourceIfNotExists 之后："
                            + "先补齐缺失文件，再判断要不要升级；顺序反了会让「首次安装」也被当成"
                            + "「旧版本升级」，从而多做一次无谓的备份与覆盖");
        }
    }

    @Nested
    @DisplayName("L3 备份先于替换且失败即中止")
    class BackupBeforeReplace {

        /** 升级方法的固定锚点；方法体一律经此提取，禁止对整文件做子串搜索。 */
        private static final String UPGRADE_SIG = "private void upgradeWordBanksIfNeeded()";

        /** 替换动作的字面量（true = 覆盖）。 */
        private static final String REPLACE_CALL =
                "plugin.saveResource(\"words/\" + tier + \".yml\", true)";

        /**
         * 取 {@code upgradeWordBanksIfNeeded()} 的方法体（花括号配对，跳过字符串字面量）。
         *
         * <p><b>为什么必须收窄</b>：本类原实现直接在整份 {@code ConfigManager.java} 上
         * {@code contains("Files.copy(")} —— 而文件里<b>另一处</b>同形代码（配置合并前的
         * 单槽 {@code .bak} 快照，消息里的 "merge-upgrade"）也含这四个词。实测：把备份
         * 循环里的 {@code Files.copy(disk.toPath(), new File(backupDir, ...))} 两行整段删掉，
         * 该断言<b>依然全绿</b>（8/8 通过）—— 备份目录建了但不往里复制，升级把服主改过的
         * 词库硬覆盖，且没有任何回滚点。收窄到方法体后，锚点只可能由被检方法自己满足。
         */
        private String upgradeBody() {
            String c = code();
            int sig = c.indexOf(UPGRADE_SIG);
            assertTrue(sig >= 0,
                    "ConfigManager.java 里找不到方法签名「" + UPGRADE_SIG + "」喵 —— "
                            + "升级逻辑被改名/搬走了，请把本 lint 的锚点同步到新位置"
                            + "（否则门禁会静默失效，而不是变红）");
            int open = c.indexOf('{', sig + UPGRADE_SIG.length());
            assertTrue(open > 0, "「" + UPGRADE_SIG + "」之后找不到左花括号喵");
            int close = matchingBrace(c, open);
            assertTrue(close > open,
                    "「" + UPGRADE_SIG + "」的花括号不配对喵 —— 源码可能被截断");
            return c.substring(open, close + 1);
        }

        @Test
        @DisplayName("存在备份目录构造与文件复制（且都在升级方法体内）")
        void backupExists() {
            String body = upgradeBody();
            assertTrue(body.contains("words-backup-v"),
                    "升级方法体里没有备份目录（words-backup-v…）—— 直接覆盖会静默冲掉"
                            + "服主自己编辑过的词库。词库是官方支持手工编辑的资产"
                            + "（/lea exportwordbank 是既有功能）");
            assertTrue(body.contains("Files.copy("),
                    "升级方法体里没有把旧词库复制到备份目录 —— 只建目录不复制，回滚时无可恢复。\n"
                            + "注意本断言只在 upgradeWordBanksIfNeeded() 方法体内搜索："
                            + "ConfigManager 里另有一处配置合并前的 .bak 快照也含 `Files.copy(`，"
                            + "拿全文件搜会被它兜住而假绿（实测删掉备份循环里的那次复制，断言仍绿）。\n"
                            + "方法体原文：\n" + body);
        }

        /**
         * 备份失败必须中止<b>替换</b> —— 不只是「置一个 flag」。
         *
         * <p>原实现的两处漏（都是实测确认过的假绿）：
         * <ol>
         *   <li>只断言窗口里有 {@code backupFailed = true}。把替换
         *       {@code plugin.saveResource(..., true)} 整个挪到 {@code if (!backupFailed)}
         *       块<b>外面</b>（还原 LG-02 的原始缺陷），flag 照置、断言照绿 —— 备份失败后
         *       仍然硬覆盖，服主改动永久丢失。实测该变异 8/8 全绿。</li>
         *   <li>「双向取更近的 catch」把窗口放宽到 500 字符。窗口是<b>偏移区间</b>不是
         *       「守卫块」，只要附近有任何 {@code backupFailed = true} 命中即可，
         *       与替换是否受守卫无关。</li>
         * </ol>
         *
         * <p>现在改为<b>括号配对求守卫块区间</b>，并要求替换调用<b>恰好位于块内、且方法体
         * 内只有这一处</b>：把调用挪出去会同时踩到「块内不含」与「块外那一处」两条。
         */
        @Test
        @DisplayName("备份失败时不得继续替换（替换调用必须在 if (!backupFailed) 块内且唯一）")
        void backupFailureAbortsReplace() {
            String body = upgradeBody();

            int guard = body.indexOf("if (!backupFailed)");
            assertTrue(guard >= 0,
                    "升级方法体里没有 `if (!backupFailed)` 守卫 —— 备份失败仍会覆盖，"
                            + "服主改过的词库彻底丢失且无法回滚。方法体原文：\n" + body);

            int guardOpen = body.indexOf('{', guard + "if (!backupFailed)".length());
            assertTrue(guardOpen > guard,
                    "`if (!backupFailed)` 之后找不到左花括号喵 —— 守卫没有块，无法判定替换是否受它保护");
            int guardClose = matchingBrace(body, guardOpen);
            assertTrue(guardClose > guardOpen,
                    "`if (!backupFailed)` 的花括号不配对喵 —— 源码可能被截断");
            String guarded = body.substring(guardOpen, guardClose + 1);

            // 1) 备份失败必须置 flag 并中止当前档位（continue / return / throw 之一）。
            // 取守卫块本身（不是"守卫 + 500 字符窗口"）：窗口会把块外的同名赋值一起算进来，
            // 与「替换是否受守卫保护」无关 —— 那正是本条 lint 原先假绿的成因。
            String tail = guarded;
            assertTrue(tail.contains("backupFailed = true"),
                    "守卫块里没有把 backupFailed 置 true —— 备份失败后其余档位仍会被覆盖。\n"
                            + "守卫块原文：\n" + guarded);

            // 2) 替换调用必须落在守卫块内。
            assertTrue(guarded.contains(REPLACE_CALL),
                    "替换调用 `" + REPLACE_CALL + "` 不在 `if (!backupFailed)` 块内喵。\n"
                            + "这是 LG-02 的原始缺陷形状：守卫只包住备份、替换放在块外，"
                            + "于是备份失败后<b>仍然硬覆盖</b>词库，没有回滚点。\n"
                            + "守卫块原文：\n" + guarded);

            // 3) 方法体里替换调用只能有这一处 —— 否则块内那份可以是摆设、块外那份才是真用的。
            int total = countOccurrences(body, REPLACE_CALL);
            assertEquals(1, total,
                    "升级方法体里 `" + REPLACE_CALL + "` 出现了 " + total + " 次，期望恰好 1 次喵。\n"
                            + "两处的典型形态是「守卫块内摆一次、块外再真正调一次」—— "
                            + "纯文本无法判断哪一次被执行，但正确实现没有取两次的理由。\n"
                            + "方法体原文：\n" + body);
        }

        /** 从 {@code leftBrace} 起做括号配对（跳过字符串/字符字面量），返回匹配的右花括号下标。 */
        private int matchingBrace(String s, int leftBrace) {
            int depth = 0;
            int i = leftBrace;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '"' || ch == '\'') {
                    char quote = ch;
                    i++;
                    while (i < s.length()) {
                        char d = s.charAt(i);
                        if (d == '\\') {
                            i += 2;
                            continue;
                        }
                        i++;
                        if (d == quote) {
                            break;
                        }
                    }
                    continue;
                }
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    if (--depth == 0) {
                        return i;
                    }
                }
                i++;
            }
            return -1;
        }

        private int countOccurrences(String haystack, String needle) {
            int n = 0;
            int from = 0;
            while ((from = haystack.indexOf(needle, from)) >= 0) {
                n++;
                from += needle.length();
            }
            return n;
        }
    }

    @Nested
    @DisplayName("L4/L5 覆盖写与版本落盘")
    class ReplaceAndRecord {

        @Test
        @DisplayName("用 saveResource(path, true) 覆盖")
        void replaceUsesOverwriteFlag() {
            String c = code();
            assertTrue(c.contains("plugin.saveResource(\"words/\" + tier + \".yml\", true)"),
                    "替换必须用 saveResource(path, true)（true = 覆盖）。传 false 时 Bukkit 在"
                            + "文件已存在时直接返回、什么都不做 —— 整个升级机制变成空转，"
                            + "且没有任何报错");
            assertFalse(c.contains("plugin.saveResource(\"words/\" + tier + \".yml\", false)"),
                    "替换分支用了 false，等于没升级");
        }

        @Test
        @DisplayName("替换成功后写 words-version.txt")
        void writesVersionFile() {
            String c = code();
            assertTrue(c.contains("words-version.txt"),
                    "没有版本记录文件 —— 每次启动都会重复「备份 + 覆盖」");
            assertTrue(c.contains("Files.writeString(versionFile.toPath()"),
                    "没有把新版本号写回 words-version.txt");
        }

        @Test
        @DisplayName("版本文件读取失败按 0（旧版）处理而非抛异常")
        void versionReadFailureDegrades() {
            String c = code();
            int read = c.indexOf("Files.readString(versionFile.toPath()");
            assertTrue(read > 0, "找不到读取 words-version.txt 的调用");
            int catchIdx = c.indexOf("catch (IOException | NumberFormatException", read);
            assertTrue(catchIdx > 0 && catchIdx - read < 600,
                    "读版本文件应同时 catch IOException 与 NumberFormatException 并降级为 0："
                            + "文件被手工改坏时不该让插件起不来");
        }
    }
}
