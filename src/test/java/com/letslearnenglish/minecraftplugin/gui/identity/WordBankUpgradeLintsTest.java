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

        @Test
        @DisplayName("存在备份目录构造与文件复制")
        void backupExists() {
            String c = code();
            assertTrue(c.contains("words-backup-v"),
                    "没有备份目录（words-backup-v…）—— 直接覆盖会静默冲掉服主自己编辑过的词库。"
                            + "词库是官方支持手工编辑的资产（/lea exportwordbank 是既有功能）");
            assertTrue(c.contains("Files.copy("),
                    "没有把旧词库复制到备份目录 —— 只建目录不复制，回滚时无可恢复");
        }

        @Test
        @DisplayName("备份失败时不得继续替换")
        void backupFailureAbortsReplace() {
            String c = code();
            // 以 `if (!backupFailed)` 为锚点反推：它的 catch 在它**之前**。
            // 不能从 Files.copy 往后找第一个 catch —— 文件里另有配置合并的
            // catch (IOException e)（消息里的 "merge-upgrade"），会抓错。
            int guard = c.indexOf("if (!backupFailed)");
            assertTrue(guard > 0, "没有 `if (!backupFailed)` 守卫 —— 备份失败仍会覆盖");

            // 备份的 try/catch 与守卫的相对位置取决于写法（守卫可能在循环头、catch 在内层），
            // 故双向找最近的 catch，取距离更近的那个。
            int before = c.lastIndexOf("catch (IOException e)", guard);
            int after = c.indexOf("catch (IOException e)", guard);
            int catchIdx = -1;
            if (before > 0 && after > 0) catchIdx = (guard - before) <= (after - guard) ? before : after;
            else if (before > 0) catchIdx = before;
            else catchIdx = after;
            assertTrue(catchIdx > 0, "找不到备份用的 catch (IOException)");

            String window = c.substring(Math.min(catchIdx, guard), Math.min(c.length(), Math.max(catchIdx, guard) + 500));
            assertTrue(window.contains("backupFailed = true"),
                    "备份失败时没有置 backupFailed —— 会在没有回滚点的情况下继续覆盖，"
                            + "服主的改动永久丢失。窗口原文：\n" + window);
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
