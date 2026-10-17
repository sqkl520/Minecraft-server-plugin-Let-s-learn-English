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

    // ============================================================
    //  赛季子通道（Wave 3 域 5 批次 A，plan-season §3.3）
    // ============================================================

    /**
     * 广播"赛季已结算"（结算服 → 各子服本地发放在线者 + 播报）。
     *
     * <p>payload 格式 {@code {origin}|{seasonId}}：origin = {@code proxy.server-name}
     * （空串 = 未配置），接收端 origin == 本服名时跳过（回环去重）。子通道自
     * 本次新增起即为带 origin 的 v1 协议——无旧格式兼容负担。0 人在线时
     * Forward 静默跳过（既有行为；此时本地也无接收者，语义自洽）。
     */
    public void sendSeasonSettled(int seasonId) {
        sendForward("ALL", "SeasonSettled", serverName() + "|" + seasonId);
    }

    /** 广播"新赛季开始"（同 {@link #sendSeasonSettled} 的 origin 协议）。 */
    public void sendSeasonStarted(int seasonId) {
        sendForward("ALL", "SeasonStarted", serverName() + "|" + seasonId);
    }

    /** {@code proxy.server-name}（本子服名；空 = 单服/未配置——回环去重退化为"可能重复一次"）。 */
    public String serverName() {
        String name = plugin.getConfigManager().getMainConfig()
                .getString("proxy.server-name", "");
        return name == null ? "" : name.trim();
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

        // R-03（低危批次收口）：帧解析抽为纯函数（ParsedFrame）——把 PF-SEA-01 的
        // 修复从"内联在 Bukkit 接口里、单服无法自动验证"变为可单测（ProxyFrameParseTest
        // 覆盖短帧/长帧≥128 字节/截断/垃圾/中文载荷五场景）。非法帧返回 null。
        ParsedFrame frame = parseForwardFrame(message);
        if (frame == null) {
            return; // parseForwardFrame 内已记 fine
        }
        String subChannel = frame.subChannel();
        ByteArrayDataInput payload = ByteStreams.newDataInput(frame.payloadBytes());

        try {
            switch (subChannel) {
                case "GlobalMessage" -> handleGlobalMessage(payload.readUTF());
                case "AchievementUnlock" -> handleAchievementBroadcast(payload.readUTF());
                // 赛季子通道（Wave 3 域 5）：payload = "{origin}|{seasonId}"，
                // origin 过滤防回环重复播报（plan-season §3.3 / R2）
                case "SeasonSettled" -> handleSeasonRelay(payload.readUTF(), true);
                case "SeasonStarted" -> handleSeasonRelay(payload.readUTF(), false);
                default -> {
                    // 忽略未知子通道（可能来自其他插件）
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Malformed proxy payload for subChannel " + subChannel + ": " + e);
        }
    }

    /**
     * R-03：Forward 投递帧的解析结果（子通道 + 载荷字节）。
     */
    record ParsedFrame(String subChannel, byte[] payloadBytes) { }

    /**
     * R-03 / PF-SEA-01：从 BungeeCord/Velocity Forward 投递帧提取子通道与载荷。
     *
     * <p>帧 = {@code [子通道 UTF][short 帧长][载荷字节]}。原实现跳过帧长直接 readUTF
     * ——短消息带 2 字节不可见前缀；长消息（modified-UTF ≥128 字节，约 126 ASCII
     * 字符或 42 个中文字）解码抛 UTFDataFormatException 整条丢失。本函数按官方协议
     * 先消费帧长、readFully 取载荷。非法帧（非本插件格式/被截断/空）返回 {@code null}
     * （调用方静默丢弃并记一次 fine，绝不抛异常）。
     *
     * <p>包私有 static：纯字节逻辑零 Bukkit 依赖，供 ProxyFrameParseTest 单测覆盖。
     */
    static ParsedFrame parseForwardFrame(byte[] message) {
        if (message == null) {
            return null;
        }
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();
            int frameLen = in.readShort() & 0xFFFF;
            byte[] payloadBytes = new byte[frameLen];
            in.readFully(payloadBytes);
            return new ParsedFrame(subChannel, payloadBytes);
        } catch (Exception e) {
            // 非法帧——调用方负责记日志（本函数无 plugin 引用，保持纯净可测）
            return null;
        }
    }

    /**
     * SeasonSettled / SeasonStarted 的 origin 去重与回投。
     *
     * <p>回环语义：Forward "ALL" 是否把消息投回发送服依代理行为而定（R2 未实测），
     * 两种行为下本设计都正确——若回环，origin == 本服名时跳过（发送服已本地播报/
     * 发放过）；若代理不回环，origin 检查恒不命中、无副作用。{@code proxy.server-name}
     * 留空（单服/未配置）时 origin 为 {@code ""}，比较退化为不跳过——配置注释已
     * 说明"可能重复一次"（发放仍受 claimed 条件 UPDATE 幂等保护，只多一次播报）。
     */
    private void handleSeasonRelay(String payload, boolean settled) {
        int sep = payload.indexOf('|');
        if (sep < 0) return;
        String origin = payload.substring(0, sep);
        int seasonId;
        try {
            seasonId = Integer.parseInt(payload.substring(sep + 1).trim());
        } catch (NumberFormatException e) {
            plugin.getLogger().fine("Malformed season relay payload: " + payload);
            return;
        }
        String self = serverName();
        if (!self.isEmpty() && self.equals(origin)) {
            return; // 本服即发送源（回环）：已本地处理过
        }
        var seasonManager = plugin.getSeasonManager();
        if (seasonManager == null) {
            return;
        }
        if (settled) {
            seasonManager.applyRelayedSettlement(seasonId);
        } else {
            seasonManager.applyRelayedSeasonStarted(seasonId);
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
        // R-01 收口（低危批次红队发现）：playerName 来自代理 payload（跨服字节流），
        // 未经清洗直接进广播——恶意/被攻陷的子服可注入颜色码（&c/&k）或换行刷屏
        // 全部收件人。照 ChatAnswerListener.sanitizeBroadcastName 先例清洗。
        String playerName = sanitizeBroadcastName(parts[0]);
        // UUID ignored for broadcast display — just show the achievement
        // 兼容：旧格式 3 段（单名）→ zh = en = parts[2]
        // R-01 配套：成就名同为 payload 来源，同款清洗（防颜色码注入文本内容）
        String zhName = sanitizeBroadcastName(parts[2]);
        String enName = parts.length >= 4 ? sanitizeBroadcastName(parts[3]) : zhName;

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
     * R-01（低危批次红队发现收口）：清洗跨服 payload 携带的文本（玩家名/成就名）。
     * 照 {@code ChatAnswerListener.sanitizeBroadcastName} 同款规则：先去 Bukkit
     * 颜色码（§），再去 & 风格颜色码（模板渲染会二次解析 &），最后截断防超长刷屏；
     * 空结果回落 "?"（占位而非丢广播）。换行符随颜色码剥离后的空白一并去除。
     */
    private static String sanitizeBroadcastName(String name) {
        if (name == null) {
            return "?";
        }
        String clean = org.bukkit.ChatColor.stripColor(name)
                .replaceAll("&[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("[\\r\\n]", " ");
        if (clean.length() > BROADCAST_TEXT_MAX_LENGTH) {
            clean = clean.substring(0, BROADCAST_TEXT_MAX_LENGTH);
        }
        return clean.isEmpty() ? "?" : clean;
    }

    /** 与 ChatAnswerListener.BROADCAST_NAME_MAX_LENGTH 同值（防超长 payload 文本刷屏）。 */
    private static final int BROADCAST_TEXT_MAX_LENGTH = 50;

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
