package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.util.AtomicFileWriter;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * TTS (Text-to-Speech) Manager for word pronunciation.
 *
 * Primary feature: phonetic symbol display in GUI.
 * Bonus feature: audio download from VoiceRSS API (requires API key).
 *
 * Since Minecraft cannot natively play custom audio files,
 * pronunciation playback uses a NOTE_BLOCK sound cue as an indicator.
 * Downloaded .wav files are cached for potential future resource-pack integration.
 */
public class TtsManager {

    /**
     * D2-7（照抄 AIChatManager R-20 同构）：400/401/403 后熔断时长（毫秒）——
     * 期间不再打 API，到期自动探测恢复（Key 修正后无需重载即可自愈）。
     */
    private static final long KEY_INVALID_COOLDOWN_MS = 5 * 60 * 1000L;

    /**
     * R12b-F3：最小合法 WAV 字节数 —— 44 字节的规范 PCM 头
     * （{@code "RIFF"}4 + {@code ChunkSize}4 + {@code "WAVE"}4 + {@code "fmt "}4 +
     * fmt 体 16 + {@code "data"}4 + data 长度 4 + 8 字节 audio payload）。
     * 短于它的一律是截断产物（「只写了文件头就断」的典型形态）。
     */
    private static final long MIN_WAV_BYTES = 44L;

    /**
     * R12b-F3：可信声明长度的上界（64 MB）。超过它视为「长度未知」的流式写法而非真截断 ——
     * 45 分钟的立体声 44kHz 16bit PCM 才 ~476MB，而单词发音远达不到 64MB，
     * 该值只是把「明显不是真实音频长度」的值排除在长度比对之外，避免误杀导致永不命中缓存。
     */
    private static final long MAX_PLAUSIBLE_WAV_BYTES = 64L * 1024 * 1024;

    /**
     * R12b-F3：连续「落了盘但没通过完整性校验」的下载次数上限 —— 到顶后关闭完整性校验，
     * 只保留魔数校验。
     *
     * <p><b>为什么必须有这个兜底</b>：完整性校验把「误判合法音频」的代价放大成了
     * 「该词的缓存永不可用」→ 每次点读都打一次 VoiceRSS API，且<b>每个词</b>都如此
     * （无上限的 API 消耗与速率风险）。而真实 VoiceRSS 响应长什么样我无法在此验证。
     *
     * <p>连续 3 次都判失败，更可能是<b>我的校验对真实响应不成立</b>，而不是连坏 3 次下载。
     * 此时退化到旧行为（只校验魔数）——那正是改动前的状态，不会比改动前更差。
     * 期间毒缓存仍由 {@link #getCachedAudio} 的读侧校验拦；写侧退让不改变读侧强度。
     */
    private static final int MAX_CONSECUTIVE_INTEGRITY_REJECTS = 3;

    private final LetsLearnEnglish plugin;
    private final File cacheDir;
    private final int cacheDays;
    private boolean enabled;
    // API Key：构造时由主线程写入，之后只读消费（下载失败不再置 null——D2-7 起
    // 改用 keyInvalidUntil 熔断），volatile 保证跨线程可见性
    private volatile String apiKey;
    /** D2-7：Key 失效熔断"开启至"时间戳（毫秒）；&gt; now 即熔断中。200 成功时清零。 */
    private volatile long keyInvalidUntil = 0L;
    /** R12b-F3：连续完整性拒绝计数（仅下载线程读写；到顶后关闭写侧完整性校验）。 */
    private int consecutiveIntegrityRejects = 0;
    /** R12b-F3：写侧完整性校验是否已因连续误判而退化（读侧校验不受影响）。 */
    private volatile boolean integrityCheckDisabled = false;
    /** R4-06-F1：独占下载线程，否则阻塞 IO 占满 commonPool 会饿死 AI 的 HTTP 与落库。 */
    private final java.util.concurrent.ExecutorService downloadPool = java.util.concurrent.Executors
            .newSingleThreadExecutor(r -> { Thread t = new Thread(r, "LLEP-TTS"); t.setDaemon(true); return t; });

    /**
     * R12c-F1：本实例的线程池是否已关闭（{@link #shutdown()} 置位，单向，不复活）。
     *
     * <p><b>为什么必须有这个标志</b>：{@link #shutdown()} 之后 {@code downloadPool} 处于
     * Terminated 状态，{@code CompletableFuture.runAsync(task, pool)} 会<b>同步抛出</b>
     * {@code RejectedExecutionException}（不是异步失败，是真的从这一行扔出来）。
     * 而 {@code /lea reload} 的拆解顺序是 D8 关线程池、D9 才撤监听器 —— 这段残留窗口里
     * {@code WordLearningListener} 的发音按钮仍可被点击，异常会从
     * {@code handlePronunciationButton} 一路冒到 Bukkit 的事件分发层，中断该事件处理器的
     * 后续业务。所以「关池」必须与「拒绝新工作」成对出现，不能只做前者。
     *
     * <p>单向不复活是刻意的：{@code enableAll()} 每次<b>全新建</b> {@code TtsManager}
     * （与 {@code AIChatManager.shuttingDown} / {@code ChatFeedbackManager.accepting}
     * 同一条契约），复用实例会让重开后的发音下载永久失效。
     */
    private volatile boolean closed = false;

    public TtsManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.cacheDir = new File(plugin.getDataFolder(), "audio");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        var config = plugin.getConfigManager().getMainConfig();
        this.enabled = config.getBoolean("tts.enabled", true);
        this.cacheDays = Math.max(1, config.getInt("tts.cache-days", 7));
        this.apiKey = config.getString("tts.voicerss-api-key", "");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = null;
            plugin.getLogger().info("TTS API key not configured — phonetic display only (no audio download).");
        } else {
            plugin.getLogger().info("TTS VoiceRSS API key configured. Audio will be cached to audio/ folder.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /** D2-7：Key 失效熔断是否开启（期间不打 TTS API，照 AIChatManager.isAuthCircuitOpen 同构）。 */
    private boolean isKeyCircuitOpen() {
        return System.currentTimeMillis() < keyInvalidUntil;
    }

    /**
     * Download TTS audio from VoiceRSS API and cache it to disk.
     * Returns the cached .wav file on success, or null on failure.
     * This is non-blocking — callers get a CompletableFuture.
     */
    public CompletableFuture<File> generateAudio(String word) {
        CompletableFuture<File> future = new CompletableFuture<>();

        // Check cache first
        File cached = getCachedAudio(word);
        if (cached != null) {
            future.complete(cached);
            return future;
        }

        if (!hasApiKey() || isKeyCircuitOpen()) {
            future.complete(null);
            return future;
        }
        // R12c-F1：线程池已关（/lea reload 拆解窗口 / 关服）→ 不派发。
        // 必须在 runAsync 之前判：Terminated 的 ExecutorService 会让 runAsync
        // **同步抛** RejectedExecutionException，异常从本方法冒到调用方的事件处理器里。
        if (closed) {
            future.complete(null);
            return future;
        }

        final String key = this.apiKey;

        try {
            CompletableFuture.runAsync(() -> {
            HttpURLConnection conn = null;
            try {
                String encodedWord = URLEncoder.encode(word, StandardCharsets.UTF_8);
                String urlStr = "https://api.voicerss.org/?key=" + key
                        + "&hl=en-us&src=" + encodedWord
                        + "&f=44khz_16bit_stereo";

                URI uri = URI.create(urlStr);
                conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "LLEP-Minecraft-Plugin/1.0");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // M20：VoiceRSS 出错时也可能回 200 + 文本 "ERROR: ..."。
                    // 非音频 Content-Type 一律不落盘，防止错误文本毒化 .wav 缓存。
                    File outputFile = new File(cacheDir, sanitizeFileName(word) + ".wav");
                    String contentType = conn.getContentType();
                    if (!isAudioContentType(contentType)) {
                        plugin.getLogger().warning("TTS VoiceRSS returned non-audio Content-Type: "
                                + contentType + " — not caching (word: " + word + ").");
                        // R-65(c)：若旧缓存文件已被毒化，尽力删除（失败忽略）。
                        deleteCachedAudioQuietly(outputFile);
                        future.complete(null);
                        return;
                    }
                    boolean needsMagicCheck = contentType == null || contentType.isEmpty()
                            || contentType.toLowerCase(java.util.Locale.ROOT)
                                    .startsWith("application/octet-stream");
                    try (InputStream is = conn.getInputStream()) {
                        // R-65：先读前 12 字节 —— 空/不可信 Content-Type 时用魔数探测真实内容。
                        byte[] head = new byte[12];
                        int headLen = 0;
                        while (headLen < head.length) {
                            int read = is.read(head, headLen, head.length - headLen);
                            if (read <= 0) break;
                            headLen += read;
                        }
                        if (headLen == 0) {
                            // R-65：空响应体（即使 Content-Type 是 audio/*）不是有效音频，不落盘。
                            plugin.getLogger().fine("TTS response for word '" + word
                                    + "' is empty — not caching.");
                            deleteCachedAudioQuietly(outputFile);
                            future.complete(null);
                            return;
                        }
                        if (needsMagicCheck) {
                            byte[] probe = (headLen == head.length) ? head
                                    : java.util.Arrays.copyOf(head, headLen);
                            if (!looksLikeAudio(probe)) {
                                // 魔数不符 → 视为错误体，不落盘；旧毒缓存尽力删除。
                                plugin.getLogger().fine("TTS response for word '" + word
                                        + "' failed audio magic-byte check (Content-Type: "
                                        + contentType + ") — not caching.");
                                deleteCachedAudioQuietly(outputFile);
                                future.complete(null);
                                return;
                            }
                        }
                        // R12b-F3：原子落盘 —— 写临时文件 + 校验完整后 rename。
                        // 旧实现 FileOutputStream 直写目标文件：写到一半失败（断网 / 磁盘满 /
                        // 进程被杀）会在缓存目录留下半截 WAV，而 getCachedAudio 只校验前 12 字节
                        // 的 "RIFF"，半截文件照样命中 → 该词永久毒化且永不重新下载。
                        if (!writeCacheAtomically(outputFile, word, head, headLen, is)) {
                            future.complete(null);
                            return;
                        }
                    }
                    plugin.getLogger().fine("TTS audio cached for word: " + word);
                    // D2-7：音频真正落盘 = Key 可用实锤（200 + 通过内容校验），
                    // 清掉熔断（照 AIChatManager 成功 200 清零同构）；错误文本体
                    // 在下方各 guard 早退，到不了这里，不会误清。
                    keyInvalidUntil = 0L;
                    future.complete(outputFile);
                } else if (responseCode == 400 || responseCode == 401 || responseCode == 403) {
                    // D2-7（照抄 AIChatManager R-20 同构）：开启熔断而非置 apiKey=null——
                    // 保留原 key 供到期自动探测恢复；Key 修正后无需重载即可自愈。
                    this.keyInvalidUntil = System.currentTimeMillis() + KEY_INVALID_COOLDOWN_MS;
                    plugin.getLogger().warning("TTS VoiceRSS API returned " + responseCode
                            + " — API key may be invalid. Pausing audio downloads for "
                            + (KEY_INVALID_COOLDOWN_MS / 60000) + " minute(s), then auto-probing again.");
                    future.complete(null);
                } else {
                    plugin.getLogger().warning("TTS VoiceRSS API returned unexpected status: " + responseCode);
                    future.complete(null);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to download TTS audio for word '" + word + "': " + e.getMessage());
                future.complete(null);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, downloadPool);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // R12c-F1：与上面的 closed 检查竞态（检查通过 → 别的线程关池 → 这里被拒）。
            // 收口在本方法内，异常绝不能冒到调用方：调用方是 InventoryClickEvent 处理器，
            // 冒出去会中断同事件里的后续业务（发奖、进度更新），而后果只是"少缓存一个音频"。
            plugin.getLogger().fine("TTS download rejected (manager already shut down) for word '"
                    + word + "' — skipping.");
            future.complete(null);
        }

        return future;
    }

    /**
     * Play a pronunciation sound cue.
     * Uses NOTE_BLOCK_PLING at a pleasant pitch as a pronunciation indicator.
     * Falls back to ENTITY_EXPERIENCE_ORB_PICKUP if note block sound is unavailable.
     */
    public void playPronunciationCue(Player player) {
        if (!enabled) return;

        Sound sound = resolveSound("BLOCK_NOTE_BLOCK_PLING");
        if (sound == null) {
            sound = resolveSound("ENTITY_EXPERIENCE_ORB_PICKUP");
        }
        if (sound != null) {
            plugin.getVersionAdapter().playSound(player, sound, 1.0f, 1.5f);
        }
    }

    /**
     * Get the cached audio file for a word, or null if not cached.
     * R-65：缓存命中也要过魔数校验 —— 旧版本可能已把错误体写成 .wav，
     * 不符则删除该（毒）缓存并返回 null，走下载路径以新校验重建。
     * R12b-F3：校验从「只看前 4 字节 RIFF」加强到「RIFF 声明的长度必须与文件实际长度相符」，
     * 否则截断的 WAV 会命中缓存 → 该词永久毒化且永不重新下载。
     */
    public File getCachedAudio(String word) {
        File f = new File(cacheDir, sanitizeFileName(word) + ".wav");
        if (f.exists() && f.length() > 0) {
            if (!cachedFileLooksLikeAudio(f)) {
                // R12b-F3：从 fine 提到 warning —— 毒缓存意味着「某个词的发音对玩家一直是坏的」，
                // 而且原实现命中就返回、永不重下，症状能持续到 cache-days 过期。这是需要运维
                // 看见的状态，不是调试噪音（f 的完整路径在文件名里已足够定位）。
                plugin.getLogger().warning("Removing corrupted TTS cache file (failed audio integrity"
                        + " check, likely a truncated download): " + f.getName()
                        + " — it will be re-downloaded on next use.");
                deleteCachedAudioQuietly(f);
                return null;
            }
            return f;
        }
        return null;
    }

    /**
     * 读缓存文件头部做两层校验：魔数 + WAV 声明长度与实际文件长度一致性。
     *
     * <p><b>为什么光有魔数不够</b>：{@code looksLikeAudio} 只认前 4 字节 {@code "RIFF"}。
     * 一个只写出文件头就断掉的 WAV 完全能通过，而 {@code getCachedAudio} 命中就返回文件、
     * {@code generateAudio} 也不会再请求 API —— 于是这个坏文件被永久当成有效缓存。
     *
     * <p><b>为什么宁可漏判也不可误判</b>：这里判 false 的后果是删除文件并重新下载；
     * 但如果把<b>合法</b> WAV 判成损坏，则该词的缓存<b>永远无法命中</b>，每次点读都打一次
     * VoiceRSS API（有配额与速率成本）。因此只做「文件比头部声明的短」这一个方向的判定 ——
     * 那是截断的充分形态；文件比声明更长（尾部追加了 ID3/LIST 之类）一律放行。
     */
    private boolean cachedFileLooksLikeAudio(File f) {
        long actualLength = f.length();
        byte[] head;
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[12];
            int n = 0;
            while (n < buf.length) {
                int read = is.read(buf, n, buf.length - n);
                if (read <= 0) break;
                n += read;
            }
            head = java.util.Arrays.copyOf(buf, n);
        } catch (Exception e) {
            return false;
        }
        if (!looksLikeAudio(head)) {
            return false;
        }
        if (!startsWithRiff(head)) {
            // MP3 等格式没有便宜可读的声明长度：只能停在魔数这一层。
            return true;
        }
        // 以 RIFF 开头就必须是完整的 RIFF/WAVE 头 —— 短于 12 字节的 "RIFF..." 正是
        // 「只写了几个字节就断掉」的截断形态，不能因为魔数对就放行。
        if (!isRiffWave(head)) {
            return false;
        }
        return riffLengthMatches(head, actualLength);
    }

    /**
     * Delete cached audio files older than cacheDays.
     *
     * <p>R12b-F3：同时清理残留的 {@code .tmp}。原子写正常路径不会留它，但**进程在写盘途中被杀**
     * 会留下一个 —— 本方法开机时异步跑一次，正是回收这种残留的时机。
     * 单次残留最多几百 KB，不清理也不会「越积越多」（每次写入前 {@code Files.newOutputStream}
     * 会覆写同名 {@code .tmp}），但让运维在 {@code audio/} 里看到一堆 {@code .tmp} 会误判成故障。
     */
    public void cleanExpiredCache() {
        cleanLeftoverTmpFiles();

        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".wav"));
        if (files == null || files.length == 0) return;

        long cutoff = System.currentTimeMillis() - (cacheDays * 24L * 60 * 60 * 1000);
        int deleted = 0;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) {
                    deleted++;
                }
            }
        }
        if (deleted > 0) {
            plugin.getLogger().info("Cleaned " + deleted + " expired TTS audio cache file(s).");
        }
    }

    /** 清掉原子写残留的 {@code <word>.wav.tmp}（区分「目录」与「文件」：非空目录删不掉，跳过）。 */
    private void cleanLeftoverTmpFiles() {
        File[] leftovers = cacheDir.listFiles(
                (dir, name) -> name.endsWith(AtomicFileWriter.TMP_SUFFIX));
        if (leftovers == null || leftovers.length == 0) {
            return;
        }
        int deleted = 0;
        for (File f : leftovers) {
            if (f.isFile() && f.delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            plugin.getLogger().info("Cleaned " + deleted + " stale TTS cache temp file(s).");
        }
    }

    /**
     * 关停 TTS 下载线程池（幂等）。
     *
     * <p><b>R4-06-F1 的原始承诺</b>：「中断在途下载，不拖住关服」。这个承诺从写下那天起
     * <b>没有任何执行者</b> —— 全仓零调用点，直到 R12c-F1 把本方法接进
     * {@code LetsLearnEnglish.disableAll()} 的 D8。所以下面两件事必须一起成立：
     * <ol>
     *   <li>先 {@link #closed} 置位（单向闩），{@link #generateAudio} 的入口守卫据此拒绝
     *       新工作 —— 否则 Terminated 的池会让 {@code CompletableFuture.runAsync}
     *       <b>同步抛</b> {@code RejectedExecutionException}，从事件处理器里冒出来；</li>
     *   <li>再 {@code shutdownNow()}：中断正在 {@code HttpURLConnection} 上阻塞的下载
     *       （connect 10s / read 15s，见 {@code generateAudio}），并丢弃队列里排着的词。</li>
     * </ol>
     *
     * <p><b>为什么顺序不能反</b>：先关池后置闸，中间那一瞬的并发调用会撞上
     * RejectedExecutionException；先置闸后关池，最坏只是「闸关了、池还没关」的几纳秒，
     * 那几条任务照常跑完然后被 shutdownNow 收掉，没有异常面。
     *
     * <p>幂等：重复调用只是再对已 Terminated 的池调一次 {@code shutdownNow()}（JDK 明确定义为
     * no-op）。线程是 daemon，所以即便某次没关也拖不住 JVM —— 但会钉住插件 ClassLoader，
     * 这正是 /lea reload 反复开关时漏调它的真实代价。
     */
    public void shutdown() {
        closed = true;
        downloadPool.shutdownNow();
    }

    /** R12c-F1：本实例是否已关停（供 lint/测试断言「闸与池成对」）。 */
    public boolean isShutdown() {
        return closed;
    }

    /**
     * 把单词折叠成安全的文件名主干。
     *
     * <p><b>slice-06-F6（round1 遗留 LOW，本轮补修）：必须是单射。</b>原实现是
     * {@code word.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase()} —— 这不是单射：
     * <ul>
     *   <li>{@code "a b"} 与 {@code "a_b"} 都折叠成 {@code a_b}</li>
     *   <li>{@code "well-known"} 与 {@code "well_known"} 都折叠成 {@code well_known}</li>
     * </ul>
     * 命中即返回<b>别的词</b>的音频：玩家学 {@code a_b} 时听到的是 {@code a b} 的发音，
     * 而且因为缓存命中，这个错误会长期固着。
     *
     * <p>修法：在做可读主干的同时追加一个短哈希后缀，保证「不同输入 → 不同文件名」。
     * 保留可读主干是为了排障时能从文件名看出是哪个词；哈希取 {@code hashCode} 的
     * 无符号十六进制（32 位足以覆盖词库量级，且这是缓存键不是安全边界）。
     */
    private String sanitizeFileName(String word) {
        String readable = word.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase(java.util.Locale.ROOT);
        // 无符号十六进制，避免负数时出现 '-' 与可读主干的分隔符混淆
        String hash = Integer.toHexString(word.hashCode());
        return readable + "-" + hash;
    }

    /**
     * M20：判断响应头声明是否为可缓存的音频。
     * 放行 audio/* 与 application/octet-stream；
     * 其余（text/*、application/json 等错误体）一律拦截。
     * R-65：Content-Type 缺失/octet-stream 不再直接 fail-open —— 调用方会用
     * {@link #looksLikeAudio} 按响应实际魔数做二次探测，不符则不落盘。
     */
    private boolean isAudioContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            // R-65：空 header —— 交由魔数探测决定
            return true;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("audio/") || lower.startsWith("application/octet-stream");
    }

    /**
     * R-65：按响应体前若干字节的魔数探测是否像音频。
     * 识别：WAV（"RIFF"）、MP3（"ID3" 或 MPEG 帧同步 0xFF 0xEx/0xFF 0xFx）。
     * 空/过短数据一律视为不像（错误体不落盘）。
     */
    private static boolean looksLikeAudio(byte[] head) {
        if (head == null || head.length < 4) {
            return false;
        }
        // WAV: "RIFF"
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
            return true;
        }
        // MP3 with ID3 tag
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            return true;
        }
        // MP3 MPEG frame sync: 0xFF 0xEx / 0xFF 0xFx (0xFB included)
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
            return true;
        }
        return false;
    }

    /** 是否是 RIFF 容器（{@code looksLikeAudio} 里的 "RIFF" 分支，单独拆出来给长度校验用）。 */
    private static boolean startsWithRiff(byte[] head) {
        return head != null && head.length >= 4
                && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
    }

    /**
     * 是否是完整的 RIFF/WAVE 头（{@code RIFF} + {@code ChunkSize} + {@code WAVE}，共 12 字节）。
     *
     * <p>文件短于 12 字节但前 4 字节是 {@code RIFF} —— 这正是「只写了 6 个字节就断掉」的形态，
     * 必须判为不完整，不能因为魔数对就放行。
     */
    private static boolean isRiffWave(byte[] head) {
        return head != null && head.length >= 12 && startsWithRiff(head)
                && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E';
    }

    /**
     * WAV 完整性：RIFF 头部声明的总长度不得超过文件实际长度。
     *
     * <p><b>格式</b>：offset 4-7 是 {@code ChunkSize}（小端 uint32），含义是
     * 「{@code ChunkSize} 个字节跟在它后面」，因此声明的文件总长 = {@code ChunkSize + 8}。
     *
     * <p><b>只判一个方向</b>：{@code actualLength < declaredTotal} 即截断。反过来
     * （文件比声明长）是合法的 —— 尾部追加 LIST/ID3 之类的元数据块很常见，判错会让该词的
     * 缓存<b>永远无法命中</b>，每次点读都打一次 API。
     *
     * <h2>三条「不判截断」的豁免（都在防误杀，见 {@link #cachedFileLooksLikeAudio}）</h2>
     * <ul>
     *   <li>{@code ChunkSize == 0} / {@code 0xFFFFFFFF} / 大于 {@link #MAX_PLAUSIBLE_WAV_BYTES}：
     *       这是「长度未知」的流式写法（边生成边输出时无法回填长度）。判成截断就会把所有
     *       下载都丢掉 —— 一次也缓存不上，每次点读都打 API。</li>
     *   <li>{@code ChunkSize < 36}（且非上述哨兵）：任何 PCM WAV 的理论下界是 44 字节总长
     *       （{@code "WAVE"} 4 + {@code fmt } 头 8 + fmt 体 16 + {@code data} 头 8），
     *       声明比这更短的头部必然是不完整的。</li>
     * </ul>
     *
     * <p>另有 {@link #MIN_WAV_BYTES} 这个不受豁免影响的硬下界：文件本身短于 44 字节就一定是
     * 截断产物（「只写了 RIFF 头就断」的典型形态），任何声明长度都救不了它。
     */
    private static boolean riffLengthMatches(byte[] head, long actualLength) {
        // 硬下界：短于最小 WAV 头必为截断，与头部声明无关。
        if (actualLength < MIN_WAV_BYTES) {
            return false;
        }
        long chunkSize = ((long) (head[4] & 0xFF))
                | ((long) (head[5] & 0xFF) << 8)
                | ((long) (head[6] & 0xFF) << 16)
                | ((long) (head[7] & 0xFF) << 24);
        if (chunkSize == 0L || chunkSize == 0xFFFFFFFFL
                || chunkSize + 8L > MAX_PLAUSIBLE_WAV_BYTES) {
            // 「长度未知」的流式写法：没有可利用的声明长度，放行（魔数已通过）。
            return true;
        }
        if (chunkSize < 36L) {
            // 声明总长不到 44 字节 —— 头部不完整（真实音频走不到这里）。
            return false;
        }
        return actualLength >= chunkSize + 8L;
    }

    /**
     * R12b-F3：把响应流原子地写进缓存文件，并在落盘后复检完整性。
     *
     * <h2>为什么不是「先攒成 byte[] 再原子写」</h2>
     * <p>VoiceRSS 请求的是 {@code f=44khz_16bit_stereo}（≈172 KB/s）。攒进内存意味着峰值内存
     * 与音频长度线性相关，且要自己处理 {@code ByteArrayOutputStream} 扩容。流式写临时文件 +
     * rename 同样原子，峰值内存恒定为下面这个 4KB buffer。
     *
     * <h2>落盘后为什么要复检</h2>
     * <p>原子写只保证「不会留下半截文件」，不保证「写下去的这份是完整的」——
     * 网络在传输中途以 {@code EOF} 收尾（而不是抛异常）时，临时文件里就是一份短了几百毫秒的
     * WAV，但那仍然是「一次完整的写入」，rename 会成功。因此在 rename 之后用<b>与
     * {@link #getCachedAudio} 完全相同</b>的判定再复检一次，不通过则删掉。
     *
     * <p>复检发生在 rename 之后，理论上存在「毒文件可见」的微小窗口。这无害：
     * (1) 走到下载分支说明原本就没有可用缓存（{@link #getCachedAudio} 已把毒缓存删掉）；
     * (2) 读缓存路径现在也做同一套完整性校验，即使被撞见也会被识别并删除。
     *
     * @return {@code true} 表示缓存文件已就位且通过完整性与魔数校验
     */
    private boolean writeCacheAtomically(File outputFile, String word, byte[] head, int headLen,
                                         InputStream is) {
        boolean written = AtomicFileWriter.writeStream(outputFile.toPath(), out -> {
            out.write(head, 0, headLen);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }, plugin.getLogger());

        if (!written) {
            // 写失败：原子写保证目标文件保持原样（不存在则仍不存在），.tmp 已清理。
            plugin.getLogger().warning("Failed to cache TTS audio for word '" + word
                    + "' — cache file left untouched.");
            return false;
        }

        if (integrityCheckDisabled) {
            // 已退化：写侧只做魔数校验（与改动前完全一致），读侧完整性校验不受影响。
            return true;
        }
        if (!cachedFileLooksLikeAudio(outputFile)) {
            consecutiveIntegrityRejects++;
            if (consecutiveIntegrityRejects >= MAX_CONSECUTIVE_INTEGRITY_REJECTS) {
                // 连续多次都判不合格：更可能是校验对真实响应不成立，而不是连坏多次下载。
                // 退化到旧行为（只校验魔数），避免「一个词都缓存不上 + 每次点读都打 API」。
                plugin.getLogger().severe("TTS audio integrity check rejected " + consecutiveIntegrityRejects
                        + " downloads in a row — the length check does not match real VoiceRSS responses."
                        + " Disabling the WRITE-side length check (magic-byte check stays; the READ-side"
                        + " integrity check stays). Please report this with a sample .wav file.");
                consecutiveIntegrityRejects = 0;
                integrityCheckDisabled = true;
            }
            plugin.getLogger().warning("TTS audio for word '" + word + "' is truncated or malformed"
                    + " (RIFF length mismatch) — discarding it so the next attempt re-downloads.");
            deleteCachedAudioQuietly(outputFile);
            return false;
        }
        consecutiveIntegrityRejects = 0;
        return true;
    }

    /** R-65(c)：尽力删除（可能已被毒化的）缓存文件；失败忽略。 */
    private void deleteCachedAudioQuietly(File f) {
        try {
            if (f != null && f.exists() && !f.delete()) {
                plugin.getLogger().fine("Could not delete poisoned TTS cache file: " + f.getName());
            }
        } catch (Exception ignored) {
            // best-effort only
        }
    }

    private Sound resolveSound(String name) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
