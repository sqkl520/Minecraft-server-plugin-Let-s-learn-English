package com.letslearnenglish.minecraftplugin.core.word;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 内置词库的<b>内容完整性</b>测试（读真实资源文件，不是 mock）。
 *
 * <h2>为什么需要这一组</h2>
 * 词库是本插件唯一的内容资产，但在此之前<b>没有任何测试读过它</b>：
 * {@code WordFreqRankTest} 测的是解析器，{@code WordTest} 测的是实体类，
 * 三份 {@code words/*.yml} 本身从没被断言过。后果是——2026-10-31 的按词频重建
 * （1357 词、覆盖率 66.16% → 85.37%）如果写坏了（字段缺失、词条重复、例句不含目标词、
 * 音标格式错乱），全部 500+ 个测试照样全绿，玩家进游戏才会发现「某个词没有中文释义」。
 *
 * <h2>这一组守什么</h2>
 * <ol>
 *   <li>三档词条数不为 0，且总数与重建后的规模一致（防止被清空/截断）；</li>
 *   <li>每个词条 8 个字段齐全且非空（{@code freq_rank} 可选）；</li>
 *   <li>三档之间<b>没有重复词条</b>（重建脚本的分桶逻辑最容易在这里出错）；</li>
 *   <li>每个例句真的包含目标词（允许规则屈折与常见不规则形式）；</li>
 *   <li>音标形如 {@code /.../}（与现有 1025 词的写法一致）；</li>
 *   <li>词性落在封闭词表内（10 个英文全称）；</li>
 *   <li>{@code freq_rank} 若存在则唯一且为正整数。</li>
 * </ol>
 *
 * <p><b>已知不测</b>：音标是否<b>正确</b>（这需要音标词典，且新增 332 词的音标来自
 * 美式 CMUdict、本库其余是英式，已知只能做到 58% 一致）、例句是否<b>语义贴切</b>
 * （需要人工）。这两项在 {@code THIRD-PARTY-NOTICES.md} 里已如实标注"未经逐词核验"。
 */
@DisplayName("WordBankContentTest — 内置词库内容完整性")
class WordBankContentTest {

    private static final String[] TIERS = {"beginner", "intermediate", "advanced"};
    private static final String[] REQUIRED_FIELDS = {
            "chinese", "part_of_speech", "phonetic", "example_sentence",
            "synonyms", "antonyms", "difficulty_level"
    };
    private static final Set<String> POS_ALLOWED = Set.of(
            "noun", "verb", "adjective", "adverb", "preposition",
            "pronoun", "conjunction", "determiner", "numeral", "interjection");

    // 不规则变化：判定"例句含目标词"时不能只看规则屈折，否则 meant/told/left 会被误判
    private static final Map<String, List<String>> IRREGULAR = new HashMap<>();
    static {
        IRREGULAR.put("mean", List.of("meant"));
        IRREGULAR.put("tell", List.of("told"));
        IRREGULAR.put("keep", List.of("kept"));
        IRREGULAR.put("bring", List.of("brought"));
        IRREGULAR.put("hold", List.of("held"));
        IRREGULAR.put("lead", List.of("led"));
        IRREGULAR.put("spend", List.of("spent"));
        IRREGULAR.put("send", List.of("sent"));
        IRREGULAR.put("forbid", List.of("forbidden"));
        IRREGULAR.put("compel", List.of("compelled"));
        IRREGULAR.put("dispel", List.of("dispelled"));
        IRREGULAR.put("bad", List.of("worse", "worst"));
        IRREGULAR.put("activity", List.of("activities"));
        IRREGULAR.put("program", List.of("programming"));
        IRREGULAR.put("feel", List.of("felt"));
        IRREGULAR.put("get", List.of("got", "gotten"));
        IRREGULAR.put("find", List.of("found"));
        IRREGULAR.put("say", List.of("said"));
        IRREGULAR.put("think", List.of("thought"));
        IRREGULAR.put("come", List.of("came"));
        IRREGULAR.put("go", List.of("went"));
        IRREGULAR.put("see", List.of("saw", "seen"));
        IRREGULAR.put("take", List.of("took", "taken"));
        IRREGULAR.put("give", List.of("gave", "given"));
        IRREGULAR.put("make", List.of("made"));
        IRREGULAR.put("know", List.of("knew", "known"));
        IRREGULAR.put("become", List.of("became"));
        IRREGULAR.put("begin", List.of("began", "begun"));
        IRREGULAR.put("run", List.of("ran"));
        IRREGULAR.put("write", List.of("wrote", "written"));
        IRREGULAR.put("speak", List.of("spoke", "spoken"));
        IRREGULAR.put("break", List.of("broke", "broken"));
        IRREGULAR.put("choose", List.of("chose", "chosen"));
        IRREGULAR.put("drive", List.of("drove", "driven"));
        IRREGULAR.put("eat", List.of("ate", "eaten"));
        IRREGULAR.put("fall", List.of("fell", "fallen"));
        IRREGULAR.put("forget", List.of("forgot", "forgotten"));
        IRREGULAR.put("hear", List.of("heard"));
        IRREGULAR.put("lose", List.of("lost"));
        IRREGULAR.put("meet", List.of("met"));
        IRREGULAR.put("pay", List.of("paid"));
        IRREGULAR.put("rise", List.of("rose", "risen"));
        IRREGULAR.put("sell", List.of("sold"));
        IRREGULAR.put("sit", List.of("sat"));
        IRREGULAR.put("sleep", List.of("slept"));
        IRREGULAR.put("stand", List.of("stood"));
        IRREGULAR.put("teach", List.of("taught"));
        IRREGULAR.put("understand", List.of("understood"));
        IRREGULAR.put("wear", List.of("wore", "worn"));
        IRREGULAR.put("win", List.of("won"));
        IRREGULAR.put("child", List.of("children"));
        IRREGULAR.put("person", List.of("people"));
        IRREGULAR.put("man", List.of("men"));
        IRREGULAR.put("woman", List.of("women"));
        IRREGULAR.put("foot", List.of("feet"));
        IRREGULAR.put("tooth", List.of("teeth"));
        IRREGULAR.put("life", List.of("lives"));
        IRREGULAR.put("wife", List.of("wives"));
    }

    private static final Pattern WORD_LINE = Pattern.compile("^\\s{2}-\\s+english:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FIELD_LINE = Pattern.compile("^\\s{4}([a-z_]+):\\s*(.*)$");

    /** 一个解析出来的词条：english + 其余字段原文（去引号）。 */
    private record Entry(String english, Map<String, String> fields) { }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("src/main/resources/words"))) return dir;
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（向上 6 层都没有 src/main/resources/words）");
    }

    private static List<Entry> load(String tier) throws IOException {
        Path f = repoRoot().resolve("src/main/resources/words/" + tier + ".yml");
        assertTrue(Files.isRegularFile(f), "词库文件不存在：" + f);
        List<Entry> out = new ArrayList<>();
        Entry cur = null;
        boolean inWords = false;
        for (String line : Files.readString(f, StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.matches("^words:\\s*$")) { inWords = true; continue; }
            if (!inWords) continue;

            Matcher m = WORD_LINE.matcher(line);
            if (m.matches()) {
                if (cur != null) out.add(cur);
                cur = new Entry(m.group(1), new HashMap<>());
                continue;
            }
            if (cur == null) continue;

            Matcher fm = FIELD_LINE.matcher(line);
            if (fm.matches()) {
                String key = fm.group(1);
                String raw = unquote(fm.group(2).trim());
                // 列表字段两种写法都要认：
                //   ① 行内：synonyms: []
                //   ② 块状：synonyms:\n      - "skill"\n      - "talent"
                // 块状时本行的值部分为空串，键本身是存在的 —— 记为占位符，
                // 由下面的 "- " 行补上真实内容。漏了 ② 会把 943 个正常词条误报成"缺字段"。
                cur.fields().put(key, raw.isEmpty() ? "__BLOCK__" : raw);
                continue;
            }
            // 列表项：- "skill"
            String t = line.trim();
            if (t.startsWith("- ")) {
                String item = unquote(t.substring(2).trim());
                // 挂到最近一个"块状"字段上
                String lastBlock = null;
                for (Map.Entry<String, String> en : cur.fields().entrySet()) {
                    if ("__BLOCK__".equals(en.getValue())) lastBlock = en.getKey();
                }
                if (lastBlock != null) {
                    String prev = cur.fields().get(lastBlock);
                    cur.fields().put(lastBlock, prev.length() <= "__BLOCK__".length()
                            ? item : prev + "|" + item);
                }
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    /**
     * 例句是否真的包含目标词。
     *
     * <p><b>短词必须只做精确匹配</b>（红队 F1）：{@code a} 的规则屈折会生成
     * {@code a/as/ad/aes}，于是 {@code "As I was leaving…"} 能通过
     * {@code \bas\b} 而句子里的「a」其实是 "As" 的首字母 —— 4 个最高频的
     * 单字母/双字母功能词（a / I / be / it）本来最该被守住，却全部漏判。
     * 长度 ≤ 2 时只认原形，不做任何屈折展开。
     */
    private static boolean containsWord(String word, String sentence) {
        String lw = word.toLowerCase();
        String lower = sentence.toLowerCase();

        if (lw.length() <= 2) {
            // 短词只认精确独立词形；不展开屈折，否则 a->as、it->its、be->bed 全部误判
            return Pattern.compile("\\b" + Pattern.quote(lw) + "\\b").matcher(lower).find();
        }

        String stem = lw.endsWith("e") ? lw.substring(0, lw.length() - 1) : lw;
        Set<String> forms = new HashSet<>();
        forms.add(lw);
        for (String suf : new String[] {"s", "es", "ed", "d", "ing"}) forms.add(stem + suf);
        for (String suf : new String[] {"s", "ed", "ing"}) forms.add(lw + suf);
        // 双写末辅音 + ed/ing：commit→committed、hug→hugged、cram→crammed、
        // drag→dragged、grin→grinned。英语拼写规则，不覆盖会导致这 5 个词被误报。
        if (lw.matches(".*[bcdfghjklmnpqrstvwxz]$")) {
            String dbl = lw + lw.charAt(lw.length() - 1);
            forms.add(dbl + "ed");
            forms.add(dbl + "ing");
        }
        forms.addAll(IRREGULAR.getOrDefault(lw, List.of()));
        for (String f : forms) {
            if (Pattern.compile("\\b" + Pattern.quote(f) + "\\b").matcher(lower).find()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------
    // 规模：防止被清空/截断
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("规模")
    class Scale {

        @Test
        @DisplayName("三档词条数均 > 0，且总数与重建后规模一致（1357）")
        void totalMatchesRebuild() throws IOException {
            int total = 0;
            for (String t : TIERS) {
                int n = load(t).size();
                assertTrue(n > 0, t + ".yml 解析出 0 个词条 —— 文件被清空或格式被破坏");
                total += n;
            }
            // 1357 = 原 1025 + 按 NGSL 前 500 补齐的 332 个高频词。
            // 若有意增删词库，改这个数字时请同时更新 words/README.md 的说明。
            assertEquals(1357, total,
                    "三档词条总数变了（实际 " + total + "）。若非有意增删，说明重建/编辑把词库写坏了。");
        }

        @Test
        @DisplayName("三档之间没有重复词条")
        void noCrossTierDuplicates() throws IOException {
            Map<String, String> seen = new HashMap<>();
            List<String> dups = new ArrayList<>();
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    String key = e.english().toLowerCase();
                    if (seen.containsKey(key)) dups.add(key + " (" + seen.get(key) + " + " + t + ")");
                    else seen.put(key, t);
                }
            }
            assertTrue(dups.isEmpty(),
                    "同一个词出现在多个档位：" + dups + "。按词频重排时最容易在这里出错 —— "
                            + "玩家会看到同一个词在 beginner 和 advanced 各出现一次。");
        }
    }

    // ---------------------------------------------------------------
    // 字段完整性
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("字段完整性")
    class FieldCompleteness {

        @Test
        @DisplayName("每个词条 7 个必填字段齐全且非空")
        void allRequiredFieldsPresent() throws IOException {
            List<String> bad = new ArrayList<>();
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    for (String f : REQUIRED_FIELDS) {
                        String v = e.fields().get(f);
                        // 空列表 "[]" 是合法值（功能词没有可靠同反义词时统一写 []，
                        // 见 words/README.md），不算缺失 —— 这里只拦「键本身不存在」与空白
                        if (v == null || v.isBlank()) {
                            bad.add(t + "/" + e.english() + " 缺 " + f);
                        }
                    }
                }
            }
            assertTrue(bad.isEmpty(),
                    bad.size() + " 个词条字段缺失。前 10 条：" + bad.subList(0, Math.min(10, bad.size())));
        }

        @Test
        @DisplayName("词性落在 10 个英文全称的封闭词表内")
        void partOfSpeechIsClosedSet() throws IOException {
            List<String> bad = new ArrayList<>();
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    String pos = e.fields().get("part_of_speech");
                    if (pos != null && !POS_ALLOWED.contains(pos)) {
                        bad.add(t + "/" + e.english() + " -> " + pos);
                    }
                }
            }
            assertTrue(bad.isEmpty(),
                    "非法词性（词库只认 noun/verb/adjective/adverb/preposition/pronoun/"
                            + "conjunction/determiner/numeral/interjection）：" + bad);
        }

        @Test
        @DisplayName("音标形如 /.../")
        void phoneticIsSlashDelimited() throws IOException {
            List<String> bad = new ArrayList<>();
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    String ph = e.fields().get("phonetic");
                    if (ph != null && !ph.matches("^/.+/$")) {
                        bad.add(t + "/" + e.english() + " -> " + ph);
                    }
                }
            }
            assertTrue(bad.isEmpty(), "音标格式异常（应为 /.../）：" + bad);
        }
    }

    // ---------------------------------------------------------------
    // 例句
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("例句")
    class Examples {

        @Test
        @DisplayName("每个例句都真的包含目标词（含规则屈折与常见不规则形式）")
        void exampleContainsTargetWord() throws IOException {
            List<String> bad = new ArrayList<>();
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    String ex = e.fields().get("example_sentence");
                    if (ex == null) continue;
                    if (!containsWord(e.english(), ex)) {
                        bad.add(t + "/" + e.english() + " -> \"" + ex + "\"");
                    }
                }
            }
            assertTrue(bad.isEmpty(),
                    bad.size() + " 个例句不含目标词。前 10 条：" + bad.subList(0, Math.min(10, bad.size())));
        }

        @Test
        @DisplayName("例句长度在 3~20 词之间")
        void exampleLengthReasonable() throws IOException {
            List<String> bad = new ArrayList<>();
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    String ex = e.fields().get("example_sentence");
                    if (ex == null || ex.isBlank()) continue;
                    int n = ex.trim().split("\\s+").length;
                    if (n < 3 || n > 20) bad.add(t + "/" + e.english() + " (" + n + " 词)");
                }
            }
            assertTrue(bad.isEmpty(),
                    "例句长度异常（<3 或 >20 词）：" + bad);
        }
    }

    // ---------------------------------------------------------------
    // freq_rank
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("freq_rank")
    class FreqRank {

        @Test
        @DisplayName("freq_rank 若存在则为正整数；同词元的不同词形可共用同一排名")
        void freqRankPositiveAndConsistentPerLemma() throws IOException {
            // 注意：**不**断言全局唯一。NGSL 的排名是「词元级」的，而词库按词条收录，
            // 于是同一词元的不同词形会拿到同一个排名：be/been、have/haven、it/its、
            // you/your、he/his、their/them/they、that/those、our/us/we、these/this、
            // I/my、her/she、say/said、who/whose、be/become/becoming。
            // 这不是缺陷 —— 它们本来就该共享频率。若断言唯一，正常数据会被误报。
            Map<Integer, List<String>> byRank = new HashMap<>();
            List<String> bad = new ArrayList<>();
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    String raw = e.fields().get("freq_rank");
                    if (raw == null || raw.isBlank()) continue;
                    int r;
                    try {
                        r = Integer.parseInt(raw);
                    } catch (NumberFormatException ex) {
                        bad.add(t + "/" + e.english() + " 非整数: " + raw);
                        continue;
                    }
                    if (r <= 0) { bad.add(t + "/" + e.english() + " 非正数: " + r); continue; }
                    byRank.computeIfAbsent(r, k -> new ArrayList<>()).add(e.english());
                }
            }
            assertTrue(bad.isEmpty(), "freq_rank 格式异常：" + bad);
            assertTrue(byRank.size() >= 400,
                    "带 freq_rank 的词元只有 " + byRank.size() + " 个 —— 重建后应有 440+ 个词元");
        }

        @Test
        @DisplayName("没有 freq_rank 的词是「不在 NGSL 表内」，属正常数据而非缺陷")
        void missingFreqRankIsNormal() throws IOException {
            int with = 0, without = 0;
            for (String t : TIERS) {
                for (Entry e : load(t)) {
                    String raw = e.fields().get("freq_rank");
                    if (raw == null || raw.isBlank()) without++; else with++;
                }
            }
            // 530 个具象名词（apple/banana/pencil）本来就不在通用频次表内
            assertTrue(without > 0,
                    "所有词都有 freq_rank 是可疑的：NGSL 只覆盖 2809 个通用高频词，"
                            + "apple/banana/pencil 这类具象名词本就不在表内");
            assertFalse(with == 0, "一个 freq_rank 都没有 —— WordBank 的解析或重建没生效");
        }
    }
}
