package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

    private final LetsLearnEnglish plugin;
    private final File cacheDir;
    private final int cacheDays;
    private boolean enabled;
    // API Key：构造时由主线程写入，下载失败时可能被 commonPool 线程置 null，故用 volatile 保证跨线程可见性
    private volatile String apiKey;

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

        if (!hasApiKey()) {
            future.complete(null);
            return future;
        }

        final String key = this.apiKey;

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
                        try (OutputStream fos = new FileOutputStream(outputFile)) {
                            fos.write(head, 0, headLen);
                            byte[] buffer = new byte[4096];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                    plugin.getLogger().fine("TTS audio cached for word: " + word);
                    future.complete(outputFile);
                } else if (responseCode == 400 || responseCode == 401 || responseCode == 403) {
                    plugin.getLogger().warning("TTS VoiceRSS API returned " + responseCode
                            + " — API key may be invalid. Disabling audio downloads for this session.");
                    this.apiKey = null;
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
        });

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
     */
    public File getCachedAudio(String word) {
        File f = new File(cacheDir, sanitizeFileName(word) + ".wav");
        if (f.exists() && f.length() > 0) {
            if (!cachedFileLooksLikeAudio(f)) {
                plugin.getLogger().fine("Removing poisoned TTS cache file (failed audio magic check): "
                        + f.getName());
                deleteCachedAudioQuietly(f);
                return null;
            }
            return f;
        }
        return null;
    }

    /** R-65：读取缓存文件前 12 字节做魔数探测；读失败/过短一律视为不是音频。 */
    private boolean cachedFileLooksLikeAudio(File f) {
        try (InputStream is = new FileInputStream(f)) {
            byte[] head = new byte[12];
            int n = 0;
            while (n < head.length) {
                int read = is.read(head, n, head.length - n);
                if (read <= 0) break;
                n += read;
            }
            return looksLikeAudio((n == head.length) ? head : java.util.Arrays.copyOf(head, n));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete cached audio files older than cacheDays.
     */
    public void cleanExpiredCache() {
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

    /**
     * Safely shut down the TTS manager.
     */
    public void shutdown() {
        // No persistent resources to release; cache files remain on disk
    }

    private String sanitizeFileName(String word) {
        return word.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
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
