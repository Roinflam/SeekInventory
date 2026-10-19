package com.flameforge.seekinventory.command;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.curios.CuriosHandler;
import com.flameforge.seekinventory.curios.CuriosSupport;
import com.flameforge.seekinventory.gui.CuriosHistoryInterface;
import com.flameforge.seekinventory.gui.OfflineEndInterface;
import com.flameforge.seekinventory.gui.OfflineInterface;
import com.flameforge.seekinventory.gui.OnlineCuriosInterface;
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
        // 控制台仅允许 help 和 reload 命令
        if (sender instanceof ConsoleCommandSender) {
            if (args.length == 0) {
                sendHelp(sender);
                return;
            }
            switch (args[0].toLowerCase()) {
                case "help":
                    sendHelp(sender);
                    return;
                case "reload":
                    handleReload(sender);
                    return;
                default:
                    sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.console-only")));
                    return;
            }
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

            case "curios":
                handleCurios(player, args);
                break;

            case "timecurios":
                handleTimeCurios(player, args);
                break;

            case "openinv":
                handleOpenInv(player, args);
                break;

            case "openend":
                handleOpenEnd(player, args);
                break;

            case "opencurios":
                handleOpenCurios(player, args);
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

        // 如果 Curios 可用，额外显示饰品栏相关帮助
        if (CuriosSupport.isAvailable()) {
            List<String> curiosHelp = plugin.getLanguageManager().getMessageList("command.help-curios");
            curiosHelp.forEach(msg -> sender.sendMessage(IString.color(msg)));
        }
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
     * 打开在线玩家的饰品栏，实时查看 / 编辑（/seek curios [玩家]，留空打开自己的）
     *
     * <p>饰品栏挂在玩家实体上，只有在线时才读得到；离线玩家引导去看历史快照。</p>
     */
    private void handleCurios(Player player, String[] args) {
        if (!player.hasPermission("si.open")) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }
        if (!CuriosSupport.isAvailable() || !CuriosHandler.isInitialized()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.curios-not-available")));
            return;
        }

        Player target;
        if (args.length < 2) {
            target = player;
        } else {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                String key = IPlayer.hasOfflinePlayer(args[1]) ? "command.curios-offline" : "command.player-not-found";
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage(key, "player", args[1])));
                return;
            }
        }

        // /seek 整体在异步线程分发，Curios capability 只能在主线程读写
        final Player finalTarget = target;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !finalTarget.isOnline()) {
                return;
            }
            try {
                OnlineCuriosInterface gui = new OnlineCuriosInterface(player, finalTarget, plugin);
                player.openInventory(gui.getInventory());
            } catch (IllegalStateException e) {
                plugin.sendError(e.getMessage());
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("gui.curios-read-failed")));
            }
        });
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

        for (OfflinePlayer p : IPlayer.getOfflinePlayers()) {
            searchOfflinePlayer(player, p, itemStack, false);
        }

        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.search-complete")));
    }

    /**
     * 搜索离线玩家
     */
    private void searchOfflinePlayer(Player player, OfflinePlayer offlinePlayer, ItemStack itemStack, boolean exactMatch) {
        File playerDataFolder = new File(plugin.getDataFolder().getParentFile().getParentFile(), "world/playerdata/");
        File playerData = new File(playerDataFolder, offlinePlayer.getUniqueId().toString() + ".dat");

        try {
            NBTFile nbtFile = new NBTFile(playerData);

            for (NBTCompound nbtCompound : nbtFile.getCompoundList("Inventory")) {
                ItemStack foundItem = NBTItem.convertNBTtoItem(nbtCompound);
                boolean matches = exactMatch ? foundItem.isSimilar(itemStack) : foundItem.getType().equals(itemStack.getType());
                if (matches) {
                    player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.found-offline-inv", "player", offlinePlayer.getName())));
                    break;
                }
            }

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

            for (NBTListCompound nbtListCompound : new ArrayList<>(nbtFile.getCompoundList("Inventory"))) {
                if (NBTItem.convertNBTtoItem(nbtListCompound).isSimilar(itemStack)) {
                    invList.remove(nbtListCompound);
                    player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.deleted-offline-inv", "player", offlinePlayer.getName())));
                    break;
                }
            }

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
        int page = parsePageArg(args, 2, player);
        if (page < 0) return;

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
        int page = parsePageArg(args, 2, player);
        if (page < 0) return;

        displaySnapshotList(player, targetName, DataManager.TYPE_ENDERCHEST, page);
    }

    /**
     * 处理查看饰品栏历史命令
     */
    private void handleTimeCurios(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (!CuriosSupport.isAvailable()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.curios-not-available")));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.timecurios-usage")));
            return;
        }

        String targetName = args[1];
        int page = parsePageArg(args, 2, player);
        if (page < 0) return;

        displaySnapshotList(player, targetName, DataManager.TYPE_CURIOS, page);
    }

    /**
     * 解析页码参数
     *
     * @param args     命令参数
     * @param argIndex 页码参数的索引位置
     * @param player   玩家（用于发送错误消息）
     * @return 页码（从 1 开始），解析失败返回 -1
     */
    private int parsePageArg(String[] args, int argIndex, Player player) {
        int page = 1;
        if (args.length > argIndex) {
            try {
                page = Integer.parseInt(args[argIndex]);
                if (page < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.invalid-page")));
                return -1;
            }
        }
        return page;
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

        // 根据类型确定显示名和打开命令
        String typeDisplay;
        String commandType;
        if (type.equals(DataManager.TYPE_INVENTORY)) {
            typeDisplay = plugin.getLanguageManager().getMessage("snapshot.type-inventory");
            commandType = "openinv";
        } else if (type.equals(DataManager.TYPE_ENDERCHEST)) {
            typeDisplay = plugin.getLanguageManager().getMessage("snapshot.type-enderchest");
            commandType = "openend";
        } else {
            typeDisplay = plugin.getLanguageManager().getMessage("snapshot.type-curios");
            commandType = "opencurios";
        }

        Map<String, String> headerPlaceholders = new HashMap<>();
        headerPlaceholders.put("player", playerName);
        headerPlaceholders.put("type", typeDisplay);
        headerPlaceholders.put("page", String.valueOf(page));
        headerPlaceholders.put("total", String.valueOf(totalPages));
        player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.snapshot-header", headerPlaceholders)));

        List<Map.Entry<Long, String>> snapshotList = new ArrayList<>(snapshots.entrySet());
        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, snapshotList.size());

        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<Long, String> entry = snapshotList.get(i);
            long timestamp = entry.getKey();
            String timeStr = dateFormat.format(new Date(timestamp * 1000));

            int recordNumber = snapshotList.size() - i;

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("number", String.valueOf(recordNumber));
            placeholders.put("time", timeStr);

            String message = plugin.getLanguageManager().getMessage("command.snapshot-item", placeholders);
            String hover = plugin.getLanguageManager().getMessage("command.snapshot-hover");
            String command = "/seekinventory " + commandType + " " + playerName + " " + i;

            IBungee.sendPartShowCommandMessage(player, message, hover, "§7§l「" + hover + "」", "§a" + hover, command, true);
        }

        if (totalPages > 1) {
            sendPageButtons(player, playerName, type, page, totalPages);
        }
    }

    /**
     * 发送翻页按钮
     */
    private void sendPageButtons(Player player, String playerName, String type, int currentPage, int totalPages) {
        // 根据类型确定命令前缀
        String commandType;
        if (type.equals(DataManager.TYPE_INVENTORY)) {
            commandType = "timeinv";
        } else if (type.equals(DataManager.TYPE_ENDERCHEST)) {
            commandType = "timeend";
        } else {
            commandType = "timecurios";
        }

        String leftButton = plugin.getLanguageManager().getMessage("snapshot.button-prev");
        String separator = plugin.getLanguageManager().getMessage("snapshot.button-separator");
        String rightButton = plugin.getLanguageManager().getMessage("snapshot.button-next");

        String leftHover = plugin.getLanguageManager().getMessage("snapshot.hover-prev");
        String rightHover = plugin.getLanguageManager().getMessage("snapshot.hover-next");

        StringBuilder fullMessage = new StringBuilder();

        if (currentPage > 1) {
            fullMessage.append(leftButton);
        } else {
            String disabledLeft = plugin.getLanguageManager().getMessage("snapshot.button-prev-disabled");
            fullMessage.append(disabledLeft);
        }

        fullMessage.append(separator);

        if (currentPage < totalPages) {
            fullMessage.append(rightButton);
        } else {
            String disabledRight = plugin.getLanguageManager().getMessage("snapshot.button-next-disabled");
            fullMessage.append(disabledRight);
        }

        String message = fullMessage.toString();

        if (currentPage == 1 && currentPage == totalPages) {
            player.sendMessage(IString.color(message));
            return;
        }

        String tempMessage = message;

        if (currentPage > 1) {
            String prevCommand = "/seekinventory " + commandType + " " + playerName + " " + (currentPage - 1);
            IBungee.sendPartShowCommandMessage(player, tempMessage, leftButton, leftButton, leftHover, prevCommand, true);
            tempMessage = tempMessage.replace(leftButton, "");
        }

        if (currentPage < totalPages) {
            String nextCommand = "/seekinventory " + commandType + " " + playerName + " " + (currentPage + 1);
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
        int index = parseIndexArg(args[2], player);
        if (index < 0) return;

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
        int index = parseIndexArg(args[2], player);
        if (index < 0) return;

        openHistorySnapshot(player, targetName, DataManager.TYPE_ENDERCHEST, index);
    }

    /**
     * 处理打开历史饰品栏命令
     */
    private void handleOpenCurios(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.no-permission")));
            return;
        }

        if (!CuriosSupport.isAvailable()) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.curios-not-available")));
            return;
        }

        if (args.length < 3) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.opencurios-usage")));
            return;
        }

        String targetName = args[1];
        int index = parseIndexArg(args[2], player);
        if (index < 0) return;

        openCuriosHistorySnapshot(player, targetName, index);
    }

    /**
     * 解析索引参数
     *
     * @param arg    参数字符串
     * @param player 玩家（用于发送错误消息）
     * @return 索引值，解析失败返回 -1
     */
    private int parseIndexArg(String arg, Player player) {
        try {
            int index = Integer.parseInt(arg);
            if (index < 0) throw new NumberFormatException();
            return index;
        } catch (NumberFormatException e) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.invalid-index")));
            return -1;
        }
    }

    /**
     * 打开历史快照（背包/末影箱）
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
     * 打开历史饰品栏快照（使用翻页 GUI）
     * 支持分批序列化格式（用 "|||" 分隔的多个 Inventory 序列化数据）和旧版单批格式
     *
     * @param player     查看者
     * @param playerName 目标玩家名
     * @param index      快照索引
     */
    private void openCuriosHistorySnapshot(Player player, String playerName, int index) {
        String snapshotData = dataManager.getSnapshot(playerName, DataManager.TYPE_CURIOS, index);

        if (snapshotData == null) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.snapshot-not-found")));
            return;
        }

        try {
            List<ItemStack> items = new ArrayList<>();

            // 判断是否为分批格式（包含分隔符 "|||"）
            if (snapshotData.contains(SeekInventory.CURIOS_BATCH_SEPARATOR)) {
                // 新版分批格式：按分隔符拆分，逐批反序列化
                String[] batches = snapshotData.split("\\|\\|\\|");
                for (String batchData : batches) {
                    if (batchData.isEmpty()) {
                        continue;
                    }
                    // 反序列化到临时 54 槽位 Inventory
                    Inventory tempInventory = Bukkit.createInventory(null, 54);
                    ISerializer.deserializerInventory(tempInventory, batchData);

                    // 收集所有非空物品
                    for (int i = 0; i < 54; i++) {
                        ItemStack item = tempInventory.getItem(i);
                        if (!IItem.isEmpty(item)) {
                            items.add(item);
                        }
                    }
                }
            } else {
                // 旧版单批格式：兼容历史数据
                Inventory tempInventory = Bukkit.createInventory(null, 54);
                ISerializer.deserializerInventory(tempInventory, snapshotData);

                for (int i = 0; i < 54; i++) {
                    ItemStack item = tempInventory.getItem(i);
                    if (!IItem.isEmpty(item)) {
                        items.add(item);
                    }
                }
            }

            if (items.isEmpty()) {
                player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.curios-snapshot-empty")));
                return;
            }

            // 使用翻页 GUI 展示
            CuriosHistoryInterface gui = new CuriosHistoryInterface(player, items, playerName, plugin);
            IInventory.openInventory(gui, player);

        } catch (Exception e) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("command.snapshot-open-failed")));
            plugin.sendError("打开饰品栏快照失败: " + e.getMessage());
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