package com.flameforge.seekinventory.command;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.gui.OfflineEndInterface;
import com.flameforge.seekinventory.gui.OfflineInterface;
import com.flameforge.seekinventory.gui.OnlineEndInterface;
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
import com.flameforge.seekinventory.config.DataManager;
import com.flameforge.seekinventory.gui.OnlineInterface;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventory;
import pers.tany.yukinoaapi.interfacepart.inventory.IInventoryItem;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
import pers.tany.yukinoaapi.interfacepart.jsonText.IBungee;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.interfacepart.player.IPlayer;
import pers.tany.yukinoaapi.interfacepart.serializer.ISerializer;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 命令处理器
 * 处理插件的所有命令
 *
 * @author Tany天炎
 */
public class Commands implements CommandExecutor {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 数据管理器 */
    private final DataManager dataManager;

    /** 时间格式化器 */
    private final SimpleDateFormat dateFormat;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public Commands(SeekInventory plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        new BukkitRunnable() {
            @Override
            public void run() {
                handleCommand(sender, args);
            }
        }.runTaskAsynchronously(plugin);
        return true;
    }

    /**
     * 处理命令
     */
    private void handleCommand(CommandSender sender, String[] args) {
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.console-only")));
            return;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                sendHelp(sender);
                break;

            case "reload":
                handleReload(sender);
                break;

            case "end":
                handleEnd(player, args);
                break;

            case "inv":
                handleInv(player, args);
                break;

            case "check":
                handleCheck(player);
                break;

            case "checkid":
                handleCheckId(player);
                break;

            case "delete":
                handleDelete(player);
                break;

            case "timeinv":
                handleTimeInv(player, args);
                break;

            case "timeend":
                handleTimeEnd(player, args);
                break;

            case "openinv":
                handleOpenInv(player, args);
                break;

            case "openend":
                handleOpenEnd(player, args);
                break;

            case "save":
                handleSave(player);
                break;

            default:
                sendHelp(sender);
                break;
        }
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        List<String> helpMessages = plugin.getLanguageManager().getMessageList("command.help");
        helpMessages.forEach(msg -> sender.sendMessage(IString.color(msg)));
    }

    /**
     * 处理重载命令
     */
    private void handleReload(CommandSender sender) {
        if (!sender.isOp()) {
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        try {
            plugin.getLanguageManager().loadLanguage();
            plugin.getConfigManager().loadConfig();
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.reload-success")));
        } catch (Exception e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage());
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.reload-failed", placeholders)));
            plugin.sendError("重载异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理打开末影箱命令
     */
    private void handleEnd(Player player, String[] args) {
        if (!player.hasPermission("si.open")) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (args.length == 1) {
            // 打开自己的末影箱
            OnlineEndInterface onlineEndInterface = new OnlineEndInterface(player, player, plugin);
            IInventory.openInventory(onlineEndInterface, player);
            return;
        }

        if (args.length == 2) {
            String targetName = args[1];
            if (IPlayer.hasOnlinePlayer(targetName)) {
                OnlineEndInterface onlineEndInterface = new OnlineEndInterface(player, Bukkit.getPlayer(targetName), plugin);
                IInventory.openInventory(onlineEndInterface, player);
            } else if (IPlayer.hasOfflinePlayer(targetName)) {
                OfflineEndInterface offlineEndInterface = new OfflineEndInterface(player, Bukkit.getOfflinePlayer(targetName), plugin);
                IInventory.openInventory(offlineEndInterface, player);
            } else {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.player-not-found", "player", targetName)));
            }
        }
    }

    /**
     * 处理打开背包命令
     */
    private void handleInv(Player player, String[] args) {
        if (!player.hasPermission("si.open")) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.inv-usage")));
            return;
        }

        String targetName = args[1];
        if (targetName.equals(player.getName())) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.cannot-open-self")));
            return;
        }

        if (IPlayer.hasOnlinePlayer(targetName)) {
            OnlineInterface onlineInterface = new OnlineInterface(player, Bukkit.getPlayer(targetName), plugin);
            IInventory.openInventory(onlineInterface, player);
        } else if (IPlayer.hasOfflinePlayer(targetName)) {
            OfflineInterface offlineInterface = new OfflineInterface(player, Bukkit.getOfflinePlayer(targetName), plugin);
            IInventory.openInventory(offlineInterface, player);
        } else {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.player-not-found", "player", targetName)));
        }
    }

    /**
     * 处理查找物品命令（精确匹配）
     */
    private void handleCheck(Player player) {
        if (!player.hasPermission("si.check")) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (IItem.isEmptyHand(player)) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.empty-hand")));
            return;
        }

        ItemStack itemStack = player.getInventory().getItemInHand();
        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.searching")));

        // 搜索在线玩家
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (IInventoryItem.hasAmount(p.getInventory(), itemStack) > 0) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-inv", "player", p.getName())));
                Bukkit.getConsoleSender().sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-inv", "player", p.getName())));
            }
            if (IInventoryItem.hasAmount(p.getEnderChest(), itemStack) > 0) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-end", "player", p.getName())));
                Bukkit.getConsoleSender().sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-end", "player", p.getName())));
            }
        }

        // 搜索离线玩家
        for (OfflinePlayer p : IPlayer.getOfflinePlayers()) {
            searchOfflinePlayer(player, p, itemStack, true);
        }

        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.search-complete")));
    }

    /**
     * 处理查找物品ID命令（只匹配材质）
     */
    private void handleCheckId(Player player) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (IItem.isEmptyHand(player)) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.empty-hand")));
            return;
        }

        ItemStack itemStack = player.getInventory().getItemInHand();
        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.searching")));

        // 搜索在线玩家
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (IInventoryItem.hasAmount(p.getInventory(), itemStack) > 0) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-inv", "player", p.getName())));
                Bukkit.getConsoleSender().sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-inv", "player", p.getName())));
            }
            if (IInventoryItem.hasAmount(p.getEnderChest(), itemStack) > 0) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-end", "player", p.getName())));
                Bukkit.getConsoleSender().sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-online-end", "player", p.getName())));
            }
        }

        // 搜索离线玩家
        for (OfflinePlayer p : IPlayer.getOfflinePlayers()) {
            searchOfflinePlayer(player, p, itemStack, false);
        }

        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.search-complete")));
    }

    /**
     * 搜索离线玩家
     *
     * @param player 执行命令的玩家
     * @param offlinePlayer 离线玩家
     * @param itemStack 要搜索的物品
     * @param exactMatch 是否精确匹配
     */
    private void searchOfflinePlayer(Player player, OfflinePlayer offlinePlayer, ItemStack itemStack, boolean exactMatch) {
        File playerDataFolder = new File(plugin.getDataFolder().getParentFile().getParentFile(), "world/playerdata/");
        File playerData = new File(playerDataFolder, offlinePlayer.getUniqueId().toString() + ".dat");

        try {
            NBTFile nbtFile = new NBTFile(playerData);

            // 搜索背包
            for (NBTCompound nbtCompound : nbtFile.getCompoundList("Inventory")) {
                ItemStack foundItem = NBTItem.convertNBTtoItem(nbtCompound);
                boolean matches = exactMatch ? foundItem.isSimilar(itemStack) : foundItem.getType().equals(itemStack.getType());
                if (matches) {
                    player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-offline-inv", "player", offlinePlayer.getName())));
                    break;
                }
            }

            // 搜索末影箱
            for (NBTCompound nbtCompound : nbtFile.getCompoundList("EnderItems")) {
                ItemStack foundItem = NBTItem.convertNBTtoItem(nbtCompound);
                boolean matches = exactMatch ? foundItem.isSimilar(itemStack) : foundItem.getType().equals(itemStack.getType());
                if (matches) {
                    player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-offline-end", "player", offlinePlayer.getName())));
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 处理删除物品命令
     */
    private void handleDelete(Player player) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (IItem.isEmptyHand(player)) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.empty-hand")));
            return;
        }

        ItemStack itemStack = player.getInventory().getItemInHand();
        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleting")));

        // 删除在线玩家物品
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (IInventoryItem.hasAmount(p.getInventory(), itemStack) > 0) {
                p.getInventory().remove(itemStack);
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleted-online-inv", "player", p.getName())));
                Bukkit.getConsoleSender().sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleted-online-inv", "player", p.getName())));
            }
            if (IInventoryItem.hasAmount(p.getEnderChest(), itemStack) > 0) {
                p.getEnderChest().remove(itemStack);
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleted-online-end", "player", p.getName())));
                Bukkit.getConsoleSender().sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleted-online-end", "player", p.getName())));
            }
        }

        // 删除离线玩家物品
        for (OfflinePlayer p : IPlayer.getOfflinePlayers()) {
            deleteOfflinePlayerItem(player, p, itemStack);
        }

        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.delete-complete")));
    }

    /**
     * 删除离线玩家物品
     */
    private void deleteOfflinePlayerItem(Player player, OfflinePlayer offlinePlayer, ItemStack itemStack) {
        File playerDataFolder = new File(plugin.getDataFolder().getParentFile().getParentFile(), "world/playerdata/");
        File playerData = new File(playerDataFolder, offlinePlayer.getUniqueId().toString() + ".dat");

        try {
            NBTFile nbtFile = new NBTFile(playerData);
            NBTCompoundList invList = nbtFile.getCompoundList("Inventory");
            NBTCompoundList endList = nbtFile.getCompoundList("EnderItems");

            // 删除背包中的物品
            for (NBTListCompound nbtListCompound : new ArrayList<>(nbtFile.getCompoundList("Inventory"))) {
                if (NBTItem.convertNBTtoItem(nbtListCompound).isSimilar(itemStack)) {
                    invList.remove(nbtListCompound);
                    player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleted-offline-inv", "player", offlinePlayer.getName())));
                    break;
                }
            }

            // 删除末影箱中的物品
            for (NBTListCompound nbtListCompound : new ArrayList<>(nbtFile.getCompoundList("EnderItems"))) {
                if (NBTItem.convertNBTtoItem(nbtListCompound).isSimilar(itemStack)) {
                    endList.remove(nbtListCompound);
                    player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleted-offline-end", "player", offlinePlayer.getName())));
                    break;
                }
            }

            nbtFile.save();
        } catch (Exception ignored) {
        }
    }

    /**
     * 处理查看背包历史命令
     */
    private void handleTimeInv(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.timeinv-usage")));
            return;
        }

        String targetName = args[1];
        int page = 1;

        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
                if (page < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.invalid-page")));
                return;
            }
        }

        displaySnapshotList(player, targetName, DataManager.TYPE_INVENTORY, page);
    }

    /**
     * 处理查看末影箱历史命令
     */
    private void handleTimeEnd(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.timeend-usage")));
            return;
        }

        String targetName = args[1];
        int page = 1;

        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
                if (page < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.invalid-page")));
                return;
            }
        }

        displaySnapshotList(player, targetName, DataManager.TYPE_ENDERCHEST, page);
    }

    /**
     * 显示快照列表（支持翻页）
     */
    private void displaySnapshotList(Player player, String playerName, String type, int page) {
        LinkedHashMap<Long, String> snapshots = dataManager.getSnapshots(playerName, type);

        if (snapshots.isEmpty()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-snapshots")));
            return;
        }

        int pageSize = plugin.getConfigManager().getPageSize();
        int totalPages = (int) Math.ceil((double) snapshots.size() / pageSize);

        if (page > totalPages) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.invalid-page-number")));
            return;
        }

        // 显示标题
        String typeDisplay = type.equals(DataManager.TYPE_INVENTORY) ?
                plugin.getLanguageManager().getMessage("snapshot.type-inventory") :
                plugin.getLanguageManager().getMessage("snapshot.type-enderchest");

        Map<String, String> headerPlaceholders = new HashMap<>();
        headerPlaceholders.put("player", playerName);
        headerPlaceholders.put("type", typeDisplay);
        headerPlaceholders.put("page", String.valueOf(page));
        headerPlaceholders.put("total", String.valueOf(totalPages));
        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.snapshot-header", headerPlaceholders)));

        // 转换为列表以支持分页
        List<Map.Entry<Long, String>> snapshotList = new ArrayList<>(snapshots.entrySet());
        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, snapshotList.size());

        // 显示当前页的快照
        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<Long, String> entry = snapshotList.get(i);
            long timestamp = entry.getKey();
            String timeStr = dateFormat.format(new Date(timestamp * 1000));

            int recordNumber = snapshotList.size() - i;
            String commandType = type.equals(DataManager.TYPE_INVENTORY) ? "openinv" : "openend";

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("number", String.valueOf(recordNumber));
            placeholders.put("time", timeStr);

            String message = plugin.getLanguageManager().getMessage("command.snapshot-item", placeholders);
            String hover = plugin.getLanguageManager().getMessage("command.snapshot-hover");
            String command = "/seekinventory " + commandType + " " + playerName + " " + i;

            IBungee.sendPartShowCommandMessage(player, message, hover, "§7§l「" + hover + "」", "§a" + hover, command, true);
        }

        // 发送翻页按钮
        if (totalPages > 1) {
            sendPageButtons(player, playerName, type, page, totalPages);
        }
    }

    /**
     * 发送翻页按钮
     *
     * @param player      玩家
     * @param playerName  目标玩家名
     * @param type        快照类型
     * @param currentPage 当前页
     * @param totalPages  总页数
     */
    private void sendPageButtons(Player player, String playerName, String type, int currentPage, int totalPages) {
        String commandType = type.equals(DataManager.TYPE_INVENTORY) ? "timeinv" : "timeend";

        String leftButton = plugin.getLanguageManager().getMessage("snapshot.button-prev");
        String separator = plugin.getLanguageManager().getMessage("snapshot.button-separator");
        String rightButton = plugin.getLanguageManager().getMessage("snapshot.button-next");

        String leftHover = plugin.getLanguageManager().getMessage("snapshot.hover-prev");
        String rightHover = plugin.getLanguageManager().getMessage("snapshot.hover-next");

        // 构建完整消息
        StringBuilder fullMessage = new StringBuilder();

        // 左按钮（上一页）
        if (currentPage > 1) {
            fullMessage.append(leftButton);
        } else {
            String disabledLeft = plugin.getLanguageManager().getMessage("snapshot.button-prev-disabled");
            fullMessage.append(disabledLeft);
        }

        fullMessage.append(separator);

        // 右按钮（下一页）
        if (currentPage < totalPages) {
            fullMessage.append(rightButton);
        } else {
            String disabledRight = plugin.getLanguageManager().getMessage("snapshot.button-next-disabled");
            fullMessage.append(disabledRight);
        }

        String message = fullMessage.toString();

        // 如果两个按钮都不可用，直接发送普通消息
        if (currentPage == 1 && currentPage == totalPages) {
            player.sendMessage(IString.color(message));
            return;
        }

        // 使用临时消息来构建点击效果
        String tempMessage = message;

        // 先处理左按钮
        if (currentPage > 1) {
            String prevCommand = "/seekinventory " + commandType + " " + playerName + " " + (currentPage - 1);
            IBungee.sendPartShowCommandMessage(player, tempMessage, leftButton, leftButton, leftHover, prevCommand, true);
            tempMessage = tempMessage.replace(leftButton, "");
        }

        // 再处理右按钮
        if (currentPage < totalPages) {
            String nextCommand = "/seekinventory " + commandType + " " + playerName + " " + (currentPage + 1);
            // 只有当左按钮已处理时才需要发送，否则整条消息已在上面发送
            if (currentPage <= 1) {
                IBungee.sendPartShowCommandMessage(player, message, rightButton, rightButton, rightHover, nextCommand, true);
            }
        }
    }

    /**
     * 处理打开历史背包命令
     */
    private void handleOpenInv(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (args.length < 3) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.openinv-usage")));
            return;
        }

        String targetName = args[1];
        int index;

        try {
            index = Integer.parseInt(args[2]);
            if (index < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.invalid-index")));
            return;
        }

        openHistorySnapshot(player, targetName, DataManager.TYPE_INVENTORY, index);
    }

    /**
     * 处理打开历史末影箱命令
     */
    private void handleOpenEnd(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (args.length < 3) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.openend-usage")));
            return;
        }

        String targetName = args[1];
        int index;

        try {
            index = Integer.parseInt(args[2]);
            if (index < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.invalid-index")));
            return;
        }

        openHistorySnapshot(player, targetName, DataManager.TYPE_ENDERCHEST, index);
    }

    /**
     * 打开历史快照
     */
    private void openHistorySnapshot(Player player, String playerName, String type, int index) {
        String snapshotData = dataManager.getSnapshot(playerName, type, index);

        if (snapshotData == null) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.snapshot-not-found")));
            return;
        }

        try {
            int size = type.equals(DataManager.TYPE_INVENTORY) ? 45 : 27;
            String titleKey = type.equals(DataManager.TYPE_INVENTORY) ? "gui.history-inventory-title" : "gui.history-enderchest-title";

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", playerName);
            String title = plugin.getLanguageManager().getMessage(titleKey, placeholders);

            Inventory inventory = Bukkit.createInventory(null, size, IString.color(title));
            ISerializer.deserializerInventory(inventory, snapshotData);
            IInventory.openInventory(inventory, player);

        } catch (Exception e) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.snapshot-open-failed")));
            plugin.sendError("打开快照失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理手动保存命令
     */
    private void handleSave(Player player) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        plugin.savePlayerSnapshot(player);
        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.manual-save-success")));
    }
}