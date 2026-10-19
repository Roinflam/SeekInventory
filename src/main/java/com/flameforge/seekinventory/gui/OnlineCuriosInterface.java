package com.flameforge.seekinventory.gui;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.curios.CuriosHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.yukinoaapi.interfacepart.other.IRandom;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.realizationpart.builder.ItemBuilder;
import pers.tany.yukinoaapi.realizationpart.item.GlassPaneUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家饰品栏（Curios）实时查看 / 编辑界面
 *
 * <p>和在线背包、末影箱一样：界面每半秒从玩家身上刷新一次，有 {@code si.edit} 权限的人
 * 可以直接拿取、放入，改动下一 tick 写回玩家的饰品槽。饰品栏只存在于在线玩家身上，
 * 离线玩家只能看历史快照（{@code /seek timecurios}）。</p>
 *
 * <p>布局（54 格）：0-44 为饰品槽，按类型标识排序，每页 45 格；45 上一页 / 53 下一页，
 * 49 为说明（本页每段格子属于哪种饰品），其余为黑色底板。槽位不足一页时多出的格子用灰色玻璃板占住，
 * 防止物品被放进不存在的槽位。</p>
 *
 * <p>写回只写「和上次渲染时不一样」的格子，没动过的槽位不会被覆盖，
 * 不会把目标玩家同一时间自己换上的饰品冲掉。</p>
 *
 * @author Roinflam
 */
public class OnlineCuriosInterface implements InventoryHolder {

    private static final Map<UUID, OnlineCuriosInterface> ACTIVE_GUIS = new ConcurrentHashMap<>();

    private static boolean registered = false;

    /** 每页饰品槽数量 */
    private static final int SLOTS_PER_PAGE = 45;

    /** 刷新间隔（tick） */
    private static final long SYNC_INTERVAL = 10L;

    private final SeekInventory plugin;
    private final String serial;
    private final Inventory inventory;
    private final Player viewer;
    private final Player target;

    /** 最近一次读到的全部饰品槽 */
    private List<CuriosHandler.CurioSlot> slots = Collections.emptyList();

    /** 当前页每格渲染时放进去的物品（写回时据此判断哪些格子被改过） */
    private final ItemStack[] rendered = new ItemStack[SLOTS_PER_PAGE];

    private int currentPage;
    private int totalPages = 1;

    /** 已安排了下一 tick 写回，期间暂停刷新 */
    private boolean writePending;

    private int syncTaskId = -1;

    public static void registerListener(Plugin plugin) {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new GUIListener(), plugin);
            registered = true;
        }
    }

    public static void cleanup() {
        for (OnlineCuriosInterface gui : ACTIVE_GUIS.values()) {
            gui.stopSyncTask();
        }
        ACTIVE_GUIS.clear();
        registered = false;
    }

    /**
     * 构造并完成首次读取（主线程调用）
     *
     * @throws IllegalStateException 读取目标玩家饰品栏失败
     */
    public OnlineCuriosInterface(Player viewer, Player target, SeekInventory plugin) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.target = target;
        this.serial = IRandom.createRandomString(8);

        Map<String, String> ph = new HashMap<>();
        ph.put("player", target.getName());
        this.inventory = Bukkit.createInventory(this, 54,
                IString.color(plugin.getLanguageManager().getMessage("gui.online-curios-title", ph)));

        // 首次读取失败直接抛给命令处理，不打开空界面
        this.slots = CuriosHandler.getCurioSlots(target);
        render();

        registerListener(plugin);
        ACTIVE_GUIS.put(viewer.getUniqueId(), this);
        startSyncTask();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ==================== 渲染与同步 ====================

    /**
     * 从目标玩家身上重新读取并渲染；读取失败时关闭界面
     */
    private void refresh() {
        if (!target.isOnline()) {
            return;
        }
        try {
            slots = CuriosHandler.getCurioSlots(target);
        } catch (IllegalStateException e) {
            plugin.sendError(e.getMessage());
            viewer.sendMessage(IString.color(plugin.getLanguageManager().getMessage("gui.curios-read-failed")));
            viewer.closeInventory();
            return;
        }
        render();
    }

    private void render() {
        totalPages = Math.max(1, (int) Math.ceil((double) slots.size() / SLOTS_PER_PAGE));
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        ItemStack noSlot = GlassPaneUtil.getGlassPane(7)
                .setDisplayName(msg("gui.curios-no-slot"))
                .getItemStack();

        int start = currentPage * SLOTS_PER_PAGE;
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            int index = start + i;
            if (index < slots.size()) {
                ItemStack item = slots.get(index).getItem();
                inventory.setItem(i, item);
                rendered[i] = item == null ? null : item.clone();
            } else {
                inventory.setItem(i, noSlot);
                rendered[i] = null;
            }
        }

        ItemStack barFiller = GlassPaneUtil.getGlassPane(15).setDisplayName("§r").getItemStack();
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, barFiller);
        }
        if (currentPage > 0) {
            inventory.setItem(45, GlassPaneUtil.getGlassPane(11)
                    .setDisplayName(msg("claim.prev-page")).getItemStack());
        }
        if (currentPage < totalPages - 1) {
            inventory.setItem(53, GlassPaneUtil.getGlassPane(14)
                    .setDisplayName(msg("claim.next-page")).getItemStack());
        }
        inventory.setItem(49, buildInfoItem());
    }

    /**
     * 说明：本页每段格子属于哪种饰品（空槽在界面上是空格子，只能靠这里对照）
     */
    private ItemStack buildInfoItem() {
        Map<String, String> ph = new HashMap<>();
        ph.put("player", target.getName());
        ph.put("count", String.valueOf(slots.size()));
        ph.put("page", String.valueOf(currentPage + 1));
        ph.put("total", String.valueOf(totalPages));

        List<String> lore = new ArrayList<>();
        lore.add(msg("gui.curios-info-count", ph));

        int start = currentPage * SLOTS_PER_PAGE;
        int end = Math.min(start + SLOTS_PER_PAGE, slots.size());
        int runStart = start;
        for (int i = start; i <= end; i++) {
            boolean boundary = i == end
                    || !slots.get(i).getIdentifier().equals(slots.get(runStart).getIdentifier());
            if (!boundary) {
                continue;
            }
            if (runStart < end) {
                Map<String, String> range = new HashMap<>();
                int from = runStart - start + 1;
                int to = i - start;
                range.put("range", from == to ? String.valueOf(from) : from + "-" + to);
                range.put("type", slots.get(runStart).getIdentifier());
                lore.add(msg("gui.curios-info-range", range));
            }
            runStart = i;
        }

        lore.add("");
        boolean canEdit = viewer.hasPermission("si.edit") && CuriosHandler.isEditSupported();
        lore.addAll(plugin.getLanguageManager().getMessageList(
                canEdit ? "gui.curios-info-edit" : "gui.curios-info-readonly"));

        return new ItemBuilder(Material.TOTEM_OF_UNDYING)
                .setDisplayName(msg("gui.curios-info-name", ph))
                .setLore(lore)
                .getItemStack();
    }

    private void startSyncTask() {
        syncTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!writePending) {
                    refresh();
                }
            }
        }.runTaskTimer(plugin, SYNC_INTERVAL, SYNC_INTERVAL).getTaskId();
    }

    private void stopSyncTask() {
        if (syncTaskId != -1) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
            syncTaskId = -1;
        }
    }

    /**
     * 下一 tick 把界面上改过的格子写回玩家（点击事件结算后格子内容才是最终值）
     */
    private void scheduleWrite() {
        if (writePending) {
            return;
        }
        writePending = true;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                writePending = false;
                if (!target.isOnline()) {
                    return;
                }
                writeBack();
                refresh();
            }
        });
    }

    private void writeBack() {
        int start = currentPage * SLOTS_PER_PAGE;
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            int index = start + i;
            if (index >= slots.size()) {
                break;
            }
            ItemStack now = inventory.getItem(i);
            if (sameItem(now, rendered[i])) {
                continue;
            }
            CuriosHandler.CurioSlot slot = slots.get(index);
            try {
                CuriosHandler.setCurio(target, slot.getIdentifier(), slot.getIndex(), isEmpty(now) ? null : now);
                rendered[i] = isEmpty(now) ? null : now.clone();
            } catch (IllegalStateException e) {
                plugin.sendError(e.getMessage());
                viewer.sendMessage(IString.color(msg("gui.curios-write-failed")));
            }
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        if (isEmpty(a) || isEmpty(b)) {
            return isEmpty(a) && isEmpty(b);
        }
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    // ==================== 交互 ====================

    private void handleClick(InventoryClickEvent evt) {
        int raw = evt.getRawSlot();
        boolean inTop = raw >= 0 && raw < 54;

        // 底栏：只处理翻页
        if (inTop && raw >= SLOTS_PER_PAGE) {
            evt.setCancelled(true);
            if (raw == 45 && currentPage > 0) {
                currentPage--;
                render();
            } else if (raw == 53 && currentPage < totalPages - 1) {
                currentPage++;
                render();
            }
            return;
        }

        boolean canEdit = viewer.hasPermission("si.edit") && CuriosHandler.isEditSupported();
        if (!canEdit) {
            evt.setCancelled(true);
            if (inTop) {
                viewer.sendMessage(IString.color(msg("gui.no-edit-permission")));
            }
            return;
        }

        // 双击收集会把底栏玻璃板也吸到光标上；不存在的槽位不让放东西
        if (evt.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || (inTop && currentPage * SLOTS_PER_PAGE + raw >= slots.size())) {
            evt.setCancelled(true);
            return;
        }

        // 只动自己背包、又不会把东西挪进界面的点击，不用写回
        if (!inTop && evt.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return;
        }
        scheduleWrite();
    }

    private void handleDrag(InventoryDragEvent evt) {
        boolean touchesTop = false;
        for (int raw : evt.getRawSlots()) {
            if (raw < 54) {
                touchesTop = true;
                if (raw >= SLOTS_PER_PAGE || currentPage * SLOTS_PER_PAGE + raw >= slots.size()
                        || !viewer.hasPermission("si.edit") || !CuriosHandler.isEditSupported()) {
                    evt.setCancelled(true);
                    return;
                }
            }
        }
        if (touchesTop) {
            scheduleWrite();
        }
    }

    private void handleTargetQuit() {
        Map<String, String> ph = new HashMap<>();
        ph.put("player", target.getName());
        viewer.sendMessage(IString.color(plugin.getLanguageManager().getMessage("gui.target-offline-curios", ph)));
        viewer.closeInventory();
    }

    private String msg(String key) {
        return plugin.getLanguageManager().getMessage(key);
    }

    private String msg(String key, Map<String, String> ph) {
        return plugin.getLanguageManager().getMessage(key, ph);
    }

    public String getSerial() {
        return serial;
    }

    public Player getTarget() {
        return target;
    }

    private static class GUIListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventoryClick(InventoryClickEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof OnlineCuriosInterface)) {
                return;
            }
            OnlineCuriosInterface gui = ACTIVE_GUIS.get(evt.getWhoClicked().getUniqueId());
            if (gui == null) {
                evt.setCancelled(true);
                return;
            }
            gui.handleClick(evt);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventoryDrag(InventoryDragEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof OnlineCuriosInterface)) {
                return;
            }
            OnlineCuriosInterface gui = ACTIVE_GUIS.get(evt.getWhoClicked().getUniqueId());
            if (gui == null) {
                evt.setCancelled(true);
                return;
            }
            gui.handleDrag(evt);
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof OnlineCuriosInterface)) {
                return;
            }
            UUID uuid = evt.getPlayer().getUniqueId();
            OnlineCuriosInterface gui = ACTIVE_GUIS.get(uuid);
            OnlineCuriosInterface holder = (OnlineCuriosInterface) evt.getInventory().getHolder();
            if (gui != null && holder.getSerial().equals(gui.getSerial())) {
                ACTIVE_GUIS.remove(uuid);
                gui.stopSyncTask();
                // 关界面前最后一次点击可能还没写回
                if (gui.writePending && gui.target.isOnline()) {
                    gui.writePending = false;
                    gui.writeBack();
                }
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent evt) {
            UUID quitter = evt.getPlayer().getUniqueId();
            for (OnlineCuriosInterface gui : new ArrayList<>(ACTIVE_GUIS.values())) {
                if (gui.getTarget().getUniqueId().equals(quitter)) {
                    gui.handleTargetQuit();
                }
            }
            OnlineCuriosInterface own = ACTIVE_GUIS.remove(quitter);
            if (own != null) {
                own.stopSyncTask();
            }
        }
    }
}
