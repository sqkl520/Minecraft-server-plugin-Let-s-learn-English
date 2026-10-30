package com.letslearnenglish.minecraftplugin.gui.identity;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;
import java.util.UUID;

/**
 * 插件自有 GUI 的 holder —— 把「这个 inventory 是谁开的、开到哪一页」变成结构性事实（R5-0）。
 *
 * <p>为什么需要它：迁移前所有 listener 都用 {@code event.getView().getTitle()} 做
 * {@code contains} 判定。标题是本地化文案（zh/en 两份、管理员可改、带占位符和动态后缀），
 * 既是性能热点也是正确性隐患 —— 改一个字丢音效、改一个字吞点击。holder 是 Bukkit 官方
 * 的身份通道：{@code Bukkit.createInventory(holder, size, title)} 会把 holder 挂到
 * inventory 上，之后 {@code view.getTopInventory().getHolder()} 直接答出身份，与文案无关。
 *
 * <p><b>契约妥协（重要，必须诚实说明）</b>：{@link InventoryHolder#getInventory()} 在
 * Bukkit API 中标注 {@code @NotNull}，但本类故意返回 {@code null}。原因是不想在 holder 与
 * inventory 之间制造双向强引用（holder 由 GUI 类先 new、再传给 createInventory，此时
 * inventory 尚不存在；若改成 createInventory 之后回填，就要给 holder 加可变状态，
 * 与「不可变身份对象」的定位冲突，也让 holder 无法安全跨线程/跨事件复用）。
 * 因此：<b>全仓不得调用 {@code holder.getInventory()}，只能读 {@link #getKey()} /
 * {@link #getPayload()}</b>。若将来确有需要，可在 Wave 2 之后改为构造时回填的
 * {@code WeakReference<Inventory>}，但那是行为变更，需要单独评审。
 *
 * <p>不可变 + 值语义（{@link #equals}/{@link #hashCode} 按 key + payload 比较），
 * 因此可以安全地被比较、放进集合、跨事件传递。
 */
public final class PluginGuiHolder implements InventoryHolder {

    private final GuiViewKey key;
    private final GuiPayload payload;

    private PluginGuiHolder(GuiViewKey key, GuiPayload payload) {
        this.key = Objects.requireNonNull(key, "key");
        this.payload = (payload == null) ? GuiPayload.NoPayload.INSTANCE : payload;
    }

    /**
     * 构造一个只带身份的 holder（payload = {@link GuiPayload.NoPayload#INSTANCE}）。
     *
     * @param key 屏幕身份键，不可为 null
     * @return 新的 holder
     */
    public static PluginGuiHolder of(GuiViewKey key) {
        return new PluginGuiHolder(key, GuiPayload.NoPayload.INSTANCE);
    }

    /**
     * 构造一个带 payload 的 holder。
     *
     * @param key     屏幕身份键，不可为 null
     * @param payload 屏幕上下文；null 视为 {@link GuiPayload.NoPayload#INSTANCE}
     * @return 新的 holder
     */
    public static PluginGuiHolder of(GuiViewKey key, GuiPayload payload) {
        return new PluginGuiHolder(key, payload);
    }

    /**
     * 屏幕身份键。
     *
     * @return 非 null
     */
    public GuiViewKey getKey() {
        return key;
    }

    /**
     * 屏幕上下文（页码 / 目标玩家 / 广场模式等）。
     *
     * @return 非 null；无上下文时为 {@link GuiPayload.NoPayload#INSTANCE}
     */
    public GuiPayload getPayload() {
        return payload;
    }

    /**
     * <b>故意违反 {@code @NotNull} 契约</b>：本 holder 只承载身份，不持有 inventory 引用。
     *
     * <p>Bukkit 只在极少数内部路径（如 {@code InventoryHolder} 的序列化/调试）会调用本方法，
     * 插件自有代码一次都不应调用。详见类 Javadoc 的「契约妥协」段。
     *
     * @return 恒为 {@code null}
     * @deprecated 不要调用；读 {@link #getKey()} 或 {@link #getPayload()}
     */
    @Override
    @Deprecated
    public Inventory getInventory() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginGuiHolder other)) return false;
        return key == other.key && payload.equals(other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, payload);
    }

    @Override
    public String toString() {
        return "PluginGuiHolder{key=" + key + ", payload=" + payload + '}';
    }

    /**
     * GUI 屏幕上下文（R5-0）。用 sealed interface + record 而不是
     * {@code Map<String,Object>}：编译期就能穷举所有屏幕形态，读取侧不需要
     * 强转和存在性判断，写错 key 直接编译失败。
     *
     * <p>为什么 {@code SquarePage} 的 mode 用 {@code String} 而不是 {@code RankingMode}：
     * 本包刻意不依赖 {@code core.progress}（避免 gui.identity 反向依赖业务层，
     * 也避免 Wave 2 各流为了一个模式字符串拖进整条 core 依赖链）。字符串取值约定为
     * {@code "weekly"} / {@code "monthly"} / {@code "alltime"} / {@code "all"} / {@code "feed"}。
     *
     * <p>关于 {@code permits}：这里刻意<b>不写</b>显式 {@code permits} 子句 —— 四个 record
     * 都声明在同一个编译单元里，JLS 的隐式 permitted 推断会直接封闭层级（record 本身
     * 是 final，无泄漏风险）；省略它同时避免「permitted 列表里用限定名引用自身嵌套类型」
     * 的作用域歧义。若将来把某个 record 拆到别的文件，必须补回 {@code permits}。
     */
    public sealed interface GuiPayload {

        /** 无上下文（单页屏幕，或页码由 GUI 内部状态决定）。 */
        record NoPayload() implements GuiPayload {
            /** 单例，避免每次构造垃圾对象。 */
            public static final NoPayload INSTANCE = new NoPayload();
        }

        /**
         * 仅页码上下文。
         *
         * @param page 1 起的页码；GUI 侧打开时已 clamp 到合法区间
         */
        record Page(int page) implements GuiPayload {
            public Page {
                if (page < 1) {
                    throw new IllegalArgumentException("page must be >= 1, got " + page);
                }
            }
        }

        /**
         * 英语广场：页码 + 模式。
         *
         * @param page    1 起的页码
         * @param modeKey "weekly"/"monthly"/"alltime"/"all"/"feed"（用小写字符串，
         *                避免本包依赖 {@code RankingMode}）
         */
        record SquarePage(int page, String modeKey) implements GuiPayload {
            public SquarePage {
                if (page < 1) {
                    throw new IllegalArgumentException("page must be >= 1, got " + page);
                }
                Objects.requireNonNull(modeKey, "modeKey");
                if (modeKey.isEmpty()) {
                    throw new IllegalArgumentException("modeKey must not be empty");
                }
            }
        }

        /**
         * 目标玩家上下文（他人主页 / 查看他人日历 / 玩家相关屏幕）。
         *
         * @param targetId 目标玩家 UUID，不可为 null
         */
        record Target(UUID targetId) implements GuiPayload {
            public Target {
                Objects.requireNonNull(targetId, "targetId");
            }
        }
    }
}
