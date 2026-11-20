package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R23：{@code quitDuel} 的<b>离线对手通告</b>（审计缺陷 A1，MEDIUM）的行为验收。
 *
 * <h2>缺陷形态</h2>
 * {@code quitDuel} 原来只对<b>在线</b>对手 {@code sendMessage("duel.opponent-forfeit")}，
 * 离线一方整段跳过；而后同一方法里的 {@code cleanup(room)} 会把双方
 * {@code disconnectTimers} 条目一并摘掉、并 cancel 离线方的宽限任务。于是该玩家重连时
 * {@code handleReconnect} 的 {@code disconnectTimers.remove(id)} 恒为 {@code null} →
 * <b>静默 return</b>。玩家看到的是「对局界面消失、分数停住、聊天栏一句话都没有」，
 * 无法区分三种结局（对手 quit / GM 解散 / 自己超时判负）。
 *
 * <h2>为什么这必须用<b>行为</b>测试而不是源码 grep</h2>
 * 「正文里出现 {@code enqueueOfflineDuelNotice}」这个断言可以被<b>一行注释</b>满足，
 * 也可以被「放在 if 里、在线时也排队」满足 —— 后者会让在线对手在下次进服时被
 * <b>重复通知一次</b>（{@code consumeOfflineDuelNotice} 的读后即清挡不住「先发后排队」）。
 * 两个方向的错误都需要能变红，因此这里直接驱动 {@link DuelManager#quitDuel}，
 * 观察唯一的真实可观察面：{@link DuelManager#consumeOfflineDuelNotice(UUID)}
 * 的返回（读后即清，恰好就是 PlayerJoinListener 的投递入口）。
 *
 * <h2>纯 JVM 前提</h2>
 * 只触碰 {@code Bukkit.getPlayer(UUID)} / {@code Bukkit.getScoreboardManager()}
 * （{@link MockedStatic} 接管，后者返回 null 时 {@code removeScoreboard} 自行早退），
 * 以及 {@code SoundManager.playSound}（{@code LetsLearnEnglish.getInstance()} 为 null
 * 时静默跳过）。不启动服务器、不碰数据库、不碰文件系统。
 */
class DuelQuitOfflineNoticeTest {

    /** 新键：断线期间对手主动 quit。刻意在本类重写一份字面量 —— 生产侧改名这里才会红。 */
    private static final String KEY = "duel.opponent-quit-while-disconnected";

    private static final UUID QUITTER = UUID.fromString("00000000-0000-0000-0000-00000000e101");
    private static final UUID OPPONENT = UUID.fromString("00000000-0000-0000-0000-00000000e102");

    private LetsLearnEnglish plugin;
    private MessageUtil messageUtil;
    private DuelManager manager;
    private DuelRoom room;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        messageUtil = mock(MessageUtil.class);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("rendered");

        manager = new DuelManager(plugin);
        room = new DuelRoom(QUITTER, "Alice", OPPONENT, "Bob");

        // 房间登记进 activeDuels（双键指向同一实例 —— 与 acceptDuel 的真实形状一致）。
        Field f = DuelManager.class.getDeclaredField("activeDuels");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, DuelRoom> active = (Map<UUID, DuelRoom>) f.get(manager);
        active.put(QUITTER, room);
        active.put(OPPONENT, room);
    }

    /** quit 的发起者：在线、消息可发、无计分板可摘（getScoreboardManager 返回 null）。 */
    private Player quitter() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(QUITTER);
        when(p.getName()).thenReturn("Alice");
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    // ------------------------------------------------------------------
    // 1. 对手离线 → 必须排队一条语义正确的通告（核心复现）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("对手离线时 quit：重连/进服必须能取到一条 opponent-quit-while-disconnected 通告")
    void offlineOpponentGetsQueuedNotice() {
        Player quitter = quitter();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(OPPONENT)).thenReturn(null); // 对手已离线

            manager.quitDuel(quitter);

            DuelManager.OfflineDuelNotice notice =
                    manager.consumeOfflineDuelNotice(OPPONENT);
            assertNotNull(notice,
                    "对手离线时 quitDuel 必须排队一条通告 —— 否则其重连后 handleReconnect 的 "
                            + "disconnectTimers 条目已被 cleanup 摘掉，会静默 return，玩家零反馈喵。");

            // 文案键必须是独立新键：cancelled-by-gm 说的是「管理员解散」（此场景是谎话），
            // lost-by-disconnect 说的是「你被判负」（quit 既不判负也不记战绩）。
            assertEquals(KEY, notice.messageKey(),
                    "必须用说明「对手退出了对决」的独立键，不得复用 cancelled-by-gm / "
                            + "lost-by-disconnect —— 那两条的文案与事实相反喵。");
            assertEquals("Alice", notice.otherPlayerName(),
                    "{player} 占位必须是被 quit 的那位玩家的名字（房间存量字段，不依赖已回收实体）喵。");

            // 读后即清：投递链是 consume 一次性的，不许残留（否则每次进服都重播）。
            assertNull(manager.consumeOfflineDuelNotice(OPPONENT),
                    "consume 必须是读后即清 —— 残留会让玩家每次进服都收到一次喵。");
        }
    }

    @Test
    @DisplayName("离线对手队列通向 quit 发起者自己 —— 主动退出方不该收到「对手退出了」")
    void quitterGetsNoNotice() {
        Player quitter = quitter();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(OPPONENT)).thenReturn(null);

            manager.quitDuel(quitter);

            assertNull(manager.consumeOfflineDuelNotice(QUITTER),
                    "发起 /le duel quit 的玩家当场已收到 duel.forfeit，不得再排队一条"
                            + "「对手退出了对决」—— 那是给离线方的文案喵。");
        }
    }

    // ------------------------------------------------------------------
    // 2. 对手在线 → 走既有 sendMessage，不排队（不重复通知）
    // ------------------------------------------------------------------

    /**
     * 反向断言：把「只在离线时排队」改成「总是排队」，本条会红。
     *
     * <p>为什么必须单独钉住：在线对手当场已经收到 {@code duel.opponent-forfeit}，
     * 若同时入队，他<b>下次进服</b>还会再收到一次 {@code opponent-quit-while-disconnected}
     * —— 同一件事通知两次，且中间隔了一次重启/重连，玩家只会认为系统在编造第二场对局。
     */
    @Test
    @DisplayName("对手在线时 quit：走既有 opponent-forfeit 播报，队列必须为空（不重复通知）")
    void onlineOpponentIsNotQueued() {
        Player quitter = quitter();
        Player opponent = mock(Player.class);
        when(opponent.getUniqueId()).thenReturn(OPPONENT);
        when(opponent.getName()).thenReturn("Bob");
        when(opponent.isOnline()).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(OPPONENT)).thenReturn(opponent);

            manager.quitDuel(quitter);

            // 既有播报路径照旧（在线方当场知情）。
            verify(opponent, times(1)).sendMessage("rendered");
            verify(messageUtil).getPlayerMessage(opponent, "duel.opponent-forfeit",
                    "player", "Alice");

            assertNull(manager.consumeOfflineDuelNotice(OPPONENT),
                    "在线对手已当场收到 opponent-forfeit，绝不入队 —— 否则下次进服会重复播报喵。");
            // 发起者同样不排队（两条路径一致）。
            assertNull(manager.consumeOfflineDuelNotice(QUITTER));
        }
    }

    @Test
    @DisplayName("对手离线时不得给对手发送任何在线消息（无实体可发，且会 NPE/静默失败）")
    void offlineOpponentReceivesNoDirectMessage() {
        Player quitter = quitter();
        Player opponent = mock(Player.class);
        when(opponent.isOnline()).thenReturn(false); // 实体还在缓存里但已离线

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(OPPONENT)).thenReturn(opponent);

            manager.quitDuel(quitter);

            verify(opponent, never()).sendMessage(anyString());
            assertNotNull(manager.consumeOfflineDuelNotice(OPPONENT),
                    "「取到了 Player 但 isOnline()==false」同样是离线 —— 必须走排队路径，"
                            + "否则该玩家重连后一无所有喵。");
        }
    }

    // ------------------------------------------------------------------
    // 3. 新键的 zh/en 对称性（I18nFullParityTest 之外的第二道锁）
    // ------------------------------------------------------------------

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    /** 与生产同一条加载路径（classpath 资源 + YamlConfiguration），显式 UTF-8（Windows 默认 GBK）。 */
    private static YamlConfiguration load(String resource) throws Exception {
        try (InputStream in = DuelQuitOfflineNoticeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 上找不到内置资源: " + resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("新键在 zh/en 都存在，且占位符集合严格对称（{prefix} + {player}）")
    void keyExistsAndIsSymmetric() throws Exception {
        YamlConfiguration zh = load("messages/zh.yml");
        YamlConfiguration en = load("messages/en.yml");

        String zhText = zh.getString(KEY);
        String enText = en.getString(KEY);

        assertNotNull(zhText, "zh.yml 缺键 " + KEY + " —— 中文玩家会看到 &c[?" + KEY + "]喵。");
        assertNotNull(enText, "en.yml 缺键 " + KEY + " —— 英文玩家会看到 &c[?" + KEY + "]喵。");

        // 占位符必须两边一致：MessageUtil 的替换是「有则替换」，缺一侧不报错、
        // 只是玩家看到裸露的 {player}，而服主自己（通常只用一种语言）永远看不到。
        assertEquals(placeholders(zhText), placeholders(enText),
                "zh/en 的占位符集合必须 1:1 对称（zh=" + placeholders(zhText)
                        + ", en=" + placeholders(enText) + "）喵。");
        assertEquals(new TreeSet<>(Set.of("prefix", "player")), placeholders(zhText),
                "该键必须带 {prefix}（与其他 duel.* 文案一致）与 {player}（说明是谁退出了）喵。");

        // 文案必须区分于既有的两条近邻键 —— 这是「不许复用」的可执行版本。
        assertFalse(zhText.equals(zh.getString("duel.cancelled-by-gm")),
                "不得与 cancelled-by-gm 同文（那说的是管理员解散）喵。");
        assertFalse(zhText.equals(zh.getString("duel.lost-by-disconnect")),
                "不得与 lost-by-disconnect 同文（quit 不判负、不记战绩）喵。");
        assertFalse(zhText.equals(enText), "zh 与 en 文案不应相同 —— 至少一侧没翻译喵。");
    }
}
