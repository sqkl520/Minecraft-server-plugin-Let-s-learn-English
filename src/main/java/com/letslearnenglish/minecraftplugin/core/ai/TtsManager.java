package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.File;
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
                    String contentType = conn.getContentType();
                    if (!isAudioContentType(contentType)) {
                        plugin.getLogger().warning("TTS VoiceRSS returned non-audio Content-Type: "
                                + contentType + " — not caching (word: " + word + ").");
                        future.complete(null);
                        return;
                    }
                    File outputFile = new File(cacheDir, sanitizeFileName(word) + ".wav");
                    try (InputStream is = conn.getInputStream();
                         OutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
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
     */
    public File getCachedAudio(String word) {
        File f = new File(cacheDir, sanitizeFileName(word) + ".wav");
        if (f.exists() && f.length() > 0) {
            return f;
        }
        return null;
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
     * M20：判断响应是否为可缓存的音频。
     * 放行 audio/* 与 application/octet-stream；Content-Type 缺失时放行
     * （不断网关/旧代理可能 strip 响应头，宁可信其有）；
     * 其余（text/*、application/json 等错误体）一律拦截。
     */
    private boolean isAudioContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return true;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("audio/") || lower.startsWith("application/octet-stream");
    }

    private Sound resolveSound(String name) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
