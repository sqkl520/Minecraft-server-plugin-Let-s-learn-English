package com.letslearnenglish.minecraftplugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.RuntimeModules;

/**
 * {@link RuntimeModules#classify(boolean, boolean)} 的<b>纯函数真值表</b>。
 *
 * <p><b>为什么需要这个类</b>：{@code /lea reload} 的三条分支（装配 / 拆解 / 只刷配置）全部由
 * 这一个静态方法决定。它一旦判错，症状是「改了 {@code server.enabled} 但 reload 什么也没发生」
 * —— 而 {@code HotReloadManager} 里每一条 return 都只打日志，没有任何行为测试会因此变红
 *（真跑 {@code reloadAll()} 需要 Bukkit server + 完整 Manager 图，纯 JVM 起不来）。
 *
 * <p><b>为什么真值表要逐格写死</b>：四格里有两格是「无迁移」（NONE_ENABLED / NONE_DISABLED），
 * 但它们<b>语义不同</b> —— 一个表示「本来就开着」，一个表示「本来就关着」。如果实现把两者
 * 合并成同一个枚举值，编译通过、reload 也「正常」，但命令层文案会把「配置没变」说成
 * 「已重新加载」，正是本功能上一轮被投诉的谎报。所以四格必须各自独立断言。
 *
 * <p><b>本类的定位</b>：只测纯函数，不碰 Bukkit（不需要 mock server、不需要 Plugin 实例）。
 * 状态机的幂等性归 {@code RuntimeModulesLifecycleTest}，源码顺序归
 * {@code ServerToggleLintsTest}。
 */
class RuntimeTransitionTest {

    @Nested
    @DisplayName("真值表 —— 四格逐一定义")
    class TruthTable {

        @Test
        @DisplayName("false / false → NONE_DISABLED（启动即关闭，reload 只刷配置）")
        void notActiveAndDisabledIsNoOp() {
            assertEquals(RuntimeModules.Transition.NONE_DISABLED,
                    RuntimeModules.classify(false, false),
                    "runtimeActive=false + configEnabled=false 必须是 NONE_DISABLED 喵。\n"
                            + "这一格是「服务器启动时 server.enabled 就是 false」的稳态："
                            + "reload 不该装配任何东西，也不该拆任何东西，只把 yml 读进内存。\n"
                            + "若判成 DISABLE，reload 会去拆一个从未装过的运行时 —— "
                            + "beginDisable() 返回 false 后整段跳过，看似无害，但日志会说"
                            + "「已卸载」，与事实相反。");
        }

        @Test
        @DisplayName("false / true → ENABLE（本次 reload 把开关改开，必须动态装配）")
        void notActiveButEnabledIsEnable() {
            assertEquals(RuntimeModules.Transition.ENABLE,
                    RuntimeModules.classify(false, true),
                    "runtimeActive=false + configEnabled=true 必须是 ENABLE 喵。\n"
                            + "这一格是本功能的核心承诺：服主把 server.enabled 改成 true 后"
                            + "执行 /lea reload，插件必须真的把监听器/任务/Manager 装起来，"
                            + "而不是只刷新配置就宣布成功。\n"
                            + "若判成 NONE_*，开关改了但功能永远不出现 —— 且没有任何异常，"
                            + "唯一线索是日志里一句「config refreshed only」。");
        }

        @Test
        @DisplayName("true / true → NONE_ENABLED（本来就开着，走 legacy 完整热重载）")
        void activeAndEnabledIsNoOpEnabled() {
            assertEquals(RuntimeModules.Transition.NONE_ENABLED,
                    RuntimeModules.classify(true, true),
                    "runtimeActive=true + configEnabled=true 必须是 NONE_ENABLED 喵。\n"
                            + "这一格是绝大多数 reload 的真实路径：运行时已装配、配置仍开着，"
                            + "走 legacy 段（AI 重载 / 词库 / 玩家数据落盘 / 音效 / 统计服务）。\n"
                            + "若判成 ENABLE，会重新 enableAll() —— 全部 Manager 重建、"
                            + "监听器再注册一遍、定时任务排两份；若判成 DISABLE，玩家直接被"
                            + "踢出学习功能。");
        }

        @Test
        @DisplayName("true / false → DISABLE（本次 reload 把开关改关，必须动态拆解）")
        void activeButDisabledIsDisable() {
            assertEquals(RuntimeModules.Transition.DISABLE,
                    RuntimeModules.classify(true, false),
                    "runtimeActive=true + configEnabled=false 必须是 DISABLE 喵。\n"
                            + "这一格对应「服主在子服上关掉英语学习」：reload 必须真的拆运行时"
                            + "（停任务、清会话、落盘、反注册监听器），否则关闭态仍在跑定时任务"
                            + "和 HTTP 统计端口。\n"
                            + "注意 DISABLE 分支必须 return，绝不能 fall through 到 legacy 段 ——"
                            + "legacy 的 reloadWordBanks() 是裸调 plugin.getWordManager()，"
                            + "此时 wordManager 已被置空，NPE 会被当成 reload 失败上报。");
        }
    }

    @Nested
    @DisplayName("契约 —— classify 是纯函数")
    class Purity {

        /**
         * 同入参连调两次必须返回相同值。
         *
         * <p><b>防的是什么</b>：将来有人为了「记住上一次的判定结果」往 {@code RuntimeModules}
         * 里塞一个静态/实例缓存（例如 {@code lastTransition}），让 classify 变成读缓存的
         * 有状态方法。那种实现在单次调用下完全正确，真值表四格全绿，但在 reload 连发
         * （命令连点、或 reloadAll 内部重复判定）时会返回<b>上一次</b>的结果 ——
         * 于是「改开 → 改关 → 改开」的第二次改变被吞掉。
         *
         * <p><b>为什么连调四次而不是只调一次</b>：只调一次无法区分纯函数与「已被别的用例
         * 污染过的缓存」。这里对四格各连调两次，且顺序刻意交错（先 A→B→A），
         * 让「按上次入参缓存」的实现必然在 A 的第二次调用上露馅。
         */
        @Test
        @DisplayName("同入参重复调用结果稳定，且不受上一次调用影响")
        void classifyIsStateless() {
            // 交错顺序：把同一组入参拆到两次不相邻的调用上。
            RuntimeModules.Transition first = RuntimeModules.classify(false, true);
            RuntimeModules.Transition other = RuntimeModules.classify(true, false);
            RuntimeModules.Transition second = RuntimeModules.classify(false, true);

            assertEquals(first, second,
                    "classify(false, true) 两次调用返回了不同的值 喵（第一次=" + first
                            + "，第二次=" + second + "）。\n"
                            + "这说明 classify 不是纯函数：有内部状态（缓存 / 计数器 / 静态字段）"
                            + "被上一次 classify(true, false) 的调用改写了。\n"
                            + "后果是 reload 的连发场景（命令连点、或同一次 reload 里重复判定）"
                            + "会拿到过期的判定，「改开 → 改关 → 改开」的第二次改变被静默吞掉。\n"
                            + "修复方向：classify 只能读入参，不得写/读任何字段。");
            assertEquals(RuntimeModules.Transition.ENABLE, first,
                    "classify(false, true) 首次调用就不是 ENABLE 喵 —— 先修真值表（见 TruthTable）。");
            assertEquals(RuntimeModules.Transition.DISABLE, other,
                    "classify(true, false) 不是 DISABLE 喵 —— 先修真值表（见 TruthTable）。");

            // 四格各再核一次：确保不是「只有某一格碰巧稳定」。
            assertNotNull(RuntimeModules.classify(false, false), "classify 不得返回 null 喵");
            assertEquals(RuntimeModules.Transition.NONE_DISABLED, RuntimeModules.classify(false, false),
                    "classify(false, false) 在交错调用之后变了 喵。");
            assertEquals(RuntimeModules.Transition.NONE_ENABLED, RuntimeModules.classify(true, true),
                    "classify(true, true) 不是 NONE_ENABLED 喵。");
        }
    }
}
