package com.letslearnenglish.minecraftplugin.gui.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code /lea reload} 动态装卸的<b>源码级 lint</b>（不是行为测试）。
 *
 * <p><b>为什么这一组必须是 lint 而不是行为测试</b>：{@code disableAll()} 的价值几乎全部在
 * 「<b>步骤之间的顺序</b>」上，而顺序在 mock 世界里基本不可观测 ——
 * <ul>
 *   <li>关 GUI 触发 {@code InventoryCloseEvent} 需要真实 Bukkit 事件总线和真实
 *       {@code InventoryView}，纯 JVM mock 下事件压根不会派发，所以「先关 GUI 后清会话」
 *       这个<b>凭空起倒计时任务</b>的变异在行为测试里毫无症状；</li>
 *   <li>「落盘前清缓存」需要真实 {@code PlayerDataStore} 与真实 DB 才能看出进度丢失；
 *       mock 的 {@code savePlayerData(UUID)} 是 no-op，先调后调一模一样；</li>
 *   <li>「提前反注册」的伤害恰恰是让 D3 里的 early-return 守卫<b>不再被调用</b> ——
 *       而没有任何断言在数「守卫被走过几次」。反过来，提前反注册会让症状从「可观测」
 *       退化成「不可见」，这正是它危险的原因。</li>
 * </ul>
 * 这些变异删掉/挪动一行之后，{@code mvn test} 全绿、插件正常启动、只在服主真正改
 * {@code server.enabled} 的那一次 reload 上表现成「玩家进度丢了」「重开后卡在练习中」。
 *
 * <h2>八条 lint</h2>
 * <ol>
 *   <li><b>L1</b> {@code disableAll()} 里各步骤锚点的下标<b>严格递增</b>；</li>
 *   <li><b>L2</b> {@code HandlerList.unregisterAll(} 排在所有清理步骤之后；</li>
 *   <li><b>L3</b> {@code savePlayerData(} 排在 {@code clearAllSessions()} /
 *       {@code dismissAllActiveDuels()} 之后；</li>
 *   <li><b>L4</b> {@code enableAll()} 里六处装配调用各恰好 1 次；</li>
 *   <li><b>L5</b> {@code isFullyEnabled()} 同时含三个判据；</li>
 *   <li><b>L6</b> {@code scheduleNextMidnightRollover} 的首行守卫同时含
 *       {@code isEnabled()} 与 {@code isRuntimeActive()}；</li>
 *   <li><b>L7</b> {@code clearAllSessions()} 与 {@code resetTransientState()} 各自被
 *       {@code try { } catch} 包住（括号配对，不是简单 indexOf）；</li>
 *   <li><b>L8</b> {@code SoundManager.playSound} 全参静态方法体内仍有
 *       {@code manager == null} 守卫。</li>
 * </ol>
 *
 * <p><b>本类与 {@code B3WiringLintsTest} / {@code GuiWiringLintsTest} /
 * {@code SessionLoopLintsTest} 零耦合</b>：{@code stripComments} / {@code extractBody} /
 * {@code srcRoot} / {@code sourceOf} 在这里<b>各自独立实现一遍</b>。把它们的私有工具
 * 复制过来当然更短，但那边为了「GUI 覆盖」「会话生命周期」调宽过剥注释的容错（例如允许
 * 未闭合的文本块到文末）；本组 lint 读的是同一个 7000 行入口类，一旦继承那份容错，
 * 一处语法边缘情况就会让<b>两边同时静默失效</b>，而失败消息还指向另一个文件。
 * 重复几十行，换来四组 lint 的失效模式互相独立。
 *
 * <h2>失败消息的约定</h2>
 * <p>每条断言都写明这是<b>文本形状 / 语义锚点</b>检查，比字符串匹配更严格。合法的等价
 * 重构（抽辅助方法、把 if 改成 guard clause、把顺序换成等价的数据驱动循环）会让它们
 * <b>误红</b>。误红必须<b>由人裁决并同步本 lint</b>，绝不允许为了让 lint 过而把实现改回
 * 错误顺序 —— 那等于用测试去固化一个已经证明有害的形状。
 */
class ServerToggleLintsTest {

    private static final String MAIN = "LetsLearnEnglish.java";

    @Nested
    @DisplayName("ServerToggle（动态装卸 lint，读源码断言）")
    class ServerToggle {

        // ==================================================================
        // L1：disableAll 的步骤顺序
        // ==================================================================

        /**
         * L1：{@code disableAll()} 方法体内九个锚点的下标必须<b>严格递增</b>。
         *
         * <p>顺序表（每一步的理由都在生产代码的 D0~D9 注释里）：
         * <pre>
         * runtimeActive = false          ← 必须是第一行（事件回环的闸）
         *   &lt; cancelTaskQuietly / midnightTask   ← 先停生产新工作的源头
         *   &lt; endConversation
         *   &lt; clearAllSessions
         *   &lt; dismissAllActiveDuels
         *   &lt; closeIfPluginGui
         *   &lt; savePlayerData
         *   &lt; flushPending
         *   &lt; HandlerList.unregisterAll
         * </pre>
         *
         * <p>两个最容易被「优化」掉的相邻关系：
         * <ol>
         *   <li><b>clearAllSessions 必须在 closeIfPluginGui 之前</b>。反过来时，
         *       {@code WordLearningListener.onInventoryClose} 的三道守卫变成
         *       「holder 是插件 GUI → hasActiveQuiz 为 true → isAnswering 为 true」，
         *       于是每关一个界面就 {@code startAnswerForCurrentWord()} 凭空起一个 20L
         *       周期倒计时任务，还给「即将断开」的玩家推答题提示。任务没人持有、没人 cancel，
         *       插件重装后仍在跑。</li>
         *   <li><b>savePlayerData 必须在 clearAllSessions 之后</b>（L3 单独再钉一次）：
         *       {@code clearAllSessions} 的 {@code endSession} 会把掌握度/累计分/复习种子写进
         *       内存，全量落盘必须能看到这些写入。反过来的话，先落盘再清会话 = 这一轮
         *       学习成果只在内存里活到 D10 的 {@code playerDataStore = null}。</li>
         * </ol>
         */
        @Test
        @DisplayName("L1：disableAll 内九个锚点下标严格递增（顺序即语义）")
        void disableStepsAreInOrder() {
            String body = methodBody(MAIN, "public void disableAll()");

            // 锚点按期望顺序排列。`midnightTask` 用 `midnightTask = null` 更精确：
            // `midnightTask` 裸词在方法体里出现两次（cancel 调用 + 置空），
            // 只查裸词会命中较晚的那个，反倒让「消息取消被挪到后面」这类变异滑过去。
            String[] anchors = {
                    "runtimeActive = false",
                    "cancelTaskQuietly(",
                    "midnightTask = null",
                    "endConversation(",
                    "clearAllSessions()",
                    "resetTransientState()",
                    "dismissAllActiveDuels()",
                    "closeIfPluginGui(",
                    "savePlayerData(",
                    "flushPending(",
                    "HandlerList.unregisterAll(",
            };

            int[] offsets = new int[anchors.length];
            for (int i = 0; i < anchors.length; i++) {
                int at = body.indexOf(anchors[i]);
                assertTrue(at >= 0,
                        "disableAll() 方法体里找不到锚点「" + anchors[i] + "」喵。\n"
                                + "锚点消失有两种可能，都必须由人裁决：\n"
                                + "  ① 这一步的清理被整段删除 —— 对应模块在 reload 后不会被拆，"
                                + "监听器/任务/会话跨代存活；\n"
                                + "  ② 步骤被搬到辅助方法里（等价重构）—— 本 lint 跟着失效，"
                                + "请把锚点改到新的调用点上。\n"
                                + "本 lint 是文本形状断言；绝不允许为了让 lint 过而把清理删掉。");
                offsets[i] = at;
            }

            for (int i = 1; i < anchors.length; i++) {
                assertTrue(offsets[i] > offsets[i - 1],
                        "disableAll() 的步骤顺序被打乱了喵：\n"
                                + "  期望「" + anchors[i - 1] + "」(偏移 " + offsets[i - 1] + ")"
                                + " 在「" + anchors[i] + "」(偏移 " + offsets[i] + ") <b>之前</b>，"
                                + "实际相反。\n"
                                + "完整实测偏移：" + render(anchors, offsets) + "\n"
                                + "最危险的两处互换：\n"
                                + "  · 先 closeIfPluginGui 后 clearAllSessions → 每关一个界面就凭空"
                                + "起一个 20L 答题倒计时任务（WordLearningListener.onInventoryClose 的"
                                + " hasActiveQuiz 守卫此时仍为 true），任务无人持有、永不 cancel；\n"
                                + "  · 先 savePlayerData 后 clearAllSessions / dismissAllActiveDuels →"
                                + " endSession 写进内存的掌握度/累计分/复习种子落在落盘之后，"
                                + "随 D10 的字段置空一起丢失。\n"
                                + "这是语义锚点检查（顺序就是本方法的全部价值）；"
                                + "若为真正等价的顺序重构，请先由人确认无害，再同步本 lint。");
            }
        }

        // ==================================================================
        // L2：反注册必须在所有清理之后
        // ==================================================================

        /**
         * L2：{@code HandlerList.unregisterAll(} 必须排在 {@code clearAllSessions()} /
         * {@code dismissAllActiveDuels()} / {@code closeIfPluginGui(} /
         * {@code savePlayerData(} 之后。
         *
         * <p>为什么单列一条（L1 的递增链已经覆盖）：L1 是一条链，链上任意一环消失就整条
         * 失效；这条只依赖「反注册 vs 四个清理点」的<b>两两关系</b>，即使将来 D2/D3 之间
         * 插入新步骤、或 L1 因为新锚点被改宽，这条仍然独立成立。
         *
         * <p>变异后果：提前反注册 → D2~D5 期间本插件的事件回环<b>不再走自己的处理器</b>。
         * 表面上「更干净」，实际是：
         * <ul>
         *   <li>D3 的「先清会话后关 GUI」论证变成空谈 —— 守卫<b>压根不存在</b>，
         *       而不是「early return」，两者在「无副作用」上等价，但一旦有人把
         *       clearAllSessions 挪到关 GUI 之后，症状就从「守卫拦住」退化成
         *       「没人拦住且没人知道」（DuelListener.onPlayerQuit 等收尾同理）；</li>
         *   <li>D5 关 GUI 期间玩家刚好 quit，{@code DuelListener.onPlayerQuit} 不再被调用，
         *       决斗的离线记账丢失。</li>
         * </ul>
         */
        @Test
        @DisplayName("L2：HandlerList.unregisterAll 排在四个清理步骤之后")
        void unregisterHappensAfterAllCleanup() {
            String body = methodBody(MAIN, "public void disableAll()");

            int unregister = body.indexOf("HandlerList.unregisterAll(");
            assertTrue(unregister >= 0,
                    "disableAll() 里找不到 HandlerList.unregisterAll( 喵。\n"
                            + "整段反注册被删 = reload 后旧监听器全部还在："
                            + "旧的 Manager 已被 D10 置空，事件打到 null 字段上，"
                            + "每个事件都刷一条 NPE 堆栈，且新装配的监听器叠加在旧的上，"
                            + "同一事件业务执行两次（金币/进度翻倍）。\n"
                            + "这条是语义锚点检查；若改成逐个 unregister（有记账清单）请同步本 lint。");

            String[] mustPrecede = {
                    "clearAllSessions()",
                    "dismissAllActiveDuels()",
                    "closeIfPluginGui(",
                    "savePlayerData(",
            };
            for (String anchor : mustPrecede) {
                int at = body.indexOf(anchor);
                assertTrue(at >= 0,
                        "disableAll() 里找不到「" + anchor + "」喵 —— 先修 L1 的锚点。");
                assertTrue(unregister > at,
                        "HandlerList.unregisterAll( 出现在「" + anchor + "」<b>之前</b> 喵"
                                + "（反注册偏移=" + unregister + "，" + anchor + " 偏移=" + at + "）。\n"
                                + "提前撤监听器让 D2~D5 的清理变成「绕过守卫」而不是「守卫 early return」：\n"
                                + "  · D3「先清会话后关 GUI」的整个论证都以监听器还注册着为前提；\n"
                                + "  · D5 关 GUI 期间玩家 quit，DuelListener.onPlayerQuit 不再收尾，"
                                + "决斗离线记账丢失。\n"
                                + "症状不可见的原因：撤得早反而「看起来没报错」—— 没有监听器就没有回环，"
                                + "也就没有 NPE。\n"
                                + "这是语义锚点检查；若为等价重构（改为逐个反注册且清单完整），请同步本 lint。");
            }
        }

        // ==================================================================
        // L3：落盘在清缓存之后
        // ==================================================================

        /**
         * L3：{@code savePlayerData(} 必须排在 {@code clearAllSessions()} 与
         * {@code dismissAllActiveDuels()} 之后。
         *
         * <p>这是一条<b>数据丢失</b>断言，和 L1/L2 的「顺序」不是同一个维度：
         * <ul>
         *   <li>{@code clearAllSessions()} 内部走 {@code endSession}，把掌握度、累计分、
         *       复习种子写进 {@code PlayerDataStore} 的<b>内存缓存</b>；</li>
         *   <li>{@code dismissAllActiveDuels()} 的 {@code cancelDuelInternal} 会把
         *       「已答对题数」这类统计写进内存；</li>
         *   <li>{@code savePlayerData(UUID)} 是把内存缓存 UPSERT 进库的唯一一步。</li>
         * </ul>
         * 顺序反过来，玩家这一轮的学习成果只活到 D10 的 {@code playerDataStore = null}。
         * 而 {@code /lea reload} 之后玩家<b>不会下线</b>，看不到任何异常 —— 症状是
         * 「reload 之后我的掌握度倒退了几分钟」。
         */
        @Test
        @DisplayName("L3：savePlayerData 在 clearAllSessions / dismissAllActiveDuels 之后")
        void saveHappensAfterSessionTeardown() {
            String body = methodBody(MAIN, "public void disableAll()");

            int save = body.indexOf("savePlayerData(");
            assertTrue(save >= 0,
                    "disableAll() 里找不到 savePlayerData( 喵。\n"
                            + "整段落盘被删 = reload/关服时在线玩家的进度全部丢失"
                            + "（内存缓存随 D10 置空），而插件看起来一切正常。");

            for (String anchor : new String[]{"clearAllSessions()", "dismissAllActiveDuels()"}) {
                int at = body.indexOf(anchor);
                assertTrue(at >= 0, "disableAll() 里找不到「" + anchor + "」喵 —— 先修 L1。");
                assertTrue(save > at,
                        "savePlayerData( 出现在「" + anchor + "」<b>之前</b> 喵"
                                + "（落盘偏移=" + save + "，" + anchor + " 偏移=" + at + "）。\n"
                                + "「" + anchor + "」会把这一轮的掌握度 / 累计分 / 复习种子写进"
                                + " PlayerDataStore 的内存缓存；savePlayerData 是把缓存 UPSERT 进库的"
                                + "唯一一步。反过来的顺序 = 这批写入落在落盘之后，"
                                + "随 D10 的 playerDataStore = null 一起消失。\n"
                                + "为什么测试看不见：mock 的 savePlayerData(UUID) 是 no-op，"
                                + "先调后调一模一样；真症状只在真实 DB + 真实会话上显现，"
                                + "表现为「reload 之后掌握度倒退」。\n"
                                + "这是语义锚点检查；若为等价重构（把落盘收进一个更晚的统一 flush），"
                                + "请同步本 lint —— 但落盘必须仍在缓存写入之后。");
            }
        }

        // ==================================================================
        // L4：enableAll 的六处装配调用各恰好一次
        // ==================================================================

        /**
         * L4：{@code enableAll()} 方法体内六处装配调用各<b>恰好 1 次</b>。
         *
         * <p>为什么是「恰好一次」而不是「至少一次」：
         * <ul>
         *   <li><b>0 次</b> = 装配掉了一行。其中 {@code new OnboardingManager(} 最阴：
         *       它会编译通过、启动无报错、日志一切正常，只是新手引导永远不出现；
         *       {@code startScheduledTasks()} 掉了 = 自动保存/复习提醒/午夜结算永久不跑，
         *       玩家进度只在退出时落盘。</li>
         *   <li><b>≥2 次</b> = 复制粘贴出第二段。监听器注册两遍（同一事件业务执行两次）、
         *       任务排两份（金币/提醒翻倍）、Manager 有第二个实例而字段只指向最后一个
         *       （前一个永远无法收尾）。</li>
         * </ul>
         *
         * <p>为什么钉在 {@code enableAll()} 上：{@code /lea reload} 的 ENABLE 分支直接调它。
         * 这些调用原本在 {@code onEnable} 里，A 流把它们整体搬进了 {@code enableAll()}
         * —— 搬迁是最容易漏行的时刻，而漏行不产生任何编译错误。
         */
        @Test
        @DisplayName("L4：enableAll 内六处装配调用各恰好 1 次")
        void assemblyCallsOccurExactlyOnce() {
            String body = methodBody(MAIN, "public void enableAll()");

            String[][] anchors = {
                    {"new WordLearningManager(", "答题会话状态机；掉了 = 玩家无法答题（GUI 打开后无倒计时）"},
                    {"new OnboardingManager(", "新手引导（构造即自注册监听器）；掉了 = 引导永不出现，编译与启动全绿"},
                    {"proxyMessenger.register()", "代理消息通道；掉了 = Velocity/Bungee 子服之间不同步"},
                    {"papiExpansion.register()", "PlaceholderAPI 扩展；掉了 = %lle_score% 等占位符全部失效"},
                    {"startScheduledTasks()", "周期任务（自动保存/复习提醒/午夜结算）；掉了 = 进度只在退出时落盘"},
                    {"registerListeners()", "全部事件监听器；掉了 = 插件变成纯命令壳，聊天判题/决斗/GUI 全哑"},
            };

            for (String[] anchor : anchors) {
                List<Integer> hits = occurrences(body, anchor[0]);
                assertEquals(1, hits.size(),
                        "enableAll() 方法体里「" + anchor[0] + "」出现了 " + hits.size()
                                + " 次（偏移=" + hits + "），期望恰好 1 次 喵。\n"
                                + "语义：" + anchor[1] + "。\n"
                                + "0 次 = 装配漏行（能编译、能启动、日志正常，只是功能静默缺失）；"
                                + "2 次以上 = 复制粘贴出第二段（监听器注册两遍 → 同一事件业务执行两次，"
                                + "任务排两份 → 金币/提醒翻倍，前一个 Manager 实例永远无法收尾）。\n"
                                + "本 lint 读的是 enableAll() 的方法体，把调用挪进辅助方法会误红 —— "
                                + "这是有意的：/lea reload 的动态装配只走这一条路径，"
                                + "抽走后本 lint 就失去了唯一的观测点。若确需抽取，请同步本 lint "
                                + "到新的调用链上（但『恰好一次』的语义必须保留）。");
            }
        }

        // ==================================================================
        // L5：isFullyEnabled 的三个判据
        // ==================================================================

        /**
         * L5：{@code isFullyEnabled()} 的方法体必须同时含 {@code isRuntimeActive()}、
         * {@code isServerEnabled()}、{@code playerDataStore != null}。
         *
         * <p>三个判据各挡一个窗口，缺任何一个都会在某条路径上放行到 null Manager：
         * <ol>
         *   <li>{@code isRuntimeActive()} —— reload 把开关改关的瞬间，配置已是 false 但拆解
         *       还没跑完；反过来改开时配置已是 true 而装配还没完成。这个布尔量是最早、
         *       最粗的一道闸。</li>
         *   <li>{@code isServerEnabled()} —— 稳态下的总开关（启动即关闭的服务器从没装过运行时）。</li>
         *   <li>{@code playerDataStore != null} —— 「已装配」的具体证据，也是
         *       {@code HotReloadManager} 短路用的<b>同一个</b>标记。少了它，两个类对
         *       「装没装」的判断会漂移。</li>
         * </ol>
         *
         * <p>变异：有人把 {@code isRuntimeActive()} 换成 {@code isEnabled()}（看着更自然）。
         * reload 期间 {@code isEnabled()} 恒 true，这个判据当场变成恒真 —— 命令在拆解窗口里
         * 一路走到 null Manager 上 NPE。而 {@code ServerToggleTest} 的六个用例注入的
         * {@code runtimeActive} 默认 false，`pds` 与配置打桩，换判据后<b>照样全绿</b>
         * （新增的判据反而被 mock 的默认值满足了）。
         */
        @Test
        @DisplayName("L5：isFullyEnabled 同时含运行时、配置、已装配三个判据")
        void fullyEnabledChecksAllThreeGates() {
            String body = methodBody(MAIN, "public boolean isFullyEnabled()");

            String[][] gates = {
                    {"isRuntimeActive()", "运行时闸", "reload 拆解/装配窗口里唯一的真信号；"
                            + "换成 isEnabled()（reload 期间恒 true）等于闸门消失"},
                    {"isServerEnabled()", "配置闸", "稳态总开关；掉了 = 关闭态服务器也放行命令"},
                    {"playerDataStore != null", "已装配判据", "与 HotReloadManager 短路用的同一语义；"
                            + "掉了 = 两个类对「装没装」的判断会漂移"},
            };

            for (String[] gate : gates) {
                assertTrue(body.contains(gate[0]),
                        "isFullyEnabled() 的方法体里找不到「" + gate[0] + "」喵 —— "
                                + "<b>" + gate[1] + "</b> 被删或被换掉了。\n"
                                + "后果：" + gate[2] + "。\n"
                                + "完整方法体：" + body.strip() + "\n"
                                + "为什么行为测试抓不到：ServerToggleTest 的 FullyEnabledGate 组用"
                                + " mock 注入 configManager 与 playerDataStore，"
                                + "换掉某个判据后 mock 的默认值往往仍然满足，六个用例全绿。\n"
                                + "这是语义锚点检查；若为等价重构（例如把三个判据收进一个"
                                + " runtimeReady() 辅助方法），请同步本 lint —— 但三个语义必须都在。");
            }
        }

        // ==================================================================
        // L6：午夜任务的首行守卫
        // ==================================================================

        /**
         * L6：{@code scheduleNextMidnightRollover()} 的<b>首行守卫</b>必须同时含
         * {@code isEnabled()} 与 {@code isRuntimeActive()}。
         *
         * <p>午夜任务会自续（任务体末尾再调本方法排下一天）。原守卫只有
         * {@code if (!isEnabled()) return;}，而 {@code /lea reload} 期间插件<b>一直</b>
         * enabled —— 任务若恰好在拆解之后触发，就会把下一天的任务重排进禁用态，
         * 留下一个「关闭了也还在跑」的活任务，且没人持有它的句柄（{@code midnightTask}
         * 已在 D1 被 cancel 并置空，新排的那个覆盖字段后又被 D10 丢弃）。
         *
         * <p>断言「首行」而不是「方法体里含」：守卫只要不在第一行，前面的取值/日志就可能
         * 先跑；更重要的是，「首行守卫」是这段代码唯一的正确形状，把它挪到 try 里或
         * 挪到 {@code getScheduler()} 之后都会让 {@code /lea reload} 的竞态窗口重新打开。
         */
        @Test
        @DisplayName("L6：scheduleNextMidnightRollover 首行守卫同时含 isEnabled 与 isRuntimeActive")
        void midnightGuardChecksRuntimeToo() {
            String body = methodBody(MAIN, "private void scheduleNextMidnightRollover()");

            // 取方法体第一行非空语句（break 掉行首的空白）。
            String firstStatement = firstStatement(body);
            assertTrue(firstStatement.contains("isEnabled()"),
                    "scheduleNextMidnightRollover 的首行守卫里找不到 isEnabled() 喵。\n"
                            + "实测首行：" + firstStatement + "\n"
                            + "（去掉它 = 插件停用后午夜任务仍会自续，往一个已 shutdown 的"
                            + " scheduler 里排任务。）");
            assertTrue(firstStatement.contains("isRuntimeActive()"),
                    "scheduleNextMidnightRollover 的首行守卫里找不到 isRuntimeActive() 喵。\n"
                            + "实测首行：" + firstStatement + "\n"
                            + "/lea reload 期间 isEnabled() <b>恒为 true</b>，只靠它救不了 ——"
                            + "任务一旦在拆解之后触发，就会把下一天的任务重排进禁用态，"
                            + "留下一个没人持有的活任务（midnightTask 字段已被 D1 取消并置空）。\n"
                            + "为什么这条必须是「首行」而不是「方法体里含」：守卫放到 getScheduler() "
                            + "之后，那段调用本身就会在禁用态执行（部分实现在 scheduler 已关闭时抛"
                            + " IllegalStateException）。\n"
                            + "这是语义锚点检查；若为等价重构（例如守卫抽成 shouldScheduleMidnight()），"
                            + "请同步本 lint —— 但必须仍在方法的第一条语句位置生效。");
        }

        // ==================================================================
        // L7：D3 两处清理各自被 try 包住
        // ==================================================================

        /**
         * L7：{@code disableAll()} 里 {@code clearAllSessions()} 与
         * {@code resetTransientState()} 的调用点都落在某个 {@code try { ... } catch} 块内。
         *
         * <p>用<b>括号配对</b>判断而不是「前面 200 字符里有 'try'」：后者会被
         * {@code try} 出现在上一步里、或注释/字符串里的 "try" 骗过。这里从调用点向前找
         * 最近的 {@code try}，要求它紧跟 {@code {} 且配对的右花括号<b>越过</b>调用点。
         *
         * <p>为什么逐条包 try 而不是整段包一个：{@code clearAllSessions()} 抛异常时
         * {@code resetTransientState()} 仍必须跑（它们补的是两个不相交的漏：
         * session 表 vs practiceWords/deathPausedQuiz）。整段一个 try 会让前半失败时
         * 后半跳过，玩家重开后卡在「练习进行中」。
         */
        @Test
        @DisplayName("L7：clearAllSessions 与 resetTransientState 各自被 try/catch 包住")
        void transientStateResetsAreIndividuallyGuarded() {
            String body = methodBody(MAIN, "public void disableAll()");

            for (String call : new String[]{"clearAllSessions()", "resetTransientState()"}) {
                int at = body.indexOf(call);
                assertTrue(at >= 0, "disableAll() 里找不到「" + call + "」喵 —— 先修 L1。");
                assertTrue(insideTryBlock(body, at),
                        "「" + call + "」的调用点不在任何 try/catch 块内 喵（偏移=" + at + "）。\n"
                                + "两个方法都必须逐条 try：clearAllSessions() 抛异常时"
                                + " resetTransientState() 仍必须执行 —— 它们补的是两个不相交的漏"
                                + "（session 表 vs practiceWords / deathPausedQuiz）。"
                                + "整段共用一个 try 会让前半失败时后半被跳过，"
                                + "玩家重开后卡在「练习进行中」。\n"
                                + "更根本的风险：disableAll() 由 /lea reload 触发，"
                                + "没有 Bukkit 的 disable 兜底。任何一步抛出未捕获异常都会"
                                + "中止后续 D4~D10（决斗不拆、GUI 不关、数据不落盘、"
                                + "监听器不反注册），而日志只留一条栈。\n"
                                + "本断言用括号配对判断（从调用点向前找最近的 try，要求其配对的"
                                + "右花括号越过调用点），不是简单的 indexOf；若为等价重构"
                                + "（例如把两步收进一个自带 try 的辅助方法），请同步本 lint。");
            }
        }

        // ==================================================================
        // L8：SoundManager 静态入口的 null 守卫
        // ==================================================================

        /**
         * L8：{@code SoundManager.playSound(Player, String, float, float)} 全参静态方法体内
         * 仍有 {@code manager == null} 守卫。
         *
         * <p>这是<b>关闭态</b>的最后一道回声：{@code server.enabled=false} 时
         * {@code soundManager} 字段恒为 null，而 {@code SoundManager.playSound} 是
         * <b>静态</b>入口、被 ~60 处调用点直接调用（不经过 plugin 实例）。
         * {@code disableAll()} 的 D10 会把它置空，插件仍活着 —— 于是 reload 之后玩家打开
         * 任意界面，{@code onInventoryOpen} 以外的大量调用点都会走到这里。
         * 少了这道守卫 = 每次点击刷一条 NPE 栈，且部分调用点在事件处理器里，
         * 会让后续业务（发奖、更新进度）一起中断。
         */
        @Test
        @DisplayName("L8：SoundManager.playSound 静态入口仍含 manager == null 守卫")
        void soundEntryPointKeepsNullGuard() {
            String body = methodBody("SoundManager.java",
                    "public static void playSound(Player player, String configKey, float volume, float pitch)");

            assertTrue(body.contains("manager == null"),
                    "SoundManager.playSound(Player, String, float, float) 里找不到 "
                            + "`manager == null` 守卫 喵。\n"
                            + "关闭态（server.enabled=false）与 /lea reload 的拆解窗口里，"
                            + "LetsLearnEnglish.getSoundManager() 返回 null，而本方法是<b>静态</b>"
                            + "入口、被约 60 处调用点直接调用。少了这道守卫，"
                            + "每一次 GUI 打开/点击/答题判定都会刷一条 NPE 栈，"
                            + "并且会中断所在事件处理器的后续业务（发奖、进度更新）。\n"
                            + "注意：本 lint 只认 `manager == null` 这一种写法；"
                            + "若改成 Objects.requireNonNull / Optional / 早返回的辅助方法，"
                            + "会误红 —— 请先由人确认「null 时静默跳过而不是抛」语义未变，"
                            + "再同步本 lint。绝不允许为了让 lint 过而删掉守卫。");
        }

        /**
         * L9：{@code disableAll()} 的 D10 必须调 {@code LetsLearnEnglishAPI.forceClearInstance()}。
         *
         * <p><b>为什么这条必须是 lint 而不是行为测试</b>：{@code disableAll()} 依赖几十个
         * Manager 与真实 Bukkit，纯 JVM 跑不起来。而「漏掉这一行」的后果是
         * <ul>
         *   <li>编译通过；</li>
         *   <li>插件正常装卸；</li>
         *   <li>{@code LetsLearnEnglishAPILifecycleTest} 那 4 条<b>全绿</b> ——
         *       它们测的是 {@code forceClearInstance()} 本身，没人测「谁调了它」
         *       （这正是「自证桩」形态：被测对象是自己造的，不是生产路径）。</li>
         * </ul>
         * 唯一的受害者是第三方插件：停用后 {@code getInstance()} 仍非 null，
         * 它拿到一个所有 getter 都返回 null Manager 的僵尸对象，NPE 落在自己那一侧。
         *
         * <p>红队实测：把 D10 那行注释掉，全部测试仍然通过 —— 所以这条 lint 是必要的。
         */
        @Test
        @DisplayName("L9：disableAll 的 D10 必须注销 API 单例（否则第三方拿到僵尸对象）")
        void disableAllClearsApiSingleton() {
            String body = methodBody(MAIN, "public void disableAll()");

            assertTrue(body.contains("forceClearInstance()"),
                    "disableAll() 里找不到 forceClearInstance() 喵 —— D10 没注销 API 单例。\n"
                            + "getInstance() 是第三方插件拿本插件 API 的<b>唯一</b>入口。"
                            + "运行时拆掉后如果静态引用还指着旧实例，第三方会拿到一个"
                            + "「所有 getter 都返回 null Manager」的僵尸对象 —— 它通常只判"
                            + " getInstance() != null 就继续用，于是 NPE 发生在<b>调用方</b>那一侧，"
                            + "而「本插件未启用」这个本可理解的事实反而消失了。\n"
                            + "清掉之后，「停用后拿不到 API」与「启动时就是 server.enabled=false」"
                            + "完全同构 —— 那正是第三方文档应该承诺的单一语义。\n"
                            + "注：本 lint 只验调用存在；若改成 clearInstance() 请先确认"
                            + "「插件已 disabled 时它会清」这条分支成立，再同步本 lint。");
        }
    }

    // ==================================================================
    // 辅助方法（本类独立实现，刻意不复用 B3WiringLintsTest 的私有工具）
    // ==================================================================

    /** 插件源码根；找不到直接 fail（绝不 skip —— 跳过会让整组 lint 静默失效）。 */
    private static Path srcRoot() {
        Path root = Paths.get(System.getProperty("user.dir"), "src", "main", "java",
                "com", "letslearnenglish", "minecraftplugin");
        if (!Files.isDirectory(root)) {
            fail("找不到源码根 " + root.toAbsolutePath()
                    + " —— 测试须从仓库根目录运行（mvn test 默认满足）喵。");
        }
        return root;
    }

    /**
     * 按文件名在源码树中唯一定位并返回<b>剥注释 + 抹字面量</b>后的源码。
     *
     * <p>按文件名而不是拼包路径：本类要读的两个文件分处
     * {@code minecraftplugin/}（入口类）与 {@code minecraftplugin/core/}（SoundManager）。
     * 写死包路径会在「文件被挪包」时静默失效；按名查找 + 唯一性校验把这种失效变成明确红灯。
     */
    private static String sourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }
        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动/改名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (hits.size() > 1) {
            fail("源码树里有 " + hits.size() + " 个 " + fileName + "（"
                    + hits.stream().map(Path::toString)
                    .collect(java.util.stream.Collectors.joining("、"))
                    + "），无法唯一定位喵。");
        }
        try {
            return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /** {@link #sourceOf(String)} + {@link #extractBody(String, String)} 的组合。 */
    private static String methodBody(String fileName, String signature) {
        return extractBody(sourceOf(fileName), signature);
    }

    /**
     * 按方法签名截出方法体（花括号之间的文本）。
     *
     * <p>签名必须在源码中<b>唯一出现</b>。刻意不提供「第 N 个匹配」的逃生口：
     * 一旦需要它，说明 lint 的定位已经不可信。
     *
     * <p>签名可以只写到参数表开头：参数表跨多行时写全签名既难维护又易在换行后失配；
     * 只要「签名 → 第一个左花括号」之间没有分号，就足以确认匹配到的是带体的方法声明。
     *
     * <p>因为 {@link #stripComments(String)} 已把注释与字面量抹成空格，数花括号时
     * 不必再区分「注释里的 {」与「文本块里的 {」。
     */
    private static String extractBody(String strippedSource, String signature) {
        int sigIndex = strippedSource.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名/改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵 —— "
                    + "若新增了重载，请把签名写全（本 lint 读的是 LetsLearnEnglish / SoundManager，"
                    + "后者的 playSound 有三个重载，必须写全参数表）。");
        }

        int bodyStart = strippedSource.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            fail("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        String between = strippedSource.substring(sigIndex + signature.length(), bodyStart);
        if (between.indexOf(';') >= 0) {
            fail("方法签名「" + signature + "」与后面的左花括号之间出现了分号，"
                    + "匹配到的可能不是方法体喵：" + between.strip());
        }

        int close = findBlockEnd(strippedSource, bodyStart);
        if (close < 0) {
            fail("方法签名「" + signature + "」的花括号没有配对结束喵 —— 源码可能不完整。");
        }
        return strippedSource.substring(bodyStart + 1, close);
    }

    /**
     * 从 {@code openBraceIdx} 处的左花括号出发做括号配对，返回配对右花括号的下标；
     * 不配对返回 {@code -1}。
     */
    private static int findBlockEnd(String s, int openBraceIdx) {
        if (openBraceIdx < 0 || openBraceIdx >= s.length() || s.charAt(openBraceIdx) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = openBraceIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 找出 {@code haystack} 里所有 {@code needle} 的起始偏移（不重叠，按出现顺序）。
     *
     * <p>用「次数」而不是「有无」是本类的核心手法：装配类缺陷最常见的形态不是
     * 「完全没有」，而是「多了一段 / 少了一段」—— 少一段看有无就够，多一段必须数次数。
     */
    private static List<Integer> occurrences(String haystack, String needle) {
        List<Integer> out = new ArrayList<>();
        if (needle.isEmpty()) {
            return out;
        }
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return out;
            }
            out.add(at);
            from = at + needle.length();
        }
    }

    /**
     * 取方法体的<b>第一条语句</b>文本（跳过前导空白与空行，到一个 {@code {} 或 {@code ;} 为止）。
     *
     * <p>用于 L6 的「首行守卫」：只判断「方法体的第一条语句里有没有某两个调用」，
     * 而不是「整个方法体里有没有」—— 后者在守卫被挪到 {@code getScheduler()} 之后时
     * 仍然会通过，而那正是把竞态窗口重新打开的写法。
     */
    private static String firstStatement(String body) {
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        int end = i;
        int depth = 0;
        while (end < body.length()) {
            char c = body.charAt(end);
            if (c == '{' || c == ';') {
                break;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            end++;
        }
        return body.substring(i, Math.min(end + 1, body.length())).strip();
    }

    /**
     * {@code offset} 处的调用是否落在某个 {@code try { ... } catch ...} 的<b>try 块</b>内。
     *
     * <p>做法：从 {@code offset} 向前找最近的 {@code try}，要求它后面（跳过空白）紧跟
     * {@code {}，并且该左花括号配对的右花括号<b>越过</b> {@code offset}。
     * 不能用「前面 N 个字符里有 'try'」：上一步的 try 会被算进来，
     * 而那个 try 的右花括号早已在 {@code offset} 之前闭合。
     *
     * <p>只找「最近的」一个 try 就够：Java 的 try 块不重叠（嵌套时内层更近）。
     */
    private static boolean insideTryBlock(String body, int offset) {
        int from = offset;
        while (true) {
            int t = body.lastIndexOf("try", from);
            if (t < 0) {
                return false;
            }
            // 必须是独立标识符（避免匹配到 retry / entry 之类）。
            boolean leftOk = t == 0 || !Character.isJavaIdentifierPart(body.charAt(t - 1));
            boolean rightOk = t + 3 >= body.length()
                    || !Character.isJavaIdentifierPart(body.charAt(t + 3));
            if (!leftOk || !rightOk) {
                from = t - 1;
                continue;
            }
            int braceOpen = t + 3;
            while (braceOpen < body.length() && Character.isWhitespace(body.charAt(braceOpen))) {
                braceOpen++;
            }
            if (braceOpen >= body.length() || body.charAt(braceOpen) != '{') {
                from = t - 1;
                continue;
            }
            int braceClose = findBlockEnd(body, braceOpen);
            if (braceClose > offset) {
                return true;
            }
            from = t - 1;
        }
    }

    /** 把「锚点 → 偏移」渲染成可读文本，便于失败消息里对照实际源码。 */
    private static String render(String[] anchors, int[] offsets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < anchors.length; i++) {
            if (i > 0) {
                sb.append(" < ");
            }
            sb.append(anchors[i]).append('@').append(offsets[i]);
        }
        return sb.toString();
    }

    /**
     * 剥掉 Java 注释，并把字符串/字符字面量的内容抹成空格（单遍字符扫描状态机）。
     *
     * <p>与 {@code B3WiringLintsTest} 的同类方法<b>刻意各写一份</b>（理由见类注释）。
     *
     * <p>为什么不能一步正则：入口类里有含 {@code //} 的 URL 字符串、有 javadoc 里写着
     * {@code disableAll()} / {@code HandlerList.unregisterAll(} 的大段注释、有 i18n 文案里的
     * {@code {name}}。正则无法区分「注释里的引号」与「字符串里的斜杠」。状态机按 JLS 的
     * 词法顺序处理行注释 → 块注释 → 文本块 → 普通字符串 → 字符字面量，其余字符原样保留。
     *
     * <p>注释与字面量的内容统一替换为<b>空格（保留换行）</b>：这样 {@code indexOf} 的偏移
     * 与原文一致，失败消息里的偏移可以直接对照源码；同时「注释里提到的 disableAll()」
     * 不会被误算成代码 —— 这一点对本类尤其关键，因为生产代码的注释里反复出现这些锚点。
     */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // 1) 行注释：跳到行尾（保留换行，偏移/行号不错位）
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            // 2) 块注释：跳到配对的 */
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(source.charAt(j) == '*' && j + 1 < n
                        && source.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            // 3) 文本块 """
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j + 2 < n
                        && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
                        && source.charAt(j + 2) == '"')) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = (j + 2 < n) ? j + 3 : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            // 4) 普通字符串：处理 \\ 转义，遇换行视为未闭合（容错）
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                blank(out, i, j);
                i = j;
                continue;
            }

            // 5) 字符字面量
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '\'' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                blank(out, i, j);
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    /** 把 {@code [from, to)} 内的非换行字符替换为空格（保持偏移与行结构）。 */
    private static void blank(char[] out, int from, int to) {
        int end = Math.min(to, out.length);
        for (int k = Math.max(0, from); k < end; k++) {
            if (out[k] != '\n' && out[k] != '\r') {
                out[k] = ' ';
            }
        }
    }
}
