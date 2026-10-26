package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder.GuiPayload.SquarePage;
import com.letslearnenglish.minecraftplugin.listener.SocialListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 英语广场「模式键」的生产者/消费者契约测试。
 *
 * <p><b>为什么需要这个类</b>：广场模式在 payload 里以字符串（{@code modeKey}）传输，
 * 但编码与解码分居两个互不知情的类：
 * <ul>
 *   <li><b>生产侧</b> {@code EnglishSquareGUI.modeKeyOf(RankingMode)} —— 渲染界面时
 *       把模式写进 {@link SquarePage}；</li>
 *   <li><b>消费侧</b> {@code SocialListener.modeFromKey(String)} —— 点击时把 payload
 *       里的字符串反解回 {@link RankingMode}，交给 {@code resolveSquareMode}。</li>
 * </ul>
 *
 * <p>两者都是 {@code private static}，没有共享常量、没有共享枚举、没有编译期约束，
 * 唯一的「协议文档」是 {@link SquarePage} 的 Javadoc 与两处 switch。任一表格写错
 * （例如 {@code "alltime"} 拼成 {@code "all_time"}），消费侧就返回 {@code null}，
 * {@code resolveSquareMode} 静默回落 {@link RankingMode#ALL_PLAYERS} —— 玩家看到
 * 榜单 A、点进去变成榜单 B，且<b>没有任何异常、日志或编译告警</b>。因此必须由测试
 * 把两张表钉成严格互逆，而不是靠人眼比对。
 *
 * <p><b>为什么用反射</b>：两个方法都是无状态纯函数，签名简单（枚举↔字符串），
 * 反射只承担「拿到方法句柄」这一件事，不触碰字段、不替换实现，脆弱性远低于
 * 反射改 final 字段那一类做法。若将来把方法提为 package-private 或 public，
 * 本类可原样改直接调用，断言语义不变。
 *
 * <p><b>为什么除了 round-trip 还要钉死字面量</b>：round-trip 只证明两侧一致，
 * 证明不了协议本身没漂移。若两侧同时把 {@code "alltime"} 改成 {@code "all_time"}，
 * round-trip 依然全绿，但任何按字面量消费该键的第三方（统计、跨服帧、外部脚本）
 * 都会失配。故用例 2 单独钉死文档承诺的五个小写串。
 */
@DisplayName("英语广场模式键：EnglishSquareGUI.modeKeyOf ↔ SocialListener.modeFromKey 契约")
class EnglishSquareModeContractTest {

    /**
     * 文档承诺的模式键（{@link SquarePage#modeKey()} 的取值约定，见其 Javadoc）。
     *
     * <p>这是「协议」本身，不是实现的镜像 —— 用例 2 用它钉死生产侧输出，
     * 用例 4 用它校验生产侧确实只产出这五个键。
     */
    private static final Map<RankingMode, String> DOCUMENTED_KEYS = Map.of(
            RankingMode.WEEKLY, "weekly",
            RankingMode.MONTHLY, "monthly",
            RankingMode.ALL_TIME_COINS, "alltime",
            RankingMode.FEED, "feed",
            RankingMode.ALL_PLAYERS, "all"
    );

    // ---------------------------------------------------------------- 用例 1

    /**
     * 核心不变式：对每个模式，生产侧编出的键必须被消费侧解回同一个模式。
     *
     * <p>用 {@link EnumSource} 而非硬编码列表 —— 将来新增枚举值时本用例自动覆盖，
     * 新增值若没有对应的 key（落进 {@code default -> "all"}）会立刻变红，而不是
     * 等到玩家点击时才发现两个模式共用一个键。
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(RankingMode.class)
    @DisplayName("用例1：modeFromKey(modeKeyOf(m)) 必须还原为 m（两侧严格互逆）")
    void roundTripIsIdentity(RankingMode mode) throws Exception {
        String key = invokeModeKeyOf(mode);
        assertNotNull(key,
                () -> "生产侧 EnglishSquareGUI.modeKeyOf(" + mode + ") 返回了 null；"
                        + "SquarePage 的紧凑构造器会直接 NPE，广场界面根本打不开");
        RankingMode back = invokeModeFromKey(key);
        assertEquals(mode, back,
                () -> "两侧映射表已漂移：modeKeyOf(" + mode + ") 产出 key=\"" + key
                        + "\"，但消费侧 SocialListener.modeFromKey(\"" + key + "\") 反解为 "
                        + back + "。修复方向：比对 EnglishSquareGUI.modeKeyOf 与 "
                        + "SocialListener.modeFromKey 中该键的 switch 分支，二者必须指向同一模式；"
                        + "若反解为 null，则消费侧不认识该键，点击会静默回落 ALL_PLAYERS（榜单错位）");
    }

    // ---------------------------------------------------------------- 用例 2

    /** 供用例 2 参数化：每个模式一条用例，失败时能直接看到是哪一行协议漂移。 */
    static Stream<Arguments> documentedKeys() {
        return DOCUMENTED_KEYS.entrySet().stream()
                .map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    /**
     * 钉死生产侧输出的字面量，防止「两侧一起改」绕过 round-trip。
     *
     * <p>失败时错误消息点明是生产侧（EnglishSquareGUI）偏离了文档协议，
     * 而不是消费侧 —— 这两者的修复责任不同。
     */
    @ParameterizedTest(name = "{0} -> \"{1}\"")
    @MethodSource("documentedKeys")
    @DisplayName("用例2：modeKeyOf 必须精确产出文档承诺的五个小写键（防协议字面量漂移）")
    void modeKeyOfProducesDocumentedKeys(RankingMode mode, String expectedKey) throws Exception {
        String actualKey = invokeModeKeyOf(mode);
        assertEquals(expectedKey, actualKey,
                () -> "生产侧 EnglishSquareGUI.modeKeyOf(" + mode + ") 应产出 \"" + expectedKey
                        + "\"，实际为 \"" + actualKey + "\"。该字面量是 SquarePage#modeKey 的公开协议："
                        + "即使消费侧同步改了、round-trip 仍绿，其他按字面量读取该键的消费方"
                        + "（统计、跨服帧、外部脚本）也会失配。请以文档承诺的取值为准修生产侧");
    }

    // ---------------------------------------------------------------- 用例 3

    /**
     * 未知/异常输入必须返回 {@code null}（调用方据此回落 ALL_PLAYERS），
     * 且大小写敏感是当前显式行为。
     *
     * <p>特别钉住 {@code "WEEKLY"}：若将来把协议改成大小写不敏感，本用例会变红，
     * 逼出一次显式决策（并同步生产侧输出的大小写），而不是让两张表各自悄悄放宽。
     */
    @ParameterizedTest(name = "modeFromKey({0}) 应为 null")
    @NullAndEmptySource
    @ValueSource(strings = {"WEEKLY", "all_time", "unknown"})
    @DisplayName("用例3：modeFromKey 对 null/空串/大写/错拼/未知键一律返回 null（不抛异常）")
    void modeFromKeyReturnsNullForUnknownKeys(String modeKey) throws Exception {
        RankingMode result = invokeModeFromKey(modeKey);
        assertEquals(null, result,
                () -> "消费侧 SocialListener.modeFromKey(\"" + modeKey + "\") 应返回 null"
                        + "（resolveSquareMode 据此回落 ALL_PLAYERS），实际返回 " + result
                        + "。若该键本应合法，说明生产侧拼写与文档不一致；"
                        + "若这是刻意放宽（如大小写不敏感），需同步生产侧与本用例");
    }

    // ---------------------------------------------------------------- 用例 4

    /**
     * 联立校验：生产侧产出的每个键都必须能构造 {@link SquarePage}，且只能产出
     * 文档里的五个键、每个模式独占一个键。
     *
     * <p>{@code SquarePage} 的紧凑构造器只拦 {@code null} 与空串，不枚举白名单，
     * 因此「payload 构造成功」本身不能证明键合法 —— 必须在此显式比对集合。
     */
    @Test
    @DisplayName("用例4：五个键均可构造 SquarePage，且生产侧只产出这五个键、模式与键一一对应")
    void squarePayloadAcceptsAllProducedKeys() throws Exception {
        Set<String> produced = new LinkedHashSet<>();
        for (RankingMode mode : RankingMode.values()) {
            String key = invokeModeKeyOf(mode);
            produced.add(key);
            assertDoesNotThrow(() -> {
                new SquarePage(1, key);
            }, () -> "生产侧 key=\"" + key + "\"（来自 " + mode + "）无法构造 SquarePage；"
                    + "SquarePage 要求 modeKey 非 null 且非空");

            SquarePage page = new SquarePage(1, key);
            assertEquals(1, page.page(), "SquarePage 应保真页码");
            RankingMode decoded = invokeModeFromKey(page.modeKey());
            assertEquals(mode, decoded,
                    () -> "经 payload 往返后模式不一致：payload.modeKey()=\"" + page.modeKey()
                            + "\"，反解为 " + decoded + "，期望 " + mode);
        }

        assertEquals(Set.copyOf(DOCUMENTED_KEYS.values()), produced,
                () -> "生产侧产出的键集合与文档协议不一致：实际 " + produced
                        + "，期望 " + new LinkedHashSet<>(DOCUMENTED_KEYS.values()));
        assertEquals(RankingMode.values().length, produced.size(),
                () -> "存在两个 RankingMode 共用同一个 modeKey（实际键集合 " + produced
                        + "，模式数 " + RankingMode.values().length + "）。"
                        + "典型原因是新枚举值落进了 modeKeyOf 的 default 分支 —— "
                        + "此时 round-trip 必然失败，且点击侧无法区分这两个模式");
    }

    // ------------------------------------------------------------ 反射辅助

    /** 生产侧：{@code EnglishSquareGUI.modeKeyOf(RankingMode)}（private static）。 */
    private static String invokeModeKeyOf(RankingMode mode) throws Exception {
        Method m = EnglishSquareGUI.class.getDeclaredMethod("modeKeyOf", RankingMode.class);
        m.setAccessible(true);
        return (String) m.invoke(null, mode);
    }

    /** 消费侧：{@code SocialListener.modeFromKey(String)}（private static）。 */
    private static RankingMode invokeModeFromKey(String modeKey) throws Exception {
        Method m = SocialListener.class.getDeclaredMethod("modeFromKey", String.class);
        m.setAccessible(true);
        return (RankingMode) m.invoke(null, modeKey);
    }
}
