package pers.tany.seekinventory.command;

import de.tr7zw.nbtapi.*;
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
import pers.tany.yukinoaapi.interfacepart.configuration.IConfig;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventory;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventoryItem;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.jsonText.IBungee;
import pers.tany.yukinoaapi.interfacepart.player.IPlayer;
import pers.tany.yukinoaapi.interfacepart.serializer.ISerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Commands implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        new BukkitRunnable() {

            @Override
            public void run() {
                if (sender instanceof ConsoleCommandSender) {
                    sender.sendMessage("§c控制台无法使用此插件指令");
                    return;
                }
                Player player = (Player) sender;
                if (args.length == 1) {
                    if (args[0].equalsIgnoreCase("reload")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        Main.config = IConfig.loadConfig(Main.plugin, "", "config");
                        Main.data = IConfig.loadConfig(Main.plugin, "", "data");
                        sender.sendMessage("§a重载成功");
                        return;
                    }
                    if (args[0].equalsIgnoreCase("end")) {
                        OnlineEndInterface onlineEndInterface = new OnlineEndInterface(player, player);
                        IInventory.openInventory(onlineEndInterface, player);
                        return;
                    }
                    if (args[0].equalsIgnoreCase("check")) {
                        if (!sender.hasPermission("si.check")) {
                            sender.sendMessage("§c你没有权限使用此指令");
                            return;
                        }
                        if (IItem.isEmptyHand(player)) {
                            player.sendMessage("§c手上物品不能为空！");
                            return;
                        }
                        ItemStack itemStack = player.getInventory().getItemInHand();
                        player.sendMessage("§a正在查找...");
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (IInventoryItem.hasAmount(p.getInventory(), itemStack) > 0) {
                                player.sendMessage("§a在线玩家" + p.getName() + "背包拥有此物品");
                                Bukkit.getConsoleSender().sendMessage("§a在线玩家" + p.getName() + "背包拥有此物品");
                            }
                            if (IInventoryItem.hasAmount(p.getEnderChest(), itemStack) > 0) {
                                player.sendMessage("§a在线玩家" + p.getName() + "末影箱拥有此物品");
                                Bukkit.getConsoleSender().sendMessage("§a在线玩家" + p.getName() + "末影箱拥有此物品");
                            }
                        }
                        for (OfflinePlayer p : IPlayer.getOfflinePlayers()) {
                            File playerDataFolder = new File(Main.plugin.getDataFolder().getParentFile().getParentFile(), "world\\playerdata\\");
                            File playerData = new File(playerDataFolder, p.getUniqueId().toString() + ".dat");
                            try {
                                NBTFile nbtFile = new NBTFile(playerData);
                                for (NBTCompound nbtCompound : nbtFile.getCompoundList("Inventory")) {
                                    if (NBTItem.convertNBTtoItem(nbtCompound).isSimilar(itemStack)) {
                                        player.sendMessage("§7离线玩家" + p.getName() + "背包拥有此物品");
                                        break;
                                    }
                                }
                                for (NBTCompound nbtCompound : nbtFile.getCompoundList("EnderItems")) {
                                    if (NBTItem.convertNBTtoItem(nbtCompound).isSimilar(itemStack)) {
                                        player.sendMessage("§7离线玩家" + p.getName() + "末影箱拥有此物品");
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {

                            }
                        }
                        player.sendMessage("§a查找完毕");
                        return;
                    }
                    if (args[0].equalsIgnoreCase("checkid")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        if (IItem.isEmptyHand(player)) {
                            player.sendMessage("§c手上物品不能为空！");
                            return;
                        }
                        ItemStack itemStack = player.getInventory().getItemInHand();
                        player.sendMessage("§a正在查找...");
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (IInventoryItem.hasAmount(p.getInventory(), itemStack) > 0) {
                                player.sendMessage("§a在线玩家" + p.getName() + "背包拥有此物品");
                                Bukkit.getConsoleSender().sendMessage("§a在线玩家" + p.getName() + "背包拥有此物品");
                            }
                            if (IInventoryItem.hasAmount(p.getEnderChest(), itemStack) > 0) {
                                player.sendMessage("§a在线玩家" + p.getName() + "末影箱拥有此物品");
                                Bukkit.getConsoleSender().sendMessage("§a在线玩家" + p.getName() + "末影箱拥有此物品");
                            }
                        }
                        for (OfflinePlayer p : IPlayer.getOfflinePlayers()) {
                            File playerDataFolder = new File(Main.plugin.getDataFolder().getParentFile().getParentFile(), "world\\playerdata\\");
                            File playerData = new File(playerDataFolder, p.getUniqueId().toString() + ".dat");
                            try {
                                NBTFile nbtFile = new NBTFile(playerData);
                                for (NBTCompound nbtCompound : nbtFile.getCompoundList("Inventory")) {
                                    if (NBTItem.convertNBTtoItem(nbtCompound).isSimilar(itemStack)) {
                                        player.sendMessage("§7离线玩家" + p.getName() + "背包拥有此物品");
                                        break;
                                    }
                                }
                                for (NBTCompound nbtCompound : nbtFile.getCompoundList("EnderItems")) {
                                    if (NBTItem.convertNBTtoItem(nbtCompound).isSimilar(itemStack)) {
                                        player.sendMessage("§7离线玩家" + p.getName() + "末影箱拥有此物品");
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {

                            }
                        }
                        player.sendMessage("§a查找完毕");
                        return;
                    }
                    if (args[0].equalsIgnoreCase("delete")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        if (IItem.isEmptyHand(player)) {
                            player.sendMessage("§c手上物品不能为空！");
                            return;
                        }
                        ItemStack itemStack = player.getInventory().getItemInHand();
                        player.sendMessage("§a正在查找...");
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (IInventoryItem.hasAmount(p.getInventory(), itemStack) > 0) {
                                p.getInventory().remove(itemStack);
                                player.sendMessage("§a在线玩家" + p.getName() + "背包拥有此物品，已删除");
                                Bukkit.getConsoleSender().sendMessage("§a在线玩家" + p.getName() + "背包拥有此物品，已删除");
                            }
                            if (IInventoryItem.hasAmount(p.getEnderChest(), itemStack) > 0) {
                                p.getEnderChest().remove(itemStack);
                                player.sendMessage("§a在线玩家" + p.getName() + "末影箱拥有此物品，已删除");
                                Bukkit.getConsoleSender().sendMessage("§a在线玩家" + p.getName() + "末影箱拥有此物品，已删除");
                            }
                        }
                        for (OfflinePlayer p : IPlayer.getOfflinePlayers()) {
                            File playerDataFolder = new File(Main.plugin.getDataFolder().getParentFile().getParentFile(), "world\\playerdata\\");
                            File playerData = new File(playerDataFolder, p.getUniqueId().toString() + ".dat");
                            try {
                                NBTFile nbtFile = new NBTFile(playerData);
                                NBTCompoundList inv_NbtCompoundList = nbtFile.getCompoundList("Inventory");
                                NBTCompoundList end_NbtCompoundList = nbtFile.getCompoundList("EnderItems");
                                for (NBTListCompound nbtListCompound : new ArrayList<>(nbtFile.getCompoundList("Inventory"))) {
                                    if (NBTItem.convertNBTtoItem(nbtListCompound).isSimilar(itemStack)) {
                                        inv_NbtCompoundList.remove(nbtListCompound);
                                        player.sendMessage("§7离线玩家" + p.getName() + "背包拥有此物品，已删除");
                                        break;
                                    }
                                }
                                for (NBTListCompound nbtListCompound : new ArrayList<>(nbtFile.getCompoundList("EnderItems"))) {
                                    if (NBTItem.convertNBTtoItem(nbtListCompound).isSimilar(itemStack)) {
                                        end_NbtCompoundList.remove(nbtListCompound);
                                        player.sendMessage("§7离线玩家" + p.getName() + "末影箱拥有此物品，已删除");
                                        break;
                                    }
                                }
                                nbtFile.save();
                            } catch (Exception ignored) {

                            }
                        }
                        player.sendMessage("§a删除完毕");
                        return;
                    }
                }
                if (args.length == 2) {
                    if (args[0].equalsIgnoreCase("inv")) {
                        if (!sender.hasPermission("si.open")) {
                            sender.sendMessage("§c你没有权限使用此指令");
                            return;
                        }
                        String name = args[1];
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
                    if (args[0].equalsIgnoreCase("end")) {
                        if (!sender.hasPermission("si.open")) {
                            sender.sendMessage("§c你没有权限使用此指令");
                            return;
                        }
                        String name = args[1];
                        if (IPlayer.hasOnlinePlayer(name)) {
                            OnlineEndInterface onlineEndInterface = new OnlineEndInterface(player, Bukkit.getPlayer(name));
                            IInventory.openInventory(onlineEndInterface, player);
                        } else if (IPlayer.hasOfflinePlayer(name)) {
                            OfflineEndInterface offlineEndInterface = new OfflineEndInterface(player, Bukkit.getOfflinePlayer(name));
                            IInventory.openInventory(offlineEndInterface, player);
                        } else {
                            sender.sendMessage("§c没有这个玩家！");
                        }
                        return;
                    }
                    if (args[0].equalsIgnoreCase("timeinv")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        String name = args[1];
                        List<String> list = Main.data.getStringList("Inv." + name);
                        if (list.size() > 0) {
                            int page = 1;
                            sender.sendMessage("§7§l玩家" + name + "背包共" + list.size() + "条记录");
                            for (int i = list.size() - 1 - (page - 1) * Main.config.getInt("Page"); i >= 0 && i >= list.size() - Main.config.getInt("Page") * page; i--) {
                                String[] s = list.get(i).split("-");
                                IBungee.sendPartShowCommandMessage(player, " §f- §2第" + (list.size() - i) + "条记录 记录于" + s[0] + " 点击打开背包", "点击打开背包", "§7§l「点击打开背包」", "§a打开此玩家背包", "/seekinventory openinv " + name + " " + i, true);
                            }
                        } else {
                            sender.sendMessage("§c此玩家没有记录，请等待服务器记录！");
                        }
                        return;
                    }
                    if (args[0].equalsIgnoreCase("timeend")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        String name = args[1];
                        List<String> list = Main.data.getStringList("End." + name);
                        if (list.size() > 0) {
                            int page = 1;
                            sender.sendMessage("§7§l玩家" + name + "末影箱共" + list.size() + "条记录");
                            for (int i = list.size() - 1 - (page - 1) * Main.config.getInt("Page"); i >= 0 && i >= list.size() - Main.config.getInt("Page") * page; i--) {
                                String[] s = list.get(i).split("-");
                                IBungee.sendPartShowCommandMessage(player, " §f- §2第" + (list.size() - i) + "条记录 记录于" + s[0] + " 点击打开末影箱", "点击打开末影箱", "§7§l「点击打开末影箱」", "§a打开此玩家末影箱", "/seekinventory openend " + name + " " + i, true);
                            }
                        } else {
                            sender.sendMessage("§c此玩家没有记录，请等待服务器记录！");
                        }
                        return;
                    }
                }
                if (args.length == 3) {
                    if (args[0].equalsIgnoreCase("timeinv")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        String name = args[1];
                        List<String> list = Main.data.getStringList("Inv." + name);
                        if (list.size() > 0) {
                            int page = 1;
                            try {
                                page = Integer.parseInt(args[2]);
                                if (page < 1) {
                                    throw new NumberFormatException();
                                }
                            } catch (NumberFormatException numberFormatException) {
                                sender.sendMessage("§c请输入大于0的数字");
                                return;
                            }
                            if ((page - 1) * Main.config.getInt("Page") > list.size()) {
                                sender.sendMessage("§c没有这么多页！");
                                return;
                            }
                            sender.sendMessage("§7§l玩家" + name + "背包共" + list.size() + "条记录");
                            for (int i = list.size() - 1 - (page - 1) * Main.config.getInt("Page"); i >= 0 && i >= list.size() - Main.config.getInt("Page") * page; i--) {
                                String[] s = list.get(i).split("-");
                                IBungee.sendPartShowCommandMessage(player, " §f- §2第" + (list.size() - i) + "条记录 记录于" + s[0] + " 点击打开背包", "点击打开背包", "§7§l「点击打开背包」", "§a打开此玩家背包", "/seekinventory openinv " + name + " " + i, true);
                            }
                        } else {
                            sender.sendMessage("§c此玩家没有记录，请等待服务器记录！");
                        }
                        return;
                    }
                    if (args[0].equalsIgnoreCase("timeend")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        String name = args[1];
                        List<String> list = Main.data.getStringList("End." + name);
                        if (list.size() > 0) {
                            int page = 1;
                            try {
                                page = Integer.parseInt(args[2]);
                                if (page < 1) {
                                    throw new NumberFormatException();
                                }
                            } catch (NumberFormatException numberFormatException) {
                                sender.sendMessage("§c请输入大于0的数字");
                                return;
                            }
                            if ((page - 1) * Main.config.getInt("Page") > list.size()) {
                                sender.sendMessage("§c没有这么多页！");
                                return;
                            }
                            sender.sendMessage("§7§l玩家" + name + "末影箱共" + list.size() + "条记录");
                            for (int i = list.size() - 1 - (page - 1) * Main.config.getInt("Page"); i >= 0 && i >= list.size() - Main.config.getInt("Page") * page; i--) {
                                String[] s = list.get(i).split("-");
                                IBungee.sendPartShowCommandMessage(player, " §f- §2第" + (list.size() - i) + "条记录 记录于" + s[0] + " 点击打开末影箱", "点击打开末影箱", "§7§l「点击打开末影箱」", "§a打开此玩家末影箱", "/seekinventory openend " + name + " " + i, true);
                            }
                        } else {
                            sender.sendMessage("§c此玩家没有记录，请等待服务器记录！");
                        }
                        return;
                    }
                    if (args[0].equalsIgnoreCase("openinv")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        String name = args[1];
                        List<String> list = Main.data.getStringList("Inv." + name);
                        if (list.size() > 0) {
                            int index = 1;
                            try {
                                index = Integer.parseInt(args[2]);
                                if (index < 0) {
                                    throw new NumberFormatException();
                                }
                            } catch (NumberFormatException numberFormatException) {
                                sender.sendMessage("§c请输入大于0的数字");
                                return;
                            }
                            Inventory inventory = Bukkit.createInventory(null, 45, "§a§l" + name + "历史背包");
                            ISerializer.deserializerInventory(inventory, list.get(index).split("-")[1]);
                            IInventory.openInventory(inventory, player);
                        } else {
                            sender.sendMessage("§c此玩家没有记录，请等待服务器记录！");
                        }
                        return;
                    }
                    if (args[0].equalsIgnoreCase("openend")) {
                        if (!sender.isOp()) {
                            sender.sendMessage("§c你没有权限执行此命令");
                            return;
                        }
                        String name = args[1];
                        List<String> list = Main.data.getStringList("End." + name);
                        if (list.size() > 0) {
                            int index = 1;
                            try {
                                index = Integer.parseInt(args[2]);
                                if (index < 0) {
                                    throw new NumberFormatException();
                                }
                            } catch (NumberFormatException numberFormatException) {
                                sender.sendMessage("§c请输入大于0的数字");
                                return;
                            }
                            Inventory inventory = Bukkit.createInventory(null, 27, "§a§l" + name + "历史末影箱");
                            ISerializer.deserializerInventory(inventory, list.get(index).split("-")[1]);
                            IInventory.openInventory(inventory, player);
                        } else {
                            sender.sendMessage("§c此玩家没有记录，请等待服务器记录！");
                        }
                        return;
                    }
                }
                sender.sendMessage("§a/seek check  §2查找拥有手上物品的玩家（和inv的背包有出入时，请以check为准）");
                sender.sendMessage("§a/seek delete  §2删除拥有手上物品的玩家物品");
                sender.sendMessage("§a/seek inv 玩家  §2打开此玩家的背包");
                sender.sendMessage("§a/seek end [玩家]  §2打开此玩家的末影箱");
                sender.sendMessage("§a/seek timeInv 玩家 [页数]  §2查看此玩家背包备份信息");
                sender.sendMessage("§a/seek timeEnd 玩家 [页数]  §2查看此玩家末影箱备份信息");
                sender.sendMessage("§a/seek reload  §2重载");
            }

        }.runTaskAsynchronously(Main.plugin);
        return true;
    }
}
