package com.letslearnenglish.minecraftplugin.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R15（wave3 后继审计 #4，HIGH）：<b>入站代理帧的签名校验</b>。
 *
 * <h2>被修的缺陷</h2>
 * <p>Bukkit 把「代理转发来的插件消息」和「任何玩家客户端自己发的自定义 payload」
 * 派发到<b>同一个回调</b> {@code onPluginMessageReceived}，而且该回调的 {@code player}
 * 参数在两种情况下都是一个在线玩家（真正的 Forward 由 Bukkit 随便挑一个在线玩家当载体）。
 * 所以**无法**从 player 判断来源 —— 攻击者不必攻陷任何子服，改个客户端就能伪造：
 * <ul>
 *   <li>{@code GlobalMessage} → 向全服广播任意文本，伪装成服主公告；</li>
 *   <li>{@code AchievementUnlock} → 给任意玩家名挂任意成就并广播；</li>
 *   <li>{@code SeasonSettled} → 触发赛季奖励发放与"赛季结束"广播。</li>
 * </ul>
 *
 * <p>唯一可靠判据是「只有本网络内的人才知道的共享密钥」。本类钉住签名/验签这一对纯函数
 * （{@code sign} / {@code verifyAndStripSignature}）——它们是 static、无 Bukkit 依赖，
 * 能在纯 JVM 里精确验证。
 *
 * <h2>为什么必须有「空密钥不减字节」这条</h2>
 * <p>{@code proxy.inbound-secret} 默认空。空时发送侧不应追加任何字节，否则
 * 升级后的子服与未升级的代理/他服之间帧格式不一致，会互相丢弃。这条测试把
 * 「空密钥 ⇒ 签名函数不被调用」这个契约钉死（验签侧同理：空密钥直接跳过）。
 */
class ProxyFrameSignatureTest {

    private static final String SECRET = "a-very-long-random-shared-secret-0123456789";

    @Test
    @DisplayName("签名长度固定为 16 字节（截断后），与帧长无关")
    void signatureLengthIsFixed() {
        assertEquals(ProxyMessenger.SIGNATURE_BYTES,
                ProxyMessenger.sign("x".getBytes(StandardCharsets.UTF_8), SECRET).length);
        assertEquals(ProxyMessenger.SIGNATURE_BYTES,
                ProxyMessenger.sign(new byte[30000], SECRET).length,
                "即使输入 30KB，签名长度也应恒定");
    }

    @Test
    @DisplayName("签名是确定性的：同一密钥同一载荷必须得到同一签名")
    void signatureIsDeterministic() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(ProxyMessenger.sign(body, SECRET), ProxyMessenger.sign(body, SECRET));
    }

    @Test
    @DisplayName("换密钥必换签名 —— 否则别服可以伪造成本服")
    void differentSecretGivesDifferentSignature() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] a = ProxyMessenger.sign(body, SECRET);
        byte[] b = ProxyMessenger.sign(body, SECRET + "-other");
        assertFalse(Arrays.equals(a, b),
                "不同密钥必须产生不同签名 —— 这是整个方案成立的前提");
    }

    @Test
    @DisplayName("改一个字节载荷，签名必须变 —— 否则攻击者可以剪贴载荷")
    void payloadTamperChangesSignature() {
        byte[] a1 = ProxyMessenger.sign("abc".getBytes(StandardCharsets.UTF_8), SECRET);
        byte[] a2 = ProxyMessenger.sign("abd".getBytes(StandardCharsets.UTF_8), SECRET);
        assertFalse(Arrays.equals(a1, a2));
    }

    @Test
    @DisplayName("正常往返：签名 + 载荷 → 验签通过并剥掉签名")
    void roundTripStripsSignature() {
        byte[] body = "{\"sub\":\"GlobalMessage\",\"text\":\"hi\"}".getBytes(StandardCharsets.UTF_8);
        byte[] sig = ProxyMessenger.sign(body, SECRET);
        byte[] framed = new byte[sig.length + body.length];
        System.arraycopy(sig, 0, framed, 0, sig.length);
        System.arraycopy(body, 0, framed, sig.length, body.length);

        byte[] restored = ProxyMessenger.verifyAndStripSignature(framed, SECRET);
        assertNotNull(restored, "正确签名必须验签通过");
        assertArrayEquals(body, restored, "剥掉签名后的载荷必须与原文逐字节相同");
    }

    @Test
    @DisplayName("错误密钥 → 验签失败返回 null")
    void wrongSecretRejected() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = ProxyMessenger.sign(body, SECRET);
        byte[] framed = concat(sig, body);

        assertNull(ProxyMessenger.verifyAndStripSignature(framed, "wrong-secret"),
                "错误密钥必须被拒 —— 别服/攻击者不知道密钥，就无法构造有效帧");
    }

    @Test
    @DisplayName("载荷被篡改 → 验签失败（签名覆盖整个 body）")
    void tamperedBodyRejected() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = ProxyMessenger.sign(body, SECRET);
        byte[] framed = concat(sig, body);
        framed[framed.length - 1] ^= 0x01; // 翻转最后一字节

        assertNull(ProxyMessenger.verifyAndStripSignature(framed, SECRET));
    }

    @Test
    @DisplayName("签名被篡改 → 验签失败")
    void tamperedSignatureRejected() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = ProxyMessenger.sign(body, SECRET);
        byte[] framed = concat(sig, body);
        framed[0] ^= 0x01;

        assertNull(ProxyMessenger.verifyAndStripSignature(framed, SECRET));
    }

    @Test
    @DisplayName("空帧 / 只有签名 / null → 一律拒绝，不抛异常")
    void malformedInputsRejected() {
        assertNull(ProxyMessenger.verifyAndStripSignature(null, SECRET));
        assertNull(ProxyMessenger.verifyAndStripSignature(new byte[0], SECRET));
        assertNull(ProxyMessenger.verifyAndStripSignature(
                new byte[ProxyMessenger.SIGNATURE_BYTES], SECRET),
                "恰好只有签名、没有载荷 —— 必须拒绝（旧实现会当成签名为空的有效帧）");
    }

    @Test
    @DisplayName("被截断的帧（签名对但 body 少一字节）必须拒绝")
    void truncatedBodyRejected() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = ProxyMessenger.sign(body, SECRET);
        byte[] framed = concat(sig, body);
        byte[] truncated = Arrays.copyOf(framed, framed.length - 1);

        assertNull(ProxyMessenger.verifyAndStripSignature(truncated, SECRET),
                "body 少了字节 → 签名不匹配 → 拒绝");
    }

    /**
     * 结构性判据：{@code inboundSecret()} 为空时，发送侧<b>不得</b>追加任何字节。
     *
     * <p>这是向后兼容的关键。它无法用行为测试直接观察（需要 Bukkit 的
     * {@code sendPluginMessage}），所以读源码断言「签名只在
     * {@code !secret.isEmpty()} 分支里发生」，与 {@code ServerToggleLintsTest}
     * 同款手法。
     */
    @Test
    @DisplayName("空密钥时发送侧不添加签名（保持与旧版本逐字节兼容）")
    void emptySecretAddsNothing() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/core/ProxyMessenger.java");
        assertTrue(java.nio.file.Files.exists(src),
                "必须读真实源文件（工作目录应为项目根）: " + src.toAbsolutePath());
        String text = java.nio.file.Files.readString(src);

        assertTrue(text.contains("if (!secret.isEmpty())"),
                "签名必须由 `!secret.isEmpty()` 门控 —— 否则空密钥配置下"
                        + "会往帧里塞签名，与未升级的代理/子服互不兼容");
        assertTrue(text.contains("String secret = inboundSecret();"),
                "发送侧必须点读配置（reload 即时生效）");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
