// 文件：OnlineInterface.java
// 路径：src/main/java/com/flameforge/seekinventory/gui/OnlineInterface.java
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
import pers.tany.yukinoaapi.realizationpart.item.GlassPaneUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家背包界面
 * 实时同步玩家背包内容
 *
 * @author Tany天炎
 */
public class OnlineInterface implements InventoryHolder {

    /** 活跃GUI实例映射（查看者UUID -> GUI实例） */
    private static final Map<UUID, OnlineInterface> ACTIVE_GUIS = new ConcurrentHashMap<>();

    /** 静态监听器是否已注册 */
    private static boolean registered = false;

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 界面唯一标识 */
    private final String serial;

    /** 背包界面 */
    private final Inventory inventory;

    /** 查看者 */
    private final Player viewer;

    /** 目标玩家 */
    private final Player target;

    /** 同步任务ID */
    private int syncTaskId = -1;

    /** 占位符物品 - 橙色玻璃板 */
    private ItemStack placeholderItem;

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
        for (OnlineInterface gui : ACTIVE_GUIS.values()) {
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
    public OnlineInterface(Player viewer, Player target, SeekInventory plugin) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.target = target;
        this.serial = IRandom.createRandomString(8);

        // 创建界面标题
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", target.getName());
        String title = plugin.getLanguageManager().getMessage("gui.online-inventory-title", placeholders);

        this.inventory = Bukkit.createInventory(this, 45, IString.color(title));

        // 创建占位符物品
        createPlaceholderItem();

        // 初始加载背包内容
        syncInventoryFromPlayer();

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
     * 创建占位符物品（橙色玻璃板）
     */
    private void createPlaceholderItem() {
        String displayName = plugin.getLanguageManager().getMessage("gui.placeholder-name");
        List<String> loreList = plugin.getLanguageManager().getMessageList("gui.placeholder-lore");

        this.placeholderItem = GlassPaneUtil.getGlassPane(1) // 1 = 橙色
                .setDisplayName(displayName)
                .setLore(loreList)
                .getItemStack();
    }

    /**
     * 从玩家背包同步到界面
     */
    private void syncInventoryFromPlayer() {
        inventory.clear();

        // 同步背包内容（0-40槽位）
        for (int i = 0; i < 41; i++) {
            ItemStack itemStack = getPlayerSlot(i);
            if (!IItem.isEmpty(itemStack)) {
                inventory.setItem(i, itemStack);
            }
        }

        // 设置占位符（41-44槽位）
        for (int i = 41; i < 45; i++) {
            inventory.setItem(i, placeholderItem);
        }
    }

    /**
     * 从界面同步到玩家背包
     */
    private void syncInventoryToPlayer() {
        // 只同步0-40槽位，忽略占位符槽位
        for (int i = 0; i < 41; i++) {
            ItemStack itemStack = inventory.getItem(i);
            setPlayerSlot(i, itemStack);
        }
    }

    /**
     * 获取玩家背包槽位（处理装备槽映射）
     *
     * @param index 界面槽位
     * @return 物品
     */
    private ItemStack getPlayerSlot(int index) {
        try {
            if (index == 36) return target.getInventory().getItem(39);
            if (index == 37) return target.getInventory().getItem(38);
            if (index == 38) return target.getInventory().getItem(37);
            if (index == 39) return target.getInventory().getItem(36);
            return target.getInventory().getItem(index);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 设置玩家背包槽位（处理装备槽映射）
     *
     * @param index 界面槽位
     * @param itemStack 物品
     */
    private void setPlayerSlot(int index, ItemStack itemStack) {
        try {
            if (index == 36) {
                target.getInventory().setItem(39, itemStack);
            } else if (index == 37) {
                target.getInventory().setItem(38, itemStack);
            } else if (index == 38) {
                target.getInventory().setItem(37, itemStack);
            } else if (index == 39) {
                target.getInventory().setItem(36, itemStack);
            } else {
                target.getInventory().setItem(index, itemStack);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 启动同步任务
     */
    private void startSyncTask() {
        syncTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                syncInventoryFromPlayer();
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
        // 阻止点击占位符槽位（41-44）
        if (evt.getRawSlot() >= 41 && evt.getRawSlot() <= 44) {
            evt.setCancelled(true);
            viewer.sendMessage(IString.color(plugin.getLanguageManager().getMessage("gui.placeholder-click-blocked")));
            return;
        }

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
                syncInventoryToPlayer();
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
        viewer.sendMessage(IString.color(plugin.getLanguageManager().getMessage("gui.target-offline", placeholders)));

        // 延迟打开离线背包
        new BukkitRunnable() {
            @Override
            public void run() {
                OfflineInterface offlineInterface = new OfflineInterface(viewer, Bukkit.getOfflinePlayer(target.getName()), plugin);
                IInventory.openInventory(offlineInterface.getInventory(), viewer);
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

            if (!(evt.getInventory().getHolder() instanceof OnlineInterface)) {
                return;
            }

            Player player = (Player) evt.getWhoClicked();
            OnlineInterface gui = ACTIVE_GUIS.get(player.getUniqueId());
            if (gui != null) {
                gui.handleClick(evt);
            }
        }

        /**
         * 处理背包关闭事件
         */
        @EventHandler
        private void onInventoryClose(InventoryCloseEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof OnlineInterface)) {
                return;
            }

            if (!(evt.getPlayer() instanceof Player)) {
                return;
            }

            Player player = (Player) evt.getPlayer();
            UUID uuid = player.getUniqueId();
            OnlineInterface gui = ACTIVE_GUIS.get(uuid);

            if (gui != null) {
                // 通过 serial 比对确认是同一个 GUI
                OnlineInterface holder = (OnlineInterface) evt.getInventory().getHolder();
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

            for (OnlineInterface gui : ACTIVE_GUIS.values()) {
                if (gui.getTarget().getName().equals(quitName)) {
                    gui.handlePlayerQuit(evt);
                    // handlePlayerQuit 中 closeInventory 会触发 onInventoryClose 自动移除
                    break;
                }
            }
        }
    }
}