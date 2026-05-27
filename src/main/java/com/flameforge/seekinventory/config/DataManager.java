package com.flameforge.seekinventory.config;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据管理器（SQLite 版本 + 缓存优化）
 * 负责加载、缓存和管理背包快照数据
 *
 * @author Tany天炎
 */
public class DataManager {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 数据库管理器 */
    private final DatabaseManager databaseManager;

    /** 快照类型 - 背包 */
    public static final String TYPE_INVENTORY = "inventory";

    /** 快照类型 - 末影箱 */
    public static final String TYPE_ENDERCHEST = "enderchest";

    /** 快照类型 - Curios 饰品栏 */
    public static final String TYPE_CURIOS = "curios";

    /** 快照列表缓存（玩家名_类型 -> 快照列表） */
    private final ConcurrentHashMap<String, LinkedHashMap<Long, String>> snapshotListCache;

    /** 快照计数缓存（玩家名_类型 -> 数量） */
    private final ConcurrentHashMap<String, Integer> snapshotCountCache;

    /** 缓存过期时间（毫秒） */
    private static final long CACHE_EXPIRE_TIME = 60000; // 1分钟

    /** 缓存时间戳（玩家名_类型 -> 时间戳） */
    private final ConcurrentHashMap<String, Long> cacheTimestamp;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public DataManager(SeekInventory plugin) {
        this.plugin = plugin;
        this.databaseManager = new DatabaseManager(plugin);
        this.snapshotListCache = new ConcurrentHashMap<>();
        this.snapshotCountCache = new ConcurrentHashMap<>();
        this.cacheTimestamp = new ConcurrentHashMap<>();
    }

    /**
     * 初始化数据库
     */
    public void initialize() {
        databaseManager.initialize();
    }

    /**
     * 保存背包快照
     *
     * @param playerName 玩家名
     * @param type 快照类型（inventory/enderchest/curios）
     * @param snapshotData 序列化的快照数据
     * @return 保存是否成功
     */
    public boolean saveSnapshot(String playerName, String type, String snapshotData) {
        String sql = "INSERT INTO inventory_snapshots (player_name, snapshot_type, snapshot_data, created_at) " +
                "VALUES (?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = databaseManager.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, playerName);
            stmt.setString(2, type);
            stmt.setString(3, snapshotData);
            stmt.setLong(4, System.currentTimeMillis() / 1000);
            stmt.executeUpdate();

            // 清除缓存
            invalidateCache(playerName, type);

            return true;

        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("snapshot.save-failed", placeholders));
            e.printStackTrace();
            return false;
        } finally {
            // 确保资源释放
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /**
     * 批量清理所有玩家的旧快照（定期调用，而非每次保存时调用）
     */
    public void cleanAllOldSnapshots() {
        int maxSnapshots = plugin.getConfigManager().getMaxSnapshots();

        String sql = "DELETE FROM inventory_snapshots WHERE id IN (" +
                "SELECT id FROM inventory_snapshots AS outer_table " +
                "WHERE (" +
                "  SELECT COUNT(*) FROM inventory_snapshots AS inner_table " +
                "  WHERE inner_table.player_name = outer_table.player_name " +
                "  AND inner_table.snapshot_type = outer_table.snapshot_type " +
                "  AND inner_table.created_at >= outer_table.created_at" +
                ") > ?" +
                ")";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, maxSnapshots);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("count", String.valueOf(deleted));
                plugin.sendMessage(plugin.getLanguageManager().getMessage("snapshot.clean-success", placeholders));

                // 清理后清除所有缓存，确保数据一致性
                clearAllCache();
            }

        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("snapshot.clean-old-failed", placeholders));
        }
    }

    /**
     * 清理过期快照（超过指定天数的快照将被删除）
     *
     * @param days 保留天数，超过此天数的快照将被删除
     */
    public void cleanExpiredSnapshots(int days) {
        // 计算截止时间戳（秒级）
        long cutoffTimestamp = (System.currentTimeMillis() / 1000) - ((long) days * 24 * 60 * 60);

        String sql = "DELETE FROM inventory_snapshots WHERE created_at < ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, cutoffTimestamp);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("count", String.valueOf(deleted));
                placeholders.put("days", String.valueOf(days));
                plugin.sendMessage(plugin.getLanguageManager().getMessage("snapshot.clean-expired-success", placeholders));

                // 清理后清除所有缓存，确保数据一致性
                clearAllCache();
            }

        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("snapshot.clean-expired-failed", placeholders));
        }
    }

    /**
     * 获取玩家的快照列表（带缓存）
     *
     * @param playerName 玩家名
     * @param type 快照类型
     * @return 快照列表（时间戳 -> 快照数据）
     */
    public LinkedHashMap<Long, String> getSnapshots(String playerName, String type) {
        String cacheKey = playerName + "_" + type;

        // 检查缓存是否有效
        if (isCacheValid(cacheKey)) {
            LinkedHashMap<Long, String> cached = snapshotListCache.get(cacheKey);
            if (cached != null) {
                return new LinkedHashMap<>(cached); // 返回副本
            }
        }

        // 从数据库加载
        LinkedHashMap<Long, String> snapshots = new LinkedHashMap<>();

        String sql = "SELECT created_at, snapshot_data FROM inventory_snapshots " +
                "WHERE player_name = ? AND snapshot_type = ? " +
                "ORDER BY created_at DESC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            stmt.setString(2, type);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long timestamp = rs.getLong("created_at");
                    String data = rs.getString("snapshot_data");
                    snapshots.put(timestamp, data);
                }
            }

            // 更新缓存
            snapshotListCache.put(cacheKey, snapshots);
            cacheTimestamp.put(cacheKey, System.currentTimeMillis());

        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("snapshot.get-list-failed", placeholders));
            e.printStackTrace();
        }

        return snapshots;
    }

    /**
     * 获取特定快照（按索引，0为最新）
     *
     * @param playerName 玩家名
     * @param type 快照类型
     * @param index 索引（0=最新）
     * @return 快照数据，不存在返回null
     */
    public String getSnapshot(String playerName, String type, int index) {
        String sql = "SELECT snapshot_data FROM inventory_snapshots " +
                "WHERE player_name = ? AND snapshot_type = ? " +
                "ORDER BY created_at DESC " +
                "LIMIT 1 OFFSET ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            stmt.setString(2, type);
            stmt.setInt(3, index);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("snapshot_data");
                }
            }

        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("snapshot.get-snapshot-failed", placeholders));
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 获取快照总数（带缓存）
     *
     * @param playerName 玩家名
     * @param type 快照类型
     * @return 快照数量
     */
    public int getSnapshotCount(String playerName, String type) {
        String cacheKey = playerName + "_" + type;

        // 检查缓存是否有效
        if (isCacheValid(cacheKey)) {
            Integer cached = snapshotCountCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        // 从数据库查询
        String sql = "SELECT COUNT(*) FROM inventory_snapshots " +
                "WHERE player_name = ? AND snapshot_type = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            stmt.setString(2, type);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    // 更新缓存
                    snapshotCountCache.put(cacheKey, count);
                    cacheTimestamp.put(cacheKey, System.currentTimeMillis());
                    return count;
                }
            }

        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("snapshot.get-count-failed", placeholders));
        }

        return 0;
    }

    /**
     * 检查缓存是否有效
     *
     * @param cacheKey 缓存键
     * @return 是否有效
     */
    private boolean isCacheValid(String cacheKey) {
        Long timestamp = cacheTimestamp.get(cacheKey);
        if (timestamp == null) {
            return false;
        }
        return (System.currentTimeMillis() - timestamp) < CACHE_EXPIRE_TIME;
    }

    /**
     * 清除指定玩家的缓存
     *
     * @param playerName 玩家名
     * @param type 快照类型
     */
    private void invalidateCache(String playerName, String type) {
        String cacheKey = playerName + "_" + type;
        snapshotListCache.remove(cacheKey);
        snapshotCountCache.remove(cacheKey);
        cacheTimestamp.remove(cacheKey);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        snapshotListCache.clear();
        snapshotCountCache.clear();
        cacheTimestamp.clear();
        plugin.sendMessage(plugin.getLanguageManager().getMessage("snapshot.cache-cleared"));
    }

    /**
     * 关闭数据库连接
     */
    public void close() {
        databaseManager.close();
    }

    /**
     * 获取数据库管理器
     *
     * @return 数据库管理器
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
