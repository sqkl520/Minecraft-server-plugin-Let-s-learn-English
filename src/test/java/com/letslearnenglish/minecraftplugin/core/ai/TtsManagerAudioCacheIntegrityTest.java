package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.util.AtomicFileWriter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R12b-F3：TTS 音频缓存的「截断即毒化」缺陷。
 *
 * <h2>这条缺陷是什么</h2>
 * <p>原实现用 {@code FileOutputStream} <b>直写</b>目标 {@code audio/<word>.wav}。写到一半失败
 * （断网 / 磁盘满 / 进程被杀）就在缓存目录留下<b>半截 WAV</b>。而读缓存路径的校验
 * {@code looksLikeAudio} 只认前 4 字节 {@code "RIFF"}，半截文件照样通过 →
 * {@code getCachedAudio} 命中即返回 → {@code generateAudio} 不会再请求 API。
 *
 * <p><b>为什么这不是「可再生缓存，坏了自然会重拉」</b>：正因为命中就返回，它<b>不会</b>重拉。
 * 玩家每次点读都拿到同一个坏音频，直到 {@code cleanExpiredCache} 按 {@code cache-days}
 * （默认 7 天）过期才恢复。这是<b>变成存量</b>的产物，不是可自愈的临时文件。
 *
 * <h2>本测试钉的两件事</h2>
 * <ol>
 *   <li><b>写入原子</b>：失败时目标文件逐字节保持原样、不留半截、不留 {@code .tmp}。</li>
 *   <li><b>校验够强</b>：RIFF 头部声明的长度与实际文件长度不符 → 判为毒缓存并删除，
 *       让下一次点读走下载路径重建。</li>
 * </ol>
 *
 * <h2>为什么直接测 private 方法</h2>
 * <p>{@code writeCacheAtomically} 是那段写入逻辑的<b>唯一</b>落点（{@code generateAudio} 里的
 * 调用只是传参）。走 {@code generateAudio} 需要劫持 {@code HttpURLConnection} 的
 * {@code openConnection()}，而 URI 是方法内联构造的，劫持点只能靠静态 mock 框架 ——
 * 那样测到的是框架的桩，不是这段代码。这里用反射直接喂一个会失败的 {@link InputStream}，
 * 失败是<b>真实的</b> IO 异常，不是桩出来的。校验侧（{@code getCachedAudio} /
 * {@code cleanExpiredCache}）是 public，直接调用。
 */
class TtsManagerAudioCacheIntegrityTest {

    @TempDir
    Path dataDir;

    // ------------------------------------------------------------------ 工具

    /** 收集日志的探针（本仓每个测试类各带一份，跨包私有工具不可复用）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<Level> levels = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("TtsManagerAudioCacheIntegrityTest." + UUID.randomUUID());
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
            for (Level l : levels) {
                if (l.intValue() >= level.intValue()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    private TtsManager newManager(Captured logs) {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        when(plugin.getLogger()).thenReturn(logs.logger);

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("tts.enabled", true);
        cfg.set("tts.cache-days", 7);
        cfg.set("tts.voicerss-api-key", "");
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getMainConfig()).thenReturn(cfg);
        when(plugin.getConfigManager()).thenReturn(configManager);
        return new TtsManager(plugin);
    }

    private Path audioDir() {
        return dataDir.resolve("audio");
    }

    /** 用生产代码自己的 {@code sanitizeFileName} 算缓存路径（不复制一份逻辑）。 */
    private Path cacheFile(TtsManager manager, String word) throws Exception {
        Method m = TtsManager.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        return audioDir().resolve(((String) m.invoke(manager, word)) + ".wav");
    }

    private static Path tmpOf(Path target) {
        return Path.of(target.toAbsolutePath() + AtomicFileWriter.TMP_SUFFIX);
    }

    /** 造一段合法的 PCM WAV：{@code dataLen} 字节的 payload，头部 {@code ChunkSize = 36 + dataLen}。 */
    private static byte[] validWav(int dataLen) {
        byte[] b = new byte[44 + dataLen];
        b[0] = 'R';
        b[1] = 'I';
        b[2] = 'F';
        b[3] = 'F';
        putLittleEndian(b, 4, 36 + dataLen);      // RIFF ChunkSize
        b[8] = 'W';
        b[9] = 'A';
        b[10] = 'V';
        b[11] = 'E';
        b[12] = 'f';
        b[13] = 'm';
        b[14] = 't';
        b[15] = ' ';
        putLittleEndian(b, 16, 16);               // fmt 块长度
        putLittleEndian(b, 20, 1);                // PCM
        putLittleEndian(b, 22, 2);                // 立体声
        putLittleEndian(b, 24, 44100);            // 采样率
        putLittleEndian(b, 28, 176400);           // byte rate
        putLittleEndian(b, 32, 4);                // block align
        putLittleEndian(b, 34, 16);               // bits per sample
        b[36] = 'd';
        b[37] = 'a';
        b[38] = 't';
        b[39] = 'a';
        putLittleEndian(b, 40, dataLen);
        for (int i = 0; i < dataLen; i++) {
            b[44 + i] = (byte) (i * 7);
        }
        return b;
    }

    private static void putLittleEndian(byte[] b, int offset, int value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        b[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        b[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    /** 真正的半截 WAV：完整 WAV 的前 {@code keep} 个字节（声明长度不变，文件变短）。 */
    private static byte[] truncatedWav(int dataLen, int keep) {
        byte[] full = validWav(dataLen);
        return java.util.Arrays.copyOf(full, keep);
    }

    /** 调 {@code writeCacheAtomically(File, String, byte[], int, InputStream)}（private）。 */
    private static boolean invokeWriteCache(TtsManager manager, File target, String word,
                                            byte[] head, int headLen, InputStream is)
            throws Exception {
        Method m = TtsManager.class.getDeclaredMethod("writeCacheAtomically",
                File.class, String.class, byte[].class, int.class, InputStream.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(manager, target, word, head, headLen, is);
    }

    // ================================================================== 组 1

    @Test
    @DisplayName("写入失败（流中途中止）：目标文件逐字节保持原样，不留半截、不留 .tmp")
    void writeFailureKeepsTargetByteForByte() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "apple");
            Files.createDirectories(target.getParent());

            // 前置：磁盘上已有一份好的缓存（真实顺序里 getCachedAudio 会先命中，故此处只是
            // 为了让「保持原样」有可断言的旧内容；下面第二条用例覆盖「原本不存在」的形态）。
            byte[] good = validWav(400);
            Files.write(target, good);

            byte[] head = validWav(400);
            head = java.util.Arrays.copyOf(head, 12);

            // 真实 IO 失败：读到第 3 次就抛（不是 mock 出来的异常）。
            InputStream failing = new InputStream() {
                private int reads = 0;

                @Override
                public int read() throws IOException {
                    throw new IOException("connection reset by peer");
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (++reads <= 2) {
                        java.util.Arrays.fill(b, off, off + Math.min(len, 64), (byte) 0x5A);
                        return Math.min(len, 64);
                    }
                    throw new IOException("connection reset by peer");
                }
            };

            assertFalse(invokeWriteCache(manager, target.toFile(), "apple", head, 12, failing),
                    "写入失败必须返回 false，否则 generateAudio 会把一个坏文件当成缓存命中"
                            + "并 complete(file)。实际日志：" + logs.messages);

            assertArrayEquals(good, Files.readAllBytes(target),
                    "原子写的核心价值：写入失败时目标文件必须逐字节保持原样。\n"
                            + "旧实现用 FileOutputStream 直写目标文件 —— 失败后磁盘上留下的是"
                            + "「头部 + 几十个字节」的半截 WAV，而 getCachedAudio 只校验前 4 字节"
                            + " \"RIFF\"，于是它<b>永久</b>被当成有效缓存返回，再也无法重新下载。\n"
                            + "实际长度：" + Files.size(target) + "（期望 " + good.length + "）");

            assertFalse(Files.exists(tmpOf(target)),
                    "失败路径必须清理 .tmp（否则 audio/ 里积残留文件，运维会误判成故障）");
        }
    }

    @Test
    @DisplayName("写入失败且原本无缓存：仍然不产生任何文件（半截文件是我们唯一要防的东西）")
    void writeFailureCreatesNoFileWhenNoneExisted() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "banana");
            Files.createDirectories(target.getParent());
            assertFalse(Files.exists(target), "前置：缓存文件必须还不存在");

            byte[] head = java.util.Arrays.copyOf(validWav(300), 12);
            InputStream failing = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("network down");
                }
            };

            assertFalse(invokeWriteCache(manager, target.toFile(), "banana", head, 12, failing),
                    "写入失败必须返回 false。实际日志：" + logs.messages);

            assertFalse(Files.exists(target),
                    "写失败不得创建目标文件。\n"
                            + "旧实现直接 open 目标文件，失败后留下 12 字节的 \"RIFF\" 头 —— "
                            + "这个 12 字节文件<b>能通过</b>原来的 looksLikeAudio 校验，"
                            + "从此该词永远拿到坏音频。实际长度："
                            + (Files.exists(target) ? Files.size(target) : -1));
            assertFalse(Files.exists(tmpOf(target)), "失败路径必须清理 .tmp");
            assertTrue(logs.anyAtOrAbove(Level.SEVERE),
                    "写失败必须留 SEVERE 痕迹（返回 false 不等于把日志一起吞掉）。实际日志："
                            + logs.messages);
        }
    }

    @Test
    @DisplayName("写入成功：内容逐字节一致、不留 .tmp")
    void writeSuccessProducesExactBytes() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "cherry");
            Files.createDirectories(target.getParent());

            byte[] audio = validWav(1024);
            byte[] head = java.util.Arrays.copyOf(audio, 12);
            InputStream rest = new java.io.ByteArrayInputStream(
                    audio, 12, audio.length - 12);

            assertTrue(invokeWriteCache(manager, target.toFile(), "cherry", head, 12, rest),
                    "完整音频必须写成功。实际日志：" + logs.messages);

            assertArrayEquals(audio, Files.readAllBytes(target),
                    "流式原子写必须把 head + 后续字节<b>按序完整</b>落盘（漏写 head 会让文件"
                            + "少 12 字节，长度校验随即判它截断 —— 每次点读都重新下载）");
            assertFalse(Files.exists(tmpOf(target)), "写成功后不得残留 .tmp");
        }
    }

    @Test
    @DisplayName("下载以 EOF 提前收尾（不抛异常）：半截音频不得留在磁盘上")
    void truncatedButCleanlyClosedStreamIsDiscarded() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "durian");
            Files.createDirectories(target.getParent());

            // 头部声明 2000 字节 payload，实际只给了 200 —— 网络以 EOF 收尾的典型形态。
            // 这不是「写失败」（一次写入完整地成功了），原子写救不了，靠落盘后复检兜。
            byte[] claimed = validWav(2000);
            byte[] partial = java.util.Arrays.copyOf(claimed, 244);
            byte[] head = java.util.Arrays.copyOf(partial, 12);
            InputStream rest = new java.io.ByteArrayInputStream(partial, 12, partial.length - 12);

            assertFalse(invokeWriteCache(manager, target.toFile(), "durian", head, 12, rest),
                    "头部声明 2036 字节但实际只有 244 字节 —— 必须判为截断。实际日志："
                            + logs.messages);

            assertFalse(Files.exists(target),
                    "截断音频不得留在缓存目录。\n"
                            + "这正是红队推翻「ACCEPTED_RISK」的那一格：原子写只保证"
                            + "「不留半截」，不保证「写下去的那份是完整的」。");
            assertFalse(Files.exists(tmpOf(target)), "不得残留 .tmp");
        }
    }

    // ================================================================== 组 2

    @Test
    @DisplayName("毒缓存识别：RIFF 声明长度与文件实际长度不符 → 返回 null 并删除")
    void poisonedTruncatedCacheIsRejectedAndDeleted() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "elderberry");
            Files.createDirectories(target.getParent());

            // 「只写了头部就断掉」的形态：合法的声明长度，但文件只有 200 字节。
            byte[] poison = truncatedWav(4000, 200);
            Files.write(target, poison);

            File result = manager.getCachedAudio("elderberry");

            assertNull(result,
                    "截断的缓存文件必须视为未命中（返回 null），让调用方走下载路径重建。\n"
                            + "原实现只校验前 4 字节 \"RIFF\"，这个 " + poison.length
                            + " 字节的文件能通过，于是被当成有效缓存<b>永久</b>返回 —— "
                            + "玩家每次点读都是坏音频，且永远不会重新下载。");
            assertFalse(Files.exists(target),
                    "判定为毒缓存后必须删除（否则下一次命中还是它，等于没修）");
            assertTrue(logs.anyAtOrAbove(Level.WARNING),
                    "删除毒缓存必须留 WARNING（不是 fine —— 默认日志级别会把 fine 吞掉，"
                            + "而「某个词的发音一直是坏的」正是运维需要看见的症状）。"
                            + "实际日志：" + logs.messages);
            assertTrue(logs.messages.toString().contains("corrupted"),
                    "诊断文案应指明是缓存损坏/截断。实际日志：" + logs.messages);
        }
    }

    @Test
    @DisplayName("毒缓存识别：只有 \"RIFF\" 6 个字节（头都没写完）→ 拒绝")
    void cacheWithOnlyMagicBytesIsRejected() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "fig");
            Files.createDirectories(target.getParent());

            Files.write(target, new byte[]{'R', 'I', 'F', 'F', 0x10, 0x00});

            assertNull(manager.getCachedAudio("fig"),
                    "6 字节的 \"RIFF..\" 是「只写出魔数就断」的形态，必须判为无效");
            assertFalse(Files.exists(target), "无效缓存必须被删除");
        }
    }

    @Test
    @DisplayName("正常 WAV 不受影响：命中且不被删除")
    void validWavStillHits() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "grape");
            Files.createDirectories(target.getParent());
            byte[] good = validWav(880);
            Files.write(target, good);

            File result = manager.getCachedAudio("grape");

            assertNotNull(result, "校验加强绝不能把<b>合法</b> WAV 判成损坏 —— 那会让该词的缓存"
                    + "永远无法命中，每次点读都打一次 VoiceRSS API（有配额与速率成本）。实际日志："
                    + logs.messages);
            assertEquals(target.toFile(), result, "必须返回那个缓存文件");
            assertTrue(Files.exists(target), "合法缓存不得被删除");
            assertArrayEquals(good, Files.readAllBytes(target), "合法缓存内容不得被改动");
        }
    }

    @Test
    @DisplayName("不误判 1：文件比声明长度更长（尾部追加了 LIST/ID3）→ 仍然命中")
    void fileLongerThanDeclaredStillHits() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "honeydew");
            Files.createDirectories(target.getParent());

            byte[] audio = validWav(200);
            byte[] withTrailingMetadata = java.util.Arrays.copyOf(audio, audio.length + 300);
            Files.write(target, withTrailingMetadata);

            assertNotNull(manager.getCachedAudio("honeydew"),
                    "只判「文件比声明短」这一个方向。反过来（文件比声明长）是合法的：尾部追加"
                            + " LIST/ID3 元数据块很常见，判错会让缓存永不命中。实际日志："
                            + logs.messages);
        }
    }

    @Test
    @DisplayName("不误判 2：ChunkSize == 0（长度未知的流式写法）→ 仍然命中")
    void zeroChunkSizeIsTreatedAsUnknownLength() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "imbe");
            Files.createDirectories(target.getParent());

            byte[] audio = validWav(512);
            putLittleEndian(audio, 4, 0);   // 边生成边输出时无法回填长度 → 留 0
            Files.write(target, audio);

            assertNotNull(manager.getCachedAudio("imbe"),
                    "ChunkSize==0 是「长度未知」的流式写法而不是截断。判成截断会把<b>每一次</b>"
                            + "下载都丢掉 —— 一次也缓存不上，每次点读都打 API。实际日志："
                            + logs.messages);
        }
    }

    @Test
    @DisplayName("不误判 3：MP3（ID3 头，没有 RIFF 长度）→ 仍然命中")
    void mp3StillHits() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "jackfruit");
            Files.createDirectories(target.getParent());

            byte[] mp3 = new byte[600];
            mp3[0] = 'I';
            mp3[1] = 'D';
            mp3[2] = '3';
            mp3[3] = 3;
            Files.write(target, mp3);

            assertNotNull(manager.getCachedAudio("jackfruit"),
                    "MP3 没有便宜可读的声明长度，只能停在魔数这一层 —— 不能因为「没法校验长度」"
                            + "就判成损坏。实际日志：" + logs.messages);
        }
    }

    // ================================================================== 组 3

    @Test
    @DisplayName("防误杀兜底：连续 3 次判不合格后，写侧校验自动退化（读侧仍然拦）")
    void writeSideCheckDegradesAfterThreeConsecutiveRejects() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Files.createDirectories(audioDir());

            // 模拟「我的长度校验对真实 VoiceRSS 响应根本不成立」：连做 3 次
            // 「落了盘但长度不符」的下载。
            for (int i = 0; i < 3; i++) {
                Path target = cacheFile(manager, "reject" + i);
                byte[] claimed = validWav(2000);
                byte[] partial = java.util.Arrays.copyOf(claimed, 244);
                byte[] head = java.util.Arrays.copyOf(partial, 12);
                InputStream rest = new java.io.ByteArrayInputStream(partial, 12, partial.length - 12);
                assertFalse(invokeWriteCache(manager, target.toFile(), "reject" + i, head, 12, rest),
                        "第 " + (i + 1) + " 次截断仍应被拒。实际日志：" + logs.messages);
            }

            assertTrue(logs.anyAtOrAbove(Level.SEVERE),
                    "连续拒绝必须留 SEVERE 说明「校验可能与真实响应不符」—— 否则运维只会看到"
                            + "无尽的 warning，却不知道这是代码假设错了。实际日志：" + logs.messages);

            // 退化后用同一份「长度不符」的内容再写一次：这次应当成功，
            // 否则该词永远缓存不上、每次点读都打 VoiceRSS API（无上限的 API 消耗）。
            Path after = cacheFile(manager, "degraded");
            byte[] claimed = validWav(2000);
            byte[] partial = java.util.Arrays.copyOf(claimed, 244);
            byte[] head = java.util.Arrays.copyOf(partial, 12);
            InputStream rest = new java.io.ByteArrayInputStream(partial, 12, partial.length - 12);

            assertTrue(invokeWriteCache(manager, after.toFile(), "degraded", head, 12, rest),
                    "连续 3 次拒绝后写侧必须退化到「只校验魔数」（改动前的行为），"
                            + "否则一个词都缓存不上。实际日志：" + logs.messages);
            assertTrue(Files.exists(after), "退化后文件应留在磁盘上（读侧仍会判定它的完整性）");

            // 关键：退化<b>只影响写侧</b>，读侧必须仍然把这半截文件拦下来。
            assertNull(manager.getCachedAudio("degraded"),
                    "读侧完整性校验不得被写侧退化牵连 —— 否则毒缓存又能命中了");
            assertFalse(Files.exists(after), "读侧判毒后必须删除");
        }
    }

    // ================================================================== 组 4

    @Test
    @DisplayName("cleanExpiredCache 顺带清掉原子写残留的 .tmp")
    void cleanExpiredCacheRemovesStaleTmpFiles() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Files.createDirectories(audioDir());

            // 进程在写盘途中被杀 → 留下 .tmp（不是 .wav，过期扫描不会碰它）
            Path stale = audioDir().resolve("stale-word-1234abcd.wav.tmp");
            Files.write(stale, new byte[128]);

            manager.cleanExpiredCache();

            assertFalse(Files.exists(stale),
                    "开机异步跑的 cleanExpiredCache 应回收原子写的 .tmp 残留 —— 否则运维在"
                            + " audio/ 里看到一堆 .tmp 会误判成故障。实际日志：" + logs.messages);
        }
    }

    @Test
    @DisplayName("cleanExpiredCache 不得误删 .wav（清理 .tmp 不能把正常缓存一起扫掉）")
    void cleanExpiredCacheKeepsFreshWav() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Path target = cacheFile(manager, "kiwi");
            Files.createDirectories(target.getParent());
            Files.write(target, validWav(100));
            Files.write(audioDir().resolve("x.wav.tmp"), new byte[10]);

            manager.cleanExpiredCache();

            assertTrue(Files.exists(target), "未过期的 .wav 缓存必须保留");
            assertFalse(Files.exists(audioDir().resolve("x.wav.tmp")), ".tmp 必须被清掉");
        }
    }

    @Test
    @DisplayName("下划线文件名（sanitize 产出）在 .tmp 清理里也认得出来")
    void tmpCleanupMatchesSanitizedNames() throws Exception {
        try (Captured logs = new Captured()) {
            TtsManager manager = newManager(logs);
            Files.createDirectories(audioDir());
            Path tmp = Path.of(cacheFile(manager, "ice cream").toAbsolutePath()
                    + AtomicFileWriter.TMP_SUFFIX);
            Files.write(tmp, "x".getBytes(StandardCharsets.UTF_8));

            manager.cleanExpiredCache();

            assertFalse(Files.exists(tmp), "带下划线/短哈希的文件名同样要能被 .tmp 清理扫到");
        }
    }
}
