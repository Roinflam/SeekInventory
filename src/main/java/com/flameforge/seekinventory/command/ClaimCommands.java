package com.flameforge.seekinventory.command;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.claim.ClaimGrant;
import com.flameforge.seekinventory.claim.ClaimLog;
import com.flameforge.seekinventory.claim.ClaimManager;
import com.flameforge.seekinventory.config.DataManager;
import com.flameforge.seekinventory.curios.CuriosSupport;
import com.flameforge.seekinventory.gui.ClaimMenuInterface;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.yukinoaapi.interfacepart.jsonText.IBungee;
import pers.tany.yukinoaapi.interfacepart.other.IString;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 领取功能命令执行器
 *
 * <p>玩家侧命令（权限 {@code si.claim}，默认所有人可用，但必须先获得管理员授权）：</p>
 * <ul>
 *   <li>{@code /seekclaim} —— 查看自己当前拥有的领取额度</li>
 *   <li>{@code /seekclaim list <类型> [页数]} —— 列出自己该类型的历史备份</li>
 *   <li>{@code /seekclaim open [类型]} —— 打开我的历史备份列表</li>
 *   <li>{@code /seekclaim open <类型> <快照ID>} —— 直接打开某一份备份的领取界面</li>
 * </ul>
 *
 * <p>管理员命令：</p>
 * <ul>
 *   <li>{@code /seekclaim grant <玩家> <类型|all> <次数> [小时]} —— 发放额度（{@code si.claim.grant}）</li>
 *   <li>{@code /seekclaim revoke <玩家> [类型]} —— 撤销额度（{@code si.claim.grant}）</li>
 *   <li>{@code /seekclaim query [玩家]} —— 查询当前有效授权（{@code si.claim.grant}）</li>
 *   <li>{@code /seekclaim log [玩家] [页数]} —— 查询领取日志（{@code si.claim.log}）</li>
 * </ul>
 *
 * <p>线程模型：命令由 Bukkit 在主线程派发。所有数据库读写均切换到异步线程执行，
 * 而打开 GUI、发放物品等涉及玩家状态的操作一律切回主线程，避免在混合端产生并发问题。</p>
 *
 * @author Tany天炎
 */
public class ClaimCommands implements CommandExecutor {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 时间格式化器（仅在主线程使用） */
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /** 玩家使用权限 */
    public static final String PERMISSION_CLAIM = "si.claim";

    /** 授权管理权限 */
    public static final String PERMISSION_GRANT = "si.claim.grant";

    /** 日志查询权限 */
    public static final String PERMISSION_LOG = "si.claim.log";

    /** 单次发放的最大次数上限（防止误输入超大数值） */
    private static final int MAX_GRANT_COUNT = 1000;

    /** 有效期小时数上限（1 年） */
    private static final int MAX_GRANT_HOURS = 8760;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public ClaimCommands(SeekInventory plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // 与 /seek 保持一致：不带二级参数时只显示帮助；
        // 无需单独的 help 子命令，任何无法识别的参数同样回落到帮助
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "info":
                handleMyGrants(sender);
                break;
            case "list":
                handleList(sender, args);
                break;
            case "open":
                handleOpen(sender, args);
                break;
            case "grant":
                handleGrant(sender, args);
                break;
            case "revoke":
                handleRevoke(sender, args);
                break;
            case "query":
                handleQuery(sender, args);
                break;
            case "log":
                handleLog(sender, args);
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    // ==================== 玩家侧 ====================

    /**
     * 查看自己当前拥有的领取额度（对应 {@code /seekclaim info}）
     *
     * @param sender 命令发送者
     */
    private void handleMyGrants(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, "claim.player-only");
            return;
        }
        if (!sender.hasPermission(PERMISSION_CLAIM)) {
            send(sender, "claim.no-permission");
            return;
        }

        Player player = (Player) sender;
        List<ClaimGrant> list = plugin.getClaimManager().getUsableGrants(player.getName());

        if (list.isEmpty()) {
            send(sender, "claim.my-grants-empty");
            return;
        }

        send(sender, "claim.my-grants-header");
        for (ClaimGrant grant : list) {
            Map<String, String> ph = new HashMap<>();
            ph.put("type", plugin.getClaimReporter().getTypeDisplay(grant.getGrantType()));
            ph.put("remaining", String.valueOf(grant.getRemaining()));
            ph.put("total", String.valueOf(grant.getTotal()));
            ph.put("expire", dateFormat.format(new Date(grant.getExpireAt() * 1000L)));
            ph.put("left", formatDuration(grant.getRemainingSeconds()));
            ph.put("command", "/seekclaim list " + shortType(grant.getGrantType()));
            sender.sendMessage(IString.color(
                    plugin.getLanguageManager().getMessage("claim.my-grants-item", ph)));
        }
        send(sender, "claim.my-grants-tip");
        ClaimMenuInterface.sendOpenButton(plugin, player, list.get(0).getGrantType());
    }

    /**
     * 列出自己指定类型的历史备份（可点击打开）
     *
     * @param sender 命令发送者
     * @param args   命令参数
     */
    private void handleList(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "claim.player-only");
            return;
        }
        if (!sender.hasPermission(PERMISSION_CLAIM)) {
            send(sender, "claim.no-permission");
            return;
        }
        if (args.length < 2) {
            send(sender, "claim.list-usage");
            return;
        }

        final Player player = (Player) sender;
        final String type = parseType(args[1]);
        if (type == null) {
            send(sender, "claim.invalid-type");
            return;
        }

        // 权限门槛：必须持有该类型的有效授权
        if (plugin.getClaimManager().getUsableGrant(player.getName(), type) == null) {
            sendGrantMissing(player, type);
            return;
        }

        final int page = parsePositiveInt(args, 2, 1);
        if (page < 1) {
            send(sender, "claim.invalid-page");
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                final List<ClaimManager.SnapshotEntry> snapshots =
                        plugin.getClaimManager().listSnapshots(player.getName(), type);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        renderSnapshotList(player, type, snapshots, page);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 在聊天栏渲染快照列表（带可点击按钮）
     *
     * @param player    玩家
     * @param type      快照类型
     * @param snapshots 快照列表
     * @param page      页码，从 1 开始
     */
    private void renderSnapshotList(Player player, String type,
                                    List<ClaimManager.SnapshotEntry> snapshots, int page) {
        if (snapshots.isEmpty()) {
            send(player, "claim.list-empty");
            return;
        }

        int pageSize = plugin.getConfigManager().getPageSize();
        int totalPages = Math.max(1, (int) Math.ceil((double) snapshots.size() / pageSize));
        if (page > totalPages) {
            send(player, "claim.invalid-page-number");
            return;
        }

        ClaimGrant grant = plugin.getClaimManager().getUsableGrant(player.getName(), type);

        Map<String, String> header = new HashMap<>();
        header.put("type", plugin.getClaimReporter().getTypeDisplay(type));
        header.put("page", String.valueOf(page));
        header.put("total", String.valueOf(totalPages));
        header.put("remaining", grant == null ? "0" : String.valueOf(grant.getRemaining()));
        player.sendMessage(IString.color(
                plugin.getLanguageManager().getMessage("claim.list-header", header)));

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, snapshots.size());
        String hover = plugin.getLanguageManager().getMessage("claim.list-hover");

        for (int i = start; i < end; i++) {
            ClaimManager.SnapshotEntry entry = snapshots.get(i);

            Map<String, String> ph = new HashMap<>();
            ph.put("number", String.valueOf(snapshots.size() - i));
            ph.put("time", dateFormat.format(new Date(entry.getCreatedAt() * 1000L)));

            String message = plugin.getLanguageManager().getMessage("claim.list-item", ph);
            String command = "/seekclaim open " + shortType(type) + " " + entry.getId();

            IBungee.sendPartShowCommandMessage(player, message, hover,
                    "§7§l「" + hover + "」", "§a" + hover, command, true);
        }

        if (totalPages > 1) {
            Map<String, String> ph = new HashMap<>();
            ph.put("type", shortType(type));
            ph.put("prev", String.valueOf(Math.max(1, page - 1)));
            ph.put("next", String.valueOf(Math.min(totalPages, page + 1)));
            player.sendMessage(IString.color(
                    plugin.getLanguageManager().getMessage("claim.list-page-tip", ph)));
        }
    }

    /**
     * 打开领取界面
     *
     * @param sender 命令发送者
     * @param args   命令参数
     */
    private void handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "claim.player-only");
            return;
        }
        if (!sender.hasPermission(PERMISSION_CLAIM)) {
            send(sender, "claim.no-permission");
            return;
        }

        final Player player = (Player) sender;

        // 不带快照编号：直接弹出历史备份列表（不带类型时自动挑一个有额度的）
        if (args.length < 3) {
            String type = null;
            if (args.length == 2) {
                type = parseType(args[1]);
                if (type == null) {
                    send(sender, "claim.invalid-type");
                    return;
                }
                if (plugin.getClaimManager().getUsableGrant(player.getName(), type) == null) {
                    sendGrantMissing(player, type);
                    return;
                }
            }
            ClaimMenuInterface.open(plugin, player, type, 0);
            return;
        }

        final String type = parseType(args[1]);
        if (type == null) {
            send(sender, "claim.invalid-type");
            return;
        }

        if (plugin.getClaimManager().getUsableGrant(player.getName(), type) == null) {
            sendGrantMissing(player, type);
            return;
        }

        final long snapshotId;
        try {
            snapshotId = Long.parseLong(args[2]);
            if (snapshotId <= 0L) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            send(sender, "claim.invalid-snapshot-id");
            return;
        }

        ClaimMenuInterface.openSnapshot(plugin, player, type, snapshotId, false, 0);
    }

    // ==================== 管理员侧 ====================

    /**
     * 发放领取额度
     *
     * @param sender 命令发送者
     * @param args   命令参数
     */
    private void handleGrant(final CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_GRANT)) {
            send(sender, "claim.no-permission");
            return;
        }
        if (args.length < 4) {
            send(sender, "claim.grant-usage");
            return;
        }

        final String rawName = args[1];
        if (!isValidPlayerName(rawName)) {
            send(sender, "claim.invalid-player-name");
            return;
        }

        // all = 背包、末影箱、饰品栏各给一份（没有备份的分类自动跳过）
        final boolean grantAll = "all".equalsIgnoreCase(args[2]);
        final List<String> types = new ArrayList<>();
        if (grantAll) {
            for (String t : ClaimManager.allTypes()) {
                if (DataManager.TYPE_CURIOS.equals(t) && !CuriosSupport.isAvailable()) {
                    continue;
                }
                types.add(t);
            }
        } else {
            String type = parseType(args[2]);
            if (type == null) {
                send(sender, "claim.invalid-type");
                return;
            }
            if (DataManager.TYPE_CURIOS.equals(type) && !CuriosSupport.isAvailable()) {
                send(sender, "command.curios-not-available");
                return;
            }
            types.add(type);
        }

        final int count;
        try {
            count = Integer.parseInt(args[3]);
            if (count < 1 || count > MAX_GRANT_COUNT) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            Map<String, String> ph = new HashMap<>();
            ph.put("max", String.valueOf(MAX_GRANT_COUNT));
            sender.sendMessage(IString.color(
                    plugin.getLanguageManager().getMessage("claim.invalid-count", ph)));
            return;
        }

        int parsedHours = plugin.getConfigManager().getClaimDefaultHours();
        if (args.length > 4) {
            try {
                parsedHours = Integer.parseInt(args[4]);
                if (parsedHours < 1 || parsedHours > MAX_GRANT_HOURS) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                Map<String, String> ph = new HashMap<>();
                ph.put("max", String.valueOf(MAX_GRANT_HOURS));
                sender.sendMessage(IString.color(
                        plugin.getLanguageManager().getMessage("claim.invalid-hours", ph)));
                return;
            }
        }
        final int hours = parsedHours;
        final String granter = sender.getName();

        new BukkitRunnable() {
            @Override
            public void run() {
                // 解析规范玩家名：在线优先，其次离线档案
                String canonical = rawName;
                Player online = Bukkit.getPlayerExact(rawName);
                if (online != null) {
                    canonical = online.getName();
                } else {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(rawName);
                    if (offline != null && offline.getName() != null) {
                        canonical = offline.getName();
                    }
                }
                final String finalName = canonical;

                // 该玩家必须有这一类的快照，否则授权毫无意义（多为输错名字）
                final List<ClaimGrant> granted = new ArrayList<>();
                final List<String> noSnapshot = new ArrayList<>();
                boolean failed = false;
                for (String type : types) {
                    if (plugin.getClaimManager().listSnapshots(canonical, type).isEmpty()) {
                        noSnapshot.add(type);
                        continue;
                    }
                    ClaimGrant grant = plugin.getClaimManager().grant(canonical, type, count, hours, granter);
                    if (grant == null) {
                        failed = true;
                    } else {
                        granted.add(grant);
                    }
                }
                final boolean anyFailed = failed;

                runSync(new Runnable() {
                    @Override
                    public void run() {
                        reportGrant(sender, finalName, granted, noSnapshot, anyFailed, count, hours, grantAll);
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 把发放结果告诉管理员，并通知、引导目标玩家（主线程）
     */
    private void reportGrant(CommandSender sender, String playerName, List<ClaimGrant> granted,
                             List<String> noSnapshot, boolean failed, int count, int hours, boolean grantAll) {
        if (failed) {
            send(sender, "claim.grant-failed");
        }

        for (String type : noSnapshot) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", playerName);
            ph.put("type", plugin.getClaimReporter().getTypeDisplay(type));
            sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage(
                    grantAll ? "claim.grant-all-skip" : "claim.no-snapshot-for-type", ph)));
        }

        if (granted.isEmpty()) {
            return;
        }

        for (ClaimGrant grant : granted) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", playerName);
            ph.put("type", plugin.getClaimReporter().getTypeDisplay(grant.getGrantType()));
            ph.put("count", String.valueOf(count));
            ph.put("remaining", String.valueOf(grant.getRemaining()));
            ph.put("hours", String.valueOf(hours));
            ph.put("expire", dateFormat.format(new Date(grant.getExpireAt() * 1000L)));
            sender.sendMessage(IString.color(
                    plugin.getLanguageManager().getMessage("claim.grant-success", ph)));
        }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            send(sender, "claim.grant-target-offline");
            return;
        }

        // 目标玩家在线：聊天栏说明 + 屏幕标题 + 可点按钮，没开着别的界面就直接弹出备份列表
        for (ClaimGrant grant : granted) {
            Map<String, String> ph = new HashMap<>();
            ph.put("type", plugin.getClaimReporter().getTypeDisplay(grant.getGrantType()));
            ph.put("count", String.valueOf(count));
            ph.put("remaining", String.valueOf(grant.getRemaining()));
            ph.put("expire", dateFormat.format(new Date(grant.getExpireAt() * 1000L)));
            target.sendMessage(IString.color(
                    plugin.getLanguageManager().getMessage("claim.grant-notify-line", ph)));
        }
        for (String line : plugin.getLanguageManager().getMessageList("claim.grant-notify-guide")) {
            target.sendMessage(IString.color(line));
        }

        String firstType = granted.get(0).getGrantType();
        ClaimMenuInterface.sendOpenButton(plugin, target, firstType);
        target.sendTitle(IString.color(plugin.getLanguageManager().getMessage("claim.grant-title")),
                IString.color(plugin.getLanguageManager().getMessage("claim.grant-subtitle")), 10, 60, 20);

        if (plugin.getConfigManager().isClaimAutoOpenOnGrant() && ClaimMenuInterface.isIdle(target)) {
            ClaimMenuInterface.open(plugin, target, firstType, 0);
        }
    }

    /**
     * 撤销领取额度
     *
     * @param sender 命令发送者
     * @param args   命令参数
     */
    private void handleRevoke(final CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_GRANT)) {
            send(sender, "claim.no-permission");
            return;
        }
        if (args.length < 2) {
            send(sender, "claim.revoke-usage");
            return;
        }

        final String rawName = args[1];
        if (!isValidPlayerName(rawName)) {
            send(sender, "claim.invalid-player-name");
            return;
        }

        final String type;
        if (args.length > 2) {
            type = parseType(args[2]);
            if (type == null) {
                send(sender, "claim.invalid-type");
                return;
            }
        } else {
            type = null;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                final int deleted = plugin.getClaimManager().revoke(rawName, type);

                runSync(new Runnable() {
                    @Override
                    public void run() {
                        Map<String, String> ph = new HashMap<>();
                        ph.put("player", rawName);
                        ph.put("count", String.valueOf(deleted));
                        String key = deleted > 0 ? "claim.revoke-success" : "claim.revoke-none";
                        sender.sendMessage(IString.color(
                                plugin.getLanguageManager().getMessage(key, ph)));
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 查询当前有效授权
     *
     * @param sender 命令发送者
     * @param args   命令参数
     */
    private void handleQuery(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_GRANT)) {
            send(sender, "claim.no-permission");
            return;
        }

        List<ClaimGrant> list;
        if (args.length > 1) {
            if (!isValidPlayerName(args[1])) {
                send(sender, "claim.invalid-player-name");
                return;
            }
            list = plugin.getClaimManager().getUsableGrants(args[1]);
        } else {
            list = plugin.getClaimManager().getAllUsableGrants();
        }

        if (list.isEmpty()) {
            send(sender, "claim.query-empty");
            return;
        }

        Map<String, String> header = new HashMap<>();
        header.put("count", String.valueOf(list.size()));
        sender.sendMessage(IString.color(
                plugin.getLanguageManager().getMessage("claim.query-header", header)));

        for (ClaimGrant grant : list) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", grant.getPlayerName());
            ph.put("type", plugin.getClaimReporter().getTypeDisplay(grant.getGrantType()));
            ph.put("remaining", String.valueOf(grant.getRemaining()));
            ph.put("total", String.valueOf(grant.getTotal()));
            ph.put("granter", grant.getGranter());
            ph.put("expire", dateFormat.format(new Date(grant.getExpireAt() * 1000L)));
            ph.put("left", formatDuration(grant.getRemainingSeconds()));
            sender.sendMessage(IString.color(
                    plugin.getLanguageManager().getMessage("claim.query-item", ph)));
        }
    }

    /**
     * 查询领取日志
     *
     * @param sender 命令发送者
     * @param args   命令参数
     */
    private void handleLog(final CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_LOG)) {
            send(sender, "claim.no-permission");
            return;
        }

        final String playerName;
        int argOffset = 1;
        if (args.length > 1 && !isNumeric(args[1])) {
            if (!isValidPlayerName(args[1])) {
                send(sender, "claim.invalid-player-name");
                return;
            }
            playerName = args[1];
            argOffset = 2;
        } else {
            playerName = null;
        }

        final int page = parsePositiveInt(args, argOffset, 1);
        if (page < 1) {
            send(sender, "claim.invalid-page");
            return;
        }

        final int pageSize = plugin.getConfigManager().getPageSize();

        new BukkitRunnable() {
            @Override
            public void run() {
                final int total = plugin.getClaimManager().countLogs(playerName);
                final List<ClaimLog> logs = plugin.getClaimManager().getLogs(playerName, page, pageSize);

                runSync(new Runnable() {
                    @Override
                    public void run() {
                        if (logs.isEmpty()) {
                            send(sender, "claim.log-empty");
                            return;
                        }

                        int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));

                        Map<String, String> header = new HashMap<>();
                        header.put("player", playerName == null
                                ? plugin.getLanguageManager().getMessage("claim.log-all-players")
                                : playerName);
                        header.put("page", String.valueOf(page));
                        header.put("total", String.valueOf(totalPages));
                        sender.sendMessage(IString.color(
                                plugin.getLanguageManager().getMessage("claim.log-header", header)));

                        for (ClaimLog log : logs) {
                            Map<String, String> ph = new HashMap<>();
                            ph.put("player", log.getPlayerName());
                            ph.put("type", plugin.getClaimReporter().getTypeDisplay(log.getGrantType()));
                            ph.put("time", dateFormat.format(new Date(log.getClaimedAt() * 1000L)));
                            ph.put("snapshot", dateFormat.format(new Date(log.getSnapshotTime() * 1000L)));
                            ph.put("count", String.valueOf(log.getItemCount()));
                            ph.put("items", log.getItemSummary());
                            ph.put("remaining", String.valueOf(log.getRemainingAfter()));
                            sender.sendMessage(IString.color(
                                    plugin.getLanguageManager().getMessage("claim.log-item", ph)));
                        }
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    // ==================== 工具方法 ====================

    /**
     * 发送帮助信息（管理员额外显示管理命令）
     *
     * @param sender 命令发送者
     */
    private void sendHelp(CommandSender sender) {
        for (String line : plugin.getLanguageManager().getMessageList("claim.help")) {
            sender.sendMessage(IString.color(line));
        }
        if (sender instanceof Player
                && !plugin.getClaimManager().getUsableGrants(sender.getName()).isEmpty()) {
            ClaimMenuInterface.sendOpenButton(plugin, (Player) sender, null);
        }
        if (sender.hasPermission(PERMISSION_GRANT) || sender.hasPermission(PERMISSION_LOG)) {
            for (String line : plugin.getLanguageManager().getMessageList("claim.help-admin")) {
                sender.sendMessage(IString.color(line));
            }
        }
    }

    /**
     * 提示玩家缺少授权，并区分「无授权 / 已过期 / 已用尽」
     *
     * @param player 玩家
     * @param type   授权类型
     */
    private void sendGrantMissing(Player player, String type) {
        ClaimGrant grant = plugin.getClaimManager().getGrant(player.getName(), type);
        if (grant == null) {
            send(player, "claim.no-grant");
        } else if (grant.isExpired()) {
            send(player, "claim.grant-expired");
        } else {
            send(player, "claim.grant-exhausted");
        }
    }

    /**
     * 解析类型别名为标准类型标识
     *
     * @param input 用户输入
     * @return 标准类型标识；无法识别返回 null
     */
    private String parseType(String input) {
        if (input == null) {
            return null;
        }
        String lower = input.toLowerCase();
        if ("inv".equals(lower) || "inventory".equals(lower)) {
            return DataManager.TYPE_INVENTORY;
        }
        if ("end".equals(lower) || "enderchest".equals(lower) || "ender".equals(lower)) {
            return DataManager.TYPE_ENDERCHEST;
        }
        if ("curios".equals(lower) || "curio".equals(lower)) {
            return DataManager.TYPE_CURIOS;
        }
        return null;
    }

    /**
     * 将标准类型标识转换为命令中使用的短别名
     *
     * @param type 标准类型标识
     * @return 短别名
     */
    private String shortType(String type) {
        if (DataManager.TYPE_INVENTORY.equals(type)) {
            return "inv";
        }
        if (DataManager.TYPE_ENDERCHEST.equals(type)) {
            return "end";
        }
        return "curios";
    }

    /**
     * 校验玩家名合法性（长度与字符集，防止注入型输入）
     *
     * @param name 玩家名
     * @return true=合法
     */
    private boolean isValidPlayerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || c == '\'' || c == '"' || c == ';' || c == '%') {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符串是否为纯数字
     *
     * @param input 待判断字符串
     * @return true=纯数字
     */
    private boolean isNumeric(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            if (!Character.isDigit(input.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析指定位置的正整数参数
     *
     * @param args         参数数组
     * @param index        参数下标
     * @param defaultValue 参数缺省时的默认值
     * @return 解析结果；格式非法返回 -1
     */
    private int parsePositiveInt(String[] args, int index, int defaultValue) {
        if (args.length <= index) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(args[index]);
            return value >= 1 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 将秒数格式化为「X小时Y分钟」
     *
     * @param seconds 秒数
     * @return 可读时长
     */
    private String formatDuration(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        Map<String, String> ph = new HashMap<>();
        ph.put("hours", String.valueOf(hours));
        ph.put("minutes", String.valueOf(minutes));
        return plugin.getLanguageManager().getMessage("claim.duration-format", ph);
    }

    /**
     * 向命令发送者发送一条语言文件消息
     *
     * @param sender 命令发送者
     * @param path   语言键路径
     */
    private void send(CommandSender sender, String path) {
        sender.sendMessage(IString.color(plugin.getLanguageManager().getMessage(path)));
    }

    /**
     * 将任务调度回主线程执行
     *
     * @param runnable 待执行任务
     */
    private void runSync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
