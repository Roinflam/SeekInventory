package com.flameforge.seekinventory;

import com.flameforge.seekinventory.command.SeekTabComplete;
import com.flameforge.seekinventory.curios.CuriosHandler;
import com.flameforge.seekinventory.curios.CuriosSupport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.flameforge.seekinventory.bstats.Metrics;
import com.flameforge.seekinventory.command.Commands;
import com.flameforge.seekinventory.command.Inv;
import com.flameforge.seekinventory.command.InvTabComplete;
import com.flameforge.seekinventory.config.ConfigManager;
import com.flameforge.seekinventory.config.DataManager;
import com.flameforge.seekinventory.config.LanguageManager;
import com.flameforge.seekinventory.listenevent.Events;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.interfacepart.register.IRegister;
import pers.tany.yukinoaapi.interfacepart.serializer.ISerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SeekInventory 插件主类
 * 功能：查看和管理玩家背包/末影箱/饰品栏，支持历史快照（SQLite + 缓存优化）
 *
 * @author Tany天炎
 * @version 3.0.0
 */
public class SeekInventory extends JavaPlugin {

    /** 插件实例 */
    private static SeekInventory instance;

    /** 配置管理器 */
    private ConfigManager configManager;

    /** 语言管理器 */
    private LanguageManager languageManager;

    /** 数据管理器 */
    private DataManager dataManager;

    /** 定时保存任务ID */
    private int autoSaveTaskId = -1;

    /** 定期清理任务ID */
    private int cleanupTaskId = -1;

    /** bStats统计对象 */
    private Metrics metrics;

    /** bStats服务ID */
    private static final int BSTATS_PLUGIN_ID = 29016;

    /** Curios 分批序列化分隔符 */
    public static final String CURIOS_BATCH_SEPARATOR = "|||";

    /**
     * 插件启用时执行
     */
    @Override
    public void onEnable() {
        instance = this;

        // 初始化语言管理器
        languageManager = new LanguageManager(this);
        languageManager.loadLanguage();

        // 初始化配置管理器
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 初始化数据管理器（SQLite + 缓存）
        dataManager = new DataManager(this);
        dataManager.initialize();

        // 注册命令
        Commands commands = new Commands(this);
        Inv invCommand = new Inv(this);

        // 创建独立的 Tab 补全
        SeekTabComplete seekTabComplete = new SeekTabComplete(this);
        InvTabComplete invTabComplete = new InvTabComplete(this);

        IRegister.registerCommands(this, "SeekInventory", commands);
        IRegister.registerCommands(this, "Inv", invCommand);

        // 注册独立的 Tab 补全
        if (this.getCommand("SeekInventory") != null) {
            this.getCommand("SeekInventory").setTabCompleter(seekTabComplete);
        }
        if (this.getCommand("Inv") != null) {
            this.getCommand("Inv").setTabCompleter(invTabComplete);
        }

        // 注册事件监听器
        IRegister.registerEvents(this, new Events(this));

        // 启动定时保存任务
        startAutoSaveTask();

        // 启动定期清理任务
        startCleanupTask();

        // 初始化bStats统计
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        // 检测 Curios 模组
        if (CuriosSupport.isAvailable()) {
            sendMessage(languageManager.getMessage("console.curios-detected"));
        }

        // 发送启用消息
        sendMessage(languageManager.getMessage("plugin.enabled"));
    }

    /**
     * 插件禁用时执行
     */
    @Override
    public void onDisable() {
        // 停止定时保存任务
        stopAutoSaveTask();

        // 停止定期清理任务
        stopCleanupTask();

        // 关闭数据库连接
        if (dataManager != null) {
            dataManager.close();
        }

        // 发送禁用消息
        if (languageManager != null) {
            sendMessage(languageManager.getMessage("plugin.disabled"));
        }
    }

    /**
     * 启动自动保存任务
     */
    private void startAutoSaveTask() {
        if (!configManager.isAutoSave()) {
            sendMessage(languageManager.getMessage("console.auto-save-disabled"));
            return;
        }

        int intervalTicks = configManager.getSaveInterval() * 60 * 20; // 分钟转刻

        autoSaveTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                saveAllSnapshots();
            }
        }.runTaskTimerAsynchronously(this, intervalTicks, intervalTicks).getTaskId();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("interval", String.valueOf(configManager.getSaveInterval()));
        sendMessage(languageManager.getMessage("console.auto-save-started", placeholders));
    }

    /**
     * 停止自动保存任务
     */
    private void stopAutoSaveTask() {
        if (autoSaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoSaveTaskId);
            autoSaveTaskId = -1;
        }
    }

    /**
     * 启动定期清理任务（每小时执行一次）
     * 同时执行按数量清理和按天数清理
     */
    private void startCleanupTask() {
        cleanupTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                // 按数量清理（每位玩家保留 maxSnapshots 条）
                dataManager.cleanAllOldSnapshots();

                // 按天数清理（删除超过 autoCleanDays 天的快照）
                int autoCleanDays = configManager.getAutoCleanDays();
                dataManager.cleanExpiredSnapshots(autoCleanDays);
            }
        }.runTaskTimerAsynchronously(this, 72000, 72000).getTaskId(); // 每小时（3600秒 * 20 tick）
    }

    /**
     * 停止定期清理任务
     */
    private void stopCleanupTask() {
        if (cleanupTaskId != -1) {
            Bukkit.getScheduler().cancelTask(cleanupTaskId);
            cleanupTaskId = -1;
        }
    }

    /**
     * 保存所有在线玩家的快照（批量处理，避免连接池耗尽）
     */
    public void saveAllSnapshots() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }

        // 使用单个异步任务批量处理，避免并发过高
        new BukkitRunnable() {
            @Override
            public void run() {
                int successCount = 0;
                long totalStartTime = System.currentTimeMillis();

                for (Player player : players) {
                    try {
                        savePlayerSnapshotSync(player);
                        successCount++;
                    } catch (Exception e) {
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("player", player.getName());
                        placeholders.put("error", e.getMessage());
                        sendError(languageManager.getMessage("snapshot.save-player-failed", placeholders));
                    }
                }

                long totalElapsed = System.currentTimeMillis() - totalStartTime;

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("count", String.valueOf(successCount));
                placeholders.put("time", String.valueOf(totalElapsed));
                sendMessage(languageManager.getMessage("snapshot.batch-save-complete", placeholders));

                // 在主线程广播消息
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        String message = languageManager.getMessage("snapshot.auto-save-complete");
                        Bukkit.broadcastMessage(IString.color(message));
                    }
                }.runTask(SeekInventory.this);
            }
        }.runTaskAsynchronously(this);
    }

    /**
     * 同步保存单个玩家的快照（在异步任务中调用，不再嵌套异步）
     * 包含背包、末影箱，以及 Curios 饰品栏（如果 Curios 模组存在）
     *
     * @param player 玩家
     */
    private void savePlayerSnapshotSync(Player player) throws Exception {
        long startTime = System.currentTimeMillis();

        // 创建临时背包用于序列化
        Inventory invTemp = Bukkit.createInventory(null, 45);
        Inventory endTemp = Bukkit.createInventory(null, 27);

        // 复制背包内容（处理装备槽映射）
        for (int i = 0; i < 41; i++) {
            ItemStack itemStack = null;
            if (i == 36) {
                itemStack = player.getInventory().getItem(39);
            } else if (i == 37) {
                itemStack = player.getInventory().getItem(38);
            } else if (i == 38) {
                itemStack = player.getInventory().getItem(37);
            } else if (i == 39) {
                itemStack = player.getInventory().getItem(36);
            } else {
                try {
                    itemStack = player.getInventory().getItem(i);
                } catch (Exception ignored) {
                }
            }
            invTemp.setItem(i, itemStack);
        }

        // 复制末影箱内容
        for (int i = 0; i < 27; i++) {
            endTemp.setItem(i, player.getEnderChest().getItem(i));
        }

        // 序列化并保存背包和末影箱
        String invData = ISerializer.serializerInventory(invTemp);
        String endData = ISerializer.serializerInventory(endTemp);

        dataManager.saveSnapshot(player.getName(), DataManager.TYPE_INVENTORY, invData);
        dataManager.saveSnapshot(player.getName(), DataManager.TYPE_ENDERCHEST, endData);

        // ========== Curios 饰品栏快照（可选，支持任意数量） ==========
        if (CuriosSupport.isAvailable()) {
            try {
                List<ItemStack> curiosItems = CuriosHandler.getCuriosItems(player);

                if (!curiosItems.isEmpty()) {
                    // 分批序列化：每批最多54个物品，用分隔符拼接
                    // 这样可以保存任意数量的饰品，不再受54个限制
                    StringBuilder curiosDataBuilder = new StringBuilder();
                    int totalItems = curiosItems.size();
                    int batchCount = (totalItems + 53) / 54; // 向上取整

                    for (int batch = 0; batch < batchCount; batch++) {
                        int batchStart = batch * 54;
                        int batchEnd = Math.min(batchStart + 54, totalItems);
                        int batchSize = batchEnd - batchStart;

                        // 计算本批次的Inventory大小（向上取整到9的倍数）
                        int invSize = calculateInventorySize(batchSize);
                        Inventory curiosTemp = Bukkit.createInventory(null, invSize);

                        for (int i = 0; i < batchSize; i++) {
                            curiosTemp.setItem(i, curiosItems.get(batchStart + i));
                        }

                        if (batch > 0) {
                            curiosDataBuilder.append(CURIOS_BATCH_SEPARATOR);
                        }
                        curiosDataBuilder.append(ISerializer.serializerInventory(curiosTemp));
                    }

                    String curiosData = curiosDataBuilder.toString();
                    dataManager.saveSnapshot(player.getName(), DataManager.TYPE_CURIOS, curiosData);
                }
            } catch (Exception e) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("error", e.getMessage() != null ? e.getMessage() : "Unknown");
                sendWarning(languageManager.getMessage("snapshot.curios-save-failed", placeholders));
            }
        }
        // ========== Curios 饰品栏快照结束 ==========

        long elapsed = System.currentTimeMillis() - startTime;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("time", String.valueOf(elapsed));
        sendMessage(languageManager.getMessage("snapshot.save-player-success", placeholders));
    }

    /**
     * 计算序列化用的库存大小（向上取整到 9 的倍数，范围 9-54）
     *
     * @param itemCount 物品数量
     * @return 库存大小
     */
    private int calculateInventorySize(int itemCount) {
        if (itemCount <= 0) return 9;
        int size = ((itemCount + 8) / 9) * 9;
        return Math.min(54, Math.max(9, size));
    }

    /**
     * 保存单个玩家的快照（保留原有接口，用于手动保存）
     *
     * @param player 玩家
     */
    public void savePlayerSnapshot(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    savePlayerSnapshotSync(player);
                } catch (Exception e) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", player.getName());
                    placeholders.put("error", e.getMessage());
                    sendError(languageManager.getMessage("snapshot.save-player-failed", placeholders));
                    e.printStackTrace();
                }
            }
        }.runTaskAsynchronously(this);
    }

    /**
     * 发送控制台消息
     *
     * @param message 消息内容
     */
    public void sendMessage(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /**
     * 发送警告消息
     *
     * @param message 消息内容
     */
    public void sendWarning(String message) {
        if (languageManager != null) {
            String prefix = languageManager.getMessage("console.warning-prefix");
            Bukkit.getConsoleSender().sendMessage(prefix + message);
        } else {
            Bukkit.getConsoleSender().sendMessage("§e[WARNING] " + message);
        }
    }

    /**
     * 发送错误消息
     *
     * @param message 消息内容
     */
    public void sendError(String message) {
        if (languageManager != null) {
            String prefix = languageManager.getMessage("console.error-prefix");
            Bukkit.getConsoleSender().sendMessage(prefix + message);
        } else {
            Bukkit.getConsoleSender().sendMessage("§c[ERROR] " + message);
        }
    }

    // Getter 方法

    public static SeekInventory getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }
}