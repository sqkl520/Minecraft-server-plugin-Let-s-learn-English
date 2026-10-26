package com.letslearnenglish.minecraftplugin.gui.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 生命周期 / 接线层面的<b>源码级 lint</b>（不是行为测试）。
 *
 * <p>这四条钉死的是红队与 RT-G2 反复报告、但在任何单测里都<b>不可观测</b>的实现缺陷：
 * <ol>
 *   <li><b>P1-1 周期保存接线</b>：{@code startScheduledTasks} 必须调增量 {@code saveDirty}，
 *       不能调全量 {@code savePlayerData}。行为测试跑不起 Bukkit 调度器，改回全量保存
 *       不会让任何测试变红，只会让每个空闲玩家每 5 分钟被 UPSERT 一整行。</li>
 *   <li><b>P1-3 快照取值顺序</b>：{@code savePlayerDataInternal} 必须先取写入序号、再进
 *       {@code connectionLock}、<b>最后</b>才取 player_data 快照。顺序错位是「旧快照覆盖
 *       新结算」这类数据损坏的根因，只在多线程真实排队时出现。</li>
 *   <li><b>RT-F-b onDisable 兜底</b>：{@code clearAllSessions()} 必须包在
 *       {@code try/catch (Throwable)} 里，否则一次 DB 异常会截断整段停用流程（GUI 不关、
 *       数据不落、连接不关）。</li>
 *   <li><b>P2-2 活引用</b>：{@code saveDirty} 必须把 {@code dirtyWords} 的<b>活引用</b>
 *       交给 {@code savePlayerDataInternal}，不能先 {@code new ArrayList<>(words)} 拷贝 ——
 *       拷贝会把「拷贝完成 → 序号采集」之间新增的词挡在写入之外，而序号又已计入它，
 *       于是 {@code clearDirty} 判定「窗口内无新写入」把标记清掉，那次写入永久丢失。</li>
 * </ol>
 *
 * <p><b>本类刻意与 {@code GuiViewKeyCoverageTest} 零耦合</b>：{@code stripComments} /
 * {@code extractBody} 在这里各自独立实现一遍。复用会带来一个真实的维护陷阱 ——
 * 有人在那个文件里为「GUI 覆盖」调宽了剥注释的容错（比如把未闭合的 {@code /*} 当成
 * 注释结束），本类的生命周期 lint 会跟着静默失效，而失败消息却指向另一个文件。
 * 重复的代价只是几十行，收益是两组 lint 的失效模式互相独立。
 *
 * <h2>语义锚点，而不是字符串偏移（FIX-4）</h2>
 *
 * <p>本类原来的四条 lint 只比较 {@code indexOf} 偏移，红队实测证明了五条<b>编译通过、
 * 全绿</b>的绕过路径：诱饵空锁、真假锁并存、别名追加全量保存、catch 里 rethrow、
 * 无关 try 包围（本次复现时又补了两条：{@code getPlayerDataStore()} 直连全量保存、
 * 「两段同锁」把取快照与写库拆到两个块）。现在改为：
 * <ul>
 *   <li>lint 1 解析「局部别名 = playerDataStore / getPlayerDataStore()」并识别方法引用；</li>
 *   <li>lint 2 / 3 用<b>括号配对</b>求块区间，断言调用确实落在块内；lint 2 还要求快照与
 *       {@code getConnection() + commit()} 在<b>同一个</b>锁块里（防「两段同锁」拆开观察点）；</li>
 *   <li>lint 3 还要求 catch 的参数是 {@code Throwable} 且 catch 体内无 {@code throw}；</li>
 *   <li>lint 4 用「拷贝形态黑名单」代替单一的字符串白名单。</li>
 * </ul>
 *
 * <p><b>失败消息的约定</b>：每条 lint 的失败消息都写明「这是语义锚点检查，比字符串匹配
 * 更严格；若为<b>真正等价</b>的重构（如抽辅助方法），请同步本 lint」。这些 lint 检查的是
 * <b>文本形状</b>，合法的等价重构（抽辅助方法、换写法）会让它们误红；误红必须由人裁决并
 * 同步 lint，绝不允许为了让 lint 过而改回错误实现。
 *
 * <p><b>已知且接受的误报</b>（写在这里，免得后人反复踩）：
 * <ol>
 *   <li>lint 1：{@code saveDirty} 若被抽成 {@code persistDirty(playerId)} 辅助方法、调度器
 *       只调辅助方法，会误红 —— 接受。检查点必须留在调用链上，抽走就是把不变式搬出了
 *       本 lint 的视野，应当同步 lint 而不是放行。（{@code store = playerDataStore} /
 *       {@code store = getPlayerDataStore()} / {@code store = this.playerDataStore} /
 *       {@code store = this.getPlayerDataStore()} 四种别名已识别，不会误红。）</li>
 *   <li>lint 2：若把 {@code databaseManager.getConnectionLock()} 提成局部变量
 *       （{@code Object lock = ...; synchronized (lock)}），会误红 —— 接受。锁对象被别名化后
 *       文本上无法再区分「同一把锁」与「另一把锁」。（{@code this.databaseManager
 *       .getConnectionLock()} 已归一化，不会误红。）</li>
 *   <li>lint 3：{@code catch} 体内若嵌了另一个 try/catch 且内层 rethrow，会误红 —— 接受
 *       （保守方向，宁可让人看一眼）。</li>
 *   <li>lint 4：{@code saveDirty} 若把 {@code hasWords ? words : ...} 换成完全不含 {@code words}
 *       字样的等价写法（如 {@code Optional.ofNullable(words).orElse(emptyList())}），会误红 ——
 *       接受；本 lint 认的是「words 以活引用形态出现」，不是通用数据流分析。</li>
 * </ol>
 *
 * <h2>FIX-6：<i>调用位置</i>之外，还要钉住<i>调用次数</i></h2>
 *
 * <p>RT-G4 红队实测出两条「位置对、语义错」的绕过，均已封死：
 * <ol>
 *   <li><b>masteryMap 挪到锁外</b>：{@code savePlayerDataInternal} 的源码注释明写锁内取值坏
 *       两条路径（player_data 与 word_progress），而原 lint 只检查 {@code snapshotPlayerData}。
 *       现在同一条 lint 里额外断言 {@code wordMasteryCache.get(playerId)} 的<b>每一次</b>读取
 *       都落在<b>同一个</b>连接锁块内。</li>
 *   <li><b>锁内摆设 + 锁外真用</b>：{@code snapUsed = snapshotPlayerData(playerId)} 在锁外读、
 *       锁内再调一次「当摆设」，写库用 {@code snapUsed}。纯文本无法判断锁内那次的返回值有没有
 *       被使用，但能判断<b>调用次数</b>：正确实现只取一次快照。现在要求
 *       {@code snapshotPlayerData(playerId)} 在方法体内恰好出现 1 次，且那 1 次在锁内。</li>
 * </ol>
 *
 * <p><b>仍然不验的</b>（明确的残余风险，不要假装它被覆盖了）：本 lint 不追踪<b>返回值绑定</b>。
 * 若有人只调一次 {@code snapshotPlayerData(playerId)}（锁内），却把结果赋给变量 A、写库时
 * 改用另一条路径算出来的数值，本 lint 看不出来 —— 那属于数据流分析，超出「源码级形状检查」
 * 的边界。真正的保证来自 {@code PlayerDataSnapshot} 的不可变性 + 锁内取值的写法约束，
 * 以及 code review；<b>P1-3 目前没有行为测试兜底</b>（多线程排队窗口在单测里不可复现），
 * 因此这条残余风险必须由人工评审承担，不能声称「已由行为测试覆盖」。
 */
class GuiWiringLintsTest {

    /** 方法调用 / 方法引用的接收者（{@code recv.method(} / {@code recv::method} / {@code recv().method(}）。 */
    private static final String RECV =
            "(?:([\\w$]+)(?:\\s*\\(\\s*\\))?\\s*(?:\\.|::)\\s*|::\\s*)";

    @Nested
    @DisplayName("StructuralLints（生命周期接线，读源码断言）")
    class StructuralLints {

        // ===== 1. P1-1 =====

        /**
         * P1-1：周期保存必须走增量 {@code saveDirty}，不得走全量 {@code savePlayerData}。
         *
         * <p>断言被<b>限定在 {@code startScheduledTasks} 方法体内</b>：{@code onDisable}
         * 与 {@code unloadPlayerData} 里确实调 {@code savePlayerData}（那是「确保落盘」
         * 的既定语义），全文件 grep 会立刻假红。
         *
         * <p>两个方向都要断言：光有 {@code saveDirty} 不够 —— 有人可以「保留 saveDirty
         * 再加一行 savePlayerData」，照样每个玩家每轮被全量重写一次。
         *
         * <p>红队绕过（FIX-4 前全绿）：{@code Consumer<UUID> fullSaver =
         * playerDataStore::savePlayerData; onlinePlayerIds.forEach(fullSaver);}。
         * 因此这里不只看 {@code playerDataStore.savePlayerData(}，还会：
         * <ul>
         *   <li>识别方法引用 {@code playerDataStore::savePlayerData}（以及裸 {@code ::savePlayerData}）；</li>
         *   <li>先解析方法体内所有「别名 = playerDataStore」的局部变量（{@code var} / 显式类型 /
         *       {@code final} 都算），再检查别名上的 {@code .savePlayerData(} / {@code ::savePlayerData}。</li>
         * </ul>
         */
        @Test
        @DisplayName("P1-1：周期保存调用 saveDirty，且不调用全量 savePlayerData")
        void saveDirtyIsWiredToScheduler() {
            String body = methodBody("LetsLearnEnglish.java",
                    "private void startScheduledTasks()");

            // 接受者集合 = playerDataStore 本身 + 所有「别名 = playerDataStore」的局部变量。
            List<String> receivers = aliasesOf(body, "playerDataStore");

            assertTrue(callsMethod(body, receivers, "saveDirty"),
                    "在 startScheduledTasks 方法体里找不到 playerDataStore.saveDirty(...) / "
                            + "playerDataStore::saveDirty 喵（已解析的接收者=" + receivers + "）。\n"
                            + "P1-1 要求周期保存走增量：全量保存会让每个空闲玩家每轮都被 UPSERT 一整行，"
                            + "write amplification 回到修复前。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如抽 persistDirty 辅助方法并把调用留在辅助方法里），请同步本 lint。");

            assertFalse(callsMethod(body, receivers, "savePlayerData"),
                    "startScheduledTasks 方法体里出现了全量 savePlayerData 调用/方法引用 喵"
                            + "（已解析的接收者=" + receivers + "）。\n"
                            + "P1-1：周期保存只允许增量 saveDirty；全量保存只应出现在"
                            + " onDisable / unloadPlayerData / reload flush 这三处「确保落盘」的窗口。\n"
                            + "注意方法引用与别名也算：Consumer<UUID> fullSaver = playerDataStore::savePlayerData; "
                            + "再 forEach(fullSaver) 与直接调用等价。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如抽成 ensurePersisted 辅助方法并改了调用形状），请同步本 lint。");
        }

        // ===== 2. P1-3 =====

        /**
         * P1-3：{@code savePlayerDataInternal} 里「取序号 → 抢锁 → 取快照」的先后顺序。
         *
         * <p>为什么必须是这个顺序：{@code clearDirty} 用「保存前后序号是否相同」判断
         * 窗口内有没有新写入。若快照在锁外、甚至在序号之前取，那么在「取快照」与「抢锁
         * 写库」之间存在任意长的排队窗口：玩家结算一局并 quit，主线程先写入新值并清缓存，
         * 本线程获锁后用旧快照覆盖 —— {@code total_score} / {@code total_sessions} 永久少一局，
         * 且这两个值只存在于 player_data，下次 join 不会从 word_progress 重建，
         * <b>不可自愈</b>。
         *
         * <p>同理序号必须先于取值：先取值再记序号，取值之后、记序号之前的那次写入会被
         * 误判成「已包含在快照里」，commit 后标记被清而新值丢失。
         *
         * <p>红队绕过（FIX-4 前全绿）：
         * <pre>
         * synchronized (databaseManager.getConnectionLock()) { }   // 诱饵：满足 indexOf 比较
         * PlayerDataSnapshot snap = snapshotPlayerData(playerId);  // 真快照在锁外
         * </pre>
         * 或保留正确文本的空锁 + {@code synchronized (new Object())} 包住真快照。
         * 因此这里改为<b>括号配对求块区间</b>：遍历方法体内每一处 {@code synchronized}，
         * 解析其参数表达式，只有参数归一化后等于 {@code databaseManager.getConnectionLock()}
         * 的块才算「真锁」，然后断言快照偏移落在该块的 {@code (open, close)} 区间内。
         */
        @Test
        @DisplayName("P1-3：序号 → 连接锁 → player_data 快照 的顺序")
        void snapshotCapturedInsideConnectionLock() {
            String body = methodBody("PlayerDataStore.java",
                    "private void savePlayerDataInternal(UUID playerId, Collection<String> wordFilter)");

            int seq = body.indexOf("currentWriteSeq(playerId)");

            // FIX-6（P1-3 后半）：快照调用必须<b>全方法体只出现一次</b>。
            // 「锁内摆一次当摆设、锁外再取一次真用」是红队实测的全绿绕过：
            //   final PlayerDataSnapshot snapUsed = snapshotPlayerData(playerId);   // 锁外，真正写库用
            //   synchronized (lock) { final PlayerDataSnapshot snap = snapshotPlayerData(playerId); ... }
            // 纯文本无法判断「锁内那次调用的返回值有没有被使用」，但可以判断<b>调用次数</b>：
            // 正确实现只取一次，第二次调用只能是「另取一份旧值」—— 直接判红。
            List<Integer> snapshotCalls = callOffsets(body, "snapshotPlayerData");
            int snapshot = snapshotCalls.isEmpty() ? -1 : snapshotCalls.get(0);

            assertTrue(seq >= 0,
                    "在 savePlayerDataInternal 方法体里找不到 currentWriteSeq(playerId) 喵。\n"
                            + "P1-3 要求先采集写入序号，再取任何值；没有序号就无法判断"
                            + "「保存窗口内有没有新写入」。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改调 seqOf(playerId) 之类的包装），请同步本 lint。");
            assertFalse(snapshotCalls.isEmpty(),
                    "在 savePlayerDataInternal 方法体里找不到 snapshotPlayerData(playerId) 喵。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如把 player_data 读取内联成逐字段 get），请同步本 lint。");

            assertEquals(1, snapshotCalls.size(),
                    "savePlayerDataInternal 里 snapshotPlayerData(playerId) 出现了 "
                            + snapshotCalls.size() + " 次（偏移=" + snapshotCalls + "），期望恰好 1 次 喵。\n"
                            + "P1-3：这个方法只允许取<b>一次</b> player_data 快照，且必须在连接锁内。"
                            + "出现第二次的典型形态是红队的「锁内摆设 + 锁外真用」：\n"
                            + "    final PlayerDataSnapshot snapUsed = snapshotPlayerData(playerId);  // 锁外取，真正写库用\n"
                            + "    synchronized (databaseManager.getConnectionLock()) {\n"
                            + "        final PlayerDataSnapshot snap = snapshotPlayerData(playerId);  // 锁内调用当摆设\n"
                            + "        ... statsStmt.setLong(5, snapUsed.score);                          // 用的是锁外那个\n"
                            + "    }\n"
                            + "锁仍是同一把、调用也在锁内 —— 但写进 DB 的是排队前读到的旧值，"
                            + "total_score / total_sessions 照样永久少一局。\n"
                            + "本检查用「调用次数」替代「判断返回值绑定」：纯文本层面无法可靠追踪"
                            + "返回值是否被使用，但「需要取两次快照」在正确实现里没有存在的理由。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如把取值+写库整体搬进锁内的私有方法、只留一次调用），请同步本 lint。");

            assertTrue(seq < snapshot,
                    "P1-3 不变式被破坏：snapshotPlayerData 出现在 currentWriteSeq 之前"
                            + "（序号偏移=" + seq + "，快照偏移=" + snapshot + "）喵。\n"
                            + "先取值再记序号时，「取值之后、记序号之前」的那次写入会被误判成"
                            + "已包含在快照里 → commit 后 clearDirty 清掉标记，而新值从未落库。\n"
                            + "这条顺序在运行时不可观测，只能靠本 lint 抓。"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（如先算一个不可变的 seq/snapshot 元组再使用），请同步本 lint。");

            List<int[]> lockBlocks = connectionLockBlocks(body);
            assertFalse(lockBlocks.isEmpty(),
                    "在 savePlayerDataInternal 方法体里找不到参数为 "
                            + "databaseManager.getConnectionLock() 的 synchronized 块 喵。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改用 try-with-lock 或把锁提到调用方），请同步本 lint，"
                            + "但不得取消这把锁 —— 它是「取值与写库同一个观察点」的唯一保证。");

            // 观察点必须是「同一个块」：快照所在的那个 lock 块里还得有取连接与提交这两步。
            // 否则可以写成两段 synchronized (同一把锁)：第一段取快照、第二段写库 ——
            // 锁是同一把，但中间已经放开了锁，排队窗口原样复活。
            boolean insideSameBlockAsWrite = false;
            int snapshotBlock = -1;
            for (int i = 0; i < lockBlocks.size(); i++) {
                int[] block = lockBlocks.get(i);
                if (snapshot > block[0] && snapshot < block[1]) {
                    String locked = body.substring(block[0], block[1]);
                    if (locked.contains("getConnection()") && locked.contains("commit()")) {
                        insideSameBlockAsWrite = true;
                        snapshotBlock = i;
                        break;
                    }
                }
            }

            assertTrue(insideSameBlockAsWrite,
                    "P1-3 不变式被破坏：snapshotPlayerData 不在「取连接 + 提交」的同一个 "
                            + "synchronized (databaseManager.getConnectionLock()) 块内 喵。\n"
                            + "连接锁块区间=" + renderRanges(lockBlocks) + "，快照偏移=" + snapshot + "。\n"
                            + "锁外取快照会在「取值」与「抢锁写库」之间留一个任意长的排队窗口："
                            + "玩家结算后 quit，主线程先写入新值并清缓存，本线程获锁后用旧快照覆盖，"
                            + "total_score / total_sessions 永久少一局且不可自愈。\n"
                            + "本检查用括号配对求块区间，并要求快照与 databaseManager.getConnection() + "
                            + "commit() 落在同一个锁块里；因此「诱饵空锁」「保留正确文本的空锁 + "
                            + "synchronized (new Object()) 包住真快照」「两段同锁、取值与写库被拆开」都会被判红。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（如把整段取值+写库搬进锁内的私有方法），请同步本 lint。");

            // ===== FIX-6（P1-3 后半）：word_progress 路径的 masteryMap 也必须在同一个锁块内 =====
            //
            // 上面只钉住了 player_data 那条路径。savePlayerDataInternal 的源码注释写得清清楚楚，
            // 锁外取值会坏两条路径，其中 ① 已被覆盖，② 是：
            //   锁外读到非 null 的 masteryMap（旧引用仍带着词表），但 quit 已清空 correct /
            //   incorrect / review / lastReview 四个 map → 循环里 getIntValue 回落 0，
            //   用「正确 mastery + 全 0 计数」覆盖刚由 quit 全量写好的行。
            // 红队实测：把 masteryMap 挪到锁外，161 个用例全绿 —— 因为原 lint 只找
            // snapshotPlayerData 的偏移，`wordMasteryCache.get(playerId)` 从来没被检查过。
            List<Integer> masteryCalls =
                    qualifiedCallOffsets(body, "wordMasteryCache", "get");
            assertFalse(masteryCalls.isEmpty(),
                    "在 savePlayerDataInternal 方法体里找不到 wordMasteryCache.get(...) 喵。\n"
                            + "P1-3 要求 word_progress 路径的 masteryMap 也在连接锁内取；"
                            + "读法被改名会让本 lint 静默失效。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改调 masteryOf(playerId) 之类的包装），请同步本 lint —— "
                            + "但取值位置必须仍在锁内。");

            // 「锁内摆一次当摆设、锁外再取一次真用」对 mastery 同样成立：锁外那份才是真正
            // 迭代进 upsertStmt 的 map。与快照一致，要求<b>每一次</b>读取都落在同一个锁块内。
            int masteryBlock = -1;
            for (int offset : masteryCalls) {
                int block = indexOfBlockContaining(lockBlocks, offset);
                assertTrue(block >= 0,
                        "P1-3 不变式被破坏：wordMasteryCache.get(...) 不在任何 "
                                + "synchronized (databaseManager.getConnectionLock()) 块内 喵"
                                + "（mastery 偏移=" + offset + "，连接锁块区间="
                                + renderRanges(lockBlocks) + "）。\n"
                                + "锁外读 masteryMap 的故障链：异步保存线程在锁外拿到非 null 的旧 map 引用后排队；"
                                + "期间玩家 quit，unloadPlayerData 清空 correct/incorrect/review/lastReview "
                                + "四个计数 map 并全量写好 DB；本线程此后获锁，masteryMap 仍带着词表，"
                                + "但 getIntValue(map, word) 对已清空的 map 一律回落 0 —— "
                                + "于是用「正确的 mastery + 全 0 计数」覆盖掉刚写好的行，"
                                + "复习次数、正确/错误计数、最近复习时间全部清零。\n"
                                + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                                + "（如把整段取值+写库搬进锁内的私有方法），请同步本 lint。");
                if (masteryBlock < 0) {
                    masteryBlock = block;
                } else {
                    assertEquals(masteryBlock, block,
                            "P1-3 不变式被破坏：wordMasteryCache.get(...) 的多次读取"
                                    + "没有落在<b>同一个</b>连接锁块里 喵（偏移=" + masteryCalls
                                    + "，连接锁块区间=" + renderRanges(lockBlocks) + "）。\n"
                                    + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");
                }
            }

            assertEquals(snapshotBlock, masteryBlock,
                    "P1-3 不变式被破坏：snapshotPlayerData 与 wordMasteryCache.get(...) "
                            + "没有落在<b>同一个</b>连接锁块里 喵"
                            + "（快照所在块=" + snapshotBlock + "，mastery 所在块=" + masteryBlock
                            + "，连接锁块区间=" + renderRanges(lockBlocks) + "）。\n"
                            + "两条路径必须在「同一个观察点」取值：分成两段 synchronized（同一把锁）"
                            + "只保证了互斥，不保证两次取值之间没有被插进来一次 quit —— "
                            + "第一段读 player_data、放锁、quit 清缓存并全量落盘、第二段读 masteryMap，"
                            + "两个旧值照样拼成一次错误覆盖。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");
        }

        // ===== 3. RT-F-b =====

        /**
         * RT-F-b：{@code onDisable} 里 {@code clearAllSessions()} 必须被 try/catch 包住。
         *
         * <p>故障链是真实存在的、不是假想：{@code clearAllSessions()} → {@code endSession}
         * → {@code PlayerDataStore.updateWordProgress} → 缓存缺失时 {@code loadPlayerData}
         * → {@code DatabaseManager.getConnection()}，后者在取连接失败时
         * {@code throw new RuntimeException("Failed to get database connection", e)}。
         * 没有这层兜底时整段 onDisable 就此中断，后面四件事全部跳过：决斗收尾、
         * GUI 清扫（holder 跨 ClassLoader 裂缝的唯一堵漏手段）、全量落盘、关库。
         *
         * <p>必须 {@code catch (Throwable)} 而不是 {@code catch (Exception)}：停用路径真正
         * 要防的正是 {@code LinkageError} / {@code NoClassDefFoundError} 这类换 ClassLoader
         * 或依赖缺失才会冒出来的 Error。
         *
         * <p>红队绕过（FIX-4 前全绿）：
         * <pre>
         * try { wordLearningManager.clearAllSessions(); }
         * catch (Throwable t) { throw new IllegalStateException(t); }   // 照旧截断 onDisable
         * </pre>
         * 或把调用从 try 块里挪出去、旁边留一个无关的空 try。因此这里用括号配对求
         * try 块的区间，断言调用在块内；再解析该 try 的 catch 子句，要求参数是
         * {@code Throwable} <b>且</b> catch 体不含 {@code throw}。
         */
        @Test
        @DisplayName("RT-F-b：clearAllSessions 被 try/catch(Throwable) 包住且不重抛")
        void clearAllSessionsGuardedByTryCatch() {
            String body = methodBody("LetsLearnEnglish.java", "public void onDisable()");

            int call = body.indexOf("clearAllSessions()");
            assertTrue(call >= 0,
                    "在 onDisable 方法体里找不到 clearAllSessions() 喵。\n"
                            + "会话清理是停用流程里第一个碰 DB 的步骤，删掉它会留下未结算的会话。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如抽成 clearSessionsSafely() 辅助方法并在其中 try/catch），"
                            + "请同步本 lint。");

            List<int[]> guards = enclosingTryBlocks(body, call);
            assertFalse(guards.isEmpty(),
                    "onDisable 里的 clearAllSessions() 没有被任何 try 块包住 喵"
                            + "（调用偏移=" + call + "）。\n"
                            + "RT-F-b：没有这层兜底时，一次 DB 异常（getConnection 在取连接失败时"
                            + "抛 RuntimeException，是必然会发生而不是理论风险）就会截断整段停用流程 ——"
                            + "决斗收尾、GUI 清扫、全量落盘、关库全部跳过。\n"
                            + "本检查要求调用落在某个 try 块的括号配对区间内；「在调用前后各放一个"
                            + "无关的空 try/catch」不算。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（把清理抽成自带 try/catch 的辅助方法），请同步本 lint —— "
                            + "误红必须由人裁决，不得改回无兜底实现。");

            // 由内向外找第一个带 catch 的 try（内层 try 只有 finally 时，兜底可能在外层）。
            List<CatchClause> catches = List.of();
            for (int[] candidate : guards) {
                List<CatchClause> found = catchClausesAfter(body, candidate[1]);
                if (!found.isEmpty()) {
                    catches = found;
                    break;
                }
            }
            assertFalse(catches.isEmpty(),
                    "clearAllSessions() 所在的 try 块后面没有 catch 子句 喵"
                            + "（try 块区间=" + renderRanges(guards) + "，调用偏移=" + call + "）。\n"
                            + "try/finally 不带 catch 无法吞掉异常，停用流程照样被截断。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            CatchClause throwing = null;
            CatchClause nonThrowable = null;
            for (CatchClause c : catches) {
                if (!c.throwable) {
                    nonThrowable = c;
                } else if (c.rethrows) {
                    throwing = c;
                }
            }
            boolean safe = catches.stream().anyMatch(c -> c.throwable && !c.rethrows);

            assertTrue(safe,
                    "clearAllSessions() 的 try/catch 兜底无效 喵"
                            + (throwing != null
                            ? "：catch (" + throwing.type + " " + throwing.var + ") 体内有 throw，"
                            + "异常被重新抛出，onDisable 照样在这里截断，后面四件事全部跳过。"
                            : "")
                            + (nonThrowable != null
                            ? "：catch 参数类型是 " + nonThrowable.type
                            + "，不是 Throwable —— 停用路径要防的正是 LinkageError / "
                            + "NoClassDefFoundError 这类换 ClassLoader 或依赖缺失才会出现的 Error，"
                            + "catch (Exception) 会让它们照旧截断停用流程。"
                            : "")
                            + "\n这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如改成 try-with-resources 形状或抽成辅助方法），请同步本 lint。");
        }

        // ===== 4. P2-2 =====

        /**
         * P2-2：{@code saveDirty} 必须把 {@code dirtyWords} 的<b>活引用</b>交给
         * {@code savePlayerDataInternal}，不得先拷贝。
         *
         * <p>反例是真实发生过的窗口：{@code saveDirty} 在<b>参数求值</b>时先执行
         * {@code new ArrayList<>(words)}（t0），随后主线程 {@code markWordDirty(W)} 完成
         * add + bump 序号 4→5（t1），本方法才读 {@code seqAtSnapshot} 得到 5（t2）——
         * 序号已包含那次写入，但拷贝里没有 W，本次只写下旧词（t3）；commit 后
         * {@code clearDirty} 比较 {@code currentWriteSeq()==5 == seqAtSnapshot==5}，判定
         * 「窗口内无新写入」而整包清空（t4）。结果是 W 的标记被清、数据也从未落库，
         * 只能等 12 个活跃轮次 / 36 个空闲轮次的全量，或玩家 quit 时的 unloadPlayerData 才补上。
         *
         * <p>活引用安全的前提是 {@code newKeySet()} 的迭代弱一致（不会 CME），且
         * {@code savePlayerDataInternal} 在 seq 采集<b>之后</b>才开始迭代 —— 那一点由
         * {@link StructuralLints#snapshotCapturedInsideConnectionLock()} 钉住。
         *
         * <p>本 lint 改成「拷贝形态黑名单 + 活引用白名单」：
         * <ul>
         *   <li>白名单：{@code hasWords ? words :}、{@code Collections.unmodifiableSet(words)}
         *       （不拷贝的视图）、以及 {@code if (hasWords) { ... words ... } else { ... }}
         *       的局部变量形态；</li>
         *   <li>黑名单：任何 {@code new Xxx<>(words)}、{@code List/Set/Collection.copyOf(words)}、
         *       {@code words.stream()/toArray()} —— 全是拷贝，一个都不放行。</li>
         * </ul>
         * 原实现的失败消息写着「允许 Collections.unmodifiableSet 视图」，代码却只认
         * {@code hasWords ? words :}，属于自相矛盾；现在两者一致。
         */
        @Test
        @DisplayName("P2-2：saveDirty 传活引用，不预先拷贝 dirtyWords")
        void playDataStoreSaveDirtyUsesLiveReference() {
            String body = methodBody("PlayerDataStore.java", "public void saveDirty(UUID playerId)");

            Matcher copyCtor = Pattern.compile(
                    "new\\s+[A-Za-z_$][\\w$.]*(?:\\s*<[^<>]*>)?\\s*\\(\\s*words\\s*\\)").matcher(body);
            boolean copyCtorHit = copyCtor.find();
            String copyCtorText = copyCtorHit ? copyCtor.group() : "";
            assertFalse(copyCtorHit,
                    "saveDirty 方法体里出现了把 dirtyWords 拷进新容器的写法 喵：" + copyCtorText
                            + "\nP2-2：参数求值发生在方法体之前，拷贝会把「拷贝完成 → 序号采集」"
                            + "之间新增的词挡在本次写入之外，而序号已包含那次写入；"
                            + "commit 后 clearDirty 会判定「窗口内无新写入」把整包标记清掉，"
                            + "那些词永久丢失（不是「多保留一轮」，是标记丢失 + 数据滞后）。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；new ArrayList<>(words) / "
                            + "new HashSet<>(words) / List.copyOf(words) 一律不放行。");

            Matcher copyOf = Pattern.compile(
                    "(?:List|Set|SortedSet|Collection|EnumSet|Map|HashMap|ArrayList|HashSet)"
                            + "\\s*\\.\\s*copyOf\\s*\\(\\s*words\\s*\\)").matcher(body);
            boolean copyOfHit = copyOf.find();
            String copyOfText = copyOfHit ? copyOf.group() : "";
            assertFalse(copyOfHit,
                    "saveDirty 方法体里出现了 copyOf(words) 喵：" + copyOfText
                            + "\ncopyOf 是深拷贝，与 new ArrayList<>(words) 同一个缺陷窗口。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            Matcher copyStream = Pattern.compile(
                    "words\\s*\\.\\s*(?:stream|parallelStream|toArray)\\s*\\(").matcher(body);
            boolean copyStreamHit = copyStream.find();
            String copyStreamText = copyStreamHit ? copyStream.group() : "";
            assertFalse(copyStreamHit,
                    "saveDirty 方法体里出现了 words.stream()/toArray() 喵：" + copyStreamText
                            + "\n流式收集会新建容器，仍然是拷贝 —— 复制发生在序号采集之前，"
                            + "窗口内的新词照样被挡在外面。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            Matcher addAll = Pattern.compile("\\.\\s*addAll\\s*\\(\\s*words\\s*\\)").matcher(body);
            boolean addAllHit = addAll.find();
            String addAllText = addAllHit ? addAll.group() : "";
            assertFalse(addAllHit,
                    "saveDirty 方法体里出现了 addAll(words) 喵：" + addAllText
                            + "\n先 new 容器再 addAll 与 new ArrayList<>(words) 是同一件事："
                            + "拷贝发生在序号采集之前，窗口内的新词被挡在写入之外。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构，请同步本 lint。");

            boolean liveRef = body.contains("hasWords ? words :")
                    || body.contains("Collections.unmodifiableSet(words)")
                    || hasIfElseLiveReference(body);

            assertTrue(liveRef,
                    "在 saveDirty 方法体里找不到把 dirtyWords 的活引用交给 "
                            + "savePlayerDataInternal 的形状 喵。\n"
                            + "接受的形状：`hasWords ? words : ...`、"
                            + "`Collections.unmodifiableSet(words)`（不拷贝的视图）、"
                            + "或 `if (hasWords) { ... words ... } else { ... }` 的局部变量形态。\n"
                            + "P2-2：增量保存必须把 dirtyWords 的活引用交给 savePlayerDataInternal，"
                            + "让它在本方法采集写入序号之后再迭代；否则「拷贝完成 → 序号采集」"
                            + "之间新增的词会被挡在本次写入之外，而序号又已计入它 → "
                            + "clearDirty 判定窗口内无新写入、清掉标记，那次写入永久丢失。\n"
                            + "这是语义锚点检查，比字符串匹配更严格；若为真正等价的重构"
                            + "（例如先赋给局部变量 liveWords 再传），请同步本 lint。");
        }
    }

    // ===== 辅助方法（本类独立实现，刻意不复用 GuiViewKeyCoverageTest） =====

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
     * {@code minecraftplugin/}（{@code LetsLearnEnglish}）与
     * {@code minecraftplugin/data/}（{@code PlayerDataStore}），写死包路径会在
     * 「文件被挪到别的包」时静默失效。按名查找 + 唯一性校验把这种失效变成一次明确的红灯。
     *
     * @param fileName 文件名（含 {@code .java}），须在源码树中唯一
     */
    private static String sourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (java.io.IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }

        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动/改名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (hits.size() > 1) {
            fail("源码树里有 " + hits.size() + " 个 " + fileName + "（"
                    + hits.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("、"))
                    + "），无法唯一定位喵 —— 请把本 lint 的锚点改成相对路径。");
        }

        try {
            return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
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
     * <p>签名必须<b>在源码中唯一出现</b>：既不能有重载，也不能有同名的调用点。
     * 这里刻意不提供「第 N 个匹配」这种逃生口 —— 一旦需要它，说明 lint 的定位不再
     * 可信，应当改成先 grep 出唯一锚点再断言。
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
        // 签名与左花括号之间若出现分号，说明匹配到的是声明/调用而不是带体的方法。
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
     * <p>这是本类「语义锚点」的基础：只有块区间是真的，才能断言调用<b>在块内</b>。
     * 单纯比较 {@code indexOf} 偏移会被诱饵空块（{@code synchronized (...) { }}）骗过。
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

    /** 去掉全部空白，用于比较 {@code synchronized (...)} 的参数表达式。 */
    private static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }

    /**
     * 去掉表达式开头的 {@code this.}（可重复出现）。
     *
     * <p>FIX-6：{@code this.databaseManager.getConnectionLock()} 与
     * {@code databaseManager.getConnectionLock()} 是<b>同一个</b>表达式 —— 显式 {@code this.}
     * 只是写法偏好。归一化后比较，避免把等价重构误判成「另一把锁」而误红。
     */
    private static String thisPrefix(String normalizedExpr) {
        String s = normalizedExpr;
        while (s.startsWith("this.")) {
            s = s.substring("this.".length());
        }
        return s;
    }

    /**
     * 返回 {@code offset} 落在哪个区间的 {@code (open, close)} 内部的下标；不属于任何区间返回 {@code -1}。
     *
     * <p>区间必须是左开右闭语义的「严格内部」：端点本身就是花括号，
     * 与 {@code snapshot > block[0] && snapshot < block[1]} 保持一致。
     */
    private static int indexOfBlockContaining(List<int[]> blocks, int offset) {
        for (int i = 0; i < blocks.size(); i++) {
            int[] block = blocks.get(i);
            if (offset > block[0] && offset < block[1]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 找出方法体内所有「以单个标识符实参调用 {@code method}」的偏移，按出现顺序返回。
     *
     * <p>FIX-6：用「调用次数」而不是「第一次出现的偏移」来堵「锁内摆设 + 锁外真用」——
     * 正确实现只该取一次快照，出现第二次就说明有人另取了一份（多半是排队前那份旧值）。
     *
     * <p>实参只要求是<b>单个标识符</b>（{@code playerId} / {@code id} 都算）：参数改名是
     * 合法重构，不该让计数失效。<b>不</b>匹配嵌套调用（{@code f(g(x))}）—— 那种写法
     * 会先在「锁内块」那条断言上失败，报错信息也更准确。
     */
    private static List<Integer> callOffsets(String body, String method) {
        List<Integer> out = new ArrayList<>();
        Pattern p = Pattern.compile("(?<![\\w$.])" + Pattern.quote(method)
                + "\\s*\\(\\s*[A-Za-z_$][\\w$]*\\s*\\)(?![\\w$])");
        Matcher m = p.matcher(body);
        while (m.find()) {
            out.add(m.start());
        }
        return out;
    }

    /**
     * 找出方法体内所有 {@code receiver.method(单个标识符)} 调用的偏移，按出现顺序返回。
     *
     * <p>与 {@code callOffsets} 相比，这里额外限定接收者 ——
     * 其它缓存容器上的同名 {@code get} 不算数；实参名同样不敏感。
     */
    private static List<Integer> qualifiedCallOffsets(String body, String receiver, String method) {
        List<Integer> out = new ArrayList<>();
        Pattern p = Pattern.compile("(?<![\\w$.])" + Pattern.quote(receiver)
                + "\\s*\\.\\s*" + Pattern.quote(method)
                + "\\s*\\(\\s*[A-Za-z_$][\\w$]*\\s*\\)(?![\\w$])");
        Matcher m = p.matcher(body);
        while (m.find()) {
            out.add(m.start());
        }
        return out;
    }

    /**
     * 找出方法体内所有「局部别名 = playerDataStore」的变量名。
     *
     * <p>覆盖 {@code var store = playerDataStore;}、{@code PlayerDataStore store = playerDataStore;}、
     * {@code final PlayerDataStore store = playerDataStore;}、{@code store = this.playerDataStore;}
     * 以及 {@code store = getPlayerDataStore();}。红队的绕过正是
     * {@code Consumer<UUID> fullSaver = playerDataStore::savePlayerData;} —— 接收者换了个名字，
     * 只认字面量 {@code playerDataStore.} 的 lint 会全绿放行。
     *
     * <p>返回列表<b>总是包含</b> {@code playerDataStore} 本身，调用方直接使用即可。
     */
    private static List<String> aliasesOf(String body, String target) {
        List<String> out = new ArrayList<>();
        out.add(target);
        // this.playerDataStore / getPlayerDataStore() 是同一对象的两种等价写法，都要算别名来源。
        String getter = "get" + Character.toUpperCase(target.charAt(0)) + target.substring(1);
        out.add(getter); // 直连写法：getPlayerDataStore().savePlayerData(...)
        String rhs = "(?:(?:this\\s*\\.\\s*)?" + Pattern.quote(target)
                + "|(?:this\\s*\\.\\s*)?" + Pattern.quote(getter) + "\\s*\\(\\s*\\))";
        Matcher m = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*=\\s*(?:" + rhs + ")\\s*;").matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * 方法体内是否出现「在给定接收者上调用/引用 {@code method}」。
     *
     * <p>同时识别四种形状：{@code recv.method(...)}、{@code recv::method}、
     * {@code recv().method(...)} / {@code recv()::method}（getter 直连）、
     * 裸方法引用 {@code ::method}（接收者无法静态确定，按最坏情况算）。
     * 由于注释与字符串已被抹成空格，这里不会把注释里的调用误算成代码。
     */
    private static boolean callsMethod(String body, List<String> receivers, String method) {
        Matcher m = Pattern.compile(RECV + Pattern.quote(method) + "(?![\\w$])").matcher(body);
        while (m.find()) {
            String recv = m.group(1);
            if (recv == null || receivers.contains(recv)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找出方法体内所有「参数为 {@code databaseManager.getConnectionLock()}」的
     * {@code synchronized} 块区间。
     *
     * <p>只认参数表达式（归一化去空白后）完全等于 {@code databaseManager.getConnectionLock()}
     * 的块：{@code synchronized (new Object())} 不是同一把锁，必须排除。
     *
     * @return 每个块一个 {@code int[]{左花括号索引, 右花括号索引}}
     */
    private static List<int[]> connectionLockBlocks(String body) {
        List<int[]> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("\\bsynchronized\\s*\\(").matcher(body);
        while (m.find()) {
            int parenOpen = body.indexOf('(', m.start());
            int parenClose = findMatchingParen(body, parenOpen);
            if (parenClose < 0) {
                continue;
            }
            String expr = normalizeWhitespace(body.substring(parenOpen + 1, parenClose));
            // 归一化后再去掉可选的 `this.` 前缀：`this.databaseManager.getConnectionLock()`
            // 与 `databaseManager.getConnectionLock()` 是同一个表达式，不是另一把锁。
            String canonical = thisPrefix(expr);
            if (!"databaseManager.getConnectionLock()".equals(canonical)) {
                continue;
            }
            int braceOpen = body.indexOf('{', parenClose);
            if (braceOpen < 0) {
                continue;
            }
            int braceClose = findBlockByBraceMatching(body, braceOpen);
            if (braceClose < 0) {
                continue;
            }
            blocks.add(new int[]{braceOpen, braceClose});
        }
        return blocks;
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
     * 找出包含 {@code call} 的<b>所有</b> {@code try} 块区间，按「由内向外」排序。
     *
     * <p>必须用括号配对而不是「往前找最近的 {@code try {}」：红队把调用从 try 块里挪出去、
     * 前后各留一个无关的空 try，字符串搜索会指到错误的块上。
     */
    private static List<int[]> enclosingTryBlocks(String body, int call) {
        List<int[]> found = new ArrayList<>();
        Matcher m = Pattern.compile("\\btry\\s*\\{").matcher(body);
        while (m.find()) {
            int braceOpen = body.indexOf('{', m.start());
            int braceClose = findBlockByBraceMatching(body, braceOpen);
            if (braceClose < 0 || call <= braceOpen || call >= braceClose) {
                continue;
            }
            found.add(new int[]{braceOpen, braceClose});
        }
        // 区间更窄 = 更内层。由内向外，第一条带 catch 的就是最近的一层兜底。
        found.sort((a, b) -> Integer.compare(a[1] - a[0], b[1] - b[0]));
        return found;
    }

    /** 一个 {@code catch} 子句的解析结果。 */
    private static final class CatchClause {
        private final String type;
        private final String var;
        private final boolean throwable;
        private final boolean rethrows;

        private CatchClause(String type, String var, boolean throwable, boolean rethrows) {
            this.type = type;
            this.var = var;
            this.throwable = throwable;
            this.rethrows = rethrows;
        }
    }

    /**
     * 解析紧跟在 {@code tryBlockClose} 之后的 {@code catch} 子句链。
     *
     * <p>对每个 catch 记录：参数类型、变量名、是否 {@code Throwable}、体内是否含
     * {@code throw}。两个判据缺一不可 —— {@code catch (Throwable t) { throw ...; }}
     * 异常照样向上冒，onDisable 照样被截断；{@code catch (Exception e) {}} 则漏掉
     * {@code LinkageError} / {@code NoClassDefFoundError}。
     */
    private static List<CatchClause> catchClausesAfter(String body, int tryBlockClose) {
        List<CatchClause> out = new ArrayList<>();
        int p = skipWhitespace(body, tryBlockClose + 1);
        while (p < body.length() && body.startsWith("catch", p)
                && (p + 5 >= body.length() || !Character.isJavaIdentifierPart(body.charAt(p + 5)))) {
            int parenOpen = body.indexOf('(', p);
            int parenClose = findMatchingParen(body, parenOpen);
            if (parenOpen < 0 || parenClose < 0) {
                break;
            }
            String params = body.substring(parenOpen + 1, parenClose).strip();
            String type = "";
            String var = "";
            Matcher pm = Pattern.compile("(?:final\\s+)?([\\w$.]+(?:\\s*\\|\\s*[\\w$.]+)*)\\s+(\\w+)$")
                    .matcher(params);
            if (pm.find()) {
                type = pm.group(1);
                var = pm.group(2);
            }

            int braceOpen = body.indexOf('{', parenClose);
            if (braceOpen < 0) {
                break;
            }
            int braceClose = findBlockByBraceMatching(body, braceOpen);
            if (braceClose < 0) {
                break;
            }
            String catchBody = body.substring(braceOpen + 1, braceClose);

            boolean isThrowable = Pattern.compile("(?:^|[\\s|])(?:java\\.lang\\.)?Throwable$")
                    .matcher(type.strip()).find();
            boolean rethrows = Pattern.compile("\\bthrow\\b").matcher(catchBody).find();

            out.add(new CatchClause(type, var, isThrowable, rethrows));
            p = skipWhitespace(body, braceClose + 1);
            // finally 之后不会再有 catch，直接结束。
            if (p < body.length() && body.startsWith("finally", p)) {
                break;
            }
        }
        return out;
    }

    /**
     * 是否采用 {@code if (hasWords) { ... words ... } else { ... }} 的局部变量形态。
     *
     * <p>P2-2 的另一种等价写法：先按条件选好要传的集合，再一次性调用。这里要求
     * then 分支引用 {@code words} 且 else 分支存在 —— 拷贝形态已由黑名单单独拦下，
     * 所以 else 分支只需要「存在」，允许 {@code Collections.emptyList()}。
     */
    private static boolean hasIfElseLiveReference(String body) {
        Matcher m = Pattern.compile("\\bif\\s*\\(\\s*hasWords\\s*\\)").matcher(body);
        while (m.find()) {
            int thenOpen = body.indexOf('{', m.end());
            if (thenOpen < 0) {
                continue;
            }
            int thenClose = findBlockByBraceMatching(body, thenOpen);
            if (thenClose < 0) {
                continue;
            }
            int q = skipWhitespace(body, thenClose + 1);
            if (!body.startsWith("else", q)) {
                continue;
            }
            int elseOpen = body.indexOf('{', q);
            if (elseOpen < 0) {
                continue;
            }
            int elseClose = findBlockByBraceMatching(body, elseOpen);
            if (elseClose < 0) {
                continue;
            }
            String thenPart = body.substring(thenOpen + 1, thenClose);
            String elsePart = body.substring(elseOpen + 1, elseClose);
            if (thenPart.contains("words") && !elsePart.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥掉 Java 注释，并把字符串/字符字面量的内容抹成空格（单遍字符扫描状态机）。
     *
     * <p>为什么不能一步正则：源码里有含 {@code //} 的 URL 字符串、有 Javadoc 里的
     * {@code Bukkit.createInventory(...)} 字面量、有标题里的 {@code "{page}"}。正则无法
     * 区分「注释里的引号」与「字符串里的斜杠」。状态机按 JLS 的词法顺序处理
     * 行注释 → 块注释 → 文本块 → 普通字符串 → 字符字面量，其余字符原样保留。
     *
     * <p>注释与字面量的内容统一替换为<b>空格（保留换行）</b>：这样后续的
     * {@code indexOf} 偏移与原文一致，失败消息里的偏移可以直接对照源码；同时
     * 「注释里出现的 savePlayerData」不会被误算成代码。
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
