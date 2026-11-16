package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主菜单槽位冲突 fail-fast（用户裁决：同槽 = 拒绝加载该菜单）。
 *
 * <h2>缺陷本体</h2>
 * 原实现在 {@code MainMenu.open()} 里对同槽只打 WARNING 就继续渲染。而渲染是
 * <b>后写覆盖先写</b>、点击是 if-else <b>先命中者胜</b> —— 两者方向相反。槽位 11 同时
 * 给了 {@code word-learning}（下标 0，先渲染）与 {@code duel}（下标 7，后渲染）时：
 * <ul>
 *   <li>渲染：duel 后画 → 玩家看到的是<b>对决</b>图标；</li>
 *   <li>点击：word-learning 分支先命中 → 实际执行<b>单词学习</b>。</li>
 * </ul>
 * 图标与功能错位，玩家没有任何自救途径。这就是必须 fail-fast 的理由。
 *
 * <h2>判据的观测点</h2>
 * 用 {@code Bukkit.createInventory} 的<b>调用次数</b>：它是「界面真的被造出来」的唯一入口，
 * {@code open()} 无论走哪条路都要经过它。次数 0 只有一种解释 —— 在造界面之前就 return 了。
 * 配上「玩家收到了聊天提示」，就能区分「明确拒绝」与「静默失败」。
 *
 * <h2>纯 JVM 的两个必要拦截</h2>
 * 与 {@code PersonalScenarioGuiPermissionTest} / {@code DuelGuiSlotLayoutTest} 同款：
 * {@code Bukkit.createInventory} 用 {@link MockedStatic} 拦截并计数；
 * {@code new ItemStack(...)} 用 {@link MockedConstruction} 拦截（否则抛
 * {@code ExceptionInInitializerError}：No RegistryAccess）。
 *
 * <h2>被本类封死的变异</h2>
 * <ol>
 *   <li><b>把 fail-fast 改回「只告警」</b> → {@link #conflictRefusesToOpenTheMenu()} 红
 *       （createInventory 被调用 1 次）；</li>
 *   <li><b>拒绝时不发聊天提示</b>（静默 return）→ 同一条用例的 sendMessage 断言红；</li>
 *   <li><b>日志降级成 WARNING</b> → {@link #conflictIsLoggedAtSevereWithBothItemNames()} 红；</li>
 *   <li><b>把判据改成「配置里 9 个键任意重复即拒」</b> →
 *       {@link #hiddenDuplicateSlotDoesNotBlockTheMenu()} 红（该用例的重复槽被 gate 挡住）；</li>
 *   <li><b>过度修复：无冲突也拒绝</b> → {@link #cleanLayoutStillOpens()} 红。</li>
 * </ol>
 */
@DisplayName("主菜单槽位冲突 fail-fast")
class MainMenuSlotConflictTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final String LANG = "zh";

    /** {@code gui-layout.main-menu.<item>.slot} 的 9 个键，与生产代码 MENU_SLOT_KEYS 同序。 */
    private static final String[] ITEM_KEYS = {
            "word-learning", "dialogue-practice", "personal-profile", "english-square",
            "daily-challenge", "wrong-book", "language-toggle", "duel", "checkin"
    };
    /** 出厂默认槽位（两两不同 —— 所以出厂配置永远不该被本机制拒绝）。 */
    private static final int[] DEFAULT_SLOTS = { 11, 13, 15, 17, 10, 14, 18, 12, 16 };

    // ------------------------------------------------------------------ 脚手架

    /** {@code open()} 一次执行的全部可观测结果。 */
    private record Result(int createInventoryCalls,
                          List<String> chatMessages,
                          List<String> severeMessages,
                          List<String> allMessages,
                          Throwable thrown) {

        String chat() {
            return String.join("\n", chatMessages);
        }

        String severe() {
            return String.join("\n", severeMessages);
        }
    }

    /**
     * 用给定槽位表打开一次主菜单，收集「建了几次界面 / 玩家收到什么 / 日志打了什么」。
     *
     * @param slotsOverride 下标 → 槽位；为 null 表示用出厂默认值
     * @param gates         模块门覆盖（键为 {@code word-system/ai-chat/duel/checkin/daily-challenges}）
     */
    private Result open(int[] slotsOverride, java.util.Map<String, Boolean> gates) {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration messages = mock(FileConfiguration.class);
        FileConfiguration mainConfig = mock(FileConfiguration.class);
        Player player = mock(Player.class);
        Captured logs = new Captured();

        when(plugin.getConfigManager()).thenReturn(cm);
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn(LANG);
        when(plugin.getLogger()).thenReturn(logs.logger);
        when(cm.getMessageConfig(anyString())).thenReturn(messages);
        when(cm.getMainConfig()).thenReturn(mainConfig);
        // gui.menu-size 默认 27（承载 10..18，足够放下全部 9 个按钮）
        when(mainConfig.getInt("gui.menu-size", 27)).thenReturn(27);

        // 槽位：未覆盖项用出厂默认值
        int[] slots = (slotsOverride == null) ? DEFAULT_SLOTS.clone() : slotsOverride.clone();
        for (int i = 0; i < ITEM_KEYS.length; i++) {
            when(cm.getGuiSlot("main-menu", ITEM_KEYS[i], DEFAULT_SLOTS[i])).thenReturn(slots[i]);
        }

        // 模块门：默认全开（= 出厂配置），按需覆盖
        when(cm.isWordSystemEnabled()).thenReturn(gate(gates, "word-system"));
        when(cm.isAIChatEnabled()).thenReturn(gate(gates, "ai-chat"));
        when(cm.isDailyChallengesEnabled()).thenReturn(gate(gates, "daily-challenges"));
        when(cm.isDuelEnabled()).thenReturn(gate(gates, "duel"));
        when(cm.isCheckinEnabled()).thenReturn(gate(gates, "checkin"));

        // AI 服务判空：getAIChatManager() 返回 null 即「不可用」→ 画灰态但仍占槽渲染
        when(plugin.getAIChatManager()).thenReturn(null);

        // 文案一律返回 null → MessageUtil.getGuiMsg 给 "[?key]" → 生产代码走内置英文回落。
        // 这正是**当前仓库的真实状态**（冲突提示的 i18n 键还没写进 zh.yml / en.yml），
        // 所以本类顺带钉住「键缺失时仍然说人话」这条最要紧的性质。
        when(messages.getString(anyString())).thenReturn(null);
        when(messages.getString(anyString(), anyString())).thenReturn(null);

        List<String> chat = Collections.synchronizedList(new ArrayList<>());
        org.mockito.Mockito.doAnswer(invocation -> {
            chat.add(invocation.getArgument(0));
            return null;
        }).when(player).sendMessage(anyString());

        final int[] calls = {0};
        Throwable thrown = null;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        calls[0]++;
                        return mock(Inventory.class);
                    });
            try {
                new MainMenu(plugin).open(player);
            } catch (Throwable t) {
                // 捕获而非逃逸：断言要区分「拒绝」与「炸了」，让异常逃逸会掩盖真正的判据。
                thrown = t;
            }
        }
        return new Result(calls[0], chat, logs.severeMessages, logs.messages, thrown);
    }

    private Result open(int[] slotsOverride) {
        return open(slotsOverride, java.util.Map.of());
    }

    private static boolean gate(java.util.Map<String, Boolean> gates, String name) {
        Boolean v = gates.get(name);
        return v == null || v;   // 缺省 true —— 与出厂 config.yml 一致
    }

    /** 收集日志的探针（本仓每个测试类各带一份，跨包私有工具不可复用）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<String> severeMessages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("MainMenuSlotConflictTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    String msg = record.getMessage() == null ? "" : record.getMessage();
                    messages.add(msg);
                    if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                        severeMessages.add(msg);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    // ------------------------------------------------------------------ 用例 1：有冲突 → 不打开

    /**
     * <b>这条就是缺陷本体</b>：槽位 0 同时给 word-learning（下标 0）与 duel（下标 7），
     * 两者模块门都开着 → 必须拒绝打开，并且玩家必须收到聊天提示（不是静默）。
     *
     * <p>变异指向：把 fail-fast 改回「只告警不阻止」→ createInventory 变成 1 次 → 红。
     */
    @Test
    @DisplayName("有冲突 → 菜单不打开，且玩家收到明确提示（不是静默）")
    void conflictRefusesToOpenTheMenu() {
        int[] slots = DEFAULT_SLOTS.clone();
        slots[0] = 0;    // word-learning
        slots[7] = 0;    // duel —— 与上面同槽

        Result result = open(slots);

        assertTrue(result.thrown() == null,
                "拒绝路径必须安静 return，不得抛异常喵。抛出：" + result.thrown());
        assertEquals(0, result.createInventoryCalls(),
                "同槽冲突时不得创建主菜单界面喵 —— createInventory 被调用了 "
                        + result.createInventoryCalls() + " 次。\n"
                        + "这是 fail-fast 的唯一硬判据：界面被造出来就等于玩家拿到了它。");
        assertFalse(result.chatMessages().isEmpty(),
                "拒绝打开时玩家必须收到提示，不能静默喵 —— 静默等于把「服务器配坏了」"
                        + "伪装成「按钮坏了」，玩家会一直点、一直以为是自己网络问题。");
        assertTrue(result.chat().contains("0"),
                "提示里必须写明冲突的槽位号（0），玩家/服主才定位得到喵。实际收到：\n"
                        + result.chat());
    }

    @Test
    @DisplayName("冲突被 SEVERE 记录，且含具体槽位与两个项名")
    void conflictIsLoggedAtSevereWithBothItemNames() {
        int[] slots = DEFAULT_SLOTS.clone();
        slots[0] = 0;
        slots[7] = 0;

        Result result = open(slots);

        assertFalse(result.severeMessages().isEmpty(),
                "同槽冲突必须打 SEVERE（服主侧）喵 —— 降级成 WARNING 会和一堆无关告警混在一起，"
                        + "而这条是「主菜单整个用不了」，属于启动级故障。实际日志：\n"
                        + String.join("\n", result.allMessages));
        String severe = result.severe();
        assertTrue(severe.contains("slot 0"),
                "SEVERE 必须写明冲突的槽位号（slot 0）喵。实际：\n" + severe);
        assertTrue(severe.contains("word-learning"),
                "SEVERE 必须写明第一个冲突项（word-learning）喵。实际：\n" + severe);
        assertTrue(severe.contains("duel"),
                "SEVERE 必须写明第二个冲突项（duel）喵。实际：\n" + severe);
        assertTrue(severe.contains("gui-layout.main-menu"),
                "SEVERE 必须给出完整配置路径，否则服主不知道去哪改喵。实际：\n" + severe);
    }

    @Test
    @DisplayName("三向同槽：一条 SEVERE 里报出全部 3 对，不是只报第一对就收工")
    void threeWayConflictReportsEveryPair() {
        int[] slots = DEFAULT_SLOTS.clone();
        slots[0] = 3;    // word-learning
        slots[3] = 3;    // english-square
        slots[6] = 3;    // language-toggle

        Result result = open(slots);

        assertEquals(0, result.createInventoryCalls(), "三向同槽同样必须拒绝打开喵");
        String severe = result.severe();
        assertTrue(severe.contains("word-learning") && severe.contains("english-square")
                        && severe.contains("language-toggle"),
                "三向同槽有 3 对，全部都要报出来喵 —— 只报一对会让服主改完一对、"
                        + "再撞下一对，来回重启。实际：\n" + severe);
    }

    // ------------------------------------------------------------------ 用例 2：无冲突 → 正常打开

    @Test
    @DisplayName("无冲突 → 正常打开（出厂默认槽位两两不同）")
    void cleanLayoutStillOpens() {
        Result result = open(null);

        assertTrue(result.thrown() == null, "无冲突路径不得抛异常喵。抛出：" + result.thrown());
        assertEquals(1, result.createInventoryCalls(),
                "无冲突时必须照常创建主菜单界面喵 —— createInventory 调用次数应为 1，实际 "
                        + result.createInventoryCalls() + "。\n"
                        + "本用例封的是「过度修复」：为了避免冲突而干脆不开菜单，等于把整个功能删了。");
        assertTrue(result.chatMessages().isEmpty(),
                "无冲突时不该给玩家发任何提示喵。实际收到：" + result.chat());
    }

    @Test
    @DisplayName("出厂默认槽位表两两不同（fail-fast 不会误杀出厂配置）")
    void defaultSlotsAreDistinct() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int slot : DEFAULT_SLOTS) {
            assertTrue(seen.add(slot),
                    "MENU_SLOT_DEFAULTS 出现重复槽位 " + slot
                            + " —— 出厂配置会被 fail-fast 直接打死，全服开不了主菜单喵。");
        }
    }

    // ------------------------------------------------------------------ 用例 4：权限/门隐藏的"看似同槽"

    /**
     * 关键语义用例：{@code duel} 与 {@code checkin} 都配成 12，但 {@code duel.enabled=false}。
     * 玩家侧只有签到渲染，界面<b>完全无歧义</b> —— 不得因为「配置难看」就拒绝打开。
     *
     * <p>判据来自实读：{@code MainMenu} 里 {@code hasPermission} 0 命中，可见性只由
     * <b>模块门</b>决定（{@code duel}/{@code checkin}/{@code word-system}/{@code ai-chat}/
     * {@code daily-challenges}）。{@code dialogue-practice} 的「API Key 缺失」是灰态，
     * 仍占槽渲染，不算隐藏。
     *
     * <p>变异指向：把判据改成「配置里 9 个键任意重复即拒」→ 本用例红。
     */
    @Test
    @DisplayName("门隐藏导致的\"看似同槽\"不算冲突：duel 关闭时 duel+checkin 同槽照常打开")
    void hiddenDuplicateSlotDoesNotBlockTheMenu() {
        int[] slots = DEFAULT_SLOTS.clone();
        slots[7] = 12;   // duel   —— 与 checkin 同槽
        slots[8] = 12;   // checkin

        Result result = open(slots, java.util.Map.of("duel", false));

        assertTrue(result.thrown() == null,
                "门隐藏路径不得抛异常喵。抛出：" + result.thrown());
        assertEquals(1, result.createInventoryCalls(),
                "duel 关闭时该槽只有签到会渲染，界面无歧义，必须照常打开喵 —— "
                        + "用「配置里 9 个键任意重复即拒」会把这种零实伤的服一并打死。\n"
                        + "createInventory 实际调用次数：" + result.createInventoryCalls());
        assertTrue(result.chatMessages().isEmpty(),
                "无实伤的情况不该打扰玩家喵。实际收到：" + result.chat());
        // 但服主必须被提前告警：今天开模块，明天主菜单就废。
        // 注意这里断言的是 WARNING 而非 SEVERE —— 当前没有任何实伤（界面照常打开），
        // 用 SEVERE 会把「一个能用的服」和「主菜单整个用不了」记成同一级别。
        String all = String.join("\n", result.allMessages());
        assertTrue(all.contains("hidden by a module switch"),
                "被 gate 隐藏的重复槽仍须告警喵 —— 否则服主打开 duel.enabled 时主菜单"
                        + "会毫无预兆地直接开不了。实际日志：\n" + all);
        assertTrue(all.contains("duel") && all.contains("checkin"),
                "隐藏重复的告警要点名是哪两项喵。实际：\n" + all);
    }

    @Test
    @DisplayName("两项都可见时同槽 → 仍然拒绝（隐藏判据不能反过来当成放行口子）")
    void bothVisibleDuplicateStillRefuses() {
        int[] slots = DEFAULT_SLOTS.clone();
        slots[7] = 12;
        slots[8] = 12;

        // 两个门都开（默认）
        Result result = open(slots);

        assertEquals(0, result.createInventoryCalls(),
                "duel 与 checkin 都开着却同槽 12 —— 这是真实错位（图标是后画的签到，"
                        + "点击先后命中 duel），必须拒绝喵。");
        assertTrue(result.severe().contains("duel") && result.severe().contains("checkin"),
                "SEVERE 要点名这两项喵。实际：\n" + result.severe());
    }

    @Test
    @DisplayName("同槽但两项都被 gate 隐藏 → 不拒绝（一个都不渲染，无从错位）")
    void duplicateAmongBothHiddenDoesNotRefuse() {
        int[] slots = DEFAULT_SLOTS.clone();
        slots[0] = 5;   // word-learning
        slots[5] = 5;   // wrong-book —— 同归 word-system 门

        Result result = open(slots, java.util.Map.of("word-system", false));

        assertEquals(1, result.createInventoryCalls(),
                "word-system 关闭时这两项都不渲染喵 —— 一个都不画，谈不上「看到的和点到的不一样」，"
                        + "不该拒绝打开。");
    }

    // ------------------------------------------------------------------ 用例：越界回落后的默认表

    @Test
    @DisplayName("槽位越界回落默认表后不误判：仍正常打开")
    void outOfRangeSlotFallsBackAndStillOpens() {
        int[] slots = DEFAULT_SLOTS.clone();
        slots[0] = 999;   // 越界 → 整组回落默认表

        Result result = open(slots);

        assertTrue(result.thrown() == null, "越界回落路径不得抛异常喵。抛出：" + result.thrown());
        assertEquals(1, result.createInventoryCalls(),
                "越界回落用的是默认槽位表（两两不同），不得被判成冲突喵。实际 createInventory="
                        + result.createInventoryCalls());
    }

    // ------------------------------------------------------------------ 结构 lint：点击侧未漂移

    /**
     * 渲染侧既然会因冲突拒绝，点击侧（{@code MainMenuListener}）就必须与渲染侧读
     * <b>同一份</b>槽位 —— 否则「渲染用的默认表 / 点击用的原配置」会在越界回落路径上
     * 分成两套，玩家点到的按钮和看到的按钮又不是同一个了。
     */
    @Test
    @DisplayName("点击侧仍读同一份 gui-layout.main-menu 槽位（不得另起一套）")
    void clickSideReadsTheSameSlotConfig() {
        String source = readSource("listener", "MainMenuListener.java");
        for (String key : ITEM_KEYS) {
            assertTrue(source.contains("\"main-menu\", \"" + key + "\""),
                    "MainMenuListener 必须按 gui-layout.main-menu." + key
                            + " 判定点击槽位喵 —— 渲染侧用这份配置，点击侧用别的就会错位。");
        }
    }

    private static String readSource(String pkg, String fileName) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Paths.get(
                            System.getProperty("user.dir"), "src", "main", "java",
                            "com", "letslearnenglish", "minecraftplugin", pkg, fileName),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取 " + fileName + " 失败喵：" + e.getMessage(), e);
        }
    }
}
