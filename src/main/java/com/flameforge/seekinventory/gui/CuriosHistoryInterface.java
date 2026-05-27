package com.flameforge.seekinventory.gui;

import com.flameforge.seekinventory.SeekInventory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pers.tany.yukinoaapi.interfacepart.other.IRandom;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.realizationpart.builder.ItemBuilder;
import pers.tany.yukinoaapi.realizationpart.item.GlassPaneUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Curios 饰品栏历史快照查看界面
 * 管理员可拿取物品用于恢复玩家饰品，支持翻页浏览
 *
 * 布局：
 * - 第 1-5 行（槽位 0-44）：饰品物品（可拿取）
 * - 第 6 行（槽位 45-53）：导航栏（不可交互）
 *   - 槽位 45：上一页按钮
 *   - 槽位 49：页码信息
 *   - 槽位 53：下一页按钮
 *   - 其他：填充玻璃板
 *
 * @author Tany天炎
 */
public class CuriosHistoryInterface implements InventoryHolder {

    /** 活跃 GUI 实例映射（查看者 UUID -> GUI 实例） */
    private static final Map<UUID, CuriosHistoryInterface> ACTIVE_GUIS = new ConcurrentHashMap<>();

    /** 静态监听器是否已注册 */
    private static boolean registered = false;

    /** 每页显示的物品数量 */
    private static final int ITEMS_PER_PAGE = 45;

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 界面唯一标识 */
    private final String serial;

    /** 背包界面 */
    private final Inventory inventory;

    /** 查看者 */
    private final Player viewer;

    /** 所有饰品物品列表 */
    private final List<ItemStack> items;

    /** 当前页码（从 0 开始） */
    private int currentPage;

    /** 总页数 */
    private final int totalPages;

    /**
     * 注册静态监听器（仅首次调用生效）
     *
     * @param plugin 插件实例
     */
    public static void registerListener(Plugin plugin) {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new GUIListener(), plugin);
            registered = true;
        }
    }

    /**
     * 清理所有活跃实例并重置注册状态
     */
    public static void cleanup() {
        ACTIVE_GUIS.clear();
        registered = false;
    }

    /**
     * 构造函数
     *
     * @param viewer     查看者
     * @param items      饰品物品列表（所有非空物品）
     * @param targetName 目标玩家名（用于标题显示）
     * @param plugin     插件实例
     */
    public CuriosHistoryInterface(Player viewer, List<ItemStack> items, String targetName,
                                  SeekInventory plugin) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.items = items;
        this.serial = IRandom.createRandomString(8);
        this.currentPage = 0;
        this.totalPages = Math.max(1, (int) Math.ceil((double) items.size() / ITEMS_PER_PAGE));

        // 创建界面
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", targetName);
        String title = plugin.getLanguageManager().getMessage("gui.history-curios-title", placeholders);
        this.inventory = Bukkit.createInventory(this, 54, IString.color(title));

        // 确保静态监听器已注册
        registerListener(plugin);
        ACTIVE_GUIS.put(viewer.getUniqueId(), this);

        // 渲染第一页
        renderPage();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 渲染当前页内容
     */
    private void renderPage() {
        // 清空物品区域
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            inventory.setItem(i, null);
        }

        // 计算当前页的物品范围
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, items.size());

        // 放置物品
        for (int i = startIndex; i < endIndex; i++) {
            inventory.setItem(i - startIndex, items.get(i));
        }

        // 渲染导航栏
        renderNavigationBar();
    }

    /**
     * 渲染底部导航栏
     * 所有玻璃板均带有名字和描述
     */
    private void renderNavigationBar() {
        // 填充玻璃板（带名字和 lore）
        ItemStack filler = GlassPaneUtil.getGlassPane(1) // 1 = 橙色
                .setDisplayName(plugin.getLanguageManager().getMessage("gui.curios-filler-name"))
                .setLore(plugin.getLanguageManager().getMessageList("gui.curios-filler-lore"))
                .getItemStack();
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // 上一页按钮（槽位 45）
        if (currentPage > 0) {
            ItemStack prevButton = GlassPaneUtil.getGlassPane(5) // 5 = 绿色
                    .setDisplayName(plugin.getLanguageManager().getMessage("gui.curios-prev-page"))
                    .setLore(plugin.getLanguageManager().getMessageList("gui.curios-prev-lore"))
                    .getItemStack();
            inventory.setItem(45, prevButton);
        } else {
            ItemStack disabledPrev = GlassPaneUtil.getGlassPane(7) // 7 = 灰色
                    .setDisplayName(plugin.getLanguageManager().getMessage("gui.curios-no-prev"))
                    .setLore(plugin.getLanguageManager().getMessageList("gui.curios-disabled-lore"))
                    .getItemStack();
            inventory.setItem(45, disabledPrev);
        }

        // 页码信息（槽位 49）
        Map<String, String> pagePlaceholders = new HashMap<>();
        pagePlaceholders.put("page", String.valueOf(currentPage + 1));
        pagePlaceholders.put("total", String.valueOf(totalPages));
        pagePlaceholders.put("count", String.valueOf(items.size()));

        List<String> pageLoreRaw = plugin.getLanguageManager().getMessageList("gui.curios-page-lore");
        List<String> pageLore = new ArrayList<>();
        for (String line : pageLoreRaw) {
            pageLore.add(line
                    .replace("{page}", String.valueOf(currentPage + 1))
                    .replace("{total}", String.valueOf(totalPages))
                    .replace("{count}", String.valueOf(items.size())));
        }

        ItemStack pageInfo = new ItemBuilder(Material.PAPER)
                .setDisplayName(plugin.getLanguageManager().getMessage("gui.curios-page-info", pagePlaceholders))
                .setLore(pageLore)
                .getItemStack();
        inventory.setItem(49, pageInfo);

        // 下一页按钮（槽位 53）
        if (currentPage < totalPages - 1) {
            ItemStack nextButton = GlassPaneUtil.getGlassPane(5) // 5 = 绿色
                    .setDisplayName(plugin.getLanguageManager().getMessage("gui.curios-next-page"))
                    .setLore(plugin.getLanguageManager().getMessageList("gui.curios-next-lore"))
                    .getItemStack();
            inventory.setItem(53, nextButton);
        } else {
            ItemStack disabledNext = GlassPaneUtil.getGlassPane(7) // 7 = 灰色
                    .setDisplayName(plugin.getLanguageManager().getMessage("gui.curios-no-next"))
                    .setLore(plugin.getLanguageManager().getMessageList("gui.curios-disabled-lore"))
                    .getItemStack();
            inventory.setItem(53, disabledNext);
        }
    }

    /**
     * 处理点击事件
     * 导航栏区域（45-53）禁止交互，仅处理翻页
     * 物品区域（0-44）允许自由拿取，供管理员恢复物品使用
     *
     * @param evt 点击事件
     */
    private void handleClick(InventoryClickEvent evt) {
        int rawSlot = evt.getRawSlot();

        // 导航栏区域：禁止交互，处理翻页逻辑
        if (rawSlot >= 45 && rawSlot <= 53) {
            evt.setCancelled(true);

            if (rawSlot == 45 && currentPage > 0) {
                currentPage--;
                renderPage();
            } else if (rawSlot == 53 && currentPage < totalPages - 1) {
                currentPage++;
                renderPage();
            }
            return;
        }

        // 物品区域（0-44）和玩家背包区域：允许自由拿取
        // 管理员可直接取出物品用于恢复玩家饰品
    }

    /**
     * 获取界面唯一标识
     *
     * @return 唯一标识
     */
    public String getSerial() {
        return serial;
    }

    /**
     * 静态内部监听器
     * 全局只注册一次，通过 ACTIVE_GUIS 路由到具体实例
     */
    private static class GUIListener implements Listener {

        /**
         * 处理背包点击事件
         */
        @EventHandler(priority = EventPriority.LOWEST)
        private void onInventoryClick(InventoryClickEvent evt) {
            if (!(evt.getWhoClicked() instanceof Player)) {
                return;
            }
            if (evt.getRawSlot() == -999) {
                return;
            }
            if (!(evt.getInventory().getHolder() instanceof CuriosHistoryInterface)) {
                return;
            }

            Player player = (Player) evt.getWhoClicked();
            CuriosHistoryInterface gui = ACTIVE_GUIS.get(player.getUniqueId());
            if (gui != null) {
                gui.handleClick(evt);
            }
        }

        /**
         * 处理背包关闭事件
         */
        @EventHandler
        private void onInventoryClose(InventoryCloseEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof CuriosHistoryInterface)) {
                return;
            }
            if (!(evt.getPlayer() instanceof Player)) {
                return;
            }

            Player player = (Player) evt.getPlayer();
            UUID uuid = player.getUniqueId();
            CuriosHistoryInterface gui = ACTIVE_GUIS.get(uuid);

            if (gui != null) {
                CuriosHistoryInterface holder = (CuriosHistoryInterface) evt.getInventory().getHolder();
                if (holder.getSerial().equals(gui.getSerial())) {
                    ACTIVE_GUIS.remove(uuid);
                }
            }
        }
    }
}