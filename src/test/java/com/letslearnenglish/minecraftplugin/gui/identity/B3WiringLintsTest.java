package com.letslearnenglish.minecraftplugin.gui.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * B3 接线点的<b>源码级 lint</b>（不是行为测试）。
 *
 * <p>这六条钉死的都是红队实测「<b>删掉之后 295 个用例仍然全绿</b>」的变异。它们之所以
 * 不可观测，原因只有两类：
 * <ul>
 *   <li><b>根本没有测试类</b>：{@code AIChatManager} / {@code ChatFeedbackManager} 的构造
 *       需要 {@code JavaPlugin} 与真实配置，单测里跑不起来，于是「接线掉了一行」没有任何
 *       断言看得见；</li>
 *   <li><b>mock 下的默认值把分支关掉了</b>：{@code ProfileGUI} 的决斗块整段包在
 *       {@code isDuelEnabled()} 里，mock 的 {@code ConfigManager} 默认返回 {@code false}，
 *       于是 {@code scheduleDuelRow(...)} 那行删掉也照绿；{@code placeDuelRow} 是
 *       {@code private}，三道守卫更没有入口。</li>
 * </ul>
 *
 * <p><b>本类与 {@code GuiWiringLintsTest} / {@code SessionLoopLintsTest} 零耦合</b>：
 * {@code stripComments} / {@code extractBody} / {@code srcRoot} / {@code sourceOf} /
 * {@code rawSourceOf} / {@code rawMethodBody} / {@code methodBody} /
 * {@code findBlockByBraceMatching} 在这里各自独立实现一遍。复用会
 * 带来真实的维护陷阱 —— 那边为了「GUI 覆盖」或「生命周期」调宽了剥注释的容错，本类的
 * 接线 lint 会跟着静默失效，而失败消息却指向另一个文件。重复几十行换来三组 lint 的失效
 * 模式互相独立，是划算的。
 *
 * <p><b>失败消息的约定</b>：每条 lint 都写明这是<b>语义锚点 / 文本形状</b>检查，比字符串
 * 匹配更严格。合法的等价重构（抽辅助方法、换写法）会让它们误红；<b>误红必须由人裁决并
 * 同步本 lint，绝不允许为了让 lint 过而改回错误实现。</b>
 *
 * <h2>六条 lint 与它们各自封死的变异</h2>
 * <ol>
 *   <li><b>L1</b> {@code setFocusWordProvider(...)} 在 {@code onEnable} 里恰好一次、转发到
 *       {@code resolveFocusWords}，且该决策体内同时取 {@code getSuggestedFocusWords} 与
 *       {@code getDueReviewWords} —— 删掉这个调用，AI 永远拿不到目标词。
 *       （L6 之后决策体从 lambda 内联抽成了纯函数，见下方「已知且接受的误报」。）</li>
 *   <li><b>L2</b> provider 不是恒空常量 —— lambda 里没有 {@code return java.util.List.of();}，
 *       决策体里存在 {@code suggested.isEmpty()} 分支，且
 *       {@code ai-chat.focus-words-from-review} 的读点默认值为 {@code false}。</li>
 *   <li><b>L3</b> {@code handleSummaryResult} 真的调用 {@code applyToLearningLoop} ——
 *       删掉它，AI 花钱买来的纠错永不落地。</li>
 *   <li><b>L4</b> {@code scheduleReview} 同时存在 {@code true}（真答错）与 {@code false}
 *       （推荐词）两种调用 —— 把 {@code true} 改回 {@code false}，真答错的词不重置复习
 *       间隔，AI 纠错等于没接上。</li>
 *   <li><b>L5</b> {@code ProfileGUI.open} 在 {@code isDuelEnabled()} 的 if 块<b>内</b>
 *       落 slot 16 占位，且整页异步回填（{@code scheduleProfileBackfill}）恰好派发一次
 *       —— 删掉任一处，主页数据永远停在「加载中」。</li>
 *   <li><b>L6</b> {@code stillViewing} 的三道守卫都在，且生产回填方法
 *       {@code placeProfileData} 在渲染决斗行（{@code applyDuelRow}）前调用它
 *       —— 删掉任意一道，异步回填会把 A 的战绩写进 B 的界面（跨玩家串台）。
 *       C-P2-6 起锚点从已删除的 {@code placeDuelRow} 上移到真实生产路径。</li>
 * </ol>
 *
 * <h2>已知且接受的误报（写在这里，免得后人反复踩）</h2>
 * <ol>
 *   <li>L1 / L2：<b>L6 已经做过一次这样的重构并同步了 lint</b> —— 取数决策体从
 *       {@code onEnable} 的 lambda 内联抽成了 {@code LetsLearnEnglish.resolveFocusWords}
 *       静态纯函数（原因：{@code onEnable} 依赖 Bukkit server，「开关默认关闭」这条隐私
 *       边界必须能被纯 JVM 单测钉住）。因此 L1 的锚点分两段：lambda 里查
 *       {@code resolveFocusWords(}，决策体里查两路数据源；L2 的 {@code isEmpty} 判据也
 *       跟着搬到决策体。同理，若再抽成 {@code this::focusWordsForPlayer} 之类的具名方法，
 *       会误红 —— 接受，但应按同样方式同步 lint，而不是放行。</li>
 *   <li>L2：判据认的是 {@code suggested.isEmpty()} 这个<b>变量名</b>。局部变量改名
 *       （{@code suggested} → {@code focus}）会误红 —— 接受，见失败消息里的同步提示。</li>
 *   <li>L3：断言分两层 —— ① 主路径（{@code hasActiveConversation} 守卫块之外）恰好 1 处；
 *       ② 若该守卫块存在，则块内也必须至少有 1 处。实测该方法体里本来就有两处调用
 *       （守卫分支 {@code notify=false}、主路径 {@code notify=true}），把总数写死成某个
 *       具体值会在合理的分支拆分后误红，因此按「位置 + 存在性」断言而不是「总数」。</li>
 *   <li>L6：这是<b>文本形状</b>断言，只验三道守卫的文本存在、且 {@code placeProfileData}
 *       在渲染前调用守卫，不验真实短路行为；真行为由 {@code ProfileOpenAsyncTest} 覆盖
 *       （C-P2-6：守卫测试已从反射直调死方法改为经 {@code open()} 全链路）。</li>
 *   <li>L5 / L6：L3/L21 把守卫从 {@code placeDuelRow} 抽进 {@code stillViewing}、把派发点
 *       从 {@code scheduleDuelRow} 合成 {@code scheduleProfileBackfill}，lint 已同步锚点；
 *       C-P2-6 又删掉了已死的 {@code placeDuelRow}，L6 的接线锚点改指 {@code placeProfileData}。
 *       再进一步抽（例如守卫改成 {@code guard(view) → Optional<Inventory>}）会误红 ——
 *       接受，同步 lint 而不是放行。</li>
 * </ol>
 */
class B3WiringLintsTest {

    @Nested
    @DisplayName("B3Wiring（接线点 lint，读源码断言）")
    class B3Wiring {

        // ==================================================================
        // L1：setFocusWordProvider 接线存在，且同时接了两路数据源
        // ==================================================================

        /**
         * L1：{@code onEnable} 里 {@code setFocusWordProvider(...)} 恰好出现 1 次，
         * 它转发到 {@code resolveFocusWords(...)}，且该方法体内同时含
         * {@code getSuggestedFocusWords(} 与 {@code getDueReviewWords(}。
         *
         * <p><b>L6 之后的结构变化</b>：取数决策体已从 lambda 内联抽到
         * {@code LetsLearnEnglish.resolveFocusWords}（纯函数，供 {@code FocusWordReviewToggleTest}
         * 直接调用 —— {@code onEnable} 依赖 Bukkit server，纯 JVM 跑不起来）。因此本 lint
         * 分两段断言：① lambda 仍在且仍转发（接线没断）；② 决策体里两路数据源都还在
         * （数据源没被砍）。两段缺一，目标词注入就整体失效。
         *
         * <p>为什么必须两种都断言：只数调用次数抓不到「provider 还挂着但恒返回空表」
         * （那是 L2 的活），只查决策体抓不到「整块被删」。
         *
         * <p>红队变异：删掉 {@code this.aiChatManager.setFocusWordProvider(...)} 这一整段。
         * 后果是 {@code AIChatManager.focusWordProvider} 永远为 {@code null}，system prompt
         * 里的「目标词」一节永远拼不出来 —— 玩家每场都在盲练。而 {@code AIChatManager}
         * 没有测试类，{@code buildSystemPrompt} 又是 private，其余用例全绿。
         */
        @Test
        @DisplayName("L1：enableFullFeatures 里 setFocusWordProvider 恰好一次，且转发到接了两路数据源的决策体")
        void focusWordProviderIsWired() {
            // 锚点变更（子服总开关 server.enabled）：provider 接线随 onEnable 的
            // 「完整启用」段一起搬进 enableFullFeatures(long)。语义未变 —— 仍是在
            // 插件启用的主干上一次注入；变的是它现在只在 server.enabled=true 时执行
            //（关闭态压根不该注册 AI，这正是总开关要的）。
            // 找不到方法签名时 methodBody 会 fail()，所以「方法被改名/拆并」不会静默假绿。
            String body = methodBody("LetsLearnEnglish.java",
                    "public void enableAll()");

            List<Integer> calls = occurrences(body, "setFocusWordProvider(");
            assertEquals(1, calls.size(),
                    "onEnable 方法体里 setFocusWordProvider(...) 出现了 " + calls.size()
                            + " 次（偏移=" + calls + "），期望恰好 1 次 喵。\n"
                            + "删掉这个调用会让 AI 永远拿不到目标词 —— "
                            + "AIChatManager.focusWordProvider 恒为 null，system prompt 的"
                            + "「目标词」一节永远拼不出来，玩家每场都在盲练；"
                            + "而 AIChatManager 没有测试类，buildSystemPrompt 是 private。\n"
                            + "出现 2 次以上则可能是有人复制粘贴出第二段 provider 注入，"
                            + "后注册的会覆盖先注册的，接线语义不再唯一。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改调一个注入辅助方法），请同步本 lint —— "
                            + "但 provider 必须仍然在 onEnable 的调用链上完成注入。");

            int parenOpen = body.indexOf('(', calls.get(0));
            int[] region = lambdaRegion(body, parenOpen);
            assertTrue(region != null,
                    "onEnable 里 setFocusWordProvider(...) 之后找不到可解析的 lambda 体 喵"
                            + "（调用偏移=" + calls.get(0) + "）。\n"
                            + "期望形态：setFocusWordProvider(id -> { ... }) 或 "
                            + "setFocusWordProvider(id -> expr)。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            String lambda = body.substring(region[0], region[1]);

            assertTrue(lambda.contains("resolveFocusWords("),
                    "setFocusWordProvider 的 lambda 体里找不到 resolveFocusWords(...) 喵。\n"
                            + "L6 之后取数决策体已抽到该静态纯函数（为了让「开关默认关闭」这条"
                            + "隐私边界能被纯 JVM 单测钉住）。lambda 只剩一行转发，"
                            + "转发目标被删/改名 = provider 恒空 = 目标词注入整体失效。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改调 focusWordsFor(id)），请同步本 lint。");

            String decision = methodBody("LetsLearnEnglish.java",
                    "public static List<String> resolveFocusWords(");

            assertTrue(decision.contains("getSuggestedFocusWords("),
                    "resolveFocusWords 方法体里找不到 getSuggestedFocusWords(...) 喵。\n"
                            + "这是第 1 路数据源：ChatFeedbackManager 的 ConcurrentHashMap"
                            + "（上一场 AI 纠错词 + 推荐词，已按词库过滤），纯内存读。\n"
                            + "少了它，「下一场重点练什么」这条闭环就断了 —— AI 只会按词库随机出题，"
                            + "上一场用错的词不会在下一场被针对。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            assertTrue(decision.contains("getDueReviewWords("),
                    "resolveFocusWords 方法体里找不到 getDueReviewWords(...) 喵。\n"
                            + "这是第 2 路数据源：ReviewScheduler 的 30s TTL 内存缓存"
                            + "（艾宾浩斯到期复习词），同样是纯内存读 —— 在主线程上安全。\n"
                            + "L6 起它受 ai-chat.focus-words-from-review（默认 false）控制，"
                            + "但<b>代码路径必须存在</b>：开关为 true 时仍要能取到复习词。\n"
                            + "整条调用被删掉 = 该配置键成了死配置（打开也没用）。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");
        }

        // ==================================================================
        // L2：provider 不是恒空常量
        // ==================================================================

        /**
         * L2：provider 不是恒空常量 —— lambda 转发到决策体，且决策体里存在
         * {@code suggested.isEmpty()} 分支与 {@code focus-words-from-review} 的读点。
         *
         * <p><b>L6 之后的结构变化</b>：判据已从「lambda 内的 {@code return java.util.List.of();}
         * 计数」搬到「决策体里的分支形态」。原因：L6 要给复习词回落加一个默认 false 的开关，
         * 而「开关默认关闭」这条隐私边界必须能被纯 JVM 单测钉住 —— 于是决策体被抽成
         * {@code resolveFocusWords} 纯函数（{@code onEnable} 依赖 Bukkit server，跑不起来）。
         * 代价是本 lint 的锚点跟着换位置；收益是同一份语义第一次有了行为测试
         * （{@code FocusWordReviewToggleTest}）。
         *
         * <p>仍然保留本 lint 的理由：行为测试只覆盖被显式调用的入参组合，而「lambda 被换成
         * 恒空常量」这类接线级变异，行为测试看不见（它测的是 resolveFocusWords 本身）。
         * <p><b>这条断言是较脆弱的文本形状检查</b>：认的是变量名 {@code suggested} 与
         * {@code isEmpty()} 的字面形态。若实现被等价重写（变量改名、改走 {@code Optional}、
         * 改判 {@code size() > 0}），会误红；失败消息写明处置方式：<b>由人确认是等价重构后
         * 同步本 lint</b>，不得为了让 lint 过而改回原样。
         *
         * <p>红队变异：把 lambda 整体换成 {@code id -> java.util.List.of()}。
         * 后果 —— AI 拿不到任何目标词，玩家每场盲练。
         */
        @Test
        @DisplayName("L2：provider 转发到决策体，且决策体含 suggested.isEmpty() 分支与开关读点")
        void focusWordProviderIsNotConstantEmpty() {
            // 同 L1：锚点随子服总开关搬到 enableFullFeatures(long)。
            String body = methodBody("LetsLearnEnglish.java",
                    "public void enableAll()");

            List<Integer> calls = occurrences(body, "setFocusWordProvider(");
            assertFalse(calls.isEmpty(),
                    "enableFullFeatures 方法体里找不到 setFocusWordProvider(...) 喵 —— "
                            + "provider 注入整段被删，本题无从检查（L1 也会同时变红）。\n"
                            + "请先修 L1 的接线，再回来看本 lint。");

            int parenOpen = body.indexOf('(', calls.get(0));
            int[] region = lambdaRegion(body, parenOpen);
            assertTrue(region != null,
                    "enableFullFeatures 里 setFocusWordProvider(...) 之后找不到可解析的 lambda 体 喵。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            String lambda = body.substring(region[0], region[1]);

            // ① 恒空常量变异：lambda 里直接返回空表 = provider 事实上成了常量，
            //    目标词注入整体失效。期望 0 处 —— 抽函数后 lambda 只是一行转发。
            List<Integer> emptyReturns = occurrences(lambda, "return java.util.List.of();");
            assertTrue(emptyReturns.isEmpty(),
                    "setFocusWordProvider 的 lambda 体里有 " + emptyReturns.size()
                            + " 处 `return java.util.List.of();`（偏移=" + emptyReturns + "），"
                            + "期望 0 处 喵。\n"
                            + "L6 之后 lambda 应只有一行转发 `resolveFocusWords(...)`；"
                            + "任何直接返回空表的分支都属于「provider 变成常量」的变异 —— "
                            + "AI 永远拿不到目标词，玩家每场盲练。\n"
                            + "本 lint 认的是字面形态；<b>若为等价重构（例如把 null 守卫搬回 lambda），"
                            + "请先由人确认语义确实未变，再同步本 lint</b>。");

            String decision = methodBody("LetsLearnEnglish.java",
                    "public static List<String> resolveFocusWords(");

            // ② 分支判据：必须有「suggested 是否为空」这一层判断，证明决策体不是直通常量。
            boolean hasEmptinessBranch = Pattern.compile(
                            "!?\\s*suggested\\s*\\.\\s*isEmpty\\s*\\(\\s*\\)")
                    .matcher(decision).find();
            assertTrue(hasEmptinessBranch,
                    "resolveFocusWords 里找不到 `suggested.isEmpty()` / `!suggested.isEmpty()` "
                            + "这类分支判据 喵。\n"
                            + "正确形状是「先取 suggested，非空就用它，空了才看要不要回落到复习词」"
                            + "—— 没有这层判断，要么恒返回空表（目标词注入失效），"
                            + "要么恒返回复习词（L6 的隐私开关形同虚设）。\n"
                            + "本 lint 认的是<b>变量名 + 方法名</b>的字面形态，属于较脆弱的文本形状断言；"
                            + "<b>若为等价重构（局部变量从 suggested 改名、改判 size()、改走 Optional），"
                            + "请先由人确认语义确实未变，再同步本 lint</b> —— "
                            + "绝不允许为了让 lint 过而改回错误实现。");

            // ③ 隐私开关的读点必须在决策体里：删掉它，focus-words-from-review 变死配置，
            //    「默认关闭」这条边界就无从谈起（服务器升级后可能悄悄开始泄题）。
            //
            // 用 rawMethodBody 而不是 methodBody：配置键是字符串字面量，stripComments
            // 会把字面量内容抹成空格，在那里查键名永远是空答复（假绿）。
            // 同时把默认值 false 一起钉住 —— 只查键名会让「默认改成 true」的变异溜过去，
            // 而那正是 L6 要防的那一次改动。
            String decisionRaw = rawMethodBody("LetsLearnEnglish.java",
                    "public static List<String> resolveFocusWords(");
            assertTrue(decisionRaw.contains("ai-chat.focus-words-from-review"),
                    "resolveFocusWords 里找不到 `ai-chat.focus-words-from-review` 的读点 喵。\n"
                            + "这是 L6 的隐私开关（默认 false）：到期复习词 = 玩家尚未掌握、"
                            + "即将被考的单词，注入 TARGET WORDS 段等于告诉 AI「请用这些词」，"
                            + "玩家会从 AI 台词里看到复习答案。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改走一个集中式 settings 读取器），请同步本 lint。");
            assertTrue(Pattern.compile("\"ai-chat\\.focus-words-from-review\"\\s*,\\s*false")
                            .matcher(decisionRaw).find(),
                    "`ai-chat.focus-words-from-review` 的<b>读点默认值不是 false</b> 喵。\n"
                            + "这是隐私边界，不是功能偏好：老服务器升级上来时配置文件里没有这个键，"
                            + "读点默认值就是唯一生效的口径。默认 true = 升级即开始向 AI 泄露"
                            + "玩家尚未掌握的复习词（AI 会在台词里用出来，玩家免费看到答案）。\n"
                            + "若确有等价重构（例如抽出一个常量 DEFAULT_FOCUS_FROM_REVIEW = false），"
                            + "请先由人确认默认值未变，再同步本 lint。");
        }

        // ==================================================================
        // L3：handleSummaryResult 必须调用 applyToLearningLoop
        // ==================================================================

        /**
         * L3：{@code handleSummaryResult} 方法体里必须存在
         * {@code applyToLearningLoop(}，且<b>主路径</b>（{@code hasActiveConversation}
         * 守卫块之外）恰好 1 处。
         *
         * <p>为什么分「总数」与「主路径」两层：实测该方法体里本来就有两处调用 ——
         * {@code hasActiveConversation} 为真时（玩家已开新会话、点评不显示）走
         * {@code notify=false}，主路径走 {@code notify=true}。把总数写死成 1 或 2 都会
         * 在合理的分支拆分后误红；而只断言「总数 ≥ 1」又漏掉「主路径被删、只剩防串台那一处」
         * 的情形 —— 那正是玩家最常走的路径。
         *
         * <p>红队变异：删掉 {@code applyToLearningLoop(player, feedback, true);} 这一行。
         * 后果：AI 点评照常显示，但 corrections 既不入错题本、也不排复习、也不进下一场的
         * 目标词 —— AI 纠错永不落地，玩家花的 API 钱买不到任何学习闭环。而
         * {@code ChatFeedbackManager} 没有测试类，295 个用例全绿。
         */
        @Test
        @DisplayName("L3：handleSummaryResult 调用 applyToLearningLoop，且主路径恰好一次")
        void applyToLearningLoopIsInvoked() {
            String body = methodBody("ChatFeedbackManager.java",
                    "private void handleSummaryResult(");

            List<Integer> calls = occurrences(body, "applyToLearningLoop(");
            assertFalse(calls.isEmpty(),
                    "handleSummaryResult 方法体里找不到 applyToLearningLoop(...) 喵"
                            + "（调用偏移=" + calls + "）。\n"
                            + "删掉这个调用，AI 花钱买来的纠错永远不会落地：corrections 不入错题本、"
                            + "不排复习、也不进下一场的「目标词」；玩家可见的点评照常显示，"
                            + "所以没有任何行为测试会变红 —— 现有 295 个用例全绿。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如把落地逻辑并进 persistAfter 之类的辅助方法），请同步本 lint —— "
                            + "但落地调用必须仍在点评处理链上。");

            // 主路径 = hasActiveConversation 守卫块之外。该块是「防串台」分支：
            // 玩家已开新会话时点评不显示，但纠错仍要静默落地（notify=false）。
            List<int[]> guardBlocks = ifBlocksWithCondition(body, "hasActiveConversation(");
            int mainPathCalls = 0;
            for (int offset : calls) {
                if (!insideAny(guardBlocks, offset)) {
                    mainPathCalls++;
                }
            }
            assertEquals(1, mainPathCalls,
                    "handleSummaryResult 的<b>主路径</b>（hasActiveConversation 守卫块之外）"
                            + "里 applyToLearningLoop(...) 出现了 " + mainPathCalls + " 次，期望恰好 1 次 喵"
                            + "（全部调用偏移=" + calls + "，守卫块区间=" + renderRanges(guardBlocks) + "）。\n"
                            + "常见变异是只保留 hasActiveConversation 分支里那一处（notify=false）、"
                            + "把主路径的 notify=true 删掉 —— 玩家最常走的那条路径因此完全不落地纠错，"
                            + "而防串台分支还在，光看「方法体里有调用」抓不到。\n"
                            + "出现 2 次以上则说明有人重复插入调用：plan.countAsWrong() 会被执行两遍，"
                            + "incrementWrongCount 多加一次，错题本可能提前在第 2 次就入库。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如主路径改成调用一个内部再转调 applyToLearningLoop 的辅助方法），"
                            + "请同步本 lint。");

            // 防串台分支（hasActiveConversation 为真、点评不显示）也必须落地纠错：
            // 玩家结束一局后立刻开了新对话是最常见的连招，若这条分支静默丢弃 feedback，
            // 「AI 买的纠错」在最活跃的那批玩家身上反而永远不生效。
            // 守卫块<b>不存在</b>时跳过本子断言（那是更大的分支结构改动，不在 B3 范围内）；
            // 存在但里面没有调用 → 红。
            if (!guardBlocks.isEmpty()) {
                boolean guardBranchApplies = false;
                for (int[] g : guardBlocks) {
                    for (int offset : calls) {
                        if (offset > g[0] && offset < g[1]) {
                            guardBranchApplies = true;
                            break;
                        }
                    }
                }
                assertTrue(guardBranchApplies,
                        "hasActiveConversation 守卫块（防串台分支）里没有 applyToLearningLoop(...) 喵"
                                + "（全部调用偏移=" + calls + "，守卫块区间="
                                + renderRanges(guardBlocks) + "）。\n"
                                + "这条分支对应「玩家结束一局后立刻开了新对话」—— 点评为了防串台"
                                + "不显示，但 AI 花钱买来的 corrections 仍必须静默落地"
                                + "（notify=false 只是不弹提示，不是不写入）。\n"
                                + "删掉这一处，最活跃的那批玩家反而永远拿不到 AI 纠错的收益："
                                + "错题本、复习种子、下一场目标词全部落空，而点评界面看起来一切正常。\n"
                                + "这是语义锚点检查，比字符串匹配更严格；若该分支被整体重构掉"
                                + "（不再需要防串台），请同步本 lint。");
            }
        }

        // ==================================================================
        // L4：scheduleReview 的 true / false 两个分支都在
        // ==================================================================

        /**
         * L4：{@code ChatFeedbackManager} 里同时存在
         * {@code scheduleReview(playerId, word, true)} 与
         * {@code scheduleReview(playerId, word, false)}。
         *
         * <p>实测参数就是<b>字面量</b> {@code true} / {@code false}（不是变量
         * {@code asWrong}），因此可以按字面形态断言。
         *
         * <p>为什么是「两种调用都必须在」而不是「只要有一个 scheduleReview」：
         * <ul>
         *   <li>{@code true}（真答错）：{@code scheduleReview} 内部会
         *       {@code seedReview + resetReview}，把词重新排到<b>最近</b>的一次复习。
         *       改成 {@code false} 后只 {@code seedReview}，而它对已有复习记录的词是
         *       <b>no-op</b> —— 真答错的词复习间隔分毫不动，下次提醒仍按旧的长间隔走。</li>
         *   <li>{@code false}（推荐词 / 句级纠错切出的词）：只播种、不重置。删掉它，
         *       推荐词永远进不了复习池。</li>
         * </ul>
         *
         * <p>红队变异：把 {@code scheduleReview(playerId, word, true)} 的 {@code true}
         * 改回 {@code false}。所有行为测试照绿（{@code ChatFeedbackManager} 没有测试类，
         * 且 {@code seedReview} 的 no-op 语义只在「该词已有复习记录时」才显现，需要真实
         * {@code PlayerDataStore} 状态）。
         */
        @Test
        @DisplayName("L4：scheduleReview 同时存在 true（真答错）与 false（推荐词）两种调用")
        void scheduleReviewUsesBothFlags() {
            String src = sourceOf("ChatFeedbackManager.java");

            assertTrue(src.contains("scheduleReview("),
                    "ChatFeedbackManager.java 里找不到 scheduleReview(...) 调用 喵。\n"
                            + "AI 纠错落地链在「计数/入错题本」之后必须排复习；"
                            + "整条调用被删掉时，corrections 只停留在内存里，"
                            + "玩家此后不会被安排复习。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如统一走一个带枚举语义的排复习入口），请同步本 lint。");

            boolean wrongBranch = Pattern.compile(
                            "scheduleReview\\s*\\(\\s*playerId\\s*,\\s*word\\s*,\\s*true\\s*\\)")
                    .matcher(src).find();
            assertTrue(wrongBranch,
                    "找不到 scheduleReview(playerId, word, true) —— <b>真答错</b>分支缺失 喵。\n"
                            + "`true` 走的是 seedReview + resetReview：把词重新排到最近的一次复习。"
                            + "把 true 改成 false（或删掉这一行）后只剩 seedReview，"
                            + "而 PlayerDataStore.seedReview 在 lastReviewTime / reviewCount 已存在时"
                            + "直接 return（no-op）—— 学过的词在 AI 对话里答错后，复习间隔分毫不动，"
                            + "下次提醒仍按旧的长间隔走，AI 纠错等于没接上闭环。\n"
                            + "这个缺陷只在「该词已有复习记录」时显现，且 ChatFeedbackManager 没有测试类，"
                            + "所以现有 295 个用例全绿。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改传一个布尔局部变量 asWrong 而不是字面量），请同步本 lint —— "
                            + "但真答错必须仍然走 reset 语义。");

            boolean recommendBranch = Pattern.compile(
                            "scheduleReview\\s*\\(\\s*playerId\\s*,\\s*word\\s*,\\s*false\\s*\\)")
                    .matcher(src).find();
            assertTrue(recommendBranch,
                    "找不到 scheduleReview(playerId, word, false) —— <b>推荐词</b>分支缺失 喵。\n"
                            + "`false` 只 seedReview、不 resetReview：推荐词 / 句级纠错切出的词"
                            + "不是「答错事件」，只保证它在复习池里，已有进度的词不重置间隔。\n"
                            + "删掉这一行，AI 推荐的词永远进不了复习池，下一场的目标词也就没有后续巩固。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");
        }

        // ==================================================================
        // L5：ProfileGUI.open 在 isDuelEnabled() 块内派发异步回填
        // ==================================================================

        /**
         * L5：{@code ProfileGUI.open(Player, UUID)} 必须同时满足两件事：决斗「加载中」占位
         * 落在 {@code isDuelEnabled()} 的 if 块<b>内</b>，且整页异步回填恰好派发一次。
         *
         * <p><b>L21 重构后的形态</b>：原实现是「决斗块内直接调 {@code scheduleDuelRow(...)}」。
         * 现在统计 / 金币 / 成就数也搬进了异步段，四类数据合并为一个任务
         * （{@code scheduleProfileBackfill}），因此「派发点」只有一个、且必须由
         * {@code needBackfill} 判断承接（模块全关时不得派发）。原来那条「调用必须在
         * isDuelEnabled() 块内」的锚点，改由「占位 setItem 必须在块内」承接 ——
         * 语义等价：模块关着时既不该画占位、也不该发起 DB 读。
         *
         * <p>红队变异：删掉占位 {@code inv.setItem(DUEL_SLOT, ...)}，slot 16 从打开到回填
         * 之间是一个裸空槽（会露馅成「这张主页没有这一行」，并且 fillEmpty 的灰玻璃会先
         * 占位再被回填覆盖）；删掉 {@code scheduleProfileBackfill(...)}，整页数据永远停在
         * 「加载中」。两种在 mock 下都不可观测（{@code isDuelEnabled()} 默认 false）。
         */
        @Test
        @DisplayName("L5：open 在 isDuelEnabled() 块内落占位，且异步回填恰好派发一次")
        void duelPlaceholderAndBackfillAreWired() {
            String body = methodBody("ProfileGUI.java",
                    "public void open(Player viewer, UUID targetId)");

            List<Integer> placeholders = occurrences(body, "inv.setItem(DUEL_SLOT");
            assertEquals(1, placeholders.size(),
                    "ProfileGUI.open 方法体里 inv.setItem(DUEL_SLOT, ...) 出现了 "
                            + placeholders.size() + " 次（偏移=" + placeholders + "），期望恰好 1 次 喵。\n"
                            + "删掉它，slot 16 在异步回填之前是裸空槽；玩家会以为这张主页没有决斗行，"
                            + "而模块关闭时该槽本该由 fillEmpty 垫灰玻璃。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            List<int[]> duelBlocks = ifBlocksWithCondition(body, "isDuelEnabled()");
            assertFalse(duelBlocks.isEmpty(),
                    "ProfileGUI.open 方法体里找不到 if (... isDuelEnabled() ...) 块 喵。\n"
                            + "模块门是「决斗关着就整行不渲染、也不派发异步 DB 读」的唯一保证；"
                            + "块结构被拆掉后，本 lint 无法再判断占位是否在门控之内。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若改成 guard clause / switch，请同步本 lint。");

            assertTrue(insideAny(duelBlocks, placeholders.get(0)),
                    "决斗占位不在 isDuelEnabled() 的 if 块<b>内</b> 喵"
                            + "（调用偏移=" + placeholders.get(0)
                            + "，if 块区间=" + renderRanges(duelBlocks) + "）。\n"
                            + "把占位挪到 if 外面 = duel.enabled 关闭时 slot 16 仍被写，「整行不渲染」失效。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            List<Integer> dispatches = occurrences(body, "scheduleProfileBackfill(");
            assertEquals(1, dispatches.size(),
                    "ProfileGUI.open 方法体里 scheduleProfileBackfill(...) 出现了 "
                            + dispatches.size() + " 次（偏移=" + dispatches + "），期望恰好 1 次 喵。\n"
                            + "删掉它，统计三卡 / 金币 / 成就数 / 决斗战绩全都停在「加载中」："
                            + "主线程只落了占位，真正取数与回填全靠这一次派发。\n"
                            + "而 mock 下 ConfigManager 的模块门默认 false，这段分支进不去，"
                            + "现有测试会全绿 —— 这是「分支被 mock 默认值关掉」的典型盲区。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如抽成 refreshProfileData(...) 并保留一次调用），请同步本 lint。");
        }

        // ==================================================================
        // L6：决斗回填的三道守卫
        // ==================================================================

        /**
         * L6：三道守卫的文本形状都在，且 {@code placeProfileData} 在渲染决斗行前调用它们。
         *
         * <p><b>L3/L21 重构后的形态</b>：守卫从 {@code placeDuelRow} 内联抽进了
         * {@code stillViewing(...)}（统计/金币/成就/决斗四处回填共用同一份守卫，避免
         * 四份会各自漂移的实现）。因此断言分两段：守卫在 {@code stillViewing} 里，
         * 且生产方法 {@code placeProfileData} 的 {@code applyDuelRow(...)} 渲染发生在
         * {@code stillViewing} 之后。
         *
         * <p><b>C-P2-6（红队 C）</b>：原锚点指向 {@code placeDuelRow} —— 自 L21 起生产
         * 路径不再调用它（只剩测试反射直调），守卫测试因此验证的是死代码。它已被删除，
         * 锚点上移到 {@code placeProfileData}。
         *
         * <p>三道守卫逐条对应一种「异步回填报废」的失效：
         * <ol>
         *   <li><b>守卫 1</b> {@code viewer.isOnline()} —— 玩家已下线；</li>
         *   <li><b>守卫 2</b> {@code top.getHolder() != opened} —— 当前打开的不是当初那张
         *       界面（<b>引用</b>相等，不是 {@code equals}：同 key 的两次打开 holder 值相等，
         *       只有实例相等才说明是同一张）；</li>
         *   <li><b>守卫 3</b> {@code payload instanceof ...Target} + {@code t.targetId()} ——
         *       界面归属仍是原目标。</li>
         * </ol>
         *
         * <p><b>本 lint 只验守卫存在，不验行为。</b> 真实的串台/过期快照行为由
         * {@code ProfileDuelRowGuardTest} 覆盖（它能直接调 private 方法或经反射构造场景）；
         * 这里补的是「守卫被整段删掉、而行为测试被同步删掉/没覆盖到」时的兜底。
         */
        @Test
        @DisplayName("L6：stillViewing 三道守卫都在，且 placeProfileData 渲染决斗行前必须调用它")
        void placeDuelRowHasThreeGuards() {
            String guards = methodBody("ProfileGUI.java", "private boolean stillViewing(");

            // 守卫 1：在线检查。
            assertTrue(guards.contains("isOnline()"),
                    "stillViewing 方法体里找不到 isOnline() 喵 —— <b>守卫 1</b> 缺失。\n"
                            + "异步段可能耗时数十毫秒，期间玩家完全可能下线；没有这道守卫时"
                            + "仍会对已关闭的视图 setItem（部分实现下抛 IllegalStateException，"
                            + "打断调度线程），并且与后面两道守卫组合出跨玩家串台。\n"
                            + "注意：本 lint 只验守卫存在，实际行为由 ProfileDuelRowGuardTest 覆盖。\n"
                            + "这是文本形状断言，比行为测试弱；若为真正等价的重构，请同步本 lint —— "
                            + "但调用必须仍发生在任何回填写入之前。");

            // 守卫 2：holder <b>引用</b>相等（!=），不是 equals。
            boolean holderRefCompare = Pattern.compile(
                            "getHolder\\s*\\(\\s*\\)\\s*!=\\s*[A-Za-z_$][\\w$]*")
                    .matcher(guards).find();
            assertTrue(holderRefCompare,
                    "stillViewing 方法体里找不到 `top.getHolder() != opened` 形式的引用相等比较 喵"
                            + " —— <b>守卫 2</b> 缺失或被改成了 equals。\n"
                            + "这道守卫判定「当前打开的仍是打开时那张界面」。必须是<b>引用</b>比较："
                            + "同 key 的两次打开（连点 / 来回切人）holder 的值相等，"
                            + "用 equals 会把「B 的界面」误判成「A 的界面」，"
                            + "把 A 的战绩写进 B 的槽位 —— 跨玩家数据串台，属隐私事故。\n"
                            + "注意：本 lint 只验守卫存在，实际行为由 ProfileDuelRowGuardTest 覆盖。\n"
                            + "这是文本形状断言，比行为测试弱；若为真正等价的重构"
                            + "（例如把比较收进 isSameView(opened) 辅助方法），请同步本 lint。");

            // 守卫 3：payload 归属 —— 锚点必须是「守卫 3 独有的形状」。
            //
            // 这里踩过两次坑：
            //   ① 最初只查 `guards.contains("Target")` + `targetId()` —— 守卫 2 的
            //      WARNING 分支里同样有这两个词，删掉守卫 3 整段 lint 依然绿。
            //   ② 之后改成「`opened.getPayload() instanceof` find() && `targetId().equals(target)`
            //      find()」—— 两条<b>独立</b>的 find()。第二条仍从方法体开头扫，第一个命中是
            //      守卫 2 WARNING 分支里的 `t.targetId().equals(target)`（它在文件里更早），
            //      于是「删掉守卫 3 的归属判定半边、只留 instanceof 外壳」时 lint 仍绿。
            // 现在用<b>一次匹配</b>把变量名与负向谓词绑成合取形状：`\1` 反向引用保证
            // `instanceof` 绑定的变量就是被 `equals` 判的那个；`||` + 前导 `!` 是守卫 3
            // 独有的形状（守卫 2 用的是 `&&` + `!(...)` 收尾，不会命中）。
            //
            // ⚠ 本正则依赖守卫 3 的真实形状：
            //     if (!(opened.getPayload() instanceof ...Target t) || !t.targetId().equals(target))
            //   若做等价重构（例如抽成 payloadBelongsTo(opened, target)），<b>必须同步本 lint</b>，
            //   不允许为了让 lint 过而改回错误实现。
            boolean targetPayload = Pattern.compile(
                            "if\\s*\\(\\s*!\\s*\\(\\s*opened\\s*\\.\\s*getPayload\\s*\\(\\s*\\)\\s*instanceof"
                                    + "\\s+[\\w.$]*Target\\s+(\\w+)\\s*\\)"
                                    + "\\s*\\|\\|\\s*!\\s*\\1\\s*\\.\\s*targetId\\s*\\(\\s*\\)\\s*\\."
                                    + "\\s*equals\\s*\\(\\s*target\\s*\\)")
                    .matcher(guards).find();
            assertTrue(targetPayload,
                    "stillViewing 方法体里找不到守卫 3 的<b>完整合取形状</b>喵：\n"
                            + "    if (!(opened.getPayload() instanceof ...Target t)"
                            + " || !t.targetId().equals(target))\n"
                            + "<b>注意锚点选择</b>：必须用一次匹配 + 反向引用把「instanceof 绑定的变量」"
                            + "与「被 equals 判的变量」绑成同一个 `\\1`。守卫 2 的 WARNING 分支里有"
                            + " `other.getPayload() instanceof ...Target t` 和 `t.targetId().equals(target)`，"
                            + "任何拆成两条独立 find() 的写法都会让「删掉守卫 3 归属判定半边」这个变异全绿"
                            + "（曾经如此：第二条 find() 的第一个命中点在守卫 2）。\n"
                            + "守卫 3 是纵深防御：即使守卫 2 的实例比对将来被重构成读 inv.getHolder()"
                            + "（纯 JVM 单测里 mock 返回 null，会静默失效），归属判定仍能拦住串台。\n"
                            + "本 lint 依赖上述 `if (!(...) || !t.targetId().equals(target))` 形状；"
                            + "若做等价重构请同步本 lint。\n"
                            + "注意：本 lint 只验守卫存在，实际行为由 ProfileDuelRowGuardTest 覆盖。");

            // 顺序锚点（防将来有人调换两段）：守卫 3 必须排在守卫 2 之后。
            // 若守卫 2 被重写成不再出现 `other.getPayload()`，indexOf 返回 -1，
            // 本断言自动通过 —— 这可接受：守卫 2 另有独立锚点（上面的 holder 引用相等）。
            assertTrue(guards.indexOf("opened.getPayload()") > guards.indexOf("other.getPayload()"),
                    "守卫 3 必须排在守卫 2 之后（否则锚点会再次串味）喵：\n"
                            + "守卫 3 偏移=" + guards.indexOf("opened.getPayload()")
                            + "，守卫 2 偏移=" + guards.indexOf("other.getPayload()") + "。\n"
                            + "两段顺序一旦对调，任何「从方法体开头扫」的弱锚点都会先命中守卫 2，"
                            + "重新退回假绿（本条 lint 的历史缺陷正是如此）。");


            // 接线：placeProfileData 必须先过守卫再决斗行渲染（否则守卫形同虚设）。
            //
            // C-P2-6（红队 C）：原锚点指向 {@code placeDuelRow} —— 那方法自 L21 起
            // 生产路径不再调用（只剩测试反射直调），于是「守卫测试验证的是死代码」，
            // 把 placeProfileData 开头的 stillViewing 删掉它们照样全绿。现在
            // placeDuelRow 已删除，渲染收进 {@code applyDuelRow}，锚点改为
            // **生产方法** placeProfileData 里「stillViewing 调用 → applyDuelRow 调用」
            // 的先后关系。
            String place = methodBody("ProfileGUI.java",
                    "private void placeProfileData(Player viewer, UUID target,");
            int guardCall = place.indexOf("stillViewing(");
            int write = place.indexOf("applyDuelRow(");
            assertTrue(guardCall >= 0,
                    "placeProfileData 方法体里找不到 stillViewing(...) 调用 喵。\n"
                            + "守卫被抽成公共方法后，若漏掉这次调用，所有回填都绕过三门校验 —— "
                            + "跨玩家串台会重新变成静默行为。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");
            assertTrue(write > guardCall,
                    "placeProfileData 里 applyDuelRow(...) 出现在 stillViewing(...) 之前 喵"
                            + "（守卫偏移=" + guardCall + "，渲染偏移=" + write + "）。\n"
                            + "守卫必须在写入之前：顺序反过来等于「先写数据再检查该不该写」。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");
        }
    }

    // ==================================================================
    // 辅助方法（本类独立实现，刻意不复用 GuiWiringLintsTest / SessionLoopLintsTest）
    // ==================================================================

    /** 插件源码根（{@code com/letslearnenglish/minecraftplugin}）；找不到直接 fail，绝不 skip。 */
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
     * 在源码树里按<b>文件名</b>定位源码，返回<b>剥注释 + 抹字面量</b>后的内容。
     *
     * <p>为什么按文件名而不是按包路径拼：本类要读的三个文件分处
     * {@code minecraftplugin/}（{@code LetsLearnEnglish}）、
     * {@code minecraftplugin/core/ai/}（{@code ChatFeedbackManager}）与
     * {@code minecraftplugin/gui/}（{@code ProfileGUI}）。写死包路径会在「文件被挪到别的包」
     * 时静默失效；按名查找 + 唯一性校验把这种失效变成一次明确的红灯。
     *
     * @param fileName 文件名（含 {@code .java}），须在源码树中唯一
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
                    + "），无法唯一定位喵 —— 请把本 lint 的锚点改成相对路径。");
        }

        try {
            return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /** {@link #sourceOf(String)} + {@link #methodBody(String, String)} 的组合。 */
    private static String methodBody(String fileName, String signature) {
        return extractBody(sourceOf(fileName), signature);
    }

    /**
     * 定位源码并返回<b>未剥注释</b>的方法体（保留字符串字面量的原文）。
     *
     * <p>为什么需要它：{@link #stripComments(String)} 会把字符串字面量的内容抹成空格，
     * 于是「配置键名」「默认值字面量」这类检查在剥过的文本上永远是空答复 ——
     * 那会让断言静默变成「查的东西不存在」而不是「代码里没有」。
     *
     * <p>实现靠一条不变量：{@code stripComments} 用<b>空格（保留换行）</b>替换注释与字面量，
     * 所以剥过的文本与原文<b>偏移逐一对应</b>。因此在剥过的文本上做括号配对求出
     * {@code [start, end)}，再用同一对偏移去切原文，就能既避开注释里的花括号、
     * 又拿到字面量的真实字符。
     */
    private static String rawMethodBody(String fileName, String signature) {
        String stripped = sourceOf(fileName);
        String raw = rawSourceOf(fileName);
        int sigIndex = stripped.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名/改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        int bodyStart = stripped.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            fail("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        int bodyClose = findBlockByBraceMatching(stripped, bodyStart);
        if (bodyClose < 0) {
            fail("方法签名「" + signature + "」的花括号没有配对结束喵 —— 源码可能不完整。");
        }
        return raw.substring(bodyStart + 1, bodyClose);
    }

    /**
     * 定位源码并返回<b>未剥注释/未抹字面量</b>的原文。
     *
     * <p>与 {@link #sourceOf(String)} 的唯一差别是「不 strip」。检查「配置键名 +
     * 默认值」这类<b>字面量</b>时必须用它：stripComments 会把字符串内容抹成空格。
     */
    private static String rawSourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }
        if (hits.size() != 1) {
            fail("源码树里 " + fileName + " 的命中数是 " + hits.size() + "（期望恰好 1）喵。");
        }
        try {
            return java.nio.file.Files.readString(hits.get(0), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /**
     * 按方法签名截出方法体（花括号之间的文本）。
     *
     * <p>签名必须<b>在源码中唯一出现</b>：既不能有重载，也不能有同名的调用点。
     * 这里刻意不提供「第 N 个匹配」这种逃生口 —— 一旦需要它，说明 lint 的定位不再可信。
     *
     * <p>签名可以只写到参数表开头（例如 {@code "private void placeProfileData("}）：参数表跨多行时
     * 写全签名既难维护又容易在换行后失配；只要「签名 → 第一个左花括号」之间没有分号，
     * 就足以确认匹配到的是带体的方法声明。
     *
     * <p>因为 {@link #stripComments(String)} 已把注释与字符串/字符字面量抹成空格，
     * 这里数花括号时不必再区分「注释里的 {」与「标题里的 {」。
     */
    private static String extractBody(String strippedSource, String signature) {
        int sigIndex = strippedSource.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名/改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵 —— "
                    + "若新增了重载，请把本 lint 的锚点改成更精确的签名。");
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

        int depth = 0;
        for (int i = bodyStart; i < strippedSource.length(); i++) {
            char c = strippedSource.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return strippedSource.substring(bodyStart + 1, i);
                }
            }
        }
        fail("方法签名「" + signature + "」的花括号没有配对结束喵 —— 源码可能不完整。");
        return ""; // 不可达
    }

    /**
     * 从 {@code openBraceIdx} 处的左花括号出发，用括号计数找到配对的右花括号，返回其索引。
     *
     * <p>这是「调用在块内」类断言的基础：只有块区间是真的，才能区分「调用在 if 块里」
     * 与「调用在 if 块上面一行」。单纯比较 {@code indexOf} 偏移会被摆拍骗过。
     *
     * @return 配对右花括号的索引；源码不完整（未闭合）时返回 {@code -1}
     */
    private static int findBlockByBraceMatching(String s, int openBraceIdx) {
        if (openBraceIdx < 0 || openBraceIdx >= s.length() || s.charAt(openBraceIdx) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = openBraceIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 从 {@code openIdx} 处的 {@code (} 出发找到配对的 {@code )}，返回其索引（找不到返回 -1）。 */
    private static int findMatchingParen(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 跳过空白，返回下一个非空白字符的索引（到文末则返回 {@code s.length()}）。 */
    private static int skipWhitespace(String s, int from) {
        int i = Math.max(0, from);
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * 求 {@code setFocusWordProvider(} 的 lambda 体区间。
     *
     * <p>两种形态都支持：
     * <ul>
     *   <li>块体 {@code id -> { ... }} → 括号配对求 {@code {}} 区间；</li>
     *   <li>表达式体 {@code id -> expr;} → 扫到<b>顶层</b>分号或调用右括号为止
     *       （嵌套括号里的分号不算，例如三元/方法调用）。</li>
     * </ul>
     *
     * @param s           已剥注释的源码
     * @param callOpenParen {@code setFocusWordProvider} 之后那个 {@code (} 的索引
     * @return {@code int[]{起, 止}}（区间为左闭右闭，端点本身是 {@code {} 或表达式边界）；
     *         解析失败返回 {@code null}
     */
    private static int[] lambdaRegion(String s, int callOpenParen) {
        if (callOpenParen < 0 || callOpenParen >= s.length()
                || s.charAt(callOpenParen) != '(') {
            return null;
        }
        int arrow = s.indexOf("->", callOpenParen);
        if (arrow < 0) {
            return null;
        }
        int start = skipWhitespace(s, arrow + 2);
        if (start >= s.length()) {
            return null;
        }
        if (s.charAt(start) == '{') {
            int close = findBlockByBraceMatching(s, start);
            return close < 0 ? null : new int[]{start, close};
        }
        // 表达式 lambda：扫到顶层的 `;` 或包住它的 `)`。
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return new int[]{start, i};
                }
                depth--;
            } else if (c == ';' && depth == 0) {
                return new int[]{start, i};
            }
        }
        return null;
    }

    /**
     * 找出方法体内所有 {@code needle} 出现的起始偏移（不重叠，按出现顺序）。
     *
     * <p>用「次数」而不是「有无」是本类的核心手法：接线类缺陷最常见的形态不是
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
                break;
            }
            out.add(at);
            from = at + needle.length();
        }
        return out;
    }

    /**
     * 找出方法体内所有 {@code if (条件)} 且条件（去空白后）包含 {@code condition} 的
     * then 块区间 {@code [左花括号, 右花括号]}。
     *
     * <p>只认带花括号的 then 块：单语句 {@code if (x) doThing();} 无法用括号配对求区间，
     * 也就不该被当成「条件块内」的可信证据（保守方向，宁可误红）。
     */
    private static List<int[]> ifBlocksWithCondition(String body, String condition) {
        List<int[]> blocks = new ArrayList<>();
        String wanted = condition.replaceAll("\\s+", "");
        Matcher m = Pattern.compile("\\bif\\s*\\(").matcher(body);
        while (m.find()) {
            int parenOpen = body.indexOf('(', m.start());
            int parenClose = findMatchingParen(body, parenOpen);
            if (parenClose < 0) {
                continue;
            }
            String cond = body.substring(parenOpen + 1, parenClose).replaceAll("\\s+", "");
            if (!cond.contains(wanted)) {
                continue;
            }
            int braceOpen = skipWhitespace(body, parenClose + 1);
            if (braceOpen >= body.length() || body.charAt(braceOpen) != '{') {
                continue; // 单语句 if：不认
            }
            int braceClose = findBlockByBraceMatching(body, braceOpen);
            if (braceClose < 0) {
                continue;
            }
            blocks.add(new int[]{braceOpen, braceClose});
        }
        return blocks;
    }

    /** {@code offset} 是否落在任一区间 {@code (open, close)} 的严格内部。 */
    private static boolean insideAny(List<int[]> blocks, int offset) {
        for (int[] block : blocks) {
            if (offset > block[0] && offset < block[1]) {
                return true;
            }
        }
        return false;
    }

    /** 把块区间渲染成可读文本，便于失败消息里对照源码。 */
    private static String renderRanges(List<int[]> ranges) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ranges.get(i)[0]).append("..").append(ranges.get(i)[1]);
        }
        return sb.append(']').toString();
    }

    /**
     * 剥掉 Java 注释，并把字符串/字符字面量的内容抹成空格（单遍字符扫描状态机）。
     *
     * <p>与 {@code GuiWiringLintsTest} / {@code SessionLoopLintsTest} 的同类方法
     * <b>刻意各写一份</b>（理由见类注释）。
     *
     * <p>为什么不能一步正则：源码里有含 {@code //} 的 URL 字符串、有 Javadoc 里的
     * {@code setFocusWordProvider(...)} 字面量、有 i18n 文案里的 {@code {name}}。
     * 正则无法区分「注释里的引号」与「字符串里的斜杠」。状态机按 JLS 的词法顺序处理
     * 行注释 → 块注释 → 文本块 → 普通字符串 → 字符字面量，其余字符原样保留。
     *
     * <p>注释与字面量的内容统一替换为<b>空格（保留换行）</b>：这样后续的
     * {@code indexOf} 偏移与原文一致，失败消息里的偏移可以直接对照源码；同时
     * 「注释里提到的 setFocusWordProvider」不会被误算成代码。
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

            // 2) 块注释：跳到配对的 */（未闭合则到文末）
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
