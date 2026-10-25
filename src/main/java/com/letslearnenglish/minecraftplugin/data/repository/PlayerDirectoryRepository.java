package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * player_data 的轻量读侧仓储：名字 ↔ UUID 目录查询，以及单列统计读取。
 *
 * <p>本类只搬 SQL 与参数绑定；日期边界/时区换算、业务判定、异常语义仍留在调用方。
 * 名字回落的业务规则（如报告里"在线名 → 库内名 → uuid 前 8 位"的优先级）、
 * 三级查找中的 Bukkit 在线/名册分支、tab 补全的缓存策略均不在本类。
 *
 * <p>锁纪律（E-04 正确范式）：所有方法在 {@code synchronized (connectionLock)} 内
 * 取连接并执行语句，连接引用不跨出锁。方法内部不调用 {@code setAutoCommit}
 * （连接级状态，会破坏外层事务）。
 *
 * <p>异常语义按调用方分别保留：
 * <ul>
 *   <li>{@link #resolveUuidByName} / {@link #findNamesByPrefix}：读失败记 WARNING
 *       并返回 {@code null} / 空列表（AdminCommandHandler 的既有契约，
 *       "找不到"仍以 null 表达，不改成 Optional）。</li>
 *   <li>{@link #findLongestStreak}：<strong>静默吞掉</strong> SQLException，只留
 *       原注释——PAPI 占位符每秒可能被调用多次，不能刷 WARNING 日志。</li>
 * </ul>
 *
 * <p>线程模型：方法同步阻塞在调用线程；调用方各自决定线程（AdminCommandHandler
 * 在命令主线程，LLEPapiExpansion 在任意线程），本类不新增线程切换。
 *
 * <p>生命周期（R6-E）：表前缀<b>不</b>在构造时快照，而是每次拼 SQL 时现读
 * {@link DatabaseManager#getTablePrefix()}。理由见
 * {@code ReportRepository} 类 javadoc 的"生命周期"段：{@code tablePrefix} 是可变字段，
 * 重连时 {@code initialize()} 会按当前配置重写，构造期快照会导致本类与
 * {@code DatabaseManager} 指向两套不同的表。调用方仍须在 {@code initialize()} 之后
 * 构造本类。
 */
public class PlayerDirectoryRepository {

    private final DatabaseManager db;
    private final Logger logger;

    /**
     * @param db     数据库管理器（连接、连接锁、表前缀的唯一来源）
     * @param logger 插件日志器（失败降级时记 WARNING；调用方传 {@code plugin.getLogger()}）
     */
    public PlayerDirectoryRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * R6-E：现读表前缀，不在构造期快照（见类 javadoc 的"生命周期"段）。
     */
    private String prefix() {
        return db.getTablePrefix();
    }

    /**
     * 按 player_name 精确查 player_data.uuid（不含 Bukkit 在线/名册回落——
     * 那是调用方的三级查找）。
     *
     * @param playerName 玩家名（精确匹配，大小写按库内排序规则）
     * @return 查到的 UUID；无行、SQL 失败或库内 uuid 非法均返回 {@code null}
     */
    public UUID resolveUuidByName(String playerName) {
        String sql = "SELECT uuid FROM " + prefix()
                + "player_data WHERE player_name = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("uuid"));
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                logger.log(Level.WARNING,
                        "Failed to lookup player UUID: " + playerName, e);
            }
        }
        return null;
    }

    /**
     * 按前缀查 player_data.player_name（DISTINCT，最多 {@code limit} 条）。
     * 空行/空白名字在结果侧过滤（与原实现一致）。
     *
     * @param prefix 已输入前缀（空串 = 全量前缀）
     * @param limit  最大返回条数
     * @return 名字列表（无命中或读失败返回空列表，永不为 null）
     */
    public List<String> findNamesByPrefix(String prefix, int limit) {
        List<String> names = new ArrayList<>();
        String sql = "SELECT DISTINCT player_name FROM " + prefix()
                + "player_data WHERE player_name IS NOT NULL AND player_name LIKE ? LIMIT ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, prefix + "%");
                stmt.setInt(2, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("player_name");
                        if (name != null && !name.isBlank()) {
                            names.add(name);
                        }
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query known player names for tab completion", e);
            }
        }
        return names;
    }

    /**
     * 读 player_data.longest_streak（答题会话连击峰值，只增不减）。
     *
     * <p>异常语义严格保留原实现：<strong>静默返回 0</strong>，不记日志——
     * PAPI 占位符每秒可能被调用多次，WARNING 会刷屏。
     *
     * @param playerId 玩家 UUID
     * @return 库内连击峰值；无行或读失败返回 0
     */
    public int findLongestStreak(UUID playerId) {
        String column = "longest_streak";
        String sql = "SELECT " + column + " FROM " + prefix() + "player_data WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(column);
                    }
                }
            } catch (SQLException e) {
                // Silently return 0 on error
            }
        }
        return 0;
    }
}
