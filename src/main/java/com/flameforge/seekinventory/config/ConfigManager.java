package com.flameforge.seekinventory.config;

import com.flameforge.seekinventory.SeekInventory;
import org.bukkit.configuration.file.YamlConfiguration;
import pers.tany.yukinoaapi.interfacepart.configuration.IConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置管理器
 * 负责加载、缓存和提供配置数据
 *
 * @author Tany天炎
 */
public class ConfigManager {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 配置文件对象 */
    private YamlConfiguration config;

    /** 是否启用定时保存 */
    private boolean autoSave;

    /** 保存间隔（分钟） */
    private int saveInterval;

    /** 最大保存快照数量 */
    private int maxSnapshots;

    /** 每页显示的记录数 */
    private int pageSize;

    /** 自动清理天数（超过此天数的快照将被自动清理） */
    private int autoCleanDays;

    /** 领取授权默认有效期（小时） */
    private int claimDefaultHours;

    /** 领取日志保留天数（超过且已上报的日志将被清理） */
    private int claimLogKeepDays;

    /** 发放额度时目标玩家在线且没开着别的界面，直接弹出历史备份列表 */
    private boolean claimAutoOpenOnGrant;

    /** 持有未用完额度的玩家进服时提醒 */
    private boolean claimJoinReminder;

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public ConfigManager(SeekInventory plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载配置文件
     */
    public void loadConfig() {
        config = IConfig.loadConfig(plugin, "", "config");

        if (config == null) {
            plugin.sendError(plugin.getLanguageManager().getMessage("error.config-load-failed"));
            config = new YamlConfiguration();
            setDefaults();
            return;
        }

        // 加载配置项
        this.autoSave = config.getBoolean("auto-save", true);
        this.saveInterval = config.getInt("save-interval", 30);
        this.maxSnapshots = config.getInt("max-snapshots", 10);
        this.pageSize = config.getInt("page-size", 10);
        this.autoCleanDays = config.getInt("auto-clean-days", 3);

        // 领取功能配置项（旧版本配置文件缺失时使用默认值，不影响启动）
        this.claimDefaultHours = config.getInt("claim.default-hours", 12);
        this.claimLogKeepDays = config.getInt("claim.log-keep-days", 30);
        this.claimAutoOpenOnGrant = config.getBoolean("claim.auto-open-on-grant", true);
        this.claimJoinReminder = config.getBoolean("claim.join-reminder", true);

        // 验证配置
        validateConfig();
    }

    /**
     * 设置默认值
     */
    private void setDefaults() {
        this.autoSave = true;
        this.saveInterval = 30;
        this.maxSnapshots = 10;
        this.pageSize = 10;
        this.autoCleanDays = 3;
        this.claimDefaultHours = 12;
        this.claimLogKeepDays = 30;
        this.claimAutoOpenOnGrant = true;
        this.claimJoinReminder = true;
    }

    /**
     * 验证配置的有效性
     */
    private void validateConfig() {
        if (saveInterval < 1) {
            warnReset("save-interval", "30");
            saveInterval = 30;
        }

        if (maxSnapshots < 1) {
            warnReset("max-snapshots", "10");
            maxSnapshots = 10;
        }

        if (pageSize < 1) {
            warnReset("page-size", "10");
            pageSize = 10;
        }

        if (autoCleanDays < 1) {
            warnReset("auto-clean-days", "3");
            autoCleanDays = 3;
        }

        if (claimDefaultHours < 1 || claimDefaultHours > 8760) {
            warnReset("claim.default-hours", "12");
            claimDefaultHours = 12;
        }

        if (claimLogKeepDays < 1) {
            warnReset("claim.log-keep-days", "30");
            claimLogKeepDays = 30;
        }
    }

    /**
     * 输出配置项被重置的警告
     *
     * @param field 配置项名称
     * @param value 重置后的值
     */
    private void warnReset(String field, String value) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("field", field);
        placeholders.put("value", value);
        plugin.sendWarning(plugin.getLanguageManager().getMessage("config.invalid-value-reset", placeholders));
    }

    // Getter 方法

    public boolean isAutoSave() {
        return autoSave;
    }

    public int getSaveInterval() {
        return saveInterval;
    }

    public int getMaxSnapshots() {
        return maxSnapshots;
    }

    public int getPageSize() {
        return pageSize;
    }

    /**
     * 获取自动清理天数
     *
     * @return 自动清理天数
     */
    public int getAutoCleanDays() {
        return autoCleanDays;
    }

    /**
     * 获取领取授权默认有效期（小时）
     *
     * @return 默认有效期小时数
     */
    public int getClaimDefaultHours() {
        return claimDefaultHours;
    }

    /**
     * 获取领取日志保留天数
     *
     * @return 日志保留天数
     */
    public int getClaimLogKeepDays() {
        return claimLogKeepDays;
    }

    public boolean isClaimAutoOpenOnGrant() {
        return claimAutoOpenOnGrant;
    }

    public boolean isClaimJoinReminder() {
        return claimJoinReminder;
    }

    public YamlConfiguration getConfig() {
        return config;
    }
}
