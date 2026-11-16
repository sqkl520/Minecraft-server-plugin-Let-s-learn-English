package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R13-T2：Tab 补全的前缀查询里，LIKE 元字符必须被转义。
 *
 * <p><b>缺陷本体</b>：{@code findNamesByPrefix} 原实现是
 * {@code stmt.setString(1, prefix + "%")} —— 把玩家输入<b>原样</b>当成 LIKE 模式。
 * 而 LIKE 里 {@code _} 是<b>单字符通配符</b>（不是字面下划线），{@code %} 是任意串通配符。
 *
 * <p><b>为什么这在 Minecraft 里是真实问题</b>：Minecraft 用户名<b>允许下划线</b>。
 * 玩家输入 {@code a_} 做补全时，SQL 实际执行的是 {@code LIKE 'a_%'} ——
 * 匹配 {@code ab}、{@code a1}、{@code ac}……补全列表里冒出一堆与输入无关的名字，
 * 而操作者（服主/管理员）会以为是插件坏了。{@code %} 同理，虽然用户名不含它，
 * 但命令层未必拦得住（玩家可从控制台或其他入口输入）。
 *
 * <p><b>修法</b>：SQL 加 {@code ESCAPE '\'}，前缀里的 {@code \ % _} 三个字符逐个转义。
 * 本类用<b>真实 SQLite</b> 验证，因为「LIKE 是否真的按转义语义匹配」是 SQL 引擎的行为，
 * mock 一个返回值的测试证明不了任何事。
 */
@DisplayName("R13-T2：玩家名前缀补全的 LIKE 转义")
class PlayerDirectoryLikeEscapeTest {

    @TempDir
    Path dir;

    private Path dbFile;
    private Connection seedConn;
    private String prefix;
    private PlayerDirectoryRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = dir.resolve("dir.db");
        seedConn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        prefix = "t_";
        try (Statement st = seedConn.createStatement()) {
            st.executeUpdate("CREATE TABLE " + prefix + "player_data ("
                    + " uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(16))");
        }

        DatabaseManager db = mock(DatabaseManager.class);
        lenient().when(db.getTablePrefix()).thenReturn(prefix);
        lenient().when(db.getConnectionLock()).thenReturn(new Object());
        lenient().when(db.getConnection()).thenReturn(seedConn);
        repo = new PlayerDirectoryRepository(db, Logger.getLogger("PlayerDirectoryLikeEscapeTest"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (seedConn != null && !seedConn.isClosed()) {
            seedConn.close();
        }
    }

    private void seed(String... names) throws SQLException {
        try (PreparedStatement ps = seedConn.prepareStatement(
                "INSERT INTO " + prefix + "player_data (uuid, player_name) VALUES (?, ?)")) {
            int i = 0;
            for (String n : names) {
                ps.setString(1, String.format("00000000-0000-0000-0000-%012d", i++));
                ps.setString(2, n);
                ps.executeUpdate();
            }
        }
    }

    // ==================================================================
    // 组 A —— 下划线：Minecraft 用户名真的允许它
    // ==================================================================

    @Test
    @DisplayName("前缀 a_ 只匹配真正以 a_ 开头的名字，不匹配 ab / a1 / ac")
    void underscoreIsLiteralNotWildcard() throws Exception {
        seed("a_bob", "a_amy", "ab", "a1", "ac", "zzz");

        List<String> got = repo.findNamesByPrefix("a_", 50);

        assertEquals(List.of("a_amy", "a_bob"), got.stream().sorted().toList(),
                "前缀里的 '_' 必须当字面下划线。若不转义，SQL 会把 'a_' 当「a + 任意一个字符」"
                        + "从而把 ab / a1 / ac 也匹配进来 —— 这些名字与操作者的输入无关，"
                        + "补全列表被污染而操作者以为是插件坏了。实际返回：" + got);
    }

    @Test
    @DisplayName("前缀 a（无元字符）仍匹配全部 a 开头 —— 防过度修复")
    void plainPrefixStillWorks() throws Exception {
        seed("a_bob", "ab", "ac", "zzz");

        List<String> got = repo.findNamesByPrefix("a", 50);

        assertEquals(List.of("a_bob", "ab", "ac"), got.stream().sorted().toList(),
                "不含元字符的前缀行为必须与修复前完全一致（防止「一律加转义」把普通前缀也弄坏）。"
                        + "实际返回：" + got);
    }

    // ==================================================================
    // 组 B —— 百分号
    // ==================================================================

    @Test
    @DisplayName("前缀含 % 时按字面匹配（虽然用户名不含它，但入口不保证）")
    void percentIsLiteral() throws Exception {
        seed("a%b", "axb", "ab");

        List<String> got = repo.findNamesByPrefix("a%", 50);

        assertEquals(List.of("a%b"), got,
                "前缀里的 '%' 必须当字面百分号。若不转义，'a%' 会匹配<b>所有</b> a 开头的名字。"
                        + "实际返回：" + got);
    }

    // ==================================================================
    // 组 C —— 反斜杠本身（转义的转义）
    // ==================================================================

    @Test
    @DisplayName("前缀含反斜杠时不会把后续字符误当转义序列")
    void backslashIsEscapedFirst() throws Exception {
        seed("a\\b", "ab");

        List<String> got = repo.findNamesByPrefix("a\\", 50);

        assertEquals(List.of("a\\b"), got,
                "前缀里的 '\\' 必须自己先被转义。若顺序写反（先转义 % _ 再转义 \\），"
                        + "'a\\' 会变成 'a\\\\' 从而匹配 'a' + 任意字符 —— 或直接被 SQL 当成"
                        + "非法转义序列而报错。实际返回：" + got);
    }

    // ==================================================================
    // 组 D —— 边界
    // ==================================================================

    @Test
    @DisplayName("空前缀 = 全量（与修复前一致）")
    void emptyPrefixReturnsAll() throws Exception {
        seed("alpha", "beta");

        List<String> got = repo.findNamesByPrefix("", 50);

        assertEquals(2, got.size(), "空前缀应返回全部已记录名字。实际返回：" + got);
    }

    @Test
    @DisplayName("无命中 → 空列表，不抛异常")
    void noMatchReturnsEmpty() throws Exception {
        seed("alpha");

        List<String> got = repo.findNamesByPrefix("zzz", 50);

        assertTrue(got.isEmpty(), "无命中必须返回空列表（不是 null）。实际返回：" + got);
    }

    @Test
    @DisplayName("limit 生效")
    void limitIsApplied() throws Exception {
        seed("a1", "a2", "a3", "a4");

        List<String> got = repo.findNamesByPrefix("a", 2);

        assertFalse(got.size() > 2, "limit=2 时最多返回 2 条。实际返回：" + got);
    }
}
