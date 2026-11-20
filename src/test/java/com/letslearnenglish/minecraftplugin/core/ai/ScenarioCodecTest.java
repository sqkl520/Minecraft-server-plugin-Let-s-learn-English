package com.letslearnenglish.minecraftplugin.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScenarioCodec} 的纯逻辑守卫 —— 场景分享码的编解码契约。
 *
 * <p>零 Bukkit 依赖（只用到 {@link PromptComposer} 这个纯字符串类）。
 *
 * <h2>本类钉死的行为（全部按实现逐行核对，不按注释转述）</h2>
 * <ol>
 *   <li><b>无框架输入往返无损</b>：{@code encode} 内部走 {@link PromptComposer#SCENARIO_PREFIX}
 *       判定，不带前缀的输入<b>原样</b>返回 —— 连首尾空白都不 trim。</li>
 *   <li><b>带框架输入被剥到只剩场景正文</b>（R5-06c）：前缀 + {@code === SCENE ===\n} 标记
 *       都去掉，正文在第一个 {@code "\n\n=== DIFFICULTY ===\n"} 处截断并 {@code trim()}。</li>
 *   <li><b>往返后重新 compose 必须逐字节还原原 prompt</b> —— 这是剥框架存在的<b>唯一目的</b>：
 *       导入的 body 会被下一次 {@code PromptComposer.compose} 再包一层框架，若 body 里
 *       还留着旧框架，框架与 RULES_BLOCK 就会重复一次、长度近乎翻倍（注释记录的 283→506）。</li>
 *   <li><b>任意次往返后 body 恒定</b>（幂等），不会越滚越长。</li>
 *   <li><b>畸形码一律返回 null</b>，包括非法 UTF-8 字节（不允许静默替换成替换字符）。</li>
 * </ol>
 *
 * <h2>round 27 修掉的三条（原 {@code @Disabled}，现已摘除并改为正向守卫）</h2>
 * <ul>
 *   <li>R23-D2 LOW：场景名含 {@code \0} → 旧行为是 decode 从第一个 {@code \0} 切开、名字被
 *       <b>静默</b>截断。<b>修法：编码侧拒绝</b>（{@code encode} 抛 {@code IllegalArgumentException}），
 *       decode 一字未动 —— 见 {@link #nulInsideNameIsRejectedAtEncodeTime()}。</li>
 *   <li>R23-D3 LOW：空场景名 → 旧行为是 encode 成功、decode 判失败（{@code separatorIdx <= 0}）。
 *       <b>修法：同样在编码侧拒绝</b>，与 decode 的既有判定对称 ——
 *       见 {@link #emptyNameIsRejectedAtEncodeTime()}。</li>
 *   <li>R23-D4 LOW：{@code decode(null)} 抛 NPE。<b>修法：入口 {@code if (base64 == null) return null}，
 *       与「畸形一律 null」齐平</b> —— 见 {@link #nullBase64ReturnsNull()}。</li>
 * </ul>
 *
 * <p><b>线格式未变</b>（名字 + {@code \0} + 正文的 Base64），所以 R23-D2/D3 的修法<b>不影响
 * 任何已发出去的分享码</b>：decode 逻辑逐字节未改，存量码照旧解出同样的结果，只是编码器
 * 不再铸造出「自己解不开」或「名字会被截断」的新码。这条兼容性由
 * {@link #legacyCodeWithNulInNameStillDecodesAsBefore()} 与
 * {@link #malformedCodesReturnNull()} 里的 {@code \0abc} 用例共同钉住。
 *
 * <h2>一堆刻意<b>不对称</b>的契约（别「顺手统一」）</h2>
 * <ul>
 *   <li>名字里禁 {@code \0}，<b>正文里允许</b> {@code \0} 且必须无损往返 ——
 *       {@link #nulInsideBodyRoundTripsLosslessly()}。</li>
 *   <li>{@code encode(null, x)} 不拒绝（拼成字面量 {@code "null"}），但 {@code encode("", x)} 拒绝。</li>
 * </ul>
 *
 * <h2>另有一处「钉住现行为」的用例（现在是绿的，修好会变红）</h2>
 * <ul>
 *   <li>R23-D5 LOW：{@code scenarioBody} 在整段 prompt 上找 {@code === SCENE ===} 标记而不先跳过
 *       首行，场景名含该标记时会切出脏 body。上游名字正则挡住了，故仅记录。
 *       见 {@link #sceneMarkerInsideNameProducesDirtyBody()}。</li>
 * </ul>
 */
class ScenarioCodecTest {

    private static final String NAME = "Village Trade";
    private static final String SCENE = "You trade emeralds. Ask what the player wants to buy.";
    private static final String DIFFICULTY = "Easy";
    private static final String DIFF_PROMPT = "You are speaking to a beginner.";
    private static final List<String> WORDS = List.of("emerald", "trade");

    /** 真实链路里被导出分享码的那段 prompt：{@code AIChatManager} 的 compose 输出。 */
    private static String composed() {
        return PromptComposer.compose(NAME, SCENE, DIFFICULTY, DIFF_PROMPT, WORDS);
    }

    private static String base64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    // ==================================================================
    // 组 1 —— 无框架输入（兼容旧码）
    // ==================================================================

    @Test
    @DisplayName("无框架 prompt 往返无损：name 与 body 与输入逐字节一致（含首尾空白）")
    void plainPromptRoundTripsLosslessly() {
        // 刻意带首尾空白与换行：无前缀路径是 return systemPrompt，不 trim
        String prompt = "  You are a villager.\nWhat do you want to buy?  \n";

        String code = ScenarioCodec.encode(NAME, prompt);
        assertNotNull(code);
        assertTrue(code.matches("[A-Za-z0-9+/]+={0,2}"), "分享码应是标准 Base64，实际: " + code);

        ScenarioCodec.DecodedScenario decoded = ScenarioCodec.decode(code);
        assertNotNull(decoded, "合法分享码不得解码失败");
        assertEquals(NAME, decoded.name());
        assertEquals(prompt, decoded.prompt(), "无框架路径必须原样返回，不得 trim");
    }

    @Test
    @DisplayName("中文场景名与中文正文按 UTF-8 往返无损")
    void chinesePayloadRoundTripsLosslessly() {
        String name = "乡村集市";
        String prompt = "你是一位村民商人，问问玩家想买什么。\n报价用英语。";

        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(name, prompt));

        assertNotNull(decoded);
        assertEquals(name, decoded.name());
        assertEquals(prompt, decoded.prompt());
    }

    @Test
    @DisplayName("空 body 往返后是空串，不是 null")
    void emptyBodyRoundTripsToEmptyString() {
        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(NAME, ""));

        assertNotNull(decoded);
        assertEquals(NAME, decoded.name());
        assertEquals("", decoded.prompt(), "空 body 必须仍是空串（分隔符后无内容）");
    }

    @Test
    @DisplayName("同名同样输入编码结果确定（同一 Base64）")
    void encodingIsDeterministic() {
        assertEquals(ScenarioCodec.encode(NAME, SCENE), ScenarioCodec.encode(NAME, SCENE));
        assertNotEquals(ScenarioCodec.encode(NAME, SCENE),
                ScenarioCodec.encode(NAME + " 2", SCENE), "不同名字必须产出不同码");
    }

    // ==================================================================
    // 组 2 —— 剥框架（R5-06c）
    // ==================================================================

    @Test
    @DisplayName("带框架的 compose 输出 → body 只剩场景正文，前缀/难度/RULES/TARGET 全剥掉")
    void framedPromptIsStrippedToSceneOnly() {
        String full = composed();
        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(NAME, full));

        assertNotNull(decoded);
        assertEquals(NAME, decoded.name());
        assertEquals(SCENE, decoded.prompt());

        assertFalse(decoded.prompt().contains(PromptComposer.SCENARIO_PREFIX),
                "剥框架后不得残留场景前缀");
        assertFalse(decoded.prompt().contains("=== SCENE ==="), "不得残留 SCENE 标记");
        assertFalse(decoded.prompt().contains("=== DIFFICULTY ==="), "不得残留 DIFFICULTY 标记");
        assertFalse(decoded.prompt().contains("=== TARGET WORDS ==="), "不得残留目标词段");
        assertFalse(decoded.prompt().contains("Important rules:"), "不得残留 RULES_BLOCK");
        assertTrue(decoded.prompt().length() * 2 < full.length(),
                "剥框架后 body 应不到原 prompt 的一半，实际 body=" + decoded.prompt().length()
                        + " 原=" + full.length());
    }

    /**
     * 剥框架存在的唯一理由。若 body 里留着旧框架，导入后下一次 compose 会把框架再包一层，
     * RULES_BLOCK 从 1 份变成 2 份 —— 这就是注释记录的 283→506。
     */
    @Test
    @DisplayName("往返后重新 compose 必须逐字节还原原 prompt（框架与 RULES 各只 1 份）")
    void recomposeAfterRoundTripIsByteIdentical() {
        String full = composed();
        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(NAME, full));
        assertNotNull(decoded);

        String recomposed = PromptComposer.compose(
                decoded.name(), decoded.prompt(), DIFFICULTY, DIFF_PROMPT, WORDS);

        assertEquals(full, recomposed, "导入后重新组装的 prompt 必须与导出前逐字节一致");
        assertEquals(1, countOccurrences(recomposed, "Important rules:"),
                "RULES_BLOCK 只能出现 1 次（剥框架失效时会变 2 次）");
        assertEquals(1, countOccurrences(recomposed, PromptComposer.SCENARIO_PREFIX),
                "场景前缀只能出现 1 次");
        assertEquals(1, countOccurrences(recomposed, "=== SCENE ==="), "SCENE 标记只能出现 1 次");
    }

    @Test
    @DisplayName("核心不变量：连续 5 次往返后 body 恒等于场景正文，长度不增长")
    void repeatedRoundTripsAreIdempotent() {
        String full = composed();
        String name = NAME;
        String body = full;

        for (int i = 1; i <= 5; i++) {
            ScenarioCodec.DecodedScenario decoded =
                    ScenarioCodec.decode(ScenarioCodec.encode(name, body));
            assertNotNull(decoded, "第 " + i + " 次往返解码失败");
            name = decoded.name();
            body = decoded.prompt();

            assertEquals(NAME, name, "第 " + i + " 次往返后场景名不得漂移");
            assertEquals(SCENE, body, "第 " + i + " 次往返后 body 必须恒等于场景正文");
        }

        assertTrue(body.length() * 4 < full.length(),
                "body 长度应稳定在场景正文量级，实际 " + body.length() + " vs 原 " + full.length());
        assertEquals(0, countOccurrences(body, "Important rules:"), "body 里不得出现 RULES_BLOCK");
        assertEquals(0, countOccurrences(body, PromptComposer.SCENARIO_PREFIX),
                "body 里不得出现场景前缀（否则下次 compose 会再包一层）");
    }

    @Test
    @DisplayName("带框架但缺 === SCENE === 标记时原样保留（剥框架静默 no-op）")
    void framedPromptWithoutSceneMarkerIsKeptWhole() {
        // 可达路径只有「手工构造 / 旧版 / 被篡改」的分享码：
        // compose 产出的 prompt 恒有 "=== SCENE ===\n" 标记（紧跟名字行），走不到 -1 这一支。
        // 但一旦这种 body 被导入，下次 compose 会把它当 scene 正文再包一层框架，
        // 于是场景正文里永久带一行多余的前缀（不再增长，但不干净）。
        String weird = PromptComposer.SCENARIO_PREFIX + "Handwritten.\n\nNo markers here.";

        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(NAME, weird));

        assertNotNull(decoded);
        assertEquals(weird, decoded.prompt(),
                "找不到 === SCENE === 标记时 scenarioBody 整体返回（i < 0 分支），不报错也不剥");
    }

    @Test
    @DisplayName("剥框架后的正文会 trim（框架路径 trim，无框架路径不 trim）")
    void strippedBodyIsTrimmed() {
        String framed = PromptComposer.SCENARIO_PREFIX + "X.\n\n=== SCENE ===\n   padded body   "
                + "\n\n=== DIFFICULTY ===\nEasy";

        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode("X", framed));

        assertNotNull(decoded);
        assertEquals("padded body", decoded.prompt());
    }

    @Test
    @DisplayName("正文自身含 \\n\\n=== DIFFICULTY ===\\n 时会被该标记截断（格式歧义，有损）")
    void sceneContainingDifficultyMarkerIsTruncated() {
        String sceneWithMarker = "Line one.\n\n=== DIFFICULTY ===\nLine two.";
        String full = PromptComposer.compose(NAME, sceneWithMarker, DIFFICULTY, DIFF_PROMPT, WORDS);

        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(NAME, full));

        assertNotNull(decoded);
        assertEquals("Line one.", decoded.prompt(),
                "marks 靠字面量切分，正文里出现同一标记会被当成分隔符截断；"
                        + "若将来支持正文含标记，需先改成结构化序列化");
    }

    /**
     * 记录一处<b>解析顺序</b>弱点：{@code scenarioBody} 是在<b>整段 prompt</b>上
     * {@code indexOf("=== SCENE ===\n")}，没有先跳过第一行。因此若场景<b>名</b>里含该标记，
     * 命中点会落在名字行内部，body 从名字中间开始切，产出含残句的脏 body。
     *
     * <p>可达性：{@code /le scenario save} 的名字正则 {@code [一-龥a-zA-Z0-9_]+}
     * 不允许 {@code =} 与换行，导入侧同样校验，所以线上进不来。属库层健壮性缺口（R23-D5）。
     * 本用例钉住<b>当前</b>行为，修好后会自动变红提醒更新。
     */
    @Test
    @DisplayName("R23-D5 现行为：场景名含 === SCENE === 标记时 body 从名字中间开始切（脏 body）")
    void sceneMarkerInsideNameProducesDirtyBody() {
        String name = "A=== SCENE ===\nB";
        String full = PromptComposer.compose(name, SCENE, DIFFICULTY, DIFF_PROMPT, List.of());

        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(name, full));

        assertNotNull(decoded);
        assertEquals("B.\n\n=== SCENE ===\n" + SCENE, decoded.prompt(),
                "命中点落在名字行内部，剥出的 body 带着名字的残句和下一个 SCENE 标记 —— "
                        + "若这里变了，说明剥框架已改为跳过首行再找标记，请更新本用例与 R23-D5 记录");
        assertTrue(decoded.prompt().contains("=== SCENE ==="),
                "脏 body 里仍残留标记 —— 这正是「未跳首行」的直接证据");
    }

    // ==================================================================
    // 组 3 —— 畸形输入
    // ==================================================================

    @Test
    @DisplayName("畸形分享码一律返回 null：空串 / 非 Base64 / 无分隔符 / 首字符即分隔符")
    void malformedCodesReturnNull() {
        assertNull(ScenarioCodec.decode(""), "空串");
        assertNull(ScenarioCodec.decode("!!!! not base64 !!!!"), "非 Base64 字符");
        assertNull(ScenarioCodec.decode("YWJj"), "Base64 的 \"abc\"，缺 \\0 分隔符");
        assertNull(ScenarioCodec.decode(base64("\0abc".getBytes(StandardCharsets.UTF_8))),
                "payload 首字符即分隔符 → 名字为空 → 判失败");
        assertNull(ScenarioCodec.decode(base64("no-separator".getBytes(StandardCharsets.UTF_8))),
                "有内容但无 \\0 分隔符");
    }

    /**
     * <b>这条用例是严格 UTF-8 解码的唯一杀手</b>，非法字节必须放在分隔符<b>之后</b>。
     *
     * <p>为什么不能只用「一坨非法字节」当输入：那样两种实现都返回 null ——
     * 严格解码抛 {@code CharacterCodingException} → null；宽松替换成 {@code U+FFFD} 后
     * payload 里依然没有 {@code \0} → {@code indexOf('\0') == -1} → 也是 null。
     * 两边结果一样，变异杀不掉，用例就成了摆设（本仓教训：假绿五形态之「闸门未触及」）。
     *
     * <p>补上分隔符后差异才显现：宽松实现会返回 name="Village Trade" + body=替换字符（U+FFFD）
     * —— 一段带替换字符的乱码提示词被当成合法场景放行，正是 slice-06-F5 修的缺陷。
     * （此处刻意不写该字符的字面量：{@code SourceEncodingTest} 禁止源码含 U+FFFD。）
     */
    @Test
    @DisplayName("分隔符之后的非法 UTF-8 必须判失败，不得静默替换后当合法场景导入")
    void invalidUtf8AfterSeparatorIsRejectedNotReplaced() {
        byte[] payload = new byte[] {
                'V', 'i', 'l', 'l', 'a', 'g', 'e', ' ', 'T', 'r', 'a', 'd', 'e',
                0x00,                       // \0 分隔符（合法）
                'b', 'o', (byte) 0xFF       // 0xFF 在 UTF-8 里永远非法
        };

        assertNull(ScenarioCodec.decode(base64(payload)),
                "损坏的分享码必须报错；若这里返回了非 null，说明解码退回了宽松替换策略，"
                        + "玩家会导入一段带替换字符的乱码提示词");

        // 对照：把同一个非法字节换成合法字节，同一段 payload 就必须能解出来
        byte[] sameShapeButValid = new byte[] {
                'V', 'i', 'l', 'l', 'a', 'g', 'e', ' ', 'T', 'r', 'a', 'd', 'e',
                0x00, 'b', 'o', 'x'
        };
        ScenarioCodec.DecodedScenario ok = ScenarioCodec.decode(base64(sameShapeButValid));
        assertNotNull(ok, "对照用例必须能解出来 —— 否则上面的 null 是别的原因造成的假红");
        assertEquals("Village Trade", ok.name());
        assertEquals("box", ok.prompt());
    }

    /**
     * R23-D3 的正向守卫：空名字在<b>编码侧</b>就被拒绝，不再铸出「自己解不开」的废码。
     *
     * <p>同时钉住另一半 —— decode 对空名字的判定<b>不变</b>（{@code separatorIdx <= 0} 仍返回
     * null）。这两半合起来才叫对称：过去是「encode 收、decode 拒」，现在是「两边都拒」。
     * 若有人图省事把 decode 放宽成允许 {@code separatorIdx == 0}，第二条断言会红。
     */
    @Test
    @DisplayName("R23-D3：空名字在编码侧被拒绝；decode 对空名字仍判失败（契约对称）")
    void emptyNameIsRejectedAtEncodeTime() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ScenarioCodec.encode("", "abc"),
                "空名字的码 decode 恒判失败，encode 再接受它就是在铸造废码");
        assertTrue(ex.getMessage().contains("empty"),
                "异常消息要能让调用方看懂拒绝理由，实际: " + ex.getMessage());

        // decode 侧不动：手工构造的「名字为空」的老码仍然明确判失败，不悄悄放行
        assertNull(ScenarioCodec.decode(base64("\0abc".getBytes(StandardCharsets.UTF_8))),
                "decode 的空名字判定必须保持原样 —— 放宽会让半截码看起来导入成功");
    }

    /**
     * R23-D2 的正向守卫：含 {@code \0} 的名字在<b>编码侧</b>被拒绝，不再静默截断。
     *
     * <p>可达性：{@code LearnCommand} 的 {@code matches("[\\u4e00-\\u9fa5a-zA-Z0-9_]+")} 挡着，
     * 线上进不来。但「编解码互逆」是分享码的核心不变量，不该靠调用方的正则恰好成立来维持。
     */
    @Test
    @DisplayName("R23-D2：含 \\0 的名字在编码侧被拒绝（不再静默截断）")
    void nulInsideNameIsRejectedAtEncodeTime() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ScenarioCodec.encode("Vil\0lage", "body"),
                "名字含分隔符必然被 decode 从第一个 \\0 切开；应当在铸造点就拒绝");
        assertTrue(ex.getMessage().contains("NUL"),
                "异常消息要指出是 NUL 分隔符冲突，实际: " + ex.getMessage());
    }

    /**
     * 与上一条配对：<b>正文</b>里含 {@code \0} 必须照旧无损往返 —— 解码只切<b>第一个</b>
     * 分隔符，其后全部归正文。名字禁 NUL、正文允许 NUL 这条不对称是刻意设计的，
     * 若谁「顺手统一」把正文也禁掉，本用例变红。
     */
    @Test
    @DisplayName("正文里的 \\0 不触发拒绝，且无损往返（只有名字禁 NUL，这条不对称是刻意的）")
    void nulInsideBodyRoundTripsLosslessly() {
        String body = "line\0with\0nuls";

        ScenarioCodec.DecodedScenario decoded =
                ScenarioCodec.decode(ScenarioCodec.encode(NAME, body));

        assertNotNull(decoded, "正文含 \\0 不是畸形输入，编码器不得拒绝");
        assertEquals(NAME, decoded.name());
        assertEquals(body, decoded.prompt(), "只切第一个分隔符，正文里的 \\0 原样保留");
    }

    /**
     * <b>向后兼容守卫</b>（本轮最该小心的一条）：R23-D2/D3 的修法如果动过 decode 或换过
     * 分隔符方案，玩家手里那串旧分享码就会导入失败。本用例用<b>字面量 Base64</b>
     * 钉死「旧码今天仍解出与修复前逐字节相同的结果」。
     *
     * <p>这里刻意<b>不走 encode</b>（encode 现在会拒绝含 {@code \0} 的名字，根本铸不出这种码）——
     * 直接用字节拼出「名字含 \0 的旧码」，模拟玩家存档里那枚历史产物，
     * 断言 decode 的旧行为<b>一字未变</b>：仍在第一个 {@code \0} 切开，剩余部分落进 body。
     */
    @Test
    @DisplayName("旧码兼容：含 \\0 名字的历史分享码仍按原样解码（decode 未改，线格式未变）")
    void legacyCodeWithNulInNameStillDecodesAsBefore() {
        // 等价于修复前 ScenarioCodec.encode("Vil\0lage", "body") 会产出的那枚码
        String legacy = base64("Vil\0lage\0body".getBytes(StandardCharsets.UTF_8));

        ScenarioCodec.DecodedScenario decoded = ScenarioCodec.decode(legacy);

        assertNotNull(decoded, "存量分享码必须仍能导入 —— 这是本轮修改的硬约束");
        assertEquals("Vil", decoded.name(), "旧行为：从第一个 \\0 切开");
        assertEquals("lage\0body", decoded.prompt(), "剩余部分（含第二个 \\0）落进 body");
    }

    /**
     * 旧码兼容的另一个方向：普通（无 NUL、非空名字）的码是绝大多数存量分享码，
     * 其 Base64 <b>逐字符</b>必须与修复前一致 —— encode 加了校验但没有动拼接顺序与编码器。
     *
     * <p>字面量是硬编码的期望值，不是「再跑一遍 encode 比自己」，否则实现整体漂移时用例照样绿。
     */
    @Test
    @DisplayName("旧码兼容：普通码的 Base64 与修复前逐字符相同（含 NUL 分隔符的拼接顺序未变）")
    void ordinaryCodeBase64IsUnchangedFromBeforeFix() {
        String expected = base64("Village Trade\0You trade emeralds.".getBytes(StandardCharsets.UTF_8));

        assertEquals(expected, ScenarioCodec.encode(NAME, "You trade emeralds."),
                "encode 的线格式（名字 + \\0 + 正文，UTF-8，标准 Base64）不得改动，"
                        + "否则玩家手里的存量分享码全部失效");
    }

    @Test
    @DisplayName("encode(null, x) / encode(x, null) 不抛 NPE")
    void nullArgumentsDoNotThrow() {
        String nullName = ScenarioCodec.encode(null, "body");
        assertNotNull(nullName);

        ScenarioCodec.DecodedScenario fromNullName = ScenarioCodec.decode(nullName);
        assertNotNull(fromNullName);
        assertEquals("null", fromNullName.name(), "字符串拼接把 null 渲染成字面量 \"null\"");
        assertEquals("body", fromNullName.prompt());

        String nullPrompt = ScenarioCodec.encode(NAME, null);
        assertNotNull(nullPrompt);

        ScenarioCodec.DecodedScenario fromNullPrompt = ScenarioCodec.decode(nullPrompt);
        assertNotNull(fromNullPrompt);
        assertEquals(NAME, fromNullPrompt.name());
        assertEquals("null", fromNullPrompt.prompt(),
                "null prompt 被拼成 4 字符的 \"null\"（不是 null、也不是空串）");
    }

    // ==================================================================
    // 组 4 —— round 27 修掉的 R23-D2/D3/D4（原 @Disabled，现为正向守卫）
    // ==================================================================

    /**
     * R23-D4 正向守卫：{@code decode(null)} 返回 null，不抛 NPE。
     *
     * <p>成因（修复前）：{@code Base64.Decoder#decode(String)} 第一句就是
     * {@code src.getBytes(...)}，null 直接 NPE；而外层只 catch
     * {@code IllegalArgumentException}，NPE 穿透到调用方。
     *
     * <p>契约：本方法对<b>一切</b>输入只回「记录或 null」，从不抛异常 ——
     * {@code null} 是最极端的格式错，没有理由例外。
     */
    @Test
    @DisplayName("R23-D4：decode(null) 返回 null 而不是抛 NPE")
    void nullBase64ReturnsNull() {
        assertNull(ScenarioCodec.decode(null),
                "decode 的契约是「畸形一律返回 null」，null 入参不得抛 NPE 穿透到玩家命令路径");
    }

    /**
     * R23-D4 的<b>no-throw 矩阵</b>：{@code decode} 对任何垃圾输入都只回 {@code null}，
     * 一个异常都不许漏出去 —— {@code null} 那条是本轮修的原始缺陷，其余几条是同一契约的
     * 回归网（将来谁在 decode 里加索引/空指针操作，这里会先红）。
     *
     * <p>矩阵里每一项都<b>独立</b>覆盖一条失败路径，不是凑数：
     * {@code null} → 旧实现的 NPE；空串 → 空 payload 无分隔符；空白/非 Base64 → 解码抛
     * {@code IllegalArgumentException}；{@code "AA=="} → payload 只有一个 {@code \0}
     * （{@code separatorIdx == 0}）；{@code "/w=="} → 非法 UTF-8。
     */
    @Test
    @DisplayName("R23-D4：decode 对一切垃圾输入只返回 null、从不抛异常")
    void decodeNeverThrowsOnGarbageInput() {
        String nullBytePayload = base64(new byte[] {0x00});
        String invalidUtf8Payload = base64(new byte[] {(byte) 0xFF});

        String[] garbage = {
                null,               // 本轮修的缺陷本身：旧实现 NPE 穿透到玩家命令路径
                "",                 // 空 payload → 无分隔符
                "   ",              // 空白不是合法 Base64 字符
                "!!!!",             // 同上是非法字符集
                "YWJj",             // Base64("abc")：有内容但无分隔符
                "====",             // 纯填充，无数据
                nullBytePayload,    // payload == "\0" → 名字为空
                invalidUtf8Payload  // 非法 UTF-8 字节，必须判失败而非替换
        };

        for (String g : garbage) {
            assertNull(ScenarioCodec.decode(g),
                    "输入 [" + String.valueOf(g) + "] 必须返回 null —— decode 的契约是"
                            + "「只回记录或 null」，任何异常穿透都会打到玩家命令路径");
        }
    }
}
