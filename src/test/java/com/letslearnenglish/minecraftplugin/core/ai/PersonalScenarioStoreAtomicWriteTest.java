package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PersonalScenarioStore} 的落盘原子性契约（R-11 第三批）。
 *
 * <h2>这条缺陷是什么</h2>
 * <p>{@code importPersonalScenario} 与 {@code deletePersonalScenario} 原本都以
 * {@code config.save(file)} <b>直接覆写</b>该玩家的 {@code player_scenarios/<uuid>.yml}。
 * 写到一半失败（进程被杀 / 磁盘满）留下半截 YAML，而
 * {@code YamlConfiguration.loadConfiguration} 吞掉解析异常返回空配置 —— 下次启动
 * {@code reloadPersonalScenarios} 读到的就是「这个玩家一条个人场景都没有」。
 * 界面上表现为「我存的场景全没了」，日志里一个字都没有。
 *
 * <h2>失败语义：本批一个字都没改</h2>
 * <p>两个方法原本就<b>没有假成功</b>：保存失败返回 {@code saveFailed=true} 且跳过
 * {@code mine.put}；删除失败返回 {@code saveFailed=true} 且跳过 {@code mine.remove}。
 * 本批只换写入方式，因此下面每组都<b>同时</b>钉「磁盘没变」与「内存没变」——
 * 只钉前者，实现把返回值改成 {@code deleted=true} 时会全绿。
 *
 * <h2>失败怎么构造</h2>
 * <p>把 {@code <uuid>.yml.tmp} 预先造成一个<b>空目录</b>：{@code Files.writeString}
 * 打不开目录，是真实文件系统拒绝（不是 mock 的异常）。同时因为它是<b>空</b>目录，
 * {@code deleteIfExists} 能清掉，于是「{@code .tmp} 不残留」这条断言也可满足。
 */
class PersonalScenarioStoreAtomicWriteTest {

    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path dataDir;

    // ------------------------------------------------------------------

    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("PersonalScenarioStoreAtomicWriteTest." + UUID.randomUUID());
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

    private LetsLearnEnglish newPlugin(Captured logs) {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        when(plugin.getLogger()).thenReturn(logs.logger);
        return plugin;
    }

    /** 该玩家的个人场景文件路径。 */
    private Path scenarioFile() {
        return dataDir.resolve("player_scenarios").resolve(OWNER + ".yml");
    }

    private Path tmpOf(Path target) {
        return Path.of(target.toAbsolutePath() + ".tmp");
    }

    /** 造一个「已有一条场景 x 的合法文件」，返回其字节快照。 */
    private byte[] seedFileWithOneScenario() throws IOException {
        Path file = scenarioFile();
        Files.createDirectories(file.getParent());
        String yml = "scenarios:\n"
                + "  personal:11111111:existing_0a1b2c3d:\n"
                + "    display_name: existing\n"
                + "    display_name_zh: existing\n"
                + "    material: PAPER\n"
                + "    prompt: keep me please\n";
        Files.writeString(file, yml, StandardCharsets.UTF_8);
        return Files.readAllBytes(file);
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Tester");
        return player;
    }

    // ==================================================================

    @Nested
    @DisplayName("组 1 · importPersonalScenario")
    class Import {

        @Test
        @DisplayName("写成功：文件内容正确（原有场景 + 新场景都在），不留 .tmp，内存已更新")
        void successWritesBothScenariosAndUpdatesMemory() throws Exception {
            try (Captured logs = new Captured()) {
                PersonalScenarioStore store = new PersonalScenarioStore(newPlugin(logs));
                seedFileWithOneScenario();

                AIChatManager.ImportResult result =
                        store.importPersonalScenario(player(OWNER), "meeting", "Ask about the agenda");

                assertNotNull(result.key(), "成功时 key 必须非空。实际结果：" + result);
                assertFalse(result.saveFailed(), "落盘成功时不得报 saveFailed。实际日志：" + logs.messages);

                Path file = scenarioFile();
                YamlConfiguration written = new YamlConfiguration();
                written.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                assertTrue(written.isConfigurationSection("scenarios." + result.key()),
                        "新场景必须真的写进文件。实际键："
                                + written.getConfigurationSection("scenarios").getKeys(false));
                assertEquals("Ask about the agenda",
                        written.getString("scenarios." + result.key() + ".prompt"));
                assertEquals("PAPER", written.getString("scenarios." + result.key() + ".material"));
                // 原子写不该顺手把别人的旧场景清掉 —— 那是「成功但覆盖了旧数据」，比失败更糟。
                assertTrue(written.isConfigurationSection(
                                "scenarios.personal:11111111:existing_0a1b2c3d"),
                        "既有场景必须原样保留（原子写退化成「整份重写」时最容易踩的一格）");

                assertFalse(Files.exists(tmpOf(file)), "写成功后不得残留 .tmp");
                assertNotNull(store.getLoadedPersonalScenario(OWNER, result.key()),
                        "写成功时内存副本必须同步更新（mine.put 不得被跳过）");
            }
        }

        @Test
        @DisplayName("写失败：磁盘逐字节不变、不留 .tmp、内存不被更新、返回 saveFailed=true")
        void failureKeepsDiskMemoryAndReportedStateConsistent() throws Exception {
            try (Captured logs = new Captured()) {
                PersonalScenarioStore store = new PersonalScenarioStore(newPlugin(logs));
                byte[] before = seedFileWithOneScenario();
                Path file = scenarioFile();

                // .tmp 被空目录占住 → writeString 抛 AccessDeniedException（真实文件系统拒绝）
                Files.createDirectory(tmpOf(file));

                AIChatManager.ImportResult result =
                        store.importPersonalScenario(player(OWNER), "meeting", "Ask about the agenda");

                assertNull(result.key(), "写盘失败时 key 必须为 null（调用点据此判定不得报成功）");
                assertTrue(result.saveFailed(), "写盘失败必须报 saveFailed=true。实际结果：" + result);

                assertArrayEquals(before, Files.readAllBytes(file),
                        "原子写的核心价值：写失败时目标文件必须逐字节保持原样。\n"
                                + "旧实现（config.save 直写）会把文件覆盖成新内容；真实故障"
                                + "（磁盘满 / 进程被杀）下留下的是半截 YAML，下次启动"
                                + "YamlConfiguration 解析失败 → 该玩家全部个人场景静默消失。"
                                + "实际内容：\n" + Files.readString(file, StandardCharsets.UTF_8));
                assertFalse(Files.exists(tmpOf(file)),
                        "失败路径必须清理 .tmp（残留会在数据目录里越积越多，"
                                + "且下次同路径写入会被它挡住）");

                // 内存侧：失败时绝不能 put —— 否则玩家会看到一条「重启后不存在」的场景，
                // 且再次导入同名场景会被判成 updated（幂等更新）而不是新增。
                assertNull(store.getLoadedPersonalScenario(OWNER, result.key()),
                        "内存不得被更新（实现里 mine.put 必须在落盘成功之后）");
                assertEquals(1, store.getPersonalScenarios(OWNER).size(),
                        "watchdog：内存副本应仍只有磁盘上那一条既有场景。实际："
                                + store.getPersonalScenarios(OWNER).keySet());
            }
        }

        @Test
        @DisplayName("写失败不发放 .bak：损坏文件的备份逻辑与原子写不冲突（两条各管一件事）")
        void corruptFileBackupStillHappensWhenAvailable() throws Exception {
            // 说明性用例：.bak 保护的是「成功但覆盖了损坏文件」，原子写保护的是「失败时目标不变」。
            // 两者正交。这里用一个「有内容但没有 scenarios 段」的文件验证备份路径仍然生效，
            // 且原子写成功之后旧内容仍在 .bak 里。
            try (Captured logs = new Captured()) {
                PersonalScenarioStore store = new PersonalScenarioStore(newPlugin(logs));
                Path file = scenarioFile();
                Files.createDirectories(file.getParent());
                Files.writeString(file, "this_is_not_a_scenarios_section: true\n",
                        StandardCharsets.UTF_8);

                AIChatManager.ImportResult result =
                        store.importPersonalScenario(player(OWNER), "meeting", "prompt");

                assertFalse(result.saveFailed(), "落盘应成功。实际日志：" + logs.messages);
                Path backup = file.resolveSibling(OWNER + ".yml.bak");
                assertTrue(Files.exists(backup),
                        "损坏文件在覆盖前必须留一份 .bak —— 原子写只保证「失败时目标不变」，"
                                + "它不保护「成功覆盖掉了一份损坏但仍可人工抢救的文件」。"
                                + "这条保护应当保留。");
                assertEquals("this_is_not_a_scenarios_section: true\n",
                        Files.readString(backup, StandardCharsets.UTF_8));
            }
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("组 2 · deletePersonalScenario")
    class Delete {

        /** 造一条真实存在于内存 + 磁盘的场景，返回它的 key。 */
        private String seedOneScenario(PersonalScenarioStore store) throws IOException {
            Path file = scenarioFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, "scenarios:\n"
                    + "  personal:11111111:meeting_0a1b2c3d:\n"
                    + "    display_name: meeting\n"
                    + "    display_name_zh: meeting\n"
                    + "    material: PAPER\n"
                    + "    prompt: p\n", StandardCharsets.UTF_8);
            store.reloadPersonalScenarios(OWNER);
            String key = "personal:11111111:meeting_0a1b2c3d";
            assertNotNull(store.getLoadedPersonalScenario(OWNER, key),
                    "前置：场景必须先在内存副本里（否则 delete 会走 notFound 分支）");
            return key;
        }

        @Test
        @DisplayName("删除成功：文件里该键消失，不留 .tmp，内存同步移除")
        void successRemovesKeyFromDiskAndMemory() throws Exception {
            try (Captured logs = new Captured()) {
                PersonalScenarioStore store = new PersonalScenarioStore(newPlugin(logs));
                String key = seedOneScenario(store);

                PersonalScenarioStore.DeleteResult result = store.deletePersonalScenario(OWNER, key);

                assertTrue(result.deleted(), "删除成功必须报 deleted=true。实际结果：" + result);
                Path file = scenarioFile();
                YamlConfiguration written = new YamlConfiguration();
                written.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                assertFalse(written.isConfigurationSection("scenarios." + key),
                        "该键必须从文件里消失");
                assertFalse(Files.exists(tmpOf(file)), "删除成功后不得残留 .tmp");
                assertNull(store.getLoadedPersonalScenario(OWNER, key),
                        "删除成功后内存副本必须同步移除");
            }
        }

        @Test
        @DisplayName("删除失败：磁盘逐字节不变、内存仍保留该场景、返回 saveFailed=true")
        void failureKeepsDiskAndMemoryUntouched() throws Exception {
            try (Captured logs = new Captured()) {
                PersonalScenarioStore store = new PersonalScenarioStore(newPlugin(logs));
                String key = seedOneScenario(store);
                Path file = scenarioFile();
                byte[] before = Files.readAllBytes(file);
                Files.createDirectory(tmpOf(file));

                PersonalScenarioStore.DeleteResult result = store.deletePersonalScenario(OWNER, key);

                assertFalse(result.deleted(), "写盘失败时绝不能报 deleted=true");
                assertTrue(result.saveFailed(), "必须报 saveFailed=true。实际结果：" + result);
                assertFalse(result.notFound(), "notFound 与 saveFailed 互斥");

                assertArrayEquals(before, Files.readAllBytes(file),
                        "删除失败时文件必须逐字节保持原样。\n"
                                + "删除是「set null 后重写整份文件」，直写会让半截 YAML 把这名玩家"
                                + "**其余**场景一起带走 —— 删除只该丢一条，不该丢一档。");
                assertFalse(Files.exists(tmpOf(file)), "删除失败后 .tmp 必须被清理");

                // 内存侧：失败时绝不能 remove —— 否则场景从界面上消失、磁盘上还在，
                // 玩家既看不到也没法重试删除，只能手工改 YAML。
                assertNotNull(store.getLoadedPersonalScenario(OWNER, key),
                        "删除失败时内存副本必须保留该场景（mine.remove 必须在落盘成功之后）");
            }
        }
    }
}
