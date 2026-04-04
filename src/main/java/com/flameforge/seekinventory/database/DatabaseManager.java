package com.flameforge.seekinventory.database;

import com.flameforge.seekinventory.SeekInventory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据库管理器
 * 负责 SQLite 数据库连接和表结构管理
 *
 * @author Tany天炎
 */
public class DatabaseManager {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** HikariCP 连接池 */
    private HikariDataSource dataSource;

    /** 数据库文件路径 */
    private final File databaseFile;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public DatabaseManager(SeekInventory plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "snapshots.db");
    }

    /**
     * 初始化数据库连接池
     */
    public void initialize() {
        try {
            // 确保数据文件夹存在
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            // 配置 HikariCP
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(20); // 增加到 20
            config.setMinimumIdle(5); // 增加最小空闲连接
            config.setConnectionTimeout(60000); // 增加到 60 秒
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setConnectionTestQuery("SELECT 1");
            config.setPoolName("SeekInventory-Pool");

            // SQLite 优化配置
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            // 创建连接池
            dataSource = new HikariDataSource(config);

            // 创建表结构
            createTables();

            plugin.sendMessage(plugin.getLanguageManager().getMessage("console.database-init-success"));

        } catch (Exception e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("console.database-init-failed", placeholders));
            e.printStackTrace();
        }
    }

    /**
     * 创建数据库表结构
     */
    private void createTables() {
        // 背包快照表
        String createSnapshotsTable = "CREATE TABLE IF NOT EXISTS inventory_snapshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_name TEXT NOT NULL," +
                "snapshot_type TEXT NOT NULL," +
                "snapshot_data TEXT NOT NULL," +
                "created_at INTEGER NOT NULL" +
                ")";

        // 创建索引（优化查询性能）
        String createPlayerTypeIndex = "CREATE INDEX IF NOT EXISTS idx_player_type " +
                "ON inventory_snapshots(player_name, snapshot_type)";

        String createCreatedAtIndex = "CREATE INDEX IF NOT EXISTS idx_created_at " +
                "ON inventory_snapshots(created_at DESC)";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(createSnapshotsTable);
            stmt.execute(createPlayerTypeIndex);
            stmt.execute(createCreatedAtIndex);
            plugin.sendMessage(plugin.getLanguageManager().getMessage("console.database-table-created"));

        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("console.database-table-create-failed", placeholders));
            e.printStackTrace();
        }
    }

    /**
     * 获取数据库连接
     *
     * @return 数据库连接
     * @throws SQLException 数据库异常
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException(plugin.getLanguageManager().getMessage("database.connection-not-initialized"));
        }
        return dataSource.getConnection();
    }

    /**
     * 关闭数据库连接池
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.sendMessage(plugin.getLanguageManager().getMessage("console.database-closed"));
        }
    }

    /**
     * 检查连接池是否可用
     *
     * @return true=可用，false=不可用
     */
    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }
}