package pers.tany.seekinventory;

import de.tr7zw.nbtapi.NBTCompound;
import de.tr7zw.nbtapi.NBTFile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.seekinventory.command.Commands;
import pers.tany.seekinventory.command.Inv;
import pers.tany.seekinventory.listenevent.Events;
import pers.tany.yukinoaapi.interfacepart.configuration.IConfig;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.interfacepart.other.ITime;
import pers.tany.yukinoaapi.interfacepart.player.IPlayer;
import pers.tany.yukinoaapi.interfacepart.register.IRegister;
import pers.tany.yukinoaapi.interfacepart.serializer.ISerializer;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends JavaPlugin {
    public static Plugin plugin = null;
    public static YamlConfiguration config;
    public static YamlConfiguration data;
    public static YamlConfiguration message;

    @Override
    public void onEnable() {
        plugin = this;

        Bukkit.getConsoleSender().sendMessage("§2[§aSeekInventory§2]§a已启用");

        IRegister.registerCommands(Main.this, "SeekInventory", new Commands());
        IRegister.registerCommands(Main.this, "Inv", new Inv());
        IRegister.registerEvents(Main.this, new Events());

        new BukkitRunnable() {

            @Override
            public void run() {
                IConfig.createResource(Main.this, "", "config.yml", false);
                IConfig.createResource(Main.this, "", "data.yml", false);

                config = IConfig.loadConfig(Main.this, "", "config");
                data = IConfig.loadConfig(Main.this, "", "data");

                new BukkitRunnable() {

                    @Override
                    public void run() {
                        if (Main.config.getBoolean("Save")) {
                            save();
                        }
                    }

                }.runTaskTimerAsynchronously(Main.plugin, 1200L * Main.config.getInt("SaveTime"), 1200L * Main.config.getInt("SaveTime"));
            }

        }.runTaskAsynchronously(Main.plugin);
    }

    @Override
    public void onDisable() {
        Bukkit.getConsoleSender().sendMessage("§2[§aSeekInventory§2]§c已卸载");
    }

    public static void save() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = Bukkit.createInventory(null, 45);
            Inventory endInventory = Bukkit.createInventory(null, 27);
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
                inventory.setItem(i, itemStack);
            }
            for (int i = 0; i < 27; i++) {
                endInventory.setItem(i, player.getEnderChest().getItem(i));
            }
            List<String> inv = data.getStringList("Inv." + player.getName());
            List<String> end = data.getStringList("End." + player.getName());
            if (inv.size() - Main.config.getInt("SaveMax") > 0) {
                inv.subList(0, inv.size() - Main.config.getInt("SaveMax")).clear();
            }
            if (end.size() - Main.config.getInt("SaveMax") > 0) {
                end.subList(0, end.size() - Main.config.getInt("SaveMax")).clear();
            }
            inv.add(ITime.getNowTimeString() + "-" + ISerializer.serializerInventory(inventory));
            end.add(ITime.getNowTimeString() + "-" + ISerializer.serializerInventory(endInventory));
            data.set("Inv." + player.getName(), inv);
            data.set("End." + player.getName(), end);
        }
        IConfig.saveConfig(Main.plugin, data, "", "data");
        Bukkit.broadcastMessage(IString.color(config.getString("Message")));
    }

    public static void save(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 45);
        Inventory endInventory = Bukkit.createInventory(null, 27);
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
            inventory.setItem(i, itemStack);
        }
        for (int i = 0; i < 27; i++) {
            endInventory.setItem(i, player.getEnderChest().getItem(i));
        }
        List<String> inv = data.getStringList("Inv." + player.getName());
        List<String> end = data.getStringList("End." + player.getName());
        if (inv.size() - Main.config.getInt("SaveMax") > 0) {
            inv.subList(0, inv.size() - Main.config.getInt("SaveMax")).clear();
        }
        if (end.size() - Main.config.getInt("SaveMax") > 0) {
            end.subList(0, end.size() - Main.config.getInt("SaveMax")).clear();
        }
        inv.add(ITime.getNowTimeString() + "-" + ISerializer.serializerInventory(inventory));
        end.add(ITime.getNowTimeString() + "-" + ISerializer.serializerInventory(endInventory));
        data.set("Inv." + player.getName(), inv);
        data.set("End." + player.getName(), end);
        IConfig.saveConfig(Main.plugin, data, "", "data");
        player.sendMessage(IString.color(config.getString("Message")));
    }
}
