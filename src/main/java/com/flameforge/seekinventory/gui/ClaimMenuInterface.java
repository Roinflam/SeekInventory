package com.flameforge.seekinventory.gui;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.claim.ClaimGrant;
import com.flameforge.seekinventory.claim.ClaimManager;
import com.flameforge.seekinventory.config.DataManager;
import com.flameforge.seekinventory.curios.CuriosSupport;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.yukinoaapi.interfacepart.jsonText.IBungee;
import pers.tany.yukinoaapi.interfacepart.other.IRandom;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.realizationpart.builder.ItemBuilder;
import pers.tany.yukinoaapi.realizationpart.item.GlassPaneUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家的「我的历史备份」列表界面
 *
 * <p>拿到领取额度的玩家不用记命令和快照编号：发放额度时直接弹出、点聊天栏按钮或输入
 * {@code /seekclaim open} 都会打开这里，点一份备份就进入领取界面，在领取界面按 ESC 回到这里。</p>
 *
 * <p>布局（54 格）：</p>
 * <ul>
 *   <li>0-44：备份列表，新的在前，每页 45 份；空位用白色玻璃板标出列表区</li>
 *   <li>45 上一页 / 53 下一页</li>
 *   <li>46 怎么领取（说明书）/ 52 我的额度</li>
 *   <li>47 / 49 / 51：背包 / 末影箱 / 饰品栏三个分类，左右对称；没有额度的分类显示灰色</li>
 * </ul>
 *
 * @author Roinflam
 */
public class ClaimMenuInterface implements InventoryHolder {

    /** 活跃界面（查看者 UUID -> 界面） */
    private static final Map<UUID, ClaimMenuInterface> ACTIVE_GUIS = new ConcurrentHashMap<>();

    /** 静态监听器是否已注册 */
    private static boolean registered = false;

    /** 每页显示的备份数量 */
    private static final int ITEMS_PER_PAGE = 45;

    /** 三个分类按钮的槽位，与 {@link ClaimManager#allTypes()} 顺序一致 */
    private static final int[] TAB_SLOTS = {47, 49, 51};

    /** 说明书槽位 */
    private static final int GUIDE_SLOT = 46;

    /** 额度信息槽位 */
    private static final int QUOTA_SLOT = 52;

    private final SeekInventory plugin;
    private final String serial;
    private final Inventory inventory;
    private final Player viewer;
    private final String type;
    private final List<ClaimManager.SnapshotEntry> snapshots;
    private final Map<Long, Integer> takenCounts;
    private final int totalPages;
    private int currentPage;

    /** 正在加载某份备份，防止连点重复打开 */
    private boolean loading;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public static void registerListener(Plugin plugin) {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new GUIListener(), plugin);
            registered = true;
        }
    }

    public static void cleanup() {
        ACTIVE_GUIS.clear();
        registered = false;
    }

    // ==================== 打开入口 ====================

    /**
     * 打开玩家的历史备份列表（主线程调用；查库在异步线程完成后切回主线程开界面）
     *
     * @param plugin        插件实例
     * @param player        玩家
     * @param preferredType 想看的分类；为 null 或该分类没有额度时，自动挑第一个有额度的分类
     * @param page          页码（从 0 开始，越界时自动收拢）
     */
    public static void open(final SeekInventory plugin, final Player player, String preferredType, final int page) {
        final String type = pickType(plugin, player, preferredType);
        if (type == null) {
            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage("claim.my-grants-empty")));
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                final List<ClaimManager.SnapshotEntry> list =
                        plugin.getClaimManager().listSnapshots(player.getName(), type);
                final Map<Long, Integer> taken =
                        plugin.getClaimManager().countTakenBySnapshot(player.getName());

                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        // 异步期间额度可能被撤销或用完
                        if (plugin.getClaimManager().getUsableGrant(player.getName(), type) == null) {
                            if (pickType(plugin, player, null) != null) {
                                open(plugin, player, null, 0);
                            } else {
                                player.sendMessage(IString.color(
                                        plugin.getLanguageManager().getMessage("claim.my-grants-empty")));
                            }
                            return;
                        }
                        ClaimMenuInterface gui = new ClaimMenuInterface(plugin, player, type, list, taken, page);
                        player.openInventory(gui.getInventory());
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 打开某一份备份的领取界面（主线程调用）
     *
     * @param plugin     插件实例
     * @param player     玩家
     * @param type       快照类型
     * @param snapshotId 快照主键
     * @param backToMenu 领取界面按 ESC 时是否回到备份列表
     * @param menuPage   回到备份列表时停在哪一页
     */
    public static void openSnapshot(final SeekInventory plugin, final Player player, final String type,
                                    final long snapshotId, final boolean backToMenu, final int menuPage) {
        new BukkitRunnable() {
            @Override
            public void run() {
                final ClaimManager.SnapshotEntry entry = plugin.getClaimManager().getSnapshotById(snapshotId);

                // 归属与类型校验：只能打开自己的、且类型匹配的快照
                if (entry == null
                        || !entry.getPlayerName().equalsIgnoreCase(player.getName())
                        || !entry.getType().equals(type)) {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            ClaimMenuInterface menu = ACTIVE_GUIS.get(player.getUniqueId());
                            if (menu != null) {
                                menu.loading = false;
                            }
                            player.sendMessage(IString.color(
                                    plugin.getLanguageManager().getMessage("claim.snapshot-not-found")));
                        }
                    });
                    return;
                }

                final LinkedHashMap<Integer, ItemStack> items = plugin.getClaimManager().parseSnapshotItems(entry);
                final Set<Integer> taken = plugin.getClaimManager().loadTakenSlots(player.getName(), snapshotId);

                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        ClaimMenuInterface menu = ACTIVE_GUIS.get(player.getUniqueId());
                        if (menu != null) {
                            menu.loading = false;
                        }
                        if (items.isEmpty()) {
                            player.sendMessage(IString.color(
                                    plugin.getLanguageManager().getMessage("claim.snapshot-empty")));
                            return;
                        }
                        // 再次校验授权（异步期间可能已被撤销 / 用尽）
                        if (plugin.getClaimManager().getUsableGrant(player.getName(), type) == null) {
                            ClaimGrant grant = plugin.getClaimManager().getGrant(player.getName(), type);
                            String key = grant == null ? "claim.no-grant"
                                    : grant.isExpired() ? "claim.grant-expired" : "claim.grant-exhausted";
                            player.sendMessage(IString.color(plugin.getLanguageManager().getMessage(key)));
                            return;
                        }

                        ClaimInterface gui = new ClaimInterface(player, entry, items, taken, plugin);
                        if (backToMenu) {
                            gui.setBackToMenu(menuPage);
                        }
                        player.openInventory(gui.getInventory());
                        gui.sendOpenWarning();
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 玩家当前是否没开着任何容器界面（只看着自己的背包或什么都没开）
     *
     * <p>用来判断能不能直接弹界面：开着别的插件界面、箱子时弹出会打断对方，只发聊天按钮。</p>
     *
     * @param player 玩家
     * @return true=可以直接弹界面
     */
    public static boolean isIdle(Player player) {
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }

    /**
     * 在聊天栏发一个「点我打开历史备份」按钮
     *
     * @param plugin 插件实例
     * @param player 玩家
     * @param type   点开后先看哪个分类，可为 null
     */
    public static void sendOpenButton(SeekInventory plugin, Player player, String type) {
        String line = plugin.getLanguageManager().getMessage("claim.open-button-line");
        String button = plugin.getLanguageManager().getMessage("claim.open-button");
        String hover = plugin.getLanguageManager().getMessage("claim.open-button-hover");
        String command = "/seekclaim open" + (type == null ? "" : " " + shortType(type));
        IBungee.sendPartShowCommandMessage(player, IString.color(line), IString.color(button),
                IString.color(button), hover, command, true);
    }

    /**
     * 挑选要显示的分类：优先用指定的，没有额度就按 背包 / 末影箱 / 饰品栏 的顺序找第一个有额度的
     *
     * @return 分类；一个有额度的都没有时返回 null
     */
    private static String pickType(SeekInventory plugin, Player player, String preferred) {
        if (preferred != null && plugin.getClaimManager().getUsableGrant(player.getName(), preferred) != null) {
            return preferred;
        }
        for (String t : ClaimManager.allTypes()) {
            if (plugin.getClaimManager().getUsableGrant(player.getName(), t) != null) {
                return t;
            }
        }
        return null;
    }

    static String shortType(String type) {
        if (DataManager.TYPE_INVENTORY.equals(type)) {
            return "inv";
        }
        if (DataManager.TYPE_ENDERCHEST.equals(type)) {
            return "end";
        }
        return "curios";
    }

    // ==================== 界面 ====================

    private ClaimMenuInterface(SeekInventory plugin, Player viewer, String type,
                               List<ClaimManager.SnapshotEntry> snapshots,
                               Map<Long, Integer> takenCounts, int page) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.type = type;
        this.snapshots = snapshots;
        this.takenCounts = takenCounts;
        this.serial = IRandom.createRandomString(8);
        this.totalPages = Math.max(1, (int) Math.ceil((double) snapshots.size() / ITEMS_PER_PAGE));
        this.currentPage = Math.max(0, Math.min(page, totalPages - 1));

        Map<String, String> ph = new HashMap<>();
        ph.put("type", plugin.getClaimReporter().getTypeDisplay(type));
        this.inventory = Bukkit.createInventory(this, 54,
                IString.color(plugin.getLanguageManager().getMessage("claim.menu-title", ph)));

        registerListener(plugin);
        ACTIVE_GUIS.put(viewer.getUniqueId(), this);
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        ItemStack listFiller = GlassPaneUtil.getGlassPane(0)
                .setDisplayName(msg("claim.menu-list-filler-name"))
                .setLore(plugin.getLanguageManager().getMessageList("claim.menu-list-filler-lore"))
                .getItemStack();

        int start = currentPage * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int index = start + i;
            inventory.setItem(i, index < snapshots.size() ? buildEntry(index) : listFiller);
        }

        ItemStack barFiller = GlassPaneUtil.getGlassPane(15).setDisplayName("§r").getItemStack();
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, barFiller);
        }

        inventory.setItem(45, currentPage > 0
                ? pane(11, "claim.prev-page", "claim.prev-lore")
                : pane(15, "claim.no-prev", "claim.disabled-lore"));
        inventory.setItem(53, currentPage < totalPages - 1
                ? pane(14, "claim.next-page", "claim.next-lore")
                : pane(15, "claim.no-next", "claim.disabled-lore"));

        inventory.setItem(GUIDE_SLOT, new ItemBuilder(Material.BOOK)
                .setDisplayName(msg("claim.menu-guide-name"))
                .setLore(plugin.getLanguageManager().getMessageList("claim.menu-guide-lore"))
                .getItemStack());

        inventory.setItem(QUOTA_SLOT, buildQuotaItem());

        List<String> types = ClaimManager.allTypes();
        for (int i = 0; i < TAB_SLOTS.length; i++) {
            String t = types.get(i);
            if (DataManager.TYPE_CURIOS.equals(t) && !CuriosSupport.isAvailable()) {
                continue;
            }
            inventory.setItem(TAB_SLOTS[i], buildTab(t));
        }
    }

    private ItemStack buildEntry(int index) {
        ClaimManager.SnapshotEntry entry = snapshots.get(index);
        int taken = takenCounts.getOrDefault(entry.getId(), 0);

        Map<String, String> ph = new HashMap<>();
        ph.put("number", String.valueOf(snapshots.size() - index));
        ph.put("time", dateFormat.format(new Date(entry.getCreatedAt() * 1000L)));
        ph.put("ago", formatAgo(System.currentTimeMillis() / 1000L - entry.getCreatedAt()));
        ph.put("taken", String.valueOf(taken));

        String name = msg(index == 0 ? "claim.menu-entry-name-latest" : "claim.menu-entry-name", ph);
        List<String> lore = new ArrayList<>();
        lore.add(msg("claim.menu-entry-time", ph));
        lore.add(msg("claim.menu-entry-ago", ph));
        if (taken > 0) {
            lore.add(msg("claim.menu-entry-taken", ph));
        }
        lore.add("");
        lore.add(msg("claim.menu-entry-click"));

        return new ItemBuilder(typeIcon(type))
                .setDisplayName(name)
                .setLore(lore)
                .getItemStack();
    }

    private ItemStack buildTab(String t) {
        Map<String, String> ph = new HashMap<>();
        ph.put("type", plugin.getClaimReporter().getTypeDisplay(t));

        ClaimGrant grant = plugin.getClaimManager().getUsableGrant(viewer.getName(), t);
        if (grant == null) {
            return GlassPaneUtil.getGlassPane(8)
                    .setDisplayName(msg("claim.menu-tab-none-name", ph))
                    .setLore(plugin.getLanguageManager().getMessageList("claim.menu-tab-none-lore"))
                    .getItemStack();
        }

        ph.put("remaining", String.valueOf(grant.getRemaining()));
        boolean selected = t.equals(type);
        List<String> lore = new ArrayList<>();
        for (String line : plugin.getLanguageManager().getMessageList(
                selected ? "claim.menu-tab-selected-lore" : "claim.menu-tab-lore")) {
            lore.add(line.replace("{remaining}", ph.get("remaining")).replace("{type}", ph.get("type")));
        }
        return new ItemBuilder(typeIcon(t))
                .setDisplayName(msg(selected ? "claim.menu-tab-selected-name" : "claim.menu-tab-name", ph))
                .setLore(lore)
                .getItemStack();
    }

    private ItemStack buildQuotaItem() {
        List<String> lore = new ArrayList<>();
        for (ClaimGrant grant : plugin.getClaimManager().getUsableGrants(viewer.getName())) {
            Map<String, String> ph = new HashMap<>();
            ph.put("type", plugin.getClaimReporter().getTypeDisplay(grant.getGrantType()));
            ph.put("remaining", String.valueOf(grant.getRemaining()));
            ph.put("expire", dateFormat.format(new Date(grant.getExpireAt() * 1000L)));
            ph.put("left", formatLeft(grant.getRemainingSeconds()));
            lore.add(msg("claim.menu-quota-line", ph));
        }
        lore.addAll(plugin.getLanguageManager().getMessageList("claim.menu-quota-tail"));
        return new ItemBuilder(Material.CLOCK)
                .setDisplayName(msg("claim.menu-quota-name"))
                .setLore(lore)
                .getItemStack();
    }

    private ItemStack pane(int color, String nameKey, String loreKey) {
        return GlassPaneUtil.getGlassPane(color)
                .setDisplayName(msg(nameKey))
                .setLore(plugin.getLanguageManager().getMessageList(loreKey))
                .getItemStack();
    }

    private static Material typeIcon(String type) {
        if (DataManager.TYPE_INVENTORY.equals(type)) {
            return Material.CHEST;
        }
        if (DataManager.TYPE_ENDERCHEST.equals(type)) {
            return Material.ENDER_CHEST;
        }
        return Material.TOTEM_OF_UNDYING;
    }

    // ==================== 交互 ====================

    private void handleClick(InventoryClickEvent evt) {
        evt.setCancelled(true);

        int raw = evt.getRawSlot();
        if (raw < 0 || raw >= 54) {
            return;
        }

        if (raw < ITEMS_PER_PAGE) {
            int index = currentPage * ITEMS_PER_PAGE + raw;
            if (index >= snapshots.size() || loading) {
                return;
            }
            loading = true;
            openSnapshot(plugin, viewer, type, snapshots.get(index).getId(), true, currentPage);
            return;
        }

        if (raw == 45 && currentPage > 0) {
            currentPage--;
            render();
            return;
        }
        if (raw == 53 && currentPage < totalPages - 1) {
            currentPage++;
            render();
            return;
        }

        List<String> types = ClaimManager.allTypes();
        for (int i = 0; i < TAB_SLOTS.length; i++) {
            if (raw != TAB_SLOTS[i]) {
                continue;
            }
            String t = types.get(i);
            if (t.equals(type)) {
                return;
            }
            if (plugin.getClaimManager().getUsableGrant(viewer.getName(), t) == null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("type", plugin.getClaimReporter().getTypeDisplay(t));
                viewer.sendMessage(IString.color(msg("claim.menu-tab-none-hint", ph)));
                return;
            }
            open(plugin, viewer, t, 0);
            return;
        }
    }

    private String msg(String key) {
        return plugin.getLanguageManager().getMessage(key);
    }

    private String msg(String key, Map<String, String> ph) {
        return plugin.getLanguageManager().getMessage(key, ph);
    }

    private String formatAgo(long seconds) {
        seconds = Math.max(0L, seconds);
        Map<String, String> ph = new HashMap<>();
        if (seconds < 3600L) {
            ph.put("minutes", String.valueOf(Math.max(1L, seconds / 60L)));
            return msg("claim.ago-minutes", ph);
        }
        if (seconds < 86400L) {
            ph.put("hours", String.valueOf(seconds / 3600L));
            return msg("claim.ago-hours", ph);
        }
        ph.put("days", String.valueOf(seconds / 86400L));
        return msg("claim.ago-days", ph);
    }

    private String formatLeft(long seconds) {
        Map<String, String> ph = new HashMap<>();
        ph.put("hours", String.valueOf(seconds / 3600L));
        ph.put("minutes", String.valueOf((seconds % 3600L) / 60L));
        return msg("claim.duration-format", ph);
    }

    public String getSerial() {
        return serial;
    }

    private static class GUIListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventoryClick(InventoryClickEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof ClaimMenuInterface)) {
                return;
            }
            ClaimMenuInterface gui = ACTIVE_GUIS.get(evt.getWhoClicked().getUniqueId());
            if (gui == null) {
                evt.setCancelled(true);
                return;
            }
            gui.handleClick(evt);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventoryDrag(InventoryDragEvent evt) {
            if (evt.getInventory().getHolder() instanceof ClaimMenuInterface) {
                evt.setCancelled(true);
            }
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof ClaimMenuInterface)) {
                return;
            }
            UUID uuid = evt.getPlayer().getUniqueId();
            ClaimMenuInterface gui = ACTIVE_GUIS.get(uuid);
            ClaimMenuInterface holder = (ClaimMenuInterface) evt.getInventory().getHolder();
            if (gui != null && holder.getSerial().equals(gui.getSerial())) {
                ACTIVE_GUIS.remove(uuid);
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent evt) {
            ACTIVE_GUIS.remove(evt.getPlayer().getUniqueId());
        }
    }
}
