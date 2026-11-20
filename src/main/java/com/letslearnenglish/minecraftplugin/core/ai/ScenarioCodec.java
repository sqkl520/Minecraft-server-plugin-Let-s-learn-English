package com.letslearnenglish.minecraftplugin.core.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Scenario Codec — encodes and decodes AI scenario share codes.
 *
 * Encode: Base64(scenarioName + "\0" + scenarioBody(systemPrompt))
 * Decode: returns a DecodedScenario record (null if malformed)
 *
 * <h2>线格式是<b>冻结</b>的（R23-D2/D3 未改分隔符方案，原因见下）</h2>
 * <p>分享码会被玩家抄在告示牌、QQ 群、Discord 里长期保存，<b>每一枚已发出去的码</b>
 * 都是 {@code Base64(名字 + '\0' + 正文)}。所以本轮修 R23-D2/D3 时刻意采用
 * <b>「编码侧拒绝、解码侧一字不动」</b>的方向：过去能解的码今天逐字节解出同样的结果，
 * 只是编码器不再铸造出会误解的新码。</p>
 *
 * <p>换成转义方案或长度前缀（{@code DatabaseManager#computeNodeId} 那种单射编码）
 * 在库层面更干净，但会让<b>所有存量分享码失效</b> —— 玩家手上那串码导入时报
 * 「分享码已损坏」。除非同时实现「先试新格式、失败回退旧格式」的双读，否则不可接受；
 * 而为一个上游正则已经挡死的 LOW 缺陷引入双格式，风险远大于收益。</p>
 *
 * <h2>分隔符 {@code \0} 的契约（不对称，但明确）</h2>
 * <ul>
 *   <li><b>名字里不许有 {@code \0}</b>：它是分隔符本身，名字含它会被解码器从第一个
 *       {@code \0} 处切开（R23-D2，静默截断）。编码器现在直接拒绝。</li>
 *   <li><b>正文里可以有 {@code \0}</b>：解码只切第一个 {@code \0}，其后全部归正文，
 *       往返无损。这条不对称是刻意的，别「顺手统一」把正文也禁掉。</li>
 *   <li><b>名字不许为空</b>：空名字的码解码侧恒判失败（{@code separatorIdx == 0}），
 *       即编码器能铸出一枚自己解不开的废码（R23-D3）。编码器现在直接拒绝。</li>
 * </ul>
 */
public class ScenarioCodec {

    /** 名字与正文之间的分隔符。改动它 = 让全部存量分享码失效，见类注释。 */
    private static final char SEPARATOR = '\0';

    /**
     * Encode a scenario name and system prompt into a shareable Base64 string.
     *
     * @throws IllegalArgumentException 名字为空串或含分隔符 {@code \0}（R23-D2 / R23-D3）。
     *         这两种输入都<b>必然</b>被 {@link #decode} 解成别的场景（截断 / 判失败），
     *         与其产出一枚「看起来成功、导入时炸」的废码，不如在铸造点就拒绝。
     *         {@code null} 名字不在拒绝之列：字符串拼接把它渲染成字面量 {@code "null"}，
     *         往返得到的就是一个名字叫 {@code null} 的场景，无损，属既有的退化契约。
     */
    public static String encode(String scenarioName, String systemPrompt) {
        requireEncodableName(scenarioName);
        String payload = scenarioName + SEPARATOR + scenarioBody(systemPrompt);
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * R23-D2 / R23-D3：让「编码器能铸、解码器不认」这条缝闭合。
     *
     * <p><b>可达性诚实的说法</b>：线上进不来。唯一的编码调用点是
     * {@code LearnCommand#handleSaveScenarioCommand}，名字取自 {@code args[1]} 并先过
     * {@code matches("[\\u4e00-\\u9fa5a-zA-Z0-9_]+")} —— 该正则既不接受 {@code \0}
     * 也不接受空串（{@code +} 要求至少一字符），Minecraft 聊天/命令本身也传不了 NUL。
     * 所以这是<b>库层契约缺口</b>而非线上 bug；但「编解码互逆」是分享码的核心不变量，
     * 靠调用方恰好做过正则来维持太脆，故在库层补上。</p>
     */
    private static void requireEncodableName(String scenarioName) {
        if (scenarioName == null) return;
        if (scenarioName.isEmpty()) {
            throw new IllegalArgumentException(
                    "scenarioName must not be empty: decode() rejects a code whose name is empty"
                            + " (separatorIdx == 0), so such a code could never be imported");
        }
        if (scenarioName.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException(
                    "scenarioName must not contain the NUL separator (U+0000): decode() splits at the"
                            + " first NUL, so the name would be silently truncated");
        }
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
     *
     * <p><b>R23-D4</b>：{@code null} 入参同样返回 {@code null} 而不是抛 NPE ——
     * {@code Base64.Decoder#decode(String)} 第一句就是 {@code src.getBytes(...)}，
     * 而外层只 {@code catch IllegalArgumentException}，NPE 会直接穿透到调用方
     * （{@code LearnCommand#handleLoadScenarioCommand} 是玩家触发的路径）。
     * 「格式错返回 null」这条契约没有理由把 {@code null} 排除在外 ——
     * {@code null} 就是最极端的格式错。
     */
    public static DecodedScenario decode(String base64) {
        if (base64 == null) return null;
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
            // `<= 0` 同时挡住两种畸形：(a) 没有分隔符（-1）；(b) 名字为空（0）。
            // (b) 保留判失败不变，而不是放宽成「允许空名字」：空名字的场景在导入侧
            // （LearnCommand 的名字正则）也过不了，放进来只会变成一条名字空白、谁也认不出的
            // 个人场景；且这条判定早就发布过，放宽会让 `\0abc` 这类半截码从「明确报损坏」
            // 变成「看起来导入成功」。对称性是靠编码侧 requireEncodableName 拒绝空名字达成（R23-D3）。
            // 只切第一个分隔符：正文里出现 \0 一律归正文，往返无损（见类注释的不对称契约）。
            int separatorIdx = payload.indexOf(SEPARATOR);
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
