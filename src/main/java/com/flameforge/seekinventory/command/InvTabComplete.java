package com.flameforge.seekinventory.command;

import com.flameforge.seekinventory.SeekInventory;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Inv 命令 Tab 补全处理器
 * 提供 /inv 命令参数的自动补全功能
 *
 * @author Tany天炎
 */
public class InvTabComplete implements TabCompleter {

    /** 插件实例 */
    private final SeekInventory plugin;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public InvTabComplete(SeekInventory plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // /inv 命令只有一个参数：玩家名
        if (args.length == 1) {
            completions.addAll(filterMatches(getPlayerNames(), args[0]));
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