package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Database Manager
 *
 * Supports SQLite (default) and MySQL storage backends.
 * Handles database connection management, table creation, and migration.
 */
public class DatabaseManager {

    private final LetsLearnEnglish plugin;
    private final ConfigManager configManager;
    private final Object connectionLock = new Object();
    private volatile Connection connection;
    /**
     * SQL 表名前缀（{@code database.mysql.table-prefix}，默认 {@code lle_}）。
     *
     * <p><b>为什么是 volatile</b>：写侧只有一处 —— {@link #initialize()} 的
     * {@code synchronized (connectionLock)} 块内（{@code /lea reload} 重连时会重写）。
     * 读侧却<b>全在锁外</b>：{@link #getTablePrefix()} 不加锁，全仓 36 处调用点
     * （{@code RewardEngine}、{@code SeasonManager}、{@code PlayerDataStore}、
     * {@code DailyChallengeManager}、{@code ReportGenerator} 等，共 12 个文件）都是
     * 「先取前缀拼 SQL，再进锁执行」。
     *
     * <p>非 volatile 时存在一条真实的半更新窗口：{@code /lea reload} 改前缀的同时
     * 有并发发币，读侧可能拿到<b>半更新的组合</b> —— 例如 {@link #isSQLite} 已翻成
     * {@code false}（MySQL 语法）而本字段还是 SQLite 的前缀，于是拼出一条两边都不对的
     * SQL。volatile 让「读到旧值」或「读到新值」都是完整的一个值，消掉撕裂窗口
     * （两个字段各自 volatile 仍不构成原子对，但已消除最坏的那种混合）。
     *
     * <p><b>刻意不给 getter 加锁</b>：那条路在发币热路径上，36 处调用点全部加锁是净亏损；
     * 与同类的 {@code connection} / {@code shuttingDown} 字段同式（volatile + 无锁读）。
     */
    private volatile String tablePrefix;

    /**
     * 当前后端是否为 SQLite（{@code true}）/ MySQL（{@code false}）。
     *
     * <p><b>为什么是 volatile</b>：理由与 {@link #tablePrefix} 完全对称 ——
     * 写侧在 {@link #initialize()} 的锁内，读侧经 {@link #isSQLite()} 在锁外，
     * 且调用点用它选 {@code ON CONFLICT} / {@code ON DUPLICATE KEY} 两套方言
     * （{@code RewardEngine:190-192} 等）。两个字段被并发读到不一致的组合时，
     * 拼出的 SQL 在两种后端上<b>都</b>语法错误 —— 那不是「偶发慢查询」，
     * 是直接抛 {@code SQLException} 的写失败。
     */
    private volatile boolean isSQLite;
    /**
     * L24：维护任务（{@code VACUUM}）的非阻塞去重闸。维护调用方在异步线程执行，
     * 若玩家/管理员连续触发两次，第二次直接返回，避免排队堆积重复的全库重建。
     */
    private final AtomicBoolean maintenanceInProgress = new AtomicBoolean(false);

    /** EH-01：关库闩。shutdown() 后置 true，getConnection() 不再自愈复活连接。 */
    private volatile boolean shuttingDown = false;

    /**
     * R12d-F7：本次启动是否<b>刚刚</b>给 {@code seasons} 补上 {@code ledger_pending} 列
     * （即第一个跑到含该列 schema 的版本）。只有这种老库升级场景才需要对「升级前就已
     * 抢占但台账无行」的赛季做一次性人工对账 —— 见
     * {@link #findLegacySeasonsWithClaimAndNoLedger()}。
     *
     * <p>用 {@code AtomicBoolean} 而非普通字段：对账结果是一次性消费语义
     * （{@link #consumeSeasonLedgerPendingColumnAdded()}），重复告警会让运维把这条
     * SEVERE 当噪音屏蔽。</p>
     */
    private final AtomicBoolean seasonLedgerPendingJustAdded = new AtomicBoolean(false);

    /**
     * R-48：本次启动是否<b>刚刚</b>给 {@code player_data} 补上 {@code answered_count} 列。
     *
     * <p>只用于「老库升级」这一个场景：CREATE TABLE 里已含该列的新库不会置位，
     * 重启也不会置位（列已存在 → {@code addColumnIfMissing} 返回 false）。置位后由
     * {@code createTables()} 末尾调 {@link #backfillAnsweredCount()} 回填并在同一处清掉。
     *
     * <p>用普通 {@code boolean} 而不是 {@code AtomicBoolean}：读写都在
     * {@code initialize()} 的 {@code connectionLock} 临界区内、同一个线程、同一次调用里，
     * 没有跨线程可见性需求（与 {@code seasonLedgerPendingJustAdded} 不同 —— 那个要跨
     * 生命周期被外部消费）。
     */
    private boolean answeredCountColumnJustAdded;

    /**
     * R-16：本进程的<b>实例标识</b>（每个 {@code DatabaseManager} 一个新 UUID）。
     *
     * <p><b>R-16 后续修正：它已不再写进数据库。</b>原来 {@code seasons.claim_owner} 存的是
     * 这个 per-process UUID，于是「同一台机器重启」和「另一台服抢占」在库里<b>逐字节同形</b>
     * —— {@code SeasonManager.rebuildPendingReleases} 只能按 {@code settled_at} 的年龄
     * （{@code SeasonManager.FOREIGN_CLAIM_STALE_MS} 15 分钟）区分，而它自己的恢复路径
     * （onEnable 的那一次 sweep）恰恰发生在重启后<b>几秒</b>内。结果：快速重启永远救不回
     * 自己刚留下的脏 claim，要等到下一次「重启 > 15 分钟之后」或下一个季边界
     * （默认 30 天）才恢复。现在库里存的是 {@link #nodeId}（跨重启稳定），
     * 本字段只在<b>取不到任何部署身份</b>时兜底成 node id（见 {@link #computeNodeId}）。</p>
     *
     * <p><b>必须 {@code final}</b>：一个 {@code DatabaseManager} 一个值，不能中途换。</p>
     */
    private final String instanceId = java.util.UUID.randomUUID().toString();

    /**
     * R-16：本<b>节点</b>的稳定标识 —— 写进 {@code seasons.claim_owner} 的那个值。
     *
     * <h2>为什么不是 per-process 的 {@link #instanceId}</h2>
     * <p>{@code claim_owner} 在库里的唯一职责是回答「这一行的抢占能不能由我释放/重结算」，
     * 那是一个<b>节点</b>问题，不是<b>进程</b>问题：重启后的新进程仍是同一台服、同一个库、
     * 同一个运营者，理应接管自己上一次留下的脏 claim。真正的「别人会不会在我读和写之间
     * 抢走」由 {@code settled_at}（每次 claim 都会变）＋ {@code claim_owner} 本身是
     * 节点级稳定值来覆盖，不需要按进程区分。</p>
     *
     * <h2>怎么算出来的（以及接受的代价，诚实记录）</h2>
     * <p>{@code sha256(<长度前缀编码(proxy.server-name, dataFolder 绝对路径)>)} 取前 16 位十六进制，
     * 前像为 {@code <UTF-8 字节长度>:<字段原文>} 两段直接相接（例：{@code "5:lobby34:/srv/lobby/data"}）。
     * <b>R-17 之前</b>这里写的是 {@code serverName + "|" + dataFolderPath} —— 没有长度前缀，
     * 于是 {@code ("n|P", "P")} 与 {@code ("n", "P|P")} 拼出逐字节相同的前像、算出同一个 id
     * （两台不同部署共用 fencing token = R-16 重新打开且永不走 15 分钟过期路径）。
     * 现在编码单射，<b>无论字段内容含什么字符</b>都不可能撞；完整论证与迁移影响见
     * {@link #computeNodeId} 的 javadoc。</p>
     * <p>两个来源都是<b>跨重启稳定</b>的：{@code proxy.server-name} 是运营者给这个子服起的名字，
     * dataFolder 绝对路径标识<b>这一份安装</b>（顺手把「同机两个子服」也拆开）。三点代价：</p>
     * <ol>
     *   <li><b>{@code proxy.server-name} 为空</b>（单服 / 未上代理 —— <b>默认配置就是空</b>）
     *       时退化为「只按 dataFolder 路径」：同机上多份安装仍然各不相同，只有
     *       「两个后端共用一个 dataFolder」才会撞 —— 那是我们不支持的错配置（同一个 SQLite
     *       文件被两个进程同时写，问题远大于抢占身份）。<b>空字段不会与「有值」混淆</b>：
     *       编码后空串是 {@code "0:"}、非空是 {@code "<n>:<原文>"}，故
     *       {@code ("", "P")} / {@code ("", "")} / {@code ("P", "")} 三者互不相同
     *       （前两者靠第二个字段的长度前缀分开，后两者靠第一个字段的）。</li>
     *   <li><b>改服名 / 搬家</b> → 算出新 node id → 老行看起来是「他服」的 → 走
     *       {@code SeasonManager.FOREIGN_CLAIM_STALE_MS} 的过期路径（等 15 分钟）而不是
     *       立即接管。
     *       这是<b>刻意的降级方向</b>：宁可多等 15 分钟，也不要把「别台服正在写」误判成
     *       「我自己的」。改名前刷新出来的脏 claim 最多晚 15 分钟恢复，仍好于修正前的
     *       最长 30 天。</li>
     *   <li><b>R-17：升级到本版后，<u>每台服</u>的 nodeId 都会变一次</b>（前像换了编码，这是
     *       必然的），于是升级前写下的 {@code claim_owner} 值<b>全部</b>看起来是「他服」。
     *       这是可接受的一次性降级，<b>没有人丢奖励</b>：见 {@link #computeNodeId} 的
     *       「迁移」一节（15 分钟自愈路径已逐条核对覆盖）。</li>
     * </ol>
     * <p>两者都取不到（单元测试里 mock 没桩 {@code getDataFolder()}、或配置读失败）时
     * <b>回落成 per-process 的 {@link #instanceId}</b>：那正好等价于修正前的语义
     * （重启后的自己 = 他服 = 走过期路径），<b>不会</b>把别人的活 claim 误认成自己的。</p>
     *
     * <p>写入方：{@link #claimSettlement} 与 {@link #reassignSeasonClaimOwner}（与
     * {@code settled_at} 同一条 UPDATE）；读取方：{@code SeasonManager.releaseSettlement}
     * 的 {@code AND claim_owner = ?}，以及 {@code rebuildPendingReleases} 的「这条欠账是不是
     * 归我处理」。<b>三处必须用同一个值</b>，否则条件 UPDATE 永远匹配 0 行。</p>
     */
    private final String nodeId;

    public DatabaseManager(LetsLearnEnglish plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.nodeId = computeNodeId(plugin, configManager, instanceId);
    }

    /**
     * R-16：本节点的稳定结算抢占标识（见 {@link #nodeId} 的 javadoc）。
     *
     * <p><b>换掉 {@code getInstanceId()} 的原因</b>：调用方
     * （{@code SeasonManager.releaseSettlement} / {@code rebuildPendingReleases} /
     * {@code recordPendingRelease} 的手工修法 SQL）全都需要「库里那一列实际存的值」。
     * 既然写库的值改成节点级，读取面必须一起改 —— 否则释放会带着 per-process UUID 去匹配
     * 一个节点级的值，条件 UPDATE 命中 0 行，判成 {@code NOT_HELD}，脏 claim 永远清不掉。</p>
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * R-16：算出 {@link #nodeId}。私有静态 —— 构造期调用，不依赖任何实例状态。
     *
     * <p>失败方向必须是<b>安全</b>的：任何一步取不到（配置为 null / 路径为 null / 两者都空 /
     * JVM 没有 SHA-256）都回落到 {@code fallbackInstanceId}，即修正前的 per-process 语义
     * （重启后的自己会被当成他服，走 15 分钟过期路径）。<b>绝不</b>回落成一个所有服共享的
     * 常量 —— 那会让每台服都以为别人的活 claim 是自己的，把 R-16 修的缺陷重新打开。</p>
     *
     * @param fallbackInstanceId 无部署身份时使用的 per-process 标识
     *
     * <h2>R-17：前像编码必须单射（修的是「两组不同部署算出同一个 nodeId」）</h2>
     * <p>修正前的前像是 {@code serverName + "|" + dataFolderPath}，<b>无长度前缀</b>，
     * 于是「分隔符落在哪一段」由内容自己决定：
     * {@code ("n|P", "P")} 与 {@code ("n", "P|P")} 都拼成 {@code "n|P|P"} → 同一个 16 位 id。
     * 现实可达的形态是 {@code proxy.server-name} 里带 {@code |}（运营者随手写 {@code srv|main}）
     * 或数据目录路径里带 {@code |}（Windows 上合法，Linux 上也合法）。后果不是"多一次广播"：
     * 两台服共用同一个 fencing token → 互相把对方的 live claim 当成自己的 →
     * <b>R-16 修复的「他服正在结算却被本服释放」重新打开，而且这次是永久的</b>
     * （owner 逐字节相等，永不进入「他服且陈旧」分支，15 分钟过期路径也救不了）。</p>
     *
     * <p>新编码：每段前加<b>该段 UTF-8 字节长度的十进制</b>再跟 {@code ':'}，两段直接相接：</p>
     * <pre>
     *   material = byteLen(name) + ":" + name + byteLen(path) + ":" + path
     *   例：name="lobby", path="/srv/lobby/data"
     *       → "5:lobby15:/srv/lobby/data"
     * </pre>
     *
     * <h3>① 单射性论证（不是"看起来没问题"）</h3>
     * <p>要证：{@code (a1,b1) != (a2,b2) ⟹ material1 != material2}（逐字节）。用反证 + 解析
     * 唯一性：假设 material1 == material2，从左往右解析同一个字节串：</p>
     * <ol>
     *   <li>先读十进制数字直到第一个 {@code ':'}。两个串从第 0 字节起完全相同，所以第一个
     *       {@code ':'} 的位置相同、数字串相同 → 解出的 {@code n1 == n2}。
     *       （长度用<b>规范十进制</b>输出 —— {@code StringBuilder.append(int)} 不产生前导 0，
     *       所以"长度段"与"第一个 ':'"一一对应，不存在 {@code "05:a"} 与 {@code "5:a"} 这类
     *       二义写法；即便存在，数值解析仍唯一，只是我们永不产出非规范形式。）</li>
     *   <li>跳过 {@code ':'} 后<b>恰好取 n 个字节</b>作为第一段。UTF-8 是自同步（self-synchronizing）
     *       且前缀无关的编码：第一段的 n 字节是一个完整的 UTF-8 序列，解码结果唯一，
     *       所以 {@code bytes(a1) == bytes(a2)} → {@code a1 == a2}。</li>
     *   <li>对剩余字节串递归做第 1、2 步 → {@code b1 == b2}。于是 {@code (a1,b1) == (a2,b2)}，
     *       与前提矛盾。∎</li>
     * </ol>
     * <p>两段之间<b>不需要</b>分隔符（也刻意不加）：第一段的自描述长度已经把它完整吃掉，
     * 剩余部分的起点是确定的。任何"转义 {@code |}"式的方案都不具备这个性质（转义字符自身
     * 又需要被转义），故不采用。</p>
     *
     * <h3>② 空字段（{@code proxy.server-name} 默认就是空）</h3>
     * <p>空串编码成 {@code "0:"}，非空编码成 {@code "<n>:<原文>"}（{@code n ≥ 1}），
     * 两者形状不同，因此三个易混输入互不相同（都有用例钉住）：</p>
     * <ul>
     *   <li>{@code ("", "P")} → {@code "0:" + len(P) + ":" + P}</li>
     *   <li>{@code ("", "")} → {@code "0:0:"}</li>
     *   <li>{@code ("P", "")} → {@code len(P) + ":" + P + "0:"}</li>
     * </ul>
     *
     * <h3>③ 旧 nodeId 与新 nodeId 并存 —— 迁移行为（升级后第一次启动）</h3>
     * <p>前像变了 ⟹ <b>每台服</b>的 nodeId 都会变一次（不只是改过名的服）。于是升级前
     * 写下的 {@code claim_owner} 值<b>全部</b>看起来是「他服」，这正是
     * {@code SeasonManager.rebuildPendingReleases} 的 ③ 分支，两条路都自愈、<b>没有人丢奖励</b>：</p>
     * <ol>
     *   <li><b>年龄 &gt; 15 分钟</b>（{@code FOREIGN_CLAIM_STALE_MS}）→ 立即走
     *       {@link #reassignSeasonClaimOwner}（条件里带 {@code AND claim_owner = <观察到的旧值>}
     *       防 TOCTOU）把 owner 改成新 id，再灌进待重试表 → {@code releaseSettlement} 匹配得上 →
     *       释放 → 重结算。<b>幂等</b>：台账 {@code INSERT OR IGNORE} + 发放走行级
     *       {@code claimed=0} 条件 UPDATE，所以最多多一次"赛季结束"广播，不会重复发钱。</li>
     *   <li><b>年龄 ≤ 15 分钟</b> → 本轮跳过，但 {@code scheduleForeignClaimRecheck} 会排一次
     *       到期复查（延迟 = 剩余宽限期），到点重跑整轮 sweep → 转成第 1 条。所以最坏情况是
     *       <b>启动后 15 分钟内</b>自愈，不需要等下一个季边界（默认 30 天）。</li>
     *   <li><b>降级回 1.0.7 之前</b>是镜像对称的：那时新 id 的行看起来是「他服」，同一条
     *       15 分钟路径再自愈一次。两个方向都不需要手工 SQL。</li>
     * </ol>
     * <p>唯一"看起来脏但无害"的遗留：{@code ledger_pending = 0} 的已结算季若在升级前
     * 留下非 NULL 的 {@code settled_at}（上一轮已清标记、还没来得及释放），它<b>不会</b>被
     * 欠账扫描（{@code loadSeasonsWithOwedLedger} 要求 {@code ledger_pending <> 0}）看到，
     * 于是那行的 {@code claim_owner} 会一直是旧值。无害：该季已经结算完毕，台账已落库，
     * 游标（{@code MAX(season_id) WHERE settled_at IS NOT NULL}）本来就该越过它，
     * 没有任何路径需要"释放"一个已结算的季。</p>
     *
     * <h3>④ 回落（两字段都空 → {@code instanceId}）的语义<b>不变</b></h3>
     * <p>编码本身能区分空字段（见 ②），但「两个字段都空」<b>不构成身份</b>：那种情况下
     * 每台服算出的前像都是 {@code "0:0:"}，即所有服共享同一个常量 —— 正是本方法绝对
     * 不能返回的东西（每台服都会把别人的 live claim 认成自己的）。所以判断顺序是
     * <b>先判"有没有任何身份"，再编码</b>：两者都空仍回落 per-process {@code instanceId}
     * （= 修正前语义：重启后的自己算他服，走 15 分钟路径）。加单射编码不改变这条兜底的方向。</p>
     *
     * <h3>长度与实现细节</h3>
     * <p>编码带来的额外开销只有每段几个字节（长度数字 + {@code ':'}），典型 material
     * 仍是几十字节量级 —— 它随后走 SHA-256，长度不进库、不进日志。</p>
     * <p><b>已知且未改变的边界</b>：本编码对<b>字节串</b>单射；Java {@code String} 若含
     * <b>不成对的代理项</b>（非法 Unicode），{@code getBytes(UTF_8)} 会把它替换成 {@code '?'}
     * 再参与哈希，因此 {@code "\uD800"} 与 {@code "?"} 在这两层都同形。这是
     * {@code String → byte[]} 这一既有转换的性质（修正前就存在），不是长度前缀引入的；
     * 且两个来源（YAML 配置值、{@code File} 绝对路径）都只可能是合法 Unicode，不可达。</p>
     */
    private static String computeNodeId(LetsLearnEnglish plugin, ConfigManager configManager,
                                        String fallbackInstanceId) {
        String serverName = "";
        String dataFolderPath = "";
        try {
            // proxy.server-name：运营者给本子服的稳定名字（默认空 —— 单服/未上代理）。
            FileConfiguration config = configManager == null ? null : configManager.getMainConfig();
            if (config != null) {
                String configured = config.getString("proxy.server-name", "");
                if (configured != null) {
                    serverName = configured.trim();
                }
            }
        } catch (RuntimeException ignored) {
            // 配置读失败不是致命错误：dataFolder 仍能把本安装与其它安装区分开。
        }
        try {
            File folder = plugin == null ? null : plugin.getDataFolder();
            if (folder != null) {
                dataFolderPath = folder.getAbsolutePath();
            }
        } catch (RuntimeException ignored) {
            // 同上。
        }
        // 判断顺序不能反：先判「有没有任何身份」，再编码。两个字段都空时前像恒为 "0:0:"，
        // 那是一个所有服共享的常量 —— 必须走 per-process 回落（见上面 ④）。
        if (serverName.isEmpty() && dataFolderPath.isEmpty()) {
            return fallbackInstanceId;
        }
        // R-17：长度前缀编码，单射（论证见 javadoc ①）。不要改回 "a|b" 或任何无长度前缀的拼接。
        StringBuilder materialBuilder =
                new StringBuilder(serverName.length() + dataFolderPath.length() + 16);
        appendLengthPrefixed(materialBuilder, serverName);
        appendLengthPrefixed(materialBuilder, dataFolderPath);
        String material = materialBuilder.toString();
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            return fallbackInstanceId;
        }
        // 取前 8 字节 → 16 位十六进制。手写而不是 BigInteger.toString(16)：后者会吃掉前导 0，
        // 长度变成 15，同一个节点在不同 JVM 上会算出两种长度的 id。
        StringBuilder hex = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * R-17：把一段按「UTF-8 字节长度 + {@code ':'} + 原文」追加进 nodeId 前像。
     *
     * <p><b>为什么长度按 UTF-8 字节而不是 char 数</b>：哈希的输入是
     * {@code material.getBytes(UTF_8)}，解析端（也就是单射性论证里的"取 n 个字节"）数的是
     * 同一批字节。用 char 长度会让一个含多字节字符的字段声称的长度与实际字节数不符，
     * 于是"自描述"这条性质在字节层面失效，论证也就断了。</p>
     *
     * <p>包级可见（不是 private）：{@code NodeIdDerivationTest} 用<b>生产编码器</b>构造恶意输入与
     * 独立对照，避免测试自己重写一遍编码而变成自证。</p>
     */
    static void appendLengthPrefixed(StringBuilder out, String field) {
        out.append(field.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .append(':')
                .append(field);
    }

    /**
     * Initialize database connection and table structure.
     *
     * <p>E-05：重连/自愈路径会重复进入本方法，先关旧连接再覆盖，
     * 否则旧句柄泄漏（SQLite 文件锁 / MySQL 空闲连接堆积）。
     * 关旧连接的异常吞掉记 WARNING，不阻断新连接建立。
     */
    public void initialize() {
        synchronized (connectionLock) {
            // EH-01：显式 initialize = 有意重开（onEnable / 测试），清掉关库闩。
            shuttingDown = false;
            closeQuietly(connection);
            connection = null;
            FileConfiguration config = configManager.getMainConfig();
            String type = config.getString("database.type", "sqlite");
            this.tablePrefix = config.getString("database.mysql.table-prefix", "lle_");

            try {
                if ("mysql".equalsIgnoreCase(type)) {
                    initializeMySQL(config);
                    this.isSQLite = false;
                } else {
                    initializeSQLite();
                    this.isSQLite = true;
                }

                createTables();
                plugin.getLogger().info("Database initialized (type: " + type + ")");
                if (isSQLite) {
                    warnAboutWalSidecars();
                }
            } catch (SQLException | RuntimeException e) {
                // LOGIC-02：失败必须回收半初始化连接。否则 createTables() 抛错后 connection
                // 已赋值且未关闭，getConnection() 判它"健康"永久返回 → 此后所有查询打在
                // "已连上但无表"上且永不自愈。置 null 让下一次取连接重试（并兜 RuntimeException）。
                closeQuietly(connection);
                connection = null;
                plugin.getLogger().log(Level.SEVERE,
                        "Database initialization failed! Connection released; will retry on next access.", e);
                throw new RuntimeException("Failed to initialize database. Check your database configuration.", e);
            }
        }
    }

    /**
     * WAL 副文件体检：启动时若发现 {@code -wal} / {@code -shm} 与主库并存且 WAL 异常大，
     * 提醒服主"数据库不是单个 .db 文件"这一事实。
     *
     * <p><b>为什么需要这条日志</b>：本插件把 SQLite 切到 WAL 后，{@code playerdata.db}
     * 不再是完整数据库——未合并的事务在 {@code playerdata.db-wal} 里。而 README /
     * DEPLOYMENT_WIKI 长期教的恢复手段是 {@code cp 备份 playerdata.db}，这在 WAL 下
     * <b>会把旧 WAL 重放进刚恢复的库</b>，于是"回滚到备份点"静默失败：被回滚的数据
     * 重新出现，且没有任何报错。文档已改成先删副文件 / 用 {@code .backup}，
     * 这条日志是最后一道提醒：服主看到 {@code -wal} 文件时能想起它属于数据库的一部分。
     *
     * <p>只在 WAL 明显偏大（&gt; 64 MB）时告警，避免每次启动刷一条无害信息。
     * 任何 IO 异常都吞掉——体检失败不该影响启动。
     */
    private void warnAboutWalSidecars() {
        try {
            String base = configManager.getMainConfig()
                    .getString("database.sqlite.filename", "playerdata.db");
            File dbFile = new File(plugin.getDataFolder(), base);
            File wal = new File(dbFile.getAbsolutePath() + "-wal");
            File shm = new File(dbFile.getAbsolutePath() + "-shm");
            if (!wal.isFile() && !shm.isFile()) {
                return;
            }
            long walSize = wal.isFile() ? wal.length() : 0L;
            long threshold = 64L * 1024 * 1024;
            if (walSize > threshold) {
                plugin.getLogger().warning("SQLite WAL file is large ("
                        + (walSize / (1024 * 1024)) + " MB). The database is NOT just "
                        + dbFile.getName() + " — it also includes " + wal.getName()
                        + " and " + shm.getName() + ". When restoring a backup, delete the "
                        + "-wal/-shm files first (or use 'sqlite3 " + dbFile.getName()
                        + " \".backup out.db\"'), otherwise stale WAL data will be replayed "
                        + "over the restored file and your rollback will silently not take effect.");
            }
        } catch (Throwable t) {
            // 体检是提示性的，任何异常都不该影响启动
            plugin.getLogger().fine("WAL sidecar check skipped: " + t.getMessage());
        }
    }

    private void initializeSQLite() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(),
                configManager.getMainConfig().getString("database.sqlite.filename", "playerdata.db"));
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        // 性能：SQLite 连接级 PRAGMA 调优（仅 SQLite；MySQL 走服务端自身配置，
        // 本方法只在 SQLite 分支被调用）。
        //  - journal_mode=WAL：写时不再阻塞读（默认 rollback journal 下写事务持整库锁，
        //    读会排队）；同时大幅降低随机写开销。
        //  - synchronous=NORMAL：WAL 下把每次提交的 fsync 降为只在 checkpoint 时同步，
        //    换来写吞吐量级提升；代价是断电/崩溃可能丢最后若干个已提交事务，但
        //    WAL 日志格式保证库文件不会损坏（不会出现半写页），这是 SQLite 官方
        //    推荐的 WAL 搭配。
        //  - busy_timeout=5000：并发访问遇到锁时最多等 5 秒再报 SQLITE_BUSY，
        //    避免异步保存线程与主线程查询撞锁立刻抛错。
        // journal_mode 是持久化在库文件头里的（设一次即可，重复设置幂等），另两个是
        // 连接级。PRAGMA 失败只影响性能、不影响正确性，故只记 WARNING 不阻断启动。
        try (Statement stmt = connection.createStatement()) {
            // journal_mode 会返回结果行（实际生效的模式），须用 executeQuery 读掉，
            // 否则 executeUpdate 会报 "query returns results"。
            try (ResultSet modeRs = stmt.executeQuery("PRAGMA journal_mode=WAL")) {
                String mode = modeRs.next() ? modeRs.getString(1) : "unknown";
                if (!"wal".equalsIgnoreCase(mode)) {
                    plugin.getLogger().warning("SQLite journal_mode is " + mode
                            + " (expected wal); concurrent write performance may degrade");
                }
            }
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to apply SQLite performance PRAGMAs (journal_mode=WAL, "
                            + "synchronous=NORMAL, busy_timeout=5000); continuing with defaults", e);
        }
    }

    private void initializeMySQL(FileConfiguration config) throws SQLException {
        // P0-1：驱动已随包 shade（pom 的 maven-shade-plugin），但 Bukkit/Paper 用独立的
        // 插件 ClassLoader，JDBC 4 的服务自动发现依赖线程上下文类加载器，未必能看见
        // META-INF/services/java.sql.Driver。显式加载一次可把失败点从晦涩的
        // "No suitable driver found" 提前成一条可读的错误。
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver (com.mysql.cj.jdbc.Driver) is not available. "
                    + "It should be bundled in this plugin's jar; if you built the plugin yourself, "
                    + "check that the maven-shade-plugin execution ran (see README 'Build').", e);
        }

        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "letslearnenglish");
        String username = config.getString("database.mysql.username", "root");
        String password = config.getString("database.mysql.password", "");

        // R-16（数据层审计 F-D10）：必须给 socket 与握手都上超时，否则一条被挂住的网络
        // 读会把<b>整个插件的数据库层</b>停住 —— 本类只有一条共享连接，所有访问都串行在
        // {@code synchronized (connectionLock)} 上，而 synchronized 本身没有超时。
        // 这不是理论风险：{@link #clearSeasonLedgerPendingDetailed} 的 javadoc 记录过一次
        // 红队复现的真实事故，起因正是"initializeMySQL 没有设 socketTimeout /
        // connectTimeout，MySQL 的锁等待就能把它挂住"—— 那次是用 fencing 堵的行为，
        // 超时本身从未补上。本方法补的就是那个缺口。
        //
        // 取值依据（socketTimeout 是最关键的一项）：
        //  - 正常查询在本插件的表规模上是毫秒级；重查询（loadSessionParticipants 的整季
        //    范围扫等）在索引可用时也是百毫秒量级。
        //  - 30 秒远大于任何合法查询，又小于 Bukkit 的看门狗（默认 60 秒）与
        //    SeasonManager.FOREIGN_CLAIM_STALE_MS（15 分钟）—— 关键是要让"挂住"以一个
        //    可捕获的 SQLException 结束，而不是让调用线程无限期停顿。
        //  - 不能取更短（如 5 秒）：mysql-connector-j 的 socketTimeout 覆盖的是"单次网络
        //    读等响应"的整段时间，跨服共享 MySQL 在正常行锁排队下超过 5 秒并不罕见，
        //    误杀它会变成"偶发失败"这种更难查的故障。SQLite 侧的 busy_timeout=5000 是
        //    本地文件锁的先例，语义不同，不能照搬。
        //  - connectTimeout 取 10 秒：它只管 TCP 握手 + 认证，不涉及查询耗时。防火墙静默
        //    丢 SYN 时操作系统的默认重试可达 20 秒以上；10 秒足以容忍跨机房的握手，又能
        //    让"库连不上"在启动阶段快速暴露成明显的 SEVERE。
        final int connectTimeoutMs = 10_000;
        final int socketTimeoutMs = 30_000;
        final int maxReconnects = 2;
        // 单位是<b>秒</b>，不是毫秒 —— 驱动把它当秒用：
        // ConnectionImpl.connectWithRetries 里 {@code Thread.sleep(initialTimeout * 1000)}。
        // 写成 2000 会变成每次重连前先睡 33 分钟，比原本"没有超时"还糟。变量名带 Sec 后缀
        // 就是为了让下一个人没法再写错一次。
        final int initialTimeoutSec = 2;

        // autoReconnect：<b>刻意保留</b>（结论来自读驱动字节码，不是"官方说别用"就删）。
        // mysql-connector-j 8.3.0（本插件 shade 的版本）里 autoReconnect 的实际行为是：
        // NativeSession.execSQL 在<b>发下一条语句之前</b>，若
        // {@code autoReconnect && (autocommit || autoReconnectForPools) && needsPing}
        // 就先 ping；ping 失败才 invokeReconnectListeners →
        // ConnectionImpl.handleReconnect → createNewIO(true)。
        // 注意 autoReconnectForPools 默认 false、reconnectAtTxEnd 默认 false，
        // 所以<b>事务中（autocommit=false）驱动不会自动重连</b>，通信失败照原样抛
        // SQLException 给 catch 块走 rollback —— F-D1 那套事务/回滚语义不受影响。
        // 同理它<b>不会</b>在一条已经阻塞的读里重试：卡住的那条由 socketTimeout 兜底，
        // 驱动不在它内部循环。
        // 代价是"下一次语句"可能多花重连时间：最坏 maxReconnects×(connectTimeout +
        // initialTimeout) ≈ 2×(10 秒 + 2 秒)=24 秒，且这是<b>有界</b>的一次性开销，可接受。
        // 为什么不干脆删掉：没有 autoReconnect 时，一次通信中断会让连接进入 closed 态，
        // 下一次访问要等本类 getConnection() 的自愈（connection.isClosed() → 重新
        // initialize()），而 initialize() 会重跑 23 条建表 DDL + 19 条索引存在性探测，
        // 比驱动内重连一次贵得多，且那条自愈路径失败时会退化成 UNAVAILABLE 代理。
        // （索引数按 createIndexIfMissing 的真实调用点算 = 19；schema.sql 里另有 22 条
        //   CREATE INDEX，差额来自 MySQL 侧不支持 IF NOT EXISTS、走的是代码路径分支。）
        // 另：本插件在 MySQL 上<b>不设置任何会话级状态</b>（无 SET SESSION / 用户变量 /
        // GET_LOCK / SELECT ... FOR UPDATE，已 grep 确认），所以 autoReconnect 常被诟病的
        // "静默重连丢会话状态"在本代码库里没有可丢的东西。
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&autoReconnect=true"
                + "&maxReconnects=" + maxReconnects
                + "&initialTimeout=" + initialTimeoutSec
                + "&connectTimeout=" + connectTimeoutMs
                + "&socketTimeout=" + socketTimeoutMs;

        connection = DriverManager.getConnection(url, username, password);

        // R-16：把生效值记 INFO。运维排查"到底有没有超时"时唯一能看的就是这行日志，
        // 因此这里由与 URL 相同的局部变量渲染，不写字面量副本（否则改 URL 忘改日志，
        // 日志会开始说谎）。
        plugin.getLogger().info("MySQL connection established: " + host + ":" + port
                + "/" + database + " (connectTimeout=" + connectTimeoutMs + "ms, socketTimeout="
                + socketTimeoutMs + "ms, autoReconnect=true, maxReconnects=" + maxReconnects
                + ", initialTimeout=" + initialTimeoutSec + "s)");
    }

    private void createTables() throws SQLException {
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_data (" +
                        "  uuid VARCHAR(36) PRIMARY KEY," +
                        "  player_name VARCHAR(32)," +
                        "  total_words_learned INT DEFAULT 0," +
                        "  total_sessions INT DEFAULT 0," +
                        "  total_score BIGINT DEFAULT 0," +
                        "  total_coins_earned BIGINT DEFAULT 0," +
                        "  total_coins_spent BIGINT DEFAULT 0," +
                        "  conversation_count INT DEFAULT 0," +
                        "  onboarding_step INT DEFAULT 0," +
                        "  wrong_book_cleared INT DEFAULT 0," +
                        // R-48：去规范化列 —— 该玩家「答过题的词数」（word_progress 里
                        // correct_count + incorrect_count > 0 的行数）。广场/权威名次用它
                        // 替代 O(N) 的 EXISTS 相关子查询，见 schema.sql 与该列上方的注释。
                        "  answered_count INT DEFAULT 0," +
                        // P3-03 收口：current_streak 为历史遗留列、零写入恒 0。
                        // 真源是 checkin_data.streak_count（每日签到连击）；本列保留不删
                        //（删列有 schema 变更风险），读侧已改走 checkin_data。
                        "  current_streak INT DEFAULT 0," +
                        "  longest_streak INT DEFAULT 0," +
                        // P3-16：last_login / created_at 为历史遗留列，全仓零读写（建表后
                        // 从未写入、无任何查询引用）。保留不删（数据永不删原则，同 P3-03
                        // 的"保留不删"先例）；如需注册漏斗统计再接线（P3-16）。
                        "  last_login BIGINT," +
                        "  created_at BIGINT," +
                        "  updated_at BIGINT" +
                        ")"
        );

        addColumnIfMissing(tablePrefix + "player_data", "total_coins_earned", "BIGINT DEFAULT 0");
        addColumnIfMissing(tablePrefix + "player_data", "total_coins_spent", "BIGINT DEFAULT 0");
        // R-28：AI 对话累计次数落盘列（供 AI_CHAT_10/50 成就重启后仍可判定）
        addColumnIfMissing(tablePrefix + "player_data", "conversation_count", "INT DEFAULT 0");
        // Phase 3 S-A：引导进度列（0=未开始，单调递增到 3=完结）
        addColumnIfMissing(tablePrefix + "player_data", "onboarding_step", "INT DEFAULT 0");
        // Phase 3 S-A：错题本累计攻克词数（WRONG_BOOK_CLEARED 成就，绝对值 UPSERT 写）
        addColumnIfMissing(tablePrefix + "player_data", "wrong_book_cleared", "INT DEFAULT 0");
        // R-48：去规范化列「答过题的词数」。补列后必须<b>立刻回填</b>存量数据 ——
        // 新列默认 0，若只补列不回填，所有存量玩家都会在广场/名次里被判定成
        // 「从没答过题」而整批消失（静默的、影响每一个老玩家）。
        // addColumnIfMissing 的返回值正是「本次是不是真的补了列」，据此只跑一次
        // （回填本身也是幂等的绝对值重算，重复执行不出错，只是没必要多一次全表 UPDATE）。
        //
        // 回填<b>不在这里执行</b>：下面的 word_progress 建表还没跑，而回填要读它。
        // 新库在 CREATE TABLE 里已含该列（addColumnIfMissing 返回 false），只有「老库补列」
        // 这一条路才会置位；真跑到这里时 word_progress 一定存在，但把顺序写死成
        // 「先建完所有表再回填」比依赖这个论证更稳。见 createTables 末尾。
        if (addColumnIfMissing(tablePrefix + "player_data", "answered_count", "INT DEFAULT 0")) {
            answeredCountColumnJustAdded = true;
        }

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "word_progress (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  word VARCHAR(64) NOT NULL," +
                        "  mastery DOUBLE DEFAULT 0.0," +
                        "  correct_count INT DEFAULT 0," +
                        "  incorrect_count INT DEFAULT 0," +
                        "  last_review_time BIGINT," +
                        "  review_count INT DEFAULT 0," +
                        "  UNIQUE(uuid, word)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "achievements (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  achievement_id VARCHAR(64) NOT NULL," +
                        "  unlocked_at BIGINT," +
                        "  UNIQUE(uuid, achievement_id)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "learning_log (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  session_type VARCHAR(32)," +
                        "  difficulty VARCHAR(16)," +
                        "  words_count INT," +
                        "  correct_count INT," +
                        "  score INT," +
                        "  started_at BIGINT," +
                        "  ended_at BIGINT" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_sessions (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  session_date DATE NOT NULL," +
                        "  words_count INT DEFAULT 0," +
                        "  correct_count INT DEFAULT 0," +
                        "  score BIGINT DEFAULT 0," +
                        "  mode VARCHAR(32)," +
                        "  review_words INT DEFAULT 0," +
                        "  UNIQUE(uuid, session_date)" +
                        ")"
        );
        // Phase 3 S-A：复习答对词数列（REVIEW_STREAK_7 判定；老库补列）
        addColumnIfMissing(tablePrefix + "player_sessions", "review_words", "INT DEFAULT 0");

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_profiles (" +
                        "  uuid VARCHAR(36) PRIMARY KEY," +
                        "  age INT DEFAULT 0," +
                        "  show_answers BOOLEAN DEFAULT 0," +
                        "  show_results BOOLEAN DEFAULT 1," +
                        "  show_in_square BOOLEAN DEFAULT 1," +
                        "  sounds_enabled BOOLEAN DEFAULT 1," +
                        "  reminders_enabled BOOLEAN DEFAULT 1" +
                        ")"
        );
        // Phase 3 S-A：per-player 音效开关（默认开；plan-audio-feedback §3.1）
        addColumnIfMissing(tablePrefix + "player_profiles", "sounds_enabled", "BOOLEAN DEFAULT 1");
        // R1/R4：per-player 提醒总开关（默认开 —— 保持既有玩家上线行为不变；老库零迁移补列）
        addColumnIfMissing(tablePrefix + "player_profiles", "reminders_enabled", "BOOLEAN DEFAULT 1");

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_achievements (" +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  achievement_id VARCHAR(64) NOT NULL," +
                        "  unlocked_at BIGINT NOT NULL," +
                        "  PRIMARY KEY (uuid, achievement_id)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_challenges (" +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  challenge_date DATE NOT NULL," +
                        "  challenge_id VARCHAR(32) NOT NULL," +
                        "  progress INT DEFAULT 0," +
                        "  completed BOOLEAN DEFAULT 0," +
                        "  claimed BOOLEAN DEFAULT 0," +
                        // D9：1 = 系统自动放弃（degradeStale 在写 completed=1/claimed=1 时一并置 1）。
                        // 报表 countDailyChallengesOrFail 的分子据此剔除，否则「没做完被降级」
                        // 与「玩家真完成」同形，家长报表的完成数系统性偏高。用 INT 不用 BOOLEAN：
                        // MySQL/SQLite 双后端默认值与读取口径统一（同 seasons.ledger_pending）。
                        "  abandoned INT DEFAULT 0," +
                        "  PRIMARY KEY (uuid, challenge_date, challenge_id)" +
                        ")"
        );
        // D9 老库补列：存量库的 daily_challenges 没有 abandoned，不补则上面那列只在
        // 全新安装存在 —— 升级服第一次跑 degradeStale / 报表 Q8 就在 prepare 阶段抛
        // `no such column: abandoned`（R14 缺陷的同款形态，见 SchemaSqlConsistencyTest）。
        addColumnIfMissing(tablePrefix + "daily_challenges", "abandoned", "INT DEFAULT 0");

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_challenge_words (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  challenge_date DATE NOT NULL," +
                        "  word_id VARCHAR(64) NOT NULL," +
                        "  completed BOOLEAN DEFAULT FALSE," +
                        "  completed_at TIMESTAMP NULL," +
                        "  UNIQUE(player_uuid, challenge_date, word_id)" +
                        ")"
        );

        // 修复批 A（R10-H1）：单词挑战奖励"已付"台账 —— 内存 Set（wordChallengeClaimedToday）
        // 之外的重启防重复真源：内存清空后按日期查库防同日重发。幂等写入
        // （INSERT OR IGNORE / INSERT IGNORE），UNIQUE(player_uuid, challenge_date)
        // 兜底"每人每日至多一行"；/lea reset 清收随 deletePlayerData 的 player_uuidTables。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_word_reward_claims (" +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  challenge_date DATE NOT NULL," +
                        "  claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE(player_uuid, challenge_date)" +
                        ")"
        );

        // Block C2（R2-M2）：今日 AI 轮次计数器持久化 —— 原内存 TodayCounter 重启/换服/
        // quit-rejoin 即清零，daily-round-limit（默认 30）可无限绕过（API 成本面）。
        // 与 daily_word_reward_claims 同式"重启后真源"：UNIQUE(player_uuid, round_date)
        // 兜底每人每日至多一行；/lea reset 清收随 deletePlayerData 的 player_uuidTables。
        // 旧行由 cleanupOldChatHistory 同窗口清理。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "ai_round_counters (" +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  round_date DATE NOT NULL," +
                        "  rounds INT NOT NULL DEFAULT 0," +
                        "  UNIQUE(player_uuid, round_date)" +
                        ")"
        );

        // Round 17：今日答题计数器持久化 —— 与上面 ai_round_counters 完全同构的
        // "重启后真源"。此前 PlayerDataStore.todayAnswerCountCache 是**纯内存**：
        // 它的唯一判据消费者是赏金门 word-system.daily-limit（默认 50），而 quit 会
        // remove 该条目（unloadPlayerData）、重启内存归零、多子服各算各的 ——
        // 「退服重进」在<b>单服</b>即可把当日答题额度刷满，不依赖 MySQL。
        // 存量数据迁移：本表新建时为空，老玩家升级当天 answer_date=今天 无行 →
        // seed 读到 0 → 升级当天额度全满（一天的一次性影响，可接受；不做回填，
        // 因为旧的内存计数从未落盘，没有可回填的真值）。
        // 旧行由 cleanupOldChatHistory 同窗口清理；/lea reset 清收随 deletePlayerData
        // 的 playerUuidTables。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_answer_counters (" +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  answer_date DATE NOT NULL," +
                        "  answers INT NOT NULL DEFAULT 0," +
                        "  UNIQUE(player_uuid, answer_date)" +
                        ")"
        );

        // R10 战略锐评（决斗零持久化）：决斗战绩台账 —— 一场一行的追加式事实表。
        // 原实现只有内存 DuelRoom，赢家只拿一个成就、无战绩/胜率/连胜可查，
        // 也无"复仇"的对手关系可回溯。本表只追加（无 UPDATE/DELETE 业务路径），
        // 保留期 365 天（决斗数据量极小，见 cleanupOldChatHistory）。
        // 双后端 DDL 分支：SQLite INTEGER PRIMARY KEY AUTOINCREMENT /
        // MySQL BIGINT AUTO_INCREMENT PRIMARY KEY（同 word_progress 等表的 isSQLite 分支写法）。
        // duel_date 存 yyyy-MM-dd 字符串（与 daily_word_reward_claims 的 DATE 列不同：
        // 本表由 recordDuelResult 用 LocalDate.now() 显式写入，字符串在两后端可精确比较）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "duel_stats (" +
                        "  id " + (isSQLite()
                                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                                : "BIGINT AUTO_INCREMENT PRIMARY KEY") + "," +
                        "  winner_uuid VARCHAR(36) NOT NULL," +
                        "  loser_uuid VARCHAR(36) NOT NULL," +
                        "  winner_score INT NOT NULL," +
                        "  loser_score INT NOT NULL," +
                        "  duel_date VARCHAR(10) NOT NULL," +
                        "  created_at BIGINT NOT NULL" +
                        ")"
        );
        // 赢/输两侧各一条 (uuid, duel_date) 复合索引 —— 供"我的战绩/胜率/连胜"
        // 按人 + 日期窗查询（含 OR 两列的战绩聚合走 index merge / 两次索引扫）。
        // 经 createIndexIfMissing（PF-SEA-02：MySQL 不支持 CREATE INDEX IF NOT EXISTS）。
        createIndexIfMissing("idx_duel_winner_date",
                "duel_stats(winner_uuid, duel_date)");
        createIndexIfMissing("idx_duel_loser_date",
                "duel_stats(loser_uuid, duel_date)");
        createIndexIfMissing("idx_duel_created",
                "duel_stats(created_at)");

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "ai_chat_history (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  session_id VARCHAR(64) NOT NULL," +
                        "  role VARCHAR(16) NOT NULL," +
                        "  content TEXT NOT NULL," +
                        "  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")"
        );

        // US-FB §3.1（Wave 3 批次 3B）：会话级 AI 对话反馈 —— 先本地统计（source='local'）
        // 兜底落库，AI 点评异步到达后 UPSERT 覆写为 source='ai' + payload。
        // UNIQUE(player_uuid, session_id) 兼作唯一查询形态的索引；不落"未达标"行。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "ai_chat_feedback (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  session_id VARCHAR(64) NOT NULL," +
                        "  scenario_key VARCHAR(64)," +
                        "  difficulty_key VARCHAR(32)," +
                        "  user_messages INT DEFAULT 0," +
                        "  ai_messages INT DEFAULT 0," +
                        "  duration_seconds INT DEFAULT 0," +
                        "  source VARCHAR(8) NOT NULL DEFAULT 'local'," +
                        "  ai_payload TEXT," +
                        "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE(player_uuid, session_id)" +
                        ")"
        );

        // Indexes for ai_chat_history
        // PF-SEA-02（Phase 3 规划期发现，编排者即修）：CREATE INDEX IF NOT EXISTS 是
        // MariaDB 扩展，Oracle MySQL 不支持——异常原被 fine 吞掉，MySQL 后端索引可能
        // 从未建立。MySQL 分支改为先查 information_schema.statistics 判存在再建；
        // SQLite 分支保持原样（原生支持 IF NOT EXISTS）。
        createIndexIfMissing("idx_chat_player_time",
                "ai_chat_history(player_uuid, timestamp)");
        createIndexIfMissing("idx_chat_session",
                "ai_chat_history(session_id)");
        // Round 21：保留期清理专用索引（与 schema.sql 的同名索引一一对应）。
        //
        // cleanupOldChatHistory 的第一条语句是
        //   DELETE FROM <p>ai_chat_history WHERE timestamp < <30 天前>
        // 上面两个索引的前导列是 player_uuid / session_id，都不以 timestamp 开头。
        //
        // SQLite 侧的实际后果比"全表扫"更微妙：planner 会用 (player_uuid, timestamp)
        // 做 skip-scan（EXPLAIN 显示 `SEARCH ... USING INDEX idx_chat_player_time
        // (ANY(player_uuid) AND timestamp<?)`），代价 ≈ 前导列去重值个数 × 索引探测。
        // 实测（10 万行、7 次取中位数、真 WAL 库）：
        //   40 名玩家 0.25 ms / 400 名 2.2 ms / 5000 名 65.6 ms —— 玩家数越多越慢，
        //   而 5000 人是本插件面向的"万人服"量级真实形状。
        // 加本索引后三种规模一律 0.1 ms（恒为单个时间范围 seek）。
        //
        // MySQL/InnoDB 没有 skip-scan，过去只能在 3 条二级索引里挑一条做全扫。
        // 这是纯性能索引，删掉它不会有任何测试变红（本仓 MEMORY 的教训：删索引后
        // 506 个测试全绿），故由 ChatCleanupIndexTest 直接对真库断言其存在。
        createIndexIfMissing("idx_chat_timestamp",
                "ai_chat_history(timestamp)");
        // PF-SEA-03：player_sessions 被周/月/赛季/streak/日历大量按日期范围查询，
        // 补 (uuid, session_date) 复合索引防全表扫。
        createIndexIfMissing("idx_sessions_uuid_date",
                "player_sessions(uuid, session_date)");
        createIndexIfMissing("idx_sessions_date",
                "player_sessions(session_date)");

        // Difficult words / Wrong Book table
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "difficult_words (" +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  word VARCHAR(100) NOT NULL," +
                        "  wrong_count INTEGER DEFAULT 0," +
                        "  added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  PRIMARY KEY (player_uuid, word)" +
                        ")"
        );

        // Daily check-in table
        // P3-06：checkin_data 仅保存当前态（last_checkin_date + streak_count），
        // 无逐日历史行——UPSERT 覆盖式写入，昨日之前不可回溯。"本周签到 X 天"
        // 类需求需从 player_sessions 推导（学习日次口径）或另建历史表（P3-06）；
        // social_feed 的 CHECKIN 动态带 social.enabled/隐身门且有保留期清理，
        // 不能当统计真源。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "checkin_data (" +
                        "  player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                        "  last_checkin_date DATE NOT NULL," +
                        "  streak_count INTEGER DEFAULT 1" +
                        ")"
        );

        // Wave 2 域 4（广场社交）：学习动态流 —— 每玩家 × 每自然日 × 每事件类型
        // UPSERT 合并为 1 行（防刷屏 = 结构聚合，见 plan-social §1.4）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "social_feed (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  feed_date DATE NOT NULL," +
                        "  event_type VARCHAR(16) NOT NULL," +
                        "  events_count INT DEFAULT 1," +
                        "  sum_correct INT DEFAULT 0," +
                        "  sum_total INT DEFAULT 0," +
                        "  last_mode VARCHAR(16)," +
                        "  last_streak INT DEFAULT 0," +
                        "  achievement_id VARCHAR(64)," +
                        "  cheer_count INT DEFAULT 0," +
                        "  created_at BIGINT NOT NULL," +
                        "  updated_at BIGINT NOT NULL," +
                        "  UNIQUE(player_uuid, feed_date, event_type)" +
                        ")"
        );
        // Wave 2 域 4：鼓励记录 —— 一人一条动态至多一次（UNIQUE 硬约束）。
        // 注意：玩家列名是 cheerer_uuid（既有的 uuid / player_uuid 两套之外第三套，
        // 见 deletePlayerData 的顺序敏感特殊处理）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "social_cheers (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  feed_id INTEGER NOT NULL," +
                        "  cheerer_uuid VARCHAR(36) NOT NULL," +
                        "  created_at BIGINT NOT NULL," +
                        "  read_by_owner BOOLEAN DEFAULT 0," +
                        "  UNIQUE(feed_id, cheerer_uuid)" +
                        ")"
        );
        // 索引经 createIndexIfMissing（PF-SEA-02：MySQL 不支持 CREATE INDEX IF NOT EXISTS）
        createIndexIfMissing("idx_feed_order",
                "social_feed(feed_date, updated_at)");
        createIndexIfMissing("idx_feed_player",
                "social_feed(player_uuid, feed_date)");
        // getFeedCacheRows 的 ORDER BY updated_at DESC + LIMIT：没有这条单列索引时，
        // (feed_date, updated_at) 满足不了全局 updated_at 序（范围谓词下索引序 ≠ 全局序），
        // 必须全扫 + 临时 B 树排序。有了它，倒序扫到 LIMIT 行即停。
        createIndexIfMissing("idx_feed_updated",
                "social_feed(updated_at)");
        createIndexIfMissing("idx_cheers_feed",
                "social_cheers(feed_id)");
        createIndexIfMissing("idx_cheers_cheerer",
                "social_cheers(cheerer_uuid)");

        // Wave 3 域 5 批次 A（赛季）：结算台账两表。
        // seasons = 每季 1 行的结算头（settled_at NULL=未结算，条件 UPDATE 抢占结算权）；
        // season_rewards = 每人一行的发放台账（防重复 + 跨服/离线补发队列）。
        // 赛季榜本身零新表（复用 player_sessions 按日期窗聚合，plan-season §1.3）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "seasons (" +
                        "  season_id INT PRIMARY KEY," +
                        "  start_date DATE NOT NULL," +
                        "  end_date DATE NOT NULL," +
                        "  settled_at BIGINT," +
                        "  top_snapshot TEXT," +
                        // R12d-F7：结算意图标记（0 = 本季不欠台账；1 = 抢占时欠一份台账且尚未确认落库）。
                        // 纯内存的 pendingReleaseRetries 随 SeasonManager 一起被 /lea reload 或重启
                        // 丢掉，而脏 claim 留在库里会把游标推过该季 → 奖励永久丢失。本列是唯一
                        // 能跨重启区分「写失败欠账」与「本季本来就不发奖」的持久判据，
                        // 见 loadSeasonsWithOwedLedger() 与 SeasonManager.rebuildPendingReleases()。
                        "  ledger_pending INT DEFAULT 0," +
                        // R-16：抢占者标识（DatabaseManager.nodeId —— 节点级，跨重启稳定）。
                        // settled_at 是从共享库读的，不是本服的 token —— 只按 settled_at fencing
                        // 会把**他服**正在结算的 live claim 当成自己的释放掉（重复结算 + 重复广播）。
                        // 与 settled_at 同一条 UPDATE 写入，两者必须同生同灭。
                        "  claim_owner VARCHAR(36)" +
                        ")"
        );
        // 老库补列（R3-L4 幂等补列原语，SQLite/MySQL 双后端；新库上面的 CREATE 已含此列）。
        // 返回值记下来：true = 本次启动是第一个跑到含该列版本的（老库升级），
        // 「升级前就已抢占但无台账」的遗留欠账必须做一次性人工对账，见
        // findLegacySeasonsWithClaimAndNoLedger() / consumeSeasonLedgerPendingColumnAdded()。
        if (addColumnIfMissing(tablePrefix + "seasons", "ledger_pending", "INT DEFAULT 0")) {
            seasonLedgerPendingJustAdded.set(true);
        }
        // R-16 老库补列：claim_owner VARCHAR(36)，可空且无默认值（NULL = 升级前的旧行，
        // 语义是「持有者未知」，由 SeasonManager.rebuildPendingReleases 按年龄裁定）。
        // 与 ledger_pending 不同，本列<b>不需要</b>一次性人工对账：无法判定持有者的行
        // 仍能靠 settled_at 的年龄决定要不要释放（见 FOREIGN_CLAIM_STALE_MS）。
        addColumnIfMissing(tablePrefix + "seasons", "claim_owner", "VARCHAR(36)");
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "season_rewards (" +
                        "  season_id INT NOT NULL," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  rank_position INT NOT NULL," + // 列名避开 SQL 保留字 rank
                        "  score BIGINT NOT NULL," +
                        "  reward_coins BIGINT NOT NULL," + // 单位"分"（对齐 RewardEngine 厘级记账）
                        "  claimed BOOLEAN DEFAULT 0," +
                        "  claimed_at BIGINT," +
                        "  PRIMARY KEY (season_id, uuid)" +
                        ")"
        );
        // 补发查询按 uuid 走（PK 前导是 season_id，非前缀），与既有索引同走
        // createIndexIfMissing（PF-SEA-02：MySQL 不支持 CREATE INDEX IF NOT EXISTS）。
        createIndexIfMissing("idx_season_rewards_uuid",
                "season_rewards(uuid)");

        // Wave 4 US-CLS-1（班级系统）：classes = 班级头（class_name 作业务键 UNIQUE，
        // 建/删/查全走班名）；class_members = 成员关系（一人一班由调用方编排，
        // Data 层只管单班成员行；PK(class_id, uuid) 兼作唯一索引）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "classes (" +
                        "  class_id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  class_name VARCHAR(32) NOT NULL UNIQUE," +
                        "  display_name VARCHAR(64)," +
                        "  created_by VARCHAR(36) NOT NULL," +
                        "  created_at BIGINT NOT NULL" +
                        ")"
        );
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "class_members (" +
                        "  class_id INT NOT NULL," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  joined_at BIGINT NOT NULL," +
                        "  PRIMARY KEY (class_id, uuid)" +
                        ")"
        );
        // 一人一班反查（getPlayerClassId 按 uuid 查；PK 前导 class_id 非前缀）
        createIndexIfMissing("idx_class_members_uuid",
                "class_members(uuid)");

        // Block C2（A6/R9-M4）：player_data(player_name) 索引 —— getKnownPlayerNames
        // （AdminCommandHandler tab 补全）按 player_name LIKE 前缀查，无索引则主线程
        // 全表扫 + 持锁阻塞（每次按键）。createIndexIfMissing 已处理双后端查重与
        // 失败静默（索引非致命）。
        createIndexIfMissing("idx_player_data_name",
                "player_data(player_name)");

        // 性能：player_data 排行榜列索引 —— 广场/排行榜三个查询
        // （getActiveSquareEntries/getRankedPlayers 的 total_score，getTopCoins 的
        // total_coins_earned，getTopWordsLearned 的 total_words_learned，getTopCombo 的
        // longest_streak）都靠 ORDER BY ... DESC LIMIT n，无索引即全表扫 + 排序
        // （10 万行实测约 315ms，有索引约 0.65ms）。索引一律升序定义：SQLite 与
        // MySQL 8 都能反向扫描满足 DESC，避免 DESC 索引的兼容性差异。
        createIndexIfMissing("idx_player_data_score",
                "player_data(total_score)");
        createIndexIfMissing("idx_player_data_coins",
                "player_data(total_coins_earned)");
        createIndexIfMissing("idx_player_data_words",
                "player_data(total_words_learned)");
        createIndexIfMissing("idx_player_data_streak",
                "player_data(longest_streak)");

        // R-48：老库刚补了 answered_count 列 → 先回填存量数据，<b>再</b>建下面那条带
        // answered_count 的复合索引。顺序是刻意的：反过来的话那 5 万行的 UPDATE 要为每一行
        // 维护一次刚建好的索引（纯浪费），而且索引里会先写进一批全是 0 的版本。
        if (answeredCountColumnJustAdded) {
            // 只有回填<b>成功</b>才清标记。原实现在调用前就置 false，于是回填一旦因
            // 数据库抖动失败（表锁、只读挂载、连接刚重连），重启后列已存在 →
            // addColumnIfMissing 返回 false → 标记不再置位 → <b>永远不再尝试回填</b>。
            // 而失败后果是所有老玩家的 answered_count 停在 0，从广场和 %lle_rank%
            // 整批消失，只有一条 SEVERE 日志能提示服主手工执行 SQL。
            // 改成「成功才清」后，下次启动会自动重试（回填本身是绝对值重算，幂等）。
            if (backfillAnsweredCount()) {
                answeredCountColumnJustAdded = false;
            }
        }

        // R-48：index 选择依据实测（不是猜的）。见 schema.sql 里该索引上方的完整测量。
        // 一句话：广场/名次的三条查询都是「answered_count > 0 过滤 + total_score 排序」，
        // 唯一被 planner 用上且<b>不劣化</b>的形状是 (total_score, answered_count) ——
        // 它让 ORDER BY 段免掉一次 filesort；单独 answered_count 索引反而让 SQLite 的
        // 广场取页从 0.3 ms 掉到 122 ms（改走 answered_count 索引后必须临时 B 树排序）。
        // 所以这里只加这一条，<b>不要</b>再加 idx_player_data_answered。
        createIndexIfMissing("idx_player_data_score_answered",
                "player_data(total_score, answered_count)");
    }

    /**
     * R-48：{@code player_data.answered_count} 的一次性回填 —— 只在「本次启动刚补出该列」时跑。
     *
     * <p><b>为什么必须有它</b>：新列 DDL 默认值是 0，而读侧改走 {@code answered_count > 0}
     * 之后，0 = 「从没答过题」= 排在广场/名次之外。不跑回填，升级后的第一秒所有存量玩家
     * 就集体从广场上消失了 —— 无报错、无日志、只是"人不见了"。
     *
     * <p><b>重新加列即重新回填</b>：{@link #addColumnIfMissing} 的返回值是「这一次真的补了列」，
     * 因此本方法天然只在升级那一次执行（重启时列已存在 → 返回 false → 不跑）。
     * 回填语句本身也是<b>幂等</b>的（绝对值重算，不是累加），重放不会跑偏；这一点是刻意
     * 选的：任何"增量"写法在重复执行时都会翻倍。
     *
     * <p><b>方言</b>：整条是相关子查询 UPDATE，<b>刻意不写成 UPDATE ... JOIN (派生表)</b> ——
     * 派生表那种写法在 MySQL/MariaDB 上同量级（实测 7.0 s vs 6.4 s），但 SQLite
     * 既不支持 {@code UPDATE ... JOIN} 也不支持对派生表做 UPDATE，于是它需要两份 SQL 而不是
     * 一份。而<b>本语句文法是三方言（SQLite / MySQL / MariaDB）唯一的交集</b>，且刻意不用
     * {@code GROUP BY}（本仓刚踩过 MariaDB 1055 ONLY_FULL_GROUP_BY 的坑）。
     * 目标表用<b>不带别名的全名限定</b>（{@code wp.uuid = <prefix>player_data.uuid}）：
     * SQLite 不接受 {@code UPDATE t alias ... WHERE wp.uuid = t.uuid} 里的别名前缀，
     * 而全名限定三家都认。已在本机 MariaDB 13.0 与 sqlite-jdbc 3.45 上分别实证
     * （5 万玩家 / 100 万词行：MariaDB 6.4 s、SQLite 1.5 s）。
     *
     * <p><b>失败不阻断启动</b>：回填是"修复存量"，跑不动（磁盘满/超时）不该让整个插件起不来。
     * 但也不能静默 —— 失败时记 SEVERE 并在日志里写清楚后果（老玩家的广场/名次会缺席），
     * 且下次重启不会自动重试（列已存在）。要人工修可执行日志里那条 SQL。
     */
    /**
     * 回填 {@code player_data.answered_count}。
     *
     * @return {@code true} 表示回填成功（调用方可据此清「待回填」标记）；
     *         {@code false} 表示失败且已记 SEVERE —— 调用方<b>必须保留</b>标记，
     *         让下次启动重试。回填是绝对值重算，重放不变。
     */
    private boolean backfillAnsweredCount() {
        String sql = "UPDATE " + tablePrefix + "player_data SET answered_count = ("
                + "SELECT COUNT(*) FROM " + tablePrefix + "word_progress wp"
                + " WHERE wp.uuid = " + tablePrefix + "player_data.uuid"
                + " AND wp.correct_count + wp.incorrect_count > 0)";
        long startedAt = System.currentTimeMillis();
        try {
            executeUpdate(sql);
            plugin.getLogger().info("Backfilled " + tablePrefix + "player_data.answered_count in "
                    + (System.currentTimeMillis() - startedAt) + " ms (one-time migration).");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to backfill " + tablePrefix + "player_data.answered_count. "
                            + "Players whose rows keep answered_count = 0 will be missing from the "
                            + "English Square and from %lle_rank% until this is fixed. "
                            + "This will be retried on the next server start; you can also run it "
                            + "manually after the database recovers: " + sql, e);
            return false;
        }
    }

    /**
     * PF-SEA-02/03：幂等建索引 —— SQLite 用原生 {@code CREATE INDEX IF NOT EXISTS}；
     * MySQL（Oracle 版，不支持 IF NOT EXISTS）先查 information_schema.statistics
     * 判存在再建。任何失败只 fine 记录（索引非致命），绝不打断建表主流程。
     *
     * @param indexName 索引名（不含表前缀，方法内部拼）
     * @param target    形如 {@code "table(col1, col2)"} 的目标（表名不含前缀）
     */
    private void createIndexIfMissing(String indexName, String target) {
        String fullIndex = tablePrefix + indexName;
        int paren = target.indexOf('(');
        String tablePart = tablePrefix + target.substring(0, paren);
        String columnsPart = target.substring(paren);
        try {
            if (isSQLite()) {
                executeUpdate("CREATE INDEX IF NOT EXISTS " + fullIndex
                        + " ON " + tablePart + columnsPart);
                return;
            }
            // MySQL：information_schema 查重（当前库内）。
            // E-04 范式：取连接与语句执行整体收进 connectionLock（executeUpdate 同锁可重入）。
            synchronized (connectionLock) {
                String checkSql = "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
                try (java.sql.PreparedStatement stmt = getConnection().prepareStatement(checkSql)) {
                    stmt.setString(1, tablePart);
                    stmt.setString(2, fullIndex);
                    try (java.sql.ResultSet rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            return; // 已存在
                        }
                    }
                }
                try (java.sql.PreparedStatement stmt = getConnection()
                        .prepareStatement("CREATE INDEX " + fullIndex + " ON " + tablePart + columnsPart)) {
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().fine("Index " + fullIndex + " skipped: " + e.getMessage());
        }
    }

    /**
     * Get the database connection.
     *
     * <p>E-04：取连接整体收进 {@code connectionLock} 内（正确范式）。
     * 原快路径在锁外做 {@code isClosed} 检查并直接返回引用，调用方在锁外拿到的
     * Connection 可能在后续被 {@link #initialize()} 的重连替换，导致 Statement
     * 打在已关闭的旧连接上。现在统一走锁内获取 + 必要时自愈重连。
     * 注意：调用方仍须在 {@code synchronized (getConnectionLock())} 内使用返回的
     * 连接（锁外持有引用跨越重连仍不安全），外部裸用点见类 javadoc 的迁移约定。
     */
    public Connection getConnection() {
        synchronized (connectionLock) {
            if (shuttingDown) {
                return UNAVAILABLE;
            }
            try {
                if (connection == null || connection.isClosed()) {
                    initialize();
                }
            } catch (SQLException | RuntimeException e) {
                // EH-01：运行期自愈失败不抛 RuntimeException（它逃出全类 catch(SQLException)，
                // 并越过 synchronized 内、try 外的 18 个裸取连接点的方法边界）。
                plugin.getLogger().log(Level.SEVERE,
                        "Database self-heal failed; serving an unavailable connection", e);
                return UNAVAILABLE;
            }
            if (connection == null) {
                plugin.getLogger().severe("Database connection is null after initialization.");
                return UNAVAILABLE;
            }
            return connection;
        }
    }

    /** EH-01：连接替身——除 isClosed/close/Object 外一律抛 SQLException（全类唯一已写好的契约）。 */
    private static final Connection UNAVAILABLE = (Connection) java.lang.reflect.Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, m, args) -> {
                String n = m.getName();
                if ("isClosed".equals(n)) return Boolean.FALSE;
                if ("close".equals(n)) return null;
                if ("equals".equals(n)) return proxy == args[0];
                if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                if ("toString".equals(n)) return "UnavailableConnection";
                throw new SQLException("Database connection unavailable: " + n);
            });

    /**
     * E-05：关连接工具。判 null + {@code isClosed}，异常吞掉记 WARNING。
     * 调用方须持有 {@code connectionLock}（重连与关闭串行化）。
     */
    private void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to close stale database connection (continuing with re-init)", e);
        }
    }

    /**
     * Execute an update statement.
     */
    public void executeUpdate(String sql) throws SQLException {
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.executeUpdate();
            }
        }
    }

    // ============================================================
    //  AI Chat History
    // ============================================================

    /**
     * Insert a single chat message into the history table.
     *
     * <p><b>R11：返回 boolean 成败（原为 {@code void}）。</b>原实现自己
     * {@code catch (SQLException)} 只记 WARNING，调用方（{@code AIChatManager} 三处写
     * system/user/assistant 行）的 {@code catch (Exception)} <b>永远看不到纯 SQL 失败</b>
     * （磁盘满 / 表锁 / 连接断），而它们失败时只走 {@code debugLog} —— 默认
     * {@code debug=false}，于是整段聊天记录静默缺行。照 {@link #upsertChatFeedback} 的
     * R11 范式改成有返回值：失败仍记 WARNING 保留现场，同时把成败交给调用方。
     *
     * <p><b>调用方不应做补偿重试</b>（与 {@code upsertChatFeedback} 不同；理由写在这里，
     * 免得下一个人照抄错范式）：
     * <ol>
     *   <li>本表<b>没有唯一键</b>（主键是自增 {@code id}，无 UNIQUE），重放一条「其实已提交、
     *       只是异常发生在提交之后」的行会让同一句话在聊天记录里出现两次 —— 补偿反成缺陷；</li>
     *   <li>失败不改变任何权威状态：聊天记录只是 {@code /lea chatlog} / GUI / 会话数统计的
     *       派生展示数据，不参与发钱、成就判定、日限或任何幂等门，丢行只会「记录缺一段」；</li>
     *   <li>补偿队列要存整段对话正文，而失败最常见的形态是「DB 整段时间不可用」，
     *       此时每次对话的每条消息都失败 —— 队列没有天然上界，内存代价失控。
     *       对照 {@code ai_chat_feedback} 的补偿只存「每会话一行汇总」，天然有界。</li>
     * </ol>
     * 结论：{@code false} 时调用方记一条 WARNING（可观测）即可，<b>不要</b>入队重放。
     * 实现见 {@code AIChatManager.warnChatHistoryWriteFailed}。
     *
     * @param playerUuid the player
     * @param sessionId  the session id
     * @param role       {@code system} / {@code user} / {@code assistant}
     * @param content    message body
     * @return {@code true} = 已写入；{@code false} = 写库失败（SQLException 已记带堆栈的
     *         WARNING，调用方只需补一条不带堆栈的语义日志）
     */
    public boolean insertChatHistory(UUID playerUuid, String sessionId, String role, String content) {
        String sql = "INSERT INTO " + tablePrefix + "ai_chat_history"
                + " (player_uuid, session_id, role, content) VALUES (?, ?, ?, ?)";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, sessionId);
                stmt.setString(3, role);
                stmt.setString(4, content);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to insert chat history for player " + playerUuid, e);
                return false;
            }
        }
    }

    /**
     * Get all messages for a specific session, ordered by timestamp ascending.
     *
     * <p>R-16（排序键唯一性）：{@code timestamp} 是
     * {@code TIMESTAMP DEFAULT CURRENT_TIMESTAMP}（见 {@code createTables}，秒级精度、
     * 无小数秒），<b>不是唯一键</b>。而同一轮的 system / user / assistant 三行由三条
     * 独立的 {@code CompletableFuture.runAsync} 写入（AIChatManager 侧三个写入点），
     * 提交顺序与落库顺序之间<b>没有任何保证</b>——一轮对话的多行几乎必然落在同一秒内，
     * 于是只按 {@code timestamp} 排序时，MySQL 可返回任意顺序，历史 GUI 会渲染成
     * 「AI 先答、玩家后问」；调度器饱和时 user 行甚至可能<b>晚于</b> assistant 行落库。
     * 修法：补 {@code id}（AUTOINCREMENT / AUTO_INCREMENT 自增主键）作为第二排序键，
     * 把"同秒内"收敛成真实插入序。自增 id 与插入序在 SQLite/MySQL 两后端同义，
     * 且 {@code id} 已在投影里（本方法本来就选它），无需改 SELECT 列表。
     * 先例见 {@code getDuelWinStreak} 的 {@code ORDER BY created_at DESC, id DESC}。
     */
    public List<ChatHistoryEntry> getChatSession(UUID playerUuid, String sessionId) {
        List<ChatHistoryEntry> entries = new ArrayList<>();
        String sql = "SELECT id, player_uuid, session_id, role, content, timestamp"
                + " FROM " + tablePrefix + "ai_chat_history"
                + " WHERE player_uuid = ? AND session_id = ?"
                + " ORDER BY timestamp ASC, id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new ChatHistoryEntry(
                                rs.getInt("id"),
                                rs.getString("player_uuid"),
                                rs.getString("session_id"),
                                rs.getString("role"),
                                rs.getString("content"),
                                rs.getTimestamp("timestamp")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get chat session " + sessionId + " for player " + playerUuid, e);
            }
        }
        return entries;
    }

    /**
     * Get distinct sessions for a player, paginated. Each entry represents one session.
     * Returns the session_id, the earliest timestamp, and the system prompt for scenario extraction.
     */
    public List<ChatHistoryEntry> getChatSessions(UUID playerUuid, int page, int pageSize) {
        List<ChatHistoryEntry> entries = new ArrayList<>();
        // Get distinct session IDs with their timestamps, most recent first
        String sql = "SELECT session_id, MIN(timestamp) AS first_time, MAX(timestamp) AS last_time"
                + " FROM " + tablePrefix + "ai_chat_history"
                + " WHERE player_uuid = ?"
                + " GROUP BY session_id"
                + " ORDER BY last_time DESC"
                + " LIMIT ? OFFSET ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setInt(2, pageSize);
                stmt.setInt(3, (page - 1) * pageSize);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // Use a ChatHistoryEntry with session_id as the primary identifier
                        // timestamp = last_time for display, content = placeholder (to be filled)
                        entries.add(new ChatHistoryEntry(
                                0,
                                playerUuid.toString(),
                                rs.getString("session_id"),
                                "session",
                                "",
                                rs.getTimestamp("last_time")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get chat sessions for player " + playerUuid, e);
            }
        }
        return entries;
    }

    /**
     * Get the system prompt and first user message for a session (used for preview).
     *
     * <p>R-16（排序键唯一性）：同 {@link #getChatSession} —— {@code timestamp} 秒级精度
     * 且非唯一，本方法又靠 {@code LIMIT 2} 取"最早两行"，同秒并列时取到哪两行由数据库
     * 任意决定，预览可能拿到<b>非首句</b>（role 判定随后还会让 {@code result[1]} 缺位）。
     * 补 {@code id ASC} 作第二排序键把顺序钉成真实插入序。
     * {@code id} 不在 SELECT 投影里——SQL 允许按未列出列排序（无 DISTINCT / UNION），
     * 且无需把 id 带出去，故不动投影。
     */
    public String[] getSessionPreview(UUID playerUuid, String sessionId) {
        String[] result = new String[2]; // [0] = system content, [1] = first user content
        String sql = "SELECT role, content FROM " + tablePrefix + "ai_chat_history"
                + " WHERE player_uuid = ? AND session_id = ?"
                + " AND role IN ('system', 'user')"
                + " ORDER BY timestamp ASC, id ASC"
                + " LIMIT 2";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString("role");
                        if ("system".equals(role) && result[0] == null) {
                            result[0] = rs.getString("content");
                        } else if ("user".equals(role) && result[1] == null) {
                            result[1] = rs.getString("content");
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get session preview for " + sessionId, e);
            }
        }
        return result;
    }

    /**
     * Get the total count of distinct sessions for a player.
     */
    public int getChatHistoryCount(UUID playerUuid) {
        String sql = "SELECT COUNT(DISTINCT session_id) FROM " + tablePrefix
                + "ai_chat_history WHERE player_uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to count chat history for player " + playerUuid, e);
            }
        }
        return 0;
    }

    /**
     * Delete chat history records older than the specified number of days.
     *
     * <p>修复批 B（R3-H2 清理侧）：同窗口一并清 {@code ai_chat_feedback}
     * （created_at 与 ai_chat_history.timestamp 同为 CURRENT_TIMESTAMP 写入，
     * 同一 {@code dateExpr} 阈值可直接套用），防该表无限增长。保留期窗口语义 =
     * "早于阈值行"（非精确边界）：两后端各自按库内时钟自洽，SQLite（UTC 字符串）与
     * MySQL（会话时区 TIMESTAMP）间的 8h 级偏移可接受，不断言被删行的时区精确性。
     *
     * <p>修复批 D（R3-L2）：天数经占位符绑定（原为字符串拼接；唯一调用传常量 30
     * 无注入面，此处对齐全仓参数化惯例）——SQLite 绑字符串 modifier（"-N days"）、
     * MySQL 绑 int，见 {@link #bindKeepDays}。
     *
     * <p><b>Round 21：本方法整段在 {@code connectionLock} 内执行</b>（单连接无池，
     * 该锁全局唯一且 {@code synchronized} 无超时），因此这 7 条 DELETE 里任何一条
     * 变慢，全服所有 DB 操作都会排队等它。玩家可见症状 = 「午夜整点卡一下」，
     * 且随数据量单调恶化。
     *
     * <p>逐条的索引可用性（真库 {@code EXPLAIN QUERY PLAN} 实测，非推断）：
     * <ul>
     *   <li><b>sql1 {@code ai_chat_history}</b> —— 原无 {@code timestamp} 前导索引，
     *       SQLite 退化成 {@code (player_uuid, timestamp)} 上的 skip-scan，代价随
     *       <b>玩家数</b>增长（10 万行：40 人 0.25 ms / 5000 人 49 ms）。
     *       Round 21 已加 {@code idx_chat_timestamp}，现恒为单次范围 seek；</li>
     *   <li><b>sql2 {@code ai_chat_feedback}</b> —— {@code created_at} 无索引，
     *       真 {@code SCAN}。但该表每会话至多一行（UNIQUE(player_uuid, session_id)
     *       且只落达标会话），量级比 ai_chat_history 小一个数量级，
     *       <b>判定：暂不加索引</b>（见下）；</li>
     *   <li><b>sql3/sql5/sql6/sql7 四张日期台账</b> —— 均有
     *       {@code UNIQUE(player_uuid, &lt;date&gt;)} 兜底，planner 走同样的 skip-scan，
     *       但前导列去重值 = 玩家数、表内行数也 = 玩家数 × 天数，
     *       实测均在亚毫秒级。<b>判定：不加</b>；</li>
     *   <li><b>sql4 {@code duel_stats}</b> —— 已有 {@code idx_duel_created},
     *       走 {@code SEARCH ... USING INDEX idx_duel_created}，本来就是最优。</li>
     * </ul>
     *
     * <p><b>为什么只给 sql1 加索引、其余不加</b>：索引不是免费的 —— 每次 INSERT
     * 都要多维护一棵 B 树。判定标准是「这张表的行数量级 × 清理频率」是否值得。
     * 只有 {@code ai_chat_history} 达到「每场对话数十行 × 玩家数 × 保留期」的
     * 十万级，其余几张表即使全扫也在毫秒级。
     */
    public void cleanupOldChatHistory(int daysToKeep) {
        String dateExpr;
        if (isSQLite()) {
            dateExpr = "datetime('now', ?)";
        } else {
            dateExpr = "DATE_SUB(NOW(), INTERVAL ? DAY)";
        }
        String sql = "DELETE FROM " + tablePrefix + "ai_chat_history"
                + " WHERE timestamp < " + dateExpr;
        String sql2 = "DELETE FROM " + tablePrefix + "ai_chat_feedback WHERE created_at < " + dateExpr;
        // Block C2（A1/R2-M2）：ai_round_counters 旧行同窗口清理（round_date 为纯日期，
        // 与日期阈值精确字典序可比——SQLite date('now') / MySQL CURDATE() 两后端对照）
        // ——新表必须有清理者，先例 R3-H2。
        String roundDateExpr = isSQLite()
                ? "date('now', ?)"
                : "DATE_SUB(CURDATE(), INTERVAL ? DAY)";
        String sql3 = "DELETE FROM " + tablePrefix + "ai_round_counters"
                + " WHERE round_date < " + roundDateExpr;
        // R10：决斗战绩台账保留期固定 365 天（不随调用方 daysToKeep 走——唯一调用点
        // 传的是 AI 历史的 30 天）。created_at 是 epoch 毫秒，阈值在 Java 侧算好直接
        // 绑 long，天然免去两后端日期函数的方言差异与库内时区偏移。
        String sql4 = "DELETE FROM " + tablePrefix + "duel_stats WHERE created_at < ?";
        long duelCutoff = System.currentTimeMillis() - DUEL_STATS_RETENTION_DAYS * 24L * 60L * 60L * 1000L;

        // slice-09-dc::F-09-02（round1 遗留 MEDIUM，round10 补修）：两张**同样的每日台账**
        // 一直没有保留期清理者，而 ai_round_counters 有（见上面 sql3，注释「新表必须有
        // 清理者，先例 R3-H2」）。
        //
        // 红队提醒的两个坑，这里都避开了：
        //   ① **懒建缺表** —— daily_coin_counters 由 RewardEngine.ensureDailyCoinsTable 懒建，
        //      若清理跑到建表之前会抛 no such table。这里用**独立 try-catch**（与上面 4 个
        //      清理者同构），缺表只丢一条 WARNING，不影响其它清理。
        //   ② **跨天删行会不会重置日限** —— 红队已证伪：额度是按 `coin_date = 今天` 查的，
        //      删昨天的行不影响今天的判定；且这里只删**严格早于**阈值的行，今天/昨天都安全。
        //
        // 保留期取 daysToKeep（与 AI 历史同一窗口，唯一调用点传 30 天）——这两张表都只服务
        // 「当日/近几日」语义，30 天足够覆盖时区漂移与跨天边界。
        String coinDateExpr = isSQLite() ? "date('now', ?)" : "DATE_SUB(CURDATE(), INTERVAL ? DAY)";
        String sql5 = "DELETE FROM " + tablePrefix + "daily_coin_counters"
                + " WHERE coin_date < " + coinDateExpr;
        // 列名必须与建表语句逐字一致。`daily_word_reward_claims` 的 DDL
        // （schema.sql）建的是 `challenge_date`；本轮改动期间这里一度写成 `claim_date`，
        // SQLite 在 **prepare** 阶段就抛 `no such column`，该清理静默失效。
        //
        // 【事实更正（wave-3 锐评核对）】这不是"从上线起就错"的历史缺陷：
        // `git grep claim_date HEAD -- src` = 0 命中，versions/ 下所有 jar 的字节码里
        // 也没有该字符串 —— 整个 sql5/sql6 块都是本轮未提交改动新增的。
        // 真实起服复现到的 `no such column` 跑的是工作树，不是任何已发布版本。
        // 因此这条属"本轮开发中自己发现并纠正"的内部失误，不构成对既有版本的修复。
        String sql6 = "DELETE FROM " + tablePrefix + "daily_word_reward_claims"
                + " WHERE challenge_date < " + coinDateExpr;
        // Round 17：新表必须有清理者（先例 R3-H2，见上面 sql3 的注释）。答题计数与
        // ai_round_counters 同为"每人每日至多一行"的日期台账，answer_date 也是纯日期
        // 字符串 → 复用同一个 coinDateExpr 口径（SQLite date('now',?) / MySQL
        // DATE_SUB(CURDATE(), INTERVAL ? DAY)），两后端精确字典序可比。
        // 跨天删行不会重置日限：额度按 answer_date = 今天 查，删的只有严格早于阈值的行。
        String sql7 = "DELETE FROM " + tablePrefix + "daily_answer_counters"
                + " WHERE answer_date < " + coinDateExpr;
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                bindKeepDays(stmt, daysToKeep);
                int deleted = stmt.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("Cleaned up " + deleted
                            + " old AI chat history records (older than " + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old chat history", e);
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(sql2)) {
                bindKeepDays(stmt, daysToKeep);
                int deletedFeedback = stmt.executeUpdate();
                if (deletedFeedback > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedFeedback
                            + " old AI chat feedback records (older than " + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old chat feedback", e);
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(sql3)) {
                bindKeepDays(stmt, daysToKeep);
                int deletedRounds = stmt.executeUpdate();
                if (deletedRounds > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedRounds
                            + " old AI round counter records (older than " + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old AI round counters", e);
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(sql4)) {
                stmt.setLong(1, duelCutoff);
                int deletedDuels = stmt.executeUpdate();
                if (deletedDuels > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedDuels
                            + " old duel stats records (older than "
                            + DUEL_STATS_RETENTION_DAYS + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old duel stats", e);
            }
            // F-09-02：两张每日台账的保留期清理。独立 try-catch —— 缺表（懒建尚未发生）
            // 只丢一条 FINE，不影响其它清理者。
            try (PreparedStatement stmt = getConnection().prepareStatement(sql5)) {
                bindKeepDays(stmt, daysToKeep);
                int deletedCoins = stmt.executeUpdate();
                if (deletedCoins > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedCoins
                            + " old daily coin counter rows (older than " + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.FINE,
                        "Skipped daily_coin_counters cleanup (table may not exist yet)", e);
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(sql6)) {
                bindKeepDays(stmt, daysToKeep);
                int deletedClaims = stmt.executeUpdate();
                if (deletedClaims > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedClaims
                            + " old daily word reward claim rows (older than "
                            + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old daily word reward claims", e);
            }
            // Round 17：今日答题计数器旧行清理（独立 try-catch，同上面几个清理者）。
            try (PreparedStatement stmt = getConnection().prepareStatement(sql7)) {
                bindKeepDays(stmt, daysToKeep);
                int deletedAnswers = stmt.executeUpdate();
                if (deletedAnswers > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedAnswers
                            + " old daily answer counter rows (older than "
                            + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old daily answer counters", e);
            }
        }
    }

    // ============================================================
    //  AI Chat Feedback（US-FB §3.1，Wave 3 批次 3B）
    // ============================================================

    /**
     * US-FB §3.1：会话级反馈 UPSERT（先本地行后 AI 覆写，幂等）。
     *
     * <p>SQLite {@code ON CONFLICT(player_uuid, session_id) DO UPDATE} /
     * MySQL {@code ON DUPLICATE KEY UPDATE}（双后端对照，沿 {@link #incrementWrongCount}
     * 惯例）；全列覆写 —— AI 覆写时 {@code payloadJsonOrNull} 覆盖先前值。
     *
     * <p><b>R11：返回 boolean 成败（原为 {@code void}）。</b>原实现自己
     * {@code catch (SQLException)} 只记 WARNING，于是 {@code ChatFeedbackManager.writeTask}
     * 的 {@code catch (RuntimeException)} <b>永远看不到纯 SQL 失败</b>（磁盘满 / 表锁 /
     * 连接断），刚建的失败补偿对它完全无效 —— 点评数据静默蒸发。照
     * {@link com.letslearnenglish.minecraftplugin.data.repository.AchievementRepository#upsertUnlock}
     * 的既有范式改成有返回值：失败仍记 WARNING 保留现场，同时把成败交给调用方。
     *
     * @param payloadJsonOrNull AI 点评 JSON；仅本地行时传 {@code null}
     * @return {@code true} = 已写入（UPSERT 幂等，重复覆写也算成功）；
     *         {@code false} = 写库失败（SQLException 已记 WARNING，调用方应自行决定是否重试）
     */
    public boolean upsertChatFeedback(UUID playerUuid, String sessionId, String scenarioKey,
                                      String difficultyKey, int userMessages, int aiMessages,
                                      int durationSeconds, String source, String payloadJsonOrNull) {
        String upsertSql;
        if (isSQLite()) {
            upsertSql = "INSERT INTO " + tablePrefix + "ai_chat_feedback"
                    + " (player_uuid, session_id, scenario_key, difficulty_key, user_messages,"
                    + " ai_messages, duration_seconds, source, ai_payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT(player_uuid, session_id) DO UPDATE SET"
                    + " scenario_key = excluded.scenario_key, difficulty_key = excluded.difficulty_key,"
                    + " user_messages = excluded.user_messages, ai_messages = excluded.ai_messages,"
                    + " duration_seconds = excluded.duration_seconds, source = excluded.source,"
                    + " ai_payload = excluded.ai_payload";
        } else {
            upsertSql = "INSERT INTO " + tablePrefix + "ai_chat_feedback"
                    + " (player_uuid, session_id, scenario_key, difficulty_key, user_messages,"
                    + " ai_messages, duration_seconds, source, ai_payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE"
                    + " scenario_key = VALUES(scenario_key), difficulty_key = VALUES(difficulty_key),"
                    + " user_messages = VALUES(user_messages), ai_messages = VALUES(ai_messages),"
                    + " duration_seconds = VALUES(duration_seconds), source = VALUES(source),"
                    + " ai_payload = VALUES(ai_payload)";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(upsertSql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, sessionId);
                stmt.setString(3, scenarioKey);
                stmt.setString(4, difficultyKey);
                stmt.setInt(5, userMessages);
                stmt.setInt(6, aiMessages);
                stmt.setInt(7, durationSeconds);
                stmt.setString(8, source);
                stmt.setString(9, payloadJsonOrNull);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to upsert chat feedback for player " + playerUuid
                                + " / session " + sessionId, e);
                return false;
            }
        }
    }

    /**
     * US-FB §3.1：读取单会话反馈行（GUI 详情页用；UNIQUE(player_uuid, session_id)
     * 上的有界索引查询）。无行返回 {@code null}。
     */
    public ChatFeedbackEntry getChatFeedback(UUID playerUuid, String sessionId) {
        String sql = "SELECT id, player_uuid, session_id, scenario_key, difficulty_key,"
                + " user_messages, ai_messages, duration_seconds, source, ai_payload, created_at"
                + " FROM " + tablePrefix + "ai_chat_feedback"
                + " WHERE player_uuid = ? AND session_id = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new ChatFeedbackEntry(
                                rs.getInt("id"),
                                rs.getString("player_uuid"),
                                rs.getString("session_id"),
                                rs.getString("scenario_key"),
                                rs.getString("difficulty_key"),
                                rs.getInt("user_messages"),
                                rs.getInt("ai_messages"),
                                rs.getInt("duration_seconds"),
                                rs.getString("source"),
                                rs.getString("ai_payload"),
                                rs.getTimestamp("created_at")
                        );
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get chat feedback for player " + playerUuid
                                + " / session " + sessionId, e);
            }
        }
        return null;
    }

    // ============================================================
    //  Leaderboard queries
    // ============================================================

    /** Get top players by lifetime coins earned. */
    public List<LeaderboardEntry> getTopCoins(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        // R-05：隐身者（player_profiles.show_in_square = 0）不上榜；
        // 无档案行视为默认可见。SELECT…JOIN…WHERE 口径 SQLite/MySQL 通用，无需 isSQLite 分支。
        String sql = "SELECT d.uuid, d.player_name, d.total_coins_earned FROM " + tablePrefix
                + "player_data d LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid"
                + " WHERE COALESCE(p.show_in_square, 1) <> 0"
                + " ORDER BY d.total_coins_earned DESC LIMIT ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    int rank = 1;
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String name = rs.getString("player_name");
                        if (name == null || name.isEmpty()) {
                            name = uuid.toString();
                        }
                        entries.add(new LeaderboardEntry(uuid, name, rs.getLong("total_coins_earned"),
                                0, 0, 0, rank++));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query top coins", e);
            }
        }
        return entries;
    }

    /**
     * Get top players by total_words_learned from player_data table.
     */
    public List<LeaderboardEntry> getTopWordsLearned(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        // R-05：隐身者不上榜（同 getTopCoins 的 JOIN 过滤语义）。
        String sql = "SELECT d.uuid, d.player_name, d.total_words_learned FROM " + tablePrefix
                + "player_data d LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid"
                + " WHERE COALESCE(p.show_in_square, 1) <> 0"
                + " ORDER BY d.total_words_learned DESC LIMIT ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    int rank = 1;
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String name = rs.getString("player_name");
                        if (name == null || name.isEmpty()) {
                            name = uuid.toString();
                        }
                        entries.add(new LeaderboardEntry(uuid, name, rs.getLong("total_words_learned"),
                                0, 0, 0, rank++));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query top words learned", e);
            }
        }
        return entries;
    }

    /**
     * Get top players by longest_streak (combo) from player_data table.
     */
    public List<LeaderboardEntry> getTopCombo(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        // R-05：隐身者不上榜（同 getTopCoins 的 JOIN 过滤语义）。
        String sql = "SELECT d.uuid, d.player_name, d.longest_streak FROM " + tablePrefix
                + "player_data d LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid"
                + " WHERE COALESCE(p.show_in_square, 1) <> 0"
                + " ORDER BY d.longest_streak DESC LIMIT ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    int rank = 1;
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String name = rs.getString("player_name");
                        if (name == null || name.isEmpty()) {
                            name = uuid.toString();
                        }
                        int combo = rs.getInt("longest_streak");
                        entries.add(new LeaderboardEntry(uuid, name, combo,
                                0, 0, 0, rank++));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query top combo", e);
            }
        }
        return entries;
    }

    // ============================================================
    //  Daily Challenge Words
    // ============================================================

    /**
     * 当日挑战词的读面三态（照 {@link DifficultWordsLoad} 范式）。
     *
     * <p><b>为什么必须是三态（A5/D3）：调用方拿 {@code isEmpty()} 当"今天没分配"。</b>
     * {@code DailyChallengeManager.ensureWordChallengesAssignedLocked} 的判据是
     * {@code existing.isEmpty()} —— 一次 SELECT 抖动与"确实还没分配"逐字节同形，
     * 于是走随机抽词并 INSERT 一批新的：当日挑战集从 N 变成 N+M
     * （{@code INSERT OR IGNORE} 只去重同词，不覆盖既有行）→
     * {@link WordChallengeCompletion#COMPLETED} 要求 {@code total > 0 && total == done} →
     * <b>当天奖励变成不可达</b>，玩家还会在 GUI 里看到几条从未作答的词。
     * 触发点包含 GUI 打开与 join 的 {@code checkAndAssign}，库抖一次即中。</p>
     *
     * @param words      挑战词 id（{@code readFailed=false} 时可信；失败时可能只含
     *                   读到一半的行，<b>不得</b>作为"未分配"使用）
     * @param readFailed {@code true} = 查询/连接失败，结果未知
     */
    public record DailyChallengeWordsLoad(List<String> words, boolean readFailed) {}

    /**
     * 三态版 {@link #loadDailyChallengeWords(UUID, String)}（本方法即其实现）。
     */
    public DailyChallengeWordsLoad loadDailyChallengeWords(UUID playerId, String date) {
        List<String> words = new ArrayList<>();
        String sql = "SELECT word_id FROM " + tablePrefix
                + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        words.add(rs.getString("word_id"));
                    }
                }
                return new DailyChallengeWordsLoad(words, false);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load daily challenge words for " + playerId, e);
                return new DailyChallengeWordsLoad(words, true);
            }
        }
    }

    /*
     * R12-F8：这里曾有 {@code List<String> getDailyChallengeWords(UUID, String)}
     * （读失败 → 空 List 的两态便利版）。已删除，且<b>不得</b>重新引入 ——
     * 「库抖了一下」与「今天确实还没分配」在返回值上逐字节同形，唯一的正确用法是
     * 三态版 {@link #loadDailyChallengeWords(UUID, String)} 并显式区分
     * {@code readFailed}。理由与墓碑 lint 见 {@code TwoStateLegacyLintTest}。
     */

    /**
     * Assign daily challenge words for a player.
     *
     * <p><b>R11：返回 boolean 成败（原为 {@code void}）。</b>原实现自己
     * {@code catch (SQLException)} 只记 WARNING，而调用方
     * {@code DailyChallengeManager.ensureWordChallengesAssignedLocked} 紧接着无条件打印
     * "Assigned N daily challenge words to player …" —— <b>失败时日志自相矛盾</b>，且挑战词
     * 是当日单词挑战的前提，没写进去玩家当天就没有挑战可做（每答对一题都会再空回查一次）。
     * 照 {@link #upsertChatFeedback} 的 R11 范式改成有返回值：失败仍记 WARNING 保留现场，
     * 同时把成败交给调用方决定是否重试 / 是否记更重的日志。
     *
     * <p>幂等语义（{@code INSERT OR IGNORE} / {@code INSERT IGNORE}，UNIQUE
     * {@code (player_uuid, challenge_date, word_id)} 兜底）不变：重复分配只影响 0 行，
     * 与"本次写入成功"同义成功，故不看 {@code executeBatch()} 的返回计数。
     *
     * @param playerId the player
     * @param date     the challenge date (yyyy-MM-dd)
     * @param wordIds  word ids to assign（空列表 = 一次空 batch，按成功处理）
     * @return {@code true} = batch 已提交（含"行已存在"的幂等 no-op）；
     *         {@code false} = 写库失败（SQLException 已记 WARNING，调用方须自行处置）
     */
    public boolean assignDailyChallengeWords(UUID playerId, String date, List<String> wordIds) {
        String insertSql;
        if (isSQLite()) {
            insertSql = "INSERT OR IGNORE INTO " + tablePrefix
                    + "daily_challenge_words (player_uuid, challenge_date, word_id) VALUES (?, ?, ?)";
        } else {
            insertSql = "INSERT IGNORE INTO " + tablePrefix
                    + "daily_challenge_words (player_uuid, challenge_date, word_id) VALUES (?, ?, ?)";
        }
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                for (String wordId : wordIds) {
                    stmt.setString(1, playerId.toString());
                    stmt.setString(2, date);
                    stmt.setString(3, wordId);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to assign daily challenge words for " + playerId, e);
                return false;
            }
        }
    }

    /**
     * Mark a daily challenge word as completed.
     * Returns true if the word was newly completed (row was updated),
     * false if it was already completed or not found.
     */
    public boolean markDailyChallengeWordComplete(UUID playerId, String date, String wordId) {
        String sql = "UPDATE " + tablePrefix
                + "daily_challenge_words SET completed = TRUE, completed_at = CURRENT_TIMESTAMP"
                + " WHERE player_uuid = ? AND challenge_date = ? AND word_id = ? AND completed = FALSE";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setString(3, wordId);
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to mark daily challenge word complete for " + playerId, e);
                return false;
            }
        }
    }

    /**
     * "该日单词挑战是否全完成"的<b>三态</b>答案（A5/D2）。
     *
     * <p><b>为什么不能再用 boolean</b>：旧签名 {@code catch → return false} 把两种完全
     * 不同的含义折叠成一个 {@code false}：
     * <ul>
     *   <li>{@link #NOT_COMPLETED}：SQL 成功执行，且 {@code total == 0 || total != done}
     *       —— 业务事实「这一天没有全完成」；</li>
     *   <li>{@link #READ_FAILED}：查询/连接失败 —— <b>结果未知</b>。</li>
     * </ul>
     * 唯一会因此丢资产的消费者是 {@code DailyChallengeManager.hasPendingWordRewardForDate}
     * （午夜 rollover 补发）：它把 {@code false} 读成「昨日没有 pending」，直接
     * {@code return false} 并让调用方把该日算作"作废"。而<b>该日之后再无回访点</b>
     * （{@code hasPendingWordReward} 只看今日状态），于是「昨日确实全完成但奖励还没发」
     * 这笔钱被一次 SELECT 抖动永久抹掉。</p>
     *
     * <p>{@link #READ_FAILED} 的处置必须交给调用方：补发路径应当<b>保留 pending</b>
     * （不判「无 pending」），至少留一条 SEVERE，让运维能看出"这一天没发是因为读不到库"。</p>
     */
    public enum WordChallengeCompletion {
        /** 至少有一个挑战词，且全部已完成。 */
        COMPLETED,
        /** SQL 执行成功但未全完成（含「该日根本没有挑战词行」，与缓存口径 {@code allCompleted()} 同义）。 */
        NOT_COMPLETED,
        /** 查询/连接失败（{@code SQLException} 已记 WARNING）：结果<b>未知</b>，不得当作「未完成」。 */
        READ_FAILED
    }

    /**
     * 三态版"该日单词挑战是否全完成"（A5/D2）。
     *
     * <p>语义与原 boolean 版逐条等价，只把 {@code catch} 的 {@code false} 换成
     * {@link WordChallengeCompletion#READ_FAILED}。</p>
     */
    public WordChallengeCompletion loadWordChallengeCompletion(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) as total, SUM(CASE WHEN completed THEN 1 ELSE 0 END) as done FROM "
                + tablePrefix + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int total = rs.getInt("total");
                        int done = rs.getInt("done");
                        return (total > 0 && total == done)
                                ? WordChallengeCompletion.COMPLETED
                                : WordChallengeCompletion.NOT_COMPLETED;
                    }
                    // rs.next() 为 false：聚合查询必然有一行，正常不可达。按「未知」处理 ——
                    // 猜成 NOT_COMPLETED 就等于把"不知道"伪装成业务事实（本缺陷的本体）。
                    return WordChallengeCompletion.READ_FAILED;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check daily challenge words completion for " + playerId, e);
                return WordChallengeCompletion.READ_FAILED;
            }
        }
    }

    /*
     * R12-F8：这里曾有 {@code boolean isDailyChallengeWordsCompleted(UUID, String)}
     * （{@code READ_FAILED} 折叠成 {@code false} 的两态便利版）。已删除，且<b>不得</b>
     * 重新引入 —— 任何调用方都必须显式写出「读失败要怎么办」。唯一剩下的调用方
     * {@code DailyChallengeManager.dbSaysAllWordsCompleted} 已改为
     * {@code loadWordChallengeCompletion(...) == COMPLETED}，语义完全一致（它需要的
     * 恰好就是折叠后的语义：读不到 = 不确认完成 = 本轮不发奖，pending 留给下一次）。
     * 理由与墓碑 lint 见 {@code TwoStateLegacyLintTest}。
     */

    /**
     * Get word challenge progress (completed / total) for today.
     * Returns int[]{completed, total}.
     */
    public int[] getDailyChallengeWordsProgress(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) as total, SUM(CASE WHEN completed THEN 1 ELSE 0 END) as done FROM "
                + tablePrefix + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new int[]{rs.getInt("done"), rs.getInt("total")};
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get daily challenge words progress for " + playerId, e);
            }
        }
        return new int[]{0, 0};
    }

    /**
     * 修复批 A（R10-H1）：标记玩家某日的单词挑战奖励已发放（幂等，双后端对照）。
     *
     * <p>SQLite {@code INSERT OR IGNORE} / MySQL {@code INSERT IGNORE}（同
     * {@link #assignDailyChallengeWords} 惯例），UNIQUE(player_uuid, challenge_date)
     * 兜底重复标记为 no-op。该表是"已付"的重启后真源——内存
     * {@code wordChallengeClaimedToday} 重启即空，同日重启不再据此重复发钱。
     *
     * <p><b>R11：返回 boolean 成败（原为 {@code void}）。</b>原实现自己
     * {@code catch (SQLException)} 只记 WARNING，调用方（{@code DailyChallengeManager}）
     * 无从观察"台账没写进去"，而它在<b>发钱之后</b>调用本方法——于是"钱已到账、台账为空"
     * 这一状态既无日志外的信号、也无补偿动作。失败仍记 WARNING 保留现场，同时把成败
     * 交给调用方，照 {@link #upsertChatFeedback} 的 R11 范式。
     *
     * <p><b>失败方向（调用方必须按此理解返回值）</b>：{@code false} 表示"这一笔已付"
     * 只存在于内存里，<b>钱已经发出去了</b>；重启后内存清空、DB 台账查不到、挑战词在
     * DB 里仍是"全完成" → 同一天会<b>再发一次</b>。即失败方向是「多给」，不是「少给」
     * （详见 {@code DailyChallengeManager#onAllWordChallengesComplete} 的处置说明）。
     *
     * @param playerId the player
     * @param date     the challenge date (yyyy-MM-dd；rollover 补发传昨日)
     * @return {@code true} = 台账行已存在或本次写入成功（{@code INSERT OR IGNORE} /
     *         {@code INSERT IGNORE} 幂等，重复标记也算成功）；{@code false} = 写库失败
     *         （SQLException 已记 WARNING，调用方须自行处置，见方法 javadoc 的失败方向）
     */
    public boolean markDailyWordRewardClaimed(UUID playerId, String date) {
        String insertSql;
        if (isSQLite()) {
            insertSql = "INSERT OR IGNORE INTO " + tablePrefix
                    + "daily_word_reward_claims (player_uuid, challenge_date) VALUES (?, ?)";
        } else {
            insertSql = "INSERT IGNORE INTO " + tablePrefix
                    + "daily_word_reward_claims (player_uuid, challenge_date) VALUES (?, ?)";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(insertSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.executeUpdate();
                // 影响行数刻意不看：IGNORE 语义下"行已存在"= 已付，与"本次写入"同义成功。
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to mark daily word reward claimed for " + playerId
                                + " / " + date, e);
                return false;
            }
        }
    }

    /**
     * 修复批 A（R10-H1）：玩家某日的单词挑战奖励是否已发放（{@code daily_word_reward_claims}
     * 台账查询）。异常 → false + WARNING（降级语义同 {@link #markDailyWordRewardClaimed}：
     * 查不到就按未付处理，最坏后果与修复前持平）。
     *
     * @param playerId the player
     * @param date     the challenge date (yyyy-MM-dd)
     * @return true if a claim row exists for this player on this date
     */
    public boolean isDailyWordRewardClaimed(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "daily_word_reward_claims"
                + " WHERE player_uuid = ? AND challenge_date = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check daily word reward claim for " + playerId
                                + " / " + date, e);
                return false;
            }
        }
    }

    /**
     * R-16（round16 数据层审计 F-D4 #1）：{@link #loadWordChallengeSnapshot} 的<b>三态载荷</b>。
     *
     * <p>形状与命名照同族既有范式 —— {@link DailyChallengeWordsLoad}(words, readFailed) /
     * {@link DifficultWordsLoad} / {@link PendingSeasonRewardsLoad}：状态位挂在<b>外层 load
     * 记录</b>上，内层载荷（{@link WordChallengeSnapshot}）保持纯数据体。这样
     * ① 名字可预测（{@code XxxLoad(xxx, readFailed)} + {@code readFailed()}）；
     * ② 缓存侧 {@code DailyWordChallengeState} 的构造参数无需改动；
     * ③ 不会出现「某个 record 带 readFailed、另一个没有」的第二套写法。</p>
     *
     * @param snapshot   {@code readFailed=false} 时可信；{@code true} 时<b>只</b>含读到一半的
     *                   行（展示可以，判定不行），<b>不得</b>当作「今天没有挑战词」
     * @param readFailed {@code true} = 单词集合查询/连接失败（{@code SQLException} 已记
     *                   WARNING），结果<b>未知</b>。台账位（{@code rewardPaid}）自身的读失败
     *                   <b>不</b>置本标志 —— 那一路「按未付处理」是
     *                   {@link #isDailyWordRewardClaimed} 刻意保留的口径，且调用方
     *                   {@code hasPendingWordReward} 会再补一次 DB 回查兜底
     */
    public record WordChallengeSnapshotLoad(WordChallengeSnapshot snapshot, boolean readFailed) {
    }

    /**
     * R7-B ③：一次查询取回「今日挑战词集合 + 已完成集合 + 奖励台账是否已发」三份数据，
     * 供 {@code DailyChallengeManager} 的 per-player 每日缓存加载（把答对路径上原本
     * 每题 4 条 SELECT 压成"每次缓存加载 2 条"）。
     *
     * <p>语义与 {@link #loadDailyChallengeWords(UUID, String)}.words()、
     * {@link #loadWordChallengeCompletion(UUID, String)}、{@link #isDailyWordRewardClaimed}
     * 三者组合完全一致，但拆成两次最小查询：
     * 第一次只读 {@code daily_challenge_words}（绝不在 JOIN 后再查
     * {@code daily_word_reward_claims} —— 后者多行会产生笛卡尔积），第二次单行 COUNT。</p>
     *
     * <p><strong>失败降级（R-16 改写：原先在这里把"读失败"折叠成"今日无挑战词"）</strong>：
     * 单词查询异常 → {@link WordChallengeSnapshotLoad#readFailed()} = {@code true}，载荷只含
     * 读到一半的行，<b>调用方不得把它当"今日没分配"</b>；台账查询异常 → 只把
     * rewardPaid 置 false（记 WARNING），单词集合照常返回（{@code readFailed} 保持
     * {@code false} —— 那一路的"失败方向 = 仍按未付处理"是
     * {@link #isDailyWordRewardClaimed} 里刻意保留并已在调用方回查兜底的口径，
     * 与单词集合无关，不要顺手折进本标志）。</p>
     *
     * <p><strong>R6-E</strong>：本方法不再计算"是否全完成"。那个字段原先算出来
     * <b>从未被任何调用方读取</b>（调用方一律用缓存的
     * {@code DailyWordChallengeState.allCompleted()}），属于审计噪音；
     * "有词且全部完成"的判定规则仍在两处活着的实现里：
     * {@link #loadWordChallengeCompletion(UUID, String)}（SQL 口径三态，rollover 补发走它）
     * 与 {@code DailyChallengeManager.DailyWordChallengeState#allCompleted()}（缓存口径）。
     * 两者语义等价（都要求"至少一个词"且"完成数 ≥ 总数"，DB 侧因 {@code completed ⊆ words}
     * 可写成 {@code ==}）。</p>
     *
     * <p><b>R-16（round16 数据层审计 F-D4 #1）：本方法曾是同一族读面里唯一的两态成员。</b>
     * 两个兄弟方法早已改成三态 —— {@link #loadDailyChallengeWords(UUID, String)} 返回
     * {@link DailyChallengeWordsLoad}(words, readFailed)、
     * {@link #loadWordChallengeCompletion(UUID, String)} 返回
     * {@link WordChallengeCompletion}{COMPLETED, NOT_COMPLETED, READ_FAILED}；只有快照路径
     * 仍把 {@code SQLException} 吞成"空词集 + rewardPaid=false"。同一件事三种写法，
     * 而两态的那个恰好落在发奖路径上。</p>
     *
     * <p><b>本方法的两条真实故障路径（照实记录，不套用兄弟方法的措辞）</b>：
     * <ol>
     *   <li><b>读到 0 行就抛</b>：返回空快照 → 与「今天确实还没分配」逐字节同形。这条路径上
     *       的重复分配<b>不会</b>发生在本方法 —— 分配判据走的是
     *       {@link #loadDailyChallengeWords}（已三态、读失败不分配）。真正的代价在缓存侧：
     *       {@code DailyChallengeManager} 拿空集算出的 {@code noWordsAssigned} 与
     *       {@code allCompleted()} 都是"把不知道当业务事实"。</li>
     *   <li><b>读到一半抛</b>（{@code while (rs.next())} 中途异常）：{@code words} 里已经
     *       落了一半行、非空，于是缓存侧<b>会把它当作当日真实挑战集缓存一整天</b>。此后
     *       {@code DailyWordChallengeState.allCompleted()} 拿"半份总数"当分母 → 玩家做完全部
     *       半份词即判全完成 → 走 {@code state.allCompleted()} 短路直接发奖（DB 复核被短路
     *       跳过）→ <b>多发一次整额奖励</b>。这条与"读失败"无关也会发生，故必须让调用方
     *       知道"这份载荷不完整"。</li>
     * </ol>
     * 两条的共同处置：把 {@code readFailed} 交给调用方，由它决定"弃用这份载荷 + 下次重读"。</p>
     *
     * <p>故照 {@code DifficultWordsLoad} / {@code PendingSeasonRewardsLoad} 的既有命名与
     * 形状补一层载荷记录 {@link WordChallengeSnapshotLoad}，{@link WordChallengeSnapshot}
     * 本身不上 readFailed 字段 —— 与 {@link DailyChallengeWordsLoad} 的处置一致：状态挂在
     * 外层 load 记录上，内层快照仍是纯数据体（缓存侧构造 {@code DailyWordChallengeState}
     * 时要按三份字段取值，混进一个"未知"位会让它变成第四种语义）。</p>
     *
     * @param playerId 玩家
     * @param date     {@code yyyy-MM-dd}
     * @return 三态载荷（详见 {@link WordChallengeSnapshotLoad}）
     */
    public WordChallengeSnapshotLoad loadWordChallengeSnapshot(UUID playerId, String date) {
        String wordsSql = "SELECT word_id, completed FROM " + tablePrefix
                + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        Set<String> words = new HashSet<>();
        Set<String> completedWords = new HashSet<>();
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(wordsSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String wordId = rs.getString("word_id");
                        words.add(wordId);
                        if (rs.getBoolean("completed")) {
                            completedWords.add(wordId);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load daily challenge words for " + playerId, e);
                // R-16：读失败必须自成一态。旧实现这里返回空快照，与「今天确实还没分配」
                // 逐字节同形（与 DailyChallengeWordsLoad 修掉的是同一形态，只是这条路径被漏掉了）。
                // 最坏的两种后果见方法 javadoc：读到一半抛时，半份集合会被缓存成"当日真集合"，
                // allCompleted() 拿它当分母 → 提前发奖。故载荷仍带上已读到的半截行
                // （展示可用），但 readFailed=true 明确宣告"这份不完整、不得用于判定"。
                return new WordChallengeSnapshotLoad(
                        new WordChallengeSnapshot(words, completedWords, false), true);
            }
        }
        return new WordChallengeSnapshotLoad(new WordChallengeSnapshot(words, completedWords,
                isDailyWordRewardClaimed(playerId, date)), false);
    }

    /**
     * R7-B ②：单条 UPDATE 推进行为挑战进度并判定完成位（合并原
     * {@code DailyChallengeRepository.findState} SELECT + {@code updateProgress} UPDATE）。
     *
     * <p><strong>返回值语义是本方法的关键契约</strong>：返回受影响行数，
     * {@code 0} 表示"该玩家今日没有这一行挑战"——这正是原实现
     * {@code loadChallengeState(...) == null} 的触发条件，调用方据此走 R-32 补分配，
     * 因此本方法<strong>必须</strong>返回 {@code executeUpdate()} 的原值，不能改成 boolean。</p>
     *
     * <p>SQL 层面的等价替换（原两步的全部判定下推到一条语句；<strong>赋值顺序见实现注释</strong>）：</p>
     * <pre>
     * completed = CASE WHEN claimed = 0 AND completed = 0 AND progress + inc &gt;= target
     *                  THEN 1 ELSE completed END                       -- 只置不撤
     * progress  = CASE WHEN claimed = 0 THEN
     *                 CASE WHEN progress + inc &gt;= target THEN target      -- R-79 clamp
     *                      ELSE progress + inc END
     *                 ELSE progress END
     * </pre>
     *
     * <p><strong>方言关键陷阱</strong>：MySQL 单表 UPDATE 按从左到右求值、后续赋值看到的是
     * 前面刚写入的新值；SQLite 一律用旧行值。故 {@code completed} 必须写在 {@code progress}
     * <em>之前</em>，否则 MySQL 会把 {@code progress + inc} 算成 {@code (旧+inc)+inc}，
     * 差一格提前完成。此顺序下两方言行为完全一致。</p>
     *
     * <p><strong>方言核对</strong>：<em>不</em>使用 {@code MIN(a, b)} —— 虽然 SQLite 与 MySQL
     * 在双参数形式下都会解析为标量函数，但 MySQL 的 {@code MIN} 同时是聚合函数名，
     * 可读性与工具链（SQL 高亮/静态检查）歧义都不值得省一个 {@code CASE}。此处统一用
     * {@code CASE WHEN ... THEN ... ELSE ... END}，两方言逐字同义。</p>
     *
     * <p>{@code progress} 列在 {@code schema.sql} 与 {@code createTables()} 中均为
     * {@code INT DEFAULT 0}，但存量库若为 NULL 会传染成 NULL 写回 —— 故每次读取都用
     * {@code COALESCE(progress, 0)} 包住（与既有 {@code adjustCoins} 的惯例一致）。</p>
     *
     * <p><strong>并发</strong>：整条语句在 MySQL 默认 REPEATABLE READ 下对目标行加排他锁，
     * 读-改-写原子；调用方仍在未变的 {@code connectionLock} 临界区内，无新增锁。</p>
     *
     * <p><strong>{@code claimed = 1} 为什么放进 CASE 而不是 WHERE</strong>：原实现在
     * {@code alreadyClaimed} 时直接 return、<em>不写库</em>，但行仍存在。若把它写进 WHERE，
     * 已领取行会返回 {@code 0} 行，调用方会把"已领取"误判成"今日未分配"并触发一次无谓的
     * R-32 补分配。放进 SET 的 CASE 里则：行被命中（返回 1，不触发补分配）、两列原样写回
     * （与原 return 的可观察状态一致）。</p>
     *
     * <p>与原实现逐条等价（无放宽、无收紧）：{@code increment} 恒 &gt; 0，故
     * {@code progress >= target} 时 {@code progress + inc >= target} 必然成立 ——
     * 原实现"已达标的畸形旧行也判完成"的行为被完整保留。</p>
     *
     * @param playerId     玩家
     * @param date         {@code yyyy-MM-dd}
     * @param challengeId  挑战 id
     * @param increment    本次增量（恒 &gt; 0，调用方保证）
     * @param target       完成阈值（挑战类型不同而不同，<strong>必须参数绑定</strong>）
     * @return 受影响行数；写失败返回 {@code -1}（区别于"今日无行"的 0）
     */
    public int advanceChallengeProgress(UUID playerId, String date, String challengeId,
                                        int increment, int target) {
        // 每个 ? 都独立绑定（不用同名参数复用）：明确、可读、与既有 SQL 风格一致。
        //
        // 【赋值顺序是正确性的一部分，不可交换】MySQL 的单表 UPDATE 按**从左到右**求值，
        // 后面的赋值会看到前面刚写入的新值（官方示例：SET col1 = col1+1, col2 = col1 里
        // col2 拿到的是新 col1）；SQLite 则一律用旧行值。若把 progress 写在前面，MySQL 下
        // completed 的 `progress + inc >= target` 会变成 `(旧值+inc) + inc >= target`，
        // 导致差一格就提前置 completed=1（如 8/10 答对一次就被判完成）。故 completed
        // **必须写在 progress 之前**：此时两方言看到的都是旧 progress，语义一致。
        //
        // completed 分支带 `completed = 0` 前提：只置位、绝不撤销 —— 等价原实现的
        // `if (alreadyCompleted) nowCompleted = true;`，也兜住"completed 由 completeChallenge
        // 二进制置 1、progress 仍为 0"的行（COMBO_5 这类），不会被本条 UPDATE 打回 0。
        String sql = "UPDATE " + tablePrefix
                + "daily_challenges SET "
                + "completed = CASE WHEN claimed = 0 AND completed = 0 AND COALESCE(progress, 0) + ? >= ? "
                + "THEN 1 ELSE completed END, "
                + "progress = CASE WHEN claimed = 0 THEN "
                + "CASE WHEN COALESCE(progress, 0) + ? >= ? THEN ? ELSE COALESCE(progress, 0) + ? END "
                + "ELSE progress END "
                + "WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                int idx = 1;
                // completed 分支：+inc 与 target 比较（必须在 progress 之前求值）
                stmt.setInt(idx++, increment);
                stmt.setInt(idx++, target);
                // progress 分支：+inc 与 target 比较，封顶 target，否则累加
                stmt.setInt(idx++, increment);
                stmt.setInt(idx++, target);
                stmt.setInt(idx++, target);
                stmt.setInt(idx++, increment);
                stmt.setString(idx++, playerId.toString());
                stmt.setString(idx++, date);
                stmt.setString(idx, challengeId);
                return stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to advance daily challenge progress for " + playerId, e);
                return -1;
            }
        }
    }

    /**
     * Block C2（R2-M2）：今日 AI 有效轮次 +1 落库（UPSERT，双后端对照）。
     *
     * <p>SQLite {@code ON CONFLICT(player_uuid, round_date) DO UPDATE} /
     * MySQL {@code ON DUPLICATE KEY UPDATE}（同 {@link #incrementWrongCount} 惯例）。
     * 该表是 daily-round-limit 的重启后真源——内存 TodayCounter 重启/quit-rejoin
     * 即清，按 {@link #getAiRoundCount} 回读真值闭合绕过路径。
     *
     * <p>静默降级：SQLException 只记 WARNING（写失败最坏后果 = 重启后计数可重复，
     * 与修复前行为持平，不崩、不阻断聊天主流程）。
     *
     * @param playerId the player
     * @param date     the round date (yyyy-MM-dd)
     */
    public void incrementAiRound(UUID playerId, String date) {
        String upsertSql;
        if (isSQLite()) {
            upsertSql = "INSERT INTO " + tablePrefix
                    + "ai_round_counters (player_uuid, round_date, rounds) VALUES (?, ?, 1)"
                    + " ON CONFLICT(player_uuid, round_date) DO UPDATE SET rounds = rounds + 1";
        } else {
            upsertSql = "INSERT INTO " + tablePrefix
                    + "ai_round_counters (player_uuid, round_date, rounds) VALUES (?, ?, 1)"
                    + " ON DUPLICATE KEY UPDATE rounds = rounds + 1";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(upsertSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to increment AI round counter for " + playerId
                                + " / " + date, e);
            }
        }
    }

    /**
     * P0-4 红队补丁（R-P1-1）：今日 AI 轮次 -1 落库（退还预扣）。
     *
     * <p>用途单一：开场白在"发请求前"预扣轮次（堵 sameSession 绕过），但若请求在
     * <b>确定已到达上游之前</b>就失败（超时 / 连接失败 / 5xx / 429），这次请求并没有
     * 产生上游计费，应当退还——否则上游持续故障时，玩家反复点场景会把当天的
     * daily-round-limit 与 opening-daily-cap 全部耗光，当天连正常聊天都被拒且
     * 重启/重登都无法恢复（DB 已落库），属于可用性锁死。
     *
     * <p>{@code WHERE rounds > 0} 保证不会写出负值（并发下重复退还的最坏结果是
     * 少退一次，上限仍由内存 TodayCounter 约束）。
     *
     * <p>静默降级：SQLException 只记 WARNING（退还失败最坏后果 = 玩家多耗一轮，
     * 与不退还持平，不崩、不阻断主流程）。
     *
     * @param playerId the player
     * @param date     the round date (yyyy-MM-dd)
     */
    public void decrementAiRound(UUID playerId, String date) {
        String sql = "UPDATE " + tablePrefix + "ai_round_counters"
                + " SET rounds = rounds - 1 WHERE player_uuid = ? AND round_date = ? AND rounds > 0";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to decrement AI round counter for " + playerId
                                + " / " + date, e);
            }
        }
    }

    /**
     * Block C2（R2-M2）：玩家某日的 AI 有效轮次累计（{@code ai_round_counters} 查询）。
     * 无行 → 0；异常 → 0 + WARNING（降级语义同 {@link #incrementAiRound}：查不到
     * 就按 0 处理，最坏后果 = 重启后防刷上限可重复，与修复前持平）。
     *
     * @param playerId the player
     * @param date     the round date (yyyy-MM-dd)
     * @return rounds recorded for this player on this date, 0 if none
     */
    public int getAiRoundCount(UUID playerId, String date) {
        String sql = "SELECT rounds FROM " + tablePrefix + "ai_round_counters"
                + " WHERE player_uuid = ? AND round_date = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("rounds");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get AI round count for " + playerId
                                + " / " + date, e);
            }
        }
        return 0;
    }

    /**
     * Round 17：今日答题次数 +1 落库（UPSERT，双后端对照）。
     *
     * <p>与 {@link #incrementAiRound} 逐项同构（SQLite {@code ON CONFLICT(...) DO UPDATE} /
     * MySQL {@code ON DUPLICATE KEY UPDATE}）：该表是 {@code word-system.daily-limit}
     * 的重启后真源 —— 原内存 {@code todayAnswerCountCache} 退服重进即被
     * {@code PlayerDataStore.unloadPlayerData} remove 掉，单服即可无限刷新当日额度。
     *
     * <p>静默降级：SQLException 只记 WARNING（写失败最坏后果 = 重启后计数可重复，
     * 与修复前行为持平，不崩、不阻断答题主流程）。
     *
     * @param playerId the player
     * @param date     the answer date (yyyy-MM-dd)
     */
    public void incrementDailyAnswerCount(UUID playerId, String date) {
        String upsertSql;
        if (isSQLite()) {
            upsertSql = "INSERT INTO " + tablePrefix
                    + "daily_answer_counters (player_uuid, answer_date, answers) VALUES (?, ?, 1)"
                    + " ON CONFLICT(player_uuid, answer_date) DO UPDATE SET answers = answers + 1";
        } else {
            upsertSql = "INSERT INTO " + tablePrefix
                    + "daily_answer_counters (player_uuid, answer_date, answers) VALUES (?, ?, 1)"
                    + " ON DUPLICATE KEY UPDATE answers = answers + 1";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(upsertSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to increment daily answer counter for " + playerId
                                + " / " + date, e);
            }
        }
    }

    /**
     * Round 17：玩家某日的答题次数累计（{@code daily_answer_counters} 查询）。
     * 无行 → 0；异常 → 0 + WARNING（降级语义同 {@link #getAiRoundCount}：查不到就按 0
     * 处理，最坏后果 = 重启后当日额度可重复，与修复前持平）。
     *
     * @param playerId the player
     * @param date     the answer date (yyyy-MM-dd)
     * @return answers recorded for this player on this date, 0 if none
     */
    public int getDailyAnswerCount(UUID playerId, String date) {
        String sql = "SELECT answers FROM " + tablePrefix + "daily_answer_counters"
                + " WHERE player_uuid = ? AND answer_date = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("answers");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get daily answer count for " + playerId
                                + " / " + date, e);
            }
        }
        return 0;
    }

    // ============================================================
    //  Duel Stats（R10 战略锐评：决斗战绩持久化）
    // ============================================================

    /**
     * 决斗战绩保留天数。决斗数据量极小（一场一行，全服日均个位数到几十行），
     * 一年足够支撑"胜率/连胜/复仇"类查询；更老的明细没有展示价值。
     */
    private static final int DUEL_STATS_RETENTION_DAYS = 365;

    /** 连胜扫描窗口：最多回看最近多少场（有界查询，防重度玩家全表扫）。 */
    private static final int DUEL_STREAK_SCAN_LIMIT = 50;

    /**
     * 一名玩家的决斗累计战绩（{@code duel_stats} 聚合投影）。
     *
     * @param wins   作为 winner_uuid 的行数
     * @param losses 作为 loser_uuid 的行数
     */
    public static record DuelStats(int wins, int losses) {

        /** 总场次。 */
        public int total() {
            return wins + losses;
        }

        /**
         * 胜率（0.0~1.0）；总场次为 0 时返回 0.0（不返回 NaN，调用方免判空）。
         */
        public double winRate() {
            int total = total();
            return total == 0 ? 0.0 : (double) wins / total;
        }
    }

    /**
     * R10：落一场决斗战绩（追加写，一场一行）。
     *
     * <p>静默降级：SQLException 只记 WARNING（写失败最坏后果 = 少一条历史战绩，
     * 玩家当场看到的胜负播报/成就/奖励均已先行完成，绝不因落库失败打断结算）。
     *
     * <p>线程契约：同步阻塞在调用线程；{@code DuelManager.endDuel} 在主线程结算，
     * 因此调用方必须自带异步派发（见 DuelManager 的 runAsync 帮助方法），
     * 与 {@code AchievementManager.checkHelpAchievement} 的"主线程调用方 + 内部
     * 异步派发"纪律同源。
     *
     * @param winner     胜者 UUID
     * @param loser      败者 UUID
     * @param winnerScore 胜者得分（正常路径恒等于 {@code DuelRoom.WIN_SCORE}）
     * @param loserScore  败者得分
     */
    public void recordDuelResult(UUID winner, UUID loser, int winnerScore, int loserScore) {
        String sql = "INSERT INTO " + tablePrefix
                + "duel_stats (winner_uuid, loser_uuid, winner_score, loser_score,"
                + " duel_date, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, winner.toString());
                stmt.setString(2, loser.toString());
                stmt.setInt(3, winnerScore);
                stmt.setInt(4, loserScore);
                // duel_date 与全仓既有日期口径一致（LocalDate.now() → yyyy-MM-dd）
                stmt.setString(5, LocalDate.now().toString());
                stmt.setLong(6, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to record duel result winner=" + winner
                                + " loser=" + loser, e);
            }
        }
    }

    /**
     * R10：玩家累计胜负场次（赢/输两列各一次 COUNT，同一连接内顺序执行）。
     *
     * <p>索引支撑：两个 COUNT 分别命中 {@code idx_duel_winner_date} /
     * {@code idx_duel_loser_date} 的前导列，均为索引覆盖计数，非全表扫。
     * 异常 → 空战绩（wins=0, losses=0）+ WARNING（降级：展示层显示"暂无战绩"，
     * 不是"显示为全败"）。
     *
     * @param player the player
     * @return 累计战绩（永不为 null）
     */
    public DuelStats getDuelStats(UUID player) {
        String winSql = "SELECT COUNT(*) FROM " + tablePrefix
                + "duel_stats WHERE winner_uuid = ?";
        String lossSql = "SELECT COUNT(*) FROM " + tablePrefix
                + "duel_stats WHERE loser_uuid = ?";
        synchronized (connectionLock) {
            try {
                int wins = 0;
                int losses = 0;
                try (PreparedStatement stmt = getConnection().prepareStatement(winSql)) {
                    stmt.setString(1, player.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            wins = rs.getInt(1);
                        }
                    }
                }
                try (PreparedStatement stmt = getConnection().prepareStatement(lossSql)) {
                    stmt.setString(1, player.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            losses = rs.getInt(1);
                        }
                    }
                }
                return new DuelStats(wins, losses);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get duel stats for " + player, e);
                return new DuelStats(0, 0);
            }
        }
    }

    /**
     * R10：当前连胜场数（从最近一场往回数，直到遇到一场失败或扫描窗口用尽）。
     *
     * <p>实现说明：SQL 取该玩家参与（赢或输）的最近 {@value #DUEL_STREAK_SCAN_LIMIT}
     * 行，按 {@code created_at DESC, id DESC} 排序后在 Java 里数前缀连胜。
     * 为什么不在 SQL 里算：窗口函数（{@code ROW_NUMBER}）两后端语法/版本门槛不一致
     * （MySQL 8 才有，SQLite 3.25+ 才有），而"最多 50 行"的扫描成本可忽略；
     * {@code id DESC} 作次序键兜底同毫秒写入（自增主键单调，双后端均成立）。
     *
     * <p>语义边界：窗口内全胜且恰好用满 50 行时，返回值会是 50（可能低估真实连胜，
     * 不虚报）；30 场以内完全精确。异常 → 0 + WARNING。
     *
     * <p>线程契约：同步阻塞读，禁止主线程调用（与 {@link #getDuelStats} 同）。
     *
     * @param player the player
     * @return 当前连胜场数（0 = 无胜绩或最近一场为负）
     */
    public int getDuelWinStreak(UUID player) {
        String sql = "SELECT winner_uuid FROM " + tablePrefix + "duel_stats"
                + " WHERE winner_uuid = ? OR loser_uuid = ?"
                + " ORDER BY created_at DESC, id DESC LIMIT ?";
        String id = player.toString();
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, id);
                stmt.setInt(3, DUEL_STREAK_SCAN_LIMIT);
                try (ResultSet rs = stmt.executeQuery()) {
                    int streak = 0;
                    while (rs.next()) {
                        if (!id.equals(rs.getString("winner_uuid"))) {
                            break; // 最近一场参与但没赢 → 连胜中断
                        }
                        streak++;
                    }
                    return streak;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get duel win streak for " + player, e);
                return 0;
            }
        }
    }

    // ============================================================
    //  Difficult Words / Wrong Book
    // ============================================================

    /**
     * Increment the wrong count for a word. Inserts the word if it doesn't exist yet.
     * Returns the new wrong count after incrementing.
     */
    public int incrementWrongCount(UUID playerId, String word) {
        String upsertSql;
        if (isSQLite()) {
            upsertSql = "INSERT INTO " + tablePrefix + "difficult_words (player_uuid, word, wrong_count) VALUES (?, ?, 1) ON CONFLICT(player_uuid, word) DO UPDATE SET wrong_count = wrong_count + 1";
        } else {
            upsertSql = "INSERT INTO " + tablePrefix + "difficult_words (player_uuid, word, wrong_count) VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE wrong_count = wrong_count + 1";
        }
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to increment wrong count for " + playerId + " / " + word, e);
            }
        }

        // Read back the new count
        return getWrongCount(playerId, word);
    }

    /** DC-01：把错题行写成绝对值（UPSERT）。供 quit 前同步刷盘，替代会被世代号作废的异步 increment。 */
    public void setDifficultWordCount(UUID playerId, String word, int wrongCount) {
        String sql = (isSQLite()
                ? "INSERT INTO " + tablePrefix + "difficult_words (player_uuid, word, wrong_count) VALUES (?, ?, ?)"
                        + " ON CONFLICT(player_uuid, word) DO UPDATE SET wrong_count = excluded.wrong_count"
                : "INSERT INTO " + tablePrefix + "difficult_words (player_uuid, word, wrong_count) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE wrong_count = VALUES(wrong_count)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                stmt.setInt(3, wrongCount);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist difficult word " + word, e);
            }
        }
    }

    /**
     * Get the current wrong count for a player's word.
     */
    public int getWrongCount(UUID playerId, String word) {
        String sql = "SELECT wrong_count FROM " + tablePrefix + "difficult_words WHERE player_uuid = ? AND word = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("wrong_count");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get wrong count for " + playerId + " / " + word, e);
            }
        }
        return 0;
    }

    /**
     * Get all difficult words for a player with their wrong counts and added timestamps.
     */
    public List<DifficultWord> getDifficultWords(UUID playerId) {
        return loadDifficultWords(playerId).words();
    }

    /** R4-15-EH-15-02：读面三态（照 {@link CheckInLoad} 范式）——「读失败」不得与「确实没有错题」同形。 */
    public record DifficultWordsLoad(List<DifficultWord> words, boolean readFailed) {}

    public DifficultWordsLoad loadDifficultWords(UUID playerId) {
        List<DifficultWord> words = new ArrayList<>();
        String sql = "SELECT word, wrong_count, added_at FROM " + tablePrefix
                + "difficult_words WHERE player_uuid = ? ORDER BY added_at DESC";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String word = rs.getString("word");
                        int wrongCount = rs.getInt("wrong_count");
                        Timestamp ts = rs.getTimestamp("added_at");
                        long addedAt = ts != null ? ts.getTime() : System.currentTimeMillis();
                        words.add(new DifficultWord(word, wrongCount, addedAt));
                    }
                }
                return new DifficultWordsLoad(words, false);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get difficult words for " + playerId, e);
                return new DifficultWordsLoad(words, true);
            }
        }
    }

    /**
     * Remove a word from the player's difficult words list entirely.
     */
    public void removeDifficultWord(UUID playerId, String word) {
        String sql = "DELETE FROM " + tablePrefix + "difficult_words WHERE player_uuid = ? AND word = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to remove difficult word for " + playerId + " / " + word, e);
            }
        }
    }

    /**
     * {@link #decrementWrongCount} 的<b>三态</b>结果 —— "写库失败"与"没有可减的行"必须分开。
     *
     * <p><b>为什么不能再返回 {@code boolean}</b>：旧签名把两种毫无关系的含义塞进 {@code false}：
     * ① 业务事实「这条 SQL 执行成功，但 0 行被改（行不存在 / wrong_count 已达 1）」；
     * ② 故障事实「这条 SQL 根本没执行成功（{@code SQLException}）」。调用方
     * （{@code PlayerDataStore.decrementWrongCount}）按 ① 消费 {@code false} —— 命中就删行 ——
     * 于是<b>一次瞬时写库失败被翻译成了 DELETE</b>：连接自愈窗口 / {@code SQLITE_BUSY} /
     * 只读挂载下，UPDATE 抛异常而随后的 DELETE 反而成功，这个词整行从错题本消失。
     * 这与 {@link CheerResult} 是同一类缺陷（把写失败折叠成业务事实），修法也沿用同一范式。
     *
     * <p><b>{@link #FAILED} 的含义是"未知"而不是"没减"</b>：{@code executeUpdate} 抛异常时，
     * 减没减、行在不在都无从判断，因此 <b>FAILED 绝不能触发任何 DELETE</b>。宁可让这个词在本轮
     * 多留一行（下一次成功减的时候自然对齐，quit 刷盘的绝对值写也会覆盖），也不能错删 ——
     * 两个方向的代价不对等。
     *
     * <p>为什么不用 {@code Boolean}（{@code null} = 失败）：装箱的 {@code null} 太容易被
     * 解箱成 NPE、或被 {@code if (!result)} 静默当成 false，语义又退回同形。也不用
     * {@code Optional}：这里没有"载荷"要携带，三态本身就是全部信息。
     */
    public enum DecrementResult {
        /** 行存在且原 {@code wrong_count > 1}：已减 1，词仍留在错题本。 */
        DECREMENTED,
        /** SQL 执行成功但 0 行被改：行不存在，或 {@code wrong_count} 已达 1 —— 调用方应删行（幂等）。 */
        NO_ROW_TO_DECREMENT,
        /** 写库失败（{@code SQLException} 已记 WARNING）：结果<b>未知</b>，调用方<b>不得</b>删行。 */
        FAILED
    }

    /**
     * Decrement the wrong count for a word (used when practicing correctly).
     *
     * <p>Block C2（A3/R3-M2）：原子化边界 —— 条件改为 {@code wrong_count > 1}
     * （纯标准 UPDATE，无后端差异），单条 SQL 同时完成"减 1"与"是否应删行"的判定，
     * 消除原先"decrement → getWrongCount 读回 → removeDifficultWord"三段独立取锁
     * 之间被并发 increment 插入导致的状态漂移。
     *
     * <p><b>返回语义（三态，见 {@link DecrementResult}）</b>：{@link DecrementResult#DECREMENTED}
     * = 已减 1 且仍 &gt; 0；{@link DecrementResult#NO_ROW_TO_DECREMENT} = 可删行；
     * {@link DecrementResult#FAILED} = 写库失败，<b>不可</b>当业务信号消费。
     * 旧实现这里返回 {@code boolean}，{@code catch} 分支也 {@code return false}，
     * 与"0 行被改"逐字节同形 —— 一次 {@code SQLITE_BUSY} 就能让调用方删掉整行。
     *
     * <p>残余乱序窗口（同一玩家的 increment 异步任务整体晚于本 decrement 执行）在
     * 调用方 {@code PlayerDataStore.decrementWrongCount} 注明，最坏结果 = 词留书
     * （可重试清除，无数据损坏）。
     */
    public DecrementResult decrementWrongCount(UUID playerId, String word) {
        String sql = "UPDATE " + tablePrefix + "difficult_words SET wrong_count = wrong_count - 1 WHERE player_uuid = ? AND word = ? AND wrong_count > 1";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                int rows = stmt.executeUpdate();
                return rows > 0 ? DecrementResult.DECREMENTED : DecrementResult.NO_ROW_TO_DECREMENT;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to decrement wrong count for " + playerId + " / " + word, e);
                // 写失败 = 结果未知。绝不返回 NO_ROW_TO_DECREMENT：那会让调用方删行。
                return DecrementResult.FAILED;
            }
        }
    }

    // ============================================================
    //  Statistics HTTP Dashboard queries
    // ============================================================

    /**
     * R7-B ③：单词挑战的当日快照（{@link #loadWordChallengeSnapshot} 的返回体）。
     *
     * <p><strong>R6-E</strong>：原先的 {@code allCompleted} 字段已删除——它算出来后
     * 从未被任何调用方读取（调用方一律用缓存的
     * {@code DailyWordChallengeState.allCompleted()}），留着只会误导"业务规则只存在一处"
     * 的判断。业务判定请走缓存侧的 {@code allCompleted()}；SQL 口径的等价判定见
     * {@link #loadWordChallengeCompletion(UUID, String)}（rollover 补发路径仍在用）。</p>
     *
     * <p>集合均为不可变快照（调用方不得写入；缓存侧自行复制）。</p>
     *
     * <p><b>R-16：本记录<b>不带</b> {@code readFailed}</b> —— 三态位挂在
     * {@link WordChallengeSnapshotLoad} 上（照 {@link DailyChallengeWordsLoad} 的分层）。
     * 单独拿到一个 {@code WordChallengeSnapshot} 时你<b>无法</b>判断它是"今天确实没有
     * 挑战词"还是"读失败后的半截结果"，故不要用它的 {@code words().isEmpty()} 做任何
     * 分配/发奖判定。</p>
     *
     * @param words          今日分配的全部挑战词 id
     * @param completedWords 其中已完成的词 id
     * @param rewardPaid     当日奖励台账（{@code daily_word_reward_claims}）是否已有行
     */
    public record WordChallengeSnapshot(Set<String> words, Set<String> completedWords,
                                        boolean rewardPaid) {

        /** 防御性复制 + 只读包装：调用方持有的可变集合不会反向影响快照。 */
        public WordChallengeSnapshot {
            words = Collections.unmodifiableSet(new HashSet<>(words));
            completedWords = Collections.unmodifiableSet(new HashSet<>(completedWords));
        }
    }

    /**
     * Daily statistics record for the 7-day trend chart.
     * sessions 口径（P3-02）：学习日次 = 当日 player_sessions 行数（每人每日一行）。
     */
    public record DailyStats(String date, int sessions, int players, int wordsLearned) {}

    /**
     * Count distinct words that have been learned by any player.
     */
    public int getTotalWordCount() {
        String sql = "SELECT COUNT(DISTINCT word) FROM " + tablePrefix + "word_progress";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total word count", e);
            }
        }
        return 0;
    }

    /**
     * Count distinct players who were active today (have a session record for today).
     */
    public int getTodayActivePlayers() {
        String today = LocalDate.now().toString();
        String sql = "SELECT COUNT(DISTINCT uuid) FROM " + tablePrefix
                + "player_sessions WHERE session_date = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, today);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query today active players", e);
            }
        }
        return 0;
    }

    /**
     * Count total learning sessions.
     *
     * <p>P3-02 收口：原查 learning_log（历史遗留表，全库零写入），现改走
     * player_sessions。口径为"总学习日次"（per-player per-day 行数；
     * player_sessions 受 UNIQUE(uuid, session_date) UPSERT 合并，每人每日一行）。
     *
     * <p>Block C2（A5/R3-M4）双口径说明：本方法返回 player_sessions 行数
     * （**天数口径**）——管理报表/统计 HTTP 消费。如需**局数口径**（每次会话结束
     * +1、同日多局累计）走 {@code player_data.total_sessions}（内存侧
     * {@code PlayerDataStore.PlayerStats.getTotalSessions}，成就判定以该口径为准）。
     * 两值同屏展示（如 ProfileGUI 与统计面板）数值可不同，属既定口径差，非缺陷。
     */
    public int getTotalSessions() {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "player_sessions";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total sessions", e);
            }
        }
        return 0;
    }

    /**
     * Sum lifetime coins earned from all players.
     */
    public long getTotalCoinsEarned() {
        String sql = "SELECT COALESCE(SUM(total_coins_earned), 0) FROM " + tablePrefix + "player_data";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total coins earned", e);
            }
        }
        return 0L;
    }

    /**
     * Sum coin spending recorded by this plugin.
     */
    public long getTotalCoinsSpent() {
        String sql = "SELECT COALESCE(SUM(total_coins_spent), 0) FROM " + tablePrefix + "player_data";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total coins spent", e);
                return 0L;
            }
        }
    }

    public boolean recordCoinsEarned(UUID playerId, double amount) {
        return adjustCoins(playerId, "total_coins_earned", amount);
    }

    public boolean recordCoinsSpent(UUID playerId, double amount) {
        return adjustCoins(playerId, "total_coins_spent", amount);
    }

    /**
     * {@code total_coins_earned} / {@code total_coins_spent} 的写穿真源（统计与成就用）。
     *
     * <p><b>R11：返回 {@code boolean}（原为 {@code void}）。</b>原实现吞掉 {@code SQLException}
     * 只记 WARNING —— 写失败 = 玩家的金币流水漏记，统计偏少且无任何可察觉信号。
     *
     * <p><b>参数被拒时返回 {@code true} 而不是 {@code false}</b>：{@code amount <= 0} 或列名
     * 不在白名单里属于「调用方给了一个不需要写的东西」，不是「写库失败」。把它报成
     * {@code false} 会让调用方去重试一个永远不会成功、也不该成功的操作。
     *
     * @return {@code true} = 已写入（或参数无需写入）；{@code false} = 写库失败
     */
    private boolean adjustCoins(UUID playerId, String column, double amount) {
        if (amount <= 0 || !(column.equals("total_coins_earned") || column.equals("total_coins_spent"))) {
            return true;
        }
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, " + column + ", updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET " + column + " = COALESCE(" + column + ", 0) + excluded." + column + ", updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE " + column + " = COALESCE(" + column + ", 0) + VALUES(" + column + "), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setLong(2, Math.round(amount));
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to record coin transaction for " + playerId, e);
                return false;
            }
        }
    }

    /**
     * {@code total_coins_earned} 的<b>两态退化视图</b>（
     * {@link #getCoinsEarnedWithStatus} 的折叠版）。
     *
     * <p><b>语义</b>：与改造前逐字节一致 —— {@code READ_FAILED} 与「无行」都折成 {@code 0}。
     * 保留它<b>不是</b>因为它是个好接口，而是因为三个调用方（
     * {@code AchievementManager} 的成就判定与成就墙进度、{@code EnglishSquareGUI} 的自己头像）
     * 本来就把「未知」渲染成 0，改它们 = 成本 + 0 收益。范式的先例是
     * {@code clearSeasonLedgerPending} / {@code clearSeasonLedgerPendingDetailed} 那一对。
     *
     * <p><b>凡是要区分「读失败」与「确实是 0」的调用方必须改用三态版</b>；
     * 继续用本方法就会把「不知道」固化成「确认的 0」。
     *
     * @param playerId the player
     * @return 库内累计金币；无行或读失败均为 {@code 0}
     */
    public long getCoinsEarned(UUID playerId) {
        return getCoinsEarnedWithStatus(playerId).value();
    }

    /**
     * R22-D2：{@code total_coins_earned} 读取结果的<b>可分辨</b>状态。
     *
     * <p><b>为什么需要它</b>：{@link #getCoinsEarned} 在 {@code SQLException} 时
     * {@code return 0L}，与「这个玩家确实赚了 0 枚」逐字节同形。对展示型调用方无害，
     * 但 {@code LLEPapiExpansion.scheduleCoinsLoad} 的 javadoc 逐字承诺
     * 「<b>绝不</b>把失败折成 0 写进缓存 —— 那会把「不知道」固化成「确实是 0」」，
     * 而它的 {@code catch (RuntimeException)} 根本收不到 SQL 失败（异常在
     * {@code getCoinsEarned} 内部就被吃掉了）—— 那段承诺是死代码，
     * 且失败值会走 60s「确认值」TTL 而不是 15s 退避，记分板对本人显示可信的假 0。
     *
     * <p><b>为什么只有两态</b>：{@code player_data} 无行 ≡ 0 枚（此人确实没赚过），
     * 两者语义相同，不像 {@code MinSeasonIdResult} 那样有「表空 vs 有值」的业务分支。
     * 真正需要区分的只有「读到了」与「读失败」。
     */
    public enum CoinsReadResult {
        /** 查询成功：{@code value} 是库内真值（无行为 0）。 */
        PRESENT,
        /** 查询/连接失败（已记 WARNING）：结果<b>未知</b>，不得当作 0。 */
        READ_FAILED
    }

    /**
     * {@link CoinsReadResult} 的带值版本（{@code PRESENT} 时携带库内值）。
     *
     * @param result 状态
     * @param value  已确认的库内值；{@code READ_FAILED} 时无意义（恒 0）
     */
    public record CoinsEarnedRead(CoinsReadResult result, long value) {

        /** 读失败的共享实例（避免每次失败都新建对象 —— PAPI 每秒可能读多次）。 */
        public static final CoinsEarnedRead FAILED = new CoinsEarnedRead(CoinsReadResult.READ_FAILED, 0L);

        /** 已确认值的工厂方法。 */
        public static CoinsEarnedRead present(long value) {
            return new CoinsEarnedRead(CoinsReadResult.PRESENT, value);
        }

        /** @return {@code true} = 本次读取失败，{@code value} 不可信 */
        public boolean readFailed() {
            return result == CoinsReadResult.READ_FAILED;
        }
    }

    /**
     * 三态版 {@code total_coins_earned} 读取（见 {@link CoinsEarnedRead}）。
     *
     * <p>SQL 与异常处理与 {@link #getCoinsEarned} 完全一致（失败仍落
     * {@code Level.WARNING} "Failed to query coins for ..." —— 服主需要这条一手信号），
     * 唯一差别是失败时返回 {@link CoinsEarnedRead#FAILED} 而不是一个看起来可信的 0。
     *
     * @param playerId the player
     * @return {@code PRESENT}（含无行的 0）或 {@code READ_FAILED}
     */
    public CoinsEarnedRead getCoinsEarnedWithStatus(UUID playerId) {
        String sql = "SELECT total_coins_earned FROM " + tablePrefix + "player_data WHERE uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return CoinsEarnedRead.present(rs.next() ? rs.getLong(1) : 0L);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query coins for " + playerId, e);
                return CoinsEarnedRead.FAILED;
            }
        }
    }

    /**
     * R-28：持久化玩家 AI 对话累计次数（绝对值 UPSERT）。
     *
     * <p>由 {@code PlayerDataStore.incrementConversationCount} 在每次有效对话结束后
     * 即时调用，使 AI_CHAT_10/50 成就计数跨重启不丢。写绝对值而非增量，调用方以缓存
     * 为权威先 +1 再落库，重复落库幂等；不受 quit 事件两个监听器
     * （ChatScenarioListener 结束对话 vs PlayerJoinListener 卸载缓存）执行顺序影响。
     *
     * @param playerId the player
     * @param count    the new absolute conversation count
     */
    public void setConversationCount(UUID playerId, int count) {
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, conversation_count, updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET conversation_count = excluded.conversation_count, updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE conversation_count = VALUES(conversation_count), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, count);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist conversation_count for " + playerId, e);
            }
        }
    }

    /**
     * Phase 3 S-A：读取玩家引导进度（{@code player_data.onboarding_step}）。
     *
     * <p>无行（从未落库的新玩家）→ 0，与 OnboardingManager.STEP_UNINITIALIZED 对齐。
     * 读侧只此一条小 SELECT（OnboardingManager 于 join 时同步调用，模式同
     * loadProfileSettings）。
     *
     * @param playerId the player
     * @return the persisted onboarding step, 0 if no row exists
     */
    public int getOnboardingStep(UUID playerId) {
        OnboardingLoad load = loadOnboardingStep(playerId);
        if (load.readFailed()) {
            // 旧签名无法表达"读失败"，返回 0 会被当成 STEP_UNINITIALIZED（老号引导被写死为完成）。
            // 故对读失败直接抛出，杜绝任何调用方再把失败折叠成 0。
            throw new IllegalStateException("onboarding_step read failed for " + playerId);
        }
        return load.step();
    }

    /** R4-07-eh-02：读面三态（照 {@link CheckInLoad} 范式）——读失败与"确实 0"必须可区分。 */
    public record OnboardingLoad(int step, boolean readFailed) {}

    public OnboardingLoad loadOnboardingStep(UUID playerId) {
        String sql = "SELECT onboarding_step FROM " + tablePrefix + "player_data WHERE uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return new OnboardingLoad(rs.next() ? rs.getInt(1) : 0, false);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query onboarding_step for " + playerId, e);
                return new OnboardingLoad(0, true);
            }
        }
    }

    /**
     * Phase 3 S-A：持久化玩家引导进度（绝对值 UPSERT，同 {@link #setConversationCount} pattern）。
     *
     * <p>由 {@code OnboardingManager.advanceStep} 在 step 单调推进时调用；写绝对值而非
     * 增量，重复落库幂等。仅写 onboarding_step 列，与 savePlayerData 的 UPSERT
     * 互不覆盖（各自显式列名）。
     *
     * @param playerId the player
     * @param step     the new absolute onboarding step
     */
    public void setOnboardingStep(UUID playerId, int step) {
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, onboarding_step, updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET onboarding_step = excluded.onboarding_step, updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE onboarding_step = VALUES(onboarding_step), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, step);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist onboarding_step for " + playerId, e);
            }
        }
    }

    /**
     * Phase 3 S-A：持久化玩家错题本累计攻克词数（绝对值 UPSERT，同 {@link #setConversationCount} pattern）。
     *
     * <p>由 {@code PlayerDataStore.incrementWrongBookCleared} 在练习答对导致词语移出
     * 错题本时调用，使 WRONG_BOOK_CLEARED 成就计数跨重启不丢。写绝对值而非增量，
     * 调用方以缓存为权威先 +1 再落库，重复落库幂等；列名避开 word_progress 的
     * INSERT OR REPLACE 全列覆盖陷阱（P3-09），与 word 级数据互不干扰。
     *
     * @param playerId the player
     * @param count    the new absolute cleared-word count
     */
    public void setWrongBookCleared(UUID playerId, int count) {
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, wrong_book_cleared, updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET wrong_book_cleared = excluded.wrong_book_cleared, updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE wrong_book_cleared = VALUES(wrong_book_cleared), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, count);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist wrong_book_cleared for " + playerId, e);
            }
        }
    }

    /**
     * Get daily statistics breakdown for the last 7 days.
     * 数据全部来自 player_sessions（P3-02 收口：learning_log 为历史遗留表，
     * 全库零写入，不再参与统计）：players/wordsLearned 按 uuid/words_count 聚合；
     * sessions 按 session_date 分组计数。session 口径为"学习日次"——player_sessions
     * 每人每日一行，故每日 sessions 数值上等于当日 players。
     */
    public List<DailyStats> getLast7DaysStats() {
        Map<String, int[]> dailyData = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        // Initialize all 7 days (including today) with zeros
        for (int i = 6; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            dailyData.put(date, new int[]{0, 0, 0}); // [sessions, players, wordsLearned]
        }

        // Query player_sessions for daily players and words
        String sessionsSql = "SELECT session_date, COUNT(DISTINCT uuid) as players, "
                + "COALESCE(SUM(words_count), 0) as words "
                + "FROM " + tablePrefix + "player_sessions "
                + "WHERE session_date >= ? "
                + "GROUP BY session_date";
        Connection conn;
        synchronized (connectionLock) {
            // E-04：取连接移入锁内（原锁外引用跨语句块复用，重连即失效）。
            conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sessionsSql)) {
                stmt.setString(1, today.minusDays(6).toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String date = rs.getString("session_date");
                        int[] data = dailyData.get(date);
                        if (data != null) {
                            data[1] = rs.getInt("players");
                            data[2] = rs.getInt("words");
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query daily player stats", e);
            }
        }

        // P3-02 收口：每日会话数改走 player_sessions（learning_log 零写入历史遗留）。
        // session_date 为 LocalDate 字符串，与 7 天 key 同构可比（两后端同构先例：
        // PlayerDataStore.getRankedPlayersForPeriod）。
        String sessionsCountSql = "SELECT session_date, COUNT(*) as cnt "
                + "FROM " + tablePrefix + "player_sessions "
                + "WHERE session_date >= ? "
                + "GROUP BY session_date";
        synchronized (connectionLock) {
            // E-04：上一个锁块已释放，重连可能已替换引用，此处锁内重取。
            conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sessionsCountSql)) {
                stmt.setString(1, today.minusDays(6).toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String day = rs.getString("session_date");
                        int[] data = dailyData.get(day);
                        if (data != null) {
                            data[0] = rs.getInt("cnt");
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query daily session counts", e);
            }
        }

        List<DailyStats> result = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : dailyData.entrySet()) {
            int[] d = entry.getValue();
            result.add(new DailyStats(entry.getKey(), d[0], d[1], d[2]));
        }
        return result;
    }

    // ============================================================
    //  Daily Check-in
    // ============================================================

    /**
     * Get a player's check-in data (last date and streak).
     * Returns null if the player has never checked in.
     */
    public CheckInData getCheckInData(UUID playerId) {
        return loadCheckInData(playerId).data();
    }

    /**
     * EH-03（round3）：{@link #getCheckInData} 的可区分版本。
     *
     * <p>原实现把「读库失败」与「确实从未签到」都返回 {@code null}，签到逻辑据此
     * 判成"首次签到"，把玩家的真实 {@code streak} 覆盖为 1 —— DB 抖一次就毁掉
     * 连续天数。本版本用 {@code readFailed} 把两者分开，调用方必须区别对待：
     * 读失败时<b>不得写库</b>，并如实回报失败。
     */
    public record CheckInLoad(CheckInData data, boolean readFailed) {}

    public CheckInLoad loadCheckInData(UUID playerId) {
        String sql = "SELECT last_checkin_date, streak_count FROM " + tablePrefix
                + "checkin_data WHERE player_uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String lastDate = rs.getString("last_checkin_date");
                        int streak = rs.getInt("streak_count");
                        return new CheckInLoad(new CheckInData(lastDate, streak), false);
                    }
                }
                return new CheckInLoad(null, false); // 确实没有记录
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get check-in data for " + playerId, e);
                return new CheckInLoad(null, true); // 读失败，与"没有记录"不同
            }
        }
    }

    /**
     * 多服签到抢占的三态结果。
     *
     * <p><b>为什么不能用 boolean</b>：原来的 {@code updateCheckIn} 返回 boolean，
     * {@code false} 同时承载两件处置完全不同的事（{@code CheckInManager} 的
     * R11-M4 注释已经把这个隐患写在纸上，只是当时靠"读侧先判过今日已签"绕开了，
     * 多服共享 MySQL 时绕不开）：
     * <ol>
     *   <li><b>今日已被（他服或本服并发）抢走</b> —— 正常的幂等拒绝，
     *       玩家该看到"今天已经签过了"，<b>不重试</b>；</li>
     *   <li><b>SQL 失败</b>（{@code SQLITE_BUSY} / MySQL 死锁 / 磁盘满）——
     *       本次根本没抢，玩家该看到"请稍后再试"。若折叠成 ①，玩家不再重试、
     *       当日 streak 断掉。</li>
     * </ol>
     * 更糟的是原实现的 {@code false} 连 ① 都表达不出来：裸 UPSERT 恒 {@code return true}。
     * 于是两台服共享库时同一玩家同一天能签两次、领双份真钱 —— 读与写之间没有任何 fencing。
     *
     * <p>结构与措辞照抄同族的两个先例：{@link SettlementClaim}（R15 对
     * {@code tryClaimSettlement} 的改造）与 {@link LedgerClearResult}（R-16 对
     * {@code clearSeasonLedgerPendingDetailed} 的改造）。
     *
     * <ul>
     *   <li>{@link #CLAIMED} —— 本次调用<b>真的把"今天"写进了库</b>（条件 UPDATE 命中 1 行，
     *       或首次插入成功）。这是唯一允许发钱的结局；</li>
     *   <li>{@link #TAKEN} —— <b>今天已经被写过</b>（条件 UPDATE 命中 0 行且 IGNORE 插入
     *       也是 0 行）= 他服/本服并发抢在前面。<b>正确的多服结局</b>，不是故障：
     *       调用方按"今日已签到"提示，<b>不重试、不发钱</b>；</li>
     *   <li>{@link #FAILED} —— <b>未知</b>：SQL 抛了异常，本次既没抢到也没被拒。
     *       调用方必须给"稍后再试"，<b>不能</b>当成"已签到"（否则当日 streak 白断）。</li>
     * </ul>
     */
    public enum CheckInClaim {
        /** 本次调用写入了"今天"：唯一可以发钱的结局。 */
        CLAIMED,
        /** 今天已被写过（他服 / 本服并发）：正常的幂等拒绝，不发钱、不重试。 */
        TAKEN,
        /**
         * SQL 异常：本次既没抢到也没被拒。<b>不得</b>折叠成 {@link #TAKEN}
         * —— 那会让玩家不再重试、把当天的连续天数白断掉。
         */
        FAILED
    }

    /**
     * 条件写入签到台账 —— <b>多服单发保护</b>（对齐
     * {@link #claimSettlement} / {@link #markSeasonRewardClaimed} 的"affected rows 判定谁赢"）。
     *
     * <h2>为什么不是 UPSERT</h2>
     * 原实现（{@code updateCheckIn}，本轮删除）是裸 UPSERT，且把
     * {@code executeUpdate()} 的返回值直接丢弃、恒 {@code return true}。两台服共享
     * MySQL 时"读 → 判今日已签 → 写 → 发钱"这条链上读与写之间没有任何 fencing，
     * 两边都读到"今天还没签"→ 两边都写 → 两边都发钱。
     *
     * <h2>为什么不用 UPSERT + affected rows（这是本方法最容易写错的地方）</h2>
     * 两种后端的 UPSERT 在 affected rows 上<b>语义不同，且 MySQL 还会随驱动参数漂移</b>。
     * 本机实测（sqlite-jdbc 3.45.1.0 / mysql-connector-j 8.3.0，真库，非文档推演）：
     * <pre>
     *   SQLite  INSERT OR REPLACE  首次插入=1  同值替换=1  值变化替换=1
     *   MySQL   ON DUPLICATE KEY UPDATE（驱动默认）
     *                              首次插入=1  冲突且值相同=1  冲突且值变化=2
     *   MySQL   同上 + useAffectedRows=true（CLIENT_FOUND_ROWS）
     *                              首次插入=1  冲突且值相同=<b>0</b>  冲突且值变化=2
     * </pre>
     * 也就是说"值变了=2"、"值未变"在 1 与 0 之间随连接参数摆动 —— 任何
     * {@code == 1} / {@code >= 1} 的判定都会在某档驱动参数下把"我今天写的"读成"别人写的"
     * （或反过来，把"别人写的"读成"我写的" = 又发一次钱）。故<b>不使用</b> UPSERT。
     *
     * <h2>采用的条件写法（两后端、两档驱动参数下逐字节一致）</h2>
     * <pre>
     *   ① UPDATE checkin_data SET last_checkin_date=?, streak_count=?
     *        WHERE player_uuid=? AND (last_checkin_date IS NULL OR last_checkin_date &lt;&gt; ?)
     *      → 1 行 = 本次把今天写进去了 → CLAIMED
     *   ② ①命中 0 行时再 INSERT OR IGNORE / INSERT IGNORE
     *      → 1 行 = 库里本来没有行（首次签到）+ 这次是我插的 → CLAIMED
     *      → 0 行 = 行已存在且日期就是今天（他服/并发抢先） → TAKEN
     * </pre>
     * 实测（同上，真库）：条件 UPDATE 在"日期不同=1 / 日期相同=0 / 行不存在=0"、
     * IGNORE 插入在"首次=1 / 主键冲突=0"—— 两种后端、两档 {@code useAffectedRows}
     * 全部一致。<b>这两个 0 含义不同，必须分开</b>：②的 0 是"今天已被抢走"（TAKEN），
     * 而①的 0 只说明"行不存在或日期已是今天"，要靠②再分一次。把两者混成一个 0，
     * 就会把首次签到误判成"今日已签"（新玩家永远签不上）。
     *
     * <p>①的 WHERE 带 {@code last_checkin_date IS NULL} 是防御：列声明是 NOT NULL，
     * 但历史库/手工改库可能留 NULL，而 {@code NULL <> '2026-01-01'} 求值为 NULL → 0 行，
     * 随后②又会因主键冲突返回 0 → 该玩家被永久判成"今日已签"。（正常库里不触发。）
     *
     * <p><b>跨进程原子性</b>：本进程内两条语句串行在 {@link #connectionLock} 上；
     * 跨进程由后端行锁/UPSERT 冲突保证"只有一个赢家"——InnoDB 的第二个 INSERT 会在
     * 对方提交后拿到主键冲突（IGNORE → 0 行）；SQLite 侧若撞锁则抛
     * {@code SQLITE_BUSY} → {@link CheckInClaim#FAILED}，方向是<b>不发钱</b>（fail-closed），
     * 绝不会变成第二份收入。
     *
     * <p><b>墓碑</b>：boolean 形态的 {@code updateCheckIn} 已<b>删除</b>，不保留退化视图。
     * 理由同 {@code TwoStateLegacyLintTest} 的纪律 —— 这里保留 boolean 版会再造一个
     * "把 {@link CheckInClaim#FAILED} 折叠成 Today 已签 / 或恒 true" 的活口子，
     * 任何人误用就立刻把本条缺陷搬回来。而表达不出三态的接口，比没有接口更危险。
     *
     * <p><b>已知残留向量（本方法的胜负判据<b>不</b>覆盖）：</b>判赢的条件是
     * 「本次传入的 {@code date} 与库里日期<b>不相等</b>」。于是若两台服对"今天"给出
     * <b>不同</b>的字符串（各服 JVM 时区不同 / 一服 NTP 跳变 / 夏令时切换那一小时），
     * 两次调用<b>都会</b>返回 {@link CheckInClaim#CLAIMED}。真 SQLite 实测：
     * 服 A（UTC+8）{@code 2026-03-01} → CLAIMED，服 B（UTC-5）{@code 2026-02-28}
     * → 同样 CLAIMED。根因是 {@code checkin_data} 只存"最后一次签到日期"这一个当前态，
     * <b>结构上</b>表达不了"同一天两个不同日期值"。彻底收口需要改胜负判据的来源
     * （库侧 {@code CURRENT_DATE}）或另建按日期去重的台账表，超出本条修复的授权范围，
     * 故在此显式记录，并由 {@code CheckInCrossServerClaimTest} 的
     * {@code differingServerDatesAreStillASecondPayoutVector} 用例钉住当前实测行为。
     *
     * @param playerId    玩家
     * @param date        本次签到日（{@code yyyy-MM-dd}）
     * @param streakCount 本次应写入的连续天数
     * @return 见 {@link CheckInClaim}
     */
    public CheckInClaim claimCheckIn(UUID playerId, String date, int streakCount) {
        // ① 条件 UPDATE：日期不同（或为 NULL）才算"我写的"。两后端语义一致。
        String updateSql = "UPDATE " + tablePrefix + "checkin_data"
                + " SET last_checkin_date = ?, streak_count = ?"
                + " WHERE player_uuid = ?"
                + "   AND (last_checkin_date IS NULL OR last_checkin_date <> ?)";
        // ② 首次签到才需要插入；IGNORE 的 affected rows 在两种后端上一致（1=插入 / 0=冲突）。
        // 与 markDailyWordRewardClaimed 同一范式（SQLite INSERT OR IGNORE / MySQL INSERT IGNORE）。
        String insertSql = isSQLite()
                ? "INSERT OR IGNORE INTO " + tablePrefix
                        + "checkin_data (player_uuid, last_checkin_date, streak_count) VALUES (?, ?, ?)"
                : "INSERT IGNORE INTO " + tablePrefix
                        + "checkin_data (player_uuid, last_checkin_date, streak_count) VALUES (?, ?, ?)";
        synchronized (connectionLock) {
            try {
                Connection conn = getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    stmt.setString(1, date);
                    stmt.setInt(2, streakCount);
                    stmt.setString(3, playerId.toString());
                    stmt.setString(4, date);
                    if (stmt.executeUpdate() == 1) {
                        return CheckInClaim.CLAIMED;
                    }
                }
                // ①命中的 0 行有两种含义（行不存在 / 日期已是今天），必须靠②再分一次。
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, playerId.toString());
                    stmt.setString(2, date);
                    stmt.setInt(3, streakCount);
                    // 1 行 = 首次签到且是我插的；0 行 = 行已在且日期就是今天 = 别人先签了。
                    return stmt.executeUpdate() == 1 ? CheckInClaim.CLAIMED : CheckInClaim.TAKEN;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to claim daily check-in for " + playerId + " / " + date
                                + " (streak " + streakCount + ") — the claim was NOT recorded;"
                                + " callers must treat this as 'try again', not as 'already"
                                + " checked in' (folding the two would silently break the streak)",
                        e);
                return CheckInClaim.FAILED;
            }
        }
    }

    // ============================================================
    //  Square Social (Feed + Cheers) —— Wave 2 域 4
    // ============================================================

    /**
     * 一条动态流缓存行（{@code social_feed} 的读取投影 + JOIN 出的作者名）。
     * 只承载原始数据；语言侧渲染由 {@code SocialManager}/{@code FeedTextFormatter} 完成。
     */
    public record FeedRow(long id, UUID authorId, String authorName, String eventType,
                          int eventsCount, int sumCorrect, int sumTotal, String lastMode,
                          int lastStreak, String achievementId, int cheerCount, long updatedAt) { }

    /**
     * {@link #cheerFeed} 的结果四态：写入成功 / 已鼓励过 / 条目已不存在 / 写库失败。
     *
     * <p>{@link #FAILED} 与 {@link #GONE} <b>必须</b>在任何读侧状态下都不可混淆：前者是
     * 「库没写进去」，后者是「行真的不在了」。把 {@code SQLException} 折进 {@code GONE}
     * 会让写库故障（锁超时 / 磁盘满 / 只读存储 / MySQL read_only）被谎报成业务事实
     * （玩家看到「这条动态已经翻篇了」），且本仓默认 {@code journal_mode=WAL}——
     * WAL 的设计目的正是「写时读不阻塞」，所以「写失败 + 读成功」是主路径而非边角，
     * 不能用读侧健康度反推写侧结果。
     */
    public enum CheerResult { OK, ALREADY, GONE, FAILED }

    /**
     * 动态流写入（UPSERT 合并，无读-改-写）。
     *
     * <p>合并键 {@code UNIQUE(player_uuid, feed_date, event_type)}：同日同类型累加
     * {@code events_count} 与 SESSION_DONE 的 {@code sum_correct/sum_total}；覆写
     * "最近一次"列（{@code last_mode}/{@code last_streak}/{@code achievement_id}，
     * 用 COALESCE/IF 保护"本次为 null/0 时不抹掉旧值"的跨类型场景）；刷新
     * {@code updated_at}（排序键）。鼓励不改 updated_at，排序稳定。
     *
     * @param playerId      动态作者
     * @param eventType     SESSION_DONE / ACHIEVEMENT / CHECKIN
     * @param correctDelta  SESSION_DONE 本次答对（其余类型传 0）
     * @param totalDelta    SESSION_DONE 本次题数（其余类型传 0）
     * @param lastMode      SESSION_DONE 本次模式（其余类型传 null）
     * @param lastStreak    CHECKIN 连续天数（其余类型传 0）
     * @param achievementId ACHIEVEMENT 成就 id（其余类型传 null）
     */
    public void upsertFeedEvent(UUID playerId, String eventType, int correctDelta, int totalDelta,
                                String lastMode, int lastStreak, String achievementId) {
        String now = LocalDate.now().toString();
        long ts = System.currentTimeMillis();
        String sql;
        if (isSQLite()) {
            sql = "INSERT INTO " + tablePrefix + "social_feed (player_uuid, feed_date, event_type,"
                    + " events_count, sum_correct, sum_total, last_mode, last_streak, achievement_id,"
                    + " cheer_count, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 0, ?, ?)"
                    + " ON CONFLICT(player_uuid, feed_date, event_type) DO UPDATE SET"
                    + " events_count = events_count + 1,"
                    + " sum_correct = sum_correct + excluded.sum_correct,"
                    + " sum_total = sum_total + excluded.sum_total,"
                    + " last_mode = COALESCE(excluded.last_mode, last_mode),"
                    + " last_streak = CASE WHEN excluded.last_streak > 0 THEN excluded.last_streak ELSE last_streak END,"
                    + " achievement_id = COALESCE(excluded.achievement_id, achievement_id),"
                    + " updated_at = excluded.updated_at";
        } else {
            sql = "INSERT INTO " + tablePrefix + "social_feed (player_uuid, feed_date, event_type,"
                    + " events_count, sum_correct, sum_total, last_mode, last_streak, achievement_id,"
                    + " cheer_count, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 0, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE"
                    + " events_count = events_count + 1,"
                    + " sum_correct = sum_correct + VALUES(sum_correct),"
                    + " sum_total = sum_total + VALUES(sum_total),"
                    + " last_mode = COALESCE(VALUES(last_mode), last_mode),"
                    + " last_streak = IF(VALUES(last_streak) > 0, VALUES(last_streak), last_streak),"
                    + " achievement_id = COALESCE(VALUES(achievement_id), achievement_id),"
                    + " updated_at = VALUES(updated_at)";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, now);
                stmt.setString(3, eventType);
                stmt.setInt(4, Math.max(0, correctDelta));
                stmt.setInt(5, Math.max(0, totalDelta));
                stmt.setString(6, lastMode);
                stmt.setInt(7, lastStreak);
                stmt.setString(8, achievementId);
                stmt.setLong(9, ts);
                stmt.setLong(10, ts);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to upsert feed event " + eventType + " for " + playerId, e);
            }
        }
    }

    /**
     * 动态流缓存读取（FeedCache 刷新专用）。
     *
     * <p>读取侧隐私过滤与榜单同款：{@code LEFT JOIN player_profiles} +
     * {@code COALESCE(show_in_square,1) <> 0}（隐身者条目对所有人隐藏，含本人）。
     * {@code retentionDays <= 0} = 不设日期下限（永久保留）。作者名经
     * {@code LEFT JOIN player_data} 取（MAX() 包住兼容 ONLY_FULL_GROUP_BY；本方法
     * 可能在异步线程被调用，结果消费阶段不得调 Bukkit API），空回落 uuid 字符串。
     *
     * @param retentionDays 保留的自然日天数（含今天）；&lt;=0 表示不过滤
     * @param limit         返回行数上限（按 updated_at 倒序）
     */
    public List<FeedRow> getFeedCacheRows(int retentionDays, int limit) {
        List<FeedRow> rows = new ArrayList<>();
        // 两个 LEFT JOIN 的关联列都是各自表的主键（player_data.uuid / player_profiles.uuid
        // 均为 PRIMARY KEY），因此是严格的 1:1，不会产生行乘数 —— 既不需要 MAX() 去重，
        // 也不需要 GROUP BY 收敛。
        //
        // 为什么不写 GROUP BY f.id（看着更短）：MariaDB 没有实现函数依赖检测，
        // 在 ONLY_FULL_GROUP_BY 下 `GROUP BY 主键 + 取其他非聚合列` 会直接报
        // ERROR 1055 'xxx isn't in GROUP BY'（本机 MariaDB 13.0.2 实测）。而写成
        // 全列 GROUP BY 会让 SQLite 把执行计划从「覆盖索引范围扫」降级成「全表扫」。
        // 两条路都更差，所以直接不写 GROUP BY。
        String sql = "SELECT f.id, f.player_uuid, p.player_name,"
                + " f.event_type, f.events_count, f.sum_correct, f.sum_total, f.last_mode,"
                + " f.last_streak, f.achievement_id, f.cheer_count, f.updated_at"
                + " FROM " + tablePrefix + "social_feed f"
                + " LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = f.player_uuid"
                + " LEFT JOIN " + tablePrefix + "player_profiles pf ON pf.uuid = f.player_uuid"
                + " WHERE COALESCE(pf.show_in_square, 1) <> 0";
        if (retentionDays > 0) {
            sql += " AND f.feed_date >= ?";
        }
        // 加 f.id 做 tie-break：updated_at 大量重复时顺序不定，会让分页/快照出现
        // 同一行出现两次或漏行。带 id 后仍走 idx_feed_updated（已实测不退化）。
        sql += " ORDER BY f.updated_at DESC, f.id DESC LIMIT ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                int idx = 1;
                if (retentionDays > 0) {
                    stmt.setString(idx++, feedRetentionCutoff(retentionDays));
                }
                stmt.setInt(idx, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID authorId;
                        try {
                            authorId = UUID.fromString(rs.getString("player_uuid"));
                        } catch (IllegalArgumentException e) {
                            continue; // 脏行跳过，不拖垮整次缓存刷新
                        }
                        String name = rs.getString("player_name");
                        if (name == null || name.isEmpty()) {
                            name = authorId.toString();
                        }
                        rows.add(new FeedRow(
                                rs.getLong("id"),
                                authorId,
                                name,
                                rs.getString("event_type"),
                                rs.getInt("events_count"),
                                rs.getInt("sum_correct"),
                                rs.getInt("sum_total"),
                                rs.getString("last_mode"),
                                rs.getInt("last_streak"),
                                rs.getString("achievement_id"),
                                rs.getInt("cheer_count"),
                                rs.getLong("updated_at")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load feed cache rows", e);
            }
        }
        return rows;
    }

    /**
     * 保留窗口下端（含今天共 retentionDays 个自然日）：{@code today - (retentionDays - 1)}。
     * 读缓存（{@code >= cutoff}）与清理（{@code < cutoff}）共用同一口径。
     */
    private static String feedRetentionCutoff(int retentionDays) {
        return LocalDate.now().minusDays(Math.max(0, retentionDays - 1)).toString();
    }

    /**
     * 鼓励一条动态。
     *
     * <p>同一锁内三步（仿 {@link #deletePlayerData} 事务化）：① 锁内复核 feed 行存在
     * （单连接串行，无 TOCTOU；缺行 → GONE）；② {@code INSERT OR IGNORE / INSERT IGNORE}
     * 进 {@code social_cheers}（受影响行数 0 → ALREADY，唯一约束在 DB 层兜底一人一次）；
     * ③ 插入成功才 {@code cheer_count = cheer_count + 1}（去规范化计数）。
     * ②③ 包在 {@code setAutoCommit(false)} 事务里（仿 savePlayerData），连接状态不外泄。
     */
    public CheerResult cheerFeed(long feedId, UUID cheererUuid) {
        String insertSql = isSQLite()
                ? "INSERT OR IGNORE INTO " + tablePrefix + "social_cheers (feed_id, cheerer_uuid, created_at, read_by_owner) VALUES (?, ?, ?, 0)"
                : "INSERT IGNORE INTO " + tablePrefix + "social_cheers (feed_id, cheerer_uuid, created_at, read_by_owner) VALUES (?, ?, ?, 0)";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try {
                // ① feed 行存在性（锁内复核）
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT 1 FROM " + tablePrefix + "social_feed WHERE id = ?")) {
                    check.setLong(1, feedId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) {
                            return CheerResult.GONE;
                        }
                    }
                }
                conn.setAutoCommit(false);
                try {
                    // ② 唯一约束兜底"一人一条一次"
                    int inserted;
                    try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                        stmt.setLong(1, feedId);
                        stmt.setString(2, cheererUuid.toString());
                        stmt.setLong(3, System.currentTimeMillis());
                        inserted = stmt.executeUpdate();
                    }
                    if (inserted == 0) {
                        conn.rollback();
                        return CheerResult.ALREADY;
                    }
                    // ③ 去规范化计数 +1
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE " + tablePrefix + "social_feed SET cheer_count = cheer_count + 1 WHERE id = ?")) {
                        stmt.setLong(1, feedId);
                        stmt.executeUpdate();
                    }
                    conn.commit();
                    return CheerResult.OK;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to cheer feed " + feedId + " by " + cheererUuid, e);
                // 写库故障必须自成一态，不得折进 GONE（那会把「库写不进去」谎报成
                // 「这条动态不存在」）。调用方据此给「请稍后再试」类提示并回收防抖戳。
                return CheerResult.FAILED;
            }
        }
    }

    /**
     * 鼓励者名单（"谁鼓励了我"），按鼓励时间升序。名字 {@code LEFT JOIN player_data}
     * 取，空回落 uuid；本方法异步可达，结果消费阶段不得调 Bukkit API。
     */
    public List<String> getCheerers(long feedId, int limit) {
        return loadCheerers(feedId, limit).names();
    }

    /** R4-17-EH-F3：读面三态（照 {@link CheckInLoad} 范式）——「读失败」不得与「确实无人鼓励」同形。 */
    public record CheerersLoad(List<String> names, boolean readFailed) {}

    /**
     * 鼓励者名单本体（"谁鼓励了我"）。
     *
     * <p><b>本方法刻意不过滤鼓励者自己的 {@code show_in_square} —— 这是产品裁决，
     * 不是遗漏。</b>裁决口径（用户在 R31 明确，原话：「隐身管的是其动态可不可见，
     * 不是其（比如鼓励）操作」）：
     * <ul>
     *   <li>{@code showInSquare == false} 的语义边界只有一条：<b>「我的动态」</b>
     *       （我在广场上发的那条 feed）不对他人展示。它由
     *       {@link #getFeedCacheRows} 的 {@code LEFT JOIN player_profiles} 实现。</li>
     *   <li>它<b>不遮蔽我的「操作」</b>（鼓励、签到等）留下的可见痕迹。鼓励是一次<b>操作</b>，
     *       不是一条<b>动态</b>；「谁鼓励了我」记的是操作，故不套用动态的可见性开关。</li>
     *   <li>因此隐身玩家的名字<b>仍然出现</b>在别人动态的鼓励者名单里，也仍然出现在
     *       {@code notify-online} 的即时提醒里 —— 两者都是「操作痕迹」，同一条口径。</li>
     * </ul>
     *
     * <p><b>不要"顺手补上"这条过滤。</b> 本项目曾经加过
     * {@code LEFT JOIN player_profiles pr} + {@code COALESCE(pr.show_in_square,1) <> 0}
     * 把它当成「隐私单向泄露」来修，被用户判定为<b>过度解读</b>并回退 ——
     * 那是把「动态可见性」开关错误地升格成了「一切行为痕迹的总开关」。
     * 榜单（{@link #getTopCoins}）与动态流（{@link #getFeedCacheRows}）过滤
     * {@code show_in_square} 是因为它们展示的正是<b>动态/展示面貌</b>，与本方法不同源。
     *
     * <p><b>名字来源与一致性</b>：名字走 {@code LEFT JOIN player_data p} 取，
     * 查不到行或无名字时回落 uuid（见下方读取循环）。正因为<b>没有</b>额外的过滤，
     * 名单长度应当与 {@code social_feed.cheer_count} 对得上（唯一例外是 {@code limit}
     * 截断，以及历史遗留的、{@code player_data} 已被清理的孤儿鼓励行 —— 后者回落 uuid，
     * 不会凭空少人）。
     */
    public CheerersLoad loadCheerers(long feedId, int limit) {
        List<String> names = new ArrayList<>();
        String sql = "SELECT c.cheerer_uuid, MAX(p.player_name) AS player_name"
                + " FROM " + tablePrefix + "social_cheers c"
                + " LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = c.cheerer_uuid"
                // 刻意不 JOIN player_profiles：showInSquare 管「我的动态是否可见」，
                // 不管「我的鼓励操作留下的痕迹是否可见」（用户 R31 裁决）。
                + " WHERE c.feed_id = ?"
                + " GROUP BY c.cheerer_uuid, c.created_at"
                + " ORDER BY c.created_at ASC LIMIT ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setLong(1, feedId);
                stmt.setInt(2, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("player_name");
                        if (name == null || name.isEmpty()) {
                            name = rs.getString("cheerer_uuid");
                        }
                        names.add(name);
                    }
                }
                return new CheerersLoad(names, false);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load cheerers for feed " + feedId, e);
                return new CheerersLoad(names, true);
            }
        }
    }

    /**
     * 把该玩家所有动态行上的鼓励标记为已读（进服摘要发出后调用）。
     * 子查询只涉及 {@code social_feed}（非被更新表），无 MySQL 更新-子查询自引用限制。
     */
    public void markFeedCheersRead(UUID ownerUuid) {
        // 隐蔽语义（plan-social §1.6 边界决策）：摘要只发给作者本人，不向第三方泄露；
        // 隐身期间收到的鼓励照常提醒。
        String sql = "UPDATE " + tablePrefix + "social_cheers SET read_by_owner = 1"
                + " WHERE feed_id IN (SELECT id FROM " + tablePrefix + "social_feed WHERE player_uuid = ?)";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, ownerUuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to mark feed cheers read for " + ownerUuid, e);
            }
        }
    }

    /**
     * 标记单条鼓励已读（作者在线即时提醒发出后调用）。
     * 作者恰在"提醒后立即退服"间隙的极小竞态允许下次进服重复提醒一次（宽容语义）。
     */
    public void markSingleCheerRead(long feedId, UUID cheererUuid) {
        String sql = "UPDATE " + tablePrefix + "social_cheers SET read_by_owner = 1"
                + " WHERE feed_id = ? AND cheerer_uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setLong(1, feedId);
                stmt.setString(2, cheererUuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to mark single cheer read (feed " + feedId + ")", e);
            }
        }
    }

    /** 该玩家所有动态上未读（{@code read_by_owner=0}）的鼓励总数（进服摘要；走 idx_cheers_feed）。 */
    public int countUnreadCheers(UUID ownerUuid) {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "social_cheers c"
                + " JOIN " + tablePrefix + "social_feed f ON f.id = c.feed_id"
                + " WHERE f.player_uuid = ? AND c.read_by_owner = 0";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, ownerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to count unread cheers for " + ownerUuid, e);
                return 0;
            }
        }
    }

    /**
     * "你已鼓励"集合（广场打开路径上**唯一**允许的同步查询，plan-social §3.2）：
     * {@code WHERE cheerer_uuid = ? AND feed_id IN (≤28 ids)} 走 UNIQUE(feed_id,
     * cheerer_uuid) 索引，量级与既有 getPlayerStats 兜底同级。有界输入（调用方传
     * 本页 id 列表），空输入直接返回空集不查库。
     */
    public Set<Long> getCheeredFeedIds(UUID viewerId, List<Long> feedIds) {
        Set<Long> result = new java.util.HashSet<>();
        if (feedIds == null || feedIds.isEmpty()) {
            return result;
        }
        StringBuilder sql = new StringBuilder("SELECT feed_id FROM " + tablePrefix
                + "social_cheers WHERE cheerer_uuid = ? AND feed_id IN (");
        for (int i = 0; i < feedIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql.toString())) {
                stmt.setString(1, viewerId.toString());
                for (int i = 0; i < feedIds.size(); i++) {
                    stmt.setLong(i + 2, feedIds.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(rs.getLong(1));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load cheered feed ids for " + viewerId, e);
            }
        }
        return result;
    }

    /**
     * 鼓励统计三路聚合行（Wave 2 冻结接口 {@code SocialManager.getCheerStats} 的数据源）：
     * {@code given}=累计发出鼓励数（cheerer_uuid 索引）；{@code distinctTargets}=发出对象
     * 去重数（JOIN social_feed 按作者去重，P3-3 互助成就判据）；{@code received}=收到
     * 鼓励总数。一条 SQL 三子查询，锁内单次执行。
     */
    public record CheerStatsRow(int given, int distinctTargets, int received) { }

    public CheerStatsRow getCheerStatsRow(UUID playerId) {
        String id = playerId.toString();
        String sql = "SELECT"
                + " (SELECT COUNT(*) FROM " + tablePrefix + "social_cheers WHERE cheerer_uuid = ?) AS given,"
                + " (SELECT COUNT(DISTINCT f.player_uuid) FROM " + tablePrefix + "social_cheers c"
                + "    JOIN " + tablePrefix + "social_feed f ON f.id = c.feed_id"
                + "    WHERE c.cheerer_uuid = ?) AS distinct_targets,"
                + " (SELECT COUNT(*) FROM " + tablePrefix + "social_cheers c"
                + "    JOIN " + tablePrefix + "social_feed f ON f.id = c.feed_id"
                + "    WHERE f.player_uuid = ?) AS received";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, id);
                stmt.setString(3, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new CheerStatsRow(rs.getInt("given"),
                                rs.getInt("distinct_targets"), rs.getInt("received"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load cheer stats for " + playerId, e);
            }
        }
        return new CheerStatsRow(0, 0, 0);
    }

    /**
     * 清理过期动态：先删过期 feed 行上的 cheers（子查询先于 feed 行删除——顺序敏感），
     * 再删 feed 行；两删同一事务。{@code retentionDays <= 0} = 永久保留（no-op）。
     * cutoff 口径与 {@link #getFeedCacheRows} 一致。
     */
    public void cleanupOldFeedEvents(int retentionDays) {
        if (retentionDays <= 0) {
            return; // 永久保留（不推荐，注释见 config.yml social.feed.retention-days）
        }
        String cutoff = feedRetentionCutoff(retentionDays);
        String deleteCheers = "DELETE FROM " + tablePrefix + "social_cheers"
                + " WHERE feed_id IN (SELECT id FROM " + tablePrefix + "social_feed WHERE feed_date < ?)";
        String deleteFeed = "DELETE FROM " + tablePrefix + "social_feed WHERE feed_date < ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try {
                conn.setAutoCommit(false);
                try {
                    int cheersDeleted;
                    try (PreparedStatement stmt = conn.prepareStatement(deleteCheers)) {
                        stmt.setString(1, cutoff);
                        cheersDeleted = stmt.executeUpdate();
                    }
                    int feedDeleted;
                    try (PreparedStatement stmt = conn.prepareStatement(deleteFeed)) {
                        stmt.setString(1, cutoff);
                        feedDeleted = stmt.executeUpdate();
                    }
                    conn.commit();
                    if (feedDeleted > 0) {
                        plugin.getLogger().info("Cleaned up " + feedDeleted
                                + " old feed events (+" + cheersDeleted + " cheers) older than "
                                + cutoff + ".");
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clean up old feed events", e);
            }
        }
    }

    // ============================================================
    //  Season settlement ledger (seasons + season_rewards) —— Wave 3 域 5 批次 A
    // ============================================================

    /**
     * 赛季结算头行（{@code seasons} 表的读取投影）。
     *
     * @param seasonId    赛季号（1-based，锚点纯函数推导）
     * @param startDate   yyyy-MM-dd 字符串（与 session_date 同构）
     * @param endDate     yyyy-MM-dd 字符串（闭区间）
     * @param settledAt   结算时间戳；null = 未结算
     * @param topSnapshot 结算时 top10 JSON（回顾用；未结算为 null）
     */
    public record SeasonRow(int seasonId, String startDate, String endDate,
                            Long settledAt, String topSnapshot) { }

    /**
     * 赛季奖励台账行（{@code season_rewards} 的读取投影）。
     *
     * <p>放在 DatabaseManager 内嵌（而非 SeasonManager）的理由：DB 方法参数/返回
     * 用本类型即可，两个类零互相依赖（SeasonManager → DatabaseManager 单向），
     * 与既有 {@link FeedRow} / {@link CheerStatsRow} 惯例一致。
     *
     * @param seasonId     赛季号
     * @param uuid         获奖玩家
     * @param rankPosition 名次；0 = 参与奖（未上榜）
     * @param score        窗口内学习分
     * @param rewardCents  奖励金额，单位"分"（对齐 RewardEngine 厘级记账）
     */
    public record SeasonRewardRow(int seasonId, UUID uuid, int rankPosition,
                                  long score, long rewardCents) { }

    /**
     * 建立赛季行（幂等，双后端对照）：SQLite {@code INSERT OR IGNORE} /
     * MySQL {@code INSERT IGNORE}（F2-01 教训，不做裸 INSERT）。已存在（含已结算）
     * 的行不被覆盖——结算抢占只动 {@code settled_at} 列。
     */
    public void ensureSeasonRow(int seasonId, LocalDate start, LocalDate end) {
        String sql = (isSQLite()
                ? "INSERT OR IGNORE INTO "
                : "INSERT IGNORE INTO ") + tablePrefix + "seasons"
                + " (season_id, start_date, end_date, settled_at, top_snapshot) VALUES (?, ?, ?, NULL, NULL)";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, seasonId);
                stmt.setString(2, start.toString());
                stmt.setString(3, end.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to ensure season row " + seasonId, e);
            }
        }
    }

    /**
     * 结算抢占（多服单发保护）：条件 UPDATE {@code WHERE season_id=? AND settled_at IS NULL}。
     * {@code executeUpdate()==1} = 本服获得结算权（唯一赢家）；0 = 他服已抢先或行不存在。
     * 无后端差异（纯 UPDATE）。
     *
     * <p><b>R12d-F7：{@code ledgerPending} 与 claim 同一条语句落库。</b>抢占与"欠一份台账"
     * 是同一瞬间的两个事实，分成两条语句会留一个「已抢占但标记还没写」的窗口，重启正好
     * 落在窗口里就退化成修复前的形状（脏 claim + 无标记 → 永不被重建）。同语句写入
     * 使「有 claim 必有标记，或标记为 0（本季不欠台账）」成为原子不变量。</p>
     *
     * @param seasonId      赛季号
     * @param settledAt     本次 claim 的 fencing token（{@code releaseSettlement} 要求原值）
     * @param topSnapshot   top10 快照 JSON
     * @param ledgerPending {@code true} = 本季欠一份奖励台账（{@code season.rewards.enabled}
     *                      为真时由调用方传 true）；{@code false} = 本季不发奖，重启后
     *                      <b>不得</b>被重建逻辑当成「写失败」重试
     */
    public boolean tryClaimSettlement(int seasonId, long settledAt, String topSnapshot,
                                      boolean ledgerPending) {
        return claimSettlement(seasonId, settledAt, topSnapshot, ledgerPending)
                == SettlementClaim.CLAIMED;
    }

    /**
     * R15（wave3-锐评 #2 的后继审计 F1）：{@link #tryClaimSettlement} 的<b>三态</b>版本。
     *
     * <p><b>为什么 boolean 不够</b>：{@code false} 同时承载三件语义完全不同的事：
     * <ol>
     *   <li>{@link #CLAIMED} 的反面 —— <b>他服已抢先</b>（正确的跳过，本服不该重试）；</li>
     *   <li><b>行不存在</b>（{@code ensureSeasonRow} 自己吞了 SQLException，见 :3134）；</li>
     *   <li><b>SQL 失败</b>（{@code SQLITE_BUSY} / MySQL 死锁 → 本次根本没抢）。</li>
     * </ol>
     * 调用方 {@code SeasonManager} 原来对三者一律 {@code continue}，于是「第 N 季 claim
     * 失败、第 N+1 季成功」时游标 {@code MAX(settled_at)} 会越过第 N 季；而第 N 季的
     * {@code ledger_pending} 仍是 0（claim 没成功，标记也没写），债务扫描
     * （要求 {@code settled_at IS NOT NULL AND ledger_pending <> 0}）看不见它 ——
     * <b>整季奖励永久丢失且无任何回访路径</b>。
     *
     * <p>同一个方法往下几行，{@code writeSeasonLedger} 失败的分支已经写了
     * 「必须 break：若 continue，后续季会照常 claim 并把 settled_at 推到本季之上」——
     * 那个道理对 claim 失败同样成立，只是当时它藏在 boolean 里表达不出来。
     */
    public SettlementClaim claimSettlement(int seasonId, long settledAt, String topSnapshot,
                                           boolean ledgerPending) {
        String sql = "UPDATE " + tablePrefix + "seasons"
                + " SET settled_at = ?, top_snapshot = ?, ledger_pending = ?, claim_owner = ?"
                + " WHERE season_id = ? AND settled_at IS NULL";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setLong(1, settledAt);
                stmt.setString(2, topSnapshot);
                stmt.setInt(3, ledgerPending ? 1 : 0);
                // R-16：持有者与 settled_at 写在同一条语句 —— 读写两侧对「谁的抢占」
                // 必须有同一个答案。留成两条语句会出现「已抢占但 owner 还是旧值」的窗口，
                // 正好落在窗口里的一次他服 release 就会把本服的 live claim 放掉。
                // R-16 后续修正：写的是**节点级** nodeId 而不是 per-process instanceId ——
                // 否则重启后的自己会被判成"他服"，自己的恢复路径反而要先等满 15 分钟
                // （见 nodeId 的 javadoc）。
                stmt.setString(4, nodeId);
                stmt.setInt(5, seasonId);
                return stmt.executeUpdate() == 1
                        ? SettlementClaim.CLAIMED
                        : SettlementClaim.TAKEN;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to claim season settlement for " + seasonId, e);
                return SettlementClaim.FAILED;
            }
        }
    }

    /**
     * R15：赛季结算抢占的三态结果。
     *
     * <ul>
     *   <li>{@link #CLAIMED} —— 本服抢到了，继续写台账；</li>
     *   <li>{@link #TAKEN} —— 他服/其他路径已抢（{@code settled_at} 非 NULL）。
     *       <b>正确跳过</b>，不动游标以外的一切，也不需要释放；</li>
     *   <li>{@link #FAILED} —— <b>未知</b>：SQL 失败导致这次根本没抢成，行可能还在、
     *       也可能不存在。调用方<b>必须 break</b>（不能 continue），否则后续季会把游标
     *       推过本季，造成整季静默丢失。</li>
     * </ul>
     */
    public enum SettlementClaim {
        CLAIMED,
        TAKEN,
        FAILED
    }

    /**
     * 批量写入赛季奖励台账（抢占者唯一，直接 INSERT 无冲突；单事务）。
     * 空列表 no-op（返回 {@code true}：没有台账要写 = 全部落库）。
     *
     * <p><b>R11：返回 boolean 成败（原为 {@code void}）。</b>原实现自己
     * {@code catch (SQLException)} 只记 WARNING —— 失败方向是「<b>玩家拿不到奖励</b>」：
     * 台账行是发放的唯一数据源（{@code getPendingSeasonRewards} 读它），行没进去，
     * 即时的 {@code deliverPending} 与后续 join 补发都查不到这个玩家，钱永远不发。
     * 更糟的是它与 {@code tryClaimSettlement} 组成"先 claim（推进游标）后写台账"，
     * 写失败被吞时游标已前进，该季永不被回访 → 永久丢失。
     *
     * <p>当前生产调用方 {@code SeasonManager.runSettleSweep} 已经<b>不再调用本方法</b>
     * （见 {@code writeSeasonLedger}：它改走 INSERT OR IGNORE + 回读计数，并自带
     * 释放抢占的重试路径）。本方法目前只作为台账写入的标准形态保留，任何新调用方都必须
     * <b>检查返回值</b>，不得假定写完即成功。
     *
     * @param rows 台账行；{@code null} 或空列表 → {@code true}（无写入即无失败）
     * @return {@code true} = 全部行已提交；{@code false} = 写入失败（SQLException 已记
     *         WARNING；事务已回滚，一行都没进库）
     */
    public boolean insertSeasonRewards(List<SeasonRewardRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return true;
        }
        String sql = "INSERT INTO " + tablePrefix + "season_rewards"
                + " (season_id, uuid, rank_position, score, reward_coins, claimed, claimed_at)"
                + " VALUES (?, ?, ?, ?, ?, 0, NULL)";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (SeasonRewardRow row : rows) {
                        stmt.setInt(1, row.seasonId());
                        stmt.setString(2, row.uuid().toString());
                        stmt.setInt(3, row.rankPosition());
                        stmt.setLong(4, row.score());
                        stmt.setLong(5, row.rewardCents());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                    conn.commit();
                    return true;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to insert season reward rows (" + rows.size() + ")", e);
                return false;
            }
        }
    }

    /**
     * 该玩家全部未领取的台账行（join 补发 / 结算即时发放的数据源），按赛季升序。
     * 走 {@code idx_season_rewards_uuid}。
     *
     * <p><b>R12-G1：发放路径请改用 {@link #loadPendingSeasonRewards}</b> —— 本方法把
     * 「读失败」折叠成空 List，调用方无法区分它与「这个玩家确实没有待发奖励」。
     * 本两态版只保留给展示用途。
     */
    public List<SeasonRewardRow> getPendingSeasonRewards(UUID playerId) {
        List<SeasonRewardRow> rows = new ArrayList<>();
        String sql = "SELECT season_id, uuid, rank_position, score, reward_coins"
                + " FROM " + tablePrefix + "season_rewards"
                + " WHERE uuid = ? AND claimed = 0"
                + " ORDER BY season_id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new SeasonRewardRow(
                                rs.getInt("season_id"),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getInt("rank_position"),
                                rs.getLong("score"),
                                rs.getLong("reward_coins")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load pending season rewards for " + playerId, e);
            }
        }
        return rows;
    }

    /**
     * R12-G1（round12 F1 §7.1，单玩家版）：{@link #getPendingSeasonRewards} 的<b>三态</b>版，
     * 供 {@code SeasonManager.deliverPending}（join 补发）使用。
     *
     * <p><b>为什么 join 路径也要三态</b>：join 是「结算时不在线的玩家」的<b>唯一</b>补发面。
     * 两态版在读失败时返回空 List，{@code deliverPending} 的 {@code rows.isEmpty()} 早退 ——
     * 于是一次 SELECT 抖动与「这个玩家确实没有待发奖励」逐字节同形。<b>不丢数据</b>
     * （台账 {@code claimed} 仍是 0，下次 join 会重试），但只要他这次没拿到，就<b>没有任何
     * 痕迹</b>说明奖励被推迟了。这与批量版是同一个缺陷形态，必须一起修，否则只修结算路径
     * 会让 join 路径继续静默。
     *
     * <p>发放逻辑本身不变（延迟发放是既有且正确的语义），这里只把「未知」暴露给调用方。
     *
     * @param playerId 玩家 UUID（null → 视为「无奖励」而非「读失败」，见下）
     * @return 三态结果；{@code readFailed=true} 时 {@code byPlayer} 的内容<b>不可信</b>
     */
    public PendingSeasonRewardsLoad loadPendingSeasonRewards(UUID playerId) {
        // null UUID 是调用方 bug，但不是「读失败」—— 没有玩家就不可能欠他奖励。
        // 返回空 + readFailed=false 而非抛：发放是 join 热路径，不能因为一个坏参数
        // 打断进服流程（对齐本类其它读方法的降级口径）。
        if (playerId == null) {
            return new PendingSeasonRewardsLoad(Map.of(), false);
        }
        List<SeasonRewardRow> rows = new ArrayList<>();
        String sql = "SELECT season_id, uuid, rank_position, score, reward_coins"
                + " FROM " + tablePrefix + "season_rewards"
                + " WHERE uuid = ? AND claimed = 0"
                + " ORDER BY season_id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new SeasonRewardRow(
                                rs.getInt("season_id"),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getInt("rank_position"),
                                rs.getLong("score"),
                                rs.getLong("reward_coins")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load pending season rewards for " + playerId, e);
                return new PendingSeasonRewardsLoad(Map.of(), true);
            }
        }
        return new PendingSeasonRewardsLoad(
                rows.isEmpty() ? Map.of() : Map.of(playerId, rows), false);
    }

    /**
     * R12-G1（round12 F1 §7.1）：{@link #getPendingSeasonRewardsFor} 的<b>三态</b>版。
     *
     * <p><b>为什么需要它</b>：两态版在读失败时返回空 map，而调用方
     * {@code SeasonManager.deliverPendingBatch} 用 {@code byPlayer.isEmpty()} 判「没人有奖励」
     * → 早退。于是「读失败」与「确实没人欠奖励」同形：<b>不丢数据</b>（台账 {@code claimed}
     * 仍是 0，下次 join 会重试），但若玩家再也不上线，这笔钱就一直挂着<b>且没人知道</b>。
     *
     * <p>本版把「读失败」显式暴露出来，让调用方至少能记一条 SEVERE。发放逻辑本身不变 ——
     * 延迟发放是既有且正确的语义，这里只补可观测性。
     *
     * @param playerIds 待查玩家（null/空 → {@code readFailed=false} 的空结果：没有请求就没有失败）
     * @return 三态结果；{@code readFailed=true} 时 {@code byPlayer} 的内容<b>不可信</b>
     */
    public PendingSeasonRewardsLoad loadPendingSeasonRewardsFor(Collection<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return new PendingSeasonRewardsLoad(Map.of(), false);
        }
        String sql = "SELECT season_id, uuid, rank_position, score, reward_coins"
                + " FROM " + tablePrefix + "season_rewards"
                + " WHERE claimed = 0 AND uuid IN (%s) ORDER BY season_id ASC";
        Map<UUID, List<SeasonRewardRow>> byPlayer = new LinkedHashMap<>();
        List<UUID> idList = new ArrayList<>(playerIds);
        final int batchSize = 500; // SQLite 999 变量上限防御（与两态版同口径）
        synchronized (connectionLock) {
            for (int start = 0; start < idList.size(); start += batchSize) {
                List<UUID> batch = idList.subList(start, Math.min(start + batchSize, idList.size()));
                StringBuilder inClause = new StringBuilder();
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) {
                        inClause.append(", ");
                    }
                    inClause.append("?");
                }
                try (PreparedStatement stmt = getConnection()
                        .prepareStatement(String.format(sql, inClause))) {
                    for (int i = 0; i < batch.size(); i++) {
                        stmt.setString(i + 1, batch.get(i).toString());
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            UUID owner = UUID.fromString(rs.getString("uuid"));
                            byPlayer.computeIfAbsent(owner, k -> new ArrayList<>())
                                    .add(new SeasonRewardRow(
                                            rs.getInt("season_id"), owner,
                                            rs.getInt("rank_position"),
                                            rs.getLong("score"),
                                            rs.getLong("reward_coins")));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to load pending season rewards (batch " + start + " of "
                                    + idList.size() + ")", e);
                    // 读到一半失败也没法拼出完整结果 —— 整批判失败，让调用方别信这份 map。
                    return new PendingSeasonRewardsLoad(byPlayer, true);
                }
            }
        }
        return new PendingSeasonRewardsLoad(byPlayer, false);
    }

    /** R12-G1：{@link #loadPendingSeasonRewardsFor} 的三态载荷（照 {@code DifficultWordsLoad} 范式）。 */
    public record PendingSeasonRewardsLoad(Map<UUID, List<SeasonRewardRow>> byPlayer,
                                           boolean readFailed) { }

    /**
     * Block C2（A7/R7-M1 配对，供 C2-C 结算批量发放消费）：多名玩家全部未领取的
     * 台账行，按 player_uuid 分组（仅含至少一行 pending 的玩家）。
     *
     * <p>IN 占位符展开照 {@code PlayerDataStore.getRankedPlayersForPeriodFiltered}
     * 先例；{@code claimed} 条件与 {@link #getPendingSeasonRewards} 同式。
     * SQLite 变量上限 999 —— 集合 &gt; 500 时分批查询合并（每批独立 SELECT，
     * 结果并集语义与单查等价）。空集合 → 空 map。
     *
     * <p>异常 → 空 map + WARNING（静默降级 = 发放推迟到 join 路径，与
     * deliverPending 现有"秒退留待下次"语义同族）。
     *
     * <p><b>R12-G1：发放路径请改用 {@link #loadPendingSeasonRewardsFor}</b> —— 本方法把
     * 「读失败」折叠成空 map，调用方无法区分它与「确实没人欠奖励」。本两态版只保留给
     * 展示用途。
     *
     * @param playerIds 待查玩家（在线 UUID 快照；null/空 → 空 map）
     * @return player_uuid → 该玩家 pending 行列表（按赛季升序，与单查排序一致）
     */
    public Map<UUID, List<SeasonRewardRow>> getPendingSeasonRewardsFor(Collection<UUID> playerIds) {
        Map<UUID, List<SeasonRewardRow>> byPlayer = new LinkedHashMap<>();
        if (playerIds == null || playerIds.isEmpty()) {
            return byPlayer;
        }
        // SQLite 999 变量上限防御：>500 分批（每批一次 SELECT，并集合并）
        List<UUID> idList = new ArrayList<>(playerIds);
        final int batchSize = 500;
        for (int start = 0; start < idList.size(); start += batchSize) {
            List<UUID> batch = idList.subList(start, Math.min(start + batchSize, idList.size()));
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    inClause.append(", ");
                }
                inClause.append("?");
            }
            String sql = "SELECT season_id, uuid, rank_position, score, reward_coins"
                    + " FROM " + tablePrefix + "season_rewards"
                    + " WHERE uuid IN (" + inClause + ") AND claimed = 0"
                    + " ORDER BY season_id ASC";
            synchronized (connectionLock) {
                try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                    int idx = 1;
                    for (UUID id : batch) {
                        stmt.setString(idx++, id.toString());
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            byPlayer.computeIfAbsent(uuid, k -> new ArrayList<>())
                                    .add(new SeasonRewardRow(
                                            rs.getInt("season_id"),
                                            uuid,
                                            rs.getInt("rank_position"),
                                            rs.getLong("score"),
                                            rs.getLong("reward_coins")
                                    ));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to batch load pending season rewards (" + batch.size()
                                    + " players in batch)", e);
                    return new LinkedHashMap<>(); // 静默降级：推迟到 join 路径
                }
            }
        }
        return byPlayer;
    }

    /**
     * 条件标记已领取：{@code WHERE season_id=? AND uuid=? AND claimed=0}。
     * {@code executeUpdate()==1} = 本路径抢到发放权（调用方随后才发钱——对齐
     * CheckInManager "先标记后付款"先例，R-02 双击重发教训）。
     */
    public boolean markSeasonRewardClaimed(int seasonId, UUID playerId) {
        return setSeasonRewardClaimed(seasonId, playerId, true);
    }

    /**
     * W3A tester M1 收口：付款失败回滚——把 {@code claimed} 从 1 改回 0（仅当
     * 当前确为 1），使该行回到 pending、下次 join/结算路径可重试。与
     * {@link #markSeasonRewardClaimed} 同为条件 UPDATE（竞态安全）。
     */
    public boolean unmarkSeasonRewardClaimed(int seasonId, UUID playerId) {
        return setSeasonRewardClaimed(seasonId, playerId, false);
    }

    private boolean setSeasonRewardClaimed(int seasonId, UUID playerId, boolean claimed) {
        String sql = "UPDATE " + tablePrefix + "season_rewards"
                + " SET claimed = ?, claimed_at = ?"
                + " WHERE season_id = ? AND uuid = ? AND claimed = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                int target = claimed ? 1 : 0;
                int expected = claimed ? 0 : 1;
                stmt.setInt(1, target);
                stmt.setLong(2, claimed ? System.currentTimeMillis() : 0L);
                stmt.setInt(3, seasonId);
                stmt.setString(4, playerId.toString());
                stmt.setInt(5, expected);
                return stmt.executeUpdate() == 1;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to update season reward claimed=" + claimed
                                + " (" + seasonId + "/" + playerId + ")", e);
                return false;
            }
        }
    }

    /** 读已结算的赛季行（top_snapshot 回顾；不存在/未结算返回 null）。 */
    public SeasonRow getSettledSeason(int seasonId) {
        String sql = "SELECT season_id, start_date, end_date, settled_at, top_snapshot"
                + " FROM " + tablePrefix + "seasons"
                + " WHERE season_id = ? AND settled_at IS NOT NULL";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, seasonId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long settled = rs.getLong("settled_at");
                        Long settledAt = rs.wasNull() ? null : settled;
                        return new SeasonRow(
                                rs.getInt("season_id"),
                                rs.getString("start_date"),
                                rs.getString("end_date"),
                                settledAt,
                                rs.getString("top_snapshot")
                        );
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load settled season " + seasonId, e);
            }
        }
        return null;
    }

    /**
     * seasons 表最小 season_id 的<b>三态</b>结果（A5/D6）。
     *
     * <p><b>为什么需要它</b>：{@code SeasonManager.runSettleSweep} 原先把"最小 season_id 读回 {@code null}"
     * 解释为「首次启用，表空」—— 于是只 {@code ensureSeasonRow} 并 return，还打一条
     * "Season ledger initialized at season X (first enable)" 的 INFO。而旧实现
     * {@code catch (SQLException) → return null} 与"表真的空"逐字节同形：一次读抖动
     * 就让这一轮结算被跳过，并且<b>日志给出与事实相反的信息</b>（运维会据此认为历史季
     * 按设计不追发）。不丢数据（游标未前进，下一轮读成功即补上），但事实被谎报。</p>
     *
     * <p>三态取值与 {@link WordChallengeCompletion} 同构：区分「表空」「有值」「不知道」。</p>
     */
    public enum MinSeasonIdResult {
        /** 有值：记录里的 {@code id} 为最小 season_id（调用方据此定 catch-up 起点）。 */
        PRESENT,
        /** SQL 成功执行且表为空：确实是「首次启用」。 */
        EMPTY,
        /** 查询/连接失败（已记 WARNING）：结果<b>未知</b>，既不是「首次启用」也不是「有值」。 */
        READ_FAILED
    }

    /**
     * {@link #MinSeasonIdResult} 的带值版本（{@code PRESENT} 时携带 {@code id}）。
     *
     * <p>用 record 而不是"枚举 + 单独取值方法"：调用方必须同时拿到"哪一态"和"值是多少"，
     * 两个独立调用之间没有一致性问题（本方法只读一次库）。</p>
     */
    public record SeasonIdRange(MinSeasonIdResult state, Integer id) {
        static final SeasonIdRange EMPTY = new SeasonIdRange(MinSeasonIdResult.EMPTY, null);
        static final SeasonIdRange FAILED = new SeasonIdRange(MinSeasonIdResult.READ_FAILED, null);

        static SeasonIdRange present(int id) {
            return new SeasonIdRange(MinSeasonIdResult.PRESENT, id);
        }
    }

    /**
     * 三态版最小 season_id：区分「表空（首次启用）」「有值」「读失败」。
     *
     * <p>行只会由 {@code SeasonManager.settleIfNeededAsync} 建立，最小值即"启用时刻的季"。</p>
     */
    public SeasonIdRange loadMinSeasonId() {
        String sql = "SELECT MIN(season_id) FROM " + tablePrefix + "seasons";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int min = rs.getInt(1);
                    // wasNull() == true ⇒ 表空（MIN 对空表返回 NULL）⇒ 真正的首次启用。
                    return rs.wasNull() ? SeasonIdRange.EMPTY : SeasonIdRange.present(min);
                }
                // 聚合查询必然返回一行；不可达时按"未知"处理，不猜"表空"。
                return SeasonIdRange.FAILED;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query min season id", e);
                return SeasonIdRange.FAILED;
            }
        }
    }

    /*
     * R12-F8：这里曾有 {@code Integer minSeasonId()}（{@code null} 同时承载
     * 「表空」与「读失败」的两态便利版）。已删除，且<b>不得</b>重新引入 ——
     * 「首次启用」与「不知道」在返回值上同形，{@code SeasonManager.runSettleSweep}
     * 一旦读回 null 就会打出与事实相反的 "first enable" INFO 并跳过本轮结算。
     * 唯一正确用法是三态版 {@link #loadMinSeasonId()}。
     * 理由与墓碑 lint 见 {@code TwoStateLegacyLintTest}。
     */

    /**
     * R12d-F7：一条「欠着台账」的赛季结算记录（重启后重建待重试表的输入）。
     *
     * <p>为什么不只是 {@code List<Integer>}：{@code releaseSettlement} 的 fencing 条件是
     * {@code WHERE season_id = ? AND settled_at = ? AND claim_owner = ?}（R-16 起两列都要
     * 匹配），重建必须带回<b>库里当次 claim 的 settled_at 原值</b>与<b>持有者原值</b>；
     * 换任何其它值都永远匹配 0 行（判成 {@code NOT_HELD}）→ 脏 claim 永远清不掉，
     * 缺陷原样保留。{@code ledgerRows} 只用于日志
     * （区分「一行都没写进去」与「写了一部分」）。</p>
     *
     * @param seasonId   赛季号
     * @param settledAt  库里 {@code settled_at} 的实际值（fencing token 原值，非 NULL）
     * @param ledgerRows 该季当前台账行数（0 = 一行都没写进去）
     * @param claimOwner R-16：库里 {@code claim_owner} 的实际值 —— <b>谁</b>持有这次抢占。
     *                   {@code null} = 本列上线前写入的旧行（持有者未知）；
     *                   等于本节点 {@link #getNodeId()} = 本服（含重启前/热重载前的
     *                   <b>自己</b>，这正是它必须是节点级而非进程级的原因）；
     *                   其它值 = 他服的 live claim（或一个已经死掉的进程留下的陈旧抢占，
     *                   只能按 {@code settledAt} 的年龄区分，见
     *                   {@code SeasonManager.rebuildPendingReleases}）。重建逻辑<b>必须</b>
     *                   看这个字段再决定能不能释放：settled_at 是从共享库读的，只按它
     *                   fencing 会释放掉别人正在结算的 claim。
     */
    public record SeasonLedgerDebt(int seasonId, long settledAt, int ledgerRows,
                                   String claimOwner) { }

    /**
     * R12d-F7：欠台账扫描的<b>三态</b>结果（照 {@link SessionParticipantsLoad} 范式）。
     *
     * <p>{@code readFailed=true} 时 {@code debts} 不可信 —— 调用方必须中止本轮结算，
     * <b>不能</b>当成「没有欠账」：脏 claim 照常把游标推过该季，正是本方法要修的缺陷。</p>
     */
    public record SeasonLedgerDebtScan(List<SeasonLedgerDebt> debts, boolean readFailed) { }

    /**
     * R12d-F7：找出<b>已抢占但台账没确认落库</b>的赛季 —— 重启后重建
     * {@code SeasonManager.pendingReleaseRetries} 的唯一数据源。
     *
     * <p><b>判据为什么是 {@code ledger_pending} 而不是「台账缺行」</b>：
     * 「{@code settled_at} 非 NULL 且台账无行」这个状态由三种完全不同的历史产生 ——
     * ① 台账写失败（奖励真的欠着）；② 结算时 {@code season.rewards.enabled=false}；
     * ③ 各档位金额配置成 0。后两者<b>本来就不该有台账</b>。若按「缺行」重建，它们会被
     * 反复释放并重新结算，一旦之后奖励配置被打开，就会给一批早已结算过的赛季
     * <b>补发真钱</b>；反之若改用「当前配置有奖励吗」过滤，则「结算时开着、重启前关掉」
     * 的季会被判成「不欠账」→ 奖励永久丢失。库里的行在这三种历史下逐字节同形，
     * <b>事后无法区分</b>，所以必须在抢占那一刻把意图写下来。
     * {@code ledger_pending} 就是这个意图：1 = 本次 claim 欠一份台账且尚未确认完成。</p>
     *
     * <p>维持它的三条语句（缺一条就会出现假阳或假阴）：
     * <ul>
     *   <li>{@link #tryClaimSettlement} 把标记与 claim 写在<b>同一条</b> UPDATE 里
     *       （原子，不留「已抢占但标记未写」的重启窗口）；</li>
     *   <li>{@code SeasonManager} 回读确认台账完整后调
     *       {@link #clearSeasonLedgerPendingDetailed}（<b>必须</b>用三态版：只有
     *       {@link LedgerClearResult#NOT_HELD} 才意味着"归属已不是我们的"，
     *       {@link LedgerClearResult#FAILED} 仍归本服）；</li>
     *   <li>{@code SeasonManager.releaseSettlement} 释放时一并清零
     *       （回到未抢占态，由下一个抢占者重新决定）。</li>
     * </ul>
     *
     * <p>判据<b>不</b>要求台账真的缺行：部分写入（3 行只进去 2 行）也必须被重建，否则
     * 少掉的那行永远补不上。反过来「台账已完整但清零失败」的重启结果是多结算一次 ——
     * {@code INSERT OR IGNORE} 与 {@code claimed=0} 条件标记使重复结算不重复发钱。</p>
     *
     * <p>{@code ledgerRows} 用相关子查询而非 {@code LEFT JOIN}：一季多行会放大结果集，
     * 补 {@code DISTINCT} 又会把计数意义破坏掉。子查询走 {@code season_rewards} 的
     * PK 前缀 {@code (season_id, uuid)}。</p>
     *
     * @param minId 赛季号下界（含）
     * @param maxId 赛季号上界（含）
     * @return 三态结果；{@code readFailed=true} 时 {@code debts} 为空但<b>不可</b>当成"无欠账"
     */
    public SeasonLedgerDebtScan loadSeasonsWithOwedLedger(int minId, int maxId) {
        List<SeasonLedgerDebt> debts = new ArrayList<>();
        String sql = "SELECT s.season_id, s.settled_at, s.claim_owner,"
                + " (SELECT COUNT(*) FROM " + tablePrefix + "season_rewards r"
                + "   WHERE r.season_id = s.season_id) AS ledger_rows"
                + " FROM " + tablePrefix + "seasons s"
                + " WHERE s.season_id >= ? AND s.season_id <= ?"
                + "   AND s.settled_at IS NOT NULL"
                + "   AND COALESCE(s.ledger_pending, 0) <> 0"
                + " ORDER BY s.season_id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, minId);
                stmt.setInt(2, maxId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        debts.add(new SeasonLedgerDebt(
                                rs.getInt("season_id"),
                                rs.getLong("settled_at"),
                                rs.getInt("ledger_rows"),
                                // R-16：NULL = 升级前的旧行（持有者未知）。
                                // getString 对 SQL NULL 返回 null，正是我们要的三态之一。
                                rs.getString("claim_owner")));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to scan seasons with an owed reward ledger — the settlement"
                                + " sweep must NOT treat this as 'no debt'", e);
                return new SeasonLedgerDebtScan(List.of(), true);
            }
        }
        return new SeasonLedgerDebtScan(debts, false);
    }

    /**
     * R12d-F7：台账已确认完整 → 清掉「欠台账」标记。
     *
     * <p>失败只记 WARNING、不回滚结算：标记留着的最坏后果是下次启动多结算一次这一季，
     * 而 {@code INSERT OR IGNORE} 与 {@code claimed=0} 条件标记保证不会重复发钱。</p>
     *
     * <p><b>R-16（红队实测 / 事后追补，不是原始设计）：原来的 {@code WHERE} 只有
     * {@code season_id = ?} —— 没有 fencing，于是这条语句能擦掉<b>别人的</b>标记。</b>
     * 这正是"修一个洞时新开一个洞"的形态：{@code ledger_pending} 本来就是为了给丢奖留
     * 恢复信号，而这条无条件的 {@code UPDATE} 正好能把这个信号抹掉。失败序列
     * （红队用真 SQLite 探针复现，非推演）：
     * <ol>
     *   <li>服 A 抢占第 N 季并开始 {@code writeSeasonLedger}（在 {@code SeasonManager}，
     *       不在本类 —— 原 {@code {@link #writeSeasonLedger}} 是坏链，R-16 顺手改成
     *       {@code @code}）。
     *       这次写<b>可以无限期阻塞</b> —— 当时 {@code initializeMySQL} 没有设
     *       {@code socketTimeout} / {@code connectTimeout}（<b>R-16 已补上</b>，见
     *       {@code initializeMySQL} 的注释：现在卡住的写最多 30 秒就会抛
     *       SQLException），MySQL 的锁等待就能把它挂住；</li>
     *   <li>15 分钟过去（陈旧判据只按墙钟年龄，两边没有心跳通道）。服 B 的
     *       {@code rebuildPendingReleases} 判 A 的 claim 已陈旧 →
     *       {@link #reassignSeasonClaimOwner} 接管 → 释放 → 以 B 的身份重新抢占 →
     *       写自己的台账 → 结算；</li>
     *   <li>A 那条被挂住的写<b>终于返回</b>，接着调本方法 —— 按 {@code season_id} 匹配，
     *       于是清掉的是 <b>B 的</b>{@code ledger_pending}；</li>
     *   <li>结果：B 的欠账标记消失，本来会触发恢复的<b>唯一</b>信号被销毁。再叠加台账
     *       {@code INSERT OR IGNORE} 的"先写者定金额"，共享 uuid 的行是 A 的、B 的金额
     *       被静默丢弃 —— 而且<b>没有任何日志能事后指出这件事发生过</b>。</li>
     * </ol>
     *
     * <p><b>修法</b>：与 {@code SeasonManager.releaseSettlement}（:807-809）用<b>同一套</b>
     * fencing —— {@code season_id = ? AND settled_at = ? AND claim_owner = ?} 三列全匹配。
     * 两个 token 缺一不可，理由是它们挡的是两种不同的抢跑：{@code settled_at} 挡"他服
     * 释放后又重新抢占"（时间已经换人），{@code claim_owner} 挡"settled_at 逐字节相同
     * 但持有者已换"（{@code reassignSeasonClaimOwner} 恰恰只改 owner、<b>原值保留</b>
     * {@code settled_at}，所以单靠它区分不出接管前后的两个持有者）。</p>
     *
     * <p><b>为什么不复用 {@code releaseSettlement} 那条语句</b>：它会顺带把
     * {@code settled_at} 置 NULL、{@code claim_owner} 置 NULL，也就是<b>释放</b>抢占。
     * 而本方法的语义是"结算成功、只清标记、抢占保持"—— 混用会让正常结算的季在成功后
     * 立刻退回未抢占态，下一轮 sweep 重新抢一遍再结算一次。</p>
     *
     * <p><b>R-16 后续修正（三态）：上面这次 fencing 修复自己开的一个新洞。</b>
     * 加 fencing 时把方法从 {@code void} 改成了 {@code boolean}（为了能判"0 行匹配 = 别人
     * 接管了"），但这个 {@code false} 同时承载了两件<b>处置完全相反</b>的事：
     * <ol>
     *   <li>{@code executeUpdate() == 0} → 他服接管（合法的多服结局）；</li>
     *   <li>{@code catch (SQLException)} → 语句压根没执行（瞬时抖动）。</li>
     * </ol>
     * 调用方 {@code SeasonManager.runSettleSweep} 只按 ① 解释 {@code false} → {@code continue}
     * 且不置 {@code lastSettledId} → <b>一次 SQL 抖动就让本服把"自己刚结算完的季"当成
     * "别人抢走了"，整季静默不上报（无广播、无本服发放）</b>，而库里该季确实已结算。
     * 真 SQLite 探针实测复现：{@code INFO:Season settled: 1} 紧跟
     * {@code SEVERE:Season 1 settlement LOST its claim ... matched 0 rows} —— 后一句里的
     * "matched 0 rows" 并不成立，它其实是 UPDATE 抛了。原 javadoc 甚至自己写着
     * "若单服仍反复出现，就说明 marker UPDATE 本身在失败"，即作者已知两态存在，
     * 只是 boolean 表达不出来。改为 {@link LedgerClearResult} 三态。</p>
     *
     * @param seasonId   赛季号
     * @param claimedAt  本次 claim 写入的 {@code settled_at} 值（调用方自己 claim 时用的那个，
     *                   不是从库里回读的 —— 回读值可能是他服的，那就等于没有 fencing）
     * @param claimOwner 本次 claim 的持有者；调用方必须传 {@link #getNodeId()}
     *                   （与 {@link #claimSettlement} 写入的是同一个值，否则永远匹配 0 行）
     * @return 见 {@link LedgerClearResult}
     */
    public LedgerClearResult clearSeasonLedgerPendingDetailed(int seasonId, long claimedAt,
                                                             String claimOwner) {
        String sql = "UPDATE " + tablePrefix + "seasons SET ledger_pending = 0"
                + " WHERE season_id = ? AND settled_at = ? AND claim_owner = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, seasonId);
                stmt.setLong(2, claimedAt);
                stmt.setString(3, claimOwner);
                // 0 行 = 三列 fencing 没全对上 = 本服不再是持有者。这是**正确的不命中**
                // （R-16 修的就是"不命中也要命中"），与下面的 FAILED 截然不同。
                return stmt.executeUpdate() == 1
                        ? LedgerClearResult.CLEARED
                        : LedgerClearResult.NOT_HELD;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear the owed-ledger marker for"
                        + " season " + seasonId + " (settled_at=" + claimedAt + ", claim_owner="
                        + claimOwner + ") — the season may be settled once more on the"
                        + " next restart (no double payout: the ledger insert is idempotent and"
                        + " payouts go through a claimed=0 conditional update)", e);
                return LedgerClearResult.FAILED;
            }
        }
    }

    /**
     * R-16 后续修正：{@link #clearSeasonLedgerPendingDetailed} 的三态结果。
     *
     * <p><b>为什么本类需要第二个三态枚举而不复用 {@link SettlementClaim}</b>：两者都是
     * "条件 UPDATE + SQL 异常"的形状，但<b>同一个 {@code false} 在这个方向上承载的两种
     * 语义、调用方处置恰好相反</b>（见下），而 {@code SettlementClaim} 的取值名讲的是
     * "抢还是没抢"，用在这里会读反。结构与注释体例照抄 {@link SettlementClaim}
     * （它又是 R15 对 {@code tryClaimSettlement} 的同一套改造），不另立风格。
     *
     * <p>形态与 {@code SeasonManager.ReleaseOutcome} 同构，取值名刻意沿用
     * 它的 {@code NOT_HELD}：同一个"条件 UPDATE 命中 0 行"的概念在两个方向上必须是同一个词，
     * 否则运维对着两条日志会以为是两件事。
     *
     * <ul>
     *   <li>{@link #CLEARED} —— 命中 1 行，标记确实由本次调用清零。本服照常上报结算；</li>
     *   <li>{@link #NOT_HELD} —— <b>0 行匹配</b>：本服已经不再持有该季的 claim
     *       （他服接管了 / 已被释放 / 行被重建）。本服<b>不能</b>宣称本季由自己结算 ——
     *       归属别人的结算不该由本服代播（重复广播）。这是<b>正常</b>的多服结局；</li>
     *   <li>{@link #FAILED} —— <b>UPDATE 抛了 SQLException</b>（{@code SQLITE_BUSY} /
     *       MySQL 锁等待超时 / 触发器 ABORT / 磁盘错误）。此时<b>归属没有被否认</b>：
     *       语句压根没跑到库上，三列 fencing 没被比较过，本服仍然持有 claim，且台账行
     *       已经落库（{@code writeSeasonLedger} 回读过计数）。<b>不能</b>把它读成
     *       "他服抢走了" —— 那会让一次瞬时抖动变成"整季静默不上报"。</li>
     * </ul>
     */
    public enum LedgerClearResult {
        /** 标记已清零（命中 1 行）：本次结算的簿记收尾完成。 */
        CLEARED,
        /** 0 行匹配：本服不再持有该季的 claim（他服接管 / 已释放 / 行被重建）。正常的跳过。 */
        NOT_HELD,
        /**
         * SQL 异常：<b>归属未变</b>，只是标记没清掉。本服仍是持有者、台账已落库，
         * 因此结算本身<b>成功</b>；{@code ledger_pending} 留在 1 只是给
         * {@code rebuildPendingReleases} 留了一个"以后再幂等重结算一次"的入口。
         */
        FAILED
    }

    /**
     * boolean 形态的簿记收尾（{@link #clearSeasonLedgerPendingDetailed} 的退化视图）。
     *
     * <p>语义<b>恰好</b>等于 {@code == LedgerClearResult.CLEARED}，与改造前逐字节一致
     * （{@code false} 同时覆盖 {@code NOT_HELD} 与 {@code FAILED}）—— 保留它是为了
     * 既有调用方与测试不必改写，<b>不是</b>因为它是个好接口：凡是要按结局分支的调用方
     * 都必须改用 Detailed 版，否则就把本条缺陷又搬回去了。
     *
     * <p>当前生产调用方只有 {@code SeasonManager.runSettleSweep}，它已改走三态版
     * （{@code NOT_HELD} 与 {@code FAILED} 的处置相反 —— 这正是保留两种形态的原因：
     * 退化视图**表达不出**这个区别）。测试里 2 处调用点
     * （{@code SeasonReleaseRestartTest:255}、{@code :369}）只用它做"标记确实清掉了"
     * 的前置断言，那些场景本来就是 {@code CLEARED}，故断言语义不变。
     */
    public boolean clearSeasonLedgerPending(int seasonId, long claimedAt, String claimOwner) {
        return clearSeasonLedgerPendingDetailed(seasonId, claimedAt, claimOwner)
                == LedgerClearResult.CLEARED;
    }

    /**
     * R-16：接管一条<b>已陈旧</b>的他人抢占 —— 把 {@code claim_owner} 改成自己，
     * {@code settled_at} <b>原值保留</b>（绝不动别人的 fencing token）。
     *
     * <p><b>为什么需要它</b>：{@code SeasonManager.rebuildPendingReleases} 遇到
     * {@code claim_owner} 不是本服的欠账行时，既要避免释放别人<b>正在</b>结算的 live claim
     * （那正是 R-16 修的缺陷），又不能永远不碰死服留下的抢占（那样游标被它钉死，
     * 后续季永不结算）。判据是 {@code settled_at} 的年龄。但"判年龄"和"写 owner"之间
     * 有 TOCTOU 窗口：那个"死掉"的服的 claim 不能凭空复活 —— 所以 WHERE 里带上
     * <b>我们刚刚观察到的那个持有者</b>（{@code claim_owner = ?} 或
     * {@code claim_owner IS NULL}），只有它没变过才命中。</p>
     *
     * <p>为什么不顺手改 {@code ledger_pending}：欠账是<b>事实</b>（这一季确实还没确认落库），
     * 不由持有者决定，改动它会让重建逻辑看不见这一季。</p>
     *
     * @param seasonId   赛季号
     * @param settledAt  库里 {@code settled_at} 的原值（fencing；不匹配 = 行已被改动）
     * @param claimOwner 我们观察到的持有者；{@code null} = 本列上线前的旧行（无持有者）
     * @return {@code true} = 这条语句命中了 1 行（状态确实按我们的观察接管了）
     */
    public boolean reassignSeasonClaimOwner(int seasonId, long settledAt, String claimOwner) {
        String sql = "UPDATE " + tablePrefix + "seasons SET claim_owner = ?"
                + " WHERE season_id = ? AND settled_at = ?"
                + (claimOwner == null ? " AND claim_owner IS NULL" : " AND claim_owner = ?");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                // R-16 后续修正：接管的目标是**本节点**（跨重启稳定），不是本进程。
                // 若这里写 per-process id，重建出来的待重试表在重启后交给 releaseSettlement
                // 时会带着一个库里没有的值去匹配 → 0 行 → NOT_HELD → 脏 claim 留在库里。
                stmt.setString(1, nodeId);
                stmt.setInt(2, seasonId);
                stmt.setLong(3, settledAt);
                if (claimOwner != null) {
                    stmt.setString(4, claimOwner);
                }
                return stmt.executeUpdate() == 1;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to take over the stale"
                        + " settlement claim of season " + seasonId + " (observed owner="
                        + (claimOwner == null ? "NULL" : claimOwner) + ", settled_at=" + settledAt
                        + ") — the season stays blocked on that claim until the next sweep", e);
                return false;
            }
        }
    }

    /**
     * R12d-F7：<b>仅用于老库升级的一次性对账</b> —— 升级前就存在的「已抢占、无台账」赛季。
     *
     * <p>升级那一刻 {@code ledger_pending} 被补成默认 0，于是修复前就已经丢了奖的赛季
     * <b>不可能</b>被 {@link #loadSeasonsWithOwedLedger} 识别（它们没有标记）。而库里的行
     * 无法区分那三种历史（见该方法的 javadoc），所以这里<b>不自动重试</b>：只把可疑季列出来，
     * 由运维人工核对后执行点名 SQL。宁可不自动补钱，也不误补钱。</p>
     *
     * @return 可疑赛季号（升序）；读失败返回 {@code null}（与"无可疑季"区分）
     */
    public List<Integer> findLegacySeasonsWithClaimAndNoLedger() {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT s.season_id FROM " + tablePrefix + "seasons s"
                + " WHERE s.settled_at IS NOT NULL"
                + "   AND COALESCE(s.ledger_pending, 0) = 0"
                + "   AND NOT EXISTS (SELECT 1 FROM " + tablePrefix + "season_rewards r"
                + "                   WHERE r.season_id = s.season_id)"
                + " ORDER BY s.season_id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to scan pre-upgrade seasons for a missing reward ledger", e);
                return null;
            }
        }
        return ids;
    }

    /**
     * R12d-F7：一次性消费「本次启动刚给 {@code seasons} 补上 {@code ledger_pending} 列」
     * 这个事实。
     *
     * <p>返回 {@code true} 只会在<b>第一个</b>跑到含该列版本的启动里出现一次，调用方据此
     * 决定是否执行遗留欠账对账并告警。用消费语义（而非纯读）是为了让告警只出现一次 ——
     * 重复告警会让运维把这条 SEVERE 当噪音屏蔽掉。</p>
     */
    public boolean consumeSeasonLedgerPendingColumnAdded() {
        return seasonLedgerPendingJustAdded.compareAndSet(true, false);
    }

    /** 最大已结算 season_id（catch-up 游标；null = 尚无已结算季）。 */
    public Long maxSettledSeasonId() {
        String sql = "SELECT MAX(season_id) FROM " + tablePrefix + "seasons"
                + " WHERE settled_at IS NOT NULL";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long max = rs.getLong(1);
                    return rs.wasNull() ? null : max;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query max settled season id", e);
            }
        }
        return null;
    }

    /**
     * 窗口内有会话记录的玩家 uuid（参与奖判据，plan-season §1.4）。
     * 读索引 {@code idx_sessions_date} 的日期范围扫；含隐身者（奖励是发放语义，
     * 不受展示开关约束——与榜单展示过滤口径不同）。
     *
     * <p><b>F1（R12-B）：{@code readFailed} 三态。</b>本方法的唯一消费者是赛季结算的
     * 参与奖台账。原实现 {@code catch (SQLException)} 返回空 Set，与「整季无人参与」
     * 完全同形，结算方据此写出缺参与奖的台账并把它当成功 —— 该季奖励永久丢失。
     *
     * @param ids        参与者 uuid（{@code readFailed=false} 时可信；失败时可能只含
     *                   失败前已读到的部分行，<b>不得使用</b>）
     * @param readFailed {@code true} = 查询/连接失败，<b>结果不可作为「无人参与」使用</b>
     */
    public record SessionParticipantsLoad(Set<UUID> ids, boolean readFailed) {}

    public Set<UUID> getSessionParticipants(LocalDate since, LocalDate until) {
        return loadSessionParticipants(since, until).ids();
    }

    /** F1（R12-B）：{@link #getSessionParticipants(LocalDate, LocalDate)} 的三态版。 */
    public SessionParticipantsLoad loadSessionParticipants(LocalDate since, LocalDate until) {
        Set<UUID> ids = new java.util.HashSet<>();
        String sql = "SELECT DISTINCT uuid FROM " + tablePrefix + "player_sessions"
                + " WHERE session_date >= ? AND session_date <= ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, since.toString());
                stmt.setString(2, until.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            ids.add(UUID.fromString(rs.getString("uuid")));
                        } catch (IllegalArgumentException e) {
                            // 脏行跳过，不拖垮整次结算
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load season participants for window", e);
                return new SessionParticipantsLoad(ids, true);
            }
        }
        return new SessionParticipantsLoad(ids, false);
    }

    /**
     * Delete every row that belongs to one player across all player-scoped tables.
     *
     * <p>R-03：{@code /lea reset} 的删表实现（原实现只删 4 表，榜单/签到/挑战/错题/
     * AI 历史全部残留）。当前共触及 <b>23 张不同物理表</b> —— 22 张<b>删除</b>本玩家行、
     * 1 张（{@code classes}）只<b>置空</b>创建者，清单的唯一真相源是方法体内的
     * 两个数组（uuid 系 {@code uuidTables} 10 表、player_uuid 系 {@code playerUuidTables}
     * 9 表）加上 5 条显式语句涉及的 4 张表（{@code social_cheers} 两条
     * ——一条按 {@code cheerer_uuid}、一条按子查询、{@code social_feed}、{@code duel_stats}
     * 是 DELETE，{@code classes} 是 Round 17 起的那条 {@code UPDATE created_by}）。
     * 其中 {@code player_achievements} 是死表（E-03，删了无害：免残行未来被误接线复活）。
     * {@code seasons} 无玩家列故不动。
     *
     * <p><b>Round 17：{@code classes.created_by} 已处理（置哨兵，<u>不</u>删班）。</b>
     * 该列在玩家建班时写真实 UUID（控制台建班才是全零哨兵，见 {@code AdminCommandHandler}
     * 的 class create），列名既不是 {@code uuid} 也不是 {@code player_uuid}，两个数组的
     * 单列模板都套不上，故本方法末尾单列一条
     * {@code UPDATE ... SET created_by = <全零 UUID> WHERE created_by = ?} 把它抹掉。
     *
     * <p><b>为什么置哨兵而不是删班</b>：reset 的语义是"清<b>这个玩家</b>的数据"，而
     * {@code classes} 行是<b>共享结构</b>——{@link #deleteClass} 会连带删
     * {@code class_members}，若在这里复用它，一个人 reset 会让同班其他玩家的成员关系
     * 一起消失（第三方数据破坏，严重度高于"残留一个 UUID"）。管理员要删班有独立入口
     * {@code /lea class delete} → {@link #deleteClass}，不需要 reset 兼职。列是
     * {@code NOT NULL} 故不能置 NULL；用与"控制台建班"同一个全零 UUID 哨兵，
     * 可以让"没有真实创建者"在库里只有一种表示，而不是两种。
     *
     * <p><b>⚠ 该处置的前提是 {@code created_by} 当前零消费 —— 一旦它被展示层消费，
     * 这里必须重新评估。</b>已核实的现状：写入点只有 {@link #createClass}；读取点只有
     * {@link #getClassByName} / {@link #listClasses} 塞进 {@link ClassRow#createdBy()}，
     * 而 {@code ClassRow} 的消费方（{@code LearnCommand} 的班级榜、{@code AdminCommandHandler}
     * 的 class add/remove 等）只取 {@code classId()} / {@code className()} ——
     * 建班者从未被渲染到 GUI 或聊天。届时若真要做"班级详情显示建班者"，哨兵会把
     * "控制台建的班"和"建班者已被 reset"混成同一个值（展示层无法区分），那时应改方案：
     * 要么新增显式的"已擦除"标记列，要么展示层不展示创建者。
     *
     * <p>本 javadoc 刻意<b>不再逐表罗列</b>：此前罗列的清单（"uuid 系 9 表 + player_uuid
     * 系 4 表共 13 表"）在后续批次加表时全部失同步，反而误导维护者。加表请以数组为准，
     * 并同步更新 {@code @return} 与事务处的表数。
     *
     * <p>建表语句无外键（SQLite/MySQL 双实现与 schema.sql 均已查证），直删无需排序。
     * 与本类其他方法一致：全程持 {@code connectionLock}；连接不可用时
     * {@link #getConnection()} 换发抛 {@code SQLException} 的替身句柄（EH-01），
     * 因此调用方无需再 try/catch。
     *
     * <p><b>R11：返回 boolean 成败（原为 {@code void}）。</b>原实现自己
     * {@code catch (SQLException)} 只记 WARNING，于是 {@code AdminCommandHandler
     * .resetPlayerData} 在本方法返回后无条件打印 "Player data reset complete" 并向
     * 管理员回 "已重置…的学习数据" —— 而本方法是「任一表失败即整批 rollback」的单事务，
     * 失败时<b>一条数据都没删</b>。这是隐私删除 / 数据擦除语义，谎报成功比一般写失败重
     * （管理员据此认为数据已依法清除）。故照 {@link #upsertChatFeedback} 的 R11 范式改成
     * 有返回值：失败仍记 WARNING 保留现场，同时把成败交给调用方。
     *
     * <p>F5-03（备查）：已知极端竞态——在途异步 auto-save 快照（周期保存任务 /
     * persistLongestStreak / recordCoinsEarned 等即时落库）可能在 delete 后写回
     * 残行（毫秒窗口；玩家重进时数据正常覆盖，影响极小）。如需根治需引入
     * resetting per-player 标志（本表标记删中，写侧见此跳过），F5-03 备查。
     *
     * <p><b>D5：去规范化列 {@code social_feed.cheer_count} 的对账。</b>
     * 删 {@code social_cheers} 之前先快照「本玩家鼓励过哪些 feed」，删完按
     * {@code COUNT(*)} 重算这些 feed 的 {@code cheer_count}（见
     * {@link #selectCheeredFeedIds} / {@link #reconcileCheerCounts}）。此前只删行不减计数，
     * 于是「谁鼓励了我」名单少人、而广场卡片的「❤ 鼓励 ×N」不变 —— 同一张卡片上两个
     * 自相矛盾的数字，且没有任何对账路径能修回来。整段在同一事务内，中间态不可见。
     *
     * @param playerId the player whose rows should be removed
     * @return {@code true} = 事务已 commit，全部 22 张本玩家数据表的本玩家行已删除
     *         （{@code classes.created_by} 也已置哨兵；缺表的 player_uuid 系表按
     *         "本来就是空的"处理，见 {@link #delPlayerUuidTable}，不算失败；
     *         玩家没建过班导致 {@code classes} 匹配 0 行同样不算失败，见
     *         {@link #eraseClassCreator}）；
     *         {@code false} = <b>整批已 rollback（或事务根本没开起来 / commit 抛异常），
     *         数据一条都没删</b>。调用方必须把这个 false 报出去，不得提示成功。
     */
    public boolean deletePlayerData(UUID playerId) {
        String[] uuidTables = {
            "player_data", "word_progress", "achievements", "player_achievements",
            "learning_log", "player_sessions", "player_profiles", "daily_challenges",
            "season_rewards", // Wave 3 域 5：赛季发放台账（uuid 系；seasons 表无玩家列不动）
            "class_members" // Wave 4 批次 4A：班级成员关系（uuid 系；classes 表无玩家列不动）
        };
        // 这 4 表的玩家列叫 player_uuid，不是 uuid（见 createTables 建表语句）
        // US-FB（Wave 3 批次 3B）：ai_chat_feedback 一并清收（防 reset 残行）。
        String[] playerUuidTables = {
            "daily_challenge_words", "ai_chat_history", "difficult_words", "checkin_data",
            "ai_chat_feedback",
            "daily_word_reward_claims", // 修复批 A（R10-H1）：挑战奖励已付台账（player_uuid 系，防后建表盲区）
            "ai_round_counters", // Block C2（R2-M2）：今日 AI 轮次计数器（player_uuid 系，防后建表盲区）
            // Round 17：今日答题计数器（player_uuid 系，防后建表盲区）。与 ai_round_counters
            // 同为"每日台账"；漏了它 = /lea reset 后当日答题额度被旧行回读复活。
            "daily_answer_counters",
            // R5-DC-02（round6）：今日金币日限台账（player_uuid 系）。与
            // daily_word_reward_claims / ai_round_counters / checkin_data 同属"每日台账"，
            // 那三者都被清而它原先被漏掉 —— 且自 F-09-02 起它是日限的写穿真源，残行会让
            // reset 后的额度被回读复活（reload / 重启都不再能救）。
            //
            // ⚠ 这张表是 <b>createTables() 之外</b>当前唯一的懒建表（RewardEngine
            // .ensureDailyCoinsTable 首次记账时才建）。因此它可能在库中<b>根本不存在</b>：
            // 全新安装、或该服从未发出过一分钱的金币。缺表时跳过而非判失败，
            // 否则 DC-02 的"任一表失败整批回滚"会让 /lea reset 在干净库上永久失效
            // （实测：player_data / player_profiles 都删不掉）。
            //
            // 实现见 delPlayerUuidTable → tableExists（DatabaseMetaData.getTables 探测）。
            // 该探测对<b>所有</b> player_uuid 系表生效，不只 daily_coin_counters ——
            // 任何一张 player_uuid 表在库里缺席都会被当作"本来就是空的"跳过，
            // 不会拖垮整批。所以新增 player_uuid 表时无需为"缺表"额外做特殊处理。
            "daily_coin_counters"
        };
        String id = playerId.toString();
        // Round 17：classes.created_by 的擦除目标 —— 与 AdminCommandHandler 的
        // 「控制台建班」使用同一个全零 UUID 哨兵（列 NOT NULL，故不能置 NULL；
        // 用同一哨兵而非另一个占位串，是为了让"没有真实创建者"在库里只有一种表示）。
        String erasedCreator = new UUID(0L, 0L).toString();
        synchronized (connectionLock) {
            Connection conn = getConnection();
            // DC-02：本方法的全部表删除必须原子（原逐表自动提交 ⇒ 失败即永久部分删除）。
            // 任一表失败整批回滚。
            // 当前共 23 张不同物理表 = uuidTables 10 + playerUuidTables 9 + 下方 4 条显式
            // DELETE 语句涉及的 3 张表（social_cheers / social_feed / duel_stats）
            // + 末条 UPDATE 涉及的 1 张（classes，Round 17 起）。
            // ⚠ 真相源是上面的两个数组 + 下面的 del(...) / UPDATE 语句，不是这个数字：
            // 新增或删除表时必须同步更新它 — 它已经错过一次（写的 23，实为 22）。
            boolean failed = false;
            try {
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to open reset transaction for " + playerId, e);
                // R11：事务开不起来 = 一条数据都没删 → false（原实现 return; 后调用方照报成功）
                return false;
            }
            // R11：成败判据只有一条 —— 事务**真的 commit 成功**。因为 finally 里不能
            // return，故用局部标记带到 synchronized 块之外。三种情况都为 false：
            // ① 任一 DELETE 失败 → 走 rollback 分支；② commit 自己抛 SQLException
            // （数据同样没落盘）；③ 事务没开起来（上面已提前 return false）。
            boolean committed = false;
            try {
                for (String table : uuidTables) {
                    failed |= !del(conn, "DELETE FROM " + tablePrefix + table + " WHERE uuid = ?",
                            "rows from " + table + " of " + playerId, id);
                }
                for (String table : playerUuidTables) {
                    // R5-DC-02：缺表 ≠ 失败。探测对所有 player_uuid 系表生效
                    // （delPlayerUuidTable → tableExists），当前只有 daily_coin_counters
                    // 实际会缺席（懒建，见上面清单处的说明）；其余表由 createTables()
                    // 无条件建出。
                    failed |= !delPlayerUuidTable(conn, table, id, playerId);
                }
                // D5：去规范化计数对账。必须在删除<b>之前</b>取快照 —— 删完就查不到
                // 「本人鼓励过哪些 feed」了（行已经没了）。affectedFeeds 只含本玩家鼓励过的
                // feed id（走 idx_cheers_cheerer，代价与该玩家的鼓励条数成正比，不是全表）。
                List<Long> affectedFeeds = selectCheeredFeedIds(conn, id);
                boolean snapshotOk = affectedFeeds != null;
                if (!snapshotOk) {
                    // 快照失败 = 后面的对账无从做起 → 与「删表失败」同等处理：整批回滚。
                    // 宁可一条不删，也不能留下一个「行删了、计数没对账」的半成品（那正是 D5）。
                    failed = true;
                }
                // 顺序敏感：先删本人在他人 feed 上的鼓励，再删本人 feed（否则子查询无行可命中）。
                failed |= !del(conn, "DELETE FROM " + tablePrefix + "social_cheers WHERE cheerer_uuid = ?",
                        "social_cheers given by " + playerId, id);
                // D5：删完立刻按「实际行数」重算受影响 feed 的 cheer_count（不是 -1/N 的
                // 增量推测）。用 COUNT(*) 重算而不是减去 N 的理由：本方法要修的是
                // 「计数与行数不一致」这件事本身，重算是幂等的，且能顺手修掉历史遗留漂移；
                // 增量扣减只能在计数本来正确时成立（而它正是因为不总正确才有这条缺陷）。
                // 语句形状（UPDATE social_feed ⋈ 子查询 social_cheers）在 MySQL/SQLite 都合法：
                // 更新表与子查询表不同，不触碰 MySQL 的「更新-子查询自引用」限制
                // （同 markFeedCheersRead 里那条注释的判据）。
                // 快照为 null 时跳过（失败标志已置，整批会回滚），避免对 null 列表做增强 for。
                if (snapshotOk && !reconcileCheerCounts(conn, affectedFeeds)) {
                    failed = true;
                }
                failed |= !del(conn, "DELETE FROM " + tablePrefix + "social_cheers WHERE feed_id IN"
                        + " (SELECT id FROM " + tablePrefix + "social_feed WHERE player_uuid = ?)",
                        "social_cheers on feed rows of " + playerId, id);
                failed |= !del(conn, "DELETE FROM " + tablePrefix + "social_feed WHERE player_uuid = ?",
                        "social_feed rows of " + playerId, id);
                // duel_stats 是第四套列名（winner_uuid / loser_uuid 都可能是本人），故单列一条 OR 删除。
                failed |= !del(conn, "DELETE FROM " + tablePrefix + "duel_stats"
                        + " WHERE winner_uuid = ? OR loser_uuid = ?", "duel_stats rows of " + playerId, id, id);
                // Round 17：classes.created_by 擦除（**UPDATE 不是 DELETE** —— 班级行必须
                // 留着，否则会连带毁掉同班其他玩家的成员关系；理由见本方法 javadoc）。
                // 刻意<b>不用</b> del()：del 的成败判据是 executeUpdate 没抛异常，影响 0 行
                // 返回 true —— 正是本语句需要的语义（玩家没建过班时匹配 0 行是<b>正常</b>，
                // 若把它当失败就会触发 failed → 整批 rollback，/lea reset 对从没建过班的
                // 玩家永久失效）。所以这里只需要"SQL 没抛异常"，绝不能比影响行数。
                failed |= !eraseClassCreator(conn, id, erasedCreator);
            } finally {
                try {
                    if (failed) {
                        conn.rollback();
                        plugin.getLogger().warning("Reset of " + playerId + " rolled back (a delete failed).");
                    } else {
                        conn.commit();
                        committed = true;
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to finalize reset for " + playerId, e);
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // 事务已 commit/rollback
                    }
                }
            }
            if (!committed) {
                // R11：写一条「后果明确」的 WARNING —— 上面那两条只管"哪个 SQL 出错"，
                // 不告诉运维"管理员以为删了、实际一条没删"。管理员的成功提示由调用方负责，
                // 这条日志是唯一留在控制台、跨重启可查的现场。
                plugin.getLogger().warning("Reset of " + playerId
                        + " FAILED: nothing was deleted (transaction rolled back or commit failed)."
                        + " The caller must not report success.");
            }
            return committed;
        }
    }

    /**
     * D5：取出该玩家作为<b>鼓励者</b>留下的全部 {@code feed_id}（去重）。
     *
     * <p>必须在 {@code DELETE FROM social_cheers WHERE cheerer_uuid = ?} <b>之前</b>调用 ——
     * 删完就无从得知「该玩家影响过哪些 feed」，也就没法只重算受影响的那些行。
     *
     * <p>代价：走 {@code idx_cheers_cheerer}（schema.sql 已建），只按 UUID 取一列，
     * 与「该玩家鼓励过多少帖子」成正比，<b>与全表规模无关</b>。
     *
     * <p>为什么是 {@code SELECT DISTINCT}：同一玩家对同一条 feed 至多一行（UNIQUE 约束），
     * DISTINCT 只是让返回集合的语义显式（去重后重算 COUNT(*) 不会重复写同一条 feed）。
     *
     * @param conn 已开事务的连接
     * @return 去重后的 feed id 列表（可能为空 = 该玩家没鼓励过任何帖子）；
     *         <b>null</b> = 查询失败 —— 调用方据此把整个 reset 回滚，因为跳过快照会在
     *         库里留下「行删了 / 计数没对账」的半成品（那正是 D5 要消灭的状态）
     */
    private List<Long> selectCheeredFeedIds(Connection conn, String uuid) {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT feed_id FROM " + tablePrefix + "social_cheers WHERE cheerer_uuid = ?")) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to snapshot cheered feed ids of " + uuid + " (reset rolled back)", e);
            return null;
        }
    }

    /**
     * D5：把指定 feed 的 {@code social_feed.cheer_count} 按 {@code social_cheers} 的
     * <b>实际行数</b>重算（对账，不是增量扣减）。
     *
     * <p><b>为什么选「重算」而不是「减去 N」</b>（方案 A 与方案 B 之间的实测取舍）：
     * <ul>
     *   <li>受影响 feed 的集合来自 {@link #selectCheeredFeedIds}（有界，走
     *       {@code idx_cheers_cheerer}），所以这里已经拿到了方案 A「只动受影响的 feed」
     *       的好处 —— 不是方案 B 那种 O(全表) 的 {@code UPDATE ... WHERE} 全量重算；</li>
     *   <li>但每条 feed 的<b>新值</b>用 {@code COUNT(*)} 重新数，而不是
     *       {@code cheer_count - 1}：本方法要修的正是「计数与行数不一致」，
     *       重算是幂等的、可重复执行，且能顺手把 {@code cheer_count} 已经漂移过的历史
     *       行也拉回正确值；增量扣减只在「计数本来就对」的前提下成立，而那正是缺陷的
     *       反面假设（一旦之前漂过，扣减只会继续错）。</li>
     * </ul>
     * 一句话：<b>范围按方案 A（只碰受影响的 feed），值按重算（幂等、可自愈）</b>。
     *
     * <p>语句按条逐个执行而不是拼一条巨大的 {@code IN (...) + CASE}：受影响 feed 数量
     * 就是该玩家鼓励过的帖子数（正常玩家个位数到几十），逐条预编译语句更清晰、不触
     * 参数个数上限，且失败能精确到是哪条 feed。
     *
     * <p>SQLite/MySQL 双兼容：{@code UPDATE frequent LEFT JOIN something} 在 SQLite 上
     * 不可移植，故这里用「每条 feed 一条独立 UPDATE + 标量子查询」的通用形态
     * （{@code UPDATE … SET cheer_count = (SELECT COUNT(*) FROM … WHERE feed_id = ?) WHERE id = ?}）。
     * 子查询涉及的表（{@code social_cheers}）与被更新表（{@code social_feed}）不同，
     * 不触碰 MySQL 的「更新-子查询自引用」限制（同 {@link #markFeedCheersRead} 的判据）。
     *
     * @return true = 全部对账语句执行成功；false = 任一条失败（调用方整批回滚）
     */
    private boolean reconcileCheerCounts(Connection conn, List<Long> feedIds) {
        String sql = "UPDATE " + tablePrefix + "social_feed"
                + " SET cheer_count = (SELECT COUNT(*) FROM " + tablePrefix
                + "social_cheers c WHERE c.feed_id = ?)"
                + " WHERE id = ?";
        for (Long feedId : feedIds) {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, feedId);
                stmt.setLong(2, feedId);
                // 影响 0 行是正常态：该 feed 可能已被作者 reset 删掉（或本就属于别人且已清理）。
                // 成败判据只有「SQL 没抛异常」—— 与 eraseClassCreator 同口径。
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to reconcile cheer_count of feed " + feedId + " (reset rolled back)", e);
                return false;
            }
        }
        return true;
    }

    /** DC-02：单条 DELETE 失败只记 WARNING 并返回 false；调用方据此整批回滚。 */
    private boolean del(Connection conn, String sql, String what, String... params) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setString(i + 1, params[i]);
            }
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete " + what, e);
            return false;
        }
    }

    /**
     * R5-DC-02：{@code player_uuid} 系台账的删除，**缺表不算失败**。
     *
     * <p>为什么需要这一层：{@code daily_coin_counters} 是本类 {@code createTables()} 之外
     * 当前唯一的懒建表（由 {@code RewardEngine.ensureDailyCoinsTable} 在首次记账时创建）。
     * 在全新安装 / 该服从未发过金币的库上，它<b>根本不存在</b>，{@code DELETE FROM} 会抛
     * {@code no such table}。而 DC-02 的契约是"任一表失败整批回滚"，于是
     * {@code /lea reset} 会连带把 player_data / player_profiles 等全部回滚掉 ——
     * 表现为"管理员执行 reset、提示成功、玩家数据一条没删"（实测复现）。
     *
     * <p><b>探测范围刻意做成通用的</b>：本方法对 {@code playerUuidTables} 里的<b>每一张</b>
     * 表都先探测存在性，不是只给 {@code daily_coin_counters} 开特例 —— 日后任何一张
     * player_uuid 系表变成懒建/条件建表，缺表语义已经自动正确，不需要再来改这里。
     *
     * <p>故缺表按"本来就是空的"处理（跳过 + {@code FINE} 日志），其他 SQL 错误仍返回
     * {@code false} 让整批回滚。表存在性走 JDBC 元数据（{@link DatabaseMetaData#getTables}），
     * 与 {@link #columnAlreadyExists} 用 {@code PRAGMA table_info} /
     * {@code information_schema} 判列同源：都是"先查元数据再决定动作"，不依赖驱动的
     * 错误消息文本（locale/驱动版本差异下口径不稳）。
     *
     * @return true = 已删除或表不存在（对调用方而言都是"该表已无本玩家残行"）
     */
    private boolean delPlayerUuidTable(Connection conn, String table, String id, UUID playerId) {
        String physical = tablePrefix + table;
        if (!tableExists(conn, physical)) {
            plugin.getLogger().fine("Skipping reset for absent table " + physical
                    + " (never created yet) — player " + playerId);
            return true;
        }
        return del(conn, "DELETE FROM " + physical + " WHERE player_uuid = ?",
                "rows from " + table + " of " + playerId, id);
    }

    /**
     * Round 17：擦除 {@code classes.created_by} 里的本玩家 UUID（置全零哨兵，<b>不删班</b>）。
     *
     * <p>为什么不能复用 {@link #del}：本方法删的是 {@code uuid} / {@code player_uuid} 列，
     * 匹配 0 行是正常的，但 {@code classes.created_by} 的 0 行匹配有<b>额外</b>含义 ——
     * 绝大多数玩家从没建过班。若沿用"0 行 = 失败"的判据，{@code failed=true} 会让整个
     * reset 事务回滚，表现为"从没建过班的玩家 /lea reset 永远失败"（与 R5-DC-02 的
     * 缺表坑同形：把正常情况误判成故障，代价是全功能不可用）。
     *
     * <p>故本方法的成败判据只有一条：<b>SQL 没抛异常</b>。影响行数一律忽略。
     * 这与 {@link #del} 的语义其实一致（{@code del} 也是只看有没有抛），区别只在于
     * 本方法名要说清它不做行数判定，免得后来者"顺手统一成 0 行算失败"。
     *
     * <p>为什么是 UPDATE 而不是 DELETE：{@code classes} 行是共享结构，
     * {@link #deleteClass} 会连带删 {@code class_members}；在这里删班等于一个人 reset
     * 让同班其他玩家集体退班。管理员要删班用 {@code /lea class delete}。
     *
     * @return true = 语句执行成功（含匹配 0 行的正常情况，即该玩家从未建过班）；
     *         false = SQL 失败，调用方须整批回滚
     */
    private boolean eraseClassCreator(Connection conn, String id, String erasedCreator) {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE " + tablePrefix + "classes SET created_by = ? WHERE created_by = ?")) {
            stmt.setString(1, erasedCreator);
            stmt.setString(2, id);
            // 影响行数刻意丢弃：0 行 = 该玩家没建过班（正常），不是失败。
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to erase classes.created_by of " + id, e);
            return false;
        }
    }

    /**
     * R5-DC-02：表存在性元数据查询（SQLite / MySQL 通用）。
     *
     * <p>用 {@link DatabaseMetaData#getTables} 而不是 {@code sqlite_master} 或
     * {@code information_schema}：后两者各只服务一个后端。大小写按三种形态各查一次
     * （SQLite 存原名、MySQL 在 Windows 上可能折叠），命中即返回。
     *
     * <p>查询本身失败（连接/权限）时<b>保守返回 true</b>（当作存在），让随后的 DELETE
     * 去暴露真实错误并触发整批回滚 —— 与 {@link #columnAlreadyExists} 的容错口径一致，
     * 避免"元数据查不了 ⇒ 静默跳过删除"这种更危险的失败模式。
     */
    private boolean tableExists(Connection conn, String physicalTable) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            for (String candidate : new String[]{
                    physicalTable, physicalTable.toUpperCase(java.util.Locale.ROOT),
                    physicalTable.toLowerCase(java.util.Locale.ROOT)}) {
                try (ResultSet rs = meta.getTables(null, null, candidate, new String[]{"TABLE"})) {
                    while (rs.next()) {
                        if (physicalTable.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to probe existence of table " + physicalTable
                            + " — assuming it exists (conservative)", e);
            return true;
        }
    }

    // ============================================================
    //  Classes（US-CLS-1，Wave 4 批次 4A）
    // ============================================================

    /**
     * 班级头行（{@code classes} 表的读取投影）。
     *
     * @param classId     自增主键（成员表以此为关联键）
     * @param className   业务键（UNIQUE，命令与查询一律按班名）
     * @param displayName 展示名（建班时默认等于 className；预留改名能力）
     * @param createdBy   创建者 UUID 字符串
     * @param createdAt   创建时间戳（epoch 毫秒）
     */
    public static record ClassRow(int classId, String className, String displayName,
                                  String createdBy, long createdAt) { }

    /**
     * 班级成员行（{@code class_members} LEFT JOIN {@code player_data} 的读取投影）。
     *
     * @param classId    所属班级
     * @param uuid       成员 UUID
     * @param playerName player_data 的名字；查不到（LEFT JOIN 空）回落 uuid 字符串
     * @param joinedAt   加入时间戳（epoch 毫秒）
     */
    public static record ClassMemberRow(int classId, UUID uuid, String playerName, long joinedAt) { }

    /**
     * 建班的三态结果（{@link #createClassDetailed}）。
     *
     * <p><b>为什么 boolean 表达不出这里的两件事</b>：改造前的 {@code createClass} 返回
     * {@code boolean}，{@code false} 同时承载两个处置完全不同的结局：
     * <ol>
     *   <li><b>同名班级已存在</b> —— 正常的幂等拒绝，管理员该看到"已存在同名班级"，
     *       并且<b>不需要</b>去翻日志；</li>
     *   <li><b>SQL 失败</b>（{@code SQLITE_BUSY} / MySQL 死锁 / 磁盘满 / 连接不可用）——
     *       本次根本没建成，管理员该看到"创建失败，请检查控制台日志"。</li>
     * </ol>
     * 把 ② 折叠成 ① 的后果不是"少一句话"，而是<b>把管理员指向错误的排查方向</b>：他去看
     * "那个同名的班"，看不到，于是重试；故障持续时每次都被同一句话劝退，控制台里那条
     * WARNING 永远不会有人打开。
     *
     * <p>结构与取值风格照抄本类已有的三处三态（{@link SettlementClaim} /
     * {@link LedgerClearResult} / {@link CheckInClaim}）：它们都是"条件写 + SQL 异常"的形状，
     * 三态枚举是这类形状的唯一正确返回类型。本枚举的 {@code ALREADY_EXISTS} 与
     * {@link ClassDeleteResult#NOT_FOUND} 是同一种"正常的不命中"，故两处刻意用同一类词根。
     *
     * <ul>
     *   <li>{@link #CREATED} —— INSERT 命中，班确实建成了。提示 {@code class.created}；</li>
     *   <li>{@link #ALREADY_EXISTS} —— 同名班级已在（预检命中，或多服并发插入撞 UNIQUE 且
     *       <b>事后探测确认行已在</b>）。这是<b>正常</b>结局，提示 {@code class.name-exists}；</li>
     *   <li>{@link #FAILED} —— SQL 异常<b>且</b>事后探测不到同名行：本次没建成，也<b>不得</b>
     *       说成"重名"。提示 {@code class.create-failed}（必须带"检查控制台日志"的指引）。</li>
     * </ul>
     */
    public enum ClassCreateResult {
        /** INSERT 已写入：班建成了。 */
        CREATED,
        /** 同名班已存在（预检命中 / 并发撞 UNIQUE 且探测确认）：正常的幂等拒绝。 */
        ALREADY_EXISTS,
        /**
         * SQL 异常且事后探测<b>看不到</b>同名行：本次没建成，也<b>不能</b>说成"重名"。
         * 调用方必须报"创建失败、去查日志"，不得报 {@code name-exists}。
         */
        FAILED
    }

    /**
     * US-CLS-1：建班（三态版）。{@code class_name} 为业务键（UNIQUE）。
     *
     * <p>先查后插（同锁内；多服共享 MySQL 时并发插入由 UNIQUE 约束兜底）。
     * display_name 默认取 className（预留改名能力）。
     *
     * <p><b>为什么 INSERT 失败后还要回查一次</b>：R-18 之前两条路都归 {@code false}，
     * 调用方一律提示"已存在同名班级"。要分开就得在失败路径上判定"到底是不是重名"。
     * 判定手段刻意<b>不用</b>驱动错误码/错误文案（MySQL 1062 /
     * SQLite {@code SQLITE_CONSTRAINT_UNIQUE}）：错误码随驱动版本与连接参数漂移，
     * 错误文案随 locale 变化 —— 与 {@link #delPlayerUuidTable} 拒绝依赖
     * {@code no such table} 文案、改用 JDBC 元数据探测是同一条纪律。
     * 这里改问一句"这一行现在在不在"：
     * <ul>
     *   <li>在 → 并发那份 INSERT 赢了，本次是重名（{@link ClassCreateResult#ALREADY_EXISTS}）；</li>
     *   <li>不在（含回查本身失败）→ 真的没建成（{@link ClassCreateResult#FAILED}）。</li>
     * </ul>
     * 回查沿用的 {@link #getClassByName} 自身吞 SQLException 返回 {@code null}，方向正好是
     * <b>报 FAILED 而不是报重名</b>（fail-visible），故这里不需要额外区分"回查失败"。
     *
     * @param className 班名（业务键）
     * @param createdBy 建班者 UUID（控制台建班传全零哨兵）
     * @param createdAt 建班时间戳（epoch 毫秒）
     * @return 见 {@link ClassCreateResult}
     */
    public ClassCreateResult createClassDetailed(String className, UUID createdBy, long createdAt) {
        synchronized (connectionLock) {
            if (getClassByName(className) != null) {
                return ClassCreateResult.ALREADY_EXISTS; // 重名（调用方据此提示 class.name-exists）
            }
            String sql = "INSERT INTO " + tablePrefix + "classes"
                    + " (class_name, display_name, created_by, created_at) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, className);
                stmt.setString(2, className);
                stmt.setString(3, createdBy.toString());
                stmt.setLong(4, createdAt);
                stmt.executeUpdate();
                return ClassCreateResult.CREATED;
            } catch (SQLException e) {
                // UNIQUE 冲突（多服并发建同名班）或连接故障；两种都不抛，但必须分开报。
                plugin.getLogger().log(Level.WARNING,
                        "Failed to create class " + className
                                + " — probing whether the name is already taken", e);
                return getClassByName(className) != null
                        ? ClassCreateResult.ALREADY_EXISTS
                        : ClassCreateResult.FAILED;
            }
        }
    }

    /**
     * boolean 形态的建班（{@link #createClassDetailed} 的退化视图）。
     *
     * <p>语义<b>恰好</b>等于 {@code == ClassCreateResult.CREATED}，与改造前逐字节一致
     * （{@code false} 同时覆盖 {@code ALREADY_EXISTS} 与 {@code FAILED}）—— 保留它是为了
     * 既有调用方与测试不必改写，<b>不是</b>因为它是个好接口：凡是要按结局分支的调用方
     * 都必须改用 Detailed 版，否则就把"把 SQL 故障说成重名"这条缺陷又搬回去了。
     *
     * <p>当前生产调用方只有 {@code AdminCommandHandler.handleClassCreate}，它已改走三态版；
     * 保留 boolean 版是因为 {@code ResetErasesClassCreatorTest} 等测试只把它当
     * "建班成功"的前置断言（那些场景本来就是 {@code CREATED}，断言语义不变），
     * 与 {@link #clearSeasonLedgerPending} 保留退化视图的理由完全相同。
     *
     * @return {@code true} = 班确实建成了（{@link ClassCreateResult#CREATED}）
     */
    public boolean createClass(String className, UUID createdBy, long createdAt) {
        return createClassDetailed(className, createdBy, createdAt) == ClassCreateResult.CREATED;
    }

    /**
     * 删班的三态结果（{@link #deleteClassDetailed}）。
     *
     * <p><b>为什么 boolean 表达不出这里的三件事</b>：改造前的 {@code deleteClass} 返回
     * {@code boolean}，而它有<b>三条</b>互不相同的失败路径全都归 {@code false}：
     * <ol>
     *   <li><b>班不存在</b>（预检查不到，或预检查到了但班级行已被并发删除）；</li>
     *   <li><b>删 {@code class_members} 的 SQL 抛了</b>（{@code SQLITE_BUSY} / MySQL
     *       锁等待超时 / 表被改坏）—— 本次一条都没删；</li>
     *   <li><b>删 {@code classes} 的 SQL 抛了</b> —— 改造前成员行<b>已经永久删除</b>，
     *       班级行还在，留下一个空班。</li>
     * </ol>
     * 三者在调用侧需要的文案完全不同：①该说"找不到班级"（管理员打错名字，不用查日志），
     * ②③该说"删除失败，请检查控制台日志"（管理员必须去查，且<b>不得</b>以为班还在、
     * 也不得以为班已删干净）。把一个 SQL 故障说成"找不到班级"是**指向错误方向的谎报**：
     * 管理员会去核对班名，而真正的现场在控制台的 WARNING 里。
     *
     * <p>结构与措辞照抄本类已有的三处三态（{@link SettlementClaim} /
     * {@link LedgerClearResult} / {@link CheckInClaim}）。取值名沿用
     * {@code NOT_FOUND}（与 {@link ClassCreateResult#ALREADY_EXISTS} 同属"正常的不命中"）
     * 与 {@code FAILED}（同族三态的一致名字，运维对着日志不会以为是两件事）。
     *
     * <ul>
     *   <li>{@link #DELETED} —— 两条 DELETE 在<b>同一事务</b>里都成功并 commit：
     *       班级行与全部成员行都不在了。唯一的成功结局，提示 {@code class.deleted}；</li>
     *   <li>{@link #NOT_FOUND} —— 班里本来就没有这个名字（或并发删除抢先）。
     *       <b>正常</b>结局、<b>不是故障</b>：提示 {@code class.not-found}，
     *       管理员不需要去查日志。库内状态<b>没有</b>被本次调用改动；</li>
     *   <li>{@link #FAILED} —— SQL 抛出，<b>整批已 rollback</b>：班级行与成员行都还是
     *       调用前的样子（这正是本次加事务的目的 —— 改造前这个分支会留下空班）。
     *       提示 {@code class.delete-failed}，<b>不得</b>说成"找不到班级"。</li>
     * </ul>
     */
    public enum ClassDeleteResult {
        /** 两条 DELETE 同事务 commit：班级行与成员行都删掉了。 */
        DELETED,
        /** 班本来就不存在（或已被并发删除）：正常的"没事可做"，不改动任何数据。 */
        NOT_FOUND,
        /**
         * SQL 异常，整批已 rollback：数据保持调用前的样子。
         * <b>不得</b>折叠成 {@link #NOT_FOUND} —— 那会把一次数据库故障
         * 说成"班名打错了"，管理员永远看不到控制台里那条现场日志。
         */
        FAILED
    }

    /**
     * US-CLS-1：删班（三态版 + 单事务）。先删成员行再删班级行（无外键，排序防孤儿）。
     *
     * <p><b>R-18：两条 DELETE 收进一个事务。</b>改造前两条语句各自 auto-commit，
     * 于是第二条（删 {@code classes}）失败时，成员行<b>已经被永久删除</b>、班级行还在 ——
     * 留下一个空班，而调用方收到的提示是"找不到班级"。加事务后任一语句失败即整批
     * rollback，库内状态回到调用前，"失败"这个词才第一次名副其实。
     *
     * <p><b>事务写法逐字照抄 {@link #deletePlayerData}</b>（同文件、同一个踩坑现场）：
     * <ol>
     *   <li>{@code setAutoCommit(false)} 失败 → 事务没开起来，一条都没删
     *       （此时<b>不</b>进 try/finally，因为 finally 里的 rollback/commit 会打在
     *       自动提交的原始状态上，反而破坏调用前的连接状态）；</li>
     *   <li>任一条 DELETE 抛异常 → 记 {@code failed} 标记，<b>不在 catch 里 return</b>
     *       —— 必须走到 finally 让 rollback 执行；</li>
     *   <li>{@code finally} 里 {@code failed ? rollback() : commit()}，并再用一层
     *       {@code finally} 恢复 {@code setAutoCommit(true)}。那层嵌套的
     *       {@code finally} 是关键：commit/rollback 自己抛异常时若不恢复 autoCommit，
     *       该连接此后<b>所有</b>语句都会落在一个永远不提交的隐式事务里
     *       （{@link #deletePlayerData} 的注释里记着这个坑）；</li>
     *   <li>成败判据只有"commit 真的成功了" —— 用局部标记 {@code committed}
     *       带到 {@code synchronized} 块外，因为 finally 里不能 {@code return}
     *       （会吞掉异常并让 autoCommit 恢复被跳过）。</li>
     * </ol>
     *
     * <p><b>为什么成员行"影响 0 行"不算失败、班级行"影响 0 行"却是 NOT_FOUND</b>：
     * 一个班可以没有成员（建完空班就删），所以成员行 0 行是正常结果；而班级行是本次
     * 删除的目标，0 行意味着<b>行在查完之后被别的服删掉了</b>（TOCTOU 后窗），
     * 此时谎报成功会让管理员以为"自己删的那个班"没了，实际是别人删的同一个班 ——
     * 语义上仍应报 NOT_FOUND（该班确实已经不存在）。
     *
     * <p>锁纪律与改造前一致：查名 + 两条 DELETE 全程持 {@link #connectionLock}（锁可重入，
     * {@link #getClassByName} 内部自身取锁安全），无 TOCTOU。
     *
     * @param className 班名（业务键）
     * @return 见 {@link ClassDeleteResult}
     */
    public ClassDeleteResult deleteClassDetailed(String className) {
        synchronized (connectionLock) {
            // 4A tester L-2 收口：查名+删行同锁内（原锁外查+锁内删存在 TOCTOU——
            // 共享 MySQL 双服并发删同一班时旧实现可能"成员删了、班已不在"）。
            // 锁可重入，getClassByName 内部自身取锁安全。
            ClassRow row = getClassByName(className);
            if (row == null) {
                return ClassDeleteResult.NOT_FOUND;
            }
            Connection conn = getConnection();
            // R-18：事务开不起来 = 一条都没删（与 deletePlayerData 的同名分支同口径）。
            // 此时<b>不</b>进下面的 try/finally —— 连接仍处于自动提交的原始状态，
            // 冒然 rollback/commit 会把它从调用前的状态改坏。
            try {
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to open delete-class transaction for " + className, e);
                return ClassDeleteResult.FAILED;
            }
            // finally 里不能 return，故用局部标记带出 try。committed 恒 false 的三种成因：
            // ① 任一 DELETE 抛异常 → failed=true → 走 rollback 分支；② commit 自己抛
            // SQLException（数据同样没落盘）；③ 事务没开起来（上面已提前 return）。
            boolean failed = false;
            boolean committed = false;
            // 班级行被并发删掉（TOCTOU 后窗）：不是 SQL 故障，也不该谎报 DELETED。
            boolean concurrentGone = false;
            try {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM " + tablePrefix + "class_members WHERE class_id = ?")) {
                    stmt.setInt(1, row.classId());
                    // 成员行 0 行 = 空班，正常（不是失败）。
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM " + tablePrefix + "classes WHERE class_id = ?")) {
                    stmt.setInt(1, row.classId());
                    // 影响 0 行 = 班级已被并发删除（TOCTOU 后窗）。不是 SQL 故障，但本次
                    // 也确实没删掉什么 → 收尾后报 NOT_FOUND（见方法 javadoc 第 4 段）。
                    // 这里<b>不 return</b>：事务已开着，必须让 finally 收尾（否则 autoCommit
                    // 永远停在 false，该连接此后所有语句落进一个永不提交的隐式事务）。
                    if (stmt.executeUpdate() == 0) {
                        concurrentGone = true;
                    }
                }
            } catch (SQLException e) {
                // 失败<b>不能在这 return</b>：事务已开着，直接返回会把 autoCommit 永远
                // 留在 false —— 该连接此后所有语句都落进一个永不提交的隐式事务里
                // （deletePlayerData 的注释里记着这个坑）。故只置标记，交给 finally 回滚。
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete class " + className + " — rolling back the whole delete", e);
                failed = true;
            } finally {
                try {
                    if (failed) {
                        conn.rollback();
                        plugin.getLogger().warning("Delete of class " + className
                                + " rolled back (a delete failed).");
                    } else {
                        conn.commit();
                        committed = true;
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to finalize delete of class " + className, e);
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // 事务已 commit/rollback
                    }
                }
            }
            if (!committed) {
                // 后果明确的 WARNING：上面那条只说"哪个 SQL 出错"，不告诉运维
                // "管理员以为删了、实际一条没删"。管理员的失败提示由调用方负责。
                plugin.getLogger().warning("Delete of class " + className
                        + " FAILED: nothing was deleted (transaction rolled back or commit failed)."
                        + " The caller must not report success.");
                return ClassDeleteResult.FAILED;
            }
            return concurrentGone ? ClassDeleteResult.NOT_FOUND : ClassDeleteResult.DELETED;
        }
    }

    /**
     * boolean 形态的删班（{@link #deleteClassDetailed} 的退化视图）。
     *
     * <p>语义<b>恰好</b>等于 {@code == ClassDeleteResult.DELETED}，与改造前逐字节一致
     * （{@code false} 同时覆盖 {@code NOT_FOUND} 与 {@code FAILED}）—— 保留它是为了
     * 既有调用方与测试不必改写，<b>不是</b>因为它是个好接口：凡是要按结局分支的调用方
     * 都必须改用 Detailed 版（否则"把 SQL 故障说成找不到班级"立刻复发）。
     *
     * <p>当前生产调用方只有 {@code AdminCommandHandler.handleClassDelete}，它已改走三态版；
     * 保留 boolean 版与 {@link #clearSeasonLedgerPending} 的理由相同。
     *
     * @return {@code true} = 班级行与成员行都已删除（{@link ClassDeleteResult#DELETED}）
     */
    public boolean deleteClass(String className) {
        return deleteClassDetailed(className) == ClassDeleteResult.DELETED;
    }

    /**
     * US-CLS-1：按班名取班级头（null = 不存在）。
     */
    public ClassRow getClassByName(String className) {
        String sql = "SELECT class_id, class_name, display_name, created_by, created_at FROM "
                + tablePrefix + "classes WHERE class_name = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, className);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new ClassRow(rs.getInt("class_id"), rs.getString("class_name"),
                                rs.getString("display_name"), rs.getString("created_by"),
                                rs.getLong("created_at"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load class by name: " + className, e);
            }
        }
        return null;
    }

    /**
     * US-CLS-1：全部班级（建班顺序）。list 命令与补全消费。
     */
    public List<ClassRow> listClasses() {
        List<ClassRow> classes = new ArrayList<>();
        String sql = "SELECT class_id, class_name, display_name, created_by, created_at FROM "
                + tablePrefix + "classes ORDER BY class_id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    classes.add(new ClassRow(rs.getInt("class_id"), rs.getString("class_name"),
                            rs.getString("display_name"), rs.getString("created_by"),
                            rs.getLong("created_at")));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to list classes", e);
            }
        }
        return classes;
    }

    /**
     * US-CLS-1：班级成员（LEFT JOIN player_data 取名；player_name 空回落 uuid 字符串）。
     *
     * <p>ResultSet 关闭后再组装（UUID 解析/名字回落为纯内存操作，不持语句句柄）；
     * 脏 uuid 行跳过不拖垮整列表。
     */
    public List<ClassMemberRow> getClassMembers(int classId) {
        List<Object[]> raw = new ArrayList<>();
        String sql = "SELECT m.class_id, m.uuid, m.joined_at, p.player_name FROM "
                + tablePrefix + "class_members m LEFT JOIN " + tablePrefix + "player_data p"
                + " ON p.uuid = m.uuid WHERE m.class_id = ? ORDER BY m.joined_at ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, classId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        raw.add(new Object[]{
                                rs.getInt("class_id"), rs.getString("uuid"),
                                rs.getString("player_name"), rs.getLong("joined_at")
                        });
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load members of class " + classId, e);
            }
        }
        List<ClassMemberRow> members = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            String uuidStr = (String) r[1];
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue; // 脏行跳过，不拖垮整列表
            }
            String name = (String) r[2];
            if (name == null || name.isBlank()) {
                name = uuidStr;
            }
            members.add(new ClassMemberRow((int) r[0], uuid, name, (long) r[3]));
        }
        return members;
    }

    /**
     * US-CLS-1：玩家所在班级（一人一班由调用方编排；null = 无班）。
     */
    public Integer getPlayerClassId(UUID uuid) {
        String sql = "SELECT class_id FROM " + tablePrefix
                + "class_members WHERE uuid = ? LIMIT 1";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("class_id");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load class of player " + uuid, e);
            }
        }
        return null;
    }

    /**
     * US-CLS-1：加成员。已在班内返回 false（幂等由调用方提示）。
     * 换班语义（退旧班 + 进新班）由调用方编排——Data 层只管单班成员行。
     */
    public boolean addClassMember(int classId, UUID uuid, long joinedAt) {
        synchronized (connectionLock) {
            try (PreparedStatement check = getConnection().prepareStatement(
                    "SELECT 1 FROM " + tablePrefix
                            + "class_members WHERE class_id = ? AND uuid = ?")) {
                check.setInt(1, classId);
                check.setString(2, uuid.toString());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false; // 已在班内
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check class membership for " + uuid, e);
                return false;
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(
                    "INSERT INTO " + tablePrefix
                            + "class_members (class_id, uuid, joined_at) VALUES (?, ?, ?)")) {
                stmt.setInt(1, classId);
                stmt.setString(2, uuid.toString());
                stmt.setLong(3, joinedAt);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                // PK 冲突（多服并发加同人）或连接故障；两种都归 false 不抛
                plugin.getLogger().log(Level.WARNING,
                        "Failed to add member " + uuid + " to class " + classId, e);
                return false;
            }
        }
    }

    /**
     * US-CLS-1：移出成员。不在班返回 false。
     */
    public boolean removeClassMember(int classId, UUID uuid) {
        String sql = "DELETE FROM " + tablePrefix
                + "class_members WHERE class_id = ? AND uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, classId);
                stmt.setString(2, uuid.toString());
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to remove member " + uuid + " from class " + classId, e);
                return false;
            }
        }
    }

    /**
     * US-CLS-1：班级人数（/lea class add 满员判定；0 = 空班或班不存在）。
     */
    public int getClassMemberCount(int classId) {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix
                + "class_members WHERE class_id = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, classId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to count members of class " + classId, e);
            }
        }
        return 0;
    }

    /**
     * Shutdown the database connection.
     */
    public void shutdown() {
        synchronized (connectionLock) {
            // EH-01：置闩并**保留**已关闭连接引用 —— 见 getConnection() 的说明。
            shuttingDown = true;
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    plugin.getLogger().info("Database connection closed.");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing database connection", e);
            }
        }
    }

    /**
     * 当前后端是否为 SQLite（{@code true}）/ MySQL（{@code false}）。
     *
     * <p><b>无锁读</b>，依赖字段本身的 {@code volatile}（见 {@link #isSQLite} 字段的
     * 说明）。全仓 49 处调用点（{@code getTablePrefix()} 36 处，两 getter 合计 85 处
     * 引用）都在锁外先取方言再拼 SQL —— 在这里加锁会把发币热路径串行化，代价远大于收益。
     */
    public boolean isSQLite() {
        return isSQLite;
    }

    /**
     * SQL 表名前缀（默认 {@code lle_}）。
     *
     * <p><b>无锁读</b>，依赖字段本身的 {@code volatile}（见 {@link #tablePrefix} 字段的
     * 说明）。调用方都是「取前缀 → 拼 SQL → 进 {@code connectionLock} 执行」，
     * 加锁等于把两次加锁串在一条热路径上。
     */
    public String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * Expose the serialization monitor that guards the single shared Connection.
     *
     * <p>M16 band-aid（<b>历史说明</b>）: every method in this class that touches
     * the shared connection runs inside {@code synchronized (connectionLock)}, so
     * all access routed through DatabaseManager is serialized. At M16 several
     * classes still obtained the raw connection via {@link #getConnection()} and
     * ran their own SQL off this lock (PlayerDataStore, DailyChallengeManager,
     * AchievementManager, AdminCommandHandler, ProgressManagerImpl,
     * LLEPapiExpansion); those call sites had to wrap their statement execution
     * in {@code synchronized (databaseManager.getConnectionLock()) { ... }} on
     * this same monitor. <b>That era is over</b> — see the R6-F paragraph below.
     *
     * <p>Phase 1 流 E（F0-01/F0-02）曾登记"外部裸用点已收编完毕，共 25 处"。
     * <b>该账本已过期</b>。2026-10-25（R6-D）逐文件实测的开工基线为：
     * PlayerDataStore 12 处、DailyChallengeManager 10 处、ReportGenerator 9 处、
     * AchievementManager 4 处、AdminCommandHandler 3 处、LLEPapiExpansion 1 处、
     * LetsLearnEnglish 1 处，<b>共 40 处</b>；R6-D 将入口类备份任务的 1 处上收进
     * {@link #backupTo(String)} 后为 <b>39 处</b>。
     * （注：AdminCommandHandler 旧账登记 2 处，实测为 3 处，已按实测更正。）
     *
     * <p><b>R6 重构进行中</b>：上述裸用点正陆续迁入 {@code data/repository/*}
     * （DailyChallengeRepository / AchievementRepository / ReportRepository 等，
     * 仓储内部同样持本锁，约定不变），因此数字会实时变动——本注释记录的是
     * R6-D 开工基线而非终值。迁移完成后请重新统计并更新本段。
     *
     * <p><b>R6-F 进展</b>：{@code AdminCommandHandler} 的最后一处（{@code applyClassAdd}
     * 的"查班 → 判满员 → 退旧班 → 进新班"原子段）已下沉到
     * {@code data/repository/ClassRepository#addMemberAtomic}，该类的裸用点归零。
     * 至此 {@code data/} 之外（{@code core/}、{@code gui/}、{@code listener/}、
     * {@code command/}、{@code api/}、{@code expansion/}、入口类）对本锁与
     * {@link #getConnection()} 的引用<b>全部为零</b>；剩余引用只在 {@code data/} 之内
     * （{@code PlayerDataStore} 的 12 处 + 各 {@code data/repository/*} 仓储）。
     * 复核命令：{@code grep -rn "getConnectionLock()" src/main/java}，所有命中都应落在
     * {@code data/} 下（含 javadoc 提及）。
     *
     * <p>因此"新代码仍必须包在 synchronized 内"的要求只对 <b>data 层内部</b>生效；
     * 层外新代码请改用 {@link #withConnection(SqlFunction)} 或走现成仓储方法，
     * 不要直接取连接或取锁。
     */
    public Object getConnectionLock() {
        return connectionLock;
    }

    /**
     * R3-L4：幂等补列 —— 先以元数据查询判"列是否已存在"再决定 ALTER
     * （SQLite {@code PRAGMA table_info}、MySQL {@code information_schema.columns}，
     * 与 {@link #createIndexIfMissing} 的 MySQL 侧做法同源）。原实现按异常消息
     * 含 "duplicate"/"already exists" 判定，locale/驱动消息差异下口径不稳。
     *
     * <p>R12d-F7：返回 {@code boolean}（原为 {@code void}）—— 调用方需要知道
     * "这次启动是否真的补了列"，据此判断是否第一次升级到含该列的 schema
     * （{@code seasons.ledger_pending} 的遗留欠账需要一次性人共裁定，见
     * {@link #warnAboutUnclassifiableLedgerDebts()}）。</p>
     *
     * @return {@code true} = 本次调用新建了该列（老库升级）；{@code false} = 列已存在
     *         （或存在性查询失败，保守跳过）
     */
    private boolean addColumnIfMissing(String table, String column, String definition) throws SQLException {
        if (columnAlreadyExists(table, column)) {
            return false; // 已存在（或查询故障的保守跳过，见 helper）——跳过 ALTER
        }
        try {
            executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            return true;
        } catch (SQLException e) {
            // 查后改的 TOCTOU 残余窗口（多服共享 MySQL 并发启动同时补同一列，
            // 败者报 duplicate）仍按旧口径吞掉；其余真实错误（权限/磁盘/语法）
            // 照旧上抛，由 initialize 记 SEVERE。
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (!message.contains("duplicate") && !message.contains("already exists")) throw e;
            return false;
        }
    }

    /**
     * R3-L4：列存在性元数据查询。返回 true = 已存在或查询故障（保守跳过 ALTER）——
     * 查询故障（连接/权限等）记 WARNING 后按"已存在"处理，与旧实现"不因补列失败
     * 打断建表主流程"的容错口径一致，仅明确"列不存在"才返回 false 放行 ALTER。
     */
    private boolean columnAlreadyExists(String table, String column) {
        synchronized (connectionLock) {
            try {
                if (isSQLite()) {
                    try (PreparedStatement stmt = getConnection().prepareStatement(
                            "PRAGMA table_info(" + table + ")")) {
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                if (column.equals(rs.getString("name"))) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                }
                String checkSql = "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
                try (PreparedStatement stmt = getConnection().prepareStatement(checkSql)) {
                    stmt.setString(1, table);
                    stmt.setString(2, column);
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next() && rs.getInt(1) > 0;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check existence of column " + column + " on " + table
                                + " — skipping ALTER (conservative)", e);
                return true;
            }
        }
    }

    /**
     * 修复批 D（R3-L2）：把保留天数绑到唯一占位符——
     * SQLite 的 {@code datetime('now', ?)} / {@code date('now', ?)} 需字符串
     * modifier（{@code "-N days"}），MySQL 的 {@code INTERVAL ? DAY} 需 int。
     */
    private void bindKeepDays(PreparedStatement stmt, int daysToKeep) throws SQLException {
        if (isSQLite()) {
            stmt.setString(1, "-" + daysToKeep + " days");
        } else {
            stmt.setInt(1, daysToKeep);
        }
    }

    // ============================================================
    //  R6-D: 供仓储与入口类使用（2026-10-25）
    //
    //  本节只做"加法"：以上 92 个 public 方法的签名与实现均未改动。
    //  新增方法集中追加在文件末尾，避免与并行流（data/repository/*、core 各管理器）
    //  的改动面重叠，减少合并冲突。
    // ============================================================

    /**
     * R6-D：受检 SQL 操作的函数式载体。
     *
     * <p>作为 {@link DatabaseManager} 的嵌套接口定义，不新建顶层文件——避免与其他
     * 并行流在包扫描/白名单式检查上产生冲突。
     *
     * @param <T> 返回值类型
     */
    @FunctionalInterface
    public interface SqlFunction<T> {
        /**
         * 在 connectionLock 内对共享连接执行操作。
         *
         * @param conn 由 {@link #getConnection()} 提供的共享连接（调用期间持有锁）
         * @return 操作结果
         * @throws SQLException SQL 失败时抛出，由 {@link #withConnection} 统一记录
         */
        T apply(Connection conn) throws SQLException;
    }

    /**
     * R6-D：在 connectionLock 内取连接并执行一段 SQL 操作，失败记 WARNING 后返回 {@code null}。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：本重载目前只被同文件的
     * {@link #countRows(String)} 间接使用，<b>不是</b> R6 搬迁遗漏的死代码，而是为
     * 后续仓储化批次预留的标准形态——它就是"锁内取连接 + 异常兜底"这段样板的
     * 唯一收口点，后续新仓储应当直接用它，而不是各写一份 synchronized 模板。
     * 下次审计请勿按"零调用方"删除。</p>
     *
     * <p><b>这是可选工具，不是强制入口。</b>R6 的 5 个仓储流是并行开发的，
     * 它们可能不知道本方法存在——那没有任何问题：新仓储完全可以直接沿用既有范式
     *
     * <pre>{@code
     * synchronized (db.getConnectionLock()) {
     *     try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) { ... }
     * }
     * }</pre>
     *
     * <p>本方法只是把"加锁 + 取连接 + catch SQLException + 记 WARNING + 返回兜底值"
     * 这段重复样板收拢一次，语义与手写完全一致，可按需选用。
     *
     * <p>锁语义：内部先 {@code synchronized (connectionLock)} 再调用
     * {@link #getConnection()}（后者自身也同步同一把锁）。Java 的 synchronized 是
     * <b>可重入</b>的，同一线程二次进入不会自锁，因此不存在"嵌套加锁造成死锁"的问题；
     * 反过来说，仓储若已在外层持有该锁，再调用本方法同样安全（重入计数 +1 后退出）。
     * 请勿在持锁期间做消息投递/调度器调用等阻塞动作（R9-L5 约定）。
     *
     * <p>异常口径：只捕获 {@link SQLException}。EH-01 起 {@link #getConnection()} 在连接
     * 不可用时换发抛 SQLException 的替身句柄，故"数据库整体不可用"也走同一条降级路径，
     * 返回 {@code fallback} 而不是让非受检异常穿透调用方。
     *
     * @param fn 要执行的操作，不可为 null
     * @param <T> 返回值类型
     * @return 操作结果；{@code fn} 为 null 或抛出 SQLException 时返回 {@code null}
     */
    public <T> T withConnection(SqlFunction<T> fn) {
        return withConnection(fn, null);
    }

    /**
     * R6-D：{@link #withConnection(SqlFunction)} 的带兜底值重载。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：目前仅被 {@link #countRows(String)}
     * 与上面那个重载内部使用，为后续仓储化批次预留（带兜底值的变体是"读失败降级"
     * 的通用形态）。下次审计请勿按"零调用方"删除。</p>
     *
     * @param fn       要执行的操作，不可为 null
     * @param fallback 失败（或被传入 null 函数）时返回的默认值
     * @param <T>      返回值类型
     * @return 操作结果，或 {@code fallback}
     */
    public <T> T withConnection(SqlFunction<T> fn, T fallback) {
        if (fn == null) {
            return fallback;
        }
        synchronized (connectionLock) {
            try {
                return fn.apply(getConnection());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Database operation failed (withConnection) — returning fallback", e);
                return fallback;
            }
        }
    }

    /**
     * R6-D：诊断用行数统计。
     *
     * <p>表名<b>只接受白名单内的 23 张逻辑表名</b>（不含 table-prefix，如
     * {@code player_data}；若传入已带前缀的完整名也会被识别并剥掉前缀），
     * 其余一律拒绝并返回 -1——杜绝把外部输入拼进 SQL 造成注入/误查。
     * 前缀取自配置 {@code database.mysql.table-prefix}（SQLite 同样生效）。
     *
     * <p>R6-E（当前无生产调用方，为后续诊断/运维命令预留）：前缀与表名的剥离比较
     * 改为大小写不敏感——运维手工输入 {@code LLE_player_data} 时不该被拒。安全性不变：
     * 剥离<b>之后</b>仍要过 {@link #COUNTABLE_TABLES} 精确白名单，比对大小写不敏感
     * 只会让"已带前缀的完整名"这一输入形态更宽容，不会放宽白名单本身；
     * 实际拼进 SQL 的仍是白名单内的字面量，且 SQL 标识符在 SQLite/MySQL 默认配置下
     * 本身大小写不敏感。</p>
     *
     * @param table 逻辑表名，见 {@code COUNTABLE_TABLES}（私有常量，Javadoc 无法链接）
     * @return 行数；表名非法或查询失败返回 -1（与"0 行"区分）
     */
    public int countRows(String table) {
        if (table == null) {
            return -1;
        }
        String prefix = tablePrefix == null ? "" : tablePrefix;
        String logical = table.trim();
        // R6-E：剥离前缀用大小写不敏感比较；白名单校验仍用原值精确匹配。
        if (!prefix.isEmpty()
                && logical.regionMatches(true, 0, prefix, 0, prefix.length())) {
            logical = logical.substring(prefix.length());
        }
        if (!COUNTABLE_TABLES.contains(logical)) {
            plugin.getLogger().warning("countRows rejected unknown table: " + table);
            return -1;
        }
        final String counted = logical;
        Integer rows = withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + prefix + counted);
                 ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }, Integer.valueOf(-1));
        // 显式判 null：即便日后有人改动 fallback 传参，也不会因自动拆箱抛 NPE。
        return rows == null ? -1 : rows;
    }

    /**
     * R6-D：允许 {@link #countRows(String)} 统计的逻辑表名白名单（23 张，与
     * {@code createTables()} 的建表清单一一对应）。
     *
     * <p>新增表时必须同步补进本集合，否则诊断统计会安全地拒绝该表名。
     */
    private static final Set<String> COUNTABLE_TABLES = Set.of(
            "player_data",
            "word_progress",
            "achievements",
            "learning_log",
            "player_sessions",
            "player_profiles",
            "player_achievements",
            "daily_challenges",
            "daily_challenge_words",
            "daily_word_reward_claims",
            "ai_round_counters",
            "daily_answer_counters", // Round 17：与 createTables() 的建表清单一一对应
            "duel_stats",
            "ai_chat_history",
            "ai_chat_feedback",
            "difficult_words",
            "checkin_data",
            "social_feed",
            "social_cheers",
            "seasons",
            "season_rewards",
            "classes",
            "class_members"
    );

    /**
     * R6-D：SQLite 数据库文件的绝对路径，供诊断/运维显示与备份路径推演。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：这是诊断能力的基础件，为后续
     * {@code /lea} 诊断命令等批次预留，<b>不是</b> R6 搬迁的死代码。下次审计请勿
     * 按"零调用方"删除。</p>
     *
     * <p>与 {@code initializeSQLite()} 同源取值：数据目录 + 配置
     * {@code database.sqlite.filename}（默认 {@code playerdata.db}）。
     *
     * <p>后端判定读 {@code isSQLite} 字段（与既有 {@link #isSQLite()} 同源）。
     * 诊断命令在主线程调用，此时 initialize() 早已完成，无需额外同步。
     *
     * @return 绝对路径；当前后端为 MySQL（或尚未 initialize）时返回 {@code null}
     */
    public String getSqliteFilePath() {
        if (!isSQLite) {
            return null;
        }
        File dbFile = new File(plugin.getDataFolder(),
                configManager.getMainConfig().getString("database.sqlite.filename", "playerdata.db"));
        return dbFile.getAbsolutePath();
    }

    /**
     * R6-D：只读探活——不触发重连、不修改任何状态，供 /lea 诊断命令使用。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：这是诊断能力的基础件，为后续
     * {@code /lea} 诊断命令等批次预留，<b>不是</b> R6 搬迁的死代码。下次审计请勿
     * 按"零调用方"删除。</p>
     *
     * <p>直接读 volatile 字段而非走 {@link #getConnection()}：后者在连接缺失时会
     * 同步加锁并尝试 initialize()，那是"自愈"而不是"探活"，诊断命令不应有副作用，
     * 也不应为了报告一个状态而阻塞主线程等锁。
     *
     * @return true 表示当前持有可用连接；false 表示连接为 null、已关闭或状态不可读
     */
    public boolean isConnectionHealthy() {
        Connection conn = connection;
        if (conn == null) {
            return false;
        }
        try {
            return !conn.isClosed();
        } catch (SQLException e) {
            // isClosed 本身极少失败；失败时按"不健康"报告，不抛给诊断调用方。
            return false;
        }
    }

    /**
     * R6-D：SQLite 热备快照（{@code VACUUM INTO}）。
     *
     * <p>自 {@code LetsLearnEnglish.backupDatabase} 上收：原有
     * {@code synchronized (getConnectionLock())} + {@link #getConnection()} + 路径单引号
     * 转义逻辑<b>逐字保留</b>。
     *
     * <p>为什么仍是字符串拼接：SQLite 的 {@code VACUUM INTO} 只接受字面量文件名，
     * <b>不接受绑定参数</b>（{@code VACUUM INTO ?} 会报语法错误），因此只能拼接；
     * 调用方传入的是内部构造路径（数据目录 + 时间戳），单引号转义作为纵深防御保留。
     *
     * <p>异常口径与原实现一致：SQL 失败原样抛出 {@link SQLException}，由调用方
     * （备份任务）负责删除可能的残缺产物并记日志——本方法<b>不</b>做文件清理，
     * 以免与调用方的清理职责重叠。
     *
     * <p>后端约束：仅对 SQLite 有意义（MySQL 无 {@code VACUUM INTO}）。是否调用由
     * 调用方按 {@code database.type} 判断——与原入口类 {@code backupDatabase} 的
     * 前置检查保持同一职责划分，本方法<b>不</b>重复判断，以免在"部分初始化"等
     * 边缘状态下引入原本不存在的失败路径。
     *
     * @param absolutePath 目标文件的绝对路径（不可为 null/空）
     * @throws SQLException 目标路径为空，或 VACUUM INTO 执行失败
     */
    public void backupTo(String absolutePath) throws SQLException {
        if (absolutePath == null || absolutePath.isEmpty()) {
            throw new SQLException("Backup target path is empty");
        }
        synchronized (connectionLock) {
            String target = absolutePath.replace("'", "''");
            try (PreparedStatement stmt = getConnection()
                    .prepareStatement("VACUUM INTO '" + target + "'")) {
                stmt.execute();
            }
        }
    }

    /**
     * R6-D：备份产物完整性校验（{@code PRAGMA quick_check}）。
     *
     * <p>自 {@code LetsLearnEnglish.checkBackupIntegrity} 上收。关键点：必须使用
     * <b>独立只读连接</b>直连备份文件——查活连接等于查错对象（R3-H1 第二修）。
     * 因此本方法<b>不经过</b> {@link #getConnection()}，也不持有 connectionLock。
     *
     * <p>R6-D 加固：显式 {@code Class.forName("org.sqlite.JDBC")}（照
     * {@link #initializeMySQL} 的写法）。Bukkit/Paper 的插件 ClassLoader 与 JDBC 4
     * 的服务自动发现（依赖线程上下文类加载器）不总是能互相看见，显式加载可把失败点
     * 从晦涩的 "No suitable driver found" 提前成一条可读的 WARNING。加载失败时
     * <b>不直接判负</b>，而是记 WARNING 后继续尝试 DriverManager（若驱动经
     * ServiceLoader 可见仍可成功），保持与原实现相同的成功率。
     *
     * @param backupFile 备份文件
     * @return true 表示 quick_check 返回 "ok"；文件缺失、驱动缺失且无法连接、
     *         或校验结果非 ok 时返回 false
     */
    public boolean checkBackupIntegrity(File backupFile) {
        if (backupFile == null || !backupFile.isFile()) {
            plugin.getLogger().warning("Backup integrity check skipped: file not found"
                    + (backupFile == null ? "" : " (" + backupFile.getAbsolutePath() + ")"));
            return false;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            // 不提前返回：DriverManager 的服务发现仍可能成功，降级为提示。
            plugin.getLogger().warning("SQLite JDBC driver (org.sqlite.JDBC) not found by name; "
                    + "falling back to JDBC ServiceLoader discovery: " + e.getMessage());
        }
        String url = "jdbc:sqlite:" + backupFile.getAbsolutePath();
        // EH-03：原实现把「校验无法执行」与「判定为损坏」折叠成 false，而调用方对 false 的
        // 唯一反应是删文件 —— 一次 SQLITE_BUSY 就删掉完好备份。现在只有"明确损坏"才 false；
        // 查不成一律保留并记 SEVERE（彻底修法是三态返回，属 PUBLIC_API_CHANGE）。
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("PRAGMA quick_check");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (msg.contains("malformed") || msg.contains("not a database") || msg.contains("corrupt")) {
                plugin.getLogger().warning("Backup is corrupt: " + e.getMessage());
                return false;
            }
            plugin.getLogger().severe("Backup integrity check INCONCLUSIVE (" + e.getMessage()
                    + "); keeping " + backupFile.getName());
            return true;
        }
    }

    /**
     * L24：SQLite 维护性整理（{@code PRAGMA optimize} + {@code VACUUM}）。
     *
     * <p><b>它解决什么问题</b>：SQLite 的 {@code DELETE}/{@code UPDATE} 只把页面标记为
     * 可复用，<b>不归还操作系统</b>；{@code ai_chat_history}、{@code social_feed}、
     * {@code word_progress} 这类持续增删的表长期运行后文件只涨不缩，空闲页碎片化还会
     * 拖慢全表扫描。{@code VACUUM} 会重建整库文件，把空闲页真正释放并把行/索引重新
     * 紧凑排布。{@code PRAGMA optimize} 先按需刷新查询规划器统计信息（等价于一次
     * 有条件的 {@code ANALYZE}），两者搭配才是完整的"整理"。
     *
     * <p><b>与 {@link #backupTo(String)} 的区别</b>：{@code VACUUM INTO} 是<b>热备快照</b>
     * ——生成一个新的副本文件，主库一个字节都不会变，因此不能用来回收空洞；本方法才是
     * 就地整理。
     *
     * <p><b>线程契约（调用方必须保证）</b>：<b>绝不可在主线程（Bukkit tick 线程）调用</b>。
     * {@code VACUUM} 会持有写锁且耗时与库体积成正比（大库可达数秒到数十秒），
     * 在主线程执行会直接造成服务器卡顿甚至看门狗超时。调用方应自行丢到
     * {@code Bukkit.getScheduler().runTaskAsynchronously(...)} 或独立线程池里执行；
     * 本方法<b>不会</b>替你切换线程，也<b>不会</b>自动排程——它只是一个可被
     * 未来的 {@code /lea maintenance} 命令或停机维护脚本调用的一次性入口。
     *
     * <p><b>并发</b>：内部持 {@code connectionLock}（与全类同一把锁），因此执行期间所有
     * 其它数据库读写都会阻塞等待——这正是必须异步调用的第二个理由。为防止两次维护
     * 请求叠加把队列越排越长，这里用一个 {@link java.util.concurrent.atomic.AtomicBoolean}
     * 做非阻塞去重：已有维护在跑时直接返回 {@code false}。
     *
     * <p><b>事务状态</b>：SQLite 禁止在显式事务内执行 {@code VACUUM}（会报
     * "cannot VACUUM from within a transaction"）。本类所有事务段都在
     * {@code connectionLock} 内成对 {@code setAutoCommit(false/true)}，因此正常路径下
     * 进来时必为 autocommit。这里仍做一次防御性检查：若发现 autocommit 已被关闭
     * （说明有别处遗留了未提交事务），先恢复 autocommit 再执行。既然本方法全程持锁，
     * 此刻不可能有别的线程正处于事务中，该分支只会在异常/缺陷路径下命中——此时
     * <b>不</b>把 autocommit=false 还原回去：遗留的关闭状态本身才是 bug，让连接停在
     * autocommit=true 比让它继续"写进去永不提交"更安全（后者是静默丢数据）。
     *
     * <p><b>异常口径</b>：任何 {@link SQLException}（含库被占用、文件只读、磁盘满）都
     * <b>不</b>抛给调用方，只记 WARNING + 堆栈并返回 {@code false}。维护失败绝不该
     * 影响主流程——最坏情况只是"这次没整理成"。
     *
     * <p><b>后端约束</b>：MySQL 没有 {@code VACUUM}（对应能力是 {@code OPTIMIZE TABLE}，
     * 语义与代价都不同，未实现）。当前后端不是 SQLite 时直接记 FINE 并返回 {@code false}。
     *
     * <p><b>前置条件</b>：必须在 {@link #initialize()} 完成之后调用。后端判定读的是
     * {@code isSQLite} 字段，该字段只由 {@code initialize()} 赋值；在初始化之前调用会
     * 看到一个默认的 {@code false}，本方法按"非 SQLite"安全跳过（漏做一次整理，
     * 不会误在 MySQL 上执行 VACUUM）。
     *
     * <p><b>手动执行</b>（服务器停机维护）：见仓库 {@code README.md}「SQLite 数据库整理」
     * 与 {@code DEPLOYMENT_WIKI.md} 7.2.1。核心是三条 {@code sqlite3} 命令：
     * {@code PRAGMA optimize; VACUUM;} 再 {@code PRAGMA quick_check;} 校验。
     *
     * @return {@code true} 表示确实执行了 {@code PRAGMA optimize} + {@code VACUUM}；
     *         {@code false} 表示后端是 MySQL、已有维护在跑，或执行失败（已记日志）
     */
    public boolean runMaintenance() {
        if (!isSQLite) {
            plugin.getLogger().fine("Database maintenance skipped: not a SQLite backend.");
            return false;
        }
        if (!maintenanceInProgress.compareAndSet(false, true)) {
            plugin.getLogger().warning("Database maintenance skipped: another maintenance run is still in progress.");
            return false;
        }
        synchronized (connectionLock) {
            try {
                Connection conn = getConnection();
                if (!conn.getAutoCommit()) {
                    // VACUUM 不能处于事务内；持锁期间不应存在进行中的事务，属防御分支。
                    // 不还原：遗留 autocommit=false 会让之后所有写入永不提交（静默丢数据）。
                    plugin.getLogger().warning("Database maintenance found autocommit disabled; "
                            + "forcing autocommit=true so VACUUM can run (a caller left a transaction open).");
                    conn.setAutoCommit(true);
                }
                // 先刷新查询规划器统计（按需 ANALYZE），再重建文件回收空闲页。
                try (PreparedStatement optimize = conn.prepareStatement("PRAGMA optimize")) {
                    optimize.execute();
                }
                try (PreparedStatement vacuum = conn.prepareStatement("VACUUM")) {
                    vacuum.execute();
                }
                plugin.getLogger().info("Database maintenance finished (PRAGMA optimize + VACUUM).");
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Database maintenance failed (VACUUM/PRAGMA optimize); the database is left untouched", e);
                return false;
            } catch (RuntimeException e) {
                // getConnection() 自愈失败会抛 RuntimeException（连接不可用）——同样不得外泄。
                plugin.getLogger().log(Level.WARNING,
                        "Database maintenance aborted: database connection unavailable", e);
                return false;
            } finally {
                maintenanceInProgress.set(false);
            }
        }
    }
}
