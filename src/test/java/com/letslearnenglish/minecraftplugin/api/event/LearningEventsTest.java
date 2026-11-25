package com.letslearnenglish.minecraftplugin.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 自定义 Bukkit 事件的数据载体契约（纯 JVM，不起服务器）。
 *
 * <h2>能测什么、不能测什么</h2>
 * <p>{@code org.bukkit.event.Event} 的构造器不碰 {@code Bukkit.getServer()}（已反汇编确认：
 * 只给 {@code async} 布尔字段赋值，{@code HandlerList} 的构造器也只建 {@code EnumMap} 与
 * 静态注册表条目），所以三个事件类可以在<b>纯 JVM</b>里直接 {@code new} 出来 —— 本类只打
 * 「构造器存的值 getter 能取出来」「字段不可变」「HandlerList 是单例」「不是 Cancellable」
 * 这类事实。</p>
 *
 * <p><b>不测「事件真的被 fire 了」</b>：那需要 {@code Bukkit.getPluginManager().callEvent}，
 * 而 {@code Bukkit.getPluginManager()} 内部读静态 {@code server} 字段（反汇编：
 * {@code getstatic server → invokeinterface Server.getPluginManager}），纯 JVM 下必然 NPE。
 * 「触发点接没接线」改用源码 lint（{@link WiringLint}），那才是这类契约唯一可静态钉住的形式。</p>
 *
 * <h2>为什么 lint 必须剥注释再匹配</h2>
 * <p>本仓踩过：注释里出现的字符串会让 lint 假绿。三个触发点的注释里都<b>大量</b>写着
 * {@code callEvent} / {@code PlayerQuizCompleteEvent}（解释为什么那样派发），直接在原文上
 * 搜等于什么都没钉住。{@link WiringLint} 里的剥离器逐字符走状态机，把注释整段抹成空格
 * （保留换行），并用一条独立的自检用例证明它真的抹掉了注释 —— 否则「零命中 = 通过」的
 * 断言可以因为剥离器失效而恒绿。</p>
 */
class LearningEventsTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    // ==================================================================
    // 一、PlayerWordLearnedEvent
    // ==================================================================

    @Nested
    @DisplayName("PlayerWordLearnedEvent —— 数据载体")
    class WordLearned {

        @Test
        @DisplayName("构造器存的三个值都能原样取回（含中文释义为空的情况）")
        void gettersRoundTrip() {
            PlayerWordLearnedEvent event = new PlayerWordLearnedEvent(PLAYER, "apple", "苹果");

            assertEquals(PLAYER, event.getPlayerId(), "playerId 必须原样返回");
            assertEquals("apple", event.getWord(), "英文原词必须原样返回（不做大小写归一）");
            assertEquals("苹果", event.getWordChinese(), "中文释义必须原样返回");

            PlayerWordLearnedEvent noChinese = new PlayerWordLearnedEvent(PLAYER, "apple", null);
            assertNull(noChinese.getWordChinese(),
                    "词库缺中文释义时 getter 必须返回 null —— 若这里抛 NPE，"
                            + "监听方在事件里读释义就会炸掉整条结算链");
        }

        @Test
        @DisplayName("字段不可变：没有 setter，且字段声明为 final")
        void fieldsAreImmutable() throws Exception {
            assertNoSetters(PlayerWordLearnedEvent.class);
            for (String field : List.of("playerId", "word", "wordChinese")) {
                var f = PlayerWordLearnedEvent.class.getDeclaredField(field);
                assertTrue(Modifier.isFinal(f.getModifiers()),
                        field + " 必须是 final —— 事件是不可变数据载体，"
                                + "监听方之间共享同一个实例，任何可变字段都会被后一个监听器改掉");
            }
        }

        @Test
        @DisplayName("不是 Cancellable，但仍是 Event 且 HandlerList 单例")
        void notCancellableButIsEvent() {
            PlayerWordLearnedEvent event = new PlayerWordLearnedEvent(PLAYER, "apple", "苹果");

            assertTrue(event instanceof Event, "必须继承 Bukkit Event 才能被事件总线识别");
            assertFalse(event instanceof Cancellable,
                    "PlayerWordLearnedEvent 不得实现 Cancellable 喵 —— 掌握度此刻已经写进内存与"
                            + "待落盘队列，「取消」没有任何能撤销它的语义，"
                            + "监听方会误以为 isCancelled() 会改变结算结果");

            assertSame(PlayerWordLearnedEvent.getHandlerList(), event.getHandlers(),
                    "实例 getHandlers() 与静态 getHandlerList() 必须是同一个对象");
            assertSame(PlayerWordLearnedEvent.getHandlerList(), PlayerWordLearnedEvent.getHandlerList(),
                    "每次调用必须返回同一个 HandlerList —— 每次 new 一个会让监听器注册到"
                            + "永远不被派发的那个实例上");
        }
    }

    // ==================================================================
    // 二、PlayerQuizCompleteEvent
    // ==================================================================

    @Nested
    @DisplayName("PlayerQuizCompleteEvent —— 数据载体")
    class QuizComplete {

        @Test
        @DisplayName("构造器存的五个值都能原样取回（负分与 0/0 也算合法）")
        void gettersRoundTrip() {
            PlayerQuizCompleteEvent event =
                    new PlayerQuizCompleteEvent(PLAYER, 7, 3, -12L, "spelling");

            assertEquals(PLAYER, event.getPlayerId());
            assertEquals(7, event.getCorrectCount());
            assertEquals(3, event.getIncorrectCount());
            assertEquals(-12L, event.getScoreDelta(), "分数增量可以为负（答错扣分），必须原样保留");
            assertEquals("spelling", event.getMode());

            PlayerQuizCompleteEvent empty = new PlayerQuizCompleteEvent(PLAYER, 0, 0, 0L, "review");
            assertEquals(0, empty.getCorrectCount());
            assertEquals(0L, empty.getScoreDelta(),
                    "0 也必须原样返回 —— 若用 long 默认值 0 之外的手段表达「未知」，"
                            + "监听方无法区分「本局零分」与「字段没填」");
        }

        @Test
        @DisplayName("mode 为 null 时 getter 返回 null，不抛异常、不做归一化")
        void nullModeIsPassedThrough() {
            PlayerQuizCompleteEvent event =
                    new PlayerQuizCompleteEvent(PLAYER, 1, 0, 10L, null);

            assertNull(event.getMode(),
                    "mode 必须原样返回 null 喵。归一到某个具体模式（例如 \"normal\"）会把"
                            + "「不知道」伪装成事实，监听方据此统计出来的分布是错的");
        }

        @Test
        @DisplayName("字段不可变：没有 setter，且字段声明为 final")
        void fieldsAreImmutable() throws Exception {
            assertNoSetters(PlayerQuizCompleteEvent.class);
            for (String field : List.of("playerId", "correctCount", "incorrectCount",
                    "scoreDelta", "mode")) {
                var f = PlayerQuizCompleteEvent.class.getDeclaredField(field);
                assertTrue(Modifier.isFinal(f.getModifiers()), field + " 必须是 final");
            }
        }

        @Test
        @DisplayName("不是 Cancellable，但仍是 Event 且 HandlerList 单例")
        void notCancellableButIsEvent() {
            PlayerQuizCompleteEvent event =
                    new PlayerQuizCompleteEvent(PLAYER, 1, 0, 10L, "choice");

            assertTrue(event instanceof Event);
            assertFalse(event instanceof Cancellable,
                    "PlayerQuizCompleteEvent 不得实现 Cancellable 喵 —— 会话已结束、"
                            + "掌握度/累计分/复习种子全部写进内存，取消无法撤销其中任何一条");
            assertSame(PlayerQuizCompleteEvent.getHandlerList(), event.getHandlers());
        }
    }

    // ==================================================================
    // 三、PlayerAchievementUnlockEvent
    // ==================================================================

    @Nested
    @DisplayName("PlayerAchievementUnlockEvent —— 数据载体")
    class AchievementUnlock {

        @Test
        @DisplayName("构造器存的三个值都能原样取回（含成就名为 null）")
        void gettersRoundTrip() {
            PlayerAchievementUnlockEvent event =
                    new PlayerAchievementUnlockEvent(PLAYER, "first_word", "Fresh Start");

            assertEquals(PLAYER, event.getPlayerId());
            assertEquals("first_word", event.getAchievementId(),
                    "成就 id 是跨版本稳定的标识，必须原样返回");
            assertEquals("Fresh Start", event.getAchievementName());

            PlayerAchievementUnlockEvent unknown =
                    new PlayerAchievementUnlockEvent(PLAYER, "custom_id", null);
            assertNull(unknown.getAchievementName(),
                    "未知 id 没有显示名时返回 null，而不是空串 —— "
                            + "空串会让监听方把「没有名字」当成「名字就是空」");
        }

        @Test
        @DisplayName("字段不可变：没有 setter，且字段声明为 final")
        void fieldsAreImmutable() throws Exception {
            assertNoSetters(PlayerAchievementUnlockEvent.class);
            for (String field : List.of("playerId", "achievementId", "achievementName")) {
                var f = PlayerAchievementUnlockEvent.class.getDeclaredField(field);
                assertTrue(Modifier.isFinal(f.getModifiers()), field + " 必须是 final");
            }
        }

        @Test
        @DisplayName("不是 Cancellable 且 HandlerList 单例")
        void notCancellableButIsEvent() {
            PlayerAchievementUnlockEvent event =
                    new PlayerAchievementUnlockEvent(PLAYER, "first_word", "Fresh Start");

            assertTrue(event instanceof Event);
            assertFalse(event instanceof Cancellable,
                    "PlayerAchievementUnlockEvent 不得实现 Cancellable 喵 —— 事件刻意发在"
                            + "「内存已解锁 + UPSERT 已发出」之后（唯一并发闸门是 unlocked.add 的返回值）。"
                            + "插到写库之前会让并发触发重复事件；插到写库之后，取消又回滚不了任何东西。"
                            + "要控制解锁请用 achievements-config.yml 的开关与前置条件");
            assertSame(PlayerAchievementUnlockEvent.getHandlerList(), event.getHandlers());
        }
    }

    // ==================================================================
    // 四、接线 lint（触发点真的调用了 EventDispatch.fire）
    // ==================================================================

    @Nested
    @DisplayName("接线 lint —— 触发点必须真的派发（纯 JVM 测不到）")
    class WiringLint {

        @Test
        @DisplayName("PlayerWordLearnedEvent：跨阈判定在数据层，fire 在 endSession（分层）")
        void wordLearnedIsFiredFromEndSessionUsingDataLayerVerdict() {
            // 判据在数据层：掌握度只由 updateWordProgress 写，「跨阈」这个信息只有它算得出来。
            // 签名只锚到方法名 + 左括号：参数表在源码里跨行，写全参会让 lint 在换行处失配
            // （那样它会把「方法还在、只是折行了」误报成「方法被删了」）。
            String store = methodBody("data/PlayerDataStore.java",
                    "public Map<String, String> updateWordProgress(");

            assertContains(store, "masteryThreshold",
                    "updateWordProgress 里没有读 masteryThreshold 喵 —— "
                            + "阈值必须与 getMasteredWords 同源读同一个配置键（两处各读一次，"
                            + "reload 改阈值后两侧同 tick 生效）");
            assertContains(store, "newlyMastered.put(",
                    "updateWordProgress 里没有收集跨阈词喵 —— 返回值恒空会让事件永远不发。");
            assertContains(store, "return newlyMastered",
                    "updateWordProgress 没有返回跨阈词喵 —— 上层拿不到「哪个词刚学会」。");

            // 派发在核心层：数据层不反向依赖对外事件总线（分层方向是 API → … → Data → Config → Util）。
            assertFalse(stripComments(sourceOf("data/PlayerDataStore.java"))
                            .contains("EventDispatch"),
                    "PlayerDataStore 里出现了 EventDispatch 喵 —— 数据层不该反向依赖对外事件总线。"
                            + "掌握度的算法留在数据层、事件的派发留在核心层（endSession）。");

            String body = methodBody("core/word/WordManagerImpl.java",
                    "public void endSession(UUID playerId)");

            assertContains(body, "updateWordProgress",
                    "endSession 里没调 updateWordProgress 喵 —— 结算链被搬走了？");
            assertContains(body, "PlayerWordLearnedEvent",
                    "endSession 方法体里找不到 PlayerWordLearnedEvent 喵 —— "
                            + "数据层算出了跨阈词，但没有人把它派发出去，功能静默失效。");
            assertContains(body, "EventDispatch.fire",
                    "endSession 方法体里找不到 EventDispatch.fire 喵 —— "
                            + "事件类建好了但没人发，全部功能测试仍然会绿。");
        }

        @Test
        @DisplayName("PlayerQuizCompleteEvent：endSession 里在 session != null 块内 fire")
        void quizCompleteIsFiredFromEndSession() {
            String body = methodBody("core/word/WordManagerImpl.java",
                    "public void endSession(UUID playerId)");

            assertContains(body, "PlayerQuizCompleteEvent",
                    "endSession 方法体里找不到 PlayerQuizCompleteEvent 喵。");
            assertContains(body, "EventDispatch.fire",
                    "endSession 方法体里找不到 EventDispatch.fire 喵 —— "
                            + "会话结束是这个事件唯一的时机，少了这一行事件永远不发。");
            assertContains(body, "session.getTotalCorrect()",
                    "endSession 里没有取 session 的答题数喵 —— 事件字段必须有真实来源，"
                            + "不能填空值。");
        }

        @Test
        @DisplayName("PlayerAchievementUnlockEvent：tryUnlock 里 fire 且排在 unlocked.add 之后")
        void achievementUnlockIsFiredAfterTheConcurrencyGate() {
            String body = methodBody("core/achievement/AchievementManager.java",
                    "public boolean tryUnlock(UUID playerId, Achievement achievement)");

            assertContains(body, "PlayerAchievementUnlockEvent",
                    "tryUnlock 方法体里找不到 PlayerAchievementUnlockEvent 喵。");
            assertContains(body, "EventDispatch.fire",
                    "tryUnlock 方法体里找不到 EventDispatch.fire 喵。");

            // 顺序锚：fire 必须<b>晚于</b> unlocked.add —— 早于它的话，并发下两个线程都会
            // 通过 contains 检查并各发一次事件（add 的返回值才是唯一权威闸门）。
            int gate = body.indexOf("unlocked.add(");
            int fire = body.indexOf("EventDispatch.fire");
            assertTrue(gate >= 0,
                    "tryUnlock 里找不到 unlocked.add( 喵 —— F1 的并发闸门可能被改掉了，"
                            + "本 lint 的顺序断言随之失去意义");
            assertTrue(fire > gate,
                    "EventDispatch.fire 排在 unlocked.add( 之前喵（gate=" + gate
                            + "，fire=" + fire + "）。\n"
                            + "那样两条并发线程都能通过 contains 检查并各发一次事件 —— "
                            + "「重复调用不能重复发事件」这条契约会静默失效。");
        }

        /**
         * 自检：剥离器必须真的抹掉注释。
         *
         * <p>没有这条，「注释里写着 callEvent 也算命中」会让上面三条断言在代码被删空后
         * 依然通过。本仓已经在同类 lint 上踩过两次。</p>
         */
        @Test
        @DisplayName("自检：注释里的代码标识符不会被当成真实调用")
        void selfCheckStripperRemovesComments() {
            String source = """
                    class Fake {
                        // EventDispatch.fire
                        /* PlayerQuizCompleteEvent */
                        void real() {
                            EventDispatch.fire(null, null);
                        }
                    }
                    """;
            String stripped = stripComments(source);

            assertFalse(stripped.contains("PlayerQuizCompleteEvent"),
                    "注释里的 PlayerQuizCompleteEvent 必须被抹掉喵 —— 三个触发点的"
                            + "说明注释里都写着这些类名，剥不掉就等于什么都没钉住");
            assertEquals(1, countOccurrences(stripped, "EventDispatch.fire"),
                    "剥完只剩真实调用那一处喵。若数到 3，说明剥离器没生效，"
                            + "上面三条接线断言就全是假绿");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 断言目标类没有任何 setter（名字以 set 开头且 public 的方法）。 */
    private static void assertNoSetters(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            assertFalse(m.getName().startsWith("set"),
                    type.getSimpleName() + " 不该有 setter（发现 " + m.getName() + "）喵 —— "
                            + "事件是不可变数据载体，监听方共享同一个实例");
        }
    }

    /** 仓库根：从 target/test-classes 往上找 pom.xml。 */
    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("找不到含 pom.xml 的仓库根");
        }
        return p;
    }

    private static String sourceOf(String relativeUnderPackage) {
        Path p = repoRoot().resolve("src/main/java/com/letslearnenglish/minecraftplugin")
                .resolve(relativeUnderPackage);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("找不到源码 " + p.toAbsolutePath()
                    + " —— 文件被移动/改名会让本 lint 静默失效，必须硬失败");
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + p.toAbsolutePath() + " 失败", e);
        }
    }

    /** 取方法体（<b>剥注释 + 抹字面量</b>之后）。签名必须在源码中唯一出现。 */
    private static String methodBody(String relativeUnderPackage, String signature) {
        String stripped = stripComments(sourceOf(relativeUnderPackage));
        int sig = stripped.indexOf(signature);
        if (sig < 0) {
            throw new IllegalStateException("源码中找不到方法签名「" + signature
                    + "」喵 —— 方法被改名/改签名了，请同步本 lint");
        }
        if (stripped.indexOf(signature, sig + 1) >= 0) {
            throw new IllegalStateException("方法签名「" + signature + "」出现多次，无法唯一定位");
        }
        int open = stripped.indexOf('{', sig + signature.length());
        if (open < 0) {
            throw new IllegalStateException("签名「" + signature + "」之后没有左花括号");
        }
        int depth = 0;
        for (int i = open; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return stripped.substring(open + 1, i);
                }
            }
        }
        throw new IllegalStateException("签名「" + signature + "」的花括号没有配对结束");
    }

    private static void assertContains(String haystack, String needle, String message) {
        assertTrue(haystack.contains(needle), message);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    /**
     * 剥掉注释并把字符串/字符字面量的内容抹成空格（保留换行，偏移不变）。
     *
     * <p><b>为什么抹字面量而不只是删注释</b>：本 lint 找的是代码标识符；把字面量一起抹掉，
     * 可以让「日志/提示文本里恰好出现类名」这种情形不至于冒充真实调用。副作用是本类
     * 其余断言不能依赖字符串常量（本文件里没有那种断言）。</p>
     */
    static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // 行注释
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            // 块注释（未闭合则到文末）
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

            // 文本块 """..."""
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j < n) {
                    if (source.charAt(j) == '\\') {
                        j += 2;
                        continue;
                    }
                    if (source.charAt(j) == '"' && j + 2 < n && source.charAt(j + 1) == '"'
                            && source.charAt(j + 2) == '"') {
                        j += 3;
                        break;
                    }
                    j++;
                }
                j = Math.min(n, j);
                blank(out, i + 1, Math.max(i + 1, j - 1));
                i = j;
                continue;
            }

            // 普通字符串
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"' || d == '\n') {
                        break;
                    }
                    j++;
                }
                blank(out, i + 1, Math.min(j, n));
                i = Math.min(j + 1, n);
                continue;
            }

            // 字符字面量
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '\'' || d == '\n') {
                        break;
                    }
                    j++;
                }
                blank(out, i + 1, Math.min(j, n));
                i = Math.min(j + 1, n);
                continue;
            }

            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = Math.max(0, from); k < Math.min(to, out.length); k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }
}