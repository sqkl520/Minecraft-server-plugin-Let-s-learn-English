package com.letslearnenglish.minecraftplugin.util;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * 版本适配器：把服务端版本差异集中到这一个类里，其余代码只调这里的方法。
 *
 * <h2>支持范围</h2>
 * <b>Minecraft 1.21.4 – 1.21.11（Paper / Spigot / Purpur 等 Bukkit 系服务端）。</b>
 *
 * <p>这个范围由两件事共同决定，二者缺一不可：
 * <ol>
 *   <li><b>编译用的 paper-api 版本 = 下限</b>。本项目用 {@code 1.21.4-R0.1-SNAPSHOT} 编译，
 *       因此只引用 1.21.4 就存在的 API —— 更低版本（1.21.0–1.21.3）可能缺方法，
 *       不在支持范围内；</li>
 *   <li><b>Java 字节码版本 = 上限</b>。项目以 Java 21 为目标，而 Paper 从 26.x 起
 *       要求 Java 25（class 文件 major 69）。所以 26.x 及其后的版本<b>不是 API 不兼容，
 *       而是 JVM 根本读不了本项目产出的 class</b>，不在支持范围内。</li>
 * </ol>
 *
 * <p>1.21.4–1.21.11 之间之所以能「一次编译、全线运行」，是因为 Bukkit API 只做加法：
 * 用低版本编译出的类，在高版本服务端上引用的每个类/方法都仍然存在（实测 1.21.11
 * 编译零弃用警告、全部测试通过）。
 */
public class VersionAdapter {

    /** 支持的最低 MC 次版本号（1.21.4 → 4）。低于它可能缺 API。 */
    private static final int MIN_SUPPORTED_MINOR = 4;
    /** 支持的最高 MC 次版本号（1.21.11 → 11）。再往上属于 26.x，需 Java 25。 */
    private static final int MAX_SUPPORTED_MINOR = 11;

    private final LetsLearnEnglish plugin;
    private final String serverVersion;
    /** 解析后的 MC 次版本号（"1.21.11" → 11）；无法解析时为 -1。 */
    private final int mcMinor;
    /** 是否属于 26.x 及以后的年份版本（那类版本要求 Java 25，本插件跑不了）。 */
    private final boolean yearBased;

    public VersionAdapter(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.serverVersion = plugin.getServer().getMinecraftVersion();

        int[] parsed = parseServerVersion(this.serverVersion);
        if (parsed[0] < 0 && parsed[1] == 0) {
            plugin.getLogger().warning("Failed to parse server version: " + serverVersion
                    + "; version compatibility will be reported as unknown.");
        }
        this.mcMinor = parsed[0];
        this.yearBased = parsed[1] == 1;
    }

    /**
     * 解析 Paper 的版本串 —— <b>纯函数，不碰 Bukkit</b>，因此可以被单测直接钉住。
     *
     * <p>必须处理两种形状：
     * <ul>
     *   <li>{@code "1.21.11"} —— 传统三段式，MC 次版本在<b>第二段</b>；</li>
     *   <li>{@code "26.2"} —— 年份式（2026 年起），次版本在<b>第一段</b>且只有两段。</li>
     * </ul>
     *
     * <p>旧实现无条件取 {@code parts[1]}，于是 {@code "26.2"} 被解析成 major=2，
     * 与 {@code 18..21} 比较后判成「不支持」——而 26.x 占全部服务器约 45%。
     * 这个 bug 不抛异常、不影响功能，只是<b>显示错</b>，所以必须有单元测试直接钉住。
     *
     * @return {@code {mcMinor, yearBasedFlag}}；mcMinor 为 -1 表示无法解析或不在 1.21 线上
     */
    static int[] parseServerVersion(String version) {
        int parsedMinor = -1;
        boolean yearBased = false;
        try {
            // 用 '.' 字符类而不是 "\." 转义 —— 两种写法等价，但字符类少一层转义、
            // 不会被各种工具链的转义规则吃掉反斜杠（本轮就被坑过一次）。
            String[] parts = version.split("[.]");
            int first = parts.length > 0
                    ? Integer.parseInt(parts[0].replaceAll("[^0-9].*$", "")) : -1;

            if (first >= 26) {
                yearBased = true;
                parsedMinor = parts.length > 1
                        ? Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")) : 0;
            } else if (first == 1 && parts.length > 2) {
                int mcMajor = Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")); 
                int mcPatch = Integer.parseInt(parts[2].replaceAll("[^0-9].*$", "")); 
                parsedMinor = mcMajor == 21 ? mcPatch : -1;
            }
        } catch (NumberFormatException e) {
            parsedMinor = -1;
        }
        return new int[] {parsedMinor, yearBased ? 1 : 0};
    }

    /**
     * Play a sound to a player (cross-version compatible).
     */
    public void playSound(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /**
     * Send an action bar message (cross-version compatible).
     *
     * Paper 1.21.4 deprecated {@code Player.sendActionBar(String)} — this routes
     * through the Adventure Component API instead (javap-verified available via
     * paper-api's adventure-text-serializer-legacy compile dependency).
     * Caller is responsible for translating '&amp;' color codes first
     * ({@code ChatColor.translateAlternateColorCodes('&', text)}).
     */
    public void sendActionBar(Player player, String legacyText) {
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy
                .LegacyComponentSerializer.legacySection().deserialize(legacyText));
    }

    /**
     * Get the full version string.
     */
    public String getServerVersion() {
        return serverVersion;
    }

    /**
     * 当前服务端是否在**本产物的支持范围内**。
     *
     * <p>本项目发布两个包，支持范围不同：
     * <ul>
     *   <li>普通包（paper-api 1.21.4 编译，Java 21）→ 1.21.4 – 1.21.11；</li>
     *   <li>{@code -mc26} 包（paper-api 26.3 编译，Java 25）→ 26.1+。</li>
     * </ul>
     * 本方法只能判断「服务端是不是 26.x」，判断不了「装的是哪个包」——
     * 那由 {@code plugin.yml} 的 {@code api-version} 在加载期就拦掉了。
     * 所以语义是：**26.x 上返回 false，提示服主改用 {@code -mc26} 包**。
     *
     * <p><b>只影响 {@code /lea info} 的一行显示，不参与任何功能门禁。</b>
     * 刻意如此：版本判定写错时宁可显示错，也不能让插件拒绝加载。
     */
    public boolean isVersionSupported() {
        if (yearBased) {
            return false;   // 26.x 需 Java 25，应改用 -mc26 包
        }
        return mcMinor >= MIN_SUPPORTED_MINOR && mcMinor <= MAX_SUPPORTED_MINOR;
    }

    /**
     * 面向玩家的兼容性描述。区分「太旧」「26.x 该换包」「未验证」三态 ——
     * 只显示 true/false 会让 26.x 的服主以为自己装错了。
     */
    public String describeCompatibility() {
        if (yearBased) {
            return "26.x detected (" + serverVersion + "). On Paper 26.1+ use the -mc26 build "
                    + "(Java 25); the standard build targets Java 21 and covers 1.21.4-1.21.11";
        }
        if (mcMinor < 0) {
            return "unknown (could not parse " + serverVersion + "; supported: 1.21.4-1.21.11)";
        }
        if (mcMinor < MIN_SUPPORTED_MINOR) {
            return "NOT supported: " + serverVersion
                    + " is older than the compile target (supported: 1.21.4-1.21.11)";
        }
        if (mcMinor > MAX_SUPPORTED_MINOR) {
            return "untested: " + serverVersion + " (verified: 1.21.4-1.21.11)";
        }
        return "supported (1.21.4-1.21.11)";
    }
}