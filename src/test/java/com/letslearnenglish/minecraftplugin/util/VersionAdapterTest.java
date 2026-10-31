package com.letslearnenglish.minecraftplugin.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link VersionAdapter#parseServerVersion(String)} 的版本串解析。
 *
 * <h2>为什么需要这一组</h2>
 * 旧实现是：
 * <pre>{@code
 * parsedMajor = parts.length > 1 ? Integer.parseInt(parts[1]) : 21;
 * ...
 * public boolean isVersionSupported() { return majorVersion >= 18 && majorVersion <= 21; }
 * }</pre>
 * 它假定版本串永远是 {@code "1.21.11"} 这种三段式。Paper 改用年份版本号后：
 * <pre>
 *   "26.2" → parts[1] = "2" → majorVersion = 2 → 判成「不支持」
 *   "26.3" → parts[1] = "3" → majorVersion = 3 → 判成「不支持」
 * </pre>
 * bStats 实测 26.x 占全部服务器的 <b>约 45%</b>（32 万台样本）—— 等于给将近一半的服主
 * 在 {@code /lea info} 里显示「版本兼容性: false」，让他们以为自己装错了。
 *
 * <p>这个 bug <b>不抛异常、不影响任何功能</b>，只是显示错，所以必须有单元测试直接钉住
 * 解析结果。为此把解析逻辑抽成了 {@code static int[] parseServerVersion(String)} ——
 * 纯函数、不碰 Bukkit，测试<b>直接调用生产代码</b>而不是复制一份逻辑（复制的测试是自证，
 * 生产代码改坏了它照样绿）。
 */
@DisplayName("VersionAdapterTest — 服务端版本串解析")
class VersionAdapterTest {

    /** {@code parseServerVersion} 的返回值下标语义。 */
    private static final int MINOR = 0;
    private static final int YEAR_BASED = 1;

    /** 与生产代码完全一致的判定口径（已抽成静态方法，这里只做组合断言）。 */
    private static boolean supported(String version) {
        int[] r = VersionAdapter.parseServerVersion(version);
        if (r[YEAR_BASED] == 1) {
            return false;                      // 年份式 → 需 Java 25
        }
        return r[MINOR] >= 4 && r[MINOR] <= 11;
    }

    @Nested
    @DisplayName("支持范围内：1.21.4 – 1.21.11")
    class InRange {

        @Test
        @DisplayName("1.21.4 – 1.21.11 全部解析正确且判定为支持")
        void allSupported() {
            for (int patch = 4; patch <= 11; patch++) {
                String v = "1.21." + patch;
                int[] r = VersionAdapter.parseServerVersion(v);
                assertEquals(patch, r[MINOR], v + " 的次版本号解析错误");
                assertEquals(0, r[YEAR_BASED], v + " 不应被识别为年份式版本");
                assertTrue(supported(v), v + " 应当被判定为支持（这是本项目声明的支持范围）");
            }
        }

        @Test
        @DisplayName("带后缀的版本串也能解析")
        void suffixTolerated() {
            assertEquals(11, VersionAdapter.parseServerVersion("1.21.11-R0.1")[MINOR]);
            assertTrue(supported("1.21.11-R0.1"));
        }

        @Test
        @DisplayName("1.21 与 1.21.0 这类缺补丁号的形式不崩")
        void shortForms() {
            // "1.21" 只有两段 → 走不到 mcPatch 分支，解析为未知
            assertEquals(-1, VersionAdapter.parseServerVersion("1.21")[MINOR]);
            // "1.21.0" 是合法三段式
            assertEquals(0, VersionAdapter.parseServerVersion("1.21.0")[MINOR]);
            assertFalse(supported("1.21.0"), "1.21.0 低于编译基准 1.21.4");
        }
    }

    @Nested
    @DisplayName("支持范围外")
    class OutOfRange {

        @Test
        @DisplayName("1.21.0 – 1.21.3 判定为不支持（低于编译基准）")
        void belowCompileTarget() {
            for (int patch = 0; patch <= 3; patch++) {
                String v = "1.21." + patch;
                assertFalse(supported(v), v + " 低于编译基准 1.21.4，应判不支持");
            }
        }

        @Test
        @DisplayName("1.20.x / 1.19.x / 1.18.x 判定为不支持")
        void olderLines() {
            for (String v : new String[] {"1.20.4", "1.19.4", "1.18.2"}) {
                assertFalse(supported(v), v + " 不在 1.21 线上，应判不支持");
                assertEquals(-1, VersionAdapter.parseServerVersion(v)[MINOR],
                        v + " 不在 1.21 线上，次版本号应记为未知");
            }
        }

        /**
         * 本类存在的核心理由。旧实现下 {@code "26.2"} 被解析成 major=2，
         * 恰好也落到「不支持」，但<b>原因完全错</b>（它以为太旧，实际是太新）。
         * 新实现显式识别年份式版本，并给出正确的解释。
         */
        @Test
        @DisplayName("26.x 被识别为年份式，判定不支持")
        void yearBasedVersions() {
            for (String v : new String[] {"26.1.2", "26.2", "26.3", "26.4"}) {
                int[] r = VersionAdapter.parseServerVersion(v);
                assertEquals(1, r[YEAR_BASED], v + " 应被识别为年份式版本");
                assertFalse(supported(v), v + " 需 Java 25，应判不支持");
            }
        }

        @Test
        @DisplayName("1.21.12+ 超出已验证范围，不宣称支持")
        void futurePatchNotClaimed() {
            assertFalse(supported("1.21.12"),
                    "1.21.12 超出已验证范围 1.21.4-1.21.11，不应宣称支持");
        }
    }

    @Nested
    @DisplayName("异常输入不抛")
    class Malformed {

        @Test
        @DisplayName("无法解析的版本串返回未知而不是抛异常")
        void garbageIsUnknown() {
            for (String v : new String[] {"", "abc", "unknown-build"}) {
                int[] r = VersionAdapter.parseServerVersion(v);
                assertEquals(-1, r[MINOR], "\"" + v + "\" 应解析为未知(-1)");
                assertFalse(supported(v), "\"" + v + "\" 不应被判为支持");
            }
        }
    }
}
