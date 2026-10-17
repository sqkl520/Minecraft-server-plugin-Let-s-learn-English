package com.letslearnenglish.minecraftplugin.core;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R-03 / PF-SEA-01 回归测试：ProxyMessenger 的 Forward 帧解析纯函数
 * （{@link ProxyMessenger#parseForwardFrame(byte[])}）。
 *
 * <p>把"帧解析修复只能在双服环境验证"变为可在单机自动回归——覆盖原实现
 * 的两个实伤场景（短消息 2 字节前缀 / 长消息 UTFDataFormatException）与
 * 三条防御路径（截断帧 / 垃圾字节 / 中文多字节载荷）。纯字节逻辑零 Bukkit。
 */
class ProxyFrameParseTest {

    /** 构造标准 Forward 投递帧：[子通道 UTF][short 帧长][载荷字节]。 */
    private static byte[] buildFrame(String subChannel, byte[] payload) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(subChannel);
        out.writeShort(payload.length);
        out.write(payload);
        return out.toByteArray();
    }

    private static byte[] utf(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void shortFrameRoundTrips() {
        byte[] payload = utf("hello|world");
        byte[] frame = buildFrame("GlobalMessage", payload);

        ProxyMessenger.ParsedFrame parsed = ProxyMessenger.parseForwardFrame(frame);

        assertNotNull(parsed, "合法短帧必须解析成功");
        assertEquals("GlobalMessage", parsed.subChannel());
        assertArrayEquals(payload, parsed.payloadBytes(), "载荷必须逐位相等（无 2 字节前缀污染）");
    }

    @Test
    void longFrameBeyondModifiedUtfThresholdRoundTrips() {
        // ≥128 字节触发原实现的 UTFDataFormatException 场景（modified-UTF 长解码）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("abcdefghij"); // 3000 字节 ASCII，远超 128
        }
        byte[] payload = utf(sb.toString());
        byte[] frame = buildFrame("AchievementUnlock", payload);

        ProxyMessenger.ParsedFrame parsed = ProxyMessenger.parseForwardFrame(frame);

        assertNotNull(parsed, "长帧（原实伤场景）必须解析成功");
        assertEquals("AchievementUnlock", parsed.subChannel());
        assertArrayEquals(payload, parsed.payloadBytes(), "长载荷必须逐位相等——PF-SEA-01 核心回归");
    }

    @Test
    void truncatedFrameReturnsNull() {
        byte[] payload = utf("some payload that will be truncated");
        byte[] frame = buildFrame("GlobalMessage", payload);
        // 截掉尾部：readShort 声称的长度大于剩余字节 → readFully 抛 EOF
        byte[] truncated = new byte[frame.length - 10];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);

        assertNull(ProxyMessenger.parseForwardFrame(truncated), "截断帧必须返回 null（不抛异常）");
    }

    @Test
    void garbageBytesReturnNull() {
        assertNull(ProxyMessenger.parseForwardFrame(null), "null 输入返回 null");
        assertNull(ProxyMessenger.parseForwardFrame(new byte[0]), "空数组返回 null");
        assertNull(ProxyMessenger.parseForwardFrame(new byte[]{(byte) 0xFF, 0x00, 0x01}),
                "垃圾字节返回 null");
    }

    @Test
    void multibyteChinesePayloadRoundTrips() {
        // 真实协议：payload 内部是本插件自己的 writeUTF 格式（自带 short 长度前缀）
        ByteArrayDataOutput payloadOut = ByteStreams.newDataOutput();
        payloadOut.writeUTF("玩家名|uuid|成就名|Achievement");
        byte[] payload = payloadOut.toByteArray();
        byte[] frame = buildFrame("AchievementUnlock", payload);

        ProxyMessenger.ParsedFrame parsed = ProxyMessenger.parseForwardFrame(frame);

        assertNotNull(parsed);
        assertArrayEquals(payload, parsed.payloadBytes(), "中文多字节载荷必须逐位相等");
        // 反向解码验证：载荷内 writeUTF 可直接 readUTF 还原（调用方消费形态）
        assertEquals("玩家名|uuid|成就名|Achievement",
                ByteStreams.newDataInput(parsed.payloadBytes()).readUTF());
    }
}
