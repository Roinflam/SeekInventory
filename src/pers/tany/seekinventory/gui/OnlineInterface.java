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

public class OnlineInterface implements InventoryHolder, Listener {
    private final String serial;
    private final Inventory inventory;
    private final Player player;
    private final Player p;
    private int taskID;

    public OnlineInterface(Player player, Player p) {
        Inventory inventory = Bukkit.createInventory(this, 45, "§2§l" + p.getName() + "背包");
        for (int i = 0; i < 41; i++) {
            ItemStack itemStack = null;
            if (i == 36) {
                itemStack = p.getInventory().getItem(39);
            } else if (i == 37) {
                itemStack = p.getInventory().getItem(38);
            } else if (i == 38) {
                itemStack = p.getInventory().getItem(37);
            } else if (i == 39) {
                itemStack = p.getInventory().getItem(36);
            } else {
                try {
                    itemStack = p.getInventory().getItem(i);
                } catch (Exception ignored) {

                }
            }
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
                for (int i = 0; i < 41; i++) {
                    ItemStack itemStack = null;
                    if (i == 36) {
                        itemStack = p.getInventory().getItem(39);
                    } else if (i == 37) {
                        itemStack = p.getInventory().getItem(38);
                    } else if (i == 38) {
                        itemStack = p.getInventory().getItem(37);
                    } else if (i == 39) {
                        itemStack = p.getInventory().getItem(36);
                    } else {
                        try {
                            itemStack = p.getInventory().getItem(i);
                        } catch (Exception ignored) {

                        }
                    }
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
                if (evt.getInventory().getHolder() instanceof OnlineInterface) {
                    if (!player.hasPermission("si.edit")) {
                        evt.setCancelled(true);
                        player.sendMessage("§c你没有权限编辑背包");
                        return;
                    }
                    Bukkit.getScheduler().cancelTask(taskID);
                    new BukkitRunnable() {

                        @Override
                        public void run() {
                            for (int i = 0; i < 41; i++) {
                                ItemStack itemStack = null;
                                if (i == 36) {
                                    itemStack = inventory.getItem(39);
                                } else if (i == 37) {
                                    itemStack = inventory.getItem(38);
                                } else if (i == 38) {
                                    itemStack = inventory.getItem(37);
                                } else if (i == 39) {
                                    itemStack = inventory.getItem(36);
                                } else {
                                    itemStack = inventory.getItem(i);
                                }
                                try {
                                    p.getInventory().setItem(i, itemStack);
                                } catch (Exception ignored) {

                                }
                            }
                        }

                    }.runTask(Main.plugin);
                    taskID = new BukkitRunnable() {

                        @Override
                        public void run() {
                            inventory.clear();
                            for (int i = 0; i < 41; i++) {
                                ItemStack itemStack = null;
                                if (i == 36) {
                                    itemStack = p.getInventory().getItem(39);
                                } else if (i == 37) {
                                    itemStack = p.getInventory().getItem(38);
                                } else if (i == 38) {
                                    itemStack = p.getInventory().getItem(37);
                                } else if (i == 39) {
                                    itemStack = p.getInventory().getItem(36);
                                } else {
                                    try {
                                        itemStack = p.getInventory().getItem(i);
                                    } catch (Exception ignored) {

                                    }
                                }
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
        if (evt.getInventory().getHolder() instanceof OnlineInterface && evt.getPlayer() instanceof Player) {
            OnlineInterface onlineInterface = (OnlineInterface) evt.getInventory().getHolder();
            if (evt.getPlayer().equals(player) && onlineInterface.getSerial().equals(serial)) {
                Bukkit.getScheduler().cancelTask(taskID);
                HandlerList.unregisterAll(this);
            }
        }
    }

    public String getSerial() {
        return serial;
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent evt) {
        Player player = evt.getPlayer();
        if (player.getName().equals(this.p.getName())) {
            this.player.closeInventory();
            this.player.sendMessage("§a" + player.getName() + "已经下线，准备打开其离线背包");
            Player p = this.player;
            new BukkitRunnable() {

                @Override
                public void run() {
                    OfflineInterface offlineInterface = new OfflineInterface(p, Bukkit.getOfflinePlayer(player.getName()));
                    IInventory.openInventory(offlineInterface.getInventory(), p);
                }

            }.runTaskLater(Main.plugin, 10);
        }
    }
}
