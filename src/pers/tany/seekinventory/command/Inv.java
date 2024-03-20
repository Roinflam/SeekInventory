package pers.tany.seekinventory.command;

import de.tr7zw.nbtapi.NBTCompound;
import de.tr7zw.nbtapi.NBTFile;
import de.tr7zw.nbtapi.NBTItem;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.seekinventory.Main;
import pers.tany.seekinventory.gui.OfflineEndInterface;
import pers.tany.seekinventory.gui.OfflineInterface;
import pers.tany.seekinventory.gui.OnlineEndInterface;
import pers.tany.seekinventory.gui.OnlineInterface;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventory;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventoryItem;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.player.IPlayer;
import pers.tany.yukinoaapi.interfacepart.serializer.ISerializer;

import java.io.File;
import java.util.HashSet;

public class Inv implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        new BukkitRunnable() {

            @Override
            public void run() {
                if (!sender.hasPermission("si.open")) {
                    sender.sendMessage("§c你没有权限使用此指令");
                    return;
                }
                if (sender instanceof ConsoleCommandSender) {
                    sender.sendMessage("§c控制台无法使用此插件指令");
                    return;
                }
                Player player = (Player) sender;
                if (args.length == 1) {
                    String name = args[0];
                    if (name.equals(player.getName())) {
                        sender.sendMessage("§c不能打开自己的背包！");
                        return;
                    }
                    if (IPlayer.hasOnlinePlayer(name)) {
                        OnlineInterface onlineInterface = new OnlineInterface(player, Bukkit.getPlayer(name));
                        IInventory.openInventory(onlineInterface, player);
                    } else if (IPlayer.hasOfflinePlayer(name)) {
                        OfflineInterface offlineInterface = new OfflineInterface(player, Bukkit.getOfflinePlayer(name));
                        IInventory.openInventory(offlineInterface, player);
                    } else {
                        sender.sendMessage("§c没有这个玩家！");
                    }
                    return;
                }
                sender.sendMessage("§a/inv 玩家  §2打开此玩家的背包");
            }

        }.runTaskAsynchronously(Main.plugin);
        return true;
    }
}
