package com.flameforge.seekinventory.command;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.claim.ClaimGrant;
import com.flameforge.seekinventory.config.DataManager;
import com.flameforge.seekinventory.curios.CuriosSupport;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 领取命令 Tab 补全处理器
 *
 * <p>补全内容随权限动态变化：普通玩家只能看到 {@code list} / {@code open} / {@code help}，
 * 管理员额外看到 {@code grant} / {@code revoke} / {@code query} / {@code log}。
 * 饰品栏类型仅在 Curios 模组可用时出现。</p>
 *
 * @author Tany天炎
 */
public class ClaimTabComplete implements TabCompleter {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 玩家可用子命令 */
    private static final List<String> PLAYER_COMMANDS = Arrays.asList("info", "list", "open");

    /** 授权管理子命令 */
    private static final List<String> GRANT_COMMANDS = Arrays.asList("grant", "revoke", "query");

    /** 日志查询子命令 */
    private static final List<String> LOG_COMMANDS = Arrays.asList("log");

    /** 基础类型别名（始终可用） */
    private static final List<String> BASE_TYPES = Arrays.asList("inv", "end");

    /** 饰品栏类型别名（仅 Curios 可用时提供） */
    private static final String CURIOS_TYPE = "curios";

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public ClaimTabComplete(SeekInventory plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission(ClaimCommands.PERMISSION_CLAIM)) {
                subs.addAll(PLAYER_COMMANDS);
            }
            if (sender.hasPermission(ClaimCommands.PERMISSION_GRANT)) {
                subs.addAll(GRANT_COMMANDS);
            }
            if (sender.hasPermission(ClaimCommands.PERMISSION_LOG)) {
                subs.addAll(LOG_COMMANDS);
            }
            completions.addAll(filter(subs, args[0]));
            return completions;
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            switch (sub) {
                case "list":
                case "open":
                    // 玩家侧：只补全自己实际持有授权的类型
                    completions.addAll(filter(getOwnedTypes(sender), args[1]));
                    break;
                case "grant":
                case "revoke":
                case "query":
                case "log":
                    if (hasAdminPermission(sender, sub)) {
                        completions.addAll(filter(getOnlinePlayerNames(), args[1]));
                    }
                    break;
                default:
                    break;
            }
            return completions;
        }

        if (args.length == 3) {
            switch (sub) {
                case "list":
                    completions.addAll(Arrays.asList("1", "2", "3"));
                    break;
                case "grant":
                case "revoke":
                    if (sender.hasPermission(ClaimCommands.PERMISSION_GRANT)) {
                        List<String> types = getAllTypes();
                        if ("grant".equals(sub)) {
                            types.add("all");
                        }
                        completions.addAll(filter(types, args[2]));
                    }
                    break;
                case "log":
                    if (sender.hasPermission(ClaimCommands.PERMISSION_LOG)) {
                        completions.addAll(Arrays.asList("1", "2", "3"));
                    }
                    break;
                default:
                    break;
            }
            return completions;
        }

        if (args.length == 4 && "grant".equals(sub)
                && sender.hasPermission(ClaimCommands.PERMISSION_GRANT)) {
            // 发放次数提示
            completions.addAll(Arrays.asList("1", "2", "3", "5", "10"));
            return completions;
        }

        if (args.length == 5 && "grant".equals(sub)
                && sender.hasPermission(ClaimCommands.PERMISSION_GRANT)) {
            // 有效期小时数提示
            completions.addAll(Arrays.asList("12", "24", "48", "72"));
        }

        return completions;
    }

    /**
     * 判断发送者是否具备对应管理子命令的权限
     *
     * @param sender 命令发送者
     * @param sub    子命令
     * @return true=有权限
     */
    private boolean hasAdminPermission(CommandSender sender, String sub) {
        if ("log".equals(sub)) {
            return sender.hasPermission(ClaimCommands.PERMISSION_LOG);
        }
        return sender.hasPermission(ClaimCommands.PERMISSION_GRANT);
    }

    /**
     * 获取全部可用类型别名
     *
     * @return 类型别名列表
     */
    private List<String> getAllTypes() {
        List<String> types = new ArrayList<>(BASE_TYPES);
        if (CuriosSupport.isAvailable()) {
            types.add(CURIOS_TYPE);
        }
        return types;
    }

    /**
     * 获取发送者当前持有有效授权的类型别名
     *
     * @param sender 命令发送者
     * @return 类型别名列表（非玩家或无授权时为空）
     */
    private List<String> getOwnedTypes(CommandSender sender) {
        List<String> types = new ArrayList<>();
        if (!(sender instanceof Player)) {
            return types;
        }
        Player player = (Player) sender;
        for (ClaimGrant grant : plugin.getClaimManager().getUsableGrants(player.getName())) {
            String grantType = grant.getGrantType();
            if (DataManager.TYPE_INVENTORY.equals(grantType)) {
                types.add("inv");
            } else if (DataManager.TYPE_ENDERCHEST.equals(grantType)) {
                types.add("end");
            } else if (DataManager.TYPE_CURIOS.equals(grantType)) {
                types.add(CURIOS_TYPE);
            }
        }
        return types;
    }

    /**
     * 获取全部在线玩家名
     *
     * @return 玩家名列表
     */
    private List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    /**
     * 按输入前缀过滤补全项
     *
     * @param options 全部候选项
     * @param input   用户输入
     * @return 匹配项列表
     */
    private List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase();
        return options.stream()
                .filter(option -> option.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}
