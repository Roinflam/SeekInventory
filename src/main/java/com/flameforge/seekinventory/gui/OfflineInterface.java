// 文件：OfflineInterface.java
// 路径：src/main/java/com/flameforge/seekinventory/gui/OfflineInterface.java
package com.flameforge.seekinventory.gui;

import com.flameforge.seekinventory.SeekInventory;
import de.tr7zw.nbtapi.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.other.IRandom;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.realizationpart.builder.ItemBuilder;
import pers.tany.yukinoaapi.realizationpart.item.GlassPaneUtil;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线玩家背包界面
 * 通过 NBT 读取离线玩家数据
 *
 * @author Tany天炎
 */
public class OfflineInterface implements InventoryHolder {

    /** 活跃GUI实例映射（查看者UUID -> GUI实例） */
    private static final Map<UUID, OfflineInterface> ACTIVE_GUIS = new ConcurrentHashMap<>();

    /** 静态监听器是否已注册 */
    private static boolean registered = false;

    /** 离线编辑缓存（玩家名 -> 背包数据） */
    public static ConcurrentHashMap<String, ConcurrentHashMap<Integer, ItemStack>> offlineInv = new ConcurrentHashMap<>();

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 界面唯一标识 */
    private final String serial;

    /** 背包界面 */
    private final Inventory inventory;

    /** 查看者 */
    private final Player viewer;

    /** 离线玩家 */
    private final OfflinePlayer offlinePlayer;

    /** 玩家数据文件 */
    private final File playerData;

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
        ACTIVE_GUIS.clear();
        registered = false;
    }

    /**
     * 构造函数
     *
     * @param viewer 查看者
     * @param offlinePlayer 离线玩家
     * @param plugin 插件实例
     */
    public OfflineInterface(Player viewer, OfflinePlayer offlinePlayer, SeekInventory plugin) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.offlinePlayer = offlinePlayer;
        this.serial = IRandom.createRandomString(8);

        // 创建界面标题
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", offlinePlayer.getName());
        String title = plugin.getLanguageManager().getMessage("gui.offline-inventory-title", placeholders);

        this.inventory = Bukkit.createInventory(this, 45, IString.color(title));

        // 获取玩家数据文件路径
        File playerDataFolder = new File(plugin.getDataFolder().getParentFile().getParentFile(), "world/playerdata/");
        playerData = new File(playerDataFolder, offlinePlayer.getUniqueId().toString() + ".dat");

        // 创建占位符物品
        createPlaceholderItem();

        // 加载背包数据
        loadInventoryData();

        // 设置占位符（41-44槽位）
        for (int i = 41; i < 45; i++) {
            inventory.setItem(i, placeholderItem);
        }

        // 确保静态监听器已注册，然后加入活跃映射（自动覆盖旧实例）
        registerListener(plugin);
        ACTIVE_GUIS.put(viewer.getUniqueId(), this);
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
     * 加载背包数据
     */
    private void loadInventoryData() {
        // 优先从缓存加载
        if (offlineInv.containsKey(offlinePlayer.getName())) {
            ConcurrentHashMap<Integer, ItemStack> cachedData = offlineInv.get(offlinePlayer.getName());
            for (int index : cachedData.keySet()) {
                inventory.setItem(index, cachedData.get(index));
            }
            return;
        }

        // 从 NBT 文件加载
        try {
            NBTFile nbtFile = new NBTFile(playerData);

            for (NBTCompound nbtCompound : nbtFile.getCompoundList("Inventory")) {
                int index = nbtCompound.getInteger("Slot");
                ItemStack itemStack = NBTItem.convertNBTtoItem(nbtCompound);

                // 映射槽位
                if (index < 36 && index > -1) {
                    inventory.setItem(index, itemStack);
                } else if (index == 100) {
                    inventory.setItem(39, itemStack);
                } else if (index == 101) {
                    inventory.setItem(38, itemStack);
                } else if (index == 102) {
                    inventory.setItem(37, itemStack);
                } else if (index == 103) {
                    inventory.setItem(36, itemStack);
                } else if (index == -106) {
                    inventory.setItem(40, itemStack);
                }
            }
        } catch (Exception e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", offlinePlayer.getName());
            placeholders.put("error", e.getMessage());
            plugin.sendError(plugin.getLanguageManager().getMessage("offline.load-inventory-failed", placeholders));
            e.printStackTrace();
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
        }
    }

    /**
     * 处理背包关闭（由静态监听器路由调用）
     *
     * @param evt 关闭事件
     */
    private void handleClose(InventoryCloseEvent evt) {
        saveInventoryData();
    }

    /**
     * 保存背包数据
     */
    private void saveInventoryData() {
        try {
            NBTFile nbtFile = new NBTFile(playerData);
            NBTCompoundList nbtListCompounds = nbtFile.getCompoundList("Inventory");
            nbtListCompounds.clear();

            // 只保存0-40槽位，忽略占位符槽位（41-44）
            for (int i = 0; i < 41; i++) {
                ItemStack itemStack = inventory.getItem(i);
                if (!IItem.isEmpty(itemStack)) {
                    int slot = convertToNBTSlot(i);
                    NBTCompound nbtCompound = new NBTContainer(IItem.getItemNBT(itemStack));
                    nbtCompound.setInteger("Slot", slot);
                    nbtListCompounds.addCompound(nbtCompound);
                }
            }

            nbtFile.save();

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", offlinePlayer.getName());
            plugin.sendMessage(plugin.getLanguageManager().getMessage("offline.save-inventory-success", placeholders));

        } catch (Exception e) {
            // 保存失败，使用缓存
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", offlinePlayer.getName());
            placeholders.put("error", e.getMessage());
            plugin.sendWarning(plugin.getLanguageManager().getMessage("offline.save-inventory-failed", placeholders));

            ConcurrentHashMap<Integer, ItemStack> cachedData = new ConcurrentHashMap<>();
            // 只缓存0-40槽位
            for (int i = 0; i < 41; i++) {
                ItemStack item = inventory.getItem(i);
                cachedData.put(i, item == null ? new ItemBuilder(Material.AIR).getItemStack() : item);
            }
            offlineInv.put(offlinePlayer.getName(), cachedData);
        }
    }

    /**
     * 转换界面槽位为 NBT 槽位
     *
     * @param index 界面槽位
     * @return NBT 槽位
     */
    private int convertToNBTSlot(int index) {
        if (index < 36) return index;
        if (index == 36) return 103;
        if (index == 37) return 102;
        if (index == 38) return 101;
        if (index == 39) return 100;
        if (index == 40) return -106;
        return 999;
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
     * 获取查看者
     *
     * @return 查看者
     */
    public Player getViewer() {
        return viewer;
    }

    /**
     * 获取离线玩家
     *
     * @return 离线玩家
     */
    public OfflinePlayer getOfflinePlayer() {
        return offlinePlayer;
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

            if (!(evt.getInventory().getHolder() instanceof OfflineInterface)) {
                return;
            }

            Player player = (Player) evt.getWhoClicked();
            OfflineInterface gui = ACTIVE_GUIS.get(player.getUniqueId());
            if (gui != null) {
                gui.handleClick(evt);
            }
        }

        /**
         * 处理背包关闭事件
         */
        @EventHandler
        private void onInventoryClose(InventoryCloseEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof OfflineInterface)) {
                return;
            }

            if (!(evt.getPlayer() instanceof Player)) {
                return;
            }

            Player player = (Player) evt.getPlayer();
            UUID uuid = player.getUniqueId();
            OfflineInterface gui = ACTIVE_GUIS.get(uuid);

            if (gui != null) {
                // 通过 serial 比对确认是同一个 GUI
                OfflineInterface holder = (OfflineInterface) evt.getInventory().getHolder();
                if (holder.getSerial().equals(gui.getSerial())) {
                    ACTIVE_GUIS.remove(uuid);
                    gui.handleClose(evt);
                }
            }
        }

        /**
         * 处理玩家登录事件
         * 遍历所有活跃实例，阻止正在被编辑的离线玩家登录
         */
        @EventHandler
        private void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent evt) {
            for (OfflineInterface gui : ACTIVE_GUIS.values()) {
                if (gui.getViewer().isOp() && evt.getName().equals(gui.getOfflinePlayer().getName())) {
                    evt.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
                    evt.setKickMessage(IString.color(gui.plugin.getLanguageManager().getMessage("gui.editing-kick")));
                    break;
                }
            }
        }
    }
}