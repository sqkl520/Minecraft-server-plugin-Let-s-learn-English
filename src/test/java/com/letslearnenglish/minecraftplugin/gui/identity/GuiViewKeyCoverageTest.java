package com.letslearnenglish.minecraftplugin.gui.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link GuiViewKey} 与 {@code Bukkit.createInventory(...)} 的<b>源码级 lint</b>（不是行为测试）。
 *
 * <p>为什么需要它：R5 之后插件的 GUI 身份完全由 {@link PluginGuiHolder} 回答 —— holder 通过
 * {@code Bukkit.createInventory(holder, size, title)} 挂在 top inventory 上。新增一个界面时
 * 若忘了挂 holder，编译通过、所有行为测试全绿，玩家侧却是「整个界面点不动、物品能被拿走」，
 * 只有人工点一遍才发现。这条链路没有任何运行时观测点能在单测里复现（需要 Bukkit 服务端），
 * 所以唯一的自动防线就是这里：直接读源码，核对
 * 「{@code createInventory} 的调用数 == {@code PluginGuiHolder.of} 的调用数」。
 *
 * <p><b>为什么要先剥注释</b>：{@code gui/identity/} 下三个类的 Javadoc 里各含一处
 * {@code Bukkit.createInventory(...)} 字面量（说明性文字）。裸 grep 会数出 24 而不是 21，
 * 因此所有统计都必须跑在 {@link #stripComments(String)} 的产物上；该方法同时把字符串/字符
 * 字面量抹成空格，避免标题里的 {@code {page}} 之类字符干扰括号配对。
 *
 * <p><b>为什么不用「相邻行」匹配</b>：21 处调用里既有同行内联（{@code DuelGUI}、
 * {@code WordBankSelectGUI}），也有 holder 在下一行、甚至跨三行的（{@code EnglishSquareGUI}
 * 的 {@code SquarePage}）。按行匹配必然漏判，故改为整文件读入后做两次独立的全文件计数，
 * 只比较总数与 key 集合。
 *
 * <p><b>黄金映射表（{@link #EXPECTED}）</b>：早期版本用
 * {@code assertEquals(GuiViewKey.values().length, totalCreate)} 断言总量 —— 两边都取自
 * 实现，属于伪常量：把 {@code DuelGUI.java} 与 {@code GuiViewKey.DUEL} 一起删掉，三条断言
 * 照样全绿。现在改成「文件 → 期望 key」的显式表（18 个 GUI 类、21 个 key），且表里直接
 * 引用枚举常量：删常量会编译失败，删文件会被「表内文件不存在」抓到，给某个 createInventory
 * 换 key 会被「该文件的 key 序列不匹配」抓到。之所以要求<b>顺序</b>也一致（{@code List}
 * 而非 {@code Set}），是因为 {@code ChatHistoryGUI} 一文件挂两个 key，单纯比集合抓不到
 * {@code CHAT_HISTORY_LIST ↔ CHAT_HISTORY_SESSION} 互换。若只是调整了方法在文件里的先后，
 * 请同步本表。
 *
 * <p>{@link StructuralLints} 一组的断言是<b>结构断言，不是行为断言</b>：它们检查的是
 * 源码中语句的先后顺序，运行时不可观测（见该组内说明）。
 */
class GuiViewKeyCoverageTest {

    // ===== 路径 =====

    // ===== 正则 =====

    /** 任意 {@code Bukkit.createInventory(} 调用（含跨行，{@code \s*} 覆盖换行）。 */
    private static final Pattern CREATE_INV = Pattern.compile("Bukkit\\.createInventory\\s*\\(");

    /** 任意 {@code PluginGuiHolder.of(} 调用（一参 / 两参形式都匹配）。 */
    private static final Pattern HOLDER_OF = Pattern.compile("PluginGuiHolder\\.of\\s*\\(");

    /** holder 构造点上的 key 名：{@code PluginGuiHolder.of(GuiViewKey.XXX}（跨行也匹配）。 */
    private static final Pattern HOLDER_OF_KEY =
            Pattern.compile("PluginGuiHolder\\.of\\s*\\(\\s*GuiViewKey\\.([A-Z][A-Z0-9_]*)");

    /** 源码中任意 {@code GuiViewKey.XXX} 引用（诊断用，不参与主断言）。 */
    private static final Pattern KEY_REF = Pattern.compile("GuiViewKey\\.([A-Z][A-Z0-9_]*)");

    /** {@code bumpWriteSeq(playerId)} 调用。 */
    private static final Pattern BUMP_SEQ = Pattern.compile("bumpWriteSeq\\s*\\(\\s*playerId\\s*\\)");

    // ===== 黄金映射表 =====

    /**
     * 「GUI 源文件 → 该文件里 {@code createInventory} 依次挂的 key」的显式期望表。
     *
     * <p>这张表是<b>手写的事实</b>，不是从实现里算出来的 —— 它是「防止整类被删掉但两边同时
     * 归零」的唯一手段。表的每一项都直接引用 {@link GuiViewKey} 常量，所以：
     * <ul>
     *   <li>删掉某个枚举常量 → 本文件<b>编译失败</b>（比运行时红灯更早）；</li>
     *   <li>删掉某个 GUI 类 → {@link #everyGuiFileMatchesGoldenTable()} 报「表内文件不存在」；</li>
     *   <li>某个类忘了挂 holder → 该文件的 key 序列少一项；</li>
     *   <li>把两个 key 互换（实测存活的变异）→ 序列不匹配。</li>
     * </ul>
     *
     * <p>顺序敏感是刻意的：{@code ChatHistoryGUI} / {@code EnglishSquareGUI} / {@code ProfileGUI}
     * 各自一文件两 key，只比集合抓不到互换。表的顺序与源码中 {@code createInventory} 的出现
     * 顺序一致（{@code ChatHistoryGUI}：先 list 后 session；{@code EnglishSquareGUI}：先主广场
     * 后隐身页；{@code ProfileGUI}：先主页后设置）。仅仅调整了方法在文件内的先后时，
     * 请同步本表 —— 这是本表唯一的维护成本。
     */
    private static final Map<String, List<GuiViewKey>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("AchievementWallGUI.java", List.of(GuiViewKey.ACHIEVEMENT_WALL));
        EXPECTED.put("ChallengeCompleteGUI.java", List.of(GuiViewKey.CHALLENGE_COMPLETE));
        EXPECTED.put("ChatHistoryGUI.java",
                List.of(GuiViewKey.CHAT_HISTORY_LIST, GuiViewKey.CHAT_HISTORY_SESSION));
        EXPECTED.put("ClassBoardGUI.java", List.of(GuiViewKey.CLASS_BOARD));
        EXPECTED.put("ConfirmationGUI.java", List.of(GuiViewKey.CONFIRMATION));
        EXPECTED.put("DailyChallengeGUI.java", List.of(GuiViewKey.DAILY_CHALLENGE));
        EXPECTED.put("DuelChallengeGUI.java", List.of(GuiViewKey.DUEL_CHALLENGE));
        EXPECTED.put("DuelGUI.java", List.of(GuiViewKey.DUEL));
        EXPECTED.put("EnglishSquareGUI.java",
                List.of(GuiViewKey.SQUARE, GuiViewKey.SQUARE_HIDDEN));
        EXPECTED.put("LeaderboardGUI.java", List.of(GuiViewKey.LEADERBOARD));
        EXPECTED.put("LearningCalendarGUI.java", List.of(GuiViewKey.LEARNING_CALENDAR));
        EXPECTED.put("MainMenu.java", List.of(GuiViewKey.MAIN_MENU));
        EXPECTED.put("PersonalScenarioGUI.java", List.of(GuiViewKey.PERSONAL_SCENARIO));
        EXPECTED.put("ProfileGUI.java",
                List.of(GuiViewKey.PROFILE, GuiViewKey.PROFILE_SETTINGS));
        EXPECTED.put("ScenarioSelectGUI.java", List.of(GuiViewKey.SCENARIO_SELECT));
        EXPECTED.put("WordBankSelectGUI.java", List.of(GuiViewKey.WORD_BANK));
        EXPECTED.put("WordLearningGUI.java", List.of(GuiViewKey.WORD_LEARNING));
        EXPECTED.put("WrongBookGUI.java", List.of(GuiViewKey.WRONG_BOOK));
    }

    // ===== 用例组 1：源码覆盖 =====

    @Nested
    @DisplayName("SourceCoverage（源码覆盖 lint）")
    class SourceCoverage {

        /**
         * 最高价值用例：<b>整个源码树</b>下每个文件都必须满足
         * {@code count(createInventory) == count(PluginGuiHolder.of)}。
         *
         * <p>扫描根是全树而不是 {@code gui/}：红队实测在 {@code listener/} 下新增
         * {@code Bukkit.createInventory(null, 27, "x")} 时，只扫 {@code gui/} 的 lint 全绿 ——
         * 这是最危险的一类回归（界面点不动、物品可被拿走，门禁却报告正常）。
         *
         * <p>同时充当「剥注释是否正确」的自检点：{@code identity/} 三个文件剥注释后
         * {@code createInventory} 计数必须为 0（原文里的 3 处全部位于 Javadoc）。
         */
        @Test
        @DisplayName("每处 createInventory 都挂了 holder（全源码树）")
        void everyCreateInventoryCallCarriesAHolder() {
            Path identityDir = guiDir().resolve("identity");
            List<String> problems = new ArrayList<>();
            int totalCreate = 0;
            int totalHolder = 0;

            for (Path file : allJavaFiles()) {
                String stripped = stripComments(readFile(file));
                int createCount = countMatches(stripped, CREATE_INV);
                int holderCount = countMatches(stripped, HOLDER_OF);
                totalCreate += createCount;
                totalHolder += holderCount;

                if (createCount != holderCount) {
                    String hint = createCount > holderCount
                            ? "（有界面漏挂 holder：编译能过、界面点不动）"
                            : "（holder 比 createInventory 多：可能重复挂载或残留调用）";
                    problems.add(relative(file) + "：Bukkit.createInventory=" + createCount
                            + "，PluginGuiHolder.of=" + holderCount + hint);
                }

                // 自检点：identity 包内只有 Javadoc 提到 createInventory，剥注释后必须为 0。
                if (isInside(file, identityDir)) {
                    assertEquals(0, createCount,
                            relative(file) + " 剥注释后仍统计到 " + createCount
                                    + " 处 Bukkit.createInventory —— stripComments 把 Javadoc "
                                    + "误当成代码，或该包内真的出现了建界面调用（identity 包不该建界面）喵。");
                }
            }

            assertTrue(problems.isEmpty(),
                    "以下文件的 createInventory 与 PluginGuiHolder.of 数量不一致，"
                            + "意味着有界面没挂结构身份（holder）：\n  "
                            + String.join("\n  ", problems));

            assertEquals(totalCreate, totalHolder,
                    "全源码树汇总不一致：createInventory=" + totalCreate
                            + "，PluginGuiHolder.of=" + totalHolder + " 喵。");
        }

        /**
         * {@code gui/} <b>之外</b>不得出现任何 {@code Bukkit.createInventory}。
         *
         * <p>为什么单独钉一条：{@code everyCreateInventoryCallCarriesAHolder} 只比较「同文件内
         * 两个计数相等」。若有人在 {@code listener/} 里同时写一行 {@code createInventory(null,...)}
         * 和一行毫无意义的 {@code PluginGuiHolder.of(key)}（比如复制粘贴残留），两个计数抵消，
         * 那条 lint 依然全绿。这里从「归属」维度再钉一次：建界面的代码只允许住在 {@code gui/}。
         *
         * <p>确实需要在别处建界面时（例如 core 层按业务逻辑开屏），请先拆出一个
         * {@code gui/} 下的 GUI 类再调用 —— 而不是放宽本断言。
         */
        @Test
        @DisplayName("gui/ 之外没有裸 createInventory")
        void noCreateInventoryOutsideGuiTree() {
            Path gui = guiDir();
            List<String> offenders = new ArrayList<>();
            for (Path file : allJavaFiles()) {
                if (isInside(file, gui)) {
                    continue;
                }
                int count = countMatches(stripComments(readFile(file)), CREATE_INV);
                if (count > 0) {
                    offenders.add(relative(file) + "：" + count + " 处");
                }
            }

            assertTrue(offenders.isEmpty(),
                    "gui/ 之外出现了 Bukkit.createInventory，这些界面不在黄金表看守范围内，"
                            + "极可能漏挂 PluginGuiHolder（玩家侧表现为界面点不动、物品能被拿走）喵：\n  "
                            + String.join("\n  ", offenders)
                            + "\n正确做法：把建界面逻辑搬进 gui/ 下的 GUI 类并挂 holder。");
        }

        /**
         * <b>核心防线</b>：每个 GUI 源文件挂的 key 必须<b>逐项等于</b> {@link #EXPECTED} 的
         * 黄金表（顺序敏感）。
         *
         * <p>为什么不再断言「总量 == 枚举常量数」：两边都取自实现，删掉一个文件 + 一个常量
         * 后同时减一，断言照样通过（伪常量）。黄金表是手写事实，删常量会先编译失败。
         */
        @Test
        @DisplayName("每个 GUI 文件挂的 key 精确等于黄金映射表")
        void everyGuiFileMatchesGoldenTable() {
            Map<String, List<GuiViewKey>> actualByFile = new LinkedHashMap<>();
            for (Path file : allJavaFiles()) {
                List<GuiViewKey> keys = extractHolderKeyList(stripComments(readFile(file)));
                if (!keys.isEmpty()) {
                    actualByFile.put(file.getFileName().toString(), keys);
                }
            }

            List<String> problems = new ArrayList<>();

            // 1) 表内每个文件都必须存在，且 key 序列完全一致。
            EXPECTED.forEach((fileName, expectedKeys) -> {
                List<GuiViewKey> actual = actualByFile.get(fileName);
                if (actual == null) {
                    problems.add(fileName + "：黄金表要求它挂 " + expectedKeys
                            + "，但该文件不存在或一个 holder 都没挂"
                            + "（文件被删/改名，或漏挂了 createInventory 的 holder）");
                } else if (!actual.equals(expectedKeys)) {
                    problems.add(fileName + "：期望 " + expectedKeys + "，实际 " + actual
                            + describeKeyDiff(expectedKeys, actual));
                }
            });

            // 2) 反过来：挂 holder 的文件必须都在表里 —— 新增界面不能悄悄绕过黄金表。
            for (String fileName : actualByFile.keySet()) {
                if (!EXPECTED.containsKey(fileName)) {
                    problems.add(fileName + "：该文件挂了 " + actualByFile.get(fileName)
                            + "，但不在黄金表里 —— 新增/改名的界面必须同步 EXPECTED"
                            + "（否则身份覆盖就有一块无人看守的盲区）");
                }
            }

            assertTrue(problems.isEmpty(),
                    "GUI 身份与黄金映射表不一致（黄金表是本测试手写的唯一期望源，"
                            + "不是从实现推出来的）喵：\n  " + String.join("\n  ", problems)
                            + "\n若这是有意的界面增删，请同步 EXPECTED 与 GuiViewKey；"
                            + "若是忘记挂 holder，请在该处补 PluginGuiHolder.of(...)。");

            // 表自身的一致性：总 key 数必须等于枚举常量数，且每个常量恰好出现一次。
            // 这条的价值在于「表被改漏」时立刻红，而不是等实现漂移。
            List<GuiViewKey> tableKeys = new ArrayList<>();
            EXPECTED.values().forEach(tableKeys::addAll);
            assertEquals(GuiViewKey.values().length, tableKeys.size(),
                    "黄金表覆盖了 " + tableKeys.size() + " 个 key，但 GuiViewKey 有 "
                            + GuiViewKey.values().length + " 个常量喵 —— 表本身不完整。");
            assertEquals(new TreeSet<>(List.of(GuiViewKey.values())), new TreeSet<>(tableKeys),
                    "黄金表的 key 集合与 GuiViewKey 常量集合不相等（有重复或遗漏）喵。");
        }

        /** 21 处 holder 的 key 必须两两不同，否则两个屏幕会共享身份。 */
        @Test
        @DisplayName("所有 holder key 两两不同")
        void allHolderKeysAreDistinct() {
            Map<String, List<String>> occurrences = new LinkedHashMap<>();
            for (Path file : allJavaFiles()) {
                String stripped = stripComments(readFile(file));
                for (String key : extractHolderKeys(stripped)) {
                    occurrences.computeIfAbsent(key, k -> new ArrayList<>()).add(relative(file));
                }
            }

            List<String> duplicates = new ArrayList<>();
            occurrences.forEach((key, places) -> {
                if (places.size() > 1) {
                    duplicates.add("GuiViewKey." + key + " 出现 " + places.size() + " 次："
                            + String.join("、", places));
                }
            });

            assertTrue(duplicates.isEmpty(),
                    "存在重复的 holder key（两个屏幕共用同一身份会让点击分发互相串台）喵：\n  "
                            + String.join("\n  ", duplicates));

            assertTrue(occurrences.size() >= GuiViewKey.values().length,
                    "只收集到 " + occurrences.size() + " 个不同的 holder key，少于 GuiViewKey 常量数 "
                            + GuiViewKey.values().length + "：可能有界面根本没挂 holder 喵。");
        }

        /** 每个枚举常量都必须至少被挂载一次。 */
        @Test
        @DisplayName("每个 GuiViewKey 常量都被使用")
        void allEnumConstantsAreUsed() {
            Set<String> used = collectAllHolderKeys();
            Set<String> unused = new TreeSet<>();
            for (GuiViewKey key : GuiViewKey.values()) {
                if (!used.contains(key.name())) {
                    unused.add(key.name());
                }
            }

            assertTrue(unused.isEmpty(),
                    "以下 GuiViewKey 常量在 gui/ 下没有任何 PluginGuiHolder.of 调用点（死身份）喵：\n  "
                            + String.join("\n  ", unused)
                            + "\n若该屏幕已被删除，请一并删除枚举常量；若是新常量，请挂到对应的 createInventory 上。");
        }

        /** 收集到的 key 必须都是枚举常量；顺带扫描全局 {@code GuiViewKey.XXX} 引用。 */
        @Test
        @DisplayName("不存在未知的 key 引用")
        void noUnknownKeysReferenced() {
            Set<String> declared = new LinkedHashSet<>();
            for (GuiViewKey key : GuiViewKey.values()) {
                declared.add(key.name());
            }

            Set<String> referenced = new TreeSet<>();
            for (Path file : allJavaFiles()) {
                String stripped = stripComments(readFile(file));
                Matcher m = KEY_REF.matcher(stripped);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            }

            Set<String> unknown = new TreeSet<>(referenced);
            unknown.removeAll(declared);

            assertTrue(unknown.isEmpty(),
                    "以下 GuiViewKey 名字在源码中被引用，但不在枚举常量里（正常情况下编译期就会挡住，"
                            + "此处仅作诊断）喵：" + unknown + "\n已声明：" + declared);
        }

        /** holder 调用点总数必须等于枚举常量数（全源码树，新增枚举值自动跟上）。 */
        @Test
        @DisplayName("holder 调用点数量与枚举常量数一致")
        void holderCallSiteCountMatchesEnumSize() {
            int holderCalls = 0;
            for (Path file : allJavaFiles()) {
                holderCalls += countMatches(stripComments(readFile(file)), HOLDER_OF);
            }

            assertEquals(GuiViewKey.values().length, holderCalls,
                    "全源码树 PluginGuiHolder.of 调用点共 " + holderCalls + " 处，"
                            + "但 GuiViewKey 有 " + GuiViewKey.values().length + " 个常量喵。"
                            + "常见原因：新增界面忘了挂 holder（调用点偏少），"
                            + "或同一界面被挂了两个 holder（调用点偏多）。");
        }
    }

    // ===== 用例组 2：结构断言 =====

    /**
     * <b>结构断言，不是行为断言。</b>
     *
     * <p>这里检查的是源码里语句的<b>先后顺序</b>，不是运行结果。因为在任何单测里都观测不到
     * 「保存线程在 add 与 bump 之间抢跑」这种纳秒级窗口 —— 它只在多线程真实调度下出现，
     * 且一旦出现就是「标记被误清、写入丢失」。顺序正确时该窗口在逻辑上不存在，
     * 所以唯一可行的验证方式就是读源码核对顺序。
     */
    @Nested
    @DisplayName("StructuralLints（结构断言，不是行为断言）")
    class StructuralLints {

        /**
         * R7-C（P2-2）不变式：{@code markDirty} / {@code markWordDirty} 必须先落标记，
         * 最后才递增写入序号，使「看到新序号 ⇒ 必然已看到标记」成立。
         *
         * <p>顺序反了不会崩、不会报错，只会让保存线程读到已前进的序号却读不到本轮的标记，
         * 于是按「窗口内无新写入」清掉整包脏标记 —— 纯顺序、不可观测，只能靠 lint 抓。
         */
        @Test
        @DisplayName("markDirty / markWordDirty 先标记后 bump 序号")
        void markDirtyAddsBeforeBumpingSequence() {
            Path store = srcRoot().resolve("data").resolve("PlayerDataStore.java");
            assertTrue(Files.isRegularFile(store),
                    "找不到 " + store.toAbsolutePath() + " 喵。");

            String stripped = stripComments(readFile(store));

            String markDirtyBody = extractMethodBody(stripped, "private void markDirty(UUID playerId)");
            int playerMark = markDirtyBody.indexOf("dirtyPlayers.add(playerId)");
            int playerBump = markDirtyBody.indexOf("bumpWriteSeq(playerId)");

            assertTrue(playerMark >= 0,
                    "在 markDirty 方法体里找不到 dirtyPlayers.add(playerId) 喵 —— "
                            + "脏标记的实现可能被改名/重构，请同步本 lint（否则门禁会静默失效）。");
            assertTrue(playerBump >= 0,
                    "在 markDirty 方法体里找不到 bumpWriteSeq(playerId) 喵 —— "
                            + "R7-C（P2-2）要求先标记再递增序号，请勿删除序号递增。");
            assertEquals(1, countMatches(markDirtyBody, BUMP_SEQ),
                    "markDirty 方法体内 bumpWriteSeq(playerId) 出现了 "
                            + countMatches(markDirtyBody, BUMP_SEQ) + " 次，应当恰好 1 次喵。");

            assertTrue(playerMark < playerBump,
                    "R7-C（P2-2）不变式被破坏：markDirty 里 bumpWriteSeq 出现在 "
                            + "dirtyPlayers.add 之前（add 偏移=" + playerMark
                            + "，bump 偏移=" + playerBump + "）喵。\n"
                            + "该顺序保证「保存线程看到新序号 ⇒ 必然已看到标记」；"
                            + "反过来先 bump 再 add，保存线程可能读到已递增的序号却读不到集合里的标记，"
                            + "于是按『窗口内无新写入』清掉整包脏标记，那次写入永远不会落库。\n"
                            + "这条顺序在运行时不可观测，只能靠本 lint 抓。");

            String markWordDirtyBody = extractMethodBody(stripped,
                    "private void markWordDirty(UUID playerId, String word)");
            int wordPlayerMark = markWordDirtyBody.indexOf("dirtyPlayers.add(playerId)");
            int wordMark = firstNonNegative(markWordDirtyBody,
                    "dirtyWords.computeIfAbsent", "dirtyWords.get(playerId).add", "dirtyWords.putIfAbsent");
            int wordBump = markWordDirtyBody.indexOf("bumpWriteSeq(playerId)");

            assertTrue(wordPlayerMark >= 0,
                    "在 markWordDirty 方法体里找不到 dirtyPlayers.add(playerId) 喵 —— "
                            + "增量保存若只写 word_progress 而漏写 player_data，统计数据将永不更新。");
            assertTrue(wordMark >= 0,
                    "在 markWordDirty 方法体里找不到任何向 dirtyWords 写入的语句"
                            + "（computeIfAbsent / get(...).add / putIfAbsent）喵 —— "
                            + "脏标记的实现可能被重构，请同步本 lint。");
            assertTrue(wordBump >= 0,
                    "在 markWordDirty 方法体里找不到 bumpWriteSeq(playerId) 喵。");
            assertEquals(1, countMatches(markWordDirtyBody, BUMP_SEQ),
                    "markWordDirty 方法体内 bumpWriteSeq(playerId) 出现了 "
                            + countMatches(markWordDirtyBody, BUMP_SEQ) + " 次，应当恰好 1 次喵。");

            int lastMark = Math.max(wordPlayerMark, wordMark);
            assertTrue(lastMark < wordBump,
                    "R7-C（P2-2）不变式被破坏：markWordDirty 里 bumpWriteSeq 出现在脏标记写入之前"
                            + "（最后一处标记偏移=" + lastMark + "，bump 偏移=" + wordBump + "）喵。\n"
                            + "必须先 dirtyPlayers.add + dirtyWords 落标记，最后才 bumpWriteSeq；"
                            + "否则保存线程可能看到新序号却看到空标记，进而误清整包脏标记、丢失写入。\n"
                            + "这条顺序在运行时不可观测，只能靠本 lint 抓。");
        }
    }

    // ===== 辅助方法 =====

    /** 源码根；找不到直接 fail（绝不 skip —— 跳过等于门禁静默关闭）。 */
    private static Path srcRoot() {
        Path root = Paths.get(System.getProperty("user.dir"), "src", "main", "java",
                "com", "letslearnenglish", "minecraftplugin");
        if (!Files.isDirectory(root)) {
            fail("找不到源码根 " + root.toAbsolutePath()
                    + " —— 测试须从仓库根目录运行（mvn test 默认满足）喵。");
        }
        return root;
    }

    /** {@code gui/} 目录；不存在直接 fail。 */
    private static Path guiDir() {
        Path dir = srcRoot().resolve("gui");
        if (!Files.isDirectory(dir)) {
            fail("找不到 GUI 源码目录 " + dir.toAbsolutePath() + " 喵。");
        }
        return dir;
    }

    /**
     * 递归列出<b>整个插件源码树</b>（{@link #srcRoot()}，即
     * {@code com/letslearnenglish/minecraftplugin}）下所有 {@code .java}。
     *
     * <p>为什么扫描根必须提到全树：早期版本只扫 {@code gui/}，于是在 {@code listener/}
     * 里新增一处裸 {@code Bukkit.createInventory(null, 27, "x")} 时六条 lint 全绿 ——
     * 界面没挂 holder，点击全部失效，门禁却报告一切正常。红队实测确认这条盲区存在。
     */
    private static List<Path> allJavaFiles() {
        Path dir = srcRoot();
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
            assertTrue(!files.isEmpty(), "在 " + dir.toAbsolutePath() + " 下没有找到任何 .java 文件喵。");
            return files;
        } catch (java.io.IOException e) {
            fail("遍历 " + dir.toAbsolutePath() + " 失败喵：" + e.getMessage());
            return List.of(); // 不可达
        }
    }

    /** 读取源码，统一 UTF-8（源码里有大量中文注释，平台默认编码会乱码）。 */
    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            fail("读取 " + path.toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /** 判断 path 是否位于 dir 之下（含任意深度的子目录）。 */
    private static boolean isInside(Path path, Path dir) {
        return path.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize());
    }

    /** 相对仓库根的路径，用于失败消息里一眼定位。 */
    private static String relative(Path path) {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path abs = path.toAbsolutePath();
        return abs.startsWith(repoRoot) ? repoRoot.relativize(abs).toString() : abs.toString();
    }

    /** 统计 haystack 中 pattern 的出现次数（非重叠）。 */
    private static int countMatches(String haystack, Pattern pattern) {
        Matcher m = pattern.matcher(haystack);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    /** 收集源码里所有 holder 构造点的 key 名（无顺序保证）。 */
    private static Set<String> extractHolderKeys(String source) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = HOLDER_OF_KEY.matcher(source);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /** 汇总整个源码树的 holder key 名。 */
    private static Set<String> collectAllHolderKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Path file : allJavaFiles()) {
            keys.addAll(extractHolderKeys(stripComments(readFile(file))));
        }
        return keys;
    }

    /**
     * 按<b>出现顺序</b>收集源码里所有 holder 构造点的 key（{@link GuiViewKey} 枚举值）。
     *
     * <p>与 {@link #extractHolderKeys(String)} 的区别：那个返回 {@code Set}（去重、
     * 无顺序），用于「两两不同」和「每个常量都被用到」两条诊断；这里保留顺序与重复，
     * 用于和黄金表逐项比对 —— 顺序敏感才能抓住「一文件两 key 互换」这类实测存活的变异。
     *
     * @throws IllegalStateException 出现表里没有的 key 字面量（正常情况编译期就挡住了）
     */
    private static List<GuiViewKey> extractHolderKeyList(String source) {
        List<GuiViewKey> keys = new ArrayList<>();
        Matcher m = HOLDER_OF_KEY.matcher(source);
        while (m.find()) {
            keys.add(GuiViewKey.valueOf(m.group(1)));
        }
        return keys;
    }

    /** 为黄金表对账的失败消息补一句「差在哪」—— 只比位置，不猜意图。 */
    private static String describeKeyDiff(List<GuiViewKey> expected, List<GuiViewKey> actual) {
        if (expected.size() == actual.size() && expected.size() > 1
                && new LinkedHashSet<>(expected).equals(new LinkedHashSet<>(actual))) {
            return "（key 集合相同但顺序不同 —— 实测过的存活变异就是这种「两个 key 互换」，"
                    + "请核对 createInventory 实际挂的是哪一个）";
        }
        if (expected.size() != actual.size()) {
            return "（数量不同：期望 " + expected.size() + " 处，实际 " + actual.size()
                    + " 处 —— 多挂或少挂 holder）";
        }
        return "";
    }

    /** 返回多个候选串中第一个出现（偏移 >= 0）的偏移，都不存在时返回 -1。 */
    private static int firstNonNegative(String source, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int idx = source.indexOf(needle);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    /**
     * 从方法签名定位方法体（花括号之间的内容）。
     *
     * <p>不能全文件 {@code indexOf("bumpWriteSeq(playerId)")} —— 该方法体里有多处调用点，
     * 必须先把范围收窄到目标方法。实现借用 {@link #stripComments(String)} 已经把字符串
     * 字面量抹成空格的前提，所以这里直接数 {@code {} / {@code }} 即可，无需再处理
     * {@code "{page}"} 这类标题里的花括号。
     *
     * @param strippedSource 已剥注释/字面量的源码
     * @param signature      完整方法签名（到右括号为止），如
     *                       {@code "private void markDirty(UUID playerId)"}
     * @return 花括号之间的方法体文本
     */
    private static String extractMethodBody(String strippedSource, String signature) {
        int sigIndex = strippedSource.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— "
                    + "方法可能被改名/改签名，请同步本 lint（否则门禁会静默失效）。");
        }
        if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位喵。");
        }

        int bodyStart = strippedSource.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            fail("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        // 签名与方法体之间若出现分号，说明匹配到的是声明/调用而不是带体的方法。
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
        fail("方法签名「" + signature + "」的花括号没有配对结束喵。");
        return ""; // 不可达
    }

    /**
     * 剥掉 Java 注释，并把字符串/字符字面量的内容抹成空格。
     *
     * <p>为什么不能一步正则搞定：源码里有 URL、有含 {@code //} 的字符串、有标题里的
     * {@code "{page}"}，正则无法区分「注释里的引号」与「字符串里的斜杠」。
     * 这里用单遍字符扫描状态机：
     * <ol>
     *   <li>遇到 {@code //} → 跳到行尾（保留换行，行号/偏移不错位）；</li>
     *   <li>遇到 {@code /*} → 跳到 {@code *}{@code /}（未闭合则跳到文末）；</li>
     *   <li>遇到 {@code """} → 跳到配对的 {@code """}（文本块）；</li>
     *   <li>遇到 {@code "} 或 {@code '} → 跳到配对引号，处理 {@code \\} 转义，
     *       遇换行视为字面量结束（容错未闭合）；</li>
     *   <li>其余字符原样保留。</li>
     * </ol>
     * 注释与字面量的内容统一替换为空格（但不换行），这样后续的正则计数与花括号配对
     * 既不会被注释里的 {@code Bukkit.createInventory(...)} 干扰，也不会被字符串里的
     * {@code {} / {@code )} 干扰；同时所有匹配串的偏移与原文保持一致，便于报错定位。
     */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // 1) 行注释
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            // 2) 块注释
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(source.charAt(j) == '*' && j + 1 < n && source.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            // 3) 文本块 """
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
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

            // 4) 普通字符串
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

    /** 把 {@code [from, to)} 区间内的非换行字符替换为空格（保持偏移与行结构）。 */
    private static void blank(char[] out, int from, int to) {
        int end = Math.min(to, out.length);
        for (int k = Math.max(0, from); k < end; k++) {
            if (out[k] != '\n' && out[k] != '\r') {
                out[k] = ' ';
            }
        }
    }
}
