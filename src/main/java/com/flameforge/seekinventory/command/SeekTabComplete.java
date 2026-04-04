package com.flameforge.seekinventory.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import com.flameforge.seekinventory.SeekInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Seek 命令 Tab 补全处理器
 * 提供 /seek 命令参数的自动补全功能
 *
 * @author Tany天炎
 */
public class SeekTabComplete implements TabCompleter {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 主命令列表 */
    private static final List<String> MAIN_COMMANDS = Arrays.asList(
            "help", "inv", "end", "check", "checkid", "delete",
            "timeinv", "timeend", "openinv", "openend", "save", "reload"
    );

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public SeekTabComplete(SeekInventory plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // 第一个参数：主命令补全
            completions.addAll(filterMatches(MAIN_COMMANDS, args[0]));
        } else if (args.length == 2) {
            // 第二个参数：根据主命令补全
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "inv":
                case "end":
                case "timeinv":
                case "timeend":
                case "openinv":
                case "openend":
                    // 玩家名补全
                    completions.addAll(filterMatches(getPlayerNames(), args[1]));
                    break;
            }
        } else if (args.length == 3) {
            // 第三个参数
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "timeinv":
                case "timeend":
                    // 页数提示
                    completions.addAll(Arrays.asList("1", "2", "3", "5", "10"));
                    break;
                case "openinv":
                case "openend":
                    // 索引提示
                    completions.addAll(Arrays.asList("0", "1", "2", "3", "4", "5"));
                    break;
            }
        }

        return completions;
    }

    /**
     * 获取所有在线玩家名列表
     *
     * @return 玩家名列表
     */
    private List<String> getPlayerNames() {
        List<String> playerNames = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerNames.add(player.getName());
        }
        return playerNames;
    }

    /**
     * 过滤匹配的补全选项
     *
     * @param options 所有选项
     * @param input   用户输入
     * @return 匹配的选项
     */
    private List<String> filterMatches(List<String> options, String input) {
        String lowerInput = input.toLowerCase();
        return options.stream()
                .filter(option -> option.toLowerCase().startsWith(lowerInput))
                .collect(Collectors.toList());
    }
}