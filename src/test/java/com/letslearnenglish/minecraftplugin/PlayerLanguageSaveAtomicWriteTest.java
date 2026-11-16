package com.letslearnenglish.minecraftplugin;

import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code LetsLearnEnglish.savePlayerLanguages} 的落盘守卫（R-11 第三批 + R4-EH-01）。
 *
 * <h2>为什么这里不能「直接 new 一个插件」</h2>
 * <p>与 {@code ServerToggleTest} 同款：{@link LetsLearnEnglish} 是 {@code final} 且继承
 * {@code JavaPlugin}，其无参构造在 Paper 1.21.4 会校验 ClassLoader，纯 JVM 里直接抛
 * {@code IllegalStateException: JavaPlugin requires to be created by a valid classloader}。
 * 这里用 {@code mock(...)} + {@code doCallRealMethod()}，只让 {@code onDisable}（进而
 * {@code disableAll} → {@code savePlayerLanguages}）走真实实现；Objenesis 不跑字段
 * 初始化器，所以 {@code playerLanguage} 必须反射补齐（不是产品缺陷，是测试替身的前置）。
 *
 * <h2>本类钉住的三件事</h2>
 * <ol>
 *   <li><b>写成功</b>：内容与内存表一致，不留 {@code .tmp}；</li>
 *   <li><b>写失败</b>：{@code player_lang.yml} 逐字节保持原样（原子写的核心价值），
 *       且不残留 {@code .tmp}；</li>
 *   <li><b>R4-EH-01 早退</b>：{@code !playerLanguagesLoaded && file.exists()} 时必须
 *       <b>一个字节都不写</b>。这条是既有保护（防「没读过就用空表覆盖全服偏好」），
 *       本批改写入方式绝不能把它改坏 —— 而且它必须<b>先于</b>任何文件操作生效。</li>
 * </ol>
 */
class PlayerLanguageSaveAtomicWriteTest {

    @TempDir
    Path dataDir;

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    // ------------------------------------------------------------------

    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("PlayerLanguageSaveAtomicWriteTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(record.getMessage() == null ? "" : record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    private static void injectField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Path langFile() {
        return dataDir.resolve("player_lang.yml");
    }

    private Path tmpOf(Path target) {
        return Path.of(target.toAbsolutePath() + ".tmp");
    }

    /**
     * 造一个「已装配完毕」的插件替身：{@code playerLanguagesLoaded=true}、
     * 内存表由 {@code languages} 填充，{@code dataFolder} 指向 {@link TempDir}。
     *
     * <p>{@code disableAll()} 走真实实现 —— 它在「关闭态」下因 {@code beginDisable()}
     * 返回 false 而整段跳过，这正是这里要的：只留 {@code savePlayerLanguages} 一棵独苗，
     * 其余依赖（Manager）全为 null 也不会被碰到。
     */
    private LetsLearnEnglish loadedPlugin(Captured logs, java.util.Map<UUID, String> languages)
            throws Exception {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(logs.logger);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        // ConfigManager 只在 loadPlayerLanguages 里用到；savePlayerLanguages 不碰它。
        when(plugin.getConfigManager()).thenReturn(mock(ConfigManager.class));
        injectField(plugin, LetsLearnEnglish.class, "playerLanguage", languages);
        injectField(plugin, LetsLearnEnglish.class, "playerLanguagesLoaded", true);
        doCallRealMethod().when(plugin).onDisable();
        doCallRealMethod().when(plugin).disableAll();
        return plugin;
    }

    // ==================================================================

    @Nested
    @DisplayName("组 1 · 写成功")
    class Success {

        @Test
        @DisplayName("内容与内存表一致，且不留 .tmp")
        void writesExactlyOnceWithNoTmpLeftBehind() throws Exception {
            try (Captured logs = new Captured()) {
                java.util.Map<UUID, String> languages = new ConcurrentHashMap<>();
                languages.put(ALICE, "en");
                languages.put(BOB, "zh");
                LetsLearnEnglish plugin = loadedPlugin(logs, languages);

                plugin.onDisable();

                Path file = langFile();
                assertTrue(Files.exists(file), "savePlayerLanguages 必须把语言偏好写回文件");
                FileConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
                assertEquals("en", saved.getString(ALICE.toString()),
                        "player_lang.yml 里丢了已加载的语言偏好");
                assertEquals("zh", saved.getString(BOB.toString()));
                // 逐字节对照：原子写产物必须与「直接 saveToString + UTF-8 写出」一致，
                // 否则「行为不变」这句话不成立。
                YamlConfiguration expected = new YamlConfiguration();
                expected.set(ALICE.toString(), "en");
                expected.set(BOB.toString(), "zh");
                assertEquals(expected.saveToString(),
                        Files.readString(file, StandardCharsets.UTF_8),
                        "原子写产物必须与原实现（config.save）逐字节一致");
                assertFalse(Files.exists(tmpOf(file)), "写成功后不得残留 .tmp");
            }
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("组 2 · 写失败（原子写的核心价值）")
    class AtomicFailure {

        @Test
        @DisplayName("写失败：旧 player_lang.yml 逐字节保持原样，.tmp 不残留，且不抛异常")
        void failureKeepsExistingFileByteForByte() throws Exception {
            String original = ALICE + ": en\n";
            Files.writeString(langFile(), original, StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(langFile());
            // 真实文件系统故障：.tmp 被空目录占住 → writeString 打不开目录
            Files.createDirectory(tmpOf(langFile()));

            try (Captured logs = new Captured()) {
                java.util.Map<UUID, String> languages = new ConcurrentHashMap<>();
                languages.put(ALICE, "zh");    // 与磁盘上的 en 不同：成功覆写会看得出来
                languages.put(BOB, "zh");
                LetsLearnEnglish plugin = loadedPlugin(logs, languages);

                // onDisable 尾部还有 databaseManager.shutdown()（为 null → 跳过），
                // 整段不得因写盘失败而抛异常：停用流程必须跑完。
                plugin.onDisable();

                assertArrayEquals(before, Files.readAllBytes(langFile()),
                        "原子写的核心价值：写失败时旧文件必须逐字节保持原样。\n"
                                + "旧实现（langConfig.save）会把文件覆盖成新内容；真实故障"
                                + "（磁盘满 / 停服时被杀）下留下的是半截 YAML，下次启动"
                                + "loadPlayerLanguages 解析失败 → 全服玩家语言偏好一起回落默认值。"
                                + "实际内容：\n" + Files.readString(langFile(), StandardCharsets.UTF_8));
                assertFalse(Files.exists(tmpOf(langFile())),
                        "失败路径必须清理 .tmp（残留会在数据目录里越积越多）");
                assertTrue(logs.messages.stream().anyMatch(m -> m.contains("player language preferences")),
                        "写失败必须留日志 —— onDisable 路径没有交互方，日志就是全部可做的。"
                                + "实际日志：" + logs.messages);
            }
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("组 3 · R4-EH-01 早退（既有保护，绝不能被本批改坏）")
    class NeverLoadedGuard {

        @Test
        @DisplayName("没读过 + 文件已存在 → 拒绝覆盖，文件逐字节不变，且不留 .tmp")
        void refusesToOverwriteWhenLanguagesWereNeverLoaded() throws Exception {
            String original = ALICE + ": en\n";
            Files.writeString(langFile(), original, StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(langFile());

            try (Captured logs = new Captured()) {
                java.util.Map<UUID, String> languages = new ConcurrentHashMap<>();
                // 刻意空表：这正是「loadAllConfigs() 抛异常 → loadPlayerLanguages 轮不到」
                // 的形态，内存表就是空的。没有这道闸，onDisable 会用空表覆盖全服偏好。
                LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
                when(plugin.getLogger()).thenReturn(logs.logger);
                when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
                when(plugin.getConfigManager()).thenReturn(mock(ConfigManager.class));
                injectField(plugin, LetsLearnEnglish.class, "playerLanguage", languages);
                injectField(plugin, LetsLearnEnglish.class, "playerLanguagesLoaded", false);
                doCallRealMethod().when(plugin).onDisable();
                doCallRealMethod().when(plugin).disableAll();

                plugin.onDisable();

                assertArrayEquals(before, Files.readAllBytes(langFile()),
                        "R4-EH-01：没读过 player_lang.yml 就绝不许写它。\n"
                                + "这条保护防的是「loadAllConfigs() 抛异常 → loadPlayerLanguages()"
                                + " 轮不到 → onDisable 用空表覆盖全服语言偏好」。本批改原子写时"
                                + "把它改坏，后果比半截 YAML 更直接：偏好被清空且完全合法可解析。");
                assertFalse(Files.exists(tmpOf(langFile())),
                        "早退必须发生在任何文件操作之前 —— 连 .tmp 都不该被创建");
                assertTrue(logs.messages.stream().anyMatch(m -> m.contains("refusing to")),
                        "拒绝覆盖必须留日志（否则运维只看到「偏好被重置了」而毫无线索）。"
                                + "实际日志：" + logs.messages);
            }
        }

        @Test
        @DisplayName("没读过但文件不存在（真·首次启动）→ 照常落盘（早退的 exists() 半边）")
        void stillWritesOnGenuineFirstStart() throws Exception {
            try (Captured logs = new Captured()) {
                java.util.Map<UUID, String> languages = new ConcurrentHashMap<>();
                languages.put(ALICE, "en");
                LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
                when(plugin.getLogger()).thenReturn(logs.logger);
                when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
                when(plugin.getConfigManager()).thenReturn(mock(ConfigManager.class));
                injectField(plugin, LetsLearnEnglish.class, "playerLanguage", languages);
                injectField(plugin, LetsLearnEnglish.class, "playerLanguagesLoaded", false);
                doCallRealMethod().when(plugin).onDisable();
                doCallRealMethod().when(plugin).disableAll();

                plugin.onDisable();

                // 条件写成 `!loaded` 而不是 `!loaded && exists()` 时，真·首次启动会被拒绝落盘
                // ——玩家的语言选择在重启后就没了。这条是那个 && 的右半边。
                assertTrue(Files.exists(langFile()),
                        "文件不存在时无数据可毁，必须照常落盘（这是早退条件里 exists() 的作用）");
                assertEquals("en", YamlConfiguration.loadConfiguration(langFile().toFile())
                        .getString(ALICE.toString()));
            }
        }
    }
}
