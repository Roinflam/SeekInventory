package pers.tany.seekinventory.gui;

import de.tr7zw.nbtapi.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import pers.tany.seekinventory.Main;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.other.IRandom;
import pers.tany.yukinoaapi.realizationpart.builder.ItemBuilder;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

public class OfflineInterface implements InventoryHolder, Listener {
    private final String serial;
    public static ConcurrentHashMap<String, ConcurrentHashMap<Integer, ItemStack>> offlineInv = new ConcurrentHashMap<>();
    private final Inventory inventory;
    private final Player player;
    private final OfflinePlayer offlinePlayer;
    private final File playerData;

    public OfflineInterface(Player player, OfflinePlayer offlinePlayer) {
        Inventory inventory = Bukkit.createInventory(this, 45, "§8" + offlinePlayer.getName() + "背包");
        File playerDataFolder = new File(Main.plugin.getDataFolder().getParentFile().getParentFile(), "world\\playerdata\\");
        playerData = new File(playerDataFolder, offlinePlayer.getUniqueId().toString() + ".dat");
        if (offlineInv.containsKey(offlinePlayer.getName())) {
            ConcurrentHashMap<Integer, ItemStack> concurrentHashMap = offlineInv.get(offlinePlayer.getName());
            for (int index : concurrentHashMap.keySet()) {
                inventory.setItem(index, concurrentHashMap.get(index));
            }
        } else {
            try {
                NBTFile nbtFile = new NBTFile(playerData);
                for (NBTCompound nbtCompound : nbtFile.getCompoundList("Inventory")) {
                    int index = nbtCompound.getInteger("Slot");
                    ItemStack itemStack = NBTItem.convertNBTtoItem(nbtCompound);
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
                e.printStackTrace();
            }
        }

        this.inventory = inventory;
        this.player = player;
        this.offlinePlayer = offlinePlayer;
        this.serial = IRandom.createRandomString(8);

        Bukkit.getPluginManager().registerEvents(this, Main.plugin);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onInventoryClick(InventoryClickEvent evt) {
        if (evt.getWhoClicked() instanceof Player && evt.getWhoClicked().equals(player)) {
            int rawSlot = evt.getRawSlot();
            if (rawSlot != -999) {
                if (evt.getInventory().getHolder() instanceof OfflineInterface) {
                    if (!player.hasPermission("si.edit")) {
                        evt.setCancelled(true);
                        player.sendMessage("§c你没有权限编辑背包");
                    }
                }
            }
        }
    }

    public String getSerial() {
        return serial;
    }


    @EventHandler
    private void onInventoryClose(InventoryCloseEvent evt) {
        if (evt.getInventory().getHolder() instanceof OfflineInterface && evt.getPlayer() instanceof Player) {
            OfflineInterface offlineInterface = (OfflineInterface) evt.getInventory().getHolder();
            if (evt.getPlayer().equals(player) && offlineInterface.getSerial().equals(serial)) {
                HandlerList.unregisterAll(this);
                try {
                    NBTFile nbtFile = new NBTFile(playerData);
                    NBTCompoundList nbtListCompounds = nbtFile.getCompoundList("Inventory");
                    nbtListCompounds.clear();
                    for (int i = 0; i < 41; i++) {
                        ItemStack itemStack = inventory.getItem(i);
                        if (!IItem.isEmpty(itemStack)) {
                            int slot = 999;
                            if (i < 36) {
                                slot = i;
                            } else if (i == 36) {
                                slot = 103;
                            } else if (i == 37) {
                                slot = 102;
                            } else if (i == 38) {
                                slot = 101;
                            } else if (i == 39) {
                                slot = 100;
                            } else if (i == 40) {
                                slot = -106;
                            }
                            NBTCompound nbtCompound = new NBTContainer(IItem.getItemNBT(itemStack));
                            nbtCompound.setInteger("Slot", slot);
                            nbtListCompounds.addCompound(nbtCompound);
                        }
                    }
                    nbtFile.save();
                } catch (Exception e) {
                    ConcurrentHashMap<Integer, ItemStack> concurrentHashMap = new ConcurrentHashMap<>();
                    for (int i = 0; i < 41; i++) {
                        concurrentHashMap.put(i, inventory.getItem(i) == null ? new ItemBuilder(Material.AIR).getItemStack() : inventory.getItem(i));
                    }
                    offlineInv.put(offlinePlayer.getName(), concurrentHashMap);
                }
            }
        }
    }

    @EventHandler
    private void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent evt) {
        if (player.isOp() && evt.getName().equals(offlinePlayer.getName())) {
            evt.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            evt.setKickMessage("§c管理员正在编辑你的物品，请稍后进入");
        }
    }
}
