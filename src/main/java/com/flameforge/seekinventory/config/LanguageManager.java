package com.flameforge.seekinventory.config;

import com.flameforge.seekinventory.SeekInventory;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pers.tany.yukinoaapi.interfacepart.configuration.IConfig;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 语言管理器
 * 负责加载和管理多语言文件（自动生成机制）
 *
 * @author Tany天炎
 */
public class LanguageManager {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 当前使用的语言代码 */
    private String currentLanguage;

    /** 语言文件配置对象 */
    private FileConfiguration langConfig;

    /** 默认语言 */
    private static final String DEFAULT_LANGUAGE = "en_us";

    /** 支持的语言文件列表 */
    private static final List<String> SUPPORTED_LANGUAGES = Arrays.asList(
            "en_us",
            "zh_cn"
    );

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public LanguageManager(SeekInventory plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载语言文件
     */
    public void loadLanguage() {
        // 处理配置文件版本
        handleConfigVersion();

        // 确保所有语言文件都已释放到磁盘
        ensureAllLanguageFiles();

        // 读取配置的语言设置
        String configLang = plugin.getConfig().getString("Language", DEFAULT_LANGUAGE);

        if ("default".equalsIgnoreCase(configLang)) {
            this.currentLanguage = detectSystemLanguage();
            plugin.getServer().getConsoleSender().sendMessage(
                    "§7[§bSeekInventory§7] §7Auto-detected system language: §e" + this.currentLanguage);
        } else {
            this.currentLanguage = configLang.toLowerCase();
        }

        // 加载目标语言文件
        YamlConfiguration targetLangConfig = IConfig.loadConfig(plugin, "lang", currentLanguage);

        if (targetLangConfig == null) {
            plugin.getServer().getConsoleSender().sendMessage(
                    "§e[SeekInventory] Failed to load language file '" + currentLanguage + "', using default language");
            this.currentLanguage = DEFAULT_LANGUAGE;
            targetLangConfig = IConfig.loadConfig(plugin, "lang", DEFAULT_LANGUAGE);
        }

        if (targetLangConfig == null) {
            plugin.getServer().getConsoleSender().sendMessage(
                    "§c[SeekInventory] Language file loading failed, using empty configuration");
            this.langConfig = new YamlConfiguration();
            return;
        }

        this.langConfig = targetLangConfig;

        // 加载默认语言作为备用
        try {
            InputStream defStream = plugin.getResource("lang/" + DEFAULT_LANGUAGE + ".yml");
            if (defStream != null) {
                FileConfiguration defConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                langConfig.setDefaults(defConfig);
                defStream.close();
            }
        } catch (Exception e) {
            plugin.getServer().getConsoleSender().sendMessage(
                    "§e[SeekInventory] Failed to load default language backup: " + e.getMessage());
        }
    }

    /**
     * 确保所有语言文件都已从jar包中释放
     */
    private void ensureAllLanguageFiles() {
        IConfig.createDir(plugin, "lang");

        List<String> createdFiles = new ArrayList<>();

        for (String langCode : SUPPORTED_LANGUAGES) {
            String fileName = langCode + ".yml";
            File langFile = new File(plugin.getDataFolder(), "lang/" + fileName);

            if (!langFile.exists()) {
                boolean created = IConfig.createResource(plugin, "lang", fileName, false);

                if (created) {
                    createdFiles.add(fileName);
                } else {
                    // 如果 jar 中不存在，从内置默认创建
                    plugin.getServer().getConsoleSender().sendMessage(
                            "§e[SeekInventory] Language file " + fileName + " not in jar, skipping");
                }
            }
        }

        if (!createdFiles.isEmpty()) {
            plugin.getServer().getConsoleSender().sendMessage(
                    "§a[SeekInventory] Auto-generated language files: §e" + String.join(", ", createdFiles));
        }
    }

    /**
     * 检测系统语言
     *
     * @return 语言代码
     */
    private String detectSystemLanguage() {
        Locale systemLocale = Locale.getDefault();
        String language = systemLocale.getLanguage().toLowerCase();
        String country = systemLocale.getCountry().toLowerCase();
        String langCode = language + "_" + country;

        File langFile = new File(plugin.getDataFolder(), "lang/" + langCode + ".yml");
        if (langFile.exists()) {
            return langCode;
        }

        String[] commonVariants = {
                language + "_" + language,
                language + "_cn",
                language + "_us",
                language + "_gb"
        };

        for (String variant : commonVariants) {
            File variantFile = new File(plugin.getDataFolder(), "lang/" + variant + ".yml");
            if (variantFile.exists()) {
                return variant;
            }
        }

        return DEFAULT_LANGUAGE;
    }

    /**
     * 检查并处理配置文件版本
     */
    private void handleConfigVersion() {
        String pluginVersion = plugin.getDescription().getVersion();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        boolean forceOverwrite = false;

        if (configFile.exists()) {
            YamlConfiguration existingConfig = YamlConfiguration.loadConfiguration(configFile);
            String configVersion = existingConfig.getString("ConfigVersion", "");

            int pluginMajor = extractMajorVersion(pluginVersion);
            int configMajor = extractMajorVersion(configVersion);

            if (pluginMajor != configMajor) {
                forceOverwrite = true;
                plugin.getServer().getConsoleSender().sendMessage(
                        "§e[SeekInventory] Major version change (" + configVersion + " → " + pluginVersion + "), overwriting config files");
            }
        } else {
            forceOverwrite = true;
        }

        IConfig.createResource(plugin, "", "config.yml", forceOverwrite);

        if (forceOverwrite) {
            IConfig.createDir(plugin, "lang");
            for (String langCode : SUPPORTED_LANGUAGES) {
                IConfig.createResource(plugin, "lang", langCode + ".yml", true);
            }

            if (configFile.exists()) {
                plugin.getServer().getConsoleSender().sendMessage(
                        "§a[SeekInventory] Config files updated to new version");
            }
        }
    }

    /**
     * 提取主版本号
     *
     * @param version 版本号字符串
     * @return 主版本号
     */
    private int extractMajorVersion(String version) {
        if (version == null || version.isEmpty()) {
            return -1;
        }

        try {
            String[] parts = version.split("\\.");
            if (parts.length > 0) {
                return Integer.parseInt(parts[0].trim());
            }
        } catch (NumberFormatException e) {
            plugin.getServer().getConsoleSender().sendMessage(
                    "§e[SeekInventory] Unable to parse version number: " + version);
        }

        return -1;
    }

    /**
     * 获取消息文本
     *
     * @param path 消息路径
     * @return 消息文本
     */
    public String getMessage(String path) {
        if (langConfig == null) {
            return "§c[Language Error] " + path;
        }

        String message = langConfig.getString(path);

        if (message == null) {
            plugin.getServer().getConsoleSender().sendMessage("§e[SeekInventory] Missing message: §7" + path);
            return "§c[Missing: " + path + "]";
        }

        return message;
    }

    /**
     * 获取消息列表
     *
     * @param path 消息路径
     * @return 消息列表
     */
    public List<String> getMessageList(String path) {
        List<String> list = new ArrayList<>();
        if (langConfig == null) {
            list.add("§c[Language Error] " + path);
            return list;
        }

        List<String> messages = langConfig.getStringList(path);

        if (messages.isEmpty()) {
            plugin.getServer().getConsoleSender().sendMessage("§e[SeekInventory] Missing message list: §7" + path);
            list.add("§c[Missing: " + path + "]");
            return list;
        }

        return messages;
    }

    /**
     * 获取带占位符替换的消息
     *
     * @param path         消息路径
     * @param placeholders 占位符映射
     * @return 替换后的消息文本
     */
    public String getMessage(String path, Map<String, String> placeholders) {
        String message = getMessage(path);

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        return message;
    }

    /**
     * 获取带单个占位符的消息
     *
     * @param path  消息路径
     * @param key   占位符键
     * @param value 占位符值
     * @return 替换后的消息文本
     */
    public String getMessage(String path, String key, String value) {
        return getMessage(path).replace("{" + key + "}", value);
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    public FileConfiguration getConfig() {
        return langConfig;
    }
}