package com.letslearnenglish.minecraftplugin.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

import net.milkbowl.vault.economy.Economy;

/**
 * 第 17 轮审计的三条「<b>纯内存状态</b>」问题：进程内状态在异常 / 重启 / reload 窗口里
 * 会丢或泄漏（N9 / N10 / N12）。
 *
 * <h2>本类的定位：诊断 + 门禁</h2>
 * <ol>
 *   <li><b>把缺陷写成 {@code @Disabled} 的期望断言</b> —— 摘掉注解即红，红的信息里带着
 *       实测值。刻意<b>不</b>写「断言现状 = 双发 / 集合只增」的绿测试：那会把缺陷固化成
 *       契约（同 {@code DailyCapCrossServerTest} 的绿/红划分理由）。
 *       目前只剩 <b>N9</b> 一条（{@code pendingClaimLedgerWrites} 纯内存、无持久化）。</li>
 *   <li><b>钉住审计报告说错、或修复后仍必须成立的事实</b>（绿测试）—— 尤其是 N9 的补写
 *       登记表<b>确实</b>会被午夜 rollover 清掉这一点（审计说「不清」，实测相反），
 *       以及 N12 的<b>结构性不可达</b>证据。</li>
 * </ol>
 *
 * <h2>N10 已修（跨天裁剪）</h2>
 * <p>{@code paidButUnmarked} 过去只增不删。修法是在领奖入口<b>跨天后的第一次点击</b>整片
 * 裁掉非今日的键（{@code MiscGUIListener.prunePaidButUnmarkedForNewDay}），并由
 * 「已裁剪到哪一天」的闸门把扫描次数收敛到<b>每个自然日至多一次</b>。
 * 见 {@link N10PaidButUnmarked#hasACleanupPointTriggeredByTheClaimEntry()}（形状门禁）与
 * {@link N10PaidButUnmarked#crossDayPruneDropsStaleKeys()}（行为，反射直调 private 方法）。
 * 刻意<b>不</b>加 {@code PlayerQuitEvent} 按玩家清 —— 那会把「钱已到账、台账没写」的护栏
 * 在玩家重进后重新打开（二次付款）。
 *
 * <h2>N12 的结论（本类最重要的产出）</h2>
 * 审计称「{@code Thread.sleep} 在 stripe lock 内，GUI 领奖路径可达」。<b>实测不成立</b>：
 * {@code readWordChallengeCompletionWithRetry} 在 {@code src/main} 里<b>只有一个调用点</b>
 * （{@code hasPendingWordRewardForDate}），后者在 {@code src/main} 里也<b>只有一个调用点</b>
 * （{@code LetsLearnEnglish} 的午夜 rollover 主线程段），而那段代码<b>不持有任何 stripe
 * lock</b>。GUI 的 {@code claimBehavioralChallenge} 走的是<b>行为</b>挑战领取，
 * 与<b>单词</b>挑战的 pending 判定是两条互不相交的链。
 * 见 {@link N12StripeLockReachability} 的 L1 / L2 / L3 —— 它们是<b>绿</b>的回归门禁。
 *
 * <p><b>N12 仍然真实存在的残余</b>（形状不同，报告里必须写清）：stripe lock 内虽然没有
 * {@code Thread.sleep}，但整段「查库 → Vault 转账 → 写台账」都在<b>主线程</b>且持锁。
 * 同 stripe 的玩家等的是数据库 socket 延迟（{@code socketTimeout=30s} 量级），不是 100ms
 * 退避。它<b>不是</b>审计描述的那条链，也不该按那条链的修法去改。
 *
 * <p><b>纯 JVM</b>：只触碰 {@code Bukkit.getPlayer}（static mock 接管），其余 plugin / DB /
 * Vault / 消息全 mock；奖励金额走真 {@link YamlConfiguration}（mock 的
 * {@code getInt(key, def)} 返回 0 会让发奖金额失真 —— 同
 * {@code DailyChallengeLedgerFailureTest} 的理由）。
 */
@DisplayName("N9/N10/N12：纯内存状态的持久性与锁边界")
class InMemoryStateDurabilityTest {

    /** 不该出现在 GUI 领奖锁内的两个名字 —— 两者都是 {@code Thread.sleep} 链的上游。 */
    private static final String[] FORBIDDEN_IN_LOCK = {
            "readWordChallengeCompletionWithRetry",
            "hasPendingWordReward"
    };

    // ==================================================================
    //  N9：台账补写清单是纯内存 —— 重启后同日重复发钱
    // ==================================================================

    /**
     * N9 的用例需要真实的行为链（发钱 / 写台账 / 补写），故自带一套 mock 基座。
     *
     * <p><b>为什么 mock 只放在这个嵌套类里</b>：N10 / N12 全是源码 lint，不需要任何
     * mock。若把 {@code @BeforeEach} 提到外层，Mockito 的 strict stubs 会在纯 lint 用例里
     * 报 {@code UnnecessaryStubbingException}（本仓 {@code DailyChallengeLedgerFailureTest}
     * 显式用过 {@code lenient()} 即为证）。
     */
    @Nested
    @DisplayName("N9：daily_word_reward_claims 写失败后的补写清单")
    class N9PendingClaimLedger {

        private static final UUID PLAYER_ID =
                UUID.fromString("00000000-0000-0000-0000-000000000917");
        private static final String TODAY = "2026-11-10";
        private static final String OTHER_DAY = "2026-11-11";
        private static final double WORD_REWARD = 100.0;

        private LetsLearnEnglish plugin;
        private WordManagerImpl wordManager;
        private DatabaseManager databaseManager;
        private ConfigManager configManager;
        private MessageUtil messageUtil;
        private Economy economy;
        private ProgressManagerImpl progressManager;
        private RewardEngine rewardEngine;
        private Player player;
        private Logger logger;

        @BeforeEach
        void setUp() {
            plugin = mock(LetsLearnEnglish.class);
            wordManager = mock(WordManagerImpl.class);
            databaseManager = mock(DatabaseManager.class);
            configManager = mock(ConfigManager.class);
            messageUtil = mock(MessageUtil.class);
            economy = mock(Economy.class);
            progressManager = mock(ProgressManagerImpl.class);
            rewardEngine = mock(RewardEngine.class);
            player = mock(Player.class);
            logger = mock(Logger.class);

            FileConfiguration mainConfig = new YamlConfiguration();
            mainConfig.set("daily-challenges.word-challenge-reward", WORD_REWARD);

            when(plugin.getDatabaseManager()).thenReturn(databaseManager);
            when(plugin.getConfigManager()).thenReturn(configManager);
            when(plugin.getMessageUtil()).thenReturn(messageUtil);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getEconomy()).thenReturn(economy);
            when(plugin.getProgressManager()).thenReturn(progressManager);
            when(configManager.getMainConfig()).thenReturn(mainConfig);
            when(progressManager.getRewardEngine()).thenReturn(rewardEngine);
            when(rewardEngine.isMoneyEnabled()).thenReturn(true);
            when(rewardEngine.depositCappedReward(any(Player.class), anyDouble()))
                    .thenReturn(WORD_REWARD);

            when(player.getUniqueId()).thenReturn(PLAYER_ID);
            when(player.isOnline()).thenReturn(true);
        }

        /** 让 {@code hasPendingWordRewardForDate} 认为「该日全完成、尚未付款」= 会真的发钱。 */
        private void stubUnsettledButCompleted(String date) {
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, date)).thenReturn(false);
            when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, date))
                    .thenReturn(DatabaseManager.WordChallengeCompletion.COMPLETED);
        }

        /**
         * <b>缺陷记录（N9）</b>：「钱已发、台账没写进去」的欠账只存在于
         * {@code pendingClaimLedgerWrites}（{@code ConcurrentHashMap}，无持久化）。
         *
         * <h2>为什么这条是 @Disabled 而不是绿测试</h2>
         * 断言的是<b>期望</b>（一个挑战日最多发一次钱）。当前实现下它必红，红的实测值是 2 次。
         * 写「现状 = 2 次」的绿测试等于把这个缺陷固化成契约。
         *
         * <h2>时间线</h2>
         * <ol>
         *   <li>进程 A：玩家完成全部挑战词 → 发 100 金币（Vault 转账成功）→ 写台账<b>失败</b>
         *       → 内存位标记已付、{@code pendingClaimLedgerWrites} 登记该日期、一条 SEVERE；</li>
         *   <li><b>进程重启</b>（新 {@code DailyChallengeManager} 实例 = 内存全空）；</li>
         *   <li>玩家再答对任意一词 → 内存已付位空、DB 台账查不到、DB 里挑战词仍「全完成」
         *       → 判定为 pending → <b>再发一次</b>。</li>
         * </ol>
         *
         * <p>「进程重启」用<b>新实例 + 同一个 DB mock</b> 表达 —— 内存态零共享，DB 态共享，
         * 这正是重启的最小等价物（同 {@code DailyCapCrossServerTest} 用两份
         * {@code DatabaseManager} 表达「两个进程」的手法）。
         *
         * <p><b>修复后请摘掉 {@code @Disabled}</b>：任何让欠账跨重启可见的修法
         * （持久化对账标记 / 写台账重试直到成功 / 发钱前置台账位）都应让本用例转绿。
         */
        @Disabled("已知残留（N9）：pendingClaimLedgerWrites 是纯内存 ConcurrentHashMap，"
                + "进程重启即清空；「钱已发、台账没写」的欠账只剩一条 SEVERE 日志。"
                + "修复方向：把补写清单持久化（同一张写不进去的表之外的对账载体），"
                + "或改成「先写台账、后发钱」的幂等顺序。修好后摘掉本注解即可当回归用")
        @Test
        @DisplayName("N9 缺陷记录：台账写失败 + 进程重启 → 同一挑战日重复发放")
        void ledgerWriteFailurePlusRestartPaysTwice() {
            stubUnsettledButCompleted(TODAY);
            // 台账始终写不进去（模拟持续性 DB 写故障）。
            when(databaseManager.markDailyWordRewardClaimed(PLAYER_ID, TODAY)).thenReturn(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

                // ── 进程 A ──────────────────────────────────────────
                DailyChallengeManager processA = new DailyChallengeManager(plugin, wordManager);
                processA.hasPendingWordRewardForDate(PLAYER_ID, TODAY);

                // 两个「进程」各失败一次，故 SEVERE 至少出现一次（用 atLeastOnce，不锁次数）。
                verify(logger, atLeastOnce()).severe(contains("ledger write FAILED"));
                verify(logger, atLeastOnce()).severe(contains(TODAY));
                // 进程 A 确实发了一次钱（前置事实，必须成立）。
                verify(rewardEngine, times(1)).depositCappedReward(player, WORD_REWARD);

                // ── 进程重启 ────────────────────────────────────────
                DailyChallengeManager processB = new DailyChallengeManager(plugin, wordManager);

                // 重启后同一天玩家再答对一词 → 同一条判定链重跑。
                processB.hasPendingWordRewardForDate(PLAYER_ID, TODAY);

                // 期望：整日只发一次（desired behaviour）。当前实现下这里必红 ——
                // Mockito 的失败信息会直接报出实际次数（Wanted 1 time but was 2 times）。
                // 成因：进程 A 的付款只记在内存（台账写失败 + SEVERE 日志），重启后内存清空、
                // DB 台账查不到、DB 里挑战词仍「全完成」→ 同一天再发一次。
                // 见 pendingClaimLedgerWrites 的 javadoc 与 persistWordRewardClaim 的 SEVERE 文案。
                // 修复后请摘掉本用例的 @Disabled。
                verify(rewardEngine, times(1)).depositCappedReward(player, WORD_REWARD);
            }
        }

        /**
         * <b>绿测试</b>：补写登记表<b>确实</b>被午夜 rollover 清空 ——
         * 审计报告「午夜 rollover 只 {@code clearAllWordChallengeClaims}（清已付位），
         * <b>不清</b> {@code pendingClaimLedgerWrites}」<b>与实测相反</b>
         * （{@code clearAllWordChallengeClaims()} 的末尾确实有
         * {@code pendingClaimLedgerWrites.clear()}）。
         *
         * <p>探针选得讲究：不能在清理之后再用<b>同一天</b>触发
         * {@code hasPendingWordRewardForDate} —— 那样即使登记表没清，发钱路径本身也会再写
         * 一次台账，两次来源混在一起分不清。改用<b>另一天</b>且该日 DB 台账为「已付」：
         * 那样唯一的 {@code markDailyWordRewardClaimed(TODAY)} 来源就只剩补写钩子
         * {@code retryPendingClaimLedgerWrites}，观测是单义的。
         *
         * <p><b>变异判别</b>：删掉 {@code clearAllWordChallengeClaims()} 里的
         * {@code pendingClaimLedgerWrites.clear()} → 第二次判定会多写一次 → 本用例变红。
         */
        @Test
        @DisplayName("N9 契约：clearAllWordChallengeClaims() 会清空补写登记（rollover 后不再补写）")
        void rolloverClearsThePendingLedgerQueue() {
            stubUnsettledButCompleted(TODAY);
            // 另一天已付 → 该日调用不会发钱，只会跑一次补写钩子。
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, OTHER_DAY)).thenReturn(true);
            when(databaseManager.markDailyWordRewardClaimed(PLAYER_ID, TODAY)).thenReturn(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

                DailyChallengeManager manager = new DailyChallengeManager(plugin, wordManager);
                manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY);
                verify(databaseManager, times(1)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);

                // 午夜 rollover 的清理点。
                manager.clearAllWordChallengeClaims();

                // 之后的任何一次判定都不得再补写这笔（登记表已被清空）。
                manager.hasPendingWordRewardForDate(PLAYER_ID, OTHER_DAY);
                verify(databaseManager, times(1)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);
            }
        }

        /**
         * <b>绿测试</b>：{@code unloadPlayer}（quit）<b>不清</b>补写登记 —— 这是<b>正确</b>的
         * 选择，别"顺手修"掉。
         *
         * <p>理由：补写登记是「欠账」，清了就没有任何机会闭合窗口；留着则玩家下次答对
         * （包括同一天重新上线后）就会补写成功，欠账窗口在重启前大多能关掉。
         * 换言之 —— quit 保留登记表是 N9 的<b>唯一</b>缓解手段，删掉它只会让重复发钱更容易。
         * 真正无缓解的只有「进程重启」，那由 {@link #ledgerWriteFailurePlusRestartPaysTwice()}
         * 那条 @Disabled 记录。
         *
         * <p><b>顺手核实审计提到的 reload 问题</b>：{@code /lea reload} 的 legacy 段
         * <b>不重建</b> {@code DailyChallengeManager}（{@code HotReloadManager.reloadAll()}
         * 的 legacy 段只刷配置 / 词库 / 缓存），所以<b>常规 reload 不会打断
         * 补写</b>。只有 {@code server.enabled} 由 false 改 true 走 {@code plugin.enableAll()}
         * 时才会 {@code new DailyChallengeManager(...)} 并丢掉
         * 登记表 —— 而那种场景下运行时本来就是拆解态，不存在在途欠账。
         */
        @Test
        @DisplayName("N9 契约：unloadPlayer 刻意不清补写登记（quit 后仍可补写）")
        void quitKeepsThePendingLedgerQueueOnPurpose() {
            stubUnsettledButCompleted(TODAY);
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, OTHER_DAY)).thenReturn(true);
            when(databaseManager.markDailyWordRewardClaimed(PLAYER_ID, TODAY)).thenReturn(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

                DailyChallengeManager manager = new DailyChallengeManager(plugin, wordManager);
                manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY);
                verify(databaseManager, times(1)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);

                manager.unloadPlayer(PLAYER_ID);

                manager.hasPendingWordRewardForDate(PLAYER_ID, OTHER_DAY);
                verify(databaseManager, times(2)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);
            }
        }

        /**
         * <b>绿测试</b>：{@code resetPlayer} <b>必须</b>清补写登记 —— 否则 reset 删掉的台账行
         * 会被下一次补写重新插回去，玩家当天领不到奖（这是 reset 语义的一部分，非内存卫生）。
         *
         * <p><b>变异判别</b>：删掉 {@code resetPlayer} 里的
         * {@code pendingClaimLedgerWrites.remove(playerId)} → 本用例变红。
         */
        @Test
        @DisplayName("N9 契约：resetPlayer 必须清补写登记（否则把删掉的已付行重新插回）")
        void resetClearsThePendingLedgerQueue() {
            stubUnsettledButCompleted(TODAY);
            when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, OTHER_DAY)).thenReturn(true);
            when(databaseManager.markDailyWordRewardClaimed(PLAYER_ID, TODAY)).thenReturn(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

                DailyChallengeManager manager = new DailyChallengeManager(plugin, wordManager);
                manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY);
                verify(databaseManager, times(1)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);

                manager.resetPlayer(PLAYER_ID);

                manager.hasPendingWordRewardForDate(PLAYER_ID, OTHER_DAY);
                verify(databaseManager, times(1)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);
            }
        }
    }

    // ==================================================================
    //  N10：paidButUnmarked 纯内存且全仓无清理点
    // ==================================================================

    @Nested
    @DisplayName("N10：MiscGUIListener.paidButUnmarked 的生命周期")
    class N10PaidButUnmarked {

        /**
         * <b>绿测试</b>：领取键含<b>日期</b> —— 因此「跨天自然失效」这个说法在
         * <b>逻辑</b>上成立（昨天的键今天不会再被 {@code contains} 命中）。
         *
         * <p>但它<b>不</b>成立在<b>内存</b>上：条目从不 {@code remove} / {@code clear}，
         * 跨天后它只是不再被查到，<b>仍然占着堆</b>。两者必须分开说 —— 这正是本条结论。
         * 缺清理点的事实由 {@link #hasNoCleanupPointAtAll()} 记录。
         */
        @Test
        @DisplayName("N10 契约：领取键按 玩家|挑战|日期 构造（逻辑上跨天失效）")
        void claimKeyIsDateScoped() {
            String body = methodBody("MiscGUIListener.java",
                    "private void claimBehavioralChallenge(Player player, int idx)");

            assertTrue(body.contains("paidButUnmarked.contains(claimKey)"),
                    "claimBehavioralChallenge 里找不到 paidButUnmarked.contains(claimKey) 喵 —— "
                            + "方法被重构的话请同步本 lint（否则下面的日期断言可能在空处成立）。");
            assertTrue(body.contains("LocalDate.now()"),
                    "领取键必须含「今天」喵。\n实测方法体：\n" + body.strip() + "\n"
                            + "去掉日期维度会让 WRITE_FAILED 的拒付在<b>跨天后永久生效</b> —— "
                            + "玩家第二天再也领不到同一挑战（键完全相同），"
                            + "从「防重复付款」翻成「永久拒付」。");
        }

        /**
         * <b>N10 已修（跨天裁剪）</b>：{@code paidButUnmarked} 的条目永不释放 —— 键含日期只让
         * 它在<b>逻辑</b>上跨天失效（昨天的键今天不会再被 {@code contains} 命中），条目照样
         * 占着堆。跑几个月的服量级是「玩家数 × 挑战数 × 天数」。
         *
         * <h2>修法与触发时机</h2>
         * 由 {@code prunePaidButUnmarkedForNewDay(today)} 在<b>跨天后的第一次领奖点击</b>整片
         * 裁掉非今日的键，并由 {@code paidButUnmarkedDay} 闸门保证每个自然日至多真正遍历一次。
         * 刻意<b>不</b>加 {@code PlayerQuitEvent} 按玩家清：quit 清会把「钱已到账、台账没写」
         * 的护栏在玩家重进后重新打开（二次付款）。
         *
         * <h2>本条断言的是「形状」，行为由下一条用例真跑</h2>
         * 源码 lint 只能证明「清理点存在且在正确的顺序上」；真正证明它清的是
         * {@link #crossDayPruneDropsStaleKeys()}。
         */
        @Test
        @DisplayName("N10 契约：paidButUnmarked 有跨天裁剪点，且裁剪排在 contains 判定之前")
        void hasACleanupPointTriggeredByTheClaimEntry() {
            String src = sourceOf("MiscGUIListener.java");

            assertTrue(src.contains("paidButUnmarked.removeIf"),
                    "paidButUnmarked 仍然没有任何清理点喵 —— 条目只会无界增长。\n"
                            + "注意：键含日期只让它在<b>逻辑</b>上跨天失效，条目照样占着内存。");

            String claimBody = methodBody("MiscGUIListener.java",
                    "private void claimBehavioralChallenge(Player player, int idx)");
            int pruneIdx = claimBody.indexOf("prunePaidButUnmarkedForNewDay(");
            int containsIdx = claimBody.indexOf("paidButUnmarked.contains(");
            assertTrue(pruneIdx > 0,
                    "领奖入口里找不到裁剪调用喵 —— 清理点存在但没人调用，等于没修。\n"
                            + "实测方法体：\n" + claimBody.strip());
            assertTrue(containsIdx > pruneIdx,
                    "裁剪必须排在 paidButUnmarked.contains 判定<b>之前</b>喵"
                            + "（裁剪@" + pruneIdx + "，contains@" + containsIdx + "）。\n"
                            + "顺序反了会让「今天的键」在本次点击里仍被上一轮的陈旧判定命中，"
                            + "而裁剪真正生效要等下一次点击 —— 中间那一次点击是错误的拒付。");

            String pruneBody = methodBody("MiscGUIListener.java",
                    "private void prunePaidButUnmarkedForNewDay(LocalDate today)");
            // stripComments 会把字符串字面量内容抹成空格（`endsWith(    + today)`），
            // 而这里判的恰恰是<b>字面量里有没有竖线</b>，所以必须读未剥的原文。
            // 取的是方法<b>体</b>（自左花括号起），方法 javadoc 不在其内 —— 不会自证。
            String rawPruneBody = rawMethodBody("MiscGUIListener.java",
                    "private void prunePaidButUnmarkedForNewDay(LocalDate today)");
            assertTrue(rawPruneBody.contains("endsWith(\"|\" + today)"),
                    "裁剪判据必须带<b>竖线</b>一起匹配（`endsWith(\"|\" + today)`）喵。\n"
                            + "只比日期串时，判据依赖「challengeId 不会以日期形态收尾」这个隐含前提；"
                            + "带上分隔符后，「最后一段等于今天」在结构上成立，"
                            + "`|2026-02-1` 与 `|2026-02-10` 这类前缀歧义不可能发生。\n"
                            + "实测方法体（原文）：\n" + rawPruneBody.strip());

            assertTrue(pruneBody.contains("paidButUnmarkedDay"),
                    "裁剪必须由「已裁剪到哪一天」的闸门守卫喵 —— 没有闸门就是每次领奖点击都"
                            + "遍历整个集合（O(集合大小) 挂在主线程的点击路径上）。");
        }

        /**
         * <b>N10 行为</b>：真正驱动裁剪 —— 造出「昨天的键 + 今天的键」，把时钟指到今天后
         * 断言昨日键被清掉、今日键保留；再把闸门与跨天各验一次。
         *
         * <p><b>为什么必须反射</b>：{@code paidButUnmarked} 与
         * {@code prunePaidButUnmarkedForNewDay} 都是 private 且本类在另一个包。行为断言比
         * 「源码里有 removeIf」强得多 —— 后者在判据写反（例如清掉今天的键）时照样绿。
         *
         * <p><b>变异判别</b>：把 {@code !key.endsWith("|" + today)} 改成
         * {@code false}（永不裁剪），或把判据里的 {@code !} 去掉（清反方向），本用例必红。
         */
        @Test
        @DisplayName("N10 行为：跨天裁剪清掉昨日键、保留今日键，同日重复调用不误删")
        void crossDayPruneDropsStaleKeys() throws Exception {
            com.letslearnenglish.minecraftplugin.listener.MiscGUIListener listener =
                    newListener();
            Set<String> keys = paidKeysOf(listener);

            String uuid = "00000000-0000-0000-0000-0000000000aa";
            String yesterdayKey = uuid + "|answer_10|2026-02-09";
            String todayKey = uuid + "|answer_10|2026-02-10";
            keys.add(yesterdayKey);
            keys.add(todayKey);

            LocalDate today = LocalDate.of(2026, 2, 10);
            pruneTo(listener, today);

            assertEquals(Set.of(todayKey), keys,
                    "跨天裁剪后集合里应<b>只剩今天</b>的键喵。\n"
                            + "多了 → 陈旧条目没被清（N10 未修）；少了 → 判据把今天的键也清掉了"
                            + "（防重复付款的护栏失效，钱可二次发放）。实测：" + keys);

            // 闸门：同一自然日再进来一次，不得重扫 / 不得误删刚写进去的今日键
            String laterTodayKey = uuid + "|combo_5|2026-02-10";
            keys.add(laterTodayKey);
            pruneTo(listener, today);
            assertTrue(keys.contains(laterTodayKey),
                    "同一自然日内的第二次调用把今日键删掉了喵 —— 闸门失效或判据方向错，"
                            + "护栏会在同一天里被自己拆掉。实测：" + keys);

            // 跨到下一天：所有 2026-02-10 的键都该被清掉（含刚加的 combo_5）
            pruneTo(listener, today.plusDays(1));
            assertTrue(keys.isEmpty(),
                    "跨天后旧日期的条目应被整片清掉喵，实测残留：" + keys);
        }

        /**
         * {@link #crossDayPruneDropsStaleKeys()} 的夹具：{@code MiscGUIListener} 的构造器
         * 只取 {@code DatabaseManager} 与 {@code Logger} 两个引用（无 I/O、无建表），
         * 故 mock 掉 {@code LetsLearnEnglish} 即可，不需要真库。
         */
        private com.letslearnenglish.minecraftplugin.listener.MiscGUIListener newListener() {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            when(plugin.getDatabaseManager()).thenReturn(mock(DatabaseManager.class));
            when(plugin.getLogger()).thenReturn(Logger.getLogger("N10Fixture"));
            return new com.letslearnenglish.minecraftplugin.listener.MiscGUIListener(plugin);
        }

        @SuppressWarnings("unchecked")
        private Set<String> paidKeysOf(
                com.letslearnenglish.minecraftplugin.listener.MiscGUIListener listener)
                throws Exception {
            java.lang.reflect.Field field = com.letslearnenglish.minecraftplugin.listener
                    .MiscGUIListener.class.getDeclaredField("paidButUnmarked");
            field.setAccessible(true);
            return (Set<String>) field.get(listener);
        }

        private void pruneTo(
                com.letslearnenglish.minecraftplugin.listener.MiscGUIListener listener,
                LocalDate day) throws Exception {
            java.lang.reflect.Method method = com.letslearnenglish.minecraftplugin.listener
                    .MiscGUIListener.class.getDeclaredMethod(
                            "prunePaidButUnmarkedForNewDay", LocalDate.class);
            method.setAccessible(true);
            method.invoke(listener, day);
        }

        /**
         * <b>绿测试</b>（反向门禁）：{@code paidButUnmarked} 的写入点<b>只有一个</b>，
         * 且必须落在 {@code case WRITE_FAILED} 分支内 —— 它是「防重复付款」的护栏，
         * 不是普通的已领集合。别把它当成可随手删除的死代码。
         */
        @Test
        @DisplayName("N10 反向门禁：paidButUnmarked.add 只出现在 WRITE_FAILED 分支")
        void addHappensOnlyOnWriteFailure() {
            String body = methodBody("MiscGUIListener.java",
                    "private void claimBehavioralChallenge(Player player, int idx)");

            int addIdx = body.indexOf("paidButUnmarked.add(");
            assertTrue(addIdx > 0,
                    "claimBehavioralChallenge 里找不到 paidButUnmarked.add(...) 喵 —— "
                            + "若护栏被移除，钱已到账但台账未落的格子会变成可重复领取。");
            int caseIdx = body.indexOf("case WRITE_FAILED");
            assertTrue(caseIdx > 0 && caseIdx < addIdx,
                    "paidButUnmarked.add 必须落在 switch 的 case WRITE_FAILED 分支内喵。\n"
                            + "实测 case WRITE_FAILED@" + caseIdx + "，add@" + addIdx + "\n"
                            + "它是「钱已到账、台账没写」的唯一进程内护栏，写在别处等于护栏失效。");
        }
    }

    // ==================================================================
    //  N12：stripe lock 内不得出现阻塞退避 —— 可达性用结构证明
    // ==================================================================

    @Nested
    @DisplayName("N12：GUI 领奖持锁期间不得进入带 Thread.sleep 的补发判定链")
    class N12StripeLockReachability {

        /**
         * <b>L1（主门禁）</b>：{@code MiscGUIListener} 里
         * {@code synchronized (getClaimStripeLock(...))} 的<b>块体</b>不得含
         * {@code readWordChallengeCompletionWithRetry} 或 {@code hasPendingWordReward}。
         *
         * <h2>为什么用 lint 而不是行为测试</h2>
         * 「主线程持锁睡觉」在单测里<b>不可观测</b>：没有真实 tick 循环，mock 的
         * {@code loadWordChallengeCompletion} 也不消费 {@code Thread.sleep} 的真实时长。
         * 把调用挪回锁内，所有行为断言依然全绿 —— 唯一会变红的是<b>代码形状</b>。
         * 与 {@code MidnightTaskThreadBoundaryTest} 同一层次判断。
         *
         * <h2>防「在空处通过」</h2>
         * 先断言<b>确实框出了一个 synchronized 块</b>、且块体里确实有
         * {@code claimBehavioralChallenge} —— 否则提取器一旦失效（锚点改名 / 格式变化），
         * 本 lint 会在空字符串上恒绿，等于门禁静默失效。
         */
        @Test
        @DisplayName("L1：stripe lock 块体内不得出现 sleep 链上游（且必须框到真实块体）")
        void stripeLockBodyHasNoSleepChain() {
            String src = sourceOf("MiscGUIListener.java");
            List<String> allBodies = synchronizedBlockBodies(src);

            assertFalse(allBodies.isEmpty(),
                    "MiscGUIListener 里找不到任何 synchronized 块喵 —— 要么锁被删了（"
                            + "双发保护失效），要么提取器的锚点与代码形状脱节（门禁静默失效）。");

            List<String> claimBodies = bodiesContaining(allBodies, "claimBehavioralChallenge");
            assertFalse(claimBodies.isEmpty(),
                    "没有任何 synchronized 块体包含 claimBehavioralChallenge 喵 —— "
                            + "提取器可能框错了块（例如框到了 synchronized 的表达式而不是块）。\n"
                            + "实测全部块体数=" + allBodies.size() + "\n"
                            + "若在空集合上做断言，本 lint 会恒绿。");

            // 对<b>全部</b> synchronized 块检查（不只领奖那一个）：领奖体本身也可能被
            // 抽成一个私有方法在锁内调用，那样检测点就落在别处。
            for (String body : allBodies) {
                for (String forbidden : FORBIDDEN_IN_LOCK) {
                    assertFalse(body.contains(forbidden),
                            "GUI 领奖相关的 synchronized 块体内出现了「" + forbidden + "」喵！\n"
                                    + "实测块体：\n" + body.strip() + "\n"
                                    + "那条链的上游是 readWordChallengeCompletionWithRetry"
                                    + "（3 次尝试、两次 Thread.sleep(50ms)）。一旦它在锁内，"
                                    + "命中重试时<b>同 stripe 的其它玩家会在主线程上一起等</b> ——"
                                    + "32 条带，100 人在线时平均每带 3 人。\n"
                                    + "注意 readWordChallengeCompletionWithRetry 的 javadoc 只承诺"
                                    + "「退避在 connectionLock 之外」，"
                                    + "<b>没有</b>承诺在 stripe lock 之外。");
                }
            }
        }

        /**
         * <b>L2（可达性证明 · 正向）</b>：{@code hasPendingWordRewardForDate} 在
         * {@code src/main} 里只出现在两个文件 —— {@code LetsLearnEnglish.java}（唯一调用点，
         * 午夜 rollover 主线程段）与 {@code DailyChallengeManager.java}（声明本身）。
         *
         * <p>这是「GUI 路径<b>根本走不到</b> sleep」这条结论的结构证据，而不是靠读代码推理。
         * 一旦有人在 GUI / 命令路径新增调用点，本 lint 立刻红 —— 那条新路径必须重新评估
         * 「主线程会不会睡觉」。
         */
        @Test
        @DisplayName("L2：hasPendingWordRewardForDate 只在午夜 rollover 被调用（GUI 路径不可达）")
        void settlementSweepIsReachableOnlyFromRollover() {
            Set<String> callSiteFiles = filesMentioning("hasPendingWordRewardForDate");

            assertTrue(callSiteFiles.contains("LetsLearnEnglish.java"),
                    "午夜 rollover 里不再调用 hasPendingWordRewardForDate 喵？"
                            + "实测有该名字的文件：" + callSiteFiles + "\n"
                            + "若补发链整体下线，请同步本 lint（否则可达性结论失去依据）。");
            assertTrue(callSiteFiles.contains("DailyChallengeManager.java"),
                    "DailyChallengeManager.java 里应当有该方法的<b>声明</b>喵，实测："
                            + callSiteFiles);

            Set<String> unexpected = new LinkedHashSet<>(callSiteFiles);
            unexpected.remove("LetsLearnEnglish.java");
            unexpected.remove("DailyChallengeManager.java");
            assertTrue(unexpected.isEmpty(),
                    "hasPendingWordRewardForDate 出现了新的调用点喵：" + unexpected + "\n"
                            + "该方法内部调用 readWordChallengeCompletionWithRetry（含 "
                            + "Thread.sleep 退避）。任何新调用点都必须先回答三个问题："
                            + "① 跑在哪条线程？② 是否持有 stripe lock？③ 是否在主线程？"
                            + "答不清就不要加 —— 这正是 N12 的原始形状。");
        }

        /**
         * <b>L3（可达性证明 · 反向）</b>：{@code readWordChallengeCompletionWithRetry}
         * 自己的调用点也<b>只有</b> {@code hasPendingWordRewardForDate}。
         *
         * <p>L2 + L3 合起来是完整的：带 sleep 的方法 → 唯一调用者 → 唯一调用点
         * （午夜 rollover）→ 该段不持有 stripe lock。任何一环被打破，L2 或 L3 会红。
         */
        @Test
        @DisplayName("L3：readWordChallengeCompletionWithRetry 只被 hasPendingWordRewardForDate 调用")
        void retryReadHasASingleCaller() {
            String mgr = sourceOf("DailyChallengeManager.java");

            int declaration = mgr.indexOf("DatabaseManager.WordChallengeCompletion"
                    + " readWordChallengeCompletionWithRetry(");
            assertTrue(declaration > 0,
                    "DailyChallengeManager 里找不到 readWordChallengeCompletionWithRetry 的声明喵 —— "
                            + "方法被改名 / 删除的话请同步本 lint。");

            String sweep = extractBody(mgr,
                    "public boolean hasPendingWordRewardForDate(UUID playerId, String date)");
            assertTrue(sweep.contains("readWordChallengeCompletionWithRetry"),
                    "hasPendingWordRewardForDate 不再调用 readWordChallengeCompletionWithRetry 喵，"
                            + "可达性链断了，请重新追踪 sleep 的新上游。");

            // 全仓（src/main）里该方法名的出现次数：声明 1 次 + 唯一调用 1 次 = 2。
            int occurrences = countOccurrencesInMainSource("readWordChallengeCompletionWithRetry(");
            assertEquals(2, occurrences,
                    "readWordChallengeCompletionWithRetry 在 src/main 里出现了 " + occurrences
                            + " 次（期望 2 = 1 次声明 + 1 次调用）喵。\n"
                            + "多出来的可能是新的调用点 —— 它会继承 Thread.sleep 退避，"
                            + "必须确认它不在主线程、也不在 stripe lock 内。");
        }

        /**
         * <b>变异自检（M1）</b>：把那条调用<b>注入</b>到 stripe lock 块体内，
         * 同一套检测谓词必须报出违规。
         *
         * <h2>为什么在内存里注入而不是改 src/main</h2>
         * 本角色只读源码（不许改 {@code src/main}，且正与 6 个并发 agent 并行），
         * 不能真的做仓库级变异。所以变异施加在<b>真实源码文本</b>的内存副本上 ——
         * 得到的仍是「真文件 + 一行注入」，比手写一段假样本更接近真实变异，且零副作用。
         *
         * <p>本用例绿 = L1 的检测逻辑确实能变红（不是恒绿的假门禁）。
         * 若有人把 L1 的 forbidden 名单清空，本用例也会红。
         */
        @Test
        @DisplayName("M1 变异自检：把调用注入锁内 → L1 的检测谓词必须报违规")
        void mutatingACallIntoTheLockIsDetected() {
            String src = sourceOf("MiscGUIListener.java");
            List<String> original = bodiesContaining(
                    synchronizedBlockBodies(src), "claimBehavioralChallenge");
            assertFalse(original.isEmpty(),
                    "变异自检前置：找不到含 claimBehavioralChallenge 的 synchronized 块体喵。");
            assertFalse(original.get(0).contains("hasPendingWordReward"),
                    "变异前置：原始块体本来就不该含 hasPendingWordReward 喵。");

            // 真实变异形状：在锁内、claimBehavioralChallenge 之<b>前</b>先问一次补发判定。
            String mutated = src.replace(
                    "claimBehavioralChallenge(player, idx);",
                    "plugin.getDailyChallengeManager()"
                            + ".hasPendingWordRewardForDate(player.getUniqueId(),"
                            + " LocalDate.now().toString());\n"
                            + "                claimBehavioralChallenge(player, idx);");
            assertFalse(mutated.equals(src),
                    "变异注入失败喵 —— 锚点「claimBehavioralChallenge(player, idx);」没找到，"
                            + "本自检失去意义（也说明 L1 的锚点可能已漂移）。");

            List<String> mutatedBodies = bodiesContaining(
                    synchronizedBlockBodies(mutated), "claimBehavioralChallenge");
            assertFalse(mutatedBodies.isEmpty(), "变异后提取不到领奖锁块体喵。");
            String mutatedBody = mutatedBodies.get(0);

            assertTrue(mutatedBody.contains("hasPendingWordReward"),
                    "变异没有落进锁内喵 —— 自检无效。实测变异块体：\n" + mutatedBody.strip());

            // L1 用的正是这个谓词；它必须在这里为 true（= L1 会报违规）。
            boolean detected = false;
            for (String forbidden : FORBIDDEN_IN_LOCK) {
                detected |= mutatedBody.contains(forbidden);
            }
            assertTrue(detected,
                    "L1 的检测谓词对这份变异<b>没有报违规</b>喵 —— 说明 lint 是恒绿的假门禁。\n"
                            + "实测变异块体：\n" + mutatedBody.strip());
        }
    }

    // ==================================================================
    //  工具：源码定位 / 剥注释 / 花括号配对 / synchronized 块提取
    //  （本类独立实现一份，理由同 ServerToggleLintsTest：失效模式要互相独立）
    // ==================================================================

    private String methodBody(String fileName, String signature) {
        return extractBody(sourceOf(fileName), signature);
    }

    /**
     * 未剥注释的<b>方法体</b>（自左花括号起）。
     *
     * <p><b>为什么需要它</b>：{@link #sourceOf} 走 {@link #stripComments}，把字符串字面量的
     * <b>内容</b>抹成空格 —— 于是 {@code endsWith("|" + today)} 在剥过的源码里变成
     * {@code endsWith(    + today)}，任何「字面量里有没有某个字符」的断言都会恒红。
     * 反过来直接读<b>整份原文</b>又会让断言被 javadoc 里的同名提及满足（自证桩）。
     *
     * <p>所以这里只做一件事：原文 + 方法体范围。javadoc 在签名之前，天然被排除。
     */
    private String rawMethodBody(String fileName, String signature) {
        return extractBody(rawSourceOf(fileName), signature);
    }

    /** 未剥注释的整份源码。仅用于必须看见字面量内容的断言。 */
    private String rawSourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        for (Path p : allMainSources()) {
            if (p.getFileName().toString().equals(fileName)) {
                hits.add(p);
            }
        }
        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动 / 改名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (hits.size() > 1) {
            fail("源码树里有 " + hits.size() + " 个 " + fileName + "，无法唯一定位喵。");
        }
        try {
            return Files.readString(hits.get(0), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    private Path srcRoot() {
        Path direct = Paths.get("src", "main", "java");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            Path candidate = p.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            p = p.getParent();
        }
        return fail("找不到 src/main/java —— lint 无法读源码；"
                + " workingDirectory=" + System.getProperty("user.dir"));
    }

    private List<Path> allMainSources() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(files::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }
        return files;
    }

    private String sourceOf(String fileName) {
        List<Path> hits = new ArrayList<>();
        for (Path p : allMainSources()) {
            if (p.getFileName().toString().equals(fileName)) {
                hits.add(p);
            }
        }
        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动 / 改名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (hits.size() > 1) {
            fail("源码树里有 " + hits.size() + " 个 " + fileName + "，无法唯一定位喵。");
        }
        try {
            // 剥注释再匹配：本仓 lint 因「匹配到注释」假阳性过 4 次以上。
            return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /** 哪些文件名里出现了 {@code needle}（已剥注释）。声明与调用都算 —— 由调用方区分。 */
    private Set<String> filesMentioning(String needle) {
        Set<String> hits = new LinkedHashSet<>();
        for (Path p : allMainSources()) {
            try {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (src.contains(needle + "(")) {
                    hits.add(p.getFileName().toString());
                }
            } catch (IOException e) {
                fail("读取 " + p.toAbsolutePath() + " 失败喵：" + e.getMessage());
            }
        }
        return hits;
    }

    private int countOccurrencesInMainSource(String needle) {
        int total = 0;
        for (Path p : allMainSources()) {
            try {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                int idx = 0;
                while ((idx = src.indexOf(needle, idx)) >= 0) {
                    total++;
                    idx += needle.length();
                }
            } catch (IOException e) {
                fail("读取 " + p.toAbsolutePath() + " 失败喵：" + e.getMessage());
            }
        }
        return total;
    }

    /**
     * 抽出文件里每个 {@code synchronized (expr) { ... }} 的<b>块体</b>（不含花括号）。
     *
     * <p>不能从 {@code synchronized} 往后找第一个 {@code {} —— 表达式里可能自带括号
     * （本仓的形状是 {@code getClaimStripeLock(player.getUniqueId())}）。故作括号配对，
     * 并要求右括号与左花括号之间只有空白。
     */
    private static List<String> synchronizedBlockBodies(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int s = src.indexOf("synchronized", i);
            if (s < 0) {
                break;
            }
            i = s + "synchronized".length();
            int paren = src.indexOf('(', i);
            if (paren < 0) {
                break;
            }
            int parenClose = findMatching(src, paren, '(', ')');
            if (parenClose < 0) {
                break;
            }
            int brace = src.indexOf('{', parenClose);
            if (brace < 0) {
                break;
            }
            if (!src.substring(parenClose + 1, brace).isBlank()) {
                i = brace; // 不是 "synchronized(...) {" 的形状，继续往后找
                continue;
            }
            int end = findBlockEnd(src, brace);
            if (end < 0) {
                break;
            }
            out.add(src.substring(brace + 1, end));
            i = end;
        }
        return out;
    }

    /** 从给出的块体里挑出包含 {@code needle} 的那些（无匹配时返回空列表，由调用方断言）。 */
    private static List<String> bodiesContaining(List<String> bodies, String needle) {
        List<String> out = new ArrayList<>();
        for (String body : bodies) {
            if (body.contains(needle)) {
                out.add(body);
            }
        }
        return out;
    }

    /** 从 {@code open}（必须指向 {@code openCh}）找配对的 {@code closeCh}，返回下标或 -1。 */
    private static int findMatching(String s, int open, char openCh, char closeCh) {
        if (open < 0 || open >= s.length() || s.charAt(open) != openCh) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == openCh) {
                depth++;
            } else if (c == closeCh) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 从 {@code open}（必须指向 '{'）找到配对的 '}'，返回其下标；不配对返回 -1。 */
    private static int findBlockEnd(String s, int open) {
        return findMatching(s, open, '{', '}');
    }

    /** 按方法签名截出方法体（含花括号）。签名必须唯一出现，且其后紧跟方法体。 */
    private static String extractBody(String strippedSource, String signature) {
        int sigIndex = strippedSource.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名 / 改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵。");
        }
        int bodyStart = strippedSource.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            fail("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        String between = strippedSource.substring(sigIndex + signature.length(), bodyStart);
        if (between.indexOf(';') >= 0) {
            fail("方法签名「" + signature + "」与左花括号之间出现了分号，"
                    + "匹配到的可能不是方法体喵：" + between.strip());
        }
        int close = findBlockEnd(strippedSource, bodyStart);
        if (close < 0) {
            fail("方法签名「" + signature + "」的花括号没有配对结束喵 —— 源码可能不完整。");
        }
        return strippedSource.substring(bodyStart, close + 1);
    }

    /** 剥掉 Java 注释并把字符串 / 字符字面量内容抹成空格（保留换行，偏移不错位）。 */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }
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

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }
}
