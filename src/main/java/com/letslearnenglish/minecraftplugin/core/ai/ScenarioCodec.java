package com.letslearnenglish.minecraftplugin.core.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Scenario Codec — encodes and decodes AI scenario share codes.
 *
 * Encode: Base64(scenarioName + "\0" + scenarioBody(systemPrompt))
 * Decode: returns a DecodedScenario record (null if malformed)
 */
public class ScenarioCodec {

    /**
     * Encode a scenario name and system prompt into a shareable Base64 string.
     */
    public static String encode(String scenarioName, String systemPrompt) {
        String payload = scenarioName + "\0" + scenarioBody(systemPrompt);
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** R5-06c：剥离 {@link PromptComposer} 外层框架 —— 原样落盘会让下次 compose 再包一层
     *  {@link PromptComposer#SCENARIO_PREFIX}，往返后框架与 RULES_BLOCK 各重复一次、长度近乎翻倍
     *  （实测 283→506），破坏「框架单点生成」前缀契约。无框架的输入原样返回（兼容旧码）。 */
    private static String scenarioBody(String systemPrompt) {
        String scene = "=== SCENE ===\n";
        int i = (systemPrompt == null || !systemPrompt.startsWith(PromptComposer.SCENARIO_PREFIX))
                ? -1 : systemPrompt.indexOf(scene);
        if (i < 0) return systemPrompt;
        String body = systemPrompt.substring(i + scene.length());
        int end = body.indexOf("\n\n=== DIFFICULTY ===\n");
        return (end < 0 ? body : body.substring(0, end)).trim();
    }

    /**
     * Decode a Base64 share code back into name and prompt.
     * Returns null if the Base64 is malformed or missing the null separator.
     */
    public static DecodedScenario decode(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            // slice-06-F5（round1 遗留 LOW，本轮补修）：非法 UTF-8 必须判失败，不能静默替换。
            //
            // 原实现 new String(bytes, UTF_8) 用的是 REPLACE 策略：损坏字节被换成 U+FFFD
            // 后「解码成功」，于是半截/篡改的分享码会被当成合法场景导入并落盘 —— 玩家看到的
            // 是一段带 `�` 的乱码提示词，而不是「分享码已损坏」。  // enc-guard-allow: 描述 U+FFFD 本身
            String payload;
            try {
                payload = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                return null;
            }
            int separatorIdx = payload.indexOf('\0');
            if (separatorIdx <= 0) return null;
            return new DecodedScenario(
                payload.substring(0, separatorIdx),
                payload.substring(separatorIdx + 1)
            );
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record DecodedScenario(String name, String prompt) {}
}
