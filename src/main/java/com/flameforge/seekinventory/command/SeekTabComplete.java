package com.flameforge.seekinventory.command;

import com.flameforge.seekinventory.SeekInventory;
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
 * Seek 命令 Tab 补全处理器
 * 提供 /seek 命令参数的自动补全功能
 *
 * 注意：Curios 相关子命令（timecurios / opencurios）仅在 CuriosSupport.isAvailable() 返回 true 时
 * 才会出现在 Tab 补全列表中。在纯 Bukkit/Spigot/Paper 等非模组服或缺少 Curios 模组的环境下，
 * 这些命令对用户完全不可见，保持与 /seek help 帮助信息和命令处理逻辑的一致性。
 *
 * @author Tany天炎
 */
public class SeekTabComplete implements TabCompleter {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 基础主命令列表（始终可用） */
    private static final List<String> BASE_COMMANDS = Arrays.asList(
            "help", "inv", "end", "check", "checkid", "delete",
            "timeinv", "timeend",
            "openinv", "openend",
            "save", "reload"
    );

    /** Curios 相关主命令列表（仅在 CuriosSupport.isAvailable() 为 true 时显示） */
    private static final List<String> CURIOS_COMMANDS = Arrays.asList(
            "timecurios", "opencurios"
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
            // 第一个参数：根据 Curios 可用性动态构建主命令列表
            List<String> mainCommands = new ArrayList<>(BASE_COMMANDS);
            if (CuriosSupport.isAvailable()) {
                mainCommands.addAll(CURIOS_COMMANDS);
            }
            completions.addAll(filterMatches(mainCommands, args[0]));
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
                    // 玩家名补全（始终可用）
                    completions.addAll(filterMatches(getPlayerNames(), args[1]));
                    break;
                case "timecurios":
                case "opencurios":
                    // 仅在 Curios 可用时才提供补全
                    // 防止用户通过手动输入命令名后仍能获得 Tab 补全提示
                    if (CuriosSupport.isAvailable()) {
                        completions.addAll(filterMatches(getPlayerNames(), args[1]));
                    }
                    break;
            }
        } else if (args.length == 3) {
            // 第三个参数：根据主命令补全
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "timeinv":
                case "timeend":
                    // 页数提示（始终可用）
                    completions.addAll(Arrays.asList("1", "2", "3", "5", "10"));
                    break;
                case "openinv":
                case "openend":
                    // 索引提示（始终可用）
                    completions.addAll(Arrays.asList("0", "1", "2", "3", "4", "5"));
                    break;
                case "timecurios":
                    // 页数提示（仅在 Curios 可用时）
                    if (CuriosSupport.isAvailable()) {
                        completions.addAll(Arrays.asList("1", "2", "3", "5", "10"));
                    }
                    break;
                case "opencurios":
                    // 索引提示（仅在 Curios 可用时）
                    if (CuriosSupport.isAvailable()) {
                        completions.addAll(Arrays.asList("0", "1", "2", "3", "4", "5"));
                    }
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