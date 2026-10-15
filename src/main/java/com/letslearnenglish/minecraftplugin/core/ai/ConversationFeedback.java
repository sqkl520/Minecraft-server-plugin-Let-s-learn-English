package com.letslearnenglish.minecraftplugin.core.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * US-FB §3.2：AI 会话总结结果 —— 纯逻辑 DTO + 防御式解析（US-FB 1b 冻结消费）。
 *
 * <p>模型输出不可信：{@link #parse(String)} 先做 salvage（去 markdown 围栏 + 取第一个
 * {@code {} 到最后一个 {@code }} 的子串），再逐字段防御读取（缺失/类型错 → 该字段降级
 * 为空），最后施加服务端硬上限（无论模型输出什么）并清洗控制字符与 {@code §}。
 *
 * <p>约定（§3.2）：
 * <ul>
 *   <li>上限：{@code comment} ≤500 字符；{@code corrections} ≤5 条、每字段 ≤200 字符；
 *       {@code words} ≤8 个、每词 ≤40 字符</li>
 *   <li>清洗：与 {@code AIChatManager.sanitizeInput} 同款控制字符正则 + {@code §} 剔除；
 *       {@code &} 保留（与既有"AI 回复原文经 translateAlternateColorCodes"姿态一致）</li>
 *   <li>任一步彻底失败（null/空输入/无 JSON 对象）→ 返回 {@code null} → 上层按
 *       "解析失败"降级（发 ai-failed，本地行留存）。<b>永不抛异常</b></li>
 *   <li>不要求插件的 {@code response_format}</li>
 * </ul>
 */
public record ConversationFeedback(String comment, List<Correction> corrections, List<String> words) {

    /** 单条纠错：原句 / 修正 / 简短注记（note 用玩家语言，original/corrected 恒为英文）。 */
    public record Correction(String original, String corrected, String note) { }

    private static final int MAX_COMMENT_CHARS = 500;
    private static final int MAX_CORRECTIONS = 5;
    private static final int MAX_CORRECTION_FIELD_CHARS = 200;
    private static final int MAX_WORDS = 8;
    private static final int MAX_WORD_CHARS = 40;

    /** 与 AIChatManager.sanitizeInput 同款：剔除控制字符（保留 \t \n \r）。 */
    private static final String CONTROL_CHARS_REGEX = "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]";
    /** § 颜色控制符单独剔除（玩家可见消息会再经 translateAlternateColorCodes）。 */
    private static final char SECTION_SIGN = '§';

    /**
     * §3.2：解析 AI 总结原始输出。永不抛异常。
     *
     * @param raw API 返回的 {@code choices[0].message.content} 原文
     * @return 解析结果（字段可降级为空）；无法得到 JSON 对象时返回 {@code null}
     */
    public static ConversationFeedback parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String jsonText = salvageJsonObject(raw);
            if (jsonText == null) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(jsonText);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();

            String comment = cap(clean(optString(obj, "comment")), MAX_COMMENT_CHARS);
            List<Correction> corrections = parseCorrections(obj.get("corrections"));
            List<String> words = parseWords(obj.get("words"));
            return new ConversationFeedback(comment, corrections, words);
        } catch (Exception e) {
            // 防御底线：无论出现何种 Gson / 类型异常都降级为"解析失败"。
            return null;
        }
    }

    /**
     * §3.2 规则 1：去 markdown 围栏（```json / ```）；取第一个 '{' 到最后一个 '}'
     * 的子串（salvage，模型偶发前后加话术）。找不到成对花括号时返回 null。
     */
    private static String salvageJsonObject(String raw) {
        String text = raw.trim();
        if (text.contains("```")) {
            text = text.replace("```json", "").replace("```", "");
        }
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first < 0 || last <= first) {
            return null;
        }
        return text.substring(first, last + 1);
    }

    /** §3.2 规则 2：防御读取 corrections —— 非数组 → 空；元素非对象/全空 → 跳过；上限 5 条。 */
    private static List<Correction> parseCorrections(JsonElement element) {
        List<Correction> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return result;
        }
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (result.size() >= MAX_CORRECTIONS) {
                break;
            }
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject entry = item.getAsJsonObject();
            String original = cap(clean(optString(entry, "original")), MAX_CORRECTION_FIELD_CHARS);
            String corrected = cap(clean(optString(entry, "corrected")), MAX_CORRECTION_FIELD_CHARS);
            String note = cap(clean(optString(entry, "note")), MAX_CORRECTION_FIELD_CHARS);
            if (original.isEmpty() && corrected.isEmpty() && note.isEmpty()) {
                continue; // 全空条目无展示价值，视作降级丢弃
            }
            result.add(new Correction(original, corrected, note));
        }
        return result;
    }

    /** §3.2 规则 2：防御读取 words —— 非数组 → 空；非字符串/空串 → 跳过；上限 8 个。 */
    private static List<String> parseWords(JsonElement element) {
        List<String> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return result;
        }
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (result.size() >= MAX_WORDS) {
                break;
            }
            String word = cap(clean(asJsonString(item)), MAX_WORD_CHARS);
            if (!word.isEmpty()) {
                result.add(word);
            }
        }
        return result;
    }

    /** 防御读取：仅接受 JSON 字符串；对象/数组/数字/布尔/null 一律视作缺失（类型错降级）。 */
    private static String optString(JsonObject obj, String key) {
        return asJsonString(obj.get(key));
    }

    private static String asJsonString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    /** §3.2 规则 4：清洗 —— 控制字符 + {@code §} 剔除，随后 trim（null/空安全）。 */
    private static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = text.replaceAll(CONTROL_CHARS_REGEX, "").replace(String.valueOf(SECTION_SIGN), "");
        return cleaned.trim();
    }

    /** §3.2 规则 3：服务端硬上限裁剪（在清洗之后、再次 trim，防截断尾部留空白）。 */
    private static String cap(String text, int max) {
        if (text == null) {
            return "";
        }
        String capped = (text.length() > max) ? text.substring(0, max) : text;
        return capped.trim();
    }
}
