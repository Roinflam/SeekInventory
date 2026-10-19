package com.flameforge.seekinventory.claim;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.config.DataManager;
import com.flameforge.seekinventory.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.serializer.ISerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 领取授权核心管理器
 *
 * <p>负责三张表的建立与维护：</p>
 * <ul>
 *   <li>{@code claim_grants} —— 玩家的领取额度（每位玩家每种类型至多一条）</li>
 *   <li>{@code claim_taken} —— 已被领取的快照槽位（防止同一物品被重复领取）</li>
 *   <li>{@code claim_logs} —— 领取会话汇总日志（同时承担待上报队列）</li>
 * </ul>
 *
 * <p><b>防重复领取设计：</b>额度按「次数」计量，但同一条快照的同一个槽位在
 * {@code claim_taken} 中唯一。这样玩家即使拥有多次额度、可以无限次重开界面，
 * 也无法把同一件独一无二的物品领取两次。</p>
 *
 * <p><b>并发与性能：</b>授权对象常驻内存（{@link #grants}），额度扣减在内存中同步完成
 * （对 {@link ClaimGrant} 对象加锁），数据库写入异步执行，避免主线程 I/O 卡顿。
 * 权衡：若服务端在扣减后、异步落库前崩溃，该次扣减会丢失（玩家可多领 1 件）。
 * 由于窗口期仅数毫秒且领取为低频操作，此权衡是可接受的。</p>
 *
 * @author Tany天炎
 */
public class ClaimManager {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 数据库管理器（复用 DataManager 内已初始化的连接池） */
    private final DatabaseManager databaseManager;

    /** 授权缓存：key = 玩家名小写|类型 -> 授权对象 */
    private final ConcurrentHashMap<String, ClaimGrant> grants = new ConcurrentHashMap<>();

    /** 已领取槽位缓存：key = 玩家名小写|快照ID -> 槽位全局索引集合 */
    private final ConcurrentHashMap<String, Set<Integer>> takenCache = new ConcurrentHashMap<>();

    /** 领取消耗结果枚举 */
    public enum ConsumeResult {
        /** 领取成功 */
        SUCCESS,
        /** 没有该类型的授权 */
        NO_GRANT,
        /** 授权已过期 */
        EXPIRED,
        /** 额度已用尽 */
        EXHAUSTED,
        /** 该槽位此前已被领取过 */
        ALREADY_TAKEN
    }

    /**
     * 快照条目（含元数据，可选携带序列化数据）
     */
    public static class SnapshotEntry {

        /** 数据库主键 */
        private final long id;

        /** 快照所属玩家名 */
        private final String playerName;

        /** 快照类型 */
        private final String type;

        /** 记录时间（秒级时间戳） */
        private final long createdAt;

        /** 序列化数据（列表查询时为 null，按 ID 查询时非 null） */
        private final String data;

        /**
         * 构造函数
         *
         * @param id         数据库主键
         * @param playerName 所属玩家名
         * @param type       快照类型
         * @param createdAt  记录时间（秒）
         * @param data       序列化数据，可为 null
         */
        public SnapshotEntry(long id, String playerName, String type, long createdAt, String data) {
            this.id = id;
            this.playerName = playerName;
            this.type = type;
            this.createdAt = createdAt;
            this.data = data;
        }

        public long getId() {
            return id;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getType() {
            return type;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public String getData() {
            return data;
        }
    }

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public ClaimManager(SeekInventory plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDataManager().getDatabaseManager();
    }

    /**
     * 初始化：建表 + 加载未过期授权到内存
     */
    public void initialize() {
        createTables();
        loadGrants();
    }

    /**
     * 创建领取功能所需的三张表及索引
     */
    private void createTables() {
        String createGrants = "CREATE TABLE IF NOT EXISTS claim_grants (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_name TEXT NOT NULL," +
                "grant_type TEXT NOT NULL," +
                "remaining INTEGER NOT NULL," +
                "total INTEGER NOT NULL," +
                "granter TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "expire_at INTEGER NOT NULL," +
                "UNIQUE(player_name, grant_type)" +
                ")";

        String createTaken = "CREATE TABLE IF NOT EXISTS claim_taken (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_name TEXT NOT NULL," +
                "snapshot_id INTEGER NOT NULL," +
                "slot INTEGER NOT NULL," +
                "taken_at INTEGER NOT NULL," +
                "UNIQUE(player_name, snapshot_id, slot)" +
                ")";

        String createLogs = "CREATE TABLE IF NOT EXISTS claim_logs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_name TEXT NOT NULL," +
                "grant_type TEXT NOT NULL," +
                "snapshot_id INTEGER NOT NULL," +
                "snapshot_time INTEGER NOT NULL," +
                "item_summary TEXT NOT NULL," +
                "item_count INTEGER NOT NULL," +
                "remaining_after INTEGER NOT NULL," +
                "claimed_at INTEGER NOT NULL," +
                "reported INTEGER NOT NULL DEFAULT 0" +
                ")";

        String idxTaken = "CREATE INDEX IF NOT EXISTS idx_claim_taken_lookup " +
                "ON claim_taken(player_name, snapshot_id)";
        String idxLogsPlayer = "CREATE INDEX IF NOT EXISTS idx_claim_logs_player " +
                "ON claim_logs(player_name, claimed_at DESC)";
        String idxLogsReported = "CREATE INDEX IF NOT EXISTS idx_claim_logs_reported " +
                "ON claim_logs(reported)";

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(createGrants);
            stmt.execute(createTaken);
            stmt.execute(createLogs);
            stmt.execute(idxTaken);
            stmt.execute(idxLogsPlayer);
            stmt.execute(idxLogsReported);

            plugin.sendMessage(plugin.getLanguageManager().getMessage("claim.table-created"));

        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.table-create-failed", ph));
        }
    }

    /**
     * 从数据库加载所有未过期的授权到内存缓存
     */
    private void loadGrants() {
        String sql = "SELECT player_name, grant_type, remaining, total, granter, created_at, expire_at " +
                "FROM claim_grants WHERE expire_at > ? AND remaining > 0";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, now());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ClaimGrant grant = new ClaimGrant(
                            rs.getString("player_name"),
                            rs.getString("grant_type"),
                            rs.getInt("remaining"),
                            rs.getInt("total"),
                            rs.getString("granter"),
                            rs.getLong("created_at"),
                            rs.getLong("expire_at"));
                    grants.put(key(grant.getPlayerName(), grant.getGrantType()), grant);
                }
            }

            Map<String, String> ph = new HashMap<>();
            ph.put("count", String.valueOf(grants.size()));
            plugin.sendMessage(plugin.getLanguageManager().getMessage("claim.grants-loaded", ph));

        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.grants-load-failed", ph));
        }
    }

    // ==================== 授权发放 / 撤销 / 查询 ====================

    /**
     * 发放授权（同玩家同类型重复发放时：次数累加，过期时间取较晚者）
     *
     * <p>本方法包含数据库写入，请在异步线程调用。</p>
     *
     * @param playerName 目标玩家名（规范大小写）
     * @param type       授权类型
     * @param count      发放次数，调用方需保证 &gt; 0
     * @param hours      有效期小时数，调用方需保证 &gt; 0
     * @param granter    发放者名称
     * @return 发放后的授权对象，失败返回 null
     */
    public ClaimGrant grant(String playerName, String type, int count, int hours, String granter) {
        String cacheKey = key(playerName, type);
        long current = now();
        long newExpire = current + (long) hours * 3600L;

        synchronized (grants) {
            ClaimGrant exist = grants.get(cacheKey);

            int newRemaining;
            int newTotal;
            long createdAt;
            long expireAt;

            if (exist != null && !exist.isExpired()) {
                // 累加已有授权
                newRemaining = exist.getRemaining() + count;
                newTotal = exist.getTotal() + count;
                createdAt = exist.getCreatedAt();
                expireAt = Math.max(exist.getExpireAt(), newExpire);
            } else {
                newRemaining = count;
                newTotal = count;
                createdAt = current;
                expireAt = newExpire;
            }

            // 使用 INSERT OR REPLACE 而非 UPSERT，兼容较老版本的 sqlite-jdbc 驱动
            String sql = "INSERT OR REPLACE INTO claim_grants " +
                    "(player_name, grant_type, remaining, total, granter, created_at, expire_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, playerName);
                stmt.setString(2, type);
                stmt.setInt(3, newRemaining);
                stmt.setInt(4, newTotal);
                stmt.setString(5, granter);
                stmt.setLong(6, createdAt);
                stmt.setLong(7, expireAt);
                stmt.executeUpdate();

            } catch (SQLException e) {
                Map<String, String> ph = new HashMap<>();
                ph.put("error", e.getMessage());
                plugin.sendError(plugin.getLanguageManager().getMessage("claim.grant-save-failed", ph));
                return null;
            }

            ClaimGrant grant = new ClaimGrant(playerName, type, newRemaining, newTotal,
                    granter, createdAt, expireAt);
            grants.put(cacheKey, grant);
            return grant;
        }
    }

    /**
     * 撤销授权
     *
     * <p>本方法包含数据库写入，请在异步线程调用。</p>
     *
     * @param playerName 目标玩家名
     * @param type       授权类型，传 null 表示撤销该玩家全部类型
     * @return 实际撤销的授权条数
     */
    public int revoke(String playerName, String type) {
        String sql = type == null
                ? "DELETE FROM claim_grants WHERE player_name = ? COLLATE NOCASE"
                : "DELETE FROM claim_grants WHERE player_name = ? COLLATE NOCASE AND grant_type = ?";

        int deleted = 0;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            if (type != null) {
                stmt.setString(2, type);
            }
            deleted = stmt.executeUpdate();

        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.grant-revoke-failed", ph));
            return 0;
        }

        // 同步清理内存缓存
        if (type == null) {
            for (String t : allTypes()) {
                grants.remove(key(playerName, t));
            }
        } else {
            grants.remove(key(playerName, type));
        }
        return deleted;
    }

    /**
     * 获取玩家某类型的可用授权
     *
     * @param playerName 玩家名
     * @param type       授权类型
     * @return 可用授权对象；不存在、已过期或已用尽时返回 null
     */
    public ClaimGrant getUsableGrant(String playerName, String type) {
        ClaimGrant grant = grants.get(key(playerName, type));
        if (grant == null) {
            return null;
        }
        if (!grant.isUsable()) {
            return null;
        }
        return grant;
    }

    /**
     * 获取玩家某类型的授权（不判断是否可用，用于区分「无授权 / 已过期 / 已用尽」）
     *
     * @param playerName 玩家名
     * @param type       授权类型
     * @return 授权对象，不存在返回 null
     */
    public ClaimGrant getGrant(String playerName, String type) {
        return grants.get(key(playerName, type));
    }

    /**
     * 获取玩家全部可用授权
     *
     * @param playerName 玩家名
     * @return 可用授权列表（可能为空，永不为 null）
     */
    public List<ClaimGrant> getUsableGrants(String playerName) {
        List<ClaimGrant> list = new ArrayList<>();
        for (String type : allTypes()) {
            ClaimGrant grant = getUsableGrant(playerName, type);
            if (grant != null) {
                list.add(grant);
            }
        }
        return list;
    }

    /**
     * 获取全服所有可用授权（用于 /seekclaim query 无参查询）
     *
     * @return 可用授权列表
     */
    public List<ClaimGrant> getAllUsableGrants() {
        List<ClaimGrant> list = new ArrayList<>();
        for (ClaimGrant grant : grants.values()) {
            if (grant.isUsable()) {
                list.add(grant);
            }
        }
        list.sort((a, b) -> Long.compare(a.getExpireAt(), b.getExpireAt()));
        return list;
    }

    // ==================== 额度消耗 ====================

    /**
     * 尝试消耗一次领取额度并标记槽位已领取
     *
     * <p>内存判定与扣减为同步原子操作，数据库写入异步执行。
     * 本方法可在主线程安全调用。</p>
     *
     * @param playerName 领取者玩家名
     * @param type       领取类型
     * @param snapshotId 来源快照主键
     * @param slot       槽位全局索引
     * @return 消耗结果
     */
    public ConsumeResult tryConsume(String playerName, String type, long snapshotId, int slot) {
        ClaimGrant grant = grants.get(key(playerName, type));
        if (grant == null) {
            return ConsumeResult.NO_GRANT;
        }
        if (grant.isExpired()) {
            return ConsumeResult.EXPIRED;
        }

        Set<Integer> taken = takenCache.computeIfAbsent(
                takenKey(playerName, snapshotId), k -> new CopyOnWriteArraySet<>());

        synchronized (grant) {
            if (grant.getRemaining() <= 0) {
                return ConsumeResult.EXHAUSTED;
            }
            // add 返回 false 说明该槽位已在集合中（已被领取）
            if (!taken.add(slot)) {
                return ConsumeResult.ALREADY_TAKEN;
            }
            grant.setRemaining(grant.getRemaining() - 1);
        }

        final int remainingAfter = grant.getRemaining();
        final long takenAt = now();

        // 异步落库：先写已领取槽位（防重复的关键），再更新额度
        // 关服流程中无法调度任务，此时退化为同步写入，确保扣减不丢失
        if (plugin.isEnabled()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    persistTaken(playerName, snapshotId, slot, takenAt);
                    persistRemaining(playerName, type, remainingAfter);
                }
            }.runTaskAsynchronously(plugin);
        } else {
            persistTaken(playerName, snapshotId, slot, takenAt);
            persistRemaining(playerName, type, remainingAfter);
        }

        // 额度归零时移除授权，玩家随即失去查看历史备份的权限
        if (remainingAfter <= 0) {
            grants.remove(key(playerName, type));
        }

        return ConsumeResult.SUCCESS;
    }

    /**
     * 持久化「已领取槽位」记录
     *
     * @param playerName 玩家名
     * @param snapshotId 快照主键
     * @param slot       槽位全局索引
     * @param takenAt    领取时间（秒）
     */
    private void persistTaken(String playerName, long snapshotId, int slot, long takenAt) {
        String sql = "INSERT OR IGNORE INTO claim_taken (player_name, snapshot_id, slot, taken_at) " +
                "VALUES (?, ?, ?, ?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            stmt.setLong(2, snapshotId);
            stmt.setInt(3, slot);
            stmt.setLong(4, takenAt);
            stmt.executeUpdate();

        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.taken-save-failed", ph));
        }
    }

    /**
     * 持久化剩余额度；额度为 0 时直接删除授权行
     *
     * @param playerName 玩家名
     * @param type       授权类型
     * @param remaining  剩余次数
     */
    private void persistRemaining(String playerName, String type, int remaining) {
        String sql = remaining > 0
                ? "UPDATE claim_grants SET remaining = ? WHERE player_name = ? COLLATE NOCASE AND grant_type = ?"
                : "DELETE FROM claim_grants WHERE player_name = ? COLLATE NOCASE AND grant_type = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            if (remaining > 0) {
                stmt.setInt(1, remaining);
                stmt.setString(2, playerName);
                stmt.setString(3, type);
            } else {
                stmt.setString(1, playerName);
                stmt.setString(2, type);
            }
            stmt.executeUpdate();

        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.grant-save-failed", ph));
        }
    }

    /**
     * 加载某玩家在某快照上已领取的槽位集合（带内存缓存）
     *
     * <p>包含数据库读取，请在异步线程首次调用（打开界面前）。</p>
     *
     * @param playerName 玩家名
     * @param snapshotId 快照主键
     * @return 已领取的槽位全局索引集合（永不为 null）
     */
    public Set<Integer> loadTakenSlots(String playerName, long snapshotId) {
        String cacheKey = takenKey(playerName, snapshotId);
        Set<Integer> cached = takenCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Set<Integer> result = new CopyOnWriteArraySet<>();
        String sql = "SELECT slot FROM claim_taken WHERE player_name = ? COLLATE NOCASE AND snapshot_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            stmt.setLong(2, snapshotId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getInt("slot"));
                }
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.taken-load-failed", ph));
        }

        takenCache.put(cacheKey, result);
        return result;
    }

    /**
     * 统计玩家在每份快照上已领取的格数（备份列表界面展示用）
     *
     * <p>包含数据库读取，请在异步线程调用。</p>
     *
     * @param playerName 玩家名
     * @return 快照主键 -&gt; 已领取格数（永不为 null）
     */
    public Map<Long, Integer> countTakenBySnapshot(String playerName) {
        Map<Long, Integer> result = new HashMap<>();
        String sql = "SELECT snapshot_id, COUNT(*) AS cnt FROM claim_taken " +
                "WHERE player_name = ? COLLATE NOCASE GROUP BY snapshot_id";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong("snapshot_id"), rs.getInt("cnt"));
                }
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.taken-load-failed", ph));
        }
        return result;
    }

    // ==================== 快照读取（按主键，索引稳定） ====================

    /**
     * 列出玩家某类型的全部快照元数据（不含序列化数据，查询轻量）
     *
     * <p>包含数据库读取，请在异步线程调用。</p>
     *
     * @param playerName 玩家名
     * @param type       快照类型
     * @return 按时间倒序排列的快照条目列表（永不为 null）
     */
    public List<SnapshotEntry> listSnapshots(String playerName, String type) {
        List<SnapshotEntry> list = new ArrayList<>();
        String sql = "SELECT id, player_name, created_at FROM inventory_snapshots " +
                "WHERE player_name = ? COLLATE NOCASE AND snapshot_type = ? ORDER BY created_at DESC, id DESC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            stmt.setString(2, type);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new SnapshotEntry(rs.getLong("id"), rs.getString("player_name"),
                            type, rs.getLong("created_at"), null));
                }
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.snapshot-query-failed", ph));
        }
        return list;
    }

    /**
     * 按主键读取快照（含序列化数据）
     *
     * <p>包含数据库读取，请在异步线程调用。</p>
     *
     * @param snapshotId 快照主键
     * @return 快照条目，不存在返回 null
     */
    public SnapshotEntry getSnapshotById(long snapshotId) {
        String sql = "SELECT id, player_name, snapshot_type, created_at, snapshot_data " +
                "FROM inventory_snapshots WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, snapshotId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new SnapshotEntry(rs.getLong("id"), rs.getString("player_name"),
                            rs.getString("snapshot_type"), rs.getLong("created_at"),
                            rs.getString("snapshot_data"));
                }
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.snapshot-query-failed", ph));
        }
        return null;
    }

    /**
     * 将快照序列化数据解析为「全局索引 -&gt; 物品」映射
     *
     * <p>三种类型的全局索引规则：</p>
     * <ul>
     *   <li>背包：0 ~ 44，对应 45 格临时容器</li>
     *   <li>末影箱：0 ~ 26，对应 27 格临时容器</li>
     *   <li>饰品栏：批次号 × 54 + 批内槽位，与保存时的分批规则一一对应</li>
     * </ul>
     * <p>索引由数据本身决定，不受清理、翻页影响，可安全用于持久化「已领取」标记。</p>
     *
     * <p>本方法调用 {@code Bukkit.createInventory}，与插件既有快照逻辑一致，
     * 在异步线程调用。</p>
     *
     * @param entry 含序列化数据的快照条目
     * @return 有序的「全局索引 -&gt; 物品」映射，解析失败返回空映射
     */
    public LinkedHashMap<Integer, ItemStack> parseSnapshotItems(SnapshotEntry entry) {
        LinkedHashMap<Integer, ItemStack> result = new LinkedHashMap<>();
        if (entry == null || entry.getData() == null || entry.getData().isEmpty()) {
            return result;
        }

        try {
            String type = entry.getType();

            if (DataManager.TYPE_CURIOS.equals(type)) {
                // 饰品栏：可能为分批格式，也可能是旧版单批格式
                String[] batches = entry.getData().contains(SeekInventory.CURIOS_BATCH_SEPARATOR)
                        ? entry.getData().split("\\|\\|\\|")
                        : new String[]{entry.getData()};

                for (int batch = 0; batch < batches.length; batch++) {
                    String batchData = batches[batch];
                    if (batchData == null || batchData.isEmpty()) {
                        continue;
                    }
                    Inventory temp = Bukkit.createInventory(null, 54);
                    ISerializer.deserializerInventory(temp, batchData);

                    for (int i = 0; i < 54; i++) {
                        ItemStack item = temp.getItem(i);
                        if (!IItem.isEmpty(item)) {
                            result.put(batch * 54 + i, item);
                        }
                    }
                }
            } else {
                int size = DataManager.TYPE_INVENTORY.equals(type) ? 45 : 27;
                Inventory temp = Bukkit.createInventory(null, size);
                ISerializer.deserializerInventory(temp, entry.getData());

                for (int i = 0; i < size; i++) {
                    ItemStack item = temp.getItem(i);
                    if (!IItem.isEmpty(item)) {
                        result.put(i, item);
                    }
                }
            }
        } catch (Exception e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage() != null ? e.getMessage() : "Unknown");
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.snapshot-parse-failed", ph));
            return new LinkedHashMap<>();
        }

        return result;
    }

    // ==================== 日志 ====================

    /**
     * 写入一条领取会话汇总日志
     *
     * <p>包含数据库写入，请在异步线程调用。</p>
     *
     * @param log 日志对象（id 字段忽略）
     * @return 入库后的自增主键，失败返回 -1
     */
    public long insertLog(ClaimLog log) {
        String sql = "INSERT INTO claim_logs (player_name, grant_type, snapshot_id, snapshot_time, " +
                "item_summary, item_count, remaining_after, claimed_at, reported) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, log.getPlayerName());
            stmt.setString(2, log.getGrantType());
            stmt.setLong(3, log.getSnapshotId());
            stmt.setLong(4, log.getSnapshotTime());
            stmt.setString(5, log.getItemSummary());
            stmt.setInt(6, log.getItemCount());
            stmt.setInt(7, log.getRemainingAfter());
            stmt.setLong(8, log.getClaimedAt());
            stmt.executeUpdate();

            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.log-save-failed", ph));
        }
        return -1L;
    }

    /**
     * 查询尚未上报给管理员的日志
     *
     * <p>包含数据库读取，请在异步线程调用。</p>
     *
     * @return 待上报日志列表，按时间正序
     */
    public List<ClaimLog> getUnreportedLogs() {
        List<ClaimLog> list = new ArrayList<>();
        String sql = "SELECT * FROM claim_logs WHERE reported = 0 ORDER BY claimed_at ASC LIMIT 200";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                list.add(readLog(rs));
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.log-query-failed", ph));
        }
        return list;
    }

    /**
     * 将指定日志标记为已上报
     *
     * <p>包含数据库写入，请在异步线程调用。</p>
     *
     * @param ids 日志主键列表
     */
    public void markReported(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String sql = "UPDATE claim_logs SET reported = 1 WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (Long id : ids) {
                if (id == null || id < 0) {
                    continue;
                }
                stmt.setLong(1, id);
                stmt.addBatch();
            }
            stmt.executeBatch();

        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.log-save-failed", ph));
        }
    }

    /**
     * 分页查询领取日志
     *
     * <p>包含数据库读取，请在异步线程调用。</p>
     *
     * @param playerName 玩家名，传 null 查询全服
     * @param page       页码，从 1 开始
     * @param pageSize   每页条数
     * @return 日志列表，按时间倒序
     */
    public List<ClaimLog> getLogs(String playerName, int page, int pageSize) {
        List<ClaimLog> list = new ArrayList<>();
        String sql = playerName == null
                ? "SELECT * FROM claim_logs ORDER BY claimed_at DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM claim_logs WHERE player_name = ? COLLATE NOCASE ORDER BY claimed_at DESC LIMIT ? OFFSET ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int idx = 1;
            if (playerName != null) {
                stmt.setString(idx++, playerName);
            }
            stmt.setInt(idx++, pageSize);
            stmt.setInt(idx, Math.max(0, (page - 1) * pageSize));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(readLog(rs));
                }
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.log-query-failed", ph));
        }
        return list;
    }

    /**
     * 统计日志总数（用于分页）
     *
     * <p>包含数据库读取，请在异步线程调用。</p>
     *
     * @param playerName 玩家名，传 null 统计全服
     * @return 日志总数
     */
    public int countLogs(String playerName) {
        String sql = playerName == null
                ? "SELECT COUNT(*) FROM claim_logs"
                : "SELECT COUNT(*) FROM claim_logs WHERE player_name = ? COLLATE NOCASE";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            if (playerName != null) {
                stmt.setString(1, playerName);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.log-query-failed", ph));
        }
        return 0;
    }

    /**
     * 从结果集读取一条日志
     *
     * @param rs 结果集
     * @return 日志对象
     * @throws SQLException 读取异常
     */
    private ClaimLog readLog(ResultSet rs) throws SQLException {
        return new ClaimLog(
                rs.getLong("id"),
                rs.getString("player_name"),
                rs.getString("grant_type"),
                rs.getLong("snapshot_id"),
                rs.getLong("snapshot_time"),
                rs.getString("item_summary"),
                rs.getInt("item_count"),
                rs.getInt("remaining_after"),
                rs.getLong("claimed_at"));
    }

    // ==================== 定期清理 ====================

    /**
     * 定期清理（由主类的每小时任务调用，异步线程）
     *
     * <p>清理内容：</p>
     * <ul>
     *   <li>已过期或额度耗尽的授权行</li>
     *   <li>超过保留天数且已上报的日志</li>
     *   <li>对应快照已被删除的孤儿「已领取」记录</li>
     *   <li>内存中失效的授权与已领取槽位缓存</li>
     * </ul>
     */
    public void cleanup() {
        long current = now();
        int keepDays = plugin.getConfigManager().getClaimLogKeepDays();
        long logCutoff = current - (long) keepDays * 24L * 3600L;

        try (Connection conn = databaseManager.getConnection()) {

            // 清理过期 / 耗尽的授权
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM claim_grants WHERE expire_at <= ? OR remaining <= 0")) {
                stmt.setLong(1, current);
                stmt.executeUpdate();
            }

            // 清理已上报的历史日志
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM claim_logs WHERE reported = 1 AND claimed_at < ?")) {
                stmt.setLong(1, logCutoff);
                stmt.executeUpdate();
            }

            // 清理孤儿已领取记录（对应快照已被快照清理任务删除）
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM claim_taken WHERE snapshot_id NOT IN " +
                        "(SELECT id FROM inventory_snapshots)");
            }

        } catch (SQLException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("claim.cleanup-failed", ph));
        }

        // 清理内存缓存
        grants.entrySet().removeIf(entry -> !entry.getValue().isUsable());
        if (takenCache.size() > 512) {
            // 已领取槽位缓存仅为读取加速，超过阈值直接整体丢弃，下次访问按需重建
            takenCache.clear();
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 生成授权缓存键
     *
     * @param playerName 玩家名
     * @param type       类型
     * @return 缓存键
     */
    private String key(String playerName, String type) {
        return playerName.toLowerCase(Locale.ROOT) + "|" + type;
    }

    /**
     * 生成已领取槽位缓存键
     *
     * @param playerName 玩家名
     * @param snapshotId 快照主键
     * @return 缓存键
     */
    private String takenKey(String playerName, long snapshotId) {
        return playerName.toLowerCase(Locale.ROOT) + "|" + snapshotId;
    }

    /**
     * 获取当前秒级时间戳
     *
     * @return 秒级时间戳
     */
    private long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /**
     * 获取全部支持的授权类型
     *
     * @return 类型列表（不可变）
     */
    public static List<String> allTypes() {
        return Collections.unmodifiableList(java.util.Arrays.asList(
                DataManager.TYPE_INVENTORY, DataManager.TYPE_ENDERCHEST, DataManager.TYPE_CURIOS));
    }
}
