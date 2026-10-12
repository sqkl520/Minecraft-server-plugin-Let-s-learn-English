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
     * 向全服发送成就解锁公告。
     */
    public void sendAchievementBroadcast(String playerName, UUID playerId, String achievementName) {
        String payload = playerName + "|" + playerId + "|" + achievementName;
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

        switch (subChannel) {
            case "GlobalMessage" -> {
                String msg = in.readUTF();
                handleGlobalMessage(msg);
            }
            case "AchievementUnlock" -> {
                String payload = in.readUTF();
                handleAchievementBroadcast(payload);
            }
            default -> {
                // 忽略未知子通道（可能来自其他插件）
            }
        }
    }

    private void handleGlobalMessage(String message) {
        String formatted = org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
        Bukkit.broadcastMessage(formatted);
        plugin.getLogger().info("[Proxy] Global message received: " + message);
    }

    private void handleAchievementBroadcast(String payload) {
        String[] parts = payload.split("\\|", 3);
        if (parts.length < 3) return;
        String playerName = parts[0];
        // UUID ignored for broadcast display — just show the achievement
        String achievementName = parts[2];

        String defaultLang = plugin.getConfigManager().getMainConfig().getString("general.language", "zh");
        org.bukkit.configuration.file.FileConfiguration messages = plugin.getConfigManager().getMessageConfig(defaultLang);
        // R-78（P2 批次·编排者 fan-in）：模板缺失时不再 NPE 半残——两语言都缺才放弃广播。
        String template = messages.getString("dialogue.proxy-achievement");
        if (template == null) {
            template = plugin.getConfigManager().getMessageConfig("zh")
                    .getString("dialogue.proxy-achievement");
            if (template == null) {
                return;
            }
        }
        String msg = template.replace("{achievement}", achievementName).replace("{player}", playerName);
        msg = org.bukkit.ChatColor.translateAlternateColorCodes('&', msg);
        Bukkit.broadcastMessage(msg);
    }
}
