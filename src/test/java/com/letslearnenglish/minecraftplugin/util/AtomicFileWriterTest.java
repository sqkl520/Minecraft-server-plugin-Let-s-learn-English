package com.letslearnenglish.minecraftplugin.util;

import org.junit.jupiter.api.DisplayName;
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
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AtomicFileWriter} 的落盘契约。
 *
 * <h2>为什么这个类必须有自己的测试（而不是只测三个调用点）</h2>
 * <p>它是三处落点（{@code PersonalScenarioStore} 保存/删除、{@code savePlayerLanguages}）
 * 与 {@code ConfigManager.saveConfig} / {@code migrateMessageConfig} <b>共用的唯一一份</b>
 * 原子写实现。调用点测试只能证明「调用了它」，证明不了「它在失败时真的没动目标文件」——
 * 而那正是整个改动的全部价值。这里的每条用例都直接对着文件系统断言。
 *
 * <p><b>失败怎么构造</b>：把 {@code <target>.tmp} 预先造成一个<b>空目录</b>。
 * {@code Files.writeString} 打不开目录（Windows 上 {@code AccessDeniedException}），
 * 是真实文件系统拒绝，不是 mock 出来的异常。选空目录而不是非空目录，是为了让
 * 「{@code .tmp} 不残留」这条断言在同一条路径上可满足（空目录能被
 * {@code deleteIfExists} 清掉；非空目录清不掉，那条形态由
 * {@code AdminWordBankPersistFailureTest} 单独覆盖）。
 */
class AtomicFileWriterTest {

    @TempDir
    Path dir;

    /** 收集日志的探针（不复用其他测试类的私有工具，跨包不可见）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<Level> levels = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("AtomicFileWriterTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(record.getMessage() == null ? "" : record.getMessage());
                    levels.add(record.getLevel());
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

        boolean anyAtOrAbove(Level level) {
            synchronized (levels) {
                for (Level l : levels) {
                    if (l.intValue() >= level.intValue()) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    private static Path tmpOf(Path target) {
        return Path.of(target.toAbsolutePath() + AtomicFileWriter.TMP_SUFFIX);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("写成功：内容逐字节一致、不留 .tmp、父目录被创建")
    void successWritesExactContentAndLeavesNoTmp() {
        Path target = dir.resolve("nested").resolve("deeper").resolve("payload.yml");
        String content = "difficulty: beginner\nwords:\n  - english: apple\n";

        try (Captured logs = new Captured()) {
            assertTrue(AtomicFileWriter.write(target, () -> content, logs.logger),
                    "写成功必须返回 true。实际日志：" + logs.messages);

            assertEquals(content, Files.readString(target, StandardCharsets.UTF_8),
                    "落盘内容必须与 Supplier 提供的字符串逐字节一致（UTF-8）");
            assertFalse(Files.exists(tmpOf(target)),
                    "写成功后不得残留 .tmp —— 残留会在数据目录里越积越多，"
                            + "且下次同路径写入会被它挡住");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("父目录不存在也能写（Files.writeString 不会建父目录，本类必须补）")
    void parentDirectoryIsCreated() throws IOException {
        Path target = dir.resolve("player_scenarios").resolve("abc.yml");
        assertFalse(Files.exists(target.getParent()), "前置：父目录必须还不存在");

        try (Captured logs = new Captured()) {
            assertTrue(AtomicFileWriter.write(target, () -> "scenarios: {}\n", logs.logger),
                    "父目录不存在时必须自己创建（对应 FileConfiguration.save 的 createParentDirs）。"
                            + "实际日志：" + logs.messages);
        }
        assertTrue(Files.isRegularFile(target), "文件必须真的落盘");
    }

    @Test
    @DisplayName("写失败：目标文件逐字节保持原样，.tmp 不残留，且留 SEVERE")
    void failureKeepsTargetByteForByteAndCleansTmp() throws IOException {
        Path target = dir.resolve("payload.yml");
        String good = "scenarios:\n  personal:abc:meeting:\n    display_name: meeting\n";
        Files.writeString(target, good, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(target);

        // 真实文件系统故障：.tmp 路径被空目录占住 → writeString 打不开目录
        Path tmp = tmpOf(target);
        Files.createDirectory(tmp);

        try (Captured logs = new Captured()) {
            assertFalse(AtomicFileWriter.write(target, () -> "scenarios: {}\n", logs.logger),
                    "临时文件写不进去时必须返回 false。实际日志：" + logs.messages);

            assertArrayEquals(before, Files.readAllBytes(target),
                    "原子写的核心价值：失败时目标文件必须逐字节保持原样。\n"
                            + "旧实现（config.save 直写）在这里会把目标覆盖成新内容；"
                            + "真实故障（磁盘满 / 进程被杀）下留下的是半截 YAML，"
                            + "下次启动 YamlConfiguration 解析失败 → 整份数据静默消失。"
                            + "实际内容：\n" + Files.readString(target, StandardCharsets.UTF_8));
            assertFalse(Files.exists(tmp),
                    "失败路径必须清理 .tmp（否则数据目录残留孤儿文件，"
                            + "且下次同路径写入可能被它挡住）");
            assertTrue(logs.anyAtOrAbove(Level.SEVERE),
                    "写失败必须留 SEVERE 痕迹（返回 false 不是把日志也一起吞掉）。实际日志："
                            + logs.messages);
        }
    }

    @Test
    @DisplayName("替换阶段失败（目标只读）：目标仍逐字节不变，.tmp 被清理")
    void failureAtMoveStageAlsoKeepsTarget() throws IOException {
        Path target = dir.resolve("payload.yml");
        Files.writeString(target, "keep: me\n", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(target);
        assertTrue(target.toFile().setReadOnly(), "前置：必须能把目标文件设为只读");

        try (Captured logs = new Captured()) {
            // .tmp 写得进去，失败发生在 move 阶段（Windows 上 ATOMIC_MOVE 覆盖只读目标抛
            // AccessDeniedException），因此这条覆盖的是与上一条不同的 catch 分支。
            assertFalse(AtomicFileWriter.write(target, () -> "replace: me\n", logs.logger),
                    "替换失败必须返回 false（不能因为 .tmp 写成功就报成功）。实际日志："
                            + logs.messages);
            assertArrayEquals(before, Files.readAllBytes(target),
                    "替换阶段失败时目标文件也必须保持原样");
            assertFalse(Files.exists(tmpOf(target)), "替换失败后 .tmp 必须被清理");
        } finally {
            target.toFile().setWritable(true);
        }
    }

    @Test
    @DisplayName("内容序列化抛异常：与写盘失败同一路径（false + 目标不变 + .tmp 不残留）")
    void serializationFailureIsTreatedAsWriteFailure() throws IOException {
        Path target = dir.resolve("payload.yml");
        Files.writeString(target, "keep: me\n", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(target);

        Supplier<String> exploding = () -> {
            throw new IllegalStateException("unserializable value in config");
        };

        try (Captured logs = new Captured()) {
            assertFalse(AtomicFileWriter.write(target, exploding, logs.logger),
                    "序列化异常也必须表现为「没写出去」，绝不能让异常逃出去而调用方以为成功了");
            assertArrayEquals(before, Files.readAllBytes(target),
                    "序列化失败时目标文件同样必须保持原样");
            assertFalse(Files.exists(tmpOf(target)),
                    "序列化失败发生在 writeString 之前/之中，不能留下空 .tmp");
        }
    }

    // ================================================================== writeStream

    @Test
    @DisplayName("writeStream：二进制内容逐字节落盘（走 writeString 会被 UTF-8 编码破坏）")
    void writeStreamWritesBinaryVerbatim() throws IOException {
        Path target = dir.resolve("audio").resolve("word.wav");
        // 含 0x80 以上与 0x00 的字节：任何 String 往返都会破坏它们
        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 37);
        }

        try (Captured logs = new Captured()) {
            assertTrue(AtomicFileWriter.writeStream(target, out -> out.write(payload), logs.logger),
                    "流式原子写必须成功。实际日志：" + logs.messages);
        }
        assertArrayEquals(payload, Files.readAllBytes(target),
                "二进制负载必须逐字节一致 —— 这正是不能复用 write(Supplier<String>) 的原因："
                        + "Files.writeString 会按 UTF-8 编码，任意字节序列往返后不再相等");
        assertFalse(Files.exists(tmpOf(target)), "写成功后不得残留 .tmp");
    }

    @Test
    @DisplayName("writeStream 写入中途抛异常：目标文件逐字节保持原样，.tmp 被清理")
    void writeStreamFailureKeepsTarget() throws IOException {
        Path target = dir.resolve("word.wav");
        byte[] before = {1, 2, 3, 4, 5};
        Files.write(target, before);

        try (Captured logs = new Captured()) {
            assertFalse(AtomicFileWriter.writeStream(target, out -> {
                out.write(new byte[]{9, 9, 9, 9});
                throw new IOException("connection reset by peer");
            }, logs.logger),
                    "sink 抛异常必须表现为「没写出去」。实际日志：" + logs.messages);

            assertArrayEquals(before, Files.readAllBytes(target),
                    "写入中途失败时目标文件必须逐字节保持原样（临时文件里的半截内容"
                            + "永远不会被 rename 过去 —— 这就是本方法的全部价值）");
            assertFalse(Files.exists(tmpOf(target)), "失败路径必须清理 .tmp");
            assertTrue(logs.anyAtOrAbove(Level.SEVERE), "失败必须留 SEVERE 痕迹");
        }
    }

    @Test
    @DisplayName("writeStream 父目录不存在也能写")
    void writeStreamCreatesParentDirectory() throws IOException {
        Path target = dir.resolve("a").resolve("b").resolve("c.wav");
        assertFalse(Files.exists(target.getParent()), "前置：父目录必须还不存在");

        try (Captured logs = new Captured()) {
            assertTrue(AtomicFileWriter.writeStream(target, out -> out.write(new byte[]{7}),
                    logs.logger), "父目录必须被创建。实际日志：" + logs.messages);
        }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(target));
    }

    @Test
    @DisplayName("writeStream 不破坏已有的 write(String) 契约（两个入口共用同一套落盘逻辑）")
    void textWriteStillWorksAlongsideStreamWrite() throws IOException {
        Path textTarget = dir.resolve("cfg.yml");
        Path binTarget = dir.resolve("blob.bin");
        try (Captured logs = new Captured()) {
            assertTrue(AtomicFileWriter.write(textTarget, () -> "k: v\n", logs.logger),
                    "文本入口必须仍然可用。实际日志：" + logs.messages);
            assertTrue(AtomicFileWriter.writeStream(binTarget, out -> out.write(new byte[]{1, 2}),
                    logs.logger), "流式入口必须可用。实际日志：" + logs.messages);
        }
        assertEquals("k: v\n", Files.readString(textTarget, StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(binTarget));
        assertFalse(Files.exists(tmpOf(textTarget)), "文本入口不得残留 .tmp");
        assertFalse(Files.exists(tmpOf(binTarget)), "流式入口不得残留 .tmp");
    }
}
