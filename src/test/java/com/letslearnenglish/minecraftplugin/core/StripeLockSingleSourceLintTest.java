package com.letslearnenglish.minecraftplugin.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R15（wave3 后继审计 · 并发专场）：<b>同一个 stripe 锁数组不得有第二份</b>。
 *
 * <h2>被修的缺陷</h2>
 * <p>{@code getClaimStripeLock} 是按玩家 UUID 哈希选一个 monitor 的分片锁。
 * 它的<b>有效性完全依赖「所有竞争方拿到同一个 monitor」</b>这个前提。
 *
 * <p>而改动前有两份逐字相同的实现，各自持有自己的 {@code new Object[32]}：
 * <ul>
 *   <li>{@code DailyChallengeManager}：命令/其它路径的挑战领奖；</li>
 *   <li>{@code MiscGUIListener}：GUI 点击路径的挑战领奖。</li>
 * </ul>
 * 两个不同的数组 = 两把不同的锁 → 两处的 {@code synchronized} <b>互不排斥</b>。
 * R-02 的注释写着「两次快速点击同一按钮绝不会交错出双发」，但那只在<b>同一类内部</b>
 * 成立；跨类并发时 {@code check → deposit → mark} 仍可交错，双发保护形同虚设。
 *
 * <h2>为什么用源码 lint 而不是并发行为测试</h2>
 * <p>要真复现双发，得让两个不同的入口线程在同一玩家上交错执行
 * 「读状态 → 发钱 → 标记」，并精确控制调度点。这种测试必然计时敏感、在 CI 上不稳定，
 * 而且<b>它失败时也说不清是锁错了还是时序没凑巧</b>。
 *
 * <p>相比之下，「锁数组只有一份」是<b>源码形状</b>问题，静态检查才是对的层次：
 * 它 100% 确定、零 flake，而且能抓到未来新增的第三个入口。
 */
class StripeLockSingleSourceLintTest {

    private static final Path SRC_ROOT =
            Path.of("src/main/java/com/letslearnenglish/minecraftplugin");

    @Test
    @DisplayName("全仓只有一处 new Object[N] 作为 claimStripeLocks（锁数组单一真相源）")
    void stripeLockArrayDeclaredOnce() throws IOException {
        assertTrue(Files.isDirectory(SRC_ROOT),
                "必须能读到源码树（工作目录应为项目根）: " + SRC_ROOT.toAbsolutePath());

        List<String> declarations = new ArrayList<>();
        // 匹配 `new Object[32]` 这类锁数组初始化，以及配套的循环填充
        Pattern arrayInit = Pattern.compile("claimStripeLocks\\s*=\\s*new\\s+Object\\s*\\[");

        for (Path file : javaSources()) {
            String text = Files.readString(file);
            Matcher m = arrayInit.matcher(text);
            while (m.find()) {
                declarations.add(file.getFileName() + " @offset " + m.start());
            }
        }

        assertTrue(declarations.size() == 1,
                "claimStripeLocks 数组必须只被 new 一次（当前 " + declarations.size() + " 次）。"
                        + "两个数组 = 两把锁 = 跨入口的 synchronized 互不排斥，双发保护失效:\n  "
                        + String.join("\n  ", declarations));
    }

    /**
     * 第二方向：不得有任何类<b>自己</b>再建一份 stripe 锁循环填充。
     *
     * <p>第一方向抓 `= new Object[N]`；这一方向抓「数组是别人给的，但自己循环填充」
     * 这种变形（那样会把共享数组里的对象全部替换掉，等于又造了一份）。
     */
    @Test
    @DisplayName("不得再有类自行循环填充 claimStripeLocks")
    void noSecondFillLoop() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern fillLoop = Pattern.compile(
                "for\\s*\\([^)]*claimStripeLocks\\.length[^)]*\\)\\s*\\{[^}]*claimStripeLocks\\[");

        for (Path file : javaSources()) {
            String text = Files.readString(file);
            if (fillLoop.matcher(text).find()) {
                offenders.add(file.getFileName().toString());
            }
        }

        // 只允许 DailyChallengeManager 一处（它是锁数组的所有者）
        assertTrue(offenders.size() <= 1,
                "只允许锁数组的所有者自己填充一次；其它类填充会覆盖共享数组内容:\n  "
                        + String.join("\n  ", offenders));
        if (offenders.size() == 1) {
            assertTrue(offenders.get(0).equals("DailyChallengeManager.java"),
                    "填充者必须是 DailyChallengeManager（锁的所有者），实际是 " + offenders.get(0));
        }
    }

    /**
     * 第三方向：{@code MiscGUIListener} 必须<b>委托</b>而不是自建。
     */
    @Test
    @DisplayName("MiscGUIListener 的 nameList 必须来自 DailyChallengeManager")
    void miscListenerDelegates() throws IOException {
        Path f = SRC_ROOT.resolve("listener/MiscGUIListener.java");
        assertTrue(Files.exists(f), "应能找到 MiscGUIListener: " + f.toAbsolutePath());
        String text = Files.readString(f);

        assertTrue(text.contains("getDailyChallengeManager().getClaimStripeLocks()"),
                "MiscGUIListener 必须向 DailyChallengeManager 借用同一份锁数组");
        assertFalse(text.contains("claimStripeLocks = new Object["),
                "MiscGUIListener 不得自建第二个锁数组（那正是被修的缺陷）");
    }

    private static List<Path> javaSources() throws IOException {
        try (var walk = Files.walk(SRC_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }
}
