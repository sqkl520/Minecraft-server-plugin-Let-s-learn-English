package com.letslearnenglish.minecraftplugin.config;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L13 回归测试：{@link ConfigManager#getGuiMaterial(String, String, Material)} 必须拒绝
 * 空气方块与非法名称，并回落默认值（同时按配置路径只警告一次）。
 *
 * <p><b>为什么需要这个类</b>：{@code AIR} / {@code CAVE_AIR} / {@code VOID_AIR} 都是
 * 合法 {@link Material}，{@code Material.valueOf} 会成功返回 —— 旧实现于是把它们原样
 * 塞进 {@code Inventory#setItem}，效果等于清空该槽：服主配错后得到一个"看不见的按钮"
 * 或彻底消失的按钮，且日志里一个字都没有。这类 bug 不会抛异常、不会让任何现有测试变红，
 * 只能靠断言"返回的 Material 不是空气"来钉住。</p>
 *
 * <p><b>技术前提</b>：本类不接触 Bukkit 服务器运行时 —— {@link Material} 的枚举常量与
 * {@code valueOf} 不依赖注册表，{@link YamlConfiguration} 是纯内存实现，{@link Logger}
 * 直接 mock。{@code mainConfig} 是 {@link ConfigManager} 的私有字段（由
 * {@code loadAllConfigs()} 填充），这里用反射注入真配置对象，避免为了一个 getter 去 mock
 * 整个加载链。刻意<b>不</b>mock {@code Material}：真枚举才能证明空气判定分支真的走通。</p>
 *
 * <p><b>为什么不断言 {@code Material#isAir()}</b>：该实现经 {@code asBlockType()} 触碰
 * {@code Registry}，纯 JVM 下抛 {@code No RegistryAccess implementation found} ——
 * 生产代码也正因此改用显式常量比较（见 {@code ConfigManager#isAirMaterial}）。</p>
 */
class ConfigManagerGuiMaterialTest {

    private static final String GUI = "main-menu";
    private static final String ITEM = "word-learning";

    /**
     * 读 {@code ConfigManager.MESSAGE_CONFIG_VERSION} 的真实值。
     *
     * <p>语言文件里的 {@code config-version} 低于该常量时会触发合并迁移分支，那条分支
     * 自身会产出日志，污染 {@code verify(logger, times(n))} 的计数。测试里写死版本号
     * 等于把「改版本号」变成一次隐形的测试失败，故反射读常量。
     */
    private static int messageConfigVersion() {
        try {
            java.lang.reflect.Field f = ConfigManager.class
                    .getDeclaredField("MESSAGE_CONFIG_VERSION");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法读取 MESSAGE_CONFIG_VERSION 喵", e);
        }
    }

    private static final String PATH = "gui-layout." + GUI + "." + ITEM + ".material";

    private LetsLearnEnglish plugin;
    private Logger logger;
    private FileConfiguration mainConfig;
    private ConfigManager configManager;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);

        mainConfig = new YamlConfiguration();
        configManager = new ConfigManager(plugin);
        injectMainConfig(configManager, mainConfig);
    }

    /** {@code mainConfig} 由 {@code loadAllConfigs()} 填充，私有且无 setter —— 反射注入。 */
    private static void injectMainConfig(ConfigManager cm, FileConfiguration cfg) throws Exception {
        Field field = ConfigManager.class.getDeclaredField("mainConfig");
        field.setAccessible(true);
        field.set(cm, cfg);
    }

    @Test
    @DisplayName("AIR：回车默认材质，并打一条指明完整配置路径的 WARNING")
    void airFallsBackToDefault() {
        mainConfig.set(PATH, "AIR");

        Material result = configManager.getGuiMaterial(GUI, ITEM, Material.BOOK);

        assertEquals(Material.BOOK, result, "AIR 必须回落默认值，否则按钮会变成空槽");
        verify(logger, times(1)).warning(contains(PATH));
    }

    @Test
    @DisplayName("CAVE_AIR：同为空气方块，同样回落")
    void caveAirFallsBackToDefault() {
        mainConfig.set(PATH, "CAVE_AIR");

        assertEquals(Material.BOOK, configManager.getGuiMaterial(GUI, ITEM, Material.BOOK));
        verify(logger, times(1)).warning(contains(PATH));
    }

    @Test
    @DisplayName("VOID_AIR：同为空气方块，同样回落")
    void voidAirFallsBackToDefault() {
        mainConfig.set(PATH, "VOID_AIR");

        assertEquals(Material.BOOK, configManager.getGuiMaterial(GUI, ITEM, Material.BOOK));
        verify(logger, times(1)).warning(contains(PATH));
    }

    @Test
    @DisplayName("空气方块是合法 Material 名：旧实现正是因此把它们原样放行（bug 前提）")
    void airMaterialsAreValidMaterialNames() {
        // 这三条断言钉住"为什么必须显式拦"：valueOf 对它们全部成功，旧实现会直接返回，
        // 于是按钮变成空槽。刻意不调 Material#isAir()：它依赖 Registry，纯 JVM 下会抛错。
        assertEquals(Material.AIR, Material.valueOf("AIR"));
        assertEquals(Material.CAVE_AIR, Material.valueOf("CAVE_AIR"));
        assertEquals(Material.VOID_AIR, Material.valueOf("VOID_AIR"));
    }

    @Test
    @DisplayName("非法名称：回落默认值，不再静默")
    void invalidNameFallsBackAndWarns() {
        mainConfig.set(PATH, "NOT_A_MATERIAL");

        Material result = configManager.getGuiMaterial(GUI, ITEM, Material.BOOK);

        assertEquals(Material.BOOK, result);
        verify(logger, times(1)).warning(contains(PATH));
    }

    @Test
    @DisplayName("合法名称（大小写混写）：值被原样采纳，不告警")
    void validNameIsAcceptedCaseInsensitively() {
        mainConfig.set(PATH, "book");

        Material result = configManager.getGuiMaterial(GUI, ITEM, Material.BOOK);

        assertEquals(Material.BOOK, result);
        verifyNoInteractions(logger);
    }

    @Test
    @DisplayName("键不存在：回落默认值，不产生任何告警（缺省是合法状态）")
    void missingKeyFallsBackSilently() {
        Material result = configManager.getGuiMaterial(GUI, ITEM, Material.DIAMOND);

        assertEquals(Material.DIAMOND, result);
        verifyNoInteractions(logger);
    }

    @Test
    @DisplayName("同一配置键重复调用：只警告一次（GUI 渲染热路径防刷屏）")
    void repeatedLookupWarnsOnlyOnce() {
        mainConfig.set(PATH, "AIR");

        for (int i = 0; i < 20; i++) {
            assertEquals(Material.BOOK, configManager.getGuiMaterial(GUI, ITEM, Material.BOOK));
        }

        verify(logger, times(1)).warning(anyString());
    }

    @Test
    @DisplayName("不同配置键各自警告一次（去重粒度为完整路径，不是全局一次）")
    void differentKeysWarnIndependently() {
        mainConfig.set(PATH, "AIR");
        mainConfig.set("gui-layout." + GUI + ".daily-challenge.material", "NOT_A_MATERIAL");

        configManager.getGuiMaterial(GUI, ITEM, Material.BOOK);
        configManager.getGuiMaterial(GUI, "daily-challenge", Material.WRITABLE_BOOK);

        verify(logger, times(2)).warning(anyString());
        verify(logger, times(1)).warning(contains(PATH));
    }

    @Test
    @DisplayName("reload（loadAllConfigs）后去重集合重置：每个键可再次告警一次")
    void reloadResetsWarningDedup(@TempDir Path tempDir) throws Exception {
        mainConfig.set(PATH, "AIR");
        configManager.getGuiMaterial(GUI, ITEM, Material.BOOK);
        verify(logger, times(1)).warning(anyString());

        // 走真实 loadAllConfigs：它把去重集合清空并按磁盘重新装载。为了让这条链在纯 JVM
        // 下跑通且不产生额外日志，这里落一份"版本已是最新"的最小语言文件（避免触发
        // 资源迁移分支），词库资源缺失则由 mock 的 saveResource 静默兜底。
        Path messagesDir = tempDir.resolve("messages");
        Files.createDirectories(messagesDir);
        // 版本号必须跟 ConfigManager.MESSAGE_CONFIG_VERSION 保持一致：写成旧版本会触发
        // 语言文件合并分支，那条分支自己会打一条迁移 INFO/WARNING，把下面
        // verify(logger, times(2)) 的计数污染成 3。这里直接读运行时常量，改版本号时
        // 不用回来手工同步这个字面量。
        int messageConfigVersion = messageConfigVersion();
        String messageFile = "config-version: " + messageConfigVersion
                + "\ngeneral:\n  language: zh\n";
        Files.writeString(messagesDir.resolve("zh.yml"), messageFile, StandardCharsets.UTF_8);
        Files.writeString(messagesDir.resolve("en.yml"), messageFile, StandardCharsets.UTF_8);

        FileConfiguration reloaded = new YamlConfiguration();
        reloaded.set(PATH, "AIR");
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfig()).thenReturn(reloaded);

        configManager.loadAllConfigs();

        // 若去重集合没被清空，旧条目仍记得 PATH → 这里不会再警告 → 测试变红。
        assertEquals(Material.BOOK, configManager.getGuiMaterial(GUI, ITEM, Material.BOOK));
        verify(logger, times(2)).warning(anyString());
    }
}
