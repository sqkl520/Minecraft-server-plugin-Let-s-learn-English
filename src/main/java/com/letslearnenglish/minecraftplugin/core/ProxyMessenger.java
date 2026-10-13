package com.letslearnenglish.minecraftplugin.core;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Proxy Messenger — Velocity / BungeeCord 跨服通信
 *
 * 通过 BungeeCord 插件消息通道实现：
 * - 跨服公告广播
 * - 成就解锁全服通知
 * - 全局消息下发
 *
 * Velocity 向下兼容 BungeeCord 的 PluginMessage 协议，
 * 因此本类可同时兼容 Velocity 和 BungeeCord 代理。
 */
public class ProxyMessenger implements PluginMessageListener {

    public static final String CHANNEL = "BungeeCord";

    private final LetsLearnEnglish plugin;
    private volatile boolean registered;

    public ProxyMessenger(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册插件消息通道。
     * 如果当前没有在线玩家（即无代理连接），静默跳过不做任何操作，
     * 避免在单服测试环境输出无意义报错。
     */
    public void register() {
        if (registered) return;
        try {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
            registered = true;
            plugin.getLogger().info("Proxy messaging channel registered (Velocity/BungeeCord compatible).");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to register proxy messaging channel — running in standalone mode.", e);
        }
    }

    public void unregister() {
        if (!registered) return;
        try {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        } catch (Exception ignored) {
            // channel may already be unregistered during plugin disable
        }
        registered = false;
    }

    // ============================================================
    //  发送方法
    // ============================================================

    /**
     * 向全服发送公告消息（转发到代理，代理广播给所有子服）。
     */
    public void sendGlobalMessage(String message) {
        sendForward("ALL", "GlobalMessage", message);
    }

    /**
     * 向全服发送成就解锁公告（PF-ACH-03：双语 payload）。
     *
     * <p>payload 格式 {@code playerName|uuid|zhName|enName}——接收端逐收件人按各自
     * 语言取用对应名称渲染（原实现只带解锁者语言的单名，混服里语言混拼）。
     * 旧 3 段格式（{@code playerName|uuid|name}）由接收端兼容。
     */
    public void sendAchievementBroadcast(String playerName, UUID playerId,
                                         String zhName, String enName) {
        String payload = playerName + "|" + playerId + "|" + zhName + "|" + enName;
        sendForward("ALL", "AchievementUnlock", payload);
    }

    /**
     * 向指定子服发送消息。
     *
     * @param targetServer 目标子服名，"ALL" 表示全部子服
     * @param subChannel   子通道标识
     * @param data         载荷数据
     */
    private void sendForward(String targetServer, String subChannel, String data) {
        if (!registered) return;

        // 需要至少一个在线玩家作为"信使"来发送 PluginMessage
        Player messenger = getAnyOnlinePlayer();
        if (messenger == null) {
            plugin.getLogger().fine("No online player available to send proxy message, skipping.");
            return;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");       // BungeeCord 子通道: Forward
        out.writeUTF(targetServer);    // 目标服务器名或 "ALL"
        out.writeUTF(subChannel);      // 自定义子通道

        // 写入载荷
        ByteArrayDataOutput payloadOut = ByteStreams.newDataOutput();
        payloadOut.writeUTF(data);
        byte[] payloadBytes = payloadOut.toByteArray();

        out.writeShort(payloadBytes.length);
        out.write(payloadBytes);

        messenger.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    private Player getAnyOnlinePlayer() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    public boolean isRegistered() {
        return registered;
    }

    // ============================================================
    //  接收方法 — 处理来自其他子服或代理的消息
    // ============================================================

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;

        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel = in.readUTF();

        // PF-SEA-01（Phase 3 规划期发现，编排者即修）：BungeeCord/Velocity 的 Forward
        // 投递帧 = [子通道 UTF][short 帧长][载荷字节]。原实现跳过帧长直接 readUTF ——
        // 短消息会带 2 字节不可见前缀；长消息（modified-UTF ≥128 字节，约 126 ASCII
        // 字符或 42 个中文字）解码抛 UTFDataFormatException 整条丢失。现按官方协议
        // 先消费帧长、readFully 取载荷，再在载荷内解析本插件自己的 writeUTF 格式。
        byte[] payloadBytes;
        try {
            int frameLen = in.readShort() & 0xFFFF;
            payloadBytes = new byte[frameLen];
            in.readFully(payloadBytes);
        } catch (Exception e) {
            // 非法帧（非本插件格式/被截断）——丢弃并记一次 fine，绝不让异常穿出监听器
            plugin.getLogger().fine("Malformed proxy frame for subChannel " + subChannel + ": " + e);
            return;
        }
        ByteArrayDataInput payload = ByteStreams.newDataInput(payloadBytes);

        try {
            switch (subChannel) {
                case "GlobalMessage" -> handleGlobalMessage(payload.readUTF());
                case "AchievementUnlock" -> handleAchievementBroadcast(payload.readUTF());
                default -> {
                    // 忽略未知子通道（可能来自其他插件）
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Malformed proxy payload for subChannel " + subChannel + ": " + e);
        }
    }

    private void handleGlobalMessage(String message) {
        String formatted = org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
        Bukkit.broadcastMessage(formatted);
        plugin.getLogger().info("[Proxy] Global message received: " + message);
    }

    /**
     * PF-ACH-03（plan-achievements §2.4/H13）：成就广播逐收件人渲染。
     *
     * <p>payload 四段 {@code playerName|uuid|zhName|enName}；旧 3 段格式兼容
     * （zh=en=parts[2]）。与旧实现（全服同一句、模板固定服务器默认语言）不同，
     * 现按每位收件人自己的语言取名称与模板渲染发送（R-86m 答对广播的
     * per-recipient 先例）。{prefix} 按收件人语言解析——接收端重写后模板渲染
     * 完整（旧实现会把字面 "{prefix}" 广播出去）。
     */
    private void handleAchievementBroadcast(String payload) {
        String[] parts = payload.split("\\|", 4);
        if (parts.length < 3) return;
        String playerName = parts[0];
        // UUID ignored for broadcast display — just show the achievement
        // 兼容：旧格式 3 段（单名）→ zh = en = parts[2]
        String zhName = parts[2];
        String enName = parts.length >= 4 ? parts[3] : zhName;

        for (Player online : Bukkit.getOnlinePlayers()) {
            String lang = plugin.getPlayerLanguage(online);
            String achievementName = "zh".equalsIgnoreCase(lang) ? zhName : enName;
            String template = resolveAchievementTemplate(lang);
            if (template == null) continue;
            String msg = template
                    .replace("{achievement}", achievementName)
                    .replace("{player}", playerName)
                    .replace("{prefix}", resolvePrefix(lang));
            msg = org.bukkit.ChatColor.translateAlternateColorCodes('&', msg);
            online.sendMessage(msg);
        }
    }

    /**
     * R-78（P2 批次·编排者 fan-in，per-recipient 化）：模板缺失时不再 NPE 半残——
     * 该语言缺键回落 zh，两语言都缺返回 null（该收件人放弃本次广播）。
     */
    private String resolveAchievementTemplate(String lang) {
        String template = plugin.getConfigManager().getMessageConfig(lang)
                .getString("dialogue.proxy-achievement");
        if (template == null && !"zh".equalsIgnoreCase(lang)) {
            template = plugin.getConfigManager().getMessageConfig("zh")
                    .getString("dialogue.proxy-achievement");
        }
        return template;
    }

    /** {prefix} 解析：config.yml 的 {@code prefix.<lang>}，缺省用消息文件自身 prefix 键。 */
    private String resolvePrefix(String lang) {
        String prefix = plugin.getConfigManager().getMainConfig().getString("prefix." + lang);
        if (prefix != null && !prefix.isEmpty()) {
            return prefix;
        }
        String fallback = plugin.getConfigManager().getMessageConfig(lang).getString("prefix");
        return fallback != null ? fallback : "";
    }
}
