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

public class OfflineEndInterface implements InventoryHolder, Listener {
    private final String serial;
    public static ConcurrentHashMap<String, ConcurrentHashMap<Integer, ItemStack>> offlineEndInv = new ConcurrentHashMap<>();
    private final Inventory inventory;
    private final Player player;
    private final OfflinePlayer offlinePlayer;
    private final File playerData;

    public OfflineEndInterface(Player player, OfflinePlayer offlinePlayer) {
        File playerDataFolder = new File(Main.plugin.getDataFolder().getParentFile().getParentFile(), "world\\playerdata\\");
        playerData = new File(playerDataFolder, offlinePlayer.getUniqueId().toString() + ".dat");
        Inventory inventory = Bukkit.createInventory(this, 27, "§8" + offlinePlayer.getName() + "末影箱");
        if (offlineEndInv.containsKey(offlinePlayer.getName())) {
            ConcurrentHashMap<Integer, ItemStack> concurrentHashMap = offlineEndInv.get(offlinePlayer.getName());
            for (int index : concurrentHashMap.keySet()) {
                inventory.setItem(index, concurrentHashMap.get(index));
            }
        } else {
            try {
                NBTFile nbtFile = new NBTFile(playerData);
                for (NBTCompound nbtCompound : nbtFile.getCompoundList("EnderItems")) {
                    int index = nbtCompound.getInteger("Slot");
                    ItemStack itemStack = NBTItem.convertNBTtoItem(nbtCompound);
                    inventory.setItem(index, itemStack);
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
                if (evt.getInventory().getHolder() instanceof OfflineEndInterface) {
                    if (!player.hasPermission("si.edit")) {
                        evt.setCancelled(true);
                        player.sendMessage("§c你没有权限编辑背包");
                    }
                }
            }
        }
    }

    @EventHandler
    private void onInventoryClose(InventoryCloseEvent evt) {
        if (evt.getInventory().getHolder() instanceof OfflineEndInterface && evt.getPlayer() instanceof Player) {
            OfflineEndInterface offlineEndInterface = (OfflineEndInterface) evt.getInventory().getHolder();
            if (evt.getPlayer().equals(player) && offlineEndInterface.getSerial().equals(serial)) {
                HandlerList.unregisterAll(this);
                try {
                    NBTFile nbtFile = new NBTFile(playerData);
                    NBTCompoundList nbtListCompounds = nbtFile.getCompoundList("EnderItems");
                    nbtListCompounds.clear();
                    for (int i = 0; i < 27; i++) {
                        ItemStack itemStack = inventory.getItem(i);
                        if (!IItem.isEmpty(itemStack)) {
                            NBTCompound nbtCompound = new NBTContainer(IItem.getItemNBT(itemStack));
                            nbtCompound.setInteger("Slot", i);
                            nbtListCompounds.addCompound(nbtCompound);
                        }
                    }
                    nbtFile.save();
                } catch (Exception e) {
                    ConcurrentHashMap<Integer, ItemStack> concurrentHashMap = new ConcurrentHashMap<>();
                    for (int i = 0; i < 41; i++) {
                        concurrentHashMap.put(i, inventory.getItem(i) == null ? new ItemBuilder(Material.AIR).getItemStack() : inventory.getItem(i));
                    }
                    offlineEndInv.put(offlinePlayer.getName(), concurrentHashMap);
                }
            }
        }
    }

    public String getSerial() {
        return serial;
    }

    @EventHandler
    private void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent evt) {
        if (player.isOp() && evt.getName().equals(offlinePlayer.getName())) {
            evt.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            evt.setKickMessage("§c管理员正在编辑你的物品，请稍后进入");
        }
    }
}
