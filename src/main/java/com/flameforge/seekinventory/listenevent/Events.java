package com.flameforge.seekinventory.listenevent;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.claim.ClaimGrant;
import com.flameforge.seekinventory.gui.ClaimMenuInterface;
import com.flameforge.seekinventory.gui.OfflineEndInterface;
import com.flameforge.seekinventory.gui.OfflineInterface;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.yukinoaapi.interfacepart.other.IString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件监听器
 * 处理玩家加入时的离线编辑同步
 *
 * @author Tany天炎
 */
public class Events implements Listener {

    /** 插件实例 */
    private final SeekInventory plugin;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public Events(SeekInventory plugin) {
        this.plugin = plugin;
    }

    /**
     * 处理玩家加入事件
     * 同步离线时被编辑的背包和末影箱数据
     */
    /**
     * 还有没用完的领取额度的玩家进服时提醒一次（进服 3 秒后发，避开登录插件、公告刷屏）
     */
    @EventHandler
    private void onClaimReminder(PlayerJoinEvent evt) {
        if (!plugin.getConfigManager().isClaimJoinReminder() || plugin.getClaimManager() == null) {
            return;
        }
        final Player player = evt.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                List<ClaimGrant> grants = plugin.getClaimManager().getUsableGrants(player.getName());
                if (grants.isEmpty()) {
                    return;
                }
                StringBuilder detail = new StringBuilder();
                for (ClaimGrant grant : grants) {
                    if (detail.length() > 0) {
                        detail.append("§7、");
                    }
                    detail.append("§a").append(plugin.getClaimReporter().getTypeDisplay(grant.getGrantType()))
                            .append(" §e").append(grant.getRemaining()).append("§7");
                }
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("detail", detail.toString());
                player.sendMessage(IString.color(
                        plugin.getLanguageManager().getMessage("claim.join-reminder", placeholders)));
                ClaimMenuInterface.sendOpenButton(plugin, player, grants.get(0).getGrantType());
            }
        }.runTaskLater(plugin, 60L);
    }

    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent evt) {
        Player player = evt.getPlayer();

        new BukkitRunnable() {
            @Override
            public void run() {
                // 同步离线背包编辑
                if (OfflineInterface.offlineInv.containsKey(player.getName())) {
                    ConcurrentHashMap<Integer, ItemStack> invData = OfflineInterface.offlineInv.get(player.getName());
                    syncInventory(player, invData);
                    OfflineInterface.offlineInv.remove(player.getName());

                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", player.getName());
                    plugin.sendMessage(plugin.getLanguageManager().getMessage("offline.sync-inventory-success", placeholders));
                }

                // 同步离线末影箱编辑
                if (OfflineEndInterface.offlineEndInv.containsKey(player.getName())) {
                    ConcurrentHashMap<Integer, ItemStack> endData = OfflineEndInterface.offlineEndInv.get(player.getName());
                    syncEnderChest(player, endData);
                    OfflineEndInterface.offlineEndInv.remove(player.getName());

                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", player.getName());
                    plugin.sendMessage(plugin.getLanguageManager().getMessage("offline.sync-enderchest-success", placeholders));
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 同步背包数据
     *
     * @param player 玩家
     * @param invData 背包数据映射
     */
    private void syncInventory(Player player, ConcurrentHashMap<Integer, ItemStack> invData) {
        for (int index : invData.keySet()) {
            int targetSlot = convertSlotIndex(index);
            try {
                player.getInventory().setItem(targetSlot, invData.get(index));
            } catch (Exception e) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("slot", String.valueOf(index));
                placeholders.put("error", e.getMessage());
                plugin.sendError(plugin.getLanguageManager().getMessage("offline.sync-inventory-slot-failed", placeholders));
            }
        }
    }

    /**
     * 同步末影箱数据
     *
     * @param player 玩家
     * @param endData 末影箱数据映射
     */
    private void syncEnderChest(Player player, ConcurrentHashMap<Integer, ItemStack> endData) {
        for (int index : endData.keySet()) {
            try {
                player.getEnderChest().setItem(index, endData.get(index));
            } catch (Exception e) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("slot", String.valueOf(index));
                placeholders.put("error", e.getMessage());
                plugin.sendError(plugin.getLanguageManager().getMessage("offline.sync-enderchest-slot-failed", placeholders));
            }
        }
    }

    /**
     * 转换槽位索引（处理装备槽映射）
     *
     * @param index 原始索引
     * @return 转换后的索引
     */
    private int convertSlotIndex(int index) {
        if (index == 36) return 39;
        if (index == 37) return 38;
        if (index == 38) return 37;
        if (index == 39) return 36;
        return index;
    }
}