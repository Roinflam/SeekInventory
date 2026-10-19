package com.flameforge.seekinventory.gui;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.claim.ClaimGrant;
import com.flameforge.seekinventory.claim.ClaimLog;
import com.flameforge.seekinventory.claim.ClaimManager;
import com.flameforge.seekinventory.config.DataManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pers.tany.yukinoaapi.interfacepart.item.IItem;
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
 * 玩家历史备份领取界面
 *
 * <p>与管理员查看界面的差异：</p>
 * <ul>
 *   <li>完全只读：所有点击与拖拽默认取消，玩家无法放入 / 换位 / 丢弃任何物品</li>
 *   <li>唯一可用操作为 <b>Shift + 左键</b>，领取该槽位的全部物品，消耗 1 次额度</li>
 *   <li>已领取过的槽位显示为灰色标记，不可再次领取（防止同一物品被重复复制）</li>
 *   <li>额度归零时自动关闭界面，玩家随即失去查看历史备份的权限</li>
 *   <li>关闭界面时把本次会话的全部领取行为汇总上报给管理员</li>
 * </ul>
 *
 * <p>布局按快照类型区分，与管理员查看历史快照时的界面保持一致：</p>
 * <ul>
 *   <li><b>背包</b>：45 格容器，槽位与真实背包一一对应（0-35 主背包、36-39 盔甲、40 副手），
 *       41-44 在真实背包中不存在，正好用来放 4 块规则说明玻璃板；不分页</li>
 *   <li><b>末影箱</b>：真实容量 27 格，界面额外补一行凑成 36 格，
 *       底部一行用于放置规则说明玻璃板；不分页</li>
 *   <li><b>饰品栏</b>：饰品数量可能远超一屏，采用 54 格容器，
 *       0-44 为物品区（每页 45 件），45-53 为导航栏，
 *       其中 45 上一页 / 49 页码 / 53 下一页，47/48/50/51 放置规则说明玻璃板</li>
 * </ul>
 *
 * @author Tany天炎
 */
public class ClaimInterface implements InventoryHolder {

    /** 活跃 GUI 实例映射（查看者 UUID -> GUI 实例） */
    private static final Map<UUID, ClaimInterface> ACTIVE_GUIS = new ConcurrentHashMap<>();

    /** 静态监听器是否已注册 */
    private static boolean registered = false;

    /** 每页显示的物品数量 */
    private static final int ITEMS_PER_PAGE = 45;

    /** 规则说明玻璃板的数量（对应语言文件中的 claim.rule1 ~ claim.rule4） */
    private static final int RULE_PANE_COUNT = 4;

    /** 各块规则玻璃板的颜色（依次为 黄 / 橙 / 红 / 品红，语义由浅入深） */
    private static final int[] RULE_PANE_COLORS = {4, 1, 14, 2};

    /** 末影箱界面规则行中，四块规则玻璃板所在的槽位 */
    private static final int[] ENDERCHEST_RULE_SLOTS = {28, 30, 32, 34};

    /** 饰品栏界面导航栏中，四块规则玻璃板所在的槽位（分列页码两侧） */
    private static final int[] CURIOS_RULE_SLOTS = {47, 48, 50, 51};

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 界面唯一标识 */
    private final String serial;

    /** 领取界面容器 */
    private final Inventory inventory;

    /** 领取者 */
    private final Player viewer;

    /** 快照类型 */
    private final String type;

    /** 快照主键 */
    private final long snapshotId;

    /** 快照记录时间（秒级时间戳） */
    private final long snapshotTime;

    /** 是否启用分页模式（仅饰品栏为 true） */
    private final boolean paged;

    /** 界面容器总格数 */
    private final int guiSize;

    /** 全局索引有序列表（与 itemMap 的 key 顺序一致） */
    private final List<Integer> globalIndexes;

    /** 全局索引 -> 物品映射 */
    private final LinkedHashMap<Integer, ItemStack> itemMap;

    /** 已领取的全局索引集合（与 ClaimManager 内存缓存为同一对象引用） */
    private final Set<Integer> takenSlots;

    /** 当前页码（从 0 开始） */
    private int currentPage;

    /** 总页数 */
    private final int totalPages;

    /** 本次会话领取记录：物品显示名 -> 累计数量（保持领取顺序） */
    private final LinkedHashMap<String, Integer> sessionItems = new LinkedHashMap<>();

    /** 本次会话领取的件数（即消耗的额度次数） */
    private int sessionCount;

    /** 界面是否已结算上报，防止重复上报 */
    private boolean reported;

    /** 从备份列表点进来的：按 ESC 关闭后回到备份列表 */
    private boolean backToMenu;

    /** 回到备份列表时停在哪一页 */
    private int menuPage;

    /** 时间格式化器 */
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

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
     * 构造函数（必须在主线程调用）
     *
     * @param viewer     领取者
     * @param entry      快照条目（元数据）
     * @param itemMap    全局索引 -&gt; 物品映射，由 ClaimManager 异步解析后传入
     * @param takenSlots 已领取槽位集合，由 ClaimManager 异步加载后传入
     * @param plugin     插件实例
     */
    public ClaimInterface(Player viewer, ClaimManager.SnapshotEntry entry,
                          LinkedHashMap<Integer, ItemStack> itemMap,
                          Set<Integer> takenSlots, SeekInventory plugin) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.type = entry.getType();
        this.snapshotId = entry.getId();
        this.snapshotTime = entry.getCreatedAt();
        this.itemMap = itemMap;
        this.takenSlots = takenSlots;
        this.globalIndexes = new ArrayList<>(itemMap.keySet());
        this.serial = IRandom.createRandomString(8);
        this.currentPage = 0;

        // 背包 / 末影箱的槽位数固定且在一屏之内，直接对标管理员界面的原生布局；
        // 饰品栏数量不可预知，必须分页
        if (DataManager.TYPE_CURIOS.equals(this.type)) {
            this.paged = true;
            this.guiSize = 54;
            this.totalPages = Math.max(1, (int) Math.ceil((double) globalIndexes.size() / ITEMS_PER_PAGE));
        } else {
            this.paged = false;
            // 背包本身 45 格且 41-44 空置，可直接放规则板；
            // 末影箱只有 27 格没有富余，额外补一行（27 -> 36）专门承载规则说明
            this.guiSize = DataManager.TYPE_INVENTORY.equals(this.type) ? 45 : 36;
            this.totalPages = 1;
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("type", plugin.getClaimReporter().getTypeDisplay(type));
        ph.put("time", dateFormat.format(new Date(snapshotTime * 1000L)));
        String title = plugin.getLanguageManager().getMessage("claim.gui-title", ph);

        this.inventory = Bukkit.createInventory(this, guiSize, IString.color(title));

        registerListener(plugin);
        ACTIVE_GUIS.put(viewer.getUniqueId(), this);

        renderPage();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 标记为从备份列表打开：关闭后回到列表的第 {@code page} 页
     *
     * @param page 列表页码（从 0 开始）
     */
    public void setBackToMenu(int page) {
        this.backToMenu = true;
        this.menuPage = page;
    }

    // ==================== 渲染 ====================

    /**
     * 渲染界面内容
     *
     * <p>分页模式下渲染当前页物品区与底部导航栏；
     * 非分页模式下界面槽位与快照槽位一一对应，不渲染导航栏。</p>
     */
    private void renderPage() {
        if (paged) {
            renderPagedItems();
            renderNavigationBar();
        } else {
            renderDirectItems();
        }
    }

    /**
     * 渲染分页模式的物品区（饰品栏专用）
     */
    private void renderPagedItems() {
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            inventory.setItem(i, null);
        }

        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, globalIndexes.size());

        for (int i = start; i < end; i++) {
            int globalIndex = globalIndexes.get(i);
            inventory.setItem(i - start, buildSlotItem(globalIndex));
        }
    }

    /**
     * 渲染一一对应模式的物品区（背包 / 末影箱）
     *
     * <p>槽位含义与管理员查看历史快照时完全一致：背包 0-35 为主背包、36-39 为盔甲、40 为副手，
     * 末影箱 0-26 为箱内格子。之后再渲染规则说明区。</p>
     */
    private void renderDirectItems() {
        for (int slot = 0; slot < guiSize; slot++) {
            ItemStack item = itemMap.get(slot);
            inventory.setItem(slot, item == null ? null : buildSlotItem(slot));
        }

        renderRuleArea();
    }

    /**
     * 渲染规则说明区（仅一一对应模式调用）
     *
     * <p>背包直接占用真实背包中不存在的 41-44 四格；
     * 末影箱使用额外补出的第 4 行（27-35），四块规则板居中分布，其余填充只读警示板。</p>
     */
    private void renderRuleArea() {
        if (DataManager.TYPE_INVENTORY.equals(type)) {
            for (int i = 0; i < RULE_PANE_COUNT; i++) {
                inventory.setItem(41 + i, buildRulePane(i));
            }
            return;
        }

        ItemStack filler = buildFiller();
        for (int slot = 27; slot < 36; slot++) {
            inventory.setItem(slot, filler);
        }
        for (int i = 0; i < RULE_PANE_COUNT; i++) {
            inventory.setItem(ENDERCHEST_RULE_SLOTS[i], buildRulePane(i));
        }
    }

    /**
     * 构建一块规则说明玻璃板
     *
     * @param index 规则序号，取值 0 ~ {@link #RULE_PANE_COUNT}-1，
     *              对应语言文件中的 {@code claim.rule1} ~ {@code claim.rule4}
     * @return 规则说明物品
     */
    private ItemStack buildRulePane(int index) {
        String key = "claim.rule" + (index + 1);
        return GlassPaneUtil.getGlassPane(RULE_PANE_COLORS[index])
                .setDisplayName(plugin.getLanguageManager().getMessage(key + "-name"))
                .setLore(plugin.getLanguageManager().getMessageList(key + "-lore"))
                .getItemStack();
    }

    /**
     * 构建某个快照槽位对应的展示物品（已领取则显示灰色标记）
     *
     * @param globalIndex 槽位全局索引
     * @return 展示用物品，槽位为空时返回 null
     */
    private ItemStack buildSlotItem(int globalIndex) {
        ItemStack origin = itemMap.get(globalIndex);
        if (origin == null) {
            return null;
        }
        return takenSlots.contains(globalIndex) ? buildTakenMarker(origin) : withTakeHint(origin);
    }

    /**
     * 给展示用的物品副本末尾加上「Shift + 左键领取」提示，原物品不动（发放时用的是 itemMap 里的原件）
     *
     * @param origin 原始物品
     * @return 带提示的副本；物品没有 ItemMeta 时原样返回
     */
    private ItemStack withTakeHint(ItemStack origin) {
        ItemStack display = origin.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            return origin;
        }
        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
        lore.addAll(plugin.getLanguageManager().getMessageList("claim.item-hint-lore"));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    /**
     * 构建只读警示填充玻璃板
     *
     * @return 填充物品
     */
    private ItemStack buildFiller() {
        return GlassPaneUtil.getGlassPane(14) // 14 = 红色，强调只读警示
                .setDisplayName(plugin.getLanguageManager().getMessage("claim.filler-name"))
                .setLore(plugin.getLanguageManager().getMessageList("claim.filler-lore"))
                .getItemStack();
    }

    /**
     * 构建「已领取」占位标记
     *
     * @param origin 原始物品，用于在 lore 中回显名称
     * @return 灰色玻璃板标记物品
     */
    private ItemStack buildTakenMarker(ItemStack origin) {
        List<String> lore = new ArrayList<>();
        Map<String, String> ph = new HashMap<>();
        ph.put("item", getDisplayName(origin));
        lore.add(plugin.getLanguageManager().getMessage("claim.taken-lore-item", ph));
        lore.addAll(plugin.getLanguageManager().getMessageList("claim.taken-lore"));

        return GlassPaneUtil.getGlassPane(7) // 7 = 灰色
                .setDisplayName(plugin.getLanguageManager().getMessage("claim.taken-name"))
                .setLore(lore)
                .getItemStack();
    }

    /**
     * 渲染底部导航栏（仅分页模式调用）
     */
    private void renderNavigationBar() {
        ItemStack filler = buildFiller();
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // 规则说明板分列页码两侧，不占用 45 / 49 / 53 三个功能槽位
        for (int i = 0; i < RULE_PANE_COUNT; i++) {
            inventory.setItem(CURIOS_RULE_SLOTS[i], buildRulePane(i));
        }

        // 上一页
        if (currentPage > 0) {
            inventory.setItem(45, GlassPaneUtil.getGlassPane(5) // 5 = 绿色
                    .setDisplayName(plugin.getLanguageManager().getMessage("claim.prev-page"))
                    .setLore(plugin.getLanguageManager().getMessageList("claim.prev-lore"))
                    .getItemStack());
        } else {
            inventory.setItem(45, GlassPaneUtil.getGlassPane(7)
                    .setDisplayName(plugin.getLanguageManager().getMessage("claim.no-prev"))
                    .setLore(plugin.getLanguageManager().getMessageList("claim.disabled-lore"))
                    .getItemStack());
        }

        // 页码 / 额度信息
        ClaimGrant grant = plugin.getClaimManager().getUsableGrant(viewer.getName(), type);
        int remaining = grant == null ? 0 : grant.getRemaining();

        Map<String, String> ph = new HashMap<>();
        ph.put("page", String.valueOf(currentPage + 1));
        ph.put("total", String.valueOf(totalPages));
        ph.put("remaining", String.valueOf(remaining));
        ph.put("count", String.valueOf(globalIndexes.size()));

        List<String> infoLore = new ArrayList<>();
        for (String line : plugin.getLanguageManager().getMessageList("claim.info-lore")) {
            infoLore.add(line
                    .replace("{page}", String.valueOf(currentPage + 1))
                    .replace("{total}", String.valueOf(totalPages))
                    .replace("{remaining}", String.valueOf(remaining))
                    .replace("{count}", String.valueOf(globalIndexes.size())));
        }

        inventory.setItem(49, new ItemBuilder(Material.PAPER)
                .setDisplayName(plugin.getLanguageManager().getMessage("claim.info-name", ph))
                .setLore(infoLore)
                .getItemStack());

        // 下一页
        if (currentPage < totalPages - 1) {
            inventory.setItem(53, GlassPaneUtil.getGlassPane(5)
                    .setDisplayName(plugin.getLanguageManager().getMessage("claim.next-page"))
                    .setLore(plugin.getLanguageManager().getMessageList("claim.next-lore"))
                    .getItemStack());
        } else {
            inventory.setItem(53, GlassPaneUtil.getGlassPane(7)
                    .setDisplayName(plugin.getLanguageManager().getMessage("claim.no-next"))
                    .setLore(plugin.getLanguageManager().getMessageList("claim.disabled-lore"))
                    .getItemStack());
        }
    }

    // ==================== 交互处理 ====================

    /**
     * 处理点击事件（由静态监听器路由调用，运行于主线程）
     *
     * <p>安全策略：无条件取消事件，再按规则决定是否执行领取或翻页。
     * 这样可彻底杜绝借助 Shift 转移、数字键换位、丢弃等方式把物品放入假容器造成的物品丢失。</p>
     *
     * @param evt 点击事件
     */
    private void handleClick(InventoryClickEvent evt) {
        evt.setCancelled(true);

        int rawSlot = evt.getRawSlot();

        // 点击自己的背包区域：仅拦截，不做任何处理
        if (rawSlot < 0 || rawSlot >= guiSize) {
            return;
        }

        // 分页模式的导航栏：处理翻页
        if (paged && rawSlot >= ITEMS_PER_PAGE) {
            if (rawSlot == 45 && currentPage > 0) {
                currentPage--;
                renderPage();
            } else if (rawSlot == 53 && currentPage < totalPages - 1) {
                currentPage++;
                renderPage();
            }
            return;
        }

        int globalIndex = resolveGlobalIndex(rawSlot);

        // 空槽位或填充占位：静默拦截，不打扰玩家
        if (globalIndex < 0 || !itemMap.containsKey(globalIndex)) {
            return;
        }

        // 有物品的槽位：只接受 Shift + 左键
        if (evt.getClick() != ClickType.SHIFT_LEFT) {
            sendMessage("claim.click-hint");
            return;
        }

        handleTake(globalIndex);
    }

    /**
     * 把界面槽位换算为快照槽位的全局索引
     *
     * @param rawSlot 界面槽位
     * @return 全局索引；无对应槽位时返回 -1
     */
    private int resolveGlobalIndex(int rawSlot) {
        if (!paged) {
            // 一一对应模式：界面槽位即快照槽位
            return rawSlot;
        }
        int listIndex = currentPage * ITEMS_PER_PAGE + rawSlot;
        if (listIndex < 0 || listIndex >= globalIndexes.size()) {
            return -1;
        }
        return globalIndexes.get(listIndex);
    }

    /**
     * 执行领取逻辑（主线程）
     *
     * @param globalIndex 目标槽位的全局索引
     */
    private void handleTake(int globalIndex) {
        ItemStack origin = itemMap.get(globalIndex);
        if (origin == null) {
            return;
        }

        // 前置判断：该槽位是否已被领取
        if (takenSlots.contains(globalIndex)) {
            sendMessage("claim.already-taken");
            renderPage();
            return;
        }

        // 前置判断：背包空间是否足够（不足则拒绝，不做掉落处理）
        if (!hasEnoughSpace(origin)) {
            sendMessage("claim.inventory-full");
            return;
        }

        // 扣减额度并标记槽位（内存原子操作 + 异步落库）
        ClaimManager.ConsumeResult result =
                plugin.getClaimManager().tryConsume(viewer.getName(), type, snapshotId, globalIndex);

        switch (result) {
            case NO_GRANT:
                sendMessage("claim.no-grant");
                closeLater();
                return;
            case EXPIRED:
                sendMessage("claim.grant-expired");
                closeLater();
                return;
            case EXHAUSTED:
                sendMessage("claim.grant-exhausted");
                closeLater();
                return;
            case ALREADY_TAKEN:
                sendMessage("claim.already-taken");
                renderPage();
                return;
            default:
                break;
        }

        // 发放物品
        ItemStack give = origin.clone();
        Map<Integer, ItemStack> leftover = viewer.getInventory().addItem(give);
        if (!leftover.isEmpty()) {
            // 理论上不会发生（已做空间校验），兜底掉落避免物品凭空消失
            for (ItemStack rest : leftover.values()) {
                viewer.getWorld().dropItemNaturally(viewer.getLocation(), rest);
            }
        }
        viewer.updateInventory();

        // 记录本次会话（日志使用去色名称，避免颜色码入库与污染上报文本）
        String plainName = getPlainName(origin);
        sessionItems.put(plainName, sessionItems.getOrDefault(plainName, 0) + origin.getAmount());
        sessionCount++;

        ClaimGrant grant = plugin.getClaimManager().getUsableGrant(viewer.getName(), type);
        int remaining = grant == null ? 0 : grant.getRemaining();

        Map<String, String> ph = new HashMap<>();
        ph.put("item", getDisplayName(origin));
        ph.put("amount", String.valueOf(origin.getAmount()));
        ph.put("remaining", String.valueOf(remaining));
        viewer.sendMessage(IString.color(
                plugin.getLanguageManager().getMessage("claim.take-success", ph)));

        renderPage();

        // 额度耗尽：结算并关闭界面
        if (remaining <= 0) {
            sendMessage("claim.depleted-close");
            closeLater();
        }
    }

    /**
     * 检查玩家背包（主存储 36 格）是否放得下整组物品
     *
     * @param stack 待放入的物品
     * @return true=空间充足
     */
    private boolean hasEnoughSpace(ItemStack stack) {
        int need = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());

        ItemStack[] contents = viewer.getInventory().getStorageContents();
        for (ItemStack slot : contents) {
            if (slot == null || slot.getType() == Material.AIR) {
                need -= max;
            } else if (slot.isSimilar(stack)) {
                need -= Math.max(0, max - slot.getAmount());
            }
            if (need <= 0) {
                return true;
            }
        }
        return need <= 0;
    }

    /**
     * 延迟 1 tick 关闭界面（避免在事件处理过程中直接关闭容器引发客户端异常）
     */
    private void closeLater() {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (viewer.isOnline()) {
                    viewer.closeInventory();
                }
            }
        });
    }

    /**
     * 处理界面关闭：结算本次会话并上报管理员
     */
    private void handleClose(boolean quit) {
        if (!quit && backToMenu) {
            reopenMenuLater();
        }
        if (reported || sessionCount <= 0) {
            reported = true;
            return;
        }
        reported = true;

        // 拼接物品汇总，例如「钻石剑×1、石头×64」
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : sessionItems.entrySet()) {
            if (summary.length() > 0) {
                summary.append("、");
            }
            summary.append(entry.getKey()).append("×").append(entry.getValue());
        }

        ClaimGrant grant = plugin.getClaimManager().getGrant(viewer.getName(), type);
        int remainingAfter = (grant == null || !grant.isUsable()) ? 0 : grant.getRemaining();

        ClaimLog log = new ClaimLog(-1L, viewer.getName(), type, snapshotId, snapshotTime,
                summary.toString(), sessionCount, remainingAfter,
                System.currentTimeMillis() / 1000L);

        plugin.getClaimReporter().submit(log);
    }

    /**
     * 下一 tick 回到备份列表
     *
     * <p>关闭可能是玩家按 ESC，也可能是别的插件 / 命令打开了新界面；
     * 只有玩家此刻什么界面都没开时才回列表，不抢别人的界面。
     * 这份额度已经用完时列表会自动换到还有额度的分类，都用完了就不再打开。</p>
     */
    private void reopenMenuLater() {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (!viewer.isOnline() || !ClaimMenuInterface.isIdle(viewer)) {
                    return;
                }
                if (plugin.getClaimManager().getUsableGrants(viewer.getName()).isEmpty()) {
                    return;
                }
                ClaimMenuInterface.open(plugin, viewer, type, menuPage);
            }
        });
    }

    /**
     * 获取物品的展示名称（含颜色码）
     *
     * <p>使用 YukiNoaAPI 的 {@code IItem.getName}：优先取自定义显示名，
     * 无自定义名时经 NMS 反射取物品原版/模组名称，比单纯读取 Material 名更贴近玩家所见。</p>
     *
     * @param stack 物品
     * @return 展示名称，获取失败时回退为材质名
     */
    private String getDisplayName(ItemStack stack) {
        if (stack == null) {
            return "?";
        }
        String name = IItem.getName(stack);
        if (name == null || name.isEmpty()) {
            return stack.getType().name();
        }
        return name;
    }

    /**
     * 获取去除颜色码的物品名称
     *
     * <p>用于写入领取日志与上报消息：日志需长期存库并在聊天栏拼接展示，
     * 保留颜色码会污染后续文本的颜色，因此统一去色。</p>
     *
     * @param stack 物品
     * @return 纯文本名称
     */
    private String getPlainName(ItemStack stack) {
        return stripColor(getDisplayName(stack));
    }

    /**
     * 移除字符串中的 Minecraft 颜色码（§ 及其后一位字符）
     *
     * @param input 原始字符串
     * @return 去色后的字符串
     */
    private String stripColor(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\u00A7' && i + 1 < input.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 向领取者发送一条语言文件消息
     *
     * @param path 语言键路径
     */
    private void sendMessage(String path) {
        viewer.sendMessage(IString.color(plugin.getLanguageManager().getMessage(path)));
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
     * 打开界面时向玩家发送的警示提示
     */
    public void sendOpenWarning() {
        ClaimGrant grant = plugin.getClaimManager().getUsableGrant(viewer.getName(), type);
        String remaining = String.valueOf(grant == null ? 0 : grant.getRemaining());
        String count = String.valueOf(itemMap.size());

        for (String line : plugin.getLanguageManager().getMessageList("claim.open-warning")) {
            viewer.sendMessage(IString.color(line
                    .replace("{remaining}", remaining)
                    .replace("{count}", count)));
        }
    }

    /**
     * 静态内部监听器
     * 全局只注册一次，通过 ACTIVE_GUIS 路由到具体实例
     */
    private static class GUIListener implements Listener {

        /**
         * 处理点击事件
         *
         * @param evt 点击事件
         */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventoryClick(InventoryClickEvent evt) {
            if (!(evt.getWhoClicked() instanceof Player)) {
                return;
            }
            if (!(evt.getInventory().getHolder() instanceof ClaimInterface)) {
                return;
            }

            Player player = (Player) evt.getWhoClicked();
            ClaimInterface gui = ACTIVE_GUIS.get(player.getUniqueId());
            if (gui == null) {
                // 兜底：找不到实例时同样禁止一切操作
                evt.setCancelled(true);
                return;
            }
            gui.handleClick(evt);
        }

        /**
         * 处理拖拽事件：领取界面完全禁止拖拽
         *
         * @param evt 拖拽事件
         */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventoryDrag(InventoryDragEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof ClaimInterface)) {
                return;
            }
            evt.setCancelled(true);
        }

        /**
         * 处理界面关闭事件
         *
         * @param evt 关闭事件
         */
        @EventHandler
        public void onInventoryClose(InventoryCloseEvent evt) {
            if (!(evt.getInventory().getHolder() instanceof ClaimInterface)) {
                return;
            }
            if (!(evt.getPlayer() instanceof Player)) {
                return;
            }

            Player player = (Player) evt.getPlayer();
            UUID uuid = player.getUniqueId();
            ClaimInterface gui = ACTIVE_GUIS.get(uuid);

            if (gui != null) {
                ClaimInterface holder = (ClaimInterface) evt.getInventory().getHolder();
                if (holder.getSerial().equals(gui.getSerial())) {
                    ACTIVE_GUIS.remove(uuid);
                    gui.handleClose(false);
                }
            }
        }

        /**
         * 处理玩家退出事件：确保掉线时也能完成会话结算与上报
         *
         * @param evt 退出事件
         */
        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent evt) {
            ClaimInterface gui = ACTIVE_GUIS.remove(evt.getPlayer().getUniqueId());
            if (gui != null) {
                gui.handleClose(true);
            }
        }
    }
}
