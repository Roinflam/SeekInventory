package pers.tany.seekinventory.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.seekinventory.Main;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventory;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.other.IRandom;

public class OnlineEndInterface implements InventoryHolder, Listener {
    private final String serial;
    private final Inventory inventory;
    private final Player player;
    private final Player p;
    private int taskID;

    public OnlineEndInterface(Player player, Player p) {
        Inventory inventory = Bukkit.createInventory(this, 27, "§5§l" + p.getName() + "末影箱");
        for (int i = 0; i < 27; i++) {
            ItemStack itemStack = p.getEnderChest().getItem(i);
            if (!IItem.isEmpty(itemStack)) {
                inventory.setItem(i, itemStack);
            }
        }

        this.inventory = inventory;
        this.player = player;
        this.p = p;
        this.serial = IRandom.createRandomString(8);

        Bukkit.getPluginManager().registerEvents(this, Main.plugin);
        taskID = new BukkitRunnable() {

            @Override
            public void run() {
                inventory.clear();
                for (int i = 0; i < 27; i++) {
                    ItemStack itemStack = p.getEnderChest().getItem(i);
                    if (!IItem.isEmpty(itemStack)) {
                        inventory.setItem(i, itemStack);
                    }
                }
            }

        }.runTaskTimerAsynchronously(Main.plugin, 5, 5).getTaskId();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onInventoryClick(InventoryClickEvent evt) {
        if (evt.getWhoClicked() instanceof Player && evt.getWhoClicked().equals(player)) {
            if (evt.getRawSlot() != -999) {
                if (evt.getInventory().getHolder() instanceof OnlineEndInterface) {
                    if (!player.hasPermission("si.edit")) {
                        evt.setCancelled(true);
                        player.sendMessage("§c你没有权限编辑背包");
                        return;
                    }
                    Bukkit.getScheduler().cancelTask(taskID);
                    new BukkitRunnable() {

                        @Override
                        public void run() {
                            for (int i = 0; i < 27; i++) {
                                p.getEnderChest().setItem(i, inventory.getItem(i));
                            }
                        }

                    }.runTask(Main.plugin);
                    taskID = new BukkitRunnable() {

                        @Override
                        public void run() {
                            inventory.clear();
                            for (int i = 0; i < 27; i++) {
                                ItemStack itemStack = p.getEnderChest().getItem(i);
                                if (!IItem.isEmpty(itemStack)) {
                                    inventory.setItem(i, itemStack);
                                }
                            }
                        }

                    }.runTaskTimerAsynchronously(Main.plugin, 5, 5).getTaskId();
                }
            }
        }
    }

    @EventHandler
    private void onInventoryClose(InventoryCloseEvent evt) {
        if (evt.getInventory().getHolder() instanceof OnlineEndInterface && evt.getPlayer() instanceof Player) {
            OnlineEndInterface onlineEndInterface = (OnlineEndInterface) evt.getInventory().getHolder();
            if (evt.getPlayer().equals(player) && onlineEndInterface.getSerial().equals(serial)) {
                Bukkit.getScheduler().cancelTask(taskID);
                HandlerList.unregisterAll(this);
            }
        }
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent evt) {
        Player player = evt.getPlayer();
        if (player.getName().equals(this.p.getName())) {
            this.player.closeInventory();
            this.player.sendMessage("§a" + player.getName() + "已经下线，准备打开其离线末影箱");
            Player p = this.player;
            new BukkitRunnable() {

                @Override
                public void run() {
                    OfflineEndInterface offlineEndInterface = new OfflineEndInterface(p, Bukkit.getOfflinePlayer(player.getName()));
                    IInventory.openInventory(offlineEndInterface.getInventory(), p);
                }

            }.runTaskLater(Main.plugin, 10);
        }
    }

    public String getSerial() {
        return serial;
    }
}
