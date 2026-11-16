package com.letslearnenglish.minecraftplugin.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * slice-06-F6（round1 遗留 LOW）：TTS 缓存文件名必须是<b>单射</b>。
 *
 * <p>原实现 {@code word.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase()} 不是单射：
 * {@code "a b"} 与 {@code "a_b"} 都折叠成 {@code a_b}，{@code "well-known"} 与
 * {@code "well_known"} 都折叠成 {@code well_known}。缓存命中后玩家听到的是<b>别的词</b>
 * 的发音，而且因为命中缓存，这个错误会长期固着。
 *
 * <p>本测试直接调生产代码的 {@code sanitizeFileName}（反射，方法为 private），
 * 不复制一份逻辑 —— 复制的测试是自证，生产代码改坏了它照样绿。
 *
 * <p><b>变异检验</b>：把方法改回原来的单行实现，{@link #collidingPairsGetDifferentNames}
 * 立刻变红。
 */
@DisplayName("TtsManagerTest — TTS 缓存文件名单射性")
class TtsManagerCacheKeyTest {

    private static String sanitize(String word) throws Exception {
        Method m = TtsManager.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        // sanitizeFileName 不碰实例字段，用 Objenesis 分配一个未初始化实例即可，
        // 避免为了一个纯字符串函数去搭完整的 Bukkit 插件 mock。
        Class<?> objenesis = Class.forName("org.objenesis.ObjenesisStd");
        Object ob = objenesis.getDeclaredConstructor().newInstance();
        Object inst = objenesis.getMethod("newInstance", Class.class).invoke(ob, TtsManager.class);
        return (String) m.invoke(inst, word);
    }

    @Test
    @DisplayName("F6 — 折叠后相同的词对必须得到不同的文件名")
    void collidingPairsGetDifferentNames() throws Exception {
        String[][] pairs = {
                {"a b", "a_b"},
                {"well-known", "well_known"},
                {"hello world", "hello_world"},
                {"x.y", "x_y"},
                {"A/B", "A_B"},
        };
        for (String[] p : pairs) {
            String n1 = sanitize(p[0]);
            String n2 = sanitize(p[1]);
            assertNotEquals(n1, n2,
                    "「" + p[0] + "」与「" + p[1] + "」折叠到同一文件名 " + n1
                            + " —— 缓存命中会返回别的词的音频，且因命中而长期固着");
        }
    }

    @Test
    @DisplayName("同一单词必须稳定得到同一文件名（否则缓存永不命中）")
    void sameWordIsStable() throws Exception {
        assertEquals(sanitize("apple"), sanitize("apple"),
                "同一单词两次调用得到不同文件名 —— 缓存将永远无法命中，每次都重新合成");
    }

    @Test
    @DisplayName("文件名保留可读主干，便于排障")
    void keepsReadableStem() throws Exception {
        String name = sanitize("apple");
        org.junit.jupiter.api.Assertions.assertTrue(name.startsWith("apple"),
                "文件名应以可读主干开头（排障时能看出是哪个词），实际: " + name);
    }

    @Test
    @DisplayName("不含非法文件名字符（否则写入会失败）")
    void hasNoIllegalFileNameChars() throws Exception {
        for (String w : new String[]{"a b", "a/b", "a:b", "a*b", "a?b", "a|b", "a\"b", "a<b>c"}) {
            String name = sanitize(w);
            org.junit.jupiter.api.Assertions.assertFalse(
                    name.matches(".*[\\\\/:*?\"<>|].*"),
                    "「" + w + "」的文件名含非法字符: " + name);
        }
    }
}
