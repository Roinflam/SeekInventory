package com.flameforge.seekinventory.command;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.gui.OfflineInterface;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import com.flameforge.seekinventory.gui.OnlineInterface;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventory;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.interfacepart.player.IPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Inv 命令处理器
 * 快捷打开背包命令
 *
 * @author Tany天炎
 */
public class Inv implements CommandExecutor {

    /** 插件实例 */
    private final SeekInventory plugin;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public Inv(SeekInventory plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        new BukkitRunnable() {
            @Override
            public void run() {
                handleInvCommand(sender, args);
            }
        }.runTaskAsynchronously(plugin);
        return true;
    }

    /**
     * 处理inv命令
     */
    private void handleInvCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("si.open")) {
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.console-only")));
            return;
        }

        Player player = (Player) sender;

        if (args.length != 1) {
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.inv-usage")));
            return;
        }

        String targetName = args[0];

        if (targetName.equals(player.getName())) {
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.cannot-open-self")));
            return;
        }

        if (IPlayer.hasOnlinePlayer(targetName)) {
            OnlineInterface onlineInterface = new OnlineInterface(player, Bukkit.getPlayer(targetName), plugin);
            IInventory.openInventory(onlineInterface, player);
        } else if (IPlayer.hasOfflinePlayer(targetName)) {
            OfflineInterface offlineInterface = new OfflineInterface(player, Bukkit.getOfflinePlayer(targetName), plugin);
            IInventory.openInventory(offlineInterface, player);
        } else {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetName);
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.player-not-found", placeholders)));
        }
    }
}