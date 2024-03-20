package pers.tany.seekinventory.listenevent;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.seekinventory.Main;
import pers.tany.seekinventory.gui.OfflineEndInterface;
import pers.tany.seekinventory.gui.OfflineInterface;

import java.util.concurrent.ConcurrentHashMap;

public class Events implements Listener {

    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent evt) {
        Player player = evt.getPlayer();
        new BukkitRunnable() {

            @Override
            public void run() {
                if (OfflineInterface.offlineInv.containsKey(player.getName())) {
                    ConcurrentHashMap<Integer, ItemStack> concurrentHashMap = OfflineInterface.offlineInv.get(player.getName());
                    for (int index : concurrentHashMap.keySet()) {
                        int i = index;
                        if (index == 36) {
                            i = 39;
                        } else if (index == 37) {
                            i = 38;
                        } else if (index == 38) {
                            i = 37;
                        } else if (index == 39) {
                            i = 36;
                        }
                        try {
                            player.getInventory().setItem(i, concurrentHashMap.get(index));
                        } catch (Exception ignored) {

                        }
                    }
                    OfflineInterface.offlineInv.remove(player.getName());
                }
                if (OfflineEndInterface.offlineEndInv.containsKey(player.getName())) {
                    ConcurrentHashMap<Integer, ItemStack> concurrentHashMap = OfflineEndInterface.offlineEndInv.get(player.getName());
                    for (int index : concurrentHashMap.keySet()) {
                        player.getEnderChest().setItem(index, concurrentHashMap.get(index));
                    }
                    OfflineEndInterface.offlineEndInv.remove(player.getName());
                }
            }

        }.runTaskAsynchronously(Main.plugin);
    }
}

