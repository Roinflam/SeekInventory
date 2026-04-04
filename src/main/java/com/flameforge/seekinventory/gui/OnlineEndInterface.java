// 文件：OnlineEndInterface.java
// 路径：src/main/java/com/flameforge/seekinventory/gui/OnlineEndInterface.java
package com.flameforge.seekinventory.gui;

import com.flameforge.seekinventory.SeekInventory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventory;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.other.IRandom;
import pers.tany.yukinoaapi.interfacepart.other.IString;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家末影箱界面
 * 实时同步玩家末影箱内容
 *
 * @author Tany天炎
 */
public class OnlineEndInterface implements InventoryHolder {

    /** 活跃GUI实例映射（查看者UUID -> GUI实例） */
    private static final Map<UUID, OnlineEndInterface> ACTIVE_GUIS = new ConcurrentHashMap<>();

    /** 静态监听器是否已注册 */
    private static boolean registered = false;

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 界面唯一标识 */
    private final String serial;

    /** 末影箱界面 */
    private final Inventory inventory;

    /** 查看者 */
    private final Player viewer;

    /** 目标玩家 */
    private final Player target;

    /** 同步任务ID */
    private int syncTaskId = -1;

    /**
     * 注册静态监听器（仅首次调用生效）
     *
     * @param plugin 插件实例
     */
    public static void registerListener(Plugin plugin) {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new GUIListener(), plugin);
            registered = true;
        }
    }

    /**
     * 清理所有活跃实例并重置注册状态
     */
    public static void cleanup() {
        // 停止所有活跃实例的同步任务
        for (OnlineEndInterface gui : ACTIVE_GUIS.values()) {
            gui.stopSyncTask();
        }
        ACTIVE_GUIS.clear();
        registered = false;
    }

    /**
     * 构造函数
     *
     * @param viewer 查看者
     * @param target 目标玩家
     * @param plugin 插件实例
     */
    public OnlineEndInterface(Player viewer, Player target, SeekInventory plugin) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.target = target;
        this.serial = IRandom.createRandomString(8);

        // 创建界面标题
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", target.getName());
        String title = plugin.getLanguageManager().getMessage("gui.online-enderchest-title", placeholders);

        this.inventory = Bukkit.createInventory(this, 27, IString.color(title));

        // 初始加载末影箱内容
        syncEnderChestFromPlayer();

        // 确保静态监听器已注册，然后加入活跃映射（自动覆盖旧实例）
        registerListener(plugin);
        ACTIVE_GUIS.put(viewer.getUniqueId(), this);

        // 启动定时同步任务
        startSyncTask();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 从玩家末影箱同步到界面
     */
    private void syncEnderChestFromPlayer() {
        inventory.clear();
        for (int i = 0; i < 27; i++) {
            ItemStack itemStack = target.getEnderChest().getItem(i);
            if (!IItem.isEmpty(itemStack)) {
                inventory.setItem(i, itemStack);
            }
        }
    }

    /**
     * 从界面同步到玩家末影箱
     */
    private void syncEnderChestToPlayer() {
        for (int i = 0; i < 27; i++) {
            target.getEnderChest().setItem(i, inventory.getItem(i));
        }
    }

    /**
     * 启动同步任务
     */
    private void startSyncTask() {
        syncTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                syncEnderChestFromPlayer();
            }
        }.runTaskTimerAsynchronously(plugin, 5, 5).getTaskId();
    }

    /**
     * 停止同步任务
     */
    private void stopSyncTask() {
        if (syncTaskId != -1) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
            syncTaskId = -1;
        }
    }

    /**
     * 处理背包点击（由静态监听器路由调用）
     *
     * @param evt 点击事件
     */
    private void handleClick(InventoryClickEvent evt) {
        // 检查编辑权限
        if (!viewer.hasPermission("si.edit")) {
            evt.setCancelled(true);
            viewer.sendMessage(IString.color(plugin.getLanguageManager().getMessage("gui.no-edit-permission")));
            return;
        }

        // 暂停同步，执行编辑，然后恢复同步
        stopSyncTask();

        new BukkitRunnable() {
            @Override
            public void run() {
                syncEnderChestToPlayer();
            }
        }.runTask(plugin);

        startSyncTask();
    }

    /**
     * 处理背包关闭（由静态监听器路由调用）
     *
     * @param evt 关闭事件
     */
    private void handleClose(InventoryCloseEvent evt) {
        stopSyncTask();
    }

    /**
     * 处理目标玩家下线（由静态监听器路由调用）
     *
     * @param evt 退出事件
     */
    private void handlePlayerQuit(PlayerQuitEvent evt) {
        viewer.closeInventory();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", target.getName());
        viewer.sendMessage(IString.color(plugin.getLanguageManager().getMessage("gui.target-offline-end", placeholders)));

        // 延迟打开离线末影箱
        new BukkitRunnable() {
            @Override
            public void run() {
                OfflineEndInterface offlineEndInterface = new OfflineEndInterface(viewer, Bukkit.getOfflinePlayer(target.getName()), plugin);
                IInventory.openInventory(offlineEndInterface.getInventory(), viewer);
            }
        }.runTaskLater(plugin, 10);
    }

    /**
     * 获取界面唯一标识
     *
     * @return 唯一标识
     */
    public String getSerial() {
        return serial;
    }

    /**
     * 获取目标玩家
     *
     * @return 目标玩家
     */
    public Player getTarget() {
        return target;
    }

    /**
     * 静态内部监听器
     * 全局只注册一次，通过 ACTIVE_GUIS 路由到具体实例
     */
    private static class GUIListener implements Listener {

        /**
         * 处理背包点击事件
         */
        @EventHandler(priority = EventPriority.LOWEST)
        private void onInventoryClick(InventoryClickEvent evt) {
            if (!(evt.getWhoClicked() instanceof Player)) {
                return;
            }

            if (evt.getRawSlot() == -999) {
                return;
            }

            if (!(evt.getInventory().getHolder() instanceof OnlineEndInterface)) {
                return;
            }

            Player player = (Player) evt.getWhoClicked();
            OnlineEndInterface gui = ACTIVE_GUIS.get(player.getUniqueId());
            if (gui != null) {
                gui.handleClick(evt);
            }
        }

        /**
         * 处理背包关闭事件
         */
        @EventHandler
        private void onInventoryClose(InventoryCloseEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof OnlineEndInterface)) {
                return;
            }

            if (!(evt.getPlayer() instanceof Player)) {
                return;
            }

            Player player = (Player) evt.getPlayer();
            UUID uuid = player.getUniqueId();
            OnlineEndInterface gui = ACTIVE_GUIS.get(uuid);

            if (gui != null) {
                // 通过 serial 比对确认是同一个 GUI
                OnlineEndInterface holder = (OnlineEndInterface) evt.getInventory().getHolder();
                if (holder.getSerial().equals(gui.getSerial())) {
                    ACTIVE_GUIS.remove(uuid);
                    gui.handleClose(evt);
                }
            }
        }

        /**
         * 处理目标玩家下线事件
         * 遍历所有活跃实例，找到目标匹配的 GUI
         */
        @EventHandler
        private void onPlayerQuit(PlayerQuitEvent evt) {
            String quitName = evt.getPlayer().getName();

            for (OnlineEndInterface gui : ACTIVE_GUIS.values()) {
                if (gui.getTarget().getName().equals(quitName)) {
                    gui.handlePlayerQuit(evt);
                    // handlePlayerQuit 中 closeInventory 会触发 onInventoryClose 自动移除
                    break;
                }
            }
        }
    }
}