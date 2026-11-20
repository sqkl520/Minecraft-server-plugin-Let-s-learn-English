package com.letslearnenglish.minecraftplugin.core.social;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R22：{@link FeedTextFormatter} 的纯函数用例（本类此前零行覆盖）。
 *
 * <h2>为什么值得单独一个类</h2>
 * 它是动态流<b>唯一的文案组装层</b>：{@code square.feed.*} 模板选哪个、占位符填什么、
 * 隐私降级走哪条，全在这 135 行里。DB 只存类型/数字/成就 id，所以「玩家在广场上看到
 * 什么」几乎完全由本类决定 —— 而它此前没有任何一条断言。
 *
 * <h2>本类钉住的四组事实</h2>
 * <ol>
 *   <li><b>选键规则</b>：{@code events_count > 1} 走 merged 变体，否则走单条变体；
 *       未知事件类型回 {@code null}（调用方据此跳过整条，而不是渲染半句话）。</li>
 *   <li><b>隐私降级</b>：{@code showResults=false} 时数字行换成
 *       {@code hidden-detail}，<b>连「答对几题」的模板都不取</b>（不是取了再丢弃 ——
 *       那种实现一旦被调用方误渲染就会真泄露）。</li>
 *   <li><b>i18n 覆盖</b>：本类硬编码的 7 个键必须真实存在于 zh/en 两份语言文件，
 *       否则玩家看到的是 {@code [?square.feed.session]} 而不是一句话。</li>
 *   <li><b>颜色码注入（D8，已修）</b>：{@code playerName} 曾原样拼进模板，而 GUI 随后对它做
 *       {@code translateAlternateColorCodes('&', ...)}。现在本层在拼接前剥色，
 *       且<b>只剥占位符取值、不剥模板</b>（后者的护栏见
 *       {@code ColorCodeInjection.templateOwnColorCodesSurvive}）。</li>
 * </ol>
 */
@DisplayName("R22：FeedTextFormatter —— 动态流文案组装（零覆盖补测）")
class FeedTextFormatterTest {

    /** 夹具：真实 zh.yml 的同款模板（只保留本类会用到的键）。 */
    private static final Map<String, String> ZH = new HashMap<>();
    private static final Map<String, String> EN = new HashMap<>();

    static {
        ZH.put("square.feed.session", "&f{player} 完成了一次&e{mode}&f会话");
        ZH.put("square.feed.session-merged", "&f{player} 今天完成了 &e{count} &f次学习会话");
        ZH.put("square.feed.achievement", "&f{player} 解锁了成就 &6{achievement}&f！");
        ZH.put("square.feed.achievement-merged",
                "&f{player} 今日解锁了 &e{count} &f个成就，最新：&6{achievement}");
        ZH.put("square.feed.checkin", "&f{player} 连续签到 &e{streak} &f天");
        ZH.put("square.feed.session-detail", "&7答对 &a{correct}&7/&e{total}");
        ZH.put("square.feed.hidden-detail", "&7完成了一次学习会话");
        ZH.put("word.mode-spelling", "拼写");
        ZH.put("word.mode-choice", "选择题");
        ZH.put("word.mode-fill_blank", "填空题");
        ZH.put("word.mode-review", "复习");

        EN.put("square.feed.session", "&f{player} finished a &e{mode}&f session");
        EN.put("square.feed.session-merged", "&f{player} completed &e{count} &fsessions today");
        EN.put("square.feed.achievement", "&f{player} unlocked the achievement &6{achievement}&f!");
        EN.put("square.feed.achievement-merged",
                "&f{player} unlocked &e{count}&f achievements today, latest: &6{achievement}");
        EN.put("square.feed.checkin", "&f{player} checked in &e{streak}&f days in a row");
        EN.put("square.feed.session-detail", "&7Correct: &a{correct}&7/&e{total}");
        EN.put("square.feed.hidden-detail", "&7Completed a learning session");
        EN.put("word.mode-spelling", "Spelling");
        EN.put("word.mode-choice", "Choice");
        EN.put("word.mode-fill_blank", "Fill-blank");
        EN.put("word.mode-review", "Review");
    }

    /**
     * 夹具查表：命中返回模板，缺键返回 {@code <missing:key>} 而不是 null。
     *
     * <p>为什么不能用 {@code ZH::get}：缺键时它返回 {@code null}，紧接着的
     * {@code .replace(...)} 会 NPE —— 于是「夹具缺键」会表现成一个与真实原因无关的
     * NullPointerException，而不是可读的断言失败。本仓 lint 反复踩过这个形状。
     */
    private static final FeedTextFormatter.KeyLookup STRICT_ZH =
            key -> ZH.containsKey(key) ? ZH.get(key) : "<missing:" + key + ">";

    private static final FeedTextFormatter.KeyLookup ZH_LOOKUP = STRICT_ZH;

    private static final FeedTextFormatter.KeyLookup EN_LOOKUP =
            key -> EN.containsKey(key) ? EN.get(key) : "<missing:" + key + ">";

    /** 缺键全占位：用于「必须只取某一个键」这类断言。 */
    private static final FeedTextFormatter.KeyLookup MISSING_ALL = key -> "<missing:" + key + ">";

    private static String eventLine(String type, int count, String player, String mode,
                                    int streak, String achievementId) {
        return FeedTextFormatter.buildEventLine(type, count, player, mode, streak,
                achievementId, "zh", ZH_LOOKUP);
    }

    /**
     * 去掉<b>{@code &x} 形态</b>的颜色码，得到玩家实际看到的可见文本。
     *
     * <p><b>为什么不能用 {@code ChatColor.stripColor}</b>：那个方法只认已经转义过的
     * {@code §}（U+00A7）控制符。而本层（{@code FeedTextFormatter}）刻意<b>不</b>转义 ——
     * 它把 {@code &f}/{@code &e} 原样交给调用方，由 {@code EnglishSquareGUI}
     * 做 {@code translateAlternateColorCodes('&', ...)}（见 {@link ColorCodeInjection}
     * 那条链路 lint）。所以在这一层拿 {@code stripColor} 去洗，等于什么都没洗：
     * 实测 {@code stripColor("&fA 今天完成了 &e2 &f次学习会话")} 原样返回，
     * 于是「可见文本」断言会一直因为「模板里有颜色码」而红，而不是因为逻辑错。
     *
     * <p>做法上选「剥颜色码再比」而不是「把期望值写成带颜色码的字面量」：
     * 后者把模板的<b>具体配色</b>钉进断言，将来把 {@code &e} 改成 {@code &6}
     * （纯审美改动、玩家可见文本不变）就会误报；而这里断言的是
     * 「玩家读到哪句话」，与配色解耦。
     */
    private static String visible(String line) {
        assertNotNull(line, "可见文本断言的前置条件：渲染结果不得为 null");
        return line.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    // ==================================================================
    // 组 1 —— 选键规则（merged / 单条 / 未知）
    // ==================================================================

    @Nested
    @DisplayName("组 1 —— 选键规则")
    class KeySelection {

        @Test
        @DisplayName("SESSION_DONE count==1 → session 模板，模式名按 i18n 本地化")
        void singleSessionUsesPlainTemplate() {
            String line = eventLine("SESSION_DONE", 1, "小明", "choice", 0, null);

            assertEquals("&f小明 完成了一次&e选择题&f会话", line,
                    "count==1 必须走单条模板，且 {mode} 要按 word.mode-* 本地化（不能把 "
                            + "英文标识符 choice 直接给玩家看）");
        }

        @Test
        @DisplayName("SESSION_DONE count>1 → session-merged，且不再出现模式名")
        void mergedSessionDropsMode() {
            String line = eventLine("SESSION_DONE", 3, "小明", "choice", 0, null);

            // 模板是 "&f{player} 今天完成了 &e{count} &f次学习会话" —— 注意 &e 与 &f 夹在
            // {count} 两侧，所以裸子串 "今天完成了 3 次" 并不存在（中间有颜色码）。
            // 断言必须按剥掉 &x 之后的可见文本做，否则这条会因为模板配色而不是逻辑失败。
            // 注意用 visible() 而不是 ChatColor.stripColor()——后者只认 '§'，对 '&' 无效。
            assertEquals("小明 今天完成了 3 次学习会话", visible(line), line);
            assertFalse(line.contains("选择题"), "合并行不该再提模式（它聚合了多种模式）：" + line);
            assertFalse(line.contains("{"), "占位符必须全部替换：" + line);
        }

        @Test
        @DisplayName("合并阈值是严格 >1：count==1 与 count==2 分属两侧")
        void mergeThresholdIsStrictlyGreaterThanOne() {
            String one = visible(eventLine("SESSION_DONE", 1, "A", "choice", 0, null));
            String two = visible(eventLine("SESSION_DONE", 2, "A", "choice", 0, null));

            assertFalse(one.contains("今天完成"), "count==1 不得走 merged：" + one);
            assertEquals("A 今天完成了 2 次学习会话", two, "count==2 必须走 merged：" + two);
        }

        @Test
        @DisplayName("未知模式：回落原值（信息不丢）；null / 空串回落 (?)")
        void unknownModeFallsBack() {
            assertTrue(eventLine("SESSION_DONE", 1, "A", "custom_mode", 0, null)
                            .contains("custom_mode"),
                    "未知模式必须回落原值 —— 回落空串会让玩家看到「完成了一次会话」，"
                            + "丢失管理员自定义模式的信息");
            assertTrue(eventLine("SESSION_DONE", 1, "A", null, 0, null).contains("(?)"),
                    "null 模式要有可见占位，不能渲染成「完成了一次会话」");
            assertTrue(eventLine("SESSION_DONE", 1, "A", "", 0, null).contains("(?)"),
                    "空串模式同 null");
        }

        @Test
        @DisplayName("ACHIEVEMENT：单条带成就名，合并带 count + 最新成就名")
        void achievementTemplates() {
            String single = eventLine("ACHIEVEMENT", 1, "小明", null, 0, "helpful");
            assertEquals("&f小明 解锁了成就 &6互助之星&f！", single);

            String merged = eventLine("ACHIEVEMENT", 4, "小明", null, 0, "helpful");
            // 同 merged 模板：&e{count} &f 之间夹着颜色码，按剥掉 &x 后的可见文本断言。
            assertTrue(visible(merged).contains("今日解锁了 4 个成就"),
                    visible(merged));
            assertTrue(merged.contains("互助之星"), merged);
        }

        @Test
        @DisplayName("CHECKIN：{streak} 按原值填（0 也照填，签到动态恒有 streak）")
        void checkinTemplate() {
            assertEquals("&f小明 连续签到 &e7 &f天", eventLine("CHECKIN", 1, "小明", null, 7, null));
            assertEquals("&f小明 连续签到 &e0 &f天", eventLine("CHECKIN", 1, "小明", null, 0, null));
        }

        @Test
        @DisplayName("未知 / null 事件类型 → null（调用方跳过整条，不得渲染半句话）")
        void unknownTypeIsNull() {
            assertNull(eventLine("SOMETHING_NEW", 1, "A", null, 0, null),
                    "未知类型必须回 null —— 回空串会让 GUI 渲染出一个只有头像+鼓励数的空条目");
            assertNull(FeedTextFormatter.buildEventLine(null, 1, "A", null, 0, null, "zh", ZH_LOOKUP),
                    "null 类型必须回 null（旧版本行 / 脏数据）");
        }

        /**
         * 夹具自检：本类用到的每个 key 都必须在 {@link #ZH} 里铺过。
         *
         * <p>为什么这条必须有：若夹具漏了一个键，{@link #ZH_LOOKUP} 会返回
         * {@code <missing:key>}，而「渲染结果里不含残留占位符」这类断言<b>照样是绿的</b>
         * —— 因为占位符变成了另一个字符串。于是整套模板断言退化成「拿假串比假串」，
         * 零判别力。这正是本仓反复记录的「假绿」形态之一。
         */
        @Test
        @DisplayName("夹具自检：所有被取的键都已铺（否则模板断言是假绿）")
        void fixtureCoversEveryKeyUsed() {
            String probe = FeedTextFormatter.buildEventLine("SESSION_DONE", 1, "P", "choice", 0,
                    null, "zh", ZH_LOOKUP)
                    + FeedTextFormatter.buildEventLine("SESSION_DONE", 2, "P", "choice", 0,
                    null, "zh", ZH_LOOKUP)
                    + FeedTextFormatter.buildEventLine("ACHIEVEMENT", 1, "P", null, 0, "helpful",
                    "zh", ZH_LOOKUP)
                    + FeedTextFormatter.buildEventLine("ACHIEVEMENT", 2, "P", null, 0, "helpful",
                    "zh", ZH_LOOKUP)
                    + FeedTextFormatter.buildEventLine("CHECKIN", 1, "P", null, 3, null,
                    "zh", ZH_LOOKUP)
                    + FeedTextFormatter.buildDetailLine("SESSION_DONE", true, 1, 2, ZH_LOOKUP)
                    + FeedTextFormatter.buildDetailLine("SESSION_DONE", false, 1, 2, ZH_LOOKUP);
            assertFalse(probe.contains("<missing:"),
                    "夹具缺键 —— 上面所有模板断言都会退化成假绿：" + probe);
        }

        @Test
        @DisplayName("en 夹具同样铺全（EN_LOOKUP 用同一套自检）")
        void fixtureCoversEveryKeyUsedInEnglish() {
            String probe = FeedTextFormatter.buildEventLine("SESSION_DONE", 1, "P", "choice", 0,
                    null, "en", EN_LOOKUP)
                    + FeedTextFormatter.buildEventLine("ACHIEVEMENT", 2, "P", null, 0, "helpful",
                    "en", EN_LOOKUP)
                    + FeedTextFormatter.buildEventLine("CHECKIN", 1, "P", null, 3, null,
                    "en", EN_LOOKUP)
                    + FeedTextFormatter.buildDetailLine("SESSION_DONE", true, 1, 2, EN_LOOKUP)
                    + FeedTextFormatter.buildDetailLine("SESSION_DONE", false, 1, 2, EN_LOOKUP);
            assertFalse(probe.contains("<missing:"), "en 夹具缺键：" + probe);
        }
    }

    // ==================================================================
    // 组 2 —— 数字行与隐私降级
    // ==================================================================

    @Nested
    @DisplayName("组 2 —— 数字行（隐私降级）")
    class DetailLine {

        @Test
        @DisplayName("showResults=true → 答对 X/Y")
        void visibleResultsRenderNumbers() {
            assertEquals("&7答对 &a3&7/&e5",
                    FeedTextFormatter.buildDetailLine("SESSION_DONE", true, 3, 5, ZH_LOOKUP));
        }

        @Test
        @DisplayName("showResults=false → hidden-detail，且绝不出现数字")
        void hiddenResultsNeverLeakNumbers() {
            String line = FeedTextFormatter.buildDetailLine("SESSION_DONE", false, 3, 5, ZH_LOOKUP);

            assertEquals("&7完成了一次学习会话", line);
            assertFalse(line.contains("3"), "隐藏态不得泄露答对数：" + line);
            assertFalse(line.contains("5"), "隐藏态不得泄露题数：" + line);
        }

        @Test
        @DisplayName("隐藏态必须走 hidden-detail 键，而不是「取了 session-detail 再丢掉」")
        void hiddenPathUsesItsOwnKey() {
            String hidden = FeedTextFormatter.buildDetailLine("SESSION_DONE", false, 3, 5,
                    MISSING_ALL);
            assertEquals("<missing:square.feed.hidden-detail>", hidden,
                    "隐藏态必须只取 hidden-detail —— 若这里取到 session-detail 的键，"
                            + "说明实现是「先按可见模板渲染再覆盖」，那种形状一旦被别的调用方"
                            + "复用就会真泄露");
        }

        @Test
        @DisplayName("非 SESSION_DONE 事件没有数字行（ACHIEVEMENT / CHECKIN 回 null）")
        void onlySessionHasDetailLine() {
            assertNull(FeedTextFormatter.buildDetailLine("ACHIEVEMENT", true, 1, 2, ZH_LOOKUP));
            assertNull(FeedTextFormatter.buildDetailLine("CHECKIN", true, 1, 2, ZH_LOOKUP));
            assertNull(FeedTextFormatter.buildDetailLine(null, true, 1, 2, ZH_LOOKUP),
                    "null 类型回 null（不要因为 equals 的写法差异而 NPE）");
        }
    }

    // ==================================================================
    // 组 3 —— 成就名渲染
    // ==================================================================

    @Nested
    @DisplayName("组 3 —— 成就名 / 模式键映射")
    class Names {

        @Test
        @DisplayName("成就名按 viewer 语言渲染（zh / en 各一份）")
        void achievementNameFollowsViewerLanguage() {
            assertEquals("互助之星", FeedTextFormatter.resolveAchievementName("helpful", "zh"));
            assertEquals("Helping Hand", FeedTextFormatter.resolveAchievementName("helpful", "en"));
            // 大小写口径：Achievement.getDisplayName 是 "zh".equalsIgnoreCase(language)，
            // 故 "ZH" 仍走中文；而 "cn" / "tw" 这类非 "zh" 值一律走 en（既有口径，本层不改）
            assertEquals("互助之星", FeedTextFormatter.resolveAchievementName("helpful", "ZH"),
                    "getDisplayName 用 equalsIgnoreCase(\"zh\")，大写 ZH 仍走中文 —— 钉住以免漂移");
            assertEquals("Helping Hand", FeedTextFormatter.resolveAchievementName("helpful", "cn"),
                    "非 zh 的语言标识走 en 分支（既有口径；本层不做语言归一化）");
        }

        @Test
        @DisplayName("未知成就 id 回落 id 原值；null / 空串回落 ?")
        void unknownAchievementIdFallsBack() {
            assertEquals("no_such_achievement",
                    FeedTextFormatter.resolveAchievementName("no_such_achievement", "zh"),
                    "查不到定义时要回落 id 原值 —— 玩家至少能看到「哪个成就」（也为事后定位）");
            assertEquals("?", FeedTextFormatter.resolveAchievementName(null, "zh"));
            assertEquals("?", FeedTextFormatter.resolveAchievementName("", "zh"));
        }

        @Test
        @DisplayName("模式键映射：四个已知模式，其余（含 null）回 null")
        void modeMessageKeyMapping() {
            assertEquals("word.mode-spelling", FeedTextFormatter.modeMessageKey("spelling"));
            assertEquals("word.mode-choice", FeedTextFormatter.modeMessageKey("choice"));
            assertEquals("word.mode-fill_blank", FeedTextFormatter.modeMessageKey("fill_blank"));
            assertEquals("word.mode-review", FeedTextFormatter.modeMessageKey("review"));
            assertNull(FeedTextFormatter.modeMessageKey(null));
            assertNull(FeedTextFormatter.modeMessageKey("SPELLING"),
                    "大小写敏感（会话 mode 由 WordModes 归一后落库，全小写）—— 若将来改为忽略大小写，"
                            + "本断言会红，提醒同步 WordModes 口径");
        }
    }

    // ==================================================================
    // 组 4 —— 颜色码注入（D8 修复后的回归 + 防过度修复护栏）
    // ==================================================================

    @Nested
    @DisplayName("组 4 —— 颜色码注入：占位符剥色，模板配色保留")
    class ColorCodeInjection {

        /**
         * <b>链路证明（转换前）</b>：本层输出仍是未转义的 {@code &} 形态，唯一调用方
         * {@code EnglishSquareGUI.renderFeedEntries} 紧接着对它执行
         * {@code ChatColor.translateAlternateColorCodes('&', eventLine)}。
         *
         * <p>D8 修复把清洗放在本层（拼接前），所以这条现在断言的是
         * 「注入物到不了转码器」：同一个 payload 经本层之后已经不含 {@code &}，
         * 下游转码不产生任何 {@code §}。这条同时钉住「转码这一步仍然存在」——
         * 否则洗干净只是因为下游不再转码，与本修复无关（假绿形态之一）。
         */
        @Test
        @DisplayName("&c&l 进本层 → 输出不含 & 码 → 转码后不出现 §（注入不再可达）")
        void ampersandColorCodeIsStrippedBeforeTranslation() {
            String line = eventLine("SESSION_DONE", 1, "&c&lAdmin", "choice", 0, null);

            assertFalse(line.contains("&c") || line.contains("&l"),
                    "本层必须已剥离颜色/格式码：" + line);
            assertTrue(line.contains("Admin"), "名字的可见部分必须保留：" + line);

            String rendered = ChatColor.translateAlternateColorCodes('&', line);
            // 模板自身仍有 &f/&e，所以 § 必然出现 —— 关键是「§ 后面不能跟着 c/l」，
            // 即攻击者写的那两个码没有生效。
            assertFalse(rendered.contains("§c") && rendered.contains("§lAdmin"),
                    "攻击者写的 &c&l 不得变成 §c§l：" + rendered);
            String visible = ChatColor.stripColor(rendered);
            assertTrue(visible.contains("Admin"), visible);
        }

        /**
         * 覆盖<b>全部四条事件行模板</b>（含两个 merged 变体）：每一条的 {@code {player}}
         * 都必须已剥色。
         *
         * <p>为什么不能只测一条：五处 {@code .replace("{player}", ...)} 是五个独立调用点，
         * 单独一个用例只能覆盖它自己走到的那一条分支 —— 漏改其中一处（例如只在
         * SESSION_DONE 上改了、CHECKIN 忘了）时，其余分支的注入面会静默留存。
         * 变异验证证实过这一点：把 CHECKIN 那一处改回裸 {@code playerName}，
         * 除本循环外只有一条 CJK 用例会红。
         */
        @Test
        @DisplayName("四条事件行模板的 {player} 全部已剥色（逐分支覆盖，不漏任一处）")
        void everyEventTemplateStripsPlayerName() {
            String payload = "&c&lAdmin";
            String[] lines = {
                    eventLine("SESSION_DONE", 1, payload, "choice", 0, null),
                    eventLine("SESSION_DONE", 3, payload, "choice", 0, null),
                    eventLine("ACHIEVEMENT", 1, payload, null, 0, "helpful"),
                    eventLine("ACHIEVEMENT", 3, payload, null, 0, "helpful"),
                    eventLine("CHECKIN", 1, payload, null, 7, null),
            };
            String[] branch = {"session", "session-merged", "achievement",
                    "achievement-merged", "checkin"};
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                assertNotNull(line, branch[i] + " 渲染结果不得为 null");
                assertFalse(line.contains("&c") || line.contains("&l"),
                        branch[i] + " 分支的 {player} 仍在裸拼颜色码 —— 五处 replace "
                                + "必须全部走 safePlayer：" + line);
                assertTrue(line.contains("Admin"),
                        branch[i] + " 分支必须保留名字的可见部分：" + line);
            }
        }

        @Test
        @DisplayName("模板自身配色必须保留（防修复过度：整行剥色会让广场界面失色）")
        void templateOwnColorCodesSurvive() {
            String line = eventLine("SESSION_DONE", 1, "小明", "choice", 0, null);

            assertEquals("&f小明 完成了一次&e选择题&f会话", line,
                    "本层只剥 {player} 的取值，模板里的 &f/&e 必须原样保留 —— 若整行 strip，"
                            + "广场动态会全部失色");
            assertFalse(eventLine("CHECKIN", 1, "小明", null, 7, null).isEmpty());
            assertEquals("&f小明 连续签到 &e7 &f天", eventLine("CHECKIN", 1, "小明", null, 7, null),
                    "CHECKIN 模板配色同样不得被剥");
        }

        @Test
        @DisplayName("中文输入同样剥净（任何 & + 颜色/格式字符都不得生效）")
        void injectionWorksWithCjkPayloadBlocked() {
            String line = eventLine("CHECKIN", 1, "&4&l系统公告&r", null, 3, null);

            assertFalse(line.contains("&4") || line.contains("&l") || line.contains("&r"),
                    "颜色码与 reset 码都必须剥掉：" + line);
            String rendered = ChatColor.translateAlternateColorCodes('&', line);
            String visible = ChatColor.stripColor(rendered);
            assertTrue(visible.contains("系统公告"),
                    "攻击者写的正文仍作为普通文字出现（本修复只剥码，不做关键词过滤）：" + visible);
            assertFalse(rendered.contains("§4"), "攻击者的 &4 不得成真：" + rendered);
        }

        @Test
        @DisplayName("超长 playerName 不截断（DB 列宽 VARCHAR(32) 之外还有「读侧无上限」这一段）")
        void playerNameIsNotTruncated() {
            String name = "x".repeat(120);
            String line = eventLine("SESSION_DONE", 1, name, "choice", 0, null);

            assertTrue(line.contains(name),
                    "本层不做长度截断；写侧 cachePlayerName 只截 32 字符，读侧（本层）无上限。"
                            + "若将来补了截断，本用例会红，提醒同步「玩家看到多少」的预期");
        }

        /**
         * 期望行为（D8 已修）：与 {@code ChatAnswerListener.sanitizeBroadcastName} 同款 ——
         * 颜色码必须在本层剥离，不得进入下游 {@code translateAlternateColorCodes}。
         *
         * <p>修法落在 {@code FeedTextFormatter.sanitizePlayerText}（本层是五处
         * {@code {player}} 的唯一拼接点，比在调用方各加一道更难漏改）。改用自有正则而非
         * {@code ChatColor.stripColor}：<b>实测</b>后者对 {@code &} 前缀完全无效
         * （{@code stripColor("&c&lAdmin")} 原样返回），只认已转义的 {@code §}，
         * 而本层输出正是未转义的 {@code &} 形态。
         */
        @Test
        @DisplayName("playerName 里的颜色码被剥离，不得进入转码器")
        void playerNameColorCodesShouldBeStripped() {
            String line = eventLine("SESSION_DONE", 1, "&c&lAdmin", "choice", 0, null);

            assertFalse(line.contains("&c"),
                    "playerName 必须在拼接前剥离颜色码（否则会原样进入 translateAlternateColorCodes）"
                            + "：" + line);
            assertTrue(line.contains("Admin"), "名字的可见部分必须保留：" + line);
        }

        /**
         * <b>链路证明（结构，D8 修复后翻转）</b>：清洗在本层、落地点在 GUI。
         *
         * <p>为什么必须单独一条：只测「本层输出里不含 &c」证明不了<b>防线装对位置</b>——
         * 万一调用方改成了先 translate 再无论什么都不管，本层洗干净也没意义；
         * 反过来，若有人把清洗挪到调用方而本层又开了一个新占位符拼接点，
         * 只测本层就漏了。这条把两端钉在一起：本层必须有自己的净化器，
         * GUI 必须仍然对这条输出做转码（否则「干净」只是因为没人转码，是假绿）。
         *
         * <p>双向基线（照 {@code MainThreadIoContractTest.KNOWN_MAIN_THREAD_IO} 先例）：
         * 任何一端改动都会红并说明该同步什么。
         */
        @Test
        @DisplayName("LINT：本层必须有 sanitizePlayerText，且 GUI 仍对这条输出做 translate")
        void sanitizerAndSinkAreBothPinned() throws Exception {
            String formatter = sourceOf("src/main/java/com/letslearnenglish/minecraftplugin/core/"
                    + "social/FeedTextFormatter.java");
            assertTrue(formatter.contains("sanitizePlayerText"),
                    "本层必须有名字净化器（D8）—— 若被删/改名，注入面就回来了");
            assertTrue(formatter.contains(".replace(\"{player}\", safePlayer)"),
                    "五处 {player} 必须统一替换为已净化的 safePlayer，而不是裸 playerName");
            assertFalse(formatter.contains(".replace(\"{player}\", playerName)"),
                    "仍有裸 playerName 进模板 —— 这就是 D8 的原始缺陷形态");
            assertFalse(formatter.contains("ChatColor.stripColor("),
                    "不要依赖 ChatColor.stripColor：实测它对 & 前缀无效（只认 §），"
                            + "用了会让「已清洗」变成假象");
            assertFalse(formatter.contains("import org.bukkit.ChatColor"),
                    "本层不依赖 Bukkit（类注释承诺「不碰 Bukkit」）—— 净化用纯正则实现");

            String gui = sourceOf("src/main/java/com/letslearnenglish/minecraftplugin/gui/"
                    + "EnglishSquareGUI.java");
            assertTrue(gui.contains("ChatColor.translateAlternateColorCodes('&', eventLine)"),
                    "GUI 必须把 FeedTextFormatter 的事件行交给 translateAlternateColorCodes —— "
                            + "这是注入落地点，找不到说明渲染路径改了，请同步本 lint");
            assertTrue(gui.contains("FeedTextFormatter.buildEventLine("),
                    "GUI 必须调 buildEventLine 取事件行（否则本 lint 的两端不再是同一条链）");
            // 头像名同样转码，且同样来自 player_data.player_name —— 必须同款净化
            assertTrue(gui.contains("FeedTextFormatter.sanitizePlayerText(item.authorName())"),
                    "头像 displayName 也把 authorName 交给转码器（第二个落地点），"
                            + "必须同样走 sanitizePlayerText；裸 item.authorName() 会让 D8 只修一半");
        }

        /**
         * 净化器的行为矩阵：覆盖 & 颜色码 / 格式码 / hex / 已转义 § / 换行 / 空值。
         *
         * <p>逐条钉住是因为每一类都有各自的绕过路径：只剥 {@code &0-f} 会漏
         * {@code &k}（乱码）与 {@code &l}（粗体）；只剥单字符码会把 {@code &#ff0000}
         * 的六位数字留成可见残渣；不剥 {@code §} 形态则「已转义」的输入直接穿透。
         */
        @Test
        @DisplayName("sanitizePlayerText：& 码 / 格式码 / hex / § / 换行 / 空值逐类剥净")
        void sanitizerCoversEveryCodeFlavour() {
            assertEquals("Admin", FeedTextFormatter.sanitizePlayerText("&cAdmin"),
                    "& 颜色码必须剥");
            assertEquals("Admin", FeedTextFormatter.sanitizePlayerText("&c&lAdmin"),
                    "格式码（&l 粗体）必须一起剥 —— 只剥颜色的话名字仍能变粗");
            assertEquals("AAAAAAAA", FeedTextFormatter.sanitizePlayerText("&kAAAAAAAA"),
                    "&k 乱码码必须剥（否则能拼出乱码条伪装系统消息）");
            assertEquals("strike", FeedTextFormatter.sanitizePlayerText("&mstrike"));
            assertEquals("Reset", FeedTextFormatter.sanitizePlayerText("&rReset"));
            assertEquals("Admin", FeedTextFormatter.sanitizePlayerText("§cAdmin"),
                    "已转义 § 形态同样要剥（DB 里可能存的就是 §）");
            assertEquals("hex", FeedTextFormatter.sanitizePlayerText("&#ff0000hex"),
                    "hex 码必须整段剥，不能把 #ff0000 留成可见文本");
            assertEquals("hex2", FeedTextFormatter.sanitizePlayerText("&x&f&f&0&0&0&0hex2"),
                    "展开式 hex 必须整段剥，否则会留下 &f&f&0&0&0&0 残渣再被下游点亮");
            assertEquals("AB", FeedTextFormatter.sanitizePlayerText("A\nB"),
                    "换行会把一条 lore 劈成两行，必须收掉");
            assertEquals("A & B", FeedTextFormatter.sanitizePlayerText("A & B"),
                    "不成码的 & 保持原样（不做过度清洗，正常名字不受影响）");
            assertEquals("&z", FeedTextFormatter.sanitizePlayerText("&z"),
                    "&z 不是颜色码，原样保留（剥不剥都不影响渲染）");
            assertEquals("?", FeedTextFormatter.sanitizePlayerText(null),
                    "null 回落占位（与 ChatAnswerListener 同口径）");
            assertEquals("?", FeedTextFormatter.sanitizePlayerText("&c&l"),
                    "名字全是颜色码 → 剥完为空，必须有可见占位而不是空串");
        }
    }

    // ==================================================================
    // 组 5 —— 真实语言文件覆盖（i18n 缺口 = 玩家看到 [?key]）
    // ==================================================================

    @Nested
    @DisplayName("组 5 —— 本类硬编码的键在 zh/en 两份语言文件里都真实存在")
    class RealMessageFiles {

        /** 本类通过 {@code t.get(...)} 取的全部键（新增键必须同步本数组）。 */
        private static final String[] KEYS = {
                "square.feed.session",
                "square.feed.session-merged",
                "square.feed.session-detail",
                "square.feed.achievement",
                "square.feed.achievement-merged",
                "square.feed.checkin",
                "square.feed.hidden-detail",
                "word.mode-spelling",
                "word.mode-choice",
                "word.mode-fill_blank",
                "word.mode-review",
        };

        @Test
        @DisplayName("11 个键在 zh.yml / en.yml 里按点路径都取得到，且保留各自占位符")
        void allKeysExistInBothLanguages() throws Exception {
            for (String lang : new String[] {"zh", "en"}) {
                YamlConfiguration yml = loadMessages(lang);
                for (String key : KEYS) {
                    String value = yml.getString(key);
                    assertNotNull(value, lang + ".yml 里取不到 " + key
                            + " 喵 —— 玩家会在动态流上看到 [? " + key + "] 而不是一句话。"
                            + "常见原因：键缩进了别的段内部，YAML 会解析成 <该段>." + key);
                    assertFalse(value.isBlank(), lang + " 的 " + key + " 是空白值");
                }
                // 占位符与代码里的 replace 必须对上（少一个就是玩家看到 {count} 字面量）
                assertTrue(yml.getString("square.feed.session-merged").contains("{count}"),
                        lang + " 的 session-merged 缺 {count}");
                assertTrue(yml.getString("square.feed.session-merged").contains("{player}"),
                        lang + " 的 session-merged 缺 {player}");
                assertTrue(yml.getString("square.feed.session").contains("{mode}"),
                        lang + " 的 session 缺 {mode}");
                assertTrue(yml.getString("square.feed.achievement-merged")
                                .contains("{achievement}"),
                        lang + " 的 achievement-merged 缺 {achievement}");
                assertTrue(yml.getString("square.feed.checkin").contains("{streak}"),
                        lang + " 的 checkin 缺 {streak}");
                assertTrue(yml.getString("square.feed.session-detail").contains("{correct}")
                                && yml.getString("square.feed.session-detail").contains("{total}"),
                        lang + " 的 session-detail 缺 {correct}/{total}");
            }
        }

        @Test
        @DisplayName("用真实语言文件渲染一次：无残留占位符、无 [?key]")
        void rendersCleanlyAgainstRealFiles() throws Exception {
            for (String lang : new String[] {"zh", "en"}) {
                YamlConfiguration yml = loadMessages(lang);
                FeedTextFormatter.KeyLookup lookup = yml::getString;
                String[] lines = {
                        FeedTextFormatter.buildEventLine("SESSION_DONE", 1, "P", "spelling", 0,
                                null, lang, lookup),
                        FeedTextFormatter.buildEventLine("SESSION_DONE", 3, "P", "spelling", 0,
                                null, lang, lookup),
                        FeedTextFormatter.buildEventLine("ACHIEVEMENT", 2, "P", null, 0,
                                "helpful", lang, lookup),
                        FeedTextFormatter.buildEventLine("CHECKIN", 1, "P", null, 9, null,
                                lang, lookup),
                        FeedTextFormatter.buildDetailLine("SESSION_DONE", true, 1, 2, lookup),
                        FeedTextFormatter.buildDetailLine("SESSION_DONE", false, 1, 2, lookup),
                };
                for (String line : lines) {
                    assertNotNull(line, lang + "：渲染结果不该为 null");
                    assertFalse(line.contains("{"), lang + " 渲染残留占位符：" + line);
                    assertFalse(line.contains("[?"), lang + " 渲染出现缺键占位符：" + line);
                }
            }
        }

        private YamlConfiguration loadMessages(String lang) throws Exception {
            Path p = repoRoot().resolve("src/main/resources/messages/" + lang + ".yml");
            YamlConfiguration yml = new YamlConfiguration();
            // loadFromString 而非 loadConfiguration(File)：后者解析失败时走 Bukkit logger，
            // 纯 JVM 下 Bukkit.server 为 null → 「YAML 写坏了」会表现成 NPE 而不是断言失败。
            yml.loadFromString(Files.readString(p, StandardCharsets.UTF_8));
            return yml;
        }
    }

    // ==================================================================
    // 脚手架
    // ==================================================================

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("src/main/java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（向上 6 层都没有 src/main/java）");
    }

    private static String sourceOf(String relative) throws Exception {
        Path p = repoRoot().resolve(relative);
        assertTrue(Files.isRegularFile(p), "被 lint 的源文件不存在：" + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
