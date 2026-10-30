package com.letslearnenglish.minecraftplugin.gui.identity;

import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder.GuiPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginGuiHolder} 与 {@link GuiPayload} 的纯 POJO / record 测试。
 *
 * <p>这里零 Mockito：被测类型只依赖 {@code java.util} 与 Bukkit 的两个接口类型
 * （{@code InventoryHolder} / {@code Inventory}），构造与比较全程不触达任何服务端状态，
 * 因此不需要 mock 也不需要 MockBukkit。
 *
 * <p>记录一条从源码读出的事实（与直觉不同，勿按惯例猜）：{@code Page} / {@code SquarePage}
 * 的页码越界抛 {@link IllegalArgumentException}（compact constructor 里显式判 {@code page < 1}），
 * 而 {@code null} 参数一律由 {@link java.util.Objects#requireNonNull} 抛出
 * {@link NullPointerException}。两类异常不可互换断言。
 */
@DisplayName("PluginGuiHolder 值语义与参数校验")
class PluginGuiHolderTest {

    @Nested
    @DisplayName("holder 构造与取值")
    class HolderContract {

        @Test
        void ofRejectsNullKey() {
            assertThrows(NullPointerException.class, () -> PluginGuiHolder.of(null),
                    "key 为 null 必须抛 NullPointerException；holder 的身份不能是可空值");
        }

        @Test
        void ofWithNullPayloadFallsBackToNoPayload() {
            PluginGuiHolder holder = PluginGuiHolder.of(GuiViewKey.SQUARE, null);

            assertSame(GuiPayload.NoPayload.INSTANCE, holder.getPayload(),
                    "payload 传 null 时必须回落到 NoPayload.INSTANCE 单例（引用相等）；"
                            + "若改成保留 null，读取侧每次 payload 访问都要判空");

            assertSame(GuiPayload.NoPayload.INSTANCE, PluginGuiHolder.of(GuiViewKey.SQUARE).getPayload(),
                    "单参 of(key) 的 payload 也必须是同一个 NoPayload.INSTANCE 单例");
        }

        @Test
        void equalsAndHashCodeAreValueBased() {
            PluginGuiHolder a = PluginGuiHolder.of(GuiViewKey.SQUARE,
                    new GuiPayload.SquarePage(3, "monthly"));
            PluginGuiHolder b = PluginGuiHolder.of(GuiViewKey.SQUARE,
                    new GuiPayload.SquarePage(3, "monthly"));

            assertEquals(a, b,
                    "key 与 payload 都相同的两个 holder 必须相等；"
                            + "否则事件间传递或集合去重会失效");
            assertEquals(a.hashCode(), b.hashCode(),
                    "相等对象的 hashCode 必须相等，否则放进 HashSet/HashMap 会重复或查不到");

            PluginGuiHolder differentPayload = PluginGuiHolder.of(GuiViewKey.SQUARE,
                    new GuiPayload.SquarePage(4, "monthly"));
            assertNotEquals(a, differentPayload,
                    "同 key 但 payload 不同（页码 3 vs 4）的 holder 必须不相等");
            assertNotEquals(a, null, "holder 与 null 必须不相等");
            assertNotEquals(a, "PluginGuiHolder{SQUARE}", "holder 与非同类对象必须不相等");
        }

        @Test
        void holderEqualityDistinguishesSamePageDifferentKey() {
            GuiPayload samePayload = new GuiPayload.Page(1);

            PluginGuiHolder square = PluginGuiHolder.of(GuiViewKey.SQUARE, samePayload);
            PluginGuiHolder squareHidden = PluginGuiHolder.of(GuiViewKey.SQUARE_HIDDEN, samePayload);

            assertNotEquals(square, squareHidden,
                    "SQUARE 与 SQUARE_HIDDEN 即使 payload 相同也必须不相等；"
                            + "这两页标题逐字相同（都取 square.title），"
                            + "若 holder 相等性漏掉 key，就再也无法区分它们");
            assertEquals(GuiViewKey.SQUARE, square.getKey(),
                    "key 必须原样返回 SQUARE");
            assertEquals(GuiViewKey.SQUARE_HIDDEN, squareHidden.getKey(),
                    "key 必须原样返回 SQUARE_HIDDEN");
        }

        @Test
        @SuppressWarnings("deprecation")
        void getInventoryReturnsNull() {
            // 这是故意的「文档化违约」：InventoryHolder#getInventory() 在 Bukkit API 里标注
            // @NotNull，但本类恒返回 null，以免 holder 与 inventory 形成双向强引用。
            // 见 PluginGuiHolder.java 的类 Javadoc「契约妥协」段（约 18-25 行）与
            // getInventory() 自身的 Javadoc（约 79-92 行）。
            // 本用例把这个违约钉死：若有人「顺手修好」成返回 inventory，必须先评审。
            assertNull(PluginGuiHolder.of(GuiViewKey.PROFILE).getInventory(),
                    "getInventory() 必须恒为 null；全仓不得调用它，只能读 getKey()/getPayload()");
        }
    }

    @Nested
    @DisplayName("GuiPayload 构造校验")
    class PayloadInvariants {

        @Test
        void pageRejectsZero() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> new GuiPayload.Page(0),
                    "页码 0 必须抛 IllegalArgumentException（页码 1 起）；"
                            + "若这里抛的是 NPE，说明校验语义被改成空值检查");
            assertTrue(ex.getMessage().contains(">= 1"),
                    "异常消息应说明合法下界是 1，实际为：" + ex.getMessage());
        }

        @Test
        void pageRejectsNegative() {
            assertThrows(IllegalArgumentException.class, () -> new GuiPayload.Page(-1),
                    "页码 -1 必须抛 IllegalArgumentException；负数页码会让 GUI 算出负的起始槽位");
        }

        @Test
        void pageAcceptsOne() {
            GuiPayload.Page page = assertDoesNotThrow(() -> new GuiPayload.Page(1),
                    "页码 1 是合法下界，不得拒绝");
            assertEquals(1, page.page(),
                    "第一个合法页码必须原样保留为 1");
        }

        @Test
        void squarePageRejectsNullModeKey() {
            assertThrows(NullPointerException.class,
                    () -> new GuiPayload.SquarePage(1, null),
                    "modeKey 为 null 必须抛 NullPointerException（Objects.requireNonNull），"
                            + "不是 IllegalArgumentException");
        }

        @Test
        void squarePageRejectsEmptyModeKey() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> new GuiPayload.SquarePage(1, ""),
                    "modeKey 为空串必须抛 IllegalArgumentException；"
                            + "空串能过 null 检查却匹配不到任何 RankingMode，会静默落回默认榜");
            assertTrue(ex.getMessage().contains("empty"),
                    "异常消息应说明 modeKey 不得为空，实际为：" + ex.getMessage());
        }

        @ParameterizedTest(name = "modeKey = \"{0}\"")
        @ValueSource(strings = {"weekly", "monthly", "alltime", "all", "feed"})
        void squarePageAcceptsAllDocumentedModeKeys(String modeKey) {
            // 这 5 个值来自 PluginGuiHolder 的类 Javadoc 约定，并与 SocialListener.modeFromKey
            // 的 switch 分支逐一对应（weekly/monthly/alltime/feed/all）。
            // 构造器本身不枚举白名单，只拦 null 与空串 —— 所以这里测的是「约定值可用」，
            // 而不是「其他值会被拒」。
            GuiPayload.SquarePage page = assertDoesNotThrow(
                    () -> new GuiPayload.SquarePage(1, modeKey),
                    "约定内的 modeKey \"" + modeKey + "\" 必须能构造成功");
            assertEquals(modeKey, page.modeKey(),
                    "模式键必须原样保留，否则 SocialListener.modeFromKey 会落到默认榜");
        }

        @Test
        void targetRejectsNullUuid() {
            assertThrows(NullPointerException.class,
                    () -> new GuiPayload.Target(null),
                    "目标 UUID 为 null 必须抛 NullPointerException；"
                            + "允许 null 会让「查看他人主页」静默退化成无归属");
        }

        @Test
        void targetAcceptsUuid() {
            UUID id = UUID.randomUUID();
            GuiPayload.Target target = new GuiPayload.Target(id);

            assertEquals(id, target.targetId(),
                    "目标 UUID 必须原样保留，否则他人主页会拉到错误玩家的数据");
        }

        @Test
        void recordsUseValueEquality() {
            assertEquals(new GuiPayload.Page(2), new GuiPayload.Page(2),
                    "record 值语义：同页码的 Page 必须相等");
            assertEquals(new GuiPayload.Page(2).hashCode(), new GuiPayload.Page(2).hashCode(),
                    "record 值语义：相等 Page 的 hashCode 必须相等");
            assertNotEquals(new GuiPayload.Page(2), new GuiPayload.Page(3),
                    "不同页码的 Page 必须不相等");

            assertEquals(new GuiPayload.SquarePage(1, "weekly"),
                    new GuiPayload.SquarePage(1, "weekly"),
                    "record 值语义：同 page + modeKey 的 SquarePage 必须相等");
            assertNotEquals(new GuiPayload.SquarePage(1, "weekly"),
                    new GuiPayload.SquarePage(1, "monthly"),
                    "modeKey 不同的 SquarePage 必须不相等，否则周榜与月榜共用 holder 会互相覆盖");
        }
    }
}
