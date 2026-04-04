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
    }

    /**
     * 验证配置的有效性
     */
    private void validateConfig() {
        if (saveInterval < 1) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("field", "save-interval");
            placeholders.put("value", "30");
            plugin.sendWarning(plugin.getLanguageManager().getMessage("config.invalid-value-reset", placeholders));
            saveInterval = 30;
        }

        if (maxSnapshots < 1) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("field", "max-snapshots");
            placeholders.put("value", "10");
            plugin.sendWarning(plugin.getLanguageManager().getMessage("config.invalid-value-reset", placeholders));
            maxSnapshots = 10;
        }

        if (pageSize < 1) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("field", "page-size");
            placeholders.put("value", "10");
            plugin.sendWarning(plugin.getLanguageManager().getMessage("config.invalid-value-reset", placeholders));
            pageSize = 10;
        }
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

    public YamlConfiguration getConfig() {
        return config;
    }
}