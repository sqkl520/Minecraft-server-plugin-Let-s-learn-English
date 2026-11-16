package com.letslearnenglish.minecraftplugin.config;

import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14：语言标识归一化（{@code ConfigManager.canonicalLanguage}）的回归测试。
 *
 * <p><b>缺陷形态</b>：{@code ConfigManager.getMessageConfig(String)} 原来用精确的
 * {@code "zh".equals(lang)}，而另外 9 处按语言取配置的地方全都用 {@code equalsIgnoreCase}。
 * 于是 {@code player_lang.yml} 里写一个 {@code ZH}，聊天与 GUI 走英文、成就名与场景名走中文
 * —— 同一个玩家屏幕上两种语言，且<b>零日志</b>说明原因。
 * 同理 {@code general.language: cn}（中文用户的高频笔误）会让全服静默变英文。
 *
 * <p><b>为什么必须钉住「未知值回落 zh 且报告未识别」两件事一起</b>：只钉回落，等于允许
 * 实现把 {@code fr} 悄悄当成 {@code zh} 且不告警 —— 那还是静默失败，只是换了个方向；
 * 只钉告警，则服主可能被一个「告警了但仍然给错语言」的实现困住。
 */
class LanguageCanonicalizationTest {

    @ParameterizedTest(name = "canonicalLanguage(\"{0}\") == \"zh\"")
    @ValueSource(strings = {"zh", "ZH", "Zh", " zh ", "\tzh\n", "zh_CN", "zh-CN", "zh_cn",
            "zh_TW", "zh-Hans", "cn", "CN", "CN_hans"})
    @DisplayName("中文的各种写法都归一到 zh")
    void chineseVariants(String raw) {
        assertEquals("zh", ConfigManager.canonicalLanguage(raw));
        assertTrue(ConfigManager.isZh(raw));
    }

    @ParameterizedTest(name = "canonicalLanguage(\"{0}\") == \"en\"")
    @ValueSource(strings = {"en", "EN", "En", " en ", "\ten\n", "en_US", "en-GB", "en_gb",
            "en_GB"})
    @DisplayName("英文的各种写法都归一到 en")
    void englishVariants(String raw) {
        assertEquals("en", ConfigManager.canonicalLanguage(raw));
        assertFalse(ConfigManager.isZh(raw));
    }

    /**
     * <b>实现是前缀规则，不是词表匹配</b>（{@code ConfigManager.mapLanguageToken}）：
     * {@code zh}/{@code cn}/{@code zh_*} 归 zh，{@code en}/{@code en_*} 归 en，其余一律未知。
     * 因此 {@code chinese} / {@code english} / {@code zheng} 这类**自然语言单词**不是合法标签
     * —— 它们不以前缀形式出现，会走"未知 → 回落 zh + 告警"。
     *
     * <p>这条断言是刻意钉住边界的：如果哪天有人把匹配从 {@code startsWith} 放宽成
     * {@code contains}，{@code zheng} 会突然变成 zh 且不再告警，而 {@code english} 仍不会
     * 变成 en —— 那时这条测试会红，逼人回来重新判断。
     */
    @Test
    @DisplayName("自然语言单词不是合法标签：english/chinese/zheng 一律未知")
    void wordsAreNotTags() {
        for (String raw : new String[]{"english", "ENGLISH", "chinese", "zheng", "eng"}) {
            assertFalse(ConfigManager.isRecognizedLanguage(raw),
                    "自然语言单词不应被当作语言标签: " + raw);
            assertEquals("zh", ConfigManager.canonicalLanguage(raw),
                    "未知值仍须回落 zh（绝不返回 null / 原值）: " + raw);
        }
    }

    @Test
    @DisplayName("null 与未知值一律回落 zh，但 isRecognizedLanguage 报 false")
    void unknownFallsBackButIsReported() {
        for (String raw : new String[]{null, "", "  ", "fr", "de", "jp", "xx", "zheng"}) {
            assertEquals("zh", ConfigManager.canonicalLanguage(raw),
                    "未知/空值必须回落 zh，而不是 null 或原值：raw=" + raw);
            assertFalse(ConfigManager.isRecognizedLanguage(raw),
                    "未知/空值必须报告「未识别」，否则回落是静默的：raw=" + raw);
        }
    }

    @ParameterizedTest(name = "\"{0}\" 被识别")
    @ValueSource(strings = {"zh", "ZH", "en", "EN", "zh_CN", "en_US"})
    @DisplayName("可识别的取值 isRecognizedLanguage == true")
    void recognized(String raw) {
        assertTrue(ConfigManager.isRecognizedLanguage(raw), "应被识别: " + raw);
    }

    @Test
    @DisplayName("canonicalLanguage 永不返回 null —— null 会让 getMessageConfig 取到 null 配置")
    void neverReturnsNull() {
        assertFalse(ConfigManager.canonicalLanguage(null) == null);
        assertFalse(ConfigManager.canonicalLanguage("") == null);
        assertFalse(ConfigManager.canonicalLanguage("完全不是语言") == null);
    }

    @Test
    @DisplayName("isZh 与 canonicalLanguage 必须同源（不能各自实现一套）")
    void isZhAgreesWithCanonical() {
        for (String raw : new String[]{"zh", "ZH", " zh ", "zh_CN", "cn", "en", "EN", "fr",
                null, "", "中文"}) {
            boolean expected = "zh".equals(ConfigManager.canonicalLanguage(raw));
            assertEquals(expected, ConfigManager.isZh(raw), "isZh 与 canonicalLanguage 不一致: " + raw);
        }
    }

    @ParameterizedTest(name = "幂等：canonicalLanguage(canonicalLanguage(\"{0}\")) 不变")
    @NullAndEmptySource
    @CsvSource({"ZH", " zh ", "zh_CN", "cn", "EN", "en_gb", "fr", "zheng"})
    @DisplayName("归一化必须幂等 —— 否则两次读同一份配置会得到不同语言")
    void idempotent(String raw) {
        String once = ConfigManager.canonicalLanguage(raw);
        assertEquals(once, ConfigManager.canonicalLanguage(once),
                "二次归一化改变了结果: raw=" + raw + " once=" + once);
    }
}
