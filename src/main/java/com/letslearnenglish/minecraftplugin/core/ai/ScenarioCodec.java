package com.letslearnenglish.minecraftplugin.core.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Scenario Codec — encodes and decodes AI scenario share codes.
 *
 * Encode: Base64(scenarioName + "\0" + systemPrompt)
 * Decode: returns a DecodedScenario record (null if malformed)
 */
public class ScenarioCodec {

    /**
     * Encode a scenario name and system prompt into a shareable Base64 string.
     */
    public static String encode(String scenarioName, String systemPrompt) {
        String payload = scenarioName + "\0" + systemPrompt;
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode a Base64 share code back into name and prompt.
     * Returns null if the Base64 is malformed or missing the null separator.
     */
    public static DecodedScenario decode(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            String payload = new String(bytes, StandardCharsets.UTF_8);
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
