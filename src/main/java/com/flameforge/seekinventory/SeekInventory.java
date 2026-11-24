package com.flameforge.seekinventory;

import com.flameforge.seekinventory.claim.ClaimManager;
import com.flameforge.seekinventory.claim.ClaimReporter;
import com.flameforge.seekinventory.command.ClaimCommands;
import com.flameforge.seekinventory.command.ClaimTabComplete;
import com.flameforge.seekinventory.command.SeekTabComplete;
import com.flameforge.seekinventory.curios.CuriosHandler;
import com.flameforge.seekinventory.curios.CuriosSupport;
import com.flameforge.seekinventory.gui.ClaimInterface;
import com.flameforge.seekinventory.gui.ClaimMenuInterface;
import com.flameforge.seekinventory.gui.OfflineEndInterface;
import com.flameforge.seekinventory.gui.OfflineInterface;
import com.flameforge.seekinventory.gui.OnlineCuriosInterface;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bstats.bukkit.Metrics;
import com.flameforge.seekinventory.command.Commands;
import com.flameforge.seekinventory.command.Inv;
import com.flameforge.seekinventory.command.InvTabComplete;
import com.flameforge.seekinventory.config.ConfigManager;
import com.flameforge.seekinventory.config.DataManager;
import com.flameforge.seekinventory.config.LanguageManager;
import com.flameforge.seekinventory.listenevent.Events;
import pers.tany.yukinoaapi.interfacepart.other.IString;
import pers.tany.yukinoaapi.interfacepart.register.IRegister;
import pers.tany.yukinoaapi.interfacepart.serializer.ISerializer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SeekInventory 插件主类
 * 功能：查看和管理玩家背包/末影箱/饰品栏，支持历史快照（SQLite + 缓存优化）
 * 3.0.0 起支持管理员授权玩家自助领取历史备份物品
 *
 * @author Tany天炎
 * @version 3.0.0
 */
public class SeekInventory extends JavaPlugin {

    /** 插件实例 */
    private static SeekInventory instance;

    /** 配置管理器 */
    private ConfigManager configManager;

    /** 语言管理器 */
    private LanguageManager languageManager;

    /** 数据管理器 */
    private DataManager dataManager;

    /** 领取授权管理器 */
    private ClaimManager claimManager;

    /** 领取行为上报器 */
    private ClaimReporter claimReporter;

    /** 定时保存任务ID */
    private int autoSaveTaskId = -1;

    /** 定期清理任务ID */
    private int cleanupTaskId = -1;

    /** bStats统计对象 */
    private Metrics metrics;

    /** bStats服务ID */
    private static final int BSTATS_PLUGIN_ID = 29016;

    /** Curios 分批序列化分隔符 */
    public static final String CURIOS_BATCH_SEPARATOR = "|||";

    // ── 自动保存分批采集 ──────────────────────────────────────────────────

    /** 每 tick 最多采集几名玩家 */
    private static final int CAPTURE_MAX_PLAYERS_PER_TICK = 3;

    /** 每 tick 采集的时间预算（纳秒）：用完就留到下一 tick，但每 tick 至少采集一名，保证有进展 */
    private static final long CAPTURE_BUDGET_NANOS = 2_000_000L;

    /** 卸载时等写库线程写完的上限（秒） */
    private static final long WRITER_SHUTDOWN_WAIT_SECONDS = 60L;

    /**
     * 这一轮自动保存还没采集的玩家（UUID，按开始时的在线顺序）。
     * 一轮开始时把当时在线的玩家全部排进来，之后每 tick 采几个；这一轮中途才上线的玩家不进这一轮，
     * 和以前「到点那一刻在线的玩家」是同一批人。
     */
    private final Deque<UUID> pendingCapture = new ArrayDeque<>();

    /** 这一轮已经采集好的快照，一轮采完一起交给写库线程 */
    private List<CapturedSnapshot> roundSnapshots;

    /** 这一轮的分批采集任务；null 表示当前没有进行中的一轮 */
    private BukkitTask roundTask;

    /** 这一轮开始的时间（毫秒），用于「整轮耗时」日志 */
    private long roundStartTime;

    /**
     * 单线程写库：自动保存和手动保存都排在这里，按交出的先后写入；卸载时等它写完再关连接池。
     * 以前用 Bukkit 的异步任务，卸载时连接池先关、还没写完的那批直接失败。
     */
    private ExecutorService snapshotWriter;

    /**
     * 已在主线程采集并序列化完成的玩家快照数据。
     *
     * <p>这里只保存纯字符串，后续异步线程只负责写数据库，不再访问 Player、Inventory、
     * Curios capability 或任何 NMS 对象。</p>
     */
    private static final class CapturedSnapshot {

        /** 玩家名。 */
        private final String playerName;

        /** 背包序列化数据。 */
        private final String inventoryData;

        /** 末影箱序列化数据。 */
        private final String enderChestData;

        /** Curios 序列化数据；读取失败时为 null。 */
        private final String curiosData;

        /** 当前服务器是否应当保存 Curios 快照。 */
        private final boolean curiosExpected;

        /**
         * 创建一份已完成主线程采集的快照。
         *
         * @param playerName 玩家名
         * @param inventoryData 背包序列化数据
         * @param enderChestData 末影箱序列化数据
         * @param curiosData Curios 序列化数据；读取失败时为 null
         * @param curiosExpected 当前环境是否启用了 Curios 支持
         */
        private CapturedSnapshot(String playerName, String inventoryData, String enderChestData,
                                 String curiosData, boolean curiosExpected) {
            this.playerName = playerName;
            this.inventoryData = inventoryData;
            this.enderChestData = enderChestData;
            this.curiosData = curiosData;
            this.curiosExpected = curiosExpected;
        }
    }

    /**
     * 插件启用时执行
     */
    @Override
    public void onEnable() {
        instance = this;

        // 初始化语言管理器
        languageManager = new LanguageManager(this);
        languageManager.loadLanguage();

        // 初始化配置管理器
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 初始化数据管理器（SQLite + 缓存）
        dataManager = new DataManager(this);
        dataManager.initialize();
        snapshotWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SeekInventory-SnapshotWriter");
            t.setDaemon(true);
            return t;
        });

        // 初始化领取模块（依赖 DataManager 已建立的连接池）
        claimManager = new ClaimManager(this);
        claimManager.initialize();
        claimReporter = new ClaimReporter(this);
        claimReporter.registerListener();

        // 注册命令
        Commands commands = new Commands(this);
        Inv invCommand = new Inv(this);
        ClaimCommands claimCommands = new ClaimCommands(this);

        // 创建独立的 Tab 补全
        SeekTabComplete seekTabComplete = new SeekTabComplete(this);
        InvTabComplete invTabComplete = new InvTabComplete(this);
        ClaimTabComplete claimTabComplete = new ClaimTabComplete(this);

        IRegister.registerCommands(this, "SeekInventory", commands);
        IRegister.registerCommands(this, "Inv", invCommand);
        IRegister.registerCommands(this, "SeekClaim", claimCommands);

        // 注册独立的 Tab 补全
        if (this.getCommand("SeekInventory") != null) {
            this.getCommand("SeekInventory").setTabCompleter(seekTabComplete);
        }
        if (this.getCommand("Inv") != null) {
            this.getCommand("Inv").setTabCompleter(invTabComplete);
        }
        if (this.getCommand("SeekClaim") != null) {
            this.getCommand("SeekClaim").setTabCompleter(claimTabComplete);
        }

        // 注册事件监听器
        IRegister.registerEvents(this, new Events(this));
        IRegister.registerEvents(this, new RoundQuitListener());

        // 启动定时保存任务
        startAutoSaveTask();

        // 启动定期清理任务
        startCleanupTask();

        // 初始化bStats统计
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        // 检测 Curios 模组，并在启动阶段主动验证反射处理器，避免等到第一次备份才发现失效。
        if (CuriosSupport.isAvailable()) {
            if (CuriosHandler.isInitialized()) {
                sendMessage(languageManager.getMessage("console.curios-detected"));
            } else {
                sendWarning(languageManager.getMessage("console.curios-handler-init-failed"));
            }
        }

        // 发送启用消息
        sendMessage(languageManager.getMessage("plugin.enabled"));
    }

    /**
     * 插件禁用时执行
     */
    @Override
    public void onDisable() {
        // 停止定时保存任务
        stopAutoSaveTask();

        // 正在分批进行的一轮：剩下的玩家在这里同步采完（卸载时的一次性开销，和以前每轮的开销相同），
        // 交给写库线程后等它写完，再关连接池；不放弃，免得这一轮里排在后面的玩家没有快照
        finishRoundNow();
        shutdownSnapshotWriter();

        // 停止定期清理任务
        stopCleanupTask();

        OfflineInterface.saveAndCloseAll();
        OfflineEndInterface.saveAndCloseAll();
        OfflineInterface.cleanup();
        OfflineEndInterface.cleanup();

        // 清理领取界面活跃实例，避免热重载后监听器重复注册
        ClaimInterface.cleanup();
        ClaimMenuInterface.cleanup();
        OnlineCuriosInterface.cleanup();

        // 关闭数据库连接
        if (dataManager != null) {
            dataManager.close();
        }

        // 发送禁用消息
        if (languageManager != null) {
            sendMessage(languageManager.getMessage("plugin.disabled"));
        }
    }

    /**
     * 启动自动保存任务
     */
    private void startAutoSaveTask() {
        if (!configManager.isAutoSave()) {
            sendMessage(languageManager.getMessage("console.auto-save-disabled"));
            return;
        }

        int intervalTicks = configManager.getSaveInterval() * 60 * 20; // 分钟转刻

        // 定时器必须运行在主线程，因为快照采集会读取 Bukkit Player、Inventory 和 Curios capability。
        // 采集按 tick 分批，真正的数据库写入交给写库线程，不会阻塞主线程等待 SQLite。
        autoSaveTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                saveAllSnapshots();
            }
        }.runTaskTimer(this, intervalTicks, intervalTicks).getTaskId();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("interval", String.valueOf(configManager.getSaveInterval()));
        sendMessage(languageManager.getMessage("console.auto-save-started", placeholders));
    }

    /**
     * 停止自动保存任务
     */
    private void stopAutoSaveTask() {
        if (autoSaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoSaveTaskId);
            autoSaveTaskId = -1;
        }
    }

    /**
     * 启动定期清理任务（每小时执行一次）
     * 同时执行按数量清理、按天数清理，以及领取模块的过期数据清理
     */
    private void startCleanupTask() {
        cleanupTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                // 按数量清理（每位玩家保留 maxSnapshots 条）
                dataManager.cleanAllOldSnapshots();

                // 按天数清理（删除超过 autoCleanDays 天的快照）
                int autoCleanDays = configManager.getAutoCleanDays();
                dataManager.cleanExpiredSnapshots(autoCleanDays);

                // 领取模块清理（过期授权、历史日志、孤儿领取记录）
                if (claimManager != null) {
                    claimManager.cleanup();
                }
            }
        // 每小时一次（3600 秒 * 20 tick）。首次推迟 5 分钟（6000 tick）：自动保存默认每 30 分钟一次、同样从启动时算起，
        // 不错开的话每隔一次自动保存就与清理在同一 tick 开始，清理删库时保存会等锁（原来直接失败丢快照）。
        }.runTaskTimerAsynchronously(this, 72000 + 6000, 72000).getTaskId();
    }

    /**
     * 停止定期清理任务
     */
    private void stopCleanupTask() {
        if (cleanupTaskId != -1) {
            Bukkit.getScheduler().cancelTask(cleanupTaskId);
            cleanupTaskId = -1;
        }
    }

    /**
     * 保存所有在线玩家的快照（开始一轮自动保存）。
     *
     * <p>线程模型分为两段：</p>
     * <ol>
     *   <li>主线程：读取 Player、背包、末影箱、Curios capability，并立即序列化为字符串；
     *       按每 tick 最多 {@value #CAPTURE_MAX_PLAYERS_PER_TICK} 名、{@value #CAPTURE_BUDGET_NANOS} 纳秒预算分批做
     *       （以前所有在线玩家在同一 tick 里采完，人多、饰品多时单次 30 ms 以上）；</li>
     *   <li>写库线程：一轮采完后把已经序列化好的字符串按顺序写入 SQLite。</li>
     * </ol>
     *
     * <p>同一名玩家的背包、末影箱、饰品仍在同一 tick 里采完，快照内部前后一致（拆开的话两次采集之间挪动的物品会两边都有或都没有）。
     * 这一轮里还没轮到就下线的玩家，在下线事件里当场采集（这时背包还在）；中途上线的不进这一轮。
     * 上一轮还没采完时又到点，这一次跳过（正常情况下一轮只需要几十 tick）。</p>
     *
     * <p>这样既避免 Mohist/Forge capability 被异步访问，也继续保证数据库 I/O 不占用服务器主线程。</p>
     */
    public void saveAllSnapshots() {
        if (!Bukkit.isPrimaryThread()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    saveAllSnapshots();
                }
            }.runTask(this);
            return;
        }

        if (roundTask != null) {
            getLogger().warning("上一轮自动保存还没采集完（剩 " + pendingCapture.size() + " 名玩家），本次跳过");
            return;
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }

        pendingCapture.clear();
        for (Player player : players) {
            if (player != null) {
                pendingCapture.add(player.getUniqueId());
            }
        }
        roundSnapshots = new ArrayList<>(pendingCapture.size());
        roundStartTime = System.currentTimeMillis();

        // 第一批就在这一 tick 做；没采完的从下一 tick 起每 tick 接着做
        roundTask = new BukkitRunnable() {
            @Override
            public void run() {
                captureRoundBatch();
            }
        }.runTaskTimer(this, 1L, 1L);
        captureRoundBatch();
    }

    /**
     * 采集这一轮的一批玩家（每 tick 调用一次）。采完最后一名就结束这一轮、交给写库线程。
     */
    private void captureRoundBatch() {
        if (roundTask == null) {
            return;
        }
        long start = System.nanoTime();
        int captured = 0;
        while (!pendingCapture.isEmpty()) {
            if (captured >= CAPTURE_MAX_PLAYERS_PER_TICK
                    || (captured > 0 && System.nanoTime() - start >= CAPTURE_BUDGET_NANOS)) {
                return;
            }
            Player player = Bukkit.getPlayer(pendingCapture.poll());
            if (player == null || !player.isOnline()) {
                // 已下线：下线事件里已经采过了
                continue;
            }
            captureIntoRound(player);
            captured++;
        }
        finishRound();
    }

    /**
     * 采集一名玩家并放进这一轮的结果；失败只打日志，不影响其他玩家
     *
     * @param player 在线玩家
     */
    private void captureIntoRound(Player player) {
        try {
            roundSnapshots.add(capturePlayerSnapshot(player));
        } catch (Exception e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("error", getErrorMessage(e));
            sendError(languageManager.getMessage("snapshot.save-player-failed", placeholders));
        }
    }

    /**
     * 这一轮里还没轮到的玩家下线：当场采集，不让他漏掉这一轮。
     * LOWEST：在别的插件处理下线（清背包、转移物品之类）之前看到的背包，最接近这一轮开始时的样子。
     */
    private final class RoundQuitListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerQuit(PlayerQuitEvent evt) {
            if (roundTask == null) {
                return;
            }
            Player player = evt.getPlayer();
            if (pendingCapture.remove(player.getUniqueId())) {
                captureIntoRound(player);
            }
        }
    }

    /**
     * 卸载时调用：进行中的一轮剩下的玩家同步采完，并交给写库线程
     */
    private void finishRoundNow() {
        if (roundTask == null) {
            return;
        }
        while (!pendingCapture.isEmpty()) {
            Player player = Bukkit.getPlayer(pendingCapture.poll());
            if (player != null && player.isOnline()) {
                captureIntoRound(player);
            }
        }
        finishRound();
    }

    /**
     * 结束这一轮：停掉分批任务，把采好的快照交给写库线程按顺序写入
     */
    private void finishRound() {
        if (roundTask != null) {
            roundTask.cancel();
            roundTask = null;
        }
        final List<CapturedSnapshot> capturedSnapshots = roundSnapshots;
        final long totalStartTime = roundStartTime;
        roundSnapshots = null;
        pendingCapture.clear();

        if (capturedSnapshots == null || capturedSnapshots.isEmpty()) {
            return;
        }

        // 写库线程按顺序写入，避免为每位玩家创建一个数据库任务导致连接池瞬时并发过高。
        submitWrite(() -> {
            int successCount = 0;

            for (CapturedSnapshot snapshot : capturedSnapshots) {
                if (persistCapturedSnapshot(snapshot)) {
                    successCount++;
                }
            }

            long totalElapsed = System.currentTimeMillis() - totalStartTime;

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("count", String.valueOf(successCount));
            placeholders.put("time", String.valueOf(totalElapsed));
            sendMessage(languageManager.getMessage("snapshot.batch-save-complete", placeholders));

            // Bukkit 广播必须回到主线程；插件正在卸载时不再广播。
            if (isEnabled()) {
                try {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            String message = languageManager.getMessage("snapshot.auto-save-complete");
                            Bukkit.broadcastMessage(IString.color(message));
                        }
                    }.runTask(SeekInventory.this);
                } catch (RuntimeException ignored) {
                    // 卸载过程中排不上任务
                }
            }
        });
    }

    /**
     * 把一次写库交给写库线程；写库线程已关闭时在当前线程直接写（只会发生在卸载收尾时）
     *
     * @param task 写库任务（只碰已序列化好的字符串和数据库）
     */
    private void submitWrite(Runnable task) {
        ExecutorService writer = snapshotWriter;
        if (writer != null) {
            try {
                writer.execute(task);
                return;
            } catch (RejectedExecutionException ignored) {
                // 已关闭，落到下面同步写
            }
        }
        task.run();
    }

    /**
     * 卸载时调用，必须在关闭连接池之前：等写库线程把已交出的快照写完
     */
    private void shutdownSnapshotWriter() {
        ExecutorService writer = snapshotWriter;
        snapshotWriter = null;
        if (writer == null) {
            return;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(WRITER_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                getLogger().severe("快照写库线程 " + WRITER_SHUTDOWN_WAIT_SECONDS + " 秒内没有写完，剩下的快照没有保存");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 在服务器主线程采集并序列化一名玩家的完整快照。
     *
     * <p>Curios 读取失败时不会伪造一条空饰品快照，而是把 Curios 数据标记为失败；背包和末影箱
     * 仍会正常保存。真正合法的“饰品栏为空”会序列化成一个空 Inventory，因此时间线能够正确记录
     * 玩家已经清空饰品的状态。</p>
     *
     * @param player 在线玩家
     * @return 已序列化完成的快照数据
     * @throws IllegalStateException 当前不在主线程或玩家已经离线
     */
    private CapturedSnapshot capturePlayerSnapshot(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("玩家快照只能在服务器主线程采集");
        }
        if (player == null || !player.isOnline()) {
            throw new IllegalStateException("玩家已离线，无法采集快照");
        }

        // 创建临时背包用于立即序列化，临时 Inventory 不会传递给异步线程。
        Inventory invTemp = Bukkit.createInventory(null, 45);
        Inventory endTemp = Bukkit.createInventory(null, 27);

        // 复制背包内容（处理装备槽映射）。
        for (int i = 0; i < 41; i++) {
            ItemStack itemStack = null;
            if (i == 36) {
                itemStack = player.getInventory().getItem(39);
            } else if (i == 37) {
                itemStack = player.getInventory().getItem(38);
            } else if (i == 38) {
                itemStack = player.getInventory().getItem(37);
            } else if (i == 39) {
                itemStack = player.getInventory().getItem(36);
            } else if (i < player.getInventory().getSize()) {
                // 1.8 及以下没有副手，背包只有 40 格
                try {
                    itemStack = player.getInventory().getItem(i);
                } catch (Exception e) {
                    getLogger().warning("读取玩家 " + player.getName() + " 背包槽位 " + i
                            + " 失败，已按空槽处理: " + getErrorMessage(e));
                }
            }
            invTemp.setItem(i, itemStack);
        }

        // 复制末影箱内容。
        for (int i = 0; i < 27; i++) {
            endTemp.setItem(i, player.getEnderChest().getItem(i));
        }

        String invData = ISerializer.serializerInventory(invTemp);
        String endData = ISerializer.serializerInventory(endTemp);

        boolean curiosExpected = CuriosSupport.isAvailable();
        String curiosData = null;

        if (curiosExpected) {
            try {
                List<ItemStack> curiosItems = CuriosHandler.getCuriosItems(player);
                // 即使饰品栏合法为空，也必须生成一条空快照，防止“最新记录”停留在旧饰品状态。
                curiosData = serializeCuriosItems(curiosItems);
            } catch (Exception e) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("error", getErrorMessage(e));
                sendWarning(languageManager.getMessage("snapshot.curios-save-failed", placeholders));
            }
        }

        return new CapturedSnapshot(player.getName(), invData, endData, curiosData, curiosExpected);
    }

    /**
     * 把 Curios 物品列表序列化为历史快照格式。
     *
     * <p>每批最多 54 件，多个批次继续使用旧版兼容的 {@link #CURIOS_BATCH_SEPARATOR} 连接。
     * 空列表也会生成一个 54 格空 Inventory 的序列化结果，从而明确表示“该时间点饰品栏为空”。</p>
     *
     * @param curiosItems 已从主线程读取的非空饰品列表
     * @return Curios 快照字符串，永不返回 null
     */
    private String serializeCuriosItems(List<ItemStack> curiosItems) {
        if (curiosItems == null || curiosItems.isEmpty()) {
            // 历史读取端固定使用 54 格容器反序列化 Curios；空快照也保持 54 格最稳妥。
            Inventory emptyCurios = Bukkit.createInventory(null, 54);
            return ISerializer.serializerInventory(emptyCurios);
        }

        StringBuilder curiosDataBuilder = new StringBuilder();
        int totalItems = curiosItems.size();
        int batchCount = (totalItems + 53) / 54;

        for (int batch = 0; batch < batchCount; batch++) {
            int batchStart = batch * 54;
            int batchEnd = Math.min(batchStart + 54, totalItems);
            int batchSize = batchEnd - batchStart;

            int invSize = calculateInventorySize(batchSize);
            Inventory curiosTemp = Bukkit.createInventory(null, invSize);

            for (int i = 0; i < batchSize; i++) {
                ItemStack item = curiosItems.get(batchStart + i);
                curiosTemp.setItem(i, item == null ? null : item.clone());
            }

            if (batch > 0) {
                curiosDataBuilder.append(CURIOS_BATCH_SEPARATOR);
            }
            curiosDataBuilder.append(ISerializer.serializerInventory(curiosTemp));
        }

        return curiosDataBuilder.toString();
    }

    /**
     * 把已经序列化的玩家快照写入数据库。
     *
     * <p>本方法只处理 String 和数据库，不访问 Bukkit Player/NMS/Curios，因此可以安全地在异步线程调用。</p>
     *
     * @param snapshot 主线程采集完成的快照
     * @return true 表示本次应保存的所有类型都写入成功
     */
    private boolean persistCapturedSnapshot(CapturedSnapshot snapshot) {
        long startTime = System.currentTimeMillis();

        boolean inventorySaved = dataManager.saveSnapshot(
                snapshot.playerName, DataManager.TYPE_INVENTORY, snapshot.inventoryData);
        boolean enderChestSaved = dataManager.saveSnapshot(
                snapshot.playerName, DataManager.TYPE_ENDERCHEST, snapshot.enderChestData);

        boolean curiosSaved = true;
        if (snapshot.curiosExpected) {
            // null 表示主线程读取 Curios 时发生了真实故障，不能用空快照掩盖。
            curiosSaved = snapshot.curiosData != null
                    && dataManager.saveSnapshot(snapshot.playerName, DataManager.TYPE_CURIOS, snapshot.curiosData);
        }

        boolean success = inventorySaved && enderChestSaved && curiosSaved;
        if (success) {
            long elapsed = System.currentTimeMillis() - startTime;
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", snapshot.playerName);
            placeholders.put("time", String.valueOf(elapsed));
            sendMessage(languageManager.getMessage("snapshot.save-player-success", placeholders));
        }

        return success;
    }

    /**
     * 计算序列化用的库存大小（向上取整到 9 的倍数，范围 9-54）。
     *
     * @param itemCount 物品数量
     * @return 库存大小
     */
    private int calculateInventorySize(int itemCount) {
        if (itemCount <= 0) {
            return 9;
        }
        int size = ((itemCount + 8) / 9) * 9;
        return Math.min(54, Math.max(9, size));
    }

    /**
     * 保存单个玩家的快照（用于手动保存命令）。
     *
     * <p>如果调用方不在主线程，会先切回主线程完成数据采集；采集完成后交给写库线程写数据库。</p>
     *
     * @param player 玩家
     */
    public void savePlayerSnapshot(final Player player) {
        if (!Bukkit.isPrimaryThread()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    savePlayerSnapshot(player);
                }
            }.runTask(this);
            return;
        }

        final CapturedSnapshot snapshot;
        try {
            snapshot = capturePlayerSnapshot(player);
        } catch (Exception e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player == null ? "Unknown" : player.getName());
            placeholders.put("error", getErrorMessage(e));
            sendError(languageManager.getMessage("snapshot.save-player-failed", placeholders));
            return;
        }

        submitWrite(() -> persistCapturedSnapshot(snapshot));
    }

    /**
     * 提取异常中最有用的错误信息，避免日志只得到 null。
     *
     * @param throwable 异常
     * @return 非空错误描述
     */
    private String getErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown";
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isEmpty()) {
            return current.getClass().getName();
        }
        return current.getClass().getName() + ": " + message;
    }

    /**
     * 发送控制台消息
     *
     * @param message 消息内容
     */
    public void sendMessage(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /**
     * 发送警告消息
     *
     * @param message 消息内容
     */
    public void sendWarning(String message) {
        if (languageManager != null) {
            String prefix = languageManager.getMessage("console.warning-prefix");
            Bukkit.getConsoleSender().sendMessage(prefix + message);
        } else {
            Bukkit.getConsoleSender().sendMessage("§e[WARNING] " + message);
        }
    }

    /**
     * 发送错误消息
     *
     * @param message 消息内容
     */
    public void sendError(String message) {
        if (languageManager != null) {
            String prefix = languageManager.getMessage("console.error-prefix");
            Bukkit.getConsoleSender().sendMessage(prefix + message);
        } else {
            Bukkit.getConsoleSender().sendMessage("§c[ERROR] " + message);
        }
    }

    // Getter 方法

    public static SeekInventory getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    /**
     * 获取领取授权管理器
     *
     * @return 领取授权管理器
     */
    public ClaimManager getClaimManager() {
        return claimManager;
    }

    /**
     * 获取领取行为上报器
     *
     * @return 领取行为上报器
     */
    public ClaimReporter getClaimReporter() {
        return claimReporter;
    }
}
