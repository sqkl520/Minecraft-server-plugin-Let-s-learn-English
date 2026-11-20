package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.word.WordModes;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.data.repository.DailyChallengeRepository;
import com.letslearnenglish.minecraftplugin.gui.DailyChallengeGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import com.letslearnenglish.minecraftplugin.gui.WordBankSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Listener for Privacy Settings, Word Bank Select, and Learning Calendar GUI interactions.
 */
public class MiscGUIListener implements Listener {

    private final LetsLearnEnglish plugin;

    /**
     * round4 F2 / EH-12-02：领取台账的三态写入口。
     *
     * <p>为什么本类直接持仓储而不是走 {@code DailyChallengeManager.claimReward}：后者的
     * 返回类型是 {@code double}，把「护栏不满足」与「SQL 写失败」折叠成同一个 {@code 0}，
     * 调用方<b>无法</b>分流——这正是本条缺陷的根因。仓储构造无副作用（只存两个引用），
     * 与 {@code ReportGenerator} 内自建仓储同一做法。
     */
    private final DailyChallengeRepository claimRepository;

    /**
     * 领奖慢操作告警阈值：<b>50 ms = 一个 tick</b>。
     *
     * <p><b>为什么是 50 而不是别的数</b>：行为挑战领取跑在<b>主线程</b>（{@code InventoryClickEvent}
     * 处理器），而主线程每 tick 只有 50 ms 预算。一次领奖占掉超过一整个 tick，TPS 就会掉——
     * 所以 50 ms 本身就是「玩家可能感觉到卡顿」的物理边界，而不是随手挑的魔法数。
     * 稳态实测约 1–10 ms（三条 JDBC 语句都在本地/局域网库上，且已在 stripe lock 内串行化），
     * 因此常规领奖<b>不会</b>刷这条日志；只有午夜窗口（跨天回填与领奖竞争
     * {@code connectionLock}）或 MySQL 远程慢查询才会触发。
     *
     * <p><b>为什么这条观测值得存在</b>：本仓库反复出现「注释里写着一个已知风险，但线上没有任何
     * 运行时证据」的形态——诊断为「午夜整点卡一下」时服主只能猜。有了这条 WARNING，日志直接
     * 说明<b>哪个玩家、哪个挑战、等/用了多久</b>，把注释里的风险变成可观测的事实。
     * 纯观测，零正确性风险：只读 {@link System#nanoTime()} 与写一行日志。
     */
    private static final long CLAIM_SLOW_THRESHOLD_MS = 50L;

    /**
     * 仅供上面那条慢操作 WARNING 使用的「本次领奖命中的挑战 id」。
     *
     * <p><b>为什么需要这个字段</b>：挑战 id（{@code cp.type.id}）只有在
     * {@link #claimBehavioralChallenge} 里查过 {@code getTodayChallenges} 之后才知道，
     * 而那一次查询本身就在 stripe lock 内；日志却必须留在锁外（锁内做 IO 会延长持锁）。
     * 于是把 id 在锁内<b>记到字段上</b>（一次引用赋值，纳秒级，不构成新的 IO），
     * 出锁后再连同耗时一起写日志。
     *
     * <p><b>线程与失效代价（刻意不追求绝对正确）</b>：正常路径上写与读都在<b>主线程</b>
     * （{@code InventoryClickEvent} 处理器）的同一个 {@link #handleDailyChallenge}
     * 调用栈内 —— 锁前清空、锁内赋值、出锁立刻读，所以常规情况下值一定是本次的。
     * 但两个<b>不同玩家</b>并发放行时，他们可能落在<b>不同</b> stripe 上，锁并不能把他们
     * 串行化；若真有第三方插件在异步线程里伪造 {@code InventoryClickEvent}，这两个
     * 调用就可能交错，日志里偶尔会写错一个挑战 id。
     *
     * <p><b>为什么不为此加 volatile / ThreadLocal</b>：这条字段唯一的用途是给一行
     * WARNING 提供上下文，最坏后果是某条慢操作日志的 {@code challenge=} 指到了同服另一个
     * 玩家当次点的挑战（耗时、玩家名、下标仍然都是真的）。为消除这么小的诊断噪声而引入
     * 同步或线程局部存储，反而给主线程热点路径添了新东西 —— 与「纯观测、零正确性风险」
     * 的定位不符。这是个有意接受的取舍，不是遗漏。
     */
    private String lastClaimChallengeId;

    /**
     * R-02: behavioral-claim stripe locks. A fixed array of 32 monitors —
     * indexed by UUID hash — so the full "check → deposit → mark" critical
     * section runs serialized per player with no growing map and no cleanup.
     *
     * <p><b>R15（wave3 后继审计 · 并发专场）—— 这里原先是第二个独立锁数组。</b>
     * {@code DailyChallengeManager} 与 {@code MiscGUIListener} 各持一份
     * {@code new Object[32]}，而 {@code getClaimStripeLock} 的实现逐字相同。锁的有效性
     * 完全依赖「所有竞争方拿的是<b>同一个</b> monitor」这个前提 —— 两个不同的数组
     * 意味着两处的 {@code synchronized} <b>互不排斥</b>。
     *
     * <p>后果：R-02 的注释写着「两次快速点击同一按钮绝不会交错出双发」，但那只在
     * <b>同一类内部</b>成立。挑战领奖有两个入口 —— GUI 点击（本类
     * {@code claimBehavioralChallenge}）与命令/其它路径（{@code DailyChallengeManager}
     * 的 claim 方法）。两者并发时各自持不同的锁，`check → deposit → mark` 仍可交错，
     * 双发保护形同虚设。
     *
     * <p>现在委托给 {@code DailyChallengeManager} 的同一份锁数组：单一真相源，
     * 结构上不可能再分叉。
     */
    private Object[] claimStripeLocks() {
        return plugin.getDailyChallengeManager().getClaimStripeLocks();
    }

    /** R15：取本玩家在本进程内的唯一领奖 stripe monitor（与 DailyChallengeManager 共用）。 */
    private Object getClaimStripeLock(UUID playerId) {
        Object[] locks = claimStripeLocks();
        return locks[(playerId.hashCode() & 0x7fffffff) % locks.length];
    }

    /**
     * round4 F2 / EH-12-02：**已付款但台账写失败**的领取键（{@code uuid|challengeId|date}）。
     *
     * <p>为什么必须有它：{@code markClaimedOrFail} 返回 {@code WRITE_FAILED} 时磁盘上的
     * {@code claimed} 仍是 0，{@code getTodayChallenges} 下次仍报"未领取"，{@code cp.claimed}
     * 仍为 false —— 只要不禁，玩家再点一次就会<b>二次付款</b>。GUI 重开与否都不改变这一点。
     * 故在本进程内记住这一格，任何后续点击直接拒绝付款并提示联系管理员。
     *
     * <p>有界性与生命周期：进程重启即清空（此前的欠账由 SEVERE 日志交给运维对账，本集合
     * 不承担持久化责任）。并发实现与 {@code dailyWordStateCache} 同款。
     *
     * <p><b>N10（跨天裁剪）：本集合过去只增不删。</b>键含日期只让它在<b>逻辑</b>上跨天失效
     * （昨天的键今天不会再被 {@code contains} 命中），但条目<b>永不释放</b>：每天每个玩家
     * 每个挑战都可能新增一条，跑几个月的服量级是「玩家数 × 挑战数 × 天数」。慢性 DB 写故障
     * （{@code WRITE_FAILED} 反复命中）+ Vault 正常这个组合下它会无界增长。
     * 现在由 {@link #prunePaidButUnmarkedForNewDay} 在跨天后的第一次领奖点击时整片裁掉
     * 昨日及更早的条目（判据与触发时机的论证见该方法）。
     *
     * <p><b>为什么不在 {@code PlayerQuitEvent} 里按玩家清</b>：那会让守卫<i>反向</i>漏钱 ——
     * 本集合保护的是「钱已到账、台账没写成」的格子。玩家下线重进后 {@code claimed} 仍是 0、
     * 按钮仍可点，quit 时清掉他的键等于把<b>二次付款</b>重新打开（同一格里再付一次）。
     * 只有「跨天」这个时间维度是真正无用的（昨天的挑战今天根本不在列表里，
     * 见 {@code getTodayChallenges} 只读当日行），所以裁剪只能按日期做。
     */
    private final Set<String> paidButUnmarked = ConcurrentHashMap.newKeySet();

    /**
     * {@link #paidButUnmarked} 当前<b>已裁剪到</b>的自然日（口径与集合内的日期后缀一致）。
     *
     * <p>{@code null} = 从未裁剪过。写入/读取都只在主线程的领奖点击链上，但集合本身是并发
     * 实现（第三方插件可能在别的线程伪造 {@code InventoryClickEvent}），故用 {@code volatile}
     * 而不是裸字段 —— 只为一个「是否已裁剪」的判定付出一次可见性保证。
     */
    private volatile LocalDate paidButUnmarkedDay;

    /**
     * N10：跨天裁剪 —— 丢掉所有<b>不属于</b> {@code today} 的领取键。
     *
     * <h2>判据为什么是 {@code endsWith("|" + today)}</h2>
     * 键的构造是 {@code uuid|challengeId|date}（见 {@code claimBehavioralChallenge}），日期
     * 是<b>最后一段</b>。{@code LocalDate.toString()} 恒定是 10 字符零填充的 {@code yyyy-MM-dd}，
     * 所以「以 {@code |yyyy-MM-dd} 结尾」与「最后一段等于今天」逐字等价。刻意连<b>竖线</b>
     * 一起匹配，而不是只匹配 {@code endsWith(today)}：只比日期串时，判据依赖「任何
     * {@code challengeId} 都不会以日期形态收尾」这个隐含前提；带上分隔符之后，
     * 前缀歧义（{@code |2026-02-1} 与 {@code |2026-02-10}）在<b>结构上</b>不可能发生。
     *
     * <h2>触发时机为什么是「跨天后的第一次领奖点击」</h2>
     * <ul>
     *   <li><b>不在每次 add 前扫</b>：{@code removeIf} 是 O(集合大小) 的遍历，而本方法会在
     *       同一自然日里被反复调用（每次领奖点击一次）。用 {@link #paidButUnmarkedDay}
     *       做闸门后，实际扫描<b>每个自然日至多一次</b>，其余调用只付一次 volatile 读 +
     *       一次 {@code LocalDate.equals}。</li>
     *   <li><b>不挂在 {@code WRITE_FAILED} 分支</b>：集合只有 {@code WRITE_FAILED} 才写入，
     *       所以「只在 add 时裁剪」意味着「DB 一直正常时永远不裁剪」。挂在点击入口则让
     *       裁剪的触发面覆盖所有领奖点击，不依赖写失败是否再发生。</li>
     *   <li><b>不在 {@code add} 里判 contains 之后</b>：裁剪必须在读之前（{@code contains}）
     *       完成才自然 —— 顺序上「先裁 → 再判 → 后加」，同一自然日内的条目一条都不会被误删
     *       （今天的键都带今天的后缀）。</li>
     * </ul>
     *
     * <h2>能力边界（如实记录，别把「有界」读成「准点」）</h2>
     * 触发面是<b>真实领奖点击</b>，且调用点在
     * {@code if (!cp.completed || cp.claimed) return;} <b>之后</b> —— 所以本方法
     * <b>不是</b>一个午夜定时任务：如果一个服从此再没有任何人点领奖，最后一天的条目会一直留着。
     * 那仍然是有界状态（每天的新增都需要有人领奖才会发生，同一个前提），本缺陷要消灭的是
     * 「<b>无界</b>增长」。若将来要求「即使无人领奖也准点释放」，那应该挂在
     * {@code LetsLearnEnglish} 的午夜 rollover 上 —— 那是另一个改动，不在本条范围。
     *
     * <h2>并发与最坏情况</h2>
     * {@code ConcurrentHashMap.newKeySet().removeIf(...)} 是并发安全的（逐键
     * {@code remove}，不抛 {@code ConcurrentModificationException}）。两个线程同日同时进来
     * 会重复扫一次 —— 幂等，只是多花一次遍历；跨午夜时若两个线程分别带着 day1 / day2
     * 进来，最坏后果是「一条 day1 的键多留一天」，仍在「每天至多一次扫描」的有界范围内。
     *
     * @param today 本次领奖点击看到的「今天」；{@code null} 视为不可判定，直接跳过裁剪
     */
    private void prunePaidButUnmarkedForNewDay(LocalDate today) {
        if (today == null || today.equals(paidButUnmarkedDay)) {
            return;
        }
        paidButUnmarked.removeIf(key -> key == null || !key.endsWith("|" + today));
        paidButUnmarkedDay = today;
    }

    public MiscGUIListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        // 仓储只存 (DatabaseManager, Logger) 两个引用，无 I/O、无建表；DatabaseManager
        // 的 initialize() 已在插件装配阶段完成（本监听器在 :946 构造，晚于 DB 初始化）。
        this.claimRepository = new DailyChallengeRepository(
                plugin.getDatabaseManager(), plugin.getLogger());
        // R15：锁数组改为向 DailyChallengeManager 借用（见 claimStripeLocks() 的注释），
        // 本类不再自建第二份 —— 两个数组 = 两把锁 = 保护失效。
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // ── R5-3 判定顺序（四条铁律，顺序不可调整）────────────────────────────
        // 1) 先判窗外：getClickedInventory() 只在点到界面之外时为 null。
        if (event.getClickedInventory() == null) return;

        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        // ── R5-7 前置守卫：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）与双击收集
        //（COLLECT_TO_CURSOR）必须放在下面的 rawSlot 越界 return <b>之前</b>——这两类
        // action 的 rawSlot 落在下半区，否则会被那个 return 吞掉、永不执行。
        //
        // 为什么必须拦：原版会为这两类 action 在<b>整个 view</b> 里找落点，包括上半区。
        // 上半区任何空格都会成为落点，物品塞进去后本类的「上半区点击一律 cancel」
        // 又会把它锁死（玩家点不出来、shift-click 也回不去，关界面即丢）。COLLECT_TO_CURSOR
        // 还会从整个 view（含上半区展示物）收集同材质物品，把提示物扫进光标。
        //
        // 裁决：对<b>全部 5 个 key</b> 生效（不是只对 PROFILE_SETTINGS）。理由：
        //   - 破坏性与「这一页当前有没有空槽」无关，只与「原版有没有落点」有关。当前
        //     PROFILE_SETTINGS 的 slot 10/12 是空槽（本次已在 ProfileGUI 侧用 fillEmpty
        //     补上），但其余四页的满填充是靠各 GUI 自己逐个 setItem 维持的、没有任何
        //     结构性保证，未来布局改动随时可能漏格；按 key 列白名单等于把「今天恰好没
        //     空格」写成不变量，而 shift-click 对任何上半区都是纯破坏操作（本类没有任何
        //     合法的「把物品移进上半区」语义），全拦没有功能损失。
        //   - 普通下半区点击仍然一步不拦（R5-3 铁律 2 的「玩家能整理背包」语义不变）。
        // 归属判定仍先走 resolveOwnedView：非本插件界面（别的插件的 GUI）绝不被误 cancel。
        if (rawSlot >= topSize
                && (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || event.getAction() == InventoryAction.COLLECT_TO_CURSOR)) {
            if (resolveOwnedView(event.getView()) != null) {
                event.setCancelled(true);
            }
            return;
        }

        // 2) 再判下半区（玩家背包）：rawSlot 落在 top 之外一律不处理、也不 cancel，
        //    玩家开着 GUI 仍能正常整理背包。
        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 3) 然后才做身份判定：只认 holder（结构身份）。
        GuiViewKey key = resolveOwnedView(event.getView());
        if (key == null) return;

        // 4) 只有「本类辖管界面的上半区点击」才 cancel —— 绝不允许只判 holder 就 cancel
        //    （event.getInventory() 恒为上半区，与玩家实际点击的位置无关）。
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        switch (key) {
            case PROFILE_SETTINGS -> handlePrivacySettings(player, slot);
            case WORD_BANK -> handleWordBankSelect(player, slot);
            case LEARNING_CALENDAR -> handleLearningCalendar(player, slot);
            case DAILY_CHALLENGE -> handleDailyChallenge(player, slot);
            case CHALLENGE_COMPLETE -> handleChallengeComplete(player, slot);
            // 不可达：resolveOwnedView 只返回以上五个 key（其余一律 null）。
            default -> {
            }
        }
    }

    /**
     * R5-11：解析「本监听器负责的界面」的<b>点击</b>归属 —— <b>只认 holder</b>。
     *
     * <p><b>PROFILE_SETTINGS 的属主纪律（保持）</b>：本类<b>继续</b>是隐私设置页的唯一
     * 属主，{@code SocialListener#resolveSocialKey} 的 switch 里没有该分支且显式排除它。
     * R5-11 删掉标题兜底后这条纪律更重要了 —— 判定已经是纯 holder，只要 holder 命中
     * {@code PROFILE_SETTINGS}，本类就必须接住，否则五个开关与返回键会全部变成死键。
     * 移交前提仍是「SocialListener 加 case + 本类删 key」同一提交，不可只做一半。
     *
     * @param view 事件界面，可为 null
     * @return 本类辖管的 key；不归本类管时返回 null
     */
    private GuiViewKey resolveOwnedView(InventoryView view) {
        GuiViewKey byHolder = GuiIdentity.resolve(view);
        // 结构身份压倒文案：holder 说了算（null 就是「不是本类界面」）。
        return isTrackedKey(byHolder) ? byHolder : null;
    }

    /**
     * R5-11：解析「本监听器负责的界面」的<b>拖拽</b>归属 —— 与
     * {@link #resolveOwnedView(InventoryView)} 判定口径<b>完全一致</b>（同一个
     * {@link #isTrackedKey}），区别只在返回值形态：拖拽守卫只需 boolean。
     *
     * @param view 事件界面，可为 null
     * @return true 表示本类应守卫该界面
     */
    private boolean isTrackedView(InventoryView view) {
        return isTrackedKey(GuiIdentity.resolve(view));
    }

    /**
     * R5-11：本监听器辖下的五类屏幕（null 安全：{@code null} 不是其中任何一类）。
     *
     * <p>{@link GuiViewKey#PROFILE_SETTINGS} 的属主裁决见
     * {@link #resolveOwnedView(InventoryView)} 的 Javadoc。
     */
    private static boolean isTrackedKey(GuiViewKey key) {
        return key == GuiViewKey.PROFILE_SETTINGS
                || key == GuiViewKey.WORD_BANK
                || key == GuiViewKey.LEARNING_CALENDAR
                || key == GuiViewKey.DAILY_CHALLENGE
                || key == GuiViewKey.CHALLENGE_COMPLETE;
    }

    private void handlePrivacySettings(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            // R-86b：设置页返回走返回栈 —— SocialListener 在进入设置前压入「本人主页」帧，
            // 这里弹回；无栈时 fallback 也开本人主页（防无动作）。与其它返回键语义一致。
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, player.getUniqueId()));
            backAction.run();
        } else if (slot == 11 || slot == 13 || slot == 14 || slot == 15 || slot == 16) {
            SoundManager.playSound(player, "menu-click");
            PlayerDataStore store = plugin.getPlayerDataStore();
            // L-写路径收口：缓存缺失说明 join 时读库失败（或热重载 clear 后未重建）。
            // 此时直接 getProfileSettings 会 computeIfAbsent 合成一份「全公开」默认值
            // （showResults / showInSquare 均为 true），玩家只点一个开关，
            // saveProfileSettings 就整对象 UPSERT，把 show_in_square / show_results
            // 永久写回 true —— 玩家关掉的隐私开关不可撤销地丢失。
            // 因此先尝试同步加载真实值；DB 仍不可用就不允许改（fail-closed，提示后返回）。
            if (store.getCachedProfileSettings(player.getUniqueId()) == null) {
                if (!store.loadProfileSettings(player.getUniqueId())) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(
                            player, "profile.settings-load-failed"));
                    return;
                }
            }
            PlayerDataStore.ProfileSettings ps = store.getProfileSettings(player.getUniqueId());
            switch (slot) {
                case 11 -> ps.setShowAnswers(!ps.isShowAnswers());
                case 13 -> ps.setShowResults(!ps.isShowResults());
                // R1/R4：提醒总开关（发送侧门由编排者接线：LetsLearnEnglish 三处 + ReviewScheduler）
                case 14 -> ps.setRemindersEnabled(!ps.isRemindersEnabled());
                case 15 -> ps.setShowInSquare(!ps.isShowInSquare());
                // Wave 1 3A-4 补建：per-player 音效开关（SoundManager.isPlayerSoundEnabled 门已接线）
                case 16 -> ps.setSoundsEnabled(!ps.isSoundsEnabled());
            }
            store.saveProfileSettings(player.getUniqueId());
            new ProfileGUI(plugin).openSettings(player);
        }
    }

    private void handleWordBankSelect(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }
        if (slot < WordBankSelectGUI.BANK_SLOT_START
                || slot >= WordBankSelectGUI.BANK_SLOT_START + WordBankSelectGUI.BANK_SLOT_COUNT) {
            return;
        }
        // MC-2 word-system 门（plan-menu-config 1.(b)："WordBankSelectGUI 不可达"）：
        // 关闭时词库点击整体拒绝——正常路径由主菜单按钮隐藏 + /le word 命令门覆盖，
        // 本处是僵尸 GUI（关开关前已打开）与边缘入口的兜底防线（S-F 流已记录
        // "入口层门由 S-G 落盘"）。空白/填充格点击仍零行为（上方范围检查先行）。
        if (!plugin.getConfigManager().isWordSystemEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "module.disabled"));
            return;
        }
        // R-53 fan-in：与 GUI 同序（getAvailableDifficulties 的 TreeSet 快照）、同截断。
        List<String> banks = new ArrayList<>(plugin.getWordManager().getAvailableDifficulties());
        boolean hasMore = banks.size() > WordBankSelectGUI.BANK_SLOT_COUNT;
        int moreSlot = WordBankSelectGUI.BANK_SLOT_START + WordBankSelectGUI.BANK_SLOT_COUNT - 1;
        // R-49/R-53：尾槽"更多词库…"提示点击当 no-op（仅播音），不反算打开被隐藏的词库
        if (hasMore && slot == moreSlot) {
            SoundManager.playSound(player, "menu-click");
            return;
        }
        int shownCount = hasMore ? WordBankSelectGUI.BANK_SLOT_COUNT - 1 : banks.size();
        int index = slot - WordBankSelectGUI.BANK_SLOT_START;
        if (index < 0 || index >= shownCount) return;
        SoundManager.playSound(player, "menu-click");
        String difficulty = banks.get(index);
        // C1-N1 收口（R2-M4 同族，存量守卫缺口）：决斗中不开新答题 —— 决斗分支
        // 优先吞全部聊天（ChatAnswerListener），答题输入静默超时判错。守卫先于
        // 对话判，与 /le dialogue 命令入口的守卫顺序一致。
        var duelManager = plugin.getDuelManager();
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.finish-duel-first"));
            return;
        }
        // R-66 反向（P2 编排者 fan-in）：AI 对话进行中不开新答题（与 /le word 入口对称守卫）。
        if (plugin.getAIChatManager().hasActiveConversation(player)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.chat-active"));
            return;
        }
        // MC-3（F2-05 收口）：默认模式从私有常量改为 WordModes.firstEnabledMode ——
        // Wave 2 裁决：默认模式解析优先 choice（FEAT-MODE-01）；choice 被关时按
        // spelling→fill_blank 回落；三模式全关时拒绝。
        // （word.no-modes-enabled），不再回落到可能已关闭的 choice。
        // 顺序对齐 /le word 命令路径（模式门先于引导接管）：全关时不落进 step==1 教程。
        String mode = WordModes.firstEnabledMode(plugin.getConfigManager());
        if (mode == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.no-modes-enabled"));
            return;
        }
        // 域 1 新手引导拦截：step==1 的"首次答题"改开 3 词迷你会话（接管开局则直接返回）。
        if (plugin.getOnboardingManager().interceptQuizStart(player, difficulty)) {
            return;
        }
        try {
            var session = plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
            // Wave 1 fan-in（S-F 上报纵深）：word 门关闭时 startQuiz 返回 null 已发
            // module.disabled——此处不再开 GUI / 发 session-start（上方兜底门之外的第二道防线）。
            // R1-M1/M2：session-in-progress / practice-in-progress 的 null 同样已由
            // 守卫内发提示，此处一并静默 return。
            if (session == null) {
                return;
            }
        } catch (IllegalStateException e) {
            // R11-M2（含 R8-L2 合并）：空词池窗口 selectWordsForSession 抛 ISE 原先冒泡
            // 出事件处理器——玩家零反馈且困在原界面。逐字对齐命令路径
            // （LearnCommand.startWordQuiz 的 catch）：界面不关，玩家可改点其它词库。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.no-words-available"));
            return;
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message", e.getMessage()));
            return;
        }
        new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
        // PF-ONB-03（Phase 3 规划期发现，编排者即修）：F2-13 只修了命令路径，词库选择
        // 这条新手主路径此前仍把英文标识符（beginner/choice）透传给中文玩家；
        // MC-3（PF-MC-11 收口）：本地化改走 WordModes 单一实现（本类私有副本删除）。
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "word.session-start",
                "difficulty", WordModes.localizeDifficulty(plugin.getMessageUtil(), player, difficulty),
                "mode", WordModes.localizeMode(plugin.getMessageUtil(), player, mode)));
    }

    private void handleLearningCalendar(Player player, int slot) {
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
        }
    }

    private void handleDailyChallenge(Player player, int slot) {
        // MC-2 模块门：daily-challenges.enabled=false 时领取分支整体拒绝
        // （僵尸 GUI 的迟到点击不进任何写路径；点读式门，/lea reload 即时生效）。
        if (!plugin.getConfigManager().isDailyChallengesEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "module.disabled"));
            return;
        }
        if (slot == 8) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
            return;
        }
        // Claim buttons：槽位查位映射（MC-5/F1-12）——与 DailyChallengeGUI.CHALLENGE_SLOTS
        // 共用同一份槽位真相（WrongBook 模式），不再手写 (slot-11)/2 魔法反算。
        // 索引语义与渲染侧一致：列表第 i 项渲染在 CHALLENGE_SLOTS[i]。
        int idx = Arrays.binarySearch(DailyChallengeGUI.CHALLENGE_SLOTS, slot);
        if (idx >= 0) {
            SoundManager.playSound(player, "menu-click");
            // ── 慢操作观测：计时起点必须在 synchronized <b>之前</b> ──────────────
            // 要观测的是「等锁 + 锁内执行」的总时长，不只是锁内执行——因为「等锁」
            // 正是玩家实际感受到的那一段：另一个线程（午夜回填、命令路径、同 stripe
            // 的其它玩家）持着 connectionLock 或本 stripe 时，本线程就在下面的
            // synchronized 上排队。从锁内开始计时会把这段排队整个漏掉，
            // 而它恰是「午夜整点卡一下」最可能的成因。
            //
            // 用 System.nanoTime() 而非 currentTimeMillis()：后者是墙上时钟，
            // 会跟着 NTP 校时 / 手动改时间往回跳，算出负数或离谱的耗时——
            // 测时长的经典错误。nanoTime 单调，只用于求差。
            long claimStartNanos = System.nanoTime();
            String targetChallengeId;
            // R-02: run the whole claim under the player's stripe lock so two
            // rapid clicks of the same button can never interleave a double pay.
            synchronized (getClaimStripeLock(player.getUniqueId())) {
                lastClaimChallengeId = null;
                claimBehavioralChallenge(player, idx);
                targetChallengeId = lastClaimChallengeId;
            }
            long claimElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - claimStartNanos);
            // 日志必须落在锁<b>之外</b>：写日志是 IO（控制台 + 日志文件 flush），
            // 在锁内记会白白延长持锁时间，恰好是本条观测要防的那种退化。
            if (claimElapsedMs >= CLAIM_SLOW_THRESHOLD_MS) {
                plugin.getLogger().warning("Slow daily-challenge claim: player="
                        + player.getName()
                        + " challenge=" + (targetChallengeId == null ? "<none>" : targetChallengeId)
                        + " index=" + idx
                        + " elapsed=" + claimElapsedMs + "ms"
                        + " (threshold " + CLAIM_SLOW_THRESHOLD_MS + "ms = 1 tick,"
                        + " lock wait included)"
                        + " — inspect connectionLock contention / slow database");
            }
        }
    }

    /**
     * R-02: behavioral-challenge claim body. MUST be called with the player's
     * stripe lock held: state is re-read inside the lock and the full
     * "check → deposit → mark" sequence stays serialized, so money is paid at
     * most once for a challenge in any timing. M12 preserved: a failed
     * deposit leaves the reward unmarked and re-claimable.
     *
     * @param player the clicking player
     * @param idx index into today's challenge list (0..CHALLENGE_SLOTS.length-1)
     */
    private void claimBehavioralChallenge(Player player, int idx) {
        var challenges = plugin.getDailyChallengeManager().getTodayChallenges(player.getUniqueId());
        if (idx < 0 || idx >= challenges.size()) return;
        var cp = challenges.get(idx);
        // 纯观测：本次领奖命中的挑战 id，供 handleDailyChallenge 出锁后写慢操作日志用
        //（挑战 id 只有在这里查过 getTodayChallenges 后才知道，而日志必须留在锁外）。
        // 单字段赋值、无分支、不改变任何判定与付款顺序 —— 不是业务逻辑。
        lastClaimChallengeId = cp.type.id;
        if (!cp.completed || cp.claimed) return;
        // M12: deposit-then-mark. Transfer first, mark claimed only
        // after money arrived. On failure the reward stays claimable
        // and the GUI is refreshed unclaimed for retry.
        // MC-5：奖励金额走 DailyChallengeManager.getChallengeReward（唯一读取口，
        // 读 daily-challenges.rewards.<id>，缺键/非法/≤0 回落枚举默认），
        // 直读 cp.type.reward 已废除（PF-MC-09）。
        double reward = plugin.getDailyChallengeManager().getChallengeReward(cp.type);
        // PF-MC-01（Phase 3 规划期发现，编排者即修）：行为挑战领取此前直接走
        // depositCappedReward，未过 money.enabled 门 —— 服主关币（默认 false）且
        // 装了 Vault 时仍会实发金币，与签到/单词挑战的 economy-disabled 语义不一致
        // （F1-11/R-44 的残留缺口）。关闭时终态处理：标记已领（防按钮永远可点、
        // 重复提示）+ 一次 economy-disabled 提示，与单词挑战侧同口径。
        // Wave 1 fan-in（S-H 上报收口）：判定改走 RewardEngine 公开并集
        // （isRewardsEnabled() && money.enabled）——原先只读 money 键，关奖励伞时漏判。
        boolean moneyEnabled = plugin.getProgressManager().getRewardEngine().isMoneyEnabled();
        // round4 F2：本进程已为同一格子付过款但台账没写成 —— 再付一次就是重复发放。
        // 检查放在 stripe lock 内（调用方已持），键含日期，跨天自然失效。
        LocalDate today = LocalDate.now();
        // N10：跨天裁剪必须在所有读/写之前 —— 顺序是「先裁 → 再判 → 后加」。
        // 同一自然日的键一条都不会被误删（今天的键都带今天的日期后缀），且本方法会因
        // paidButUnmarkedDay 闸门而每个自然日至多真正遍历一次。
        prunePaidButUnmarkedForNewDay(today);
        String claimKey = player.getUniqueId() + "|" + cp.type.id + "|" + today;
        if (paidButUnmarked.contains(claimKey)) {
            // 不重开 GUI：重开只会把玩家送回一个仍显示"可领取"的按钮，形成点击循环。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily.claimed"));
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    "该挑战奖励已发放，但领取记录写入失败；请联系管理员核对，不要重复领取 / "
                            + "reward already paid, claim record not saved, "
                            + "contact an admin"));
            return;
        }
        if (!moneyEnabled) {
            // round4 F2：本条无 Vault 转账，"付款"这一步不存在，但仍须分流台账写失败——
            // 否则 claimed 位没落盘，按钮永远可点、玩家每次点都收到同一条提示。
            DailyChallengeRepository.ClaimMark mark = claimRepository.markClaimedOrFail(
                    player.getUniqueId(), LocalDate.now().toString(), cp.type.id);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.economy-disabled"));
            if (mark == DailyChallengeRepository.ClaimMark.WRITE_FAILED) {
                plugin.getLogger().warning("economy-disabled claim row NOT marked — player="
                        + player.getName() + " challenge=" + cp.type.id
                        + " — the claim button will stay clickable");
            }
            new DailyChallengeGUI(plugin).openAsync(player);
            return;
        }
        boolean reopenGui = true;
        // R-16：把「应发面值」与「实发额」一起带下来。缺陷本体就在这一行 —— 过去
        // depositChallengeReward 只回 boolean，下面所有分支都只能拿面值 reward 渲染，
        // 日限截断时玩家被告知 +100 而实际只到 0.20，且台账照样标「已领」，
        // 剩下那 80 永久不可追回。
        RewardEngine.CappedPayout payout = depositChallengeReward(player, reward);
        if (payout.paid() > 0) {
            // round4 F2 / EH-12-02：`markClaimed` 的布尔把「护栏不满足」与「SQL 写失败」
            // 折叠成同一个 false，调用方无法分流；`marked == 0` 原先<b>没有任何分支</b>
            // —— 钱已到账、台账未落、玩家零提示、GUI 立即重开且按钮仍可点（可重复领取）。
            // 改用三态 ClaimMark：MARKED / NOT_ELIGIBLE / WRITE_FAILED 各自有终态处理。
            // 锁纪律：本方法由 handleDailyChallenge 持本类 stripe lock 调用，仓储只取
            // connectionLock（L→M 同向），无反向路径。
            //
            // R-16 裁决（行为挑战，逐格）：<b>截断也照常收盘，但如实报实发额</b>。
            // 理由：本格台账是 daily_challenges 的 (uuid, challenge_date, challenge_id) 行，
            // 质地与单词挑战不同 —— 它是<b>按日一格</b>的，不收盘该行就一直停在
            // completed=1/claimed=0，按钮天天显示「可领取」；而跨天后 getTodayChallenges
            // 只读<b>当日</b>行，昨日的 pending 行再没有任何补发入口（午夜 rollover 的
            // 补发只走 hasPendingWordRewardForDate，即单词挑战那一路）。留 pending 换不来
            // 「明天补发」，只换来一个永远点不完的按钮 + 玩家每次点都看到同一条提示 ——
            // 这与本类 round4 F2 的 paidButUnmarked 循环是同一形态的坑。
            // 所以：收盘 + 用 daily.reward-capped 说明「实际发放 X（应发 Y）」，把
            // 不可追回的事实<b>告诉玩家</b>，而不是用一个假数字盖住它。
            DailyChallengeRepository.ClaimMark mark = claimRepository.markClaimedOrFail(
                    player.getUniqueId(), LocalDate.now().toString(), cp.type.id);
            switch (mark) {
                case MARKED -> player.sendMessage(claimMessage(player, "daily.reward-claimed", payout));
                case NOT_ELIGIBLE -> {
                    // 护栏未命中：本次存款前 cp.claimed 为 false，故该行是在"检查→标记"之间
                    // 被别的实例/线程标掉的（或 completed 被重置）。钱已到账，对玩家是
                    // "已领取"，但台账与本次付款未必一一对应 —— 留 WARNING 供运维对账。
                    plugin.getLogger().warning("Daily challenge reward paid, but claim row was"
                            + " already marked/unmarkable — player=" + player.getName()
                            + " challenge=" + cp.type.id + " reward=" + reward
                            + " paid=" + payout.paid()
                            + " — possible duplicate payout, verify the ledger");
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "daily.claimed"));
                }
                case WRITE_FAILED -> {
                    // 最坏的一格：钱已到账、台账未落。① SEVERE 留痕供人工对账；
                    // ② 对玩家不谎报"发放失败"（金币确实到了），只说明领取记录没写成；
                    // ③ **不重开 GUI** —— 台账未标，按钮会继续显示"可领取"，重开等于
                    //    把玩家送回重复领取的入口。
                    plugin.getLogger().severe("Daily challenge reward PAID but claim row NOT marked"
                            + " — player=" + player.getName()
                            + " challenge=" + cp.type.id
                            + " reward=" + reward
                            + " date=" + LocalDate.now()
                            + " — manual ledger repair required");
                    // 进程内防重复付款（见 paidButUnmarked 的 javadoc）：磁盘 claimed 仍为 0，
                    // 不禁就会二次发放。
                    paidButUnmarked.add(claimKey);
                    player.sendMessage(claimMessage(player, "daily.reward-claimed", payout));
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "general.error", "message",
                            "领取记录写入失败，请勿重复领取，请联系管理员核对 / "
                                    + "claim record not saved, do not claim again, "
                                    + "contact an admin"));
                    reopenGui = false;
                }
            }
        } else {
            // R-16：这里报的是**面值**（"未丢失"指的是这笔面额仍挂在该行上可重试），
            // 与成功分支不同口径是有意的 —— 一分钱都没发时没有"实发额"可报。
            // 同时顺手修掉 `(int)` 截断：99.9 会被渲染成 99，与 CappedPayout 的
            // 两位小数格式化不一致。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily.claim-failed", "reward", payout.nominalText()));
        }
        if (reopenGui) {
            new DailyChallengeGUI(plugin).openAsync(player);
        }
    }

    /**
     * R-16：领奖结果的玩家可见文案 —— <b>未截断走原子，被日限截断走 {@code daily.reward-capped}
     * 并同时给出实发与面值</b>。
     *
     * <p>三个领奖点（本类行为挑战、{@code DailyChallengeManager} 单词挑战、
     * {@code CheckInManager} 签到）此前各写一遍「拿面值拼字符串」，同一个算术抄三份必然漂移；
     * 而「是否截断」的判断已收口在 {@link RewardEngine.CappedPayout#capped()}。
     *
     * <p><b>为什么不用 {@code getPlayerMessage} 拼 {@code genericKey}</b>：缺键时它回落的是
     * 可见占位符 {@code [?daily.reward-capped]}，玩家会看到一串方括号。但截断这件事本身
     * 不可省略（帧一：不报实发＝谎报金额），故取「两段都必发、各自可独立缺键」的形状：
     * 截断时先发一句实发说明，再发原子（未截断就只发原子）。原子保持既有键与占位符，
     * 既有 yml 不用改。
     *
     * @param player the player to message
     * @param baseKey {@code daily.reward-claimed} 一类既有键
     * @param payout  面值与实发额
     * @return 要发给玩家的完整消息（多行已用换行拼接）
     */
    private String claimMessage(Player player, String baseKey, RewardEngine.CappedPayout payout) {
        String base = plugin.getMessageUtil().getPlayerMessage(player,
                baseKey, "reward", payout.paidText());
        if (!payout.capped()) {
            return base;
        }
        // 截断：先把「实际发了多少 / 应发多少」讲清楚，再接原子。
        String capped = plugin.getMessageUtil().getPlayerMessage(player,
                "daily.reward-capped",
                "paid", payout.paidText(),
                "nominal", payout.nominalText());
        return capped + "\n" + base;
    }

    /**
     * R-34/F1-23: route the behavioral-challenge payout through
     * {@link com.letslearnenglish.minecraftplugin.core.progress.RewardEngine#depositCappedReward}
     * so it obeys {@code money.daily-cap} and updates the today-coins ledger, and so coin
     * stats are recorded inside the reward layer — this listener no longer calls Vault or
     * {@code DatabaseManager.recordCoinsEarned} directly.
     *
     * <p><b>R-16：返回 {@link RewardEngine.CappedPayout} 而不是 boolean。</b>旧签名把
     * 「发没发」答得很清楚、把「发了多少」整个丢掉，调用方只能拿<b>面值</b>渲染文案
     * （见 {@code claimMessage} 与调用点的 R-16 说明）。{@code paid > 0} 仍然等价于
     * 「钱到了」（调用方据此决定要不要写 claimed），只是现在同时带着实发额。
     * 无 Vault 时如实返回 {@code paid=0} —— 与旧行为一致（不写 claimed、可重试）。
     *
     * @param player the player
     * @param reward the nominal reward amount
     * @return 面值 + 实发额（{@code paid() > 0} 才表示钱真的到了）
     */
    private RewardEngine.CappedPayout depositChallengeReward(Player player, double reward) {
        var eco = plugin.getEconomy();
        if (eco == null) {
            plugin.getLogger().warning("Daily challenge reward of " + reward
                    + " for " + player.getName() + " not paid: Vault economy unavailable");
            return new RewardEngine.CappedPayout(reward, 0);
        }
        double paid = plugin.getProgressManager().getRewardEngine().depositCappedReward(player, reward);
        return new RewardEngine.CappedPayout(reward, paid);
    }

    private void handleChallengeComplete(Player player, int slot) {
        if (slot == 8 || slot == 22) {
            SoundManager.playSound(player, "menu-click");
            // F1-09: slot 22 is the "reward already arrived, back to menu"
            // button (NOT a claim action — payout happened before this page
            // opened).
            // R-55: 庆祝页返回改走 popBack（有栈回栈、无栈回主菜单），
            // 与 DailyChallenge 的 slot 8 返回语义一致，不再绕栈直开主菜单。
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
        }
    }

    /**
     * R-55e: 拖拽 guard（抄 DuelGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 本监听器辖下各 GUI（隐私设置/词库选择/过往学习/每日挑战/庆祝页）
     * 的展示物品可被拖进玩家背包。
     *
     * R5-11：入口判定<b>只认 holder</b>（{@link #isTrackedView}），标题兜底已删除。
     */
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView() == null) return;

        // R5-11：与 onInventoryClick 同一套纯 holder 判定（共用 isTrackedKey）。
        if (!isTrackedView(event.getView())) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
