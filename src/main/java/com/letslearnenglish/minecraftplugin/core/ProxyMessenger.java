package com.letslearnenglish.minecraftplugin.core;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** 插件消息通道安全上限（字节）。Minecraft 网络层单包 32767 字节含通道名/
     *  子通道/长度前缀等开销，载荷保守上限取 30000（R9-H3/R7-H1）。计长口径为
     *  writeUTF（modified-UTF-8），见 {@link #truncateUtf8}——R5-CON-01。
     *  public：AdminCommand.handleBroadcast 命令层拒绝复用同一上限（单源）。 */
    public static final int MAX_PAYLOAD_BYTES = 30000;

    private final LetsLearnEnglish plugin;
    private volatile boolean registered;
    private final AtomicBoolean malformedFrameWarned = new AtomicBoolean();

    public ProxyMessenger(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册插件消息通道。
     * 如果当前没有在线玩家（即无代理连接），静默跳过不做任何操作，
     * 避免在单服测试环境输出无意义报错。
     *
     * <p>R-16（低危积压 FIX 2）：<b>{@code synchronized} —— 这段是未受保护的
     * check-then-act</b>。今天唯一的调用点是 {@code LetsLearnEnglish#onEnable}（主线程），
     * 所以只是<b>潜在</b>缺陷；但一旦将来有人从异步任务（热重载、按需重连）里再叫一次，
     * 两个线程能同时越过 {@code if (registered)} 各自走完两条注册调用 ——
     * 而<b>重复注册不可恢复</b>：Bukkit 的 Messenger 对通道只按「插件名 + 通道名」记账，
     * 没有「按 listener 撤掉某个入站回调」的接口，{@link #unregister()} 会把整条通道连
     * 两次注册的引用一起摘掉。
     *
     * <p>为什么用 {@code synchronized} 而不是 {@code AtomicBoolean} CAS：
     * 临界区包含<b>两步</b> Bukkit 调用（outgoing + incoming），CAS 只能保护那个布尔值，
     * 保护不了这两步之间的窗口 —— 第二个线程会看到 CAS 已占用、于是静默跳过，
     * 而它本该在第一步完成之后再判断；更糟的是 {@link #unregister()} 是同一份状态的反向
     * 组合（先撤两条通道、再落标志），CAS 无法让 register/unregister 互相排斥。
     * 这里锁的是「注册/注销这一对动作」而不是「标志位」，所以两个方法共用一个对象监视器。
     * 这不是热路径（一个 JVM 生命周期里几次调用），锁开销无意义。
     */
    public synchronized void register() {
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

    /** 与 {@link #register()} 同一把锁：注销是注册的镜像 check-then-act，必须互相排斥。 */
    public synchronized void unregister() {
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
    /**
     * R15（wave3 后继审计 #4，MEDIUM）：<b>返回是否真的发出去了</b>。
     *
     * <p>原实现是 {@code void}，而 {@code sendForward} 在「无在线玩家可当信使」和
     * 「通道未注册」两种情况下静默 {@code return}（只记一条 FINE）。于是
     * {@code /lea broadcast} 在空服上会对管理员说「已发送」，消息其实被丢掉了 ——
     * 与类 javadoc 里点名的「截断路径」是同一类谎报，只是没被一起修。
     *
     * <p>返回值语义：{@code true} = 确实交给了 {@code sendPluginMessage}；
     * {@code false} = 没送出去（无信使 / 未注册 / 发送抛异常）。调用方据此决定文案。
     */
    public boolean sendGlobalMessage(String message) {
        return sendForward("ALL", "GlobalMessage", message);
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
     * @return {@code true} = 确实交给了 {@code sendPluginMessage}；
     *         {@code false} = 没送出去（null 载荷 / 未注册 / 无在线信使 / 发送抛异常）
     */
    private boolean sendForward(String targetServer, String subChannel, String data) {
        if (data == null) return false;

        // R9-H3/R7-H1 纵深防御：命令层已拒绝超长（AdminCommand.handleBroadcast），
        // 此处兜底截断防 writeUTF/网络层 32767 异常逃逸（仅防御性路径可达）。
        // R5-CON-01：truncateUtf8 自带 writeUTF 口径计长并幂等，超限才截断。
        String truncated = truncateUtf8(data, MAX_PAYLOAD_BYTES);
        if (!truncated.equals(data)) {
            plugin.getLogger().warning("Proxy payload truncated (> " + MAX_PAYLOAD_BYTES + " bytes)");
            data = truncated;
        }

        if (!registered) return false;

        // 需要至少一个在线玩家作为"信使"来发送 PluginMessage
        Player messenger = getAnyOnlinePlayer();
        if (messenger == null) {
            // R15：这一条原为 FINE（几乎不可见）。空服上执行 /lea broadcast 时，
            // 管理员会收到「已发送」而消息其实被丢弃。现在 WARNING 可见，
            // 且返回值让命令层能给出正确文案。
            plugin.getLogger().warning("Proxy message NOT sent: no online player available"
                    + " to act as the messenger (subChannel=" + subChannel + ")."
                    + " The message was discarded, not queued.");
            return false;
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

        byte[] frame = out.toByteArray();

        // R15：配置了 proxy.inbound-secret 时，在整帧前追加 HMAC 签名。
        // 接收侧（本类的 onPluginMessageReceived）在验签通过后才解析帧，因此
        // 客户端伪造的、不带签名的帧会在解析前就被丢弃。
        // 空密钥 = 不签名，与加此功能前的字节流**完全一致**（向后兼容单服/局域网）。
        String secret = inboundSecret();
        if (!secret.isEmpty()) {
            byte[] sig = sign(frame, secret);
            byte[] signed = new byte[sig.length + frame.length];
            System.arraycopy(sig, 0, signed, 0, sig.length);
            System.arraycopy(frame, 0, signed, sig.length, frame.length);
            frame = signed;
        }

        // R9-H3：网络层单包超限等异常不再逃逸到命令层（防刷栈/踢信使玩家）
        try {
            messenger.sendPluginMessage(plugin, CHANNEL, frame);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to send proxy message", e);
            return false;
        }
    }

    /** R9-H3/R7-H1：按 writeUTF 字节数截断至 ≤ maxBytes；未超限原样返回（幂等）。
     *  R5-CON-01：计长必须与封包同单位——modified-UTF-8（0x0001-0x7F→1，0x0000 与
     *  0x0080-0x07FF→2，其余含每个 surrogate 单独计→3）。旧口径 getBytes(UTF_8) 对
     *  BMP 外字符算 4、lone surrogate 算 1，少算致 emoji 穿透闸门（30000→45002）。 */
    private static String truncateUtf8(String s, int maxBytes) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int b = (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF ? 2 : 3);
            if (n + b > maxBytes) return s.substring(0, i);
            n += b;
        }
        return s;
    }

    /**
     * slice-03-contract-r5-con-01（round1 遗留 MEDIUM，round10 补修）：**唯一**的载荷超限判定。
     *
     * <p><b>为什么要这个公开方法</b>：`MAX_PAYLOAD_BYTES` 有两道闸门 ——
     * <ol>
     *   <li>命令层（{@code AdminCommand}）前置拒绝，避免超长广播进入代理层；</li>
     *   <li>发送层（{@link #truncateUtf8}）兜底截断。</li>
     * </ol>
     * 原实现里两道闸门用的是**不同的字节口径**：命令层用
     * {@code getBytes(StandardCharsets.UTF_8).length}（BMP 外字符 4 字节、孤立 surrogate 1 字节），
     * 发送层用 {@link #truncateUtf8} 的 modified-UTF-8（每个 surrogate 单独计 3 字节）。
     *
     * <p>后果：一个带 emoji 的广播在命令层**算小**（4 字节/字符）从而通过，到发送层
     * **算大**（6 字节/字符）被静默截断 —— 管理员收到了「发送成功」，玩家看到的却是半截。
     * 这正是 `round5` 修好了第一道闸门、却漏了第二道的那条。
     *
     * <p>现在把判定收口到本方法：与 {@link #truncateUtf8} **共用同一套计长逻辑**
     * （{@code truncateUtf8(s, MAX).length() != s.length()} 即超限），调用方不再自己算字节。
     *
     * @param payload 待发送的完整载荷（含前缀）
     * @return true = 超过 {@link #MAX_PAYLOAD_BYTES}，调用方应拒绝而不是让它被静默截断
     */
    public static boolean exceedsPayloadLimit(String payload) {
        if (payload == null) return false;
        return truncateUtf8(payload, MAX_PAYLOAD_BYTES).length() != payload.length();
    }

    private Player getAnyOnlinePlayer() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    // ============================================================
    //  R15：入站帧签名（见 onPluginMessageReceived 的注释）
    // ============================================================

    /** HMAC 签名长度（SHA-256 截断到 16 字节，够用且让帧体积可控）。 */
    static final int SIGNATURE_BYTES = 16;

    /** 只有本 JVM 才需要的告警去重：签名失败每条只记一次 WARNING。 */
    private final java.util.concurrent.atomic.AtomicBoolean unsignedFrameWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 读取 {@code proxy.inbound-secret}；每次调用都点读配置，故 {@code /lea reload} 即时生效。
     *
     * @return 去空白后的密钥；未配置返回空串（= 不校验签名）
     */
    private String inboundSecret() {
        String s = plugin.getConfigManager().getMainConfig()
                .getString("proxy.inbound-secret", "");
        return s == null ? "" : s.trim();
    }

    /**
     * 计算一段字节的 HMAC-SHA256 截断签名。
     *
     * <p>用 HMAC 而不是「secret + payload 的 SHA256」：后者存在长度扩展攻击，且
     * 在密钥与消息拼接边界不清晰时会给出可伪造的碰撞。
     */
    static byte[] sign(byte[] payload, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] full = mac.doFinal(payload);
            return java.util.Arrays.copyOf(full, SIGNATURE_BYTES);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            // HmacSHA256 是 JDK 必备算法；走到这里说明 JRE 被裁剪过。
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * 校验并剥掉签名头。
     *
     * @param message 收到的完整载荷（前 {@value #SIGNATURE_BYTES} 字节为签名）
     * @param secret  共享密钥
     * @return 去掉签名后的真实载荷；签名缺失/错误返回 {@code null}
     */
    static byte[] verifyAndStripSignature(byte[] message, String secret) {
        if (message == null || message.length <= SIGNATURE_BYTES) {
            return null;
        }
        byte[] sig = java.util.Arrays.copyOf(message, SIGNATURE_BYTES);
        byte[] body = java.util.Arrays.copyOfRange(message, SIGNATURE_BYTES, message.length);
        // 恒定时间比较：签名虽非密码，但没有理由给时序侧信道留口子（与本仓
        // StatisticsHttpServer 的 MessageDigest.isEqual 同口径）。
        return java.security.MessageDigest.isEqual(sig, sign(body, secret)) ? body : null;
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

        // R15（wave3 后继审计 #4，HIGH —— 伪造帧）：**来源校验**。
        //
        // 问题本质：Bukkit 把「代理转发来的插件消息」与「任何玩家客户端自己发的
        // 自定义 payload」派发到**同一个回调**，且本回调的 `player` 参数在两种情况下
        // 都是一个在线玩家（真正的 Forward 由 Bukkit 随便挑一个在线玩家作为载体）。
        // 因此**无法**从 player 参数判断来源 —— 攻击者不需要攻陷任何子服，改个客户端
        // 就能伪造 GlobalMessage / AchievementUnlock / SeasonSettled。
        //
        // 唯一可靠的判据是「只有本网络内的人才知道的共享密钥」：发送侧在载荷前追加
        // HMAC-SHA256，接收侧验证。`proxy.inbound-secret` 为空时跳过校验，保持与本键
        // 加入前完全一致的行为（单服/局域网开箱可用）；一旦配置，**所有**入站帧都必须
        // 带正确签名。
        String secret = inboundSecret();
        byte[] payloadBytes = message;
        if (!secret.isEmpty()) {
            payloadBytes = verifyAndStripSignature(message, secret);
            if (payloadBytes == null) {
                // 签名缺失/错误 —— 可能是伪造帧，也可能是对端没配同一个密钥。
                // 两种都只记一条（防刷屏），并丢弃。
                if (unsignedFrameWarned.compareAndSet(false, true)) {
                    plugin.getLogger().warning("Proxy frame REJECTED: missing or invalid signature."
                            + " Check that every backend server has the same proxy.inbound-secret."
                            + " (This warning prints once per JVM; further rejects log at FINE.)");
                } else {
                    plugin.getLogger().fine("Proxy frame rejected (bad signature).");
                }
                return;
            }
        }

        // R-03（低危批次收口）：帧解析抽为纯函数（ParsedFrame）——把 PF-SEA-01 的
        // 修复从"内联在 Bukkit 接口里、单服无法自动验证"变为可单测（ProxyFrameParseTest
        // 覆盖短帧/长帧≥128 字节/截断/垃圾/中文载荷五场景）。非法帧返回 null。
        ParsedFrame frame = parseForwardFrame(payloadBytes);
        if (frame == null) {
            String detail = "Proxy frame parse failed — malformed Forward frame dropped ("
                    + (message == null ? "null" : message.length + " bytes") + ")";
            if (malformedFrameWarned.compareAndSet(false, true)) plugin.getLogger().warning(detail);
            else plugin.getLogger().fine(detail);
            return;
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
     * （调用方 onPluginMessageReceived 记诊断，绝不抛异常）。
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
        // SEC-01：与 AchievementUnlock 同一不可认证的跨服信任边界，照其规则净化。
        String safe = sanitizeBroadcastText(message, MAX_PAYLOAD_BYTES);
        if (safe.isBlank()) return;
        Bukkit.broadcastMessage(safe);
        plugin.getLogger().info("[Proxy] Global message received: " + safe);
    }

    /** SEC-01：剥 & 码、§ 格式码、换行、超长。保留 § 颜色码——AdminCommand 已把前缀翻成 §。 */
    static String sanitizeBroadcastText(String text, int maxBytes) {
        if (text == null) return "";
        String clean = text.replaceAll("&[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("§[k-orK-OR]", "").replaceAll("[\\r\\n]", " ");
        return truncateUtf8(clean, maxBytes);
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
        // R14（wave-3 锐评 GAP-2 同族）：`lang` 可能来自玩家语言原值（"ZH"/"cn"/"zh_CN"），
        // 直接拼 `prefix.` 会查一个不存在的键 → 静默丢掉服主在 config.yml 配的前缀。
        // 归一化后再拼，且 getMessageConfig 本身也已按 canonical 值选文件。
        String canonical = com.letslearnenglish.minecraftplugin.config.ConfigManager
                .canonicalLanguage(lang);
        String prefix = plugin.getConfigManager().getMainConfig().getString("prefix." + canonical);
        if (prefix != null && !prefix.isEmpty()) {
            return prefix;
        }
        String fallback = plugin.getConfigManager().getMessageConfig(canonical).getString("prefix");
        return fallback != null ? fallback : "";
    }
}
